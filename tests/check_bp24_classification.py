#!/usr/bin/env python3
"""
BP24 NO-ON classification coverage check.

Enumerates every boolean on/off setter in Drivers/Levoit/*.groovy that is a
NO-ON site (configures a device preference; powering on is NOT implied) and
verifies that each such site carries a BP24 classification comment.

A NO-ON site is behaviorally identified by ALL of the following:

  (1) The method is a boolean on/off setter — three recognized shapes:

      Standard shape: normalizes against on/off semantics, makes a direct API
      call or delegates to a same-file doSet* callee, and emits an on/off
      attribute via sendEvent.

      Non-emitting API setter shape: normalizes against on/off semantics, makes
      a direct API call, but deliberately emits NO attribute (the attribute
      reflects hardware runtime state polled separately, not the user preference
      that this method writes).  Sole present codebase member: LevoitSuperior6000S
      setDryingMode.  This shape is enumerated by two behavioral discriminators:
      (a) a `? 1 : 0` binary-coercion ternary must be present (excludes mode/speed
      setters); (b) the body must NOT call safeIntArg() (excludes duration/level
      commands like setTimer whose on/off handling is an incidental secondary-param
      validation, not the method's governing decision).  Both are behavior-derived
      so future non-emitting preference setters are automatically covered.

      Inline delegator shape: a 1-line pass-through stub delegating to a shared
      doSet* helper that is itself on/off-semantic.

  (2) The method body (and its callee, for split-gate delegators) does NOT call
      ensureSwitchOn() — auto-on is NOT implied by this setter.

  (3) The method body is not a line-count delegator stub (<=3 non-empty lines,
      no direct API call) UNLESS it delegates to a doSet* shared helper whose
      body also has no ensureSwitchOn().

A site PASSES when the method def line, or the contiguous comment block immediately
above the def line, contains the marker:

    BP24: NO-ON

Exit codes:
  0 -- every NO-ON site has the classification comment
  1 -- one or more NO-ON sites are missing the comment

Usage:
    uv run --python 3.12 tests/check_bp24_classification.py
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DRIVERS_DIR = REPO_ROOT / "Drivers" / "Levoit"

# Methods excluded from scope regardless of behavioral match.
EXCLUDED_METHOD_NAMES = {"setAutoMode", "setPetMode"}

ANY_METHOD_RE = re.compile(r"^(?:    )?def\s+(\w+)\s*\(", re.MULTILINE)

INLINE_DELEGATOR_RE = re.compile(
    r"^(?:    )?def\s+\w+\s*\([^)]*\)\s*\{\s*(?:return\s+)?\w[\w.]*\s*\([^)]*\)\s*\}",
)

_NORMALIZE_RE = re.compile(
    r'==\s*"on"|==\s*"off"'
    r'|in\s*\["on"'
    r'|in\s*\["on","off"\]'
    r'|\(v\s*(in|==)'
    r'|\.toLowerCase\(\)',
    re.IGNORECASE,
)

_API_CALL_RE = re.compile(
    r"hubBypass\(|parent\.sendBypassRequest\(|doSet\w+\("
)

_DELEGATE_CALL_RE = re.compile(r"\bhandle\w+\s*\(|\bdoSet\w+\s*\(")

# Targets inline delegators whose callee is a doSet* shared-lib helper.
# Used to catch cross-file delegator stubs that the primary predicate misses because
# they have no normalize pattern or sendEvent in their own body.
_DOSET_CALLEE_RE = re.compile(r"\bdoSet\w+\s*\(")

_ONOFF_COMPARISON_RE = re.compile(
    r'==\s*"on"|==\s*"off"'
    r'|in\s*\[\s*"on"\s*,\s*"off"\s*\]',
    re.IGNORECASE,
)

# Binary integer-coercion discriminator for the non-emitting API setter shape.
# A binary on/off setter coerces its on/off decision into a 0 or 1 integer for
# the API payload: `? 1 : 0` or `? 0 : 1` (order depends on convention).
# Mode/speed setters map to enums or level integers, not to a binary 0/1 ternary.
_BINARY_COERCE_RE = re.compile(r'\?\s*1\s*:\s*0|\?\s*0\s*:\s*1')

_DIRECT_API_CALL_RE = re.compile(
    r"hubBypass\(|parent\.sendBypassRequest\("
)

ENSURE_SWITCH_ON_RE = re.compile(r"\bensureSwitchOn\s*\(")

BP24_NO_ON_RE = re.compile(r"BP24:\s*NO-ON")


def _find_struct_braces(source: str, start: int) -> list:
    results = []
    i = start
    n = len(source)
    while i < n:
        c = source[i]
        if c == '/' and i + 1 < n and source[i + 1] == '/':
            i = source.find('\n', i)
            if i < 0:
                break
            i += 1
            continue
        if c == '/' and i + 1 < n and source[i + 1] == '*':
            end = source.find('*/', i + 2)
            if end < 0:
                break
            i = end + 2
            continue
        if c == '"' and source[i:i+3] == '"""':
            end = source.find('"""', i + 3)
            if end < 0:
                break
            i = end + 3
            continue
        if c == '"':
            i += 1
            depth_gs = 0
            while i < n:
                ch = source[i]
                if ch == '\\':
                    i += 2
                    continue
                if ch == '"' and depth_gs == 0:
                    i += 1
                    break
                if ch == '$' and i + 1 < n and source[i + 1] == '{':
                    depth_gs += 1
                    i += 2
                    continue
                if ch == '}' and depth_gs > 0:
                    depth_gs -= 1
                    i += 1
                    continue
                i += 1
            continue
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
        if c == '{':
            results.append((i, '{'))
        elif c == '}':
            results.append((i, '}'))
        i += 1
    return results


def extract_method_body(source: str, def_pos: int) -> str:
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


def _extract_sendEvent_value(body: str):
    for m in re.finditer(r'sendEvent\s*\(', body):
        start = m.end() - 1
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
    val = _extract_sendEvent_value(body)
    if val is None:
        return False
    if val in ('"on"', '"off"', "'on'", "'off'"):
        return True
    if re.match(r'^[a-zA-Z_]\w*$', val):
        assign_re = re.compile(
            r'\b' + re.escape(val) + r'\s*=\s*([^\n;{]+)',
            re.IGNORECASE,
        )
        for am in assign_re.finditer(body):
            rhs = am.group(1)
            if '.toLowerCase()' in rhs or '.toLower()' in rhs:
                # b2: BINARY on/off comparison only — in ["on","off"] exactly,
                # or == "on" / == "off".  Multi-state lists like ["on","off","dim"]
                # do NOT satisfy b2, excluding mode/multi-state-enum setters.
                onoff_ctx = re.compile(
                    r'\b' + re.escape(val) + r'\b'
                    r'.*?(?:==\s*"on"|==\s*"off"|in\s*\[\s*"on"\s*,\s*"off"\s*\])',
                    re.DOTALL,
                )
                if onoff_ctx.search(body):
                    return True
            # Canonical truthy-ternary pattern: X = (... ? "on" : "off").
            if re.search(r'\?\s*"on"\s*:\s*"off"', rhs):
                return True
    return False


def _resolve_delegate_callee_body(caller_name: str, caller_body: str, source: str):
    all_positions = {m.group(1): m.start() for m in ANY_METHOD_RE.finditer(source)}
    for dm in _DELEGATE_CALL_RE.finditer(caller_body):
        raw = dm.group(0)
        callee_name = raw[:raw.index("(")].strip()
        if callee_name == caller_name:
            continue
        if callee_name not in all_positions:
            continue
        return extract_method_body(source, all_positions[callee_name])
    return None


def _resolve_cross_file_doset_callee(caller_body: str) -> str | None:
    """
    Resolve the first doSet* callee named in caller_body by searching all
    .groovy files in DRIVERS_DIR.  Returns the callee method body, or None
    if not found in any file.
    """
    for dm in _DOSET_CALLEE_RE.finditer(caller_body):
        raw = dm.group(0)
        callee_name = raw[:raw.index("(")].strip()
        for lib_file in DRIVERS_DIR.glob("*.groovy"):
            lib_source = lib_file.read_text(encoding="utf-8")
            positions = {m.group(1): m.start() for m in ANY_METHOD_RE.finditer(lib_source)}
            if callee_name in positions:
                return extract_method_body(lib_source, positions[callee_name])
    return None


def _is_callee_onoff_semantic(callee_body: str) -> bool:
    return (
        bool(_DIRECT_API_CALL_RE.search(callee_body))
        and bool(_ONOFF_COMPARISON_RE.search(callee_body))
    )


def is_behavioral_onoff_setter(method_name: str, body: str, source: str) -> bool:
    if method_name in EXCLUDED_METHOD_NAMES:
        return False
    # Standard shape: normalizes + API call + emits on/off attribute.
    if (bool(_NORMALIZE_RE.search(body))
            and bool(_API_CALL_RE.search(body))
            and _is_onoff_emitting(body)):
        return True
    # Non-emitting API setter shape: normalizes + direct API call but no sendEvent.
    # Captures binary on/off preference setters whose attribute reflects hardware
    # runtime state polled separately (e.g. setDryingMode whose dryingMode attribute
    # is poll-populated).  Three discriminators together select the genuine members:
    #   (1) Binary-coercion (_BINARY_COERCE_RE): requires `? 1 : 0` or `? 0 : 1`
    #       in the body — excludes mode/speed setters that map to enum strings or
    #       multi-level integers.
    #   (2) No numeric-primary-arg coercion: excludes methods that coerce their
    #       primary (first) parameter via safeIntArg() — that signature identifies
    #       duration/level/numeric commands (setTimer, setMistLevel, etc.) whose
    #       on/off handling is an incidental secondary-param validation, not the
    #       method's governing decision.  Binary on/off preference setters take only
    #       an on/off argument and therefore never call safeIntArg().
    if (bool(_NORMALIZE_RE.search(body))
            and bool(_DIRECT_API_CALL_RE.search(body))
            and not _is_onoff_emitting(body)
            and "safeIntArg(" not in body):
        if bool(_BINARY_COERCE_RE.search(body)):
            return True
    # Split-gate delegator: gate-holder with on/off-semantic callee in same file.
    if bool(_NORMALIZE_RE.search(body)):
        callee_body = _resolve_delegate_callee_body(method_name, body, source)
        if callee_body is not None and _is_callee_onoff_semantic(callee_body):
            return True
    # Cross-file delegator stub: inline 1-liner calling a doSet* shared-lib helper.
    # These stubs have no normalize pattern or sendEvent of their own, but they are
    # the public setter surface for preference-toggles implemented in lib files.
    # Gate: the resolved doSet* callee must be on/off-semantic (makes an API call
    # AND contains a binary on/off comparison) — same test as the split-gate path.
    # This prevents level/mist delegators (def setMistLevel(l) { doSetMistLevel(l) })
    # from being misclassified as binary on/off setters.
    def_line = body.splitlines()[0].rstrip() if body.splitlines() else ""
    if (INLINE_DELEGATOR_RE.match(def_line)
            and bool(_DOSET_CALLEE_RE.search(body))
            and method_name.startswith("set")):
        callee_body = _resolve_cross_file_doset_callee(body)
        if callee_body is not None and _is_callee_onoff_semantic(callee_body):
            return True
    return False


def _has_ensure_switch_on(body: str, callee_body) -> bool:
    if ENSURE_SWITCH_ON_RE.search(body):
        return True
    if callee_body and ENSURE_SWITCH_ON_RE.search(callee_body):
        return True
    return False


def is_delegator(def_line: str, body: str) -> bool:
    if INLINE_DELEGATOR_RE.match(def_line.rstrip()):
        if "hubBypass(" not in def_line and "sendBypassRequest(" not in def_line:
            return True
    non_empty = [ln.strip() for ln in body.splitlines() if ln.strip()]
    if len(non_empty) <= 3:
        if "hubBypass(" not in body and "sendBypassRequest(" not in body:
            return True
    return False


def extract_predef_comment(source: str, def_pos: int) -> str:
    preceding = source[:def_pos]
    lines = preceding.splitlines()
    collected = []
    for line in reversed(lines):
        stripped = line.strip()
        if stripped == "":
            continue
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            collected.append(stripped)
        else:
            break
    return "\n".join(collected)


def has_bp24_no_on_comment(source: str, def_pos: int, body: str) -> bool:
    predef = extract_predef_comment(source, def_pos)
    return bool(BP24_NO_ON_RE.search(predef)) or bool(BP24_NO_ON_RE.search(body))


def main() -> int:
    missing_sites: list[str] = []

    for driver_file in sorted(DRIVERS_DIR.glob("*.groovy")):
        source = driver_file.read_text(encoding="utf-8")

        all_starts = [
            (m.start(), m.group(1)) for m in ANY_METHOD_RE.finditer(source)
        ]
        all_starts.append((len(source), None))

        for i, (pos, method_name) in enumerate(all_starts[:-1]):
            if method_name is None:
                continue

            body = extract_method_body(source, pos)
            def_line = body.splitlines()[0] if body.splitlines() else ""

            # Must be an in-scope on/off setter.
            if not is_behavioral_onoff_setter(method_name, body, source):
                continue

            # Resolve callee body for ensureSwitchOn check on delegators.
            callee_body = _resolve_delegate_callee_body(method_name, body, source)

            # SHOULD-ON sites (have ensureSwitchOn) are exempt — classification is SHOULD-ON.
            if _has_ensure_switch_on(body, callee_body):
                continue

            # Delegator stubs that call a shared doSet* helper also need the comment
            # (the comment on the delegator is the classification marker for callers).
            # The is_delegator() short-circuit used by check_c3_gate_coverage.py is NOT
            # applied here — BP24 classification applies to delegators too.

            # Check for BP24: NO-ON comment.
            if not has_bp24_no_on_comment(source, pos, body):
                missing_sites.append(f"{driver_file.name}:{method_name}")

    if missing_sites:
        print("NO-ON SETTERS MISSING BP24 CLASSIFICATION COMMENT:")
        for site in missing_sites:
            print(f"  {site}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
