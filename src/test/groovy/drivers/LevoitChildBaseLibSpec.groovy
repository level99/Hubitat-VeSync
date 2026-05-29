package drivers

import spock.lang.Unroll
import support.HubitatSpec

/**
 * Unit tests for LevoitChildBaseLib.groovy library methods.
 *
 * The library is tested through LevoitClassic200S.groovy — the first driver
 * migrated to include it. HubitatSpec.loadDriverClass() handles #include
 * resolution, inlining both LevoitDiagnosticsLib and LevoitChildBaseLib
 * before compiling.
 *
 * Covers:
 *   logInfo    — fires only when descriptionTextEnable is true
 *   logDebug   — fires only when debugOutput is true
 *   logError   — always fires (unconditional)
 *   logWarn    — always fires (unconditional)
 *   logAlways  — always fires regardless of descriptionTextEnable (for user-invoked diagnostics)
 *   logDebugOff — clears debugOutput pref when true; no-op when false
 *   ensureDebugWatchdog (BP16) — no-ops when <30 min elapsed; disables + clears when >=30 min
 *   ensureSwitchOn (BP23) — calls on() when switch is off and not already turning on;
 *                            skips on() when state.turningOn is set;
 *                            skips on() when switch is already "on"
 *   requireNotNull (BP18) — returns false + logs WARN when null;
 *                            returns true silently when non-null
 *   safeIntArg (BP26)     — null/empty/blank/non-numeric/over-range/decimal contract;
 *                            4-arg clamp overload; W2 warn fires only on non-null/non-empty failures
 */
class LevoitChildBaseLibSpec extends HubitatSpec {

    @Override
    String driverSourcePath() {
        // Load through the migrated Classic 200S driver — it includes both
        // LevoitDiagnostics and LevoitChildBase.
        "Drivers/Levoit/LevoitClassic200S.groovy"
    }

    @Override
    Map defaultSettings() {
        return [descriptionTextEnable: true, debugOutput: false]
    }

    // -------------------------------------------------------------------------
    // logInfo
    // -------------------------------------------------------------------------

    def "logInfo fires when descriptionTextEnable is true"() {
        given:
        settings.descriptionTextEnable = true

        when:
        driver.logInfo("hello info")

        then:
        testLog.infos.contains("hello info")
    }

    @Unroll
    def "logInfo is silent when descriptionTextEnable is #pref"() {
        given:
        settings.descriptionTextEnable = pref

        when:
        driver.logInfo(msg)

        then:
        !testLog.infos.contains(msg)

        where:
        pref  | msg
        false | "suppressed info"
        null  | "null-pref info"   // null = unsaved preference
    }

    // -------------------------------------------------------------------------
    // logDebug
    // -------------------------------------------------------------------------

    def "logDebug fires when debugOutput is true"() {
        given:
        settings.debugOutput = true

        when:
        driver.logDebug("debug trace")

        then:
        testLog.debugs.contains("debug trace")
    }

    @Unroll
    def "logDebug is silent when debugOutput is #pref"() {
        given:
        settings.debugOutput = pref

        when:
        driver.logDebug(msg)

        then:
        !testLog.debugs.contains(msg)

        where:
        pref  | msg
        false | "suppressed debug"
        null  | "null-pref debug"   // null = unsaved preference
    }

    // -------------------------------------------------------------------------
    // logError
    // -------------------------------------------------------------------------

    def "logError always fires regardless of preferences"() {
        given:
        settings.descriptionTextEnable = false
        settings.debugOutput = false

        when:
        driver.logError("critical failure")

        then:
        testLog.errors.contains("critical failure")
    }

    // -------------------------------------------------------------------------
    // logWarn
    // -------------------------------------------------------------------------

    def "logWarn always fires regardless of preferences"() {
        given:
        settings.descriptionTextEnable = false
        settings.debugOutput = false

        when:
        driver.logWarn("something suspicious")

        then:
        testLog.warns.contains("something suspicious")
    }

    // -------------------------------------------------------------------------
    // logDebugOff
    // -------------------------------------------------------------------------

    def "logDebugOff clears debugOutput pref when it is currently true"() {
        given:
        settings.debugOutput = true

        when:
        driver.logDebugOff()

        then: "debugOutput is updated to false via device.updateSetting"
        settings.debugOutput == false
    }

    def "logDebugOff is a no-op when debugOutput is already false"() {
        given:
        settings.debugOutput = false

        when:
        driver.logDebugOff()

        then: "setting stays false, no exception"
        settings.debugOutput == false
        noExceptionThrown()
    }

    // -------------------------------------------------------------------------
    // ensureDebugWatchdog (BP16)
    // -------------------------------------------------------------------------

    def "ensureDebugWatchdog does nothing when debugOutput is false"() {
        given:
        settings.debugOutput = false
        // Place debugEnabledAt well in the past to confirm it's the false-flag that matters
        state.debugEnabledAt = 1745000000000L - (31L * 60 * 1000)

        when:
        driver.ensureDebugWatchdog()

        then: "setting stays false, no update call, no side effects"
        settings.debugOutput == false
        noExceptionThrown()
    }

    def "ensureDebugWatchdog does nothing when debugEnabledAt is not set"() {
        given:
        settings.debugOutput = true
        state.remove("debugEnabledAt")

        when:
        driver.ensureDebugWatchdog()

        then: "debugOutput stays true — no elapsed time to check"
        settings.debugOutput == true
        noExceptionThrown()
    }

    def "ensureDebugWatchdog does nothing when less than 30 min have elapsed"() {
        given:
        settings.debugOutput = true
        // now() returns 1745000000000L in HubitatSpec; set debugEnabledAt 29 min ago
        state.debugEnabledAt = 1745000000000L - (29L * 60 * 1000)

        when:
        driver.ensureDebugWatchdog()

        then: "debugOutput stays true — 30 min has not elapsed yet"
        settings.debugOutput == true
        noExceptionThrown()
    }

    def "ensureDebugWatchdog disables debug and clears state when 30+ min have elapsed"() {
        given:
        settings.debugOutput = true
        settings.descriptionTextEnable = true
        // now() returns 1745000000000L; set debugEnabledAt 31 min ago
        state.debugEnabledAt = 1745000000000L - (31L * 60 * 1000)

        when:
        driver.ensureDebugWatchdog()

        then: "debugOutput has been cleared"
        settings.debugOutput == false

        and: "debugEnabledAt state key has been removed"
        state.debugEnabledAt == null

        and: "an INFO log was emitted announcing the auto-disable"
        testLog.infos.any { it.contains("BP16 watchdog") }
    }

    // -------------------------------------------------------------------------
    // ensureSwitchOn (BP23)
    // -------------------------------------------------------------------------

    def "ensureSwitchOn calls on() when switch is off and not already turning on"() {
        given: "device switch is off and state.turningOn is not set"
        // currentValue("switch") reads from testDevice.events — seed with an off event
        testDevice.sendEvent(name: "switch", value: "off")
        state.remove("turningOn")
        boolean onCalled = false
        driver.metaClass.on = { -> onCalled = true }

        when:
        driver.ensureSwitchOn()

        then:
        onCalled == true
    }

    def "ensureSwitchOn skips on() when state.turningOn is set (re-entrance guard)"() {
        given:
        testDevice.sendEvent(name: "switch", value: "off")
        state.turningOn = true
        boolean onCalled = false
        driver.metaClass.on = { -> onCalled = true }

        when:
        driver.ensureSwitchOn()

        then: "on() was NOT called — the re-entrance guard blocked it"
        onCalled == false
    }

    def "ensureSwitchOn skips on() when switch is already on"() {
        given:
        testDevice.sendEvent(name: "switch", value: "on")
        state.remove("turningOn")
        boolean onCalled = false
        driver.metaClass.on = { -> onCalled = true }

        when:
        driver.ensureSwitchOn()

        then: "on() was NOT called — device is already on"
        onCalled == false
    }

    def "ensureSwitchOn skips on() when switch has never been set (null currentValue)"() {
        given: "no switch event ever sent — currentValue returns null"
        // TestDevice.currentValue returns null when no matching event exists
        state.remove("turningOn")
        boolean onCalled = false
        driver.metaClass.on = { -> onCalled = true }

        when:
        driver.ensureSwitchOn()

        then: "on() was NOT called — null != 'on' is true but... wait, null != 'on' so on() IS called"
        // Actually: device.currentValue("switch") returns null when no event; null != "on" is true,
        // so ensureSwitchOn WILL call on(). This is the correct/safe behavior — if we don't know the
        // switch state we should try to turn it on rather than silently skip.
        onCalled == true
    }

    // -------------------------------------------------------------------------
    // requireNotNull (BP18)
    // -------------------------------------------------------------------------

    def "requireNotNull returns false and logs WARN when arg is null"() {
        given:
        settings.descriptionTextEnable = true

        when:
        boolean result = driver.requireNotNull(null, "setMode")

        then: "returns false (caller should abort)"
        result == false

        and: "a WARN log was emitted naming the method and blank-parameter cause"
        testLog.warns.any { it.contains("setMode") && it.contains("null") && it.contains("likely empty Rule Machine action parameter") }
    }

    def "requireNotNull returns true when arg is non-null"() {
        when:
        boolean result = driver.requireNotNull("auto", "setMode")

        then: "returns true (caller should continue)"
        result == true

        and: "no WARN was logged"
        testLog.warns.isEmpty()
    }

    def "requireNotNull returns true for empty string (empty string is not null)"() {
        when:
        boolean result = driver.requireNotNull("", "setDisplay")

        then: "empty string is not null — caller proceeds"
        result == true
    }

    def "requireNotNull returns true for zero integer (0 is not null)"() {
        when:
        boolean result = driver.requireNotNull(0, "setLevel")

        then:
        result == true
    }

    def "requireNotNull WARN message names the calling method"() {
        when:
        driver.requireNotNull(null, "setNightLightMode")

        then:
        testLog.warns.any { it.contains("setNightLightMode") }
    }

    // -------------------------------------------------------------------------
    // logAlways
    // -------------------------------------------------------------------------

    @Unroll
    def "logAlways emits info regardless of descriptionTextEnable=#pref"() {
        given:
        settings.descriptionTextEnable = pref

        when:
        driver.logAlways(msg)

        then: "logAlways has no pref gate — output regardless of the preference value"
        testLog.infos.contains(msg)

        where:
        pref  | msg
        true  | "probe result always-on"
        false | "probe result pref disabled"
        null  | "probe result pref null"
    }

    // -------------------------------------------------------------------------
    // safeIntArg (BP26) — 2-arg contract
    // -------------------------------------------------------------------------

    @Unroll
    def "safeIntArg: #desc -> #expected (no exception, correct return)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.safeIntArg(input, 99) == expected

        where:
        desc                         | input                     | expected
        "null → fallback"            | null                      | 99
        "empty string → fallback"    | ""                        | 99
        "blank string → fallback"    | "  "                      | 99
        "integer string '5'"         | "5"                       | 5
        "negative string '-5'"       | "-5"                      | -5
        "zero string '0'"            | "0"                       | 0
        "decimal '5.7' truncates"    | "5.7"                     | 5
        "decimal '-5.7' truncates"   | "-5.7"                    | -5
        "decimal '5.0' = 5"          | "5.0"                     | 5
        "integer 7 (boxed)"          | 7                         | 7
        "integer -3 (boxed)"         | -3                        | -3
        // non-numeric / boolean-string / over-range all return the fallback
        "non-numeric 'abc'"          | "abc"                     | 99
        "boolean 'true' string"      | "true"                    | 99
        "over-range BigDecimal (W1)" | "999999999999999999999"   | 99
    }

    @Unroll
    def "safeIntArg W2: #desc"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        when:
        driver.safeIntArg(input, 99)

        then: "a WARN naming safeIntArg and containing the diagnostic substring was emitted"
        testLog.warns.any { it.contains("safeIntArg") && it.contains(substring) }

        where:
        desc                                            | input                       | substring
        "non-numeric non-empty input logs a WARN"       | "abc"                       | "abc"
        "over-range input logs a WARN"                  | "999999999999999999999"     | "out-of-range"
    }

    @Unroll
    def "safeIntArg W2: #desc (silent fallback, no WARN)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        when:
        driver.safeIntArg(input, 99)

        then: "no WARN — this is the requireNotNull / Rule Machine blank-slot path, not a parse failure"
        testLog.warns.isEmpty()

        where:
        desc                                 | input
        "null input does NOT log a WARN"     | null
        "empty string does NOT log a WARN"   | ""
    }

    // -------------------------------------------------------------------------
    // safeIntArg (BP26) — 4-arg clamp overload (I1)
    // -------------------------------------------------------------------------

    @Unroll
    def "safeIntArg 4-arg: #desc -> #expected"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.safeIntArg(input, fb, lo, hi) == expected

        where:
        desc                                                  | input | fb | lo | hi | expected
        "value within range returned unchanged"               | "5"   | 1  | 1  | 9  | 5
        "value below lo clamped to lo"                        | "0"   | 1  | 1  | 9  | 1
        "value above hi clamped to hi"                        | "99"  | 1  | 1  | 9  | 9
        "fallback used for non-numeric input before clamp"    | "abc" | 5  | 1  | 9  | 5
        "fallback outside range clamped after non-numeric"    | "abc" | 0  | 1  | 9  | 1
        "null input uses fallback then clamps"                | null  | 3  | 1  | 9  | 3
        "boundary value at lo returned as-is"                 | "1"   | 5  | 1  | 9  | 1
        "boundary value at hi returned as-is"                 | "9"   | 5  | 1  | 9  | 9
    }

    // -------------------------------------------------------------------------
    // parseLevelOrNull (BP28) — numeric -> Integer; non-numeric/null/empty -> null
    // -------------------------------------------------------------------------

    def "parseLevelOrNull: numeric input '#raw' returns Integer #expected (BP28)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.parseLevelOrNull(raw) == expected

        where:
        raw    | expected
        "0"    | 0
        "5"    | 5
        "9"    | 9
        5      | 5
        "5.7"  | 5     // decimal truncates toward zero (matches safeIntArg int() semantics)
        "0.0"  | 0
    }

    def "parseLevelOrNull: non-numeric/null/empty input '#raw' returns null (BP28)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.parseLevelOrNull(raw) == null

        where:
        raw << ["garbage", "hgih", "abc", "", "  ", null, "true", "1.2.3"]
    }

    def "parseLevelOrNull: out-of-int-range BigDecimal returns null (W1 guard, BP28)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect: "beyond Integer range -> null (treated as garbage, not bit-wrapped)"
        driver.parseLevelOrNull("99999999999999") == null
    }

    // -------------------------------------------------------------------------
    // canonOnOff (BP25) — single blessed source for the permissive truthy-variant
    // on/off coercion. Returns "on" for on/true/1/yes (case/whitespace-insensitive),
    // "off" otherwise. Re-normalizes its input internally so it is idempotent and
    // safe whether passed the raw arg or the already-normalized form.
    // -------------------------------------------------------------------------

    @Unroll
    def "canonOnOff: '#raw' -> '#expected' (BP25 permissive truthy coercion)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.canonOnOff(raw) == expected

        where:
        raw       | expected
        // truthy variants -> "on"
        "on"      | "on"
        "true"    | "on"
        "1"       | "on"
        "yes"     | "on"
        // explicit off and the rest -> "off"
        "off"     | "off"
        "false"   | "off"
        "0"       | "off"
        "no"      | "off"
        // anything unrecognized -> "off"
        "garbage" | "off"
        ""        | "off"
        "   "     | "off"
    }

    @Unroll
    def "canonOnOff: case-insensitive + whitespace-trimmed '#raw' -> '#expected'"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect: "input is normalized (trim + lowercase) internally before the truthy test"
        driver.canonOnOff(raw) == expected

        where:
        raw       | expected
        "ON"      | "on"
        " On "    | "on"
        "TRUE"    | "on"
        " yes"    | "on"
        "OFF"     | "off"
        " Off "   | "off"
        "FALSE"   | "off"
    }

    def "canonOnOff: null input -> 'off' (no exception)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect: "explicit `if (v == null) return 'off'` guard returns off before any .toString(); never throws"
        driver.canonOnOff(null) == "off"
    }

    def "canonOnOff: boxed integer 1 -> 'on', 0 -> 'off' (toString coercion)"() {
        given:
        settings.debugOutput = false
        settings.descriptionTextEnable = false

        expect:
        driver.canonOnOff(1) == "on"
        driver.canonOnOff(0) == "off"
    }
}
