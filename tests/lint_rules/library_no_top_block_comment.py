"""
library_no_top_block_comment.py — RULE29: forbid /* ... */ block-comment doc
headers in Hubitat library files (both file-scope and body-scope).

Rationale (verified 2026-04-30 on hub firmware 2.4.4.156 AND 2.5.0.126):
  Hubitat's library parser silently fails with `{"success":false,"message":"Internal
  error"}` from POST /library/saveOrUpdateJson when a library source contains a
  multi-line /* ... */ block comment of certain content shape. No specific syntactic
  trigger is isolated; the bug correlates with content shape + multi-line block-comment
  form. Single-line `// ...` comments work around it cleanly. Same content; different
  comment syntax = saves vs fails. Bisection evidence: ~30 in-browser variant tests
  via CodeMirror.setValue + Save + response capture in the v2.4 release cycle.

  One transient trip also observed during body-scope /* */ polish pass (v2.5 cycle).
  Not reproducible on re-deploy, but the defensive policy is: keep ALL /* */ blocks
  out of library files. The rule is therefore widened to cover body-scope blocks
  (after the library() declaration) as well as file-scope blocks (before it).

  Practical impact: any library file that lands in this repo with a /* */ doc block
  breaks HPM install (HPM hits the same broken save endpoint). End users see a
  generic "Internal error" toast and no install. v2.4 release blocker class.

  This rule prevents drift — any future library added to the repo must use //
  line comments exclusively, until Hubitat fixes the platform parser bug.

Scope:
  Applies ONLY to files identified as Hubitat libraries via is_library_file()
  (i.e., files containing a top-level `library(...)` block). Driver files (which
  use `definition(...)`) are unaffected — the bug is library-parser-specific.

Detection — two passes:

  Pass 1 (file-scope): Walk the source from line 1; skip blank lines and lines
  starting with //. The MIT license block at the very top of the file is permitted
  (first /* */ block). Any subsequent /* */ block BEFORE the library() declaration
  is forbidden.

  Pass 2 (body-scope): After the library() declaration closes (i.e., the line
  containing the closing `)` of the library() call), scan remaining lines for any
  /* */ or /** */ block comment. Every such block is forbidden — convert to //
  line comments. The single-line /* ... */ form (open and close on same line) is
  also caught.

Severity: FAIL (BLOCKING). Library that fails to save = HPM install broken =
  not shippable.

Out-of-scope:
  - Driver files (definition() block, not library())
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file


def check_rule29_library_no_top_block_comment(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE29: a Hubitat library file MUST NOT contain /* ... */ block comments:
      - File-scope: any /* */ block after the first one (MIT header) and before library()
      - Body-scope: any /* */ or /** */ block after the library() declaration

    Returns one FAIL finding per forbidden block detected (up to 2: one file-scope,
    one body-scope). Returns empty list (PASS) for non-library files.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    if not is_library_file(raw_text):
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    block_open_re    = re.compile(r'^\s*/\*')
    block_close_re   = re.compile(r'\*/')
    library_decl_re  = re.compile(r'^\s*library\s*\(')
    library_close_re = re.compile(r'^\s*\)')

    # ------------------------------------------------------------------
    # Pass 1: file-scope — forbid any /* */ block after the MIT header
    # and before the library() declaration.
    # The very first /* */ block (typically the MIT license) is permitted.
    # ------------------------------------------------------------------
    in_block              = False
    block_count           = 0
    second_block_open_line = None
    library_decl_line     = None
    library_close_line    = None
    in_library_decl       = False

    for idx, line in enumerate(raw_lines, start=1):
        # Detect start of library() declaration
        if not in_library_decl and library_decl_re.search(line) and not in_block:
            in_library_decl = True
            library_decl_line = idx

        # Detect close of library() declaration (the ) on its own line)
        if in_library_decl and library_close_re.match(line) and not in_block:
            library_close_line = idx
            break

        # Don't scan block comments once we're inside the library() args
        if in_library_decl:
            continue

        if not in_block:
            if block_open_re.match(line):
                in_block = True
                block_count += 1
                if block_count >= 2 and second_block_open_line is None:
                    second_block_open_line = idx
                # Same-line single-line block: /* ... */
                if block_close_re.search(line):
                    in_block = False
        else:
            if block_close_re.search(line):
                in_block = False

    if second_block_open_line is not None:
        findings.append({
            "severity": "FAIL",
            "rule_id": "RULE29_library_block_comment_at_top",
            "title": "Library file contains /* */ block comment after the MIT header and before library() (Hubitat platform-bug workaround)",
            "file": file_rel,
            "line": second_block_open_line,
            "context": raw_lines[second_block_open_line - 1].rstrip(),
            "why": (
                "Hubitat's library parser (verified on FW 2.4.4.156 and 2.5.0.126) "
                "fails to save library files containing /* ... */ block comments at "
                "file scope (after the optional MIT header, before the library() "
                "declaration). POST /library/saveOrUpdateJson returns "
                "`{success:false, message:'Internal error'}` and no compile error is "
                "logged. End users hitting this through HPM see the same generic "
                "toast and the library install fails. v2.4 release blocker class."
            ),
            "fix": (
                "Convert the /* ... */ block to // line comments. Same content, "
                "different syntax. Each line of the block becomes a `// ` line. "
                "See Drivers/Levoit/LevoitDiagnosticsLib.groovy for the canonical "
                "example. Track the upstream Hubitat bug filing in TODO.md."
            ),
        })

    # ------------------------------------------------------------------
    # Pass 2: body-scope — forbid any /* */ or /** */ block after the
    # library() declaration closes.
    # ------------------------------------------------------------------
    if library_close_line is None:
        # Could not locate library() closing paren — skip pass 2
        return findings

    in_block          = False
    body_block_line   = None

    for idx, line in enumerate(raw_lines, start=1):
        if idx <= library_close_line:
            continue

        if not in_block:
            if block_open_re.match(line):
                in_block = True
                if body_block_line is None:
                    body_block_line = idx
                # Same-line single-line block: /* ... */
                if block_close_re.search(line):
                    in_block = False
                    break  # one finding is enough
        else:
            if block_close_re.search(line):
                in_block = False
                break  # one finding is enough

    if body_block_line is not None:
        findings.append({
            "severity": "FAIL",
            "rule_id": "RULE29_library_block_comment_in_body",
            "title": "Library file contains /* */ block comment after library() declaration (Hubitat platform-bug defensive policy)",
            "file": file_rel,
            "line": body_block_line,
            "context": raw_lines[body_block_line - 1].rstrip(),
            "why": (
                "One transient save failure was observed during a body-scope /* */ "
                "polish pass (v2.5 cycle). Not reproducible on re-deploy, but the "
                "defensive policy is to keep ALL /* */ blocks out of library files. "
                "Hubitat's library parser bug (BP20) is not fully characterized — "
                "the known trigger is file-scope blocks, but body-scope blocks are "
                "also forbidden by convention to prevent drift. Use // line comments "
                "for all documentation in library files."
            ),
            "fix": (
                "Convert the /* ... */ or /** ... */ block to // line comments. "
                "Same content, different syntax. Each line of the block becomes a "
                "`// ` line. See Drivers/Levoit/LevoitDiagnosticsLib.groovy for "
                "the canonical example."
            ),
        })

    return findings


ALL_RULES = [check_rule29_library_no_top_block_comment]
