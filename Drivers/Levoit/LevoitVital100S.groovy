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
 *  Levoit Vital 100S Air Purifier (LAP-V102S) — Hubitat driver
 *
 *  Targets:    LAP-V102S-AASR, LAP-V102S-WUS, LAP-V102S-WEU,
 *              LAP-V102S-AUSR, LAP-V102S-WJP, LAP-V102S-AJPR, LAP-V102S-AEUR
 *  Marketing:  Levoit Vital 100S
 *  Reference:  pyvesync VeSyncAirBaseV2 + LAP-V102S.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  Delta vs Vital 200S (same VeSyncAirBaseV2 class):
 *    - NO LIGHT_DETECT feature: lightDetectionSwitch / environmentLightState fields
 *      ARE present in the response model (shared with V201S) but are intentionally
 *      ignored — no attribute declarations, no setters, no sendEvent calls.
 *
 *  History:
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Preview driver —
 *                      built from canonical pyvesync LAP-V102S.yaml fixture and
 *                      VeSyncAirBaseV2 class semantics. Maintainer has no V100S
 *                      hardware; live-test via community captureDiagnostics() post-merge.
 *                      Capabilities: Switch, SwitchLevel, FanControl, AirQuality.
 *                      Setters: setSpeed (sleep/low/med/high/max), setMode (manual/auto/
 *                      sleep/pet), setPetMode, setAutoPreference, setRoomSize,
 *                      setChildLock, setDisplay, setTimer, cancelTimer, resetFilter, toggle.
 *                      Reads PM2.5, AQ index, filter %, errorCode, timer state.
 *                      Diagnostic raw-response logging gated by debugOutput preference.
 */

metadata {
    definition(
        name: "Levoit Vital 100S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Vital 100S (LAP-V102S) — power, fan speed, mode, timer, AQ/PM2.5, filter health; canonical pyvesync payloads",
        version: "2.0",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "SwitchLevel"
        capability "FanControl"
        capability "Actuator"
        capability "AirQuality"

        attribute "filter", "number"
        attribute "mode", "string"
        attribute "pm25", "number"
        attribute "airQualityIndex", "number"
        attribute "petMode", "string"
        attribute "autoPreference", "string"
        attribute "roomSize", "number"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "info", "string"
        attribute "errorCode", "number"
        attribute "timerRemain", "number"

        command "setDisplay", [[name:"Display*", type: "ENUM", constraints: ["on","off"]]]
        command "setSpeed", [[name:"Speed*", type: "ENUM", constraints: ["off","sleep","low","medium","high","max"]]]
        command "setMode",  [[name:"Mode*", type: "ENUM", constraints: ["manual","sleep","auto","pet"]]]
        command "setPetMode", [[name:"Pet Mode*", type: "ENUM", constraints: ["on","off"]]]
        command "setAutoPreference", [[name:"Preference*", type: "ENUM", constraints: ["default","efficient","quiet"]]]
        command "setRoomSize", [[name:"Room Size*", type: "NUMBER"]]
        command "setChildLock", [[name:"Child Lock*", type: "ENUM", constraints: ["on","off"]]]
        command "toggle"
        command "resetFilter"
        command "setTimer", [[name:"Minutes*", type:"NUMBER", description:"Auto-off after N minutes; 0 cancels"]]
        command "cancelTimer"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: false
        input "skipSpeedOnTurnOn", "bool", title: "Skip speed/mode configuration when turning on (faster)?", defaultValue: true
    }
}

def installed(){ logDebug "Installed ${settings}"; updated() }
def updated(){
    logDebug "Updated ${settings}"
    state.clear(); unschedule(); initialize()
    runIn(3, "update")
    if (settings?.debugOutput) runIn(1800, "logDebugOff")
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

def on(){
    logDebug "on()"

    // Prevent re-entrance loop
    if (state.turningOn) {
        logDebug "Already turning on, skipping recursive call"
        return
    }

    state.turningOn = true
    try {
        if (handlePower(true)) {
            logInfo "Power on"
            // CRITICAL: Update switch state IMMEDIATELY so Rooms/automations see device is on
            // This prevents them from resending the command while we're still configuring speed/mode
            device.sendEvent(name:"switch", value:"on")

            // Now attempt speed/mode configuration (these may fail but device is already on)
            runInMillis(500, "configureOnState")
        } else {
            logError "Failed to turn on device"
        }
    } finally {
        state.remove('turningOn')
    }
}

// Separate method to configure speed/mode after power is on
def configureOnState() {
    logDebug "configureOnState()"

    // Skip if user disabled speed configuration on turn-on
    if (settings?.skipSpeedOnTurnOn) {
        logDebug "Skipping speed/mode config (skipSpeedOnTurnOn=true)"
        return
    }

    if (device.currentValue("switch") != "on") return

    // Capture original mode before any writes so we don't read a mutated state.mode
    def origMode = state.mode ?: "manual"
    if (origMode == "manual") {
        // Single setLevel establishes manual mode + saved speed atomically (V2 quirk)
        def targetSpeed = state.speed && state.speed != "off" ? state.speed : "low"
        def lvl = mapSpeedToInteger(targetSpeed)
        def ok = setSpeedLevel(lvl)
        if (ok) {
            state.speed = targetSpeed
            state.mode = "manual"
            device.sendEvent(name: "speed", value: targetSpeed)
            device.sendEvent(name: "mode", value: "manual")
            device.sendEvent(name: "petMode", value: "off")
        }
    } else {
        // auto/sleep/pet — set mode directly (no speed write needed)
        setMode(origMode)
    }
}

def off(){
    logDebug "off()"

    // Prevent re-entrance loop
    if (state.turningOff) {
        logDebug "Already turning off, skipping recursive call"
        return
    }

    state.turningOff = true
    try {
        if (handlePower(false)) {
            logInfo "Power off"
            // CRITICAL: Update switch state IMMEDIATELY
            device.sendEvent(name:"switch", value:"off")
        } else {
            logError "Failed to turn off device"
        }
    } finally {
        state.remove('turningOff')
    }
}

def toggle(){ logDebug "toggle()"; device.currentValue("switch")=="on" ? off() : on() }

def cycleSpeed(){
    logDebug "cycleSpeed()"
    def cur = state.speed ?: "low"
    def next = ["sleep":"low","low":"medium","medium":"high","high":"max","max":"sleep"][cur] ?: "low"
    if (device.currentValue("switch")!="on") on()
    setSpeed(next)
}

def setLevel(val){
    logDebug "setLevel $val"
    Integer lvl
    if (val < 20) lvl=1
    else if (val < 40) lvl=2
    else if (val < 60) lvl=3
    else lvl=4
    sendEvent(name:"level", value: val)
    def ok = setSpeedLevel(lvl)
    if (ok) {
        // setLevel establishes manual mode + speed atomically (V2 quirk); emit mode events here
        state.mode = "manual"
        device.sendEvent(name:"mode", value: "manual")
        device.sendEvent(name:"petMode", value: "off")
        logInfo "Level: ${val}% (fan level ${lvl})"
    }
}

def setSpeed(spd){
    logDebug "setSpeed(${spd})"
    if (spd=="off") return off()
    if (spd=="sleep") { setMode("sleep"); device.sendEvent(name:"speed", value:"on"); return }

    // Only check power if we're not already turning on
    if (!state.turningOn && device.currentValue("switch")!="on") on()

    // setLevel establishes manual mode + speed atomically; no setMode("manual") pre-call needed (V2 quirk)
    def lvl = mapSpeedToInteger(spd)
    def ok = setSpeedLevel(lvl)
    if (ok){
        state.speed = spd
        state.mode = "manual"
        device.sendEvent(name:"speed", value: spd)
        device.sendEvent(name:"mode", value: "manual")
        device.sendEvent(name:"petMode", value: "off")
        logInfo "Speed: ${spd}"
    }
}

def setSpeedLevel(level){
    logDebug "setSpeedLevel(${level})"
    def ok = writeSpeedPreferred(level)
    if (!ok) logError "Speed write failed for level ${level}"
    return ok
}

// ---------- Mode ----------
// V2-line mode-write split (per pyvesync LAP-V102S.yaml fixture):
//   manual: there is no separate "set manual mode" command; manual mode is established by
//           sending a speed via setLevel (levelIdx/levelType/manualSpeedLevel).
//   auto/sleep/pet: use setPurifierMode with {workMode: <mode>}.
def setMode(mode){
    logDebug "setMode(${mode})"

    // Only check power if we're not already turning on
    if (!state.turningOn && device.currentValue("switch") != "on") {
        logDebug "Device off, skipping mode change"
        return false
    }

    boolean ok = false
    if (mode == "manual") {
        // V2-line: manual mode is established by sending a speed via setLevel (no separate mode command)
        int spLevel = mapSpeedToInteger(state.speed ?: "low")
        def resp = hubBypass("setLevel", [levelIdx: 0, levelType: "wind", manualSpeedLevel: spLevel], "setMode->setLevel(${spLevel})")
        ok = httpOk(resp)
    } else if (mode in ["auto", "sleep", "pet"]) {
        def resp = hubBypass("setPurifierMode", [workMode: mode as String], "setPurifierMode(${mode})")
        ok = httpOk(resp)
    } else {
        logError "Unknown mode: ${mode}"
        return false
    }

    if (ok) {
        state.mode = mode
        device.sendEvent(name: "mode", value: (mode == "pet" ? "pet" : mode))
        device.sendEvent(name: "petMode", value: (mode == "pet" ? "on" : "off"))
        if (mode == "manual") device.sendEvent(name: "speed", value: state.speed ?: "low")
        else if (mode == "sleep") device.sendEvent(name: "speed", value: "on")
        else device.sendEvent(name: "speed", value: "auto")
        logInfo "Mode: ${mode}"
    } else {
        logError "Mode write failed for ${mode}"
    }
    return ok
}

// ---------- Feature setters ----------
def setPetMode(onOff){ setMode(onOff=="on" ? "pet" : "auto") }

def setAutoPreference(pref){
    logDebug "setAutoPreference(${pref})"
    def resp = hubBypass("setAutoPreference", [autoPreferenceType: pref, roomSize: state.roomSize ?: 600], "setAutoPreference")
    if (httpOk(resp)){ state.autoPreference = pref; device.sendEvent(name:"autoPreference", value: pref) }
}

def setRoomSize(sz){
    logDebug "setRoomSize(${sz})"
    def resp = hubBypass("setAutoPreference", [autoPreferenceType: state.autoPreference ?: "default", roomSize: sz], "setAutoPreference(roomSize)")
    if (httpOk(resp)){ state.roomSize = sz as Integer; device.sendEvent(name:"roomSize", value: sz as Integer) }
}

def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    def resp = hubBypass("setChildLock", [childLockSwitch: onOff=="on" ? 1:0], "setChildLock")
    if (httpOk(resp)) device.sendEvent(name:"childLock", value: onOff)
}

def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    def resp = hubBypass("setDisplay", [screenSwitch: onOff=="on" ? 1:0], "setDisplay")
    if (httpOk(resp)) device.sendEvent(name:"display", value: onOff)
}

def resetFilter(){
    logDebug "resetFilter()"
    def resp = hubBypass("resetFilterLife", [:], "resetFilterLife")
    if (httpOk(resp)) logDebug "Filter reset requested"
}

// ---------- Timer (V2-line uses addTimerV2 / delTimerV2) ----------
def setTimer(minutes){
    int n = (minutes as Integer) ?: 0
    logDebug "setTimer(${n} min)"
    if (n <= 0) { cancelTimer(); return }
    int secs = n * 60
    def data = [
        enabled: true,
        startAct: [[type: "powerSwitch", act: 0]],  // act:0 = power off when timer fires
        tmgEvt: [clkSec: secs]
    ]
    def resp = hubBypass("addTimerV2", data, "addTimerV2(${n}min)")
    if (httpOk(resp)) {
        def tid = resp?.data?.result?.id
        if (tid != null) state.timerId = tid
        logInfo "Timer set: power off in ${n} minutes (id=${tid})"
    }
}

def cancelTimer(){
    logDebug "cancelTimer()"
    if (!state.timerId) {
        logDebug "No active timer to cancel"
        return
    }
    def resp = hubBypass("delTimerV2", [id: state.timerId, subDeviceNo: 0], "delTimerV2(id=${state.timerId})")
    if (httpOk(resp)) {
        state.remove("timerId")
        logInfo "Timer cancelled"
    }
}

// ---------- Update / status ----------
def update(){
    logDebug "update()"
    def resp = hubBypass("getPurifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) logError "No status returned from getPurifierStatus"
        else applyStatus(status)
    }
}

// Called by parent driver with status updates.
// status arg structure: [code:0, result:[powerSwitch, workMode, AQLevel, PM25, ...]]
// Parent passes the full API response; do NOT wrap in [result: status] — it already has .result
def update(status) {
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg variant — parent driver calls update(status, nightLight); nightLight unused on V100S
// REQUIRED: parent always dispatches with 2 args; missing this causes MissingMethodException.
def update(status, nightLight) {
    logDebug "update() from parent (2-arg, nightLight ignored)"
    applyStatus(status)
    return true
}

def applyStatus(status){
    logDebug "applyStatus()"
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older
    // driver type without Save (Bug Pattern #12 — forward-compat migration safety).
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }
    def r = status?.result ?: [:]
    // Defensive envelope peel: V2-line bypassV2 responses can be double-wrapped.
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // Single-wrapped (normal purifier) exits immediately (peelGuard stays 0).
    // Double-wrapped (humidifier shape applied to purifier) peels once.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic: gated by debugOutput pref — quiet in production, easy to triage when needed.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    def powerOn = r.powerSwitch == 1
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // Filter
    if (r.filterLifePercent != null) {
        device.sendEvent(name:"filter", value: r.filterLifePercent as Integer)
        Integer fl = r.filterLifePercent as Integer
        Integer lastFl = state.lastFilterLife as Integer
        if (lastFl == null || lastFl >= 20) {
            if (fl < 10) logInfo "Filter life critically low at ${fl}%"
            else if (fl < 20) logInfo "Filter life at ${fl}% -- consider replacement"
        } else if (lastFl >= 10 && fl < 10) {
            logInfo "Filter life critically low at ${fl}%"
        }
        state.lastFilterLife = fl
    }

    // Mode
    def workMode = (r.workMode ?: "manual") as String
    state.mode = workMode
    device.sendEvent(name:"mode", value: workMode=="pet" ? "pet" : workMode)
    device.sendEvent(name:"petMode", value: workMode=="pet" ? "on" : "off")

    // Fan speed reporting: prefer fanSpeedLevel unless 255 (off/idle sentinel), else manualSpeedLevel
    // Bug Pattern #6: fanSpeedLevel==255 means "off"; map to 0 before any speed calculation.
    Integer sp = null
    if (r.fanSpeedLevel != null && r.fanSpeedLevel != 255) sp = r.fanSpeedLevel as Integer
    else if (r.manualSpeedLevel != null) sp = r.manualSpeedLevel as Integer
    sp = sp ?: 2
    state.speed = mapIntegerToSpeed(sp)
    // When device is off, speed reports "off" regardless of last manual setting (avoids
    // "switch=off, speed=high" contradiction — Bug Pattern #6 sentinel handling).
    if (!powerOn) {
        device.sendEvent(name:"speed", value: "off")
    } else {
        switch(workMode){
            case "manual": device.sendEvent(name:"speed", value: state.speed); break
            case "sleep":  device.sendEvent(name:"speed", value:"on"); break
            default:       device.sendEvent(name:"speed", value:"auto"); break
        }
    }

    // AQ — compute label locally so we can also use it in the info HTML below (avoids race with sendEvent)
    String localAQ = null
    if (r.AQLevel != null){
        def idx = r.AQLevel as Integer
        device.sendEvent(name:"airQualityIndex", value: idx)
        localAQ = ["unknown","good","moderate","poor","very poor"][(idx in 1..4)? idx : 0]
        device.sendEvent(name:"airQuality", value: localAQ)
    }
    if (r.PM25 != null) device.sendEvent(name:"pm25", value: r.PM25 as Integer)

    // Preferences / misc
    if (r.autoPreference){
        def t = r.autoPreference.autoPreferenceType ?: "default"
        def rs = (r.autoPreference.roomSize ?: 0) as Integer
        state.autoPreference = t; state.roomSize = rs
        device.sendEvent(name:"autoPreference", value: t)
        device.sendEvent(name:"roomSize", value: rs)
    }

    // CROSS-CHECK [HA cross-check finding #4 / pyvesync device_map.py line ~898]:
    //   Decision: lightDetectionSwitch and environmentLightState fields ARE present in the
    //     V102S response (shared PurifierVitalDetailsResult model with V201S) but are
    //     intentionally ignored here -- no attribute declarations, no sendEvent calls.
    //   Rationale: pyvesync device_map.py lists V102S with features=[AIR_QUALITY] only; the
    //     LIGHT_DETECT feature flag is NOT present for V102S (it IS present for V201S). The
    //     feature flag is pyvesync's authoritative per-model capability gate. The ST+HB cross-
    //     check claimed lightDetectionSwitch is "live boolean, implement toggle", but that ignored
    //     the V102S-vs-V201S feature-flag distinction. The HA finding (which correctly checks
    //     device_map.py) wins.
    //   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
    //     (V102S HumidifierMap entry; LIGHT_DETECT absent); HA vesync integration cross-check
    //     finding #4 (2026-04-26).
    //   Refutation: community user confirms setLightDetection WORKS on V102S hardware -->
    //     expose the toggle conditionally (feature-flag check or simple expose).

    device.sendEvent(name:"childLock", value: r.childLockSwitch == 1 ? "on":"off")
    device.sendEvent(name:"display",   value: r.screenSwitch == 1 ? "on":"off")

    // Error code and timer remain
    if (r.errorCode != null) {
        Integer ec = r.errorCode as Integer
        device.sendEvent(name: "errorCode", value: ec)
        Integer lastEc = state.lastErrorCode as Integer
        if (ec != 0 && (lastEc == null || lastEc == 0)) logInfo "Device error code: ${ec}"
        state.lastErrorCode = ec
    }
    if (r.timerRemain != null) device.sendEvent(name: "timerRemain", value: r.timerRemain as Integer)

    // Info HTML — use local variables (sendEvent is async, currentValue may return stale)
    def html = "Filter: ${r.filterLifePercent ?: 0}%"
    if (localAQ)            html += "<br>Air Quality: ${localAQ}"
    if (r.PM25   != null)   html += "<br>PM2.5: ${r.PM25} µg/m³"
    device.sendEvent(name:"info", value: html)
}

// ---------- Internal helpers ----------
def mapSpeedToInteger(s){
    switch("${s}"){
        case "sleep": return 1
        case "low":   return 2
        case "medium":return 3
        case "high":  return 4
        case "max":   return 4
        default:      return 2
    }
}
def mapIntegerToSpeed(n){
    switch(n as Integer){
        case 1: return "sleep"
        case 2: return "low"
        case 3: return "medium"
        case 4: return "high"
        case 255: return "off"
        default: return "low"
    }
}

def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

// Hub/parent call wrapper
private hubBypass(method, Map data=[:], tag=null, cb=null){
    def rspObj = [ status: -1, data: null ]
    parent.sendBypassRequest(device, [ method: method, source:"APP", data: data ]) { resp ->
        rspObj = [ status: resp?.status, data: resp?.data ]
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
        if (inner == null) return true
        if (inner == 0) return true
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"
    return false
}

// ---------- Power ----------
// Canonical V102S payload per pyvesync: {powerSwitch: 0/1, switchIdx: 0}
def handlePower(on){
    logDebug "handlePower: ${on}"
    def resp = hubBypass("setSwitch", [powerSwitch: on ? 1 : 0, switchIdx: 0], "setSwitch(power=${on?1:0})")
    if (httpOk(resp)){
        logDebug "Power response ${resp.status} ${resp.data}"
        return true
    }
    return false
}

// ---------- Preferred V2-line speed write (canonical pyvesync V102S payload) ----------
// Canonical V102S payload per pyvesync LAP-V102S.yaml fixture:
//   method: setLevel
//   data: { levelIdx: 0, levelType: "wind", manualSpeedLevel: <1..4> }
// NOTE field names: levelIdx (not switchIdx), levelType (not type), manualSpeedLevel (not level)
// Bug Pattern #4: using the Core-line names (level/id/type) returns inner code -1 on V2 devices.
private boolean writeSpeedPreferred(level){
    def resp = hubBypass("setLevel", [ levelIdx: 0, levelType: "wind", manualSpeedLevel: level as Integer ], "setLevel{levelIdx,levelType,manualSpeedLevel}")
    if (httpOk(resp)) return true
    return false
}

// ------------- END -------------
