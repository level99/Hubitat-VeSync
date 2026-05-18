#!/usr/bin/env python3
"""
C3 idempotency gate coverage check.

Enumerates every boolean on/off setter method in Drivers/Levoit/*.groovy and
classifies each as one of:

  HAS_GATE        -- body contains a C3 idempotency gate
                     (device.currentValue(...) == <var> guard before the API call)

  HAS_RATIONALE   -- body contains, or is immediately preceded by, a
                     'No C3 idempotency gate:' comment explaining why C3 is
                     intentionally absent. The pre-def comment block (contiguous
                     // lines or /* */ block directly above the def line, stopping
                     at the first non-comment/non-blank line or at a preceding
                     method boundary) is scanned in addition to the method body.

  NEITHER         -- neither a gate nor a rationale comment is present

Scope: a method is an in-scope boolean on/off setter under ONE of two shapes:

  Standard shape -- all three behavioral predicates hold simultaneously:

    (1) NORMALIZES the parameter against on/off semantics — the method body
        contains any of:
          == "on"  |  == "off"  |  in ["on", ...]  |  in ["on","off"]
          truthy coercion: in ["on","true","1","yes"]  |  (v == "on")
          .toLowerCase() call (universal convention for on/off normalization
          in this codebase; used with a subsequent in/== comparison nearby)

    (2) MAKES A DIRECT API CALL — the method body contains hubBypass( or
        parent.sendBypassRequest( or doSet*(

    (3) EMITS AN ON/OFF ATTRIBUTE — determined behaviorally:
        (a) the method body contains a sendEvent with a value that is the
            string literal "on" or "off", OR
        (b) the sendEvent value is an identifier X that is:
            (b1) assigned anywhere in the same method body via .toLowerCase()
                 on the RHS of the assignment, AND
            (b2) also appears in an on/off comparison in the body:
                 in ["on", ...] or == "on" or == "off".
            Both conditions are required to distinguish on/off-normalized
            identifiers from mode-enum-normalized identifiers (e.g. `m` in
            `setMode` with `m in ["auto","sleep"]` satisfies b1 but not b2).

  Split-gate shape -- the method holds the C3 gate and delegates to a same-file
    handle*/doSet* callee for the actual API call and sendEvent. Example:

        String v = (arg as String).trim().toLowerCase()
        if (device.currentValue("attr") == v) return   // <-- gate-holder
        handleDisplayOn(v)   // <-- API + sendEvent in callee

    Three conditions together identify this shape:
      (1) Body normalizes against on/off semantics (predicate-1 signal).
      (2) Body contains a delegate call to a handle*/doSet* name (not self).
      (3) The resolved callee exists in the SAME source file AND is itself
          on/off-semantic: its body makes an API call (hubBypass or
          sendBypassRequest) AND contains an on/off comparison (== "on",
          == "off", in ["on",...]) anywhere in its body.

    Gate PRESENCE is intentionally NOT a scope condition. Requiring the gate
    for scope would make the regression guard self-referential and vacuous:
    a method that loses its gate would drop out of scope instead of being
    flagged NEITHER. Scope is gate-independent; classify() decides gate presence.

    The "same-file" constraint is load-bearing: setSpeed/setMode in Core per-
    driver files call handleSpeed/handleMode defined in LevoitCorePurifierLib,
    a different file. Those callees are not found in the per-driver file's
    method index, so the split-gate scope correctly excludes them. Only
    setDisplay in LevoitCorePurifierLib.groovy delegates to handleDisplayOn
    in the same file, and handleDisplayOn's body contains (displayOn == "on"),
    making it on/off-semantic.

    The "not self" constraint prevents a method from treating an appearance of
    its own name inside a string literal (e.g., logDebug "handleDisplayOn()")
    as a recursive self-delegation.

Explicitly excluded from scope regardless of behavioral predicate match:
  - setAutoMode  -- takes a mode-enum string, not on/off; sendEvent emits
                    "autoPreference", not an on/off attribute. Predicates
                    naturally fail, but the exclusion is kept for clarity.
  - setPetMode   -- a short delegator to setMode(); makes no direct API call
                    and has no sendEvent. Predicates naturally fail. Kept as
                    documentation of the intentional non-scope.

Nightlight-family methods (setNightlightMode, setNightLight, setNightlight,
setNightlightSwitch): match predicates 1-3 (normalize, API call, emit on/off)
and each has a 'No C3 idempotency gate:' rationale comment. Correctly
classified HAS_RATIONALE; NOT in EXCLUDED_METHOD_NAMES.

Two shapes of method are skipped without requiring a gate or rationale:

  1. Line-count delegator stubs (<=3 non-empty lines, no hubBypass or
     sendBypassRequest call) — the C3 gate lives in the callee.

  2. Single-expression inline delegators of the form
         def setX(args) { someDelegate(args) }
     i.e. the def line's inline body is a single call expression
     (optionally return-prefixed), with no hubBypass or sendBypassRequest.
     These are pass-through wrappers; the terminal command method that actually
     calls the API is what this script audits.

Exit codes:
  0 -- every in-scope on/off setter has a C3 gate or documented rationale (empty output)
  1 -- one or more NEITHER sites found (sites listed to stdout)

Usage:
    uv run --python 3.12 tests/check_c3_gate_coverage.py
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DRIVERS_DIR = REPO_ROOT / "Drivers" / "Levoit"

# Explicitly excluded method names regardless of behavioral predicate match.
# See docstring above for per-method behavioral rationale.
EXCLUDED_METHOD_NAMES = {"setAutoMode", "setPetMode"}

# Any top-level method definition -- used to delimit method bodies.
ANY_METHOD_RE = re.compile(
    r"^(?:    )?def\s+(\w+)\s*\(",
    re.MULTILINE,
)

# Single-expression inline delegator: def name(...) { [return] someCall(...) }
INLINE_DELEGATOR_RE = re.compile(
    r"^(?:    )?def\s+\w+\s*\([^)]*\)\s*\{\s*(?:return\s+)?\w[\w.]*\s*\([^)]*\)\s*\}",
)

C3_GATE_RE = re.compile(r"device\.currentValue\(")
C3_RATIONALE_RE = re.compile(r"No C3 idempotency gate:")

# ---- Behavioral predicates ----

# Predicate 1: body normalizes a parameter against on/off semantics.
# Matches: == "on", == "off", in ["on",...], truthy coercion patterns,
# and .toLowerCase() (the universal normalization call for on/off setters).
_NORMALIZE_RE = re.compile(
    r'==\s*"on"|==\s*"off"'
    r'|in\s*\["on"'
    r'|in\s*\["on","off"\]'
    r'|\(v\s*(in|==)'
    r'|\.toLowerCase\(\)',
    re.IGNORECASE,
)

# Predicate 2: body makes a direct API call.
# Split-gate callers (whose API call is in a callee) are handled separately
# by _is_split_gate_onoff_setter below.
_API_CALL_RE = re.compile(
    r"hubBypass\(|parent\.sendBypassRequest\(|doSet\w+\("
)

# Used by split-gate detection: method delegates to handle*/doSet* callee.
_DELEGATE_CALL_RE = re.compile(r"\bhandle\w+\s*\(|\bdoSet\w+\s*\(")

# On/off comparison present in a callee body — used by split-gate callee resolution.
# A callee is on/off-semantic when its body contains this marker (meaning it
# works with boolean on/off values, not mode-enum or speed-level values).
_ONOFF_COMPARISON_RE = re.compile(
    r'==\s*"on"|==\s*"off"'
    r'|in\s*\["on"',
    re.IGNORECASE,
)

# Direct API call — used in callee-semantic check (same as standard predicate-2
# but without doSet* to avoid recursive split-gate chaining).
_DIRECT_API_CALL_RE = re.compile(
    r"hubBypass\(|parent\.sendBypassRequest\("
)


def _find_struct_braces(source: str, start: int) -> list[tuple[int, str]]:
    """
    Scan from `start` in source and yield (position, '{' or '}') for every
    brace that is NOT inside a string literal or comment.

    Handled non-structural regions:
      - // line comments (to end of line)
      - /* */ block comments
      - triple-quoted strings  \"\"\"...\"\"\"
      - double-quoted strings  \"...\" (GString ${...} braces counted separately
        would add complexity; we skip the entire double-quoted span including
        any embedded ${...} since they are not method-scope braces)
      - single-quoted strings  '...'
    """
    results: list[tuple[int, str]] = []
    i = start
    n = len(source)
    while i < n:
        c = source[i]
        # Line comment
        if c == '/' and i + 1 < n and source[i + 1] == '/':
            i = source.find('\n', i)
            if i < 0:
                break
            i += 1
            continue
        # Block comment
        if c == '/' and i + 1 < n and source[i + 1] == '*':
            end = source.find('*/', i + 2)
            if end < 0:
                break
            i = end + 2
            continue
        # Triple-quoted string
        if c == '"' and source[i:i+3] == '"""':
            end = source.find('"""', i + 3)
            if end < 0:
                break
            i = end + 3
            continue
        # Double-quoted string (GString) — skip whole span including ${...}
        if c == '"':
            i += 1
            depth_gstring = 0
            while i < n:
                ch = source[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == '"' and depth_gstring == 0:
                    i += 1
                    break
                if ch == '$' and i + 1 < n and source[i + 1] == '{':
                    depth_gstring += 1
                    i += 2
                    continue
                if ch == '}' and depth_gstring > 0:
                    depth_gstring -= 1
                    i += 1
                    continue
                i += 1
            continue
        # Single-quoted string
        if c == "'":
            i += 1
            while i < n:
                ch = source[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == "'":
                    i += 1
                    break
                i += 1
            continue
        # Structural brace
        if c == '{':
            results.append((i, '{'))
        elif c == '}':
            results.append((i, '}'))
        i += 1
    return results


def extract_method_body(source: str, def_pos: int) -> str:
    """
    Extract a method body by brace-depth matching from the opening brace of
    the def at def_pos. Skips braces inside string literals and comments.
    Returns the text from def_pos through the method's closing brace (inclusive).
    Falls back to the next-def boundary on error.
    """
    try:
        brace_start = source.index("{", def_pos)
    except ValueError:
        return source[def_pos:]

    braces = _find_struct_braces(source, brace_start)
    depth = 0
    for pos, kind in braces:
        if kind == '{':
            depth += 1
        else:
            depth -= 1
            if depth == 0:
                return source[def_pos : pos + 1]
    return source[def_pos:]


def _extract_sendEvent_value(body: str) -> str | None:
    """
    Return the value expression from the first sendEvent(... value: X ...) in body.
    Scans for 'value:' inside any sendEvent call. Returns the raw token as a string,
    or None if no sendEvent with a value argument is found.
    """
    for m in re.finditer(r'sendEvent\s*\(', body):
        start = m.end() - 1  # position of opening (
        depth = 0
        j = start
        while j < len(body):
            c = body[j]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    args = body[start + 1 : j]
                    vm = re.search(r'\bvalue\s*:\s*([^\s,)\]]+)', args)
                    if vm:
                        return vm.group(1).strip()
                    break
            j += 1
    return None


def _is_onoff_emitting(body: str) -> bool:
    """
    Return True when the method body emits an on/off-valued attribute via sendEvent,
    determined behaviorally without enumerating variable names.

    Two recognized shapes:

    (a) sendEvent value is the string literal "on" or "off" directly.

    (b) sendEvent value is an identifier X that is:
        (b1) assigned anywhere in the method body via .toLowerCase() on the
             RHS of the assignment, AND
        (b2) also appears in an on/off comparison in the method body:
             in ["on", ...] or == "on" or == "off".
        Both conditions are required to distinguish on/off-normalized identifiers
        (e.g. `s` in `doSetMuteSwitch` with `s in ["on","off"]`) from
        mode-enum-normalized identifiers (e.g. `m` in `setMode` with
        `m in ["auto","sleep"]`) — the latter do not satisfy (b2).
    """
    val = _extract_sendEvent_value(body)
    if val is None:
        return False

    # (a) Direct on/off string literal
    if val in ('"on"', '"off"', "'on'", "'off'"):
        return True

    # (b) Identifier: trace assignment to on/off normalization AND verify on/off context.
    # Match bare identifiers (Groovy variable names) only.
    if re.match(r'^[a-zA-Z_]\w*$', val):
        assign_re = re.compile(
            r'\b' + re.escape(val) + r'\s*=\s*([^\n;{]+)',
            re.IGNORECASE,
        )
        for am in assign_re.finditer(body):
            rhs = am.group(1)
            if '.toLowerCase()' in rhs or '.toLower()' in rhs:
                # Verify the identifier appears in an on/off comparison in the body.
                onoff_context_re = re.compile(
                    r'\b' + re.escape(val) + r'\b'
                    r'.*?(?:==\s*"on"|==\s*"off"|in\s*\["on")',
                    re.DOTALL,
                )
                if onoff_context_re.search(body):
                    return True

    return False


def _resolve_delegate_callee_body(
    caller_name: str, caller_body: str, source: str
) -> str | None:
    """
    Resolve the first same-file handle*/doSet* callee named in caller_body.

    Scans caller_body for the first match of _DELEGATE_CALL_RE, extracts the
    callee name, and looks it up in source using ANY_METHOD_RE + extract_method_body.
    Returns the callee's body string, or None if no callee is found.

    Two guards prevent false positives:
      - Self-reference: if the extracted callee name equals caller_name, the
        match is a method name appearing inside a string literal (e.g.,
        logDebug "handleDisplayOn()") — skip it and try subsequent matches.
      - Not-defined: if the callee name has no matching def in source, skip it.

    Only the first resolvable, non-self callee is returned. The split-gate
    shape in this codebase always has exactly one meaningful callee.
    """
    all_method_positions: dict[str, int] = {
        m.group(1): m.start() for m in ANY_METHOD_RE.finditer(source)
    }
    for dm in _DELEGATE_CALL_RE.finditer(caller_body):
        # Extract callee name: text before the '(' in the match.
        raw = dm.group(0)
        callee_name = raw[:raw.index("(")].strip()
        if callee_name == caller_name:
            continue
        if callee_name not in all_method_positions:
            continue
        return extract_method_body(source, all_method_positions[callee_name])
    return None


def _is_callee_onoff_semantic(callee_body: str) -> bool:
    """
    Return True when callee_body is on/off-semantic: it makes a direct API
    call AND contains an on/off comparison anywhere in its body.

    This distinguishes on/off terminal helpers (e.g., handleDisplayOn which
    tests `displayOn == "on"`) from speed/mode helpers (e.g., handleSpeed,
    handleMode which have no on/off comparison in their bodies).
    """
    return (
        bool(_DIRECT_API_CALL_RE.search(callee_body))
        and bool(_ONOFF_COMPARISON_RE.search(callee_body))
    )


def _is_split_gate_onoff_setter(
    method_name: str, body: str, source: str
) -> bool:
    """
    Recognize the split-gate shape: a method that holds the C3 idempotency gate
    and delegates to a same-file handle*/doSet* callee for the API call and
    sendEvent.

    Example (LevoitCorePurifierLib.setDisplay):
        String v = (arg as String).trim().toLowerCase()
        if (device.currentValue("attr") == v) return
        handleDisplayOn(v)   // <- API + sendEvent in callee

    Three conditions together identify this shape:
      (1) Body normalizes against on/off semantics (_NORMALIZE_RE).
      (2) Body contains a delegate call to a handle*/doSet* name that is NOT
          the current method (non-self) and IS defined in the same source file.
      (3) The resolved callee body is on/off-semantic: it makes a direct API
          call AND contains an on/off comparison.

    The on/off-semantic callee test (condition 3) discriminates against
    speed/mode callees: handleSpeed and handleMode make API calls but contain
    no on/off comparison — they process speed levels and mode enums, not
    boolean on/off values. handleDisplayOn contains (displayOn == "on"), making
    it on/off-semantic.

    Gate PRESENCE is intentionally NOT a scope condition. Requiring the gate
    for scope would make the regression guard self-referential: a method that
    loses its gate would drop out of scope instead of being flagged NEITHER.
    """
    if not bool(_NORMALIZE_RE.search(body)):
        return False
    callee_body = _resolve_delegate_callee_body(method_name, body, source)
    if callee_body is None:
        return False
    return _is_callee_onoff_semantic(callee_body)


def is_behavioral_onoff_setter(
    method_name: str, body: str, source: str
) -> bool:
    """
    Return True when the method is an in-scope boolean on/off setter.

    Two shapes recognized:

    Standard shape: all three behavioral predicates hold:
      (1) body normalizes against on/off semantics
      (2) body makes a direct API call (hubBypass/sendBypassRequest/doSet*)
      (3) body emits an on/off attribute via sendEvent (behavioral, not name-based)

    Split-gate shape: the method holds the C3 gate and delegates to a same-file
      handle*/doSet* callee that is on/off-semantic (callee makes an API call
      AND contains an on/off comparison). See _is_split_gate_onoff_setter.
      The callee-semantic test ensures speed/mode delegating methods (setSpeed
      -> handleSpeed, setMode -> handleMode) are not incorrectly included.

    Also excludes methods by name that are intentionally not on/off setters
    (see EXCLUDED_METHOD_NAMES and module docstring).
    """
    if method_name in EXCLUDED_METHOD_NAMES:
        return False
    # Standard three-predicate path
    if (bool(_NORMALIZE_RE.search(body))
            and bool(_API_CALL_RE.search(body))
            and _is_onoff_emitting(body)):
        return True
    # Split-gate path: gate-holder delegates to an on/off-semantic same-file callee
    if _is_split_gate_onoff_setter(method_name, body, source):
        return True
    return False


def extract_predef_comment(source: str, def_pos: int) -> str:
    """
    Return the contiguous comment block (// lines and blank lines) immediately
    above the def at def_pos. Stops at the first non-comment, non-blank line
    scanning backwards from the line before def_pos, or at the start of source.
    The returned string is suitable for C3_RATIONALE_RE.search().
    """
    preceding = source[:def_pos]
    lines = preceding.splitlines()

    collected: list[str] = []
    for line in reversed(lines):
        stripped = line.strip()
        if stripped == "":
            continue
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            collected.append(stripped)
        else:
            break

    return "\n".join(collected)


def is_delegator(def_line: str, body: str) -> bool:
    """
    Return True if the method should be skipped as a pass-through delegator.

    Two shapes qualify:
    (a) The def line itself is a single-expression inline delegator
        (def X(args) { someCall(args) }) with no hubBypass/sendBypassRequest.
    (b) The extracted body (def line through closing brace) has <=3
        non-empty lines and no hubBypass or sendBypassRequest call.
    """
    if INLINE_DELEGATOR_RE.match(def_line.rstrip()):
        if "hubBypass(" not in def_line and "sendBypassRequest(" not in def_line:
            return True

    non_empty = [ln.strip() for ln in body.splitlines() if ln.strip()]
    if len(non_empty) <= 3:
        if "hubBypass(" not in body and "sendBypassRequest(" not in body:
            return True

    return False


def classify(source: str, def_pos: int, body: str) -> str:
    """
    Classify an on/off setter as HAS_GATE, HAS_RATIONALE, or NEITHER.

    Searches both the method body AND the contiguous comment block immediately
    above the def line.
    """
    if C3_GATE_RE.search(body):
        return "HAS_GATE"
    if C3_RATIONALE_RE.search(body):
        return "HAS_RATIONALE"
    predef = extract_predef_comment(source, def_pos)
    if C3_RATIONALE_RE.search(predef):
        return "HAS_RATIONALE"
    return "NEITHER"


def main() -> int:
    neither_sites: list[str] = []

    for driver_file in sorted(DRIVERS_DIR.glob("*.groovy")):
        source = driver_file.read_text(encoding="utf-8")

        all_starts: list[tuple[int, str]] = [
            (m.start(), m.group(1)) for m in ANY_METHOD_RE.finditer(source)
        ]
        all_starts.append((len(source), None))  # sentinel

        for i, (pos, method_name) in enumerate(all_starts[:-1]):
            if method_name is None:
                continue

            # Brace-depth bounded body (B3 fix: use true method boundary).
            body = extract_method_body(source, pos)

            # Extract just the def line for delegator shape-(a) check.
            def_line = body.splitlines()[0] if body.splitlines() else ""

            # Apply behavioral predicates to determine in-scope status.
            if not is_behavioral_onoff_setter(method_name, body, source):
                continue

            if is_delegator(def_line, body):
                continue

            if classify(source, pos, body) == "NEITHER":
                neither_sites.append(f"{driver_file.name}:{method_name}")

    if neither_sites:
        print("ON/OFF SETTERS WITH NEITHER C3 GATE NOR RATIONALE COMMENT:")
        for site in neither_sites:
            print(f"  {site}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
