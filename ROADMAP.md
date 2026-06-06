# Roadmap

What's planned for upcoming releases of the **Hubitat-VeSync** community fork. This is a living document; items may shift between releases as community demand surfaces, hardware becomes available for testing, or implementation effort gets re-estimated.

For what's already shipped, see [`CHANGELOG.md`](CHANGELOG.md). For day-to-day in-flight work, see the maintainer's local `TODO.md` (gitignored — not present in clones).

---

## v2.9 — next release candidates

Items locked to the next release because they're internally actionable (no external blockers).

### Refactor / lint coverage

- **RULE37 / RULE39 deferred-coverage scope decisions.** Three theoretical bypass shapes are known but unexercised by any current driver or CHANGELOG and are intentionally not covered: snake_case setter names; multi-param command methods where only the first arg is guarded (the rest's casts aren't checked); and a `####` sub-header nested inside a changelog section (which wouldn't reset the section-scope tracker). All three are deliberate scope calls — widen reactively if a real instance ever appears.
- **RULE39's internal-jargon name set is curated-small by design, not exhaustive.** Compound-camelCase identifiers that can legitimately appear in technical `### Fixed`/`### Added` prose — VeSync API fields, parent-driver internals, API method names — are deliberately excluded so the rule doesn't strip audience-meaningful fix-mechanism detail that power-users and Maker API integrators rely on. Grow the set only when such a name lands in prose as a jargon-dump rather than as fix-explanation context.
- **APP_VERSION ↔ pyvesync drift monitoring lint rule (BP27 pattern).** The v2.7 release migrated the auth flow to pyvesync 3.4.1's two-stage OAuth after VeSync deprecated the legacy `/cloud/v1/user/login` endpoint via an appVersion gate. The coverage sub-agent flagged the lack of an automated drift-detection lint rule (compare this fork's `@Field static final String APP_VERSION` against pyvesync's `src/pyvesync/const.py` `APP_VERSION` at lint time). Deferred per the sub-agent's own calibration: a missing lint rule for a NEW bug-pattern category is WARN-not-FAIL on the first occurrence; the rule should be added when the pattern recurs. Add when (a) VeSync deprecates a second endpoint via the same appVersion gate (BP27 catalog entry would then have a `Fix scope: class-wide` shape), or (b) a non-trivial pyvesync APP_VERSION update is shipped and we miss the drift signal. Mechanism: a lint rule that fetches pyvesync's const.py from GitHub at lint time (offline mode tolerates fetch failure) and compares the two values; a delta of more than one minor version surfaces a WARN. Verified 2026-05-29: no drift — fork `APP_VERSION` `5.6.60` matches pyvesync 3.4.2 `const.py`.

---

## Awaiting external input — release-agnostic

Items here can ship in any future release once the external signal arrives. Listed without a release-version label because we can't anticipate when the signal lands. Each item names the specific signal that unblocks it.

### Upstream pyvesync (merge / patch pending)

- **Pyvesync PR #502 fold-in.** Reconciles RGB nightlight `colorSliderLocation` anchor table + re-verifies HSV-brightness adjustment against pyvesync's `_apply_brightness_to_rgb`. **Status:** verified still OPEN upstream as of 2026-05-29 (head ref `dev`, last activity 2026-05-27). Wait for merge.
- **Upstream pyvesync PR — regional code roll-up.** Single PR contributing model codes back upstream that we cover but pyvesync's `device_map.py` doesn't enumerate: `LAP-V201S-WUSR`, `LAP-V201S-WEUR` (Vital 200S), `LAP-C201S-WUSR` (Core 200S), `LAP-C401S-KUSR` (PlasmaPro 400S-P black), `LPF-R432S-AUK` (UK Pedestal Fan), `LTF-F362S-WUSR` (36-inch Tower Fan, with note that hardware is sibling of F422S). Low-controversy patch; eliminates 6 of our enumeration gaps in one upstream merge. **Action:** we can write the patch ourselves and file the PR; benefit unlocks once pyvesync maintainer merges.

### Hubitat platform (parser fix pending)

- **Hubitat platform bug BP20 — library `/* */` doc-header parser fails save.** Filed at [community thread 163611](https://community.hubitat.com/t/bug-report-library-save-returns-internal-error-on-doc-header-block-fw-2-4-4-156-2-5-0-126/163611). Workaround in place: lint RULE29 + `// line comments` in library source. Verified still unfixed as of 2026-05-29 (bug thread shows no Hubitat-staff fix; current release notes don't mention a library-parser fix). **When Hubitat fixes the parser:** revert the in-source NOTE block + `// → /* */` conversion in `LevoitDiagnosticsLib.groovy`, drop RULE29 from lint, scrub BP20 mentions from CONTRIBUTING.md / CLAUDE.md / dev+QA agents.

### Community signal (user report needed)

- **OasisMist 1000S WEUR nightlight payload fallback** — only if community reports failure with the current pyvesync-class pattern. Pyvesync's WEUR fixture doesn't actually exercise nightlight, leaving the payload underspecified upstream. Fallback: switch to spkesDE's single-method `setNightLightBrightness {nightLightBrightness: N}` pattern (1-line change in `LevoitOasisMist1000S.groovy:setNightlight`).
- **Sprout Air VOC/CO2 attribute population verification** — driver declares `voc` + `co2` attributes (Sprout Air is Levoit's air-quality flagship per marketing) but pyvesync's device_map.py doesn't list explicit VOC/CO2 features. Driver is null-safe — attributes stay null if API doesn't return them. Action if community reports null values: confirm pyvesync feature gap + drop attributes (or document as marketing-aspirational).

### Hardware / API capture availability

- **Plain (non-RGB) brightness nightlight for `LUH-O451S-WUSR`** — per pyvesync issue #500 refutation of the v2.1 "no nightlight (hardware lacks it)" CROSS-CHECK decision. WUSR variant DOES have a nightlight per the user report; investigate, capture/validate, re-add nightlight conditionally for that specific variant.
- **V2-line humidifier `setMode` BP24 classification (4 drivers).** LV600SHC, OasisMist 1000S, Sprout, Sup6000S — currently SKIP-OK pending live-capture. V2 firmware is suspected to reject mode changes while off (same as Vital line); needs confirmation via mitmproxy.
- **Tower Fan write-path parity with Pedestal Fan.** Port the confirmed Pedestal Fan write paths (`setChildLock`, `setSmartCleaningReminder`, plus already-shipped `setMute` / `setDisplay`) to Tower Fan once a Tower Fan tester is available. Maintainer doesn't own LTF-F422S / LTF-F362S; speculative additions deliberately avoided to prevent silent-failure scenarios for community Tower Fan owners.

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

- **Pedestal Fan write-path commands — refuted, awaiting API capture.** Seven Pedestal Fan setter commands and the Tower Fan `setSleepPreference` are blocked on API-shape discovery. Each was attempted with educated-guess payloads against real hardware; all refuted by VeSync API inner code (method-doesn't-exist or payload-format-wrong). Read-side fields populate correctly on poll, so the device hardware tracks this state — the cloud write paths via guessed method names are simply wrong.

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

- **Coverage gaps — 4 Levoit devices unsupported.** Each blocked on external work: pyvesync upstream enumeration, community `state.deviceType` capture from real device, or product not yet shipping publicly.

  | Device | Model code(s) | Likely pyvesync class | Blocker |
  |---|---|---|---|
  | Classic 36-Inch Smart Tower Fan | `LTF-F362S-WUSR` (+ likely region siblings) | Likely `VeSyncTowerFan` (same as 42-inch `LTF-F422S`) | pyvesync has not yet enumerated the dev_type. Either wait for upstream, or one community owner can share their device's `state.deviceType` from a Hubitat Generic-driver install + a debug-log payload sample to confirm the class is identical. May need fan-level cap adjustment if the smaller fan has fewer than 12 speeds. |
  | Core Mini / Core Mini-P | `LAP-C161-WUS`, `LAP-C161-KUS` | Unknown — could be `VeSyncAirBypass` (Tier 2 sibling of Core 200S) OR could be BLE-only | pyvesync has zero entries for `LAP-C161`. First action: open a pyvesync issue with a deviceType capture from a real Mini, or wait for a community report. |
  | CirculAir Oscillating Fan (2-in-1 pedestal/tabletop) | Model code unknown publicly (likely `LSF-*` or `LCF-*` per naming pattern) | Unknown | Model code itself isn't surfaced in any retailer listing reviewed. Need a community owner to share `state.deviceType` from a Hubitat install via existing parent's Generic-driver fall-through. Pyvesync class TBD. |
  | Pet Odor & Hair Air Purifier (CES 2025 announce, Q2 2026 launch) | TBD — not yet shipping | Likely `VeSyncAirBaseV2` sibling but unverified | Product hasn't shipped publicly yet (per Levoit's CES 2025 press release). Watch upstream pyvesync `device_map.py` for the new dev_type entry once retail availability begins. |

  If you own one of these and your Hubitat hub is connected to the VeSync cloud via this fork's parent driver, please share the `state.deviceType` value (visible on the Generic child device's State Variables) on the [Hubitat community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) — that's what unblocks adding support.

---

## Beyond next release — unscheduled

Items below are not yet locked to a release. They're available for community pickup or maintainer prioritization as time permits.

### Tier 4 — out-of-scope unless demand surfaces

- **LV-PUR131S** / **LV-RH131S** — older WiFi 131 line. Different API entirely (NOT bypassV2). Older Wi-Fi cloud protocol with different envelope; would need a separate `sendV1Request` path. Substantial separate code path for products that are EOL (~2018-2020 generation). **Recommend SKIP** unless explicit community demand justifies the infrastructure work.

### User-visible polish

- **BREAKING (future major) — Unify `airQuality` attribute type across Core and Vital/Generic families.** Core 300S/400S/600S emit `airQuality` as a **number** (US AQI, 0–500, same value as `aqi`). Vital 100S/200S and Generic emit `airQuality` as a **string** (categorical label: "good", "moderate", "poor", "very poor"). The two representations are incompatible for cross-family Rule Machine conditions and dashboard bindings. The correct long-term fix is to pick one canonical type — most likely **number (AQI)** for all purifier families — and migrate the Vital/Generic drivers from the categorical string to the computed AQI value. This is BP9-breaking: Vital's string `airQuality` has shipped since v2.1; changing its type orphans any existing Rule Machine conditions or dashboard tiles that compare it as a string (e.g., `IF airQuality = "good"`). Deferred out of the v2.6 additive line on those grounds. Schedule for a future major release where BREAKING changes are expected and documented in the migration guide.

- **Hide `duration` parameter from `setLevel` UI on Levoit drivers (UX polish).** Hubitat's `SwitchLevel` capability advertises `setLevel(level, duration)` in the device-page command card and Rule Machine "Set Level" action. For VeSync devices the duration parameter is a no-op (no hardware fade exists; we ignore it). Showing the input field anyway is bad UX — users may enter a value expecting it to take effect. Fix pattern: declare a custom command override alongside the capability declaration: `command "setLevel", [[name: "Level*", type: "NUMBER", description: "0-100"]]`. This narrows the device-page UI to a single Level field while leaving the capability contract (and the 2-arg implementation that v2.4 added) intact. Caveats: the override may not affect Rule Machine's "Set Level" action editor (RM uses its own action-template system); per-Hubitat-version variance — older platform versions ignore command overrides on capability-provided methods. Pre-flight verification on FW 2.5.0.126+ before applying across all 11 affected drivers.
- **Child-side error log dedup during parent-level network outages.** When parent's `sendBypassRequest` returns false during an outage (BP22 catches and dedupes parent-side), the calling child driver still logs its own ERROR ("Speed write failed for level X" + "HTTP -1"). For a Pedestal Fan being re-driven by a Room Lighting rule with motion retrigger, every motion event during the outage produces 2 ERROR lines on the child even though parent had already logged the BP22 first-fire WARN. Fix: child drivers check parent's outage state (e.g., `parent.isNetworkUnreachable()` helper) and downgrade their own write-failure ERRORs to DEBUG during a known outage.
- **BP22 long-outage WARN cadence + probe-interval residual stalls.** At the 1-hour mark of a prolonged outage, the hourly-WARN re-surface fires several times in quick succession (1-min intervals) instead of once — concurrent `updateDevices()` cycles read stale `state.lastNetworkWarnAt`. Same root-cause class as occasional probe-attempt bunching at 1-min intervals (instead of the intended 5-min cadence). Both are state-write-flush timing issues during 120s+ poll cycles. Possible fixes: epoch-hour bucket integer for the cadence timestamp, or `@Field static` (downside: doesn't survive driver redeploy), or `runIn(0, ...)` cycle coalescing. Log volume is dramatically improved over the pre-BP22 baseline; this is residual polish only.
- **Manual-install path documentation.** For users without HPM (paste-from-GitHub flow). Order matters: install libraries FIRST (drivers will fail to compile if they `#include` a library that isn't installed yet), then drivers, then create the parent app device. Add a section to the README and the Hubitat community thread OP covering: per-library raw-URL list, per-driver raw-URL list, troubleshooting "missing library" symptoms (`#include level99.LevoitXxx not found` → install the library from the Libraries Code section). Caveat for users on this path: manual install means they have to know to install each new library themselves when a release adds one; HPM does this automatically on Update.

### Tooling & dev experience

- **NUMBER-input "NaN" UI quirk on `setSpeed`/`setMistLevel`/`setHumidity`/etc.** Hubitat's device-page command card renders `<input type="number">` for `NUMBER`-typed parameters. With no value bound, browsers display "NaN" until the user types. Affects every `NUMBER` command across this fork (Pedestal/Tower Fan `setSpeed`, all humidifiers' `setMistLevel` + `setHumidity`, EverestAir/Sprout `setFanSpeed`, etc.) and is a Hubitat platform behavior, not a per-driver bug. Speculative `range:` / `defaultValue:` keys on the parameter map were tried in v2.4 and don't take effect (not in Hubitat's documented command-parameter spec). Possible v2.5+ fix: convert `setSpeed` (and similar) to `ENUM` with explicit string constraints — gives a dropdown, eliminates NaN. Tradeoff: programmatic callers must handle string-typed input (a 1-line `setSpeed(val) { setSpeed(val as Integer) }` shim covers it). FanControl capability's own `setSpeed(named)` is unaffected. Worth doing as a cross-driver UX polish pass when a v2.5+ window opens.
- **PyvesyncCoverageSpec — class-source introspection (post-MVP).** Current MVP is fixture-vs-fixture parity (method name + data key set). Misses: payload data values, response field coverage, structural depth, and class-source-vs-port mismatches like the OasisMist 1000S WEUR nightlight ambiguity (pyvesync class CODE has the split but the FIXTURE doesn't exercise it). Extension: parse pyvesync's `vesyncfan.py` symbolically, compare method signatures + payload-builder logic against our drivers, surface gaps the fixture-only gate can't see. Higher build cost; do only if the auto-tracking bot's Output A/B reveals a real need.

### Speculative / unresolved API questions

Open questions about VeSync API behavior that we haven't fully resolved. If you find answers, update this section in the same PR.

1. **Why Superior 6000S responses double-wrap and Vital 200S responses don't.** Both use bypassV2. Pyvesync handles both via `process_bypassv2_result` helper that abstracts the unwrap. The actual cloud-side reason isn't documented.
2. **Whether VeSync rate-limits per-account or per-IP.** Empty-result polls are common (~10-30% on healthy installs). We assume rate-limiting; could also be transient cloud failures. Real cap is unknown.
3. **Whether older firmware on Vital 200S returns the same fields.** Our diagnostic-confirmed field set is from one user's device. Older units might have different fields; the V102S fixture in pyvesync suggests the field set has evolved over firmware versions.
4. **Whether EU VeSync accounts require the EU host (`smartapi.vesync.eu`) for initial login, or only for device commands.** Region routing via the `VeSync API region` preference is in place; the login-vs-command distinction is unconfirmed. Community EU-hardware reports welcome on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).

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
