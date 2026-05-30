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
    """Return a list of method entries for one pyvesync fixture file.

    Pyvesync fixture format (after our trim — see tests/pyvesync-fixtures/README.md):
        <op_name>:
          json_object:
            payload:
              method: <bypassV2 method name>
              data:
                <key1>: ...
                <key2>: ...
              source: APP

    Multiple ops may dispatch via the same method. There are two distinct cases:

      1. Same method, IDENTICAL key-set across all its ops (e.g. setSwitch's
         turn_on/turn_off both send [powerSwitch, switchIdx]). The op count is
         incidental; the validation shape is a single fixed key-set.

      2. Same method, DIFFERENT key-sets depending on which caller invoked it
         (e.g. setOscillationStatus on LPF-R423S — horizontal vs vertical vs
         range vs axis-off each send a legitimately different key-set). The
         union of these would accept key combinations no real caller ever sends,
         so a strict union-exact-match either always-fails (when the union is
         the validation target) or under-validates (accepting illegal combos).

    We therefore preserve the DISTINCT key-set variants per method rather than
    flattening to a union. The returned entry shape is:
        {"method": m, "dataKeyVariants": [sorted_keys_1, sorted_keys_2, ...]}
    where the variant list is order-stable (first-occurrence order in the
    fixture) and deduped. render_groovy_block() emits a single `dataKeys` Set
    for the common one-variant case and a `dataKeyVariants` list-of-Sets when a
    method has more than one legitimate key-set.
    """
    raw = yaml.safe_load(fixture_path.read_text(encoding="utf-8")) or {}
    # method -> list of distinct key-sets (tuples), in first-occurrence order
    by_method: dict[str, list[tuple[str, ...]]] = {}
    for op_name, op_body in raw.items():
        payload = (op_body or {}).get("json_object", {}).get("payload")
        if not payload:
            continue
        method = payload.get("method")
        if not method:
            continue
        data = payload.get("data") or {}
        keys = tuple(sorted(data.keys())) if isinstance(data, dict) else ()
        variants = by_method.setdefault(method, [])
        if keys not in variants:
            variants.append(keys)
    return [
        {"method": m, "dataKeyVariants": [list(v) for v in variants]}
        for m, variants in by_method.items()
    ]


def merge_extensions(
    pyvesync_methods: list[dict],
    extensions: list[dict],
    extension_comment: str | None = None,
) -> list[dict]:
    """Merge extension entries into the pyvesync method list.

    Extensions take precedence on method-name conflict (rare but possible).
    Extension methods are appended after pyvesync methods to preserve
    visual ordering hint in the generated output (pyvesync first, extensions
    after).

    Extension entries in virtual_parent_extensions.json use a single `dataKeys`
    list (no extension method currently has caller-varying key-sets). We
    normalize that to the internal one-variant `dataKeyVariants` shape so the
    renderer sees a uniform structure. If an extension ever needs multiple
    variants, it may supply `dataKeyVariants` (list of key lists) directly.
    """
    def to_variants(ext: dict) -> list[list[str]]:
        if "dataKeyVariants" in ext:
            return [sorted(v) for v in ext["dataKeyVariants"]]
        return [sorted(ext.get("dataKeys", []))]

    result = list(pyvesync_methods)
    seen_methods = {m["method"] for m in result}
    first_appended = True
    for ext in extensions:
        method = ext["method"]
        variants = to_variants(ext)
        if method in seen_methods:
            # Replace existing entry with extension's key variants
            for entry in result:
                if entry["method"] == method:
                    entry["dataKeyVariants"] = variants
                    break
        else:
            entry = {"method": method, "dataKeyVariants": variants}
            # Mark the first newly-appended extension method so the renderer can
            # emit a one-line provenance divider above it (sourced from the
            # extension block's _comment, kept in virtual_parent_extensions.json
            # so the generated block stays self-documenting AND idempotent).
            if first_appended:
                if extension_comment:
                    entry["extensionComment"] = extension_comment
                first_appended = False
            result.append(entry)
            seen_methods.add(method)
    return result


def render_groovy_block(fixture_to_methods: dict[str, list[dict]]) -> str:
    """Render the @Field FIXTURE_OPS map as a Groovy literal.

    Output format matches the existing hand-coded style: aligned dataKeys on
    each line, `as Set` suffix, comma-separated entries inside the per-fixture
    list.

    A method with a single legitimate key-set emits the established
    `dataKeys: [...] as Set` shape (the validator does an exact-match against
    it). A method whose key-set legitimately varies by caller (currently only
    setOscillationStatus on LPF-R423S) emits a `dataKeyVariants` list of Sets;
    the validator passes if the caller's keys match ANY listed variant.
    """
    def keys_set_literal(keys: list[str]) -> str:
        inner = ", ".join(f'"{k}"' for k in keys)
        return f"[{inner}] as Set"

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
            variants = entry["dataKeyVariants"]
            # Emit a provenance divider above the first extension-sourced method,
            # if the extension block carried a _comment. Kept here (not as a
            # manual edit) so a regen run reproduces it byte-for-byte.
            comment = entry.get("extensionComment")
            if comment:
                lines.append(f"        // {comment}")
            method_quoted = f'"{method}",'
            method_padded = method_quoted.ljust(max_method_len + 4)
            if len(variants) == 1:
                # Common case: one fixed key-set → exact-match `dataKeys`.
                line = (
                    f"        [methodName: {method_padded} "
                    f"dataKeys: {keys_set_literal(variants[0])}]"
                )
            else:
                # Multi-variant case: emit a list of allowed key-sets. The
                # validator accepts the payload if it matches ANY variant.
                variants_literal = ", ".join(
                    keys_set_literal(v) for v in variants
                )
                line = (
                    f"        [methodName: {method_padded} "
                    f"dataKeyVariants: [{variants_literal}]]"
                )
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
    # Skip the top-level _comment metadata key; only fixture entries are processed.
    extensions_by_fixture: dict[str, list[dict]] = {
        k: v.get("method_extensions", [])
        for k, v in extensions_data.items()
        if not k.startswith("_")
    }
    # Per-fixture extension provenance comment (emitted as a divider above the
    # first appended extension method); None when the entry has no _comment.
    extension_comment_by_fixture: dict[str, str | None] = {
        k: v.get("_comment")
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
        ext_comment = extension_comment_by_fixture.get(fixture_name)
        merged = merge_extensions(pyvesync_methods, extensions, ext_comment)
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
