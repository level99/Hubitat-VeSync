## What this PR does

<!-- One paragraph: what changes and why. -->

## Testing

<!-- How did you verify this works? Tested on your own hub? Spock tests cover it? Both? -->

**Verified on (check all that apply):**

- [ ] Real hardware (own a device matching this driver; commands round-trip via real VeSync cloud)
- [ ] Virtual test parent (`VeSyncIntegrationVirtual.groovy` — fixture-driven on-hub verification; see CONTRIBUTING.md "On-hub validation without owning the hardware")
- [ ] Spock + manual review only (no on-hub verification — preview driver, contributor without hardware, or doc/test-only change)

## Checklist

- [ ] Lint passes (`uv run --python 3.12 tests/lint.py --strict` exits 0). CI runs this too.
- [ ] Spock tests pass (`./gradlew test --no-daemon`) — for any driver-source change.
- [ ] No real PII committed (hub IPs, real emails, real credentials, real device IDs). The lint catches most; this is your eyeball check.
- [ ] If adding/changing a driver: `levoitManifest.json` + `Drivers/Levoit/readme.md` updated.
- [ ] If a breaking change for existing users: noted in "What this PR does" above + migration guide updated.

Closes #
