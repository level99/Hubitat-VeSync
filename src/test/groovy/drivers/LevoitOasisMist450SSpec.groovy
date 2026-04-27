package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitOasisMist450S.groovy (Levoit OasisMist 450S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap, double-wrap, and null
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from device_on_warm_mist_level2 fixture emits
 *                      expected events for all attributes
 *   Switch payload  -- on() produces setSwitch with {enabled:true, id:0} (humidifier shape)
 *   Mist level      -- setMistLevel(5) produces setVirtualLevel with {id:0, level:5, type:'mist'}
 *   Warm-mist level -- setWarmMistLevel(2) produces setVirtualLevel with {id:0, level:2, type:'warm'}
 *                      level=0 produces type:'warm' with level:0 (warm mist off)
 *   HA finding #10  -- level=0 => warmMistEnabled='off' (guards against pyvesync 200300S bug
 *                      that sets warm_mist_enabled=True unconditionally after set_warm_level)
 *   Mode enum       -- exactly 3 valid modes (auto/sleep/manual); 'humidity' is REJECTED
 *                      (pyvesync issue #295: device returns 11000000 "Mode value invalid!");
 *                      HA bonus finding #a was refuted and is NOT tested here
 *   No nightlight   -- setNightLight is not a callable command; applyStatus ignores any
 *                      night_light_brightness field in the response
 *   Target humidity -- setHumidity(50) produces setTargetHumidity with {target_humidity:50};
 *                      clamp floor is 40 (NOT 30) -- pyvesync issue #296, firmware rejects
 *                      values below 40 with API error 11003000
 *   Display         -- setDisplay produces setDisplay with {state: bool}
 *   Auto-stop       -- setAutoStop produces setAutomaticStop with {enabled: bool}
 *   Toggle pattern  -- state.lastSwitchSet populated -> toggle reads state; unset -> currentValue
 */
class LevoitOasisMist450SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitOasisMist450S.groovy"
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
        given: "a single-wrapped 450S status (device on, manual, mist=5, warm=2)"
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = purifierStatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = purifierStatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    def "update() no-arg self-fetch signature is callable"() {
        when: "update() is called (self-fetch -- parent will make API call)"
        driver.update()

        then: "no exception thrown"
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: envelope peel handles both shapes
    // -------------------------------------------------------------------------

    def "applyStatus single-wrapped: dereferences status.result to reach device fields (Bug Pattern #3)"() {
        given: "status map as parent passes it: {code:0, result:{<device fields>}}"
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached -- switch=on and humidity=42 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 42
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given: "double-wrapped envelope (humidifier shape applied to 450S data)"
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then: "device fields ARE correctly reached via peel loop"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 42
    }

    def "applyStatus handles null status gracefully without throwing"() {
        when:
        driver.applyStatus(null)

        then:
        noExceptionThrown()
        // switch defaults to off (enabled=null -> false path)
        lastEventValue("switch") == "off"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires when descriptionTextEnable is null (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

        when:
        driver.applyStatus(status)
        driver.applyStatus(status)

        then:
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from canonical fixture
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_warm_mist_level2 fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "mist level is 5"
        lastEventValue("mistLevel") == 5

        and: "humidity is 42"
        lastEventValue("humidity") == 42

        and: "targetHumidity is 50 (from configuration.auto_target_humidity)"
        lastEventValue("targetHumidity") == 50

        and: "waterLacks is 'no'"
        lastEventValue("waterLacks") == "no"

        and: "displayOn is 'on' (display=true)"
        lastEventValue("displayOn") == "on"

        and: "warmMistLevel is 2"
        lastEventValue("warmMistLevel") == 2

        and: "warmMistEnabled is 'on' (warm_level=2 > 0)"
        lastEventValue("warmMistEnabled") == "on"

        and: "humidityHigh is 'no'"
        lastEventValue("humidityHigh") == "no"

        and: "autoStopReached is 'no'"
        lastEventValue("autoStopReached") == "no"

        and: "autoStopEnabled is 'off' (configuration.automatic_stop=false)"
        lastEventValue("autoStopEnabled") == "off"

        and: "info HTML is populated"
        lastEventValue("info") != null

        and: "no nightlight events emitted (450S has no nightlight)"
        !testDevice.events.any { it.name in ["nightLight", "nightLightBrightness"] }

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus device_off: switch=off, mistLevel=0, warmMistEnabled=off, warmMistLevel=0"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("mistLevel") == 0
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus emits mode='humidity' as-received if API response contains it (passthrough, not a valid setMode value)"() {
        // The driver cannot set humidity mode (device rejects it -- pyvesync issue #295),
        // but if a device somehow ends up reporting mode='humidity' in its status response
        // (e.g. state set externally or via the app), applyStatus should emit the literal
        // value rather than mangling it. Reality wins over our mode allow-list on reads.
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_humidity_mode as Map)

        when:
        driver.applyStatus(status)

        then: "mode attribute reflects what the device reported, not what we can set"
        lastEventValue("switch") == "on"
        lastEventValue("mode") == "humidity"
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus device_warm_off: warm_level=0 -> warmMistEnabled='off' (level-derived, not field-derived)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        // device_warm_off has warm_enabled=false, warm_level=0 (consistent, correct case)
        def status = purifierStatusEnvelope(fixture.responses.device_warm_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus: warm_level=0 but warm_enabled=true (pyvesync-bug API state) -> warmMistEnabled='off'"() {
        given: "synthetic response simulating the pyvesync VeSyncHumid200300S bug aftermath"
        settings.descriptionTextEnable = false
        // pyvesync's set_warm_level(0) leaves warm_mist_enabled=True in its state model (bug).
        // On the next poll, the API might return warm_enabled=true with warm_level=0.
        // Our driver must derive enabled state from warm_level, NOT from warm_enabled.
        def deviceData = [
            enabled: true,
            humidity: 45,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "manual",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            warm_enabled: true,     // buggy pyvesync state
            warm_level: 0,          // actual level is 0 = off
            display: true,
            configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "warmMistEnabled is 'off' because warm_level=0 (level-derived semantics)"
        lastEventValue("warmMistEnabled") == "off"

        and: "warmMistLevel is 0"
        lastEventValue("warmMistLevel") == 0
    }

    def "applyStatus device_water_lacks: waterLacks='yes'"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_water_lacks as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("waterLacks") == "yes"
        testLog.infos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    def "waterLacks INFO NOT logged on repeat poll when value unchanged (state-change gating)"() {
        given:
        settings.descriptionTextEnable = true
        state.lastWaterLacks = "yes"

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_water_lacks as Map)
        int infosBefore = testLog.infos.size()

        when:
        driver.applyStatus(status)

        then:
        def newInfos = testLog.infos.drop(infosBefore)
        !newInfos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    // -------------------------------------------------------------------------
    // Switch payload shape
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {enabled:true, id:0} -- humidifier shape, NOT purifier shape"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then: "setSwitch was called"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null

        and: "payload uses humidifier field names"
        req.data.enabled == true
        req.data.id == 0

        and: "purifier field names are absent"
        !req.data.containsKey("powerSwitch")
        !req.data.containsKey("switchIdx")
    }

    def "off() sends setSwitch with {enabled:false, id:0} -- humidifier shape"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.off()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == false
        req.data.id == 0
        !req.data.containsKey("powerSwitch")
    }

    // -------------------------------------------------------------------------
    // Toggle pattern (NIT 1 -- read-after-write race avoidance)
    // -------------------------------------------------------------------------

    def "toggle() reads state.lastSwitchSet when populated (state takes priority over currentValue)"() {
        given: "state.lastSwitchSet is 'on' (would have been set by a prior on() call)"
        settings.descriptionTextEnable = false
        state.lastSwitchSet = "on"

        when: "toggle() is called"
        driver.toggle()

        then: "off path was taken (setSwitch with enabled:false)"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == false
    }

    def "toggle() falls back to device.currentValue when state.lastSwitchSet is unset (first-call case)"() {
        given: "state.lastSwitchSet is unset; device.currentValue('switch') returns null/off (default)"
        settings.descriptionTextEnable = false
        // state.lastSwitchSet not seeded; testDevice.currentValue defaults to null

        when: "toggle() is called"
        driver.toggle()

        then: "on path was taken (setSwitch with enabled:true) since current is treated as 'off'"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == true
    }

    def "on() sets state.lastSwitchSet='on' after successful call"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then:
        state.lastSwitchSet == "on"
    }

    def "off() sets state.lastSwitchSet='off' after successful call"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.off()

        then:
        state.lastSwitchSet == "off"
    }

    // -------------------------------------------------------------------------
    // Mist level payload (300S-inherited field names)
    // -------------------------------------------------------------------------

    def "setMistLevel(5) sends setVirtualLevel with {id:0, level:5, type:'mist'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(5)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "mist" }
        req != null
        req.data.id == 0
        req.data.level == 5
        req.data.type == "mist"

        and: "Superior 6000S field names absent (levelIdx, virtualLevel, levelType)"
        !req.data.containsKey("levelIdx")
        !req.data.containsKey("virtualLevel")
        !req.data.containsKey("levelType")
    }

    def "setMistLevel clamps values below 1 to 1"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(0)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "mist" }
        req != null
        req.data.level == 1
    }

    def "setMistLevel clamps values above 9 to 9"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(15)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "mist" }
        req != null
        req.data.level == 9
    }

    // -------------------------------------------------------------------------
    // Warm-mist level payload (450S-specific)
    // -------------------------------------------------------------------------

    def "setWarmMistLevel(2) sends setVirtualLevel with {id:0, level:2, type:'warm'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(2)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        req != null
        req.data.id == 0
        req.data.level == 2
        req.data.type == "warm"

        and: "type discriminator is 'warm', NOT 'mist'"
        req.data.type != "mist"
    }

    def "setWarmMistLevel(0) sends setVirtualLevel with {id:0, level:0, type:'warm'} (warm mist off)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(0)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        req != null
        req.data.level == 0
        req.data.type == "warm"
    }

    def "setWarmMistLevel(3) is accepted as maximum level"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(3)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        req != null
        req.data.level == 3
    }

    def "setWarmMistLevel(4) is rejected with logError and NO API call"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(4)

        then: "no request was sent"
        testParent.allRequests.isEmpty()

        and: "error was logged mentioning level constraint"
        testLog.errors.any { it.contains("4") || it.contains("0-3") || it.contains("warm") }
    }

    def "warm type discriminator in setWarmMistLevel payload is 'warm' NOT 'mist' (cross-check)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(1)

        then:
        def warmReq = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        def mistReq = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "mist" }
        warmReq != null
        mistReq == null
    }

    // -------------------------------------------------------------------------
    // HA finding #10 -- level=0 means OFF (anti-pyvesync-bug assertions)
    // pyvesync VeSyncHumid200300S.set_warm_level() unconditionally sets
    // warm_mist_enabled=True after the call, even for level=0.
    // LV600S class has the correct logic: warm_mist_enabled = (warm_level > 0).
    // Our driver mirrors LV600S. These tests explicitly guard that correct behavior.
    // -------------------------------------------------------------------------

    def "setWarmMistLevel(0) -> warmMistEnabled becomes 'off' (HA finding #10 -- NOT 'on' per pyvesync bug)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(0)

        then:
        def warmOnStr = lastEventValue("warmMistEnabled")
        warmOnStr == "off"

        // Confirm it is explicitly NOT "on" -- this is the pyvesync-bug regression guard
        warmOnStr != "on"
    }

    def "setWarmMistLevel(2) -> warmMistEnabled becomes 'on' (level > 0 means enabled)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(2)

        then:
        lastEventValue("warmMistEnabled") == "on"
    }

    def "setWarmMistLevel(0) -> state.warmMistEnabled is 'off' (internal state also correct)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(0)

        then:
        state.warmMistEnabled == "off"
    }

    def "setWarmMistLevel(1) -> state.warmMistEnabled is 'on'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(1)

        then:
        state.warmMistEnabled == "on"
    }

    // -------------------------------------------------------------------------
    // Mode enum: exactly 3 valid modes (auto/sleep/manual)
    // 'humidity' mode is NOT a valid setMode input for OasisMist 450S US.
    // Per pyvesync issue #295: device firmware returns API error 11000000
    // ("Mode value invalid!") when sent setHumidityMode{mode:'humidity'}.
    // The earlier HA bonus finding #a claiming firmware-variant-dependent
    // acceptance was refuted by user-reported API captures.
    // -------------------------------------------------------------------------

    def "setMode('auto') sends setHumidityMode with {mode:'auto'} -- NOT workMode"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("auto")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "auto"
        !req.data.containsKey("workMode")
    }

    def "setMode('sleep') sends setHumidityMode with {mode:'sleep'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("sleep")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "sleep"
    }

    def "setMode('manual') sends setHumidityMode with {mode:'manual'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("manual")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "manual"
    }

    def "setMode('humidity') is REJECTED -- device returns error 11000000 on this value (pyvesync issue #295)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("humidity")

        then: "no request sent -- humidity is an invalid mode on OasisMist 450S US firmware"
        testParent.allRequests.isEmpty()

        and: "error was logged"
        testLog.errors.any { it.contains("humidity") || it.contains("Invalid mode") }
    }

    def "setMode with unknown value (turbo) logs error and sends no request"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("Invalid mode") || it.contains("turbo") }
    }

    // -------------------------------------------------------------------------
    // No nightlight: explicit absence assertions
    // -------------------------------------------------------------------------

    def "applyStatus does NOT emit nightLight or nightLightBrightness events (450S has no nightlight)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

        when:
        driver.applyStatus(status)

        then:
        !testDevice.events.any { it.name == "nightLight" }
        !testDevice.events.any { it.name == "nightLightBrightness" }
    }

    def "applyStatus ignores night_light_brightness field even if somehow present in response"() {
        given:
        settings.descriptionTextEnable = false
        // Inject nightlight field that some firmware might erroneously return
        def deviceData = [
            enabled: true,
            humidity: 45,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "manual",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            warm_enabled: true,
            warm_level: 2,
            display: true,
            night_light_brightness: 50,  // should be silently ignored
            configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no nightlight events emitted despite field presence"
        !testDevice.events.any { it.name == "nightLight" }
        !testDevice.events.any { it.name == "nightLightBrightness" }

        and: "normal attributes still populated"
        lastEventValue("switch") == "on"
        lastEventValue("warmMistLevel") == 2
    }

    // -------------------------------------------------------------------------
    // Target humidity payload
    // Range: 40-80% (NOT 30-80%).
    // Device firmware rejects values below 40 with API error 11003000.
    // pyvesync's base class inherits (30, 80) which is incorrect for this model.
    // Confirmed: pyvesync issue #296; homebridge-levoit-humidifiers minHumidityLevel: 40.
    // -------------------------------------------------------------------------

    def "setHumidity(50) sends setTargetHumidity with {target_humidity:50} -- snake_case"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(50)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 50
        !req.data.containsKey("targetHumidity")
    }

    def "setHumidity clamps values below 40 to 40 (firmware floor -- pyvesync issue #296)"() {
        given:
        settings.descriptionTextEnable = false

        when: "value below firmware floor is requested"
        driver.setHumidity(35)

        then: "clamped to 40, NOT 30 (pyvesync base class bug -- OasisMist 450S rejects <40)"
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 40
        req.data.target_humidity != 35
        req.data.target_humidity != 30
    }

    def "setHumidity(25) also clamps to 40 -- well below firmware floor"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(25)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 40
    }

    def "setHumidity clamps values above 80 to 80"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(95)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 80
    }

    // -------------------------------------------------------------------------
    // Display payload
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends setDisplay with {state:true} -- NOT screenSwitch"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("on")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.state == true
        !req.data.containsKey("screenSwitch")
    }

    def "setDisplay('off') sends setDisplay with {state:false}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("off")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.state == false
    }

    // -------------------------------------------------------------------------
    // Auto-stop payload
    // -------------------------------------------------------------------------

    def "setAutoStop('on') sends setAutomaticStop with {enabled:true} -- NOT autoStopSwitch"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoStop("on")

        then:
        def req = testParent.allRequests.find { it.method == "setAutomaticStop" }
        req != null
        req.data.enabled == true
        !req.data.containsKey("autoStopSwitch")
    }

    def "setAutoStop('off') sends setAutomaticStop with {enabled:false}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setAutoStop("off")

        then:
        def req = testParent.allRequests.find { it.method == "setAutomaticStop" }
        req != null
        req.data.enabled == false
    }

    // -------------------------------------------------------------------------
    // Info HTML (Bug Pattern #7 guard)
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables, not device.currentValue() (Bug Pattern #7 guard)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

        when:
        driver.applyStatus(status)

        then:
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Humidity:")
        infoVal.contains("42")
        infoVal.contains("Target:")
        infoVal.contains("50")
        infoVal.contains("Mode:")
        infoVal.contains("manual")
        infoVal.contains("Warm:")
        infoVal.contains("L2")
    }

    def "info HTML includes warm=off when warm_level=0"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_warm_off as Map)

        when:
        driver.applyStatus(status)

        then:
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Warm:")
        infoVal.contains("off")
    }

    // -------------------------------------------------------------------------
    // Configuration block parsing
    // -------------------------------------------------------------------------

    def "targetHumidity is read from configuration.auto_target_humidity, not top-level"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true,
            humidity: 40,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "auto",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            warm_enabled: true,
            warm_level: 1,
            display: true,
            configuration: [
                auto_target_humidity: 65,
                display: true,
                automatic_stop: true
            ]
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "targetHumidity is 65 (from configuration.auto_target_humidity)"
        lastEventValue("targetHumidity") == 65

        and: "autoStopEnabled is 'on' (configuration.automatic_stop=true)"
        lastEventValue("autoStopEnabled") == "on"
    }

    def "autoStopEnabled='on' when configuration.automatic_stop is true"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)
        // device_off: configuration.automatic_stop=true

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("autoStopEnabled") == "on"
    }
}
