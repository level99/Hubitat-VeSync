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
   │ (Opus)           │   returns APPROVE or ISSUES (BLOCKING/NIT)
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

3. **Always try resume first; fall back to fresh Agent only on failure.** For every handoff after the very first dispatch of a role, the protocol is:
   - **Capture the agent ID** from each `Agent({...})` dispatch result (the response includes `agentId: <id>` — e.g., `a745afcdc2e7112d6`)
   - `SendMessage({to: '<agentId>', message: "..."})` — **addresses by agent ID, NOT by the descriptive `name:` parameter**. Per [code.claude.com/docs/en/sub-agents#resume-subagents](https://code.claude.com/docs/en/sub-agents#resume-subagents): *"When a subagent completes, Claude receives its agent ID. Claude uses the SendMessage tool with the agent's ID as the `to` field to resume it."*
   - If the recipient's prior run has already exited (subagents terminate after returning their final result; the runtime evicts the addressable session after some window — no fixed TTL exposed to the orchestrator), the message lands in a dead inbox and `SendMessage` returns `success: false`
   - **Only then** dispatch a fresh `Agent({name: ..., ...})` with full re-briefing context

   **Note on the name parameter:** the `name:` field in `Agent({name: 'dev', ...})` is a human-readable label for the dispatch (visible in transcripts/UI), but it does NOT make the agent addressable by that name in `SendMessage`. The local in-environment SendMessage tool description may say *"refer to teammates by name, never by UUID"* — that's correct for **agent teams** (TeamCreate-style coordination) but **not** for single-session subagent resume. For subagent resume, ALWAYS use the agent ID.

   **Detection heuristic:** if you sent a SendMessage and want to know whether the agent is alive, wait for the completion notification. If `SendMessage` itself returns `success: false`, the session is gone — fresh dispatch immediately. If `SendMessage` succeeded but no completion notification arrives in a reasonable window (~60s for trivial follow-ups, ~3min for code edits), assume the agent exited and re-dispatch fresh.

   **Re-dispatch must re-supply context:** a fresh agent has no memory of prior rounds. The re-briefing prompt MUST include: the file paths, the task recap, all prior QA/tester findings the agent needs, and the specific delta being asked for. Copy-paste from the SendMessage body you were about to send, plus enough preamble to orient cold.

   Why this matters: resume preserves ~60–70% input tokens via warm cache. Fresh dispatches reload everything. Never default to fresh just because it's easier to compose. Using the **wrong** addressing (name instead of ID) consistently fails — every name-based send returns `"No agent named '...' is currently addressable"` and forces a fresh dispatch, defeating the cache-preservation purpose entirely.

4. **Brief human-readable summary between rounds — but don't block on user response.** When QA or tester returns findings, output a concise summary to the main chat BEFORE forwarding to the developer. Then proceed directly to the SendMessage handoff. The user reads the summary and can interrupt if they want to redirect; otherwise the pipeline keeps flowing. Example:

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

---

## Two deployment contexts

### A. With Hubitat MCP server (maintainer setup)

After QA approval, dispatch the **vesync-driver-operations** agent. It handles the deploy + verify cycle and returns a structured PASS/FAIL/UNCERTAIN report. Pass it the source file path, driver ID (look up via `mcp__hubitat__manage_apps_drivers list_hub_drivers`), affected device IDs, and a test plan.

You can also do the deploy yourself if the change is trivial:
1. Upload source: `curl -F "uploadFile=@<file>" -F "folder=/" "http://<hub-IP>/hub/fileManager/upload"`
2. Deploy: `update_driver_code` MCP tool with `driverId`, `sourceFile`, `confirm: true`
3. Verify: trigger `refresh` on the affected device, inspect `applyStatus raw r ...` log lines, confirm attributes populated.

Use the operations agent when: you want structured PASS/FAIL evidence in the work item, the test plan is non-trivial, or you want to keep the main session's context lean (operations is Haiku, much cheaper than Opus on log-heavy verification).

### B. Without Hubitat MCP (typical contributor)

The pipeline still works — you just stop at the local-file-edit step. Push your changes and let the maintainer (or yourself, manually) deploy:
1. Run developer + QA pipeline as above.
2. Once QA approves, commit changes.
3. To test locally:
   - In Hubitat UI: **Drivers Code** → find the driver → **Edit** → paste new content → **Save**.
   - Open the affected child device's page → click **Refresh**.
   - Check **Logs** for `applyStatus raw r ...` lines.
4. Open a PR with the diff + test plan + manual verification notes.

You don't have to deploy to merge — code review + spec-conformance via the dev/QA pipeline is sufficient gate. The maintainer will deploy on the live hub before/during merge.

---

## Logging conventions (enforce in every change)

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

These patterns are load-bearing. Don't break them in a refactor without explicit reason.

- The **diagnostic raw-response line** in every child's `applyStatus`: `if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=...) keys=..., values=..."` — shows the parsed device data after envelope peel.
- The **VeSync API trace logging** wrapper closure in parent's `sendBypassRequest` — gives every API call a 1-line summary at debug level (method name + HTTP status + inner code) and a full request/response body dump at verboseDebug level. The summary is the primary debugging signal for "did this command actually go through?"
- The **sanitize() helper** in parent — auto-redacts auth-sensitive values from any log line. Direct `log.X` calls in the parent bypass it; always route through the helpers.
- The **envelope-peel while loop** in every V2-line child's `applyStatus`:
  ```groovy
  while (r instanceof Map && r.containsKey('code') && r.containsKey('result') && r.result instanceof Map && peelGuard < 4) {
      r = r.result; peelGuard++
  }
  ```
- The **3-signature update pattern** in every child:
  ```groovy
  def update()                       // self-fetch
  def update(status)                 // 1-arg parent callback
  def update(status, nightLight)     // 2-arg parent callback (REQUIRED)
  ```

---

## GitHub workflow (fork conventions)

This fork has two git remotes:

- `origin` → `https://github.com/level99/Hubitat-VeSync.git` (the fork — where PRs target)
- `upstream` → `https://github.com/NiklasGustafsson/Hubitat.git` (Niklas's original — read-only, never PR here)

The `gh` CLI does **not** infer the right repo from these remotes. By default it walks the remotes, finds `upstream`, and uses that as `--repo` for every PR/issue/check command. This causes `gh pr create` to fail with the cryptic error:

```
GraphQL: Head sha can't be blank, Base sha can't be blank,
No commits between main and release/v2.1, Head ref must be a branch,
Base ref must be a branch (createPullRequest)
```

— because it's trying to open a PR in `NiklasGustafsson/Hubitat` (which has no `release/v2.1` branch and a different `master` default).

**Fix this once per clone** with:

```bash
gh repo set-default level99/Hubitat-VeSync
```

This writes `gh-resolved` to `.git/config` so all future `gh` commands target the fork. Verify with `gh repo view --json nameWithOwner` — should print `"nameWithOwner":"level99/Hubitat-VeSync"`.

**If you can't / don't want to set the default**, every `gh` command needs `--repo level99/Hubitat-VeSync` explicitly. That includes:

- `gh pr create --repo level99/Hubitat-VeSync ...`
- `gh pr view N --repo level99/Hubitat-VeSync`
- `gh pr checks N --repo level99/Hubitat-VeSync --watch`
- `gh pr comment N --repo level99/Hubitat-VeSync ...`
- `gh pr review N --repo level99/Hubitat-VeSync ...`
- `gh issue create --repo level99/Hubitat-VeSync ...`

Forgetting `--repo` is the most common preventable failure when working with this clone. If `gh repo view` shows `nameWithOwner: NiklasGustafsson/...`, run `gh repo set-default level99/Hubitat-VeSync` BEFORE any other `gh` work.

---

## Cost optimization notes

The 4-agent pipeline (developer Sonnet / qa Opus / tester Haiku / operations Haiku) is shaped around model-cost asymmetry:

- **Resume pattern (SendMessage, not fresh Agent)** preserves ~60–70% of input tokens on iterative rounds because each agent's cache stays warm on the driver source file + prior findings. Fresh dispatches reload everything.
- **Sonnet for dev** is ~5× cheaper per token than Opus on underlying pricing; on Claude Max plan, consumes cap at roughly ⅕ the rate of Opus.
- **Haiku for tester** is ~19× cheaper than Opus; test-output-dominated work (Gradle logs, JUnit XML, Spock failure traces) is where the savings stack up most.
- **Haiku for operations** — same ratio; log-fetch + structured tool calls are mechanical work.
- **QA reads diffs** (small payloads), not whole files, on review rounds after the first.
- **Test/log output containment via tester + ops** keeps main + dev + QA contexts lean — they never see raw 100KB Gradle output or 60KB log dumps, only ~1KB structured summaries with verbatim failure quotes.
- **Total**: iterative driver work through this pipeline runs at roughly **30–40% of the cost of doing everything in the main Opus session**.

This is why the "always try resume first; fall back to fresh only on failure" rule is load-bearing for cost discipline, not just convenience. Default to fresh and the pipeline's cost-optimization promise evaporates.

---

## When this file is wrong

If reality and this file diverge (driver structure changes, new pattern emerges, bug-catalog grows), update this file in the same PR. The agents read CLAUDE.md as part of their context priming; outdated CLAUDE.md misleads future work.
