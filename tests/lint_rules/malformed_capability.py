"""
malformed_capability.py — RULE42: flag capability declarations with an embedded space.

Hubitat capability identifiers are always a single CamelCase token (e.g.
"SwitchLevel", "FanControl", "TemperatureMeasurement"). They NEVER contain a
space. A declaration like `capability "Switch Level"` (two words) is silently
ignored by the Hubitat platform — the capability simply does not register, so
the device loses the dashboard tiles and Rule Machine bindings that capability
would have provided, with NO error surfaced.

This is an enumerable class of bug (any capability string with a space is always
wrong), so it is caught by a mechanical rule rather than patched per-instance:
the canonical predicate is "a capability "..." literal whose contents contain a
space character." There is no legitimate Hubitat capability name with a space.

Scope:
  - Applies to .groovy files (drivers and libraries).
  - Matches `capability "<text with a space>"`.

Severity: FAIL (BLOCKING). A misregistered capability is a silent feature loss.

Exemptions: none expected — a space in a capability name is always a bug. If a
genuine exception ever arises, add to lint_config.yaml with rule_id
RULE42_malformed_capability and a substantive reason.
"""

import re

from lint_rules._helpers import make_finding_for_path


# Match a capability declaration whose quoted argument contains a space.
# capability   "Switch Level"   ->  group(1) == "Switch Level"
MALFORMED_CAP_RE = re.compile(r'\bcapability\s+"([^"]*\s[^"]*)"')


def check_rule42_malformed_capability(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE42: a `capability "..."` declaration MUST NOT contain a space in the
    capability name. Hubitat capability identifiers are single CamelCase tokens;
    an embedded space means the capability silently fails to register.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    for i, line in enumerate(cleaned_lines, 1):
        m = MALFORMED_CAP_RE.search(line)
        if m:
            bad = m.group(1)
            collapsed = bad.replace(" ", "")
            findings.append(make_finding_for_path(
                severity="FAIL",
                rule_id="RULE42_malformed_capability",
                title=f'Malformed capability name "{bad}" (contains a space)',
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why=(
                    "Rule 42: Hubitat capability identifiers are always a single "
                    "CamelCase token and never contain a space. A capability string "
                    "with an embedded space is silently ignored by the platform — the "
                    "capability fails to register, so dashboard tiles and Rule Machine "
                    "bindings that depend on it never appear, with no error surfaced. "
                    f'"{bad}" is almost certainly meant to be "{collapsed}".'
                ),
                fix=(
                    f'Remove the space: capability "{bad}" -> capability "{collapsed}"'
                ),
            ))

    return findings


ALL_RULES = [check_rule42_malformed_capability]
