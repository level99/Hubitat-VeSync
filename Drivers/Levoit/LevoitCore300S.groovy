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

metadata {
    definition(
        name: "Levoit Core300S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson and elfege (contributor)",
        description: "Supports controlling the Levoit 300S air purifier",
        version: "2.4",
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
            command "setSpeed", [[name:"Speed*", type: "ENUM", description: "Speed", constraints: ["off", "sleep", "auto", "low", "medium", "high"] ] ]
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

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated();
}

def updated() {
	logDebug "Updated with settings: ${settings}"
    state.clear()
    state.driverVersion = "2.4"
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

def toggle() {
    logDebug "toggle()"
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def cycleSpeed() {
    logDebug "cycleSpeed()"

    def speed = "low";

    switch(state.speed) {
        case "low":
            speed = "medium";
            break;
        case "medium":
            speed = "high";
            break;
        case "high":
            speed = "low";
            break;
    }

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
    else if (speed == "auto") {
        setMode(speed)
        state.speed = speed
        handleEvent("speed", speed)
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
        device.sendEvent(name: "speed", value: speed)
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

def setAutoMode(mode) {
    setAutoMode(mode, 100);
}

def setAutoMode(mode, roomSize) {
    logDebug "setAutoMode(${mode}, ${roomSize})"
    
    if (mode == "efficient") {
        handleAutoMode(mode, roomSize);
    }
    else {
        handleAutoMode(mode);
    }

    handleMode("auto");
    state.mode = "auto";
    state.auto_mode = mode;
    state.room_size = roomSize;

	handleEvent("auto_mode", mode)
	handleEvent("mode", "auto")
    handleEvent("speed",  "auto")    
}

def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    handleDisplayOn(displayOn)
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
    }
    return 3;
}

def mapIntegerStringToSpeed(speed) {
    switch(speed)
    {
        case "1":
            return "low";
        case "2":
            return "medium";
    }
    return "high";
}

def mapIntegerToSpeed(speed) {
    switch(speed)
    {
        case 1:
            return "low";
        case 2:
            return "medium";
    }
    return "high";
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def logInfo(msg) {
    if (settings?.descriptionTextEnable) log.info msg
}

def logError(msg) {
    log.error msg
}

void logDebugOff() {
  //
  // runIn() callback to disable "Debug" logging after 30 minutes
  // Cannot be private
  //
  if (settings?.debugOutput) device.updateSetting("debugOutput", [type: "bool", value: false]);
}

// BP16 debug watchdog — auto-disable stuck debugOutput after hub reboot
private void ensureDebugWatchdog() {
    if (settings?.debugOutput && state.debugEnabledAt) {
        Long elapsed = now() - (state.debugEnabledAt as Long)
        if (elapsed > 30 * 60 * 1000) {
            logInfo "BP16 watchdog: 30 min elapsed since debug enable; auto-disabling now (post-reboot self-heal)"
            device.updateSetting("debugOutput", [type:"bool", value:false])
            state.remove("debugEnabledAt")
        }
    }
}

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

def handleAutoMode(mode) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "type": mode ],
                "method": "setAutoPreference",
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

def handleAutoMode(mode, size) {

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "type": mode, "room_size": size ],
                "method": "setAutoPreference",
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

private void handleEvent(name, val)
{
    logDebug "handleEvent(${name}, ${val})"
    device.sendEvent(name: name, value: val)
}

private void updateAQIandFilter(String val, filter) {

    logDebug "updateAQI(${val})"

    //
    // Conversions based on https://en.wikipedia.org/wiki/Air_quality_index
    //
    BigDecimal pm = val.toBigDecimal();

    BigDecimal aqi;

    if (state.prevPM == null || state.prevPM != pm || state.prevFilter == null || state.prevFilter != filter) {

        state.prevPM = pm;
        state.prevFilter = filter;

        if      (pm <  12.1) aqi = convertRange(pm,   0.0,  12.0,   0,  50);
        else if (pm <  35.5) aqi = convertRange(pm,  12.1,  35.4,  51, 100);
        else if (pm <  55.5) aqi = convertRange(pm,  35.5,  55.4, 101, 150);
        else if (pm < 150.5) aqi = convertRange(pm,  55.5, 150.4, 151, 200);
        else if (pm < 250.5) aqi = convertRange(pm, 150.5, 250.4, 201, 300);
        else if (pm < 350.5) aqi = convertRange(pm, 250.5, 350.4, 301, 400);
        else                 aqi = convertRange(pm, 350.5, 500.4, 401, 500);

        handleEvent("AQI", aqi);

        String danger;
        String color;

        if      (aqi <  51) { danger = "Good";                           color = "7e0023"; }
        else if (aqi < 101) { danger = "Moderate";                       color = "fff300"; }
        else if (aqi < 151) { danger = "Unhealthy for Sensitive Groups"; color = "f18b00"; }
        else if (aqi < 201) { danger = "Unhealthy";                      color = "e53210"; }
        else if (aqi < 301) { danger = "Very Unhealthy";                 color = "b567a4"; }
        else if (aqi < 401) { danger = "Hazardous";                      color = "7e0023"; }
        else {                danger = "Hazardous";                      color = "7e0023"; }

        if (state.lastAqiDanger != danger) logInfo "Air quality: ${danger}"
        state.lastAqiDanger = danger

        handleEvent("aqiColor", color)
        handleEvent("aqiDanger", danger)

        // Filter life threshold alerts
        if (filter != null) {
            Integer flInt = filter as Integer
            Integer lastFl = state.lastFilterLife as Integer
            if (lastFl == null || lastFl >= 20) {
                if (flInt < 10) logInfo "Filter life critically low at ${flInt}%"
                else if (flInt < 20) logInfo "Filter life at ${flInt}% — consider replacement"
            } else if (lastFl >= 10 && flInt < 10) {
                logInfo "Filter life critically low at ${flInt}%"
            }
            state.lastFilterLife = flInt
        }

        def html = "AQI: ${aqi}<br>PM2.5: ${pm} &micro;g/m&sup3;<br>Filter: ${filter}%"

        handleEvent("info", html)
        handleEvent("filter", filter)
    }
}

private BigDecimal convertRange(BigDecimal val, BigDecimal inMin, BigDecimal inMax, BigDecimal outMin, BigDecimal outMax, Boolean returnInt = true) {
  // Let make sure ranges are correct
  assert (inMin <= inMax);
  assert (outMin <= outMax);

  // Restrain input value
  if (val < inMin) val = inMin;
  else if (val > inMax) val = inMax;

  val = ((val - inMin) * (outMax - outMin)) / (inMax - inMin) + outMin;
  if (returnInt) {
    // If integer is required we use the Float round because the BigDecimal one is not supported/not working on Hubitat
    val = val.toFloat().round().toBigDecimal();
  }

  return (val);
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
		log.error "${action}: ${resp.status} - ${resp.getData()}"
		recordError("${action}: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
	else
	{
		log.error "${action}: unexpected HTTP response: ${resp.status}"
		recordError("${action}: unexpected HTTP response: ${resp.status}", [site:"checkHttpResponse"])
		return false
	}
}
