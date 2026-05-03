---
description: Cut a new release of the Hubitat-VeSync fork — analyze commits, draft manifest + CHANGELOG updates, present for approval, apply on go-ahead.
---

You are cutting a new release of the level99/Hubitat-VeSync fork. Run the procedure below end-to-end. Do not skip steps; do not commit or tag (the user does that manually per preview-before-publish rule).

## Step 1 — Pre-flight checks

Run these in parallel and report any failure:

- `git status --short` — working tree should be clean (only `TODO.md` may be untracked; any other uncommitted change blocks the release).
- `./gradlew test --no-daemon` — Spock harness must PASS. (If `JAVA_HOME` isn't set on the host, set it to a JDK 17 install or rely on the Gradle wrapper's toolchain auto-provisioning.)
- `uv run --python 3.12 tests/lint.py --strict` — lint must PASS clean. (Requires `uv` on PATH — install via [astral.sh/uv](https://docs.astral.sh/uv/) if not already present.)
- `git rev-parse --abbrev-ref HEAD` — confirm the branch (typically `release/v<version>` or `main`).
- **Production-log audit (if Hubitat MCP available).** Run `mcp__hubitat__manage_logs get_hub_logs level:error limit:50` on the maintainer's hub. Compare error timestamps to the previous release tag's date — any new ERROR-level entries since the last release that aren't accounted for by an in-flight fix should be triaged BEFORE this cut. Catches "production has been silently throwing X for days" bugs in batch instead of trickling out post-release. v2.2.1 cycle would have caught BP1 / resp.msg / cron-syntax in one pass via this check; without it they surfaced one-by-one across 4-5 user-flag rounds. Surface findings as a "production triage" report; if any are real, hold the cut and run dev/QA/tester rounds to fix before proceeding.
- **CHANGELOG drift check.** Run `uv run --python 3.12 tools/changelog_drift_check.py`. The helper diffs `git log <last-tag>..HEAD` against the `[Unreleased]` section of `CHANGELOG.md`, flagging any `feat:` / `fix:` commits whose distinctive keywords don't appear anywhere in `[Unreleased]`. (Skips `chore:` / `docs:` / `test:` / `refactor:` / `style:` / `build:` / `ci:` types per Conventional Commits — those typically don't warrant CHANGELOG bullets.) Catches the "feature shipped during the cycle but the bullet was skipped" drift pattern that surfaced 2026-04-30 mid-v2.4 (4 missing entries: Phase 1.5/1.7/Phase 2 fixes/WARN-only). On a clean cycle, prints a one-line confirmation. On drift, prints the missing commit list with their searched keywords — review each, decide if user-meaningful, add a bullet to `[Unreleased]` BEFORE proceeding to Step 4. The check is informational (exit 0 always); the cut-release agent decides whether to hold based on findings.

If any pre-flight fails, stop and report. Do not proceed.

## Step 2 — Determine target version

Read `levoitManifest.json` `version` field. Default the new version by:

- If branch is `release/v<X.Y>` → propose `<X.Y>`.
- Else if last commit subjects use `v<X.Y>+` pattern (in-flight work) → propose `<X.Y>`.
- Else → ask user explicitly.

Confirm the proposed version with the user before doing analysis. (One-line ask, e.g., *"Cutting v2.0 — confirm?"*) — if redirected, use the version they specify.

## Step 3 — Gather analysis data

Compute the baseline ref:

- If `git tag --list "v*"` returns tags → use the latest tag (e.g., `v1.0`).
- If no tags yet → use the commit where `levoitManifest.json` `version` was last different from current (find via `git log -p --follow levoitManifest.json | grep -B 5 '"version"'`). For the first cut on this fork, that's the start of meaningful divergence from upstream.
- If neither yields a sensible base → ask user for the baseline ref.

Then collect, in parallel:

- `git log <baseline>..HEAD --format="%h %s"` — commit subjects (the spine of the CHANGELOG)
- `git diff --stat <baseline>..HEAD` — what files changed (sanity-check for surprises)
- Current `levoitManifest.json` `releaseNotes` — tone/style reference for the new HPM notes
- `CHANGELOG.md` if it exists — format/heading-level reference. If it doesn't exist, this cut creates it.

## Step 4 — Draft the three artifacts

### Artifact A — Manifest delta (`levoitManifest.json`)

- `version`: target version (string, e.g. `"2.0"` — match prior format).
- `dateReleased`: today's date in `YYYY-MM-DD` (use `date +%Y-%m-%d` via bash).
- `releaseNotes`: **PREPEND a new line for this release to the existing string — do NOT overwrite.** The convention is a single string accumulating all versions, newest-first, one line per release, separated by literal `\n` escape sequences. Format per line:
  ```
  <version> - <user-facing description, ~2-4 sentences, no bullets>.
  ```
  Example after a 2.1 cut on a manifest that previously had only 2.0:
  ```
  "releaseNotes": "2.1 - Adds five new drivers: ...\n2.0 - First community-fork release after Niklas's repo went idle. Adds ..."
  ```
  Note: the manifest's top-level `author` field already credits the maintainer; don't repeat it inline on every release line. (Older manifests through v2.1 had `<version> - Dan Cox - ...` lines; v2.2 cut scrubbed the redundant author tag from all historical lines for consistency.)

  Reference: this matches the convention used by well-maintained Hubitat HPM packages (e.g. [tomwpublic/hubitat_SmartHQ](https://github.com/tomwpublic/hubitat_SmartHQ/blob/main/packageManifest.json) — accumulates ~15 versions back to 0.9.0). HPM displays the entire string verbatim in the update popup; cumulative notes give users updating from a stale install (e.g. v0.0 manual paste, or skipping several versions) the full context of what changed in their jump.

  Content guidance for the new line you're adding:
  - Lead with what's new from the user's perspective (new device support, fixed bugs they'd notice).
  - Mention any breaking change or migration step prominently if applicable.
  - Skip refactors, internal cleanup, test additions, doc-only changes.
  - Skip implementation details (e.g., don't say "auth-aware closure pattern" — say "fixes session expiry causing devices to go unresponsive").
  - **Skip development methodology and contributor process.** The HPM popup is for end-users clicking Update, not for developers. Don't explain how the drivers were built (e.g., "cross-referenced pyvesync, Home Assistant, SmartThings, and Homebridge community drivers"), don't mention QA / cross-check / citation policies, don't describe contributor workflow. If a user wonders *how* a feature was built, the commit history and `CHANGELOG.md` are where they look — not the HPM update popup.

  When applying: read the current `releaseNotes` value, prepend the new line + `\n`, preserve all existing lines verbatim. Do not edit historical entries.

- **Bundle URL versioning.** If `levoitManifest.json` has a `bundles[]` array, every entry's `location` URL is checked. URLs of the form `https://github.com/level99/Hubitat-VeSync/releases/download/v<old>/<filename>.zip` MUST bump to `v<new>/...` to match the new tag — even if the bundle contents are byte-identical. The asset is per-release-tag immutable; the new release-tag's asset has its own URL. If `bundles[]` is missing or empty, skip silently. If `location` does NOT match the GitHub Releases pattern (e.g. third-party CDN), do not auto-bump — surface as INFO and ask user.

### Artifact B — `CHANGELOG.md` entry

Use [Keep-a-Changelog](https://keepachangelog.com/en/1.1.0/) format. If `CHANGELOG.md` doesn't exist, create it with the standard preamble + the new entry. If it exists, prepend the new entry below `## [Unreleased]` (or below the top-of-file preamble if there's no Unreleased section).

Structure:

```markdown
## [<version>] - <YYYY-MM-DD>

### Added
- <user-meaningful additions, parsed from commits>

### Changed
- <behavior changes that aren't bug fixes>

### Fixed
- <bug fixes>

### Removed
- <only if something was removed>
```

Parse commit subjects into buckets by intent (not literal prefix — many commits don't have a clean `feat:`/`fix:` prefix in this fork). For each bucket entry, write a one-liner that's developer-facing (more technical than the HPM notes) but still readable. Reference commit hashes inline when useful: `- Foo bar (a63d1c7)`.

Skip bullets that are pure churn (CI tweaks, agent-config edits, lint exemption juggling) — CHANGELOG is for things downstream consumers care about.

### Artifact C — Driver-version surfaces (if any)

Scan for any `DRIVER_VERSION` constants or top-of-file `// vX.Y` version comments in `Drivers/Levoit/*.groovy`. These are distinct from the `version:` field in `definition()` — they are imperative version strings sometimes used by driver logic at runtime. If found and the value differs from the new package version, propose an update. If none found, note: *"No runtime DRIVER_VERSION constants to update."*

### Artifact C.7 — Per-driver `version` field lockstep

For every file matched by `Drivers/Levoit/*.groovy`:

1. Read the `definition(...)` block (from `definition(` to the matching `)` before the opening `{`).
2. Look for a `version:` field inside that block.
3. If present and the value differs from the new package version, propose updating it to match.
4. If absent, propose adding it as a new named-argument line immediately before `documentationLink:` (or before `importUrl:` / `singleThreaded:` if `documentationLink:` is absent).

Show the proposed edits in unified-diff form so the user can review before approving.

Lockstep rule: all drivers must carry the same `version` value as the package `version` field in `levoitManifest.json` at the time of every release cut. Per-driver drift (e.g. only Vital 200S at v2.0.1 while others stay at v2.0) is NOT supported in this fork. If the user wants per-driver release cycles, that is a separate architectural change outside the scope of this procedure.

Static enforcement: lint rule 20 (`tests/lint_rules/version_lockstep.py`) enforces this invariant at the static analysis layer. `uv run --python 3.12 tests/lint.py --strict` will fail with RULE20_missing_version_field or RULE20_version_drift findings if any driver drifts. The Spock harness does NOT contain a lockstep spec (it was replaced by the lint rule; cross-file consistency checks belong in the static lint layer, not in runtime tests).

### Artifact C.5 — Manifest `drivers` array reconciliation

Reconcile `levoitManifest.json`'s `drivers` array against the actual files in `Drivers/Levoit/*.groovy`:

1. Glob `Drivers/Levoit/*.groovy` to get the actual driver-file list.
2. Parse `levoitManifest.json` and extract the `drivers` array entries (each has `name`, `location` ending in a `.groovy` filename).
3. Compare:
   - **In files but NOT in manifest** → contributor added a driver file but forgot the manifest entry. Propose a new entry per file:
     - `id`: fresh UUID v4 (generate via Python: `python -c "import uuid; print(uuid.uuid4())"` if `uv` available, or `[guid]::NewGuid()` via PowerShell, or any UUID generator).
     - `name`: parse from the driver source's `definition(name: "...", ...)` block.
     - `namespace`: parse from the same block (must match — Bug Pattern #9 protection).
     - `location`: `https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/Drivers/Levoit/<filename>` (URL-encode spaces as `%20`; the parent + `Notification Tile.groovy` + `LevoitCore200S Light.groovy` use this).
     - `required`: default `false` for new drivers (only the parent `VeSync Integration` is `required: true`).
   - **In manifest but NOT in files** → contributor removed a driver file. **DO NOT auto-remove the manifest entry.** Removing a driver from a published package orphans every user's existing devices using that driver (Bug Pattern #9). Surface as a WARNING in the proposal output: *"Driver `<name>` is in the manifest but its source file is missing. This is highly likely a mistake — do NOT proceed with removal unless you've confirmed no users have devices on this driver. If intentional, the user must manually edit the manifest after applying the rest of the release."*
   - **In both, but `name` or `namespace` mismatched** → driver file's `definition()` was edited in a way that drifts from the manifest. Surface as a WARNING — driver-name changes orphan devices (Bug Pattern #9). Suggest reverting the source-side edit OR explicitly accepting the drift if intentional.

Show the proposed additions in the approval round so the user can see what's being added. The user should confirm UUIDs are sensible and the `required: false` default is right (most drivers are optional; only the parent integration is required).

### Artifact C.6 — Manifest `bundles` array reconciliation

Reconcile `levoitManifest.json`'s `bundles[]` array (if present) against the actual library files in `Drivers/Levoit/*Lib.groovy`:

1. Glob `Drivers/Levoit/*Lib.groovy` to list current library source files.
2. Parse `levoitManifest.json` and extract the `bundles[]` array entries.
3. Each bundle conceptually wraps one or more library files. For v2.4.2's single-bundle setup, the `levoit_libraries` bundle contains all `*Lib.groovy` files in `Drivers/Levoit/` (currently just `LevoitDiagnosticsLib.groovy`). As more libraries land (Phase 2-6 of the library extraction roadmap), they may stay in one bundle or split into per-family bundles — design choice per release.
4. **Detection — library file with no bundle reference:** if a `*Lib.groovy` file exists in source but `tools/build-bundle.py` doesn't include it in any bundle's build output AND no `bundles[]` entry references that bundle, surface a WARNING: *"Library file `<filename>` exists but is not declared in any bundle's build-script. Will not ship to HPM users."* Do NOT auto-add — bundle composition is a release-architecture decision. Suggest the user either (a) add the file to `tools/build-bundle.py`, or (b) accept that the library is dev-only / not for HPM distribution.
5. **Detection — bundle entry with no source backing:** if `bundles[]` references a bundle whose corresponding library files no longer exist in source, surface a WARNING in the same shape as Artifact C.5's drivers-removal warning. Removing a library that drivers `#include` would orphan every install (Bug Pattern #9 family). Do NOT auto-remove.
6. Each `bundles[].location` URL must be non-empty, https://, and (if matching the GitHub Releases pattern `https://github.com/level99/Hubitat-VeSync/releases/download/v<version>/<filename>.zip`) embed the new version per Artifact A's "Bundle URL versioning" rule.

If `bundles[]` is absent or empty AND no `*Lib.groovy` files exist in source, skip silently — this is a pre-library-era release.

### Artifact D — `ROADMAP.md` update

Read `ROADMAP.md`. Determine whether items that just shipped were tracked there (typically the "v<X.Y> — next release" section). Two cases:

- **Roadmap items shipped in this release:** propose edits to advance the roadmap. Typically that means:
  - Remove the "v<X.Y> — next release" section's items that just shipped (CHANGELOG.md is now the system-of-record for what's been delivered).
  - Re-rank the "Beyond v<X.Y>" sections — which items now move up to "v<X.Y+1> — next release" based on prior sequencing notes? Use whatever sequencing context is in `TODO.md` (if present) or `ROADMAP.md` itself, but don't invent new commitments — only promote items that were already implied as the next batch.
  - If nothing in "Beyond" was clearly queued next, leave the "next release" header advanced to `v<X.Y+1>` with a *"TBD — open for proposals"* placeholder under it.

- **Roadmap items DID NOT ship** (e.g., a patch release that only fixed bugs): note this in the proposal as *"No roadmap content advanced this release — patch / hotfix only."* No ROADMAP.md edits needed.

Show the diff in unified-diff form (or full proposed-section snippets) so the user can approve / redirect.

### Artifact E — `TODO.md` sweep (only if `TODO.md` exists locally)

Run `[ -f TODO.md ] && echo "present" || echo "absent"` to check. `TODO.md` is gitignored — only present in the maintainer's local checkout, not in clones.

- **If present:** propose edits to:
  - Add ✅ entries to the *"Done in v<X.Y>"* section (the just-shipped items)
  - Remove "in-flight before push" items now completed
  - Advance the v<X.Y+1> sequencing section forward (mark completed steps; refocus on next-up items)
  - Update community-thread tag lists if applicable

  Show the diff for approval.

- **If absent:** skip silently. Do not create `TODO.md` from scratch — it's a maintainer-personal artifact, not something to seed automatically.

### Artifact F — `CONTRIBUTING.md` drift check (only if file exists)

Run `[ -f CONTRIBUTING.md ] && echo "present" || echo "absent"` to check.

- **If absent:** skip silently. (Older clones / branches may not have it.)

- **If present:** scan the sections that drift release-over-release and propose edits where they no longer match current state:

  | CONTRIBUTING.md section | Drift source | Check |
  |---|---|---|
  | `## Codebase orientation` (repo tree) | new/renamed driver files | glob `Drivers/Levoit/*.groovy` and compare to the tree's listed files |
  | `## Adding a new device driver` (closest-existing-driver-as-template hints) | new device families | scan for hardcoded references to existing driver classes; flag if a v2.X+ family (e.g. fans added in v2.1) isn't represented |
  | `## Conventions enforced by lint/tests` (rule IDs + BP refs) | new lint rules / new bug patterns | glob `tests/lint_rules/*.py` for rule IDs and cross-check against the listed rules. Cross-check BP catalog references against `CLAUDE.md` "Bug-pattern catalog" entries |
  | `### What's still gappy in shipped previews` | preview drivers losing/gaining gaps | grep `Drivers/Levoit/*.groovy` for `[PREVIEW vX.Y]` in `definition(description: ...)` and cross-check against the listed gaps |

  Surface drift as **proposed edits** in the approval round (unified diff or proposed-section snippet). **Do not apply automatically** — content drift in a contributor-facing doc deserves a human eyeball.

  If no drift detected: report *"No CONTRIBUTING.md drift detected this release."*

### Artifact G — `README.md` drift check (only if file exists)

Run `[ -f README.md ] && echo "present" || echo "absent"` to check.

- **If absent:** skip silently.

- **If present:** scan for hardcoded version references in prose that drift release-over-release. The scan should NOT touch per-device row annotations like `*(v2.1 preview)*` — those mark when each driver was first introduced and are intentional historical markers; leaving them in place preserves provenance.

  Drift patterns to surface:

  | Pattern | Why it drifts |
  |---|---|
  | `Preview drivers are v<X.Y> drivers...` (or similar generalizing prose) | Each release adds new preview drivers. Generalize to `v<earliest>+` or version-agnostic phrasing. |
  | `For upcoming devices beyond v<X.Y>: ROADMAP.md` (or similar pointer) | The "beyond" version should bump to the version being cut. |
  | `...used as v<X.Y> cross-check sources` (acknowledgements section) | Cross-check sources are used across all releases; drop the version qualifier rather than bumping it. |

  Surface drift as **proposed edits** in the approval round (unified diff). **Do not apply automatically** — README content is user-facing and deserves a human eyeball.

  If no drift detected: report *"No README.md drift detected this release."*

### Artifact H — `repository.json` drift check (only if file exists)

Run `[ -f repository.json ] && echo "present" || echo "absent"` to check.

- **If absent:** skip silently. (Older clones or non-HPM forks may not have it.)

- **If present:** the file is a tier-2 HPM repository manifest. Scan the `description` field of each entry in the `packages` array for stale device-list references. The description typically enumerates supported device families (e.g., *"Supports air purifiers (Core 200S/300S/400S/600S, Vital 100S/200S), humidifiers (Classic 300S, ...), and fans (Tower, Pedestal)"*). Compare the listed devices against:

  | Source of truth | What to check |
  |---|---|
  | `Drivers/Levoit/*.groovy` file inventory | Every supported device family represented in the description |
  | Each driver's `definition(name: "...")` block | Marketing names match (don't invent shorthand) |
  | Recent CHANGELOG entries since last cut | Major user-facing features (e.g., EU region support, RGB nightlight) worth a brief mention if they expand the package's scope |

  This file drifted silently across v2.0 → v2.1 → v2.2 because no artifact checked it. The v2.2 cut updated the description to mention LV600S + Dual 200S humidifiers + EU region. Surface drift as a proposed JSON edit. **Do not auto-apply** — the description is user-facing in HPM keyword search.

  If no drift detected: report *"No repository.json drift detected this release."*

### Artifact I — `Drivers/Levoit/readme.md` top-blurb drift check (only if file exists)

Run `[ -f Drivers/Levoit/readme.md ] && echo "present" || echo "absent"` to check.

- **If absent:** skip silently.

- **If present:** scan the top-blurb prose (typically the first ~15 lines after the heading) for stale per-release device-addition annotations. Lines like `**Levoit X** ... — short description *(vN.M)*` record when each driver was first introduced. Compare against:

  - The driver-file inventory in `Drivers/Levoit/*.groovy`
  - Each driver's `definition(name: "...")` block
  - The version being cut

  If a driver was added in the version being cut and isn't represented in the blurb, propose adding a row with the appropriate `*(v<X.Y>)*` annotation. **Don't touch the existing rows** — those are intentional historical markers documenting when each driver first shipped.

  The driver-list table further down in this file (per-driver section headers and event/attribute tables) is typically maintained by the developer agent on each driver-add diff, not by the cut-release procedure. **Only the top-blurb prose is the cut-release responsibility.**

  Surface drift as proposed edits (unified diff or proposed-row snippet). **Do not auto-apply** — the blurb is user-facing.

  If no drift detected: report *"No Drivers/Levoit/readme.md top-blurb drift detected this release."*

### Artifact J — Hubitat community-thread announcement draft

Always draft a community-thread announcement post for every release, regardless of severity (major / minor / patch). The user posts manually on the [Hubitat thread](https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499) — DO NOT auto-apply / write to disk / post anything. This artifact's output is just the draft for the user to copy.

**Strict format (match existing announcements verbatim — v2.4 / v2.3 / v2.2.1 are the references):**

```
**v<X.Y[.Z]> is live — <em-dash subtitle, single-line summary, ~6-12 words>**

Released: [Release v<X.Y[.Z]>](https://github.com/level99/Hubitat-VeSync/releases/tag/v<X.Y[.Z]>)

**To update:** HPM → Update → "Levoit Air Purifiers, Humidifiers, and Fans". Existing devices upgrade in place; no re-pairing.

### <Section header — H3, see "Section conventions" below>

- **<Bullet subject in bold, ending with period.>** <Description: user-visible scenario where the bug bit OR what the new feature does. Include device family list if narrow.>
- **<Next bullet.>** ...

### <Optional second section>

...

### If you own <relevant device for community testing call>

<Paragraph requesting community help on devices the maintainer can't test.>

[Release notes](https://github.com/level99/Hubitat-VeSync/releases/tag/v<X.Y[.Z]>) · [CHANGELOG](https://github.com/level99/Hubitat-VeSync/blob/main/CHANGELOG.md) · [ROADMAP for v<NEXT>](https://github.com/level99/Hubitat-VeSync/blob/main/ROADMAP.md)
```

**Section conventions** (use H3 `###`, NOT H2 — convention shifted from v2.2.1's `##` to v2.3+ `###`):

| Section header | When to include |
|---|---|
| `### Bug fixes` | Always for patch releases. Common in major/minor too. |
| `### New features` | Major/minor when adding capabilities to existing drivers (e.g., `online` attribute, `captureDiagnostics`) |
| `### New drivers (preview — community feedback welcome if you own one)` | Major/minor when adding new device drivers as preview |
| `### <Driver> line back-fill (<scope>)` | Major/minor when filling in capability gaps on existing driver line (e.g., "Core line back-fill (Core 200S/300S/400S/600S)") |
| `### <Device> moves out of preview (\`<MODEL-CODE>\`)` | When promoting a preview driver to released after live verification |
| `### Other devices` | Small additions like new regional model code variants |
| `### If you own <X>` | When asking for community testing help on a specific device the maintainer can't verify |

**Bullet style:**

- **Bug-fix bullets:** lead with the user-visible symptom (what they noticed in their hub), bolded as the subject, then explain what's fixed. Pattern: `- **<Symptom>.** <Where it bit users>. <What the fix does>.`
  - Example: `- **ERROR: No status returned from getPurifierStatus after a VeSync firmware update or device re-pair** — closes the v2.3 self-heal regression. Polling now recovers without user action; no manual Resync needed.`
- **Feature bullets:** lead with the feature name, bolded as the subject, then explain what users can do with it. Pattern: `- **<Feature name>.** <What users can do with it>.`
  - Example: `- **Per-device offline detection.** Every Levoit child device exposes a new \`online\` attribute (\`true\` / \`false\`)...`

**Patch-release variant** (e.g., v2.2.1 / v2.4.1):

- Often a single bullet under `### Bug fixes`
- No `### If you own...` section (no new device support to test)
- Total length: ~120-150 words
- Title subtitle: short and specific (e.g., "setLevel auto-on hotfix" / "Resync crash fix")

**Major/minor-release variant** (e.g., v2.2 / v2.3 / v2.4):

- Multiple sections (Bug fixes + New features + New drivers + ...)
- Usually includes `### If you own <preview device>` community-feedback section
- Total length: ~200-380 words
- Title subtitle: comma-separated headline summary (e.g., "per-device offline detection, captureDiagnostics, Pedestal Fan write-path")

**TMI rules (HARD — match the rest of the cut-release procedure):**

- **No maintainer-environment references** — never write "the maintainer's hub", "post-deploy on dev1064", "verified on Master Air Purifier 1070", etc. Generalize to "live-verified on hub" or drop.
- **No personal/family detail** — never write "the kid's nap routine", "the noise machine", or anything specific to the maintainer's domestic setup.
- **No implementation jargon end-users won't recognize** — skip `MissingMethodException`, `state.turningOn`, "sandbox-swallowed", "re-entrance flag", lint rule numbers, exception class FQNs. Reword in plain language symptom + outcome.
- **No pipeline-process detail** — never mention dev/QA/tester rounds, agent dispatches, model choices, "QA APPROVE'd through 3 rounds".
- **No CI infrastructure changes** — uv-pin / setup-action chores / dependabot bumps / etc. don't belong in the user-facing announce. They live in CHANGELOG / commit / GitHub Release for archaeology.
- **Skip latent bug fixes that were sandbox-swallowed in production** — if a bug never produced user-visible behavior pre-fix, don't list it (users won't recognize the change). Same logic as the manifest releaseNotes guidance.

**Sources for content:**

- Title subtitle: derived from the CHANGELOG `[<version>]` headline + commit messages. Match `levoitManifest.json` releaseNotes line tone.
- Bullets: parsed from the CHANGELOG entry, but rewritten user-facing (CHANGELOG is more technical; community announce is plain-language).
- "If you own X" section: only when ROADMAP.md or recent CHANGELOG flags a community-tester ask for a specific device.

**Output:** show the draft verbatim (in a code block) so the user can copy-paste. NEVER post it; the user posts manually.

## Step 5 — Present for approval

Output a single message structured as:

```
## Proposed v<version> release

**Date:** <YYYY-MM-DD>
**Branch:** <branch name>
**Baseline:** <baseline ref> (<N> commits, <X> files changed)

### Artifact A — levoitManifest.json delta

```json
{
  "version": "<new>",
  "dateReleased": "<date>",
  "releaseNotes": "<curated paragraph verbatim>"
}
```

### Artifact B — CHANGELOG.md entry

\`\`\`markdown
<entry as it will appear in the file>
\`\`\`

### Artifact C — driver-version surfaces

<list of runtime DRIVER_VERSION constants, or "None">

### Artifact C.7 — per-driver version field lockstep

<unified diff of version: field additions/updates per .groovy file, or "No drift detected — all drivers already at vX.Y">

### Artifact C.5 — manifest drivers-array reconciliation

<list of additions / WARNINGs / "No drift detected">

### Artifact C.6 — manifest bundles-array reconciliation

<list of additions / WARNINGs / "bundles[] absent — skipping" / "No drift detected; bundle URL bumped to v<version>">

### Artifact D — ROADMAP.md updates

<unified diff or proposed-section snippet, or "No roadmap content advanced this release">

### Artifact E — TODO.md sweep

<unified diff or proposed-section snippet, or "TODO.md not present locally — skipping">

### Artifact F — CONTRIBUTING.md drift check

<unified diff or proposed-section snippet, or "No CONTRIBUTING.md drift detected this release" or "CONTRIBUTING.md not present — skipping">

### Artifact G — README.md drift check

<unified diff or proposed-section snippet, or "No README.md drift detected this release" or "README.md not present — skipping">

### Artifact H — repository.json drift check

<proposed JSON edit to description field, or "No repository.json drift detected this release" or "repository.json not present — skipping">

### Artifact I — Drivers/Levoit/readme.md top-blurb drift check

<unified diff or proposed-row snippet, or "No Drivers/Levoit/readme.md top-blurb drift detected this release" or "Drivers/Levoit/readme.md not present — skipping">

### Artifact J — Hubitat community-thread announcement draft

\`\`\`
<full announcement draft, verbatim, in the format documented in Step 4 Artifact J — title + Released-link + To update + sections + footer>
\`\`\`

(Draft only. The user posts manually on the Hubitat community thread; this artifact never auto-applies.)

---

Approve to apply, or tell me what to change.
```

Wait for explicit approval. If the user redirects on any artifact, redraft that artifact only and re-present.

## Step 6 — Apply on approval

After explicit approval (e.g., "approved", "go", "ship it"):

- Edit `levoitManifest.json`: update `version`, `dateReleased`, `releaseNotes`. Apply any Artifact C.5 drivers-array additions (do NOT remove entries on `WARNING:` cases — those need human-only review per the spec). Apply Artifact C.6 bundles-array URL version bumps (location URLs of the form `releases/download/v<old>/...` → `releases/download/v<new>/...`). Leave `packageName`, `author`, `documentationLink`, `communityLink`, `licenseFile` untouched (those are stable fields changed manually outside of release cuts).
- Edit (or create) `CHANGELOG.md`: prepend the new entry per Step 4 Artifact B.
- Apply any Artifact C edits if applicable (runtime DRIVER_VERSION constants).
- Apply Artifact C.7 edits: for each `.groovy` file in `Drivers/Levoit/`, update or add the `version:` field inside `definition()` to match the new package version.
- Apply Artifact D `ROADMAP.md` edits if any were proposed.
- Apply Artifact E `TODO.md` edits if `TODO.md` exists locally and edits were proposed.
- Apply any Artifact F `CONTRIBUTING.md` edits if proposed and approved.
- Apply any Artifact G `README.md` edits if proposed and approved.
- Apply any Artifact H `repository.json` edits if proposed and approved.
- Apply any Artifact I `Drivers/Levoit/readme.md` edits if proposed and approved.
- **Artifact J community announcement draft is NEVER auto-applied** — it lives in the Step 5 proposal output for the user to copy-paste manually onto the Hubitat thread. Do not write to disk. Do not post anywhere. Do not commit.

Do NOT commit. Do NOT tag. Do NOT push.

## Step 7 — Final report

Print a short summary:

```
v<version> release prepared. Files staged-ready (uncommitted):
  - levoitManifest.json
  - CHANGELOG.md
  - Drivers/Levoit/*.groovy   (version: field lockstep — all N files updated)
  - ROADMAP.md         (if advanced)
  - TODO.md            (if present locally and swept)
  - CONTRIBUTING.md    (if drift detected and approved)
  - README.md          (if drift detected and approved)
  - repository.json    (if drift detected and approved)
  - Drivers/Levoit/readme.md  (if top-blurb drift detected and approved)

Next steps for you:
  1. Review with: git diff
  2. Commit (preview-before-publish: ask for a message draft)
  3. Push the release branch to origin: git push -u origin <branch>
  4. Build the HPM bundle ZIP from current source (only if levoitManifest.json
     has a bundles[] array — otherwise skip to step 7):
       uv run --python 3.12 tools/build-bundle.py
     Verify output at bundles/levoit_libraries.zip (gitignored, never committed).
  5. Create the GitHub Release WITH the bundle asset attached. This creates the
     tag, publishes the release, and uploads the asset URL the manifest references:
       gh release create v<version> \
         --target release/v<version> \
         --title "v<version> — <release subject>" \
         --notes-file <release notes file> \
         ./bundles/levoit_libraries.zip
  6. Verify the asset URL serves BEFORE merging to main:
       curl -I https://github.com/level99/Hubitat-VeSync/releases/download/v<version>/levoit_libraries.zip
     Expect HTTP 302 (redirect chain to GitHub's S3 storage). If 404, the asset
     upload failed — re-check step 5 and re-verify before proceeding.
  7. Open PR <branch> -> main via `gh pr create` (preview-before-publish: ask
     for a body draft). PR path is REQUIRED — every release goes through PR
     review even for one-line hotfixes. Do NOT propose direct/fast-forward
     merge as an alternative; the maintainer's branch-protection admin-bypass
     exists for genuine emergency hotfixes (production-broken-now scenarios),
     not for cut-release flow. Bypass is the maintainer's call to invoke at
     the time of the actual emergency, not a cut-release option to surface.
  8. Iterate on PR review. Gemini Code Assist auto-fires on PR open
     (configured via .gemini/config.yaml); maintainer reviews + addresses
     Gemini feedback. Both must be addressed (or explicitly waived with
     rationale) before merge.
  9. Squash-merge to main once review is clean.
 10. Hubitat community-thread announce — copy-paste the Artifact J draft from
     the Step 5 proposal output (no edits needed; format matches v2.4 / v2.3 /
     v2.2.1 conventions). Post on:
     https://community.hubitat.com/t/release-levoit-air-purifiers-humidifiers-and-fans/163499

Note: TODO.md is gitignored, so even if it was updated it stays local-only.
Don't try to git-add it.

ORDERING WHY: the bundle asset URL must be live BEFORE the manifest on `main`
references it. HPM users clicking Update fetch the manifest from `main` and
immediately try to fetch the bundle from the URL it specifies. If the merge
happens before `gh release create`, there is a window where the manifest is
published but the asset URL returns 404 — Update fails and users are stuck.
Steps 4-6 close that window: build the artifact, publish the release+tag with
the asset attached, verify the URL, THEN merge.

For releases that do NOT change any library source AND do NOT need a bundle URL
bump (rare — typically only patch releases that fix a single driver and don't
touch a *Lib.groovy file), skip steps 4-6 entirely and renumber 7 onward.
Re-using a prior release's bundle is fine; the manifest's bundle URL just
points at the prior tag's asset.
```

Stop. The user owns commit/push/PR/tag/release under preview-before-publish.

## Notes

- This procedure does NOT push to remote, does NOT open a PR, does NOT tag, does NOT update GitHub releases. Those happen across the steps above:
  * Push branch is POST-CUT (step 3).
  * Build bundle ZIP + `gh release create` (which creates the tag and uploads the asset) + URL verification are POST-CUT, PRE-MERGE (steps 4-6) — required ordering so the asset URL is live before HPM users fetch the manifest from main.
  * Open PR + iterate review + squash-merge are POST-RELEASE-PUBLISH (steps 7-9).
  * `gh release create` targets the release branch HEAD (e.g. `--target release/v<version>`); the resulting tag points at that commit. After squash-merge to main, the same commit lives on main's history (fast-forward or squash-merge equivalent), so the tag remains valid for HPM URL resolution.
- If the user runs this with uncommitted in-progress work other than `TODO.md`, stop and ask — they may have intended to commit first.
- The `TODO.md` file is intentionally gitignored / not part of releases. Do not surface it in the release diff.
- For dry-run / preview-only mode, the user can say "draft only, don't apply" — produce Step 5 output and stop without waiting for approval.
