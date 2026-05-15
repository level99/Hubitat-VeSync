"""
bp17_poll_health.py — RULE35: enforce ensurePollHealth() definition and call-site
in VeSyncIntegration.groovy's updateDevices() method.

Bug Pattern #17 — Stale ``state.deviceList`` configModule causes silent empty-result
polls.  When a device's configModule is stale (device replaced, cloud-side rename,
firmware update), every subsequent poll returns an empty result.  Without a watchdog,
the empty-result accumulates silently — the user sees stale attribute values and no
error log.

Fix (canonical since v2.4):
  ``ensurePollHealth()`` is defined in the parent driver and called at the top of
  ``updateDevices()``.  It maintains a per-device ``consecutiveEmpty`` counter and
  triggers a ``getDevices()`` configModule refresh when the counter exceeds the
  threshold (5 consecutive empty polls), capped at 3 total self-heal attempts per
  device to avoid BP21 infinite-loop on genuinely-offline devices.

Checks (parent driver only):
  1. FAIL if ``ensurePollHealth`` is not defined (method body must exist).
  2. FAIL if ``ensurePollHealth`` is not called inside ``updateDevices()``.

Detection note:
  ``updateDevices()`` in this codebase is declared as ``def Boolean updateDevices()``
  (return type between ``def`` and the method name).  ``find_method_bodies()`` from
  groovy_lite uses ``def\\s+updateDevices\\s*\\(`` which does NOT match because
  ``Boolean`` intervenes.  This rule therefore uses a broader pattern that handles
  optional return-type tokens, then brace-counts the body directly.

Scope: VeSyncIntegration.groovy only.
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding, make_finding_for_path

PARENT_DRIVER = "VeSyncIntegration.groovy"

ENSURE_POLL_HEALTH_DEF_PATTERN = re.compile(
    r'\b(?:private\s+)?void\s+ensurePollHealth\s*\(\s*\)'
)

ENSURE_POLL_HEALTH_CALL_PATTERN = re.compile(
    r'\bensurePollHealth\s*\(\s*\)'
)

# Matches updateDevices() definition with optional return-type token between
# `def` and the method name.  The optional return-type group uses a non-whitespace
# character class so it cannot greedily consume the method name itself.
# Handles both same-line `def Boolean updateDevices() {` and next-line brace form.
UPDATEDEVICES_DEF_PATTERN = re.compile(
    r'\bdef\b\s+(?:[A-Za-z_][A-Za-z0-9_<>?\[\]]*\s+)?updateDevices\s*\(\s*\)'
)


def _extract_updateDevices_body(raw_text: str):
    """
    Find the body of ``updateDevices()`` using a broad pattern that handles
    optional return types (``def Boolean updateDevices()``).

    Returns (start_line_1based, body_text) or None if not found.
    """
    m = UPDATEDEVICES_DEF_PATTERN.search(raw_text)
    if not m:
        return None

    # Scan forward from the end of the match for the opening `{`
    brace_pos = raw_text.find('{', m.end())
    if brace_pos == -1:
        return None

    start_line = raw_text[:m.start()].count('\n') + 1

    # Brace-balance walk
    depth = 0
    pos = brace_pos
    body_end = brace_pos
    while pos < len(raw_text):
        ch = raw_text[pos]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                body_end = pos
                break
        pos += 1

    body = raw_text[brace_pos:body_end + 1]
    return start_line, body


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    return make_finding_for_path(severity, rule_id, title, path, rel_base, lineno, lines, why, fix)


def check_rule35_bp17_poll_health(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE35 (Bug Pattern #17): ensure ensurePollHealth() is defined and called in
    updateDevices() in VeSyncIntegration.groovy.
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    # Check 1: ensurePollHealth() must be defined
    if not ENSURE_POLL_HEALTH_DEF_PATTERN.search(raw_text):
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE35_missing_ensurePollHealth_def",
            title="ensurePollHealth() not defined in VeSyncIntegration.groovy",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #17: without ensurePollHealth(), stale configModule values cause "
                "silent empty-result polls indefinitely. The watchdog counts consecutive empty "
                "results per device and triggers a getDevices() refresh when the threshold is hit, "
                "capped at 3 self-heal attempts to avoid BP21 infinite-loop on offline devices.",
            fix="Add private void ensurePollHealth() that tracks consecutiveEmpty per device "
                "and calls getDevices() when the threshold is exceeded. See VeSyncIntegration "
                "v2.4+ for the canonical implementation.",
        ))

    # Check 2: ensurePollHealth() must be called inside updateDevices()
    result = _extract_updateDevices_body(raw_text)
    if result is None:
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE35_missing_updateDevices",
            title="updateDevices() not found — cannot verify ensurePollHealth() call",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #17: updateDevices() must exist and call ensurePollHealth() at its top.",
            fix="Add updateDevices() and call ensurePollHealth() at the top of its body.",
        ))
    else:
        start_line, body = result
        if not ENSURE_POLL_HEALTH_CALL_PATTERN.search(body):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE35_ensurePollHealth_not_called",
                title="ensurePollHealth() not called inside updateDevices()",
                path=path, rel_base=rel_base, lineno=start_line, lines=raw_lines,
                why="Bug Pattern #17: ensurePollHealth() must be called at the top of "
                    "updateDevices() so the watchdog fires on every poll cycle. "
                    "A definition without a call site provides no protection.",
                fix="Add ensurePollHealth() call at the top of updateDevices(), before "
                    "the device iteration loop. See VeSyncIntegration v2.4+ for placement.",
            ))

    return findings


ALL_RULES = [
    check_rule35_bp17_poll_health,
]
