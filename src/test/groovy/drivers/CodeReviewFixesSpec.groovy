package drivers

import support.HubitatSpec
import support.TestParent

/**
 * Both-ways regression coverage for the v2.10 /code-review fixes (A1/A2/B1).
 * B2 lives in StormHardeningSpec (Core on()-failure window clear); B3 + the
 * clearDuplicateWrite helper unit both-ways live in LevoitChildBaseLibSpec.
 *
 * A1 — BP30 dedup must run BEFORE an early-return delegation so the slot reflects the NEW
 * effective value; otherwise auto->manual->auto (low->sleep->low) within the window falsely
 * suppresses the 3rd write. NON-VACUITY: with the dedup AFTER the delegation (pre-fix order)
 * the 3rd write is suppressed -> the "size() == 2" assertions go RED.
 */
class EverestAirCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitEverestAir.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "setMode auto->manual->auto within the window FIRES the 3rd write (BP30 A1)"() {
        given: "device on; mode/fanSpeed slots clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        state.remove("dupWriteVal_fanSpeed"); state.remove("dupWriteAt_fanSpeed")
        testParent.allRequests.clear()

        when: "auto, then manual (delegates to setFanSpeed), then auto again — all within the window"
        driver.setMode("auto")
        driver.setMode("manual")
        driver.setMode("auto")

        then: "two setPurifierMode(auto) writes fired — the 3rd was NOT falsely suppressed"
        testParent.allRequests.findAll { it.method == "setPurifierMode" && it.data.workMode == "auto" }.size() == 2
    }

    def "setMode failure then same-value retry FIRES (BP30 B1 clear-on-failure)"() {
        given: "device on; mode slot clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")

        when: "the first setMode('auto') gets a FAILED cloud response (httpOk false -> clearDuplicateWrite)"
        testParent.cannedResponse = TestParent.httpErrorResponse(500)
        driver.setMode("auto")
        testParent.allRequests.clear()

        and: "an immediate retry of the same value (now a default-OK response) fires"
        driver.setMode("auto")

        then: "the retry FIRED — the failed write cleared the dedup slot, so it was not suppressed"
        testParent.allRequests.findAll { it.method == "setPurifierMode" && it.data.workMode == "auto" }.size() == 1
    }

    def "setMode manual delegation FAILURE clears the mode slot so a retry FIRES (A1-delegation, setFanSpeed)"() {
        given: "device on; mode + fanSpeed slots clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        state.remove("dupWriteVal_fanSpeed"); state.remove("dupWriteAt_fanSpeed")

        when: "first setMode('manual') — the delegated setFanSpeed write FAILS (httpOk false)"
        testParent.cannedResponse = TestParent.httpErrorResponse(500)
        driver.setMode("manual")   // records mode='manual', delegates to setFanSpeed (fails) -> must clear 'mode'
        testParent.allRequests.clear()

        and: "an immediate same-value retry (default-OK)"
        driver.setMode("manual")

        then: "the retry's delegated setFanSpeed (setLevel) FIRED — the failed delegation cleared the 'mode' slot"
        testParent.allRequests.findAll { it.method == "setLevel" }.size() == 1
    }
}

class SproutAirCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitSproutAir.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "setMode auto->manual->auto within the window FIRES the 3rd write (BP30 A1)"() {
        given:
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        state.remove("dupWriteVal_fanSpeed"); state.remove("dupWriteAt_fanSpeed")
        testParent.allRequests.clear()

        when:
        driver.setMode("auto")
        driver.setMode("manual")
        driver.setMode("auto")

        then:
        testParent.allRequests.findAll { it.method == "setPurifierMode" && it.data.workMode == "auto" }.size() == 2
    }
}

class VitalCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitVital200S.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "setSpeed low->sleep->low within the window FIRES the 3rd write (BP30 A1)"() {
        given: "device on; speed slot clear (mapSpeedToInteger('low') == 2)"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_speed"); state.remove("dupWriteAt_speed")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        testParent.allRequests.clear()

        when: "low (setLevel), then sleep (delegates to setMode->setPurifierMode), then low again"
        driver.setSpeed("low")
        driver.setSpeed("sleep")
        driver.setSpeed("low")

        then: "two setLevel(manualSpeedLevel:2) writes fired — the 3rd 'low' was NOT suppressed"
        testParent.allRequests.findAll { it.method == "setLevel" && it.data.manualSpeedLevel == 2 }.size() == 2
    }

    def "setSpeed sleep delegation FAILURE clears the speed slot so a retry FIRES (A1-delegation, setMode)"() {
        given: "device on; speed + mode slots clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_speed"); state.remove("dupWriteAt_speed")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")

        when: "first setSpeed('sleep') — the delegated setMode('sleep') write FAILS"
        testParent.cannedResponse = TestParent.httpErrorResponse(500)
        driver.setSpeed("sleep")   // records speed='sleep', delegates to setMode('sleep') (fails) -> must clear 'speed'
        testParent.allRequests.clear()

        and: "an immediate same-value retry (default-OK)"
        driver.setSpeed("sleep")

        then: "the retry's delegated setMode('sleep') FIRED setPurifierMode — the failed delegation cleared the 'speed' slot"
        testParent.allRequests.findAll { it.method == "setPurifierMode" && it.data.workMode == "sleep" }.size() == 1
    }
}

/**
 * A2 — child_lock/display must be coerced via asBool so a String "false"/"0" maps to "off",
 * not raw Groovy truthiness (a non-empty String "false" is truthy -> would wrongly emit "on").
 * NON-VACUITY: reverting asBool() -> `status.result.child_lock ? "on" : "off"` makes the
 * "false"->"off" assertion go RED (emits "on").
 */
class Core200SCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitCore200S.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "child_lock and display string 'false' map to 'off' via asBool (BP30 A2)"() {
        when: "a status reports child_lock/display as the STRING 'false' (a non-numeric flag value)"
        driver.update([result: [enabled: true, mode: "manual", level: 1, child_lock: "false", display: "false"]], null)

        then: "asBool coerces 'false' to off (NOT raw truthiness, which would emit 'on')"
        lastEventValue("childLock") == "off"
        lastEventValue("display") == "off"
    }

    def "child_lock numeric 1 maps to 'on', 0 maps to 'off' via asBool (BP30 A2)"() {
        when:
        driver.update([result: [enabled: true, mode: "manual", level: 1, child_lock: 1, display: 0]], null)

        then:
        lastEventValue("childLock") == "on"
        lastEventValue("display") == "off"
    }
}

/**
 * A1-delegation for the humidifier sendModeRequest path: setMode("auto") records the "mode"
 * slot then delegates to sendModeRequest (try-canonical-then-fallback). If BOTH payload variants
 * fail, sendModeRequest now returns false and setMode clears "mode" so a retry FIRES.
 */
class LV600SCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitLV600S.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "setMode auto delegation FAILURE clears the mode slot so a retry FIRES (A1-delegation, sendModeRequest)"() {
        given: "device on; mode slot + firmware variant clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        state.remove("firmwareVariant")

        when: "first setMode('auto') — BOTH sendModeRequest payload variants FAIL (try + fallback)"
        testParent.requestResponses = [TestParent.httpErrorResponse(500), TestParent.httpErrorResponse(500)]
        driver.setMode("auto")   // records mode='auto', delegates to sendModeRequest (both fail) -> must clear 'mode'
        testParent.allRequests.clear()

        and: "an immediate same-value retry (default-OK)"
        driver.setMode("auto")

        then: "the retry's delegated sendModeRequest FIRED setHumidityMode — the failed delegation cleared the 'mode' slot"
        testParent.allRequests.findAll { it.method == "setHumidityMode" }.size() >= 1
    }
}

/**
 * A1-delegation for the Core.setSpeed auto/sleep branches (the additional gap found during the
 * audit, beyond the named set): setSpeed("auto") records the "speed" slot then delegates to
 * setMode. If setMode fails, "speed" must be cleared so a retry FIRES. Core 300S supports auto.
 */
class Core300SCodeReviewSpec extends HubitatSpec {

    @Override String driverSourcePath() { "Drivers/Levoit/LevoitCore300S.groovy" }
    @Override Map defaultSettings() { [descriptionTextEnable: true, debugOutput: false] }

    def "setSpeed auto delegation FAILURE clears the speed slot so a retry FIRES (A1-delegation, Core.setSpeed)"() {
        given: "device on; speed + mode slots clear"
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn"); state.remove("powerOnPending")
        state.remove("dupWriteVal_speed"); state.remove("dupWriteAt_speed")
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")

        when: "first setSpeed('auto') — the delegated setMode('auto') write FAILS"
        testParent.cannedResponse = TestParent.httpErrorResponse(500)
        driver.setSpeed("auto")   // records speed='auto', delegates to setMode('auto') (fails) -> must clear 'speed'
        testParent.allRequests.clear()

        and: "an immediate same-value retry (default-OK)"
        driver.setSpeed("auto")

        then: "the retry's delegated setMode('auto') FIRED setPurifierMode — the failed delegation cleared the 'speed' slot"
        testParent.allRequests.findAll { it.method == "setPurifierMode" }.size() == 1
    }
}
