"""
library_no_top_block_comment.py — RULE29: forbid /* ... */ block-comment doc
headers in Hubitat library files.

Rationale (verified 2026-04-30 on hub firmware 2.4.4.156 AND 2.5.0.126):
  Hubitat's library parser silently fails with `{"success":false,"message":"Internal
  error"}` from POST /library/saveOrUpdateJson when a library source contains a
  multi-line /* ... */ block comment of certain content shape at the top of the file.
  No specific syntactic trigger is isolated; the bug correlates with content shape +
  multi-line block-comment form. Single-line `// ...` comments work around it
  cleanly. Same content; different comment syntax = saves vs fails. Bisection
  evidence: ~30 in-browser variant tests via CodeMirror.setValue + Save + response
  capture in the v2.4 release cycle.

  Practical impact: any library file that lands in this repo with a /* */ doc block
  at the top breaks HPM install (HPM hits the same broken save endpoint). End users
  see a generic "Internal error" toast and no install. v2.4 release blocker class.

  This rule prevents drift — any future library added to the repo must use //
  line comments at the top, until Hubitat fixes the platform parser bug.

Scope:
  Applies ONLY to files identified as Hubitat libraries via is_library_file()
  (i.e., files containing a top-level `library(...)` block). Driver files (which
  use `definition(...)`) are unaffected — the bug is library-parser-specific.

Detection:
  Walk the source from line 1; skip blank lines and lines starting with //. The
  first non-blank/non-comment line must NOT begin with /* (the start of a block
  comment). The MIT license block at the very top of the file (before any //
  conversion) is permitted because the v2.4 bisection showed the MIT block alone
  saves cleanly when followed only by // line comments and the library() block.
  In other words: the bug triggers on a /* */ block AFTER the MIT block. We
  conservatively forbid ALL /* */ blocks before the library() declaration except
  the very first one (the MIT/copyright header).

Severity: FAIL (BLOCKING). Library that fails to save = HPM install broken =
  not shippable.

Out-of-scope:
  - Driver files (definition() block, not library())
  - /* */ blocks INSIDE method bodies (javadoc above functions) — those are not
    at the top of the file and the bisection evidence shows they save cleanly
"""

import re
from pathlib import Path

from lint_rules._helpers import is_library_file


def check_rule29_library_no_top_block_comment(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE29: a Hubitat library file MUST NOT contain a /* ... */ block comment
    after the first one (the optional MIT/copyright header) and before the
    `library(` declaration.

    Returns one FAIL finding when a forbidden /* */ block is detected.
    Returns empty list (PASS) for non-library files.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    if not is_library_file(raw_text):
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    # Walk lines and count /* */ blocks before the library() declaration.
    # The very first /* */ block (typically the MIT license) is permitted.
    # Any subsequent /* */ block before library() is the bug-triggering pattern.
    block_open_re   = re.compile(r'^\s*/\*')
    block_close_re  = re.compile(r'\*/\s*$')
    library_decl_re = re.compile(r'^\s*library\s*\(')

    in_block        = False
    block_count     = 0
    second_block_open_line = None

    for idx, line in enumerate(raw_lines, start=1):
        # Stop scanning once we reach the library() declaration
        if library_decl_re.search(line) and not in_block:
            break

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
            "title": "Library file contains /* */ block comment after the MIT header (Hubitat platform-bug workaround)",
            "file": file_rel,
            "line": second_block_open_line,
            "context": raw_lines[second_block_open_line - 1].rstrip(),
            "why": (
                "Hubitat's library parser (verified on FW 2.4.4.156 and 2.5.0.126) "
                "fails to save library files containing /* ... */ block comments at "
                "the top of the file (after the optional MIT header, before the "
                "library() declaration). POST /library/saveOrUpdateJson returns "
                "`{success:false, message:'Internal error'}` and no compile error is "
                "logged. End users hitting this through HPM see the same generic "
                "toast and the library install fails. v2.4 release blocker class."
            ),
            "fix": (
                "Convert the /* ... */ block to // line comments. Same content, "
                "different syntax. Each line of the block becomes a `// ` line. "
                "Example:\n"
                "    Before:  /*                Now:  // Library overview\n"
                "              * Library overview          //\n"
                "              *                            // Exports: foo(), bar()\n"
                "              * Exports: foo(), bar()\n"
                "              */\n\n"
                "Tip: javadoc /* */ blocks INSIDE method bodies (above each function) "
                "are NOT affected and may stay as /* */. The bug is specifically the "
                "block-comment form at file scope before library(). See "
                "Drivers/Levoit/LevoitDiagnosticsLib.groovy for the canonical example "
                "of the workaround. Track the upstream Hubitat bug filing in TODO.md."
            ),
        })

    return findings


ALL_RULES = [check_rule29_library_no_top_block_comment]
