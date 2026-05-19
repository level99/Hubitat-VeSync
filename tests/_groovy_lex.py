#!/usr/bin/env python3
"""
_groovy_lex.py — shared Groovy-aware lexer for the verifier family.

Provides comment/string/brace-depth scanning used by check_bp24_classification.py,
check_bp26_spec_coverage.py, and check_c3_gate_coverage.py.  Single source of truth:
fixes here propagate to all three verifiers automatically.

Handles ALL Groovy non-structural regions:
  - // line comments (to end of line)
  - /* */ block comments
  - triple-quoted strings  \"\"\"...\"\"\"
  - double-quoted strings  \"...\" (GString ${} braces tracked)
  - single-quoted strings  '...'
  - dollar-slashy strings  $/.../$  (unambiguous open/close markers)
  - slashy strings  /.../  (context-sensitive: opened only in expression-start
    position — see _SLASHY_OPENER_PREV for the grammar-derived set; NOT after an
    operand token such as identifier ) ] digit or close-quote)

Slashy-string context rule:
  A '/' that follows an operand is a division operator, not a slashy-string
  opener.  Two trackers collaborate: `last_structural` (the last non-whitespace
  single character) is checked against _SLASHY_OPENER_PREV for single-char
  expression-start operators; a two-variable token tracker (`cur_token` for the
  currently-building identifier, `prev_token` for the last completed token) is
  checked against _SLASHY_OPENER_KEYWORDS for keyword expression-start contexts
  such as 'return /re/', 'in /re/', 'case /re/', etc.  At each '/' the governing
  token is `cur_token` (if non-empty) else `prev_token`.  Whitespace completes
  `cur_token` → `prev_token`; any non-ident non-whitespace char also completes
  and resets.  False-positive direction is safe (over-strip / over-skip), never
  silent-drop of real structural content.

Public API:
  _strip_comments_and_strings(text)  -> str
  _find_struct_braces(source, start) -> list[tuple[int, str]]
  extract_method_body(source, def_pos) -> str
"""

# ---------------------------------------------------------------------------
# Slashy-string context helpers
# ---------------------------------------------------------------------------

# Single-character tokens after which a bare '/' opens a slashy string.
#
# CLOSED PRINCIPLE — this set is minimal: it contains only chars that are
# BOTH (a) grammar-unambiguous slashy-openers (a '/' whose immediate non-ws
# predecessor is one of these is necessarily expression-start, never division)
# AND (b) actually occur, or plausibly occur, immediately before a slashy in
# this codebase's Groovy.  Grammar-correct-but-currently-unexercised operators
# (e.g. '>', '<', '^', '*', '%', '+', '-', '!') are DELIBERATELY EXCLUDED to
# minimize the false-positive-slashy silent-drop surface.  In particular, '+'
# and '-' are excluded because postfix '++' / '--' make '+' / '-' ambiguous as
# last_structural before a division '/' (e.g. 'a++ / b' → last char '+', yet
# '/' is real division).  If a real corpus instance of '<op> /slashy/' for some
# other operator ever appears, add that single char HERE together with a
# non-vacuous both-ways regression guard — never speculatively.
#
# Members:
#   = ( [ { , ; : ?   — assignment, open-bracket, separator, ternary
#   | &               — bitwise/logical binary ops
#   ~                 — regex-find =~ / match ==~ / not-find !~ (last char '~')
#                       and Pattern literal ~/re/.  Real corpus instance:
#                       LevoitDiagnosticsLib.groovy  tn =~ /v?(...)\s*$/
# Does NOT include letters/digits/)/] (operand chars), which signal division.
_SLASHY_OPENER_PREV = frozenset('=([{,;:?|&~')

# Keyword tokens after which a bare '/' is in expression-start position.
# These are value/expression-start keywords in Groovy: the token ends a
# statement or sub-expression and the following '/' begins a new expression.
# Does NOT include operand-yielding tokens where '/' would be division
# (e.g. 'true', 'false', 'null', 'this', 'super' yield values; excluded).
_SLASHY_OPENER_KEYWORDS = frozenset({
    'return', 'in', 'case', 'assert', 'else', 'new', 'instanceof', 'do',
})


def _is_slashy_open_position(last_structural: str, governing_token: str) -> bool:
    """
    Return True when a '/' at the current position opens a slashy string.

    Two recognition paths:
      1. last_structural is empty (start of scan) or is a single-char
         expression-start operator in _SLASHY_OPENER_PREV.
      2. governing_token (cur_token if non-empty, else prev_token at the call
         site) is in _SLASHY_OPENER_KEYWORDS — handles 'return /re/',
         'in /re/', 'x in /re/', 'case /re/', etc.

    Conservative: false-positive direction is over-strip (loud), never
    silent-drop of real structural content.  After an operand token such as
    an identifier, ')', ']', digit, or close-quote the '/' is division.
    """
    if not last_structural:
        return True
    if last_structural[-1] in _SLASHY_OPENER_PREV:
        return True
    if governing_token in _SLASHY_OPENER_KEYWORDS:
        return True
    return False


def _skip_slashy_string(text: str, i: int, n: int) -> int:
    """
    Advance past a Groovy slashy string whose opening '/' is at position i.
    Returns the index AFTER the closing '/'.
    Treats '\\/' as an escaped slash that does NOT close the string.
    Returns n if no closing '/' is found before end-of-input.
    """
    i += 1  # skip opening '/'
    while i < n:
        c = text[i]
        if c == '\\' and i + 1 < n:
            i += 2  # escaped char — skip both
            continue
        if c == '/':
            return i + 1  # closing slash
        i += 1
    return n


def _skip_dollar_slashy_string(text: str, i: int, n: int) -> int:
    """
    Advance past a Groovy dollar-slashy string whose '$/' is at position i.
    Returns the index AFTER the closing '/$'.

    Escape sequences handled (in check order):
      $$   — escaped literal '$' (two dollars, NOT a close marker)
      $/   — escaped literal '$/' (a dollar followed by slash, not close)
      /$   — the closing marker

    Returns n if no closing '/$' is found before end-of-input.
    """
    i += 2  # skip opening '$/'
    while i < n:
        # $$ — escaped literal dollar; skip both chars before checking $/ or /$
        if text[i] == '$' and i + 1 < n and text[i + 1] == '$':
            i += 2
            continue
        # $/ — escaped dollar-slash literal (not a close)
        if text[i] == '$' and i + 1 < n and text[i + 1] == '/':
            i += 2
            continue
        # /$ — closing marker
        if text[i] == '/' and i + 1 < n and text[i + 1] == '$':
            return i + 2
        i += 1
    return n


# ---------------------------------------------------------------------------
# _strip_comments_and_strings
# ---------------------------------------------------------------------------

def _strip_comments_and_strings(text: str) -> str:
    """
    Return text with // line comments and /* */ block comments removed,
    and string literal contents replaced by empty placeholders.

    Preserves line count: newlines inside removed regions are re-emitted as
    bare newlines so that line-number-based tests remain stable.

    Slashy strings /.../  and dollar-slashy strings $/.../$ are handled before
    the // / /* dispatch so an embedded '/*' inside a slashy literal is never
    misread as a block-comment opener (slashy-opener-checked-before-comment-dispatch).
    """
    result = []
    i = 0
    n = len(text)
    last_structural = ''
    cur_token  = ''   # currently-building identifier/keyword (not yet complete)
    prev_token = ''   # last COMPLETED identifier/keyword token

    while i < n:
        c = text[i]

        # Dollar-slashy: $/ ... /$  — check BEFORE bare-'/' dispatch.
        if c == '$' and i + 1 < n and text[i + 1] == '/':
            end = _skip_dollar_slashy_string(text, i, n)
            newlines = text[i:end].count('\n')
            result.append('\n' * newlines if newlines else '')
            i = end
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # '/' dispatch — slashy BEFORE // and /* (slashy-opener-checked-before-comment-dispatch).
        if c == '/':
            # Governing token: cur_token if mid-identifier, else prev_token.
            governing = cur_token if cur_token else prev_token
            # Slashy string opener (not // or /*).
            if (i + 1 < n
                    and text[i + 1] not in ('/', '*')
                    and _is_slashy_open_position(last_structural, governing)):
                end = _skip_slashy_string(text, i, n)
                newlines = text[i:end].count('\n')
                result.append('\n' * newlines if newlines else '')
                i = end
                last_structural = '"'
                cur_token = prev_token = ''
                continue
            # Line comment.
            if i + 1 < n and text[i + 1] == '/':
                end = text.find('\n', i)
                if end < 0:
                    break
                result.append('\n')
                i = end + 1
                continue
            # Block comment.
            if i + 1 < n and text[i + 1] == '*':
                end = text.find('*/', i + 2)
                if end < 0:
                    break
                newlines = text[i:end + 2].count('\n')
                result.append('\n' * newlines)
                i = end + 2
                continue
            # Division operator — emit and track.
            result.append(c)
            last_structural = c
            cur_token = prev_token = ''
            i += 1
            continue

        # Triple-quoted string  """..."""
        if c == '"' and text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            if end < 0:
                result.append('""""""')
                break
            newlines = text[i:end + 3].count('\n')
            result.append('""' + '\n' * newlines + '""')
            i = end + 3
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # Double-quoted string  "..."  (GString — skip whole span, no ${} depth tracking).
        # Intentional asymmetry: _strip uses a simple scan while _find_struct_braces
        # tracks GString ${} depth.  Both are correct for their callers: bp24 extracts
        # the method body via _find_struct_braces first, then strips within that body;
        # a nested GString brace cannot synthesize a phantom safeIntArg token.
        if c == '"':
            i += 1
            while i < n:
                ch = text[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == '"':
                    i += 1
                    break
                i += 1
            result.append('""')
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # Single-quoted string  '...'
        if c == "'":
            i += 1
            while i < n:
                ch = text[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == "'":
                    i += 1
                    break
                i += 1
            result.append("''")
            last_structural = "'"
            cur_token = prev_token = ''
            continue

        # Identifier/keyword character — accumulate into cur_token.
        if c.isalpha() or c == '_' or (c.isdigit() and cur_token):
            cur_token += c
            result.append(c)
            last_structural = c
            i += 1
            continue

        # Non-identifier character.
        result.append(c)
        if c not in ' \t\n\r':
            # Structural char: complete cur_token → prev_token, then reset both.
            if cur_token:
                prev_token = cur_token
                cur_token = ''
            else:
                prev_token = ''
            last_structural = c
        else:
            # Whitespace: complete cur_token → prev_token (token boundary), keep prev.
            if cur_token:
                prev_token = cur_token
                cur_token = ''
        i += 1

    return ''.join(result)


# ---------------------------------------------------------------------------
# _find_struct_braces
# ---------------------------------------------------------------------------

def _find_struct_braces(source: str, start: int) -> list[tuple[int, str]]:
    """
    Scan from `start` in source and return (position, '{' or '}') for every
    brace that is NOT inside a string literal or comment.

    Handled non-structural regions (Groovy-aware, slashy-strings-included):
      - // line comments
      - /* */ block comments
      - triple-quoted strings  \"\"\"...\"\"\"
      - double-quoted strings  \"...\" (GString ${} braces tracked — see asymmetry note)
      - single-quoted strings  '...'
      - dollar-slashy strings  $/.../$
      - slashy strings  /.../  (context-sensitive — expression-start only)
    """
    results: list[tuple[int, str]] = []
    i = start
    n = len(source)
    last_structural = ''
    cur_token  = ''   # currently-building identifier/keyword (not yet complete)
    prev_token = ''   # last COMPLETED identifier/keyword token

    while i < n:
        c = source[i]

        # Dollar-slashy: $/ ... /$  — before bare-'/' dispatch.
        if c == '$' and i + 1 < n and source[i + 1] == '/':
            i = _skip_dollar_slashy_string(source, i, n)
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # '/' dispatch — slashy BEFORE // and /* (slashy-opener-checked-before-comment-dispatch).
        if c == '/':
            # Governing token: cur_token if mid-identifier, else prev_token.
            governing = cur_token if cur_token else prev_token
            if (i + 1 < n
                    and source[i + 1] not in ('/', '*')
                    and _is_slashy_open_position(last_structural, governing)):
                i = _skip_slashy_string(source, i, n)
                last_structural = '"'
                cur_token = prev_token = ''
                continue
            if i + 1 < n and source[i + 1] == '/':
                i = source.find('\n', i)
                if i < 0:
                    break
                i += 1
                continue
            if i + 1 < n and source[i + 1] == '*':
                end = source.find('*/', i + 2)
                if end < 0:
                    break
                i = end + 2
                continue
            # Division operator — track and advance.
            last_structural = c
            cur_token = prev_token = ''
            i += 1
            continue

        # Triple-quoted string.
        if c == '"' and source[i:i + 3] == '"""':
            end = source.find('"""', i + 3)
            if end < 0:
                break
            i = end + 3
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # Double-quoted string (GString) — skip including ${...} spans.
        # Intentional asymmetry: _find_struct_braces tracks GString ${} depth
        # while _strip_comments_and_strings uses a simple scan.  Both are correct
        # for their callers: this function must not count a brace inside ${} as
        # structural; _strip only needs to consume the string, not track its braces.
        if c == '"':
            i += 1
            depth_gs = 0
            while i < n:
                ch = source[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == '"' and depth_gs == 0:
                    i += 1
                    break
                if ch == '$' and i + 1 < n and source[i + 1] == '{':
                    depth_gs += 1
                    i += 2
                    continue
                if ch == '}' and depth_gs > 0:
                    depth_gs -= 1
                    i += 1
                    continue
                i += 1
            last_structural = '"'
            cur_token = prev_token = ''
            continue

        # Single-quoted string.
        if c == "'":
            i += 1
            while i < n:
                ch = source[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == "'":
                    i += 1
                    break
                i += 1
            last_structural = "'"
            cur_token = prev_token = ''
            continue

        # Identifier/keyword character — accumulate into cur_token.
        if c.isalpha() or c == '_' or (c.isdigit() and cur_token):
            cur_token += c
            last_structural = c
            i += 1
            continue

        # Non-identifier character — complete cur_token → prev_token.
        if c not in ' \t\n\r':
            # Structural char: complete then reset both.
            if cur_token:
                prev_token = cur_token
                cur_token = ''
            else:
                prev_token = ''
            # Structural brace — record.
            if c == '{':
                results.append((i, '{'))
            elif c == '}':
                results.append((i, '}'))
            last_structural = c
        else:
            # Whitespace: complete cur_token → prev_token (token boundary), keep prev.
            if cur_token:
                prev_token = cur_token
                cur_token = ''
        i += 1

    return results


# ---------------------------------------------------------------------------
# extract_method_body
# ---------------------------------------------------------------------------

def extract_method_body(source: str, def_pos: int) -> str:
    """
    Extract a method body by brace-depth matching from the opening brace of
    the def at def_pos.  Skips braces inside string literals and comments
    (via _find_struct_braces).
    Returns the text from def_pos through the method's closing brace (inclusive).
    Falls back to source[def_pos:] if no opening brace is found.
    """
    try:
        brace_start = source.index("{", def_pos)
    except ValueError:
        return source[def_pos:]
    braces = _find_struct_braces(source, brace_start)
    depth = 0
    for pos, kind in braces:
        if kind == '{':
            depth += 1
        else:
            depth -= 1
            if depth == 0:
                return source[def_pos: pos + 1]
    return source[def_pos:]
