package drivers

import support.HubitatSpec

/**
 * Unit tests for LevoitCore200S.groovy (Levoit Core 200S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists
 *   Bug Pattern #12 — pref-seed in update(status, nightLight)
 *   Bug Pattern #16 — debug auto-disable watchdog (child-side canonical test)
 *   Happy path      — update(status, nightLight) parses Core 200S response correctly
 *
 * NOTE: The Core 200S uses an OLDER driver structure. Unlike Vital 200S,
 * it does NOT have a separate applyStatus() method — status parsing happens
 * directly in update(status, nightLight). The pref-seed block and all state
 * mutations are in that method.
 *
 * The response format is also different from V2:
 *   - status.result.level (Integer, 1-3) for fan speed
 *   - status.result.mode (String: "manual" | "sleep")
 *   - status.result.enabled (Boolean)
 *   - status.result.filter_life (Integer, 0-100)
 *   - status.result.night_light (String: "on" | "off" | "dim")
 */
class LevoitCore200SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "Core 200S status response with result nested under result key"
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "parent calls update(status, nightLight)"
        def result = driver.update(status, null)

        then:
        noExceptionThrown()
        // Returns the parsed status map
        result != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed (lives in update, not applyStatus for Core 200S)
    // -------------------------------------------------------------------------

    def "pref-seed fires in update() when descriptionTextEnable is null (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.update(status, null)

        then: "updateSetting called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def fixture = loadYamlFixture("Core200S.yaml")
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

    def "update(status, nightLight) happy path from device_on_manual_speed2 fixture"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map
        // status structure: { result: { level:2, mode:"manual", enabled:true, filter_life:85, ... }, code:0 }
        assert status.result != null
        assert status.result.enabled == true

        when:
        driver.update(status, null)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "filter is 85"
        lastEventValue("filter") == 85

        and: "speed is set from level 2 -> 'medium'"
        lastEventValue("speed") == "medium"

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "update(status, nightLight) happy path from device_off fixture"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_off as Map
        assert status.result.enabled == false

        when:
        driver.update(status, null)

        then: "switch is off"
        lastEventValue("switch") == "off"

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "filter life threshold INFO logs when below 10 percent"() {
        given: "prior filter life was 15 (above critical)"
        settings.descriptionTextEnable = true
        state.lastFilterLife = 15

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_filter_low as Map  // filter_life=8

        when:
        driver.update(status, null)

        then: "INFO logged about low filter"
        testLog.infos.any { it.contains("Filter") || it.contains("filter") || it.contains("8") }
    }

    def "speed mapIntegerToSpeed level=2 maps to 'medium'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map  // level=2

        when:
        driver.update(status, null)

        then: "speed is 'medium' for level 2"
        lastEventValue("speed") == "medium"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #16: debug auto-disable watchdog (child-side canonical test)
    // This child is the canonical representative — other children carry identical
    // ensureDebugWatchdog() code so are not individually specced for BP16.
    // -------------------------------------------------------------------------

    def "ensureDebugWatchdog() no-ops when debugOutput is false (BP16 -- child guard)"() {
        given: "debug is off; timestamp from an old session is present"
        settings.debugOutput = false
        state.debugEnabledAt = driver.now() - (2 * 60 * 60 * 1000)  // 2 hours ago

        when:
        driver.ensureDebugWatchdog()

        then: "no updateSetting call"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null
    }

    def "ensureDebugWatchdog() no-ops when elapsed < 30 min (BP16 -- within window)"() {
        given: "debug enabled 5 min ago"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (5 * 60 * 1000)  // 5 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "watchdog did not fire"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall == null
    }

    def "ensureDebugWatchdog() disables debug when elapsed >= 30 min (BP16 -- post-reboot self-heal)"() {
        given: "debug was enabled 35 min ago (runIn timer evaporated across reboot)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (35 * 60 * 1000)  // 35 min ago

        when:
        driver.ensureDebugWatchdog()

        then: "updateSetting called to disable debugOutput"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "state.debugEnabledAt cleared"
        !state.containsKey("debugEnabledAt")

        and: "BP16 INFO log emitted"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    def "update(status, nightLight) calls ensureDebugWatchdog() on every parent poll (BP16 -- call site)"() {
        given: "debug stuck true for 40 min (post-reboot scenario)"
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        state.debugEnabledAt = driver.now() - (40 * 60 * 1000)  // 40 min ago

        def fixture = loadYamlFixture("Core200S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "parent calls update(status, nightLight)"
        driver.update(status, null)

        then: "watchdog fired inside update(): debug disabled"
        def debugCall = testDevice.settingsUpdates.find { it.name == "debugOutput" }
        debugCall != null
        debugCall.value == false

        and: "BP16 INFO log emitted"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    // -------------------------------------------------------------------------
    // v2.3 new features: childLock, display, timer, resetFilter
    // -------------------------------------------------------------------------

    def "setChildLock('on') sends setChildLock with child_lock=true"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("on")

        then: "setChildLock request sent with child_lock=true"
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
        // device_on_manual_speed1 has child_lock: true
        def fixture = loadYamlFixture("Core200S.yaml")
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

    // -------------------------------------------------------------------------
    // SwitchLevel 2-arg overload
    // -------------------------------------------------------------------------

    def "setLevel(20) maps to speed 1 (low band: pct < 33)"() {
        // Regression guard: before the Fix 3 band-mapping fix, mapSpeedToInteger("1") returned 3
        // (default branch) so setLevel(20) → setLevel(50) → setLevel(80) all sent speed 3 (high).
        given: "device is on and in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setLevel(20) is called -- falls in pct < 33 band → speed 1"
        driver.setLevel(20)

        then: "Core-line setLevel request was sent with level=1"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.level == 1
    }

    def "setLevel(50) maps to speed 2 (mid band: 33 <= pct < 66)"() {
        given: "device is on and in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setLevel(50) is called -- 33 <= 50 < 66 → speed 2"
        driver.setLevel(50)

        then: "Core-line setLevel request was sent with level=2"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.level == 2
    }

    def "setLevel(80) maps to speed 3 (high band: pct >= 66)"() {
        given: "device is on and in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setLevel(80) is called -- pct >= 66 → speed 3"
        driver.setLevel(80)

        then: "Core-line setLevel request was sent with level=3"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.level == 3
    }

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — Core-line setLevel request sent"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.level == 2   // 50 → speed 2
    }

    def "BP18: setLevel(null) does not throw and does not send a speed command"() {
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

    def "setSpeed emits named speed attribute ('low'/'medium'/'high'), not integer string"() {
        // Regression guard for Fix 3 secondary bug: setLevel→setSpeed passed integer 1/2/3;
        // state.speed and speed attribute were set to "1"/"2"/"3" instead of "low"/"medium"/"high".
        given: "device is on and in manual mode"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setSpeed(1) is called (as setLevel would invoke it internally)"
        driver.setSpeed(1)

        then: "speed attribute is emitted as 'low', not '1'"
        def speedEvt = testDevice.events.findAll { it.name == "speed" }.last()
        speedEvt.value == "low"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #23: setLevel(N>0) auto-turns-on when switch is off
    // -------------------------------------------------------------------------

    def "BP23: setLevel(N>0) when switch is off calls on() before sending level command"() {
        // Room Lighting 'Activate' calls setLevel(100) on an off device. Without the BP23
        // guard, the cloud accepted speed/mode commands but the device stayed physically off.
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
        // SwitchLevel convention: setLevel(0) is equivalent to off().
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
        // Pre-fix code had a dead `if (state.switch == "off") { on() }` branch.
        // state.switch is never set (it's device.currentValue("switch") that matters),
        // so the guard never fired and the device stayed physically off.
        // Post-fix: ensureSwitchOn() checks device.currentValue("switch") != "on" correctly.
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
    // D2: off() emits speed:off for capability parity
    // -------------------------------------------------------------------------

    def "D2: off() emits both switch:off and speed:off events for capability parity"() {
        // Pre-fix off() only emitted switch:off. D2 fix adds speed:off to match the
        // AQ-group Core drivers (300S/400S/600S) which already emitted both.
        // Without speed:off, FanControl consumers see a stale speed attribute after
        // the device is turned off.
        given: "device is on with a known speed"
        settings.descriptionTextEnable = false
        state.speed = "medium"
        testDevice.events.add([name: "switch", value: "on"])

        when: "off() is called"
        driver.off()

        then: "switch:off event was emitted"
        testDevice.events.find { it.name == "switch" && it.value == "off" } != null

        and: "speed:off event was emitted (D2 parity with AQ-group Core drivers)"
        testDevice.events.find { it.name == "speed" && it.value == "off" } != null

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

    def "BP24-B: setSpeed when switch is off calls on() before sending speed command (Core 200S)"() {
        // Pre-fix: setSpeed delegated to handleSpeed without checking switch state.
        // Post-fix: ensureSwitchOn() at top of setSpeed (after off/on short-circuits)
        // turns device on first, then the mode/speed path fires normally.
        given: "device is off and mode is manual so the numeric speed path fires"
        settings.descriptionTextEnable = false
        state.mode = "manual"
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with a named speed on an off device"
        driver.setSpeed("low")

        then: "on() was called — setSwitch with enabled=true was sent before the speed command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "setLevel was sent (handleSpeed sends setLevel with {level, id, type})"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setMode when switch is off calls on() before sending mode command (Core 200S)"() {
        // Pre-fix: setMode delegated to handleMode without checking switch state.
        // Post-fix: ensureSwitchOn() at top of setMode turns device on first.
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setMode is called with 'manual' on an off device"
        driver.setMode("manual")

        then: "on() was called — setSwitch with enabled=true was sent before the mode command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null

        and: "setPurifierMode was sent (handleMode sends setPurifierMode)"
        def modeReq = testParent.allRequests.find { it.method == "setPurifierMode" }
        modeReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setSpeed('off') from off-state does NOT auto-on (power short-circuit) (Core 200S)"() {
        // setSpeed("off") calls off(), which is a no-op since already off.
        // It must NOT call ensureSwitchOn() first — power short-circuits fire before the guard.
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

    def "BP18: setSpeed(null) rejected with logWarn, no API call (Core 200S)"() {
        // requireNotNull rejects null speed args (e.g. from Rule Machine with blank parameter slot).
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

    def "BP18: setMode(null) rejected with logWarn, no API call (Core 200S)"() {
        // requireNotNull rejects null mode args (e.g. from Rule Machine with blank parameter slot).
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

    def "BP24-B + invalid mode: setMode('badvalue') from off-state does NOT auto-on (Core 200S)"() {
        // Invalid mode is rejected BEFORE ensureSwitchOn() fires — so a typo from Rule Machine
        // does not accidentally power on the device. Regression guard for the Fix-2 ordering.
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
