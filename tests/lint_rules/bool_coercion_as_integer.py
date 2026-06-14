"""
bool_coercion_as_integer.py — RULE45: flag the throw-prone `(<expr> as Integer) == 1`
boolean-coercion idiom; direct the author to the shared asBool() helper instead.

THE BUG CLASS — a VeSync flag field (enabled, water_lacks, display, child_lock,
warm_enabled, powerSwitch, childLockSwitch, autoStopSwitch, ...) is coerced to a boolean
with one of:

    (x as Integer) == 1
    (x instanceof Boolean) ? x : ((x as Integer) == 1)

The `(x as Integer)` branch throws NumberFormatException when the field arrives as a
non-numeric String (e.g. "false" / "off") — and because the Hubitat sandbox swallows the
exception mid-callback, it ABORTS the whole applyStatus/update status-parse, leaving the
device with stale or missing attributes. ~69 hand-inlined sites had this latent fault.

THE FIX — route every such coercion through the shared, never-throwing helper
``asBool(raw)`` in LevoitChildBaseLib (Boolean -> as-is; Number 1 -> true; truthy String
-> true; anything else -> false). This rule is the closed mechanism that stops the class
from re-appearing: any new `(... as Integer) == 1` boolean coercion under Drivers/Levoit/
fails ``lint --strict``.

Scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - The asBool() helper's OWN body never matches: it coerces via ``raw.intValue() == 1``
    (a method call on an already-typed Number), NOT ``as Integer) == 1`` — so the helper
    is exempt by construction, no special-casing needed.

Detection — two passes:
  (i)  SINGLE-LINE — a line (comments stripped) containing ``... as Integer) == 1``.
       Covers the bare assignment form, the inline ``? "on" : "off"`` event form, and the
       tail of the verbose ``instanceof Boolean) ? <ident> : ((<ident> as Integer) == 1)``
       ternary. This token-sequence is the load-bearing signature of the same-line form.
  (ii) SPLIT — a cast-to-Integer ASSIGNMENT (``[Integer|int|def] <var> = (...) as Integer``
       with NO ``== 1`` on that line) whose assigned ``<var>`` is compared ``<var> == 1``
       within the next few lines (SPLIT_WINDOW). The cast on the assignment line is what
       throws on a String, so the ASSIGNMENT line is flagged. This is exactly how
       Sup6000S:332/333 slipped past the single-line rule.

  The downstream ``<var> == 1`` compare is the discriminator that keeps pass (ii)
  false-positive-free WITHOUT dataflow: a genuine NUMERIC level cast (mist level, speed,
  room size) is compared to a range/threshold (``> 0``, ``>= 5``, ``< 3``) or used in
  arithmetic — never ``== 1`` — so it never matches. Only a 0/1 FLAG is tested ``== 1``.
  ``asBool`` is the only blessed coercion and uses ``raw.intValue() == 1`` (not
  ``as Integer``), so its own body never trips either pass.

Exemptions:
  Add ``bool_coercion_as_integer_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      line: 123
      rationale: "reason this site legitimately needs the raw cast-compare"
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# The load-bearing signature of the whole class: a cast to Integer immediately compared
# to 1. Matches the bare form `(r.field as Integer) == 1`, the inline-event form
# `(r.field as Integer) == 1 ? "on" : "off"`, and the tail of the verbose
# `(x instanceof Boolean) ? x : ((x as Integer) == 1)` ternary.
AS_INTEGER_EQ1_RE = re.compile(r'as\s+Integer\s*\)\s*==\s*1\b')

# SPLIT-FORM detection. The cast and the `== 1` compare can be split across two lines:
#     Integer screen = (r.screenState ?: r.screenSwitch) as Integer   # line N
#     device.sendEvent(... , value: screen == 1 ? "on" : "off")       # line N+k
# This is the SAME throw-prone class (the cast on line N throws on a String), but the
# single-line regex above misses it (that is exactly how Sup6000S:332/333 evaded RULE45).
#
# Part 1: a line that ASSIGNS a cast-to-Integer to a variable, with NO `== 1` on that line
# (tail `as Integer`, optionally followed by a trailing comment we already stripped). The
# leading type (`Integer`/`int`/`def`) is optional. Capture the assigned variable name.
ASSIGN_AS_INTEGER_RE = re.compile(
    r'^\s*(?:Integer|int|def)?\s*(\w+)\s*=\s*.*\bas\s+Integer\s*$'
)
# Part 2: that captured variable later compared `== 1` (bare or as a ternary test). The
# `== 1` (NOT `> 0` / `>= N` / `< N` / used in arithmetic) is the discriminator that
# separates a 0/1 FLAG coercion from a legitimate numeric-level cast.
def _var_eq1_re(var: str) -> "re.Pattern":
    return re.compile(r'\b' + re.escape(var) + r'\s*==\s*1\b')

# Window (in lines) to scan after a cast-assignment for the `<var> == 1` compare.
SPLIT_WINDOW = 3


def _strip_line_comments(text: str) -> str:
    # Remove // line comments so a comment that mentions the idiom (e.g. a docstring
    # describing the pre-fix form) does not trip the rule.
    return re.sub(r'//[^\n]*', '', text)


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('bool_coercion_as_integer_exemptions', [])
    if not isinstance(entries, list):
        return exemptions
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        file_ = str(entry.get('file', '')).replace('\\', '/').strip()
        line = entry.get('line')
        rationale = str(entry.get('rationale', '')).strip()
        if file_ and isinstance(line, int) and rationale:
            exemptions.add((file_, line))
    return exemptions


def check_rule45_bool_coercion_as_integer(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE45: flag the throw-prone `(<expr> as Integer) == 1` boolean-coercion idiom in
    Drivers/Levoit/*.groovy — direct the author to the shared asBool() helper.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    # Pre-strip comments per line once; reuse for both passes.
    clean_lines = [_strip_line_comments(rl) for rl in raw_lines]

    # Lines already flagged (avoid double-reporting if a single line somehow matches both).
    flagged_lines = set()

    def _emit(lineno):
        if (file_rel, lineno) in exemption_set:
            return
        if lineno in flagged_lines:
            return
        flagged_lines.add(lineno)
        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE45_bool_coercion_as_integer',
            title=(
                'Throw-prone boolean coercion `(... as Integer) == 1` — use the shared '
                'asBool() helper instead'
            ),
            file_rel=file_rel,
            lineno=lineno,
            raw_lines=raw_lines,
            why=(
                'A VeSync flag field may arrive as a non-numeric String (e.g. "false"). '
                '`(x as Integer)` then throws NumberFormatException, which the Hubitat '
                'sandbox swallows mid-callback and ABORTS the entire status parse — leaving '
                'the device with stale/missing attributes. This idiom is the boolean-coercion '
                'bug class (~69 hand-inlined sites previously carried it). The cast and the '
                '`== 1` compare may be on the SAME line or SPLIT across two lines — both forms '
                'are the same throw-prone class.'
            ),
            fix=(
                'Replace `(x instanceof Boolean) ? x : ((x as Integer) == 1)`, the bare '
                '`(x as Integer) == 1`, AND the split `Integer s = (x) as Integer` / `s == 1` '
                'forms with `asBool(x)` from LevoitChildBaseLib — total, never-throwing, same '
                'Number==1 semantics, and it correctly parses truthy Strings '
                '("true"/"1"/"on"/"yes") to true.'
            ),
        ))

    # Pass 1 — single-line `(... as Integer) == 1` (bare / inline-event / verbose-ternary tail).
    for idx, line_clean in enumerate(clean_lines):
        if AS_INTEGER_EQ1_RE.search(line_clean):
            _emit(idx + 1)

    # Pass 2 — SPLIT form: a cast-to-Integer ASSIGNMENT (no `== 1` on its line) whose
    # assigned variable is compared `== 1` within SPLIT_WINDOW following lines. The
    # `<var> == 1` compare downstream is the discriminator: a cast compared to a range/
    # threshold (`> 0`, `>= N`, `< N`) or used in arithmetic — i.e. a genuine numeric level,
    # not a 0/1 flag — never matches and is never flagged.
    for idx, line_clean in enumerate(clean_lines):
        if AS_INTEGER_EQ1_RE.search(line_clean):
            continue  # already a single-line match — handled in pass 1
        m = ASSIGN_AS_INTEGER_RE.match(line_clean)
        if not m:
            continue
        var = m.group(1)
        eq1 = _var_eq1_re(var)
        for j in range(idx + 1, min(idx + 1 + SPLIT_WINDOW, len(clean_lines))):
            if eq1.search(clean_lines[j]):
                # Flag the CAST line (where the throw actually originates).
                _emit(idx + 1)
                break

    return findings


ALL_RULES = [check_rule45_bool_coercion_as_integer]
