package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitOasisMist1000S.groovy (Levoit OasisMist 1000S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level clamped to 0 when device is off (virtualLevel retained by API)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from canonical fixture emits expected events
 *
 *   Switch payload  -- on() produces setSwitch with {powerSwitch:1, switchIdx:0}
 *                   -- NOT {enabled:true, id:0} (VeSyncHumid200300S class difference)
 *   Mode write-path -- setMode("auto") sends {workMode:'auto'} (NOT 'humidity' -- contrast A603S)
 *                   -- setMode("sleep") sends {workMode:'sleep'}
 *                   -- setMode("manual") sends {workMode:'manual'}
 *                   -- field name is 'workMode' NOT 'mode' (VeSyncHumid200300S class difference)
 *   Mode read-path  -- 'auto' passes through; 'humidity'/'autoPro' normalize to 'auto'
 *   Mist API method -- setMistLevel(5) sends API method 'virtualLevel' NOT 'setVirtualLevel'
 *   Mist payload    -- payload is {levelIdx:0, virtualLevel:5, levelType:'mist'}
 *                   -- NOT {id:0, level:5, type:'mist'} (VeSyncHumid200300S difference)
 *   Target humidity -- setHumidity(55) sends {targetHumidity:55} camelCase top-level
 *                   -- NOT {target_humidity} or nested {configuration.auto_target_humidity}
 *   Display command -- setDisplay('on') sends {screenSwitch:1} integer
 *                   -- NOT {state:bool} (VeSyncHumid200300S) or {enabled, id}
 *   Display read    -- from screenState (preferred) or screenSwitch fallback
 *   Auto-stop write -- setAutoStop('on') sends setAutoStopSwitch {autoStopSwitch:1}
 *                   -- NOT setAutomaticStop {enabled:bool} (VeSyncHumid200300S)
 *   Auto-stop read  -- autoStopSwitch + autoStopState from response (both camelCase int)
 *   Water lacks     -- from waterLacksState (int 0|1) NOT water_lacks bool
 *   Water tank      -- waterTankLifted=1 also triggers waterLacks='yes'
 *   Nightlight read -- nightLight.nightLightSwitch + nightLight.brightness parsed from response
 *   Nightlight cmd  -- setNightlight gates on state.deviceType == 'LUH-M101S-WEUR'
 *   Nightlight US   -- non-WEUR setNightlight no-ops with INFO log (runtime gate)
 *   Nightlight split-- setNightlight(onOff) with no brightness → setNightLightStatus (toggle path)
 *                   -- setNightlight(onOff, N) with brightness → setLightStatus (brightness path)
 *   No warm mist    -- no setWarmMistLevel command (1000S has no warm mist hardware)
 *   No 200300S API  -- no {enabled, id} switch payload; no {mode:} mode field;
 *                   -- no setAutomaticStop; no configuration.auto_target_humidity
 */
class LevoitOasisMist1000SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitOasisMist1000S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature (REQUIRED for all children)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "single-wrapped 1000S canonical status"
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = purifierStatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = purifierStatusEnvelope(deviceData)

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
        given: "the device data wrapped once in {code:0, result:{...}} (typical 1000S response)"
        def deviceData = [powerSwitch: 1, workMode: "manual", humidity: 42, targetHumidity: 55,
                          mistLevel: 5, virtualLevel: 5, waterLacksState: 0, waterTankLifted: 0,
                          autoStopSwitch: 1, autoStopState: 0, screenSwitch: 1, screenState: 1]
        def status = [code: 0, result: deviceData]   // single-wrapped

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch")   == "on"
        lastEventValue("mode")     == "manual"
        lastEventValue("mistLevel") == 5
        noExceptionThrown()
    }

    def "applyStatus handles double-wrapped response defensively (Bug Pattern #3)"() {
        given: "double-wrapped {code:0, result:{code:0, result:{device data}}}"
        def deviceData = [powerSwitch: 1, workMode: "auto", humidity: 60, targetHumidity: 65,
                          mistLevel: 4, virtualLevel: 4, waterLacksState: 0, waterTankLifted: 0,
                          autoStopSwitch: 1, autoStopState: 0, screenSwitch: 1, screenState: 1]
        def status = [code: 0, result: [code: 0, result: deviceData]]   // double-wrapped

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("mode")   == "auto"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: mist level 0 when device is off
    // -------------------------------------------------------------------------

    def "applyStatus clamps mistLevel to 0 when switch is off (Bug Pattern #6)"() {
        given: "device off but virtualLevel still holds last-set value (5)"
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_off as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then: "switch is off and API retained virtualLevel=5, but driver should report 0"
        lastEventValue("switch")    == "off"
        (fixture.responses.device_off as Map).virtualLevel == 5   // confirm fixture has retained value
        lastEventValue("mistLevel") == 0                           // driver must clamp to 0
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires once when descriptionTextEnable is null (Bug Pattern #12)"() {
        given: "descriptionTextEnable is null (not yet set)"
        settings.descriptionTextEnable = null
        def deviceData = [powerSwitch: 1, workMode: "manual", humidity: 42, targetHumidity: 55,
                          virtualLevel: 3, waterLacksState: 0, waterTankLifted: 0,
                          autoStopSwitch: 1, autoStopState: 0, screenState: 1]
        def status = [code: 0, result: deviceData]

        when: "first applyStatus call"
        driver.applyStatus(status)

        then: "pref was seeded and state.prefsSeeded is true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does not overwrite descriptionTextEnable=false (Bug Pattern #12)"() {
        given: "user explicitly set descriptionTextEnable to false"
        settings.descriptionTextEnable = false
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 42,
                                        targetHumidity: 55, virtualLevel: 3,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]

        when:
        driver.applyStatus(status)

        then: "user choice preserved — not overwritten by pref-seed"
        testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" } == null
        state.prefsSeeded == true
    }

    def "pref-seed fires only once (state.prefsSeeded gate)"() {
        given: "pref-seed already ran"
        state.prefsSeeded = true
        settings.descriptionTextEnable = null
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 42,
                                        targetHumidity: 55, virtualLevel: 3,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]

        when:
        driver.applyStatus(status)

        then: "no updateSetting called — gate prevents repeat seed"
        testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" } == null
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from canonical fixture
    // -------------------------------------------------------------------------

    def "applyStatus canonical fixture emits all expected events"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")          == "on"
        lastEventValue("mode")            == "manual"
        lastEventValue("humidity")        == 42
        lastEventValue("targetHumidity")  == 55
        lastEventValue("mistLevel")       == 5
        lastEventValue("waterLacks")      == "no"
        lastEventValue("displayOn")       == "on"
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("autoStopReached") == "no"
    }

    def "applyStatus auto mode fixture sets mode=auto"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_mode as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")   == "auto"
        lastEventValue("switch") == "on"
    }

    def "applyStatus sleep mode fixture sets mode=sleep"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_sleep_mode as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")      == "sleep"
        lastEventValue("displayOn") == "off"
    }

    def "applyStatus water_lacks fixture emits waterLacks=yes"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_water_lacks as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("waterLacks") == "yes"
    }

    def "applyStatus waterTankLifted=1 also emits waterLacks=yes"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_tank_lifted as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("waterLacks") == "yes"
        lastEventValue("switch")     == "off"
    }

    def "applyStatus auto-stop-reached fixture emits autoStopReached=yes"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_auto_stop_reached as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("autoStopReached") == "yes"
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("switch")          == "off"
    }

    // -------------------------------------------------------------------------
    // Switch payload: {powerSwitch, switchIdx} NOT {enabled, id}
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with powerSwitch=1 and switchIdx=0"() {
        when:
        driver.on()

        then: "payload uses V2-style camelCase fields (NOT VeSyncHumid200300S style)"
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call != null
        call.data.powerSwitch == 1
        call.data.switchIdx   == 0
        !call.data.containsKey("enabled")  // NOT {enabled: bool}
        !call.data.containsKey("id")       // NOT {id: 0}
    }

    def "off() sends setSwitch with powerSwitch=0 and switchIdx=0"() {
        when:
        driver.off()

        then:
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call != null
        call.data.powerSwitch == 0
        call.data.switchIdx   == 0
    }

    // -------------------------------------------------------------------------
    // Mode write-path: {workMode: value} NOT {mode: value}
    // -------------------------------------------------------------------------

    def "setMode('auto') sends workMode='auto' (NOT 'humidity' -- contrast A603S)"() {
        when:
        driver.setMode("auto")

        then:
        def call = testParent.allRequests.find { it.method == "setHumidityMode" }
        call != null
        call.data.workMode == "auto"   // 1000S canonical: mist_modes[AUTO] = 'auto'
        !call.data.containsKey("mode") // NOT {mode: 'auto'} (VeSyncHumid200300S style)
    }

    def "setMode('sleep') sends workMode='sleep'"() {
        when:
        driver.setMode("sleep")

        then:
        def call = testParent.allRequests.find { it.method == "setHumidityMode" }
        call != null
        call.data.workMode == "sleep"
    }

    def "setMode('manual') sends workMode='manual'"() {
        when:
        driver.setMode("manual")

        then:
        def call = testParent.allRequests.find { it.method == "setHumidityMode" }
        call != null
        call.data.workMode == "manual"
    }

    def "setMode with invalid mode logs error and does not call API"() {
        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.find { it.method == "setHumidityMode" } == null
    }

    // -------------------------------------------------------------------------
    // Mode read-path normalization
    // -------------------------------------------------------------------------

    def "applyStatus workMode='humidity' normalizes to user-facing 'auto'"() {
        given: "some firmware reports 'humidity' for auto mode"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "humidity", humidity: 55,
                                        targetHumidity: 60, virtualLevel: 4,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    def "applyStatus workMode='autoPro' normalizes to user-facing 'auto'"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "autoPro", humidity: 55,
                                        targetHumidity: 60, virtualLevel: 4,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    // -------------------------------------------------------------------------
    // Mist level: API method 'virtualLevel', payload {levelIdx, virtualLevel, levelType}
    // -------------------------------------------------------------------------

    def "setMistLevel sends API method 'virtualLevel' (NOT 'setVirtualLevel')"() {
        when:
        driver.setMistLevel(5)

        then:
        def call = testParent.allRequests.find { it.method == "virtualLevel" }
        call != null
        // Confirm there is no call to 'setVirtualLevel' (that's VeSyncHumid200300S / A603S method)
        testParent.allRequests.find { it.method == "setVirtualLevel" } == null
    }

    def "setMistLevel(5) sends {levelIdx:0, virtualLevel:5, levelType:'mist'}"() {
        when:
        driver.setMistLevel(5)

        then:
        def call = testParent.allRequests.find { it.method == "virtualLevel" }
        call != null
        call.data.levelIdx     == 0
        call.data.virtualLevel == 5
        call.data.levelType    == "mist"
        !call.data.containsKey("id")    // NOT {id: 0} (VeSyncHumid200300S style)
        !call.data.containsKey("level") // NOT {level: N}
        !call.data.containsKey("type")  // NOT {type: 'mist'}
    }

    def "setMistLevel clamps to 1-9 range"() {
        when:
        driver.setMistLevel(0)    // below min → 1
        driver.setMistLevel(10)   // above max → 9

        then:
        def calls = testParent.allRequests.findAll { it.method == "virtualLevel" }
        calls[0].data.virtualLevel == 1
        calls[1].data.virtualLevel == 9
    }

    // -------------------------------------------------------------------------
    // Target humidity: {targetHumidity: N} camelCase top-level
    // -------------------------------------------------------------------------

    def "setHumidity(55) sends {targetHumidity:55} top-level camelCase"() {
        when:
        driver.setHumidity(55)

        then:
        def call = testParent.allRequests.find { it.method == "setTargetHumidity" }
        call != null
        call.data.targetHumidity == 55
        !call.data.containsKey("target_humidity")  // NOT snake_case (VeSyncHumid200300S)
    }

    def "applyStatus reads targetHumidity from top-level camelCase field"() {
        given: "response has top-level targetHumidity, no configuration sub-object"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 42,
                                        targetHumidity: 65, virtualLevel: 3,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("targetHumidity") == 65
    }

    def "setHumidity clamps to 30-80 range"() {
        when:
        driver.setHumidity(20)   // below min → 30
        driver.setHumidity(90)   // above max → 80

        then:
        def calls = testParent.allRequests.findAll { it.method == "setTargetHumidity" }
        calls[0].data.targetHumidity == 30
        calls[1].data.targetHumidity == 80
    }

    // -------------------------------------------------------------------------
    // Display: {screenSwitch: int} NOT {state: bool}
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends {screenSwitch:1} integer (NOT {state:bool})"() {
        when:
        driver.setDisplay("on")

        then:
        def call = testParent.allRequests.find { it.method == "setDisplay" }
        call != null
        call.data.screenSwitch == 1
        !call.data.containsKey("state")   // NOT {state: bool} (VeSyncHumid200300S style)
    }

    def "setDisplay('off') sends {screenSwitch:0}"() {
        when:
        driver.setDisplay("off")

        then:
        def call = testParent.allRequests.find { it.method == "setDisplay" }
        call.data.screenSwitch == 0
    }

    def "applyStatus reads display from screenState (preferred over screenSwitch)"() {
        given: "response has both screenState and screenSwitch; screenState=0 wins"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 42,
                                        targetHumidity: 55, virtualLevel: 3,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0,
                                        screenState: 0, screenSwitch: 1]]  // state=off, switch=on
        when:
        driver.applyStatus(status)

        then: "screenState wins over screenSwitch"
        lastEventValue("displayOn") == "off"
    }

    def "applyStatus falls back to screenSwitch when screenState absent"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 42,
                                        targetHumidity: 55, virtualLevel: 3,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0,
                                        screenSwitch: 1]]  // no screenState key
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("displayOn") == "on"
    }

    // -------------------------------------------------------------------------
    // Auto-stop: setAutoStopSwitch {autoStopSwitch: int}
    // -------------------------------------------------------------------------

    def "setAutoStop('on') sends setAutoStopSwitch {autoStopSwitch:1} (NOT setAutomaticStop)"() {
        when:
        driver.setAutoStop("on")

        then:
        def call = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        call != null
        call.data.autoStopSwitch == 1
        // Must NOT use the VeSyncHumid200300S method
        testParent.allRequests.find { it.method == "setAutomaticStop" } == null
    }

    def "setAutoStop('off') sends {autoStopSwitch:0}"() {
        when:
        driver.setAutoStop("off")

        then:
        def call = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        call.data.autoStopSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Nightlight read-path
    // -------------------------------------------------------------------------

    def "applyStatus parses nightLight sub-object when present (WEUR fixture)"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_weur_nightlight_on as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn")         == "on"
        lastEventValue("nightlightBrightness") == 70
    }

    def "applyStatus nightlight off (brightness=0) emits nightlightOn=off"() {
        given:
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_weur_nightlight_off as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn")         == "off"
        lastEventValue("nightlightBrightness") == 0
    }

    def "applyStatus emits no nightlightOn event when nightLight key absent (US variant)"() {
        given: "US variant: no nightLight sub-object in response"
        def fixture = loadYamlFixture("LUH-M101S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        assert !deviceData.containsKey("nightLight")   // confirm fixture has no nightLight

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then: "no nightlight events emitted"
        testDevice.events.findAll { it.name == "nightlightOn" }.isEmpty()
        testDevice.events.findAll { it.name == "nightlightBrightness" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Nightlight write-path: runtime gate on state.deviceType
    // -------------------------------------------------------------------------

    def "setNightlight no-ops with INFO log when state.deviceType is not WEUR"() {
        given: "device type is US variant"
        state.deviceType = "LUH-M101S-WUS"

        when:
        driver.setNightlight("on", 70)

        then: "no API call made"
        testParent.allRequests.find { it.method == "setLightStatus" }     == null
        testParent.allRequests.find { it.method == "setNightLightStatus" } == null
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Nightlight write-path: toggle vs brightness split (BLOCKING #1 fix)
    //
    // pyvesync uses TWO distinct API methods:
    //   toggle_nightlight()         → setNightLightStatus {nightLightSwitch: int}
    //   set_nightlight_brightness() → setLightStatus      {brightness: N, nightLightSwitch: int}
    //
    // Driver routes: setNightlight(onOff) with no brightness → setNightLightStatus
    //                setNightlight(onOff, N) with explicit brightness → setLightStatus
    // -------------------------------------------------------------------------

    def "setNightlight('on') with no brightness sends setNightLightStatus {nightLightSwitch:1}"() {
        given:
        state.deviceType = "LUH-M101S-WEUR"

        when: "called with only onOff, no brightness arg"
        driver.setNightlight("on")

        then: "toggle path used (NOT brightness path)"
        def call = testParent.allRequests.find { it.method == "setNightLightStatus" }
        call != null
        call.data.nightLightSwitch == 1
        // Must NOT call setLightStatus (that's the brightness path)
        testParent.allRequests.find { it.method == "setLightStatus" } == null
    }

    def "setNightlight('off') with no brightness sends setNightLightStatus {nightLightSwitch:0}"() {
        given:
        state.deviceType = "LUH-M101S-WEUR"

        when:
        driver.setNightlight("off")

        then:
        def call = testParent.allRequests.find { it.method == "setNightLightStatus" }
        call != null
        call.data.nightLightSwitch == 0
        testParent.allRequests.find { it.method == "setLightStatus" } == null
    }

    def "setNightlight('on', 70) sends setLightStatus {brightness:70, nightLightSwitch:1}"() {
        given:
        state.deviceType = "LUH-M101S-WEUR"

        when: "called with explicit brightness -- brightness path"
        driver.setNightlight("on", 70)

        then:
        def call = testParent.allRequests.find { it.method == "setLightStatus" }
        call != null
        call.data.brightness       == 70
        call.data.nightLightSwitch == 1
        // Must NOT call setNightLightStatus (that's the toggle path)
        testParent.allRequests.find { it.method == "setNightLightStatus" } == null
    }

    def "setNightlight('off', 0) sends setLightStatus {brightness:0, nightLightSwitch:0}"() {
        given:
        state.deviceType = "LUH-M101S-WEUR"

        when:
        driver.setNightlight("off", 0)

        then:
        def call = testParent.allRequests.find { it.method == "setLightStatus" }
        call != null
        call.data.brightness       == 0
        call.data.nightLightSwitch == 0
    }

    // -------------------------------------------------------------------------
    // No warm mist: driver has no setWarmMistLevel command
    // -------------------------------------------------------------------------

    def "driver has no setWarmMistLevel method (1000S has no warm mist hardware)"() {
        when:
        driver.setWarmMistLevel(2)

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // Info HTML: local variables only (no device.currentValue race; BP#7)
    // -------------------------------------------------------------------------

    def "applyStatus sets info attribute with local variable values"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", humidity: 55,
                                        targetHumidity: 60, virtualLevel: 4,
                                        waterLacksState: 0, waterTankLifted: 0,
                                        autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        def info = lastEventValue("info")
        info != null
        info.contains("55")   // humidity
        info.contains("60")   // target humidity
        info.contains("4")    // mist level
    }
}
