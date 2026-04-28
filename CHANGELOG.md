# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.2] - 2026-04-27

Adds two new humidifier drivers (Levoit LV600S and Dual 200S — both preview), EU region support (preview), RGB color nightlight on the EU OasisMist 450S 4.5L variant, and routing for 6 additional regional model code variants. Fixes two reboot-survival bugs (Bug Patterns #14 and #16) that could leave polling permanently stopped or debug logging stuck on indefinitely after a hub reboot. v2.2 device drivers ship as **preview** — built without maintainer EU hardware via cross-referencing pyvesync canonical fixtures + Home Assistant `vesync` integration. Each carries inline `CROSS-CHECK` comment blocks documenting contentious decisions for community refutation.

### Added

- **EU region support (preview)** — New `VeSync API region` preference (US/EU) on the VeSync Integration parent device. EU users can select `EU` to route all API calls through `smartapi.vesync.eu` instead of the default `smartapi.vesync.com`. Changing region automatically clears stored auth credentials and forces re-login (cross-region tokens are invalid). US is the default; existing installs are unaffected. **EU support ships as preview — no EU hardware live-verified by the maintainer. Community validation welcome on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).** Login log includes the active region and host for easy confirmation.
- **Levoit LV600S Humidifier** driver (LUH-A602S-WUSR, -WUS, -WEUR, -WEU, -WJP, -WUSC — all 6 model codes). Ships as `[PREVIEW v2.2]`. Same `VeSyncHumid200300S` API class as Classic 300S + OasisMist 450S; extends with warm-mist control (0-3 levels, 0=off). Capabilities: Switch, MistLevel 1-9, WarmMistLevel 0-3, TargetHumidity 30-80, Mode (auto/sleep/manual), AutoStop, Display. No night-light (hardware absent; confirmed by pyvesync device_map.py LUH-A602S features list). **Open question:** [pyvesync PR #505](https://github.com/webdjoe/pyvesync/pull/505) (unmerged as of 2026-04-27) reports EU firmware variants may need `mode:"humidity"` instead of `mode:"auto"` to switch auto mode; driver follows canonical pyvesync fixture (`mode:"auto"`) and documents the conflict with inline CROSS-CHECK comments. Community hardware reports welcome. **Naming trap:** LUH-A602S uses VeSyncHumid200300S; LUH-A603S (different model, also marketed as "LV600S") uses VeSyncLV600S — NOT covered by this driver.
- **Parent driver wiring** for LV600S: `deviceType()` switch adding LUH-A602S-* → A602S, `addChildDevice` branch for "Levoit LV600S Humidifier", newList tracking in `getDevices()`. `isLevoitClimateDevice()` unchanged — LUH- prefix already covers LUH-A602S-* (added in v2.1).
- **Routing for 6 additional regional model code variants** (v2.2 pyvesync audit): `LUH-O451S-WEU` (EU OasisMist 450S 4.5L), `LAP-V201S-AEUR` (EU Vital 200S), `LAP-V201-AUSR` (AU Vital 200S — note intentional missing `S` in SKU; this is what the VeSync API emits for that hardware), `LAP-C202S-WUSR` (US Core 200S variant), `LAP-V201S-WJP` (Japan Vital 200S), `LAP-C302S-WUSB` (US Core 300S bundle SKU). All map to existing drivers via existing dtypes. `isLevoitClimateDevice()` unchanged — LAP-/LUH- prefix blankets already cover all 6 codes (RULE22 satisfied). Closes user gaps for previously-unhandled codes that fell through to the Generic driver.
- **RGB nightlight support for `LUH-O451S-WEU` (EU OasisMist 450S 4.5L)** (preview). Single-driver implementation — no separate driver file. Hubitat `ColorControl` capability declared in metadata (available to all OasisMist 450S variants per Hubitat's static metadata model); all RGB commands gate at runtime on `state.deviceType == "LUH-O451S-WEU"`. Non-WEU users: commands no-op with INFO log. New commands: `setNightlightSwitch(on/off)`, standard ColorControl `setColor` / `setHue` / `setSaturation`. New attributes: `nightlightSwitch`, `nightlightBrightness`, `hue`, `saturation`, `colorMode`. Implementation: HSV-brightness-adjustment (V-channel = brightness/100 via pure-function `rgbToHsv`/`hsvToRgb` helpers), `colorSliderLocation` derived by nearest-point Euclidean match on the 8-color gradient from PR #502. 180-second stale-data gate (`state.rgbNightlightSetTime`) prevents poll data overwriting a just-sent command. `state.clear()` in `updated()` resets the gate. **Ships as preview — no EU live-hardware validation by maintainer.** Based on [pyvesync PR #502](https://github.com/webdjoe/pyvesync/pull/502) (OPEN/CHANGES_REQUESTED, stalled 2026-01). EU community testers welcome on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).
- **Spock unit-test spec** `LevoitLV600SSpec.groovy` — 30 tests covering BP#1 (2-arg update), BP#3 (envelope peel, single and double-wrapped), BP#6 (mistLevel=0 when off), BP#12 (pref-seed), happy-path applyStatus from fixture, switch/mist/warm-mist/mode/humidity payload field assertions, PR #505 read-path passthrough ("humidity" mode from EU firmware emitted as-received), no night-light contract, display field aliasing, water-lacks state-change gating, info HTML local-variable guard (BP#7).
- **Vendored pyvesync fixture** `tests/fixtures/LUH-A602S.yaml` — request payloads sourced from upstream `LUH-A602S-WUS.yaml` (commit c98729c); response scenarios synthesized from Classic 300S template with warm-mist fields populated. SOURCE.md updated.
- **Levoit Dual 200S Humidifier** driver (`Dual200S`, `LUH-D301S-WUSR`, `-WJP`, `-WEU`, `-KEUR` — all 5 model codes). Ships as `[PREVIEW v2.2]`. Same `VeSyncHumid200300S` API class as Classic 300S; key differences: mist range 1-2 only (Classic 300S is 1-9 per device_map.py LUH-D301S mist_levels=range(1,3)); modes auto+manual only (no sleep per device_map.py mist_modes); no warm mist; no nightlight command (feature flag absent — `nightLightBrightness` emitted as read-only passive attribute if API returns it). Multi-firmware auto-mode try-fallback with cache applied (EU SKUs LUH-D301S-WEU/-KEUR carry the same pyvesync PR #505 risk as LUH-A602S-WEU/-WEUR). HA issue #160387 references Dual 200S nightlight; driver conservatively omits the setter pending hardware confirmation.
- **Parent driver wiring** for Dual 200S: `deviceType()` switch adding Dual200S + LUH-D301S-* → D301S dtype, `addChildDevice` branch for "Levoit Dual 200S Humidifier" with verify1 defensive validation, newList tracking extended, `isLevoitClimateDevice()` updated with "Dual200S" literal (LUH-D301S-* already covered by LUH- prefix blanket).
- **Spock unit-test spec** `LevoitDual200SSpec.groovy` — 32 tests covering BP#1 (2-arg update), BP#3 (envelope peel), BP#6 (mist=0 when off), BP#12 (pref-seed), happy-path from canonical fixture, switch/mist-clamping (1-2 ceiling enforced, Classic-300S-style 9 clamped to 2), mode rejection (sleep invalid), multi-firmware auto-mode try-fallback/cache, mode-read normalization, setHumidity, display field chain, no nightlight command, no warm-mist command, water-lacks state-change gating, nightLightBrightness passive read.
- **Vendored pyvesync fixture** `tests/fixtures/LUH-D301S.yaml` — request payloads sourced from upstream `Dual200S.yaml` (commit c98729c); response scenarios include canonical (from call_json_humidifiers.py HUMIDIFIER_DETAILS["Dual200S"]) + device_off + device_on_auto_mist2 + device_water_lacks + device_legacy_display_alias + device_auto_stop_reached. SOURCE.md updated.
- **`CODE_OF_CONDUCT.md`** — Contributor Covenant 2.1 verbatim canonical text + cross-references in README/CONTRIBUTING/CLAUDE (746b1c4).
- **`CONTRIBUTING.md`** — shared contributor onboarding (codebase tour, dev environment, conventions, test runners, PR flow, preview-driver protocol). Useful for both human contributors and AI sessions; complements `CLAUDE.md` (AI-pipeline overlay) (93a4a90).
- **Lint rules RULE23/24/25** — `driver_app_only_api` (forbids `subscribe()`/`unsubscribe()` in `Drivers/Levoit/*.groovy`; closes the BP15 gap that let app-only API calls reach v2.1 hubs), `agent_pointer_integrity` (verifies cross-doc `see CLAUDE.md X` / `see CONTRIBUTING.md X` references resolve to actual section headers), `bp16_watchdog_call_site` (enforces `ensureDebugWatchdog()` call site in all driver files).

### Changed

- Parent driver `updateDevices()` API-method routing centralized in `@Field` map — replaces inline string-substring matching with a single lookup table (c31c14c). No behavioral change.
- `releaseNotes` field in `levoitManifest.json` now cumulates per-version notes (matches HPM convention used by long-lived packages); the HPM update popup will show the full version history starting v2.0 forward (2a39b14).
- Hubitat community thread URL updated across docs to the active-maintenance thread (50bb123).
- README.md install-path text corrected — VeSync Integration is a driver, not an app (27a54be).

### Fixed

- **Poll cycle survives hub reboots (Bug Pattern #14).** The parent driver previously used a recursive `runIn()` chain to schedule the next poll — `runIn()` jobs are in-memory only and are not persisted across hub reboots. After any hub reboot, polling would stop permanently until the user clicked Save Preferences (or a device command fired). Fixed by replacing the `runIn()` chain with `schedule()` cron, which the Hubitat platform persists across reboots. A self-heal watchdog (`ensurePollWatchdog()`) auto-migrates pre-v2.2 installs to `schedule()`-based polling on the first poll tick or device command — no user action required for most users. **Known limitation:** on pre-v2.2 installs that have not yet polled or received a device command since upgrading, polling will not auto-resume after a hub reboot until the user clicks Save Preferences or fires a device command. Post-migration (after the first poll or command on v2.2), polling auto-resumes after every future reboot with no user action.
- **Debug logging stuck on indefinitely after hub reboots (Bug Pattern #16).** `runIn(1800, "logDebugOff")` in `updated()` — the mechanism that auto-disables debug after 30 minutes — is in-memory only and evaporates across hub reboots. `settings.debugOutput` persists on disk, so after a reboot the debug flag stays `true` forever with no further user action, generating ~2.75 log lines/second from API trace calls indefinitely. Discovered on maintainer hub `dev1064` during daily 02:15 MDT reboot cycle; debug was stuck for weeks before being caught manually. Fixed with the same architectural approach as BP14: `updated()` now records `state.debugEnabledAt = now()` when debug is enabled (cleared on disable), and a new `ensureDebugWatchdog()` method is called at the top of every poll/command entry point in all 17 drivers (parent + 16 children). The watchdog auto-disables debug when the elapsed time since enable exceeds 30 minutes, covering the post-reboot case. The existing `runIn(1800, "logDebugOff")` is preserved for the happy-path (no reboot within the 30-min window). Self-heal occurs within one poll cycle (default ≤30s) of the next interaction after a reboot. Logs `"BP16 watchdog: 30 min elapsed since debug enable; auto-disabling now (post-reboot self-heal)"` at INFO level when it fires.

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
- **Lint rule 22 (whitelist parity)** — new static lint at `tests/lint_rules/whitelist_parity.py` enforcing that every model code recognized by parent driver `deviceType()` is also recognized by `isLevoitClimateDevice()` (the Generic-driver fall-through whitelist). Catches both omissions and regressions at lint-time. Companion `@Unroll` Spock parity spec (18-row `where:` table) validates the same invariant at test-time (fe4d723).
- **18 additional Spock tests** for v2.1 driver bug fixes — Tower/Pedestal Fan FanControl-`"on"` enum coverage, Tower/Pedestal/Vital100S SwitchLevel `setLevel(0)→off()` coverage, Vital 100S/200S + Superior 6000S `state.lastSwitchSet` seeding + race-free toggle coverage. Final spec count: **446** (9d52179).

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
- **Tower Fan / Pedestal Fan `setSpeed("on")`** — was logging an error instead of powering on. Hubitat's `FanControl` capability standard enum includes `"on"`; now properly routes to `on()` (9d52179).
- **Tower Fan / Pedestal Fan / Vital 100S `setLevel(0)`** — was turning the device on at speed 1 instead of off. Hubitat `SwitchLevel` convention says 0% → off; now early-returns `off()` (9d52179).
- **Vital 100S/200S, Superior 6000S `toggle()`** — was prone to read-after-write race against `device.currentValue("switch")` lag. Added `state.lastSwitchSet` seeding in `on()`/`off()` and refactored `toggle()` to read the state variable first (matches Classic 300S canonical pattern; full V2-line sister-driver parity now) (9d52179).
- **Vital 100S `setLevel`** — now writes `state.speed` so `configureOnState` replay applies the correct named speed on the next `on()` (9d52179).
- **Generic-driver fall-through whitelist (`isLevoitClimateDevice`) missing `LUH-` prefix** — pre-existing v2.0 gap. Any unknown LUH- humidifier was silently skipped at discovery instead of falling through to the Generic driver. Added (fe4d723).
- **Generic-driver fall-through whitelist missing `Classic300S` literal** — some Classic 300S firmware reports this literal device-name instead of `LUH-A601S-*`. Added (fe4d723).
- **Groovy block-comment terminator bug in `VeSyncIntegration.groovy` Javadoc** — `LUH-O451S-*/LUH-O601S-*` contained `*/` which prematurely closed the Javadoc block, breaking Spock compilation. Changed slash to comma (fe4d723).

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
