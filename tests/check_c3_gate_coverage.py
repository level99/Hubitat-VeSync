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

Scope: "boolean on/off setter" means a public method whose name starts with "set"
and whose suffix is one of: Display, ChildLock, LightDetection, AutoStop,
DryingMode, NightlightSwitch, NightLight, Nightlight.

Explicitly excluded from scope (not boolean on/off setters):
  - AutoMode       -- takes a mode-enum string ("efficient"/"quiet"/"default"), not on/off
  - PetMode        -- a wrapper delegator to setMode(); not a direct on/off boolean path
  - NightlightMode -- three-state enum setter ("on"/"off"/"dim"), not a boolean on/off toggle

Two shapes of method are skipped without requiring a gate or rationale:

  1. Line-count delegator stubs (<=3 non-empty lines, no hubBypass or
     sendBypassRequest call) -- the C3 gate lives in the callee.

  2. Single-expression delegators of the form
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

# Boolean on/off setter suffixes that this check covers.
# AutoMode, PetMode, and NightlightMode are intentionally excluded (see module docstring).
ON_OFF_SETTER_RE = re.compile(
    r"^(?:    )?def\s+"
    r"(set(?:Display|ChildLock|LightDetection|AutoStop|DryingMode"
    r"|NightlightSwitch|NightLight|Nightlight))\s*\(",
    re.MULTILINE,
)

# Any top-level method definition -- used to delimit method bodies.
# Capturing group 1 = method name.
ANY_METHOD_RE = re.compile(
    r"^(?:    )?def\s+(\w+)\s*\(",
    re.MULTILINE,
)

# Single-expression inline delegator: def name(...) { [return] someCall(...) }
# Matches when the entire body is on the def line inside braces, containing one
# call expression only (no semicolons, no hubBypass, no sendBypassRequest).
INLINE_DELEGATOR_RE = re.compile(
    r"^(?:    )?def\s+\w+\s*\([^)]*\)\s*\{\s*(?:return\s+)?\w[\w.]*\s*\([^)]*\)\s*\}",
)

C3_GATE_RE = re.compile(r"device\.currentValue\(")
C3_RATIONALE_RE = re.compile(r"No C3 idempotency gate:")


def extract_predef_comment(source: str, def_pos: int) -> str:
    """
    Return the contiguous comment block (// lines and blank lines) immediately
    above the def at def_pos.  Stops at the first non-comment, non-blank line
    scanning backwards from the line before def_pos, or at the start of source.
    The returned string is suitable for C3_RATIONALE_RE.search().
    """
    # Work with the source text before def_pos.
    preceding = source[:def_pos]
    lines = preceding.splitlines()

    collected: list[str] = []
    for line in reversed(lines):
        stripped = line.strip()
        if stripped == "":
            # Blank lines: continue scanning upward (allow one blank gap).
            continue
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
            collected.append(stripped)
        else:
            # Non-comment, non-blank line -- stop.
            break

    return "\n".join(collected)


def is_delegator(def_line: str, body: str) -> bool:
    """
    Return True if the method should be skipped as a pass-through delegator.

    Two shapes qualify:
    (a) The def line itself is a single-expression inline delegator
        (def X(args) { someCall(args) }) with no hubBypass/sendBypassRequest.
    (b) The extracted body (def line through next method start) has <=3
        non-empty lines and no hubBypass or sendBypassRequest call.
    """
    # Shape (a): inline single-expression delegator on the def line itself.
    if INLINE_DELEGATOR_RE.match(def_line.rstrip()):
        if "hubBypass(" not in def_line and "sendBypassRequest(" not in def_line:
            return True

    # Shape (b): short body without direct API calls (existing heuristic).
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
        lines = source.splitlines(keepends=True)

        # Collect on/off setter positions: {char_offset: method_name}
        on_off_by_pos: dict[int, str] = {
            m.start(): m.group(1) for m in ON_OFF_SETTER_RE.finditer(source)
        }
        if not on_off_by_pos:
            continue

        # Collect ALL method start positions (group 1 = method name).
        all_starts: list[tuple[int, str | None]] = [
            (m.start(), m.group(1)) for m in ANY_METHOD_RE.finditer(source)
        ]
        all_starts.append((len(source), None))  # sentinel

        for i, (pos, _) in enumerate(all_starts[:-1]):
            if pos not in on_off_by_pos:
                continue
            method_name = on_off_by_pos[pos]
            next_pos = all_starts[i + 1][0]
            body = source[pos:next_pos]

            # Extract just the def line (first line of body) for shape-(a) check.
            def_line = body.splitlines()[0] if body.splitlines() else ""

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
