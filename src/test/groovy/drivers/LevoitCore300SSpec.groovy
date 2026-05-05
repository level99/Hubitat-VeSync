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

    // -------------------------------------------------------------------------
    // Bug Pattern #24-A: cycleSpeed() auto-turns-on when switch is off
    // -------------------------------------------------------------------------

    def "BP24-A: cycleSpeed() from off-state turns the device on before sending speed command"() {
        // Pre-fix code had a dead `if (state.switch == "off") { on() }` branch (300S used the
        // switch/case form, same shape). state.switch is never set; ensureSwitchOn() checks
        // device.currentValue("switch") correctly.
        given: "device is off and state.turningOn is not set"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")

        when: "cycleSpeed() is called on an off device"
        driver.cycleSpeed()

        then: "on() was called — setSwitch with enabled=true was sent"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "state.turningOn is cleared after the call (no re-entrance leak)"
        !state.containsKey("turningOn")

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // C3: state-change gate — setChildLock and setDisplay (retroactive fix via lib)
    // -------------------------------------------------------------------------

    def "C3: setChildLock('on') when childLock is already 'on' is a no-op (no API call)"() {
        given: "childLock is already on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "on"])

        when:
        driver.setChildLock("on")

        then: "no setChildLock API call was made"
        testParent.allRequests.find { it.method == "setChildLock" } == null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "C3: setChildLock('on') when childLock is 'off' does send the API call"() {
        given: "childLock is currently off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "off"])

        when:
        driver.setChildLock("on")

        then: "setChildLock API call was made"
        testParent.allRequests.find { it.method == "setChildLock" } != null
    }

    def "C3: setDisplay('on') when display is already 'on' is a no-op (no API call)"() {
        given: "display is already on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "on"])

        when:
        driver.setDisplay("on")

        then: "no setDisplay API call was made"
        testParent.allRequests.find { it.method == "setDisplay" } == null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "C3: setDisplay('on') when display is 'off' does send the API call"() {
        given: "display is currently off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "off"])

        when:
        driver.setDisplay("on")

        then: "setDisplay API call was made"
        testParent.allRequests.find { it.method == "setDisplay" } != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #24-B: setSpeed() + setMode() auto-turn-on when device is off
    // -------------------------------------------------------------------------

    def "BP24-B: setSpeed when switch is off calls on() before sending speed command (Core 300S)"() {
        // Core 300S setSpeed: ensureSwitchOn() fires after the off/on short-circuits
        // and before the mode/speed dispatch path.
        given: "device is off and mode is manual so the speed path fires"
        settings.descriptionTextEnable = false
        state.mode = "manual"
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with a named speed on an off device"
        driver.setSpeed("medium")

        then: "on() was called — setSwitch with enabled=true was sent before the speed command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "setLevel was sent (handleSpeed sends setLevel with {level, id, type})"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setMode when switch is off calls on() before sending mode command (Core 300S)"() {
        // Core 300S setMode: ensureSwitchOn() fires before handleMode delegation.
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setMode is called with 'auto' on an off device"
        driver.setMode("auto")

        then: "on() was called — setSwitch with enabled=true was sent before the mode command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "setPurifierMode was sent (handleMode sends setPurifierMode)"
        def modeReq = testParent.allRequests.find { it.method == "setPurifierMode" }
        modeReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setSpeed('off') from off-state does NOT auto-on (power short-circuit) (Core 300S)"() {
        // setSpeed("off") fires the off() short-circuit before ensureSwitchOn().
        given: "device is already off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed('off') is called on an already-off device"
        driver.setSpeed("off")

        then: "setSwitch with enabled=true was NOT sent (no auto-on for off short-circuit)"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true } == null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP18: setSpeed(null) rejected with logWarn, no API call (Core 300S)"() {
        // requireNotNull rejects null speed args (Rule Machine blank parameter slot).
        given: "device is on"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "switch", value: "on"])

        when: "setSpeed is called with null"
        driver.setSpeed(null)

        then: "no API call was made"
        testParent.allRequests.size() == 0

        and: "a warning was logged"
        testLog.warns.any { it.contains("setSpeed") }
    }

    def "BP18: setMode(null) rejected with logWarn, no API call (Core 300S)"() {
        // requireNotNull rejects null mode args (Rule Machine blank parameter slot).
        given: "device is on"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "switch", value: "on"])

        when: "setMode is called with null"
        driver.setMode(null)

        then: "no API call was made"
        testParent.allRequests.size() == 0

        and: "a warning was logged"
        testLog.warns.any { it.contains("setMode") }
    }

    def "BP24-B + invalid mode: setMode('badvalue') from off-state does NOT auto-on (Core 300S)"() {
        // Invalid mode is rejected BEFORE ensureSwitchOn() fires — typo from Rule Machine
        // does not accidentally power on the device.
        given: "device is off"
        settings.descriptionTextEnable = true
        testDevice.events.add([name: "switch", value: "off"])

        when: "setMode is called with an invalid mode value on an off device"
        driver.setMode("badvalue")

        then: "setSwitch with enabled=true was NOT sent (invalid mode rejected before auto-on)"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true } == null

        and: "no setPurifierMode call was made"
        testParent.allRequests.find { it.method == "setPurifierMode" } == null

        and: "a warning was logged mentioning the invalid mode"
        testLog.warns.any { it.contains("invalid mode") || it.contains("badvalue") }
    }
}
