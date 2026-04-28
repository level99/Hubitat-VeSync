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
 *   Mode write-path -- setMode("auto") canonical-accept: payload="auto", cached as "std"
 *                   -- setMode("auto") canonical-reject, alt-accept: falls back to "humidity", cached as "alt"
 *                   -- setMode("auto") with cached "alt": goes direct to "humidity" without retry
 *                   -- updated() clears state.firmwareVariant so firmware updates are re-detected
 *   Mode read-path  -- "auto"/"autoPro"/"humidity" normalize to user-facing "auto";
 *                      "sleep"/"manual" pass through unchanged
 *   No nightlight   -- setNightLight is not a callable command; applyStatus ignores any
 *                      night_light_brightness field in the response
 *   probeNightLight -- constructs correct payload; logs SUCCESS/REJECTED based on inner code
 *   Target humidity -- setHumidity(50) produces setTargetHumidity with {target_humidity:50};
 *                      clamp floor is 40 (NOT 30) -- pyvesync issue #296, firmware rejects
 *                      values below 40 with API error 11003000
 *   Display         -- setDisplay produces setDisplay with {state: bool}
 *   Auto-stop       -- setAutoStop produces setAutomaticStop with {enabled: bool}
 *   Toggle pattern  -- state.lastSwitchSet populated -> toggle reads state; unset -> currentValue
 *   Fix 2 / DT     -- state.deviceType self-seed from getDataValue("deviceType") on first
 *                      applyStatus (for existing-deployed installs); guard preserves existing
 *                      value; WEU gate functional once seeded (setColor calls API, not no-op)
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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(deviceData)

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

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
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

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("mistLevel") == 0
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus normalizes mode='humidity' (alt-firmware auto mode) to user-facing 'auto' (v2.2 read-path)"() {
        // v2.2: 'humidity' in the API response is the alt-firmware name for auto mode
        // (pyvesync PR #505 / LUH-A602S-WEU variant). The driver normalizes it to 'auto'
        // for a consistent user-facing attribute regardless of firmware variant.
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_humidity_mode as Map)

        when:
        driver.applyStatus(status)

        then: "mode attribute is 'auto' (normalized from 'humidity' by read-path)"
        lastEventValue("switch") == "on"
        lastEventValue("mode") == "auto"
        lastEventValue("warmMistLevel") == 0
        lastEventValue("warmMistEnabled") == "off"
    }

    def "applyStatus device_warm_off: warm_level=0 -> warmMistEnabled='off' (level-derived, not field-derived)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        // device_warm_off has warm_enabled=false, warm_level=0 (consistent, correct case)
        def status = v2StatusEnvelope(fixture.responses.device_warm_off as Map)

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
        def status = v2StatusEnvelope(deviceData)

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
        state.lastWaterLacks = "yes"

        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_water_lacks as Map)
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
    // Mode write-path: multi-firmware try-canonical-then-fallback-with-cache
    // v2.2: 'humidity' is NOT a user-settable mode (not in command constraints).
    // However the write path transparently handles firmware variants where
    // setHumidityMode{mode:"auto"} is rejected (using "humidity" as alt payload).
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

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"

        and: "workMode field absent (450S uses 'mode', not Superior 6000S 'workMode')"
        !req.data.containsKey("workMode")
    }

    def "setMode('auto') canonical-reject then alt-accept: falls back to 'humidity', cached as 'alt'"() {
        given: "no cached variant; canonical 'auto' payload rejected (inner code -1)"
        settings.descriptionTextEnable = true
        testParent.cannedResponse = TestParent.innerErrorResponse()

        when:
        driver.setMode("auto")

        then: "two requests: first 'auto' (rejected), then 'humidity' (accepted via default OK)"
        testParent.allRequests.size() == 2
        testParent.allRequests[0].data.mode == "auto"
        testParent.allRequests[1].data.mode == "humidity"

        and: "firmwareVariant cached as 'alt'"
        state.firmwareVariant == "alt"

        and: "mode event emitted as 'auto' (user-facing, regardless of 'humidity' payload used)"
        lastEventValue("mode") == "auto"
    }

    def "setMode('auto') with cached 'alt': goes direct to 'humidity' payload without retry"() {
        given: "firmwareVariant already detected as 'alt'"
        settings.descriptionTextEnable = true
        state.firmwareVariant = "alt"

        when:
        driver.setMode("auto")

        then: "only one request, directly with 'humidity' payload (cache hit)"
        testParent.allRequests.size() == 1
        testParent.allRequests[0].data.mode == "humidity"

        and: "firmwareVariant remains 'alt'"
        state.firmwareVariant == "alt"

        and: "mode event emitted as 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "updated() clears state.firmwareVariant so firmware updates get re-detected"() {
        given:
        settings.descriptionTextEnable = false
        state.firmwareVariant = "alt"

        when:
        driver.updated()

        then: "state cleared including firmwareVariant (via state.clear() in updated())"
        state.firmwareVariant == null
    }

    def "setMode('humidity') is REJECTED as user input -- not a valid user mode (pyvesync issue #295)"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setMode("humidity")

        then: "no request sent -- 'humidity' is rejected by the allow-list guard in setMode()"
        testParent.allRequests.isEmpty()

        and: "error logged"
        testLog.errors.any { it.contains("humidity") || it.contains("Invalid mode") }
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
        testParent.allRequests.size() == 1
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
    // Mode read-path normalization: "auto"/"autoPro"/"humidity" -> "auto"
    // -------------------------------------------------------------------------

    def "applyStatus mode='humidity' normalized to 'auto' (alt-firmware auto alias, pyvesync PR #505)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true, humidity: 50, mist_virtual_level: 3, mist_level: 3,
            mode: "humidity", water_lacks: false, humidity_high: false,
            automatic_stop_reach_target: false, warm_enabled: false, warm_level: 0,
            display: true, configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode='humidity' from API is normalized to user-facing 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='autoPro' normalized to 'auto' (Superior-line alias that may appear on some variants)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true, humidity: 50, mist_virtual_level: 3, mist_level: 3,
            mode: "autoPro", water_lacks: false, humidity_high: false,
            automatic_stop_reach_target: false, warm_enabled: false, warm_level: 0,
            display: true, configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode='autoPro' from API is normalized to user-facing 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='auto' passes through unchanged (std-firmware canonical)"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true, humidity: 50, mist_virtual_level: 3, mist_level: 3,
            mode: "auto", water_lacks: false, humidity_high: false,
            automatic_stop_reach_target: false, warm_enabled: false, warm_level: 0,
            display: true, configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode='auto' passes through as 'auto'"
        lastEventValue("mode") == "auto"
    }

    def "applyStatus mode='sleep' passes through unchanged"() {
        given:
        settings.descriptionTextEnable = false
        def deviceData = [
            enabled: true, humidity: 50, mist_virtual_level: 3, mist_level: 3,
            mode: "sleep", water_lacks: false, humidity_high: false,
            automatic_stop_reach_target: false, warm_enabled: false, warm_level: 0,
            display: true, configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "mode='sleep' passes through unchanged"
        lastEventValue("mode") == "sleep"
    }

    // -------------------------------------------------------------------------
    // No nightlight: explicit absence assertions
    // -------------------------------------------------------------------------

    def "applyStatus does NOT emit nightLight or nightLightBrightness events (450S has no nightlight)"() {
        given:
        settings.descriptionTextEnable = false
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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
        def status = v2StatusEnvelope(deviceData)

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
        def status = v2StatusEnvelope(fixture.responses.device_on_warm_mist_level2 as Map)

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
        def status = v2StatusEnvelope(fixture.responses.device_warm_off as Map)

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
        def fixture = loadYamlFixture("LUH-O451S-WUS.yaml")
        def status = v2StatusEnvelope(fixture.responses.device_off as Map)
        // device_off: configuration.automatic_stop=true

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("autoStopEnabled") == "on"
    }

    // -------------------------------------------------------------------------
    // probeNightLight (diagnostic command -- pyvesync issue #500 evidence gathering)
    // -------------------------------------------------------------------------

    def "probeNightLight constructs correct payload: method=setNightLightBrightness, data={night_light_brightness:50}"() {
        given: "descriptionTextEnable on so INFO logs flow"
        settings.descriptionTextEnable = true

        when:
        driver.probeNightLight()

        then: "setNightLightBrightness was called"
        def req = testParent.allRequests.find { it.method == "setNightLightBrightness" }
        req != null

        and: "payload uses night_light_brightness=50 (snake_case, mid-range probe value)"
        req.data.night_light_brightness == 50

        and: "no other field names (defensive: not 'brightness', not 'nightLightBrightness')"
        !req.data.containsKey("brightness")
        !req.data.containsKey("nightLightBrightness")
    }

    def "probeNightLight logs SUCCESS message (always-INFO) when inner code is 0"() {
        given: "parent returns inner code 0 (device accepted nightlight command)"
        settings.descriptionTextEnable = false  // probe uses log.info directly, bypasses logInfo gate
        // Use cannedResponse to drive inner code 0 (device accepted)
        testParent.cannedResponse = [
            status: 200,
            data: [
                code: 0,
                result: [code: 0, result: [:], traceId: "test-trace"],
                traceId: "test-trace"
            ]
        ]

        when:
        driver.probeNightLight()

        then: "SUCCESS message logged at INFO -- visible regardless of descriptionTextEnable"
        // probe() uses log.info directly (not logInfo), so descriptionTextEnable=false doesn't suppress it
        testLog.infos.any {
            it.contains("SUCCESS") || it.contains("CONFIRMED")
        }

        and: "no REJECTED message"
        !testLog.infos.any { it.contains("REJECTED") }
    }

    def "probeNightLight logs REJECTED message (always-INFO) when inner code is non-zero"() {
        given: "parent returns inner code -1 (device rejected nightlight command)"
        settings.descriptionTextEnable = false
        // Use cannedResponse to drive inner code -1 (device rejected)
        testParent.cannedResponse = [
            status: 200,
            data: [
                code: 0,
                result: [code: -1, result: [:], traceId: "test-trace"],
                traceId: "test-trace"
            ]
        ]

        when:
        driver.probeNightLight()

        then: "REJECTED message logged at INFO"
        testLog.infos.any {
            it.contains("REJECTED") || it.contains("inner code")
        }

        and: "no SUCCESS message"
        !testLog.infos.any { it.contains("SUCCESS") || it.contains("CONFIRMED") }
    }

    // =========================================================================
    // RGB nightlight (LUH-O451S-WEU only) -- runtime-gated
    // All tests in this section operate under two sub-scenarios:
    //   (a) WEU variant: state.deviceType == "LUH-O451S-WEU" -> commands execute
    //   (b) Non-WEU variant: state.deviceType == anything else -> no-op + INFO log
    // =========================================================================

    // -------------------------------------------------------------------------
    // WEU gate: all commands no-op when variant != WEU
    // -------------------------------------------------------------------------

    def "setNightlightSwitch no-ops with INFO log when deviceType is NOT LUH-O451S-WEU"() {
        given: "a US variant (default -- state.deviceType not set or not WEU)"
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O451S-WUS"

        when:
        driver.setNightlightSwitch("on")

        then: "no API call sent"
        testParent.allRequests.isEmpty()

        and: "INFO log mentions unsupported / variant"
        testLog.infos.any { it.contains("not supported") || it.contains("variant") || it.contains("ignoring") }
    }

    def "setColor no-ops with INFO log when deviceType is NOT LUH-O451S-WEU"() {
        given:
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O451S-WUS"

        when:
        driver.setColor([hue: 50, saturation: 100, level: 80])

        then:
        testParent.allRequests.isEmpty()
        testLog.infos.any { it.contains("not supported") || it.contains("variant") || it.contains("ignoring") }
    }

    def "setHue no-ops with INFO log when deviceType is NOT LUH-O451S-WEU"() {
        given:
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O601S-KUS"

        when:
        driver.setHue(75)

        then:
        testParent.allRequests.isEmpty()
        testLog.infos.any { it.contains("not supported") || it.contains("variant") || it.contains("ignoring") }
    }

    def "setSaturation no-ops with INFO log when deviceType is NOT LUH-O451S-WEU"() {
        given:
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O601S-WUS"

        when:
        driver.setSaturation(50)

        then:
        testParent.allRequests.isEmpty()
        testLog.infos.any { it.contains("not supported") || it.contains("variant") || it.contains("ignoring") }
    }

    def "setNightlightSwitch no-ops when state.deviceType is null (uninitialized)"() {
        given:
        settings.descriptionTextEnable = true
        // state.deviceType not set

        when:
        driver.setNightlightSwitch("on")

        then:
        testParent.allRequests.isEmpty()
        testLog.infos.any { it.contains("not supported") || it.contains("ignoring") }
    }

    // -------------------------------------------------------------------------
    // WEU active: setNightlightSwitch payload correctness
    // -------------------------------------------------------------------------

    def "setNightlightSwitch('on') sends correct setLightStatus payload when WEU variant"() {
        given: "WEU variant; default white (no prior hue/sat state)"
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O451S-WEU"
        // No prior nightlightHue/nightlightSaturation set -- defaults to white

        when:
        driver.setNightlightSwitch("on")

        then: "setLightStatus request sent"
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null

        and: "action is 'on'"
        req.data.action == "on"

        and: "payload has all required fields"
        req.data.containsKey("brightness")
        req.data.containsKey("red")
        req.data.containsKey("green")
        req.data.containsKey("blue")
        req.data.containsKey("colorMode")
        req.data.speed == 0
        req.data.containsKey("colorSliderLocation")
    }

    def "setNightlightSwitch('off') sends setLightStatus with action='off'"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        state.nightlightBrightness = 80

        when:
        driver.setNightlightSwitch("off")

        then:
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.action == "off"
    }

    def "setNightlightSwitch('on') emits nightlightSwitch='on' event on success"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"

        when:
        driver.setNightlightSwitch("on")

        then:
        lastEventValue("nightlightSwitch") == "on"
    }

    def "setNightlightSwitch sets state.rgbNightlightSetTime on success"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        assert state.rgbNightlightSetTime == null

        when:
        driver.setNightlightSwitch("on")

        then: "rgbNightlightSetTime is stamped (non-null)"
        state.rgbNightlightSetTime != null
    }

    // -------------------------------------------------------------------------
    // WEU active: setColor payload correctness
    // -------------------------------------------------------------------------

    def "setColor sends setLightStatus with correct brightness-adjusted RGB and colorSliderLocation"() {
        given: "pure red hue=0 (0 degrees), sat=100, brightness=80"
        settings.descriptionTextEnable = true
        state.deviceType = "LUH-O451S-WEU"

        when: "setColor with red hue (hue=0 in Hubitat 0-100 scale = 0 degrees)"
        driver.setColor([hue: 0, saturation: 100, level: 80])

        then: "setLightStatus sent"
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null

        and: "action is 'on'"
        req.data.action == "on"

        and: "brightness is 80"
        req.data.brightness == 80

        and: "colorMode is 'color'"
        req.data.colorMode == "color"

        and: "speed is 0"
        req.data.speed == 0

        and: "colorSliderLocation is between 0 and 100"
        req.data.colorSliderLocation >= 0 && req.data.colorSliderLocation <= 100

        and: "RGB values are present and in valid range"
        req.data.red   >= 0 && req.data.red   <= 255
        req.data.green >= 0 && req.data.green <= 255
        req.data.blue  >= 0 && req.data.blue  <= 255
    }

    def "setColor emits hue, saturation, nightlightBrightness, nightlightSwitch='on' events"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"

        when:
        driver.setColor([hue: 50, saturation: 80, level: 70])

        then:
        lastEventValue("hue")                  == 50
        lastEventValue("saturation")           == 80
        lastEventValue("nightlightBrightness") == 70
        lastEventValue("nightlightSwitch")     == "on"
        lastEventValue("colorMode")            == "RGB"
    }

    def "setColor clamps brightness below 40 to 40 (firmware floor)"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"

        when:
        driver.setColor([hue: 30, saturation: 100, level: 20])  // 20 is below firmware floor

        then:
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness == 40  // clamped to minimum
        req.data.brightness != 20
    }

    def "setColor sets state.rgbNightlightSetTime on success"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"

        when:
        driver.setColor([hue: 0, saturation: 100, level: 100])

        then:
        state.rgbNightlightSetTime != null
    }

    // -------------------------------------------------------------------------
    // WEU active: setHue + setSaturation delegate correctly
    // -------------------------------------------------------------------------

    def "setHue sends setLightStatus using current saturation and brightness from state"() {
        given: "prior state has saturation=80 and brightness=90"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        state.nightlightSaturation = 80
        state.nightlightBrightness = 90

        when:
        driver.setHue(25)

        then: "setLightStatus sent"
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness == 90   // prior state brightness preserved
        req.data.action == "on"
    }

    def "setSaturation sends setLightStatus using current hue and brightness from state"() {
        given:
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        state.nightlightHue        = 180  // 180 degrees (stored as 0-360 in state)
        state.nightlightBrightness = 75

        when:
        driver.setSaturation(60)

        then:
        def req = testParent.allRequests.find { it.method == "setLightStatus" }
        req != null
        req.data.brightness == 75   // prior state brightness preserved
        req.data.action == "on"
    }

    // -------------------------------------------------------------------------
    // HSV math: pure-function tests on rgbToHsv / hsvToRgb round-trip
    // These call the static helpers directly via driver.rgbToHsv / driver.hsvToRgb.
    // -------------------------------------------------------------------------

    def "rgbToHsv(255,0,0) returns (h~=0, s=1.0, v=1.0) -- red"() {
        when:
        def (h, s, v) = driver.rgbToHsv(255, 0, 0)

        then:
        (h as Double) < 0.01 || (h as Double) > 0.99   // hue wraps near 0/1 for red
        Math.abs((s as Double) - 1.0) < 0.01
        Math.abs((v as Double) - 1.0) < 0.01
    }

    def "rgbToHsv(0,255,0) returns (h~=0.333, s=1.0, v=1.0) -- green"() {
        when:
        def (h, s, v) = driver.rgbToHsv(0, 255, 0)

        then:
        Math.abs((h as Double) - (1.0/3.0)) < 0.01
        Math.abs((s as Double) - 1.0) < 0.01
        Math.abs((v as Double) - 1.0) < 0.01
    }

    def "rgbToHsv(0,0,255) returns (h~=0.666, s=1.0, v=1.0) -- blue"() {
        when:
        def (h, s, v) = driver.rgbToHsv(0, 0, 255)

        then:
        Math.abs((h as Double) - (2.0/3.0)) < 0.01
        Math.abs((s as Double) - 1.0) < 0.01
        Math.abs((v as Double) - 1.0) < 0.01
    }

    def "rgbToHsv(128,128,128) returns (h~=any, s=0.0, v~=0.5) -- gray (achromatic)"() {
        when:
        def (h, s, v) = driver.rgbToHsv(128, 128, 128)

        then:
        Math.abs((s as Double)) < 0.01   // saturation == 0
        Math.abs((v as Double) - (128/255.0)) < 0.01
    }

    def "hsvToRgb(0.0, 1.0, 1.0) returns (255, 0, 0) -- red"() {
        when:
        def (r, g, b) = driver.hsvToRgb(0.0, 1.0, 1.0)

        then:
        (r as Integer) == 255
        (g as Integer) == 0
        (b as Integer) == 0
    }

    def "hsvToRgb(1/3.0, 1.0, 1.0) returns (0, 255, 0) -- green"() {
        when:
        def (r, g, b) = driver.hsvToRgb(1/3.0, 1.0, 1.0)

        then:
        (r as Integer) == 0
        (g as Integer) == 255
        (b as Integer) == 0
    }

    def "hsvToRgb round-trip: convert RGB -> HSV -> RGB stays within 1 unit for mid-range purple"() {
        given: "purple (128, 0, 192)"
        int rIn = 128; int gIn = 0; int bIn = 192

        when: "round-trip via rgbToHsv then hsvToRgb"
        def (h, s, v) = driver.rgbToHsv(rIn, gIn, bIn)
        def (rOut, gOut, bOut) = driver.hsvToRgb(h as Double, s as Double, v as Double)

        then: "round-trip accurate to within 1 unit per channel (integer rounding)"
        Math.abs((rOut as Integer) - rIn) <= 1
        Math.abs((gOut as Integer) - gIn) <= 1
        Math.abs((bOut as Integer) - bIn) <= 1
    }

    def "hsvToRgb(any, 0.0, 0.5) returns gray (achromatic -- s=0 -> r==g==b)"() {
        when:
        def (r, g, b) = driver.hsvToRgb(0.25, 0.0, 0.5)

        then:
        (r as Integer) == (g as Integer)
        (g as Integer) == (b as Integer)
    }

    def "applyBrightnessToRgb(255,0,0, 50) returns red at 50% brightness -- v=0.5"() {
        when:
        def (r, g, b) = driver.applyBrightnessToRgb(255, 0, 0, 50)

        then: "hue preserved (red -- r high, g and b low), brightness halved"
        (r as Integer) > 100        // r still dominant
        (g as Integer) < 10
        (b as Integer) < 10
        // Not full brightness -- r should be ~127
        (r as Integer) < 200
    }

    def "applyBrightnessToRgb clamps brightness below 40 to 40"() {
        when: "pass brightness=20 (below firmware floor)"
        def (r, g, b) = driver.applyBrightnessToRgb(255, 0, 0, 20)

        and: "pass brightness=40 (at floor)"
        def (r2, g2, b2) = driver.applyBrightnessToRgb(255, 0, 0, 40)

        then: "both produce same result (clamped to 40)"
        (r as Integer) == (r2 as Integer)
        (g as Integer) == (g2 as Integer)
        (b as Integer) == (b2 as Integer)
    }

    // -------------------------------------------------------------------------
    // colorSliderLocation: nearest-point match assertions
    // -------------------------------------------------------------------------

    def "colorSliderLocation returns 0 for exact Red anchor (252,50,0)"() {
        when:
        int loc = driver.colorSliderLocation(252, 50, 0)

        then:
        loc == 0
    }

    def "colorSliderLocation returns 14 for exact Orange anchor (255,171,2)"() {
        when:
        int loc = driver.colorSliderLocation(255, 171, 2)

        then:
        loc == 14
    }

    def "colorSliderLocation returns 29 for exact Yellow-Green anchor (181,255,0)"() {
        when:
        int loc = driver.colorSliderLocation(181, 255, 0)

        then:
        loc == 29
    }

    def "colorSliderLocation returns 43 for exact Green anchor (2,255,120)"() {
        when:
        int loc = driver.colorSliderLocation(2, 255, 120)

        then:
        loc == 43
    }

    def "colorSliderLocation returns 57 for exact Cyan anchor (3,200,254)"() {
        when:
        int loc = driver.colorSliderLocation(3, 200, 254)

        then:
        loc == 57
    }

    def "colorSliderLocation returns 71 for exact Blue anchor (0,40,255)"() {
        when:
        int loc = driver.colorSliderLocation(0, 40, 255)

        then:
        loc == 71
    }

    def "colorSliderLocation returns 86 for exact Purple anchor (220,0,255)"() {
        when:
        int loc = driver.colorSliderLocation(220, 0, 255)

        then:
        loc == 86
    }

    def "colorSliderLocation returns 100 for exact Pink anchor (254,0,60)"() {
        when:
        int loc = driver.colorSliderLocation(254, 0, 60)

        then:
        loc == 100
    }

    def "colorSliderLocation for off-anchor pure blue (0,0,255) returns location nearest to Blue anchor"() {
        // (0,0,255) -- pure blue. Nearest anchor is Blue (0,40,255) at location=71.
        // Distance to Blue anchor: sqrt((0-0)^2 + (0-40)^2 + (255-255)^2) = 40
        // Distance to Purple (220,0,255): sqrt(220^2) = 220 -- much further
        when:
        int loc = driver.colorSliderLocation(0, 0, 255)

        then:
        loc == 71
    }

    def "colorSliderLocation for off-anchor pure red (255,0,0) returns location nearest to Red anchor"() {
        // (255,0,0) -- pure red. Nearest anchor is Red (252,50,0) at location=0.
        // Distance: sqrt(3^2 + 50^2) = sqrt(2509) ~= 50
        // vs Pink (254,0,60): sqrt(1^2 + 60^2) = sqrt(3601) ~= 60 -- Red is closer
        when:
        int loc = driver.colorSliderLocation(255, 0, 0)

        then:
        loc == 0
    }

    def "colorSliderLocation result is always in [0, 100]"() {
        // Arbitrary off-anchor color -- just verify bounds.
        when:
        int loc = driver.colorSliderLocation(100, 100, 100)  // gray -- undefined gradient behavior

        then:
        loc >= 0 && loc <= 100
    }

    // -------------------------------------------------------------------------
    // 180-second stale-data gate: applyStatus suppresses RGB within 180s of write
    // -------------------------------------------------------------------------

    def "applyStatus suppresses RGB attribute updates within 180s of last write (stale-data gate)"() {
        given: "WEU variant with rgbNightlightSetTime set 60s ago (within 180s window)"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        state.rgbNightlightSetTime = driver.now() - 60_000L  // 60 seconds ago

        // Status contains rgbNightLight data that should be suppressed
        def deviceData = buildBaseDeviceData() + [
            rgbNightLight: [action: "on", brightness: 80, red: 255, green: 0, blue: 0,
                            colorMode: "color", speed: 0, colorSliderLocation: 0]
        ]
        def status = v2StatusEnvelope(deviceData)

        // Capture events before applyStatus -- note any hue/sat/nightlightSwitch currently set
        int eventsBefore = testDevice.events.size()

        when:
        driver.applyStatus(status)

        then: "no RGB-specific events emitted (suppressed by stale gate)"
        def newEvents = testDevice.events.drop(eventsBefore)
        !newEvents.any { it.name == "hue" }
        !newEvents.any { it.name == "saturation" }
        !newEvents.any { it.name == "nightlightSwitch" }
        !newEvents.any { it.name == "nightlightBrightness" }
    }

    def "applyStatus emits RGB attributes after 180s stale window expires"() {
        given: "WEU variant with rgbNightlightSetTime set 300s ago (past 180s window)"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        state.rgbNightlightSetTime = driver.now() - 300_000L  // 300 seconds ago

        def deviceData = buildBaseDeviceData() + [
            rgbNightLight: [action: "on", brightness: 80, red: 2, green: 255, blue: 120,
                            colorMode: "color", speed: 0, colorSliderLocation: 43]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "RGB events ARE emitted (outside stale window)"
        lastEventValue("nightlightSwitch") == "on"
        lastEventValue("nightlightBrightness") == 80
        // hue/sat derived from (2,255,120) -- Green anchor area
        lastEventValue("hue") != null
        lastEventValue("saturation") != null
    }

    def "applyStatus emits RGB attributes when rgbNightlightSetTime is null (no prior write)"() {
        given: "WEU variant with no prior write (rgbNightlightSetTime not set)"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"
        // state.rgbNightlightSetTime is null

        def deviceData = buildBaseDeviceData() + [
            rgbNightLight: [action: "off", brightness: 100, red: 0, green: 40, blue: 255,
                            colorMode: "color", speed: 0, colorSliderLocation: 71]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then:
        lastEventValue("nightlightSwitch") == "off"
        lastEventValue("nightlightBrightness") == 100
    }

    def "applyStatus does NOT emit RGB events for non-WEU variant even if rgbNightLight field is present"() {
        given: "US variant with stray rgbNightLight field (should be ignored)"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WUS"   // non-WEU

        def deviceData = buildBaseDeviceData() + [
            rgbNightLight: [action: "on", brightness: 80, red: 255, green: 0, blue: 0,
                            colorMode: "color", speed: 0, colorSliderLocation: 0]
        ]
        def status = v2StatusEnvelope(deviceData)

        when:
        driver.applyStatus(status)

        then: "no RGB events"
        !testDevice.events.any { it.name == "nightlightSwitch" }
        !testDevice.events.any { it.name == "nightlightBrightness" }
        !testDevice.events.any { it.name == "hue" }
        !testDevice.events.any { it.name == "saturation" }
    }

    def "applyStatus handles null rgbNightLight gracefully on WEU variant (null-guard)"() {
        given: "WEU variant with no rgbNightLight key in response"
        settings.descriptionTextEnable = false
        state.deviceType = "LUH-O451S-WEU"

        def status = v2StatusEnvelope(buildBaseDeviceData())  // no rgbNightLight key

        when:
        driver.applyStatus(status)

        then: "no exception thrown"
        noExceptionThrown()

        and: "no RGB events emitted"
        !testDevice.events.any { it.name == "nightlightSwitch" }
    }

    def "updated() clears state.rgbNightlightSetTime so first applyStatus after update reads fresh RGB state"() {
        given:
        settings.descriptionTextEnable = false
        state.rgbNightlightSetTime = driver.now() - 1000L  // was set recently

        when:
        driver.updated()

        then: "state.clear() in updated() removes rgbNightlightSetTime"
        state.rgbNightlightSetTime == null
    }

    // =========================================================================
    // Fix 2 / Task 11: state.deviceType self-seed from getDataValue (WEU RGB gate)
    //
    // state.deviceType gates the RGB nightlight command path. Before this fix,
    // it was never written by the parent, so the gate was always false (silent
    // no-op for ALL users). Fix: parent now writes updateDataValue("deviceType",
    // device.deviceType) on addChildDevice; child self-seeds state.deviceType
    // from getDataValue("deviceType") on first applyStatus() for existing installs.
    //
    // Tests:
    //   DT1 — self-seed: absent state + getDataValue returns code -> state set
    //   DT2 — self-seed guard: existing state.deviceType not overwritten
    //   DT3 — WEU gate functional: setColor fires API when deviceType is WEU
    // =========================================================================

    def "state.deviceType self-seeded from getDataValue when absent (DT1)"() {
        // DT1: first applyStatus after upgrade on an existing-deployed install.
        // Parent already created the child and stored getDataValue("deviceType").
        // state.deviceType is absent (state.clear() on install cleared it).
        given: "deviceType data value is the WEU code; state.deviceType not yet set"
        assert !state.containsKey("deviceType")
        testDevice.updateDataValue("deviceType", "LUH-O451S-WEU")
        settings.descriptionTextEnable = false

        def status = v2StatusEnvelope(buildBaseDeviceData())

        when:
        driver.applyStatus(status)

        then: "state.deviceType was seeded from getDataValue"
        state.deviceType == "LUH-O451S-WEU"
    }

    def "state.deviceType self-seed guard preserves existing value (DT2)"() {
        // DT2: if state.deviceType is already set (e.g. from a prior applyStatus),
        // the self-seed must not overwrite it -- regardless of what getDataValue returns.
        given: "state.deviceType already set; getDataValue returns a different value"
        state.deviceType = "LUH-O451S-WUS"
        testDevice.updateDataValue("deviceType", "LUH-O451S-WEU")  // different value
        settings.descriptionTextEnable = false

        def status = v2StatusEnvelope(buildBaseDeviceData())

        when:
        driver.applyStatus(status)

        then: "state.deviceType unchanged -- guard protected it"
        state.deviceType == "LUH-O451S-WUS"
    }

    def "WEU gate is functional: setColor fires API when deviceType is WEU (DT3)"() {
        // DT3: end-to-end gate check. If state.deviceType == "LUH-O451S-WEU",
        // setColor(r:255, g:0, b:0) must actually call sendBypassRequest (i.e., the
        // isRgbSupported() guard returns true and the command proceeds).
        // We verify an HTTP call was captured (not silently no-oped).
        given:
        state.deviceType = "LUH-O451S-WEU"
        settings.descriptionTextEnable = false

        when:
        driver.setColor([hue: 0, saturation: 100, level: 100])

        then: "at least one parent.sendBypassRequest call was made (RGB not no-oped)"
        noExceptionThrown()
        // TestParent.allRequests captures every sendBypassRequest call.
        // If the WEU gate returned false (pre-fix: always), no call would be recorded.
        testParent.allRequests.size() >= 1
    }

    // =========================================================================
    // Helper: shared base device data for RGB tests (avoids repeating the full
    // fixture structure in every RGB scenario)
    // =========================================================================
    private Map buildBaseDeviceData(){
        return [
            enabled: true,
            humidity: 45,
            mist_virtual_level: 3,
            mist_level: 3,
            mode: "auto",
            water_lacks: false,
            humidity_high: false,
            automatic_stop_reach_target: false,
            warm_enabled: false,
            warm_level: 0,
            display: true,
            configuration: [auto_target_humidity: 55, display: true, automatic_stop: false]
        ]
    }
}
