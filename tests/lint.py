#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["pyyaml"]
# ///
"""
lint.py — Static structural linter for the level99/Hubitat-VeSync driver pack.

Checks driver source files for structural bug patterns and hygiene issues
that can be detected without running the code. Complements the Spock unit
test harness, which tests behavior at runtime.

Usage:
    uv run --python 3.12 tests/lint.py [options]

    Options:
      --paths PATH [PATH ...]   Files or directories to lint (default: Drivers/ + tests/)
      --strict                  Treat WARN as FAIL (used in CI)
      --json                    Machine-readable JSON output
      --config PATH             Lint config file (default: tests/lint_config.yaml)

Exit codes:
    0  PASS  — no FAIL findings (WARNs allowed in non-strict mode)
    1  FAIL  — one or more FAIL findings, OR any finding in --strict mode
    2  WARN  — no FAILs but one or more WARNs (non-strict mode only)
"""

import argparse
import json
import os
import sys
from pathlib import Path


# ---------------------------------------------------------------------------
# Exemption handling
# ---------------------------------------------------------------------------

def _validate_exemptions(exemptions: list) -> None:
    """
    Validate exemption entries from config. Raises ValueError on bad entries.
    Each entry must have rule_id (str), file (str), and reason (non-empty str).
    """
    for i, entry in enumerate(exemptions):
        if not isinstance(entry, dict):
            raise ValueError(f"Exemption #{i} is not a mapping: {entry!r}")
        rule_id = entry.get('rule_id', '')
        file_ = entry.get('file', '')
        reason = entry.get('reason', '')
        if not rule_id:
            raise ValueError(f"Exemption #{i} missing required field 'rule_id'")
        if not file_:
            raise ValueError(f"Exemption #{i} missing required field 'file'")
        if not reason or not str(reason).strip():
            raise ValueError(
                f"Exemption #{i} ({rule_id!r} on {file_!r}) has empty 'reason'. "
                "Every exemption must have a substantive explanation."
            )


def _build_exemption_set(config: dict) -> set:
    """
    Parse exemptions from config, validate them, and return a set of
    (rule_id, normalized_file) pairs. File paths are normalized to forward
    slashes for cross-platform comparison.
    Also validates that rule_ids look plausible (non-empty string).
    Returns empty set and prints a warning if validation fails.
    """
    raw = config.get('exemptions', [])
    if not raw:
        return set()
    try:
        _validate_exemptions(raw)
    except ValueError as e:
        print(f"[ERROR] lint_config.yaml exemptions invalid: {e}", file=sys.stderr)
        sys.exit(1)
    result = set()
    for entry in raw:
        rule_id = str(entry['rule_id']).strip()
        file_ = str(entry['file']).replace('\\', '/').strip()
        result.add((rule_id, file_))
    return result


def _apply_exemptions(findings: list, exemption_set: set, repo_root: Path) -> tuple:
    """
    Filter findings that match an exemption (rule_id, file) pair.
    Returns (kept_findings, exempted_count, unused_exemptions).
    File paths in findings are normalized to forward slashes for comparison.
    unused_exemptions is the subset of exemption_set that matched nothing —
    callers should warn about these (likely stale or mistyped entries).
    """
    if not exemption_set:
        return findings, 0, set()
    kept = []
    exempted = 0
    matched_exemptions = set()
    for f in findings:
        file_str = str(f.get('file', '')).replace('\\', '/')
        rule_id = f.get('rule_id', '')
        key = (rule_id, file_str)
        if key in exemption_set:
            exempted += 1
            matched_exemptions.add(key)
        else:
            kept.append(f)
    unused_exemptions = exemption_set - matched_exemptions
    return kept, exempted, unused_exemptions

# ---------------------------------------------------------------------------
# Load config
# ---------------------------------------------------------------------------

def load_config(config_path: Path) -> dict:
    """Load YAML config, falling back to empty dict if pyyaml unavailable."""
    if not config_path.exists():
        return {}
    try:
        import yaml
        with open(config_path, encoding='utf-8') as f:
            return yaml.safe_load(f) or {}
    except ImportError:
        # pyyaml not available — try JSON fallback (shouldn't happen with uv)
        try:
            with open(config_path.with_suffix('.json'), encoding='utf-8') as f:
                return json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return {}
    except Exception:
        return {}


# ---------------------------------------------------------------------------
# Rule registry + per-file runners
# ---------------------------------------------------------------------------

def _collect_per_file_rules():
    """Import all rule modules and return a flat list of per-file rule functions."""
    from lint_rules import bug_patterns, logging_discipline, sandbox_safety, pii_scan, reentrance
    rules = []
    for module in [bug_patterns, logging_discipline, sandbox_safety, pii_scan, reentrance]:
        rules.extend(module.ALL_RULES)
    return rules


def _collect_repo_level_rules():
    """Return list of (check_fn, needs_repo_root) for repo-wide checks."""
    from lint_rules import manifest_consistency, doc_sync, version_lockstep, readme_devices_sync
    return [
        manifest_consistency.check_rule18_manifest_consistency,
        doc_sync.check_rule19_doc_sync,
        version_lockstep.check_rule20_version_lockstep,
        readme_devices_sync.check_rule21_readme_devices_sync,
    ]


# ---------------------------------------------------------------------------
# File collection
# ---------------------------------------------------------------------------

# Extensions scanned by per-file rules
SCANNED_EXTENSIONS = {
    '.groovy', '.md', '.json', '.yaml', '.yml', '.txt', '.gradle', '.xml', '.html', '.py',
}

# Directories to always skip
SKIP_DIRS = {
    '.git', '.gradle', 'build', '.gemini', 'gradle',
    '__pycache__', '.pytest_cache', 'node_modules',
}

# Files to always skip (relative path fragments)
SKIP_FILE_FRAGMENTS = {
    'gradlew', 'gradlew.bat', 'gradle-wrapper.jar',
}


def collect_files(paths: list[Path], repo_root: Path) -> list[Path]:
    """
    Walk each path; collect files appropriate for per-file linting.
    Returns deduplicated sorted list of absolute Paths.
    """
    collected = set()
    for p in paths:
        if p.is_file():
            collected.add(p.resolve())
        elif p.is_dir():
            for root, dirs, files in os.walk(p):
                root_path = Path(root)
                # Prune skip dirs
                dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
                for fname in files:
                    fpath = root_path / fname
                    ext = fpath.suffix.lower()
                    if ext not in SCANNED_EXTENSIONS:
                        continue
                    if fname in SKIP_FILE_FRAGMENTS:
                        continue
                    collected.add(fpath.resolve())

    return sorted(collected)


# ---------------------------------------------------------------------------
# Per-file lint runner
# ---------------------------------------------------------------------------

def lint_file(path: Path, rules: list, config: dict, repo_root: Path) -> list[dict]:
    """Run all per-file rules against a single file. Returns list of findings."""
    try:
        raw_text = path.read_text(encoding='utf-8', errors='replace')
    except (OSError, IOError) as e:
        return [{
            "severity": "WARN",
            "rule_id": "LINT_READ_ERROR",
            "title": f"Cannot read file: {e}",
            "file": str(path.relative_to(repo_root)).replace('\\', '/'),
            "line": 0,
            "context": "",
            "why": "Lint tool cannot read this file.",
            "fix": "Check file permissions.",
        }]

    raw_lines = raw_text.splitlines()

    from lint_rules.groovy_lite import clean_source
    _, cleaned_lines = clean_source(raw_text)

    findings = []
    for rule_fn in rules:
        try:
            result = rule_fn(
                path=path,
                raw_lines=raw_lines,
                cleaned_lines=cleaned_lines,
                raw_text=raw_text,
                config=config,
                rel_base=repo_root,
            )
            findings.extend(result)
        except Exception as e:
            findings.append({
                "severity": "WARN",
                "rule_id": "LINT_RULE_ERROR",
                "title": f"Rule {rule_fn.__name__} crashed: {e}",
                "file": str(path.relative_to(repo_root)).replace('\\', '/'),
                "line": 0,
                "context": "",
                "why": "Bug in the lint rule itself — please report.",
                "fix": "Check the lint rule implementation.",
            })

    return findings


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

def format_human(findings: list[dict], files_scanned: int, strict: bool,
                 exemptions_applied: int = 0) -> str:
    """Format findings as human-readable text. Returns the full output string."""
    lines = []

    for f in findings:
        sev = f.get('severity', 'WARN')
        rule = f.get('rule_id', '?')
        title = f.get('title', '(no title)')
        fpath = f.get('file', '?')
        lineno = f.get('line', 0)
        context = f.get('context', '')
        why = f.get('why', '')
        fix = f.get('fix', '')

        lines.append(f"\n[{sev}] {rule}: {title}")
        lines.append(f"  File: {fpath}:{lineno}")
        if context.strip():
            lines.append(f"  Source:")
            lines.append(context)
        if why:
            lines.append(f"  Why: {why}")
        if fix:
            lines.append(f"  Fix: {fix}")

    # Summary
    fail_count = sum(1 for f in findings if f.get('severity') == 'FAIL')
    warn_count = sum(1 for f in findings if f.get('severity') == 'WARN')

    if strict:
        effective_fail = fail_count + warn_count
        result_str = "FAIL" if effective_fail > 0 else "PASS"
        count_str = f"{effective_fail} finding(s) (strict mode: WARNs treated as FAILs)"
    else:
        if fail_count > 0:
            result_str = "FAIL"
        elif warn_count > 0:
            result_str = "WARN"
        else:
            result_str = "PASS"
        count_str = f"{fail_count} FAIL, {warn_count} WARN"

    lines.append(f"\nLint result: {result_str}")
    lines.append(f"  {count_str} across {files_scanned} files scanned.")
    if exemptions_applied > 0:
        lines.append(f"  {exemptions_applied} finding(s) suppressed by lint_config.yaml exemptions.")
    if findings:
        lines.append("Run with --json for machine-readable output.")

    return '\n'.join(lines)


def format_json(findings: list[dict], files_scanned: int, strict: bool,
                exemptions_applied: int = 0) -> str:
    """Format findings as JSON."""
    fail_count = sum(1 for f in findings if f.get('severity') == 'FAIL')
    warn_count = sum(1 for f in findings if f.get('severity') == 'WARN')

    if strict:
        result_str = "FAIL" if (fail_count + warn_count) > 0 else "PASS"
    else:
        if fail_count > 0:
            result_str = "FAIL"
        elif warn_count > 0:
            result_str = "WARN"
        else:
            result_str = "PASS"

    output = {
        "result": result_str,
        "files_scanned": files_scanned,
        "fail_count": fail_count,
        "warn_count": warn_count,
        "exemptions_applied": exemptions_applied,
        "findings": findings,
    }
    return json.dumps(output, indent=2)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Static structural linter for level99/Hubitat-VeSync driver pack.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        '--paths', nargs='+', type=Path,
        help="Files or directories to lint. Default: Drivers/ and tests/ subdirectories.",
    )
    parser.add_argument(
        '--strict', action='store_true',
        help="Treat WARN as FAIL (used in CI).",
    )
    parser.add_argument(
        '--json', action='store_true',
        help="Machine-readable JSON output.",
    )
    parser.add_argument(
        '--config', type=Path,
        help="Path to lint_config.yaml (default: tests/lint_config.yaml next to this script).",
    )
    args = parser.parse_args()

    # Locate repo root (parent of tests/)
    script_dir = Path(__file__).parent
    repo_root = script_dir.parent

    # Add tests/ to sys.path so lint_rules imports work
    if str(script_dir) not in sys.path:
        sys.path.insert(0, str(script_dir))

    # Load config
    config_path = args.config or (script_dir / 'lint_config.yaml')
    config = load_config(config_path)

    # Determine scan paths
    if args.paths:
        scan_paths = [p if p.is_absolute() else (repo_root / p) for p in args.paths]
    else:
        scan_paths = [
            repo_root / 'Drivers',
            repo_root / 'levoitManifest.json',
            repo_root / 'README.md',
            repo_root / 'CLAUDE.md',
            repo_root / 'TODO.md',
            repo_root / '.claude',
            repo_root / '.gemini',
        ]
        # Add tests/ dir itself (for lint hygiene checks on test files)
        tests_dir = repo_root / 'tests'
        if tests_dir.exists():
            scan_paths.append(tests_dir)

    # Load rule modules
    per_file_rules = _collect_per_file_rules()
    repo_level_rules = _collect_repo_level_rules()

    # Build exemption set from config (validates on load; exits if malformed)
    exemption_set = _build_exemption_set(config)

    # Collect files
    files = collect_files([p for p in scan_paths if p.exists()], repo_root)
    files_scanned = len(files)

    all_findings = []

    # Per-file rules
    for path in files:
        findings = lint_file(path, per_file_rules, config, repo_root)
        all_findings.extend(findings)

    # Repo-level rules (manifest, doc sync)
    for rule_fn in repo_level_rules:
        try:
            findings = rule_fn(repo_root=repo_root, config=config)
            all_findings.extend(findings)
        except Exception as e:
            all_findings.append({
                "severity": "WARN",
                "rule_id": "LINT_RULE_ERROR",
                "title": f"Repo-level rule {rule_fn.__name__} crashed: {e}",
                "file": "(repo-level)",
                "line": 0,
                "context": "",
                "why": "Bug in the lint rule itself — please report.",
                "fix": "Check the lint rule implementation.",
            })

    # Apply per-rule exemptions from lint_config.yaml
    all_findings, exemptions_applied, unused_exemptions = _apply_exemptions(
        all_findings, exemption_set, repo_root
    )

    # Warn about exemptions that matched no findings (stale or mistyped rule_id/file)
    for (rule_id, file_) in sorted(unused_exemptions):
        print(
            f"[WARN] Unused exemption in lint_config.yaml: rule_id={rule_id!r} file={file_!r} "
            f"matched no findings. Check for typo or stale entry.",
            file=sys.stderr,
        )
        # Add a WARN finding so it shows in --json output too
        all_findings.append({
            "severity": "WARN",
            "rule_id": "LINT_UNUSED_EXEMPTION",
            "title": f"Unused exemption: {rule_id!r} on {file_!r}",
            "file": "(lint-config)",
            "line": 0,
            "context": "",
            "why": "An exemption in lint_config.yaml matched no findings. Either the rule_id "
                   "is misspelled, the file path is wrong, or the exemption is stale (the "
                   "underlying issue was fixed and the exemption no longer needed).",
            "fix": f"Remove or correct the exemption for rule_id={rule_id!r} file={file_!r} "
                   "in tests/lint_config.yaml.",
        })

    # Sort findings: FAILs first, then WARNs; within each, by file+line
    all_findings.sort(key=lambda f: (
        0 if f.get('severity') == 'FAIL' else 1,
        f.get('file', ''),
        f.get('line', 0),
    ))

    # Output
    if args.json:
        print(format_json(all_findings, files_scanned, args.strict, exemptions_applied))
    else:
        print(format_human(all_findings, files_scanned, args.strict, exemptions_applied))

    # Exit code
    fail_count = sum(1 for f in all_findings if f.get('severity') == 'FAIL')
    warn_count = sum(1 for f in all_findings if f.get('severity') == 'WARN')

    if args.strict:
        sys.exit(1 if (fail_count + warn_count) > 0 else 0)
    else:
        if fail_count > 0:
            sys.exit(1)
        elif warn_count > 0:
            sys.exit(2)
        else:
            sys.exit(0)


if __name__ == '__main__':
    main()
