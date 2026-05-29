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

// REQUIRES: #include level99.LevoitDiagnostics  (provides recordError used in update())
// REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError, ensureDebugWatchdog,
//                                                hubBypass, httpOk)
// PROVIDES: cross-family infrastructure + V2-line and Classic-family shared helpers:
//   Lifecycle: installed, updated, uninstalled, initialize, refresh
//   Polling: update() (no-arg), update(status) (1-arg), update(status, nightLight) (2-arg)
//   Power: on, off (payload parameterized via per-driver powerPayload(boolean) hook),
//          toggle (lastSwitchSet preferred-state pattern)
//   HTTP plumbing: hubBypass, httpOk
//   V2-line shared bodies (OM1000S + Sprout + Sup6000S + LV600SHC for setDisplay):
//     doSetDisplayScreenSwitch — {screenSwitch: int} payload, emits displayOn
//     doSetAutoStopSwitch      — {autoStopSwitch: int} payload, emits autoStopEnabled
//   Classic-family shared bodies (Classic 200S/300S, Dual 200S, LV600S, OasisMist 450S):
//     doSetTargetHumidity       — {target_humidity: N} payload, floor/ceiling parameterized
//                                  (default 30/80; OasisMist 450S passes 40/80)
//     doSetDisplayStateSwitch   — {state: bool} payload, emits displayOn (4 of 5 drivers;
//                                  Classic 200S has its own setIndicatorLightSwitch override)
//     doSetAutoStopEnabled      — {enabled: bool} payload via "setAutomaticStop", emits
//                                  autoStopEnabled (all 5 Classic-family drivers)
//   Each driver declares a 1-line delegator (def setDisplay(o){doSetDisplayStateSwitch(o)})
//   so the public method name is stable while the body is shared. Helper names are distinct
//   (V2-line vs Classic-family) so all 9 humidifier drivers can #include this lib without
//   Groovy "duplicate method signature" conflicts.
//
// Per-driver methods retained (intentional family divergence):
//   powerPayload(boolean) hook (Classic {enabled,id} vs V2 {powerSwitch,switchIdx}),
//   setMode (5 wire-value variants),
//   setMistLevel (range + payload varies), setWarmMistLevel (3 drivers, 2 shapes),
//   setChildLock/setDryingMode (Sprout+Sup6000S only),
//   nightlight methods (per-driver), applyStatus (structurally family-divergent),
//   Classic 200S setDisplay (setIndicatorLightSwitch override -- the one model override
//     in pyvesync VeSyncHumid200S vs VeSyncHumid200300S; bodies are NOT identical).
//
// Behavior notes:
//   - update() (0-arg) recordError tag normalized to [site:"update"] across all 9 drivers
//     (audit found mixed [site:]/[method:] usage).
//   - httpOk() recordError tag normalized to [site:"httpOk"] (Sup6000S used [method:]).
//   - Infrastructure methods (update, httpOk) use [site:] tags; feature helpers
//     (doSetDisplayScreenSwitch, doSetAutoStopSwitch, doSetTargetHumidity,
//      doSetDisplayStateSwitch, doSetAutoStopEnabled) use [method:] tags matching the
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

// ---- Power ----
// Shared on()/off() bodies. The only per-driver difference is the setSwitch payload, supplied
// by the powerPayload(boolean) hook each driver declares:
//   Classic family (Classic 200S/300S, Dual 200S, OasisMist 450S, LV600S): {enabled: bool, id: 0}
//   V2 family (LV600S HubConnect, OasisMist 1000S, Sprout, Superior 6000S):
//                                                                  {powerSwitch: 0|1, switchIdx: 0}
// The diagnostic tag string passed as hubBypass's 3rd arg is derived from the payload so it stays
// accurate per-family without a second hook (the tag is a log string only; specs assert req.data,
// not the tag). state.turningOn re-entrance guard on on() prevents the
// ensureSwitchOn() -> on() -> setMistLevel() -> ensureSwitchOn() recursion.
// Each driver MUST declare: Map powerPayload(boolean on) { ... }
def on(){
    logDebug "on()"
    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        Map payload = powerPayload(true)
        def resp = hubBypass("setSwitch", payload, "setSwitch(${payload})")
        if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
        else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
    } finally {
        state.turningOn = false
    }
}

def off(){
    logDebug "off()"
    Map payload = powerPayload(false)
    def resp = hubBypass("setSwitch", payload, "setSwitch(${payload})")
    if (httpOk(resp)) { logInfo "Power off"; state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off") }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
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

// hubBypass, httpOk are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

// ---- V2-line shared feature setters ----
// Used by OM1000S, Sprout, Sup6000S, LV600SHC (setDisplay only).
// All 4 drivers use identical payload shape for setDisplay: {screenSwitch: Integer}
// OM1000S, Sprout, Sup6000S use identical payload for setAutoStop: {autoStopSwitch: Integer}
// Classic-family drivers (Classic 200S/300S, Dual 200S, LV600S, OM450S) keep per-driver
// implementations: different API methods (setAutomaticStop, setIndicatorLightSwitch) and
// different payload shapes ({enabled: bool}, {state: bool}).

// Intentionally omits the strict "on"/"off"-only gate that LevoitFanLib's helper of the same
// name uses. Permissive truthy-coercion behavior is preserved for back-compat with this
// driver family; truthy-variant inputs ("true", "1", "yes") are accepted and map to "on".
// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetDisplayScreenSwitch(onOff) {
    logDebug "setDisplay(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return false
    String val = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off",
    // never the raw normalized input ("true", "1", "yes"). The C3 gate compares canonical
    // vs canonical so truthy-variant input does not defeat same-state suppression.
    String canon = canonOnOff(val)
    if (device.currentValue("displayOn") == canon) return true
    Integer v = (canon == "on") ? 1 : 0
    def resp = hubBypass("setDisplay", [screenSwitch: v], "setDisplay(${canon})")
    Boolean ok = httpOk(resp)
    if (ok) {
        device.sendEvent(name:"displayOn", value: canon)
        logInfo "Display: ${canon}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
    return ok
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetAutoStopSwitch(onOff) {
    logDebug "setAutoStop(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setAutoStop")) return false
    String val = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off".
    String canon = canonOnOff(val)
    if (device.currentValue("autoStopEnabled") == canon) return true
    Integer v = (canon == "on") ? 1 : 0
    def resp = hubBypass("setAutoStopSwitch", [autoStopSwitch: v], "setAutoStopSwitch(${canon})")
    Boolean ok = httpOk(resp)
    if (ok) {
        device.sendEvent(name:"autoStopEnabled", value: canon)
        logInfo "Auto-stop: ${canon}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutoStopSwitch"])
    }
    return ok
}

// ---- Classic-family shared feature setters ----
// Used by Classic 200S, Classic 300S, Dual 200S, LV600S, OasisMist 450S (all VeSyncHumid200300S
// or sibling V1 humidifier classes). Distinct method names from V2-line helpers above to avoid
// confusion with their {screenSwitch:int} / {autoStopSwitch:int} payload shapes.

// setTargetHumidity payload: {target_humidity: N} -- snake_case key (V1 humidifier convention).
// Floor/ceiling parameterized: most drivers use 30/80; OasisMist 450S uses 40/80 (firmware
// floor per pyvesync issue #296). Caller passes overrides via the optional floor/ceiling args.
// BP24: NO-ON — configures a device preference (humidity target); powering on is not implied.
def doSetTargetHumidity(percent, Integer floor = 30, Integer ceiling = 80) {
    logDebug "setHumidity(${percent})"
    if (!requireNotNull(percent, "setHumidity")) return false
    Integer p = safeIntArg(percent, 0)
    if (p <= 0) { logWarn "setHumidity called with ${p} -- 0% is not a valid target humidity; ignoring"; return false }
    p = Math.max(floor, Math.min(ceiling, p))
    def resp = hubBypass("setTargetHumidity", [target_humidity: p], "setTargetHumidity(${p})")
    Boolean ok = httpOk(resp)
    if (ok) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"; recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
    return ok
}

// setDisplay payload: {state: bool} -- boolean form used by Classic 300S, Dual 200S, LV600S,
// OasisMist 450S. Distinct from V2-line doSetDisplayScreenSwitch ({screenSwitch: int}) and from
// Classic 200S's setIndicatorLightSwitch ({enabled: bool, id: 0}) override.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetDisplayStateSwitch(onOff) {
    logDebug "setDisplay(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return false
    String val = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off",
    // never the raw normalized input ("true", "1", "yes"). The C3 gate compares canonical
    // vs canonical so truthy-variant input does not defeat same-state suppression.
    String canon = canonOnOff(val)
    if (device.currentValue("displayOn") == canon) return true
    Boolean v = (canon == "on")
    def resp = hubBypass("setDisplay", [state: v], "setDisplay(${canon})")
    Boolean ok = httpOk(resp)
    if (ok) {
        device.sendEvent(name:"displayOn", value: canon)
        logInfo "Display: ${canon}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
    return ok
}

// setAutomaticStop payload: {enabled: bool} -- distinct from V2-line setAutoStopSwitch
// ({autoStopSwitch: int}). API method is also different ("setAutomaticStop" vs "setAutoStopSwitch").
// BP24: NO-ON — configures a device preference; powering on is not implied.
def doSetAutoStopEnabled(onOff) {
    logDebug "setAutoStop(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setAutoStop")) return false
    String val = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off".
    String canon = canonOnOff(val)
    if (device.currentValue("autoStopEnabled") == canon) return true
    Boolean v = (canon == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${canon})")
    Boolean ok = httpOk(resp)
    if (ok) {
        device.sendEvent(name:"autoStopEnabled", value: canon)
        logInfo "Auto-stop: ${canon}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutomaticStop"])
    }
    return ok
}
