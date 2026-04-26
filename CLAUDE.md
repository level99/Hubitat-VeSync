# CLAUDE.md — contributor workflow for Hubitat-VeSync

This file tells Claude Code (and any AI-assisted contributor session) how to work in this codebase. It establishes the development pipeline, the agents that enforce it, and the canonical references to consult before changing code.

If you're starting a fresh Claude Code session in this repo, this file IS your context. Read it first.

---

## Codebase summary

This is a community-maintained Hubitat Elevation driver pack for **Levoit air purifiers and humidifiers**, communicating with the **VeSync cloud API**. It's a fork of [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat) with added support for the Vital and Superior product lines, plus assorted parent-driver fixes.

### Layout
```
Hubitat-VeSync/
├── Drivers/Levoit/
│   ├── VeSyncIntegration.groovy          ← parent driver (auth, polling, child management)
│   ├── LevoitCore200S.groovy             ← Core 200S (older API conventions)
│   ├── LevoitCore200S Light.groovy       ← Core 200S night-light child
│   ├── LevoitCore300S.groovy
│   ├── LevoitCore400S.groovy
│   ├── LevoitCore600S.groovy
│   ├── LevoitVital200S.groovy            ← Vital 200S (V2 API conventions)
│   ├── LevoitSuperior6000S.groovy        ← Superior 6000S humidifier (V2, double-wrapped responses)
│   ├── Notification Tile.groovy
│   └── readme.md                          ← user-facing docs
├── levoitManifest.json                    ← HPM manifest
├── README.md                              ← top-level repo readme
├── CLAUDE.md                              ← THIS FILE — contributor workflow
└── .claude/agents/
    ├── vesync-driver-developer.md         ← writer agent (Sonnet)
    ├── vesync-driver-qa.md                ← reviewer agent (Opus)
    └── vesync-driver-operations.md        ← deploy + verify agent (Haiku, requires Hubitat MCP)
```

### Architecture in one paragraph

A single **parent driver** (`VeSyncIntegration.groovy`) holds the user's VeSync account credentials, logs in, discovers devices, schedules periodic polling, and routes API calls. **Child drivers** are per-model (one for Core 200S, one for Vital 200S, etc.). Each child exposes Hubitat capabilities (Switch, FanControl, etc.) and parses status responses. The parent calls `child.update(status, nightLight)` on every poll; children also self-fetch when the user hits Refresh. All API traffic uses VeSync's `/cloud/v2/deviceManaged/bypassV2` endpoint with model-specific method names + payloads.

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
       │     │ With MCP:        │
       │     │ Agent({          │
       │     │  subagent_type:  │
       │     │  'vesync-driver- │
       │     │  operations',    │
       │     │  name: 'ops'})   │
       │     │                  │
       │     │ Without MCP:     │
       │     │ commit + return  │
       │     │ manual deploy    │
       │     │ steps to user    │
       │     └──────────────────┘
       │
       │ 5. Main relays issues briefly:
       │    "QA found N issues:
       │     - BLOCKING: [...]
       │     - NIT: [...]"
       │
       │ 6. SendMessage({to: 'dev', message: 'QA feedback: ...'})
       ▼
   ┌──────────────────┐
   │ vesync-driver-   │ ← RESUMED — keeps cache of prior context.
   │ developer        │   Don't dispatch fresh; that wastes cache.
   └─────────┬────────┘
             │ 7. Updated diff → return to step 3
             ▼
       (loop until APPROVE)
```

### Rules

1. **Always dispatch the developer first**, never edit driver code directly from the main session. The agent has the bug-pattern catalog and canonical-payload references in its context; you don't.

2. **Always run QA before deploy or commit.** Even for "trivial" changes — many bugs in this codebase were "trivial" until they shipped.

3. **Resume via SendMessage, don't re-dispatch fresh.** Subagent cache contains the driver source, the diff in flight, and the prior round's context. A fresh dispatch reloads everything (~60–70% input token waste).

4. **The developer doesn't deploy on its own.** It returns a diff summary. Deploy/commit happens from the main session.

5. **The QA doesn't write code.** It returns suggestions; the developer applies them.

6. **Iteration cap: 3 rounds.** If QA flags BLOCKING three rounds in a row, escalate to human. Usually means the spec is wrong, not the code.

7. **Honest pushback on disagreements.** If the developer thinks QA's feedback would cause a regression, surface the disagreement to the human user; don't rubber-stamp.

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

## Canonical references

When the developer or QA needs to validate a payload or response shape, these are the sources of truth (in priority order):

1. **pyvesync test fixtures** — `src/tests/api/vesyncpurifier/<MODEL>.yaml` and `src/tests/api/vesynchumidifier/<MODEL>.yaml` in [webdjoe/pyvesync](https://github.com/webdjoe/pyvesync). These are real device captures.

2. **pyvesync class implementations** — `src/pyvesync/devices/vesyncpurifier.py` and `vesynchumidifier.py`. Show response-field interpretation, reverse-mappings (e.g. `"autoPro"` → `"auto"`), and threshold logic.

3. **pyvesync device registry** — `src/pyvesync/device_map.py`. Maps model codes (`LAP-V201S-WUS`, `LEH-S601S-WUSR`, etc.) to device classes. Shows which model codes share a class.

4. **Live-captured response from a real device** — when in doubt, the diagnostic raw-response log line on a child driver shows exactly what the API returned for the user's specific firmware. Pyvesync may lag the live API.

5. **Hubitat Capability reference** — https://docs.hubitat.com/index.php?title=Driver_Capability_List. For deciding which capabilities to declare and what methods/attributes they imply.

6. **Hubitat developer docs** — https://docs.hubitat.com/index.php?title=Developer_Documentation. For sandbox restrictions, async patterns, etc.

The developer agent has a primer on all of these in its agent definition. It will pull from web sources as needed via WebFetch.

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

Auto-disable: every driver's `updated()` includes `if (settings?.debugOutput) runIn(1800, logDebugOff)` to flip debug off after 30 minutes.

INFO logs go at state-change points only (use `state.lastFoo` comparison gates) — not on every poll cycle.

---

## Bug-pattern catalog

The QA agent's definition contains a numbered catalog of 11 bug patterns from the v2.0 community-fork debugging. Reference them by number when flagging issues:

1. Missing 2-arg `update(status, nightLight)` signature on a child
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

When the developer or QA recognizes one of these patterns in a diff, name it explicitly: *"Bug Pattern #1 — missing 2-arg signature."* The other agent recognizes the name and applies the canonical fix.

---

## Adding a new device

**Pyvesync's test fixtures are ground truth for VeSync API behavior.** Always start there. Don't trust Homebridge plugins or random GitHub forks — pyvesync is the de facto community reference and is what Home Assistant's VeSync integration uses internally. Real-device captures by their maintainers feed the fixtures.

1. Confirm pyvesync supports it: check `src/pyvesync/device_map.py` for a class + dev_types entry covering your model code (e.g. `LAP-V102S-WUS`).
2. Pull the YAML fixture for canonical payloads: `src/tests/api/vesyncpurifier/<MODEL>.yaml` or `vesynchumidifier/<MODEL>.yaml`.
3. Pull the pyvesync class to understand response field semantics + reverse-mappings.
4. Copy the closest existing driver as a template:
   - Vital line / V2 API → use `LevoitVital200S.groovy` as template
   - Newer humidifier (LEH-...) → use `LevoitSuperior6000S.groovy`
   - Core line (older API conventions) → use `LevoitCore400S.groovy`
5. Replace metadata, methods, field parsing.
6. Update parent's `deviceType()` switch + `getDevices()` `addChildDevice` branch to recognize the new model code.
7. Update `levoitManifest.json` (new entry with fresh UUID + version bump + dateReleased + releaseNotes).
8. Update `Drivers/Levoit/readme.md` (driver list table + per-device events table).
9. Run dev + QA pipeline.
10. Test live, commit, push, open PR (or merge if you're the maintainer).

Step 4-5 should always be done by `vesync-driver-developer`. Don't write driver code directly from the main session.

---

## Common contributor tasks → which agent

| Task | First step |
|---|---|
| User reports "device discovered but no data" | Dispatch QA on the affected driver to identify which bug pattern applies. Often #1 or #3. |
| VeSync changed a field name | Capture verbose API response → dispatch developer with the new field map. |
| Add support for new Vital/Superior/Core variant | Follow "Adding a new device" above. |
| Logging spam in user reports | Dispatch developer to gate noisy logs behind `descriptionTextEnable`/`debugOutput` per conventions. |
| HPM users report "driver not found" | Verify `levoitManifest.json` has the entry with correct `location` URL pointing to the fork's `main` branch. |
| Migrate existing-deployed driver to new name | DON'T (Bug Pattern #9). Add the new name as a separate driver file and document migration in readme. |

---

## Source references in this codebase

- The **VeSync API trace logging** wrapper closure in parent's `sendBypassRequest` — gives every call a 1-line summary at debug level, full body dump at verboseDebug level.
- The **diagnostic raw-response line** in every child's `applyStatus`: `if (settings?.debugOutput) log.debug "applyStatus raw r (after peel=...) keys=..., values=..."` — shows the parsed device data after envelope peel.
- The **sanitize() helper** in parent — auto-redacts auth-sensitive values from any log line.
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

These patterns are load-bearing. Don't break them in a refactor without explicit reason.

---

## When this file is wrong

If reality and this file diverge (driver structure changes, new pattern emerges, bug-catalog grows), update this file in the same PR. The agents read CLAUDE.md as part of their context priming; outdated CLAUDE.md misleads future work.
