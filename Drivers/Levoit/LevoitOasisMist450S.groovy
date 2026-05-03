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
 *  Levoit OasisMist 450S Humidifier (LUH-O451S-WUS / LUH-O601S-WUS) — Hubitat driver
 *
 *  Targets:    OasisMist 450S US, LUH-O451S-WUS, LUH-O451S-WUSR,
 *              LUH-O451S-WEU (EU 4.5L, RGB nightlight -- see CROSS-CHECK below),
 *              LUH-O601S-WUS, LUH-O601S-KUS (all map to VeSyncHumid200300S class)
 *  Marketing:  Levoit OasisMist 450S Smart Humidifier (US/EU), OasisMist 4.5L Series
 *  Reference:  pyvesync VeSyncHumid200300S + LUH-O451S-WUS.yaml fixture
 *              device_map.py line ~700 -- HumidifierMap entry confirms dev_types
 *              https://github.com/webdjoe/pyvesync
 *              pyvesync PR #502 (RGB nightlight -- OPEN/CHANGES_REQUESTED, stalled 2026-01)
 *
 *  CROSS-CHECK [pyvesync device_map.py line ~700 / ST+HB cross-check finding #11]:
 *    Decision: all 5 model codes (LUH-O451S-WUS, LUH-O451S-WUSR, LUH-O451S-WEU,
 *      LUH-O601S-WUS, LUH-O601S-KUS) route to this single driver.
 *    Rationale: pyvesync groups all five under the same HumidifierMap entry (class
 *      VeSyncHumid200300S); the HA core has no LUH-O601S-specific code paths; ST+HB
 *      cross-check finding #11 noted no behavioral divergence between 450 and 600 series
 *      in community reports. LUH-O451S-WEU adds RGB nightlight hardware; all others
 *      share the same API behavior. Single-driver, runtime-gated RGB code (Task 7).
 *    Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/device_map.py
 *      (HumidifierMap LUH-O451S-WUS/WUSR + LUH-O601S-WUS/KUS entry, line ~700);
 *      ST+HB cross-check finding #11 (2026-04-26);
 *      pyvesync PR #502 (LUH-O451S-WEU RGB nightlight, 2026-01).
 *    Refutation: community user with LUH-O601S reports field-name divergence or a
 *      capability the 450S driver doesn't support --> split into a separate driver file
 *      (remember: driver name change = Bug Pattern #9, so use a new driver name).
 *  Project:    https://github.com/level99/Hubitat-VeSync
 *
 *  History:
 *    2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
 *    2026-04-27: v2.2  RGB nightlight support for LUH-O451S-WEU (EU variant only).
 *                      Ships as [PREVIEW] pending live-hardware validation. Based on
 *                      pyvesync PR #502 (OPEN/CHANGES_REQUESTED, stalled 2026-01).
 *                      Implementation: capability "ColorControl" declared in metadata
 *                      (visible to all variants -- Hubitat limitation); all RGB code
 *                      gated at runtime on state.deviceType == "LUH-O451S-WEU".
 *                      Non-WEU users: RGB commands no-op with INFO log. New custom
 *                      command setNightlightSwitch(on/off) + standard setColor/setHue/
 *                      setSaturation for color control. See CROSS-CHECK block below.
 *    2026-04-26: v2.0  Community fork initial release (Dan Cox). Built from canonical
 *                      pyvesync payloads (LUH-O451S-WUS.yaml + call_json_humidifiers.py
 *                      + device_map.py + HA issue #138004 warm-mist/mode findings).
 *                      Extends Classic 300S (same VeSyncHumid200300S class) with:
 *                      warm-mist control (setWarmMistLevel 0-3, 0=off), 3-mode enum
 *                      (auto/sleep/manual). NO nightlight (hardware does not have it).
 *                      Features: WARM_MIST, AUTO_STOP. Target humidity range 40-80%
 *                      (device firmware limit; pyvesync issue #296). 'humidity' mode
 *                      intentionally excluded: device universally rejects it with API
 *                      error 11000000 (pyvesync issue #295; HA bonus finding #a refuted).
 *                      Warm-mist level=0 sets warmMistEnabled=off (corrects pyvesync bug
 *                      in VeSyncHumid200300S.set_warm_level -- LV600S class has the
 *                      correct logic; we mirror that). All 4 LUH-O451S/O601S model
 *                      codes route to this driver per device_map.py grouping.
 */

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase

// CROSS-CHECK [pyvesync PR #502 / RGB nightlight for LUH-O451S-WEU]:
//   Status: PR #502 is OPEN/CHANGES_REQUESTED, stalled since 2026-01. Upstream pyvesync
//     has not merged it. User explicitly decided to ship RGB support in v2.2 despite
//     upstream instability ("preview" -- pending live-hardware validation).
//   Model scope: Only LUH-O451S-WEU has RGB nightlight hardware. All other model codes
//     (WUS, WUSR, O601S-WUS, O601S-KUS) have no RGB hardware. All RGB code gated at
//     runtime on state.deviceType == "LUH-O451S-WEU" (set by parent routing in Task 6).
//   Capability declaration: capability "ColorControl" is declared in metadata (visible to
//     ALL variants). This is a Hubitat limitation -- capabilities are static at compile
//     time, not per-device-instance. Non-WEU users see ColorControl commands on their
//     device page; those commands no-op with an INFO log ("RGB nightlight not supported
//     on this hardware variant; ignoring command"). This is the least-bad tradeoff
//     (alternative: a separate LevoitOasisMist450S_WEU.groovy, which the user explicitly
//     declined due to Bug Pattern #9 risk and driver-name proliferation).
//   Payload: setLightStatus {action, brightness, red, green, blue, colorMode, speed:0,
//     colorSliderLocation}. RGB is HSV-brightness-adjusted (V = brightness/100, H+S from
//     raw color). colorSliderLocation is nearest-point on 8-color gradient (0-100).
//   180-second stale-data gate: after each RGB write, state.rgbNightlightSetTime is set
//     to now(). applyStatus suppresses RGB attribute updates within 180s of last write
//     to prevent stale poll data overwriting a just-sent command.
//   colorSliderLocation gradient source: pyvesync PR #502 _RGB_NIGHTLIGHT_GRADIENT.
//     8 anchor points extracted from PR diff (2026-01). Colors listed in order
//     (location=0 to location=100, evenly spaced at ~14.3 units):
//       0: (252,50,0) Red         14: (255,171,2) Orange
//       29: (181,255,0) YellowGreen  43: (2,255,120) Green
//       57: (3,200,254) Cyan       71: (0,40,255) Blue
//       86: (220,0,255) Purple     100: (254,0,60) Pink
//     (locations: 0, 100/7, 200/7, 300/7, 400/7, 500/7, 600/7, 100 = ~0,14,29,43,57,71,86,100)
//   Source: https://github.com/webdjoe/pyvesync/pull/502
//   Refutation: EU community user reports incorrect color rendering, wrong brightness,
//     or device rejecting setLightStatus payload --> update per their captured response.

// CROSS-CHECK [pyvesync issue #295 / HA bonus finding #a (refuted)]:
//   Decision: 'humidity' mode is intentionally NOT exposed in setMode for the OasisMist 450S.
//   Rationale: pyvesync issue #295 documents that the OasisMist 450S US firmware returns API
//     error code 11000000 ("Mode value invalid!") when sent setHumidityMode{mode:'humidity'}.
//     The HA bonus finding #a claimed firmware-variant-dependent acceptance, but this was
//     refuted by user-reported API captures showing universal rejection across US model codes.
//     Only auto/sleep/manual are accepted.
//   Source: https://github.com/webdjoe/pyvesync/issues/295 (pyvesync issue #295);
//     HA vesync integration cross-check bonus finding #a (2026-04-26).
//   Refutation: community user with LUH-O451S-WUS confirms 'humidity' mode works on their
//     firmware --> re-add as optional mode with a note about firmware version dependency.

metadata {
    definition(
        name: "Levoit OasisMist 450S Humidifier",
        namespace: "NiklasGustafsson",
        author: "Dan Cox (community fork)",
        description: "[PREVIEW v2.2] Levoit OasisMist 450S/600S US+EU (LUH-O451S-WUS/-WUSR/-WEU, LUH-O601S-WUS/-KUS) — mist 1-9, warm mist 0-3, target humidity 40-80%, auto/sleep/manual modes, auto-stop, display; LUH-O451S-WEU (EU) adds RGB nightlight (ColorControl); canonical pyvesync payloads; RGB based on pyvesync PR #502 (preview)",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
    {
        capability "Switch"
        capability "RelativeHumidityMeasurement"    // current ambient humidity
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"
        // ColorControl: declared for ALL variants (Hubitat limitation -- capabilities are
        // static). Commands gate at runtime on state.deviceType == "LUH-O451S-WEU".
        // Non-WEU users see these commands but they no-op with an INFO log.
        capability "ColorControl"                   // setColor, setHue, setSaturation; hue, saturation, color, colorMode attributes

        attribute "mode",                "string"   // auto | sleep | manual
        attribute "mistLevel",           "number"   // 0-9 (0 = inactive)
        // CROSS-CHECK: 40-80% range (NOT 30-80%) -- firmware limit per pyvesync issue #296 +
        //   homebridge-levoit-humidifiers minHumidityLevel:40 confirmation. See setHumidity().
        attribute "targetHumidity",      "number"   // 40-80 % (firmware limit; pyvesync issue #296)
        attribute "waterLacks",          "string"   // yes | no
        attribute "warmMistLevel",       "number"   // 0-3 (0 = warm mist off)
        attribute "warmMistEnabled",     "string"   // on | off  (NOTE: level=0 -> off, 1-3 -> on)
        attribute "displayOn",           "string"   // on | off
        attribute "autoStopEnabled",     "string"   // on | off
        attribute "autoStopReached",     "string"   // yes | no
        attribute "humidityHigh",        "string"   // yes | no
        attribute "info",                "string"   // HTML summary for dashboard tiles
        // RGB nightlight (LUH-O451S-WEU only -- runtime-gated; see CROSS-CHECK above)
        attribute "nightlightSwitch",     "string"  // on | off
        attribute "nightlightBrightness", "number"  // 40-100

        // setMode: 3 valid modes. 'humidity' is excluded -- device firmware universally
        // rejects it with API error 11000000 (pyvesync issue #295; HA finding #a refuted).
        command "setMode",               [[name:"Mode*",    type:"ENUM",   constraints:["auto","sleep","manual"]]]
        command "setMistLevel",          [[name:"Level*",   type:"NUMBER", description:"1-9"]]
        command "setHumidity",           [[name:"Percent*", type:"NUMBER", description:"40-80"]]
        command "setWarmMistLevel",      [[name:"Level*",   type:"NUMBER", description:"0-3 (0=off)"]]
        command "setDisplay",            [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "setAutoStop",           [[name:"On/Off*",  type:"ENUM",   constraints:["on","off"]]]
        command "toggle"
        // RGB nightlight (LUH-O451S-WEU only -- runtime-gated). Standard ColorControl
        // setColor/setHue/setSaturation come from capability declaration above.
        command "setNightlightSwitch",   [[name:"value*",   type:"ENUM",   constraints:["on","off"]]]

        // CROSS-CHECK [pyvesync issue #500 / counter-evidence from second WUSR owner]:
        //   Decision (v2.1): OasisMist 450S WUSR has NO nightlight hardware (no attribute declared,
        //     no setNightLight command). This matches the majority of community reports.
        //   Disputed claim: pyvesync issue #500 (single reporter, LUH-O451S-WUSR) alleges the
        //     device accepts setNightLightBrightness. Counter-evidence: a second WUSR owner reports
        //     no nightlight hardware whatsoever; probe results on that device are expected to
        //     produce inner code != 0 (device rejection). The pyvesync issue is "awaiting info"
        //     since 2026-01-18 with no API capture from the original reporter.
        //   Action: probeNightLight() gathers actionable data from the community. One call,
        //     always-INFO output (not gated by debugOutput), designed to be posted to the
        //     pyvesync #500 thread as evidence. If enough WUSR owners report inner code 0,
        //     a nightlight attribute + setNightLight command will be added in v2.3.
        //   Source: https://github.com/webdjoe/pyvesync/issues/500
        //   Refutation path: paste probe output in issue #500 or the Hubitat community thread.
        command "probeNightLight"

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
    // state.clear() also clears state.firmwareVariant -- firmware updates get re-detected on
    // next setMode("auto") call (see sendModeRequest multi-firmware fallback logic below).
    // state.clear() also removes rgbNightlightSetTime -- clears the 180s stale-data gate so
    // the first applyStatus after updated() always reads fresh RGB state from the device.
    state.clear(); unschedule(); initialize()
    state.driverVersion = "2.4.1"
    runIn(3, "refresh")
    // Turn off debug log in 30 minutes (happy path — no hub reboot)
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
// Humidifier switch payload: {enabled: bool, id: 0}
// NOT purifier shape {powerSwitch: int, switchIdx: 0}
def on(){
    logDebug "on()"
    def resp = hubBypass("setSwitch", [enabled: true, id: 0], "setSwitch(enabled=true)")
    if (httpOk(resp)) { logInfo "Power on"; state.lastSwitchSet = "on"; device.sendEvent(name:"switch", value:"on") }
    else { logError "Power on failed"; recordError("Power on failed", [method:"setSwitch"]) }
}

def off(){
    logDebug "off()"
    def resp = hubBypass("setSwitch", [enabled: false, id: 0], "setSwitch(enabled=false)")
    if (httpOk(resp)) { logInfo "Power off"; state.lastSwitchSet = "off"; device.sendEvent(name:"switch", value:"off") }
    else { logError "Power off failed"; recordError("Power off failed", [method:"setSwitch"]) }
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
// CROSS-CHECK [pyvesync issue #295 / HA bonus finding #a (refuted) / pyvesync issue #500 context]:
//   v2.1 decision: three valid modes only (auto, sleep, manual). 'humidity' excluded as a
//     user-settable mode because pyvesync issue #295 documents device firmware returning API
//     error 11000000 ("Mode value invalid!") when sent {mode:"humidity"} on US models.
//   v2.2 revision: the pyvesync #500 report creates uncertainty about WUSR firmware variation.
//     Some WEU/WEUR firmware variants may accept "humidity" as the auto-mode payload (same
//     pattern as LV600S PR #505). To handle this transparently, the write path uses the
//     try-canonical-then-fallback pattern with fingerprint cache:
//       - Default: try "auto" (canonical US fixture). Cache as "std" on success.
//       - If rejected: try "humidity" ONCE. Cache as "alt" on success.
//       - Both rejected: logError (no silent failure).
//     state.firmwareVariant caches the result so subsequent calls go direct without retry.
//     state.firmwareVariant is cleared in updated() so firmware updates are re-detected.
//   Read-path: normalize "auto", "autoPro", "humidity" -> "auto" user-facing attribute.
//   Note: we still do NOT expose "humidity" as a user-settable mode via the command definition
//     because all three firmware values mean "auto" from the user's perspective.
//   Source: https://github.com/webdjoe/pyvesync/issues/295 (issue #295, universal US rejection);
//     https://github.com/webdjoe/pyvesync/issues/500 (issue #500, WUSR nightlight/behavior query).
// payload: {mode: <value>} -- NOT {workMode: <value>} (Superior 6000S style)
def setMode(mode){
    logDebug "setMode(${mode})"
    if (mode == null) { logWarn "setMode called with null mode (likely empty Rule Machine action parameter); ignoring"; return }
    String m = (mode as String).toLowerCase()
    if (!(m in ["auto","sleep","manual"])) { logError "Invalid mode: ${m} -- must be one of: auto, sleep, manual"; recordError("Invalid mode: ${m}", [method:"setHumidityMode"]); return }
    if (m == "auto") {
        // Multi-firmware try-canonical-then-fallback with cache
        String preferred = (state.firmwareVariant == "alt") ? "humidity" : "auto"
        sendModeRequest(preferred, "auto", false)
    } else {
        // sleep and manual: no known firmware variant issue -- send directly
        def resp = hubBypass("setHumidityMode", [mode: m], "setHumidityMode(${m})")
        if (httpOk(resp)) {
            state.mode = m
            device.sendEvent(name:"mode", value: m)
            logInfo "Mode: ${m}"
        } else {
            logError "Mode write failed: ${m}"; recordError("Mode write failed: ${m}", [method:"setHumidityMode"])
        }
    }
}

// sendModeRequest: internal helper for multi-firmware auto-mode try-fallback logic.
// Shared pattern with LevoitLV600S -- see that driver for full rationale (PR #505 / issue #500).
// payloadValue -- 'mode' field value sent to device API ("auto" or "humidity").
// userMode     -- canonical user-facing mode string to emit if successful ("auto").
// isRetry      -- true when this is the alternate-payload retry (prevents infinite recursion).
private void sendModeRequest(String payloadValue, String userMode, boolean isRetry){
    def resp = hubBypass("setHumidityMode", [mode: payloadValue], "setHumidityMode(${payloadValue})")
    def innerCode = resp?.data?.result?.code
    boolean ok = (resp?.status in [200,201,204]) && (innerCode == null || innerCode == 0)
    if (ok) {
        String detectedVariant = (payloadValue == "auto") ? "std" : "alt"
        if (state.firmwareVariant != detectedVariant) {
            state.firmwareVariant = detectedVariant
            logInfo "Firmware variant auto-detected: ${detectedVariant} (payload '${payloadValue}' accepted). Cached for future setMode calls."
        }
        state.mode = userMode
        device.sendEvent(name:"mode", value: userMode)
        logInfo "Mode: ${userMode}"
    } else if (!isRetry) {
        // Canonical payload rejected -- try alternate once
        String alternate = (payloadValue == "auto") ? "humidity" : "auto"
        logDebug "setMode(${userMode}): payload '${payloadValue}' rejected (inner code: ${innerCode}); trying alternate firmware variant '${alternate}'"
        sendModeRequest(alternate, userMode, true)
    } else {
        // Both variants rejected
        logError "Mode '${userMode}' rejected by both payload variants ('auto' and 'humidity', inner code: ${innerCode}). Check device connectivity or report via GitHub issue."
        recordError("Mode '${userMode}' rejected by both payload variants (inner code: ${innerCode})", [method:"setHumidityMode"])
    }
}

// ---------- Mist level ----------
// setVirtualLevel payload: {id: 0, level: N, type: 'mist'}
// NOTE: field names id/level/type -- NOT levelIdx/virtualLevel/levelType (Superior 6000S)
def setMistLevel(level){
    logDebug "setMistLevel(${level})"
    Integer lvl = Math.max(1, Math.min(9, (level as Integer) ?: 1))
    def resp = hubBypass("setVirtualLevel", [id: 0, level: lvl, type: "mist"], "setVirtualLevel(mist,${lvl})")
    if (httpOk(resp)) {
        state.mistLevel = lvl
        device.sendEvent(name:"mistLevel", value: lvl)
        logInfo "Mist level: ${lvl}"
    } else {
        logError "Mist level write failed: ${lvl}"; recordError("Mist level write failed: ${lvl}", [method:"setVirtualLevel"])
    }
}

// ---------- Warm-mist level ----------
// CROSS-CHECK [pyvesync vesynchumidifier.py:328 (known bug) / pyvesync VeSyncLV600S class]:
//   Decision: level=0 sets warmMistEnabled="off"; level 1-3 sets warmMistEnabled="on".
//     This is the CORRECT semantic -- mirrors VeSyncLV600S class logic, not the buggy
//     VeSyncHumid200300S class logic.
//   Rationale: pyvesync VeSyncHumid200300S.set_warm_level() sets warm_mist_enabled=True
//     unconditionally even when level=0, which is a bug. The VeSyncLV600S class has the
//     correct version: warm_mist_enabled = (warm_level > 0). The real device almost certainly
//     behaves correctly (level=0 = warm mist off, matching the "Heat Off" physical button
//     position). We trust the corrected LV600S logic over the bugged 200300S logic.
//   Source: https://github.com/webdjoe/pyvesync/blob/master/src/pyvesync/devices/
//     vesynchumidifier.py line ~328 (VeSyncHumid200300S.set_warm_level, the bug);
//     same file VeSyncLV600S class (correct logic: warm_mist_enabled = warm_level > 0).
//   Refutation: community user reports level=0 still produces warm mist (device truly
//     ignores level=0 and stays on) --> reverse the logic; always set warmMistEnabled
//     from warm_enabled field instead.
// setVirtualLevel payload: {id: 0, level: N, type: 'warm'} -- same method, different type
// Valid range: 0-3 (0 = warm mist off; 1-3 = warm intensity levels)
def setWarmMistLevel(level){
    logDebug "setWarmMistLevel(${level})"
    Integer lvl = (level as Integer) ?: 0
    if (lvl < 0 || lvl > 3) {
        logError "Invalid warm mist level ${lvl} -- must be 0-3 (0=off, 1-3=warm intensity)"
        recordError("Invalid warm mist level ${lvl}", [method:"setVirtualLevel"])
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
        logError "Warm mist level write failed: ${lvl}"; recordError("Warm mist level write failed: ${lvl}", [method:"setVirtualLevel"])
    }
}

// ---------- Target humidity ----------
// CROSS-CHECK [pyvesync issue #296 / homebridge-levoit-humidifiers]:
//   Decision: clamp target humidity to 40-80%, NOT 30-80%.
//   Rationale: pyvesync's VeSyncHumid200300S base class inherits humidity_min=30, which is
//     incorrect for OasisMist 450S. pyvesync issue #296 documents that the device firmware
//     rejects values below 40 with API error code 11003000. homebridge-levoit-humidifiers
//     independently sets minHumidityLevel:40 for this model family, confirming the 40-floor.
//   Source: https://github.com/webdjoe/pyvesync/issues/296 (pyvesync issue #296);
//     https://github.com/RaresAil/homebridge-levoit-humidifiers (OasisMist 450S config,
//     minHumidityLevel:40).
//   Refutation: community user confirms values 30-39 are accepted by their device -->
//     restore the 30 floor (or make it model-code conditional).
// setTargetHumidity payload: {target_humidity: N} -- note snake_case (not camelCase)
def setHumidity(percent){
    logDebug "setHumidity(${percent})"
    Integer p = Math.max(40, Math.min(80, (percent as Integer) ?: 50))
    def resp = hubBypass("setTargetHumidity", [target_humidity: p], "setTargetHumidity(${p})")
    if (httpOk(resp)) {
        state.targetHumidity = p
        device.sendEvent(name:"targetHumidity", value: p)
        logInfo "Target humidity: ${p}%"
    } else {
        logError "Target humidity write failed: ${p}"; recordError("Target humidity write failed: ${p}", [method:"setTargetHumidity"])
    }
}

// ---------- Display ----------
// setDisplay payload: {state: bool} -- NOT {screenSwitch: int} (Superior 6000S)
def setDisplay(onOff){
    logDebug "setDisplay(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setDisplay", [state: v], "setDisplay(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"displayOn", value: onOff)
        logInfo "Display: ${onOff}"
    } else {
        logError "Display write failed"; recordError("Display write failed", [method:"setDisplay"])
    }
}

// ---------- RGB nightlight (LUH-O451S-WEU only -- runtime-gated) ----------
// All commands in this section check state.deviceType == "LUH-O451S-WEU" at runtime.
// Non-WEU users see these commands on their device page (ColorControl capability is
// static in metadata -- Hubitat limitation), but all calls no-op with an INFO log.
// See CROSS-CHECK block at the top of the file for full rationale.

// Internal gate helper -- avoids duplicating the check + log in every command.
private boolean isRgbVariant(){
    if (state.deviceType == "LUH-O451S-WEU") return true
    logInfo "RGB nightlight not supported on this hardware variant (${state.deviceType ?: 'unknown'}); ignoring command"
    return false
}

// setNightlightSwitch: turn RGB nightlight on or off, keeping last-known color + brightness.
// Sends setLightStatus with action="on"|"off" and brightness from state (default 100).
def setNightlightSwitch(value){
    logDebug "setNightlightSwitch(${value})"
    if (!isRgbVariant()) return
    String action = (value == "on") ? "on" : "off"
    // Keep last color + brightness when toggling -- pull from state or use defaults
    Integer brightness = (state.nightlightBrightness as Integer) ?: 100
    Integer hue = (state.nightlightHue as Integer) ?: 0          // degrees 0-360
    Integer sat = (state.nightlightSaturation as Integer) ?: 100 // 0-100
    def (Integer r, Integer g, Integer b) = hsvToRgb(hue / 360.0, sat / 100.0, 1.0)
    def (Integer adjR, Integer adjG, Integer adjB) = applyBrightnessToRgb(r, g, b, brightness)
    Integer sliderLoc = colorSliderLocation(r, g, b)
    def payload = [
        action: action, brightness: brightness,
        red: adjR, green: adjG, blue: adjB,
        colorMode: "color", speed: 0,
        colorSliderLocation: sliderLoc
    ]
    def resp = hubBypass("setLightStatus", payload, "setNightlightSwitch(${action})")
    if (httpOk(resp)) {
        state.rgbNightlightSetTime = now()
        state.nightlightAction = action
        device.sendEvent(name:"nightlightSwitch", value: action)
        logInfo "Nightlight switch: ${action}"
    } else {
        logError "Nightlight switch write failed: ${action}"; recordError("Nightlight switch write failed: ${action}", [method:"setLightStatus"])
    }
}

// setColor: set RGB nightlight color and turn it on.
// colorMap is the standard Hubitat map: {hue: 0-100, saturation: 0-100, level: 0-100}.
// Hubitat ColorControl hue is 0-100 (percentage of full circle), NOT 0-360 degrees.
// Convert hue 0-100 -> 0-360 for internal HSV calculations.
def setColor(Map colorMap){
    logDebug "setColor(${colorMap})"
    if (!isRgbVariant()) return
    Integer hue100  = Math.max(0, Math.min(100, (colorMap?.hue  as Integer) ?: 0))
    Integer sat100  = Math.max(0, Math.min(100, (colorMap?.saturation as Integer) ?: 100))
    // colorMap.level is Hubitat's SwitchLevel (0-100) -- use as brightness if provided.
    // brightness must be in range 40-100 (firmware floor at 40 per pyvesync PR #502).
    Integer brightness = (colorMap?.level != null)
        ? Math.max(40, Math.min(100, colorMap.level as Integer))
        : ((state.nightlightBrightness as Integer) ?: 100)
    // Raw (pre-brightness) RGB from hue/sat at full value -- used for colorSliderLocation.
    def (Integer rawR, Integer rawG, Integer rawB) = hsvToRgb(hue100 / 100.0, sat100 / 100.0, 1.0)
    // Brightness-adjusted RGB for the actual payload.
    def (Integer adjR, Integer adjG, Integer adjB) = applyBrightnessToRgb(rawR, rawG, rawB, brightness)
    Integer sliderLoc = colorSliderLocation(rawR, rawG, rawB)
    def payload = [
        action: "on", brightness: brightness,
        red: adjR, green: adjG, blue: adjB,
        colorMode: "color", speed: 0,
        colorSliderLocation: sliderLoc
    ]
    def resp = hubBypass("setLightStatus", payload, "setColor(h=${hue100},s=${sat100},b=${brightness})")
    if (httpOk(resp)) {
        state.rgbNightlightSetTime = now()
        state.nightlightHue        = (hue100 * 360 / 100) as Integer  // store as 0-360 internally
        state.nightlightSaturation = sat100
        state.nightlightBrightness = brightness
        state.nightlightAction     = "on"
        device.sendEvent(name:"hue",         value: hue100)
        device.sendEvent(name:"saturation",  value: sat100)
        device.sendEvent(name:"nightlightBrightness", value: brightness)
        device.sendEvent(name:"nightlightSwitch",     value: "on")
        device.sendEvent(name:"colorMode",   value: "RGB")
        logInfo "Nightlight color: hue=${hue100} sat=${sat100} brightness=${brightness}"
    } else {
        logError "Nightlight setColor failed"; recordError("Nightlight setColor failed", [method:"setLightStatus"])
    }
}

// setHue: adjust hue only, keeping current saturation and brightness, turning nightlight on.
// hue is 0-100 (Hubitat convention).
def setHue(hue){
    logDebug "setHue(${hue})"
    if (!isRgbVariant()) return
    Integer hue100 = Math.max(0, Math.min(100, (hue as Integer) ?: 0))
    Integer sat100 = (state.nightlightSaturation as Integer) ?: 100
    Integer brightness = (state.nightlightBrightness as Integer) ?: 100
    setColor([hue: hue100, saturation: sat100, level: brightness])
}

// setSaturation: adjust saturation only, keeping current hue and brightness, turning nightlight on.
// saturation is 0-100 (Hubitat convention).
def setSaturation(saturation){
    logDebug "setSaturation(${saturation})"
    if (!isRgbVariant()) return
    // Convert stored internal hue (0-360) back to Hubitat hue (0-100) for setColor round-trip.
    Integer hueInternal = (state.nightlightHue as Integer) ?: 0  // 0-360
    Integer hue100      = (hueInternal * 100 / 360) as Integer   // -> 0-100
    Integer sat100      = Math.max(0, Math.min(100, (saturation as Integer) ?: 100))
    Integer brightness  = (state.nightlightBrightness as Integer) ?: 100
    setColor([hue: hue100, saturation: sat100, level: brightness])
}

// ---------- RGB pure-function helpers (testable, no driver state dependencies) ----------

// rgbToHsv: convert (r, g, b) [0-255] to (h, s, v) [0.0-1.0].
// Pure function -- no side effects.
// Mirrors Python colorsys.rgb_to_hsv semantics used in pyvesync PR #502.
static List<Double> rgbToHsv(int r, int g, int b){
    double rd = r / 255.0; double gd = g / 255.0; double bd = b / 255.0
    double maxC = [rd, gd, bd].max()
    double minC = [rd, gd, bd].min()
    double delta = maxC - minC
    double v = maxC
    double s = (maxC == 0.0) ? 0.0 : delta / maxC
    double h = 0.0
    if (delta != 0.0) {
        if (maxC == rd)      h = ((gd - bd) / delta) % 6.0
        else if (maxC == gd) h = ((bd - rd) / delta) + 2.0
        else                 h = ((rd - gd) / delta) + 4.0
        h /= 6.0
        if (h < 0) h += 1.0
    }
    return [h, s, v]
}

// hsvToRgb: convert (h, s, v) [0.0-1.0] to (r, g, b) [0-255].
// Pure function -- no side effects.
// Mirrors Python colorsys.hsv_to_rgb semantics used in pyvesync PR #502.
static List<Integer> hsvToRgb(double h, double s, double v){
    if (s == 0.0) {
        int c = (int)(v * 255)
        return [c, c, c]
    }
    double i  = Math.floor(h * 6)
    double f  = (h * 6) - i
    double p  = v * (1.0 - s)
    double q  = v * (1.0 - s * f)
    double t  = v * (1.0 - s * (1.0 - f))
    int iMod  = ((int)i) % 6
    double rd, gd, bd
    switch (iMod) {
        case 0: rd=v; gd=t; bd=p; break
        case 1: rd=q; gd=v; bd=p; break
        case 2: rd=p; gd=v; bd=t; break
        case 3: rd=p; gd=q; bd=v; break
        case 4: rd=t; gd=p; bd=v; break
        default: rd=v; gd=p; bd=q; break
    }
    return [(int)(rd*255), (int)(gd*255), (int)(bd*255)]
}

// applyBrightnessToRgb: apply brightness [40-100] to (r,g,b) via HSV V-channel adjustment.
// Returns brightness-adjusted (r, g, b) [0-255].
// Pure function. Algorithm: rgb -> HSV, set V=brightness/100, HSV -> rgb.
// This mirrors pyvesync PR #502 _apply_brightness_to_rgb() using colorsys.
// Brightness is clamped to [40,100] (device firmware floor of 40).
static List<Integer> applyBrightnessToRgb(int r, int g, int b, int brightness){
    brightness = Math.max(40, Math.min(100, brightness))
    def (double h, double s, double v) = rgbToHsv(r, g, b)
    return hsvToRgb(h, s, brightness / 100.0)
}

// colorSliderLocation: derive colorSliderLocation (0-100) from RAW (pre-brightness) RGB.
// Uses nearest-point Euclidean match against the 8-color gradient anchor table from
// pyvesync PR #502 _RGB_NIGHTLIGHT_GRADIENT. These exact (r,g,b,location) values are
// sourced from the PR diff (2026-01) and must be kept in sync if PR #502 is revised.
// Pure function -- no driver state dependencies.
//
// 8 anchor colors (source: pyvesync PR #502 _RGB_NIGHTLIGHT_GRADIENT):
//   location  0: (252,  50,   0) -- Red
//   location 14: (255, 171,   2) -- Orange
//   location 29: (181, 255,   0) -- Yellow-Green
//   location 43: (  2, 255, 120) -- Green
//   location 57: (  3, 200, 254) -- Cyan
//   location 71: (  0,  40, 255) -- Blue
//   location 86: (220,   0, 255) -- Purple
//   location 100: (254,  0,  60) -- Pink
static int colorSliderLocation(int r, int g, int b){
    // [red, green, blue, location] for each gradient anchor
    final List<List<Integer>> gradient = [
        [252,  50,   0,   0],
        [255, 171,   2,  14],
        [181, 255,   0,  29],
        [  2, 255, 120,  43],
        [  3, 200, 254,  57],
        [  0,  40, 255,  71],
        [220,   0, 255,  86],
        [254,   0,  60, 100]
    ]
    int bestLoc = 0
    long bestDist = 0x7fffffffffffffffL  // Long.MAX_VALUE (safe literal -- avoids sandbox class lookup)
    for (entry in gradient) {
        long dr = (r - entry[0]).toLong()
        long dg = (g - entry[1]).toLong()
        long db = (b - entry[2]).toLong()
        long dist = dr*dr + dg*dg + db*db
        if (dist < bestDist) { bestDist = dist; bestLoc = entry[3] }
    }
    return bestLoc
}

// ---------- Nightlight probe (diagnostic, community data-gathering) ----------
// CROSS-CHECK [pyvesync issue #500]: see metadata command declaration above for full context.
// This is a diagnostic command only -- not part of regular device operation.
// The probe intentionally logs at INFO (descriptionTextEnable-gated) so the output
// is visible without enabling debugOutput. Users are asked to paste the log line on
// the community thread as evidence for/against nightlight support on their WUSR hardware.
def probeNightLight(){
    logInfo "Nightlight probe starting -- sending setNightLightBrightness(brightness=50) to device..."
    // brightness=50 is a safe mid-range value; no flash, minimal effect even if accepted.
    // night_light_brightness is the canonical pyvesync field name for this model class.
    def resp = hubBypass("setNightLightBrightness", [night_light_brightness: 50], "probeNightLight")
    // Probe response handler: always log at INFO regardless of settings (diagnostic intent).
    // Uses logAlways (no descriptionTextEnable gate) so the result is visible to the user
    // without needing to enable logging prefs. logAlways routes through the lib helper
    // (log.info directly) — no credential exposure risk since child drivers don't carry auth.
    // The inner code is the key signal -- 0 = device accepted, non-zero = device rejected.
    def innerCode = resp?.data?.result?.code
    if (innerCode == null) {
        // Null inner code: response was malformed or device is offline.
        logAlways "[OasisMist 450S] Nightlight probe INCONCLUSIVE -- no inner code in response " +
            "(device may be offline or API returned malformed response). " +
            "If device is online, try again. Otherwise report: resp=${resp} on pyvesync issue #500."
        return
    }
    Integer code = innerCode as Integer
    if (code == 0) {
        logAlways "[OasisMist 450S] Nightlight probe SUCCESS -- your device accepts " +
            "setNightLightBrightness (inner code 0). " +
            "Please paste this log line on the Hubitat community thread or pyvesync issue #500 " +
            "(https://github.com/webdjoe/pyvesync/issues/500) as evidence. " +
            "Model: LUH-O451S-WUSR nightlight CONFIRMED. This data supports adding nightlight " +
            "support in a future driver version."
    } else {
        logAlways "[OasisMist 450S] Nightlight probe REJECTED (inner code: ${code}) -- " +
            "your device does not support setNightLightBrightness, matching the v2.1 driver " +
            "assumption (no nightlight hardware). No action needed. " +
            "If you want to share this result: post on pyvesync issue #500 " +
            "(https://github.com/webdjoe/pyvesync/issues/500). " +
            "Model: LUH-O451S-WUSR nightlight REJECTED (inner code: ${code})."
    }
}

// ---------- Auto-stop ----------
// setAutomaticStop payload: {enabled: bool} -- NOT {autoStopSwitch: int} (Superior 6000S)
def setAutoStop(onOff){
    logDebug "setAutoStop(${onOff})"
    Boolean v = (onOff == "on")
    def resp = hubBypass("setAutomaticStop", [enabled: v], "setAutomaticStop(${onOff})")
    if (httpOk(resp)) {
        device.sendEvent(name:"autoStopEnabled", value: onOff)
        logInfo "Auto-stop: ${onOff}"
    } else {
        logError "Auto-stop write failed"; recordError("Auto-stop write failed", [method:"setAutomaticStop"])
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
    logDebug "update() from parent (2-arg, nightLight ignored -- 450S has no nightlight)"
    applyStatus(status)
    return true
}

// ---------- applyStatus ----------
def applyStatus(status){
    logDebug "applyStatus()"

    // BP16 watchdog: auto-disable debugOutput after 30 min even across hub reboots.
    // Placed here so all three update() entry points (0-arg, 1-arg, 2-arg) trigger it.
    ensureDebugWatchdog()

    // One-time pref seed: heal descriptionTextEnable=true default for users migrated
    // from older Type without Save (forward-compat -- BP#12)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    // Self-seed state.deviceType from parent's stored data value (Fix 2 / Task 11).
    // Covers existing-deployed installs where parent created the child before v2.2
    // added updateDataValue("deviceType", ...) to every addChildDevice branch.
    // The `if (!state.deviceType)` guard is idempotent -- only reads getDataValue once.
    if (!state.deviceType) {
        state.deviceType = device.getDataValue("deviceType") ?: ""
    }

    def r = status?.result ?: [:]
    // Defensive envelope peel -- humidifier bypassV2 responses can be double-wrapped.
    // Peel through any [code, result, traceId] envelope layers until device data is reached.
    // OasisMist 450S is typically single-wrapped, but the peel loop is defensive (BP#3).
    int peelGuard = 0
    while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
        r = r.result
        peelGuard++
    }
    // Diagnostic raw dump -- gated by debugOutput. Keep for ongoing field diagnostics.
    logDebug "applyStatus raw r (after peel=${peelGuard}) keys=${r?.keySet()}, values=${r}"

    // ---- Power ----
    // OasisMist 450S response uses `enabled` (boolean), NOT `powerSwitch` (int)
    def enabledRaw = r.enabled
    boolean powerOn = (enabledRaw instanceof Boolean) ? enabledRaw : ((enabledRaw as Integer) == 1)
    device.sendEvent(name:"switch", value: powerOn ? "on" : "off")

    // ---- Humidity ----
    if (r.humidity != null) device.sendEvent(name:"humidity", value: r.humidity as Integer)

    // ---- Target humidity -- from configuration.auto_target_humidity ----
    // Matches Classic 300S model: targetHumidity lives under configuration, not top-level
    Integer targetH = null
    if (r.configuration instanceof Map && r.configuration.auto_target_humidity != null) {
        targetH = r.configuration.auto_target_humidity as Integer
    }
    if (targetH != null) device.sendEvent(name:"targetHumidity", value: targetH)

    // ---- Mode ----
    // v2.2: normalize all firmware-reported auto aliases to user-facing "auto".
    // Firmware may report "auto" (canonical US), "autoPro" (some devices), or "humidity"
    // (WEU/WEUR variants per pyvesync PR #505 pattern). All three mean "auto" to the user.
    // sleep and manual pass through as-received.
    // This pairs with the write-path try-fallback logic in sendModeRequest() which similarly
    // normalizes the outcome to userMode="auto" regardless of payload variant used.
    String rawMode = (r.mode ?: "manual") as String
    String userMode = (rawMode in ["humidity", "autoPro"]) ? "auto" : rawMode
    state.mode = userMode
    device.sendEvent(name:"mode", value: userMode)

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
    // CROSS-CHECK [pyvesync vesynchumidifier.py:328 (known bug) / VeSyncLV600S class]:
    //   Decision: derive warmMistEnabled from warm_level value (level > 0 = on), NOT from
    //     warm_enabled boolean field. This mirrors the correct VeSyncLV600S logic and
    //     guards against the pyvesync VeSyncHumid200300S bug where warm_enabled=True is
    //     emitted even when warm_level=0.
    //   See setWarmMistLevel() CROSS-CHECK block above for full rationale.
    // warm_enabled and warm_level are top-level response fields (ClassicLVHumidResult)
    // OasisMist 450S populates these (unlike Classic 300S which always has warm_enabled=false)
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

    // ---- Humidity high indicator ----
    def humHigh = r.humidity_high
    boolean humHighBool = (humHigh instanceof Boolean) ? humHigh : ((humHigh as Integer) == 1)
    device.sendEvent(name:"humidityHigh", value: humHighBool ? "yes" : "no")

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
    // Canonical key: `display` (boolean) at top level.
    // Fallback: `indicator_light_switch` (legacy firmware alias, same as Classic 300S).
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

    // ---- RGB nightlight (LUH-O451S-WEU only) ----
    // Only emit RGB attributes when state.deviceType is the WEU variant.
    // US/KUS variants: neither night_light_brightness nor rgbNightLight will be present in
    // the response; the gate also guards against noise if a future firmware revision sends
    // unexpected fields on non-WEU hardware.
    // 180-second stale-data gate: after a write (setColor/setHue/setSaturation/setNightlightSwitch),
    // we set state.rgbNightlightSetTime. If the poll arrives within 180s of that write, the
    // device-reported RGB state may lag (hardware processes write then returns to idle state).
    // Suppress the read-path update during that window to avoid overwriting a just-sent command.
    if (state.deviceType == "LUH-O451S-WEU") {
        Long lastSet = state.rgbNightlightSetTime as Long
        boolean withinStaleWindow = (lastSet != null) && ((now() - lastSet) < 180_000L)
        if (withinStaleWindow) {
            logDebug "applyStatus: suppressing RGB read-path (within 180s of last write, lastSet=${lastSet})"
        } else {
            // rgbNightLight is a nested map in the getHumidifierStatus response for WEU.
            // Fields: action (on/off), brightness (40-100), red/green/blue (0-255), colorMode.
            // Null-guard: if the field is absent (firmware variant without RGB), skip silently.
            def nl = r?.rgbNightLight
            if (nl instanceof Map) {
                String nlAction = nl?.action as String
                Integer nlBrightness = nl?.brightness as Integer
                Integer nlR = nl?.red   as Integer
                Integer nlG = nl?.green as Integer
                Integer nlB = nl?.blue  as Integer
                if (nlAction != null) device.sendEvent(name:"nightlightSwitch",     value: nlAction)
                if (nlBrightness != null) device.sendEvent(name:"nightlightBrightness", value: nlBrightness)
                // Convert RGB -> HSV for standard ColorControl hue/saturation attributes.
                // Hue: 0-360 internally, Hubitat ColorControl expects 0-100 (percent of circle).
                // Saturation: 0.0-1.0 -> 0-100.
                if (nlR != null && nlG != null && nlB != null) {
                    def (double h, double s, double v) = rgbToHsv(nlR, nlG, nlB)
                    Integer hue100 = (int)(h * 100)
                    Integer sat100 = (int)(s * 100)
                    device.sendEvent(name:"hue",        value: hue100)
                    device.sendEvent(name:"saturation", value: sat100)
                    device.sendEvent(name:"colorMode",  value: "RGB")
                    logDebug "applyStatus: rgbNightLight action=${nlAction} brightness=${nlBrightness} RGB=(${nlR},${nlG},${nlB}) hue=${hue100} sat=${sat100}"
                }
            } else if (settings?.debugOutput && nl != null) {
                logDebug "applyStatus: rgbNightLight field present but unexpected type: ${nl?.class}"
            }
        }
    }
    // NOTE: non-WEU variants have NO night-light hardware. night_light_brightness and
    // rgbNightLight are not parsed and no nightlight events are emitted for those variants.

    // ---- Info HTML (use local variables -- avoids device.currentValue race; BP#7) ----
    def parts = []
    if (r.humidity != null) parts << "Humidity: ${r.humidity as Integer}%"
    if (targetH != null)    parts << "Target: ${targetH}%"
    if (mistVirtual != null) parts << "Mist: L${mistVirtual} (1-9)"
    parts << "Mode: ${userMode}"
    if (r.warm_level != null) {
        Integer wl = r.warm_level as Integer
        parts << "Warm: ${wl > 0 ? 'L'+wl : 'off'}"
    }
    parts << "Water: ${waterLacksStr == 'yes' ? 'empty' : 'ok'}"
    device.sendEvent(name:"info", value: parts.join("<br>"))
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

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
    logError "HTTP ${st}"; recordError("HTTP ${st}", [site:"httpOk"])
    return false
}

// ------------- END -------------
