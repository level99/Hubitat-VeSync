# CLAUDE.md — contributor workflow for Hubitat-VeSync

This file is the **AI-pipeline overlay** for the repo. It assumes you've read [`CONTRIBUTING.md`](CONTRIBUTING.md) — the canonical contributor onboarding (codebase tour, conventions, dev env, test runners, PR flow, preview-driver protocol). Both audiences land at CONTRIBUTING.md for the shared foundation; this file adds AI-specific mechanics on top: the 4-agent dev/QA/tester/operations dispatch protocol, agent resume conventions via SendMessage, cost-optimization rules around fresh-vs-resume dispatches, and the bug-pattern catalog that lint rules and Spock specs reference numerically.

If you're a human contributor, you can stop reading here — `CONTRIBUTING.md` has everything you need. If you're an AI session, continue.

---

## Project documentation files — when to load

Beyond `CLAUDE.md` (always loaded), the following docs live in the repo. **Read them on-demand** when their content is relevant to the user's question — don't preemptively load them all.

| File | Purpose | When to read |
|---|---|---|
| `README.md` | Top-level repo overview, install instructions, supported-device matrix | User asks about install, what the fork does, repo overview |
| `Drivers/Levoit/readme.md` | Per-driver feature/event tables, capabilities, preferences | User asks about a specific driver's features, events, or how a model behaves |
| `CONTRIBUTING.md` | Canonical contributor onboarding — codebase tour, dev env, conventions, test runners, PR flow, preview-driver protocol. Useful for humans and AI sessions both. | **Read first when contributing.** Trigger phrases: *"contributing"*, *"how do I open a PR"*, *"contributor guide"*, *"set up dev environment"*, *"add a new device"*, *"adding a driver"*, *"running tests"*, *"lint rules"*. |
| `ROADMAP.md` | Public roadmap — future releases, device-support tiers, speculative API questions, naming traps | Trigger phrases: *"roadmap"*, *"future release"*, *"next version"*, *"v2.X"* (where X is unshipped), *"upcoming"*, *"planned"*, *"any plans for &lt;model&gt;"*, *"what's coming"* |
| `CHANGELOG.md` | Release-by-release change history (Keep-a-Changelog format) | Trigger phrases: *"changelog"*, *"release notes"*, *"what changed"*, *"what shipped in v2.X"* |
| `docs/migration-from-niklas-upstream.md` | Step-by-step migration guide for users coming from the original NiklasGustafsson/Hubitat upstream | Trigger phrases: *"migration"*, *"upgrade from upstream"*, *"moving from Niklas"*, *"v1 to v2"*, *"existing devices break after install"* |
| `CODE_OF_CONDUCT.md` | Community-conduct standard (Contributor Covenant 2.1). | Trigger phrases: *"code of conduct"*, *"community rules"*, *"incident reporting"* |
| `levoitManifest.json` | HPM package manifest | Trigger phrases: *"HPM"*, *"package manifest"*, *"manifest"*, *"Hubitat Package Manager"*, anything about install via HPM |
| `.gemini/config.yaml` (when present) | Gemini Code Assist auto-review configuration for this repo | Trigger phrases: *"gemini"*, *"auto-review"*, *"PR review bot"* |

### Maintainer-local files (NOT in clones)

| File | Purpose | When to read |
|---|---|---|
| `TODO.md` | **Maintainer-private working notes** — gitignored. Only present in the maintainer's local checkout. Holds in-flight scope decisions, sequencing detail, community-thread tag lists, and other content not appropriate for public repos. | **Read only if the file exists locally.** Check via `[ -f TODO.md ]` first. Trigger phrases (when present): *"todo"*, *"what's in flight"*, *"in-flight work"*, *"next steps for &lt;current release&gt;"*. |

### Loading discipline

- **Never assume a file exists.** When a trigger phrase fires, glob/check first. `ROADMAP.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, etc. may not exist in older clones or branches.
- **Don't dump file contents into chat.** Read the file, extract what's relevant, paraphrase for the user. Quote verbatim only when correctness depends on it.
- **`TODO.md` discipline:** if it doesn't exist, treat the user's "todo" question as a request about `ROADMAP.md` (public-facing) instead. Don't suggest creating `TODO.md` from scratch — it's a maintainer-personal artifact.

---

## The pipeline (HARD rule)

**Every code change goes through this pipeline. Do not skip steps.**

```
   ┌──────────────────┐
   │ Main session     │
   │ (you, the orch.) │
   └─────────┬────────┘
             │ 1. Dispatch via Agent({subagent_type: 'vesync-driver-developer', name: 'dev', prompt: '<task>'})
             ▼
   ┌──────────────────┐
   │ vesync-driver-   │
   │ developer        │ ← writes code, returns diff summary
   │ (Sonnet)         │
   └─────────┬────────┘
             │ 2. Diff returned to main
             ▼
   ┌──────────────────┐
   │ Main session     │
   │ summarizes diff  │
   │ briefly          │
   └─────────┬────────┘
             │ 3. Agent({subagent_type: 'vesync-driver-qa', name: 'qa', prompt: 'Review this diff: <diff>'})
             ▼
   ┌──────────────────┐
   │ vesync-driver-qa │ ← reviews diff against bug-pattern catalog + canonical pyvesync
   │ (Sonnet by       │   returns APPROVE or ISSUES (BLOCKING/NIT)
   │  default; Opus   │   See "QA dispatch: model selection" below for elevation criteria
   │  on elevation)   │
   └─────────┬────────┘
             │ 4. Verdict returned to main
             ▼
       ┌─────┴──────┐
     ISSUES       APPROVE
       │            │
       │            ▼
       │     ┌──────────────────┐
       │     │ vesync-driver-   │ ← runs ./gradlew test (Spock harness)
       │     │ tester (Haiku)   │   returns PASS / FAIL / UNCERTAIN
       │     └─────────┬────────┘
       │               │ 4b. Verdict returned to main
       │               ▼
       │         ┌─────┴─────┐
       │       FAIL/        PASS
       │       UNCERTAIN      │
       │         │            ▼
       │         │     ┌──────────────────┐
       │         │     │ With MCP:        │
       │         │     │ vesync-driver-   │
       │         │     │ operations       │
       │         │     │ (Haiku) — deploy │
       │         │     │ + verify on hub  │
       │         │     │                  │
       │         │     │ Without MCP:     │
       │         │     │ commit + return  │
       │         │     │ manual deploy    │
       │         │     │ steps to user    │
       │         │     └──────────────────┘
       │         │
       │         └─→ same loop as ISSUES path below
       │
       │ 5. Main relays issues briefly:
       │    "QA found N issues:
       │     - BLOCKING: [...]
       │     - NIT: [...]"
       │
       │ 6. SendMessage({to: '<dev-agent-id>', message: 'QA feedback: ...'})
       │    (agent IDs are returned by Agent dispatch — see Rule 3 below)
       ▼
   ┌──────────────────┐
   │ vesync-driver-   │ ← RESUMED — keeps cache of prior context.
   │ developer        │   Don't dispatch fresh; that wastes cache.
   └─────────┬────────┘
             │ 7. Updated diff → return to step 3
             ▼
       (loop until APPROVE → tester PASS)
```

### Rules

1. **Always dispatch the developer first**, never edit driver code directly from the main session. The agent has the bug-pattern catalog and canonical-payload references in its context; you don't. (Exceptions: editorial doc work, agent-definition tweaks, and similar non-driver-code changes — those can be done in main session since they're outside the bug-pattern catalog the pipeline catches.)

2. **Always run QA before tester or deploy.** Even for "trivial" changes — many bugs in this codebase were "trivial" until they shipped. After QA APPROVE, run tester (Spock harness) before deploy/commit. Tester catches regressions QA can't (compile errors, runtime sandbox gaps, behavior assertions).

3. **Resume vs fresh dispatch — observe cache state.** Anthropic's prompt cache TTL is 5 min default, up to 1 hr with explicit breakpoints. On cache miss, the agent's accumulated transcript re-prices as fresh input. Capture `agentId` from each dispatch result + `date -Iseconds` per-role; on next dispatch need, compute time delta against same role's last timestamp and decide:

   | Time since same-role last dispatch | Topic relevance | Action |
   |---|---|---|
   | <5 min | Same topic family | `SendMessage` resume (cache likely warm) |
   | 5-30 min | Same topic family | Resume if accumulated transcript is small; fresh if large |
   | 5-30 min | Unrelated topic | Fresh `Agent({...})` dispatch |
   | >30 min | Any | Fresh dispatch (cache definitely expired) |
   | New Claude Code session | Any | Fresh dispatch |

   **Resume protocol:** `SendMessage({to: '<agentId>', ...})` — addresses by **agent ID, NOT** the `name:` parameter (which is just a UI label). If `SendMessage` returns `success: false`, fresh-dispatch immediately. **Fresh re-dispatch must re-supply context** — file paths, task recap, prior findings, specific delta — since the new agent has no memory of prior rounds.

4. **Brief human-readable summary between rounds — but don't block on user response.** When QA or tester returns findings, output a concise summary to the main chat BEFORE forwarding to the developer. Then proceed directly to the SendMessage handoff. The user reads the summary and can interrupt if they want to redirect; otherwise the pipeline keeps flowing. Within a warm-cache pipeline, iterate without prompting unless an architectural decision, failure, or outbound action is involved. Example:

   > QA found 3 issues on the V201S diff:
   > - BLOCKING: Bug Pattern #4 — setLevel uses `switchIdx` instead of `levelIdx` (line 234)
   > - BLOCKING: missing 2-arg update signature (line 412)
   > - NIT: log message leaks full token (line 78) — suggest `.take(8) + "..."`
   >
   > Resuming developer with fixes.

5. **The developer doesn't deploy or test on its own.** It returns a diff summary. Tester runs `./gradlew test`. Deploy/commit happens from the main session.

6. **The QA and tester don't write code.** QA returns suggestions; tester returns PASS/FAIL/UNCERTAIN with verbatim quotes. The developer applies fixes.

7. **Tester UNCERTAIN handling.** If the tester reports UNCERTAIN (test output it couldn't classify — flaky-looking spec, unfamiliar Spock error, transient environment issue), main reviews the verbatim output and decides: benign (tell tester to tag and continue), concerning (feed back to dev or escalate). Don't let UNCERTAIN sit — it blocks PASS. For benign patterns the tester confirmed once, those stay benign across rounds (cache discipline).

8. **Tester runs lint first (fast static check) then Spock harness.** If lint FAILs, tester returns immediately with lint findings; Spock skipped (would produce noisy results on top of structural bugs). Run `uv run --python 3.12 tests/lint.py` to invoke lint standalone.

9. **Iteration cap: 3 rounds.** If QA flags BLOCKING three rounds in a row OR tester returns FAIL after 2 rounds of dev fixes on the same specs, escalate to human. Usually means the spec is wrong, not the code.

10. **Honest pushback on disagreements.** If the developer thinks QA's feedback would cause a regression, surface the disagreement to the human user; don't rubber-stamp.

11. **NITs are not optional unless explicitly deferred.** When QA returns a NIT alongside (or without) a BLOCKING, the default action is to fix it in the same round as any BLOCKING — same dispatch to dev, same re-review by QA. A NIT is "nice-to-fix" in the sense that it doesn't block production correctness; it is NOT "skip silently." Skipping a NIT is allowed only when the orchestrator (you, main session) makes an explicit decision to defer:
    - Surface the deferral to the user with rationale ("Deferring NIT 2 to v<next>: cosmetic helper-name; no shipping risk")
    - Record it as a TaskCreate entry naming the next-release target
    - Add a v<next>-candidate item to TODO.md (or ROADMAP.md if user-facing)

    Silent skipping is a process bug. The v2.2.1 cycle had a NIT almost lost between QA round 2 and round 3 — that triggered the TaskCreate-for-multi-fix-releases discipline; this rule extends that lesson into a hard default. Bias toward fixing; require an explicit decision to defer.

---

## Workflow optimizations (lessons learned)

These rules came out of the v2.2 / v2.2.1 release cycles where the orchestrator (main session) drifted from the pipeline rules above and burned user time on rework. They reinforce the existing pipeline rather than replace it.

### TMI rules for outbound text

When drafting commit messages, release notes, PR bodies, or community-forum posts, omit by default:

- **References to the maintainer's hub or production environment** ("the maintainer's hub", "production hub", "post-deploy on dev1064"). Replace with neutral phrasing ("post-deploy", "in production") or drop entirely.
- **Pipeline-process detail** (number of dev/QA/tester rounds, agent dispatches, "QA APPROVE'd through 3 rounds", model choices). The audience reads diffs and CHANGELOG, not pipeline state.
- **Implementation jargon users won't recognize** in HPM popups / community posts (`MissingPropertyException`, `sanitize()` routing, helper-extraction patterns, lint rule numbers, exception class FQNs). Reword to symptom-and-fix in plain language.
- **HPM upgrade boilerplate** in `levoitManifest.json releaseNotes`: `"Existing v<prev> users upgrade in place via HPM; no device re-pairing required."` is understood by HPM users and adds noise. Skip unless the release has a NON-trivial migration step (v2.0's Vital 200S / Superior 6000S Device Type re-pick is the bar — that's worth keeping).
- **Hardware-specific test details** in user-facing release notes ("verified on Vital 200S 1847 + Superior 6000S 1848 deploys"). Generalize to "Live-verified on hub post-deploy" or drop.

Self-check before showing draft for approval: would dropping this line confuse a future user reading the CHANGELOG / release notes a year from now? If no, drop it.

### TaskCreate discipline for multi-fix releases

When a release cycle accumulates >2 distinct bugs/items tracked across multiple pipeline rounds (e.g., 6 bugs + 2 NITs + Gemini finding in v2.2.1), use TaskCreate to maintain state. Update task status (in_progress when starting, completed when done) as each item moves through dev → QA → tester → ops.

Why: prevents re-narrating "where are we?" at every handoff. Chat narrative gets long; a task list is the canonical state. Surfaces dropped items earlier (a NIT was almost lost between QA round 2 and round 3 of v2.2.1).

When NOT to use: single-bug fixes, doc-only changes, single-round commits. Threshold is roughly 3+ items or 2+ pipeline rounds.

### Batch fix-rounds, audit-first when triaging

Default to batch dispatch over sequential when:

- **Multiple bugs surface in close session window** — hold ~1 round before dispatching dev; consolidate. v2.2.1 dispatched dev 4 times (Bugs 1/2/3 → +Bug 4/5/1b/6 → NITs → Gemini fix); could've been 2 with consolidation.
- **One bug surfaces in an actively-reviewed area** (e.g., user is reading logs, just bought new hardware, just installed via HPM) — spawn an audit agent BEFORE dev to find related issues, then dispatch dev with the bundled scope. The Core line childLock observation is the canonical example: user flagged one missing feature; an audit-first agent found 5 more in the same drivers.

Why: each dev round = SendMessage cost + QA + tester re-runs downstream + ops re-verify. Compounds badly.

When NOT to batch: critical-path bugs blocking production (ship the fix immediately, batch the polish separately). Or when bug surfaces are fundamentally unrelated.

### Pre-flight before any tester dispatch

Before dispatching tester (or sending SendMessage to resume tester), confirm:

(a) QA has APPROVE'd the EXACT current diff state, OR
(b) The tester run is a standalone sanity check on already-QA'd / already-committed code (e.g., pre-PR baseline run after a rebase)

If neither holds — STOP. Dispatch QA first.

Drift pattern observed in v2.2.1: dev returned with fix → orchestrator went directly to tester to save round-trip → user caught the missing QA step → had to backfill QA. The QA step is cheap (Sonnet, small diff); skipping creates rework when QA finds something tester wouldn't catch (logging discipline, design quality, cross-pattern interactions, PII routing).

### Per-commit CHANGELOG discipline (prevention layer)

Every `feat:` or `fix:` commit on a release branch MUST include a one-line bullet update to `CHANGELOG.md`'s `[Unreleased]` section in the same diff. This is the prevention layer; the `/cut-release` pre-flight CHANGELOG drift check is the safety net.

**Format:** match existing entries — short prose under the appropriate Keep-a-Changelog header (`Added` / `Changed` / `Fixed` / `Removed`), optional commit-hash hint at end.

**Skip CHANGELOG update for:**
- Pure refactors with no behavior change
- Doc-only changes (CONTRIBUTING / CLAUDE / agent specs / ROADMAP / TODO)
- Test-only changes (Spock specs / fixtures / lint tweaks)
- Tooling / CI / build-config changes
- Pipeline-process/workflow updates that don't touch user-visible driver code

**When in doubt, add the entry** — over-disclosure beats drift. Drift accumulates silently (every "trivial" feat: that didn't update CHANGELOG compounds), and post-hoc reconstruction at cut time is slower than per-commit habit.

**Why both layers?** The detective layer (cut-release scanner, shipped in v2.4 commit `ec5af15`) catches misses but only at cut time, after the diff has already been merged into the release branch. By then, untangling which commits should have included `[Unreleased]` updates requires re-reading every feat/fix in the cycle and reverse-engineering the rationale. The preventive layer (this rule + the dev agent's rule 4a) keeps each commit honest at write time, which is where the context lives.

Drift signal: `0427455` "docs(v2.4): catch up [Unreleased] CHANGELOG with v2.4 cycle work" — single backfill commit needed precisely because per-commit discipline wasn't in place. After this rule lands, future cycles shouldn't need a catch-up commit.

### HPM stale-state recovery (maintainer-only)

When ops verification deploys a release-candidate driver to the maintainer's hub via MCP `update_driver_code` BEFORE the cut commit / squash-merge / HPM publish, HPM's internal tracking falls behind reality. The hub's source is post-target-version, but HPM still records the prior released version.

When the user later clicks HPM Update for that release, HPM tries to upgrade `<old-version> → <new-version>`, hits a state-mismatch check (the source is already past `<old-version>`), and fails with: *"Failed to upgrade driver ... Be sure the package is not in use with devices."*

Recovery sequence (order matters):

1. **HPM → Repair** → select "Levoit Air Purifiers, Humidifiers, and Fans". Repair re-fetches the manifest and reconciles HPM's tracking.
2. After Repair, the parent driver's polling cron may not re-arm cleanly. **Run `forceReinitialize` on the parent device** to re-establish the BP14 schedule() cron + reset the BP12 pref-seed state. Verify via `get_attribute heartbeat` returning `"synced"`.
3. If heartbeat doesn't recover within ~60s, click Save Preferences on the parent (re-runs `updated()` → `initialize()` directly).

Avoidance: don't deploy uncommitted working-tree code via MCP to the maintainer's hub if that hub is also where HPM updates are tested. Either keep the maintainer's hub on HPM-published versions only, or accept the post-cut Repair routine as standard.

This is a **maintainer-only** scenario; end users never trip this because they only get drivers via HPM. Mention this in the cut-release post-flight if HPM testing is part of the verification step.

---

## QA dispatch: model selection

The `vesync-driver-qa` agent defaults to **Sonnet** as of v2.2 (was Opus through v2.1). Per the agent's own self-analysis after the v2.1 review cycles, Sonnet produces equivalent verdicts at ~5× lower per-token cost for most diffs in this codebase. Opus remains worth the cost for a specific subset.

### Default: Sonnet

Use Sonnet for the typical case (no `model:` override needed; agent definition's `model: sonnet` frontmatter handles it):

- Pure refactors with bit-identical-behavior claims
- Single-pattern bug fixes that match a documented catalog entry (BP1, BP4, BP6, etc.)
- Doc-only changes
- Spec-only changes (test additions, fixture updates)
- Per-driver `version:` lockstep bumps, manifest updates, frozen_driver_names additions

### Elevate to Opus

Pass `model: "opus"` in the `Agent({...})` dispatch (or `model: "opus"` override on `SendMessage` resume — whatever the runtime supports) for:

- **Auth / credential / token-handling code paths.** Catastrophic-failure cost; rare; Opus's nuanced judgment is worth it.
- **New bug pattern being introduced.** First instance of a pattern that may become a catalog entry.
- **Multiple bug patterns in interaction.** E.g., the v2.1 round-1 Theme A/B/C across 3 drivers — interaction effects need cross-pattern reasoning.
- **3rd+ consecutive iteration on the same diff.** Subtle drift between rounds is what Opus catches.
- **Substantial parent-driver routing or polling logic changes.** Affects every device family; missed regression hits all users.

### Cost-saving levers (input-side, where 97% of tokens live)

The QA agent's self-analysis after the v2.1 review cycles attributed token cost as approximately:
- ~48% accumulated transcript carryover from prior rounds (stale content from earlier reviews)
- ~29% additional carryover from rounds further back
- ~15% static priming (agent definition + tool schemas + auto-injected CLAUDE.md; CONTRIBUTING.md is lazy-loaded on-demand)
- ~3% output verdict
- ~5% genuinely fresh content for the current review

Output is only ~3% of total. **Do not reduce verdict verbosity** — output fidelity is the dev agent's signal for what to fix; cutting it sacrifices clarity for negligible cost savings. The leverage is on the input side: model default (Sonnet, applies multiplicatively to all input + output), cache hygiene (the resume-vs-fresh discipline above), and tighter briefs (don't ask for "verify all 13 driver names" if a representative sampling produces the same correctness signal).

---

## Two deployment contexts

After QA approval, the operations layer verifies on a real Hubitat hub. Three sub-modes — pick based on whether MCP is available AND whether real device hardware is owned for the driver under test.

### A1. With MCP + real hardware (canonical path for shipped-hardware drivers)

Dispatch the **vesync-driver-operations** agent. It handles the deploy + verify cycle on real hardware (real `VeSync Integration` parent → real VeSync cloud → real device) and returns a structured PASS/FAIL/UNCERTAIN report. Pass it the source file path, driver ID (look up via `mcp__hubitat__manage_apps_drivers list_hub_drivers`), affected device IDs, and a test plan.

You can also do the deploy yourself if the change is trivial:
1. Upload source: `curl -F "uploadFile=@<file>" -F "folder=/" "http://<hub-IP>/hub/fileManager/upload"`
2. Deploy: `update_driver_code` MCP tool with `driverId`, `sourceFile`, `confirm: true`
3. Verify: trigger `refresh` on the affected device, inspect `applyStatus raw r ...` log lines, confirm attributes populated.

Use the operations agent when: you want structured PASS/FAIL evidence in the work item, the test plan is non-trivial, or you want to keep the main session's context lean (operations is Haiku, much cheaper than Opus on log-heavy verification).

This is the only mode that exercises the real cloud round-trip — required for catching API-shape regressions (BP4 V201S field-name verification, BP13 token-expiry, response envelope shapes the fixture doesn't capture).

### A2. With MCP + virtual test parent (preview drivers without hardware)

For drivers shipped as preview without maintainer hardware (the default since v2.1 — Tower/Pedestal Fans, LV600S, Dual 200S, Classic 200S, OasisMist 1000S, Sprout family, EverestAir, etc.), real-hardware verification isn't available. The **virtual test parent** (`Drivers/Levoit/VeSyncIntegrationVirtual.groovy`, ships `required: false` in HPM) replaces the real parent with a fixture-driven harness that serves canned pyvesync responses to children.

Dispatch flow:

1. Deploy the virtual parent + new child driver via MCP `update_driver_code`.
2. Configure the virtual parent's preferences to spawn the relevant fixture's child (UI step, or direct settings update via MCP).
3. Send commands to the spawned child via `mcp__hubitat__send_command` — virtual parent intercepts, validates payload data keys against `FIXTURE_OPS`, returns a canned response asynchronously.
4. Read attributes (`mcp__hubitat__get_attribute`) and logs (`mcp__hubitat__manage_logs`) to verify the child's parser populated state correctly.
5. Operations agent returns PASS/FAIL/UNCERTAIN with `[DEV TOOL]` log markers as evidence.

What this catches that A1 doesn't (because no hardware to test on): Hubitat sandbox runtime quirks, async callback ordering, real `addChildDevice` lifecycle, `schedule()`/`runIn()` cron mechanics — the BP14/BP16/BP17 pattern fingerprint. What it does NOT catch (no real cloud): BP4 field-name regressions vs live API, BP13 token expiry, response envelope shapes pyvesync didn't capture.

The virtual parent coexists safely with the real `VeSync Integration` parent on the same hub. Virtual children use the `VirtualVeSync-` DNI prefix and never touch the VeSync cloud, so there is no cross-wiring risk. If both parents are installed, a WARN is logged at spawn time (`[DEV TOOL] Real 'VeSync Integration' parent app detected`) — this is informational only and does not block spawn.

For preview drivers, A2 is the standard pre-ship gate. If a driver is later acquired and tested via A1, that supersedes A2 for that specific driver.

### B. Without Hubitat MCP (typical contributor)

The pipeline still works — you just stop at the local-file-edit step. Push your changes and let the maintainer (or yourself, manually) deploy:
1. Run developer + QA pipeline as above.
2. Once QA approves, commit changes.
3. To test locally:
   - In Hubitat UI: **Drivers Code** → find the driver → **Edit** → paste new content → **Save**.
   - Open the affected child device's page → click **Refresh**.
   - Check **Logs** for `applyStatus raw r ...` lines.
   - For drivers without hardware, install the virtual test parent (HPM Modify → opt in to "VeSync Virtual Test Parent") and run a fixture-driven verification on your hub before opening the PR.
4. Open a PR with the diff + test plan + manual verification notes (call out which sub-mode you used: real hardware / virtual parent / Spock-only).

You don't have to deploy to merge — code review + spec-conformance via the dev/QA pipeline is sufficient gate. The maintainer will deploy on the live hub before/during merge.

---

## Logging conventions

Three preferences gate logging in every driver:

| Pref | Default | Purpose |
|---|---|---|
| `descriptionTextEnable` | `true` | INFO-level user-meaningful events (power on/off, mode change, threshold alerts) |
| `debugOutput` | `false` | DEBUG internal trace + `applyStatus raw r ...` diagnostic dump |
| `verboseDebug` (parent only) | `false` | Full API response body dump on every API call |

Helper pattern (every driver):
```groovy
private logInfo(msg)  { if (settings?.descriptionTextEnable) log.info  msg }
private logDebug(msg) { if (settings?.debugOutput)            log.debug msg }
private logError(msg) { log.error msg }
```

The **parent driver's** `logInfo`, `logDebug`, `logError` route through a `sanitize()` helper that auto-redacts `email`, `state.accountID`, `state.token`, and `settings.password`. **Always preserve this** when modifying parent — direct `log.X` calls bypass sanitize.

Auto-disable: every driver's `updated()` includes `if (settings?.debugOutput) runIn(1800, "logDebugOff")` to flip debug off after 30 minutes. (Use string-literal handler form for `runIn` — bare-identifier form depends on Hubitat sandbox binding magic that doesn't replicate in the Spock harness's test classloader.)

INFO logs go at state-change points only (use `state.lastFoo` comparison gates) — not on every poll cycle.

### Pref-seed pattern (Bug Pattern #12)

See CONTRIBUTING.md "Pref-seed at first poll method" row in the "Conventions enforced by lint/tests" table for the full pattern and rationale. Insertion points by driver shape (the dev agent uses this table directly):

| Driver shape | Insertion point |
|---|---|
| V2-API (Vital, Superior) | top of `applyStatus()` |
| Core 200S/300S/400S/600S | top of `update(status, nightLight)` |
| Core 200S Light | top of `update(status)` (1-arg only) |
| Notification Tile (event-driven) | top of `deviceNotification(notification)` |
| Parent (VeSyncIntegration) | top of `updateDevices()` (before `driverReloading` guard) |

Implementation pattern (preserves user choice via null guard, bounded to one write per device lifecycle via `state.prefsSeeded`):

```groovy
if (!state.prefsSeeded) {
    if (settings?.descriptionTextEnable == null) {
        device.updateSetting("descriptionTextEnable", [type:"bool", value:true])
    }
    state.prefsSeeded = true
}
```

---

## Bug-pattern catalog

The QA agent's definition contains a numbered catalog of bug patterns from the v2.0 community-fork debugging. Reference them by number when flagging issues:

1. Missing 2-arg `update(status, nightLight)` signature on a child — exception: `LevoitCore200S Light.groovy` intentionally uses only 1-arg (parent calls it explicitly, not through generic poll path); lint config exempts it
2. Hardcoded `getPurifierStatus` for all device types in parent
3. Response envelope peel missing or wrong depth
4. V201S `setLevel` payload field-name mismatch
5. V201S manual-mode set via wrong method
6. Speed reports last setting while switch is "off"
7. Info HTML race with `device.currentValue`
8. Drying state mapped as boolean
9. Driver name change breaks device association
10. SmartThings icon URL leftovers in metadata
11. `documentationLink` pointing to unrelated project
12. Type-change leaves new pref defaults uncommitted (silent INFO suppression)
13. Token-expiry silent failure (no re-auth on HTTP 401 or inner auth codes)
14. Hub-reboot drops `runIn`-based poll cycle — `runIn()` is in-memory only; use `schedule()` cron for periodic work (persists across reboots). See `vesync-driver-qa.md` BP14 entry for canonical `setupPollSchedule()` + `ensurePollWatchdog()` design and live-verification footer.
15. Driver code uses app-only API (`subscribe`/`unsubscribe` to location events) — drivers cannot subscribe to location events; use `schedule()` for periodic work, parent→child calls for cross-device. HubitatSpec mock must fail-fast (not no-op). See `vesync-driver-qa.md` BP15 entry for full root-cause + fix.
16. `debugOutput` stuck `true` indefinitely after hub reboot — `runIn(1800, "logDebugOff")` in `updated()` is in-memory only and evaporates across reboots; `settings.debugOutput` persists. Fix: `updated()` records `state.debugEnabledAt = now()` when debug enabled (clears it when disabled); `ensureDebugWatchdog()` at top of every poll/command entry auto-disables when elapsed > 30 min. Same architectural shape as BP14. See `vesync-driver-qa.md` BP16 entry for full design.
17. Stale `state.deviceList` configModule causes silent empty-result polls — see `vesync-driver-qa.md` BP17 entry for full root-cause + fix (consecutiveEmpty counter + `ensurePollHealth()` watchdog + auto-Resync trigger).
18. NullPointerException on `(arg as String).toLowerCase()` for null command parameter — Rule Machine passes null when a parameter slot is left blank; null-coerced-to-String throws NPE that Hubitat sandbox swallows. Fix: null-guard at method entry. See `vesync-driver-qa.md` BP18.
19. Self-heal logic refreshes intermediate state but not the load-bearing data value — BP17's Resync correctly refreshed `state.deviceList` but not the children's `configModule` data values that `sendBypassRequest` reads at API call time, so the self-heal logged success but didn't actually fix the polling. Fix: refresh ALL load-bearing call-site sources, not just intermediate state. See `vesync-driver-qa.md` BP19 (the v2.4 fix folded the missing 21 else-branches).
20. Library file file-scope commentary triggers Hubitat parser "Internal error" — the trigger is fuzzier than originally cataloged. Original finding (2026-04-30 morning): `/* */` block comments at file scope before `library(` fail save. Update (2026-04-30 afternoon, during UX refactor): also fails on `//` line-comment blocks containing literal text `/* */` or `library(` as documentation, on expanded section dividers, and at certain comment-density thresholds. RULE29 still catches the obvious `/* */` form; the broader trigger pattern is enforced via convention + the ops agent's library-deploy smoke test (POST to `/library/saveOrUpdateJson` and verify `success:true`). Rule for library files specifically: keep file-scope content between MIT header and `library(` declaration to ZERO commentary; put explanations in CONTRIBUTING.md / BP catalog / agent specs. javadoc `/* */` blocks INSIDE method bodies are unaffected. Driver files (using `definition()`) are not affected. See `vesync-driver-qa.md` BP20 entry for canonical fix + the broader convention; canonical example `Drivers/Levoit/LevoitDiagnosticsLib.groovy`.

When the developer or QA recognizes one of these patterns in a diff, name it explicitly: *"Bug Pattern #1 — missing 2-arg signature."* The other agent recognizes the name and applies the canonical fix.

---

## Common contributor tasks → which agent

| Task | First step |
|---|---|
| User reports "device discovered but no data" | Dispatch QA on the affected driver to identify which bug pattern applies. Often #1 or #3. |
| VeSync changed a field name | Capture verbose API response → dispatch developer with the new field map. |
| Add support for new Vital/Superior/Core variant | See CONTRIBUTING.md "Adding a new device driver" for the 11-step flow; dispatch `vesync-driver-developer` for steps 4-5 (driver code). |
| Logging spam in user reports | Dispatch developer to gate noisy logs behind `descriptionTextEnable`/`debugOutput` per conventions. |
| HPM users report "driver not found" | Verify `levoitManifest.json` has the entry with correct `location` URL pointing to the fork's `main` branch. |
| Migrate existing-deployed driver to new name | DON'T (Bug Pattern #9). Add the new name as a separate driver file and document migration in readme. |

---

## Source references in this codebase

Load-bearing patterns (don't break in refactors). Each is canonicalized in the relevant agent def — see those for full pattern + code.

- **Diagnostic raw-response line** in every child's `applyStatus` (debug-gated `log.debug "applyStatus raw r ..."`).
- **VeSync API trace logging** wrapper in parent's `sendBypassRequest` (1-line summary at debug; full body at verboseDebug).
- **`sanitize()` helper** in parent — direct `log.X` bypasses it; always route through `logInfo`/`logDebug`/`logError`.
- **Envelope-peel while loop** in V2-line children's `applyStatus` (peels up to 4 layers of `{code, result, traceId}`).
- **3-signature update pattern** in every child: `update()`, `update(status)`, `update(status, nightLight)` — last one REQUIRED (BP1).

---

## GitHub workflow (fork conventions)

See `CONTRIBUTING.md` "Fork remotes (`gh` CLI gotcha)" — this fork has two remotes (`origin` = fork, `upstream` = Niklas's original); `gh` defaults to upstream and fails. Run `gh repo set-default level99/Hubitat-VeSync` once per clone, otherwise prefix every `gh` command with `--repo level99/Hubitat-VeSync`.

---

## Cost optimization notes

The 4-agent pipeline is shaped around model-cost asymmetry: dev/QA Sonnet (~5× cheaper than Opus per token), tester/ops Haiku (~19× cheaper than Opus). Resume via `SendMessage` on warm cache preserves ~60-70% of input tokens (driver source + prior findings stay cached). Tester/ops contain raw output (100KB Gradle / 60KB logs) into ~1KB structured summaries — main + dev + QA contexts stay lean. Cumulative effect: iterative driver work runs at **~30-40% of all-Opus-main-session cost**. See "QA dispatch: model selection" above for QA cost-attribution detail (48/29/15/3/5).

---

## When this file is wrong

If reality and this file diverge (driver structure changes, new pattern emerges, bug-catalog grows), update this file in the same PR. The agents read CLAUDE.md as part of their context priming; outdated CLAUDE.md misleads future work.
