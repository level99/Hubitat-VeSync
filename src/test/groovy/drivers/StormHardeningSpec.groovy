package drivers

import support.HubitatSpec

/**
 * Bug Pattern #30 — command-storm hardening regression coverage.
 *
 * A burst of overlapping on() commands (Rule Machine double-fire, dashboard taps,
 * automations) must collapse to ONE effective power sequence instead of firing the
 * full power+speed+mode burst N times and colliding in flight ("...write failed" +
 * dependent-child starvation). The mechanism:
 *   Layer 1 — singleThreaded:true in definition() (serialization; asserted by RULE48 lint).
 *   Layer 2 — beginPowerOnWindow()/clearPowerOnWindow() async-window guard on on()/off().
 *   Layer 3 — time-windowed duplicate-write suppression (isDuplicateWrite) on setMode/setSpeed
 *             (bypassed during power-on; distinct from the pre-existing setDisplay/setChildLock
 *             C3 equality gates the catalog calls out separately).
 *
 * NON-VACUITY (both-ways): reverting the Layer-2 guard in on() makes the "exactly ONE
 * setSwitch" assertions go RED (a storm re-issues setSwitch N times). Removing the
 * clearPowerOnWindow() call in off() makes the off->on recovery assertion go RED (the
 * window stays open and the post-off on() is suppressed). The harness always returns
 * 200-OK so it cannot itself reproduce the cloud collision; the load-bearing discriminator
 * is the request COUNT, not the absence of a "write failed" log.
 *
 * The unit-level mechanics of beginPowerOnWindow/clearPowerOnWindow (window open/suppress/
 * reopen) live in LevoitChildBaseLibSpec; these specs prove the on()/off() wiring per family.
 */
class StormHardeningSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        // Core line — the family the real-world incident was observed on (Core 200S).
        "Drivers/Levoit/LevoitCore200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    private int setSwitchOnCount() {
        testParent.allRequests.findAll {
            it.method == "setSwitch" && (it.data?.enabled == true || it.data?.powerSwitch == 1)
        }.size()
    }

    def "a failed on() clears the power-on window so an immediate retry FIRES (BP30 B2)"() {
        given: "device off; window clear"
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("powerOnPending"); state.remove("powerOnWindowAt"); state.remove("turningOn")

        when: "the first on() gets a FAILED power-on response (handlePower false -> clearPowerOnWindow)"
        testParent.cannedResponse = support.TestParent.httpErrorResponse(500)
        driver.on()
        testParent.allRequests.clear()

        and: "an immediate retry (default-OK) is attempted within the same instant (now() fixed)"
        driver.on()

        then: "the retry FIRED a fresh power-on — a failed on() does NOT hold the window for 4s"
        setSwitchOnCount() == 1
    }

    def "a storm of on() commands fires exactly ONE power sequence (BP30 Layer 2)"() {
        given: "device is off and no power-on window is open"
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("powerOnPending")
        state.remove("powerOnWindowAt")
        state.remove("turningOn")
        testParent.allRequests.clear()

        when: "three on() invocations arrive within the async window (now() is fixed in-harness)"
        driver.on()
        driver.on()
        driver.on()

        then: "only the first on() issued a power-on; the rest were suppressed by the window guard"
        setSwitchOnCount() == 1

        and: "no spurious write-failed errors (always-OK harness; guards against a regression that logs them)"
        !testLog.errors.any { it.toLowerCase().contains("write failed") }
    }

    def "the power-on window auto-clears so off() then on() fires a fresh sequence (BP30 Layer 2)"() {
        given: "a power-on storm has opened the window"
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("powerOnPending")
        state.remove("turningOn")
        driver.on()
        driver.on()      // suppressed — window open

        when: "the user turns the device off (clears the window) then on again"
        driver.off()
        testParent.allRequests.clear()
        driver.on()

        then: "the post-off on() is NOT suppressed — a fresh power-on sequence fires"
        setSwitchOnCount() == 1
    }

    def "beginPowerOnWindow is idempotent within the window and reopens after clear (BP30)"() {
        given:
        state.remove("powerOnPending")
        state.remove("powerOnWindowAt")

        expect: "first call opens the window"
        driver.beginPowerOnWindow() == true

        and: "a second call within the window is suppressed"
        driver.beginPowerOnWindow() == false

        when: "the window is cleared (safety timer / off())"
        driver.clearPowerOnWindow()

        then: "the window reopens for the next power-on"
        driver.beginPowerOnWindow() == true
        state.powerOnPending == true
    }

    def "a storm of identical setMode collapses to ONE write; the same value after the window writes again (BP30 Layer 3)"() {
        given: "device is on (so setMode does not trigger a power-on burst) and the dedup slot is clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn")
        state.remove("powerOnPending")
        state.remove("dupWriteVal_mode")
        state.remove("dupWriteAt_mode")
        testParent.allRequests.clear()

        when: "three identical setMode commands arrive within the dedup window (now() fixed in-harness)"
        driver.setMode("sleep")
        driver.setMode("sleep")
        driver.setMode("sleep")

        then: "only ONE mode write reached the parent — the burst duplicates were dropped"
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.size() == 1

        when: "the dedup window elapses and the same mode is requested again (out-of-band correction)"
        state.dupWriteAt_mode = (state.dupWriteAt_mode as Long) - 5000L
        driver.setMode("sleep")

        then: "a second mode write fires — an identical re-request after the window is never blocked"
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.size() == 2
    }

    def "an in-flight power-on is NOT blocked by the dedup window — establishment write always fires (BP30 Layer 3 exclusion)"() {
        given: "device on; a recent identical mode write is on record (would otherwise be a dup); a power-on is in flight"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn")
        state.dupWriteVal_mode = "sleep"
        state.dupWriteAt_mode = 1745000000000L     // now() == this in-harness -> within the dedup window
        state.powerOnPending = true                // simulate an in-flight power-on (Vital configureOnState shape)
        testParent.allRequests.clear()

        when: "an establishment-style setMode fires the same value while the power-on window is open"
        driver.setMode("sleep")

        then: "the dedup is bypassed during power-on — the establishment write reaches the cloud (anti-suppression)"
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.size() == 1
    }
}

/**
 * Family-symmetric BP30 coverage for the V2 (Vital) line, whose on() is genuinely async
 * (handlePower then runInMillis(500, configureOnState)) — a different shape from the Core
 * line's synchronous burst, so it needs its own storm proof. setSwitch payload here is the
 * V2 shape {powerSwitch:1, switchIdx:0}.
 */
class StormHardeningVitalSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitVital200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    private int setSwitchOnCount() {
        testParent.allRequests.findAll {
            it.method == "setSwitch" && it.data?.powerSwitch == 1
        }.size()
    }

    def "a storm of on() commands fires exactly ONE power-on (BP30 Layer 2, Vital)"() {
        given:
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("powerOnPending")
        state.remove("powerOnWindowAt")
        state.remove("turningOn")
        testParent.allRequests.clear()

        when:
        driver.on()
        driver.on()
        driver.on()

        then: "only one setSwitch(powerSwitch=1) reached the parent"
        setSwitchOnCount() == 1

        and:
        !testLog.errors.any { it.toLowerCase().contains("write failed") }
    }

    def "off() clears the window so a later on() is not suppressed (BP30 Layer 2, Vital)"() {
        given:
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("powerOnPending")
        state.remove("turningOn")
        driver.on()
        driver.on()

        when:
        driver.off()
        testParent.allRequests.clear()
        driver.on()

        then:
        setSwitchOnCount() == 1
    }
}

/**
 * BP30 Layer 3 (nightLight slot) coverage for the Core 200S Light (night-light child) — the
 * literal victim device in the incident. Its on()/off() are SINGLE idempotent setNightLight
 * cloud writes with no multi-step power-on establishment, so L2 (the power-on window guard)
 * does not fit; the storm vector is identical-write repeats, suppressed by the time-windowed
 * isDuplicateWrite dedup on the "nightLight" slot. on()/off() funnel through setNightLight, so
 * an on() burst is covered too — via the L3 dedup, NOT a power-on window. (L1 still applies —
 * singleThreaded:true.)
 */
class StormHardeningLightSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        "Drivers/Levoit/LevoitCore200S Light.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    private int nightLightWriteCount() {
        testParent.allRequests.findAll { it.method == "setNightLight" }.size()
    }

    def "a storm of identical setNightLight writes collapses to ONE; the same value after the window fires again (BP30 Layer 3, nightLight slot)"() {
        given:
        state.remove("dupWriteVal_nightLight")
        state.remove("dupWriteAt_nightLight")
        testParent.allRequests.clear()

        when: "three identical night-light writes arrive within the dedup window (now() fixed in-harness)"
        driver.setNightLight("on")
        driver.setNightLight("on")
        driver.setNightLight("on")

        then: "only ONE reached the parent — the burst duplicates were dropped"
        nightLightWriteCount() == 1

        when: "the dedup window elapses and the same value is requested again"
        state.dupWriteAt_nightLight = (state.dupWriteAt_nightLight as Long) - 5000L
        driver.setNightLight("on")

        then: "it fires again — an out-of-window re-request is never blocked (drift-correctable)"
        nightLightWriteCount() == 2
    }

    def "different night-light values both fire within the window — dedup is recency-of-value, not an equality gate (BP30 Layer 3)"() {
        given:
        state.remove("dupWriteVal_nightLight")
        state.remove("dupWriteAt_nightLight")
        testParent.allRequests.clear()

        when: "two DIFFERENT values within the window"
        driver.setNightLight("off")
        driver.setNightLight("on")

        then: "both fire — only identical repeats are suppressed"
        nightLightWriteCount() == 2
    }
}
