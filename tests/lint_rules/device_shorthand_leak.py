"""
device_shorthand_leak.py -- RULE41: flag internal device-name SHORTHANDS that
leak into USER-FACING prose.

Motivation: the v2.6 levoitManifest.json releaseNotes shipped "Sup6000S" (a dev
shorthand) instead of the public "Superior 6000S". The multi-agent QA fan-out's
operator lens missed it on multiple passes; only a different-model reviewer
caught it.  Judgment alone is unreliable for this class -- a mechanical check is
required.

Scope (authoritative predicate, not an example list):
  USER-FACING PROSE in three files ONLY:
    - levoitManifest.json -- the ``releaseNotes`` string VALUE only (NOT the
      driver ``name`` / ``location`` fields, which are metadata, not prose).
    - CHANGELOG.md -- user-facing version-section subsections (### Fixed /
      ### Changed / ### Added).  The ### Internal subsection and the top-of-file
      preamble are EXCLUDED -- implementation detail belongs there.
    - Drivers/Levoit/readme.md -- all lines (the readme is wholly user-facing).
  Dev-facing docs (CLAUDE.md, CONTRIBUTING.md, ROADMAP.md, .claude/**, tests/**)
  are OUT of scope -- internal shorthand is fine there.

DENYLIST DERIVATION (authoritative source = manifest driver ``name`` fields)
AND the conservatism that keeps it false-positive-free:

  Two empirical facts about this corpus shape the denylist:

  (A) The Core family public names are literally "Levoit Core200S Air Purifier",
      "Core300S", "Core400S", "Core600S" -- NO space -- so "Core200S" is the
      PUBLIC name, not a leak.  Likewise "LV600S", "EverestAir" alone are public.
      These are NOT in the denylist.

  (B) Several no-space forms (``Classic200S``, ``Classic300S``, ``Dual200S``,
      ``Vital100S``, ``Vital200S``) are the LITERAL VeSync device-type tokens
      the cloud API reports -- confirmed by the readme's own "device code
      literal ``Classic200S``" documentation.  They therefore appear legitimately
      in technical user-facing prose (e.g. ``"Dual200S" literal``,
      ``HUMIDIFIER_DETAILS["Dual200S"]``, ``(Classic200S)``) where they name the
      real token, NOT as a marketing-name leak.  Flagging them would produce
      false positives in legitimate technical prose.

  Therefore the denylist is restricted to the ABBREVIATED / CONTRACTED forms
  that are NOT valid VeSync device-type literals and have ZERO legitimate use in
  any prose -- pure dev shorthands.  These are the genuine "marketing name got
  garbled" leaks the rule exists to catch (the v2.6 ``Sup6000S`` incident is the
  canonical member):
    Sup6000S, Superior6000S, OM1000S, OM450S, OasisMist1000S, OasisMist450S,
    LV600SHC, LV600SHubConnect, SproutAir, SproutHumidifier, TowerFan,
    PedestalFan.
  Each maps to its spaced public name from the manifest.

CONSERVATISM / false-positive avoidance, layered:
  1. Closed, hand-curated denylist -- NOT a heuristic.  Real hardware model
     codes (LEH-S601S, LUH-O451S-WUS, LAP-C201S) contain hyphens + a vendor
     prefix and never appear here.
  2. Denylist excludes BOTH the no-space-is-public Core family (A) AND the
     no-space-is-a-real-API-literal families (B).  Only never-legitimate
     contractions remain.
  3. Backticked occurrences and ``*.yaml`` / ``*.groovy`` filename contexts are
     additionally suppressed (defense in depth, in case a contraction ever
     appears as a code reference).
  4. Manifest scope is the ``releaseNotes`` value only -- driver ``name`` fields
     are metadata, not prose, and are not scanned.

  WARN severity (wording hygiene) -- strict mode surfaces it as a gating finding.

Grow the denylist reactively: when a new CONTRACTED shorthand form leaks (one
that is not a valid VeSync device-type literal and whose manifest public form
differs), add the (shorthand -> public-name) pair.  Never add a form that is
either the manifest public name (Core200S, LV600S) OR a real VeSync device-type
literal (Dual200S, Classic200S, Vital100S/200S) -- those have legitimate prose
uses and would create false positives.
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding_for_file


# ---------------------------------------------------------------------------
# Scope: user-facing prose files only
# ---------------------------------------------------------------------------

_MANIFEST_BASENAME = 'levoitManifest.json'
_CHANGELOG_BASENAME = 'CHANGELOG.md'
_README_BASENAME = 'readme.md'
_README_PARENT = 'Levoit'  # Drivers/Levoit/readme.md


def _file_kind(path: Path) -> "str | None":
    """Return 'manifest' / 'changelog' / 'readme' for in-scope files, else None."""
    name = path.name
    if name == _MANIFEST_BASENAME:
        return 'manifest'
    if name == _CHANGELOG_BASENAME:
        return 'changelog'
    if name == _README_BASENAME and path.parent.name == _README_PARENT:
        return 'readme'
    return None


# ---------------------------------------------------------------------------
# Authoritative denylist: internal shorthand -> public name
# ---------------------------------------------------------------------------
#
# KEY = dev shorthand (an ABBREVIATED / CONTRACTED form that is NOT a valid
# VeSync device-type literal and has no legitimate prose use).  VALUE = public
# name as it appears in levoitManifest.json driver ``name`` fields.
#
# Deliberately EXCLUDED -- NOT leaks (would produce false positives):
#   - manifest public name == no-space form:  Core200S..Core600S, LV600S,
#     EverestAir.
#   - real VeSync device-type literals (legitimate in technical prose):
#     Dual200S, Classic200S, Classic300S, Vital100S, Vital200S.
_SHORTHANDS = {
    # Superior 6000S -- public "Superior 6000S"; confirmed v2.6 leak class
    'Sup6000S': 'Superior 6000S',
    'Superior6000S': 'Superior 6000S',
    # OasisMist family -- public "OasisMist 450S" / "OasisMist 1000S"
    'OM1000S': 'OasisMist 1000S',
    'OM450S': 'OasisMist 450S',
    'OasisMist1000S': 'OasisMist 1000S',
    'OasisMist450S': 'OasisMist 450S',
    # LV600S Hub Connect -- public "LV600S Hub Connect"
    'LV600SHC': 'LV600S Hub Connect',
    'LV600SHubConnect': 'LV600S Hub Connect',
    # Sprout family -- public "Sprout Air" / "Sprout Humidifier"
    'SproutAir': 'Sprout Air',
    'SproutHumidifier': 'Sprout Humidifier',
    # Fans -- public "Tower Fan" / "Pedestal Fan"
    'TowerFan': 'Tower Fan',
    'PedestalFan': 'Pedestal Fan',
}

# Word-boundary-anchored alternation, longest-first so e.g. "OasisMist1000S" is
# preferred over a (hypothetical) shorter prefix.
_SHORTHAND_RE = re.compile(
    r'\b(' + '|'.join(
        re.escape(s) for s in sorted(_SHORTHANDS, key=len, reverse=True)
    ) + r')\b'
)


# Sanity checks at module load.
assert _SHORTHAND_RE.findall('Sup6000S Superior 6000S LEH-S601S-WUS') == ['Sup6000S'], (
    "_SHORTHAND_RE sanity check failed -- matched a public name or model code"
)
assert _SHORTHAND_RE.findall('Core200S Core600S') == [], (
    "_SHORTHAND_RE sanity check failed -- Core family must NOT be in the denylist "
    "(no-space Core200S IS the manifest public name)"
)
assert _SHORTHAND_RE.findall('Dual200S Classic200S Vital200S Vital100S') == [], (
    "_SHORTHAND_RE sanity check failed -- real VeSync device-type literals "
    "(Dual200S, Classic*, Vital*) must NOT be in the denylist (legitimate prose use)"
)
assert _SHORTHAND_RE.findall('Sup6000S OM1000S LV600SHC') == ['Sup6000S', 'OM1000S', 'LV600SHC'], (
    "_SHORTHAND_RE sanity check failed -- expected the contracted dev shorthands"
)


# ---------------------------------------------------------------------------
# Code-literal suppression
# ---------------------------------------------------------------------------
#
# The no-space form is a legitimate VeSync API device-type literal and fixture
# filename.  Suppress matches that are clearly code references rather than prose.

def _is_code_literal(line: str, match_start: int, match_end: int) -> bool:
    """
    True if the match at [match_start, match_end) on *line* is a code reference
    (backtick-enclosed device-type literal, or a ``.yaml`` / ``.groovy`` filename)
    rather than user-facing prose.
    """
    # Backtick-enclosed: a backtick immediately before AND after (allowing the
    # shorthand to be the whole backtick span, e.g. `Dual200S`).
    before = line[match_start - 1] if match_start > 0 else ''
    after = line[match_end] if match_end < len(line) else ''
    if before == '`' and after == '`':
        return True
    # Filename context: shorthand immediately followed by a known source/fixture
    # extension (e.g. ``Dual200S.yaml``, ``Classic300S.groovy``).
    tail = line[match_end:match_end + 7].lower()
    if tail.startswith('.yaml') or tail.startswith('.yml') or tail.startswith('.groovy'):
        return True
    return False


# ---------------------------------------------------------------------------
# CHANGELOG section classifier (reuse the RULE39 user-facing-scope contract)
# ---------------------------------------------------------------------------

_VERSION_SECTION_RE = re.compile(r'^##\s+\[')
_USER_FACING_HEADERS = {'### fixed', '### changed', '### added'}
_INTERNAL_HEADER = '### internal'


def _changelog_in_scope_lines(raw_lines):
    """
    Yield (lineno_1based, line) for CHANGELOG lines inside a user-facing
    subsection (### Fixed / ### Changed / ### Added) of a version section.
    Excludes ### Internal and the top-of-file preamble.
    """
    in_version_section = False
    in_user_facing = False
    for lineno, line in enumerate(raw_lines, 1):
        stripped = line.strip()
        lower = stripped.lower()
        if _VERSION_SECTION_RE.match(stripped):
            in_version_section = True
            in_user_facing = False
            continue
        if not in_version_section:
            continue
        if lower in _USER_FACING_HEADERS:
            in_user_facing = True
            continue
        if lower == _INTERNAL_HEADER:
            in_user_facing = False
            continue
        if stripped.startswith('## '):
            in_version_section = True
            in_user_facing = False
            continue
        if stripped.startswith('### '):
            in_user_facing = False
            continue
        if in_user_facing:
            yield lineno, line


# ---------------------------------------------------------------------------
# Manifest releaseNotes line classifier
# ---------------------------------------------------------------------------

_RELEASENOTES_KEY_RE = re.compile(r'^\s*"releaseNotes"\s*:')


def _manifest_releasenotes_lines(raw_lines):
    """
    Yield (lineno_1based, line) for the manifest line(s) holding the
    ``releaseNotes`` value.  The manifest stores releaseNotes as a single
    JSON string (newlines escaped as ``\\n``), so it occupies exactly the one
    physical line beginning with the ``"releaseNotes":`` key.  Driver ``name``
    and ``location`` fields are NOT yielded.
    """
    for lineno, line in enumerate(raw_lines, 1):
        if _RELEASENOTES_KEY_RE.match(line):
            yield lineno, line


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule41_device_shorthand_leak(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE41: flag internal device-name shorthands leaking into user-facing prose.

    Only fires on levoitManifest.json (releaseNotes value only), CHANGELOG.md
    (user-facing subsections only), and Drivers/Levoit/readme.md (all lines).
    Code-literal device-type tokens (backticked, or ``*.yaml`` / ``*.groovy``
    filenames) are suppressed.  Emits a WARN per remaining shorthand occurrence
    with the suggested public name.
    """
    findings = []

    kind = _file_kind(path)
    if kind is None:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    if kind == 'manifest':
        scoped = _manifest_releasenotes_lines(raw_lines)
    elif kind == 'changelog':
        scoped = _changelog_in_scope_lines(raw_lines)
    else:  # readme -- wholly user-facing
        scoped = enumerate(raw_lines, 1)

    for lineno, line in scoped:
        for m in _SHORTHAND_RE.finditer(line):
            if _is_code_literal(line, m.start(1), m.end(1)):
                continue
            shorthand = m.group(1)
            public = _SHORTHANDS[shorthand]
            findings.append(make_finding_for_file(
                severity='WARN',
                rule_id='RULE41_device_shorthand_leak',
                title=(
                    f'Internal device shorthand {shorthand!r} in user-facing prose '
                    f'(use {public!r})'
                ),
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    f'{shorthand!r} is an internal dev shorthand. User-facing prose '
                    '(release notes, CHANGELOG, the device readme, HPM popups) must use '
                    f'the public device name {public!r} as it appears in the driver '
                    'metadata. Shorthands confuse users who only know the marketed name.'
                ),
                fix=(
                    f'Replace {shorthand!r} with {public!r}. If this is a code-literal '
                    'device-type token, wrap it in backticks. If it is genuinely a new '
                    'public name (not a leak), update the denylist in '
                    'tests/lint_rules/device_shorthand_leak.py.'
                ),
            ))

    return findings


ALL_RULES = [check_rule41_device_shorthand_leak]
