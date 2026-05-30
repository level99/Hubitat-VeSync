"""
bp26_unsafe_int_coercion.py — RULE37: flag bare ``as Integer`` / ``as int`` /
``.toInteger()`` coercions on untyped command parameters inside ``set*`` /
``cycle*`` methods, AND on Map-field accesses inside ``set*(Map mapParam)``
or ``set*(mapParam)`` (untyped) methods.

Bug Pattern #26 — unsafe numeric coercion on command parameters.

In Groovy 2.5.23 (Hubitat sandbox), ``(x as Integer)`` and ``.toInteger()``
throw ``NumberFormatException`` / ``GroovyCastException`` when the input is
a non-numeric string (empty string, ``"abc"``, ``"5.7"``, ``"true"``).
The ``?:`` Elvis operator catches ``null`` but NOT thrown exceptions, so
``(x as Integer) ?: 0`` does NOT fall back on ``"abc"`` — it propagates the
exception which the sandbox swallows silently.

Sources of non-numeric args in practice:
  - Rule Machine parameter slot left blank  -> ``""`` (NOT null)
  - Dashboard numeric tile                  -> decimal string like ``"5.7"``
  - Hub variable binding                    -> ``"true"`` / ``"1"`` / ``"false"``
  - ColorControl ``setColor(Map colorMap)`` -> Map field values are Strings
    from RM color-map expressions; ``colorMap?.hue as Integer`` on ``"55.5"``
    throws ``NumberFormatException`` (silent).

Detection scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - Only inside method bodies whose name starts with ``set`` or ``cycle``.
  Pass 1 (untyped-param): coercions on the first (untyped) param of the
    enclosing method.  Statically-typed params (``Integer level``) are safe
    because the Hubitat sandbox enforces the type at dispatch time.
  Pass 2 (Map-field, B2 extension): coercions on Map-field accesses inside
    ``set*(Map mapParam)`` OR ``set*(mapParam)`` (untyped) methods.
    Detects the following forms (all throw on non-numeric Map values):
      - ``mapParam?.field as Integer`` / ``mapParam.field as Integer``   (one-hop)
      - ``(mapParam.field) as Integer``                                  (a: paren-before-as)
      - ``mapParam.outer.field as Integer``                              (b: multi-level chain)
      - ``def alias = mapParam.field; alias as Integer``                 (c: aliased local)
      - ``Integer.parseInt(mapParam.field)``                             (d: parseInt form)
      - ``colorMap['key'] as Integer`` / ``colorMap['key'].toInteger()`` (e: bracket access)
      - ``def setColor(colorMap)`` body with ``colorMap.hue as Integer`` (f: untyped Map param)
    Flags: ``as Integer``, ``as int``, ``.toInteger()``, ``Integer.parseInt()``.
  - Does NOT flag: ``safeIntArg(x, ...)``, typed-param coercions, coercions
    on variables that were assigned from a ``safeIntArg`` call.

Exemptions:
  Add ``bp26_unsafe_int_coercion_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: setSomething
      rationale: "reason the coercion is safe here"
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Matches def set*(param), def doSet*(param), or def cycle*(param) — captures method name
# and first param.  Untyped first param only (no type keyword before the name).
# Group 1: method_name, Group 2: first_param_name (no type keyword immediately before it).
# doSet* shared-helper definitions receive the same user-supplied input as set* callers
# (the caller normalises and delegates — the helper still does the integer coercion) and
# must be covered by the same rule.
SET_METHOD_RE = re.compile(
    r'^\s*def\s+((?:doSet|set|cycle)[A-Z]\w*)\s*\(\s*(\w+)\s*(?:[,=)])',
    re.MULTILINE,
)

# Matches def set*(Map mapParam ...) or def doSet*(Map mapParam ...) — Map-typed first param.
# Group 1: method_name, Group 2: Map-param variable name.
SET_MAP_METHOD_RE = re.compile(
    r'^\s*def\s+((?:doSet|set|cycle)[A-Z]\w*)\s*\(\s*Map\s+(\w+)',
    re.MULTILINE,
)

# Matches def set*(colorMap) or def doSet*(colorMap) — untyped first param that looks like
# a Map variable (name contains "map", "color", "Map", or "Color", case-insensitive).
# This covers ``def setColor(colorMap)`` which Hubitat drivers commonly declare without
# the ``Map`` keyword.  Group 1: method_name, Group 2: param_name.
SET_UNTYPED_MAP_PARAM_RE = re.compile(
    r'^\s*def\s+((?:doSet|set|cycle)[A-Z]\w*)\s*\(\s*(\w*[Mm]ap\w*|\w*[Cc]olor\w*)\s*(?:[,=)])',
    re.MULTILINE,
)

# Map-field as-Integer forms: catches all dot-access variants.
# Handles:
#   - ``mapParam?.field as Integer``                (one-hop, optional-chained)
#   - ``mapParam.field as Integer``                 (one-hop)
#   - ``(mapParam.field) as Integer``               (a: paren-before-as)
#   - ``mapParam.outer.field as Integer``           (b: multi-level chain)
#   - ``mapParam['key'] as Integer``                (e: bracket access)
# Group 1: the full receiver expression (for diagnostic messages).
_MAP_FIELD_AS_INTEGER_RE = re.compile(
    r'(?:\((\w+(?:\??\.\w+)+)\)|\b(\w+(?:\??\.\w+)+|\w+\[[\'\"]?\w+[\'\"]?\]))\s+as\s+(?:Integer|int)\b'
)

# Map-field toInteger() form: one-hop and multi-hop dot-access + bracket access.
_MAP_FIELD_TO_INTEGER_RE = re.compile(
    r'\b(\w+(?:\??\.\w+)+|\w+\[[\'\"]?\w+[\'\"]?\])\.toInteger\s*\(\s*\)'
)

# Integer.parseInt / Long.parseLong on a Map-field access.
_PARSEINT_RE = re.compile(
    r'\b(?:Integer|Long)\.parse(?:Int|Long)\s*\(\s*(\w+(?:\??\.\w+)+|\w+\[[\'\"]?\w+[\'\"]?\])\s*\)'
)

# Alias assignment: ``def alias = mapParam.field`` / ``def alias = mapParam?.field``
# / ``def alias = mapParam['key']``.
# Group 1: alias variable name, Group 2: full receiver (for base extraction).
_ALIAS_ASSIGN_RE = re.compile(
    r'\bdef\s+(\w+)\s*=\s*(\w+(?:\??\.\w+)+|\w+\[[\'\"]?\w+[\'\"]?\])'
)

# Matches (x as Integer) / (x as int) [parenthesized] OR bare x as Integer / x as int
# [unparenthesized, word-boundary anchored].  Group 1 is the cast subject in both forms.
# The parenthesized form is tried first (longer, more specific); the bare form catches
# unparenthesized assignments like `int secs = seconds as Integer`.
AS_INTEGER_RE = re.compile(r'(?:\((\w+)\s+as\s+(?:Integer|int)\)|\b(\w+)\s+as\s+(?:Integer|int)\b)')

# Matches x.toInteger() — group 1 is the receiver
TO_INTEGER_RE = re.compile(r'\b(\w+)\.toInteger\s*\(\s*\)')

# Matches a relational comparison (<, >, <=, >=) where one operand is a bare identifier
# and the other is a numeric literal — used to catch implicit coercion via comparison
# operator on an untyped command parameter.
# Group 1: identifier on the left of the operator.
# Group 2: identifier on the right of the operator (for `N < param` form).
RELATIONAL_RE = re.compile(
    r'(?:\b(\w+)\s*(?:<|>|<=|>=)\s*\d+\b|\b\d+\s*(?:<|>|<=|>=)\s*(\w+)\b)'
)

# Matches safeIntArg(x, ...) — confirms the safe alternative is used somewhere in the body
SAFE_INT_ARG_RE = re.compile(r'\bsafeIntArg\s*\(')

# Extracts the first argument of a safeIntArg(receiver, fallback) call — the receiver
# expression that is already guarded.  Matches the token between the opening paren and
# the first comma, accepting optional-chain dot forms (``m?.hue``, ``m.a.b``,
# ``m['key']``).  Group 1 is the receiver string (stripped of whitespace).
SAFE_INT_ARG_RECEIVER_RE = re.compile(
    r'\bsafeIntArg\s*\(\s*([\w\[\]\'".\?]+)\s*,'
)

# Matches explicit Integer/int typed declaration of the parameter name before any comparison,
# which makes the comparison safe (the sandbox enforces the type at dispatch time or the
# local assignment has already coerced safely).
# Group 1: the type-declared variable name.
TYPED_LOCAL_RE = re.compile(r'\bInteger\s+(\w+)\s*=')

# Detects a forbidden coercion expression in the *fallback* position of a safeIntArg() call.
#
# Invariant: the fallback argument to safeIntArg() must be either a safe literal/constant
# or another safeIntArg() call. Any expression containing `as Integer`/`as int`/`as Long`/
# `as long`, `.toInteger()`, `Integer.parseInt(...)`, or `Long.parseLong(...)` in the
# fallback position throws before safeIntArg's try/catch can intercept it — safeIntArg's
# protection does not extend to its own arguments.
# Correct nested form: safeIntArg(x, safeIntArg(y, N)) — the inner call is the guard.
#
# First-arg shapes accepted (Pass 1 input space):
#   simple identifier:    safeIntArg(level, ...)
#   dotted chain:         safeIntArg(state.room_size, ...)
#   optional chain:       safeIntArg(map?.field, ...)
#   bracket access:       safeIntArg(state.list[0], ...)
#   method call:          safeIntArg(getFanSpeed(), ...)
#   nested method call:   safeIntArg(state.foo.bar(), ...)
#
# The fallback-body segment `(?:[^()]*|\([^)]*\))*` permits one level of nested parens
# (e.g. `(state.room_size ?: 800)`) without crossing the outer safeIntArg closing paren.
# Cast-form alternation covers Integer/int/Long/long, .toInteger(), Integer.parseInt(),
# Long.parseLong() — the four idioms that throw on non-numeric strings.
SAFEINTARG_COERCION_FALLBACK_RE = re.compile(
    r'safeIntArg\s*\('                                              # safeIntArg(
    r'\s*\w[\w]*(?:\??\.\w+|\[[^\]]+\])*(?:\(\s*[\w.,\s\'"]*\s*\))?'  # first arg shapes
    r'\s*,'                                                         # ,
    r'\s*(?!safeIntArg\s*\()'                                       # NOT a nested safeIntArg
    r'(?:[^()]*|\([^)]*\))*'                                        # fallback body
    r'\b(?:'                                                        # coercion forms:
    r'as\s+(?:Integer|int|Long|long)\b'                             #   as Integer / int / Long / long
    r'|toInteger\s*\(\s*\)'                                         #   .toInteger()
    r'|Integer\.parseInt\s*\('                                      #   Integer.parseInt(...)
    r'|Long\.parseLong\s*\('                                        #   Long.parseLong(...)
    r')',
)


def _base_of(receiver_str: str) -> str:
    """Extract the leading variable name from ``foo?.bar``, ``foo.bar.baz``,
    ``foo['key']``, or bare ``foo``."""
    return receiver_str.split('[')[0].split('.')[0].rstrip('?')


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
    entries = config.get('bp26_unsafe_int_coercion_exemptions', [])
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


def check_rule37_unsafe_int_coercion(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE37 (Bug Pattern #26): flag bare ``as Integer`` / ``as int`` /
    ``.toInteger()`` coercions on untyped first command parameters inside
    ``set*`` / ``cycle*`` methods, AND on Map-field accesses inside
    ``set*(Map mapParam)`` methods.  These throw before ``?:`` can rescue
    on non-numeric Rule Machine inputs.  Use ``safeIntArg(x, fallback)``
    from LevoitChildBaseLib instead.

    Two scan passes:
    1. Untyped-first-param pass (original): scans ``set*(param)``/``cycle*(param)``
       methods where the first parameter has no type keyword.
    2. Map-field pass (B2 extension): scans ``set*(Map mapParam)`` methods for
       unsafe Integer coercion of Map-field accesses (``mapParam?.field as Integer``).
       Hubitat ColorControl ``setColor(Map colorMap)`` is the canonical example.
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

        # Find method body
        brace_start = raw_text.find('{', m.end() - 1)
        if brace_start == -1:
            continue
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue

        body = raw_text[brace_start:body_end]
        body_clean = _strip_line_comments(body)

        # If safeIntArg is used in the body, SOME coercions are guarded — but not
        # necessarily the one on this method's first parameter.  A method like
        #   def setX(level){ safeIntArg(other, 0); Integer v = level as Integer }
        # guards `other` but still raw-casts `level`.  Mirror the Map-field
        # partial-guard logic: collect the set of receiver expressions already passed
        # as the first argument to a safeIntArg() call, and only treat THOSE specific
        # receivers as exempt.  Continue scanning the rest of the body for a raw cast
        # on `param_name` when `param_name` is NOT in the protected set.
        #
        # Also, regardless of partial vs full guard, check that no coercion expression
        # appears in the *fallback* position of a safeIntArg() call — such expressions
        # throw before the guard can intercept them.
        param_is_safeintarg_protected = False
        if SAFE_INT_ARG_RE.search(body_clean):
            safeintarg_protected_p1: set[str] = set()
            for sir_m in SAFE_INT_ARG_RECEIVER_RE.finditer(body_clean):
                safeintarg_protected_p1.add(sir_m.group(1).strip())
            # The first param is protected if its bare name (or a receiver whose base is
            # the param) was passed as the first safeIntArg argument.
            if param_name in safeintarg_protected_p1 or any(
                _base_of(r) == param_name for r in safeintarg_protected_p1
            ):
                param_is_safeintarg_protected = True

            # Check for forbidden coercion in safeIntArg fallback argument.
            fb_m = SAFEINTARG_COERCION_FALLBACK_RE.search(body_clean)
            if fb_m:
                abs_pos = brace_start + fb_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() has a coercion expression '
                        f'(`as Integer`/`as int`/`.toInteger()`) as a fallback '
                        f'argument inside safeIntArg() — the coercion throws '
                        f'before safeIntArg\'s guard can intercept it'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        'safeIntArg\'s try/catch protects its OWN evaluation, not the '
                        'expressions passed as its arguments. A fallback like '
                        '`safeIntArg(x, (y ?: N) as Integer)` evaluates the cast '
                        'before the call, so a non-numeric `y` still throws '
                        'NumberFormatException that the sandbox swallows silently.'
                    ),
                    fix=(
                        'Nest the fallback in a second safeIntArg() call: '
                        '`safeIntArg(x, safeIntArg(y, N))`. '
                        'The inner safeIntArg guards the fallback expression itself. '
                        'Literal constant fallbacks (e.g. `safeIntArg(x, 800)`) are safe as-is.'
                    ),
                ))

            # If THIS method's first param is itself safeIntArg-guarded, its own
            # coercion is safe — skip the per-param cast scan below.  Otherwise fall
            # through and scan for an unguarded raw cast on param_name.
            if param_is_safeintarg_protected:
                continue

        # RULE37 intentionally checks only the FIRST untyped parameter of each
        # set*/cycle* method.  Non-first command parameters (e.g. brightness/colorTemp
        # in setNightlight) are not scanned here — they are covered by targeted Spock
        # regression specs in the corresponding *Spec.groovy files instead of lint.
        # This keeps RULE37 free of false-positives on the many response-field
        # `as Integer` casts that appear further into method bodies.
        #
        # TODO(v2.6+): Recognise an enclosing
        #   `if (x instanceof Number || (x instanceof String && x.isInteger()))`
        # precondition as a safety guard and skip the gated `as Integer`, removing
        # the need for the two LevoitPedestalFan/LevoitTowerFan setSpeed exemptions
        # in lint_config.yaml.  The static regex currently cannot see that guard.

        # Search for (param as Integer) / (param as int) — group(1) for parens form,
        # group(2) for bare unparenthesized form (e.g. `int secs = seconds as Integer`).
        for cast_m in AS_INTEGER_RE.finditer(body_clean):
            matched_name = cast_m.group(1) or cast_m.group(2)
            if matched_name == param_name:
                abs_pos = brace_start + cast_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses `{param_name} as Integer` '
                        f'on untyped command parameter'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`{param_name} as Integer` throws NumberFormatException / '
                        f'GroovyCastException on non-numeric input ("abc", "", "5.7", '
                        f'"true"). The `?:` Elvis operator catches null but NOT thrown '
                        f'exceptions — the exception is swallowed silently by the Hubitat '
                        f'sandbox, leaving the command a no-op with no log entry.'
                    ),
                    fix=(
                        f'Replace `{param_name} as Integer` (or `({param_name} as Integer) ?: N`) '
                        f'with `safeIntArg({param_name}, N)` from LevoitChildBaseLib. '
                        f'safeIntArg() uses .isInteger()/.isBigDecimal() chain and '
                        f'never throws.'
                    ),
                ))
                break  # one finding per method is enough

        else:
            # Search for param.toInteger() (only if as-Integer check found nothing)
            for ti_m in TO_INTEGER_RE.finditer(body_clean):
                if ti_m.group(1) == param_name:
                    abs_pos = brace_start + ti_m.start()
                    lineno = _line_of(raw_text, abs_pos)
                    findings.append(make_finding(
                        severity='FAIL',
                        rule_id='RULE37_unsafe_int_coercion',
                        title=(
                            f'BP26: {method_name}() uses `{param_name}.toInteger()` '
                            f'on untyped command parameter'
                        ),
                        file_rel=file_rel,
                        lineno=lineno,
                        raw_lines=raw_lines,
                        why=(
                            f'`.toInteger()` throws NumberFormatException on non-numeric '
                            f'strings. Rule Machine blank slots pass "" (not null); '
                            f'the sandbox swallows the exception silently.'
                        ),
                        fix=(
                            f'Replace `{param_name}.toInteger()` with '
                            f'`safeIntArg({param_name}, N)` from LevoitChildBaseLib.'
                        ),
                    ))
                    break

            else:
                # Search for relational comparison on untyped param (e.g. `if (level < 10)`).
                # Only flag when the param has NOT been assigned to a typed local (Integer x = ...)
                # before the comparison — typed locals are already safe.
                typed_locals = {m.group(1) for m in TYPED_LOCAL_RE.finditer(body_clean)}
                if param_name not in typed_locals:
                    for rel_m in RELATIONAL_RE.finditer(body_clean):
                        matched = rel_m.group(1) or rel_m.group(2)
                        if matched == param_name:
                            abs_pos = brace_start + rel_m.start()
                            lineno = _line_of(raw_text, abs_pos)
                            findings.append(make_finding(
                                severity='FAIL',
                                rule_id='RULE37_unsafe_int_coercion',
                                title=(
                                    f'BP26: {method_name}() uses a relational comparison '
                                    f'(`{param_name} < N` or similar) on untyped command '
                                    f'parameter — implicit coercion throws on non-numeric input'
                                ),
                                file_rel=file_rel,
                                lineno=lineno,
                                raw_lines=raw_lines,
                                why=(
                                    f'Comparing an untyped command parameter with a numeric '
                                    f'literal implicitly coerces it to a number. On non-numeric '
                                    f'input (e.g. "" or "abc" from a blank Rule Machine slot), '
                                    f'Groovy throws GroovyCastException before the comparison '
                                    f'executes. The sandbox swallows the exception silently.'
                                ),
                                fix=(
                                    f'Add `Integer pct = safeIntArg({param_name}, 0)` (or an '
                                    f'appropriate fallback) before the comparison, then compare '
                                    f'against `pct` instead of `{param_name}`. '
                                    f'safeIntArg() from LevoitChildBaseLib never throws.'
                                ),
                            ))
                            break

    # --- Map-field coercion extension (B2, closed-mechanism pass) ---
    # Scans set*(Map mapParam) AND set*(mapParam) methods for all enumerable unsafe
    # coercion forms.  Covers the full class predicate:
    #   (a) paren-before-as: (colorMap.hue) as Integer
    #   (b) multi-level chain: colorMap.color.hue as Integer
    #   (c) aliased local: def h = colorMap.hue; h as Integer
    #   (d) parseInt form: Integer.parseInt(colorMap.hue)
    #   (e) bracket access: colorMap['hue'] as Integer / colorMap['hue'].toInteger()
    #   (f) untyped Map param: def setColor(colorMap) with colorMap.hue as Integer
    # One finding per method is enough (break after first hit per category, then exit).

    # Collect all set*(Map ...) matches and all set*(untypedMapLookingParam ...) matches.
    # Deduplicate by method start position so a method that matches both regexes is only
    # scanned once (Map-typed regex wins; untyped is checked only when Map-typed misses).
    _map_method_positions = set()
    _map_method_matches = []

    for mm in SET_MAP_METHOD_RE.finditer(raw_text):
        _map_method_positions.add(mm.start())
        _map_method_matches.append(mm)

    for mm in SET_UNTYPED_MAP_PARAM_RE.finditer(raw_text):
        if mm.start() not in _map_method_positions:
            # Only append if not already covered by SET_MAP_METHOD_RE.
            # Also skip if SET_METHOD_RE already covers this method (it handles non-Map params).
            _map_method_positions.add(mm.start())
            _map_method_matches.append(mm)

    for m in _map_method_matches:
        method_name = m.group(1)
        map_param   = m.group(2)

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

        # Partial-guard check: safeIntArg() in the body means SOME Map fields are
        # already guarded, but not necessarily ALL of them.  A method like:
        #   def setX(Map m){ safeIntArg(m.a, 0); Integer y = m.b as Integer }
        # has m.a safe but m.b still throws.  We must NOT skip the whole method;
        # instead, collect the set of receiver expressions already passed as the
        # first argument to a safeIntArg() call and treat only those as exempt.
        #
        # Exception: still check for coercion-in-fallback (same invariant as the
        # untyped-param path) — that bug is independent of partial vs full guard.
        safeintarg_protected: set[str] = set()
        if SAFE_INT_ARG_RE.search(body_clean):
            # Collect receivers already protected.
            for sir_m in SAFE_INT_ARG_RECEIVER_RE.finditer(body_clean):
                safeintarg_protected.add(sir_m.group(1).strip())
            # Check for forbidden coercion-in-fallback (still applies regardless of
            # how many fields are protected by safeIntArg).
            fb_m = SAFEINTARG_COERCION_FALLBACK_RE.search(body_clean)
            if fb_m:
                abs_pos = brace_start + fb_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() has a coercion expression in '
                        f'safeIntArg() fallback position (Map-field method)'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        'safeIntArg fallback argument is evaluated before the call; '
                        "a coercion cast there throws before safeIntArg's guard can intercept."
                    ),
                    fix='Nest the fallback in a second safeIntArg() call.',
                ))
            # If every reachable Map-field access appears in safeintarg_protected,
            # the method may be fully guarded — but we still scan to catch any
            # Map-field coercion whose receiver is NOT in the protected set.
            # The scan below only flags receivers that are NOT in safeintarg_protected.

        # Collect alias names assigned from mapParam fields in this body.
        # e.g. ``def h = colorMap.hue`` or ``def hue = colorMap?.hue`` or
        # ``def hue = colorMap['hue']``.  Group 1: alias, Group 2: full receiver.
        alias_to_receiver = {}
        for alias_m in _ALIAS_ASSIGN_RE.finditer(body_clean):
            alias      = alias_m.group(1)
            receiver   = alias_m.group(2)
            base       = receiver.split('[')[0].split('.')[0].rstrip('?')
            if base == map_param:
                alias_to_receiver[alias] = receiver

        found_in_method = False

        # (a/b) as-Integer forms: paren-before-as + multi-level + one-hop + bracket access.
        for cast_m in _MAP_FIELD_AS_INTEGER_RE.finditer(body_clean):
            # Group 1: paren form (without the parens), Group 2: bare/bracket form.
            receiver = cast_m.group(1) or cast_m.group(2)
            if receiver is None:
                continue
            base = _base_of(receiver)
            if base == map_param or base in alias_to_receiver:
                label = alias_to_receiver[base] if base in alias_to_receiver else receiver
                # Skip if this specific receiver is already guarded by safeIntArg().
                if receiver in safeintarg_protected or label in safeintarg_protected:
                    continue
                abs_pos = brace_start + cast_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses `{receiver} as Integer` '
                        f'on Map-field access — throws on decimal strings or blank inputs'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`{label} as Integer` throws NumberFormatException / '
                        f'GroovyCastException on non-numeric Map values ("55.5", "", "abc"). '
                        f'Hubitat ColorControl tiles and Rule Machine color-map expressions '
                        f'can produce these via hub-variable bindings or expression slots.'
                    ),
                    fix=(
                        f'Replace with `safeIntArg({label}, <fallback>)` from LevoitChildBaseLib.'
                    ),
                ))
                found_in_method = True
                break

        if found_in_method:
            continue

        # (e/b) toInteger() forms: one-hop, multi-hop, bracket access.
        for ti_m in _MAP_FIELD_TO_INTEGER_RE.finditer(body_clean):
            receiver = ti_m.group(1)
            base = _base_of(receiver)
            if base == map_param or base in alias_to_receiver:
                if receiver in safeintarg_protected:
                    continue
                abs_pos = brace_start + ti_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses `{receiver}.toInteger()` '
                        f'on Map-field access'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`.toInteger()` throws NumberFormatException on non-numeric '
                        f'Map field values. Use safeIntArg() instead.'
                    ),
                    fix=(
                        f'Replace `{receiver}.toInteger()` with '
                        f'`safeIntArg({receiver}, <fallback>)` from LevoitChildBaseLib.'
                    ),
                ))
                found_in_method = True
                break

        if found_in_method:
            continue

        # (c) Alias coercion: ``def h = colorMap.hue; h as Integer``.
        # AS_INTEGER_RE captures bare `alias as Integer` (group 2 of AS_INTEGER_RE).
        for cast_m in AS_INTEGER_RE.finditer(body_clean):
            alias = cast_m.group(1) or cast_m.group(2)
            if alias and alias in alias_to_receiver:
                original_receiver = alias_to_receiver[alias]
                if original_receiver in safeintarg_protected or alias in safeintarg_protected:
                    continue
                abs_pos = brace_start + cast_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses alias `{alias} as Integer` '
                        f'(assigned from Map-field `{original_receiver}`)'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`{alias}` was assigned from `{original_receiver}` which is a '
                        f'Map field value (String at runtime). `{alias} as Integer` throws '
                        f'NumberFormatException / GroovyCastException on non-numeric input.'
                    ),
                    fix=(
                        f'Assign `{alias} = safeIntArg({original_receiver}, <fallback>)` '
                        f'instead of direct assignment + coercion.'
                    ),
                ))
                found_in_method = True
                break

        if found_in_method:
            continue

        # (c) Alias toInteger(): ``def h = colorMap.hue; h.toInteger()``.
        for ti_m in TO_INTEGER_RE.finditer(body_clean):
            alias = ti_m.group(1)
            if alias in alias_to_receiver:
                original_receiver = alias_to_receiver[alias]
                if original_receiver in safeintarg_protected or alias in safeintarg_protected:
                    continue
                abs_pos = brace_start + ti_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses alias `{alias}.toInteger()` '
                        f'(assigned from Map-field `{original_receiver}`)'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`{alias}` was assigned from `{original_receiver}`, a Map field '
                        f'(String at runtime). `.toInteger()` throws on non-numeric values.'
                    ),
                    fix=(
                        f'Assign `{alias} = safeIntArg({original_receiver}, <fallback>)` '
                        f'instead of direct assignment + .toInteger().'
                    ),
                ))
                found_in_method = True
                break

        if found_in_method:
            continue

        # (d) Integer.parseInt / Long.parseLong on a Map-field access.
        for pi_m in _PARSEINT_RE.finditer(body_clean):
            receiver = pi_m.group(1)
            base = _base_of(receiver)
            if base == map_param or base in alias_to_receiver:
                if receiver in safeintarg_protected:
                    continue
                abs_pos = brace_start + pi_m.start()
                lineno = _line_of(raw_text, abs_pos)
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE37_unsafe_int_coercion',
                    title=(
                        f'BP26: {method_name}() uses Integer.parseInt({receiver}) '
                        f'on Map-field access — throws NumberFormatException on non-numeric input'
                    ),
                    file_rel=file_rel,
                    lineno=lineno,
                    raw_lines=raw_lines,
                    why=(
                        f'`Integer.parseInt({receiver})` throws NumberFormatException on '
                        f'decimal strings ("55.5"), blank strings (""), or non-numeric values. '
                        f'Map values from RM color-map expressions or dashboard tiles can be '
                        f'any of these.'
                    ),
                    fix=(
                        f'Replace with `safeIntArg({receiver}, <fallback>)` from LevoitChildBaseLib.'
                    ),
                ))
                break

    return findings


ALL_RULES = [check_rule37_unsafe_int_coercion]
