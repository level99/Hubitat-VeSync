"""
bp18_null_guard.py — RULE27: flag set* command methods whose parameter is used in
a string-normalization call without a preceding explicit null-guard.

Bug Pattern #18 — Rule Machine 'Run Custom Action' with an empty/unset action
parameter passes null to the driver method. Any ``(arg as String).toLowerCase()``
or similar cast-then-call expression evaluates as ``null.toLowerCase()`` and throws
NullPointerException. Hubitat's driver sandbox catches and discards driver exceptions
silently — the user sees nothing: the command appears to have run, no attribute
changes, no log entry.

Historical occurrence:
  v2.3 audit found 17 sites across 13 child drivers — setMode(), setSpeed(),
  setNightLight(), setNightlightMode() — all using the ``(arg as String).toLowerCase()``
  pattern immediately after the logDebug call, with no null check. Fixed in commit
  56649f6 by inserting ``if (arg == null) { logWarn "...; ignoring"; return }`` before
  each vulnerable line.

Detection algorithm:
  For each method matching ``def set[A-Z][a-zA-Z]+(<optional type> <arg>)``:
    1. Extract arg name from the signature.
    2. Read the method body (brace-balanced, from opening `{` to matching `}`).
    3. Scan the body for vulnerable patterns where the arg is used in a normalization
       call or arithmetic comparison without a preceding null check.
    4. For each vulnerable hit, check whether there is an EARLIER position in the same
       method body that contains EITHER accepted guard form (see below).
    5. If neither guard form is present before the vulnerable call → FAIL.

Accepted guard forms (both are functionally equivalent):
  Form 1 — inline equality check:
    ``if (arg == null)`` or ``if (null == arg)`` (Yoda style).
  Form 2 — helper call (canonical since Phase 3+ drivers, v2.5):
    ``requireNotNull(arg, ...)`` — provided by LevoitChildBaseLib.  The helper
    checks for null, logs a WARN, and returns false; the driver exits via
    ``if (!requireNotNull(arg, "setX")) return false``.

  Elvis operator (``arg ?: default``) is NOT accepted: it silently substitutes a
  default value and proceeds, which produces a wrong API call without any user
  notification. The BP18 canonical fix requires warn-and-return, which requires an
  explicit null check.

Vulnerable normalization patterns detected (on the param name `arg`):
  - ``(arg as String).toLowerCase()`` / ``.toUpperCase()`` / ``.trim()``
  - ``arg.toLowerCase()`` / ``.toUpperCase()`` / ``.toInteger()`` / ``.toLong()`` / ``.toDouble()``

Vulnerable arithmetic-on-null patterns detected (v2.5+ extension):
  - ``if (arg < N)``  ``if (arg > N)``  ``if (arg <= N)``  ``if (arg >= N)``
  - ``if (arg == 0)``  ``if (arg == N)``  ``if (arg != null)`` (used as surrogate check)
  where ``N`` is a numeric literal and ``arg`` is the raw parameter name without coercion.

  Rationale: ``(level as Integer)`` coercion on a null param in Groovy/Hubitat sandbox
  yields ``null`` (not 0), so ``if (level < 1)`` on null throws NullPointerException
  silently.  The v2.5 Tier 3 audit found 6 latent sites of this shape (setMistLevel,
  setTargetHumidity, setLevel across 6 humidifier drivers) that passed the original
  string-normalization-only RULE27.  All 6 were fixed in Tier 1/3; this extended rule
  prevents the pattern from re-appearing in new drivers.

Scope: all .groovy files under Drivers/Levoit/ — including library files.

Library-aware (inherent): this rule does not call ``is_library_file()`` and
therefore already scans library files directly when lint.py invokes it with a
lib file path.  Findings in lib code are attributed to the lib file.  No
additional driver-level lib-content expansion is needed.

Exemptions: use the standard lint_config.yaml exemptions mechanism.
"""

import re
from pathlib import Path

# Only check .groovy driver files.
DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Match ``def setXxx(arg)`` or ``def setXxx(Type arg)`` with the body opening brace
# on the same line.
# Group 1: method name (setXxx)
# Group 2: arg name (the last word inside the parens — handles optional leading type)
SET_METHOD_RE = re.compile(
    r'^\s*def\s+(set[A-Z][a-zA-Z0-9]*)\s*\(\s*(?:[A-Za-z][a-zA-Z0-9]*\s+)?([a-zA-Z][a-zA-Z0-9]*)\s*\)\s*\{',
    re.MULTILINE,
)


def _make_vuln_re(arg: str) -> re.Pattern:
    """
    Build a regex that matches any string-normalization call OR arithmetic comparison
    on ``arg`` where null-coercion would cause NullPointerException.

    String-normalization patterns:
      (arg as String).toLowerCase()    (arg as String).toUpperCase()    (arg as String).trim()
      arg.toLowerCase()    arg.toUpperCase()    arg.toInteger()    arg.toLong()    arg.toDouble()

    Arithmetic-on-null patterns (v2.5+ extension — catches setMistLevel/setLevel NPE class):
      if (arg < N)   if (arg > N)   if (arg <= N)   if (arg >= N)   if (arg == 0)   if (arg == N)
      where N is a numeric literal and arg is the raw parameter name.

      Rationale: in Groovy, ``(level as Integer)`` where level is null yields null (not 0).
      Any arithmetic comparison on the unconverted param therefore hits NPE silently.
      The guard forms ``requireNotNull`` / ``if (arg == null)`` satisfy the check because
      they appear BEFORE the comparison — the conversion ``Integer lvl = (level as Integer)``
      that would happen post-guard is safe.

    Does NOT match calls on local variables or state properties that happen to share
    the arg name (uses negative lookbehind for `.` on the direct pattern, preventing
    ``state.mode.toLowerCase()`` from matching when arg is ``mode``; and word-boundary
    anchoring on arithmetic patterns prevents `setPercentage` arg matching `percent`
    substring of `targetPercent`).
    """
    a = re.escape(arg)
    cast_pattern = (
        rf'\(\s*{a}\s+as\s+String\s*\)\s*\.\s*(?:toLowerCase|toUpperCase|trim)\s*\(\s*\)'
    )
    # Negative lookbehind for `.` prevents matching `state.mode.toLowerCase()` where
    # `mode` is a property name on an object, not the method parameter itself.
    direct_pattern = (
        rf'(?<!\.)\b{a}\s*\.\s*(?:toLowerCase|toUpperCase|toInteger|toLong|toDouble)\s*\(\s*\)'
    )
    # Arithmetic-on-null: if (arg <op> <numericLiteral>) where op is a comparison operator.
    # Positive lookahead ensures the right-hand side is a number, preventing matches on
    # non-numeric comparisons like `if (mode == "on")` (those are string guards, not arithmetic).
    # Negative lookbehind prevents matching state.level or similar prefixed names.
    arithmetic_pattern = (
        rf'(?<![.\w])\b{a}\b\s*(?:[<>]=?|==)\s*\d+(?!\w)'
    )
    return re.compile(rf'(?:{cast_pattern}|{direct_pattern}|{arithmetic_pattern})')


def _make_guard_re(arg: str) -> re.Pattern:
    """
    Build a regex that matches an explicit null-equality guard on ``arg``.

    Matches:
      if (arg == null)      if ( arg == null )
      if (null == arg)      if ( null == arg )   (Yoda style)

    Does NOT match Elvis (``arg ?: ...``) — see module docstring for rationale.
    """
    a = re.escape(arg)
    return re.compile(
        rf'if\s*\(\s*(?:{a}\s*==\s*null|null\s*==\s*{a})\s*\)'
    )


def _make_require_not_null_re(arg: str) -> re.Pattern:
    """
    Build a regex that matches a ``requireNotNull(arg, ...)`` helper call on ``arg``.

    Matches:
      requireNotNull(arg, "setDisplay")
      if (!requireNotNull(arg, "setDisplay")) return false
      requireNotNull( arg , "methodName")

    The helper (from LevoitChildBaseLib) checks for null, logs a WARN, and returns
    false.  Its presence before a normalization call satisfies the BP18 guard
    requirement (Form 2 — see module docstring).
    """
    a = re.escape(arg)
    return re.compile(
        rf'requireNotNull\s*\(\s*{a}\s*,'
    )


def _find_method_body_end(source: str, brace_start: int) -> int:
    """
    Starting at ``brace_start`` (position of the opening ``{`` of a method body
    in ``source``), walk forward counting brace depth and return the position
    immediately after the matching closing ``}``.

    Returns -1 if the body is unterminated (malformed source).
    """
    depth = 0
    for i in range(brace_start, len(source)):
        ch = source[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i + 1  # position after the closing `}`
    return -1


def _line_of(source: str, pos: int) -> int:
    """Return the 1-based line number of character position ``pos`` in ``source``."""
    return source[:pos].count('\n') + 1


def check_rule27_bp18_null_guard(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE27 (Bug Pattern #18): Fail if a set* command method uses a normalization call
    on its parameter without a preceding explicit null-guard.

    Operates on raw_text to avoid cleaned_lines stripping string literals that might
    contain the parameter name (false negatives). Line-comment stripping is approximated
    by the SET_METHOD_RE and guard/vuln REs not matching inside ``//`` lines — acceptable
    for this heuristic.

    Returns a list of FAIL findings, one per unguarded vulnerable pattern found.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for m in SET_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)
        arg_name = m.group(2)

        # The opening brace of the method body is the last char of the match.
        brace_start = m.end() - 1
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            # Unterminated method — skip (malformed source; compile will fail anyway)
            continue

        body = raw_text[brace_start:body_end]

        # Strip full-line comments before scanning so that a Groovy comment like
        #   // Note: (mode as String).toLowerCase() — example of the bug
        # inside a guarded method body does not produce a spurious FAIL finding.
        # Inline-comment tails (someCode  // note) are preserved in the code portion,
        # which is correct — we want to match real code, not commented-out code.
        body_for_scan = re.sub(r'(?m)^\s*//.*$', '', body)

        vuln_re = _make_vuln_re(arg_name)
        guard_re = _make_guard_re(arg_name)
        require_not_null_re = _make_require_not_null_re(arg_name)

        for vuln_match in vuln_re.finditer(body_for_scan):
            vuln_pos_in_body = vuln_match.start()

            # Is there either accepted guard form earlier in the same body?
            # Form 1: inline null-equality check  if (arg == null) / if (null == arg)
            # Form 2: requireNotNull(arg, ...) helper call (LevoitChildBaseLib, canonical since v2.5)
            guard_match = guard_re.search(body_for_scan, 0, vuln_pos_in_body)
            require_not_null_match = require_not_null_re.search(body_for_scan, 0, vuln_pos_in_body)
            if guard_match or require_not_null_match:
                # At least one accepted guard precedes the vulnerable call — OK
                continue

            # Neither guard form present — FAIL
            # Compute the absolute line number for the vulnerable expression.
            abs_vuln_pos = brace_start + vuln_pos_in_body
            vuln_line = _line_of(raw_text, abs_vuln_pos)

            # Pull the raw line for context
            raw_line = raw_lines[vuln_line - 1] if 0 < vuln_line <= len(raw_lines) else ""

            findings.append({
                "severity": "FAIL",
                "rule_id": "RULE27_bp18_null_guard",
                "title": (
                    f"{method_name}({arg_name}) uses parameter without a preceding "
                    f"null-guard (string-normalization or arithmetic-on-null — Bug Pattern #18)"
                ),
                "file": file_rel,
                "line": vuln_line,
                "context": f"    {raw_line}",
                "why": (
                    f"Rule Machine 'Run Custom Action' with an empty/unset parameter "
                    f"passes null to the driver method. String coercions like `({arg_name} as String)` "
                    f"and arithmetic comparisons like `if ({arg_name} < N)` both throw "
                    f"NullPointerException when `{arg_name}` is null. Hubitat's driver sandbox "
                    f"discards driver exceptions silently — the user sees nothing: no attribute "
                    f"change, no log entry, no error. See Bug Pattern #18 in CLAUDE.md."
                ),
                "fix": (
                    f"Add a null-guard at the top of {method_name}(), before the first use of "
                    f"the parameter. Two accepted forms: "
                    f"(1) helper (canonical): "
                    f"`if (!requireNotNull({arg_name}, \"{method_name}\")) return` "
                    f"(requires LevoitChildBaseLib #include); "
                    f"(2) inline: "
                    f"`if ({arg_name} == null) {{ logWarn \"{method_name} called with null "
                    f"{arg_name} (likely empty Rule Machine action parameter); ignoring\"; return }}`"
                    f". Elvis (`{arg_name} ?: default`) is NOT accepted — it proceeds silently "
                    f"with a wrong value. Also ensure `def logWarn(msg){{ log.warn msg }}` is "
                    f"defined in the driver (or included via LevoitChildBaseLib)."
                ),
            })

    return findings


ALL_RULES = [check_rule27_bp18_null_guard]
