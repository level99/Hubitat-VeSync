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
// 2026-04-29: v2.4  Phase 5 — captureDiagnostics + error ring-buffer via LevoitDiagnosticsLib.
// 2026-04-25: v2.0 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Added descriptionTextEnable preference (default true) and gated logInfo helper
//                  - Added INFO logging at state-change transitions (night light on/off/dim)
//                  - debugOutput consistently defaults to false
// 2023-02-05: v1.6 Fixed the heartbeat logic.
// 2023-02-04: v1.5 Adding heartbeat event
// 2023-02-03: v1.4 Logging errors properly.
// 2022-08-05: v1.3 Fixed error caused by change in VeSync API for getPurifierStatus.
// 2022-07-19: v1.2 Support for setting the auto-mode of the purifier.
// 2022-07-18: v1.1 Support for Levoit Air Purifier Core 600S.
//                  Split into separate files for each device.
//                  Support for 'SwitchLevel' capability.
// 2021-10-22: v1.0 Support for Levoit Air Purifier Core 200S / 400S


#include level99.LevoitDiagnostics
#include level99.LevoitChildBase

metadata {
    definition(
        name: "Levoit Core200S Air Purifier Light",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson",
        description: "Supports controlling the Levoit 200S / 300S air purifiers' night light capability",
        version: "2.4.1",
        documentationLink: "https://github.com/level99/Hubitat-VeSync")
        {
            capability "Switch"
            capability "Switch Level"

            command "setNightLight", [[name:"Night Light*", type: "ENUM", description: "Display", constraints: ["on", "off", "dim"] ] ]

            attribute "diagnostics",     "string"
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

    update()

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
	setNightLight("on")
}

def off() {
    logDebug "off()"
	setNightLight("off")
}

def setNightLight(mode)
{
    logDebug "setNightLight(${mode})"

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "night_light": mode ],
                "method": "setNightLight",
                "source": "APP" ]) { resp ->
			if (checkHttpResponse("setNightLight", resp))
			{
                sendLevelEvent(mode)
                logInfo "Night light: ${mode}"
				result = true
			}
		}
    return result
}

def sendLevelEvent(mode)
{
    def dimLevel = 0
    def swtch = "off"

    if (mode == "on") { dimLevel = 100; swtch = "on"; }
    else if (mode == "dim") { dimLevel = 50; swtch = "on"; } 

    device.sendEvent(name: "level", value: dimLevel)
    device.sendEvent(name: "switch", value: swtch)
}

// 2-arg setLevel overload — Hubitat SwitchLevel capability standard signature.
// VeSync devices do NOT support hardware-level fade/duration, so the duration
// parameter is intentionally ignored. Delegates to the 1-arg version.
// Without this overload, any caller using the standard 2-arg form (Rule Machine
// with duration, dashboard tiles, MCP setLevel(N, D), third-party apps) throws
// MissingMethodException — Hubitat sandbox catches it silently and the command
// fails without user feedback.
def setLevel(level, duration) {
    setLevel(level)
}

def setLevel(level) {
    logDebug "setLevel(${level})"
    if (level < 10) { setNightLight("off") }
    else if (level > 75) { setNightLight("on") }
    else setNightLight("dim");
}

def update() {
    // Refresh only the parent Core 200S purifier, which will then update this night-light child
    // via the 2-arg update(status, nightLight) callback. Avoid calling parent.updateDevices()
    // which would poll every device on the account (cross-coupling, wasteful).
    def parentDNI = device.deviceNetworkId.replace("-nl", "")
    def parentDevice = parent.getChildDevice(parentDNI)
    if (parentDevice) {
        parentDevice.update()
    } else {
        logDebug "update(): parent purifier device not found for DNI=${parentDNI}"
    }
}

def update(status) {
    ensureDebugWatchdog()
    logDebug "update()"
    // One-time pref seed: heal descriptionTextEnable=true default for users migrated from older Type without Save (forward-compat)
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null) {
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        }
        state.prefsSeeded = true
    }

    def result = null  // declared here to avoid MissingPropertyException on return
    def mode = status.result.night_light
    state.mode = mode

    sendLevelEvent(mode)
    device.sendEvent(name: "mode", value: mode)

    return result
}

// logDebug, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// Note: logError is also provided by the lib (not previously defined locally in this driver).

def checkHttpResponse(action, resp) {
	if (resp.status == 200 || resp.status == 201 || resp.status == 204)
		return true
	else if (resp.status == 400 || resp.status == 401 || resp.status == 404 || resp.status == 409 || resp.status == 500)
	{
		log.error "${action}: ${resp.status} - ${resp.getData()}"; recordError("${action}: HTTP ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
	else
	{
		log.error "${action}: unexpected HTTP response: ${resp.status}"; recordError("${action}: unexpected HTTP ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
}
