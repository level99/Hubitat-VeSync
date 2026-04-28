package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitPedestalFan.groovy (Levoit Pedestal Fan LPF-R432S).
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
 *                     response "advancedSleep" emits mode="sleep"; all 4 modes (normal/turbo/eco/sleep)
 *                     round-trip correctly
 *   Method names    — setMode produces setFanMode (NOT setTowerFanMode);
 *                     update() uses getFanStatus (NOT getTowerFanStatus);
 *                     oscillation uses setOscillationStatus (NOT setOscillationSwitch)
 *   Temperature /10 — fixture has temperature:750; emitted temperature event = 75.0. Edge: 0 -> no event
 *   Oscillation toggles — setHorizontalOscillation/setVerticalOscillation produce correct payloads
 *   Oscillation ranges  — setHorizontalRange/setVerticalRange produce correct payloads with state:1
 *   No childLock setter — metaClass.getMetaMethod check; read attribute from fixture works
 *   No timer commands   — setTimer/cancelTimer methods absent
 *   NIT 1 toggle pattern — state.lastSwitchSet populated/unset paths
 *   sleepPreferenceType + oscillation coordinate/range nested fields — read from response
 */
class LevoitPedestalFanSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitPedestalFan.groovy"
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

    def "update() no-arg self-fetch calls getFanStatus API (NOT getTowerFanStatus)"() {
        given: "default state"

        when: "update() no-arg is called"
        driver.update()

        then: "getFanStatus was sent to the API (Pedestal Fan-specific read method)"
        def req = testParent.allRequests.find { it.method == "getFanStatus" }
        req != null
        noExceptionThrown()

        and: "getTowerFanStatus was NOT called (Tower Fan method -- wrong for Pedestal Fan)"
        testParent.allRequests.findAll { it.method == "getTowerFanStatus" }.size() == 0
    }

    def "update(status) 1-arg parent callback is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Pedestal Fan status response"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status, nightLight) 2-arg parent callback is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Pedestal Fan status response"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, null) -- Pedestal Fan has no nightlight hardware"
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
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields reached -- switch=on confirms dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("speed") != null
        lastEventValue("speed") != "off"
    }

    def "applyStatus handles double-wrapped (humidifier-style) envelope (Bug Pattern #3)"() {
        given: "double-wrapped: {code:0, result:{code:0, result:{device fields}}}"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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
        req.data.levelIdx         == 0
        req.data.levelType        == "wind"
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

    // -------------------------------------------------------------------------
    // Bug Pattern #6: speed="off" when powerSwitch=0
    // -------------------------------------------------------------------------

    def "applyStatus emits speed='off' when powerSwitch=0 even if manualSpeedLevel != 0 (Bug Pattern #6)"() {
        given: "device is off but manualSpeedLevel=5 (last-set level still in response)"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        driver.applyStatus(v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map))

        then: "updateSetting NOT called for descriptionTextEnable (user choice preserved)"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    def "pref-seed fires only once across multiple applyStatus calls (Bug Pattern #12)"() {
        given: "descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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
        given: "canonical device-on, normal mode, speed 5, H-oscillation on, V-oscillation off, mute off, display on"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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

        and: "horizontal oscillation is on (horizontalOscillationState=1)"
        lastEventValue("horizontalOscillation") == "on"

        and: "vertical oscillation is off (verticalOscillationState=0)"
        lastEventValue("verticalOscillation") == "off"

        and: "mute is off (muteState=0)"
        lastEventValue("mute") == "off"

        and: "display is on (screenState=1)"
        lastEventValue("displayOn") == "on"

        and: "childLock is off (childLock=0)"
        lastEventValue("childLock") == "off"

        and: "errorCode is 0"
        lastEventValue("errorCode") == 0

        and: "no errors logged"
        testLog.errors.isEmpty()
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

    // -------------------------------------------------------------------------
    // Mode reverse-mapping (HA finding #d)
    // -------------------------------------------------------------------------

    def "setMode('sleep') sends setFanMode with workMode='advancedSleep' (HA finding #d)"() {
        given: "default state"

        when: "user calls setMode('sleep')"
        driver.setMode("sleep")

        then: "API receives 'advancedSleep' literal, NOT 'sleep'"
        def req = testParent.allRequests.find { it.method == "setFanMode" }
        req != null
        req.data.workMode == "advancedSleep"

        and: "NOT the user-facing label"
        req.data.workMode != "sleep"
    }

    def "applyStatus with workMode='advancedSleep' emits mode='sleep' event (HA finding #d)"() {
        given: "response contains workMode='advancedSleep'"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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

    def "setMode('normal') sends setFanMode with workMode='normal' (no reverse-mapping)"() {
        given: "default state"

        when:
        driver.setMode("normal")

        then: "API receives 'normal' unchanged"
        def req = testParent.allRequests.find { it.method == "setFanMode" }
        req != null
        req.data.workMode == "normal"
    }

    def "setMode('turbo') sends setFanMode with workMode='turbo'"() {
        given: "default state"

        when:
        driver.setMode("turbo")

        then:
        def req = testParent.allRequests.find { it.method == "setFanMode" }
        req != null
        req.data.workMode == "turbo"
    }

    def "setMode('eco') sends setFanMode with workMode='eco' (Pedestal Fan differentiator)"() {
        given: "default state"

        when: "eco mode -- Pedestal Fan has eco, Tower Fan has auto"
        driver.setMode("eco")

        then:
        def req = testParent.allRequests.find { it.method == "setFanMode" }
        req != null
        req.data.workMode == "eco"
    }

    def "applyStatus with workMode='eco' emits mode='eco' (no reverse-mapping)"() {
        given: "response contains workMode='eco'"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_eco_mode as Map
        assert deviceData.workMode == "eco"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'eco' unchanged"
        lastEventValue("mode") == "eco"
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
    // Method name correctness assertions
    // -------------------------------------------------------------------------

    def "setMode produces setFanMode (NOT setTowerFanMode)"() {
        given: "default state"

        when: "any mode is set"
        driver.setMode("normal")

        then: "method is setFanMode, not setTowerFanMode"
        def fanReqs       = testParent.allRequests.findAll { it.method == "setFanMode" }
        def towerFanReqs  = testParent.allRequests.findAll { it.method == "setTowerFanMode" }
        def purifierReqs  = testParent.allRequests.findAll { it.method == "setPurifierMode" }
        fanReqs.size() >= 1
        towerFanReqs.size() == 0
        purifierReqs.size() == 0
    }

    def "update() self-fetch uses getFanStatus (NOT getTowerFanStatus, NOT getPurifierStatus)"() {
        given: "default state"

        when:
        driver.update()

        then:
        def pedReqs     = testParent.allRequests.findAll { it.method == "getFanStatus" }
        def towerReqs   = testParent.allRequests.findAll { it.method == "getTowerFanStatus" }
        def purReqs     = testParent.allRequests.findAll { it.method == "getPurifierStatus" }
        pedReqs.size() >= 1
        towerReqs.size() == 0
        purReqs.size() == 0
    }

    def "setHorizontalOscillation uses setOscillationStatus (NOT setOscillationSwitch)"() {
        given: "default state"

        when:
        driver.setHorizontalOscillation("on")

        then:
        def correctReqs = testParent.allRequests.findAll { it.method == "setOscillationStatus" }
        def wrongReqs   = testParent.allRequests.findAll { it.method == "setOscillationSwitch" }
        correctReqs.size() >= 1
        wrongReqs.size() == 0
    }

    def "setVerticalOscillation uses setOscillationStatus (NOT setOscillationSwitch)"() {
        given: "default state"

        when:
        driver.setVerticalOscillation("on")

        then:
        def correctReqs = testParent.allRequests.findAll { it.method == "setOscillationStatus" }
        def wrongReqs   = testParent.allRequests.findAll { it.method == "setOscillationSwitch" }
        correctReqs.size() >= 1
        wrongReqs.size() == 0
    }

    // -------------------------------------------------------------------------
    // Temperature divide-by-10 (pyvesync source vesyncfan.py:314 confirmed)
    // -------------------------------------------------------------------------

    def "applyStatus divides temperature field by 10 before emitting"() {
        given: "fixture with temperature=750 (= 75.0°F)"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.temperature == 750
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "temperature event value is 75.0 (750 / 10), NOT the raw 750"
        def tempVal = lastEventValue("temperature")
        tempVal != null
        Math.abs((tempVal as Float) - 75.0f) < 0.05f

        and: "NOT the raw integer value"
        tempVal != 750
    }

    def "applyStatus with temperature=850 emits 85.0°F"() {
        given: "fixture with temperature=850 (high speed turbo)"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_high_speed_turbo as Map
        assert deviceData.temperature == 850
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        def tempVal = lastEventValue("temperature")
        Math.abs((tempVal as Float) - 85.0f) < 0.05f
    }

    def "applyStatus with temperature=0 does NOT emit a temperature event (sanity gate)"() {
        given: "status with temperature=0 (uninitialized field sentinel)"
        def deviceData = [powerSwitch: 1, workMode: "normal", fanSpeedLevel: 3,
                          manualSpeedLevel: 3, temperature: 0, errorCode: 0,
                          horizontalOscillationState: 0, verticalOscillationState: 0,
                          muteSwitch: 0, muteState: 0, screenSwitch: 1, screenState: 1,
                          childLock: 0]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no temperature event was emitted for raw value 0"
        def tempEvents = testDevice.events.findAll { it.name == "temperature" }
        tempEvents.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Oscillation toggles
    // -------------------------------------------------------------------------

    def "setHorizontalOscillation('on') sends setOscillationStatus with {horizontalOscillationState:1, actType:'default'}"() {
        when:
        driver.setHorizontalOscillation("on")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.horizontalOscillationState == 1
        req.data.actType == "default"

        and: "verticalOscillationState is absent (separate payloads per axis)"
        !req.data.containsKey("verticalOscillationState")

        and: "no range fields (toggle only, no range)"
        !req.data.containsKey("left")
        !req.data.containsKey("right")
    }

    def "setHorizontalOscillation('off') sends setOscillationStatus with {horizontalOscillationState:0, actType:'default'}"() {
        when:
        driver.setHorizontalOscillation("off")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.horizontalOscillationState == 0
        req.data.actType == "default"
    }

    def "setVerticalOscillation('on') sends setOscillationStatus with {verticalOscillationState:1, actType:'default'}"() {
        when:
        driver.setVerticalOscillation("on")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.verticalOscillationState == 1
        req.data.actType == "default"

        and: "horizontalOscillationState is absent (separate payloads per axis)"
        !req.data.containsKey("horizontalOscillationState")
    }

    def "setVerticalOscillation('off') sends setOscillationStatus with {verticalOscillationState:0, actType:'default'}"() {
        when:
        driver.setVerticalOscillation("off")

        then:
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.verticalOscillationState == 0
        req.data.actType == "default"
    }

    // -------------------------------------------------------------------------
    // Oscillation ranges
    // -------------------------------------------------------------------------

    def "setHorizontalRange(10, 80) produces setOscillationStatus with state:1 and left/right bounds"() {
        when:
        driver.setHorizontalRange(10, 80)

        then: "setOscillationStatus request with H state=1 and range fields"
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.horizontalOscillationState == 1    // must be ON (range requires oscillation active)
        req.data.actType == "default"
        req.data.left  == 10
        req.data.right == 80

        and: "vertical state absent (H-only payload)"
        !req.data.containsKey("verticalOscillationState")
        !req.data.containsKey("top")
        !req.data.containsKey("bottom")
    }

    def "setVerticalRange(20, 70) produces setOscillationStatus with state:1 and top/bottom bounds"() {
        when:
        driver.setVerticalRange(20, 70)

        then: "setOscillationStatus request with V state=1 and range fields"
        def req = testParent.allRequests.find { it.method == "setOscillationStatus" }
        req != null
        req.data.verticalOscillationState == 1      // must be ON
        req.data.actType == "default"
        req.data.top    == 20
        req.data.bottom == 70

        and: "horizontal state absent (V-only payload)"
        !req.data.containsKey("horizontalOscillationState")
        !req.data.containsKey("left")
        !req.data.containsKey("right")
    }

    def "setHorizontalRange emits horizontalOscillation='on' and range attribute events"() {
        when:
        driver.setHorizontalRange(5, 95)

        then: "horizontalOscillation turns on"
        lastEventValue("horizontalOscillation") == "on"

        and: "range bounds are emitted as attributes"
        lastEventValue("oscillationLeft")  == 5
        lastEventValue("oscillationRight") == 95
    }

    def "setVerticalRange emits verticalOscillation='on' and range attribute events"() {
        when:
        driver.setVerticalRange(10, 85)

        then: "verticalOscillation turns on"
        lastEventValue("verticalOscillation") == "on"

        and: "range bounds are emitted as attributes"
        lastEventValue("oscillationTop")    == 10
        lastEventValue("oscillationBottom") == 85
    }

    // -------------------------------------------------------------------------
    // childLock: read-only attribute, no setter command
    // -------------------------------------------------------------------------

    def "no setChildLock command exists on the driver (read-only per pyvesync + ST/HB cross-check)"() {
        expect: "driver has no setChildLock method"
        driver.metaClass.getMetaMethod("setChildLock", Object) == null
    }

    def "applyStatus with childLock=1 emits childLock='on' attribute (read-only)"() {
        given: "fixture with childLock=1"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_with_childLock as Map
        assert deviceData.childLock == 1
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "childLock attribute emitted with 'on'"
        lastEventValue("childLock") == "on"
    }

    def "applyStatus with childLock=0 emits childLock='off'"() {
        given: "fixture with childLock=0 (canonical)"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.childLock == 0
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("childLock") == "off"
    }

    // -------------------------------------------------------------------------
    // No timer commands (omitted per pyvesync + no community evidence)
    // -------------------------------------------------------------------------

    def "no setTimer command exists on the driver (omitted per pyvesync VeSyncPedestalFan)"() {
        expect: "driver has no setTimer method (single-arg form)"
        driver.metaClass.getMetaMethod("setTimer", Object) == null
    }

    def "no cancelTimer command exists on the driver (omitted per pyvesync VeSyncPedestalFan)"() {
        expect: "driver has no cancelTimer method"
        driver.metaClass.getMetaMethod("cancelTimer") == null
    }

    // -------------------------------------------------------------------------
    // NIT 1 toggle pattern: state.lastSwitchSet
    // -------------------------------------------------------------------------

    def "on() seeds state.lastSwitchSet='on' and off() seeds 'off' (NIT 1)"() {
        when: "on() is called"
        driver.on()

        then: "lastSwitchSet is 'on'"
        state.lastSwitchSet == "on"

        when: "off() is called"
        driver.off()

        then: "lastSwitchSet is 'off'"
        state.lastSwitchSet == "off"
    }

    def "toggle() reads state.lastSwitchSet='on' and calls off() (NIT 1)"() {
        given: "state.lastSwitchSet was seeded by a prior on() call"
        state.lastSwitchSet = "on"

        when: "toggle() is called"
        driver.toggle()

        then: "setSwitch with powerSwitch=0 was sent (off was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    def "toggle() reads state.lastSwitchSet='off' and calls on() (NIT 1)"() {
        given: "state.lastSwitchSet was seeded by a prior off() call"
        state.lastSwitchSet = "off"

        when:
        driver.toggle()

        then: "setSwitch with powerSwitch=1 was sent (on was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
    }

    def "toggle() falls back to device.currentValue('switch') when state not seeded (NIT 1)"() {
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
    // sleepPreferenceType nested field
    // -------------------------------------------------------------------------

    def "applyStatus exposes sleepPreferenceType from nested sleepPreference object"() {
        given: "fixture with sleepPreference.sleepPreferenceType='default'"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.sleepPreference?.sleepPreferenceType == "default"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "sleepPreferenceType attribute event emitted"
        lastEventValue("sleepPreferenceType") == "default"
    }

    def "applyStatus with null sleepPreference does not throw"() {
        given: "fixture with sleepPreference=null"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_off as Map
        assert deviceData.sleepPreference == null
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no exception thrown"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Oscillation coordinate + range nested fields from response
    // -------------------------------------------------------------------------

    def "applyStatus reads oscillationCoordinate yaw and pitch from response"() {
        given: "fixture with oscillationCoordinate={yaw:45, pitch:10}"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_on_normal_speed5 as Map
        assert deviceData.oscillationCoordinate?.yaw   == 45
        assert deviceData.oscillationCoordinate?.pitch == 10
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "oscillationYaw and oscillationPitch attributes are emitted"
        lastEventValue("oscillationYaw")   == 45
        lastEventValue("oscillationPitch") == 10
    }

    def "applyStatus reads oscillationRange left/right/top/bottom from response"() {
        given: "fixture with oscillation_full_range: left=5, right=95, top=10, bottom=85"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_oscillation_full_range as Map
        assert deviceData.oscillationRange?.left   == 5
        assert deviceData.oscillationRange?.right  == 95
        assert deviceData.oscillationRange?.top    == 10
        assert deviceData.oscillationRange?.bottom == 85
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "all 4 oscillation range attributes are emitted"
        lastEventValue("oscillationLeft")   == 5
        lastEventValue("oscillationRight")  == 95
        lastEventValue("oscillationTop")    == 10
        lastEventValue("oscillationBottom") == 85
    }

    def "applyStatus with null oscillationCoordinate does not throw"() {
        given: "fixture with oscillationCoordinate=null"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_off as Map
        assert deviceData.oscillationCoordinate == null
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no exception thrown"
        noExceptionThrown()
    }

    def "applyStatus with null oscillationRange does not throw"() {
        given: "fixture with oscillationRange=null"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_off as Map
        assert deviceData.oscillationRange == null
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no exception thrown"
        noExceptionThrown()
    }

    def "applyStatus device_oscillation_full_range emits H and V oscillation both on"() {
        given: "fixture with both axes oscillating"
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def deviceData = fixture.responses.device_oscillation_full_range as Map
        assert deviceData.horizontalOscillationState == 1
        assert deviceData.verticalOscillationState   == 1
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "both oscillation attributes are 'on'"
        lastEventValue("horizontalOscillation") == "on"
        lastEventValue("verticalOscillation")   == "on"
    }

    // -------------------------------------------------------------------------
    // Mute / display payloads
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Info HTML (Bug Pattern #7 -- local variables, not device.currentValue)
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables and contains mode and speed info (Bug Pattern #7)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LPF-R432S.yaml")
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

    def "info HTML includes H-Osc and V-Osc status"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map)

        when:
        driver.applyStatus(status)

        then: "info HTML contains oscillation status"
        def infoVal = lastEventValue("info") as String
        infoVal.contains("H-Osc") || infoVal.contains("Osc")
    }

    def "info HTML includes temperature when non-zero"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LPF-R432S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_normal_speed5 as Map)

        when:
        driver.applyStatus(status)

        then: "info HTML contains temperature value (75.0)"
        def infoVal = lastEventValue("info") as String
        infoVal.contains("Temp") || infoVal.contains("75")
    }
}
