/*
 * MIT License
 *
 * Copyright (c) Niklas Gustafsson
 * Portions copyright (c) 2026 Dan Cox (level99/Hubitat-VeSync community fork)
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

library(
    name: "LevoitCorePurifier",
    namespace: "level99",
    author: "Dan Cox (level99) — extracted from NiklasGustafsson upstream",
    description: "Shared methods for Levoit Core 200S/300S/400S/600S air purifier drivers",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCorePurifierLib.groovy"
)

// Library contract:
//   REQUIRES: #include level99.LevoitDiagnostics  (provides recordError)
//   REQUIRES: #include level99.LevoitChildBase    (provides logInfo/logDebug/logError/ensureSwitchOn)
//   REQUIRES: host driver provides `def mapSpeedToInteger(speed)` returning Integer
//   PROVIDES: see Group 1 + Group 2 sections below for the 21 methods supplied.

// ---- Group 1: Shared 14 — called by all four Core drivers (200S/300S/400S/600S) ----

def installed() {
	logDebug "Installed with settings: ${settings}"
    updated();
}

def uninstalled() {
	logDebug "Uninstalled app"
}

def initialize() {
	logDebug "initializing"
}

def toggle() {
    logDebug "toggle()"
	if (device.currentValue("switch") == "on")
		off()
	else
		on()
}

def setDisplay(displayOn) {
    logDebug "setDisplay(${displayOn})"
    // BP18: null-guard — Rule Machine passes null for blank parameter slots.
    if (!requireNotNull(displayOn, "setDisplay")) return false
    // BP25: normalize to lowercase before C3 gate so "ON"/"OFF" strings from Rule Machine
    // are treated the same as "on"/"off". Without this, "ON" bypasses the gate AND the
    // downstream payload coercion evaluates ("ON" == "on") as false → sends wrong boolean.
    String v = (displayOn as String).trim().toLowerCase()
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("display") == v) return
    handleDisplayOn(v)
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

def handleDisplayOn(displayOn)
{
    logDebug "handleDisplayOn()"
    // displayOn is expected to be pre-normalized to lowercase by the setDisplay caller.
    // The payload coercion and event value both use it directly.

    def result = false

    parent.sendBypassRequest(device, [
                data: [ "state": (displayOn == "on")],
                "method": "setDisplay",
                "source": "APP"
            ]) { resp ->
			if (checkHttpResponse("handleDisplayOn", resp))
			{
                logDebug "Set display"
                device.sendEvent(name: "display", value: displayOn)
                logInfo "Display: ${displayOn}"
				result = true
			}
		}
    return result
}

def setChildLock(value) {
    // Core-line API uses child_lock (boolean); Vital-line API uses childLockSwitch (integer). Intentional divergence per pyvesync class hierarchy.
    logDebug "setChildLock(${value})"
    // BP18: null-guard — Rule Machine passes null for blank parameter slots.
    if (!requireNotNull(value, "setChildLock")) return false
    // BP25: normalize to lowercase before C3 gate and payload coercion.
    // "ON" from Rule Machine bypasses the gate and inverts the payload without this guard.
    String v = (value as String).trim().toLowerCase()
    // C3 state-change gate: no-op when value matches current attribute (suppresses redundant events)
    if (device.currentValue("childLock") == v) return
    def result = false
    parent.sendBypassRequest(device, [
                data: [ child_lock: (v == "on") ],
                "method": "setChildLock",
                "source": "APP"
            ]) { resp ->
        if (checkHttpResponse("setChildLock", resp)) {
            device.sendEvent(name: "childLock", value: v)
            logInfo "Child lock (Display Lock): ${v}"
            result = true
        }
    }
    return result
}

def setTimer(seconds) {
    int secs = safeIntArg(seconds, 0)
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

// 1-arg parent callback — BP1 requires all three overloads; this delegator lives in the lib
// so all four Core drivers (200S/300S/400S/600S) inherit it automatically.
def update(status) { update(status, null) }

// ---- Group 2: AQ-group 7 — called by 300S/400S/600S only (200S does not have AQ sensor) ----

def setAutoMode(mode) {
    setAutoMode(mode, 100);
}

def setAutoMode(mode, roomSize) {
    // 200S guard: 200S firmware doesn't support setAutoPreference (no AQ sensor).
    // The lib is shared but the cloud rejects this call on 200S. Block at lib boundary
    // to prevent state divergence + log noise on Rule Machine / MCP misuse.
    if (device.typeName?.contains("Core200S")) {
        logDebug "setAutoMode is not supported on Core 200S (no AQ sensor); ignoring"
        return
    }

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

        handleEvent("aqi", aqi);

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
