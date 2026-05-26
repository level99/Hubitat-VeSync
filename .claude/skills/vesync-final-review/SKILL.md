---
name: vesync-final-review
description: Final pre-PR multi-agent QA review for Hubitat-VeSync driver fork. Runs the Haiku pre-flight gate, then dispatches up to 6 specialized sub-agents in parallel (coverage, platform, protocol, adversarial, design, operator) PLUS the OpenAI Codex CLI as a different-model second-opinion pass, synthesizes their findings into a unified report. Use BEFORE opening or marking a PR ready-for-review. Distinct from the cheaper pipeline-stage `vesync-driver-qa` agent. Usage: /vesync-final-review [PR# OR base..HEAD OR brief context]
context: hubitat-vesync-fork
disable-model-invocation: true
---

# VeSync Driver Final Review — Multi-Agent Fan-Out

Run the comprehensive multi-agent QA review before opening or marking a PR ready-for-review. You (main session) are the orchestrator — dispatch pre-flight first, then in a single parallel beat dispatch the 6 specialized Claude sub-agents AND fire the OpenAI Codex CLI as a different-model second-opinion pass, gather all reports, synthesize into a unified report.

Codex is **not a Claude sub-agent** — it's orchestrated via a `Bash` call from this skill in the same parallel dispatch message as the 6 `Agent({...})` calls. Different model family = different blind spots. Empirically calibrated to catch doc-vs-code drift and sibling-pattern incompleteness the 6-agent Claude fan-out misses.

## When to use this skill vs the pipeline `vesync-driver-qa` agent

- **Pipeline `vesync-driver-qa` agent** (cheap, iterative): use via `Agent({vesync-driver-qa})` during dev↔qa iteration loops. Sonnet-default. ~$1-2 per round, cache-warm resumes. Catches ~60-70% per round; cache + context accumulates across rounds.
- **`/vesync-final-review` skill** (thorough, gated): use ONCE before opening a PR or flipping draft → ready-for-review. ~$8-10 per run with full multi-agent fan-out. Catches near-100% of what the maintainer or community thread reader would otherwise flag.

Don't invoke this skill for trivial fixes mid-iteration — the cheap pipeline agent is the right tool there. Use this skill at the ship-readiness gate.

**Why a skill, not a coordinator agent**: per [Claude Code docs](https://code.claude.com/docs/en/sub-agents), sub-agents cannot dispatch other sub-agents. The Task/Agent tools are stripped at the second nesting level. Only the main session can fan out, so coordination has to happen here, not inside a coordinator sub-agent.

## Instructions

Argument: `$ARGUMENTS` — the PR number, branch range, or context to audit.

### Step 1 — Identify audit target

Parse `$ARGUMENTS`:
- If it looks like `#N` or `PR N` or just a number → audit a GitHub PR. Use `gh pr view <N> --json headRefName,baseRefName,headRefOid,baseRefOid` to find the branch and commit refs. Check out the branch locally if needed.
- If it looks like `<sha>..<sha>` or `<branch>..<branch>` → audit a local diff range directly.
- Otherwise treat as freeform context — assume current branch HEAD vs `upstream/main` (or `origin/main` if no upstream remote), and use `$ARGUMENTS` as additional scope/focus context to pass to sub-agents.

Confirm the audit scope to the user briefly before dispatching: which branch, which base, what's in the diff.

### Step 1b — Codex auth/install pre-check

Before dispatching anything else, check whether the OpenAI Codex CLI is installed and authenticated. This determines whether the parallel fan-out includes the Codex second-opinion pass or runs Claude-agents-only.

```bash
codex login status 2>&1
```

Interpret:
- `Logged in using ChatGPT` or `Logged in using API key` → set `codex_available = true`. Codex will participate in the parallel dispatch.
- `Not logged in` → set `codex_available = false`. Warn the user once: *"Codex CLI is installed but not authenticated (`codex login`). Proceeding with 6-agent Claude fan-out only."* Do NOT abort the skill.
- Exit non-zero / `command not found` / similar → set `codex_available = false`. Warn the user once: *"Codex CLI is not installed on PATH. Proceeding with 6-agent Claude fan-out only. To install: `npm install -g @openai/codex`."* Do NOT abort the skill.

When `codex_available = false`, the rest of the skill behaves as if Codex did not exist — the 6 Claude sub-agents still fan out and produce the unified report. The synthesis step skips the Codex-merge logic.

### Step 2 — Dispatch pre-flight agent (gate)

Dispatch the `vesync-driver-qa-preflight` Haiku agent FIRST. It runs in ~60-90 seconds and does:
- Lint --strict, Spock compile, manifest sanity, BP catalog grep, convention scan, path-leakage, version lockstep, FORK_RELEASE_VERSION lockstep
- Diff classification + dispatch plan (which of the 6 deep-audit sub-agents to fan out to)

```
Agent({
  subagent_type: 'vesync-driver-qa-preflight',
  name: 'qa-preflight',
  prompt: `
Pre-flight check on PR <PR# or branch> at HEAD <SHA>. Base: <base SHA>.

Workdir: <workdir>.

Run all pre-flight mechanical checks + diff triage per your definition. Return structured PASS/FAIL/UNCERTAIN verdict + dispatch plan.
`
})
```

**Gate the fan-out on pre-flight verdict:**

- **PASS**: parse the dispatch plan from the report, fan out the marked sub-agents in parallel per Step 3.
- **FAIL**: report the FAIL findings to the user and STOP. Do not dispatch any deep-audit sub-agents. Broken PR costs ~$0.005 instead of the full audit's $5-8.
- **UNCERTAIN**: surface the uncertainty to the user. Default to dispatching all 6 sub-agents (fall through to Step 3), but flag what was uncertain so the user can intervene.

### Step 3 — Parallel fan-out per pre-flight's dispatch plan

The pre-flight agent's report includes a structured dispatch plan:

```
| Reviewer | Dispatch? | Reason if skipping |
|---|---|---|
| coverage | YES | … |
| platform | YES | … |
| protocol | YES | … |
| adversarial | YES | … |
| design | YES | … |
| operator | YES | … |
| codex | YES | … |
```

Dispatch the reviewers marked YES. Brief the user: "Pre-flight PASS. Dispatching N of 6 Claude sub-agents — <list> — in parallel, plus Codex second-opinion pass." If skipping any, the user sees why.

**Codex override**: regardless of pre-flight's classification, force the `codex` row to NO when `codex_available = false` (Step 1b set this). Pre-flight does not know whether Codex is installed/authenticated; the skill owns that gate.

For reference, the matrix the pre-flight agent applies (don't re-compute it; trust pre-flight's classification):

| Diff content | Coverage | Platform | Protocol | Adversarial | Design | Operator | Codex |
|---|---|---|---|---|---|---|---|
| Pure docs (`*.md` only) | YES | maybe | NO | NO | maybe | YES | maybe |
| New driver added | YES | YES | YES | YES | YES | YES | YES |
| Existing driver behavior changed | YES | YES | YES | YES | YES | YES | YES |
| New library / lib refactor | YES | YES | YES | YES | YES | maybe | YES |
| Spec-only changes | maybe | NO | NO | YES | maybe | NO | maybe |
| Lint rule change | YES | YES | NO | maybe | NO | NO | maybe |
| Manifest-only change | NO | YES | NO | NO | NO | maybe | NO |
| `version:` bump only | NO | YES | NO | NO | NO | maybe | NO |
| CHANGELOG / release notes only | NO | NO | NO | NO | NO | YES | maybe |
| CI / workflow change | NO | YES | NO | NO | NO | NO | NO |
| Cut-release skill / agent def | NO | NO | NO | NO | YES | maybe | YES |

Codex column rationale: `maybe` for pure-docs (Codex is strong at doc-vs-code drift), `YES` for new driver / behavior change / new library / cut-release-skill (sibling-pattern incompleteness + stale-narration axes pay off), `maybe` for spec-only (vacuous-guard axis), `maybe` for lint rule change (over-zealous enforcement axis), `NO` for trivial mechanical changes (version bump / manifest-only / CI). When in doubt, lean YES — Codex is one message on Plus billing.

### Step 4a — Codex CWD selection (skip if `codex_available = false`)

Codex runs against a working tree on disk. Pick one of two modes for the `-C` argument:

**Mode A — current HEAD (default).** Use when the audit SHA equals the current local HEAD AND the working tree has no uncommitted changes touching `*.groovy`, `*.md`, or `tests/**`:

```bash
git rev-parse HEAD                            # must equal the audit HEAD SHA
git status --porcelain -- '*.groovy' '*.md' 'tests/**'   # must produce no output
```

If both checks pass, set `CODEX_CWD=<repo-root>` and skip the worktree step. Typical case (self-audit of current HEAD).

**Mode B — temporary worktree (fallback).** Use when Mode A's preconditions fail (cross-PR audit, historical SHA review, dirty working tree, branch checkout mid-review):

```bash
git worktree add ../codex_review_<short-sha> <HEAD-SHA>
# CODEX_CWD=<repo-parent>/codex_review_<short-sha>
```

The `<short-sha>` is the first 8 chars of the audit HEAD SHA. Remember to clean up via `git worktree remove ../codex_review_<short-sha>` in Step 6 after synthesis completes.

### Step 4b — Issue the parallel fan-out

Dispatch the YES Claude sub-agents in a SINGLE message with multiple `Agent({...})` calls, AND in the same message fire the Codex Bash call if `codex_available = true`. All run concurrently. Each Claude sub-agent gets the same diff context but its scope is its specialization:

```
Agent({
  subagent_type: 'vesync-driver-qa-coverage',
  name: 'qa-coverage',
  prompt: `
Audit PR <PR# or branch> at HEAD <SHA>. Base: <base SHA>.
Diff: \`git diff <base>..HEAD\`.

Workdir: <workdir>.

[scope/focus context from $ARGUMENTS]

Return your standard structured report per your definition.
`
})
Agent({
  subagent_type: 'vesync-driver-qa-platform',
  name: 'qa-platform',
  prompt: <same shape>
})
... (other Claude sub-agents) ...
Bash({
  command: `cat <<'CODEX_PROMPT' | codex exec -C $CODEX_CWD -s read-only --skip-git-repo-check -o <repo-parent>/.codex_review_<short-sha>.md
<calibrated prompt from Step 4c, with <BASE_SHA> and <HEAD_SHA> substituted>
CODEX_PROMPT`,
  run_in_background: true,
  timeout: 1200000,
  description: 'Codex CLI second-opinion pass'
})
```

The Bash call uses `run_in_background: true` so it returns immediately; the orchestrator gets notified when the codex job exits. Output is written to `<repo-parent>/.codex_review_<short-sha>.md` (a sibling of the repo, not inside the worktree) so it persists after Mode B's worktree is cleaned up.

**Capture each Claude sub-agent's agent ID** from the dispatch result. Store for SendMessage on re-review rounds. The pattern:

```
agentId: <id> (use SendMessage with to: '<id>' to continue this agent)
```

Record these in your working memory for this skill invocation. Codex does not have a transcript-resume API — re-review rounds re-run Codex fresh (see Re-review rounds section below).

### Step 4c — Codex prompt template

This is the calibrated 7-axis prompt that Step 4b's heredoc inlines. Substitute `<BASE_SHA>` and `<HEAD_SHA>` with the actual commit refs from Step 1. The template was calibrated against PR #13 (v2.6) pre-merge HEAD `d6f9a35`; in that run Codex caught 4 real findings (1 latent NUL byte in an agent definition, 2 stale comments contradicting current code, 1 stale tooling docstring) that 30 sweeps of the existing pipeline + Gemini review had missed.

````
# Your role

You are a senior Hubitat Elevation platform engineer reviewing a Hubitat Groovy driver-pack pull request. Specifically you are an expert in:

- **Hubitat Elevation platform** — the sandboxed Groovy runtime for apps and drivers. You know the sandbox restrictions, the parent-child driver architecture, the `definition() { ... } preferences { ... }` block conventions, capability declarations, attribute / command semantics, the `subscribe()` / `unsubscribe()` event model (apps only — drivers can NOT subscribe), the `schedule()` cron vs `runIn(N, "handler")` in-memory-only scheduling distinction (runIn evaporates on hub reboot — only `schedule()` survives), why `runIn` handler arg must be a string literal (sandbox classloader binding), `state` vs `atomicState` semantics, `device.currentValue("x")` vs `device.latestValue("x")`, `sendEvent(name: "x", value: "y")` event emission, and the `#include namespace.LibraryName` mechanism (textual paste at parse time, not runtime linking).
- **Levoit / VeSync cloud integration** — the VeSync HTTPS API, the `bypassV2` envelope shape, response peeling, the `configModule` routing field per device family, snake_case vs camelCase field-name conventions per device firmware, token-expiry re-auth, the two-stage OAuth flow.
- **pyvesync (webdjoe/pyvesync)** is the canonical reference for VeSync API behavior. When the driver code's payload, field name, or response shape is in question, pyvesync's `src/pyvesync/` for the matching device family is ground truth.
- **Groovy semantics in the Hubitat sandbox** — closure scope, `@Field static final` (Hubitat-allowed), `def` vs typed params (sandbox dispatches differently — typed primitive params get enforced at dispatch time, untyped don't), the `as Integer` / `.toInteger()` exception-throw-before-Elvis trap (Groovy's `?:` catches null but NOT thrown exceptions).

# Required reading FIRST (before reviewing the diff)

Read these files in the working tree to load the fork's specific conventions, architecture, and bug-pattern catalog into your context. They're the canonical, always-current source of truth — read them at task time, do not rely on training-data knowledge:

1. **`CONTRIBUTING.md`** — full bug-pattern catalog (BP1-BPN), every lint rule's purpose, the 5-driver-family layout, parent-child architecture, HPM packaging via `bundles[]`, every convention this codebase enforces.
2. **`CLAUDE.md`** — fork-specific AI-pipeline overlay; contains the architecture summary, family-line cleavage, bug-pattern conventions, and the cross-cutting / fix-scope discipline rules.
3. **`.claude/agents/vesync-driver-qa-preflight.md`** + **`.claude/agents/vesync-driver-qa-coverage.md`** + **`.claude/agents/vesync-driver-qa-platform.md`** + **`.claude/agents/vesync-driver-qa-protocol.md`** + **`.claude/agents/vesync-driver-qa-adversarial.md`** + **`.claude/agents/vesync-driver-qa-design.md`** + **`.claude/agents/vesync-driver-qa-operator.md`** — the 6 parallel Claude sub-agents reviewing this PR alongside you. Read these to understand exactly what they cover, so you can focus your output on what they're likely to miss.
4. **`Drivers/Levoit/readme.md`** — per-driver feature/capability/attribute reference (helps you tell whether a doc row is drifted from code).
5. **`docs/oauth-flow.md`** (if it exists) — internal-debugging reference for the two-stage OAuth login flow introduced in v2.7. Worth reading if the diff touches `VeSyncIntegration.groovy` auth code.

# Your task

Review the diff: `git diff <BASE_SHA>..<HEAD_SHA>` in the current working directory. Read whatever additional files you need for context.

Surface findings the maintainer would flag in review — bugs, regressions, dishonest commit messages, BREAKING changes that aren't called out as BREAKING, comments/docs that drift from code, sibling-pattern incompleteness across the 5 driver families (Core / Vital / Classic / V2-humidifier / Fan).

# What we want from you (focus areas where our 6 sub-agents are weakest)

The 6 parallel Claude sub-agents (whose definitions you've now read) cover most of the well-trodden ground. Their blind spots — where your output adds the most value — tend to be:

- **Doc/comment-vs-code drift.** A docstring, comment, README row, BREAKING note, or BP-catalog entry that contradicts what the code now does. Triangulate across files when the drift spans multiple places.
- **Sibling-pattern incompleteness across drivers in the same family.** When a fix lands on driver A but the same pattern exists on driver B in the same family and didn't get the fix.
- **Vacuous regression-guards.** A Spock spec or assertion declared to "prove" a fix that would pass identically with or without the fix applied.
- **Over-zealous code-side enforcement.** A new lint rule, verifier, or assertion that flags real existing patterns as wrong, OR that is structurally easy to circumvent.
- **Stale narration.** A commit message, BREAKING note, or BP-catalog entry that overstates or understates what the diff actually changes.
- **HPM-bundle integrity gaps.** `levoitManifest.json bundles[].location` URL drift, `LIBS` array in `tools/build-bundle.py` vs files in the to-be-built ZIP, `install.txt` / `update.txt` line mismatch.
- **Anything else a senior maintainer would catch on first read** that doesn't fit the categories above. You're not bound to these axes — they're hints, not a checklist.

Don't waste output re-flagging things in the 6 sub-agents' scopes unless you spot a specific gap in their coverage on this diff.

# Output format

Markdown. Each finding:

```
## [SEVERITY] <short title>
- File:line — what's wrong
- Why it matters — concrete consequence (user-visible / cloud-reject / future maintenance hazard)
- Suggested fix — minimum-viable patch
```

SEVERITY: BLOCKING (ships a bug or regression), WARN (should-fix before merge), NIT (improvement, not blocking).

If you find nothing material, say so explicitly. Don't pad with style nits or hypothetical concerns.
````

This template is the source of truth; do not duplicate it elsewhere in the codebase.

### Step 5 — Run sub-agents in background optional

If the PR is large and the user is doing other work, dispatch each sub-agent with `run_in_background: true`. Otherwise foreground is fine (parallel dispatch finishes when the slowest sub-agent finishes — typically 6-12 min for full fan-out on a typical driver PR).

### Step 6 — Gather reports + synthesize

When all dispatched Claude sub-agents return AND the Codex background job notifies completion (if dispatched), read `<repo-parent>/.codex_review_<short-sha>.md` into your context. If Codex was not dispatched (auth gate failed in Step 1b, or matrix marked it NO), skip the Codex-merge logic below and proceed with Claude-only synthesis.

Produce a unified report with this structure:

```markdown
# VeSync Driver QA Report — <PR# / branch>

**Scope reviewed:** <base..HEAD, files touched count, lines>
**Sub-agents dispatched:** <list>
**Verdict:** PASS | PASS WITH WARNINGS | FAIL

## Summary

<2-3 sentence bottom line synthesizing across sub-agents>

## Findings

### Critical (blocks submission)
- [FAIL] <sub-agent>: <finding> — <file:line> — <fix>

### Warnings (should fix before submission)
- [WARN] <sub-agent>: <finding> — <file:line> — <fix>

### Passed
<one-line per clean sub-agent scope>

## Sub-agent reports

[Inline each sub-agent's report verbatim, or summarize if very long]

## Recommended next steps
1. ...

## Reviewer IDs (for re-review rounds)
- coverage: <id>
- platform: <id>
- protocol: <id>
- adversarial: <id>
- design: <id>
- operator: <id>
- codex: re-runs fresh each round (no transcript resume; see Re-review rounds)
```

**Synthesis rules**:

- **Deduplicate**: if two reviewers flag the same file:line (likely on doc-vs-code or lint-vs-spec boundary), merge into one finding noting both perspectives. When Codex and a Claude sub-agent agree on a finding, that agreement strengthens severity — note both in the merged entry.
- **Severity merge**: if any reviewer flags BLOCKING, the unified finding is BLOCKING. Use the strongest reasoning.
- **Cross-cutting findings**: some issues span multiple reviewer scopes (a new SHOULD-ON method with no auto-on guard AND missing from-off Spock spec AND no CHANGELOG bullet). Merge into a single finding with multi-scope attribution.
- **Preserve reviewer voice**: cite the original reports verbatim in the appendix when clear. Don't paraphrase if it loses precision.
- **Codex-only findings**: if Codex caught something no Claude sub-agent flagged, label it `[codex-only]` in the unified report. This is exactly the second-opinion value the integration exists to capture — surface it prominently, not buried.
- **Codex-vs-Claude disagreement**: if Codex contradicts a Claude sub-agent's verdict on the same finding (e.g., Claude says PASS on a scope, Codex flags a BLOCKING in that same scope), present both perspectives in the unified report and explicitly note the disagreement. Let the user judge rather than auto-picking a side.
- **Worktree cleanup**: if Step 4a chose Mode B (temporary worktree), run `git worktree remove ../codex_review_<short-sha>` after the unified report is delivered. Skip cleanup in Mode A (no worktree was created). The `.codex_review_<short-sha>.md` output file is left in place as an audit artifact — clean it up manually if no longer needed.

### Verdict rules

- **PASS** = all sub-agents PASS, no warnings.
- **PASS WITH WARNINGS** = all sub-agents PASS or WARN, no BLOCKING.
- **FAIL** = any sub-agent reports BLOCKING.

## Re-review rounds (after dev fixes)

When the dev pushes fixes addressing prior findings, the user will re-invoke `/vesync-final-review` (or just ask "re-review with the fixes"). At that point:

1. Identify which findings the dev addressed (from PR comments, commit messages, or user's brief).
2. Identify which sub-agents flagged those findings (their scope).
3. **Resume those sub-agents** via `SendMessage` to their stored agent IDs:
   ```
   SendMessage({
     to: '<stored agent ID>',
     message: `
   Dev addressed your prior findings:
   - <Your finding>: <how dev addressed it>

   Re-review the updated diff focused on those changes.
   `
   })
   ```
4. **Sub-agents NOT to re-dispatch**: any whose scope wasn't touched by the fix. Their prior PASS verdicts still hold. Note in the unified report ("Platform: not re-reviewed — scope unchanged, prior PASS holds.").
5. If `SendMessage` returns `success: false` (agent transcript evicted), fall back to fresh `Agent({...})` with full context.
6. **Codex re-runs fresh each round.** The Codex CLI has no transcript-resume API — every invocation is independent. Decide whether to re-run based on whether the fix touched production code:
   - If the fix touched `Drivers/Levoit/*.groovy`, `Drivers/Levoit/*Lib.groovy`, `tests/lint_rules/`, `tests/check_*.py`, `levoitManifest.json`, or `tools/build-bundle.py` → re-run Codex (its findings may have shifted).
   - If the fix was doc-only (CHANGELOG `[Unreleased]`, README rows, BP-catalog entries) → skip Codex re-run; its prior findings on production code are still valid. The re-review is Claude-side only.
   - Re-running Codex spends +1 ChatGPT Plus message per round.

### Cost discipline

| Scenario | Cost estimate |
|---|---|
| Full round-1 (all 6 sub-agents + Codex) | ~200-300K Claude tokens, ~8-12 min wall time, +1 ChatGPT Plus message |
| Re-review with 1 sub-agent resumed (no Codex) | ~40-70K tokens, ~3-5 min |
| Re-review with 3 sub-agents resumed + Codex | ~100-150K tokens, ~5-8 min, +1 ChatGPT Plus message |
| Trivial doc-only fix re-review (no re-dispatch, no Codex) | ~10-20K tokens, ~30s |

Target: 2-3 round convergence for a typical PR. A typical PR cycle spends ~2-3 ChatGPT Plus messages across all Codex runs — well inside Plus's weekly headroom unless you also use Codex heavily for unrelated interactive coding.

## When NOT to use this skill

- **Trivial review requests**: "Does this change affect lib boundary?" → spawn just `design` directly, don't fan out.
- **Specific scope questions**: "Is this the right BP24 classification?" → answer directly, don't fan out.
- **Pre-PR sanity check on a one-liner fix**: dispatch just one targeted sub-agent.

Use the full fan-out for: pre-PR final review, post-feature-complete audit, when a PR has had multiple rounds and you want confidence convergence.

## When you find yourself doing the audit yourself

If you (main session) start doing the audit work directly instead of dispatching — STOP. That defeats the purpose of the skill. Either:
- Dispatch the appropriate sub-agent and let it do the work
- Tell the user the skill isn't the right tool for this scope and answer directly without invoking the multi-agent pattern

## Reviewer reference

8 reviewers total: 7 Claude sub-agents (defined in `.claude/agents/`) + the OpenAI Codex CLI. Pre-flight runs first as a gate; the 6 deep-audit Claude agents AND Codex fan out in parallel per pre-flight's dispatch plan:

| Reviewer | Model / Family | Orchestration | Dispatch order | Scope |
|---|---|---|---|---|
| `vesync-driver-qa-preflight` | Claude / Haiku | Claude sub-agent | First (gate) | Lint --strict, Spock compile, manifest sanity, BP catalog grep, convention scan, path leakage, version + FORK_RELEASE_VERSION lockstep, diff triage / dispatch plan |
| `vesync-driver-qa-coverage` | Claude / Opus | Claude sub-agent | Parallel (post-preflight) | Spock test coverage (BP-pattern regression tests, family-symmetric coverage, lib-spec coverage), lint rule coverage (new BP class → new rule), documentation coverage (`Drivers/Levoit/readme.md`, CHANGELOG `[Unreleased]`, BP catalog entry), fixture coverage |
| `vesync-driver-qa-platform` | Claude / Sonnet | Claude sub-agent | Parallel | BP14 (reboot survival), BP15 (driver vs app API), BP16 (debug watchdog), BP18 (null-guard), string-literal `runIn` handler, capability/attribute/command coherence, RULE30 logger discipline, 3-signature `update()` per BP1 |
| `vesync-driver-qa-protocol` | Claude / Sonnet | Claude sub-agent | Parallel | bypassV2 envelope shape, BP4 field-name verification (snake_case vs camelCase per family), BP3 envelope peel depth, BP13 token-expiry re-auth, configModule routing, pyvesync canonical cross-reference |
| `vesync-driver-qa-adversarial` | Claude / Opus | Claude sub-agent | Parallel | Input adversaries (null/empty/unicode/MAX_INT), state adversaries (guard-bypass), concurrency adversaries (async race, re-entrance), environment adversaries (BP14/16/17/19/21/22), Rule Machine adversaries (BP18 blank slots, C3 idempotency, BP23/BP24 from-off) |
| `vesync-driver-qa-design` | Claude / Sonnet | Claude sub-agent | Parallel | Lib boundary integrity (Phase 1-5 architecture), cross-line consistency (Core/Vital/Classic/V2/Fan family), helper-extraction opportunities, intentional-asymmetry rationale, BP24 SHOULD-ON/NO-ON/SKIP-OK classification |
| `vesync-driver-qa-operator` | Claude / Sonnet | Claude sub-agent | Parallel | BREAKING flag honesty (what breaks vs what's preserved), TMI filter (no impl-detail in user-facing prose), CHANGELOG `[Unreleased]` per-commit discipline, dashboard/RM impact disclosure, log discipline + PII sanitize routing, `Drivers/Levoit/readme.md` device-row updates, cut-release invariant trips |
| Codex CLI | OpenAI / GPT family | Skill-orchestrated `Bash` call (NOT a Claude sub-agent) | Parallel | Broad-review second-opinion pass with soft prioritization toward doc-vs-code drift, sibling-pattern incompleteness across driver families, vacuous regression-guards, over-zealous code-side enforcement, stale narration, HPM-bundle integrity. Different model family = different blind spots than the 6 Claude sub-agents. Prompt baked inline in Step 4c. |
