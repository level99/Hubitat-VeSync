# Fixture Sources

Pyvesync test fixtures vendored into this directory for use by the Spock unit test harness.

## Upstream repository

https://github.com/webdjoe/pyvesync

## Commit fetched

`c98729c45860cc765d63d49a9a02bbc18de090c4` (2026-04-16)

## Date retrieved

2026-04-25

## Files

| Fixture file | Request source | Response source |
|---|---|---|
| `LAP-V201S.yaml` | `src/tests/api/vesyncpurifier/LAP-V201S.yaml` | `device_on_manual_speed2`: canonical from `src/tests/call_json_purifiers.py` PURIFIER_DETAILS["LAP-V201S"] with PurifierDefaults constants resolved. Note: canonical `AQLevel: 2` (PurifierDefaults.air_quality_enum=AirQualityLevel.GOOD per const.py), NOT `1` (which is the LV-RH131S `air_quality_level` constant — different model). Boolean/enum fields (screenState, childLockSwitch, screenSwitch, lightDetectionSwitch, environmentLightState) are written as integers in our fixture rather than the raw `DeviceStatus`/`bool` form pyvesync uses for V201S; this matches real-device behavior and the `int(...)` form sibling models (V102S/EL551S/B851S) use. Other scenarios synthesized. |
| `LEH-S601S.yaml` | `src/tests/api/vesynchumidifier/LEH-S601S.yaml` | `device_on_manual_canonical`: canonical from `src/tests/call_json_humidifiers.py` HUMIDIFIER_DETAILS["LEH-S601S"] with HumidifierDefaults constants resolved. Other scenarios synthesized. |
| `Core200S.yaml` | `src/tests/api/vesyncpurifier/Core200S.yaml` | `device_on_manual_speed1`: canonical from `src/tests/call_json_purifiers.py` PURIFIER_DETAILS["Core200S"]. `device_on_manual_speed2` and other scenarios synthesized. |
| `Core300S.yaml` | `src/tests/api/vesyncpurifier/Core300S.yaml` | `device_on_manual_speed1`: canonical from `src/tests/call_json_purifiers.py` PURIFIER_DETAILS["Core300S"]. `device_on_manual_speed2` and other scenarios synthesized. |
| `Core400S.yaml` | `src/tests/api/vesyncpurifier/Core400S.yaml` | `device_on_manual_speed1`: canonical from `src/tests/call_json_purifiers.py` PURIFIER_DETAILS["Core400S"]. `device_on_manual_speed2` and other scenarios synthesized. |
| `Core600S.yaml` | `src/tests/api/vesyncpurifier/Core600S.yaml` | `device_on_manual_speed1`: canonical from `src/tests/call_json_purifiers.py` PURIFIER_DETAILS["Core600S"]. `device_on_manual_speed2` and other scenarios synthesized. |

## Response sourcing detail

### Canonical scenarios

Each fixture now has a `device_on_manual_speed1` (purifiers) or `device_on_manual_canonical` (humidifier)
scenario whose field values are sourced from pyvesync's `call_json_purifiers.py` /
`call_json_humidifiers.py` Python files at the commit above. These Python files define
`PURIFIER_DETAILS` and `HUMIDIFIER_DETAILS` dicts mapping model codes to their expected
API response fields, with values from `PurifierDefaults` / `HumidifierDefaults` classes.

Constants were resolved to literals by reading both the defaults class definition and
the const.py enums (PurifierModes, HumidifierModes, DryingModes, DeviceStatus) at the
same commit.

### Synthesized scenarios

Scenarios other than the canonical one are synthesized from driver source code field names.
These exist to exercise specific test cases (speed level mapping, filter-life threshold
alerts, device-off state, etc.) that require specific field values the canonical defaults
don't provide. They are labeled with `# Synthesized scenario:` comments in the YAML.

## Field gap surfaces (canonical vs. driver)

During round 2 canonicalization, the following fields were found in pyvesync canonical
responses that our drivers do NOT currently parse:

### LAP-V201S (Vital 200S driver)
- `efficientModeTimeRemain` — new field, not parsed by LevoitVital200S.groovy
- `sleepPreference` — nested object with sleep schedule config, not parsed
- `sensorCalibration` — present in synthesized fixture but not in canonical (may be absent in real responses)
- Canonical `childLockSwitch: 1` vs synthesized `0` — driver parses correctly; spec assertions updated

### LEH-S601S (Superior 6000S driver)
- `autoPreference: 1` (integer in humidifier, vs object in purifier) — driver may handle this differently
- `scheduleCount: 0` / `errorCode: 0` — present in canonical but likely ignored by driver (not asserted)
- `dryingRemain: 7200` in DONE state — unusual; live devices may differ from test fixture

### Core-line (200S/300S/400S/600S drivers)
- `display: true` / `child_lock: true` — Boolean form not parsed by Core-line drivers (they use `enabled`/`mode` shape)
- `extension: {schedule_count, timer_remain}` — not parsed by Core-line drivers
- `device_error_code: 0` — not parsed by Core-line drivers
- Core200S: `configuration: {display, display_forever}` (no auto_preference) — driver doesn't access these

These gaps are informational only. Driver parsing changes are out of scope for round 2.

## Refresh procedure

To update fixtures after pyvesync adds or changes API fields:

```bash
# Fetch the latest upstream YAML for a purifier model
curl -sL https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/api/vesyncpurifier/<MODEL>.yaml

# Fetch the latest upstream YAML for a humidifier model
curl -sL https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/api/vesynchumidifier/<MODEL>.yaml

# Fetch canonical response data
curl -sL https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/call_json_purifiers.py
curl -sL https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/call_json_humidifiers.py
curl -sL https://raw.githubusercontent.com/webdjoe/pyvesync/master/src/tests/defaults.py
```

Update the fixture file(s), bump the commit SHA and retrieval date in this file and in the fixture file headers. Then re-run `./gradlew test` to confirm the specs still pass (or update failing specs to match the new field names).
