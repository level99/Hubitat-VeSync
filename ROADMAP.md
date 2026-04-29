# Roadmap

What's planned for upcoming releases of the **Hubitat-VeSync** community fork. This is a living document; items may shift between releases as community demand surfaces, hardware becomes available for testing, or implementation effort gets re-estimated.

For what's already shipped, see [`CHANGELOG.md`](CHANGELOG.md). For day-to-day in-flight work, see the maintainer's local `TODO.md` (gitignored — not present in clones).

---

## v2.4 — next release (TBD)

### Tooling — primary v2.4 work item

- **Pyvesync upstream-tracking automation** — IN-FLIGHT on `release/v2.4`. Scheduled GitHub Actions workflow at `.github/workflows/pyvesync-tracker.yml` with implementation in `tools/pyvesync-tracker/`. Two outputs sharing a single weekly cron run:
  - **Output A — auto-refresh PR.** When pyvesync ships a new tag, the bot opens a `chore: bump pyvesync to <tag>` PR that refreshes the 19 vendored YAMLs in `tests/pyvesync-fixtures/` and updates the pinned-commit reference. Existing CI (Lint + Spock + PyvesyncCoverageSpec) runs on the PR — green = safe to merge, red = real upstream divergence flagged for investigation. Removes the manual refresh step that v2.3 left as the only weak point in the CoverageSpec design.
  - **Output B — new-device-detect issue.** Same bot diffs pyvesync's `device_map.py` between old and new tag, filtered to Levoit prefixes (`LAP-`, `LUH-`, `LEH-`, `LTF-`, `LPF-`, `LV-`) plus the literal device types we recognize. If new Levoit entries appear, opens an issue tagged `auto-filed`, `new-device-support`, `upstream-tracking` — body lists new model codes + their pyvesync class assignments + diff link. Tier 2 grep filter; maintainer does the fold-in-vs-new-driver call from the surfaced issue.

  Activates on default-branch merge: schedule fires once `release/v2.4` merges to `main`. See `tools/pyvesync-tracker/README.md` for the entry-points + dry-run instructions.

### Hardware-pending items (carryforward from v2.3)

- **Pedestal Fan write-path completion** — `setDisplay`, `setMute`, `setChildLock`, timer payload. Awaiting maintainer's Pedestal Fan arrival to capture canonical request shapes.
- **Tower Fan `displayingType` semantics resolution** — currently a 0/1 toggle of unknown function. Toggle in mobile app, observe device behavior + status field, document, decide whether to expose as user-facing command.

### Upstream-pending items (carryforward from v2.3)

- **Pyvesync PR #502 fold-in** once upstream merges — reconcile RGB nightlight `colorSliderLocation` anchor table + re-verify HSV-brightness adjustment against pyvesync's `_apply_brightness_to_rgb`.
- **Upstream pyvesync PR — regional code roll-up.** Single PR contributing model codes back upstream that we cover but pyvesync's `device_map.py` doesn't enumerate: `LAP-V201S-WUSR`, `LAP-V201S-WEUR` (Vital 200S), `LAP-C201S-WUSR` (Core 200S), `LAP-C401S-KUSR` (PlasmaPro 400S-P black), `LPF-R432S-AUK` (UK Pedestal Fan), `LTF-F362S-WUSR` (36-inch Tower Fan, with note that hardware is sibling of F422S). Low-controversy patch; eliminates 6 of our enumeration gaps in one upstream merge.
- **BP16 live-verified footer** — analogous to BP14's footer in `qa-agent.md`; add after first community report confirms post-reboot self-heal.
- **Plain (non-RGB) brightness nightlight for `LUH-O451S-WUSR`** — per pyvesync issue #500 refutation of the v2.1 "no nightlight (hardware lacks it)" CROSS-CHECK decision. WUSR variant DOES have a nightlight per the user report; investigate, capture/validate, re-add nightlight conditionally for that specific variant.

### v2.3 post-cut WATCH items (queued during the v2.3 cut-release pre-flight + cross-source validation)

- **`getPurifierStatus` empty-response ERROR log dedup/downgrade** — pre-existing v2.0+ behavior; one offline child drives ~1 ERROR/min when the device is unplugged or off the VeSync cloud. Surfaced by the v2.3 production-log audit. Downgrade to WARN or dedup per-device per session so the log doesn't spam an error per minute for a known-offline scenario.
- **OasisMist 1000S WEUR nightlight payload fallback** — only if community reports failure with the current pyvesync-class pattern. Pyvesync's WEUR fixture doesn't actually exercise nightlight, leaving the payload underspecified upstream. Fallback: switch to spkesDE's single-method `setNightLightBrightness {nightLightBrightness: N}` pattern (1-line change in `LevoitOasisMist1000S.groovy:setNightlight`).
- **Sprout Air VOC/CO2 attribute population verification** — driver declares `voc` + `co2` attributes (Sprout Air is Levoit's air-quality flagship per marketing) but pyvesync's device_map.py doesn't list explicit VOC/CO2 features. Driver is null-safe — attributes stay null if API doesn't return them. Action if community reports null values: confirm pyvesync feature gap + drop attributes (or document as marketing-aspirational).
- **EverestAir `setTimer`/`clearTimer` commands** — pyvesync `VeSyncAirBaseV2` exposes `set_timer`/`clear_timer`; v2.3 driver doesn't surface them. Cookie-cutter port from Tower Fan timer pattern.

---

## Beyond v2.4 — unscheduled

Items below are not yet locked to a release. They're available for community pickup or maintainer prioritization as time permits.

### Coverage gaps identified (v2.2 audit, 2026-04-27)

A comprehensive audit of Levoit's current Wi-Fi lineup against pyvesync `device_map.py` surfaced four additional devices we don't cover and aren't in our queue. All four are blocked on external work — either pyvesync upstream needs to enumerate the model code, or a community member needs to share a `state.deviceType` capture from a real device so we can confirm class assignment, or the product hasn't shipped publicly yet.

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

### Tooling & dev experience

- **PyvesyncCoverageSpec — class-source introspection (post-MVP).** Current MVP is fixture-vs-fixture parity (method name + data key set). Misses: payload data values, response field coverage, structural depth, and class-source-vs-port mismatches like the OasisMist 1000S WEUR nightlight ambiguity (pyvesync class CODE has the split but the FIXTURE doesn't exercise it). Extension after the v2.4 auto-tracking bot lands: parse pyvesync's `vesyncfan.py` symbolically, compare method signatures + payload-builder logic against our drivers, surface gaps the fixture-only gate can't see. Higher build cost than the v2.4 bot; queue for v2.5+ depending on whether Output A/B output reveals a need.
- **Virtual test parent driver** — sibling driver to `VeSyncIntegration.groovy` that returns canned pyvesync-fixture data instead of talking to the cloud. Lets contributors exercise child-driver parser paths end-to-end without owning the hardware. Worth building if hardware coverage expands or if multiple new device types need to land in succession.
- **Pyvesync local Python harness** — small Python script that diff's our driver payloads against pyvesync's canonical request/response shapes. Useful as a CI gate; partly subsumed by the v2.3 PyvesyncCoverageSpec — revisit after the v2.4 auto-tracking bot lands to decide whether a Python-side complement adds value.
- **Release automation Tier 1** — `scripts/release.sh` plus seeded `CHANGELOG.md`. The `/cut-release` slash command (in `.claude/commands/cut-release.md`) implements the Claude-driven flow; a complementary shell script for non-Claude releases would close the loop.

### Speculative / unresolved API questions

Open questions about VeSync API behavior that we haven't fully resolved. If you find answers, update this section in the same PR.

1. **Why Superior 6000S responses double-wrap and Vital 200S responses don't.** Both use bypassV2. Pyvesync handles both via `process_bypassv2_result` helper that abstracts the unwrap. The actual cloud-side reason isn't documented.
2. **Whether VeSync rate-limits per-account or per-IP.** Empty-result polls are common (~10-30% on healthy installs). We assume rate-limiting; could also be transient cloud failures. Real cap is unknown.
3. **Whether older firmware on Vital 200S returns the same fields.** Our diagnostic-confirmed field set is from one user's device. Older units might have different fields; the V102S fixture in pyvesync suggests the field set has evolved over firmware versions.
4. **The `traceId` field's significance.** The parent driver currently sends a hardcoded value (inherited from upstream and ultimately from pyvesync). If VeSync ever validates this, the integration breaks.
5. ~~**Whether the `deviceRegion` field in the parent's request body matters.** Currently hardcoded to `"US"` for all installs.~~ **Shipped in v2.2 preview.** EU host routing (`smartapi.vesync.eu`) is now implemented via the `VeSync API region` preference on the parent device. The `deviceRegion` body field is now preference-backed via `getDeviceRegion()`. Open question: whether EU VeSync accounts require the EU host for initial login, or only for device commands. Community EU-hardware reports welcome on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499).

### Adjacent product directions

- **Alexa-aware capability surfaces.** Drivers currently expose standard capabilities (Switch, SwitchLevel, AudioVolume, etc.) — Alexa interprets these via Hubitat's Echo Skill but with limited semantic richness. Adding richer capabilities like `MediaInputSource` might give voice-control polish. Worth a research pass + pilot on one driver.
- **Migration guide for users coming from a Home Assistant + Homebridge bridge.** Some community users used HA-as-bridge to get Vital 200S working before our v2.0 native support; a migration-from-bridge guide could ease their transition.

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
3. **Open an issue** before doing a large diff so we can confirm scope + sequencing (e.g. "I want to add Classic 300S — does that fit the v2.1 plan or should it land later?").
4. **Run the Spock harness + lint locally** before opening a PR. CI gates both, but local fast-feedback saves round-trips.
5. **Live-test if you have the hardware.** If you don't, note that in the PR — the maintainer or another community user with matching hardware can test before merge.
