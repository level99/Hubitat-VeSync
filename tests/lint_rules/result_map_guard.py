"""
result_map_guard.py — RULE51: flag a ``<resp>.data.result`` read in a child driver
command path that is NOT preceded/guarded by a ``<resp>.data instanceof Map`` check
in the same method.

Bug class (v2.10 cluster 1 — "result.code crash class"):

The VeSync gateway/CDN can return a non-JSON body (an HTML error page, a proxy
error) with an HTTP 200/2xx status. Hubitat's ``httpPost`` then leaves ``resp.data``
as a non-null **String**, not a Map. The ``?.`` safe-navigation operator guards
``null`` but NOT wrong-type: a bare ``resp?.data?.result?.code`` (or ``.result``,
``.result?.id``) does a property access on the String and throws
``groovy.lang.MissingPropertyException``, which aborts the calling command with a
raw sandbox stack trace instead of returning a clean failure.

The codebase already guards this in its canonical sibling helpers — in
``LevoitChildBaseLib.groovy`` ``hubBypass`` uses
``(resp?.data instanceof Map) ? resp.data.result?.code : null`` and
``isDeviceOffResp`` guards ``resp.data instanceof Map`` before every ``.result``
access. This rule makes that guard mandatory at every ``.data.result`` read site.

Authoritative predicate (the CLASS, not an example list):
    A ``<recv>[?.]data[?.]result`` read whose enclosing method body does NOT
    contain a ``<recv>[?.]data instanceof Map`` guard for the SAME receiver.

Detection scope:
  - ``.groovy`` files under ``Drivers/Levoit/``.
  - CHILD drivers + child-included libraries only. The parent app-driver
    ``VeSyncIntegration.groovy`` and the virtual test parent
    ``VeSyncIntegrationVirtual.groovy`` are the parent-side auth/discovery
    response subsystem (a separate reachability cluster) and are out of scope
    for this rule by design — they are excluded by name. Rationale (audited
    v2.10 cluster 2): every ``.data.result`` / ``.data.code`` read in the parent
    is already inside a try/catch that catches the MissingPropertyException a
    non-Map body would throw and degrades gracefully (logs + returns false,
    retried next cycle) rather than aborting a command — ``login`` stages and
    ``getDevices`` run inside ``retryableHttp``'s catch; the ``updateDevices``
    poll closure runs inside ``sendBypassRequest``'s own catch; ``isAuthFailure``
    has its own try/catch. Child command paths (this rule's scope) lack that
    surrounding catch, which is why the guard is mandatory there but not here.
  - Comment- and string-literal content is removed before scanning (a
    ``.data.result`` token inside a ``//`` comment or a log-string literal is
    not a code read).

Must-NOT-catch (safe by construction):
  - A read on the SAME line as, or in the same method as, a
    ``<recv>.data instanceof Map`` guard (the canonical fix shape).

Exemptions (rare — a read that is provably safe by context, e.g. wrapped in a
``try/catch`` whose whole purpose is to surface the non-Map body as diagnostic
output). Add ``result_map_guard_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: someMethod
      rationale: "why the unguarded read cannot abort a command here"
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Parent-side files: excluded by design (separate cluster — see module docstring).
PARENT_FILES = {
    "VeSyncIntegration.groovy",
    "VeSyncIntegrationVirtual.groovy",
}

# A method definition: one-or-more leading modifier/type keywords, then NAME(.
# Requires at least one leading keyword so a bare method CALL (e.g.
# ``parent.sendBypassRequest(...)``) is never mistaken for a definition.
# Group 1 = method name (the identifier immediately before the paren).
_METHOD_RE = re.compile(
    r'^[ \t]*(?:(?:private|public|protected|static|final|synchronized|'
    r'def|void|boolean|Boolean|Integer|int|long|Long|String|Map|List|'
    r'Object|BigDecimal|Number)\s+)+(\w+)\s*\(',
    re.MULTILINE,
)

# A `<recv>.data.result` read: receiver var, optional-chain or plain dot to
# `data`, then optional-chain or plain dot to `result`. Group 1 = receiver var.
_DATA_RESULT_RE = re.compile(
    r'\b(\w+)\s*\??\.\s*data\s*\??\.\s*result\b'
)


def _guard_re(recv: str) -> re.Pattern:
    """Regex matching a `<recv>.data instanceof Map` guard for THIS receiver."""
    return re.compile(
        r'\b' + re.escape(recv) + r'\s*\??\.\s*data\s+instanceof\s+Map\b'
    )


def _blank_string_literals(text: str) -> str:
    """Replace the CONTENT of Groovy string literals ('...', "...", '''...''',
    \"\"\"...\"\"\") with spaces, preserving quotes, length, and newlines so byte
    offsets still map 1:1 to line numbers. This prevents a ``.data.result`` token
    that appears inside a log string (e.g. ``"resp.data.result"``) from being read
    as a code access. ``clean_source`` blanks comments but NOT string literals, so
    this pass complements it."""
    out = list(text)
    n = len(text)
    i = 0
    while i < n:
        c = text[i]
        if c in ('"', "'"):
            # Triple-quoted?
            triple = text[i:i + 3] in ('"""', "'''")
            quote = c
            delim = quote * 3 if triple else quote
            dlen = len(delim)
            j = i + dlen
            while j < n:
                if text[j] == '\\':  # skip escaped char
                    j += 2
                    continue
                if text[j:j + dlen] == delim:
                    j += dlen
                    break
                j += 1
            # Blank the interior (keep delimiters and any newlines within).
            for k in range(i + dlen, min(j - dlen, n)):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
            continue
        i += 1
    return "".join(out)


def _cleaned_text(cleaned_lines) -> str:
    """Join the harness-cleaned lines (comments blanked by ``clean_source``) into
    a single text with the SAME line structure as the raw source, then blank the
    interior of string literals. Byte offsets map to line numbers 1:1 against
    raw_lines."""
    return _blank_string_literals("\n".join(cleaned_lines))


def _line_of(text: str, pos: int) -> int:
    return text[:pos].count('\n') + 1


def _method_body_bounds(text: str, sig_match) -> "tuple[int, int] | None":
    """Given a `_METHOD_RE` match, confirm it is a real method definition
    (param list closes, then a `{` opens the body) and return
    (body_open_brace_index, body_end_index_exclusive). Returns None if the
    match is actually a variable declaration / call, not a method body."""
    # Find the opening paren of the param list (at/after the captured name).
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
    # After the closing paren, the next non-whitespace char must be `{` for this
    # to be a method body (distinguishes `def foo(...) {` from `Integer x = bar(...)`).
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


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('result_map_guard_exemptions', [])
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


def check_rule51_result_map_guard(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """RULE51: FAIL an unguarded ``<recv>.data.result`` read in a child driver
    method. A non-JSON gateway error body makes ``resp.data`` a String; the read
    then throws MissingPropertyException and aborts the command. Guard with
    ``(resp?.data instanceof Map) ? ... : <default>`` (or an early
    ``if (!(resp.data instanceof Map)) return false``) — matching the canonical
    ``hubBypass`` / ``isDeviceOffResp`` helpers in LevoitChildBaseLib."""
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings
    if path.name in PARENT_FILES:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    text = _cleaned_text(cleaned_lines)

    for sig in _METHOD_RE.finditer(text):
        method_name = sig.group(1)
        bounds = _method_body_bounds(text, sig)
        if bounds is None:
            continue
        brace_open, body_end = bounds
        body = text[brace_open:body_end]

        if (file_rel, method_name) in exemption_set:
            continue

        # Receivers already guarded somewhere in this method body.
        flagged_recv = set()
        for rd in _DATA_RESULT_RE.finditer(body):
            recv = rd.group(1)
            if recv in flagged_recv:
                continue
            if _guard_re(recv).search(body):
                continue  # method has a `<recv>.data instanceof Map` guard
            flagged_recv.add(recv)
            abs_pos = brace_open + rd.start()
            lineno = _line_of(text, abs_pos)
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE51_result_map_guard',
                title=(
                    f'BP-crash-class: {method_name}() reads `{recv}.data.result` '
                    f'without a `{recv}.data instanceof Map` guard'
                ),
                file_rel=file_rel,
                lineno=lineno,
                raw_lines=raw_lines,
                why=(
                    f'A non-JSON gateway/CDN error body (HTML error page, proxy error) '
                    f'leaves `{recv}.data` a non-null String, not a Map. `?.` guards null '
                    f'but NOT wrong-type, so `{recv}?.data?.result...` does a property '
                    f'access on the String and throws MissingPropertyException — aborting '
                    f'the command with a raw sandbox stack trace instead of a clean failure.'
                ),
                fix=(
                    f'Guard the read with `({recv}?.data instanceof Map) ? '
                    f'{recv}.data.result... : <default>` (or an early '
                    f'`if (!({recv}.data instanceof Map)) return false`), matching the '
                    f'canonical hubBypass / isDeviceOffResp helpers in LevoitChildBaseLib.'
                ),
            ))

    return findings


ALL_RULES = [check_rule51_result_map_guard]
