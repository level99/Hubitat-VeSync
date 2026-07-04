"""
bare_bool_flag_eq.py — RULE55: flag a bare `<recv>.<boolFlag> == 0|1` comparison on a
VeSync boolean flag field; route it through the shared asBool() helper instead.

THE BUG CLASS (BP7 coercion divergence, companion to RULE45):
A VeSync flag field can arrive as an int (0/1), a Boolean (true/false), or a String
("1"/"true"/"on"). A bare integer comparison `r.powerSwitch == 1` does NOT throw (unlike
RULE45's `(x as Integer) == 1`), but it returns the WRONG answer for the Boolean/String
shapes: `true == 1` is false, `"1" == 1` is false. So a device that reports powerSwitch as
a Boolean reads as "off" while it is on. When one site uses asBool and another uses the bare
`== 1` for the same field (e.g. LevoitGeneric's switch event via asBool vs its info-tile via
`== 1`), the two DIVERGE and the tile contradicts the attribute.

RULE45 catches the throw-prone `(x as Integer) == 1` cast form. This rule catches the
distinct bare `<field> == 0|1` form (no cast, wrong-result-not-throw). The fix for both is
the same: the shared, never-throwing `asBool(raw)` helper in LevoitChildBaseLib.

Authoritative predicate (the CLASS, not an example list):
    A `<recv>[?.]<field> == [01]` comparison where `<field>` is a KNOWN VeSync boolean flag
    (the BOOL_FLAGS allowlist below). The allowlist — not a name-suffix heuristic — is what
    keeps this false-positive-free: a suffix rule (`*State == 1`) would wrongly flag ENUM
    fields like `dryingState` (0=off/1=active/2=complete) whose `== 1` is a legitimate enum
    test, and NUMERIC fields like `fanSpeedLevel == 1` (a real level check).

Detection scope: `.groovy` files under Drivers/Levoit/. Comments/strings blanked first.

Must-catch:
  - `r.powerSwitch == 1`, `r.childLockSwitch == 0`, `${r.screenSwitch == 1 ? 'on':'off'}`.

Must-NOT-catch:
  - `asBool(r.powerSwitch)` (the fix).
  - `(r.powerSwitch as Integer) == 1` (RULE45's cast form — the field is not immediately
    before `==`, so this rule does not match it; RULE45 owns it).
  - `r.dryingState == 1` / `r.fanSpeedLevel == 1` (enum / numeric — not in BOOL_FLAGS).
  - `muteState == 1` (a LOCAL variable, not a `<recv>.<field>` access — out of scope; those
    are `as Integer`-derived locals in RULE45's domain).
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# The authoritative set of VeSync BOOLEAN flag fields (each is 0/1 or bool/string true/false).
# NOT included: enum fields (dryingState 0/1/2) or numeric fields (fanSpeedLevel, mistLevel,
# manualSpeedLevel, virtualLevel, filterLifePercent, AQLevel, humidity, temperature, ...).
BOOL_FLAGS = [
    "powerSwitch", "childLockSwitch", "screenSwitch", "screenState",
    "lightDetectionSwitch", "environmentLightState", "autoStopSwitch", "autoStopState",
    "nightLightSwitch", "muteSwitch", "oscillationSwitch",
    "horizontalOscillationState", "verticalOscillationState",
    "waterLacksState", "waterTankLifted", "smartCleaningReminderState",
    "highTemperatureReminderState",
]

# `<recv>[?.]<boolFlag> [==|!=] 0|1`. Group 1 = the flag name. The field must be immediately
# before the comparison (so `(r.x as Integer) == 1` — where `as Integer)` sits between the
# field and `==` — does NOT match; that is RULE45's form).
_BARE_EQ_RE = re.compile(
    r'\b\w+\s*\??\.\s*(' + '|'.join(BOOL_FLAGS) + r')\s*(?:==|!=)\s*[01]\b'
)


def _blank_strings(text: str) -> str:
    """Blank the LITERAL interior of string literals (preserving length/newlines) so a flag
    token inside a log string isn't read as code — but PRESERVE `${...}` GString interpolations,
    whose contents are real code (e.g. `"Power: ${r.powerSwitch == 1 ? 'on':'off'}"`).
    clean_source already blanks comments."""
    out = list(text)
    n = len(text)
    i = 0
    while i < n:
        c = text[i]
        if c in ('"', "'"):
            triple = text[i:i + 3] in ('"""', "'''")
            delim = c * 3 if triple else c
            dlen = len(delim)
            interpolates = (c == '"')  # only double-quoted (G)Strings interpolate ${...}
            j = i + dlen
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if interpolates and text[j:j + 2] == '${':
                    # Leave the interpolation code intact; skip to the matching closing brace.
                    depth = 1
                    k = j + 2
                    while k < n and depth > 0:
                        if text[k] == '{':
                            depth += 1
                        elif text[k] == '}':
                            depth -= 1
                        k += 1
                    j = k
                    continue
                if text[j:j + dlen] == delim:
                    j += dlen
                    break
                if out[j] != '\n':
                    out[j] = ' '
                j += 1
            i = j
            continue
        i += 1
    return "".join(out)


def check_rule55_bare_bool_flag_eq(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """RULE55: FAIL a bare `<recv>.<boolFlag> == 0|1` comparison on a VeSync boolean flag —
    route it through asBool() (wrong result for Boolean/String shapes; distinct from RULE45's
    throw-prone cast form)."""
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    # NOTE: asBool's own body uses `raw.intValue() == 1` (a method call, not `.field == 1`),
    # so it never matches; no special-casing needed.
    for idx, raw_line in enumerate(cleaned_lines):
        line = _blank_strings(raw_line)
        m = _BARE_EQ_RE.search(line)
        if not m:
            continue
        flag = m.group(1)
        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE55_bare_bool_flag_eq',
            title=(
                f'Bare `{flag} == 0|1` boolean coercion — use the shared asBool() helper instead'
            ),
            file_rel=file_rel,
            lineno=idx + 1,
            raw_lines=raw_lines,
            why=(
                f'A VeSync flag field may arrive as an int, a Boolean, or a String. A bare '
                f'`{flag} == 1` does not throw, but returns the WRONG answer for the Boolean/String '
                f'shapes (`true == 1` is false, `"1" == 1` is false) — so a device reporting the '
                f'flag as a Boolean reads inverted. When one site uses asBool and another the bare '
                f'`== 1` for the same field, the two diverge (BP7 coercion divergence).'
            ),
            fix=(
                f'Replace `{flag} == 1` with `asBool(<recv>.{flag})` from LevoitChildBaseLib — '
                f'total, never-throwing, correct for int / Boolean / truthy-String shapes. '
                f'(For a `== 0` test, use `!asBool(...)`.)'
            ),
        ))

    return findings


ALL_RULES = [check_rule55_bare_bool_flag_eq]
