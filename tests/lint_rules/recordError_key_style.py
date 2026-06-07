r"""
recordError_key_style.py — RULE43: enforce the canonical recordError ctx-map key.

`recordError(message, ctxMap)` stores its ctx map verbatim as a diagnostic label
(ring-buffer + bug-report pre-fill).  The key name has zero functional effect, so
two spellings drifted into the codebase: `[method: "..."]` and `[site: "..."]`.
The fleet decision is `method:` everywhere.

This rule flags any `recordError(...)` call whose ctx map uses a `site:` key and
requires `method:` instead.

Scope (authoritative predicate, not an example list):
  File: any `.groovy` source file (driver, library, or parent-app source).
  Match: a `recordError(` call whose argument span contains a Groovy map key
    token `site:` (matched as `\bsite\s*:`).  The argument span runs from the
    `recordError(` token to its balanced closing paren and MAY span multiple
    source lines — auth-flow calls in the parent app wrap the ctx map onto a
    continuation line, so a single-line scan has a blind spot.  This rule scans
    the full source text with paren-balancing so multi-line calls are covered.
  Only the `site:` token INSIDE a recordError call's argument span is flagged.
    A bare `site:` elsewhere (a different map, a comment, a docstring) is not in
    scope — the `recordError(` co-occurrence within the balanced span is required.

Out of scope: the value spelling and any other ctx keys (e.g. `value:`) are not
  touched — only the `site` key rename is enforced.  Non-.groovy files are skipped.
"""

import re
from lint_rules._helpers import make_finding_for_path


# Locates the start of each recordError call.
_RECORD_ERROR_CALL_RE = re.compile(r'recordError\s*\(')

# A `site:` Groovy map key (whitespace-tolerant between `site` and `:`).
_SITE_KEY_RE = re.compile(r'\bsite\s*:')


def _record_error_spans(raw_text):
    """
    Yield (start_offset, end_offset) character spans for each recordError(...)
    call in raw_text, where end_offset is just past the balanced closing paren.

    Paren-balanced so multi-line calls (ctx map wrapped onto a continuation line)
    are captured in full.  An unbalanced/truncated call (no closing paren found)
    yields its span to end-of-text so a `site:` in a malformed tail is still seen.
    """
    for m in _RECORD_ERROR_CALL_RE.finditer(raw_text):
        # m.end() is the position just after the opening paren.
        depth = 1
        i = m.end()
        n = len(raw_text)
        while i < n and depth > 0:
            c = raw_text[i]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            i += 1
        yield (m.start(), i)


def _offset_to_lineno(raw_text, offset):
    """1-based line number for a character offset into raw_text."""
    return raw_text.count('\n', 0, offset) + 1


def check_rule43_recordError_key_style(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE43: flag `recordError(... site: ...)` and require `method:`.

    Fires only on `.groovy` files.  Scans each recordError call's balanced
    argument span (multi-line aware) for a `site:` ctx-map key.
    """
    findings = []

    if path.suffix.lower() != '.groovy':
        return findings

    for start, end in _record_error_spans(raw_text):
        span = raw_text[start:end]
        site_m = _SITE_KEY_RE.search(span)
        if not site_m:
            continue
        # Report at the line where the `site:` key actually appears (which may be
        # a continuation line below the recordError( token).
        lineno = _offset_to_lineno(raw_text, start + site_m.start())
        findings.append(make_finding_for_path(
            severity='FAIL',
            rule_id='RULE43_recordError_key_style',
            title="recordError ctx map uses 'site:' — canonical key is 'method:'",
            path=path,
            rel_base=rel_base,
            lineno=lineno,
            lines=raw_lines,
            why=(
                "recordError's ctx-map key is a diagnostic label with no functional "
                "effect, so the codebase must use one spelling. The fleet-wide canonical "
                "key is 'method:'. A 'site:' key is a drifted spelling."
            ),
            fix=(
                "Rename the 'site:' key to 'method:' in this recordError call, preserving "
                "any other keys/values in the map (e.g. [site:\"setMode\", value:x] -> "
                "[method:\"setMode\", value:x])."
            ),
        ))

    return findings


ALL_RULES = [check_rule43_recordError_key_style]
