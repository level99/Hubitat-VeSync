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
 *  Levoit LV600S Hub Connect Humidifier (LUH-A603S-WUS) -- Hubitat driver
 *
 *  Targets:    LUH-A603S-WUS (the single confirmed model code for VeSyncLV600S class)
 *  Marketing:  Levoit LV600S Smart Humidifier "Hub Connect" SKU
 *  Reference:  pyvesync VeSyncLV600S (commit c98729c)
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  CROSS-CHECK — NAMING TRAP (LV600S Hub Connect vs LV600S / existing LevoitLV600S.groovy):
 *    This driver targets VeSyncLV600S class (device_map.py: class_name='VeSyncLV600S',
 *    dev_types=['LUH-A603S-WUS']). This is a DIFFERENT class from VeSyncHumid200300S, which
 *    covers the existing LV600S driver (LUH-A602S-* family, all 6 regional variants).
 *    Both are branded "LV600S" by Levoit. They are NOT interchangeable:
 *      - LevoitLV600S.groovy          = LUH-A602S-* (VeSyncHumid200300S class)
 *        payload: {mode:'auto'}, {enabled:bool, id:0}, {id:0, level:N, type:'mist'/'warm'}
 *      - LevoitLV600SHubConnect.groovy = LUH-A603S-WUS (VeSyncLV600S class) <-- this driver
 *        payload: {workMode:'humidity'}, {powerSwitch:int, switchIdx:0}, {levelIdx:0, virtualLevel:N, levelType:'mist'}
 *    Key class-level differences in API payload field names:
 *      - Power:    {powerSwitch: 0|1, switchIdx: 0}  (NOT {enabled, id})
 *      - Mode:     {workMode: 'humidity'|'sleep'|'manual'}  (NOT {mode: 'auto'|...})
 *              *** AUTO MODE IS SENT AS 'humidity' (NOT 'auto') -- see mist_modes in device_map.py ***
 *      - Mist:     {levelIdx: 0, virtualLevel: N, levelType: 'mist'}  (NOT {id, level, type})
 *      - WarmMist: {levelIdx: 0, levelType: 'warm', mistLevel: 0, warmLevel: N}  (NOT {id, level, type:'warm'})
 *      - Display:  {screenSwitch: 0|1}  (NOT {state: bool})
 *      - TargetHumidity: {targetHumidity: N}  camelCase top-level (NOT snake_case nested in configuration)
 *    Response field names (LV600SResult, all camelCase, top-level -- no configuration sub-object):
 *      powerSwitch, humidity, targetHumidity, virtualLevel, mistLevel, workMode,
 *      waterLacksState (int), waterTankLifted (int), autoStopSwitch, autoStopState,
 *      screenSwitch, screenState, warmPower (bool), warmLevel (int 0-3)
 *    Do NOT modify LevoitLV600S.groovy (A602S driver) for LUH-A603S-WUS devices.
 *    This is a SEPARATE driver file.
 *
 *  WARM MIST payload difference:
 *    setLevel with {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:N}
 *    Note: 'mistLevel' field is always 0 in the warm-level payload (per pyvesync LUH-A603S-WUS.yaml).
 *    warmLevel=0 means warm mist off; 1-3 means warm intensity levels.
 *
 *  AUTO MODE NOTE:
 *    VeSyncLV600S mist_modes maps AUTO -> 'humidity' in device_map.py.
 *    setMode("auto") sends {workMode: 'humidity'} -- NOT {workMode: 'auto'}.
 *    On the read path, the device reports workMode='humidity' when in auto mode.
 *    This driver normalizes 'humidity' -> 'auto' for the user-facing mode attribute.
 *    This is the INVERSE of the LV600S A602S driver (which uses 'auto' on the wire and
 *    normalizes 'humidity' as a fallback for EU firmware). For A603S, 'humidity' IS the wire.
 *
 *  NO AUTO-STOP:
 *    pyvesync device_map.py LUH-A603S-WUS features=[WARM_MIST] -- AUTO_STOP not listed.
 *    The LV600SResult model does include autoStopSwitch and autoStopState fields.
 *    We expose them as read-only attributes (passive read) but do NOT expose setAutoStop.
 *    If community users confirm setAutoStop works on LUH-A603S-WUS, promote to command.
 *
 *  History:
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-28: v2.3  Community fork [PREVIEW v2.3]. Built from pyvesync device_map.py
 *                      LUH-A603S-WUS entry + VeSyncLV600S class + LUH-A603S-WUS.yaml fixture
 *                      (commit c98729c). Capabilities: Switch, RelativeHumidityMeasurement,
 *                      Sensor, Actuator, Refresh. Setters: setMode (auto/sleep/manual -- auto
 *                      sends {workMode:'humidity'} on wire), setMistLevel (1-9), setWarmMistLevel
 *                      (0-3, 0=off), setHumidity (top-level targetHumidity, no configuration
 *                      sub-object), setDisplay (screenSwitch: int), toggle. Reads: humidity,
 *                      targetHumidity, mistLevel (virtualLevel), waterLacks, displayOn,
 *                      autoStopEnabled (read-only passive), warmMistLevel, warmMistEnabled.
 *                      No setAutoStop command (AUTO_STOP not in features; autoStopSwitch/State
 *                      emitted as passive read attributes). No night-light.
 *                      CRITICAL payload differences from LevoitLV600S.groovy (A602S):
 *                      powerSwitch/switchIdx, workMode, levelIdx/virtualLevel/levelType.
 */

#include level99.LevoitDiagnostics

metadata {
    definition(
        name: "Levoit LV600S Hub Connect Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit LV600S Hub Connect (LUH-A603S-WUS) — DIFFERENT from LevoitLV600S.groovy (A602S). Uses VeSyncLV600S class payloads: powerSwitch/switchIdx, workMode:'humidity' for auto mode, levelIdx/virtualLevel/levelType. Mist 1-9, warm mist 0-3, target humidity top-level camelCase. No setAutoStop command (passive read only). No night-light.",
        version: "2.3",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        // CROSS-CHECK [pyvesync device_map.py LUH-A603S-WUS / VeSyncLV600S class]:
        //   mode attribute: user-facing values are "auto", "sleep", "manual".
        //   On write, auto maps to wire value 'humidity'. On read, 'humidity' normalizes to 'auto'.
        attribute "mode",             "string"   // auto | sleep | manual (user-facing)
        attribute "mistLevel",        "number"   // 0-9 (0 = inactive; virtualLevel field in response)
        attribute "targetHumidity",   "number"   // range per pyvesync (no min override for A603S)
        attribute "waterLacks",       "string"   // yes | no  (from waterLacksState int field)
        attribute "warmMistLevel",    "number"   // 0-3 (0 = warm mist off; warmLevel field)
        attribute "warmMistEnabled",  "string"   // on | off  (derived from warmLevel > 0)
        attribute "displayOn",        "string"   // on | off  (from screenSwitch/screenState)
        // autoStop: read-only passive (autoStopSwitch + autoStopState from LV600SResult)
        // AUTO_STOP not in device_map.py LUH-A603S-WUS features=[WARM_MIST].
        // Emitted as attributes so users can monitor; no setAutoStop command.
        attribute "autoStopEnabled",  "string"   // on | off  (autoStopSwitch -- read-only)
        attribute "autoStopReached",  "string"   // yes | no  (autoStopState -- read-only)
        attribute "info",             "string"   // HTML summary for dashboard tiles

        // CROSS-CHECK [pyvesync device_map.py LUH-A603S-WUS mist_modes]:
        //   mist_modes = {AUTO: 'humidity', SLEEP: 'sleep', MANUAL: 'manual'}
        //   User sees "auto"; wire sends {workMode: 'humidity'}.
        command "setMode",          [[name:"Mode*",    type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",     [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        // CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml set_warm_level fixture]:
        //   setLevel payload: {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:N}
        //   warmLevel=0 means warm mist off.
        command "setWarmMistLevel", [[name:"Level*",   type:"NUMBER", description:"0-3 (0=off)"]]
        // CROSS-CHECK: setHumidity sends {targetHumidity: N} camelCase (NOT snake_case target_humidity)
        command "setHumidity",      [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        // CROSS-CHECK: setDisplay sends {screenSwitch: int} (NOT {state: bool} or {enabled, id})
        command "setDisplay",       [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        // NOTE: setAutoStop intentionally absent -- LUH-A603S-WUS features=[WARM_MIST], no AUTO_STOP.
        command "toggle"
        // NOTE: setNightLight intentionally absent -- LUH-A603S-WUS has no night-light hardware.

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
    state.clear(); unschedule(); initialize()
    state.driverVersion = "2.3"
    runIn(3, "refresh")
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
// CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml / VeSyncLV600S class]:
//   VeSyncLV600S uses powerSwitch/switchIdx payload -- NOT {enabled, id} like VeSyncHumid200300S.
//   Source: pyvesync LUH-A603S-WUS.yaml turn_on: {powerSwitch:1, switchIdx:0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(powerSwitch=1)")
    if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
    else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(powerSwitch=0)")
    if (httpOk(resp)) { logInfo "Power off"; state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off") }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
}

def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// CROSS-CHECK [pyvesync device_map.py LUH-A603S-WUS / VeSyncLV600S.set_mode()]:
//   mist_modes = {AUTO:'humidity', SLEEP:'sleep', MANUAL:'manual'}
//   User-facing "auto" maps to wire value "humidity" -- NOT "auto".
//   Read path: "humidity" from device normalized to user-facing "auto".
//   Payload field: {workMode: <value>} -- NOT {mode: <value>} (VeSyncHumid200300S class).
//   Source: pyvesync LUH-A603S-WUS.yaml set_auto_mode: {workMode: 'humidity'}
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) {
        logError "Invalid mode: ${m} -- must be one of: auto, sleep, manual"
        recordError("Invalid mode: ${m}", [method:"setHumidityMode"])
        return
    }
    // Map user-facing "auto" to wire value "humidity" (VeSyncLV600S class convention)
    // This is the INVERSE of A602S where "humidity" is a firmware-variant fallback.
    // For A603S, "humidity" IS the canonical auto-mode wire value per device_map.py.
    String wireMode = (m == "auto") ? "humidity" : m
    def resp = hubBypass("setHumidityMode", [workMode: wireMode], "setHumidityMode(${wireMode})")
    if (httpOk(resp)) {
        state.mode = m   // store user-facing "auto", not wire "humidity"
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m} (wire: ${wireMode})"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml set_mist_level / VeSyncLV600S.set_mist_level()]:
//   setVirtualLevel payload: {levelIdx:0, virtualLevel:N, levelType:'mist'}
//   NOT {id:0, level:N, type:'mist'} (VeSyncHumid200300S class).
//   Source: pyvesync LUH-A603S-WUS.yaml set_mist_level: {levelIdx:0, levelType:'mist', virtualLevel:2}
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [levelIdx: 0, virtualLevel: lvl, levelType: "mist"], "setVirtualLevel(mist,${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"; recordError("Mist level write failed: ${lvl}", [method:"setVirtualLevel"])
    }
}

// ---------- Warm-mist level ----------
// CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml set_warm_level / VeSyncLV600S.set_warm_level()]:
//   setLevel payload: {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:N}
//   Note: 'mistLevel' is always 0 in the warm payload. 'warmLevel' is the actual warm intensity.
//   warmLevel=0 means warm mist off; 1-3 means warm intensity levels.
//   Source: pyvesync LUH-A603S-WUS.yaml set_warm_level: {levelIdx:0, levelType:'warm', mistLevel:0, warmLevel:3}
//   Source: pyvesync LUH-A603S-WUS.yaml set_warm_off: (warmLevel:0 case from set_warm_level)
def setWarmMistLevel(level){
    logDebug "setWarmMistLevel(${level})"
    Integer lvl = (level as Integer) ?: 0
    if (lvl < 0 || lvl > 3) {
        logError "Invalid warm mist level ${lvl} -- must be 0-3 (0=off, 1-3=warm intensity)"
        recordError("Invalid warm mist level ${lvl}", [method:"setLevel"])
        return
    }
    def resp = hubBypass("setLevel", [levelIdx: 0, levelType: "warm", mistLevel: 0, warmLevel: lvl], "setLevel(warm,${lvl})")
    if (httpOk(resp)) {
        boolean warmOn = (lvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        state.warmMistLevel = lvl
        state.warmMistEnabled = warmOnStr
        device.sendEvent(name:"warmMistLevel", value: lvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        logInfo "Warm mist: level=${lvl}, enabled=${warmOnStr}"
    } else {
        logError "Warm mist level write failed: ${lvl}"; recordError("Warm mist level write failed: ${lvl}", [method:"setLevel"])
    }
}

// ---------- Target humidity ----------
// CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml set_humidity / VeSyncLV600S.set_humidity()]:
//   setTargetHumidity payload: {targetHumidity: N}  camelCase, top-level field
//   NOT snake_case {target_humidity: N} (VeSyncHumid200300S class).
//   NOT nested in configuration (A603S response has targetHumidity at top level, no configuration).
//   Source: pyvesync LUH-A603S-WUS.yaml set_humidity: {targetHumidity: 50}
def setHumidity(percent){
    logDebug "setHumidity(${percent})"
    Integer p = Math.max(30, Math.min(80, (percent as Integer) ?: 50))
    def resp = hubBypass("setTargetHumidity", [targetHumidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"; recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
}

// ---------- Display ----------
// CROSS-CHECK [pyvesync LUH-A603S-WUS.yaml turn_on_display / VeSyncLV600S.toggle_display()]:
//   setDisplay payload: {screenSwitch: 0|1}  integer, NOT bool
//   NOT {state: bool} (VeSyncHumid200300S class) or {enabled, id} (Classic 200S).
//   Source: pyvesync LUH-A603S-WUS.yaml turn_on_display: {screenSwitch: 1}
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
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
// nightLight parameter accepted but ignored -- LV600S Hub Connect has no night-light
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- LV600S Hub Connect has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    ensureDebugWatchdog()

    // One-time pref seed: heal descriptionTextEnable=true default (BP#12)
    // Insertion point: top of applyStatus() per V2-humidifier-base convention
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
    // CROSS-CHECK: LV600S Hub Connect response uses `powerSwitch` (int 0|1), NOT `enabled` (bool).
    // This is the V2-class response convention: powerSwitch: int.
    def pwRaw = r.powerSwitch
    boolean powerOn = (pwRaw instanceof Boolean) ? pwRaw : ((pwRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- TOP-LEVEL field, NOT nested in configuration ----
    // CROSS-CHECK: LV600SResult has targetHumidity as a top-level camelCase field.
    // No configuration sub-object in A603S response. This differs from A602S and Classic-line drivers.
    if (r.targetHumidity != null) device.sendEvent(name:"targetHumidity", value: r.targetHumidity as Integer)

    // ---- Mode ----
    // CROSS-CHECK [pyvesync device_map.py LUH-A603S-WUS mist_modes]:
    //   Read path: device reports workMode='humidity' when in auto mode.
    //   'humidity' normalized to user-facing 'auto'. 'sleep' and 'manual' pass through.
    //   'auto' aliased to 'auto' (defensive; if firmware ever reports 'auto' canonically).
    String rawMode = (r.workMode ?: "manual") as String
    String userMode = (rawMode == "humidity") ? "auto" : rawMode
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

    // ---- Mist level -- use virtualLevel (active running level) ----
    // CROSS-CHECK: LV600SResult uses virtualLevel (not mist_virtual_level snake_case).
    // mistLevel field is also present (last-set manual level).
    Integer mistVirtual = null
    if (r.virtualLevel != null) {
        mistVirtual = r.virtualLevel as Integer
    } else if (r.mistLevel != null) {
        mistVirtual = r.mistLevel as Integer
    }
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Warm mist ----
    // CROSS-CHECK: LV600SResult uses warmLevel (int 0-3) and warmPower (bool).
    // Derive warmMistEnabled from warmLevel value (level > 0 = on).
    // Same LV600S-correct logic as LevoitLV600S.groovy warm mist parsing.
    if (r.warmLevel != null) {
        Integer warmLvl = r.warmLevel as Integer
        boolean warmOn = (warmLvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        device.sendEvent(name:"warmMistLevel", value: warmLvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        state.warmMistLevel = warmLvl
        state.warmMistEnabled = warmOnStr
    } else if (r.warmPower != null) {
        // warmLevel absent but warmPower present -- use as fallback
        def warmPowerRaw = r.warmPower
        boolean warmOn = (warmPowerRaw instanceof Boolean) ? warmPowerRaw : ((warmPowerRaw as Integer) == 1)
        device.sendEvent(name:"warmMistEnabled", value: warmOn ? "on" : "off")
    }

    // ---- Water lacks ----
    // CROSS-CHECK: LV600SResult uses waterLacksState (int 0|1), NOT water_lacks (bool).
    def wlRaw = r.waterLacksState
    boolean waterLacks = (wlRaw instanceof Boolean) ? wlRaw : ((wlRaw as Integer) == 1)
    String waterLacksStr = waterLacks ? "yes" : "no"
    if (state.lastWaterLacks != waterLacksStr) {
        if (waterLacks) logInfo "Water reservoir empty"
    }
    state.lastWaterLacks = waterLacksStr
    device.sendEvent(name:"waterLacks", value: waterLacksStr)

    // ---- Auto-stop (passive read -- no setter command exposed) ----
    // autoStopSwitch: 0|1 (feature enabled/disabled); autoStopState: 0|1 (reached target)
    // AUTO_STOP not in device_map.py features -- emitting as read-only attributes only.
    if (r.autoStopSwitch != null) {
        def asRaw = r.autoStopSwitch
        boolean asEnabled = (asRaw instanceof Boolean) ? asRaw : ((asRaw as Integer) == 1)
        device.sendEvent(name:"autoStopEnabled", value: asEnabled ? "on" : "off")
    }
    if (r.autoStopState != null) {
        def asStateRaw = r.autoStopState
        boolean asReached = (asStateRaw instanceof Boolean) ? asStateRaw : ((asStateRaw as Integer) == 1)
        device.sendEvent(name:"autoStopReached", value: asReached ? "yes" : "no")
    }

    // ---- Display ----
    // CROSS-CHECK: LV600SResult uses screenSwitch (write) and screenState (read).
    // Prefer screenState (actual LED state) over screenSwitch (control request).
    // If neither present, fallback to no emission (no false display state emitted).
    def displayRaw = null
    if (r.screenState != null) {
        displayRaw = r.screenState
    } else if (r.screenSwitch != null) {
        displayRaw = r.screenSwitch
    }
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // NOTE: LV600S Hub Connect has NO night-light hardware.
    // LV600SResult has no night_light_brightness field. No nightlight events emitted.

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (r.targetHumidity != null) parts << "Target: ${r.targetHumidity as Integer}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${userMode}"
    if (r.warmLevel != null) {
        Integer wl = r.warmLevel as Integer
        parts << "Warm: ${wl > 0 ? 'L'+wl : 'off'}"
    }
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
