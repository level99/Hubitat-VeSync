package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitClassic300S.groovy (Levoit Classic 300S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from Classic300S.yaml emits expected events
 *   Switch payload  -- on() produces setSwitch with {enabled:true, id:0} (humidifier shape)
 *   Mist level      -- setMistLevel(5) produces setVirtualLevel with {id:0, level:5, type:'mist'}
 *   Mode payload    -- setMode("auto") produces setHumidityMode with {mode:'auto'}
 *   Target humidity -- setHumidity(50) produces setTargetHumidity with {target_humidity:50}
 *   Night-light enum (HA finding #9) -- 3-step mapping: off=0, dim=50, bright=100
 *   Display field aliasing (HA finding #8) -- 'display' key first; fallback to 'indicator_light_switch'
 */
class LevoitClassic300SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitClassic300S.groovy"
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
        given: "a single-wrapped Classic 300S status (device on, manual, mist=5)"
        def fixture = loadYamlFixture("Classic300S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
        def status = purifierStatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("Classic300S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
        def status = purifierStatusEnvelope(deviceData)

        when:
        def result = driver.update(status)

        then:
        result == true
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #3: envelope peel handles both shapes
    // -------------------------------------------------------------------------

    def "applyStatus single-wrapped: dereferences status.result to reach device fields (Bug Pattern #3)"() {
        given: "status map as parent passes it: {code:0, result:{<device fields>}}"
        def fixture = loadYamlFixture("Classic300S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached — switch=on and humidity=42 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 42
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given: "double-wrapped envelope (humidifier shape applied to 300S data)"
        def fixture = loadYamlFixture("Classic300S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
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

        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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

        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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

        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

        when:
        driver.applyStatus(status)
        driver.applyStatus(status)

        then:
        def seedCalls = testDevice.settingsUpdates.findAll { it.name == "descriptionTextEnable" }
        seedCalls.size() == 1
    }

    // -------------------------------------------------------------------------
    // Happy path: full applyStatus from fixture
    // -------------------------------------------------------------------------

    def "applyStatus happy path from device_on_manual_speed5 fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Classic300S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
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

        and: "nightLight is 'dim' (night_light_brightness=50)"
        lastEventValue("nightLight") == "dim"

        and: "nightLightBrightness is 50 (raw value)"
        lastEventValue("nightLightBrightness") == 50

        and: "humidityHigh is 'no'"
        lastEventValue("humidityHigh") == "no"

        and: "autoStopReached is 'no'"
        lastEventValue("autoStopReached") == "no"

        and: "autoStopEnabled is 'off' (configuration.automatic_stop=false)"
        lastEventValue("autoStopEnabled") == "off"

        and: "info HTML is populated"
        lastEventValue("info") != null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus device_off: switch=off, mistLevel=0"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("mistLevel") == 0
    }

    def "applyStatus device_water_lacks: waterLacks='yes'"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Classic300S.yaml")
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
        state.lastWaterLacks = "yes"  // already logged on prior poll

        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_water_lacks as Map)
        int infosBefore = testLog.infos.size()

        when:
        driver.applyStatus(status)

        then:
        def newInfos = testLog.infos.drop(infosBefore)
        !newInfos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    // -------------------------------------------------------------------------
    // Switch payload shape (300S-specific quirk)
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {enabled:true, id:0} — humidifier shape, NOT purifier shape"() {
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

    def "off() sends setSwitch with {enabled:false, id:0} — humidifier shape"() {
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

    def "toggle() reads state.lastSwitchSet when populated (NIT 1 — read-after-write race avoidance)"() {
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
        given: "state.lastSwitchSet is unset; device.currentValue('switch') returns 'off' (default)"
        settings.descriptionTextEnable = false
        // state.lastSwitchSet not seeded; testDevice.currentValue defaults to null/off

        when: "toggle() is called"
        driver.toggle()

        then: "on path was taken (setSwitch with enabled:true) since current is treated as 'off'"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == true
    }

    // -------------------------------------------------------------------------
    // Mist level payload (300S-specific field names)
    // -------------------------------------------------------------------------

    def "setMistLevel(5) sends setVirtualLevel with {id:0, level:5, type:'mist'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(5)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.id == 0
        req.data.level == 5
        req.data.type == "mist"

        and: "Superior 6000S field names are absent (levelIdx, virtualLevel, levelType)"
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
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 1
    }

    def "setMistLevel clamps values above 9 to 9"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(15)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 9
    }

    // -------------------------------------------------------------------------
    // Mode payload (300S-specific: 'mode' not 'workMode')
    // -------------------------------------------------------------------------

    def "setMode('auto') sends setHumidityMode with {mode:'auto'} — NOT workMode"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("auto")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "auto"

        and: "Superior 6000S field name 'workMode' is absent"
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

    def "setMode with invalid value logs error and sends no request"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("turbo")

        then:
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("Invalid mode") || it.contains("turbo") }
    }

    // -------------------------------------------------------------------------
    // Target humidity payload
    // -------------------------------------------------------------------------

    def "setHumidity(50) sends setTargetHumidity with {target_humidity:50}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(50)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        // Note: payload uses snake_case field name (pyvesync fixture canonical)
        req.data.target_humidity == 50

        and: "camelCase field name absent (Superior 6000S style)"
        !req.data.containsKey("targetHumidity")
    }

    def "setHumidity clamps values below 30 to 30"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(10)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 30
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
    // Night-light enum (HA finding #9 — discrete 3-step)
    // -------------------------------------------------------------------------

    def "setNightLight('off') sends setNightLightBrightness with {night_light_brightness:0}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight("off")

        then:
        def req = testParent.allRequests.find { it.method == "setNightLightBrightness" }
        req != null
        req.data.night_light_brightness == 0
    }

    def "setNightLight('dim') sends setNightLightBrightness with {night_light_brightness:50}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight("dim")

        then:
        def req = testParent.allRequests.find { it.method == "setNightLightBrightness" }
        req != null
        req.data.night_light_brightness == 50
    }

    def "setNightLight('bright') sends setNightLightBrightness with {night_light_brightness:100}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight("bright")

        then:
        def req = testParent.allRequests.find { it.method == "setNightLightBrightness" }
        req != null
        req.data.night_light_brightness == 100
    }

    def "setNightLight with invalid value logs error and sends NO request (HA finding #9 safety)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight("medium")

        then: "no request was sent — invalid enum value must not trigger API call"
        testParent.allRequests.isEmpty()

        and: "an error was logged"
        testLog.errors.any { it.contains("medium") || it.contains("Invalid") || it.contains("nightLight") }
    }

    def "applyStatus parses night_light_brightness=50 as 'dim' (HA finding #9)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)
        // device_on_manual_speed5: night_light_brightness=50

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightLight") == "dim"
        lastEventValue("nightLightBrightness") == 50
    }

    def "applyStatus parses night_light_brightness=0 as 'off'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)
        // device_off: night_light_brightness=0

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightLight") == "off"
        lastEventValue("nightLightBrightness") == 0
    }

    def "applyStatus parses night_light_brightness=100 as 'bright'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_legacy_display_alias as Map)
        // device_legacy_display_alias: night_light_brightness=100

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightLight") == "bright"
        lastEventValue("nightLightBrightness") == 100
    }

    def "applyStatus snaps unknown night_light_brightness to 'off' (HA finding #9 safety)"() {
        given:
        settings.debugOutput = true
        def deviceData = [
            enabled: true,
            humidity: 40,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "manual",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            night_light_brightness: 75,  // NOT a valid 3-step value
            configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]
        ]
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "nightLight snapped to 'off' (not 'dim' or 'bright')"
        lastEventValue("nightLight") == "off"

        and: "raw brightness attribute also snapped to 0"
        lastEventValue("nightLightBrightness") == 0
    }

    // -------------------------------------------------------------------------
    // Display field aliasing (HA finding #8)
    // -------------------------------------------------------------------------

    def "applyStatus reads 'display' key when present (HA finding #8 canonical)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        // device_on_manual_speed5 has display:true
        def deviceData = fixture.responses.device_on_manual_speed5 as Map
        assert deviceData.containsKey("display")
        assert !deviceData.containsKey("indicator_light_switch")
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "displayOn is 'on' (display=true)"
        lastEventValue("displayOn") == "on"
    }

    def "applyStatus falls back to 'indicator_light_switch' when 'display' is absent (HA finding #8)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        // device_legacy_display_alias: 'display' key absent, 'indicator_light_switch' present
        def deviceData = fixture.responses.device_legacy_display_alias as Map
        assert !deviceData.containsKey("display") : "fixture must NOT have 'display' key"
        assert deviceData.containsKey("indicator_light_switch")  : "fixture must have 'indicator_light_switch'"
        def status = purifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "displayOn is 'on' (indicator_light_switch=true fallback succeeded)"
        lastEventValue("displayOn") == "on"
    }

    def "applyStatus display=false maps to displayOn='off'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)
        // device_off: display=false

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("displayOn") == "off"
    }

    // -------------------------------------------------------------------------
    // Info HTML (Bug Pattern #7 guard: no device.currentValue() race)
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables, not device.currentValue() (Bug Pattern #7 guard)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true

        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

        when:
        driver.applyStatus(status)

        then:
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Humidity:")
        infoVal.contains("42")     // raw humidity value from fixture
        infoVal.contains("Target:")
        infoVal.contains("50")     // targetHumidity from configuration
        infoVal.contains("Mode:")
        infoVal.contains("manual")
    }

    // -------------------------------------------------------------------------
    // Configuration block parsing
    // -------------------------------------------------------------------------

    def "targetHumidity is read from configuration.auto_target_humidity, not top-level"() {
        given:
        settings.descriptionTextEnable = false
        // Inline fixture: configuration.auto_target_humidity=65, no top-level targetHumidity key
        def deviceData = [
            enabled: true,
            humidity: 40,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "auto",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            night_light_brightness: 0,
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
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = purifierStatusEnvelope(fixture.responses.device_off as Map)
        // device_off: configuration.automatic_stop=true

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("autoStopEnabled") == "on"
    }
}
