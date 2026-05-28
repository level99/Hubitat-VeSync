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
//   REQUIRES: #include level99.LevoitChildBase    (provides logDebug/logError/ensureDebugWatchdog/seedPrefs)
//   REQUIRES: #include level99.LevoitCorePurifier (provides checkHttpResponse/handleEvent/updateAQIandFilter)
//   REQUIRES: host driver provides `def mapIntegerToSpeed(level)` returning a String speed name
//   PROVIDES: update() (0-arg self-fetch), update(status, nightLight) (2-arg poll dispatcher)

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
