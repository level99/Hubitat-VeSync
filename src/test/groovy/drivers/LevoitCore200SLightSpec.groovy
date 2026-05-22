package drivers

import support.HubitatSpec

/**
 * Unit tests for "LevoitCore200S Light.groovy" (Core 200S Night Light child driver).
 *
 * Covers:
 *   Bug Pattern #12 — pref-seed in update(status) (1-arg, night-light driver has no 2-arg)
 *   Happy path      — update(status) parses night_light field and fires level+switch events
 *
 * NOTE: The Core 200S Light is a SPECIAL CASE:
 *   - It has a 1-arg update(status) but NO 2-arg update(status, nightLight).
 *   - The parent calls nightLight.update(status) (with the FULL status map from the main purifier).
 *   - The driver reads status.result.night_light ("on" | "off" | "dim").
 *   - Bug Pattern #1 does NOT apply here — this driver intentionally has only a 1-arg signature
 *     because the parent calls it explicitly, not through the generic 2-arg callback path.
 *
 * NOTE: REGRESSION GUARD — these tests also indirectly verify that
 *   `def result = null` remains declared at the top of `update(status)` in
 *   Drivers/Levoit/LevoitCore200S Light.groovy:156. Removing that declaration
 *   reintroduces the latent undeclared-variable bug, which surfaces here as
 *   MissingPropertyException on `return result` and breaks every test in this
 *   spec that calls driver.update(status). If a future contributor sees these
 *   tests fail with that exception, the fix is to restore the `def result = null`
 *   line — not to modify the spec.
 *
 * The filename has a space in it ("LevoitCore200S Light.groovy"). The GroovyClassLoader
 * handles this fine; we just need to use the correct path string.
 */
class LevoitCore200SLightSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore200S Light.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: null, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #12: pref-seed
    // -------------------------------------------------------------------------

    def "pref-seed fires in update(status) when descriptionTextEnable is null (Bug Pattern #12)"() {
        given: "settings has descriptionTextEnable=null"
        settings.descriptionTextEnable = null
        assert !state.prefsSeeded

        // Core 200S Light's update(status) receives the full status map
        // The driver reads status.result.night_light
        def status = [
            result: [
                night_light: "off",
                level: 2,
                mode: "manual",
                enabled: false,
                filter_life: 90
            ],
            code: 0
        ]

        when:
        driver.update(status)

        then: "updateSetting called to seed descriptionTextEnable=true"
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall != null
        seedCall.value == true
        state.prefsSeeded == true
    }

    def "pref-seed does NOT overwrite user-set false (Bug Pattern #12)"() {
        given:
        settings.descriptionTextEnable = false

        def status = [result: [night_light: "off"], code: 0]

        when:
        driver.update(status)

        then:
        def seedCall = testDevice.settingsUpdates.find { it.name == "descriptionTextEnable" }
        seedCall == null
        state.prefsSeeded == true
    }

    // -------------------------------------------------------------------------
    // Happy path: night light parsing
    // -------------------------------------------------------------------------

    def "update(status) with night_light='on' emits switch=on and level=100"() {
        given:
        settings.descriptionTextEnable = true
        def status = [result: [night_light: "on"], code: 0]

        when:
        driver.update(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("level") == 100
        testLog.errors.isEmpty()
    }

    def "update(status) with night_light='off' emits switch=off and level=0"() {
        given:
        settings.descriptionTextEnable = true
        def status = [result: [night_light: "off"], code: 0]

        when:
        driver.update(status)

        then:
        lastEventValue("switch") == "off"
        lastEventValue("level") == 0
        testLog.errors.isEmpty()
    }

    def "update(status) with night_light='dim' emits switch=on and level=50"() {
        given:
        settings.descriptionTextEnable = true
        def status = [result: [night_light: "dim"], code: 0]

        when:
        driver.update(status)

        then:
        lastEventValue("switch") == "on"
        lastEventValue("level") == 50
        testLog.errors.isEmpty()
    }

    def "setNightLight sends correct payload to parent.sendBypassRequest"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setNightLight("dim")

        then:
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "dim"
    }

    def "on() delegates to setNightLight('on')"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.on()

        then:
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "on"
    }

    def "off() delegates to setNightLight('off')"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.off()

        then:
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "off"
    }

    def "setLevel(90) routes to setNightLight('on') since level > 75"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLevel(90)

        then:
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "on"
    }

    def "setLevel(5) routes to setNightLight('off') since level < 10"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLevel(5)

        then:
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "off"
    }

    def "setLevel(90, 30) 2-arg form delegates to 1-arg (SwitchLevel standard signature)"() {
        // Hubitat SwitchLevel capability advertises setLevel(level, duration). Without the 2-arg
        // overload, callers (Rule Machine with duration, dashboards, MCP) throw MissingMethodException.
        given:
        settings.descriptionTextEnable = false

        when: "setLevel is called with two args (level=90, duration=30)"
        driver.setLevel(90, 30)

        then: "same routing as setLevel(90) -- routes to setNightLight('on') since level > 75"
        noExceptionThrown()
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "on"
    }

    // -------------------------------------------------------------------------
    // BP18 regression guard: setLevel(null) does not throw NPE
    // -------------------------------------------------------------------------

    def "BP18: setLevel(null) does not throw and logs a warn (Core 200S Light)"() {
        // Regression guard: before this fix, null < 10 threw NPE (Groovy null arithmetic),
        // which the Hubitat sandbox swallowed silently. Core 200S Light's setLevel routing
        // (to setNightLight on/off/dim) has different semantics than the purifier's off() path,
        // so we use an explicit null early-return with a logWarn instead of the coerce-to-0 idiom.
        given:
        settings.descriptionTextEnable = false

        when:
        driver.setLevel(null)

        then: "no NPE thrown"
        noExceptionThrown()

        and: "no setNightLight API call was made"
        testParent.allRequests.findAll { it.method == "setNightLight" }.isEmpty()

        and: "a warning was logged"
        testLog.warns.any { it.contains("setLevel") }
    }

    // ---- BP25: setNightLight passes raw string to API as 'night_light' field ----

    def "BP25: setNightLight('ON') sends night_light:'on' (lowercase), not 'ON' (BP25 regression guard)"() {
        // Pre-fix: mode passed raw → API receives "ON" which the VeSync cloud may reject.
        // Post-fix: m = mode.toLowerCase() → API receives "on".
        when:
        driver.setNightLight("ON")

        then: "setNightLight API call made with night_light:'on' (lowercase)"
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "on"
    }

    def "BP25: setNightLight('DIM') sends night_light:'dim' (lowercase) (BP25 regression guard)"() {
        when:
        driver.setNightLight("DIM")

        then: "setNightLight API call made with night_light:'dim' (lowercase)"
        def req = testParent.allRequests.find { it.method == "setNightLight" }
        req != null
        req.data.night_light == "dim"
    }

    // -------------------------------------------------------------------------
    // Bug Pattern #26 (D2 variant): comparison-operator implicit coercion on
    // blank Rule Machine slot — setLevel("") and setLevel("abc") must not throw
    // -------------------------------------------------------------------------

    def "BP26: setLevel('') does not throw on empty-string input from Rule Machine (Core 200S Light)"() {
        // Before D2 fix, `level < 10` on a blank-slot "" string threw GroovyCastException
        // (swallowed silently by the Hubitat sandbox — no log entry, no effect).
        // After D2 fix: safeIntArg("", 0, 0, 100) returns 0; 0 < 10 → setNightLight("off").
        given:
        settings.descriptionTextEnable = false

        when: "setLevel called with empty string (Rule Machine blank slot)"
        driver.setLevel("")

        then: "no exception thrown"
        noExceptionThrown()

        and: "no error logged"
        testLog.errors.isEmpty()
    }

    def "BP26: setLevel('abc') does not throw on non-numeric string (Core 200S Light)"() {
        given:
        settings.descriptionTextEnable = false

        when: "setLevel called with non-numeric string"
        driver.setLevel("abc")

        then: "no exception thrown"
        noExceptionThrown()

        and: "no error logged"
        testLog.errors.isEmpty()
    }
}
