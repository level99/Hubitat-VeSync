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
 *  Levoit Classic 300S Humidifier (LUH-A601S) — Hubitat driver
 *
 *  Targets:    Classic300S (device name), LUH-A601S-WUSB, LUH-A601S-AUSW
 *  Marketing:  Levoit Classic 300S Smart Humidifier
 *  Reference:  pyvesync VeSyncHumid200300S + Classic300S.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
 *                      LevoitDiagnostics library. Added recordError() ring-buffer calls at
 *                      all logError sites.
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Built from canonical pyvesync
 *                      payloads (Classic300S.yaml fixture + call_json_humidifiers.py + HA PR
 *                      #137544 production-reality findings). Capabilities: Switch,
 *                      RelativeHumidityMeasurement, Sensor, Actuator, Refresh. Setters: setMode
 *                      (auto/sleep/manual), setMistLevel (1-9), setHumidity (30-80%), setDisplay,
 *                      setAutoStop, setNightLight (off/dim/bright — discrete 3-step per HA #137544),
 *                      toggle. Reads humidity, targetHumidity, mistLevel, waterLacks, displayOn,
 *                      autoStopEnabled, autoStopReached, humidityHigh, nightLight,
 *                      nightLightBrightness. Switch payload uses humidifier shape {enabled, id:0},
 *                      NOT purifier shape {powerSwitch, switchIdx}. setVirtualLevel uses
 *                      {id:0, level:N, type:'mist'}. display field aliased from indicator_light_switch
 *                      on legacy firmware variants. Foundation driver for OasisMist 450S US extension.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase

metadata {
    definition(
        name: "Levoit Classic 300S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.1] Levoit Classic 300S (LUH-A601S) humidifier — mist 1-9, target humidity, auto/sleep/manual modes, night-light (off/dim/bright), auto-stop, display; canonical pyvesync payloads",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",                "string"   // auto | sleep | manual
        attribute "mistLevel",           "number"   // 0-9 (0 = inactive)
        attribute "targetHumidity",      "number"   // 30-80 %
        attribute "waterLacks",          "string"   // yes | no
        attribute "nightLight",          "string"   // off | dim | bright  (HA finding #9)
        attribute "nightLightBrightness","number"   // 0 | 50 | 100  (raw API value)
        attribute "displayOn",           "string"   // on | off
        attribute "autoStopEnabled",     "string"   // on | off
        attribute "autoStopReached",     "string"   // yes | no
        attribute "humidityHigh",        "string"   // yes | no
        attribute "info",                "string"   // HTML summary for dashboard tiles
        attribute "diagnostics",         "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online",              "string"

        command "setMode",         [[name:"Mode*",        type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",    [[name:"Level*",        type:"NUMBER", description:"1-9"]]
        command "setHumidity",     [[name:"Percent*",      type:"NUMBER", description:"30-80"]]
        command "setNightLight",   [[name:"Level*",        type:"ENUM",   constraints:["off","dim","bright"]]]
        command "setDisplay",      [[name:"On/Off*",       type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",     [[name:"On/Off*",       type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
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
    state.clear(); unschedule(); initialize()
    state.driverVersion = "2.4.1"
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
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m}"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    // Classic 300S uses {mode: <value>}, NOT {workMode: <value>} (Superior 6000S difference)
    def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// CROSS-CHECK [pyvesync device_map.py LUH-A601S entry / ST+HB homebridge-levoit-humidifiers]:
//   Decision: expose mist range 1-9 (NOT 1-3, even though the physical button has only 3 steps).
//   Rationale: pyvesync device_map.py confirms LUH-A601S mist_levels=9; the VeSync app also
//     exposes 9 levels via the cloud UI. homebridge-levoit-humidifiers independently sets
//     mistLevels:9 for Classic300S. The physical button is a 3-step subset; the API is 1-9.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LUH-A601S HumidifierMap entry, mist_levels=9);
//     https://github.com/RaresAil/homebridge-levoit-humidifiers (Classic300S config).
//   Refutation: community user confirms the device rejects levels 4-9 with an API error -->
//     reduce range to 1-3.
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
// NOTE: field names id/level/type -- NOT levelIdx/virtualLevel/levelType (Superior 6000S)
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
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
// setTargetHumidity payload: {target_humidity: N} — note underscore, not camelCase
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
// setDisplay payload: {state: bool} — NOT {screenSwitch: int} (Superior 6000S difference)
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
// setAutomaticStop payload: {enabled: bool} — NOT {autoStopSwitch: int} (Superior 6000S difference)
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

// ---------- Night-light (HA finding #9 — discrete 3-step only) ----------
// CROSS-CHECK [HA PR #137544 / ST+HB homebridge-levoit-humidifiers]:
//   Decision: expose night-light as 3-step discrete enum: off/dim/bright = 0/50/100.
//     Values outside {0, 50, 100} are rejected with logError.
//   Rationale: HA PR #137544 author physically tested the Classic 300S and confirmed only
//     0, 50, and 100 are accepted; arbitrary values (e.g. 25, 75) are rejected by the device.
//     homebridge-levoit-humidifiers uses a continuous 0-100 slider, which CONTRADICTS the HA
//     author's physical test. We take the more conservative approach matching empirical evidence.
//   Source: https://github.com/home-assistant/core/pull/137544 (HA Classic300S humidifier PR,
//     HA cross-check finding #9, 2026-04-26).
//   Refutation: community user reports values like 25 or 75 actually take effect on their
//     device --> broaden to continuous range or add the intermediate values to the enum.
// Accepts enum "off" | "dim" | "bright". Rejects anything outside the 3-step table.
// Request payload: {night_light_brightness: 0|50|100}
def setNightLight(level){
    logDebug "setNightLight(${level})"
    if (level == null) { logWarn "setNightLight called with null level (likely empty Rule Machine action parameter); ignoring"; return }
    String lvlStr = (level as String).toLowerCase()
    // Night-light is discrete 3-step only (HA finding #9 -- physical device constraint)
    Map nlNameToInt = [off: 0, dim: 50, bright: 100]
    if (!nlNameToInt.containsKey(lvlStr)) {
        logError "Invalid nightLight value '${lvlStr}' -- must be one of: off, dim, bright"
        recordError("Invalid nightLight value '${lvlStr}'", [method:"setNightLightBrightness"])
        return
    }
    Integer brightness = nlNameToInt[lvlStr]
    def resp = hubBypass("setNightLightBrightness", [night_light_brightness: brightness], "setNightLightBrightness(${brightness})")
    if (httpOk(resp)) {
        state.nightLight = lvlStr
        device.sendEvent(name:"nightLight", value: lvlStr)
        device.sendEvent(name:"nightLightBrightness", value: brightness)
        logInfo "Night light: ${lvlStr}"
    } else {
        logError "Night light write failed: ${lvlStr}"; recordError("Night light write failed: ${lvlStr}", [method:"setNightLightBrightness"])
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

// 2-arg parent callback — REQUIRED (BP#1); parent always calls with two args
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored)"
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
    // from older Type without Save (forward-compat — BP#12)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel — humidifier bypassV2 responses can be double-wrapped.
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // Classic 300S is typically single-wrapped, but the peel loop is defensive (BP#3).
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump — gated by debugOutput. Keep for ongoing field diagnostics.
    logDebug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // Classic 300S response uses `enabled` (boolean), NOT `powerSwitch` (int)
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity — from configuration.auto_target_humidity (BP note) ----
    // The top-level response may not include targetHumidity directly; it lives under
    // configuration.auto_target_humidity per the pyvesync ClassicConfig model.
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    String rawMode = (r.mode ?: "manual") as String
    state.mode = rawMode
    device.sendEvent(name:"mode", value: rawMode)

    // ---- Mist level — use mist_virtual_level (active running level) ----
    // mist_level = last-set manual level; mist_virtual_level = currently active
    // Prefer mist_virtual_level for the attribute, which reflects what's actually running
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
    def humHigh = r.humidity_high
    boolean humHighBool = (humHigh instanceof Boolean) ? humHigh : ((humHigh as Integer) == 1)
    device.sendEvent(name:"humidityHigh", value: humHighBool ? "yes" : "no")

    // ---- Auto-stop reached ----
    def autoStopReach = r.automatic_stop_reach_target
    boolean autoStopBool = (autoStopReach instanceof Boolean) ? autoStopReach : ((autoStopReach as Integer) == 1)
    device.sendEvent(name:"autoStopReached", value: autoStopBool ? "yes" : "no")

    // ---- Auto-stop enabled — from configuration.automatic_stop ----
    Boolean autoStopEnabled = null
    if (r.configuration instanceof Map && r.configuration.automatic_stop != null) {
        def asRaw = r.configuration.automatic_stop
        autoStopEnabled = (asRaw instanceof Boolean) ? asRaw : ((asRaw as Integer) == 1)
    }
    if (autoStopEnabled != null) device.sendEvent(name:"autoStopEnabled", value: autoStopEnabled ? "on" : "off")

    // ---- Display (HA finding #8 -- field aliasing) ----
    // CROSS-CHECK [HA cross-check finding #8 / HA Classic300S fixture humidifier-detail.json]:
    //   Decision: read `display` first, then fall back to `indicator_light_switch`, then
    //     fall back to configuration.display.
    //   Rationale: `display` is the canonical response key per real-device HA captures.
    //     `indicator_light_switch` is an older alias present on Classic 200S and some Classic
    //     300S firmware variants; retained as a defensive fallback. configuration.display is a
    //     tertiary fallback if both top-level keys are absent.
    //   Source: HA vesync integration cross-check finding #8 (2026-04-26); real-device
    //     humidifier-detail.json fixture in HA tests/components/vesync/fixtures/.
    //   Refutation: community user reports response includes neither `display` nor
    //     `indicator_light_switch` --> add a debug-level log about the missing field and
    //     investigate whether a new firmware key is in play.
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

    // ---- Night-light (HA finding #9 -- discrete 3-step: 0=off, 50=dim, 100=bright) ----
    // CROSS-CHECK [HA PR #137544 / ST+HB homebridge-levoit-humidifiers]:
    //   Decision: map {0->off, 50->dim, 100->bright}; snap unexpected values to "off".
    //   Rationale: see setNightLight() citation above. Same empirical basis -- HA author's
    //     physical test confirmed only these 3 values are valid. Snapping to "off" on
    //     unexpected parse (rather than crashing or emitting raw) matches the conservative
    //     approach and keeps the attribute consistent with the setter enum.
    //   Refutation: community user reports night_light_brightness=25 in a real API response
    //     --> broaden the mapping or log the intermediate value as-received.
    if (r.night_light_brightness != null) {
        Integer nlRaw = r.night_light_brightness as Integer
        Map nlIntToName = [0: "off", 50: "dim", 100: "bright"]
        String nlName = nlIntToName[nlRaw]
        if (nlName == null) {
            // Unexpected value -- snap to "off" with a debug note (per HA production findings)
            logDebug "applyStatus: unexpected night_light_brightness=${nlRaw}, snapping to 'off'"
            nlName = "off"
            nlRaw = 0
        }
        device.sendEvent(name:"nightLight", value: nlName)
        device.sendEvent(name:"nightLightBrightness", value: nlRaw)
    }

    // ---- Info HTML (use local variables — avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${rawMode}"
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

// Hub/parent call wrapper — matches sibling driver pattern
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
