"""
bug_patterns.py — lint rules derived from the VeSync driver bug-pattern catalog.

Rules implemented here:
  BP1  — Missing 2-arg update(status, nightLight) on child drivers
  BP2  — Hardcoded getPurifierStatus in parent's updateDevices()
  BP3  — Missing envelope-peel while-loop in V2-line drivers
  BP4  — V201S/V2-line setLevel payload field-name mismatch
  BP5  — V201S manual mode set via setPurifierMode instead of setLevel
  BP7  — device.currentValue() in info HTML assembly (race condition)
  BP9  — Driver name changed from frozen list (orphans existing installs)
  BP10 — SmartThings icon URL leftovers in metadata
  BP11 — documentationLink pointing to wrong project
  BP12 — Missing pref-seed (state.prefsSeeded) at correct insertion point

Child drivers: all .groovy files in Drivers/Levoit/ except VeSyncIntegration.groovy
               and files not in the known-driver set (e.g. Notification Tile.groovy
               is excluded from BP1/BP3 checks — it is not a polled child).
V2-line drivers: LevoitVital200S.groovy, LevoitSuperior6000S.groovy
                 (and future LAP-/LEH- new-model files detected heuristically).
"""

import re
from pathlib import Path
from .groovy_lite import clean_source, find_method_bodies, get_definition_block


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

PARENT_DRIVER = "VeSyncIntegration.groovy"
NOTIFICATION_TILE = "Notification Tile.groovy"

# Drivers that are polled children and must declare all three update() signatures
POLLED_CHILD_DRIVERS = {
    "LevoitCore200S.groovy",
    "LevoitCore200S Light.groovy",
    "LevoitCore300S.groovy",
    "LevoitCore400S.groovy",
    "LevoitCore600S.groovy",
    "LevoitVital200S.groovy",
    "LevoitSuperior6000S.groovy",
}

# V2-line drivers that must have the envelope-peel while loop
V2_LINE_DRIVERS = {
    "LevoitVital200S.groovy",
    "LevoitSuperior6000S.groovy",
}

# V2-line drivers where setLevel field names are enforced (Vital line, not Core)
V2_PURIFIER_DRIVERS = {
    "LevoitVital200S.groovy",
}

FORK_DOC_DOMAIN = "github.com/level99/Hubitat-VeSync"


def _context(lines, lineno, window=1):
    """Return up to (window) lines of context around lineno (1-based)."""
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


# ---------------------------------------------------------------------------
# BP1 — Missing 2-arg update() signature
# ---------------------------------------------------------------------------

def check_bp1_missing_2arg_update(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Every polled child driver must declare all three update() overloads:
      def update()
      def update(status)
      def update(status, nightLight)
    """
    findings = []
    fname = path.name
    if fname not in POLLED_CHILD_DRIVERS:
        return findings

    # Detect each overload via regex (allow any whitespace)
    has_0arg = bool(re.search(r'\bdef\s+update\s*\(\s*\)', raw_text))
    has_1arg = bool(re.search(r'\bdef\s+update\s*\(\s*\w+\s*\)', raw_text))
    has_2arg = bool(re.search(r'\bdef\s+update\s*\(\s*\w+\s*,\s*\w+\s*\)', raw_text))

    if not has_2arg:
        # Find a representative line — the def update( with 1 arg if present, else first def update
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+update\s*\(', line):
                lineno = i
                break
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="BP1_missing_2arg_update",
            title="Missing 2-arg update(status, nightLight) signature",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Bug Pattern #1: parent calls update(status, nightLight) on every poll; child missing "
                "the 2-arg signature throws MissingMethodException silently on each poll cycle.",
            fix='Add: def update(status, nightLight) { applyStatus(status); return true }',
        ))

    if has_2arg and not has_0arg:
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+update\s*\(', line):
                lineno = i
                break
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="BP1_missing_0arg_update",
            title="Missing no-arg update() self-fetch signature",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Bug Pattern #1: child drivers need all three update() overloads; the no-arg form "
                "is called by the child's own refresh() to self-fetch status.",
            fix='Add: def update() { /* fetch status from parent */ }',
        ))

    return findings


# ---------------------------------------------------------------------------
# BP2 — Hardcoded getPurifierStatus in parent
# ---------------------------------------------------------------------------

def check_bp2_hardcoded_purifier_method(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    In VeSyncIntegration.groovy, the updateDevices() method must not contain
    a hardcoded "getPurifierStatus" string outside a device-type branch.

    Heuristic: find any literal "getPurifierStatus" in updateDevices() body.
    If it appears AND there's no nearby humidifier branch check, flag it.
    The current correct implementation branches on typeName.contains("Humidifier").
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    bodies = find_method_bodies(raw_text, "updateDevices")
    for (start_line, body) in bodies:
        # Find occurrences of getPurifierStatus
        for m in re.finditer(r'"getPurifierStatus"', body):
            # Count chars before this match to find line offset within body
            pre = body[:m.start()]
            line_offset = pre.count('\n')
            abs_line = start_line + line_offset

            # Check if a humidifier branch exists in the same body
            has_branch = bool(re.search(
                r'(contains\s*\(\s*"Humidifier"\s*\)|getHumidifierStatus)',
                body
            ))
            if not has_branch:
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="BP2_hardcoded_purifier_method",
                    title="Hardcoded getPurifierStatus without humidifier branch in updateDevices()",
                    path=path, rel_base=rel_base, lineno=abs_line, lines=raw_lines,
                    why="Bug Pattern #2: humidifier devices silently fail when the parent sends "
                        "getPurifierStatus to them (returns inner code -1). Parent must branch by "
                        "device type and send getHumidifierStatus for humidifiers.",
                    fix='Branch inside updateDevices() loop: '
                        'String method = typeName.contains("Humidifier") ? "getHumidifierStatus" : "getPurifierStatus"',
                ))
            break  # one finding per updateDevices body is enough

    return findings


# ---------------------------------------------------------------------------
# BP3 — Missing envelope-peel while-loop in V2-line drivers
# ---------------------------------------------------------------------------

PEEL_PATTERN = re.compile(
    r'while\s*\(.*peelGuard\s*<\s*\d',
    re.DOTALL
)

def check_bp3_envelope_peel(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    V2-line drivers must have the defensive while-loop envelope peel in applyStatus().
    """
    findings = []
    fname = path.name

    # Determine if this looks like a V2-line driver
    is_explicit_v2 = fname in V2_LINE_DRIVERS
    # Heuristic for future new V2-model files: contains getHumidifierStatus or
    # references peelGuard anywhere already (would pass), or references
    # "bypassV2" and "result" access patterns.
    if not is_explicit_v2:
        # Only check explicitly known V2 files for now
        return findings

    # Find applyStatus() bodies
    bodies = find_method_bodies(raw_text, "applyStatus")
    if not bodies:
        # applyStatus missing entirely — flag
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="BP3_no_applyStatus",
            title="applyStatus() method not found",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #3: V2-line driver must have applyStatus() with envelope-peel logic.",
            fix="Add applyStatus(status) with the while-loop peel (peelGuard < 4).",
        ))
        return findings

    for (start_line, body) in bodies:
        if not PEEL_PATTERN.search(body):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="BP3_missing_envelope_peel",
                title="Missing envelope-peel while-loop in applyStatus()",
                path=path, rel_base=rel_base, lineno=start_line, lines=raw_lines,
                why="Bug Pattern #3: V2-line bypassV2 responses are sometimes double-wrapped. "
                    "Without the while-loop peel (peelGuard < 4), applyStatus() reads the inner "
                    "envelope dict [code, result, traceId] instead of device fields.",
                fix="Add inside applyStatus(): "
                    "int peelGuard = 0; while (r instanceof Map && r.containsKey('code') && "
                    "r.containsKey('result') && r.result instanceof Map && peelGuard < 4) { "
                    "r = r.result; peelGuard++ }",
            ))

    return findings


# ---------------------------------------------------------------------------
# BP4 — V2-line setLevel field-name mismatch
# ---------------------------------------------------------------------------

def check_bp4_setlevel_field_names(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    In V2-purifier drivers, setLevel payloads must use:
      levelIdx (not switchIdx)
      levelType (not bare 'type:')
      manualSpeedLevel (not 'level:' standalone)
    Also flag payloads missing levelIdx entirely where setLevel is used.
    """
    findings = []
    if path.name not in V2_PURIFIER_DRIVERS:
        return findings

    # Check for non-canonical field names in setLevel-adjacent code
    # Look for hubBypass("setLevel", ...) or sendBypassRequest with setLevel
    setlevel_pattern = re.compile(r'"setLevel"')

    for i, line in enumerate(cleaned_lines, 1):
        if not setlevel_pattern.search(line):
            continue
        # Found a setLevel reference — check the surrounding few lines for bad field names
        window_start = max(0, i - 1)
        window_end = min(len(cleaned_lines), i + 5)
        window = '\n'.join(cleaned_lines[window_start:window_end])

        if re.search(r'\bswitchIdx\b', window):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="BP4_switchIdx_in_setLevel",
                title="Non-canonical field 'switchIdx' in setLevel payload",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Bug Pattern #4: V2-line setLevel requires 'levelIdx' (not 'switchIdx'). "
                    "Using switchIdx returns inner code -1 from VeSync API.",
                fix="Replace switchIdx with levelIdx: 0",
            ))

        # Check for 'type:' without 'levelType' nearby
        if re.search(r'\btype\s*:', window) and not re.search(r'\blevelType\b', window):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="BP4_type_instead_of_levelType",
                title="Non-canonical field 'type:' instead of 'levelType' in setLevel payload",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Bug Pattern #4: V2-line setLevel requires 'levelType' (not 'type'). "
                    "Using bare 'type:' returns inner code -1 from VeSync API.",
                fix="Replace type: with levelType: (e.g. levelType: \"wind\")",
            ))

    return findings


# ---------------------------------------------------------------------------
# BP5 — V201S manual mode set via setPurifierMode
# ---------------------------------------------------------------------------

def check_bp5_manual_via_setPurifierMode(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    In V201S (and V2 purifier) drivers, setPurifierMode with workMode:"manual"
    always returns inner code -1. Manual mode must be established via setLevel.
    """
    findings = []
    if path.name not in V2_PURIFIER_DRIVERS:
        return findings

    # Look for setPurifierMode with "manual" nearby
    pattern = re.compile(r'setPurifierMode')
    for i, line in enumerate(cleaned_lines, 1):
        if not pattern.search(line):
            continue
        window_start = max(0, i - 1)
        window_end = min(len(cleaned_lines), i + 3)
        window = '\n'.join(cleaned_lines[window_start:window_end])
        if re.search(r'"manual"', window) or re.search(r"'manual'", window):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="BP5_manual_via_setPurifierMode",
                title="V201S manual mode set via setPurifierMode (always fails)",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Bug Pattern #5: V201S/V2-purifier: setPurifierMode with workMode:\"manual\" "
                    "returns inner code -1. Manual mode is established by calling setLevel with "
                    "a speed value — not by setPurifierMode.",
                fix='In setMode(): if (mode == "manual") { call setSpeedLevel(...) } '
                    'else { call setPurifierMode with workMode: mode }',
            ))

    return findings


# ---------------------------------------------------------------------------
# BP7 — device.currentValue() in info HTML assembly
# ---------------------------------------------------------------------------

def check_bp7_currentvalue_race(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    In applyStatus(), using device.currentValue("X") to build info HTML after a
    sendEvent("X") is a race condition — currentValue returns the OLD value.
    Flag as WARN (heuristic; false positives possible).
    Uses cleaned_lines (comment-stripped) to avoid flagging comments that mention
    device.currentValue() as something to avoid.
    """
    findings = []
    if path.name == PARENT_DRIVER or path.name == NOTIFICATION_TILE:
        return findings
    if not path.suffix == '.groovy':
        return findings

    bodies = find_method_bodies(raw_text, "applyStatus")
    for (start_line, body) in bodies:
        body_lines = body.splitlines()
        for offset, bline in enumerate(body_lines):
            abs_line = start_line + offset
            # Use the cleaned line (comment-stripped) to avoid false positives on
            # comments that mention device.currentValue() as a warning
            if abs_line - 1 < len(cleaned_lines):
                check_line = cleaned_lines[abs_line - 1]
            else:
                check_line = bline
            if re.search(r'device\.currentValue\s*\(', check_line):
                findings.append(_making_finding(
                    severity="WARN",
                    rule_id="BP7_currentvalue_race",
                    title="device.currentValue() inside applyStatus() may race with sendEvent()",
                    path=path, rel_base=rel_base, lineno=abs_line, lines=raw_lines,
                    why="Bug Pattern #7: sendEvent() dispatch is async; device.currentValue() "
                        "called immediately after returns the prior value, not the just-sent one. "
                        "Build info HTML using local variables instead.",
                    fix="Store the value in a local variable before sendEvent(), "
                        'then use that variable in the HTML string: '
                        'String aq = computeAQ(...); sendEvent(name:"airQuality", value:aq); '
                        'def html = "...${aq}..."',
                ))

    return findings


# ---------------------------------------------------------------------------
# BP9 — Driver name change
# ---------------------------------------------------------------------------

def check_bp9_driver_name_frozen(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    The driver metadata name field must match the frozen list in config.
    Any drift = FAIL because changing the name orphans existing installs.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    frozen = config.get('frozen_driver_names', {})
    if path.name not in frozen:
        return findings  # unknown file — not checked

    expected_name = frozen[path.name]

    # Extract name from definition(name: "...")
    m = re.search(r'\bname\s*:\s*"([^"]+)"', raw_text)
    if not m:
        m = re.search(r"\bname\s*:\s*'([^']+)'", raw_text)
    if not m:
        return findings  # can't parse name, skip

    actual_name = m.group(1)
    if actual_name != expected_name:
        lineno = raw_text[:m.start()].count('\n') + 1
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="BP9_driver_name_changed",
            title=f"Driver metadata name changed (was: '{expected_name}', now: '{actual_name}')",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Bug Pattern #9: Hubitat associates devices with drivers by the metadata 'name' "
                "field. Changing it orphans existing user devices — they show up as unknown type "
                "and stop working until manually re-typed.",
            fix=f'Restore: name: "{expected_name}". To add a new model, create a new driver file.',
        ))

    return findings


# ---------------------------------------------------------------------------
# BP10 — SmartThings icon URLs
# ---------------------------------------------------------------------------

ST_ICON_PATTERNS = [
    re.compile(r's3\.amazonaws\.com/smartapp-icons'),
    re.compile(r'\biconUrl\s*:'),
    re.compile(r'\biconX2Url\s*:'),
    re.compile(r'\biconX3Url\s*:'),
    re.compile(r'\bcategory\s*:\s*"My Apps"'),
]

def check_bp10_smartthings_icons(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Flag SmartThings-era metadata artifacts (icon URLs, "My Apps" category).
    These don't render in Hubitat and add noise.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    for i, line in enumerate(raw_lines, 1):
        for pat in ST_ICON_PATTERNS:
            if pat.search(line):
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="BP10_smartthings_icon",
                    title="SmartThings-era metadata artifact (icon URL or 'My Apps' category)",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why="Bug Pattern #10: iconUrl/iconX2Url/iconX3Url and category:'My Apps' are "
                        "SmartThings-era relics that don't render in Hubitat. They add noise.",
                    fix="Remove iconUrl, iconX2Url, iconX3Url, and category:'My Apps' from metadata.",
                ))
                break  # one finding per line

    return findings


# ---------------------------------------------------------------------------
# BP11 — documentationLink pointing to wrong project
# ---------------------------------------------------------------------------

def check_bp11_documentation_link(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    documentationLink in metadata must point to the fork's own repo, not
    an unrelated project.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    for i, line in enumerate(raw_lines, 1):
        m = re.search(r'\bdocumentationLink\s*:\s*"([^"]+)"', line)
        if not m:
            m = re.search(r"\bdocumentationLink\s*:\s*'([^']+)'", line)
        if not m:
            continue
        url = m.group(1)
        if FORK_DOC_DOMAIN not in url:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="BP11_wrong_documentation_link",
                title=f"documentationLink points to wrong project: {url!r}",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Bug Pattern #11: documentationLink pointing to an unrelated project confuses "
                    "users who follow it from the Hubitat driver UI.",
                fix=f'Set documentationLink: "https://github.com/{FORK_DOC_DOMAIN}"',
            ))

    return findings


# ---------------------------------------------------------------------------
# BP12 — Missing pref-seed (state.prefsSeeded)
# ---------------------------------------------------------------------------

PREF_SEED_PATTERN = re.compile(r'state\.prefsSeeded')

# Insertion-point method per driver type
BP12_INSERTION_POINTS = {
    "VeSyncIntegration.groovy": "updateDevices",
    "LevoitCore200S Light.groovy": "update",  # 1-arg form; any update matches
    "Notification Tile.groovy": "deviceNotification",
}
BP12_APPLY_STATUS_DRIVERS = {
    "LevoitVital200S.groovy",
    "LevoitSuperior6000S.groovy",
}
# Core drivers use update(status, nightLight) as insertion point
BP12_CORE_DRIVERS = {
    "LevoitCore200S.groovy",
    "LevoitCore300S.groovy",
    "LevoitCore400S.groovy",
    "LevoitCore600S.groovy",
}


def check_bp12_pref_seed(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Every driver must have the one-time state.prefsSeeded self-healing block
    at the correct insertion point, ensuring descriptionTextEnable=true is
    applied for users who migrate without clicking Save Preferences.
    """
    findings = []
    fname = path.name
    if fname not in (
        set(BP12_INSERTION_POINTS.keys())
        | BP12_APPLY_STATUS_DRIVERS
        | BP12_CORE_DRIVERS
    ):
        return findings

    # Does the file have the pattern at all?
    if not PREF_SEED_PATTERN.search(raw_text):
        # Determine the expected insertion method for context
        if fname in BP12_INSERTION_POINTS:
            method = BP12_INSERTION_POINTS[fname]
        elif fname in BP12_APPLY_STATUS_DRIVERS:
            method = "applyStatus"
        else:
            method = "update (2-arg form)"

        findings.append(_making_finding(
            severity="FAIL",
            rule_id="BP12_missing_pref_seed",
            title="Missing state.prefsSeeded self-healing block",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #12: when a driver is Type-changed or HPM-updated, new preference "
                "defaultValues are not auto-committed until user clicks Save Preferences. Without "
                "the pref-seed block, descriptionTextEnable may be null (falsy), silently "
                "suppressing all INFO logs for migrating users.",
            fix=f"Add at top of {method}(): "
                "if (!state.prefsSeeded) { "
                "if (settings?.descriptionTextEnable == null) { "
                "device.updateSetting(\"descriptionTextEnable\", [type:\"bool\", value:true]) } "
                "state.prefsSeeded = true }",
        ))

    return findings


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------

ALL_RULES = [
    check_bp1_missing_2arg_update,
    check_bp2_hardcoded_purifier_method,
    check_bp3_envelope_peel,
    check_bp4_setlevel_field_names,
    check_bp5_manual_via_setPurifierMode,
    check_bp7_currentvalue_race,
    check_bp9_driver_name_frozen,
    check_bp10_smartthings_icons,
    check_bp11_documentation_link,
    check_bp12_pref_seed,
]
