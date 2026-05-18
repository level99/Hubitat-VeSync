"""
bp25_case_sensitivity.py — RULE33: two sub-checks for Bug Pattern #25.

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

Canonical fix pattern (re-blessed):

    String v    = (onOff as String).trim().toLowerCase()  // normalize FIRST
    String canon = (v in ["on","true","1","yes"]) ? "on" : "off"  // truthy-coerce
    if (device.currentValue("attr") == canon) return       // C3 gate uses canon
    Integer sw = (canon == "on") ? 1 : 0                   // payload uses canon
    ...
    device.sendEvent(name:"attr", value: canon)             // event emits canon

Two sub-checks are performed:

  RULE33a (original) — raw comparison:
    A set* method performs a == "on"/"off" comparison against the raw first
    parameter without a prior toLowerCase() normalization.

  RULE33b (extended) — raw-normalized sendEvent:
    A set* or doSet* shared-helper method uses toLowerCase() normalization but
    emits the raw normalized variable in sendEvent instead of a truthy-derived
    canonical identifier.  The re-blessed canonical pattern derives canon via a
    ternary ``(v in ["on","true","1","yes"]) ? "on" : "off"`` and emits canon.
    Emitting `v` directly is non-canonical: truthy inputs "true"/"1"/"yes"
    would be stored as-is in the attribute instead of as "on".

    The doSet* coverage is required because the blessed canonical reference
    (LevoitHumidifierLib doSetDisplayScreenSwitch / doSetAutoStopSwitch) is
    itself a doSet*-named method; the check must be able to catch a regression
    in the reference implementation itself.

Detection algorithm (RULE33a):
  For each .groovy file under Drivers/Levoit/:
    For each method whose name starts with 'set' and whose first parameter
    name looks like an on/off selector (any name works; the rule checks whether
    that name is compared raw against "on"/"off" in the method body):
      1. Extract the method body (brace-balanced).
      2. Scan for a == "on" or == "off" comparison involving the first param.
      3. If comparison present: check whether a toLowerCase() call appears
         BEFORE the first such comparison.
      4. If no toLowerCase() before the comparison → RULE33a FAIL.

Detection algorithm (RULE33b):
  For each .groovy file under Drivers/Levoit/:
    For each method whose name starts with 'set' OR 'doSet':
      1. Extract the method body.
      2. Skip if no toLowerCase() call present (RULE33a covers those).
      3. Extract the sendEvent value identifier.
      4. If the identifier X is assigned via .toLowerCase() on its RHS (raw
         normalized, not canon-derived), check whether a truthy-coercion
         assignment of the form ``? "on" : "off"`` exists in the body for X
         or for any other identifier used in sendEvent.
      5. Binary on/off discriminator: require X appears in a binary on/off
         comparison (``== "on"``, ``== "off"``, or ``in ["on","off"]`` exactly)
         in the method body.  Mode/multi-state setters (setMode with
         ``m in ["auto","sleep"]``, setNightlightMode with
         ``m in ["on","off","dim"]``) fail this test and are excluded.
      6. If the method normalizes to lowercase, emits a raw normalized
         identifier (no truthy-canon ternary), passes the binary discriminator,
         AND has no strict enum-rejection gate → RULE33b FAIL.

    RULE33a skips methods with first param name 'v'/'sw'/'val' (short normalized
    aliases indicating upstream normalization has already occurred).  RULE33b
    does NOT apply this skip: RULE33b's job is detecting raw-normalized-var
    emission, not raw-parameter comparison; a method named doSetFoo(val) that
    emits `val` without a canon ternary would still be non-canonical.
    Both sub-checks skip exempted (file, method) pairs.

False-positive protection:
  - RULE33b skips methods where the sendEvent value is a string literal "on"/"off"
    (already canonical by construction).
  - RULE33b skips methods where the sendEvent value identifier is assigned via
    a ternary that produces "on"/"off" (already canonical).
  - RULE33b skips methods that use strict enum-rejection gates (the strict gate
    guarantees the normalized var is already canonical after the gate — these
    are correctly identified by the presence of an in ["on","off"] gate with no
    truthy variants in the rejection list).  This exempts LevoitFanLib's
    doSetDisplayScreenSwitch and doSetMuteSwitch (strict-enum fan-line
    intentional behavioral divergence from LevoitHumidifierLib).
  - RULE33b skips mode/multi-state setters via the binary discriminator: the
    emitted identifier must appear in a binary on/off comparison in the method
    body.  setMode, setNightLight (three-state), setNightlightMode (three-state)
    are correctly excluded because their identifiers only appear in multi-value
    mode-enum lists, not in on/off comparisons.
  - Library files are included (same as RULE33a).

Exemptions:
  Add ``bp25_case_sensitivity_exemptions`` list to lint_config.yaml:
    - file: Drivers/Levoit/SomeDriver.groovy
      method: setSomething
      rationale: "reason"
  Exemptions apply to both sub-checks.
"""

import re
from pathlib import Path
from lint_rules._helpers import make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# Regex to find 'set*' method definitions (RULE33a only).
# Captures the first parameter only.
# Matches both single-param (setDisplay(onOff)) and multi-param with defaults
# (setNightlight(onOff, brightness = null, colorTemp = null)).
# Group 1: method_name, Group 2: first_param_name
SET_METHOD_RE = re.compile(
    r'^\s*def\s+(set\w+)\s*\(\s*(\w+)\s*(?:[,=)])',
    re.MULTILINE,
)

# Regex for RULE33b: matches both 'set*' AND 'doSet*' method definitions.
# doSet* shared-helper coverage is required so the blessed canonical reference
# (LevoitHumidifierLib doSetDisplayScreenSwitch / doSetAutoStopSwitch) can be
# audited for regressions.  The _STRICT_GATE_RE exemption handles LevoitFanLib's
# strict-enum doSet* helpers (fan-line intentional behavioral divergence).
# Group 1: method_name, Group 2: first_param_name
SET_OR_DOSET_METHOD_RE = re.compile(
    r'^\s*def\s+((?:doSet|set)\w+)\s*\(\s*(\w+)\s*(?:[,=)])',
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


# Additional regexes for RULE33b.

# Identifies a sendEvent value identifier.
_SENDEVENT_VALUE_RE = re.compile(r'sendEvent\s*\([^)]*\bvalue\s*:\s*([^\s,)\]]+)')

# Truthy-coercion ternary: ? "on" : "off" (the canonical canon-derivation pattern).
_TRUTHY_TERNARY_RE = re.compile(r'\?\s*"on"\s*:\s*"off"')

# Strict enum-rejection gate that guarantees a raw-normalized var is already canonical:
# in ["on","off"] with no truthy-variant entries.
_STRICT_GATE_RE = re.compile(r'in\s*\[\s*"on"\s*,\s*"off"\s*\]')


def check_rule33b_raw_normalized_sendevent(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE33b: Fail if a set* or doSet* method normalizes with toLowerCase() but
    emits the raw normalized variable in sendEvent instead of a truthy-derived
    canonical value.

    The re-blessed canonical pattern derives the emitted value via a ternary:
        String canon = (v in [...]) ? "on" : "off"
    and emits: device.sendEvent(name:"attr", value: canon)

    Emitting the raw normalized variable `v` is non-canonical: truthy inputs
    "true"/"1"/"yes" would be stored as-is in the attribute instead of as "on".

    doSet* shared-helper methods are included so regressions in the blessed
    canonical reference (LevoitHumidifierLib doSetDisplayScreenSwitch /
    doSetAutoStopSwitch) are caught.  LevoitFanLib's strict-enum doSet* helpers
    are correctly excluded by the _STRICT_GATE_RE guard.
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    exemption_set = _build_exemption_set(config)

    for m in SET_OR_DOSET_METHOD_RE.finditer(raw_text):
        method_name = m.group(1)
        param_name = m.group(2)

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

        # Only applies to methods that already use toLowerCase() (RULE33a covers the rest).
        if not TO_LOWER_RE.search(body_clean):
            continue

        # Extract the sendEvent value identifier.
        se_match = _SENDEVENT_VALUE_RE.search(body_clean)
        if not se_match:
            continue

        se_val = se_match.group(1).strip()

        # Skip if emitting a string literal "on"/"off" directly — already canonical.
        if se_val in ('"on"', '"off"', "'on'", "'off'"):
            continue

        # Only inspect bare identifiers.
        if not re.match(r'^[a-zA-Z_]\w*$', se_val):
            continue

        # Check if the identifier is assigned via a truthy-coercion ternary (? "on" : "off").
        assign_re = re.compile(
            r'\b' + re.escape(se_val) + r'\s*=\s*([^\n;{]+)',
            re.IGNORECASE,
        )
        has_truthy_canon = False
        is_lowercase_assigned = False
        for am in assign_re.finditer(body_clean):
            rhs = am.group(1)
            if _TRUTHY_TERNARY_RE.search(rhs):
                has_truthy_canon = True
                break
            if '.toLowerCase()' in rhs or '.toLower()' in rhs:
                is_lowercase_assigned = True

        if has_truthy_canon:
            # Identifier is derived from a truthy-canon ternary — correct canonical pattern.
            continue

        if not is_lowercase_assigned:
            # Identifier is not a toLowerCase-normalized var at all — RULE33a domain.
            continue

        # The emitted identifier is a raw-normalized var (toLowerCase only, no canon ternary).
        # Check whether a strict enum-rejection gate is present; if so, the var is
        # already bounded to "on"/"off" by construction and emitting it is safe.
        if _STRICT_GATE_RE.search(body_clean):
            continue

        # Binary on/off discriminator: the emitted identifier must appear in a
        # binary on/off comparison (== "on", == "off", or in ["on","off"] exactly)
        # somewhere in the method body.  Mode/multi-state setters (setMode with
        # m in ["auto","sleep"], setNightLight with lvlStr in ["off","dim","bright"],
        # setNightlightMode with m in ["on","off","dim"]) do NOT satisfy this test
        # because their identifier only appears in multi-value or non-on/off lists.
        # Emitting a mode-enum value in sendEvent is correct; BP25 does not apply.
        binary_ctx_re = re.compile(
            r'\b' + re.escape(se_val) + r'\b'
            r'.*?(?:==\s*"on"|==\s*"off"|in\s*\[\s*"on"\s*,\s*"off"\s*\])',
            re.DOTALL,
        )
        if not binary_ctx_re.search(body_clean):
            continue

        # BP25 sub-check b: raw-normalized sendEvent without truthy-canon derivation.
        se_line = _line_of(raw_text, brace_start + se_match.start())

        findings.append(make_finding(
            severity='FAIL',
            rule_id='RULE33b_raw_normalized_sendevent',
            title=(
                f'BP25: {method_name}() emits raw normalized `{se_val}` in sendEvent '
                f'instead of a truthy-derived canonical value'
            ),
            file_rel=file_rel,
            lineno=se_line,
            raw_lines=raw_lines,
            why=(
                f'`{se_val}` is normalized via toLowerCase() but truthy inputs '
                f'"true"/"1"/"yes" would be stored as-is in the attribute instead '
                f'of as "on". The canonical pattern derives a truthy-coerced value: '
                f'`String canon = (v in ["on","true","1","yes"]) ? "on" : "off"` '
                f'and emits canon.'
            ),
            fix=(
                f'Add `String canon = ({se_val} in ["on","true","1","yes"]) ? "on" : "off"` '
                f'after the toLowerCase() normalization, then use `canon` in sendEvent '
                f'and payload coercion.'
            ),
        ))

    return findings


ALL_RULES = [check_rule33_case_sensitivity, check_rule33b_raw_normalized_sendevent]
