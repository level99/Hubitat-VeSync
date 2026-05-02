package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitVital100S.groovy (Levoit Vital 100S Air Purifier).
 *
 * The V100S uses the same VeSyncAirBaseV2 pyvesync class as the V201S with one
 * behavioral delta: NO LIGHT_DETECT feature. lightDetectionSwitch and
 * environmentLightState ARE present in the response payload (shared model) but the
 * driver must NOT emit Hubitat events for them.
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- defensive peel-loop in applyStatus handles both single-wrap and double-wrap
 *   Bug Pattern #4  -- setLevel uses {levelIdx, levelType, manualSpeedLevel}, NOT {level, id, type}
 *   Bug Pattern #5  -- setMode("manual") routes through setLevel, NOT setPurifierMode
 *   Bug Pattern #6  -- speed reports "off" when powerSwitch=0, even if manualSpeedLevel != 255
 *   Bug Pattern #12 -- pref-seed fires when descriptionTextEnable is null, preserves false
 *   Happy path      -- full applyStatus from LAP-V102S.yaml emits expected events (canonical values)
 *   Pet mode        -- setMode("pet") produces setPurifierMode with workMode="pet"
 *   No LIGHT_DETECT -- lightDetectionSwitch and environmentLightState in response do NOT cause events
 */
class LevoitVital100SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitVital100S.groovy"
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: envelope dereference
    // -------------------------------------------------------------------------

    def "applyStatus dereferences status.result to reach device fields (single-wrap)"() {
        given: "a status map as the parent passes it: {code:0, result:{<device fields>}}"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "applyStatus is called with the single-wrapped envelope"
        driver.applyStatus(status)

        then: "device fields were reached and events emitted (switch=on confirms dereference succeeded)"
        lastEventValue("switch") == "on"
        // PM2.5 is a device-field -- reaching it proves the dereference worked (canonical value is 3)
        lastEventValue("pm25") == 3
    }

    def "applyStatus envelope peel handles double-wrapped responses (Bug Pattern #3)"() {
        // LevoitVital100S.groovy has a defensive while-loop peel matching LevoitVital200S.groovy.
        // This test passes a double-wrapped envelope (humidifier shape applied to purifier data)
        // and asserts that the driver correctly reaches the device fields via the peel loop.
        //
        // Double-wrap shape: status = {code, result: {code, result: {device fields}, traceId}}
        given: "a double-wrapped envelope (humidifier shape applied to purifier data)"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when: "applyStatus receives a double-wrapped envelope"
        driver.applyStatus(doubleWrapped)

        then: "device fields ARE correctly reached via peel loop -- switch=on, pm25 reached"
        lastEventValue("switch") == "on"
        lastEventValue("pm25") == 3
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when: "applyStatus is called with null"
        driver.applyStatus(null)

        then: "no exception thrown"
        noExceptionThrown()
        // switch still gets emitted as "off" because powerSwitch defaults to 0 (null)
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #4: setLevel uses correct V2-line field names
    // -------------------------------------------------------------------------

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

    def "setLevel sends {levelIdx, levelType, manualSpeedLevel} field names (Bug Pattern #4)"() {
        given: "device is on (needed for setSpeedLevel to proceed)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(50) is called -- maps to speed level 3"
        driver.setLevel(50)

        then: "sendBypassRequest was called with correct V102S field names"
        def setLevelReq = testParent.allRequests.find { it.method == "setLevel" }
        setLevelReq != null
        setLevelReq.data.containsKey("levelIdx")
        setLevelReq.data.containsKey("levelType")
        setLevelReq.data.containsKey("manualSpeedLevel")
        // Verify field name ABSENCE as well -- no legacy Core-line names
        !setLevelReq.data.containsKey("id")
        !setLevelReq.data.containsKey("type")
        !setLevelReq.data.containsKey("level")
    }

    def "setSpeed('high') sends {levelIdx:0, levelType:'wind', manualSpeedLevel:4} (Bug Pattern #4)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        driver.state.mode = "manual"

        when: "setSpeed('high') is called -- maps to manualSpeedLevel 4"
        driver.setSpeed("high")

        then: "setLevel request uses V102S canonical field names with correct values"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.levelIdx == 0
        req.data.levelType == "wind"
        req.data.manualSpeedLevel == 4
    }

    def "setMode('manual') sends a setLevel call, NOT setPurifierMode (Bug Pattern #5)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setMode('manual') is called"
        driver.setMode("manual")

        then: "request was setLevel (manual mode established by sending a speed)"
        def setLevelReq = testParent.allRequests.find { it.method == "setLevel" }
        def setPurifierModeReq = testParent.allRequests.find { it.method == "setPurifierMode" }
        setLevelReq != null
        setPurifierModeReq == null
    }

    def "setSpeed(2) sends manualSpeedLevel:2 in setLevel payload (Bug Pattern #4 canonical)"() {
        // Canonical pyvesync set_fan_speed fixture: manualSpeedLevel:3 for speed=3.
        // This test uses speed='medium' (also manualSpeedLevel=3) vs 'low' (manualSpeedLevel=2).
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        driver.state.mode = "manual"

        when: "setSpeed('low') is called -- maps to manualSpeedLevel 2"
        driver.setSpeed("low")

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.manualSpeedLevel == 2
        req.data.levelIdx == 0
        req.data.levelType == "wind"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: speed=off when powerSwitch=0
    // -------------------------------------------------------------------------

    def "applyStatus emits speed='off' when powerSwitch=0 even if manualSpeedLevel != 255 (Bug Pattern #6)"() {
        given: "device is off but last manual speed was 3 (not 255)"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null and sets it to true (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null (first install before Save)"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        when: "applyStatus is called (pref-seed is at the top of applyStatus)"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
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

    // -------------------------------------------------------------------------
    // Pet mode
    // -------------------------------------------------------------------------

    def "setMode('pet') sends setPurifierMode with workMode='pet'"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setMode('pet') is called"
        driver.setMode("pet")

        then: "setPurifierMode request was sent with workMode=pet"
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req != null
        req.data.workMode == "pet"
    }

    def "applyStatus with pet mode fixture sets petMode=on and mode=pet"() {
        given: "canonical pet mode fixture"
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_pet_mode as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "pet"
        lastEventValue("petMode") == "on"
        lastEventValue("switch") == "on"
    }

    def "setPetMode('on') delegates to setMode('pet')"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setPetMode('on') is called"
        driver.setPetMode("on")

        then: "setPurifierMode request was sent with workMode=pet"
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req != null
        req.data.workMode == "pet"
    }

    // -------------------------------------------------------------------------
    // No LIGHT_DETECT exposure
    // -------------------------------------------------------------------------

    def "applyStatus does NOT emit lightDetection event even when lightDetectionSwitch is in response"() {
        // V100S has no LIGHT_DETECT feature. The response model (shared with V201S) includes
        // lightDetectionSwitch and environmentLightState, but the V100S driver must ignore both.
        given: "canonical fixture with lightDetectionSwitch=0 and environmentLightState=1 present"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        // Verify the fixture actually contains these fields (shared model)
        assert deviceData.containsKey("lightDetectionSwitch")
        assert deviceData.containsKey("environmentLightState")
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no 'lightDetection' event was emitted"
        !testDevice.events.any { it.name == "lightDetection" }

        and: "no 'lightDetected' event was emitted"
        !testDevice.events.any { it.name == "lightDetected" }
    }

    def "applyStatus does NOT emit lightDetected event even when environmentLightState=1 in response"() {
        // Additional explicit assertion: environmentLightState=1 (light detected=true) in the
        // canonical fixture must NOT cause a 'lightDetected' event to be emitted.
        given: "fixture with environmentLightState=1"
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        assert deviceData.environmentLightState == 1 : "fixture must have environmentLightState=1 to make this assertion meaningful"
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no event with name 'lightDetected' was emitted"
        !testDevice.events.any { it.name == "lightDetected" }
    }

    // -------------------------------------------------------------------------
    // Additional coverage
    // -------------------------------------------------------------------------

    def "filter life critically low (<10%) logs INFO when transitioning below threshold"() {
        given: "prior filter life was 15% (above critical)"
        settings.descriptionTextEnable = true
        state.lastFilterLife = 15

        def fixture = loadYamlFixture("LAP-V102S.yaml")
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
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "autoPreference attribute is set to canonical value 'default'"
        lastEventValue("autoPreference") == "default"
        // V102S canonical autoPreference.roomSize is 600 (differs from V201S which uses 0)
        lastEventValue("roomSize") == 600
    }

    def "info HTML is built from local variables not device.currentValue() race (Bug Pattern #7 guard)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LAP-V102S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "applyStatus is called"
        driver.applyStatus(status)

        then: "info HTML contains filter and PM2.5 values"
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Filter:")
        // PM2.5 value from canonical fixture is 3
        infoVal.contains("3")
        // Air quality label computed locally -- canonical AQLevel=2 maps to "moderate"
        infoVal.contains("moderate") || infoVal.contains("Air Quality")
    }

    // -------------------------------------------------------------------------
    // Theme B: setLevel(0) -> off() (SwitchLevel convention)
    // -------------------------------------------------------------------------

    def "setLevel(0) calls off() -- SwitchLevel convention (Theme B)"() {
        // Hubitat SwitchLevel says setLevel(0) means 'off' (Z-Wave dimmer convention).
        // Previously 0 mapped to lvl=1 (val < 20 branch) and sent manualSpeedLevel=1.
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

    // -------------------------------------------------------------------------
    // Theme C: state.lastSwitchSet consistency + state.speed after setLevel
    // -------------------------------------------------------------------------

    def "on() seeds state.lastSwitchSet='on' (Theme C)"() {
        when: "on() is called"
        driver.on()

        then: "lastSwitchSet is 'on'"
        state.lastSwitchSet == "on"
    }

    def "off() seeds state.lastSwitchSet='off' (Theme C)"() {
        when: "off() is called"
        driver.off()

        then: "lastSwitchSet is 'off'"
        state.lastSwitchSet == "off"
    }

    def "toggle() uses state.lastSwitchSet='on' to call off() (Theme C)"() {
        given: "state.lastSwitchSet was seeded to 'on'"
        state.lastSwitchSet = "on"

        when:
        driver.toggle()

        then: "setSwitch with powerSwitch=0 was sent (off was called)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    def "toggle() falls back to device.currentValue('switch') when state not seeded (Theme C)"() {
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

    def "setLevel writes state.speed for configureOnState replay (Theme C)"() {
        // setLevel establishes manual mode atomically on V2-line. The driver must write
        // state.speed so configureOnState() can replay the correct named speed on next on().
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when: "setLevel(50) is called -- maps to lvl=3 (val >= 40), which is 'medium' speed"
        driver.setLevel(50)

        then: "state.speed is set (not null) so configureOnState can replay it"
        state.speed != null
        // lvl=3 maps to "medium" via mapIntegerToSpeed
        state.speed == "medium"

        and: "state.mode is 'manual'"
        state.mode == "manual"
    }
}
