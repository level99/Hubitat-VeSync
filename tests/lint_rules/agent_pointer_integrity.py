"""
agent_pointer_integrity.py — RULE24: verify section-pointer cross-references in agent definitions.

During the v2.2 slim-down, agent definition files (.claude/agents/*.md) were updated
to replace inline content with pointers like:
  See `CLAUDE.md` "Logging conventions"
  See `CONTRIBUTING.md` "Codebase orientation"

If the referenced section header is later renamed in the target file, the pointer
silently breaks -- the agent reads a stale reference and may give wrong guidance.

This rule:
  1. Globs .claude/agents/*.md for pointer patterns.
  2. Extracts each (target_file, section_header) pair.
  3. Verifies the referenced section actually exists as a ## or ### heading in the
     target file (CLAUDE.md or CONTRIBUTING.md at repo root).
  4. FAILs with file:line, the broken pointer text, and a fix hint.

Pointer detection:
  Matches the phrase pattern used in the slim-down:
    see `FILENAME` "Section header"
    See `FILENAME` "Section header"
    see FILENAME "Section header"
  Also handles backtick-less forms and is case-insensitive on "see".
  Extracts filename from backtick-quoted or plain identifiers ending in .md.
  Extracts section header from the following double-quoted string.

  Multiple pointers per line are handled via finditer over the whole line.

Skipped content:
  Lines inside fenced code blocks (``` ... ```) are skipped to avoid false
  positives from example code that mentions "see CLAUDE.md" as illustration.
  Simple heuristic: track fenced-block open/close on lines starting with ```.

Section matching:
  Looks for `## <header>` or `### <header>` in the target file.
  Match is case-sensitive (section headers are canonical; a rename at either
  end should be a deliberate change, not a typo silently hidden by case folding).
  Leading/trailing whitespace on the header text is stripped before comparison.
"""

import re
from pathlib import Path
from functools import lru_cache


# ---------------------------------------------------------------------------
# Patterns
# ---------------------------------------------------------------------------

# Matches: see `CLAUDE.md` "Section name"
# or:      See CONTRIBUTING.md "Section name"
# Group 1: filename (with or without backticks)
# Group 2: section header text (inside double quotes)
#
# Deliberately permissive on quote style around filename (backtick or none).
# Uses non-greedy match on section header to handle multiple pointers per line.
POINTER_PATTERN = re.compile(
    r'[Ss]ee\s+`?([A-Za-z0-9_\-\.]+\.md)`?\s+"([^"]+)"',
    re.IGNORECASE,
)

# Fenced code block delimiter: a line starting with ``` (possibly after whitespace)
FENCE_PATTERN = re.compile(r'^\s*```')


# ---------------------------------------------------------------------------
# Section-header extraction from a target file
# ---------------------------------------------------------------------------

def _extract_section_headers(file_path: Path) -> set:
    """
    Return the set of section header texts (the part after ## or ### )
    from the given Markdown file. Returns empty set if file not found.
    """
    if not file_path.exists():
        return set()
    text = file_path.read_text(encoding='utf-8', errors='replace')
    headers = set()
    for line in text.splitlines():
        # Match ## or ### header lines (but not #### and deeper)
        m = re.match(r'^#{2,3}\s+(.+)', line)
        if m:
            headers.add(m.group(1).strip())
    return headers


# Cache per-session so repeated pointer lookups against the same file don't
# re-read the file on every agent-file line. lru_cache with Path args requires
# Path to be hashable (it is).
@lru_cache(maxsize=8)
def _cached_section_headers(file_path: Path) -> frozenset:
    return frozenset(_extract_section_headers(file_path))


# ---------------------------------------------------------------------------
# Finding builder
# ---------------------------------------------------------------------------

def _making_finding(severity, rule_id, title, file_str, lineno, context, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": file_str,
        "line": lineno,
        "context": context,
        "why": why,
        "fix": fix,
    }


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule24_agent_pointer_integrity(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE24: Verify that section pointers in .claude/agents/*.md refer to
    headings that actually exist in the referenced target file.

    Returns list of findings. FAIL for each broken pointer.
    WARN for each pointer to a target file that doesn't exist at repo root.
    """
    findings = []

    # Only check agent definition files
    path_str = str(path).replace('\\', '/')
    if '.claude/agents/' not in path_str:
        return findings
    if path.suffix != '.md':
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    in_fence = False
    for lineno, line in enumerate(raw_lines, 1):
        # Track fenced code blocks (skip content inside them)
        if FENCE_PATTERN.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue

        # Find all pointer matches on this line
        for m in POINTER_PATTERN.finditer(line):
            target_filename = m.group(1)   # e.g. "CLAUDE.md" or "CONTRIBUTING.md"
            section_header  = m.group(2)   # e.g. "Logging conventions"

            # Resolve target file relative to repo root
            target_path = rel_base / target_filename

            if not target_path.exists():
                findings.append(_making_finding(
                    severity="WARN",
                    rule_id="RULE24_target_file_missing",
                    title=f"Pointer to non-existent file '{target_filename}' in agent definition",
                    file_str=file_rel,
                    lineno=lineno,
                    context=f"    {line.rstrip()}",
                    why=(
                        f"RULE24 (Agent Pointer Integrity): the agent definition references "
                        f"'{target_filename}' which does not exist at repo root. Either the file "
                        f"was not yet created, was renamed, or the path is wrong."
                    ),
                    fix=(
                        f"Verify '{target_filename}' exists at repo root, or update the pointer "
                        f"to reference the correct filename."
                    ),
                ))
                continue

            # Check the section header exists in the target file
            headers = _cached_section_headers(target_path)
            if section_header not in headers:
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE24_broken_section_pointer",
                    title=f"Broken section pointer: '{section_header}' not found in {target_filename}",
                    file_str=file_rel,
                    lineno=lineno,
                    context=f"    {line.rstrip()}",
                    why=(
                        f"RULE24 (Agent Pointer Integrity): the agent definition contains "
                        f"see `{target_filename}` \"{section_header}\" but no '## {section_header}' "
                        f"or '### {section_header}' heading exists in {target_filename}. "
                        f"The pointer was likely valid before a section rename."
                    ),
                    fix=(
                        f"Section header '{section_header}' not found in {target_filename}. "
                        f"Did the section get renamed? Update the pointer to the current heading, "
                        f"or restore the heading in {target_filename}."
                    ),
                ))

    return findings


ALL_RULES = [check_rule24_agent_pointer_integrity]
