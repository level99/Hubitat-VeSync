# Roadmap

What's planned for upcoming releases of the **Hubitat-VeSync** community fork. This is a living document; items may shift between releases as community demand surfaces, hardware becomes available for testing, or implementation effort gets re-estimated.

For what's already shipped, see [`CHANGELOG.md`](CHANGELOG.md). For day-to-day in-flight work, see the maintainer's local `TODO.md` (gitignored — not present in clones).

---

## v2.5 — next release candidates

### Upstream-pending items

- **Pyvesync PR #502 fold-in** once upstream merges — reconcile RGB nightlight `colorSliderLocation` anchor table + re-verify HSV-brightness adjustment against pyvesync's `_apply_brightness_to_rgb`.
- **Upstream pyvesync PR — regional code roll-up.** Single PR contributing model codes back upstream that we cover but pyvesync's `device_map.py` doesn't enumerate: `LAP-V201S-WUSR`, `LAP-V201S-WEUR` (Vital 200S), `LAP-C201S-WUSR` (Core 200S), `LAP-C401S-KUSR` (PlasmaPro 400S-P black), `LPF-R432S-AUK` (UK Pedestal Fan), `LTF-F362S-WUSR` (36-inch Tower Fan, with note that hardware is sibling of F422S). Low-controversy patch; eliminates 6 of our enumeration gaps in one upstream merge.
- **BP16 live-verified footer** — analogous to BP14's footer in `qa-agent.md`; add after first community report confirms post-reboot self-heal.
- **Plain (non-RGB) brightness nightlight for `LUH-O451S-WUSR`** — per pyvesync issue #500 refutation of the v2.1 "no nightlight (hardware lacks it)" CROSS-CHECK decision. WUSR variant DOES have a nightlight per the user report; investigate, capture/validate, re-add nightlight conditionally for that specific variant.
- **Hubitat platform bug BP20 — library `/* */` doc-header parser fails save.** Filed at [community thread 163611](https://community.hubitat.com/t/bug-report-library-save-returns-internal-error-on-doc-header-block-fw-2-4-4-156-2-5-0-126/163611). Workaround in place: lint RULE29 + `// line comments` in library source. **When Hubitat fixes the parser:** revert the in-source NOTE block + `// → /* */` conversion in `LevoitDiagnosticsLib.groovy`, drop RULE29 from lint, scrub BP20 mentions from CONTRIBUTING.md / CLAUDE.md / dev+QA agents.

### Conditional items (act on community signal)

- **OasisMist 1000S WEUR nightlight payload fallback** — only if community reports failure with the current pyvesync-class pattern. Pyvesync's WEUR fixture doesn't actually exercise nightlight, leaving the payload underspecified upstream. Fallback: switch to spkesDE's single-method `setNightLightBrightness {nightLightBrightness: N}` pattern (1-line change in `LevoitOasisMist1000S.groovy:setNightlight`).
- **Sprout Air VOC/CO2 attribute population verification** — driver declares `voc` + `co2` attributes (Sprout Air is Levoit's air-quality flagship per marketing) but pyvesync's device_map.py doesn't list explicit VOC/CO2 features. Driver is null-safe — attributes stay null if API doesn't return them. Action if community reports null values: confirm pyvesync feature gap + drop attributes (or document as marketing-aspirational).
- **EverestAir `setTimer`/`clearTimer` commands** — pyvesync `VeSyncAirBaseV2` exposes `set_timer`/`clear_timer`; v2.3 driver doesn't surface them. Cookie-cutter port from Tower Fan timer pattern.

---

## Beyond v2.5 — unscheduled

Items below are not yet locked to a release. They're available for community pickup or maintainer prioritization as time permits.

### Tower Fan write-path parity with Pedestal Fan

Port the confirmed Pedestal Fan write paths (`setChildLock`, `setSmartCleaningReminder`, plus already-shipped `setMute` / `setDisplay`) to Tower Fan once a Tower Fan tester is available. Maintainer doesn't own LTF-F422S / LTF-F362S; speculative additions deliberately avoided to prevent silent-failure scenarios for community Tower Fan owners.

**Unblocks on:** community Tower Fan owner volunteering for live-verification ([Levoit community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499)) OR maintainer hardware purchase.

**Plan once a tester is available:**
1. Run hardware-capture protocol: enable parent verboseDebug, send each candidate command, watch response codes.
2. Cross-reference pyvesync `VeSyncTowerFan` — features pyvesync already supports indicate the API method is confirmed; features pyvesync doesn't have are higher-risk speculative.
3. Port confirmed features to `LevoitTowerFan.groovy` with the verified payloads (likely identical to Pedestal Fan; may need minor field-name adjustments).
4. Update `LevoitTowerFanSpec.groovy` with parallel test coverage.
5. Resolve `displayingType` semantics in the same pass — currently a 0/1 toggle of unknown function. Toggle in mobile app, observe device behavior + status field, document, decide whether to expose.

**Tower Fan-only caveats (not on Pedestal Fan):**
- 1-axis oscillation only (no vertical) — `runOscillationCalibration` may not apply, or may apply differently
- workMode list differs slightly: `normal | turbo | auto | sleep` (no `eco`); `setLevelMemory` mode constraints need updating

**Upstream:** the pyvesync PR for Pedestal Fan write-path methods (in TODO.md) expands to cover Tower Fan once verified.

### Pedestal Fan write-path commands — refuted, awaiting API capture

Seven Pedestal Fan setter commands and the Tower Fan `setSleepPreference` are blocked on API-shape discovery. Each was attempted with educated-guess payloads against real hardware; all refuted by VeSync API inner code (method-doesn't-exist or payload-format-wrong). Read-side fields populate correctly on poll, so the device hardware tracks this state — the cloud write paths via guessed method names are simply wrong.

**Refuted attempts (don't repeat):**

| Command | Payloads tried | Inner code | Notes |
|---|---|---|---|
| `setTimer` | `{action:"on"\|"off", total:N}` | -1 | pyvesync has no timer methods; HA PR #163353 still open |
| `cancelTimer` | `clearTimer + {}` | -1 | Paired with setTimer failure |
| `setSleepPreference` | flat `{sleepPreferenceType}` AND nested `{sleepPreference:{...}}` | 11000000 | Both "advanced" and "default" values tried; same shape on Tower Fan |
| `setHighTemperatureThreshold` | `setHighTemperature + {highTemperature: degF×10}` | -1 | |
| `setHighTemperatureReminder` | `{highTemperatureReminderState: 1\|0}` | -1 | |
| `setLevelMemory` | `{workMode, level, enable}` | -1 | |
| `runOscillationCalibration` | `oscillationCalibration + {}` | -1 | May be local-network only |

**Hypothesis:** VeSync mobile app likely uses a different API namespace for several of these — possibly "schedule"-style for timer-related operations, possibly local-network (not cloud) for oscillation calibration.

**Resolution path:** mitmproxy capture of the VeSync mobile app's actual request for each feature. iOS/Android with the app installed; trigger each feature once; capture the corresponding `/cloud/v2/deviceManaged/bypassV2` request body. Read-only attributes for these fields remain declared in the driver so users can see device state.

### Coverage gaps awaiting external resolution

Four Levoit devices we don't cover, all blocked on external work — pyvesync upstream needs to enumerate the model code, OR a community member needs to share a `state.deviceType` capture from a real device, OR the product hasn't shipped publicly yet.

| Device | Model code(s) | Likely pyvesync class | Blocker |
|---|---|---|---|
| Classic 36-Inch Smart Tower Fan | `LTF-F362S-WUSR` (+ likely region siblings) | Likely `VeSyncTowerFan` (same as 42-inch `LTF-F422S`) | pyvesync has not yet enumerated the dev_type. Either wait for upstream, or one community owner can share their device's `state.deviceType` from a Hubitat Generic-driver install + a debug-log payload sample to confirm the class is identical. May need fan-level cap adjustment if the smaller fan has fewer than 12 speeds. |
| Core Mini / Core Mini-P | `LAP-C161-WUS`, `LAP-C161-KUS` | Unknown — could be `VeSyncAirBypass` (Tier 2 sibling of Core 200S) OR could be BLE-only | pyvesync has zero entries for `LAP-C161`. First action: open a pyvesync issue with a deviceType capture from a real Mini, or wait for a community report. |
| CirculAir Oscillating Fan (2-in-1 pedestal/tabletop) | Model code unknown publicly (likely `LSF-*` or `LCF-*` per naming pattern) | Unknown | Model code itself isn't surfaced in any retailer listing reviewed. Need a community owner to share `state.deviceType` from a Hubitat install via existing parent's Generic-driver fall-through. Pyvesync class TBD. |
| Pet Odor & Hair Air Purifier (CES 2025 announce, Q2 2026 launch) | TBD — not yet shipping | Likely `VeSyncAirBaseV2` sibling but unverified | Product hasn't shipped publicly yet (per Levoit's CES 2025 press release). Watch upstream pyvesync `device_map.py` for the new dev_type entry once retail availability begins. |

If you own one of these and your Hubitat hub is connected to the VeSync cloud via this fork's parent driver, please share the `state.deviceType` value (visible on the Generic child device's State Variables) on the [Hubitat community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) — that's what unblocks adding support.

### Tier 4 — out-of-scope unless demand surfaces

- **LV-PUR131S** / **LV-RH131S** — older WiFi 131 line. Different API entirely (NOT bypassV2). Older Wi-Fi cloud protocol with different envelope; would need a separate `sendV1Request` path. Substantial separate code path for products that are EOL (~2018-2020 generation). **Recommend SKIP** unless explicit community demand justifies the infrastructure work.

### Deferred model code variants (low-urgency)

Two additional model codes present in pyvesync `device_map.py` that fall through to the Generic driver today. Low risk to add; low urgency.

| Code | Likely dtype | Reason deferred |
|---|---|---|
| `LAP-C301S-WAAA` | `300S` | Unknown region suffix `WAAA`; pyvesync supports it but suffix meaning is undocumented. Routing it to Core 300S is almost certainly correct, but without a community hardware report it's hard to confirm. |
| `LAP-C302S-WGC` | `300S` | Unknown region suffix `WGC`; same reasoning. `C302S` is the bundle SKU family (`LAP-C302S-WUSB` was added in v2.2 for US); `WGC` could be a China mainland variant. |

Community hardware reports (a debug log showing the model code + `captureDiagnostics` output) will confirm routing and unblock adding these in the next polish round.

### Architectural — library extraction refactor (6-phase, headline architectural item)

Driver duplication is significant: 22 child drivers carry ~4,500-5,000 lines of duplicated logic — cross-cutting boilerplate (HTTP plumbing, log helpers, lifecycle hooks, BP16 watchdogs, BP18 null-guards) plus per-class parallel implementations (Core / Vital / Fan / V1-humidifier / V2-humidifier line `setLevel` / `setSpeed` / parsers / mode handling). Extracting into a general library + 5 class libraries collapses the duplication, eliminates the recurring "BP-N applies to N drivers, fix in N places" bug shape, and meaningfully reduces LLM-token cost when working on the repo.

**Phase plan (each phase is its own dev/QA/tester cycle, its own PR):**

1. **`LevoitChildBaseLib.groovy`** — general/cross-cutting helpers (HTTP plumbing, log helpers, lifecycle hooks, `toggle`, BP16 `ensureDebugWatchdog`, BP23 `ensureSwitchOn`, BP18 null-guard, 2-arg `setLevel(val, duration)` overload). Touches 16-24 drivers; ~1,500-2,000 lines collapsed into ~250-line library. Phase 1 also includes a one-time validation experiment to confirm Hubitat library `local-override-precedence` behavior — drivers needing to deviate from a library function need a known mechanism.
2. **`LevoitCorePurifierLib.groovy`** — Core 200S/300S/400S/600S shared `setLevel`/`setSpeed`/`handleSpeed`/`setMode`/parsers/AQ/PM2.5 logic. Most homogeneous class; ~1,400 lines dedup.
3. **`LevoitV1HumidifierLib.groovy`** — Classic 200S/300S, Dual 200S, LV600S, OasisMist 450S shared mist-level / mode / parser logic. ~1,000 lines dedup.
4. **`LevoitV2HumidifierLib.groovy`** — Superior 6000S, LV600S Hub Connect, OasisMist 1000S, Sprout Humidifier shared logic. ~800 lines dedup.
5. **`LevoitVitalPurifierLib.groovy`** — Vital 100S/200S shared logic. ~700 lines dedup.
6. **`LevoitFanLib.groovy`** — Tower / Pedestal Fan shared logic. ~500 lines dedup.

**Total dedup:** ~4,500 lines collapsed into ~1,300 lines of library code; net driver-codebase line-count drops from 17,471 → ~13,000 (-26%). Each future BP-N collapses to a 1-fix instead of N. Retroactively dedupes BP1 (10 drivers), BP12 (5 shapes), BP14 (parent + every child command path), BP16 (every child), BP18 (17 method sites × 13 drivers), BP23 (8 drivers).

**Tradeoffs:** Hubitat library files carry BP20 platform-parser exposure (lint RULE29 + ops-agent library-deploy smoke-test mitigate). Library include semantics don't support method override; drivers needing per-instance variation either parameterize the library function or override locally (Phase 1 validates the override-precedence behavior). Each library ships via HPM as `required: true` (precedent: `LevoitDiagnosticsLib.groovy` in v2.4).

**Sequencing:** Phase 1 in v2.5 (highest leverage, lowest risk). Phase 2 (Core line) in v2.6. Phases 3-6 staged across v2.7+ based on bandwidth and any new BP-N pressure. ~30-50 hours total work; each phase ~6-10 hr.

**Don't bundle phases.** Each library is its own architecture decision with its own validation surface. Discrete phases let learnings from Phase 1 inform Phase 2+ design.

### Tooling & dev experience

- **NUMBER-input "NaN" UI quirk on `setSpeed`/`setMistLevel`/`setHumidity`/etc.** Hubitat's device-page command card renders `<input type="number">` for `NUMBER`-typed parameters. With no value bound, browsers display "NaN" until the user types. Affects every `NUMBER` command across this fork (Pedestal/Tower Fan `setSpeed`, all humidifiers' `setMistLevel` + `setHumidity`, EverestAir/Sprout `setFanSpeed`, etc.) and is a Hubitat platform behavior, not a per-driver bug. Speculative `range:` / `defaultValue:` keys on the parameter map were tried in v2.4 and don't take effect (not in Hubitat's documented command-parameter spec). Possible v2.5+ fix: convert `setSpeed` (and similar) to `ENUM` with explicit string constraints — gives a dropdown, eliminates NaN. Tradeoff: programmatic callers must handle string-typed input (a 1-line `setSpeed(val) { setSpeed(val as Integer) }` shim covers it). FanControl capability's own `setSpeed(named)` is unaffected. Worth doing as a cross-driver UX polish pass when a v2.5+ window opens.
- **PyvesyncCoverageSpec — class-source introspection (post-MVP).** Current MVP is fixture-vs-fixture parity (method name + data key set). Misses: payload data values, response field coverage, structural depth, and class-source-vs-port mismatches like the OasisMist 1000S WEUR nightlight ambiguity (pyvesync class CODE has the split but the FIXTURE doesn't exercise it). Extension: parse pyvesync's `vesyncfan.py` symbolically, compare method signatures + payload-builder logic against our drivers, surface gaps the fixture-only gate can't see. Higher build cost; do only if the auto-tracking bot's Output A/B reveals a real need.

### Speculative / unresolved API questions

Open questions about VeSync API behavior that we haven't fully resolved. If you find answers, update this section in the same PR.

1. **Why Superior 6000S responses double-wrap and Vital 200S responses don't.** Both use bypassV2. Pyvesync handles both via `process_bypassv2_result` helper that abstracts the unwrap. The actual cloud-side reason isn't documented.
2. **Whether VeSync rate-limits per-account or per-IP.** Empty-result polls are common (~10-30% on healthy installs). We assume rate-limiting; could also be transient cloud failures. Real cap is unknown.
3. **Whether older firmware on Vital 200S returns the same fields.** Our diagnostic-confirmed field set is from one user's device. Older units might have different fields; the V102S fixture in pyvesync suggests the field set has evolved over firmware versions.
4. **The `traceId` field's significance.** The parent driver currently sends a hardcoded value (inherited from upstream and ultimately from pyvesync). If VeSync ever validates this, the integration breaks.
5. **Whether EU VeSync accounts require the EU host (`smartapi.vesync.eu`) for initial login, or only for device commands.** Region routing via the `VeSync API region` preference is in place; the login-vs-command distinction is unconfirmed. Community EU-hardware reports welcome on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).

### Adjacent product directions

- **Alexa-aware capability surfaces.** Drivers currently expose standard capabilities (Switch, SwitchLevel, AudioVolume, etc.) — Alexa interprets these via Hubitat's Echo Skill but with limited semantic richness. Adding richer capabilities like `MediaInputSource` might give voice-control polish. Worth a research pass + pilot on one driver.
- **Migration guide for users coming from a Home Assistant + Homebridge bridge.** Some community users used HA-as-bridge to get Vital 200S working before our v2.0 native support; a migration-from-bridge guide could ease their transition.

### Far backlog — maybe (not committed)

Items that have been considered but aren't priorities. Recorded so they don't get re-proposed without context. Build only if a specific need emerges.

- **Release automation Tier 1 — `scripts/release.sh`.** A bash/Python implementation of the mechanical parts of the `/cut-release` slash command (version bumps + lockstep + manifest reconciliation + drift detection). Doesn't replace the prose-generation parts (releaseNotes content, CHANGELOG bullets, PR body) which still need a human or Claude session. Maintainer always uses the Claude-driven flow today, so this only matters if the maintainer wants to cut releases outside Claude OR a non-Claude fork wants its own release flow. ~2-3 hr build cost; net ROI low at current scale.

---

## Critical naming traps

When adding new device support, watch for these — they've bitten contributors before:

- **`LUH-A602S` vs `LUH-A603S`** — both branded "LV600S", but DIFFERENT pyvesync classes (`VeSyncHumid200300S` vs `VeSyncLV600S`). Different mode-name conventions.
- **`LEH-S601S` vs `LUH-S601S` vs `LUH-M101S`** — Superior 6000S vs OasisMist 1000S, completely different products with confusingly similar codes. Parent `deviceType()` regex matching needs to be precise.
- **`LUH-O451S` vs `LUH-O601S`** — pyvesync groups `-O601S` under OasisMist 450S (NOT OasisMist 1000S) per real-device behavior.
- **`VeSyncHumid200S` vs `VeSyncHumid200300S`** — different classes for Classic 200S vs Classic 300S/Dual 200S/LV600S/OasisMist 450S. The "200/300" in the class name is concatenation, not a range.

---

## Things we explicitly won't do

- **Don't trust Homebridge plugin code as canonical.** Pyvesync only.
- **Don't change `name:` metadata of an existing driver.** Always add new drivers; never rename. (The Hubitat platform associates devices to drivers by name; changing it orphans every device the maintainer has installed.)
- **Don't hardcode hub IPs, device IDs, or other per-install identifiers** in committed files (agent definitions, fixtures, etc.). Per-install context belongs in local config or test fixtures only.

---

## How to contribute to a roadmap item

Each item here is fair game for a community PR. The general path:

1. **Read [`CLAUDE.md`](CLAUDE.md)** for the dev/QA/tester pipeline and conventions.
2. **Pyvesync first.** For any new device support, start at [pyvesync](https://github.com/webdjoe/pyvesync) — `src/pyvesync/device_map.py` for device class assignment, `src/tests/api/...` for canonical request/response fixtures. Don't trust other reverse-engineered clients as the source of truth.
3. **Open an issue** before doing a large diff so we can confirm scope + sequencing (e.g. "I want to add &lt;new device&gt; — does that fit the next release or should it land later?").
4. **Run the Spock harness + lint locally** before opening a PR. CI gates both, but local fast-feedback saves round-trips.
5. **Live-test if you have the hardware.** If you don't, note that in the PR — the maintainer or another community user with matching hardware can test before merge.
