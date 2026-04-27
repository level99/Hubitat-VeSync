# Contributing to Hubitat-VeSync

Thanks for considering a contribution. This is a community-maintained fork of the original [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat) Levoit/VeSync driver pack, picked up after upstream went idle in 2022. Bug fixes, new device support, doc improvements, and test additions are all welcome.

## What's most useful

| Contribution | Friction | Notes |
|---|---|---|
| **New device support** — file an issue with a `captureDiagnostics()` paste from the Generic Levoit Diagnostic Driver. Even without a code PR, the diagnostics block has everything a driver author needs. | Low | Use the [new-device-support issue template](.github/ISSUE_TEMPLATE/new_device_support.yml). Diagnostics auto-redact PII (email, account ID, token). |
| **Bug reports with debug logs** | Low | Use the [bug-report template](.github/ISSUE_TEMPLATE/bug_report.yml). Enable parent-driver `debugOutput`, reproduce, copy the relevant log lines (PII auto-redacted), paste. |
| **Driver code (bug fix or new device)** | Medium | See "Setting up your dev environment" + "Adding a new device" below. Spock + lint run on every PR. |
| **Hardware validation of a preview driver** | Low-medium | If you own a `[PREVIEW vX.Y]` device and the driver behaves differently than expected, that's a real-world refutation of an inline `CROSS-CHECK` decision. See "Working on a preview driver" below. |
| **Doc improvements** | Low | README, per-driver readme, migration guide — all fair game. The `Drivers/Levoit/readme.md` events table is the most common place users wish there was more detail. |

## Where to file what

- **Code PRs** — open against `main` at https://github.com/level99/Hubitat-VeSync. Branch off latest `main`; squash-merge is the default.
- **Bug reports / new-device requests / feature requests** — GitHub issues with the matching template. The [community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) is fine for casual questions, but issues are the durable record.
- **Sensitive incident reports** — direct message to the maintainer at https://github.com/level99. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) for the formal escalation framework.

## Codebase orientation

```
Hubitat-VeSync/
├── Drivers/Levoit/
│   ├── VeSyncIntegration.groovy          ← parent driver (auth, polling, child management)
│   ├── LevoitCore200S.groovy             ← Core 200S child
│   ├── LevoitCore200S Light.groovy       ← Core 200S night-light child
│   ├── LevoitCore300S/400S/600S.groovy   ← rest of Core line
│   ├── LevoitVital100S/200S.groovy       ← Vital line (V2 API)
│   ├── LevoitClassic300S.groovy          ← V1-humidifier base
│   ├── LevoitSuperior6000S.groovy        ← V2-humidifier (double-wrapped responses)
│   ├── LevoitOasisMist450S.groovy        ← extends Classic 300S with warm mist
│   ├── LevoitTowerFan.groovy             ← v2.1 fan (single-axis oscillation)
│   ├── LevoitPedestalFan.groovy          ← v2.1 fan (2-axis oscillation, range)
│   ├── LevoitGeneric.groovy              ← fall-through diagnostic driver
│   ├── Notification Tile.groovy          ← optional dashboard helper
│   └── readme.md                          ← per-driver event/attribute reference
├── src/test/groovy/drivers/              ← Spock unit-test specs (one per driver)
├── tests/
│   ├── lint.py                            ← static lint orchestrator
│   ├── lint_rules/                        ← 22 pluggable rules (BP1-13, RULE15-22)
│   ├── lint_config.yaml                   ← frozen_driver_names, exemptions
│   └── fixtures/*.yaml                    ← captured pyvesync API responses
├── levoitManifest.json                    ← HPM package manifest
├── repository.json                        ← HPM tier-2 keyword listing
├── README.md                              ← top-level repo readme
├── CHANGELOG.md                           ← Keep-a-Changelog format
├── ROADMAP.md                             ← future device support tiers
├── docs/migration-from-niklas-upstream.md ← v1.x→v2.x migration guide
└── .claude/
    ├── agents/*.md                        ← AI-contributor agent specs (referenced from CLAUDE.md)
    └── commands/cut-release.md            ← /cut-release slash command spec
```

### Architecture in one paragraph

A single **parent driver** (`VeSyncIntegration.groovy`) holds the VeSync account credentials, logs in to VeSync's cloud, discovers devices, schedules periodic polling, and routes API calls. **Child drivers** are per-model (one for Core 200S, one for Vital 200S, etc.). Each child exposes Hubitat capabilities (`Switch`, `FanControl`, etc.) and parses status responses. The parent calls `child.update(status, nightLight)` on every poll cycle. All API traffic uses VeSync's `bypassV2` endpoint with model-specific method names + payloads.

### Canonical reference: pyvesync

For any new device support or any "is this payload right?" question, the source of truth is **[webdjoe/pyvesync](https://github.com/webdjoe/pyvesync)**:

- `src/pyvesync/device_map.py` — which model codes belong to which class
- `src/pyvesync/devices/vesyncpurifier.py` and `vesynchumidifier.py` and `vesyncfan.py` — class implementations with response field semantics
- `src/tests/api/vesyncpurifier/<MODEL>.yaml` and `vesynchumidifier/<MODEL>.yaml` and `vesyncfan/<MODEL>.yaml` — real-device captures used as canonical fixtures

The fork's drivers are derived from these. Don't trust other reverse-engineered clients (Homebridge plugins, random GitHub forks) as primary references — pyvesync is what Home Assistant's `vesync` integration uses internally and is the de facto community canon.

pyvesync may lag the live API; the diagnostic raw-response log line on a child driver (`applyStatus raw r (after peel=...) keys=..., values=...`) is the ground truth when the two conflict. If you see a field in the log that pyvesync's class doesn't parse, prefer the live response and file an issue upstream at pyvesync after capturing the divergence.

### Audience

This file is the canonical contributor onboarding for the repo — codebase tour, conventions, dev env, test runners, PR flow, preview-driver protocol. Useful regardless of whether you're a human contributor or an AI-assisted session.

`CLAUDE.md` (root) layers AI-pipeline-specific mechanics on top: a 4-agent dev/QA/tester/operations dispatch protocol, agent resume conventions via SendMessage, cost-optimization rules around fresh-vs-resume dispatches. AI sessions read both files; humans only need this one. The conventions enforced (lint rules, Spock specs, BP catalog) apply uniformly to both.

## Setting up your dev environment

You don't need a Hubitat hub or any Levoit hardware to contribute. The Spock unit-test harness mocks the Hubitat sandbox, and lint is pure static analysis. A laptop is sufficient for everything except live-hardware validation.

### Required

- **Java 17 or later** — Gradle's toolchain auto-provisioning will download a JDK on first build if `JAVA_HOME` isn't set, so this is usually friction-free. If you prefer to install Java explicitly, [Eclipse Adoptium](https://adoptium.net) Temurin 17 is what CI uses.
- **`uv`** for the Python lint runner — [astral.sh/uv](https://docs.astral.sh/uv/) one-line install. `uv` auto-downloads Python 3.12 on first run; no system Python install needed.
- **Git** — any modern version.

### Optional

- A Hubitat hub (any model, firmware 2.3.2+) — only needed if you want to live-test driver changes. The Spock harness covers most code paths without one.
- A Levoit/VeSync device + account — only needed for hardware validation of a preview driver, or for capturing a new device's payloads when filing a `captureDiagnostics` issue.

### Clone + first build

```bash
git clone https://github.com/level99/Hubitat-VeSync
cd Hubitat-VeSync

# Run Spock unit tests (downloads Gradle wrapper + JDK on first run; ~2 min cold, ~5s warm)
./gradlew test

# Run static lint (downloads Python 3.12 on first run; ~10s cold, ~5s warm)
uv run --python 3.12 tests/lint.py
```

If both pass green you're set. Failures here usually mean a stale local checkout — `git pull origin main` and retry.

### IDE setup (optional)

The Groovy drivers are plain Hubitat-flavored Groovy — any editor with `.groovy` syntax highlighting works. For deeper integration:

- **IntelliJ IDEA** (Community is free) opens the project as a Gradle project; Spock specs run with right-click → Run.
- **VS Code** + Groovy extension works for editing; tests need `./gradlew test` from terminal.

There's no required Hubitat-specific tooling — the project relies on standard Groovy + Gradle.

## Adding a new device driver

This is the most common substantive contribution. The flow is:

1. **Confirm pyvesync supports it.** Check `src/pyvesync/device_map.py` in [webdjoe/pyvesync](https://github.com/webdjoe/pyvesync) for the model code. If pyvesync covers it, there's a class assignment + dev_types entry. If pyvesync doesn't cover it, the path is harder — pyvesync is the canonical reference, and without it you're blind-building from packet captures alone (still doable but more iteration).
2. **Pull the canonical fixture.** From `src/tests/api/<category>/<MODEL>.yaml` in pyvesync. Drop a copy at `tests/fixtures/<MODEL>.yaml` in this repo — the Spock harness loads it for testing.
3. **Read the pyvesync class.** `src/pyvesync/devices/vesyncpurifier.py` / `vesynchumidifier.py` / `vesyncfan.py` shows response field semantics, reverse-mappings (e.g. `"autoPro"` → `"auto"`), threshold logic, and which payloads the device actually accepts.
4. **Copy the closest existing driver as a template.** Match by class:
   - V2-API purifier (Vital line) → `LevoitVital200S.groovy`
   - V1-API humidifier with mist 1-9 → `LevoitClassic300S.groovy`
   - V2-API humidifier (Superior 6000S class with double-wrapped responses) → `LevoitSuperior6000S.groovy`
   - Older Core line → `LevoitCore400S.groovy`
   - Fan → `LevoitTowerFan.groovy` (single-axis oscillation) or `LevoitPedestalFan.groovy` (2-axis with range)
5. **Replace metadata, commands, and field parsing.** Preserve the load-bearing patterns (see "Conventions enforced..." section): the 3-signature `update()` pattern, the diagnostic raw-response log line, the pref-seed insertion, the envelope-peel loop on V2 drivers.
6. **Update the parent driver** (`Drivers/Levoit/VeSyncIntegration.groovy`):
   - `deviceType()` switch — recognize the new model code(s) and return the family label
   - `getDevices()` `addChildDevice` branch — instantiate the new child driver
   - `isLevoitClimateDevice()` whitelist — add the prefix or literal name (lint rule 22 enforces parity with `deviceType()`)
   - `updateDevices()` method routing — pick the right `getXxxStatus` API method per family if you're adding a new family (existing families auto-route)
7. **Update `tests/lint_config.yaml`** — add the new driver's filename + `definition(name: ...)` value to `frozen_driver_names` (lint rule 19; protects against future driver-name changes that would orphan user installs — Bug Pattern #9).
8. **Update `levoitManifest.json`** — add a new `drivers` array entry with a fresh UUID v4 (any UUID generator works), the driver's `name` matching `definition(name: ...)`, namespace `NiklasGustafsson` (preserved from upstream), and the GitHub raw URL for the location.
9. **Update `Drivers/Levoit/readme.md`** — add a new driver entry to the manual-install table and a per-device events table (lint rule 21 enforces drivers↔README sync).
10. **Add a Spock spec at `src/test/groovy/drivers/<DriverName>Spec.groovy`** — copy the closest existing spec as a template. Cover at minimum: happy-path setSwitch, setMode, applyStatus parsing of the canonical fixture, and any device-specific commands. The harness uses the Hubitat sandbox mock — see existing specs for the patterns.
11. **Run lint + Spock locally** — `./gradlew test` and `uv run --python 3.12 tests/lint.py`. Both should pass before opening a PR.

### Preview drivers (no maintainer hardware)

If you don't have the device yourself but pyvesync covers it cleanly, you can ship the driver as a **preview** (the v2.1 Vital 100S, Classic 300S, OasisMist 450S, Tower Fan, and Pedestal Fan all started this way). The convention is:

- Add `[PREVIEW vX.Y]` prefix to the driver's `description:` field in `definition()` (NOT the `name:` field — that's frozen by Bug Pattern #9)
- Add inline `CROSS-CHECK` comment blocks at every contentious decision point (decision/rationale/source/refutation criteria — see existing v2.1 drivers for the format)
- Note "preview" status in the `Drivers/Levoit/readme.md` driver entry

Hardware reports refuting blind decisions are then tracked back to the `CROSS-CHECK` source citation, validated, and the prefix gets stripped in the next release cut.

### Don't change driver names once shipped

Bug Pattern #9: Hubitat associates devices to drivers by `(namespace, name)`. Renaming a driver after release orphans every existing user's device. Lint rule 19 (`frozen_driver_names`) catches this statically. If you genuinely need a new name, ship it as a separate driver file and document a migration path in `docs/migration-from-niklas-upstream.md`.

## Fixing a bug

The fork has accumulated a 13-entry bug-pattern catalog from the v2.0 → v2.1 cycle. Most bugs you'll encounter map to one of these. Read the catalog before coding; the fix is often "apply the documented pattern."

The catalog itself lives in the project `CLAUDE.md` (root) and is referenced numerically (`Bug Pattern #1` etc.) throughout the codebase. Each entry has: symptom, root cause, canonical fix, and where the regression spec / lint rule lives.

### Workflow

1. **Reproduce.** Capture a debug log (parent driver `debugOutput=true`) showing the bad behavior. PII auto-redacts.
2. **Write a Spock spec that fails.** Add a regression test to `src/test/groovy/drivers/<DriverName>Spec.groovy` that asserts the *correct* behavior and fails on current code. Catching the bug in the test harness FIRST means: (a) you've actually understood the bug, (b) the fix can't silently re-regress later.
3. **Check the bug-pattern catalog.** If the symptom matches a numbered entry (`Bug Pattern #X`), apply the canonical fix from that entry. If it doesn't match, you may have found a new pattern — flag it in the PR description so it can be added to the catalog.
4. **Fix the code.** Smallest change that turns the failing spec green.
5. **Verify.** Re-run `./gradlew test` (the previously-failing spec now passes; existing specs still green) and `uv run --python 3.12 tests/lint.py` (no new findings).

### When the bug isn't in driver code

- **Lint finds something the spec missed** — e.g., a `version:` lockstep drift, a frozen-driver-name mismatch, a manifest entry without a corresponding driver file. Read the rule's title — it tells you which file to fix and how. Lint rules live at `tests/lint_rules/`.
- **The bug is in the parent driver's poll/route logic** — fix it once in `Drivers/Levoit/VeSyncIntegration.groovy` and add a spec to `VeSyncIntegrationSpec.groovy`. Parent fixes are higher-leverage than child fixes (one fix benefits all device families).
- **The bug is a new bug pattern** — propose a new numbered entry for the catalog in your PR description. The maintainer will fold it into `CLAUDE.md` on merge if accepted.

### What not to do

- **Don't add error handling for cases that can't happen.** Trust the parent driver's invariants; trust pyvesync's documented payload shapes. Defensive null-guards are fine on response fields the API may omit; defensive guards on internal state are usually wrong.
- **Don't refactor surrounding code while you're there.** A bug fix should be minimal-diff. Bundling refactors makes review harder and increases the chance of regressing something else.
- **Don't widen scope to "while I'm here, also fix...".** Open separate PRs for separate concerns. Squash-merge collapses each PR into one commit on `main`; one fix per commit is the working unit.

## Conventions enforced by lint/tests

The Spock harness + 22 lint rules catch a long tail of regressions. Most of these you don't need to memorize — you'll trip them, read the finding, fix. But a few are worth knowing up-front because they shape how a driver is written.

### High-leverage conventions

| Convention | Enforced by | What it catches |
|---|---|---|
| **Frozen driver `name:` field** | RULE19 + BP9 | Renaming a shipped driver orphans every user device on it. `definition(name: ...)` must match `tests/lint_config.yaml` `frozen_driver_names`. |
| **Per-driver `version:` lockstep** | RULE20 | All drivers' `definition(version: ...)` must match `levoitManifest.json` `version` exactly. Bumping for one without the others fails lint. |
| **Drivers ↔ README sync** | RULE21 | Every driver file in `Drivers/Levoit/*.groovy` must appear in the supported-devices table in top-level `README.md`. The bold first-cell name must match `definition(name: ...)`. |
| **Manifest ↔ files reconciliation** | RULE18 | Every driver file has a `levoitManifest.json` entry with matching name + namespace. UUIDs must be unique. |
| **`deviceType()` ↔ whitelist parity** | RULE22 | Every model code recognized by `deviceType()` in the parent driver must also be covered by `isLevoitClimateDevice()` (either by prefix `startsWith` or literal in-list). |
| **PII scan** | RULE16 | No real email addresses, account IDs, tokens, or passwords in committed files. Test fixtures use placeholder values; allow-patterns for common placeholders are in `tests/lint_config.yaml`. |
| **3-signature `update()` pattern** | BP1 | Every child driver exposes `update()` (self-fetch), `update(status)` (1-arg parent callback), `update(status, nightLight)` (2-arg parent callback — the one the parent's poll cycle uses). |
| **Pref-seed at first poll method** | BP12 | Every driver has a `state.prefsSeeded` block at the top of its first parent-callback method that auto-applies `descriptionTextEnable=true` if null. Heals migration paths where Hubitat doesn't auto-commit `defaultValue`. |
| **V2-API field discipline** | BP4 | V2-API drivers (Vital, Tower Fan, Pedestal Fan, Superior 6000S) must use `levelIdx` / `levelType` / `manualSpeedLevel` field names — NOT the legacy Core-line `id` / `type` / `level`. |
| **Logging gates** | Convention | INFO logs gate on `descriptionTextEnable`; DEBUG logs gate on `debugOutput`; both default to user choice (`descriptionTextEnable` on, `debugOutput` off with 30-min auto-disable). PII auto-redacts via parent's `sanitize()`. |

### Full lint rule list

`tests/lint_rules/` has one Python module per rule. Run `uv run --python 3.12 tests/lint.py --strict` to see all rule IDs. Each finding includes the rule ID, file path, line number, and a `fix:` hint.

### Bug-pattern catalog references

The catalog (`CLAUDE.md` root) numbers each pattern (`Bug Pattern #N`). Lint rules and Spock specs cite these numbers in finding/test-name strings. When you see `BP9` in a finding, that's catalog entry #9 (driver-name change orphan).

### Exemptions

Some lint rules have legitimate per-file exemptions — e.g., `LevoitCore200S Light.groovy` intentionally has only the 1-arg `update()` signature (parent invokes it explicitly, not through generic poll path), and that exemption is documented in `tests/lint_config.yaml` with a justification. If you have a real reason to deviate from a rule, add an exemption with a substantive `reason:` string. Empty reasons fail lint by design — exemptions are not a silent escape hatch.

## Running tests locally

The "Setting up your dev environment" section covered the cold-start commands. This section goes deeper.

### Run a single Spock spec

`./gradlew test` runs everything (~3s warm). To target one driver's specs:

```bash
./gradlew test --tests "drivers.LevoitClassic300SSpec"
```

Use the spec class name (matches the `.groovy` filename without extension). Add `--tests "drivers.LevoitClassic300SSpec.setMistLevel*"` to filter to specific test methods within the spec (`*` is a glob).

### Reading Spock failures

Spock's failure output shows the full expression with a power-assert-style breakdown — the actual vs expected values are inlined under each operand. Don't squint at stack traces; read the assertion section.

The full HTML report lands at `build/reports/tests/test/index.html` after each run — open it in a browser for a navigable per-spec/per-method view. Useful when scanning hundreds of green tests for the failing one.

### Lint output

```bash
uv run --python 3.12 tests/lint.py
```

Default mode prints findings as human-readable text. For machine consumption (CI parsing, scripting):

```bash
uv run --python 3.12 tests/lint.py --json
```

Each finding includes:
- `severity` — `FAIL` (blocking), `WARN` (informational, doesn't fail CI)
- `rule_id` — e.g., `RULE19_frozen_driver_name_changed`, `BP12_missing_pref_seed`
- `file` + `line` — pinpoint location
- `why` — one-sentence rationale
- `fix` — suggested action

`--strict` mode is what CI uses. It exits non-zero on any FAIL; WARN doesn't fail. Use it locally to mirror CI exactly.

### Common gotchas

- **File-system case sensitivity.** Linux CI is case-sensitive; Windows/macOS dev machines often aren't. A driver file named `LevoitClassic300s.groovy` (lowercase `s`) will pass on a Mac and fail on Linux CI. Match the canonical capitalization in the manifest.
- **Line endings.** `.gitattributes` enforces LF for `.groovy` and `.py` files. If your editor inserts CRLF, lint may complain. Re-clone with proper Git config (`git config core.autocrlf input`) or run `git add --renormalize .` to fix.
- **Stale Gradle daemon.** If `./gradlew test` hangs or returns ghost results after a code change, `./gradlew --stop` then re-run.
- **`uv` first run is slow.** First invocation downloads Python 3.12 (~30s). Subsequent runs use the cached interpreter (~5s).

### CI matches local exactly

GitHub Actions runs the same `./gradlew test` and `uv run ... tests/lint.py --strict` invocations on every PR. There's no separate CI-only step. If green locally, green in CI (modulo the case-sensitivity caveat above). The reverse — green CI but red local — usually means stale local checkout; `git pull origin main` and rebuild.

### When NOT to run tests locally

Almost never. Both lint and Spock are fast enough (sub-10-second warm runs each) that there's no reason to skip them.

## Opening a PR

### Branch + commit

```bash
git checkout main
git pull origin main
git checkout -b feat/your-thing       # or fix/your-thing for a bug fix
# ... make changes, run tests locally ...
git add <files>
git commit -m "<subject line>"
git push -u origin feat/your-thing
```

Branch naming: `feat/<short-name>` for new functionality, `fix/<short-name>` for bug fixes, `docs/<short-name>` for doc-only changes. Names are descriptive, not strictly enforced.

### Commit message style

Subject line ≤ 72 characters, imperative voice ("add X", not "added X"), specific. Body wraps at ~72 columns and explains the why, not just the what. The diff already shows the what.

Examples from the recent log (`git log --oneline | head -10` to skim more):

```
docs(readme): correct VeSync Integration install path -- it's a driver, not an app
manifest: cumulate releaseNotes across versions (HPM convention) + harden cut-release spec
v2.1: 5 new drivers + first fan support (preview)
```

### One concern per PR

Squash-merge is the default. Each PR collapses to a single commit on `main`, so the PR's scope = a single logical unit going into history. Bundling unrelated fixes makes review harder and the squash commit message muddier. Open separate PRs for separate concerns.

### Open the PR

```bash
gh pr create --base main --head feat/your-thing
```

(If you're using a fork-of-the-fork, prefix with `--repo level99/Hubitat-VeSync` — `gh` sometimes resolves to the wrong upstream when remotes are layered.)

The repo has a PR template (`.github/PULL_REQUEST_TEMPLATE.md`) that prompts for:
- Summary of the change
- Test plan (what you ran locally, what manual verification you did)
- Linked issues (`Closes #N`)

Fill it out — reviewers (human + bot) read it before the diff.

### CI gates

Two GitHub Actions workflows run on every PR:

- **Static lint** (`.github/workflows/lint.yml`) — `uv run --python 3.12 tests/lint.py --strict`. Fails on any FAIL finding.
- **Spock harness** (`.github/workflows/spock.yml`) — `./gradlew test --no-daemon`. Fails on any failing spec.

Both must pass before merge. Branch protection on `main` enforces this.

### Bot review

This repo has [Gemini Code Assist](https://developers.google.com/gemini-code-assist) configured at `.gemini/config.yaml`. On PR open, Gemini posts a summary + line-level findings (typically medium-priority code-quality observations). Address what's actionable; reply on findings you intentionally don't apply with the rationale.

### Maintainer review + merge

The maintainer reviews, may ask follow-ups, and squash-merges on approval. The squash commit message is editable at merge time — typically uses the PR title + body, but the maintainer may rewrite for clarity.

After merge: the maintainer tags releases (`v2.X`) and publishes GitHub Release pages. Contributor PRs don't bump versions — that's a release-cut decision (see `/cut-release` slash-command spec at `.claude/commands/cut-release.md` for the maintainer-side flow).

## Working on a preview driver

If you own a Levoit/VeSync device whose Hubitat driver is tagged `[PREVIEW vX.Y]` in its description field, your hardware is genuinely the most useful thing you can contribute. The fork's preview drivers were built blind via cross-reference (pyvesync + Home Assistant + SmartThings + Homebridge); every contentious decision is documented inline as a `CROSS-CHECK` comment block waiting for a hardware report to validate or refute it.

### Two paths

**Validation report** — "the driver works correctly on my hardware." File a brief comment on the [community thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) or open a tracking issue. Once 1-2 independent users on different firmware revisions confirm a driver works correctly, the maintainer can strip the `[PREVIEW vX.Y]` prefix in the next release cut.

**Refutation report** — "command X doesn't work" or "attribute Y reports the wrong value." This is where the inline `CROSS-CHECK` blocks pay off:

1. Enable debug logging on the affected device (its preferences page → `debugOutput` true → Save Preferences).
2. Reproduce the broken behavior. Note the timestamp.
3. Pull the relevant log lines from Hubitat → Logs (PII auto-redacts).
4. On the device's detail page, run `captureDiagnostics` (if it's a Generic Levoit Diagnostic Driver) or grab the `applyStatus raw r ...` debug-log line (any V2 driver outputs this on every poll cycle, and it shows the parsed device response post-envelope-peel).
5. Find the `CROSS-CHECK` comment in the driver source most relevant to the broken behavior — search for the field name or command name. Each block has a `Refutation:` line that describes what evidence would override the original blind decision.
6. File an issue using the [bug-report template](.github/ISSUE_TEMPLATE/bug_report.yml). Paste the debug log + the `applyStatus raw r` line + a quote of the `CROSS-CHECK` block you're refuting.

The maintainer (or a contributor) iterates on the driver based on your evidence. If the refutation is clean, the canonical fix usually ships in the next patch release.

### What's still gappy in shipped previews

`ROADMAP.md` tracks known gaps in preview drivers — typically setter payloads we couldn't determine without hardware. Examples from v2.1:

- Pedestal Fan: `setDisplay` / `setMute` / `setChildLock` are read-only attributes (no setter exposed; pyvesync has none, no community evidence)
- Pedestal Fan: timer support omitted
- Tower Fan `displayingType`: read-only diagnostic of unknown semantics

If you have hardware and can capture the missing payloads (e.g., toggle the field in the Levoit mobile app while watching a packet capture, or build a debug driver that probes payload variants and inspects whether the next status poll reflects the change), that's a fast path to a setter implementation. File a new-device-support issue with the captures and the maintainer or another contributor can write the driver method.

### Live-test pattern (any driver, not just preview)

For any change that affects API payloads, the validation flow is:

1. Deploy the modified driver to a real Hubitat hub (Drivers Code page → paste source → Save).
2. On the affected device, enable `debugOutput` and exercise the changed command.
3. Watch Logs for the parent driver's `sendBypassRequest` trace line — it shows the API method + summary at debug level. Set parent's `verboseDebug=true` for the full request/response body dump if the trace summary isn't enough.
4. Cross-reference the response shape against pyvesync's canonical fixture for the same model.

The Spock harness covers most paths without hardware, but a live-test for a payload-touching change is cheap insurance against a fixture-vs-reality drift.

## Additional resources

- **`CLAUDE.md`** (root) — AI-pipeline overlay if you're contributing via an AI-assisted session (dev/QA/tester/operations agent dispatch protocol, resume conventions, cost-optimization rules). Optional reading for humans; both audiences land here for the conventions enforced by lint and tests.
- **`Drivers/Levoit/readme.md`** — per-driver event/attribute/command reference. Useful when adding a new device that's similar to an existing one (find the closest match by capability set).
- **`docs/migration-from-niklas-upstream.md`** — for users coming from the original v1.x repo. Contributors generally don't need it, but the patterns it explains (driver-by-name matching, in-place HPM updates) are foundational to why Bug Pattern #9 exists.
- **`ROADMAP.md`** — public roadmap with device-support tiers, naming traps, speculative API questions. Pick something off the queue if you want to contribute proactively without filing an issue first.
- **`CHANGELOG.md`** — release-by-release history, Keep-a-Changelog format. Useful for understanding why a particular convention was introduced (each entry references commit hashes).
- **[pyvesync](https://github.com/webdjoe/pyvesync)** — canonical VeSync API reference. Linked throughout the codebase; every "is this payload right?" question routes here first.
- **[Hubitat developer docs](https://docs.hubitat.com/index.php?title=Developer_Documentation)** — for sandbox restrictions, async patterns, capability definitions. Mostly relevant when extending capabilities or working around platform quirks.

## License

By contributing, you agree your contributions are licensed under the same MIT License the project uses (`LICENSE` at repo root).
