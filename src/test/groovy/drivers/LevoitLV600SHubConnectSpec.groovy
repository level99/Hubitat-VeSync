package drivers

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
        def status = purifierStatusEnvelope(deviceData)

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

    def "applyStatus single-wrapped: dereferences status.result to reach device fields (Bug Pattern #3)"() {
        given:
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def deviceData = fixture.responses.device_on_manual_warm2 as Map
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_auto_humidity as Map)

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

    def "applyStatus workMode='humidity' normalized to user-facing 'auto' (canonical A603S auto mode)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_auto_humidity as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    def "applyStatus workMode='sleep' passes through unchanged"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_sleep_mode as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "sleep"
    }

    def "applyStatus workMode='manual' passes through unchanged"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "manual"
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

    def "setMistLevel clamps values below 1 to 1"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(0)

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
        given:
        settings.descriptionTextEnable = false

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
        given:
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
        def status = purifierStatusEnvelope(fixture.responses.device_warm_off as Map)

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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(deviceData)

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
    // Auto-stop: passive read only (no setter command)
    // -------------------------------------------------------------------------

    def "setAutoStop command does NOT exist on LV600S Hub Connect (AUTO_STOP not in features)"() {
        when:
        def thrown = null
        try {
            driver.setAutoStop("on")
        } catch (MissingMethodException e) {
            thrown = e
        }

        then:
        thrown != null
    }

    def "applyStatus autoStopSwitch=1: autoStopEnabled='on' (passive read)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A603S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
        thrown(Exception)
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
        def status = purifierStatusEnvelope(deviceData)

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
        def status = purifierStatusEnvelope(fixture.responses.device_water_lacks as Map)

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
        def status = purifierStatusEnvelope(fixture.responses.device_water_lacks as Map)
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
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_warm2 as Map)

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
}
