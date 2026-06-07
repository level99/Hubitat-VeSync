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
#include level99.LevoitCoreAQPurifier

metadata {
    definition(
        name: "Levoit Core300S Air Purifier",
        namespace: "NiklasGustafsson",
        author: "Niklas Gustafsson and elfege (contributor)",
        description: "Supports controlling the Levoit 300S air purifier",
        version: "2.9",
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
            attribute "airQuality", "number";                          // US-AQI (0-500); type="number" on Core 300S/400S/600S, "string" on Vital 100S/200S — families diverge. Use airQualityIndex for cross-family comparisons.

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

// Core 300S supports auto mode — setSpeed/setMode (provided by the lib) enable the
// auto branch + 3-value allowed-mode set via this hook (Bucket B2/B3, #142 Phase 2d).
private boolean supportsAutoMode() { true }

// Speed-band table for Core 300S (3-band). Index = API integer level, value = named band.
// Consumed by the lib's table-driven mapSpeedToInteger/mapIntegerToSpeed/
// mapIntegerStringToSpeed helpers (Bucket B1, #142 Phase 2c).
private Map getSpeedBands() { [1:"low", 2:"medium", 3:"high"] }

// Auto-preference modes for Core 300S. MUST match the setAutoMode command-constraint enum above.
// Consumed by the lib's setAutoMode to reject invalid input before waking an off device (BP24).
private List getAutoModes() { ["default", "quiet", "efficient"] }

// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog, ensureSwitchOn
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// installed, uninstalled, initialize, updated, on, off, toggle, setDisplay, handlePower,
// handleSpeed, handleMode, handleDisplayOn, setChildLock, setTimer, cancelTimer, resetFilter,
// checkHttpResponse, setLevel(value, duration), setLevel(value), cycleSpeed, mapSpeedToInteger,
// mapIntegerToSpeed, mapIntegerStringToSpeed, setSpeed, setMode, handleEvent
// are provided by #include level99.LevoitCorePurifier (LevoitCorePurifierLib.groovy).
// update, update(status, nightLight), setAutoMode, handleAutoMode, updateAQIandFilter,
// convertRange are provided by #include level99.LevoitCoreAQPurifier (LevoitCoreAQPurifierLib.groovy).

