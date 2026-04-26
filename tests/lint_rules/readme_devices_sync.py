"""
readme_devices_sync.py — cross-check Drivers/Levoit/*.groovy driver names against
the "Supported devices" table in README.md.

Rule 21 checks:
  - Every .groovy file in Drivers/Levoit/ (except intentionally-excluded files)
    has its driver display name present in the README.md "## Supported devices"
    section.

Rationale: when a contributor adds a new driver file, the README table is the
user-visible discovery surface.  Forgetting to add a row means users won't know
the device is supported.  This check closes that gap by making the drift a lint
FAIL rather than a silent omission.

Normalization note: driver definition(name: ...) values in Core-line drivers use
"Core200S" (no internal space) while the README table writes "Core 200S" (with
space) in its bold cell text.  Both sides are whitespace-collapsed (all \s+
removed) before the substring comparison, so "Core200S" == "Core 200S" after
normalization.  Markdown bold markers (**) are stripped from the README cell text
before comparison.
"""

import re
from pathlib import Path


# ---------------------------------------------------------------------------
# Files intentionally excluded from the README "Supported devices" check.
# ---------------------------------------------------------------------------

EXCLUDED_FILES = {
    "Notification Tile.groovy",    # utility tile renderer, not a VeSync device driver
    "LevoitCore200S Light.groovy", # night-light child of Core 200S; covered by parent's row
    "VeSyncIntegration.groovy",    # parent/integration driver, not a per-device child
}


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

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


def _extract_definition_block(source: str):
    """
    Return (block_interior, start_line) for the definition(...) named-argument span,
    or (None, 1) if not found.

    Matches from 'definition(' through the first closing ')' that is followed
    (possibly with whitespace) by '{'.  Handles multi-line blocks and description:
    strings containing parentheses.

    Returns the interior of the parens so callers can regex-search it for named args.
    """
    pattern = re.compile(r'(?s)definition\s*\((.+?)\)\s*\{')
    m = pattern.search(source)
    if not m:
        return None, 1
    block_interior = m.group(1)
    start_pos = m.start()
    start_line = source[:start_pos].count('\n') + 1
    return block_interior, start_line


def _extract_name_from_block(block_interior: str):
    """
    Return the name string from inside a definition() block, or None.
    Handles both single-quoted and double-quoted values.
    """
    m = re.search(r'name\s*:\s*"([^"]+)"', block_interior)
    if m:
        return m.group(1)
    m = re.search(r"name\s*:\s*'([^']+)'", block_interior)
    if m:
        return m.group(1)
    return None


def _normalize(s: str) -> str:
    """Strip Markdown bold (**), then remove all whitespace for comparison."""
    s = s.replace('**', '')
    s = re.sub(r'\s+', '', s)
    return s


def _extract_supported_devices_section(readme_text: str):
    """
    Return (section_text, section_start_line) for the block bounded by
    '## Supported devices' and the next '##' heading (or end of file).

    Returns (None, 0) if the section is not found.
    """
    lines = readme_text.splitlines()
    in_section = False
    section_lines = []
    section_start_line = 0

    for i, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not in_section:
            # Case-insensitive match to tolerate minor heading variations
            if re.match(r'^##\s+Supported devices', stripped, re.IGNORECASE):
                in_section = True
                section_start_line = i
                section_lines.append(line)
        else:
            # Next ##-level heading ends the section (but ### or #### do not)
            if re.match(r'^##\s+\S', stripped) and not re.match(r'^###', stripped):
                break
            section_lines.append(line)

    if not section_lines:
        return None, 0

    return '\n'.join(section_lines), section_start_line


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule21_readme_devices_sync(repo_root: Path, config: dict):
    """
    Verify that every Drivers/Levoit/*.groovy driver (minus excluded files) has
    its definition(name: ...) value present in README.md's 'Supported devices'
    section.

    Returns list of findings in the standard lint finding format.
    """
    findings = []

    drivers_dir = repo_root / "Drivers" / "Levoit"
    readme_path = repo_root / "README.md"

    # --- Guard: README missing ---
    if not readme_path.exists():
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE21_readme_missing",
            title="README.md not found at repo root",
            file_str="README.md",
            lineno=1,
            context="",
            why="Rule 21 (README Devices Sync): README.md must exist and contain a "
                "'## Supported devices' section so users discovering the project know "
                "which models are supported.",
            fix="Create README.md with a '## Supported devices' table listing every "
                "device driver by display name.",
        ))
        return findings

    readme_text = readme_path.read_text(encoding='utf-8', errors='replace')

    # --- Guard: section missing ---
    section_text, section_start_line = _extract_supported_devices_section(readme_text)
    if section_text is None:
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE21_section_missing",
            title="README.md has no '## Supported devices' section",
            file_str="README.md",
            lineno=1,
            context="",
            why="Rule 21 (README Devices Sync): README.md must contain a "
                "'## Supported devices' section. Without it, users cannot determine "
                "which models are supported before installing.",
            fix="Add a '## Supported devices' heading followed by a markdown table with "
                "one row per device driver. See the current README for the expected format.",
        ))
        return findings

    # Normalized section text for substring searching
    section_normalized = _normalize(section_text)

    # --- Guard: drivers directory missing ---
    if not drivers_dir.exists():
        # Rule 18 already flags this; don't double-report
        return findings

    # --- Check each driver file ---
    for driver_path in sorted(drivers_dir.glob('*.groovy')):
        if driver_path.name in EXCLUDED_FILES:
            continue

        try:
            source = driver_path.read_text(encoding='utf-8', errors='replace')
        except (OSError, IOError):
            continue  # unreadable files caught by per-file rules

        rel_path = str(driver_path.relative_to(repo_root)).replace('\\', '/')

        block_interior, def_lineno = _extract_definition_block(source)
        if block_interior is None:
            # No definition() block -- not a conventional driver; skip silently.
            continue

        driver_name = _extract_name_from_block(block_interior)
        if driver_name is None:
            # No name: field -- version_lockstep or other rules will flag structural issues.
            continue

        driver_name_normalized = _normalize(driver_name)

        if driver_name_normalized not in section_normalized:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE21_driver_not_in_readme",
                title=f"Driver not listed in README.md 'Supported devices' table: {driver_path.name}",
                file_str=rel_path,
                lineno=def_lineno,
                context=f'    definition(name: "{driver_name}", ...)',
                why="Rule 21 (README Devices Sync): every driver in Drivers/Levoit/ should be "
                    "listed in README.md's 'Supported devices' table so users discovering the "
                    "project know which models are supported. This driver was added but the "
                    "README table was not updated.",
                fix=f"Add a row to the '## Supported devices' table in README.md with this "
                    f"driver's display name (matching the definition(name: ...) field: "
                    f'"{driver_name}") and a brief capability summary. '
                    "See existing rows for format.",
            ))

    return findings
