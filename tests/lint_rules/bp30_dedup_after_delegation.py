"""
bp30_dedup_after_delegation.py — RULE49 (E1): a mode/speed setter must run its
``isDuplicateWrite(slot, value)`` check BEFORE any early-return branch that delegates to
ANOTHER setter. Otherwise the delegation runs before the slot is recorded, the slot keeps
the prior value, and a later identical write is falsely suppressed within the dedup window
(BP30 A1 — the exact bug that stranded a device in `manual` after auto->manual->auto).

This is the closed mechanism for the A1 class (E1). The pre-fix shape was:

    def setMode(mode) {
        ...validation...
        ensureSwitchOn()
        if (m == "manual") { setFanSpeed(...); return }   // delegation + return BEFORE dedup
        if (isDuplicateWrite("mode", m)) return            // <-- too late; slot never recorded "manual"
        ...
    }

Fix (what this rule enforces): the isDuplicateWrite call comes AHEAD of the delegation branch
(see Vital.setMode), so every path that changes the effective mode/speed records the new value.

Predicate (cleanly expressible — that is why it ships as a rule, not a TODO):
  Within a ``def``/``void`` ``set<Name>(...)`` method whose body CALLS isDuplicateWrite, flag if
  the body region BEFORE the isDuplicateWrite call contains a delegation to ANOTHER setter
  (``set<CapName>(``) followed by a ``return``. Validation guards (requireNonEmptyEnum/...->return,
  invalid-value->return) are preceded by requireX/logError, NOT a setX( call, so they do not
  trip the rule — that is the discriminator that keeps it low-false-positive.

Scope: ``.groovy`` under ``Drivers/Levoit/``. Matched against the COMMENT-STRIPPED source so a
comment describing the pre-fix shape does not trip it. Severity FAIL.
"""

import re

from lint_rules._helpers import make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# `def setX(...) {` or `void setX(...) {` — a setter method header opening a brace.
_SETTER_RE = re.compile(r'^[ \t]*(?:def|void)\s+(set[A-Za-z]\w*)\s*\([^)]*\)\s*\{', re.MULTILINE)
_ISDUP_RE = re.compile(r'\bisDuplicateWrite\s*\(')
# A delegation CALL to another setter: setMode( / setFanSpeed( / setSpeed( ... (capitalised
# second char so it is a real set<Word> setter, not e.g. `settings(`). Excludes hubBypass("setX")
# string args (no `(` immediately after the name there).
_DELEGATION_RE = re.compile(r'\bset[A-Z]\w*\s*\(')
_RETURN_RE = re.compile(r'\breturn\b')


def _blank_strings(text: str) -> str:
    """Replace string-literal CONTENTS with spaces (newlines preserved) so a method-name token
    inside a log string — e.g. ``logDebug "setMode(${mode})"`` — is NOT mistaken for a delegation
    call, and a ``{``/``}`` inside a GString does not corrupt brace matching. Length/line geometry
    is preserved so finding line numbers stay accurate."""
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


def check_rule49_dedup_after_delegation(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """RULE49 (BP30 A1): FAIL a set* method whose isDuplicateWrite is preceded by a
    delegation-and-return to another setter."""
    findings = []

    if path.suffix != '.groovy':
        return findings
    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    # Comment-stripped AND string-blanked: a method-name token inside a log string
    # (logDebug "setMode(...)") must not be read as a delegation call.
    text = _blank_strings('\n'.join(cleaned_lines))
    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for m in _SETTER_RE.finditer(text):
        name = m.group(1)
        brace = text.find('{', m.end() - 1)
        if brace == -1:
            continue
        end = _find_block_end(text, brace)
        if end == -1:
            continue
        body = text[brace:end]

        dup = _ISDUP_RE.search(body)
        if not dup:
            continue  # not a deduped setter — out of scope

        pre = body[:dup.start()]
        deleg = _DELEGATION_RE.search(pre)
        if deleg and _RETURN_RE.search(pre, deleg.end()):
            lineno = text[:brace + dup.start()].count('\n') + 1
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE49_dedup_after_delegation',
                title=(
                    f'{name}() delegates to another setter and returns BEFORE its '
                    f'isDuplicateWrite call (BP30 A1)'
                ),
                file_rel=file_rel,
                lineno=lineno,
                raw_lines=raw_lines,
                why=(
                    'An early-return delegation that runs before isDuplicateWrite leaves the '
                    'dedup slot stale at the prior value, so a later identical write is falsely '
                    'suppressed within the 2 s window (BP30 A1). The slot must record the NEW '
                    'effective value on every path that changes the mode/speed.'
                ),
                fix=(
                    'Move the isDuplicateWrite(slot, value) call AHEAD of the delegation branch '
                    '(see Vital.setMode for the reference shape), so the delegation path records '
                    'the new effective value and a later identical write fires.'
                ),
            ))

    return findings


ALL_RULES = [check_rule49_dedup_after_delegation]
