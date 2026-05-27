"""
bug_patterns.py — lint rules derived from the VeSync driver bug-pattern catalog.

Rules implemented here:
  BP1  — Missing 2-arg update(status, nightLight) on child drivers
         (also enforces 1-arg and 0-arg overloads — all three required per BP1)
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

Driver family classification (source: pyvesync device_map.py + CLAUDE.md):
  V2-line (bypassV2 double-envelope peel required):
    Vital purifiers (VeSyncAirBaseV2): Vital 100S, Vital 200S
    V2 fans (VeSyncAirBaseV2): EverestAir, Sprout Air
    V2 humidifiers (VeSyncHumid1000S, VeSyncSproutHumid, LV600SHubConnect variant):
      LV600S HubConnect, OasisMist 1000S, Sprout Humidifier, Superior 6000S
    Generic: LevoitGeneric
  V2-purifier (V2-style setLevel field names — levelIdx/manualSpeedLevel/levelType):
    Vital 100S, Vital 200S, EverestAir, Sprout Air
    (NOT humidifiers — they use setVirtualLevel / virtualLevel fields)
  Classic-line (VeSyncAirBypass older payload conventions):
    Core 200S/300S/400S/600S, Core 200S Light
  Classic-humidifier (VeSyncHumid200300S — enabled:bool/target_humidity snake_case):
    Classic 200S/300S, Dual 200S, LV600S, OasisMist 450S
"""

import re
from pathlib import Path
from .groovy_lite import clean_source, find_method_bodies, get_definition_block
from ._helpers import included_lib_texts
from lint_rules._helpers import make_finding, make_finding_for_path


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

PARENT_DRIVER = "VeSyncIntegration.groovy"
NOTIFICATION_TILE = "Notification Tile.groovy"

# Drivers that are polled children and must declare all three update() signatures.
# When a driver delegates its method bodies to a shared library via #include, the
# regex search is expanded to include the library text (see check_bp1_missing_2arg_update).
# Includes all currently-shipping child drivers; libs (LevoitChildBaseLib, etc.) excluded
# because library() files are not polled by the parent directly.
POLLED_CHILD_DRIVERS = {
    # Core purifier line (VeSyncAirBypass)
    "LevoitCore200S.groovy",
    "LevoitCore200S Light.groovy",
    "LevoitCore300S.groovy",
    "LevoitCore400S.groovy",
    "LevoitCore600S.groovy",
    # Vital purifier line (VeSyncAirBaseV2)
    "LevoitVital100S.groovy",
    "LevoitVital200S.groovy",
    # V2 fan/purifier line (VeSyncAirBaseV2)
    "LevoitEverestAir.groovy",
    "LevoitSproutAir.groovy",
    # Fan line
    "LevoitTowerFan.groovy",
    "LevoitPedestalFan.groovy",
    # Humidifier drivers — all 9 humidifier models
    "LevoitClassic200S.groovy",
    "LevoitClassic300S.groovy",
    "LevoitDual200S.groovy",
    "LevoitLV600S.groovy",
    "LevoitLV600SHubConnect.groovy",
    "LevoitOasisMist450S.groovy",
    "LevoitOasisMist1000S.groovy",
    "LevoitSproutHumidifier.groovy",
    "LevoitSuperior6000S.groovy",
    # Generic catch-all
    "LevoitGeneric.groovy",
}

# V2-line drivers that must have the envelope-peel while loop in their per-driver applyStatus().
# Source: pyvesync device_map.py class membership + CLAUDE.md family table.
# NOTE: humidifier drivers (Classic200S, Classic300S, Dual200S, LV600S, LV600SHubConnect,
#   OasisMist450S, OasisMist1000S, SproutHumidifier, Superior6000S) also use V2-style
#   envelopes but share their applyStatus peel via LevoitHumidifierLib — the lib carries
#   the peel and is not directly checkable here. These are intentionally excluded from
#   BP3 scope to avoid false-positive "missing peel" findings on drivers that inherit it
#   from the library. BP3 expansion to humidifier drivers is tracked in ROADMAP.md.
V2_LINE_DRIVERS = {
    # Vital purifier line (VeSyncAirBaseV2)
    "LevoitVital100S.groovy",
    "LevoitVital200S.groovy",
    # V2 fan/purifier line (VeSyncAirBaseV2)
    "LevoitEverestAir.groovy",
    "LevoitSproutAir.groovy",
    # V2 humidifier drivers with per-driver applyStatus (no lib peel)
    "LevoitSuperior6000S.groovy",
    # Generic catch-all (VeSyncAirBaseV2 compatible)
    "LevoitGeneric.groovy",
}

# V2-purifier drivers where setLevel field names are enforced (BP4).
# These use: levelIdx (not switchIdx), levelType (not type), manualSpeedLevel (not level).
# Source: pyvesync VeSyncAirBaseV2.set_fan_speed() canonical payload.
# Does NOT include humidifier V2 drivers — they use setVirtualLevel/virtualLevel fields.
V2_PURIFIER_DRIVERS = {
    "LevoitVital100S.groovy",
    "LevoitVital200S.groovy",
    "LevoitEverestAir.groovy",
    "LevoitSproutAir.groovy",
}

# BP5-specific scope: only Vital 200S has the quirk where setPurifierMode("manual") fails.
# EverestAir and Sprout Air are VeSyncAirBaseV2-class drivers that correctly use
# setPurifierMode for ALL modes including manual — the BP5 quirk is V201S-specific.
BP5_MANUAL_MODE_DRIVERS = {
    "LevoitVital200S.groovy",
    "LevoitVital100S.groovy",
}

FORK_DOC_DOMAIN = "github.com/level99/Hubitat-VeSync"




# ---------------------------------------------------------------------------
# BP1 — Missing 2-arg update() signature
# ---------------------------------------------------------------------------

def check_bp1_missing_2arg_update(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Every polled child driver must declare all three update() overloads:
      def update()
      def update(status)
      def update(status, nightLight)

    Library-aware: when a driver delegates its update() overloads to a shared library
    via ``#include level99.<LibName>``, the overloads live in the library file rather
    than the driver source.  We concatenate the texts of all resolved #include'd
    libraries before searching, so that method signatures in libs satisfy the check.
    """
    findings = []
    fname = path.name
    if fname not in POLLED_CHILD_DRIVERS:
        return findings

    # Expand raw_text with the body of any #include'd library files so that
    # update() overloads extracted to a shared lib still satisfy the check.
    lib_texts = included_lib_texts(raw_text, path)
    search_text = raw_text + '\n'.join(lib_texts)

    # Detect each overload via regex (allow any whitespace)
    has_0arg = bool(re.search(r'\bdef\s+update\s*\(\s*\)', search_text))
    has_1arg = bool(re.search(r'\bdef\s+update\s*\(\s*\w+\s*\)', search_text))
    has_2arg = bool(re.search(r'\bdef\s+update\s*\(\s*\w+\s*,\s*\w+\s*\)', search_text))

    if not has_2arg:
        # Find a representative line — the def update( with 1 arg if present, else first def update
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+update\s*\(', line):
                lineno = i
                break
        findings.append(make_finding_for_path(
            severity="FAIL",
            rule_id="BP1_missing_2arg_update",
            title="Missing 2-arg update(status, nightLight) signature",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Bug Pattern #1: parent calls update(status, nightLight) on every poll; child missing "
                "the 2-arg signature throws MissingMethodException silently on each poll cycle.",
            fix='Add: def update(status, nightLight) { applyStatus(status); return true }',
        ))

    if has_2arg and not has_1arg:
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+update\s*\(', line):
                lineno = i
                break
        findings.append(make_finding_for_path(
            severity="FAIL",
            rule_id="BP1_missing_1arg_update",
            title="Missing 1-arg update(status) defensive delegator signature",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Bug Pattern #1: all three update() overloads are required. The 1-arg form "
                "is called when the parent dispatches with a single argument (rare but real). "
                "Missing it throws MissingMethodException silently. "
                "Canonical form: def update(status) { update(status, null) }",
            fix='Add: def update(status) { update(status, null) }',
        ))

    if has_2arg and not has_0arg:
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+update\s*\(', line):
                lineno = i
                break
        findings.append(make_finding_for_path(
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
# BP2 — Missing deviceMethodFor() routing helper call in updateDevices()
# ---------------------------------------------------------------------------

def check_bp2_hardcoded_purifier_method(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    In VeSyncIntegration.groovy, the updateDevices() method must delegate
    API-method selection to the deviceMethodFor() helper (v2.3+).

    Positive assertion: updateDevices() body must contain a call to
    deviceMethodFor(...). If it does NOT, flag FAIL — someone re-inlined
    routing logic (or the helper was accidentally removed).

    Pre-v2.3 the check was a negative assertion (look for "getPurifierStatus"
    without a humidifier branch). That approach was replaced because:
      (a) the literal moved to deviceMethodFor(), making the old check vacuous, and
      (b) the old fix: suggestion pointed to the deprecated typeName.contains()
          pattern, which would re-introduce exactly the regression this rule guards.

    The positive-assertion form is more robust: it fires on any regression
    where updateDevices() stops delegating to the helper, regardless of what
    inline approach the regressor chose.
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    bodies = find_method_bodies(raw_text, "updateDevices")
    for (start_line, body) in bodies:
        has_helper_call = bool(re.search(r'\bdeviceMethodFor\s*\(', body))
        if not has_helper_call:
            findings.append(make_finding_for_path(
                severity="FAIL",
                rule_id="BP2_missing_deviceMethodFor_call",
                title="updateDevices() does not call deviceMethodFor() for API-method routing",
                path=path, rel_base=rel_base, lineno=start_line, lines=raw_lines,
                why="Bug Pattern #2: the VeSync API uses different status-read methods per device "
                    "family (getPurifierStatus / getHumidifierStatus / getTowerFanStatus / "
                    "getFanStatus). Routing must be delegated to deviceMethodFor(child), which "
                    "maps raw model codes through deviceType() to the correct method. Inlining "
                    "routing logic in updateDevices() (e.g. typeName.contains() or hardcoding "
                    "a method name) silently fails for some device families.",
                fix="In updateDevices() loop: `String method = deviceMethodFor(dev)` — then use "
                    "`method` in the command Map. Do not inline routing logic. See "
                    "VeSyncIntegration.groovy deviceMethodFor() for the canonical dtype→method "
                    "dispatch switch.",
            ))
        break  # one finding per updateDevices body is enough

    return findings


# ---------------------------------------------------------------------------
# BP3 — Missing envelope-peel while-loop in V2-line drivers
# ---------------------------------------------------------------------------

# Two valid expressions of the BP3 contract:
#   1. Inline while-loop pattern (status quo on most drivers as of v2.8)
#   2. Helper call `peelEnvelope(...)` from LevoitChildBaseLib (Task #140 Phase 2+
#      migration shape; helper body is byte-equivalent to the inline loop)
PEEL_PATTERN = re.compile(
    r'while\s*\(.*peelGuard\s*<\s*\d',
    re.DOTALL
)
PEEL_HELPER_PATTERN = re.compile(r'\bpeelEnvelope\s*\(')

def check_bp3_envelope_peel(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    V2-line drivers must have the defensive envelope peel in applyStatus() —
    either inline (PEEL_PATTERN while-loop) or via the peelEnvelope() helper
    from LevoitChildBaseLib (PEEL_HELPER_PATTERN). Both shapes are semantically
    equivalent; the helper body is byte-equivalent to the inline pattern.
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
        findings.append(make_finding_for_path(
            severity="FAIL",
            rule_id="BP3_no_applyStatus",
            title="applyStatus() method not found",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #3: V2-line driver must have applyStatus() with envelope-peel logic.",
            fix="Add applyStatus(status) with the while-loop peel (peelGuard < 4) "
                "OR a call to peelEnvelope(status) from LevoitChildBaseLib.",
        ))
        return findings

    for (start_line, body) in bodies:
        has_inline = bool(PEEL_PATTERN.search(body))
        has_helper = bool(PEEL_HELPER_PATTERN.search(body))
        if not (has_inline or has_helper):
            findings.append(make_finding_for_path(
                severity="FAIL",
                rule_id="BP3_missing_envelope_peel",
                title="Missing envelope-peel while-loop or peelEnvelope() helper call in applyStatus()",
                path=path, rel_base=rel_base, lineno=start_line, lines=raw_lines,
                why="Bug Pattern #3: V2-line bypassV2 responses are sometimes double-wrapped. "
                    "Without the while-loop peel (peelGuard < 4) or peelEnvelope() helper call, "
                    "applyStatus() reads the inner envelope dict [code, result, traceId] instead "
                    "of device fields.",
                fix="Add inside applyStatus(): EITHER "
                    "int peelGuard = 0; while (r instanceof Map && r.containsKey('code') && "
                    "r.containsKey('result') && r.result instanceof Map && peelGuard < 4) { "
                    "r = r.result; peelGuard++ } "
                    "OR call the helper: def r = peelEnvelope(status) "
                    "(requires #include level99.LevoitChildBase).",
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
            findings.append(make_finding_for_path(
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
            findings.append(make_finding_for_path(
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
    In the Vital 200S / Vital 100S (VeSyncAirBaseV2 LAP-V201S family), setPurifierMode
    with workMode:"manual" always returns inner code -1. Manual mode must be established
    via setLevel with a speed value.

    Scope: BP5_MANUAL_MODE_DRIVERS only (Vital 200S, Vital 100S).
    EverestAir and Sprout Air (also VeSyncAirBaseV2) correctly use setPurifierMode for
    ALL modes including "manual" — the quirk is V201S-firmware-specific, not class-wide.
    """
    findings = []
    if path.name not in BP5_MANUAL_MODE_DRIVERS:
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
            findings.append(make_finding_for_path(
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
                findings.append(make_finding_for_path(
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
        findings.append(make_finding_for_path(
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
                findings.append(make_finding_for_path(
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
            findings.append(make_finding_for_path(
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

# Two valid expressions of the BP12 contract:
#   1. Inline `state.prefsSeeded` block (status quo on most drivers as of v2.8)
#   2. Helper call `seedPrefs()` from LevoitChildBaseLib (Task #140 Phase 2+
#      migration shape; helper body is byte-equivalent to the inline block)
PREF_SEED_PATTERN = re.compile(r'state\.prefsSeeded')
PREF_SEED_HELPER_PATTERN = re.compile(r'\bseedPrefs\s*\(')

# Insertion-point method per driver type
BP12_INSERTION_POINTS = {
    "VeSyncIntegration.groovy": "updateDevices",
    "LevoitCore200S Light.groovy": "update",  # 1-arg form; any update matches
    "Notification Tile.groovy": "deviceNotification",
}
BP12_APPLY_STATUS_DRIVERS = {
    # V2 purifier line — applyStatus is the pref-seed insertion point
    "LevoitVital100S.groovy",
    "LevoitVital200S.groovy",
    "LevoitEverestAir.groovy",
    "LevoitSproutAir.groovy",
    # V2 humidifier drivers with per-driver applyStatus
    "LevoitLV600SHubConnect.groovy",
    "LevoitOasisMist1000S.groovy",
    "LevoitSproutHumidifier.groovy",
    "LevoitSuperior6000S.groovy",
    # Generic
    "LevoitGeneric.groovy",
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
    Every driver must have the one-time pref-seed self-healing block at the
    correct insertion point — either inline (state.prefsSeeded gate) or via
    the seedPrefs() helper from LevoitChildBaseLib. Both shapes ensure
    descriptionTextEnable=true is applied for users who migrate without
    clicking Save Preferences.
    """
    findings = []
    fname = path.name
    if fname not in (
        set(BP12_INSERTION_POINTS.keys())
        | BP12_APPLY_STATUS_DRIVERS
        | BP12_CORE_DRIVERS
    ):
        return findings

    # Does the file have either valid expression of the pattern?
    has_inline = bool(PREF_SEED_PATTERN.search(raw_text))
    has_helper = bool(PREF_SEED_HELPER_PATTERN.search(raw_text))
    if not (has_inline or has_helper):
        # Determine the expected insertion method for context
        if fname in BP12_INSERTION_POINTS:
            method = BP12_INSERTION_POINTS[fname]
        elif fname in BP12_APPLY_STATUS_DRIVERS:
            method = "applyStatus"
        else:
            method = "update (2-arg form)"

        findings.append(make_finding_for_path(
            severity="FAIL",
            rule_id="BP12_missing_pref_seed",
            title="Missing state.prefsSeeded self-healing block or seedPrefs() helper call",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Bug Pattern #12: when a driver is Type-changed or HPM-updated, new preference "
                "defaultValues are not auto-committed until user clicks Save Preferences. Without "
                "the pref-seed block or seedPrefs() helper call, descriptionTextEnable may be null "
                "(falsy), silently suppressing all INFO logs for migrating users.",
            fix=f"Add at top of {method}(): EITHER "
                "if (!state.prefsSeeded) { "
                "if (settings?.descriptionTextEnable == null) { "
                "device.updateSetting(\"descriptionTextEnable\", [type:\"bool\", value:true]) } "
                "state.prefsSeeded = true } "
                "OR call the helper: seedPrefs() "
                "(requires #include level99.LevoitChildBase).",
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
