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
    name: "LevoitCoreAQPurifier",
    namespace: "level99",
    author: "Dan Cox (level99) — extracted from NiklasGustafsson upstream",
    description: "AQ-only update() + update(status, nightLight) methods for Levoit Core 300S/400S/600S air purifiers. Core 200S is excluded (Bucket C1 — own inline versions per Phase 1 audit of Task #142).",
    documentationLink: "https://github.com/level99/Hubitat-VeSync",
    importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/LevoitCoreAQPurifierLib.groovy"
)

// Library contract:
//   REQUIRES: #include level99.LevoitDiagnostics  (provides recordError)
//   REQUIRES: #include level99.LevoitChildBase    (provides logDebug/logError/ensureDebugWatchdog/seedPrefs/requireNonEmptyEnum/safeIntArg)
//   REQUIRES: #include level99.LevoitCorePurifier (provides checkHttpResponse/handleEvent/handleMode)
//   REQUIRES: host driver provides `def mapIntegerToSpeed(level)` returning a String speed name
//   PROVIDES: update() (0-arg self-fetch), update(status, nightLight) (2-arg poll dispatcher),
//             setAutoMode(mode), setAutoMode(mode, roomSize), handleAutoMode(mode),
//             handleAutoMode(mode, size), updateAQIandFilter(String, filter), convertRange(...)

// Bucket A5 (#142 Phase 2b-amended): byte-identical 0-arg self-fetch across 300S/400S/600S.
// Issues getPurifierStatus bypassV2 request, validates the response envelope, and
// dispatches to the 2-arg update(status, nightLight) below. Core 200S has its own
// inline version (Bucket C — fetches the night-light child and threads it through).
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

// Bucket A6 (#142 Phase 2b-amended): byte-identical poll-data dispatcher across 300S/400S/600S.
// The nightLight parameter is unused on the AQ-trio (all three callers pass null from
// the 0-arg update() above); kept for BP1 3-signature uniformity. Core 200S has its
// own inline version (Bucket C1 — uses device.sendEvent directly, has inline filter-life
// logic, propagates state to its sibling night-light child).
//
// Calls into host driver for: mapIntegerToSpeed (per-driver — Bucket B1, Phase 2c).
// Calls into LevoitChildBase lib for: ensureDebugWatchdog (BP16), seedPrefs (BP12).
// Calls into LevoitCorePurifier lib for: checkHttpResponse, handleEvent, updateAQIandFilter.
def update(status, nightLight)
{
    ensureDebugWatchdog()
    logDebug "update(status, nightLight)"
    seedPrefs()

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

// ---- AQ-group 7 — auto-mode commands + AQI/filter parsing, called by 300S/400S/600S only ----
// (Relocated from LevoitCorePurifier in Phase 2b-cleanup: Core 200S has no AQ sensor and never
// references any of these methods. Living here — a lib 200S does NOT #include — removes them from
// 200S's method surface, so the prior typeName-based 200S runtime guard in setAutoMode is no longer
// needed and has been dropped.)

def setAutoMode(mode) {
    // Re-use last-set value so the device keeps its prior room-size calibration.
    // Fall back to 800 (pyvesync VeSyncAirBypass canonical default) when no prior
    // value exists, so the device doesn't receive an unrealistic sentinel.
    setAutoMode(mode, state.room_size ?: 800);
}

def setAutoMode(mode, roomSize) {
    if (!requireNonEmptyEnum(mode, "setAutoMode")) return
    // BP26: safe integer coercion — Rule Machine passes "" or null for blank numeric slots.
    // Nested safeIntArg: the inner call guards the fallback expression itself.
    // An unguarded `(state.room_size ?: 800) as Integer` cast can throw NumberFormatException
    // if state.room_size holds a non-numeric String written raw from the VeSync API response.
    // safeIntArg's try/catch cannot protect the fallback expression if it throws before the call
    // — nesting avoids the unguarded cast entirely.
    // Fallback chain: roomSize → state.room_size (safeIntArg-guarded) → 800 (literal, always safe).
    Integer sz = safeIntArg(roomSize, safeIntArg(state.room_size, 800))
    // Floor: room_size must be ≥1 (negative values from e.g. safeIntArg("-50"→-50 on valid-but-
    // negative RM input would self-poison state and send an invalid API value on every blank call).
    // Max clamp deferred: no authoritative upper bound confirmed from pyvesync or VeSync API docs.
    sz = Math.max(1, sz)

    logDebug "setAutoMode(${mode}, ${sz})"

    if (mode == "efficient") {
        handleAutoMode(mode, sz);
    }
    else {
        handleAutoMode(mode);
    }

    handleMode("auto");
    state.mode = "auto";
    state.auto_mode = mode;
    state.room_size = sz;

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
        // Adds a conventional `airQuality` NUMBER (US-AQI) attribute for Rule Machine / dashboard
        // ergonomics. The AirQuality capability's required attribute (`airQualityIndex`) is emitted
        // by the per-driver applyStatus before this method is called; this adds `airQuality` as
        // additive convenience under the conventional name, not a capability-contract fix.
        // airQualityIndex and aqi are unchanged — backward-compatible.
        handleEvent("airQuality", aqi);

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
