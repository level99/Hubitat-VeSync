"""
bp25_case_sensitivity.py — RULE33: flag on/off setter methods that compare raw
caller input against "on"/"off" without a prior toLowerCase() normalization.

Bug Pattern #25 — C3 gate case-sensitivity.

VeSync attribute values are stored and compared as lowercase ("on" / "off").
Rule Machine and dashboards may pass "ON" / "OFF" (uppercase).  Without
toLowerCase() normalization before both the C3 idempotency gate and the payload
coercion, two bugs fire simultaneously:

  (a) C3 gate bypass: ``device.currentValue("attr") == onOff`` evaluates
      ``"on" == "ON"`` as false, so the gate is bypassed and a redundant cloud
      call is made every time, even when the device state already matches.

  (b) Payload inversion: ``(onOff == "on") ? 1 : 0`` evaluates ``"ON" == "on"``
      as false, so the OPPOSITE command value is sent.  The device silently
      receives the wrong command (e.g., childLockSwitch:0 to lock, enabled:false
      to enable auto-stop).

Canonical fix pattern (from LevoitHumidifierLib.groovy doSetDisplayScreenSwitch):

    String v = (onOff as String).toLowerCase()   // normalize FIRST
    if (device.currentValue("attr") == v) return  // C3 gate uses v
    Integer sw = (v == "on") ? 1 : 0              // payload uses v
    ...
    device.sendEvent(name:"attr", value: v)        // event uses v

Detection algorithm:
  For each .groovy file under Drivers/Levoit/:
    For each method whose name starts with 'set' and whose first parameter
    name looks like an on/off selector (any name works; the rule checks whether
    that name is compared raw against "on"/"off" in the method body):
      1. Extract the method body (brace-balanced).
      2. Scan for a == "on" or == "off" comparison involving the first param.
      3. If comparison present: check whether a toLowerCase() call appears
         BEFORE the first such comparison.
      4. If no toLowerCase() before the comparison → RULE33 FAIL.

    Both single-parameter methods (setDisplay(onOff)) and multi-parameter
    methods with optional defaults (setNightlight(onOff, brightness = null))
    are scanned. Only the first parameter is checked — subsequent parameters
    are numeric/enum types that don't need on/off normalization.

False-positive protection:
  - Methods whose first parameter name is 'v', 'sw', or 'val' are SKIP —
    they're already normalized aliases, not raw input.
  - The LevoitHumidifierLib methods are already correct; this rule only fires
    on remaining unfixed sites.
  - Library files are included (same as RULE32).

Exemptions:
  Add ``bp25_case_sensitivity_exemptions`` list to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: setSomething
      rationale: "reason the comparison is safe without toLowerCase()"
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Regex to find 'set*' method definitions. Captures the first parameter only.
# Matches both single-param (setDisplay(onOff)) and multi-param with defaults
# (setNightlight(onOff, brightness = null, colorTemp = null)).
# Group 1: method_name, Group 2: first_param_name
SET_METHOD_RE = re.compile(
    r'^\s*def\s+(set\w+)\s*\(\s*(\w+)\s*(?:[,=)])',
    re.MULTILINE,
)

# Detects a == "on" or == "off" literal string comparison (not inside a comment).
# Matches patterns like: (foo == "on"), foo == "on", foo == "off"
ON_OFF_COMPARE_RE = re.compile(r'\b(\w+)\s*==\s*"(?:on|off)"')

# Detects a .toLowerCase() call on any expression (safe normalization present).
TO_LOWER_RE = re.compile(r'\.toLowerCase\s*\(\s*\)')


def _find_method_body_end(source: str, brace_start: int) -> int:
    """Walk forward from brace_start counting brace depth; return index after closing }."""
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
    """Return the 1-based line number of character position pos."""
    return source[:pos].count('\n') + 1


def _strip_line_comments(text: str) -> str:
    """Remove full-line // comments."""
    return re.sub(r'(?m)^\s*//.*$', '', text)


def _build_exemption_set(config: dict) -> set:
    exemptions = set()
    entries = config.get('bp25_case_sensitivity_exemptions', [])
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


def check_rule33_case_sensitivity(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE33 (Bug Pattern #25): Fail if a set* method performs a == "on" / "off"
    comparison without a prior toLowerCase() normalization on the raw parameter.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    for m in SET_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)
        param_name = m.group(2)

        if (file_rel, method_name) in exemption_set:
            continue

        # Methods whose param is already a short normalized alias (v, sw, val)
        # are unlikely to need toLowerCase — they're probably already normalized
        # upstream. Skip to avoid false positives on delegation wrappers.
        if param_name in ('v', 'sw', 'val'):
            continue

        # Find the opening { for this method definition.
        # The new SET_METHOD_RE ends at the first param separator, not at '{'.
        # Scan forward from the match end to find the method's opening brace.
        brace_start = raw_text.find('{', m.end() - 1)
        if brace_start == -1:
            continue
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue

        body = raw_text[brace_start:body_end]
        body_clean = _strip_line_comments(body)

        # Does the body contain a == "on" or == "off" comparison?
        compare_match = ON_OFF_COMPARE_RE.search(body_clean)
        if not compare_match:
            continue

        compare_pos = compare_match.start()
        compared_var = compare_match.group(1)

        # Is the compared variable the raw parameter or a local 'v'?
        # We only flag if the comparison uses a name that looks like the raw input.
        # If the method assigns `String v = (... .toLowerCase())` and then compares v,
        # that's correct (the toLowerCase happened before the compare).
        # If the comparison uses the original param name directly, that's a BP25 site.
        if compared_var != param_name:
            # Comparison uses a different variable (probably a normalized local).
            # Check whether toLowerCase() appears before the comparison position.
            body_before_compare = body_clean[:compare_pos]
            if TO_LOWER_RE.search(body_before_compare):
                # Normalization happened before the comparison — correct.
                continue
            # Comparison uses a different var and no toLowerCase() before it.
            # This is a derived-variable case; skip (the rule targets direct param use).
            continue

        # Comparison uses the raw parameter directly.
        # Check whether toLowerCase() appears anywhere before the comparison position.
        body_before_compare = body_clean[:compare_pos]
        if TO_LOWER_RE.search(body_before_compare):
            # toLowerCase() called on something before the comparison — correct.
            continue

        # BP25 site: raw parameter compared without prior toLowerCase().
        compare_line = _line_of(raw_text, brace_start + compare_pos)

        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE33_case_sensitivity',
            title=(
                f'BP25: {method_name}() compares raw `{param_name}` against '
                f'"on"/"off" without prior toLowerCase()'
            ),
            file_rel=file_rel,
            lineno=compare_line,
            raw_lines=raw_lines,
            why=(
                f'Rule Machine and dashboards may pass "ON"/"OFF" (uppercase). '
                f'Without toLowerCase() normalization: (a) the C3 idempotency gate '
                f'(`device.currentValue("attr") == {param_name}`) evaluates false even '
                f'when the device is already in the requested state; (b) the payload '
                f'coercion (`{param_name} == "on" ? 1 : 0`) sends the OPPOSITE value.'
            ),
            fix=(
                f'Add `String v = ({param_name} as String).trim().toLowerCase()` '
                f'immediately after the requireNotNull guard, then use `v` throughout '
                f'the method for all comparisons and payload coercions.'
            ),
        ))

    return findings


ALL_RULES = [check_rule33_case_sensitivity]
