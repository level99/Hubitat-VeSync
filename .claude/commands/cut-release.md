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
  <version> - <author> - <user-facing description, ~2-4 sentences, no bullets>.
  ```
  Example after a 2.1 cut on a manifest that previously had only 2.0:
  ```
  "releaseNotes": "2.1 - Dan Cox - Adds five new drivers: ...\n2.0 - Dan Cox - First community-fork release after Niklas's repo went idle. Adds ..."
  ```

  Reference: this matches the convention used by well-maintained Hubitat HPM packages (e.g. [tomwpublic/hubitat_SmartHQ](https://github.com/tomwpublic/hubitat_SmartHQ/blob/main/packageManifest.json) — accumulates ~15 versions back to 0.9.0). HPM displays the entire string verbatim in the update popup; cumulative notes give users updating from a stale install (e.g. v0.0 manual paste, or skipping several versions) the full context of what changed in their jump.

  Content guidance for the new line you're adding:
  - Lead with what's new from the user's perspective (new device support, fixed bugs they'd notice).
  - Mention any breaking change or migration step prominently if applicable.
  - Skip refactors, internal cleanup, test additions, doc-only changes.
  - Skip implementation details (e.g., don't say "auth-aware closure pattern" — say "fixes session expiry causing devices to go unresponsive").
  - **Skip development methodology and contributor process.** The HPM popup is for end-users clicking Update, not for developers. Don't explain how the drivers were built (e.g., "cross-referenced pyvesync, Home Assistant, SmartThings, and Homebridge community drivers"), don't mention QA / cross-check / citation policies, don't describe contributor workflow. If a user wonders *how* a feature was built, the commit history and `CHANGELOG.md` are where they look — not the HPM update popup.

  When applying: read the current `releaseNotes` value, prepend the new line + `\n`, preserve all existing lines verbatim. Do not edit historical entries.

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

### Artifact D — ROADMAP.md updates

<unified diff or proposed-section snippet, or "No roadmap content advanced this release">

### Artifact E — TODO.md sweep

<unified diff or proposed-section snippet, or "TODO.md not present locally — skipping">

### Artifact F — CONTRIBUTING.md drift check

<unified diff or proposed-section snippet, or "No CONTRIBUTING.md drift detected this release" or "CONTRIBUTING.md not present — skipping">

---

Approve to apply, or tell me what to change.
```

Wait for explicit approval. If the user redirects on any artifact, redraft that artifact only and re-present.

## Step 6 — Apply on approval

After explicit approval (e.g., "approved", "go", "ship it"):

- Edit `levoitManifest.json`: update `version`, `dateReleased`, `releaseNotes`. Apply any Artifact C.5 drivers-array additions (do NOT remove entries on `WARNING:` cases — those need human-only review per the spec). Leave `packageName`, `author`, `documentationLink`, `communityLink`, `licenseFile` untouched (those are stable fields changed manually outside of release cuts).
- Edit (or create) `CHANGELOG.md`: prepend the new entry per Step 4 Artifact B.
- Apply any Artifact C edits if applicable (runtime DRIVER_VERSION constants).
- Apply Artifact C.7 edits: for each `.groovy` file in `Drivers/Levoit/`, update or add the `version:` field inside `definition()` to match the new package version.
- Apply Artifact D `ROADMAP.md` edits if any were proposed.
- Apply Artifact E `TODO.md` edits if `TODO.md` exists locally and edits were proposed.
- Apply any Artifact F `CONTRIBUTING.md` edits if proposed and approved.

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

Next steps for you:
  1. Review with: git diff
  2. Commit (preview-before-publish: I'll draft the message when you ask)
  3. Tag: git tag -a v<version> -m "<release subject>"
  4. Push branch + tag: git push origin <branch> v<version>

Note: TODO.md is gitignored, so even if it was updated it stays local-only.
Don't try to git-add it.
```

Stop. The user owns commit/tag/push under preview-before-publish.

## Notes

- This procedure does NOT push to remote, does NOT open a PR, does NOT update GitHub releases. Those are post-merge steps after maintainer review.
- If the user runs this with uncommitted in-progress work other than `TODO.md`, stop and ask — they may have intended to commit first.
- The `TODO.md` file is intentionally gitignored / not part of releases. Do not surface it in the release diff.
- For dry-run / preview-only mode, the user can say "draft only, don't apply" — produce Step 5 output and stop without waiting for approval.
