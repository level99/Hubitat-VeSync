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
}
