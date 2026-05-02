/*
 * MIT License
 *
 * Copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
 * Built atop the VeSyncIntegration parent framework by Niklas Gustafsson.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 */

/*
 *  Levoit Dual 200S Humidifier (LUH-D301S series) -- Hubitat driver
 *
 *  Targets:    Dual200S (device literal name), LUH-D301S-WUSR, LUH-D301S-WJP,
 *              LUH-D301S-WEU, LUH-D301S-KEUR
 *  Marketing:  Levoit Dual 200S Smart Humidifier (2L dual-nozzle ultrasonic)
 *  Reference:  pyvesync VeSyncHumid200300S + Dual200S.yaml fixture (commit c98729c)
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  KEY DIFFERENCES from Classic 300S (same VeSyncHumid200300S class):
 *    - Mist range: 1-2 only (Classic 300S has 1-9)
 *      Confirmed: pyvesync device_map.py LUH-D301S entry: mist_levels=list(range(1, 3))
 *    - Mist modes: auto + manual only (Classic 300S has auto/sleep/manual)
 *      Confirmed: pyvesync device_map.py mist_modes={AUTO:'auto', MANUAL:'manual'}
 *      -- sleep is NOT in the Dual 200S mode map; setMode("sleep") rejected.
 *    - Features: AUTO_STOP only; no NIGHTLIGHT, no WARM_MIST
 *      Confirmed: pyvesync device_map.py features=[HumidifierFeatures.AUTO_STOP]
 *    - All API payload conventions are identical to Classic 300S (VeSyncHumid200300S class)
 *
 *  NIGHTLIGHT NOTE:
 *    The pyvesync canonical call_json_humidifiers.py Dual200S response DOES include
 *    night_light_brightness in the response fields (same as Classic 300S base class).
 *    However, device_map.py LUH-D301S features=[AUTO_STOP] -- HumidifierFeatures.NIGHTLIGHT
 *    is NOT listed. HA issue #160387 references LUH-D301S-WUSR in a nightlight context,
 *    but that report concerns the "device shows API field but feature flag missing" ambiguity,
 *    same pattern as pyvesync #500 was for OasisMist 450S. We follow the conservative approach:
 *    read and emit night_light_brightness if the API returns it (passive read-path),
 *    but do NOT expose a setNightLight command (no-feature-flag protection).
 *    If community users confirm the command works on their hardware, we can promote it to
 *    a command in a future release. See CROSS-CHECK block in applyStatus for full rationale.
 *
 *  History:
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-27: v2.1  Community fork [PREVIEW v2.2]. Built from canonical pyvesync payloads
 *                      (Dual200S.yaml fixture commit c98729c + device_map.py LUH-D301S entry).
 *                      Capabilities: Switch, RelativeHumidityMeasurement, Sensor, Actuator,
 *                      Refresh. Setters: setMode (auto/manual -- NO sleep per device_map.py),
 *                      setMistLevel (1-2, clamped -- NOT 1-9 like Classic 300S), setHumidity
 *                      (30-80%), setDisplay, setAutoStop, toggle. Reads: humidity, targetHumidity,
 *                      mistLevel, waterLacks, displayOn, autoStopEnabled, autoStopReached,
 *                      nightLightBrightness (read-only; passive; no setNightLight command).
 *                      Switch payload: humidifier shape {enabled, id:0}. setVirtualLevel:
 *                      {id:0, level:N, type:'mist'}. Multi-firmware auto-mode try-fallback
 *                      with cache (same pattern as LV600S -- pyvesync PR #505 applies here too).
 */

#include level99.LevoitDiagnostics

metadata {
    definition(
        name: "Levoit Dual 200S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.2] Levoit Dual 200S (LUH-D301S-WUSR/-WJP/-WEU/-KEUR + 'Dual200S' literal) -- mist 1-2 (2-level only), target humidity 30-80%, auto/manual modes, auto-stop, display; canonical pyvesync VeSyncHumid200300S payloads. No night-light command (feature flag absent); no sleep mode (not in device_map.py mist_modes). Night-light brightness read passively if API returns it.",
        version: "2.4",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        // CROSS-CHECK [pyvesync device_map.py LUH-D301S entry]:
        //   mode attribute: only "auto" and "manual" are valid per pyvesync device_map.py.
        //   mist_modes={HumidifierModes.AUTO:'auto', HumidifierModes.MANUAL:'manual'}.
        //   Sleep mode is NOT listed for Dual 200S. The setMode command exposes auto/manual only.
        //   Refutation: community user reports sleep mode works on their Dual 200S hardware
        //     --> add 'sleep' to the setMode enum and setMode validation; file pyvesync issue.
        attribute "mode",             "string"   // auto | manual
        attribute "mistLevel",        "number"   // 0-2 (0 = inactive; max=2 per device_map.py)
        attribute "targetHumidity",   "number"   // 30-80 %
        attribute "waterLacks",       "string"   // yes | no
        attribute "displayOn",        "string"   // on | off
        attribute "autoStopEnabled",  "string"   // on | off
        attribute "autoStopReached",  "string"   // yes | no
        // night_light_brightness: read-passively (if API returns it) -- no setter command exposed.
        // pyvesync device_map.py LUH-D301S features=[AUTO_STOP] -- NIGHTLIGHT not listed.
        // Field is present in the pyvesync call_json_humidifiers.py Dual200S response shape
        // (inherited from VeSyncHumid200300S base class), but absent from the features flag.
        // We emit it as an attribute so users can see it and report if the command also works.
        attribute "nightLightBrightness", "number"  // 0 | 50 | 100 (read-only; no setter)
        attribute "info",             "string"   // HTML summary for dashboard tiles

        // CROSS-CHECK [pyvesync device_map.py LUH-D301S mist_modes]:
        //   Decision: expose only "auto" and "manual" (not "sleep").
        //   mist_modes for Dual200S = {HumidifierModes.AUTO:'auto', HumidifierModes.MANUAL:'manual'}
        //   Sleep mode is NOT listed. The Classic 300S has sleep (LUH-A601S mist_modes includes
        //   HumidifierModes.SLEEP). This is a real hardware difference, not a pyvesync omission.
        //   Source: pyvesync device_map.py LUH-D301S HumidifierMap entry (commit c98729c).
        //   Refutation: community user reports "sleep" mode works on their hardware -->
        //     add to enum and validation; file a pyvesync bug report to update the mode map.
        command "setMode",          [[name:"Mode*",    type:"ENUM",   constraints:["auto","manual"]]]
        // CROSS-CHECK [pyvesync device_map.py LUH-D301S mist_levels]:
        //   Decision: enforce mist range 1-2 (NOT 1-9 like Classic 300S).
        //   mist_levels=list(range(1, 3)) -> [1, 2]. This is the canonical pyvesync range.
        //   Source: pyvesync device_map.py LUH-D301S HumidifierMap (commit c98729c).
        //   Refutation: community user reports level 3+ is accepted by their device -->
        //     expand range; update setMistLevel clamp and info tile "1-2" annotation.
        command "setMistLevel",     [[name:"Level*",   type:"NUMBER", description:"1-2"]]
        command "setHumidity",      [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        command "setDisplay",       [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",      [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        // NOTE: setNightLight intentionally absent -- LUH-D301S features=[AUTO_STOP], no NIGHTLIGHT.
        // HA issue #160387 mentions LUH-D301S-WUSR in a nightlight context, but we do NOT ship
        // the command without explicit pyvesync feature-flag confirmation. If community users
        // confirm setNightLightBrightness works on their Dual 200S, promote to command in next release.
        // NOTE: setWarmMistLevel intentionally absent -- Dual 200S has no warm mist hardware.

        attribute "diagnostics", "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online", "string"
        command "captureDiagnostics"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput",           "bool", title: "Enable debug logging?",                    defaultValue: false
    }
}

// ---------- Lifecycle ----------
def installed(){ logDebug "Installed ${settings}"; updated() }
def updated(){
    logDebug "Updated ${settings}"
    // state.clear() above removes all state including firmwareVariant -- firmware updates get
    // re-detected on next setMode("auto") call (see sendModeRequest fallback logic below).
    state.clear(); unschedule(); initialize()
    state.driverVersion = "2.4"
    runIn(3, "refresh")
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

// ---------- Power ----------
// Humidifier switch payload: {enabled: bool, id: 0}
// NOT purifier shape {powerSwitch: int, switchIdx: 0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [enabled: true, id: 0], "setSwitch(enabled=true)")
    if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
    else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [enabled: false, id: 0], "setSwitch(enabled=false)")
    if (httpOk(resp)) { logInfo "Power off"; state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off") }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
}

// state.lastSwitchSet preferred over device.currentValue() to avoid the read-after-write
// race (the new event from on()/off() may not be queryable yet on a same-tick toggle()).
// Falls back to device.currentValue("switch") when state isn't seeded yet (first-call case).
def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// CROSS-CHECK [pyvesync Dual200S.yaml (commit c98729c) / device_map.py LUH-D301S entry]:
//   Decision: expose two user-facing modes (auto, manual). Sleep is NOT exposed.
//     pyvesync device_map.py LUH-D301S mist_modes contains only AUTO and MANUAL.
//     Classic 300S (LUH-A601S) adds SLEEP; Dual 200S does not.
//   Write-path: try-canonical-then-fallback-with-cache (multi-firmware support -- same
//     pattern as LV600S, since pyvesync PR #505 reports some EU firmware reports
//     "humidity" instead of "auto" for the auto mode. LUH-D301S-WEU/-KEUR are EU SKUs,
//     so the same firmware variant risk applies here as to LUH-A602S-WEU/-WEUR.)
//     - Canonical payload: {mode: "auto"} (per Dual200S.yaml fixture).
//     - Alternate payload: {mode: "humidity"} (EU firmware variant per PR #505 pattern).
//     On first setMode("auto") call, try canonical. On inner-code rejection, try alternate.
//     Cache the accepted variant in state.firmwareVariant for subsequent calls.
//     state.firmwareVariant cleared in updated() so firmware updates are re-detected.
//   Read-path: normalize "auto", "autoPro", "humidity" -> "auto" in user-facing attribute.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LUH-D301S HumidifierMap, mist_modes -- commit c98729c).
//     Dual200S.yaml set_auto_mode payload uses {mode: "auto"}.
//   Refutation: community user with LUH-D301S-WEU reports auto broken even after "humidity"
//     fallback --> both variants tried; log both inner codes; escalate to pyvesync issue.
// Payload: {mode: <value>} -- NOT {workMode: <value>} (Superior 6000S difference)
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    // CROSS-CHECK: only auto and manual are valid for Dual 200S (no sleep per device_map.py)
    if (!(m in ["auto","manual"])) { logError "Invalid mode: ${m} -- must be one of: auto, manual (sleep not supported on Dual 200S)"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    if (m == "auto") {
        // Multi-firmware try-canonical-then-fallback with cache (same as LV600S -- PR #505 risk)
        String preferred = (state.firmwareVariant == "alt") ? "humidity" : "auto"
        sendModeRequest(preferred, "auto", false)
    } else {
        // manual has no known firmware variant issue -- send directly
        def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
        if (httpOk(resp)) {
            state.mode = m
            device.sendEvent(name:"mode", value: m)
            logInfo "Mode: ${m}"
        } else {
            logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
        }
    }
}

// sendModeRequest: internal helper for multi-firmware auto-mode try-fallback logic.
// payloadValue -- the 'mode' field value being sent to the device API ("auto" or "humidity").
// userMode     -- the canonical user-facing mode string to emit if successful ("auto").
// isRetry      -- true when this is the alternate-payload retry (prevents infinite recursion).
private void sendModeRequest(String payloadValue, String userMode, boolean isRetry){
    def resp = hubBypass("setHumidityMode", [mode: payloadValue], "setHumidityMode(${payloadValue})")
    def innerCode = resp?.data?.result?.code
    boolean ok = (resp?.status in [200,201,204]) && (innerCode == null || innerCode == 0)
    if (ok) {
        String detectedVariant = (payloadValue == "auto") ? "std" : "alt"
        if (state.firmwareVariant != detectedVariant) {
            state.firmwareVariant = detectedVariant
            logInfo "Firmware variant auto-detected: ${detectedVariant} (payload '${payloadValue}' accepted). Cached for future setMode calls."
        }
        state.mode = userMode
        device.sendEvent(name:"mode", value: userMode)
        logInfo "Mode: ${userMode}"
    } else if (!isRetry) {
        // Canonical payload rejected -- try alternate once
        String alternate = (payloadValue == "auto") ? "humidity" : "auto"
        logDebug "setMode(${userMode}): payload '${payloadValue}' rejected (inner code: ${innerCode}); trying alternate firmware variant '${alternate}'"
        sendModeRequest(alternate, userMode, true)
    } else {
        // Both variants rejected
        logError "Mode '${userMode}' rejected by both payload variants ('auto' and 'humidity', inner code: ${innerCode}). Check device connectivity or report via GitHub issue."
        recordError("Mode '${userMode}' rejected by both payload variants (inner code: ${innerCode})", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// CROSS-CHECK [pyvesync device_map.py LUH-D301S entry]:
//   Decision: enforce mist range 1-2 (NOT 1-9 like Classic 300S or 1-9 like LV600S).
//   pyvesync device_map.py LUH-D301S: mist_levels=list(range(1, 3)) -> [1, 2].
//   Clamping: Math.max(1, Math.min(2, level)) ensures out-of-range values are silently
//   clamped rather than rejected. This matches pyvesync's own set_mist_level behavior
//   (it clamps to the mist_levels list's max/min before sending).
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LUH-D301S HumidifierMap, mist_levels=list(range(1, 3)), commit c98729c).
//   Refutation: community user reports level 3 or higher is accepted by their Dual 200S
//     --> expand clamp range and update the metadata description string "1-2".
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
// NOTE: field names id/level/type -- NOT levelIdx/virtualLevel/levelType (Superior 6000S)
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(2, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "mist"], "setVirtualLevel(${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"; recordError("Mist level write failed: ${lvl}", [method:"setVirtualLevel"])
    }
}

// ---------- Target humidity ----------
// CROSS-CHECK [pyvesync device_map.py LUH-D301S entry]:
//   Decision: clamp target humidity to 30-80%. The LUH-D301S entry in device_map.py
//   does NOT override humidity_min from the VeSyncHumid200300S base class (which is 30).
//   This differs from OasisMist 450S which has a firmware floor of 40 (pyvesync issue #296).
//   Source: pyvesync device_map.py LUH-D301S HumidifierMap (commit c98729c, no humidity_min).
//   Refutation: community user with Dual 200S reports values below 40 are rejected with
//     API error 11003000 --> raise floor to 40 (same as OasisMist 450S).
// setTargetHumidity payload: {target_humidity: N} -- note snake_case (not camelCase)
def setHumidity(percent){
    logDebug "setHumidity(${percent})"
    Integer p = Math.max(30, Math.min(80, (percent as Integer) ?: 50))
    def resp = hubBypass("setTargetHumidity", [target_humidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"; recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
}

// ---------- Display ----------
// setDisplay payload: {state: bool} -- NOT {screenSwitch: int} (Superior 6000S)
// Same as Classic 300S, OasisMist 450S, LV600S (all VeSyncHumid200300S class)
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setDisplay", [state: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
}

// ---------- Auto-stop ----------
// setAutomaticStop payload: {enabled: bool} -- NOT {autoStopSwitch: int} (Superior 6000S)
// Same as Classic 300S, OasisMist 450S, LV600S (all VeSyncHumid200300S class)
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutomaticStop"])
    }
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// below) passes data.result already. The peel-while-loop in applyStatus handles both shapes
// defensively, but the data-flow asymmetry means applyStatus's first peel iteration is doing
// different work depending on entry point.
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getHumidifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getHumidifierStatus"; recordError("No status returned from getHumidifierStatus", [method:"update"]) }
        else applyStatus(status)
    }
}

// 1-arg parent callback
def update(status){
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg parent callback -- REQUIRED (BP#1); parent always calls with two args
// nightLight parameter accepted but ignored -- Dual 200S nightlight command is not exposed
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- Dual 200S has no nightlight command)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated
    // from older Type without Save (forward-compat -- BP#12)
    // Insertion point: top of applyStatus() per V1-humidifier-base pref-seed convention
    // (same as Classic 300S, LV600S, OasisMist 450S -- all VeSyncHumid200300S class).
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- humidifier bypassV2 responses can be double-wrapped (BP#3).
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // Dual 200S is typically single-wrapped like Classic 300S, but the peel loop is defensive.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // Dual 200S response uses `enabled` (boolean), NOT `powerSwitch` (int)
    // Same humidifier shape as Classic 300S, LV600S, OasisMist 450S (all VeSyncHumid200300S)
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- from configuration.auto_target_humidity ----
    // Same nested location as Classic 300S, LV600S, OasisMist 450S (VeSyncHumid200300S class)
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // CROSS-CHECK [pyvesync Dual200S.yaml / device_map.py LUH-D301S entry]:
    //   Read path: normalize all known auto-mode firmware aliases to "auto".
    //   Firmware may report "auto" (canonical), "autoPro" (some devices), or "humidity"
    //   (EU firmware per PR #505 pattern -- LUH-D301S-WEU/-KEUR are EU SKUs so the risk applies).
    //   All three are normalized to "auto" for user-facing consistency.
    //   "manual" passes through as-received.
    //   We also normalize "sleep" -> emit as-received with a debug note (Dual 200S shouldn't
    //   report sleep mode, but we don't crash on unexpected firmware behavior).
    //   Rationale: user chose "auto" from the driver command; seeing "humidity" or "autoPro"
    //   as the attribute value is confusing and breaks rule-based automations.
    String rawMode = (r.mode ?: "manual") as String
    String userMode = (rawMode in ["humidity", "autoPro"]) ? "auto" : rawMode
    if (rawMode == "sleep") {
        // Dual 200S device_map.py has no sleep mode -- log a debug note if firmware reports it
        logDebug "applyStatus: unexpected mode 'sleep' from Dual 200S firmware -- emitting as-received; report to GitHub if reproducible"
    }
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

    // ---- Mist level -- use mist_virtual_level (active running level) ----
    // mist_level = last-set manual level; mist_virtual_level = currently active
    // CROSS-CHECK [pyvesync device_map.py LUH-D301S mist_levels=list(range(1,3))]:
    //   We trust the API-reported value as-received. We do NOT clamp on read because the
    //   device's own report is authoritative -- if it says level 2, we emit 2. If it says 0
    //   (device off state), we emit 0. Clamping (1-2) applies on WRITE only (setMistLevel).
    Integer mistVirtual = null
    if (r.mist_virtual_level != null) {
        mistVirtual = r.mist_virtual_level as Integer
    } else if (r.mist_level != null) {
        mistVirtual = r.mist_level as Integer
    }
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Water lacks ----
    def waterLacksRaw = r.water_lacks
    boolean waterLacks = (waterLacksRaw instanceof Boolean) ? waterLacksRaw : ((waterLacksRaw as Integer) == 1)
    String waterLacksStr = waterLacks ? "yes" : "no"
    if (state.lastWaterLacks != waterLacksStr) {
        if (waterLacks) logInfo "Water reservoir empty"
    }
    state.lastWaterLacks = waterLacksStr
    device.sendEvent(name:"waterLacks", value: waterLacksStr)

    // ---- Humidity high indicator ----
    // Not exposed as a primary attribute (Classic 300S does expose this; we omit it from
    // Dual 200S as it's not in the primary feature set — add a humidityHigh attribute
    // if community requests it).

    // ---- Auto-stop reached ----
    def autoStopReach = r.automatic_stop_reach_target
    boolean autoStopBool = (autoStopReach instanceof Boolean) ? autoStopReach : ((autoStopReach as Integer) == 1)
    device.sendEvent(name:"autoStopReached", value: autoStopBool ? "yes" : "no")

    // ---- Auto-stop enabled -- from configuration.automatic_stop ----
    Boolean autoStopEnabled = null
    if (r.configuration instanceof Map && r.configuration.automatic_stop != null) {
        def asRaw = r.configuration.automatic_stop
        autoStopEnabled = (asRaw instanceof Boolean) ? asRaw : ((asRaw as Integer) == 1)
    }
    if (autoStopEnabled != null) device.sendEvent(name:"autoStopEnabled", value: autoStopEnabled ? "on" : "off")

    // ---- Display ----
    // CROSS-CHECK [HA cross-check finding #8 / Classic 300S precedent]:
    //   Decision: read 'display' first, then fall back to 'indicator_light_switch', then
    //     configuration.display. Same defensive chain as Classic 300S (same class).
    //   Source: Classic 300S CROSS-CHECK HA finding #8 (same VeSyncHumid200300S class).
    //   Refutation: community user reports 'display' absent and neither fallback works -->
    //     investigate firmware variant and add a new key to the chain.
    def displayRaw = null
    if (r.display != null) {
        displayRaw = r.display
    } else if (r.indicator_light_switch != null) {
        logDebug "applyStatus: 'display' missing, falling back to 'indicator_light_switch'"
        displayRaw = r.indicator_light_switch
    } else if (r.configuration instanceof Map && r.configuration.display != null) {
        displayRaw = r.configuration.display
    }
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // ---- Night-light brightness (read-only / passive) ----
    // CROSS-CHECK [pyvesync device_map.py LUH-D301S features / HA issue #160387]:
    //   Decision: emit night_light_brightness as a read-only attribute if the API returns it.
    //     Do NOT expose a setNightLight command (feature flag absent = conservative approach).
    //   Rationale: pyvesync device_map.py LUH-D301S features=[HumidifierFeatures.AUTO_STOP].
    //     HumidifierFeatures.NIGHTLIGHT is NOT in the list. However, the pyvesync canonical
    //     call_json_humidifiers.py Dual200S response shape DOES include night_light_brightness
    //     (inherited from VeSyncHumid200300S base class response model). This is the same
    //     ambiguity as pyvesync #500 for OasisMist 450S: field present in response shape but
    //     not in features flag. HA issue #160387 references LUH-D301S-WUSR in a nightlight
    //     context, but the issue is about API field presence vs. feature flag discrepancy, not
    //     a confirmed "command works on hardware" report.
    //     Conservative approach: passive read (emit what the device reports) but no write command.
    //     This lets users see the value via the attribute and report if the setter also works.
    //   Source: pyvesync device_map.py LUH-D301S (commit c98729c, features=[AUTO_STOP]);
    //     pyvesync call_json_humidifiers.py Dual200S response (includes night_light_brightness).
    //   Refutation: community user with Dual 200S confirms setNightLightBrightness command
    //     is accepted by the device --> add setNightLight command (discrete 3-step: off/dim/bright)
    //     matching Classic 300S implementation; update lint_config frozen_driver_names unchanged.
    if (r.night_light_brightness != null) {
        Integer nlRaw = r.night_light_brightness as Integer
        device.sendEvent(name:"nightLightBrightness", value: nlRaw)
    }

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-2)"
    parts << "Mode: ${userMode}"
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logWarn(msg){ log.warn msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

// BP16 debug watchdog — auto-disable stuck debugOutput after hub reboot
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

// Hub/parent call wrapper -- matches sibling driver pattern
private hubBypass(method, Map data=[:], tag=null, cb=null){
    def rspObj = [status: -1, data: null]
    parent.sendBypassRequest(device, [method: method, source: "APP", data: data]) { resp ->
        rspObj = [status: resp?.status, data: resp?.data]
        def inner = resp?.data?.result?.code
        if (tag) logDebug "${tag} -> HTTP ${resp?.status}, inner ${inner}"
        if (cb) cb(resp)
    }
    return rspObj
}

private boolean httpOk(resp){
    if (!resp) return false
    def st = resp.status as Integer
    if (st in [200,201,204]){
        def inner = resp?.data?.result?.code
        if (inner == null || inner == 0) return true
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"; recordError("HTTP ${st}", [site:"httpOk"])
    return false
}

// ------------- END -------------
