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
 *  Levoit Sprout Humidifier (LEH-B381S-WUS / LEH-B381S-WEU)
 *
 *  Targets:    US: LEH-B381S-WUS
 *              EU: LEH-B381S-WEU
 *              Both model codes route to this single driver.
 *  Marketing:  Levoit Sprout 4L Smart Humidifier / Sprout Series
 *  pyvesync:   VeSyncSproutHumid class (BypassV2Mixin, VeSyncHumidifier)
 *  Reference:  pyvesync device_map.py + devices/vesynchumidifier.py VeSyncSproutHumid class
 *              https://github.com/webdjoe/pyvesync
 *
 *  CROSS-CHECK [pyvesync device_map.py / VeSyncSproutHumid class]:
 *    How the Sprout Humidifier differs from ALL sibling humidifier classes:
 *
 *    vs VeSyncHumid200300S (Classic 300S LUH-A601S-*, OasisMist 450S LUH-O451S-*,
 *       LV600S A602S LUH-A602S-*):
 *      - Switch: {powerSwitch: int, switchIdx: 0} NOT {enabled: bool, id: 0}
 *      - Mode: {workMode: mist_modes[mode]} via setHumidityMode
 *        (auto -> 'autoPro', NOT 'auto'; sleep -> 'sleep'; manual -> 'manual')
 *      - Mist level: API method 'setVirtualLevel' (same name as 200300S),
 *        payload {levelIdx:0, virtualLevel:N, levelType:'mist'}
 *        (same V2-style fields as OasisMist 1000S; different field names from
 *        the 200300S classic line which uses {id, level, type})
 *      - ONLY 2 mist levels (1-2), NOT 9. device_map.py: mist_levels=[1,2]
 *      - Target humidity: {targetHumidity: N} top-level camelCase
 *      - Display: {screenSwitch: int} via setDisplay
 *      - Child lock: {childLockSwitch: int} via setChildLock
 *      - Auto-stop: {autoStopSwitch: int} via setAutoStopSwitch
 *      - Drying mode: {autoDryingSwitch: int} via setDryingMode
 *      - Nightlight: {brightness, colorTemperature, nightLightSwitch} via setLightStatus
 *        (colorTemperature in K, 2000-3500; brightness 0-100)
 *      - Extra response fields: filterLifePercent, hepaFilterLifePercent,
 *        temperature (F*10 -- divide by 10), dryingMode sub-object,
 *        nightLight sub-object {brightness, nightLightSwitch, colorTemperature}
 *      - NO warm mist hardware
 *
 *    vs VeSyncHumid1000S (OasisMist 1000S LUH-M101S-*):
 *      - Sprout mist API method is 'setVirtualLevel' (1000S uses 'virtualLevel')
 *      - Sprout auto mode wire value is 'autoPro' (1000S uses 'auto')
 *      - Sprout has drying mode + child lock; 1000S has neither
 *      - Sprout has hepaFilterLifePercent; 1000S does not
 *      - Sprout nightlight payload adds colorTemperature field; 1000S does not
 *        (SproutNightlight model has colorTemperature; OasisMist 1000S nightLight does not)
 *      - Sprout mist range: 1-2 only; 1000S: 1-9
 *
 *    vs VeSyncHumid200S (Classic200S literal device type):
 *      - Classic 200S display uses setIndicatorLightSwitch; Sprout uses setDisplay {screenSwitch}
 *      - Classic 200S has no child lock, no drying mode, no nightlight, no filter life
 *
 *    vs VeSyncLV600S (LV600S Hub Connect LUH-A603S-WUS):
 *      - LV600S Hub Connect auto mode wire value: 'humidity'; Sprout: 'autoPro'
 *      - LV600S Hub Connect has warm mist; Sprout does not
 *      - Both use setVirtualLevel for mist; both use powerSwitch/switchIdx for power
 *
 *    Nightlight:
 *      Both US and EU variants appear to have nightlight hardware (VeSyncSproutHumid
 *      is a single class with no feature-gating in device_map.py for nightlight).
 *      Driver exposes setNightlight command (no runtime gate needed).
 *      colorTemperature in Kelvin (2000-3500, warm-to-cool); brightness 0-100.
 *
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-05-03: v2.5  setDisplay + setAutoStop methods extracted to LevoitHumidifierLib
 *                       (now shared with OM1000S/Sup6000S/Sprout).
 *    2026-05-03: v2.4.2  Phase 4 Round 5 — migrated to LevoitHumidifierLib (11 shared methods
 *                        removed from driver). BP24-B ensureSwitchOn() on setMistLevel. BP18
 *                        requireNotNull on setDisplay + setChildLock + setAutoStop + setDryingMode.
 *                        C3 state-change gate on setDisplay + setChildLock.
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-28: v2.2.1  Initial release. US + EU Sprout Humidifier in a single driver.
 *                        pyvesync VeSyncSproutHumid class. V2-style payload conventions.
 *                        Ships as [PREVIEW] — no maintainer hardware. Built from
 *                        pyvesync VeSyncSproutHumid class implementation and
 *                        device_map.py. See CROSS-CHECK above.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitHumidifier

metadata {
    definition(
        name: "Levoit Sprout Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit Sprout Humidifier (LEH-B381S-WUS/-WEU) — mist 1-2, target humidity 30-80%, auto/sleep/manual modes, auto-stop, display, child lock, drying mode, nightlight (brightness + color temp). pyvesync VeSyncSproutHumid class; V2-style payloads. Auto mode wire value: 'autoPro' (not 'auto' — contrast OasisMist 1000S).",
        version: "2.7",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // humidity attribute
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"
        capability "TemperatureMeasurement"          // temperature attribute

        attribute "mode",                "string"   // auto | sleep | manual
        attribute "mistLevel",           "number"   // 0-2 (0 = inactive when off)
        attribute "targetHumidity",      "number"   // 30-80 %
        attribute "waterLacks",          "string"   // yes | no
        attribute "displayOn",           "string"   // on | off
        attribute "childLock",           "string"   // on | off
        attribute "autoStopEnabled",     "string"   // on | off  (writable via setAutoStop)
        attribute "autoStopReached",     "string"   // yes | no  (read-only)
        attribute "dryingEnabled",       "string"   // on | off  (writable via setDryingMode)
        attribute "filterLife",          "number"   // 0-100 % (regular filter)
        attribute "hepaFilterLife",      "number"   // 0-100 % (HEPA filter)
        attribute "nightlightOn",        "string"   // on | off
        attribute "nightlightBrightness", "number"  // 0-100
        attribute "nightlightColorTemp", "number"   // 2000-3500 K
        attribute "info",                "string"   // HTML tile summary

        command "setMode",               [[name:"Mode*",      type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",          [[name:"Level*",     type:"NUMBER", description:"1-2"]]
        command "setHumidity",           [[name:"Percent*",   type:"NUMBER", description:"30-80"]]
        command "setDisplay",            [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setChildLock",          [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",           [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setDryingMode",         [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]]]
        command "setNightlight",         [[name:"On/Off*",    type:"ENUM",   constraints:["on","off"]],
                                          [name:"Brightness", type:"NUMBER", description:"0-100"],
                                          [name:"ColorTemp",  type:"NUMBER", description:"2000-3500 K (warm-cool)"]]
        command "toggle"

        attribute "diagnostics",     "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online",          "string"
        command "captureDiagnostics"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput",           "bool", title: "Enable debug logging?",                    defaultValue: false
    }
}

// Lifecycle, refresh, toggle, update (0/1/2-arg), hubBypass, httpOk
// are provided by #include level99.LevoitHumidifier (LevoitHumidifierLib.groovy).

// ---------- Power ----------
// VeSyncSproutHumid toggle_switch: {powerSwitch: int, switchIdx: 0}
def on(){
    logDebug "on()"
    // state.turningOn prevents re-entrance: ensureSwitchOn() -> on() -> setMistLevel() -> ensureSwitchOn()
    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(powerSwitch=1)")
        if (httpOk(resp)) { state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on"); logInfo "Power on" }
        else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
    } finally {
        state.turningOn = false
    }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(powerSwitch=0)")
    if (httpOk(resp)) { state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off"); logInfo "Power off" }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
}

// toggle: provided by #include level99.LevoitHumidifier

// ---------- Mode ----------
// VeSyncSproutHumid set_mode: {workMode: mist_modes[mode]}
// CRITICAL: mist_modes[auto] = 'autoPro' (NOT 'auto' — contrast OasisMist 1000S 'auto',
// contrast LV600S Hub Connect 'humidity'). Canonical from pyvesync device_map.py.
// In reverse: workMode='autoPro' from API response maps back to user-facing 'auto'.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be: auto, sleep, manual"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    // Wire-value mapping: auto -> 'autoPro' (device_map.py mist_modes for Sprout)
    String wire = (m == "auto") ? "autoPro" : m
    def resp = hubBypass("setHumidityMode", [workMode: wire], "setHumidityMode(${wire})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// VeSyncSproutHumid set_mist_level: API method 'setVirtualLevel' (NOT 'virtualLevel'),
// payload {levelIdx: 0, virtualLevel: N, levelType: 'mist'}.
// Range: 1-2 only (mist_levels=[1,2] per device_map.py — Sprout is a 2-level device).
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    if (!requireNotNull(level, "setMistLevel")) return
    Integer lvl = safeIntArg(level, 0)
    if (lvl <= 0) { off(); return }
    Integer clamped = Math.max(1, Math.min(2, lvl))
    ensureSwitchOn()
    def resp = hubBypass("setVirtualLevel", [levelIdx: 0, virtualLevel: clamped, levelType: "mist"], "setVirtualLevel(mist,${clamped})")
    if (httpOk(resp)) {
        state.mistLevel = clamped
        device.sendEvent(name:"mistLevel", value: clamped)
        logInfo "Mist level: ${clamped}"
    } else {
        logError "Mist level write failed: ${clamped}"; recordError("Mist level write failed: ${clamped}", [method:"setVirtualLevel"])
    }
}

// ---------- Target humidity ----------
// VeSyncSproutHumid set_humidity: {targetHumidity: N} top-level camelCase.
// Range: 30-80 % per device_map.py target_minmax=(30,80).
def setHumidity(percent){
    logDebug "setHumidity(${percent})"
    if (!requireNotNull(percent, "setHumidity")) return
    Integer p = safeIntArg(percent, 0)
    if (p <= 0) { logWarn "setHumidity called with ${p} -- 0% is not a valid target humidity; ignoring"; return }
    p = Math.max(30, Math.min(80, p))
    def resp = hubBypass("setTargetHumidity", [targetHumidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"; recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
}

// V2-line shared body via lib; delegator preserves method-presence semantics.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDisplay(onOff) { doSetDisplayScreenSwitch(onOff) }

// ---------- Child lock ----------
// VeSyncSproutHumid toggle_child_lock: {childLockSwitch: int}
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setChildLock")) return false
    // BP25: derive canonical on/off for sendEvent and C3 gate — never emit raw "true"/"1"/"yes".
    String val = (onOff as String).trim().toLowerCase()
    String canon = (val in ["on","true","1","yes"]) ? "on" : "off"
    if (device.currentValue("childLock") == canon) return true
    Integer v = (canon == "on") ? 1 : 0
    def resp = hubBypass("setChildLock", [childLockSwitch: v], "setChildLock(${canon})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: canon)
        logInfo "Child lock: ${canon}"
    } else {
        logError "Child lock write failed"; recordError("Child lock write failed", [method:"setChildLock"])
    }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setAutoStop(onOff) { doSetAutoStopSwitch(onOff) }

// ---------- Drying mode ----------
// VeSyncSproutHumid toggle_drying_mode: {autoDryingSwitch: int}
// Drying mode runs the fan without mist after humidification to dry out the internals.
// The dryingEnabled attribute reflects the user-controlled preference flag (autoDryingSwitch),
// not hardware runtime state, so a C3 idempotency gate is valid here.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDryingMode(onOff){
    logDebug "setDryingMode(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDryingMode")) return false
    // BP25: normalize to lowercase, then derive canonical on/off for sendEvent and C3 gate.
    // Attribute always emits "on" or "off"; the C3 gate compares canonical vs canonical.
    String v = (onOff as String).trim().toLowerCase()
    String canon = (v in ["on","true","1","yes"]) ? "on" : "off"
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("dryingEnabled") == canon) return true
    Integer sw = (canon == "on") ? 1 : 0
    def resp = hubBypass("setDryingMode", [autoDryingSwitch: sw], "setDryingMode(${canon})")
    if (httpOk(resp)) {
        device.sendEvent(name:"dryingEnabled", value: canon)
        logInfo "Drying mode: ${canon}"
    } else {
        logError "Drying mode write failed"; recordError("Drying mode write failed", [method:"setDryingMode"])
    }
}

// ---------- Nightlight ----------
// VeSyncSproutHumid _set_nightlight_state: {brightness, colorTemperature, nightLightSwitch}
// via setLightStatus.
// brightness: 0-100; colorTemperature: 2000-3500 K (warm to cool white).
// nightLightSwitch=1 when on, 0 when off.
// brightness=0 with nightLightSwitch=0 means off.
// NOTE: colorTemperature is optional — defaults to prior state (or 3500 if unknown).
//
// No C3 idempotency gate: the payload carries brightness and colorTemperature in addition
// to the on/off switch. Two calls with the same on/off value but different brightness or
// color-temperature arguments send different payloads, so comparing only nightlightOn
// would incorrectly suppress legitimate brightness or color-temperature updates.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setNightlight(onOff, brightness = null, colorTemp = null){
    logDebug "setNightlight(${onOff}, ${brightness}, ${colorTemp})"
    if (!requireNonEmptyEnum(onOff, "setNightlight")) return
    // BP25: normalize to lowercase before all comparisons.
    String nl = (onOff as String).trim().toLowerCase()
    Integer br   = (brightness  != null) ? Math.max(0, Math.min(100, safeIntArg(brightness, 0)))       : null   // BP26
    Integer ct   = (colorTemp   != null) ? Math.max(2000, Math.min(3500, safeIntArg(colorTemp, 3500))) : null   // BP26
    if (nl == "off") { br = 0; ct = ct ?: 3500 }
    else {
        // on: if no brightness supplied default to 100; clamp floor to 1
        br = (br == null) ? 100 : (br == 0 ? 50 : br)
        ct = ct ?: 3500
    }
    Integer nlSwitch = (nl == "on" && br > 0) ? 1 : 0
    def resp = hubBypass("setLightStatus",
        [brightness: br, colorTemperature: ct, nightLightSwitch: nlSwitch],
        "setLightStatus(br=${br},ct=${ct},nl=${nlSwitch})")
    if (httpOk(resp)) {
        String onOffStr = (nlSwitch == 1) ? "on" : "off"
        device.sendEvent(name:"nightlightOn",         value: onOffStr)
        device.sendEvent(name:"nightlightBrightness", value: br)
        device.sendEvent(name:"nightlightColorTemp",  value: ct)
        logInfo "Nightlight: ${onOffStr}, brightness=${br}, colorTemp=${ct}K"
    } else {
        logError "Nightlight write failed"; recordError("Nightlight write failed", [method:"setLightStatus"])
    }
}

// refresh, update (0/1/2-arg): provided by #include level99.LevoitHumidifier

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    ensureDebugWatchdog()

    // BP12 pref-seed: heal descriptionTextEnable=true default for migrated installs.
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // BP#3: defensive envelope peel — bypassV2 responses can be wrapped in
    // {code, result, traceId} envelope layers. Peel until device data is reached.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump (debugOutput-gated).
    logDebug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    def powerRaw = r.powerSwitch
    boolean powerOn = (powerRaw instanceof Boolean) ? powerRaw : ((powerRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Temperature (F*10 — divide by 10) ----
    // Null/0 temperature is common when sensor is not present or not yet warmed up.
    if (r.temperature != null) {
        Integer tempRaw = r.temperature as Integer
        if (tempRaw != 0) {
            BigDecimal tempF = tempRaw / 10.0
            device.sendEvent(name:"temperature", value: tempF, unit: "°F")
        }
    }

    // ---- Target humidity ----
    if (r.targetHumidity != null) device.sendEvent(name:"targetHumidity", value: r.targetHumidity as Integer)

    // ---- Mode ----
    // Reverse mapping: 'autoPro' -> 'auto'; 'sleep' -> 'sleep'; 'manual' -> 'manual'.
    // Also normalize 'auto' defensively in case firmware diverges.
    String rawMode = (r.workMode ?: "manual") as String
    String userMode = (rawMode in ["autoPro", "auto", "humidity"]) ? "auto" : rawMode
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

    // ---- Mist level (1-2 range) ----
    // BP#6: clamp to 0 when device is off (virtualLevel may retain last-set value).
    Integer mistVirtual = null
    if (r.virtualLevel != null) {
        mistVirtual = r.virtualLevel as Integer
    } else if (r.mistLevel != null) {
        mistVirtual = r.mistLevel as Integer
    }
    if (!powerOn && mistVirtual != null && mistVirtual > 0) mistVirtual = 0
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Water lacks ----
    def waterLacksRaw = r.waterLacksState
    boolean waterLacks = (waterLacksRaw instanceof Boolean) ? waterLacksRaw : ((waterLacksRaw as Integer) == 1)
    if (!waterLacks && r.waterTankLifted != null) {
        def liftedRaw = r.waterTankLifted
        waterLacks = (liftedRaw instanceof Boolean) ? liftedRaw : ((liftedRaw as Integer) == 1)
    }
    String waterLacksStr = waterLacks ? "yes" : "no"
    if (state.lastWaterLacks != waterLacksStr) {
        if (waterLacks) logInfo "Water reservoir empty or tank lifted"
    }
    state.lastWaterLacks = waterLacksStr
    device.sendEvent(name:"waterLacks", value: waterLacksStr)

    // ---- Auto-stop ----
    def autoStopSwitchRaw = r.autoStopSwitch
    if (autoStopSwitchRaw != null) {
        boolean autoStopEnabled = (autoStopSwitchRaw instanceof Boolean) ? autoStopSwitchRaw : ((autoStopSwitchRaw as Integer) == 1)
        device.sendEvent(name:"autoStopEnabled", value: autoStopEnabled ? "on" : "off")
    }
    def autoStopStateRaw = r.autoStopState
    if (autoStopStateRaw != null) {
        boolean autoStopReached = (autoStopStateRaw instanceof Boolean) ? autoStopStateRaw : ((autoStopStateRaw as Integer) == 1)
        device.sendEvent(name:"autoStopReached", value: autoStopReached ? "yes" : "no")
    }

    // ---- Display ----
    // Prefer screenState (actual) over screenSwitch (config).
    def displayRaw = r.screenState != null ? r.screenState : r.screenSwitch
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // ---- Child lock ----
    def childLockRaw = r.childLockSwitch
    if (childLockRaw != null) {
        boolean childLock = (childLockRaw instanceof Boolean) ? childLockRaw : ((childLockRaw as Integer) == 1)
        device.sendEvent(name:"childLock", value: childLock ? "on" : "off")
    }

    // ---- Drying mode ----
    def dryingModeObj = r.dryingMode
    if (dryingModeObj instanceof Map) {
        // autoDryingSwitch = user-enabled flag; dryingState = current runtime state
        def autoDrySwRaw = dryingModeObj.autoDryingSwitch
        if (autoDrySwRaw != null) {
            boolean dryingEnabled = (autoDrySwRaw instanceof Boolean) ? autoDrySwRaw : ((autoDrySwRaw as Integer) == 1)
            device.sendEvent(name:"dryingEnabled", value: dryingEnabled ? "on" : "off")
        }
    }

    // ---- Filter life ----
    if (r.filterLifePercent != null)     device.sendEvent(name:"filterLife",     value: r.filterLifePercent as Integer)
    if (r.hepaFilterLifePercent != null) device.sendEvent(name:"hepaFilterLife", value: r.hepaFilterLifePercent as Integer)

    // ---- Nightlight (present on both US and EU variants per pyvesync VeSyncSproutHumid) ----
    // nightLight = {nightLightSwitch: int, brightness: int, colorTemperature: int}
    def nl = r?.nightLight
    if (nl instanceof Map) {
        def nlSwitchRaw = nl.nightLightSwitch
        if (nlSwitchRaw != null) {
            boolean nlOn = (nlSwitchRaw instanceof Boolean) ? nlSwitchRaw : ((nlSwitchRaw as Integer) == 1)
            device.sendEvent(name:"nightlightOn", value: nlOn ? "on" : "off")
        }
        if (nl.brightness != null)       device.sendEvent(name:"nightlightBrightness", value: nl.brightness as Integer)
        if (nl.colorTemperature != null) device.sendEvent(name:"nightlightColorTemp",  value: nl.colorTemperature as Integer)
    }

    // ---- Info HTML (local variables only — avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null)         parts << "Humidity: ${r.humidity as Integer}%"
    if (r.targetHumidity != null)   parts << "Target: ${r.targetHumidity as Integer}%"
    if (mistVirtual != null)        parts << "Mist: L${mistVirtual} (1-2)"
    parts << "Mode: ${userMode}"
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    if (r.filterLifePercent != null)     parts << "Filter: ${r.filterLifePercent as Integer}%"
    if (r.hepaFilterLifePercent != null) parts << "HEPA: ${r.hepaFilterLifePercent as Integer}%"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// hubBypass, httpOk provided by #include level99.LevoitHumidifier.

// ------------- END -------------
