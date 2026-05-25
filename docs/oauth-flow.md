# VeSync OAuth2 flow — internal reference

**Audience:** maintainers and AI sessions debugging auth issues. NOT user-facing — users see only the per-version CHANGELOG entries.

**Scope:** the two-stage OAuth2 login flow introduced in v2.7 (`d8df06d`), matching pyvesync 3.4.1's `authByPWDOrOTM` + `loginByAuthorizeCode4Vesync` pattern. Replaces the legacy `/cloud/v1/user/login` single-step endpoint that VeSync began rejecting in early 2026 with inner code `-11012022` "app version is too low" (BP catalog entry #27).

**Source of truth:** `Drivers/Levoit/VeSyncIntegration.groovy` — `login()`, `getAuthorizationCode()`, `exchangeAuthCode()`, `ensureTerminalId()`, `deriveUserCountryCode()`, `generateTraceId()`. Cross-reference: webdjoe/pyvesync 3.4.1 (`pyvesync/auth.py`, `pyvesync/const.py`, `pyvesync/models/vesync_models.py`).

---

## Constants

| Constant | Value | Notes |
|---|---|---|
| `APP_VERSION` | `"5.6.60"` | matches pyvesync 3.4.1 `const.py APP_VERSION` |
| `APP_ID` | `"eldodkfj"` | matches pyvesync 3.4.1 `const.py APP_ID` |
| `CLIENT_TYPE` | `"vesyncApp"` | matches pyvesync 3.4.1 |
| `AUTH_PROTOCOL_TYPE` | `"generic"` | per pyvesync `RequestGetTokenModel` |
| `PHONE_BRAND` | `"pyvesync"` | matches pyvesync 3.4.1 `const.py PHONE_BRAND` — used on all three call sites (auth, getDevices, sendBypassRequest) since v2.7 |
| `PHONE_OS` | `"Android"` | per pyvesync |
| `CROSS_REGION_ERROR_CODE` | `-11260022` | VeSync's regional sharding rejection |
| `MAX_CROSS_REGION_RETRIES` | `2` | bounds the cross-region retry recursion |
| `DEFAULT_TRACE_ID` | `"1634265366"` | static traceId for `getDevices` + `sendBypassRequest`; AUTH path uses dynamic `generateTraceId()` instead |

## State variables

| State | Set by | Purpose | Persistence |
|---|---|---|---|
| `state.terminalId` | `ensureTerminalId()` first call | Stable per-install fingerprint VeSync identifies the Hubitat hub by. Format: `"2" + 32-hex` (33 chars). Generated once, never reset (preserved across `Force Reinitialize`). | Persistent (Hubitat state) |
| `state.token` | `exchangeAuthCode()` on Stage 2 success | Bearer token for bypassV2 device calls. Cleared on auth failure. | Persistent |
| `state.accountID` | `exchangeAuthCode()` on Stage 2 success (atomic with token) | Account identifier for bypassV2 envelope. Atomic-pair invariant: set together with `state.token` or both absent. | Persistent |
| `state.countryCode` | `deriveUserCountryCode()` first call OR cross-region response | ISO country code for `userCountryCode` field. Default `"US"` (US region) or `"DE"` (EU region). Updated when VeSync's cross-region response supplies a corrective value. | Persistent |
| `state.traceSeq` | `generateTraceId()` | Per-request counter for traceId suffix. Wraps at 99999 to prevent integer overflow + always render 5 digits. | Persistent |
| `state.reAuthInProgress` | `sendBypassRequest` BP13 path | Serializes concurrent re-auth attempts when multiple children hit 401 simultaneously. | Transient (cleared in finally) |

---

## Fresh-install flow

User installs parent driver → enters email + password + deviceRegion → Save Preferences → `updated()` → `login()`.

At this point: `state.token`, `state.terminalId`, `state.accountID`, `state.countryCode`, `state.traceSeq` are all **null/unset**.

### Stage 0 — `login()` (line 930)

Wraps the two-stage flow in `retryableHttp("login", 3)` — catches transient Apache HttpClient `IllegalStateException("Connection pool shut down")` errors, up to 3 retries. The two API calls happen serially inside that retry envelope.

### Stage 1 — `getAuthorizationCode()` → `/globalPlatform/api/accountAuth/v1/authByPWDOrOTM`

1. `ensureTerminalId()` — `state.terminalId` is null → generates `"2" + UUID-hex` (33 chars). Persists. **This identifier persists forever for this install.**
2. `deriveUserCountryCode()` — null → defaults `"US"` (US region) or `"DE"` (EU region). Persists.
3. `generateTraceId()` — builds `"APP" + tid[-5..-2] + epochSec + "-" + 5-digit-seq`. First call: seq=1, persists `state.traceSeq=1`.
4. `MD5(password)` — hash the user-entered password.
5. Build body Map (~18 fields): `email`, hashed `password`, `method:"authByPWDOrOTM"`, `terminalId`, `traceId`, `clientInfo:"pyvesync"`, `appID:"eldodkfj"`, `clientType:"vesyncApp"`, `clientVersion:"VeSync 5.6.60"`, `authProtocolType:"generic"`, `userCountryCode`, `acceptLanguage:"en"`, etc. — matches pyvesync 3.4.1's `RequestGetTokenModel`.
6. **Pre-serialize body to JSON String** via `new groovy.json.JsonBuilder(body).toString()` (Apache HttpClient chunked-encoding workaround — see `058ac2f`). Set headers `Content-Type: application/json; charset=UTF-8` + `Expect: ""`.
7. `httpPost` to Stage 1 endpoint. Response on success: `result.authorizeCode` + `result.accountID`.
8. Returns `[authCode: <code>, stage1AccountID: <id>]` — **does NOT write `state.accountID` yet** (atomic-pair invariant).

### Stage 2 — `exchangeAuthCode(authCode, stage1AccountID, null, null, 0)` → `/user/api/accountManage/v1/loginByAuthorizeCode4Vesync`

1. `ensureTerminalId()` returns same value from Stage 1.
2. `generateTraceId()` → seq=2, fresh traceId.
3. Build body Map (~15 fields) with `authorizeCode` from Stage 1. Matches pyvesync's `RequestLoginTokenModel`. On a fresh login, `bizToken` and `regionChange` are absent.
4. Pre-serialize, POST.
5. Response on success: `result.token` + `result.accountID` + `result.countryCode`.
6. **Atomic-pair commit:**
   ```groovy
   state.token     = token
   state.accountID = acctId ?: stage1AccountID
   if (countryCode) state.countryCode = countryCode
   ```
7. Logs `INFO: Logged in to VeSync (<region>: <api host>)`.

### Cross-region edge

If Stage 2 returns inner code `-11260022` (CROSS_REGION_ERROR_CODE), the account is registered in the other region. Response includes `bizToken` + (sometimes) corrective `countryCode`. Driver recurses Stage 2 with `bizToken` + `regionChange="lastRegion"`. Bounded to `MAX_CROSS_REGION_RETRIES=2`. If corrective `countryCode` is empty (observed VeSync behavior — see BP catalog), driver emits a WARN and retries with current `state.countryCode`.

### Post-login → `getDevices()`

`login() → true` triggers `getDevices()` which uses the new `state.token` + `state.accountID` via the bypassV2 path. Children get spawned, polls begin.

---

## What VeSync sees on its side (the "new device login" email)

A fresh login with `clientInfo: "pyvesync"` + new `terminalId` + new external IP triggers VeSync's standard anti-abuse path → user receives:

> Dear User, Your VeSync Account was logged in on a new device. Device: pyvesync. Login Time: ... Location: ... (IP Address: ...)

This is expected on **first login under v2.7** for any existing user (the `PHONE_BRAND` changed from a different value, and `terminalId` is new). It is also expected on a true fresh install (new VeSync account, or first connection from a new Hubitat hub).

It is NOT expected on:
- Subsequent token-expiry re-auths (same fingerprint persists)
- `Force Reinitialize` (terminalId is stashed + restored)
- Hub reboot (state persists)

If a user reports repeated "new device login" emails on a stable install, the terminalId may be regenerating — check that `state.terminalId` is still set in `captureDiagnostics` output.

---

## Subsequent activity

| Trigger | Behavior |
|---|---|
| Normal polls | Children call `parent.sendBypassRequest()` → uses persisted `state.token` directly. No re-login. |
| Token expiry (HTTP 401 / inner -11001000 / -11201000 / others detected by `isAuthFailure()`) | BP13 path in `sendBypassRequest` triggers `login()` again. `state.terminalId` + `state.accountID` + `state.countryCode` persist → VeSync sees the SAME fingerprint → no second "new device" email. `state.reAuthInProgress` flag serializes concurrent re-auth attempts. |
| `Force Reinitialize` command | Stashes `state.terminalId` before `state.clear()`, restores after. Preserves fingerprint. Triggers fresh `login()` but VeSync sees same install. |
| Hub reboot | `state.*` persists to disk → same fingerprint after restart. Polling resumes from cron via BP14. |
| VeSync deprecates current auth endpoint (BP27 recurrence) | Same shape of failure as the v2.7 trigger: inner code `-11012022` or similar version-gate code. Migration to next pyvesync release's auth flow required. |

---

## Troubleshooting / symptom-to-root-cause

### Inner-code mapping

| Inner code | Meaning | Site | What to check |
|---|---|---|---|
| `0` | Success | any | — |
| `-11012022` | "app version is too low" | Stage 1 (or any auth call) | VeSync tightened appVersion validation; check pyvesync `APP_VERSION` for newer value; this is the BP27 pattern that triggered v2.7 |
| `-11102086` | "internal error" on auth path | Stage 1 / Stage 2 | Apache HttpClient sending `Transfer-Encoding: chunked` and/or `Expect: 100-continue` — verify body is pre-serialized to JSON String AND `Expect: ""` header is set. Critical workaround — see `058ac2f` |
| `-11260022` | Cross-region rejection | Stage 1 / Stage 2 | Account is in opposite region; driver retries with `bizToken` + `regionChange="lastRegion"`. Bounded to 2 retries |
| `-11001000` | Token expired | bypassV2 | Normal — BP13 path triggers `login()` re-auth |
| `-11201000` | Invalid credentials | bypassV2 / auth | Either token rotation OR user changed password OR account-level lockout. BP13 also triggers re-auth on this code |
| `-11001` / `-11201` | 4-digit variants (rare) | bypassV2 | Same handling as the 8-digit forms — `isAuthFailure()` recognizes both |
| any other non-zero | VeSync-specific failure | varies | Driver logs `ERROR: <method>: VeSync inner code=<n> msg='<msg>'` + `recordError()`. Check `captureDiagnostics` for the message; cross-reference with pyvesync issue tracker |

### "Login failed - check credentials" symptom

This generic user-facing message can have multiple root causes. Triage:

1. **Email/password actually wrong** — try in VeSync mobile app. If app fails too → password reset.
2. **Inner code -11012022 in logs** → BP27 recurrence; VeSync tightened version-gate. Update `APP_VERSION` to match current pyvesync release.
3. **Inner code -11102086 in logs** → Apache HttpClient transport issue. Verify pre-serialize-body workaround still in place; check if Hubitat updated its HTTP runtime.
4. **Inner code -11260022 with retry exhaustion** → cross-region thrash. Check `state.countryCode`; user may have toggle-tried both US and EU deviceRegion settings; if exhaustion persists, deviceRegion preference may not match account region.
5. **HTTP error before inner code (no response body)** → network-layer issue (DNS, firewall, captive portal). BP22 circuit-breaker should catch this.
6. **`isAuthFailure()` loops** — Stage 1 succeeds, Stage 2 fails with auth code → check `state.reAuthInProgress` isn't stuck `true` (transient state should clear in finally).

### How to enable verboseDebug + read the dumps

**Enable:** parent device → Preferences → `Verbose Debug` toggle ON → Save.

**Read:** Hubitat Logs → filter to VeSync Integration. Look for:

- `getAuthorizationCode request body: [email:..., terminalId:..., ...]` (password filtered; sanitize() redacts email/token at log time)
- `getAuthorizationCode: HTTP 200, innerCode 0` or `innerCode -<code>`
- Stage 1 response dump (auth-material keys `accountID`, `authorizeCode`, `token`, `bizToken` are filtered before log)
- `exchangeAuthCode request body: [method:loginByAuthorizeCode4Vesync, terminalId:..., ...]` (`authorizeCode` + `bizToken` filtered)
- `Logged in to VeSync (<region>: <host>)` on success

**Auto-disable:** verboseDebug + debugOutput both auto-disable after 30 minutes (BP16 watchdog).

### Diagnostic field reference for `captureDiagnostics`

The diagnostics dump (`captureDiagnostics` command on parent) surfaces v2.7 auth-relevant state:

- `parent.countryCode` — current `state.countryCode` verbatim
- `parent.terminalId` — truncated to 8 chars + ellipsis (`"2a1b3c4d…"`) for privacy. Full value never leaves the hub
- `parent.token` — presence indicator only (set/unset); value never logged
- `parent.accountID` — presence indicator only

If `terminalId` shows `(not set)` on a working install, something cleared it — possible BP14/state-clear issue.

### Known forum-issue mappings

| Forum symptom | Likely root cause | Diagnostic |
|---|---|---|
| "Stopped working overnight, login fails" on a previously stable install | Token expired AND re-auth failing — possibly BP27 (VeSync tightened gate) | Check Hubitat logs for inner code -11012022 |
| "I keep getting new device login emails from VeSync" | `state.terminalId` regenerating — should be one-time per install | Check `captureDiagnostics`: if `terminalId` rotates between dumps, investigate state-clear path |
| "Devices show offline but VeSync app works" | Auth OK but bypassV2 calls failing — possible BP4 field-name shift, BP17 stale configModule, or network issue (BP22) | Check inner codes on `sendBypassRequest` log lines |
| "Force Reinitialize doesn't help" | Same fingerprint preserved correctly; underlying API issue is independent of auth | Investigate without changing auth state — see BP catalog #20-22 |

---

## HTTP-layer notes (Apache HttpClient quirks on Hubitat)

The v2.7 auth-flow workaround in commit `058ac2f` addresses two Apache HttpClient 4.x behaviors that VeSync's OAuth middleware rejects:

1. **`Transfer-Encoding: chunked`** — Apache's default when body size is not pre-computed at send time. Suppress by pre-serializing the body to a JSON String first (then Content-Length is computable and Apache sends a fixed-length body).
2. **`Expect: 100-continue`** — Apache auto-injects on POST requests. VeSync's middleware rejects this with inner code `-11102086`. Suppress by setting `Expect: ""` explicitly in the request headers (Apache treats empty-value as "skip injection").

If a future Hubitat JDK or HttpClient upgrade changes these defaults, the workaround should remain benign (no chunked encoding + no Expect header is universally safe). But test the auth path after any platform upgrade.

The workaround is auth-path-only. `sendBypassRequest()` / `getDevices()` send Map bodies (Apache chunks them) and VeSync accepts this on the bypassV2 path. If a future bypassV2 endpoint also starts rejecting chunked encoding, the same workaround applies.

### `Expect: ""` regression spec

`V27.8b` in `VeSyncIntegrationSpec.groovy` pins `params.headers["Expect"] == ""` on both auth stages. A refactor that drops the explicit `Expect: ""` header will fail V27.8b.

---

## Why pre-v2.7 differs

The pre-v2.7 (`v2.6` and earlier) login used a single POST to `/cloud/v1/user/login` with a hashed password. Returned `accountID` + `tk` (token) in one round-trip. Simpler but newer accounts get rejected with `-11012022` because VeSync's app-version-validation on that endpoint tightened in early 2026.

The v2.7 two-stage flow:
- Mirrors what the VeSync mobile app does today
- Uses dynamic `terminalId` + `traceId` per pyvesync
- Carries `clientInfo:"pyvesync"` instead of a fake device-brand string
- Sends through different endpoints (`/globalPlatform/...` + `/user/api/accountManage/...`) that VeSync's current backend accepts

Any future similar "endpoint deprecated via version-gate" event (BP27 recurrence) will require similar migration — track pyvesync's `APP_VERSION` and endpoint URLs as the canonical reference.

---

## References

- pyvesync 3.4.1: https://github.com/webdjoe/pyvesync (tag `v3.4.1`)
  - `src/pyvesync/auth.py` — auth flow source
  - `src/pyvesync/const.py` — `APP_VERSION`, `APP_ID`, `PHONE_BRAND`, etc.
  - `src/pyvesync/models/vesync_models.py` — `RequestGetTokenModel`, `RequestLoginTokenModel`
- Bug Pattern catalog: see `~/.claude/CLAUDE.md` BP27 (VeSync API endpoint deprecated via appVersion gate)
- v2.7 commits: `d8df06d` (OAuth migration), `058ac2f` (HttpClient workaround), `0487244` (verboseDebug logging), `1bcc978` (regression guards)
- BP catalog entries in the QA agent definition: see `.claude/agents/vesync-driver-qa.md` BP13 (token-expiry re-auth wiring)
