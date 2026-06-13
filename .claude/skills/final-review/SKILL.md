---
name: final-review
description: Final pre-PR multi-agent QA review for Hubitat-VeSync driver fork. Runs the Haiku pre-flight gate, then dispatches up to 6 specialized sub-agents in parallel (coverage, platform, protocol, adversarial, design, operator) PLUS external different-family second-opinion reviewers — the OpenAI Codex CLI and the OpenCode multi-model pack — synthesizes their findings into a unified report. (Google Gemini CLI is deferred — cost-uncompetitive on its required paid tier; see the agent-pipeline handoff doc.) Use BEFORE opening or marking a PR ready-for-review. Distinct from the cheaper pipeline-stage `vesync-driver-qa` agent. Usage: /final-review [PR# OR base..HEAD OR brief context]
context: hubitat-vesync-fork
disable-model-invocation: true
---

# VeSync Driver Final Review — Multi-Agent Fan-Out

Run the comprehensive multi-agent QA review before opening or marking a PR ready-for-review. You (main session) are the orchestrator — dispatch pre-flight first, then in a single parallel beat dispatch the 6 specialized Claude sub-agents AND fire the external second-opinion reviewers (the OpenAI Codex CLI + the OpenCode multi-model pack) as `Bash` calls, gather all reports, synthesize into a unified report.

The external reviewers are **not Claude sub-agents** — each is orchestrated via a `Bash` call from this skill in the same parallel dispatch message as the 6 `Agent({...})` calls. Different model family = different blind spots, and the benefit **compounds**: across consecutive ship gates every family (Claude lenses + Codex + each OpenCode pack member) repeatedly catches real BLOCKINGs the *others* miss — cross-variant correctness bugs, sibling-pattern gaps, stale-doc drift — that no single-family panel surfaces. All external reviewers consume the SAME shared prompt and review the FULL change independently — they are not gap-fillers; their highest-value catches are independent findings.

**OpenCode runs as a multi-model *pack*** (one family, several model siblings dispatched in one beat — see Step 4b). **Gemini is deferred** (its required paid Google AI/Vertex tier is cost-uncompetitive vs the OpenCode pack at ship-gate scale); the Gemini recipe lives in the handoff doc as documented-but-dormant, not wired here.

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

### Step 1b — External-reviewer auth/install pre-check (Codex + OpenCode pack, independent)

Before dispatching anything else, probe each external family **independently** — each has its own availability flag, and a missing/blocked one never aborts the skill (graceful degrade, per family). Verify-before-trust is permanent and per-finding for every external family, not a calibration-phase-only step.

**Codex (`codex_available`):**
```bash
codex login status 2>&1
```
- `Logged in using ChatGPT` / `Logged in using API key` → `codex_available = true`.
- `Not logged in` → `codex_available = false`. Warn once: *"Codex CLI installed but not authenticated (`codex login`). Proceeding without it."*
- Exit non-zero / `command not found` → `codex_available = false`. Warn once: *"Codex CLI not on PATH. Proceeding without it. Install: `npm install -g @openai/codex`."*
- Output indicates the ChatGPT plan's usage window is exhausted / rate-limited (substrings `usage limit`, `rate limit`, `quota`, `429`, `reset at`) → `codex_available = false`. Warn once: *"Codex authenticated but ChatGPT usage window exhausted (resets later). Proceeding without it."* Do NOT burn a probe message to confirm.

**OpenCode pack (`opencode_available`) — via the direct client; MAINTAINER-LOCAL, fail-safe:**
The pack runs through the **non-agentic direct client** `~/OneDrive/bin/opencode_go_qa_client.py` (handoff §5b-pre, mandatory 2026-06-13) — ONE HTTPS call per model, NO agent loop, NO `opencode run`. The agentic CLI is retired for review (it silently truncated at the read→write boundary and swallowed provider errors). **The client is a maintainer-local tool — it lives in OneDrive, NOT in the repo** — so this entire family is OPTIONAL and gated: a 3rd-party contributor who doesn't have it gets the Claude fan-out (+ Codex if they have it) and nothing breaks.
```bash
test -f ~/OneDrive/bin/opencode_go_qa_client.py && echo PRESENT || echo ABSENT      # client present?
uv run --python 3.12 ~/OneDrive/bin/opencode_go_qa_client.py --models >/dev/null 2>&1 && echo AUTHOK || echo AUTHFAIL   # OpenCode authed?
```
- client PRESENT AND `--models` AUTHOK → `opencode_available = true`.
- client ABSENT (the 3rd-party-contributor case) → `opencode_available = false`. Warn once: *"OpenCode direct QA client not present (maintainer-only tool, not in-repo). Proceeding without the pack — the Claude lenses (+ Codex if available) carry the review."* This is NOT an error — it's the expected path for any non-maintainer clone.
- client present but `--models` AUTHFAIL (OpenCode not authenticated — `~/.local/share/opencode/auth.json` missing) → `opencode_available = false`. Warn once: *"OpenCode client present but not authenticated. Proceeding without the pack."*
- **Per-model cap / retirement is per-call, NOT gated here.** A FREE member can hit its daily cap (clean `FreeUsageLimitError` in that model's output — the direct client returns real error bodies, no silent hang) or be retired (404 / `model not found`). Either way it surfaces cleanly at Step 6; drop just that member and keep the rest. No smoke-test probe beyond `--models`.
- **No cherry-picking at dispatch.** If the gate is YES, dispatch every standard pack member (Step 4b). Cost-aware dropping (e.g. near a monthly cap) is an explicit orchestrator decision stated to the user, never a silent partial dispatch.

**Do NOT spend a real `codex exec` probe just to test usage** — the per-message budget is the scarce resource the Codex gate protects. The free `codex login status` / client `--models` checks plus the Step 6 runtime guards are sufficient.

**Gemini: deferred, not probed.** Its required paid Google AI/Vertex tier is cost-uncompetitive vs the OpenCode pack at this scale (handoff doc §5b-ter). Don't probe or dispatch it. The recipe is preserved there as documented-but-dormant if a future re-evaluation is wanted.

When a family's flag is `false`, the rest of the skill behaves as if it did not exist — the Claude fan-out (and the other family, if available) still runs and produces the unified report; synthesis skips that family's merge logic. If both are false, it's a pure Claude-only fan-out.

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

Dispatch the reviewers marked YES. Brief the user: "Pre-flight PASS. Dispatching N of 6 Claude sub-agents — <list> — in parallel, plus external second-opinion passes: <Codex and/or OpenCode pack, per availability>." If skipping any, the user sees why.

**The `codex` matrix column gates BOTH external families** — Codex and the OpenCode pack review the same diff against the same shared prompt, so the per-diff "is an external pass worth it?" decision is shared. When that row is YES, fire Codex (if `codex_available`) AND every OpenCode pack member (if `opencode_available`). When it's NO (trivial mechanical diff), fire neither family. Pre-flight does not know whether either CLI is installed/authenticated; the skill owns that gate via the two flags.

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

External-reviewer (`codex` column) rationale: `maybe` for pure-docs (externals are strong at doc-vs-code drift), `YES` for new driver / behavior change / new library / cut-release-skill (sibling-pattern incompleteness + stale-narration + cross-variant correctness pay off), `maybe` for spec-only (vacuous-guard axis), `maybe` for lint rule change (over-zealous enforcement axis), `NO` for trivial mechanical changes (version bump / manifest-only / CI). When in doubt, lean YES — Codex is ~one ChatGPT-Plus message and the OpenCode pack is dollar-per-token against a monthly cap (each member's input is one shared prompt; a huge-input/tiny-output review is cheap), so both are cheap relative to the catch value.

### Step 4a — External-reviewer working tree + staged context (skip if both external flags false)

The two families consume context differently:

- **Codex** runs `-s read-only` (no writes) via `-C $REVIEW_CWD`, and runs `git diff` itself in its sandbox (Mode A/B below).
- **OpenCode direct client** reads any path you hand it — **NO `--dir`/`/tmp`-sandbox, NO pre-stage-to-dodge-rejection dance, NO worktree requirement** (all retired with `opencode run`). It makes ONE HTTPS POST per model and **reads the attached files itself — their bytes never enter THIS session's context, only paths do.** Context is the caller's job now: a diff-only review false-positives (e.g. a phantom "missing import" for a symbol 190 lines above the hunk), so you ATTACH the FULL changed files + convention docs, not just the diff.

**Pick `REVIEW_CWD`** (Codex's git cwd; Mode A/B — the direct client doesn't need it):
- *Mode A* — audit SHA == current HEAD and tree clean (`git status --porcelain -- '*.groovy' '*.md' 'tests/**'` empty): `REVIEW_CWD=<repo-root>`, no worktree.
- *Mode B* (cross-PR / historical SHA / dirty tree): `git worktree add ../review_<short-sha> <HEAD-SHA>`; `REVIEW_CWD=<repo-parent>/review_<short-sha>`. Remove it in Step 6.

**Stage the diff + list the changed files** (off the repo, so OneDrive doesn't churn — the direct client reads any path):
```bash
mkdir -p /c/tmp/qa
git -C $REVIEW_CWD diff <BASE_SHA>..<HEAD_SHA> > /c/tmp/qa/review_diff_<short-sha>.txt
git -C $REVIEW_CWD diff --name-only <BASE_SHA>..<HEAD_SHA>     # → the changed files to attach IN FULL (Step 4b)
```

`<short-sha>` = first 8 chars of the audit HEAD SHA. **No diff-size ceiling any more** — the old 7K-line ceiling was an agentic step-cap artifact; the direct client is bounded only by the model's ~1M context window, so a large diff is just a larger single POST (input+output must stay under ~1M tokens).

### Step 4b — Issue the parallel fan-out

**First author the shared prompt file (ONCE).** Codex AND every OpenCode pack member consume the SAME prompt — identical input makes the cross-reviewer comparison meaningful and saves authoring N prompts. Build the Step-4c prompt (with `<BASE_SHA>`/`<HEAD_SHA>`, the explicit changed-file list, and the per-diff HUNT invariants substituted) and `Write` it to `/c/tmp/qa/final_review_prompt_<short-sha>.md`. The prompt tells reviewers the diff + changed files + convention docs are **provided** to them — Codex reads them via its sandbox / `git diff`; the OpenCode client gets them as `-a` attachments (Step 4a staged the diff; attach the changed files + docs in full below).

Then dispatch the YES Claude sub-agents in a SINGLE message with multiple `Agent({...})` calls, AND in the same message fire Codex (if `codex_available`) + one background `Bash` call per OpenCode pack member (if `opencode_available`). All run concurrently. Each Claude sub-agent gets the same diff context but its scope is its specialization:

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
  command: `codex exec -s read-only -C $REVIEW_CWD - < /c/tmp/qa/final_review_prompt_<short-sha>.md > /c/tmp/qa/codex_review_<short-sha>.md 2>&1`,
  run_in_background: true, timeout: 1200000, description: 'Codex CLI second-opinion pass'
})   // only if codex_available

// External family 2 — OpenCode PACK via the DIRECT CLIENT (§5b-pre): one background Bash call PER
// MEMBER, all in this beat. Standard 5-pack (3 FREE distinct families + 2 PAID Go) ≈ $0.34/run.
// NO `opencode run` — so NO `--dir`/`< /dev/null`/`timeout 2700`/`OPENCODE_DB`/ANSI-strip; that
// entire agentic gotcha pile is retired. The client makes ONE HTTPS POST per model, reads the
// attached files ITSELF (bytes never enter THIS session — only paths do), returns clean markdown,
// and cannot side-write or step-cap-truncate. Context is the caller's job → ATTACH the convention
// docs + the FULL changed files + the staged diff (a diff-only review false-positives).
//   CLI="uv run --python 3.12 ~/OneDrive/bin/opencode_go_qa_client.py"
//   PROMPT=/c/tmp/qa/final_review_prompt_<short-sha>.md
//   ATTACH="-a bugpatterns=docs/BUG-PATTERNS.md -a contributing=CONTRIBUTING.md -a claudemd=CLAUDE.md \
//           -a diff=/c/tmp/qa/review_diff_<short-sha>.txt <one  -a label=<path>  per changed file, IN FULL>"
//   ROUTING (§5b-pre): FREE seats add `--base https://opencode.ai/zen/v1`; PAID Go seats use the
//   DEFAULT base (the /zen/go/v1 quota — $0 within plan). Bare model ids — the opencode/ or
//   opencode-go/ CLI prefix 401s the API. Outputs to /c/tmp/qa (off the repo / OneDrive).
Bash({ command: `cd $REVIEW_CWD && $CLI -m mimo-v2.5-free         --base https://opencode.ai/zen/v1 --user-file $PROMPT $ATTACH > /c/tmp/qa/oc_mimo_<short-sha>.md       2>&1`, run_in_background: true, timeout: 1500000, description: 'OpenCode — mimo-v2.5-free (FREE, Xiaomi family)' })
Bash({ command: `cd $REVIEW_CWD && $CLI -m deepseek-v4-flash-free --base https://opencode.ai/zen/v1 --user-file $PROMPT $ATTACH > /c/tmp/qa/oc_dsflashfree_<short-sha>.md 2>&1`, run_in_background: true, timeout: 1500000, description: 'OpenCode — deepseek-v4-flash-free (FREE, DeepSeek @200K)' })
Bash({ command: `cd $REVIEW_CWD && $CLI -m nemotron-3-ultra-free  --base https://opencode.ai/zen/v1 --user-file $PROMPT $ATTACH > /c/tmp/qa/oc_nemotron_<short-sha>.md   2>&1`, run_in_background: true, timeout: 1500000, description: 'OpenCode — nemotron-3-ultra-free (FREE, Nvidia family — most precise free voice)' })
Bash({ command: `cd $REVIEW_CWD && $CLI -m deepseek-v4-flash      --user-file $PROMPT $ATTACH > /c/tmp/qa/oc_dsflash_<short-sha>.md  2>&1`, run_in_background: true, timeout: 1500000, description: 'OpenCode — deepseek-v4-flash (PAID Go ~$0.033, recall/breadth)' })
Bash({ command: `cd $REVIEW_CWD && $CLI -m deepseek-v4-pro        --user-file $PROMPT $ATTACH > /c/tmp/qa/oc_dspro_<short-sha>.md    2>&1`, run_in_background: true, timeout: 1500000, description: 'OpenCode — deepseek-v4-pro (PAID Go ~$0.31, precision/judgment)' })
// All 5 only if opencode_available; dispatch ALL if the gate is YES (no cherry-pick). The 3 FREE
// distinct families (Xiaomi / DeepSeek / Nvidia) anchor signal at $0; the 2 PAID Go seats are the
// throttle-proof fallback when the free seats hit their daily cap (flash=recall, pro=judgment).
```

Each `Bash` uses `run_in_background: true` so it returns immediately; you're notified when each job exits. All outputs go to `/c/tmp/qa/` (off the repo, so they survive Mode B worktree cleanup and don't churn OneDrive). Per-family differences (do NOT assume one recipe transfers): **Codex** reads the prompt via stdin redirect (`- < file`) and runs git in its read-only sandbox. **OpenCode pack members** run through the direct client — a single HTTPS POST that reads `--user-file` + every `-a` attachment itself and writes clean markdown to stdout. None of the old `opencode run` machinery applies: no `< /dev/null` (no agentic stdin hang), no `timeout 2700` exit-wedge (the client exits cleanly; its own `--timeout`, default 1200s, is the per-call ceiling — the Bash-tool `timeout:` just bounds the wrapper), no `OPENCODE_DB` (one HTTPS call, no shared SQLite WAL to race), no `--dir`/ANSI-strip/side-write/step-cap-truncation. The client also surfaces real HTTP errors (a free-cap `FreeUsageLimitError`, a 404, an auth fail) as clean text in its output instead of hanging. Pack members are stateless (no resume); cherry-picking members at dispatch is a process bug — dispatch all if the gate is YES.

**Capture each Claude sub-agent's agent ID** from the dispatch result. Store for SendMessage on re-review rounds. The pattern:

```
agentId: <id> (use SendMessage with to: '<id>' to continue this agent)
```

Record these in your working memory for this skill invocation. Neither external CLI has a transcript-resume API — re-review rounds re-run each available external reviewer fresh (see Re-review rounds section below).

### Step 4c — Shared external-reviewer prompt (Codex + every OpenCode pack member consume it)

This is the SINGLE prompt all external reviewers read (authored to `/c/tmp/qa/final_review_prompt_<short-sha>.md` in Step 4b), and it's the SAME template the §5c pipeline-tier parallel external reuses — author it once, don't fork a second prompt. Substitute `<BASE_SHA>`/`<HEAD_SHA>` with the Step-1 refs, the explicit changed-file list, and a per-diff **HUNT** list of the load-bearing invariants THIS change must preserve. (The diff + changed files reach the reviewer as attachments / git, not a path baked into the prompt.)

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

These files are PROVIDED to you — as attachments to this request (the OpenCode direct client inlines them), or readable in the working tree if you have file access (Codex). Use them to load the fork's specific conventions, architecture, and bug-pattern catalog; they're the canonical, always-current source of truth — do not rely on training-data knowledge:

1. **`docs/BUG-PATTERNS.md`** — the canonical bug-pattern catalog (BP1–BP29: symptom, root cause, fix scope, canonical fix, lint rule, regression coverage); read this FIRST. **`CONTRIBUTING.md`** — every lint rule's purpose, the 5-driver-family layout, parent-child architecture, HPM packaging via `bundles[]`, every convention this codebase enforces.
2. **`CLAUDE.md`** — fork-specific AI-pipeline overlay; contains the architecture summary, family-line cleavage, bug-pattern conventions, and the cross-cutting / fix-scope discipline rules.
3. **`.claude/agents/vesync-driver-qa-*.md`** (coverage, platform, protocol, adversarial, design, operator + preflight) — the parallel Claude sub-agents reviewing this PR alongside you. Read these so you know what's already well-covered — a SOFT dedup hint to cut duplicate noise, NOT a scope boundary. Review the WHOLE change against the invariant set + your own judgement; your most valuable findings are the independent ones the in-house lenses didn't think to look for.
4. **`Drivers/Levoit/readme.md`** — per-driver feature/capability/attribute reference (helps you tell whether a doc row is drifted from code).
5. **`docs/oauth-flow.md`** (if it exists) — internal-debugging reference for the two-stage OAuth login flow introduced in v2.7. Worth reading if the diff touches `VeSyncIntegration.groovy` auth code.

# Your task — FULL independent review

Review the entire change. **The full git diff and the full changed files are PROVIDED to you** — as attachments to this request (the `diff=` attachment is the unified diff; each changed file is attached in full so you can verify claims in context), or — if you have a git-capable sandbox (Codex) — runnable via `git diff <BASE_SHA>..<HEAD_SHA>`. The changed files are: `<explicit changed-file list>`. Output your findings INLINE as your response — this is a one-shot review. Exclude generated/vendored paths and the dependency dir.

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

**Keep the authored prompt file framing-free — it MUST open cold with the `# Your role` heading.** When you `Write` the prompt to `.final_review_prompt_<short-sha>.md` (Step 4b), do NOT prepend any meta-framing — no "this is the shared prompt template", no "dispatch contract", no orchestrator notes, not even wrapped in `<!-- -->` (HTML comments are still passed to the model as input text; they don't strip). Strong-RLHF models read framing-about-the-task as a document *describing* a task and pivot to clarifying-question mode instead of executing the review — empirically isolated across three model families (two returned only a meta-question / A-B-C menu until the framing was stripped, then produced full substantive reviews from the identical body). Orchestrator-facing notes (template purpose, placeholder list) live HERE in the SKILL, never in the reviewer-facing file.

### Step 5 — Run sub-agents in background optional

If the PR is large and the user is doing other work, dispatch each sub-agent with `run_in_background: true`. Otherwise foreground is fine (parallel dispatch finishes when the slowest sub-agent finishes — typically 6-12 min for full fan-out on a typical driver PR).

### Step 6 — Gather reports + synthesize

When all dispatched Claude sub-agents return AND each dispatched external background job notifies completion, read each available external output into your context: `/c/tmp/qa/codex_review_<short-sha>.md` and each `/c/tmp/qa/oc_<model>_<short-sha>.md`. Skip the merge logic for any family/member not dispatched.

**Pull the verdict out.** Codex's log is noisy (spinner/banner); the OpenCode direct-client outputs are CLEAN markdown (no ANSI, no TUI frames — read them whole or grep the severity blocks):
```bash
# Codex: content follows the final `codex` banner line
awk '/^codex$/{f=1} f' /c/tmp/qa/codex_review_<short-sha>.md | tail -40
# OpenCode (per member): clean markdown — just read it, or grep the severity/verdict blocks
grep -E "BLOCKING|WARNING|NIT|Verdict|shippable" /c/tmp/qa/oc_<model>_<short-sha>.md | tail -40
```

**OpenCode per-member failure modes (drop the member, keep the pack).** The direct client surfaces real HTTP error bodies as clean text — no silent hang, no side-write, no truncation to diagnose:
- **`FreeUsageLimitError` in the output** → that FREE member hit its daily cap (resets in hours). Drop it: "pack: N/5, `<model>` skipped — free-cap". The 2 PAID Go seats draw a separate quota and are unaffected.
- **`401` / auth error** → OpenCode not authenticated for that route (should've been caught by the Step-1b `--models` gate; if it slips through, drop the member).
- **`404` / `model not found`** → that model was retired. Drop it and note it; do NOT silently swap in an unvetted model.
- **Per-member cost variance is wide** — within the PAID tier, `deepseek-v4-pro` (~$0.31) can spend ~10× `deepseek-v4-flash` (~$0.033) on the same prompt. Watch $/run per member; the cheap flash often matches the pro seat on $/real-finding (pro earns its seat on the judgment/design-altitude angle, not raw count).

**Brief throttle (wait) vs structural rate cap (needs the paid seats) — by the reset interval in the error text.** "retry after *N **seconds***" auto-recovers. "reset after *N **hours***" / `FreeUsageLimitError` is the structural daily free cap → waiting and model-switching across FREE seats won't fix it (they share one IP-day bucket); fall back to the 2 PAID Go seats for the round.

**Drop an external family/member only on a sustained outage (or structural cap), and state the drop EXPLICITLY in synthesis, never silently** (a drop is itself availability/cost data). Absent at dispatch → proceed without it; briefly throttled → wait within the window; structurally capped or per-member auth-fail → note it (e.g. "Codex: unavailable (usage/rate-limit)" / "pack: 4/5, `<model>` skipped — auth") and synthesize with the rest. The Claude fan-out is the authoritative result; externals are always additive.

**Stale-narration findings get EXTRA verification — open the file and read the line.** Verify-before-trust is permanent and per-finding for every external family. The hallucination risk is *asymmetric*: external models that score well on doc-vs-code-drift catches also confidently fabricate what a README/docstring/TODO "should" say from training-data priors. If an external BLOCKING is "doc line N is stale / says X", `sed -n 'Np' <file>` and read it BEFORE forwarding to dev — a single false claim about file content costs a wasted fix round (dev dutifully "fixes" unbroken text). Code findings hallucinate far less; doc/narration findings far more.

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
- codex / opencode-pack: external CLIs — re-run fresh each round (no transcript resume; see Re-review rounds)
```

**Synthesis rules**:

- **Deduplicate**: if two reviewers flag the same file:line, merge into one finding noting both perspectives. Agreement across families (a Claude lens + Codex + one or more OpenCode pack members on the same finding) strengthens severity — note all that flagged it.
- **Severity merge**: if any reviewer flags BLOCKING, the unified finding is BLOCKING. Use the strongest reasoning.
- **Cross-cutting findings**: some issues span multiple reviewer scopes (a new SHOULD-ON method with no auto-on guard AND missing from-off Spock spec AND no CHANGELOG bullet). Merge into a single finding with multi-scope attribution.
- **Preserve reviewer voice**: cite the original reports verbatim in the appendix when clear. Don't paraphrase if it loses precision.
- **External-only findings**: if an external reviewer caught something no Claude lens flagged, label it `[codex-only]` / `[opencode-<model>-only]` in the unified report. This is exactly the second-opinion value the integration exists to capture — surface it prominently, not buried. **Verify each external finding against the actual code before acting on it** (permanent, per-finding, every family — a hallucinated finding caught late costs a wasted fix round; doc/narration claims especially, per the stale-narration guard above).
- **Disagreement — reviewer-vs-reviewer too, not just external-vs-Claude**: if any two reviewers contradict on the same finding (Claude-vs-external, OR two externals **split** — including two siblings *inside* the OpenCode pack calling it BLOCKING vs WARN — they reason independently and will sometimes disagree), present both perspectives, adjudicate against the actual code first, and surface the residual to the user. Don't auto-pick a side.
- **Worktree cleanup**: if Step 4a chose Mode B (temporary worktree), run `git worktree remove ../review_<short-sha>` after the unified report is delivered. Skip in Mode A. The `/c/tmp/qa/` scratch (each `oc_<model>_<short-sha>.md` + `codex_review_<short-sha>.md`, the `review_diff_<short-sha>.txt`, the `final_review_prompt_<short-sha>.md`) lives off the repo, so it never dirties the tree — clean up `/c/tmp/qa/*_<short-sha>.*` when no longer needed.

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
6. **All externals re-run fresh each round.** None has a transcript-resume API — every invocation is independent. Re-stage the diff + re-author the shared prompt for the updated tree, then re-fire Codex + each OpenCode pack member. Decide whether to re-run by what the fix touched:
   - If the fix touched `Drivers/Levoit/*.groovy`, `Drivers/Levoit/*Lib.groovy`, `tests/lint_rules/`, `tests/check_*.py`, `levoitManifest.json`, or `tools/build-bundle.py` → re-run the externals (findings may have shifted).
   - If the fix was doc-only (CHANGELOG `[Unreleased]`, README rows, BP-catalog entries) → skip the external re-run; their prior production-code findings still hold. The re-review is Claude-side only.
   - Re-running spends +1 ChatGPT-Plus message (Codex) and one OpenCode 5-pack run (~$0.34; 3 FREE seats + 2 paid Go) per round.

### Cost discipline

| Scenario | Cost estimate |
|---|---|
| Full round-1 (6 sub-agents + Codex + OpenCode 5-pack via direct client) | ~200-300K Claude tokens, ~8-12 min wall, +1 ChatGPT-Plus message, +1 OpenCode 5-pack run (~$0.34 = $0+$0+$0 FREE seats + $0.033+$0.31 paid Go); the attached-file bytes hit the model quota, NOT this session's context |
| Re-review with 1 sub-agent resumed (no externals) | ~40-70K tokens, ~3-5 min |
| Re-review with 3 sub-agents resumed + externals | ~100-150K tokens, ~5-8 min, +1 ChatGPT-Plus message, +1 OpenCode pack run |
| Trivial doc-only fix re-review (no re-dispatch, no externals) | ~10-20K tokens, ~30s |

Target: 2-3 round convergence for a typical PR. A typical cycle spends ~2-3 ChatGPT-Plus messages across Codex runs (inside Plus's weekly headroom) plus matching OpenCode 5-pack runs (PAID Go seats draw the Go subscription quota — $0 within plan; watch $/run per member, the pro seat can be ~10× the flash seat — §5b-bis).

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

7 Claude sub-agents (defined in `.claude/agents/`) + 2 external families (OpenAI Codex + the OpenCode multi-model pack, the pack itself = N model siblings). Pre-flight runs first as a gate; the 6 deep-audit Claude agents AND every available external fan out in parallel per pre-flight's dispatch plan. (Gemini is deferred — see the header note + handoff doc §5b-ter.)

| Reviewer | Model / Family | Orchestration | Dispatch order | Scope |
|---|---|---|---|---|
| `vesync-driver-qa-preflight` | Claude / Haiku | Claude sub-agent | First (gate) | Lint --strict, Spock compile, manifest sanity, BP catalog grep, convention scan, path leakage, version + FORK_RELEASE_VERSION lockstep, diff triage / dispatch plan |
| `vesync-driver-qa-coverage` | Claude / Opus | Claude sub-agent | Parallel (post-preflight) | Spock test coverage (BP-pattern regression tests, family-symmetric coverage, lib-spec coverage), lint rule coverage (new BP class → new rule), documentation coverage (`Drivers/Levoit/readme.md`, CHANGELOG `[Unreleased]`, BP catalog entry), fixture coverage |
| `vesync-driver-qa-platform` | Claude / Sonnet | Claude sub-agent | Parallel | BP14 (reboot survival), BP15 (driver vs app API), BP16 (debug watchdog), BP18 (null-guard), string-literal `runIn` handler, capability/attribute/command coherence, RULE30 logger discipline, 3-signature `update()` per BP1 |
| `vesync-driver-qa-protocol` | Claude / Sonnet | Claude sub-agent | Parallel | bypassV2 envelope shape, BP4 field-name verification (snake_case vs camelCase per family), BP3 envelope peel depth, BP13 token-expiry re-auth, configModule routing, pyvesync canonical cross-reference |
| `vesync-driver-qa-adversarial` | Claude / Opus | Claude sub-agent | Parallel | Input adversaries (null/empty/unicode/MAX_INT), state adversaries (guard-bypass), concurrency adversaries (async race, re-entrance), environment adversaries (BP14/16/17/19/21/22), Rule Machine adversaries (BP18 blank slots, C3 idempotency, BP23/BP24 from-off) |
| `vesync-driver-qa-design` | Claude / Sonnet | Claude sub-agent | Parallel | Lib boundary integrity (Phase 1-5 architecture), cross-line consistency (Core/Vital/Classic/V2/Fan family), helper-extraction opportunities, intentional-asymmetry rationale, BP24 SHOULD-ON/NO-ON/SKIP-OK classification |
| `vesync-driver-qa-operator` | Claude / Sonnet | Claude sub-agent | Parallel | BREAKING flag honesty (what breaks vs what's preserved), TMI filter (no impl-detail in user-facing prose), CHANGELOG `[Unreleased]` per-commit discipline, dashboard/RM impact disclosure, log discipline + PII sanitize routing, `Drivers/Levoit/readme.md` device-row updates, cut-release invariant trips |
| Codex CLI | OpenAI / GPT family | Skill-orchestrated `Bash` call (NOT a Claude sub-agent) | Parallel | FULL independent second-opinion review (runs git in its read-only sandbox). Highest-value catches are independent findings — cross-variant correctness, doc-vs-code drift, sibling-pattern incompleteness, vacuous guards, stale narration, HPM-bundle integrity. Consumes the shared prompt (Step 4b/4c). |
| OpenCode pack (standard 5, via direct client) | 5 siblings via `~/OneDrive/bin/opencode_go_qa_client.py` (bare model ids): `mimo-v2.5-free` (FREE, Xiaomi) + `deepseek-v4-flash-free` (FREE, DeepSeek @200K) + `nemotron-3-ultra-free` (FREE, Nvidia) + `deepseek-v4-flash` (PAID Go ~$0.033, recall) + `deepseek-v4-pro` (PAID Go ~$0.31, judgment) ≈ **$0.34/run** | Skill-orchestrated `Bash` call PER MEMBER (NOT Claude sub-agents; **MAINTAINER-LOCAL client — absent for 3rd parties → whole family gated off, fail-safe**) | Parallel | FULL independent second-opinion review from several distinct families at once — blind-spot benefit compounds. 3 FREE distinct families (Xiaomi/DeepSeek/Nvidia) anchor signal at $0; 2 PAID Go seats are the throttle-proof fallback. ONE HTTPS POST per model (NO `opencode run` → no `--dir`/`< /dev/null`/`timeout`/`OPENCODE_DB`/ANSI/side-write/step-cap); reads the attached diff + full changed files + convention docs itself (bytes never enter this session). FREE seats route `--base …/zen/v1`, PAID Go use the default `/zen/go/v1` quota. Dispatch ALL 5 if the gate is YES (no cherry-pick); per-member free-cap/retire varies — drop a member, keep the pack (Step 6). Consumes the SAME shared prompt. |
| ~~Gemini CLI~~ | Google / Gemini family | — | **DEFERRED** | Not in the active fan-out — its required paid Google AI/Vertex tier is cost-uncompetitive vs the OpenCode pack at ship-gate scale. Recipe preserved (documented-but-dormant) in handoff doc §5b-ter; re-evaluate if pricing/usage changes. |
