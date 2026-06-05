---
name: final-review
description: Final pre-PR multi-agent QA review for Hubitat-VeSync driver fork. Runs the Haiku pre-flight gate, then dispatches up to 6 specialized sub-agents in parallel (coverage, platform, protocol, adversarial, design, operator) PLUS two different-family external reviewers — the OpenAI Codex CLI and the Google Gemini CLI — as second-opinion passes, synthesizes their findings into a unified report. Use BEFORE opening or marking a PR ready-for-review. Distinct from the cheaper pipeline-stage `vesync-driver-qa` agent. Usage: /final-review [PR# OR base..HEAD OR brief context]
context: hubitat-vesync-fork
disable-model-invocation: true
---

# VeSync Driver Final Review — Multi-Agent Fan-Out

Run the comprehensive multi-agent QA review before opening or marking a PR ready-for-review. You (main session) are the orchestrator — dispatch pre-flight first, then in a single parallel beat dispatch the 6 specialized Claude sub-agents AND fire two different-family external reviewers (the OpenAI Codex CLI and the Google Gemini CLI) as second-opinion passes, gather all reports, synthesize into a unified report.

The two external reviewers are **not Claude sub-agents** — each is orchestrated via a `Bash` call from this skill in the same parallel dispatch message as the 6 `Agent({...})` calls. Different model family = different blind spots, and the benefit **compounds**: across consecutive ship gates the three families (Claude lenses + Codex + Gemini) each repeatedly catch real BLOCKINGs the *other two* miss — including cross-variant correctness bugs no single-family panel surfaces. Both external reviewers consume the SAME shared prompt file (authored once in Step 4b) and review the FULL change independently — they are not gap-fillers; their highest-value catches are independent findings.

## When to use this skill vs the pipeline `vesync-driver-qa` agent

- **Pipeline `vesync-driver-qa` agent** (cheap, iterative): use via `Agent({vesync-driver-qa})` during dev↔qa iteration loops. Sonnet-default. ~$1-2 per round, cache-warm resumes. Catches ~60-70% per round; cache + context accumulates across rounds.
- **`/final-review` skill** (thorough, gated): use ONCE before opening a PR or flipping draft → ready-for-review. ~$8-10 per run with full multi-agent fan-out. Catches near-100% of what the maintainer or community thread reader would otherwise flag.

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

### Step 1b — External-reviewer auth/install/usage pre-check (Codex + Gemini, independent)

Before dispatching anything else, probe BOTH external CLIs **independently** — each has its own availability flag, and a missing/blocked one never aborts the skill (graceful degrade, per external reviewer). Calibrate each new family independently before trusting it; verify-before-trust is permanent and per-finding for every external family, not a calibration-phase-only step.

**Codex (`codex_available`):**
```bash
codex login status 2>&1
```
- `Logged in using ChatGPT` / `Logged in using API key` → `codex_available = true`.
- `Not logged in` → `codex_available = false`. Warn once: *"Codex CLI installed but not authenticated (`codex login`). Proceeding without it."*
- Exit non-zero / `command not found` → `codex_available = false`. Warn once: *"Codex CLI not on PATH. Proceeding without it. Install: `npm install -g @openai/codex`."*
- Output indicates the ChatGPT plan's usage window is exhausted / rate-limited (substrings `usage limit`, `rate limit`, `quota`, `429`, `reset at`) → `codex_available = false`. Warn once: *"Codex authenticated but ChatGPT usage window exhausted (resets later). Proceeding without it."* Do NOT burn a probe message to confirm.

**Gemini (`gemini_available`) — gated on BOTH the CLI and `rg`, same graceful-degrade as Codex:**
```bash
gemini --version 2>&1     # CLI present?
command -v rg             # ripgrep on PATH? Gemini shells out to it for repo search
```
- `gemini` prints a version AND `rg` is on PATH → `gemini_available = true`.
- `gemini` `command not found` / exit non-zero → `gemini_available = false`. Warn once: *"Gemini CLI not on PATH. Proceeding without it. Install: `npm install -g @google/gemini-cli`."*
- `gemini` present but **`rg` absent** → `gemini_available = false`. Warn once: *"Gemini CLI present but ripgrep (`rg`) is not on PATH — Gemini falls back to a built-in grep that times out (~30s) on large files and stalls the review. Install `rg` (or have Step 4b prepend its dir to PATH). Proceeding without Gemini."*
- **Assume the PAID Google Gemini API tier is configured.** A FULL PR-sized review re-sends accumulated context across many agentic turns, spiking *input-tokens-per-minute*; the free tier's ~250K input-TPM cap (shared across all the vendor's free models — model-switching does NOT help) walls mid-review. `--version` only gates install presence — it does NOT verify the tier. **Smoke-test trap:** a one-token auth ping passes on the free tier and tells you nothing; only the real multi-turn review exercises the cap, caught by the Step 6 runtime guard. Do NOT burn a probe run to test the tier.

**Do NOT spend a real `codex exec` / `gemini -p` probe message just to test usage** — on the metered/per-message tiers the message budget is the scarce resource the gate exists to protect. The free `--version` / `login status` checks plus the Step 6 runtime guard (which catches a usage-exhaustion that only surfaces once the real call runs) are sufficient.

When an external reviewer's flag is `false`, the rest of the skill behaves as if it did not exist — the Claude fan-out (and the other external reviewer, if available) still runs and produces the unified report; synthesis skips that reviewer's merge logic. If BOTH are false, it's a pure Claude-only fan-out.

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

Dispatch the reviewers marked YES. Brief the user: "Pre-flight PASS. Dispatching N of 6 Claude sub-agents — <list> — in parallel, plus external second-opinion passes: <Codex and/or Gemini, per availability>." If skipping any, the user sees why.

**The `codex` matrix column gates BOTH external reviewers** — Codex and Gemini review the same diff against the same shared prompt, so the per-diff "is an external pass worth it?" decision is shared. When that row is YES, fire *each* external reviewer whose Step-1b availability flag is `true` (`codex_available`, `gemini_available` independently). When it's NO (trivial mechanical diff), fire neither. Pre-flight does not know whether either CLI is installed/authenticated; the skill owns that gate via the two flags.

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

External-reviewer (`codex` column) rationale: `maybe` for pure-docs (externals are strong at doc-vs-code drift), `YES` for new driver / behavior change / new library / cut-release-skill (sibling-pattern incompleteness + stale-narration + cross-variant correctness pay off), `maybe` for spec-only (vacuous-guard axis), `maybe` for lint rule change (over-zealous enforcement axis), `NO` for trivial mechanical changes (version bump / manifest-only / CI). When in doubt, lean YES — Codex is ~one ChatGPT-Plus message and Gemini on the paid API tier is per-token but a huge-input/tiny-output review is pennies, so both are cheap relative to the catch value.

### Step 4a — External-reviewer working tree (skip if both external flags false)

Both externals should run `git diff <base>..HEAD` themselves so they review the *change* (a git-less reviewer handed a file list reviews the whole file and surfaces real-but-out-of-scope findings). Their sandboxes differ, which drives the tree choice:

- **Codex** runs `-s read-only` (no writes) — safe pointed straight at the repo-root.
- **Gemini** runs `--yolo` (auto-approves shell + writes, so it CAN run `git diff` like Codex). That auto-approve blast radius MUST be contained in a **disposable git worktree** at the audit SHA — never the live tree.

**Decision:**

- **If Gemini will run (`gemini_available` + matrix YES):** create one disposable worktree at the audit SHA and run BOTH externals in it (Codex `-C` it, read-only; Gemini `cd` into it, `--yolo` contained):
  ```bash
  git worktree add ../review_<short-sha> <HEAD-SHA>   # REVIEW_CWD=<repo-parent>/review_<short-sha>
  ```
- **If only Codex will run:** *Mode A* — when the audit SHA == current HEAD and the tree is clean (`git status --porcelain -- '*.groovy' '*.md' 'tests/**'` is empty), set `REVIEW_CWD=<repo-root>`, no worktree. Else *Mode B* — `git worktree add ../review_<short-sha> <HEAD-SHA>`.

`<short-sha>` = first 8 chars of the audit HEAD SHA. Remove any worktree created here via `git worktree remove ../review_<short-sha>` in Step 6. (Containing `--yolo` in a throwaway worktree is enough for our own repo; add a `--sandbox`-style flag only when reviewing UNTRUSTED code.)

### Step 4b — Issue the parallel fan-out

**First author the shared prompt file (ONCE).** Both external reviewers consume the SAME prompt — identical input makes the cross-reviewer comparison meaningful and saves authoring two prompts. Build the Step-4c prompt (with `<BASE_SHA>`/`<HEAD_SHA>` and the per-diff HUNT invariants substituted) and `Write` it to a file *outside the repo* so the working tree stays clean: `<repo-parent>/.final_review_prompt_<short-sha>.md`. (Pass it as a FILE both CLIs read — never piped on the command line; a large prompt on argv blows the OS limit with "Argument list too long".)

Then dispatch the YES Claude sub-agents in a SINGLE message with multiple `Agent({...})` calls, AND in the same message fire one background `Bash` call per *available* external reviewer (`codex_available`, `gemini_available`). All run concurrently. Each Claude sub-agent gets the same diff context but its scope is its specialization:

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
Agent({ subagent_type: 'vesync-driver-qa-platform', name: 'qa-platform', prompt: <same shape> })
... (other YES Claude sub-agents) ...

// External reviewer 1 — Codex (can run git in its read-only sandbox; reads prompt from stdin):
Bash({
  command: `codex exec -s read-only -C $REVIEW_CWD - < <repo-parent>/.final_review_prompt_<short-sha>.md > <repo-parent>/.codex_review_<short-sha>.md 2>&1`,
  run_in_background: true, timeout: 1200000, description: 'Codex CLI second-opinion pass'
})   // only if codex_available

// External reviewer 2 — Gemini (--yolo = auto-approve shell so it runs git itself, contained
// by the disposable worktree cwd; needs rg on PATH; grant read of the out-of-repo prompt dir):
Bash({
  command: `cd $REVIEW_CWD && PATH="<dir-of-rg>:$PATH" gemini --yolo -m <model> --include-directories <repo-parent> -p "Read <repo-parent>/.final_review_prompt_<short-sha>.md and carry out the review it describes against this repo (the current working directory). Run 'git diff <base>..HEAD' yourself to see the change; read files for sibling context. Output [BLOCKING|WARNING|NIT] file:line + a one-line verdict." > <repo-parent>/.gemini_review_<short-sha>.md 2>&1`,
  run_in_background: true, timeout: 1200000, description: 'Gemini CLI second-opinion pass'
})   // only if gemini_available
```

Each `Bash` uses `run_in_background: true` so it returns immediately; you're notified when each job exits. Outputs go to `<repo-parent>/.{codex,gemini}_review_<short-sha>.md` (siblings of the repo, not inside the worktree) so they survive worktree cleanup. Per-CLI differences (do NOT assume one recipe transfers): **Codex** reads the prompt via stdin redirect (`- < file`), runs git in its read-only sandbox. **Gemini** can't be both read-only AND shell-capable, so it runs `--yolo` (auto-approves shell/writes) **inside the disposable worktree** (Step 4a) — that containment is why the worktree is mandatory whenever Gemini runs; it then runs `git diff` itself and reviews the *change*, not whole files. Gemini needs `rg` on PATH (prepend `<dir-of-rg>` if not already there — without it, it stalls on a built-in-grep timeout). The prompt is *named* via a SHORT `-p` (never piped — argv limit), and `--include-directories`/`-C` grant the out-of-repo prompt dir. The CLI may silently resolve `-m <flash-vN>` to a newer flash variant — harmless; verify in the run log if it matters.

**Capture each Claude sub-agent's agent ID** from the dispatch result. Store for SendMessage on re-review rounds. The pattern:

```
agentId: <id> (use SendMessage with to: '<id>' to continue this agent)
```

Record these in your working memory for this skill invocation. Neither external CLI has a transcript-resume API — re-review rounds re-run each available external reviewer fresh (see Re-review rounds section below).

### Step 4c — Shared external-reviewer prompt (both Codex & Gemini consume it)

This is the SINGLE prompt both external CLIs read (authored to `<repo-parent>/.final_review_prompt_<short-sha>.md` in Step 4b). Substitute `<BASE_SHA>`/`<HEAD_SHA>` with the Step-1 refs, the explicit `<file list>` (Gemini can't compute a diff — list the changed files so it can read them), and a per-diff **HUNT** list of the load-bearing invariants THIS change must preserve.

**Frame it as a FULL independent review — NOT "find what our lenses missed."** This is the load-bearing reframe: the external reviewers' highest-value catches are INDEPENDENT findings (in this project: cross-variant correctness bugs and a missed state-freshness gate — none flagged by the in-house lenses). Priming with the full invariant set AND an open-ended "flag anything beyond this" surfaces those; narrowing to "just the gaps" suppresses the second-opinion value they exist to provide. (This broad-and-doc-loading framing applies to the EXTERNAL reviewers ONLY — the Claude lenses stay durably specialized per their per-lens defs; do not collapse them into freeform prompts.) A *soft* "our in-house lenses already cover X well" hint is fine to cut duplicate noise, but never as a scope boundary.

The role/domain-priming + read-docs-at-task-time structure was calibrated against PR #13 (v2.6) pre-merge HEAD `d6f9a35`, where the external pass caught 4 real findings (latent NUL byte in an agent def, 2 stale comments, 1 stale tooling docstring) that 30 sweeps + a bot review had missed.

````
# Your role

You are a senior Hubitat Elevation platform engineer reviewing a Hubitat Groovy driver-pack pull request. Specifically you are an expert in:

- **Hubitat Elevation platform** — the sandboxed Groovy runtime for apps and drivers. You know the sandbox restrictions, the parent-child driver architecture, the `definition() { ... } preferences { ... }` block conventions, capability declarations, attribute / command semantics, the `subscribe()` / `unsubscribe()` event model (apps only — drivers can NOT subscribe), the `schedule()` cron vs `runIn(N, "handler")` in-memory-only scheduling distinction (runIn evaporates on hub reboot — only `schedule()` survives), why `runIn` handler arg must be a string literal (sandbox classloader binding), `state` vs `atomicState` semantics, `device.currentValue("x")` vs `device.latestValue("x")`, `sendEvent(name: "x", value: "y")` event emission, and the `#include namespace.LibraryName` mechanism (textual paste at parse time, not runtime linking).
- **Levoit / VeSync cloud integration** — the VeSync HTTPS API, the `bypassV2` envelope shape, response peeling, the `configModule` routing field per device family, snake_case vs camelCase field-name conventions per device firmware, token-expiry re-auth, the two-stage OAuth flow.
- **pyvesync (webdjoe/pyvesync)** is the canonical reference for VeSync API behavior. When the driver code's payload, field name, or response shape is in question, pyvesync's `src/pyvesync/` for the matching device family is ground truth.
- **Groovy semantics in the Hubitat sandbox** — closure scope, `@Field static final` (Hubitat-allowed), `def` vs typed params (sandbox dispatches differently — typed primitive params get enforced at dispatch time, untyped don't), the `as Integer` / `.toInteger()` exception-throw-before-Elvis trap (Groovy's `?:` catches null but NOT thrown exceptions).

# Required reading FIRST (before you start)

Read these files in the working tree to load the fork's specific conventions, architecture, and bug-pattern catalog into your context. They're the canonical, always-current source of truth — read them at task time, do not rely on training-data knowledge:

1. **`CONTRIBUTING.md`** — full bug-pattern catalog (BP1-BPN), every lint rule's purpose, the 5-driver-family layout, parent-child architecture, HPM packaging via `bundles[]`, every convention this codebase enforces.
2. **`CLAUDE.md`** — fork-specific AI-pipeline overlay; contains the architecture summary, family-line cleavage, bug-pattern conventions, and the cross-cutting / fix-scope discipline rules.
3. **`.claude/agents/vesync-driver-qa-*.md`** (coverage, platform, protocol, adversarial, design, operator + preflight) — the parallel Claude sub-agents reviewing this PR alongside you. Read these so you know what's already well-covered — a SOFT dedup hint to cut duplicate noise, NOT a scope boundary. Review the WHOLE change against the invariant set + your own judgement; your most valuable findings are the independent ones the in-house lenses didn't think to look for.
4. **`Drivers/Levoit/readme.md`** — per-driver feature/capability/attribute reference (helps you tell whether a doc row is drifted from code).
5. **`docs/oauth-flow.md`** (if it exists) — internal-debugging reference for the two-stage OAuth login flow introduced in v2.7. Worth reading if the diff touches `VeSyncIntegration.groovy` auth code.

# Your task — FULL independent review

Review the entire change. **If you can run git, review `git diff <BASE_SHA>..<HEAD_SHA>`; otherwise read these files directly: `<explicit changed-file list>`.** Read whatever additional files you need for context. Exclude generated/vendored paths and the dependency dir.

WHAT THIS CHANGE IS: `<2-3 lines: what the diff does, what must stay true>`.

HUNT — verify EACH of these against the actual code (these are the load-bearing invariants for THIS change; substitute per-diff):
  1. `<load-bearing invariant #1>`
  2. `<invariant #2>`
  ... `<the full set of properties that must hold for this change to be correct>` ...

Also flag ANYTHING beyond this list — spec gaps, missed cases, anything the spec or I overlooked. Do not limit yourself to the items above. The kinds of things this fork's maintainer cares about (use as a checklist of *shapes*, not a scope limit):

- **Cross-variant / sibling-pattern correctness.** A fix on driver A whose siblings in the same family (Core / Vital / Classic / V2-humidifier / Fan) didn't get it; a per-variant payload/field that's wrong on one model.
- **Doc/comment-vs-code drift.** A docstring, comment, README row, BREAKING note, or BP-catalog entry that contradicts what the code now does.
- **Vacuous regression-guards.** A spec/assertion declared to "prove" a fix that would pass identically with or without the fix.
- **Over-zealous code-side enforcement.** A new lint rule/verifier that flags real existing patterns, or is structurally easy to circumvent.
- **Stale narration.** A commit message, BREAKING note, or catalog entry that over/understates what the diff changes.
- **HPM-bundle integrity.** `levoitManifest.json bundles[].location` URL drift; `LIBS` in `tools/build-bundle.py` vs files in the built ZIP; `install.txt`/`update.txt` mismatch.
- **Anything a senior maintainer would catch on first read.**

Our in-house Claude lenses already cover the well-trodden ground (you read their defs) — that's a soft hint to avoid duplicate noise, NOT a boundary. Verify every claim against the actual code; do not assume.

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

When all dispatched Claude sub-agents return AND each dispatched external background job notifies completion, read each available external reviewer's output (`<repo-parent>/.codex_review_<short-sha>.md`, `<repo-parent>/.gemini_review_<short-sha>.md`) into your context. For any external reviewer not dispatched (flag false in Step 1b, or matrix marked the external column NO), skip its merge logic.

**Pull the verdict out of the noisy logs.** Both logs carry spinner frames, retries, and boilerplate; the findings are at the END:
```bash
# Codex: content follows the final `codex` banner line
awk '/^codex$/{f=1} f' <repo-parent>/.codex_review_<short-sha>.md | tail -40
# Gemini: strip spinner/retry/boilerplate, then tail
grep -vE "^(Loaded|Loading|Attempt [0-9]|Warning:|⠋|⠙|⠹|⠸|⠼|⠴|⠦|⠧|⠇|⠏)" <repo-parent>/.gemini_review_<short-sha>.md | tail -60
```

**Tell a brief throttle (wait) apart from a structural rate cap (needs paid tier) — by the reset interval in the error text.** Two different failure modes:
- **Brief throttle** — "retry after *N **seconds***", auto-recovers (a Gemini run may retry several times over ~40s and complete). Because the fan-out is parallel, a throttled reviewer normally lands within the pack's window. Policy: synthesize when the pack (lenses + tester + other external) finishes; if one external is still retrying, grant a short grace (≈ the slowest reviewer's runtime, capped a couple minutes past the pack), then proceed.
- **Structural rate cap** — "reset after *N **hours***" / "quota exhausted". A FULL-review re-sends accumulated context across many agentic turns, spiking input-tokens-per-minute; the **free** tier caps exactly that (~250K input-TPM, shared across all the vendor's free models — switching models does NOT help). **Waiting will NOT fix this and neither will a model switch — it needs the paid API tier** (we assume paid is configured; if it isn't, this is the symptom). Don't retry-loop against a structural cap.

**Drop an external reviewer only on a *sustained* outage (or an unconfigured-paid-tier structural cap), and state the drop EXPLICITLY in synthesis, never silently** (a drop is itself availability/cost data). Absent at dispatch → proceed without it; briefly throttled mid-run → wait within the window; structurally capped → note "Gemini: dropped (free-tier structural cap — needs paid API tier)" and synthesize with the rest.

**Filter out-of-scope findings from a git-less reviewer.** If a reviewer ran without git (fallback mode — it read whole files, not a diff), it may flag real bugs in *unchanged* code that merely shares a file with the diff. Those are correct-but-out-of-PR-scope; note them separately (or as follow-ups), don't treat them as blocking *this* PR. With both externals on the `--yolo`/git path this shouldn't arise, but verify each external finding is actually in the diff's scope before escalating.

**Runtime usage-exhaustion guard (the Step 1b pre-check's safety net), per external reviewer.** Step 1b can't always detect a usage block up front — a plan's window can be fine at pre-check time but exhaust mid-run, surfacing only in the background job's output. Before merging an external reviewer's findings, sanity-check its output file: if it's **missing, empty, or its body is a usage/rate-limit/auth error rather than a findings report** (substrings `usage limit`, `rate limit`, `quota`, `429`, `stream error`, `not logged in`), treat THAT reviewer as unavailable for this run — note e.g. `Codex: unavailable this run (usage/rate-limit)` or `Gemini: dropped (sustained throttle)` in the unified report's **Sub-agents dispatched** line, skip its merge, and proceed with the rest. Do NOT surface the raw error text as a "finding", and do NOT block or retry (a retry burns another message/quota against the same exhausted window). The Claude fan-out is the authoritative result; the external reviewers are always additive.

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
- codex / gemini: external CLIs — re-run fresh each round (no transcript resume; see Re-review rounds)
```

**Synthesis rules**:

- **Deduplicate**: if two reviewers flag the same file:line, merge into one finding noting both perspectives. Agreement across families (a Claude lens + Codex + Gemini on the same finding) strengthens severity — note all that flagged it.
- **Severity merge**: if any reviewer flags BLOCKING, the unified finding is BLOCKING. Use the strongest reasoning.
- **Cross-cutting findings**: some issues span multiple reviewer scopes (a new SHOULD-ON method with no auto-on guard AND missing from-off Spock spec AND no CHANGELOG bullet). Merge into a single finding with multi-scope attribution.
- **Preserve reviewer voice**: cite the original reports verbatim in the appendix when clear. Don't paraphrase if it loses precision.
- **External-only findings**: if an external reviewer caught something no Claude lens flagged, label it `[codex-only]` / `[gemini-only]` in the unified report. This is exactly the second-opinion value the integration exists to capture — surface it prominently, not buried. **Verify each external finding against the actual code before acting on it** (permanent, per-finding, every family — a hallucinated finding caught late costs a wasted fix round).
- **Disagreement — reviewer-vs-reviewer too, not just external-vs-Claude**: if any two reviewers contradict on the same finding (Claude-vs-external, OR the two externals **split** with each other — they reason independently and will sometimes disagree), present both perspectives in the unified report, adjudicate against the actual code first, and surface the residual to the user. Don't auto-pick a side.
- **Worktree cleanup**: if Step 4a chose Mode B (temporary worktree), run `git worktree remove ../codex_review_<short-sha>` after the unified report is delivered. Skip in Mode A. The `.codex_review_<short-sha>.md` / `.gemini_review_<short-sha>.md` output files and the `.final_review_prompt_<short-sha>.md` prompt file are left as audit artifacts — clean up manually if no longer needed.

### Verdict rules

- **PASS** = all sub-agents PASS, no warnings.
- **PASS WITH WARNINGS** = all sub-agents PASS or WARN, no BLOCKING.
- **FAIL** = any sub-agent reports BLOCKING.

## Re-review rounds (after dev fixes)

When the dev pushes fixes addressing prior findings, the user will re-invoke `/final-review` (or just ask "re-review with the fixes"). At that point:

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
6. **Both external CLIs re-run fresh each round.** Neither has a transcript-resume API — every invocation is independent. Re-author the shared prompt file for the updated tree, then re-fire each available external. Decide whether to re-run by what the fix touched:
   - If the fix touched `Drivers/Levoit/*.groovy`, `Drivers/Levoit/*Lib.groovy`, `tests/lint_rules/`, `tests/check_*.py`, `levoitManifest.json`, or `tools/build-bundle.py` → re-run the externals (findings may have shifted).
   - If the fix was doc-only (CHANGELOG `[Unreleased]`, README rows, BP-catalog entries) → skip the external re-run; their prior production-code findings still hold. The re-review is Claude-side only.
   - Re-running spends +1 ChatGPT-Plus message (Codex) and one Gemini paid-API run (pennies) per round.

### Cost discipline

| Scenario | Cost estimate |
|---|---|
| Full round-1 (all 6 sub-agents + Codex + Gemini) | ~200-300K Claude tokens, ~8-12 min wall, +1 ChatGPT-Plus message, +1 Gemini paid-API run (pennies) |
| Re-review with 1 sub-agent resumed (no externals) | ~40-70K tokens, ~3-5 min |
| Re-review with 3 sub-agents resumed + externals | ~100-150K tokens, ~5-8 min, +1 ChatGPT-Plus message, +1 Gemini run |
| Trivial doc-only fix re-review (no re-dispatch, no externals) | ~10-20K tokens, ~30s |

Target: 2-3 round convergence for a typical PR. A typical cycle spends ~2-3 ChatGPT-Plus messages across all Codex runs (well inside Plus's weekly headroom) plus matching Gemini paid-API runs (a few pennies total; the *free* tier's input-TPM cap, not dollar cost, is what forces the paid tier — see Step 1b/Step 6).

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

9 reviewers total: 7 Claude sub-agents (defined in `.claude/agents/`) + 2 different-family external CLIs (OpenAI Codex + Google Gemini). Pre-flight runs first as a gate; the 6 deep-audit Claude agents AND both available externals fan out in parallel per pre-flight's dispatch plan:

| Reviewer | Model / Family | Orchestration | Dispatch order | Scope |
|---|---|---|---|---|
| `vesync-driver-qa-preflight` | Claude / Haiku | Claude sub-agent | First (gate) | Lint --strict, Spock compile, manifest sanity, BP catalog grep, convention scan, path leakage, version + FORK_RELEASE_VERSION lockstep, diff triage / dispatch plan |
| `vesync-driver-qa-coverage` | Claude / Opus | Claude sub-agent | Parallel (post-preflight) | Spock test coverage (BP-pattern regression tests, family-symmetric coverage, lib-spec coverage), lint rule coverage (new BP class → new rule), documentation coverage (`Drivers/Levoit/readme.md`, CHANGELOG `[Unreleased]`, BP catalog entry), fixture coverage |
| `vesync-driver-qa-platform` | Claude / Sonnet | Claude sub-agent | Parallel | BP14 (reboot survival), BP15 (driver vs app API), BP16 (debug watchdog), BP18 (null-guard), string-literal `runIn` handler, capability/attribute/command coherence, RULE30 logger discipline, 3-signature `update()` per BP1 |
| `vesync-driver-qa-protocol` | Claude / Sonnet | Claude sub-agent | Parallel | bypassV2 envelope shape, BP4 field-name verification (snake_case vs camelCase per family), BP3 envelope peel depth, BP13 token-expiry re-auth, configModule routing, pyvesync canonical cross-reference |
| `vesync-driver-qa-adversarial` | Claude / Opus | Claude sub-agent | Parallel | Input adversaries (null/empty/unicode/MAX_INT), state adversaries (guard-bypass), concurrency adversaries (async race, re-entrance), environment adversaries (BP14/16/17/19/21/22), Rule Machine adversaries (BP18 blank slots, C3 idempotency, BP23/BP24 from-off) |
| `vesync-driver-qa-design` | Claude / Sonnet | Claude sub-agent | Parallel | Lib boundary integrity (Phase 1-5 architecture), cross-line consistency (Core/Vital/Classic/V2/Fan family), helper-extraction opportunities, intentional-asymmetry rationale, BP24 SHOULD-ON/NO-ON/SKIP-OK classification |
| `vesync-driver-qa-operator` | Claude / Sonnet | Claude sub-agent | Parallel | BREAKING flag honesty (what breaks vs what's preserved), TMI filter (no impl-detail in user-facing prose), CHANGELOG `[Unreleased]` per-commit discipline, dashboard/RM impact disclosure, log discipline + PII sanitize routing, `Drivers/Levoit/readme.md` device-row updates, cut-release invariant trips |
| Codex CLI | OpenAI / GPT family | Skill-orchestrated `Bash` call (NOT a Claude sub-agent) | Parallel | FULL independent second-opinion review (reads the git diff in its read-only sandbox). Highest-value catches are independent findings — cross-variant correctness, doc-vs-code drift, sibling-pattern incompleteness, vacuous guards, stale narration, HPM-bundle integrity. Consumes the shared prompt file (Step 4b/4c). |
| Gemini CLI | Google / Gemini family | Skill-orchestrated `Bash` call (NOT a Claude sub-agent) | Parallel | FULL independent second-opinion review from a THIRD model family — blind-spot benefit compounds with Codex (each catches BLOCKINGs the other two families miss). Runs `--yolo` inside the disposable worktree so it executes `git diff` itself (contained blast radius); needs `rg` on PATH and the **paid Gemini API tier** (free tier's input-TPM cap walls a large review). Consumes the SAME shared prompt file. |
