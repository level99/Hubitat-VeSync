package drivers

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

        and: "display is 'on' (screenState=1)"
        lastEventValue("display") == "on"

        and: "childLock is 'off'"
        lastEventValue("childLock") == "off"

        and: "autoStopConfig is 'on' (autoStopSwitch=1)"
        lastEventValue("autoStopConfig") == "on"

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
}
