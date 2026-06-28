"""
capability_coherence.py — RULE47: a declared capability must have its required
command(s) and attribute(s) actually present in the driver's EFFECTIVE source.

A Hubitat `capability "X"` declaration is a contract: the platform exposes the
capability's standard commands and attributes (dashboard tiles, Rule Machine
conditions, HomeKit/Alexa bindings) ONLY if the driver actually implements the
required commands and EMITS the required attributes. Declaring a capability but
never emitting its attribute is a silent dead-capability bug: the tile/condition
exists but stays permanently null. The dead-AirQuality bug on EverestAir and
Sprout Air (declared `capability "AirQuality"`, emitted only a custom
`airQualityIndex`, never the standard `airQuality`) is the canonical instance,
and `supportedFanSpeeds` missing on every FanControl driver is the same class.

This is an enumerable class — "a declared capability whose required command/attr
is absent from the effective source" — so it is caught mechanically rather than
patched per-instance. The closed mechanism is this rule plus its contract map.

Effective source = the driver file PLUS every `#include level99.<Lib>` it pulls
(on(), off(), refresh(), setSpeed(), handleEvent("..."), etc. commonly live in a
library). Resolving includes is mandatory: scanning the driver body alone would
false-positive on every lib-provided command/emit.

Severity:
  - FAIL on a missing required COMMAND — a hard contract break (the command
    simply does not exist; the capability's primary action is unusable).
  - WARN on a required ATTRIBUTE that is never emitted — the dead-attribute class
    (tile/condition present but null). WARN is treated as FAIL under --strict, so
    a declared-but-unemitted attribute still gates the strict lint gate.

"Command present" = a `command "X"` declaration OR a `def X(` definition appears
in the effective source.
"Attribute emitted" = the attribute name appears as `sendEvent(name:"X"`,
`device.sendEvent(name:"X"`, or `handleEvent("X"` anywhere in the effective
source. Presence-of-emission only — the rule does NOT check the emitted value's
type or scale (Vital's string-valued `airQuality` must PASS; the type divergence
is a separate, documented concern). An `attribute "X"` declaration alone does NOT
satisfy an attribute requirement — it must be EMITTED (declaring-but-not-emitting
is exactly the bug this rule exists to catch).

Scope: driver files only (those with a `definition (`/`metadata {` block). Library
files and non-driver groovy are skipped.

Exemptions: via lint_config.yaml rule_id RULE47_capability_coherence with a
substantive reason (e.g. a capability deliberately declared for a marker role).
"""

import re

from lint_rules._helpers import make_finding_for_path, is_library_file, included_lib_texts


# Contract map: capability name -> {required commands, required emitted attributes}.
# Sensor/Actuator are pure markers (no command/attribute requirement). The `switch`
# attribute is NOT platform-auto-emitted — a driver declaring Switch must emit it
# (every fork driver does so in applyStatus/handlePower); a declared-but-never-emitted
# `switch` is the same dead-capability class as dead airQuality.
CAPABILITY_CONTRACT = {
    "AirQuality":                  {"commands": [],                                  "attributes": ["airQuality"]},
    "FanControl":                  {"commands": ["setSpeed"],                        "attributes": ["speed", "supportedFanSpeeds"]},
    "SwitchLevel":                 {"commands": ["setLevel"],                        "attributes": ["level"]},
    "Switch":                      {"commands": ["on", "off"],                       "attributes": ["switch"]},
    "Refresh":                     {"commands": ["refresh"],                         "attributes": []},
    "Notification":                {"commands": ["deviceNotification"],              "attributes": []},
    "Momentary":                   {"commands": ["push"],                            "attributes": []},
    "Configuration":               {"commands": ["configure"],                       "attributes": []},
    "ColorControl":                {"commands": ["setColor", "setHue", "setSaturation"], "attributes": ["hue", "saturation", "colorMode"]},
    "RelativeHumidityMeasurement": {"commands": [],                                  "attributes": ["humidity"]},
    "TemperatureMeasurement":      {"commands": [],                                  "attributes": ["temperature"]},
    "Sensor":                      {"commands": [],                                  "attributes": []},
    "Actuator":                    {"commands": [],                                  "attributes": []},
}

# A driver file has a `definition (` block; libraries have `library (` instead.
_DEFINITION_RE = re.compile(r'\bdefinition\s*\(', re.MULTILINE)

# `capability "X"` / `capability 'X'` — single-token name only (RULE42 handles the
# malformed-with-space case; here we only care about well-formed names).
_CAPABILITY_RE = re.compile(r'''\bcapability\s+(["'])([A-Za-z]\w*)\1''')


def _command_present(name, effective_text):
    """
    True if `command "name"` is declared OR a method named `name` is defined in the
    effective source.

    Method-definition detection matches a leading definition token before the name:
    `def`, `void`, `private`/`public`/`protected`/`static`, or a type token
    (`Integer push(...)`, `String configure(...)`). The leading-token requirement is
    what distinguishes a DEFINITION (`void push() {`) from a CALL site (`push()` /
    `configure()`), so a bare invocation does not satisfy the contract.
    """
    if re.search(r'''\bcommand\s+(["'])%s\1''' % re.escape(name), effective_text):
        return True
    # A method definition at the start of a line: <def|void|modifier|Type> name ( ... )
    # The leading definition token distinguishes a DEFINITION from a bare call site.
    # `def`/`void`/access modifiers, or a Type token (uppercase-initial: Integer,
    # String, Boolean, ...). Lowercase keywords that can precede a CALL (`return name()`,
    # `else name()`, `new Name()`) are deliberately NOT in the allowed leading set, so a
    # call site does not satisfy the contract.
    if re.search(
        r'(?:^|\n)\s*(?:def|void|private|public|protected|static|[A-Z]\w*)\s+'
        r'%s\s*\(' % re.escape(name),
        effective_text,
    ):
        return True
    return False


def _attribute_emitted(name, effective_text):
    """
    True if `name` is emitted via sendEvent/device.sendEvent/handleEvent anywhere in
    the effective source. Presence-of-emission only — no value/type inspection.

    Matches:
      sendEvent(name:"X"          device.sendEvent(name:"X"          handleEvent("X"
    with either quote style and optional whitespace after the colon / paren.
    """
    q = r'''["']%s["']''' % re.escape(name)
    # sendEvent(name: "X"   and   device.sendEvent(name: "X"
    if re.search(r'sendEvent\s*\(\s*name\s*:\s*%s' % q, effective_text):
        return True
    # handleEvent("X"
    if re.search(r'handleEvent\s*\(\s*%s' % q, effective_text):
        return True
    return False


def check_rule47_capability_coherence(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE47: every declared `capability "X"` must have its required command(s)
    present and its required attribute(s) emitted in the driver's effective source
    (driver body + all #include'd libraries).
    """
    findings = []

    if path.suffix != '.groovy':
        return findings
    # Skip library files — capabilities are declared on driver files only.
    if is_library_file(raw_text):
        return findings
    # Skip non-driver groovy (no definition() block, e.g. helper/support files).
    if not _DEFINITION_RE.search(raw_text):
        return findings

    # Effective source: driver body + every included library body. Include resolution
    # is mandatory — on()/off()/refresh()/setSpeed()/handleEvent() commonly live in a lib.
    effective_text = raw_text
    for lib_text in included_lib_texts(raw_text, path):
        effective_text = effective_text + "\n" + lib_text

    # Find each declared capability on its own line (so the finding points at the
    # capability declaration the contract is failing for).
    for i, line in enumerate(cleaned_lines, 1):
        m = _CAPABILITY_RE.search(line)
        if not m:
            continue
        cap = m.group(2)
        contract = CAPABILITY_CONTRACT.get(cap)
        if contract is None:
            # Unknown capability — not in the contract map. Don't flag (the map is the
            # authoritative known set; extend it rather than guessing requirements).
            continue

        for cmd in contract["commands"]:
            if not _command_present(cmd, effective_text):
                findings.append(make_finding_for_path(
                    severity="FAIL",
                    rule_id="RULE47_capability_coherence",
                    title=f'capability "{cap}" declared but required command "{cmd}" is absent',
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=(
                        f'Rule 47: declaring capability "{cap}" is a contract to provide the '
                        f'"{cmd}" command. It is not present in the driver body or any '
                        f'#include\'d library, so the capability\'s action is unusable — '
                        f'dashboards and Rule Machine see the capability but cannot invoke it. '
                        f'(Effective source = driver + included libs; include resolution applied.)'
                    ),
                    fix=(
                        f'Add `command "{cmd}", [...]` (or a `def {cmd}(...)` definition) to the '
                        f'driver or one of its #include\'d libraries, or remove the '
                        f'`capability "{cap}"` declaration if the capability is not actually supported.'
                    ),
                ))

        for attr in contract["attributes"]:
            if not _attribute_emitted(attr, effective_text):
                findings.append(make_finding_for_path(
                    severity="WARN",
                    rule_id="RULE47_capability_coherence",
                    title=f'capability "{cap}" declared but required attribute "{attr}" is never emitted',
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=(
                        f'Rule 47: declaring capability "{cap}" is a contract to emit the '
                        f'"{attr}" attribute. No sendEvent/handleEvent for "{attr}" appears in '
                        f'the driver body or any #include\'d library, so the capability is dead — '
                        f'its dashboard tile, Rule Machine conditions, and voice/HomeKit reads '
                        f'stay permanently null. This is the dead-capability class (e.g. the '
                        f'AirQuality tile reading only a custom airQualityIndex while the standard '
                        f'airQuality attribute stayed blank). An `attribute "{attr}"` declaration '
                        f'alone does NOT satisfy this — the attribute must be EMITTED.'
                    ),
                    fix=(
                        f'Emit "{attr}" in applyStatus/update (e.g. '
                        f'`device.sendEvent(name:"{attr}", value: ...)`), or for a static value '
                        f'emit it once in initialize()/updated(). Remove the `capability "{cap}"` '
                        f'declaration only if the capability is genuinely not supported.'
                    ),
                ))

    return findings


ALL_RULES = [check_rule47_capability_coherence]
