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
 *  Levoit Classic 200S Humidifier -- Hubitat driver
 *
 *  Targets:    Classic200S (device literal name -- the ONLY model code for this device)
 *  Marketing:  Levoit Classic 200S Smart Humidifier (2.5L ultrasonic)
 *  Reference:  pyvesync VeSyncHumid200S (commit c98729c)
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  CROSS-CHECK — NAMING TRAP (Classic 200S vs Classic 300S):
 *    This driver targets VeSyncHumid200S class (device_map.py: class_name='VeSyncHumid200S',
 *    dev_types=['Classic200S']). This is a DIFFERENT class from VeSyncHumid200300S, which
 *    covers the Classic 300S (LUH-A601S-*), LV600S/A602S, OasisMist 450S, and Dual 200S.
 *    Key behavioral difference: VeSyncHumid200S overrides toggle_display() to use
 *    setIndicatorLightSwitch (not setDisplay). This driver implements that override.
 *    Do NOT conflate with:
 *      - LevoitClassic300S.groovy (LUH-A601S, VeSyncHumid200300S class)
 *      - LevoitDual200S.groovy (LUH-D301S, VeSyncHumid200300S class -- mist 1-2 only)
 *      - LevoitLV600S.groovy (LUH-A602S, VeSyncHumid200300S class -- warm mist)
 *    The Classic 200S has no LUH- prefix code — only the literal "Classic200S" deviceType.
 *
 *  KEY DIFFERENCES from Classic 300S (LevoitClassic300S.groovy / VeSyncHumid200300S class):
 *    - Device code: "Classic200S" literal only (no LUH- prefix; Classic 300S uses LUH-A601S-*)
 *    - Display command: setIndicatorLightSwitch {enabled: bool, id: 0} (Classic 300S uses
 *      setDisplay {state: bool}). This is the ONLY method override in VeSyncHumid200S vs
 *      VeSyncHumid200300S. Source: pyvesync vesynchumidifier.py VeSyncHumid200S.toggle_display()
 *    - Response display key: indicator_light_switch (Alias in ClassicLVHumidResult);
 *      ClassicConfig also uses indicator_light_switch alias -- NOT the plain 'display' key.
 *      The fallback chain in applyStatus() tries 'indicator_light_switch' FIRST for Classic 200S.
 *    - Mist levels: 1-9 (same as Classic 300S; device_map.py Classic200S mist_levels=range(1,10))
 *    - Modes: auto + manual only (no sleep; device_map.py Classic200S mist_modes={AUTO,MANUAL})
 *      This matches Dual 200S but NOT Classic 300S (which has sleep mode).
 *    - Features: AUTO_STOP only (no WARM_MIST; device_map.py Classic200S features=[AUTO_STOP])
 *    - Night-light: present in ClassicLVHumidResult base class response model
 *      (night_light_brightness field). Classic 200S features=[AUTO_STOP] -- NIGHTLIGHT not listed.
 *      Same treatment as Dual 200S: passive read (emit attribute if API returns it),
 *      no setNightLight command until community hardware confirms it works.
 *    All other payload conventions inherit from VeSyncHumid200300S base:
 *      Switch: {enabled: bool, id: 0}; Mode: {mode: value}; setVirtualLevel: {id:0, level:N, type:'mist'};
 *      Auto-stop: {enabled: bool}; targetHumidity: nested at configuration.auto_target_humidity.
 *
 *  History:
 *    2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
 *                      LevoitDiagnostics library. Added recordError() ring-buffer calls at
 *                      all logError sites.
 *    2026-04-28: v2.3  Community fork [PREVIEW v2.3]. Built from pyvesync device_map.py
 *                      Classic200S entry + VeSyncHumid200S class (commit c98729c) + Classic200S.yaml
 *                      fixture (setIndicatorLightSwitch confirmed). Capabilities: Switch,
 *                      RelativeHumidityMeasurement, Sensor, Actuator, Refresh. Setters:
 *                      setMode (auto/manual -- no sleep per device_map.py), setMistLevel (1-9),
 *                      setHumidity (30-80%), setDisplay (via setIndicatorLightSwitch), setAutoStop,
 *                      toggle. Reads: humidity, targetHumidity, mistLevel, waterLacks, displayOn,
 *                      autoStopEnabled, autoStopReached, nightLightBrightness (passive read-only).
 *                      Key differentiator from Classic 300S: display uses setIndicatorLightSwitch
 *                      not setDisplay. No warm mist. Mode auto/manual only (no sleep).
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase

metadata {
    definition(
        name: "Levoit Classic 200S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit Classic 200S (literal deviceType 'Classic200S') — mist 1-9, target humidity 30-80%, auto/manual modes only (no sleep), auto-stop, display (via setIndicatorLightSwitch -- different from Classic 300S). No warm mist. Night-light brightness passive read-only. pyvesync VeSyncHumid200S class. CROSS-CHECK: different from Classic 300S (VeSyncHumid200300S).",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        // CROSS-CHECK [pyvesync device_map.py Classic200S / VeSyncHumid200S class]:
        //   'mode' attribute: only "auto" and "manual" are valid per device_map.py Classic200S
        //   mist_modes={HumidifierModes.AUTO:'auto', HumidifierModes.MANUAL:'manual'}.
        //   Sleep mode is NOT listed for Classic 200S. Classic 300S (LUH-A601S) has sleep;
        //   Classic 200S does not -- this is a real hardware/class difference.
        attribute "mode",             "string"   // auto | manual (no sleep on Classic 200S)
        attribute "mistLevel",        "number"   // 0-9 (0 = inactive; range 1-9 per device_map.py)
        attribute "targetHumidity",   "number"   // 30-80 %
        attribute "waterLacks",       "string"   // yes | no
        attribute "displayOn",        "string"   // on | off
        attribute "autoStopEnabled",  "string"   // on | off
        attribute "autoStopReached",  "string"   // yes | no
        // night_light_brightness: read passively (if API returns it) -- no setter command exposed.
        // pyvesync device_map.py Classic200S features=[AUTO_STOP] -- NIGHTLIGHT not listed.
        // Field present in ClassicLVHumidResult base class (same as Dual 200S pattern).
        attribute "nightLightBrightness", "number"  // 0 | 50 | 100 (read-only; no setter)
        attribute "info",             "string"   // HTML summary for dashboard tiles
        attribute "diagnostics",      "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online",           "string"

        // CROSS-CHECK [pyvesync device_map.py Classic200S mist_modes]:
        //   Only auto and manual are valid. No sleep mode on Classic 200S.
        command "setMode",          [[name:"Mode*",    type:"ENUM",   constraints:["auto","manual"]]]
        command "setMistLevel",     [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        command "setHumidity",      [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        // CROSS-CHECK [pyvesync VeSyncHumid200S.toggle_display()]:
        //   setDisplay uses setIndicatorLightSwitch with {enabled: bool, id: 0}
        //   NOT setDisplay with {state: bool} (Classic 300S and other VeSyncHumid200300S devices).
        //   This is the only method override in VeSyncHumid200S vs its parent class.
        command "setDisplay",       [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",      [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        command "captureDiagnostics"
        // NOTE: setNightLight intentionally absent -- Classic200S features=[AUTO_STOP], no NIGHTLIGHT.
        // NOTE: setWarmMistLevel intentionally absent -- Classic 200S has no warm mist hardware.
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

// state.lastSwitchSet preferred over device.currentValue() to avoid the read-after-write race.
def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// CROSS-CHECK [pyvesync Classic200S.yaml (commit c98729c) / device_map.py Classic200S entry]:
//   Decision: expose two user-facing modes (auto, manual). Sleep is NOT exposed.
//     pyvesync device_map.py Classic200S mist_modes contains only AUTO ('auto') and MANUAL ('manual').
//     Classic 300S (LUH-A601S) adds SLEEP; Classic 200S does not.
//   Write-path: canonical {mode: "auto"} / {mode: "manual"}.
//     No firmware-variant fallback (no EU SKUs for Classic 200S -- only "Classic200S" literal code).
//   Read-path: "auto" and "manual" pass through. "autoPro"/"humidity" aliases normalized to "auto"
//     as a defensive read-path normalization (no known firmware variant issue for Classic 200S,
//     but defensive normalization is free and prevents user confusion if firmware changes).
// Payload: {mode: <value>} -- same as Classic 300S, NOT {workMode: <value>} (V2-class devices)
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    // CROSS-CHECK: only auto and manual are valid for Classic 200S (no sleep per device_map.py)
    if (!(m in ["auto","manual"])) {
        logError "Invalid mode: ${m} -- must be one of: auto, manual (sleep not supported on Classic 200S)"
        recordError("Invalid mode: ${m}", [method:"setMode"])
        return
    }
    def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"
        recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// CROSS-CHECK [pyvesync device_map.py Classic200S entry]:
//   mist_levels = list(range(1, 10)) -> 1-9.
//   Same range as Classic 300S. (Dual 200S has a different 1-2 range -- different class.)
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
// NOTE: field names id/level/type -- NOT levelIdx/virtualLevel/levelType (Superior 6000S / LV600S Hub Connect)
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "mist"], "setVirtualLevel(${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"
        recordError("Mist level write failed: ${lvl}", [method:"setVirtualLevel"])
    }
}

// ---------- Target humidity ----------
// CROSS-CHECK [pyvesync device_map.py Classic200S entry]:
//   No humidity_min override in device_map.py Classic200S entry -- inherits base class 30.
//   Floor 30, ceiling 80 (same as Classic 300S and LV600S; different from OasisMist 450S floor 40).
// setTargetHumidity payload: {target_humidity: N} -- snake_case key
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
        recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
}

// ---------- Display ----------
// CROSS-CHECK [pyvesync VeSyncHumid200S.toggle_display() -- the ONE override in VeSyncHumid200S]:
//   Classic 200S uses setIndicatorLightSwitch with {enabled: bool, id: 0}.
//   Classic 300S and other VeSyncHumid200300S devices use setDisplay with {state: bool}.
//   This is the KEY behavioral difference between Classic 200S and all other VeSyncHumid200300S
//   family drivers. Source: pyvesync vesynchumidifier.py VeSyncHumid200S class (commit c98729c)
//   and Classic200S.yaml fixture (turn_on_display/turn_off_display use setIndicatorLightSwitch).
//   Refutation: community user reports setIndicatorLightSwitch rejected (inner code -1) and
//     setDisplay with {state: bool} works --> swap to setDisplay; file pyvesync issue.
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Boolean v = (onOff == "on")
    // Classic 200S: setIndicatorLightSwitch with {enabled, id} -- NOT setDisplay with {state}
    def resp = hubBypass("setIndicatorLightSwitch", [enabled: v, id: 0], "setIndicatorLightSwitch(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"
        recordError("Display write failed", [method:"setIndicatorLightSwitch"])
    }
}

// ---------- Auto-stop ----------
// setAutomaticStop payload: {enabled: bool} -- same as Classic 300S / Dual 200S (VeSyncHumid200300S base)
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"
        recordError("Auto-stop write failed", [method:"setAutomaticStop"])
    }
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getHumidifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getHumidifierStatus"; recordError("No status returned from getHumidifierStatus", [site:"update"]) }
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
// nightLight parameter accepted but ignored -- Classic 200S has no nightlight command
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- Classic 200S has no nightlight command)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    ensureDebugWatchdog()

    // One-time pref seed: heal descriptionTextEnable=true default (BP#12)
    // Insertion point: top of applyStatus() per V1-humidifier-base convention
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- humidifier bypassV2 responses can be double-wrapped (BP#3).
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // Classic 200S response uses `enabled` (boolean), same as other VeSyncHumid200300S class devices
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- from configuration.auto_target_humidity ----
    // Same nested location as Classic 300S, Dual 200S, LV600S (VeSyncHumid200300S base class)
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // CROSS-CHECK [pyvesync device_map.py Classic200S mist_modes]:
    //   Read path: normalize "autoPro"/"humidity" -> "auto" (defensive; no known firmware alias
    //   for Classic200S specifically, but consistent with other VeSyncHumid200300S-class drivers).
    //   "manual" passes through as-received.
    //   Classic 200S should never report "sleep" but we emit it as-received if it appears.
    String rawMode = (r.mode ?: "manual") as String
    String userMode = (rawMode in ["humidity", "autoPro"]) ? "auto" : rawMode
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

    // ---- Mist level -- use mist_virtual_level (active running level) ----
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
    // CROSS-CHECK [pyvesync ClassicLVHumidResult model + ClassicConfig / VeSyncHumid200S class]:
    //   Classic 200S response uses indicator_light_switch as the primary key (top-level and in config).
    //   ClassicLVHumidResult: display field has Alias('indicator_light_switch') -- the JSON key
    //   coming from the device is indicator_light_switch, not 'display'.
    //   ClassicConfig also has: display Annotated[bool, Alias('indicator_light_switch')].
    //   Read priority: top-level 'indicator_light_switch' FIRST, then 'display' (defensive
    //   fallback in case some firmware variant uses the non-aliased name), then configuration.display
    //   or configuration.indicator_light_switch as a last-resort tertiary fallback.
    //   This is the REVERSE priority of Classic 300S (which tries 'display' first).
    def displayRaw = null
    if (r.indicator_light_switch != null) {
        displayRaw = r.indicator_light_switch
    } else if (r.display != null) {
        logDebug "applyStatus: 'indicator_light_switch' missing, falling back to 'display' key"
        displayRaw = r.display
    } else if (r.configuration instanceof Map) {
        // Tertiary fallback: check both aliases in configuration sub-object
        if (r.configuration.indicator_light_switch != null) {
            displayRaw = r.configuration.indicator_light_switch
        } else if (r.configuration.display != null) {
            displayRaw = r.configuration.display
        }
    }
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // ---- Night-light brightness (read-only / passive) ----
    // pyvesync device_map.py Classic200S features=[AUTO_STOP] -- NIGHTLIGHT not listed.
    // ClassicLVHumidResult includes night_light_brightness (base class field). Passive read only.
    if (r.night_light_brightness != null) {
        Integer nlRaw = r.night_light_brightness as Integer
        device.sendEvent(name:"nightLightBrightness", value: nlRaw)
    }

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${userMode}"
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

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
    recordError("HTTP ${st}", [site:"httpOk"])
    return false
}

// ------------- END -------------
