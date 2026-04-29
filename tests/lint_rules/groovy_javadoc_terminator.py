"""
groovy_javadoc_terminator.py — RULE26: detect `*/` literals embedded inside Groovy
Javadoc blocks.

Groovy (like Java) parses `*/` as the end-of-block-comment marker regardless of
context. If a `/**` block contains a literal `*/` anywhere before its intended
closing marker, the parser closes the comment at that point, leaving the remainder
of the intended Javadoc as apparent live code — causing compile errors.

This bit the codebase twice:

  v2.1 commit fe4d723: `LUH-O451S-*/LUH-O601S-*` inside a Javadoc model-code list.
    The `*/` after `LUH-O451S-` terminated the block; the rest parsed as live code;
    Spock failed to compile.

  v2.2 PR #4 BP14 cron-fix Gemini round: `"0 */${minutes} * * * ?"` inside a
    setupPollSchedule() Javadoc example. Same failure mode.

Both required tester-surface → dev-diagnose-and-fix → multiple iteration rounds.
This static rule catches them at lint time.

Algorithm:
  For each `/**` opening in the raw source, scan forward for every `*/` occurrence.
  For each `*/` found, classify it:

    LEGITIMATE CLOSE — the `*/` is structurally the end-of-comment marker:
      The raw line containing it, after stripping whitespace and an optional leading
      `*` (Javadoc continuation prefix), is empty or contains only the `*/` itself.
      Pattern: lines like `*/`, ` */`, `  */`, ` * */`. Stop scanning here — any
      earlier `*/` in this block were already flagged as embedded.

    EMBEDDED — the `*/` has non-whitespace content before it on the same line (e.g.
      `LUH-O451S-*/`, `"0 */${minutes} * * *`, `/* inner */`). Flag as a finding,
      then continue scanning for more `*/` in the same block.

  Special case: `/**/` — the `*/` appears at content_start (immediately after `/**`).
    The containing line is `/**/`, which does NOT strip to just `*/`. However, the
    distance (slash_pos - content_start == 0) signals no content was scanned before
    the close, so this is treated as an empty Javadoc with no findings. See the
    `slash_pos == content_start` guard in the implementation.

Scope: all .groovy files under Drivers/Levoit/.

Note: this rule operates on raw source text (NOT cleaned_lines). The groovy_lite
strip_block_comments helper strips `/* ... */` blocks, which is exactly the content
we need to inspect. Using cleaned_lines would eliminate the very tokens we need to
detect. raw_text is used directly.

HTML entity escapes such as `&#42;/` or `*&#47;` are NOT exempted: Groovy parses
the raw bytes, not HTML entities. If they appear inside a block comment they still
trigger the parse bug and should be flagged.
"""

import re
from pathlib import Path


# Only check .groovy files in the driver directory.
DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# A "legitimate close" line is one where, after stripping leading whitespace and
# an optional leading Javadoc-continuation `*`, the only remaining content is `*/`.
# Examples that match (legitimate close):
#   */
#    */
#   * */
#    * */
# Examples that do NOT match (embedded — has content before */):
#   * Supported models: LUH-O451S-*/LUH-O601S-*
#   * @example "0 */${minutes} * * * ?"
#   /* inner block */
_LEGITIMATE_CLOSE_RE = re.compile(r'^\s*\*?\s*\*/$')


def _line_of(text: str, pos: int) -> int:
    """Return the 1-based line number of character position pos in text."""
    return text[:pos].count('\n') + 1


def _context_at_line(raw_lines: list, lineno: int, window: int = 1) -> str:
    """Return up to (window) lines of context around lineno (1-based)."""
    start = max(0, lineno - 1 - window)
    end = min(len(raw_lines), lineno + window)
    return '\n'.join(f"    {raw_lines[i]}" for i in range(start, end))


def _is_legitimate_close(raw_lines: list, lineno: int) -> bool:
    """
    Return True if the raw line at lineno (1-based) looks like a Javadoc close:
    only whitespace + optional leading `*` + `*/`, nothing else on the line.

    This distinguishes:
      Legitimate:  " * */"  " */"  "*/"
      Embedded:    " * Supported models: LUH-O451S-*/"
                   ' * @example "0 */${minutes}"'
                   " /* inner comment */"
    """
    if lineno < 1 or lineno > len(raw_lines):
        return False
    line = raw_lines[lineno - 1]
    return bool(_LEGITIMATE_CLOSE_RE.match(line))


def check_rule26_javadoc_terminator(
    path: Path,
    raw_lines: list,
    cleaned_lines: list,
    raw_text: str,
    config: dict,
    rel_base: Path,
) -> list:
    """
    RULE26: detect `*/` literals embedded inside Groovy `/**` Javadoc blocks.

    An embedded `*/` closes the block-comment prematurely, leaving subsequent
    lines of the intended Javadoc as live code — a compile error.

    Operates on raw_text because groovy_lite.clean_source already strips block
    comments; inspecting cleaned_lines would destroy the evidence.

    For each `/**` block, scans forward through ALL `*/` occurrences and classifies
    each as either a legitimate close (line contains only whitespace + `*/`) or an
    embedded terminator (line has substantive content before the `*/`). The scan
    stops at the first legitimate close. Any embedded `*/` found before that close
    is a RULE26 finding.

    Returns a list of finding dicts, one per embedded `*/` found.
    """
    findings = []

    # Only check .groovy files in the driver directory.
    if path.suffix != '.groovy':
        return findings
    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    text_len = len(raw_text)

    # Scan for every `/**` opening in the file.
    # Use a position cursor that advances past each processed block so we
    # don't re-enter a block we just scanned.
    i = 0
    while i < text_len:
        open_pos = raw_text.find('/**', i)
        if open_pos == -1:
            break

        open_lineno = _line_of(raw_text, open_pos)
        # Content of the Javadoc block starts after the three-char `/**` marker.
        content_start = open_pos + 3

        # Scan forward from content_start for every `*/`.
        # Classify each one: legitimate close or embedded terminator.
        scan_pos = content_start
        block_closed = False

        while True:
            slash_pos = raw_text.find('*/', scan_pos)
            if slash_pos == -1:
                # No `*/` found — unclosed block comment. Not our bug; stop.
                # Advance outer cursor past the open so we don't loop forever.
                i = open_pos + 3
                block_closed = True  # sentinel: outer loop should re-check
                break

            # Special case: `/**/` — the `*/` immediately follows `/**` with
            # zero characters between them. This is an empty Javadoc block.
            # The raw line is something like `/**/` (not just `*/`), so the
            # legitimate-close regex would NOT match it. Guard explicitly:
            # if there is no content between `/**` and this `*/`, it's a clean
            # empty block with nothing that could be embedded — treat as close.
            if slash_pos == content_start:
                # Empty Javadoc `/**/`: no content → no embedded terminator.
                # Advance outer cursor past this close.
                i = slash_pos + 2
                block_closed = True
                break

            # Determine which raw line this `*/` falls on.
            slash_lineno = _line_of(raw_text, slash_pos)

            if _is_legitimate_close(raw_lines, slash_lineno):
                # This `*/` is the intended Javadoc close. Stop scanning.
                # The block is now fully consumed; advance past its close.
                i = slash_pos + 2
                block_closed = True
                break
            else:
                # Embedded `*/` — flag it and continue scanning in case
                # there are more embedded terminators before the close.
                context = _context_at_line(raw_lines, slash_lineno)
                findings.append({
                    "severity": "FAIL",
                    "rule_id": "RULE26_javadoc_terminator",
                    "title": (
                        f"Embedded `*/` inside Javadoc block "
                        f"(opened line {open_lineno}, "
                        f"premature close at line {slash_lineno})"
                    ),
                    "file": file_rel,
                    "line": slash_lineno,
                    "context": context,
                    "why": (
                        "Groovy closes a block comment at the first `*/` it "
                        "encounters. A `*/` embedded inside a `/**` Javadoc block "
                        "(e.g. in a model-code list like `LUH-O451S-*/LUH-O601S-*` "
                        "or a cron string like "
                        '`"0 */${minutes} * * * ?"`) terminates the comment early. '
                        "Everything between that embedded `*/` and the intended "
                        "block-close is then parsed as live Groovy code, causing a "
                        "compile error. See tests/lint_rules/groovy_javadoc_terminator.py "
                        "for background and historical occurrences."
                    ),
                    "fix": (
                        "Remove or escape the `*/` sequence from inside the Javadoc "
                        "block. Options: (a) replace `*/` in a model-code list with a "
                        "comma or line break (e.g. `LUH-O451S-*,` then newline "
                        "`LUH-O601S-*`); (b) for cron strings, move the example "
                        "outside the Javadoc into a `// comment` above or below; "
                        "(c) use an HTML-safe representation in prose "
                        "(e.g. `* /` with a space, or `{@literal */}`)."
                    ),
                })
                # Advance scan_pos past this embedded `*/` and keep looking.
                scan_pos = slash_pos + 2

        if not block_closed:
            # Safety: if inner loop exited without setting block_closed
            # (shouldn't happen given the break structure), advance by 1.
            i = open_pos + 3

    return findings


ALL_RULES = [check_rule26_javadoc_terminator]
