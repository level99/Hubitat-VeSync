"""
direct_log_calls.py — RULE30: flag direct log.X calls in child driver files.

All logging in Levoit child drivers must route through the shared library helpers
(logInfo / logDebug / logError / logWarn / logAlways) provided by
#include level99.LevoitChildBase.  Direct log.info / log.debug / log.warn /
log.error calls in driver body code bypass the library, defeating:

  - The descriptionTextEnable / debugOutput pref gates.
  - Future library-level changes (e.g. adding structured log metadata).
  - Consistent formatting conventions across the driver pack.

Scope:
  - Applies ONLY to .groovy files in Drivers/Levoit/ that use a definition()
    block (i.e., driver files, not library files).
  - Exempts VeSyncIntegration.groovy (the real parent): it defines and owns its
    own sanitize()-wrapping log helpers; log.X inside those helpers is correct.
  - Exempts Notification Tile.groovy: uses logsOff() rather than logDebugOff()
    and is not yet migrated to the shared library (deferred per CONTRIBUTING.md).
  - Exempts library files (library() block): they implement the helpers; direct
    log.X calls inside logDebug / logInfo etc. are intentional.
  - Skips lines that are inside helper-method bodies (def logX / void logX /
    def logAlways) — those bodies legitimately contain log.X.
  - Skips comment-only lines (detected after groovy_lite cleaning).

Severity: FAIL (BLOCKING). Unfired helpers = silent misbehavior.

Exemptions: add to lint_config.yaml using rule_id RULE30_direct_log_in_driver
if a future driver genuinely needs an ungated log call (document the reason).
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Files that are explicitly out-of-scope for RULE30.
RULE30_EXCLUDED_FILENAMES = {
    "VeSyncIntegration.groovy",   # real parent — owns sanitize-wrapping helpers
    "Notification Tile.groovy",   # deferred: uses logsOff() pattern
}

# Match any direct log.debug / log.info / log.warn / log.error call.
# Anchored to word boundary so "logDebug" / "logInfo" etc. don't match.
DIRECT_LOG_RE = re.compile(r'\blog\.(debug|info|warn|error)\s*\b')

# Match the OPENING of a logging helper method definition.
# Covers both driver-scope and private-scope forms:
#   def logDebug(msg) { ... }
#   private void logInfo(msg) { ... }
#   void logAlways(msg) { ... }
HELPER_DEF_RE = re.compile(
    r'(?:private\s+)?(?:def|void)\s+'
    r'(?:log(?:Debug|Info|Error|Warn|Always|DebugOff|sOff)|logsOff)\s*\('
)


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


def check_rule30_direct_log_in_driver(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE30: a child driver file MUST NOT call log.info / log.debug / log.warn /
    log.error directly in its body code.  All log calls must route through the
    library helpers (logInfo, logDebug, logError, logWarn, logAlways, etc.)
    provided by #include level99.LevoitChildBase.

    Helper method bodies (def logX / void logX) are skipped — they contain
    the canonical log.X calls that implement the helpers themselves.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    # Only applies to files in Drivers/Levoit/ directory.
    if 'Drivers' not in path.parts and 'Levoit' not in path.parts:
        # Check by path string as well for Windows vs. Unix path segments
        path_str = str(path).replace('\\', '/')
        if 'Drivers/Levoit/' not in path_str:
            return findings

    # Skip excluded files.
    if path.name in RULE30_EXCLUDED_FILENAMES:
        return findings

    # Skip library files.
    if is_library_file(raw_text):
        return findings

    # Track whether we are inside a logging helper method body using brace depth.
    in_helper_body = False
    helper_brace_depth = 0
    brace_depth = 0

    for i, line in enumerate(cleaned_lines, 1):
        open_count = line.count('{')
        close_count = line.count('}')

        if in_helper_body:
            brace_depth += open_count - close_count
            if brace_depth <= helper_brace_depth:
                in_helper_body = False
            # Inside helper body — skip; log.X here is intentional.
        else:
            if HELPER_DEF_RE.search(line):
                in_helper_body = True
                helper_brace_depth = brace_depth
                brace_depth += open_count - close_count
            else:
                brace_depth += open_count - close_count
                if DIRECT_LOG_RE.search(line):
                    m = DIRECT_LOG_RE.search(line)
                    level = m.group(1) if m else "X"
                    findings.append(_making_finding(
                        severity="FAIL",
                        rule_id="RULE30_direct_log_in_driver",
                        title=f"Direct log.{level}() call in driver body (bypasses library helpers)",
                        path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                        why=(
                            "Rule 30: all logging in Levoit child drivers must route through "
                            "the shared library helpers (logInfo / logDebug / logError / "
                            "logWarn / logAlways) provided by #include level99.LevoitChildBase. "
                            f"A direct log.{level}() call bypasses the pref gate and defeats "
                            "library-level consistency. If the call must always emit regardless "
                            "of prefs, use logAlways() instead of log.info() directly."
                        ),
                        fix=(
                            f"Replace log.{level}(...) with the appropriate helper:\n"
                            "  log.info(msg)  -> logInfo(msg)    [gated by descriptionTextEnable]\n"
                            "  log.debug(msg) -> logDebug(msg)   [gated by debugOutput]\n"
                            "  log.error(msg) -> logError(msg)   [always emits]\n"
                            "  log.warn(msg)  -> logWarn(msg)    [always emits]\n"
                            "  log.info(msg)  -> logAlways(msg)  [always emits, user-diagnostic "
                            "intent — use when the call MUST be visible regardless of prefs]"
                        ),
                    ))

    return findings


ALL_RULES = [check_rule30_direct_log_in_driver]
