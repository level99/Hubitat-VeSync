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
 *  Levoit LV600S Humidifier (LUH-A602S-* family) -- Hubitat driver
 *
 *  Targets:    LUH-A602S-WUSR, LUH-A602S-WUS, LUH-A602S-WEUR, LUH-A602S-WEU,
 *              LUH-A602S-WJP, LUH-A602S-WUSC
 *  Marketing:  Levoit LV600S Smart Humidifier (6L evaporative + warm mist)
 *  Reference:  pyvesync VeSyncHumid200300S + LUH-A602S-WUS.yaml fixture
 *              https://github.com/webdjoe/pyvesync
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  IMPORTANT: pyvesync PR #505 ("fix: set correct auto mode for LV600S")
 *    reports that LUH-A602S-WEU requires mode:"humidity" (not mode:"auto") to
 *    switch into auto mode. This PR is OPEN and unmerged as of 2026-04-27.
 *    The canonical pyvesync fixture (LUH-A602S-WUS.yaml, commit c98729c) uses
 *    mode:"auto". We follow the canonical fixture. If auto mode fails on your
 *    device, this is the suspected cause -- please report with a debug log.
 *    See: https://github.com/webdjoe/pyvesync/pull/505
 *
 *  CLASS NOTE (naming trap): LUH-A602S uses VeSyncHumid200300S (same class as Classic
 *    300S and OasisMist 450S). LUH-A603S uses the DIFFERENT class VeSyncLV600S.
 *    Both are branded "LV600S" by Levoit but have different API behaviors. This driver
 *    covers ONLY LUH-A602S-* codes. Do NOT add LUH-A603S-* here.
 *
 *  History:
 *    2026-04-27: v2.2  Community fork [PREVIEW]. Built from canonical pyvesync payloads
 *                      (LUH-A602S-WUS.yaml fixture + device_map.py LUH-A602S entry +
 *                      OasisMist 450S warm-mist cross-check + pyvesync PR #505 audit).
 *                      Capabilities: Switch, RelativeHumidityMeasurement, Sensor,
 *                      Actuator, Refresh. Setters: setMode (auto/sleep/manual), setMistLevel
 *                      (1-9), setHumidity (30-80%), setWarmMistLevel (0-3, 0=off), setDisplay,
 *                      setAutoStop, toggle. Reads humidity, targetHumidity, mistLevel,
 *                      warmMistLevel, warmMistEnabled, waterLacks, displayOn, autoStopEnabled,
 *                      autoStopReached. NO night-light (hardware absent; confirmed by device_map.py
 *                      LUH-A602S features=[WARM_MIST, AUTO_STOP] -- nightlight not in list).
 *                      Switch payload uses humidifier shape {enabled, id:0}. setVirtualLevel
 *                      uses {id:0, level:N, type:'mist'|'warm'}. Target humidity range 30-80
 *                      (device_map.py LUH-A602S inherits humidity_min=30 from base class).
 *                      Warm-mist level=0 semantics mirror VeSyncLV600S class correct logic
 *                      (NOT the buggy VeSyncHumid200300S base -- pyvesync vesynchumidifier.py ~328).
 */

metadata {
    definition(
        name: "Levoit LV600S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.2] Levoit LV600S (LUH-A602S-WUSR/-WUS/-WEUR/-WEU/-WJP/-WUSC) — mist 1-9, warm mist 0-3, target humidity 30-80%, auto/sleep/manual modes, auto-stop, display; no night-light; canonical pyvesync payloads. NOTE: auto-mode may use 'humidity' payload on some firmware -- see pyvesync PR #505 and driver source CROSS-CHECK.",
        version: "2.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        // CROSS-CHECK [pyvesync device_map.py LUH-A602S entry / pyvesync PR #505]:
        //   'mode' attribute reflects what the device reports. Possible values from the API
        //   include "auto", "sleep", "manual" (and possibly "humidity" on some EU firmware
        //   variants per PR #505 -- the attribute passes through as-received for read path).
        attribute "mode",             "string"   // auto | sleep | manual (possibly 'humidity' on WEU)
        attribute "mistLevel",        "number"   // 0-9 (0 = inactive)
        // CROSS-CHECK [pyvesync device_map.py LUH-A602S entry]:
        //   Target humidity range 30-80. The LUH-A602S entry in device_map.py does NOT
        //   override humidity_min from the VeSyncHumid200300S base class (which is 30).
        //   This differs from OasisMist 450S which has a firmware floor of 40 per pyvesync
        //   issue #296. If community users report firmware rejection below 40, raise the floor.
        attribute "targetHumidity",   "number"   // 30-80 % (pyvesync base class humidity_min=30)
        attribute "waterLacks",       "string"   // yes | no
        attribute "warmMistLevel",    "number"   // 0-3 (0 = warm mist off)
        attribute "warmMistEnabled",  "string"   // on | off  (level=0 -> off, 1-3 -> on)
        attribute "displayOn",        "string"   // on | off
        attribute "autoStopEnabled",  "string"   // on | off
        attribute "autoStopReached",  "string"   // yes | no
        attribute "info",             "string"   // HTML summary for dashboard tiles

        // setMode: 3 valid modes. Sleep is added since device_map.py LUH-A602S lists
        // mist_modes as including sleep per VeSyncHumid200300S base class.
        // 'humidity' is NOT exposed as a setter -- see PR #505 CROSS-CHECK in setMode().
        command "setMode",          [[name:"Mode*",    type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",     [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        command "setHumidity",      [[name:"Percent*", type:"NUMBER", description:"30-80"]]
        command "setWarmMistLevel", [[name:"Level*",   type:"NUMBER", description:"0-3 (0=off)"]]
        command "setDisplay",       [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",      [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        // NOTE: setNightLight is intentionally absent -- LV600S has no night-light hardware.
        // pyvesync device_map.py LUH-A602S features list: [WARM_MIST, AUTO_STOP] -- no NIGHTLIGHT.
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
    runIn(3, "refresh")
    if (settings?.debugOutput) runIn(1800, "logDebugOff")
}
def uninstalled(){ logDebug "Uninstalled" }
def initialize(){ logDebug "Initializing" }

// ---------- Power ----------
// Humidifier switch payload: {enabled: bool, id: 0}
// NOT purifier shape {powerSwitch: int, switchIdx: 0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [enabled: true, id: 0], "setSwitch(enabled=true)")
    if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
    else logError "Power on failed"
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [enabled: false, id: 0], "setSwitch(enabled=false)")
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
// CROSS-CHECK [pyvesync LUH-A602S-WUS.yaml (commit c98729c) / pyvesync PR #505]:
//   Decision: expose three modes (auto, sleep, manual) matching the canonical fixture.
//     set_auto_mode payload: {mode: "auto"} per LUH-A602S-WUS.yaml.
//   pyvesync PR #505 reports LUH-A602S-WEU requires {mode: "humidity"} to switch
//     to auto mode. PR is OPEN and unmerged. The pyvesync maintainer requested device
//     JSON before merging; the change could break other 200300S devices.
//   Risk: EU-variant users may find "auto" mode has no effect (the PR #505 symptom).
//     If this happens, users should report via GitHub issue with debug log + country.
//   Rationale: the canonical fixture is the authoritative pyvesync reference. We do not
//     implement unmerged PR changes. If PR #505 merges, add a "humidity" alias and document
//     that LUH-A602S-WEU/-WEUR users should select "humidity" mode from the app.
//   Source: https://github.com/webdjoe/pyvesync/pull/505 (open as of 2026-04-27);
//     https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/api/
//       vesynchumidifier/LUH-A602S-WUS.yaml (commit c98729c, set_auto_mode payload).
//   Refutation: community user with LUH-A602S-WUS (US variant) confirms "auto" fails
//     on their firmware --> PR #505 affects more than just EU; add "humidity" alias.
// Classic 300S / OasisMist 450S payload: {mode: <value>} -- NOT {workMode: <value>}
def setMode(mode){
    logDebug "setMode(${mode})"
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be one of: auto, sleep, manual"; return }
    def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
    if (httpOk(resp)) {
        state.mode = m
        device.sendEvent(name:"mode", value: m)
        logInfo "Mode: ${m}"
    } else {
        logError "Mode write failed: ${m}"
    }
}

// ---------- Mist level ----------
// CROSS-CHECK [pyvesync device_map.py LUH-A602S entry]:
//   mist_levels = list(range(1, 10)) -> 1-9.
//   Payload: {id:0, level:N, type:'mist'} -- same as Classic 300S and OasisMist 450S.
//   NOT levelIdx/virtualLevel/levelType (Superior 6000S style).
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "mist"], "setVirtualLevel(mist,${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"
    }
}

// ---------- Warm-mist level ----------
// CROSS-CHECK [pyvesync vesynchumidifier.py ~line 328 (known bug) / VeSyncLV600S class]:
//   Decision: level=0 sets warmMistEnabled="off"; level 1-3 sets warmMistEnabled="on".
//     This is the CORRECT semantic -- mirrors VeSyncLV600S class logic, not the buggy
//     VeSyncHumid200300S class logic.
//   Rationale: pyvesync VeSyncHumid200300S.set_warm_level() sets warm_mist_enabled=True
//     unconditionally even when level=0, which is a bug. The VeSyncLV600S class has the
//     correct version: warm_mist_enabled = (warm_level > 0). The real device almost certainly
//     behaves correctly (level=0 = warm mist off, matching the "Heat Off" physical button
//     position). We trust the corrected LV600S logic over the bugged 200300S logic.
//     NOTE: this driver targets LUH-A602S (VeSyncHumid200300S class), not LUH-A603S
//     (VeSyncLV600S class). Despite the class difference, the warm-mist level=0 behavior
//     is expected to be identical hardware-side. We apply the LV600S class's correct
//     level=0 semantics regardless of which pyvesync class we're under.
//   Source: pyvesync vesynchumidifier.py VeSyncHumid200300S.set_warm_level() (~line 328,
//     bug: always sets warm_mist_enabled=True); same file VeSyncLV600S class (correct:
//     warm_mist_enabled = warm_level > 0). Cross-confirmed by OasisMist 450S CROSS-CHECK.
//   Refutation: community user with LUH-A602S reports level=0 still produces warm mist
//     (device ignores level=0 and stays on) --> reverse the logic; always set warmMistEnabled
//     from warm_enabled field.
// setVirtualLevel payload: {id: 0, level: N, type: 'warm'}
// Valid range: 0-3 (0 = warm mist off; 1-3 = warm intensity levels)
def setWarmMistLevel(level){
    logDebug "setWarmMistLevel(${level})"
    Integer lvl = (level as Integer) ?: 0
    if (lvl < 0 || lvl > 3) {
        logError "Invalid warm mist level ${lvl} -- must be 0-3 (0=off, 1-3=warm intensity)"
        return
    }
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "warm"], "setVirtualLevel(warm,${lvl})")
    if (httpOk(resp)) {
        // level=0 means warm mist OFF; level 1-3 means warm mist ON at that intensity
        // This is the CORRECT semantic (mirrors LV600S class, not the buggy 200300S class)
        boolean warmOn = (lvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        state.warmMistLevel = lvl
        state.warmMistEnabled = warmOnStr
        device.sendEvent(name:"warmMistLevel", value: lvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        logInfo "Warm mist: level=${lvl}, enabled=${warmOnStr}"
    } else {
        logError "Warm mist level write failed: ${lvl}"
    }
}

// ---------- Target humidity ----------
// CROSS-CHECK [pyvesync device_map.py LUH-A602S entry]:
//   Decision: clamp target humidity to 30-80%. The LUH-A602S entry in device_map.py
//     does not override humidity_min from the VeSyncHumid200300S base class (which is 30).
//   OasisMist 450S has firmware floor of 40 (pyvesync issue #296); that issue is specific
//     to OasisMist 450S hardware. LV600S device_map.py shows no humidity_min override,
//     so we use the base class 30 floor.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
//     (LUH-A602S HumidifierMap entry -- no humidity_min override).
//   Refutation: community user with LUH-A602S reports values below 40 are rejected
//     with API error 11003000 --> raise floor to 40 (matches OasisMist 450S behavior).
// setTargetHumidity payload: {target_humidity: N} -- note snake_case (not camelCase)
def setHumidity(percent){
    logDebug "setHumidity(${percent})"
    Integer p = Math.max(30, Math.min(80, (percent as Integer) ?: 50))
    def resp = hubBypass("setTargetHumidity", [target_humidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"
    }
}

// ---------- Display ----------
// setDisplay payload: {state: bool} -- NOT {screenSwitch: int} (Superior 6000S)
// Same as Classic 300S and OasisMist 450S (all VeSyncHumid200300S class)
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setDisplay", [state: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"
    }
}

// ---------- Auto-stop ----------
// setAutomaticStop payload: {enabled: bool} -- NOT {autoStopSwitch: int} (Superior 6000S)
// Same as Classic 300S and OasisMist 450S (all VeSyncHumid200300S class)
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"
    }
}

// ---------- Refresh ----------
def refresh(){ update() }

// ---------- Update / status ----------
// Self-fetch when called directly (no-arg).
// NOTE: this path passes resp.data into applyStatus; the parent-callback path (1-arg/2-arg
// below) passes data.result already. The peel-while-loop in applyStatus handles both shapes
// defensively, but the data-flow asymmetry means applyStatus's first peel iteration is doing
// different work depending on entry point.
def update(){
    logDebug "update() self-fetch"
    def resp = hubBypass("getHumidifierStatus", [:], "update")
    if (httpOk(resp)) {
        def status = resp?.data
        if (!status?.result) logError "No status returned from getHumidifierStatus"
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
// nightLight parameter accepted but ignored -- LV600S has no night-light hardware
def update(status, nightLight){
    logDebug "update() from parent (2-arg, nightLight ignored -- LV600S has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated
    // from older Type without Save (forward-compat -- BP#12)
    // Insertion point: top of applyStatus() per V1-humidifier-base pref-seed convention
    // (same as Classic 300S and OasisMist 450S -- uses same VeSyncHumid200300S class).
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- humidifier bypassV2 responses can be double-wrapped (BP#3).
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // LV600S is typically single-wrapped like Classic 300S, but the peel loop is defensive.
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // LV600S response uses `enabled` (boolean), NOT `powerSwitch` (int)
    // Same humidifier shape as Classic 300S / OasisMist 450S (all VeSyncHumid200300S class)
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- from configuration.auto_target_humidity ----
    // Same nested location as Classic 300S and OasisMist 450S (VeSyncHumid200300S class)
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // CROSS-CHECK [pyvesync PR #505 / LUH-A602S-WUS.yaml]:
    //   Read path: emit mode as-received from the device. The device may report "auto",
    //   "sleep", "manual", or (on some EU firmware per PR #505) "humidity". We pass through
    //   the raw value rather than remapping, so the attribute reflects actual device state.
    //   The user may see "humidity" as the mode value if their device is in auto mode and
    //   uses the PR #505 firmware variant. This is expected behavior for those variants.
    //   Refutation: community user with EU variant reports seeing "humidity" mode in status
    //   --> expected; add a note in the driver readme.
    String rawMode = (r.mode ?: "manual") as String
    state.mode = rawMode
    device.sendEvent(name:"mode", value: rawMode)

    // ---- Mist level -- use mist_virtual_level (active running level) ----
    // mist_level = last-set manual level; mist_virtual_level = currently active
    Integer mistVirtual = null
    if (r.mist_virtual_level != null) {
        mistVirtual = r.mist_virtual_level as Integer
    } else if (r.mist_level != null) {
        mistVirtual = r.mist_level as Integer
    }
    if (mistVirtual != null) device.sendEvent(name:"mistLevel", value: mistVirtual)

    // ---- Warm-mist ----
    // CROSS-CHECK [pyvesync vesynchumidifier.py ~328 (known bug) / VeSyncLV600S class]:
    //   Decision: derive warmMistEnabled from warm_level value (level > 0 = on), NOT from
    //     warm_enabled boolean field. This mirrors the correct VeSyncLV600S logic and
    //     guards against the pyvesync VeSyncHumid200300S bug where warm_enabled=True is
    //     emitted even when warm_level=0.
    //   See setWarmMistLevel() CROSS-CHECK block above for full rationale.
    // warm_enabled and warm_level are top-level response fields (ClassicLVHumidResult)
    if (r.warm_level != null) {
        Integer warmLvl = r.warm_level as Integer
        // Derive enabled state from level value (correct logic from LV600S class)
        boolean warmOn = (warmLvl > 0)
        String warmOnStr = warmOn ? "on" : "off"
        device.sendEvent(name:"warmMistLevel", value: warmLvl)
        device.sendEvent(name:"warmMistEnabled", value: warmOnStr)
        state.warmMistLevel = warmLvl
        state.warmMistEnabled = warmOnStr
    } else if (r.warm_enabled != null) {
        // warm_level absent but warm_enabled present -- use it as fallback
        def warmEnabledRaw = r.warm_enabled
        boolean warmOn = (warmEnabledRaw instanceof Boolean) ? warmEnabledRaw : ((warmEnabledRaw as Integer) == 1)
        device.sendEvent(name:"warmMistEnabled", value: warmOn ? "on" : "off")
    }

    // ---- Water lacks ----
    def waterLacksRaw = r.water_lacks
    boolean waterLacks = (waterLacksRaw instanceof Boolean) ? waterLacksRaw : ((waterLacksRaw as Integer) == 1)
    String waterLacksStr = waterLacks ? "yes" : "no"
    if (state.lastWaterLacks != waterLacksStr) {
        if (waterLacks) logInfo "Water reservoir empty"
    }
    state.lastWaterLacks = waterLacksStr
    device.sendEvent(name:"waterLacks", value: waterLacksStr)

    // ---- Auto-stop reached ----
    def autoStopReach = r.automatic_stop_reach_target
    boolean autoStopBool = (autoStopReach instanceof Boolean) ? autoStopReach : ((autoStopReach as Integer) == 1)
    device.sendEvent(name:"autoStopReached", value: autoStopBool ? "yes" : "no")

    // ---- Auto-stop enabled -- from configuration.automatic_stop ----
    Boolean autoStopEnabled = null
    if (r.configuration instanceof Map && r.configuration.automatic_stop != null) {
        def asRaw = r.configuration.automatic_stop
        autoStopEnabled = (asRaw instanceof Boolean) ? asRaw : ((asRaw as Integer) == 1)
    }
    if (autoStopEnabled != null) device.sendEvent(name:"autoStopEnabled", value: autoStopEnabled ? "on" : "off")

    // ---- Display ----
    // CROSS-CHECK [HA cross-check finding #8 / Classic 300S precedent]:
    //   Decision: read 'display' first, then fall back to 'indicator_light_switch', then
    //     configuration.display.
    //   Rationale: same defensive chain as Classic 300S (same VeSyncHumid200300S class).
    //     'display' is the canonical response key. 'indicator_light_switch' is a legacy
    //     alias present on some Classic 200S and older 300S firmware variants; retained as
    //     defensive fallback. LV600S is expected to use 'display', but as a preview driver
    //     without live hardware we retain the full fallback chain.
    //   Source: Classic 300S CROSS-CHECK HA finding #8 (same class, same field expectation).
    //   Refutation: community user reports 'display' absent and neither fallback works -->
    //     investigate firmware variant and add a new key to the chain.
    def displayRaw = null
    if (r.display != null) {
        displayRaw = r.display
    } else if (r.indicator_light_switch != null) {
        logDebug "applyStatus: 'display' missing, falling back to 'indicator_light_switch'"
        displayRaw = r.indicator_light_switch
    } else if (r.configuration instanceof Map && r.configuration.display != null) {
        displayRaw = r.configuration.display
    }
    if (displayRaw != null) {
        boolean displayOn = (displayRaw instanceof Boolean) ? displayRaw : ((displayRaw as Integer) == 1)
        device.sendEvent(name:"displayOn", value: displayOn ? "on" : "off")
    }

    // NOTE: LV600S has NO night-light hardware.
    // night_light_brightness is not parsed and no nightlight events are emitted.
    // pyvesync device_map.py LUH-A602S features=[WARM_MIST, AUTO_STOP] -- no NIGHTLIGHT entry.

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${rawMode}"
    if (r.warm_level != null) {
        Integer wl = r.warm_level as Integer
        parts << "Warm: ${wl > 0 ? 'L'+wl : 'off'}"
    }
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// ---------- Internal helpers ----------
def logDebug(msg){ if (settings?.debugOutput) log.debug msg }
def logError(msg){ log.error msg }
def logInfo(msg){ if (settings?.descriptionTextEnable) log.info msg }
void logDebugOff(){ if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }

// Hub/parent call wrapper -- matches sibling driver pattern
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
    logError "HTTP ${st}"
    return false
}

// ------------- END -------------
