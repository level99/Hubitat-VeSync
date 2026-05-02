#!/usr/bin/env python3
"""Detect new Levoit device codes added between two pyvesync release tags.

Usage: detect_devices.py <old_tag> <new_tag>

Fetches src/pyvesync/device_map.py from each tag, extracts string literals
that look like Levoit device codes (prefix LAP-/LUH-/LEH-/LTF-/LPF-/LV-, or
literal codes like Core200S, Classic200S, Dual200S, ...), and reports codes
present in <new_tag> but not <old_tag>.

Workflow outputs (written to $GITHUB_OUTPUT):
  new-devices : "true" if any new Levoit codes detected, else "false"
  new-count   : count of new codes (string)

Side effect: writes new-devices.md (markdown body suitable for `gh issue create
--body-file new-devices.md`). Empty file when no new devices found.
"""

from __future__ import annotations

import os
import re
import sys
import urllib.request
from pathlib import Path

DEVICE_MAP_URL = "https://raw.githubusercontent.com/webdjoe/pyvesync/{tag}/src/pyvesync/device_map.py"
DIFF_URL = "https://github.com/webdjoe/pyvesync/compare/{old}...{new}"

# Prefixes that indicate Levoit / OEM-rebrand-of-Levoit hardware in pyvesync's device_map.
LEVOIT_PREFIXES: tuple[str, ...] = ("LAP-", "LUH-", "LEH-", "LTF-", "LPF-", "LV-")

# Literal model codes used by older Levoit devices (pre-prefix-naming convention).
LITERAL_CODES: frozenset[str] = frozenset({
    "Core200S", "Core300S", "Core400S", "Core600S",
    "Classic200S", "Classic300S",
    "Dual200S", "Vital200S",
})

STRING_RE = re.compile(r"['\"]([A-Za-z0-9._-]+)['\"]")
MAP_OPEN_RE = re.compile(r"(\w+Map)\(", re.DOTALL)


def fetch_text(url: str, token: str | None) -> str:
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def is_levoit(code: str) -> bool:
    if any(code.startswith(p) for p in LEVOIT_PREFIXES):
        return True
    return code in LITERAL_CODES


def extract_levoit_codes(source: str) -> set[str]:
    return {m.group(1) for m in STRING_RE.finditer(source) if is_levoit(m.group(1))}


def find_block_for_code(source: str, code: str) -> dict[str, str]:
    """Best-effort lookup of the surrounding `<X>Map(...)` block for a given code.

    Returns {map_type, class_name, model_name, model_display} where available.
    Returns {} if no enclosing Map block is found.
    """
    quoted = re.compile(rf"['\"]({re.escape(code)})['\"]")
    m = quoted.search(source)
    if not m:
        return {}
    pos = m.start()
    last_open = None
    for bm in MAP_OPEN_RE.finditer(source[:pos]):
        last_open = bm
    if not last_open:
        return {}
    start = last_open.end()
    depth = 1
    i = start
    while i < len(source) and depth > 0:
        ch = source[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        i += 1
    block = source[last_open.start():i]
    info: dict[str, str] = {"map_type": last_open.group(1)}
    for key in ("class_name", "model_name", "model_display"):
        km = re.search(rf"{key}\s*=\s*['\"]([^'\"]+)['\"]", block)
        if km:
            info[key] = km.group(1)
    return info


def write_outputs(outputs: dict[str, str]) -> None:
    out_path = os.environ.get("GITHUB_OUTPUT")
    if not out_path:
        for k, v in outputs.items():
            print(f"{k}={v}")
        return
    with open(out_path, "a", encoding="utf-8") as f:
        for k, v in outputs.items():
            f.write(f"{k}={v}\n")


def render_issue_body(old_tag: str, new_tag: str, added: list[str], new_src: str) -> str:
    n = len(added)
    plural = "s" if n != 1 else ""
    lines: list[str] = []
    lines.append(
        f"Pyvesync added support for {n} new Levoit code{plural} between "
        f"`{old_tag}` and `{new_tag}`."
    )
    lines.append("")
    lines.append("## New device codes")
    lines.append("")
    lines.append("| Code | pyvesync class | Marketing name | Display |")
    lines.append("|---|---|---|---|")
    for code in added:
        info = find_block_for_code(new_src, code)
        cls = info.get("class_name", "—")
        mn = info.get("model_name", "—")
        md = info.get("model_display", "—")
        lines.append(f"| `{code}` | `{cls}` | {mn} | {md} |")
    lines.append("")
    lines.append("## Triage")
    lines.append("")
    lines.append(
        "- **Fold-in candidate** — if the pyvesync class matches an existing "
        "driver in this fork, extend that driver's `deviceType()` switch to "
        "recognize the new code."
    )
    lines.append(
        "- **New driver candidate** — if the pyvesync class is new, follow the "
        "new-device flow in `CONTRIBUTING.md` (capture deviceType + status "
        "payload + bypassV2 method names)."
    )
    lines.append("")
    lines.append(f"Pyvesync diff: {DIFF_URL.format(old=old_tag, new=new_tag)}")
    lines.append("")
    lines.append("_Auto-filed by `.github/workflows/pyvesync-tracker.yml`._")
    return "\n".join(lines) + "\n"


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: detect_devices.py <old_tag> <new_tag>", file=sys.stderr)
        return 2
    old_tag, new_tag = sys.argv[1], sys.argv[2]
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")

    print(f"Fetching device_map.py for {old_tag} and {new_tag}...")
    old_src = fetch_text(DEVICE_MAP_URL.format(tag=old_tag), token)
    new_src = fetch_text(DEVICE_MAP_URL.format(tag=new_tag), token)
    old_codes = extract_levoit_codes(old_src)
    new_codes = extract_levoit_codes(new_src)
    added = sorted(new_codes - old_codes)

    print(f"Old: {len(old_codes)} Levoit codes; New: {len(new_codes)}; Added: {len(added)}")
    for c in added:
        print(f"  + {c}")

    out_md = Path("new-devices.md")
    if not added:
        out_md.write_text("", encoding="utf-8")
        write_outputs({"new-devices": "false", "new-count": "0"})
        return 0

    body = render_issue_body(old_tag, new_tag, added, new_src)
    out_md.write_text(body, encoding="utf-8")
    print(f"Wrote new-devices.md ({len(body)} bytes)")
    write_outputs({"new-devices": "true", "new-count": str(len(added))})
    return 0


if __name__ == "__main__":
    sys.exit(main())
