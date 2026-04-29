# `tools/pyvesync-tracker/`

Implementation of the pyvesync upstream-tracking workflow at
`.github/workflows/pyvesync-tracker.yml`.

## What it does

Once a week the workflow checks whether `webdjoe/pyvesync` has shipped a new
release tag past the version pinned in `tests/pyvesync-fixtures/README.md`.
When a newer tag is found, the bot does up to two things in the same run:

- **Output A** — opens a `chore: bump pyvesync to <tag>` PR that refreshes the
  vendored YAML fixtures in `tests/pyvesync-fixtures/` to the new tag. Lint,
  Spock, and `PyvesyncCoverageSpec` run on the PR via existing CI; green = safe
  to merge; red = real divergence between this fork's drivers and pyvesync,
  triaged per failure.
- **Output B** — if any new Levoit device codes appeared in pyvesync's
  `device_map.py`, opens a `new-device-support` issue listing them with their
  pyvesync class assignments and triage hints.

## Entry points

| Script | Purpose | Deps |
|---|---|---|
| `detect.py` | Read pinned tag from README, fetch latest pyvesync release, set workflow outputs (`has-update`, `old-tag`, `new-tag`). | stdlib |
| `refresh.py <new_tag>` | Fetch the vendored YAMLs from the new tag (set defined in `FIXTURES`), trim each to `<op>.json_object.payload`, preserve existing leading-comment block, update the pinned-tag line in README. | `pyyaml` (PEP 723 inline) |
| `detect_devices.py <old_tag> <new_tag>` | Diff `device_map.py` between two tags, filter to Levoit codes, emit `new-devices.md` for issue body. | stdlib |

## Dry-run locally

```bash
# Detect: should print has-update=false against current pinned tag.
uv run --python 3.12 tools/pyvesync-tracker/detect.py

# Output A: refresh against current tag (no-op for fixtures; rewrites README).
uv run --python 3.12 tools/pyvesync-tracker/refresh.py 3.4.2
git diff tests/pyvesync-fixtures/   # inspect; revert if not committing
git checkout -- tests/pyvesync-fixtures/

# Output B: simulate against an older tag to see the issue body it would open.
uv run --python 3.12 tools/pyvesync-tracker/detect_devices.py 3.3.3 3.4.2
cat new-devices.md
rm new-devices.md
```

## Filter scope

Output B intentionally filters to Levoit prefixes (`LAP-`, `LUH-`, `LEH-`,
`LTF-`, `LPF-`, `LV-`) plus literal codes (`Core200S`, `Classic200S`,
`Vital200S`, `Dual200S`, `Core300S`/`400S`/`600S`, `Classic300S`). Pyvesync
also tracks Cosori, Etekcity, and other VeSync-OEM brands; those don't surface
through this fork's driver pack and are filtered out to keep the issue queue
clean.

To extend the filter: edit `LEVOIT_PREFIXES` and `LITERAL_CODES` in
`detect_devices.py`.
