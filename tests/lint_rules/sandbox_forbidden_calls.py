"""
sandbox_forbidden_calls.py — RULE57: flag Hubitat-Groovy-sandbox-forbidden
method calls / constructs in driver and library source.

The Hubitat driver/app Groovy sandbox enforces a method allowlist at COMPILE
time. A source file that calls a forbidden method fails to compile with
``Expression [MethodCallExpression] is not allowed: <expr>`` — the driver (and
every driver that ``#include``s an offending library) will not load at all.

The confirmed-forbidden call that motivated this rule is ``getClass()``: a
debug log line ``resp.data?.getClass()?.simpleName`` shipped in a shared
library error path and every dependent driver failed to compile on the live
hub. The Spock harness does NOT catch this class because it does not enforce
the Hubitat sandbox allowlist — only a static check (this rule) or a live-hub
save does. This is a closed mechanism for the whole class, not just the one
``getClass()`` instance.

Detection scope:
  - ``.groovy`` files under ``Drivers/Levoit/`` — both driver AND library files
    (the outage was in ``LevoitChildBaseLib.groovy``).
  - Comment-aware (uses the harness ``cleaned_lines``) and string-literal-aware
    (the interior of ``'...'`` / ``"..."`` / triple-quoted literals is blanked),
    so a forbidden token inside a ``//`` comment or a log-string literal does
    NOT flag.

Forbidden constructs flagged (FAIL):
  - ``getClass()``            — reflection; the confirmed outage call
  - ``.execute()``           — shell/process execution
  - bare ``System.``         — ``System.exit`` / ``System.getProperty`` etc.
  - bare ``Runtime.``        — ``Runtime.getRuntime()`` etc.
  - bare ``Thread.``         — thread control
  - ``GroovyShell``          — dynamic Groovy evaluation
  - ``ClassLoader``          — classloader access
  - ``.newInstance(``        — reflective instantiation
  - ``Eval.``                — dynamic evaluation

Explicitly NOT flagged (sandbox-LEGAL — must stay passing):
  - ``e.metaClass.respondsTo(e, 'getResponse')`` — the ``getClass`` token does
    not appear in ``metaClass``; ``respondsTo`` is allowed. Used in the parent
    driver and confirmed compiling on the live hub.
  - ``instanceof`` type checks.
  - ``.simpleName`` on a non-``getClass()`` expression (the fixed line uses an
    ``instanceof`` ternary, not ``getClass().simpleName``).
  - ordinary method calls, and any of the above tokens inside a comment or a
    plain string literal.

Exemptions:
  Add ``sandbox_forbidden_calls_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      construct: getClass()
      rationale: "why this specific call is actually sandbox-legal here"
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# (compiled regex, human label) for each forbidden construct.  Order is
# stable so findings are deterministic; each match emits one FAIL.
FORBIDDEN_CONSTRUCTS = [
    (re.compile(r'\bgetClass\s*\('), "getClass()"),
    (re.compile(r'\.execute\s*\('), ".execute()"),
    (re.compile(r'\bSystem\s*\.'), "System."),
    (re.compile(r'\bRuntime\s*\.'), "Runtime."),
    (re.compile(r'\bThread\s*\.'), "Thread."),
    (re.compile(r'\bGroovyShell\b'), "GroovyShell"),
    (re.compile(r'\bClassLoader\b'), "ClassLoader"),
    (re.compile(r'\.newInstance\s*\('), ".newInstance("),
    (re.compile(r'\bEval\s*\.'), "Eval."),
]


def _blank_string_literals(text: str) -> str:
    """Blank the plain-text CONTENT of Groovy string literals with spaces,
    preserving quotes, length, and newlines so byte offsets still map 1:1 to
    line numbers. ``clean_source`` blanks comments but NOT string literals; this
    pass complements it so a forbidden token inside a plain log-string (e.g.
    ``"never call getClass() here"``) is not read as a code call.

    CRITICAL: inside a double-quoted GString, ``${...}`` interpolation blocks are
    LIVE code that DOES execute and DOES hit the sandbox — the confirmed outage
    was ``logDebug "...${resp.data?.getClass()?.simpleName}..."``, forbidden code
    inside an interpolation. Those blocks are PRESERVED (not blanked) so the rule
    still catches a forbidden call there. Single-quoted strings are not
    interpolated in Groovy, so their whole interior is blanked."""
    out = list(text)
    n = len(text)
    i = 0
    while i < n:
        c = text[i]
        if c in ('"', "'"):
            triple = text[i:i + 3] in ('"""', "'''")
            quote = c
            delim = quote * 3 if triple else quote
            dlen = len(delim)
            is_gstring = (quote == '"')  # double-quoted -> live ${...} interpolation
            j = i + dlen
            while j < n:
                if text[j] == '\\':  # escaped char — leave as-is, advance past both
                    j += 2
                    continue
                if text[j:j + dlen] == delim:
                    j += dlen
                    break
                if is_gstring and text[j:j + 2] == '${':
                    # Preserve the interpolation block (live code) — skip to the
                    # matching brace by depth-counting; leave out[j:k] untouched.
                    depth = 0
                    k = j
                    while k < n:
                        if text[k] == '{':
                            depth += 1
                        elif text[k] == '}':
                            depth -= 1
                            if depth == 0:
                                k += 1
                                break
                        k += 1
                    j = k
                    continue
                if out[j] != '\n':
                    out[j] = ' '
                j += 1
            i = j
            continue
        i += 1
    return "".join(out)


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('sandbox_forbidden_calls_exemptions', [])
    if not isinstance(entries, list):
        return exemptions
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        file_ = str(entry.get('file', '')).replace('\\', '/').strip()
        construct = str(entry.get('construct', '')).strip()
        rationale = str(entry.get('rationale', '')).strip()
        if file_ and construct and rationale:
            exemptions.add((file_, construct))
    return exemptions


def check_rule57_sandbox_forbidden_calls(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE57: flag Hubitat-Groovy-sandbox-forbidden method calls / constructs.

    A forbidden call fails to COMPILE inside the Hubitat sandbox
    (``Expression [MethodCallExpression] is not allowed``), so the driver — and
    every driver ``#include``-ing an offending library — never loads. The Spock
    harness does not enforce the sandbox allowlist, so this static check is the
    only pre-hub gate for the class.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    # Comment-blanked (harness) + string-literal-blanked so tokens inside
    # comments or string literals never flag. Line geometry preserved 1:1.
    scan_lines = _blank_string_literals("\n".join(cleaned_lines)).split("\n")

    for idx, line in enumerate(scan_lines, 1):
        for pattern, label in FORBIDDEN_CONSTRUCTS:
            if (file_rel, label) in exemption_set:
                continue
            if pattern.search(line):
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE57_sandbox_forbidden_call',
                    title=(
                        f'Hubitat-sandbox-forbidden construct `{label}` — '
                        f'the driver/library will fail to compile on the hub'
                    ),
                    file_rel=file_rel,
                    lineno=idx,
                    raw_lines=raw_lines,
                    why=(
                        f'`{label}` is not on the Hubitat Groovy sandbox method '
                        f'allowlist. The sandbox rejects it at COMPILE time '
                        f'(`Expression [MethodCallExpression] is not allowed`), so '
                        f'this file — and every driver that #includes it — fails to '
                        f'load on the hub. The Spock harness does not enforce the '
                        f'sandbox allowlist, so it will not catch this.'
                    ),
                    fix=(
                        f'Remove the `{label}` call. For type inspection use an '
                        f'`instanceof` check (e.g. `x instanceof String ? "String" '
                        f': "non-Map"`) instead of `getClass()`. If the call is '
                        f'genuinely sandbox-legal in this context, add a '
                        f'sandbox_forbidden_calls_exemptions entry with a rationale.'
                    ),
                ))

    return findings


ALL_RULES = [check_rule57_sandbox_forbidden_calls]
