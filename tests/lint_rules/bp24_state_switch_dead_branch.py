"""
bp24_state_switch_dead_branch.py — RULE31: flag any read of state.switch in a
conditional context inside Drivers/Levoit/*.groovy.

Bug Pattern #24-A — Dead ``state.switch`` guard in configure-style commands.

``state.switch`` is never written anywhere in the codebase (only
``device.sendEvent(name:"switch", ...)`` and ``device.currentValue("switch")``
are used to track power state).  Any code that reads ``state.switch`` in a
conditional like ``if (state.switch == "off") { on() }`` is therefore a
permanently-dead branch: the condition is always false, on() is never called,
and the device stays off when a configure-style command fires from automation.

Historical occurrence:
  Round 1.5 audit (2026-05-03) found exactly 4 sites across 4 Core line drivers:
    LevoitCore200S.groovy  cycleSpeed() line ~184
    LevoitCore300S.groovy  cycleSpeed() line ~211
    LevoitCore400S.groovy  cycleSpeed() line ~214
    LevoitCore600S.groovy  cycleSpeed() line ~213

  All four share the same pattern: cycleSpeed() increments state.speed then
  checks ``if (state.switch == "off") { on() }`` before delegating to setSpeed().
  Because state.switch is never written, the guard never fires and the device
  stays off when cycleSpeed() is triggered from an off state.

Detection algorithm:
  For each line in cleaned_lines (block-comment-stripped):
    1. Skip full-line comments (``//``-prefixed after strip).
    2. Look for ``state\\.switch`` referenced in a conditional or comparison
       context (``==``, ``!=``, ``if``, ``?:``, ``&&``, ``||``, ternary).
    3. Emit FAIL with 3-line context.

Note: this rule intentionally does NOT check for ``state.switch =`` writes.
A write of state.switch would suppress the finding (the branch would no longer
be dead).  If a future driver author legitimately writes state.switch, that
author should add an exemption with an explanation; the default assumption is
dead-branch.

Scope: all .groovy driver files under Drivers/Levoit/ (library files excluded
via is_library_file()).

Exemptions: use the standard lint_config.yaml exemptions mechanism with
rule_id ``RULE31_state_switch_dead_branch``.
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Matches state.switch used in a comparison or conditional expression.
# We look for:
#   state.switch == ...
#   state.switch != ...
#   state.switch in ...
#   ... == state.switch
#   ... != state.switch
#   if (...state.switch...)  [catch the whole expression]
#   state.switch (in a boolean context, e.g. ternary)
#
# The simplest reliable approach: match any line containing the literal
# ``state.switch`` (case-sensitive; Groovy is case-sensitive for identifiers).
# We exclude lines that are pure write assignments (``state.switch =``).
STATE_SWITCH_READ_RE = re.compile(r'\bstate\.switch\b')
STATE_SWITCH_WRITE_RE = re.compile(r'\bstate\.switch\s*=(?!=)')  # = but not ==


def _context_lines(raw_lines, lineno, window=3):
    """Return up to ``window`` lines of context around lineno (1-based)."""
    start = max(0, lineno - 1 - window)
    end = min(len(raw_lines), lineno + window)
    return '\n'.join(f"    {raw_lines[i]}" for i in range(start, end))


def check_rule31_state_switch_dead_branch(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE31 (Bug Pattern #24-A): Fail if any line reads ``state.switch`` in a
    conditional context.

    Operates on cleaned_lines (block-comment-stripped) so that commented-out
    code (e.g. ``// OLD: if (state.switch == "off")``) does not produce a
    spurious finding.

    A line is exempt if it only writes to state.switch (``state.switch = X``).
    Any read of state.switch is a dead branch — emit FAIL.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    if is_library_file(raw_text):
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for i, cleaned_line in enumerate(cleaned_lines, 1):
        stripped = cleaned_line.lstrip()
        # Skip full-line comments
        if stripped.startswith('//'):
            continue
        # Skip pure write assignments (state.switch = X)
        if not STATE_SWITCH_READ_RE.search(cleaned_line):
            continue
        if STATE_SWITCH_WRITE_RE.search(cleaned_line) and not re.search(
            r'\bstate\.switch\s*[=!]=', cleaned_line
        ):
            # Pure assignment, not a comparison — skip
            continue

        # Found a read of state.switch — flag it
        findings.append({
            "severity": "FAIL",
            "rule_id": "RULE31_state_switch_dead_branch",
            "title": (
                "Dead-branch guard: state.switch is never written; "
                "any read is a permanently-dead condition (Bug Pattern #24-A)"
            ),
            "file": file_rel,
            "line": i,
            "context": _context_lines(raw_lines, i),
            "why": (
                "Bug Pattern #24-A: ``state.switch`` is never written anywhere in the "
                "codebase. Power state is tracked via ``device.sendEvent(name:'switch', ...)`` "
                "and read via ``device.currentValue('switch')``. Any conditional that reads "
                "``state.switch`` is permanently dead (the condition is never true) — the "
                "auto-on guard never fires, and configure-style commands (cycleSpeed, setSpeed, "
                "etc.) silently leave the device off when invoked from automation on an off device."
            ),
            "fix": (
                "Replace ``if (state.switch == 'off') { on() }`` (dead branch) with the "
                "canonical BP24 auto-on guard: "
                "``if (!state.turningOn && device.currentValue('switch') != 'on') on()`` — "
                "placed at the top of the method body, after parameter validation, before the "
                "API call. See Bug Pattern #24-A in .claude/agents/vesync-driver-qa.md for "
                "the canonical fix and classification taxonomy."
            ),
        })

    return findings


ALL_RULES = [check_rule31_state_switch_dead_branch]
