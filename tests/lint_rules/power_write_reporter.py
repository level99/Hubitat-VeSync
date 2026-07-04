"""
power_write_reporter.py — RULE53: flag a raw ``recordError(...)`` call inside an
``on()`` / ``off()`` power method body — it must route through the blessed
``reportWriteError`` / ``reportWriteFailure`` helper instead.

Bug class ("power-write failure bypasses BP22 outage dedup"):

The Switch power methods ``on()`` and ``off()`` report a failed power write with a
bare ``logError(...) + recordError(...)`` pair. That bypasses the BP22 v2.9
child-side outage dedup: during a known VeSync network outage the parent already
surfaces the outage once, and every child's write-fail branch is supposed to
downgrade to a single DEBUG (not spam one ERROR + one diagnostics-ring record per
device per retry). The centralized reporter ``reportWriteError(tag, ctx)`` (in
``LevoitChildBaseLib``) does exactly that:
  - known outage  => one DEBUG, no ERROR, no record
  - otherwise     => the prior logError(tag) + recordError(tag, ctx)
The already-correct sibling power methods (CorePurifierLib / VitalPurifierLib
on()/off()) call ``reportWriteError``; the drivers that bypassed it were an
inconsistency, flooding logs during an outage.

Why scope to ``on()`` / ``off()`` (and not every setter):
    on()/off() are no-argument Switch methods — they have NO input-validation
    branch and NO status-read branch, so EVERY ``recordError`` in their body is a
    power-write failure that must use the helper. That makes "a raw recordError in
    on()/off()" a reliable, false-positive-free predicate. Capability-required
    setters (setSpeed/setLevel/setMode) mix write-failure branches (which already
    use reportWriteError/reportWriteFailure uniformly) with legitimate
    input-validation branches (``Invalid mode: ...``) that correctly use a raw
    logError+recordError — so a setter-scoped mechanical rule would false-positive
    on validation. The on()/off() scope captures 100% of the actual violation class.

Authoritative predicate (the CLASS, not an example list):
    A ``recordError(`` call token inside the body of a ``def on(...)`` or
    ``def off(...)`` method, where the call is NOT itself a
    ``reportWriteError(`` / ``reportWriteFailure(`` call.

Detection scope:
  - ``.groovy`` files under ``Drivers/Levoit/``.
  - Comment content is removed before scanning (harness ``cleaned_lines``);
    string-literal content is preserved, but a ``recordError(`` token only ever
    appears as real code (it is a call, not a plausible log-string substring the
    way ``.data.result`` was), so no extra string-blanking is needed — and the
    ``def on(``/``def off(`` body bounds already exclude unrelated code.

Must-catch:
  - ``def on(){ ... else { logError "Power on failed"; recordError("...", [...]) } }``

Must-NOT-catch (safe by construction):
  - ``def on(){ ... else { reportWriteError("Power on failed", [...]) } }`` (the fix).
  - ``def off(){ ... else reportWriteFailure("...", resp, [...]) }``.
  - A raw ``recordError`` inside a NON-power method (setMode validation, update()
    "No status returned", checkHttpResponse) — out of this rule's scope.
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# A `def on(` / `def off(` method definition. Group 1 = method name.
_POWER_METHOD_RE = re.compile(
    r'^[ \t]*(?:def|void|Boolean|boolean|Object)\s+(on|off)\s*\(',
    re.MULTILINE,
)

# A recordError( call. Group 0 is the whole token; we exclude reportWrite* by
# checking the 20 chars before the match for a `reportWrite` receiver-less prefix.
_RECORD_ERROR_RE = re.compile(r'\brecordError\s*\(')


def _method_body_bounds(text, sig_match):
    """Given a `_POWER_METHOD_RE` match, confirm the param list closes then a `{`
    opens the body, and return (brace_open_index, body_end_index_exclusive), or
    None if this is not a real method body."""
    paren_open = text.find('(', sig_match.end() - 1)
    if paren_open == -1:
        return None
    depth = 0
    i = paren_open
    n = len(text)
    while i < n:
        c = text[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        i += 1
    if i >= n:
        return None
    j = i + 1
    while j < n and text[j] in ' \t\r\n':
        j += 1
    if j >= n or text[j] != '{':
        return None
    brace_open = j
    depth = 0
    k = brace_open
    while k < n:
        c = text[k]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return (brace_open, k + 1)
        k += 1
    return None


def _line_of(text, pos):
    return text[:pos].count('\n') + 1


def check_rule53_power_write_reporter(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """RULE53: FAIL a raw ``recordError(...)`` inside an on()/off() power method
    body. A power-write failure must route through ``reportWriteError`` (or
    ``reportWriteFailure`` when a ``resp`` is in scope) so it participates in the
    BP22 child-side network-outage dedup instead of spamming an ERROR + a
    diagnostics record per device per retry during an outage."""
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    text = "\n".join(cleaned_lines)

    for sig in _POWER_METHOD_RE.finditer(text):
        method_name = sig.group(1)
        bounds = _method_body_bounds(text, sig)
        if bounds is None:
            continue
        brace_open, body_end = bounds
        body = text[brace_open:body_end]

        for rm in _RECORD_ERROR_RE.finditer(body):
            # Exclude reportWriteError/reportWriteFailure — those are the fix and do
            # not contain a bare `recordError(` token at their call sites anyway; this
            # guard is belt-and-braces in case a helper name ever changes.
            preceding = body[max(0, rm.start() - 16):rm.start()]
            if 'reportWrite' in preceding:
                continue
            abs_pos = brace_open + rm.start()
            lineno = _line_of(text, abs_pos)
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE53_power_write_reporter',
                title=(
                    f'{method_name}() reports a power-write failure with a raw '
                    f'recordError(...) instead of reportWriteError(...)'
                ),
                file_rel=file_rel,
                lineno=lineno,
                raw_lines=raw_lines,
                why=(
                    f'A bare logError + recordError in {method_name}() bypasses the BP22 '
                    f'child-side outage dedup: during a known VeSync outage the parent already '
                    f'surfaces it once, and each child\'s write-fail branch must downgrade to a '
                    f'single DEBUG rather than logging an ERROR + a diagnostics record per device '
                    f'per retry. The already-correct sibling on()/off() methods use reportWriteError.'
                ),
                fix=(
                    f'Replace the logError(...) + recordError(tag, ctx) pair with '
                    f'reportWriteError(tag, ctx) (from LevoitChildBaseLib). Use '
                    f'reportWriteFailure(tag, resp, ctx) instead only if a device-off (BP29) '
                    f'rejection is possible for this write — it is not for a power on/off.'
                ),
            ))

    return findings


ALL_RULES = [check_rule53_power_write_reporter]
