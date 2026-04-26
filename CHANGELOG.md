# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1] - 2026-04-26

Adds five new drivers — two air purifiers, two humidifiers, and two fans (the first non-purifier/non-humidifier devices in this fork). All v2.1 drivers ship as **preview** — built without maintainer hardware via cross-referencing pyvesync canonical fixtures + Home Assistant `vesync` integration + SmartThings/Homebridge community drivers. Each carries inline `CROSS-CHECK` comment blocks at every contentious decision point so users can refute blind decisions with hardware reports.

### Added

- **Levoit Vital 100S Air Purifier** driver (LAP-V102S, all regional variants). Same V2 platform as Vital 200S; LIGHT_DETECT feature intentionally absent (pyvesync `device_map.py` confirms V102S has no LIGHT_DETECT flag) (652c3fe).
- **Levoit Classic 300S Humidifier** driver (LUH-A601S). Foundational V1-humidifier driver — switch payload `{enabled, id:0}`, mist 1-9, target humidity 30-80, 3-step night light (off/dim/bright per HA PR #137544 physical-test findings), display-field aliasing chain (3f14521).
- **Levoit OasisMist 450S Smart Humidifier** driver (LUH-O451S-WUS/-WUSR, LUH-O601S-WUS/-KUS — all 4 model codes route to single driver per pyvesync grouping). Extends Classic 300S with warm-mist (0-3) and 3-mode enum (no `humidity` mode — firmware rejects per pyvesync issue #295). Humidity range firmware-clamped to 40-80 (pyvesync issue #296). No night-light hardware (6c55717, df2c1c4).
- **Levoit Tower Fan** driver (LTF-F422S, all regional variants). 12-speed fan, single-axis oscillation, mute, sleep mode reverse-mapped from API `advancedSleep`. Temperature `/10` per HA fixture empirical evidence. `displayingType` exposed as read-only diagnostic (pyvesync notes "Unknown functionality") (64ba991).
- **Levoit Pedestal Fan** driver (LPF-R432S-AEU/-AUS). 12-speed fan, 2-axis oscillation with range control (separate horizontal + vertical, no combined toggle), eco mode (NOT auto), childLock read-only (no setter). Timer omitted in v2.1 (no community-confirmed payload) (71ef619).
- **Parent driver wiring** for all 5 new drivers — `deviceType()` switch, `addChildDevice` branches, `isLevoitClimateDevice` whitelist extended with `LTF-`/`LPF-` prefixes, `updateDevices()` routing for fan API methods (`getTowerFanStatus`, `getFanStatus`) (ad96505).
- **Spock unit-test specs** for all 5 new drivers — LevoitClassic300SSpec (40 tests), LevoitOasisMist450SSpec (53), LevoitPedestalFanSpec (62), LevoitTowerFanSpec (49), LevoitVital100SSpec (24); plus 15 new VeSyncIntegrationSpec tests for parent wiring. All pass on the harness (410 total methods green).
- **Test fixtures** for all 5 new device codes — `Classic300S.yaml`, `LAP-V102S.yaml`, `LPF-R432S.yaml`, `LTF-F422S.yaml`, `LUH-O451S-WUS.yaml`.
- **Cross-check citation audit** — ~272 lines of structured `CROSS-CHECK` comment blocks (decision/rationale/source/refutation criteria) across all 5 v2.1 drivers (01d421c).
- **HPM packageName rename** to *"Levoit Air Purifiers, Humidifiers, and Fans"*. Repository.json `Fan` tag added.
- **Preview banner** — `[PREVIEW v2.1]` prefix in each new driver's `description:` field (visible in Hubitat Drivers Code list and device Type dropdown). Name field deliberately untouched (Bug Pattern #9 protection).

### Changed

- All 15 drivers' `definition()` `version:` field bumped 2.0 → 2.1 (lint rule 20 lockstep).
- `Drivers/Levoit/readme.md` — top blurb expanded; manual-install table extended with 5 entries; new event/attribute tables for each new driver; v2.1 preview-driver caveat in acknowledgements.
- Top-level `README.md` — supported-devices table extended with 5 v2.1 rows; preview-driver disclosure paragraph; bullets reorganized to surface fan + humidifier variant differences.
- `ROADMAP.md` — v2.1 "next release" section retired; replaced with v2.2 placeholder; LV600S promoted as cheapest immediate follow-on (cheap because Classic 300S shipped + warm-mist payload available from OasisMist 450S).
- `tests/lint_config.yaml` — `frozen_driver_names` extended with 5 new entries (BP9 protection now active for v2.1 drivers).
- `/cut-release` slash command spec — releaseNotes guidance hardened to exclude development methodology / contributor process from HPM popup (2e3934d).
- `CLAUDE.md` — subagent resume protocol corrected to address agents by ID, not role name, per [upstream docs](https://code.claude.com/docs/en/sub-agents#resume-subagents) (d286eb2).

### Fixed

- **OasisMist 450S `humidity` mode rejection** — dropped from `setMode` enum. Device firmware universally rejects `setHumidityMode{mode:'humidity'}` with API error 11000000 across all US model codes (pyvesync issue #295). `auto`/`sleep`/`manual` only (df2c1c4).
- **OasisMist 450S target humidity floor** — clamp range corrected from `30-80` to `40-80` (firmware floor; pyvesync issue #296 + homebridge cross-check confirmation) (df2c1c4).
- **README.md OasisMist row** — first-cell text aligned with driver `definition(name:)` to satisfy lint RULE21 (README Devices Sync) (28d3efa).

## [2.0] - 2026-04-26

First community-fork release after divergence from [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat). Refocused around the VeSync ecosystem (Levoit air purifiers and humidifiers); other driver families pruned.

### Added

- **Levoit Vital 200S Air Purifier** driver (LAP-V201S, including 200S-P): full controls, AQ/PM2.5 sensors, pet mode, light-detection sleep, room size.
- **Levoit Superior 6000S Humidifier** driver (LEH-S601S): mist control 1-9, target humidity, drying mode, auto-stop, water/wick monitoring, ambient temperature.
- **Generic Levoit Diagnostic Driver** — fall-through for unsupported Levoit climate-class models (LAP-/LEH-/LV- prefixes). Best-effort Switch + AQ + Humidity, plus `captureDiagnostics()` to generate a paste-ready markdown block for filing new-device-support issues.
- **Token-expiry auto re-auth** — parent driver detects HTTP 401 and VeSync inner-code session-expiry signals, calls `login()` to refresh, and retries. Devices stay connected across multi-month uptimes (Bug Pattern #13).
- **HPM tier-2 `repository.json`** at fork root for keyword-search discoverability in Hubitat Package Manager.
- **Comprehensive README** with supported-devices table, install paths, after-install setup, troubleshooting, architecture overview, and CI badges.
- **Migration guide** for users coming from Niklas upstream (`docs/migration-from-niklas-upstream.md`).
- **Public ROADMAP.md** for forward-looking device-support tiers and infrastructure plans.
- **Per-driver `version` field** in every driver's `definition()` block; Hubitat UI now displays driver version in the device detail panel.
- **Spock unit-test harness** with Hubitat sandbox mock — ~140 specs across all drivers covering bug patterns, happy paths, and state-change gating.
- **Static lint** (`tests/lint.py`) with 21 rules: bug patterns, logging discipline, sandbox safety, PII scan, manifest consistency, doc sync, version lockstep, drivers↔README sync.
- **GitHub Actions CI** for lint + Spock harness on every PR + push to main.
- **Dependabot** for monthly GitHub Actions and Gradle dependency updates.
- **Three GitHub issue templates** (bug report, new device support, feature request) + PR template.
- **AI-assisted contributor pipeline** — four specialist agent definitions in `.claude/agents/` (developer / QA / tester / operations) covering driver code authoring, review, harness execution, and live-hub deploy. Top-level `CLAUDE.md` contributor workflow doc + bug-pattern catalog. `/cut-release` Claude Code slash command for release-cut automation. The pipeline catches bug-pattern regressions early via dev → QA → tester rounds; conventions are AI-friendly but also serve human contributors.

### Changed

- **Parent driver routes API method by device type** — humidifiers get `getHumidifierStatus`, purifiers get `getPurifierStatus`. Previously sent `getPurifierStatus` to all devices, silently failing for humidifiers (Bug Pattern #2).
- **Generic driver dispatch is whitelist-gated** — only attaches to Levoit climate-class devices (LAP-/LEH-/LV- prefixes). Non-Levoit devices on the same VeSync account (Etekcity smart plugs, Cosori air fryers, thermostats) are skipped with an INFO log instead of receiving a malfunctioning Generic child.
- **Connection-pool retry** logic for transient HTTP failures.
- **PII redaction** (`sanitize()`) wraps every parent-driver log call — email, account ID, token, password redacted from output.
- **Manifest `packageName` author field** reordered to put fork maintainer first (HPM display); driver source `namespace` preserved as `NiklasGustafsson` (Bug Pattern #9 — would orphan existing user devices if changed).

### Fixed

- Humidifier polling silently failed — see Bug Pattern #2 above.
- `getDevices()` empty device list (`[]`) now treated as a valid response (zero-device VeSync accounts no longer error).
- Undeclared `result =` assignment in `updateDevices()` was throwing `MissingPropertyException` on every poll cycle.
- `addChildDevice` phantom-handle case after recent DNI deletion — defensive validation + `state.deviceList` self-heal pass.

### Removed

- Non-VeSync driver families (Twinkly, SharkIQ, Ring, etc.) pruned from the fork. Surviving content focused on Levoit/VeSync only.
- Legacy SharkIQ Apache notice from `LICENSE` (driver was already removed; notice was dead text).
