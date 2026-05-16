"""
bp26_unsafe_int_coercion.py — RULE37: flag bare ``as Integer`` / ``as int`` /
``.toInteger()`` coercions on untyped command parameters inside ``set*`` /
``cycle*`` methods.

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

Detection scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - Only inside method bodies whose name starts with ``set`` or ``cycle``.
  - Only coercions that operate on a name matching the first (untyped) param
    of the enclosing method — statically-typed params (``Integer level``) are
    safe because the Hubitat sandbox enforces the type at dispatch time.
  - Flags: ``(x as Integer)``, ``(x as int)``, ``x.toInteger()``.
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

# Matches def set*(param) or def cycle*(param) — captures method name and first param.
# Untyped first param only (no type keyword before the name).
# Group 1: method_name, Group 2: first_param_name (no type keyword immediately before it)
SET_METHOD_RE = re.compile(
    r'^\s*def\s+((?:set|cycle)\w+)\s*\(\s*(\w+)\s*(?:[,=)])',
    re.MULTILINE,
)

# Matches (x as Integer) / (x as int) [parenthesized] OR bare x as Integer / x as int
# [unparenthesized, word-boundary anchored].  Group 1 is the cast subject in both forms.
# The parenthesized form is tried first (longer, more specific); the bare form catches
# unparenthesized assignments like `int secs = seconds as Integer`.
AS_INTEGER_RE = re.compile(r'(?:\((\w+)\s+as\s+(?:Integer|int)\)|\b(\w+)\s+as\s+(?:Integer|int)\b)')

# Matches x.toInteger() — group 1 is the receiver
TO_INTEGER_RE = re.compile(r'\b(\w+)\.toInteger\s*\(\s*\)')

# Matches safeIntArg(x, ...) — confirms the safe alternative is used
SAFE_INT_ARG_RE = re.compile(r'\bsafeIntArg\s*\(')

# Detects a forbidden coercion expression in the *fallback* position of a safeIntArg() call.
#
# Invariant: the fallback argument to safeIntArg() must be either a safe literal/constant
# or another safeIntArg() call. Any expression containing `as Integer`, `as int`, or
# `.toInteger()` in the fallback position throws before safeIntArg's try/catch can
# intercept it — safeIntArg's protection does not extend to its own arguments.
# Correct nested form: safeIntArg(x, safeIntArg(y, N)) — the inner call is the guard.
#
# Pattern: safeIntArg(<first_arg>, <fallback>) where fallback is NOT immediately another
# safeIntArg( call, and fallback contains a coercion cast.
# The `(?:[^()]*|\([^)]*\))*` segment permits one level of nested parens in the fallback
# (e.g. `(state.room_size ?: 800)`) without crossing the outer safeIntArg closing paren.
SAFEINTARG_COERCION_FALLBACK_RE = re.compile(
    r'safeIntArg\s*\(\s*\w[\w.]*\s*,'    # safeIntArg(<arg1>,
    r'\s*(?!safeIntArg\s*\()'             # NOT immediately followed by another safeIntArg(
    r'(?:[^()]*|\([^)]*\))*'             # fallback body: non-paren chars OR one paren group
    r'\b(?:as\s+(?:Integer|int)\b|toInteger\s*\(\s*\))',  # coercion present in fallback
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
    ``set*`` / ``cycle*`` methods.  These throw before ``?:`` can rescue
    on non-numeric Rule Machine inputs.  Use ``safeIntArg(x, fallback)``
    from LevoitChildBaseLib instead.
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

        # If safeIntArg is used in the body, the primary parameter coercion is safe.
        # However, also check that no coercion expression appears in the *fallback*
        # position of a safeIntArg() call — such expressions throw before the guard
        # can intercept them (safeIntArg's try/catch does not protect its arguments).
        if SAFE_INT_ARG_RE.search(body_clean):
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

    return findings


ALL_RULES = [check_rule37_unsafe_int_coercion]
