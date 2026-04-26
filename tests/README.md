# tests/ — Static Linter and Spock Unit Tests

This directory contains two complementary quality gates for the driver pack:

1. **Static linter** (`lint.py`) — fast structural checks run before any code execution
2. **Spock unit tests** (run via `./gradlew test`) — behavioral tests of driver parsing and command dispatch

---

## Static linter

The linter performs regex-based structural analysis of Groovy driver source, JSON manifest files, and Markdown documentation. It catches a specific catalog of structural anti-patterns without executing any code. Running takes roughly 5 seconds on the full codebase.

### What it checks

The linter checks are grouped into categories that map directly to the codebase's documented bug-pattern catalog and hygiene rules:

**Bug pattern checks (structural subset of the catalog):**
- Missing 2-arg `update(status, nightLight)` signature on child drivers — causes silent poll failures
- Hardcoded `getPurifierStatus` without a humidifier branch in the parent driver
- Missing response-envelope peel loop in V2-line drivers (Vital, Superior lines)
- Non-canonical field names in `setLevel` payloads (`switchIdx`/`type` vs `levelIdx`/`levelType`)
- Manual mode set via `setPurifierMode` on V2 purifiers (always fails; must use `setLevel`)
- `device.currentValue()` called immediately after `sendEvent()` in `applyStatus()` (race condition)
- Driver metadata `name` field changed from its frozen value (orphans existing user installs)
- SmartThings-era metadata artifacts (`iconUrl`, `category: "My Apps"`)
- `documentationLink` pointing to a project other than this fork's repository
- Missing `state.prefsSeeded` pref-seed block at the correct driver insertion point

**Logging discipline checks:**
- Direct `log.X` calls in the parent driver that bypass the `sanitize()` credential-redaction helper
- Bare preference references (`if (debugOutput)`) without the required `settings?.` prefix
- Missing 30-minute auto-disable wiring (`runIn(1800, "logDebugOff")` + `logDebugOff()` method)

**PII and credential checks (all file types):**
- Hub-LAN IP literals (10.x.x.x, 192.168.x.x, 172.16-31.x.x)
- Email addresses
- MAC addresses
- Personal domain references (configurable block list in `lint_config.yaml`)
- Token-shaped strings near `token` / `accountID` identifiers
- Hardcoded password literals

**Sandbox safety:**
- Forbidden imports (`java.io.*`, `java.lang.Runtime`, `java.lang.ProcessBuilder`, `groovy.lang.GroovyShell`, `org.codehaus.groovy.control.*`)
- Reflection patterns (`Eval.me()`, `evaluate()`, `Class.forName()`)

**Consistency checks:**
- `levoitManifest.json` cross-referenced against `Drivers/Levoit/` (every driver file has an entry, every entry points to a real file, UUIDs unique)
- `Drivers/Levoit/readme.md` table includes every driver file

**Re-entrance guard discipline (WARN only):**
- `state.X = true` guard sets without a corresponding `state.remove('X')` in a `finally` block

### What it does NOT replace

- The **Spock harness** tests behavioral correctness — does `applyStatus` parse a fixture correctly, does `setSpeed` send the right payload. Static analysis cannot verify runtime behavior.
- The **QA agent** (`vesync-driver-qa`) performs semantic review — logic correctness, API contract compliance, code-reading-in-context. The linter only checks structure.
- **Live deployment verification** still requires the operations agent or manual hub testing.

The linter is the cheapest gate. It runs first. If it fails, fix the structural issue before running Spock or requesting QA review.

### Running the linter

Install `uv` first if not already installed:
```
https://docs.astral.sh/uv/getting-started/installation/
```

Then from the repo root:

```bash
# Default scan (Drivers/, levoitManifest.json, README.md, CLAUDE.md, TODO.md, .claude/, tests/)
uv run --python 3.12 tests/lint.py

# Scan specific paths only
uv run --python 3.12 tests/lint.py --paths Drivers/Levoit/LevoitVital200S.groovy

# Strict mode: treat WARNs as FAILs (used in CI)
uv run --python 3.12 tests/lint.py --strict

# Machine-readable JSON output (for CI parsing)
uv run --python 3.12 tests/lint.py --json
```

Exit codes:
- `0` — PASS (no FAILs; WARNs allowed in non-strict mode)
- `1` — FAIL (one or more FAILs, or any finding in strict mode)
- `2` — WARN-only (no FAILs but warnings present; non-strict mode only)

### Configuration

`tests/lint_config.yaml` controls:
- **`frozen_driver_names`**: maps each driver filename to its expected metadata `name` value. This enforces Bug Pattern #9 (don't change driver names on existing files).
- **`pii_allow_patterns`**: regex patterns that suppress PII findings on matching lines (for documentation that uses placeholder values).
- **`blocked_domains`**: personal domains to flag if found in committed files.
- **`pii_token_allowlist`**: specific strings to exclude from the token-shaped-string check.

### Running the lint rule tests (pytest)

```bash
uv run --python 3.12 --with pytest tests/lint_test.py -v
```

Each rule has known-good and known-bad test fixtures. The test suite verifies rules fire on bad input and stay silent on good input, without touching any real driver files.

---

## Spock unit tests

The Spock harness runs behavioral tests of driver parsing and command dispatch against canonical pyvesync YAML fixtures. It requires a JDK (auto-provisioned via Gradle toolchain).

```bash
./gradlew test --no-daemon
```

### Run order in the pipeline

The tester agent runs these in sequence:

1. **Lint first (~5 seconds):** `uv run --python 3.12 tests/lint.py --json`
2. **If lint FAIL:** return lint findings immediately; skip Spock (structural bugs cause spurious test failures)
3. **If lint PASS or WARN:** run Spock harness: `./gradlew test --no-daemon`
4. Return a combined report covering lint WARNs (if any) and Spock results

### Fixtures

`tests/fixtures/` contains vendored pyvesync API fixture files used as test inputs. See `tests/fixtures/SOURCE.md` for the upstream commit the fixtures were vendored from.
