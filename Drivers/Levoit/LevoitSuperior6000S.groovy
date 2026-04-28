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
 *  Levoit Superior 6000S Evaporative Humidifier (LEH-S601S) — Hubitat driver
 *
 *  Targets:    LEH-S601S-WUS, LEH-S601S-WUSR, LEH-S601S-WEUR, LEH-S602S-WUS
 *  Marketing:  Levoit Superior 6000S Smart Evaporative Humidifier
 *  Reference:  pyvesync VeSyncSuperior6000S + LEH-S601S.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-25: v2.0  Community fork initial release (Dan Cox). Full driver based on canonical
 *                      pyvesync payloads. Capabilities: Switch, SwitchLevel, RelativeHumidity-
 *                      Measurement, TemperatureMeasurement, Refresh, Actuator. Setters: setMode
 *                      (manual/auto/sleep), setMistLevel (1-9), setTargetHumidity (30-80%),
 *                      setDisplay, setChildLock, setAutoStop, setDryingMode, toggle. Reads
 *                      humidity, target humidity, temperature, mist level, water status,
 *                      drying-mode state (active/complete/idle/off), auto-stop state, water-
 *                      pump cleaning cycle, wick filter %, timer remaining. Handles double-
 *                      wrapped bypassV2 envelope. Diagnostic raw-response logging gated by
 *                      debugOutput preference for ongoing field diagnostics.
 */

metadata {
    definition(
        name: "Levoit Superior 6000S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Superior 6000S (LEH-S601S) evaporative humidifier — mist 1-9, target humidity, modes, drying mode, auto-stop, water pump cleaning, ambient temp; canonical pyvesync payloads",
        version: "2.2.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "SwitchLevel"                    // mist level 1-9 mapped to 0-100
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "TemperatureMeasurement"         // ambient temp from device sensor
        capability "Actuator"
        capability "Refresh"

        attribute "mode", "string"               // auto | manual | sleep (reverse-mapped from autoPro)
        attribute "mistLevel", "number"          // 1-9, actual reported
        attribute "virtualLevel", "number"       // 1-9, set level
        attribute "targetHumidity", "number"     // %, target
        attribute "water", "string"              // ok | empty | removed
        attribute "display", "string"            // on | off
        attribute "childLock", "string"          // on | off
        attribute "autoStopConfig", "string"     // on | off
        attribute "autoStopActive", "string"     // yes | no
        attribute "dryingMode", "string"         // active | complete | idle | off (auto-drying state)
        attribute "dryingTimeRemain", "number"   // seconds remaining in drying cycle
        attribute "wickFilterLife", "number"     // 0-100 %
        attribute "timerRemain", "number"        // seconds
        attribute "pumpCleanStatus", "string"    // cleaning | idle (water pump self-clean cycle)
        attribute "pumpCleanRemain", "number"    // seconds remaining in pump clean cycle
        attribute "info", "string"               // HTML summary

        command "setMode",            [[name:"Mode*",            type:"ENUM",   constraints:["auto","manual","sleep"]]]
        command "setMistLevel",       [[name:"Level*",           type:"NUMBER", description:"1-9"]]
        command "setTargetHumidity",  [[name:"Percent*",         type:"NUMBER", description:"30-80"]]
        command "setDisplay",         [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setChildLock",       [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",        [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setDryingMode",      [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
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
    runIn(3, refresh)
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, logDebugOff)
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

// ---------- Power ----------
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [powerSwitch: 1, switchIdx: 0], "setSwitch(power=1)")
    if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
    else logError "Power on failed"
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [powerSwitch: 0, switchIdx: 0], "setSwitch(power=0)")
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
def setMode(mode){
    logDebug "setMode(${mode})"
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","manual","sleep"])) { logError "Invalid mode: ${m}"; return }
    // autoPro is the canonical API value for "auto" on Superior 6000S
    String apiMode = (m == "auto") ? "autoPro" : m
    def resp = hubBypass("setHumidityMode", [workMode: apiMode], "setHumidityMode(${apiMode})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"
    }
}

// ---------- Mist level ----------
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    if (device.currentValue("switch") != "on") on()
    def resp = hubBypass("setVirtualLevel", [levelIdx: 0, virtualLevel: lvl, levelType: "mist"], "setVirtualLevel(${lvl})")
    if (httpOk(resp)) {
        state.virtualLevel = lvl
        device.sendEvent(name:"virtualLevel", value: lvl)
        device.sendEvent(name:"level", value: percentFromLevel(lvl))
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"
    }
}

// SwitchLevel capability path: map 0-100 to 1-9
def setLevel(val){
    logDebug "setLevel(${val})"
    Integer pct = Math.max(0, Math.min(100, (val as Integer) ?: 0))
    Integer lvl = levelFromPercent(pct)
    sendEvent(name:"level", value: pct)
    setMistLevel(lvl)
}

// ---------- Target humidity ----------
def setTargetHumidity(percent){
    logDebug "setTargetHumidity(${percent})"
    Integer p = Math.max(30, Math.min(80, (percent as Integer) ?: 50))
    def resp = hubBypass("setTargetHumidity", [targetHumidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"
    }
}

// ---------- Feature setters ----------
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${onOff})")
    if (httpOk(resp)) device.sendEvent(name:"display", value: onOff)
    else logError "Display write failed"
}

def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setChildLock", [childLockSwitch: v], "setChildLock(${onOff})")
    if (httpOk(resp)) device.sendEvent(name:"childLock", value: onOff)
    else logError "Child lock write failed"
}

def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setAutoStopSwitch", [autoStopSwitch: v], "setAutoStopSwitch(${onOff})")
    if (httpOk(resp)) { device.sendEvent(name:"autoStopConfig", value: onOff); logInfo "Auto-stop: ${onOff}" }
    else logError "Auto-stop write failed"
}

def setDryingMode(onOff){
    logDebug "setDryingMode(${onOff})"
    Integer v = (onOff == "on") ? 1 : 0
    def resp = hubBypass("setDryingMode", [autoDryingSwitch: v], "setDryingMode(${onOff})")
    if (httpOk(resp)) logInfo "Drying mode auto-switch set: ${onOff}"
    else logError "Drying mode write failed"
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
// Self-fetch when called directly
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getHumidifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) logError "No status returned from getHumidifierStatus"
        else applyStatus(status)
    }
}

// Parent-poll, 1-arg signature (some humidifier parent paths use this)
def update(status){
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg variant — parent driver calls update(status, nightLight); nightLight not applicable to humidifiers
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

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }
    def r = status?.result ?: [:]
    // Humidifier responses are double-wrapped (envelope inside envelope on bypassV2 for some
    // device classes). Peel through any [code, result, traceId] layers until we find device data.
    // V201S purifier was single-wrapped; this peel is robust for both.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump — gated by debugOutput. Keep for ongoing field diagnostics.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // Power
    def powerOn = (r.powerSwitch as Integer) == 1
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // Current ambient humidity
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // Target humidity (auto setpoint)
    if (r.targetHumidity != null) device.sendEvent(name:"targetHumidity", value: r.targetHumidity as Integer)

    // Temperature: API gives F × 10 (e.g. 683 → 68.3°F). Round to 1 decimal via Math.round.
    if (r.temperature != null) {
        double tempF = (r.temperature as Integer) / 10.0
        if (location?.temperatureScale == "C") {
            double tempC = (tempF - 32) * 5.0 / 9.0
            device.sendEvent(name:"temperature", value: Math.round(tempC * 10) / 10.0, unit:"°C")
        } else {
            device.sendEvent(name:"temperature", value: Math.round(tempF * 10) / 10.0, unit:"°F")
        }
    }

    // Mode — reverse-map autoPro -> auto for user-friendly reporting
    String workMode = (r.workMode ?: "manual") as String
    String reportedMode = (workMode == "autoPro") ? "auto" : workMode
    state.mode = reportedMode
    device.sendEvent(name:"mode", value: reportedMode)

    // Mist levels: mistLevel = actual reported, virtualLevel = requested set level
    if (r.mistLevel != null) device.sendEvent(name:"mistLevel", value: r.mistLevel as Integer)
    if (r.virtualLevel != null) {
        Integer vl = r.virtualLevel as Integer
        device.sendEvent(name:"virtualLevel", value: vl)
        // SwitchLevel: map 1-9 to 0-100
        device.sendEvent(name:"level", value: percentFromLevel(vl))
    }

    // Water status — removed takes priority over empty
    String water = "ok"
    if ((r.waterTankLifted as Integer) == 1)  water = "removed"
    else if ((r.waterLacksState as Integer) == 1) water = "empty"
    if (state.lastWater != water) logInfo "Water: ${water}"
    state.lastWater = water
    device.sendEvent(name:"water", value: water)

    // Display: prefer screenState (actual) over screenSwitch (config) if both present
    Integer screen = (r.screenState != null ? r.screenState : r.screenSwitch) as Integer
    device.sendEvent(name:"display", value: screen == 1 ? "on" : "off")

    // Child lock
    device.sendEvent(name:"childLock", value: (r.childLockSwitch as Integer) == 1 ? "on" : "off")

    // Auto-stop: config (switch) vs active state
    device.sendEvent(name:"autoStopConfig", value: (r.autoStopSwitch as Integer) == 1 ? "on" : "off")
    device.sendEvent(name:"autoStopActive", value: (r.autoStopState as Integer) == 1 ? "yes" : "no")

    // Drying mode — nested map. dryingState enum (per pyvesync DryingModes): 0=OFF, 1=DRYING, 2=COMPLETE
    if (r.dryingMode instanceof Map) {
        Integer dState  = r.dryingMode.dryingState as Integer
        Integer dAuto   = r.dryingMode.autoDryingSwitch as Integer
        Integer dRemain = r.dryingMode.dryingRemain as Integer
        String dryingStr
        switch (dState) {
            case 1:  dryingStr = "active";    break  // currently drying wick
            case 2:  dryingStr = "complete";  break  // just finished a drying cycle
            default: dryingStr = (dAuto == 1) ? "idle" : "off"  // 0 or null
        }
        if (state.lastDryingMode != dryingStr) {
            if (dryingStr == "active")   logInfo "Wick drying cycle started"
            if (dryingStr == "complete") logInfo "Wick drying cycle complete"
        }
        state.lastDryingMode = dryingStr
        device.sendEvent(name:"dryingMode",       value: dryingStr)
        device.sendEvent(name:"dryingTimeRemain", value: dRemain ?: 0)
    }

    // Water pump cleaning cycle status (Superior 6000S exclusive — undocumented in pyvesync)
    if (r.waterPump instanceof Map) {
        Integer pumpClean = r.waterPump.cleanStatus as Integer  // 0 = idle, 1 = cleaning
        Integer pumpRemain = r.waterPump.remainTime as Integer
        device.sendEvent(name:"pumpCleanStatus", value: pumpClean == 1 ? "cleaning" : "idle")
        device.sendEvent(name:"pumpCleanRemain", value: pumpRemain ?: 0)
    }

    // Wick filter life
    if (r.filterLifePercent != null) {
        Integer wfl = r.filterLifePercent as Integer
        device.sendEvent(name:"wickFilterLife", value: wfl)
        Integer lastWfl = state.lastWickLife as Integer
        if (lastWfl == null || lastWfl >= 20) {
            if (wfl < 10) logInfo "Wick filter at ${wfl}% — consider cleaning or replacement (urgent)"
            else if (wfl < 20) logInfo "Wick filter at ${wfl}% — consider cleaning or replacement"
        } else if (lastWfl >= 10 && wfl < 10) {
            logInfo "Wick filter at ${wfl}% — consider cleaning or replacement (urgent)"
        }
        state.lastWickLife = wfl
    }

    // Timer remaining
    if (r.timerRemain != null) device.sendEvent(name:"timerRemain", value: r.timerRemain as Integer)

    // Info HTML — use local variables; don't read device.currentValue (may race with sendEvent)
    def parts = []
    if (r.humidity != null)          parts << "Humidity: ${r.humidity as Integer}%"
    if (r.targetHumidity != null)    parts << "Target: ${r.targetHumidity as Integer}%"
    if (r.virtualLevel != null)      parts << "Mist: L${r.virtualLevel as Integer} (1-9)"
    parts << "Mode: ${reportedMode}"
    parts << "Water: ${water}"
    if (r.filterLifePercent != null) parts << "Wick: ${r.filterLifePercent as Integer}%"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
private int percentFromLevel(Integer lvl){
    if (lvl == null || lvl < 1) return 0
    if (lvl >= 9) return 100
    return Math.round((lvl - 1) * (100.0 / 8.0)) as int
}

private int levelFromPercent(Integer pct){
    if (pct == null || pct <= 0) return 1
    if (pct >= 100) return 9
    int lvl = Math.floor(pct * 8.0 / 100.0).intValue() + 1
    return Math.max(1, Math.min(9, lvl))
}

def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
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

// Hub/parent call wrapper
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
        logDebug "HTTP 200 innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"
    return false
}

// ------------- END -------------
