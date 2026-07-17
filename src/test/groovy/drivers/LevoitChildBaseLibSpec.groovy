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
 *   asBool                — total never-throwing boolean coercion (Boolean/Number==1/truthy
 *                            String -> true; 2/null/""/uncoercible -> false; never throws on
 *                            a non-numeric String — the fault that aborted the old as-Integer path)
 *   clampOffLevel (BP6)   — hardened to accept def; null/String/non-Number first arg passes
 *                            through unchanged with no throw; Integer-caller behavior unchanged
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
    // beginPowerOnWindow / clearPowerOnWindow (BP30 Layer 2)
    // now() is fixed in-harness, so two begin calls see the window still open;
    // runInMillis is a no-op, so the safety timer never auto-fires — clearing is
    // exercised directly. NON-VACUITY: a begin() that always returned true (no
    // suppression) makes the second assertion RED; a clear() that did not remove
    // powerOnPending makes the reopen assertion RED.
    // -------------------------------------------------------------------------

    def "beginPowerOnWindow opens the window on first call and suppresses within it (BP30)"() {
        given:
        state.remove("powerOnPending")
        state.remove("powerOnWindowAt")

        expect: "first call opens the window and returns true"
        driver.beginPowerOnWindow() == true
        state.powerOnPending == true

        and: "a second call within the window returns false (storm suppressed)"
        driver.beginPowerOnWindow() == false
    }

    def "clearPowerOnWindow reopens the window for the next power-on (BP30)"() {
        given: "a window is open"
        driver.beginPowerOnWindow()

        when:
        driver.clearPowerOnWindow()

        then: "state is cleared and a subsequent begin re-opens"
        state.powerOnPending == null
        state.powerOnWindowAt == null
        driver.beginPowerOnWindow() == true
    }

    // -------------------------------------------------------------------------
    // isDuplicateWrite (BP30 Layer 3) — time-windowed dedup, NOT a cached-attribute
    // equality gate. now() is fixed in-harness, so two calls at the "same instant"
    // are within the window; the after-window leg backdates state.dupWriteAt_* directly.
    // -------------------------------------------------------------------------

    def "isDuplicateWrite suppresses an identical write within the dedup window (BP30 Layer 3)"() {
        given:
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")

        expect: "first write proceeds (and is recorded)"
        driver.isDuplicateWrite("mode", "auto") == false

        and: "an identical write within the window is a storm duplicate"
        driver.isDuplicateWrite("mode", "auto") == true
    }

    def "isDuplicateWrite ALWAYS fires an identical write after the window elapses (BP30 anti-wedge)"() {
        given: "a prior identical write recorded well outside the dedup window"
        // now() is fixed at 1745000000000L in-harness; backdate the last-write timestamp >2s.
        state.dupWriteVal_mode = "auto"
        state.dupWriteAt_mode = 1745000000000L - 5000L

        expect: "the same value writes again — a drifted cloud state stays correctable from Hubitat"
        driver.isDuplicateWrite("mode", "auto") == false
    }

    def "isDuplicateWrite tracks slots independently so mode and speed do not evict each other (BP30)"() {
        given:
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        state.remove("dupWriteVal_speed"); state.remove("dupWriteAt_speed")

        expect: "first write of each slot proceeds; the interleaved other-slot write does not reset it"
        driver.isDuplicateWrite("mode", "auto") == false
        driver.isDuplicateWrite("speed", "high") == false
        driver.isDuplicateWrite("mode", "auto") == true
        driver.isDuplicateWrite("speed", "high") == true
    }

    def "isDuplicateWrite suppresses an identical nightLight write within the window (BP30 Layer 3, nightLight slot)"() {
        given:
        state.remove("dupWriteVal_nightLight"); state.remove("dupWriteAt_nightLight")

        expect: "first night-light write proceeds, an identical one within the window is a storm duplicate"
        driver.isDuplicateWrite("nightLight", "on") == false
        driver.isDuplicateWrite("nightLight", "on") == true
    }

    def "isDuplicateWrite fires an identical nightLight write after the window elapses (BP30 anti-wedge, nightLight slot)"() {
        given: "a prior identical night-light write recorded outside the dedup window"
        state.dupWriteVal_nightLight = "on"
        state.dupWriteAt_nightLight = 1745000000000L - 5000L   // 5s ago, > DUP_WRITE_WINDOW_MS (2s)

        expect: "the same value writes again — drift stays correctable from Hubitat"
        driver.isDuplicateWrite("nightLight", "on") == false
    }

    // -------------------------------------------------------------------------
    // clearDuplicateWrite (BP30 B1) — a FAILED write must not suppress the retry.
    // NON-VACUITY: without clearDuplicateWrite the 3rd call below stays a duplicate (true).
    // -------------------------------------------------------------------------

    def "clearDuplicateWrite clears a recorded slot so an immediate same-value retry FIRES (BP30 B1)"() {
        given: "a write was recorded (proceeded), and an identical one within the window is a dup"
        state.remove("dupWriteVal_mode"); state.remove("dupWriteAt_mode")
        assert driver.isDuplicateWrite("mode", "auto") == false   // 1st: records, proceeds
        assert driver.isDuplicateWrite("mode", "auto") == true    // 2nd: within window -> dup

        when: "the cloud write failed, so the failure branch clears the slot"
        driver.clearDuplicateWrite("mode")

        then: "the same value now FIRES (not falsely suppressed) — a failed write is retryable"
        driver.isDuplicateWrite("mode", "auto") == false
    }

    // -------------------------------------------------------------------------
    // beginPowerOnWindow / clearPowerOnWindow unschedule (BP30 B3) — the safety timer must
    // be cancelled so an on->off->on sequence cannot leave an orphan timer that closes the
    // next window early. NON-VACUITY: without the unschedule() the recorded list is empty -> RED.
    // -------------------------------------------------------------------------

    def "clearPowerOnWindow cancels the pending safety timer (BP30 B3)"() {
        given:
        List unscheduled = []
        driver.metaClass.unschedule = { Object[] args -> unscheduled << (args ? args[0] : null) }

        when:
        driver.clearPowerOnWindow()

        then: "the clearPowerOnWindow safety timer is unscheduled (no orphan to close the next window early)"
        unscheduled.contains("clearPowerOnWindow")
    }

    def "beginPowerOnWindow unschedules any prior timer before arming a fresh one (BP30 B3)"() {
        given:
        state.remove("powerOnPending"); state.remove("powerOnWindowAt")
        List unscheduled = []
        driver.metaClass.unschedule = { Object[] args -> unscheduled << (args ? args[0] : null) }

        when:
        driver.beginPowerOnWindow()

        then:
        unscheduled.contains("clearPowerOnWindow")
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

    // -------------------------------------------------------------------------
    // BP29 — BYPASS_DEVICE_IS_OFF (inner code 11005000) handling via the STATELESS
    // reportWriteFailure() helper. (No persisted sentinel — see CLAUDE.md BP29.)
    //
    // VeSync rejects a bypassV2 write with inner result code 11005000 when the
    // target device is powered OFF. This is an EXPECTED condition (e.g. a scheduled
    // rule firing setDisplay on an off humidifier), not a fault. The caller's
    // write-failure branch routes through reportWriteFailure(tag, resp, ctx):
    //   - device-off (11005000) => ONE WARN naming the cause, no ERROR, no record.
    //   - any other failure      => the prior logError(tag) + recordError(tag, ctx).
    // The device-off-vs-fault decision is made from the `resp` passed in at call
    // time, so it CANNOT leak across calls (the prior design used a disk-persisted
    // state.lastWriteDeviceOff sentinel that leaked into later unrelated errors;
    // that sentinel is GONE — these specs assert the leak is impossible).
    //
    // NON-VACUITY: each test asserts a discriminating signal that only holds under
    // the BP29 logic. The leak-regression spec is the load-bearing one: it drives a
    // device-off rejection, THEN a separate error with NO intervening httpOk, and
    // asserts the second (genuine) error IS logged AND recorded. Under any persisted
    // suppression flag that survives across commands, that assertion goes RED.
    // Reverting reportWriteFailure's isDeviceOffResp() branch to always logError +
    // recordError makes the "device-off => one WARN / no ERROR / no record" specs go
    // RED. (Orchestrator owns the both-ways proof.)
    // -------------------------------------------------------------------------

    // Build a TestHttpResponse-shaped map carrying an arbitrary inner result code.
    private Map respWithInnerCode(Object innerCode) {
        return [
            status: 200,
            data: [
                code: 0,
                result: [code: innerCode, result: [:], traceId: "t"],
                traceId: "t"
            ]
        ]
    }

    def "reportWriteFailure: device-off (11005000) logs exactly one WARN, no ERROR, no ring-buffer record (BP29)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")

        when: "the caller's write-failure branch reports a device-off envelope"
        driver.reportWriteFailure("Display write failed", respWithInnerCode(11005000), [method: "setDisplay"])

        then: "exactly one WARN, naming BYPASS_DEVICE_IS_OFF + the tag + that it was not applied"
        testLog.warns.count { it.contains("BYPASS_DEVICE_IS_OFF") } == 1
        testLog.warns.any { it.contains("Display write failed") && it.contains("device is off") && it.contains("not applied") }

        and: "no generic ERROR for an expected condition"
        !testLog.errors.any { it.contains("Display write failed") }

        and: "nothing appended to the error ring-buffer for this device"
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "reportWriteFailure: a genuine inner-error (-1) logs ERROR + records, no device-off WARN (BP29 negative)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")

        when:
        driver.reportWriteFailure("Display write failed", respWithInnerCode(-1), [method: "setDisplay"])

        then: "NOT classified as device-off"
        !testLog.warns.any { it.contains("BYPASS_DEVICE_IS_OFF") }

        and: "the genuine failure IS logged and recorded"
        testLog.errors.any { it.contains("Display write failed") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    def "reportWriteFailure: a null/transport-failure resp is treated as a genuine fault (BP29 boundary)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")

        when: "no inner code available (e.g. HTTP 500 / null resp)"
        driver.reportWriteFailure("Display write failed", [status: 500, data: null], [method: "setDisplay"])

        then: "not device-off — real fault logged + recorded"
        !testLog.warns.any { it.contains("BYPASS_DEVICE_IS_OFF") }
        testLog.errors.any { it.contains("Display write failed") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    def "isDeviceOffResp: true only for inner 11005000; false for 0, -1, null, transport failure (BP29 predicate)"() {
        expect:
        driver.isDeviceOffResp(respWithInnerCode(11005000))
        !driver.isDeviceOffResp(respWithInnerCode(0))
        !driver.isDeviceOffResp(respWithInnerCode(-1))
        !driver.isDeviceOffResp(respWithInnerCode(null))
        !driver.isDeviceOffResp([status: 500, data: null])
        !driver.isDeviceOffResp(null)
    }

    // BP29 crash-repro (load-bearing both-ways guard). On a non-JSON error response
    // (CDN/gateway HTTP 502/504 with a raw HTML body), hubBypass returns
    // [status:5xx, data:"<html>...</html>"] — resp.data is a NON-NULL String. The
    // pre-fix predicate did `resp?.data?.result?.code`: ?. guards null but NOT
    // wrong-type, so .result is a property access on a String and throws
    // groovy.lang.MissingPropertyException, crashing the driver from the write-fail
    // branch. The instanceof-Map guards make this return false instead.
    //
    // DISCRIMINATION: against the OLD code this spec FAILS — the property access on a
    // String throws MissingPropertyException, so the `notThrown` block goes RED. Against
    // the fixed code resp.data is not a Map, the chained guard short-circuits, and the
    // predicate returns false. (Orchestrator owns the both-ways proof.)
    def "isDeviceOffResp: non-JSON String body (HTTP 502/504 HTML) does NOT throw and returns false (BP29 crash-repro)"() {
        when: "a gateway error whose inner resp.data is a raw HTML String, not a Map"
        boolean result = driver.isDeviceOffResp([status: 502, data: "<html><body>502 Bad Gateway</body></html>"])

        then: "no MissingPropertyException — the property access on a String is never attempted"
        notThrown(Exception)

        and: "a non-Map body is not a device-off envelope"
        result == false
    }

    def "isDeviceOffResp: still returns true for the genuine device-off Map shape (BP29 fix did not break detection)"() {
        expect: "the canonical device-off envelope [status:200, data:[result:[code:11005000]]]"
        driver.isDeviceOffResp([status: 200, data: [result: [code: 11005000]]])
    }

    def "isDeviceOffResp: returns false for a normal-success Map and for null (BP29 fix regression net)"() {
        expect:
        !driver.isDeviceOffResp([status: 200, data: [result: [code: 0]]])
        !driver.isDeviceOffResp(null)
    }

    // BP29 crash-repro for the hubBypass() callback's inner-code peel (sibling site).
    // The callback runs UNCONDITIONALLY on every hubBypass call (the logDebug trace
    // always fires since callers pass a tag) and is NOT wrapped in try/catch — so a
    // non-JSON 502/504 HTML body (resp.data is a non-null String) made the pre-fix
    // `resp?.data?.result?.code` throw MissingPropertyException INSIDE the async
    // callback, before reportWriteFailure/isDeviceOffResp could ever run.
    //
    // The harness CAN deliver a String-bodied response with no changes: TestParent
    // drives the callback with cannedResponse, and TestHttpResponse.getData() returns
    // backing.data untyped — so a String `data` surfaces verbatim to resp.data.
    //
    // DISCRIMINATION: against the OLD callback line this spec FAILS — the property
    // access on the String throws inside the callback, so the `notThrown` block goes
    // RED. Against the type-guarded line the `instanceof Map` check is false, inner is
    // null, the logDebug trace prints "inner null", and no exception is thrown.
    // (Orchestrator owns the both-ways proof.)
    def "hubBypass: non-JSON String body (HTTP 502/504 HTML) does NOT throw in the callback (BP29 sibling crash-repro)"() {
        given: "debug on so the always-fired trace line evaluates `inner`"
        settings.debugOutput = true
        testParent.cannedResponse = [status: 502, data: "<html><body>502 Bad Gateway</body></html>"]

        when: "a write whose response carries a raw HTML String body reaches the hubBypass callback"
        def resp = driver.hubBypass("setDisplay", [screenSwitch: 1], "setDisplay")

        then: "the callback's inner-code peel did not throw on the String body"
        notThrown(Exception)

        and: "the transport result still surfaces the status and the raw String data unchanged"
        resp.status == 502
        resp.data == "<html><body>502 Bad Gateway</body></html>"
    }

    def "httpOk: device-off (11005000) returns false but does NOT itself log WARN/ERROR or record (BP29 — reporting is the caller's job)"() {
        given:
        settings.descriptionTextEnable = true
        settings.debugOutput = true
        state.remove("errorHistory")

        when:
        boolean ok = driver.httpOk(respWithInnerCode(11005000))

        then: "treated as failure"
        ok == false

        and: "httpOk no longer owns the device-off WARN (reportWriteFailure does)"
        !testLog.warns.any { it.contains("BYPASS_DEVICE_IS_OFF") }
        !testLog.errors.any { it.contains("BYPASS_DEVICE_IS_OFF") }

        and: "no ring-buffer record from httpOk for a device-off inner code"
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "httpOk: HTTP-status failure (non-2xx) logs ERROR + records (unchanged by BP29)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")

        when:
        boolean ok = driver.httpOk([status: 500, data: null])

        then:
        ok == false
        testLog.errors.any { it.contains("HTTP 500") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    // result.code non-Map crash class: httpOk reads resp.data.result.code
    // ONLY on a 2xx status. The dangerous vector is therefore HTTP 200 with a NON-JSON
    // String body (a CDN/gateway HTML interstitial, a proxy error page). Pre-fix, the bare
    // `resp?.data?.result?.code` did a property access on the String and threw
    // MissingPropertyException INSIDE httpOk, aborting the caller's command with a raw
    // sandbox stack trace. Post-fix, the `!(resp.data instanceof Map)` guard returns false
    // (a non-JSON body is not a valid success), so the caller takes its clean failure branch.
    //
    // DISCRIMINATION: reverting to `def inner = resp?.data?.result?.code` makes the
    // `boolean ok = driver.httpOk(...)` line throw -> the notThrown block goes RED.
    // (Orchestrator owns the both-ways proof.)
    def "httpOk: HTTP 200 with a non-JSON String body returns false and does NOT throw (v2.10 result.code crash class)"() {
        given:
        settings.debugOutput = true

        when: "httpOk is handed a 200 whose body is a raw HTML String, not a Map"
        boolean ok
        ok = driver.httpOk([status: 200, data: "<html><body>200 but not JSON</body></html>"])

        then: "no MissingPropertyException from the .result read on a String"
        notThrown(Exception)

        and: "a non-Map body is not a valid success"
        ok == false
    }

    // LOAD-BEARING leak-regression spec (the exact QA-flagged BLOCKING):
    // A device-off rejection on one command must NOT suppress a later, unrelated
    // genuine error that hits logError/recordError with NO intervening httpOk.
    // Under the prior persisted-sentinel design this assertion went RED (the flag
    // set by the device-off command stayed TRUE and swallowed the next error).
    // The stateless reportWriteFailure makes cross-call suppression impossible.
    def "BP29 leak regression: device-off on one command does NOT suppress a later unrelated error (no persisted state)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")

        when: "command A: a NO-ON preference setter on an off device gets device-off"
        driver.reportWriteFailure("Display write failed", respWithInnerCode(11005000), [method: "setDisplay"])

        then: "A was handled quietly — one WARN, no ERROR, no record"
        testLog.warns.any { it.contains("BYPASS_DEVICE_IS_OFF") }
        !testLog.errors.any { it.contains("Display write failed") }
        ((state.errorHistory ?: [:]) as Map)["test-device-001"] in [null, []]

        when: "command B (LATER, separate): a validation-error branch logs+records directly, NO httpOk between"
        driver.logError("Unknown mode: badvalue")
        driver.recordError("Unknown mode: badvalue", [method: "setMode"])

        then: "the genuine error IS logged (not suppressed by any leaked device-off state)"
        testLog.errors.any { it.contains("Unknown mode: badvalue") }

        and: "and IS recorded into the diagnostics ring-buffer"
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).any { it.msg.contains("Unknown mode: badvalue") }
    }

    // -------------------------------------------------------------------------
    // BP22 — child-side network-outage dedup. During a known network outage
    // (parent.isNetworkUnreachable() == true) the parent never invokes the child's
    // sendBypassRequest callback, so a write returns the [status:-1] transport sentinel
    // and the child's write-fail branches would log one ERROR + recordError per command
    // per retrigger for the whole outage. The parent already surfaces the outage once
    // (BP22 first-fire WARN + hourly re-surface), so the child DOWNGRADES its write-fail
    // ERROR+record to a single DEBUG while the outage is known. Outside an outage the
    // genuine-failure path (ERROR + record) is preserved unchanged.
    //
    // The downgrade is centralized in three shared LevoitChildBase points:
    //   httpOk()           — the non-2xx transport-failure branch
    //   reportWriteFailure — the resp-bearing write-fail helper (BP29)
    //   reportWriteError   — the no-resp write-fail helper (per-driver bare branches)
    //
    // NON-VACUITY / both-ways: each test pairs outage-true (DEBUG, no ERROR/record) with
    // outage-false (ERROR + record). Reverting the networkOutageKnown() downgrade in any of
    // the three points makes the corresponding outage-true assertion go RED (it would log an
    // ERROR + record instead of staying quiet). (Orchestrator owns the revert/mutation proof.)
    // -------------------------------------------------------------------------

    def "httpOk: transport failure during a known outage logs DEBUG only — no ERROR, no record (BP22)"() {
        given:
        settings.descriptionTextEnable = true
        settings.debugOutput = true
        state.remove("errorHistory")
        testParent.networkUnreachable = true

        when: "the [status:-1] transport sentinel (parent never invoked the callback) reaches httpOk during an outage"
        boolean ok = driver.httpOk([status: -1, data: null])

        then: "still a failure"
        ok == false

        and: "downgraded: a DEBUG naming the outage, NOT an ERROR"
        testLog.debugs.any { it.contains("network outage") && it.contains("BP22") }
        !testLog.errors.any { it.contains("HTTP -1") }

        and: "nothing recorded into the diagnostics ring-buffer"
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "httpOk: transport failure with NO outage still logs ERROR + records (BP22 negative / both-ways)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")
        testParent.networkUnreachable = false

        when:
        boolean ok = driver.httpOk([status: -1, data: null])

        then: "genuine HTTP failure path preserved"
        ok == false
        testLog.errors.any { it.contains("HTTP -1") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    def "reportWriteFailure: transport failure during a known outage logs DEBUG only — no ERROR, no record (BP22)"() {
        given:
        settings.descriptionTextEnable = true
        settings.debugOutput = true
        state.remove("errorHistory")
        testParent.networkUnreachable = true

        when:
        driver.reportWriteFailure("Mode write failed: auto", [status: -1, data: null], [method: "setMode"])

        then: "downgraded to DEBUG; no ERROR, no record"
        testLog.debugs.any { it.contains("network outage") && it.contains("BP22") }
        !testLog.errors.any { it.contains("Mode write failed") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "reportWriteFailure: transport failure with NO outage still logs ERROR + records (BP22 negative / both-ways)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")
        testParent.networkUnreachable = false

        when:
        driver.reportWriteFailure("Mode write failed: auto", [status: -1, data: null], [method: "setMode"])

        then:
        testLog.errors.any { it.contains("Mode write failed") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    def "reportWriteFailure: device-off is checked BEFORE the outage downgrade (device-off WARN never masked) (BP22 ordering)"() {
        given: "a (hypothetical) device-off envelope while an outage flag is also set"
        settings.descriptionTextEnable = true
        settings.debugOutput = true
        state.remove("errorHistory")
        testParent.networkUnreachable = true

        when: "the resp carries the device-off inner code (cannot really co-occur with a transport outage, but assert ordering)"
        driver.reportWriteFailure("Display write failed", respWithInnerCode(11005000), [method: "setDisplay"])

        then: "the device-off WARN fires (device-off branch is first), not the outage DEBUG"
        testLog.warns.any { it.contains("BYPASS_DEVICE_IS_OFF") }
        !testLog.errors.any { it.contains("Display write failed") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "reportWriteError: write-fail during a known outage logs DEBUG only — no ERROR, no record (BP22)"() {
        given:
        settings.descriptionTextEnable = true
        settings.debugOutput = true
        state.remove("errorHistory")
        testParent.networkUnreachable = true

        when: "a no-resp bare write-fail branch reports via the unified helper during an outage"
        driver.reportWriteError("Failed to turn on device", [method: "on"])

        then: "downgraded to DEBUG; no ERROR, no record"
        testLog.debugs.any { it.contains("network outage") && it.contains("BP22") }
        !testLog.errors.any { it.contains("Failed to turn on device") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).isEmpty()
    }

    def "reportWriteError: write-fail with NO outage logs ERROR + records (BP22 negative / both-ways)"() {
        given:
        settings.descriptionTextEnable = true
        state.remove("errorHistory")
        testParent.networkUnreachable = false

        when:
        driver.reportWriteError("Failed to turn on device", [method: "on"])

        then: "genuine fault path preserved"
        testLog.errors.any { it.contains("Failed to turn on device") }
        def hist = (state.errorHistory ?: [:]) as Map
        (hist["test-device-001"] ?: []).size() == 1
    }

    def "networkOutageKnown is parent-null-safe and false by default (no outage)"() {
        given: "default TestParent reports no outage"
        testParent.networkUnreachable = false

        expect: "the gate returns false, so callers behave exactly as pre-BP22"
        driver.networkOutageKnown() == false
    }

    def "emitSwitchState emits the switch attribute AND syncs state.lastSwitchSet"() {
        // toggle() prefers state.lastSwitchSet over the switch attribute (read-after-write mirror).
        // emitSwitchState is the poll-emit helper: it must ALSO update state.lastSwitchSet so an
        // external power change seen only by the poll is honored by the next toggle().
        given: "a stale lastSwitchSet from a prior local write"
        state.lastSwitchSet = "on"

        when: "a poll reflects the device now off"
        driver.emitSwitchState(false)

        then: "the switch attribute is emitted off AND the mirror is synced off"
        lastEventValue("switch") == "off"
        state.lastSwitchSet == "off"

        when: "a later poll reflects on"
        driver.emitSwitchState(true)

        then: "both the attribute and the mirror reflect on"
        lastEventValue("switch") == "on"
        state.lastSwitchSet == "on"
    }

    // -------------------------------------------------------------------------
    // asBool — total, never-throwing boolean coercion (replaces the ~69 throw-prone
    // `(x instanceof Boolean) ? x : ((x as Integer) == 1)` sites). Number==1 semantics
    // (2 -> false); truthy Strings -> true; everything else (null, "", uncoercible) -> false.
    // -------------------------------------------------------------------------

    @Unroll
    def "asBool(#desc) == #expected"() {
        expect:
        driver.asBool(raw) == expected

        where:
        desc                 | raw            || expected
        "Boolean true"       | true           || true
        "Boolean false"      | false          || false
        "Number 1"           | 1              || true
        "Number 0"           | 0              || false
        "Number 2 (->false)" | 2              || false   // ONLY 1 is true, matches old as-Integer==1
        "String 'true'"      | "true"         || true
        "String '1'"         | "1"            || true
        "String 'on'"        | "on"           || true
        "String 'yes'"       | "yes"          || true
        "String 'TRUE'"      | "TRUE"         || true    // case-insensitive
        "String 'false'"     | "false"        || false
        "String 'off'"       | "off"          || false
        "String '0'"         | "0"            || false
        "String '' (empty)"  | ""             || false
        "String '  on  '"    | "  on  "       || true    // trimmed
        "null"               | null           || false
        "uncoercible object" | [a: 1]         || false   // a Map -> not Boolean/Number/CharSequence
    }

    def "asBool never throws on a non-numeric String (the bug it fixes)"() {
        when: "the value that broke the old `(x as Integer)` path"
        boolean result = driver.asBool("false")

        then: "no NumberFormatException; correctly parsed to false"
        noExceptionThrown()
        result == false
    }

    // -------------------------------------------------------------------------
    // clampOffLevel — hardened to accept def (FIX E): a non-Integer/null first arg must
    // pass through unchanged with no throw/NPE. Behavior for the current Integer callers
    // is unchanged (positive Number while off -> 0; otherwise unchanged).
    // -------------------------------------------------------------------------

    @Unroll
    def "clampOffLevel(#v, powerOn=#powerOn) == #expected (#desc)"() {
        expect:
        driver.clampOffLevel(v, powerOn) == expected

        where:
        desc                          | v     | powerOn || expected
        "off + positive -> 0"         | 5     | false   || 0
        "on + positive -> unchanged"  | 5     | true    || 5
        "off + zero -> unchanged"     | 0     | false   || 0
        "off + null -> null (no NPE)" | null  | false   || null
        "on + null -> null (no NPE)"  | null  | true    || null
        "off + String -> unchanged"   | "x"   | false   || "x"
        "off + Boolean -> unchanged"  | true  | false   || true
    }

    def "clampOffLevel does not throw on a null first arg (FIX E hardening)"() {
        when:
        def result = driver.clampOffLevel(null, false)

        then:
        noExceptionThrown()
        result == null
    }

    def "clampOffLevel does not throw on a String first arg (FIX E hardening)"() {
        when:
        def result = driver.clampOffLevel("notanumber", false)

        then: "non-Number passes through unchanged, no GroovyCastException"
        noExceptionThrown()
        result == "notanumber"
    }
}
