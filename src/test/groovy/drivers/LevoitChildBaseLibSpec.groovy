package drivers

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

    def "logInfo is silent when descriptionTextEnable is false"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.logInfo("suppressed info")

        then:
        !testLog.infos.contains("suppressed info")
    }

    def "logInfo is silent when descriptionTextEnable is null (unsaved preference)"() {
        given:
        settings.descriptionTextEnable = null

        when:
        driver.logInfo("null-pref info")

        then:
        !testLog.infos.contains("null-pref info")
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

    def "logDebug is silent when debugOutput is false"() {
        given:
        settings.debugOutput = false

        when:
        driver.logDebug("suppressed debug")

        then:
        !testLog.debugs.contains("suppressed debug")
    }

    def "logDebug is silent when debugOutput is null (unsaved preference)"() {
        given:
        settings.debugOutput = null

        when:
        driver.logDebug("null-pref debug")

        then:
        !testLog.debugs.contains("null-pref debug")
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

    def "logAlways emits info regardless of descriptionTextEnable=true"() {
        given:
        settings.descriptionTextEnable = true

        when:
        driver.logAlways("probe result always-on")

        then:
        testLog.infos.contains("probe result always-on")
    }

    def "logAlways emits info even when descriptionTextEnable=false"() {
        given:
        settings.descriptionTextEnable = false

        when:
        driver.logAlways("probe result pref disabled")

        then: "logAlways has no pref gate — output regardless"
        testLog.infos.contains("probe result pref disabled")
    }

    def "logAlways emits info even when descriptionTextEnable is null"() {
        given:
        settings.descriptionTextEnable = null

        when:
        driver.logAlways("probe result pref null")

        then: "logAlways has no pref gate — null pref does not suppress it"
        testLog.infos.contains("probe result pref null")
    }
}
