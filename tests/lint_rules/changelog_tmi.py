"""
changelog_tmi.py -- RULE39: flag implementation-detail jargon in CHANGELOG.md
user-facing version-section bullets.

CHANGELOG.md serves two audiences: end users reading release notes, and the HPM
popup that surfaces bullet text directly.  Implementation jargon (exception class
names, internal method identifiers, bare BP/RULE catalog labels, issue/PR tracker
refs) is meaningless to these readers and must not appear in user-facing bullets.

Scope (authoritative predicate, not an example list):
  File: CHANGELOG.md only (exact basename match).
  Lines: bullet lines (starting ``- **`` or ``-`` followed by text) that appear
    inside a version-section user-facing subsection.  A "version section" is any
    block opened by ``## [x.y.z]`` or ``## [Unreleased]``.  A "user-facing
    subsection" is ``### Fixed``, ``### Changed``, or ``### Added``.  The
    ``### Internal`` subsection is explicitly excluded -- implementation detail
    belongs there.  The top-of-file preamble (before the first ``## [`` line) is
    excluded.

Four jargon categories (authoritative predicate):

  (i)  Exception-class jargon: a word ending in ``Exception`` or ``Error`` that
       follows an uppercase letter or contains a dot (i.e. looks like a Java/Groovy
       exception class name or FQN).  Examples: ``NullPointerException``,
       ``GroovyCastException``, ``java.lang.NumberFormatException``.
       Allowlist: none -- user-facing text never needs exception class names.
       Plain-English phrase like "an internal error" is always sufficient.

  (ii) Internal method-name jargon: detected via two complementary predicates.

       Method-call shape ``foo()`` (parens required) -- any ``\\w+()`` pattern
       where the name is NOT in the public driver command allowlist.  The allowlist
       covers commands that a user would legitimately call from Rule Machine or a
       dashboard (e.g. ``setLevel()``, ``setMistLevel()``, ``setMode()``,
       ``cycleSpeed()``, ``on()``, ``off()``, ``refresh()``, ``toggle()``,
       ``captureDiagnostics()``, ``forceReinitialize()``, ``resetAllChildren()``,
       ``resyncEquipment()``, ``probeNightLight()``, ``setDisplay()``,
       ``setChildLock()``, ``setAutoStop()``, ``setDryingMode()``, ``setTimer()``,
       ``cancelTimer()``, ``resetFilter()``, ``setAutoMode()``, ``setSpeed()``,
       ``setAutoPreference()``, ``setRoomSize()``, ``setNightLight()``,
       ``setNightlight()``, ``setNightlightMode()``, ``setNightlightSwitch()``,
       ``setHue()``, ``setSaturation()``, ``setColor()``, ``setWarmMistLevel()``,
       ``setHumidity()``, ``setTargetHumidity()``, ``setFanSpeed()``,
       ``setOscillation()``, ``setHorizontalOscillation()``,
       ``setVerticalOscillation()``, ``setMute()``, ``setLightDetection()``,
       ``setPetMode()``, ``setSmartCleaningReminder()``,
       ``setHorizontalRange()``, ``setVerticalRange()``,
       ``setHorizontalOscillationRange()``, ``setVerticalOscillationRange()``).
       Internal identifiers caught via this path include: polling helpers
       (``updateDevices()``, ``sendBypassRequest()``, ``applyStatus()``,
       ``ensurePollWatchdog()``, ``ensurePollHealth()``, ``deviceMethodFor()``),
       library internals (``safeIntArg()``, ``ensureSwitchOn()``,
       ``requireNotNull()``, ``logDebugOff()``, ``ensureDebugWatchdog()``,
       ``doSetDisplayScreenSwitch()``), Rule Machine setup helpers
       (``initialize()``, ``updated()``).

       Bare-name shape (no parens) -- a word-boundary match against
       ``_INTERNAL_JARGON_NAMES``, a small curated frozenset of compound
       camelCase names with NO plausible plain-English usage in user-facing
       release-notes prose.  Examples: ``applyStatus``, ``sendBypassRequest``,
       ``ensurePollHealth``.  Plain English words like ``update``, ``configure``,
       ``clear``, ``setup`` are deliberately NOT in this set -- they have natural
       English meaning and the parens-required shape above is sufficient to catch
       their call-site forms.  Reactively grow the set when a new bare-name
       instance is found in a live CHANGELOG sweep.

  Internal-name detection -- hybrid approach:
    - Method-call shape ``foo()`` always flagged via ``_METHOD_CALL_RE``
      (catches any name with parens; set membership determines allowlist vs jargon).
    - Bare-name shape ``foo`` only flagged if ``foo in _INTERNAL_JARGON_NAMES``
      (small curated set; grows reactively on real instances).
    - Plain English words deliberately not in the bare-name set (no plausible
      user-facing release-notes false-positive -- but if a real instance lands,
      evaluate per-case before adding to the set).

  (iii) Tracker-reference jargon: ``issue #N``, ``PR #N``, ``Issue #N``,
        ``PR#N``, ``issue#N`` where N is one or more digits.  These are pipeline
        cross-references, not user-meaningful information.

  (iv) Bare catalog-label jargon: ``BP`` optionally followed by ``#`` then one
       or more digits (e.g. ``BP23``, ``BP1``, ``BP#1``, ``BP#26``), or ``RULE``
       optionally followed by ``#`` then one or more digits (e.g. ``RULE37``,
       ``RULE22``, ``RULE#37``, ``RULE#39``), when they appear as standalone
       tokens (word-boundary anchored).  Parenthetical references like ``(BP22)``,
       ``(BP#22)``, ``(RULE37)``, or ``(RULE#37)`` are equally jargon -- the
       boundary anchor catches them.
       Note: ``BREAKING`` is not a catalog label and is explicitly allowed.
       Non-matching forms: ``BP`` with no following digits (``BPx``, ``BP123BC``),
       ``RULE`` with no following digits (``RULEa1``, ``RULEx``).
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding_for_file


# ---------------------------------------------------------------------------
# Section-state parser
# ---------------------------------------------------------------------------

# Version-section opener: ## [x.y.z] or ## [Unreleased]
_VERSION_SECTION_RE = re.compile(r'^##\s+\[')

# User-facing subsection headers
_USER_FACING_HEADERS = {'### fixed', '### changed', '### added'}

# Excluded subsection header
_INTERNAL_HEADER = '### internal'


def _classify_lines(raw_lines):
    """
    Yield (lineno_1based, line, in_user_facing_scope) for every line.

    in_user_facing_scope is True only when:
      - We are inside a ## [x.y.z] / ## [Unreleased] version section, AND
      - The current subsection is ### Fixed / ### Changed / ### Added
        (NOT ### Internal), AND
      - We have seen at least one ## [ opener (not preamble).
    """
    in_version_section = False
    in_user_facing = False

    for lineno, line in enumerate(raw_lines, 1):
        stripped = line.strip()
        lower = stripped.lower()

        # Version-section opener resets subsection state
        if _VERSION_SECTION_RE.match(stripped):
            in_version_section = True
            in_user_facing = False
            yield lineno, line, False
            continue

        # Not in a version section yet (preamble)
        if not in_version_section:
            yield lineno, line, False
            continue

        # Subsection header -- update tracking
        if lower in _USER_FACING_HEADERS:
            in_user_facing = True
            yield lineno, line, False
            continue
        if lower == _INTERNAL_HEADER:
            in_user_facing = False
            yield lineno, line, False
            continue
        # Any other ## header closes current subsection
        if stripped.startswith('## '):
            in_version_section = True
            in_user_facing = False
            yield lineno, line, False
            continue
        # Any ### header not matched above also closes user-facing scope
        if stripped.startswith('### '):
            in_user_facing = False
            yield lineno, line, False
            continue

        yield lineno, line, in_user_facing


# ---------------------------------------------------------------------------
# Category (i): exception-class jargon
# ---------------------------------------------------------------------------

# Matches words that look like Java/Groovy exception class names or FQNs.
# Positive forms: word ending in Exception or Error, with capital letter before
# the suffix (indicating PascalCase class name), OR containing a dot before it.
_EXCEPTION_CLASS_RE = re.compile(
    r'\b(?:[A-Z]\w*(?:Exception|Error)|[\w.]+\.(?:\w+)?(?:Exception|Error))\b'
)


# ---------------------------------------------------------------------------
# Category (ii): internal method-name jargon
# ---------------------------------------------------------------------------

# Public driver command allowlist -- these are user-callable and may appear in
# user-facing bullets without triggering RULE39.
_PUBLIC_COMMANDS = frozenset({
    # Basic power / state
    'on', 'off', 'toggle', 'refresh',
    # Levels / speeds
    'setLevel', 'setMistLevel', 'setWarmMistLevel', 'setFanSpeed',
    'setSpeed', 'cycleSpeed',
    # Modes
    'setMode', 'setAutoMode', 'setPetMode', 'setPurifierMode', 'setHumidityMode',
    # Preferences / config
    'configure',
    'setAutoPreference', 'setRoomSize',
    'setDisplay', 'setChildLock', 'setAutoStop', 'setAutoStopSwitch', 'setDryingMode',
    'setLightDetection', 'setMute', 'setSmartCleaningReminder',
    # Timer
    'setTimer', 'cancelTimer',
    # Filter
    'resetFilter',
    # Humidity / targets
    'setHumidity', 'setTargetHumidity',
    # Nightlight
    'setNightLight', 'setNightlight', 'setNightlightMode', 'setNightlightSwitch',
    # Color
    'setHue', 'setSaturation', 'setColor',
    # Oscillation
    'setOscillation', 'setHorizontalOscillation', 'setVerticalOscillation',
    'setHorizontalRange', 'setVerticalRange',
    'setHorizontalOscillationRange', 'setVerticalOscillationRange',
    # Diagnostics
    'captureDiagnostics',
    # Parent-driver lifecycle / management commands (user-callable from device UI)
    'forceReinitialize', 'resetAllChildren', 'resyncEquipment', 'probeNightLight',
})

# Matches identifier() patterns -- captures the name (without parens)
_METHOD_CALL_RE = re.compile(r'\b([a-zA-Z_]\w*)\(\)')

# Bare-name internal jargon -- compound camelCase names with NO plausible
# plain-English usage in user-facing release-notes prose.  Matched word-boundary
# anchored WITHOUT requiring trailing parens.
#
# Selection criteria: a name belongs here only if it is a compound camelCase
# identifier that any release-notes reader would recognise as a Groovy method
# name, not an English word.  Plain words like ``update``, ``configure``,
# ``clear``, ``setup`` are EXCLUDED; their call-site forms (with parens) are
# already caught by _METHOD_CALL_RE.  Grow this set reactively when a bare-name
# instance is found in a live CHANGELOG sweep.
_INTERNAL_JARGON_NAMES = frozenset({
    'applyStatus',
    'sendBypassRequest',
    'ensurePollHealth',
    'ensurePollWatchdog',
    'peelEnvelope',
    'seedPrefs',
    'hubBypass',
    'safeIntArg',
    'ensureSwitchOn',
    'ensureDebugWatchdog',
    'mapIntegerStringToSpeed',
    'requireNotNull',
    'handleEvent',
})

# Pre-built pattern for bare internal-jargon names (word-boundary anchored).
# Constructed from _INTERNAL_JARGON_NAMES at module load; no parens required.
_INTERNAL_JARGON_RE = re.compile(
    r'\b(' + '|'.join(re.escape(n) for n in sorted(_INTERNAL_JARGON_NAMES)) + r')\b'
)


# ---------------------------------------------------------------------------
# Category (iii): tracker references
# ---------------------------------------------------------------------------

_TRACKER_REF_RE = re.compile(
    r'\b(?:PR|[Ii]ssue)\s*#\s*\d+|\b(?:PR|[Ii]ssue)#\d+',
    re.IGNORECASE,
)

# External-provenance tokens: if a line contains any of these, tracker-ref
# matches on that line are suppressed.  Covers pyvesync/HA/Homebridge upstream
# references that are legitimate cross-references, not this-fork process tokens.
_TRACKER_EXTERNAL_TOKENS = (
    'pyvesync',
    'webdjoe',
    'homebridge',
    'home assistant',
    ' ha ',
    'HA PR',
    'HA issue',
    'niklasgustafs',
)


def _is_external_tracker_ref(line: str) -> bool:
    """Return True if the line's tracker ref is an external-provenance citation."""
    lower = line.lower()
    return any(tok.lower() in lower for tok in _TRACKER_EXTERNAL_TOKENS)


# ---------------------------------------------------------------------------
# Category (iv): bare catalog labels
# ---------------------------------------------------------------------------

# Matches BP<digits> or RULE<digits> as standalone tokens (word-boundary anchored).
# Optional '#' between prefix and digits covers both ``BP1`` and ``BP#1`` forms.
# Does NOT match BREAKING (which starts differently).
# Does NOT match non-digit suffixes: ``BPx``, ``BP123BC``, ``RULEa1``, ``RULEx``
# are not catalog labels and are not flagged.
# Sanity check (verified at module load via assertion below):
#   re.findall(_CATALOG_LABEL_RE, 'BP#1 BP1 RULE#37 RULE37 BP123BC RULEa1')
#   -> ['BP#1', 'BP1', 'RULE#37', 'RULE37']  (not the non-digit-suffix forms)
_CATALOG_LABEL_RE = re.compile(r'\b(BP#?\d+|RULE#?\d+)\b')

assert re.findall(_CATALOG_LABEL_RE, 'BP#1 BP1 RULE#37 RULE37 BP123BC RULEa1') == [
    'BP#1', 'BP1', 'RULE#37', 'RULE37'
], "_CATALOG_LABEL_RE sanity check failed -- pattern is too broad or too narrow"


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule39_changelog_tmi(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE39: flag implementation-detail jargon in CHANGELOG.md user-facing bullets.

    Only fires on CHANGELOG.md.  Scans ### Fixed / ### Changed / ### Added bullets
    inside ## [x.y.z] / ## [Unreleased] version sections.  Skips ### Internal and
    the top-of-file preamble.

    Categories checked:
      (i)   Exception class names / FQNs
      (ii)  Internal method-name identifiers (not in public-command allowlist)
      (iii) Issue #N / PR #N tracker references
      (iv)  Bare BP<N> / RULE<N> catalog labels
    """
    findings = []

    # Only fires on CHANGELOG.md
    if path.name != 'CHANGELOG.md':
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for lineno, line, in_scope in _classify_lines(raw_lines):
        if not in_scope:
            continue

        # Only check bullet lines
        stripped = line.strip()
        if not stripped.startswith('-'):
            continue

        # (i) Exception-class jargon
        for m in _EXCEPTION_CLASS_RE.finditer(line):
            findings.append(make_finding_for_file(
                severity='WARN',
                rule_id='RULE39_changelog_tmi',
                title=f'Exception class name {m.group()!r} in user-facing CHANGELOG bullet',
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    'Exception class names (NullPointerException, GroovyCastException, etc.) '
                    'are internal implementation details. Users and HPM popups cannot act on '
                    'them. Replace with plain-language symptom: "an internal error", '
                    '"silently failed", etc.'
                ),
                fix=(
                    f'Replace {m.group()!r} with a plain-language symptom description. '
                    'Move the exception class name to the ### Internal subsection if '
                    'it is needed for contributor reference.'
                ),
            ))

        # (ii) Internal method-name jargon -- method-call shape (parens required)
        for m in _METHOD_CALL_RE.finditer(line):
            name = m.group(1)
            if name not in _PUBLIC_COMMANDS:
                findings.append(make_finding_for_file(
                    severity='WARN',
                    rule_id='RULE39_changelog_tmi',
                    title=f'Internal method name {m.group()!r} in user-facing CHANGELOG bullet',
                    file_str=file_rel,
                    lineno=lineno,
                    context=f'    {line.rstrip()}',
                    why=(
                        f'{m.group()!r} is an internal implementation identifier, not a '
                        'user-callable command. End users and HPM popup readers cannot act '
                        'on it. Replace with a behavioral description: "the polling loop", '
                        '"the API request path", "the startup sequence", etc.'
                    ),
                    fix=(
                        f'Replace {m.group()!r} with a behavioral description. If it is a '
                        'public driver command, add it to the _PUBLIC_COMMANDS allowlist in '
                        'tests/lint_rules/changelog_tmi.py.'
                    ),
                ))

        # (ii) Internal method-name jargon -- bare-name shape (no parens required)
        # Only flags names in the curated _INTERNAL_JARGON_NAMES set; plain English
        # words like 'update', 'configure', 'clear' are not in the set.
        for m in _INTERNAL_JARGON_RE.finditer(line):
            name = m.group(1)
            # Skip if already caught by the parens path above (avoid double-finding)
            if f'{name}()' in line:
                continue
            findings.append(make_finding_for_file(
                severity='WARN',
                rule_id='RULE39_changelog_tmi',
                title=f'Internal jargon name {name!r} (bare, no parens) in user-facing CHANGELOG bullet',
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    f'{name!r} is an internal implementation identifier. End users and '
                    'HPM popup readers have no context for it. Replace with a behavioral '
                    'description ("the status parser", "the API request", etc.) or move '
                    'the reference to the ### Internal subsection.'
                ),
                fix=(
                    f'Replace {name!r} with a plain-language behavioral description, or '
                    'move the reference to the ### Internal subsection.'
                ),
            ))

        # (iii) Tracker references
        # Lines where the tracker ref is an external-provenance citation
        # (pyvesync PR #N, HA issue #N, etc.) are suppressed.
        for m in _TRACKER_REF_RE.finditer(line):
            if _is_external_tracker_ref(line):
                continue
            findings.append(make_finding_for_file(
                severity='WARN',
                rule_id='RULE39_changelog_tmi',
                title=f'Tracker reference {m.group()!r} in user-facing CHANGELOG bullet',
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    'Issue and PR numbers are pipeline cross-references, not user-meaningful '
                    'information. They belong in commit messages, not user-facing release notes. '
                    'Users cannot access them without knowing the repo URL and navigating GitHub.'
                ),
                fix=(
                    f'Remove the {m.group()!r} tracker reference. If the cross-reference is '
                    'needed for contributor context, move it to ### Internal.'
                ),
            ))

        # (iv) Bare catalog labels
        for m in _CATALOG_LABEL_RE.finditer(line):
            findings.append(make_finding_for_file(
                severity='WARN',
                rule_id='RULE39_changelog_tmi',
                title=f'Bare catalog label {m.group()!r} in user-facing CHANGELOG bullet',
                file_str=file_rel,
                lineno=lineno,
                context=f'    {line.rstrip()}',
                why=(
                    f'{m.group()!r} is an internal bug-pattern or lint-rule catalog label. '
                    'End users and HPM popup readers have no context for these labels. '
                    'Replace with the behavioral symptom the pattern describes, or move '
                    'the label to ### Internal.'
                ),
                fix=(
                    f'Replace {m.group()!r} with a plain-language behavioral description, '
                    'or move the reference to the ### Internal subsection.'
                ),
            ))

    return findings


ALL_RULES = [check_rule39_changelog_tmi]
