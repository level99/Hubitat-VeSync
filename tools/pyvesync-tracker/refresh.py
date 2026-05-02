#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# dependencies = ["pyyaml"]
# ///
"""Refresh vendored pyvesync fixtures to a target release tag.

Usage: uv run --python 3.12 tools/pyvesync-tracker/refresh.py <new_tag>

For each of the 19 vendored fixtures listed in tests/pyvesync-fixtures/README.md:

  1. Fetch the upstream YAML from
     https://raw.githubusercontent.com/webdjoe/pyvesync/<new_tag>/src/tests/api/<subdir>/<file>.yaml
  2. Parse it and extract only the `<op>.json_object.payload` block per
     top-level operation (this is the only structure PyvesyncCoverageSpec reads;
     the rest of the upstream envelope — accountID, appVersion, traceId, etc. — is
     stripped to keep the vendored copy diff-readable).
  3. Re-emit each op as its own YAML doc with a blank line separator (matches the
     existing vendored format).
  4. Preserve the existing local file's first-line marketing-name comment and the
     "Original path: ..." comment; replace the "Tag: ..." line with the new tag/
     commit/date.

Updates the pinned-tag and source-URL references in tests/pyvesync-fixtures/README.md.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_DIR = REPO_ROOT / "tests" / "pyvesync-fixtures"
README = FIXTURES_DIR / "README.md"

# (pyvesync subdir, basename) for each vendored fixture. Mirrors the table in README.md.
FIXTURES: list[tuple[str, str]] = [
    ("vesyncpurifier", "Core200S"),
    ("vesyncpurifier", "Core300S"),
    ("vesyncpurifier", "Core400S"),
    ("vesyncpurifier", "Core600S"),
    ("vesyncpurifier", "LAP-V102S"),
    ("vesyncpurifier", "LAP-V201S"),
    ("vesyncpurifier", "LAP-B851S-WUS"),
    ("vesyncpurifier", "EL551S"),
    ("vesynchumidifier", "Classic200S"),
    ("vesynchumidifier", "Classic300S"),
    ("vesynchumidifier", "Dual200S"),
    ("vesynchumidifier", "LUH-A602S-WUS"),
    ("vesynchumidifier", "LUH-A603S-WUS"),
    ("vesynchumidifier", "LUH-O451S-WUS"),
    ("vesynchumidifier", "LUH-M101S-WUS"),
    ("vesynchumidifier", "LEH-B381S"),
    ("vesynchumidifier", "LEH-S601S"),
    ("vesyncfan", "LTF-F422S"),
    ("vesyncfan", "LPF-R423S"),
]

RAW_URL = "https://raw.githubusercontent.com/webdjoe/pyvesync/{tag}/src/tests/api/{subdir}/{name}.yaml"
COMMIT_API = "https://api.github.com/repos/webdjoe/pyvesync/commits/{tag}"


def fetch_text(url: str, token: str | None) -> str:
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def fetch_commit_meta(tag: str, token: str | None) -> tuple[str, str]:
    req = urllib.request.Request(
        COMMIT_API.format(tag=tag),
        headers={"Accept": "application/vnd.github+json"},
    )
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    sha = data["sha"][:7]
    iso = data.get("commit", {}).get("committer", {}).get("date", "")
    date = iso.split("T", 1)[0] if iso else ""
    return sha, date


def trim_to_payloads(upstream_text: str) -> dict[str, dict]:
    """Parse upstream pyvesync YAML and return {op: {json_object: {payload: ...}}}.

    Skips any top-level op that lacks `json_object.payload` (rare but possible
    for ops that only document the response envelope).
    """
    raw = yaml.safe_load(upstream_text) or {}
    out: dict[str, dict] = {}
    for op_name, op_body in raw.items():
        payload = (op_body or {}).get("json_object", {}).get("payload")
        if payload is None:
            continue
        out[op_name] = {"json_object": {"payload": payload}}
    return out


def emit_yaml_with_blanks(payloads: dict[str, dict]) -> str:
    """Emit each top-level op as its own YAML doc, joined with blank lines.

    Uses sort_keys=False to preserve op order from upstream and field order
    inside each payload. allow_unicode=True keeps em-dashes etc. as UTF-8.
    """
    parts = []
    for op_name, op_data in payloads.items():
        chunk = yaml.safe_dump(
            {op_name: op_data},
            default_flow_style=False,
            sort_keys=False,
            indent=2,
            allow_unicode=True,
        )
        parts.append(chunk.rstrip("\n"))
    return "\n\n".join(parts) + "\n"


def rewrite_header(existing_text: str, new_tag: str, sha: str, date: str) -> str | None:
    """Preserve the existing leading comment block (all `#` and blank lines up
    to the first YAML content line), updating only the `# Tag: ...` line.

    Returns None if the file has no leading `# Tag:` line to update — caller
    falls back to a minimal 4-line header.
    """
    lines = existing_text.splitlines()
    header_end = 0
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "" or line.startswith("#"):
            header_end = i + 1
            continue
        break
    if header_end == 0:
        return None
    header = lines[:header_end]
    while header and header[-1].strip() == "":
        header.pop()
    tag_re = re.compile(r"^#\s*Tag:")
    new_tag_line = f"# Tag: {new_tag} (commit {sha} — {date})"
    found = False
    for i, line in enumerate(header):
        if tag_re.match(line):
            header[i] = new_tag_line
            found = True
            break
    if not found:
        return None
    return "\n".join(header) + "\n\n"


def update_readme(new_tag: str, sha: str, date: str) -> None:
    text = README.read_text(encoding="utf-8")
    pinned_re = re.compile(r"\*\*pyvesync tag:[^*]+\*\*")
    replacement = f"**pyvesync tag: {new_tag} (commit {sha} — {date})**"
    new_text, n = pinned_re.subn(replacement, text, count=1)
    if n == 0:
        raise SystemExit("could not locate pinned-tag line in README")
    new_text = re.sub(
        r"https://github\.com/webdjoe/pyvesync/tree/[^/]+/src/tests/api/",
        f"https://github.com/webdjoe/pyvesync/tree/{new_tag}/src/tests/api/",
        new_text,
    )
    README.write_text(new_text, encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: refresh.py <new_tag>", file=sys.stderr)
        return 2
    new_tag = sys.argv[1]
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")

    sha, date = fetch_commit_meta(new_tag, token)
    print(f"Refreshing fixtures to pyvesync {new_tag} (commit {sha}, {date})...")

    for subdir, name in FIXTURES:
        url = RAW_URL.format(tag=new_tag, subdir=subdir, name=name)
        dest = FIXTURES_DIR / f"{name}.yaml"
        if not dest.exists():
            print(f"  SKIP {name}.yaml (not in vendored set — local file missing)")
            continue
        try:
            upstream = fetch_text(url, token)
        except Exception as exc:
            print(f"  FAILED {name}.yaml ({url}): {exc}", file=sys.stderr)
            return 1
        payloads = trim_to_payloads(upstream)
        if not payloads:
            print(
                f"  FAILED {name}.yaml: trim produced zero ops — upstream may "
                f"have restructured (no `<op>.json_object.payload` blocks). "
                f"Inspect {url}",
                file=sys.stderr,
            )
            return 1
        body = emit_yaml_with_blanks(payloads)
        existing = dest.read_text(encoding="utf-8")
        header = rewrite_header(existing, new_tag, sha, date)
        if header is None:
            print(f"  WARN {name}.yaml has no recognized header block — using minimal header")
            header = (
                f"# pyvesync canonical request fixtures — {name}\n"
                f"# Source: https://github.com/webdjoe/pyvesync\n"
                f"# Tag: {new_tag} (commit {sha} — {date})\n"
                f"# Original path: src/tests/api/{subdir}/{name}.yaml\n\n"
            )
        dest.write_text(header + body, encoding="utf-8")
        print(f"  refreshed {name}.yaml")

    update_readme(new_tag, sha, date)
    print(f"Updated README pinned reference to {new_tag} (commit {sha}, {date})")

    # Regenerate the FIXTURE_OPS @Field block in VeSyncIntegrationVirtual.groovy
    # so the virtual test parent's payload-validation map stays synchronized
    # with the refreshed pyvesync fixtures. Imports the regenerator as a module
    # to share refresh.py's already-loaded pyyaml env (avoids spawning a nested
    # uv subprocess just to re-resolve the same PEP 723 deps).
    regenerator_path = REPO_ROOT / "tools" / "regenerate_virtual_parent.py"
    if regenerator_path.is_file():
        print("Regenerating FIXTURE_OPS in VeSyncIntegrationVirtual.groovy...")
        sys.path.insert(0, str(REPO_ROOT / "tools"))
        try:
            import regenerate_virtual_parent  # noqa: PLC0415 — deferred import
            rc = regenerate_virtual_parent.main()
            if rc != 0:
                print(f"Regenerator returned non-zero ({rc})", file=sys.stderr)
                return rc
        finally:
            sys.path.pop(0)
    else:
        print(f"  SKIP regenerator (not found at {regenerator_path})")

    return 0


if __name__ == "__main__":
    sys.exit(main())
