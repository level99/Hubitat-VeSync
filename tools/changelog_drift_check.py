#!/usr/bin/env python3
# /// script
# requires-python = ">=3.12"
# ///
"""CHANGELOG drift check — flag feat/fix commits not represented in [Unreleased].

Used as a pre-flight step by `.claude/commands/cut-release.md` to catch the
"feature shipped but CHANGELOG bullet skipped" drift pattern. Surfaced
2026-04-30 during v2.4 development when 4 user-meaningful commits had no
matching [Unreleased] bullets.

Algorithm:
1. Get the last release tag (`git describe --tags --abbrev=0`).
2. List all commits since that tag with `git log <tag>..HEAD`.
3. Filter to commits with subjects starting `feat(` / `fix(` / `feat:` /
   `fix:` (per Conventional Commits — these are user-meaningful types).
4. Skip `chore:` / `docs:` / `test:` / `refactor:` / `style:` / `build:` /
   `ci:` types — internal, don't typically warrant CHANGELOG entries.
5. For each remaining commit, extract distinctive keyword tokens (>=4 chars,
   excluding common stopwords) from the subject after the type/scope prefix.
6. Read CHANGELOG.md's [Unreleased] section text.
7. For each commit, check if ANY keyword token appears in [Unreleased]
   (case-insensitive). If none match, flag the commit as potentially missing.
8. Report:
   - Status line (n commits in cycle, m missing / clean)
   - Per-missing-commit: subject + suggestion to add a bullet

Returns exit 0 always (informational; the cut-release skill decides whether
to fail on findings or just surface them — currently surfaces and lets
maintainer decide).

Usage:
    uv run --python 3.12 tools/changelog_drift_check.py
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = REPO_ROOT / "CHANGELOG.md"

# Conventional Commits types that DO warrant CHANGELOG bullets
USER_FACING_TYPES = ("feat", "fix")

# Common stopwords to drop from keyword extraction
STOPWORDS = {
    "the", "and", "for", "with", "from", "into", "onto", "this", "that",
    "have", "will", "should", "would", "must", "shall", "but", "not",
    "does", "doesn", "isn", "wasn", "via", "when", "then", "than",
    "after", "before", "during", "while", "until", "since", "still",
    "always", "never", "also", "both", "either", "neither", "however",
    "v2", "v3", "release", "branch", "commit", "test", "tests", "spec",
    "specs", "code", "source", "file", "files", "method", "methods",
    "function", "functions", "class", "classes", "groovy", "python",
    "fork", "fixed", "bug", "added", "removed", "changed", "feat", "fix",
}


def get_last_tag() -> str | None:
    """Return the last git tag reachable from HEAD, or None if no tags exist."""
    try:
        result = subprocess.run(
            ["git", "describe", "--tags", "--abbrev=0"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip() or None
    except subprocess.CalledProcessError:
        return None


def get_cycle_commits(last_tag: str) -> list[str]:
    """Return list of commit subjects since last_tag (excluding merges)."""
    result = subprocess.run(
        ["git", "log", f"{last_tag}..HEAD", "--no-merges", "--format=%s"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def is_user_facing(subject: str) -> bool:
    """True if commit subject starts with a CHANGELOG-worthy type prefix."""
    m = re.match(r"^([a-z]+)(\([^)]*\))?:", subject)
    if not m:
        return False
    return m.group(1) in USER_FACING_TYPES


def strip_type_prefix(subject: str) -> str:
    """Strip Conventional Commits type/scope prefix, returning the bare subject."""
    return re.sub(r"^[a-z]+(\([^)]*\))?:\s*", "", subject)


def extract_keywords(subject: str) -> list[str]:
    """Pull distinctive >=4-char tokens from a commit subject, lowercased,
    excluding stopwords and pure-numeric tokens.
    """
    bare = strip_type_prefix(subject).lower()
    # Strip non-alphanumeric (keep dashes for things like "BP18", "RULE27")
    tokens = re.findall(r"[a-z0-9]+(?:-[a-z0-9]+)*", bare)
    keep = []
    for tok in tokens:
        if len(tok) < 4:
            continue
        if tok in STOPWORDS:
            continue
        if tok.isdigit():
            continue
        keep.append(tok)
    return keep


def get_unreleased_section() -> str:
    """Extract the text content of CHANGELOG.md's [Unreleased] section."""
    if not CHANGELOG.is_file():
        return ""
    text = CHANGELOG.read_text(encoding="utf-8")
    m = re.search(
        r"^## \[Unreleased\].*?(?=^## \[|\Z)",
        text,
        re.MULTILINE | re.DOTALL,
    )
    return m.group(0).lower() if m else ""


def check_commit_represented(subject: str, unreleased_text: str) -> bool:
    """True if any keyword from the subject appears in [Unreleased] text."""
    keywords = extract_keywords(subject)
    if not keywords:
        # No distinctive keywords — can't validate; assume represented to
        # avoid false-positive nagging on pure boilerplate subjects
        return True
    return any(kw in unreleased_text for kw in keywords)


def main() -> int:
    last_tag = get_last_tag()
    if not last_tag:
        print("CHANGELOG drift check: no prior tags found — first release? Skipping.")
        return 0

    commits = get_cycle_commits(last_tag)
    if not commits:
        print(f"CHANGELOG drift check: no commits since {last_tag}.")
        return 0

    user_facing = [c for c in commits if is_user_facing(c)]
    if not user_facing:
        print(
            f"CHANGELOG drift check: {len(commits)} commits since {last_tag}, "
            f"none with feat: or fix: prefix. Nothing to validate."
        )
        return 0

    unreleased = get_unreleased_section()
    if not unreleased:
        print(
            "CHANGELOG drift check: WARN — [Unreleased] section not found in "
            "CHANGELOG.md. All user-facing commits below need bullets."
        )
        for c in user_facing:
            print(f"  - {c}")
        return 0

    missing = [c for c in user_facing if not check_commit_represented(c, unreleased)]

    if not missing:
        print(
            f"CHANGELOG drift check: {len(user_facing)} feat/fix commits since "
            f"{last_tag}; all appear represented in [Unreleased]."
        )
        return 0

    print(
        f"CHANGELOG drift check: WARN — {len(missing)} of {len(user_facing)} "
        f"feat/fix commits since {last_tag} may be missing from [Unreleased]:"
    )
    for c in missing:
        keywords = extract_keywords(c)
        kw_hint = ", ".join(keywords[:5]) if keywords else "(no distinctive keywords)"
        print(f"  - {c}")
        print(f"    keywords searched: {kw_hint}")
    print()
    print(
        "Action: review each commit above; if user-meaningful, add a bullet to "
        "CHANGELOG.md [Unreleased] before cutting. If internal (should have been "
        "chore/docs but mislabeled), continue."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
