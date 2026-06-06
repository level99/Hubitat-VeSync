package drivers

import spock.lang.Unroll
import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitLV600SHubConnect.groovy (Levoit LV600S Hub Connect Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level 0 when device is off (virtualLevel=0 when off)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from LUH-A603S-WUS.yaml canonical fixture emits expected events
 *   Switch payload  -- on() produces setSwitch with {powerSwitch:1, switchIdx:0}
 *                   -- NOT {enabled:true, id:0} (A602S / Classic 300S / Classic 200S difference)
 *   Mode write-path -- setMode("auto") sends {workMode:'humidity'} (A603S canonical; NOT 'auto')
 *                   -- setMode("sleep") sends {workMode:'sleep'}
 *                   -- setMode("manual") sends {workMode:'manual'}
 *                   -- field name is 'workMode' (NOT 'mode' -- VeSyncHumid200300S class difference)
 *   Mode read-path  -- workMode='humidity' normalized to user-facing 'auto'
 *                   -- 'sleep' and 'manual' pass through unchanged
 *   Mist level      -- setMistLevel(5) sends setVirtualLevel {levelIdx:0, virtualLevel:5, levelType:'mist'}
 *                   -- NOT {id:0, level:5, type:'mist'} (A602S / Classic 300S difference)
 *   Warm mist       -- setWarmMistLevel(2) sends setLevel {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:2}
 *                   -- setWarmMistLevel(0): warmMistEnabled='off', warmMistLevel=0
 *                   -- method is 'setLevel' (NOT 'setVirtualLevel' for warm)
 *   Target humidity -- setHumidity(55) sends {targetHumidity:55} camelCase (NOT snake_case target_humidity)
 *                   -- targetHumidity is TOP-LEVEL in response (NOT nested in configuration)
 *   Display command -- setDisplay('on') sends {screenSwitch:1} (NOT {state:bool} or {enabled, id})
 *   Display read    -- from screenState (preferred) or screenSwitch fallback
 *   Water lacks     -- from waterLacksState (int 0|1) -- NOT water_lacks (bool)
 *   Auto-stop       -- passive read from autoStopSwitch + autoStopState; no setter command
 *   No auto-stop cmd -- setAutoStop throws MissingMethodException (not exposed -- feature flag absent)
 *   No night-light  -- driver declares no setNightLight command; no nightLight events emitted
 *   Water lacks     -- state-change gating, INFO logged only on transition
 */
class LevoitLV600SHubConnectSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitLV600SHubConnect.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature (REQUIRED for all children)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a single-wrapped A603S status (device on, manual mode, warm=2)"
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then:
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
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

    def "applyStatus single-wrapped: dereferences status.result to reach device fields (Bug Pattern #3)"() {
        given:
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields reached -- switch=on and humidity=55 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 55
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given:
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 55
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when:
        driver.applyStatus(null)

        then:
        noExceptionThrown()
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #6: mist level 0 when device is off
    // -------------------------------------------------------------------------

    def "applyStatus device_off: switch=off, mistLevel=0 from virtualLevel=0 (Bug Pattern #6)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("mistLevel") == 0
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    def "pref-seed fires only once across multiple applyStatus calls (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null

        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)
        driver.applyStatus(status)

        then:
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_manual_warm2 fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("mode") == "manual"
        lastEventValue("mistLevel") == 5           // virtualLevel=5
        lastEventValue("humidity") == 55
        lastEventValue("targetHumidity") == 60     // top-level targetHumidity (camelCase, no config nested)
        lastEventValue("warmMistLevel") == 2
        lastEventValue("warmMistEnabled") == "on"
        lastEventValue("waterLacks") == "no"       // waterLacksState=0
        lastEventValue("displayOn") == "on"        // screenState=1
        lastEventValue("autoStopEnabled") == "on"  // autoStopSwitch=1
        lastEventValue("autoStopReached") == "no"  // autoStopState=0
        lastEventValue("info") != null
        testLog.errors.isEmpty()
    }

    def "applyStatus device_on_auto_humidity: workMode='humidity' normalized to mode='auto'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_auto_humidity as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("switch") == "on"
        lastEventValue("targetHumidity") == 65
        lastEventValue("autoStopEnabled") == "on"
    }

    // -------------------------------------------------------------------------
    // Switch payload shape (V2-class -- powerSwitch/switchIdx, NOT enabled/id)
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {powerSwitch:1, switchIdx:0} -- NOT humidifier {enabled:true, id:0}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 1
        req.data.switchIdx == 0

        and: "VeSyncHumid200300S-class humidifier field names are absent"
        !req.data.containsKey("enabled")
        !req.data.containsKey("id")
    }

    def "off() sends setSwitch with {powerSwitch:0, switchIdx:0}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.off()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
        req.data.switchIdx == 0
        !req.data.containsKey("enabled")
    }

    def "toggle() reads state.lastSwitchSet to avoid read-after-write race"() {
        given:
        settings.descriptionTextEnable = false
        state.lastSwitchSet = "on"

        when:
        driver.toggle()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.powerSwitch == 0
    }

    // -------------------------------------------------------------------------
    // Mode write-path: workMode field, 'humidity' as auto wire value
    // -------------------------------------------------------------------------

    def "setMode('auto') sends {workMode:'humidity'} -- canonical A603S auto-mode wire value"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("auto")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.workMode == "humidity"

        and: "VeSyncHumid200300S field name 'mode' is absent"
        !req.data.containsKey("mode")

        and: "user-facing mode event is 'auto' (not 'humidity')"
        lastEventValue("mode") == "auto"
    }

    def "setMode('sleep') sends {workMode:'sleep'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("sleep")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.workMode == "sleep"
        lastEventValue("mode") == "sleep"
    }

    def "setMode('manual') sends {workMode:'manual'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("manual")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.workMode == "manual"
    }

    def "setMode('invalid') logs error and sends no request"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.isEmpty()
        !testLog.errors.isEmpty()
    }

    // -------------------------------------------------------------------------
    // Mode read-path: workMode='humidity' -> user-facing 'auto'
    // -------------------------------------------------------------------------

    @Unroll
    def "applyStatus mode read-path: #fixtureKey normalizes to user-facing '#expectedMode'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses[fixtureKey] as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == expectedMode

        where:
        fixtureKey                | expectedMode
        "device_on_auto_humidity" | "auto"     // workMode='humidity' normalized (canonical A603S auto mode)
        "device_on_sleep_mode"    | "sleep"    // 'sleep' passes through unchanged
        "device_on_manual_warm2"  | "manual"   // 'manual' passes through unchanged
    }

    // -------------------------------------------------------------------------
    // Mist level payload (levelIdx/virtualLevel/levelType -- NOT id/level/type)
    // -------------------------------------------------------------------------

    def "setMistLevel(5) sends setVirtualLevel with {levelIdx:0, virtualLevel:5, levelType:'mist'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(5)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.levelIdx == 0
        req.data.virtualLevel == 5
        req.data.levelType == "mist"

        and: "A602S/Classic-class field names are absent"
        !req.data.containsKey("id")
        !req.data.containsKey("level")
        !req.data.containsKey("type")
    }

    def "setMistLevel(1) passes through as the minimum valid level"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(1)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.virtualLevel == 1
    }

    def "setMistLevel clamps values above 9 to 9"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(15)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.virtualLevel == 9
    }

    // -------------------------------------------------------------------------
    // Warm-mist payload (setLevel method -- NOT setVirtualLevel for warm)
    // -------------------------------------------------------------------------

    def "setWarmMistLevel(2) sends setLevel with {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:2}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(2)

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.levelIdx == 0
        req.data.levelType == "warm"
        req.data.mistLevel == 0    // always 0 in warm payload per pyvesync fixture
        req.data.warmLevel == 2

        and: "state reflects on"
        state.warmMistEnabled == "on"
        state.warmMistLevel == 2
    }

    def "setWarmMistLevel(0) sends {warmLevel:0} and emits warmMistEnabled='off'"() {
        given: "device is on (CONCERN 2 early-return skips when off+lvl=0; this tests the on-device warm-off path)"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setWarmMistLevel(0)

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.warmLevel == 0

        and: "level=0 -> off (correct warm-off semantics)"
        state.warmMistEnabled == "off"
        state.warmMistLevel == 0
    }

    def "setWarmMistLevel(3) sends {warmLevel:3} and emits warmMistEnabled='on'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(3)

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.warmLevel == 3
        state.warmMistEnabled == "on"
        state.warmMistLevel == 3
    }

    def "setWarmMistLevel out-of-range (4) logs error and sends no request"() {
        given: "device is on so BP24-B ensureSwitchOn() no-ops; isolates validation scope"
        testDevice.events.add([name: "switch", value: "on"])
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(4)

        then:
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("4") || it.contains("warm") || it.contains("0-3") }
    }

    // -------------------------------------------------------------------------
    // Warm mist applyStatus: warmLevel parsing
    // -------------------------------------------------------------------------

    def "applyStatus warmLevel=0: warmMistEnabled='off', warmMistLevel=0"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_warm_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus warmLevel=2: warmMistEnabled='on'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("warmMistLevel") == 2
        lastEventValue("warmMistEnabled") == "on"
    }

    def "applyStatus warmLevel absent, warmPower=true: warmMistEnabled='on' (fallback path)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1, humidity: 55, targetHumidity: 60,
            virtualLevel: 4, mistLevel: 4, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 1, screenState: 1,
            warmPower: true
            // warmLevel deliberately absent -- exercises fallback to warmPower
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("warmMistEnabled") == "on"
        // warmMistLevel NOT emitted when warmLevel absent
        testDevice.events.find { it.name == "warmMistLevel" } == null
    }

    // -------------------------------------------------------------------------
    // Target humidity: camelCase top-level (NOT snake_case nested in configuration)
    // -------------------------------------------------------------------------

    def "setHumidity(65) sends setTargetHumidity with {targetHumidity:65} camelCase (NOT snake_case)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(65)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 65

        and: "snake_case key absent (Classic 300S / A602S style)"
        !req.data.containsKey("target_humidity")
    }

    def "setHumidity clamps to 30 minimum"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(10)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 30
    }

    def "setHumidity clamps to 80 maximum"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(95)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 80
    }

    def "targetHumidity is read from top-level camelCase field (NOT configuration.auto_target_humidity)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1, humidity: 50, targetHumidity: 70,  // top-level camelCase
            virtualLevel: 3, mistLevel: 3, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 1, screenState: 1,
            warmPower: false, warmLevel: 0
            // no 'configuration' sub-object -- A603S response has none
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "targetHumidity 70 from top-level field (not nested)"
        lastEventValue("targetHumidity") == 70
    }

    // -------------------------------------------------------------------------
    // Display: screenSwitch (NOT state/bool or enabled/id)
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends setDisplay with {screenSwitch:1} -- NOT {state:bool} or {enabled, id}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("on")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1

        and: "other class field names absent"
        !req.data.containsKey("state")
        !req.data.containsKey("enabled")
        !req.data.containsKey("id")
    }

    def "setDisplay('off') sends {screenSwitch:0}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("off")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 0
    }

    // -------------------------------------------------------------------------
    // BP25-truthy: doSet* shared-helper path (setDisplay delegator)
    // LV600SHubConnect.setDisplay delegates to LevoitHumidifierLib
    // doSetDisplayScreenSwitch.  The truthy-canon ternary in the shared helper
    // must emit "on" (not "true" or "1") and send screenSwitch:1.
    // These specs MUST FAIL if value: canon is reverted to value: val in
    // LevoitHumidifierLib doSetDisplayScreenSwitch.
    // -------------------------------------------------------------------------

    @Unroll
    def "BP25-truthy: setDisplay('#truthyInput') sends screenSwitch:1 and emits 'on' (doSet* path)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay(truthyInput)

        then: "API call sent with screenSwitch:1"
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.screenSwitch == 1

        and: "emitted attribute is canonical 'on', not raw '${truthyInput}'"
        lastEventValue("displayOn") == "on"

        where:
        truthyInput << ["true", "1"]
    }

    // -------------------------------------------------------------------------
    // Auto-stop: passive read only (no setter command)
    // -------------------------------------------------------------------------

    def "setAutoStop command does NOT exist on LV600S Hub Connect (AUTO_STOP not in features)"() {
        when:
        driver.setAutoStop("on")

        then:
        thrown(MissingMethodException)
    }

    def "applyStatus autoStopSwitch=1: autoStopEnabled='on' (passive read)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("autoStopEnabled") == "on"   // autoStopSwitch=1
        lastEventValue("autoStopReached") == "no"   // autoStopState=0
    }

    // -------------------------------------------------------------------------
    // No night-light
    // -------------------------------------------------------------------------

    def "setNightLight command does NOT exist on LV600S Hub Connect (hardware absent)"() {
        when:
        driver.setNightLight("off")

        then:
        thrown(MissingMethodException)
    }

    def "applyStatus does NOT emit nightLight events (LV600S Hub Connect has no night-light hardware)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            powerSwitch: 1, humidity: 50, targetHumidity: 60,
            virtualLevel: 3, mistLevel: 3, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 1, screenState: 1,
            warmPower: false, warmLevel: 0,
            night_light_brightness: 50   // present but must be ignored
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        testDevice.events.find { it.name == "nightLight" } == null
        testDevice.events.find { it.name == "nightLightBrightness" } == null
    }

    // -------------------------------------------------------------------------
    // Water lacks: waterLacksState (int) not water_lacks (bool)
    // -------------------------------------------------------------------------

    def "applyStatus waterLacksState=1: waterLacks='yes' and INFO logged"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("waterLacks") == "yes"
        testLog.infos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    def "waterLacks INFO NOT logged on repeat poll when value unchanged (state-change gate)"() {
        given:
        settings.descriptionTextEnable = true
        state.lastWaterLacks = "yes"

        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)
        int infosBefore = testLog.infos.size()

        when:
        driver.applyStatus(status)

        then:
        def newInfos = testLog.infos.drop(infosBefore)
        !newInfos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    // -------------------------------------------------------------------------
    // Info HTML (local variable guard -- BP#7)
    // -------------------------------------------------------------------------

    def "info HTML built from local variables, not device.currentValue (Bug Pattern #7 guard)"() {
        given:
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true

        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Humidity:")
        infoVal.contains("55")
        infoVal.contains("Target:")
        infoVal.contains("60")
        infoVal.contains("Mode:")
        infoVal.contains("manual")
        infoVal.contains("Warm:")
        infoVal.contains("L2")
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
    // BP24-B regression guard: auto-on from off-state (setMistLevel + setWarmMistLevel)
    // NOTE: setMode is SKIP-OK (V2-family; pending live-capture) -- no test here.
    // LV600S Hub Connect on() uses V2-family payload: {powerSwitch:1, switchIdx:0}
    // These tests MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "setMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [
            powerSwitch: 0, humidity: 55, targetHumidity: 60,
            virtualLevel: 0, mistLevel: 0, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 0, screenState: 0,
            warmPower: false, warmLevel: 0
        ]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMistLevel called while device is off"
        driver.setMistLevel(5)

        then: "setSwitch(powerSwitch:1) was sent -- V2-family payload (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null
    }

    def "setWarmMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [
            powerSwitch: 0, humidity: 55, targetHumidity: 60,
            virtualLevel: 0, mistLevel: 0, workMode: "manual",
            waterLacksState: 0, waterTankLifted: 0,
            autoStopSwitch: 1, autoStopState: 0,
            screenSwitch: 0, screenState: 0,
            warmPower: false, warmLevel: 0
        ]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setWarmMistLevel(2) called while device is off"
        driver.setWarmMistLevel(2)

        then: "setSwitch(powerSwitch:1) was sent (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.powerSwitch == 1 }
        onReq != null
    }

    def "BP26: setWarmMistLevel('') does not throw on empty-string input from Rule Machine (LV600S HubConnect)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setWarmMistLevel called with empty string (Rule Machine blank slot)"
        driver.setWarmMistLevel("")
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "BP26: setWarmMistLevel('abc') does not throw on non-numeric input from Rule Machine (LV600S HubConnect)"() {
        given:
        settings.descriptionTextEnable = false
        when: "setWarmMistLevel called with non-numeric string"
        driver.setWarmMistLevel("abc")
        then: "no exception thrown"
        noExceptionThrown()
        and: "no error logged"
        testLog.errors.isEmpty()
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setMistLevel with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setMistLevel('#badInput') does not throw and does not make a setVirtualLevel (mist) API call (LV600S HubConnect)"() {
        // safeIntArg() maps non-numeric inputs to 0; 0 triggers the lvl<=0 guard → off() path.
        // No setVirtualLevel(mist) call is made.
        given: "device is on so the auto-on guard doesn't confuse the assertion"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setVirtualLevel (mist) API call"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" && it.data.levelType == "mist" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setMistLevel('5.7') does not throw and makes a setVirtualLevel API call with truncated value (5) (LV600S HubConnect)"() {
        // safeIntArg("5.7") → 5 (truncation). Math.max(1, Math.min(9, 5)) = 5 → cloud call made.
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setVirtualLevel API call was made with virtualLevel=5 (truncated from 5.7)"
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.levelType == "mist" }
        req != null
        req.data.virtualLevel == 5
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setHumidity with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setHumidity('#badInput') does not throw and does not make a setTargetHumidity API call (LV600S HubConnect)"() {
        // safeIntArg() maps non-numeric inputs to 0; 0 triggers the p<=0 guard → logWarn + return.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setTargetHumidity API call"
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setHumidity('5.7') does not throw and makes a setTargetHumidity API call with clamped value (30) (LV600S HubConnect)"() {
        // safeIntArg("5.7") → 5. 5 > 0 so the rejection guard is skipped;
        // Math.max(30, Math.min(80, 5)) = 30 (clamped to minimum). LV600SHC uses camelCase targetHumidity.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setTargetHumidity API call was made with targetHumidity=30 (5 clamped to minimum)"
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.targetHumidity == 30
    }

    // -------------------------------------------------------------------------
    // BP28 regression guard: non-numeric mist value must NOT turn device off.
    // Non-vacuity: (a) FAILS on pre-fix (safeIntArg("garbage",0)->0->off());
    // PASSES post-fix (parseLevelOrNull->null->ignore). (b) guards explicit-0 contract.
    // -------------------------------------------------------------------------

    def "setMistLevel('garbage') is ignored — no off(), no cloud command (BP28)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when: "setMistLevel called with a non-numeric typo"
        driver.setMistLevel("garbage")

        then: "nothing was sent to the cloud (no off(), no setVirtualLevel)"
        testParent.allRequests.isEmpty()
    }

    def "setMistLevel(0) still calls off() (BP28 explicit-0 contract preserved)"() {
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])
        testParent.allRequests.clear()

        when: "setMistLevel(0) is called"
        driver.setMistLevel(0)

        then: "off() (setSwitch) was sent"
        testParent.allRequests.find { it.method == "setSwitch" } != null

        and: "no setVirtualLevel mist command was sent"
        testParent.allRequests.every { it.method != "setVirtualLevel" }
    }

    // -------------------------------------------------------------------------
    // BP24 SHOULD-ON: setMode from off-state turns the device on (v2.9).
    // NON-VACUITY: deleting the ensureSwitchOn() line in setMode makes the on()
    // assertion go RED (no setSwitch powerSwitch=1 fires; expected revert -> RED).
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

        and: "the mode command (setHumidityMode) was sent with wire workMode='humidity'"
        def modeReq = testParent.allRequests.find { it.method == "setHumidityMode" }
        modeReq != null
        modeReq.data.workMode == "humidity"
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
        testParent.allRequests.find { it.method == "setHumidityMode" } == null
    }
}
