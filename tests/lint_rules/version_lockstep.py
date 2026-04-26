"""
version_lockstep.py — cross-check every driver definition() version: field against
levoitManifest.json.

Rule 20 checks:
  - Every .groovy file in Drivers/Levoit/ has a version: field inside definition()
  - That field's value matches the package version in levoitManifest.json

All drivers must carry the same version as the manifest at every release cut.
Per-driver drift (e.g. only one driver at v2.0.1 while others stay at v2.0) is
not supported in this fork — version: in definition() is HPM metadata, not a
per-driver runtime indicator.
"""

import json
import re
from pathlib import Path


def _making_finding(severity, rule_id, title, file_str, lineno, context, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": file_str,
        "line": lineno,
        "context": context,
        "why": why,
        "fix": fix,
    }


def _extract_definition_block(source: str):
    """
    Return (block_text, start_line) for the definition(...) named-argument span,
    or (None, 1) if no definition() block is found.

    Strategy: locate 'definition(' then consume characters until the closing ')'
    that precedes the capability block's '{'. This handles multi-line blocks and
    description: strings that contain parentheses, because the closing ')' of a
    Groovy named-argument list is always followed (possibly after whitespace) by
    '{' or a newline+'{'.

    We use a compiled regex with DOTALL that matches from 'definition(' through
    the first ')' that is followed by optional whitespace and then '{'.  This
    covers both the compact style:
        definition(name: "...", version: "...") {
    and the expanded style:
        definition(
            name: "...",
            version: "...") {
    or with a separate opening brace on the next line:
        definition(
            name: "...",
            version: "..."
        ) {

    Returns the *interior* of the parens (everything between the '(' and the
    closing ')') so callers can regex-search it for named args.
    """
    pattern = re.compile(r'(?s)definition\s*\((.+?)\)\s*\{')
    m = pattern.search(source)
    if not m:
        return None, 1
    block_interior = m.group(1)
    # Calculate line number of the 'definition(' token
    start_pos = m.start()
    start_line = source[:start_pos].count('\n') + 1
    return block_interior, start_line


def _extract_version_from_block(block_interior: str):
    """
    Return the version string from inside a definition() block's named-arg span,
    or None if no version: field is present.
    """
    m = re.search(r'version\s*:\s*"([^"]+)"', block_interior)
    if not m:
        return None
    return m.group(1)


def check_rule20_version_lockstep(repo_root: Path, config: dict):
    """
    Verify that every Drivers/Levoit/*.groovy definition() block carries a
    version: field whose value matches levoitManifest.json's version field.
    Returns list of findings.
    """
    findings = []

    manifest_path = repo_root / "levoitManifest.json"
    drivers_dir = repo_root / "Drivers" / "Levoit"

    # --- Read manifest version ---
    if not manifest_path.exists():
        # manifest_consistency (rule 18) already fires on this; don't double-report
        return findings

    try:
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    except json.JSONDecodeError:
        # manifest_consistency (rule 18) already fires on this
        return findings

    manifest_version = manifest.get('version', '')
    if not manifest_version:
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE20_manifest_no_version",
            title="levoitManifest.json has no 'version' field",
            file_str="levoitManifest.json",
            lineno=1,
            context="",
            why="Rule 20 (Version Lockstep): the manifest version is the authoritative "
                "version for the entire driver pack. Without it, lockstep enforcement "
                "cannot run.",
            fix="Add a \"version\" field to levoitManifest.json (e.g. \"version\": \"2.0\").",
        ))
        return findings

    # --- Check each driver file ---
    if not drivers_dir.exists():
        return findings  # rule 18 already flags missing directory

    for driver_path in sorted(drivers_dir.glob('*.groovy')):
        try:
            source = driver_path.read_text(encoding='utf-8', errors='replace')
        except (OSError, IOError):
            continue  # unreadable files caught by per-file rules

        rel_path = str(driver_path.relative_to(repo_root)).replace('\\', '/')
        block_interior, def_lineno = _extract_definition_block(source)

        if block_interior is None:
            # No definition() block — not a conventional driver (e.g. helper script).
            # Don't flag it; rule 18 / doc_sync will catch files that shouldn't be present.
            continue

        driver_version = _extract_version_from_block(block_interior)

        if driver_version is None:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE20_missing_version_field",
                title=f"Driver missing version: field in definition(): {driver_path.name}",
                file_str=rel_path,
                lineno=def_lineno,
                context=f"    (no version: field found in definition() block)",
                why="Rule 20 (Version Lockstep): all drivers must carry a version: field in "
                    "their definition() block matching the package version in "
                    "levoitManifest.json. This gives users a stable 'what version am I "
                    "running' anchor in the Hubitat UI and lets HPM track update availability.",
                fix="Add    version: \"" + manifest_version + "\"\n"
                    "inside the definition() named-argument list, immediately before "
                    "documentationLink: (or before importUrl: / singleThreaded: if "
                    "documentationLink: is absent). "
                    "Alternatively, run /cut-release which keeps all version: fields in lockstep.",
            ))
        elif driver_version != manifest_version:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE20_version_drift",
                title=f"Driver version: field drifts from manifest version: {driver_path.name}",
                file_str=rel_path,
                lineno=def_lineno,
                context=f"    version: \"{driver_version}\" (manifest says \"{manifest_version}\")",
                why="Rule 20 (Version Lockstep): all drivers must carry the same version value "
                    "as levoitManifest.json. Drift means HPM may show an incorrect version in "
                    "the Hubitat UI, and /cut-release will flag the mismatch as unresolved.",
                fix=f"Update the version: field in {driver_path.name}'s definition() block "
                    f"from \"{driver_version}\" to \"{manifest_version}\" to match the manifest. "
                    "Alternatively, run /cut-release which keeps all version: fields in lockstep.",
            ))

    return findings
