"""
bp30_dedup_clear_on_failure.py — RULE50 (B1-completeness closed mechanism): a method that calls
``isDuplicateWrite(slot)`` MUST also clear that dedup record on the write-failure path
(``clearDuplicateWrite(...)`` — directly, or via the A1-delegation pattern that clears the outer
slot when a delegated setter returns false).

WHY: ``isDuplicateWrite`` records the slot on-proceed, so a SUCCESSFUL identical write coalesces
within the 2 s window. But if the write then FAILS and the slot is NOT cleared, an identical retry
is falsely suppressed for up to 2 s (the B1 gap). The B1 class-wide fix patched the failure
branches but shipped NO enforcing rule — which is exactly why ``setNightLight`` (1 of 31
isDuplicateWrite sites) slipped through with no failure-path clear. This rule is that mechanism.

Predicate (mechanical, low-false-positive): a method body that contains ``isDuplicateWrite(`` MUST
also contain ``clearDuplicateWrite(``. A method with the former and not the latter records a dedup
slot it can never clear on failure → FAIL. (Method-level co-presence is the proxy: in this codebase
``clearDuplicateWrite`` only ever appears on a failure/delegation-failure branch, so co-presence is
equivalent to "the failure path clears the slot" without a fragile branch-flow analysis.)

The source is COMMENT-stripped AND string-blanked (the RULE49 lesson) so a log-string literal such
as ``logDebug "isDuplicateWrite ..."`` cannot trip or mask the rule.

Exemption: ``bp30_dedup_clear_on_failure_exemptions`` in lint_config.yaml — a list of
``{file, method, rationale}`` for any legitimate no-clear case. None currently: every deduped setter
(including Core.setSpeed, whose auto/sleep/manual/recover branches all clear "speed" on a delegated
or handleSpeed failure) carries its clear.

Scope: ``.groovy`` under ``Drivers/Levoit/``. Severity FAIL.
"""

import re

from lint_rules._helpers import make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Method header: optional modifiers, a return-type token (def/void/common types or a capitalised
# custom type), the name, params, and an opening brace on the same line.
_METHOD_RE = re.compile(
    r'^[ \t]*(?:(?:private|public|protected|static|final)\s+)*'
    r'(?:def|void|boolean|Boolean|Integer|int|String|Long|BigDecimal|Object|Map|List|[A-Z]\w*)\s+'
    r'(\w+)\s*\([^)]*\)\s*\{',
    re.MULTILINE,
)
_ISDUP_RE = re.compile(r'\bisDuplicateWrite\s*\(')
_CLEARDUP_RE = re.compile(r'\bclearDuplicateWrite\s*\(')


def _blank_strings(text: str) -> str:
    """Replace string-literal contents with spaces (length/line geometry preserved) so a method
    name or helper token inside a log string is not read as code (RULE49 lesson)."""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        c = text[i]
        if quote:
            if c == '\\' and i + 1 < n:
                out.append('  '); i += 2; continue
            if c == quote:
                quote = None; out.append(c); i += 1; continue
            out.append('\n' if c == '\n' else ' '); i += 1; continue
        if c in ('"', "'"):
            quote = c; out.append(c); i += 1; continue
        out.append(c); i += 1
    return ''.join(out)


def _find_block_end(source: str, brace_start: int) -> int:
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


def _exemption_set(config: dict) -> set:
    out = set()
    for e in config.get('bp30_dedup_clear_on_failure_exemptions', []) or []:
        if isinstance(e, dict):
            f = str(e.get('file', '')).replace('\\', '/').strip()
            mth = str(e.get('method', '')).strip()
            if f and mth:
                out.add((f, mth))
    return out


def check_rule50_dedup_clear_on_failure(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """RULE50 (B1-completeness): FAIL a method that records a dedup slot via isDuplicateWrite but
    never clears it (clearDuplicateWrite) — so a failed write would suppress an identical retry."""
    findings = []

    if path.suffix != '.groovy':
        return findings
    if DRIVER_DIR_FRAGMENT not in str(path).replace('\\', '/'):
        return findings

    text = _blank_strings('\n'.join(cleaned_lines))
    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exempt = _exemption_set(config)

    for m in _METHOD_RE.finditer(text):
        name = m.group(1)
        brace = text.find('{', m.end() - 1)
        if brace == -1:
            continue
        end = _find_block_end(text, brace)
        if end == -1:
            continue
        body = text[brace:end]

        if _ISDUP_RE.search(body) and not _CLEARDUP_RE.search(body):
            if (file_rel, name) in exempt:
                continue
            lineno = text[:m.start()].count('\n') + 1
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE50_dedup_clear_on_failure',
                title=(
                    f'{name}() calls isDuplicateWrite but never clearDuplicateWrite — a failed '
                    f'write would suppress an identical retry for the dedup window (B1 gap)'
                ),
                file_rel=file_rel,
                lineno=lineno,
                raw_lines=raw_lines,
                why=(
                    'isDuplicateWrite records the dedup slot on-proceed so identical SUCCESSFUL '
                    'writes coalesce within the 2 s window. If the write then FAILS and the slot is '
                    'not cleared, an identical retry is falsely suppressed for up to 2 s. This was '
                    'the nightlight gap — 1 of 31 sites with no failure-path clear.'
                ),
                fix=(
                    'On the write-FAILURE branch, call clearDuplicateWrite("<slot>") (or, for a '
                    'delegation early-return, observe the delegated call and clear the OUTER slot on '
                    'failure). If a no-clear is genuinely correct, add a '
                    'bp30_dedup_clear_on_failure_exemptions entry with a rationale.'
                ),
            ))

    return findings


ALL_RULES = [check_rule50_dedup_clear_on_failure]
