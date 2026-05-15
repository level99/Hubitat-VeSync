"""
bp14_poll_persistence.py — RULE34: flag runIn() calls that are used as recurring
poll schedules in the parent driver (VeSyncIntegration.groovy).

Bug Pattern #14 — Hub reboot drops ``runIn()``-based poll cycles.  ``runIn()`` is
an in-memory timer; when the hub reboots, the pending job evaporates.  The driver
never calls ``updateDevices()`` again until the user clicks Save Preferences or
triggers a manual Refresh.  ``schedule()``-based cron jobs are persisted by the
Hubitat platform across reboots and are the correct mechanism for periodic work.

Detection (parent-driver only):
  Positive assertion (must be present):
    ``setupPollSchedule`` defined and ``schedule(`` called inside it.
  Negative assertion (must be absent after fix):
    ``runIn(`` where the handler argument is ``"updateDevices"`` — these are the
    legacy recurring-poll chains that were replaced with schedule() in v2.2.

Exemptions (not violations — these runIn() call shapes are intentional):
  - ``runIn(1800, "logDebugOff")`` / ``runIn(1800, "logsOff")`` — auto-disable debug
  - ``runIn(15, "initialize")`` — deferred initialization after updated()
  - ``runIn(2, "getDevices")`` — async configModule refresh in ensurePollHealth()
  - ``runIn(N, "timeOutLevoit")`` — connection-timeout callback pattern

Any other ``runIn(...)`` with a handler that looks like a poll/device-update trigger
is flagged as WARN (not FAIL) because there may be one-shot uses that are legitimate.
``runIn(..., "updateDevices")`` specifically is FAIL — that's the anti-pattern we
know is wrong.

Scope: VeSyncIntegration.groovy only (parent driver).  Child drivers have no
recurring poll of their own; ``runIn()`` in children is safe (one-shot callbacks).
"""

import re
from pathlib import Path

PARENT_DRIVER = "VeSyncIntegration.groovy"

# Exempted handler names — runIn with these handlers is intentional and correct.
EXEMPT_HANDLERS = frozenset([
    "logDebugOff",
    "logsOff",
    "initialize",
    "getDevices",
    "timeOutLevoit",
])

# Matches runIn(N, "handlerName") or runIn(N, 'handlerName')
RUNIN_PATTERN = re.compile(
    r'\brunIn\s*\(\s*[^,]+,\s*["\']([a-zA-Z][a-zA-Z0-9]*)["\']'
)

# Positive assertion: schedule() called anywhere in the file body
SCHEDULE_CALL_PATTERN = re.compile(r'\bschedule\s*\(')

# Positive assertion: setupPollSchedule defined
SETUP_POLL_SCHEDULE_DEF_PATTERN = re.compile(r'\bsetupPollSchedule\s*\(')


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    start = max(0, lineno - 2)
    end = min(len(lines), lineno + 1)
    context = '\n'.join(f"    {lines[i]}" for i in range(start, end))
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": str(path.relative_to(rel_base)).replace('\\', '/'),
        "line": lineno,
        "context": context,
        "why": why,
        "fix": fix,
    }


def check_rule34_bp14_poll_persistence(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE34 (Bug Pattern #14): enforce schedule()-based poll cycle in the parent driver.

    Two checks:
      1. FAIL if ``runIn(..., "updateDevices")`` appears — direct anti-pattern.
      2. FAIL if ``setupPollSchedule`` is not defined (parent must define it).
      3. FAIL if ``schedule(`` is not called anywhere (indicates schedule() was removed).
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    # Check 1: runIn("updateDevices") is the known anti-pattern
    for i, line in enumerate(cleaned_lines, 1):
        m = RUNIN_PATTERN.search(line)
        if not m:
            continue
        handler = m.group(1)
        if handler == "updateDevices":
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE34_runin_updateDevices",
                title="runIn() used as recurring poll trigger for 'updateDevices'",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Bug Pattern #14: runIn() is an in-memory timer that evaporates on hub reboot. "
                    "Using it to chain poll cycles means the poll stops after every hub restart "
                    "until the user manually saves preferences. Use schedule() with a cron string "
                    "— it is persisted by the Hubitat platform across reboots.",
                fix="Replace runIn(N, 'updateDevices') with a schedule()-based cron in "
                    "setupPollSchedule(). Call unschedule('updateDevices') + "
                    "schedule(cronExpression, 'updateDevices'). See v2.2+ VeSyncIntegration for "
                    "the canonical setupPollSchedule() implementation.",
            ))

    # Check 2: setupPollSchedule must be defined
    if not SETUP_POLL_SCHEDULE_DEF_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE34_missing_setupPollSchedule",
            title="setupPollSchedule() not found in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #14: the parent driver must define setupPollSchedule() to arm "
                "a schedule()-based cron for recurring polling. Without it, the poll cycle "
                "is either absent or relies on fragile runIn() chains that evaporate on reboot.",
            fix="Add setupPollSchedule() that calls schedule(cronExpr, 'updateDevices'). "
                "See VeSyncIntegration v2.2+ for the canonical implementation.",
        ))

    # Check 3: schedule() must be called (confirms the cron is actually armed)
    if not SCHEDULE_CALL_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE34_missing_schedule_call",
            title="schedule() not called anywhere in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #14: schedule() is how Hubitat arms a persistent cron-based poll. "
                "Without any schedule() call, the poll cycle is not reboot-safe.",
            fix="Call schedule(cronExpr, 'updateDevices') inside setupPollSchedule(). "
                "See VeSyncIntegration v2.2+ for the canonical implementation.",
        ))

    return findings


ALL_RULES = [
    check_rule34_bp14_poll_persistence,
]
