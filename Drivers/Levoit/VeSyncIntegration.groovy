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
//                    (APP_VERSION, DEFAULT_TRACE_ID, DEFAULT_TIME_ZONE, DEVICE_REGION);
//                    timeZone now derived from hub location with fallback to DEFAULT_TIME_ZONE.
//                    NOTE: regional API routing is binary (US→smartapi.vesync.com,
//                    EU→smartapi.vesync.eu per pyvesync const.py); a full implementation
//                    requires switching the endpoint host, not just the body field. Parked
//                    for v2.1+ — DEVICE_REGION constant left as breadcrumb.
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
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

import java.security.MessageDigest
import groovy.transform.Field

// VeSync API metadata constants — centralised here so version/ID changes are one-line fixes.
// DO NOT change DEFAULT_TRACE_ID to a dynamic value — pyvesync uses "1634265366" verbatim
// and changing it risks triggering VeSync anti-bot detection.
@Field static final String APP_VERSION       = "2.5.1"
@Field static final String DEFAULT_TRACE_ID  = "1634265366"
@Field static final String DEFAULT_TIME_ZONE = "America/Los_Angeles"
// Regional routing is binary: US → smartapi.vesync.com, EU → smartapi.vesync.eu
// (per pyvesync const.py). Full support requires switching the endpoint host — parked for v2.1+.
@Field static final String DEVICE_REGION     = "US"

// updateDevices() poll-method routing map (GitHub issue #3 / Gemini PR #2 suggestion).
// Maps a typeName substring to the VeSync API method that returns device status for that family.
// Iteration order (LinkedHashMap insertion order) is preserved; most-specific keys listed first.
// Keys are frozen by Bug Pattern #9 / RULE19 — driver typeName substrings are frozen once shipped
// because Hubitat associates devices to drivers by name. Adding a new driver family requires a new
// map entry here AND a matching driver whose definition(name:) contains the same substring.
@Field static final Map<String,String> TYPENAME_TO_METHOD = [
    "Tower Fan":    "getTowerFanStatus",
    "Pedestal Fan": "getFanStatus",
    "Humidifier":   "getHumidifierStatus"
]
@Field static final String DEFAULT_POLL_METHOD = "getPurifierStatus"

metadata {
    definition(
        name: "VeSync Integration",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson (original); Dan Cox (fork: Vital 200S, Superior 6000S, parent fixes); elfege (contributor)",
        description: "Integrates Levoit air purifiers and humidifiers with Hubitat Elevation via VeSync cloud API",
        version: "2.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Actuator"
            attribute "heartbeat", "string";  
        }

    preferences {
        input(name: "email", type: "string", title: "<font style='font-size:12px; color:#1a77c9'>Email Address</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Email Address</font>", defaultValue: "", required: true);
        input(name: "password", type: "password", title: "<font style='font-size:12px; color:#1a77c9'>Password</font>", description: "<font style='font-size:12px; font-style: italic'>VeSync Account Password</font>", defaultValue: "");
		input("refreshInterval", "number", title: "<font style='font-size:12px; color:#1a77c9'>Refresh Interval</font>", description: "<font style='font-size:12px; font-style: italic'>Poll VeSync status every N seconds</font>", required: true, defaultValue: 30)
        input("descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true, required: false)
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
        input("verboseDebug", "bool", title: "Verbose API response logging? (Logs full response body of every VeSync API call. Useful for diagnosing 'VeSync changed an API field' issues. Sanitized automatically. Heavy on log buffer — leave off unless triaging.)", defaultValue: false, required: false)
        input("note", "hidden", title: "<b>IMPORTANT:</b> After saving driver code changes, reboot your hub for reliable HTTP operation. Connection pool errors may occur until reboot.")

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

    // Turn off debug log in 30 minutes
    if (settings?.debugOutput) runIn(1800, "logDebugOff");
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
 * Cron syntax: "0/N * * * * ?" -- every N seconds (Hubitat quartz-cron subset).
 * Minimum granularity is 1 second; any interval < 1 is clamped to 1.
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
    schedule("0/${interval} * * * * ?", "updateDevices")
    state.scheduleVersion = "2.2"
    logDebug "Poll schedule armed: every ${interval}s (schedule()-based, persists across reboots)"
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
            } else {
                logError "${label}: IllegalStateException - ${e.message}"
            }
            return false
        }
        catch (Exception e) {
            logError "${label}: ${e.toString()}"
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
            uri: "https://smartapi.vesync.com/cloud/v1/user/login",
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
                logInfo "Logged in to VeSync"
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

    // Stop if driver is reloading
    if (state.driverReloading) {
        logDebug "Skipping updateDevices - driver reloading"
        return false
    }

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

    sendEvent(name: "heartbeat", value: "syncing", isStateChange: true, descriptionText: "Waiting on update from VeSync servers.")
    logInfo "Heartbeat: syncing"

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
            // Routing is driven by TYPENAME_TO_METHOD (defined near the top of this file alongside
            // the other @Field constants). Keys are typeName substrings; iteration order (insertion
            // order, most-specific first) is preserved by Groovy's LinkedHashMap default.
            // DEFAULT_POLL_METHOD ("getPurifierStatus") covers all purifiers and any unrecognized type.
            String typeName = dev.typeName ?: dev.name ?: ""
            String method = TYPENAME_TO_METHOD.find { typeName.contains(it.key) }?.value ?: DEFAULT_POLL_METHOD
            def command = [
                "method": method,
                "source": "APP",
                "data": [:]
            ]

            sendBypassRequest(dev, command) { resp ->
                if (checkHttpResponse("update", resp))
                {
                    def status = resp.data.result
                    if (status == null)
                        logError "No status returned from ${method}: ${resp.msg}"
                    else
                        dev.update(status, getChildDevice(dni+"-nl"))
                }
            }
        }
        catch (exc)
        {
            logError exc.toString();
        }
    }

    sendEvent(name: "heartbeat", value: "synced", isStateChange: true, descriptionText: "Update received from VeSync servers.")
    logInfo "Heartbeat: synced"

    // Schedule a call to the timeout method. This will cancel any outstanding
    // schedules.
    runIn(5 * (int)settings.refreshInterval, "timeOutLevoit")
}

private deviceType(code) {
    switch(code)
    {
        case "Core200S": 
        case "LAP-C201S-AUSR":
        case "LAP-C201S-WUSR":
            return "200S";
        case "Core300S": 
        case "LAP-C301S-WJP":
            return "300S";
        case "Core400S": 
        case "LAP-C401S-WJP":
        case "LAP-C401S-WUSR":
        case "LAP-C401S-WAAA":
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
        // Superior 6000S — pyvesync VeSyncSuperior6000S supports LEH-S601S-WUS/-WUSR/-WEUR and LEH-S602S-WUS
        case ~/LEH-S60[12]S-(WUS|WUSR|WEUR)/:
            return "V601S";
        // Classic 300S Humidifier — pyvesync VeSyncHumid200300S, dev_types Classic300S + LUH-A601S-*
        case "Classic300S":
        case "LUH-A601S-WUSB":
        case "LUH-A601S-AUSW":
            return "A601S";
        // OasisMist 450S US — same pyvesync class as Classic 300S; LUH-O601S-* shares this map entry
        // per SmartThings + Homebridge cross-check (4 model codes confirmed in device_map.py)
        case "LUH-O451S-WUS":
        case "LUH-O451S-WUSR":
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
            return "PEDESTALFAN";
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
    if (code in ["Core200S", "Core300S", "Core400S", "Core600S", "Vital200S", "Classic300S"]) return true
    return false
}

private Boolean getDevices() {
    return retryableHttp("getDevices", 3) {
        def params = [
            uri: "https://smartapi.vesync.com/cloud/v1/deviceManaged/devices",
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
                             dtype == "V100S" || dtype == "A601S" || dtype == "O451S" || dtype == "TOWERFAN" || dtype == "PEDESTALFAN") {
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

                for (device in resp.data.result.list) {
                    def dtype = deviceType(device.deviceType);
                    def equip1 = getChildDevice(device.cid)

                    if (dtype == "200S") {
                        def equip2 = getChildDevice(device.cid+"-nl")

                        if (equip2 == null) {
                            equip2 = addChildDevice("Levoit Core200S Air Purifier Light", device.cid+"-nl", [name: device.deviceName + " Light", label: device.deviceName + " Light", isComponent: false]);
                            // Defensive validation: addChildDevice may return a phantom handle if the DNI was
                            // recently deleted and Hubitat's purge has not completed (platform race condition).
                            def verifyLight = getChildDevice(device.cid + "-nl")
                            if (verifyLight == null) {
                                logError "addChildDevice for ${device.deviceName} Light (${device.cid}-nl) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip2 = verifyLight
                            equip2.updateDataValue("configModule", device.configModule);
                            equip2.updateDataValue("cid", device.cid);
                            equip2.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} Light (Levoit Core200S Air Purifier Light)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} Light / " + dtype;
                            equip2.name = device.deviceName + " Light";
                            equip2.label = device.deviceName + " Light";
                        }

                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core200S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
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
                            logInfo "Added child device: ${device.deviceName} (Levoit Core200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "300S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core300S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core300S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "400S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core400S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core400S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "600S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Core600S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Core600S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "V200S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Vital 200S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Vital 200S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "V601S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Superior 6000S Humidifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Superior 6000S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "V100S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Vital 100S Air Purifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Vital 100S Air Purifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "A601S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Classic 300S Humidifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Classic 300S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "O451S") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit OasisMist 450S Humidifier", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit OasisMist 450S Humidifier)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "TOWERFAN") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Tower Fan", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Tower Fan)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "PEDESTALFAN") {
                        if (equip1 == null) {
                            logDebug "Adding ${device.deviceName}"
                            equip1 = addChildDevice("Levoit Pedestal Fan", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
                            def verify1 = getChildDevice(device.cid)
                            if (verify1 == null) {
                                logError "addChildDevice for ${device.deviceName} (${device.cid}) appeared to succeed but the device is not queryable. This usually means the DNI was recently deleted and Hubitat's purge has not completed. Try forceReinitialize again in a minute."
                                continue
                            }
                            equip1 = verify1
                            equip1.updateDataValue("configModule", device.configModule);
                            equip1.updateDataValue("cid", device.cid);
                            equip1.updateDataValue("uuid", device.uuid);
                            logInfo "Added child device: ${device.deviceName} (Levoit Pedestal Fan)"
                        }
                        else {
                            logDebug "Updating ${device.deviceName} / " + dtype;
                            equip1.name = device.deviceName;
                            equip1.label = device.deviceName;
                        }
                    }
                    else if (dtype == "GENERIC") {
                        if (isLevoitClimateDevice(device.deviceType)) {
                            if (equip1 == null) {
                                logDebug "Adding ${device.deviceName} (unrecognized model ${device.deviceType} -- using Generic driver)"
                                equip1 = addChildDevice("Levoit Generic Device", device.cid, [name: device.deviceName, label: device.deviceName, isComponent: false]);
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
                            }
                        } else {
                            logInfo "Skipping unsupported device type: ${device.deviceType} (${device.deviceName}) -- not a Levoit air purifier or humidifier. If this is a Levoit device, please file a new-device-support issue."
                        }
                    }
                }

                state.deviceList = newList
                runIn(5 * (int)settings.refreshInterval, "timeOutLevoit")

                // Delay before first device update to ensure connection pool is stable
                runIn(10, "updateDevices")
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
        uri: "https://smartapi.vesync.com/cloud/v2/deviceManaged/bypassV2",
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
            "deviceRegion": DEVICE_REGION
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
                }
            } finally {
                state.remove('reAuthInProgress')
            }
        }
        // Not an auth failure (or re-auth failed): pass through to inner closure
        tracingClosure(resp)
    }

    try {
        httpPost(params, effectiveClosure)
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
        logError "sendBypassRequest: ${e.toString()}"
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