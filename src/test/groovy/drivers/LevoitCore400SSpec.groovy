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

    // -------------------------------------------------------------------------
    // BP24 SHOULD-ON: setAutoMode from off-state turns the device on (v2.9).
    // NON-VACUITY: deleting the ensureSwitchOn() line in setAutoMode makes the
    // on() assertion go RED (no setSwitch enabled=true fires).
    // -------------------------------------------------------------------------

    def "BP24: setAutoMode('default') from off-state turns the device on before the mode command"() {
        given: "device is off and turningOn flag clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "setAutoMode('default') is called on an off device"
        driver.setAutoMode("default")

        then: "on() fired — setSwitch with enabled=true was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true } != null

        and: "the auto-mode command (setPurifierMode) was sent"
        testParent.allRequests.find { it.method == "setPurifierMode" } != null
    }

    // -------------------------------------------------------------------------
    // v2.9: setAutoMode no longer commits state.mode unconditionally — only on a
    // successful handleMode() call. NON-VACUITY: reverting to the old unconditional
    // `state.mode = "auto"` makes the "mode stays manual on failure" assertion go RED.
    // -------------------------------------------------------------------------

    def "setAutoMode does NOT set state.mode='auto' when the mode command fails (state-vs-reality)"() {
        given: "device is on (so ensureSwitchOn is a no-op and does not consume a queued response)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOn")
        state.mode = "manual"
        testParent.allRequests.clear()
        // First call (handleAutoMode -> setAutoPreference) succeeds; second call
        // (handleMode -> setPurifierMode) fails with HTTP 500.
        testParent.requestResponses = [
            support.TestParent.successResponse([:]),
            support.TestParent.httpErrorResponse(500)
        ]

        when: "setAutoMode('default') is called and the mode write fails"
        driver.setAutoMode("default")

        then: "state.mode was NOT flipped to 'auto' — it reflects reality (still manual)"
        state.mode != "auto"

        and: "an error was logged for the failed auto-mode write"
        testLog.errors.any { it.toLowerCase().contains("auto mode write failed") } ||
            testLog.errors.any { it.contains("500") }
    }

    def "setAutoMode DOES set state.mode='auto' when the mode command succeeds"() {
        given: "device is on; both API calls succeed (default OK responses)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOn")
        state.mode = "manual"
        testParent.allRequests.clear()

        when: "setAutoMode('default') is called"
        driver.setAutoMode("default")

        then: "state.mode committed to 'auto' on success"
        state.mode == "auto"

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
    }

    // -------------------------------------------------------------------------
    // v2.9: CorePurifierLib.setMode no longer commits state.mode unconditionally —
    // only on a successful handleMode() call (same fix class as setAutoMode above).
    // NON-VACUITY: reverting to the old unconditional `state.mode = m` makes the
    // "mode stays as-is on failure" assertion go RED (expected revert -> RED).
    // -------------------------------------------------------------------------

    def "setMode does NOT commit state.mode when the mode command fails (state-vs-reality)"() {
        given: "device is on (ensureSwitchOn is a no-op), prior mode is sleep"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOn")
        state.mode = "sleep"
        testParent.allRequests.clear()
        // handleMode -> setPurifierMode fails with HTTP 500 (the only request when already on).
        testParent.requestResponses = [
            support.TestParent.httpErrorResponse(500)
        ]

        when: "setMode('auto') is called and the mode write fails"
        driver.setMode("auto")

        then: "state.mode was NOT flipped to 'auto' — it reflects reality (still sleep)"
        state.mode == "sleep"

        and: "an error was logged for the failed mode write"
        testLog.errors.any { it.toLowerCase().contains("mode write failed") } ||
            testLog.errors.any { it.contains("500") }
    }

    def "setMode DOES commit state.mode when the mode command succeeds"() {
        given: "device is on; the API call succeeds (default OK responses)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOn")
        state.mode = "sleep"
        testParent.allRequests.clear()

        when: "setMode('auto') is called"
        driver.setMode("auto")

        then: "state.mode committed to 'auto' on success"
        state.mode == "auto"

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
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

    @Unroll
    def "setChildLock('#input') sends setChildLock with child_lock=#expected"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock(input)

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.child_lock == expected

        where:
        input | expected
        "on"  | true
        "off" | false
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

    @Unroll
    def "C3: #setter('on') when #attr is already 'on' is a no-op (no API call)"() {
        given: "#attr is already on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "on"])

        when:
        driver."$setter"("on")

        then: "no #apiMethod API call was made"
        testParent.allRequests.find { it.method == apiMethod } == null

        and: "no errors logged"
        testLog.errors.isEmpty()

        where:
        setter         | apiMethod      | attr
        "setChildLock" | "setChildLock" | "childLock"
        "setDisplay"   | "setDisplay"   | "display"
    }

    @Unroll
    def "C3: #setter('on') when #attr is 'off' does send the API call"() {
        given: "#attr is currently off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "off"])

        when:
        driver."$setter"("on")

        then: "#apiMethod API call was made"
        testParent.allRequests.find { it.method == apiMethod } != null

        where:
        setter         | apiMethod      | attr
        "setChildLock" | "setChildLock" | "childLock"
        "setDisplay"   | "setDisplay"   | "display"
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

    // -------------------------------------------------------------------------
    // Item 4: setLevel(N) emits named speed attribute (not raw integer string)
    // -------------------------------------------------------------------------

    @Unroll
    def "setLevel(#pct) emits speed attribute as named band '#expected', not raw integer-string (Core 400S)"() {
        // Regression guard: setLevel() calls setSpeed(N) which calls handleSpeed().
        // Without the mapIntegerStringToSpeed() remap, the speed attribute is emitted as
        // the raw integer-string. Rule Machine rules using ``speed == "high"`` would never
        // match. Fix: add the remap line to setSpeed before the mode dispatch.
        //   pct=60: 60 >= 50 && 60 < 75 → speed 3 → "high".
        //   pct=100: 100 >= 75 → speed 4 → "max" (the 4th level unique to Core 400S/600S).
        //
        // Both-ways: orchestrator-owned.
        given: "device is on in manual mode so the speed path fires directly"
        settings.descriptionTextEnable = true
        state.mode = "manual"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(pct)

        then: "the speed attribute was emitted as the named band, not the raw integer-string"
        lastEventValue("speed") == expected

        and: "no error was logged"
        testLog.errors.isEmpty()

        where:
        pct | expected
        60  | "high"
        100 | "max"
    }

    // -----------------------------------------------------------------------
    // BP25: case-sensitivity — setChildLock / setDisplay uppercase inputs
    // -----------------------------------------------------------------------

    @Unroll
    def "BP25: #setter('ON') uppercase makes the API call and sends #field:true (not false)"() {
        given: "#attr is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "off"])

        when: "#setter is called with uppercase 'ON'"
        driver."$setter"("ON")

        then: "API call was made"
        def req = testParent.allRequests.find { it.method == apiMethod }
        req != null

        and: "payload carries the on value (true), NOT false"
        req.data[field] == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue(attr) == "on"

        where:
        setter         | apiMethod      | field        | attr
        "setChildLock" | "setChildLock" | "child_lock" | "childLock"
        "setDisplay"   | "setDisplay"   | "state"      | "display"
    }

    @Unroll
    def "BP25: #setter('ON') when #attr is already 'on' is a no-op (C3 gate works with uppercase)"() {
        given: "#attr is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "on"])

        when: "#setter called with uppercase 'ON'"
        driver."$setter"("ON")

        then: "no API call was made (C3 gate worked correctly)"
        testParent.allRequests.find { it.method == apiMethod } == null

        where:
        setter         | apiMethod      | attr
        "setChildLock" | "setChildLock" | "childLock"
        "setDisplay"   | "setDisplay"   | "display"
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

    @Unroll
    def "BP26: setTimer('#badInput') does not throw on non-numeric input from Rule Machine (Core 400S)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setTimer called with non-numeric input (Rule Machine blank/typo slot)"
        driver.setTimer(badInput)
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()

        where:
        badInput << ["", "abc"]
    }

    // -------------------------------------------------------------------------
    // W3: setSpeed null state.mode — RECOVER instead of warn+drop (Tier-25)
    // -------------------------------------------------------------------------

    def "W3: setSpeed null state.mode — device turns on AND speed is recovered, not dropped (Core 400S)"() {
        // W3 regression guard: when state.mode is null/unset (fresh device, pre-first-poll),
        // setSpeed("high") must:
        //   1. auto-on (ensureSwitchOn fires before the mode dispatch),
        //   2. RECOVER by calling setMode("manual") and applying the speed — NOT warn+drop.
        // Pre-fix: else { logWarn "cannot apply speed"; return } — speed was lost.
        // Post-fix (Tier-25): else { setMode("manual"); handleSpeed(s); ... } — speed applied.
        //
        // Both-ways: orchestrator-owned.
        given: "device is off and state.mode is null (fresh device, pre-first-poll)"
        settings.descriptionTextEnable = true
        // state.mode intentionally NOT set — simulates a fresh/pre-poll device
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with a valid non-off speed"
        driver.setSpeed("high")

        then: "on() was called — ensureSwitchOn turned the device on before the mode dispatch"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "a setPurifierMode API call was made to set manual mode (recover path fired)"
        def modeReq = testParent.allRequests.find { it.method == "setPurifierMode" }
        modeReq != null

        and: "a setLevel (speed) API call was made — speed was NOT dropped"
        def speedReq = testParent.allRequests.find { it.method == "setLevel" }
        speedReq != null

        and: "the speed attribute was emitted with the requested value"
        lastEventValue("speed") == "high"

        and: "no error was logged"
        testLog.errors.isEmpty()

        and: "no 'cannot apply speed' warning was emitted (recover path replaces warn+drop)"
        !testLog.warns.any { it.contains("cannot apply speed") }
    }

    def "re-entrancy guard: setSpeed from on() does not issue setPurifierMode (Core 400S)"() {
        // Regression guard for the `if (!state.turningOn) { handleMode("manual"); ... }`
        // wrapper in setSpeed's recover-else branch.
        //
        // When on() calls setSpeed() internally, state.turningOn is already set.
        // The guard suppresses the setPurifierMode call to prevent a race.
        // The speed command (setLevel) must still fire.
        //
        // Goes RED if the `if (!state.turningOn)` wrapper is removed.
        // Both-ways: orchestrator-owned.
        given: "state.turningOn is set and state.mode is null"
        settings.descriptionTextEnable = false
        state.turningOn = true
        state.mode = null

        when: "setSpeed is called (as on() would call it internally)"
        driver.setSpeed("high")

        then: "no setPurifierMode request was issued — re-entrancy guard suppressed it"
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.isEmpty()

        and: "a setLevel (speed) API call WAS made — speed is still applied"
        !testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()

        and: "no exception was thrown"
        noExceptionThrown()
    }
}
