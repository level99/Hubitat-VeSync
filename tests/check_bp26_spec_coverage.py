#!/usr/bin/env python3
"""
BP26 safeIntArg spec coverage check.

Enumerates every command method in Drivers/Levoit/*.groovy whose body calls
safeIntArg() on a command parameter, then verifies that the corresponding
*Spec.groovy file in src/test/groovy/drivers/ contains a BP26 coercion-throw
regression spec for that method.

A "BP26 spec" is defined as a test method whose name contains both "BP26"
and the command method name (case-insensitive).

Exits 0 and produces no output when all safeIntArg-using command methods have
coverage.  Exits 1 and lists uncovered sites otherwise.

Usage:
    uv run --python 3.12 tests/check_bp26_spec_coverage.py
"""

import re
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
REPO_ROOT = Path(__file__).resolve().parent.parent
DRIVERS_DIR = REPO_ROOT / "Drivers" / "Levoit"
SPEC_DIR = REPO_ROOT / "src" / "test" / "groovy" / "drivers"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def driver_name_to_spec_file(driver_file: Path) -> Path:
    """
    Map a driver filename to its Spock spec file.
    e.g. LevoitClassic200S.groovy -> LevoitClassic200SSpec.groovy
         LevoitCore200S Light.groovy -> LevoitCore200SLightSpec.groovy (spaces stripped)
    """
    stem = driver_file.stem.replace(" ", "")
    return SPEC_DIR / f"{stem}Spec.groovy"


def find_safeintarg_command_methods(driver_source: str) -> list[str]:
    """
    Return names of top-level command methods (def <name>(...)) whose bodies
    contain a safeIntArg( call.

    Strategy: split source into method blocks by scanning for top-level
    'def <name>(' tokens, then check each block for safeIntArg.
    """
    # Find all top-level method definitions.
    # A top-level def has no leading whitespace (or only class-level indentation
    # consistent with the Hubitat driver flat structure).
    # Pattern: start-of-line, optional spaces <=4, 'def ', then method name.
    method_pattern = re.compile(r"^(?:    )?def\s+(\w+)\s*\(", re.MULTILINE)

    results = []
    lines = driver_source.splitlines(keepends=True)
    full = "".join(lines)

    # Find all method start positions and names.
    method_starts = [(m.start(), m.group(1)) for m in method_pattern.finditer(full)]
    method_starts.append((len(full), None))  # sentinel

    for i, (start, name) in enumerate(method_starts[:-1]):
        end = method_starts[i + 1][0]
        body = full[start:end]

        # Skip private/lifecycle/applyStatus methods that aren't user commands.
        # We only care about public command methods that users can call.
        # Filter out well-known non-command helpers.
        skip_names = {
            "installed", "updated", "initialize", "refresh", "logDebugOff",
            "ensureDebugWatchdog", "applyStatus", "on", "off", "toggle",
            "hubBypass", "httpOk", "sanitize", "logInfo", "logDebug",
            "logError", "logWarn", "recordError", "captureDiagnostics",
            "ensureSwitchOn", "requireNotNull", "safeIntArg",
            "setupPollSchedule", "ensurePollWatchdog",
            "doSetDisplayScreenSwitch", "doSetAutoStopSwitch",
            "getDevices", "updateDevices", "sendBypassRequest",
            "isNightlightVariant",
        }
        if name in skip_names:
            continue

        # Must be a public def (not 'private').
        # Check the characters just before 'def' in the full source.
        before = full[max(0, start - 20):start]
        if "private" in before:
            continue

        if "safeIntArg(" in body:
            results.append(name)

    return results


def has_bp26_spec(spec_source: str, method_name: str) -> bool:
    """
    Return True if spec_source contains a BP26 test for method_name.
    A match requires both 'BP26' and the method name to appear in the
    same test method name (def "...") line.
    """
    pattern = re.compile(
        r'def\s+"[^"]*BP26[^"]*' + re.escape(method_name) + r'[^"]*"',
        re.IGNORECASE,
    )
    pattern2 = re.compile(
        r'def\s+"[^"]*' + re.escape(method_name) + r'[^"]*BP26[^"]*"',
        re.IGNORECASE,
    )
    return bool(pattern.search(spec_source) or pattern2.search(spec_source))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    uncovered: list[str] = []

    for driver_file in sorted(DRIVERS_DIR.glob("*.groovy")):
        # Skip library files — they don't have a corresponding spec of their own
        # for individual methods; they are covered via the driver specs.
        if "Lib.groovy" in driver_file.name or "Virtual" in driver_file.name:
            continue

        driver_source = driver_file.read_text(encoding="utf-8")
        command_methods = find_safeintarg_command_methods(driver_source)

        if not command_methods:
            continue

        spec_file = driver_name_to_spec_file(driver_file)
        if not spec_file.exists():
            for m in command_methods:
                uncovered.append(f"{driver_file.name}:{m}  (no spec file {spec_file.name})")
            continue

        spec_source = spec_file.read_text(encoding="utf-8")
        for m in command_methods:
            if not has_bp26_spec(spec_source, m):
                uncovered.append(f"{driver_file.name}:{m}  (no BP26 spec in {spec_file.name})")

    if uncovered:
        print("MISSING BP26 coercion-throw specs:")
        for line in uncovered:
            print(f"  {line}")
        return 1

    # Empty output = all safeIntArg command methods have BP26 coverage.
    return 0


if __name__ == "__main__":
    sys.exit(main())
