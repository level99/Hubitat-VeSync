#!/usr/bin/env python3
"""Detect whether pyvesync has a newer release tag than the one pinned in this repo.

Reads the pinned tag from tests/pyvesync-fixtures/README.md (parses the
`**pyvesync tag: X.Y.Z (...)**` line). Fetches the latest release tag from
GitHub. If newer, sets workflow outputs so downstream steps run.

Workflow outputs (written to $GITHUB_OUTPUT):
  has-update : "true" if a newer tag exists, else "false"
  old-tag    : the currently-pinned tag
  new-tag    : the latest tag (if newer; else equal to old-tag)

Local dry-run: prints the same key=value pairs to stdout when $GITHUB_OUTPUT is unset.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
README = REPO_ROOT / "tests" / "pyvesync-fixtures" / "README.md"
LATEST_RELEASE_URL = "https://api.github.com/repos/webdjoe/pyvesync/releases/latest"
PINNED_TAG_RE = re.compile(r"\*\*pyvesync tag:\s*([0-9]+(?:\.[0-9]+)+)")


def read_pinned_tag() -> str:
    text = README.read_text(encoding="utf-8")
    m = PINNED_TAG_RE.search(text)
    if not m:
        raise SystemExit(f"could not parse pinned tag from {README}")
    return m.group(1)


def fetch_latest_tag() -> str:
    req = urllib.request.Request(
        LATEST_RELEASE_URL,
        headers={"Accept": "application/vnd.github+json"},
    )
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return data["tag_name"]


def version_tuple(v: str) -> tuple[int, ...]:
    parts = []
    for chunk in v.split("."):
        m = re.match(r"^(\d+)", chunk)
        parts.append(int(m.group(1)) if m else 0)
    return tuple(parts)


def write_outputs(outputs: dict[str, str]) -> None:
    out_path = os.environ.get("GITHUB_OUTPUT")
    if not out_path:
        for k, v in outputs.items():
            print(f"{k}={v}")
        return
    with open(out_path, "a", encoding="utf-8") as f:
        for k, v in outputs.items():
            f.write(f"{k}={v}\n")


def main() -> int:
    old = read_pinned_tag()
    new = fetch_latest_tag()
    if version_tuple(new) > version_tuple(old):
        print(f"Update available: {old} -> {new}")
        write_outputs({"has-update": "true", "old-tag": old, "new-tag": new})
    else:
        print(f"No update needed (pinned: {old}, latest: {new})")
        write_outputs({"has-update": "false", "old-tag": old, "new-tag": old})
    return 0


if __name__ == "__main__":
    sys.exit(main())
