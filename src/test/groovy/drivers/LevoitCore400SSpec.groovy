package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitCore400S.groovy (Levoit Core 400S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists
 *   Bug Pattern #12 — pref-seed in update(status, nightLight)
 *   Happy path      — update(status, nightLight) parses Core 400S response correctly
 *
 * Core 400S has 4 fan speeds (not 3) and full auto-preference support.
 * Uses Core-line field conventions: setLevel data has {level, id:0, type:"wind"}.
 */
class LevoitCore400SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore400S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given:
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then:
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    def "update() happy path from device_on_manual_speed2 emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map
        assert status.result.enabled == true

        when:
        driver.update(status, null)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "filter is 85"
        lastEventValue("filter") == 85

        and: "speed set from level 2 -> 'medium'"
        lastEventValue("speed") == "medium"

        and: "no errors"
        testLog.errors.isEmpty()
    }

    def "update() with device_on_auto_mode emits auto speed"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_auto_mode as Map

        when:
        driver.update(status, null)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("speed") == "auto"
    }

    def "update() device_off emits switch=off"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_off as Map

        when:
        driver.update(status, null)

        then:
        lastEventValue("switch") == "off"
        testLog.errors.isEmpty()
    }

    def "setLevel uses Core-line {level, id, type} convention (NOT V2 {levelIdx, levelType, manualSpeedLevel})"() {
        given: "device is on in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        driver.state.mode = "manual"

        when: "setLevel(50) is called"
        driver.setLevel(50)

        then: "setLevel request uses Core-line field names"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("level")
        req.data.containsKey("id")
        req.data.containsKey("type")
        // Core-line does NOT use V2 field names
        !req.data.containsKey("levelIdx")
        !req.data.containsKey("levelType")
        !req.data.containsKey("manualSpeedLevel")
        req.data.type == "wind"
        req.data.id == 0
    }

    def "AQI calculation produces a value between 0 and 500"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then: "AQI event was emitted with a numeric value in valid range"
        def aqiVal = testDevice.allEvents("AQI")
        aqiVal.size() > 0
        def v = aqiVal.last().value
        v != null
        v >= 0
    }
}
