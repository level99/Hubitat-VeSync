# Hubitat-VeSync

[![Lint](https://github.com/level99/Hubitat-VeSync/actions/workflows/lint.yml/badge.svg?branch=main)](https://github.com/level99/Hubitat-VeSync/actions/workflows/lint.yml)
[![Spock](https://github.com/level99/Hubitat-VeSync/actions/workflows/spock.yml/badge.svg?branch=main)](https://github.com/level99/Hubitat-VeSync/actions/workflows/spock.yml)

A Hubitat Elevation driver pack for **Levoit smart home devices** (air purifiers and humidifiers), communicating with the VeSync cloud API. Discover and control your Levoit hardware from Hubitat — fan speeds, modes, schedules, AQ + PM2.5 sensors, target humidity, water level, drying — alongside everything else on your hub.

## Supported devices

| Device | Model codes | Capabilities surfaced |
|---|---|---|
| **Levoit Core 200S** Air Purifier | `Core200S`, `LAP-C201S-AUSR`, `LAP-C201S-WUSR` | Switch, FanControl (1-3), Mode, Timer, Display, ChildLock, Filter; separate Night Light child |
| **Levoit Core 300S** Air Purifier | `Core300S`, `LAP-C301S-WJP` | Switch, FanControl (1-4), Mode, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Core 400S** Air Purifier | `Core400S`, `LAP-C401S-WJP/-WUSR/-WAAA` | Switch, FanControl (1-4), Mode, AutoPreference, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Core 600S** Air Purifier | `Core600S`, `LAP-C601S-WUS/-WUSR/-WEU` | Switch, FanControl (1-5), Mode, AutoPreference, Timer, Display, ChildLock, Filter, AQ + PM2.5 |
| **Levoit Vital 200S** Air Purifier | `LAP-V201S-WUS/-WUSR/-WEU/-WEUR/-AASR/-AUSR` | Switch, FanControl (1-4), Mode, AutoPreference, PetMode, LightDetection, Timer, Display, ChildLock, Filter, AQ + PM2.5, RoomSize |
| **Levoit Superior 6000S** Humidifier | `LEH-S601S-WUS/-WUSR/-WEUR`, `LEH-S602S-WUS` | Switch, MistLevel (1-9), TargetHumidity, Mode (auto/sleep/manual), DryingMode, AutoStop, ChildLock, Display, WickFilterLife, Water level, Pump cleaning, Temperature |
| **Levoit Generic Device** | Fall-through for unsupported `LAP-` / `LEH-` / `LV-` Levoit models | Best-effort Switch + SwitchLevel + AirQuality + Humidity, plus `captureDiagnostics()` for filing new-device-support issues |

For per-device attribute and command details: [`Drivers/Levoit/readme.md`](Drivers/Levoit/readme.md).

For upcoming devices in v2.1+ (Vital 100S, Classic 300S, OasisMist 450S US, Tower/Pedestal Fans, etc.): [`ROADMAP.md`](ROADMAP.md).

## Install via Hubitat Package Manager

### First-time install (no Levoit drivers yet)

Two install paths depending on whether the fork is in HPM's master index yet:

1. **HPM keyword search** *(preferred — once the master-index PR merges)*: open HPM → Install → Search by Keywords → search `Levoit` or `VeSync` → install.
2. **HPM manifest URL** *(works today)*: open HPM → Install → From a URL → paste:
   ```
   https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json
   ```
   HPM downloads and installs the parent app + per-model drivers.

### Manual install (no HPM)

Paste each driver from `Drivers/Levoit/*.groovy` into Hubitat's **Drivers Code** page. HPM is strongly recommended — it handles updates automatically.

## After install — first-time setup

1. Apps → **Add User App** → select **VeSync Integration**.
2. Enter your VeSync mobile-app email + password (same credentials you use in the Levoit/VeSync mobile app).
3. Set **Refresh Interval** (default 30s — how often Hubitat polls VeSync for device state). 60-120s is plenty for most use and reduces API load.
4. Click Done. Within ~5 seconds the parent logs in, discovers your Levoit devices, and creates one Hubitat child per device, named to match your VeSync app labels.

To re-scan after adding a new device in the VeSync app: open the parent device → **Resync Equipment**.

## What you get in Hubitat

Each child device exposes standard Hubitat capabilities plus device-specific attributes:

- **Air purifiers** — control fan speed via `setSpeed`/`cycleSpeed`, switch modes via `setMode("auto"/"manual"/"sleep"/...)`, see real-time AQ + PM2.5 readings, monitor filter life percentage. Vital 200S also exposes pet mode and light-detection sleep mode.
- **Humidifiers** — set target humidity, control mist level (1-9), monitor water and wick status, enable drying mode after manual shutdown, watch ambient temperature. Superior 6000S also exposes pump cleaning state.
- **All devices** — standard `Switch` (on/off), `Refresh`, child-lock, display on/off, sleep timers.
- **`info` HTML attribute** on every child — multi-line summary suitable for dashboard tiles.

## Migrating from upstream NiklasGustafsson/Hubitat?

Install the HPM package the same way as a first-time install — Hubitat matches our drivers to your Niklas-installed drivers by `(namespace, name)` and updates source in place. Existing Core 200S/300S/400S/600S devices keep working with no re-pairing. Vital 200S / Superior 6000S users (previously "discovered but no data") need to re-pick the device Type once. Full guide: [`docs/migration-from-niklas-upstream.md`](docs/migration-from-niklas-upstream.md).

## Architecture

A single **parent driver** (`VeSyncIntegration.groovy`) holds your VeSync credentials, logs in, discovers devices, schedules periodic polling, and routes API calls. Per-model **child drivers** (one for Core 200S, one for Vital 200S, etc.) expose Hubitat capabilities and parse status responses. All API traffic uses VeSync's `bypassV2` cloud endpoint with model-specific method names + payloads. Token auto-refresh on expiry; PII redaction in all log paths; connection-pool retry on transient failures.

## Logging

Three preference toggles per driver gate log verbosity:

- `descriptionTextEnable` (default ON) — INFO-level user-meaningful events (power on/off, mode changes, threshold alerts).
- `debugOutput` (default OFF) — DEBUG trace + raw API response dump. Auto-disables after 30 minutes.
- `verboseDebug` (parent only, default OFF) — full request/response body dump for API drift diagnosis.

The parent driver auto-redacts email, account ID, token, and password from every log line.

## Troubleshooting

- **"Discovered but no data"** on a child device — the device's Type may not match the new fork driver. Open the device → Type dropdown → pick the matching `Levoit ...` driver → Save → Refresh.
- **Devices unresponsive after weeks/months** — VeSync token expired. The fork's parent (v2.0+) auto-recovers; first poll after expiry may show a 5-10s delay while re-auth runs.
- **Parent never finds devices** — verify VeSync credentials in the parent preferences; same email + password as the Levoit/VeSync mobile app.
- **Devices show up but attribute polling lags** — refresh interval may be too aggressive. Increase to 60-120s.
- **Non-Levoit devices on your VeSync account** (Etekcity smart plugs, Cosori air fryers, etc.) — these are skipped at discovery with an INFO log. They don't get a Hubitat child created.

For new-device-support requests, install the **Generic Levoit Diagnostic** driver as a fall-through, run its `captureDiagnostics()` command, and paste the markdown output into a [new-device-support issue](https://github.com/level99/Hubitat-VeSync/issues/new?template=new_device_support.yml).

## Contributing

Pull requests welcome. See [`CLAUDE.md`](CLAUDE.md) for the development pipeline, code conventions, and bug-pattern catalog. The Spock unit-test harness + static lint run on every PR via GitHub Actions.

Roadmap and unscheduled future work: [`ROADMAP.md`](ROADMAP.md).

## Support / community

- **Hubitat community thread:** [Levoit Air Purifiers Drivers](https://community.hubitat.com/t/levoit-air-purifiers-drivers/81816) — active discussion for both this fork and Niklas's original drivers.
- **GitHub issues:** [level99/Hubitat-VeSync/issues](https://github.com/level99/Hubitat-VeSync/issues) — bug reports + new-device-support requests via issue templates.

## Credits

- **Niklas Gustafsson** — original VeSyncIntegration framework, Core 200S/300S/400S/600S drivers
- **Dan Cox** — community fork maintainer, v1.6+ contributions, Vital 200S, Superior 6000S, parent humidifier-method fix, Generic diagnostic driver, token-expiry auto-recovery, infrastructure
- **elfege** — `setLevel()` support, Core 600S 'max' speed
- **[pyvesync](https://github.com/webdjoe/pyvesync)** — canonical VeSync API payload reference

## License

[MIT](LICENSE) — Copyright (c) 2020 Niklas Gustafsson (original). Fork contributions remain MIT.
