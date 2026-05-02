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
 *  Levoit OasisMist 1000S Humidifier (LUH-M101S-WUS / LUH-M101S-WUSR / LUH-M101S-WEUR)
 *
 *  Targets:    OasisMist 1000S US: LUH-M101S-WUS, LUH-M101S-WUSR
 *              OasisMist 1000S EU: LUH-M101S-WEUR (adds NIGHTLIGHT + NIGHTLIGHT_BRIGHTNESS)
 *              All three model codes route to this single driver.
 *  Marketing:  Levoit OasisMist 1000S Humidifier / Oasismist Series
 *  pyvesync:   VeSyncHumid1000S class (inherits VeSyncHumid200300S; overrides key methods)
 *  Reference:  pyvesync device_map.py + devices/vesynchumidifier.py VeSyncHumid1000S class
 *              LUH-M101S-WUS.yaml + LUH-M101S-WEUR.yaml pyvesync test fixtures
 *              https://github.com/webdjoe/pyvesync
 *
 *  CROSS-CHECK [pyvesync device_map.py / VeSyncHumid1000S class]:
 *    How the 1000S differs from ALL sibling classes:
 *
 *    vs VeSyncHumid200300S (Classic 300S LUH-A601S-*, OasisMist 450S LUH-O451S-*,
 *       LV600S A602S LUH-A602S-*, Dual 200S LUH-D301S-*):
 *      - Switch: {powerSwitch: int, switchIdx: 0} NOT {enabled: bool, id: 0}
 *      - Mode: {workMode: value} NOT {mode: value}
 *      - Mist level: API method 'virtualLevel' (NOT 'setVirtualLevel'), payload
 *        {levelIdx: 0, virtualLevel: N, levelType: 'mist'} NOT {id, level, type}
 *      - Target humidity: {targetHumidity: N} top-level camelCase NOT nested
 *        {configuration: {auto_target_humidity: N}} snake_case
 *      - Display: {screenSwitch: int} NOT {state: bool}
 *      - Auto-stop: {autoStopSwitch: int} NOT {enabled: bool} + NOT method setAutomaticStop
 *      - Response: camelCase top-level fields (powerSwitch, workMode, virtualLevel,
 *        targetHumidity, waterLacksState, screenSwitch) NOT snake_case
 *      - NO warm mist hardware (no warm_level / warm_enabled fields)
 *
 *    vs VeSyncHumid200S (Classic 200S 'Classic200S'):
 *      - Same V2-style payload divergences from VeSyncHumid200300S base (see above)
 *      - Classic 200S still uses {enabled, id} for switch; 1000S uses {powerSwitch, switchIdx}
 *      - Classic 200S display = setIndicatorLightSwitch; 1000S display = setDisplay{screenSwitch}
 *
 *    vs VeSyncLV600S (LV600S Hub Connect LUH-A603S-WUS):
 *      - Both use camelCase V2-style payloads; 1000S auto mode wire value is 'auto'
 *        (LV600S Hub Connect uses 'humidity' for auto — different mist_modes mapping)
 *      - 1000S has NIGHTLIGHT/NIGHTLIGHT_BRIGHTNESS features on EU variant; A603S does not
 *      - 1000S has AUTO_STOP; A603S does not
 *      - 1000S has NO warm mist; A603S has warm mist via setLevel
 *
 *    Nightlight gating:
 *      US/WUSR variants: features=[AUTO_STOP] only — no nightlight hardware.
 *        nightLight nested object will be absent from API response.
 *      EU/WEUR variant:  features=[NIGHTLIGHT, NIGHTLIGHT_BRIGHTNESS, AUTO_STOP].
 *        nightLight = {nightLightSwitch: 0|1, brightness: 0-100} in API response.
 *      Both variants: nightLight attributes are passive read-only. Driver exposes
 *        setNightlight command (sets nightLightSwitch + brightness together).
 *        Runtime-gated: non-WEU users see the command but it no-ops with INFO log.
 *        Capability "SwitchLevel" NOT declared; nightlight is a separate named attribute.
 *
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-28: v2.2.1  Initial release. US + EU OasisMist 1000S in a single driver.
 *                        pyvesync VeSyncHumid1000S class. V2-style payload conventions.
 *                        EU nightlight gated at runtime on state.deviceType == "LUH-M101S-WEUR".
 *                        Ships as [PREVIEW] — no maintainer hardware. Built from
 *                        pyvesync fixtures (LUH-M101S-WUS.yaml + LUH-M101S-WEUR.yaml)
 *                        and VeSyncHumid1000S class implementation. See CROSS-CHECK above.
 */

#include level99.LevoitDiagnostics

metadata {
    definition(
        name: "Levoit OasisMist 1000S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.3] Levoit OasisMist 1000S (LUH-M101S-WUS/-WUSR/-WEUR) — mist 1-9, target humidity 30-80%, auto/sleep/manual modes, auto-stop, display; WEUR adds nightlight (runtime-gated). pyvesync VeSyncHumid1000S class; V2-style payloads (powerSwitch/workMode/virtualLevel). No warm mist.",
        version: "2.4",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // humidity attribute
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "mode",               "string"    // auto | sleep | manual
        attribute "mistLevel",          "number"    // 0-9 (0 = inactive when off)
        attribute "targetHumidity",     "number"    // 30-80 %
        attribute "waterLacks",         "string"    // yes | no
        attribute "displayOn",          "string"    // on | off
        attribute "autoStopEnabled",    "string"    // on | off  (writable via setAutoStop)
        attribute "autoStopReached",    "string"    // yes | no  (read-only — device-triggered)
        // Nightlight: declared for all variants (Hubitat static-metadata limitation).
        // Commands gate at runtime on state.deviceType == "LUH-M101S-WEUR".
        // Non-WEUR users see these on device page; they no-op with INFO log.
        attribute "nightlightOn",       "string"    // on | off  (WEUR only; passive on US/WUSR)
        attribute "nightlightBrightness", "number"  // 0-100     (WEUR only; passive on US/WUSR)
        attribute "info",               "string"    // HTML tile summary

        command "setMode",              [[name:"Mode*",    type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",         [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        command "setHumidity",          [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        command "setDisplay",           [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",          [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        // Nightlight (WEUR only -- runtime-gated; no-ops with INFO log on US/WUSR)
        command "setNightlight",        [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]],
                                         [name:"Brightness", type:"NUMBER", description:"0-100 (0=off)"]]
        command "toggle"

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
    state.driverVersion = "2.4"
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
// VeSyncHumid1000S toggle_switch: {powerSwitch: int(toggle), switchIdx: 0}
// Different from VeSyncHumid200300S which uses {enabled: bool, id: 0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(powerSwitch=1)")
    if (httpOk(resp)) { state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on"); logInfo "Power on" }
    else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(powerSwitch=0)")
    if (httpOk(resp)) { state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off"); logInfo "Power off" }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
}

def toggle(){
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---------- Mode ----------
// VeSyncHumid1000S set_mode: {workMode: mist_modes[mode]}
// Mist modes: auto->'auto', sleep->'sleep', manual->'manual' (straight mapping, unlike A603S).
// No firmware-variant issue documented for 1000S (canonical pyvesync fixture uses 'auto').
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be: auto, sleep, manual"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    def resp = hubBypass("setHumidityMode", [workMode: m], "setHumidityMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// VeSyncHumid1000S set_mist_level: API method 'virtualLevel' (NOT 'setVirtualLevel'),
// payload {levelIdx: 0, virtualLevel: N, levelType: 'mist'}.
// Different field names from VeSyncHumid200300S ({id, level, type}) and different method name.
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("virtualLevel", [levelIdx: 0, virtualLevel: lvl, levelType: "mist"], "virtualLevel(mist,${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"; recordError("Mist level write failed: ${lvl}", [method:"virtualLevel"])
    }
}

// ---------- Target humidity ----------
// VeSyncHumid1000S set_humidity: {targetHumidity: N} -- top-level camelCase.
// Different from VeSyncHumid200300S: {target_humidity: N} (which nests under configuration).
// Range: 30-80% per pyvesync device_map.py (no custom humidity_min for this model family).
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
// VeSyncHumid1000S set_display: {screenSwitch: int} -- integer 0/1.
// Different from VeSyncHumid200300S which uses {state: bool}.
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

// ---------- Auto-stop ----------
// VeSyncHumid1000S toggle_automatic_stop: {autoStopSwitch: int(toggle)}.
// Different from VeSyncHumid200300S which uses method setAutomaticStop + {enabled: bool}.
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setAutoStopSwitch", [autoStopSwitch: v], "setAutoStopSwitch(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutoStopSwitch"])
    }
}

// ---------- Nightlight (WEUR only -- runtime-gated) ----------
// CROSS-CHECK [pyvesync device_map.py VeSyncHumid1000S WEUR entry]:
//   WEUR features=[NIGHTLIGHT, NIGHTLIGHT_BRIGHTNESS, AUTO_STOP].
//   US/WUSR features=[AUTO_STOP] only -- no nightlight hardware.
//
//   TWO distinct API methods (pyvesync VeSyncHumid1000S):
//     toggle_nightlight()         → POST setNightLightStatus {nightLightSwitch: int}
//                                   Pure on/off; does NOT set brightness.
//     set_nightlight_brightness() → POST setLightStatus {brightness: N, nightLightSwitch: int}
//                                   Sets brightness 0-100 AND nightLightSwitch (0 if brightness=0).
//
//   setNightlight(onOff) with no brightness arg → setNightLightStatus (toggle path).
//   setNightlight(onOff, brightness) with explicit brightness → setLightStatus (brightness path).
//   brightness=0 via setLightStatus means nightlight off (nightLightSwitch=0).
//   No separate RGB: 1000S nightlight is white with brightness control only.
private boolean isNightlightVariant(){
    if (state.deviceType == "LUH-M101S-WEUR") return true
    logInfo "Nightlight not supported on this hardware variant (${state.deviceType ?: 'unknown'}); ignoring command"
    return false
}

// setNightlight: nightlight on/off, with optional brightness control.
//   setNightlight("on")          → setNightLightStatus {nightLightSwitch: 1}  (toggle path)
//   setNightlight("off")         → setNightLightStatus {nightLightSwitch: 0}  (toggle path)
//   setNightlight("on",  70)     → setLightStatus {brightness: 70, nightLightSwitch: 1}
//   setNightlight("off", 0)      → setLightStatus {brightness: 0,  nightLightSwitch: 0}
// On non-WEUR variants: no-ops with INFO log.
def setNightlight(onOff, brightness = null){
    logDebug "setNightlight(${onOff}, ${brightness})"
    if (!isNightlightVariant()) return
    if (brightness == null) {
        // Pure on/off toggle -- use setNightLightStatus (pyvesync toggle_nightlight path)
        Integer nlSwitch = (onOff == "on") ? 1 : 0
        def resp = hubBypass("setNightLightStatus", [nightLightSwitch: nlSwitch], "setNightLightStatus(${onOff})")
        if (httpOk(resp)) {
            String onOffStr = (nlSwitch == 1) ? "on" : "off"
            device.sendEvent(name:"nightlightOn", value: onOffStr)
            logInfo "Nightlight: ${onOffStr}"
        } else {
            logError "Nightlight toggle failed"; recordError("Nightlight toggle failed", [method:"setNightLightStatus"])
        }
    } else {
        // Brightness control -- use setLightStatus (pyvesync set_nightlight_brightness path)
        Integer br = Math.max(0, Math.min(100, (brightness as Integer) ?: 0))
        if (onOff == "off") br = 0
        Integer nlSwitch = (br > 0) ? 1 : 0
        def resp = hubBypass("setLightStatus", [brightness: br, nightLightSwitch: nlSwitch], "setLightStatus(brightness=${br})")
        if (httpOk(resp)) {
            String onOffStr = (nlSwitch == 1) ? "on" : "off"
            device.sendEvent(name:"nightlightOn",         value: onOffStr)
            device.sendEvent(name:"nightlightBrightness", value: br)
            logInfo "Nightlight: ${onOffStr}, brightness=${br}"
        } else {
            logError "Nightlight brightness write failed"; recordError("Nightlight brightness write failed", [method:"setLightStatus"])
        }
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
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- 1000S has no external night-light child)"
    applyStatus(status)
    return true
}

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

    // Self-seed state.deviceType from parent's stored data value.
    // Guards nightlight runtime-gate without requiring a forced Save Preferences.
    if (!state.deviceType) {
        state.deviceType = device.getDataValue("deviceType") ?: ""
    }

    def r = status?.result ?: [:]
    // BP#3: defensive envelope peel — bypassV2 responses can be wrapped in
    // {code, result, traceId} envelope layers. Peel until device data is reached.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump (debugOutput-gated). Keep for ongoing field diagnostics.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // 1000S response: powerSwitch (int 0|1) NOT `enabled` (bool).
    def powerRaw = r.powerSwitch
    boolean powerOn = (powerRaw instanceof Boolean) ? powerRaw : ((powerRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- top-level camelCase (unlike VeSyncHumid200300S nested snake_case) ----
    Integer targetH = null
    if (r.targetHumidity != null) targetH = r.targetHumidity as Integer
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // 1000S mist_modes: auto->'auto', sleep->'sleep', manual->'manual' (direct mapping).
    // Still normalize 'humidity' and 'autoPro' defensively (future firmware may vary).
    String rawMode = (r.workMode ?: "manual") as String
    String userMode = (rawMode in ["humidity", "autoPro"]) ? "auto" : rawMode
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

    // ---- Mist level ----
    // BP#6: report 0 when device is off (virtualLevel may retain last-set value while off).
    Integer mistVirtual = null
    if (r.virtualLevel != null) {
        mistVirtual = r.virtualLevel as Integer
    } else if (r.mistLevel != null) {
        mistVirtual = r.mistLevel as Integer
    }
    // When switch is off, clamp mist to 0 (last-set level retained in API response; BP#6).
    if (!powerOn && mistVirtual != null && mistVirtual > 0) mistVirtual = 0
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Water lacks ----
    // 1000S: waterLacksState (int 0|1 or bool). Also check waterTankLifted.
    def waterLacksRaw = r.waterLacksState
    boolean waterLacks = (waterLacksRaw instanceof Boolean) ? waterLacksRaw : ((waterLacksRaw as Integer) == 1)
    // Also consider tank lifted as a water-unavailable signal.
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

    // ---- Auto-stop state (passive read) ----
    // autoStopSwitch = config; autoStopState = currently active
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
    // 1000S: screenSwitch (int 0|1) and screenState (actual display state).
    // Prefer screenState (actual) over screenSwitch (config); fall back to screenSwitch.
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

    // ---- Nightlight (WEUR only -- passive read on US/WUSR) ----
    // nightLight is a nested map: {nightLightSwitch: 0|1, brightness: 0-100}.
    // Always parse it defensively; only emit events when state.deviceType == WEUR
    // to avoid confusing US/WUSR users with nightlight events their hardware can't produce.
    // Exception: if nightLight data IS present on a non-WEUR device (unexpected), still
    // emit attributes — better to surface unexpected fields than silently suppress them.
    def nl = r?.nightLight
    if (nl instanceof Map) {
        def nlSwitchRaw = nl.nightLightSwitch
        def nlBrightness = nl.brightness
        if (nlSwitchRaw != null) {
            boolean nlOn = (nlSwitchRaw instanceof Boolean) ? nlSwitchRaw : ((nlSwitchRaw as Integer) == 1)
            device.sendEvent(name:"nightlightOn", value: nlOn ? "on" : "off")
        }
        if (nlBrightness != null) {
            device.sendEvent(name:"nightlightBrightness", value: nlBrightness as Integer)
        }
    }

    // ---- Info HTML (local variables only — avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null)    parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)       parts << "Target: ${targetH}%"
    if (mistVirtual != null)   parts << "Mist: L${mistVirtual} (1-9)"
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
