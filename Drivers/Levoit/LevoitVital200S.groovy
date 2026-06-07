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
 *  Levoit Vital 200S Air Purifier (LAP-V201S) — Hubitat driver
 *
 *  Targets:    LAP-V201S-WUS, LAP-V201S-WUSR, LAP-V201S-WEU, LAP-V201S-AASR
 *  Marketing:  Levoit Vital 200S, Vital 200S-P
 *  Reference:  pyvesync VeSyncAirBaseV2 + LAP-V201S.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-05-03: v2.5  Migrated shared methods to LevoitVitalPurifier library.
 *                      BP18 null-guard hardening on setMode/setSpeed/setDisplay/
 *                      setChildLock/setAutoPreference/setPetMode/setRoomSize.
 *                      State-change gate on setChildLock/setDisplay (C3).
 *                      Fixed setLevel log message: was "mist level", now "fan level" (C2).
 *                      recordError tags normalized to [method:].
 *    2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
 *                      LevoitDiagnostics library. Added recordError() ring-buffer calls at
 *                      all logError sites.
 *    2026-04-25: v2.0  Community fork initial release (Dan Cox). Built from canonical pyvesync
 *                      payloads. Capabilities: Switch, SwitchLevel, FanControl, AirQuality.
 *                      Setters: setSpeed (sleep/low/med/high/max), setMode (manual/auto/sleep/
 *                      pet), setPetMode, setAutoPreference, setRoomSize, setLightDetection,
 *                      setChildLock, setDisplay, setTimer, cancelTimer, resetFilter, toggle.
 *                      Reads PM2.5, AQ index, filter %, errorCode, timer state.
 *                      Diagnostic raw-response logging gated by debugOutput preference.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitVitalPurifier

metadata {
    definition(
        name: "Levoit Vital 200S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Vital 200S / 200S-P (LAP-V201S) — power, fan speed, mode, timer, AQ/PM2.5, filter health; canonical pyvesync payloads",
        version: "2.9",
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
        attribute "airQuality", "string"      // categorical label ("good"/"moderate"/etc.); type="string" on Vital line, "number" on Core 300S/400S/600S — families diverge. Use airQualityIndex for cross-family numeric comparisons.
        attribute "petMode", "string"
        attribute "autoPreference", "string"
        attribute "roomSize", "number"
        attribute "lightDetection", "string"
        attribute "lightDetected", "string"
        attribute "childLock", "string"
        attribute "display", "string"
        attribute "info", "string"
        attribute "errorCode", "number"
        attribute "timerRemain", "number"
        attribute "diagnostics", "string"
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online", "string"

        command "setDisplay", [[name:"Display*", type: "ENUM", constraints: ["on","off"]]]
        command "setSpeed", [[name:"Speed*", type: "ENUM", constraints: ["off","sleep","low","medium","high","max"]]]
        command "setMode",  [[name:"Mode*", type: "ENUM", constraints: ["manual","sleep","auto","pet"]]]
        command "setPetMode", [[name:"Pet Mode*", type: "ENUM", constraints: ["on","off"]]]
        command "setAutoPreference", [[name:"Preference*", type: "ENUM", constraints: ["default","efficient","quiet"]]]
        command "setRoomSize", [[name:"Room Size*", type: "NUMBER"]]
        command "setLightDetection", [[name:"Light Detection*", type: "ENUM", constraints: ["on","off"]]]
        command "setChildLock", [[name:"Child Lock*", type: "ENUM", constraints: ["on","off"]]]
        command "toggle"
        command "resetFilter"
        command "setTimer", [[name:"Minutes*", type:"NUMBER", description:"Auto-off after N minutes; 0 cancels"]]
        command "cancelTimer"
        command "captureDiagnostics"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: false
        input "skipSpeedOnTurnOn", "bool", title: "Skip speed/mode configuration when turning on (faster)?", defaultValue: true
    }
}

// ---------- V200S-only setter ----------
// setLightDetection is present on V200S but NOT V100S (LIGHT_DETECT feature flag distinction).
// Not extracted to the shared library because it is per-driver-only.
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setLightDetection(onOff) {
    logDebug "setLightDetection(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setLightDetection")) return
    // BP25: normalize to lowercase before C3 gate and payload coercion.
    String v = (onOff as String).trim().toLowerCase()
    // Canonical on/off derived from truthy test — sendEvent always emits "on" or "off".
    String canon = canonOnOff(v)
    // C3 state-change gate: suppress redundant cloud calls when value already matches attribute.
    if (device.currentValue("lightDetection") == canon) return
    def resp = hubBypass("setLightDetection", [lightDetectionSwitch: (canon == "on") ? 1 : 0], "setLightDetection(${canon})")
    if (httpOk(resp)) device.sendEvent(name:"lightDetection", value: canon)
}

def applyStatus(status) {
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    seedPrefs()
    def r = peelEnvelope(status)
    // Diagnostic: gated by debugOutput pref — quiet in production, easy to triage when needed.
    logDebug "applyStatus raw r keys=${r?.keySet()}, values=${r}"

    def powerOn = r.powerSwitch == 1
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // Filter
    if (r.filterLifePercent != null) {
        device.sendEvent(name:"filter", value: r.filterLifePercent as Integer)
        Integer fl = r.filterLifePercent as Integer
        Integer lastFl = state.lastFilterLife as Integer
        if (lastFl == null || lastFl >= 20) {
            if (fl < 10) logInfo "Filter life critically low at ${fl}%"
            else if (fl < 20) logInfo "Filter life at ${fl}% — consider replacement"
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

    // Fan speed reporting: prefer fanSpeedLevel unless 255 (off), else manualSpeedLevel
    Integer sp = null
    if (r.fanSpeedLevel != null && r.fanSpeedLevel != 255) sp = r.fanSpeedLevel as Integer
    else if (r.manualSpeedLevel != null) sp = r.manualSpeedLevel as Integer
    sp = sp ?: 2
    state.speed = mapIntegerToSpeed(sp)
    // When device is off, speed reports "off" regardless of last manual setting (avoids "switch=off, speed=high" contradiction)
    if (!powerOn) {
        device.sendEvent(name:"speed", value: "off")
    } else {
        switch(workMode) {
            case "manual": device.sendEvent(name:"speed", value: state.speed); break
            case "sleep":  device.sendEvent(name:"speed", value:"on"); break
            default:       device.sendEvent(name:"speed", value:"auto"); break
        }
    }

    // AQ — compute label locally so we can also use it in the info HTML below (avoids race with sendEvent)
    String localAQ = null
    if (r.AQLevel != null) {
        def idx = r.AQLevel as Integer
        device.sendEvent(name:"airQualityIndex", value: idx)
        localAQ = ["unknown","good","moderate","poor","very poor"][(idx in 1..4)? idx : 0]
        device.sendEvent(name:"airQuality", value: localAQ)
    }
    if (r.PM25 != null) device.sendEvent(name:"pm25", value: r.PM25 as Integer)

    // Preferences / misc
    if (r.autoPreference) {
        def t = r.autoPreference.autoPreferenceType ?: "default"
        def rs = (r.autoPreference.roomSize ?: 0) as Integer
        state.autoPreference = t; state.roomSize = rs
        device.sendEvent(name:"autoPreference", value: t)
        device.sendEvent(name:"roomSize", value: rs)
    }

    // V200S-only: light detection feature (LIGHT_DETECT flag present for V201S; absent for V102S)
    device.sendEvent(name:"lightDetection", value: r.lightDetectionSwitch == 1 ? "on":"off")
    device.sendEvent(name:"lightDetected",  value: r.environmentLightState == 1 ? "yes":"no")
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

// logDebug, logError, logInfo, logDebugOff, ensureDebugWatchdog, ensureSwitchOn, requireNotNull
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// All other methods (lifecycle, power, speed, mode, timer, update, hubBypass, httpOk, etc.)
// are provided by #include level99.LevoitVitalPurifier (LevoitVitalPurifierLib.groovy).

// ------------- END -------------
