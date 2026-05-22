"""
bp24_auto_on_guard_missing.py — RULE32: flag SHOULD-ON configure-style command
methods that make API calls without a preceding auto-on guard.

Bug Pattern #24-B/C — Missing auto-on guard on configure-style commands.

SwitchLevel / FanControl / MistLevel capability convention: calling a
configure-style command (cycleSpeed, setMistLevel, setMode, setFanSpeed, etc.)
on an off-state device should auto-turn-on the device before applying the
command.  Without this guard the VeSync cloud accepts the command, state updates
correctly, but the physical device stays off — leaving an inconsistency between
Hubitat attributes and the real device state.

Sub-shapes covered:
  BP24-B: Method has API call but NO guard at all (``currentValue("switch")``
          absent AND ``ensureSwitchOn()`` absent).
  BP24-C: Method has an INLINE partial guard (``device.currentValue("switch")``
          present) but the ``!state.turningOn`` re-entrance flag is missing.
          This does NOT apply when the guard is ``ensureSwitchOn()`` — that
          helper encapsulates the re-entrance check internally, so the calling
          method legitimately never references ``state.turningOn``.

Guard recognition:
  Two equivalent guard forms are accepted:
    1. ``ensureSwitchOn()`` — canonical helper (preferred).  Encapsulates both
       the currentValue check and the !state.turningOn re-entrance flag.
       A method using this form is fully correct; BP24-C does NOT apply.
    2. Inline ``device.currentValue("switch")`` check.  Must be accompanied by
       ``state.turningOn`` somewhere in the method body (BP24-C check enforced).

Note on BP24-C detection: the partial-guard check is conservative.  We only
flag it if ``state.turningOn`` is nowhere in the method body at all — this
avoids false-positives on drivers that define ``state.turningOn`` in ``on()``
but read it in the guard on a different line from ``currentValue``.  This
means some BP24-C instances with the guard on separate lines may be missed;
those are caught by code review (Section K of QA checklist) rather than lint.

SHOULD-ON methods targeted by this rule:
  cycleSpeed, setFanSpeed, setSpeed, setMistLevel, setWarmMistLevel, setMode

SKIP-OK / NO-ON methods are excluded via ``bp24_auto_on_exemptions`` in
lint_config.yaml.  Each entry names (file, method) pairs to skip with a reason.

Detection algorithm:
  For each .groovy driver file under Drivers/Levoit/:
    For each method matching the SHOULD-ON regex:
      1. Extract the method body (brace-balanced).
      2. Check whether the body contains an API call
         (hubBypass / sendBypassRequest / sendLevel).
      3. If API call present:
         a. Check for ``ensureSwitchOn()`` (canonical helper) OR inline
            ``device.currentValue("switch")`` guard.
         b. If neither form present → BP24-B FAIL.
         c. If only the inline form is present AND ``state.turningOn`` is
            nowhere in the method body → BP24-C FAIL.
         d. If ``ensureSwitchOn()`` is present → PASS (re-entrance check is
            inside the helper; ``state.turningOn`` need not appear in caller).
      4. If no API call (pure delegation method, e.g. setSpeed(numeric) that
         only calls setSpeed(string)) → skip (no direct cloud interaction).

Exemptions:
  Add ``bp24_auto_on_exemptions`` list to lint_config.yaml:

    bp24_auto_on_exemptions:
      - file: Drivers/Levoit/LevoitVital100S.groovy
        method: setMode
        rationale: "SKIP-OK — V2 API rejects mode changes while off; ..."
      - ...

  Each entry suppresses RULE32 findings for that (file, method) pair.
  The ``rationale`` field is required (non-empty).

Library-aware (v2.5): library files are no longer excluded.  lint.py
invokes this rule for every ``.groovy`` file under Drivers/Levoit/,
including lib files, so SHOULD-ON methods extracted to a shared library
(e.g. ``cycleSpeed``, ``setSpeed``, ``setMode`` in LevoitVitalPurifierLib)
are covered.  Findings are attributed to the lib file path.  No driver-level
lib-content expansion is needed — de-duplication is implicit because lint.py
scans each file exactly once.

For lib-resident SKIP-OK methods, add a ``bp24_auto_on_exemptions`` entry
using the lib file's repo-relative path (e.g.
``Drivers/Levoit/LevoitVitalPurifierLib.groovy``).
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Regex to find SHOULD-ON method definitions.
# Matches method names: cycleSpeed, setFanSpeed, setSpeed, setMistLevel,
# setWarmMistLevel, setMode — with optional return type and optional parameter.
# The method signature must open a brace on the same line (or we scan from
# the { in the following lines via brace-tracking).
SHOULD_ON_METHOD_RE = re.compile(
    r'^\s*def\s+(cycleSpeed|setFanSpeed|setSpeed|setMistLevel|setWarmMistLevel|setMode)'
    r'\s*\([^)]*\)\s*\{',
    re.MULTILINE,
)

# API call patterns that mean the method directly contacts the VeSync cloud.
API_CALL_RE = re.compile(
    r'\b(?:hubBypass|sendBypassRequest|sendLevel)\s*\('
)

# Inline guard pattern: device.currentValue("switch") check.
# When present, the caller must also reference state.turningOn (BP24-C check).
INLINE_GUARD_RE = re.compile(
    r'device\.currentValue\s*\(\s*"switch"\s*\)'
)

# Helper guard pattern: ensureSwitchOn() call.
# This is the canonical preferred form.  It encapsulates both the currentValue
# check and the !state.turningOn re-entrance flag internally, so the calling
# method does NOT need to reference state.turningOn.  A method using only this
# form is fully correct — BP24-C does not apply.
ENSURE_SWITCH_ON_RE = re.compile(r'ensureSwitchOn\s*\(')

# Re-entrance flag pattern (required when inline guard is used, not when
# ensureSwitchOn() is used).
TURNING_ON_RE = re.compile(r'\bstate\.turningOn\b')


def _find_method_body_end(source: str, brace_start: int) -> int:
    """
    Walk forward from ``brace_start`` (the opening ``{`` of a method body)
    counting brace depth and return the index immediately after the closing
    ``}``.  Returns -1 if the body is unterminated.
    """
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
    """Return the 1-based line number of character position ``pos``."""
    return source[:pos].count('\n') + 1


def _strip_line_comments(text: str) -> str:
    """Remove full-line ``//`` comments (crude but sufficient for body scan)."""
    return re.sub(r'(?m)^\s*//.*$', '', text)


def _build_exemption_set(config: dict) -> set:
    """
    Parse ``bp24_auto_on_exemptions`` from config.  Returns a set of
    (normalized_file, method_name) pairs.  Logs nothing — malformed entries
    are silently skipped (the standard exemptions mechanism in lint.py handles
    the formal exemptions; this is a separate per-rule list).
    """
    exemptions = set()
    entries = config.get('bp24_auto_on_exemptions', [])
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


def check_rule32_auto_on_guard_missing(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE32 (Bug Pattern #24-B/C): Fail if a SHOULD-ON configure-style command
    method makes an API call without a preceding auto-on guard.

    Operates on raw_text for method-body extraction (to preserve string literals
    and brace structure).  Cleans line comments from the body before scanning
    for API calls and guards (avoids flagging commented-out legacy code).

    Library-aware (v2.5): library files are no longer excluded.  SHOULD-ON
    methods extracted to shared libraries (e.g. ``cycleSpeed``, ``setSpeed``,
    ``setMode`` in LevoitVitalPurifierLib) are scanned directly when lint.py
    invokes this rule with the lib file as ``path``.  Findings are attributed
    to the lib file.  Exemptions for lib-resident SKIP-OK methods should use
    the lib's repo-relative path as the ``file`` field.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    exemption_set = _build_exemption_set(config)

    for m in SHOULD_ON_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)

        # Check exemption list
        if (file_rel, method_name) in exemption_set:
            continue

        # Locate method body
        brace_start = m.end() - 1  # last char of match is '{'
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue  # malformed; skip

        body = raw_text[brace_start:body_end]
        body_clean = _strip_line_comments(body)

        # Does the method make a direct API call?
        if not API_CALL_RE.search(body_clean):
            # Pure delegation (e.g. calls another method that does the API call)
            # — not our responsibility; the callee should have the guard.
            continue

        # API call present — check for auto-on guard.
        method_start_line = _line_of(raw_text, m.start())

        has_ensure_switch_on = bool(ENSURE_SWITCH_ON_RE.search(body_clean))
        has_inline_guard = bool(INLINE_GUARD_RE.search(body_clean))
        has_guard = has_ensure_switch_on or has_inline_guard
        has_turning_on = bool(TURNING_ON_RE.search(body_clean))

        # BP24-C only applies when the guard is the inline currentValue form.
        # ensureSwitchOn() encapsulates the re-entrance check internally — the
        # calling method legitimately does not reference state.turningOn.
        needs_reentrance_check = has_inline_guard and not has_ensure_switch_on

        if not has_guard:
            # BP24-B: no guard at all
            findings.append(make_finding(
                severity="FAIL",
                rule_id="RULE32_auto_on_guard_missing",
                title=(
                    f"{method_name}() makes API call without auto-on guard "
                    "(Bug Pattern #24-B)"
                ),
                file_rel=file_rel,
                lineno=method_start_line,
                raw_lines=raw_lines,
                why=(
                    f"Bug Pattern #24-B: ``{method_name}()`` sends a command to the VeSync "
                    "cloud but does not check whether the device is on first. Calling this "
                    "method from automation on an off-state device causes the cloud to accept "
                    "the command but the physical device stays off — Hubitat attributes and "
                    "real device state diverge. SwitchLevel / FanControl / configure-style "
                    "command convention: SHOULD-ON methods must auto-turn-on before commanding."
                ),
                fix=(
                    f"Add ``ensureSwitchOn()`` at the top of ``{method_name}()`` (after "
                    "parameter validation, before the API call). ``ensureSwitchOn()`` is "
                    "provided by LevoitChildBaseLib and handles both the currentValue check "
                    "and the !state.turningOn re-entrance flag. Alternatively use the inline "
                    "form: ``if (!state.turningOn && device.currentValue('switch') != 'on') on()`` — "
                    "See Bug Pattern #24 in .claude/agents/vesync-driver-qa.md for the canonical "
                    "fix shape and the SHOULD-ON / SKIP-OK / NO-ON classification taxonomy. "
                    "If this method is intentionally SKIP-OK or NO-ON, add an exemption entry "
                    "to the ``bp24_auto_on_exemptions`` list in tests/lint_config.yaml with a "
                    "substantive ``rationale`` explaining why auto-on is undesirable here."
                ),
            ))
        elif needs_reentrance_check and not has_turning_on:
            # BP24-C: inline currentValue guard present but no state.turningOn re-entrance flag.
            # Does NOT fire when ensureSwitchOn() is the guard — that helper encapsulates
            # the re-entrance check internally.
            findings.append(make_finding(
                severity="FAIL",
                rule_id="RULE32_auto_on_guard_missing",
                title=(
                    f"{method_name}() inline auto-on guard is missing the state.turningOn "
                    "re-entrance flag (Bug Pattern #24-C)"
                ),
                file_rel=file_rel,
                lineno=method_start_line,
                raw_lines=raw_lines,
                why=(
                    f"Bug Pattern #24-C: ``{method_name}()`` has a "
                    "``device.currentValue('switch') != 'on'`` guard but lacks the "
                    "``!state.turningOn`` re-entrance check. Without the flag, if ``on()`` "
                    "internally re-enters this method (e.g. through a speed-init call during "
                    "turn-on), the method calls ``on()`` again recursively, causing a stack "
                    "overflow or double-initialization."
                ),
                fix=(
                    f"Replace the inline guard in ``{method_name}()`` with ``ensureSwitchOn()`` "
                    "(canonical preferred form, provided by LevoitChildBaseLib — handles "
                    "re-entrance internally). Or prefix the existing guard with the re-entrance "
                    "check: ``if (!state.turningOn && device.currentValue('switch') != 'on') on()`` — "
                    "``on()`` must set ``state.turningOn = true`` at entry (in a try/finally "
                    "block with ``state.remove('turningOn')`` in finally). This prevents the "
                    "recursive call when ``on()`` internally triggers a speed or mode command "
                    "during its own initialization sequence."
                ),
            ))
        # else: guard is ensureSwitchOn() (complete, re-entrance handled in helper),
        #       OR inline guard + state.turningOn both present — correct, no finding

    return findings


ALL_RULES = [check_rule32_auto_on_guard_missing]
