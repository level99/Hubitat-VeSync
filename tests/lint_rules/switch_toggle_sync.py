"""
switch_toggle_sync.py — RULE54: a driver whose toggle() prefers state.lastSwitchSet
must emit the poll `switch` attribute through emitSwitchState() (which also syncs
state.lastSwitchSet), NOT a raw `sendEvent(name:"switch", value: <x> ? "on":"off")`.

Bug class (v2.10 — "toggle inverts wrong after an external power change"):

toggle() reads `state.lastSwitchSet ?: device.currentValue("switch")` — a synchronous
read-after-write mirror, because device.currentValue is not updated synchronously right
after sendEvent within a rapid toggle sequence. on()/off() set state.lastSwitchSet on the
WRITE path, but the poll/applyStatus switch-emit historically did NOT. So after an EXTERNAL
power change (VeSync app / physical button / Alexa), the poll refreshed the `switch`
attribute but left state.lastSwitchSet stale — and toggle() then preferred the stale mirror
forever, inverting the wrong way. The fix routes every poll switch-emit through the shared
LevoitChildBaseLib helper `emitSwitchState(powerOn)`, which emits the attribute AND syncs
state.lastSwitchSet, so an out-of-band change is honored on the next poll.

Authoritative predicate (the CLASS, not an example list):
    A `.groovy` file whose toggle SCOPE (its own body PLUS every #include'd library body)
    contains the toggle read `state.lastSwitchSet ?:`, and whose OWN body still emits the
    switch attribute via a raw `<recv>.sendEvent(name:"switch", value: <ident> ? "on":"off")`
    ternary (the poll-emit shape) instead of `emitSwitchState(...)`.

Why include-resolution matters: the toggle() lives in a shared lib (LevoitFan /
LevoitHumidifier / LevoitVitalPurifier) for most drivers, while the poll switch-emit lives in
the driver's own applyStatus. Keying only on the emit shape would false-positive on Core200S /
Generic, which ALSO use the ternary emit but whose toggle() reads device.currentValue("switch")
directly (no lastSwitchSet mirror), so they need no sync.

Must-catch:
  - A driver that reads `state.lastSwitchSet ?:` (directly or via an include) and still has a
    raw `device.sendEvent(name:"switch", value: powerOn ? "on":"off")`.

Must-NOT-catch:
  - The same driver once the emit is `emitSwitchState(powerOn)`.
  - A file that uses the raw ternary emit but has NO `state.lastSwitchSet ?:` in scope
    (Core200S / Generic — their toggle uses currentValue, no mirror to keep in sync).
  - The write-path literal emits `sendEvent(name:"switch", value:"on")` (not the ternary shape).
"""

import re
from lint_rules._helpers import make_finding, included_lib_texts

DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# The toggle read that establishes the lastSwitchSet-preferred mirror.
_TOGGLE_READ_RE = re.compile(r'\bstate\s*\.\s*lastSwitchSet\s*\?:')

# A raw poll switch-emit: <recv>.sendEvent(name:"switch", value: <ident> ? "on" : "off").
# The ternary-on-an-identifier shape is the poll form (write-path emits use string literals
# like value:"on"). Group used only for line reporting.
_RAW_TERNARY_SWITCH_EMIT_RE = re.compile(
    r'\bsendEvent\s*\(\s*name\s*:\s*"switch"\s*,\s*value\s*:\s*\w+\s*\?\s*"on"\s*:\s*"off"'
)


def check_rule54_switch_toggle_sync(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """RULE54: FAIL a raw ternary poll switch-emit in a driver whose toggle prefers
    state.lastSwitchSet. Route it through emitSwitchState(powerOn) so the toggle
    read-after-write mirror stays in sync with external power changes seen by the poll."""
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    own_text = "\n".join(cleaned_lines)

    # Toggle scope = this file's own body + every included library body. A driver's toggle()
    # is frequently defined in an included lib, so the mirror-read can live outside this file.
    scope_texts = [own_text] + list(included_lib_texts(raw_text, path))
    is_toggle_mirror = any(_TOGGLE_READ_RE.search(t) for t in scope_texts)
    if not is_toggle_mirror:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')

    for m in _RAW_TERNARY_SWITCH_EMIT_RE.finditer(own_text):
        lineno = own_text[:m.start()].count('\n') + 1
        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE54_switch_toggle_sync',
            title=(
                'poll switch-emit uses a raw ternary sendEvent instead of emitSwitchState() '
                'in a driver whose toggle() prefers state.lastSwitchSet'
            ),
            file_rel=file_rel,
            lineno=lineno,
            raw_lines=raw_lines,
            why=(
                'toggle() reads `state.lastSwitchSet ?: device.currentValue("switch")`. If the poll '
                'emits the switch attribute directly (raw sendEvent) it does NOT update '
                'state.lastSwitchSet, so after an EXTERNAL power change (VeSync app / physical '
                'button / Alexa) the stale mirror shadows the fresh attribute forever and toggle() '
                'inverts the wrong way.'
            ),
            fix=(
                'Emit the poll switch state via emitSwitchState(powerOn) (LevoitChildBaseLib), which '
                'emits the attribute AND syncs state.lastSwitchSet.'
            ),
        ))

    return findings


ALL_RULES = [check_rule54_switch_toggle_sync]
