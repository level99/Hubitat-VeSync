# Roadmap

What's planned for upcoming releases of the **Hubitat-VeSync** community fork. This is a living document; items may shift between releases as community demand surfaces, hardware becomes available for testing, or implementation effort gets re-estimated.

For what's already shipped, see [`CHANGELOG.md`](CHANGELOG.md). For day-to-day in-flight work, see the maintainer's local `TODO.md` (gitignored — not present in clones).

---

## v2.2 — next release (TBD)

### Preview drivers added in v2.2

- **LV600S Humidifier** (`LUH-A602S-*`, all 6 regional variants) — same `VeSyncHumid200300S` class as Classic 300S, extended with warm-mist (0-3 levels). Ships as `[PREVIEW v2.2]`. **Known open question:** [pyvesync PR #505](https://github.com/webdjoe/pyvesync/pull/505) reports EU firmware variants (LUH-A602S-WEU/-WEUR) may need `mode:"humidity"` instead of `mode:"auto"` to switch auto mode; PR is unmerged pending clarification. Community hardware reports welcome.

### Fixed in v2.2

- **Poll cycle survives hub reboots (Bug Pattern #14)** — parent driver switched from recursive `runIn()` chain to persistent `schedule()` cron job.

Items below remain unscheduled until prioritized into a release.

---

## Beyond v2.2 — unscheduled

Items below are not yet locked to a release. They're available for community pickup or maintainer prioritization as time permits.

### Device support — high value, no version locked

#### Tier 2 (moderate effort, good coverage gain)

| Driver | Model codes | Notes |
|---|---|---|
| ~~**LV600S** (`VeSyncHumid200300S`)~~ | ~~`LUH-A602S-*`~~ | **Shipped v2.2 as preview.** |
| **OasisMist 1000S US** | `LUH-M101S-WUS`, `-WUSR` | Different humidifier class (`VeSyncHumid1000S`) — new payload conventions; pull pyvesync's class in full before drafting |

#### Tier 3 (broad coverage, lower individual demand)

| Driver | Model codes | pyvesync class | Notes |
|---|---|---|---|
| Everest Air | `LAP-EL551S-*` | `VeSyncAirBaseV2` | TURBO mode + VENT_ANGLE feature (new payload methods) |
| Sprout Air | `LAP-B851S-*` | `VeSyncAirSprout` | Different class than Vital line; AIR_QUALITY + NIGHTLIGHT |
| LV600S Hub Connect | `LUH-A603S-WUS` | `VeSyncLV600S` | **Naming trap** — different class than `-A602S` despite both being branded "LV600S" |
| Sprout Humid | `LEH-B381S-*` | `VeSyncSproutHumid` | Newest humidifier class, less docs |
| Classic 200S | `Classic200S` | `VeSyncHumid200S` | Different class from `VeSyncHumid200300S` despite the naming similarity |
| Dual 200S | `Dual200S`, `LUH-D301S-*` | `VeSyncHumid200300S` | Trivial after Classic 300S (shipped v2.1) — same class, mist 1-2 instead of 1-9 |
| OasisMist 450S EU | `LUH-O451S-WEU` | `VeSyncHumid200300S` | Trivial after 450S US (shipped v2.1) — same class, no humidity mode |
| OasisMist 1000S EU / Vibe Mini | `LUH-M101S-WEUR` | `VeSyncHumid1000S` | Low effort after 1000S US |

#### Tier 4 — out-of-scope unless demand surfaces

- **LV-PUR131S** / **LV-RH131S** — older WiFi 131 line. Different API entirely (NOT bypassV2). Older Wi-Fi cloud protocol with different envelope; would need a separate `sendV1Request` path. Substantial separate code path for products that are EOL (~2018-2020 generation). **Recommend SKIP** unless explicit community demand justifies the infrastructure work.

### Tooling & dev experience

- **Full pyvesync compatibility audit** — manual side-by-side pass through every supported driver against pyvesync's canonical fixtures + class implementations, plus an automated `PyvesyncCoverageSpec` CI gate that re-runs on pyvesync upstream refresh. Catches the entire class of "VeSync API drifted, our driver silently wrong" bugs before they reach users.
- **Whole-repo `runIn` bare-identifier sweep** — convert remaining bare-identifier `runIn(N, handlerName)` calls to string-literal form for portability between Hubitat sandbox and the test classloader. ~14 instances across 8 files; bundle with the next child-driver lifecycle spec coverage round.
- **Virtual test parent driver** — sibling driver to `VeSyncIntegration.groovy` that returns canned pyvesync-fixture data instead of talking to the cloud. Lets contributors exercise child-driver parser paths end-to-end without owning the hardware. Worth building if hardware coverage expands or if multiple new device types need to land in succession.
- **Pyvesync local Python harness** — small Python script that diff's our driver payloads against pyvesync's canonical request/response shapes. Useful as a CI gate; skip until CI is the limiting factor.
- **`CONTRIBUTING.md`** — translate the dev/QA/tester pipeline + agent dispatch + PR conventions in `CLAUDE.md` to a human-contributor audience.
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
