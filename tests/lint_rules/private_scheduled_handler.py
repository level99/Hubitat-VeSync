"""
private_scheduled_handler.py — RULE52: flag a `private` method that is named as
the string-literal handler argument of a ``runIn`` / ``runInMillis`` / ``schedule``
call in the SAME file.

Bug class (v2.10 cluster 2 — "private scheduled handler is unreachable"):

Hubitat's scheduler stores a handler as a String name and later invokes it by
name through the Groovy MetaObject Protocol (dynamic dispatch on the device/app
instance). A ``private`` method compiles to INVOKESPECIAL bytecode, which is
OUTSIDE the MOP — the same reason ``login()`` must stay non-private for test
mocking (see BP13). So ``runIn(2, "getDevices")`` where ``getDevices`` is
``private`` fails to invoke: the scheduled self-heal / poll / timeout handler
silently never runs.

Every other string-scheduled handler in this codebase is deliberately
non-private (``updateDevices``, ``initialize``, ``logDebugOff``, ``timeOutLevoit``,
``logsOff``, …). This rule makes that convention mechanical.

Authoritative predicate (the CLASS, not an example list):
    A method declared ``private`` in a driver/app/library ``.groovy`` file whose
    name also appears as the string-literal handler argument (the 2nd positional
    argument) of a ``runIn`` / ``runInMillis`` / ``schedule`` call in the same file.

Detection scope:
  - ``.groovy`` files under ``Drivers/Levoit/``.
  - Handler discovery and private-decl discovery are both per-file. In this
    codebase a scheduled handler and its declaration are always colocated in the
    same file (libraries are inlined by ``#include`` at parse time, so a driver
    that schedules a method also carries that method's body). Cross-file
    scheduling of a lib-private method is not a shape this codebase uses.
  - Comment content is removed before scanning (via the harness ``cleaned_lines``);
    string-literal content is preserved because the handler NAME is a string
    literal that the rule must read.

Must-catch:
  - ``private Boolean getDevices() { ... }`` + ``runIn(2, "getDevices")``.

Must-NOT-catch (safe by construction):
  - A non-private scheduled handler (``def``/``void``/typed, no ``private``).
  - A ``private`` method that is NEVER scheduled.
  - A name that appears only in ``unschedule("name")`` (unschedule does not invoke).

Note on cron literals: ``schedule(cron, "handler")`` in this codebase always
passes the cron as a VARIABLE (never an inline literal containing commas), so the
2nd-argument extraction is unambiguous. An inline cron literal with a comma is not
a shape used here.
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# A `private` method declaration on a single line. Requires the `private` keyword,
# then any modifiers/type up to (but not through) a `=` (so private FIELDS with an
# initializer are excluded), then NAME immediately followed by `(` (a method's param
# list). Group 1 = method name.
_PRIVATE_METHOD_RE = re.compile(
    r'^[ \t]*private\b[^=\n]*?\b(\w+)\s*\('
)

# A string-scheduled handler: runIn / runInMillis / schedule, first positional arg
# (delay expr or cron var — no comma), then the quoted handler name as arg 2.
# Group 1 = handler method name.
_SCHEDULE_RE = re.compile(
    r'\b(?:runIn|runInMillis|schedule)\s*\(\s*[^,\n]+,\s*["\'](\w+)["\']'
)


def check_rule52_private_scheduled_handler(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """RULE52: FAIL a ``private`` method that is used as a string-literal
    runIn/runInMillis/schedule handler in the same file. Hubitat's scheduler
    invokes handlers by name through the Groovy MOP, which cannot reach a
    ``private`` (INVOKESPECIAL) method, so the scheduled call silently never
    fires. Drop the ``private`` modifier (package-default visibility)."""
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    # Collect private method names (name -> first-decl line number).
    private_methods = {}
    for i, line in enumerate(cleaned_lines):
        m = _PRIVATE_METHOD_RE.match(line)
        if m:
            name = m.group(1)
            if name not in private_methods:
                private_methods[name] = i + 1  # 1-based

    if not private_methods:
        return findings

    # Scan for schedule/runIn calls whose handler name is a private method.
    for i, line in enumerate(cleaned_lines):
        for sm in _SCHEDULE_RE.finditer(line):
            handler = sm.group(1)
            if handler in private_methods:
                findings.append(make_finding(
                    severity='FAIL',
                    rule_id='RULE52_private_scheduled_handler',
                    title=(
                        f'`{handler}` is scheduled by name but declared `private` '
                        f'(decl line {private_methods[handler]}) — the handler will never fire'
                    ),
                    file_rel=file_rel,
                    lineno=i + 1,
                    raw_lines=raw_lines,
                    why=(
                        f'Hubitat\'s scheduler invokes a runIn/schedule handler by its String '
                        f'name through the Groovy MetaObject Protocol. A `private` method '
                        f'compiles to INVOKESPECIAL bytecode, which is outside the MOP (the same '
                        f'reason login() must stay non-private for test mocking). So '
                        f'`{handler}` is never actually called by the scheduler — the poll / '
                        f'self-heal / timeout it drives silently stops working.'
                    ),
                    fix=(
                        f'Drop the `private` modifier from `{handler}(...)` (use package-default '
                        f'visibility, like every other scheduled handler: updateDevices, '
                        f'initialize, logDebugOff, timeOutLevoit).'
                    ),
                ))

    return findings


ALL_RULES = [check_rule52_private_scheduled_handler]
