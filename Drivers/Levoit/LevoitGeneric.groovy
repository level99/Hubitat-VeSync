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
 *  Levoit Generic Device — Hubitat driver
 *
 *  Purpose:    Fall-through driver for Levoit devices whose model code is not
 *              yet recognized by the parent's deviceType() switch. Provides
 *              basic best-effort control (power on/off) and diagnostic capture
 *              to accelerate new-model support.
 *
 *  Targets:    Any unrecognized VeSync device (LAP-*, LEH-*, etc.)
 *  Reference:  pyvesync VeSyncBaseDevice
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-25: v2.0  Community fork initial release (Dan Cox). Fall-through diagnostic
 *                      driver for unsupported Levoit models. Capabilities: Switch,
 *                      SwitchLevel, RelativeHumidityMeasurement, AirQuality, Actuator,
 *                      Refresh. Heuristic field parsing: emits events conditioned on which
 *                      fields are present in the response. compat attribute reports detected
 *                      shape ("v2-api purifier (N fields)", "v2-api humidifier (N fields)",
 *                      "unknown shape"). diagnostics attribute holds copy-paste-ready markdown
 *                      for filing a new-device-support issue. captureDiagnostics() command
 *                      tries both getPurifierStatus and getHumidifierStatus and records all
 *                      inner codes + field shapes. Power fallback: tries setSwitch/powerSwitch
 *                      first; falls back to setPower for older V1 API devices.
 */

metadata {
    definition(
        name: "Levoit Generic Device",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Fall-through diagnostic driver for unsupported Levoit models. Provides best-effort power control and diagnostic capture for new-device-support issue filing.",
        version: "2.0",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "SwitchLevel"
        capability "RelativeHumidityMeasurement"
        capability "AirQuality"
        capability "Actuator"
        capability "Refresh"

        attribute "compat", "string"            // detected shape: "v2-api purifier (N fields)" | "v2-api humidifier (N fields)" | "unknown shape"
        attribute "diagnostics", "string"     // copy-paste-ready markdown for new-device-support issue
        attribute "info", "string"            // HTML summary for dashboard tiles
        attribute "mode", "string"            // best-effort mode string if present in response
        attribute "filter", "number"          // filter/wick life % if present
        attribute "airQualityIndex", "number" // numeric AQ index (required by AirQuality capability)

        command "captureDiagnostics"
        command "toggle"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: false
    }
}

def installed(){ logDebug "Installed ${settings}"; updated() }
def updated(){
    logDebug "Updated ${settings}"
    state.clear(); unschedule(); initialize()
    runIn(3, "refresh")
    if (settings?.debugOutput) runIn(1800, "logDebugOff")
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

// ---------- Power ----------

def on(){
    logDebug "on()"
    // Try modern V2 payload first (setSwitch + switchIdx).
    // Only fall back to V1 setPower when inner code is exactly -1, which signals the device
    // rejected this method variant (try the other envelope). Do NOT fall back on rate-limit
    // (-11003), offline (-11304), or auth-failure (-11000) — those are real errors and masking
    // them with a V1 retry hides the root cause in user log captures.
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(power=1)")
    if (httpOk(resp)) {
        logInfo "Power on"
        device.sendEvent(name:"switch", value:"on")
    } else if (shouldFallback(resp)) {
        logDebug "setSwitch inner code -1 — device rejected V2 method; trying V1 setPower fallback"
        def resp2 = hubBypass("setPower", [powerSwitch: 1], "setPower(power=1)")
        if (httpOk(resp2)) {
            logInfo "Power on (V1 fallback)"
            device.sendEvent(name:"switch", value:"on")
        } else {
            logError "Power on failed (setSwitch returned -1; setPower also failed)"
        }
    } else {
        logError "Power on failed (setSwitch returned non-fallback error; see debug log)"
    }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(power=0)")
    if (httpOk(resp)) {
        logInfo "Power off"
        device.sendEvent(name:"switch", value:"off")
    } else if (shouldFallback(resp)) {
        logDebug "setSwitch inner code -1 — device rejected V2 method; trying V1 setPower fallback"
        def resp2 = hubBypass("setPower", [powerSwitch: 0], "setPower(power=0)")
        if (httpOk(resp2)) {
            logInfo "Power off (V1 fallback)"
            device.sendEvent(name:"switch", value:"off")
        } else {
            logError "Power off failed (setSwitch returned -1; setPower also failed)"
        }
    } else {
        logError "Power off failed (setSwitch returned non-fallback error; see debug log)"
    }
}

def toggle(){
    logDebug "toggle()"
    device.currentValue("switch") == "on" ? off() : on()
}

// ---------- SwitchLevel (best-effort pass-through) ----------

def setLevel(val){
    logDebug "setLevel(${val})"
    // Record the level; we can't know which speed/mist command applies until we know
    // the device shape, so just store and emit for rules/automations.
    device.sendEvent(name:"level", value: val as Integer)
    logDebug "setLevel: emitted level=${val} (no API call — unknown device shape; use captureDiagnostics)"
}

// ---------- Refresh ----------

def refresh(){ update() }

// ---------- Update / status (all 3 signatures required — BP#1) ----------

// Self-fetch: try purifier first, then humidifier; use whichever yields data
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getPurifierStatus", [:], "getPurifierStatus")
    if (httpOk(resp) && hasDeviceFields(resp?.data)) {
        applyStatus(resp?.data)
        return
    }
    // Try humidifier status as fallback
    def resp2 = hubBypass("getHumidifierStatus", [:], "getHumidifierStatus")
    if (httpOk(resp2) && hasDeviceFields(resp2?.data)) {
        applyStatus(resp2?.data)
        return
    }
    // Both failed or returned no device fields; still call applyStatus so compat is updated
    if (resp?.data) applyStatus(resp?.data)
    else if (resp2?.data) applyStatus(resp2?.data)
    else logError "No status data returned from either getPurifierStatus or getHumidifierStatus"
}

// 1-arg parent callback
def update(status){
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg parent callback — REQUIRED (Bug Pattern #1)
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored)"
    applyStatus(status)
    return true
}

// ---------- applyStatus — heuristic field parser ----------

def applyStatus(status){
    logDebug "applyStatus()"

    // One-time pref seed: heal descriptionTextEnable=true default for users
    // migrated from older Type without clicking Save Preferences (Bug Pattern #12).
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel: V2-line bypassV2 responses can be double-wrapped.
    // Peel through any [code, result, traceId] envelope layers until device data.
    // Handles single-wrap (purifiers) and double-wrap (humidifiers) transparently.
    // (Bug Pattern #3)
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump — gated by debugOutput
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // Detect device shape by field presence
    String detectedCompat = detectShape(r)
    String lastCompat = state.lastCompat
    if (lastCompat != detectedCompat) {
        logInfo "Device shape detected: ${detectedCompat}"
        state.lastCompat = detectedCompat
    }
    device.sendEvent(name:"compat", value: detectedCompat)

    // --- Power (all known shapes use powerSwitch) ---
    if (r.powerSwitch != null) {
        boolean powerOn = (r.powerSwitch as Integer) == 1
        device.sendEvent(name:"switch", value: powerOn ? "on" : "off")
    }

    // --- Humidity (humidifier shape) ---
    if (r.humidity != null) {
        device.sendEvent(name:"humidity", value: r.humidity as Integer)
    }

    // --- AirQuality / PM2.5 (purifier shape) ---
    // Hubitat AirQuality capability requires the airQualityIndex attribute (number, 0-500).
    // For AQLevel (Levoit 1-4 index): emit airQualityIndex as the raw int AND airQuality as
    // a categorical label, matching the Vital 200S pattern.
    // For PM25 only (no AQLevel): emit airQualityIndex = PM25 value (ug/m3 as proxy int),
    // and skip the categorical mapping because we cannot reliably compute it without the
    // EPA formula and breakpoints that vary by device generation.
    // localAqLabel is kept for use in info HTML below (avoids sendEvent race — Bug Pattern #7).
    String localAqLabel = null
    if (r.AQLevel != null) {
        Integer aqIdx = r.AQLevel as Integer
        device.sendEvent(name:"airQualityIndex", value: aqIdx)
        localAqLabel = ["unknown","good","moderate","poor","very poor"][(aqIdx in 1..4) ? aqIdx : 0]
        device.sendEvent(name:"airQuality", value: localAqLabel)
    } else if (r.PM25 != null) {
        device.sendEvent(name:"airQualityIndex", value: r.PM25 as Integer)
        // airQuality (categorical) not emitted: unknown device's AQLevel scheme
    }

    // --- Level (mist level for humidifiers, fan speed for purifiers) ---
    Integer levelVal = null
    if (r.virtualLevel != null) {
        // Humidifier: virtualLevel 1-9, map to 0-100
        Integer vl = r.virtualLevel as Integer
        levelVal = Math.round((vl - 1) * (100.0 / 8.0)) as Integer
        device.sendEvent(name:"level", value: levelVal)
    } else if (r.manualSpeedLevel != null && r.manualSpeedLevel != 255) {
        // Purifier: manualSpeedLevel 1-4, map to 0-100
        Integer sl = r.manualSpeedLevel as Integer
        levelVal = Math.round((sl - 1) * (100.0 / 3.0)) as Integer
        device.sendEvent(name:"level", value: levelVal)
    } else if (r.fanSpeedLevel != null && r.fanSpeedLevel != 255) {
        Integer sl = r.fanSpeedLevel as Integer
        levelVal = Math.round((sl - 1) * (100.0 / 3.0)) as Integer
        device.sendEvent(name:"level", value: levelVal)
    }

    // --- Mode (best-effort) ---
    String modeVal = null
    if (r.workMode != null) {
        String wm = r.workMode as String
        modeVal = (wm == "autoPro") ? "auto" : wm  // reverse-map Superior 6000S autoPro
    } else if (r.mode != null) {
        modeVal = r.mode as String  // Core-line older field
    }
    if (modeVal != null) {
        device.sendEvent(name:"mode", value: modeVal)
    }

    // --- Filter life (both purifier filterLifePercent and humidifier same) ---
    if (r.filterLifePercent != null) {
        device.sendEvent(name:"filter", value: r.filterLifePercent as Integer)
    }

    // --- Info HTML (for dashboard tiles) ---
    def parts = []
    parts << "Shape: ${detectedCompat}"
    if (r.powerSwitch != null)       parts << "Power: ${r.powerSwitch == 1 ? 'on' : 'off'}"
    if (r.humidity != null)          parts << "Humidity: ${r.humidity as Integer}%"
    if (r.PM25 != null)              parts << "PM2.5: ${r.PM25}µg/m³"
    if (localAqLabel != null)        parts << "Air Quality: ${localAqLabel}"
    if (r.filterLifePercent != null) parts << "Filter: ${r.filterLifePercent as Integer}%"
    if (modeVal != null)             parts << "Mode: ${modeVal}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- captureDiagnostics ----------
// Fetches both getPurifierStatus and getHumidifierStatus, recording inner codes
// and field shapes for pasting into a new-device-support GitHub issue.

def captureDiagnostics(){
    logDebug "captureDiagnostics()"
    logInfo "Starting diagnostic capture — fetching both getPurifierStatus and getHumidifierStatus"

    String modelCode = device.getDataValue("deviceType") ?: "UNKNOWN"
    String cid       = device.getDataValue("cid") ?: "UNKNOWN"
    String devName   = device.getName() ?: "UNKNOWN"

    def sb = new StringBuilder()
    sb.append("### Levoit Generic Device Diagnostic Capture\n\n")
    sb.append("**Device:** ${devName}  \n")
    sb.append("**Model code (VeSync deviceType):** `${modelCode}`  \n")
    sb.append("**Driver:** Levoit Generic Device (fall-through)  \n")
    sb.append("**Capture timestamp:** ${new Date()}  \n\n")

    // --- getPurifierStatus ---
    sb.append("#### getPurifierStatus response\n\n")
    def pr = runProbeMethod("getPurifierStatus")
    sb.append("- HTTP status: `${pr.httpStatus}`\n")
    sb.append("- Inner code: `${pr.innerCode}`\n")
    sb.append("- Outer code: `${pr.outerCode}`\n")
    sb.append("- Peel depth: `${pr.peelDepth}`\n")
    sb.append("- Field count: `${pr.fieldCount}`\n")
    if (pr.fieldKeys) sb.append("- Fields: `${pr.fieldKeys}`\n")
    if (pr.errorMsg)  sb.append("- Error: ${pr.errorMsg}\n")
    sb.append("\n")

    // --- getHumidifierStatus ---
    sb.append("#### getHumidifierStatus response\n\n")
    def hr = runProbeMethod("getHumidifierStatus")
    sb.append("- HTTP status: `${hr.httpStatus}`\n")
    sb.append("- Inner code: `${hr.innerCode}`\n")
    sb.append("- Outer code: `${hr.outerCode}`\n")
    sb.append("- Peel depth: `${hr.peelDepth}`\n")
    sb.append("- Field count: `${hr.fieldCount}`\n")
    if (hr.fieldKeys) sb.append("- Fields: `${hr.fieldKeys}`\n")
    if (hr.errorMsg)  sb.append("- Error: ${hr.errorMsg}\n")
    sb.append("\n")

    // --- Detected shape ---
    sb.append("#### Interpretation\n\n")
    String bestCompat = (pr.fieldCount > hr.fieldCount) ? pr.compat : hr.compat
    if (pr.fieldCount == 0 && hr.fieldCount == 0) bestCompat = "unknown shape — both methods returned no device fields"
    sb.append("- Best-guess compat: **${bestCompat}**\n\n")

    sb.append("---\n")
    sb.append("_Paste this block into the [new-device-support issue template]")
    sb.append("(https://github.com/level99/Hubitat-VeSync/issues/new?template=new_device_support.yml)_\n")

    String diagText = sb.toString()
    device.sendEvent(name:"diagnostics", value: diagText)
    logInfo "Diagnostic capture complete — see 'diagnostics' attribute. Detected: ${bestCompat}"
    logDebug "captureDiagnostics: ${diagText}"
}

// ---------- Internal helpers ----------

/**
 * runProbeMethod — fires one bypassV2 call, peels the envelope, and returns
 * a metadata map suitable for inserting into the diagnostics string.
 * All fields redacted-safe: no credentials included.
 */
private Map runProbeMethod(String method){
    def result = [
        httpStatus: -1,
        outerCode:  null,
        innerCode:  null,
        peelDepth:  0,
        fieldCount: 0,
        fieldKeys:  null,
        compat:     "unknown shape",
        errorMsg:   null
    ]
    try {
        def resp = hubBypass(method, [:], "captureDiagnostics/${method}")
        result.httpStatus = resp?.status as Integer
        result.outerCode  = resp?.data?.code
        result.innerCode  = resp?.data?.result?.code

        def r = resp?.data?.result ?: [:]
        int pg = 0
        while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && pg < 4) {
            r = r.result; pg++
        }
        result.peelDepth  = pg
        result.fieldCount = (r instanceof Map) ? r.size() : 0
        result.fieldKeys  = (r instanceof Map && r.size() > 0) ? r.keySet().sort().join(", ") : null
        result.compat     = detectShape(r instanceof Map ? r : [:])
    } catch (Exception e) {
        result.errorMsg = e.message?.take(200)
    }
    return result
}

/**
 * detectShape — heuristic: classify the peeled device-data map.
 * Returns a human-readable string for the compat attribute and diagnostics.
 */
private String detectShape(Map r){
    if (!r || r.size() == 0) return "unknown shape"
    int n = r.size()
    // Humidifier indicators: humidity, mistLevel, virtualLevel, targetHumidity
    int humidifierHits = 0
    if (r.containsKey('humidity'))       humidifierHits++
    if (r.containsKey('mistLevel'))      humidifierHits++
    if (r.containsKey('virtualLevel'))   humidifierHits++
    if (r.containsKey('targetHumidity')) humidifierHits++
    if (r.containsKey('waterLacksState')) humidifierHits++
    if (r.containsKey('dryingMode'))     humidifierHits++

    // Purifier indicators: PM25, AQLevel, fanSpeedLevel, manualSpeedLevel, filterLifePercent + no humidity
    int purifierHits = 0
    if (r.containsKey('PM25'))              purifierHits++
    if (r.containsKey('AQLevel'))           purifierHits++
    if (r.containsKey('fanSpeedLevel'))     purifierHits++
    if (r.containsKey('manualSpeedLevel'))  purifierHits++
    if (r.containsKey('filterLifePercent')) purifierHits++

    if (humidifierHits >= 2) return "v2-api humidifier (${n} fields)"
    if (purifierHits >= 2)   return "v2-api purifier (${n} fields)"
    // Has powerSwitch but not enough other indicators
    if (r.containsKey('powerSwitch') && n >= 3) return "v2-api unknown type (${n} fields)"
    return "unknown shape"
}

/**
 * hasDeviceFields — returns true if the status response contains what looks like
 * device-level data (has powerSwitch or humidity, at minimum).
 * Used in update() self-fetch to decide whether a method "worked".
 */
private boolean hasDeviceFields(data){
    if (!data) return false
    def r = data?.result ?: [:]
    int pg = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && pg < 4) {
        r = r.result; pg++
    }
    return r instanceof Map && (r.containsKey('powerSwitch') || r.containsKey('humidity') || r.containsKey('PM25'))
}

def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

// Hub/parent call wrapper
private hubBypass(method, Map data=[:], tag=null, cb=null){
    def rspObj = [status: -1, data: null]
    parent.sendBypassRequest(device, [method: method, source:"APP", data: data]) { resp ->
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
    logError "HTTP ${st}"
    return false
}

/**
 * shouldFallback — returns true only when a V2 setSwitch call was received by the
 * VeSync transport (HTTP 200-204) but rejected at the device level with inner code -1.
 * Inner code -1 is the canonical "this method variant doesn't apply to your device;
 * try the older V1 envelope" signal.
 *
 * Deliberately returns false for:
 *   -11000 — auth failure (re-login needed, not a method-envelope mismatch)
 *   -11003 — rate limit (back off, don't retry with V1)
 *   -11304 — device offline (V1 won't help)
 *   any other non-zero code — real error; log it, don't mask with V1 fallback
 */
private boolean shouldFallback(resp){
    if (!resp) return false
    def st = resp?.status as Integer
    if (!(st in [200, 201, 204])) return false
    def inner = resp?.data?.result?.code
    return (inner as Integer) == -1
}

// ------------- END -------------
