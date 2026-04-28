"""
driver_app_only_api.py — RULE23: flag app-only Hubitat APIs used in driver code.

Bug Pattern #15 — Driver code uses app-only API (subscribe / unsubscribe).

Hubitat distinguishes the app sandbox (SmartApps / installed apps) from the
driver sandbox. `subscribe(location, "eventName", handler)` and `unsubscribe()`
for location events are only wired into the app sandbox's metaclass. Calling
them in driver code produces `MissingMethodException` at ~2.75 Hz (every poll
tick). No legitimate driver use-case requires these APIs; periodic work uses
`schedule()`, not `subscribe(location, ...)`.

Scope:
  - All .groovy files under Drivers/Levoit/ (children + parent).
  - There is NO legitimate-driver-context use of subscribe() or unsubscribe()
    for location events. RULE23 fails on ALL drivers including the parent.

Comment filter:
  - Lines that (after stripping leading whitespace) begin with `//` are skipped.
  - Multi-line block comments /* ... */ are NOT deeply parsed -- the groovy_lite
    clean_source helper already strips them from cleaned_lines. RULE23 operates
    on cleaned_lines so block-comment false-positives are avoided automatically.

Design decision (KISS): rather than trying to parse Groovy call-site context,
RULE23 uses a whitespace-tolerant regex on cleaned lines. This avoids the
false-negative risk of a context-aware parser that might miss dynamic dispatch,
and keeps the rule simple to audit. The only class of false positive (a string
literal containing the word "subscribe(" in executable code, which is unusual)
is acceptable given the severity of BP15.
"""

import re
from pathlib import Path


# Patterns that unambiguously indicate app-only API usage in driver code.
# Uses word-boundary \b so "unsubscribe" is not matched by the "subscribe" pattern.
APP_ONLY_PATTERNS = [
    (
        re.compile(r'\bsubscribe\s*\('),
        "subscribe()",
        "subscribe(location, ...) is app-only. Use schedule() for periodic work in drivers.",
    ),
    (
        re.compile(r'\bunsubscribe\s*\('),
        "unsubscribe()",
        "unsubscribe() is app-only. Call unschedule() to cancel scheduled jobs in drivers.",
    ),
]

# Only scan Groovy driver files; skip test harness and Python lint files.
DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"


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


def check_rule23_driver_app_only_api(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE23 (Bug Pattern #15): Flag subscribe() and unsubscribe() calls in driver code.

    These are app-only APIs. Calling them in a Hubitat driver context throws
    MissingMethodException at runtime on every invocation (typically every poll tick).

    Operates on cleaned_lines (block-comment-stripped) to avoid flagging occurrences
    inside /* ... */ documentation blocks. Skips lines that start with '//' after
    stripping leading whitespace (inline comment filter).
    """
    findings = []

    # Only check .groovy files in the driver directory
    if path.suffix != '.groovy':
        return findings

    # Normalize path for fragment check (forward slashes, cross-platform)
    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    for i, cleaned_line in enumerate(cleaned_lines, 1):
        # Skip lines that are purely comments (// ...) after stripping leading whitespace.
        # Block comments are already stripped by groovy_lite.clean_source into spaces,
        # so cleaned_line won't contain /* ... */ content.
        stripped = cleaned_line.lstrip()
        if stripped.startswith('//'):
            continue

        for pattern, api_name, fix_msg in APP_ONLY_PATTERNS:
            if pattern.search(cleaned_line):
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE23_driver_app_only_api",
                    title=f"App-only API '{api_name}' called in driver code (Bug Pattern #15)",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=(
                        f"Bug Pattern #15: {api_name} is only available in the Hubitat app sandbox "
                        "(SmartApps / installed-app context). Calling it in driver code throws "
                        "MissingMethodException at runtime. In driver context, Hubitat does not "
                        "wire these methods into the driver sandbox's metaclass."
                    ),
                    fix=fix_msg,
                ))

    return findings


ALL_RULES = [check_rule23_driver_app_only_api]
