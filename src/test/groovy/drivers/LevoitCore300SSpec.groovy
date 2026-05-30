package drivers

import spock.lang.Unroll
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

    @Unroll
    def "C3: #driverMethod('on') when #attr is already 'on' is a no-op (no API call)"() {
        given: "#attr is already on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "on"])

        when:
        driver."$driverMethod"("on")

        then: "no #apiMethod API call was made"
        testParent.allRequests.find { it.method == apiMethod } == null

        and: "no errors logged"
        testLog.errors.isEmpty()

        where:
        driverMethod   | attr        | apiMethod
        "setChildLock" | "childLock" | "setChildLock"
        "setDisplay"   | "display"   | "setDisplay"
    }

    @Unroll
    def "C3: #driverMethod('on') when #attr is 'off' does send the API call"() {
        given: "#attr is currently off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "off"])

        when:
        driver."$driverMethod"("on")

        then: "#apiMethod API call was made"
        testParent.allRequests.find { it.method == apiMethod } != null

        where:
        driverMethod   | attr        | apiMethod
        "setChildLock" | "childLock" | "setChildLock"
        "setDisplay"   | "display"   | "setDisplay"
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

    // -------------------------------------------------------------------------
    // BP18 regression guard: setLevel(null) does not throw NPE
    // -------------------------------------------------------------------------

    def "BP18: setLevel(null) does not throw and does not send a speed command (Core 300S)"() {
        // Regression guard: before this fix, null < 33 threw NPE (Groovy null arithmetic),
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

    @Unroll
    def "BP25: #driverMethod('ON') uppercase makes the API call and sends #payloadField:true (not false)"() {
        // Pre-fix: ("ON" == "on") is false → payloadField:false sent (inverted).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → payloadField:true (correct).
        given: "#attr is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "off"])

        when: "#driverMethod is called with uppercase 'ON'"
        driver."$driverMethod"("ON")

        then: "API call was made"
        def req = testParent.allRequests.find { it.method == apiMethod }
        req != null

        and: "payload carries #payloadField:true, NOT false"
        req.data[payloadField] == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue(attr) == "on"

        where:
        driverMethod   | apiMethod      | payloadField | attr
        "setChildLock" | "setChildLock" | "child_lock" | "childLock"
        "setDisplay"   | "setDisplay"   | "state"      | "display"
    }

    @Unroll
    def "BP25: #driverMethod('ON') when #attr is already 'on' is a no-op (C3 gate works with uppercase)"() {
        // Pre-fix: ("on" == "ON") is false → gate bypassed, redundant API call made.
        // Post-fix: toLowerCase() yields "on" == "on" → gate fires, no API call.
        given: "#attr is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: attr, value: "on"])

        when: "#driverMethod called with uppercase 'ON'"
        driver."$driverMethod"("ON")

        then: "no API call was made (C3 gate worked correctly)"
        testParent.allRequests.find { it.method == apiMethod } == null

        where:
        driverMethod   | apiMethod      | attr
        "setChildLock" | "setChildLock" | "childLock"
        "setDisplay"   | "setDisplay"   | "display"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #26: safeIntArg regression — setLevel non-numeric RM inputs
    // -------------------------------------------------------------------------

    @Unroll
    def "BP26: setLevel('#badInput') does not throw and does not make a setLevel API call (Core 300S fallback=0 → off)"() {
        // safeIntArg coerces "abc"/""/true to 0; pct==0 routes to off(), no speed API call.
        // Pre-fix: (value as Integer) on any of these threw before the guard could fire.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no speed setLevel API call — 0 coercion routes to off()"
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setLevel('5.7') does not throw and makes a setLevel API call (Core 300S)"() {
        // safeIntArg("5.7") → 5 (truncation). 5% < 33% → speed band 1.
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

    // -------------------------------------------------------------------------
    // Item 4: setLevel(N) emits named speed attribute (not raw integer string)
    // -------------------------------------------------------------------------

    def "setLevel(50) emits speed attribute as named band 'medium', not raw '2' (Core 300S)"() {
        // Regression guard: setLevel() calls setSpeed(2) which calls handleSpeed().
        // Without the mapIntegerStringToSpeed() remap, the speed attribute is emitted as
        // "2" (raw integer-string). Rule Machine rules using ``speed == "medium"`` would
        // never match. Fix: add the remap line to setSpeed before the mode dispatch.
        //
        // Both-ways: orchestrator-owned.
        given: "device is on in manual mode so the speed path fires directly"
        settings.descriptionTextEnable = true
        state.mode = "manual"
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(50) is called — passes Integer 2 to setSpeed"
        driver.setLevel(50)

        then: "the speed attribute was emitted as 'medium' (named), not '2' (raw)"
        lastEventValue("speed") == "medium"

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #26: safeIntArg regression — non-numeric RM inputs must not throw
    // -------------------------------------------------------------------------

    @Unroll
    def "BP26: setTimer('#badInput') does not throw on non-numeric input from Rule Machine (Core 300S)"() {
        // Before BP26 fix, `seconds as Integer` on non-numeric input threw NumberFormatException
        // (swallowed silently by Hubitat sandbox, leaving the command a no-op with no log entry).
        // safeIntArg(badInput, 0) returns 0, which routes to the cancelTimer / early-return path.
        given:
        settings.descriptionTextEnable = false

        when: "setTimer called with non-numeric input (Rule Machine blank slot / typo)"
        driver.setTimer(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no error logged"
        testLog.errors.isEmpty()

        where:
        badInput << ["", "abc"]
    }

    // -------------------------------------------------------------------------
    // setAutoMode fallback hardening — non-numeric state.room_size + floor
    // Guards the nested safeIntArg(state.room_size, 800) fix: confirms that a
    // non-numeric value in state.room_size does not produce a silent no-op,
    // and that a negative roomSize is floored to ≥1 before reaching the API.
    // -------------------------------------------------------------------------

    def "setAutoMode('efficient') with garbage state.room_size makes the API call with room_size=800 (not a silent no-op)"() {
        // Pre-fix: state.room_size written raw from VeSync API; if it ever holds a non-numeric
        // String, `(state.room_size ?: 800) as Integer` throws NumberFormatException BEFORE
        // safeIntArg runs — sandbox swallows it, setAutoMode is a silent no-op.
        // Post-fix: nested safeIntArg(state.room_size, 800) absorbs non-numeric state.
        // Uses mode='efficient' because that is the only code path that calls
        // handleAutoMode(mode, sz) — the 2-arg overload that sends room_size in the payload.
        given: "state.room_size is a non-numeric string (e.g. from a corrupted API response)"
        settings.descriptionTextEnable = false
        state.room_size = "garbage"   // non-numeric; pre-fix caused NumberFormatException

        when: "setAutoMode called with no explicit roomSize (1-arg form, efficient mode)"
        driver.setAutoMode("efficient")

        then: "no exception thrown"
        noExceptionThrown()

        and: "the setAutoPreference API call IS made (not a silent no-op)"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "the room_size in the payload is the safe fallback 800, not garbage"
        req.data.room_size == 800

        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "setAutoMode('efficient', '-50') then blank setAutoMode('efficient') does NOT send room_size=-50 (floor guard)"() {
        // Pre-fix self-poison: setAutoMode("efficient", "-50") → safeIntArg returns -50 (valid parse
        // of numeric string "-50") → state.room_size = -50 → -50 is truthy in Groovy → next blank
        // 1-arg call passes -50 as roomSize → 2-arg overload sends room_size:-50 (invalid API value).
        // Post-fix floor Math.max(1, sz) prevents the negative value from reaching the API.
        given: "poisoned state: a prior call left state.room_size = -50 (negative)"
        settings.descriptionTextEnable = false
        state.room_size = -50   // explicitly poisoned; would be truthy in Groovy (?:) expressions

        when: "setAutoMode called again with no explicit roomSize (blank-slot 1-arg call)"
        driver.setAutoMode("efficient")

        then: "no exception thrown"
        noExceptionThrown()

        and: "the setAutoPreference API call IS made"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "room_size sent is >= 1 (floor applied — -50 is rejected by Math.max(1, sz))"
        req.data.room_size >= 1

        and: "no error logged"
        testLog.errors.isEmpty()
    }

    // -----------------------------------------------------------------------
    // BP26: LevoitCoreAQPurifierLib.setAutoMode — numeric coercion on roomSize
    // Both-ways: revert safeIntArg in setAutoMode → this spec FAILs; restore → PASSes.
    // -----------------------------------------------------------------------

    @Unroll
    def "BP26: setAutoMode('efficient', '#badRoomSize') does not throw and makes the API call (Core 300S)"() {
        // safeIntArg maps non-numeric roomSize to 0, then Math.max(1, 0) → 1.
        // Pre-fix: (roomSize as Integer) threw NumberFormatException on non-numeric input
        // before the guard fired — the sandbox swallowed it silently (no API call, no log).
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoMode("efficient", badRoomSize)

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setAutoPreference API call was made (not a silent no-op)"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "room_size sent is >= 1 (non-numeric fell back to 0, then floor to 1)"
        req.data.room_size >= 1

        where:
        badRoomSize << ["abc", "", true]
    }

    def "BP26: setAutoMode('efficient', '5.7') does not throw and makes the API call with truncated value (Core 300S)"() {
        // safeIntArg("5.7") → 5. Math.max(1, 5) → 5. API call made with room_size=5.
        // This test fails if safeIntArg is removed from LevoitCoreAQPurifierLib.setAutoMode.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoMode("efficient", "5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setAutoPreference API call was made"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "room_size is 5 (truncated from 5.7)"
        req.data.room_size == 5
    }

    // -------------------------------------------------------------------------
    // W3: setSpeed null state.mode — RECOVER instead of warn+drop (Tier-25)
    // -------------------------------------------------------------------------

    def "W3: setSpeed null state.mode — device turns on AND speed is recovered, not dropped (Core 300S)"() {
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

    def "re-entrancy guard: setSpeed from on() does not issue setPurifierMode (Core 300S)"() {
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
