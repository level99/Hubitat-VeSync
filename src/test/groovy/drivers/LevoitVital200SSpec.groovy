package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitVital200S.groovy (Levoit Vital 200S Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  — 2-arg update(status, nightLight) signature exists and is callable
 *   Envelope        — defensive peel-loop in applyStatus handles both single-wrap and double-wrap
 *   Bug Pattern #3  — double-wrapped envelope is correctly peeled; device fields reached via loop
 *   Bug Pattern #4  — setLevel uses {levelIdx, levelType, manualSpeedLevel}, NOT {level, id, type}
 *   Bug Pattern #5  — manual mode is set via setLevel, NOT via setPurifierMode(workMode:"manual")
 *   Bug Pattern #6  — speed reports "off" when powerSwitch=0, even if manualSpeedLevel != 255
 *   Bug Pattern #7  — info HTML uses local variables, not device.currentValue()
 *   Bug Pattern #12 — pref-seed fires when descriptionTextEnable is null, preserves false
 *   Happy path      — full applyStatus from LAP-V201S.yaml emits expected events (canonical values)
 *   Bug Pattern #25 — setChildLock/setDisplay('ON') uppercase sends correct payload (1 not 0),
 *                      C3 gate works correctly with uppercase input, emitted event is lowercase;
 *                      truthy inputs ('true','1') canonicalized to 'on' (not stored verbatim)
 */
class LevoitVital200SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitVital200S.groovy"
    }

    @Override
    Map defaultSettings() {
        // descriptionTextEnable null simulates first-install-before-Save state
        // that triggers the pref-seed path.
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a single-wrapped purifier status response (device on, manual, speed 2)"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given: "a single-wrapped purifier status response"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Envelope dereference (single-wrap for Vital 200S purifier)
    // -------------------------------------------------------------------------

    def "applyStatus dereferences status.result to reach device fields"() {
        given: "a status map as the parent passes it: {code:0, result:{<device fields>}}"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        // Parent passes resp.data.result to child.update().
        // For purifiers, resp.data.result = { code:0, result:{device fields}, traceId:... }
        def status = v2StatusEnvelope(deviceData)

        when: "applyStatus is called with the single-wrapped envelope"
        driver.applyStatus(status)

        then: "device fields were reached and events emitted (switch=on confirms dereference succeeded)"
        lastEventValue("switch") == "on"
        // PM2.5 is a device-field — reaching it proves the dereference worked (canonical value is 3)
        lastEventValue("pm25") == 3
    }

    def "applyStatus envelope peel handles double-wrapped responses (Bug Pattern #3)"() {
        // LevoitVital200S.groovy has a defensive while-loop peel matching LevoitSuperior6000S.groovy.
        // This test passes a double-wrapped envelope (humidifier shape applied to purifier data)
        // and asserts that the driver correctly reaches the device fields via the peel loop.
        //
        // Double-wrap shape: status = {code, result: {code, result: {device fields}, traceId}}
        // Before the fix, r = status?.result yielded {code, result:{device fields}} — the inner
        // envelope — so powerSwitch was not found and switch stayed "off".
        // After the fix, the peel loop unwraps one more level and device fields ARE reached.
        given: "a double-wrapped envelope (humidifier shape applied to purifier data)"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        // humidifierStatusEnvelope wraps as: {code:0, result:{code:0, result:{device fields}}}
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when: "applyStatus receives a double-wrapped envelope"
        driver.applyStatus(doubleWrapped)

        then: "device fields ARE correctly reached via peel loop -- switch=on, pm25 reached"
        lastEventValue("switch") == "on"
        // PM2.5 is a device-level field; reaching it confirms the peel worked (canonical value is 3)
        lastEventValue("pm25") == 3
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when: "applyStatus is called with null"
        driver.applyStatus(null)

        then: "no exception thrown -- Elvis-with-safe-navigate guards null status arg"
        noExceptionThrown()
        // switch still gets emitted as "off" because powerSwitch defaults to 0 (null)
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #4: setLevel uses correct V201S field names
    // -------------------------------------------------------------------------

    def "setLevel sends {levelIdx, levelType, manualSpeedLevel} field names (Bug Pattern #4)"() {
        given: "device is on (needed for setSpeedLevel to proceed)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(50) is called — maps to speed level 3"
        driver.setLevel(50)

        then: "sendBypassRequest was called with correct V201S field names"
        // The last setLevel call (not the potential setMode call) should have levelIdx
        def setLevelReq = testParent.allRequests.find { it.method == "setLevel" }
        setLevelReq != null
        setLevelReq.data.containsKey("levelIdx")
        setLevelReq.data.containsKey("levelType")
        setLevelReq.data.containsKey("manualSpeedLevel")
        // Verify field name ABSENCE as well — no legacy Core-line names
        !setLevelReq.data.containsKey("id")
        !setLevelReq.data.containsKey("type")
        !setLevelReq.data.containsKey("level")
    }

    def "setSpeed sends {levelIdx, levelType, manualSpeedLevel} for manual speeds (Bug Pattern #4)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        // Set mode to manual so setSpeed goes through the setSpeedLevel path
        driver.state.mode = "manual"

        when: "setSpeed('high') is called"
        driver.setSpeed("high")

        then: "setLevel request uses V201S canonical field names"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.levelIdx == 0
        req.data.levelType == "wind"
        req.data.manualSpeedLevel instanceof Integer
        req.data.manualSpeedLevel >= 1
        req.data.manualSpeedLevel <= 4
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #5: manual mode via setLevel, not setPurifierMode
    // -------------------------------------------------------------------------

    def "setMode('manual') sends a setLevel call, NOT setPurifierMode (Bug Pattern #5)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        driver.state.speed = "low"

        when: "setMode('manual') is called"
        driver.setMode("manual")

        then: "only setLevel was sent, not setPurifierMode with workMode=manual"
        def setPurifierCalls = testParent.allRequests.findAll { it.method == "setPurifierMode" }
        def setLevelCalls    = testParent.allRequests.findAll { it.method == "setLevel" }
        setLevelCalls.size() >= 1
        // No setPurifierMode with workMode:manual — that call returns inner code -1 on V201S
        def badCall = setPurifierCalls.find { it.data?.workMode == "manual" }
        badCall == null
    }

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — setLevel request sent with manualSpeedLevel"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("manualSpeedLevel")
    }

    def "setMode('auto') sends setPurifierMode with workMode='auto' (Bug Pattern #5 complement)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setMode('auto') is called"
        driver.setMode("auto")

        then: "setPurifierMode with workMode=auto was sent"
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req != null
        req.data.workMode == "auto"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: speed=off when powerSwitch=0
    // -------------------------------------------------------------------------

    def "applyStatus emits speed='off' when powerSwitch=0 even if manualSpeedLevel != 255 (Bug Pattern #6)"() {
        given: "device is off but last manual speed was 3 (not 255)"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_off as Map
        // device_off fixture: powerSwitch=0, fanSpeedLevel=255, manualSpeedLevel=3
        assert deviceData.powerSwitch == 0
        assert deviceData.manualSpeedLevel == 3 : "fixture must have non-255 manualSpeedLevel to test #6"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "speed is 'off', NOT the last manual speed value"
        lastEventValue("speed") == "off"
        lastEventValue("switch") == "off"
    }

    def "applyStatus emits actual speed string when powerSwitch=1 and mode=manual (Bug Pattern #6 complement)"() {
        given: "device is on, manual, speed level 2"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        assert deviceData.powerSwitch == 1
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "speed is a non-off string"
        lastEventValue("switch") == "on"
        lastEventValue("speed") != "off"
        lastEventValue("speed") != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #7: info HTML uses local variables, not device.currentValue()
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables not device.currentValue() race (Bug Pattern #7)"() {
        given: "fresh device with no prior events"
        // Verify no events pre-exist for 'airQuality' or 'pm25'
        assert testDevice.events.isEmpty()
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "applyStatus is called — sendEvent fires and info HTML is built"
        driver.applyStatus(status)

        then: "info HTML contains filter and PM2.5 values (proves local-var path, not currentValue)"
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Filter:")
        // PM2.5 value from canonical fixture is 3
        infoVal.contains("3")
        // Air quality label computed locally — canonical AQLevel=2 maps to "moderate"
        infoVal.contains("moderate") || infoVal.contains("Air Quality")
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null and sets it to true (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null (first install before Save)"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        when: "applyStatus is called (pref-seed is at the top of applyStatus)"
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        driver.applyStatus(v2StatusEnvelope(fixture.responses.device_on_manual_speed2 as Map))

        then: "updateSetting was called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite descriptionTextEnable when user has set it to false (Bug Pattern #12)"() {
        given: "user has explicitly set descriptionTextEnable=false"
        settings.descriptionTextEnable = false
        assert !state.prefsSeeded

        when:
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        driver.applyStatus(v2StatusEnvelope(fixture.responses.device_on_manual_speed2 as Map))

        then: "updateSetting NOT called for descriptionTextEnable (user setting preserved)"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        // But state.prefsSeeded is still set (seed ran, just didn't overwrite)
        state.prefsSeeded == true
    }

    def "pref-seed only fires once even across multiple applyStatus calls (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_speed2 as Map)

        when: "applyStatus is called twice"
        driver.applyStatus(status)
        driver.applyStatus(status)

        then: "updateSetting for descriptionTextEnable was called exactly once"
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from fixture
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_manual_speed2 fixture emits expected events"() {
        given: "canonical device-on, manual mode, speed 2 fixture"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "petMode is off"
        lastEventValue("petMode") == "off"

        and: "speed is sleep (canonical fanSpeedLevel=1 maps to 'sleep' via mapIntegerToSpeed)"
        lastEventValue("speed") == "sleep"

        and: "filter life is 80 (canonical PurifierDefaults.filter_life=80)"
        lastEventValue("filter") == 80

        and: "PM2.5 is 3 (canonical PurifierDefaults.air_quality_value_pm25=3)"
        lastEventValue("pm25") == 3

        and: "airQualityIndex is 2 (canonical PurifierDefaults.air_quality_enum=AirQualityLevel.GOOD=2)"
        lastEventValue("airQualityIndex") == 2

        and: "lightDetection is off (canonical lightDetectionSwitch=0)"
        lastEventValue("lightDetection") == "off"

        and: "display is on (screenSwitch=1)"
        lastEventValue("display") == "on"

        and: "childLock is on (canonical childLockSwitch=1)"
        lastEventValue("childLock") == "on"

        and: "errorCode is 0"
        lastEventValue("errorCode") == 0

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus happy path from device_on_auto_mode fixture emits auto-specific events"() {
        given: "canonical device-on, auto mode fixture"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_auto_mode as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode is auto"
        lastEventValue("mode") == "auto"

        and: "speed is 'auto' (device in auto mode)"
        lastEventValue("speed") == "auto"

        and: "petMode is off"
        lastEventValue("petMode") == "off"

        and: "switch is on"
        lastEventValue("switch") == "on"
    }

    def "applyStatus with pet mode fixture sets petMode=on"() {
        given: "canonical pet mode fixture"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_pet_mode as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "pet"
        lastEventValue("petMode") == "on"
    }

    def "filter life critically low (<10%) logs INFO when transitioning below threshold"() {
        given: "prior filter life was 15% (above critical)"
        settings.descriptionTextEnable = true
        state.lastFilterLife = 15

        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_filter_low as Map  // filterLifePercent=8
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "INFO log was emitted about critically low filter"
        testLog.infos.any { it.contains("critically low") || it.contains("Filter") }
    }

    def "autoPreference fields are populated from nested autoPreference map"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LAP-V201S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "autoPreference attribute is set to fixture value 'default'"
        lastEventValue("autoPreference") == "default"
        lastEventValue("roomSize") == 0
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

    // -------------------------------------------------------------------------
    // Bug Pattern #21: online attribute declared in metadata
    // -------------------------------------------------------------------------

    def "metadata declares 'online' attribute (BP21 — parent writes it; no child setter needed)"() {
        // BP21: the parent marks the child offline/online via sendEvent(name:"online", ...).
        // For this to work without Hubitat rejecting the event, the attribute must be
        // declared in the driver's metadata. This test asserts it is present by verifying
        // that sendEvent with name:"online" is accepted (no exception) and is retrievable.
        when: "parent marks device offline via sendEvent"
        testDevice.sendEvent(name: "online", value: "false",
            descriptionText: "Test offline mark")

        then: "event was captured and value is retrievable"
        testDevice.currentValue("online") == "false"

        when: "parent marks device back online"
        testDevice.sendEvent(name: "online", value: "true",
            descriptionText: "Test recovery")

        then:
        testDevice.currentValue("online") == "true"
        noExceptionThrown()
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

        when: "setLevel(50) is called on an off device"
        driver.setLevel(50)

        then: "on() was called — setSwitch with powerSwitch=1 was sent"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP23: setLevel(0) calls off() and does not send a speed command"() {
        given: "default state"

        when: "setLevel(0) is called"
        driver.setLevel(0)

        then: "setSwitch with powerSwitch=0 was sent (off() was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 }
        req != null

        and: "no setLevel API call was made"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BP24-C: cycleSpeed() from off-state turns the device on
    // -------------------------------------------------------------------------

    def "BP24-C: cycleSpeed() from off-state turns the device on before sending speed command"() {
        // BP24-C fix: cycleSpeed() previously had `if (device.currentValue("switch") != "on") on()`
        // which lacked the `!state.turningOn` re-entrance flag — risk of recursive on() chain
        // under concurrent calls. Now uses ensureSwitchOn() (from LevoitChildBase) which has
        // the correct guard: `!state.turningOn && device.currentValue("switch") != "on" → on()`.
        // (Distinct from BP24-A's `state.switch` dead-branch shape on the Core line.)
        given: "device is off and turningOn flag is clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove('turningOn')

        when: "cycleSpeed is invoked on an off device"
        driver.cycleSpeed()

        then: "setSwitch with powerSwitch=1 was sent (the on() call fired)"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null

        and: "no errors were logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BP24: invalid speed from off-state must NOT auto-power the device on
    // -------------------------------------------------------------------------

    def "BP24: setSpeed('turbo') on an off device does NOT turn it on and sends no speed command"() {
        // Regression guard: the invalid-speed reject must run BEFORE ensureSwitchOn().
        // Pre-fix, ensureSwitchOn() ran first, so an unrecognized speed powered the device
        // on and then mapSpeedToInteger's default→low (return 2) sent a real low-speed command.
        // NON-VACUITY: this assertion goes RED if the reject is moved back after
        // ensureSwitchOn() (the pre-fix ordering) — the off device would then receive a
        // setSwitch powerSwitch=1 AND a setLevel command, failing both `then` blocks.
        given: "device is off and turningOn flag is clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove('turningOn')

        when: "setSpeed('turbo') is called on an off device"
        driver.setSpeed("turbo")

        then: "no on() — no setSwitch powerSwitch=1 was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null

        and: "no setLevel speed command was sent"
        testParent.allRequests.find { it.method == "setLevel" } == null

        and: "a warn was logged naming the method"
        testLog.warns.any { it.contains("setSpeed") }
    }

    // -------------------------------------------------------------------------
    // C3: state-change gate — setChildLock and setDisplay
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
    // BP18: null-guard on Vital lib setters
    // -------------------------------------------------------------------------

    def "BP18: setMode(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setMode(null)

        then: "no setPurifierMode API call was made"
        testParent.allRequests.find { it.method == "setPurifierMode" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setMode") }
    }

    def "BP18: setSpeed(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setSpeed(null)

        then: "no setLevel API call was made"
        testParent.allRequests.find { it.method == "setLevel" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setSpeed") }
    }

    def "BP18: setDisplay(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setDisplay(null)

        then: "no setDisplay API call was made"
        testParent.allRequests.find { it.method == "setDisplay" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setDisplay") }
    }

    def "BP18: setChildLock(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setChildLock(null)

        then: "no setChildLock API call was made"
        testParent.allRequests.find { it.method == "setChildLock" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setChildLock") }
    }

    def "BP18: setAutoPreference(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setAutoPreference(null)

        then: "no setAutoPreference API call was made"
        testParent.allRequests.find { it.method == "setAutoPreference" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setAutoPreference") }
    }

    // -------------------------------------------------------------------------
    // Fix 1 regression guard: setAutoPreference field name (autoPreference, not autoPreferenceType)
    // -------------------------------------------------------------------------

    def "setAutoPreference('default') sends payload with field 'autoPreference' NOT 'autoPreferenceType'"() {
        // Regression guard: before this fix, the driver sent {autoPreferenceType: pref, ...}
        // which the VeSync cloud silently accepted but applied no preference change.
        // pyvesync VeSyncAirBaseV2.set_auto_preference() sends {'autoPreference': preference, ...}.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setAutoPreference("default")

        then: "setAutoPreference API call was made"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "payload contains 'autoPreference' (correct pyvesync field name)"
        req.data.containsKey("autoPreference")
        req.data.autoPreference == "default"

        and: "payload does NOT contain 'autoPreferenceType' (wrong field name from before fix)"
        !req.data.containsKey("autoPreferenceType")
    }

    def "setRoomSize(400) sends payload with field 'autoPreference' NOT 'autoPreferenceType'"() {
        // Same regression guard as above — setRoomSize calls setAutoPreference endpoint with
        // the current preference type and new room size. Must use 'autoPreference', not 'autoPreferenceType'.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.autoPreference = "efficient"

        when:
        driver.setRoomSize(400)

        then: "setAutoPreference API call was made (setRoomSize uses the same endpoint)"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req != null

        and: "payload contains 'autoPreference' (not 'autoPreferenceType')"
        req.data.containsKey("autoPreference")
        req.data.autoPreference == "efficient"   // reads from state.autoPreference
        req.data.roomSize == 400

        and: "payload does NOT contain 'autoPreferenceType'"
        !req.data.containsKey("autoPreferenceType")
    }

    // -------------------------------------------------------------------------
    // BP18 regression guard: setLevel(null) does not throw NPE
    // -------------------------------------------------------------------------

    def "BP18/BP28: setLevel(null) does not throw and sends no speed command (null ignored)"() {
        // Regression guard: before BP18, null < 20 threw NPE (Groovy null arithmetic),
        // swallowed silently by the sandbox. Under BP28, parseLevelOrNull(null) -> null ->
        // ignore (device unchanged) — no off(), no speed command.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(null)

        then: "no NPE thrown"
        noExceptionThrown()

        and: "no setLevel (speed) API call and no off() — null is ignored"
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
    }

    def "BP18: setPetMode(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setPetMode(null)

        then: "no setPurifierMode API call was made"
        testParent.allRequests.find { it.method == "setPurifierMode" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setPetMode") }
    }

    def "BP18: setRoomSize(null) returns silently with logWarn (no API call)"() {
        when:
        driver.setRoomSize(null)

        then: "no setAutoPreference API call was made"
        testParent.allRequests.find { it.method == "setAutoPreference" } == null

        and: "logWarn fired with the method name"
        testLog.warns.any { it.contains("setRoomSize") }
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #25: C3 gate case-sensitivity — uppercase "ON"/"OFF" input
    // These specs MUST FAIL on pre-fix code (raw param compared) and
    // PASS on post-fix code (toLowerCase() normalization added).
    // -------------------------------------------------------------------------

    def "BP25: setChildLock('ON') uppercase makes the API call and sends childLockSwitch:1 (not 0)"() {
        // Pre-fix: ("ON" == "on") is false → childLockSwitch:0 sent (unlock instead of lock).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → childLockSwitch:1 (correct).
        given: "childLock is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "off"])

        when: "setChildLock is called with uppercase 'ON'"
        driver.setChildLock("ON")

        then: "API call was made (gate correctly passed — 'off' != 'on')"
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null

        and: "payload carries childLockSwitch:1 (lock), NOT 0 (unlock)"
        req.data.childLockSwitch == 1

        and: "emitted event value is lowercase 'on'"
        lastEventValue("childLock") == "on"
    }

    def "BP25: setChildLock('ON') when childLock is already 'on' is a no-op (C3 gate works with uppercase)"() {
        // Pre-fix: ("on" == "ON") is false → gate bypassed, redundant API call made.
        // Post-fix: toLowerCase() yields "on" == "on" → gate fires, no API call.
        given: "childLock is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "on"])

        when: "setChildLock called with uppercase 'ON' (idempotent call)"
        driver.setChildLock("ON")

        then: "no API call was made (C3 gate worked correctly)"
        testParent.allRequests.find { it.method == "setChildLock" } == null
    }

    def "BP25: setDisplay('ON') uppercase makes the API call and sends screenSwitch:1 (not 0)"() {
        // Pre-fix: ("ON" == "on") is false → screenSwitch:0 sent (turn off instead of on).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → screenSwitch:1 (correct).
        given: "display is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "off"])

        when: "setDisplay is called with uppercase 'ON'"
        driver.setDisplay("ON")

        then: "API call was made"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null

        and: "payload carries screenSwitch:1 (on), NOT 0 (off)"
        req.data.screenSwitch == 1

        and: "emitted event value is lowercase 'on'"
        lastEventValue("display") == "on"
    }

    def "BP25: setDisplay('ON') when display is already 'on' is a no-op (C3 gate works with uppercase)"() {
        // Pre-fix: ("on" == "ON") is false → gate bypassed, redundant API call made.
        // Post-fix: toLowerCase() yields "on" == "on" → gate fires, no API call.
        given: "display is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "on"])

        when: "setDisplay called with uppercase 'ON' (idempotent call)"
        driver.setDisplay("ON")

        then: "no API call was made (C3 gate worked correctly)"
        testParent.allRequests.find { it.method == "setDisplay" } == null
    }

    // ---- BP25: setLightDetection (Vital200S-only setter) ----

    def "BP25: setLightDetection('ON') sends lightDetectionSwitch:1, not 0 (BP25 regression guard)"() {
        // Pre-fix: (onOff=="on") where onOff="ON" evaluates false → lightDetectionSwitch:0 (wrong).
        // Post-fix: toLowerCase() normalizes "ON"→"on" → lightDetectionSwitch:1.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLightDetection("ON")

        then: "setLightDetection sent with lightDetectionSwitch:1 (enabled)"
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 1

        and: "emitted event value is lowercase 'on'"
        lastEventValue("lightDetection") == "on"
    }

    def "BP25: setLightDetection('OFF') sends lightDetectionSwitch:0 (BP25 regression guard)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLightDetection("OFF")

        then: "setLightDetection sent with lightDetectionSwitch:0 (disabled)"
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 0

        and: "emitted event value is lowercase 'off'"
        lastEventValue("lightDetection") == "off"
    }

    def "BP18: setLightDetection(null) returns silently with no API call (BP18 null-guard)"() {
        when:
        driver.setLightDetection(null)

        then:
        noExceptionThrown()
        testParent.allRequests.find { it.method == "setLightDetection" } == null
        testLog.warns.any { it.contains("setLightDetection") }
    }

    // -------------------------------------------------------------------------
    // C3 idempotency gate — setLightDetection must not re-call API when the value
    // already matches the current lightDetection attribute (Vital 200S)
    // -------------------------------------------------------------------------

    def "C3: setLightDetection with already-current value makes no hubBypass call (Vital 200S)"() {
        // Regression guard: before this fix, setLightDetection had no C3 gate, so calling
        // setLightDetection("on") when light detection was already on fired a redundant cloud
        // call on every Rule Machine evaluation.
        // This test FAILS on pre-fix code (gate absent → allRequests is non-empty)
        // and PASSES with the gate present.
        given: "lightDetection attribute is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "lightDetection", value: "on"])

        when: "setLightDetection called with the same value"
        driver.setLightDetection("on")

        then: "no setLightDetection API call was made (C3 gate suppressed it)"
        testParent.allRequests.findAll { it.method == "setLightDetection" }.isEmpty()
        noExceptionThrown()
    }

    def "C3: setLightDetection with different value does make a hubBypass call (Vital 200S)"() {
        // Confirm the gate is a state-change guard, not a complete no-op.
        given: "lightDetection attribute is 'off'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "lightDetection", value: "off"])

        when: "setLightDetection called with 'on' (different from current)"
        driver.setLightDetection("on")

        then: "setLightDetection API call was made"
        testParent.allRequests.any { it.method == "setLightDetection" }
    }

    // -----------------------------------------------------------------------
    // BP26 + BP18: setTimer (VitalPurifierLib) — numeric coercion + null-guard
    // -----------------------------------------------------------------------

    def "BP18+BP26: setTimer(null) returns silently with no API call"() {
        // VitalPurifierLib.setTimer previously had neither requireNotNull nor safeIntArg.
        // Post-fix: requireNotNull rejects null; safeIntArg handles non-numeric strings.
        when:
        driver.setTimer(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "addTimerV2" }.isEmpty()
    }

    def "BP26: setTimer('#badInput') does not throw and does not make an addTimerV2 API call (fallback=0 → cancelTimer)"() {
        // safeIntArg maps "abc" and "" to 0; true maps to 0 ("true" is not numeric).
        // 0 → n<=0 guard → cancelTimer() path; no addTimerV2 call.
        when:
        driver.setTimer(badInput)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "addTimerV2" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setTimer('5.7') does not throw and makes an addTimerV2 API call with truncated value (5 minutes)"() {
        // safeIntArg("5.7") → 5 (truncation). 5 > 0 → addTimerV2 called with clkSec=300 (5×60).
        when:
        driver.setTimer("5.7")

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "addTimerV2" }
        req != null
        req.data.tmgEvt?.clkSec == 300
    }

    // -----------------------------------------------------------------------
    // BP25-truthy: truthy-input assertions — regression guards for canon ternary.
    // "ON" tests above prove toLowerCase() normalization.  These prove the canon
    // ternary: reverting sendEvent(canon) → sendEvent(val) would store "true" or
    // "1" verbatim, failing these assertions.
    // -----------------------------------------------------------------------

    def "BP25-truthy: setChildLock('true') sends childLockSwitch:1 and emits childLock='on' (Vital 200S)"() {
        // Pre-canon: v="true"; sendEvent(value:"true"). Post-canon: canon="on"; sendEvent(value:"on").
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "off"])

        when:
        driver.setChildLock("true")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1
        lastEventValue("childLock") == "on"
    }

    def "BP25-truthy: setChildLock('1') sends childLockSwitch:1 and emits childLock='on' (Vital 200S)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "childLock", value: "off"])

        when:
        driver.setChildLock("1")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1
        lastEventValue("childLock") == "on"
    }

    def "BP25-truthy: setDisplay('true') sends screenSwitch:1 and emits display='on' (Vital 200S)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "off"])

        when:
        driver.setDisplay("true")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1
        lastEventValue("display") == "on"
    }

    def "BP25-truthy: setDisplay('1') sends screenSwitch:1 and emits display='on' (Vital 200S)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "display", value: "off"])

        when:
        driver.setDisplay("1")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1
        lastEventValue("display") == "on"
    }

    def "BP25-truthy: setLightDetection('true') sends lightDetectionSwitch:1 and emits lightDetection='on' (Vital 200S)"() {
        // setLightDetection is a Vital200S per-driver method, not in VitalPurifierLib.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLightDetection("true")

        then:
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 1
        lastEventValue("lightDetection") == "on"
    }

    def "BP25-truthy: setLightDetection('1') sends lightDetectionSwitch:1 and emits lightDetection='on' (Vital 200S)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLightDetection("1")

        then:
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 1
        lastEventValue("lightDetection") == "on"
    }

    // -------------------------------------------------------------------------
    // B1 regression guard: resetFilter() uses method name "resetFilter" (not "resetFilterLife")
    // -------------------------------------------------------------------------

    def "resetFilter() sends bypass method 'resetFilter' (B1 regression guard)"() {
        // Regression guard: pyvesync VeSyncAirBaseV2.reset_filter() sends {"method":"resetFilter"}.
        // An earlier draft of LevoitVitalPurifierLib used "resetFilterLife" (wrong name) which
        // VeSync silently rejects — the filter life counter was never actually reset.
        // This test FAILS on pre-fix code (method name is "resetFilterLife")
        // and PASSES on the corrected implementation ("resetFilter").
        // Both-ways proof: orchestrator-owned.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.resetFilter()

        then: "the bypass request was sent with method 'resetFilter' (not 'resetFilterLife')"
        def req = testParent.allRequests.find { it.method == "resetFilter" }
        req != null

        and: "no 'resetFilterLife' request was sent (wrong method name rejected by VeSync)"
        testParent.allRequests.find { it.method == "resetFilterLife" } == null

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // NIT 4: setAutoPreference string-enum guard upgraded to requireNonEmptyEnum
    // setAutoPreference(pref): the `pref` param is a user-callable string enum.
    // Empty "" slipped past the former requireNotNull guard and reached the cloud.
    // -------------------------------------------------------------------------

    def "NIT 4: setAutoPreference('') silently exits without API call (empty-string RM blank slot) (Vital 200S/VitalPurifierLib)"() {
        // Pre-fix: requireNotNull("") returned true; "" was sent as autoPreference to the cloud.
        // Post-fix: requireNonEmptyEnum("") returns false silently; no API call.
        given:
        testParent.allRequests.clear()
        int warnsBefore = testLog.warns.size()

        when:
        driver.setAutoPreference("")

        then: "no API call"
        testParent.allRequests.find { it.method == "setAutoPreference" } == null

        and: "no WARN logged (empty string is the RM blank-slot path — silent)"
        testLog.warns.size() == warnsBefore

        and: "no exception thrown"
        noExceptionThrown()
    }

    def "NIT 4: setAutoPreference(null) still warns (null path unchanged) (Vital 200S/VitalPurifierLib)"() {
        // Null path must still warn — regression guard on the existing BP18 behavior.
        given:
        testParent.allRequests.clear()

        when:
        driver.setAutoPreference(null)

        then: "WARN logged for null"
        testLog.warns.any { it.toLowerCase().contains("setautopreference") }

        and: "no API call"
        testParent.allRequests.find { it.method == "setAutoPreference" } == null
    }
}
