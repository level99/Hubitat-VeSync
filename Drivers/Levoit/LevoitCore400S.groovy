/*

MIT License

Copyright (c) Niklas Gustafsson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

// History:
//
// 2026-05-03: v2.5  Migrated shared methods to LevoitCorePurifier library.
//                  BP24-A fix: cycleSpeed() auto-on-from-off via ensureSwitchOn().
//                  Sneak #2 fix: setSpeed() sleep-to-manual branch uses handleEvent() consistently.
//                  BP24-B fix (Phase 5c): setSpeed() + setMode() now call
//                  ensureSwitchOn() — auto-turn-on device when off before sending
//                  cloud command. BP18 null-guards added to both methods.
// 2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
//                  LevoitDiagnostics library. Added recordError() ring-buffer calls at
//                  all logError / log.error sites.
// 2026-04-28: v2.3 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Added childLock attribute + setChildLock command (Core-line "Display Lock")
//                  - Added display read-back attribute (was write-only)
//                  - Added setTimer / cancelTimer commands + timerRemain attribute
//                  - Added resetFilter command
//                  - Added pm25 attribute (raw µg/m³ from air_quality_value, previously buried in info HTML)
//                  - Added airQualityIndex attribute (Levoit categorical 1-4 from air_quality field)
//                  - Added AirQuality capability declaration
// 2026-04-25: v2.0 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Added descriptionTextEnable preference (default true) and gated logInfo helper
//                  - Added INFO logging at state-change transitions (power, mode, speed, filter alerts)
//                  - debugOutput consistently defaults to false
//                  - Removed legacy SmartThings icon URLs and "My Apps" category from metadata
//                  - Updated documentationLink to fork repo
// 2023-02-05: v1.6 Fixed the heartbeat logic.
// 2023-02-04: v1.5 Adding heartbeat event
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-19: v1.2 Support for setting the auto-mode of the purifier.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitCorePurifier

metadata {
    definition(
        name: "Levoit Core400S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson and elfege (contributor)",
        description: "Supports controlling the Levoit 400S air purifier",
        version: "2.5",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Switch"
            capability "FanControl"
            capability "Actuator"
            capability "SwitchLevel"
            capability "AirQuality"

            attribute "filter", "number";                              // Filter status (0-100%)
            attribute "mode", "string";                                // Purifier mode
            attribute "childLock", "string";                           // Front-panel button lock (VeSync app calls it "Display Lock" for Core line)
            attribute "display", "string";                             // Front-panel display state
            attribute "timerRemain", "number";                         // Auto-off timer remaining (seconds; 0 when no timer)
            attribute "pm25", "number";                                // Raw PM2.5 reading (µg/m³)
            attribute "airQualityIndex", "number";                     // Levoit categorical AQ index (1-4); distinct from computed US-AQI 'aqi'

            attribute "aqi", "number";                                 // AQI (0-500)
            attribute "aqiDanger", "string";                           // AQI danger level
            attribute "aqiColor", "string";                            // AQI HTML color

            attribute "info", "string";                               // HTML
            attribute "diagnostics", "string"
            // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
            attribute "online", "string"

            command "setDisplay", [[name:"Display*", type: "ENUM", description: "Display", constraints: ["on", "off"] ] ]
            command "setSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off", "sleep", "auto", "low", "medium", "high", "max"] ] ]
            command "setMode",  [[name:"Mode*", type: "ENUM", description: "Mode", constraints: ["manual", "sleep", "auto"] ] ]
            command "setAutoMode",  [
                [name:"Mode*", type: "ENUM", description: "Mode", constraints: ["default", "quiet", "efficient"] ],
                [ name:"Room Size", type: "NUMBER", description: "Room size in square feet" ] ]
            command "setChildLock", [[name: "On/Off*", type: "ENUM", description: "Display Lock (Child Lock)", constraints: ["on", "off"] ] ]
            command "setTimer", [[name: "Seconds*", type: "NUMBER", description: "Auto-off seconds (1-86400)"]]
            command "cancelTimer"
            command "resetFilter"
            command "toggle"
            command "update"
            command "captureDiagnostics"
        }

    preferences {
        input("descriptionTextEnable", "bool", title: "Enable descriptive (info-level) logging?", defaultValue: true)
        input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: false, required: false)
    }
}

def updated() {
	logDebug "Updated with settings: ${settings}"
    state.clear()
    unschedule()
	initialize()

    runIn(3, "update")

    // Turn off debug log in 30 minutes (happy path — no hub reboot)
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
}

def on() {
    logDebug "on()"

    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        handlePower(true)
        logInfo "Power on"
        handleEvent("switch", "on")

        if (state.speed != null) {
            setSpeed(state.speed)
        }
        else {
            setSpeed("low")
        }

        if (state.mode != null) {
            setMode(state.mode)
        }
        else {
            update()
        }
    } finally {
        state.remove('turningOn')
    }
}

def off() {
    logDebug "off()"

    if (state.turningOff) { logDebug "Already turning off, skipping re-entrant call"; return }
    state.turningOff = true
    try {
        handlePower(false)
        logInfo "Power off"
        handleEvent("switch", "off")
        handleEvent("speed", "off")
    } finally {
        state.remove('turningOff')
    }
}

def cycleSpeed() {
    logDebug "cycleSpeed()"
    ensureSwitchOn()

    def speed = "low";

    switch(state.speed) {
        case "low":
            speed = "medium";
            break;
        case "medium":
            speed = "high";
            break;
        case "high":
            speed = "max";
            break;
        case "max":
            speed = "low";
            break;
    }

    setSpeed(speed)
}

// 2-arg setLevel overload — Hubitat SwitchLevel capability standard signature.
// VeSync devices do NOT support hardware-level fade/duration, so the duration
// parameter is intentionally ignored. Delegates to the 1-arg version.
// Without this overload, any caller using the standard 2-arg form (Rule Machine
// with duration, dashboard tiles, MCP setLevel(N, D), third-party apps) throws
// MissingMethodException — Hubitat sandbox catches it silently and the command
// fails without user feedback.
def setLevel(value, duration)
{
    setLevel(value)
}

def setLevel(value)
{
    logDebug "setLevel $value"
    // BP18: null-guard converts null → 0 (null < N throws NPE; 0 routes cleanly to off() below).
    Integer pct = safeIntArg(value, 0, 0, 100)
    // SwitchLevel convention: setLevel(0) means off (Z-Wave dimmer platform expectation).
    if (pct == 0) { off(); return }
    // BP23: auto-on when switch is off (SwitchLevel capability convention).
    // state.turningOn guard prevents recursive on()->setSpeed()->setLevel() loop.
    if (!state.turningOn && device.currentValue("switch") != "on") on()
    def speed = 0
    setMode("manual") // always manual if setLevel() cmd was called

    if(pct < 25) speed = 1
    if(pct >= 25 && pct < 50) speed = 2
    if(pct >= 50 && pct < 75) speed = 3
    if(pct >= 75) speed = 4

    device.sendEvent(name: "level", value: pct)
    setSpeed(speed)
}

def setSpeed(speed) {
    logDebug "setSpeed(${speed})"
    if (!requireNotNull(speed, "setSpeed")) return false                        // BP18 null-guard
    String s = (speed as String).trim().toLowerCase()
    // Power short-circuits BEFORE ensureSwitchOn — setSpeed("off") must NOT auto-on first
    if (s == "off") { off(); return }
    ensureSwitchOn()                                                             // BP24-B auto-on (after short-circuit)
    if (s == "auto") {
        setMode(s)
        state.speed = s
        handleEvent("speed", s)
    }
    else if (s == "sleep") {
        setMode(s)
        handleEvent("speed", "on")
    }
    else if (state.mode == "manual") {
        handleSpeed(s)
        state.speed = s
        handleEvent("speed", s)
        logInfo "Speed: ${s}"
    }
    else if (state.mode == "sleep") {
        setMode("manual")
        handleSpeed(s)
        state.speed = s
        handleEvent("speed", s)
        logInfo "Speed: ${s}"
    }
}

def setMode(mode) {
    logDebug "setMode(${mode})"
    if (!requireNotNull(mode, "setMode")) return false                          // BP18 null-guard
    String m = (mode as String).trim().toLowerCase()
    if (!(m in ["manual", "sleep", "auto"])) {                                  // reject invalid BEFORE auto-on
        logWarn "setMode: invalid mode '${m}' -- must be one of: manual, sleep, auto; ignoring"
        return false
    }
    ensureSwitchOn()                                                             // BP24-B auto-on (after rejection checks)

    handleMode(m)
    state.mode = m
	handleEvent("mode", m)
    logInfo "Mode: ${m}"

    switch(m)
    {
        case "manual":
            handleEvent("speed",  state.speed)
            break;
        case "auto":
            handleEvent("speed",  "auto")
            break;
        case "sleep":
            handleEvent("speed",  "on")
            break;
    }
}

def mapSpeedToInteger(speed) {
    switch(speed)
    {
        case "1":
        case "low":
            return 1;
        case "2":
        case "medium":
            return 2;
        case "3":
        case "high":
            return 3;
    }
    return 4;
}

def mapIntegerStringToSpeed(speed) {
    switch(speed)
    {
        case "1":
            return "low";
        case "2":
            return "medium";
        case "3":
            return "high";
    }
    return "max";
}

def mapIntegerToSpeed(speed) {
    switch(speed)
    {
        case 1:
            return "low";
        case 2:
            return "medium";
        case 3:
            return "high";
    }
    return "max";
}

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog, ensureSwitchOn
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// installed, uninstalled, initialize, toggle, setDisplay, handlePower, handleSpeed,
// handleMode, handleDisplayOn, setChildLock, setTimer, cancelTimer, resetFilter,
// checkHttpResponse, setAutoMode, handleAutoMode, handleEvent, updateAQIandFilter,
// convertRange are provided by #include level99.LevoitCorePurifier (LevoitCorePurifierLib.groovy).

def update() {

    logDebug "update()"

    def result = null

    parent.sendBypassRequest(device,  [
                "method": "getPurifierStatus",
                "source": "APP",
                "data": [:]
            ]) { resp ->
			if (checkHttpResponse("update", resp))
			{
                def status = resp.data.result
                if (status == null) {
                    logError "No status returned from getPurifierStatus: ${resp.msg}"
                    recordError("No status returned from getPurifierStatus", [site:"update"])
                } else
                    result = update(status, null)
			}
		}
    return result
}

def update(status, nightLight)
{
    ensureDebugWatchdog()
    logDebug "update(status, nightLight)"
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    logDebug status

    def speed = mapIntegerToSpeed(status.result.level)
    def mode = status.result.mode
    def auto_mode = status?.result?.configuration?.auto_preference?.type
    def room_size = status?.result?.configuration?.auto_preference?.room_size

    handleEvent("switch", status.result.enabled ? "on" : "off")
    if (state.mode == null || mode != state.mode)
        handleEvent("mode",   status.result.mode)
    if (state.auto_mode == null || auto_mode != state.auto_mode)
        handleEvent("auto_mode", auto_mode)

    // state.mode must be set BEFORE switch evaluates — see Core 200S line 336/355 for canonical ordering
    state.speed = speed
    state.mode = status.result.mode
    state.auto_mode = auto_mode
    state.room_size = room_size

    switch(state.mode)
    {
        case "manual":
            handleEvent("speed",  speed)
            break;
        case "auto":
            handleEvent("speed",  "auto")
            break;
        case "sleep":
            handleEvent("speed",  "on")
            break;
    }

    // New v2.3 fields: child_lock, display, timer, pm25, airQualityIndex
    if (status.result?.child_lock != null)
        handleEvent("childLock", status.result.child_lock ? "on" : "off")
    if (status.result?.display != null)
        handleEvent("display", status.result.display ? "on" : "off")
    if (status.result?.extension?.timer_remain != null)
        handleEvent("timerRemain", status.result.extension.timer_remain as Integer)
    if (status.result?.air_quality_value != null)
        handleEvent("pm25", status.result.air_quality_value as Integer)
    if (status.result?.air_quality != null)
        handleEvent("airQualityIndex", status.result.air_quality as Integer)

    if (status.result?.air_quality_value != null)
        updateAQIandFilter(status.result.air_quality_value.toString(), status.result.filter_life)
}
