/*

MIT License

Copyright (c) 2021-2023 Niklas Gustafsson (original upstream framework, v1.0-v1.5)
Copyright (c) 2025-2026 Dan Cox (community fork — Vital 200S support, connection pool fix, humidifier method-branch fix, v1.6+)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

// History:
//
// 2026-05-01: v2.4  Bug Pattern #22 — HTTP-error log spam and poll-cycle stalls during
//                  network outages eliminated.
//                  - isNetworkException() classifies 8 exception classes (4 JDK + 4 Apache
//                    HttpClient) via cause-chain walk (depth 10) — covers sandbox wrappers.
//                  - First network error of an outage: one-time WARN + state.networkUnreachableSince
//                    set. Subsequent errors: DEBUG only (silent in normal logs).
//                  - Dual circuit-breaker: (1) top-level in updateDevices() skips the entire
//                    poll cycle during a known outage (prevents parallel-child stalls); (2)
//                    per-call in sendBypassRequest() skips individual httpPost() for command-
//                    triggered calls outside polling. Both use the same 5-minute probe interval.
//                    Coordination via state.networkProbeInFlight flag: top-level sets it when
//                    firing a recovery probe so per-call breaker doesn't re-block the probe.
//                    Cleared in a try/finally in updateDevices().
//                  - New emitNetworkWarnIfDue() helper: hourly WARN re-surface while unreachable.
//                  - Recovery: INFO "reachable again after Xh Ym" + all state fields cleared
//                    on first successful httpPost() completion.
//                  - Non-network exceptions keep existing logError path.
//                  - New state: state.networkUnreachableSince (Long), state.lastNetworkWarnAt (Long),
//                    state.lastNetworkProbeAt (Long), state.networkProbeInFlight (Boolean).
// 2026-05-01: v2.4  Bug Pattern #21 — bounded self-heal backoff for chronically-offline devices.
//                  - ensurePollHealth() now caps self-heal Resync attempts per device at 3.
//                    After 3 failed attempts the device is marked offline in state
//                    (state.deviceOfflineSince[dni]), further self-heal triggers are suppressed,
//                    and the child receives a sendEvent(online:"false") for dashboards/RM.
//                  - On first successful poll after an offline period the state is cleared and
//                    the child receives sendEvent(online:"true") with recovery duration in INFO log.
//                  - Log refactored from per-poll ERROR spam to tiered DEBUG/INFO/WARN:
//                      counts 1-4 AND not offline  -> DEBUG "N consecutive empty"
//                      count == 5 (first BP17 fire) -> INFO  "may be offline, self-healing"
//                      attempts 2-3                 -> DEBUG "self-heal attempt N"
//                      3rd-attempt offline mark     -> INFO  "marked offline, suppressing noise"
//                      while offline, hourly        -> WARN  "has been offline for Nh Mm"
//                      while offline, sub-hourly    -> DEBUG "still empty (offline since X)"
//                      first recovery               -> INFO  "back online after Nh Mm"
//                  - Hourly offline WARN emitted at top of updateDevices() by
//                    emitOfflineWarnsIfDue() — iterates state.deviceOfflineSince.
//                  - New helpers: markChildOffline(dni), markChildOnline(dni),
//                    formatOfflineDuration(sinceMillis), emitOfflineWarnsIfDue().
//                  - Per-child "online" attribute added to all child drivers (excludes
//                    LevoitCore200S Light, LevoitGeneric, Notification Tile, VeSyncIntegrationVirtual).
// 2026-04-27: v2.2.1 Bug fixes.
//                  - Bug 1: safeAddChildDevice() helper wraps every addChildDevice()
//                    call in getDevices() with UnknownDeviceTypeException catch.
//                    A missing optional driver no longer crashes the entire discovery
//                    loop; subsequent devices continue to be added. INFO log includes
//                    an install URL so the user knows exactly which driver to install.
//                    Core 200S Light specifically: a missing Light driver is treated as
//                    non-fatal — the main Core 200S purifier is still added.
//                  - Bug 2: resp?.msg -> resp?.hasProperty('msg') ? resp.msg : ''
//                    in updateDevices() error path. resp?.msg only null-guards against
//                    a null resp, but HttpResponseDecorator has no 'msg' property so
//                    the access still throws MissingPropertyException when resp is
//                    non-null. hasProperty() check eliminates hourly ERROR bursts.
//                  - Bug 3: Removed stale "reboot your hub after driver code changes"
//                    preferences banner. Advice was relevant on early Hubitat platforms
//                    but obsolete on current versions (2.3+). The connection-pool-shutdown
//                    retry logic added in v2.0 handles transient HTTP failures
//                    transparently without a hub reboot.
//                  - Bug 4: Missing-driver INFO log dedup. When N devices share a missing
//                    driver, safeAddChildDevice() now logs exactly once per unique driver
//                    per Resync Equipment call (not once per device). state.warnedMissingDrivers
//                    Set is initialized before the device-add loop and removed after.
//                  - Bug 5: Heartbeat log spam eliminated. "syncing" intermediate sendEvent
//                    removed (transient — emitted immediately before "synced" on every
//                    cycle, carries no distinct signal). "synced" sendEvent now uses
//                    isStateChange + descriptionText only on transitions (e.g. recovery
//                    from "not synced"); steady-state cycles update the attribute silently.
//                    ~240 INFO lines/hour eliminated on a healthy hub.
// 2026-04-27: v2.2+ EU region support (preview). Added deviceRegion preference (US/EU).
//                  - New getDeviceRegion() getter reads settings?.deviceRegion ?: "US".
//                  - New getApiHost() helper: EU -> smartapi.vesync.eu, US -> smartapi.vesync.com.
//                  - All three hardcoded API URLs now use getApiHost() dynamically.
//                  - Removed @Field static DEVICE_REGION constant; body field "deviceRegion"
//                    now reads from getDeviceRegion().
//                  - updated() detects region change (state.lastRegion diff), clears token +
//                    accountID, and forces re-login when region is switched.
//                  NOTE: EU support ships as preview -- no EU hardware live-verified.
//                  Community validation welcome on Hubitat thread.
// 2026-04-27: v2.2+ Dual 200S Humidifier preview driver wiring.
//                  - deviceType() switch: added Dual200S, LUH-D301S-WUSR, LUH-D301S-WJP,
//                    LUH-D301S-WEU, LUH-D301S-KEUR returning "D301S" dtype.
//                  - getDevices() addChildDevice loop: new D301S else-if branch with
//                    verify1 defensive validation, mapping to "Levoit Dual 200S Humidifier".
//                  - getDevices() newList tracking: extended cid-tracking conditional
//                    to include "D301S".
//                  - isLevoitClimateDevice(): LUH-D301S-* covered by existing LUH-* blanket;
//                    "Dual200S" literal added to the literal-names list (RULE22 parity).
// 2026-04-27: v2.2+ Debug auto-disable watchdog (Bug Pattern #16).
//                  - updated() now records state.debugEnabledAt = now() when debug is
//                    enabled, and clears it when debug is disabled.  The existing
//                    runIn(1800, "logDebugOff") stays for the happy path (no reboot).
//                  - Added ensureDebugWatchdog(): called at top of updateDevices()
//                    alongside ensurePollWatchdog().  If debugOutput has been true for
//                    >30 min (i.e. runIn timer evaporated across a hub reboot) the
//                    watchdog auto-disables debug and logs an INFO with the BP16 token.
//                  - Children each carry an identical ensureDebugWatchdog() called from
//                    update(status, nightLight) (or update(status) for Core200S Light).
//                  NOTE: state.debugEnabledAt is cleared by state.clear() in
//                  forceReinitialize(), so a force-reinit resets the 30-min clock.
// 2026-04-27: v2.2+ Bug fix -- setupPollSchedule() invalid Quartz cron for intervals >= 60s.
//                  The seconds-field cron "0/N * * * * ?" requires N < 60; for N >= 60
//                  Hubitat's Quartz scheduler rejects the expression silently and the
//                  poll cycle never arms. All README-recommended values >= 60s (60, 120,
//                  300) were broken. Fixed by branching on interval magnitude:
//                  - interval < 60  => "0/${interval} * * * * ?" (seconds-resolution, unchanged)
//                  - interval >= 60 => "0 */${interval/60} * * * ?" (minutes-resolution)
//                  Non-multiples of 60 (e.g. 90s) fire at floor(90/60)=1 min and emit
//                  a WARN so the user can correct. Recommended values (30/60/120/300)
//                  all divide evenly. Maintainer live-verify was on default 30s (< 60),
//                  so the breakage was not caught before release. (PR #4, Gemini review.)
// 2026-04-27: v2.2+ Hub-reboot poll-chain recovery (Bug Pattern #14).
//                  - Replaced recursive runIn() chain with schedule()-based cron job.
//                    schedule() persists across hub reboots; runIn() does not, causing
//                    polling to die permanently after every reboot until the user
//                    clicks Save Preferences.
//                  - Added setupPollSchedule() -- arms the cron, clears any prior
//                    runIn chain with unschedule("updateDevices").
//                  - Added ensurePollWatchdog() -- called at top of updateDevices()
//                    (after BP12 pref-seed) and at top of sendBypassRequest() (before
//                    HTTP); detects pre-v2.2 installs that still have the runIn-based
//                    chain, migrates them to schedule()-based automatically on first
//                    call. Idempotent once state.scheduleVersion == "2.2".
//                  - Removed the runIn((int)settings.refreshInterval, "updateDevices")
//                    call from inside updateDevices() (was the entire chain that broke
//                    on reboot). schedule() now drives the recurring tick.
//                  - initialize() now calls setupPollSchedule() so fresh installs
//                    start reboot-resilient.
//                  NOTE: subscribe(location, ...) is app-only API (Bug Pattern #15).
//                  The prior round's systemStart belt-and-braces layer was removed
//                  because it crashed in driver context. schedule() alone is sufficient
//                  -- Hubitat platform persists schedule() cron jobs across reboots.
// 2026-04-26: v2.1+ Phase 3 — parent driver wiring for 5 new v2.1 drivers.
//                  - deviceType() switch: added V100S (LAP-V102S-* family),
//                    A601S (Classic300S / LUH-A601S-*), O451S (LUH-O451S-* /
//                    LUH-O601S-* overlap), TOWERFAN (LTF-F422S-*), PEDESTALFAN
//                    (LPF-R432S-*) branches.
//                  - getDevices() addChildDevice loop: 5 new else-if branches
//                    with verify1 defensive validation pattern.
//                  - getDevices() newList tracking: extended the cid-tracking
//                    conditional to include the 5 new dtype strings.
//                  - updateDevices() method routing: Tower Fan -> getTowerFanStatus,
//                    Pedestal Fan -> getFanStatus; prior logic (Humidifier ->
//                    getHumidifierStatus, else -> getPurifierStatus) unchanged.
//                  - isLevoitClimateDevice() whitelist: added LTF- and LPF-
//                    prefixes; updated Javadoc comment to reflect v2.1 change.
// 2026-04-26: v2.0+ Added version field to every driver's definition() block.
//                  Hubitat UI now displays driver version in the device detail
//                  panel. /cut-release keeps this lockstep with the package
//                  version. No per-driver release cycles -- all drivers at the
//                  package version on every cut.
// 2026-04-26: v2.0+ Defensive addChildDevice validation + state.deviceList
//                  self-heal pass. Live-test discovered that when a child
//                  device is deleted and forceReinitialize triggered shortly
//                  after, Hubitat's platform may return a non-null phantom
//                  handle from addChildDevice (DNI mapping not fully purged
//                  yet). Parent now re-fetches via getChildDevice to verify
//                  the add succeeded; on failure logs an actionable error and
//                  skips updateDataValue. Also: updateDevices() now runs a
//                  self-heal pass at startup that removes state.deviceList
//                  entries with no corresponding child, so orphaned polls
//                  clean themselves up over time.
// 2026-04-26: v2.0+ Generic-driver dispatch filter -- only Levoit climate-class
//                  devices (LAP-/LEH-/LV- prefixes + literal Core200S/300S/400S/
//                  600S/Vital200S names) attach to the Generic driver. Non-Levoit
//                  devices on the same VeSync account (Etekcity plugs, Cosori,
//                  thermostats, etc.) are now skipped with an INFO log instead
//                  of getting a malfunctioning Generic child. Fan prefixes
//                  (LTF-/LPF-) intentionally excluded from v2.0; will be added
//                  in v2.1 alongside proper fan drivers.
// 2026-04-26: v2.0+ Defensive check fix in getDevices() — empty device list
//                  ([]) is a valid VeSync response (zero-device account), not an
//                  error. Changed `!resp.data.result.list` to `== null` so the
//                  success path completes for accounts with no devices.
// 2026-04-25: v2.0+ Parent-driver NIT fixes (Issues 2, 3, Pattern 2):
//                  - Issue 2: removed unused `result =` assignment in updateDevices()
//                    (was causing MissingPropertyException on every poll cycle)
//                  - Issue 3: getDevices() now retries with re-auth on token expiry
//                    (same effectiveClosure pattern as sendBypassRequest)
//                  - Pattern 2: extracted hardcoded API metadata to @Field constants
//                    (APP_VERSION, DEFAULT_TRACE_ID, DEFAULT_TIME_ZONE);
//                    timeZone now derived from hub location with fallback to DEFAULT_TIME_ZONE.
//                    NOTE: regional API routing (US→smartapi.vesync.com / EU→smartapi.vesync.eu)
//                    implemented in v2.2 via getApiHost() + deviceRegion preference.
//                    DEVICE_REGION @Field constant was removed in favour of getDeviceRegion().
// 2026-04-25: v2.0+ Token-expiry re-auth fix (Bug Pattern #13).
//                  - isAuthFailure() detects HTTP 401 and inner code -11001000 (token expired)
//                    and -11201000 (invalid credentials); also handles rare 4-digit variants
//                    -11001 and -11201 defensively
//                  - sendBypassRequest() now wraps response in effectiveClosure that auto-retries
//                    once after calling login() to refresh the token
//                  - state.reAuthInProgress flag (cleared in finally) prevents infinite loops
// 2026-04-25: v2.0 Community fork release (Dan Cox, level99/Hubitat-VeSync). Squashed:
//                  - Branch poll method by device type — humidifiers now use getHumidifierStatus
//                    (was using getPurifierStatus for all devices, silently failing for humidifiers)
//                  - Connection pool shutdown error retry logic (transient HTTP failures)
//                  - Vital 200S / 200S-P (LAP-V201S-*) device discovery support
//                  - Levoit Superior 6000S Humidifier (LEH-S601S-*) device discovery support
//                  - Defensive null-guard on dev lookup
// 2023-02-04: v1.5 Adding heartbeat event (Niklas Gustafsson, upstream)
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2026-04-30: v2.4  Bug Pattern #19 — existing-child configModule refresh on Resync.
//                  getDevices() else-branches (existing-child update path) previously
//                  refreshed only name, label, and deviceType; configModule, cid, and
//                  uuid were never updated. When VeSync changed a device's configModule
//                  server-side (e.g. firmware update), sendBypassRequest still read the
//                  stale value from the child data store, and every poll returned empty.
//                  The BP17 watchdog correctly fired and scheduled a Resync, but Resync
//                  hit the else-branch and did not propagate the fresh configModule, so
//                  polling continued to fail indefinitely. All 21 else-branches (20 equip1
//                  + 1 equip2 for Core200S Light) now update configModule, cid, and uuid
//                  before deviceType, matching the new-child if-branch pattern.
// 2026-04-29: v2.4  Phase 5 — per-device captureDiagnostics + error ring-buffer.
//                  - #include level99.LevoitDiagnostics: library provides
//                    recordError() ring-buffer (last 10 per device, FIFO), and
//                    captureDiagnostics() for the parent itself.
//                  - Added captureDiagnosticsFor(String childDni) — returns
//                    per-device parent-side context map for child diagnostic captures
//                    (consecutiveEmpty, lastPollMethod, configModule, last error).
//                  - updateDevices() now sets state.lastPollMethod[<dni>] before
//                    calling dev.update() so captureDiagnosticsFor can report it.
//                  - recordError() called alongside logError() at the 6 highest-value
//                    call sites (login failure, poll-null, connection-pool, re-auth).
//                  - Added attribute "diagnostics" + command "captureDiagnostics"
//                    to metadata.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

import java.security.MessageDigest
import groovy.transform.Field

#include level99.LevoitDiagnostics

// VeSync API metadata constants — centralised here so version/ID changes are one-line fixes.
// DO NOT change DEFAULT_TRACE_ID to a dynamic value — pyvesync uses "1634265366" verbatim
// and changing it risks triggering VeSync anti-bot detection.
@Field static final String APP_VERSION       = "2.5.1"
@Field static final String DEFAULT_TRACE_ID  = "1634265366"
@Field static final String DEFAULT_TIME_ZONE = "America/Los_Angeles"
@Field static final int    MAX_HEAL_ATTEMPTS = 3   // BP21: cap self-heal Resyncs before marking offline
// Regional routing is binary: US → smartapi.vesync.com, EU → smartapi.vesync.eu
// (per pyvesync const.py). Implemented in v2.2 via deviceRegion preference + getApiHost() helper.

// Poll-method routing is handled by deviceMethodFor(child) below.
// TYPENAME_TO_METHOD and DEFAULT_POLL_METHOD were removed in v2.3 (issue #3 AC #3):
// substring matching on typeName was fragile and untestable. Routing is now dtype-based
// via deviceType() + the dtype→method switch in deviceMethodFor().

metadata {
    definition(
        name: "VeSync Integration",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson (original); Dan Cox (fork: Vital 200S, Superior 6000S, parent fixes); elfege (contributor)",
        description: "Integrates Levoit air purifiers and humidifiers with Hubitat Elevation via VeSync cloud API",
        version: "2.3",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Actuator"
            attribute "heartbeat", "string"
            attribute "diagnostics", "string"

            command "captureDiagnostics"
        }

    preferences {
        input(name: "email", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Email Address</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Email Address</font>", defaultValue: "", required: true);
        input(name: "password", type: "password", title: "<font style='font-size:12px; color:#1a77c9'>Password</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Password</font>", defaultValue: "");
		input("refreshInterval", "number", title: "<font style='font-size:12px; color:#1a77c9'>Refresh Interval</font>", description: "<font style='font-size:12px; font-style: italic'>Poll VeSync status every N seconds</font>", required: true, defaultValue: 30)
        input("deviceRegion", "enum", title: "<font style='font-size:12px; color:#1a77c9'>VeSync API region</font>",
              description: "<font style='font-size:12px; font-style: italic'>US: smartapi.vesync.com (default). EU: smartapi.vesync.eu. Changing region clears stored auth token and forces re-login. EU support is preview — community validation welcome.</font>",
              options: ["US", "EU"], defaultValue: "US", required: true)
        input("descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true, required: false)
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
        input("verboseDebug", "bool", title: "Verbose API response logging? (Logs full response body of every VeSync API call. Useful for diagnosing 'VeSync changed an API field' issues. Sanitized automatically. Heavy on log buffer — leave off unless triaging.)", defaultValue: false, required: false)

        command "resyncEquipment"
        command "forceReinitialize", [[name: "Force reinitialization (use if connection errors persist after driver update)"]]

    }
}

def installed() {
	logDebug "Installed with settings: ${settings}"
    runIn(15, "initialize")
}

def updated() {
	logDebug "Updated with settings: ${settings}"

    // Region-change detection: if the user switched US <-> EU, cross-region tokens are invalid.
    // Clear stored auth credentials and force re-login via initialize() below.
    String newRegion = settings?.deviceRegion ?: "US"
    if (state.lastRegion != null && state.lastRegion != newRegion) {
        logInfo "VeSync API region changed from ${state.lastRegion} to ${newRegion} -- clearing stored token and forcing re-login"
        state.remove('token')
        state.remove('accountID')
    }
    state.lastRegion = newRegion
    state.driverVersion = "2.3"

    // Set flag to stop any running tasks from old driver instance
    state.driverReloading = true
    try {
        // Clear any existing schedules
        unschedule()

        // Brief pause to let old tasks see the flag
        pauseExecution(500)
    } finally {
        // Always clear reload flag — even if unschedule() or pauseExecution() throws
        state.remove('driverReloading')
    }

    // Delay initialization to allow HTTP connection pool to stabilize (15s for reliability)
	runIn(15, "initialize")

    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}

def uninstalled() {
    
    unschedule()

	logDebug "Uninstalled app"

	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}	
}

def initialize() {
	logDebug "initializing"

    // Arm the persistent cron schedule before login so that even a failed login
    // attempt still establishes the poll timer for when credentials are next corrected.
    setupPollSchedule()

    // Start the initialization chain: login → getDevices → updateDevices
    def loginSuccess = login()
    if (loginSuccess) {
        logDebug "Login successful, discovering devices..."
        getDevices()
    } else {
        logError "Login failed - check credentials and retry"
        recordError("Login failed - check credentials and retry", [site:"initialize"])
    }
}

/**
 * Arm (or re-arm) the persistent schedule()-based poll cron (Bug Pattern #14).
 *
 * schedule() registers a platform-level cron job that survives hub reboots.
 * unschedule("updateDevices") first clears any prior schedule() OR runIn() chain
 * registered for updateDevices -- this is the migration step that kills the legacy
 * runIn-based chain on pre-v2.2 installs.
 *
 * Cron syntax depends on interval magnitude (Quartz seconds field range is 0-59;
 * the seconds-form increment must be less than 60):
 *   interval < 60  -> seconds-resolution form (every N seconds in the seconds field)
 *   interval >= 60 -> minutes-resolution form (every N minutes in the minutes field,
 *                     where N = interval / 60).
 *                     Non-multiples of 60 (e.g. 90s) fire every floor(90/60)=1 min;
 *                     a WARN is emitted so the user can correct to a clean multiple.
 *                     Recommended values: 30, 60, 120, 300 (all divide evenly).
 * Minimum granularity is 1 second; any interval < 1 is clamped to 1.
 * See the method body below for the literal cron expressions.
 *
 * state.scheduleVersion is set to "2.2" after arming so ensurePollWatchdog()
 * can distinguish schedule()-armed installs from legacy runIn()-only installs.
 *
 * NOTE: subscribe(location, ...) is NOT available in driver context (Bug Pattern #15).
 * Do NOT add location-event subscriptions here or anywhere in this driver.
 */
private setupPollSchedule() {
    Integer interval = Math.max(1, (settings?.refreshInterval ?: 30) as Integer)
    unschedule("updateDevices")
    String cron
    if (interval < 60) {
        cron = "0/${interval} * * * * ?"
    } else {
        Integer minutes = (int)(interval / 60)
        if (interval % 60 != 0) {
            logWarn "VeSync Integration: refreshInterval ${interval}s is not a multiple of 60. " +
                    "Cron will fire every ${minutes} minute(s) (~${minutes * 60}s) instead. " +
                    "Set interval to 60, 120, or 300 for exact timing."
        }
        cron = "0 */${minutes} * * * ?"
    }
    schedule(cron, "updateDevices")
    state.scheduleVersion = "2.2"
    logDebug "Poll schedule armed: every ${interval}s via cron '${cron}' (schedule()-based, persists across reboots)"
}

/**
 * Self-heal watchdog -- detects pre-v2.2 installs and migrates them (Bug Pattern #14).
 *
 * Called at the top of updateDevices() (after BP12 pref-seed, before driverReloading guard)
 * and at the top of sendBypassRequest() (before HTTP -- catches the case where polling died
 * but a direct command is still sent, e.g. from a Rule that fires the device).
 *
 * Idempotent: once state.scheduleVersion == "2.2" the body is skipped in O(1).
 */
private ensurePollWatchdog() {
    if (state.scheduleVersion != "2.2") {
        logInfo "BP14 migration: switching from runIn-based to schedule()-based poll cycle"
        setupPollSchedule()
    }
}

/**
 * Debug auto-disable watchdog -- Bug Pattern #16.
 *
 * runIn(1800, "logDebugOff") in updated() is in-memory only and evaporates across
 * hub reboots.  settings.debugOutput persists, so after a reboot debug runs forever.
 * This watchdog detects that condition and self-heals within one poll cycle.
 *
 * Called at the top of updateDevices() alongside ensurePollWatchdog().
 * Idempotent: O(1) when debug is off or state.debugEnabledAt is absent.
 */
private void ensureDebugWatchdog() {
    if (settings?.debugOutput && state.debugEnabledAt) {
        Long elapsed = now() - (state.debugEnabledAt as Long)
        if (elapsed > 30 * 60 * 1000) {
            logInfo "BP16 watchdog: 30 min elapsed since debug enable; auto-disabling now (post-reboot self-heal)"
            device.updateSetting("debugOutput", [type:"bool", value:false])
            state.remove("debugEnabledAt")
        }
    }
}

/**
 * BP17 / BP21 poll-health watchdog.
 *
 * A stale configModule in state.deviceList (caused by a VeSync-side firmware update)
 * makes every bypassV2 status call return an empty result. BP17's fix triggers a full
 * getDevices() Resync at 5 consecutive empties to refresh configModule from the cloud.
 *
 * BP21 bounds that self-heal loop for chronically-offline devices (unplugged / off WiFi /
 * off VeSync cloud). If the underlying device is genuinely offline, the Resync succeeds
 * (VeSync cloud still knows the device) but the next 5 polls also fail, triggering another
 * Resync → infinite ~5min cycle + per-poll ERROR spam.
 *
 * Fix: cap self-heal attempts at 3 per device per session. After 3 failures:
 *   - Mark device offline in state (state.deviceOfflineSince[dni] = now())
 *   - Suppress further self-heal triggers until the next non-empty result
 *   - Emit sendEvent(online:"false") to the child for dashboards / Rule Machine
 *
 * Recovery: the first non-empty result (in the updateDevices() success branch) clears
 * state and emits sendEvent(online:"true"). See the updateDevices() empty/success
 * branches and markChildOffline()/markChildOnline() helpers.
 *
 * state.selfHealAttempts[dni] — counter, incremented at each BP17 fire (whole-map discipline)
 * state.deviceOfflineSince[dni] — Long (millis) when marked offline; absent when online
 * state.lastOfflineWarnAt[dni]  — Long (millis) of last hourly WARN; absent until first WARN
 *
 * Threshold of 5: ~2.5 min at 30s interval — fast enough to self-heal before the
 * user notices, slow enough to avoid spurious Resyncs on transient API blips.
 */
private void ensurePollHealth() {
    if (!state.consecutiveEmpty) return
    int threshold = 5

    state.consecutiveEmpty.each { String dni, countRaw ->
        int count = countRaw as Integer
        if (count < threshold) return  // not yet a concern

        // Device is already marked offline — skip all self-heal logic entirely.
        // A genuinely-offline device won't recover via getDevices(); suppressing
        // the trigger avoids the infinite ~5min self-heal loop (BP21).
        if (state.deviceOfflineSince?.containsKey(dni)) {
            logDebug "BP21: ${dni} already marked offline — skipping self-heal"
            return
        }

        // Count how many self-heal attempts have been made for this DNI
        def healMap = (state.selfHealAttempts ?: [:]) as Map
        int attempts = ((healMap[dni] ?: 0) as Integer) + 1
        healMap[dni] = attempts
        state.selfHealAttempts = healMap

        // Reset the consecutive-empty counter for this DNI before the Resync
        // to avoid re-triggering immediately when the Resync completes.
        def counts = (state.consecutiveEmpty ?: [:]) as Map
        counts.remove(dni)
        state.consecutiveEmpty = counts

        if (attempts <= MAX_HEAL_ATTEMPTS) {
            // Still within the self-heal budget — attempt Resync.
            // (Attempt 1 is logged at INFO to alert the user; attempts 2+ at DEBUG.)
            if (attempts == 1) {
                def child = getChildDevice(dni)
                String label = child?.label ?: child?.name ?: dni
                logInfo "BP17 poll-health: device '${label}' (${dni}) returned ≥${threshold} consecutive empty results. Attempting self-heal (attempt ${attempts}/${MAX_HEAL_ATTEMPTS})."
            } else {
                logDebug "BP17 poll-health: self-heal attempt ${attempts}/${MAX_HEAL_ATTEMPTS} for ${dni}"
            }
            runIn(2, "getDevices")  // async — keeps current poll thread from blocking
        } else {
            // Exhausted self-heal budget — mark device offline.
            def child = getChildDevice(dni)
            String label = child?.label ?: child?.name ?: dni
            logInfo "BP21: device '${label}' (${dni}) still empty after ${MAX_HEAL_ATTEMPTS} self-heal attempts. " +
                    "Marking offline. Suppressing further self-heal triggers. " +
                    "Check device power / WiFi / VeSync app to verify."
            markChildOffline(dni)
        }
    }
}

/**
 * Mark a child device as offline (BP21).
 *
 * Sets state.deviceOfflineSince[dni] to the current timestamp and emits
 * sendEvent(name:"online", value:"false") on the child. The child's "online"
 * attribute is readable in dashboards and Rule Machine without any special
 * driver code — parent writes it directly via sendEvent.
 *
 * Idempotent if the device is already marked offline (the parent's
 * ensurePollHealth() guards prevent re-entry, but the double-guard is cheap).
 */
private void markChildOffline(String dni) {
    def offlineMap = (state.deviceOfflineSince ?: [:]) as Map
    if (offlineMap.containsKey(dni)) return   // already marked offline; no-op
    long ts = now()
    offlineMap[dni] = ts
    state.deviceOfflineSince = offlineMap

    // Seed lastOfflineWarnAt to now() so emitOfflineWarnsIfDue() in the same
    // updateDevices() cycle does not immediately fire a WARN (BP21 transition-cycle bug).
    // The first hourly WARN will fire ≥1h after this mark.
    def warnMap = (state.lastOfflineWarnAt ?: [:]) as Map
    warnMap[dni] = ts
    state.lastOfflineWarnAt = warnMap

    def child = getChildDevice(dni)
    if (child == null) {
        logDebug "markChildOffline(${dni}): no child device found — state updated but no event emitted"
        return
    }
    child.sendEvent(name: "online", value: "false",
        descriptionText: "Marked offline after ${MAX_HEAL_ATTEMPTS} self-heal attempts failed. Check device power / WiFi / VeSync app.")
}

/**
 * Mark a child device as back online (BP21 recovery).
 *
 * Clears state.deviceOfflineSince[dni], state.selfHealAttempts[dni], and
 * state.lastOfflineWarnAt[dni], then emits sendEvent(name:"online", value:"true")
 * on the child. Logs recovery duration at INFO.
 *
 * Called from the updateDevices() success branch when a poll returns non-empty
 * for a DNI that was previously marked offline.
 */
private void markChildOnline(String dni) {
    def offlineMap = (state.deviceOfflineSince ?: [:]) as Map
    Long sinceMillis = offlineMap[dni] as Long
    offlineMap.remove(dni)
    state.deviceOfflineSince = offlineMap

    def healMap = (state.selfHealAttempts ?: [:]) as Map
    healMap.remove(dni)
    state.selfHealAttempts = healMap

    def warnMap = (state.lastOfflineWarnAt ?: [:]) as Map
    warnMap.remove(dni)
    state.lastOfflineWarnAt = warnMap

    def child = getChildDevice(dni)
    if (child == null) {
        logDebug "markChildOnline(${dni}): no child device found — state cleared but no event emitted"
        return
    }
    String durStr = sinceMillis ? formatOfflineDuration(sinceMillis) : "unknown duration"
    child.sendEvent(name: "online", value: "true",
        descriptionText: "Device back online after ${durStr} offline.")
    logInfo "BP21: device '${child.label ?: child.name ?: dni}' (${dni}) back online after ${durStr} offline."
}

/**
 * Format an offline duration in human-readable form (BP21).
 *
 * sinceMillis — the timestamp (millis) when the device was marked offline.
 * Returns strings like "8 minutes", "2 hours", "1d 4h".
 */
private String formatOfflineDuration(long sinceMillis) {
    long elapsed = now() - sinceMillis
    long seconds = elapsed / 1000
    long minutes = seconds / 60
    long hours   = minutes / 60
    long days    = hours   / 24
    if (days > 0) {
        long remHours = hours - days * 24
        return remHours > 0 ? "${days}d ${remHours}h" : "${days}d"
    }
    if (hours > 0) {
        long remMin = minutes - hours * 60
        return remMin > 0 ? "${hours}h ${remMin}m" : "${hours} hour${hours == 1 ? '' : 's'}"
    }
    if (minutes == 0) return "less than a minute"
    return "${minutes} minute${minutes == 1 ? '' : 's'}"
}

/**
 * Emit hourly WARN for each device currently marked offline (BP21).
 *
 * Called at the top of updateDevices() after ensurePollHealth(). Iterates
 * state.deviceOfflineSince; for any entry where 1 hour has passed since the
 * last WARN (or no WARN has been emitted yet for that device), emits a WARN
 * log and updates state.lastOfflineWarnAt[dni].
 */
private void emitOfflineWarnsIfDue() {
    def offlineMap = state.deviceOfflineSince as Map
    if (!offlineMap) return
    long oneHour = 3600000L
    long nowMs = now()

    def warnMap = (state.lastOfflineWarnAt ?: [:]) as Map
    boolean warnMapDirty = false

    offlineMap.each { String dni, sinceRaw ->
        Long sinceMillis = sinceRaw as Long
        Long lastWarnAt = warnMap[dni] as Long

        // Defense-in-depth: lastOfflineWarnAt[dni] may be absent for state
        // migrated from pre-BP21 driver versions, or for any future code path that
        // sets deviceOfflineSince without going through markChildOffline().
        // Treat missing as "just now" — seed it and skip this cycle so the 1h
        // cadence starts cleanly rather than firing immediately.
        if (lastWarnAt == null) {
            warnMap[dni] = nowMs
            warnMapDirty = true
            return
        }

        boolean dueForWarn = (nowMs - lastWarnAt >= oneHour)
        if (!dueForWarn) return

        def child = getChildDevice(dni)
        String label = child?.label ?: child?.name ?: dni
        String durStr = formatOfflineDuration(sinceMillis)
        logWarn "BP21: device '${label}' has been offline for ${durStr}. Check device power / WiFi / VeSync app."

        warnMap[dni] = nowMs
        warnMapDirty = true
    }

    if (warnMapDirty) state.lastOfflineWarnAt = warnMap
}

/**
 * Returns true if the exception (or any cause in its chain) is a network-layer error
 * that BP22 should suppress.
 *
 * JDK network exceptions:
 *   UnknownHostException   — DNS failure (hub lost internet or VeSync DNS down)
 *   SocketTimeoutException — connect or read timeout
 *   ConnectException       — connection refused / unreachable
 *   NoRouteToHostException — ICMP network-unreachable
 *
 * Apache HttpClient exceptions (Hubitat's actual HTTP stack wraps JDK in these;
 * live-observed: ConnectTimeoutException appears as the cause of a wrapper, not the
 * top-level exception, so cause-chain walk is required):
 *   ConnectTimeoutException       — connect timeout (firewall silent drop / SYN timeout)
 *   ConnectionPoolTimeoutException — connection-pool exhaustion (cascading effect during outage)
 *   NoHttpResponseException        — server stopped responding after accepting connection
 *   HttpHostConnectException       — connection refused or ICMP unreachable at HttpClient layer
 *
 * String-based class.name comparison avoids classpath import issues in the Hubitat
 * Groovy sandbox. Cause-chain walk (bounded to depth 10) handles sandbox/proxy wrappers
 * that wrap the real network exception one or more levels deep.
 */
private boolean isNetworkException(Exception e) {
    Throwable t = e
    int depth = 0
    while (t != null && depth < 10) {
        String cn = t?.class?.name ?: ""
        if (cn == "java.net.UnknownHostException"                        ||
            cn == "java.net.SocketTimeoutException"                      ||
            cn == "java.net.ConnectException"                            ||
            cn == "java.net.NoRouteToHostException"                      ||
            cn == "org.apache.http.conn.ConnectTimeoutException"         ||
            cn == "org.apache.http.conn.ConnectionPoolTimeoutException"  ||
            cn == "org.apache.http.NoHttpResponseException"              ||
            cn == "org.apache.http.conn.HttpHostConnectException") {
            return true
        }
        t = t.cause
        depth++
    }
    return false
}

/**
 * Emit hourly WARN while the VeSync API is unreachable (BP22).
 *
 * Parallels emitOfflineWarnsIfDue() (BP21) but operates at the parent level:
 * network outages affect all children identically, so a single parent-level
 * state pair suffices (vs. the per-DNI maps of BP21).
 *
 * Defense-in-depth: if state.lastNetworkWarnAt is somehow absent while
 * networkUnreachableSince is set (e.g. state from a pre-BP22 hub install),
 * seeds lastNetworkWarnAt = now() and skips this cycle — same null-guard
 * lesson learned from the BP21 transition-cycle bug.
 *
 * Called at the top of updateDevices() after emitOfflineWarnsIfDue().
 */
private void emitNetworkWarnIfDue() {
    if (state.networkUnreachableSince == null) return
    long nowMs = now()
    Long lastWarnAt = state.lastNetworkWarnAt as Long
    if (lastWarnAt == null) {
        // Defense-in-depth: seed and skip this cycle.
        state.lastNetworkWarnAt = nowMs
        return
    }
    if (nowMs - lastWarnAt >= 3600000L) {
        String durStr = formatOfflineDuration(state.networkUnreachableSince as long)
        logWarn "BP22: VeSync API still unreachable for ${durStr}. Check hub network / DNS / VeSync service status."
        state.lastNetworkWarnAt = nowMs
    }
}

/**
 * Returns the configured API region ("US" or "EU").
 * Reads from settings?.deviceRegion; defaults to "US" when preference is unset
 * (preserves backwards compatibility for existing installs).
 */
private String getDeviceRegion() {
    settings?.deviceRegion ?: "US"
}

/**
 * Returns the VeSync API host for the configured region.
 * Regional routing is binary per pyvesync const.py:
 *   US → smartapi.vesync.com
 *   EU → smartapi.vesync.eu
 * Used in all three API call sites: login, getDevices, and sendBypassRequest.
 */
private String getApiHost() {
    getDeviceRegion() == "EU" ? "smartapi.vesync.eu" : "smartapi.vesync.com"
}

private Boolean retryableHttp(String label, Integer maxAttempts, Closure httpCall) {
    // Handles transient "Connection pool shut down" errors
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            return httpCall()
        }
        catch (IllegalStateException e) {
            if (e.message?.contains("Connection pool shut down")) {
                if (attempt < maxAttempts) {
                    logDebug "${label}: Connection pool error, retry ${attempt}/${maxAttempts} in 3s"
                    pauseExecution(3000)
                    continue
                }
                logError "${label}: Connection pool shut down after ${maxAttempts} attempts"
                recordError("${label}: Connection pool shut down after ${maxAttempts} attempts", [site:"retryableHttp"])
            } else {
                logError "${label}: IllegalStateException - ${e.message}"
                recordError("${label}: IllegalStateException - ${e.message}", [site:"retryableHttp"])
            }
            return false
        }
        catch (Exception e) {
            logError "${label}: ${e.toString()}"
            recordError("${label}: ${e.toString()}", [site:"retryableHttp"])
            if (e.metaClass.respondsTo(e, 'getResponse')) {
                try {
                    checkHttpResponse(label, e.getResponse())
                } catch (Exception ex) {
                    logError "${label}: Could not parse error response"
                }
            }
            return false
        }
    }
    return false
}

Boolean login() {
    return retryableHttp("login", 3) {
        def logmd5 = MD5(password)

        def params = [
            uri: "https://${getApiHost()}/cloud/v1/user/login",
            contentType: "application/json",
            requestContentType: "application/json",
            body: [
                "timeZone": getLocationTimeZone(),
                "acceptLanguage": "en",
                "appVersion": APP_VERSION,
                "phoneBrand": "SM N9005",
                "phoneOS": "Android",
                "traceId": DEFAULT_TRACE_ID,
                "email": email,
                "password": logmd5,
                "devToken": "",
                "userType": "1",
                "method": "login"
            ],
            headers: [
                "Accept": "application/json",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
                "User-Agent": "Hubitat Elevation",
                "accept-language": "en",
                "appVersion": APP_VERSION,
                "tz": getLocationTimeZone()
            ]
        ]

        logDebug "login: ${params.uri}"

        def result = false
        httpPost(params) { resp ->
            if (checkHttpResponse("login", resp)) {
                state.token = resp.data.result.token
                state.accountID = resp.data.result.accountID
                logInfo "Logged in to VeSync (${getDeviceRegion()} region: ${getApiHost()})"
                result = true
            }
        }
        return result
    }
}

def Boolean updateDevices()
{
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    // BP14 migration watchdog -- detects runIn-based installs (pre-v2.2) and converts them
    // to schedule()-based on first call. Idempotent once state.scheduleVersion == "2.2".
    ensurePollWatchdog()

    // BP16 debug watchdog -- detects stuck debugOutput=true after hub reboots (runIn
    // timer evaporated) and auto-disables. Idempotent once debug is off.
    ensureDebugWatchdog()

    // BP17 poll-health watchdog -- detects DNIs that have returned ≥5 consecutive empty
    // results (symptom of stale configModule after VeSync-side firmware update) and
    // triggers a full getDevices() Resync to refresh configModule values. Idempotent
    // once all DNI counters are cleared.
    // BP21 bounds this to 3 Resync attempts per device; after 3 fails the device is
    // marked offline and further self-heal is suppressed until the device recovers.
    ensurePollHealth()

    // BP21 hourly offline WARN -- for each device currently marked offline, emit a
    // WARN once per hour so the user knows it's still unreachable (vs. per-poll ERROR
    // spam that the old code emitted).
    emitOfflineWarnsIfDue()

    // BP22 hourly network WARN -- if the VeSync API has been unreachable (DNS / timeout /
    // connection error), re-surface a WARN once per hour. Complements BP21 (which is
    // per-device offline); BP22 is parent-level and fires regardless of device count.
    emitNetworkWarnIfDue()

    // BP22 top-level circuit-breaker: skip the entire poll cycle if the VeSync API is
    // known-unreachable and the probe interval hasn't elapsed yet.
    //
    // WHY this is needed in addition to the per-call breaker in sendBypassRequest():
    // Children are polled in a for-loop that dispatches all httpPost() calls near-
    // simultaneously. The first child to fail (~20s later) sets networkUnreachableSince,
    // but the other children are already mid-httpPost and won't re-check the breaker.
    // Result: first outage cycle still stalls ~120s. AND because state propagation in
    // Hubitat's JSON-backed proxy may not be visible to in-flight closures, subsequent
    // cycles can stall too. Top-level breaker stops the whole cycle before any child
    // dispatches, eliminating the stall entirely.
    //
    // The per-call breaker in sendBypassRequest() is still useful for command-triggered
    // calls (e.g. user presses button while network is down) that arrive outside the
    // normal polling path.
    //
    // Coordination: when firing a recovery probe here, set state.networkProbeInFlight so
    // the per-call breaker doesn't re-block the probe call in each child's sendBypassRequest.
    // Cleared in a try/finally below regardless of cycle success or failure.
    if (state.networkUnreachableSince != null) {
        long nowMs = now()
        long lastProbeAt = (state.lastNetworkProbeAt as Long) ?: (state.networkUnreachableSince as Long)
        long sinceLastProbe = nowMs - lastProbeAt
        if (sinceLastProbe < 300000L) {
            logDebug "BP22: skipping updateDevices cycle (network unreachable; next probe in ${(int)((300000L - sinceLastProbe) / 1000)}s)"
            return false
        }
        // Probe interval elapsed — let the full cycle run as a recovery probe.
        state.lastNetworkProbeAt = nowMs
        state.networkProbeInFlight = true
        logDebug "BP22: probing updateDevices cycle for network recovery (last probe ${(int)(sinceLastProbe / 1000)}s ago)"
    }

    // Stop if driver is reloading
    if (state.driverReloading) {
        logDebug "Skipping updateDevices - driver reloading"
        return false
    }

    // BP22: wrap remaining cycle body in try/finally so networkProbeInFlight is always
    // cleared at cycle end, regardless of whether the probe succeeded, failed, or threw.
    try {

    // Self-heal: remove cids from state.deviceList that have no corresponding child device.
    // This handles the case where addChildDevice silently returned a phantom handle during
    // discovery (Hubitat platform race after a recent DNI deletion). Once the platform fully
    // purges the deleted device, getChildDevice returns null and these entries become
    // orphaned polls. Removing them stops the orphan and lets the next forceReinitialize
    // attempt a clean re-add.
    def staleCids = []
    state.deviceList?.each { dni, configModule ->
        if (dni.endsWith("-nl")) return  // light children are tied to their parent's cid entry
        if (getChildDevice(dni) == null) {
            staleCids << dni
        }
    }
    if (staleCids) {
        staleCids.each { dni ->
            logInfo "Removing stale device tracking entry for ${dni} (no corresponding child device)"
            state.deviceList.remove(dni)
            // Also remove the paired night-light DNI if present
            if (state.deviceList?.containsKey(dni + "-nl")) {
                state.deviceList.remove(dni + "-nl")
            }
        }
        logInfo "Self-heal: removed ${staleCids.size()} stale device tracking entries. Run forceReinitialize to retry discovery."
    }

    // NOTE (BP14): the schedule()-based cron (set up in setupPollSchedule()) drives the
    // recurring poll tick automatically. There is NO runIn() call here for the next
    // updateDevices() invocation -- the legacy runIn chain was removed as part of the BP14
    // fix. Adding a runIn() back here would create a second concurrent chain on top of the
    // schedule() cron and is incorrect.

    // "syncing" is a transient intermediate state immediately followed by "synced"
    // or an error — emitting it every cycle is pure log noise. Dropped in v2.2.1.

    for (e in state.deviceList) {

        try {
            def dni = e.key
            def configModule = e.value

            if (dni.endsWith("-nl")) continue;

            logDebug "Updating ${dni}"

            def dev = getChildDevice(dni)
            if (dev == null) {
                logDebug "Skipping ${dni} — no child device"
                continue
            }

            // Branch the API method by device type. The VeSync API uses different read methods
            // for each device family; calling the wrong method returns code:-1 with empty result.
            // Routing is dtype-based via deviceMethodFor() (v2.3: replaced the old
            // TYPENAME_TO_METHOD substring-matching approach that failed issue #3 AC #3).
            String method = deviceMethodFor(dev)
            def command = [
                "method": method,
                "source": "APP",
                "data": [:]
            ]

            // Track last poll method per DNI for captureDiagnosticsFor() (Phase 5)
            // Whole-map-reassignment required (same BP17 state-write discipline).
            def pollMethods = (state.lastPollMethod ?: [:]) as Map
            pollMethods[dni] = method
            state.lastPollMethod = pollMethods

            sendBypassRequest(dev, command) { resp ->
                if (checkHttpResponse("update", resp))
                {
                    def status = resp.data.result
                    if (status == null) {
                        // BP17: track consecutive empty results per DNI.
                        // BP21: tiered logging — no per-poll ERROR; DEBUG while count<5 or offline;
                        // INFO at threshold (first self-heal fire); WARN hourly via emitOfflineWarnsIfDue().
                        // Whole-map-reassignment required: Hubitat state is a JSON-backed proxy.
                        def counts = (state.consecutiveEmpty ?: [:]) as Map
                        int newCount = ((counts[dni] ?: 0) as Integer) + 1
                        counts[dni] = newCount
                        state.consecutiveEmpty = counts

                        boolean alreadyOffline = state.deviceOfflineSince?.containsKey(dni)
                        if (alreadyOffline) {
                            // Device is marked offline — suppress ERROR spam; DEBUG only.
                            Long since = state.deviceOfflineSince[dni] as Long
                            logDebug "BP21: ${method} still empty for ${dni} (offline since ${new Date(since).format('HH:mm')})"
                        } else {
                            // Device is not yet marked offline — suppress per-poll ERROR;
                            // DEBUG for counts 1-4; ensurePollHealth() fires the INFO at count==5.
                            logDebug "BP21: ${method} returned empty for ${dni} (${newCount} consecutive)"
                        }
                        recordError("No status returned from ${method}", [site:"updateDevices"], dni)
                    } else {
                        // Successful poll — clear the stale-configModule counter for this DNI.
                        // Same whole-map-reassignment discipline: remove on snapshot, then reassign.
                        if (state.consecutiveEmpty?.containsKey(dni)) {
                            def counts = state.consecutiveEmpty as Map
                            counts.remove(dni)
                            state.consecutiveEmpty = counts
                        }
                        // BP21: if device was previously marked offline, mark it back online now.
                        if (state.deviceOfflineSince?.containsKey(dni)) {
                            markChildOnline(dni)
                        }
                        dev.update(status, getChildDevice(dni+"-nl"))
                    }
                }
            }
        }
        catch (exc)
        {
            logError exc.toString();
        }
    }

    // Only emit descriptionText when the heartbeat transitions to a new value.
    // Steady-state "synced→synced" cycles update the attribute silently so
    // Rule Machine / dashboards still read it, but no INFO log is generated.
    // The recovery case ("not synced" → "synced") does emit descriptionText so the
    // user can see when the stall cleared. ~240 INFO lines/hour eliminated at rest.
    String prevHeartbeat = device.currentValue("heartbeat")
    boolean heartbeatChanged = (prevHeartbeat != "synced")
    // Distinguish first-run (null) from recovery (was a non-"synced" value) so the
    // event history doesn't say "recovered from unknown" on every fresh install.
    String descText = null
    if (heartbeatChanged) {
        descText = (prevHeartbeat == null)
            ? "Heartbeat: synced"
            : "Heartbeat: synced (recovered from ${prevHeartbeat})"
    }
    // Note: no explicit logInfo() call here. sendEvent with a non-null descriptionText
    // auto-logs at INFO when descriptionTextEnable=true (standard Hubitat platform
    // behavior); calling logInfo() in addition would produce a duplicate INFO line on
    // every heartbeat transition. This bypasses the parent's sanitize() routing --
    // safe because descText is statically constructed and contains no interpolated PII.
    sendEvent(name: "heartbeat", value: "synced", isStateChange: heartbeatChanged, descriptionText: descText)

    // Schedule a call to the timeout method. This will cancel any outstanding
    // schedules.
    runIn(5 * (int)settings.refreshInterval, "timeOutLevoit")

    } finally {
        // BP22: clear probe-in-flight flag regardless of cycle outcome.
        // The flag is only set when the top-level circuit-breaker fires a recovery probe;
        // clearing it here is a no-op in normal (non-outage) cycles.
        if (state.networkProbeInFlight) {
            state.remove('networkProbeInFlight')
        }
    }
}

/**
 * Returns the VeSync API poll method for the given child device (v2.3).
 *
 * Primary routing is dtype-based: reads the raw model code from the child's stored
 * deviceType dataValue, maps it through deviceType() to a dtype string, then
 * dispatches dtype → method via switch. This eliminates the old TYPENAME_TO_METHOD
 * substring-matching approach (issue #3 AC #3) which could silently mis-route if a
 * driver's metadata name changed or a new driver's name didn't contain the expected
 * substring.
 *
 * BP17 fallback: when rawCode is absent or maps to GENERIC (e.g. stale deviceType
 * dataValue after a VeSync firmware update), falls back to typeName substring matching
 * so humidifiers and fans still receive the correct poll method instead of defaulting
 * to getPurifierStatus and compounding the empty-result problem.
 *
 * Method mapping by device family:
 *   Tower Fan   (TOWERFAN)                                  → getTowerFanStatus
 *   Pedestal Fan (PEDESTALFAN)                              → getFanStatus
 *   Humidifiers (V601S, A601S, O451S, A602S, D301S, C200S, A603S, OM1000S, B381S) → getHumidifierStatus
 *   Air purifiers (EL551S, B851S, 200S, 300S, 400S, 600S, V200S, V100S)           → getPurifierStatus
 *   Fallback: typeName contains "Humidifier"/"Tower Fan"/"Pedestal Fan"            → respective method
 *   Default (unrecognized)                                                         → getPurifierStatus
 */
private String deviceMethodFor(child) {
    String rawCode = child?.getDataValue("deviceType") ?: ""
    String dtype = rawCode ? deviceType(rawCode) : "GENERIC"
    switch (dtype) {
        case "TOWERFAN":    return "getTowerFanStatus"
        case "PEDESTALFAN": return "getFanStatus"
        case "V601S": case "A601S": case "O451S": case "A602S":
        case "D301S": case "C200S": case "A603S": case "OM1000S": case "B381S":
            return "getHumidifierStatus"
        case "EL551S": case "B851S": case "200S": case "300S":
        case "400S": case "600S": case "V200S": case "V100S":
            return "getPurifierStatus"
    }
    // BP17 fallback: rawCode was empty or mapped to GENERIC (stale/unknown deviceType dataValue).
    // Infer the correct method from the child's driver typeName — avoids 1/min log-error bursts
    // when state.deviceList configModule drifts after a VeSync-side firmware update.
    String typeName = child?.typeName ?: child?.name ?: ""
    if (typeName.contains("Tower Fan"))    return "getTowerFanStatus"
    if (typeName.contains("Pedestal Fan")) return "getFanStatus"
    if (typeName.contains("Humidifier"))   return "getHumidifierStatus"
    return "getPurifierStatus"
}

private deviceType(code) {
    switch(code)
    {
        case "Core200S":
        case "LAP-C201S-AUSR":
        case "LAP-C201S-WUSR":
        case "LAP-C202S-WUSR":      // US Core 200S variant (v2.2 audit -- same VeSyncAirBypass class)
            return "200S";
        case "Core300S":
        case "LAP-C301S-WJP":
        case "LAP-C302S-WUSB":      // US Core 300S bundle SKU (v2.2 audit -- same VeSyncAirBypass class)
            return "300S";
        case "Core400S":
        case "LAP-C401S-WJP":
        case "LAP-C401S-WUSR":
        case "LAP-C401S-WAAA":
        case "LAP-C401S-KUSR":      // PlasmaPro 400S-P black (v2.3 audit -- same VeSyncAirBypass class)
            return "400S";
        case "Core600S": 
        case "LAP-C601S-WUS":
        case "LAP-C601S-WUSR":
        case "LAP-C601S-WEU":
            return "600S";
        case "Vital200S":
        case "LAP-V201S-WUS":
        case "LAP-V201S-WUSR":
        case "LAP-V201S-WEU":
        case "LAP-V201S-WEUR":
        case "LAP-V201S-AASR":
        case "LAP-V201S-AUSR":
        case "LAP-V201S-AEUR":      // EU V201S variant (v2.2 audit -- same VeSyncAirBypass class)
        case "LAP-V201-AUSR":       // AU market V201S: intentional no-S typo SKU -- this is the
                                    // literal code the VeSync API emits for that hardware (verified
                                    // in pyvesync device_map.py). Do NOT "fix" the spelling here.
        case "LAP-V201S-WJP":       // Japan V201S variant (v2.2 audit -- same VeSyncAirBypass class)
            return "V200S";
        // Vital 100S — pyvesync VeSyncAirBaseV2, dev_types LAP-V102S-* family
        case "LAP-V102S-AASR":
        case "LAP-V102S-WUS":
        case "LAP-V102S-WEU":
        case "LAP-V102S-AUSR":
        case "LAP-V102S-WJP":
        case "LAP-V102S-AJPR":
        case "LAP-V102S-AEUR":
            return "V100S";
        // Superior 6000S — pyvesync VeSyncSuperior6000S. Listed literally (not via regex) so RULE22 lint can verify
        // isLevoitClimateDevice() coverage statically. Preserves prior `~/LEH-S60[12]S-(WUS|WUSR|WEUR)/` matching surface.
        case "LEH-S601S-WUS":
        case "LEH-S601S-WUSR":
        case "LEH-S601S-WEUR":
        case "LEH-S602S-WUS":
        case "LEH-S602S-WUSR":
        case "LEH-S602S-WEUR":
            return "V601S";
        // Classic 300S Humidifier — pyvesync VeSyncHumid200300S, dev_types Classic300S + LUH-A601S-*
        case "Classic300S":
        case "LUH-A601S-WUSB":
        case "LUH-A601S-AUSW":
            return "A601S";
        // OasisMist 450S — same pyvesync class as Classic 300S; LUH-O601S-* shares this map entry
        // per SmartThings + Homebridge cross-check (4 US model codes confirmed in device_map.py).
        // LUH-O451S-WEU added v2.2 audit: EU 4.5L variant, pyvesync confirms VeSyncHumid200300S class.
        case "LUH-O451S-WUS":
        case "LUH-O451S-WUSR":
        case "LUH-O451S-WEU":       // EU 4.5L variant (v2.2 audit -- same VeSyncHumid200300S class)
        case "LUH-O601S-WUS":
        case "LUH-O601S-KUS":
            return "O451S";
        // Levoit Tower Fan — pyvesync VeSyncTowerFan
        case "LTF-F422S-KEU":
        case "LTF-F422S-WUSR":
        case "LTF-F422S-WJP":
        case "LTF-F422S-WUS":
            return "TOWERFAN";
        // Levoit Pedestal Fan — pyvesync VeSyncPedestalFan
        // Note: pyvesync fixture filename has a typo (LPF-R423S) but real device codes are LPF-R432S
        case "LPF-R432S-AEU":
        case "LPF-R432S-AUS":
        case "LPF-R432S-AUK":       // UK Pedestal Fan (v2.3 audit -- same VeSyncPedestalFan class)
            return "PEDESTALFAN";
        // LV600S Humidifier — pyvesync VeSyncHumid200300S (same class as Classic 300S + OasisMist 450S)
        // NAMING TRAP: LUH-A602S (this block) uses VeSyncHumid200300S; LUH-A603S uses VeSyncLV600S.
        // Both are branded "LV600S" by Levoit but have different API behaviors. Only A602S here.
        // All 6 regional variants route to the same driver (pyvesync device_map.py LUH-A602S entry).
        case "LUH-A602S-WUSR":
        case "LUH-A602S-WUS":
        case "LUH-A602S-WEUR":
        case "LUH-A602S-WEU":
        case "LUH-A602S-WJP":
        case "LUH-A602S-WUSC":
            return "A602S";
        // Dual 200S Humidifier — pyvesync VeSyncHumid200300S (same class as Classic 300S + LV600S)
        // Mist range 1-2 only (Classic 300S is 1-9). Modes: auto + manual only (no sleep).
        // Features: AUTO_STOP only (no nightlight, no warm mist per device_map.py LUH-D301S entry).
        // "Dual200S" is the literal device type some firmware reports; LUH-D301S-* are the SKU codes.
        case "Dual200S":
        case "LUH-D301S-WUSR":
        case "LUH-D301S-WJP":
        case "LUH-D301S-WEU":
        case "LUH-D301S-KEUR":
            return "D301S";
        // Classic 200S Humidifier — pyvesync VeSyncHumid200S (subclass of VeSyncHumid200300S).
        // NAMING TRAP: VeSyncHumid200S overrides toggle_display() to use setIndicatorLightSwitch.
        // This is the ONLY difference from VeSyncHumid200300S. Only one literal code: "Classic200S".
        // Device has no LUH- prefix -- it only reports the literal device type "Classic200S".
        case "Classic200S":
            return "C200S";
        // LV600S Hub Connect Humidifier — pyvesync VeSyncLV600S (DIFFERENT from VeSyncHumid200300S).
        // NAMING TRAP: LUH-A603S (this block) uses VeSyncLV600S; LUH-A602S uses VeSyncHumid200300S.
        // Both are marketed as "LV600S" but have completely different API payload conventions.
        // VeSyncLV600S: powerSwitch/switchIdx, workMode (auto='humidity'), levelIdx/virtualLevel/levelType.
        case "LUH-A603S-WUS":
            return "A603S";
        // OasisMist 1000S — pyvesync VeSyncHumid1000S (inherits VeSyncHumid200300S; key method overrides).
        // V2-style payloads: powerSwitch/switchIdx, workMode (auto='auto'), levelIdx/virtualLevel/levelType,
        // targetHumidity top-level camelCase, screenSwitch for display, setAutoStopSwitch for auto-stop.
        // US/WUSR: features=[AUTO_STOP]. WEUR: features=[NIGHTLIGHT, NIGHTLIGHT_BRIGHTNESS, AUTO_STOP].
        // All three model codes route to the same Levoit OasisMist 1000S Humidifier driver.
        case "LUH-M101S-WUS":
        case "LUH-M101S-WUSR":
        case "LUH-M101S-WEUR":
            return "OM1000S";
        // Sprout Humidifier — pyvesync VeSyncSproutHumid (BypassV2Mixin, VeSyncHumidifier).
        // V2-style payloads: powerSwitch/switchIdx, workMode (auto='autoPro' — NOT 'auto'),
        // setVirtualLevel for mist (range 1-2 only), nightlight via setLightStatus
        // with colorTemperature field. Drying mode, child lock, dual filter life.
        case "LEH-B381S-WUS":
        case "LEH-B381S-WEU":
            return "B381S";
        // Sprout Air Purifier — pyvesync VeSyncAirSprout (inherits VeSyncAirBaseV2).
        // V2-style payloads: powerSwitch/switchIdx, setPurifierMode {workMode} for auto/sleep,
        // manual mode via setLevel (NOT setPurifierMode). Fan levels 1-3.
        // AIR_QUALITY + NIGHTLIGHT features. Nightlight via setNightLight {night_light: str}.
        case "LAP-B851S-WEU":
        case "LAP-B851S-WNA":
        case "LAP-B851S-AEUR":
        case "LAP-B851S-AUS":
        case "LAP-B851S-WUS":
        case "LAP-BAY-MAX01S":
            return "B851S";
        // EverestAir Air Purifier — pyvesync VeSyncAirBaseV2 (same class as Vital 200S/Sprout Air).
        // V2-style payloads: powerSwitch/switchIdx, setPurifierMode {workMode} for auto/sleep/turbo,
        // manual mode via setLevel. Fan levels 1-3.
        // Unique features: TURBO mode (workMode="turbo"), VENT_ANGLE (fanRotateAngle passive read),
        // LIGHT_DETECT (setLightDetection {lightDetectionSwitch}). No nightlight, no pet mode.
        case "LAP-EL551S-WUS":
        case "LAP-EL551S-WEU":
        case "LAP-EL551S-AEUR":
        case "LAP-EL551S-AUS":
            return "EL551S";
        default:
            // Unknown model code — fall through to Generic diagnostic driver.
            // The Generic driver provides best-effort power control and captureDiagnostics()
            // to accelerate new-model support filing (new-device-support issue template).
            return "GENERIC";
    }
}

/**
 * Returns true if the raw VeSync model code looks like a Levoit climate-class device
 * (air purifier, humidifier, or fan) that should be attached to a Levoit driver.
 *
 * Whitelist (v2.1):
 *   LAP-*  — Levoit Air Purifier (Core, Vital, and future variants)
 *   LEH-*  — Levoit Evaporative Humidifier (Superior, OasisMist, and future)
 *   LUH-*  — Levoit Ultrasonic Humidifier (Classic 300S LUH-A601S-*, OasisMist 450S
 *             LUH-O451S-*, LUH-O601S-*, and future humidifier follow-ons).
 *             Added alongside the LUH-* gap fix; lint rule 22 enforces parity with deviceType().
 *   LV-*   — Older Levoit purifiers/humidifiers (LV-PUR131S, LV-RH131S, etc.)
 *   LTF-*  — Levoit Tower Fan (added v2.1 alongside proper Tower Fan driver).
 *             Future unknown LTF-X-Y codes fall through to the Generic driver
 *             rather than being skipped as non-Levoit.
 *   LPF-*  — Levoit Pedestal Fan (added v2.1 alongside proper Pedestal Fan driver).
 *             Same rationale as LTF-*.
 *   Literal names recognized by deviceType() — included for completeness so
 *     this method can also be used as a standalone classifier if needed.
 *     "Classic300S" added alongside LUH-* (some firmware reports this literal).
 *
 * Everything else (Etekcity WS-, ESW-, ESO-, Cosori CS-, thermostats, etc.)
 * is not a Levoit climate device and returns false.
 */
private Boolean isLevoitClimateDevice(String code) {
    if (!code) return false
    if (code.startsWith("LAP-")) return true
    if (code.startsWith("LEH-")) return true
    if (code.startsWith("LUH-")) return true   // Classic 300S, OasisMist 450S, Superior 6000S, future humidifiers
    if (code.startsWith("LV-"))  return true
    // v2.1: fan prefixes added alongside proper Tower Fan and Pedestal Fan drivers
    if (code.startsWith("LTF-")) return true
    if (code.startsWith("LPF-")) return true
    if (code in ["Core200S", "Core300S", "Core400S", "Core600S", "Vital200S", "Classic300S", "Dual200S", "Classic200S"]) return true
    // LUH-A603S-WUS / LUH-M101S-* / LEH-B381S-* covered by LUH-*/LEH-* prefix blankets above (RULE22 satisfied)
    // LAP-B851S-* / LAP-BAY-MAX01S covered by LAP-* prefix blanket above (RULE22 satisfied)
    // B381S and B851S are dtype abbreviations (not raw model codes), not reachable here directly.
    // isLevoitClimateDevice() is called with raw model codes, so prefix checks above cover them.
    return false
}

private Boolean getDevices() {
    return retryableHttp("getDevices", 3) {
        def params = [
            uri: "https://${getApiHost()}/cloud/v1/deviceManaged/devices",
            contentType: "application/json",
            requestContentType: "application/json",
            body: [
                "timeZone": getLocationTimeZone(),
                "acceptLanguage": "en",
                "appVersion": APP_VERSION,
                "phoneBrand": "SM N9005",
                "phoneOS": "Android",
                "traceId": DEFAULT_TRACE_ID,
                "accountID": state.accountID,
                "token": state.token,
                "method": "devices",
                "pageNo": "1",
                "pageSize": "100"
            ],
            headers: [
                "tz": getLocationTimeZone(),
                "Accept": "application/json",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
                "User-Agent": "Hubitat Elevation",
                "accept-language": "en",
                "appVersion": APP_VERSION,
                "accountID": state.accountID,
                "tk": state.token
            ]
        ]

        // processResponse: the inner handler that does the actual device-list work.
        // Called by effectiveClosure below (either directly or after re-auth retry).
        // Returns true on success, false on failure. Uses a holder list for symmetry
        // with potential future closure-passed-by-reference patterns; plain
        // `def result = false` also works (see login() at line ~219 for the
        // established pattern).
        def resultHolder = [false]
        Closure processResponse = { resp ->
            if (checkHttpResponse("getDevices", resp)) {
                // Defensive null checks for API response structure
                if (!resp.data) {
                    logError "getDevices: No data in response"
                    return
                }
                if (!resp.data.result) {
                    logError "getDevices: No result in response data"
                    return
                }
                if (resp.data.result.list == null) {
                    logError "getDevices: No list in response result"
                    return
                }

                def newList = [:]

                for (device in resp.data.result.list) {
                    logDebug "Device found: ${device.deviceType} / ${device.deviceName} / ${device.macID}"

                    def dtype = deviceType(device.deviceType);

                    if (dtype == "200S") {
                        newList[device.cid] = device.configModule;
                        newList[device.cid+"-nl"] = device.configModule;
                    }
                    else if (dtype == "400S" || dtype == "300S" || dtype == "600S" || dtype == "V200S" || dtype == "V601S" ||
                             dtype == "V100S" || dtype == "A601S" || dtype == "O451S" || dtype == "TOWERFAN" || dtype == "PEDESTALFAN" ||
                             dtype == "A602S" || dtype == "D301S" || dtype == "C200S" || dtype == "A603S" || dtype == "OM1000S" ||
                             dtype == "B381S" || dtype == "B851S" || dtype == "EL551S") {
                        newList[device.cid] = device.configModule;
                    }
                    else if (dtype == "GENERIC" && isLevoitClimateDevice(device.deviceType)) {
                        newList[device.cid] = device.configModule;
                    }
                }

                logInfo "Discovered ${resp.data.result.list.size()} VeSync device(s)"

                // Remove devices that are no longer present.
                def list = getChildDevices();
                if (list) list.each {
                    String dni = it.getDeviceNetworkId();
                    if (newList.containsKey(dni) == false) {
                        logInfo "Removed child device: ${dni}"
                        logDebug "Deleting ${dni}"
                        deleteChildDevice(dni);
                    }
                }

                // Dedup missing-driver INFO logs: track which driver names have already
                // been warned about this Resync so N devices missing the same driver
                // produce exactly 1 actionable message (not N). Cleared in finally so
                // cleanup runs on both normal exit and any unhandled exception in the loop.
                state.warnedMissingDrivers = [] as Set
                // Generic-migration hint dedup: track which DNIs have already been warned
                // this Resync so a device doesn't get the same migration INFO every poll.
                state.genericMigrationWarnings = [] as List

                try { for (device in resp.data.result.list) {
                    def dtype = deviceType(device.deviceType);
                    def equip1 = getChildDevice(device.cid)

                    if (dtype == "200S") {
                        def equip2 = getChildDevice(device.cid+"-nl")

                        if (equip2 == null) {
                            // Note: a null return from safeAddChildDevice means the Light driver is not installed.
                            // This is non-fatal for the 200S branch — we continue to add the main purifier device.
                            warnIfGenericMigration(device.cid+"-nl", "Levoit Core200S Air Purifier Light")
                            equip2 = safeAddChildDevice("Levoit Core200S Air Purifier Light", device.cid+"-nl",
                                [name: device.deviceName + " Light", label: device.deviceName + " Light", isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCore200S%20Light.groovy")
                            if (equip2 != null) {
                                // Defensive validation: addChildDevice may return a phantom handle if the DNI was
                                // recently deleted and Hubitat's purge has not completed (platform race condition).
                                def verifyLight = getChildDevice(device.cid + "-nl")
                                if (verifyLight == null) {
                                    logError "addChildDevice for ${device.deviceName} Light (${device.cid}-nl) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                    // Non-fatal: continue to add the main Core 200S device below.
                                } else {
                                    equip2 = verifyLight
                                    equip2.updateDataValue("configModule", device.configModule);
                                    equip2.updateDataValue("cid", device.cid);
                                    equip2.updateDataValue("uuid", device.uuid);
                                    equip2.updateDataValue("deviceType", device.deviceType);
                                    logInfo "Added child device: ${device.deviceName} Light (Levoit Core200S Air Purifier Light)"
                                }
                            }
                        }
                        else {
                            logDebug "Updating ${device.deviceName} Light / " + dtype;
                            equip2.name = device.deviceName + " Light";
                            equip2.label = device.deviceName + " Light";
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip2.updateDataValue("configModule", device.configModule);
                            equip2.updateDataValue("cid", device.cid);
                            equip2.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip2.updateDataValue("deviceType", device.deviceType);
                        }

                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Core200S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Core200S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCore200S.groovy")
                            if (equip1 == null) continue
                            // Defensive validation: confirm the device is queryable (guards against DNI-purge phantom)
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "300S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Core300S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Core300S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCore300S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core300S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "400S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Core400S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Core400S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCore400S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core400S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "600S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Core600S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Core600S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCore600S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core600S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "V200S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Vital 200S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Vital 200S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitVital200S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Vital 200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "V601S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Superior 6000S Humidifier")
                            equip1 = safeAddChildDevice("Levoit Superior 6000S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitSuperior6000S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Superior 6000S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "V100S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Vital 100S Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Vital 100S Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitVital100S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Vital 100S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "A601S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Classic 300S Humidifier")
                            equip1 = safeAddChildDevice("Levoit Classic 300S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitClassic300S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Classic 300S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "O451S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit OasisMist 450S Humidifier")
                            equip1 = safeAddChildDevice("Levoit OasisMist 450S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitOasisMist450S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit OasisMist 450S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "A602S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit LV600S Humidifier")
                            equip1 = safeAddChildDevice("Levoit LV600S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitLV600S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit LV600S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "D301S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Dual 200S Humidifier")
                            equip1 = safeAddChildDevice("Levoit Dual 200S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitDual200S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Dual 200S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "C200S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Classic 200S Humidifier")
                            equip1 = safeAddChildDevice("Levoit Classic 200S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitClassic200S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Classic 200S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "A603S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit LV600S Hub Connect Humidifier")
                            equip1 = safeAddChildDevice("Levoit LV600S Hub Connect Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitLV600SHubConnect.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit LV600S Hub Connect Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "OM1000S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit OasisMist 1000S Humidifier")
                            equip1 = safeAddChildDevice("Levoit OasisMist 1000S Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitOasisMist1000S.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit OasisMist 1000S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "B381S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Sprout Humidifier")
                            equip1 = safeAddChildDevice("Levoit Sprout Humidifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitSproutHumidifier.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Sprout Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "B851S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Sprout Air Purifier")
                            equip1 = safeAddChildDevice("Levoit Sprout Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitSproutAir.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Sprout Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "EL551S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit EverestAir Air Purifier")
                            equip1 = safeAddChildDevice("Levoit EverestAir Air Purifier", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitEverestAir.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit EverestAir Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "TOWERFAN") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Tower Fan")
                            equip1 = safeAddChildDevice("Levoit Tower Fan", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitTowerFan.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Tower Fan)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "PEDESTALFAN") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            warnIfGenericMigration(device.cid, "Levoit Pedestal Fan")
                            equip1 = safeAddChildDevice("Levoit Pedestal Fan", device.cid,
                                [name: device.deviceName, label: device.deviceName, isComponent: false],
                                "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitPedestalFan.groovy")
                            if (equip1 == null) continue
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            equip1.updateDataValue("deviceType", device.deviceType);
                            logInfo "Added child device: ${device.deviceName} (Levoit Pedestal Fan)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                            // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                            equip1.updateDataValue("deviceType", device.deviceType);
                        }
                    }
                    else if (dtype == "GENERIC") {
                        if (isLevoitClimateDevice(device.deviceType)) {
                            if (equip1 == null) {
                                logDebug "Adding ${device.deviceName} (unrecognized model ${device.deviceType} -- using Generic driver)"
                                equip1 = safeAddChildDevice("Levoit Generic Device", device.cid,
                                    [name: device.deviceName, label: device.deviceName, isComponent: false],
                                    "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitGeneric.groovy")
                                if (equip1 == null) continue
                                def verify1 = getChildDevice(device.cid)
                                if (verify1 == null) {
                                    logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                    continue
                                }
                                equip1 = verify1
                                equip1.updateDataValue("configModule", device.configModule);
                                equip1.updateDataValue("cid", device.cid);
                                equip1.updateDataValue("uuid", device.uuid);
                                equip1.updateDataValue("deviceType", device.deviceType);
                                logInfo "Added child device: ${device.deviceName} (Levoit Generic Device -- model ${device.deviceType} not yet supported; run captureDiagnostics to file a new-device-support request)"
                            }
                            else {
                                logDebug "Updating ${device.deviceName} / " + dtype;
                                equip1.name = device.deviceName;
                                equip1.label = device.deviceName;
                                // BP19: refresh load-bearing data values so BP17 Resync actually heals stale configModule
                                equip1.updateDataValue("configModule", device.configModule);
                                equip1.updateDataValue("cid", device.cid);
                                equip1.updateDataValue("uuid", device.uuid);
                                // backfill for v2.1 -> v2.2 upgrades — child gates on state.deviceType
                                equip1.updateDataValue("deviceType", device.deviceType);
                            }
                        } else {
                            logInfo "Skipping unsupported device type: ${device.deviceType} (${device.deviceName}) -- not a Levoit air purifier or humidifier. If this is a Levoit device, please file a new-device-support issue."
                        }
                    }
                } } finally {
                    // Clean up the dedup set — it was only needed during the loop above.
                    // Removing it from state avoids persisting a stale set on disk.
                    // finally{} ensures cleanup runs even if the loop throws unexpectedly.
                    state.remove('warnedMissingDrivers')
                    state.remove('genericMigrationWarnings')
                }

                state.deviceList = newList
                runIn(5 * (int)settings.refreshInterval, "timeOutLevoit")
                // Removed v2.2 — schedule()-based cron from setupPollSchedule() handles initial
                // poll within 30s of installed/updated; runIn(10, "updateDevices") was redundant
                // and produced a duplicate heartbeat event on first install.
                resultHolder[0] = true
            }
        }

        // effectiveClosure: detect auth failure, re-authenticate once, retry.
        // Mirrors the same pattern used in sendBypassRequest. Re-uses the
        // state.reAuthInProgress guard so the two entry points don't collide.
        Closure effectiveClosure = { resp ->
            if (isAuthFailure(resp) && !state.reAuthInProgress) {
                logInfo "VeSync token expired or invalid (getDevices) -- re-authenticating"
                state.reAuthInProgress = true
                try {
                    if (login()) {
                        // Refresh tokens in both body and headers before retry
                        params.body.token       = state.token
                        params.body.accountID   = state.accountID
                        params.headers.tk       = state.token
                        params.headers.accountID = state.accountID
                        logDebug "Re-auth succeeded -- retrying getDevices"
                        httpPost(params, processResponse)
                        return
                    } else {
                        logError "Re-auth failed during getDevices -- check VeSync credentials"
                        recordError("Re-auth failed during getDevices -- check VeSync credentials", [site:"getDevices"])
                    }
                } finally {
                    state.remove('reAuthInProgress')
                }
            }
            // Not an auth failure (or re-auth failed): process the response as-is
            processResponse(resp)
        }

        httpPost(params, effectiveClosure)
        return resultHolder[0]
    }
}

/**
 * Detect VeSync auth-failure responses (Bug Pattern #13).
 *
 * Two failure modes:
 *   1. HTTP 401 — transport-level auth rejection (rare for VeSync, but handle defensively)
 *   2. HTTP 200 with inner error code in the JSON body — the typical VeSync signal:
 *        -11001000 (normalized from VeSync API) = TOKEN_EXPIRED
 *        -11201000                               = PASSWORD_ERROR / invalid credentials
 *      We also accept the 4-digit shorthand forms (-11001, -11201) defensively,
 *      in case older firmware or regional variants use abbreviated codes.
 *
 * pyvesync cross-check (c98729c, src/pyvesync/utils/errors.py):
 *   '-11001000' -> TOKEN_EXPIRED (ErrorTypes.TOKEN_ERROR)   [only TOKEN_ERROR class entry]
 *   '-11201000' -> PASSWORD_ERROR (ErrorTypes.AUTHENTICATION)
 *   '-11003000' -> REQUEST_HIGH (ErrorTypes.RATE_LIMIT)     [NOT auth — do NOT include]
 *   '-11202000' -> ACCOUNT_NOT_EXIST (ErrorTypes.AUTHENTICATION)
 *
 * Inner code -1 is NOT an auth failure (Bug Pattern #2 humidifier wrong-method, etc.).
 * Do NOT include -1 in this predicate.
 *
 * The VeSync API returns inner codes at resp.data.code (bypassV2 transport level),
 * NOT at resp.data.result.code (device level). We inspect both because the exact
 * placement of auth-failure codes may depend on which endpoint rejected the request.
 */
private boolean isAuthFailure(resp) {
    try {
        Integer httpStatus = resp?.status as Integer
        if (httpStatus == 401) return true

        // Check the top-level JSON code (bypassV2 transport rejection)
        def outerCode = resp?.data?.code
        if (outerCode != null) {
            long c = outerCode as long
            // 8-digit canonical codes
            if (c == -11001000L || c == -11201000L) return true
            // 4-digit defensive variants (older firmware / regional variants)
            if (c == -11001L || c == -11201L) return true
        }

        // Also check result-level code in case some devices nest it there
        def resultCode = resp?.data?.result?.code
        if (resultCode != null) {
            long c = resultCode as long
            if (c == -11001000L || c == -11201000L) return true
            if (c == -11001L || c == -11201L) return true
        }
    } catch (ignored) {
        // Null-safe: malformed response is not an auth failure
    }
    return false
}

def Boolean sendBypassRequest(equipment, payload, Closure closure) {
    // Stop if driver is reloading
    if (state.driverReloading) {
        logDebug "Skipping sendBypassRequest - driver reloading"
        return false
    }

    // BP14 migration watchdog -- catches installs where polling died after a reboot but
    // the user (or a Rule) still sends a direct command. The direct command re-arms the
    // cron so future polls resume from this point forward.
    ensurePollWatchdog()

    logDebug "sendBypassRequest(${payload})"

    def params = [
        uri: "https://${getApiHost()}/cloud/v2/deviceManaged/bypassV2",
        contentType: "application/json; charset=UTF-8",
        requestContentType: "application/json; charset=UTF-8",
        body: [
            "timeZone": getLocationTimeZone(),
            "acceptLanguage": "en",
            "appVersion": APP_VERSION,
            "phoneBrand": "SM N9005",
            "phoneOS": "Android",
            "traceId": DEFAULT_TRACE_ID,
            "cid": equipment.getDataValue("cid"),
            "configModule": equipment.getDataValue("configModule"),
            "payload": payload,
            "accountID": getAccountID(),
            "token": getAccountToken(),
            "method": "bypassV2",
            "debugMode": false,
            "deviceRegion": getDeviceRegion()
        ],
        headers: [
            "tz": getLocationTimeZone(),
            "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate, br",
            "Connection": "keep-alive",
            "User-Agent": "Hubitat Elevation",
            "accept-language": "en",
            "appVersion": APP_VERSION,
            "accountID": getAccountID(),
            "tk": getAccountToken()
        ]
    ]

    // Wrap the caller's closure with centralized API-trace logging.
    // - debugOutput on  → 1-line summary per API call (method/cid/status/inner)
    // - verboseDebug on → full response body dump (useful when VeSync changes a field name)
    // Both are sanitized via logDebug → sanitize() chain. Children's parse-time logs
    // continue to work and provide complementary parser-side observability.
    Closure tracingClosure = closure
    if (settings?.debugOutput) {
        String method   = payload?.method?.toString() ?: "?"
        String cidShort = (equipment?.getDataValue("cid") ?: "?").take(8)
        tracingClosure = { resp ->
            try {
                Integer status = resp?.status as Integer ?: -1
                def innerCode  = resp?.data?.result?.code
                logDebug "API trace: method=${method} cid=${cidShort}.. HTTP ${status} inner=${innerCode}"
                if (settings?.verboseDebug) {
                    logDebug "API response body: ${resp?.data}"
                }
            } catch (ignored) { /* never let trace logging break the API call */ }
            closure(resp)
        }
    }

    // Outermost closure: detect auth failure and retry once with a fresh token.
    // Must be the outer wrapper so it can intercept the response before the
    // tracing closure or the caller's closure processes it.
    //
    // Re-entrance guard: state.reAuthInProgress prevents infinite loops if
    // login() itself somehow triggers sendBypassRequest (it does NOT — login()
    // uses httpPost directly via retryableHttp — but we guard defensively).
    Closure effectiveClosure = { resp ->
        if (isAuthFailure(resp) && !state.reAuthInProgress) {
            logInfo "VeSync token expired or invalid -- re-authenticating"
            state.reAuthInProgress = true
            try {
                if (login()) {
                    // Refresh both body and header tokens with the new values
                    params.body.token     = getAccountToken()
                    params.body.accountID = getAccountID()
                    params.headers.tk     = getAccountToken()
                    params.headers.accountID = getAccountID()
                    logDebug "Re-auth succeeded -- retrying ${payload?.method}"
                    // Retry once; pass the retry's response to the inner (tracing) closure
                    httpPost(params, tracingClosure)
                    return
                } else {
                    logError "Re-auth failed -- VeSync credentials may need to be updated in driver settings"
                    recordError("Re-auth failed -- VeSync credentials may need to be updated in driver settings", [site:"sendBypassRequest"])
                }
            } finally {
                state.remove('reAuthInProgress')
            }
        }
        // Not an auth failure (or re-auth failed): pass through to inner closure
        tracingClosure(resp)
    }

    // BP22 per-call circuit-breaker: skip httpPost entirely when in a known network outage.
    // Guards command-triggered calls (e.g. user pressing a button while network is down)
    // that arrive outside the normal polling path. The top-level breaker in updateDevices()
    // already handles the parallel-poll case; this is the fallback for non-poll callers.
    //
    // Skip if state.networkProbeInFlight is true: the top-level breaker in updateDevices()
    // already determined that this cycle IS the recovery probe and set lastNetworkProbeAt.
    // Without this bypass, the per-call check would see sinceLastProbe=0 and skip too,
    // creating a deadlock where neither breaker allows the probe through.
    if (state.networkUnreachableSince != null && !state.networkProbeInFlight) {
        long nowMs = now()
        long lastProbeAt = (state.lastNetworkProbeAt as Long) ?: (state.networkUnreachableSince as Long)
        long sinceLastProbe = nowMs - lastProbeAt
        if (sinceLastProbe < 300000L) {
            logDebug "BP22: skipping httpPost (network unreachable; next probe in ${(int)((300000L - sinceLastProbe) / 1000)}s)"
            return false
        }
        // 5+ minutes elapsed — let this call through as a probe for recovery.
        state.lastNetworkProbeAt = nowMs
        logDebug "BP22: probing httpPost for network recovery (last probe ${(int)(sinceLastProbe / 1000)}s ago)"
    }

    try {
        httpPost(params, effectiveClosure)
        // Network success — check if we're recovering from an outage (BP22).
        if (state.networkUnreachableSince != null) {
            String durStr = formatOfflineDuration(state.networkUnreachableSince as long)
            logInfo "BP22: VeSync API reachable again after ${durStr} unreachable."
            state.networkUnreachableSince = null
            state.lastNetworkWarnAt = null
            state.lastNetworkProbeAt = null
        }
        return true
    }
    catch (IllegalStateException e) {
        if (e.message?.contains("Connection pool shut down")) {
            logError "sendBypassRequest: Connection pool shut down - try again in a few seconds"
        } else {
            logError "sendBypassRequest: IllegalStateException - ${e.message}"
        }
        return false
    }
    catch (Exception e) {
        // BP22: distinguish network-layer errors from other failures.
        // Network errors during an outage flood logs with one ERROR per child per poll cycle.
        // Apply tiered suppression: one-time WARN on first error; DEBUG-only while outage continues.
        if (isNetworkException(e)) {
            if (state.networkUnreachableSince == null) {
                long ts = now()
                state.networkUnreachableSince = ts
                state.lastNetworkWarnAt = ts
                logWarn "BP22: VeSync API unreachable — ${e.class.simpleName}: ${e.message}. Suppressing further per-poll errors until recovery; will re-surface hourly while down."
            } else {
                logDebug "BP22: still unreachable (${e.class.simpleName})"
            }
        } else {
            logError "sendBypassRequest: ${e.toString()}"
            recordError("sendBypassRequest: ${e.toString()}", [site:"sendBypassRequest"])
        }
        return false
    }
}

// Commands -------------------------------------------------------------------------------------------------------------------

def resyncEquipment() {
  //
  // This will trigger a sensor remapping and cleanup
  //
  try {
    logDebug "resyncEquipment()"
    logInfo "Resyncing VeSync equipment"
    
    // Wait for connection pool to be ready
    pauseExecution(2000)
    getDevices()
  }
  catch (Exception e) {
    logError("Exception in resyncEquipment(): ${e}");
  }
}

def forceReinitialize() {
  //
  // Force reinitialization - use if connection errors persist
  //
  try {
    logDebug "forceReinitialize()"
    logInfo "Force reinitializing VeSync integration"
    
    // Clear state and reschedule
    state.clear()
    unschedule()
    
    // Wait longer for connection pool
    pauseExecution(5000)
    
    initialize()
  }
  catch (Exception e) {
    logError("Exception in forceReinitialize(): ${e}");
  }
}

// Helpers -------------------------------------------------------------------------------------------------------------------

def getAccountToken() {
    return state.token
}

def getAccountID() {
    return state.accountID
}

/**
 * Return the hub's configured timezone ID, falling back to the hard-coded
 * DEFAULT_TIME_ZONE if the hub has no location configured.
 *
 * Hubitat exposes `location.timeZone` as a java.util.TimeZone. Its `.ID`
 * property returns the IANA zone name (e.g. "America/Chicago"). This is the
 * value VeSync expects in the timeZone request field.
 *
 * Fallback: hubs without a location configured (common in early setup or
 * developer-mode installs) return null for location?.timeZone, so we fall
 * back to "America/Los_Angeles" (the value previously hardcoded in this driver
 * since the original upstream -- preserved for backward-compat with existing installs).
 */
private String getLocationTimeZone() {
    return location?.timeZone?.ID ?: DEFAULT_TIME_ZONE
}

/**
 * Attempt to add a child device by driver name; return null and log an actionable INFO
 * message (with install URL) if the driver is not installed, so the discovery loop can
 * continue to the next device rather than crashing.
 *
 * This catches com.hubitat.app.exception.UnknownDeviceTypeException — the exception
 * thrown by addChildDevice() when the named driver type has not been installed on the
 * hub. Without this guard, a single missing optional driver kills the entire discovery
 * loop for all subsequent devices.
 *
 * For the Core 200S Light companion specifically: the caller should treat a null return
 * as a non-fatal skip (continue to add the main Core 200S device). For all other drivers
 * a null return means the entire device cannot be managed and the caller should skip it.
 *
 * @param driverName  Hubitat driver type name (e.g. "Levoit Core200S Air Purifier")
 * @param dni         Device Network Id to assign
 * @param opts        Map of options (name, label, isComponent)
 * @param installUrl  Raw GitHub URL for the driver file (shown in the INFO log)
 * @return            The new child device, or null if the driver is not installed
 */
/**
 * Detect Generic-driver → proper-driver migration opportunities.
 *
 * When a user originally installed a device whose model code was unrecognized,
 * the parent fell back to "Levoit Generic Device" for that DNI. If a later release
 * adds a proper driver for that model code, Hubitat does NOT auto-migrate the
 * existing child device — addChildDevice() with an existing DNI returns the existing
 * handle unchanged. The user must manually re-pick the driver via:
 *   Hubitat UI → device page → Type dropdown → Save
 *
 * This helper logs one INFO message per affected device per Resync, naming the
 * device, the proper driver name, and the migration steps. Deduped via
 * state.genericMigrationWarnings (same lifecycle as state.warnedMissingDrivers:
 * initialized before the device-add loop, removed in the finally block).
 *
 * Call BEFORE every safeAddChildDevice() site in getDevices().
 */
private void warnIfGenericMigration(String dni, String newDriverName) {
    def existing = getChildDevice(dni)
    if (existing == null) return
    if (existing.typeName != "Levoit Generic Device") return
    if (state.genericMigrationWarnings == null) return  // initialized in getDevices() loop preamble
    if (state.genericMigrationWarnings.contains(dni)) return  // already warned this Resync
    String label = existing.label ?: existing.name ?: dni
    logInfo "Migration available: '${label}' (${dni}) is currently using the Generic fallback driver. " +
            "v2.3 has a proper driver: '${newDriverName}'. To upgrade: Hubitat UI → device page → " +
            "'Type' dropdown → '${newDriverName}' → Save Preferences. Your DNI, label, room, and " +
            "automations are preserved; only the driver code changes."
    def warnings = state.genericMigrationWarnings
    warnings.add(dni)
    state.genericMigrationWarnings = warnings
}

private safeAddChildDevice(String driverName, String dni, Map opts, String installUrl) {
    try {
        return addChildDevice(driverName, dni, opts)
    } catch (com.hubitat.app.exception.UnknownDeviceTypeException ex) {
        // Dedup: only log the first miss per driver per Resync Equipment invocation.
        // state.warnedMissingDrivers is initialized at the top of the device-add loop
        // in getDevices() and removed when the loop completes. Subsequent devices that
        // need the same missing driver are silently skipped (still return null) so the
        // user sees exactly one actionable message per missing driver, not one per device.
        if (state.warnedMissingDrivers == null || !state.warnedMissingDrivers.contains(driverName)) {
            logInfo "Driver '${driverName}' is not installed — skipping ${opts?.label ?: dni}. " +
                "Add via HPM → Modify → 'Levoit Air Purifiers, Humidifiers, and Fans' → " +
                "check '${driverName}' → Next. Or paste from ${installUrl}. " +
                "Then click Resync Equipment again."
            if (state.warnedMissingDrivers != null) {
                def warned = state.warnedMissingDrivers
                warned.add(driverName)
                state.warnedMissingDrivers = warned
            }
        }
        return null
    }
}

def MD5(s) {
	def digest = MessageDigest.getInstance("MD5")
	new BigInteger(1,digest.digest(s.getBytes())).toString(16).padLeft(32,"0")
} 

def parseJSON(data) {
    def json = data.getText()
    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(json)
}

def logInfo(msg) {
    if (settings?.descriptionTextEnable) log.info sanitize(msg)
}

def logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug sanitize(msg)
    }
}

def logError(msg) {
    log.error sanitize(msg)
}

def logWarn(msg) {
    log.warn sanitize(msg)
}

// Auto-sanitize log messages so debug captures shared by users in community threads / GitHub
// issues don't leak the VeSync account email, accountID, auth token, or hashed password.
// Strips the values from any log message (regardless of which call site emitted it).
private String sanitize(msg) {
    if (msg == null) return msg
    String s = msg.toString()

    // Email — redact any address-shaped substring
    s = s.replaceAll(/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/, '[email-redacted]')

    // Known sensitive values from current state (exact-match replace, fast and safe)
    if (state?.accountID) s = s.replace(state.accountID as String, '[accountID-redacted]')
    if (state?.token)     s = s.replace(state.token as String,     '[token-redacted]')

    // Password should never be in logs but defensively redact if a settings dump leaks it
    if (settings?.password) s = s.replace(settings.password as String, '[password-redacted]')

    return s
}

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false]);
}

// ---------------------------------------------------------------------------
// captureDiagnosticsFor — parent-side context for child diagnostic captures
// (Phase 5, LevoitDiagnosticsLib)
//
// Children call parent.captureDiagnosticsFor(device.deviceNetworkId) from the
// library's captureDiagnostics() to retrieve parent-held per-device state that
// the child cannot read directly (consecutiveEmpty counter, last poll method,
// configModule, last error timestamp + message from the ring buffer).
//
// Returns a Map of display-ready key→value pairs. Empty map on any error.
// The child's buildDiagnosticBlock() formats this into the "Parent state for
// this device" table section of the markdown dump.
// ---------------------------------------------------------------------------

Map captureDiagnosticsFor(String childDni) {
    if (!childDni) return [:]
    try {
        Map ctx = [:]

        // Consecutive-empty counter (BP17 stale-configModule detector)
        def counts = state.consecutiveEmpty as Map ?: [:]
        ctx["consecutiveEmpty"] = counts[childDni] ?: 0

        // Last poll method routed to this child (tracked in updateDevices() since v2.4)
        def pollMethods = state.lastPollMethod as Map ?: [:]
        ctx["lastPollMethod"] = pollMethods[childDni] ?: "(not yet polled this session)"

        // configModule from state.deviceList — the bypassV2 routing key
        def devList = state.deviceList as Map ?: [:]
        ctx["configModule"] = devList[childDni] ?: "(unknown)"

        // Last error from the ring buffer for this DNI
        def history = (state.errorHistory as Map ?: [:])
        def slot = (history[childDni] ?: []) as List
        if (slot) {
            def last = slot[-1]
            String ts = last.ts ? new Date(last.ts as Long).format("yyyy-MM-dd HH:mm:ss") : "?"
            ctx["lastError.ts"]  = ts
            ctx["lastError.msg"] = (last.msg ?: "").take(200)
        } else {
            ctx["lastError.ts"]  = "(none)"
            ctx["lastError.msg"] = ""
        }

        return ctx
    } catch (Exception e) {
        logDebug "captureDiagnosticsFor(${childDni}): ${e}"
        return [:]
    }
}


def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		// Route through logError to auto-sanitize — response data on a failed login can include accountID/token
		logError "${action}: ${resp.status} - ${resp.getData()}"
		return false
	}
	else
	{
		logError "${action}: unexpected HTTP response: ${resp.status}"
		return false
	}
}

def timeOutLevoit() {
    //If the timeout expires before being reset, mark this Parent Device as 'not present' to allow action to be taken
    logInfo "Heartbeat timeout — no update from VeSync servers"
    sendEvent(name: "heartbeat", value: "not synced", isStateChange: true, descriptionText: "No update received from VeSync servers in a long time.")
}