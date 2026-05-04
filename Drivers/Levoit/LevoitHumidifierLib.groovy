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
// PROVIDES: 11 cross-family infrastructure methods byte-identical across all 9 humidifier drivers:
//   Lifecycle: installed, updated, uninstalled, initialize, refresh
//   Polling: update() (no-arg), update(status) (1-arg), update(status, nightLight) (2-arg)
//   Power: toggle (lastSwitchSet preferred-state pattern)
//   HTTP plumbing: hubBypass, httpOk
//
// Per-driver methods retained (intentional family divergence):
//   on/off (Classic vs V2 payload), setMode (5 wire-value variants),
//   setMistLevel (range + payload varies), setWarmMistLevel (3 drivers, 2 shapes),
//   setHumidity/setTargetHumidity (snake_case vs camelCase, floor varies),
//   setDisplay (3 shapes), setAutoStop (2 shapes), setChildLock/setDryingMode (Sprout+Sup6000S only),
//   nightlight methods (per-driver), applyStatus (structurally family-divergent).
//
// Behavior notes:
//   - update() (0-arg) recordError tag normalized to [site:"update"] across all 9 drivers
//     (audit found mixed [site:]/[method:] usage; lib uses [site:] uniformly).
//   - httpOk() recordError tag normalized to [site:"httpOk"] (Sup6000S used [method:]; lib uses [site:]).

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
