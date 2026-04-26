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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        driver.applyStatus(purifierStatusEnvelope(fixture.responses.device_on_manual_speed2 as Map))

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
        driver.applyStatus(purifierStatusEnvelope(fixture.responses.device_on_manual_speed2 as Map))

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed2 as Map)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "autoPreference attribute is set to fixture value 'default'"
        lastEventValue("autoPreference") == "default"
        lastEventValue("roomSize") == 0
    }
}
