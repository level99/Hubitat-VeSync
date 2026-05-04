"""
bp16_watchdog_call_site.py — RULE25: flag driver files missing ensureDebugWatchdog() call.

Bug Pattern #16 — Hub reboot loses the runIn(1800, "logDebugOff") job, leaving
debugOutput permanently on after the reboot.

Every child/parent driver under Drivers/Levoit/ must call ensureDebugWatchdog() at
least once in executable code. The call checks how long debugOutput has been enabled
(state.debugEnabledAt) and auto-disables it if 30+ minutes have elapsed. This catches
the case where a hub reboot fired while the original runIn job was pending.

Absence of the call means debugOutput can stay on indefinitely after any hub reboot,
flooding logs until the user manually disables it.

Scope:
  - All .groovy files under Drivers/Levoit/ (children + parent).
  - Library files (those using ``library()`` instead of ``definition()``) are automatically
    skipped via ``is_library_file()`` — no lint_config.yaml exemption needed.
  - For other intentional exceptions (e.g. a pure event-listener with no poll cycle),
    add an exemption entry in tests/lint_config.yaml with a substantive reason.

Comment filter:
  - Lines that (after stripping leading whitespace) begin with `//` are skipped.
  - Multi-line block comments /* ... */ are already stripped by groovy_lite.clean_source;
    RULE25 operates on cleaned_lines so block-comment false-positives are avoided.

Design (KISS): minimum-viable call-site-existence check — identical complexity to
RULE23 (driver_app_only_api) but inverted polarity: FAIL if the call is ABSENT rather
than FAIL if it is PRESENT. Method definition lines (`private void ensureDebugWatchdog()`)
are excluded by a pre-filter so only true call invocations satisfy the check. Verifying
placement at the top of the correct entry method requires AST awareness; that is a v2.3
enhancement. Presence anywhere in the file is the v2.2 guard against regression in
newly-added drivers.

Library-aware (v2.5): when a driver delegates its poll/command entry methods to a shared
library via ``#include level99.<LibName>``, the ``ensureDebugWatchdog()`` call may live
in the library rather than the driver source.  We check the driver source first and fall
back to included library texts, matching the pattern used by BP1 and RULE15.  This
prevents false positives when future refactoring moves all watchdog call sites into a
shared library.

Library files themselves (those using ``library()`` instead of ``definition()``) are
always skipped via ``is_library_file()`` — libraries have no independent poll cycle and
no debug-output preference of their own.
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file, included_lib_texts


# Pattern that matches an invocation (not a definition) of ensureDebugWatchdog().
# Uses word-boundary \b and allows optional whitespace before the opening paren.
WATCHDOG_CALL_PATTERN = re.compile(r'\bensureDebugWatchdog\s*\(\s*\)')

# Pattern that matches a Groovy method *definition* line for ensureDebugWatchdog.
# Applied as a pre-filter so that definition lines do not satisfy the call-site check.
# Matches any line containing a modifier keyword immediately before the method name,
# e.g. `private void ensureDebugWatchdog() {` or `def ensureDebugWatchdog() {`.
WATCHDOG_DEF_PATTERN = re.compile(r'\b(?:private|public|protected|void|def)\s+ensureDebugWatchdog')

# Only scan Groovy driver files; skip test harness and Python lint files.
DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"


def check_rule25_bp16_watchdog_call_site(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE25 (Bug Pattern #16): Fail if a driver file has no ensureDebugWatchdog() call.

    Scans cleaned_lines (block-comment-stripped) for at least one occurrence of
    ensureDebugWatchdog() outside a line-comment. A single match anywhere in the file
    is sufficient to pass — the rule does not verify placement.

    Returns a single FAIL finding if the call is absent; empty list if present.
    """
    findings = []

    # Only check .groovy files in the driver directory
    if path.suffix != '.groovy':
        return findings

    # Normalize path for fragment check (forward slashes, cross-platform)
    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    # Skip Hubitat library files — they use library() not definition() and have no
    # poll cycle or debug-output preference. ensureDebugWatchdog() doesn't apply.
    if is_library_file(raw_text):
        return findings

    # Scan cleaned lines for at least one non-comment, non-definition call site
    # in the driver source itself.
    for cleaned_line in cleaned_lines:
        stripped = cleaned_line.lstrip()
        if stripped.startswith('//'):
            continue
        # Skip method definition lines (e.g. `private void ensureDebugWatchdog() {`).
        # Without this filter, the definition itself would satisfy the call-site check,
        # masking the case where all true call sites have been removed.
        if WATCHDOG_DEF_PATTERN.search(cleaned_line):
            continue
        if WATCHDOG_CALL_PATTERN.search(cleaned_line):
            # Found a live call site in the driver source — rule passes for this file
            return findings

    # No call site in the driver source — check included library texts as fallback.
    # This handles the case where a driver delegates its poll/command entry methods
    # entirely to a shared library (e.g. via #include level99.LevoitCorePurifier)
    # and the ensureDebugWatchdog() call lives in the library's update() body.
    # Pattern mirrors BP1 and RULE15 lib-awareness extensions (commit be9515d).
    from lint_rules.groovy_lite import clean_source as _clean_source
    for lib_text in included_lib_texts(raw_text, path):
        _, lib_cleaned = _clean_source(lib_text)
        for cleaned_line in lib_cleaned:
            stripped = cleaned_line.lstrip()
            if stripped.startswith('//'):
                continue
            if WATCHDOG_DEF_PATTERN.search(cleaned_line):
                continue
            if WATCHDOG_CALL_PATTERN.search(cleaned_line):
                # Found a live call site in an included library — rule passes
                return findings

    # No call site found in driver or any included library — FAIL
    findings.append({
        "severity": "FAIL",
        "rule_id": "RULE25_bp16_watchdog_call_site",
        "title": "Missing ensureDebugWatchdog() call (Bug Pattern #16)",
        "file": str(path.relative_to(rel_base)).replace('\\', '/'),
        "line": 0,
        "context": "",
        "why": (
            "Bug Pattern #16: when a hub reboots while debugOutput is true, the "
            "runIn(1800, 'logDebugOff') job is lost. Without ensureDebugWatchdog(), "
            "debugOutput stays on permanently after a reboot -- flooding logs indefinitely. "
            "The watchdog detects elapsed time > 30 min since debug was enabled and "
            "auto-disables debugOutput on the next poll."
        ),
        "fix": (
            "Add a call to ensureDebugWatchdog() in the driver's primary poll entry method "
            "(e.g. applyStatus(), update(status, nightLight), or updateDevices() for the "
            "parent). The method reads state.debugEnabledAt and auto-disables debugOutput "
            "if 30+ min have elapsed. See CLAUDE.md Bug Pattern #16 for the canonical "
            "pattern. If this driver intentionally has no poll cycle and no debug logging, "
            "add an exemption in tests/lint_config.yaml with a substantive reason string."
        ),
    })

    return findings


ALL_RULES = [check_rule25_bp16_watchdog_call_site]
