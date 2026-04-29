package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitSproutAir.groovy (Levoit Sprout Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- fan speed 0 when device off (fanSpeedLevel=255 mapped to 0 by API)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from canonical fixture emits expected events
 *
 *   Switch payload   -- on() produces setSwitch with {powerSwitch:1, switchIdx:0}
 *                    -- NOT {switch:'on', id:0} (VeSyncAirBypass Core line difference)
 *   Mode write-path  -- setMode("auto") sends setPurifierMode {workMode:'auto'}
 *                    -- setMode("sleep") sends setPurifierMode {workMode:'sleep'}
 *                    -- setMode("manual") delegates to setFanSpeed(1) NOT setPurifierMode
 *                    -- field name is 'workMode' NOT 'mode' (Core line VeSyncAirBypass)
 *   Fan speed        -- setFanSpeed(2) sends setLevel {levelIdx:0, manualSpeedLevel:2, levelType:'wind'}
 *                    -- NOT setLevel {level, id, type} (Core line VeSyncAirBypass)
 *                    -- range 1-3 (fan_levels=[1,2,3]); setting speed sets mode=manual
 *   Fan speed read   -- fanSpeedLevel=255 in response maps to 0 (device off / pyvesync convention)
 *   AQ sensors       -- AQLevel, PM25, PM1, PM10, AQI, VOC, CO2 all emitted
 *   Display          -- setDisplay('on') sends {screenSwitch:1}
 *   Child lock       -- setChildLock('on') sends {childLockSwitch:1}
 *   Nightlight write -- setNightlightMode sends setNightLight {night_light: 'on'/'off'/'dim'}
 *                    -- string enum NOT {brightness, colorTemperature, nightLightSwitch} (humidifier)
 *   Nightlight read  -- nightlight.nightLightSwitch (bool) + brightness parsed
 *   No timer         -- no setTimer command (Sprout Air has no timer feature)
 */
class LevoitSproutAirSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitSproutAir.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature (REQUIRED for all children)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: envelope peel
    // -------------------------------------------------------------------------

    def "applyStatus handles single-wrapped response (Bug Pattern #3)"() {
        given:
        def deviceData = [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1, manualSpeedLevel: 1,
                          childLockSwitch: 0, AQLevel: 1, PM25: 5, PM1: 3, PM10: 8, AQI: 95,
                          screenSwitch: 1, screenState: 1]
        def status = [code: 0, result: deviceData]

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch")  == "on"
        lastEventValue("mode")    == "auto"
        lastEventValue("fanSpeed") == 1
        noExceptionThrown()
    }

    def "applyStatus handles double-wrapped response defensively (Bug Pattern #3)"() {
        given:
        def deviceData = [powerSwitch: 1, workMode: "sleep", fanSpeedLevel: 1, manualSpeedLevel: 1,
                          childLockSwitch: 0, AQLevel: 1, PM25: 3, PM1: 2, PM10: 5, AQI: 98,
                          screenSwitch: 0, screenState: 0]
        def status = [code: 0, result: [code: 0, result: deviceData]]

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("mode")   == "sleep"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: fan speed 0 when device off (fanSpeedLevel=255 maps to 0)
    // -------------------------------------------------------------------------

    def "applyStatus maps fanSpeedLevel=255 to 0 when device off (Bug Pattern #6)"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_off as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")   == "off"
        (fixture.responses.device_off as Map).fanSpeedLevel == 255  // confirm fixture has 255
        lastEventValue("fanSpeed") == 0                              // driver maps 255 -> 0
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires once when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does not overwrite descriptionTextEnable=false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" } == null
        state.prefsSeeded == true
    }

    def "pref-seed fires only once (state.prefsSeeded gate)"() {
        given:
        state.prefsSeeded = true
        settings.descriptionTextEnable = null
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" } == null
    }

    // -------------------------------------------------------------------------
    // Happy path: canonical fixture
    // -------------------------------------------------------------------------

    def "applyStatus canonical fixture emits all expected events"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")         == "on"
        lastEventValue("mode")           == "auto"
        lastEventValue("fanSpeed")       == 1
        lastEventValue("airQualityIndex") == 1
        lastEventValue("pm25")           == 5
        lastEventValue("pm1")            == 3
        lastEventValue("pm10")           == 8
        lastEventValue("aqi")            == 95
        lastEventValue("humidity")       == 48
        lastEventValue("displayOn")      == "on"
        lastEventValue("childLock")      == "off"
    }

    def "applyStatus manual mode fixture sets mode=manual and fanSpeed=2"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")    == "manual"
        lastEventValue("fanSpeed") == 2
    }

    def "applyStatus poor AQ fixture emits AQLevel=4 and PM25=85"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_poor_aq as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("airQualityIndex") == 4
        lastEventValue("pm25")            == 85
        lastEventValue("voc")             == 180
        lastEventValue("co2")             == 900
    }

    // -------------------------------------------------------------------------
    // Switch payload: {powerSwitch, switchIdx} NOT {switch:'on', id}
    // -------------------------------------------------------------------------

    def "on() sends setSwitch {powerSwitch:1, switchIdx:0} (NOT Core line {switch:'on', id:0})"() {
        when:
        driver.on()

        then:
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call != null
        call.data.powerSwitch == 1
        call.data.switchIdx   == 0
        !call.data.containsKey("switch")
        !call.data.containsKey("id")
    }

    def "off() sends setSwitch {powerSwitch:0, switchIdx:0}"() {
        when:
        driver.off()

        then:
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call.data.powerSwitch == 0
        call.data.switchIdx   == 0
    }

    // -------------------------------------------------------------------------
    // Mode write-path: setPurifierMode {workMode: str}
    // -------------------------------------------------------------------------

    def "setMode('auto') sends setPurifierMode {workMode:'auto'}"() {
        when:
        driver.setMode("auto")

        then:
        def call = testParent.allRequests.find { it.method == "setPurifierMode" }
        call != null
        call.data.workMode == "auto"
        !call.data.containsKey("mode")  // NOT Core line 'mode' field
    }

    def "setMode('sleep') sends setPurifierMode {workMode:'sleep'}"() {
        when:
        driver.setMode("sleep")

        then:
        def call = testParent.allRequests.find { it.method == "setPurifierMode" }
        call != null
        call.data.workMode == "sleep"
    }

    def "setMode('manual') delegates to setLevel (NOT setPurifierMode) per pyvesync behavior"() {
        when:
        driver.setMode("manual")

        then:
        // Manual mode is established via setLevel (fan speed), NOT setPurifierMode
        testParent.allRequests.find { it.method == "setPurifierMode" } == null
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
    }

    def "setMode with invalid mode logs error and does not call API"() {
        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.find { it.method == "setPurifierMode" } == null
        testParent.allRequests.find { it.method == "setLevel" }         == null
    }

    // -------------------------------------------------------------------------
    // Fan speed: setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
    // Range 1-3; sets mode=manual
    // -------------------------------------------------------------------------

    def "setFanSpeed(2) sends setLevel {levelIdx:0, manualSpeedLevel:2, levelType:'wind'}"() {
        when:
        driver.setFanSpeed(2)

        then:
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
        call.data.levelIdx         == 0
        call.data.manualSpeedLevel == 2
        call.data.levelType        == "wind"
        !call.data.containsKey("level")  // NOT Core line 'level' field
        !call.data.containsKey("id")     // NOT Core line 'id' field
        !call.data.containsKey("type")   // NOT Core line 'type' field
    }

    def "setFanSpeed(2) also emits mode=manual event"() {
        when:
        driver.setFanSpeed(2)

        then:
        lastEventValue("fanSpeed") == 2
        lastEventValue("mode")     == "manual"
    }

    def "setFanSpeed clamps to 1-3 range"() {
        when:
        driver.setFanSpeed(0)   // below min -> 1
        driver.setFanSpeed(9)   // above max -> 3

        then:
        def calls = testParent.allRequests.findAll { it.method == "setLevel" }
        calls[0].data.manualSpeedLevel == 1
        calls[1].data.manualSpeedLevel == 3
    }

    // -------------------------------------------------------------------------
    // Display and child lock
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends setDisplay {screenSwitch:1}"() {
        when:
        driver.setDisplay("on")

        then:
        def call = testParent.allRequests.find { it.method == "setDisplay" }
        call != null
        call.data.screenSwitch == 1
    }

    def "setChildLock('on') sends setChildLock {childLockSwitch:1}"() {
        when:
        driver.setChildLock("on")

        then:
        def call = testParent.allRequests.find { it.method == "setChildLock" }
        call != null
        call.data.childLockSwitch == 1
    }

    def "applyStatus child lock on fixture emits childLock=on"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_child_lock_on as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("childLock") == "on"
    }

    // -------------------------------------------------------------------------
    // Nightlight write: setNightLight {night_light: 'on'/'off'/'dim'} string enum
    // CRITICAL: purifier nightlight uses setNightLight (NOT setLightStatus -- humidifier)
    // -------------------------------------------------------------------------

    def "setNightlightMode('on') sends setNightLight {night_light:'on'}"() {
        when:
        driver.setNightlightMode("on")

        then:
        def call = testParent.allRequests.find { it.method == "setNightLight" }
        call != null
        call.data.night_light == "on"
        // Must NOT call setLightStatus (that's the humidifier nightlight API)
        testParent.allRequests.find { it.method == "setLightStatus" } == null
    }

    def "setNightlightMode('dim') sends setNightLight {night_light:'dim'}"() {
        when:
        driver.setNightlightMode("dim")

        then:
        def call = testParent.allRequests.find { it.method == "setNightLight" }
        call != null
        call.data.night_light == "dim"
    }

    def "setNightlightMode('off') sends setNightLight {night_light:'off'}"() {
        when:
        driver.setNightlightMode("off")

        then:
        def call = testParent.allRequests.find { it.method == "setNightLight" }
        call != null
        call.data.night_light == "off"
    }

    def "setNightlightMode with invalid mode logs error and does not call API"() {
        when:
        driver.setNightlightMode("medium")

        then:
        testParent.allRequests.find { it.method == "setNightLight" } == null
    }

    // -------------------------------------------------------------------------
    // Nightlight read-path: nightlight sub-object (lowercase 'nightlight' key)
    // PurifierNightlight: nightLightSwitch=bool, brightness=int
    // -------------------------------------------------------------------------

    def "applyStatus parses nightlight sub-object (bool nightLightSwitch) when present"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_nightlight_on as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn")         == "on"
        lastEventValue("nightlightBrightness") == 60
    }

    def "applyStatus nightlight off (nightLightSwitch=false) emits nightlightOn=off"() {
        given:
        def fixture = loadYamlFixture("LAP-B851S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn") == "off"
    }

    def "applyStatus emits no nightlightOn event when nightlight key absent"() {
        given: "response without nightlight sub-object"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.events.findAll { it.name == "nightlightOn" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Temperature (divided by 10)
    // -------------------------------------------------------------------------

    def "applyStatus divides temperature by 10 (F*10 raw value)"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1, temperature: 720]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("temperature") == 72.0
    }

    // -------------------------------------------------------------------------
    // No timer
    // -------------------------------------------------------------------------

    def "driver has no setTimer method (Sprout Air has no timer feature)"() {
        when:
        driver.setTimer(30)

        then:
        thrown(MissingMethodException)
    }

    // ---- BP18: null-arg guard ----

    def "setMode(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver.setMode(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains("setMode") && it.contains("null") }
        testParent.allRequests.isEmpty()
    }

    def "setNightlightMode(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver.setNightlightMode(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains("setNightlightMode") && it.contains("null") }
        testParent.allRequests.isEmpty()
    }
}
