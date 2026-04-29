"""
lint_test.py — pytest tests for each lint rule.

Tests use in-memory Groovy snippets to verify rules fire on known-bad code
and stay silent on known-good code. No real driver files are modified.

Run:
    uv run --python 3.12 --with pytest tests/lint_test.py -v
    # or from repo root:
    uv run --python 3.12 -m pytest tests/lint_test.py -v
"""

import json
import sys
import textwrap
from pathlib import Path

import pytest

# Add tests/ directory to path so lint_rules imports work
TESTS_DIR = Path(__file__).parent
REPO_ROOT = TESTS_DIR.parent
if str(TESTS_DIR) not in sys.path:
    sys.path.insert(0, str(TESTS_DIR))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def make_fake_path(name: str) -> Path:
    """Return a fake path under Drivers/Levoit/ for rel_base calculations."""
    if not name.endswith('.groovy') and not name.endswith('.md') and not name.endswith('.json'):
        name = name + '.groovy'
    return REPO_ROOT / "Drivers" / "Levoit" / name


def run_rule(rule_fn, src: str, fname: str = "TestDriver.groovy", config: dict = None):
    """
    Invoke a single per-file rule function against the given source string.
    Returns the list of findings.
    """
    from lint_rules.groovy_lite import clean_source
    config = config or {}
    path = make_fake_path(fname)
    raw_lines = src.splitlines()
    _, cleaned_lines = clean_source(src)
    return rule_fn(
        path=path,
        raw_lines=raw_lines,
        cleaned_lines=cleaned_lines,
        raw_text=src,
        config=config,
        rel_base=REPO_ROOT,
    )


def severities(findings):
    return [f['severity'] for f in findings]


def rule_ids(findings):
    return [f['rule_id'] for f in findings]


# ---------------------------------------------------------------------------
# Import all rules at module level (avoids class-binding pitfalls)
# ---------------------------------------------------------------------------

from lint_rules.groovy_javadoc_terminator import check_rule26_javadoc_terminator
from lint_rules.bp18_null_guard import check_rule27_bp18_null_guard
from lint_rules.bug_patterns import (
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
)
from lint_rules.logging_discipline import (
    check_rule13_direct_log_in_parent,
    check_rule14_bare_settings_ref,
    check_rule15_auto_disable_wiring,
)
from lint_rules.sandbox_safety import check_rule17_sandbox_forbidden
from lint_rules.pii_scan import check_rule16_pii_scan
from lint_rules.reentrance import check_rule20_reentrance_guards
from lint_rules.whitelist_parity import (
    _extract_regex_prefix_cases,
    _all_regex_cases_are_prefix_anchored,
)


# ---------------------------------------------------------------------------
# BP1 — Missing 2-arg update
# ---------------------------------------------------------------------------

class TestBP1Missing2ArgUpdate:
    GOOD = textwrap.dedent("""\
        def update() { refresh() }
        def update(status) { applyStatus(status) }
        def update(status, nightLight) { applyStatus(status); return true }
    """)

    BAD_NO_2ARG = textwrap.dedent("""\
        def update() { refresh() }
        def update(status) { applyStatus(status) }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp1_missing_2arg_update, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_missing_2arg_fails(self):
        findings = run_rule(check_bp1_missing_2arg_update, self.BAD_NO_2ARG, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP1_missing_2arg_update' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings)

    def test_not_checked_for_parent(self):
        # Parent driver is not in POLLED_CHILD_DRIVERS — not checked
        findings = run_rule(check_bp1_missing_2arg_update, self.BAD_NO_2ARG, "VeSyncIntegration")
        assert findings == []


# ---------------------------------------------------------------------------
# BP2 — Hardcoded getPurifierStatus in parent
# ---------------------------------------------------------------------------

class TestBP2HardcodedPurifierMethod:
    # v2.3+: BP2 is now a positive assertion — updateDevices() MUST call deviceMethodFor().
    # Pre-v2.3 GOOD snippet used typeName.contains(); that pattern is now the BAD case.
    GOOD = textwrap.dedent("""\
        def updateDevices() {
            String method = deviceMethodFor(dev)
            sendBypassRequest(dev, ["method": method, "data": {}])
        }
    """)

    # Missing deviceMethodFor() — inline hardcoded method (old pattern, regression risk)
    BAD_HARDCODED = textwrap.dedent("""\
        def updateDevices() {
            sendBypassRequest(dev, ["method": "getPurifierStatus", "data": {}])
        }
    """)

    # Missing deviceMethodFor() — old typeName.contains() pattern (also bad: re-inlines routing)
    BAD_TYPENAME_CONTAINS = textwrap.dedent("""\
        def updateDevices() {
            String method = typeName.contains("Humidifier") ? "getHumidifierStatus" : "getPurifierStatus"
            sendBypassRequest(dev, ["method": method, "data": {}])
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp2_hardcoded_purifier_method, self.GOOD, "VeSyncIntegration")
        assert findings == []

    def test_bad_hardcoded_fails(self):
        findings = run_rule(check_bp2_hardcoded_purifier_method, self.BAD_HARDCODED, "VeSyncIntegration")
        assert any(f['rule_id'] == 'BP2_missing_deviceMethodFor_call' for f in findings)

    def test_bad_typename_contains_fails(self):
        # The old "correct" pattern is now also a violation — it re-inlines routing
        findings = run_rule(check_bp2_hardcoded_purifier_method, self.BAD_TYPENAME_CONTAINS, "VeSyncIntegration")
        assert any(f['rule_id'] == 'BP2_missing_deviceMethodFor_call' for f in findings)

    def test_not_checked_for_children(self):
        findings = run_rule(check_bp2_hardcoded_purifier_method, self.BAD_HARDCODED, "LevoitVital200S")
        assert findings == []


# ---------------------------------------------------------------------------
# BP3 — Envelope peel
# ---------------------------------------------------------------------------

class TestBP3EnvelopePeel:
    GOOD = textwrap.dedent("""\
        def applyStatus(status) {
            def r = status?.result ?: [:]
            int peelGuard = 0
            while (r instanceof Map && r.containsKey('code') && r.containsKey('result')
                   && r.result instanceof Map && peelGuard < 4) {
                r = r.result
                peelGuard++
            }
            if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"
        }
    """)

    BAD = textwrap.dedent("""\
        def applyStatus(status) {
            def r = status?.result ?: [:]
            // no peel loop
            def mode = r.workMode
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp3_envelope_peel, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_bp3_envelope_peel, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP3_missing_envelope_peel' for f in findings)

    def test_not_checked_for_core_drivers(self):
        # Core drivers don't use V2 envelope — not checked
        findings = run_rule(check_bp3_envelope_peel, self.BAD, "LevoitCore400S")
        assert findings == []


# ---------------------------------------------------------------------------
# BP4 — setLevel field names
# ---------------------------------------------------------------------------

class TestBP4SetLevelFieldNames:
    GOOD = textwrap.dedent("""\
        def setSpeedLevel(Integer level) {
            hubBypass("setLevel", [levelIdx: 0, levelType: "wind", manualSpeedLevel: level], "setLevel")
        }
    """)

    BAD_SWITCHIDX = textwrap.dedent("""\
        def setSpeedLevel(Integer level) {
            hubBypass("setLevel", [switchIdx: 0, levelType: "wind", manualSpeedLevel: level], "setLevel")
        }
    """)

    BAD_TYPE = textwrap.dedent("""\
        def setSpeedLevel(Integer level) {
            hubBypass("setLevel", [levelIdx: 0, type: "wind", manualSpeedLevel: level], "setLevel")
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp4_setlevel_field_names, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_switchidx_fails(self):
        findings = run_rule(check_bp4_setlevel_field_names, self.BAD_SWITCHIDX, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP4_switchIdx_in_setLevel' for f in findings)

    def test_bad_type_instead_of_leveltype_fails(self):
        findings = run_rule(check_bp4_setlevel_field_names, self.BAD_TYPE, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP4_type_instead_of_levelType' for f in findings)

    def test_not_checked_for_core_drivers(self):
        findings = run_rule(check_bp4_setlevel_field_names, self.BAD_SWITCHIDX, "LevoitCore400S")
        assert findings == []


# ---------------------------------------------------------------------------
# BP5 — Manual mode via setPurifierMode
# ---------------------------------------------------------------------------

class TestBP5ManualViaPurifierMode:
    GOOD = textwrap.dedent("""\
        def setMode(mode) {
            if (mode == "manual") {
                setSpeedLevel(mapSpeedToInteger(state.speed ?: "low"))
            } else {
                hubBypass("setPurifierMode", [workMode: mode], "setPurifierMode")
            }
        }
    """)

    BAD = textwrap.dedent("""\
        def setMode(mode) {
            hubBypass("setPurifierMode", [workMode: "manual"], "setPurifierMode")
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP5_manual_via_setPurifierMode' for f in findings)

    def test_not_checked_for_core_drivers(self):
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.BAD, "LevoitCore400S")
        assert findings == []


# ---------------------------------------------------------------------------
# BP7 — currentValue race
# ---------------------------------------------------------------------------

class TestBP7CurrentValueRace:
    GOOD = textwrap.dedent("""\
        def applyStatus(status) {
            String aq = "good"
            device.sendEvent(name: "airQuality", value: aq)
            def html = "<br>Air Quality: ${aq}"
        }
    """)

    BAD = textwrap.dedent("""\
        def applyStatus(status) {
            device.sendEvent(name: "airQuality", value: "good")
            def html = "<br>Air Quality: ${device.currentValue('airQuality')}"
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp7_currentvalue_race, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_warns(self):
        findings = run_rule(check_bp7_currentvalue_race, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP7_currentvalue_race' for f in findings)
        assert all(f['severity'] == 'WARN'
                   for f in findings if f['rule_id'] == 'BP7_currentvalue_race')


# ---------------------------------------------------------------------------
# BP9 — Driver name frozen
# ---------------------------------------------------------------------------

class TestBP9DriverNameFrozen:
    GOOD = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson") {}
        }
    """)

    BAD = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Vital 200S Air Purifier (NEW)", namespace: "NiklasGustafsson") {}
        }
    """)

    CONFIG = {"frozen_driver_names": {"LevoitVital200S.groovy": "Levoit Vital 200S Air Purifier"}}

    def test_good_passes(self):
        findings = run_rule(check_bp9_driver_name_frozen, self.GOOD, "LevoitVital200S", self.CONFIG)
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_bp9_driver_name_frozen, self.BAD, "LevoitVital200S", self.CONFIG)
        assert any(f['rule_id'] == 'BP9_driver_name_changed' for f in findings)

    def test_unknown_file_skipped(self):
        # File not in frozen list — skipped
        findings = run_rule(check_bp9_driver_name_frozen, self.BAD, "SomeNewDriver", self.CONFIG)
        assert findings == []


# ---------------------------------------------------------------------------
# BP10 — SmartThings icons
# ---------------------------------------------------------------------------

class TestBP10SmartThingsIcons:
    GOOD = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Core200S Air Purifier", namespace: "NiklasGustafsson") {}
        }
    """)

    BAD_ICON_URL = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Core200S Air Purifier",
                iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png") {}
        }
    """)

    BAD_MY_APPS = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Core200S Air Purifier", category: "My Apps") {}
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp10_smartthings_icons, self.GOOD, "LevoitCore200S")
        assert findings == []

    def test_icon_url_fails(self):
        findings = run_rule(check_bp10_smartthings_icons, self.BAD_ICON_URL, "LevoitCore200S")
        assert any(f['rule_id'] == 'BP10_smartthings_icon' for f in findings)

    def test_my_apps_category_fails(self):
        findings = run_rule(check_bp10_smartthings_icons, self.BAD_MY_APPS, "LevoitCore200S")
        assert any(f['rule_id'] == 'BP10_smartthings_icon' for f in findings)


# ---------------------------------------------------------------------------
# BP11 — documentationLink
# ---------------------------------------------------------------------------

class TestBP11DocumentationLink:
    GOOD = textwrap.dedent("""\
        definition(documentationLink: "https://github.com/level99/Hubitat-VeSync") {}
    """)

    BAD = textwrap.dedent("""\
        definition(documentationLink: "https://github.com/dcmeglio/hubitat-bond") {}
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp11_documentation_link, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_bp11_documentation_link, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP11_wrong_documentation_link' for f in findings)


# ---------------------------------------------------------------------------
# BP12 — Pref seed
# ---------------------------------------------------------------------------

class TestBP12PrefSeed:
    GOOD = textwrap.dedent("""\
        def applyStatus(status) {
            if (!state.prefsSeeded) {
                if (settings?.descriptionTextEnable == null) {
                    device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
                }
                state.prefsSeeded = true
            }
        }
    """)

    BAD = textwrap.dedent("""\
        def applyStatus(status) {
            def r = status?.result ?: [:]
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_bp12_pref_seed, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_bp12_pref_seed, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP12_missing_pref_seed' for f in findings)


# ---------------------------------------------------------------------------
# Rule 13 — Direct log.X in parent
# ---------------------------------------------------------------------------

class TestRule13DirectLogInParent:
    GOOD = textwrap.dedent("""\
        def logInfo(msg)  { if (settings?.descriptionTextEnable) log.info sanitize(msg) }
        def logDebug(msg) { if (settings?.debugOutput) log.debug sanitize(msg) }
        def logError(msg) { log.error sanitize(msg) }

        def someMethod() {
            logInfo "Hello"
            logDebug "Debug"
        }
    """)

    BAD = textwrap.dedent("""\
        def logInfo(msg) { if (settings?.descriptionTextEnable) log.info sanitize(msg) }

        def someMethod() {
            log.info "This leaks credentials"
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_rule13_direct_log_in_parent, self.GOOD, "VeSyncIntegration")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_rule13_direct_log_in_parent, self.BAD, "VeSyncIntegration")
        assert any(f['rule_id'] == 'RULE13_direct_log_in_parent' for f in findings)

    def test_not_checked_for_children(self):
        # Children don't hold credentials; rule only applies to parent
        findings = run_rule(check_rule13_direct_log_in_parent, self.BAD, "LevoitVital200S")
        assert findings == []


# ---------------------------------------------------------------------------
# Rule 14 — Bare settings reference
# ---------------------------------------------------------------------------

class TestRule14BareSettingsRef:
    GOOD = textwrap.dedent("""\
        private logDebug(msg) { if (settings?.debugOutput) log.debug msg }
    """)

    BAD = textwrap.dedent("""\
        def someMethod() {
            if (debugOutput) log.debug "something"
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_rule14_bare_settings_ref, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        findings = run_rule(check_rule14_bare_settings_ref, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE14_bare_settings_ref' for f in findings)


# ---------------------------------------------------------------------------
# Rule 15 — Auto-disable wiring
# ---------------------------------------------------------------------------

class TestRule15AutoDisableWiring:
    GOOD = textwrap.dedent("""\
        def updated() {
            if (settings?.debugOutput) runIn(1800, "logDebugOff")
        }
        void logDebugOff() {
            if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false])
        }
    """)

    BAD_NO_RUNIN = textwrap.dedent("""\
        def updated() {
            // forgot auto-disable
        }
        void logDebugOff() {
            device.updateSetting("debugOutput", [type:"bool", value:false])
        }
    """)

    BAD_NO_METHOD = textwrap.dedent("""\
        def updated() {
            if (settings?.debugOutput) runIn(1800, "logDebugOff")
        }
        // forgot logDebugOff method
    """)

    def test_good_passes(self):
        findings = run_rule(check_rule15_auto_disable_wiring, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_no_runin_fails(self):
        findings = run_rule(check_rule15_auto_disable_wiring, self.BAD_NO_RUNIN, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE15_missing_runin_1800' for f in findings)

    def test_bad_no_method_fails(self):
        findings = run_rule(check_rule15_auto_disable_wiring, self.BAD_NO_METHOD, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE15_missing_logdebugoff_method' for f in findings)


# ---------------------------------------------------------------------------
# Rule 17 — Sandbox safety
# ---------------------------------------------------------------------------

class TestRule17SandboxSafety:
    GOOD = textwrap.dedent("""\
        import groovy.json.JsonSlurper
        import java.security.MessageDigest

        def someMethod() {
            def slurper = new JsonSlurper()
        }
    """)

    BAD_IMPORT = textwrap.dedent("""\
        import java.io.File
        import java.lang.Runtime
    """)

    BAD_EVAL = textwrap.dedent("""\
        def dangerousMethod() {
            Eval.me("println 'bad'")
        }
    """)

    BAD_CLASS_FOR_NAME = textwrap.dedent("""\
        def load(className) {
            return Class.forName(className).newInstance()
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_bad_import_fails(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.BAD_IMPORT, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE17_forbidden_import' for f in findings)

    def test_eval_fails(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.BAD_EVAL, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE17_forbidden_reflection' for f in findings)

    def test_class_for_name_fails(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.BAD_CLASS_FOR_NAME, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE17_forbidden_reflection' for f in findings)


# ---------------------------------------------------------------------------
# Rule 16 — PII scan
# ---------------------------------------------------------------------------

class TestRule16PIIScan:
    GOOD = textwrap.dedent("""\
        // No PII here
        def someMethod() {
            logInfo "All clean"
        }
    """)

    BAD_IP = "def hubIp = '10.99.99.99'"
    BAD_EMAIL = "// Contact: test@example.invalid"
    BAD_MAC = "// Device: AA:BB:CC:DD:EE:FF"

    def test_good_passes(self):
        findings = run_rule(check_rule16_pii_scan, self.GOOD, "VeSyncIntegration")
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == []

    def test_bad_ip_fails(self):
        # PII scan uses REPO_ROOT-relative path check; use a plain filename
        findings = run_rule(check_rule16_pii_scan, self.BAD_IP, "README.md")
        assert any(f['rule_id'] == 'RULE16_lan_ip' for f in findings)

    def test_bad_email_fails(self):
        findings = run_rule(check_rule16_pii_scan, self.BAD_EMAIL, "CLAUDE.md")
        assert any(f['rule_id'] == 'RULE16_email' for f in findings)

    def test_bad_mac_fails(self):
        findings = run_rule(check_rule16_pii_scan, self.BAD_MAC, "SomeFile.groovy")
        assert any(f['rule_id'] == 'RULE16_mac_address' for f in findings)

    def test_placeholder_email_passes(self):
        findings = run_rule(check_rule16_pii_scan, "// Use your@email.com to log in", "README.md")
        email_findings = [f for f in findings if f['rule_id'] == 'RULE16_email']
        assert email_findings == []

    def test_allowed_email_passes(self):
        findings = run_rule(
            check_rule16_pii_scan,
            "noreply@anthropic.com is the co-author",
            "CLAUDE.md",
            config={"pii_allow_patterns": ["noreply@anthropic\\.com"]},
        )
        email_findings = [f for f in findings if f['rule_id'] == 'RULE16_email']
        assert email_findings == []


# ---------------------------------------------------------------------------
# Rule 20 — Re-entrance guards
# ---------------------------------------------------------------------------

class TestRule20ReentranceGuards:
    GOOD = textwrap.dedent("""\
        def on() {
            state.turningOn = true
            try {
                handlePower(true)
            } finally {
                state.remove('turningOn')
            }
        }
    """)

    BAD = textwrap.dedent("""\
        def on() {
            state.turningOn = true
            handlePower(true)
            state.remove('turningOn')
        }
    """)

    def test_good_passes(self):
        findings = run_rule(check_rule20_reentrance_guards, self.GOOD, "LevoitVital200S")
        assert all(f['severity'] != 'FAIL' for f in findings)

    def test_bad_warns(self):
        findings = run_rule(check_rule20_reentrance_guards, self.BAD, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE20_reentrance_guard_unclosed' for f in findings)
        assert all(f['severity'] == 'WARN'
                   for f in findings if f['rule_id'] == 'RULE20_reentrance_guard_unclosed')


# ---------------------------------------------------------------------------
# Rule 26 — Javadoc terminator detector
# ---------------------------------------------------------------------------

class TestRule26JavadocTerminator:
    """
    RULE26: detect `*/` embedded inside Groovy `/**` Javadoc blocks.

    An embedded `*/` causes Groovy to close the block comment prematurely.
    Everything between the embedded `*/` and the intended close is then
    parsed as live code, producing a compile error. This bit the codebase
    twice (v2.1 fe4d723 and v2.2 PR#4) before the rule was added.

    Note: RULE26 operates on raw_text (NOT cleaned_lines) because
    groovy_lite.clean_source already strips block comments. The run_rule()
    helper passes raw_text correctly via its internal clean_source call.
    """

    # --- PASS cases ---

    CLEAN_JAVADOC = textwrap.dedent("""\
        /**
         * Sets the purifier mode.
         * @param mode one of: auto, sleep, manual
         * @return void
         */
        def setMode(String mode) { }
    """)

    EMPTY_JAVADOC = textwrap.dedent("""\
        /**/
        def noop() { }
    """)

    LINE_COMMENT_WITH_STAR_SLASH = textwrap.dedent("""\
        // This line has */ in a line comment — not inside a block comment
        def someMethod() { return 42 }
    """)

    STRING_LITERAL_WITH_STAR_SLASH = textwrap.dedent("""\
        def getCron() {
            return "0 */5 * * * ?"
        }
    """)

    # --- FAIL cases ---

    # v2.1 reproducer: model-code list with */ inside a Javadoc block
    V21_REPRODUCER = textwrap.dedent("""\
        /**
         * Supported models: LUH-O451S-*/LUH-O601S-*
         * Use this driver for either variant.
         */
        def applyStatus(status) { }
    """)

    # v2.2 reproducer: cron string with */ inside a Javadoc block
    V22_REPRODUCER = textwrap.dedent("""\
        /**
         * Sets up the poll schedule. Example cron: "0 */${minutes} * * * ?"
         * @param minutes poll interval in minutes
         */
        def setupPollSchedule(int minutes) { }
    """)

    # Nested /* inner */ inside /** ... */ — inner */ closes the outer block
    NESTED_BLOCK_INSIDE_JAVADOC = textwrap.dedent("""\
        /**
         * Main doc.
         * /* inner block comment */
         * More doc that now parses as live code.
         */
        def foo() { }
    """)

    def test_clean_javadoc_passes(self):
        findings = run_rule(check_rule26_javadoc_terminator, self.CLEAN_JAVADOC)
        assert findings == [], f"Expected no findings on clean Javadoc, got: {findings}"

    def test_empty_javadoc_passes(self):
        # /**/  — open immediately followed by close; no embedded */ inside
        findings = run_rule(check_rule26_javadoc_terminator, self.EMPTY_JAVADOC)
        assert findings == [], f"Expected no findings on empty /**/, got: {findings}"

    def test_line_comment_with_star_slash_passes(self):
        # */ in a // line comment — not inside a block comment; should not trigger
        findings = run_rule(
            check_rule26_javadoc_terminator, self.LINE_COMMENT_WITH_STAR_SLASH
        )
        assert findings == [], (
            f"Expected no findings for */ in line comment, got: {findings}"
        )

    def test_string_literal_with_star_slash_passes(self):
        # */ inside a string literal in live code — no Javadoc block present; PASS
        findings = run_rule(
            check_rule26_javadoc_terminator, self.STRING_LITERAL_WITH_STAR_SLASH
        )
        assert findings == [], (
            f"Expected no findings for */ in string literal outside Javadoc, got: {findings}"
        )

    def test_v21_reproducer_fails(self):
        # LUH-O451S-*/LUH-O601S-* inside Javadoc — the */ after LUH-O451S-
        # terminates the block; rest of the block content is live code.
        findings = run_rule(check_rule26_javadoc_terminator, self.V21_REPRODUCER)
        rule26 = [f for f in findings if f['rule_id'] == 'RULE26_javadoc_terminator']
        assert rule26, "Expected RULE26 finding on v2.1 reproducer"
        assert all(f['severity'] == 'FAIL' for f in rule26)

    def test_v22_reproducer_fails(self):
        # "0 */${minutes} * * * ?" inside Javadoc — the */ in the cron string
        # terminates the block early.
        findings = run_rule(check_rule26_javadoc_terminator, self.V22_REPRODUCER)
        rule26 = [f for f in findings if f['rule_id'] == 'RULE26_javadoc_terminator']
        assert rule26, "Expected RULE26 finding on v2.2 reproducer"
        assert all(f['severity'] == 'FAIL' for f in rule26)

    def test_nested_block_inside_javadoc_fails(self):
        # /* inner */ inside /** ... */ — the inner */ terminates the outer block
        findings = run_rule(
            check_rule26_javadoc_terminator, self.NESTED_BLOCK_INSIDE_JAVADOC
        )
        rule26 = [f for f in findings if f['rule_id'] == 'RULE26_javadoc_terminator']
        assert rule26, "Expected RULE26 finding on nested /* */ inside /** */"
        assert all(f['severity'] == 'FAIL' for f in rule26)

    def test_non_groovy_file_skipped(self):
        # RULE26 only checks .groovy files; .py files should produce no findings
        findings = run_rule(
            check_rule26_javadoc_terminator,
            self.V21_REPRODUCER,
            fname="some_script.py",
        )
        assert findings == [], (
            "RULE26 should not check non-.groovy files"
        )

    def test_finding_includes_line_number(self):
        # The embedded */ in the v2.1 reproducer is on line 2; verify lineno is set
        findings = run_rule(check_rule26_javadoc_terminator, self.V21_REPRODUCER)
        rule26 = [f for f in findings if f['rule_id'] == 'RULE26_javadoc_terminator']
        assert rule26, "Expected at least one RULE26 finding"
        assert all(f['line'] > 0 for f in rule26), "line number must be positive"


# ---------------------------------------------------------------------------
# RULE22 — whitelist parity regex prefix extraction
# ---------------------------------------------------------------------------

class TestRule22RegexPrefixExtraction:
    """
    Unit tests for the RULE22 regex-case resolution helpers added in v2.3.

    _extract_regex_prefix_cases() resolves prefix-anchored Groovy regex cases
    (``case ~/^PREFIX-.*$/:`` form) to literal prefix strings.

    _all_regex_cases_are_prefix_anchored() returns True only when every
    ``case ~/.../:`` pattern in the body was resolved by the extractor.
    """

    # Body with a single clean prefix-anchored regex case
    BODY_SINGLE_PREFIX = "switch(code) { case ~/^LAP-.*$/: return 'EL551S' }"

    # Body with two distinct prefix-anchored regex cases
    BODY_TWO_PREFIXES = (
        "switch(code) { "
        "case ~/^LAP-.*$/: return 'LAP' "
        "case ~/^LEH-.*$/: return 'LEH' "
        "}"
    )

    # Body without trailing $ (also valid anchored form)
    BODY_NO_DOLLAR = "switch(code) { case ~/^LAP-.*/:  return 'EL551S' }"

    # Body with a non-prefix-anchored regex (character class) -- not resolvable
    BODY_CHAR_CLASS = "switch(code) { case ~/^LEH-S60[12]S-.*$/: return 'Humidifier' }"

    # Body with one resolvable + one unresolvable regex
    BODY_MIXED = (
        "switch(code) { "
        "case ~/^LAP-.*$/: return 'LAP' "
        "case ~/^LEH-S60[12]S-.*$/: return 'LEH_special' "
        "}"
    )

    # Body with no regex cases at all
    BODY_NO_REGEX = 'switch(code) { case "LAP-EL551S-WUS": return "EL551S" }'

    def test_single_prefix_extracted(self):
        result = _extract_regex_prefix_cases(self.BODY_SINGLE_PREFIX)
        assert result == {"LAP-"}

    def test_two_prefixes_extracted(self):
        result = _extract_regex_prefix_cases(self.BODY_TWO_PREFIXES)
        assert result == {"LAP-", "LEH-"}

    def test_no_dollar_anchor_extracted(self):
        # Trailing $ is optional in the pattern; should still resolve
        result = _extract_regex_prefix_cases(self.BODY_NO_DOLLAR)
        assert result == {"LAP-"}

    def test_char_class_not_extracted(self):
        # Character class [12] breaks the simple prefix anchor form -- not resolved
        result = _extract_regex_prefix_cases(self.BODY_CHAR_CLASS)
        assert result == set()

    def test_no_regex_returns_empty(self):
        result = _extract_regex_prefix_cases(self.BODY_NO_REGEX)
        assert result == set()

    def test_all_anchored_when_all_resolve(self):
        # Both regex cases in BODY_TWO_PREFIXES are prefix-anchored -- all resolved
        resolved = _extract_regex_prefix_cases(self.BODY_TWO_PREFIXES)
        assert _all_regex_cases_are_prefix_anchored(self.BODY_TWO_PREFIXES, resolved) is True

    def test_not_all_anchored_when_one_unresolvable(self):
        # BODY_MIXED has 2 regex cases but only 1 is prefix-anchored
        resolved = _extract_regex_prefix_cases(self.BODY_MIXED)
        assert len(resolved) == 1  # only LAP- resolved
        assert _all_regex_cases_are_prefix_anchored(self.BODY_MIXED, resolved) is False

    def test_all_anchored_on_body_with_no_regex(self):
        # No regex cases at all -- vacuously True (0 == 0)
        resolved = _extract_regex_prefix_cases(self.BODY_NO_REGEX)
        assert _all_regex_cases_are_prefix_anchored(self.BODY_NO_REGEX, resolved) is True


# ---------------------------------------------------------------------------
# Exemption mechanism
# ---------------------------------------------------------------------------

class TestExemptionMechanism:
    """
    Tests for the per-rule exemption mechanism in lint_config.yaml + lint.py.

    These tests exercise _validate_exemptions, _build_exemption_set, and
    _apply_exemptions directly — no subprocess needed.
    """

    def _import_lint(self):
        """Import the lint module (may need sys.path tweak for tests/ dir)."""
        import importlib.util
        spec = importlib.util.spec_from_file_location("lint", TESTS_DIR / "lint.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_exemption_suppresses_matching_finding(self):
        """A finding matching (rule_id, file) in the exemption set is removed."""
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitCore200S Light.groovy", "line": 42},
            {"severity": "FAIL", "rule_id": "BP3_missing_envelope_peel",
             "file": "Drivers/Levoit/LevoitVital200S.groovy", "line": 10},
        ]
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        assert count == 1
        assert len(kept) == 1
        assert kept[0]['rule_id'] == "BP3_missing_envelope_peel"

    def test_exemption_does_not_suppress_different_rule(self):
        """An exemption for rule X does not suppress a finding for rule Y on the same file."""
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP3_missing_envelope_peel",
             "file": "Drivers/Levoit/LevoitCore200S Light.groovy", "line": 10},
        ]
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        assert count == 0
        assert len(kept) == 1

    def test_exemption_does_not_suppress_different_file(self):
        """An exemption for file A does not suppress a finding for file B under the same rule."""
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitVital200S.groovy", "line": 10},
        ]
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        assert count == 0
        assert len(kept) == 1

    def test_backslash_path_normalized_for_comparison(self):
        """Windows-style backslash paths in findings are normalized before exemption comparison."""
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP1_missing_2arg_update",
             "file": "Drivers\\Levoit\\LevoitCore200S Light.groovy", "line": 42},
        ]
        # Exemption uses forward slashes (as config file always does)
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        assert count == 1
        assert len(kept) == 0

    def test_unused_exemption_returned(self):
        """An exemption that matched no finding is returned in unused_exemptions."""
        lint = self._import_lint()
        findings = []  # No findings at all
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        assert count == 0
        assert ("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy") in unused

    def test_missing_reason_raises_on_build(self):
        """An exemption entry with empty or missing 'reason' causes lint to exit with error."""
        lint = self._import_lint()
        bad_config = {"exemptions": [
            {"rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitCore200S Light.groovy",
             "reason": ""},  # empty reason — must error
        ]}
        import pytest
        with pytest.raises(SystemExit) as exc:
            lint._build_exemption_set(bad_config)
        assert exc.value.code == 1

    def test_missing_reason_field_raises(self):
        """An exemption entry with no 'reason' key at all causes lint to exit with error."""
        lint = self._import_lint()
        bad_config = {"exemptions": [
            {"rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitCore200S Light.groovy"},
            # no 'reason' field
        ]}
        import pytest
        with pytest.raises(SystemExit) as exc:
            lint._build_exemption_set(bad_config)
        assert exc.value.code == 1

    def test_bogus_rule_id_produces_unused_exemption(self):
        """
        An exemption with a bogus rule_id that matches no finding yields an unused exemption.
        The unused-exemption warning mechanism handles this — callers add a WARN finding.
        """
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitVital200S.groovy", "line": 10},
        ]
        exemption_set = {("NONEXISTENT_RULE_XYZ", "Drivers/Levoit/LevoitVital200S.groovy")}
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        # The real finding is untouched
        assert count == 0
        assert len(kept) == 1
        # The bogus exemption shows as unused
        assert ("NONEXISTENT_RULE_XYZ", "Drivers/Levoit/LevoitVital200S.groovy") in unused

    def test_empty_exemptions_section_is_noop(self):
        """If exemptions: [] or exemptions is absent, apply_exemptions returns findings unchanged."""
        lint = self._import_lint()
        findings = [
            {"severity": "FAIL", "rule_id": "BP1_missing_2arg_update",
             "file": "Drivers/Levoit/LevoitVital200S.groovy", "line": 10},
        ]
        kept, count, unused = lint._apply_exemptions(findings, set(), REPO_ROOT)
        assert count == 0
        assert kept == findings
        assert unused == set()

    def test_bp1_exemption_on_light_driver_suppresses_real_finding(self):
        """
        End-to-end: BP1 check on LevoitCore200S Light.groovy produces a finding,
        and the exemption from lint_config.yaml suppresses it.
        """
        # Run BP1 rule against a source that has only 1-arg update (as the Light driver does)
        src = textwrap.dedent("""\
            def update() { refresh() }
            def update(status) { applyStatus(status) }
        """)
        # BP1 fires on the Light driver (1-arg only)
        findings = run_rule(
            check_bp1_missing_2arg_update, src,
            fname="LevoitCore200S Light.groovy",
        )
        assert any(f['rule_id'] == 'BP1_missing_2arg_update' for f in findings), \
            "BP1 should fire on LevoitCore200S Light.groovy with 1-arg-only update"

        # Now apply exemption — finding should be suppressed
        lint = self._import_lint()
        exemption_set = {("BP1_missing_2arg_update", "Drivers/Levoit/LevoitCore200S Light.groovy")}
        # Findings from run_rule use REPO_ROOT-relative path under Drivers/Levoit/
        kept, count, unused = lint._apply_exemptions(findings, exemption_set, REPO_ROOT)
        bp1_findings = [f for f in kept if f['rule_id'] == 'BP1_missing_2arg_update']
        assert bp1_findings == [], \
            "BP1 finding on Light driver should be suppressed by exemption"
        assert count >= 1


# ---------------------------------------------------------------------------
# RULE27 — BP18 null guard on set* commands
# ---------------------------------------------------------------------------

class TestRule27NullGuardOnSetCommands:
    """
    RULE27 (Bug Pattern #18): set* command methods must have an explicit
    ``if (arg == null)`` guard before any normalization call on the parameter.

    10 tests: 5 PASS, 4 FAIL, 1 finding-quality.
    """

    # --- PASS cases ---

    PASS_EXPLICIT_GUARD = textwrap.dedent("""\
        def setMode(mode) {
            logDebug "setMode(${mode})"
            if (mode == null) { logWarn "setMode called with null mode; ignoring"; return }
            String m = (mode as String).toLowerCase()
            if (!(m in ["auto","manual"])) { logError "bad mode: ${m}"; return }
        }
    """)

    PASS_GUARD_YODA = textwrap.dedent("""\
        def setMode(mode) {
            if (null == mode) { logWarn "null mode; ignoring"; return }
            String m = (mode as String).toLowerCase()
        }
    """)

    PASS_NO_NORMALIZATION = textwrap.dedent("""\
        def setChildLock(enabled) {
            def val = enabled ? 1 : 0
            hubBypass("setChildLock", [childLockSwitch: val], "setChildLock")
        }
    """)

    PASS_NON_DRIVER_FILE = textwrap.dedent("""\
        def setMode(mode) {
            String m = (mode as String).toLowerCase()
        }
    """)

    # Method with a null-guard present AND a line comment that contains the
    # vulnerable pattern as an example — rule must not fire on the comment.
    PASS_COMMENT_WITH_FAKE_PATTERN = textwrap.dedent("""\
        def setMode(mode) {
            logDebug "setMode(${mode})"
            if (mode == null) { logWarn "setMode called with null mode; ignoring"; return }
            // Note: (mode as String).toLowerCase() would NPE if mode is null (Bug Pattern #18)
            String m = (mode as String).toLowerCase()
            if (!(m in ["auto","manual"])) { logError "bad mode"; return }
        }
    """)

    # --- FAIL cases ---

    FAIL_NO_GUARD_CAST_LOWER = textwrap.dedent("""\
        def setMode(mode) {
            logDebug "setMode(${mode})"
            String m = (mode as String).toLowerCase()
            if (!(m in ["auto","manual"])) { logError "bad mode"; return }
        }
    """)

    FAIL_NO_GUARD_DIRECT_LOWER = textwrap.dedent("""\
        def setSpeed(spd) {
            String s = spd.toLowerCase()
            if (s == "off") { off(); return }
        }
    """)

    FAIL_NO_GUARD_TO_INTEGER = textwrap.dedent("""\
        def setLevel(level) {
            int lvl = level.toInteger()
            hubBypass("setLevel", [level: lvl], "setLevel")
        }
    """)

    FAIL_GUARD_AFTER_VULN = textwrap.dedent("""\
        def setMode(mode) {
            String m = (mode as String).toLowerCase()
            if (mode == null) { logWarn "null; ignoring"; return }
        }
    """)

    def test_pass_explicit_guard(self):
        """Guard before normalization: no finding."""
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_EXPLICIT_GUARD)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], f"Expected no RULE27 findings, got: {rule27}"

    def test_pass_guard_yoda(self):
        """Yoda-style ``null == arg`` guard is also accepted."""
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_GUARD_YODA)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], f"Expected no RULE27 findings for Yoda-style guard, got: {rule27}"

    def test_pass_no_normalization(self):
        """set* method with no normalization call: no finding."""
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_NO_NORMALIZATION)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], f"Expected no RULE27 findings on non-normalizing method, got: {rule27}"

    def test_pass_non_driver_file(self):
        """Files outside Drivers/Levoit/ are out of scope — no finding."""
        findings = run_rule(
            check_rule27_bp18_null_guard,
            self.PASS_NON_DRIVER_FILE,
            fname="TestDevice.groovy",
        )
        # make_fake_path puts it in Drivers/Levoit/ — use a path outside that dir
        from lint_rules.bp18_null_guard import check_rule27_bp18_null_guard as rule_fn
        from lint_rules.groovy_lite import clean_source
        fake_path = REPO_ROOT / "src" / "test" / "groovy" / "support" / "TestDevice.groovy"
        raw_lines = self.PASS_NON_DRIVER_FILE.splitlines()
        _, cleaned_lines = clean_source(self.PASS_NON_DRIVER_FILE)
        result = rule_fn(
            path=fake_path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=self.PASS_NON_DRIVER_FILE,
            config={},
            rel_base=REPO_ROOT,
        )
        assert result == [], f"Expected no RULE27 findings for out-of-scope file, got: {result}"

    def test_pass_comment_with_fake_pattern(self):
        """Line comment containing the vulnerable pattern is not flagged when a real guard exists."""
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_COMMENT_WITH_FAKE_PATTERN)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], (
            f"Expected no RULE27 findings when vulnerable pattern appears only in a // comment, "
            f"got: {rule27}"
        )

    def test_fail_no_guard_cast_lower(self):
        """``(mode as String).toLowerCase()`` with no null-guard: FAIL."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_NO_GUARD_CAST_LOWER)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL on unguarded (mode as String).toLowerCase()"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_fail_no_guard_direct_lower(self):
        """``spd.toLowerCase()`` with no null-guard: FAIL."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_NO_GUARD_DIRECT_LOWER)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL on unguarded spd.toLowerCase()"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_fail_no_guard_to_integer(self):
        """``level.toInteger()`` with no null-guard: FAIL."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_NO_GUARD_TO_INTEGER)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL on unguarded level.toInteger()"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_fail_guard_after_vuln(self):
        """Guard that appears AFTER the vulnerable line does not prevent FAIL."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_GUARD_AFTER_VULN)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL when guard comes after the normalization call"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_finding_includes_method_name_and_line(self):
        """Finding message includes the method name; line number is set and positive."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_NO_GUARD_CAST_LOWER)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27
        assert any('setMode' in f['title'] for f in rule27)
        assert all(f['line'] > 0 for f in rule27)


# ---------------------------------------------------------------------------
# Integration: lint.py exit codes
# ---------------------------------------------------------------------------

class TestLintExitCodes:
    """Verify that the main entrypoint returns the right exit codes."""

    def test_help_does_not_crash(self):
        """Smoke test: lint.py --help returns 0."""
        import subprocess
        result = subprocess.run(
            [sys.executable, str(TESTS_DIR / 'lint.py'), '--help'],
            capture_output=True, text=True,
        )
        assert result.returncode == 0

    def test_json_output_is_valid_json(self):
        """--json produces parseable output."""
        import subprocess
        result = subprocess.run(
            [sys.executable, str(TESTS_DIR / 'lint.py'), '--json',
             '--paths', str(REPO_ROOT / 'Drivers' / 'Levoit')],
            capture_output=True, text=True,
            cwd=str(REPO_ROOT),
        )
        # Output must be valid JSON regardless of exit code
        data = json.loads(result.stdout)
        assert 'result' in data
        assert 'files_scanned' in data
        assert 'findings' in data
        assert data['result'] in ('PASS', 'FAIL', 'WARN')
