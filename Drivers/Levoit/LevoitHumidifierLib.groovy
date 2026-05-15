/*
 * MIT License
 *
 * Copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
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

library(
    name: "LevoitHumidifier",
    namespace: "level99",
    author: "Dan Cox (level99)",
    description: "Shared infrastructure methods for Levoit humidifier drivers (Classic 200S/300S, Dual 200S, LV600S, LV600S HubConnect, OasisMist 450S/1000S, Sprout, Superior 6000S — 9 drivers across 5 pyvesync classes).",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitHumidifierLib.groovy"
)

// REQUIRES: #include level99.LevoitDiagnostics  (provides recordError used in update() + httpOk())
// REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError, ensureDebugWatchdog)
// PROVIDES: 13 cross-family infrastructure + V2-line shared helpers:
//   Lifecycle: installed, updated, uninstalled, initialize, refresh
//   Polling: update() (no-arg), update(status) (1-arg), update(status, nightLight) (2-arg)
//   Power: toggle (lastSwitchSet preferred-state pattern)
//   HTTP plumbing: hubBypass, httpOk
//   V2-line shared bodies (OM1000S + Sprout + Sup6000S + LV600SHC for setDisplay):
//     doSetDisplayScreenSwitch — {screenSwitch: int} payload, emits displayOn
//     doSetAutoStopSwitch      — {autoStopSwitch: int} payload, emits autoStopEnabled
//   Each V2-line driver declares a 1-line delegator (def setDisplay(o){doSetDisplayScreenSwitch(o)})
//   so the public method name is stable while the body is shared. The helpers use unique
//   names to avoid Groovy "duplicate method signature" compile errors on the 5 Classic-family
//   drivers that also #include this lib and keep their own per-driver setDisplay/setAutoStop.
//
// Per-driver methods retained (intentional family divergence):
//   on/off (Classic vs V2 payload), setMode (5 wire-value variants),
//   setMistLevel (range + payload varies), setWarmMistLevel (3 drivers, 2 shapes),
//   setHumidity/setTargetHumidity (snake_case vs camelCase, floor varies),
//   setDisplay/setAutoStop for Classic-family ({state: bool}, {enabled: bool}, etc.): per-driver
//   setChildLock/setDryingMode (Sprout+Sup6000S only),
//   nightlight methods (per-driver), applyStatus (structurally family-divergent).
//
// Behavior notes:
//   - update() (0-arg) recordError tag normalized to [site:"update"] across all 9 drivers
//     (audit found mixed [site:]/[method:] usage).
//   - httpOk() recordError tag normalized to [site:"httpOk"] (Sup6000S used [method:]).
//   - Infrastructure methods (update, httpOk) use [site:] tags; V2-line feature helpers
//     (doSetDisplayScreenSwitch, doSetAutoStopSwitch) use [method:] tags matching the
//     per-driver convention they replace.

// ---- Lifecycle ----

def installed() {
    logDebug "Installed ${settings}"
    updated()
}

def updated() {
    logDebug "Updated ${settings}"
    state.clear(); unschedule(); initialize()
    runIn(3, "refresh")
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}

def uninstalled() {
    logDebug "Uninstalled"
}

def initialize() {
    logDebug "Initializing"
}

// ---- Refresh ----

def refresh() {
    update()
}

// ---- Toggle ----
// state.lastSwitchSet preferred over device.currentValue() to avoid the read-after-write
// race (the new event from on()/off() may not be queryable yet on a same-tick toggle()).
// Falls back to device.currentValue("switch") when state isn't seeded yet (first-call case).
def toggle() {
    logDebug "toggle()"
    String current = state.lastSwitchSet ?: device.currentValue("switch")
    current == "on" ? off() : on()
}

// ---- Update / status ----
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// below) passes data.result already. The peel-while-loop in applyStatus handles both shapes
// defensively, but the data-flow asymmetry means applyStatus's first peel iteration is doing
// different work depending on entry point.
def update() {
    logDebug "update() self-fetch"
    def resp = hubBypass("getHumidifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) { logError "No status returned from getHumidifierStatus"; recordError("No status returned from getHumidifierStatus", [site:"update"]) }
        else applyStatus(status)
    }
}

// 1-arg parent callback
def update(status) {
    logDebug "update() from parent (1-arg)"
    applyStatus(status)
    return true
}

// 2-arg parent callback — REQUIRED (BP#1); parent always calls with two args
def update(status, nightLight) {
    logDebug "update() from parent (2-arg, nightLight ignored)"
    applyStatus(status)
    return true
}

// ---- HTTP plumbing ----

// Hub/parent call wrapper — matches sibling driver pattern
private hubBypass(method, Map data=[:], tag=null, cb=null) {
    def rspObj = [status: -1, data: null]
    parent.sendBypassRequest(device, [method: method, source: "APP", data: data]) { resp ->
        rspObj = [status: resp?.status, data: resp?.data]
        def inner = resp?.data?.result?.code
        if (tag) logDebug "${tag} -> HTTP ${resp?.status}, inner ${inner}"
        if (cb) cb(resp)
    }
    return rspObj
}

private boolean httpOk(resp) {
    if (!resp) return false
    def st = resp.status as Integer
    if (st in [200,201,204]) {
        def inner = resp?.data?.result?.code
        if (inner == null || inner == 0) return true
        logDebug "HTTP 200, innerCode ${inner}"
        return false
    }
    logError "HTTP ${st}"; recordError("HTTP ${st}", [site:"httpOk"])
    return false
}

// ---- V2-line shared feature setters ----
// Used by OM1000S, Sprout, Sup6000S, LV600SHC (setDisplay only).
// All 4 drivers use identical payload shape for setDisplay: {screenSwitch: Integer}
// OM1000S, Sprout, Sup6000S use identical payload for setAutoStop: {autoStopSwitch: Integer}
// Classic-family drivers (Classic 200S/300S, Dual 200S, LV600S, OM450S) keep per-driver
// implementations: different API methods (setAutomaticStop, setIndicatorLightSwitch) and
// different payload shapes ({enabled: bool}, {state: bool}).

def doSetDisplayScreenSwitch(onOff) {
    logDebug "setDisplay(${onOff})"
    if (!requireNotNull(onOff, "setDisplay")) return false
    String val = (onOff as String).trim().toLowerCase()
    if (device.currentValue("displayOn") == val) return true
    Integer v = (val in ["on","true","1","yes"]) ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${val})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: val)
        logInfo "Display: ${val}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
}

def doSetAutoStopSwitch(onOff) {
    logDebug "setAutoStop(${onOff})"
    if (!requireNotNull(onOff, "setAutoStop")) return false
    String val = (onOff as String).trim().toLowerCase()
    if (device.currentValue("autoStopEnabled") == val) return true
    Integer v = (val in ["on","true","1","yes"]) ? 1 : 0
    def resp = hubBypass("setAutoStopSwitch", [autoStopSwitch: v], "setAutoStopSwitch(${val})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: val)
        logInfo "Auto-stop: ${val}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutoStopSwitch"])
    }
}
