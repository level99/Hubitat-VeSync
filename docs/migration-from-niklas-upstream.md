# Migration from upstream NiklasGustafsson/Hubitat to level99/Hubitat-VeSync v2.0

If you're currently running Levoit drivers from [NiklasGustafsson/Hubitat](https://github.com/NiklasGustafsson/Hubitat) on your Hubitat hub, this guide walks through migrating to this fork.

## TL;DR

- **Existing Core 200S / 300S / 400S / 600S devices:** keep working with no Type change required. The fork preserves Niklas's driver names so device-to-driver association stays intact. Just update the driver source.
- **Existing Vital 200S devices that show "discovered but no data":** finally fixed in v2.0. Re-pick the Type after install (see [Vital 200S section](#scenario-b-vital-200s--finally-works) below).
- **Existing humidifier devices that silently stopped updating:** finally fixed in v2.0 — parent now routes per-device-type method correctly. No user action beyond updating the parent driver.
- **Already-working setups:** the parent driver also gets connection-pool retry logic and auto-sanitizing logging — both transparent improvements with no user action.

The fork is HPM-installable; the migration is mostly "update via HPM, save preferences once, verify logs."

## Prerequisites

Before migrating:

- **Hubitat hub firmware 2.3.6+** (older versions may work but aren't tested)
- **HPM (Hubitat Package Manager) installed** — recommended path. Manual paste install also works.
- **Note your existing devices and driver types** — write them down before starting so you can verify each one post-migration.
- **Have your VeSync app credentials handy** — the parent driver may briefly need to re-login; not always, but be ready.
- **Optional: take a hub backup.** Hubitat → Settings → Backup and Restore → Download Backup. Worst-case rollback insurance.

## What changes vs upstream

This fork is rebranded around the VeSync surface specifically:

| | Upstream NiklasGustafsson/Hubitat | This fork (level99/Hubitat-VeSync) |
|---|---|---|
| Levoit Core 200S / 300S / 400S / 600S | Working | Working (unchanged behavior + minor logging improvements) |
| Levoit Vital 200S | "Discovered but no data" — never worked | **Works** (community-confirmed) |
| Levoit Superior 6000S | "Discovered but no data" — never worked | **Works** |
| Humidifier polling (any humidifier child) | Silently broken — parent always called `getPurifierStatus` | **Fixed** — parent routes by device type |
| Auto-sanitizing log helpers (PII redaction) | Not present — debug captures could leak credentials | Added in parent driver |
| Connection pool retry on transient HTTP errors | Not present | Added in parent driver |
| Token-expiry auto-recovery | Not present — devices silently stop after weeks/months | Added (Bug Pattern #13 fix) |
| Other driver families (Twinkly, Shark, Ring, etc.) | Present in upstream | **Pruned** — fork is Levoit/VeSync only |

## Migration paths

Pick the scenario that matches your setup:

### Scenario A: Core 200S/300S/400S/600S only

1. **HPM update or fresh install.** Add the manifest URL `https://raw.githubusercontent.com/level99/Hubitat-VeSync/main/levoitManifest.json`. HPM detects the existing Core driver names match — it offers to update in-place.
2. **For each existing device, click Save Preferences once.** This is the "Bug Pattern #12" pref-seed gate — your `descriptionTextEnable` setting commits properly afterward, so INFO logs start flowing on state changes. (Note: if you forget, the fork auto-heals on first poll cycle anyway, but explicit Save is faster.)
3. **Verify in Hubitat → Logs** that the next poll produces an `applyStatus raw r (after peel=...) keys=[...]` line for each child. That's the diagnostic marker confirming the new driver is parsing the response correctly.

No Type change needed. Done.

### Scenario B: Vital 200S — "finally works"

If you have a Vital 200S that showed "discovered but no data" under upstream, v2.0 fixes it. The fork added a brand-new driver (`Levoit Vital 200S Air Purifier`) for this model — not a rename of an existing driver.

1. **HPM update** picks up the new driver file.
2. **In Hubitat → Devices, find your Vital 200S device.**
3. **Change Type to "Levoit Vital 200S Air Purifier"** (the new fork driver). Click Save Device.
4. **Click Save Preferences** on the device (commits the new driver's default settings — see Bug Pattern #12 note above).
5. **Verify the next poll cycle** produces `applyStatus raw r (after peel=N) keys=[powerSwitch, workMode, fanSpeedLevel, ...]`. Real device data should populate within ~30 seconds.

### Scenario C: Superior 6000S — "finally works"

Same shape as Scenario B but for the Superior 6000S humidifier:

1. **HPM update** picks up `Levoit Superior 6000S Humidifier`.
2. **Change Type** on the existing device. Save Device.
3. **Save Preferences.**
4. **Verify polling** — look for `applyStatus raw r (after peel=1)` (note `peel=1` — Superior 6000S responses are double-wrapped; the driver auto-peels both layers).
5. **Watch for INFO logs** like `Water: ok`, `Wick drying cycle complete` on state changes — confirmation the parser is working end-to-end.

### Scenario D: Mixed install

Combine the above. Order doesn't matter; the fork's driver files are independent. Recommended sequence:

1. Update parent driver first (HPM does this automatically). Restart any in-flight schedules.
2. Update Core line children — no Type change needed.
3. Re-Type Vital 200S / Superior 6000S devices to the new fork drivers.
4. Save Preferences across all affected devices.
5. Watch logs for one full poll cycle (~30s default).

## Verification — what good looks like

In Hubitat → Logs after migration, you should see these markers per polling cycle:

- **Parent:** `Heartbeat: synced` — polling loop completed successfully
- **Per child:** `applyStatus raw r (after peel=N) keys=[...non-empty list...]` — device data parsed
- **No `MissingMethodException`** lines anywhere — these were the upstream bug signature (Bug Pattern #1)
- **No `code:-1` repeated traceId errors** — the upstream humidifier bug signature (Bug Pattern #2)

State-change INFO logs (like `Power on`, `Speed: medium`, `Mode: auto`, `Water: ok`) should fire on actual state transitions. If they don't appear at all even though attributes are populating, see [Troubleshooting → Silent INFO logs](#silent-info-logs).

## Things to NOT do

- **Don't manually rename existing driver files or change `name:` metadata fields.** Hubitat associates devices to drivers by the `name` field. Changing it orphans your existing devices (Bug Pattern #9). The fork preserves the original Core line names exactly for this reason; only Vital 200S + Superior 6000S have new names because they're brand-new drivers in the fork.
- **Don't delete your existing devices to "start fresh."** You'll lose room assignments, automation rules, and dashboard tile configurations. Just update the driver source and re-Type if needed; everything else stays.
- **Don't disable the parent driver mid-migration.** Children depend on the parent for cloud calls. Update the parent first, then update children, then verify.

## Troubleshooting

### Silent INFO logs

**Symptom:** Device attributes populate correctly but no INFO log entries appear even on state changes.

**Cause:** Bug Pattern #12 — when Hubitat re-Types a device, the new driver's `defaultValue: true` for `descriptionTextEnable` doesn't auto-commit until you click Save Preferences. The fork includes a one-time auto-heal that fixes this on first poll cycle, but explicit Save Preferences is faster.

**Fix:** Open the device page → Save Preferences. Next poll cycle should produce INFO logs.

### "device discovered but no data" persists after migration

**Symptom:** Vital 200S or Superior 6000S still shows null attributes after re-Typing to the fork driver.

**Possible causes:**
- Forgot to update the parent driver — children depend on the parent's `sendBypassRequest` method-branching fix.
- Forgot Save Preferences on the device.
- VeSync token expired and parent's auto-recovery hasn't fired yet — wait one poll cycle (~30s).
- VeSync cloud transient failure — empty results are common (~10-30%) even on healthy installs. Wait 2-3 poll cycles before declaring failure.

**Fix:** verify parent driver version reflects v2.0; verify child Type is the new fork driver; click Save Preferences; wait a poll cycle; check logs for `applyStatus raw r ...` line.

### "Connection pool shut down" errors

**Symptom:** Logs show `Connection pool shut down` errors after driver update.

**Cause:** Hubitat's HTTP connection pool needs to reinitialize after driver code changes. The fork has retry logic that handles this within 1-2 attempts.

**Fix:** Wait 30 seconds. If errors persist past that, **reboot the hub** (Hubitat → Settings → Reboot). One-time recovery for stale HTTP state.

## Rollback

If something goes seriously wrong:

1. **HPM → Revert / Match Up** can downgrade to the prior installed manifest version.
2. **Worst case:** restore the hub backup you took in Prerequisites.

The fork's changes are additive on the Core line side and surgical on the parent — there's no destructive rewrite. Rollback is straightforward.

## Reporting issues

If migration surfaces a problem not covered here:

- **Quick questions:** [Hubitat community thread](https://community.hubitat.com/t/levoit-air-purifiers-drivers/81816) — fastest response, other users may have hit the same issue.
- **Bug reports / feature requests:** [GitHub issues](https://github.com/level99/Hubitat-VeSync/issues) — better for issues that need code-level investigation.

When reporting, capture a debug log (parent device → enable Debug logging → trigger the issue → grab the relevant lines). Account credentials are auto-redacted by the parent's `sanitize()` helper, so debug captures are safe to share.
