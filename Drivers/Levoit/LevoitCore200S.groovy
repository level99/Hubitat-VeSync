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

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated();
}

def updated() {
	logDebug "Updated with settings: ${settings}"

    state.clear()
    state.driverVersion = "2.4.1"
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

def uninstalled() {
	logDebug "Uninstalled app"
}

def initialize() {
	logDebug "initializing"
}

def on() {
    logDebug "on()"

    if (state.turningOn) { logDebug "Already turning on, skipping re-entrant call"; return }
    state.turningOn = true
    try {
        handlePower(true)
        logInfo "Power on"
        device.sendEvent(name: "switch", value: "on")

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
        device.sendEvent(name: "switch", value: "off")
    } finally {
        state.remove('turningOff')
    }
}

def toggle() {
    logDebug "toggle()"
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def cycleSpeed() {
    logDebug "cycleSpeed()"

    def speed = (state.speed == "low") ? "medium" : ( (state.speed == "medium") ? "high" : "low")
    
    if (state.switch == "off")
    {
        on()
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

    sendEvent(name: "level", value: value)
    setSpeed(speed)
}

def setSpeed(speed) {
    logDebug "setSpeed(${speed})"
    if (speed == "off") {
        off()
    }
    else if (speed == "sleep") {
        setMode(speed)
        device.sendEvent(name: "speed", value: "on")
    }
    else if (state.mode == "manual") {
        handleSpeed(speed)
        state.speed = speed
        device.sendEvent(name: "speed", value: speed)
        logInfo "Speed: ${speed}"
    }
    else if (state.mode == "sleep") {
        setMode("manual")
        handleSpeed(speed)
        state.speed = speed
        device.sendEvent(name: "speed", value: speed)
        logInfo "Speed: ${speed}"
    }
}

def setMode(mode) {
    logDebug "setMode(${mode})"
    handleMode(mode)
    state.mode = mode
	device.sendEvent(name: "mode", value: mode)
    logInfo "Mode: ${mode}"
    switch(mode)
    {
        case "manual":
            device.sendEvent(name: "speed", value: state.speed)
            break;
        case "sleep":
            device.sendEvent(name: "speed", value: "on")
            break;
    }
}

def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    handleDisplayOn(displayOn)
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

// logDebug, logError, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).

def handlePower(on) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ enabled: on, id: 0 ],
                "method": "setSwitch",
                "source": "APP" ]) { resp ->
			if (checkHttpResponse("handleOn", resp))
			{
                def operation = on ? "ON" : "OFF"
                logDebug "turned ${operation}()"
				result = true
			}
		}
    return result
}

def handleSpeed(speed) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ level: mapSpeedToInteger(speed), id: 0, type: "wind" ],
                "method": "setLevel",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleSpeed", resp))
			{
                logDebug "Set speed"
				result = true
			}
		}
    return result
}

def handleMode(mode) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "mode": mode ],
                "method": "setPurifierMode",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleMode", resp))
			{
                logDebug "Set mode"
				result = true
			}
		}
    return result
}

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

def setChildLock(value) {
    // Core-line API uses child_lock (boolean); Vital-line API uses childLockSwitch (integer). Intentional divergence per pyvesync class hierarchy.
    logDebug "setChildLock(${value})"
    def result = false
    parent.sendBypassRequest(device, [
                data: [ child_lock: (value == "on") ],
                "method": "setChildLock",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("setChildLock", resp)) {
            device.sendEvent(name: "childLock", value: value)
            logInfo "Child lock (Display Lock): ${value}"
            result = true
        }
    }
    return result
}

def setTimer(seconds) {
    int secs = (seconds as Integer) ?: 0
    logDebug "setTimer(${secs}s)"
    if (secs <= 0) { cancelTimer(); return }
    def result = false
    parent.sendBypassRequest(device, [
                data: [ action: "off", total: secs ],
                "method": "addTimer",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("setTimer", resp)) {
            def tid = resp?.data?.result?.id
            if (tid != null) state.timerId = tid
            logInfo "Timer set: power off in ${secs}s (id=${tid})"
            result = true
        }
    }
    return result
}

def cancelTimer() {
    logDebug "cancelTimer()"
    if (!state.timerId) { logDebug "No active timer to cancel"; return }
    def result = false
    parent.sendBypassRequest(device, [
                data: [ id: state.timerId ],
                "method": "delTimer",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("cancelTimer", resp)) {
            state.remove("timerId")
            logInfo "Timer cancelled"
            result = true
        }
    }
    return result
}

def resetFilter() {
    logDebug "resetFilter()"
    def result = false
    parent.sendBypassRequest(device, [
                data: [:],
                "method": "resetFilter",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("resetFilter", resp)) {
            logInfo "Filter life reset"
            result = true
        }
    }
    return result
}

def handleDisplayOn(displayOn)
{
    logDebug "handleDisplayOn()"

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "state": (displayOn == "on")],
                "method": "setDisplay",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleDisplayOn", resp))
			{
                logDebug "Set display"
				result = true
			}
		}
    return result
}


def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		logError "${action}: ${resp.status} - ${resp.getData()}"
		recordError("${action}: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
	else
	{
		logError "${action}: unexpected HTTP response: ${resp.status}"
		recordError("${action}: unexpected HTTP response: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
}
