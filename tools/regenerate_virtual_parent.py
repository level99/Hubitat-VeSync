#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["pyyaml"]
# ///
"""Regenerate the FIXTURE_OPS @Field block in VeSyncIntegrationVirtual.groovy.

Reads pyvesync canonical request fixtures from tests/pyvesync-fixtures/*.yaml
and merges with hand-curated method extensions from
tools/virtual_parent_extensions.json (which list driver-source-derived methods
that pyvesync's upstream fixture hasn't been updated to cover, e.g. the v2.3
Core 200S setDisplay/setChildLock/addTimer/delTimer/resetFilter additions).

Patches the driver source in-place between:
    // === FIXTURE_OPS GENERATED START ===
    // === FIXTURE_OPS GENERATED END ===

Usage:
    uv run --python 3.12 tools/regenerate_virtual_parent.py
        Regenerates FIXTURE_OPS from current pyvesync-fixtures/ + extensions.json
        and writes the result back to the driver. Aborts cleanly if the markers
        are missing.

    uv run --python 3.12 tools/regenerate_virtual_parent.py --check
        Dry-run: prints the would-be-generated block to stdout without writing.
        Exits non-zero if the file would change.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
PYVESYNC_FIXTURES_DIR = REPO_ROOT / "tests" / "pyvesync-fixtures"
EXTENSIONS_JSON = REPO_ROOT / "tools" / "virtual_parent_extensions.json"
DRIVER_FILE = REPO_ROOT / "Drivers" / "Levoit" / "VeSyncIntegrationVirtual.groovy"

START_MARKER = "// === FIXTURE_OPS GENERATED START ==="
END_MARKER = "// === FIXTURE_OPS GENERATED END ==="


def load_pyvesync_methods(fixture_path: Path) -> list[dict]:
    """Return a list of {method, dataKeys} entries for one pyvesync fixture file.

    Pyvesync fixture format (after our trim — see tests/pyvesync-fixtures/README.md):
        <op_name>:
          json_object:
            payload:
              method: <bypassV2 method name>
              data:
                <key1>: ...
                <key2>: ...
              source: APP

    Multiple ops may dispatch via the same method (e.g. set_fan_speed AND
    set_manual_mode both → setLevel). We dedupe by method name, taking the
    union of data keys across all ops with that method (typically identical).
    """
    raw = yaml.safe_load(fixture_path.read_text(encoding="utf-8")) or {}
    by_method: dict[str, set[str]] = {}
    for op_name, op_body in raw.items():
        payload = (op_body or {}).get("json_object", {}).get("payload")
        if not payload:
            continue
        method = payload.get("method")
        if not method:
            continue
        data = payload.get("data") or {}
        keys = set(data.keys()) if isinstance(data, dict) else set()
        if method in by_method:
            by_method[method] |= keys
        else:
            by_method[method] = keys
    # Preserve first-occurrence order while deduping
    seen: list[str] = []
    for op_body in raw.values():
        payload = (op_body or {}).get("json_object", {}).get("payload") or {}
        m = payload.get("method")
        if m and m not in seen and m in by_method:
            seen.append(m)
    return [{"method": m, "dataKeys": sorted(by_method[m])} for m in seen]


def merge_extensions(
    pyvesync_methods: list[dict],
    extensions: list[dict],
) -> list[dict]:
    """Merge extension entries into the pyvesync method list.

    Extensions take precedence on method-name conflict (rare but possible).
    Extension methods are appended after pyvesync methods to preserve
    visual ordering hint in the generated output (pyvesync first, extensions
    after).
    """
    result = list(pyvesync_methods)
    seen_methods = {m["method"] for m in result}
    for ext in extensions:
        method = ext["method"]
        keys = sorted(ext.get("dataKeys", []))
        if method in seen_methods:
            # Replace existing entry with extension's keys
            for entry in result:
                if entry["method"] == method:
                    entry["dataKeys"] = keys
                    break
        else:
            result.append({"method": method, "dataKeys": keys})
            seen_methods.add(method)
    return result


def render_groovy_block(fixture_to_methods: dict[str, list[dict]]) -> str:
    """Render the @Field FIXTURE_OPS map as a Groovy literal.

    Output format matches the existing hand-coded style: aligned dataKeys on
    each line, `as Set` suffix, comma-separated entries inside the per-fixture
    list.
    """
    lines: list[str] = []
    lines.append("@Field static final Map FIXTURE_OPS = [")
    fixture_names = sorted(fixture_to_methods.keys())
    last_fixture = fixture_names[-1]
    for fixture_name in fixture_names:
        methods = fixture_to_methods[fixture_name]
        lines.append(f'    "{fixture_name}": [')
        # Compute column alignment for the dataKeys column (cosmetic).
        # Quoted method strings get an extra 2 chars for the quotes.
        max_method_len = max((len(m["method"]) for m in methods), default=0)
        for entry in methods:
            method = entry["method"]
            keys = entry["dataKeys"]
            keys_literal = ", ".join(f'"{k}"' for k in keys)
            method_quoted = f'"{method}",'
            method_padded = method_quoted.ljust(max_method_len + 4)
            line = f"        [methodName: {method_padded} dataKeys: [{keys_literal}] as Set]"
            if entry is not methods[-1]:
                line += ","
            lines.append(line)
        closing = "    ]" + ("," if fixture_name != last_fixture else "")
        lines.append(closing)
    lines.append("]")
    return "\n".join(lines)


def patch_driver(driver_source: str, generated_block: str) -> str:
    """Replace content between START/END markers with the new block.

    The markers themselves are preserved. Aborts (raises) if either marker
    is missing or appears multiple times.
    """
    pattern = re.compile(
        re.escape(START_MARKER) + r".*?" + re.escape(END_MARKER),
        re.DOTALL,
    )
    matches = list(pattern.finditer(driver_source))
    if len(matches) == 0:
        raise SystemExit(
            f"could not find marker pair in {DRIVER_FILE.name}; "
            f"expected '{START_MARKER}' and '{END_MARKER}'"
        )
    if len(matches) > 1:
        raise SystemExit(
            f"found {len(matches)} marker pairs in {DRIVER_FILE.name}; "
            f"expected exactly one"
        )
    replacement = f"{START_MARKER}\n{generated_block}\n{END_MARKER}"
    return pattern.sub(replacement, driver_source, count=1)


def main() -> int:
    check_only = "--check" in sys.argv

    if not PYVESYNC_FIXTURES_DIR.is_dir():
        raise SystemExit(f"fixtures dir not found: {PYVESYNC_FIXTURES_DIR}")
    if not EXTENSIONS_JSON.is_file():
        raise SystemExit(f"extensions JSON not found: {EXTENSIONS_JSON}")
    if not DRIVER_FILE.is_file():
        raise SystemExit(f"driver source not found: {DRIVER_FILE}")

    extensions_data = json.loads(EXTENSIONS_JSON.read_text(encoding="utf-8"))
    # Skip the _comment metadata key; only fixture entries are processed
    extensions_by_fixture: dict[str, list[dict]] = {
        k: v.get("method_extensions", [])
        for k, v in extensions_data.items()
        if not k.startswith("_")
    }

    fixture_files = sorted(PYVESYNC_FIXTURES_DIR.glob("*.yaml"))
    if not fixture_files:
        raise SystemExit(f"no .yaml fixtures in {PYVESYNC_FIXTURES_DIR}")

    fixture_to_methods: dict[str, list[dict]] = {}
    for fixture_path in fixture_files:
        fixture_name = fixture_path.stem
        pyvesync_methods = load_pyvesync_methods(fixture_path)
        extensions = extensions_by_fixture.get(fixture_name, [])
        merged = merge_extensions(pyvesync_methods, extensions)
        fixture_to_methods[fixture_name] = merged

    generated_block = render_groovy_block(fixture_to_methods)

    driver_source = DRIVER_FILE.read_text(encoding="utf-8")
    new_source = patch_driver(driver_source, generated_block)

    if new_source == driver_source:
        print(f"{DRIVER_FILE.name}: FIXTURE_OPS already current "
              f"({len(fixture_to_methods)} fixtures, "
              f"{sum(len(m) for m in fixture_to_methods.values())} ops total)")
        return 0

    if check_only:
        print(f"{DRIVER_FILE.name}: WOULD UPDATE — "
              f"{len(fixture_to_methods)} fixtures, "
              f"{sum(len(m) for m in fixture_to_methods.values())} ops total")
        print()
        print("Generated block preview:")
        print()
        print(generated_block)
        return 1  # non-zero so CI/precommit can detect uncommitted regen

    DRIVER_FILE.write_text(new_source, encoding="utf-8")
    print(f"{DRIVER_FILE.name}: regenerated FIXTURE_OPS — "
          f"{len(fixture_to_methods)} fixtures, "
          f"{sum(len(m) for m in fixture_to_methods.values())} ops total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
