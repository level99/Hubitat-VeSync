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
| `docs/oauth-flow.md` | Internal-debugging reference for the v2.7+ two-stage OAuth2 login flow (pyvesync parity). Constants/state reference, fresh-install walkthrough, cross-region handling, symptom-to-root-cause troubleshooting table, diagnostic recipes for verboseDebug dumps, HTTP-layer notes (Apache HttpClient chunked + Expect:100-continue workaround). Developer/maintainer-facing only. | Trigger phrases: *"OAuth"*, *"OAuth2"*, *"two-stage login"*, *"auth flow"*, *"authentication flow"*, *"login flow"*, *"VeSync login"*, *"VeSync auth"*, *"terminalId"*, *"authorizeCode"*, *"bizToken"*, *"BP27"*, *"appVersion is too low"*, *"new device login email"*, *"app version is too low"*, *"-11012022"*, *"-11102086"*, *"-11260022"*, *"-11001000"*, *"-11201000"*, *"Stage 1"* / *"Stage 2"*, *"Apache HttpClient"* / *"chunked encoding"* / *"Expect: 100-continue"*, *"cross-region"* (login). |
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

## Two-tier QA: pipeline agent vs `/final-review` skill

This codebase ships TWO QA entry points, with deliberate cost-quality tiering:

| Tier | Tool | When to use | Cost | Catch rate |
|---|---|---|---|---|
| **Iteration** | `Agent({vesync-driver-qa})` | Inside dev↔qa↔tester↔ops pipeline loops, many times per PR during active development | ~$1-2 per round (Sonnet, SendMessage-resumed) | ~60-70% per round; cache + context accumulates across rounds |
| **Ship gate** | `/final-review` skill | ONCE before opening a PR or flipping draft → ready-for-review | ~$8-10 per run (Haiku pre-flight + 6 specialized Claude sub-agents in parallel fan-out + two different-family external reviewers — OpenAI Codex CLI + Google Gemini CLI) + 1 ChatGPT Plus message + 1 Gemini paid-API run (pennies; the free tier's input-TPM cap walls a real review) | ~100% per run against this fork's review style |

Typical PR cycle:
1. Dev iterates: `vesync-driver-developer` → `vesync-driver-qa` (Sonnet, cheap iteration) → `vesync-driver-tester` → `vesync-driver-operations`. Loop 3-5×.
2. Before opening PR or flipping ready: run `/final-review` once. Address findings.
3. Open / mark ready. Maintainer reviews.

Don't invoke `/final-review` mid-iteration — it's the ship gate, not the iteration partner. Don't try to push the pipeline agent to skill-level rigor — that's what the skill exists for. The tiering is the whole point.

Reviewers that the skill fans out to: the 6 Claude sub-agents defined under `.claude/agents/vesync-driver-qa-*.md` — `coverage` (Opus, `effort: xhigh`), `platform` (Sonnet), `protocol` (Sonnet), `adversarial` (Opus, `effort: max`), `design` (Sonnet, `effort: max` — the synthesis lens), `operator` (Sonnet) — plus `preflight` (Haiku) as the sequential gate, plus **two different-family external reviewers** orchestrated by the skill as parallel `Bash` calls (NOT Claude sub-agents): the **OpenAI Codex CLI** and the **Google Gemini CLI**. Both consume one shared prompt file and review the FULL change independently — their highest-value catches are independent findings, not gap-fills; the blind-spot benefit compounds across the three families. Each external reviewer is independently optional: if its CLI is absent/unauthenticated/usage-blocked, the skill skips just that one and runs with the rest — degradation is graceful and per-reviewer, not a hard requirement.

| Sub-agent | Scope |
|---|---|
| `vesync-driver-qa-preflight` | Lint --strict, Spock compile, manifest sanity, BP catalog grep, convention scan, path leakage, version + FORK_RELEASE_VERSION lockstep, diff triage / dispatch plan |
| `vesync-driver-qa-coverage` | Spock test coverage (BP-pattern regression tests, family-symmetric coverage, lib-spec coverage), lint rule coverage (new BP class → new rule), documentation coverage (`Drivers/Levoit/readme.md`, CHANGELOG `[Unreleased]`, BP catalog entry), fixture coverage |
| `vesync-driver-qa-platform` | BP14 (reboot survival), BP15 (driver vs app API), BP16 (debug watchdog), BP18 (null-guard), string-literal `runIn` handler, capability/attribute/command coherence, RULE30 logger discipline, 3-signature `update()` per BP1 |
| `vesync-driver-qa-protocol` | bypassV2 envelope shape, BP4 field-name verification (snake_case vs camelCase per family), BP3 envelope peel depth, BP13 token-expiry re-auth, configModule routing, pyvesync canonical cross-reference |
| `vesync-driver-qa-adversarial` | Input adversaries (null/empty/unicode/MAX_INT), state adversaries (guard-bypass), concurrency adversaries (async race, re-entrance), environment adversaries (BP14/16/17/19/21/22), Rule Machine adversaries (BP18 blank slots, C3 idempotency, BP23/BP24 from-off) |
| `vesync-driver-qa-design` | Lib boundary integrity (Phase 1-5 architecture), cross-line consistency (Core/Vital/Classic/V2/Fan family), helper-extraction opportunities, intentional-asymmetry rationale, BP24 SHOULD-ON/NO-ON/SKIP-OK classification |
| `vesync-driver-qa-operator` | BREAKING flag honesty (what breaks vs what's preserved), TMI filter (no impl-detail in user-facing prose), CHANGELOG `[Unreleased]` per-commit discipline, dashboard/RM impact disclosure, log discipline + PII sanitize routing, `Drivers/Levoit/readme.md` device-row updates, cut-release invariant trips |
| Codex CLI (OpenAI) | FULL independent second-opinion review (reads the git diff in its read-only sandbox). Highest-value catches are independent findings — cross-variant correctness, doc-vs-code drift, sibling-pattern incompleteness, vacuous guards, stale narration, HPM-bundle integrity. Orchestrated as a parallel `Bash` call (NOT a Claude sub-agent); consumes the shared prompt file authored in SKILL.md Step 4b/4c. |
| Gemini CLI (Google) | FULL independent second-opinion review from a THIRD model family — blind-spot benefit compounds with Codex (each catches BLOCKINGs the other two families miss). Runs `--yolo` inside the disposable worktree so it executes `git diff` itself (contained blast radius); needs `rg` on PATH + the **paid Gemini API tier** (free tier's ~250K input-TPM cap walls a large-context review). Consumes the SAME shared prompt file. |

The skill's pre-flight Haiku gate runs FIRST as a sequential check; if it FAILs (lint broken, manifest malformed, version lockstep broken, etc.), the skill stops without dispatching the expensive deep-audit agents OR the external reviewers — broken-PR cost ~$0.005 instead of $5-8.

**Operator agent rationale**: v2.5 specifically had three rounds of "the BREAKING wording doesn't say what actually breaks" (display→displayOn, setTimer description, Sup6000S abbreviation leak). The other sub-agents miss this class because their lens is correctness, not communication. Dedicated set of eyes on user-facing meaningfulness pays for itself.

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

### Cross-cutting audit when fixing a named bug pattern

When fixing a bug tagged with a Bug Pattern catalog entry (BP1-BPN), the dev agent's diff MUST cover all entry points to the same semantic class — not just the reported one. Before writing code, dev produces an explicit **fix-scope statement**:

- Enumerate every method/site that matches the pattern's shape across affected drivers (e.g., for BP23/BP24-class auto-on-from-off: every `def setX` / `def cycleX` / capability-required command on every SwitchLevel/FanControl driver in scope).
- Identify which sites fall in the diff vs which are explicitly out-of-scope.
- Waive any not-fixed-in-scope site with rationale (e.g., *"cycleSpeed entry point on Tower Fan defers to its own audit because the bypass-shape differs from the dead-state shape; tracked as BP24-B sweep"*).

QA then verifies the fix-scope claim covers the real surface (Layer 3 of the BP-prevention defenses).

Why: BP23's v2.4.1 fix patched only `setLevel(val)`. cycleSpeed had the same auto-on-from-off bug shape on 6 of 8 drivers, never got the fix, and shipped to v2.4.1 unfixed. Round 1.5 of v2.5 caught the gap one release later (33 broken call sites across 18 drivers under the renamed BP24 umbrella). The fix-scope discipline catches that gap in the same review cycle, instead of needing a separate v2.X+1 sweep release.

Lives alongside "Batch fix-rounds, audit-first when triaging" above. That rule says *"audit-first when surfacing related bugs."* This rule says *"audit-first when applying a named-BP fix."* Together they cover both directions of the cross-cutting concern.

**BP catalog entries reinforce this** with an explicit `Fix scope:` line per entry — `per-instance` (single method) or `class-wide` (every entry point to the semantic class). New entries (BP24+) include this always; existing entries (BP1-23) get backfilled when next touched.

**Mechanical enforcement** (Layer 5 of the defense stack) closes any gap that judgment misses: lint rules grep for the pattern's signature on every `tests/lint.py --strict` run; new-driver Spock spec template requires a from-off regression test for every "MUST-ON" / "SHOULD-ON" classified command method.

### Pre-flight before any tester dispatch

Before dispatching tester (or sending SendMessage to resume tester), confirm:

(a) QA has APPROVE'd the EXACT current diff state, OR
(b) The tester run is a standalone sanity check on already-QA'd / already-committed code (e.g., pre-PR baseline run after a rebase)

If neither holds — STOP. Dispatch QA first.

Drift pattern observed in v2.2.1: dev returned with fix → orchestrator went directly to tester to save round-trip → user caught the missing QA step → had to backfill QA. The QA step is cheap (Sonnet, small diff); skipping creates rework when QA finds something tester wouldn't catch (logging discipline, design quality, cross-pattern interactions, PII routing).

### Verify before citing an in-codebase pattern as canonical

Before citing an existing in-codebase implementation as the "canonical pattern" in a dispatch prompt or a QA finding — e.g., *"follow the LevoitHumidifierLib pattern"* or *"match how Vital 200S does it"* — confirm the pattern has a lint rule or BP-catalog entry endorsing it.

Why this matters: the v2.6 QA audit found that the original BP25 dispatch prompt cited `LevoitHumidifierLib.groovy`'s `doSetDisplayScreenSwitch` as the correct pattern, which is correct. But v2.5 had simultaneously introduced several NEW drivers (EverestAir, Sprout Air) with nearly-correct-but-not-quite implementations — `(v == "on") ? 1 : 0` without the truthy-variant check, `.toLowerCase()` without `.trim()`. Because those newer drivers also used the phrase "BP25 fix pattern" in their comments, they read as authoritative. The correct reference was `LevoitHumidifierLib`; the misleading references were the newly-landed drivers. The distinction only became clear after cross-referencing with the lint rule (RULE33) and the BP25 catalog entry.

**Practical rule:** when building a dispatch prompt that says "match pattern X in file Y," verify Y's pattern against the lint rule or catalog entry that governs it. If no lint rule or catalog entry exists for the pattern, say so explicitly in the dispatch prompt ("this is an unblessed pattern; verify before adopting") rather than silently propagating a potentially wrong convention.

### Mechanical proof for "migrate all X" tasks

**Completeness of any "migrate every site" or "sweep all drivers" task MUST be proven by a mechanical zero-result check, not by agent self-report.** When a task says "migrate every rule to the shared helper," "add a null-guard to every `set*` method," or "sweep all `as Integer` sites," the completion claim must be backed by a grep or static check that produces an empty result — supplied verbatim in the diff summary.

Two real instances where self-report failed and the mechanical check would have caught the miss in the same review cycle:

1. **BP26 v2.6 initial sweep** — reported all `safeIntArg` sites migrated; a third site in Tower Fan (bare `seconds as Integer`) was missed and shipped. Caught only because RULE37 was later extended to flag bare `as Integer` forms. The fix-scope proof artifact (`grep -rn 'as Integer' Drivers/`) would have been non-empty and surfaced the miss.

2. **v2.6 lint-helper migration** — reported all rules using `make_finding`; six rules still emitted raw inline dicts (`findings.append({...})`). Missed because the migration relied on agent recall. The proof artifact (`grep -rn 'findings.append({' tests/lint_rules/`) would have returned 6 hits, blocking the "complete" claim.

The standard grep-to-zero artifact closes both gaps. Every "sweep all" dispatch MUST include the grep command and its empty output in the returned diff summary. If the grep returns hits, those are either in-scope misses (fix them before closing the task) or explicitly waived out-of-scope sites (document the rationale per-site, same as the named-bug-pattern fix-scope protocol).

### Empirical both-ways proof for regression guards and fix PoCs

**Any fix whose value is "fails when the bug regresses" (a regression guard / regression test), and any behavior fix that has a proof-of-concept, MUST be empirically proven both-ways before acceptance** — the guard observed FAILING when the fix is reverted or the bug reintroduced, AND observed PASSING on the correct tree. Theoretical QA reasoning alone is NOT sufficient.

**Why:** T11-1's `main()`-guard test for the dead-gate choke-point was QA-reviewed as sound on two separate passes. Sweep-#6 adversarial empirically proved it passed identically whether the choke-point call was present or deleted — incidental `LINT_UNUSED_EXEMPTION` WARN findings in strict mode satisfied its weak `code != 0` discriminator, masking the choke-point's absence entirely. Theoretical review cannot detect this class of vacuity; only running the test both ways can.

**How to apply:**

1. Write the fix + the regression guard (test or assertion).
2. Run the guard on the correct tree — it MUST pass. Record the verbatim pytest / Spock output line.
3. Revert / delete / replace the fix with a minimal no-op mutation (e.g., replace a call with `pass` / delete the assertion (Python lint-rule tests), or comment out / replace with `return` (Groovy Spock specs)). Run the guard again — it MUST fail. Record the verbatim failure block.
4. Restore the fix fully. Run `git diff --stat <fixed-file>` to confirm clean revert.
5. Report all four observations verbatim in the diff summary. If step 3 does NOT produce a failure, the guard is still vacuous — iterate until it does.

This applies to: lint choke-point call-site guards, Spock regression tests for named bug patterns, PoC mutation proofs in tier-review tasks, and any assertion whose stated purpose is "this test fails if the fix is removed."

**Ownership & division of labor — the root-cause fix for "why so many rounds".** The both-ways proof is an **orchestrator-driven protocol, not a delegated capability.** Three roles, no overlap:

- **Orchestrator (you, Opus):** OWN the mutation design and the discriminating pass-criterion. You decide the exact revert (`git stash push -- fileX`, or "replace line N with `pass`") AND the exact signal that proves the guard real ("proves out *only* if `TestFoo::test_bar` fails with an `AssertionError` naming `<X>` — NOT merely if exit≠0"). You apply the revert, run each leg (dispatch the Haiku tester for a full-suite leg; run directly for a single targeted test — whichever is cheaper), judge whether the failure is the *right* failure, restore, and confirm clean (`git diff --stat`). This judgment is Opus-tier and **never delegated** — it is exactly the work that was being done ad-hoc each round; making it an explicit owned protocol is what removes the rounds.
- **Tester (Haiku):** runs the harness on whatever tree state it is handed and reports **strictly verbatim**. It does NOT design the mutation, does NOT judge "should this be failing", does NOT reconcile two runs. A dirty working tree it sees during a both-ways sequence is the orchestrator's intentional mutation — it reports what the harness says on the tree as-given. If a dispatch asks it to *invent* the mutation or pass-criterion, it returns UNCERTAIN and asks — never improvises (a safe failure, not a fabricated "both-ways PASSED").
- **QA (Sonnet/Opus):** vacuity-by-construction is **NOT QA's burden** — static reading cannot detect it (T11-1 was QA-APPROVED "sound" on two passes and was vacuous). QA reviews scope, correctness, wording, cross-pattern interaction; it explicitly **defers the vacuity verdict to the orchestrator-driven both-ways** and must not certify "this guard is non-vacuous" by reasoning.

**A single-direction run is never proof.** A guard observed only PASSING on the correct tree proves nothing — a hollow guard and a real guard are indistinguishable in that direction. Accepting a single-direction tester run as "the guard works" is the exact defect this protocol exists to kill.

### Closed mechanism over reactive instance-patching

**When you find one instance of a problem that belongs to an enumerable class, do not patch the instance and widen a hand-grep. Build a closed, tested mechanism that catches the whole class — in the same diff.**

**Why:** Tier 22 "generalized" the comment-scrub grep and it *still* missed the no-hyphen `T11` and no-hash `Issue N` forms — a hand-grep only enumerates the shapes its author thought of, and the class is open-ended. Tier 23 converted that grep into RULE38 (a lint rule with an authoritative form-set + must-catch / must-not-catch pytest fixtures + a grep-to-zero proof) and it immediately found ~6 more real instances the hand-grep missed. The patch-and-widen loop costs one sweep round per missed shape, indefinitely; the closed mechanism costs one conversion, once.

**How to apply** — any "scrub all X" / "sweep every Y" / "guard every site Z" task:
1. Define the class by an authoritative predicate, not an example list.
2. Encode it as a mechanical check (lint rule / verifier / gating test) with **must-catch AND must-not-catch fixtures**.
3. Prove completeness with a grep-to-zero (or equivalent) artifact, supplied verbatim.
4. The instance fix and the mechanism land in the **same** diff. For an enumerable-class problem, a fix without the mechanism is incomplete by definition.

This is the durable generalization of "Mechanical proof for migrate-all-X tasks" and the Tier-23 thesis: a hand-search with a blind spot becomes a lint rule with its own tests — never re-patched in place. Both the `vesync-driver-developer` and `vesync-driver-qa-design` agent defs carry this rule so it applies even on a wiped-context dispatch.

### Sweep-orchestration: coverage audits committed HEAD, not the working tree

**When a full QA sweep fans out adversarial and coverage sub-agents in parallel, coverage MUST audit committed HEAD (`git show HEAD:<file>` / `git diff <base>..HEAD`), NOT the live working tree.**

**Why:** the adversarial sub-agent's empirical revert-tests mutate source files, run lint/tests against the mutated state, then restore. When coverage runs concurrently and inspects `git status` or the live working tree, it observes the transient mutation as an apparent code regression — a phantom BLOCKING finding that requires a full analysis cycle to diagnose as a false positive.

**Convention:** a dirty working tree during a parallel sweep is the expected signature of a sibling agent's in-flight empirical test, not a code regression. Inspecting the committed state (`git show`, `git diff base..HEAD`) is immune to this class of false positive because committed HEAD is not affected by in-flight working-tree mutations.

**Canonical incident:** Sweep #8 adversarial and coverage ran in parallel. Adversarial reverted `LevoitCorePurifierLib.groovy`'s `setAutoMode` nest to `safeIntArg(roomSize, (state.room_size ?: 800) as Integer)` for its empirical test. Coverage observed the dirty tree, flagged the regression as BLOCKING, and spent a full analysis cycle determining it was adversarial's in-progress test rather than a real regression. Cost: one extra pipeline round. Fix: coverage reads `git show HEAD:Drivers/Levoit/LevoitCorePurifierLib.groovy` rather than the live file.

**How to apply:** in sweep orchestration briefs, explicitly instruct the coverage sub-agent: "Audit committed HEAD only. Use `git show HEAD:<path>` to read driver files and `git diff <base>..HEAD` to read the diff. Do not read the live working tree — a sibling adversarial agent may have mutated it for empirical testing."

See `CONTRIBUTING.md` "Sweep-orchestration: coverage audits committed HEAD" for the one-line companion pointer.

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

### HPM library-duplicate trap

`install_library` on a hub that already has the same name+namespace library (typically from an HPM bundle install) creates a SECOND copy at a different ID. Both coexist; Hubitat `#include` binds drivers to ONE — usually the HPM-installed (lower-ID) one. `update_library_code` on the other copy then reports `success:true` but dependents still throw `MissingMethodException` for the new methods. Hub reboot does NOT fix it; this is parse-time binding, not cache.

**Pre-`install_library` check:** enumerate existing libraries by name first (scan `/library/ajax/code?id=N` IDs 1–200 via curl, ~10s). If a library with the same name+namespace exists, use `update_library_code` against that ID instead of installing a new one. If duplicates already exist, update ALL of them.

**Post-deploy validation (cheap):** spawn a fresh dependent-driver child and exercise a new-method code path. `update_library_code` returning `success:true` is necessary but not sufficient — only a runtime method call proves the binding propagated.

---

## Developer dispatch: model selection

The `vesync-driver-developer` agent uses **Opus**, always. No per-dispatch override. v2.6 surfaced enough Sonnet-introduced bugs that the per-token savings were eaten by extra QA-iteration rounds downstream, but the load-bearing reason for "always Opus" is that mixing models breaks the `SendMessage` resume cache (cached agent state is model-tied; switching models forces a fresh dispatch with full re-briefing context, losing the 60-70% input-token savings the pipeline is designed around).

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

## Operations dispatch: model selection

The `vesync-driver-operations` agent defaults to **Haiku** because log-dominated work (100KB log fetches → 1KB structured PASS/FAIL summaries) is exactly Haiku's sweet spot. But Round 3+4 of v2.5 surfaced a fragility: when the dispatch involves multi-endpoint diagnostic reasoning, error-classification, or hypothesis-testing, Haiku tends to lock onto its first wrong hypothesis and push forward instead of falling back. Sonnet has noticeably better recovery in that mode. Cost-wise Sonnet is ~5× more expensive per token than Haiku, so reserve elevation for cases where the reasoning shape genuinely matters.

### Default: Haiku

Use Haiku for the typical case (no `model:` override needed; agent definition's `model: haiku` frontmatter handles it):

- Deploy a known-shape driver via `update_driver_code` (driver already exists on hub, just swapping source)
- Exercise N pre-specified commands via `send_command` and verify attribute round-trip
- Scan logs for canonical marker phrases listed in the dispatch (matches the bug-pattern catalog directly)
- Structured PASS/FAIL on a single hub interaction with a clear test plan
- A2 virtual-parent spawn-and-test cycles (fixture name + child label given; `[DEV TOOL]` markers checked)
- Pure RPC: backup creation, file uploads, `list_devices` queries

### Elevate to Sonnet

Pass `model: "sonnet"` in the `Agent({...})` dispatch (or `model: "sonnet"` override on `SendMessage` resume — whatever the runtime supports) for:

- **Hub-state investigation across multiple endpoints.** Round 4's "is the lib actually saved on the hub" took 5+ endpoint probes + DOM inspection — Haiku locked onto "library missing from hub entirely" after one failed probe. Sonnet would have triangulated.
- **Error-classification with discrimination tables.** The BP20 vs JSON-encoding-bug distinction (`"Internal error"` vs `"Malformed library definition"`) is documented in the ops agent's own definition, but Haiku read it and still misclassified twice. The discrimination requires holding two near-identical error messages and applying conditional logic per the response — Sonnet handles that; Haiku doesn't reliably.
- **Library save / install via `/library/saveOrUpdateJson`.** Multi-step diagnosis if it fails (first save, parse response, classify error, recover). Always Sonnet for these — the failure modes need real reasoning.
- **First-time integration verification.** Round 4's first triple-include compile, Round 3's first lib smoke test — anything where the hub state is novel and the dispatch can't enumerate every possible failure shape.
- **Conditional logic in the dispatch brief.** Any time the brief says "if X happened, do Y; if Z, do W" instead of "do these N things in sequence." Sonnet picks branches; Haiku takes the first branch and keeps going even when symptoms diverge.
- **Anything where the agent needs to second-guess its own first hypothesis.** This is the meta-property: Haiku's failure mode in this codebase is hypothesis-lock-in. If the work involves baseline-checking against existing files (BP20 discrimination rule), comparing observed state to expected, or recovering from a failed first attempt, Sonnet is worth the cost.

### Trip-wire signal

If Haiku flags UNCERTAIN twice in one session for non-environmental reasons (its own reasoning, not VeSync API flakes or transient hub state), elevate to Sonnet for the next round in that session. The trip-wire prevents grinding multiple Haiku rounds on a problem the model can't solve.

### Alternative: orchestrator-direct

For investigative / exploratory hub work where even Sonnet ops would be coaching-heavy, the orchestrator (main session) can do hub investigation directly — `curl` against documented endpoints, MCP tool calls, browser MCP for SPA-data inspection. Direct work bypasses agent dispatch overhead and is often cheaper than the equivalent Sonnet round when the work is genuinely exploratory rather than mechanical. Reserve agent dispatch for repeatable verification patterns (deploy, exercise, scan, report); use orchestrator-direct for "what's actually on this hub right now" probes.

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
21. BP17 self-heal loops on chronically-offline devices — `ensurePollHealth()` triggers `getDevices()` configModule refresh on 5 consecutive empty polls; the refresh succeeds, polling resumes, but if the underlying device is genuinely offline (unplugged / off WiFi / off VeSync cloud), polls remain empty → BP17 fires again 5 polls later → infinite ~5min self-heal cycle plus per-poll ERROR spam. Fix: bound the self-heal counter (max 3 attempts per device); after 3 fails, mark device as offline in state, suppress further self-heal triggers, refactor logging to tiered DEBUG/INFO/WARN with hourly re-surface, expose state via per-child `online` attribute. See `vesync-driver-qa.md` BP21 entry for full design.
22. HTTP-error path log spam during network outages — `sendBypassRequest()` previously logged `logError` every poll cycle for every child when the hub lost internet (DNS failure, connection timeout, etc.) — e.g., 6 children × 1 cycle/min = 6 ERROR lines/min for the duration of the outage. Distinct from BP21 (which addresses empty-API-result path); this is the network-layer error path (`UnknownHostException`, `SocketTimeoutException`, `ConnectException`, `NoRouteToHostException`). Fix: classify exception by class name (cause-chain walk, depth 10, handles sandbox/proxy wrappers); on first network-class exception log one-time WARN and set `state.networkUnreachableSince` + `state.lastNetworkWarnAt` (both parent-level — network outages affect all children identically); subsequent errors are DEBUG-only; `emitNetworkWarnIfDue()` re-surfaces a WARN hourly while still down; recovery detected on first successful `httpPost()` completion → INFO with elapsed duration + all state fields cleared. Dual circuit-breaker: (1) top-level at `updateDevices()` entry skips the entire poll cycle during a known outage — prevents the parallel-child stall where all children dispatch `httpPost` near-simultaneously before the first failure can set state, causing all to stall for full connect-timeout; (2) per-call in `sendBypassRequest()` guards command-triggered (non-polling) calls. Both probe every 5 minutes for recovery. Coordination: top-level breaker sets `state.networkProbeInFlight = true` when firing a recovery probe, cleared in a `try/finally` at end of `updateDevices()`; per-call breaker checks `!state.networkProbeInFlight` so the probe cycle's children are not re-blocked. Covers both JDK and Apache HttpClient (8 exception classes total). Non-network exceptions keep the existing `logError` path. See `vesync-driver-qa.md` BP22 entry for full design.
23. `setLevel(N>0)` doesn't auto-turn-on the device when switch is off — SwitchLevel capability convention is that calling `setLevel` on an off device should turn it on AND set the level. The Levoit SwitchLevel-implementing drivers historically split: Superior 6000S (humidifier) had the auto-on guard in `setMistLevel`; Vital line had a guard in `setSpeed` but NOT on the `setLevel → setSpeedLevel → writeSpeedPreferred` path that bypasses `setSpeed`; Core line and fan drivers had no guard at all. User-visible symptom (surfaced 2026-05-02 via Willow Nap routine on maintainer hub): Room Lighting "Activate" calls `setLevel(100)` on a Levoit purifier child set to Dimmer activation type; the cloud accepts the speed/mode commands but the device stays physically off because nothing called `on()`. Affects Core 200S/300S/400S/600S, Vital 100S/200S, Tower Fan, Pedestal Fan (8 drivers). Fix: add `if (!state.turningOn && device.currentValue("switch") != "on") on()` at top of `setLevel(val)` in each affected driver, after the `val == 0 → off()` early-return guard. The `state.turningOn` re-entrance flag (already set by `on()` in Vital line; needs to be added to Core line's `on()` if not present) prevents recursion when `on()`'s own internal speed/mode setup re-enters. Also add the `if (val == 0) { off(); return }` early-return to Core line drivers (already present in Vital + fan drivers). See `vesync-driver-qa.md` BP23 entry for canonical fix shape; live-verified on maintainer hub via Willow Nap routine post-fix. Note: this bug class is the same shape as BP1 (every-driver-needs-identical-boilerplate) and is a strong candidate for class-library extraction (Core line / Vital line / Fan line shared libraries) — see ROADMAP.md.
24. C3 gate case-sensitivity — on/off setter methods compare raw caller input against stored attribute values without lowercasing first. VeSync attribute values are always lowercase (`"on"` / `"off"`). When Rule Machine or a dashboard passes `"ON"` or `"OFF"` (uppercase), two compounding bugs occur simultaneously: (a) the C3 idempotency gate (`device.currentValue("x") == onOff`) evaluates `"on" == "ON"` as `false` even when the device is already in the requested state — the gate is bypassed and a redundant cloud call is made every time; (b) the payload coercion (`onOff == "on" ? 1 : 0`) evaluates `"ON" == "on"` as `false` — the payload sends the OPPOSITE of the intended value (e.g. `childLockSwitch:0` when the user asked to lock, `enabled:false` when the user asked to enable auto-stop). Both sub-bugs fire together for any uppercase input. The pattern also applies to early-return routing — `setSpeed("OFF")` with raw `spd=="off"` check routes to `mapSpeedToInteger` instead of `off()`. The fix is to apply `String v = (onOff as String).toLowerCase()` immediately after the BP18 null-guard and before all comparisons, then use `v` throughout. The canonical correct implementation is `LevoitHumidifierLib.groovy`'s `doSetDisplayScreenSwitch`. `Fix scope: class-wide` — affects every on/off setter method across the driver surface that (1) performs a `== "on"` or `== "off"` comparison AND (2) does not already have a `toLowerCase()` normalization. All sites fixed in v2.5 sweep: `setChildLock` + `setDisplay` + `setLightDetection` + `setPetMode` + `setSpeed` (LevoitVitalPurifierLib); `setChildLock` + `setDisplay` (LevoitCorePurifierLib, with BP18 null-guards also added); `setLightDetection` (LevoitVital200S per-driver); `setAutoStop` (LevoitClassic200S, LevoitClassic300S, LevoitDual200S); `setDisplay` + `setChildLock` + `setLightDetection` (LevoitEverestAir); `setDisplay` + `setChildLock` (LevoitSproutAir, with BP18 null-guards also added); `setNightlightSwitch` (LevoitOasisMist450S); `setNightlight` (LevoitOasisMist1000S, LevoitSproutHumidifier); `setNightLight` (LevoitCore200S Light — API payload coercion only; no C3 gate on this method). Drivers already correct (out of scope): LevoitHumidifierLib `doSetDisplayScreenSwitch`, LevoitSuperior6000S, LevoitLV600S, LevoitLV600SHubConnect (all pre-lowercase via LevoitHumidifierLib or LevoitHumidifier shared method). **Re-blessed canonical pattern (v2.6)**: the original v2.5 fix normalized with `.toLowerCase()` but emitted the raw normalized var, meaning truthy inputs "true"/"1"/"yes" got stored as-is in the attribute. The re-blessed pattern derives a canonical value via a truthy-coercion ternary: `String val = ...; String canon = (val in ["on","true","1","yes"]) ? "on" : "off"` and emits `canon` in both `sendEvent` and the C3 gate comparison. See `LevoitHumidifierLib.doSetDisplayScreenSwitch` for the verbatim blessed reference. RULE33b (`tests/lint_rules/bp25_case_sensitivity.py`) enforces the canonical emission on both `set*` and `doSet*` shared-helper definitions (doSet* coverage ensures regressions in the blessed reference itself are caught); `check_c3_gate_coverage.py` `_is_onoff_emitting()` recognizes the truthy-ternary pattern. Two intentional exceptions: (1) FanLib's shared helpers use a strict enum-rejection gate that rejects truthy variants (documented behavioral divergence from HumidifierLib; RULE33b's `_STRICT_GATE_RE` exempts these); (2) multi-state enum setters (setNightlightMode, etc.) are not binary on/off and are not in scope. **Var-name note:** the `val`/`canon`/`v` trio (`val` = normalize, `canon` = canonical, `v` = payload Integer) describes variables in the `doSetDisplayScreenSwitch` blessed-reference where an explicitly-named Integer payload var is needed for `[screenSwitch: v]`. Per-driver inline implementations may use `v` as the normalize String without conflict — RULE33b validates emit correctness, not variable names.

    **BP24 classification taxonomy** — every on/off setter is classified at the source site with a one-line comment:
    - `// BP24: SHOULD-ON — SwitchLevel/FanControl convention; turn device on if currently off.` — command-type setters where powering on IS implied (speed/level commands). These MUST call `ensureSwitchOn()` before the API call.
    - `// BP24: NO-ON — configures a device preference; powering on is not implied.` — preference-type setters where powering on is NOT implied (display, child lock, auto-stop, drying mode, nightlight, oscillation, etc.). These MUST NOT call `ensureSwitchOn()`.
    - `// BP24: SKIP-OK — non-MUST command; auto-on behavior is device-specific and caller-controlled.` — reserved for edge cases that don't fit either classification.
    A site lacking any BP24 comment is flagged by `tests/check_bp24_classification.py` (exits nonzero on any NO-ON site missing the comment). The comment is the classification marker that lets a future maintainer distinguish deliberate-NO-ON from an accidental missing `ensureSwitchOn()` call.

25. Unsafe integer coercion on command parameters — `(x as Integer)` and `.toInteger()` throw `NumberFormatException` or `GroovyCastException` BEFORE the `?:` Elvis fallback can intercept the exception, because in Groovy the `?:` operator catches null but NOT thrown exceptions. The Hubitat sandbox swallows the exception silently, leaving the command a no-op with no log entry. Sources of non-numeric command args: Rule Machine parameter field left blank (passes `""` or `null` as a string), dashboard numeric tile sends `"5.7"` (decimal string), hub variable binding passes `"true"` or `"1"`. Fix: replace every `(x as Integer) ?: N` site in command-param coercion paths with `safeIntArg(x, N)` from `LevoitChildBaseLib`. The helper does a `toString().trim()` → `.isInteger()` / `.isBigDecimal()` chain with an explicit fallback, and is belt-and-suspenders safe for all Rule Machine input types. `Fix scope: class-wide` — applies to every command method across all drivers that coerces a user-supplied numeric parameter to Integer. Fixed in v2.6 sweep via `safeIntArg()` extraction into `LevoitChildBaseLib` + ~20 site retrofit across Core 200S/300S/400S/600S, Vital 200S, LevoitCorePurifierLib, LevoitVitalPurifierLib, LevoitFanLib, Superior 6000S, Classic 200S/300S, Dual 200S, LV600S, LV600S HubConnect, OasisMist 450S/1000S, Sprout Humidifier, EverestAir, Sprout Air, Pedestal Fan. Lint rule RULE37 (`tests/lint_rules/bp26_unsafe_int_coercion.py`) enforces the pattern going forward.

    **RULE37 scope (Pass 1 — scalar params):** scans `set*` / `doSet*` / `cycle*` methods. Scalar typed params (`Integer level`, `Number n`) are safe — the Hubitat sandbox enforces the declared type at dispatch time, so non-numeric inputs never reach the method body. Scalar UNtyped params are unsafe. `doSet*` shared-helper definitions are included in scope because they receive the same user-supplied input that the public `set*` caller normalises and then passes through unchanged.

    **RULE37 scope (Pass 2 — Map-field coercions):** scans `set*(Map mapParam)` and `set*(colorMap)` (untyped Map-looking param) methods for unsafe coercions of Map-field accesses (e.g. `colorMap?.hue as Integer`). Map field values are always `String` at runtime — they come from Rule Machine color-map expressions, dashboard color pickers, or Maker API. The canonical case is `setColor(Map colorMap)` on OasisMist 450S. **Partially-guarded methods are still flagged:** if a method guards some fields with `safeIntArg(m.a, 0)` but raw-casts others (`m.b as Integer`), RULE37 still flags the unguarded `m.b` cast — the presence of `safeIntArg` on one field does NOT exempt the whole method.

    **API-response-field coercions** in `applyStatus`/`update` are intentionally out of scope — the VeSync API contract guarantees numeric types for device-status fields, so only user-controlled command parameters are untrusted inputs; RULE37's `set*`/`doSet*`/`cycle*`-only method predicate enforces this boundary.

27. VeSync API endpoint deprecated via appVersion gate — VeSync periodically tightens its server-side appVersion validation on legacy endpoints. The legacy `/cloud/v1/user/login` (method "login") was hit by this in early 2026 — fresh installs got `code: -11012022 msg: "app version is too low"` despite valid credentials. The symptom user-side is "Login failed - check credentials and retry" even with known-good credentials. The fix shape: align the parent driver's auth flow + `APP_VERSION` + endpoint URLs with pyvesync's current release (3.4.1+). pyvesync moves first when VeSync tightens; this fork follows. `Fix scope: per-instance` — each endpoint deprecation is its own surgery (the v2.7 release migrated `login()` to pyvesync 3.4.1's two-stage OAuth flow; future gate-tightenings on `getDevices`, `bypassV2`, or other endpoints would each need their own migration). Detection: look for inner code `-11012022` or similar version-range codes in error responses; cross-reference pyvesync's `src/pyvesync/const.py` `APP_VERSION` against this driver's `@Field static final String APP_VERSION` constant — drift of more than one minor version is a leading indicator. There is no automatic lint rule yet; the first recurrence of this pattern will trigger adding one (per the coverage-sub-agent calibration: a missing lint rule for a new pattern is WARN-not-FAIL on the first occurrence).

28. Non-numeric level/mist value silently turns the device OFF — level/mist setters did `Integer lvl = safeIntArg(param, 0); if (lvl <= 0) { off(); return }`. Because `safeIntArg` substitutes its fallback (0) on NON-numeric input, a typo like `setMistLevel("hgih")` coerces to 0 and turns the device OFF — indistinguishable from an explicit `setMistLevel(0)`. The off-form has two shapes: a bare `off()` (direct power-off), AND a `set*("off")` sub-feature call reached via a `< N` threshold — `setLevel("brght")` on Core 200S Light coerces to 0, falls through `if (pct < 10)`, and sends `setNightLight("off")`. The contract is now: explicit `0`/low → off-form (preserved); non-numeric garbage → ignore with a one-line WARN, device stays as-is; null/empty → already handled upstream by `requireNotNull`/the parser. `Fix scope: class-wide` — every level/mist `set*` method that routes a `safeIntArg(<userParam>, 0)` result into a numeric-threshold branch (`<= 0`/`== 0`/`< <int-literal>`) whose body calls an off-form (`off()` OR `set*("off")`). Canonical fix: `parseLevelOrNull(raw)` from `LevoitChildBaseLib` — returns the parsed Integer for genuinely-numeric input (including `"0"` and `"5.7"`→5) and **null** for unparseable/null/empty input; callers do `Integer lvl = parseLevelOrNull(level); if (lvl == null) { logWarn "...ignoring non-numeric value '${level}'"; return }; if (lvl <= 0) { off(); return }` (re-apply any clamp after the null-guard). Out of scope (deliberately keep `safeIntArg`): sites where fallback-0 is a valid clamp floor (`setHumidity`, brightness), means "no timer" (`setTimer` 0 → cancel), or where 0 is a valid setting (`setWarmMistLevel` 0 = warm-off). Fixed in v2.8: 14 sites across 13 files (`setMistLevel` on Superior 6000S / OasisMist 450S+1000S / Sprout Humidifier / LV600S / LV600S HubConnect / Classic 200S+300S / Dual 200S; `setLevel` on LevoitCorePurifierLib / LevoitFanLib / LevoitVitalPurifierLib / Superior 6000S; `setLevel` set*("off") off-form on Core 200S Light). Lint rule RULE40 (`tests/lint_rules/bp28_level_off_ambiguity.py`) flags the old shape via a four-way conjunction (fallback-0 assign + threshold + off-form-in-branch + no parseLevelOrNull); must-catch (incl. the `< 10`→`setNightLight("off")` form) + must-not-catch (incl. a `safeIntArg`-fed `< N` with no off-form) fixtures in `tests/lint_test.py::TestRule40BP28LevelOffAmbiguity`.

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

The 4-agent pipeline is shaped around model-cost asymmetry: dev Opus (full per-token cost, fewer defects — see "Developer dispatch: model selection" above), QA Sonnet (~5× cheaper than Opus per token), tester/ops Haiku (~19× cheaper than Opus). Resume via `SendMessage` on warm cache preserves ~60-70% of input tokens. Tester/ops contain raw output into ~1KB structured summaries — main + dev + QA contexts stay lean. See "QA dispatch: model selection" above for QA cost-attribution detail (48/29/15/3/5).

---

## When this file is wrong

If reality and this file diverge (driver structure changes, new pattern emerges, bug-catalog grows), update this file in the same PR. The agents read CLAUDE.md as part of their context priming; outdated CLAUDE.md misleads future work.
