"""
bp22_network_breaker.py — RULE36: enforce dual network circuit-breaker invariants
in VeSyncIntegration.groovy.

Bug Pattern #22 — HTTP-error path log spam during network outages.  When the hub
loses internet connectivity, ``sendBypassRequest()`` previously logged ``logError``
on every poll cycle for every child device — e.g. 6 children × 1 poll/min = 6
ERROR lines per minute for the entire outage duration.

Fix (canonical since v2.4):
  Three state keys coordinate the dual circuit-breaker:
    - ``state.networkUnreachableSince`` (Long): epoch-ms when the outage started;
      null when network is reachable.
    - ``state.networkProbeInFlight`` (Boolean): true while a recovery probe is in
      flight from the top-level breaker; prevents per-child sendBypassRequest from
      re-blocking on the same poll cycle.
    - ``emitNetworkWarnIfDue()``: hourly WARN re-surface helper; called inside the
      network-unreachable branch so users see the outage is still active without
      log flooding.

  The dual circuit-breaker has two layers:
    Layer 1 (top-level in updateDevices()): skips the entire poll cycle when an
      outage is known, probing every 5 minutes for recovery.  Sets
      ``state.networkProbeInFlight`` before dispatching children, clears it in
      try/finally.
    Layer 2 (per-call in sendBypassRequest()): guards command-triggered calls
      (non-polling) when ``state.networkUnreachableSince != null && !state.networkProbeInFlight``.

Checks (parent driver only):
  1. FAIL if ``state.networkUnreachableSince`` not referenced (breaker state absent).
  2. FAIL if ``state.networkProbeInFlight`` not referenced (coordination flag absent).
  3. FAIL if ``emitNetworkWarnIfDue`` not defined (hourly-warn helper absent).

Scope: VeSyncIntegration.groovy only.
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding

PARENT_DRIVER = "VeSyncIntegration.groovy"

NETWORK_UNREACHABLE_SINCE_PATTERN = re.compile(r'\bstate\.networkUnreachableSince\b')
NETWORK_PROBE_IN_FLIGHT_PATTERN = re.compile(r'\bstate\.networkProbeInFlight\b')
EMIT_NETWORK_WARN_DEF_PATTERN = re.compile(
    r'\b(?:private\s+)?void\s+emitNetworkWarnIfDue\s*\(\s*\)'
)


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    return make_finding(severity, rule_id, title, str(path.relative_to(rel_base)).replace('\\', '/'), lineno, lines, why, fix)


def check_rule36_bp22_network_breaker(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE36 (Bug Pattern #22): enforce dual network circuit-breaker invariants in the
    parent driver — state.networkUnreachableSince, state.networkProbeInFlight, and
    emitNetworkWarnIfDue() must all be present.
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    # Check 1: state.networkUnreachableSince must be referenced
    if not NETWORK_UNREACHABLE_SINCE_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE36_missing_networkUnreachableSince",
            title="state.networkUnreachableSince not referenced in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #22: state.networkUnreachableSince is the primary circuit-breaker "
                "flag. It is set when the first network error of an outage is detected, cleared "
                "on recovery. Absence means the network-outage detection path is missing — "
                "the driver will log ERROR on every poll cycle during any network outage.",
            fix="Add network-outage detection in sendBypassRequest(): catch "
                "UnknownHostException/SocketTimeoutException/ConnectException, set "
                "state.networkUnreachableSince = now() on first hit, clear on recovery. "
                "See VeSyncIntegration v2.4+ for the canonical dual circuit-breaker.",
        ))

    # Check 2: state.networkProbeInFlight must be referenced
    if not NETWORK_PROBE_IN_FLIGHT_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE36_missing_networkProbeInFlight",
            title="state.networkProbeInFlight not referenced in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #22: state.networkProbeInFlight coordinates the two circuit-breaker "
                "layers. Layer 1 (top-level updateDevices) sets it before dispatching recovery-probe "
                "children; Layer 2 (per-call sendBypassRequest) skips the breaker when it is set, "
                "allowing the probe child's HTTP call through. Without the flag, Layer 2 blocks "
                "ALL children including the designated recovery probe, creating a permanent deadlock "
                "where the outage can never be detected as resolved.",
            fix="Add state.networkProbeInFlight = true/false coordination in both updateDevices() "
                "(set in try/finally wrap) and sendBypassRequest() (check before breaker skip). "
                "See VeSyncIntegration v2.4+ for the canonical implementation.",
        ))

    # Check 3: emitNetworkWarnIfDue() must be defined
    if not EMIT_NETWORK_WARN_DEF_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE36_missing_emitNetworkWarnIfDue",
            title="emitNetworkWarnIfDue() not defined in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #22: emitNetworkWarnIfDue() re-surfaces a WARN log approximately "
                "every hour while an outage is ongoing, so the user sees confirmation that the "
                "hub is still unable to reach VeSync without log flooding. Without it, after the "
                "first one-time WARN there is no further user notification for the duration of "
                "the outage.",
            fix="Add private void emitNetworkWarnIfDue() that checks "
                "(now() - state.lastNetworkWarnAt) > 3600000 and, if so, logs a WARN with "
                "outage duration and updates state.lastNetworkWarnAt. Call it in the "
                "network-unreachable branch of updateDevices(). See VeSyncIntegration v2.4+.",
        ))

    return findings


ALL_RULES = [
    check_rule36_bp22_network_breaker,
]
