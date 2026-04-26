"""
doc_sync.py — check that Drivers/Levoit/readme.md includes every driver file.

Rule 19: every .groovy file in Drivers/Levoit/ (except intentionally-excluded files)
must appear in the driver list table in readme.md. A new driver added without a
readme entry is a FAIL.

Heuristic: scan readme.md for lines containing the driver filename (as a link or
plain text). The table format used in this codebase has one row per file:
  | `LevoitVital200S.groovy` | ... |
or just the bare filename in a markdown link.
"""

import re
from pathlib import Path


# Files intentionally excluded from the readme table check
README_EXCLUDED = {
    "Notification Tile.groovy",  # not a VeSync device driver, documented separately if needed
}


def _making_finding(severity, rule_id, title, file_str, lineno, context, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": file_str,
        "line": lineno,
        "context": context,
        "why": why,
        "fix": fix,
    }


def check_rule19_doc_sync(repo_root: Path, config: dict):
    """
    Check that readme.md includes every driver file in its table.
    Returns list of findings.
    """
    findings = []

    readme_path = repo_root / "Drivers" / "Levoit" / "readme.md"
    drivers_dir = repo_root / "Drivers" / "Levoit"

    if not readme_path.exists():
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE19_readme_missing",
            title="Drivers/Levoit/readme.md not found",
            file_str="Drivers/Levoit/readme.md",
            lineno=1,
            context="",
            why="Rule 19 (Doc Sync): readme.md is the user-facing documentation for driver "
                "installation. It must exist.",
            fix="Create Drivers/Levoit/readme.md with a driver table.",
        ))
        return findings

    readme_text = readme_path.read_text(encoding='utf-8', errors='replace')
    readme_lower = readme_text.lower()

    actual_drivers = {f.name for f in drivers_dir.glob('*.groovy')}
    expected_in_readme = actual_drivers - README_EXCLUDED

    for fname in sorted(expected_in_readme):
        # Check for the filename (case-insensitive) anywhere in the readme
        if fname.lower() not in readme_lower:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE19_driver_not_in_readme",
                title=f"Driver not mentioned in readme.md: {fname}",
                file_str="Drivers/Levoit/readme.md",
                lineno=1,
                context=f"    Missing: {fname}",
                why="Rule 19 (Doc Sync): every supported driver must appear in the "
                    "Drivers/Levoit/readme.md table so HPM users know what to install.",
                fix=f"Add a row for {fname} to the installation table in readme.md, "
                    "plus add the device to the events/attributes table.",
            ))

    return findings
