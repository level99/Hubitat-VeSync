"""
groovy_lite.py — lightweight Groovy source helpers.

Does NOT attempt a full Groovy parse. Uses regex + brace-balance heuristics
to extract method bodies and strip comments well enough for the structural
checks this linter cares about.
"""

import re


def strip_line_comments(lines):
    """
    Return a list of lines with // single-line comments removed (text before //
    preserved, everything from // onward replaced by empty string).

    Does not handle // inside string literals — acceptable for our heuristics.
    """
    stripped = []
    for line in lines:
        # Simplistic: remove everything from first // that is not inside a string.
        # We handle the common case: optional leading whitespace, then code, then // comment.
        # Edge case (URL in string): tolerated as a false-preserve, not a false-flag.
        in_string = False
        result = []
        i = 0
        while i < len(line):
            ch = line[i]
            if ch == '"' and not in_string:
                in_string = True
                result.append(ch)
            elif ch == '"' and in_string:
                in_string = False
                result.append(ch)
            elif ch == '/' and not in_string and i + 1 < len(line) and line[i + 1] == '/':
                break  # rest of line is a comment
            else:
                result.append(ch)
            i += 1
        stripped.append(''.join(result))
    return stripped


def strip_block_comments(source):
    """
    Remove /* ... */ block comments from source text. Returns cleaned text.
    Preserves line count by replacing comment content with whitespace.
    """
    result = []
    i = 0
    in_block = False
    while i < len(source):
        if not in_block and source[i:i+2] == '/*':
            in_block = True
            result.append('  ')
            i += 2
        elif in_block and source[i:i+2] == '*/':
            in_block = False
            result.append('  ')
            i += 2
        elif in_block:
            # preserve newlines so line numbers stay accurate
            result.append('\n' if source[i] == '\n' else ' ')
            i += 1
        else:
            result.append(source[i])
            i += 1
    return ''.join(result)


def clean_source(raw_text):
    """
    Return (cleaned_text, cleaned_lines) with block comments stripped and
    line comments stripped. Both are used by different rule types.
    """
    no_blocks = strip_block_comments(raw_text)
    lines = no_blocks.splitlines()
    cleaned_lines = strip_line_comments(lines)
    return no_blocks, cleaned_lines


def find_method_bodies(text, method_name):
    """
    Find zero or more method bodies whose def signature matches method_name.
    Returns list of (start_line_1based, body_text) tuples.

    Heuristic: looks for `def method_name(` (allowing args and return type),
    then captures the brace-balanced body.

    Works for typical Groovy driver method definitions. Does NOT handle:
    - Methods defined as closures assigned to variables
    - Methods split across unusual whitespace patterns
    """
    results = []
    # Pattern: optional access modifier + def + method_name + ( ... )
    # Anchored loosely to handle groovy's flexible syntax
    pattern = re.compile(
        r'def\s+' + re.escape(method_name) + r'\s*\(',
        re.MULTILINE
    )
    lines = text.splitlines()

    for m in pattern.finditer(text):
        # Find position in line-terms
        start_pos = m.start()
        start_line = text[:start_pos].count('\n')  # 0-based

        # Scan from match position for the opening brace
        open_brace_pos = text.find('{', m.end())
        if open_brace_pos == -1:
            continue

        # Brace-balance scan
        depth = 0
        pos = open_brace_pos
        body_start = open_brace_pos
        body_end = open_brace_pos
        while pos < len(text):
            ch = text[pos]
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    body_end = pos
                    break
            pos += 1

        body_text = text[body_start:body_end + 1]
        results.append((start_line + 1, body_text))

    return results


def get_definition_block(text):
    """
    Extract the contents of the top-level metadata { definition(...) { ... } } block.
    Returns the inner text between the outer braces, or empty string if not found.
    """
    m = re.search(r'metadata\s*\{', text)
    if not m:
        return ''
    start = text.find('{', m.start())
    if start == -1:
        return ''
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return ''


def find_first_line_matching(pattern, lines, start=0):
    """
    Return the 1-based line number of the first line matching a compiled regex,
    or None if not found. Searches lines[start:].
    """
    for i, line in enumerate(lines[start:], start=start + 1):
        if pattern.search(line):
            return i
    return None
