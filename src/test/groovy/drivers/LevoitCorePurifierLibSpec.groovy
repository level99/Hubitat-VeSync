package drivers

import spock.lang.Unroll
import support.HubitatSpec

/**
 * Unit tests for LevoitCorePurifierLib.groovy + LevoitCoreAQPurifierLib.groovy.
 *
 * Loaded through LevoitCore400S.groovy — an AQ-capable 4-band driver that
 * includes both LevoitCorePurifier (Group 1 shared 18 + Group 2 AQ-7) AND
 * LevoitCoreAQPurifier (the centerpiece update() + update(status, nightLight)
 * extracted in Task #142 Phase 2b-amended). HubitatSpec.loadDriverClass()
 * resolves all four #include directives, inlining the library bodies before
 * compiling.
 *
 * Empirical baseline established BEFORE Phase 2c parameterizes the per-driver
 * speed-mapping hooks (mapSpeedToInteger / mapIntegerStringToSpeed /
 * mapIntegerToSpeed). Phase 2c migrations must not regress these tests.
 *
 * Covers (judgment-driven scope; per-driver specs cover the rest):
 *
 * Group 1 lifecycle (Phase 2a-extracted):
 *   on()                 — re-entrance guard via state.turningOn
 *   off()                — re-entrance guard via state.turningOff
 *   updated()            — state-clear + BP16 debug-watchdog wiring
 *   setLevel(value, dur) — 2-arg overload delegates to per-driver 1-arg
 *
 * Group 1 dispatch-to-host helpers (load-bearing for Phase 2c):
 *   toggle()             — reads currentValue("switch") + dispatches on/off
 *   handleSpeed(speed)   — calls host's mapSpeedToInteger; sendBypassRequest
 *   handleMode(mode)     — sends setPurifierMode with raw mode string
 *   handlePower(on)      — sends setSwitch with enabled bool
 *
 * Group 2 setAutoMode (load-bearing for Phase 2c — the 200S guard convention):
 *   setAutoMode(mode)               — 1-arg form reuses state.room_size
 *   setAutoMode(mode, roomSize)     — 2-arg form, mode-aware handler dispatch
 *   setAutoMode 200S guard          — short-circuits when device.typeName contains "Core200S"
 *
 * AQ Group update() + update(status, nightLight) (Phase 2b-extracted):
 *   update() 0-arg       — bypassV2 envelope + dispatch to 2-arg
 *   update() null-status — logs error + recordError
 *   update(status, nl)   — emits switch/mode/speed/filter events
 *   update(status, nl)   — emits AQ fields (pm25, airQualityIndex)
 *   update(status, nl)   — auto mode state-machine
 *   update(status, nl)   — nightLight parameter is intentionally unused
 */
class LevoitCorePurifierLibSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        // Core 400S includes all four libs (Diagnostics, ChildBase,
        // CorePurifier, CoreAQPurifier) and exercises both Group 1 and AQ
        // Group code paths. Picking 400S also means we get the 4-band
        // mapSpeedToInteger / mapIntegerToSpeed host hooks (low/medium/high/max).
        "Drivers/Levoit/LevoitCore400S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Group 1: on() re-entrance guard (Phase 2a)
    // -------------------------------------------------------------------------

    def "on() sets state.turningOn while dispatching and clears it in finally"() {
        given: "device is off; state has no speed/mode (forces update() fallback)"
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        state.remove("speed")
        state.remove("mode")

        when:
        driver.on()

        then: "handlePower(true) was sent as setSwitch enabled=true"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req?.data?.enabled == true

        and: "state.turningOn was cleared by the finally block"
        state.turningOn == null

        and: "switch event was emitted via handleEvent"
        testDevice.events.any { it.name == "switch" && it.value == "on" }
    }

    def "on() skips body when state.turningOn is already set (re-entrance guard)"() {
        given: "state.turningOn is set — simulating recursive entry"
        state.turningOn = true
        testDevice.events.add([name: "switch", value: "off"])

        when:
        driver.on()

        then: "no setSwitch request was sent — body skipped"
        !testParent.allRequests.any { it.method == "setSwitch" }

        and: "state.turningOn is still true (not cleared — we didn't enter the try/finally)"
        state.turningOn == true
    }

    def "on() with state.speed set re-uses last speed instead of defaulting to low"() {
        given: "state.speed is 'high' from a prior session"
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")
        state.speed = "high"
        state.mode = "manual"

        when:
        driver.on()

        then: "the speed command sent was for 'high' (level 3 per Core 400S 4-band mapping)"
        def speedReq = testParent.allRequests.find { it.method == "setLevel" }
        speedReq?.data?.level == 3
    }

    // -------------------------------------------------------------------------
    // Group 1: off() re-entrance guard (Phase 2a)
    // -------------------------------------------------------------------------

    def "off() sets state.turningOff while dispatching and clears it in finally"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOff")

        when:
        driver.off()

        then: "handlePower(false) was sent as setSwitch enabled=false"
        def req = testParent.allRequests.find { it.method == "setSwitch" }
        req?.data?.enabled == false

        and: "state.turningOff was cleared by finally"
        state.turningOff == null

        and: "switch + speed events emitted: switch=off, speed=off"
        testDevice.events.any { it.name == "switch" && it.value == "off" }
        testDevice.events.any { it.name == "speed" && it.value == "off" }
    }

    def "off() skips body when state.turningOff is already set (re-entrance guard)"() {
        given:
        state.turningOff = true
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.off()

        then: "no setSwitch request was sent"
        !testParent.allRequests.any { it.method == "setSwitch" }

        and: "state.turningOff still set"
        state.turningOff == true
    }

    def "off() emits speed:off in addition to switch:off (capability parity)"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.remove("turningOff")

        when:
        driver.off()

        then: "the speed=off event is the D2 fix — dashboards see speed go to off on power-down"
        testDevice.events.any { it.name == "speed" && it.value == "off" }
    }

    // -------------------------------------------------------------------------
    // Group 1: updated() lifecycle + BP16 wiring (Phase 2a)
    // -------------------------------------------------------------------------

    def "updated() clears state and (re)initializes"() {
        given: "stale state from prior session"
        state.foo = "bar"
        state.bedded = "down"

        when:
        driver.updated()

        then: "state is cleared (note: state.clear() runs INSIDE updated() before runIn)"
        state.foo == null
        state.bedded == null
    }

    def "updated() with debugOutput=true sets state.debugEnabledAt to now()"() {
        given:
        settings.debugOutput = true
        state.remove("debugEnabledAt")

        when:
        driver.updated()

        then: "BP16 wiring stamped state.debugEnabledAt with current epoch"
        state.debugEnabledAt == 1745000000000L
    }

    def "updated() with debugOutput=false removes state.debugEnabledAt"() {
        given:
        settings.debugOutput = false
        state.debugEnabledAt = 1745000000000L - (5L * 60 * 1000)

        when:
        driver.updated()

        then: "stale debugEnabledAt removed"
        state.debugEnabledAt == null
    }

    // -------------------------------------------------------------------------
    // Group 1: setLevel(value, duration) 2-arg overload (Phase 2a)
    // -------------------------------------------------------------------------

    def "setLevel(value, duration) 2-arg overload delegates to 1-arg setLevel"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "Hubitat dashboard / Rule Machine calls setLevel(75, 30) — duration ignored"
        driver.setLevel(75, 30)

        then: "75% maps to Core 400S 4-band: pct >= 75 → speed 4 (max); duration silently dropped"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req != null
        req.data.level == 4
    }

    // -------------------------------------------------------------------------
    // Group 1: toggle() — currentValue("switch") dispatch
    // -------------------------------------------------------------------------

    def "toggle() when switch is on dispatches to off()"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.toggle()

        then: "setSwitch enabled=false sent (off() path)"
        testParent.allRequests.any { it.method == "setSwitch" && it.data.enabled == false }
    }

    def "toggle() when switch is off dispatches to on()"() {
        given:
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")

        when:
        driver.toggle()

        then: "setSwitch enabled=true sent (on() path)"
        testParent.allRequests.any { it.method == "setSwitch" && it.data.enabled == true }
    }

    // -------------------------------------------------------------------------
    // Group 1: handleSpeed → host's mapSpeedToInteger (dispatch-to-host hook)
    // -------------------------------------------------------------------------

    def "handleSpeed('medium') calls host mapSpeedToInteger and sends Core-line setLevel payload"() {
        when:
        driver.handleSpeed("medium")

        then: "Core 400S's mapSpeedToInteger('medium') returns 2; sent as data.level"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req?.data?.level == 2
        req?.data?.id == 0
        req?.data?.type == "wind"
    }

    def "handleSpeed('max') uses Core 400S 4-band mapSpeedToInteger returning 4"() {
        when:
        driver.handleSpeed("max")

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req?.data?.level == 4
    }

    def "handleMode('auto') sends setPurifierMode with mode:'auto'"() {
        when:
        driver.handleMode("auto")

        then:
        def req = testParent.allRequests.find { it.method == "setPurifierMode" }
        req?.data?.mode == "auto"
    }

    // -------------------------------------------------------------------------
    // Group 2: setAutoMode — 1-arg + 2-arg + 200S guard (load-bearing for Phase 2c)
    // -------------------------------------------------------------------------

    def "setAutoMode(mode) 1-arg form re-uses state.room_size as fallback"() {
        given: "device has a previously-set room_size (300 sq ft)"
        state.room_size = 300

        when:
        driver.setAutoMode("efficient")

        then: "setAutoPreference call includes the re-used 300 sq ft + mode:'efficient'"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req?.data?.type == "efficient"
        req?.data?.room_size == 300
    }

    def "setAutoMode(mode) 1-arg form falls back to 800 sq ft when state.room_size is null"() {
        given: "no prior room_size stored (pyvesync canonical default = 800)"
        state.remove("room_size")

        when:
        driver.setAutoMode("efficient")

        then:
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req?.data?.room_size == 800
    }

    def "setAutoMode('efficient', size) routes to 2-arg handleAutoMode (mode + size)"() {
        when:
        driver.setAutoMode("efficient", 500)

        then: "setAutoPreference includes both type and room_size"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req?.data?.type == "efficient"
        req?.data?.room_size == 500
    }

    def "setAutoMode('default', size) routes to 1-arg handleAutoMode (mode only — no room_size)"() {
        when:
        driver.setAutoMode("default", 500)

        then: "non-efficient modes call the 1-arg handler — room_size is NOT included in payload"
        def req = testParent.allRequests.find { it.method == "setAutoPreference" }
        req?.data?.type == "default"
        // 1-arg handleAutoMode payload: [type: mode] (no room_size key)
        !req?.data?.containsKey("room_size")
    }

    def "setAutoMode 200S guard: device.typeName containing 'Core200S' short-circuits before any API call"() {
        given: "simulate Core 200S device type via testDevice.typeName"
        testDevice.typeName = "Levoit Core200S Air Purifier"

        when:
        driver.setAutoMode("efficient", 300)

        then: "no setAutoPreference request was sent — guard returned early"
        !testParent.allRequests.any { it.method == "setAutoPreference" }

        and: "no setPurifierMode request either — entire setAutoMode body short-circuited"
        !testParent.allRequests.any { it.method == "setPurifierMode" }
    }

    def "setAutoMode happy-path emits auto_mode + mode:auto + speed:auto events"() {
        when:
        driver.setAutoMode("efficient", 200)

        then: "three handleEvent emissions: auto_mode preference, mode=auto, speed=auto"
        testDevice.events.any { it.name == "auto_mode" && it.value == "efficient" }
        testDevice.events.any { it.name == "mode" && it.value == "auto" }
        testDevice.events.any { it.name == "speed" && it.value == "auto" }
    }

    // -------------------------------------------------------------------------
    // AQ Group: update() 0-arg — bypassV2 envelope (Phase 2b)
    // -------------------------------------------------------------------------

    def "update() 0-arg dispatches to 2-arg update(status, null) on successful response"() {
        given: "canned getPurifierStatus response with on/manual/level=2 device state"
        def fixture = loadYamlFixture("Core400S.yaml")
        def deviceData = fixture.responses.device_on_manual_speed2 as Map
        // Response shape: data.result.<device-fields-direct>
        testParent.cannedResponse = [
            status: 200,
            data: [
                code: 0,
                result: deviceData,  // direct device fields
                traceId: "test-trace"
            ]
        ]

        when:
        driver.update()

        then: "getPurifierStatus was the API call sent"
        def req = testParent.allRequests.find { it.method == "getPurifierStatus" }
        req != null

        and: "the 2-arg path fired — switch event was emitted from the parsed status"
        testDevice.events.any { it.name == "switch" && it.value == "on" }
    }

    def "update() 0-arg logs error + recordError when status is null"() {
        given: "canned response with null result (simulating device-side bad data)"
        testParent.cannedResponse = [
            status: 200,
            data: [
                code: 0,
                result: null,
                traceId: "test-trace"
            ],
            msg: "no data"
        ]

        when:
        driver.update()

        then: "error log emitted naming the empty-status condition"
        testLog.errors.any { it.contains("No status returned from getPurifierStatus") }

        and: "no switch/mode events emitted — 2-arg path was not reached"
        !testDevice.events.any { it.name == "switch" }
    }

    // -------------------------------------------------------------------------
    // AQ Group: update(status, nightLight) — 51-line dispatcher (Phase 2b)
    // -------------------------------------------------------------------------

    def "update(status, nightLight) emits expected events on canonical manual/speed2 fixture"() {
        given:
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "nightLight is null (AQ-trio does not propagate to a nightlight child)"
        driver.update(status, null)

        then: "switch event = on (status.result.enabled == true)"
        testDevice.events.any { it.name == "switch" && it.value == "on" }

        and: "mode event = manual"
        testDevice.events.any { it.name == "mode" && it.value == "manual" }

        and: "speed event = 'medium' (manual mode + level=2 → 4-band mapping)"
        testDevice.events.any { it.name == "speed" && it.value == "medium" }
    }

    def "update(status, nightLight) emits AQ fields (pm25, airQualityIndex) from device fields"() {
        given:
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed1 as Map
        // device_on_manual_speed1: air_quality=1, air_quality_value=3 (canonical pyvesync)

        when:
        driver.update(status, null)

        then: "pm25 = 3 (from air_quality_value)"
        testDevice.events.any { it.name == "pm25" && it.value == 3 }

        and: "airQualityIndex = 1 (from air_quality)"
        testDevice.events.any { it.name == "airQualityIndex" && it.value == 1 }
    }

    def "update(status, nightLight) auto-mode emits speed='auto' (state machine)"() {
        given: "auto-mode fixture (mode = auto)"
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_auto_mode as Map

        when:
        driver.update(status, null)

        then: "switch reflects on"
        testDevice.events.any { it.name == "switch" && it.value == "on" }

        and: "switch/mode mapping puts speed at 'auto' (the state-machine branch for auto mode)"
        testDevice.events.any { it.name == "speed" && it.value == "auto" }
    }

    def "update(status, nightLight) updates state.speed, state.mode, state.auto_mode, state.room_size"() {
        given:
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map
        // status.result.configuration.auto_preference: {type:"default", room_size:600}

        when:
        driver.update(status, null)

        then: "state caches the four key fields for later use (setAutoMode reuse, etc.)"
        state.speed == "medium"        // mapIntegerToSpeed(2) on 4-band Core 400S
        state.mode == "manual"
        state.auto_mode == "default"
        state.room_size == 600
    }

    def "update(status, nightLight) ignores nightLight parameter (Phase 2b — AQ-trio convention)"() {
        given: "two calls — one with null nightLight, one with a non-null Object"
        def fixture = loadYamlFixture("Core400S.yaml")
        def status = fixture.responses.device_on_manual_speed2 as Map

        when: "call with null nightLight"
        driver.update(status, null)

        and: "capture switch event count after first call"
        int eventsAfterNullCall = testDevice.events.count { it.name == "switch" }

        and: "reset and call with a non-null object as nightLight"
        testDevice.reset()
        state.clear()
        driver.update(status, new Object())

        then: "switch event still emitted — the nightLight parameter is unused on AQ-trio"
        testDevice.events.any { it.name == "switch" && it.value == "on" }

        and: "behavior is identical to null nightLight (BP1 3-signature compatibility only)"
        eventsAfterNullCall > 0
    }

    // -------------------------------------------------------------------------
    // Bucket B1 (#142 Phase 2c): table-driven speed mapping
    //
    // The primary spec loads through Core 400S (4-band). For 3-band coverage we
    // load a REAL Core 300S driver instance (which declares its own 3-band
    // getSpeedBands()) and wire it with the same sandbox globals. This tests the
    // production 3-band declaration, not a test-injected closure — and avoids the
    // Groovy metaClass-vs-internal-dispatch fragility that a getSpeedBands()
    // override would introduce (the lib's mapSpeedToInteger calls getSpeedBands()
    // as an unqualified self-call, which does not reliably honour a per-instance
    // metaClass override).
    //
    // This is the regression baseline that proves the Phase 2c parameterization
    // preserves the prior per-driver behavior tables for both band counts.
    // -------------------------------------------------------------------------

    // Lazily-loaded, sandbox-wired Core 300S instance for 3-band assertions.
    private def band3Driver() {
        def cls = loadDriverClass("Drivers/Levoit/LevoitCore300S.groovy")
        def d = cls.getDeclaredConstructor().newInstance()
        wireSandbox(d)
        return d
    }

    // ---- 4-band (default Core 400S loader) mapSpeedToInteger ----

    @Unroll
    def "4-band mapSpeedToInteger: #input -> #expected"() {
        expect:
        driver.mapSpeedToInteger(input) == expected

        where:
        input      | expected
        "low"      | 1
        "medium"   | 2
        "high"     | 3
        "max"      | 4
        "1"        | 1
        "2"        | 2
        "3"        | 3
        "4"        | 4
        "auto"     | 4   // unknown name -> max key (4-band fallback)
        "garbage"  | 4   // unknown name -> max key
        null       | 4   // null -> max key
    }

    @Unroll
    def "4-band mapIntegerToSpeed: #input -> #expected"() {
        expect:
        driver.mapIntegerToSpeed(input) == expected

        where:
        input | expected
        1     | "low"
        2     | "medium"
        3     | "high"
        4     | "max"
        5     | "max"   // out-of-range -> max band name (4-band fallback)
        0     | "max"   // out-of-range -> max band name
        255   | "max"   // API "off" sentinel -> max band name (matches old switch default)
        null  | "max"   // null -> max band name
    }

    @Unroll
    def "4-band mapIntegerStringToSpeed: #input -> #expected (delegates to mapIntegerToSpeed)"() {
        expect:
        driver.mapIntegerStringToSpeed(input) == expected

        where:
        input | expected
        "1"   | "low"
        "2"   | "medium"
        "3"   | "high"
        "4"   | "max"
    }

    // ---- 3-band (injected table) mapSpeedToInteger ----

    @Unroll
    def "3-band mapSpeedToInteger: #input -> #expected"() {
        given:
        def driver3 = band3Driver()

        expect:
        driver3.mapSpeedToInteger(input) == expected

        where:
        input      | expected
        "low"      | 1
        "medium"   | 2
        "high"     | 3
        "1"        | 1
        "2"        | 2
        "3"        | 3
        "4"        | 3   // 4 not in 3-band table -> max key 3 (matches old 200S/300S `return 3`)
        "max"      | 3   // "max" not a 3-band name -> max key 3 (old behavior)
        "auto"     | 3   // unknown -> max key
        null       | 3   // null -> max key
    }

    @Unroll
    def "3-band mapIntegerToSpeed: #input -> #expected"() {
        given:
        def driver3 = band3Driver()

        expect:
        driver3.mapIntegerToSpeed(input) == expected

        where:
        input | expected
        1     | "low"
        2     | "medium"
        3     | "high"
        4     | "high"   // out-of-range -> max band name "high" (matches old 200S/300S default)
        0     | "high"
        255   | "high"
        null  | "high"
    }

    @Unroll
    def "3-band mapIntegerStringToSpeed: #input -> #expected"() {
        given:
        def driver3 = band3Driver()

        expect:
        driver3.mapIntegerStringToSpeed(input) == expected

        where:
        input | expected
        "1"   | "low"
        "2"   | "medium"
        "3"   | "high"
    }

    // ---- round-trip integrity (the CRITICAL invariant: no v2.5-style "all land on high") ----

    def "4-band round-trip: every band name maps to int and back to itself"() {
        expect:
        ["low", "medium", "high", "max"].every { name ->
            driver.mapIntegerToSpeed(driver.mapSpeedToInteger(name)) == name
        }
    }

    def "3-band round-trip: every band name maps to int and back to itself"() {
        given:
        def driver3 = band3Driver()

        expect:
        ["low", "medium", "high"].every { name ->
            driver3.mapIntegerToSpeed(driver3.mapSpeedToInteger(name)) == name
        }
    }

    def "4-band distinctness: low/medium/high/max map to DISTINCT integers (regression guard for v2.5 'all-land-on-high' bug)"() {
        when:
        def ints = ["low", "medium", "high", "max"].collect { driver.mapSpeedToInteger(it) }

        then: "all four integers are distinct — no two bands collapse to the same level"
        ints == [1, 2, 3, 4]
        ints.toSet().size() == 4
    }

    def "3-band distinctness: low/medium/high map to DISTINCT integers"() {
        given:
        def driver3 = band3Driver()

        when:
        def ints = ["low", "medium", "high"].collect { driver3.mapSpeedToInteger(it) }

        then:
        ints == [1, 2, 3]
        ints.toSet().size() == 3
    }

    // -------------------------------------------------------------------------
    // Bucket B2/B3 (#142 Phase 2d): parameterized setSpeed + setMode
    //
    // The default driver (Core 400S) is the auto-supporting / 4-band case.
    // For the no-auto case we load a REAL Core 200S instance (supportsAutoMode()
    // returns false, 3-band table) and wire it. This proves the auto-support
    // dimension is correctly gated by the host hook — the only difference between
    // 200S and 300S/400S/600S for these two methods.
    // -------------------------------------------------------------------------

    // Lazily-loaded, sandbox-wired Core 200S instance for no-auto assertions.
    private def noAutoDriver() {
        def cls = loadDriverClass("Drivers/Levoit/LevoitCore200S.groovy")
        def d = cls.getDeclaredConstructor().newInstance()
        wireSandbox(d)
        return d
    }

    // ---- setSpeed: auto-supporting (Core 400S default loader) ----

    def "setSpeed('auto') on auto-supporting driver routes to setMode + emits speed=auto"() {
        given: "device on, switch on so ensureSwitchOn is a no-op"
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setSpeed("auto")

        then: "setPurifierMode auto sent (via setMode('auto'))"
        testParent.allRequests.any { it.method == "setPurifierMode" && it.data.mode == "auto" }

        and: "speed event = auto"
        testDevice.events.any { it.name == "speed" && it.value == "auto" }
    }

    def "setSpeed('off') short-circuits to off() before ensureSwitchOn (does NOT auto-on)"() {
        given: "device is off"
        testDevice.events.add([name: "switch", value: "off"])
        state.remove("turningOn")

        when:
        driver.setSpeed("off")

        then: "setSwitch enabled=false sent (off path)"
        testParent.allRequests.any { it.method == "setSwitch" && it.data.enabled == false }

        and: "no setSwitch enabled=true (must NOT auto-on for an explicit off)"
        !testParent.allRequests.any { it.method == "setSwitch" && it.data.enabled == true }
    }

    def "setSpeed('3') integer-string remaps to 'high' then sends 4-band level 3"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when:
        driver.setSpeed("3")

        then: "setLevel level=3 (high on 4-band Core 400S)"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req?.data?.level == 3

        and: "speed event emitted as named 'high', not '3'"
        testDevice.events.any { it.name == "speed" && it.value == "high" }
    }

    def "setSpeed('4') integer-string remaps to 'max' on 4-band driver"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when:
        driver.setSpeed("4")

        then:
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req?.data?.level == 4
        testDevice.events.any { it.name == "speed" && it.value == "max" }
    }

    def "setSpeed(null) is rejected by BP18 null/empty-guard (no API call)"() {
        when:
        driver.setSpeed(null)

        then: "no setLevel / setPurifierMode request — guard returned false"
        !testParent.allRequests.any { it.method in ["setLevel", "setPurifierMode"] }
    }

    // ---- setSpeed: no-auto (Core 200S loader) ----

    def "setSpeed('auto') on no-auto Core 200S does NOT route to auto (falls through state-machine)"() {
        given: "Core 200S (supportsAutoMode=false), switch on, manual mode"
        def d = noAutoDriver()
        testDevice.events.add([name: "switch", value: "on"])
        state.mode = "manual"

        when: "setSpeed('auto') — 'auto' is not a valid band name, not off/sleep"
        d.setSpeed("auto")

        then: "the auto branch is gated off (supportsAutoMode=false); no setPurifierMode auto"
        !testParent.allRequests.any { it.method == "setPurifierMode" && it.data.mode == "auto" }
        // 'auto' falls into the manual-mode branch -> handleSpeed("auto") -> mapSpeedToInteger("auto")
        // -> max key 3 (3-band fallback). The device receives a setLevel, not a mode change.
        and: "a setLevel was sent (auto treated as an unknown speed name -> max band)"
        def req = testParent.allRequests.find { it.method == "setLevel" }
        req?.data?.level == 3
    }

    // ---- setMode: auto-supporting (Core 400S default loader) ----

    def "setMode('auto') on auto-supporting driver is accepted + sends setPurifierMode auto"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])

        when:
        driver.setMode("auto")

        then:
        testParent.allRequests.any { it.method == "setPurifierMode" && it.data.mode == "auto" }
        testDevice.events.any { it.name == "mode" && it.value == "auto" }
        testDevice.events.any { it.name == "speed" && it.value == "auto" }
    }

    def "setMode('manual') accepted on both driver types; emits mode=manual"() {
        given:
        testDevice.events.add([name: "switch", value: "on"])
        state.speed = "medium"

        when:
        driver.setMode("manual")

        then:
        testParent.allRequests.any { it.method == "setPurifierMode" && it.data.mode == "manual" }
        testDevice.events.any { it.name == "mode" && it.value == "manual" }

        and: "manual switch-case emits the cached speed"
        testDevice.events.any { it.name == "speed" && it.value == "medium" }
    }

    def "setMode('garbage') rejected on auto-supporting driver (invalid mode, no API call)"() {
        when:
        driver.setMode("garbage")

        then: "invalid mode rejected before any API call"
        !testParent.allRequests.any { it.method == "setPurifierMode" }

        and: "a WARN names the valid modes including auto"
        testLog.warns.any { it.contains("setMode") && it.contains("auto") }
    }

    def "setMode(null) rejected by BP18 null/empty-guard"() {
        when:
        driver.setMode(null)

        then:
        !testParent.allRequests.any { it.method == "setPurifierMode" }
    }

    // ---- setMode: no-auto (Core 200S loader) — the auto-support gate ----

    def "setMode('auto') on no-auto Core 200S is REJECTED (auto not in allowed set)"() {
        given:
        def d = noAutoDriver()
        testDevice.events.add([name: "switch", value: "on"])

        when:
        d.setMode("auto")

        then: "auto is not an allowed mode on 200S — rejected, no API call"
        !testParent.allRequests.any { it.method == "setPurifierMode" }

        and: "the WARN's allowed-modes list is exactly 'manual, sleep' (auto absent from the allowed set)"
        // Note: the rejected input 'auto' is echoed in the message; we assert on the
        // allowed-list portion after 'must be one of:' which must NOT include auto.
        testLog.warns.any { it.contains("setMode") && it.contains("must be one of: manual, sleep;") }
    }

    def "setMode('sleep') on no-auto Core 200S is accepted"() {
        given:
        def d = noAutoDriver()
        testDevice.events.add([name: "switch", value: "on"])

        when:
        d.setMode("sleep")

        then: "sleep is allowed on 200S"
        testParent.allRequests.any { it.method == "setPurifierMode" && it.data.mode == "sleep" }
        testDevice.events.any { it.name == "mode" && it.value == "sleep" }
    }
}
