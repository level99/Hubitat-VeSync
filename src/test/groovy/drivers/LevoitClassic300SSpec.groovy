package drivers

import support.HubitatSpec
import support.TestParent
import spock.lang.Unroll

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)
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

    def "off() re-entrance guard: second call while turningOff=true is a no-op"() {
        // Regression guard: HumidifierLib off() symmetric re-entrance guard (state.turningOff).
        // Defensive symmetry with on(); a re-entrant off() must short-circuit.
        given:
        settings.descriptionTextEnable = false
        state.turningOff = true

        when:
        driver.off()

        then: "no setSwitch API call because re-entrance was blocked"
        testParent.allRequests.findAll { it.method == "setSwitch" }.isEmpty()
        noExceptionThrown()
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

    def "setMistLevel(1) passes through as the minimum valid level"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(1)

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

    @Unroll
    def "setMode('#mode') sends setHumidityMode with {mode:'#mode'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode(mode)

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == mode

        where:
        mode << ["sleep", "manual"]
    }

    def "setMode with invalid value logs error and sends no request"() {
        given: "device is on so BP24-B ensureSwitchOn() no-ops; isolates validation scope"
        testDevice.events.add([name: "switch", value: "on"])
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

    @Unroll
    def "setHumidity clamps #input to #expected"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(input)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == expected

        where:
        input | expected
        10    | 30
        95    | 80
    }

    // -------------------------------------------------------------------------
    // Night-light enum (HA finding #9 — discrete 3-step)
    // -------------------------------------------------------------------------

    @Unroll
    def "setNightLight('#nl') sends setNightLightBrightness with {night_light_brightness:#brightness}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight(nl)

        then:
        def req = testParent.allRequests.find { it.method == "setNightLightBrightness" }
        req != null
        req.data.night_light_brightness == brightness

        where:
        nl       | brightness
        "off"    | 0
        "dim"    | 50
        "bright" | 100
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

    @Unroll
    def "applyStatus parses night_light_brightness=#brightness as '#expectedNl' (HA finding #9)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = v2StatusEnvelope(fixture.responses[fixtureKey] as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightLight") == expectedNl
        lastEventValue("nightLightBrightness") == brightness

        where:
        fixtureKey                    | brightness | expectedNl
        "device_on_manual_speed5"     | 50         | "dim"
        "device_off"                  | 0          | "off"
        "device_legacy_display_alias" | 100        | "bright"
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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "displayOn is 'on' (indicator_light_switch=true fallback succeeded)"
        lastEventValue("displayOn") == "on"
    }

    def "applyStatus display=false maps to displayOn='off'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic300S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)
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
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_speed5 as Map)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)
        // device_off: configuration.automatic_stop=true

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("autoStopEnabled") == "on"
    }

    // ---- BP18: null-arg guard ----

    @Unroll
    def "#cmd(null) does not throw and emits a WARN log (BP18)"() {
        when:
        driver."$cmd"(null)
        then:
        noExceptionThrown()
        testLog.warns.any { it.contains(cmd) && it.contains("null") }
        testParent.allRequests.isEmpty()

        where:
        cmd << ["setMode", "setNightLight"]
    }

    // -------------------------------------------------------------------------
    // BP24-B regression guard: auto-on from off-state (setMistLevel + setMode)
    // These specs MUST FAIL on pre-fix code (no ensureSwitchOn call) and
    // PASS on post-fix code (ensureSwitchOn added as first line).
    // -------------------------------------------------------------------------

    def "setMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [enabled: false, humidity: 42, mist_virtual_level: 0, mist_level: 0,
                       mode: "manual", water_lacks: false, humidity_high: false,
                       display: false, automatic_stop_reach_target: false,
                       night_light_brightness: 0,
                       configuration: [auto_target_humidity: 50, display: false, automatic_stop: false]]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMistLevel called while device is off"
        driver.setMistLevel(5)

        then: "setSwitch(enabled:true) was sent (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null
    }

    def "setMode('auto') from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [enabled: false, humidity: 42, mist_virtual_level: 0, mist_level: 0,
                       mode: "manual", water_lacks: false, humidity_high: false,
                       display: false, automatic_stop_reach_target: false,
                       night_light_brightness: 0,
                       configuration: [auto_target_humidity: 50, display: false, automatic_stop: false]]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMode called while device is off"
        driver.setMode("auto")

        then: "setSwitch(enabled:true) was sent (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null
    }

    // -------------------------------------------------------------------------
    // CONCERN 1 regression guard: invalid mode on off-state device must NOT auto-on
    // setMode("turbo") is invalid → should reject BEFORE ensureSwitchOn() fires.
    // Without the validate-before-ensureSwitchOn ordering fix, the device would be
    // turned on before the invalid-mode error is returned.
    // This test MUST FAIL on pre-fix code and PASS on post-fix code.
    // -------------------------------------------------------------------------

    def "setMode invalid value on off-state device does NOT auto-on (CONCERN 1 validate-before-ensureSwitchOn)"() {
        given: "device is off"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [enabled: false, humidity: 42, mist_virtual_level: 0, mist_level: 0,
                       mode: "manual", water_lacks: false, humidity_high: false,
                       display: false, automatic_stop_reach_target: false,
                       night_light_brightness: 0,
                       configuration: [auto_target_humidity: 50, display: false, automatic_stop: false]]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMode called with an invalid value while device is off"
        driver.setMode("turbo")

        then: "NO setSwitch request was sent — device must not auto-on for an invalid mode"
        testParent.allRequests.every { it.method != "setSwitch" }

        and: "an error was logged (invalid mode rejection)"
        testLog.errors.any { it.contains("Invalid mode") || it.contains("turbo") }
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #25: C3 gate case-sensitivity — uppercase "ON"/"OFF" input
    // -------------------------------------------------------------------------

    def "BP25: setAutoStop('ON') uppercase makes the API call and sends enabled:true (not false)"() {
        // Pre-fix: ("ON" == "on") is false → enabled:false sent (disable instead of enable).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → enabled:true (correct).
        given: "autoStopEnabled is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "off"])

        when:
        driver.setAutoStop("ON")

        then: "setAutomaticStop API call was made"
        def req = testParent.allRequests.find { it.method == "setAutomaticStop" }
        req != null

        and: "payload carries enabled:true (enable auto-stop), NOT false (disable)"
        req.data.enabled == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue("autoStopEnabled") == "on"
    }

    def "BP25: setAutoStop('ON') when already 'on' is a no-op (C3 gate works with uppercase)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "on"])

        when:
        driver.setAutoStop("ON")

        then: "no API call was made"
        testParent.allRequests.find { it.method == "setAutomaticStop" } == null
    }

    // -------------------------------------------------------------------------
    // Regression guards — setMistLevel null/0/valid input handling
    // -------------------------------------------------------------------------

    def "setMistLevel(null) is rejected with logWarn and no API call (BP18 Fix 4)"() {
        // Pre-fix: (null as Integer) ?: 1 silently set level to 1; no warning.
        // Post-fix: requireNotNull rejects null before coercion.
        when:
        driver.setMistLevel(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
        testLog.warns.any { it.contains("setMistLevel") || it.contains("null") }
    }

    def "setMistLevel(0) routes to off() (Fix 4 — SwitchLevel setLevel(0) convention)"() {
        // Pre-fix: 0 was clamped to 1 via Math.max(1, ...) — device stayed on at level 1.
        // Post-fix: lvl <= 0 → off() called.
        when:
        driver.setMistLevel(0)

        then: "setSwitch powerSwitch=0 (off) was sent"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == false  // Classic 300S uses {enabled: bool, id: 0}
        and: "no setVirtualLevel was sent"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()
    }

    def "setMistLevel(3) sends setVirtualLevel with id:0, level:3, type:'mist'"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(3)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.id    == 0
        req.data.level == 3
        req.data.type  == "mist"
        lastEventValue("mistLevel") == 3
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setMistLevel with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setMistLevel('#badInput') does not throw and does not make a setVirtualLevel API call (fallback=0 → off)"() {
        // Inputs that safeIntArg() maps to 0: "abc", "" (empty), true ("true" is not numeric).
        // 0 → lvl<=0 → off() path; no setVirtualLevel call made.
        // Pre-fix: (level as Integer) on any of these threw before the guard could fire.
        given: "device is on so the auto-on guard doesn't confuse the assertion"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setVirtualLevel API call — 0 coercion routes to off()"
        testParent.allRequests.findAll { it.method == "setVirtualLevel" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setMistLevel('5.7') does not throw and makes a setVirtualLevel API call with truncated value (5)"() {
        // safeIntArg("5.7") → BigDecimal("5.7").intValue() = 5.  5 > 0 so the off() early-return
        // is skipped; 5 is clamped to Math.max(1, Math.min(9, 5)) = 5 and sent to the cloud.
        // Truncation is the correct post-fix behaviour — matches Groovy's native (5.7 as Integer).
        given: "device is on"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setVirtualLevel API call was made with level=5 (truncated from 5.7)"
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 5
    }

    // -----------------------------------------------------------------------
    // BP26: safe numeric coercion — setHumidity with non-numeric inputs
    // -----------------------------------------------------------------------

    def "BP26: setHumidity('#badInput') does not throw and does not make a setTargetHumidity API call (fallback=0 → rejected)"() {
        // safeIntArg() maps non-numeric inputs to 0; 0 triggers the p<=0 guard → logWarn + return.
        // Pre-fix: (percent as Integer) on these inputs threw before the guard could fire.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setTargetHumidity API call — 0 coercion rejected by minimum-humidity guard"
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    def "BP26: setHumidity('5.7') does not throw and makes a setTargetHumidity API call with clamped value (30)"() {
        // safeIntArg("5.7") → 5 (truncation). 5 > 0 so the rejection guard is skipped;
        // Math.max(30, Math.min(80, 5)) = 30 (clamped to minimum). A valid cloud call is made.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity("5.7")

        then: "no exception thrown"
        noExceptionThrown()

        and: "a setTargetHumidity API call was made with target_humidity=30 (5 clamped to minimum)"
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 30
    }

    // -------------------------------------------------------------------------
    // doSetTargetHumidity (shared-helper) — direct-call coverage at the helper API.
    // Drivers that delegate via setHumidity already exercise the helper body; these
    // specs target the helper directly so a regression in the helper signature
    // (e.g. dropping the optional floor/ceiling overload) is caught even if every
    // call site delegates.
    // -------------------------------------------------------------------------

    def "doSetTargetHumidity: happy path uses default floor=30 / ceiling=80"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.doSetTargetHumidity(55)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 55
        lastEventValue("targetHumidity") == 55
    }

    def "doSetTargetHumidity: explicit floor override (40) clamps 25 to 40 (OasisMist 450S firmware floor)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.doSetTargetHumidity(25, 40, 80)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 40
    }

    def "doSetTargetHumidity(null): BP18 null-guard — no API call, no NPE"() {
        when:
        driver.doSetTargetHumidity(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()
        testLog.warns.any { it.contains("setHumidity") && it.contains("null") }
    }

    def "BP26: doSetTargetHumidity('#badInput') direct-helper call — safe coercion (no NumberFormatException), no API call"() {
        // Direct-helper coverage for the BP26 fix in LevoitHumidifierLib.doSetTargetHumidity:
        //   Integer p = safeIntArg(percent, 0)
        //   if (p <= 0) { logWarn ...; return }
        // Pre-fix would have used `(percent as Integer)` and thrown NumberFormatException
        // / GroovyCastException before the rejection guard could fire. This spec exercises
        // the helper at its public entry (the shared-library API used by Classic 200S/300S,
        // Dual 200S, LV600S/LV600S HubConnect, OasisMist 450S/1000S, Sprout Humidifier,
        // and Superior 6000S) so a regression in the helper itself is caught even if every
        // public-command call site delegates correctly.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.doSetTargetHumidity(badInput)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no setTargetHumidity API call — 0 coercion rejected by minimum-humidity guard"
        testParent.allRequests.findAll { it.method == "setTargetHumidity" }.isEmpty()

        where:
        badInput << ["abc", "", true]
    }

    // -------------------------------------------------------------------------
    // setDisplay (delegates to doSetDisplayStateSwitch in the lib) coverage —
    // happy path, BP25 case-sensitivity, C3 idempotency, BP18 null-guard.
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends setDisplay with {state:true} via shared helper (NOT screenSwitch)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("on")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.state == true
        !req.data.containsKey("screenSwitch")

        and: "emitted event value is canonical 'on'"
        lastEventValue("displayOn") == "on"
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

    def "BP25: setDisplay('ON') uppercase normalizes to canonical 'on' (case-sensitivity guard)"() {
        // Pre-fix would have sent state:false; post-fix sends state:true and emits 'on'.
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "displayOn", value: "off"])  // ensure C3 gate does not block

        when:
        driver.setDisplay("ON")

        then:
        def req = testParent.allRequests.find { it.method == "setDisplay" }
        req != null
        req.data.state == true
        lastEventValue("displayOn") == "on"
    }

    def "setDisplay('on') when already 'on' is a no-op (C3 gate via shared helper)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "displayOn", value: "on"])

        when:
        driver.setDisplay("on")

        then: "no API call was made"
        testParent.allRequests.find { it.method == "setDisplay" } == null
    }

    def "setDisplay(null): BP18 null-guard — no API call, no NPE"() {
        when:
        driver.setDisplay(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setDisplay" }.isEmpty()
    }

    // -------------------------------------------------------------------------
    // setAutoStop (delegates to doSetAutoStopEnabled in the lib) coverage —
    // happy path; existing specs above already cover BP25, C3 idempotency.
    // Adding a happy-path lowercase 'on' spec to confirm delegation produces
    // the {enabled: bool} payload via "setAutomaticStop".
    // -------------------------------------------------------------------------

    def "setAutoStop('on') sends setAutomaticStop with {enabled:true} via shared helper (NOT autoStopSwitch)"() {
        given:
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "off"])  // C3 gate clear

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
        testDevice.events.add([name: "autoStopEnabled", value: "on"])  // C3 gate clear

        when:
        driver.setAutoStop("off")

        then:
        def req = testParent.allRequests.find { it.method == "setAutomaticStop" }
        req != null
        req.data.enabled == false
    }

    def "setAutoStop(null): BP18 null-guard — no API call, no NPE"() {
        when:
        driver.setAutoStop(null)

        then:
        noExceptionThrown()
        testParent.allRequests.findAll { it.method == "setAutomaticStop" }.isEmpty()
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
}
