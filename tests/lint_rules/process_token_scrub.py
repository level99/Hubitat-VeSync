"""
process_token_scrub.py — RULE38: flag this-fork process-token labels in code and
test comments.

Process tokens (Tier N, Sweep N, PR #N, Lead-Finding-N, Round N, Gemini bot name,
etc.) become meaningless after the corresponding cycle closes and must not appear
in driver source, test source, or lint-rule source.  The behavioral invariant the
code enforces is the durable artifact — the process label that prompted the fix is
ephemeral and belongs only in commit messages.

Scope (per-file, IN scope):
  Drivers/Levoit/*.groovy          — driver and library source
  tests/*.py                       — lint runner and verifier scripts
  tests/_groovy_lex.py             — shared lexer module
  tests/lint_rules/*.py            — lint rule modules

OUT of scope (justification):
  CHANGELOG.md                     — release-history Tier/Sweep refs are legitimate
  CLAUDE.md / .claude/agents/**    — pipeline-internal docs, not shipped code
  CONTRIBUTING.md                  — documents the pattern as a pedagogical example
  levoitManifest.json / README.md  — user-facing release metadata, not code
  commit messages                  — Tier/Sweep belong there per maintainer style
  src/test/groovy/**               — Spock specs are NOT in lint.py's default
                                     scan_paths; process-token scrubbing of Spock
                                     specs is enforced by design-lane sweep /
                                     pre-flight discipline, not this rule.

PR / Issue scope split (by file type):
  .groovy files: PR/Issue patterns are NOT checked.  Every `PR #N` and `Issue #N`
    in Groovy driver comments refers to an external upstream (pyvesync, HA, etc.);
    the codebase contains no this-fork issue/PR numbers in driver source.  Excluding
    the pattern from Groovy files avoids the need for an exhaustive per-line
    external-provenance allowlist covering all of pyvesync's PR numbers.
  .groovy QA-finding enumeration (this-fork token; NOT pattern-detected, by
    design): QA-style list enumeration in driver comments -- "// - Issue N:",
    "// (Issues N, M)", "// Pattern N:" -- IS a this-fork process token and must
    be scrubbed, but is deliberately NOT covered by the Groovy PR/Issue pattern.
    The no-"#" "Issue N" form is indistinguishable from natural-language "issue"
    as a noun in driver comments, and catching it would require a Groovy
    external-provenance allowlist to avoid false-positives on legitimate no-"#"
    upstream citations -- materially more complex than the value of guarding a
    rare, already-scrubbed form.  This form is enforced by design-lane sweep /
    pre-commit scrub discipline -- the same boundary as "src/test/groovy/**"
    above -- not by this rule.  Any new QA-finding-enumeration comment introduced
    in a driver file must be scrubbed before commit; it is caught by the
    pre-flight sweep, not by "lint --strict".
  .py files: PR/Issue patterns ARE checked.  External-provenance references on the
    same line (pyvesync, HA, Homebridge, NiklasGustafsson, etc.) are suppressed by
    the allowlist.  This-fork process tokens (PR #167, Issue 2, etc.) are flagged.

External-provenance allowlist (Python files only) — lines containing any of these
tokens are suppressed for PR/Issue matches:
  pyvesync PR # / pyvesync issue #   — upstream pyvesync bug/PR references
  HA PR # / HA issue #               — Home Assistant upstream references
  Home Assistant                     — generic HA project name
  Homebridge                         — Homebridge project name
  SmartThings                        — SmartThings platform name
  webdjoe                            — pyvesync author handle
  NiklasGustafsson                   — upstream Hubitat fork author

Bare "upstream" and "github.com" are NOT in the allowlist — they co-occur with
this-fork process tokens and would create blind spots.  Project-specific GitHub
paths (e.g., in webdjoe/pyvesync URLs) are already covered by the "webdjoe" and
"niklasgustafs" allowlist tokens.

Pattern: two compile-time regexes — one for Groovy, one for Python.

Authoritative process-token form-set (T23-A):

GROOVY variant (Drivers/Levoit/):
  Does NOT include PR/Issue — all PR #N refs in Groovy driver source refer
  to external upstreams (pyvesync/HA); no this-fork process numbers appear
  in driver comments.  See module docstring for full rationale.

  Tier forms:
    Tier N       e.g. "Tier 22"
    Tier-N       e.g. "Tier-22"
    Tier #N      e.g. "Tier #22"
    Tier#N       e.g. "Tier#22"
    TierN        e.g. "Tier22" (no separator)
    T<digits>    e.g. "T11" "T22"  (bare abbreviation, word-boundary anchored)
    T<digits>-<alnum>  e.g. "T21-A" "T11-1"

  Sweep forms:
    Sweep N      e.g. "Sweep 16"
    Sweep #N     e.g. "Sweep #16"
    Sweep#N      e.g. "Sweep#16"
    S#N          e.g. "S#16"
    S<digits> PoC

  QA-round labels:
    Lead-Finding-N   e.g. "Lead-Finding-1"
    Round N          e.g. "Round 3" (QA/sweep context)
    NOTE: bare R<digits> (e.g. "R3") is deliberately EXCLUDED — ambiguous:
    Spock specs legitimately use R1/R2/... as numbered test-case row labels.
    "Round N" spelled out is unambiguous and is included.

  Bot names:
    Gemini             (any case)
    gemini-code-assist

PYTHON variant (tests/):
  All Groovy forms PLUS:

  PR / Issue forms (this-fork process tokens):
    PR N         e.g. "PR 167"
    PR #N        e.g. "PR #167"
    PR#N         e.g. "PR#4"
    Issue N      e.g. "Issue 2"
    issue N      (case-insensitive)
    Issue #N
    Issue#N
  External-provenance allowlist suppresses pyvesync/HA/etc. lines.
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding_for_file


# ---------------------------------------------------------------------------
# Scope configuration
# ---------------------------------------------------------------------------

# Path fragments that ARE in scope (at least one must be present in the
# forward-slash-normalised file path for the rule to apply).
_IN_SCOPE_FRAGMENTS = (
    'Drivers/Levoit/',
    'tests/',
)

# Specific filenames (basename) excluded regardless of parent directory.
_OUT_OF_SCOPE_BASENAMES = frozenset({
    'CHANGELOG.md',
    'CLAUDE.md',
    'CONTRIBUTING.md',
})


# ---------------------------------------------------------------------------
# Process-token patterns
# ---------------------------------------------------------------------------

_CORE_PATTERN_PARTS = (
    # Tier variants (word boundary before "Tier")
    r'\bTier\s*#?\s*\d+'                  # Tier N / Tier #N / Tier#N / TierN
    r'|\bTier-\d+'                        # Tier-N
    # Bare T<digits> abbreviation — word-boundary anchored; negative lookahead
    # prevents matching mid-word (e.g. "getT11" does NOT match).
    r'|\bT\d+(?:-[A-Za-z0-9]+)?(?!\w)'   # T11 / T22 / T21-A / T11-1
    # Sweep variants
    r'|\bSweep\s*#?\s*\d+'               # Sweep N / Sweep #N / Sweep#N
    r'|\bS#\d+'                          # S#16
    r'|\bS\d+\s+PoC'                     # S16 PoC
    # QA-round labels
    r'|\bLead-Finding-\d+'               # Lead-Finding-1
    r'|\bRound\s+\d+'                    # Round 3
    # Bot names
    r'|\b[Gg]emini\b'                    # Gemini / gemini
    r'|gemini-code-assist'               # gemini-code-assist
)

_PR_ISSUE_PATTERN_PARTS = (
    # PR/Issue (this-fork; allowlist suppresses external-provenance lines)
    r'|\bPR\s*#?\s*\d+'                  # PR N / PR #N / PR#N
    r'|\b[Ii]ssue\s*#?\s*\d+'           # Issue N / issue N / Issue #N / Issue#N
)

_GROOVY_TOKEN_RE = re.compile(
    r'(?:' + _CORE_PATTERN_PARTS + r')',
    re.IGNORECASE,
)

_PYTHON_TOKEN_RE = re.compile(
    r'(?:' + _CORE_PATTERN_PARTS + _PR_ISSUE_PATTERN_PARTS + r')',
    re.IGNORECASE,
)


# ---------------------------------------------------------------------------
# External-provenance allowlist (Python files only)
# If any token from this set appears on the same line as a PR/Issue match,
# the match is suppressed.  Checked as case-insensitive substring search.
# Bare "upstream" and "github.com" are intentionally NOT in this list —
# they co-occur with this-fork process tokens and would create blind spots.
# ---------------------------------------------------------------------------

_EXTERNAL_PROVENANCE_TOKENS = (
    'pyvesync',
    'home assistant',
    ' ha pr',
    ' ha issue',
    'homebridge',
    'smartthings',
    'webdjoe',
    'niklasgustafs',    # prefix of NiklasGustafsson
)

# Discrimination regex: identifies PR/Issue sub-matches for allowlist check.
_PR_ISSUE_RE = re.compile(
    r'(?:\bPR\s*#?\s*\d+|\b[Ii]ssue\s*#?\s*\d+)',
    re.IGNORECASE,
)


def _has_external_provenance(line: str) -> bool:
    """Return True if the line contains an external-provenance token."""
    lower = line.lower()
    return any(token in lower for token in _EXTERNAL_PROVENANCE_TOKENS)


# ---------------------------------------------------------------------------
# Comment extraction helpers
# ---------------------------------------------------------------------------

# Matches a single-line // comment: captures everything after the //
_LINE_COMMENT_RE = re.compile(r'//(.*)$')

# Matches Python # comment: captures everything after the #
_PY_COMMENT_RE = re.compile(r'#(.*)$')


def _comment_spans(line: str, file_ext: str) -> list:
    """
    Return a list of (start, end) index pairs covering comment regions on the
    given source line.  For Groovy (.groovy) files: // comments.
    For Python (.py) files: pure-comment lines only (lines where the first
    non-whitespace character is '#').  Mixed lines like
    ``x = some_fn('# not a comment')`` are excluded to avoid false-positives
    from process-token strings inside string literals.
    Returns empty list if no comment found.
    """
    spans = []
    if file_ext == '.groovy':
        m = _LINE_COMMENT_RE.search(line)
        if m:
            spans.append((m.start(), len(line)))
    elif file_ext == '.py':
        stripped = line.lstrip()
        if stripped.startswith('#'):
            m = _PY_COMMENT_RE.search(line)
            if m:
                spans.append((m.start(), len(line)))
    return spans


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule38_process_token_scrub(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE38: flag this-fork process-token labels (Tier N, Sweep N, PR #N,
    Lead-Finding-N, Round N, Gemini, etc.) in code and test comments.

    Process tokens become meaningless after the corresponding cycle closes.
    Replace with the behavioral invariant the code enforces.

    For Groovy files, PR/Issue patterns are not checked (all such refs in
    driver source are external-provenance — see module docstring).
    For Python files, PR/Issue patterns are checked with external-provenance
    allowlist suppression.
    """
    findings = []

    path_str = str(path).replace('\\', '/')
    basename = path.name

    # Out-of-scope: specific filenames
    if basename in _OUT_OF_SCOPE_BASENAMES:
        return findings

    # In-scope check
    in_scope = any(frag in path_str for frag in _IN_SCOPE_FRAGMENTS)
    if not in_scope:
        return findings

    file_ext = path.suffix.lower()
    if file_ext == '.groovy':
        token_re = _GROOVY_TOKEN_RE
    elif file_ext == '.py':
        token_re = _PYTHON_TOKEN_RE
    else:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for lineno, line in enumerate(raw_lines, 1):
        # Only scan within comment regions
        comment_spans = _comment_spans(line, file_ext)
        if not comment_spans:
            continue

        for m in token_re.finditer(line):
            match_pos = m.start()
            # Only flag matches that are inside a comment
            if not any(start <= match_pos < end for start, end in comment_spans):
                continue
            # For Python PR/Issue matches: suppress if line has external provenance
            if (file_ext == '.py'
                    and _PR_ISSUE_RE.match(m.group())
                    and _has_external_provenance(line)):
                continue
            findings.append(make_finding_for_file(
                severity='FAIL',
                rule_id='RULE38_process_token_scrub',
                title=(
                    f'Process token {m.group()!r} in comment - '
                    f'replace with the behavioral invariant'
                ),
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    'Process labels (Tier N, Sweep N, PR #N, Lead-Finding-N, Round N, '
                    'Gemini bot name) become meaningless after the cycle closes. '
                    'Code and test comments must describe the invariant the code enforces, '
                    'not the pipeline event that triggered the change.'
                ),
                fix=(
                    'Replace the process token with the behavioral invariant. '
                    'Example: "Lead-Finding-1 (T11 QA):" -> '
                    '"Parity guard: make_finding_for_file\'s inline severity gate must '
                    'reject invalid severities identically to make_finding". '
                    'External-provenance citations (pyvesync PR #N, HA issue #N, etc.) '
                    'are explicitly allowed and must NOT be changed.'
                ),
            ))

    return findings


ALL_RULES = [check_rule38_process_token_scrub]
