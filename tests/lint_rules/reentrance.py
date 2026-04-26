"""
reentrance.py — re-entrance guard discipline check.

Rule 20: every state.X = true (for known re-entrance guard names: turningOn,
turningOff, driverReloading, reAuthInProgress) that appears inside a method
must have a corresponding state.remove('X') in a finally block in the same method.

This is a greedy heuristic. False positives are possible for:
  - Guards that are cleared in a different method (intentional split)
  - Guards set via conditional that the regex can't track

Severity: WARN (not FAIL) because the heuristic has bounded false-positive risk.
"""

import re
from pathlib import Path
from .groovy_lite import find_method_bodies, strip_block_comments

# Known re-entrance guard state keys
GUARD_KEYS = ['turningOn', 'turningOff', 'driverReloading', 'reAuthInProgress']


def _context(lines, lineno, window=1):
    start = max(0, lineno - 1 - window)
    end = min(len(lines), lineno + window)
    return '\n'.join(f"    {lines[i]}" for i in range(start, end))


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": str(path.relative_to(rel_base)).replace('\\', '/'),
        "line": lineno,
        "context": _context(lines, lineno),
        "why": why,
        "fix": fix,
    }


# Patterns for setting a guard
GUARD_SET_PATTERNS = {
    key: re.compile(r'\bstate\.' + re.escape(key) + r'\s*=\s*true\b')
    for key in GUARD_KEYS
}

# Patterns for clearing a guard (either state.remove or state.X = false)
GUARD_CLEAR_PATTERNS = {
    key: re.compile(
        r'(?:state\.remove\s*\(\s*[\'"]' + re.escape(key) + r'[\'"]\s*\)'
        r'|state\.' + re.escape(key) + r'\s*=\s*false\b)'
    )
    for key in GUARD_KEYS
}

# Pattern for a finally block
FINALLY_PATTERN = re.compile(r'\bfinally\s*\{')


def check_rule20_reentrance_guards(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Heuristic check: state.X = true for guard keys must be cleared in a finally block.
    Returns WARN findings only.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    # Work on block-comment-stripped text (preserve line numbers)
    cleaned_text = strip_block_comments(raw_text)
    cleaned_text_lines = cleaned_text.splitlines()

    for key in GUARD_KEYS:
        set_pat = GUARD_SET_PATTERNS[key]
        clear_pat = GUARD_CLEAR_PATTERNS[key]

        for i, line in enumerate(cleaned_text_lines, 1):
            if not set_pat.search(line):
                continue

            # Found a guard set — scan the surrounding method body for:
            # 1. A finally block
            # 2. The clear pattern inside that finally
            # We extract a window from the set line to end of reasonable method scope
            # (next 200 lines as heuristic; brace-balanced parsing is overkill here)
            method_window_end = min(len(cleaned_text_lines), i + 200)
            window_text = '\n'.join(cleaned_text_lines[i - 1:method_window_end])

            has_finally = bool(FINALLY_PATTERN.search(window_text))
            has_clear = bool(clear_pat.search(window_text))

            if not has_finally or not has_clear:
                missing = []
                if not has_finally:
                    missing.append("finally block")
                if not has_clear:
                    missing.append(f"state.remove('{key}') / state.{key}=false")

                findings.append(_making_finding(
                    severity="WARN",
                    rule_id="RULE20_reentrance_guard_unclosed",
                    title=f"Re-entrance guard state.{key}=true may not be cleared in finally",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=f"Rule 20 (Re-entrance Guard Discipline): if the method exits via "
                        f"exception after setting state.{key}=true and there is no finally "
                        f"block, the guard never clears. Subsequent commands silently no-op "
                        f"until hub reboot. Missing: {', '.join(missing)}.",
                    fix=f"Wrap the body after state.{key}=true in try {{ ... }} finally {{ "
                        f"state.remove('{key}') }}",
                ))

    return findings


ALL_RULES = [check_rule20_reentrance_guards]
