package drivers

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

    def "setMode('normal') sends setTowerFanMode with workMode='normal' (no reverse-mapping)"() {
        given: "default state"

        when:
        driver.setMode("normal")

        then: "API receives 'normal' unchanged"
        def req = testParent.allRequests.find { it.method == "setTowerFanMode" }
        req != null
        req.data.workMode == "normal"
    }

    def "setMode('turbo') sends setTowerFanMode with workMode='turbo'"() {
        given: "default state"

        when:
        driver.setMode("turbo")

        then:
        def req = testParent.allRequests.find { it.method == "setTowerFanMode" }
        req != null
        req.data.workMode == "turbo"
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

    def "toggle() reads state.lastSwitchSet='on' and calls off()"() {
        given: "state.lastSwitchSet was seeded by a prior on() call"
        state.lastSwitchSet = "on"

        when: "toggle() is called"
        driver.toggle()

        then: "setSwitch with powerSwitch=0 was sent (off was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    def "toggle() reads state.lastSwitchSet='off' and calls on()"() {
        given: "state.lastSwitchSet was seeded by a prior off() call"
        state.lastSwitchSet = "off"

        when:
        driver.toggle()

        then: "setSwitch with powerSwitch=1 was sent (on was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
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

    def "setOscillation('on') sends setOscillationSwitch with {oscillationSwitch:1}"() {
        when:
        driver.setOscillation("on")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationSwitch" }
        req != null
        req.data.oscillationSwitch == 1
    }

    def "setOscillation('off') sends setOscillationSwitch with {oscillationSwitch:0}"() {
        when:
        driver.setOscillation("off")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationSwitch" }
        req != null
        req.data.oscillationSwitch == 0
    }

    def "setMute('on') sends setMuteSwitch with {muteSwitch:1}"() {
        when:
        driver.setMute("on")

        then:
        def req = testParent.allRequests.find { it.method == "setMuteSwitch" }
        req != null
        req.data.muteSwitch == 1
    }

    def "setMute('off') sends setMuteSwitch with {muteSwitch:0}"() {
        when:
        driver.setMute("off")

        then:
        def req = testParent.allRequests.find { it.method == "setMuteSwitch" }
        req != null
        req.data.muteSwitch == 0
    }

    def "setDisplay('on') sends setDisplay with {screenSwitch:1}"() {
        when:
        driver.setDisplay("on")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1
    }

    def "setDisplay('off') sends setDisplay with {screenSwitch:0}"() {
        when:
        driver.setDisplay("off")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 0
    }

    // ---- BP18: null-arg + invalid-enum guards on oscillation / mute / display ----

    def "setOscillation(null) emits WARN and makes no API call (BP18)"() {
        when:
        driver.setOscillation(null)

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.warns.any { it.contains("setOscillation") && it.contains("null") }
    }

    def "setOscillation('yes') logs error and makes no API call (invalid enum)"() {
        when:
        driver.setOscillation("yes")

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("yes") || it.contains("invalid") }
    }

    def "setMute(null) emits WARN and makes no API call (BP18)"() {
        when:
        driver.setMute(null)

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.warns.any { it.contains("setMute") && it.contains("null") }
    }

    def "setMute('yes') logs error and makes no API call (invalid enum)"() {
        when:
        driver.setMute("yes")

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("yes") || it.contains("invalid") }
    }

    def "setDisplay(null) emits WARN and makes no API call (BP18)"() {
        when:
        driver.setDisplay(null)

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.warns.any { it.contains("setDisplay") && it.contains("null") }
    }

    def "setDisplay('yes') logs error and makes no API call (invalid enum)"() {
        when:
        driver.setDisplay("yes")

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("yes") || it.contains("invalid") }
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

    // ---- BP18: null-arg guard ----

    def "setSpeed(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver.setSpeed(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains("setSpeed") && it.contains("null") }
        testParent.allRequests.isEmpty()
    }

    def "setMode(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver.setMode(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains("setMode") && it.contains("null") }
        testParent.allRequests.isEmpty()
    }
}
