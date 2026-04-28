package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitCore300S.groovy (Levoit Core 300S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists
 *   Bug Pattern #12 — pref-seed in update(status, nightLight)
 *   Happy path      — update(status, nightLight) parses Core 300S response correctly
 *
 * Core 300S adds auto mode compared to Core 200S.
 * Response format: same as Core 200S — status.result.{level, mode, enabled, filter_life, ...}
 * It also has configuration.auto_preference.{type, room_size}.
 */
class LevoitCore300SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore300S.groovy"
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
        def fixture = loadYamlFixture("Core300S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        def result = driver.update(status, null)

        then:
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires in update() when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("Core300S.yaml")
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

        def fixture = loadYamlFixture("Core300S.yaml")
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
        def fixture = loadYamlFixture("Core300S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

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
        def fixture = loadYamlFixture("Core300S.yaml")
        def status = fixture.responses.device_on_auto_mode as Map

        when:
        driver.update(status, null)

        then: "mode is auto"
        lastEventValue("mode") == "auto"

        and: "speed is 'auto' in auto mode"
        lastEventValue("speed") == "auto"
    }

    def "update() with device_off emits switch=off"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core300S.yaml")
        def status = fixture.responses.device_off as Map

        when:
        driver.update(status, null)

        then:
        lastEventValue("switch") == "off"
        testLog.errors.isEmpty()
    }

    def "setMode sends setPurifierMode with mode field (Core-line convention)"() {
        given:
        settings.descriptionTextEnable = false

        when: "setMode('auto') called"
        driver.setMode("auto")

        then: "setPurifierMode sent with mode:auto"
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req != null
        req.data.mode == "auto"
        // Core-line uses 'mode' field NOT 'workMode'
        !req.data.containsKey("workMode")
    }

    // -------------------------------------------------------------------------
    // v2.3 new features: childLock, display, timer, resetFilter, pm25, airQualityIndex
    // -------------------------------------------------------------------------

    def "setChildLock('on') sends setChildLock with child_lock=true"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("on")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.child_lock == true
    }

    def "setChildLock('off') sends setChildLock with child_lock=false"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("off")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.child_lock == false
    }

    def "update() parses child_lock=true from fixture and emits childLock='on'"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core300S.yaml")
        // device_on_manual_speed1 has child_lock: true and air_quality: 1
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.child_lock == true

        when:
        driver.update(status, null)

        then:
        lastEventValue("childLock") == "on"
    }

    def "setTimer(300) sends addTimer with action='off' and total=300"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setTimer(300)

        then:
        def req = testParent.allRequests.find { it.method == "addTimer" }
        req != null
        req.data.action == "off"
        req.data.total == 300
    }

    def "resetFilter sends resetFilter method with empty data"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.resetFilter()

        then:
        def req = testParent.allRequests.find { it.method == "resetFilter" }
        req != null
        (req.data == null || req.data == [:])
    }

    def "update() parses air_quality_value into pm25 attribute"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core300S.yaml")
        // device_on_manual_speed1 has air_quality_value: 3 and air_quality: 1
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.air_quality_value == 3

        when:
        driver.update(status, null)

        then: "pm25 event emitted with value 3"
        def pm25Events = testDevice.allEvents("pm25")
        pm25Events.size() > 0
        pm25Events.last().value == 3
    }

    def "update() parses air_quality into airQualityIndex attribute"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core300S.yaml")
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.air_quality == 1

        when:
        driver.update(status, null)

        then: "airQualityIndex event emitted with value 1"
        def aqiEvents = testDevice.allEvents("airQualityIndex")
        aqiEvents.size() > 0
        aqiEvents.last().value == 1
    }
}
