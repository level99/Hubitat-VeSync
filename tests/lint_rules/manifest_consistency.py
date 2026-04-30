"""
manifest_consistency.py — cross-check levoitManifest.json against Drivers/Levoit/.

Rule 18 checks:
  - Every .groovy file in Drivers/Levoit/ has a manifest entry
  - Every manifest entry points to an existing file
  - UUIDs are unique across entries
  - File paths in manifest URLs map to real local files (basename matching)
"""

import json
import re
from pathlib import Path

from lint_rules._helpers import is_library_file


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


# Files intentionally excluded from the manifest (not installable drivers)
MANIFEST_EXCLUDED = {
    "Notification Tile.groovy",  # utility tile renderer, not a VeSync device driver
}


def check_rule18_manifest_consistency(repo_root: Path, config: dict):
    """
    Cross-check levoitManifest.json entries against Drivers/Levoit/*.groovy.
    Returns list of findings (dicts in standard format).
    """
    findings = []

    manifest_path = repo_root / "levoitManifest.json"
    drivers_dir = repo_root / "Drivers" / "Levoit"

    if not manifest_path.exists():
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE18_manifest_missing",
            title="levoitManifest.json not found",
            file_str="levoitManifest.json",
            lineno=1,
            context="",
            why="Rule 18 (Manifest Consistency): levoitManifest.json must exist for HPM to "
                "distribute this driver pack.",
            fix="Create levoitManifest.json at the repo root.",
        ))
        return findings

    try:
        manifest_text = manifest_path.read_text(encoding='utf-8')
        manifest = json.loads(manifest_text)
    except json.JSONDecodeError as e:
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE18_manifest_invalid_json",
            title=f"levoitManifest.json is not valid JSON: {e}",
            file_str="levoitManifest.json",
            lineno=1,
            context="",
            why="Rule 18 (Manifest Consistency): invalid JSON will prevent HPM from parsing "
                "the manifest.",
            fix="Fix the JSON syntax error in levoitManifest.json.",
        ))
        return findings

    # Build set of driver filenames from manifest (extract basename from location URL)
    manifest_drivers = manifest.get('drivers', [])
    manifest_filenames = set()
    manifest_ids = []
    for entry in manifest_drivers:
        loc = entry.get('location', '')
        # Extract filename from URL (last path segment, URL-decoded)
        basename = loc.split('/')[-1]
        # URL-decode %20 and other common escapes
        basename = basename.replace('%20', ' ')
        manifest_filenames.add(basename)
        uid = entry.get('id', '')
        manifest_ids.append(uid)

    # All .groovy files in Drivers/Levoit/
    if not drivers_dir.exists():
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE18_drivers_dir_missing",
            title="Drivers/Levoit/ directory not found",
            file_str="levoitManifest.json",
            lineno=1,
            context="",
            why="Rule 18 (Manifest Consistency): expected Drivers/Levoit/ directory not found.",
            fix="Ensure the repository has a Drivers/Levoit/ directory with .groovy files.",
        ))
        return findings

    actual_driver_paths = list(drivers_dir.glob('*.groovy'))

    # Auto-detect library files (those using library() block) — they are registered
    # under manifest "libraries" not "drivers", so skip them from the drivers check.
    library_filenames = set()
    for fp in actual_driver_paths:
        try:
            src = fp.read_text(encoding='utf-8', errors='replace')
            if is_library_file(src):
                library_filenames.add(fp.name)
        except OSError:
            pass

    actual_drivers = {f.name for f in actual_driver_paths}

    # Drivers that should be in manifest (exclude intentionally-omitted files).
    # Merge hardcoded exclusions with config-driven extras and auto-detected libraries.
    config_excluded = set(config.get('manifest_excluded_files', []))
    expected_in_manifest = actual_drivers - MANIFEST_EXCLUDED - config_excluded - library_filenames

    # Check: every driver file has a manifest entry
    for fname in sorted(expected_in_manifest):
        if fname not in manifest_filenames:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE18_driver_not_in_manifest",
                title=f"Driver file missing from manifest: {fname}",
                file_str="levoitManifest.json",
                lineno=1,
                context=f"    File exists: Drivers/Levoit/{fname}",
                why="Rule 18 (Manifest Consistency): every driver file must have a corresponding "
                    "entry in levoitManifest.json. HPM users won't see it otherwise.",
                fix=f'Add an entry for Drivers/Levoit/{fname} to levoitManifest.json with a '
                    'unique UUID, name, namespace, and location URL.',
            ))

    # Check: every manifest entry points to an existing file
    for entry in manifest_drivers:
        loc = entry.get('location', '')
        basename = loc.split('/')[-1].replace('%20', ' ')
        name = entry.get('name', '(unnamed)')
        if basename and basename not in actual_drivers:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE18_manifest_entry_missing_file",
                title=f"Manifest entry '{name}' points to non-existent file: {basename}",
                file_str="levoitManifest.json",
                lineno=1,
                context=f"    location: {loc}",
                why="Rule 18 (Manifest Consistency): a manifest entry pointing to a file that "
                    "doesn't exist will cause HPM install failures.",
                fix=f"Either create Drivers/Levoit/{basename} or remove the stale manifest entry.",
            ))

    # Check: UUIDs unique
    seen_ids = {}
    for i, uid in enumerate(manifest_ids):
        if not uid:
            entry_name = manifest_drivers[i].get('name', f'entry #{i}')
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE18_missing_uuid",
                title=f"Manifest entry '{entry_name}' has no UUID",
                file_str="levoitManifest.json",
                lineno=1,
                context="",
                why="Rule 18 (Manifest Consistency): each manifest entry needs a unique UUID "
                    "for HPM to track individual driver installs.",
                fix="Add a UUID 'id' field (generate with uuidgen or an online tool).",
            ))
        elif uid in seen_ids:
            entry_name = manifest_drivers[i].get('name', f'entry #{i}')
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE18_duplicate_uuid",
                title=f"Duplicate UUID '{uid}' in manifest (entries: '{seen_ids[uid]}' and '{entry_name}')",
                file_str="levoitManifest.json",
                lineno=1,
                context="",
                why="Rule 18 (Manifest Consistency): duplicate UUIDs will confuse HPM's "
                    "install tracking.",
                fix="Generate a fresh UUID for one of the duplicate entries.",
            ))
        else:
            seen_ids[uid] = manifest_drivers[i].get('name', f'entry #{i}')

    return findings
