package drivers

import spock.lang.Unroll
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
    // AirQuality capability coherence (v2.10): the standard `airQuality` attribute
    // must be EMITTED as a US-AQI (0-500) DERIVED FROM PM2.5 via the shared
    // LevoitChildBase.usAqiFromPm25 helper — same semantics as the Core purifiers.
    // (Was declared-but-dead — only the 1-4 airQualityIndex + custom aqi were emitted.)
    // NON-VACUITY: deleting the airQuality sendEvent in applyStatus makes airQuality
    // null and these assertions go RED.
    // Expected value: PM2.5=18 -> EPA band 12.1-35.4 -> US-AQI 63 (computed via the ladder).
    // -------------------------------------------------------------------------

    def "applyStatus emits airQuality as a US-AQI derived from PM2.5 (dead-capability fix, v2.10)"() {
        given: "a response carrying PM2.5=18 (US-AQI 63), AQLevel=2, and the custom AQI=60"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 2,
                                        PM25: 18, PM1: 10, PM10: 22, AQI: 60,
                                        screenSwitch: 1, screenState: 1]]

        when:
        driver.applyStatus(status)

        then: "airQuality is the PM2.5-derived US-AQI (NOT AQLevel, NOT the custom aqi), matching Core"
        lastEventValue("airQuality") != null
        (lastEventValue("airQuality") as BigDecimal) == 63

        and: "airQualityIndex (1-4 level) and the custom aqi (Levoit index) are unchanged"
        lastEventValue("airQualityIndex") == 2
        lastEventValue("aqi") == 60
    }

    def "applyStatus emits no airQuality event when PM2.5 absent from response"() {
        given: "response without PM25 (airQuality is gated on PM2.5, like the Core line)"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", fanSpeedLevel: 1,
                                        manualSpeedLevel: 1, childLockSwitch: 0, AQLevel: 2,
                                        AQI: 60, screenSwitch: 1, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        testDevice.events.findAll { it.name == "airQuality" }.isEmpty()
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

    def "applyStatus off-state with manualSpeedLevel fallback clamps fanSpeed to 0 (Bug Pattern #6)"() {
        given: "device off, fanSpeedLevel ABSENT (not the 255 sentinel) + retained manualSpeedLevel=3"
        // NON-VACUOUS clampOffLevel guard: the 255-sentinel test above maps 255->0 BEFORE the clamp,
        // so it passes with or without clampOffLevel. This exercises the manualSpeedLevel fallback
        // branch (fanSpeedLevel absent), where fanSpeedRaw=3 and ONLY clampOffLevel forces it to 0.
        // Goes RED if clampOffLevel is reverted (would emit fanSpeed=3 on an off device).
        def deviceData = [powerSwitch: 0, workMode: "manual", manualSpeedLevel: 3,
                          childLockSwitch: 0, AQLevel: 1, PM25: 5, PM1: 2, PM10: 5, AQI: 98,
                          screenSwitch: 0, screenState: 0]

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")   == "off"
        lastEventValue("fanSpeed") == 0    // depends on clampOffLevel, NOT the 255 mapping
        lastEventValue("info")?.contains("Fan: 0")
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

    // Cluster 3 (v2.10): off()'s power-write failure now routes through reportWriteError
    // so it participates in the BP22 child-side network-outage dedup. Discriminating:
    // pre-fix (raw logError "Power off failed" + recordError) this goes RED — the branch
    // logged an ERROR despite the known outage.
    def "off() power-write failure during a known outage is DEBUG-suppressed, not ERROR/recorded (BP22 — cluster 3)"() {
        given: "parent reports a known outage; cloud returns an inner -1 (genuine write failure)"
        settings.descriptionTextEnable = false
        settings.debugOutput = true   // logDebug is debugOutput-gated
        testParent.networkUnreachable = true
        testParent.cannedResponse = TestParent.innerErrorResponse()

        when:
        driver.off()

        then: "the setSwitch power-off write was attempted"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 } != null

        and: "the failure is DEBUG-suppressed (BP22), not ERROR spam"
        testLog.debugs.any { it.contains("Power off failed") && it.contains("BP22") }
        !testLog.errors.any { it.contains("Power off failed") }

        and: "no diagnostics ring-buffer record was written (recordError skipped)"
        (state.errorHistory == null) || (state.errorHistory.isEmpty())
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

    @Unroll
    def "setNightlightMode('#mode') sends setNightLight {night_light:'#mode'}"() {
        when:
        driver.setNightlightMode(mode)

        then:
        def call = testParent.allRequests.find { it.method == "setNightLight" }
        call != null
        call.data.night_light == mode

        where:
        mode << ["dim", "off"]
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

    // ---- BP25: setDisplay and setChildLock ----

    @Unroll
    def "BP25: #setter('#input') sends #field:#payloadVal and emits #attr='#expectedVal' (BP25 regression guard)"() {
        // Pre-fix: (onOff == "on") where onOff is uppercase evaluates false → inverted integer payload.
        // Post-fix: toLowerCase() normalizes → correct integer payload.
        given:
        settings.descriptionTextEnable = false

        when:
        driver."$setter"(input)

        then: "API call sent with the correct integer payload"
        def req = testParent.allRequests.find { it.method == apiMethod }
        req != null
        req.data[field] == payloadVal

        and: "emitted event value is lowercase"
        lastEventValue(attr) == expectedVal

        where:
        setter         | apiMethod      | input | field             | payloadVal | attr        | expectedVal
        "setDisplay"   | "setDisplay"   | "ON"  | "screenSwitch"    | 1          | "displayOn" | "on"
        "setDisplay"   | "setDisplay"   | "OFF" | "screenSwitch"    | 0          | "displayOn" | "off"
        "setChildLock" | "setChildLock" | "ON"  | "childLockSwitch" | 1          | "childLock" | "on"
    }

    def "BP25: setChildLock('OFF') sends childLockSwitch:0 (BP25 regression guard)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setChildLock("OFF")

        then: "setChildLock sent with childLockSwitch:0 (unlocked)"
        def req = testParent.allRequests.find { it.method == "setChildLock" }
        req != null
        req.data.childLockSwitch == 0
    }

    @Unroll
    def "BP25-truthy: #setter('#input') sends #field:1 and emits #attr='on' (truthy-canon)"() {
        // Pre-fix: v = input.toLowerCase() stored truthy variant verbatim.
        // Post-fix: canon = (v in [...]) ? "on" : "off" = "on"; sendEvent(value:"on").
        given:
        settings.descriptionTextEnable = false

        when:
        driver."$setter"(input)

        then: "API call sent with the correct integer payload"
        def req = testParent.allRequests.find { it.method == apiMethod }
        req != null
        req.data[field] == 1

        and: "emitted attribute is canonical 'on', not raw truthy variant"
        lastEventValue(attr) == "on"

        where:
        setter         | apiMethod      | input  | field             | attr
        "setDisplay"   | "setDisplay"   | "true" | "screenSwitch"    | "displayOn"
        "setDisplay"   | "setDisplay"   | "1"    | "screenSwitch"    | "displayOn"
        "setChildLock" | "setChildLock" | "true" | "childLockSwitch" | "childLock"
        "setChildLock" | "setChildLock" | "1"    | "childLockSwitch" | "childLock"
    }

    def "BP25-truthy: C3 gate suppresses setDisplay when attribute already 'on' and input is 'true'"() {
        // Regression guard: C3 gate uses canon (not raw 'true'), so 'true' input with
        // attribute already 'on' correctly triggers gate suppression.
        given:
        testDevice.events.add([name: "displayOn", value: "on"])
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("true")

        then: "C3 gate suppressed the call because canon=='on'==currentValue"
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Regression guards — v2.5 null-input and coercion fixes
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

    def "off() re-entrance guard: second call while turningOff=true is a no-op"() {
        // Regression guard: off() symmetric re-entrance guard (state.turningOff).
        // Defensive symmetry with on(); a re-entrant off() must short-circuit.
        given:
        state.turningOff = true

        when:
        driver.off()

        then: "no setSwitch API call because re-entrance was blocked"
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        noExceptionThrown()
    }

    def "setFanSpeed(null) is rejected with logWarn and no API call (BP18 Fix 2)"() {
        // Pre-fix: (null as Integer) -> NPE in sandbox.
        // Post-fix: requireNotNull rejects null before any coercion.
        when:
        driver.setFanSpeed(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
        testLog.warns.any { it.contains("setFanSpeed") || it.contains("null") }
    }

    def "setFanSpeed(2) calls ensureSwitchOn() when device is off (BP24-B Fix 2)"() {
        // Pre-fix: setFanSpeed had no ensureSwitchOn() call — device stayed off.
        // Post-fix: ensureSwitchOn() fires before the API write.
        given: "device is off"
        testDevice.events.add([name: "switch", value: "off"])

        when:
        driver.setFanSpeed(2)

        then: "setSwitch (on) AND setLevel (fan speed) both sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null
        testParent.allRequests.find { it.method == "setLevel" && it.data.manualSpeedLevel == 2 } != null
    }

    @Unroll
    def "BP26: setFanSpeed('#badInput') does not throw on non-numeric input from Rule Machine (Sprout Air)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setFanSpeed called with non-numeric input (Rule Machine blank/typo slot)"
        driver.setFanSpeed(badInput)
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()

        where:
        badInput << ["", "abc"]
    }

    // -------------------------------------------------------------------------
    // C3 idempotency gate — setDisplay / setChildLock must not re-call API when
    // the value already matches the current attribute (Sprout Air D1 fix)
    // -------------------------------------------------------------------------

    // C3 gate is a state-change guard: matching current value suppresses the cloud call;
    // differing value lets it fire. FAILS on pre-D1-fix code (gate absent → call always fires).

    @Unroll
    def "C3: #setter with already-current value makes no hubBypass call (Sprout Air)"() {
        given: "#attr attribute is already 'on'"
        testDevice.events.add([name: attr, value: "on"])

        when: "#setter called with the same value"
        driver."$setter"("on")

        then: "no #apiMethod API call was made (C3 gate suppressed it)"
        testParent.allRequests.findAll { it.method == apiMethod }.isEmpty()
        noExceptionThrown()

        where:
        setter         | apiMethod      | attr
        "setDisplay"   | "setDisplay"   | "displayOn"
        "setChildLock" | "setChildLock" | "childLock"
    }

    @Unroll
    def "C3: #setter with different value does make a hubBypass call (Sprout Air)"() {
        given: "#attr attribute is 'off'"
        testDevice.events.add([name: attr, value: "off"])

        when: "#setter called with 'on'"
        driver."$setter"("on")

        then: "#apiMethod API call was made"
        testParent.allRequests.any { it.method == apiMethod }

        where:
        setter         | apiMethod      | attr
        "setDisplay"   | "setDisplay"   | "displayOn"
        "setChildLock" | "setChildLock" | "childLock"
    }

    // -------------------------------------------------------------------------
    // BP24 SHOULD-ON: setMode from off-state turns the device on (v2.9).
    // The OUTER ensureSwitchOn() in setMode is the load-bearing guard for BOTH the
    // mode and manual paths. NON-VACUITY: deleting that ensureSwitchOn() line makes
    // the on() assertion for the non-manual path go RED (expected revert -> RED).
    // -------------------------------------------------------------------------

    def "BP24: setMode('auto') from off-state turns the device on before the mode command"() {
        given: "device is off, turningOn flag clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "setMode('auto') is called on an off device"
        driver.setMode("auto")

        then: "on() fired — setSwitch with powerSwitch=1 was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null

        and: "the mode command (setPurifierMode) was sent"
        testParent.allRequests.find { it.method == "setPurifierMode" } != null
    }

    def "BP24: invalid mode on an off device does NOT auto-power it on (validate-before-on)"() {
        given: "device is off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "an invalid mode is sent"
        driver.setMode("turbo")

        then: "no on() fired (validation rejected before ensureSwitchOn)"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null

        and: "no mode command was sent"
        testParent.allRequests.find { it.method == "setPurifierMode" } == null
    }

    // -------------------------------------------------------------------------
    // FanControl (setSpeed enum) + SwitchLevel (setLevel) — v2.10 capability add.
    // setSpeed/setLevel resolve to a 1-3 speed and route through the SINGLE shared
    // setFanSpeed cloud-write path (one "fanSpeed" dedup slot). sleep/auto delegate
    // to setMode; off/on -> off()/on(). Sprout Air has no turbo mode.
    // -------------------------------------------------------------------------

    @Unroll
    def "FanControl: setSpeed('#input') maps to setLevel manualSpeedLevel=#level (via setFanSpeed)"() {
        // NON-VACUITY: reverting setSpeed (or its low/medium/high -> setFanSpeed routing) makes
        // the setLevel cloud call absent / wrong, so manualSpeedLevel assertion goes RED.
        given: "device already on so ensureSwitchOn is a no-op"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed(input)

        then: "a single setLevel (fan-speed) cloud write with the mapped manualSpeedLevel"
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
        call.data.manualSpeedLevel == level
        call.data.levelType == "wind"

        where:
        input    | level
        "low"    | 1
        "medium" | 2
        "high"   | 3
    }

    @Unroll
    def "FanControl: setSpeed('#input') delegates to setMode (setPurifierMode workMode='#input'), no fan write"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed(input)

        then: "setPurifierMode with the mode, and NO setLevel fan-speed write"
        def call = testParent.allRequests.find { it.method == "setPurifierMode" }
        call != null
        call.data.workMode == input
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()

        where:
        input << ["sleep", "auto"]
    }

    def "FanControl: setSpeed('off') turns the device off (no fan write, no auto-on)"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed("off")

        then: "setSwitch powerSwitch=0 sent; no fan-speed write; no power-on"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 } != null
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    def "FanControl: setSpeed('turbo') is rejected (Sprout has no turbo mode) — no cloud call"() {
        given: "device off, so a wrongful auto-on would also be observable"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])

        when:
        driver.setSpeed("turbo")

        then: "no fan write, no mode write, no auto-on (validate-before-on)"
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.isEmpty()
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } == null
    }

    def "FanControl: setSpeed(null) is rejected with a warning, no cloud call (BP18)"() {
        when:
        driver.setSpeed(null)

        then:
        noExceptionThrown()
        testParent.allRequests.isEmpty()
        testLog.warns.any { it.contains("setSpeed") && it.contains("null") }
    }

    def "BP24: setSpeed('low') from off-state turns the device on then sets the speed"() {
        given: "device off, flags clear"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when:
        driver.setSpeed("low")

        then: "on() fired AND the fan-speed write was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null
        testParent.allRequests.find { it.method == "setLevel" && it.data.manualSpeedLevel == 1 } != null
    }

    @Unroll
    def "SwitchLevel: setLevel(#pct) maps to fan level #lvl and reconciles level to band #banded"() {
        // The final `level` reconciles to the banded value (setFanSpeed's optimistic emit, same
        // mapping as applyStatus) — so the dashboard slider does not flip again at poll.
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(pct)

        then: "the banded fan-speed write sent, and level settled on the banded value"
        lastEventValue("level") == banded
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
        call.data.manualSpeedLevel == lvl

        where:
        pct | lvl | banded
        1   | 1   | 33
        33  | 1   | 33
        34  | 2   | 66
        66  | 2   | 66
        67  | 3   | 100
        100 | 3   | 100
    }

    def "SwitchLevel: setLevel(0) turns the device off (no fan write)"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(0)

        then: "off() sent; no fan-speed write"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 } != null
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    def "SwitchLevel: setLevel('abc') is ignored (BP28 — non-numeric != 0), device unchanged"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel("abc")

        then: "no off(), no fan write — left as-is with a warning"
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        testParent.allRequests.findAll { it.method == "setLevel" }.isEmpty()
    }

    def "BP24: setLevel(50) from off-state turns the device on"() {
        given: "device off"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        testParent.allRequests.clear()

        when:
        driver.setLevel(50)

        then: "on() fired AND the banded fan-speed write sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null
        testParent.allRequests.find { it.method == "setLevel" && it.data.manualSpeedLevel == 2 } != null
    }

    def "setLevel(val, duration) 2-arg overload delegates to setLevel(val) (BP1)"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(67, 5)

        then: "same banded write as the 1-arg form (duration ignored)"
        noExceptionThrown()
        testParent.allRequests.find { it.method == "setLevel" && it.data.manualSpeedLevel == 3 } != null
    }

    def "FanControl: initialize() publishes supportedFanSpeeds picker list exactly (RULE47, NIT3)"() {
        // NON-VACUITY: reverting the initialize() emit makes supportedFanSpeeds null, RED.
        // Exact-list equality (not `contains`) also catches a dropped off/on in the picker —
        // a `contains` check would pass on a wrong list as long as the probed tokens are present.
        when:
        driver.initialize()

        then:
        def v = lastEventValue("supportedFanSpeeds")
        v != null
        new groovy.json.JsonSlurper().parseText(v as String) == ["off","low","medium","high","sleep","auto","on"]
    }

    @Unroll
    def "applyStatus emits speed='#expectedSpeed' for mode=#mode (on) — FanControl mirror"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: mode, fanSpeedLevel: 2,
                                        manualSpeedLevel: 2, childLockSwitch: 0, AQLevel: 1, PM25: 5,
                                        screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("speed") == expectedSpeed

        where:
        mode     | expectedSpeed
        "manual" | "medium"     // fanSpeedLevel 2 -> "medium"
        "auto"   | "auto"
        "sleep"  | "sleep"
    }

    def "applyStatus emits level mirroring active fan level (on)"() {
        given: "device on, fan level 3"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", fanSpeedLevel: 3,
                                        manualSpeedLevel: 3, childLockSwitch: 0, AQLevel: 1, PM25: 5,
                                        screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("level") == 100
    }

    def "BP6: applyStatus emits speed='off' and level=0 when device off with retained manualSpeedLevel"() {
        // NON-VACUITY for level: fanSpeedLevel ABSENT + retained manualSpeedLevel=3; only clampOffLevel
        // forces fanSpeedRaw -> 0 -> level 0. Reverting clampOffLevel makes level=100, RED.
        // NON-VACUITY for speed: the !powerOn branch forces "off"; reverting it reports the mode speed.
        given: "device off, fanSpeedLevel absent, retained manualSpeedLevel=3"
        def status = [code: 0, result: [powerSwitch: 0, workMode: "manual", manualSpeedLevel: 3,
                                        childLockSwitch: 0, AQLevel: 1, PM25: 5, screenState: 0]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("speed")  == "off"
        lastEventValue("level")  == 0
    }

    def "BP30: a burst of identical setSpeed('low') coalesces via the shared fanSpeed dedup slot"() {
        // NON-VACUITY: setSpeed routes through setFanSpeed, which dedups on the "fanSpeed" slot.
        // Two identical setSpeed('low') within the window => ONE cloud write. Reverting setFanSpeed's
        // dedup (or routing setSpeed around it) makes this 2 writes, RED.
        given: "device on, flags clear so dedup is active (not bypassed by an in-flight power-on)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOn")
        state.remove("powerOnPending")
        testParent.allRequests.clear()

        when: "two identical setSpeed calls in a burst"
        driver.setSpeed("low")
        driver.setSpeed("low")

        then: "only ONE setLevel cloud write (the 2nd is deduped)"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 1
    }

    // -------------------------------------------------------------------------
    // Optimistic emit of the standard FanControl/SwitchLevel attrs on the COMMAND
    // path (no applyStatus call) so dashboard/voice tiles reflect the command
    // immediately. NON-VACUITY: reverting the setFanSpeed/setSpeed optimistic emits
    // makes these go RED (speed/level stay null or un-reconciled).
    // -------------------------------------------------------------------------

    def "optimistic: setSpeed('low') emits speed/level immediately (no poll)"() {
        given: "device on so the fan write succeeds"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed("low")

        then: "standard attrs update from the command path alone"
        lastEventValue("speed") == "low"
        lastEventValue("level") == 33
    }

    def "optimistic: setLevel(50) reconciles level to the band and emits speed immediately (no poll)"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setLevel(50)

        then: "setLevel emits 50, then setFanSpeed's optimistic emit reconciles to band 66 + medium"
        lastEventValue("level") == 66
        lastEventValue("speed") == "medium"
    }

    def "optimistic: setFanSpeed(3) (legacy command) also updates speed/level"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setFanSpeed(3)

        then:
        lastEventValue("speed") == "high"
        lastEventValue("level") == 100
    }

    @Unroll
    def "optimistic: setSpeed('#input') emits speed='#expected' immediately"() {
        given: "device on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed(input)

        then:
        lastEventValue("speed") == expected

        where:
        input   | expected
        "off"   | "off"
        "sleep" | "sleep"
        "auto"  | "auto"
    }

    // -------------------------------------------------------------------------
    // FanControl: cycleSpeed (v2.10) — required by the FanControl capability
    // alongside setSpeed. Advances 1 -> 2 -> 3 -> 1 via the shared setFanSpeed path.
    // Was absent pre-fix → a dashboard/RM cycleSpeed threw MissingMethodException.
    // -------------------------------------------------------------------------

    @Unroll
    def "FanControl: cycleSpeed advances fan level #from -> #to (wraps 3 -> 1)"() {
        // NON-VACUITY: reverting the wrap math (or routing around setFanSpeed) makes the
        // manualSpeedLevel write wrong/absent, RED.
        given: "device on, lastFanSpeed = #from"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.lastFanSpeed = from
        testParent.allRequests.clear()

        when:
        driver.cycleSpeed()

        then: "a single setLevel (fan-speed) cloud write at the next level"
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
        call.data.manualSpeedLevel == to

        where:
        from | to
        1    | 2
        2    | 3
        3    | 1
    }

    def "FanControl: cycleSpeed with null state.lastFanSpeed cycles to level 1"() {
        given: "device on, no lastFanSpeed seeded"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("lastFanSpeed")
        testParent.allRequests.clear()

        when:
        driver.cycleSpeed()

        then:
        def call = testParent.allRequests.find { it.method == "setLevel" }
        call != null
        call.data.manualSpeedLevel == 1
    }

    def "BP24-A: cycleSpeed from off-state turns the device on then sets a speed"() {
        // NON-VACUITY: cycleSpeed's ensureSwitchOn (and setFanSpeed's) is the auto-on; deleting it
        // makes the setSwitch powerSwitch=1 assertion go RED.
        given: "device off, flags clear, lastFanSpeed = 1"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        state.lastFanSpeed = 1
        testParent.allRequests.clear()

        when:
        driver.cycleSpeed()

        then: "on() fired AND a fan-speed write was sent"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 } != null
        testParent.allRequests.find { it.method == "setLevel" } != null
    }

    // -------------------------------------------------------------------------
    // NIT1: setSpeed('on') with no prior fan speed emits the valid ENUM 'on'
    // (not null) so the FanControl tile is not left stale. NON-VACUITY: reverting
    // to the `if (state.lastFanSpeed) ...` form leaves speed unset here, RED.
    // -------------------------------------------------------------------------

    def "NIT1: setSpeed('on') with null lastFanSpeed emits speed='on'"() {
        given: "device off so on() runs, no lastFanSpeed seeded"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("lastFanSpeed")
        testParent.allRequests.clear()

        when:
        driver.setSpeed("on")

        then:
        lastEventValue("speed") == "on"
    }

    def "setSpeed('on') with a prior fan speed emits that named speed"() {
        given: "device off, lastFanSpeed = 2 (medium)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "off"])
        state.lastFanSpeed = 2
        testParent.allRequests.clear()

        when:
        driver.setSpeed("on")

        then:
        lastEventValue("speed") == "medium"
    }

    // -------------------------------------------------------------------------
    // NIT2: the off edge clears the SwitchLevel/FanControl tiles optimistically
    // (level=0, speed='off') so a dimmer/fan tile does not lag one poll behind.
    // NON-VACUITY: removing the off() level:0/speed:"off" emits leaves the tiles
    // at their retained value, RED.
    // -------------------------------------------------------------------------

    def "NIT2: setLevel(0) emits level=0 and speed='off' on the off edge"() {
        given: "device on with a running level"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "level", value: 100])
        testParent.allRequests.clear()

        when:
        driver.setLevel(0)

        then: "off() fired and the dimmer/fan tiles read 0/off immediately"
        testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 0 } != null
        lastEventValue("level") == 0
        lastEventValue("speed") == "off"
    }

    def "NIT2: off() directly emits level=0 and speed='off'"() {
        given: "device on with a running level"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "level", value: 66])

        when:
        driver.off()

        then:
        lastEventValue("switch") == "off"
        lastEventValue("level")  == 0
        lastEventValue("speed")  == "off"
    }

    // -------------------------------------------------------------------------
    // FAILED-WRITE guard (adversarial): the GATED command paths (off -> off(), and
    // low/medium/high + setLevel -> setFanSpeed) must NOT advance speed/level when the
    // cloud write fails (inner code -1 -> httpOk false). DISCRIMINATING: moving a gated
    // emit back outside its if(ok)/httpOk block changes the sentinel, RED. (on/sleep/auto
    // emit unconditionally by an explicit v2.11 deferral — not gated here, so no guard.)
    // -------------------------------------------------------------------------

    def "FAILED-WRITE: setSpeed('off') does not emit speed/level when the power-off write fails"() {
        given: "device on, sentinel speed+level, next write rigged to fail"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "speed",  value: "high"])  // sentinel
        testDevice.events.add([name: "level",  value: 99])      // sentinel
        testParent.cannedResponse = TestParent.innerErrorResponse()
        testParent.allRequests.clear()

        when:
        driver.setSpeed("off")

        then: "off() failed -> off mirrors NOT emitted; sentinels unchanged"
        lastEventValue("speed") == "high"
        lastEventValue("level") == 99
    }

    def "FAILED-WRITE: setLevel(50) does not emit speed/level when the fan write fails"() {
        given: "device on, sentinel speed+level, next write rigged to fail"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "speed",  value: "low"])   // sentinel
        testDevice.events.add([name: "level",  value: 99])      // sentinel
        testParent.cannedResponse = TestParent.innerErrorResponse()
        testParent.allRequests.clear()

        when:
        driver.setLevel(50)

        then: "setFanSpeed's if(ok) gates the emit; no pre-emit of level either -> sentinels unchanged"
        lastEventValue("level") == 99
        lastEventValue("speed") == "low"
    }

    def "FAILED-WRITE: off() does not emit speed='off'/level=0 when the power-off write fails"() {
        // Direct regression proof for off()'s success-gating: the speed:"off"+level:0 mirrors live
        // inside off()'s if(httpOk) success branch. Moving them outside it makes the sentinels
        // change -> RED. (setSpeed('off') delegates here; this asserts the off() method itself.)
        given: "device on, sentinel speed+level, the setSwitch(powerSwitch=0) write rigged to fail"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testDevice.events.add([name: "speed",  value: "high"])  // sentinel
        testDevice.events.add([name: "level",  value: 99])      // sentinel
        testParent.cannedResponse = TestParent.innerErrorResponse()
        testParent.allRequests.clear()

        when:
        driver.off()

        then: "the off-edge mirrors were NOT emitted; sentinels unchanged"
        lastEventValue("speed") == "high"
        lastEventValue("level") == 99
    }
}
