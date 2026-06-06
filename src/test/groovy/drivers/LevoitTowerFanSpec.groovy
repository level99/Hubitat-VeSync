package drivers

import spock.lang.Unroll
import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitTowerFan.groovy (Levoit Tower Fan LTF-F422S).
 *
 * Covers:
 *   Bug Pattern #1  — 3-signature update() method set (0-arg/1-arg/2-arg all callable)
 *   Bug Pattern #3  — defensive envelope peel: single-wrap, double-wrap, null all handled
 *   Bug Pattern #4  — setLevel uses {levelIdx, levelType, manualSpeedLevel}, NOT {id, type, level}
 *                     Bonus: setSpeed(13) and setSpeed(0) rejected with logError + no API call
 *   Bug Pattern #6  — speed="off" when powerSwitch=0, even if manualSpeedLevel != 0
 *   Bug Pattern #12 — pref-seed: null->true on first applyStatus; preserves false; fires once
 *   Happy path      — full applyStatus from canonical fixture emits expected events for all attributes
 *   Switch payload  — on()/off() produce {powerSwitch:1|0, switchIdx:0}; humidifier shape rejected
 *   Mode reverse-mapping (HA finding #d) — setMode("sleep") sends {workMode:"advancedSleep"};
 *                     response "advancedSleep" emits mode="sleep"; other modes pass through unchanged
 *   Temperature /10 (HA finding #1) — fixture temperature=717 emits temperature event = 71.7
 *   displayingType read-only (HA finding #5) — response displayingType=1 emits attribute;
 *                     no setDisplayingType method exists on the driver
 *   Toggle pattern  (NIT 1) — state.lastSwitchSet seeded by on()/off(); read by toggle(); fallback
 *   Oscillation / mute / display payloads — each setter sends correct payload field
 *   Timer set/cancel — setTimer(60,"off") sends {action:'off',total:60}; cancelTimer sends clearTimer
 *   sleepPreferenceType — read-only attribute event (setSleepPreference DEFERRED to v2.5+)
 */
class LevoitTowerFanSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitTowerFan.groovy"
    }

    @Override
    Map defaultSettings() {
        // descriptionTextEnable null simulates first-install-before-Save state
        // that triggers the pref-seed path (Bug Pattern #12).
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 3-signature update() methods
    // -------------------------------------------------------------------------

    def "update() no-arg self-fetch calls getTowerFanStatus API"() {
        given: "default state"

        when: "update() no-arg is called"
        driver.update()

        then: "getTowerFanStatus was sent to the API"
        def req = testParent.allRequests.find { it.method == "getTowerFanStatus" }
        req != null
        noExceptionThrown()
    }

    def "update(status) 1-arg parent callback is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Tower Fan status response"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status, nightLight) 2-arg parent callback is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Tower Fan status response"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, null) -- Tower Fan has no nightlight hardware"
        def result = driver.update(status, null)

        then: "method exists and returns without MissingMethodException"
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: Defensive envelope peel
    // -------------------------------------------------------------------------

    def "applyStatus handles single-wrapped (purifier-style) envelope"() {
        given: "single-wrapped status: {code:0, result:{device fields}}"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields reached -- switch=on confirms dereference succeeded"
        lastEventValue("switch") == "on"
        // fanSpeedLevel=5 is a device field; reaching it proves the dereference
        lastEventValue("speed") != null
        lastEventValue("speed") != "off"
    }

    def "applyStatus handles double-wrapped (humidifier-style) envelope (Bug Pattern #3)"() {
        given: "double-wrapped: {code:0, result:{code:0, result:{device fields}}}"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then: "peel loop unwraps both layers; switch=on confirms device fields were reached"
        lastEventValue("switch") == "on"
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when: "null is passed -- e.g. transient API failure returning no data"
        driver.applyStatus(null)

        then: "no exception thrown; switch defaults to off (powerSwitch null = 0)"
        noExceptionThrown()
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #4: setLevel field names (V2-API names, not Core-line names)
    // -------------------------------------------------------------------------

    def "setSpeed(7) sends setLevel with {levelIdx:0, levelType:'wind', manualSpeedLevel:7} (Bug Pattern #4)"() {
        given: "device is on"
        settings.descriptionTextEnable = false

        when: "setSpeed(7) -- raw numeric speed"
        driver.setSpeed(7)

        then: "setLevel request uses V2-API field names"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.levelIdx    == 0
        req.data.levelType   == "wind"
        req.data.manualSpeedLevel == 7

        and: "legacy Core-line field names are absent"
        !req.data.containsKey("id")
        !req.data.containsKey("type")
        !req.data.containsKey("level")
    }

    def "setSpeed(13) is rejected with logError and no API call"() {
        given: "default state"

        when: "out-of-range high speed requested"
        driver.setSpeed(13)

        then: "no setLevel API request was made"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "an error was logged"
        testLog.errors.any { it.contains("13") || it.contains("invalid") || it.contains("must be") }
    }

    def "setSpeed(0) is rejected with logError and no API call"() {
        given: "default state"

        when: "out-of-range low speed requested"
        driver.setSpeed(0)

        then: "no setLevel API request was made"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "an error was logged"
        testLog.errors.any { it.contains("0") || it.contains("invalid") || it.contains("must be") }
    }

    def "BP24: setSpeed('turbo') on an off device does NOT turn it on and sends no speed command"() {
        // Regression guard: the unknown-enum reject must run BEFORE ensureSwitchOn().
        // Pre-fix, ensureSwitchOn() ran first, so setSpeed('turbo') from off powered the
        // device on and then no-op'd at the fanControlEnumToLevel==null reject -- a phantom
        // power-on with no speed set.
        // NON-VACUITY: this assertion goes RED if ensureSwitchOn() is moved back ahead of
        // the level-resolution/reject (the pre-fix ordering) — the off device would then
        // receive a setSwitch powerSwitch=1, failing the first `then` block.
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove('turningOn')

        when: "an unrecognized enum speed is requested on an off device"
        driver.setSpeed("turbo")

        then: "no on() — no setSwitch powerSwitch=1 was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null

        and: "no setLevel speed command was sent"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "an error was logged for the unknown enum"
        testLog.errors.any { it.contains("turbo") || it.contains("unknown") }
    }

    def "off() re-entrance guard: second call while turningOff=true is a no-op"() {
        // Regression guard: FanLib off() symmetric re-entrance guard (state.turningOff).
        // Defensive symmetry with on(); a re-entrant off() must short-circuit.
        given:
        settings.descriptionTextEnable = false
        state.turningOff = true

        when:
        driver.off()

        then: "no setSwitch API call because re-entrance was blocked"
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        noExceptionThrown()
    }

    def "setSpeed('on') calls on() -- Hubitat FanControl capability convention (Theme A)"() {
        // Hubitat FanControl.setSpeed accepts 'on' as a valid enum value meaning 'resume at
        // prior/default speed'. Previously this fell through to the unknown-enum error path.
        given: "default state"

        when: "setSpeed('on') is called via FanControl enum path"
        driver.setSpeed("on")

        then: "setSwitch with powerSwitch=1 was sent (on() was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "setLevel(0) calls off() -- SwitchLevel convention (Theme B)"() {
        // Hubitat SwitchLevel says setLevel(0) means 'off' (Z-Wave dimmer convention).
        // Previously 0% mapped to levelFromPercent(0)=1 and turned the device on at level 1.
        given: "default state"

        when: "setLevel(0) is called"
        driver.setLevel(0)

        then: "setSwitch with powerSwitch=0 was sent (off() was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0

        and: "no setLevel API call was made (level 1 must NOT be sent)"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 0

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "setLevel(50, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given: "default state"
        settings.descriptionTextEnable = false

        when: "setLevel is called with two args (level=50, duration=30)"
        driver.setLevel(50, 30)

        then: "same API call as setLevel(50) — setLevel API request sent with manualSpeedLevel"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.containsKey("manualSpeedLevel")

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: speed="off" when powerSwitch=0
    // -------------------------------------------------------------------------

    def "applyStatus emits speed='off' when powerSwitch=0 even if manualSpeedLevel != 0 (Bug Pattern #6)"() {
        given: "device is off but manualSpeedLevel=5 (last-set level still in response)"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_off as Map
        assert deviceData.powerSwitch == 0
        assert deviceData.manualSpeedLevel == 5 : "fixture must have non-zero manualSpeedLevel to exercise BP#6"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "speed='off' (NOT the last-set level string) when device is off"
        lastEventValue("speed") == "off"
        lastEventValue("switch") == "off"
    }

    def "applyStatus emits actual speed enum when powerSwitch=1 (Bug Pattern #6 complement)"() {
        given: "device is on, normal mode, speed 5"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.powerSwitch == 1
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "speed is a non-off FanControl enum string"
        lastEventValue("switch") == "on"
        lastEventValue("speed") != "off"
        lastEventValue("speed") != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null and sets it to true (Bug Pattern #12)"() {
        given: "first install -- descriptionTextEnable not yet committed by Hubitat"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        when:
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        driver.applyStatus(v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map))

        then: "updateSetting was called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite descriptionTextEnable when user set it to false (Bug Pattern #12)"() {
        given: "user has explicitly set descriptionTextEnable=false"
        settings.descriptionTextEnable = false
        assert !state.prefsSeeded

        when:
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        driver.applyStatus(v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map))

        then: "updateSetting NOT called for descriptionTextEnable (user choice preserved)"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    def "pref-seed fires only once across multiple applyStatus calls (Bug Pattern #12)"() {
        given: "descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map)

        when: "applyStatus called twice"
        driver.applyStatus(status)
        driver.applyStatus(status)

        then: "updateSetting for descriptionTextEnable called exactly once"
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from canonical fixture
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_normal_speed5 fixture emits expected events"() {
        given: "canonical device-on, normal mode, speed 5, oscillation on, mute off, display on"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is normal (no reverse-mapping needed for normal)"
        lastEventValue("mode") == "normal"

        and: "speed is a FanControl enum string in 'medium' bucket (fanSpeedLevel=5)"
        lastEventValue("speed") == "medium"

        and: "oscillation is on (oscillationState=1)"
        lastEventValue("oscillation") == "on"

        and: "mute is off (muteState=0)"
        lastEventValue("mute") == "off"

        and: "display is on (screenState=1)"
        lastEventValue("displayOn") == "on"

        and: "errorCode is 0"
        lastEventValue("errorCode") == 0

        and: "timerRemain is 0"
        lastEventValue("timerRemain") == 0

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus emits sleepPreferenceType from nested sleepPreference map"() {
        given: "fixture with sleepPreference.sleepPreferenceType=default"
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.sleepPreference?.sleepPreferenceType == "default"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "sleepPreferenceType attribute is emitted"
        lastEventValue("sleepPreferenceType") == "default"
    }

    def "applyStatus with null sleepPreference does not throw"() {
        given: "fixture with sleepPreference=null"
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_off as Map
        assert deviceData.sleepPreference == null
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no exception thrown"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Switch payload -- on()/off() use purifier-style {powerSwitch, switchIdx}
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {powerSwitch:1, switchIdx:0}"() {
        given: "default state"

        when:
        driver.on()

        then: "setSwitch request has correct purifier-style payload"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
        req.data.switchIdx == 0

        and: "humidifier-style payload fields absent"
        !req.data.containsKey("enabled")
        !req.data.containsKey("id")
    }

    def "off() sends setSwitch with {powerSwitch:0, switchIdx:0}"() {
        given: "default state"

        when:
        driver.off()

        then: "setSwitch request has correct payload"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
        req.data.switchIdx == 0
    }

    def "on() seeds state.lastSwitchSet='on' and off() seeds 'off'"() {
        when: "on() is called"
        driver.on()

        then: "lastSwitchSet is 'on'"
        state.lastSwitchSet == "on"

        when: "off() is called"
        driver.off()

        then: "lastSwitchSet is 'off'"
        state.lastSwitchSet == "off"
    }

    // -------------------------------------------------------------------------
    // Mode reverse-mapping (HA finding #d)
    // -------------------------------------------------------------------------

    def "setMode('sleep') sends setTowerFanMode with workMode='advancedSleep' (HA finding #d)"() {
        given: "default state"

        when: "user calls setMode('sleep')"
        driver.setMode("sleep")

        then: "API receives 'advancedSleep' literal, NOT 'sleep'"
        def req = testParent.allRequests.find { it.method == "setTowerFanMode" }
        req != null
        req.data.workMode == "advancedSleep"

        and: "NOT the user-facing label"
        req.data.workMode != "sleep"
    }

    def "applyStatus with workMode='advancedSleep' emits mode='sleep' event (HA finding #d)"() {
        given: "response contains workMode='advancedSleep'"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_advanced_sleep as Map
        assert deviceData.workMode == "advancedSleep"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'sleep', NOT 'advancedSleep'"
        lastEventValue("mode") == "sleep"

        and: "definitely not the API literal"
        lastEventValue("mode") != "advancedSleep"
    }

    @Unroll
    def "setMode('#mode') sends setTowerFanMode with workMode='#mode' (no reverse-mapping)"() {
        given: "default state"

        when:
        driver.setMode(mode)

        then: "API receives the mode literal unchanged"
        def req = testParent.allRequests.find { it.method == "setTowerFanMode" }
        req != null
        req.data.workMode == mode

        where:
        mode << ["normal", "turbo"]
    }

    def "setMode sends setTowerFanMode, NOT setPurifierMode"() {
        given: "default state"

        when: "any mode is set"
        driver.setMode("normal")

        then: "method is setTowerFanMode, not setPurifierMode"
        def towerFanReqs = testParent.allRequests.findAll { it.method == "setTowerFanMode" }
        def purifierReqs = testParent.allRequests.findAll { it.method == "setPurifierMode" }
        towerFanReqs.size() >= 1
        purifierReqs.size() == 0
    }

    def "setMode with invalid mode logs error and makes no API call"() {
        given: "default state"

        when:
        driver.setMode("invalid-mode")

        then: "no API request was made"
        testParent.allRequests.isEmpty()

        and: "error was logged"
        testLog.errors.any { it.contains("invalid") || it.contains("must be") }
    }

    // -------------------------------------------------------------------------
    // BP24 SHOULD-ON: setMode from off-state turns the fan on (v2.9).
    // NON-VACUITY: deleting the ensureSwitchOn() line in setMode makes the on()
    // assertion go RED (no setSwitch powerSwitch=1 fires).
    // -------------------------------------------------------------------------

    def "BP24: setMode('auto') from off-state turns the fan on before the mode command"() {
        given: "fan is off, turningOn flag clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "setMode('auto') is called on an off fan"
        driver.setMode("auto")

        then: "on() fired — setSwitch with powerSwitch=1 was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null

        and: "the mode command (setTowerFanMode) was sent"
        testParent.allRequests.find { it.method == "setTowerFanMode" } != null
    }

    def "BP24: invalid mode on an off fan does NOT auto-power it on (validate-before-on)"() {
        given: "fan is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "an invalid mode is sent"
        driver.setMode("invalid-mode")

        then: "no on() fired (validation rejected before ensureSwitchOn)"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null
    }

    // -------------------------------------------------------------------------
    // Temperature divide-by-10 (HA finding #1)
    // -------------------------------------------------------------------------

    def "applyStatus divides temperature field by 10 before emitting (HA finding #1)"() {
        given: "fixture with temperature=717 (real-device idle capture = 71.7°F)"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.temperature == 717
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "temperature event value is 71.7 (717 / 10), NOT the raw 717"
        def tempVal = lastEventValue("temperature")
        tempVal != null
        // 71.7 as float -- allow minor float comparison tolerance
        Math.abs((tempVal as Float) - 71.7f) < 0.05f

        and: "NOT the raw integer value"
        tempVal != 717
    }

    def "applyStatus with temperature=850 emits 85.0°F (HA finding #1)"() {
        given: "fixture with temperature=850"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_temperature_high as Map
        assert deviceData.temperature == 850
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "temperature event is 85.0"
        def tempVal = lastEventValue("temperature")
        Math.abs((tempVal as Float) - 85.0f) < 0.05f
    }

    def "applyStatus with temperature=0 does NOT emit a temperature event (sanity gate)"() {
        given: "status with temperature=0 (uninitialized field sentinel)"
        def deviceData = [powerSwitch: 1, workMode: "normal", manualSpeedLevel: 3,
                          fanSpeedLevel: 3, temperature: 0, errorCode: 0, timerRemain: 0,
                          screenState: 1, screenSwitch: 1, oscillationSwitch: 1,
                          oscillationState: 1, muteSwitch: 0, muteState: 0, scheduleCount: 0]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no temperature event was emitted for raw value 0"
        def tempEvents = testDevice.events.findAll { it.name == "temperature" }
        tempEvents.isEmpty()
    }

    // -------------------------------------------------------------------------
    // displayingType read-only attribute (HA finding #5)
    // -------------------------------------------------------------------------

    def "applyStatus with displayingType=1 emits displayingType attribute event (HA finding #5)"() {
        given: "fixture with displayingType=1"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_with_displayingType as Map
        assert deviceData.displayingType == 1
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "displayingType attribute is emitted with raw value 1"
        lastEventValue("displayingType") == 1
    }

    def "no setDisplayingType command exists on the driver (HA finding #5)"() {
        // Verifies that the intentional omission of the setter is in place.
        // toggle_displaying_type exists in pyvesync but is labelled "Unknown functionality".
        // We deliberately do not expose it as a user-facing command.
        expect: "driver has no setDisplayingType method"
        driver.metaClass.getMetaMethod("setDisplayingType", Object) == null
    }

    def "applyStatus with displayingType=0 emits displayingType=0"() {
        given: "canonical fixture with displayingType=0"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.displayingType == 0
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("displayingType") == 0
    }

    // -------------------------------------------------------------------------
    // Toggle pattern (NIT 1): state.lastSwitchSet preferred over currentValue
    // -------------------------------------------------------------------------

    @Unroll
    def "toggle() reads state.lastSwitchSet='#lastSet' and sends setSwitch powerSwitch=#expectedPower"() {
        given: "state.lastSwitchSet was seeded by a prior on()/off() call"
        state.lastSwitchSet = lastSet

        when: "toggle() is called"
        driver.toggle()

        then: "setSwitch with the inverted powerSwitch was sent"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == expectedPower

        where:
        lastSet | expectedPower
        "on"    | 0
        "off"   | 1
    }

    def "toggle() falls back to device.currentValue('switch') when state not seeded"() {
        given: "state.lastSwitchSet not set; device.currentValue('switch') returns 'on'"
        assert state.lastSwitchSet == null
        testDevice.events.add([name: "switch", value: "on"])

        when: "toggle() falls back to currentValue"
        driver.toggle()

        then: "off was called (switch was on, so toggle goes to off)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Oscillation / mute / display payloads
    // -------------------------------------------------------------------------

    @Unroll
    def "setOscillation('#arg') sends setOscillationSwitch with {oscillationSwitch:#expected}"() {
        when:
        driver.setOscillation(arg)

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationSwitch" }
        req != null
        req.data.oscillationSwitch == expected

        where:
        arg   | expected
        "on"  | 1
        "off" | 0
    }

    @Unroll
    def "setMute('#arg') sends setMuteSwitch with {muteSwitch:#expected}"() {
        when:
        driver.setMute(arg)

        then:
        def req = testParent.allRequests.find { it.method == "setMuteSwitch" }
        req != null
        req.data.muteSwitch == expected

        where:
        arg   | expected
        "on"  | 1
        "off" | 0
    }

    @Unroll
    def "setDisplay('#arg') sends setDisplay with {screenSwitch:#expected}"() {
        when:
        driver.setDisplay(arg)

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == expected

        where:
        arg   | expected
        "on"  | 1
        "off" | 0
    }

    // ---- BP18: null-arg guards across all single-string setters ----

    @Unroll
    def "#cmd(null) emits WARN and makes no API call (BP18)"() {
        when:
        driver."$cmd"(null)

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.warns.any { it.contains(cmd) && it.contains("null") }

        where:
        cmd << ["setOscillation", "setMute", "setDisplay", "setSpeed", "setMode"]
    }

    // ---- invalid-enum guards on oscillation / mute / display ----

    @Unroll
    def "#cmd('yes') logs error and makes no API call (invalid enum)"() {
        when:
        driver."$cmd"("yes")

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("yes") || it.contains("invalid") }

        where:
        cmd << ["setOscillation", "setMute", "setDisplay"]
    }

    // -------------------------------------------------------------------------
    // Timer set and cancel
    // -------------------------------------------------------------------------

    def "setTimer(60, 'off') sends setTimer with {action:'off', total:60}"() {
        when:
        driver.setTimer(60, "off")

        then: "setTimer request has correct payload shape for Tower Fan"
        def req = testParent.allRequests.find { it.method == "setTimer" }
        req != null
        req.data.action == "off"
        req.data.total == 60

        and: "NOT the V201S addTimerV2 shape"
        !req.data.containsKey("enabled")
        !req.data.containsKey("startAct")
        !req.data.containsKey("tmgEvt")
    }

    def "setTimer(1800, 'on') sends setTimer with {action:'on', total:1800}"() {
        when:
        driver.setTimer(1800, "on")

        then:
        def req = testParent.allRequests.find { it.method == "setTimer" }
        req != null
        req.data.action == "on"
        req.data.total == 1800
    }

    def "setTimer defaults action to 'off' when not provided"() {
        when: "setTimer called with only seconds argument"
        driver.setTimer(300)

        then:
        def req = testParent.allRequests.find { it.method == "setTimer" }
        req != null
        req.data.action == "off"
        req.data.total == 300
    }

    def "cancelTimer sends clearTimer with state.timerId"() {
        given: "a timer id is stored in state"
        state.timerId = 42

        when:
        driver.cancelTimer()

        then: "clearTimer was called with the stored id"
        def req = testParent.allRequests.find { it.method == "clearTimer" }
        req != null
        req.data.id == 42
    }

    def "cancelTimer with no state.timerId is a no-op (no API call)"() {
        given: "no timer id in state"
        assert state.timerId == null

        when:
        driver.cancelTimer()

        then: "no clearTimer API call was made"
        testParent.allRequests.findAll { it.method == "clearTimer" }.isEmpty()
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // sleepPreferenceType nested field
    // -------------------------------------------------------------------------

    def "applyStatus exposes sleepPreferenceType from nested sleepPreference object"() {
        given: "response with sleepPreference.sleepPreferenceType='default'"
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def deviceData = fixture.responses.device_advanced_sleep as Map
        assert deviceData.sleepPreference?.sleepPreferenceType == "default"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "sleepPreferenceType attribute event emitted with 'default'"
        lastEventValue("sleepPreferenceType") == "default"
    }

    // -------------------------------------------------------------------------
    // Info HTML (Bug Pattern #7 -- local variables, not device.currentValue)
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables and contains mode and speed info (Bug Pattern #7)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map)

        when:
        driver.applyStatus(status)

        then: "info HTML is non-null and contains mode/speed content"
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Mode:")
        infoVal.contains("normal")
        infoVal.contains("Speed:")
    }

    def "info HTML includes temperature when non-zero"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LTF-F422S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map)

        when:
        driver.applyStatus(status)

        then: "info HTML contains temperature value (71.7)"
        def infoVal = lastEventValue("info") as String
        infoVal.contains("Temp") || infoVal.contains("71")
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

    def "BP23: on() sets state.turningOn TRUE during the API call and clears it after"() {
        // on() must set state.turningOn = true BEFORE the setSwitch API call so that any
        // internal path that re-enters on() during turn-on (e.g. a speed/level command) hits
        // the `if (state.turningOn) return` re-entrance guard instead of looping back.
        //
        // NON-VACUITY: the request-time snapshot below goes RED if LevoitFanLib.on() omits
        // `state.turningOn = true`. A spec that asserts only the post-condition (cleared after
        // on() returns) would PASS even with that line deleted — that vacuity is what this
        // closes. The callback in hubBypass fires synchronously inside on()'s try block while
        // turningOn is still true, so snapshotting state.turningOn at request time observes the
        // flag mid-flight.
        given: "device is off, turningOn clear, and a request-time snapshot hook installed"
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        def turningOnAtRequestTime = null
        // Capture state.turningOn at the moment the setSwitch(power=1) request is recorded.
        // `state` is the same live Map the driver mutates (HubitatSpec injects getState), so
        // reading it here reflects the value at the instant on() issues the API call.
        def realSend = testParent.&sendBypassRequest
        testParent.metaClass.sendBypassRequest = { dev, Map payload, Closure cb ->
            if (payload.method == "setSwitch" && payload.data?.powerSwitch == 1) {
                turningOnAtRequestTime = state.turningOn
            }
            realSend(dev, payload, cb)
        }

        when: "on() completes"
        driver.on()

        then: "state.turningOn was TRUE when the setSwitch API call fired (re-entrance guard armed)"
        turningOnAtRequestTime == true

        and: "state.turningOn was cleared after on() returned (try/finally guarantee)"
        !state.containsKey("turningOn") || state.turningOn == null

        and: "switch event was emitted as on"
        lastEventValue("switch") == "on"

        cleanup:
        testParent.metaClass = null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #24-B: setSpeed() + cycleSpeed() auto-turn-on when device is off
    // -------------------------------------------------------------------------

    def "BP24-B: setSpeed(numeric) when switch is off calls on() before sending level command"() {
        // setSpeed(N) calls sendLevel(N) which goes to hubBypass — BP24-B violation pre-fix.
        // After fix: ensureSwitchOn() at top of setSpeed body turns device on first.
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with a numeric level on an off device"
        driver.setSpeed(5)

        then: "on() was called — setSwitch with powerSwitch=1 was sent"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null

        and: "setLevel was sent at level 5"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 5

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: setSpeed(enum) when switch is off calls on() before sending level command"() {
        // setSpeed("medium-high") resolves to level 8 via fanControlEnumToLevel then calls
        // sendLevel(8) — same BP24-B entry point as the numeric path. Regression guard.
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when: "setSpeed is called with an enum value on an off device"
        driver.setSpeed("medium-high")

        then: "on() was called — setSwitch with powerSwitch=1 was sent"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null

        and: "setLevel was sent at level 8 (medium-high maps to 8 via fanControlEnumToLevel)"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 8

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: cycleSpeed() when switch is off calls on() before sending level command"() {
        // SwitchLevel/FanControl capability convention: a speed-advance command on an off
        // device should turn it on first. Without the BP24-B fix (ensureSwitchOn() at top
        // of cycleSpeed in LevoitFanLib), the device stayed physically off while the cloud
        // accepted the setLevel command — silent failure, same shape as BP24-A Core line bug.
        given: "device is off and has a known prior level"
        settings.descriptionTextEnable = false
        state.fanLevel = 5
        testDevice.events.add([name: "switch", value: "off"])

        when: "cycleSpeed() is called on an off device"
        driver.cycleSpeed()

        then: "on() was called — setSwitch with powerSwitch=1 was sent before the level command"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null

        and: "setLevel was also sent (to level 6, one step up from 5)"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 6

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "BP24-B: cycleSpeed() when switch is already on does NOT call on() again"() {
        // on() must not be called when the device is already on (redundant cloud call).
        given: "device is already on"
        settings.descriptionTextEnable = false
        state.fanLevel = 3
        testDevice.events.add([name: "switch", value: "on"])

        when: "cycleSpeed() is called on an already-on device"
        driver.cycleSpeed()

        then: "setLevel was sent (level 4)"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 4

        and: "setSwitch was NOT sent (device was already on)"
        testParent.allRequests.findAll { it.method == "setSwitch" }.size() == 0

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "cycleSpeed() with state.fanLevel unset (null) falls back to 1 (advances to 2)"() {
        // Regression guard for the Elvis fallback in LevoitFanLib.cycleSpeed():
        //   Integer cur = state.fanLevel as Integer ?: 1
        // On a fresh install where state.fanLevel was never written, cur defaults to 1 and next
        // becomes 2. Without the ?: fallback, (null + 1) would throw NPE that the Hubitat sandbox
        // silently swallows, leaving the device at level 0 with no log signal.
        given: "device is on and state.fanLevel was never set (null)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.cycleSpeed()

        then: "setLevel was sent with manualSpeedLevel=2 (1 default + 1)"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 2

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    def "cycleSpeed() with state.fanLevel = 12 (max) wraps to 1"() {
        // Regression guard for the wraparound branch in LevoitFanLib.cycleSpeed():
        //   Integer next = (cur >= 12) ? 1 : (cur + 1)
        // At level 12 (max for V2 fans), advancing wraps back to 1 rather than overflowing past
        // the device's physical 1-12 range.
        given: "device is on at max level (12)"
        settings.descriptionTextEnable = false
        state.fanLevel = 12
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.cycleSpeed()

        then: "setLevel was sent with manualSpeedLevel=1 (wrap)"
        def levelReq = testParent.allRequests.find { it.method == "setLevel" }
        levelReq != null
        levelReq.data.manualSpeedLevel == 1

        and: "no error was logged"
        testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // C3 idempotency gate: setOscillation suppresses redundant API calls
    // Both-ways: remove the C3 gate in setOscillation → these specs FAIL; restore → PASS.
    // -------------------------------------------------------------------------

    def "C3: setOscillation('on') is a no-op when oscillation is already 'on'"() {
        given: "oscillation attribute is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "oscillation", value: "on"])

        when:
        driver.setOscillation("on")

        then: "no setOscillationSwitch API call was made"
        testParent.allRequests.findAll { it.method == "setOscillationSwitch" }.isEmpty()
    }

    def "C3: setOscillation('on') makes an API call when oscillation is 'off'"() {
        given: "oscillation attribute is 'off'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "oscillation", value: "off"])

        when:
        driver.setOscillation("on")

        then: "a setOscillationSwitch API call was made"
        def req = testParent.allRequests.find { it.method == "setOscillationSwitch" }
        req != null
        req.data.oscillationSwitch == 1
    }

    // ---- C3 idempotency gate: setMute (via doSetMuteSwitch in LevoitFanLib) ----
    // Both-ways: remove the C3 gate in doSetMuteSwitch → these specs FAIL; restore → PASS.

    def "C3: setMute('on') is a no-op when mute is already 'on' (LevoitFanLib doSetMuteSwitch)"() {
        given: "mute attribute is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "mute", value: "on"])

        when:
        driver.setMute("on")

        then: "no setMuteSwitch API call was made"
        testParent.allRequests.findAll { it.method == "setMuteSwitch" }.isEmpty()
    }

    def "C3: setMute('on') makes an API call when mute is 'off' (LevoitFanLib doSetMuteSwitch)"() {
        given: "mute attribute is 'off'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "mute", value: "off"])

        when:
        driver.setMute("on")

        then: "a setMuteSwitch API call was made"
        def req = testParent.allRequests.find { it.method == "setMuteSwitch" }
        req != null
        req.data.muteSwitch == 1
    }

    // ---- C3 idempotency gate: setDisplay (via doSetDisplayScreenSwitch in LevoitFanLib) ----
    // Both-ways: remove the C3 gate in doSetDisplayScreenSwitch → these specs FAIL; restore → PASS.

    def "C3: setDisplay('off') is a no-op when displayOn is already 'off' (LevoitFanLib doSetDisplayScreenSwitch)"() {
        given: "displayOn attribute is already 'off'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "displayOn", value: "off"])

        when:
        driver.setDisplay("off")

        then: "no setDisplay API call was made"
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()
    }

    def "C3: setDisplay('on') makes an API call when displayOn is 'off' (LevoitFanLib doSetDisplayScreenSwitch)"() {
        given: "displayOn attribute is 'off'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "displayOn", value: "off"])

        when:
        driver.setDisplay("on")

        then: "a setDisplay API call was made"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #26: safeIntArg regression — non-numeric RM inputs must not throw
    // -------------------------------------------------------------------------

    def "BP26: setTimer('abc') does not throw on non-numeric input from Rule Machine (Tower Fan)"() {
        // Before BP26 fix, `seconds as Integer` on "abc" threw NumberFormatException (swallowed
        // silently by Hubitat sandbox, leaving the command a no-op with no log entry).
        // safeIntArg("abc", 0) returns 0, which routes to the cancelTimer / early-return path.
        given:
        settings.descriptionTextEnable = false

        when: "setTimer called with non-numeric string (Rule Machine non-numeric slot)"
        driver.setTimer("abc")

        then: "no exception thrown"
        noExceptionThrown()

        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "BP26: setTimer('5.7') does not throw on decimal-string input from Rule Machine (Tower Fan)"() {
        // Dashboard numeric tiles can pass decimal strings; safeIntArg truncates toward zero.
        given:
        settings.descriptionTextEnable = false

        when: "setTimer called with decimal string"
        driver.setTimer("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "BP26: setTimer(null) does not throw and does not set a timer (Tower Fan)"() {
        // requireNotNull at method entry rejects null with a WARN and returns before safeIntArg.
        given:
        settings.descriptionTextEnable = false

        when: "setTimer called with null (Rule Machine blank slot)"
        driver.setTimer(null)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no timer API call was made"
        testParent.allRequests.findAll { it.method == "setTimer" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // BP26: LevoitFanLib.setLevel — numeric coercion (Tower Fan includes LevoitFan)
    // Both-ways: revert safeIntArg in LevoitFanLib.setLevel → these specs FAIL; restore → PASS.
    // -------------------------------------------------------------------------

    @Unroll
    def "BP28: setLevel('#badInput') is ignored — no off(), no setLevel command (Tower Fan)"() {
        // BP28 contract: parseLevelOrNull maps "abc"/""/true to null -> ignore (device unchanged).
        // Previously safeIntArg mapped these to 0 -> off(); BP28 makes non-numeric a no-op while
        // explicit 0 still routes to off(). Fails if parseLevelOrNull is removed from LevoitFanLib.setLevel.
        given: "device is on so the auto-on guard does not confuse the assertion"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no off() and no setLevel command — non-numeric input ignored"
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP28: setLevel(0) still routes to off() (Tower Fan explicit-0 contract preserved)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when:
        driver.setLevel(0)

        then: "off() (setSwitch) was sent and no setLevel speed command"
        testParent.allRequests.find { it.method == "setSwitch" } != null
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    def "BP26: setLevel('5.7') does not throw and makes a setLevel API call with truncated value (Tower Fan)"() {
        // safeIntArg("5.7") → 5. levelFromPercent(5) → 1. setLevel API called with manualSpeedLevel=1.
        // This test fails if safeIntArg is removed from LevoitFanLib.setLevel.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setLevel API call was made"
        !testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }
}
