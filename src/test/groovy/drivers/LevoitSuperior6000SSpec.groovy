package drivers

import spock.lang.Unroll
import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitSuperior6000S.groovy (Levoit Superior 6000S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  — double-wrap envelope peel (humidifier shape, two result layers)
 *   Bug Pattern #8  — dryingMode enum: 0=off/idle, 1=active, 2=complete (NOT boolean)
 *   Bug Pattern #12 — pref-seed fires when descriptionTextEnable is null
 *   Happy path      — full applyStatus from LEH-S601S.yaml emits expected events
 *   State-change gating — lastWater null fires INFO; unchanged water does not
 */
class LevoitSuperior6000SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitSuperior6000S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a double-wrapped humidifier status (device on, auto mode)"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        // Superior 6000S: parent passes resp.data.result which is double-wrapped
        def status = humidifierStatusEnvelope(deviceData)

        when:
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: double-wrap envelope peel
    // -------------------------------------------------------------------------

    def "applyStatus peels double-wrap envelope to reach device fields (Bug Pattern #3)"() {
        given: "status in double-wrapped humidifier shape"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        // humidifierStatusEnvelope wraps as:
        //   { code:0, result: { code:0, result: {device fields}, traceId:... }, traceId:... }
        // The parent would pass resp.data.result to update(), so status = outer result:
        //   { code:0, result: { code:0, result: {device fields} } }
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached (humidity event proves it)"
        lastEventValue("humidity") == 45
        lastEventValue("switch") == "on"
    }

    def "applyStatus single-wrapped also works (guard against over-peel) (Bug Pattern #3)"() {
        given: "status in single-wrapped shape (as if someone calls from a purifier-like path)"
        def deviceData = [
            powerSwitch: 1,
            workMode: "manual",
            humidity: 55,
            targetHumidity: 60,
            mistLevel: 3,
            virtualLevel: 3,
            waterLacksState: 0,
            waterTankLifted: 0,
            autoStopSwitch: 0,
            autoStopState: 0,
            screenState: 1,
            screenSwitch: 1,
            filterLifePercent: 80,
            childLockSwitch: 0,
            temperature: 700,
            timerRemain: 0,
            dryingMode: [dryingState: 0, autoDryingSwitch: 0, dryingLevel: 1, dryingRemain: 0],
            waterPump: [cleanStatus: 0, remainTime: 0, totalTime: 3600]
        ]
        def status = v2StatusEnvelope(deviceData)  // single-wrap

        when:
        driver.applyStatus(status)

        then: "device fields reached even through single-wrap"
        lastEventValue("humidity") == 55
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #8: dryingMode enum 0/1/2 → off/active/complete (NOT boolean)
    // -------------------------------------------------------------------------

    def "dryingMode=0 with autoDryingSwitch=1 reports 'idle' (Bug Pattern #8)"() {
        given: "device on auto mode, dryingState=0, autoDryingSwitch=1"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        // device_on_auto: dryingMode.dryingState=0, autoDryingSwitch=1
        assert (deviceData.dryingMode as Map).dryingState == 0
        assert (deviceData.dryingMode as Map).autoDryingSwitch == 1
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "dryingMode attribute is 'idle' (enabled but not active)"
        lastEventValue("dryingMode") == "idle"
    }

    def "dryingMode=0 with autoDryingSwitch=0 reports 'off' (Bug Pattern #8)"() {
        given: "device on manual mode, dryingState=0, autoDryingSwitch=0"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_manual as Map
        // device_on_manual: dryingMode.dryingState=0, autoDryingSwitch=0
        assert (deviceData.dryingMode as Map).dryingState == 0
        assert (deviceData.dryingMode as Map).autoDryingSwitch == 0
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "dryingMode is 'off' (disabled)"
        lastEventValue("dryingMode") == "off"
    }

    def "dryingMode=1 reports 'active' (Bug Pattern #8)"() {
        given: "drying cycle is actively running (dryingState=1)"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_drying_active as Map
        assert (deviceData.dryingMode as Map).dryingState == 1
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "dryingMode is 'active'"
        lastEventValue("dryingMode") == "active"
    }

    def "dryingMode=2 reports 'complete' (Bug Pattern #8)"() {
        given: "drying cycle just finished (dryingState=2)"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_drying_complete as Map
        assert (deviceData.dryingMode as Map).dryingState == 2
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "dryingMode is 'complete'"
        lastEventValue("dryingMode") == "complete"
    }

    def "dryingMode is NOT a boolean — value is a String enum (Bug Pattern #8)"() {
        given: "any fixture with dryingMode"
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "dryingMode attribute is a String, not a boolean"
        def val = lastEventValue("dryingMode")
        val instanceof String
        val in ["off", "idle", "active", "complete"]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def status = humidifierStatusEnvelope(fixture.responses.device_on_auto as Map)

        when:
        driver.applyStatus(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def status = humidifierStatusEnvelope(fixture.responses.device_on_auto as Map)

        when:
        driver.applyStatus(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from fixture
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_auto fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is 'auto' (reverse-mapped from autoPro)"
        lastEventValue("mode") == "auto"

        and: "humidity is 45"
        lastEventValue("humidity") == 45

        and: "targetHumidity is 55"
        lastEventValue("targetHumidity") == 55

        and: "mistLevel is 3"
        lastEventValue("mistLevel") == 3

        and: "virtualLevel is 3"
        lastEventValue("virtualLevel") == 3

        and: "water is 'ok' (no lack, not lifted)"
        lastEventValue("water") == "ok"

        and: "displayOn is 'on' (screenState=1)"
        lastEventValue("displayOn") == "on"

        and: "childLock is 'off'"
        lastEventValue("childLock") == "off"

        and: "autoStopEnabled is 'on' (autoStopSwitch=1)"
        lastEventValue("autoStopEnabled") == "on"

        and: "temperature is populated"
        lastEventValue("temperature") != null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "autoPro workMode is reverse-mapped to 'auto' in mode attribute"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_on_auto as Map
        assert deviceData.workMode == "autoPro"  // fixture confirms raw API value
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'auto', not 'autoPro'"
        lastEventValue("mode") == "auto"
        lastEventValue("mode") != "autoPro"
    }

    // -------------------------------------------------------------------------
    // State-change gating for water status
    // -------------------------------------------------------------------------

    def "water status INFO logged when lastWater is null (first poll) (state-change gating)"() {
        given: "no prior water state"
        settings.descriptionTextEnable = true
        assert state.lastWater == null

        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_water_empty as Map
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "water is 'empty' and INFO was logged"
        lastEventValue("water") == "empty"
        testLog.infos.any { it.contains("Water") || it.contains("water") || it.contains("empty") }
    }

    def "water status INFO NOT logged on second poll when value unchanged (state-change gating)"() {
        given: "lastWater already set to 'empty' (prior poll)"
        settings.descriptionTextEnable = true
        state.lastWater = "empty"

        def fixture = loadYamlFixture("LEH-S601S.yaml")
        def deviceData = fixture.responses.device_water_empty as Map
        def status = humidifierStatusEnvelope(deviceData)
        int infosBefore = testLog.infos.size()

        when:
        driver.applyStatus(status)

        then: "no new water-related INFO (same value, no state change)"
        // New infos that contain water-related keywords
        def newInfos = testLog.infos.drop(infosBefore)
        !newInfos.any { it.toLowerCase().contains("water") }
    }

    def "water='removed' when waterTankLifted=1 (takes priority over waterLacksState)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1,
            workMode: "manual",
            humidity: 40,
            targetHumidity: 50,
            mistLevel: 0,
            virtualLevel: 3,
            waterLacksState: 1,   // also empty, but removed takes priority
            waterTankLifted: 1,
            autoStopSwitch: 0,
            autoStopState: 0,
            screenState: 1,
            screenSwitch: 1,
            filterLifePercent: 80,
            childLockSwitch: 0,
            temperature: 700,
            timerRemain: 0,
            dryingMode: [dryingState: 0, autoDryingSwitch: 0, dryingLevel: 1, dryingRemain: 0],
            waterPump: [cleanStatus: 0, remainTime: 0, totalTime: 3600]
        ]
        def status = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "water is 'removed' not 'empty'"
        lastEventValue("water") == "removed"
    }

    def "setMode sends autoPro (not 'auto') to the API for auto mode"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("auto")

        then: "setHumidityMode was called with workMode=autoPro"
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.workMode == "autoPro"
    }

    def "setMode sends 'manual' (not reverse-mapped) for manual mode"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("manual")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.workMode == "manual"
    }

    // -------------------------------------------------------------------------
    // Theme C parity: state.lastSwitchSet consistency (matches Classic 300S / V100S pattern)
    // -------------------------------------------------------------------------

    def "on() seeds state.lastSwitchSet='on' (Theme C parity)"() {
        when: "on() is called"
        driver.on()

        then: "lastSwitchSet is 'on'"
        state.lastSwitchSet == "on"
    }

    def "off() seeds state.lastSwitchSet='off' (Theme C parity)"() {
        when: "off() is called"
        driver.off()

        then: "lastSwitchSet is 'off'"
        state.lastSwitchSet == "off"
    }

    def "toggle() uses state.lastSwitchSet to avoid device.currentValue race (Theme C parity)"() {
        given: "state.lastSwitchSet seeded to 'on'"
        state.lastSwitchSet = "on"

        when:
        driver.toggle()

        then: "setSwitch with powerSwitch=0 was sent (off was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    def "toggle() falls back to device.currentValue('switch') when state not seeded (Theme C parity)"() {
        given: "state.lastSwitchSet not set; device.currentValue('switch') returns 'off'"
        assert state.lastSwitchSet == null
        testDevice.events.add([name: "switch", value: "off"])

        when: "toggle() falls back to currentValue"
        driver.toggle()

        then: "on was called (switch was off, so toggle goes to on)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
    }

    // ---- BP18: null-arg guard ----

    def "setMode(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver.setMode(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains("setMode") && it.contains("null") }
        testParent.allRequests.isEmpty()
    }

    // -------------------------------------------------------------------------
    // SwitchLevel 2-arg overload
    // -------------------------------------------------------------------------

    def "setLevel(50) 1-arg sends setVirtualLevel API call"() {
        // Baseline: confirm 1-arg setLevel routes to the mist-level API.
        given: "default state"
        settings.descriptionTextEnable = false

        when: "setLevel(50) is called -- maps to mist level ~5 out of 9"
        driver.setLevel(50)

        then: "setVirtualLevel API request was sent"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.containsKey("virtualLevel")
    }

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "default state"
        settings.descriptionTextEnable = false

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — setVirtualLevel request sent"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.containsKey("virtualLevel")
    }

    // -------------------------------------------------------------------------
    // BP24-B regression guard: setMistLevel from off-state triggers auto-on
    // Upgrades previous BP24-C partial guard to full ensureSwitchOn() pattern.
    // Superior 6000S V2-family payload: {powerSwitch:1, switchIdx:0}
    // This test MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "setMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [
            powerSwitch: 0, humidity: 45, targetHumidity: 55,
            mistLevel: 0, virtualLevel: 3,
            workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 0, autoStopState: 0,
            screenState: 0, screenSwitch: 0,
            filterLifePercent: 80, childLockSwitch: 0,
            temperature: 700, timerRemain: 0,
            dryingMode: [dryingState: 0, autoDryingSwitch: 0, dryingLevel: 1, dryingRemain: 0],
            waterPump: [cleanStatus: 0, remainTime: 0, totalTime: 3600]
        ]
        driver.applyStatus(humidifierStatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMistLevel called while device is off"
        driver.setMistLevel(5)

        then: "setSwitch(powerSwitch:1) was sent — V2-family payload (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null
        onReq.data.switchIdx == 0
    }

    // -------------------------------------------------------------------------
    // CONCERN 4 regression guard: setLevel(0) → off() (SwitchLevel capability convention)
    // Without the fix: pct=0 → levelFromPercent(0) → 1 → setMistLevel(1) → ensureSwitchOn()
    // turns device ON. With the fix: setLevel(0) calls off() and returns.
    // This test MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "setLevel(0) calls off() and does NOT call setVirtualLevel (SwitchLevel convention)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when: "setLevel(0) is called"
        driver.setLevel(0)

        then: "setSwitch(powerSwitch:0) was sent (off called)"
        def offReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 }
        offReq != null

        and: "setVirtualLevel was NOT sent (no mist-level command issued)"
        testParent.allRequests.every { it.method != "setVirtualLevel" }
    }

    def "setLevel(0) on already-off device sends off() without setVirtualLevel (SwitchLevel convention)"() {
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        testParent.allRequests.clear()

        when: "setLevel(0) is called on an off device"
        driver.setLevel(0)

        then: "setSwitch(powerSwitch:0) was sent (off called)"
        def offReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 }
        offReq != null

        and: "setVirtualLevel was NOT sent"
        testParent.allRequests.every { it.method != "setVirtualLevel" }
    }

    // -------------------------------------------------------------------------
    // BP28 regression guard: non-numeric level/mist value must NOT turn device off.
    // Non-vacuity: (a) FAILS on pre-fix code (safeIntArg("garbage",0)->0->off());
    // PASSES on post-fix (parseLevelOrNull("garbage")->null->ignore). (b) guards the
    // explicit-0 contract so the fix can't over-correct and break setMistLevel(0).
    // -------------------------------------------------------------------------

    def "setMistLevel('garbage') is ignored — no off(), no cloud command (BP28)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when: "setMistLevel called with a non-numeric typo"
        driver.setMistLevel("garbage")

        then: "no off() (setSwitch powerSwitch:0) was sent"
        testParent.allRequests.every { !(it.method == "setSwitch" && it.data.powerSwitch == 0) }

        and: "no setVirtualLevel mist command was sent"
        testParent.allRequests.every { it.method != "setVirtualLevel" }
    }

    def "setMistLevel(0) still calls off() (BP28 explicit-0 contract preserved)"() {
        given: "device is on, not sleeping"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "mode", value: "manual"])
        testParent.allRequests.clear()

        when: "setMistLevel(0) is called"
        driver.setMistLevel(0)

        then: "off() (setSwitch powerSwitch:0) was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 } != null

        and: "no setVirtualLevel mist command was sent"
        testParent.allRequests.every { it.method != "setVirtualLevel" }
    }

    // -------------------------------------------------------------------------
    // CONCERN 3 regression guard: setAutoStop C3 state-change gate
    // setAutoStop("on") when autoStopEnabled is already "on" → no API call (no-op).
    // -------------------------------------------------------------------------

    def "setAutoStop('on') when already 'on' is a no-op (CONCERN 3 C3 gate)"() {
        given: "autoStopEnabled is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "on"])
        testParent.allRequests.clear()

        when: "setAutoStop('on') called — same value as current"
        driver.setAutoStop("on")

        then: "no API request was sent"
        testParent.allRequests.isEmpty()
    }

    def "setAutoStop('off') when 'on' sends API call (CONCERN 3 C3 gate — state change passes through)"() {
        given: "autoStopEnabled is currently 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "on"])
        testParent.allRequests.clear()

        when: "setAutoStop('off') called — value differs"
        driver.setAutoStop("off")

        then: "setAutoStopSwitch API call was sent with autoStopSwitch=0"
        def req = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        req != null
        req.data.autoStopSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Regression guards — setTargetHumidity null/0/valid input handling
    // -------------------------------------------------------------------------

    def "setTargetHumidity(null) is rejected with logWarn and no API call (BP18 Fix 5)"() {
        // Pre-fix: (null as Integer) ?: 50 silently set humidity to 50; no warning.
        // Post-fix: requireNotNull rejects null before coercion.
        when:
        driver.setTargetHumidity(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()
        testLog.warns.any { it.contains("setTargetHumidity") || it.contains("null") }
    }

    def "setTargetHumidity(0) is rejected with logWarn and no API call (Fix 5 — 0% is not valid)"() {
        // 0% is not a meaningful humidifier target. Pre-fix: clamped to 30 silently.
        // Post-fix: p <= 0 → logWarn + return before the API call.
        when:
        driver.setTargetHumidity(0)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()
        testLog.warns.any { it.contains("0") }
    }

    def "setTargetHumidity(50) sends setTargetHumidity {targetHumidity:50} (Fix 5 happy path)"() {
        when:
        driver.setTargetHumidity(50)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 50
        lastEventValue("targetHumidity") == 50
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setMistLevel with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setMistLevel('#badInput') does not throw and does not make a setVirtualLevel API call (Superior 6000S)"() {
        // safeIntArg() maps non-numeric inputs to 0; 0 triggers the lvl<=0 guard → off() path.
        // Superior 6000S uses setVirtualLevel with {levelIdx, virtualLevel, levelType:'mist'}.
        given: "device is on so the auto-on guard doesn't confuse the assertion"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setVirtualLevel (mist) API call"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setMistLevel('5.7') does not throw and makes a setVirtualLevel API call with truncated value (5) (Superior 6000S)"() {
        // safeIntArg("5.7") → 5 (truncation). Math.max(1, Math.min(9, 5)) = 5 → cloud call made.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setVirtualLevel API call was made with virtualLevel=5 (truncated from 5.7)"
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.virtualLevel == 5
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setTargetHumidity with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setTargetHumidity('#badInput') does not throw and does not make a setTargetHumidity API call (fallback=0 → rejected)"() {
        // Inputs that safeIntArg() maps to 0: "abc", "" (empty), true ("true" is not numeric).
        // 0 → p<=0 guard fires → logWarn + return; no cloud call.
        // Pre-fix: (percent as Integer) on any of these threw before the guard could fire.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setTargetHumidity(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setTargetHumidity API call — 0 coercion rejected by minimum-humidity guard"
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setTargetHumidity('5.7') does not throw and makes a setTargetHumidity API call with clamped value (30)"() {
        // safeIntArg("5.7") → BigDecimal("5.7").intValue() = 5.  5 > 0 so the rejection guard
        // is skipped; Math.max(30, Math.min(80, 5)) = 30 (clamped to minimum).  A valid cloud
        // call is made with targetHumidity: 30.  Truncation is the correct post-fix behaviour.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setTargetHumidity("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setTargetHumidity API call was made with targetHumidity=30 (5 clamped to minimum)"
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 30
    }

    // -------------------------------------------------------------------------
    // BP26: safeIntArg regression — setLevel non-numeric RM inputs must not throw
    // -------------------------------------------------------------------------

    @Unroll
    def "BP26: setLevel('#badInput') does not throw and routes to off() on Superior 6000S (fallback=0)"() {
        // safeIntArg coerces "abc"/""/true to 0; pct==0 routes to off(), no setVirtualLevel call.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setVirtualLevel API call — 0 routes to off()"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setLevel('5.7') does not throw and makes a setVirtualLevel API call (Superior 6000S)"() {
        // safeIntArg("5.7") → 5; levelFromPercent(5) → mist level 1 (5% of 0-100 maps to level 1 of 1-9).
        // setLevel delegates to setMistLevel after percent→level mapping.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setVirtualLevel API call was made (mist level set)"
        !testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BP25-truthy: setChildLock truthy-variant canonical emission
    // -------------------------------------------------------------------------

    def "BP25-truthy: setChildLock('true') sends childLockSwitch:1 and emits 'on'"() {
        // Pre-fix: val = "true"; sendEvent(value:"true"). Post-fix: canon="on"; sendEvent(value:"on").
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("true")

        then: "API call sent with childLockSwitch:1"
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1

        and: "emitted attribute is canonical 'on', not raw 'true'"
        lastEventValue("childLock") == "on"
    }

    def "BP25-truthy: setChildLock('ON') sends childLockSwitch:1 and emits 'on'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("ON")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1
        lastEventValue("childLock") == "on"
    }

    def "BP25-truthy: setChildLock('1') sends childLockSwitch:1 and emits 'on'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("1")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1
        lastEventValue("childLock") == "on"
    }

    def "BP25-truthy: C3 gate suppresses setChildLock when childLock='on' and input is 'true'"() {
        given:
        testDevice.events.add([name: "childLock", value: "on"])
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("true")

        then: "C3 gate suppressed the call because canon=='on'==currentValue"
        testParent.allRequests.findAll { it.method == "setChildLock" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BP25-truthy: doSet* shared-helper path (setDisplay / setAutoStop delegators)
    // These methods delegate to LevoitHumidifierLib doSetDisplayScreenSwitch /
    // doSetAutoStopSwitch.  The truthy-canon ternary in the shared helpers must
    // emit "on" (not "true") and send the correct integer payload.
    // These specs MUST FAIL if value: canon is reverted to value: val in
    // LevoitHumidifierLib doSetDisplayScreenSwitch or doSetAutoStopSwitch.
    // -------------------------------------------------------------------------

    def "BP25-truthy: setDisplay('true') sends screenSwitch:1 and emits 'on' (doSet* path)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("true")

        then: "API call sent with screenSwitch:1"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1

        and: "emitted attribute is canonical 'on', not raw 'true'"
        lastEventValue("displayOn") == "on"
    }

    def "BP25-truthy: setDisplay('1') sends screenSwitch:1 and emits 'on' (doSet* path)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("1")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1
        lastEventValue("displayOn") == "on"
    }

    def "BP25-truthy: setAutoStop('true') sends autoStopSwitch:1 and emits 'on' (doSet* path)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoStop("true")

        then: "API call sent with autoStopSwitch:1"
        def req = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        req != null
        req.data.autoStopSwitch == 1

        and: "emitted attribute is canonical 'on', not raw 'true'"
        lastEventValue("autoStopEnabled") == "on"
    }

    def "BP25-truthy: setAutoStop('1') sends autoStopSwitch:1 and emits 'on' (doSet* path)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoStop("1")

        then:
        def req = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        req != null
        req.data.autoStopSwitch == 1
        lastEventValue("autoStopEnabled") == "on"
    }

    // -------------------------------------------------------------------------
    // BUG #212: sleep-mode guard — setDisplay and setMistLevel must skip during sleep
    // These tests MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "BUG #212: setDisplay skips cloud call when device is in sleep mode (Sup6000S firmware constraint)"() {
        // Pre-fix: setDisplay("on") delegated to doSetDisplayScreenSwitch without a sleep-mode check.
        // The cloud accepted the call but returned inner code -1, logging an ERROR.
        // Post-fix: setDisplay checks device.currentValue("mode") == "sleep" and returns early with INFO.
        given: "device mode is sleep"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "mode", value: "sleep"])
        testParent.allRequests.clear()

        when:
        driver.setDisplay("on")

        then: "no API call was made"
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()

        and: "an INFO log was emitted (not an ERROR)"
        testLog.infos.any { it.toLowerCase().contains("sleep") }
        testLog.errors.isEmpty()
    }

    def "BUG #212: setDisplay proceeds normally when device is in manual mode (no false-positive)"() {
        // Guard must NOT fire for non-sleep modes.
        given: "device mode is manual"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "mode", value: "manual"])
        testParent.allRequests.clear()

        when:
        driver.setDisplay("on")

        then: "API call was sent"
        !testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()
    }

    def "BUG #212: setMistLevel skips cloud call when device is in sleep mode (Sup6000S firmware constraint)"() {
        // Pre-fix: setMistLevel(5) proceeded to setVirtualLevel even during sleep mode.
        // Post-fix: sleep-mode guard runs after the safeIntArg parse and the lvl<=0 power-off
        // branch, but before the cloud write (setVirtualLevel) and ensureSwitchOn; returns early with INFO.
        given: "device mode is sleep"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "mode", value: "sleep"])
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when:
        driver.setMistLevel(5)

        then: "no API call was made"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()

        and: "an INFO log was emitted (not an ERROR)"
        testLog.infos.any { it.toLowerCase().contains("sleep") }
        testLog.errors.isEmpty()
    }

    def "BUG #212: setMistLevel proceeds normally when device is in auto mode (no false-positive)"() {
        given: "device mode is auto"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "mode", value: "auto"])
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when:
        driver.setMistLevel(5)

        then: "setVirtualLevel API call was sent"
        !testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // setMistLevel(0) power-off ordering vs sleep-mode guard.
    // Release-notes contract: setMistLevel(0) turns the device off for ALL humidifiers.
    // The lvl<=0 -> off() branch MUST evaluate BEFORE the sleep-mode short-circuit, so a
    // setMistLevel(0) issued while in sleep mode powers the device off instead of skipping.
    // Regression guard: this test FAILS if the sleep guard is moved back ahead of the
    // power-off branch (the off()/setSwitch power=0 call would never fire).
    // -------------------------------------------------------------------------

    def "setMistLevel(0) while in sleep mode powers the device off (off branch precedes sleep guard)"() {
        given: "device is in sleep mode and on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "mode", value: "sleep"])
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when:
        driver.setMistLevel(0)

        then: "a setSwitch power-off (powerSwitch=0) API call was made"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0

        and: "no setVirtualLevel mist-write call was made"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BUG #213: requireNonEmptyEnum — empty-string inputs silently rejected
    // These tests MUST FAIL on pre-fix code (setMode("") produced an INFO leak)
    // and PASS on post-fix code (silent early return, no API call, no log).
    // -------------------------------------------------------------------------

    def "BUG #213: setMode('') silently exits without API call (empty-string RM blank slot)"() {
        // Pre-fix: requireNotNull("") returned true; "" became mode ""; logInfo "Mode: " was emitted.
        // Post-fix: requireNonEmptyEnum("") returns false silently; no processing occurs.
        given:
        settings.descriptionTextEnable = true
        testParent.allRequests.clear()
        int infosBefore = testLog.infos.size()

        when:
        driver.setMode("")

        then: "no API call"
        testParent.allRequests.findAll { it.method == "setHumidityMode" }.isEmpty()

        and: "no new INFO log emitted (empty-string path is silent)"
        testLog.infos.size() == infosBefore

        and: "no exception thrown"
        noExceptionThrown()
    }

    def "BUG #213: setMode(null) still emits WARN (null path unchanged by fix)"() {
        // Null path must still warn — requireNonEmptyEnum delegates null handling to original requireNotNull WARN.
        given:
        testParent.allRequests.clear()

        when:
        driver.setMode(null)

        then: "WARN logged for null"
        testLog.warns.any { it.contains("setMode") && it.contains("null") }

        and: "no API call"
        testParent.allRequests.isEmpty()
    }

    def "BUG #213: setChildLock('') silently exits without API call (empty-string RM blank slot)"() {
        given:
        settings.descriptionTextEnable = true
        testParent.allRequests.clear()
        int infosBefore = testLog.infos.size()

        when:
        driver.setChildLock("")

        then: "no API call"
        testParent.allRequests.findAll { it.method == "setChildLock" }.isEmpty()

        and: "no new INFO log emitted"
        testLog.infos.size() == infosBefore

        and: "no exception thrown"
        noExceptionThrown()
    }

    def "BUG #213: setDryingMode('') silently exits without API call (empty-string RM blank slot)"() {
        given:
        settings.descriptionTextEnable = true
        testParent.allRequests.clear()
        int infosBefore = testLog.infos.size()

        when:
        driver.setDryingMode("")

        then: "no API call"
        testParent.allRequests.findAll { it.method == "setDryingMode" }.isEmpty()

        and: "no new INFO log emitted"
        testLog.infos.size() == infosBefore

        and: "no exception thrown"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // NIT 3: null-guard fires BEFORE sleep-mode check in setDisplay
    // Ordering guard: null input must be rejected (WARN) even when device is sleeping.
    // Pre-fix ordering: sleep check first → null passed to doSetDisplayScreenSwitch silently.
    // Post-fix ordering: requireNonEmptyEnum first → null produces WARN before sleep check runs.
    // -------------------------------------------------------------------------

    def "NIT 3: setDisplay(null) while in sleep mode logs WARN — null-guard fires before sleep check"() {
        // Pre-fix ordering: sleep-mode guard ran first; null + sleep-mode returned true silently.
        // Post-fix ordering: requireNonEmptyEnum runs first, logs WARN, returns false.
        given: "device mode is sleep"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "mode", value: "sleep"])
        testParent.allRequests.clear()

        when:
        driver.setDisplay(null)

        then: "no API call was made"
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()

        and: "a WARN was logged (not silent return)"
        testLog.warns.any { it.toLowerCase().contains("setdisplay") }

        and: "no exception thrown"
        noExceptionThrown()
    }

    def "NIT 3: setDisplay('') while in sleep mode silently exits — empty-string rejected before sleep check"() {
        // Empty string is rejected by requireNonEmptyEnum before the sleep-mode check even runs.
        given: "device mode is sleep"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "mode", value: "sleep"])
        testParent.allRequests.clear()
        int infosBefore = testLog.infos.size()

        when:
        driver.setDisplay("")

        then: "no API call"
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()

        and: "no INFO log about sleep mode (sleep check never reached)"
        testLog.infos.size() == infosBefore

        and: "no exception thrown"
        noExceptionThrown()
    }
}
