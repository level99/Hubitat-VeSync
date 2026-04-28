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
  - No exemptions expected: as of v2.2 every driver in the pack received the watchdog.
    If a future driver is intentionally exempt (e.g. a pure event-listener with no poll
    cycle), add an exemption entry in tests/lint_config.yaml with a substantive reason.

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
"""

import re
from pathlib import Path


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

    # Scan cleaned lines for at least one non-comment, non-definition call site
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
            # Found a live call site — rule passes for this file
            return findings

    # No call site found — FAIL
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
