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
    # must-not-catch: inline while-loop shape (status quo on most v2.8 drivers).
    GOOD_INLINE = textwrap.dedent("""\
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

    # must-not-catch: helper-call shape (Task #140 Phase 2+ migration).
    # Equivalent contract via peelEnvelope() from LevoitChildBaseLib.
    GOOD_HELPER = textwrap.dedent("""\
        def applyStatus(status) {
            def r = peelEnvelope(status)
            if (settings?.debugOutput) log.debug "applyStatus raw r keys=${r?.keySet()}, values=${r}"
        }
    """)

    # must-not-catch: lib-inheritance shape (Task #142 Phase 2b+ centerpiece migration).
    # The driver has no applyStatus body locally — it inherits one from the lib.
    # The lib's body must contain a complete applyStatus method with either the
    # inline peel or the peelEnvelope() call inside.
    #
    # No production driver currently inherits applyStatus via lib (V2-line drivers
    # all define their own; Core line uses update() not applyStatus). The rule
    # supports this for future lib-extraction phases. The fixture below stages
    # a synthetic lib on disk to exercise the resolution path.
    GOOD_LIB_INHERITANCE = textwrap.dedent("""\
        #include level99.LevoitSyntheticTestLib

        // Driver inherits applyStatus + envelope-peel from the synthetic lib
        // staged at Drivers/Levoit/LevoitSyntheticTestLibLib.groovy.
    """)

    # Synthetic lib content that the lib-inheritance test stages on disk.
    SYNTHETIC_LIB_NAME = "LevoitSyntheticTestLib"
    SYNTHETIC_LIB_CONTENT = textwrap.dedent("""\
        // Synthetic lib file used by test_good_lib_inheritance_passes.
        // Provides applyStatus with peelEnvelope() helper call so the BP3 rule
        // finds the contract satisfied via #include lookup.
        def applyStatus(status) {
            def r = peelEnvelope(status)
            if (settings?.debugOutput) log.debug "applyStatus raw r keys=${r?.keySet()}"
        }
    """)

    # must-catch: neither inline loop NOR helper call — bug class still flagged.
    BAD = textwrap.dedent("""\
        def applyStatus(status) {
            def r = status?.result ?: [:]
            // no peel loop, no helper call
            def mode = r.workMode
        }
    """)

    # Backward-compat alias so any out-of-class reference to GOOD keeps working.
    GOOD = GOOD_INLINE

    def test_good_inline_passes(self):
        # Direction B (inline shape): rule accepts inline while-loop pattern.
        findings = run_rule(check_bp3_envelope_peel, self.GOOD_INLINE, "LevoitVital200S")
        assert findings == []

    def test_good_helper_passes(self):
        # Direction B (helper shape): rule accepts peelEnvelope() helper call.
        findings = run_rule(check_bp3_envelope_peel, self.GOOD_HELPER, "LevoitVital200S")
        assert findings == []

    def test_good_lib_inheritance_passes(self, tmp_path):
        # Direction B (lib-inheritance shape, Task #142 Phase 2b-pre): rule accepts
        # a driver that inherits applyStatus + envelope peel from an #include'd lib.
        # Resolves via tests/lint_rules/_helpers.py:included_lib_texts which reads
        # the lib file sibling-of-driver from disk. We stage a synthetic lib in
        # Drivers/Levoit/ for the duration of this test (real path required; the
        # _helpers.py:_lib_text_cache caches by Path, and the test uses a unique
        # synthetic lib name to avoid collisions with real libs).
        lib_path = REPO_ROOT / "Drivers" / "Levoit" / f"{self.SYNTHETIC_LIB_NAME}Lib.groovy"
        lib_path.write_text(self.SYNTHETIC_LIB_CONTENT, encoding='utf-8')
        try:
            findings = run_rule(check_bp3_envelope_peel, self.GOOD_LIB_INHERITANCE, "LevoitVital200S")
            assert findings == []
        finally:
            try:
                lib_path.unlink()
            except FileNotFoundError:
                pass
            # Evict the per-path cache entry so subsequent tests don't see stale data.
            from lint_rules._helpers import _lib_text_cache
            _lib_text_cache.pop(lib_path, None)

    def test_bad_fails(self):
        # Direction A: rule still catches the bug class when neither shape present.
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
    # must-not-catch: inline state.prefsSeeded gate (status quo on most v2.8 drivers).
    GOOD_INLINE = textwrap.dedent("""\
        def applyStatus(status) {
            if (!state.prefsSeeded) {
                if (settings?.descriptionTextEnable == null) {
                    device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
                }
                state.prefsSeeded = true
            }
        }
    """)

    # must-not-catch: helper-call shape (Task #140 Phase 2+ migration).
    # Equivalent contract via seedPrefs() from LevoitChildBaseLib.
    GOOD_HELPER = textwrap.dedent("""\
        def applyStatus(status) {
            seedPrefs()
            def r = peelEnvelope(status)
        }
    """)

    # must-not-catch: lib-inheritance shape (Task #142 Phase 2b+ centerpiece migration).
    # The driver has no pref-seed pattern locally — it inherits a method (e.g.
    # update(status, nightLight)) from an #include'd lib whose body has the pattern.
    # Resolves to the real LevoitChildBaseLib.groovy on disk via _helpers.py:
    # included_lib_texts (the lib contains both the inline-gate and helper-call shapes).
    #
    # Fixture is intentionally bare — no comment or code containing the inline
    # token "state.prefsSeeded" or helper-call token "seedPrefs(" — otherwise the
    # rule's regex would match the fixture text itself and the test would pass for
    # the wrong reason (defeating the non-vacuity proof that the lib lookup matters).
    GOOD_LIB_INHERITANCE = textwrap.dedent("""\
        #include level99.LevoitChildBase

        // Driver inherits pref-seed via a method defined in the lib body.
    """)

    # must-catch: neither inline gate NOR helper call — bug class still flagged.
    BAD = textwrap.dedent("""\
        def applyStatus(status) {
            def r = status?.result ?: [:]
        }
    """)

    # Backward-compat alias so any out-of-class reference to GOOD keeps working.
    GOOD = GOOD_INLINE

    def test_good_inline_passes(self):
        # Direction B (inline shape): rule accepts state.prefsSeeded gate.
        findings = run_rule(check_bp12_pref_seed, self.GOOD_INLINE, "LevoitVital200S")
        assert findings == []

    def test_good_helper_passes(self):
        # Direction B (helper shape): rule accepts seedPrefs() helper call.
        findings = run_rule(check_bp12_pref_seed, self.GOOD_HELPER, "LevoitVital200S")
        assert findings == []

    def test_good_lib_inheritance_passes(self):
        # Direction B (lib-inheritance shape, Task #142 Phase 2b-pre): rule accepts
        # a driver that inherits the pref-seed pattern from an #include'd lib.
        # Resolves via tests/lint_rules/_helpers.py:included_lib_texts which reads
        # the real LevoitChildBaseLib.groovy from disk.
        findings = run_rule(check_bp12_pref_seed, self.GOOD_LIB_INHERITANCE, "LevoitVital200S")
        assert findings == []

    def test_bad_fails(self):
        # Direction A: rule still catches the bug class when neither shape present.
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
    twice during the v2.1–v2.2 cycle before the rule was added.

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

    11 tests: 6 PASS, 4 FAIL, 1 finding-quality.
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

    PASS_REQUIRE_NON_EMPTY_ENUM = textwrap.dedent("""\
        def setMode(mode) {
            logDebug "setMode(${mode})"
            if (!requireNonEmptyEnum(mode, "setMode")) return
            String m = (mode as String).toLowerCase()
            if (!(m in ["auto","manual"])) { logError "bad mode: ${m}"; return }
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

    def test_pass_require_non_empty_enum_guard(self):
        """requireNonEmptyEnum before normalization: no RULE27 finding.

        Non-vacuity: reverting the require(?:NotNull|NonEmptyEnum) regex extension in
        bp18_null_guard.py to accept only requireNotNull would cause this test to FAIL
        with a false-positive RULE27 finding on the requireNonEmptyEnum-guarded method
        (the guard would not be recognized as a valid null-guard, so RULE27 would fire).
        """
        findings = run_rule(check_rule27_bp18_null_guard, self.PASS_REQUIRE_NON_EMPTY_ENUM)
        rule27 = [f for f in findings if f['rule_id'] == 'RULE27_bp18_null_guard']
        assert rule27 == [], (
            f"Expected no RULE27 findings for requireNonEmptyEnum guard, got: {rule27}"
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
# RULE33b — BP25 raw-normalized sendEvent (set* and doSet* methods)
# ---------------------------------------------------------------------------

class TestRule33bRawNormalizedSendEvent:
    """
    RULE33b (Bug Pattern #25 sub-check): a set* or doSet* method that normalizes
    with toLowerCase() but emits the raw normalized variable in sendEvent instead
    of a truthy-canon-derived identifier must be flagged.

    The re-blessed canonical pattern:
        String v    = (onOff as String).trim().toLowerCase()
        String canon = (v in ["on","true","1","yes"]) ? "on" : "off"
        device.sendEvent(name:"attr", value: canon)

    Emitting `v` directly (not `canon`) is non-canonical: truthy inputs
    "true"/"1"/"yes" would be stored verbatim in the attribute instead of "on".
    """

    from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent as _rule

    # (i) bad-path: set* method normalizes then emits raw normalized var → RULE33b
    BAD_SET_EMITS_RAW = textwrap.dedent("""\
        def setChildLock(onOff) {
            if (!requireNotNull(onOff, "setChildLock")) return
            String v = (onOff as String).trim().toLowerCase()
            if (device.currentValue("childLock") == v) return true
            Integer sw = (v == "on") ? 1 : 0
            hubBypass("setChildLock", [childLockSwitch: sw], "setChildLock(${v})")
            device.sendEvent(name:"childLock", value: v)
        }
    """)

    # (ii) good-path: set* method uses truthy-canon ternary → 0 findings
    GOOD_SET_EMITS_CANON = textwrap.dedent("""\
        def setChildLock(onOff) {
            if (!requireNotNull(onOff, "setChildLock")) return
            String v = (onOff as String).trim().toLowerCase()
            String canon = (v in ["on","true","1","yes"]) ? "on" : "off"
            if (device.currentValue("childLock") == canon) return true
            Integer sw = (canon == "on") ? 1 : 0
            hubBypass("setChildLock", [childLockSwitch: sw], "setChildLock(${canon})")
            device.sendEvent(name:"childLock", value: canon)
        }
    """)

    # (iii) doSet* bad-path: doSet* helper emits raw val → RULE33b flags it
    # This is the test that FAILS if SET_OR_DOSET_METHOD_RE is narrowed back to
    # set*-only (i.e. if the doSet prefix anchor is removed from the regex).
    BAD_DOSET_EMITS_RAW = textwrap.dedent("""\
        def doSetDisplayScreenSwitch(onOff) {
            if (!requireNotNull(onOff, "doSetDisplayScreenSwitch")) return
            String val = (onOff as String).trim().toLowerCase()
            if (device.currentValue("displayOn") == val) return true
            Integer sw = (val == "on") ? 1 : 0
            hubBypass("setDisplay", [screenSwitch: sw], "setDisplay(${val})")
            device.sendEvent(name:"displayOn", value: val)
        }
    """)

    # (iii) doSet* good-path: doSet* helper emits canon → 0 findings
    GOOD_DOSET_EMITS_CANON = textwrap.dedent("""\
        def doSetDisplayScreenSwitch(onOff) {
            if (!requireNotNull(onOff, "doSetDisplayScreenSwitch")) return
            String val = (onOff as String).trim().toLowerCase()
            String canon = (val in ["on","true","1","yes"]) ? "on" : "off"
            if (device.currentValue("displayOn") == canon) return true
            Integer sw = (canon == "on") ? 1 : 0
            hubBypass("setDisplay", [screenSwitch: sw], "setDisplay(${canon})")
            device.sendEvent(name:"displayOn", value: canon)
        }
    """)

    # (iv) strict-enum exemption: FanLib-shaped doSet* with in ["on","off"] gate
    # emitting raw s → NOT flagged (_STRICT_GATE_RE exemption).
    GOOD_DOSET_STRICT_GATE = textwrap.dedent("""\
        def doSetDisplayScreenSwitch(onOff) {
            if (!requireNotNull(onOff, "doSetDisplayScreenSwitch")) return
            String s = (onOff as String).trim().toLowerCase()
            if (!(s in ["on","off"])) { logWarn "invalid"; return }
            Integer sw = (s == "on") ? 1 : 0
            hubBypass("setDisplay", [screenSwitch: sw], "setDisplay(${s})")
            device.sendEvent(name:"displayOn", value: s)
        }
    """)

    def test_set_emits_raw_normalized_fails(self):
        """set* emitting raw toLowerCase var in sendEvent must flag RULE33b with severity FAIL."""
        from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent
        findings = run_rule(check_rule33b_raw_normalized_sendevent, self.BAD_SET_EMITS_RAW)
        assert any(f['rule_id'] == 'RULE33b_raw_normalized_sendevent' for f in findings), (
            f"Expected RULE33b_raw_normalized_sendevent for set* emitting raw v, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE33b_raw_normalized_sendevent'), (
            f"RULE33b finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_set_emits_canon_passes(self):
        """set* emitting truthy-canon-derived identifier must NOT flag RULE33b."""
        from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent
        findings = run_rule(check_rule33b_raw_normalized_sendevent, self.GOOD_SET_EMITS_CANON)
        assert not any(f['rule_id'] == 'RULE33b_raw_normalized_sendevent' for f in findings), (
            f"Canon-emitting set* must not flag RULE33b, got: {findings}"
        )

    def test_doset_emits_raw_normalized_fails(self):
        """
        doSet* emitting raw val in sendEvent must flag RULE33b.

        Non-vacuity contract: this test FAILS if SET_OR_DOSET_METHOD_RE is
        narrowed back to set*-only (removing the doSet prefix), because then the
        doSet*-named method falls outside the rule's scan scope entirely.
        Restoring the doSet prefix to the regex makes this test pass.
        """
        from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent
        findings = run_rule(check_rule33b_raw_normalized_sendevent, self.BAD_DOSET_EMITS_RAW)
        assert any(f['rule_id'] == 'RULE33b_raw_normalized_sendevent' for f in findings), (
            f"Expected RULE33b_raw_normalized_sendevent for doSet* emitting raw val, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE33b_raw_normalized_sendevent'), (
            f"RULE33b finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_doset_emits_canon_passes(self):
        """doSet* emitting truthy-canon-derived identifier must NOT flag RULE33b."""
        from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent
        findings = run_rule(check_rule33b_raw_normalized_sendevent, self.GOOD_DOSET_EMITS_CANON)
        assert not any(f['rule_id'] == 'RULE33b_raw_normalized_sendevent' for f in findings), (
            f"Canon-emitting doSet* must not flag RULE33b, got: {findings}"
        )

    def test_doset_strict_enum_gate_passes(self):
        """
        FanLib-shaped doSet* with strict in ["on","off"] gate and no truthy variants
        must NOT flag RULE33b — the _STRICT_GATE_RE exemption applies.
        """
        from lint_rules.bp25_case_sensitivity import check_rule33b_raw_normalized_sendevent
        findings = run_rule(check_rule33b_raw_normalized_sendevent, self.GOOD_DOSET_STRICT_GATE)
        assert not any(f['rule_id'] == 'RULE33b_raw_normalized_sendevent' for f in findings), (
            f"Strict-gate doSet* must not flag RULE33b, got: {findings}"
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
    vacuous guard: the test passes even when the rule emits findings that would
    not gate the CI run, making the test appear green while the enforcement is
    absent.
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

    # ---- Style-variation regression guards ----
    # Each shape below was historically NOT flagged by SAFEINTARG_COERCION_FALLBACK_RE
    # and is a plausible authoring form. The regex must catch every shape that lets
    # an unsafe coercion ride inside a safeIntArg() fallback argument: any first-arg
    # form (identifier, dotted chain, optional-chain, bracket access, method call)
    # paired with any throwing coercion form (as Integer/int/Long/long, .toInteger(),
    # Integer.parseInt, Long.parseLong) in the fallback position.

    # Method-call first arg + coercion fallback — must flag.
    BAD_METHOD_CALL_FIRST_ARG = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(getFanSpeed(), (state.room_size ?: 800) as Integer)
        }
    """)

    # Bracket-access first arg + coercion fallback — must flag.
    BAD_BRACKET_ACCESS_FIRST_ARG = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(state.list[0], (state.room_size ?: 800) as Integer)
        }
    """)

    # Optional-chain first arg + coercion fallback — must flag.
    BAD_OPTIONAL_CHAIN_FIRST_ARG = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(state.config?.size, (state.room_size ?: 800) as Integer)
        }
    """)

    # `as Long` fallback — must flag (same throw-on-non-numeric shape as `as Integer`).
    BAD_FALLBACK_AS_LONG = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Long sz = safeIntArg(roomSize, (state.room_size ?: 800L) as Long)
        }
    """)

    # `as long` lowercase fallback — must flag.
    BAD_FALLBACK_AS_LONG_LOWERCASE = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            long sz = safeIntArg(roomSize, (state.room_size ?: 800L) as long)
        }
    """)

    # `Integer.parseInt(...)` fallback — must flag.
    BAD_FALLBACK_PARSEINT = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(roomSize, Integer.parseInt(state.room_size_str))
        }
    """)

    # `Long.parseLong(...)` fallback — must flag.
    BAD_FALLBACK_PARSELONG = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Long sz = safeIntArg(roomSize, Long.parseLong(state.room_size_str))
        }
    """)

    # Good: method-call first arg with NESTED safeIntArg fallback — must NOT flag.
    GOOD_METHOD_CALL_FIRST_ARG_NESTED = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(getFanSpeed(), safeIntArg(state.room_size, 800))
        }
    """)

    # Good: bracket-access first arg with literal fallback — must NOT flag.
    GOOD_BRACKET_FIRST_ARG_LITERAL = textwrap.dedent("""\
        def setAutoMode(mode, roomSize) {
            Integer sz = safeIntArg(state.list[0], 800)
        }
    """)

    def test_method_call_first_arg_fallback_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_METHOD_CALL_FIRST_ARG)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Method-call first arg with coercion fallback must flag, got: {findings}"
        )

    def test_bracket_access_first_arg_fallback_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_BRACKET_ACCESS_FIRST_ARG)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Bracket-access first arg with coercion fallback must flag, got: {findings}"
        )

    def test_optional_chain_first_arg_fallback_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_OPTIONAL_CHAIN_FIRST_ARG)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Optional-chain first arg with coercion fallback must flag, got: {findings}"
        )

    def test_fallback_as_long_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_FALLBACK_AS_LONG)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"`as Long` fallback must flag (same throw shape as `as Integer`), got: {findings}"
        )

    def test_fallback_as_long_lowercase_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_FALLBACK_AS_LONG_LOWERCASE)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"`as long` lowercase fallback must flag, got: {findings}"
        )

    def test_fallback_parseint_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_FALLBACK_PARSEINT)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Integer.parseInt(...) fallback must flag, got: {findings}"
        )

    def test_fallback_parselong_fails(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_FALLBACK_PARSELONG)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Long.parseLong(...) fallback must flag, got: {findings}"
        )

    def test_method_call_first_arg_with_nested_safeintarg_passes(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_METHOD_CALL_FIRST_ARG_NESTED)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Method-call first arg with nested safeIntArg fallback must NOT flag, got: {findings}"
        )

    def test_bracket_first_arg_with_literal_passes(self):
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_BRACKET_FIRST_ARG_LITERAL)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Bracket-access first arg with literal fallback must NOT flag, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE37 extension — BP26 relational-comparison implicit coercion
# ---------------------------------------------------------------------------

class TestRule37BP26RelationalComparison:
    """
    RULE37 extension: a relational comparison (``<``, ``>``, ``<=``, ``>=``)
    between an untyped command parameter and a numeric literal is a BP26
    violation.  Groovy implicitly coerces the parameter to a number for the
    comparison; on a non-numeric input (e.g. ``""`` from a blank Rule Machine
    slot) it throws ``GroovyCastException`` before the comparison executes.
    The sandbox swallows the exception silently.

    This shape is distinct from the explicit ``as Integer`` / ``.toInteger()``
    forms and requires a separate regex branch in the rule.

    Dead-gate severity assertion: the finding MUST carry ``severity='FAIL'``
    to block ``lint --strict``.

    Non-vacuity contract: ``test_comparison_on_untyped_param_fails`` FAILS
    when the RELATIONAL_RE branch is removed from the rule and PASSES with it
    present.  Removing the ``else`` branch that calls RELATIONAL_RE makes this
    test raise AssertionError; the branch's presence is what makes this test pass.
    """

    from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion as _rule

    # BAD: untyped param compared directly with a numeric literal — must flag.
    BAD_RELATIONAL = textwrap.dedent("""\
        def setLevel(level) {
            if (level == null) { logWarn "null"; return }
            if (level < 10) { setNightLight("off") }
            else if (level > 75) { setNightLight("on") }
            else setNightLight("dim")
        }
    """)

    # GOOD: param coerced through safeIntArg first — comparison is on the safe local.
    GOOD_SAFE_LOCAL = textwrap.dedent("""\
        def setLevel(level) {
            if (!requireNotNull(level, "setLevel")) return
            Integer pct = safeIntArg(level, 0, 0, 100)
            if (pct < 10) { setNightLight("off") }
            else if (pct > 75) { setNightLight("on") }
            else setNightLight("dim")
        }
    """)

    # GOOD: typed param — Hubitat sandbox enforces type at dispatch, comparison is safe.
    GOOD_TYPED_PARAM = textwrap.dedent("""\
        def setLevel(Integer level) {
            if (level < 10) { setNightLight("off") }
            else if (level > 75) { setNightLight("on") }
        }
    """)

    # GOOD: comparison on state.x — state fields are NOT command parameters; must NOT flag.
    GOOD_STATE_COMPARISON = textwrap.dedent("""\
        def setMode(mode) {
            if (!requireNotNull(mode, "setMode")) return
            if (state.speed < 3) { logDebug "low" }
        }
    """)

    def test_comparison_on_untyped_param_fails(self):
        """
        Relational comparison on untyped command param must flag RULE37 with severity FAIL.

        Non-vacuity: this test FAILS when the RELATIONAL_RE branch of the rule is removed
        and PASSES when the branch is present.  Removing the ``else`` block that iterates
        RELATIONAL_RE from ``check_rule37_unsafe_int_coercion`` causes the ``assert any(...)``
        call below to raise AssertionError — the finding list is empty without that branch.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_RELATIONAL)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for relational comparison on untyped param, "
            f"got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_safe_local_after_safeintarg_passes(self):
        """safeIntArg-guarded comparison on typed local must NOT flag."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_SAFE_LOCAL)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"safeIntArg-guarded comparison must not flag RULE37, got: {findings}"
        )

    def test_typed_param_comparison_passes(self):
        """Typed parameter (Integer level) is exempt — sandbox enforces type at dispatch."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_TYPED_PARAM)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Typed param comparison must not flag RULE37, got: {findings}"
        )

    def test_state_field_comparison_passes(self):
        """Comparison on state.x (not a param) must NOT flag RULE37."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_STATE_COMPARISON)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"state.field comparison must not flag RULE37, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE40 — BP28 level/mist setter safeIntArg(param,0) -> off() ambiguity
# ---------------------------------------------------------------------------

class TestRule40BP28LevelOffAmbiguity:
    """
    RULE40 (Bug Pattern #28): a set* method that assigns a local from
    ``safeIntArg(<param>, 0)`` and routes it into a numeric-threshold branch
    (``<= 0`` / ``== 0`` / ``< <int-literal>``) whose body calls an off-form
    (``off()`` OR ``set*("off")``), without a ``parseLevelOrNull`` null-ignore
    guard, silently turns the device (or a sub-feature) OFF on non-numeric input.
    The fix uses ``parseLevelOrNull``.
    """

    from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity as _rule

    # MUST-NOT-CATCH: the fixed shape (parseLevelOrNull guard present).
    GOOD_PARSE_LEVEL_OR_NULL = textwrap.dedent("""\
        def setMistLevel(level) {
            if (!requireNotNull(level, "setMistLevel")) return
            Integer lvl = parseLevelOrNull(level)
            if (lvl == null) { logWarn "setMistLevel: ignoring non-numeric value '${level}'"; return }
            if (lvl <= 0) { off(); return }
            Integer clamped = Math.max(1, Math.min(9, lvl))
            ensureSwitchOn()
            hubBypass("setVirtualLevel", [level: clamped], "setVirtualLevel(${clamped})")
        }
    """)

    # MUST-NOT-CATCH: setLevel via the 4-arg clamp overload, fixed with parseLevelOrNull.
    GOOD_PARSE_LEVEL_CLAMP = textwrap.dedent("""\
        def setLevel(val) {
            Integer pct = parseLevelOrNull(val)
            if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${val}'"; return }
            pct = Math.max(0, Math.min(100, pct))
            if (pct == 0) { off(); return }
            ensureSwitchOn()
            sendLevel(pct)
        }
    """)

    # MUST-NOT-CATCH: safeIntArg(param,0) but NO off()-on-zero branch (setTimer: 0 = no timer).
    GOOD_SAFEINTARG_NO_OFF = textwrap.dedent("""\
        def setTimer(seconds) {
            int secs = safeIntArg(seconds, 0)
            if (secs <= 0) { delTimer(); return }
            hubBypass("addTimer", [clkSec: secs], "addTimer(${secs})")
        }
    """)

    # MUST-NOT-CATCH: off()-on-zero but the zero comes from a NON-param source
    # (computed internal value, never raw user garbage).
    GOOD_OFF_FROM_NON_PARAM = textwrap.dedent("""\
        def setSomething(arg) {
            Integer derived = computeBand(arg)
            if (derived == 0) { off(); return }
            hubBypass("x", [v: derived], "x(${derived})")
        }
    """)

    # MUST-CATCH: the OLD ambiguous shape — safeIntArg(level,0) -> if(lvl<=0){off()}.
    BAD_SAFEINTARG_OFF = textwrap.dedent("""\
        def setMistLevel(level) {
            if (!requireNotNull(level, "setMistLevel")) return
            Integer lvl = safeIntArg(level, 0)
            if (lvl <= 0) { off(); return }
            Integer clamped = Math.max(1, Math.min(9, lvl))
            ensureSwitchOn()
            hubBypass("setVirtualLevel", [level: clamped], "setVirtualLevel(${clamped})")
        }
    """)

    # MUST-CATCH: setLevel with safeIntArg 4-arg clamp -> if(pct==0){off()}.
    BAD_SAFEINTARG_CLAMP_OFF = textwrap.dedent("""\
        def setLevel(val) {
            Integer pct = safeIntArg(val, 0, 0, 100)
            if (pct == 0) { off(); return }
            ensureSwitchOn()
            sendLevel(pct)
        }
    """)

    # MUST-CATCH: Core 200S Light shape — safeIntArg(level,0,0,100) -> if(pct<10){setNightLight("off")}.
    # The off-form is set*("off"), not bare off(); the threshold is `< N`, not `<= 0`. RULE40 must
    # catch this broadened off-form / threshold pair (the blind spot QA flagged).
    BAD_SETNIGHTLIGHT_OFF = textwrap.dedent("""\
        def setLevel(level) {
            if (!requireNotNull(level, "setLevel")) return
            Integer pct = safeIntArg(level, 0, 0, 100)
            if (pct < 10) { setNightLight("off") }
            else if (pct > 75) { setNightLight("on") }
            else setNightLight("dim")
        }
    """)

    # MUST-NOT-CATCH: a safeIntArg-fed `< N` branch whose body does NOT call an off-form.
    # Proves the four-way conjunction gates correctly — fallback-0 + `< N` threshold alone
    # is NOT enough; condition (iii) (off-form in branch body) must also hold.
    GOOD_SAFEINTARG_LT_NO_OFFFORM = textwrap.dedent("""\
        def setBrightness(level) {
            Integer pct = safeIntArg(level, 0)
            if (pct < 10) { pct = 10 }
            hubBypass("setBrightness", [brightness: pct], "setBrightness(${pct})")
        }
    """)

    def test_parse_level_or_null_passes(self):
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.GOOD_PARSE_LEVEL_OR_NULL)
        assert not any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"parseLevelOrNull guard must not flag RULE40, got: {findings}"
        )

    def test_parse_level_clamp_passes(self):
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.GOOD_PARSE_LEVEL_CLAMP)
        assert not any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"parseLevelOrNull clamp form must not flag RULE40, got: {findings}"
        )

    def test_safeintarg_without_off_passes(self):
        """safeIntArg(param,0) with no off()-on-zero branch is out of scope."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.GOOD_SAFEINTARG_NO_OFF)
        assert not any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"safeIntArg without off()-on-zero must not flag RULE40, got: {findings}"
        )

    def test_off_from_non_param_passes(self):
        """off()-on-zero whose zero is a computed value (not safeIntArg(param,0)) is out of scope."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.GOOD_OFF_FROM_NON_PARAM)
        assert not any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"off() from non-param value must not flag RULE40, got: {findings}"
        )

    def test_safeintarg_off_fails(self):
        """OLD shape: safeIntArg(level,0) -> if(lvl<=0){off()} must flag RULE40 with FAIL."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.BAD_SAFEINTARG_OFF)
        assert any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"Expected RULE40_level_off_ambiguity for safeIntArg->off shape, got: {findings}"
        )
        # Regression guard: missing severity key is a dead-gate — verify present and FAIL.
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE40_level_off_ambiguity'), (
            f"RULE40 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_safeintarg_clamp_off_fails(self):
        """OLD shape: safeIntArg(val,0,0,100) -> if(pct==0){off()} must flag RULE40."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.BAD_SAFEINTARG_CLAMP_OFF)
        assert any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"Expected RULE40_level_off_ambiguity for safeIntArg clamp->off shape, got: {findings}"
        )

    def test_setnightlight_off_form_fails(self):
        """Core 200S Light shape: safeIntArg(level,0,0,100) -> if(pct<10){setNightLight("off")}
        must flag RULE40 — the set*("off") off-form + `< N` threshold blind spot."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.BAD_SETNIGHTLIGHT_OFF)
        assert any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"Expected RULE40_level_off_ambiguity for setNightLight('off') off-form, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE40_level_off_ambiguity'), (
            f"RULE40 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_safeintarg_lt_without_offform_passes(self):
        """A safeIntArg-fed `< N` branch whose body does NOT call an off-form must NOT flag —
        proves the four-way conjunction gates (condition iii: off-form in branch body)."""
        from lint_rules.bp28_level_off_ambiguity import check_rule40_level_off_ambiguity
        findings = run_rule(check_rule40_level_off_ambiguity, self.GOOD_SAFEINTARG_LT_NO_OFFFORM)
        assert not any(f['rule_id'] == 'RULE40_level_off_ambiguity' for f in findings), (
            f"safeIntArg-fed `< N` clamp without off-form must not flag RULE40, got: {findings}"
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
# Parity guard: make_finding_for_file severity gate
# ---------------------------------------------------------------------------

class TestMakeFindingForFileSeverityGate:
    """
    Parity guard: make_finding_for_file re-implements the severity gate inline
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
# RULE38 — process-token scrub (authoritative form-set)
# ---------------------------------------------------------------------------

from lint_rules.process_token_scrub import check_rule38_process_token_scrub


class TestRule38ProcessTokenScrub:
    """
    RULE38: process-token labels must not appear in code or test comments.

    Must-catch: every form in the authoritative form-set fires a FAIL finding.
    Must-not-catch: external-provenance citations are explicitly suppressed.

    Non-vacuity contracts (both-ways):
      - must-catch assertions FAIL if the rule's pattern is reverted/narrowed.
      - must-not-catch assertions FAIL if the allowlist is removed.
    """

    # -----------------------------------------------------------------------
    # Helpers
    # -----------------------------------------------------------------------

    @staticmethod
    def _run_groovy(comment_line: str) -> list:
        """
        Invoke RULE38 against a minimal Groovy driver snippet containing the
        given comment line inside a method body.  Returns findings list.
        """
        src = (
            'metadata { definition(name:"TestDriver") { } }\n'
            f'def someMethod() {{\n'
            f'    {comment_line}\n'
            f'    hubBypass("get", [:])\n'
            f'}}\n'
        )
        return run_rule(check_rule38_process_token_scrub, src, "TestDriver.groovy")

    @staticmethod
    def _run_py(comment_line: str) -> list:
        """
        Invoke RULE38 against a minimal Python snippet containing the given
        comment line.  Returns findings list.
        """
        src = (
            'def some_function():\n'
            f'    {comment_line}\n'
            '    pass\n'
        )
        path = REPO_ROOT / 'tests' / 'lint_rules' / 'test_fixture.py'
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        return check_rule38_process_token_scrub(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    # -----------------------------------------------------------------------
    # Must-catch: Tier forms
    # -----------------------------------------------------------------------

    def test_catches_tier_n_spaced(self):
        """'// Tier 22' must be flagged — spaced Tier N form."""
        findings = self._run_groovy('// Tier 22 guard: ...')
        assert findings, "Expected RULE38 finding for 'Tier 22'"
        assert findings[0]['severity'] == 'FAIL'
        assert findings[0]['rule_id'] == 'RULE38_process_token_scrub'

    def test_catches_tier_hyphen(self):
        """'// Tier-22' must be flagged — hyphenated form."""
        findings = self._run_groovy('// Tier-22 guard')
        assert findings, "Expected RULE38 finding for 'Tier-22'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_tier_hash(self):
        """'// tier #22' must be flagged — hash form, case-insensitive."""
        findings = self._run_groovy('// tier #22 fix')
        assert findings, "Expected RULE38 finding for 'tier #22'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_bare_t_digits(self):
        """'// T11' must be flagged — bare abbreviation."""
        findings = self._run_groovy('// T11 QA finding')
        assert findings, "Expected RULE38 finding for 'T11'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_t_digits_alnum(self):
        """'// T21-A' must be flagged — T<digits>-<alnum> form."""
        findings = self._run_groovy('// T21-A: added guard')
        assert findings, "Expected RULE38 finding for 'T21-A'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_t11_hyphen_digit(self):
        """'// T11-1' must be flagged — T<digits>-<digit> form."""
        findings = self._run_groovy('// T11-1: parity test')
        assert findings, "Expected RULE38 finding for 'T11-1'"
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Must-catch: Sweep forms
    # -----------------------------------------------------------------------

    def test_catches_sweep_hash(self):
        """'// Sweep #16' must be flagged."""
        findings = self._run_groovy('// Sweep #16 remediation')
        assert findings, "Expected RULE38 finding for 'Sweep #16'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_sweep_spaced(self):
        """'// sweep 17' must be flagged — case-insensitive spaced form."""
        findings = self._run_groovy('// sweep 17 finding')
        assert findings, "Expected RULE38 finding for 'sweep 17'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_s_hash_digits(self):
        """'// S#16' must be flagged — compact S#N form."""
        findings = self._run_groovy('// S#16 PoC')
        assert findings, "Expected RULE38 finding for 'S#16'"
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Must-catch: PR / Issue forms (this-fork, Python files only)
    # NOTE: these use _run_py because PR/Issue patterns are only checked in
    # Python test/lint files, not in Groovy driver files.  See module docstring
    # in process_token_scrub.py for rationale.
    # -----------------------------------------------------------------------

    def test_catches_pr_hash(self):
        """'# PR #167' in a Python comment must be flagged — hash form."""
        findings = self._run_py('# PR #167 fixed this')
        assert findings, "Expected RULE38 finding for 'PR #167'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_pr_no_hash(self):
        """'# pr#4' in a Python comment must be flagged — no-space form."""
        findings = self._run_py('# pr#4 merged')
        assert findings, "Expected RULE38 finding for 'pr#4'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_issue_n(self):
        """'# Issue 2' in a Python comment must be flagged — no-hash form."""
        findings = self._run_py('# Issue 2: removed unused assignment')
        assert findings, "Expected RULE38 finding for 'Issue 2'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_issue_hash_n(self):
        """'# issue #296' in a Python comment must be flagged (no pyvesync context)."""
        findings = self._run_py('# issue #296: fixed polling')
        assert findings, "Expected RULE38 finding for 'issue #296'"
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Must-catch: QA-round labels
    # -----------------------------------------------------------------------

    def test_catches_lead_finding(self):
        """'// Lead-Finding-1' must be flagged."""
        findings = self._run_py('# Lead-Finding-1: parity guard')
        assert findings, "Expected RULE38 finding for 'Lead-Finding-1'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_round_n(self):
        """'// Round 3' must be flagged — QA round reference."""
        findings = self._run_groovy('// Round 3 fix: added null guard')
        assert findings, "Expected RULE38 finding for 'Round 3'"
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Must-catch: Bot names
    # -----------------------------------------------------------------------

    def test_catches_gemini_upper(self):
        """'// GEMINI' must be flagged — uppercase form."""
        findings = self._run_groovy('// GEMINI flagged this')
        assert findings, "Expected RULE38 finding for 'GEMINI'"
        assert findings[0]['severity'] == 'FAIL'

    def test_catches_gemini_lower(self):
        """'// gemini-code-assist' must be flagged."""
        findings = self._run_groovy('// gemini-code-assist review')
        assert findings, "Expected RULE38 finding for 'gemini-code-assist'"
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Must-NOT-catch: external-provenance allowlist (Python files)
    # These tests use _run_py to test the allowlist non-vacuously:
    # without the allowlist, each would be flagged as a PR/Issue process token.
    # -----------------------------------------------------------------------

    def test_allows_pyvesync_pr(self):
        """'# pyvesync PR #505' in Python comment must NOT be flagged."""
        findings = self._run_py('# see pyvesync PR #505 for context')
        assert not findings, (
            f"RULE38 must not flag pyvesync PR reference; got: {findings}"
        )

    def test_allows_pyvesync_issue(self):
        """'# pyvesync issue #296' in Python comment must NOT be flagged."""
        findings = self._run_py('# pyvesync issue #296 upstream fix')
        assert not findings, (
            f"RULE38 must not flag pyvesync issue reference; got: {findings}"
        )

    def test_allows_ha_pr(self):
        """'# HA PR #137544' in Python comment must NOT be flagged."""
        findings = self._run_py('# HA PR #137544 fix')
        assert not findings, (
            f"RULE38 must not flag HA PR reference; got: {findings}"
        )

    def test_allows_ha_issue(self):
        """'# HA issue #160387' in Python comment must NOT be flagged."""
        findings = self._run_py('# HA issue #160387 context')
        assert not findings, (
            f"RULE38 must not flag HA issue reference; got: {findings}"
        )

    def test_allows_homebridge(self):
        """'# Homebridge PR #12' in Python comment must NOT be flagged."""
        findings = self._run_py('# Homebridge PR #12 compat')
        assert not findings, (
            f"RULE38 must not flag Homebridge reference; got: {findings}"
        )

    def test_allows_smartthings(self):
        """'# SmartThings issue #5' in Python comment must NOT be flagged."""
        findings = self._run_py('# SmartThings issue #5 leftover')
        assert not findings, (
            f"RULE38 must not flag SmartThings reference; got: {findings}"
        )

    def test_allows_webdjoe(self):
        """'# webdjoe upstream PR #44' in Python comment must NOT be flagged."""
        findings = self._run_py('# webdjoe upstream PR #44 fix')
        assert not findings, (
            f"RULE38 must not flag webdjoe reference; got: {findings}"
        )

    def test_allows_niklasgustafs(self):
        """'# NiklasGustafsson PR #12' in Python comment must NOT be flagged."""
        findings = self._run_py('# ported from NiklasGustafsson upstream PR #12')
        assert not findings, (
            f"RULE38 must not flag NiklasGustafsson reference; got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-catch: bare 'upstream' must NOT suppress this-fork PR token
    # Non-vacuity: FAILS on pre-fix code (allowlist had 'upstream'), PASSES
    # after fix ('upstream' removed from allowlist).
    # -----------------------------------------------------------------------

    def test_still_flags_upstream_fork_pr(self):
        """'# upstream maintainer asked for PR #167 rework' must be flagged.
        Bare 'upstream' is not an external-provenance token — it co-occurs with
        this-fork process labels and must not suppress them."""
        findings = self._run_py('# upstream maintainer asked for PR #167 rework')
        assert findings, (
            "Expected RULE38 finding — bare 'upstream' must not suppress this-fork PR token"
        )
        assert findings[0]['severity'] == 'FAIL'

    # -----------------------------------------------------------------------
    # Scope: out-of-scope files must not be checked
    # -----------------------------------------------------------------------

    def test_out_of_scope_changelog(self):
        """CHANGELOG.md is out of scope — must not be checked."""
        src = '## [2.5] - 2026-05-04\n### Fixed\n- Tier 22 sweep completed\n'
        path = REPO_ROOT / 'CHANGELOG.md'
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        findings = check_rule38_process_token_scrub(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )
        assert not findings, (
            f"RULE38 must not scan CHANGELOG.md; got: {findings}"
        )

    def test_out_of_scope_contributing(self):
        """CONTRIBUTING.md is out of scope — must not be checked."""
        src = '### Comment-scrub\n\nSee Tier 22 example.\n'
        path = REPO_ROOT / 'CONTRIBUTING.md'
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        findings = check_rule38_process_token_scrub(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )
        assert not findings, (
            f"RULE38 must not scan CONTRIBUTING.md; got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Scope: non-comment code must not be flagged
    # -----------------------------------------------------------------------

    def test_no_flag_outside_comment(self):
        """Process token in string literal (not comment) must not be flagged."""
        findings = self._run_groovy('def x = "Tier 22"')
        assert not findings, (
            f"RULE38 must not flag process tokens outside comment regions; got: {findings}"
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


# ---------------------------------------------------------------------------
# Verifier internals — _strip_comments_and_strings
# ---------------------------------------------------------------------------

class TestStripCommentsAndStrings:
    """
    Persisted pytest suite for check_bp24_classification._strip_comments_and_strings.

    Covers ALL handled literal/comment forms including Groovy slashy strings
    and dollar-slashy strings (slashy-opener regression guards).

    Non-vacuity contracts (slashy-opener regression guards):
      - test_slashy_with_embedded_block_comment_* tests MUST FAIL when
        slashy-string detection is removed from _strip_comments_and_strings
        and replaced with code that dispatches // then /* without a prior
        slashy check.
      - These are the load-bearing slashy-opener regression guards.
    """

    @staticmethod
    def _strip(text: str) -> str:
        from _groovy_lex import _strip_comments_and_strings
        return _strip_comments_and_strings(text)

    # ---- // line comment ----

    def test_line_comment_removed(self):
        """// comment text must be stripped; trailing newline preserved."""
        out = self._strip('int x = 1 // some safeIntArg(x) comment\nreturn x\n')
        assert 'safeIntArg(' not in out
        assert 'return x' in out

    def test_line_comment_at_eof_removed(self):
        """// comment with no trailing newline must be stripped (break path)."""
        out = self._strip('int x = 1 // trailing')
        assert 'trailing' not in out

    # ---- /* */ block comment ----

    def test_block_comment_removed(self):
        """/* */ block comment must be stripped; embedded safeIntArg must not survive."""
        src = 'def x = 1\n/* safeIntArg(x) is described here */\nreturn x\n'
        out = self._strip(src)
        assert 'safeIntArg(' not in out
        assert 'return x' in out

    def test_block_comment_newlines_preserved(self):
        """Block comment spanning multiple lines must preserve line count."""
        src = 'a\n/* line1\nline2\nline3 */\nb\n'
        out = self._strip(src)
        assert out.count('\n') == src.count('\n')

    def test_unterminated_block_comment_truncates(self):
        """Unterminated /* is treated as rest-of-input stripped (break path)."""
        out = self._strip('int x = 1\n/* never closed\nsafeIntArg(x)\n')
        assert 'safeIntArg(' not in out

    # ---- terminated block comment mentioning safeIntArg (must strip; real call on a later line must survive) ----

    def test_terminated_comment_mentioning_safeintarg_stripped(self):
        """
        A terminated /* */ comment that mentions safeIntArg must be stripped,
        and the real safeIntArg call on a LATER line must survive.

        Regression guard: the verifier must not treat comment text mentioning
        safeIntArg as evidence the primary param is coerced.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    // safeIntArg( would be used here if this were a numeric setter\n'
            '    /* exclude: safeIntArg(onOff) is in this comment only */\n'
            '    Integer sw = (onOff == "on") ? 1 : 0\n'
            '    hubBypass("setDryingMode", [autoDryingSwitch: sw])\n'
            '}\n'
        )
        out = self._strip(src)
        # The comment-embedded safeIntArg must be stripped.
        # There is no real safeIntArg call in the code — after stripping, none
        # should appear.
        assert 'safeIntArg(' not in out, (
            "Terminated comment mentioning safeIntArg must be stripped; "
            f"survived in stripped output: {out!r}"
        )

    # ---- Double-quoted strings ----

    def test_double_quoted_string_contents_stripped(self):
        """String literal contents must be replaced; outer quotes preserved."""
        out = self._strip('def s = "safeIntArg(x) inside string"\nreturn s\n')
        assert 'safeIntArg(' not in out
        assert '""' in out  # placeholder present

    def test_double_quoted_escaped_quote(self):
        """Escaped \" inside a string must not close the string prematurely."""
        out = self._strip(r'def s = "he said \"safeIntArg(x)\"" + y')
        assert 'safeIntArg(' not in out

    # ---- Triple-quoted strings ----

    def test_triple_quoted_string_stripped(self):
        """Triple-quoted string contents must be stripped."""
        src = 'def s = """multi\nline\nsafeIntArg(x)"""\nreturn s\n'
        out = self._strip(src)
        assert 'safeIntArg(' not in out

    def test_triple_quoted_newlines_preserved(self):
        """Triple-quoted string must preserve line count via placeholder."""
        src = 'def s = """line1\nline2\nline3"""\nend\n'
        out = self._strip(src)
        assert out.count('\n') == src.count('\n')

    # ---- Single-quoted strings ----

    def test_single_quoted_string_stripped(self):
        """Single-quoted string contents must be stripped."""
        out = self._strip("def s = 'safeIntArg(x)'\nreturn s\n")
        assert 'safeIntArg(' not in out

    # ---- GString ${...} ----

    def test_gstring_expression_contents_stripped(self):
        """GString body including ${...} is stripped as part of the string literal."""
        out = self._strip('def s = "value: ${safeIntArg(x)}"\n')
        assert 'safeIntArg(' not in out

    # ---- Escape sequences ----

    def test_escape_sequences_inside_double_quote(self):
        """\\n, \\t, \\\\ inside string must not cause premature string close."""
        out = self._strip(r'def s = "a\nb\tc\\safeIntArg(x)"' + '\nreturn\n')
        assert 'safeIntArg(' not in out

    # ---- Dollar-slashy strings ----

    def test_dollar_slashy_stripped(self):
        """Dollar-slashy string $/.../$  body must be stripped."""
        src = 'def re = $/safeIntArg(x) inside dollar-slashy/$\nreturn\n'
        out = self._strip(src)
        assert 'safeIntArg(' not in out

    def test_dollar_slashy_with_embedded_block_comment_chars_stripped(self):
        """
        Dollar-slashy string containing /* must NOT open a block comment.

        If the dollar-slashy check fires BEFORE the /* check (correct), the
        entire string is consumed as a literal and safeIntArg on a later line
        survives in the stripped output.

        Regression guard (dollar-slashy variant): removing the dollar-slashy
        check causes $/ to be emitted as '$' then '/' where '/' may or may not
        enter slashy-string mode depending on context — but the embedded '/*'
        is then encountered as a bare / followed by *, which opens a block
        comment, truncating the remaining source including the real safeIntArg
        call.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    def re = $/a /* b/$\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'safeIntArg(seconds' in out, (
            "safeIntArg(seconds must survive stripping when dollar-slashy "
            "string contains /* — dollar-slashy must be consumed before /* dispatch. "
            f"Got stripped output: {out!r}"
        )

    # ---- Slashy strings (slashy-opener core regression guards) ----

    def test_slashy_stripped(self):
        """Groovy slashy string /.../  body must be stripped."""
        src = 'def re = /safeIntArg(x)/\nreturn\n'
        out = self._strip(src)
        assert 'safeIntArg(' not in out

    def test_slashy_with_embedded_block_comment_survives_safeintarg(self):
        """
        Slashy string /a/*b/ must NOT trigger block-comment parsing.
        The safeIntArg call on a subsequent line must survive in the
        stripped output.

        Regression guard (adversarial form 1 — no backslash):
        Without the slashy-opener check, the tokenizer sees '/*' inside the
        slashy literal and opens a block comment.  The '*/'-free remainder
        causes the 'break' path (unterminated block comment), truncating
        the rest of the body including the real safeIntArg call.
        This test FAILS without the slashy-opener check and PASSES with it.

        Non-vacuity: removing the slashy-string detection from
        _strip_comments_and_strings (i.e. reverting so '/' is never treated as
        a slashy opener) causes this test to FAIL.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    def re = /a/*b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'safeIntArg(seconds' in out, (
            "safeIntArg(seconds must survive stripping when slashy string "
            "contains /*; the slashy check must fire BEFORE the /* dispatch. "
            f"Got stripped output: {out!r}"
        )

    def test_slashy_with_backslash_embedded_block_comment_survives_safeintarg(self):
        """
        Slashy string /a\\/*b/ (with explicit \\/  escape) must not trigger
        block-comment parsing.

        Regression guard (adversarial form 2 — with backslash): this test
        FAILS without the slashy-opener check and PASSES with it applied.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    def re = /a\\/*b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'safeIntArg(seconds' in out, (
            "safeIntArg(seconds must survive stripping when slashy string "
            "contains \\/*. Got stripped output: {out!r}"
        )

    def test_division_operator_not_misread_as_slashy(self):
        """
        A '/' after an identifier (division context) must NOT open a slashy
        string.  The code after the '/' must survive in the stripped output.
        """
        src = 'def x = a / b\nint secs = safeIntArg(seconds, 0)\n'
        out = self._strip(src)
        assert 'safeIntArg(seconds' in out, (
            "Division operator '/' must not open a slashy string; "
            f"safeIntArg must survive. Got: {out!r}"
        )

    def test_slashy_after_equals_sign(self):
        """Slashy string opened after '=' (expression-start) must be stripped."""
        src = 'def re = /abc/\nint secs = safeIntArg(seconds, 0)\n'
        out = self._strip(src)
        # The slashy body /abc/ is stripped; safeIntArg on next line survives.
        assert 'safeIntArg(seconds' in out
        assert 'abc' not in out


# ---------------------------------------------------------------------------
# Verifier internals — _find_struct_braces / extract_method_body
# ---------------------------------------------------------------------------

class TestFindStructBraces:
    """
    Persisted pytest suite for check_bp24_classification._find_struct_braces
    and extract_method_body.

    Covers slashy-string brace desync (slashy-brace-desync guard).
    """

    @staticmethod
    def _braces(src: str, start: int = 0):
        from _groovy_lex import _find_struct_braces
        return _find_struct_braces(src, start)

    @staticmethod
    def _body(src: str, def_pos: int = 0) -> str:
        from _groovy_lex import extract_method_body
        return extract_method_body(src, def_pos)

    def test_simple_method_body(self):
        """Simple method body is extracted correctly."""
        src = 'def foo() { return 1 }'
        body = self._body(src)
        assert body == src

    def test_nested_braces_method_body(self):
        """Nested braces tracked correctly — body ends at outermost close."""
        src = 'def foo() { if (x) { return 1 } return 0 } def bar() { }'
        body = self._body(src)
        assert body == 'def foo() { if (x) { return 1 } return 0 }'

    def test_string_braces_not_structural(self):
        """Braces inside string literals must not affect depth tracking."""
        src = 'def foo() { def s = "{ not structural }" }'
        body = self._body(src)
        assert body == src

    def test_line_comment_brace_not_structural(self):
        """Braces inside // comments must not affect depth tracking."""
        src = 'def foo() {\n    // { not structural }\n    return 1\n}'
        body = self._body(src)
        assert body == src

    def test_block_comment_brace_not_structural(self):
        """Braces inside /* */ comments must not affect depth tracking."""
        src = 'def foo() {\n    /* { not structural } */\n    return 1\n}'
        body = self._body(src)
        assert body == src

    def test_slashy_string_open_brace_not_structural(self):
        """
        A '{' inside a slashy string must not increment brace depth.

        Regression guard (slashy-open-brace): without slashy-string recognition,
        the '{' inside the slashy literal is counted as a structural open-brace,
        increasing depth to 2.  The method body then extends to the next '}'
        that brings depth to 1, then the subsequent '}' that brings depth to 0
        — which is the close-brace of the FOLLOWING method.  With the
        slashy-opener check applied, the slashy string is consumed correctly
        and depth tracking is accurate.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    def re = /a{b/\n'
            '    hubBypass("setDryingMode", [sw: 1])\n'
            '}\n'
            'def otherMethod() { return 2 }\n'
        )
        body = self._body(src)
        # Body must end at the first method's closing brace, not leak into otherMethod.
        assert 'otherMethod' not in body, (
            "Slashy '{' must not extend body into next method. "
            f"Got body: {body!r}"
        )
        assert 'hubBypass' in body

    def test_slashy_string_close_brace_not_structural(self):
        """
        A '}' inside a slashy string must not decrement brace depth.

        Regression guard (slashy-close-brace): without slashy-string recognition,
        the '}' inside the slashy literal decrements depth prematurely and
        extract_method_body returns before reaching the real closing brace,
        truncating the body.
        """
        src = (
            'def setDryingMode(onOff) {\n'
            '    def re = /a}b/\n'
            '    hubBypass("setDryingMode", [sw: 1])\n'
            '}\n'
        )
        body = self._body(src)
        # Body must include hubBypass — not truncated by the slashy '}'.
        assert 'hubBypass' in body, (
            "Slashy '}' must not truncate method body early. "
            f"Got body: {body!r}"
        )

    def test_dollar_slashy_brace_not_structural(self):
        """Braces inside a dollar-slashy string must not affect depth tracking."""
        src = 'def foo() {\n    def re = $/a{b}c/$\n    return 1\n}'
        body = self._body(src)
        assert 'return 1' in body
        assert body.endswith('}')

    # ---- Keyword expression-start context (return, in, etc.) ----

    def test_return_slashy_close_brace_not_structural(self):
        """
        Regression guard: 'return /a}b/' — the '}' inside a slashy string
        opened after the keyword 'return' must NOT be counted structural.

        Without the keyword-opener set in _is_slashy_open_position, 'n' (last
        char of 'return') is not in _SLASHY_OPENER_PREV so the '/' is treated
        as division, the '}' is counted structural, and extract_method_body
        truncates the body early — hiding any safeIntArg call that follows.

        Non-vacuity: this test FAILS when _SLASHY_OPENER_KEYWORDS is removed
        from _is_slashy_open_position (i.e. when only single-char openers are
        checked) because 'return' ends in 'n', which is not in _SLASHY_OPENER_PREV.
        """
        src = (
            'def setTimer(seconds) {\n'
            '    if (x) return /a}b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        # _strip: the safeIntArg call must survive stripping
        from _groovy_lex import _strip_comments_and_strings
        stripped = _strip_comments_and_strings(src)
        assert 'safeIntArg(seconds' in stripped, (
            "_strip_comments_and_strings must not truncate source at '}' inside "
            "'return /a}b/'; the safeIntArg call after it must survive."
        )
        # extract_method_body: the body must not truncate at the slashy '}'
        body = self._body(src)
        assert 'safeIntArg(seconds' in body, (
            "extract_method_body must not truncate the method body at '}' inside "
            "'return /a}b/'; the safeIntArg call after it must be in the body."
        )

    def test_in_keyword_slashy_close_brace_not_structural(self):
        """
        Regression guard: 'x in /a}b/' — '}' inside slashy after 'in'
        must not be counted structural.  Companion to the 'return' test above.
        """
        src = (
            'def setTimer(seconds) {\n'
            '    def flag = x in /a}b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        body = self._body(src)
        assert 'safeIntArg(seconds' in body, (
            "extract_method_body must not truncate the method body at '}' inside "
            "'x in /a}b/'; 'in' must be recognized as an expression-start keyword."
        )

    # ---- Dollar-slashy $$ escape ----

    def test_dollar_slashy_double_dollar_escape_survives(self):
        """
        Regression guard (dollar-slashy-$$-escape): a dollar-slashy string '$/$$/$' (body = '$$', which
        in Groovy is an escaped '$') must close correctly so that following code is
        visible as real source.

        The pathological form is '$/$$/$':

          With the '$$' fix, _skip_dollar_slashy_string processes the '$$' escape
          (advancing past both dollars), then finds '/$' immediately and closes.
          The string spans only those 6 characters; the method body after it is
          real code visible to safeIntArg detection.

          Without the '$$' fix, the '$$' branch is absent.  The scanner processes
          the first '$' (no branch fires since text[i+1]='$', not '/'), advances
          by 1.  The second '$' has text[i+1]='/', so the '$/' escaped-slash branch
          fires, advancing by 2 (consuming the second '$' and the '/').  Now at the
          '$' of the real '/$' close marker.  That '$' has no following '/' that
          would form another '/$', so the scanner advances through it and on through
          the rest of the file without finding a close — _skip returns n (runs to
          end of input).  Everything after the opening '$/' including the safeIntArg
          call is consumed as string content and becomes invisible to the verifier.

        Non-vacuity: reverting the '$$' branch causes _skip to return n, so
        _strip_comments_and_strings treats the entire remainder of the source as
        a string literal — 'safeIntArg(seconds' is NOT in the stripped output and
        the assertion FAILs.
        """
        src = (
            'def setTimer(seconds) {\n'
            '    def re = $/$$/$\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        from _groovy_lex import _strip_comments_and_strings
        stripped = _strip_comments_and_strings(src)
        assert 'safeIntArg(seconds' in stripped, (
            "_strip_comments_and_strings: '$/$$/$' must close correctly so the "
            "safeIntArg call on the next line is visible as real code; without the "
            "'$$' escape fix the string runs to end-of-input hiding the call."
        )
        # extract_method_body: regardless of whether the string is correctly closed,
        # the safeIntArg call must be in the returned body.  (With fix: correctly
        # bounded body.  Without fix: _find_struct_braces finds no close brace after
        # the runaway string, so extract_method_body returns source[def_pos:] which
        # still contains safeIntArg — this assertion is a correctness check, not the
        # non-vacuous regression guard; the strip assertion above is load-bearing.)
        body = self._body(src)
        assert 'safeIntArg(seconds' in body, (
            "extract_method_body: the safeIntArg call must be reachable in the method "
            "body even when the dollar-slashy literal is present."
        )


# ---------------------------------------------------------------------------
# Verifier internals — is_behavioral_onoff_setter
# ---------------------------------------------------------------------------

class TestIsBehavioralOnoffSetter:
    """
    Persisted pytest suite for check_bp24_classification.is_behavioral_onoff_setter.

    Covers the key discriminator cases documented in the verifier's module docstring.
    """

    @staticmethod
    def _is_setter(name: str, body: str, source: str = '') -> bool:
        from check_bp24_classification import is_behavioral_onoff_setter
        return is_behavioral_onoff_setter(name, body, source or body)

    # escape-a: safeIntArg in a comment → method classified True (setter)
    def test_escape_a_safeintarg_in_comment_classified_setter(self):
        """
        A genuine on/off setter whose body has safeIntArg only in a comment
        must be classified True (is a boolean on/off setter).

        Non-vacuity: this test fails if _strip_comments_and_strings is broken
        and safeIntArg in the comment bleeds through as a structural match,
        causing primary_is_safeint=True and the non-emitting shape branch to
        return False (incorrectly excluding the setter).
        """
        body = (
            'def setDryingMode(onOff) {\n'
            '    // safeIntArg( would be used for a numeric setter\n'
            '    if (!requireNotNull(onOff, "setDryingMode")) return\n'
            '    String v = (onOff as String).trim().toLowerCase()\n'
            '    Integer sw = (v == "on") ? 1 : 0\n'
            '    hubBypass("setDryingMode", [autoDryingSwitch: sw])\n'
            '}\n'
        )
        result = self._is_setter('setDryingMode', body)
        assert result is True, (
            "setDryingMode with safeIntArg only in a comment must be "
            f"classified True (is a boolean on/off setter), got: {result}"
        )

    # escape-b: safeIntArg on optional secondary numeric param → classified True
    def test_escape_b_safeintarg_on_secondary_param_classified_setter(self):
        """
        An on/off setter that calls safeIntArg on an optional SECONDARY numeric
        parameter must still be classified True.

        The non-emitting shape discriminator checks whether the FIRST/primary
        parameter is safeIntArg-coerced; a secondary-param coercion must not
        exclude the method from on/off-setter classification.
        """
        body = (
            'def setDryingMode(onOff, level) {\n'
            '    if (!requireNotNull(onOff, "setDryingMode")) return\n'
            '    String v = (onOff as String).trim().toLowerCase()\n'
            '    Integer lvl = safeIntArg(level, 1)\n'
            '    Integer sw = (v == "on") ? 1 : 0\n'
            '    hubBypass("setDryingMode", [autoDryingSwitch: sw, level: lvl])\n'
            '}\n'
        )
        result = self._is_setter('setDryingMode', body)
        assert result is True, (
            "setDryingMode with safeIntArg on secondary param must be "
            f"classified True, got: {result}"
        )

    # setTimer shape: first param is safeIntArg-coerced → excluded (False)
    def test_settimer_excluded_because_primary_param_is_safeintarg(self):
        """
        setTimer whose first/primary parameter is numeric and safeIntArg-coerced
        must be classified False (not a boolean on/off setter).

        This is the exclusion that prevents duration commands from being
        enumerated as NO-ON sites.
        """
        body = (
            'def setTimer(seconds, action) {\n'
            '    if (!requireNotNull(seconds, "setTimer")) return\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    if (secs <= 0) { cancelTimer(); return }\n'
            '    String a = (action as String).trim().toLowerCase()\n'
            '    Integer sw = (a == "on") ? 1 : 0\n'
            '    hubBypass("addTimerV2", [enabled: sw, tmgEvt: [clkSec: secs]])\n'
            '}\n'
        )
        result = self._is_setter('setTimer', body)
        assert result is False, (
            "setTimer with safeIntArg on primary param must be classified "
            f"False (not a boolean on/off setter), got: {result}"
        )

    # setDryingMode genuine non-emitting binary on/off → classified True
    def test_setdryingmode_genuine_nonemitting_classified_setter(self):
        """
        setDryingMode — the canonical non-emitting binary on/off setter —
        must be classified True regardless of whether it has safeIntArg in
        comments or not.

        This exercises the comment-aware primary-param discriminator from the
        pytest side.
        """
        body = (
            'def setDryingMode(onOff) {\n'
            '    if (!requireNotNull(onOff, "setDryingMode")) return\n'
            '    String v = (onOff as String).trim().toLowerCase()\n'
            '    Integer sw = (v == "on") ? 1 : 0\n'
            '    hubBypass("setDryingMode", [autoDryingSwitch: sw])\n'
            '}\n'
        )
        result = self._is_setter('setDryingMode', body)
        assert result is True, (
            "setDryingMode must be classified True (non-emitting binary on/off setter), "
            f"got: {result}"
        )


# ---------------------------------------------------------------------------
# Verifier internals — find_safeintarg_command_methods (bp26)
# ---------------------------------------------------------------------------

class TestFindSafeIntArgCommandMethods:
    """
    Persisted pytest suite for check_bp26_spec_coverage.find_safeintarg_command_methods.

    Covers body-content scoping, the deleted-short-circuit no-op invariant,
    and skip-list exclusions.
    """

    @staticmethod
    def _find(src: str) -> list:
        from check_bp26_spec_coverage import find_safeintarg_command_methods
        return find_safeintarg_command_methods(src)

    def test_method_with_safeintarg_in_scope(self):
        """A public set* method with safeIntArg in body is in scope."""
        src = (
            'def setMistLevel(level) {\n'
            '    int lvl = safeIntArg(level, 0)\n'
            '    hubBypass("setVirtualLevel", [level: lvl])\n'
            '}\n'
        )
        result = self._find(src)
        assert 'setMistLevel' in result, (
            f"setMistLevel with safeIntArg must be in scope; got: {result}"
        )

    def test_method_without_safeintarg_excluded(self):
        """A public set* method without safeIntArg in body is out of scope."""
        src = (
            'def setChildLock(onOff) {\n'
            '    String v = (onOff as String).trim().toLowerCase()\n'
            '    hubBypass("setChildLock", [childLockSwitch: (v == "on") ? 1 : 0])\n'
            '}\n'
        )
        result = self._find(src)
        assert 'setChildLock' not in result, (
            f"setChildLock without safeIntArg must be excluded; got: {result}"
        )

    def test_skip_list_method_excluded(self):
        """Methods in the skip list (e.g. safeIntArg itself) must not appear."""
        src = (
            'def safeIntArg(val, fallback) {\n'
            '    try { return safeIntArg(val, fallback) }\n'
            '    catch (e) { return fallback }\n'
            '}\n'
        )
        result = self._find(src)
        assert 'safeIntArg' not in result, (
            f"safeIntArg helper must be excluded by skip list; got: {result}"
        )

    def test_body_scoping_limits_to_own_method(self):
        """
        safeIntArg in method B's body must NOT cause method A (which lacks it)
        to appear in results.

        This verifies brace-depth body scoping: method A's body ends at its own
        closing brace; method B's safeIntArg call is in B's scope only.
        """
        src = (
            'def setChildLock(onOff) {\n'
            '    String v = (onOff as String).toLowerCase()\n'
            '    hubBypass("setChildLock", [sw: (v=="on")?1:0])\n'
            '}\n'
            'def setMistLevel(level) {\n'
            '    int lvl = safeIntArg(level, 0)\n'
            '    hubBypass("setVirtualLevel", [level: lvl])\n'
            '}\n'
        )
        result = self._find(src)
        assert 'setMistLevel' in result
        assert 'setChildLock' not in result, (
            "setChildLock must not be in results because safeIntArg is in "
            f"setMistLevel's scope only; got: {result}"
        )


# ---------------------------------------------------------------------------
# Verifier internals — _is_onoff_emitting (c3 gate coverage)
# ---------------------------------------------------------------------------

class TestIsOnoffEmitting:
    """
    Persisted pytest suite for check_c3_gate_coverage._is_onoff_emitting
    and _resolve_delegate_callee_body core contracts.
    """

    @staticmethod
    def _emit(body: str) -> bool:
        from check_c3_gate_coverage import _is_onoff_emitting
        return _is_onoff_emitting(body)

    def test_direct_on_literal_emitting(self):
        """sendEvent with literal "on" value → emitting."""
        body = 'def f() { device.sendEvent(name:"switch", value: "on") }'
        assert self._emit(body) is True

    def test_direct_off_literal_emitting(self):
        """sendEvent with literal "off" value → emitting."""
        body = 'def f() { device.sendEvent(name:"switch", value: "off") }'
        assert self._emit(body) is True

    def test_identifier_via_tolowercase_emitting(self):
        """sendEvent emits var assigned via .toLowerCase() with on/off comparison → emitting."""
        body = (
            'def f(onOff) {\n'
            '    String v = (onOff as String).trim().toLowerCase()\n'
            '    if (v in ["on","off"]) { }\n'
            '    device.sendEvent(name:"switch", value: v)\n'
            '}\n'
        )
        assert self._emit(body) is True

    def test_identifier_via_truthy_ternary_emitting(self):
        """sendEvent emits var assigned via ? "on" : "off" ternary → emitting."""
        body = (
            'def f(onOff) {\n'
            '    String canon = (v in ["on","true","1","yes"]) ? "on" : "off"\n'
            '    device.sendEvent(name:"switch", value: canon)\n'
            '}\n'
        )
        assert self._emit(body) is True

    def test_mode_enum_identifier_not_emitting(self):
        """sendEvent emitting a mode-enum identifier (not on/off) → not emitting."""
        body = (
            'def setMode(mode) {\n'
            '    String m = (mode as String).trim().toLowerCase()\n'
            '    if (m in ["auto","sleep","manual"]) { }\n'
            '    device.sendEvent(name:"mode", value: m)\n'
            '}\n'
        )
        # m is assigned via .toLowerCase() but comparison is NOT on/off → not emitting.
        assert self._emit(body) is False

    def test_no_sendevent_not_emitting(self):
        """Body with no sendEvent → not emitting."""
        body = (
            'def setDryingMode(onOff) {\n'
            '    Integer sw = (onOff == "on") ? 1 : 0\n'
            '    hubBypass("setDryingMode", [autoDryingSwitch: sw])\n'
            '}\n'
        )
        assert self._emit(body) is False

    def test_resolve_delegate_callee_body_same_file(self):
        """_resolve_delegate_callee_body resolves a doSet* callee in the same source."""
        from check_c3_gate_coverage import _resolve_delegate_callee_body
        source = (
            'def setDisplay(onOff) {\n'
            '    String v = (onOff as String).toLowerCase()\n'
            '    doSetDisplayScreenSwitch(v)\n'
            '}\n'
            'def doSetDisplayScreenSwitch(val) {\n'
            '    Integer sw = (val == "on") ? 1 : 0\n'
            '    hubBypass("setDisplay", [screenSwitch: sw])\n'
            '    device.sendEvent(name:"displayOn", value: val)\n'
            '}\n'
        )
        callee_body = _resolve_delegate_callee_body('setDisplay', source, source)
        assert callee_body is not None, "Callee body must be resolved from same-file source"
        assert 'hubBypass' in callee_body

    def test_resolve_delegate_callee_body_missing_returns_none(self):
        """_resolve_delegate_callee_body returns None when callee not in source."""
        from check_c3_gate_coverage import _resolve_delegate_callee_body
        source = (
            'def setDisplay(onOff) {\n'
            '    doSetDisplayScreenSwitch(onOff)\n'
            '}\n'
        )
        # doSetDisplayScreenSwitch is not defined in this source snippet.
        result = _resolve_delegate_callee_body('setDisplay', source, source)
        assert result is None


class TestBp26SlashyDesync:
    """
    Slashy-string desync reproduction tests for find_safeintarg_command_methods.

    A slashy literal whose body contains '{' or '}' causes a slashy-unaware
    _find_struct_braces to miscount brace depth, truncating the method body early
    and causing find_safeintarg_command_methods to report the safeIntArg call as
    absent (false negative) or to include a method from the NEXT def in the
    brace-scan slice (false positive).

    These tests are the per-verifier counterpart to the TestFindStructBraces guards
    and verify that the fix propagated correctly through the shared module import.
    """

    @staticmethod
    def _find(source: str):
        from check_bp26_spec_coverage import find_safeintarg_command_methods
        return find_safeintarg_command_methods(source)

    def test_slashy_with_open_brace_safeintarg_found(self):
        """safeIntArg in a method that contains a slashy literal with '{' must be detected."""
        src = (
            'def setTimer(seconds) {\n'
            '    def re = /a{b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        methods = self._find(src)
        assert 'setTimer' in methods, (
            "find_safeintarg_command_methods must find setTimer when safeIntArg is present "
            "after a slashy literal with embedded '{'; old slashy-unaware brace scanner "
            "would mis-close the method body at the /{/ and miss the safeIntArg call."
        )

    def test_slashy_with_block_comment_marker_safeintarg_found(self):
        """safeIntArg after a slashy literal containing '/*' must be detected."""
        src = (
            'def setTimer(seconds) {\n'
            '    def re = /a/*b/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        methods = self._find(src)
        assert 'setTimer' in methods, (
            "find_safeintarg_command_methods must find setTimer when safeIntArg follows a "
            "slashy literal containing '/*'; old code treated /* as block-comment opener "
            "and truncated source, hiding the safeIntArg call."
        )

    def test_clean_method_without_safeintarg_not_reported(self):
        """A method with a slashy literal but no safeIntArg must NOT be reported."""
        src = (
            'def setMode(mode) {\n'
            '    def re = /a{b/\n'
            '    hubBypass("setPurifierMode", [workMode: mode])\n'
            '}\n'
        )
        methods = self._find(src)
        assert 'setMode' not in methods, (
            "find_safeintarg_command_methods must NOT report a method that lacks safeIntArg "
            "even when the method body contains a slashy literal with a brace."
        )

    def test_dollar_slashy_with_block_comment_marker_safeintarg_found(self):
        """safeIntArg after a dollar-slashy literal containing '/*' must be detected."""
        src = (
            'def setTimer(seconds) {\n'
            '    def re = $/a /* b/$\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        methods = self._find(src)
        assert 'setTimer' in methods, (
            "find_safeintarg_command_methods must find setTimer when safeIntArg follows a "
            "dollar-slashy literal containing '/*'."
        )


class TestC3SlashyDesync:
    """
    Slashy-string desync reproduction tests for check_c3_gate_coverage classify path.

    A slashy literal whose body contains '{' or '}' causes a slashy-unaware
    extract_method_body to return a truncated body, so the behavioral predicates
    (on/off comparison, API call, sendEvent) could be evaluated against an incomplete
    snippet — potentially misclassifying an in-scope setter as out-of-scope (false
    negative) or absorbing a neighboring method's content (false positive that
    inflates predicate matches).

    These tests drive the classify path via is_behavioral_onoff_setter + extract_method_body
    + classify directly, and verify that the fix propagated correctly through the shared
    module import.
    """

    @staticmethod
    def _run(source: str):
        """
        Run the c3 classify pipeline on a source snippet and return a dict
        mapping method_name -> classification string.

        Mirrors the inner loop of check_c3_gate_coverage.main() but operates on
        an in-memory source string rather than a real driver file.
        """
        from check_c3_gate_coverage import (
            ANY_METHOD_RE, is_behavioral_onoff_setter, is_delegator, classify
        )
        from _groovy_lex import extract_method_body

        all_starts = [
            (m.start(), m.group(1)) for m in ANY_METHOD_RE.finditer(source)
        ]
        all_starts.append((len(source), None))

        results = {}
        for i, (pos, method_name) in enumerate(all_starts[:-1]):
            if method_name is None:
                continue
            body = extract_method_body(source, pos)
            def_line = body.splitlines()[0] if body.splitlines() else ""
            if not is_behavioral_onoff_setter(method_name, body, source):
                continue
            if is_delegator(def_line, body):
                continue
            results[method_name] = classify(source, pos, body)
        return results

    def test_slashy_with_open_brace_setter_classified(self):
        """An on/off setter whose body has a slashy literal with '{' must be classified."""
        src = (
            'def setDisplay(onOff) {\n'
            '    def re = /a{b/\n'
            '    String val = (onOff as String).trim().toLowerCase()\n'
            '    String canon = (val in ["on","true","1","yes"]) ? "on" : "off"\n'
            '    Integer v = (canon == "on") ? 1 : 0\n'
            '    hubBypass("setDisplay", [screenSwitch: v])\n'
            '    // No C3 idempotency gate: display state not polled in this driver.\n'
            '    device.sendEvent(name:"displayOn", value: canon)\n'
            '}\n'
        )
        results = self._run(src)
        assert 'setDisplay' in results, (
            "classify pipeline must find setDisplay when the body contains a slashy literal "
            "with '{'; old slashy-unaware extract_method_body truncated the body at the /{/ "
            "closing slash, causing the method to fail all three behavioral predicates."
        )
        assert results['setDisplay'] == 'HAS_RATIONALE', (
            f"setDisplay must be classified HAS_RATIONALE; got {results['setDisplay']!r}"
        )

    def test_slashy_with_block_comment_marker_setter_classified(self):
        """An on/off setter whose body has a slashy literal containing '/*' must be classified."""
        src = (
            'def setChildLock(onOff) {\n'
            '    def re = /a/*b/\n'
            '    String val = (onOff as String).trim().toLowerCase()\n'
            '    String canon = (val in ["on","true","1","yes"]) ? "on" : "off"\n'
            '    Integer v = (canon == "on") ? 1 : 0\n'
            '    if (device.currentValue("childLock") == canon) return\n'
            '    hubBypass("setChildLock", [childLockSwitch: v])\n'
            '    device.sendEvent(name:"childLock", value: canon)\n'
            '}\n'
        )
        results = self._run(src)
        assert 'setChildLock' in results, (
            "classify pipeline must find setChildLock when body contains slashy literal "
            "with '/*'; old code treated /* as block-comment opener and truncated the body."
        )
        assert results['setChildLock'] == 'HAS_GATE', (
            f"setChildLock must be classified HAS_GATE; got {results['setChildLock']!r}"
        )


# ---------------------------------------------------------------------------
# Slashy-opener: ~ operator guards and structural-char token reset
# ---------------------------------------------------------------------------

class TestSlashyOpenerTildeOperator:
    """
    Regression guards for the '~' addition to _SLASHY_OPENER_PREV.

    A Groovy regex-find expression 'x =~ /pattern/' has '~' as the last
    character before the opening '/'.  If '~' is absent from _SLASHY_OPENER_PREV
    the '/' is mis-classified as a division operator: the lexer walks INTO the
    regex body, misreads trailing metacharacters as source tokens, and
    _find_struct_braces returns an unbalanced brace stream.

    Concrete real-corpus instance (LevoitDiagnosticsLib.groovy):
        def m = tn =~ /v?(\d+\.\d+(?:\.\d+)?)\s*$/
    At that line the '/' after '~' is a slashy opener; misclassifying it as
    division causes the lexer to walk into the pattern and misread the trailing
    '\\s*$/' span — an unbalanced brace stream follows, causing _find_struct_braces
    to report incorrect depth and silently drop any following safeIntArg() calls
    or on/off setter bodies from verifier scope.

    Non-vacuity contracts:
      - Each test MUST FAIL when '~' is removed from _SLASHY_OPENER_PREV and
        MUST PASS with it present.
      - The :415-shape test is the canonical real-corpus guard; the others cover
        the ==~ match operator and ~/re/ Pattern-literal forms.
    """

    @staticmethod
    def _strip(text: str) -> str:
        from _groovy_lex import _strip_comments_and_strings
        return _strip_comments_and_strings(text)

    @staticmethod
    def _braces(src: str, start: int = 0):
        from _groovy_lex import _find_struct_braces
        return _find_struct_braces(src, start)

    # ---- Real-corpus :415 shape ----

    def test_tilde_regex_find_safeintarg_survives(self):
        """
        Regression guard (real-corpus :415 shape): 'def m = tn =~ /pat}ern/'
        — the slashy body must be consumed by _strip so the embedded '}' does
        NOT appear in stripped output.

        The last char before '/' is '~' (the =~ find-operator).  With '~' in
        _SLASHY_OPENER_PREV: '/' opens a slashy string → body consumed →
        'pat}ern' absent from output → PASS.  Without '~': '/' treated as
        division → body NOT consumed → 'pat}ern' present in output → FAIL.

        Non-vacuity: removing '~' from _SLASHY_OPENER_PREV causes this test
        to FAIL (discriminating property: slashy-body-consumption).
        """
        src = (
            'def getDriverVersion() {\n'
            '    String tn = device?.typeName as String\n'
            '    def m = tn =~ /pat}ern/\n'
            '    int secs = safeIntArg(retrySeconds, 0)\n'
            '    hubBypass("getStatus", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'pat}ern' not in out, (
            "The slashy body 'pat}ern' must be consumed (absent from stripped "
            "output) when '~' is in _SLASHY_OPENER_PREV.  If '~' is removed, "
            "'/' is treated as division, the body is NOT consumed, and 'pat}ern' "
            f"remains in output.  Got stripped output: {out!r}"
        )

    def test_tilde_regex_find_brace_balance(self):
        """
        Regression guard (brace balance): 'def m = tn =~ /pattern\\s*$/' must
        not perturb _find_struct_braces brace counting.

        Without '~' in _SLASHY_OPENER_PREV the '/' is misread as division and
        the pattern body is scanned as source — metacharacters like '\\s*$/'
        and the method's real closing '}' together produce an unbalanced stream.

        Non-vacuity: removing '~' from _SLASHY_OPENER_PREV causes this test
        to FAIL (unbalanced open/close counts).
        """
        src = (
            'def getDriverVersion() {\n'
            '    String tn = device?.typeName as String\n'
            '    def m = tn =~ /v?(\\d+\\.\\d+(?:\\.\\d+)?)\\s*$/\n'
            '    if (m) return m[0][1]\n'
            '}\n'
            'def otherMethod() {\n'
            '    return 42\n'
            '}\n'
        )
        braces = self._braces(src)
        opens = sum(1 for _, k in braces if k == '{')
        closes = sum(1 for _, k in braces if k == '}')
        assert opens == closes, (
            f"_find_struct_braces must return a balanced brace stream when "
            f"source contains '=~ /pattern/'; got opens={opens} closes={closes}. "
            "Removing '~' from _SLASHY_OPENER_PREV causes the regex body to be "
            "scanned as source, producing extra phantom closes."
        )

    # ---- Operator-class guards ----

    def test_tilde_match_operator_slashy_opened(self):
        """
        Regression guard: 'x ==~ /auto}sleep/' — the slashy body must be
        consumed by _strip so the embedded '}' does NOT appear in stripped
        output.

        '~' is the last char before '/' (from the ==~ match operator).  With
        '~' in _SLASHY_OPENER_PREV: '/' opens a slashy string → body consumed
        → 'auto}sleep' absent from output → PASS.  Without '~': '/' treated
        as division → body NOT consumed → 'auto}sleep' present → FAIL.

        Non-vacuity: removing '~' from _SLASHY_OPENER_PREV causes this test
        to FAIL (discriminating property: slashy-body-consumption).
        """
        src = (
            'def setMode(m) {\n'
            '    if (m ==~ /auto}sleep/) { }\n'
            '    int secs = safeIntArg(retrySeconds, 0)\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'auto}sleep' not in out, (
            "The slashy body 'auto}sleep' must be consumed (absent from stripped "
            "output) when '~' is in _SLASHY_OPENER_PREV.  If '~' is removed, "
            "'/' is treated as division, the body is NOT consumed, and 'auto}sleep' "
            f"remains in output.  Got: {out!r}"
        )

    def test_tilde_pattern_literal(self):
        """
        Regression guard: '~/auto}sleep/' is a Groovy Pattern literal — the
        slashy body must be consumed by _strip so the embedded '}' does NOT
        appear in stripped output.

        Unary '~' directly precedes the slashy opener.  With '~' in
        _SLASHY_OPENER_PREV: '/' opens a slashy string → body consumed →
        'auto}sleep' absent from output → PASS.  Without '~': '/' treated as
        division → body NOT consumed → 'auto}sleep' present → FAIL.

        Non-vacuity: removing '~' from _SLASHY_OPENER_PREV causes this test
        to FAIL (discriminating property: slashy-body-consumption).
        """
        src = (
            'def setMode(m) {\n'
            '    def pat = ~/auto}sleep/\n'
            '    int secs = safeIntArg(retrySeconds, 0)\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'auto}sleep' not in out, (
            "The slashy body 'auto}sleep' must be consumed (absent from stripped "
            "output) when '~' is in _SLASHY_OPENER_PREV.  If '~' is removed, "
            "'/' is treated as division, the body is NOT consumed, and 'auto}sleep' "
            f"remains in output.  Got: {out!r}"
        )



# ---------------------------------------------------------------------------
# Structural-char token-boundary reset in _strip_comments_and_strings
# ---------------------------------------------------------------------------

class TestStripStructuralCharTokenReset:
    """
    Regression guards for the structural-char branch in _strip_comments_and_strings
    and _find_struct_braces.

    When a non-whitespace, non-ident character is encountered, the cur_token /
    prev_token boundary must be committed so the GOVERNING token for a subsequent
    '/' resolves correctly.

    The discriminating shape is 'foo}return /x}y/' (NO space between '}' and
    'return').  The '}' is a structural char NOT in _SLASHY_OPENER_PREV:

      With reset ACTIVE: '}' commits cur_token='foo' → prev_token='foo',
      cur_token=''.  'return' accumulates as a fresh cur_token.  Space commits
      prev_token='return'.  At '/', last_structural='}' (not in set) → keyword
      path → 'return' in _SLASHY_OPENER_KEYWORDS → slashy → inner '}' not
      counted as a structural brace → balanced stream.

      With reset REVERTED to 'pass': '}' records as a structural brace but does
      NOT clear cur_token='foo'.  The following 'r','e',...'n' ALL satisfy
      c.isalpha() and cur_token is non-empty, so they APPEND to cur_token →
      cur_token='fooreturn'.  Space commits prev_token='fooreturn'.  At '/',
      governing='fooreturn' NOT in keywords → division → inner '}' counted as
      a structural close-brace → imbalanced / safeIntArg swallowed.

    This shape is load-bearing: a space between '}' and 'return' would allow the
    whitespace-commit branch to independently commit cur_token before 'return'
    accumulates, making the structural-char reset appear redundant for that path
    and the test vacuous.  The no-space form is the minimal discriminating shape.

    Non-vacuity contracts:
      - test_structural_char_resets_token_for_return_slashy: reverting the
        structural-char reset block ('if cur_token: prev_token = cur_token;
        cur_token = ""' inside 'if c not in " \\t\\n\\r":') in
        _strip_comments_and_strings to 'pass' causes this test to FAIL —
        'fooreturn' is accumulated, '/' is treated as division, the body /x}y/
        is NOT consumed, and 'x}y' remains in stripped output, triggering the
        'x}y' not in out assertion.
      - test_structural_char_reset_brace_balance: reverting the analogous reset
        block in _find_struct_braces to 'pass' causes this test to FAIL —
        'fooreturn' is accumulated, '/' is treated as division, the inner '}'
        of /x}y/ is counted as a structural close-brace → unbalanced stream.
    """

    @staticmethod
    def _strip(text: str) -> str:
        from _groovy_lex import _strip_comments_and_strings
        return _strip_comments_and_strings(text)

    @staticmethod
    def _braces(src: str, start: int = 0):
        from _groovy_lex import _find_struct_braces
        return _find_struct_braces(src, start)

    def test_structural_char_resets_token_for_return_slashy(self):
        """
        Regression guard: 'if (cond) {foo}return /x}y/' (no space between '}'
        and 'return') — the slashy body 'x}y' must be consumed by _strip so it
        does NOT appear in stripped output.

        With reset ACTIVE: '}' commits 'foo'→prev_token, clears cur_token.
        'return' accumulates fresh → keyword path → slashy opened → body /x}y/
        consumed → 'x}y' absent from output → PASS.

        Non-vacuity: reverting the structural-char reset block in
        _strip_comments_and_strings to 'pass' causes this test to FAIL:
        'fooreturn' is accumulated instead of 'return', '/' is treated as
        division, body /x}y/ is NOT consumed, and 'x}y' remains in stripped
        output, triggering the 'x}y' not in out assertion.
        """
        # 'foo}return' — no whitespace between the prior-token-ending '}' and 'return'.
        # The '}' is NOT in _SLASHY_OPENER_PREV so the slashy decision falls to
        # the keyword path, which requires 'return' to be correctly isolated as
        # prev_token.  The slashy body /x}y/ must be consumed, leaving 'x}y' absent.
        src = (
            'def setTimer(seconds) {\n'
            '    if (cond) {foo}return /x}y/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        out = self._strip(src)
        assert 'x}y' not in out, (
            "The slashy body 'x}y' must be consumed (absent from stripped output) "
            "when the structural-char reset correctly isolates 'return' as the "
            "governing token.  If the reset is reverted to 'pass', 'fooreturn' is "
            "accumulated instead of 'return', '/' is treated as division, the body "
            "is NOT consumed, and 'x}y' remains in output.  "
            f"Got: {out!r}"
        )

    def test_structural_char_reset_brace_balance(self):
        """
        Brace-balance companion: 'if (cond) {foo}return /x}y/' — _find_struct_braces
        must return a balanced stream (the '}' inside the slashy is not structural).

        Non-vacuity: reverting the structural-char reset block in _find_struct_braces
        (the analogous 'if cur_token:...' inside 'if c not in " \\t\\n\\r":') to
        'pass' causes this test to FAIL: 'fooreturn' is accumulated, '/' is treated
        as division, and the '}' inside /x}y/ is counted as a structural close-brace,
        producing one extra close relative to opens.
        """
        src = (
            'def setTimer(seconds) {\n'
            '    if (cond) {foo}return /x}y/\n'
            '    int secs = safeIntArg(seconds, 0)\n'
            '    hubBypass("addTimerV2", [tmgEvt:[clkSec:secs]])\n'
            '}\n'
        )
        braces = self._braces(src)
        opens = sum(1 for _, k in braces if k == '{')
        closes = sum(1 for _, k in braces if k == '}')
        assert opens == closes, (
            f"_find_struct_braces must return a balanced stream when source "
            f"contains 'foo}}return /x}}y/'; got opens={opens} closes={closes}. "
            "Without the structural-char token reset 'fooreturn' is accumulated, "
            "'/' is treated as division, and the inner '}}' is counted as a "
            "structural close-brace."
        )


# ---------------------------------------------------------------------------
# RULE37 extension — BP26 Map-field coercion inside set*(Map mapParam) methods
# ---------------------------------------------------------------------------

class TestRule37BP26MapFieldCoercion:
    """
    RULE37 B2 extension: set*(Map mapParam) methods must not use bare
    ``as Integer`` / ``.toInteger()`` on Map-field accesses
    (e.g. ``colorMap?.hue as Integer``).

    The original RULE37 implementation only scanned the FIRST UNTYPED parameter
    of each set*/cycle* method.  A Map-typed first parameter (``Map colorMap``)
    caused the method to be skipped entirely, making Map-field coercions invisible
    to lint.

    Hubitat ColorControl ``setColor(Map colorMap)`` receives Map values from:
    - Rule Machine 'Set Color' action -- values are Strings in hub-variable bindings
    - Dashboard color-picker tiles -- decimal strings ("55.5") are common
    - Any external integration passing a Groovy Map literal with String values

    A bare ``colorMap?.hue as Integer`` throws ``NumberFormatException`` on
    "55.5" BEFORE ``?:`` can rescue, and the sandbox swallows it silently.

    Non-vacuity contracts:
      - test_map_field_as_integer_fails: reverting the Map-field extension (removing
        SET_MAP_METHOD_RE loop from the rule) causes this test to FAIL because the
        rule returns [] for the BAD_MAP_FIELD_AS_INTEGER source.
      - test_map_field_safeintarg_passes: must NOT flag when safeIntArg() is used --
        confirms the extension does not over-fire on already-safe code.
    """

    from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion as _rule

    # Must-catch: bare `colorMap?.hue as Integer` inside set*(Map ...) — classic BP26 miss.
    BAD_MAP_FIELD_AS_INTEGER = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue  = (colorMap?.hue as Integer) ?: 0
            Integer sat  = (colorMap?.saturation as Integer) ?: 100
            Integer bri  = colorMap?.level as Integer
            hubBypass("setLightStatus", [brightness: bri])
        }
    """)

    # Must-catch: toInteger() form on Map field.
    BAD_MAP_FIELD_TO_INTEGER = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = colorMap?.hue.toInteger()
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # Must-not-catch: safeIntArg-guarded Map-field access — already safe.
    GOOD_MAP_FIELD_SAFEINTARG = textwrap.dedent("""\
        def setColor(Map colorMap){
            if (colorMap == null) { logWarn "null colorMap"; return }
            Integer hue100 = Math.max(0, Math.min(100, safeIntArg(colorMap?.hue, 0)))
            Integer sat100 = Math.max(0, Math.min(100, safeIntArg(colorMap?.saturation, 100)))
            Integer bri    = (colorMap?.level != null)
                ? Math.max(40, Math.min(100, safeIntArg(colorMap.level, 100)))
                : safeIntArg(state.nightlightBrightness, 100)
            hubBypass("setLightStatus", [brightness: bri])
        }
    """)

    # Must-not-catch: typed Integer param (not a Map) — original rule exemption still holds.
    GOOD_TYPED_INT_PARAM = textwrap.dedent("""\
        def setMistLevel(Integer level) {
            Integer lvl = (level as Integer) ?: 0
            hubBypass("setVirtualLevel", [level: lvl])
        }
    """)

    def test_map_field_as_integer_fails(self):
        """
        ``colorMap?.hue as Integer`` inside set*(Map ...) must flag RULE37 FAIL.

        Non-vacuity: removing SET_MAP_METHOD_RE loop from the rule returns [] here,
        making this test FAIL (no finding emitted when bug is present).
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_MAP_FIELD_AS_INTEGER)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for Map-field 'as Integer', got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 Map-field finding must carry severity='FAIL'; got: {findings}"
        )

    def test_map_field_to_integer_fails(self):
        """``colorMap?.hue.toInteger()`` inside set*(Map ...) must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_MAP_FIELD_TO_INTEGER)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for Map-field toInteger(), got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 Map-field finding must carry severity='FAIL'; got: {findings}"
        )

    def test_map_field_safeintarg_passes(self):
        """
        safeIntArg-guarded Map-field access must NOT flag — correct form, already safe.

        Non-vacuity: over-firing here would block all setColor(Map) implementations
        from passing lint, which is the wrong behavior.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_MAP_FIELD_SAFEINTARG)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"safeIntArg-guarded Map-field must not flag RULE37, got: {findings}"
        )

    def test_typed_int_param_passes(self):
        """Typed Integer param (not Map) remains exempt from Map-field extension."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_TYPED_INT_PARAM)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Typed Integer param must not flag via Map-field extension, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Blind-spot closures (a)-(f): each was missed by the original B2 pass.
    # -----------------------------------------------------------------------

    # (a) paren-before-as: (colorMap.hue) as Integer
    BAD_PAREN_BEFORE_AS = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = (colorMap.hue) as Integer
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # (b) multi-level chain: colorMap.color.hue as Integer
    BAD_MULTI_LEVEL_CHAIN = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = colorMap.color.hue as Integer
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # (c) aliased local: def h = colorMap.hue; h as Integer
    BAD_ALIASED_LOCAL = textwrap.dedent("""\
        def setColor(Map colorMap){
            def h = colorMap.hue
            Integer hue = h as Integer
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # (d) Integer.parseInt on a Map field
    BAD_PARSEINT = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = Integer.parseInt(colorMap.hue)
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # (e) bracket access: colorMap['hue'] as Integer
    BAD_BRACKET_ACCESS = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = colorMap['hue'] as Integer
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # (f) untyped Map param: def setColor(colorMap) with colorMap.hue as Integer
    BAD_UNTYPED_MAP_PARAM = textwrap.dedent("""\
        def setColor(colorMap){
            Integer hue = colorMap.hue as Integer
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    # Must-not-catch: safeIntArg on bracket access — already safe
    GOOD_BRACKET_SAFEINTARG = textwrap.dedent("""\
        def setColor(Map colorMap){
            Integer hue = safeIntArg(colorMap['hue'], 0)
            hubBypass("setLightStatus", [hue: hue])
        }
    """)

    def test_paren_before_as_fails(self):
        """(a) ``(colorMap.hue) as Integer`` must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_PAREN_BEFORE_AS)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for (colorMap.hue) as Integer, got: {findings}"
        )

    def test_multi_level_chain_fails(self):
        """(b) ``colorMap.color.hue as Integer`` (two-hop) must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_MULTI_LEVEL_CHAIN)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for multi-level chain, got: {findings}"
        )

    def test_aliased_local_fails(self):
        """(c) ``def h = colorMap.hue; h as Integer`` must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_ALIASED_LOCAL)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for aliased local, got: {findings}"
        )

    def test_parseint_fails(self):
        """(d) ``Integer.parseInt(colorMap.hue)`` must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_PARSEINT)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for Integer.parseInt, got: {findings}"
        )

    def test_bracket_access_fails(self):
        """(e) ``colorMap['hue'] as Integer`` must flag RULE37 FAIL."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_BRACKET_ACCESS)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for bracket access, got: {findings}"
        )

    def test_untyped_map_param_fails(self):
        """(f) ``def setColor(colorMap)`` (no Map keyword) with ``colorMap.hue as Integer`` must flag."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_UNTYPED_MAP_PARAM)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for untyped Map param, got: {findings}"
        )

    def test_bracket_safeintarg_passes(self):
        """safeIntArg on bracket access must NOT flag — correct form, already safe."""
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_BRACKET_SAFEINTARG)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"safeIntArg bracket access must not flag RULE37, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Item 1: partially-guarded method — safeIntArg on m.a but raw cast on m.b
    # -----------------------------------------------------------------------

    BAD_PARTIALLY_GUARDED = textwrap.dedent("""\
        def setX(Map m){
            Integer a = safeIntArg(m.a, 0)
            Integer b = m.b as Integer
            hubBypass("setX", [a: a, b: b])
        }
    """)

    GOOD_FULLY_GUARDED = textwrap.dedent("""\
        def setX(Map m){
            Integer a = safeIntArg(m.a, 0)
            Integer b = safeIntArg(m.b, 0)
            hubBypass("setX", [a: a, b: b])
        }
    """)

    def test_partially_guarded_map_method_flags_unguarded_field(self):
        """
        A set*(Map) body that guards m.a via safeIntArg but raw-casts m.b must
        STILL FLAG RULE37 for the m.b cast — the whole-method safeIntArg exemption
        must not suppress detection of unguarded fields.

        Non-vacuity: reverting the partial-guard fix (restoring the old
        ``if SAFE_INT_ARG_RE.search(body_clean): ... continue`` in the Map-field
        pass) causes this test to FAIL because the rule returns [] for the
        BAD_PARTIALLY_GUARDED source (whole-method skipped).
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_PARTIALLY_GUARDED)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37 for partially-guarded Map method (m.b unguarded), got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 finding for partially-guarded method must carry severity='FAIL'; got: {findings}"
        )

    def test_fully_guarded_map_method_passes(self):
        """
        A set*(Map) body where ALL Map-field coercions go through safeIntArg must
        NOT flag — all fields are already safe.

        Non-vacuity: over-firing here would block all fully-safe setX(Map) from
        passing lint, which is the wrong behavior.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_FULLY_GUARDED)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Fully-guarded Map method must not flag RULE37, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE37 extension — BP26 doSet* shared-helper coverage
# ---------------------------------------------------------------------------

class TestRule37BP26DoSetMethod:
    """
    RULE37 Item-3a extension: doSet* shared-helper definitions receive the same
    user-supplied numeric inputs as set* callers (the public set* normalises on/off,
    then delegates; the doSet* helper still coerces the integer payload).
    Bare ``param as Integer`` / ``.toInteger()`` in a doSet* body must be flagged.

    Non-vacuity contracts:
      - test_doset_method_as_integer_fails: reverting SET_METHOD_RE to exclude
        ``doSet`` (e.g. ``(?:set|cycle)`` only) causes this test to FAIL because
        the rule returns [] for the BAD_DOSET source (method not matched).
      - test_doset_method_safeintarg_passes: must NOT flag when safeIntArg() is
        used — confirms the extension does not over-fire on already-safe helpers.
    """

    from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion as _rule

    BAD_DOSET = textwrap.dedent("""\
        def doSetX(numericParam) {
            Integer v = numericParam as Integer
            hubBypass("setX", [val: v])
        }
    """)

    GOOD_DOSET = textwrap.dedent("""\
        def doSetX(numericParam) {
            Integer v = safeIntArg(numericParam, 0)
            hubBypass("setX", [val: v])
        }
    """)

    def test_doset_method_as_integer_fails(self):
        """
        ``param as Integer`` inside doSetX(param) must flag RULE37 FAIL.

        Non-vacuity: reverting SET_METHOD_RE to ``(?:set|cycle)`` only (removing
        ``doSet``) returns [] here, making this test FAIL.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_DOSET)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37_unsafe_int_coercion for doSetX bare 'as Integer', got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 doSet* finding must carry severity='FAIL'; got: {findings}"
        )

    def test_doset_method_safeintarg_passes(self):
        """
        safeIntArg-guarded doSetX body must NOT flag -- correct form.

        Non-vacuity: over-firing here would block all safe doSet* helpers from
        passing lint.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_DOSET)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"safeIntArg-guarded doSet* must not flag RULE37, got: {findings}"
        )

    def test_dosettle_not_matched_as_setter(self):
        """
        doSettle(x) must NOT flag RULE37 -- lowercase letter after 'doSet' prefix
        means the [A-Z] anchor correctly excludes it from the setter regex.

        Non-vacuity: removing the [A-Z] anchor (reverting to ``doSet\\w+``) causes
        this test to FAIL because doSettle would be matched as a setter.
        """
        src = textwrap.dedent("""\
            def doSettle(x) {
                Integer v = x as Integer
            }
        """)
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, src)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"doSettle() has lowercase 't' after doSet -- must not match as setter, got: {findings}"
        )

    def test_settings_not_matched_as_setter(self):
        """
        settings(x) must NOT flag RULE37 -- lowercase letter after 'set' prefix
        means the [A-Z] anchor correctly excludes it from the setter regex.

        Non-vacuity: removing the [A-Z] anchor (reverting to ``set\\w+``) causes
        this test to FAIL because settings would be matched as a setter.
        """
        src = textwrap.dedent("""\
            def settings(x) {
                Integer v = x as Integer
            }
        """)
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, src)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"settings() has lowercase 's' after 'set' -- must not match as setter, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE37 Pass-1 partial-guard — scalar-param safeIntArg must not exempt OTHER
# unguarded raw casts in the same method (closed-mechanism fix #9).
# ---------------------------------------------------------------------------

class TestRule37BP26ScalarPartialGuard:
    """
    RULE37 scalar-param partial-guard: the original Pass-1 implementation bailed
    out of the per-param cast scan the moment ``safeIntArg(`` appeared ANYWHERE
    in the method body.  A method that guards one param via safeIntArg but
    raw-casts the FIRST (untyped) param on a different statement therefore went
    unflagged.  The fix mirrors the Map-field partial-guard: only the SPECIFIC
    safeIntArg-guarded receiver is exempt; an unguarded raw cast on the first
    param is still flagged.
    """

    # Bad: setX(level) guards `other` via safeIntArg but raw-casts the first param `level`.
    BAD_SCALAR_PARTIAL_GUARD = textwrap.dedent("""\
        def setX(level) {
            Integer o = safeIntArg(other, 0)
            Integer v = level as Integer
            hubBypass("setX", [v: v, o: o])
        }
    """)

    # Good: the first param `level` itself goes through safeIntArg; the other coercion is also guarded.
    GOOD_SCALAR_FULLY_GUARDED = textwrap.dedent("""\
        def setX(level) {
            Integer v = safeIntArg(level, 0)
            Integer o = safeIntArg(other, 0)
            hubBypass("setX", [v: v, o: o])
        }
    """)

    def test_scalar_partial_guard_flags_unguarded_first_param(self):
        """
        Must-catch: safeIntArg on a non-first param does NOT exempt a raw
        ``level as Integer`` cast on the first param.

        Non-vacuity: reverting the fix (restoring the unconditional
        ``if SAFE_INT_ARG_RE.search(body_clean): ... continue``) makes the rule
        return [] for BAD_SCALAR_PARTIAL_GUARD, failing this assertion.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.BAD_SCALAR_PARTIAL_GUARD)
        assert any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Expected RULE37 for scalar partial-guard (level raw-cast, other guarded), got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE37_unsafe_int_coercion'), (
            f"RULE37 scalar partial-guard finding must carry severity='FAIL'; got: {findings}"
        )

    def test_scalar_fully_guarded_passes(self):
        """
        Must-not-catch: when the first param itself is safeIntArg-guarded (and all
        other coercions too), the method must NOT flag.
        """
        from lint_rules.bp26_unsafe_int_coercion import check_rule37_unsafe_int_coercion
        findings = run_rule(check_rule37_unsafe_int_coercion, self.GOOD_SCALAR_FULLY_GUARDED)
        assert not any(f['rule_id'] == 'RULE37_unsafe_int_coercion' for f in findings), (
            f"Fully-guarded scalar method must not flag RULE37, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE32 delegation-following — SHOULD-ON method delegating to a lib helper that
# lacks ensureSwitchOn() must be flagged (closed-mechanism fix #10).
# ---------------------------------------------------------------------------

class TestRule32DelegationFollowing:
    """
    RULE32 delegation-following: a SHOULD-ON method (setSpeed/setMode/etc.) with
    no DIRECT API call previously escaped the auto-on scan entirely
    (``if not API_CALL_RE.search(body_clean): continue``).  The fix follows a
    same-file delegate (handleX/doSetX/setFanSpeed-style); if the delegate makes
    the API call but neither the caller nor the delegate calls ensureSwitchOn(),
    the auto-on guard is missing across the delegation boundary → BP24-B.
    """

    # Bad: setSpeed delegates to handleSpeed (which makes the API call) but neither
    # setSpeed nor handleSpeed calls ensureSwitchOn().
    BAD_DELEGATE_NO_GUARD = textwrap.dedent("""\
        def setSpeed(spd) {
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Good: the delegate handleSpeed carries the ensureSwitchOn() guard.
    GOOD_DELEGATE_CALLEE_GUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            ensureSwitchOn()
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Also-good: the caller itself carries the guard before delegating.
    GOOD_DELEGATE_CALLER_GUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            ensureSwitchOn()
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Bad (multi-delegate gap): setSpeed delegates FIRST to setMode (an auto-path
    # helper with NO direct API call — itself guarded) and THEN to handleSpeed
    # (the normal path, which makes the real API call and has NO own guard).
    # setSpeed itself has no ensureSwitchOn().  This is the exact shape RULE32's
    # first-delegate-only resolution missed: it followed setMode (no direct API
    # → bailed) and never reached handleSpeed.  Following all first-hop delegates
    # must flag this.
    BAD_MULTI_DELEGATE_LATER_UNGUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            if (spd == "auto") {
                setMode(spd)
                return
            }
            handleSpeed(spd)
        }

        def setMode(mode) {
            ensureSwitchOn()
            handleMode(mode)
        }

        def handleMode(mode) {
            hubBypass("setPurifierMode", [mode: mode])
        }

        def handleSpeed(spd) {
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Good (multi-delegate, all protected): setSpeed delegates to setMode (no
    # direct API, guarded) AND handleSpeed — but handleSpeed carries its own
    # ensureSwitchOn().  Every API-calling delegate is guarded → no finding.
    GOOD_MULTI_DELEGATE_ALL_GUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            if (spd == "auto") {
                setMode(spd)
                return
            }
            handleSpeed(spd)
        }

        def setMode(mode) {
            ensureSwitchOn()
            handleMode(mode)
        }

        def handleMode(mode) {
            hubBypass("setPurifierMode", [mode: mode])
        }

        def handleSpeed(spd) {
            ensureSwitchOn()
            hubBypass("setLevel", [level: spd])
        }
    """)

    def test_delegate_callee_unguarded_flags(self):
        """
        Must-catch: SHOULD-ON method delegating to an unguarded API-calling callee
        must flag RULE32 BP24-B.

        Non-vacuity: reverting the fix (restoring ``if not API_CALL_RE.search:
        continue``) makes the rule return [] for BAD_DELEGATE_NO_GUARD (setSpeed
        has no direct API call so it would be skipped entirely), failing this.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.BAD_DELEGATE_NO_GUARD)
        assert any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"Expected RULE32 for SHOULD-ON method delegating to unguarded callee, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE32_auto_on_guard_missing'), (
            f"RULE32 delegation finding must carry severity='FAIL'; got: {findings}"
        )

    def test_delegate_callee_guarded_passes(self):
        """Must-not-catch: when the delegate callee has ensureSwitchOn(), no finding."""
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_DELEGATE_CALLEE_GUARDED)
        assert not any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"Delegate callee with ensureSwitchOn() must not flag RULE32, got: {findings}"
        )

    def test_delegate_caller_guarded_passes(self):
        """Must-not-catch: when the caller itself has ensureSwitchOn() before delegating, no finding."""
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_DELEGATE_CALLER_GUARDED)
        assert not any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"Caller with ensureSwitchOn() before delegate must not flag RULE32, got: {findings}"
        )

    def test_multi_delegate_later_unguarded_flags(self):
        """
        Must-catch (closed-mechanism gap fix): SHOULD-ON method with NO own guard
        that delegates FIRST to a no-direct-API (guarded) helper (setMode) and
        THEN to an unguarded API-calling helper (handleSpeed) must flag RULE32.

        This is the exact setSpeed→setMode→handleSpeed shape that first-delegate-
        only resolution missed: it followed setMode (no direct API → bailed) and
        never reached handleSpeed.  Following ALL first-hop delegates catches it.

        Non-vacuity: reverting the fix (returning only the FIRST resolvable
        delegate body) makes the rule resolve setMode, find no direct API call,
        skip, and return [] for this fixture — failing this assertion.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(
            check_rule32_auto_on_guard_missing, self.BAD_MULTI_DELEGATE_LATER_UNGUARDED
        )
        assert any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            "Expected RULE32 for SHOULD-ON method whose LATER delegate makes an "
            f"unguarded API call, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE32_auto_on_guard_missing'), (
            f"RULE32 multi-delegate finding must carry severity='FAIL'; got: {findings}"
        )

    def test_multi_delegate_all_guarded_passes(self):
        """
        Must-not-catch: SHOULD-ON method delegating to multiple helpers where
        every API-calling delegate carries ensureSwitchOn() — no finding even
        though following all delegates now reaches handleSpeed.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(
            check_rule32_auto_on_guard_missing, self.GOOD_MULTI_DELEGATE_ALL_GUARDED
        )
        assert not any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"All API-calling delegates guarded must not flag RULE32, got: {findings}"
        )

    # Bad (2-hop transitive gap): setSpeed → setSpeedLevel (no direct API, no
    # guard) → handleSpeed (unguarded API).  The unguarded API call is TWO hops
    # from setSpeed.  First-hop-only resolution reached setSpeedLevel, found no
    # direct API call, and stopped — never reaching handleSpeed.  Transitive
    # following walks setSpeed → setSpeedLevel → handleSpeed and flags the
    # unguarded leaf.  (All three names are in _DELEGATE_CALL_RE's vocabulary so
    # the chain is actually followed.)
    BAD_TWO_HOP_UNGUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            setSpeedLevel(spd)
        }

        def setSpeedLevel(spd) {
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Good (2-hop transitive, guard on the path at the middle hop): setSpeed →
    # setSpeedLevel (ensureSwitchOn, no direct API) → handleSpeed (unguarded
    # API).  Because setSpeedLevel powers the device on before delegating,
    # handleSpeed's API call is protected.  The guard on the intermediate hop
    # must prune handleSpeed's subtree.
    GOOD_TWO_HOP_GUARD_ON_PATH = textwrap.dedent("""\
        def setSpeed(spd) {
            setSpeedLevel(spd)
        }

        def setSpeedLevel(spd) {
            ensureSwitchOn()
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Good (2-hop transitive, guard on the leaf): setSpeed → setSpeedLevel (no
    # guard, no direct API) → handleSpeed (ensureSwitchOn + API).  The leaf
    # guards itself.
    GOOD_TWO_HOP_GUARD_ON_LEAF = textwrap.dedent("""\
        def setSpeed(spd) {
            setSpeedLevel(spd)
        }

        def setSpeedLevel(spd) {
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            ensureSwitchOn()
            hubBypass("setLevel", [level: spd])
        }
    """)

    # Bad (delegation cycle + unguarded leaf): exercises the visited-set's
    # cycle-safety.  setSpeed → setSpeedLevel → handleSpeed → handleLoop →
    # setSpeedLevel (cycle back) and handleSpeed also makes an unguarded API
    # call.  Without the visited-set this would loop forever; with it, the
    # traversal terminates and still flags the unguarded API call in handleSpeed.
    BAD_CYCLE_UNGUARDED = textwrap.dedent("""\
        def setSpeed(spd) {
            setSpeedLevel(spd)
        }

        def setSpeedLevel(spd) {
            handleSpeed(spd)
        }

        def handleSpeed(spd) {
            handleLoop(spd)
            hubBypass("setLevel", [level: spd])
        }

        def handleLoop(spd) {
            setSpeedLevel(spd)
        }
    """)

    def test_two_hop_unguarded_flags(self):
        """
        Must-catch (closed-mechanism transitive fix): SHOULD-ON method whose
        unguarded API call is reached only via a 2-hop chain
        (setSpeed → helperA no-direct-API/guard-clean → helperB unguarded API)
        must flag RULE32 BP24-B.

        Non-vacuity: reverting the traversal back to first-hop-only resolution
        makes the rule resolve helperA, find no direct API call, stop without
        recursing into helperB, and return [] for this fixture — failing this
        assertion.  Only transitive following reaches the unguarded leaf.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.BAD_TWO_HOP_UNGUARDED)
        assert any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            "Expected RULE32 for SHOULD-ON method whose 2-hop-deep delegate makes "
            f"an unguarded API call, got: {findings}"
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE32_auto_on_guard_missing'), (
            f"RULE32 two-hop finding must carry severity='FAIL'; got: {findings}"
        )

    def test_two_hop_guard_on_path_passes(self):
        """
        Must-not-catch: a 2-hop chain where the intermediate hop (helperA)
        carries ensureSwitchOn() before delegating to helperB.  The guard on the
        path powers the device on first, so helperB's API call is protected — no
        finding even though following the chain reaches the API-calling leaf.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_TWO_HOP_GUARD_ON_PATH)
        assert not any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"Guard on the intermediate delegation hop must protect the leaf, got: {findings}"
        )

    def test_two_hop_guard_on_leaf_passes(self):
        """
        Must-not-catch: a 2-hop chain where the API-calling leaf (helperB) guards
        itself with ensureSwitchOn().  No finding.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.GOOD_TWO_HOP_GUARD_ON_LEAF)
        assert not any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            f"API-calling leaf guarding itself must not flag RULE32, got: {findings}"
        )

    def test_delegation_cycle_terminates_and_flags(self):
        """
        Cycle-safety: a delegation cycle (setSpeed → helperA → helperB → handleA
        → helperA) with an unguarded API call in helperB must terminate (the
        visited-set breaks the cycle) and still flag the unguarded call.  A
        non-terminating traversal would hang the test run.
        """
        from lint_rules.bp24_auto_on_guard_missing import check_rule32_auto_on_guard_missing
        findings = run_rule(check_rule32_auto_on_guard_missing, self.BAD_CYCLE_UNGUARDED)
        assert any(f['rule_id'] == 'RULE32_auto_on_guard_missing' for f in findings), (
            "Expected RULE32 for SHOULD-ON method reaching an unguarded API call "
            f"through a delegation cycle (and traversal must terminate), got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE39 -- CHANGELOG user-facing TMI lint
# ---------------------------------------------------------------------------

from lint_rules.changelog_tmi import check_rule39_changelog_tmi


class TestRule39ChangelogTmi:
    """
    RULE39: implementation-detail jargon must not appear in CHANGELOG.md
    user-facing version-section bullets (### Fixed / ### Changed / ### Added).

    Covers four jargon categories:
      (i)   Exception class names (NullPointerException, etc.)
      (ii)  Internal method names not in the public-command allowlist
      (iii) Tracker references (issue #N, PR #N)
      (iv)  Bare catalog labels (BP<N>, RULE<N>)

    Non-vacuity contracts:
      - must-catch tests FAIL if the rule predicate is disabled or narrowed.
      - must-not-catch tests FAIL if the rule over-fires on clean content.
      - The ### Internal exclusion test confirms the scope boundary.
      - The preamble exclusion test confirms the preamble is out of scope.

    Both-ways proof: orchestrator-owned.
    """

    @staticmethod
    def _run(src: str) -> list:
        """Invoke RULE39 against src as if it were CHANGELOG.md."""
        path = REPO_ROOT / 'CHANGELOG.md'
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        return check_rule39_changelog_tmi(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    @staticmethod
    def _run_nonchangelog(src: str) -> list:
        """Invoke RULE39 against src as a non-CHANGELOG file -- must return []."""
        path = REPO_ROOT / 'Drivers' / 'Levoit' / 'SomeDriver.groovy'
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        return check_rule39_changelog_tmi(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    # -----------------------------------------------------------------------
    # Must-catch: category (i) -- exception-class jargon
    # -----------------------------------------------------------------------

    def test_catches_exception_class_name(self):
        """
        NullPointerException in a user-facing Fixed bullet must flag RULE39.

        Non-vacuity: removing the _EXCEPTION_CLASS_RE scan loop returns [] here,
        making this test FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Command silently fails.** A NullPointerException in the polling loop caused no output.
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE39_changelog_tmi' for f in findings), (
            f"Expected RULE39_changelog_tmi for exception class name, got: {findings}"
        )
        assert any('NullPointerException' in f['title'] for f in findings
                   if f['rule_id'] == 'RULE39_changelog_tmi'), (
            f"Finding should name the matched exception class, got: {findings}"
        )

    def test_catches_groovy_cast_exception(self):
        """GroovyCastException in a user-facing bullet must flag RULE39."""
        src = textwrap.dedent("""\
            ## [2.5] - 2026-05-04

            ### Fixed

            - **Blank parameter silently fails.** GroovyCastException thrown on empty Rule Machine slot.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'GroovyCastException' in f['title']
            for f in findings
        ), f"Expected GroovyCastException finding, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-catch: category (ii) -- internal method-name jargon
    # -----------------------------------------------------------------------

    def test_catches_internal_method_name(self):
        """
        updateDevices() in a user-facing Fixed bullet must flag RULE39.

        Non-vacuity: removing the _METHOD_CALL_RE scan loop returns [] here.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Polling resumes after reboot.** Fixed a bug in updateDevices() that skipped re-registration.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'updateDevices()' in f['title']
            for f in findings
        ), f"Expected updateDevices() finding, got: {findings}"

    def test_catches_sendbypassrequest_method(self):
        """sendBypassRequest() is an internal method and must flag."""
        src = textwrap.dedent("""\
            ## [2.4] - 2026-05-01

            ### Fixed

            - **API call silently dropped.** sendBypassRequest() now retries on transient 500.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'sendBypassRequest()' in f['title']
            for f in findings
        ), f"Expected sendBypassRequest() finding, got: {findings}"

    def test_bare_requirenonemptyenum_in_fixed_section_flagged(self):
        """
        requireNonEmptyEnum (bare, no parens) in a user-facing Fixed bullet must flag RULE39.

        Non-vacuity: removing 'requireNonEmptyEnum' from _INTERNAL_JARGON_NAMES in
        changelog_tmi.py causes this test to FAIL (no RULE39 finding is emitted).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Blank string no longer slips past guard.** A new requireNonEmptyEnum guard rejects empty inputs.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'requireNonEmptyEnum' in f['title']
            for f in findings
        ), f"Expected requireNonEmptyEnum bare-name RULE39 finding, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-catch: category (iii) -- tracker references
    # -----------------------------------------------------------------------

    def test_catches_issue_hash_n(self):
        """
        issue #N in a user-facing bullet must flag RULE39.

        Non-vacuity: removing the _TRACKER_REF_RE scan loop returns [] here.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Changed

            - **Poll routing refactored** (issue #3 AC #3). Substring matching replaced.
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE39_changelog_tmi' for f in findings), (
            f"Expected RULE39_changelog_tmi for issue #N, got: {findings}"
        )

    def test_catches_pr_hash_n(self):
        """PR #N in a user-facing bullet must flag RULE39."""
        src = textwrap.dedent("""\
            ## [2.3] - 2026-04-28

            ### Added

            - **OasisMist 1000S support.** Based on PR #502 (OPEN/CHANGES_REQUESTED).
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE39_changelog_tmi' for f in findings), (
            f"Expected RULE39_changelog_tmi for PR #N, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-catch: category (iv) -- bare catalog labels
    # -----------------------------------------------------------------------

    def test_catches_bp_label(self):
        """
        BP22 in a user-facing bullet must flag RULE39.

        Non-vacuity: removing the _CATALOG_LABEL_RE scan loop returns [] here.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Network outage no longer spams ERROR log (BP22).** One-time WARN is logged instead.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'BP22' in f['title']
            for f in findings
        ), f"Expected BP22 catalog label finding, got: {findings}"

    def test_catches_rule_label(self):
        """RULE37 in a user-facing bullet must flag RULE39."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Blank number input now falls back safely.** Enforced by RULE37 going forward.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'RULE37' in f['title']
            for f in findings
        ), f"Expected RULE37 catalog label finding, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-not-catch: public command allowlist
    # -----------------------------------------------------------------------

    def test_setlevel_is_allowed(self):
        """
        setLevel() is a public driver command and must NOT flag RULE39.

        Non-vacuity: removing 'setLevel' from _PUBLIC_COMMANDS causes this test
        to FAIL (the rule would flag it as an internal method name).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **setLevel(null) from Rule Machine no longer silently fails.**
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'setLevel()' in f['title']
            for f in findings
        ), f"setLevel() is a public command and must not flag, got: {findings}"

    def test_setmistlevel_is_allowed(self):
        """setMistLevel() is a public driver command and must NOT flag RULE39."""
        src = textwrap.dedent("""\
            ## [2.5] - 2026-05-04

            ### Fixed

            - **setMistLevel(0) now turns the device off.**
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'setMistLevel()' in f['title']
            for f in findings
        ), f"setMistLevel() is a public command and must not flag, got: {findings}"

    def test_clean_bullet_passes(self):
        """A plain-language user-facing bullet with no jargon must not flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Polling resumes after a hub reboot.** The poll schedule now persists across reboots.
            - **Network outage no longer spams the error log.** One warning is logged when the outage begins.
        """)
        findings = self._run(src)
        assert not any(f['rule_id'] == 'RULE39_changelog_tmi' for f in findings), (
            f"Clean bullets must not flag RULE39, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: ### Internal exclusion
    # -----------------------------------------------------------------------

    def test_internal_section_excluded(self):
        """
        Exception class names and method names in ### Internal bullets must NOT flag.

        Non-vacuity: removing the _INTERNAL_HEADER exclusion causes this test
        to FAIL (both internal items would be flagged).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Internal

            - Lint rule RULE37 catches NullPointerException-class coercions. sendBypassRequest() retries on 500.
        """)
        findings = self._run(src)
        assert not any(f['rule_id'] == 'RULE39_changelog_tmi' for f in findings), (
            f"### Internal bullets must not trigger RULE39, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: preamble exclusion
    # -----------------------------------------------------------------------

    def test_preamble_excluded(self):
        """
        Content before the first ## [ line must not flag, even if it contains jargon.

        Non-vacuity: removing the in_version_section guard causes this test
        to FAIL (the preamble line would be flagged).
        """
        src = textwrap.dedent("""\
            # Changelog

            This tracks NullPointerException fixes and RULE37 enforcement.

            ## [Unreleased]

            ### Fixed

            - **Plain-language bullet.** No jargon here.
        """)
        findings = self._run(src)
        # Should not flag the preamble line
        flagged_lines = [f['line'] for f in findings if f['rule_id'] == 'RULE39_changelog_tmi']
        preamble_line = 3  # "This tracks NullPointerException fixes..."
        assert preamble_line not in flagged_lines, (
            f"Preamble line {preamble_line} must not flag RULE39, got findings at lines: {flagged_lines}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: non-CHANGELOG file
    # -----------------------------------------------------------------------

    def test_non_changelog_file_not_checked(self):
        """
        RULE39 must return [] for any file that is not named CHANGELOG.md.

        Non-vacuity: removing the path.name != 'CHANGELOG.md' gate causes this
        test to FAIL when exception class names appear in non-changelog files.
        """
        src = "- **NullPointerException fix (BP22, RULE37).** issue #3 sendBypassRequest()\n"
        findings = self._run_nonchangelog(src)
        assert findings == [], (
            f"RULE39 must not fire on non-CHANGELOG files, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: additional public-command allowlist entries (NIT 2)
    # -----------------------------------------------------------------------

    def test_configure_is_allowed(self):
        """
        configure() is a Hubitat Configuration-capability command and must NOT
        flag RULE39.

        Non-vacuity: removing 'configure' from _PUBLIC_COMMANDS causes this test
        to FAIL (the rule would flag it as an internal method name).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **configure() no longer throws on first run.** The command now initialises state before polling.
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'configure()' in f['title']
            for f in findings
        ), f"configure() is a public command and must not flag, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-catch: category (iv) broadened -- BP#N / RULE#N hash forms
    # -----------------------------------------------------------------------

    def test_bp_hash_form_in_fixed_section_flagged(self):
        """
        BP#1, BP#26, RULE#37, RULE#39 in user-facing bullets must flag RULE39.

        Non-vacuity: reverting _CATALOG_LABEL_RE to r'\\b(BP\\d+|RULE\\d+)\\b'
        (removing optional '#') causes this test to FAIL -- the hash forms are
        no longer matched.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Blank parameter no longer silently fails (BP#26).** Safe fallback applied.
            - **Two-arg update signature enforced (BP#1).** Polling no longer throws.
            - **Coercion enforced by RULE#37 going forward.** No behavior change.
            - **Scope tracked by RULE#39.** No behavior change.
        """)
        findings = self._run(src)
        flagged_labels = {
            f['title'].split("'")[1]
            for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'catalog label' in f['title']
        }
        for label in ('BP#26', 'BP#1', 'RULE#37', 'RULE#39'):
            assert label in flagged_labels, (
                f"Expected catalog label {label!r} to flag RULE39, got flagged: {flagged_labels}"
            )

    # -----------------------------------------------------------------------
    # Must-not-catch: BREAKING keyword is not a catalog label
    # -----------------------------------------------------------------------

    def test_breaking_not_flagged_as_catalog_label(self):
        """
        BREAKING in a user-facing bullet must NOT be flagged as a catalog label.

        Non-vacuity: adding r'\\bBREAKING\\b' to _CATALOG_LABEL_RE would cause
        this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **BREAKING (Core 300S/400S/600S):** Some user-facing prose.
        """)
        findings = self._run(src)
        catalog_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'catalog label' in f['title']
        ]
        assert not catalog_findings, (
            f"BREAKING must not be flagged as a catalog label, got: {catalog_findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: non-digit suffixes are not catalog labels
    # -----------------------------------------------------------------------

    def test_bp_no_digit_after_prefix_not_flagged(self):
        """
        BP, BPx, BP123BC, RULEa1, RULEx -- forms with no digit-only suffix
        immediately after BP/RULE -- must NOT flag as catalog labels.

        Non-vacuity: a broad regex like r'\\bBP\\w+\\b' would flag BPx and
        BP123BC, making this test FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **BP tracking discipline improved.** BPx notation is avoided; BP123BC is not a real label. RULEa1 and RULEx are similarly unrelated tokens. This BP-style sentence has no trailing digit sequence.
        """)
        findings = self._run(src)
        catalog_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'catalog label' in f['title']
        ]
        assert not catalog_findings, (
            f"Non-digit-suffix BP/RULE tokens must not flag as catalog labels, got: {catalog_findings}"
        )

    # -----------------------------------------------------------------------
    # Must-catch: bare applyStatus / sendBypassRequest (no parens)
    # -----------------------------------------------------------------------

    def test_bare_applystatus_in_fixed_section_flagged(self):
        """
        Bare 'applyStatus' (no parens) in a user-facing Fixed bullet must flag RULE39.

        Non-vacuity: removing 'applyStatus' from _INTERNAL_JARGON_NAMES causes
        this test to FAIL (the bare form is no longer matched).
        """
        src = textwrap.dedent("""\
            ## [2.2] - 2026-04-27

            ### Added

            - **Spock unit-test spec** for LV600S -- happy-path applyStatus from fixture plus field assertions.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'applyStatus' in f['title']
            for f in findings
        ), f"Expected bare 'applyStatus' to flag RULE39, got: {findings}"

    def test_bare_sendbypassrequest_in_added_section_flagged(self):
        """
        Bare 'sendBypassRequest' (no parens) in a user-facing Added bullet must flag.

        Non-vacuity: removing 'sendBypassRequest' from _INTERNAL_JARGON_NAMES
        causes this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **API call retry added.** The sendBypassRequest path now retries once on 500.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'sendBypassRequest' in f['title']
            for f in findings
        ), f"Expected bare 'sendBypassRequest' to flag RULE39, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-not-catch: plain English words that superficially resemble internals
    # -----------------------------------------------------------------------

    def test_english_updated_not_flagged(self):
        """
        The English verb 'updated' in a bullet must NOT flag as internal jargon.

        Non-vacuity: adding 'update' or 'updated' to _INTERNAL_JARGON_NAMES
        would cause this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Changed

            - **Polling schedule updated** to use a persistent cron.
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'jargon' in f['title'].lower()
               and 'updated' in f['title']
        ]
        assert not jargon_findings, (
            f"English 'updated' must not flag as internal jargon, got: {jargon_findings}"
        )

    def test_english_installed_not_flagged(self):
        """'installed' as English verb must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - **Levoit Dual 200S Humidifier driver installed** via HPM.
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'installed' in f['title']
        ]
        assert not jargon_findings, (
            f"English 'installed' must not flag as internal jargon, got: {jargon_findings}"
        )

    def test_english_configure_not_flagged(self):
        """'configure' as English verb (no parens) must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **No need to configure the parent after upgrading.**
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'configure' in f['title']
               and 'jargon' in f['title'].lower()
        ]
        assert not jargon_findings, (
            f"English 'configure' (bare, no parens) must not flag, got: {jargon_findings}"
        )

    def test_applyStatus_with_parens_still_flagged_via_method_call_re(self):
        """
        applyStatus() WITH parens must still flag via _METHOD_CALL_RE path.

        This preserves the existing parens-required predicate; the bare-name
        path is additive, not a replacement.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Status parsing improved.** The applyStatus() function now handles missing fields.
        """)
        findings = self._run(src)
        assert any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'applyStatus()' in f['title']
            for f in findings
        ), f"Expected applyStatus() (with parens) to flag RULE39, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-not-catch: forceReinitialize / resetAllChildren / resyncEquipment /
    #                 probeNightLight are public parent-driver commands
    # -----------------------------------------------------------------------

    def test_forceReinitialize_is_allowed(self):
        """
        forceReinitialize() is a public parent-driver command and must NOT flag.

        Non-vacuity: removing 'forceReinitialize' from _PUBLIC_COMMANDS causes
        this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Run forceReinitialize() on the parent device after upgrading** to re-arm the poll schedule.
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'forceReinitialize()' in f['title']
            for f in findings
        ), f"forceReinitialize() is a public command and must not flag, got: {findings}"

    def test_resetAllChildren_is_allowed(self):
        """resetAllChildren() is a public parent-driver command and must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Use resetAllChildren() to re-create child devices** if they show stale state.
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'resetAllChildren()' in f['title']
            for f in findings
        ), f"resetAllChildren() is a public command and must not flag, got: {findings}"

    def test_resyncEquipment_is_allowed(self):
        """resyncEquipment() is a public parent-driver command and must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - **Run resyncEquipment() from the parent device** to refresh all child configModule assignments.
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'resyncEquipment()' in f['title']
            for f in findings
        ), f"resyncEquipment() is a public command and must not flag, got: {findings}"

    def test_probeNightLight_is_allowed(self):
        """probeNightLight() is a public parent-driver command and must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - **Run probeNightLight() from the parent device** to detect nightlight hardware presence.
        """)
        findings = self._run(src)
        assert not any(
            f['rule_id'] == 'RULE39_changelog_tmi' and 'probeNightLight()' in f['title']
            for f in findings
        ), f"probeNightLight() is a public command and must not flag, got: {findings}"

    # -----------------------------------------------------------------------
    # Must-catch: section-transition -- user-facing scope re-entered after Internal
    # -----------------------------------------------------------------------

    def test_user_facing_reentered_after_internal(self):
        """
        After a ### Internal subsection, a subsequent ### Changed section must
        re-enter user-facing scope, and jargon in that ### Changed section must
        flag RULE39.

        Non-vacuity: removing the '### changed' entry from _USER_FACING_HEADERS
        would cause this test to FAIL (### Changed would not re-enter scope and
        the Exception class in it would not be flagged).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - **Some good user-facing fix.** No jargon here.

            ### Internal

            - **safeIntArg() refactored.** Internal implementation change, intentional jargon.

            ### Changed

            - **NullPointerException no longer thrown on blank input.** Exceptions were silently swallowed.
        """)
        findings = self._run(src)
        # The ### Internal bullet must NOT flag
        internal_flags = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and f['line'] == 9
        ]
        assert not internal_flags, (
            f"### Internal bullet must not flag RULE39, got: {internal_flags}"
        )
        # The ### Changed bullet MUST flag for NullPointerException
        changed_flags = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'NullPointerException' in f['title']
        ]
        assert changed_flags, (
            f"NullPointerException in ### Changed (after ### Internal) must flag RULE39, "
            f"got findings: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch: names deliberately excluded from _INTERNAL_JARGON_NAMES
    # peelEnvelope / seedPrefs / handleEvent.  Excluded under the reactive-grow
    # policy: peelEnvelope and seedPrefs have zero codebase presence (anticipatory
    # inclusions are not eligible per docstring policy); handleEvent is also
    # Hubitat platform vocabulary (lifecycle callback name) so the bare-name
    # path could over-suppress legitimate user-facing prose.  Parens-form
    # `handleEvent()` is still flagged via _METHOD_CALL_RE.  These tests lock
    # the exclusion: re-adding any of these three names to the frozenset
    # causes the corresponding test to FAIL.
    # -----------------------------------------------------------------------

    def test_bare_peelenvelope_not_flagged(self):
        """
        'peelEnvelope' bare (no parens) in a user-facing bullet must NOT flag RULE39.

        Non-vacuity: re-adding 'peelEnvelope' to _INTERNAL_JARGON_NAMES causes
        this test to FAIL (the bare-name path then matches).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - The peelEnvelope helper now skips empty results.
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'peelEnvelope' in f['title']
        ]
        assert not jargon_findings, (
            f"'peelEnvelope' is NOT in _INTERNAL_JARGON_NAMES and must not flag, got: {jargon_findings}"
        )

    def test_bare_seedprefs_not_flagged(self):
        """
        'seedPrefs' bare (no parens) in a user-facing bullet must NOT flag RULE39.

        Non-vacuity: re-adding 'seedPrefs' to _INTERNAL_JARGON_NAMES causes
        this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - seedPrefs runs once per device lifecycle.
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'seedPrefs' in f['title']
        ]
        assert not jargon_findings, (
            f"'seedPrefs' is NOT in _INTERNAL_JARGON_NAMES and must not flag, got: {jargon_findings}"
        )

    def test_bare_handleevent_not_flagged(self):
        """
        'handleEvent' bare (no parens) in a user-facing bullet must NOT flag RULE39.

        This exact phrasing is the 'Hubitat platform vocabulary' usage the WARN-2
        removal protects: a user-facing note about the lifecycle callback is NOT
        jargon-dump.  The parens-form handleEvent() is still flagged via
        _METHOD_CALL_RE (analogous shape to test_applyStatus_with_parens_still_flagged_via_method_call_re).

        Non-vacuity: re-adding 'handleEvent' to _INTERNAL_JARGON_NAMES causes
        this test to FAIL.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - The driver's handleEvent lifecycle callback now registers on install.
        """)
        findings = self._run(src)
        jargon_findings = [
            f for f in findings
            if f['rule_id'] == 'RULE39_changelog_tmi' and 'handleEvent' in f['title']
               and 'jargon' in f['title'].lower()
        ]
        assert not jargon_findings, (
            f"'handleEvent' (bare, no parens) is NOT in _INTERNAL_JARGON_NAMES and must not flag, "
            f"got: {jargon_findings}"
        )


# ---------------------------------------------------------------------------
# RULE41 -- internal device-name shorthand leak into user-facing prose
# ---------------------------------------------------------------------------

from lint_rules.device_shorthand_leak import check_rule41_device_shorthand_leak


class TestRule41DeviceShorthandLeak:
    """
    RULE41: internal device-name shorthands (Sup6000S, OM1000S, Core200S, ...)
    must not appear in user-facing prose (levoitManifest.json releaseNotes,
    CHANGELOG.md, Drivers/Levoit/readme.md).

    Non-vacuity contracts:
      - must-catch tests FAIL if the denylist entry is removed or the scan is
        disabled.
      - must-not-catch tests FAIL if the rule over-fires on public names, real
        hardware model codes, or out-of-scope dev-doc paths.

    Both-ways proof: orchestrator-owned.
    """

    @staticmethod
    def _run(src: str, basename: str = 'CHANGELOG.md', parent: str = None) -> list:
        """Invoke RULE41 against src as a named in-scope user-facing file."""
        if basename == 'readme.md':
            path = REPO_ROOT / 'Drivers' / 'Levoit' / 'readme.md'
        elif basename == 'levoitManifest.json':
            path = REPO_ROOT / 'levoitManifest.json'
        else:
            path = REPO_ROOT / basename
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        return check_rule41_device_shorthand_leak(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    @staticmethod
    def _run_at_path(src: str, path: Path) -> list:
        """Invoke RULE41 against src at an arbitrary path (for out-of-scope tests)."""
        raw_lines = src.splitlines()
        from lint_rules.groovy_lite import clean_source
        _, cleaned_lines = clean_source(src)
        return check_rule41_device_shorthand_leak(
            path=path,
            raw_lines=raw_lines,
            cleaned_lines=cleaned_lines,
            raw_text=src,
            config={},
            rel_base=REPO_ROOT,
        )

    # -----------------------------------------------------------------------
    # Must-catch
    # -----------------------------------------------------------------------

    def test_catches_sup6000s_in_releasenotes_shaped_string(self):
        """
        'Sup6000S' in the manifest releaseNotes value must flag RULE41 with the
        'Superior 6000S' suggestion. This is the confirmed v2.6 leak.

        Non-vacuity: removing the 'Sup6000S' denylist entry returns [] here.
        """
        src = '  "releaseNotes": "2.6 - Fixes Sup6000S setDisplay in sleep mode.",'
        findings = self._run(src, basename='levoitManifest.json')
        matches = [f for f in findings if f['rule_id'] == 'RULE41_device_shorthand_leak'
                   and 'Sup6000S' in f['title']]
        assert matches, f"Expected Sup6000S RULE41 finding, got: {findings}"
        assert 'Superior 6000S' in matches[0]['title'], (
            f"Finding should suggest the public name 'Superior 6000S', got: {matches[0]['title']}"
        )

    def test_catches_om1000s_in_changelog(self):
        """'OM1000S' in a CHANGELOG user-facing subsection must flag with 'OasisMist 1000S'."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - Fixes OM1000S mist level reporting.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        matches = [f for f in findings if f['rule_id'] == 'RULE41_device_shorthand_leak'
                   and 'OM1000S' in f['title']]
        assert matches, f"Expected OM1000S RULE41 finding, got: {findings}"
        assert 'OasisMist 1000S' in matches[0]['title'], (
            f"Should suggest 'OasisMist 1000S', got: {matches[0]['title']}"
        )

    def test_catches_om450s_in_readme(self):
        """'OM450S' contraction in bare readme prose must flag with 'OasisMist 450S'."""
        src = "OM450S now reports humidity."
        findings = self._run(src, basename='readme.md')
        assert any(f['rule_id'] == 'RULE41_device_shorthand_leak' and 'OM450S' in f['title']
                   for f in findings), f"Expected OM450S finding, got: {findings}"

    def test_catches_multiple_shorthands_one_line(self):
        """Two contraction shorthands on one line produce two findings."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - Sup6000S and OM1000S now share the mist-level logic.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        names = {f['title'].split("'")[1] for f in findings
                 if f['rule_id'] == 'RULE41_device_shorthand_leak'}
        assert 'Sup6000S' in names and 'OM1000S' in names, (
            f"Expected both Sup6000S and OM1000S, got: {names}"
        )

    def test_catches_lv600shc_abbreviation(self):
        """'LV600SHC' must flag with 'LV600S Hub Connect'."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - LV600SHC mist fix.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert any('LV600S Hub Connect' in f['title'] for f in findings
                   if f['rule_id'] == 'RULE41_device_shorthand_leak'), (
            f"Expected LV600S Hub Connect suggestion, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch
    # -----------------------------------------------------------------------

    def test_does_not_catch_public_superior_name(self):
        """The public 'Superior 6000S' (with space) must NOT flag."""
        src = '  "releaseNotes": "2.6 - Fixes Superior 6000S setDisplay in sleep mode.",'
        findings = self._run(src, basename='levoitManifest.json')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Public name 'Superior 6000S' must not flag, got: {findings}"
        )

    def test_does_not_catch_real_model_code(self):
        """A real hardware model code 'LEH-S601S-WUS' must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - Adds support for LEH-S601S-WUS regional variant.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Real model code must not flag, got: {findings}"
        )

    def test_does_not_catch_public_oasismist_with_space(self):
        """The public 'OasisMist 1000S' (with space) must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - OasisMist 1000S mist fix.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Public 'OasisMist 1000S' must not flag, got: {findings}"
        )

    def test_does_not_catch_lv600s_public_name(self):
        """'LV600S' alone IS the public name (not a leak) -- must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - LV600S mist level fix.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"'LV600S' is the public name and must not flag, got: {findings}"
        )

    def test_does_not_catch_core_family_public_no_space_name(self):
        """
        'Core200S'/'Core600S' (no space) ARE the manifest public names, NOT
        leaks -- must NOT flag. This is the authoritative-derivation correction:
        the Core family genuinely uses the no-space form in driver metadata.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Fixed

            - Core200S and Core600S now report PM2.5 on dashboards.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Core family no-space form is the public name and must not flag, got: {findings}"
        )

    def test_does_not_catch_manifest_non_releasenotes_line(self):
        """
        A manifest line that is NOT the releaseNotes value is out of scope, even
        if it contains a contraction -- only the releaseNotes value is scanned.
        Uses a contraction (Sup6000S) so the scope gate is genuinely exercised:
        if the rule scanned all manifest lines, this WOULD flag.
        """
        src = '      "description": "alias Sup6000S for the Superior 6000S driver",'
        findings = self._run(src, basename='levoitManifest.json')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Non-releaseNotes manifest line is out of scope, got: {findings}"
        )

    def test_does_not_catch_real_vesync_device_type_literal(self):
        """
        'Dual200S' / 'Classic200S' / 'Vital200S' are real VeSync device-type
        literals the cloud API reports verbatim -- they legitimately appear in
        technical user-facing prose and must NOT flag (they are NOT contractions).
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - Levoit Dual 200S Humidifier driver (Dual200S literal device type);
              Classic200S and Vital200S routing unchanged.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Real VeSync device-type literals must not flag, got: {findings}"
        )

    def test_does_not_catch_backticked_contraction(self):
        """
        A backticked contraction (`` `Sup6000S` ``) is suppressed as a code
        reference (defense in depth) -- must NOT flag.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - Internal device-type alias `Sup6000S` documented for contributors.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Backticked contraction must not flag, got: {findings}"
        )

    def test_does_not_catch_contraction_filename(self):
        """A '*.yaml' filename context suppresses a contraction match -- must NOT flag."""
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Added

            - Vendored fixture sourced from upstream Sup6000S.yaml.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Filename context must not flag, got: {findings}"
        )

    def test_does_not_catch_changelog_internal_subsection(self):
        """
        A contraction in the CHANGELOG ### Internal subsection is OUT of scope --
        implementation detail belongs there; must NOT flag.
        """
        src = textwrap.dedent("""\
            ## [Unreleased]

            ### Internal

            - Sup6000S router wiring refactor; no user-visible change.
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"### Internal subsection is out of scope, got: {findings}"
        )

    def test_does_not_catch_changelog_preamble(self):
        """
        A contraction in the top-of-file CHANGELOG preamble (before the first
        ``## [`` section) is OUT of scope -- must NOT flag.
        """
        src = textwrap.dedent("""\
            # Changelog

            Notes for maintainers: Sup6000S is the dev shorthand we avoid in prose.

            ## [Unreleased]
        """)
        findings = self._run(src, basename='CHANGELOG.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"CHANGELOG preamble is out of scope, got: {findings}"
        )

    def test_out_of_scope_dev_doc_path_not_scanned(self):
        """
        The shorthand 'Sup6000S' appearing in a dev-doc path (CLAUDE.md) is
        OUT of scope and must NOT flag -- internal shorthand is fine there.
        """
        src = "BP25 fix re-blessed; Sup6000S was the v2.6 leak example."
        findings = self._run_at_path(src, REPO_ROOT / 'CLAUDE.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Dev-doc CLAUDE.md is out of scope and must not flag, got: {findings}"
        )

    def test_out_of_scope_roadmap_not_scanned(self):
        """ROADMAP.md is dev-facing -- out of scope, must NOT flag."""
        src = "Future: OM450S regional variants under evaluation."
        findings = self._run_at_path(src, REPO_ROOT / 'ROADMAP.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"ROADMAP.md is out of scope and must not flag, got: {findings}"
        )

    def test_out_of_scope_stray_readme_not_scanned(self):
        """
        A readme.md outside Drivers/Levoit/ must NOT be scanned. Uses a
        contraction (Sup6000S) so the parent-dir scope gate is genuinely
        exercised: the same content under Drivers/Levoit/readme.md WOULD flag.
        """
        src = "Sup6000S example."
        findings = self._run_at_path(src, REPO_ROOT / 'readme.md')
        assert not any(f['rule_id'] == 'RULE41_device_shorthand_leak' for f in findings), (
            f"Stray top-level readme.md is out of scope, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE42 — Malformed capability name (embedded space)
# ---------------------------------------------------------------------------

class TestRule42MalformedCapability:
    """
    RULE42: a `capability "..."` declaration MUST NOT contain a space in the
    capability name. Hubitat capability identifiers are single CamelCase tokens
    (e.g. "SwitchLevel"); an embedded space (e.g. "Switch Level") is silently
    ignored by the platform, so the capability fails to register with no error.
    """

    from lint_rules.malformed_capability import check_rule42_malformed_capability as _rule

    # MUST-CATCH: the reported typo — "Switch Level" (two words).
    BAD_SWITCH_LEVEL = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Core200S Air Purifier Light") {
                capability "Switch"
                capability "Switch Level"
            }
        }
    """)

    # MUST-CATCH: a different multi-word capability typo, to prove the rule is
    # class-wide (any embedded space), not pinned to the one reported instance.
    BAD_FAN_CONTROL = textwrap.dedent("""\
        metadata {
            definition(name: "Some Fan") {
                capability "Fan Control"
            }
        }
    """)

    # MUST-NOT-CATCH: the canonical one-word form.
    GOOD_SWITCH_LEVEL = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Core 200S Air Purifier") {
                capability "Switch"
                capability "SwitchLevel"
                capability "FanControl"
            }
        }
    """)

    # MUST-NOT-CATCH: a normal string literal with a space that is NOT a
    # capability declaration (proves the rule is anchored to `capability`).
    GOOD_OTHER_STRING = textwrap.dedent("""\
        metadata {
            definition(name: "Levoit Pedestal Fan", description: "fan speed 1-12") {
                capability "SwitchLevel"
                command "setMode", [[name:"Mode*", type:"ENUM"]]
            }
        }
    """)

    def test_switch_level_typo_fails(self):
        from lint_rules.malformed_capability import check_rule42_malformed_capability
        findings = run_rule(check_rule42_malformed_capability, self.BAD_SWITCH_LEVEL)
        assert any(f['rule_id'] == 'RULE42_malformed_capability' for f in findings), (
            f'Expected RULE42 for `capability "Switch Level"`, got: {findings}'
        )
        assert any(f.get('severity') == 'FAIL' for f in findings
                   if f.get('rule_id') == 'RULE42_malformed_capability'), (
            f"RULE42 finding must carry severity='FAIL' to gate lint --strict; got: {findings}"
        )

    def test_fan_control_typo_fails(self):
        """Class-wide: any embedded-space capability name flags, not just the reported one."""
        from lint_rules.malformed_capability import check_rule42_malformed_capability
        findings = run_rule(check_rule42_malformed_capability, self.BAD_FAN_CONTROL)
        assert any(f['rule_id'] == 'RULE42_malformed_capability' for f in findings), (
            f'Expected RULE42 for `capability "Fan Control"`, got: {findings}'
        )

    def test_canonical_one_word_passes(self):
        from lint_rules.malformed_capability import check_rule42_malformed_capability
        findings = run_rule(check_rule42_malformed_capability, self.GOOD_SWITCH_LEVEL)
        assert not any(f['rule_id'] == 'RULE42_malformed_capability' for f in findings), (
            f"Canonical one-word capability names must not flag RULE42, got: {findings}"
        )

    def test_non_capability_string_with_space_passes(self):
        """A space in a non-capability string literal must NOT flag (anchored to `capability`)."""
        from lint_rules.malformed_capability import check_rule42_malformed_capability
        findings = run_rule(check_rule42_malformed_capability, self.GOOD_OTHER_STRING)
        assert not any(f['rule_id'] == 'RULE42_malformed_capability' for f in findings), (
            f"Non-capability string with a space must not flag RULE42, got: {findings}"
        )


# ---------------------------------------------------------------------------
# RULE43 — recordError ctx-map key style (site: -> method:)
# ---------------------------------------------------------------------------

from lint_rules.recordError_key_style import check_rule43_recordError_key_style


class TestRule43RecordErrorKeyStyle:
    """
    RULE43: recordError ctx maps must use the canonical 'method:' key, never 'site:'.

    Non-vacuity contracts:
      - must-catch tests FAIL if the rule predicate is disabled or narrowed
        (the rule returns [] and the `any(...)` assertion fails).
      - must-not-catch tests FAIL if the rule over-fires on canonical 'method:'
        content or on a bare 'site:' outside a recordError call.

    Both-ways proof: orchestrator-owned.
    """

    @staticmethod
    def _run(src: str) -> list:
        """Invoke RULE43 against src as a .groovy file via the shared run_rule helper."""
        return run_rule(check_rule43_recordError_key_style, src, fname="TestDriver.groovy")

    # -----------------------------------------------------------------------
    # Must-catch
    # -----------------------------------------------------------------------

    def test_catches_site_key(self):
        """A recordError call with a [site:...] ctx map must flag RULE43."""
        src = textwrap.dedent("""\
            def setMode(mode) {
                recordError("Mode write failed", [site:"setMode"])
            }
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Expected RULE43 for [site:...] recordError ctx, got: {findings}"
        )

    def test_catches_site_key_with_other_keys_preserved(self):
        """
        The 'site:' key flags even when other keys (value:) are present —
        the rename target is the 'site' key only, not the whole map.
        """
        src = textwrap.dedent("""\
            def setMode(mode) {
                recordError("Mode write failed", [site:"setMode", value:requestedMode])
            }
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Expected RULE43 for [site:..., value:...] recordError ctx, got: {findings}"
        )

    def test_catches_site_key_with_trailing_dni_arg(self):
        """A 3-arg recordError(msg, [site:...], dni) still flags on the site key."""
        src = textwrap.dedent("""\
            def updateDevices() {
                recordError("No status returned", [site:"updateDevices"], dni)
            }
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Expected RULE43 for 3-arg recordError with [site:...] ctx, got: {findings}"
        )

    def test_catches_multiline_site_key(self):
        """
        A recordError call whose ctx map wraps onto a continuation line must
        flag — the parent app's auth-flow calls use this shape.

        Non-vacuity: a single-line-only predicate returns [] here, FAILing this
        test.  Guards the multi-line awareness of _record_error_spans.
        """
        src = textwrap.dedent("""\
            def getAuthorizationCode() {
                recordError("getAuthorizationCode: cross-region at Stage 1",
                            [site:"getAuthorizationCode"])
            }
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Expected RULE43 for multi-line recordError with [site:...] ctx, got: {findings}"
        )

    def test_catches_multiline_site_key_with_paren_in_message(self):
        """
        A recordError call whose message contains parentheses AND whose ctx map
        wraps onto a continuation line must still flag — paren-balancing must not
        terminate the span early on a paren inside the message string.
        """
        src = textwrap.dedent("""\
            def exchangeAuthCode() {
                recordError("exchangeAuthCode: inner failure (code=${innerCode})",
                            [site:"exchangeAuthCode"])
            }
        """)
        findings = self._run(src)
        assert any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Expected RULE43 for multi-line recordError with paren-in-message, got: {findings}"
        )

    # -----------------------------------------------------------------------
    # Must-not-catch
    # -----------------------------------------------------------------------

    def test_method_key_passes(self):
        """The canonical 'method:' ctx key must NOT flag RULE43."""
        src = textwrap.dedent("""\
            def setMode(mode) {
                recordError("Mode write failed", [method:"setMode", value:requestedMode])
            }
        """)
        findings = self._run(src)
        assert not any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Canonical [method:...] recordError ctx must not flag RULE43, got: {findings}"
        )

    def test_bare_site_outside_recordError_passes(self):
        """
        A bare 'site:' map key NOT inside a recordError call must NOT flag —
        the rule requires the recordError( co-occurrence.
        """
        src = textwrap.dedent("""\
            def buildPayload() {
                def cfg = [site:"home", region:"us"]
                return cfg
            }
        """)
        findings = self._run(src)
        assert not any(f['rule_id'] == 'RULE43_recordError_key_style' for f in findings), (
            f"Bare [site:...] outside recordError must not flag RULE43, got: {findings}"
        )
