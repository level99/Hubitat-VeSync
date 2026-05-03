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
// 2026-05-03: v2.5  Migrated to LevoitCorePurifier shared library (Phase 2, Round 6).
//                  Removed 14 shared methods now provided by LevoitCorePurifierLib.
//                  BP24-A fix: cycleSpeed() dead state.switch branch replaced with
//                  ensureSwitchOn() from LevoitChildBase.
//                  D2 fix: off() now emits speed:off for capability parity with
//                  other Core line drivers.
// 2026-04-29: v2.4  Added captureDiagnostics command + diagnostics attribute via
//                  LevoitDiagnostics library. Added recordError() ring-buffer calls at
//                  all logError / log.error sites.
// 2026-04-28: v2.3 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Added childLock attribute + setChildLock command (Core-line "Display Lock")
//                  - Added display read-back attribute (was write-only)
//                  - Added setTimer / cancelTimer commands + timerRemain attribute
//                  - Added resetFilter command
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
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S

#include level99.LevoitDiagnostics
#include level99.LevoitChildBase
#include level99.LevoitCorePurifier

metadata {
    definition(
        name: "Levoit Core200S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson",
        description: "Supports controlling the Levoit 200S / 300S air purifiers",
        version: "2.4.2",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Switch"
            capability "SwitchLevel"
            capability "FanControl"
            capability "Actuator"

            attribute "filter", "number";                              // Filter status (0-100%)
            attribute "mode", "string";                                // Purifier mode
            attribute "childLock", "string";                           // Front-panel button lock (VeSync app calls it "Display Lock" for Core line)
            attribute "display", "string";                             // Front-panel display state
            attribute "timerRemain", "number";                         // Auto-off timer remaining (seconds; 0 when no timer)

            attribute "info", "string";                               // HTML
            attribute "diagnostics", "string"
            // "true" | "false" — parent marks "false" after 3 self-heal attempts fail; flips back to "true" on first successful poll (BP21)
            attribute "online", "string"

            command "setDisplay", [[name:"Display*", type: "ENUM", description: "Display", constraints: ["on", "off"] ] ]
            command "setSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off", "low", "medium", "high"] ] ]
            command "setMode",  [[name:"Mode*", type: "ENUM", description: "Mode", constraints: ["manual", "sleep"] ] ]
            command "setChildLock", [[name: "On/Off*", type: "ENUM", description: "Display Lock (Child Lock)", constraints: ["on", "off"] ] ]
            command "setTimer", [[name: "Seconds*", type: "NUMBER", description: "Auto-off seconds (1-86400)"]]
            command "cancelTimer"
            command "resetFilter"
            command "toggle"
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
    ensureSwitchOn()    // BP24-A fix — replaces dead state.switch == "off" branch

    def speed = (state.speed == "low") ? "medium" : ( (state.speed == "medium") ? "high" : "low")
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
    // SwitchLevel convention: setLevel(0) means off (Z-Wave dimmer platform expectation).
    if (value == 0) { off(); return }
    // BP23: auto-on when switch is off (SwitchLevel capability convention).
    // state.turningOn guard prevents recursive on()->setSpeed()->setLevel() loop.
    if (!state.turningOn && device.currentValue("switch") != "on") on()
    def speed = 0
    setMode("manual") // always manual if setLevel() cmd was called

    if(value < 33) speed = 1
    if(value >= 33 && value < 66) speed = 2
    if(value >= 66) speed = 3

    device.sendEvent(name: "level", value: value)
    setSpeed(speed)
}

def setSpeed(speed) {
    logDebug "setSpeed(${speed})"
    if (speed == "off") {
        off()
    }
    else if (speed == "sleep") {
        setMode(speed)
        handleEvent("speed", "on")
    }
    else if (state.mode == "manual") {
        handleSpeed(speed)
        state.speed = speed
        handleEvent("speed", speed)
        logInfo "Speed: ${speed}"
    }
    else if (state.mode == "sleep") {
        setMode("manual")
        handleSpeed(speed)
        state.speed = speed
        handleEvent("speed", speed)
        logInfo "Speed: ${speed}"
    }
}

def setMode(mode) {
    logDebug "setMode(${mode})"
    handleMode(mode)
    state.mode = mode
    handleEvent("mode", mode)
    logInfo "Mode: ${mode}"
    switch(mode)
    {
        case "manual":
            handleEvent("speed", state.speed)
            break;
        case "sleep":
            handleEvent("speed", "on")
            break;
    }
}

def mapSpeedToInteger(speed) {
    return (speed == "low") ? 1 : ( (speed == "medium") ? 2 : 3)
}

def mapIntegerStringToSpeed(speed) {
    return (speed == "1") ? "low" : ( (speed == "2") ? "medium" : "high")
}

def mapIntegerToSpeed(speed) {
    return (speed == 1) ? "low" : ( (speed == 2) ? "medium" : "high")
}

// logDebug, logError, logInfo, logDebugOff, ensureDebugWatchdog, ensureSwitchOn
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// installed, uninstalled, initialize, toggle, setDisplay, handlePower, handleSpeed,
// handleMode, handleDisplayOn, setChildLock, setTimer, cancelTimer, resetFilter,
// checkHttpResponse are provided by #include level99.LevoitCorePurifier (LevoitCorePurifierLib.groovy).

def update() {

    logDebug "update()"

    def result = null

    def nightLight = parent.getChildDevice(device.getDataValue("cid")+"-nl")

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
                    result = update(status, nightLight)
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

    state.speed = mapIntegerToSpeed(status.result.level)
    state.mode = status.result.mode

    device.sendEvent(name: "switch", value: status.result.enabled ? "on" : "off")
    device.sendEvent(name: "mode", value: status.result.mode)

    def fl = status.result.filter_life
    device.sendEvent(name: "filter", value: fl)
    if (fl != null) {
        Integer flInt = fl as Integer
        Integer lastFl = state.lastFilterLife as Integer
        if (lastFl == null || lastFl >= 20) {
            if (flInt < 10) logInfo "Filter life critically low at ${flInt}%"
            else if (flInt < 20) logInfo "Filter life at ${flInt}% — consider replacement"
        } else if (lastFl >= 10 && flInt < 10) {
            logInfo "Filter life critically low at ${flInt}%"
        }
        state.lastFilterLife = flInt
    }

    switch(state.mode)
    {
        case "manual":
            device.sendEvent(name: "speed", value: mapIntegerToSpeed(status.result.level))
            break;
        case "sleep":
            device.sendEvent(name: "speed", value: "on")
            break;
    }

    // New v2.3 fields: child_lock, display, timer_remain
    if (status.result?.child_lock != null)
        device.sendEvent(name: "childLock", value: status.result.child_lock ? "on" : "off")
    if (status.result?.display != null)
        device.sendEvent(name: "display", value: status.result.display ? "on" : "off")
    if (status.result?.extension?.timer_remain != null)
        device.sendEvent(name: "timerRemain", value: status.result.extension.timer_remain as Integer)

    def html = "Filter: ${status.result.filter_life}%"
    device.sendEvent(name: "info", value: html)

    if (nightLight != null) {
        nightLight.update(status)
    }

    return status
}
