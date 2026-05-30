"""
bp28_level_off_ambiguity.py — RULE40: flag level/mist setters that route a
``safeIntArg(<param>, 0)`` result into a numeric-threshold branch (``<= 0`` /
``== 0`` / ``< <int-literal>``) whose body calls an off-form (``off()`` OR
``set*("off")``) WITHOUT a preceding ``parseLevelOrNull`` null-ignore guard.

Bug Pattern #28 — non-numeric level value silently turns the device OFF.

``safeIntArg(raw, 0)`` substitutes its fallback (0) on NON-numeric input. When the
result then feeds an off-form via a threshold branch — either
``if (lvl <= 0) { off(); return }`` (direct power-off) or
``if (pct < 10) { setNightLight("off") }`` (sub-feature off, Core 200S Light) —
a typo such as ``setMistLevel("hgih")`` / ``setLevel("brght")`` coerces to 0 and
turns the device (or its sub-feature) OFF — indistinguishable from an explicit 0.
The intended contract is:

  - explicit 0 / low        -> off-form         (SwitchLevel/MistLevel convention)
  - non-numeric garbage     -> ignore + WARN    (device stays as-is)
  - null / empty (BP18)     -> already handled by requireNotNull / the parser

The canonical fix uses ``parseLevelOrNull`` from LevoitChildBaseLib, which returns
the parsed Integer for genuinely-numeric input and ``null`` for unparseable input:

    Integer lvl = parseLevelOrNull(level)
    if (lvl == null) { logWarn "setMistLevel: ignoring non-numeric value '${level}'"; return }
    if (lvl <= 0)    { off(); return }
    ...clamp + ensureSwitchOn + cloud write...

Detection scope — a method is flagged ONLY when ALL FOUR conditions hold (the
conjunction keeps benign numeric ``< N`` branches from tripping):
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - Only inside method bodies whose name starts with ``set`` (level/mist setters).
  (i)   a local is assigned from ``safeIntArg(<firstParam>, 0 ...)`` (fallback-0,
        any overload);
  (ii)  that local feeds a numeric-threshold branch (``<= 0`` / ``== 0`` /
        ``< <int-literal>``);
  (iii) the branch body calls an off-form — ``off()`` OR ``set*("off")``
        (e.g. ``setNightLight("off")``);
  (iv)  the body does NOT call ``parseLevelOrNull`` anywhere (fix not yet applied).

  Not flagged: a ``parseLevelOrNull``-guarded body (fix in place); a threshold
  branch whose operand does NOT originate from ``safeIntArg(<param>, 0)`` (computed/
  clamped internals); a ``safeIntArg``-fed ``< N`` branch whose body does NOT call
  an off-form (the must-not-catch fixture).

Exemptions:
  Add ``bp28_level_off_ambiguity_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: setSomething
      rationale: "reason the safeIntArg->off shape is intentional here"
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Matches def set*(param) — captures method name and first param name (typed or untyped).
# Level/mist setters are always set*; doSet*/cycle* are out of scope for this pattern.
# Group 1: method name, Group 2: first param name.
SET_METHOD_RE = re.compile(
    r'^\s*def\s+(set[A-Z]\w*)\s*\(\s*(?:\w+\s+)?(\w+)\s*(?:[,=)])',
    re.MULTILINE,
)

# Matches `<Type> <localVar> = safeIntArg(<receiver>, 0 ...)` — the unsafe assignment.
# Group 1: local variable name. Group 2: the receiver (first arg to safeIntArg).
# Fallback must be a literal 0 (the ambiguity-creating fallback); a non-zero fallback
# would route differently and is out of scope.
SAFEINTARG_ZERO_ASSIGN_RE = re.compile(
    r'\b(?:Integer|int|def)\s+(\w+)\s*=\s*safeIntArg\s*\(\s*(\w+)\s*,\s*0\s*(?:[,)])'
)

# Matches `parseLevelOrNull(` anywhere — presence means the fix is applied.
PARSE_LEVEL_OR_NULL_RE = re.compile(r'\bparseLevelOrNull\s*\(')

# Matches a numeric-threshold branch on a local var whose body calls an off-form.
# The threshold (condition ii) is one of: `<= 0`, `== 0`, or `< <int-literal>`.
# The off-form (condition iii) is either a bare `off()` OR a `set*("off")` /
# `set*('off')` call (e.g. `setNightLight("off")`).
#
# Group 1: the compared local variable name (the threshold operand).
#
# Both `if (x <= 0) { off() }` and `if (pct < 10) { setNightLight("off") }` match.
# The `\s*\{\s*` between the condition and the off-form keeps the match tight to a
# guard branch whose body STARTS with the off-form, avoiding spurious matches where
# an off-form merely appears later in an unrelated block. A `safeIntArg`-fed `< N`
# branch whose body does NOT call an off-form (the must-not-catch fixture) is not
# matched, so the four-way conjunction in the rule body gates correctly.
OFF_FORM = r'(?:off\s*\(\s*\)|set\w+\s*\(\s*["\']off["\']\s*\))'
THRESHOLD_OFF_RE = re.compile(
    r'\bif\s*\(\s*(\w+)\s*'
    r'(?:<=\s*0|==\s*0|<\s*\d+)'      # <= 0 | == 0 | < <int-literal>
    r'\s*\)\s*\{\s*'
    + OFF_FORM
)


def _find_method_body_end(source: str, brace_start: int) -> int:
    depth = 0
    for i in range(brace_start, len(source)):
        c = source[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def _line_of(source: str, pos: int) -> int:
    return source[:pos].count('\n') + 1


def _strip_line_comments(text: str) -> str:
    return re.sub(r'(?m)//[^\n]*', '', text)


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('bp28_level_off_ambiguity_exemptions', [])
    if not isinstance(entries, list):
        return exemptions
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        file_ = str(entry.get('file', '')).replace('\\', '/').strip()
        method = str(entry.get('method', '')).strip()
        rationale = str(entry.get('rationale', '')).strip()
        if file_ and method and rationale:
            exemptions.add((file_, method))
    return exemptions


def check_rule40_level_off_ambiguity(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE40 (Bug Pattern #28): flag a set* method ONLY when all four conditions hold:
      (i)   a local is assigned from ``safeIntArg(<firstParam>, 0 ...)`` (fallback-0,
            any overload),
      (ii)  that local feeds a numeric-threshold branch (``<= 0``, ``== 0``, or
            ``< <int-literal>``),
      (iii) the branch body calls an off-form — either ``off()`` OR
            ``set*("off")`` (e.g. ``setNightLight("off")``),
      (iv)  no ``parseLevelOrNull`` guard is present in the body.
    The four-way conjunction keeps legitimate ``if (x < N)`` numeric branches (not
    fed by fallback-0, or not calling an off-form) from tripping. On non-numeric
    input the fallback-0 routes through the threshold to the off-form, silently
    powering the device (or sub-feature) off — indistinguishable from an explicit 0.
    Use ``parseLevelOrNull`` from LevoitChildBaseLib instead.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    for m in SET_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)
        param_name = m.group(2)

        if (file_rel, method_name) in exemption_set:
            continue

        brace_start = raw_text.find('{', m.end() - 1)
        if brace_start == -1:
            continue
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue

        body = raw_text[brace_start:body_end]
        body_clean = _strip_line_comments(body)

        # Fix already applied? parseLevelOrNull present -> exempt.
        if PARSE_LEVEL_OR_NULL_RE.search(body_clean):
            continue

        # Collect locals assigned from safeIntArg(<param>, 0 ...) where the receiver
        # is this method's first param. Only the literal-0 fallback is the
        # ambiguity-creating shape.
        unsafe_locals = {}  # local var name -> char offset within body
        for assign_m in SAFEINTARG_ZERO_ASSIGN_RE.finditer(body_clean):
            local_var = assign_m.group(1)
            receiver = assign_m.group(2)
            if receiver == param_name:
                unsafe_locals[local_var] = assign_m.start()

        if not unsafe_locals:
            continue

        # Find a numeric-threshold branch (<= 0 / == 0 / < N) whose body calls an
        # off-form (off() OR set*("off")) on one of those unsafe locals.
        for off_m in THRESHOLD_OFF_RE.finditer(body_clean):
            compared_var = off_m.group(1)
            if compared_var in unsafe_locals:
                abs_pos = brace_start + off_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE40_level_off_ambiguity',
                    title=(
                        f'BP28: {method_name}() routes `safeIntArg({param_name}, 0)` into a '
                        f'numeric-threshold branch on `{compared_var}` (<= 0 / == 0 / < N) '
                        f'whose body calls an off-form (off() or set*("off")) — non-numeric '
                        f'input silently turns the device (or sub-feature) OFF'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'safeIntArg({param_name}, 0) substitutes its fallback 0 on '
                        f'NON-numeric input, so a typo like {method_name}("hgih") coerces '
                        f'to 0, falls through the threshold, and triggers the off-form — '
                        f'indistinguishable from an explicit {method_name}(0). The user '
                        f'expects a typo to be ignored, not to power the device (or its '
                        f'night-light / sub-feature) off.'
                    ),
                    fix=(
                        f'Use parseLevelOrNull from LevoitChildBaseLib: '
                        f'`Integer {compared_var} = parseLevelOrNull({param_name}); '
                        f'if ({compared_var} == null) {{ logWarn "{method_name}: ignoring '
                        f'non-numeric value \'${{{param_name}}}\'"; return }}` (re-apply any '
                        f'clamp after) BEFORE the threshold branch. parseLevelOrNull returns '
                        f'null for unparseable input and the Integer for genuine numbers '
                        f'(including "0"), so explicit 0/low still routes to the off-form.'
                    ),
                ))
                break  # one finding per method is enough

    return findings


ALL_RULES = [check_rule40_level_off_ambiguity]
