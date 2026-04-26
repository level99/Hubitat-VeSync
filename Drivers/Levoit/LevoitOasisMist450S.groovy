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
 *  Levoit OasisMist 450S Humidifier (LUH-O451S-WUS / LUH-O601S-WUS) — Hubitat driver
 *
 *  Targets:    OasisMist 450S US, LUH-O451S-WUS, LUH-O451S-WUSR,
 *              LUH-O601S-WUS, LUH-O601S-KUS (all map to VeSyncHumid200300S class)
 *  Marketing:  Levoit OasisMist 450S Smart Humidifier (US), OasisMist 4.5L Series
 *  Reference:  pyvesync VeSyncHumid200300S + LUH-O451S-WUS.yaml fixture
 *              device_map.py line ~700 — HumidifierMap entry confirms all 4 dev_types
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Built from canonical
 *                      pyvesync payloads (LUH-O451S-WUS.yaml + call_json_humidifiers.py
 *                      + device_map.py + HA issue #138004 warm-mist/mode findings).
 *                      Extends Classic 300S (same VeSyncHumid200300S class) with:
 *                      warm-mist control (setWarmMistLevel 0-3, 0=off), humidity mode
 *                      (4-mode enum: auto/sleep/manual/humidity). NO nightlight (hardware
 *                      does not have it). Features: WARM_MIST, AUTO_STOP.
 *                      Warm-mist level=0 sets warmMistEnabled=off (corrects pyvesync bug
 *                      in VeSyncHumid200300S.set_warm_level that unconditionally sets
 *                      warm_mist_enabled=True regardless of level -- LV600S class has
 *                      the correct logic; we mirror that). All 4 LUH-O451S/O601S model
 *                      codes route to this driver per device_map.py grouping.
 */

// NOTE: OasisMist 450S US has two firmware variants. Some firmware accepts
// setMode("auto"); others require setMode("humidity") for the same
// auto-target-humidity behavior. Both modes are exposed in the setMode
// command -- if "auto" doesn't take effect on your device, try "humidity".
// Per pyvesync maintainer feedback on home-assistant/core#138004.

metadata {
    definition(
        name: "Levoit OasisMist 450S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit OasisMist 450S/600S US (LUH-O451S-WUS, LUH-O451S-WUSR, LUH-O601S-WUS, LUH-O601S-KUS) — mist 1-9, warm mist 0-3, target humidity, auto/sleep/manual/humidity modes, auto-stop, display; no nightlight; canonical pyvesync payloads",
        version: "2.0",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",             "string"   // auto | sleep | manual | humidity
        attribute "mistLevel",        "number"   // 0-9 (0 = inactive)
        attribute "targetHumidity",   "number"   // 30-80 %
        attribute "waterLacks",       "string"   // yes | no
        attribute "warmMistLevel",    "number"   // 0-3 (0 = warm mist off)
        attribute "warmMistEnabled",  "string"   // on | off  (NOTE: level=0 -> off, 1-3 -> on)
        attribute "displayOn",        "string"   // on | off
        attribute "autoStopEnabled",  "string"   // on | off
        attribute "autoStopReached",  "string"   // yes | no
        attribute "humidityHigh",     "string"   // yes | no
        attribute "info",             "string"   // HTML summary for dashboard tiles

        // setMode: both "auto" and "humidity" exposed -- some 450S US firmware accepts
        // one over the other for the same auto-target-humidity behavior (finding #a).
        command "setMode",          [[name:"Mode*",    type:"ENUM",   constraints:["auto","sleep","manual","humidity"]]]
        command "setMistLevel",     [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        command "setHumidity",      [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        command "setWarmMistLevel", [[name:"Level*",   type:"NUMBER", description:"0-3 (0=off)"]]
        command "setDisplay",       [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",      [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        // NOTE: setNightLight is intentionally absent -- OasisMist 450S has no nightlight hardware
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
    runIn(3, "refresh")
    if (settings?.debugOutput) runIn(1800, "logDebugOff")
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
    else logError "Power on failed"
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [enabled: false, id: 0], "setSwitch(enabled=false)")
    if (httpOk(resp)) { logInfo "Power off"; state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off") }
    else logError "Power off failed"
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
// Four valid modes: auto, sleep, manual, humidity
// NOTE: "auto" and "humidity" may behave identically on some 450S US firmware variants
// (per pyvesync maintainer comment on home-assistant/core#138004 -- finding #a).
// We expose BOTH and let the user choose which their firmware accepts.
// payload: {mode: <value>} -- NOT {workMode: <value>} (Superior 6000S style)
def setMode(mode){
    logDebug "setMode(${mode})"
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual","humidity"])) { logError "Invalid mode: ${m}"; return }
    def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"
    }
}

// ---------- Mist level ----------
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
// NOTE: field names id/level/type -- NOT levelIdx/virtualLevel/levelType (Superior 6000S)
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "mist"], "setVirtualLevel(mist,${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"
    }
}

// ---------- Warm-mist level ----------
// setVirtualLevel payload: {id: 0, level: N, type: 'warm'} -- same method, different type
// Valid range: 0-3 (0 = warm mist off; 1-3 = warm intensity levels)
//
// IMPORTANT: pyvesync VeSyncHumid200300S.set_warm_level() has a known bug:
//   it sets state.warm_mist_enabled = True unconditionally, even for level=0.
//   The correct logic (per VeSyncLV600S class) is: warm_mist_enabled = (warm_level > 0).
//   This driver mirrors the LV600S correct behavior.
def setWarmMistLevel(level){
    logDebug "setWarmMistLevel(${level})"
    Integer lvl = (level as Integer) ?: 0
    if (lvl < 0 || lvl > 3) {
        logError "Invalid warm mist level ${lvl} -- must be 0-3 (0=off, 1-3=warm intensity)"
        return
    }
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "warm"], "setVirtualLevel(warm,${lvl})")
    if (httpOk(resp)) {
        // level=0 means warm mist OFF; level 1-3 means warm mist ON at that intensity
        // This is the CORRECT semantic (mirrors LV600S class, not the buggy 200300S class)
        boolean warmOn = (lvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        state.warmMistLevel = lvl
        state.warmMistEnabled = warmOnStr
        device.sendEvent(name:"warmMistLevel", value: lvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        logInfo "Warm mist: level=${lvl}, enabled=${warmOnStr}"
    } else {
        logError "Warm mist level write failed: ${lvl}"
    }
}

// ---------- Target humidity ----------
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
        logError "Target humidity write failed: ${p}"
    }
}

// ---------- Display ----------
// setDisplay payload: {state: bool} -- NOT {screenSwitch: int} (Superior 6000S)
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setDisplay", [state: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"
    }
}

// ---------- Auto-stop ----------
// setAutomaticStop payload: {enabled: bool} -- NOT {autoStopSwitch: int} (Superior 6000S)
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"
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
        if (!status?.result) logError "No status returned from getHumidifierStatus"
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
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- 450S has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated
    // from older Type without Save (forward-compat -- BP#12)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- humidifier bypassV2 responses can be double-wrapped.
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // OasisMist 450S is typically single-wrapped, but the peel loop is defensive (BP#3).
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // OasisMist 450S response uses `enabled` (boolean), NOT `powerSwitch` (int)
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- from configuration.auto_target_humidity ----
    // Matches Classic 300S model: targetHumidity lives under configuration, not top-level
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // 450S has 4 modes: auto, sleep, manual, humidity
    String rawMode = (r.mode ?: "manual") as String
    state.mode = rawMode
    device.sendEvent(name:"mode", value: rawMode)

    // ---- Mist level -- use mist_virtual_level (active running level) ----
    // mist_level = last-set manual level; mist_virtual_level = currently active
    Integer mistVirtual = null
    if (r.mist_virtual_level != null) {
        mistVirtual = r.mist_virtual_level as Integer
    } else if (r.mist_level != null) {
        mistVirtual = r.mist_level as Integer
    }
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Warm-mist ----
    // warm_enabled and warm_level are top-level response fields (ClassicLVHumidResult)
    // OasisMist 450S populates these (unlike Classic 300S which always has warm_enabled=false)
    //
    // IMPORTANT: We apply correct semantics here regardless of what warm_enabled says.
    // If warm_level == 0, we treat warm mist as OFF (warmMistEnabled="off") even if the
    // API response says warm_enabled=true. This guards against pyvesync's known bug
    // (VeSyncHumid200300S.set_warm_level sets warm_mist_enabled=True even for level=0).
    if (r.warm_level != null) {
        Integer warmLvl = r.warm_level as Integer
        // Derive enabled state from level value (correct logic from LV600S class)
        boolean warmOn = (warmLvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        device.sendEvent(name:"warmMistLevel", value: warmLvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        state.warmMistLevel = warmLvl
        state.warmMistEnabled = warmOnStr
    } else if (r.warm_enabled != null) {
        // warm_level absent but warm_enabled present -- use it as fallback
        def warmEnabledRaw = r.warm_enabled
        boolean warmOn = (warmEnabledRaw instanceof Boolean) ? warmEnabledRaw : ((warmEnabledRaw as Integer) == 1)
        device.sendEvent(name:"warmMistEnabled", value: warmOn ? "on" : "off")
    }

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

    // ---- Auto-stop enabled -- from configuration.automatic_stop ----
    Boolean autoStopEnabled = null
    if (r.configuration instanceof Map && r.configuration.automatic_stop != null) {
        def asRaw = r.configuration.automatic_stop
        autoStopEnabled = (asRaw instanceof Boolean) ? asRaw : ((asRaw as Integer) == 1)
    }
    if (autoStopEnabled != null) device.sendEvent(name:"autoStopEnabled", value: autoStopEnabled ? "on" : "off")

    // ---- Display ----
    // Canonical key: `display` (boolean) at top level.
    // Fallback: `indicator_light_switch` (legacy firmware alias, same as Classic 300S).
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

    // NOTE: OasisMist 450S has NO night-light hardware.
    // night_light_brightness is not parsed and no nightlight events are emitted.
    // If the API response ever includes this field on some firmware variant, it is
    // intentionally ignored here.

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${rawMode}"
    if (r.warm_level != null) {
        Integer wl = r.warm_level as Integer
        parts << "Warm: ${wl > 0 ? 'L'+wl : 'off'}"
    }
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

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
    logError "HTTP ${st}"
    return false
}

// ------------- END -------------
