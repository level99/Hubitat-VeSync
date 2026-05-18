package drivers

import spock.lang.Unroll
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

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "device is on in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        driver.state.mode = "manual"

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
        // Pre-fix code had a dead `if (state.switch == "off") { on() }` branch (400S used the
        // switch/case form with 4 speeds, same shape). state.switch is never set;
        // ensureSwitchOn() checks device.currentValue("switch") correctly.
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

    def "AQI calculation produces a value between 0 and 500"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then: "AQI event was emitted with a numeric value in valid range"
        def aqiVal = testDevice.allEvents("aqi")
        aqiVal.size() > 0
        def v = aqiVal.last().value
        v != null
        v >= 0
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
        def fixture = loadYamlFixture("Core400S.yaml")
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
        def fixture = loadYamlFixture("Core400S.yaml")
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
        def fixture = loadYamlFixture("Core400S.yaml")
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

    def "BP24-B: setSpeed when switch is off calls on() before sending speed command (Core 400S)"() {
        // Core 400S setSpeed: ensureSwitchOn() fires after the off/on short-circuits
        // and before the mode/speed dispatch path.
        given: "device is off and mode is manual so the speed path fires"
        settings.descriptionTextEnable = false
        state.mode = "manual"
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with a named speed on an off device"
        driver.setSpeed("high")

        then: "on() was called — setSwitch with enabled=true was sent before the speed command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "setLevel was sent (handleSpeed sends setLevel with {level, id, type})"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setMode when switch is off calls on() before sending mode command (Core 400S)"() {
        // Core 400S setMode: ensureSwitchOn() fires before handleMode delegation.
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

    def "BP24-B: setSpeed('off') from off-state does NOT auto-on (power short-circuit) (Core 400S)"() {
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

    def "BP18: setSpeed(null) rejected with logWarn, no API call (Core 400S)"() {
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

    def "BP18: setMode(null) rejected with logWarn, no API call (Core 400S)"() {
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

    def "BP24-B + invalid mode: setMode('badvalue') from off-state does NOT auto-on (Core 400S)"() {
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

    // -------------------------------------------------------------------------
    // BP18 regression guard: setLevel(null) does not throw NPE
    // -------------------------------------------------------------------------

    def "BP18: setLevel(null) does not throw and does not send a speed command (Core 400S)"() {
        // Regression guard: before this fix, null < 25 threw NPE (Groovy null arithmetic),
        // which the Hubitat sandbox swallowed silently. Now coerces null → 0 → off() path.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(null)

        then: "no NPE thrown"
        noExceptionThrown()

        and: "no setLevel (speed) API call was made — null coerces to 0, routes to off()"
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    // -----------------------------------------------------------------------
    // BP25: case-sensitivity — setChildLock / setDisplay uppercase inputs
    // -----------------------------------------------------------------------

    def "BP25: setChildLock('ON') uppercase makes the API call and sends child_lock:true (not false)"() {
        given: "childLock is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "off"])

        when: "setChildLock is called with uppercase 'ON'"
        driver.setChildLock("ON")

        then: "API call was made"
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null

        and: "payload carries child_lock:true (lock), NOT false (unlock)"
        req.data.child_lock == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue("childLock") == "on"
    }

    def "BP25: setChildLock('ON') when childLock is already 'on' is a no-op (C3 gate works with uppercase)"() {
        given: "childLock is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "on"])

        when: "setChildLock called with uppercase 'ON'"
        driver.setChildLock("ON")

        then: "no API call was made (C3 gate worked correctly)"
        testParent.allRequests.find { it.method == "setChildLock" } == null
    }

    def "BP25: setDisplay('ON') uppercase makes the API call and sends state:true (not false)"() {
        given: "display is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "off"])

        when: "setDisplay is called with uppercase 'ON'"
        driver.setDisplay("ON")

        then: "API call was made"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null

        and: "payload carries state:true (on), NOT false (off)"
        req.data.state == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue("display") == "on"
    }

    def "BP25: setDisplay('ON') when display is already 'on' is a no-op (C3 gate works with uppercase)"() {
        given: "display is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "on"])

        when:
        driver.setDisplay("ON")

        then: "no API call was made"
        testParent.allRequests.find { it.method == "setDisplay" } == null
    }

    @Unroll
    def "BP26: setLevel('#badInput') does not throw and does not make a setLevel API call (Core 400S fallback=0 → off)"() {
        // safeIntArg coerces "abc"/""/true to 0; pct==0 routes to off(), no speed API call.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no speed setLevel API call"
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setLevel('5.7') does not throw and makes a setLevel API call (Core 400S)"() {
        // safeIntArg("5.7") → 5 (truncation). 5% < 25% → speed band 1.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a speed setLevel API call was made"
        !testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    def "BP26: setTimer('') does not throw on empty-string input from Rule Machine (Core 400S)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setTimer called with empty string (Rule Machine blank slot)"
        driver.setTimer("")
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "BP26: setTimer('abc') does not throw on non-numeric input from Rule Machine (Core 400S)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setTimer called with non-numeric string"
        driver.setTimer("abc")
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()
    }
}
