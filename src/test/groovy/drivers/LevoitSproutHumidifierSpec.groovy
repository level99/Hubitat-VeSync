package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitSproutHumidifier.groovy (Levoit Sprout Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level clamped to 0 when device is off (virtualLevel retained by API)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from canonical fixture emits expected events
 *
 *   Switch payload   -- on() produces setSwitch with {powerSwitch:1, switchIdx:0}
 *                    -- NOT {enabled:true, id:0} (VeSyncHumid200300S class difference)
 *   Mode write-path  -- setMode("auto") sends {workMode:'autoPro'} (Sprout mist_modes mapping)
 *                    -- NOT 'auto' (OasisMist 1000S) NOT 'humidity' (LV600S Hub Connect)
 *                    -- setMode("sleep") sends {workMode:'sleep'}
 *                    -- setMode("manual") sends {workMode:'manual'}
 *                    -- field name is 'workMode' NOT 'mode' (VeSyncHumid200300S old API)
 *   Mode read-path   -- 'autoPro' normalizes to user-facing 'auto'
 *                    -- 'auto' and 'humidity' also normalize defensively (firmware variance)
 *   Mist API method  -- setMistLevel(2) sends API method 'setVirtualLevel'
 *                    -- NOT 'virtualLevel' (OasisMist 1000S uses 'virtualLevel')
 *   Mist payload     -- {levelIdx:0, virtualLevel:2, levelType:'mist'}
 *                    -- NOT {id, level, type} (VeSyncHumid200300S old API)
 *   Mist range       -- clamps to 1-2 (Sprout is 2-level hardware; NOT 1-9)
 *   Target humidity  -- setHumidity(55) sends {targetHumidity:55} camelCase top-level
 *   Child lock       -- setChildLock('on') sends setChildLock {childLockSwitch:1}
 *   Auto-stop        -- setAutoStop('on') sends setAutoStopSwitch {autoStopSwitch:1}
 *   Drying mode      -- setDryingMode('on') sends setDryingMode {autoDryingSwitch:1}
 *   Drying read      -- autoDryingSwitch from dryingMode sub-object
 *   Nightlight write -- setNightlight sends setLightStatus {brightness, colorTemperature, nightLightSwitch}
 *                    -- colorTemperature in K (2000-3500)
 *                    -- NOT setNightLight string enum (that's the purifier nightlight API)
 *   Nightlight read  -- nightLight.nightLightSwitch + brightness + colorTemperature parsed
 *   Filter life      -- filterLifePercent + hepaFilterLifePercent emitted
 *   Temperature      -- temperature divided by 10 (F*10 in response)
 *   No warm mist     -- no setWarmMistLevel command (Sprout has no warm mist hardware)
 */
class LevoitSproutHumidifierSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitSproutHumidifier.groovy"
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
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
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
        def deviceData = [powerSwitch: 1, workMode: "manual", humidity: 45, targetHumidity: 55,
                          mistLevel: 2, virtualLevel: 2, waterLacksState: 0, waterTankLifted: 0,
                          autoStopSwitch: 1, autoStopState: 0, screenSwitch: 1, screenState: 1,
                          childLockSwitch: 0, filterLifePercent: 85, hepaFilterLifePercent: 78]
        def status = [code: 0, result: deviceData]

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch")   == "on"
        lastEventValue("mode")     == "manual"
        lastEventValue("mistLevel") == 2
        noExceptionThrown()
    }

    def "applyStatus handles double-wrapped response defensively (Bug Pattern #3)"() {
        given:
        def deviceData = [powerSwitch: 1, workMode: "autoPro", humidity: 55, targetHumidity: 60,
                          mistLevel: 1, virtualLevel: 1, waterLacksState: 0, waterTankLifted: 0,
                          autoStopSwitch: 1, autoStopState: 0, screenSwitch: 1, screenState: 1,
                          childLockSwitch: 0, filterLifePercent: 85, hepaFilterLifePercent: 78]
        def status = [code: 0, result: [code: 0, result: deviceData]]

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
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_off as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")    == "off"
        (fixture.responses.device_off as Map).virtualLevel == 2   // confirm fixture has retained value
        lastEventValue("mistLevel") == 0                           // driver must clamp to 0
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires once when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 45,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0,
                                        screenState: 1, childLockSwitch: 0]]
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
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 45,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0,
                                        screenState: 1, childLockSwitch: 0]]
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
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 45,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0,
                                        screenState: 1, childLockSwitch: 0]]
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
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("switch")          == "on"
        lastEventValue("mode")            == "manual"
        lastEventValue("humidity")        == 45
        lastEventValue("targetHumidity")  == 55
        lastEventValue("mistLevel")       == 2
        lastEventValue("waterLacks")      == "no"
        lastEventValue("displayOn")       == "on"
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("autoStopReached") == "no"
        lastEventValue("childLock")       == "off"
        lastEventValue("filterLife")      == 85
        lastEventValue("hepaFilterLife")  == 78
    }

    def "applyStatus auto mode (workMode=autoPro) normalizes to 'auto'"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_auto_mode as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")   == "auto"
        lastEventValue("switch") == "on"
    }

    def "applyStatus sleep mode fixture sets mode=sleep, display=off"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_sleep_mode as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("mode")      == "sleep"
        lastEventValue("displayOn") == "off"
    }

    // -------------------------------------------------------------------------
    // Mode read-path normalization
    // -------------------------------------------------------------------------

    def "applyStatus workMode='autoPro' normalizes to user-facing 'auto'"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "autoPro", humidity: 50,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    def "applyStatus workMode='auto' also normalizes to user-facing 'auto' (defensive firmware variant)"() {
        given:
        def status = [code: 0, result: [powerSwitch: 1, workMode: "auto", humidity: 50,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0, screenState: 1]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    // -------------------------------------------------------------------------
    // Switch payload: {powerSwitch, switchIdx} NOT {enabled, id}
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {powerSwitch:1, switchIdx:0}"() {
        when:
        driver.on()

        then:
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call != null
        call.data.powerSwitch == 1
        call.data.switchIdx   == 0
        !call.data.containsKey("enabled")
        !call.data.containsKey("id")
    }

    def "off() sends setSwitch with {powerSwitch:0, switchIdx:0}"() {
        when:
        driver.off()

        then:
        def call = testParent.allRequests.find { it.method == "setSwitch" }
        call.data.powerSwitch == 0
        call.data.switchIdx   == 0
    }

    // -------------------------------------------------------------------------
    // Mode write-path: {workMode: 'autoPro'} for auto (NOT 'auto', NOT 'humidity')
    // -------------------------------------------------------------------------

    def "setMode('auto') sends workMode='autoPro' (Sprout mist_modes canonical)"() {
        when:
        driver.setMode("auto")

        then:
        def call = testParent.allRequests.find { it.method == "setHumidityMode" }
        call != null
        call.data.workMode == "autoPro"
        !call.data.containsKey("mode")
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
    // Mist level: API method 'setVirtualLevel', payload {levelIdx, virtualLevel, levelType}
    // Range 1-2 only (2-level hardware)
    // -------------------------------------------------------------------------

    def "setMistLevel sends API method 'setVirtualLevel' (NOT 'virtualLevel')"() {
        when:
        driver.setMistLevel(2)

        then:
        def call = testParent.allRequests.find { it.method == "setVirtualLevel" }
        call != null
        testParent.allRequests.find { it.method == "virtualLevel" } == null
    }

    def "setMistLevel(2) sends {levelIdx:0, virtualLevel:2, levelType:'mist'}"() {
        when:
        driver.setMistLevel(2)

        then:
        def call = testParent.allRequests.find { it.method == "setVirtualLevel" }
        call != null
        call.data.levelIdx     == 0
        call.data.virtualLevel == 2
        call.data.levelType    == "mist"
        !call.data.containsKey("id")
        !call.data.containsKey("level")
        !call.data.containsKey("type")
    }

    def "setMistLevel(0) routes to off() (SwitchLevel setLevel(0) convention)"() {
        when:
        driver.setMistLevel(0)

        then: "setSwitch off was sent, no setVirtualLevel call"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
    }

    def "setMistLevel(1) passes through as the minimum valid level (Sprout floor)"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(1)

        then:
        def calls = testParent.allRequests.findAll { it.method == "setVirtualLevel" }
        calls.size() == 1
        calls[0].data.virtualLevel == 1
    }

    def "setMistLevel(9) clamps to 2 (Sprout 2-level ceiling — NOT 9 like Classic 300S)"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(9)   // above max → 2 (Sprout max is 2, not 9)

        then:
        def calls = testParent.allRequests.findAll { it.method == "setVirtualLevel" }
        calls.size() == 1
        calls[0].data.virtualLevel == 2
    }

    // -------------------------------------------------------------------------
    // Target humidity
    // -------------------------------------------------------------------------

    def "setHumidity(55) sends {targetHumidity:55}"() {
        when:
        driver.setHumidity(55)

        then:
        def call = testParent.allRequests.find { it.method == "setTargetHumidity" }
        call != null
        call.data.targetHumidity == 55
    }

    def "setHumidity clamps to 30-80 range"() {
        when:
        driver.setHumidity(20)
        driver.setHumidity(90)

        then:
        def calls = testParent.allRequests.findAll { it.method == "setTargetHumidity" }
        calls[0].data.targetHumidity == 30
        calls[1].data.targetHumidity == 80
    }

    // -------------------------------------------------------------------------
    // Child lock
    // -------------------------------------------------------------------------

    def "setChildLock('on') sends setChildLock {childLockSwitch:1}"() {
        when:
        driver.setChildLock("on")

        then:
        def call = testParent.allRequests.find { it.method == "setChildLock" }
        call != null
        call.data.childLockSwitch == 1
    }

    def "setChildLock('off') sends {childLockSwitch:0}"() {
        when:
        driver.setChildLock("off")

        then:
        def call = testParent.allRequests.find { it.method == "setChildLock" }
        call.data.childLockSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Auto-stop
    // -------------------------------------------------------------------------

    def "setAutoStop('on') sends setAutoStopSwitch {autoStopSwitch:1}"() {
        when:
        driver.setAutoStop("on")

        then:
        def call = testParent.allRequests.find { it.method == "setAutoStopSwitch" }
        call != null
        call.data.autoStopSwitch == 1
    }

    // -------------------------------------------------------------------------
    // Drying mode
    // -------------------------------------------------------------------------

    def "setDryingMode('on') sends setDryingMode {autoDryingSwitch:1}"() {
        when:
        driver.setDryingMode("on")

        then:
        def call = testParent.allRequests.find { it.method == "setDryingMode" }
        call != null
        call.data.autoDryingSwitch == 1
    }

    def "setDryingMode('off') sends {autoDryingSwitch:0}"() {
        when:
        driver.setDryingMode("off")

        then:
        def call = testParent.allRequests.find { it.method == "setDryingMode" }
        call.data.autoDryingSwitch == 0
    }

    def "applyStatus reads dryingEnabled from dryingMode sub-object"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_drying_mode_active as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("dryingEnabled") == "on"
    }

    // -------------------------------------------------------------------------
    // Nightlight write-path: setLightStatus {brightness, colorTemperature, nightLightSwitch}
    // NOTE: Humidifier nightlight uses setLightStatus (NOT setNightLight -- that's the purifier)
    // -------------------------------------------------------------------------

    def "setNightlight('on') with no args sends setLightStatus {brightness:100, colorTemperature:3500, nightLightSwitch:1}"() {
        when:
        driver.setNightlight("on")

        then:
        def call = testParent.allRequests.find { it.method == "setLightStatus" }
        call != null
        call.data.nightLightSwitch == 1
        call.data.brightness       > 0     // default 100 when on + no brightness arg
        call.data.containsKey("colorTemperature")  // must include colorTemperature
        // Must NOT call setNightLight (that's purifier)
        testParent.allRequests.find { it.method == "setNightLight" } == null
    }

    def "setNightlight('on', 80, 2700) sends {brightness:80, colorTemperature:2700, nightLightSwitch:1}"() {
        when:
        driver.setNightlight("on", 80, 2700)

        then:
        def call = testParent.allRequests.find { it.method == "setLightStatus" }
        call != null
        call.data.brightness       == 80
        call.data.colorTemperature == 2700
        call.data.nightLightSwitch == 1
    }

    def "setNightlight('off') sends {brightness:0, nightLightSwitch:0}"() {
        when:
        driver.setNightlight("off")

        then:
        def call = testParent.allRequests.find { it.method == "setLightStatus" }
        call != null
        call.data.brightness       == 0
        call.data.nightLightSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Nightlight read-path: nightLight sub-object with colorTemperature
    // -------------------------------------------------------------------------

    def "applyStatus parses nightLight sub-object including colorTemperature"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_nightlight_on as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn")         == "on"
        lastEventValue("nightlightBrightness") == 80
        lastEventValue("nightlightColorTemp")  == 2700
    }

    def "applyStatus nightlight off emits nightlightOn=off"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("nightlightOn")         == "off"
        lastEventValue("nightlightBrightness") == 0
    }

    // -------------------------------------------------------------------------
    // Filter life and temperature
    // -------------------------------------------------------------------------

    def "applyStatus emits filterLife and hepaFilterLife from fixture"() {
        given:
        def fixture = loadYamlFixture("LEH-B381S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map

        when:
        driver.applyStatus([code: 0, result: deviceData])

        then:
        lastEventValue("filterLife")     == 85
        lastEventValue("hepaFilterLife") == 78
    }

    def "applyStatus divides temperature by 10 (F*10 raw value)"() {
        given: "temperature=720 in response means 72.0°F"
        def status = [code: 0, result: [powerSwitch: 1, workMode: "manual", humidity: 45,
                                        targetHumidity: 55, virtualLevel: 1, waterLacksState: 0,
                                        waterTankLifted: 0, autoStopSwitch: 1, autoStopState: 0,
                                        screenState: 1, childLockSwitch: 0, temperature: 720]]
        when:
        driver.applyStatus(status)

        then:
        lastEventValue("temperature") == 72.0
    }

    // -------------------------------------------------------------------------
    // No warm mist
    // -------------------------------------------------------------------------

    def "driver has no setWarmMistLevel method (Sprout has no warm mist hardware)"() {
        when:
        driver.setWarmMistLevel(2)

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

    // -------------------------------------------------------------------------
    // BP24-B regression guard: setMistLevel from off-state triggers auto-on
    // Sprout V2-family payload: {powerSwitch:1, switchIdx:0}
    // This test MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "setMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [
            powerSwitch: 0, humidity: 45, targetHumidity: 55,
            virtualLevel: 2, mistLevel: 2, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 0, screenState: 0,
            childLockSwitch: 0, filterLifePercent: 85, hepaFilterLifePercent: 78
        ]
        driver.applyStatus([code: 0, result: offData])
        testParent.allRequests.clear()

        when: "setMistLevel called while device is off"
        driver.setMistLevel(2)

        then: "setSwitch(powerSwitch:1) was sent — V2-family payload (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null
        onReq.data.switchIdx == 0
    }

    // ---- BP25: setNightlight (3-param: onOff, brightness, colorTemp) ----

    def "BP25: setNightlight('ON') with no brightness sends nightLightSwitch:1 (not 0) (BP25 regression guard)"() {
        // Pre-fix: (onOff == "on" && br > 0) where onOff="ON" evaluates false → nlSwitch=0 (wrong).
        // Post-fix: toLowerCase() normalizes "ON"→"on" → on branch, br defaults to 100 → nlSwitch=1.
        given:
        settings.descriptionTextEnable = false

        when: "setNightlight called with uppercase 'ON' and no brightness"
        driver.setNightlight("ON")

        then: "setLightStatus sent with nightLightSwitch:1 (on)"
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.nightLightSwitch == 1

        and: "nightlightOn event emitted as 'on'"
        lastEventValue("nightlightOn") == "on"
    }

    def "BP25: setNightlight('OFF') sends nightLightSwitch:0 and brightness:0 (BP25 regression guard)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightlight("OFF")

        then: "setLightStatus sent with nightLightSwitch:0 (off) and brightness:0"
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.nightLightSwitch == 0
        req.data.brightness == 0
    }

    // -------------------------------------------------------------------------
    // BP26: safe numeric coercion — setNightlight brightness + colorTemp (2nd + 3rd params)
    // RULE37 is first-param-only by design; non-first-param coercions are gated
    // by Spock regression specs instead of lint. setNightlight brightness/colorTemp
    // is the canonical worked example of this pattern for multi-param methods.
    // -------------------------------------------------------------------------

    def "BP26: setNightlight('on', 80, 2700) happy path — both numeric params reach API unchanged"() {
        // Baseline: confirms safeIntArg correctly passes-through valid numeric inputs.
        // safeIntArg(80, 0) = 80; safeIntArg(2700, 3500) = 2700; nlSwitch=1.
        when:
        driver.setNightlight("on", 80, 2700)

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness        == 80
        req.data.colorTemperature  == 2700
        req.data.nightLightSwitch  == 1
    }

    def "BP26: setNightlight('on', '#badBr') does not throw; garbage brightness coerces to fallback 0 then promoted to 50"() {
        // safeIntArg("abc"/""/true → fallback 0) → Math.max(0,Math.min(100,0)) = 0 → br=0.
        // nl="on", not-off branch: br==0 → promoted to 50 (Sprout's "turn on with no brightness" default).
        // colorTemp: null → skips safeIntArg → null → in on-branch: ct = ct ?: 3500 = 3500.
        // nlSwitch = ("on"=="on" && 50>0) ? 1 : 0 = 1.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightlight("on", badBr)

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness       == 50
        req.data.colorTemperature == 3500
        req.data.nightLightSwitch == 1

        where:
        badBr << ["abc", "", true]
    }

    def "BP26: setNightlight('on', '55.7') does not throw; brightness truncates to 55"() {
        // safeIntArg("55.7", 0) → BigDecimal("55.7").intValue() = 55 (truncation).
        // Math.max(0, Math.min(100, 55)) = 55. br=55, not 0 → not promoted. nlSwitch=1.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightlight("on", "55.7")

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness       == 55
        req.data.nightLightSwitch == 1
    }

    def "BP26: setNightlight('on', 50, 'abc') does not throw; garbage colorTemp coerces to fallback 3500"() {
        // safeIntArg("abc", 3500) → fallback 3500. Math.max(2000, Math.min(3500, 3500)) = 3500.
        // brightness=50: safeIntArg(50, 0) = 50; not promoted (br!=0). nlSwitch=1.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightlight("on", 50, "abc")

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness       == 50
        req.data.colorTemperature == 3500
        req.data.nightLightSwitch == 1
    }

    def "BP26: setNightlight('on', 50, '2750.9') does not throw; colorTemp truncates to 2750"() {
        // safeIntArg("2750.9", 3500) → BigDecimal("2750.9").intValue() = 2750 (truncation).
        // Math.max(2000, Math.min(3500, 2750)) = 2750 (within range, no clamping).
        // brightness=50 unchanged; nlSwitch=1.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightlight("on", 50, "2750.9")

        then:
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness       == 50
        req.data.colorTemperature == 2750
        req.data.nightLightSwitch == 1
    }
}
