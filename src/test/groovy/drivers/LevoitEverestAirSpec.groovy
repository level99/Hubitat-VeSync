package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitEverestAir.groovy (Levoit EverestAir Air Purifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- fan speed 0 when device off (fanSpeedLevel=255 mapped to 0)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from canonical fixture emits expected events
 *
 *   Switch payload    -- on() produces setSwitch {powerSwitch:1, switchIdx:0}
 *                     -- NOT {switch:'on', id:0} (VeSyncAirBypass Core line difference)
 *   Mode write-path   -- setMode("auto")  sends setPurifierMode {workMode:'auto'}
 *                     -- setMode("sleep") sends setPurifierMode {workMode:'sleep'}
 *                     -- setMode("turbo") sends setPurifierMode {workMode:'turbo'}
 *                     -- setMode("manual") delegates to setLevel NOT setPurifierMode
 *                     -- field name is 'workMode' NOT 'mode' (Core line)
 *   TURBO mode        -- first-of-kind: handled via setPurifierMode (not separate toggle)
 *                     -- wire value "turbo" passes through 1:1 to attribute
 *   Fan speed         -- setFanSpeed(N) sends setLevel {levelIdx:0, manualSpeedLevel:N, levelType:'wind'}
 *                     -- NOT setLevel {level, id, type} (Core line VeSyncAirBypass)
 *                     -- range 1-3 (fan_levels=[1,2,3]); clamp enforced; sets mode=manual
 *   Fan speed off     -- fanSpeedLevel=255 in response maps to 0 (device off)
 *   AQ sensors        -- AQLevel, PM25, PM1, PM10, AQPercent parsed
 *   Filter life       -- filterLifePercent parsed
 *   Display           -- setDisplay sends {screenSwitch:1/0}
 *   Child lock        -- setChildLock sends {childLockSwitch:1/0}
 *   Light detection   -- setLightDetection sends {lightDetectionSwitch:1/0}
 *                     -- lightDetection attr = feature enabled/disabled
 *                     -- lightDetected attr = ambient light currently sensed
 *   Vent angle        -- fanRotateAngle passive read -> ventAngle attribute
 *                     -- no setVentAngle command (no write path in pyvesync)
 *   Reset filter      -- resetFilter() sends resetFilter {}
 *   Timer set/cancel  -- setTimer(3600,"off") sends addTimerV2 with V2 payload shape
 *                        {enabled, startAct:[{type:"powerSwitch", act:0}], tmgEvt:{clkSec:N}};
 *                        action "on" → act:1; secs<=0 routes to cancelTimer;
 *                        null seconds rejected (BP18); cancelTimer sends delTimerV2
 *                        with {id, subDeviceNo:0}; no-op when no timerId in state.
 *                        Regression guard: Tower Fan API names (setTimer/clearTimer) must NOT appear.
 *   BP18              -- setDisplay/setChildLock/setLightDetection(null) rejected with logWarn
 *                        (requireNotNull added as part of BP25 fix sweep)
 *   Bug Pattern #25   -- setDisplay/setChildLock/setLightDetection('ON') uppercase sends correct
 *                        integer payload (1 not 0) and emits lowercase event value
 */
class LevoitEverestAirSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitEverestAir.groovy"
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
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map
        def status = [code: 0, result: deviceData]

        when:
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map
        def status = [code: 0, result: deviceData]

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
        def deviceData = [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                          manualSpeedLevel: 2, childLockSwitch: 0, AQLevel: 1,
                          PM25: 5, screenState: 1, fanRotateAngle: 90]
        def status = [code: 0, result: deviceData]

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch")    == "on"
        lastEventValue("mode")      == "auto"
        lastEventValue("fanSpeed")  == 2
        lastEventValue("ventAngle") == 90
        noExceptionThrown()
    }

    def "applyStatus handles double-wrapped response defensively (Bug Pattern #3)"() {
        given:
        def deviceData = [powerSwitch: 1, workMode: "turbo", fanSpeedLevel: 3,
                          manualSpeedLevel: 3, childLockSwitch: 0, AQLevel: 4,
                          PM25: 78, screenState: 1, fanRotateAngle: 0]
        def status = [code: 0, result: [code: 0, result: deviceData]]

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("mode")   == "turbo"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: fan speed 0 when device off (fanSpeedLevel=255 maps to 0)
    // -------------------------------------------------------------------------

    def "applyStatus maps fanSpeedLevel=255 to 0 when device off (Bug Pattern #6)"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
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
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1, fanRotateAngle: 90]]
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
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1, fanRotateAngle: 0]]
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
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, childLockSwitch: 0, AQLevel: 1,
                                        PM25: 5, screenState: 1, fanRotateAngle: 0]]
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
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")          == "on"
        lastEventValue("mode")            == "auto"
        lastEventValue("fanSpeed")        == 2
        lastEventValue("airQualityIndex") == 1
        lastEventValue("pm25")            == 5
        lastEventValue("pm1")             == 3
        lastEventValue("pm10")            == 8
        lastEventValue("aqPercent")       == 95
        lastEventValue("filterLife")      == 87
        lastEventValue("displayOn")       == "on"
        lastEventValue("childLock")       == "off"
        lastEventValue("lightDetection")  == "on"
        lastEventValue("lightDetected")   == "no"
        lastEventValue("ventAngle")       == 90
    }

    // -------------------------------------------------------------------------
    // TURBO mode — first-of-kind in this codebase
    // -------------------------------------------------------------------------

    def "applyStatus turbo fixture emits mode=turbo (Bug Pattern #9 guard: attribute value 1:1)"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_on_turbo_mode as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")    == "turbo"
        lastEventValue("switch")  == "on"
        lastEventValue("fanSpeed") == 3
    }

    def "setMode('turbo') sends setPurifierMode {workMode:'turbo'} (first-of-kind convention)"() {
        when:
        driver.setMode("turbo")

        then:
        def call = testParent.allRequests.find { it.method == "setPurifierMode" }
        call != null
        call.data.workMode == "turbo"
        // turbo is handled via setPurifierMode (same path as auto/sleep), NOT a separate toggle
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.size() == 1
    }

    def "setMode('turbo') does NOT use a separate toggle method"() {
        // Confirms the convention: turbo-as-mode uses setPurifierMode, not a hypothetical
        // setTurbo / turn_on_turbo command. This test documents the deliberate design choice.
        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.find { it.method == "setTurbo" }       == null
        testParent.allRequests.find { it.method == "turnOnTurbo" }    == null
        testParent.allRequests.find { it.method == "setPurifierMode" } != null
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
        call.data.levelType == "wind"
    }

    def "setMode with invalid mode logs error and does not call API"() {
        when:
        driver.setMode("pet")  // not in EverestAir modes (Vital line only)

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

    def "setFanSpeed(2) emits fanSpeed=2 and mode=manual events"() {
        when:
        driver.setFanSpeed(2)

        then:
        lastEventValue("fanSpeed") == 2
        lastEventValue("mode")     == "manual"
    }

    def "setFanSpeed clamps to 1-3 range (fan_levels=[1,2,3] per device_map.py)"() {
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

    def "setDisplay('off') sends setDisplay {screenSwitch:0}"() {
        when:
        driver.setDisplay("off")

        then:
        def call = testParent.allRequests.find { it.method == "setDisplay" }
        call != null
        call.data.screenSwitch == 0
    }

    def "setChildLock('on') sends setChildLock {childLockSwitch:1}"() {
        when:
        driver.setChildLock("on")

        then:
        def call = testParent.allRequests.find { it.method == "setChildLock" }
        call != null
        call.data.childLockSwitch == 1
    }

    // -------------------------------------------------------------------------
    // Light detection (LIGHT_DETECT feature — first-of-kind in V2 purifier drivers)
    // -------------------------------------------------------------------------

    def "setLightDetection('on') sends setLightDetection {lightDetectionSwitch:1}"() {
        when:
        driver.setLightDetection("on")

        then:
        def call = testParent.allRequests.find { it.method == "setLightDetection" }
        call != null
        call.data.lightDetectionSwitch == 1
    }

    def "setLightDetection('off') sends setLightDetection {lightDetectionSwitch:0}"() {
        when:
        driver.setLightDetection("off")

        then:
        def call = testParent.allRequests.find { it.method == "setLightDetection" }
        call != null
        call.data.lightDetectionSwitch == 0
    }

    def "applyStatus lightDetectionSwitch=1 emits lightDetection=on"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, AQLevel: 1, PM25: 3,
                                        screenState: 0, fanRotateAngle: 0,
                                        lightDetectionSwitch: 1, environmentLightState: 0]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("lightDetection") == "on"
        lastEventValue("lightDetected")  == "no"
    }

    def "applyStatus environmentLightState=1 emits lightDetected=yes"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_light_detect_active as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("lightDetection") == "on"
        lastEventValue("lightDetected")  == "yes"
    }

    def "applyStatus lightDetectionSwitch=0 emits lightDetection=off"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, AQLevel: 1, PM25: 5,
                                        screenState: 1, fanRotateAngle: 90,
                                        lightDetectionSwitch: 0, environmentLightState: 0]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("lightDetection") == "off"
    }

    def "applyStatus emits no lightDetection event when lightDetectionSwitch absent"() {
        given: "response without lightDetectionSwitch field"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, AQLevel: 1, PM25: 5,
                                        screenState: 1, fanRotateAngle: 0]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.events.findAll { it.name == "lightDetection" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Vent angle (VENT_ANGLE feature — passive read, no write path in pyvesync)
    // -------------------------------------------------------------------------

    def "applyStatus fanRotateAngle=90 emits ventAngle=90"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("ventAngle") == 90
    }

    def "applyStatus fanRotateAngle=135 emits ventAngle=135 (varied angle fixture)"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_vent_angle_varied as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("ventAngle") == 135
    }

    def "applyStatus emits no ventAngle event when fanRotateAngle absent from response"() {
        given: "response without fanRotateAngle field"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, AQLevel: 1, PM25: 5,
                                        screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.events.findAll { it.name == "ventAngle" }.isEmpty()
    }

    def "driver has no setVentAngle command (vent angle is passive read-only in pyvesync)"() {
        // Confirms the design decision: no write path for fan rotation exists in pyvesync.
        // If firmware adds a setter, add setVentAngle in a patch release.
        when:
        driver.setVentAngle(90)

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // Reset filter
    // -------------------------------------------------------------------------

    def "resetFilter() sends resetFilter {} and emits filterLife=100"() {
        when:
        driver.resetFilter()

        then:
        def call = testParent.allRequests.find { it.method == "resetFilter" }
        call != null
        lastEventValue("filterLife") == 100
    }

    // -------------------------------------------------------------------------
    // No nightlight (EverestAir has no nightlight feature in pyvesync device_map.py)
    // -------------------------------------------------------------------------

    def "driver has no setNightlightMode command (EverestAir lacks NIGHTLIGHT feature flag)"() {
        when:
        driver.setNightlightMode("on")

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // Poor AQ path
    // -------------------------------------------------------------------------

    def "applyStatus poor AQ fixture emits AQLevel=4 and PM25=95"() {
        given:
        def fixture = loadYamlFixture("LAP-EL551S-WUS.yaml")
        def deviceData = fixture.responses.device_poor_aq as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("airQualityIndex") == 4
        lastEventValue("pm25")            == 95
        lastEventValue("mode")            == "turbo"
    }

    // -------------------------------------------------------------------------
    // Filter life and timer
    // -------------------------------------------------------------------------

    def "applyStatus emits filterLife from filterLifePercent field"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, AQLevel: 1, PM25: 5,
                                        screenState: 1, fanRotateAngle: 0,
                                        filterLifePercent: 72]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("filterLife") == 72
    }

    // -------------------------------------------------------------------------
    // Timer set and cancel — EverestAir is VeSyncAirBaseV2, uses addTimerV2 / delTimerV2
    // NOT setTimer / clearTimer (Tower Fan / Core line shape).
    // Payload shape (set): {enabled:true, startAct:[{type:"powerSwitch", act:0|1}], tmgEvt:{clkSec:<seconds>}}
    // Payload shape (cancel): {id: <timerId>, subDeviceNo: 0}
    // -------------------------------------------------------------------------

    def "setTimer(3600, 'off') sends addTimerV2 with V2 payload shape (NOT Tower Fan setTimer)"() {
        // Regression guard: before this fix, setTimer sent 'setTimer' + {action, total} — Tower Fan
        // shape — which VeSyncAirBaseV2 firmware silently rejects. Must now be addTimerV2.
        when:
        driver.setTimer(3600, "off")

        then: "addTimerV2 request was sent (not the old Tower Fan 'setTimer' API name)"
        def req = testParent.allRequests.find { it.method == "addTimerV2" }
        req != null

        and: "payload matches PurifierV2TimerPayloadData shape"
        req.data.enabled == true
        req.data.startAct instanceof List
        req.data.startAct[0].type == "powerSwitch"
        req.data.startAct[0].act == 0             // "off" action → act:0
        req.data.tmgEvt instanceof Map
        req.data.tmgEvt.clkSec == 3600

        and: "Tower Fan payload fields are absent"
        !req.data.containsKey("action")
        !req.data.containsKey("total")

        and: "Tower Fan API name was NOT used"
        testParent.allRequests.find { it.method == "setTimer" } == null
    }

    def "setTimer(60, 'on') sends addTimerV2 with act:1 (power-on action)"() {
        when:
        driver.setTimer(60, "on")

        then:
        def req = testParent.allRequests.find { it.method == "addTimerV2" }
        req != null
        req.data.startAct[0].act == 1             // "on" action → act:1
        req.data.tmgEvt.clkSec == 60
    }

    def "setTimer(0, 'off') routes to cancelTimer (secs <= 0 short-circuit)"() {
        given: "a timer id is stored in state so cancelTimer can be observed"
        state.timerId = 99

        when:
        driver.setTimer(0, "off")

        then: "delTimerV2 API call was made (not addTimerV2)"
        testParent.allRequests.find { it.method == "delTimerV2" } != null
        testParent.allRequests.find { it.method == "addTimerV2" } == null
    }

    def "setTimer(null, 'off') is rejected with logWarn and no API call (BP18)"() {
        when:
        driver.setTimer(null, "off")

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "addTimerV2" }.isEmpty()
        testParent.allRequests.findAll { it.method == "setTimer" }.isEmpty()   // guard: old API name also absent
    }

    def "cancelTimer with state.timerId set sends delTimerV2 with {id, subDeviceNo:0} and removes state.timerId"() {
        // Regression guard: before fix, cancelTimer sent 'clearTimer' + {id} — Tower Fan shape.
        // Must now be delTimerV2 + {id, subDeviceNo: 0}.
        given: "a timer id is stored in state"
        state.timerId = 42

        when:
        driver.cancelTimer()

        then: "delTimerV2 was called (not the old Tower Fan 'clearTimer' API name)"
        def req = testParent.allRequests.find { it.method == "delTimerV2" }
        req != null

        and: "payload contains id and subDeviceNo:0"
        req.data.id == 42
        req.data.subDeviceNo == 0

        and: "Tower Fan API name was NOT used"
        testParent.allRequests.find { it.method == "clearTimer" } == null
    }

    def "cancelTimer with no state.timerId is a no-op (no API call)"() {
        given: "no timer id in state"
        assert state.timerId == null

        when:
        driver.cancelTimer()

        then: "no delTimerV2 API call was made"
        testParent.allRequests.findAll { it.method == "delTimerV2" }.isEmpty()
        testParent.allRequests.findAll { it.method == "clearTimer" }.isEmpty()
        noExceptionThrown()
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

    def "setDisplay(null) does not throw and emits a WARN log (BP18 — EverestAir requireNotNull added)"() {
        // Before the BP25 fix, EverestAir's setDisplay lacked requireNotNull entirely.
        // The BP25 fix adds requireNotNull as the first guard (before the BP25 toLowerCase fix).
        when:
        driver.setDisplay(null)
        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()
        testLog.warns.any { it.contains("setDisplay") || it.contains("null") }
    }

    def "setChildLock(null) does not throw and emits a WARN log (BP18 — EverestAir requireNotNull added)"() {
        when:
        driver.setChildLock(null)
        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setChildLock" }.isEmpty()
        testLog.warns.any { it.contains("setChildLock") || it.contains("null") }
    }

    def "setLightDetection(null) does not throw and emits a WARN log (BP18 — EverestAir requireNotNull added)"() {
        when:
        driver.setLightDetection(null)
        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setLightDetection" }.isEmpty()
        testLog.warns.any { it.contains("setLightDetection") || it.contains("null") }
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #25: case-sensitivity — uppercase "ON"/"OFF" input inverts payload
    // EverestAir has no C3 gate, so the test shape is: payload must carry correct
    // integer value (1 for "ON", 0 for "OFF") and the event value must be lowercase.
    // -------------------------------------------------------------------------

    def "BP25: setDisplay('ON') sends screenSwitch:1 (not 0) and emits displayOn='on'"() {
        // Pre-fix: ("ON" == "on") is false → screenSwitch:0 sent (display off instead of on).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → screenSwitch:1 (correct).
        when:
        driver.setDisplay("ON")

        then: "setDisplay API call was made"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null

        and: "payload carries screenSwitch:1 (on), NOT 0 (off)"
        req.data.screenSwitch == 1

        and: "emitted displayOn event is lowercase 'on'"
        lastEventValue("displayOn") == "on"
    }

    def "BP25: setDisplay('OFF') sends screenSwitch:0 (not 1) and emits displayOn='off'"() {
        when:
        driver.setDisplay("OFF")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 0
        lastEventValue("displayOn") == "off"
    }

    def "BP25: setChildLock('ON') sends childLockSwitch:1 (not 0) and emits childLock='on'"() {
        // Pre-fix: ("ON" == "on") is false → childLockSwitch:0 (unlock instead of lock).
        // Post-fix: normalize → childLockSwitch:1 (correct).
        when:
        driver.setChildLock("ON")

        then:
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 1
        lastEventValue("childLock") == "on"
    }

    def "BP25: setLightDetection('ON') sends lightDetectionSwitch:1 (not 0) and emits lightDetection='on'"() {
        // Pre-fix: ("ON" == "on") is false → lightDetectionSwitch:0 (off instead of on).
        // Post-fix: normalize → lightDetectionSwitch:1 (correct).
        when:
        driver.setLightDetection("ON")

        then:
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 1
        lastEventValue("lightDetection") == "on"
    }

    def "BP25: setLightDetection('OFF') sends lightDetectionSwitch:0 (not 1) and emits lightDetection='off'"() {
        when:
        driver.setLightDetection("OFF")

        then:
        def req = testParent.allRequests.find { it.method == "setLightDetection" }
        req != null
        req.data.lightDetectionSwitch == 0
        lastEventValue("lightDetection") == "off"
    }

    // -------------------------------------------------------------------------
    // Tier-3 regression tests (v2.5 batch)
    // -------------------------------------------------------------------------

    def "on() re-entrance guard: second call while turningOn=true is a no-op (Fix 1)"() {
        // Regression guard: BP24-B fix adds state.turningOn guard to on().
        // If ensureSwitchOn() calls on() recursively, the second call must short-circuit.
        given:
        state.turningOn = true

        when:
        driver.on()

        then: "no setSwitch API call because re-entrance was blocked"
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        noExceptionThrown()
    }

    def "setFanSpeed(null) is rejected with logWarn and no API call (BP18 Fix 2)"() {
        // Pre-fix: (null as Integer) -> NPE in sandbox or ?: 1 silently set speed to 1.
        // Post-fix: requireNotNull rejects null before any coercion.
        when:
        driver.setFanSpeed(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
        testLog.warns.any { it.contains("setFanSpeed") || it.contains("null") }
    }

    def "setFanSpeed(2) calls ensureSwitchOn() (BP24-B Fix 2)"() {
        // Pre-fix: setFanSpeed had no ensureSwitchOn() call — device stayed off.
        // Post-fix: ensureSwitchOn() fires before the API write.
        // We verify by seeding switch=off and confirming on() was called (setSwitch fired).
        given: "device is off"
        testDevice.events.add([name: "switch", value: "off"])

        when:
        driver.setFanSpeed(2)

        then: "setSwitch (on) AND setLevel (fan speed) both sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null
        testParent.allRequests.find { it.method == "setLevel" && it.data.manualSpeedLevel == 2 } != null
    }
}
