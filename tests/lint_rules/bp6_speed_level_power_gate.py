"""
bp6_speed_level_power_gate.py — RULE44: flag an ACTIVE-level attribute emit inside
an applyStatus/update status-parse body that is not power-gated.

Bug Pattern #6 — a device reports its last-set level/speed while powered OFF; emitting
that value verbatim produces a "switch=off, Mist: L5" / "switch=off, speed=high"
contradiction on dashboards. The active level must read 0/off while the device is off.

The active-level attributes (the ones that describe what the device is CURRENTLY doing)
are a fixed allowlist:

    mistLevel, warmMistLevel, warmMistEnabled, speed, fanSpeed

These MUST be power-gated in the status-parse path. The canonical mechanism is the
shared helper ``clampOffLevel(value, powerOn)`` from LevoitChildBaseLib (returns 0 when
off); a ``!powerOn`` / ``!enabled`` guard branch around the emit is also accepted.

NOT in the allowlist (deliberately): ``virtualLevel`` and ``level`` are SETPOINT
attributes (the dimmer/SwitchLevel target the device will run at next). They intentionally
retain their value while off, so they are never flagged — this is the active-vs-setpoint
distinction that keeps the rule low-false-positive WITHOUT dataflow analysis: it is keyed
on the attribute NAME, not on tracing the value's origin.

Scope:
  - Only ``.groovy`` files under ``Drivers/Levoit/``.
  - Only inside method bodies named ``applyStatus`` or ``update`` (the status-parse path
    where the device's reported state is turned into events). Write-path command setters
    (``set*`` / ``cycle*`` / ``on`` / ``off``) emit speed deliberately after a command and
    are NOT scanned — their emit reflects the command just sent, not a poll of a maybe-off
    device.

Detection — a status-parse method body is flagged when:
  (i)   it emits one of the active-level attributes via ``sendEvent(name:"<attr>", ...)``
        or ``handleEvent("<attr>", ...)``; AND
  (ii)  the body contains NO gating token — neither ``clampOffLevel(`` nor a
        ``!powerOn`` / ``!enabled`` / ``!status.result.enabled`` / ``!status?.result?.enabled``
        guard.

  This is intentionally a method-level (not per-emit) check: every correctly-gated
  status-parse body in this codebase carries exactly one gating mechanism that governs
  all of its active-level emits, so method-level presence is a faithful proxy and avoids
  the dataflow needed to prove a specific local was clamped. A body that emits an active
  attr with zero gating tokens is unambiguously the BP6 bug.

Exemptions:
  Add ``bp6_speed_level_power_gate_exemptions`` to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: applyStatus
      rationale: "reason this body legitimately emits an active level ungated"
"""

import re
from lint_rules._helpers import make_finding

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Status-parse entry points: `applyStatus(` and `update(<status-ish first param>)`. The
# first-param anchor on update() keeps the rule pinned to the methods that actually receive
# and parse a device-status Map — it excludes the zero-arg `update()` self-fetch (no status
# arg to gate) AND unrelated `updateX` methods (e.g. updateFirmware/updateDisplay), which
# never matched the name anyway but are now also excluded by the param shape. Write-path
# setters (set*/cycle*/on/off) are out of scope — their speed emit reflects a command just
# issued, not a poll of a possibly-off device.
STATUS_METHOD_RE = re.compile(
    r'^\s*def\s+(applyStatus|update)\s*\(\s*(?:def\s+)?status\b',
    re.MULTILINE,
)

# Active-level attribute emits (sendEvent name:"X" or handleEvent("X", ...)). These
# describe the device's CURRENT activity and must read 0/off when the device is off.
# virtualLevel / level are SETPOINTs and are intentionally absent.
ACTIVE_ATTRS = ('mistLevel', 'warmMistLevel', 'warmMistEnabled', 'speed', 'fanSpeed')
_ATTR_ALT = '|'.join(ACTIVE_ATTRS)
ACTIVE_EMIT_RE = re.compile(
    r'(?:sendEvent\s*\(\s*name\s*:\s*["\'](?:' + _ATTR_ALT + r')["\']'
    r'|handleEvent\s*\(\s*["\'](?:' + _ATTR_ALT + r')["\'])'
)

# Any of the accepted power-gating tokens. clampOffLevel is the canonical helper; the
# raw !powerOn / !enabled guards cover the Core/Vital speed-off branches.
GATING_TOKEN_RE = re.compile(
    r'clampOffLevel\s*\('
    r'|!\s*powerOn'
    r'|!\s*enabled\b'
    r'|!\s*status\s*\??\.\s*result\s*\??\.\s*enabled'
)


def _find_method_body_end(source: str, brace_start: int) -> int:
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
    return source[:pos].count('\n') + 1


def _strip_line_comments(text: str) -> str:
    return re.sub(r'(?m)//[^\n]*', '', text)


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('bp6_speed_level_power_gate_exemptions', [])
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


def check_rule44_bp6_power_gate(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE44 (Bug Pattern #6): flag an applyStatus/update body that emits an active-level
    attribute (mistLevel / warmMistLevel / warmMistEnabled / speed / fanSpeed) without any
    power-gating token (clampOffLevel / !powerOn / !enabled). Setpoint attributes
    (virtualLevel / level) are intentionally out of scope.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    for m in STATUS_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)

        if (file_rel, method_name) in exemption_set:
            continue

        brace_start = raw_text.find('{', m.end() - 1)
        if brace_start == -1:
            continue
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue

        body = raw_text[brace_start:body_end]
        body_clean = _strip_line_comments(body)

        emit_m = ACTIVE_EMIT_RE.search(body_clean)
        if not emit_m:
            continue  # no active-level emit in this body -> nothing to gate

        if GATING_TOKEN_RE.search(body_clean):
            continue  # body is power-gated -> OK

        abs_pos = brace_start + emit_m.start()
        lineno = _line_of(raw_text, abs_pos)
        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE44_bp6_power_gate',
            title=(
                f'BP6: {method_name}() emits an active-level attribute '
                f'(mistLevel/warmMistLevel/warmMistEnabled/speed/fanSpeed) with no '
                f'power gate — an OFF device would report its last-set level/speed'
            ),
            file_rel=file_rel,
            lineno=lineno,
            raw_lines=raw_lines,
            why=(
                'VeSync keeps mist/warm/speed levels at their last-set value while the '
                'device is OFF. Emitting them verbatim from the status-parse path produces '
                'a "switch=off, Mist: L5" / "switch=off, speed=high" contradiction on '
                'dashboards (Bug Pattern #6).'
            ),
            fix=(
                'Route the active level through clampOffLevel(value, powerOn) from '
                'LevoitChildBaseLib (returns 0 when off), or gate the emit behind a '
                '`if (!powerOn) ... else ...` / `if (!status.result.enabled)` branch. '
                'Do NOT clamp the SETPOINT attributes virtualLevel/level — those are '
                'dimmer targets that intentionally retain their value while off.'
            ),
        ))

    return findings


ALL_RULES = [check_rule44_bp6_power_gate]
