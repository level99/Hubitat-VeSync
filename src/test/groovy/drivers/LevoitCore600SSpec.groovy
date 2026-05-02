package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitCore600S.groovy (Levoit Core 600S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists
 *   Bug Pattern #12 — pref-seed in update(status, nightLight)
 *   Happy path      — update(status, nightLight) parses Core 600S response correctly
 *
 * Core 600S is the largest purifier in the Core line. Same API conventions as 400S.
 */
class LevoitCore600SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore600S.groovy"
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
        def fixture = loadYamlFixture("Core600S.yaml")
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

        def fixture = loadYamlFixture("Core600S.yaml")
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

        def fixture = loadYamlFixture("Core600S.yaml")
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
        def fixture = loadYamlFixture("Core600S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map
        assert status.result.enabled == true

        when:
        driver.update(status, null)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "filter is 80"
        lastEventValue("filter") == 80

        and: "speed set from level 2 -> 'medium'"
        lastEventValue("speed") == "medium"

        and: "no errors"
        testLog.errors.isEmpty()
    }

    def "update() with device_on_auto_mode emits auto speed"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core600S.yaml")
        def status = fixture.responses.device_on_auto_mode as Map

        when:
        driver.update(status, null)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("speed") == "auto"
    }

    def "update() with device_off emits switch=off"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core600S.yaml")
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

        when:
        driver.setMode("sleep")

        then:
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req != null
        req.data.mode == "sleep"
        !req.data.containsKey("workMode")
    }

    def "filter life low INFO logged when crossing threshold"() {
        given:
        settings.descriptionTextEnable = true
        state.lastFilterLife = 15

        def fixture = loadYamlFixture("Core600S.yaml")
        def status = fixture.responses.device_filter_low as Map  // filter_life=7

        when:
        driver.update(status, null)

        then:
        testLog.infos.any { it.contains("7") || it.contains("Filter") || it.contains("filter") }
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

    def "update() parses child_lock=true and emits childLock='on'"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core600S.yaml")
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
        def fixture = loadYamlFixture("Core600S.yaml")
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.air_quality_value == 3

        when:
        driver.update(status, null)

        then:
        def pm25Events = testDevice.allEvents("pm25")
        pm25Events.size() > 0
        pm25Events.last().value == 3
    }

    def "update() parses air_quality into airQualityIndex attribute"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core600S.yaml")
        def status = fixture.responses.device_on_manual_speed1 as Map
        assert status.result.air_quality == 1

        when:
        driver.update(status, null)

        then:
        def aqiEvents = testDevice.allEvents("airQualityIndex")
        aqiEvents.size() > 0
        aqiEvents.last().value == 1
    }

    // -------------------------------------------------------------------------
    // SwitchLevel 2-arg overload
    // -------------------------------------------------------------------------

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — Core-line setLevel request sent"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("level")
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #23: setLevel(N>0) auto-turns-on when switch is off
    // -------------------------------------------------------------------------

    def "BP23: setLevel(N>0) when switch is off calls on() before sending level command"() {
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setLevel(100) is called on an off device"
        driver.setLevel(100)

        then: "on() was called — setSwitch with enabled=true was sent before the speed command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP23: setLevel(0) calls off() and does not send a speed command"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(0) is called"
        driver.setLevel(0)

        then: "off() was called — setSwitch with enabled=false was sent"
        def offReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == false }
        offReq != null

        and: "no setLevel speed command was sent"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "no error was logged"
        testLog.errors.isEmpty()
    }
}
