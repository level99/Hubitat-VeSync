"""
bp6_speed_level_power_gate.py — RULE44: flag an ACTIVE-level attribute emit inside a
status-parse body (or status-parse HELPER) that is not power-gated.

Bug Pattern #6 — a device reports its last-set level/speed while powered OFF; emitting
that value verbatim produces a "switch=off, Mist: L5" / "switch=off, speed=high"
contradiction on dashboards. The active level must read 0/off while the device is off.

The active-level attributes (the ones that describe what the device is CURRENTLY doing)
are a fixed allowlist:

    mistLevel, warmMistLevel, warmMistEnabled, speed, fanSpeed

These MUST be power-gated in the status-parse path. The canonical mechanism is the
shared helper ``clampOffLevel(value, powerOn)`` from LevoitChildBaseLib (returns 0 when
off); a ``!powerOn`` / ``!enabled`` guard branch around the emit is also accepted.

NOT in the allowlist (deliberately): ``virtualLevel`` and ``level`` are SETPOINT
attributes (the dimmer/SwitchLevel target the device will run at next). They intentionally
retain their value while off, so they are never flagged — this is the active-vs-setpoint
distinction that keeps the rule low-false-positive WITHOUT full dataflow.

Scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - Scanned methods (the status-parse path where reported state -> events):
      * ``applyStatus(status…)`` / ``update(status…)`` entry points, AND
      * status-parse HELPER methods — any method (any name) whose body EMITS an
        active-level attr. This is what catches the Fan family: Tower/Pedestal Fan emit
        ``speed`` from the shared ``applyFanCommonHead()`` helper in LevoitFanLib, never
        from a method literally named applyStatus/update. A helper that emits an active
        attr is on the status-parse path by definition and is gated the same way.
        (Tradeoff of the positive ``apply*`` allowlist: a future WRITE-PATH method that
        happens to use the ``apply*`` name AND emits an active-level attr would also be
        scanned and could false-positive; if that ever arises, add it to
        ``bp6_speed_level_power_gate_exemptions`` in ``lint_config.yaml``.)
    Write-path command setters (``set*`` / ``cycle*`` / ``on`` / ``off`` / ``toggle`` /
    ``handle*``) are EXCLUDED — their emit reflects a command just issued, not a poll of a
    possibly-off device. They are excluded because they do not satisfy the positive
    status-parse allowlist (``_is_status_parse_method``: ``applyStatus`` / ``update(status…)``
    / ``apply*`` helpers).

Emit detection is a PAREN-BALANCED scanner (not a line-anchored regex): it handles emits
that span multiple lines and the named-arg order ``sendEvent(value: x, name:"speed")`` as
well as the usual ``name`` before ``value``. Comments and ALL Groovy string flavours
(single/double, triple-quoted, slashy ``/…/``, dollar-slashy ``$/…/$``) are stripped by a
string-aware state machine before scanning, so a ``//`` / ``/*`` inside a string is data and
a gating token inside a real comment is removed.

Detection — PER-EMIT (not method-level). For each active-level emit in a scanned method,
the emit must be power-gated. It is considered gated when ANY of:
  (a) the emitted value-expression itself contains ``clampOffLevel(``; OR
  (b) the value-expression references a GATED LOCAL — a local variable whose nearest
      PRECEDING assignment (position-aware; multi-line RHS supported) contains a gating
      token, transitively (so ``warmOnStr = warmOn ? …`` where ``warmOn = powerOn && …``
      bottoming out at ``clampOffLevel`` / ``powerOn &&`` is recognised); OR
  (c) the emit is lexically inside a power-gated branch, POLARITY-AWARE:
      - NEGATIVE ``if (!powerOn)`` / ``if (!enabled)`` / ``if (!status.result.enabled)``:
        the whole if / else-if / else chain is gated (THEN is the off-branch; ELSE/else-if
        are on-branches — both safe). The Core per-mode speed shape + FanLib
        ``if (!powerOn) … else …`` helper.
      - POSITIVE ``if (powerOn)`` / ``if (enabled)``: ONLY the THEN block is gated; an active
        emit in the ELSE (the off-branch) is the BP6 bug and is flagged.

  A method-level "any gating token anywhere" check is NOT used: it green-lit a body that
  clamps mistLevel but emits a SECOND active attr (warmMistEnabled) ungated in the
  warm-fallback branch. Per-emit closes that hole.

  Residual gap (documented, accepted): the gated-local analysis is intra-method and
  token/assignment based, not a full SSA dataflow. A local reassigned both gated and
  ungated on different paths is treated as gated (the first gated assignment "sticks").
  In this codebase active-level locals are assigned once; the simplification is safe here.
  If a future driver reassigns an active-level local ungated AFTER a gated assignment and
  emits the ungated value, RULE44 would miss it — covered instead by the per-driver
  from-off Spock guard (BP6 regression test) which exercises the real off-state value.

Exemptions:
  Add ``bp6_speed_level_power_gate_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: applyStatus
      rationale: "reason this body legitimately emits an active level ungated"
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Active-level attribute names — the device's CURRENT activity; must read 0/off when off.
# virtualLevel / level are SETPOINTs and are intentionally absent.
ACTIVE_ATTRS = ('mistLevel', 'warmMistLevel', 'warmMistEnabled', 'speed', 'fanSpeed')
_ATTR_ALT = '|'.join(ACTIVE_ATTRS)

# Active-emit detection is done by a PAREN-BALANCED scanner (_iter_active_emits), NOT a
# line-anchored regex — FIX #2 (multi-line emits where name:/value: span newlines) and
# FIX #3 (named-arg order: `sendEvent(value: x, name:"speed")` as well as the usual
# name-before-value). The scanner finds each sendEvent(/handleEvent( call, captures the
# brace/paren-balanced argument text across newlines, and extracts the attr name + value.
_EMIT_CALL_RE = re.compile(r'\b(?P<kind>sendEvent|handleEvent)\s*\(')
# Within a sendEvent arg list: `name : "attr"` (any order) and `value : <expr>`.
_NAME_KW_RE = re.compile(r'\bname\s*:\s*["\'](?P<attr>\w+)["\']')
_VALUE_KW_RE = re.compile(r'\bvalue\s*:\s*(?P<val>.*)\Z', re.DOTALL)
_ATTR_SET = set(ACTIVE_ATTRS)

# Method header: `[modifiers] <return-type> <name>(<params>)`. We scan EVERY method header,
# then decide scope by name (_is_status_parse_method) and whether it emits an active attr.
# FIX #1 — the return type is ANY valid Groovy type token (`def`, `void`, or a class name
# `[A-Za-z_]\w*` with optional generics/array suffix), not a fixed allowlist that silently
# skipped Boolean/Long/Float/BigDecimal/custom return types. The `(?P<name>...)` + `(` lookahead
# keeps this anchored to a real method header (not a field decl or a call). A bare keyword like
# `return`/`new` as the "type" is excluded so a statement isn't mis-read as a method header.
_NON_TYPE_LEADERS = (
    'return', 'new', 'if', 'else', 'for', 'while', 'switch', 'case', 'try', 'catch',
    'assert', 'throw', 'import', 'package',
)
METHOD_HEADER_RE = re.compile(
    r'^[ \t]*(?:(?:private|public|protected|static|final|synchronized|abstract)\s+)*'
    r'(?P<rettype>def|void|[A-Za-z_]\w*(?:\s*<[^>{]*>)?(?:\s*\[\s*\])?)\s+'
    r'(?P<name>\w+)\s*\(\s*(?P<params>[^)]*)\)',
    re.MULTILINE,
)

# IN-SCOPE positive allowlist (avoids a fragile write-path blocklist that missed
# configureOnState/sendLevel). A method is on the status-parse path iff:
#   - it is named exactly `applyStatus`, OR
#   - it is named exactly `update` AND its first param is a status Map (the entry point;
#     the zero-arg `update()` self-fetch and unrelated updateX methods are excluded), OR
#   - its name STARTS WITH `apply` (the helper convention: applyFanCommonHead,
#     applyFanMuteDisplay, … — every status-parse helper in this codebase is `apply*`).
# Everything else (set*/send*/handle*/on/off/configureOnState/…) is a write-path method
# whose active-attr emit reflects a command just issued, not a poll — out of scope.
UPDATE_STATUS_PARAM_RE = re.compile(r'^\s*(?:def\s+)?status\b')


def _is_status_parse_method(name: str, params: str) -> bool:
    if name == 'applyStatus':
        return True
    if name == 'update' and UPDATE_STATUS_PARAM_RE.match(params or ''):
        return True
    if name.startswith('apply'):
        return True
    return False

# Gating tokens. clampOffLevel is the canonical helper; powerOn&& / !powerOn / !enabled
# cover the warm-fallback and the per-mode speed-off branches.
GATING_TOKEN_RE = re.compile(
    r'clampOffLevel\s*\('
    r'|powerOn\s*&&'
    r'|!\s*powerOn'
    r'|!\s*enabled\b'
    r'|!\s*status\s*\??\.\s*result\s*\??\.\s*enabled'
)

# Power-gated `if (...)` openers, split by POLARITY (FIX #6/#7):
#
#   NEGATIVE — `if (!powerOn)` / `if (!enabled)` / `if (!status.result.enabled)`:
#     the THEN-branch is the device-OFF branch (emits off-values — safe) and the
#     ELSE / else-if branches are the device-ON branches (emit live values when on — safe).
#     => the ENTIRE if / else-if / else chain is gated.
#
#   POSITIVE — `if (powerOn)` / `if (enabled)`:
#     the THEN-branch is the device-ON branch (safe), but the ELSE branch is the device-OFF
#     branch — an active emit there emits a live/stale level WHILE OFF = the BP6 bug.
#     => ONLY the THEN-branch is gated; the else/else-if branches are NOT.
GATING_IF_NEG_RE = re.compile(
    r'\bif\s*\(\s*(?:'
    r'!\s*powerOn\b'
    r'|!\s*enabled\b'
    r'|!\s*status\s*\??\.\s*result\s*\??\.\s*enabled'
    r')'
)
GATING_IF_POS_RE = re.compile(
    r'\bif\s*\(\s*(?:powerOn|enabled)\s*\)'
)

_IDENT_RE = re.compile(r'\b[a-zA-Z_]\w*\b')


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


def _line_of(source: str, pos: int) -> int:
    return source[:pos].count('\n') + 1


# FIX #5 — slashy-vs-division disambiguation. A `/` begins a slashy STRING literal only
# where a regex/string is grammatically expected — i.e. NOT immediately after a value-
# producing token (identifier, number, `)`, `]`, `}`, or a closing quote). After an
# operator/opener/keyword (`(`, `,`, `=`, `[`, `:`, `&`, `|`, `!`, `~`, `?`, `{`, `;`,
# `return`, `=~`, `==~`, `<`, `>`, `+`, `-`, `*`, `%`, or start-of-input) a `/` is a slashy
# string opener. This is the standard pragmatic heuristic (a full Groovy parse is overkill);
# the one real-corpus slashy is `tn =~ /v?(\d+...)/` which this classifies correctly.
_SLASHY_PRECEDER_RE = re.compile(r'[(\[{,:;=&|!~?<>+\-*%]\s*$|(?:^|\W)(?:return|new|and|or|not)\s*$')


def _slashy_context(prev_significant: str) -> bool:
    """True if a `/` at this point opens a slashy string (regex literal) rather than being
    a division operator, judged from the source text emitted SO FAR (`prev_significant`)."""
    s = prev_significant.rstrip()
    if not s:
        return True  # start of statement/input -> regex context
    return bool(_SLASHY_PRECEDER_RE.search(s))


def _strip_comments(text: str) -> str:
    """
    Remove `/* */` block comments and `//` line comments in a STRING-LITERAL-AWARE way,
    replacing comment bytes with space (newlines preserved) so line/column geometry is
    unchanged. String-literal CONTENTS are PRESERVED — the emit regex needs the quoted
    attribute name (sendEvent(name:"mistLevel", …)), so blanking strings would erase it.

    FIX #5/#10 — a proper small state machine over the comment/string alternation, handling
    every Groovy string flavour so a `//` or `/*` INSIDE a string is never mis-stripped, and
    a real comment is always stripped:
      - `'…'` / `"…"`  single-line single/double quoted (backslash escapes honoured)
      - `'''…'''` / `\"\"\"…\"\"\"`  triple-quoted (multi-line; escapes honoured)
      - `/…/`  slashy string (regex literal) — only when grammatically a regex context
        (see _slashy_context); a `/` after a value token is treated as division, NOT a
        string. NO backslash de-special-casing inside slashy except an escaped delimiter
        `\\/` (Groovy slashy strings treat `\\` literally except before `/`).
      - `$/…/$`  dollar-slashy string (multi-line) — closes on `/$`.
    Outcomes that matter for RULE44:
      - a gating token (clampOffLevel/powerOn&&/!powerOn/!enabled) hidden in a real comment
        is removed -> cannot falsely gate (no false negative), and
      - a `//` / `/*` inside ANY string flavour is data -> does not truncate the line and
        hide a real gating token (no false positive).
    """
    out = []
    i = 0
    n = len(text)
    # states: 'code', 'line', 'block', 'sq', 'dq', 'tsq' (''' ), 'tdq' (\"\"\" ),
    #         'slashy' (/.../), 'dslashy' ($/.../$)
    state = 'code'
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        nxt2 = text[i + 2] if i + 2 < n else ''

        if state == 'line':
            if c == '\n':
                state = 'code'
                out.append(c)
            else:
                out.append(' ')
            i += 1
            continue
        if state == 'block':
            if c == '*' and nxt == '/':
                out.append('  ')
                i += 2
                state = 'code'
            else:
                out.append('\n' if c == '\n' else ' ')
                i += 1
            continue
        if state in ('sq', 'dq'):
            if c == '\\' and nxt:
                out.append(c); out.append(nxt); i += 2; continue
            out.append(c)
            if (c == "'" and state == 'sq') or (c == '"' and state == 'dq'):
                state = 'code'
            i += 1
            continue
        if state in ('tsq', 'tdq'):
            q = "'" if state == 'tsq' else '"'
            if c == '\\' and nxt:
                out.append(c); out.append(nxt); i += 2; continue
            if c == q and nxt == q and nxt2 == q:
                out.append(c); out.append(nxt); out.append(nxt2)
                i += 3
                state = 'code'
                continue
            out.append(c); i += 1; continue
        if state == 'slashy':
            # closes on an unescaped `/`. `\/` is a literal slash within the string.
            if c == '\\' and nxt == '/':
                out.append(c); out.append(nxt); i += 2; continue
            out.append(c)
            if c == '/':
                state = 'code'
            i += 1
            continue
        if state == 'dslashy':
            # multi-line; closes on `/$`.
            if c == '/' and nxt == '$':
                out.append(c); out.append(nxt); i += 2; state = 'code'; continue
            out.append('\n' if c == '\n' else c)
            i += 1
            continue

        # state == 'code'
        if c == '/' and nxt == '/':
            state = 'line'; out.append('  '); i += 2; continue
        if c == '/' and nxt == '*':
            state = 'block'; out.append('  '); i += 2; continue
        if c == '$' and nxt == '/':
            state = 'dslashy'; out.append(c); out.append(nxt); i += 2; continue
        if c == "'" and nxt == "'" and nxt2 == "'":
            state = 'tsq'; out.append(c); out.append(nxt); out.append(nxt2); i += 3; continue
        if c == '"' and nxt == '"' and nxt2 == '"':
            state = 'tdq'; out.append(c); out.append(nxt); out.append(nxt2); i += 3; continue
        if c == "'":
            state = 'sq'; out.append(c); i += 1; continue
        if c == '"':
            state = 'dq'; out.append(c); i += 1; continue
        if c == '/':
            # slashy string opener ONLY in a regex context; otherwise division — left as code.
            # Pass only the recent tail (O(1) per `/`, avoids O(n^2) full-prefix rebuild);
            # the slashy-context heuristic only inspects the last significant token.
            if _slashy_context(''.join(out[-48:])):
                state = 'slashy'; out.append(c); i += 1; continue
            out.append(c); i += 1; continue
        out.append(c)
        i += 1
    return ''.join(out)


def _balanced_args(text: str, open_paren_idx: int):
    """
    Given the index of a `(` in `text`, return (args_str, close_idx) where args_str is the
    paren-balanced argument text (NESTED parens preserved, spans newlines) and close_idx is
    the index of the matching `)`. Returns (None, -1) if unbalanced. String literals were
    already neutralised of comment delimiters by _strip_comments but still contain their own
    parens; to avoid a `)` inside a string closing the call early, we skip string spans here.
    """
    depth = 0
    i = open_paren_idx
    n = len(text)
    start = open_paren_idx + 1
    quote = ''
    while i < n:
        c = text[i]
        if quote:
            if c == '\\' and i + 1 < n:
                i += 2; continue
            if c == quote:
                quote = ''
            i += 1
            continue
        if c in ('"', "'"):
            quote = c; i += 1; continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[start:i], i
        i += 1
    return None, -1


def _iter_active_emits(body_clean: str):
    """
    Yield (call_start_offset, attr, value_expr) for every sendEvent/handleEvent call in
    `body_clean` that emits an ACTIVE-level attr. Paren-balanced + multi-line + arg-order
    agnostic (FIX #2/#3).

    - sendEvent: looks for `name:"X"` and `value: <expr>` keywords in the (balanced) args, in
      EITHER order. The value_expr is the text after `value:` up to the end of the args.
    - handleEvent("X", <expr>): positional — first arg is the attr string, second is the value.
    """
    for m in _EMIT_CALL_RE.finditer(body_clean):
        kind = m.group('kind')
        open_idx = m.end() - 1  # the `(`
        args, close_idx = _balanced_args(body_clean, open_idx)
        if args is None:
            continue
        if kind == 'sendEvent':
            nm = _NAME_KW_RE.search(args)
            if not nm:
                continue
            attr = nm.group('attr')
            if attr not in _ATTR_SET:
                continue
            vm = _VALUE_KW_RE.search(args)
            val = vm.group('val').strip() if vm else ''
            yield (m.start(), attr, val)
        else:  # handleEvent positional: ("attr", value...)
            am = re.match(r'\s*["\'](?P<attr>\w+)["\']\s*,\s*(?P<val>.*)\Z', args, re.DOTALL)
            if not am:
                continue
            attr = am.group('attr')
            if attr not in _ATTR_SET:
                continue
            yield (m.start(), attr, am.group('val').strip())


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('bp6_speed_level_power_gate_exemptions', [])
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


# FIX #12 + #9: cache the exemption set keyed on the CONTENT of the relevant config (not
# id(config), which is unsafe — a GC'd config dict's address can be reused by a different
# dict, returning a stale set). The key is a stable, deterministic serialization of the
# bp6 exemption entries; the cache is a pure-function memo (same content -> same set), so it
# cannot change behaviour vs calling _build_exemption_set every time.
_EXEMPTION_CACHE = {}


def _exemption_config_key(config: dict):
    entries = config.get('bp6_speed_level_power_gate_exemptions', [])
    if not isinstance(entries, list):
        return ()
    key_parts = []
    for entry in entries:
        if isinstance(entry, dict):
            key_parts.append((
                str(entry.get('file', '')),
                str(entry.get('method', '')),
                str(entry.get('rationale', '')),
            ))
        else:
            key_parts.append(('<non-dict>', repr(entry), ''))
    return tuple(key_parts)


def _exemption_set_cached(config: dict) -> set:
    key = _exemption_config_key(config)
    cached = _EXEMPTION_CACHE.get(key)
    if cached is None:
        cached = _build_exemption_set(config)
        _EXEMPTION_CACHE[key] = cached
    return cached


# An assignment ANYWHERE (not just line-start): preceded by start-of-body / `;` / `{` / `}`
# / newline (so it is a statement boundary, not a `==`/`<=` comparison or a named arg), an
# optional type token, the var name, a single `=` (NOT `==`/`<=`/`>=`/`!=`/`+=` etc.), then
# the RHS. The var-name START offset is captured for FIX #8 (position-accurate ordering).
_ASSIGN_SCAN_RE = re.compile(
    r'(?:^|[;{}\n])\s*'
    r'(?:(?:Integer|int|long|Long|float|Float|double|Double|def|String|boolean|Boolean'
    r'|BigDecimal|Object|Number|Map|List)\s+)?'
    r'(?P<var>[a-zA-Z_]\w*)\s*'
    r'(?<![=!<>+\-*/%&|^])=(?![=~])',
    re.MULTILINE,
)


def _collect_assignments(body_clean: str):
    """
    Return a list of (var_offset, var, rhs) for every local assignment in the body, in source
    order. FIX #8: var_offset is the character offset of the VAR NAME (not the line start) —
    so an emit followed on the SAME line by `…; mistVirtual = clampOffLevel(…)` correctly has
    its assignment offset AFTER the emit, not before. FIX #4: the RHS is accumulated across
    CONTINUATION lines until the expression's parens/brackets balance (so a multi-line
    `Integer warmLvl =\\n    clampOffLevel(…)` captures the full clampOffLevel RHS, not '').
    """
    assigns = []
    n = len(body_clean)
    for m in _ASSIGN_SCAN_RE.finditer(body_clean):
        var = m.group('var')
        var_off = m.start('var')
        # RHS starts just after the `=`. Accumulate until paren/bracket depth returns to 0
        # AND we hit a statement terminator (newline at depth 0, `;`, or `}`), so a
        # multi-line balanced RHS is fully captured.
        j = m.end()
        depth = 0
        quote = ''
        rhs_chars = []
        while j < n:
            ch = body_clean[j]
            if quote:
                rhs_chars.append(ch)
                if ch == '\\' and j + 1 < n:
                    rhs_chars.append(body_clean[j + 1]); j += 2; continue
                if ch == quote:
                    quote = ''
                j += 1
                continue
            if ch in ('"', "'"):
                quote = ch; rhs_chars.append(ch); j += 1; continue
            if ch in '([{':
                depth += 1; rhs_chars.append(ch); j += 1; continue
            if ch in ')]}':
                if depth == 0:
                    break  # closing delimiter of an enclosing block — RHS ended
                depth -= 1; rhs_chars.append(ch); j += 1; continue
            if depth == 0 and ch == ';':
                break  # explicit statement terminator at top level
            if depth == 0 and ch == '\n':
                # FIX #4: a newline ends the RHS ONLY if the statement is complete — i.e. some
                # RHS content already seen AND it does not end on a continuation operator. A
                # `warmLvl =\n   clampOffLevel(...)` has an empty/operator-trailing RHS so far,
                # so the newline is a CONTINUATION, not a terminator.
                collected = ''.join(rhs_chars).rstrip()
                if collected and collected[-1] not in '=+-*/%&|^?:.,([{<>':
                    break
                rhs_chars.append(' '); j += 1; continue
            rhs_chars.append(ch); j += 1
        rhs = ''.join(rhs_chars).strip()
        assigns.append((var_off, var, rhs))
    return assigns


def _local_gated_before(var: str, before_offset: int, assigns, _seen=None) -> bool:
    """
    POSITION-AWARE gated check: is local `var`, as seen by an emit at `before_offset`, bound
    to a power-gated value? Resolves to the NEAREST PRECEDING assignment of `var` (the one
    that is actually in effect at the emit), then:
      - gated if that RHS contains a gating token (clampOffLevel / powerOn&& / !powerOn / !enabled); OR
      - gated if that RHS references another local that is itself gated AT THAT ASSIGNMENT's
        position (transitive, multi-hop: warmOnStr -> warmOn -> warmLvl -> clampOffLevel).

    Using the nearest-PRECEDING assignment (not a method-wide union) is what makes the
    warm-fallback both-ways work: the `else if (r.warm_enabled)` branch's
    `warmOn = asBool(...)` (ungated) is the binding in effect for the fallback emit, even
    though a SIBLING `warm_level` branch also assigns `warmOn = (warmLvl > 0)` (gated).
    A method-wide set would conflate the two and mask the fallback regression.
    """
    if _seen is None:
        _seen = set()
    if var in _seen:
        return False  # cycle guard
    _seen = _seen | {var}

    # Nearest preceding assignment of `var`.
    cand = None
    for (off, v, rhs) in assigns:
        if v == var and off < before_offset:
            cand = (off, rhs)  # keep the last (nearest) one before the emit
    if cand is None:
        return False
    off, rhs = cand
    if GATING_TOKEN_RE.search(rhs):
        return True
    for ident in set(_IDENT_RE.findall(rhs)):
        if ident == var:
            continue
        if _local_gated_before(ident, off, assigns, _seen):
            return True
    return False


def _then_block_span(body_clean: str, opener_end: int):
    """Return (brace_open, block_end) for the `{ … }` block immediately following an if/else
    opener that ends at `opener_end`, or (None, None) if not brace-delimited."""
    brace = body_clean.find('{', opener_end)
    if brace == -1:
        return None, None
    # Reject if a statement clearly separates the opener from this brace (e.g. another `{`
    # would not — but a `;`/emit before the brace means it's not this if's block). For the
    # shapes here the brace immediately follows; keep it simple.
    end = _find_block_end(body_clean, brace)
    if end == -1:
        return None, None
    return brace, end


def _chain_blocks(body_clean: str, first_brace: int, first_end: int):
    """
    Walk the full if / else-if / else CHAIN starting at the then-block (first_brace,
    first_end). Yields (kind, brace_open, block_end) for each block in the chain:
      - the leading 'then' block, then any number of 'elseif' / 'else' blocks.
    FIX #6: follows EVERY consecutive else/else-if, not just the first.
    """
    blocks = [('then', first_brace, first_end)]
    end = first_end
    n = len(body_clean)
    while True:
        m = re.match(r'\s*else\b\s*(if\s*\([^{]*\))?\s*', body_clean[end:])
        if not m:
            break
        is_elseif = bool(m.group(1))
        seg_end = end + m.end()
        brace = body_clean.find('{', end + m.start() + len('else'))
        # Ensure the brace we found belongs to this else (it should be the next `{`).
        if brace == -1 or brace > seg_end + 2:
            # `else <single-statement>;` without braces — not handled (no driver shape uses it
            # for active emits); stop the chain walk.
            break
        be = _find_block_end(body_clean, brace)
        if be == -1:
            break
        blocks.append(('elseif' if is_elseif else 'else', brace, be))
        end = be
    return blocks


def _enclosing_gated_if(body_clean: str, emit_offset: int) -> bool:
    """
    True if the emit at `emit_offset` is lexically inside a power-gated branch.

    POLARITY-AWARE (FIX #7) + full-chain (FIX #6):
      - NEGATIVE `if (!powerOn)` / `if (!enabled)` / `if (!status.result.enabled)`:
        THEN is the off-branch, ELSE/else-if are on-branches — ALL gated. The whole chain
        gates the emit.
      - POSITIVE `if (powerOn)` / `if (enabled)`:
        THEN is the on-branch (gated); the ELSE/else-if branches are off-branches — an active
        emit there is the BP6 bug and is NOT gated. Only the THEN block gates.
    """
    # Negative-polarity openers: emit gated if inside ANY block of the chain.
    for gm in GATING_IF_NEG_RE.finditer(body_clean):
        if gm.start() > emit_offset:
            break
        brace, end = _then_block_span(body_clean, gm.end())
        if brace is None:
            continue
        for (_kind, b, e) in _chain_blocks(body_clean, brace, end):
            if b < emit_offset < e:
                return True

    # Positive-polarity openers: emit gated ONLY if inside the THEN block (not else/else-if).
    for gm in GATING_IF_POS_RE.finditer(body_clean):
        if gm.start() > emit_offset:
            break
        brace, end = _then_block_span(body_clean, gm.end())
        if brace is None:
            continue
        if brace < emit_offset < end:
            return True
        # else/else-if branches of a positive if are NOT gated -> fall through (no return).

    return False


def check_rule44_bp6_power_gate(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE44 (Bug Pattern #6): per-emit, flag an active-level attribute emit (mistLevel /
    warmMistLevel / warmMistEnabled / speed / fanSpeed) in a status-parse method/helper
    that is not power-gated (no clampOffLevel on the value, no gated-local reference, not
    inside a power-gated if-block). Setpoint attrs (virtualLevel / level) are out of scope.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _exemption_set_cached(config)

    text_clean = _strip_comments(raw_text)

    for hm in METHOD_HEADER_RE.finditer(text_clean):
        method_name = hm.group('name')

        # FIX #1 guard: with the widened return-type pattern, a STATEMENT like `return foo(x)`
        # could spuriously match (rettype="return", name="foo"). Reject when the "type" token is
        # a control/keyword leader — those are never a method return type.
        if hm.group('rettype') in _NON_TYPE_LEADERS:
            continue

        # Scope: only status-parse entry points (applyStatus / update(status…)) and
        # status-parse helpers (apply*). Write-path methods (set*/send*/handle*/on/off/
        # configureOnState/…) emit what they just commanded, not a poll — out of scope.
        if not _is_status_parse_method(method_name, hm.group('params')):
            continue

        brace_start = text_clean.find('{', hm.end() - 1)
        if brace_start == -1:
            continue
        body_end = _find_block_end(text_clean, brace_start)
        if body_end == -1:
            continue

        body_clean = text_clean[brace_start:body_end]

        # Only scan methods that EMIT an active-level attr (status-parse entry points AND
        # status-parse helpers like applyFanCommonHead). Paren-balanced + multi-line +
        # arg-order-agnostic (FIX #2/#3). A method with no active emit is neither an entry
        # point we care about nor a helper on this path.
        emits = list(_iter_active_emits(body_clean))
        if not emits:
            continue

        if (file_rel, method_name) in exemption_set:
            continue

        assigns = _collect_assignments(body_clean)

        for (emit_off, attr, val) in emits:
            val = val or ''

            # (a) value-expr itself clamps.
            if 'clampOffLevel(' in val:
                continue
            # (b) value-expr contains an inline gating token (e.g. a literal `powerOn &&`
            #     or `!powerOn` directly in the emit expression).
            if GATING_TOKEN_RE.search(val):
                continue
            # (c) value-expr references a local that is gated AS OF THIS EMIT's position —
            #     resolved via the NEAREST PRECEDING assignment (position-aware, so a sibling
            #     branch's same-named assignment does not mask an ungated one). Covers the
            #     clampOffLevel'd mistVirtual/warmLvl/fanSpeedRaw locals and the
            #     warmOnStr -> warmOn -> warmLvl -> clampOffLevel multi-hop chain.
            if any(_local_gated_before(ident, emit_off, assigns)
                   for ident in set(_IDENT_RE.findall(val))):
                continue
            # (d) emit lexically inside a power-gated if-block (Core per-mode speed; FanLib
            #     if(!powerOn)/else helper).
            if _enclosing_gated_if(body_clean, emit_off):
                continue

            # Ungated active-level emit -> BP6 finding.
            abs_pos = brace_start + emit_off
            lineno = _line_of(text_clean, abs_pos)
            findings.append(make_finding(
                severity='FAIL',
                rule_id='RULE44_bp6_power_gate',
                title=(
                    f'BP6: {method_name}() emits active-level attribute "{attr}" with no '
                    f'power gate — an OFF device would report its last-set level/speed'
                ),
                file_rel=file_rel,
                lineno=lineno,
                raw_lines=raw_lines,
                why=(
                    'VeSync keeps mist/warm/speed levels at their last-set value while the '
                    'device is OFF. Emitting them verbatim from the status-parse path produces '
                    'a "switch=off, Mist: L5" / "switch=off, speed=high" contradiction on '
                    'dashboards (Bug Pattern #6). This is a PER-EMIT check: each active-level '
                    'emit must be individually gated, even if a sibling emit in the same body is.'
                ),
                fix=(
                    'Route the active level through clampOffLevel(value, powerOn) from '
                    'LevoitChildBaseLib (returns 0 when off), assign it to a local derived '
                    'from a gating token (e.g. `warmOn = powerOn && …`), or place the emit '
                    'inside an `if (!powerOn) … else …` / `if (!enabled)` branch. Do NOT clamp '
                    'the SETPOINT attributes virtualLevel/level — those intentionally retain '
                    'their value while off.'
                ),
            ))

    return findings


ALL_RULES = [check_rule44_bp6_power_gate]
