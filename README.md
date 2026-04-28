# Hubitat-VeSync

[![Lint](https://github.com/level99/Hubitat-VeSync/actions/workflows/lint.yml/badge.svg?branch=main)](https://github.com/level99/Hubitat-VeSync/actions/workflows/lint.yml)
[![Spock](https://github.com/level99/Hubitat-VeSync/actions/workflows/spock.yml/badge.svg?branch=main)](https://github.com/level99/Hubitat-VeSync/actions/workflows/spock.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub release](https://img.shields.io/github/v/release/level99/Hubitat-VeSync?label=version)](https://github.com/level99/Hubitat-VeSync/releases)
[![Last commit](https://img.shields.io/github/last-commit/level99/Hubitat-VeSync)](https://github.com/level99/Hubitat-VeSync/commits/main)

A Hubitat Elevation driver pack for **Levoit smart home devices** (air purifiers, humidifiers, and fans), communicating with the VeSync cloud API. Discover and control your Levoit hardware from Hubitat — fan speeds, modes, schedules, AQ + PM2.5 sensors, target humidity, water level, drying, oscillation — alongside everything else on your hub.

## Supported devices

| Device | Model codes | Capabilities surfaced |
|---|---|---|
| **Levoit Core 200S** Air Purifier | `Core200S`, `LAP-C201S-AUSR`, `LAP-C201S-WUSR`, `LAP-C202S-WUSR` | Switch, FanControl (1-3), Mode, Timer, Display, ChildLock, Filter; separate Night Light child |
| **Levoit Core 300S** Air Purifier | `Core300S`, `LAP-C301S-WJP`, `LAP-C302S-WUSB` | Switch, FanControl (1-4), Mode, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Core 400S** Air Purifier | `Core400S`, `LAP-C401S-WJP/-WUSR/-WAAA/-KUSR` | Switch, FanControl (1-4), Mode, AutoPreference, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Core 600S** Air Purifier | `Core600S`, `LAP-C601S-WUS/-WUSR/-WEU` | Switch, FanControl (1-5), Mode, AutoPreference, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Vital 100S** Air Purifier *(v2.1 preview)* | `LAP-V102S-AASR/-WUS/-WEU/-AUSR/-WJP/-AJPR/-AEUR` | Switch, FanControl (1-4), Mode (manual/auto/sleep/pet), AutoPreference, PetMode, Timer, Display, ChildLock, Filter, AQ + PM2.5, RoomSize. **No light-detection** (V102S lacks the LIGHT_DETECT feature flag). |
| **Levoit Vital 200S** Air Purifier | `LAP-V201S-WUS/-WUSR/-WEU/-WEUR/-AASR/-AUSR/-AEUR/-WJP`, `LAP-V201-AUSR`¹ | Switch, FanControl (1-4), Mode, AutoPreference, PetMode, LightDetection, Timer, Display, ChildLock, Filter, AQ + PM2.5, RoomSize |
| **Levoit Classic 300S** Humidifier *(v2.1 preview)* | `LUH-A601S-WUSB/-AUSW` (`Classic300S`) | Switch, MistLevel (1-9), TargetHumidity (30-80), Mode (auto/sleep/manual), AutoStop, NightLight (off/dim/bright), Display, Humidity sensor |
| **Levoit Superior 6000S** Humidifier | `LEH-S601S-WUS/-WUSR/-WEUR`, `LEH-S602S-WUS` | Switch, MistLevel (1-9), TargetHumidity, Mode (auto/sleep/manual), DryingMode, AutoStop, ChildLock, Display, WickFilterLife, Water level, Pump cleaning, Temperature |
| **Levoit OasisMist 450S Humidifier** *(v2.1 preview)* | `LUH-O451S-WUS/-WUSR/-WEU`, `LUH-O601S-WUS/-KUS` | Switch, MistLevel (1-9), WarmMistLevel (0-3), TargetHumidity (40-80), Mode (auto/sleep/manual), AutoStop, Display, Humidity sensor. **No night light** on US/KUS variants (hardware lacks it). **`LUH-O451S-WEU` (EU) only:** RGB nightlight via standard Hubitat ColorControl (`setColor`, `setHue`, `setSaturation`) + `setNightlightSwitch`. Ships as preview pending EU community validation; based on [pyvesync PR #502](https://github.com/webdjoe/pyvesync/pull/502) (OPEN/CHANGES_REQUESTED). |
| **Levoit LV600S Humidifier** *(v2.2 preview)* | `LUH-A602S-WUSR/-WUS/-WEUR/-WEU/-WJP/-WUSC` | Switch, MistLevel (1-9), WarmMistLevel (0-3), TargetHumidity (30-80), Mode (auto/sleep/manual), AutoStop, Display, Humidity sensor. **No night light** (hardware lacks it). Same VeSyncHumid200300S class as Classic 300S + OasisMist 450S. **Note:** auto mode may require `humidity` payload on some EU firmware variants (see [pyvesync PR #505](https://github.com/webdjoe/pyvesync/pull/505)); driver follows canonical pyvesync fixture (`mode:"auto"`). |
| **Levoit Dual 200S Humidifier** *(v2.2 preview)* | `Dual200S`, `LUH-D301S-WUSR/-WJP/-WEU/-KEUR` | Switch, MistLevel (**1-2 only** — 2-level hardware), TargetHumidity (30-80), Mode (**auto/manual** — no sleep per pyvesync device_map.py), AutoStop, Display, Humidity sensor. **No night light command** (feature flag absent; brightness attribute is passive read-only). Same VeSyncHumid200300S API class as Classic 300S; EU SKUs apply same multi-firmware auto-mode try-fallback as LV600S. |
| **Levoit Classic 200S Humidifier** *(v2.3 preview)* | `Classic200S` (literal device type) | Switch, MistLevel (1-9), TargetHumidity (30-80), Mode (**auto/manual** — no sleep per device_map.py), AutoStop, Display, Humidity sensor. **No night light command** (feature flag absent; brightness is passive read-only). pyvesync class `VeSyncHumid200S` — subclass of VeSyncHumid200300S with one override: display uses `setIndicatorLightSwitch`. **Naming trap:** do not confuse with Classic 300S (`LUH-A601S-*`, separate driver). |
| **Levoit LV600S Hub Connect Humidifier** *(v2.3 preview)* | `LUH-A603S-WUS` | Switch, MistLevel (1-9), WarmMistLevel (0-3), TargetHumidity (30-80), Mode (auto/sleep/manual), Display, Humidity sensor. **No setAutoStop** (device_map.py features=[WARM_MIST] only; autoStop state is passive read). pyvesync class `VeSyncLV600S` — **different class** from existing A602S (`VeSyncHumid200300S`); V2-style payload conventions; auto mode wire value = `"humidity"`. **Naming trap:** both A602S and A603S are marketed as "LV600S" by Levoit — they are different hardware with different APIs. |
| **Levoit OasisMist 1000S Humidifier** *(v2.3 preview)* | `LUH-M101S-WUS`, `LUH-M101S-WUSR`, `LUH-M101S-WEUR` | Switch, MistLevel (1-9), TargetHumidity (30-80), Mode (auto/sleep/manual), AutoStop, Display, Humidity sensor. **No warm mist** (hardware absent). pyvesync class `VeSyncHumid1000S` (inherits `VeSyncHumid200300S`; overrides key methods); V2-style payload conventions (powerSwitch/workMode/`virtualLevel` API endpoint/`targetHumidity` top-level). `LUH-M101S-WEUR` (EU) adds nightlight on/off + brightness 0-100; US/WUSR variants have `AUTO_STOP` only. Nightlight command runtime-gated: no-ops with INFO log on non-WEUR hardware. Single driver for all three model codes. |
| **Levoit Tower Fan** *(v2.1 preview)* | `LTF-F422S-WUS/-WUSR/-KEU/-WJP` | Switch, FanControl (1-12), SwitchLevel, Mode (normal/turbo/auto/sleep), Oscillation (single-axis), Mute, Timer, Display, Temperature, ErrorCode |
| **Levoit Pedestal Fan** *(v2.1 preview)* | `LPF-R432S-AEU/-AUS/-AUK` | Switch, FanControl (1-12), SwitchLevel, Mode (normal/turbo/eco/sleep), 2-axis Oscillation with range control, Mute, Display, Temperature, ChildLock (read-only) |
| **Levoit Generic Device** | Fall-through for unsupported `LAP-` / `LEH-` / `LV-` Levoit models | Best-effort Switch + SwitchLevel + AirQuality + Humidity, plus `captureDiagnostics()` for filing new-device-support issues |

¹ `LAP-V201-AUSR` (no `S` after `V201`) is an intentional typo SKU — this is the literal model code the VeSync cloud API emits for AU-market V201S hardware, as documented in pyvesync `device_map.py`. If your Hubitat log shows `LAP-V201-AUSR` as unrecognized, update to v2.2 or later.

*Preview drivers* are v2.1+ drivers built without maintainer hardware, validated against canonical pyvesync fixtures + Home Assistant + SmartThings/Homebridge community drivers. Each carries inline `CROSS-CHECK` comment blocks documenting every contentious decision. If your device behaves differently, please [open an issue](https://github.com/level99/Hubitat-VeSync/issues) with a `captureDiagnostics` paste and a debug log.

For per-device attribute and command details: [`Drivers/Levoit/readme.md`](Drivers/Levoit/readme.md).

For upcoming devices beyond v2.2: [`ROADMAP.md`](ROADMAP.md).

## Install via Hubitat Package Manager

### First-time install (no Levoit drivers yet)

Two install paths:

1. **HPM keyword search** *(preferred)*: open HPM → Install → Search by Keywords → search `Levoit` or `VeSync` → install.
2. **HPM manifest URL** *(equivalent, no search needed)*: open HPM → Install → From a URL → paste:
   ```
   https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json
   ```
   HPM downloads and installs the parent driver + per-model drivers.

### Manual install (no HPM)

Paste each driver from `Drivers/Levoit/*.groovy` into Hubitat's **Drivers Code** page. HPM is strongly recommended — it handles updates automatically.

## After install — first-time setup

1. **Devices** → **Add Device** → **Virtual**. Pick any name; for **Type**, scroll down to the User Devices section and select **VeSync Integration**. Save.
2. On the new device's detail page, enter your VeSync mobile-app email + password (same credentials you use in the Levoit/VeSync mobile app).
3. Set **Refresh Interval** (default 30s — how often Hubitat polls VeSync for device state). 60-120s is plenty for most use and reduces API load.
4. Click **Save Preferences**. Within ~5 seconds the parent logs in, discovers your Levoit devices, and creates one Hubitat child per device, named to match your VeSync app labels.

To re-scan after adding a new device in the VeSync app: open the parent device → **Resync Equipment**.

### Adding new Levoit devices after the initial install

If you buy a new Levoit device after your initial setup, the corresponding child driver may not be installed yet — most child drivers ship as optional, and you only install the ones for devices you own. After Resync Equipment, the parent driver will log an INFO message naming exactly which driver is missing.

To add it via HPM:

1. HPM → **Modify** (NOT Install or Update)
2. Select **"Levoit Air Purifiers, Humidifiers, and Fans"** → **Next**
3. Check the missing driver(s) — the parent's INFO log names them — then **Next** → install
4. On the VeSync Integration parent device, click **Resync Equipment**. The new device should now appear.

**Why HPM "Modify" not "Install":** the Install search is for fresh installs of packages you don't currently have. Picking an already-installed package via Install will hang on the "Next" spinner. If you hit that hang, HPM **Match Up** can reconcile your hub's state with the manifest, then try Modify again.

## What you get in Hubitat

Each child device exposes standard Hubitat capabilities plus device-specific attributes:

- **Air purifiers** — control fan speed via `setSpeed`/`cycleSpeed`, switch modes via `setMode("auto"/"manual"/"sleep"/...)`, see real-time AQ + PM2.5 readings, monitor filter life percentage. Vital 100S/200S also expose pet mode; Vital 200S adds light-detection sleep mode.
- **Humidifiers** — set target humidity, control mist level (1-9), monitor water and wick status, watch ambient humidity (and temperature where available). Superior 6000S adds drying mode + pump cleaning; Classic 300S adds 3-step night light; OasisMist 450S adds warm-mist (0-3).
- **Fans** — control fan speed (1-12), switch modes (Tower: normal/turbo/auto/sleep; Pedestal: normal/turbo/eco/sleep), single-axis oscillation (Tower) or 2-axis with range control (Pedestal), monitor ambient temperature.
- **All devices** — standard `Switch` (on/off), `Refresh`, child-lock (read-only on Pedestal Fan), display on/off, sleep timers (where the hardware supports them).
- **`info` HTML attribute** on every child — multi-line summary suitable for dashboard tiles.

## Migrating from upstream NiklasGustafsson/Hubitat?

Install the HPM package the same way as a first-time install — Hubitat matches our drivers to your Niklas-installed drivers by `(namespace, name)` and updates source in place. Existing Core 200S/300S/400S/600S devices keep working with no re-pairing. Vital 200S / Superior 6000S users (previously "discovered but no data") need to re-pick the device Type once. Full guide: [`docs/migration-from-niklas-upstream.md`](docs/migration-from-niklas-upstream.md).

## Architecture

A single **parent driver** (`VeSyncIntegration.groovy`) holds the VeSync account credentials, logs in, discovers devices, schedules periodic polling, and routes API calls. Per-model **child drivers** (one for Core 200S, one for Vital 200S, one for Tower Fan, etc.) expose Hubitat capabilities and parse status responses. All API traffic uses VeSync's `bypassV2` cloud endpoint with model-specific method names + payloads. Token auto-refresh on expiry; PII redaction in all log paths; connection-pool retry on transient failures.

## Logging

Three preference toggles per driver gate log verbosity:

- `descriptionTextEnable` (default ON) — INFO-level user-meaningful events (power on/off, mode changes, threshold alerts).
- `debugOutput` (default OFF) — DEBUG trace + raw API response dump. Auto-disables after 30 minutes.
- `verboseDebug` (parent only, default OFF) — full request/response body dump for API drift diagnosis.

The parent driver auto-redacts email, account ID, token, and password from every log line.

## Configuration

### VeSync API region (EU users)

The parent driver's **VeSync API region** preference (default: `US`) controls which VeSync cloud host is used:

- **US** (default) — `smartapi.vesync.com`. Correct for North America, Australia, and most other regions.
- **EU** — `smartapi.vesync.eu`. Select this if your VeSync account was registered in the EU and US-region login fails.

Changing region clears the stored auth token and forces a fresh login. **EU support is preview** — the maintainer does not have EU hardware for live verification. If you're an EU user, please report your experience on the [Hubitat community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).

## Troubleshooting

- **"Discovered but no data"** on a child device — the device's Type may not match the new fork driver. Open the device → Type dropdown → pick the matching `Levoit ...` driver → Save → Refresh.
- **Devices unresponsive after weeks/months** — VeSync token expired. The fork's parent (v2.0+) auto-recovers; first poll after expiry may show a 5-10s delay while re-auth runs.
- **Parent never finds devices** — verify VeSync credentials in the parent preferences; same email + password as the Levoit/VeSync mobile app. EU users: confirm the **VeSync API region** preference is set to `EU`.
- **Devices show up but attribute polling lags** — refresh interval may be too aggressive. Increase to 60-120s.
- **Non-Levoit devices on your VeSync account** (Etekcity smart plugs, Cosori air fryers, etc.) — these are skipped at discovery with an INFO log. They don't get a Hubitat child created.

For new-device-support requests, install the **Generic Levoit Diagnostic** driver as a fall-through, run its `captureDiagnostics()` command, and paste the markdown output into a [new-device-support issue](https://github.com/level99/Hubitat-VeSync/issues/new?template=new_device_support.yml).

## Contributing

Pull requests welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md) — it covers the codebase tour, dev environment, conventions enforced by lint/tests, test runners, and PR flow. AI-assisted contributors should also read [`CLAUDE.md`](CLAUDE.md) for the dev/QA/tester agent-pipeline overlay. The Spock unit-test harness + static lint run on every PR via GitHub Actions.

Community conduct: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

Roadmap and unscheduled future work: [`ROADMAP.md`](ROADMAP.md).

## Support / community

- **Hubitat community thread:** [Levoit Air Purifiers, Humidifiers, and Fans](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) — active discussion for this fork.
- **GitHub issues:** [level99/Hubitat-VeSync/issues](https://github.com/level99/Hubitat-VeSync/issues) — bug reports + new-device-support requests via issue templates.

## Credits

- **[Niklas Gustafsson](https://github.com/NiklasGustafsson)** — original VeSyncIntegration framework, Core 200S/300S/400S/600S drivers
- **Dan Cox** — community fork maintainer (v2.0+); Vital 100S/200S, Classic 300S, Superior 6000S, OasisMist 450S, Tower Fan, Pedestal Fan, parent humidifier-method fix, Generic diagnostic driver, token-expiry auto-recovery, infrastructure
- **elfege** — `setLevel()` support, Core 600S 'max' speed
- **[pyvesync](https://github.com/webdjoe/pyvesync)** — canonical VeSync API payload reference; HA `vesync` integration, SmartThings + Homebridge community drivers used as cross-check sources

## License

[MIT](LICENSE) — Copyright (c) 2020 Niklas Gustafsson (original). Fork contributions remain MIT.
