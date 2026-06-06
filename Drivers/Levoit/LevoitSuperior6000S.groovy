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
 *    2026-05-03: v2.5  BREAKING — attribute renames for cross-line consistency:
 *                       display → displayOn, autoStopConfig → autoStopEnabled,
 *                       autoStopActive → autoStopReached. Existing dashboards/RM
 *                       rules referencing the old names must be updated. setDisplay
 *                       and setAutoStop now provided by LevoitHumidifierLib (V2-line
 *                       shared). One-week-since-v2.4-ship grace period; very few users.
 *    2026-05-03: v2.4.2  Phase 4 Round 5 — migrated to LevoitHumidifierLib (11 shared
 *                        methods removed). BP24-C partial guard on setMistLevel upgraded to full
 *                        BP24-B ensureSwitchOn(). BP18 requireNotNull on setDisplay + setChildLock
 *                        + setAutoStop + setDryingMode. C3 state-change gate on setDisplay +
 *                        setChildLock. logInfo added to setDisplay + setChildLock (NIT fold-in).
 *    2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
 *                      LevoitDiagnostics library. Added recordError() ring-buffer calls at
 *                      all logError sites.
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

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitHumidifier

metadata {
    definition(
        name: "Levoit Superior 6000S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "Levoit Superior 6000S (LEH-S601S) evaporative humidifier — mist 1-9, target humidity, modes, drying mode, auto-stop, water pump cleaning, ambient temp; canonical pyvesync payloads",
        version: "2.8",
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
        attribute "displayOn", "string"          // on | off
        attribute "childLock", "string"          // on | off
        attribute "autoStopEnabled", "string"    // on | off
        attribute "autoStopReached", "string"    // yes | no
        attribute "dryingMode", "string"         // active | complete | idle | off (auto-drying state)
        attribute "dryingTimeRemain", "number"   // seconds remaining in drying cycle
        attribute "wickFilterLife", "number"     // 0-100 %
        attribute "timerRemain", "number"        // seconds
        attribute "pumpCleanStatus", "string"    // cleaning | idle (water pump self-clean cycle)
        attribute "pumpCleanRemain", "number"    // seconds remaining in pump clean cycle
        attribute "info", "string"               // HTML summary
        attribute "diagnostics", "string"        // captureDiagnostics() output
        // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
        attribute "online", "string"

        command "setMode",            [[name:"Mode*",            type:"ENUM",   constraints:["auto","manual","sleep"]]]
        command "setMistLevel",       [[name:"Level*",           type:"NUMBER", description:"1-9"]]
        command "setTargetHumidity",  [[name:"Percent*",         type:"NUMBER", description:"30-80"]]
        command "setDisplay",         [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setChildLock",       [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",        [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "setDryingMode",      [[name:"On/Off*",          type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        command "captureDiagnostics"
    }

    preferences {
        input "descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true
        input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: false
    }
}

// Lifecycle, refresh, toggle, update (0/1/2-arg), hubBypass, httpOk
// are provided by #include level99.LevoitHumidifier (LevoitHumidifierLib.groovy).

// ---------- Power ----------
// Power on/off provided by #include level99.LevoitHumidifier; payload supplied via this hook.
// V2-family switch payload: {powerSwitch: 0|1, switchIdx: 0}
Map powerPayload(boolean on){ [powerSwitch: on ? 1 : 0, switchIdx: 0] }

// toggle: provided by #include level99.LevoitHumidifier (state.lastSwitchSet preferred pattern)

// ---------- Mode ----------
// BP24: SHOULD-ON — asking an off device to change mode auto-turns it on (matches speed/level
//   setters; pyvesync set_mode has no power gate and sets device ON on success). ensureSwitchOn()
//   runs AFTER validation so invalid input cannot wake an off device.
def setMode(mode){
    logDebug "setMode(${mode})"
    if (!requireNonEmptyEnum(mode, "setMode")) return
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["auto","manual","sleep"])) { logError "Invalid mode: ${m}"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    ensureSwitchOn()
    // autoPro is the canonical API value for "auto" on Superior 6000S
    String apiMode = (m == "auto") ? "autoPro" : m
    def resp = hubBypass("setHumidityMode", [workMode: apiMode], "setHumidityMode(${apiMode})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"
        recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    if (!requireNotNull(level, "setMistLevel")) return
    // BP24: SHOULD-ON — mist-level command; calls ensureSwitchOn() below (SwitchLevel convention).
    // setMistLevel(0) means "turn off" (SwitchLevel/MistLevel convention; release-notes contract for
    // all humidifiers). Evaluate the power-off branch BEFORE the sleep-mode short-circuit so a
    // setMistLevel(0) issued while sleeping still powers the device off rather than silently skipping.
    // BP28: distinguish explicit 0 (-> off) from non-numeric garbage (-> ignore, device unchanged).
    Integer lvl = parseLevelOrNull(level)
    if (lvl == null) { logWarn "setMistLevel: ignoring non-numeric value '${level}'"; return }
    if (lvl <= 0) { off(); return }
    // Sup6000S V2 firmware rejects setVirtualLevel while in sleep mode (inner code -1), so a
    // positive-level mist write short-circuits during sleep mode before any cloud write or ensureSwitchOn.
    if (device.currentValue("mode") == "sleep") {
        logInfo "Skipping setMistLevel during sleep mode (Sup6000S firmware rejects preference writes in sleep mode; change mode first)"
        return
    }
    Integer clamped = Math.max(1, Math.min(9, lvl))
    ensureSwitchOn()
    def resp = hubBypass("setVirtualLevel", [levelIdx: 0, virtualLevel: clamped, levelType: "mist"], "setVirtualLevel(${clamped})")
    if (httpOk(resp)) {
        state.virtualLevel = clamped
        device.sendEvent(name:"virtualLevel", value: clamped)
        device.sendEvent(name:"level", value: percentFromLevel(clamped))
        logInfo "Mist level: ${clamped}"
    } else {
        logError "Mist level write failed: ${clamped}"
        recordError("Mist level write failed: ${clamped}", [method:"setVirtualLevel"])
    }
}

// 2-arg setLevel overload — Hubitat SwitchLevel capability standard signature.
// VeSync devices do NOT support hardware-level fade/duration, so the duration
// parameter is intentionally ignored. Delegates to the 1-arg version.
// Without this overload, any caller using the standard 2-arg form (Rule Machine
// with duration, dashboard tiles, MCP setLevel(N, D), third-party apps) throws
// MissingMethodException — Hubitat sandbox catches it silently and the command
// fails without user feedback.
def setLevel(val, duration) {
    setLevel(val)
}

// SwitchLevel capability path: map 0-100 to 1-9
// SwitchLevel convention: setLevel(0) means "turn off" (Hubitat capability contract).
// Without this guard, pct=0 → levelFromPercent(0) → 1 → setMistLevel(1) → ensureSwitchOn()
// turns an off device ON at level 1 — inverted behaviour.
def setLevel(val){
    logDebug "setLevel(${val})"
    // BP28: distinguish explicit 0 (-> off) from non-numeric garbage (-> ignore, device unchanged).
    Integer pct = parseLevelOrNull(val)
    if (pct == null) { logWarn "setLevel: ignoring non-numeric value '${val}'"; return }
    pct = Math.max(0, Math.min(100, pct))
    if (pct == 0) { off(); return }
    Integer lvl = levelFromPercent(pct)
    sendEvent(name:"level", value: pct)
    setMistLevel(lvl)
}

// ---------- Target humidity ----------
def setTargetHumidity(percent){
    logDebug "setTargetHumidity(${percent})"
    if (!requireNotNull(percent, "setTargetHumidity")) return
    Integer p = safeIntArg(percent, 0)
    if (p <= 0) { logWarn "setTargetHumidity called with ${p} -- 0% is not a valid target humidity; ignoring"; return }
    p = Math.max(30, Math.min(80, p))
    def resp = hubBypass("setTargetHumidity", [targetHumidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        // BP29: device-off => one WARN (expected); any other failure => logError + record.
        reportWriteFailure("Target humidity write failed: ${p}", resp, [method:"setTargetHumidity"])
    }
}

// ---------- Feature setters ----------
// V2-line shared bodies via lib; delegators preserve method-presence semantics.
// BP24: NO-ON — configures a device preference; powering on is not implied.
// BUG #212: Sup6000S V2 firmware rejects setDisplay during sleep mode (inner code -1).
// Skip with an INFO explanation rather than logging a false-positive ERROR.
def setDisplay(onOff) {
    if (!requireNonEmptyEnum(onOff, "setDisplay")) return false
    if (device.currentValue("mode") == "sleep") {
        logInfo "Skipping setDisplay during sleep mode (Sup6000S firmware rejects preference writes in sleep mode; change mode first)"
        return true
    }
    doSetDisplayScreenSwitch(onOff)
}
// BP24: NO-ON — configures a device preference; powering on is not implied.
def setAutoStop(onOff) { doSetAutoStopSwitch(onOff) }

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setChildLock(onOff){
    logDebug "setChildLock(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setChildLock")) return false
    // BP25: derive canonical on/off for sendEvent and C3 gate — never emit raw "true"/"1"/"yes".
    String val = (onOff as String).trim().toLowerCase()
    String canon = canonOnOff(val)
    if (device.currentValue("childLock") == canon) return true
    Integer v = (canon == "on") ? 1 : 0
    def resp = hubBypass("setChildLock", [childLockSwitch: v], "setChildLock(${canon})")
    if (httpOk(resp)) {
        device.sendEvent(name:"childLock", value: canon)
        logInfo "Child lock: ${canon}"
    } else { reportWriteFailure("Child lock write failed", resp, [method:"setChildLock"]) }
}

// BP24: NO-ON — configures a device preference; powering on is not implied.
def setDryingMode(onOff){
    logDebug "setDryingMode(${onOff})"
    if (!requireNonEmptyEnum(onOff, "setDryingMode")) return false
    // No C3 idempotency gate: the dryingMode attribute reflects the hardware's current drying
    // state (active/complete/idle/off), not the autoDryingSwitch user preference that this
    // method writes. Comparing against dryingMode would incorrectly suppress the write when
    // the device is mid-cycle (state="active", user-pref="on").
    String canon = canonOnOff(onOff)
    Integer v = (canon == "on") ? 1 : 0
    def resp = hubBypass("setDryingMode", [autoDryingSwitch: v], "setDryingMode(${canon})")
    if (httpOk(resp)) logInfo "Drying mode auto-switch set: ${canon}"
    else { reportWriteFailure("Drying mode write failed", resp, [method:"setDryingMode"]) }
}

// refresh, update (0/1/2-arg): provided by #include level99.LevoitHumidifier

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    seedPrefs()
    def r = peelEnvelope(status)
    // Diagnostic raw dump — gated by debugOutput. Keep for ongoing field diagnostics.
    logDebug "applyStatus raw r keys=${r?.keySet()}, values=${r}"

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
    device.sendEvent(name:"displayOn", value: screen == 1 ? "on" : "off")

    // Child lock
    device.sendEvent(name:"childLock", value: (r.childLockSwitch as Integer) == 1 ? "on" : "off")

    // Auto-stop: config (switch) vs active state
    device.sendEvent(name:"autoStopEnabled", value: (r.autoStopSwitch as Integer) == 1 ? "on" : "off")
    device.sendEvent(name:"autoStopReached", value: (r.autoStopState as Integer) == 1 ? "yes" : "no")

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

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// hubBypass, httpOk provided by #include level99.LevoitHumidifier.

// ------------- END -------------
