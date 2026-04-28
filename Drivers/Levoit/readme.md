## VeSync: Levoit Air Purifier, Humidifier, and Fan Drivers (Community Fork)

Hubitat drivers for Levoit air purifiers, humidifiers, and fans, controlled via the VeSync cloud API.

This is a **community fork** of [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat). It preserves the original Core 200S/300S/400S/600S drivers and adds:

- **Levoit Vital 200S Air Purifier** (LAP-V201S) — full controls, AQ/PM2.5 sensors, timer
- **Levoit Vital 100S Air Purifier** (LAP-V102S) — same V2 platform as Vital 200S, no light-detection feature *(v2.1)*
- **Levoit Superior 6000S Humidifier** (LEH-S601S) — mist control, drying mode, auto-stop, water-pump cleaning, ambient temp sensor
- **Levoit Classic 300S Humidifier** (LUH-A601S) — mist 1-9, target humidity, 3-step night light *(v2.1)*
- **Levoit OasisMist 450S Smart Humidifier** (LUH-O451S, LUH-O601S) — mist 1-9 + warm mist 0-3, auto-stop *(v2.1)*. EU variant `LUH-O451S-WEU` adds RGB color nightlight via standard Hubitat ColorControl *(v2.2)*
- **Levoit LV600S Humidifier** (LUH-A602S, all 6 regional variants) — mist 1-9, warm mist 0-3, target humidity 30-80, auto-stop *(v2.2)*
- **Levoit Dual 200S Humidifier** (LUH-D301S, all 5 regional variants) — mist 1-2 (2-level hardware), target humidity 30-80, auto/manual modes only *(v2.2)*
- **Levoit Tower Fan** (LTF-F422S) — 12-speed fan, oscillation, mute, ambient temperature *(v2.1)*
- **Levoit Pedestal Fan** (LPF-R432S) — 12-speed fan, 2-axis oscillation with range control, ambient temperature *(v2.1)*
- **EU region support** — `VeSync API region` parent preference (US/EU) routes API calls to the appropriate VeSync cloud host *(v2.2 preview)*
- **Parent driver fix**: humidifier devices were silently failing every poll because the parent always sent `getPurifierStatus`. The parent now branches by device type.
- **Connection-pool retry logic** for transient HTTP failures.

Installing the integration driver and configuring it with your VeSync account credentials will automatically discover existing equipment, as long as it has been set up in the VeSync app.

Equipment found will be added as child devices under the VeSync Integration parent device, named to match the labels in the VeSync app. Pressing 'Resync Equipment' will discover newly added devices.

The Core 200S installs as two child devices (purifier + night light). All other models install as a single child device. Devices show up as fans, switches, dimmers, humidity sensors, or temperature sensors depending on capabilities. There's also an `info` attribute that's useful for dashboard tiles.

## Installation

### Via Hubitat Package Manager (recommended)

1. In Hubitat: Apps → Hubitat Package Manager → Install → From a URL
2. Manifest URL: `https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json`
3. Install only the drivers for the device types you own (the integration driver is required).

### Manual install

Copy the relevant `.groovy` files from the `Drivers/Levoit/` directory into Hubitat's "Drivers Code" page. Use the **Raw** view of each file in GitHub.

| File | Purpose |
| --- | --- |
| `VeSyncIntegration.groovy` | **Required.** Parent driver — represents the VeSync account, polls all devices. |
| `LevoitCore200S.groovy` | Levoit Core 200S air purifier |
| `LevoitCore200S Light.groovy` | Night light child for Core 200S |
| `LevoitCore300S.groovy` | Levoit Core 300S air purifier |
| `LevoitCore400S.groovy` | Levoit Core 400S air purifier |
| `LevoitCore600S.groovy` | Levoit Core 600S air purifier |
| `LevoitVital100S.groovy` | Levoit Vital 100S air purifier *(v2.1 preview)* |
| `LevoitVital200S.groovy` | Levoit Vital 200S / 200S-P air purifier |
| `LevoitClassic300S.groovy` | Levoit Classic 300S humidifier *(v2.1 preview)* |
| `LevoitSuperior6000S.groovy` | Levoit Superior 6000S evaporative humidifier |
| `LevoitOasisMist450S.groovy` | Levoit OasisMist 450S Smart Humidifier (US) *(v2.1 preview)* |
| `LevoitLV600S.groovy` | Levoit LV600S Humidifier *(v2.2 preview)* |
| `LevoitDual200S.groovy` | Levoit Dual 200S Humidifier *(v2.2 preview)* |
| `LevoitClassic200S.groovy` | Levoit Classic 200S Humidifier *(v2.3 preview)* |
| `LevoitLV600SHubConnect.groovy` | Levoit LV600S Hub Connect Humidifier (LUH-A603S) *(v2.3 preview)* |
| `LevoitTowerFan.groovy` | Levoit Tower Fan *(v2.1 preview)* |
| `LevoitPedestalFan.groovy` | Levoit Pedestal Fan *(v2.1 preview)* |
| `LevoitGeneric.groovy` | **Fall-through diagnostic driver** — any unrecognized Levoit model code. Best-effort power control + `captureDiagnostics()` for filing new-device-support requests. |

### Setup

After installing the drivers:

1. Add a virtual device with type **VeSync Integration**.
2. Configure email + password for your VeSync account, plus a refresh (polling) interval.
3. Hit **Save Preferences**, then press **Resync Equipment**. Child devices come online within a few seconds.

The refresh interval (default 30 s) determines how often the drivers poll device status. For mostly-automated use, 60–120 s is plenty and reduces total API load.

### VeSync Integration parent preferences

| Preference | Default | Description |
| --- | --- | --- |
| Email Address | — | VeSync account email |
| Password | — | VeSync account password |
| Refresh Interval | 30 s | How often to poll device status |
| VeSync API region | US | `US` → `smartapi.vesync.com`; `EU` → `smartapi.vesync.eu`. EU is **preview** — no EU hardware live-verified. Changing region clears stored auth and forces re-login. |
| Enable descriptive logging | true | INFO-level user events (power, mode, etc.) |
| Enable debug logging | false | Internal trace + raw API response dump. Auto-disables after 30 min. |
| Verbose API response logging | false | Full request/response body on every call. Use only when triaging API drift. |

## Migration from legacy hand-installed drivers

If you previously hand-pasted "Levoit Vital200S/V201S Air Purifier (...)" or "Levoit 600S Humidifier" drivers into your hub, the new drivers in this fork have different metadata names. To migrate:

1. Install the fork drivers (HPM or manual).
2. For each existing device:
   - Open device page → **Type** dropdown → pick the new driver name (e.g. **Levoit Vital 200S Air Purifier** or **Levoit Superior 6000S Humidifier**).
   - **Save Device**. Hit **Refresh** to repopulate state.
3. Once all devices are on the new drivers, the old hand-installed drivers can be deleted from "Drivers Code".

Existing rules / dashboards continue to work — same commands, same attributes (plus several new ones).

## Events / attributes

The parent **VeSync Integration** has one event attribute: `heartbeat` (`syncing`, `synced`, `not synced`). Use `not synced` in a rule to detect prolonged loss of contact with VeSync servers.

### Core 200S

| event | Values | Description |
| --- | --- | --- |
| filter | 0-100 | Remaining filter life (%) |
| mode | manual, sleep | Current mode |
| childLock | on, off | Front-panel button-lock state. Called "Display Lock" in the VeSync mobile app for Core line. |
| display | on, off | Front-panel display state. |
| timerRemain | seconds | Auto-off timer remaining (0 if no timer set). |
| info | HTML | Dashboard-tile-friendly summary |
| switch | on, off | Power state |
| speed | off, low, medium, high | Fan speed |

Commands: `setDisplay`, `setSpeed`, `setMode`, `setChildLock`, `setTimer` (seconds), `cancelTimer`, `resetFilter`, `toggle`.

Note: in the VeSync mobile app, the child-lock feature is labeled "Display Lock" for Core line devices. We expose it here as `childLock` for Hubitat cross-driver consistency with the Vital line drivers.

**Timer units:** `setTimer` accepts seconds for Core line (matches the Levoit V1 API). The Vital line's `setTimer` accepts minutes — be aware if you have both device families.

### Core 300S

| event | Values | Description |
| --- | --- | --- |
| filter | 0-100 | Filter life (%) |
| mode | manual, sleep, auto | Current mode |
| auto_mode | default, quiet, efficient | Auto-mode preference |
| childLock | on, off | Front-panel button-lock state. Called "Display Lock" in the VeSync mobile app for Core line. |
| display | on, off | Front-panel display state. |
| timerRemain | seconds | Auto-off timer remaining (0 if no timer set). |
| pm25 | µg/m³ | Real-time PM2.5 reading. |
| airQualityIndex | 1-4 | Levoit's categorical air-quality (1=excellent, 4=very bad). Distinct from the driver-computed US AQI (`aqi` attribute). |
| aqi | 0-500 | US-formula AQI |
| aqiDanger | string | Risk level for tile display |
| aqiColor | hex | Color for HTML |
| info | HTML | Tile summary |
| switch | on, off | Power |
| speed | off, sleep, low, medium, high | Fan speed |

Commands: `setDisplay`, `setSpeed`, `setMode`, `setAutoMode`, `setChildLock`, `setTimer` (seconds), `cancelTimer`, `resetFilter`, `toggle`.

Note: in the VeSync mobile app, the child-lock feature is labeled "Display Lock" for Core line devices. We expose it here as `childLock` for Hubitat cross-driver consistency with the Vital line drivers.

**Timer units:** `setTimer` accepts seconds for Core line (matches the Levoit V1 API). The Vital line's `setTimer` accepts minutes — be aware if you have both device families.

### Core 400S

Same as Core 300S except `speed` adds `max`.

Note: in the VeSync mobile app, the child-lock feature is labeled "Display Lock" for Core line devices. We expose it here as `childLock` for Hubitat cross-driver consistency with the Vital line drivers.

**Timer units:** `setTimer` accepts seconds for Core line (matches the Levoit V1 API). The Vital line's `setTimer` accepts minutes — be aware if you have both device families.

**Model codes:** `Core400S`, `LAP-C401S-WJP`, `LAP-C401S-WUSR`, `LAP-C401S-WAAA`, `LAP-C401S-KUSR` (PlasmaPro 400S-P black variant, v2.3). All use the same VeSyncAirBypass API class.

### Core 600S

Same as Core 400S; `auto_mode` adds `eco`.

Note: in the VeSync mobile app, the child-lock feature is labeled "Display Lock" for Core line devices. We expose it here as `childLock` for Hubitat cross-driver consistency with the Vital line drivers.

**Timer units:** `setTimer` accepts seconds for Core line (matches the Levoit V1 API). The Vital line's `setTimer` accepts minutes — be aware if you have both device families.

### Vital 200S (LAP-V201S-*, LAP-V201-AUSR¹)

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| speed | off, sleep, low, medium, high, max | Fan speed (off when device is off) |
| mode | manual, auto, sleep, pet | Current mode |
| petMode | on, off | True if mode is "pet" |
| filter | 0-100 | Filter life (%) |
| pm25 | µg/m³ | Real-time PM2.5 reading |
| airQualityIndex | 1-4 | Levoit-internal AQ index |
| airQuality | good, moderate, poor, very poor, unknown | Categorical AQ |
| autoPreference | default, efficient, quiet | Auto-mode preference |
| roomSize | sq ft | User-configured room size for auto mode |
| lightDetection | on, off | Whether light-detection is enabled |
| lightDetected | yes, no | Whether ambient light is detected |
| childLock | on, off | Child-lock state |
| display | on, off | Front-panel display state |
| errorCode | int | Device error code (0 = healthy) |
| timerRemain | seconds | Auto-off timer remaining |
| info | HTML | Tile summary |

Commands: `setSpeed`, `setMode`, `setPetMode`, `setAutoPreference`, `setRoomSize`, `setLightDetection`, `setChildLock`, `setDisplay`, `setTimer` (minutes), `cancelTimer`, `resetFilter`, `toggle`.

### Vital 100S (LAP-V102S) *— v2.1 preview*

Same V2 platform as Vital 200S. Differs in one capability: **no light-detection** (the field is present in the API response but the V100S has no LIGHT_DETECT feature flag in pyvesync; reads are intentionally ignored).

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| speed | off, sleep, low, medium, high, max | Fan speed (off when device is off) |
| mode | manual, auto, sleep, pet | Current mode |
| petMode | on, off | True if mode is "pet" |
| filter | 0-100 | Filter life (%) |
| pm25 | µg/m³ | Real-time PM2.5 reading |
| airQualityIndex | 1-4 | Levoit-internal AQ index |
| autoPreference | default, efficient, quiet | Auto-mode preference |
| roomSize | sq ft | User-configured room size for auto mode |
| childLock | on, off | Child-lock state |
| display | on, off | Front-panel display state |
| errorCode | int | Device error code (0 = healthy) |
| timerRemain | seconds | Auto-off timer remaining |
| info | HTML | Tile summary |

Commands: `setSpeed`, `setMode`, `setPetMode`, `setAutoPreference`, `setRoomSize`, `setChildLock`, `setDisplay`, `setTimer` (minutes), `cancelTimer`, `resetFilter`, `toggle`.

### Superior 6000S Humidifier (LEH-S601S)

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | manual, auto, sleep | Current mode (auto is "autoPro" internally) |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| temperature | °F or °C | Ambient temp from onboard sensor |
| mistLevel | 1-9 | Actual reported mist level |
| virtualLevel | 1-9 | Set/requested mist level |
| level | 0-100 | SwitchLevel mapping (mistLevel mapped to %) |
| water | ok, empty, removed | Water-tank state |
| display | on, off | Front-panel display |
| childLock | on, off | Child-lock state |
| autoStopConfig | on, off | Auto-stop-when-target-reached config |
| autoStopActive | yes, no | Whether auto-stop is currently active |
| dryingMode | active, complete, idle, off | Wick auto-drying state |
| dryingTimeRemain | seconds | Time remaining in drying cycle |
| pumpCleanStatus | cleaning, idle | Water pump self-clean cycle |
| pumpCleanRemain | seconds | Time remaining in clean cycle |
| wickFilterLife | 0-100 | Wick filter life (%) |
| timerRemain | seconds | Auto-off timer remaining |
| info | HTML | Tile summary |

Commands: `setMode`, `setMistLevel`, `setTargetHumidity`, `setDisplay`, `setChildLock`, `setAutoStop`, `setDryingMode`, `toggle`.

### Classic 300S Humidifier (LUH-A601S) *— v2.1 preview*

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, sleep, manual | Current mode |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| mistLevel | 0-9 | Reported mist level (0 = inactive) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| nightLight | off, dim, bright | Discrete 3-step night light (HA finding #9) |
| nightLightBrightness | 0, 50, 100 | Raw API value (0 = off, 50 = dim, 100 = bright) |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop-when-target-reached config |
| autoStopReached | yes, no | Whether auto-stop is currently active |
| humidityHigh | yes, no | Whether humidity exceeds the high-alarm threshold |
| info | HTML | Tile summary |

Commands: `setMode`, `setMistLevel` (1-9), `setHumidity` (30-80), `setNightLight` (off/dim/bright), `setDisplay`, `setAutoStop`, `toggle`.

### OasisMist 450S Smart Humidifier (LUH-O451S-*, LUH-O601S-*) *— v2.1/v2.2 preview*

Builds on the Classic 300S API platform. Adds warm-mist (3 levels). Differs from Classic 300S: **no night-light hardware** on US/KUS variants (no `setNightLight` command), and `setMode` excludes `humidity` as a user-settable mode (device firmware universally rejects that mode per pyvesync issue #295; v2.2 transparently handles firmware variants where `humidity` is the internal auto-mode payload). Target humidity range is 40-80 (firmware floor of 40, per pyvesync issue #296).

Covers 5 model codes: LUH-O451S-WUS, LUH-O451S-WUSR, LUH-O451S-WEU (EU 4.5L variant, added v2.2), LUH-O601S-WUS, LUH-O601S-KUS.

**LUH-O451S-WEU (EU variant) only:** RGB nightlight support added in v2.2. Hubitat `ColorControl` capability is declared for all variants (static metadata limitation); commands gate at runtime on the detected model code. Non-WEU users see the ColorControl commands on their device page; they no-op with an INFO log ("RGB nightlight not supported on this hardware variant"). Based on [pyvesync PR #502](https://github.com/webdjoe/pyvesync/pull/502) (OPEN/CHANGES_REQUESTED, stalled 2026-01) — **ships as preview** pending EU community hardware validation.

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, sleep, manual | Current mode (no `humidity` mode — see CROSS-CHECK in source) |
| humidity | % | Current ambient humidity |
| targetHumidity | 40-80 | Auto-mode target humidity (firmware-clamped) |
| mistLevel | 0-9 | Reported mist level (0 = inactive) |
| warmMistLevel | 0-3 | Warm-mist level (0 = warm-mist off) |
| warmMistEnabled | on, off | Boolean derived from warmMistLevel (on if 1-3, off if 0) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop-when-target-reached config |
| autoStopReached | yes, no | Whether auto-stop is currently active |
| humidityHigh | yes, no | Whether humidity exceeds the high-alarm threshold |
| info | HTML | Tile summary |
| nightlightSwitch | on, off | RGB nightlight power state (**LUH-O451S-WEU only**) |
| nightlightBrightness | 40-100 | RGB nightlight brightness (**LUH-O451S-WEU only**; firmware floor at 40) |
| hue | 0-100 | ColorControl hue (0-100 Hubitat scale) (**LUH-O451S-WEU only**) |
| saturation | 0-100 | ColorControl saturation (**LUH-O451S-WEU only**) |
| colorMode | RGB | Color mode (**LUH-O451S-WEU only**) |

Commands: `setMode`, `setMistLevel` (1-9), `setHumidity` (40-80), `setWarmMistLevel` (0-3), `setDisplay`, `setAutoStop`, `toggle`.

RGB commands (**LUH-O451S-WEU only** — no-op with INFO log on other variants): `setNightlightSwitch` (on/off), `setColor` (Hubitat ColorControl map: hue 0-100, saturation 0-100, level 0-100), `setHue` (0-100), `setSaturation` (0-100).

### LV600S Humidifier (LUH-A602S-*) *— v2.2 preview*

Same VeSyncHumid200300S API class as Classic 300S + OasisMist 450S. Adds warm-mist (0-3 levels). Humidity range 30-80 (base class default; differs from OasisMist 450S 40-80). No night-light hardware. **Important:** auto mode may report as `humidity` mode in status on some EU firmware variants — see [pyvesync PR #505](https://github.com/webdjoe/pyvesync/pull/505) and inline CROSS-CHECK in driver source.

Covers all 6 model codes: LUH-A602S-WUSR, LUH-A602S-WUS, LUH-A602S-WEUR, LUH-A602S-WEU, LUH-A602S-WJP, LUH-A602S-WUSC.

**Naming trap:** LUH-A602S (this driver) uses class VeSyncHumid200300S. LUH-A603S (NOT covered here) uses a different class VeSyncLV600S with different API conventions. Both are marketed as "LV600S" by Levoit.

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, sleep, manual | Current mode (may show `humidity` on some EU firmware in auto state — see PR #505) |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| mistLevel | 0-9 | Reported mist level (0 = inactive) |
| warmMistLevel | 0-3 | Warm-mist level (0 = warm-mist off) |
| warmMistEnabled | on, off | Boolean derived from warmMistLevel (on if 1-3, off if 0) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop-when-target-reached config |
| autoStopReached | yes, no | Whether auto-stop is currently active |
| info | HTML | Tile summary |

Commands: `setMode`, `setMistLevel` (1-9), `setHumidity` (30-80), `setWarmMistLevel` (0-3), `setDisplay`, `setAutoStop`, `toggle`. (No `setNightLight` — hardware absent.)

### Dual 200S Humidifier (LUH-D301S-*) *— v2.2 preview*

Same VeSyncHumid200300S API class as Classic 300S + LV600S. **Key differences from Classic 300S:** mist range is 1-2 only (not 1-9); supported modes are auto and manual only (no sleep per pyvesync device_map.py LUH-D301S entry); no warm mist; no nightlight command (nightlight feature flag absent — `nightLightBrightness` is a read-only passive attribute). EU SKUs (LUH-D301S-WEU, LUH-D301S-KEUR) apply the same multi-firmware auto-mode try-fallback as LV600S (pyvesync PR #505 risk).

Covers all 5 model codes: `Dual200S` (literal device type reported by some firmware), `LUH-D301S-WUSR`, `LUH-D301S-WJP`, `LUH-D301S-WEU`, `LUH-D301S-KEUR`.

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, manual | Current mode (auto/manual only; sleep not supported per device_map.py) |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| mistLevel | 0-2 | Reported mist level (0 = inactive; max 2 per hardware spec) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop-when-target-reached config |
| autoStopReached | yes, no | Whether auto-stop is currently active |
| nightLightBrightness | 0, 50, 100 | Night-light brightness from API (read-only; no setter command) |
| info | HTML | Tile summary |

Commands: `setMode` (auto/manual), `setMistLevel` (1-2), `setHumidity` (30-80), `setDisplay`, `setAutoStop`, `toggle`. (No `setNightLight` — feature flag absent; no `setWarmMistLevel` — hardware absent.)

### Classic 200S Humidifier (`Classic200S`) *— v2.3 preview*

pyvesync class: `VeSyncHumid200S` (subclass of `VeSyncHumid200300S`). The **one** meaningful difference from Classic 300S / LV600S (A602S) is the display command: Classic 200S uses `setIndicatorLightSwitch {enabled: bool, id: 0}` rather than `setDisplay {state: bool}`. All other modes, mist levels, target-humidity, and auto-stop behavior are inherited from the VeSyncHumid200300S base class and match Classic 300S conventions.

**CROSS-CHECK — Classic 200S vs Classic 300S naming trap:**
- `Classic200S` (this driver) → `VeSyncHumid200S`; device code literal `Classic200S`; display = `setIndicatorLightSwitch`; modes auto/manual only; no night-light command.
- `Classic300S` (separate `LevoitClassic300S.groovy`) → `VeSyncHumid200300S` base class; device codes `LUH-A601S-*`; display = `setDisplay`; modes auto/sleep/manual; 3-step night-light.
Do NOT add LUH-A601S codes to this driver and do NOT add `Classic200S` to the Classic 300S driver.

Modes: auto and manual only (no sleep per `device_map.py`). Mist levels 1-9.

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, manual | Current mode (auto/manual only; sleep not supported per device_map.py) |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| mistLevel | 0-9 | Reported mist level (0 = inactive) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop-when-target-reached config |
| autoStopReached | yes, no | Whether auto-stop is currently active |
| nightLightBrightness | 0, 50, 100 | Night-light brightness from API (read-only passive; no setter) |
| info | HTML | Tile summary |

Commands: `setMode` (auto/manual), `setMistLevel` (1-9), `setHumidity` (30-80), `setDisplay`, `setAutoStop`, `toggle`. (No `setNightLight` — feature flag absent per `device_map.py`; no `setWarmMistLevel` — hardware absent.)

### LV600S Hub Connect Humidifier (LUH-A603S-WUS) *— v2.3 preview*

pyvesync class: `VeSyncLV600S` — **distinct from `VeSyncHumid200300S`** used by the existing `LevoitLV600S.groovy` (A602S). Despite both being marketed as "LV600S", A603S and A602S use entirely different API conventions.

**CROSS-CHECK — A603S vs A602S naming trap:**
- `LUH-A603S-WUS` (this driver) → `VeSyncLV600S`; V2-style payload conventions (camelCase, `powerSwitch`/`switchIdx`, `workMode`, `levelIdx`/`virtualLevel`/`levelType`); auto mode wire value = `"humidity"`; no `setAutoStop` (device_map.py features=[WARM_MIST]); `setWarmMistLevel` uses API method `setLevel`.
- `LUH-A602S-*` (separate `LevoitLV600S.groovy`) → `VeSyncHumid200300S`; V1-style payload conventions (snake_case, `enabled`/`id`, `mode`, `id`/`level`/`type`); auto mode wire value = `"auto"`; has `setAutoStop`.
Do NOT add `LUH-A603S` to `LevoitLV600S.groovy` and do NOT add `LUH-A602S` codes to this driver.

Auto mode sends `workMode: "humidity"` on the wire (normalized to `"auto"` in the driver's state and events). Warm mist 0-3. No auto-stop command (feature flag absent; `autoStopSwitch`/`autoStopState` from status are exposed as passive read-only attributes).

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| mode | auto, sleep, manual | Current mode (`"humidity"` on wire normalized to `"auto"` in state/events) |
| humidity | % | Current ambient humidity |
| targetHumidity | 30-80 | Auto-mode target humidity |
| mistLevel | 0-9 | Reported mist level (0 = inactive) |
| warmMistLevel | 0-3 | Warm-mist level (0 = warm-mist off) |
| warmMistEnabled | on, off | Boolean derived from warmMistLevel (on if 1-3, off if 0) |
| waterLacks | yes, no | Water-tank low/empty indicator |
| displayOn | on, off | Front-panel display state |
| autoStopEnabled | on, off | Auto-stop config (passive read from `autoStopSwitch` — no setter) |
| autoStopReached | yes, no | Whether auto-stop is currently active (passive read from `autoStopState`) |
| info | HTML | Tile summary |

Commands: `setMode` (auto/sleep/manual), `setMistLevel` (1-9), `setWarmMistLevel` (0-3), `setHumidity` (30-80), `setDisplay`, `toggle`. (No `setAutoStop` — WARM_MIST is the only feature flag per `device_map.py`.)

### Tower Fan (LTF-F422S) *— v2.1 preview*

First fan device supported by this fork. Mode `sleep` maps to API literal `advancedSleep` internally; users see/use `sleep`. Temperature is divided by 10 from the raw response (e.g. `717` → `71.7°F`, matching the HA fixture's empirical reading).

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| speed | 1-12 | Fan speed (off when device is off) |
| level | 0-100 | SwitchLevel mapping (1-12 mapped to %) |
| mode | normal, turbo, auto, sleep | Current mode |
| oscillation | on, off | Single-axis oscillation state |
| mute | on, off | Mute state |
| displayOn | on, off | Front-panel display state |
| temperature | °F | Ambient temperature (raw / 10) |
| timerRemain | seconds | Active timer remaining |
| errorCode | int | Device error code (0 = healthy) |
| displayingType | 0, 1 | Read-only diagnostic — pyvesync notes "unknown functionality"; exposed for observation |
| sleepPreferenceType | string | Read-only nested response field (e.g. `default`, `advanced`) |
| info | HTML | Tile summary |

Commands: `setMode`, `setSpeed` (1-12), `setOscillation`, `setMute`, `setDisplay`, `setTimer` (seconds + on/off action), `cancelTimer`, `toggle`.

### Pedestal Fan (LPF-R432S) *— v2.1 preview*

2-axis oscillation (horizontal + vertical, controlled separately). Mode `sleep` maps to `advancedSleep` like Tower Fan; mode `eco` is the Pedestal Fan's auto-equivalent (Tower Fan has `auto`; do not confuse). `childLock` is read-only — pyvesync has no setter and ST/HB community drivers also expose no write path. Timer is omitted in v2.1 (no community-confirmed payload).

Covers LPF-R432S-AEU, LPF-R432S-AUS, and LPF-R432S-AUK (UK market variant, v2.3). (pyvesync's fixture filename is a typo — `LPF-R423S.yaml` — but real device codes are `LPF-R432S`.)

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power |
| speed | 1-12 | Fan speed (off when device is off) |
| level | 0-100 | SwitchLevel mapping (1-12 mapped to %) |
| mode | normal, turbo, eco, sleep | Current mode |
| horizontalOscillation | on, off | Horizontal oscillation toggle |
| verticalOscillation | on, off | Vertical oscillation toggle |
| oscillationLeft | 0-100 | Horizontal left bound |
| oscillationRight | 0-100 | Horizontal right bound |
| oscillationTop | 0-100 | Vertical top bound |
| oscillationBottom | 0-100 | Vertical bottom bound |
| oscillationYaw | number | Current head yaw position (read-only) |
| oscillationPitch | number | Current head pitch position (read-only) |
| mute | on, off | Mute state |
| displayOn | on, off | Front-panel display state |
| temperature | °F | Ambient temperature (raw / 10) |
| childLock | on, off | **Read-only** — pyvesync has no setter, no command exposed |
| errorCode | int | Device error code (0 = healthy) |
| sleepPreferenceType | string | Read-only nested response field |
| info | HTML | Tile summary |

Commands: `setMode`, `setSpeed` (1-12), `setHorizontalOscillation`, `setVerticalOscillation`, `setHorizontalRange` (left + right), `setVerticalRange` (top + bottom), `setMute`, `setDisplay`, `toggle`. (No `setChildLock`, no timer commands in v2.1.)

### Generic Device (any unrecognized model)

When a Levoit device is discovered whose model code is not recognized (not in the Core/Vital/Superior families), it is automatically assigned the **Levoit Generic Device** driver instead of being silently skipped.

| event | Values | Description |
| --- | --- | --- |
| switch | on, off | Power state (best-effort) |
| compat | string | Detected API shape ("v2-api purifier (N fields)", "v2-api humidifier (N fields)", "unknown shape") |
| diagnostics | markdown | Copy-paste block for new-device-support issue filing |
| humidity | % | Current humidity, if humidifier-shape response |
| airQualityIndex | number | Levoit AQ index (1-4) or PM2.5 ug/m3 proxy, if purifier-shape response. Required by Hubitat AirQuality capability. |
| airQuality | good, moderate, poor, very poor, unknown | Categorical label derived from AQLevel, if present. Not emitted for PM25-only devices. |
| level | 0-100 | Speed/mist level mapped to %, if present in response |
| mode | string | Current mode if present in response |
| filter | 0-100 | Filter/wick life %, if present in response |
| info | HTML | Tile summary |

Commands: `captureDiagnostics`, `toggle`, `on`, `off`.

**Humidifier auto-poll limitation:** The parent driver's polling logic branches on the child driver's type name — it sends `getPurifierStatus` for purifier children and `getHumidifierStatus` for humidifier children. The Generic driver's type name (`Levoit Generic Device`) does not contain "Humidifier", so the parent always polls it with `getPurifierStatus`. Devices that respond to `getHumidifierStatus` (not `getPurifierStatus`) will therefore show `compat="unknown shape"` on every background poll cycle.

This does not affect manual operation:

- The `captureDiagnostics()` command probes **both** `getPurifierStatus` and `getHumidifierStatus` and gives an accurate compat reading regardless of the poll path.
- The `refresh` button (and the `update()` self-fetch) also tries both methods, so it will find the right shape.

The background-poll limitation is intentional for this v2.0 release — the Generic driver is a diagnostic aid, not a production control driver. For reliable ongoing control of a humidifier-shape device, [file a new-device-support issue](https://github.com/level99/Hubitat-VeSync/issues/new?template=new_device_support.yml) with your `captureDiagnostics` output so a proper humidifier-aware driver can be authored. Once that driver ships, you can re-assign the device to it without losing your automations.

## Filing a new-device-support request

If your device was discovered but placed in the Generic driver, it can often be fully supported with a single community contribution. Here's how to file a useful request:

1. On the **Generic Device** child device page, click **captureDiagnostics**.
2. Wait a few seconds, then find the `diagnostics` attribute (it updates after the fetch completes).
3. Copy the entire `diagnostics` attribute value.
4. Open a [new-device-support issue](https://github.com/level99/Hubitat-VeSync/issues/new?template=new_device_support.yml) and paste the diagnostics block into the provided field.

The diagnostics block includes:
- Your device's VeSync model code
- The results of both `getPurifierStatus` and `getHumidifierStatus` probe calls (inner codes + field lists)
- The detected API shape

This gives a driver developer everything they need to add full support without needing physical access to your device.

Note: the diagnostics block does NOT include your account credentials, token, or email — those are never recorded.

## Reporting issues

If something doesn't work right, capture a debug log and post it ([Hubitat community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) or [GitHub issue](https://github.com/level99/Hubitat-VeSync/issues)):

1. On the **VeSync Integration** parent device, set **Enable debug logging** to **true** and **Save Preferences**.
2. Trigger or wait for the issue (e.g. press the misbehaving button, or wait one poll cycle for status issues).
3. In Hubitat → **Logs**, copy any lines mentioning the affected device.

Account email, accountID, and auth token are auto-redacted in every log line — you can post directly without scrubbing. Debug logging auto-disables 30 minutes after enabling.

If a maintainer asks for deeper API output, they'll point you to the **Verbose API response logging** preference on the parent device — same procedure, that toggle additionally captures the full API response body.

¹ `LAP-V201-AUSR` (no `S` after `V201`) is an intentional typo SKU — this is the literal model code the VeSync cloud API emits for AU-market V201S hardware per pyvesync `device_map.py`. The driver routes it to the Vital 200S child device. Do not assume it is a different model.

## Acknowledgements

The Groovy code is loosely based on the Python library at: https://github.com/webdjoe/pyvesync

The original Core 200S/300S/400S/600S drivers and the VeSyncIntegration framework are by **Niklas Gustafsson**.

Thank you to **elfege** for adding `setLevel()` and figuring out the 'max' speed was missing on Core 600S.

The Vital 200S, Superior 6000S, parent driver fixes, and v2.0 fork release are by **Dan Cox**, with payload research from pyvesync's test fixtures (`LAP-V201S.yaml`, `LEH-S601S.yaml`).

The v2.1 drivers (Vital 100S, Classic 300S, OasisMist 450S, Tower Fan, Pedestal Fan) are also by **Dan Cox**, and ship as **preview drivers**: the maintainer does not own this hardware. Each driver was built from canonical pyvesync fixtures plus cross-checks against Home Assistant's `vesync` integration and the SmartThings/Homebridge community drivers. Inline `CROSS-CHECK` comment blocks document every contentious decision (decision/rationale/source/refutation criteria) so users reporting hardware behavior contradicting our blind decisions have a clear reference. If your device behaves differently than this driver expects, please [open an issue](https://github.com/level99/Hubitat-VeSync/issues) with a `captureDiagnostics` paste and a debug log — we'll iterate.

If you're a Vital 200S or Superior 6000S user who tried this integration before and hit "device discovered but data retrieval fails" — that's the bug fixed in v2.0.
