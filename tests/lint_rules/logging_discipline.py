"""
logging_discipline.py — lint rules for logging conventions in this driver pack.

Rules:
  Rule 13 — Direct log.X calls in parent driver (bypass sanitize())
  Rule 14 — Missing settings?. prefix on debug/info conditionals
  Rule 15 — Missing 30-min auto-disable wiring (runIn + logDebugOff)
"""

import re
from pathlib import Path
from .groovy_lite import clean_source, find_method_bodies

PARENT_DRIVER = "VeSyncIntegration.groovy"

# Drivers that should have debugOutput preference + auto-disable
DRIVERS_WITH_DEBUG_PREF = {
    "VeSyncIntegration.groovy",
    "LevoitCore200S.groovy",
    "LevoitCore200S Light.groovy",
    "LevoitCore300S.groovy",
    "LevoitCore400S.groovy",
    "LevoitCore600S.groovy",
    "LevoitVital200S.groovy",
    "LevoitSuperior6000S.groovy",
    "Notification Tile.groovy",
}


def _context(lines, lineno, window=1):
    start = max(0, lineno - 1 - window)
    end = min(len(lines), lineno + window)
    return '\n'.join(f"    {lines[i]}" for i in range(start, end))


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": str(path.relative_to(rel_base)).replace('\\', '/'),
        "line": lineno,
        "context": _context(lines, lineno),
        "why": why,
        "fix": fix,
    }


# ---------------------------------------------------------------------------
# Rule 13 — Direct log.X in parent (bypasses sanitize())
# ---------------------------------------------------------------------------

# Direct log calls that bypass helpers
DIRECT_LOG_PATTERN = re.compile(
    r'\blog\.(info|debug|error|warn|trace)\s*\b'
)
# Helper method DEFINITIONS — any def/void/private logInfo/logDebug/logError
# These bodies legitimately contain log.X calls (they are the wrappers)
HELPER_DEF_PATTERN = re.compile(
    r'(?:private\s+)?(?:def|void)\s+log(?:Info|Debug|Error|Warn|s(?:Off)?)\s*\('
)
# A log.X call that is already inside a sanitize() wrapper is acceptable in the helper bodies
# Heuristic: if 'sanitize(' appears on the same line as log.X, it's a helper body — skip.
SANITIZE_ON_SAME_LINE = re.compile(r'\bsanitize\s*\(')


def check_rule13_direct_log_in_parent(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Every log call in VeSyncIntegration.groovy that is NOT inside a log-helper method body
    must go through the parent's logInfo/logDebug/logError helpers, which route through
    sanitize(). Direct log.X calls outside the helper bodies bypass sanitize() and can
    leak credentials.

    The helper bodies (def logInfo, def logDebug, def logError) legitimately contain log.X
    calls wrapping sanitize() — those are exempt.
    """
    findings = []
    if path.name != PARENT_DRIVER:
        return findings

    # Track whether we're inside a helper method body using brace depth.
    # We enter helper-body scope when we see a HELPER_DEF_PATTERN line, and exit
    # when the brace depth returns to the level it was at before the helper started.
    in_helper_body = False
    helper_brace_depth = 0
    brace_depth = 0

    for i, line in enumerate(cleaned_lines, 1):
        # Count braces on this line to track depth
        open_count = line.count('{')
        close_count = line.count('}')

        if in_helper_body:
            # Check if this closes the helper method body
            brace_depth += open_count - close_count
            if brace_depth <= helper_brace_depth:
                in_helper_body = False
            # Inside helper body — don't flag log.X calls here
        else:
            if HELPER_DEF_PATTERN.search(line):
                # Entering a helper method body
                in_helper_body = True
                helper_brace_depth = brace_depth  # depth BEFORE the opening brace on this line
                brace_depth += open_count - close_count
            else:
                brace_depth += open_count - close_count
                # Check for direct log.X that isn't in a helper body
                if DIRECT_LOG_PATTERN.search(line):
                    # Also allow if sanitize() appears on the same line (belt-and-suspenders)
                    if not SANITIZE_ON_SAME_LINE.search(line):
                        findings.append(_making_finding(
                            severity="FAIL",
                            rule_id="RULE13_direct_log_in_parent",
                            title="Direct log.X call in parent driver (bypasses sanitize())",
                            path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                            why="Rule 13: parent driver's log helpers route through sanitize() "
                                "which redacts email, accountID, token, and password. A direct "
                                "log.X call outside the helper bodies bypasses this and may leak "
                                "credentials to the Hubitat log UI.",
                            fix="Replace log.info/debug/error/warn with logInfo/logDebug/logError.",
                        ))

    return findings


# ---------------------------------------------------------------------------
# Rule 14 — Missing settings?. prefix
# ---------------------------------------------------------------------------

# Matches bare `if (debugOutput)` or `if (descriptionTextEnable)` etc.
# without the `settings?.` prefix
BARE_PREF_PATTERN = re.compile(
    r'\bif\s*\(\s*(?!settings)(?P<pref>debugOutput|descriptionTextEnable|verboseDebug)\b'
)


def check_rule14_bare_settings_ref(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Conditional log gating must use settings?. prefix to avoid NPE risk.
    'if (debugOutput)' without 'settings?.' prefix may throw if the setting
    is not yet committed to the settings map.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    for i, line in enumerate(cleaned_lines, 1):
        m = BARE_PREF_PATTERN.search(line)
        if m:
            pref = m.group('pref')
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE14_bare_settings_ref",
                title=f"Bare preference reference '{pref}' missing 'settings?.' prefix",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Rule 14: referencing a preference name without 'settings?.' prefix "
                    "risks NullPointerException if the preference has not been committed "
                    "(e.g. right after Type change before user clicks Save Preferences).",
                fix=f"Replace 'if ({pref})' with 'if (settings?.{pref})'",
            ))

    return findings


# ---------------------------------------------------------------------------
# Rule 15 — 30-min auto-disable wiring
# ---------------------------------------------------------------------------

# runIn(1800, ...) — covers both string and bare identifier form
RUNIN_1800_PATTERN = re.compile(r'\brunIn\s*\(\s*1800\s*,')
LOG_DEBUG_OFF_DEF_PATTERN = re.compile(r'\bvoid\s+log(?:Debug|s)Off\s*\(')
DEBUG_PREF_DECL_PATTERN = re.compile(r'\bdebugOutput\b')

# #include directive pattern: `#include level99.LibraryName`
INCLUDE_PATTERN = re.compile(r'^#include\s+([\w.]+)\s*$', re.MULTILINE)

# Cache of already-read library texts so each lib file is read at most once
# per lint run (keyed by resolved Path).
_lib_text_cache: dict = {}


def _resolve_lib_path(ns_and_name: str, driver_path: Path) -> "Path | None":
    """
    Resolve a '#include level99.<LibName>' directive to the library file on disk.

    Convention: namespace 'level99', library name 'LevoitChildBase' ->
    'Drivers/Levoit/LevoitChildBaseLib.groovy' (relative to repo root).

    driver_path is used to locate the repo root (go up until we find
    'Drivers/Levoit' as a sibling).  Falls back to None if the file
    does not exist.
    """
    parts = ns_and_name.split('.', 1)
    if len(parts) != 2 or parts[0] != 'level99':
        return None
    lib_name = parts[1]  # e.g. "LevoitChildBase"
    lib_filename = f"{lib_name}Lib.groovy"  # e.g. "LevoitChildBaseLib.groovy"

    # Walk up from the driver file to find the Drivers/Levoit directory.
    candidate = driver_path.parent / lib_filename
    if candidate.exists():
        return candidate
    return None


def _included_lib_texts(raw_text: str, driver_path: Path) -> list:
    """
    Return the raw text of every library file #include'd by this driver source.
    Results are cached; missing/unresolvable includes are silently skipped.
    """
    texts = []
    for m in INCLUDE_PATTERN.finditer(raw_text):
        ns_and_name = m.group(1)
        lib_path = _resolve_lib_path(ns_and_name, driver_path)
        if lib_path is None:
            continue
        if lib_path not in _lib_text_cache:
            try:
                _lib_text_cache[lib_path] = lib_path.read_text(encoding='utf-8')
            except OSError:
                _lib_text_cache[lib_path] = ''
        text = _lib_text_cache[lib_path]
        if text:
            texts.append(text)
    return texts


def check_rule15_auto_disable_wiring(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Every driver declaring a debugOutput preference must:
    1. Call runIn(1800, ...) in updated() when debugOutput is true
    2. Define a logDebugOff() (or logsOff()) method — either in the driver
       source itself OR in a library the driver #includes.

    Library-aware: when a driver delegates its helpers to a shared library via
    '#include level99.LevoitChildBase', the logDebugOff() definition lives in
    the library file rather than the driver source.  Both locations satisfy the
    rule; we check the driver first and fall back to included library files.
    """
    findings = []
    fname = path.name
    if fname not in DRIVERS_WITH_DEBUG_PREF:
        return findings

    lib_texts = _included_lib_texts(raw_text, path)

    has_runin_1800 = bool(RUNIN_1800_PATTERN.search(raw_text))
    # logDebugOff may live in the driver source or in any included library.
    has_logdebugoff_def = bool(LOG_DEBUG_OFF_DEF_PATTERN.search(raw_text)) or \
        any(bool(LOG_DEBUG_OFF_DEF_PATTERN.search(t)) for t in lib_texts)

    if not has_runin_1800:
        # Find the updated() method location for context
        lineno = 1
        for i, line in enumerate(raw_lines, 1):
            if re.search(r'\bdef\s+updated\s*\(\s*\)', line):
                lineno = i
                break
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE15_missing_runin_1800",
            title="Missing runIn(1800, ...) auto-disable in updated()",
            path=path, rel_base=rel_base, lineno=lineno, lines=raw_lines,
            why="Rule 15: without auto-disable, debug logging stays on permanently after a user "
                "enables it, flooding the Hubitat log buffer on every 30-second poll cycle.",
            fix='In updated(), add: if (settings?.debugOutput) runIn(1800, "logDebugOff")',
        ))

    if not has_logdebugoff_def:
        findings.append(_making_finding(
            severity="FAIL",
            rule_id="RULE15_missing_logdebugoff_method",
            title="Missing logDebugOff() / logsOff() method definition",
            path=path, rel_base=rel_base, lineno=1, lines=raw_lines,
            why="Rule 15: the runIn(1800, 'logDebugOff') callback needs a corresponding method "
                "that actually flips the debugOutput setting back to false. Either define it "
                "locally or include a library that provides it (e.g. #include level99.LevoitChildBase).",
            fix='Add: void logDebugOff() { '
                'if (settings?.debugOutput) device.updateSetting("debugOutput", [type:"bool", value:false]) }'
                '  -- or add #include level99.LevoitChildBase',
        ))

    return findings


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------

ALL_RULES = [
    check_rule13_direct_log_in_parent,
    check_rule14_bare_settings_ref,
    check_rule15_auto_disable_wiring,
]
