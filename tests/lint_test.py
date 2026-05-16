#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["pytest", "pyyaml"]
# ///
"""
lint_test.py — pytest tests for each lint rule.

Tests use in-memory Groovy snippets to verify rules fire on known-bad code
and stay silent on known-good code. No real driver files are modified.

Run:
    uv run --python 3.12 tests/lint_test.py -v
    # or from repo root:
    uv run --python 3.12 -m pytest tests/lint_test.py -v
"""

import json
import os
import subprocess
import sys
import tempfile
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
    if not name.endswith('.groovy') and not name.endswith('.md') and not name.endswith('.json') and not name.endswith('.py'):
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

from lint_rules.bp16_watchdog_call_site import check_rule25_bp16_watchdog_call_site
from lint_rules.groovy_javadoc_terminator import check_rule26_javadoc_terminator
from lint_rules.bp18_null_guard import check_rule27_bp18_null_guard
from lint_rules.captureDiagnostics_presence import check_rule28_capturediagnostics_presence
from lint_rules.library_no_top_block_comment import check_rule29_library_no_top_block_comment
from lint_rules.direct_log_calls import check_rule30_direct_log_in_driver
from lint_rules.bp24_state_switch_dead_branch import check_rule31_state_switch_dead_branch
from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP2_missing_deviceMethodFor_call')

    def test_bad_typename_contains_fails(self):
        # The old "correct" pattern is now also a violation — it re-inlines routing
        findings = run_rule(check_bp2_hardcoded_purifier_method, self.BAD_TYPENAME_CONTAINS, "VeSyncIntegration")
        assert any(f['rule_id'] == 'BP2_missing_deviceMethodFor_call' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP2_missing_deviceMethodFor_call')

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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP3_missing_envelope_peel')

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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP4_switchIdx_in_setLevel')

    def test_bad_type_instead_of_leveltype_fails(self):
        findings = run_rule(check_bp4_setlevel_field_names, self.BAD_TYPE, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP4_type_instead_of_levelType' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP4_type_instead_of_levelType')

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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP5_manual_via_setPurifierMode')

    def test_not_checked_for_core_drivers(self):
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.BAD, "LevoitCore400S")
        assert findings == []

    def test_not_checked_for_everestair(self):
        # EverestAir correctly uses setPurifierMode("manual") — BP5 is V201S-specific
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.BAD, "LevoitEverestAir")
        assert findings == [], (
            "BP5 must not fire on EverestAir: it uses setPurifierMode for manual mode correctly"
        )

    def test_not_checked_for_sproutair(self):
        # SproutAir same as EverestAir — V2-air class, setPurifierMode("manual") is correct
        findings = run_rule(check_bp5_manual_via_setPurifierMode, self.BAD, "LevoitSproutAir")
        assert findings == [], (
            "BP5 must not fire on SproutAir: it uses setPurifierMode for manual mode correctly"
        )


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP9_driver_name_changed')

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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP10_smartthings_icon')

    def test_my_apps_category_fails(self):
        findings = run_rule(check_bp10_smartthings_icons, self.BAD_MY_APPS, "LevoitCore200S")
        assert any(f['rule_id'] == 'BP10_smartthings_icon' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP10_smartthings_icon')


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP11_wrong_documentation_link')


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'BP12_missing_pref_seed')


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE13_direct_log_in_parent')

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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE14_bare_settings_ref')


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE15_missing_runin_1800')

    def test_bad_no_method_fails(self):
        findings = run_rule(check_rule15_auto_disable_wiring, self.BAD_NO_METHOD, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE15_missing_logdebugoff_method' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE15_missing_logdebugoff_method')

    # --- Library-include-aware cases (Phase 1 migration) ---

    # Driver that delegates logDebugOff to LevoitChildBase: has #include + runIn(1800)
    # but no local void logDebugOff() definition.  Rule must resolve the lib and pass.
    MIGRATED_VIA_LIB = textwrap.dedent("""\
        #include level99.LevoitDiagnostics
        #include level99.LevoitChildBase
        def updated() {
            if (settings?.debugOutput) runIn(1800, "logDebugOff")
        }
        // logDebugOff provided by LevoitChildBase library -- no local definition
    """)

    # Driver that claims to use a lib but the lib doesn't exist (unknown include).
    # The method is also absent from the driver source.  Rule must still FAIL.
    UNKNOWN_LIB_NO_METHOD = textwrap.dedent("""\
        #include level99.SomeNonExistentLib
        def updated() {
            if (settings?.debugOutput) runIn(1800, "logDebugOff")
        }
        // logDebugOff not present anywhere
    """)

    def test_lib_include_satisfies_logdebugoff_check(self):
        # LevoitChildBaseLib.groovy exists on disk and defines void logDebugOff().
        # A migrated driver referencing it via #include must pass RULE15.
        findings = run_rule(
            check_rule15_auto_disable_wiring, self.MIGRATED_VIA_LIB, "LevoitVital200S"
        )
        assert not any(f['rule_id'] == 'RULE15_missing_logdebugoff_method' for f in findings), \
            "RULE15 should not fire when logDebugOff() is provided by an #included library"

    def test_lib_include_does_not_satisfy_missing_runin(self):
        # The library provides logDebugOff() but cannot supply runIn(1800, ...) —
        # that call must appear in the driver's own updated().  Confirm still fails.
        src_no_runin = textwrap.dedent("""\
            #include level99.LevoitChildBase
            def updated() {
                // forgot runIn
            }
        """)
        findings = run_rule(
            check_rule15_auto_disable_wiring, src_no_runin, "LevoitVital200S"
        )
        assert any(f['rule_id'] == 'RULE15_missing_runin_1800' for f in findings), \
            "RULE15_missing_runin_1800 must still fire even when lib provides logDebugOff()"

    def test_unknown_lib_include_still_fails_when_method_absent(self):
        # An #include for an unknown/non-existent library is silently skipped.
        # If logDebugOff() is absent from both driver and lib, rule must FAIL.
        findings = run_rule(
            check_rule15_auto_disable_wiring, self.UNKNOWN_LIB_NO_METHOD, "LevoitVital200S"
        )
        assert any(f['rule_id'] == 'RULE15_missing_logdebugoff_method' for f in findings), \
            "Unknown #include must not mask a genuinely missing logDebugOff()"


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
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE17_forbidden_import')

    def test_eval_fails(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.BAD_EVAL, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE17_forbidden_reflection' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE17_forbidden_reflection')

    def test_class_for_name_fails(self):
        findings = run_rule(check_rule17_sandbox_forbidden, self.BAD_CLASS_FOR_NAME, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE17_forbidden_reflection' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE17_forbidden_reflection')


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

    # --- Arithmetic-on-null patterns (v2.5+ RULE27 extension) ---

    FAIL_ARITHMETIC_LT = textwrap.dedent("""\
        def setMistLevel(level) {
            if (level < 1) { level = 1 }
            hubBypass("setVirtualLevel", [virtualLevel: level], "setMistLevel")
        }
    """)

    FAIL_ARITHMETIC_GT = textwrap.dedent("""\
        def setTargetHumidity(humidity) {
            if (humidity > 80) { humidity = 80 }
            hubBypass("setTargetHumidity", [targetHumidity: humidity], "setTargetHumidity")
        }
    """)

    PASS_ARITHMETIC_AFTER_GUARD = textwrap.dedent("""\
        def setMistLevel(level) {
            if (level == null) { logWarn "setMistLevel called with null; ignoring"; return }
            Integer lvl = (level as Integer)
            if (lvl < 1) { lvl = 1 }
            hubBypass("setVirtualLevel", [virtualLevel: lvl], "setMistLevel")
        }
    """)

    def test_fail_arithmetic_lt_without_guard(self):
        """``if (level < 1)`` on raw param without null-guard: FAIL (arithmetic-on-null)."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_ARITHMETIC_LT)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL on unguarded 'if (level < 1)'"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_fail_arithmetic_gt_without_guard(self):
        """``if (humidity > 80)`` on raw param without null-guard: FAIL (arithmetic-on-null)."""
        findings = run_rule(check_rule27_bp18_null_guard, self.FAIL_ARITHMETIC_GT)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27, "Expected RULE27 FAIL on unguarded 'if (humidity > 80)'"
        assert all(f['severity'] == 'FAIL' for f in rule27)

    def test_pass_arithmetic_after_guard(self):
        """Arithmetic comparison on a local var (not raw param) after null-guard: no finding."""
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_ARITHMETIC_AFTER_GUARD)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], (
            f"Expected no RULE27 findings when arithmetic is on local var 'lvl' (not raw param "
            f"'level') and a null-guard precedes the cast, got: {rule27}"
        )


# ---------------------------------------------------------------------------
# RULE27 — BP1 1-arg update overload enforcement
# ---------------------------------------------------------------------------

class TestBP1Missing1ArgUpdate:
    """
    BP1_missing_1arg_update fires when a polled child has the 2-arg update() but
    is missing the 1-arg delegator.  The 1-arg form can be provided by the driver
    directly or by an #included library (library-aware via included_lib_texts).
    """

    from lint_rules.bug_patterns import check_bp1_missing_2arg_update as _rule

    GOOD_ALL_THREE = textwrap.dedent("""\
        def update() { refresh() }
        def update(status) { update(status, null) }
        def update(status, nightLight) { applyStatus(status); return true }
    """)

    BAD_MISSING_1ARG = textwrap.dedent("""\
        def update() { refresh() }
        def update(status, nightLight) { applyStatus(status); return true }
    """)

    def test_good_all_three_passes(self):
        """All three overloads present: no BP1 finding."""
        from lint_rules.bug_patterns import check_bp1_missing_2arg_update
        findings = run_rule(check_bp1_missing_2arg_update, self.GOOD_ALL_THREE, "LevoitVital200S")
        assert findings == []

    def test_bad_missing_1arg_fails(self):
        """Has 2-arg but missing 1-arg delegator: RULE BP1_missing_1arg_update fires."""
        from lint_rules.bug_patterns import check_bp1_missing_2arg_update
        findings = run_rule(check_bp1_missing_2arg_update, self.BAD_MISSING_1ARG, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP1_missing_1arg_update' for f in findings)
        assert any(f['severity'] == 'FAIL'
                   for f in findings if f['rule_id'] == 'BP1_missing_1arg_update')

    def test_missing_both_1arg_and_2arg_only_fires_2arg(self):
        """
        When both 1-arg AND 2-arg are absent, only BP1_missing_2arg_update fires —
        BP1_missing_1arg_update is conditional on 2-arg being present to avoid double-reporting.
        """
        from lint_rules.bug_patterns import check_bp1_missing_2arg_update
        src = "def update() { refresh() }"
        findings = run_rule(check_bp1_missing_2arg_update, src, "LevoitVital200S")
        assert any(f['rule_id'] == 'BP1_missing_2arg_update' for f in findings)
        assert not any(f['rule_id'] == 'BP1_missing_1arg_update' for f in findings), (
            "BP1_missing_1arg_update must not fire when 2-arg is also absent "
            "(would be double-reporting; fix 2-arg first)"
        )


# ---------------------------------------------------------------------------
# RULE33 — BP25 case-sensitivity (set* methods, on/off param normalization)
# ---------------------------------------------------------------------------

class TestRule33CaseSensitivity:
    """
    RULE33 (Bug Pattern #25): set* methods must call toLowerCase() before comparing
    a raw on/off parameter against the string literals "on" / "off".
    """

    from lint_rules.bp25_case_sensitivity import check_rule33_case_sensitivity as _rule

    # A method that normalizes before comparing — must NOT flag.
    GOOD_NORMALIZED = textwrap.dedent("""\
        def setChildLock(onOff) {
            if (!requireNotNull(onOff, "setChildLock")) return
            String v = (onOff as String).toLowerCase()
            if (device.currentValue("childLock") == v) return true
            Integer sw = (v == "on") ? 1 : 0
            hubBypass("setChildLock", [childLockSwitch: sw], "setChildLock(${v})")
        }
    """)

    # A method that compares raw onOff against "on" without toLowerCase() — MUST flag.
    BAD_RAW_COMPARE = textwrap.dedent("""\
        def setChildLock(onOff) {
            if (!requireNotNull(onOff, "setChildLock")) return
            Integer sw = (onOff == "on") ? 1 : 0
            hubBypass("setChildLock", [childLockSwitch: sw], "setChildLock(${onOff})")
        }
    """)

    # A method that compares a DIFFERENT var (not the raw param) without toLowerCase.
    # The rule skips derived-variable comparisons (param is "onOff"; compared var is "v").
    GOOD_DERIVED_NO_LOWER = textwrap.dedent("""\
        def setDisplay(onOff) {
            String v = onOff ? "on" : "off"
            Integer sw = (v == "on") ? 1 : 0
            hubBypass("setDisplay", [screenSwitch: sw], "setDisplay(${v})")
        }
    """)

    def test_normalized_passes(self):
        from lint_rules.bp25_case_sensitivity import check_rule33_case_sensitivity
        findings = run_rule(check_rule33_case_sensitivity, self.GOOD_NORMALIZED)
        assert findings == [], f"Expected no findings on normalized method, got: {findings}"

    def test_raw_compare_fails(self):
        """Comparing raw param against 'on' without toLowerCase() must flag RULE33 with severity FAIL."""
        from lint_rules.bp25_case_sensitivity import check_rule33_case_sensitivity
        findings = run_rule(check_rule33_case_sensitivity, self.BAD_RAW_COMPARE)
        assert any(f['rule_id'] == 'RULE33_case_sensitivity' for f in findings), (
            f"Expected RULE33_case_sensitivity, got: {findings}"
        )
        # Regression guard: missing severity key is a dead-gate — verify it's present and FAIL.
        assert any(f.get('severity') == 'FAIL' for f in findings if f.get('rule_id') == 'RULE33_case_sensitivity'), (
            f"RULE33 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_derived_variable_no_lower_passes(self):
        """Rule only flags the raw parameter — derived local vars are not flagged."""
        from lint_rules.bp25_case_sensitivity import check_rule33_case_sensitivity
        findings = run_rule(
            check_rule33_case_sensitivity, self.GOOD_DERIVED_NO_LOWER
        )
        assert not any(f['rule_id'] == 'RULE33_case_sensitivity' for f in findings), (
            f"Derived-variable comparison must not trigger RULE33, got: {findings}"
        )

    def test_exemption_suppresses_finding(self):
        """A config-level exemption must suppress the finding."""
        from lint_rules.bp25_case_sensitivity import check_rule33_case_sensitivity
        path = make_fake_path("TestDriver.groovy")
        file_rel = str(path.relative_to(REPO_ROOT)).replace('\\', '/')
        config = {
            'bp25_case_sensitivity_exemptions': [
                {'file': file_rel, 'method': 'setChildLock', 'rationale': 'test exemption'}
            ]
        }
        findings = run_rule(
            check_rule33_case_sensitivity, self.BAD_RAW_COMPARE, config=config
        )
        assert not any(f['rule_id'] == 'RULE33_case_sensitivity' for f in findings), (
            f"Exempted method must not trigger RULE33, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE34 — BP14 poll persistence (VeSyncIntegration only)
# ---------------------------------------------------------------------------

class TestRule34BP14PollPersistence:
    """
    RULE34 (Bug Pattern #14): parent driver must use schedule()-based poll cron,
    not runIn() chains for updateDevices.
    """

    from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence as _rule

    GOOD = textwrap.dedent("""\
        def updateDevices() {
            ensurePollHealth()
            getChildDevices().each { dev -> /* poll */ }
        }
        private void setupPollSchedule(int interval) {
            unschedule("updateDevices")
            String cron = "0 */${interval} * * * ?"
            schedule(cron, "updateDevices")
        }
    """)

    BAD_RUNIN_UPDATEDEVICES = textwrap.dedent("""\
        def updateDevices() {
            getChildDevices().each { dev -> /* poll */ }
            runIn(60, "updateDevices")
        }
        private void setupPollSchedule(int interval) {
            schedule("0 */1 * * * ?", "updateDevices")
        }
    """)

    BAD_MISSING_SETUP = textwrap.dedent("""\
        def updateDevices() {
            schedule("0 */1 * * * ?", "updateDevices")
            getChildDevices().each { dev -> /* poll */ }
        }
        // setupPollSchedule missing
    """)

    BAD_MISSING_SCHEDULE = textwrap.dedent("""\
        def updateDevices() {
            runIn(60, "logDebugOff")
            getChildDevices().each { dev -> /* poll */ }
        }
        private void setupPollSchedule(int interval) {
            // forgot to call schedule()
        }
    """)

    def test_good_passes(self):
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        findings = run_rule(check_rule34_bp14_poll_persistence, self.GOOD, "VeSyncIntegration")
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_runin_updatedevices_fails(self):
        """runIn(N, 'updateDevices') is the known anti-pattern: FAIL."""
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        findings = run_rule(
            check_rule34_bp14_poll_persistence, self.BAD_RUNIN_UPDATEDEVICES, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE34_runin_updateDevices' for f in findings)
        assert any(f['severity'] == 'FAIL'
                   for f in findings if f['rule_id'] == 'RULE34_runin_updateDevices')

    def test_missing_setup_poll_schedule_fails(self):
        """setupPollSchedule() missing: FAIL."""
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        findings = run_rule(
            check_rule34_bp14_poll_persistence, self.BAD_MISSING_SETUP, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE34_missing_setupPollSchedule' for f in findings)

    def test_missing_schedule_call_fails(self):
        """No schedule() call anywhere: FAIL."""
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        findings = run_rule(
            check_rule34_bp14_poll_persistence, self.BAD_MISSING_SCHEDULE, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE34_missing_schedule_call' for f in findings)

    def test_exempt_runin_handlers_not_flagged(self):
        """runIn with exempted handlers (logDebugOff, initialize, etc.) must not flag."""
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        src = textwrap.dedent("""\
            def updated() {
                runIn(1800, "logDebugOff")
                runIn(15, "initialize")
                runIn(2, "getDevices")
            }
            private void setupPollSchedule(int interval) {
                schedule("0 */1 * * * ?", "updateDevices")
            }
        """)
        findings = run_rule(check_rule34_bp14_poll_persistence, src, "VeSyncIntegration")
        runin_fails = [f for f in findings if f['rule_id'] == 'RULE34_runin_updateDevices']
        assert runin_fails == [], (
            f"Exempted runIn handlers must not trigger RULE34_runin_updateDevices, got: {runin_fails}"
        )

    def test_not_checked_for_child_drivers(self):
        """Child drivers are out of scope: no findings even on bad patterns."""
        from lint_rules.bp14_poll_persistence import check_rule34_bp14_poll_persistence
        findings = run_rule(
            check_rule34_bp14_poll_persistence, self.BAD_RUNIN_UPDATEDEVICES, "LevoitVital200S"
        )
        assert findings == []


# ---------------------------------------------------------------------------
# RULE35 — BP17 poll health watchdog (VeSyncIntegration only)
# ---------------------------------------------------------------------------

class TestRule35BP17PollHealth:
    """
    RULE35 (Bug Pattern #17): ensurePollHealth() must be defined and called inside
    updateDevices() in VeSyncIntegration.groovy.
    """

    GOOD = textwrap.dedent("""\
        def updateDevices() {
            ensurePollHealth()
            getChildDevices().each { dev -> /* poll */ }
        }
        private void ensurePollHealth() {
            // tracks consecutiveEmpty + triggers getDevices() on threshold
        }
    """)

    BAD_MISSING_DEF = textwrap.dedent("""\
        def updateDevices() {
            ensurePollHealth()
            getChildDevices().each { dev -> /* poll */ }
        }
        // ensurePollHealth definition is missing
    """)

    BAD_NOT_CALLED = textwrap.dedent("""\
        def updateDevices() {
            getChildDevices().each { dev -> /* poll */ }
        }
        private void ensurePollHealth() {
            // defined but not called
        }
    """)

    def test_good_passes(self):
        from lint_rules.bp17_poll_health import check_rule35_bp17_poll_health
        findings = run_rule(check_rule35_bp17_poll_health, self.GOOD, "VeSyncIntegration")
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_missing_def_fails(self):
        """ensurePollHealth() not defined: FAIL."""
        from lint_rules.bp17_poll_health import check_rule35_bp17_poll_health
        findings = run_rule(check_rule35_bp17_poll_health, self.BAD_MISSING_DEF, "VeSyncIntegration")
        assert any(f['rule_id'] == 'RULE35_missing_ensurePollHealth_def' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE35_missing_ensurePollHealth_def')

    def test_not_called_in_updatedevices_fails(self):
        """ensurePollHealth() defined but not called in updateDevices(): FAIL."""
        from lint_rules.bp17_poll_health import check_rule35_bp17_poll_health
        findings = run_rule(check_rule35_bp17_poll_health, self.BAD_NOT_CALLED, "VeSyncIntegration")
        assert any(f['rule_id'] == 'RULE35_ensurePollHealth_not_called' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE35_ensurePollHealth_not_called')

    def test_not_checked_for_child_drivers(self):
        """Child drivers are out of scope."""
        from lint_rules.bp17_poll_health import check_rule35_bp17_poll_health
        findings = run_rule(check_rule35_bp17_poll_health, self.BAD_NOT_CALLED, "LevoitSuperior6000S")
        assert findings == []

    def test_return_type_in_signature_is_handled(self):
        """``def Boolean updateDevices()`` with next-line brace is correctly detected."""
        from lint_rules.bp17_poll_health import check_rule35_bp17_poll_health
        src = textwrap.dedent("""\
            def Boolean updateDevices()
            {
                ensurePollHealth()
                getChildDevices().each { dev -> /* poll */ }
            }
            private void ensurePollHealth() {
                // watchdog body
            }
        """)
        findings = run_rule(check_rule35_bp17_poll_health, src, "VeSyncIntegration")
        assert findings == [], (
            f"Expected no findings on 'def Boolean updateDevices()' form, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE36 — BP22 network circuit-breaker invariants (VeSyncIntegration only)
# ---------------------------------------------------------------------------

class TestRule36BP22NetworkBreaker:
    """
    RULE36 (Bug Pattern #22): dual network circuit-breaker state keys and helper
    must all be present in VeSyncIntegration.groovy.
    """

    GOOD = textwrap.dedent("""\
        def updateDevices() {
            if (state.networkUnreachableSince != null && !state.networkProbeInFlight) {
                emitNetworkWarnIfDue()
                return
            }
            state.networkProbeInFlight = false
        }
        private void emitNetworkWarnIfDue() {
            if (state.networkUnreachableSince == null) return
            // hourly warn re-surface
        }
        def sendBypassRequest(dev, Map payload) {
            if (state.networkUnreachableSince != null && !state.networkProbeInFlight) return null
        }
    """)

    BAD_MISSING_UNREACHABLE_SINCE = textwrap.dedent("""\
        def updateDevices() {
            // no network breaker state
            getChildDevices().each { dev -> /* poll */ }
        }
        private void emitNetworkWarnIfDue() { }
    """)

    BAD_MISSING_PROBE_IN_FLIGHT = textwrap.dedent("""\
        def updateDevices() {
            if (state.networkUnreachableSince != null) return
            emitNetworkWarnIfDue()
        }
        private void emitNetworkWarnIfDue() {
            if (state.networkUnreachableSince == null) return
        }
    """)

    BAD_MISSING_EMIT_DEF = textwrap.dedent("""\
        def updateDevices() {
            if (state.networkUnreachableSince != null && !state.networkProbeInFlight) return
        }
        // emitNetworkWarnIfDue missing
    """)

    def test_good_passes(self):
        from lint_rules.bp22_network_breaker import check_rule36_bp22_network_breaker
        findings = run_rule(check_rule36_bp22_network_breaker, self.GOOD, "VeSyncIntegration")
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_missing_unreachable_since_fails(self):
        """state.networkUnreachableSince not referenced: FAIL."""
        from lint_rules.bp22_network_breaker import check_rule36_bp22_network_breaker
        findings = run_rule(
            check_rule36_bp22_network_breaker, self.BAD_MISSING_UNREACHABLE_SINCE, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE36_missing_networkUnreachableSince' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE36_missing_networkUnreachableSince')

    def test_missing_probe_in_flight_fails(self):
        """state.networkProbeInFlight not referenced: FAIL."""
        from lint_rules.bp22_network_breaker import check_rule36_bp22_network_breaker
        findings = run_rule(
            check_rule36_bp22_network_breaker, self.BAD_MISSING_PROBE_IN_FLIGHT, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE36_missing_networkProbeInFlight' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE36_missing_networkProbeInFlight')

    def test_missing_emit_def_fails(self):
        """emitNetworkWarnIfDue() not defined: FAIL."""
        from lint_rules.bp22_network_breaker import check_rule36_bp22_network_breaker
        findings = run_rule(
            check_rule36_bp22_network_breaker, self.BAD_MISSING_EMIT_DEF, "VeSyncIntegration"
        )
        assert any(f['rule_id'] == 'RULE36_missing_emitNetworkWarnIfDue' for f in findings)
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE36_missing_emitNetworkWarnIfDue')

    def test_not_checked_for_child_drivers(self):
        """Child drivers are out of scope."""
        from lint_rules.bp22_network_breaker import check_rule36_bp22_network_breaker
        findings = run_rule(
            check_rule36_bp22_network_breaker, self.BAD_MISSING_EMIT_DEF, "LevoitSuperior6000S"
        )
        assert findings == []


# ---------------------------------------------------------------------------
# RULE37 — BP26 unsafe integer coercion in command parameters
# ---------------------------------------------------------------------------

class TestRule37BP26UnsafeIntCoercion:
    """
    RULE37 (Bug Pattern #26): set*/cycle* methods must not use bare ``as Integer``
    or ``.toInteger()`` on untyped command parameters.  These throw on non-numeric
    Rule Machine inputs before ``?:`` can rescue.  Use ``safeIntArg()`` instead.
    """

    from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion as _rule

    GOOD_SAFE_INT_ARG = textwrap.dedent("""\
        def setMistLevel(level) {
            if (!requireNotNull(level, "setMistLevel")) return
            Integer lvl = safeIntArg(level, 0)
            if (lvl <= 0) { off(); return }
            Integer clamped = Math.max(1, Math.min(9, lvl))
            hubBypass("setVirtualLevel", [level: clamped], "setVirtualLevel(${clamped})")
        }
    """)

    GOOD_TYPED_PARAM = textwrap.dedent("""\
        def setMistLevel(Integer level) {
            Integer lvl = (level as Integer) ?: 0
            hubBypass("setVirtualLevel", [level: lvl], "setVirtualLevel(${lvl})")
        }
    """)

    BAD_AS_INTEGER = textwrap.dedent("""\
        def setMistLevel(level) {
            Integer lvl = (level as Integer) ?: 0
            if (lvl <= 0) { off(); return }
            hubBypass("setVirtualLevel", [level: lvl], "setVirtualLevel(${lvl})")
        }
    """)

    BAD_TO_INTEGER = textwrap.dedent("""\
        def setFanSpeed(speed) {
            Integer spd = speed.toInteger()
            hubBypass("setLevel", [manualSpeedLevel: spd], "setLevel(${spd})")
        }
    """)

    BAD_AS_INT = textwrap.dedent("""\
        def setTargetHumidity(percent) {
            Integer p = (percent as int)
            hubBypass("setTargetHumidity", [targetHumidity: p], "setTarget(${p})")
        }
    """)

    def test_safe_int_arg_passes(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_SAFE_INT_ARG)
        assert findings == [], f"safeIntArg usage must not flag, got: {findings}"

    def test_typed_param_passes(self):
        """Typed parameter (Integer level) is exempt — sandbox enforces type at dispatch."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_TYPED_PARAM)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Typed param must not flag RULE37, got: {findings}"
        )

    def test_as_integer_on_param_fails(self):
        """Bare ``(level as Integer)`` on raw command param must flag RULE37 with severity FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_AS_INTEGER)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for (level as Integer), got: {findings}"
        )
        # Regression guard: missing severity key is a dead-gate — verify it's present and FAIL.
        assert any(f.get('severity') == 'FAIL' for f in findings if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_to_integer_on_param_fails(self):
        """``.toInteger()`` on raw command param must flag RULE37 with severity FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_TO_INTEGER)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for .toInteger(), got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_as_int_on_param_fails(self):
        """Bare ``(percent as int)`` on raw command param must flag RULE37 with severity FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_AS_INT)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for (percent as int), got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_exemption_suppresses_finding(self):
        """Config-level exemption must suppress the finding."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        path = make_fake_path("TestDriver.groovy")
        file_rel = str(path.relative_to(REPO_ROOT)).replace('\\', '/')
        config = {
            'bp26_unsafe_int_coercion_exemptions': [
                {'file': file_rel, 'method': 'setMistLevel', 'rationale': 'test exemption'}
            ]
        }
        findings = run_rule(
            check_rule37_unsafe_int_coercion, self.BAD_AS_INTEGER, config=config
        )
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Exempted method must not flag RULE37, got: {findings}"
        )

    # B2b regression pin: bare unparenthesized `seconds as Integer` must also flag RULE37.
    # This is the TowerFan:270 shape that the original AS_INTEGER_RE (requiring parens)
    # missed — the false-negative that B2b closes.
    BAD_BARE_CAST = textwrap.dedent("""\
        def setTimer(seconds) {
            if (!requireNotNull(seconds, "setTimer")) return
            int secs = seconds as Integer
            if (secs <= 0) { cancelTimer(); return }
        }
    """)

    def test_bare_unparenthesized_as_integer_fails(self):
        """Bare ``seconds as Integer`` (no parentheses) must flag RULE37 — TowerFan:270 shape."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_BARE_CAST)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for bare unparenthesized 'seconds as Integer', "
            f"got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL'; got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE37 extension — BP26 coercion-expression in safeIntArg fallback position
# ---------------------------------------------------------------------------

class TestRule37BP26SafeIntArgFallbackCoercion:
    """
    RULE37 extension: a coercion expression (``as Integer`` / ``as int`` /
    ``.toInteger()``) used as the fallback argument inside a ``safeIntArg()``
    call is a BP26 violation.

    safeIntArg's try/catch protects its OWN evaluation — not the expressions
    passed as its arguments.  A fallback like
    ``safeIntArg(x, (state.y ?: 800) as Integer)`` evaluates the cast BEFORE
    the call, so a non-numeric ``state.y`` still throws NumberFormatException
    that the sandbox swallows silently.

    Correct form: ``safeIntArg(x, safeIntArg(state.y, 800))`` — the inner call
    guards the fallback expression.  Literal fallbacks (``safeIntArg(x, 800)``)
    are safe as-is.

    Dead-gate severity assertion: the finding MUST carry ``severity='FAIL'``
    to block ``lint --strict``.  A missing or wrong severity key produces a
    vacuous guard — identical to the Tier-9 false-negative that prompted adding
    this class of test.
    """

    from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion as _rule

    # Good: nested safeIntArg guards the fallback — correct form, must NOT flag.
    GOOD_NESTED = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            if (!requireNotNull(mode, "setAutoMode")) return
            Integer sz = safeIntArg(roomSize, safeIntArg(state.room_size, 800))
            sz = Math.max(1, sz)
        }
    """)

    # Good: literal fallback — no cast at all, must NOT flag.
    GOOD_LITERAL = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(roomSize, 800)
        }
    """)

    # Bad: coercion-expression as fallback — must flag RULE37 with severity FAIL.
    BAD_FALLBACK_AS_INTEGER = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(roomSize, (state.room_size ?: 800) as Integer)
        }
    """)

    def test_nested_safeintarg_fallback_passes(self):
        """Nested safeIntArg() as fallback must NOT flag — the inner call is the guard."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_NESTED)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Nested safeIntArg fallback must not flag RULE37, got: {findings}"
        )

    def test_literal_fallback_passes(self):
        """Literal constant fallback must NOT flag — no coercion at all."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_LITERAL)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Literal fallback must not flag RULE37, got: {findings}"
        )

    def test_coercion_expression_fallback_fails(self):
        """
        ``safeIntArg(x, (expr) as Integer)`` must flag RULE37 with severity FAIL.

        Regression guard: proves the extension closes the gap left by the original
        RULE37 implementation (which bailed out on any ``safeIntArg`` presence).
        This test FAILS on pre-extension code and PASSES on the extended rule,
        making it a valid empirical both-ways proof checkpoint.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_FALLBACK_AS_INTEGER)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for coercion-expression fallback, got: {findings}"
        )
        # Dead-gate severity assertion: missing severity key is a vacuous guard.
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE20 version_lockstep — _extract_definition_block parser robustness
# ---------------------------------------------------------------------------

class TestExtractDefinitionBlock:
    """Regression tests for _extract_definition_block — the parser that locates
    the metadata definition() block before extracting the version: field.

    Surfaced 2026-04-29: when a driver source contains a `// definition(name:)`
    line comment followed (much later) by an unrelated `if (cond) {` closure
    block, the original regex matched the comment's `definition(` token and
    extended through the unrelated `) {` — capturing a sprawling 'interior'
    that did NOT include the real metadata block, masking the real version: field.
    Fix: strip line comments (`re.sub` with `(?m)^\\s*//.*$`) before the match.
    """

    def test_normal_definition_block_is_extracted(self):
        """Happy path: standard driver source — version field found correctly."""
        from lint_rules.version_lockstep import _extract_definition_block, _extract_version_from_block
        source = '''
import groovy.transform.Field

metadata {
    definition(
        name: "Example",
        namespace: "level99",
        version: "2.3"
    ) {
        capability "Refresh"
    }
}
'''
        interior, lineno = _extract_definition_block(source)
        assert interior is not None
        assert _extract_version_from_block(interior) == "2.3"

    def test_comment_with_definition_token_does_not_confuse_parser(self):
        """Regression: line comment containing `definition(name:)` followed
        later by an unrelated `if (cond) {` must NOT capture the comment as
        the start of the definition block. The real definition block at the
        bottom must still be located and its version extracted.
        """
        from lint_rules.version_lockstep import _extract_definition_block, _extract_version_from_block
        source = '''
import groovy.transform.Field

// The driver name strings MUST match the real child driver's definition(name:)
// exactly — Hubitat resolves child drivers by name string on addChildDevice().

@Field static final Map FIXTURE_TO_DRIVER = ["x": "y"]

private void example() {
    if (data?.containsKey("level")) {
        // unrelated closure body; this `) {` would have terminated the
        // bogus interior captured by the comment-confused regex.
    }
}

metadata {
    definition(
        name: "Example",
        namespace: "level99",
        version: "2.3"
    ) {
        capability "Refresh"
    }
}
'''
        interior, lineno = _extract_definition_block(source)
        assert interior is not None, "definition block should be found despite comment confusion"
        version = _extract_version_from_block(interior)
        assert version == "2.3", (
            f"version: '2.3' should be extracted from the real definition block; "
            f"got {version!r} (interior len={len(interior)})"
        )

    def test_no_definition_block_returns_none(self):
        """Source without any definition() block returns (None, 1)."""
        from lint_rules.version_lockstep import _extract_definition_block
        source = '''
// Just a comment with definition(name:)
def helper() {}
'''
        interior, lineno = _extract_definition_block(source)
        assert interior is None
        assert lineno == 1


# ---------------------------------------------------------------------------
# Integration: lint.py exit codes
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# RULE28 — captureDiagnostics Phase 5 presence check
# ---------------------------------------------------------------------------

class TestRule28CaptureDiagnosticsPresence:
    """RULE28: every in-scope Levoit driver must have the Phase 5 plumbing:
    #include level99.LevoitDiagnostics, command "captureDiagnostics", and
    attribute "diagnostics", "string".
    """

    GOOD = textwrap.dedent("""\
        #include level99.LevoitDiagnostics

        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson",
                       version: "2.3") {
                capability "Switch"
                attribute "diagnostics",     "string"
                command "captureDiagnostics"
            }
        }
        def updated() { state.clear() }
    """)

    # Library file — should be out-of-scope regardless of content
    LIBRARY_FILE = textwrap.dedent("""\
        library(
            name: "LevoitDiagnostics",
            namespace: "level99"
        )
        void recordError(String msg, Map ctx = [:]) { }
    """)

    MISSING_INCLUDE = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson",
                       version: "2.3") {
                capability "Switch"
                attribute "diagnostics",     "string"
                command "captureDiagnostics"
            }
        }
    """)

    MISSING_COMMAND = textwrap.dedent("""\
        #include level99.LevoitDiagnostics

        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson",
                       version: "2.3") {
                capability "Switch"
                attribute "diagnostics",     "string"
            }
        }
    """)

    MISSING_ATTRIBUTE = textwrap.dedent("""\
        #include level99.LevoitDiagnostics

        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson",
                       version: "2.3") {
                capability "Switch"
                command "captureDiagnostics"
            }
        }
    """)

    WRONG_ATTR_TYPE = textwrap.dedent("""\
        #include level99.LevoitDiagnostics

        metadata {
            definition(name: "Levoit Vital 200S Air Purifier", namespace: "NiklasGustafsson",
                       version: "2.3") {
                capability "Switch"
                attribute "diagnostics",     "String"
                command "captureDiagnostics"
            }
        }
    """)

    def test_good_driver_passes(self):
        findings = run_rule(check_rule28_capturediagnostics_presence, self.GOOD, "LevoitVital200S")
        assert findings == []

    def test_library_file_is_out_of_scope(self):
        # Library files use library() block — RULE28 must skip them (PASS)
        findings = run_rule(check_rule28_capturediagnostics_presence, self.LIBRARY_FILE, "LevoitDiagnosticsLib")
        assert findings == []

    def test_out_of_scope_virtual_passes(self):
        # VeSyncIntegrationVirtual.groovy — test harness, explicitly out-of-scope
        findings = run_rule(check_rule28_capturediagnostics_presence, self.MISSING_INCLUDE, "VeSyncIntegrationVirtual")
        assert findings == []

    def test_out_of_scope_generic_passes(self):
        # LevoitGeneric.groovy — native captureDiagnostics; intentionally no #include
        findings = run_rule(check_rule28_capturediagnostics_presence, self.MISSING_INCLUDE, "LevoitGeneric")
        assert findings == []

    def test_missing_include_fails(self):
        findings = run_rule(check_rule28_capturediagnostics_presence, self.MISSING_INCLUDE, "LevoitVital200S")
        rule_ids_found = [f['rule_id'] for f in findings]
        assert "RULE28_missing_include_LevoitDiagnostics" in rule_ids_found
        assert all(f['severity'] == 'FAIL' for f in findings)

    def test_missing_command_fails(self):
        findings = run_rule(check_rule28_capturediagnostics_presence, self.MISSING_COMMAND, "LevoitVital200S")
        rule_ids_found = [f['rule_id'] for f in findings]
        assert "RULE28_missing_command_captureDiagnostics" in rule_ids_found
        assert any(f['severity'] == 'FAIL' for f in findings)

    def test_missing_attribute_fails(self):
        findings = run_rule(check_rule28_capturediagnostics_presence, self.MISSING_ATTRIBUTE, "LevoitVital200S")
        rule_ids_found = [f['rule_id'] for f in findings]
        assert "RULE28_missing_attribute_diagnostics" in rule_ids_found
        assert any(f['severity'] == 'FAIL' for f in findings)

    def test_wrong_attribute_type_fails(self):
        # attribute "diagnostics", "String" (capital S) must fail — Hubitat needs lowercase "string".
        # ATTR_RE is case-sensitive by design; re.IGNORECASE was removed because Hubitat's
        # attribute type enum requires the exact literal "string".
        findings = run_rule(check_rule28_capturediagnostics_presence, self.WRONG_ATTR_TYPE, "LevoitVital200S")
        rule_ids_found = [f['rule_id'] for f in findings]
        assert "RULE28_missing_attribute_diagnostics" in rule_ids_found
        assert any(f['severity'] == 'FAIL' for f in findings)

    def test_not_checked_for_non_groovy(self):
        # Python files, markdown, etc. must not be checked
        path = REPO_ROOT / "tests" / "lint_test.py"
        from lint_rules.groovy_lite import clean_source
        src = "attribute 'diagnostics', 'string'\ncommand 'captureDiagnostics'\n"
        raw_lines = src.splitlines()
        _, cleaned_lines = clean_source(src)
        findings = check_rule28_capturediagnostics_presence(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )
        assert findings == []


# ---------------------------------------------------------------------------
# Rule 30 — Direct log.X calls in driver body
# ---------------------------------------------------------------------------

class TestRule30DirectLogInDriver:
    # Good: all calls routed through helpers; no direct log.X in body.
    GOOD_MIGRATED = textwrap.dedent("""\
        #include level99.LevoitChildBase
        metadata { definition(name: "Test Driver", namespace: "ns", author: "a") {} }
        def applyStatus(status) {
            logDebug "some debug msg"
            logInfo "some info msg"
            logError "some error msg"
            logWarn "some warn msg"
            logAlways "always visible"
        }
        // logDebug, logInfo, etc. provided by lib
    """)

    # Good: helper method body legitimately contains log.X — must NOT be flagged.
    GOOD_HELPER_BODY = textwrap.dedent("""\
        metadata { definition(name: "Test Driver", namespace: "ns", author: "a") {} }
        def logDebug(msg) { if (settings?.debugOutput) log.debug msg }
        def logInfo(msg)  { if (settings?.descriptionTextEnable) log.info msg }
        def logError(msg) { log.error msg }
        void logDebugOff() { device.updateSetting("debugOutput", [type:"bool", value:false]) }
        def applyStatus(status) {
            logDebug "ok"
        }
    """)

    # Good: library file — direct log.X inside helper bodies is correct, rule must not fire.
    GOOD_LIBRARY_FILE = textwrap.dedent("""\
        library(name: "TestLib", namespace: "level99", author: "a", description: "d",
                importUrl: "http://example.com", documentationLink: "http://example.com")
        def logDebug(msg) { if (settings?.debugOutput) log.debug msg }
        def logInfo(msg)  { log.info msg }
    """)

    # Good: VeSyncIntegration.groovy is excluded by name.
    GOOD_PARENT_EXCLUDED = textwrap.dedent("""\
        metadata { definition(name: "VeSync Integration", namespace: "ns", author: "a") {} }
        def logInfo(msg) { if (settings?.descriptionTextEnable) log.info sanitize(msg) }
        def someMethod() { log.info "direct but excluded by filename" }
    """)

    # Bad: direct log.debug in driver body outside helper.
    BAD_DIRECT_LOG_DEBUG = textwrap.dedent("""\
        metadata { definition(name: "Test Driver", namespace: "ns", author: "a") {} }
        def applyStatus(status) {
            if (settings?.debugOutput) log.debug "raw diagnostic"
        }
        def logDebug(msg) { if (settings?.debugOutput) log.debug msg }
    """)

    # Bad: direct log.info in driver body (should use logInfo or logAlways).
    BAD_DIRECT_LOG_INFO = textwrap.dedent("""\
        metadata { definition(name: "Test Driver", namespace: "ns", author: "a") {} }
        def probeDevice() {
            log.info "probe result"
        }
        def logInfo(msg) { if (settings?.descriptionTextEnable) log.info msg }
    """)

    # Bad: direct log.error in driver body.
    BAD_DIRECT_LOG_ERROR = textwrap.dedent("""\
        metadata { definition(name: "Test Driver", namespace: "ns", author: "a") {} }
        def checkHttpResponse(action, resp) {
            log.error "${action}: ${resp.status}"
        }
        def logError(msg) { log.error msg }
    """)

    def test_migrated_driver_passes(self):
        findings = run_rule(check_rule30_direct_log_in_driver, self.GOOD_MIGRATED, "LevoitVital200S")
        assert not any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Migrated driver using lib helpers must pass RULE30"

    def test_helper_body_exempt(self):
        # Direct log.X inside def logDebug() / def logInfo() bodies must not be flagged.
        findings = run_rule(check_rule30_direct_log_in_driver, self.GOOD_HELPER_BODY, "LevoitVital200S")
        assert not any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "log.X inside helper method bodies must not trigger RULE30"

    def test_library_file_exempt(self):
        # Library files are excluded from RULE30.
        findings = run_rule(check_rule30_direct_log_in_driver, self.GOOD_LIBRARY_FILE, "LevoitChildBaseLib")
        assert not any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Library file log.X calls must not trigger RULE30"

    def test_parent_driver_excluded_by_name(self):
        # VeSyncIntegration.groovy is excluded.
        findings = run_rule(check_rule30_direct_log_in_driver, self.GOOD_PARENT_EXCLUDED, "VeSyncIntegration")
        assert not any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "VeSyncIntegration.groovy must be excluded from RULE30"

    def test_direct_log_debug_fails(self):
        findings = run_rule(check_rule30_direct_log_in_driver, self.BAD_DIRECT_LOG_DEBUG, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Direct log.debug in driver body must trigger RULE30"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE30_direct_log_in_driver')

    def test_direct_log_info_fails(self):
        findings = run_rule(check_rule30_direct_log_in_driver, self.BAD_DIRECT_LOG_INFO, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Direct log.info in driver body must trigger RULE30"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE30_direct_log_in_driver')

    def test_direct_log_error_fails(self):
        findings = run_rule(check_rule30_direct_log_in_driver, self.BAD_DIRECT_LOG_ERROR, "LevoitVital200S")
        assert any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Direct log.error in driver body must trigger RULE30"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE30_direct_log_in_driver')

    def test_notification_tile_excluded_by_name(self):
        # Notification Tile.groovy is excluded (logsOff() pattern, deferred).
        src = textwrap.dedent("""\
            metadata { definition(name: "Notification Tile", namespace: "ns", author: "a") {} }
            def deviceNotification(msg) {
                log.debug "raw debug in body"
            }
        """)
        findings = run_rule(
            check_rule30_direct_log_in_driver, src, "Notification Tile"
        )
        assert not any(f['rule_id'] == 'RULE30_direct_log_in_driver' for f in findings), \
            "Notification Tile.groovy must be excluded from RULE30"


# ---------------------------------------------------------------------------
# TestRule29LibraryNoBlockComment
# ---------------------------------------------------------------------------

class TestRule29LibraryNoBlockComment:
    """RULE29: /* */ block comments forbidden in Hubitat library files."""

    # ---- file-scope fixtures ----

    GOOD_MIT_ONLY = textwrap.dedent("""\
        /*
         * MIT License
         */

        library(
            name: "MyLib",
            namespace: "ns",
            author: "a"
        )

        // This is a line comment — fine.
        def foo() { return 1 }
    """)

    GOOD_BODY_LINE_COMMENTS_ONLY = textwrap.dedent("""\
        /*
         * MIT License
         */

        library(
            name: "MyLib",
            namespace: "ns",
            author: "a"
        )

        // Helper: does something useful.
        // Returns true on success.
        def foo() { return true }

        // Another helper.
        def bar() { return false }
    """)

    BAD_FILE_SCOPE_SECOND_BLOCK = textwrap.dedent("""\
        /*
         * MIT License
         */

        /* Second block comment before library() — forbidden */

        library(
            name: "MyLib",
            namespace: "ns",
            author: "a"
        )

        def foo() { return 1 }
    """)

    BAD_BODY_SCOPE_JAVADOC = textwrap.dedent("""\
        /*
         * MIT License
         */

        library(
            name: "MyLib",
            namespace: "ns",
            author: "a"
        )

        /**
         * This is a javadoc block after library() — forbidden in library files.
         */
        def foo() { return 1 }
    """)

    BAD_BODY_SCOPE_PLAIN_BLOCK = textwrap.dedent("""\
        /*
         * MIT License
         */

        library(
            name: "MyLib",
            namespace: "ns",
            author: "a"
        )

        /* A plain block comment after library() — also forbidden */
        def foo() { return 1 }
    """)

    GOOD_DRIVER_BODY_SCOPE_JAVADOC = textwrap.dedent("""\
        /*
         * MIT License
         */

        metadata {
            definition(name: "Levoit Driver", namespace: "level99", author: "a") {
                capability "Switch"
            }
        }

        /**
         * This is a javadoc block in a DRIVER file — RULE29 does not apply to drivers.
         */
        def foo() { return 1 }
    """)

    # ---- tests ----

    def test_good_mit_only_passes(self):
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.GOOD_MIT_ONLY, "LevoitChildBaseLib"
        )
        assert not findings, "Library with only MIT header /* */ must pass RULE29"

    def test_good_body_line_comments_only_passes(self):
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.GOOD_BODY_LINE_COMMENTS_ONLY, "LevoitChildBaseLib"
        )
        assert not findings, "Library with only // comments in body must pass RULE29"

    def test_file_scope_second_block_fails(self):
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.BAD_FILE_SCOPE_SECOND_BLOCK, "LevoitChildBaseLib"
        )
        assert any(f['rule_id'] == 'RULE29_library_block_comment_at_top' for f in findings), \
            "Second /* */ block before library() must fail RULE29 (file-scope)"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE29_library_block_comment_at_top')

    def test_body_scope_javadoc_fails(self):
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.BAD_BODY_SCOPE_JAVADOC, "LevoitChildBaseLib"
        )
        assert any(f['rule_id'] == 'RULE29_library_block_comment_in_body' for f in findings), \
            "/** */ javadoc block after library() must fail RULE29 (body-scope)"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE29_library_block_comment_in_body')

    def test_body_scope_plain_block_fails(self):
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.BAD_BODY_SCOPE_PLAIN_BLOCK, "LevoitChildBaseLib"
        )
        assert any(f['rule_id'] == 'RULE29_library_block_comment_in_body' for f in findings), \
            "/* */ plain block after library() must fail RULE29 (body-scope)"
        assert any(f['severity'] == 'FAIL' for f in findings if f['rule_id'] == 'RULE29_library_block_comment_in_body')

    def test_driver_file_body_scope_javadoc_exempt(self):
        # Driver files (definition() block) are not subject to RULE29.
        findings = run_rule(
            check_rule29_library_no_top_block_comment,
            self.GOOD_DRIVER_BODY_SCOPE_JAVADOC, "LevoitVital200S"
        )
        assert not findings, "Driver file /* */ blocks must be exempt from RULE29"


# ---------------------------------------------------------------------------
# TestLintExitCodes
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


# ---------------------------------------------------------------------------
# Post-collection severity hard-check in lint.py
# ---------------------------------------------------------------------------

class TestLintSeverityHardCheck:
    """
    Regression guard: lint._assert_all_severities_valid() must raise
    RuntimeError when any finding carries a missing or invalid severity.

    All tests call lint._assert_all_severities_valid() directly — the real
    extracted function from lint.py.  If that function's body were deleted or
    emptied, test_bad_severity_raises_runtime_error would FAIL, proving these
    tests are non-vacuous (they test the real code, not a local re-implementation).
    """

    def _import_lint(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("lint_hctest", TESTS_DIR / "lint.py")
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_bad_severity_raises_runtime_error(self):
        """
        A finding with no severity key fed to _assert_all_severities_valid() MUST raise
        RuntimeError.  This is the shape a raw inline dict
        ``findings.append({"rule_id": "FAKE", "file": "x.groovy"})`` produces —
        get('severity') returns None, which was silently non-gating before the hard-check was added.
        """
        lint = self._import_lint()
        bad_finding = {"rule_id": "FAKE_RULE_NO_SEV", "file": "x.groovy", "line": 1}
        with pytest.raises(RuntimeError, match="invalid severity"):
            lint._assert_all_severities_valid([bad_finding])

    def test_bogus_string_severity_raises_runtime_error(self):
        """
        A finding with severity='ERROR' (wrong string) must also raise — confirms
        the check rejects all non-('FAIL','WARN') values, not just None.
        """
        lint = self._import_lint()
        bad_finding = {"rule_id": "FAKE_RULE_BAD_SEV", "severity": "ERROR",
                       "file": "x.groovy", "line": 1}
        with pytest.raises(RuntimeError, match="invalid severity"):
            lint._assert_all_severities_valid([bad_finding])

    def test_valid_fail_severity_does_not_raise(self):
        """A list containing only severity='FAIL' findings must NOT raise."""
        lint = self._import_lint()
        good = {"rule_id": "SOME_RULE", "severity": "FAIL", "file": "x.groovy", "line": 1}
        lint._assert_all_severities_valid([good])  # must not raise

    def test_valid_warn_severity_does_not_raise(self):
        """A list containing only severity='WARN' findings must NOT raise."""
        lint = self._import_lint()
        good = {"rule_id": "SOME_RULE", "severity": "WARN", "file": "x.groovy", "line": 1}
        lint._assert_all_severities_valid([good])  # must not raise

    def test_empty_list_does_not_raise(self):
        """Empty findings list (no findings at all) must NOT raise."""
        lint = self._import_lint()
        lint._assert_all_severities_valid([])  # must not raise

    def test_mixed_valid_and_bad_raises_on_bad(self):
        """
        A mixed list where the first finding is valid but the second has no severity
        must still raise — confirms the function iterates the whole list.
        """
        lint = self._import_lint()
        findings = [
            {"rule_id": "RULE_A", "severity": "FAIL", "file": "a.groovy", "line": 1},
            {"rule_id": "RULE_B", "file": "b.groovy", "line": 2},  # no severity key
        ]
        with pytest.raises(RuntimeError, match="invalid severity"):
            lint._assert_all_severities_valid(findings)


# ---------------------------------------------------------------------------
# Lead-Finding-1 (T11 QA): make_finding_for_file severity gate parity test
# ---------------------------------------------------------------------------

class TestMakeFindingForFileSeverityGate:
    """
    Lead-Finding-1: make_finding_for_file re-implements the severity gate inline
    (bypassing make_finding). This test verifies the inline gate raises ValueError
    on an invalid severity, keeping it in sync with make_finding's own gate.
    Nothing else tests make_finding_for_file's gate directly.
    """

    def test_invalid_severity_raises_value_error(self):
        """make_finding_for_file with severity='INFO' must raise ValueError."""
        from lint_rules._helpers import make_finding_for_file
        with pytest.raises(ValueError, match="invalid severity"):
            make_finding_for_file(
                severity='INFO',
                rule_id='TEST_GATE',
                title='test',
                file_str='Drivers/Levoit/SomeDriver.groovy',
                lineno=5,
                context='    def someMethod() {',
                why='why',
                fix='fix',
            )


# ---------------------------------------------------------------------------
# RULE25 — BP16 ensureDebugWatchdog call-site
# ---------------------------------------------------------------------------

class TestRule25DebugWatchdogCallSite:
    """
    RULE25 (Bug Pattern #16): every driver file under Drivers/Levoit/ must have at
    least one live call site for ensureDebugWatchdog().  Absence means debugOutput
    can stay permanently on after a hub reboot.

    Good path: driver contains ensureDebugWatchdog() invocation.
    Bad path:  driver has no such call — FAIL with severity == 'FAIL'.

    Both the definition line (``def ensureDebugWatchdog()``) and comment-only
    occurrences are excluded; only true invocations satisfy the check.
    """

    GOOD_HAS_CALL = textwrap.dedent("""\
        def update(status, nightLight) {
            ensureDebugWatchdog()
            applyStatus(status)
        }
        private void ensureDebugWatchdog() {
            if (!settings?.debugOutput) return
            if (state.debugEnabledAt == null) { state.debugEnabledAt = now(); return }
            if ((now() - state.debugEnabledAt) > 1800000) {
                device.updateSetting("debugOutput", [type: "bool", value: false])
                state.remove("debugEnabledAt")
            }
        }
    """)

    # A driver with ensureDebugWatchdog() only in its definition line and in a comment
    # — no live call site.
    BAD_NO_LIVE_CALL = textwrap.dedent("""\
        def update(status, nightLight) {
            // ensureDebugWatchdog() -- disabled for testing
            applyStatus(status)
        }
        private void ensureDebugWatchdog() {
            // placeholder
        }
    """)

    def test_good_passes(self):
        """Driver with a live ensureDebugWatchdog() call: no findings."""
        findings = run_rule(check_rule25_bp16_watchdog_call_site, self.GOOD_HAS_CALL)
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_bad_no_live_call_fails(self):
        """Driver missing any live ensureDebugWatchdog() call: FAIL with severity == 'FAIL'."""
        findings = run_rule(check_rule25_bp16_watchdog_call_site, self.BAD_NO_LIVE_CALL)
        rule25 = [f for f in findings if f['rule_id'] == 'RULE25_bp16_watchdog_call_site']
        assert rule25, "Expected RULE25_bp16_watchdog_call_site finding on driver with no live call"
        # W11 dead-gate assertion: finding MUST carry severity='FAIL' to gate lint --strict.
        assert any(f['severity'] == 'FAIL' for f in rule25), (
            f"RULE25 finding must carry severity='FAIL' to gate lint --strict; got: {rule25}"
        )

    def test_library_file_skipped(self):
        """Library files (using library() block) are skipped — they have no debug pref."""
        lib_src = textwrap.dedent("""\
            library(
                name: "SomeLib",
                namespace: "level99",
                author: "a"
            )
            def someMethod() { logDebug "hello" }
        """)
        findings = run_rule(
            check_rule25_bp16_watchdog_call_site, lib_src, "SomethingLib"
        )
        assert findings == [], "Library files must be excluded from RULE25"


# ---------------------------------------------------------------------------
# RULE31 — BP24-A state.switch dead branch
# ---------------------------------------------------------------------------

class TestRule31StateSwitchDeadBranch:
    """
    RULE31 (Bug Pattern #24-A): reading ``state.switch`` in a conditional is a
    permanently-dead guard because ``state.switch`` is never written anywhere in the
    codebase.  Power state is tracked only via ``device.currentValue('switch')``.

    Good path:  driver reads ``device.currentValue('switch')`` — correct pattern.
    Bad path:   driver reads ``state.switch`` in a comparison — FAIL.
    """

    GOOD_CORRECT_GUARD = textwrap.dedent("""\
        def cycleSpeed() {
            ensureSwitchOn()
            state.speed = nextSpeed()
            setSpeed(state.speed)
        }
    """)

    GOOD_CURRENTVALUE_GUARD = textwrap.dedent("""\
        def cycleSpeed() {
            if (!state.turningOn && device.currentValue("switch") != "on") on()
            state.speed = nextSpeed()
            setSpeed(state.speed)
        }
    """)

    # The classic BP24-A dead-branch pattern found in Core line cycleSpeed() prior to v2.5.
    BAD_STATE_SWITCH_READ = textwrap.dedent("""\
        def cycleSpeed() {
            state.speed = nextSpeed()
            if (state.switch == "off") { on() }
            setSpeed(state.speed)
        }
    """)

    BAD_STATE_SWITCH_NEQ = textwrap.dedent("""\
        def setSpeed(speed) {
            if (state.switch != "on") { on() }
            handleSpeed(speed)
        }
    """)

    def test_good_correct_guard_passes(self):
        """Using ensureSwitchOn() — no state.switch reads: no findings."""
        findings = run_rule(check_rule31_state_switch_dead_branch, self.GOOD_CORRECT_GUARD)
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_good_currentvalue_guard_passes(self):
        """device.currentValue('switch') read is correct — no state.switch: no findings."""
        findings = run_rule(check_rule31_state_switch_dead_branch, self.GOOD_CURRENTVALUE_GUARD)
        assert findings == [], f"Expected no findings, got: {findings}"

    def test_bad_state_switch_eq_fails(self):
        """``if (state.switch == 'off')`` is a dead branch — FAIL with severity == 'FAIL'."""
        findings = run_rule(check_rule31_state_switch_dead_branch, self.BAD_STATE_SWITCH_READ)
        rule31 = [f for f in findings if f['rule_id'] == 'RULE31_state_switch_dead_branch']
        assert rule31, "Expected RULE31_state_switch_dead_branch finding"
        # W11 dead-gate assertion: finding MUST carry severity='FAIL' to gate lint --strict.
        assert any(f['severity'] == 'FAIL' for f in rule31), (
            f"RULE31 finding must carry severity='FAIL' to gate lint --strict; got: {rule31}"
        )

    def test_bad_state_switch_neq_fails(self):
        """``if (state.switch != 'on')`` is also a dead branch — FAIL."""
        findings = run_rule(check_rule31_state_switch_dead_branch, self.BAD_STATE_SWITCH_NEQ)
        rule31 = [f for f in findings if f['rule_id'] == 'RULE31_state_switch_dead_branch']
        assert rule31, "Expected RULE31_state_switch_dead_branch finding on != form"
        assert any(f['severity'] == 'FAIL' for f in rule31), (
            f"RULE31 finding must carry severity='FAIL'; got: {rule31}"
        )


# ---------------------------------------------------------------------------
# RULE32 — BP24-B/C auto-on guard missing on SHOULD-ON commands
# ---------------------------------------------------------------------------

class TestRule32AutoOnGuardMissing:
    """
    RULE32 (Bug Pattern #24-B/C): SHOULD-ON configure-style commands (cycleSpeed,
    setFanSpeed, setSpeed, setMistLevel, setWarmMistLevel, setMode) that make a
    direct API call must have a preceding auto-on guard.

    Good path: method calls ensureSwitchOn() (canonical) or has inline
    device.currentValue("switch") + state.turningOn guard.
    Bad path:  API call without any guard — FAIL with severity == 'FAIL'.
    """

    GOOD_ENSURE_SWITCH_ON = textwrap.dedent("""\
        def cycleSpeed() {
            ensureSwitchOn()
            state.speed = nextSpeed()
            parent.sendBypassRequest(device, [
                data: [level: mapSpeedToInteger(state.speed), id: 0, type: "wind"],
                "method": "setLevel", "source": "APP"
            ]) { resp -> }
        }
    """)

    GOOD_INLINE_GUARD = textwrap.dedent("""\
        def setSpeed(speed) {
            if (!state.turningOn && device.currentValue("switch") != "on") on()
            parent.sendBypassRequest(device, [
                data: [level: mapSpeedToInteger(speed), id: 0, type: "wind"],
                "method": "setLevel", "source": "APP"
            ]) { resp -> }
        }
    """)

    # BP24-B: API call present, no guard at all.
    BAD_NO_GUARD = textwrap.dedent("""\
        def cycleSpeed() {
            state.speed = nextSpeed()
            parent.sendBypassRequest(device, [
                data: [level: mapSpeedToInteger(state.speed), id: 0, type: "wind"],
                "method": "setLevel", "source": "APP"
            ]) { resp -> }
        }
    """)

    def test_good_ensure_switch_on_passes(self):
        """ensureSwitchOn() present — RULE32 must not fire."""
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_ENSURE_SWITCH_ON)
        rule32 = [f for f in findings if f['rule_id'] == 'RULE32_auto_on_guard_missing']
        assert rule32 == [], f"Expected no RULE32 with ensureSwitchOn(), got: {rule32}"

    def test_good_inline_guard_passes(self):
        """Inline device.currentValue + state.turningOn guard present — RULE32 must not fire."""
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_INLINE_GUARD)
        rule32 = [f for f in findings if f['rule_id'] == 'RULE32_auto_on_guard_missing']
        assert rule32 == [], f"Expected no RULE32 with inline guard, got: {rule32}"

    def test_bad_no_guard_fails(self):
        """API call in SHOULD-ON method with no guard — FAIL with severity == 'FAIL'."""
        findings = run_rule(check_rule32_auto_on_guard_missing, self.BAD_NO_GUARD)
        rule32 = [f for f in findings if f['rule_id'] == 'RULE32_auto_on_guard_missing']
        assert rule32, (
            "Expected RULE32_auto_on_guard_missing finding on cycleSpeed() with no guard"
        )
        # W11 dead-gate assertion: finding MUST carry severity='FAIL' to gate lint --strict.
        assert any(f['severity'] == 'FAIL' for f in rule32), (
            f"RULE32 finding must carry severity='FAIL' to gate lint --strict; got: {rule32}"
        )


# ---------------------------------------------------------------------------
# End-to-end call-site regression guard for _assert_all_severities_valid
# ---------------------------------------------------------------------------

class TestLintMainInvalidSeverityAborts:
    """
    Proves that the CALL to _assert_all_severities_valid at lint.py:~505
    (not just its function body) is what terminates a run carrying a bad severity.

    The existing TestLintSeverityHardCheck tests call the function directly —
    they verify the function body is correct but cannot detect if the call line
    were deleted while the body remained.

    This guard addresses a vacuity that was empirically discovered: an earlier
    version used a `code != 0` discriminator that was satisfied by incidental
    LINT_UNUSED_EXEMPTION WARN findings in strict mode even when the choke-point
    call was deleted.  The fix:
    (a) Uses an empty exemptions config so no LINT_UNUSED_EXEMPTION noise fires.
    (b) Asserts RuntimeError specifically — any SystemExit (code 0 or non-zero)
        is a test FAILURE, meaning the choke-point did NOT fire.

    Deletion proof: with `pass` replacing the call at lint.py:~505, lint.main()
    runs to completion (no FAILs or WARNs in the clean temp tree, empty config),
    emits sys.exit(0), which is a SystemExit — pytest.raises(RuntimeError) FAILs
    as required, proving this guard is non-vacuous.
    """

    def test_bad_severity_in_rule_aborts_lint_main(self, monkeypatch, tmp_path):
        """
        Proves that the CALL to _assert_all_severities_valid at lint.py:~505
        (not just its function body) terminates a run carrying a bad severity
        — by asserting a RuntimeError with the specific 'invalid severity'
        message, NOT a SystemExit of any code.

        Vacuity fix: the prior discriminator (`code != 0`) was satisfied by
        incidental LINT_UNUSED_EXEMPTION WARN findings in strict mode even when the
        choke-point call was deleted, making the guard empirically false-green.

        This version:
        (a) Points lint at a private temp config with ZERO exemptions so no
            LINT_UNUSED_EXEMPTION WARNs can fire — eliminating all incidental
            exit-1 paths.
        (b) Asserts specifically RuntimeError with 'invalid severity' in the message.
            Any SystemExit (regardless of code) is treated as a TEST FAILURE,
            meaning the choke-point did NOT fire.

        Deletion proof: with the _assert_all_severities_valid(all_findings) call
        at lint.py:~505 replaced by `pass`, lint.main() runs to completion with an
        INFO finding, exits via sys.exit(0) (no FAILs, no WARNs in temp tree, no
        strict WARNs because config is empty), which is a SystemExit(0) — the
        `with pytest.raises(RuntimeError)` block then FAILS as expected, proving
        the guard is non-vacuous.
        """
        import importlib.util

        # --- Build a minimal temp driver tree with an empty exemption config ---
        drivers_dir = tmp_path / "Drivers" / "Levoit"
        drivers_dir.mkdir(parents=True)
        (drivers_dir / "Dummy.groovy").write_text(
            'metadata { definition(name: "Dummy") {} }', encoding='utf-8'
        )
        # Empty exemptions config — no LINT_UNUSED_EXEMPTION noise can fire.
        empty_cfg = tmp_path / "empty_lint_config.yaml"
        empty_cfg.write_text("exemptions: []\n", encoding='utf-8')

        # Load lint as a fresh module instance so monkeypatching is isolated.
        spec = importlib.util.spec_from_file_location("lint_t12", TESTS_DIR / "lint.py")
        lint_mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(lint_mod)

        # Inject a rule that emits a raw dict with severity='INFO' — bypassing
        # make_finding's ValueError gate to simulate a future rule that
        # hand-constructs findings without the helper's validation.
        def _bad_rule(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
            return [{'severity': 'INFO', 'rule_id': 'T12_BAD_SEV', 'title': 'test',
                      'file': str(path), 'line': 1, 'context': '', 'why': '', 'fix': ''}]

        monkeypatch.setattr(lint_mod, '_collect_per_file_rules', lambda: [_bad_rule])
        monkeypatch.setattr(lint_mod, '_collect_repo_level_rules', lambda: [])

        # Point sys.argv at our temp tree with the empty exemptions config.
        # --strict is intentionally OMITTED: with an empty config there are no
        # WARN findings, so strict vs non-strict makes no difference — the only
        # possible non-zero exit is the RuntimeError from the choke-point.
        monkeypatch.setattr(sys, 'argv', [
            'lint.py',
            '--paths', str(tmp_path),
            '--config', str(empty_cfg),
        ])

        # MUST raise RuntimeError with 'invalid severity' in the message.
        # A SystemExit of ANY code (0 or non-zero) means the choke-point did
        # NOT fire — that is the exact failure this test guards against.
        with pytest.raises(RuntimeError) as exc_info:
            lint_mod.main()

        assert 'invalid severity' in str(exc_info.value).lower() or \
               'lint internal error' in str(exc_info.value).lower(), (
            f"RuntimeError was raised but message did not contain expected choke-point "
            f"text. Got: {exc_info.value!r}. The choke-point message changed — "
            f"update this assertion to match the new wording."
        )


# ---------------------------------------------------------------------------
# Severity-asserting tests for previously untested FAIL rules
# ---------------------------------------------------------------------------

class TestRule23DriverAppOnlyApi:
    """
    RULE23 (Bug Pattern #15): subscribe() and unsubscribe() in driver code.
    Good path: neither call present.
    Bad path: subscribe() present in driver code — FAIL.
    """
    from lint_rules.driver_app_only_api import check_rule23_driver_app_only_api

    GOOD = textwrap.dedent("""\
        def setupPoll() {
            schedule("0/30 * * * * ?", "updateDevices")
        }
    """)

    BAD = textwrap.dedent("""\
        def initialize() {
            subscribe(location, "systemStart", restartHandler)
        }
    """)

    def test_good_passes(self):
        from lint_rules.driver_app_only_api import check_rule23_driver_app_only_api
        findings = run_rule(check_rule23_driver_app_only_api, self.GOOD)
        assert findings == [], f"Expected no findings on clean source, got: {findings}"

    def test_bad_subscribe_fails(self):
        from lint_rules.driver_app_only_api import check_rule23_driver_app_only_api
        findings = run_rule(check_rule23_driver_app_only_api, self.BAD)
        rule23 = [f for f in findings if f['rule_id'] == 'RULE23_driver_app_only_api']
        assert rule23, "Expected RULE23 finding on driver source with subscribe()"
        assert any(f['severity'] == 'FAIL' for f in rule23), (
            f"RULE23 finding must carry severity='FAIL'; got: {rule23}"
        )


class TestRule24AgentPointerIntegrity:
    """
    RULE24: broken section pointer in .claude/agents/*.md.
    Good path: pointer to a real section.
    Bad path: pointer to a non-existent section — FAIL.
    """

    def _run_rule24(self, src, fname):
        """Run RULE24 on source with a path under .claude/agents/ so the rule triggers."""
        from lint_rules.agent_pointer_integrity import check_rule24_agent_pointer_integrity
        from lint_rules.groovy_lite import clean_source
        path = REPO_ROOT / ".claude" / "agents" / fname
        raw_lines = src.splitlines()
        _, cleaned_lines = clean_source(src)
        return check_rule24_agent_pointer_integrity(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    GOOD = textwrap.dedent("""\
        See `CONTRIBUTING.md` "Codebase orientation"
    """)

    # Two blank lines before the pointer put it on line 3:
    #   line 1: (blank)
    #   line 2: (blank)
    #   line 3: See `CONTRIBUTING.md` "..."   ← lineno = 3 (from enumerate(raw_lines, 1))
    # Regression guard for the make_finding_for_file context-preservation fix:
    # old make_finding_for_file with raw_lines=[ctx] and lineno=3:
    #   range(max(0,3-2), min(1,3+1)) = range(1, 1) → empty → context blanked.
    # Fixed: make_finding_for_file now stores context directly → non-empty.
    # Verified arithmetic: range(1, min(1, 4)) = range(1, 1) = [] (empty). ✓
    BAD = textwrap.dedent("""\


        See `CONTRIBUTING.md` "Nonexistent Section That Does Not Exist XYZ987"
    """)

    def test_good_passes(self):
        # Only passes if CONTRIBUTING.md has the section. Use the real file.
        # If CONTRIBUTING.md doesn't exist we get WARN (not FAIL) — still passes test.
        findings = self._run_rule24(self.GOOD, "test_agent.md")
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], f"Expected no FAIL findings on clean pointer, got: {fail_findings}"

    def test_bad_broken_pointer_fails(self):
        findings = self._run_rule24(self.BAD, "test_agent.md")
        rule24 = [f for f in findings if f['rule_id'] == 'RULE24_broken_section_pointer']
        assert rule24, (
            "Expected RULE24_broken_section_pointer finding on non-existent section pointer"
        )
        assert any(f['severity'] == 'FAIL' for f in rule24), (
            f"RULE24_broken_section_pointer must carry severity='FAIL'; got: {rule24}"
        )
        # Regression guard for the make_finding_for_file context-preservation fix:
        # pointer is on line 3 of BAD fixture; old make_finding_for_file:
        # range(1, min(1,4))=range(1,1)=[] → context blanked.
        # This assert FAILs if the context-preservation fix is reverted.
        assert any(f['context'] for f in rule24), (
            "Regression: context blanked for RULE24_broken_section_pointer at lineno=3 "
            "(range(1,min(1,4))=[] in old make_finding_for_file → should be non-empty post-fix)"
        )


class TestRule18ManifestConsistency:
    """
    RULE18: levoitManifest.json must exist and cover every .groovy driver.
    Good path: manifest present and consistent.
    Bad path: manifest missing — FAIL.
    """

    def test_good_manifest_passes(self):
        """Real repo root has a valid manifest — rule must produce no FAIL findings."""
        from lint_rules.manifest_consistency import check_rule18_manifest_consistency
        findings = check_rule18_manifest_consistency(repo_root=REPO_ROOT, config={})
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], (
            f"Expected no FAIL on real manifest; got: {fail_findings}"
        )

    def test_missing_manifest_fails(self):
        """No levoitManifest.json in temp dir — FAIL with severity='FAIL'."""
        from lint_rules.manifest_consistency import check_rule18_manifest_consistency
        with tempfile.TemporaryDirectory() as td:
            fake_root = Path(td)
            (fake_root / "Drivers" / "Levoit").mkdir(parents=True)
            findings = check_rule18_manifest_consistency(repo_root=fake_root, config={})
        rule18 = [f for f in findings if f['rule_id'] == 'RULE18_manifest_missing']
        assert rule18, "Expected RULE18_manifest_missing finding when manifest absent"
        assert any(f['severity'] == 'FAIL' for f in rule18), (
            f"RULE18 finding must carry severity='FAIL'; got: {rule18}"
        )


class TestRule19DocSync:
    """
    RULE19: readme.md must document every driver.
    Good path: readme exists and mentions the driver.
    Bad path: readme missing — FAIL.
    """

    def test_good_readme_passes(self):
        """Real repo root has readme.md — rule must produce no FAIL findings."""
        from lint_rules.doc_sync import check_rule19_doc_sync
        findings = check_rule19_doc_sync(repo_root=REPO_ROOT, config={})
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], (
            f"Expected no FAIL on real readme; got: {fail_findings}"
        )

    def test_missing_readme_fails(self):
        """No readme.md present — FAIL with severity='FAIL'."""
        from lint_rules.doc_sync import check_rule19_doc_sync
        with tempfile.TemporaryDirectory() as td:
            fake_root = Path(td)
            drivers_dir = fake_root / "Drivers" / "Levoit"
            drivers_dir.mkdir(parents=True)
            # Add a driver file that would require a readme entry
            (drivers_dir / "LevoitVital200S.groovy").write_text(
                'metadata { definition(name: "Levoit Vital 200S Air Purifier") {} }',
                encoding='utf-8',
            )
            findings = check_rule19_doc_sync(repo_root=fake_root, config={})
        rule19 = [f for f in findings if f['severity'] == 'FAIL']
        assert rule19, "Expected FAIL finding when readme.md is absent"
        assert any(f['rule_id'].startswith('RULE19') for f in rule19), (
            f"Expected a RULE19 FAIL finding; got: {rule19}"
        )


class TestRule20VersionLockstep:
    """
    RULE20: every driver's version must match levoitManifest.json.
    Good path: real repo passes (all drivers at matching version).
    Bad path: driver version differs from manifest — FAIL.
    """

    def test_good_version_passes(self):
        """Real repo root — rule must produce no FAIL findings."""
        from lint_rules.version_lockstep import check_rule20_version_lockstep
        findings = check_rule20_version_lockstep(repo_root=REPO_ROOT, config={})
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], (
            f"Expected no FAIL on real codebase; got: {fail_findings}"
        )

    def test_version_mismatch_fails(self):
        """Driver version != manifest version — FAIL with severity='FAIL'."""
        from lint_rules.version_lockstep import check_rule20_version_lockstep
        with tempfile.TemporaryDirectory() as td:
            fake_root = Path(td)
            drivers_dir = fake_root / "Drivers" / "Levoit"
            drivers_dir.mkdir(parents=True)
            # Manifest at version "9.9.9"
            import json as _json
            manifest = {
                "packageName": "Test",
                "version": "9.9.9",
                "drivers": [
                    {
                        "id": "aaaaaaaa-0000-0000-0000-000000000001",
                        "name": "Test Driver",
                        "namespace": "level99",
                        "location": "https://example.com/TestDriver.groovy",
                        "required": True,
                    }
                ],
            }
            (fake_root / "levoitManifest.json").write_text(
                _json.dumps(manifest), encoding='utf-8'
            )
            # Driver at version "1.0.0" — intentional mismatch.
            # Two leading comment lines pad the file so `definition(` lands on line 4:
            #   line 1: // padding comment A
            #   line 2: // padding comment B
            #   line 3: metadata {
            #   line 4: definition(   ← def_lineno = 4
            # Regression guard for the make_finding_for_file context-preservation fix:
            # old make_finding_for_file with raw_lines=[ctx] and lineno=4:
            #   range(max(0,4-2), min(1,4+1)) = range(2, 1) → empty → context blanked.
            # Fixed: make_finding_for_file now stores context directly → non-empty.
            # Verified arithmetic: range(2, min(1, 5)) = range(2, 1) = [] (empty). ✓
            (drivers_dir / "TestDriver.groovy").write_text(
                textwrap.dedent("""\
                    // padding comment A
                    // padding comment B
                    metadata {
                        definition(
                            name: "Test Driver",
                            namespace: "level99",
                            version: "1.0.0") {
                        }
                    }
                """),
                encoding='utf-8',
            )
            findings = check_rule20_version_lockstep(repo_root=fake_root, config={})
        rule20 = [f for f in findings if f['rule_id'] == 'RULE20_version_drift']
        assert rule20, "Expected RULE20_version_drift finding for version mismatch"
        assert any(f['severity'] == 'FAIL' for f in rule20), (
            f"RULE20 finding must carry severity='FAIL'; got: {rule20}"
        )
        # Regression guard for the make_finding_for_file context-preservation fix:
        # fixture yields def_lineno=4 (definition( at line 4 of the padded source).
        # Old make_finding_for_file: range(2, min(1,5))=range(2,1)=[] → context blanked.
        # This assert FAILs if the context-preservation fix is reverted.
        assert any(f['context'] for f in rule20), (
            "Regression: context blanked for RULE20_version_drift at lineno=4 "
            "(range(2,min(1,5))=[] in old make_finding_for_file → should be non-empty post-fix)"
        )


class TestRule21ReadmeDevicesSync:
    """
    RULE21: every driver must appear in README.md's Supported devices section.
    Good path: real repo passes.
    Bad path: driver not in README — FAIL.
    """

    def test_good_readme_passes(self):
        """Real repo root — rule must produce no FAIL findings."""
        from lint_rules.readme_devices_sync import check_rule21_readme_devices_sync
        findings = check_rule21_readme_devices_sync(repo_root=REPO_ROOT, config={})
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], (
            f"Expected no FAIL on real codebase; got: {fail_findings}"
        )

    def test_driver_missing_from_readme_fails(self):
        """Driver present in Drivers/ but not in README — FAIL with severity='FAIL'."""
        from lint_rules.readme_devices_sync import check_rule21_readme_devices_sync
        with tempfile.TemporaryDirectory() as td:
            fake_root = Path(td)
            drivers_dir = fake_root / "Drivers" / "Levoit"
            drivers_dir.mkdir(parents=True)
            # Driver with a distinctive name not in the README.
            # Two leading comment lines pad the file so `definition(` lands on line 4:
            #   line 1: // padding comment A
            #   line 2: // padding comment B
            #   line 3: metadata {
            #   line 4: definition(   ← def_lineno = 4
            # Regression guard for the make_finding_for_file context-preservation fix:
            # old make_finding_for_file with raw_lines=[ctx] and lineno=4:
            #   range(max(0,4-2), min(1,4+1)) = range(2, 1) → empty → context blanked.
            # Fixed: make_finding_for_file now stores context directly → non-empty.
            (drivers_dir / "LevoitTestXXX999.groovy").write_text(
                textwrap.dedent("""\
                    // padding comment A
                    // padding comment B
                    metadata {
                        definition(
                            name: "Levoit Test XXX999 Purifier",
                            namespace: "NiklasGustafsson",
                            version: "1.0") {
                        }
                    }
                """),
                encoding='utf-8',
            )
            # README that does NOT mention "Levoit Test XXX999 Purifier"
            (fake_root / "README.md").write_text(
                textwrap.dedent("""\
                    ## Supported devices

                    | Device | Notes |
                    | --- | --- |
                    | **Levoit Core 200S** | Supported |
                """),
                encoding='utf-8',
            )
            findings = check_rule21_readme_devices_sync(repo_root=fake_root, config={})
        rule21 = [f for f in findings if f['severity'] == 'FAIL']
        assert rule21, "Expected FAIL finding when driver not in README"
        assert any(f['rule_id'].startswith('RULE21') for f in rule21), (
            f"Expected a RULE21 FAIL finding; got: {rule21}"
        )
        # Regression guard for the make_finding_for_file context-preservation fix:
        # fixture yields def_lineno=4; old make_finding_for_file:
        # range(2, min(1,5))=range(2,1)=[] → context blanked.
        # This assert FAILs if the context-preservation fix is reverted.
        assert any(f['context'] for f in rule21), (
            "Regression: context blanked for RULE21 finding at lineno=4 "
            "(range(2,min(1,5))=[] in old make_finding_for_file → should be non-empty post-fix)"
        )


class TestRule22WhitelistParity:
    """
    RULE22: isLevoitClimateDevice() must cover every prefix from deviceType().
    Good path: real VeSyncIntegration.groovy passes.
    Bad path: prefix in deviceType() but missing from isLevoitClimateDevice() — FAIL.
    """

    def test_good_parity_passes(self):
        """Real repo root — rule must produce no FAIL findings."""
        from lint_rules.whitelist_parity import check_rule22_whitelist_parity
        findings = check_rule22_whitelist_parity(repo_root=REPO_ROOT, config={})
        fail_findings = [f for f in findings if f['severity'] == 'FAIL']
        assert fail_findings == [], (
            f"Expected no FAIL on real codebase; got: {fail_findings}"
        )

    def test_missing_whitelist_prefix_fails(self):
        """Prefix in deviceType() absent from isLevoitClimateDevice() — FAIL."""
        from lint_rules.whitelist_parity import check_rule22_whitelist_parity
        with tempfile.TemporaryDirectory() as td:
            fake_root = Path(td)
            drivers_dir = fake_root / "Drivers" / "Levoit"
            drivers_dir.mkdir(parents=True)
            # Parent driver: deviceType has "ZZZZ-" prefix, whitelist doesn't
            parent_src = textwrap.dedent("""\
                metadata {
                    definition(name: "VeSync Integration", namespace: "NiklasGustafsson", version: "1.0") {}
                }

                private String deviceType(String code) {
                    switch (code) {
                        case { it.startsWith("ZZZZ-") }: return "TestDevice"
                        default: return "GENERIC"
                    }
                }

                private boolean isLevoitClimateDevice(String code) {
                    return (code.startsWith("AAAA-"))
                }
            """)
            (drivers_dir / "VeSyncIntegration.groovy").write_text(parent_src, encoding='utf-8')
            findings = check_rule22_whitelist_parity(repo_root=fake_root, config={})
        rule22 = [f for f in findings if f['severity'] == 'FAIL']
        assert rule22, "Expected FAIL finding when prefix in deviceType() missing from whitelist"
        assert any(f['rule_id'].startswith('RULE22') for f in rule22), (
            f"Expected a RULE22 FAIL finding; got: {rule22}"
        )
        # NOTE: no context-preservation regression guard here. RULE22 prefix/literal findings
        # are emitted with hardcoded lineno=0 (see whitelist_parity.py). At lineno=0 the old
        # make_finding_for_file produced range(0, min(1,1))=range(0,1)=[0] → raw_lines[0]
        # = the hardcoded context string → context was always preserved, even before the fix.
        # The context-preservation fix only changed behaviour for lineno>2; lineno=0 was
        # never the broken path.
