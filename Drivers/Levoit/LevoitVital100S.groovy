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
 *    2026-05-03: v2.5  Migrated shared methods to LevoitVitalPurifier library.
 *                      BP18 null-guard hardening on setMode/setSpeed/setDisplay/
 *                      setChildLock/setAutoPreference/setPetMode/setRoomSize.
 *                      State-change gate on setChildLock/setDisplay (C3).
 *                      recordError tags normalized to [site:].
 *    2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
 *                      LevoitDiagnostics library. Added recordError() ring-buffer calls at
 *                      all logError sites.
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

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitVitalPurifier

metadata {
    definition(
        name: "Levoit Vital 100S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.1] Levoit Vital 100S (LAP-V102S) — power, fan speed, mode, timer, AQ/PM2.5, filter health; canonical pyvesync payloads",
        version: "2.5",
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
        attribute "airQuality", "string"
        attribute "petMode", "string"
        attribute "autoPreference", "string"
        attribute "roomSize", "number"
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

def applyStatus(status) {
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

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
    logDebug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

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

// ------------- END -------------
