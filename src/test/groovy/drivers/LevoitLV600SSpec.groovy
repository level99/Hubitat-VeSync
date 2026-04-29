package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Unit tests for LevoitLV600S.groovy (Levoit LV600S Humidifier).
 *
 * Covers:
 *   Bug Pattern #1  -- 2-arg update(status, nightLight) signature exists and is callable
 *   Bug Pattern #3  -- envelope peel handles single-wrap and double-wrap (defensive)
 *   Bug Pattern #6  -- mist level not reported when device is off (mistLevel=0 when off)
 *   Bug Pattern #12 -- pref-seed fires on null, preserves false, fires once
 *   Happy path      -- full applyStatus from LUH-A602S.yaml emits expected events
 *   Switch payload  -- on() produces setSwitch with {enabled:true, id:0} (humidifier shape)
 *   Mist level      -- setMistLevel(5) produces setVirtualLevel with {id:0, level:5, type:'mist'}
 *   Warm mist level -- setWarmMistLevel(2) produces setVirtualLevel with {id:0, level:2, type:'warm'}
 *   Warm mist off   -- setWarmMistLevel(0) sets warmMistEnabled='off' (LV600S-correct logic)
 *   Mode write-path -- setMode("auto") canonical-accept: payload="auto", cached as "std"
 *                   -- setMode("auto") canonical-reject, alt-accept: falls back to "humidity", cached as "alt"
 *                   -- setMode("auto") with cached "alt": goes direct to "humidity" without retry
 *                   -- updated() clears state.firmwareVariant so firmware updates are re-detected
 *   Mode read-path  -- "auto"/"autoPro"/"humidity" all normalize to user-facing "auto"
 *   PR #505 check   -- mode payload uses 'mode' field key (not 'workMode')
 *   Target humidity -- setHumidity(55) produces setTargetHumidity with {target_humidity:55}
 *   Display field   -- 'display' key first; fallback to 'indicator_light_switch'
 *   No night-light  -- driver declares no setNightLight command, no night_light_brightness parsing
 *   Water lacks     -- state-change gating, INFO logged only on transition
 */
class LevoitLV600SSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitLV600S.groovy"
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
        given: "a single-wrapped LV600S status (device on, manual, mist=5, warm=2)"
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = v2StatusEnvelope(deviceData)

        when: "parent calls update(status, nightLight) with null nightLight (LV600S has no nightlight)"
        def result = driver.update(status, null)

        then: "method exists and returns without throwing MissingMethodException"
        result == true
        noExceptionThrown()
    }

    def "update(status) 1-arg signature is also callable"() {
        given:
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
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
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "device fields were reached -- switch=on and humidity=52 confirm dereference succeeded"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 52
    }

    def "applyStatus double-wrapped: peel-loop reaches device fields (Bug Pattern #3 defensive)"() {
        given: "double-wrapped envelope (defensive test; LV600S is single-wrapped in practice)"
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def doubleWrapped = humidifierStatusEnvelope(deviceData)

        when:
        driver.applyStatus(doubleWrapped)

        then: "device fields ARE correctly reached via peel loop"
        lastEventValue("switch") == "on"
        lastEventValue("humidity") == 52
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
    // Bug Pattern #6: mist level gating when device is off
    // -------------------------------------------------------------------------

    def "applyStatus device_off: switch=off, mistLevel=0 (Bug Pattern #6)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
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

        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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

        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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

        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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

    def "applyStatus happy path from device_on_warm_mist_level2 fixture emits expected events"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "switch is on"
        lastEventValue("switch") == "on"

        and: "mode is manual"
        lastEventValue("mode") == "manual"

        and: "mist level is 5"
        lastEventValue("mistLevel") == 5

        and: "humidity is 52"
        lastEventValue("humidity") == 52

        and: "targetHumidity is 55 (from configuration.auto_target_humidity)"
        lastEventValue("targetHumidity") == 55

        and: "warmMistLevel is 2"
        lastEventValue("warmMistLevel") == 2

        and: "warmMistEnabled is 'on' (level > 0)"
        lastEventValue("warmMistEnabled") == "on"

        and: "waterLacks is 'no'"
        lastEventValue("waterLacks") == "no"

        and: "displayOn is 'on' (display=true)"
        lastEventValue("displayOn") == "on"

        and: "autoStopReached is 'no'"
        lastEventValue("autoStopReached") == "no"

        and: "autoStopEnabled is 'off' (configuration.automatic_stop=false)"
        lastEventValue("autoStopEnabled") == "off"

        and: "info HTML is populated"
        lastEventValue("info") != null

        and: "no errors logged"
        testLog.errors.isEmpty()
    }

    def "applyStatus device_on_auto_mode fixture: mode='auto', auto-stop enabled"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_auto_mode as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("mode") == "auto"
        lastEventValue("switch") == "on"
        lastEventValue("autoStopEnabled") == "on"
        lastEventValue("targetHumidity") == 65
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
        given: "state.lastSwitchSet is unset; device.currentValue('switch') returns null/off (default)"
        settings.descriptionTextEnable = false
        // state.lastSwitchSet not seeded; testDevice.currentValue defaults to null

        when: "toggle() is called"
        driver.toggle()

        then: "on path was taken (setSwitch with enabled:true) since current is treated as not 'on'"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req != null
        req.data.enabled == true
    }

    // -------------------------------------------------------------------------
    // Mist level payload (LV600S-specific field names -- same as Classic 300S)
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
    // Warm-mist payload (LV600S-specific: type='warm', same setVirtualLevel method)
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
    }

    def "setWarmMistLevel(0) sends {level:0, type:'warm'} and emits warmMistEnabled='off' (LV600S-correct logic)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(0)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        req != null
        req.data.level == 0

        and: "state reflects off (level=0 -> off, NOT the pyvesync 200300S bug where enabled=True)"
        state.warmMistEnabled == "off"
        state.warmMistLevel == 0
    }

    def "setWarmMistLevel(3) sends {level:3, type:'warm'} and emits warmMistEnabled='on'"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(3)

        then:
        def req = testParent.allRequests.find { it.method == "setVirtualLevel" && it.data.type == "warm" }
        req != null
        req.data.level == 3
        state.warmMistEnabled == "on"
        state.warmMistLevel == 3
    }

    def "setWarmMistLevel with value out of range logs error and sends no request"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setWarmMistLevel(4)

        then:
        testParent.allRequests.isEmpty()
        testLog.errors.any { it.contains("4") || it.contains("warm") || it.contains("0-3") }
    }

    // -------------------------------------------------------------------------
    // Warm mist applyStatus: level=0 correct semantics (BP#6-adjacent)
    // -------------------------------------------------------------------------

    def "applyStatus warm_level=0: warmMistEnabled='off', warmMistLevel=0 (LV600S-correct logic)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_warm_off as Map)

        when:
        driver.applyStatus(status)

        then: "warm mist off when level=0 (correct logic: level-derived, NOT warm_enabled-derived)"
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus warm_level=2, warm_enabled=true: warmMistEnabled='on'"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("warmMistLevel") == 2
        lastEventValue("warmMistEnabled") == "on"
    }

    def "applyStatus warm_enabled fallback: warm_level absent but warm_enabled=true -> warmMistEnabled='on'"() {
        given:
        settings.descriptionTextEnable = false
        // warm_level absent; warm_enabled present -- exercises fallback path
        def deviceData = [
            enabled: true,
            humidity: 50,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "manual",
            water_lacks: false,
            humidity_high: false,
            water_tank_lifted: false,
            warm_enabled: true,
            // warm_level deliberately absent
            display: true,
            automatic_stop_reach_target: false,
            configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "fallback to warm_enabled field when warm_level is absent"
        lastEventValue("warmMistEnabled") == "on"
        // warmMistLevel should NOT be emitted when warm_level is absent
        testDevice.events.find { it.name == "warmMistLevel" } == null
    }

    // -------------------------------------------------------------------------
    // Mode write-path: multi-firmware try-canonical-then-fallback-with-cache (PR #505)
    // -------------------------------------------------------------------------

    def "setMode('auto') canonical-accept: first attempt uses 'auto' payload, cached as 'std'"() {
        given: "no cached firmware variant (first call)"
        settings.descriptionTextEnable = true
        assert state.firmwareVariant == null

        when: "setMode('auto') called -- defaultOkResponse => inner code 0 => canonical accepted"
        driver.setMode("auto")

        then: "first request used canonical 'auto' payload"
        def req = testParent.allRequests[0]
        req != null
        req.method == "setHumidityMode"
        req.data.mode == "auto"

        and: "only one request was sent (no retry needed)"
        testParent.allRequests.size() == 1

        and: "firmwareVariant cached as 'std'"
        state.firmwareVariant == "std"

        and: "mode event emitted as 'auto' (user-facing)"
        lastEventValue("mode") == "auto"

        and: "Superior 6000S 'workMode' field is absent"
        !req.data.containsKey("workMode")
    }

    def "setMode('auto') canonical-reject then alt-accept: falls back to 'humidity', cached as 'alt'"() {
        given: "no cached variant; canonical 'auto' payload rejected (inner code -1)"
        settings.descriptionTextEnable = true
        // First sendBypassRequest call gets inner-error response (rejects "auto")
        // Second call (retry with "humidity") gets success
        testParent.cannedResponse = TestParent.innerErrorResponse()

        when:
        driver.setMode("auto")

        then: "two requests sent: first 'auto' (rejected), then 'humidity' (accepted)"
        testParent.allRequests.size() == 2
        testParent.allRequests[0].data.mode == "auto"
        testParent.allRequests[1].data.mode == "humidity"

        and: "firmwareVariant cached as 'alt'"
        state.firmwareVariant == "alt"

        and: "mode event emitted as 'auto' (user-facing, despite 'humidity' payload used)"
        lastEventValue("mode") == "auto"
    }

    def "setMode('auto') with cached 'alt': goes direct to 'humidity' payload, no retry"() {
        given: "firmwareVariant already cached as 'alt' (from prior detection)"
        settings.descriptionTextEnable = true
        state.firmwareVariant = "alt"

        when:
        driver.setMode("auto")

        then: "only one request sent, directly with 'humidity' payload (cache hit)"
        testParent.allRequests.size() == 1
        testParent.allRequests[0].data.mode == "humidity"

        and: "firmwareVariant remains 'alt'"
        state.firmwareVariant == "alt"

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "updated() clears state.firmwareVariant so firmware updates get re-detected on next setMode('auto')"() {
        given: "firmwareVariant is set from a prior detection session"
        settings.descriptionTextEnable = false
        state.firmwareVariant = "alt"
        assert state.firmwareVariant == "alt"

        when: "updated() is called (e.g. user clicks Save Preferences after firmware update)"
        driver.updated()

        then: "state is cleared including firmwareVariant"
        state.firmwareVariant == null
    }

    def "setMode('sleep') sends setHumidityMode with {mode:'sleep'} -- no fallback logic for non-auto modes"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("sleep")

        then:
        def req = testParent.allRequests.find { it.method == "setHumidityMode" }
        req != null
        req.data.mode == "sleep"
        testParent.allRequests.size() == 1  // no retry for sleep
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
    // Mode read-path normalization: "auto"/"autoPro"/"humidity" all map to user-facing "auto"
    // -------------------------------------------------------------------------

    def "applyStatus mode='humidity' (EU firmware per PR #505) normalized to user-facing 'auto'"() {
        given:
        settings.descriptionTextEnable = false
        // Simulate LUH-A602S-WEU reporting mode='humidity' (its auto-mode alias)
        def deviceData = [
            enabled: true, humidity: 60, mist_virtual_level: 4, mist_level: 4,
            mode: "humidity",   // EU firmware auto-mode alias -- must normalize to "auto"
            water_lacks: false, humidity_high: false, water_tank_lifted: false,
            warm_enabled: false, warm_level: 0, display: true,
            automatic_stop_reach_target: false,
            configuration: [auto_target_humidity: 65, display: true, automatic_stop: true]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'auto' (normalized from 'humidity') for user-facing consistency"
        lastEventValue("mode") == "auto"
        noExceptionThrown()
    }

    def "applyStatus mode='autoPro' normalized to user-facing 'auto'"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true, humidity: 55, mist_virtual_level: 3, mist_level: 3,
            mode: "autoPro",   // alternate auto-mode alias (some firmware variants)
            water_lacks: false, humidity_high: false, water_tank_lifted: false,
            warm_enabled: false, warm_level: 0, display: true,
            automatic_stop_reach_target: false,
            configuration: [auto_target_humidity: 60, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'auto' (normalized from 'autoPro')"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='auto' passes through unchanged (standard firmware)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_auto_mode as Map)

        when:
        driver.applyStatus(status)

        then: "'auto' -> 'auto' (no remapping needed; normalization is a no-op)"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='sleep' passes through unchanged (not an auto alias)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)
        // device_on_warm_mist_level2 has mode='manual' -- use inline for sleep
        def deviceData = [
            enabled: true, humidity: 50, mist_virtual_level: 3, mist_level: 3,
            mode: "sleep", water_lacks: false, humidity_high: false, water_tank_lifted: false,
            warm_enabled: false, warm_level: 0, display: true,
            automatic_stop_reach_target: false,
            configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]

        when:
        driver.applyStatus(v2StatusEnvelope(deviceData))

        then: "'sleep' -> 'sleep' (not remapped)"
        lastEventValue("mode") == "sleep"
    }

    // -------------------------------------------------------------------------
    // Target humidity payload
    // -------------------------------------------------------------------------

    def "setHumidity(55) sends setTargetHumidity with {target_humidity:55}"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(55)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        // Note: payload uses snake_case field name (pyvesync fixture canonical)
        req.data.target_humidity == 55

        and: "camelCase field name absent (Superior 6000S style)"
        !req.data.containsKey("targetHumidity")
    }

    def "setHumidity clamps values below 30 to 30 (LV600S humidity_min=30 per device_map.py)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setHumidity(10)

        then:
        def req = testParent.allRequests.find { it.method == "setTargetHumidity" }
        req != null
        // LV600S uses 30 floor (NOT 40 like OasisMist 450S) per device_map.py base class default
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
    // No night-light (LV600S hardware does not have night-light)
    // -------------------------------------------------------------------------

    def "setNightLight command does NOT exist on LV600S driver (hardware absent)"() {
        when: "attempt to call setNightLight -- should throw MissingMethodException"
        driver.setNightLight("off")

        then: "MissingMethodException or similar thrown -- command intentionally not implemented"
        thrown(Exception)
    }

    def "applyStatus does NOT emit nightLight events even if night_light_brightness present in response"() {
        given:
        settings.descriptionTextEnable = false
        // Defensive test: even if some firmware returns night_light_brightness, driver ignores it
        def deviceData = [
            enabled: true,
            humidity: 50,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "manual",
            water_lacks: false,
            humidity_high: false,
            water_tank_lifted: false,
            warm_enabled: false,
            warm_level: 0,
            display: true,
            night_light_brightness: 50,   // present but must be ignored by LV600S driver
            automatic_stop_reach_target: false,
            configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "nightLight and nightLightBrightness events are NOT emitted"
        testDevice.events.find { it.name == "nightLight" } == null
        testDevice.events.find { it.name == "nightLightBrightness" } == null
    }

    // -------------------------------------------------------------------------
    // Display field aliasing
    // -------------------------------------------------------------------------

    def "applyStatus reads 'display' key when present (canonical path)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        // device_on_warm_mist_level2 has display:true
        def deviceData = fixture.responses.device_on_warm_mist_level2 as Map
        assert deviceData.containsKey("display")
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "displayOn is 'on' (display=true)"
        lastEventValue("displayOn") == "on"
    }

    def "applyStatus falls back to 'indicator_light_switch' when 'display' is absent (HA finding #8 defensive)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-A602S.yaml")
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
        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)
        // device_off: display=false

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("displayOn") == "off"
    }

    // -------------------------------------------------------------------------
    // Water lacks state-change gating
    // -------------------------------------------------------------------------

    def "applyStatus device_water_lacks: waterLacks='yes' and INFO logged on first occurrence"() {
        given:
        settings.descriptionTextEnable = true
        def fixture = loadYamlFixture("LUH-A602S.yaml")
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

        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)
        int infosBefore = testLog.infos.size()

        when:
        driver.applyStatus(status)

        then:
        def newInfos = testLog.infos.drop(infosBefore)
        !newInfos.any { it.toLowerCase().contains("water") || it.toLowerCase().contains("empty") }
    }

    // -------------------------------------------------------------------------
    // Configuration block parsing
    // -------------------------------------------------------------------------

    def "targetHumidity is read from configuration.auto_target_humidity, not top-level"() {
        given:
        settings.descriptionTextEnable = false
        // Inline fixture: configuration.auto_target_humidity=70, no top-level targetHumidity key
        def deviceData = [
            enabled: true,
            humidity: 45,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "auto",
            water_lacks: false,
            humidity_high: false,
            water_tank_lifted: false,
            warm_enabled: false,
            warm_level: 0,
            display: true,
            automatic_stop_reach_target: false,
            configuration: [
                auto_target_humidity: 70,
                display: true,
                automatic_stop: true
            ]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "targetHumidity is 70 (from configuration.auto_target_humidity)"
        lastEventValue("targetHumidity") == 70

        and: "autoStopEnabled is 'on' (configuration.automatic_stop=true)"
        lastEventValue("autoStopEnabled") == "on"
    }

    // -------------------------------------------------------------------------
    // Info HTML (Bug Pattern #7 guard: no device.currentValue() race)
    // -------------------------------------------------------------------------

    def "info HTML is built from local variables, not device.currentValue() (Bug Pattern #7 guard)"() {
        given: "fresh device with no prior events"
        assert testDevice.events.isEmpty()
        settings.descriptionTextEnable = true

        def fixture = loadYamlFixture("LUH-A602S.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

        when:
        driver.applyStatus(status)

        then:
        def infoVal = lastEventValue("info") as String
        infoVal != null
        infoVal.contains("Humidity:")
        infoVal.contains("52")     // raw humidity value from fixture
        infoVal.contains("Target:")
        infoVal.contains("55")     // targetHumidity from configuration
        infoVal.contains("Mode:")
        infoVal.contains("manual")
        infoVal.contains("Warm:")  // warm mist included in info when warm_level present
        infoVal.contains("L2")     // warm level 2
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
