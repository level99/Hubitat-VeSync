/*

MIT License

Copyright (c) 2026 Dan Cox (community fork — level99/Hubitat-VeSync)

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
// 2026-05-03: v2.5 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Align namespace to "NiklasGustafsson" to match all child drivers.
//                    Resolves cross-namespace addChildDevice failure unmasked by v2.4.2
//                    HPM bundle-install fix. Fallback namespace loop removed; single
//                    direct addChildDevice call now succeeds.
//
// 2026-04-29: v2.3 (community fork, level99/Hubitat-VeSync, by Dan Cox)
//                  - Phase 1.7: 5-family template architecture (v1_purifier, v2_purifier,
//                    v1_humidifier, v2_humidifier, fan) + 18 remaining fixture spawn surfaces.
//                  - FIXTURE_TO_FAMILY map; family-keyed STATE_MUTATORS; 5 family default-
//                    state constants (hand-curated from pyvesync canonical responses).
//                  - spawnFromFixture ENUM list expanded to all 19 fixtures.
//                  - Phase 1: driver skeleton + Core 200S fixture hand-coded.
//                  - Fixture-driven virtual parent for child driver development
//                    without owning the hardware.
//                  - Spawns child devices bound to canned fixtures, routes
//                    sendBypassRequest calls through fixture data, validates
//                    payload data keys against pyvesync canonical shapes.
//                  - [DEV TOOL] — Do NOT install for normal use.

#include level99.LevoitChildBase

import groovy.transform.Field

// ---------------------------------------------------------------------------
// Fixture-to-driver metadata maps
// ---------------------------------------------------------------------------
// The driver name strings MUST match the real child driver's definition(name:)
// exactly — Hubitat resolves child drivers by name string on addChildDevice().

@Field static final Map FIXTURE_TO_DRIVER = [
    "Classic200S"    : "Levoit Classic 200S Humidifier",
    "Classic300S"    : "Levoit Classic 300S Humidifier",
    "Core200S"       : "Levoit Core200S Air Purifier",
    "Core300S"       : "Levoit Core300S Air Purifier",
    "Core400S"       : "Levoit Core400S Air Purifier",
    "Core600S"       : "Levoit Core600S Air Purifier",
    "Dual200S"       : "Levoit Dual 200S Humidifier",
    "EL551S"         : "Levoit EverestAir Air Purifier",
    "LAP-B851S-WUS"  : "Levoit Sprout Air Purifier",
    "LAP-V102S"      : "Levoit Vital 100S Air Purifier",
    "LAP-V201S"      : "Levoit Vital 200S Air Purifier",
    "LEH-B381S"      : "Levoit Sprout Humidifier",
    "LEH-S601S"      : "Levoit Superior 6000S Humidifier",
    "LPF-R423S"      : "Levoit Pedestal Fan",
    "LTF-F422S"      : "Levoit Tower Fan",
    "LUH-A602S-WUS"  : "Levoit LV600S Humidifier",
    "LUH-A603S-WUS"  : "Levoit LV600S Hub Connect Humidifier",
    "LUH-M101S-WUS"  : "Levoit OasisMist 1000S Humidifier",
    "LUH-O451S-WUS"  : "Levoit OasisMist 450S Humidifier"
]

// deviceType values stored on children via updateDataValue("deviceType", ...).
// These must match what the real VeSync cloud API returns for each model,
// as that's what the real parent stores (and what deviceType() switch reads).
@Field static final Map FIXTURE_TO_DEVICETYPE = [
    "Classic200S"    : "Classic200S",
    "Classic300S"    : "LUH-A601S-WUSB",
    "Core200S"       : "Core200S",
    "Core300S"       : "Core300S",
    "Core400S"       : "Core400S",
    "Core600S"       : "Core600S",
    "Dual200S"       : "LUH-D301S-WUSR",
    "EL551S"         : "LAP-EL551S-WUS",
    "LAP-B851S-WUS"  : "LAP-B851S-WUS",
    "LAP-V102S"      : "LAP-V102S-WUS",
    "LAP-V201S"      : "LAP-V201S-WUS",
    "LEH-B381S"      : "LEH-B381S-WUS",
    "LEH-S601S"      : "LEH-S601S-WUS",
    "LPF-R423S"      : "LPF-R432S-AEU",    // note: pyvesync fixture typo R423 vs real R432
    "LTF-F422S"      : "LTF-F422S-WUS",
    "LUH-A602S-WUS"  : "LUH-A602S-WUS",
    "LUH-A603S-WUS"  : "LUH-A603S-WUS",
    "LUH-M101S-WUS"  : "LUH-M101S-WUS",
    "LUH-O451S-WUS"  : "LUH-O451S-WUS"
]

// configModule values from pyvesync device_map.py.
// Not load-bearing for spawn/poll logic — children read this for the info HTML
// attribute only. TBD entries will not break spawn or command routing.
@Field static final Map FIXTURE_TO_CONFIGMODULE = [
    "Classic200S"    : "WifiBTOnboardingNotificationsClassic200S",        // TBD: confirm from device_map
    "Classic300S"    : "WifiBTOnboardingNotificationsClassic300S",        // TBD: confirm from device_map
    "Core200S"       : "WifiBTOnboardingNotificationsCore200S",
    "Core300S"       : "WifiBTOnboardingNotificationsCore300S",           // TBD: confirm from device_map
    "Core400S"       : "WifiBTOnboardingNotificationsCore400S",           // TBD: confirm from device_map
    "Core600S"       : "WifiBTOnboardingNotificationsCore600S",           // TBD: confirm from device_map
    "Dual200S"       : "WifiBTOnboardingNotificationsDual200S",           // TBD: confirm from device_map
    "EL551S"         : "WifiBTOnboardingNotificationsEL551S",             // TBD: confirm from device_map
    "LAP-B851S-WUS"  : "WifiBTOnboardingNotificationsB851S",             // TBD: confirm from device_map
    "LAP-V102S"      : "WifiBTOnboardingNotificationsV102S",             // TBD: confirm from device_map
    "LAP-V201S"      : "WifiBTOnboardingNotificationsVital200S",         // TBD: confirm from device_map
    "LEH-B381S"      : "WifiBTOnboardingNotificationsB381S",             // TBD: confirm from device_map
    "LEH-S601S"      : "WifiBTOnboardingNotificationsSuperior6000S",     // TBD: confirm from device_map
    "LPF-R423S"      : "WifiBTOnboardingNotificationsPedestalFan",       // TBD: confirm from device_map
    "LTF-F422S"      : "WifiBTOnboardingNotificationsTowerFan",          // TBD: confirm from device_map
    "LUH-A602S-WUS"  : "WifiBTOnboardingNotificationsLV600S",            // TBD: confirm from device_map
    "LUH-A603S-WUS"  : "WifiBTOnboardingNotificationsLV600SHubConnect",  // TBD: confirm from device_map
    "LUH-M101S-WUS"  : "WifiBTOnboardingNotificationsOasisMist1000S",    // TBD: confirm from device_map
    "LUH-O451S-WUS"  : "WifiBTOnboardingNotificationsOasisMist450S"      // TBD: confirm from device_map
]

// Family assignment for each fixture — drives STATE_MUTATORS dispatch and
// canonicalDefaultState() family-template selection.
@Field static final Map FIXTURE_TO_FAMILY = [
    "Classic200S"    : "v1_humidifier",
    "Classic300S"    : "v1_humidifier",
    "Core200S"       : "v1_purifier",
    "Core300S"       : "v1_purifier",
    "Core400S"       : "v1_purifier",
    "Core600S"       : "v1_purifier",
    "Dual200S"       : "v1_humidifier",
    "EL551S"         : "v2_purifier",
    "LAP-B851S-WUS"  : "v2_purifier",
    "LAP-V102S"      : "v2_purifier",
    "LAP-V201S"      : "v2_purifier",
    "LEH-B381S"      : "v2_humidifier",
    "LEH-S601S"      : "v2_humidifier",
    "LPF-R423S"      : "fan",
    "LTF-F422S"      : "fan",
    "LUH-A602S-WUS"  : "v1_humidifier",
    "LUH-A603S-WUS"  : "v2_humidifier",
    "LUH-M101S-WUS"  : "v2_humidifier",
    "LUH-O451S-WUS"  : "v1_humidifier"
]

// ---------------------------------------------------------------------------
// Family default states — hand-curated from pyvesync canonical responses.
// One constant per family; per-fixture overrides applied in canonicalDefaultState().
// ---------------------------------------------------------------------------

// v1_purifier seed: Core300S device_on_manual_speed1 (superset of Core200S; adds AQ fields)
// Children read: status.result.{enabled, level, mode, filter_life, night_light,
//   display, child_lock, extension.timer_remain, air_quality, air_quality_value,
//   configuration.auto_preference.{type, room_size}}
@Field static final Map V1_PURIFIER_DEFAULT_STATE = [
    enabled          : true,
    level            : 1,
    mode             : "manual",
    filter_life      : 80,
    night_light      : "on",
    display          : true,
    child_lock       : false,
    air_quality      : 1,
    air_quality_value: 3,
    configuration    : [
        display         : true,
        display_forever : true,
        auto_preference : [type: "default", room_size: 0]
    ],
    extension        : [schedule_count: 0, timer_remain: 0],
    device_error_code: 0
].asImmutable()

// v2_purifier seed: LAP-V201S device_on_manual_speed2
// Children read: powerSwitch, workMode, fanSpeedLevel, manualSpeedLevel, AQLevel, PM25,
//   filterLifePercent, autoPreference.{autoPreferenceType, roomSize}, childLockSwitch,
//   lightDetectionSwitch, environmentLightState, screenState, screenSwitch, errorCode,
//   timerRemain. Also: fanRotateAngle (EL551S), VOC/CO2/AQI/humidity/temperature (Sprout Air).
@Field static final Map V2_PURIFIER_DEFAULT_STATE = [
    powerSwitch            : 1,
    workMode               : "manual",
    fanSpeedLevel          : 1,
    manualSpeedLevel       : 2,
    AQLevel                : 2,
    PM25                   : 3,
    filterLifePercent      : 80,
    autoPreference         : [autoPreferenceType: "default", roomSize: 0],
    childLockSwitch        : 1,
    lightDetectionSwitch   : 0,
    environmentLightState  : 1,
    screenState            : 1,
    screenSwitch           : 1,
    errorCode              : 0,
    timerRemain            : 0,
    efficientModeTimeRemain: 0,
    scheduleCount          : 0
].asImmutable()

// v1_humidifier seed: Classic300S device_on_manual_speed5
// Children read: enabled, humidity, mist_virtual_level, mist_level, mode, water_lacks,
//   humidity_high, water_tank_lifted, warm_enabled, warm_level, display,
//   automatic_stop_reach_target, night_light_brightness,
//   configuration.{auto_target_humidity, display, automatic_stop}
@Field static final Map V1_HUMIDIFIER_DEFAULT_STATE = [
    enabled                   : true,
    humidity                  : 42,
    mist_virtual_level        : 5,
    mist_level                : 5,
    mode                      : "manual",
    water_lacks               : false,
    humidity_high             : false,
    water_tank_lifted         : false,
    warm_enabled              : false,
    warm_level                : null,
    display                   : true,
    automatic_stop_reach_target: false,
    night_light_brightness    : 0,
    configuration             : [
        auto_target_humidity: 50,
        display             : true,
        automatic_stop      : false
    ]
].asImmutable()

// v2_humidifier seed: LEH-S601S device_on_manual_canonical
// Children read: powerSwitch, workMode, humidity, targetHumidity, virtualLevel, mistLevel,
//   waterLacksState, waterTankLifted, autoStopSwitch, autoStopState, screenSwitch,
//   screenState, scheduleCount, timerRemain, errorCode, dryingMode.{dryingLevel,
//   autoDryingSwitch, dryingState, dryingRemain}, autoPreference, childLockSwitch,
//   filterLifePercent, temperature.
@Field static final Map V2_HUMIDIFIER_DEFAULT_STATE = [
    powerSwitch    : 1,
    workMode       : "manual",
    humidity       : 50,
    targetHumidity : 60,
    virtualLevel   : 3,
    mistLevel      : 3,
    waterLacksState: 0,
    waterTankLifted: 0,
    autoStopSwitch : 0,
    autoStopState  : 0,
    screenSwitch   : 1,
    screenState    : 1,
    scheduleCount  : 0,
    timerRemain    : 0,
    errorCode      : 0,
    dryingMode     : [dryingLevel: 1, autoDryingSwitch: 1, dryingState: 0, dryingRemain: 7200],
    autoPreference : 1,
    childLockSwitch: 0,
    filterLifePercent: 85,
    temperature    : 700
].asImmutable()

// fan seed: LTF-F422S device_on_normal_speed5
// Children read: powerSwitch, workMode, manualSpeedLevel, fanSpeedLevel, screenState,
//   screenSwitch, oscillationSwitch, oscillationState, muteSwitch, muteState,
//   timerRemain, temperature, errorCode, scheduleCount, displayingType,
//   sleepPreference.{sleepPreferenceType, oscillationSwitch, initFanSpeedLevel,
//     fallAsleepRemain, autoChangeFanLevelSwitch}.
// Pedestal Fan also: horizontalOscillationState, verticalOscillationState, childLock.
@Field static final Map FAN_DEFAULT_STATE = [
    powerSwitch      : 1,
    workMode         : "normal",
    manualSpeedLevel : 5,
    fanSpeedLevel    : 5,
    screenState      : 1,
    screenSwitch     : 1,
    oscillationSwitch: 1,
    oscillationState : 1,
    muteSwitch       : 0,
    muteState        : 0,
    timerRemain      : 0,
    temperature      : 717,
    errorCode        : 0,
    scheduleCount    : 0,
    displayingType   : 0,
    sleepPreference  : [
        sleepPreferenceType       : "default",
        oscillationSwitch         : 1,
        initFanSpeedLevel         : 0,
        fallAsleepRemain          : 0,
        autoChangeFanLevelSwitch  : 1
    ]
].asImmutable()

// ---------------------------------------------------------------------------
// Fixture operation metadata: method name -> expected data keys
// Used for payload validation in sendBypassRequest.
//
// The block between the GENERATED markers below is regenerated by
// tools/regenerate_virtual_parent.py from tests/pyvesync-fixtures/*.yaml
// merged with tools/virtual_parent_extensions.json (which adds driver-source-
// derived methods that pyvesync's canonical fixture doesn't yet cover, e.g.
// the v2.3 Core 200S additions: setDisplay/setChildLock/addTimer/delTimer/
// resetFilter). Manual edits between the markers will be overwritten.
// ---------------------------------------------------------------------------

// === FIXTURE_OPS GENERATED START ===
@Field static final Map FIXTURE_OPS = [
    "Classic200S": [
        [methodName: "setHumidityMode",          dataKeys: ["mode"] as Set],
        [methodName: "setTargetHumidity",        dataKeys: ["target_humidity"] as Set],
        [methodName: "setVirtualLevel",          dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",                dataKeys: ["enabled", "id"] as Set],
        [methodName: "setAutomaticStop",         dataKeys: ["enabled"] as Set],
        [methodName: "setIndicatorLightSwitch",  dataKeys: ["enabled", "id"] as Set],
        [methodName: "getHumidifierStatus",      dataKeys: [] as Set]
    ],
    "Classic300S": [
        [methodName: "setHumidityMode",      dataKeys: ["mode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["target_humidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",            dataKeys: ["enabled", "id"] as Set],
        [methodName: "setAutomaticStop",     dataKeys: ["enabled"] as Set],
        [methodName: "setDisplay",           dataKeys: ["state"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "Core200S": [
        [methodName: "setLevel",           dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setPurifierMode",    dataKeys: ["mode"] as Set],
        [methodName: "setSwitch",          dataKeys: ["enabled", "id"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set],
        [methodName: "setDisplay",         dataKeys: ["state"] as Set],
        [methodName: "setChildLock",       dataKeys: ["child_lock"] as Set],
        [methodName: "addTimer",           dataKeys: ["action", "total"] as Set],
        [methodName: "delTimer",           dataKeys: ["id"] as Set],
        [methodName: "resetFilter",        dataKeys: [] as Set]
    ],
    "Core300S": [
        [methodName: "setPurifierMode",    dataKeys: ["mode"] as Set],
        [methodName: "setLevel",           dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",          dataKeys: ["enabled", "id"] as Set],
        [methodName: "setDisplay",         dataKeys: ["state"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "Core400S": [
        [methodName: "setPurifierMode",    dataKeys: ["mode"] as Set],
        [methodName: "setLevel",           dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",          dataKeys: ["enabled", "id"] as Set],
        [methodName: "setDisplay",         dataKeys: ["state"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "Core600S": [
        [methodName: "setPurifierMode",    dataKeys: ["mode"] as Set],
        [methodName: "setLevel",           dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",          dataKeys: ["enabled", "id"] as Set],
        [methodName: "setDisplay",         dataKeys: ["state"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "Dual200S": [
        [methodName: "setHumidityMode",      dataKeys: ["mode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["target_humidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",            dataKeys: ["enabled", "id"] as Set],
        [methodName: "setAutomaticStop",     dataKeys: ["enabled"] as Set],
        [methodName: "setDisplay",           dataKeys: ["state"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "EL551S": [
        [methodName: "setPurifierMode",    dataKeys: ["workMode"] as Set],
        [methodName: "setLevel",           dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setSwitch",          dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setDisplay",         dataKeys: ["screenSwitch"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "LAP-B851S-WUS": [
        [methodName: "setPurifierMode",    dataKeys: ["workMode"] as Set],
        [methodName: "setLevel",           dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setSwitch",          dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setDisplay",         dataKeys: ["screenSwitch"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "LAP-V102S": [
        [methodName: "setPurifierMode",    dataKeys: ["workMode"] as Set],
        [methodName: "setLevel",           dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setSwitch",          dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setDisplay",         dataKeys: ["screenSwitch"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "LAP-V201S": [
        [methodName: "setPurifierMode",    dataKeys: ["workMode"] as Set],
        [methodName: "setLevel",           dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setSwitch",          dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setDisplay",         dataKeys: ["screenSwitch"] as Set],
        [methodName: "getPurifierStatus",  dataKeys: [] as Set]
    ],
    "LEH-B381S": [
        [methodName: "setHumidityMode",      dataKeys: ["workMode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["targetHumidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["levelIdx", "levelType", "virtualLevel"] as Set],
        [methodName: "setSwitch",            dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setAutoStopSwitch",    dataKeys: ["autoStopSwitch"] as Set],
        [methodName: "setDisplay",           dataKeys: ["screenSwitch"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "LEH-S601S": [
        [methodName: "setHumidityMode",      dataKeys: ["workMode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["targetHumidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["levelIdx", "levelType", "virtualLevel"] as Set],
        [methodName: "setSwitch",            dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setAutoStopSwitch",    dataKeys: ["autoStopSwitch"] as Set],
        [methodName: "setDisplay",           dataKeys: ["screenSwitch"] as Set],
        [methodName: "setDryingMode",        dataKeys: ["autoDryingSwitch"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "LPF-R423S": [
        [methodName: "setLevel",              dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setOscillationStatus",  dataKeys: ["actType", "bottom", "horizontalOscillationState", "left", "right", "top", "verticalOscillationState"] as Set],
        [methodName: "setSwitch",             dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "getFanStatus",          dataKeys: [] as Set],
        // Extensions: pyvesync vendored LPF-R423S.yaml omits setFanMode and setDisplay.
        // Source: tools/virtual_parent_extensions.json LPF-R423S entry.
        [methodName: "setFanMode",            dataKeys: ["workMode"] as Set],
        [methodName: "setDisplay",            dataKeys: ["screenSwitch"] as Set]
    ],
    "LTF-F422S": [
        [methodName: "setLevel",              dataKeys: ["levelIdx", "levelType", "manualSpeedLevel"] as Set],
        [methodName: "setSwitch",             dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setMuteSwitch",         dataKeys: ["muteSwitch"] as Set],
        [methodName: "setOscillationSwitch",  dataKeys: ["oscillationSwitch"] as Set],
        [methodName: "getTowerFanStatus",     dataKeys: [] as Set],
        // Extensions: pyvesync vendored LTF-F422S.yaml omits setTowerFanMode and setDisplay.
        // Source: tools/virtual_parent_extensions.json LTF-F422S entry.
        [methodName: "setTowerFanMode",       dataKeys: ["workMode"] as Set],
        [methodName: "setDisplay",            dataKeys: ["screenSwitch"] as Set]
    ],
    "LUH-A602S-WUS": [
        [methodName: "setHumidityMode",      dataKeys: ["mode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["target_humidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",            dataKeys: ["enabled", "id"] as Set],
        [methodName: "setAutomaticStop",     dataKeys: ["enabled"] as Set],
        [methodName: "setDisplay",           dataKeys: ["state"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "LUH-A603S-WUS": [
        [methodName: "setHumidityMode",      dataKeys: ["workMode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["targetHumidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["levelIdx", "levelType", "virtualLevel"] as Set],
        [methodName: "setLevel",             dataKeys: ["levelIdx", "levelType", "mistLevel", "warmLevel"] as Set],
        [methodName: "setSwitch",            dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setDisplay",           dataKeys: ["screenSwitch"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "LUH-M101S-WUS": [
        [methodName: "setHumidityMode",      dataKeys: ["workMode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["targetHumidity"] as Set],
        [methodName: "virtualLevel",         dataKeys: ["levelIdx", "levelType", "virtualLevel"] as Set],
        [methodName: "setSwitch",            dataKeys: ["powerSwitch", "switchIdx"] as Set],
        [methodName: "setAutoStopSwitch",    dataKeys: ["autoStopSwitch"] as Set],
        [methodName: "setDisplay",           dataKeys: ["screenSwitch"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ],
    "LUH-O451S-WUS": [
        [methodName: "setHumidityMode",      dataKeys: ["mode"] as Set],
        [methodName: "setTargetHumidity",    dataKeys: ["target_humidity"] as Set],
        [methodName: "setVirtualLevel",      dataKeys: ["id", "level", "type"] as Set],
        [methodName: "setSwitch",            dataKeys: ["enabled", "id"] as Set],
        [methodName: "setAutomaticStop",     dataKeys: ["enabled"] as Set],
        [methodName: "setDisplay",           dataKeys: ["state"] as Set],
        [methodName: "getHumidifierStatus",  dataKeys: [] as Set]
    ]
]
// === FIXTURE_OPS GENERATED END ===

// ---------------------------------------------------------------------------
// State mutators: family-keyed Map of method -> closure (snap, data) -> void
// updateCanonicalState() looks up the fixture's family, then dispatches here.
// ---------------------------------------------------------------------------

@Field static final Map STATE_MUTATORS = [

    "v1_purifier": [
        "setSwitch": { Map snap, Map data ->
            if (data?.containsKey("enabled")) snap.enabled = data.enabled
        },
        "setLevel": { Map snap, Map data ->
            if (data?.containsKey("level")) snap.level = data.level
            snap.mode = "manual"
        },
        "setPurifierMode": { Map snap, Map data ->
            if (data?.containsKey("mode")) snap.mode = data.mode
        },
        "setDisplay": { Map snap, Map data ->
            if (data?.containsKey("state")) snap.display = data["state"]
        },
        "setChildLock": { Map snap, Map data ->
            if (data?.containsKey("child_lock")) snap.child_lock = data.child_lock
        },
        "addTimer": { Map snap, Map data ->
            snap.extension = snap.extension ?: [:]
            snap.extension.timer_remain = data?.total ?: 0
        },
        "delTimer": { Map snap, Map data ->
            snap.extension = snap.extension ?: [:]
            snap.extension.timer_remain = 0
        },
        "resetFilter": { Map snap, Map data ->
            snap.filter_life = 100
        }
    ],

    "v2_purifier": [
        "setSwitch": { Map snap, Map data ->
            if (data?.containsKey("powerSwitch")) snap.powerSwitch = data.powerSwitch
        },
        "setLevel": { Map snap, Map data ->
            if (data?.containsKey("manualSpeedLevel")) snap.manualSpeedLevel = data.manualSpeedLevel
            snap.workMode = "manual"
        },
        "setPurifierMode": { Map snap, Map data ->
            if (data?.containsKey("workMode")) snap.workMode = data.workMode
        },
        "setDisplay": { Map snap, Map data ->
            if (data?.containsKey("screenSwitch")) snap.screenSwitch = data.screenSwitch
        },
        "setChildLock": { Map snap, Map data ->
            if (data?.containsKey("childLockSwitch")) snap.childLockSwitch = data.childLockSwitch
        },
        "setLightDetection": { Map snap, Map data ->
            if (data?.containsKey("lightDetectionSwitch")) snap.lightDetectionSwitch = data.lightDetectionSwitch
        },
        "resetFilter": { Map snap, Map data ->
            snap.filterLifePercent = 100
        }
    ],

    "v1_humidifier": [
        "setSwitch": { Map snap, Map data ->
            if (data?.containsKey("enabled")) snap.enabled = data.enabled
        },
        "setHumidityMode": { Map snap, Map data ->
            if (data?.containsKey("mode")) snap.mode = data.mode
        },
        "setVirtualLevel": { Map snap, Map data ->
            if (data?.containsKey("level")) {
                snap.mist_virtual_level = data.level
                snap.mist_level = data.level
            }
        },
        "setTargetHumidity": { Map snap, Map data ->
            if (data?.containsKey("target_humidity")) {
                snap.configuration = snap.configuration ?: [:]
                snap.configuration.auto_target_humidity = data.target_humidity
            }
        },
        "setDisplay": { Map snap, Map data ->
            if (data?.containsKey("state")) snap.display = data["state"]
        },
        "setIndicatorLightSwitch": { Map snap, Map data ->
            // Classic 200S uses setIndicatorLightSwitch instead of setDisplay
            if (data?.containsKey("enabled")) snap.indicator_light_switch = data.enabled
        },
        "setAutomaticStop": { Map snap, Map data ->
            if (data?.containsKey("enabled")) {
                snap.configuration = snap.configuration ?: [:]
                snap.configuration.automatic_stop = data.enabled
            }
        }
    ],

    "v2_humidifier": [
        "setSwitch": { Map snap, Map data ->
            if (data?.containsKey("powerSwitch")) snap.powerSwitch = data.powerSwitch
        },
        "setHumidityMode": { Map snap, Map data ->
            if (data?.containsKey("workMode")) snap.workMode = data.workMode
        },
        "setVirtualLevel": { Map snap, Map data ->
            if (data?.containsKey("virtualLevel")) {
                snap.virtualLevel = data.virtualLevel
                snap.mistLevel = data.virtualLevel
            }
        },
        "setTargetHumidity": { Map snap, Map data ->
            if (data?.containsKey("targetHumidity")) snap.targetHumidity = data.targetHumidity
        },
        "setDisplay": { Map snap, Map data ->
            if (data?.containsKey("screenSwitch")) snap.screenSwitch = data.screenSwitch
        },
        "setAutoStopSwitch": { Map snap, Map data ->
            if (data?.containsKey("autoStopSwitch")) snap.autoStopSwitch = data.autoStopSwitch
        },
        "setDryingMode": { Map snap, Map data ->
            if (data?.containsKey("autoDryingSwitch")) {
                snap.dryingMode = snap.dryingMode ?: [:]
                snap.dryingMode.autoDryingSwitch = data.autoDryingSwitch
            }
        },
        // LUH-A603S-WUS warm mist (uses 'setLevel', not 'setVirtualLevel')
        "setLevel": { Map snap, Map data ->
            if (data?.containsKey("warmLevel")) snap.warmLevel = data.warmLevel
        },
        // OasisMist 1000S mist level (uses 'virtualLevel' method, not 'setVirtualLevel')
        "virtualLevel": { Map snap, Map data ->
            if (data?.containsKey("virtualLevel")) {
                snap.virtualLevel = data.virtualLevel
                snap.mistLevel = data.virtualLevel
            }
        }
    ],

    "fan": [
        "setSwitch": { Map snap, Map data ->
            if (data?.containsKey("powerSwitch")) snap.powerSwitch = data.powerSwitch
        },
        "setLevel": { Map snap, Map data ->
            if (data?.containsKey("manualSpeedLevel")) snap.manualSpeedLevel = data.manualSpeedLevel
            snap.workMode = "normal"  // speed set implies normal mode
        },
        "setTowerFanMode": { Map snap, Map data ->
            if (data?.containsKey("workMode")) snap.workMode = data.workMode
        },
        "setFanMode": { Map snap, Map data ->
            if (data?.containsKey("workMode")) snap.workMode = data.workMode
        },
        "setOscillationSwitch": { Map snap, Map data ->
            // Tower Fan: single-axis oscillation
            if (data?.containsKey("oscillationSwitch")) snap.oscillationSwitch = data.oscillationSwitch
        },
        "setOscillationStatus": { Map snap, Map data ->
            // Pedestal Fan: two-axis oscillation (H+V)
            if (data?.containsKey("horizontalOscillationState")) snap.horizontalOscillationState = data.horizontalOscillationState
            if (data?.containsKey("verticalOscillationState"))   snap.verticalOscillationState   = data.verticalOscillationState
        },
        "setMuteSwitch": { Map snap, Map data ->
            if (data?.containsKey("muteSwitch")) snap.muteSwitch = data.muteSwitch
        },
        "setDisplay": { Map snap, Map data ->
            if (data?.containsKey("screenSwitch")) snap.screenSwitch = data.screenSwitch
        }
    ]
]

metadata {
    definition(
        name: "VeSync Virtual Test Parent",
        namespace: "NiklasGustafsson",
        author: "Dan Cox",
        description: "[DEV TOOL] Fixture-driven test harness for child driver development without owning the hardware. Do NOT install for normal use — does not connect to VeSync cloud.",
        importUrl: "https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/VeSyncIntegrationVirtual.groovy",
        documentationLink: "https://github.com/level99/Hubitat-VeSync/blob/main/CONTRIBUTING.md",
        singleThreaded: true,
        version: "2.4.2"
    ) {
        capability "Refresh"

        // Reflects current operational mode of the virtual parent.
        // "ready"    — nominal; safe to spawn (coexists with real parent if present)
        // "spawning" — spawnFromFixture in progress
        attribute "fixtureMode",          "string"
        attribute "lastFixtureLoaded",    "string"
        attribute "lastSendBypassMethod", "string"

        command "spawnFromFixture",  [
            [name: "Fixture", type: "ENUM", constraints: [
                "Classic200S",
                "Classic300S",
                "Core200S",
                "Core300S",
                "Core400S",
                "Core600S",
                "Dual200S",
                "EL551S",
                "LAP-B851S-WUS",
                "LAP-V102S",
                "LAP-V201S",
                "LEH-B381S",
                "LEH-S601S",
                "LPF-R423S",
                "LTF-F422S",
                "LUH-A602S-WUS",
                "LUH-A603S-WUS",
                "LUH-M101S-WUS",
                "LUH-O451S-WUS"
            ]],
            [name: "Child label", type: "STRING"]
        ]
        command "resetAllChildren"
        command "stepFixtureResponse", [
            [name: "Child DNI",  type: "STRING"],
            [name: "Op name",    type: "STRING"]
        ]
    }

    preferences {
        input("descriptionTextEnable", "bool",
              title: "Enable info logging",
              defaultValue: true)
        input("debugOutput", "bool",
              title: "Enable debug logging",
              defaultValue: false)
        // verboseDebug defaults TRUE — this is a dev tool; contributors want full traces.
        input("verboseDebug", "bool",
              title: "Full payload + response trace (default ON for dev tool)",
              defaultValue: true)
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logInfo "[DEV TOOL] Virtual parent installed."
    runIn(1, "runPreFlight")
    // BP12 pref-seed
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null)
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        state.prefsSeeded = true
    }
}

def updated() {
    logDebug "updated()"
    state.driverVersion = "2.4.1"
    // BP12 pref-seed
    if (!state.prefsSeeded) {
        if (settings?.descriptionTextEnable == null)
            device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
        state.prefsSeeded = true
    }
    // BP16: record debug enable timestamp; happy-path runIn disables after 30 min
    if (settings?.debugOutput) {
        runIn(1800, "logDebugOff")
        state.debugEnabledAt = now()
    } else {
        state.remove("debugEnabledAt")
    }
    runIn(1, "runPreFlight")
}

def initialize() {
    logDebug "initialize()"
}

def uninstalled() {
    logDebug "uninstalled()"
    getChildDevices()?.each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

def refresh() {
    logDebug "refresh()"
    ensureDebugWatchdog()
    Map currentBindings = state.childFixtures ?: [:]
    currentBindings.each { dni, fixtureName ->
        def child = getChildDevice(dni as String)
        if (child) {
            Map response = synthesizeStatusResponse(dni as String, fixtureName as String)
            child.update(response, null)
        }
    }
}

// ---------------------------------------------------------------------------
// Pre-flight: detect real parent conflict
// ---------------------------------------------------------------------------

// Scheduled by installed() and updated() — deferred so device is fully
// initialized before we try to read child devices or send events.
void runPreFlight() {
    if (detectRealParent()) {
        logWarn "[DEV TOOL] Real 'VeSync Integration' parent app detected on this hub. " +
                "Virtual children use the 'VirtualVeSync-' DNI prefix and don't talk to " +
                "the VeSync cloud, so coexistence is safe — but be careful not to confuse " +
                "a virtual child with the real device of the same model during testing. " +
                "Use distinct child labels and watch for the [DEV TOOL] log prefix."
    } else {
        logInfo "[DEV TOOL] Pre-flight clear — no real parent detected."
    }
    sendEvent(name: "fixtureMode", value: "ready")
}

/**
 * Returns true if a real VeSync Integration parent device (driver-type parent, not app)
 * is present on this hub.
 *
 * Detection strategy: scan child devices of all discovered devices (not reliable for
 * a parent driver since it doesn't have a fixed parent/child relationship with the virtual
 * parent). Instead, use the DNI-prefix convention: real VeSync Integration children are
 * created with DNIs matching the device's CID from the VeSync cloud, which are typically
 * alphanumeric strings without the "VirtualVeSync-" prefix we assign.
 *
 * Simpler fallback: check state.realParentDetected flag, which tests can inject.
 * This allows specs to simulate the coexistence signal without needing real app introspection.
 *
 * In production: we check getChildDevices() for any DNI NOT matching our own
 * "VirtualVeSync-" prefix — if any such child exists, another parent manages it.
 *
 * NOTE: Hubitat drivers cannot introspect installed apps (that's an app API).
 * We use child-device DNI patterns as the practical detection signal.
 *
 * Detection result triggers a WARN only — coexistence is safe because virtual children
 * use the "VirtualVeSync-" DNI prefix and never talk to the VeSync cloud. A false-negative
 * (real parent installed but no children yet) simply means no WARN is emitted, which is fine.
 */
private boolean detectRealParent() {
    // Spec-injectable override: tests set state.realParentDetected = true to simulate conflict
    if (state.realParentDetected == true) return true

    // Production heuristic: any child device present with a non-virtual DNI
    // means another parent (likely the real VeSyncIntegration) is managing it.
    def allChildren = getChildDevices() ?: []
    return allChildren.any { child ->
        String dni = child?.deviceNetworkId ?: ""
        // Our own children always have the "VirtualVeSync-" prefix
        !dni.startsWith("VirtualVeSync-")
    }
}

// ---------------------------------------------------------------------------
// spawnFromFixture
// ---------------------------------------------------------------------------

def spawnFromFixture(String fixtureName, String childLabel) {
    logDebug "spawnFromFixture(${fixtureName}, ${childLabel})"
    ensureDebugWatchdog()

    // Coexistence check: WARN if real parent detected, but always proceed.
    // Virtual children use the "VirtualVeSync-" DNI prefix and never talk to the VeSync
    // cloud, so running both parents on the same hub is safe. The WARN is just a reminder
    // to use distinct labels to avoid mental-model confusion during testing.
    if (detectRealParent()) {
        logWarn "[DEV TOOL] Real 'VeSync Integration' parent app detected on this hub. " +
                "Virtual children use the 'VirtualVeSync-' DNI prefix and don't talk to " +
                "the VeSync cloud, so coexistence is safe — but be careful not to confuse " +
                "a virtual child with the real device of the same model during testing. " +
                "Use distinct child labels and watch for the [DEV TOOL] log prefix."
    }

    if (!FIXTURE_TO_DRIVER.containsKey(fixtureName)) {
        logError "[DEV TOOL] Unknown fixture '${fixtureName}'. Known fixtures: ${FIXTURE_TO_DRIVER.keySet().sort()}"
        return
    }

    String labelSafe = (childLabel ?: fixtureName).replaceAll(/\s+/, '-')
    String virtualDni = "VirtualVeSync-${fixtureName}-${labelSafe}"
    String driverName = FIXTURE_TO_DRIVER[fixtureName]
    String deviceType = FIXTURE_TO_DEVICETYPE[fixtureName]
    String configModule = FIXTURE_TO_CONFIGMODULE[fixtureName]


    sendEvent(name: "fixtureMode", value: "spawning")

    def child
    try {
        child = addChildDevice("NiklasGustafsson", driverName, virtualDni,
                               [label: childLabel ?: fixtureName, isComponent: false])
        logDebug "[DEV TOOL] Successfully spawned '${driverName}'"
    } catch (Exception e) {
        logError "[DEV TOOL] addChildDevice failed for '${driverName}': ${e.message}. " +
                 "Ensure the driver '${driverName}' is installed on this hub."
        sendEvent(name: "fixtureMode", value: "ready")
        return
    }

    if (!child) {
        logError "[DEV TOOL] addChildDevice returned null for '${driverName}' — " +
                 "driver may not be installed."
        sendEvent(name: "fixtureMode", value: "ready")
        return
    }

    child.updateDataValue("deviceType", deviceType)
    child.updateDataValue("configModule", configModule)
    // cid and configModule in the data values — needed if child calls parent methods
    // that reference these (e.g. a self-fetch via update())
    child.updateDataValue("cid", virtualDni)

    // BP17 read->mutate->reassign pattern for state Maps
    Map currentBindings = new HashMap(state.childFixtures ?: [:])
    currentBindings[virtualDni] = fixtureName
    state.childFixtures = currentBindings

    Map currentSnapshots = new HashMap(state.fixtureSnapshots ?: [:])
    currentSnapshots[virtualDni] = canonicalDefaultState(fixtureName)
    state.fixtureSnapshots = currentSnapshots

    logInfo "[DEV TOOL] Spawned child ${virtualDni} bound to fixture ${fixtureName}"
    sendEvent(name: "lastFixtureLoaded", value: fixtureName)
    sendEvent(name: "fixtureMode", value: "ready")

    // Seed initial state: deliver status response to populate child attributes
    Map initialResponse = synthesizeStatusResponse(virtualDni, fixtureName)
    child.update(initialResponse, null)
}

// ---------------------------------------------------------------------------
// resetAllChildren
// ---------------------------------------------------------------------------

def resetAllChildren() {
    logDebug "resetAllChildren()"
    getChildDevices()?.each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
    state.childFixtures  = [:]
    state.fixtureSnapshots = [:]
    sendEvent(name: "fixtureMode", value: "ready")
    logInfo "[DEV TOOL] All virtual children removed."
}

// ---------------------------------------------------------------------------
// stepFixtureResponse (manual op-step for interactive testing)
// ---------------------------------------------------------------------------

def stepFixtureResponse(String childDni, String opName) {
    logDebug "stepFixtureResponse(${childDni}, ${opName})"
    ensureDebugWatchdog()
    runIn(1, "deliverFixtureResponse", [data: [dni: childDni, fixtureName: state.childFixtures?.get(childDni)]])
}

// ---------------------------------------------------------------------------
// sendBypassRequest — virtual cloud routing
//
// Children call parent.sendBypassRequest(device, payload, closure) to send
// commands. This virtual implementation:
//   1. Validates payload data keys against the fixture's canonical shape.
//   2. Updates the per-DNI canonical state snapshot from the request.
//   3. Schedules an async response delivery (mirrors real cloud latency).
// ---------------------------------------------------------------------------

def Boolean sendBypassRequest(equipment, payload, Closure closure) {
    if (!equipment) {
        logError "[DEV TOOL] sendBypassRequest called with null equipment"
        return false
    }

    Map payloadMap = payload as Map

    String dni = equipment?.deviceNetworkId ?: ""
    Map currentBindings = state.childFixtures ?: [:]
    String fixtureName = currentBindings[dni] as String

    if (!fixtureName) {
        logError "[DEV TOOL] sendBypassRequest from unbound child ${dni} — no fixture mapping. " +
                 "Spawn this child via spawnFromFixture() first."
        return false
    }

    String method = payloadMap?.method ?: payloadMap?.get("method") ?: "?"
    sendEvent(name: "lastSendBypassMethod", value: method)
    sendEvent(name: "lastFixtureLoaded",    value: fixtureName)

    if (settings?.verboseDebug) {
        logDebug "[DEV TOOL] sendBypassRequest method=${method} data=${payloadMap?.data}"
    }

    // Find canonical fixture op for this method
    Map fixtureOp = findFixtureOpByMethod(fixtureName, method)
    if (fixtureOp == null) {
        logWarn "[DEV TOOL] No fixture op for method '${method}' in ${fixtureName} — " +
                "payload not validated, skipping response."
        return true
    }

    // Validate payload data keys against fixture canonical shape
    Set ourKeys     = ((payloadMap?.data as Map)?.keySet() ?: []) as Set
    Set fixtureKeys = (fixtureOp.dataKeys as Set) ?: ([] as Set)
    if (ourKeys != fixtureKeys) {
        logError "[DEV TOOL] Payload data keys mismatch for method '${method}': " +
                 "ours=${ourKeys.sort()}, pyvesync=${fixtureKeys.sort()}"
    } else {
        logDebug "[DEV TOOL] Payload validated: ${method} keys=${ourKeys.sort()}"
    }

    // Update canonical state snapshot from this request
    updateCanonicalState(dni, method, payloadMap?.data as Map)

    // Deliver canned "success" response to the closure immediately.
    // The Core line's sendBypassRequest closure receives the raw HttpResponse.
    // We simulate an HTTP 200 OK with the bypassV2 success envelope.
    //
    // Use a map instead of Expando (Hubitat sandbox restriction).
    // Maps support the same property access syntax as our closure expects.
    def vr = [
        status: 200,
        data: [
            code: 0,
            msg: "request success",
            result: [
                code: 0,
                result: [:],
                traceId: "virtual-trace"
            ],
            traceId: "virtual-trace"
        ]
    ]
    try {
        closure(vr)
    } catch (Exception e) {
        logError "[DEV TOOL] Closure threw exception for method '${method}': ${e.message}"
    }

    // Async status refresh: deliver synthesized status response after 1s
    // so that any immediately-following attribute reads see updated values.
    runIn(1, "deliverFixtureResponse", [data: [dni: dni, fixtureName: fixtureName]])
    return true
}

// ---------------------------------------------------------------------------
// Async response delivery
// ---------------------------------------------------------------------------

void deliverFixtureResponse(Map data) {
    String dni = data?.dni as String
    String fixtureName = data?.fixtureName as String
    if (!dni || !fixtureName) return

    def child = getChildDevice(dni)
    if (!child) {
        logWarn "[DEV TOOL] deliverFixtureResponse: child ${dni} not found (removed?)"
        return
    }

    Map response = synthesizeStatusResponse(dni, fixtureName)
    logDebug "[DEV TOOL] deliverFixtureResponse: pushing status to ${dni}"
    child.update(response, null)
}

// ---------------------------------------------------------------------------
// Canonical state management
// ---------------------------------------------------------------------------

/**
 * Return a fresh deep copy of the default state for the named fixture.
 * Mutable — callers may mutate without affecting the immutable family constant.
 *
 * For fixtures whose family default state has nested Maps (extension, configuration,
 * dryingMode, sleepPreference, autoPreference), we deep-copy those sub-maps so
 * that mutators (e.g. addTimer writing to snap.extension.timer_remain) don't hit
 * immutable nested references.
 *
 * Per-fixture overrides are applied after the family copy to capture fields that
 * genuinely differ from the family seed (e.g. Classic200S uses indicator_light_switch
 * instead of display; OasisMist 1000S lacks configuration sub-object).
 */
private Map canonicalDefaultState(String fixtureName) {
    String family = FIXTURE_TO_FAMILY[fixtureName]
    if (!family) {
        logWarn "[DEV TOOL] canonicalDefaultState: unknown fixture '${fixtureName}', returning empty map"
        return [:]
    }

    Map copy
    switch (family) {

        case "v1_purifier":
            copy = new HashMap(V1_PURIFIER_DEFAULT_STATE)
            // Deep-copy nested Maps (immutable in the @Field constant)
            copy.extension = new HashMap(V1_PURIFIER_DEFAULT_STATE.extension ?: [:])
            copy.configuration = new HashMap(V1_PURIFIER_DEFAULT_STATE.configuration ?: [:])
            copy.configuration.auto_preference = new HashMap(
                (V1_PURIFIER_DEFAULT_STATE.configuration?.auto_preference) ?: [:])
            // Core200S override: no AQ fields (the family seed has them from Core300S)
            if (fixtureName == "Core200S") {
                copy.remove("air_quality")
                copy.remove("air_quality_value")
                copy.remove("configuration")
                // Restore the simpler Core200S extension shape
                copy.extension = [timer_remain: 0]
                copy.device_error_code = 0
            }
            break

        case "v2_purifier":
            copy = new HashMap(V2_PURIFIER_DEFAULT_STATE)
            // autoPreference is a nested Map in V2 purifiers — deep-copy
            copy.autoPreference = new HashMap(V2_PURIFIER_DEFAULT_STATE.autoPreference ?: [:])
            // EL551S override: add fanRotateAngle (EverestAir VENT_ANGLE feature)
            if (fixtureName == "EL551S") {
                copy.fanRotateAngle = 90
            }
            // LAP-B851S-WUS override: Sprout Air adds full AQ suite + nightlight sub-object
            if (fixtureName == "LAP-B851S-WUS") {
                copy.PM1 = 3
                copy.PM10 = 8
                copy.AQI = 95
                copy.humidity = 48
                copy.temperature = 720
                copy.VOC = 10
                copy.CO2 = 450
                copy.nightlight = [nightLightSwitch: false, brightness: 0]
                // LAP-B851S-WUS inherits filterLifePercent: 80 from v2_purifier seed unchanged.
            }
            break

        case "v1_humidifier":
            copy = new HashMap(V1_HUMIDIFIER_DEFAULT_STATE)
            // Deep-copy the configuration sub-map
            copy.configuration = new HashMap(V1_HUMIDIFIER_DEFAULT_STATE.configuration ?: [:])
            // Classic200S override: uses indicator_light_switch instead of display
            if (fixtureName == "Classic200S") {
                copy.remove("display")
                copy.remove("night_light_brightness")
                copy.indicator_light_switch = true
                copy.configuration = [
                    auto_target_humidity  : 50,
                    indicator_light_switch: true,
                    automatic_stop        : false
                ]
                // Classic200S has no sleep mode; mist 1-9; default mist=3
                copy.mist_virtual_level = 3
                copy.mist_level = 3
            }
            // Dual200S override: mist 1-2 only; no warm mist; default mist=1
            if (fixtureName == "Dual200S") {
                copy.mist_virtual_level = 1
                copy.mist_level = 1
                copy.humidity = 35
            }
            // LUH-O451S-WUS override: warm mist populated; no night_light_brightness
            if (fixtureName == "LUH-O451S-WUS") {
                copy.warm_enabled = true
                copy.warm_level = 2
                copy.remove("night_light_brightness")
            }
            // LUH-A602S-WUS override: warm mist populated; no night_light_brightness; no humidity_high
            if (fixtureName == "LUH-A602S-WUS") {
                copy.warm_enabled = true
                copy.warm_level = 2
                copy.humidity = 52
                copy.remove("night_light_brightness")
                copy.remove("humidity_high")
                copy.configuration = [
                    auto_target_humidity: 55,
                    display             : true,
                    automatic_stop      : false
                ]
            }
            break

        case "v2_humidifier":
            copy = new HashMap(V2_HUMIDIFIER_DEFAULT_STATE)
            // Deep-copy dryingMode sub-map (present in LEH-S601S seed)
            copy.dryingMode = new HashMap(V2_HUMIDIFIER_DEFAULT_STATE.dryingMode ?: [:])
            // LEH-B381S override: Sprout Humidifier (mist 1-2; nightlight; dual filter)
            if (fixtureName == "LEH-B381S") {
                copy.mistLevel = 2
                copy.virtualLevel = 2
                copy.humidity = 45
                copy.targetHumidity = 55
                copy.hepaFilterLifePercent = 78
                copy.temperature = 720
                copy.nightLight = [nightLightSwitch: 0, brightness: 0, colorTemperature: 3500]
                copy.remove("dryingMode")    // Sprout Humidifier dryingMode is absent in canonical (optional)
                copy.remove("temperature")   // re-add from override
                copy.temperature = 720
            }
            // LUH-A603S-WUS override: LV600S Hub Connect (warmLevel/warmPower; no dryingMode; workMode 'humidity' for auto)
            if (fixtureName == "LUH-A603S-WUS") {
                copy.humidity = 55
                copy.targetHumidity = 60
                copy.virtualLevel = 5
                copy.mistLevel = 5
                copy.warmPower = true
                copy.warmLevel = 2
                copy.scheduleCount = 0
                copy.timerRemain = 0
                copy.remove("dryingMode")
                copy.remove("autoPreference")
                copy.remove("childLockSwitch")
                copy.remove("filterLifePercent")
                copy.remove("temperature")
            }
            // LUH-M101S-WUS override: OasisMist 1000S (no dryingMode; no filterLifePercent; no temperature at top level)
            if (fixtureName == "LUH-M101S-WUS") {
                copy.humidity = 42
                copy.targetHumidity = 55
                copy.mistLevel = 5
                copy.virtualLevel = 5
                copy.remove("dryingMode")
                copy.remove("filterLifePercent")
                copy.remove("temperature")
                copy.remove("childLockSwitch")
                copy.remove("autoPreference")
            }
            break

        case "fan":
            copy = new HashMap(FAN_DEFAULT_STATE)
            // Deep-copy sleepPreference sub-map
            copy.sleepPreference = new HashMap(FAN_DEFAULT_STATE.sleepPreference ?: [:])
            // LPF-R423S (Pedestal Fan) override: replace Tower Fan fields with Pedestal Fan fields
            if (fixtureName == "LPF-R423S") {
                copy.remove("oscillationSwitch")
                copy.remove("oscillationState")
                copy.remove("displayingType")
                copy.remove("sleepPreference")
                copy.horizontalOscillationState = 1
                copy.verticalOscillationState = 0
                copy.childLock = 0
                copy.temperature = 750
                copy.oscillationCoordinate = [yaw: 45, pitch: 10]
                copy.oscillationRange = [left: 10, right: 80, top: 20, bottom: 70]
                copy.sleepPreference = [
                    sleepPreferenceType: "default",
                    oscillationState   : 1,
                    fallAsleepRemain   : 0,
                    initFanSpeedLevel  : 0
                ]
            }
            break

        default:
            logWarn "[DEV TOOL] canonicalDefaultState: unrecognized family '${family}' for fixture '${fixtureName}'"
            return [:]
    }

    return copy
}

/**
 * Apply the effect of an outbound command to the per-DNI state snapshot.
 * This keeps the synthesized status responses consistent with what the child
 * thinks the device's current state is.
 */
private void updateCanonicalState(String dni, String method, Map data) {
    Map currentSnapshots = state.fixtureSnapshots ?: [:]
    Map snap = new HashMap(currentSnapshots[dni] as Map ?: [:])

    // Look up fixture family for this DNI
    Map currentBindings = state.childFixtures ?: [:]
    String fixtureName = currentBindings[dni] as String
    String family = fixtureName ? FIXTURE_TO_FAMILY[fixtureName] : null

    if (!family) {
        logWarn "[DEV TOOL] updateCanonicalState: no family for DNI '${dni}'"
        return
    }

    Map familyMutators = STATE_MUTATORS[family] as Map
    Closure mutator = familyMutators ? familyMutators[method] as Closure : null
    if (mutator) {
        mutator(snap, data ?: [:])
    }

    // BP17 read->mutate->reassign
    Map updatedSnapshots = new HashMap(state.fixtureSnapshots ?: [:])
    updatedSnapshots[dni] = snap
    state.fixtureSnapshots = updatedSnapshots
}

/**
 * Build a synthetic getPurifierStatus / getHumidifierStatus response for
 * the given DNI, using the current canonical state snapshot.
 *
 * Shape matches what child drivers' update(status, nightLight) expects:
 *   status.result.* for Core line (v1 families)
 *   status.* at top level for V2 families (children peel the envelope themselves)
 *
 * The parent calls child.update(response, null) where response is the
 * resp.data.result from the real bypassV2 call. For Core-line children that is
 * { code:0, result: { <device fields> } }. For V2-line children that is the
 * device fields directly (after peel). Since the virtual parent mirrors the
 * real parent, we return the same shape the real parent would deliver.
 *
 * For v1 families: wrap in { code:0, result: <snap> } (matches real parent).
 * For v2 families: wrap in { code:0, result: { code:0, result: <snap> } }
 *   so children's peel-loop finds the device fields after exactly one peel.
 *   (The real parent passes resp.data.result which already has the outer
 *   bypassV2 layer stripped, so we ship the inner double-wrap here.)
 */
Map synthesizeStatusResponse(String dni, String fixtureName) {
    Map currentSnapshots = state.fixtureSnapshots ?: [:]
    Map snap = currentSnapshots[dni] as Map ?: canonicalDefaultState(fixtureName)
    String family = FIXTURE_TO_FAMILY[fixtureName] ?: "v1_purifier"

    // V2 families use double-wrapped responses; v1 families use single-wrapped.
    // The real parent strips the outermost bypassV2 layer (code/result/traceId)
    // and passes resp.data.result to child.update(). So we include one layer of
    // wrapping here (the inner result envelope that the child's peel-loop sees).
    if (family == "v2_purifier" || family == "v2_humidifier") {
        return [
            code  : 0,
            result: [
                code   : 0,
                result : new HashMap(snap),
                traceId: "virtual-trace"
            ],
            traceId: "virtual-trace"
        ]
    } else {
        // v1_purifier, v1_humidifier, fan — single-wrapped
        return [
            code  : 0,
            result: new HashMap(snap)
        ]
    }
}

/**
 * Find the fixture op entry for the given method name.
 * Returns null if no matching op is found.
 */
private Map findFixtureOpByMethod(String fixtureName, String method) {
    List ops = FIXTURE_OPS[fixtureName] as List
    if (!ops) return null
    return ops.find { it.methodName == method } as Map
}

// ---------------------------------------------------------------------------
// Lookup helpers (callable from tests and from spawnFromFixture)
// ---------------------------------------------------------------------------

String getDriverNameFor(String fixtureName)    { FIXTURE_TO_DRIVER[fixtureName] }
String getDeviceTypeFor(String fixtureName)    { FIXTURE_TO_DEVICETYPE[fixtureName] }
String getConfigModuleFor(String fixtureName)  { FIXTURE_TO_CONFIGMODULE[fixtureName] }
String getFamilyFor(String fixtureName)        { FIXTURE_TO_FAMILY[fixtureName] }

// ---------------------------------------------------------------------------
// Logging helpers
// ---------------------------------------------------------------------------
// logDebug, logError, logWarn, logInfo, logDebugOff, ensureDebugWatchdog
// are provided by #include level99.LevoitChildBase (LevoitChildBaseLib.groovy).
// Note: Virtual parent logWarn/logError had no pref gate (always surfaces) — the
// lib versions are behaviorally identical (log.warn / log.error directly).
