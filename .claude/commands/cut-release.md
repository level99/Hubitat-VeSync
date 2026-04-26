---
description: Cut a new release of the Hubitat-VeSync fork — analyze commits, draft manifest + CHANGELOG updates, present for approval, apply on go-ahead.
---

You are cutting a new release of the level99/Hubitat-VeSync fork. Run the procedure below end-to-end. Do not skip steps; do not commit or tag (the user does that manually per preview-before-publish rule).

## Step 1 — Pre-flight checks

Run these in parallel and report any failure:

- `git status --short` — working tree should be clean (only `TODO.md` may be untracked; any other uncommitted change blocks the release).
- `JAVA_HOME=~/.gradle/jdks/jdk-17.0.18+8 ./gradlew test --no-daemon` — Spock harness must PASS.
- `/c/Users/dcox.WEP.original/.local/bin/uv run --python 3.12 tests/lint.py --strict` — lint must PASS clean.
- `git rev-parse --abbrev-ref HEAD` — confirm the branch (typically `release/v<version>` or `master`).

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
- `releaseNotes`: **single paragraph, ~3-5 sentences, user-facing prose, no bullets, no implementation jargon**. This is what pops up in HPM when a user clicks Update — write it for a Hubitat user, not a developer. Match the tone of the prior `releaseNotes` (read it for reference).

  Content guidance:
  - Lead with what's new from the user's perspective (new device support, fixed bugs they'd notice).
  - Mention any breaking change or migration step prominently if applicable.
  - Skip refactors, internal cleanup, test additions, doc-only changes.
  - Skip implementation details (e.g., don't say "auth-aware closure pattern" — say "fixes session expiry causing devices to go unresponsive").

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

Currently, driver source files do **not** carry per-driver version constants — the package version lives only in `levoitManifest.json`. If this changes in the future, scan for `DRIVER_VERSION` or top-of-file `// vX.Y` comments in `Drivers/Levoit/*.groovy` and propose updates here. For now, this artifact is empty for most cuts. Note it explicitly: *"No driver-version surfaces to update."*

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

<list, or "None">

### Artifact D — ROADMAP.md updates

<unified diff or proposed-section snippet, or "No roadmap content advanced this release">

### Artifact E — TODO.md sweep

<unified diff or proposed-section snippet, or "TODO.md not present locally — skipping">

---

Approve to apply, or tell me what to change.
```

Wait for explicit approval. If the user redirects on any artifact, redraft that artifact only and re-present.

## Step 6 — Apply on approval

After explicit approval (e.g., "approved", "go", "ship it"):

- Edit `levoitManifest.json`: update `version`, `dateReleased`, `releaseNotes` only. Leave the `drivers` array untouched.
- Edit (or create) `CHANGELOG.md`: prepend the new entry per Step 4 Artifact B.
- Apply any Artifact C edits if applicable.
- Apply Artifact D `ROADMAP.md` edits if any were proposed.
- Apply Artifact E `TODO.md` edits if `TODO.md` exists locally and edits were proposed.

Do NOT commit. Do NOT tag. Do NOT push.

## Step 7 — Final report

Print a short summary:

```
v<version> release prepared. Files staged-ready (uncommitted):
  - levoitManifest.json
  - CHANGELOG.md
  - ROADMAP.md         (if advanced)
  - TODO.md            (if present locally and swept)
  - <any driver source files updated>

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
