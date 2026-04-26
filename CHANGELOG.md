# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
