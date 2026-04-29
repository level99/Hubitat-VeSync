# tests/pyvesync-fixtures/

Vendored pyvesync API fixture files used by `PyvesyncCoverageSpec.groovy` to assert
payload field-name parity between our driver fixtures and pyvesync's canonical requests.

## Pinned version

**pyvesync tag: 3.4.2 (commit 01196b9 — 2026-04-16)**

Source: https://github.com/webdjoe/pyvesync/tree/3.4.2/src/tests/api/

## Format

Each file preserves the original pyvesync YAML schema exactly:

```yaml
<operation_name>:
  json_object:
    payload:
      method: <bypassV2 method name>
      source: APP
      data: { ... fields ... }
  method: post
  url: /cloud/v2/deviceManaged/bypassV2
```

`PyvesyncCoverageSpec` reads `[op_name].json_object.payload.method` and
`[op_name].json_object.payload.data.keySet()` from these files.

## Refresh protocol

When pyvesync releases a new version:
1. Bump the pinned tag in this README.
2. Download updated YAMLs from the pyvesync repo at the new tag.
3. Run `./gradlew test` — any payload field-name changes will cause `PyvesyncCoverageSpec` failures.
4. Investigate each failure: is the pyvesync change a confirmed API change, or a pyvesync bug fix?
5. Update our `tests/fixtures/*.yaml` driver fixtures and drivers to match confirmed changes.

## File mapping (pyvesync path -> this directory)

| pyvesync path | vendored as |
|---|---|
| `src/tests/api/vesyncpurifier/Core200S.yaml` | `Core200S.yaml` |
| `src/tests/api/vesyncpurifier/Core300S.yaml` | `Core300S.yaml` |
| `src/tests/api/vesyncpurifier/Core400S.yaml` | `Core400S.yaml` |
| `src/tests/api/vesyncpurifier/Core600S.yaml` | `Core600S.yaml` |
| `src/tests/api/vesyncpurifier/LAP-V102S.yaml` | `LAP-V102S.yaml` |
| `src/tests/api/vesyncpurifier/LAP-V201S.yaml` | `LAP-V201S.yaml` |
| `src/tests/api/vesyncpurifier/LAP-B851S-WUS.yaml` | `LAP-B851S-WUS.yaml` |
| `src/tests/api/vesyncpurifier/EL551S.yaml` | `EL551S.yaml` |
| `src/tests/api/vesynchumidifier/Classic200S.yaml` | `Classic200S.yaml` |
| `src/tests/api/vesynchumidifier/Classic300S.yaml` | `Classic300S.yaml` |
| `src/tests/api/vesynchumidifier/Dual200S.yaml` | `Dual200S.yaml` |
| `src/tests/api/vesynchumidifier/LUH-A602S-WUS.yaml` | `LUH-A602S-WUS.yaml` |
| `src/tests/api/vesynchumidifier/LUH-A603S-WUS.yaml` | `LUH-A603S-WUS.yaml` |
| `src/tests/api/vesynchumidifier/LUH-O451S-WUS.yaml` | `LUH-O451S-WUS.yaml` |
| `src/tests/api/vesynchumidifier/LUH-M101S-WUS.yaml` | `LUH-M101S-WUS.yaml` |
| `src/tests/api/vesynchumidifier/LEH-B381S.yaml` | `LEH-B381S.yaml` |
| `src/tests/api/vesynchumidifier/LEH-S601S.yaml` | `LEH-S601S.yaml` |
| `src/tests/api/vesyncfan/LTF-F422S.yaml` | `LTF-F422S.yaml` |
| `src/tests/api/vesyncfan/LPF-R423S.yaml` | `LPF-R423S.yaml` |
