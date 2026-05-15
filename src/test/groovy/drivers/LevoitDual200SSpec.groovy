package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitDual200S.groovy (Levoit Dual 200S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level 0 when device is off (mistLevel=0 when off)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from LUH-D301S.yaml canonical fixture emits expected events
 *   Switch payload  -- on() produces setSwitch with {enabled:true, id:0} (humidifier shape)
 *   Switch payload  -- off() produces setSwitch with {enabled:false, id:0}
 *   Toggle          -- reads state.lastSwitchSet to avoid read-after-write race (BP#7 protection)
 *   Mist level      -- setMistLevel(1) produces setVirtualLevel with {id:0, level:1, type:'mist'}
 *   Mist level      -- setMistLevel(2) produces setVirtualLevel with {id:0, level:2, type:'mist'}
 *   Mist clamping   -- setMistLevel(0) routes to off() (SwitchLevel setLevel(0) convention)
 *   Mist clamping   -- setMistLevel(3+) clamped to 2 (range ceiling; NOT 9 like Classic 300S)
 *   Mode write-path -- setMode("auto") canonical-accept: payload="auto", cached as "std"
 *                   -- setMode("auto") canonical-reject, alt-accept: falls back to "humidity", cached as "alt"
 *                   -- setMode("auto") with cached "alt": goes direct to "humidity" without retry
 *                   -- setMode("auto") both-rejected: logError, no event emitted
 *                   -- updated() clears state.firmwareVariant so firmware updates are re-detected
 *   Mode validation -- setMode("sleep") rejected with logError (no sleep mode on Dual 200S)
 *   Mode read-path  -- "auto"/"autoPro"/"humidity" all normalize to user-facing "auto"
 *   Mode read-path  -- mode field key is 'mode' (not 'workMode' -- Superior 6000S difference)
 *   Target humidity -- setHumidity(55) produces setTargetHumidity with {target_humidity:55}
 *   Display field   -- 'display' key first; fallback to 'indicator_light_switch'
 *   No warm mist    -- driver declares no setWarmMistLevel command; no warm fields parsed
 *   Water lacks     -- state-change gating, INFO logged only on transition
 *   Night-light     -- passive read of night_light_brightness attribute; no setter command
 */
class LevoitDual200SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitDual200S.groovy"
    }

    @Override
    Map defaultSettings() {
        // descriptionTextEnable null simulates first-install-before-Save state
        // that triggers the pref-seed path.
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #1: 2-arg update signature (REQUIRED for all children)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) 2-arg signature is callable (Bug Pattern #1)"() {
        given: "a single-wrapped Dual 200S status (device on, manual, mist=1)"
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight (Dual 200S has no nightlight command)"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LUH-D301S.yaml")
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
        given: "status map as parent passes it: {code:0, result:{<device fields>}}"
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached -- switch=on and humidity=35 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 35
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given: "double-wrapped envelope (defensive test; Dual 200S is single-wrapped in practice)"
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then: "device fields ARE correctly reached via peel loop"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 35
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
    // Bug Pattern #6: mist level 0 when device is off
    // -------------------------------------------------------------------------

    def "applyStatus device_off: switch=off, mistLevel=0 (Bug Pattern #6)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-D301S.yaml")
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

        def fixture = loadYamlFixture("LUH-D301S.yaml")
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

        def fixture = loadYamlFixture("LUH-D301S.yaml")
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

        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

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

    def "applyStatus happy path from device_on_manual_canonical fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def deviceData = fixture.responses.device_on_manual_canonical as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "mist level is 1 (canonical default for Dual 200S)"
        lastEventValue("mistLevel") == 1

        and: "humidity is 35"
        lastEventValue("humidity") == 35

        and: "targetHumidity is 50 (from configuration.auto_target_humidity)"
        lastEventValue("targetHumidity") == 50

        and: "waterLacks is 'no'"
        lastEventValue("waterLacks") == "no"

        and: "displayOn is 'on' (display=true)"
        lastEventValue("displayOn") == "on"

        and: "autoStopReached is 'no'"
        lastEventValue("autoStopReached") == "no"

        and: "autoStopEnabled is 'off' (configuration.automatic_stop=false)"
        lastEventValue("autoStopEnabled") == "off"

        and: "nightLightBrightness is emitted (passive read -- 0 in canonical fixture)"
        lastEventValue("nightLightBrightness") == 0

        and: "info HTML is populated"
        lastEventValue("info") != null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus device_on_auto_mist2 fixture: mode='auto', mist=2, auto-stop enabled"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_auto_mist2 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("switch") == "on"
        lastEventValue("mistLevel") == 2
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("targetHumidity") == 55
    }

    // -------------------------------------------------------------------------
    // Switch payload shape (humidifier-specific -- NOT purifier shape)
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

    def "toggle() reads state.lastSwitchSet when populated (read-after-write race avoidance)"() {
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
        given: "state.lastSwitchSet is unset; device.currentValue defaults to null/off"
        settings.descriptionTextEnable = false

        when: "toggle() is called"
        driver.toggle()

        then: "on path was taken (setSwitch with enabled:true) since current is treated as not 'on'"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == true
    }

    // -------------------------------------------------------------------------
    // Mist level payload (Dual 200S: range 1-2, NOT 1-9 like Classic 300S)
    // -------------------------------------------------------------------------

    def "setMistLevel(1) sends setVirtualLevel with {id:0, level:1, type:'mist'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(1)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.id == 0
        req.data.level == 1
        req.data.type == "mist"

        and: "Superior 6000S field names are absent (levelIdx, virtualLevel, levelType)"
        !req.data.containsKey("levelIdx")
        !req.data.containsKey("virtualLevel")
        !req.data.containsKey("levelType")
    }

    def "setMistLevel(2) sends setVirtualLevel with {id:0, level:2, type:'mist'}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(2)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 2
    }

    def "setMistLevel(1) passes through as the minimum valid level (Dual 200S range floor)"() {
        given: "device is on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMistLevel(1)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 1
    }

    def "setMistLevel clamps values above 2 to 2 (Dual 200S range ceiling -- NOT 9 like Classic 300S)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(9)  // Classic 300S max; should clamp to 2 on Dual 200S

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 2  // clamped to Dual 200S max (NOT passed through as 9)
    }

    def "setMistLevel clamps value 3 (one over max) to 2"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMistLevel(3)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" }
        req != null
        req.data.level == 2
    }

    // -------------------------------------------------------------------------
    // Mode write-path: only auto and manual valid; NO sleep
    // -------------------------------------------------------------------------

    def "setMode('sleep') is rejected with logError (no sleep mode on Dual 200S)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("sleep")

        then: "logError called -- sleep not valid for Dual 200S"
        !testLog.errors.isEmpty()

        and: "no mode request sent to device"
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

    def "setMode('manual') sends setHumidityMode with {mode:'manual'} -- field name is 'mode' not 'workMode'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("manual")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "manual"

        and: "Superior 6000S field name is absent"
        !req.data.containsKey("workMode")
    }

    // -------------------------------------------------------------------------
    // Mode write-path: multi-firmware try-canonical-then-fallback with cache
    // -------------------------------------------------------------------------

    def "setMode('auto') canonical-accept: sends {mode:'auto'}, caches firmwareVariant='std'"() {
        given:
        settings.descriptionTextEnable = false
        // testParent returns HTTP 200 with inner code 0 by default (canonical success)

        when:
        driver.setMode("auto")

        then: "canonical 'auto' payload was sent"
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "auto"

        and: "mode event emitted"
        lastEventValue("mode") == "auto"

        and: "firmware variant cached as 'std'"
        state.firmwareVariant == "std"
    }

    def "setMode('auto') canonical-reject then alt-accept: falls back to 'humidity', caches 'alt'"() {
        given: "device is on so BP24-B ensureSwitchOn() no-ops; mock responses go to mode requests only"
        testDevice.events.add([name: "switch", value: "on"])
        settings.descriptionTextEnable = false
        // Configure testParent to reject the first request (inner code != 0) and accept the second
        testParent.requestResponses = [
            [status: 200, data: [result: [code: -11000000]]],  // canonical 'auto' rejected
            [status: 200, data: [result: [code: 0]]]            // 'humidity' accepted
        ]

        when:
        driver.setMode("auto")

        then: "two setHumidityMode requests were made"
        def modeReqs = testParent.allRequests.findAll { it.method == "setHumidityMode" }
        modeReqs.size() == 2

        and: "first request used canonical 'auto'"
        modeReqs[0].data.mode == "auto"

        and: "second request used alternate 'humidity'"
        modeReqs[1].data.mode == "humidity"

        and: "mode event emitted (user-facing 'auto', not 'humidity')"
        lastEventValue("mode") == "auto"

        and: "firmware variant cached as 'alt'"
        state.firmwareVariant == "alt"
    }

    def "setMode('auto') with cached 'alt' variant: goes direct to 'humidity' without retry"() {
        given:
        settings.descriptionTextEnable = false
        state.firmwareVariant = "alt"  // pre-cached from a previous setMode call

        when:
        driver.setMode("auto")

        then: "only ONE setHumidityMode request was made (no canonical-then-fallback retry)"
        def modeReqs = testParent.allRequests.findAll { it.method == "setHumidityMode" }
        modeReqs.size() == 1

        and: "the single request used 'humidity' directly (cached alt variant)"
        modeReqs[0].data.mode == "humidity"

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "setMode('auto') both-rejected: logError fired, no mode event emitted"() {
        given: "device is on so BP24-B ensureSwitchOn() no-ops; mock responses go to mode requests only"
        testDevice.events.add([name: "switch", value: "on"])
        settings.descriptionTextEnable = false
        // Both canonical and alternate rejected
        testParent.requestResponses = [
            [status: 200, data: [result: [code: -11000000]]],
            [status: 200, data: [result: [code: -11000000]]]
        ]

        when:
        driver.setMode("auto")

        then: "error logged"
        !testLog.errors.isEmpty()

        and: "no mode event emitted"
        !eventEmitted("mode", "auto")
    }

    def "updated() clears state.firmwareVariant so firmware updates are re-detected"() {
        given:
        state.firmwareVariant = "alt"

        when:
        driver.updated()

        then:
        state.firmwareVariant == null
    }

    // -------------------------------------------------------------------------
    // Mode read-path: normalize firmware aliases to "auto"
    // -------------------------------------------------------------------------

    def "applyStatus normalizes mode='humidity' to user-facing 'auto' (EU firmware alias)"() {
        given:
        settings.descriptionTextEnable = false
        // Simulate EU firmware reporting 'humidity' as the auto mode
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 1,
                          mist_level: 1, mode: "humidity", water_lacks: false,
                          humidity_high: false, water_tank_lifted: false,
                          display: true, automatic_stop_reach_target: false,
                          night_light_brightness: 0,
                          configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "'humidity' normalized to 'auto' for user-facing attribute"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus normalizes mode='autoPro' to user-facing 'auto'"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 1,
                          mist_level: 1, mode: "autoPro", water_lacks: false,
                          humidity_high: false, water_tank_lifted: false,
                          display: true, automatic_stop_reach_target: false,
                          night_light_brightness: 0,
                          configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
    }

    // -------------------------------------------------------------------------
    // Target humidity payload
    // -------------------------------------------------------------------------

    def "setHumidity(55) sends setTargetHumidity with {target_humidity:55} (snake_case key)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(55)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        req.data.target_humidity == 55

        and: "camelCase key is absent"
        !req.data.containsKey("targetHumidity")
    }

    def "setHumidity clamps to 30 minimum"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(20)

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

    // -------------------------------------------------------------------------
    // Display field fallback chain (HA cross-check finding #8)
    // -------------------------------------------------------------------------

    def "applyStatus reads 'display' key first for displayOn"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

        when:
        driver.applyStatus(status)

        then: "'display' key was parsed correctly"
        lastEventValue("displayOn") == "on"  // display: true in canonical fixture
    }

    def "applyStatus falls back to 'indicator_light_switch' when 'display' key absent (legacy firmware)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_legacy_display_alias as Map)

        when:
        driver.applyStatus(status)

        then: "indicator_light_switch: true -> displayOn='on'"
        lastEventValue("displayOn") == "on"
    }

    // -------------------------------------------------------------------------
    // Night-light: passive read only (no setter command)
    // -------------------------------------------------------------------------

    def "applyStatus emits nightLightBrightness attribute from night_light_brightness field"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [enabled: true, humidity: 40, mist_virtual_level: 1,
                          mist_level: 1, mode: "manual", water_lacks: false,
                          humidity_high: false, water_tank_lifted: false,
                          display: true, automatic_stop_reach_target: false,
                          night_light_brightness: 50,  // dim
                          configuration: [auto_target_humidity: 50, display: true, automatic_stop: false]]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "nightLightBrightness attribute emitted as passive read"
        lastEventValue("nightLightBrightness") == 50
    }

    def "driver declares no setNightLight command (nightlight command absent per feature flag)"() {
        when:
        driver.setNightLight("dim")

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // No warm mist (hardware absent)
    // -------------------------------------------------------------------------

    def "driver declares no setWarmMistLevel command (warm mist hardware absent)"() {
        when:
        driver.setWarmMistLevel(2)

        then:
        thrown(MissingMethodException)
    }

    // -------------------------------------------------------------------------
    // Water lacks: state-change gating (INFO logged only on transition)
    // -------------------------------------------------------------------------

    def "applyStatus water_lacks transition from no to yes: logs INFO"() {
        given:
        settings.descriptionTextEnable = true
        state.lastWaterLacks = "no"
        def fixture = loadYamlFixture("LUH-D301S.yaml")
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
        def fixture = loadYamlFixture("LUH-D301S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_manual_canonical as Map)

        when:
        driver.applyStatus(status)

        then: "water is fine and was fine before -- no 'Water reservoir empty' INFO message"
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

    // -------------------------------------------------------------------------
    // BP24-B regression guard: auto-on from off-state (setMistLevel + setMode)
    // These specs MUST FAIL on pre-fix code (no ensureSwitchOn call) and
    // PASS on post-fix code (ensureSwitchOn added as first line).
    // -------------------------------------------------------------------------

    def "setMistLevel from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [enabled: false, humidity: 35, mist_virtual_level: 0, mist_level: 0,
                       mode: "manual", water_lacks: false, humidity_high: false,
                       water_tank_lifted: false, display: false,
                       automatic_stop_reach_target: false, night_light_brightness: 0,
                       configuration: [auto_target_humidity: 50, display: false, automatic_stop: false]]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMistLevel called while device is off"
        driver.setMistLevel(1)

        then: "setSwitch(enabled:true) was sent (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #25: C3 gate case-sensitivity — uppercase "ON"/"OFF" input
    // -------------------------------------------------------------------------

    def "BP25: setAutoStop('ON') uppercase makes the API call and sends enabled:true (not false)"() {
        // Pre-fix: ("ON" == "on") is false → enabled:false sent (disable auto-stop instead of enable).
        // Post-fix: toLowerCase() normalizes "ON" → "on" → enabled:true (correct).
        given: "autoStopEnabled is currently 'off' so the C3 gate does not block"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "off"])

        when: "setAutoStop is called with uppercase 'ON'"
        driver.setAutoStop("ON")

        then: "setAutomaticStop API call was made"
        def req = testParent.allRequests.find { it.method == "setAutomaticStop" }
        req != null

        and: "payload carries enabled:true (enable auto-stop), NOT false (disable)"
        req.data.enabled == true

        and: "emitted event value is lowercase 'on'"
        lastEventValue("autoStopEnabled") == "on"
    }

    def "BP25: setAutoStop('ON') when autoStopEnabled is already 'on' is a no-op (C3 gate works with uppercase)"() {
        // Pre-fix: ("on" == "ON") is false → gate bypassed, redundant API call made.
        // Post-fix: toLowerCase() yields "on" == "on" → gate fires, no API call.
        given: "autoStopEnabled is already 'on'"
        settings.descriptionTextEnable = false
        testDevice.events.add([name: "autoStopEnabled", value: "on"])

        when: "setAutoStop called with uppercase 'ON' (idempotent)"
        driver.setAutoStop("ON")

        then: "no API call was made"
        testParent.allRequests.find { it.method == "setAutomaticStop" } == null
    }

    def "setMode('auto') from off-state triggers on() via ensureSwitchOn() (BP24-B)"() {
        given: "device is off, turningOn flag not set"
        settings.descriptionTextEnable = false
        state.remove("turningOn")
        def offData = [enabled: false, humidity: 35, mist_virtual_level: 0, mist_level: 0,
                       mode: "manual", water_lacks: false, humidity_high: false,
                       water_tank_lifted: false, display: false,
                       automatic_stop_reach_target: false, night_light_brightness: 0,
                       configuration: [auto_target_humidity: 50, display: false, automatic_stop: false]]
        driver.applyStatus(v2StatusEnvelope(offData))
        testParent.allRequests.clear()

        when: "setMode called while device is off"
        driver.setMode("auto")

        then: "setSwitch(enabled:true) was sent (auto-on via ensureSwitchOn)"
        def onReq = testParent.allRequests.find { it.method == "setSwitch" && it.data.enabled == true }
        onReq != null
    }
}
