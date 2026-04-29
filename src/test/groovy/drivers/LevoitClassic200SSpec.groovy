package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitClassic200S.groovy (Levoit Classic 200S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level 0 when device is off (mistLevel=0 when off)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from Classic200S.yaml canonical fixture emits expected events
 *   Switch payload  -- on() produces setSwitch with {enabled:true, id:0} (humidifier shape)
 *                   -- NOT purifier shape {powerSwitch, switchIdx}
 *                   -- NOT A603S/V2-class shape {powerSwitch: int, switchIdx}
 *   Display command -- setDisplay uses setIndicatorLightSwitch with {enabled:bool, id:0}
 *                   -- NOT setDisplay with {state: bool} (Classic 300S difference)
 *                   -- NOT setDisplay with {screenSwitch: int} (LV600S Hub Connect difference)
 *   Mode write-path -- setMode("auto") sends {mode:'auto'} -- NOT {workMode:...}
 *                   -- setMode("sleep") rejected (no sleep mode on Classic 200S)
 *   Mode read-path  -- "auto" passes through; "humidity"/"autoPro" normalize to "auto"
 *   Mode field key  -- response uses 'mode' key (not 'workMode' -- V2-class difference)
 *   Mist level      -- setMistLevel(5) produces setVirtualLevel with {id:0, level:5, type:'mist'}
 *   Mist range      -- clamped 1-9 (NOT 1-2 like Dual 200S)
 *   Target humidity -- setHumidity(55) produces setTargetHumidity with {target_humidity:55} snake_case
 *                   -- target humidity from configuration.auto_target_humidity (nested, same as Classic 300S)
 *                   -- NOT top-level camelCase targetHumidity (LV600S Hub Connect difference)
 *   Display read    -- indicator_light_switch FIRST (primary key for Classic 200S)
 *                   -- fallback to 'display' key (defensive for firmware variants)
 *   Night-light     -- passive read of nightLightBrightness attribute; no setNightLight command
 *   No warm mist    -- driver declares no setWarmMistLevel command (hardware absent)
 *   Water lacks     -- state-change gating, INFO logged only on transition
 */
class LevoitClassic200SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitClassic200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature (REQUIRED for all children)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Classic 200S status (device on, manual, mist=3)"
        def fixture = loadYamlFixture("Classic200S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("Classic200S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
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
        given:
        def fixture = loadYamlFixture("Classic200S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached -- switch=on and humidity=42 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 42
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given: "double-wrapped envelope (defensive test)"
        def fixture = loadYamlFixture("Classic200S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 42
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

    def "applyStatus device_off: switch=off, mistLevel=0 (Bug Pattern #6)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic200S.yaml")
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

        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

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

        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

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

        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

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

    def "applyStatus happy path from device_on_manual_canonical fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("Classic200S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("mode") == "manual"
        lastEventValue("mistLevel") == 3
        lastEventValue("humidity") == 42
        lastEventValue("targetHumidity") == 50
        lastEventValue("waterLacks") == "no"
        lastEventValue("displayOn") == "on"    // indicator_light_switch: true
        lastEventValue("autoStopReached") == "no"
        lastEventValue("autoStopEnabled") == "off"
        lastEventValue("nightLightBrightness") == 0
        lastEventValue("info") != null
        testLog.errors.isEmpty()
    }

    def "applyStatus device_on_auto_mist7 fixture: mode='auto', mist=7, auto-stop enabled"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_auto_mist7 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("switch") == "on"
        lastEventValue("mistLevel") == 7
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("targetHumidity") == 55
    }

    // -------------------------------------------------------------------------
    // Switch payload shape (humidifier-specific -- NOT purifier or V2-class shape)
    // -------------------------------------------------------------------------

    def "on() sends setSwitch with {enabled:true, id:0} -- humidifier shape, NOT purifier or A603S shape"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == true
        req.data.id == 0

        and: "purifier field names are absent"
        !req.data.containsKey("powerSwitch")
        !req.data.containsKey("switchIdx")
    }

    def "off() sends setSwitch with {enabled:false, id:0}"() {
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

    def "toggle() reads state.lastSwitchSet to avoid read-after-write race"() {
        given:
        settings.descriptionTextEnable = false
        state.lastSwitchSet = "on"

        when:
        driver.toggle()

        then:
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == false
    }

    // -------------------------------------------------------------------------
    // Display command: setIndicatorLightSwitch (Classic 200S override -- NOT setDisplay)
    // -------------------------------------------------------------------------

    def "setDisplay('on') sends setIndicatorLightSwitch with {enabled:true, id:0} -- NOT setDisplay(state:bool)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("on")

        then: "method is setIndicatorLightSwitch (Classic 200S class override)"
        def req = testParent.allRequests.find { it.method == "setIndicatorLightSwitch" }
        req != null
        req.data.enabled == true
        req.data.id == 0

        and: "Classic 300S method name is NOT used"
        def wrongReq = testParent.allRequests.find { it.method == "setDisplay" }
        wrongReq == null
    }

    def "setDisplay('off') sends setIndicatorLightSwitch with {enabled:false, id:0}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setDisplay("off")

        then:
        def req = testParent.allRequests.find { it.method == "setIndicatorLightSwitch" }
        req != null
        req.data.enabled == false
        req.data.id == 0
    }

    // -------------------------------------------------------------------------
    // Mode write-path: only auto and manual valid; NO sleep; payload field is 'mode' not 'workMode'
    // -------------------------------------------------------------------------

    def "setMode('auto') sends setHumidityMode with {mode:'auto'} -- NOT workMode, NOT 'humidity'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("auto")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "auto"

        and: "V2-class field name absent (workMode is LV600S Hub Connect / Superior 6000S)"
        !req.data.containsKey("workMode")

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
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
        !req.data.containsKey("workMode")
    }

    def "setMode('sleep') is rejected with logError (no sleep mode on Classic 200S per device_map.py)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("sleep")

        then:
        !testLog.errors.isEmpty()
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req == null
    }

    def "setMode('invalid') is rejected with logError"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("turbo")

        then:
        !testLog.errors.isEmpty()
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req == null
    }

    // -------------------------------------------------------------------------
    // Mode read-path normalization
    // -------------------------------------------------------------------------

    def "applyStatus mode='humidity' normalized to user-facing 'auto' (defensive; consistent with sibling drivers)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 3, mist_level: 3,
                          mode: "humidity", water_lacks: false, humidity_high: false,
                          water_tank_lifted: false, indicator_light_switch: true,
                          automatic_stop_reach_target: false, night_light_brightness: 0,
                          configuration: [auto_target_humidity: 50, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='autoPro' normalized to user-facing 'auto'"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 3, mist_level: 3,
                          mode: "autoPro", water_lacks: false, humidity_high: false,
                          water_tank_lifted: false, indicator_light_switch: true,
                          automatic_stop_reach_target: false, night_light_brightness: 0,
                          configuration: [auto_target_humidity: 50, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    // -------------------------------------------------------------------------
    // Mist level payload (Classic 200S range 1-9, same as Classic 300S; NOT 1-2 like Dual 200S)
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

        and: "V2-class field names absent (LV600S Hub Connect uses levelIdx/virtualLevel/levelType)"
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

    def "setMistLevel clamps values above 9 to 9 (Classic 200S max -- NOT 2 like Dual 200S)"() {
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
    // Target humidity payload (snake_case, nested in configuration -- same as Classic 300S)
    // -------------------------------------------------------------------------

    def "setHumidity(55) sends setTargetHumidity with {target_humidity:55} snake_case key"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(55)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 55

        and: "camelCase key absent (LV600S Hub Connect / Superior 6000S style)"
        !req.data.containsKey("targetHumidity")
    }

    def "setHumidity clamps to 30 minimum"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(10)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 30
    }

    def "setHumidity clamps to 80 maximum"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(95)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 80
    }

    def "targetHumidity is read from configuration.auto_target_humidity (nested -- same as Classic 300S)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 3, mist_level: 3,
                          mode: "auto", water_lacks: false, humidity_high: false,
                          water_tank_lifted: false, indicator_light_switch: true,
                          automatic_stop_reach_target: false, night_light_brightness: 0,
                          configuration: [auto_target_humidity: 70, automatic_stop: true]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "targetHumidity 70 from configuration.auto_target_humidity (nested, NOT top-level camelCase)"
        lastEventValue("targetHumidity") == 70
    }

    // -------------------------------------------------------------------------
    // Display read-path: indicator_light_switch FIRST (Classic 200S primary key)
    // -------------------------------------------------------------------------

    def "applyStatus reads indicator_light_switch FIRST for displayOn (Classic 200S primary key)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

        when:
        driver.applyStatus(status)

        then: "indicator_light_switch: true -> displayOn='on'"
        lastEventValue("displayOn") == "on"
    }

    def "applyStatus falls back to 'display' key when indicator_light_switch absent (firmware variant)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("Classic200S.yaml")
        // device_display_key_fallback: 'indicator_light_switch' absent, 'display' present
        def deviceData = fixture.responses.device_display_key_fallback as Map
        assert !deviceData.containsKey("indicator_light_switch") : "fixture must NOT have indicator_light_switch"
        assert deviceData.containsKey("display")
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "display: true -> displayOn='on' (fallback path)"
        lastEventValue("displayOn") == "on"
    }

    // -------------------------------------------------------------------------
    // Night-light: passive read only (no setter command)
    // -------------------------------------------------------------------------

    def "applyStatus emits nightLightBrightness from night_light_brightness field (passive read)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 3, mist_level: 3,
                          mode: "manual", water_lacks: false, humidity_high: false,
                          water_tank_lifted: false, indicator_light_switch: true,
                          automatic_stop_reach_target: false, night_light_brightness: 100,
                          configuration: [auto_target_humidity: 50, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightLightBrightness") == 100
    }

    def "driver declares no setNightLight command (features=[AUTO_STOP], no NIGHTLIGHT)"() {
        when:
        driver.setNightLight("dim")

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // No warm mist (hardware absent)
    // -------------------------------------------------------------------------

    def "driver declares no setWarmMistLevel command (Classic 200S has no warm mist hardware)"() {
        when:
        driver.setWarmMistLevel(2)

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // Water lacks: state-change gating
    // -------------------------------------------------------------------------

    def "applyStatus water_lacks transition from no to yes: logs INFO"() {
        given:
        settings.descriptionTextEnable = true
        state.lastWaterLacks = "no"
        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("waterLacks") == "yes"
        testLog.infos.any { it.contains("empty") || it.contains("Water") }
    }

    def "applyStatus waterLacks stable 'no': no INFO logged (state-change gate)"() {
        given:
        settings.descriptionTextEnable = true
        state.lastWaterLacks = "no"
        def fixture = loadYamlFixture("Classic200S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

        when:
        driver.applyStatus(status)

        then:
        !testLog.infos.any { it.contains("reservoir") }
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
}
