"""
captureDiagnostics_presence.py — RULE28: verify Phase 5 diagnostics plumbing in every
Levoit driver.

Every driver under Drivers/Levoit/ must declare:
  1. ``#include level99.LevoitDiagnostics`` — top-level directive (before metadata)
  2. ``command "captureDiagnostics"`` — in the metadata definition block
  3. ``attribute "diagnostics", "string"`` — in the metadata definition block

Rationale:
  Phase 5 (v2.4) added a two-tier diagnostics system across all Levoit drivers.
  Any new driver added without these three declarations won't surface the
  captureDiagnostics command in the Hubitat UI and won't record errors in the ring
  buffer provided by LevoitDiagnosticsLib. Missing plumbing is silent — users see
  no compile error but can't access the diagnostic tooling.

Out-of-scope files (automatically skipped, PASS verdict):
  - LevoitDiagnosticsLib.groovy — the library itself; uses library() not definition()
  - VeSyncIntegrationVirtual.groovy — test harness, not user-facing
  - Notification Tile.groovy — utility driver, not a VeSync device
  - LevoitGeneric.groovy — has a native captureDiagnostics() implementation;
    intentionally does NOT #include the library to avoid method-name conflict

Severity: FAIL (BLOCKING). Consistent with Phase 5 convention.

Design: same minimal-viable presence check as RULE25 (bp16_watchdog) and RULE27
(bp18_null_guard). Three independent pattern checks; each produces its own finding
so the contributor knows exactly which piece is missing.
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Files that are in-scope for the driver directory but intentionally out-of-scope
# for this rule. Keyed by exact basename.
OUT_OF_SCOPE_BASENAMES = {
    "VeSyncIntegrationVirtual.groovy",  # test harness, not user-facing
    "Notification Tile.groovy",         # utility driver, not a VeSync device driver
    "LevoitGeneric.groovy",             # has native captureDiagnostics(); no #include to avoid method conflict
}

# Patterns for the three required declarations
INCLUDE_RE  = re.compile(r'(?m)^#include\s+level99\.LevoitDiagnostics\s*$')
COMMAND_RE  = re.compile(r'''command\s+["']captureDiagnostics["']''')
ATTR_RE     = re.compile(r'''attribute\s+["']diagnostics["']\s*,\s*["']string["']''', re.IGNORECASE)


def _making_finding(rule_id, title, file_str, why, fix):
    return {
        "severity": "FAIL",
        "rule_id": rule_id,
        "title": title,
        "file": file_str,
        "line": 0,
        "context": "",
        "why": why,
        "fix": fix,
    }


def check_rule28_capturediagnostics_presence(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE28 (Phase 5 diagnostics presence): Fail if a driver file is missing any of
    the three required captureDiagnostics declarations.

    Returns up to three FAIL findings — one per missing declaration.
    Returns empty list (PASS) for out-of-scope files.
    """
    findings = []

    # Only check .groovy files in the driver directory
    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    # Skip library files (library() block, not definition())
    if is_library_file(raw_text):
        return findings

    # Skip explicitly out-of-scope basenames
    if path.name in OUT_OF_SCOPE_BASENAMES:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    # Check 1: #include level99.LevoitDiagnostics
    if not INCLUDE_RE.search(raw_text):
        findings.append(_making_finding(
            rule_id="RULE28_missing_include_LevoitDiagnostics",
            title="Missing '#include level99.LevoitDiagnostics' directive (Phase 5)",
            file_str=file_rel,
            why=(
                "Phase 5 (v2.4) requires every Levoit driver to include the LevoitDiagnostics "
                "library so that captureDiagnostics() and recordError() are available at "
                "runtime. Without the #include, the Hubitat sandbox will throw "
                "MissingMethodException on any recordError() or captureDiagnostics() call."
            ),
            fix=(
                "Add '#include level99.LevoitDiagnostics' on a line by itself, immediately "
                "before the 'metadata {' block (after any comment blocks but before the "
                "metadata DSL call). Example:\n"
                "    #include level99.LevoitDiagnostics\n\n"
                "    metadata {\n"
                "        definition(...) { ... }"
            ),
        ))

    # Check 2: command "captureDiagnostics"
    if not COMMAND_RE.search(raw_text):
        findings.append(_making_finding(
            rule_id="RULE28_missing_command_captureDiagnostics",
            title="Missing 'command \"captureDiagnostics\"' in metadata block (Phase 5)",
            file_str=file_rel,
            why=(
                "Phase 5 (v2.4) requires every Levoit driver to expose captureDiagnostics "
                "as a Hubitat command so users can trigger it from the device page UI. "
                "Without the command declaration, the button won't appear even if the "
                "#include is present."
            ),
            fix=(
                "Add 'command \"captureDiagnostics\"' inside the definition() block, "
                "after the last existing command declaration. Example:\n"
                "        command \"toggle\"\n\n"
                "        attribute \"diagnostics\",     \"string\"\n"
                "        command \"captureDiagnostics\""
            ),
        ))

    # Check 3: attribute "diagnostics", "string"
    if not ATTR_RE.search(raw_text):
        findings.append(_making_finding(
            rule_id="RULE28_missing_attribute_diagnostics",
            title="Missing 'attribute \"diagnostics\", \"string\"' in metadata block (Phase 5)",
            file_str=file_rel,
            why=(
                "Phase 5 (v2.4) requires every Levoit driver to declare the 'diagnostics' "
                "string attribute so that Hubitat stores and displays the captureDiagnostics() "
                "output. Without the declaration, device.sendEvent(name:'diagnostics', ...) "
                "silently discards the value."
            ),
            fix=(
                "Add 'attribute \"diagnostics\", \"string\"' inside the definition() block. "
                "The type must be lowercase 'string' (not 'String') to match Hubitat's "
                "attribute type enum. Example:\n"
                "        attribute \"diagnostics\",     \"string\"\n"
                "        command \"captureDiagnostics\""
            ),
        ))

    return findings


ALL_RULES = [check_rule28_capturediagnostics_presence]
