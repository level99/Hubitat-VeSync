#!/usr/bin/env python3
"""
BP26 safeIntArg spec coverage check.

Enumerates every command method in Drivers/Levoit/*.groovy (including library
files) whose body calls safeIntArg() on a command parameter, then verifies
that at least one corresponding *Spec.groovy file in
src/test/groovy/drivers/ contains a BP26 coercion-throw regression spec for
that method.

A "BP26 spec" is defined as a test method whose name contains both "BP26"
and the command method name (case-insensitive).

For library files (*Lib.groovy): a method is covered if at least one of the
Spock specs for a driver that #includes the library contains a BP26 spec
naming that method.  Every including driver's spec is checked; any single hit
satisfies the requirement.

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


def _find_struct_braces(source: str, start: int) -> list[tuple[int, str]]:
    """
    Scan from `start` in source and yield (position, '{' or '}') for every
    brace that is NOT inside a string literal or comment.

    Handled non-structural regions:
      - // line comments (to end of line)
      - /* */ block comments
      - triple-quoted strings  \"\"\"...\"\"\"
      - double-quoted strings  \"...\" (GString ${...} braces skipped)
      - single-quoted strings  '...'
    """
    results: list[tuple[int, str]] = []
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


def find_safeintarg_command_methods(driver_source: str) -> list[str]:
    """
    Return names of top-level command methods (def <name>(...)) whose bodies
    contain a safeIntArg( call.

    Strategy: split source into method blocks by scanning for top-level
    'def <name>(' tokens, then check each block for safeIntArg.
    """
    method_pattern = re.compile(r"^(?:    )?def\s+(\w+)\s*\(", re.MULTILINE)

    results = []
    full = driver_source

    method_starts = [(m.start(), m.group(1)) for m in method_pattern.finditer(full)]
    method_starts.append((len(full), None))  # sentinel

    for i, (start, name) in enumerate(method_starts[:-1]):
        # Bound the method body by brace-depth matching so the body slice ends
        # at the method's own closing brace, not at the next def token. Braces
        # inside string literals and comments are skipped to avoid false matches
        # (e.g. a string containing "}").
        try:
            brace_start = full.index("{", start)
        except ValueError:
            body_end = method_starts[i + 1][0]
            body = full[start:body_end]
            # fall through to safeIntArg check below
        else:
            braces = _find_struct_braces(full, brace_start)
            depth = 0
            body_end = method_starts[i + 1][0]
            for pos, kind in braces:
                if kind == '{':
                    depth += 1
                else:
                    depth -= 1
                    if depth == 0:
                        body_end = pos + 1
                        break
            body = full[start:body_end]

        skip_names = {
            "installed", "updated", "initialize", "refresh", "logDebugOff",
            "ensureDebugWatchdog", "applyStatus", "on", "off", "toggle",
            "hubBypass", "httpOk", "sanitize", "logInfo", "logDebug",
            "logError", "logWarn", "recordError", "captureDiagnostics",
            "ensureSwitchOn", "requireNotNull", "safeIntArg",
            "setupPollSchedule", "ensurePollWatchdog",
            "getDevices", "updateDevices", "sendBypassRequest",
            "isNightlightVariant",
        }
        # Behavior-based skip for doSet* shared helpers: only include a doSet*
        # method if its body actually calls safeIntArg() — that is, if the body
        # is already past the outer `if "safeIntArg(" in body:` gate below.
        # doSet* helpers that do NOT call safeIntArg() have no BP26 coercion to
        # guard, so spec coverage is not required.  This is derived from the body
        # content, not from a hardcoded name list, so any future doSet* helper
        # that gains a safeIntArg() call automatically comes into scope.
        # (Note: the safeIntArg() gate further below provides the same exclusion
        # for non-doSet* methods; this guard is a cheap short-circuit for the
        # doSet* family specifically, preventing confusion with the name-exclusion
        # pattern previously used for doSetDisplayScreenSwitch/doSetAutoStopSwitch.)
        if name.startswith("doSet") and "safeIntArg(" not in body:
            continue
        if name in skip_names:
            continue

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

    Word-boundary delimiters ensure that a spec for 'setTimer' does not
    falsely satisfy coverage for 'setTimerRemaining' (or vice versa).
    The boundaries use look-ahead/look-behind for non-word characters
    since Groovy test name strings may not be pure identifiers.
    """
    esc = re.escape(method_name)
    # Word-boundary: preceded/followed by non-word char or string start/end
    wb_name = r'(?<![A-Za-z0-9_])' + esc + r'(?![A-Za-z0-9_])'
    pattern = re.compile(
        r'def\s+"[^"]*BP26[^"]*' + wb_name + r'[^"]*"',
        re.IGNORECASE,
    )
    pattern2 = re.compile(
        r'def\s+"[^"]*' + wb_name + r'[^"]*BP26[^"]*"',
        re.IGNORECASE,
    )
    return bool(pattern.search(spec_source) or pattern2.search(spec_source))


def lib_name_from_include(lib_file: Path) -> str | None:
    """
    Extract the library 'name:' value from a *Lib.groovy file.
    e.g. library(name: "LevoitCorePurifier", ...) -> "LevoitCorePurifier"
    """
    source = lib_file.read_text(encoding="utf-8")
    m = re.search(r'library\s*\(.*?name\s*:\s*"([^"]+)"', source, re.DOTALL)
    return m.group(1) if m else None


def find_including_drivers(lib_name: str) -> list[Path]:
    """
    Return driver files (non-Lib) that #include level99.<lib_name>.
    """
    include_pattern = re.compile(
        r"#include\s+level99\." + re.escape(lib_name) + r"\b"
    )
    result = []
    for f in sorted(DRIVERS_DIR.glob("*.groovy")):
        if "Lib.groovy" in f.name or "Virtual" in f.name:
            continue
        if include_pattern.search(f.read_text(encoding="utf-8")):
            result.append(f)
    return result


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    uncovered: list[str] = []

    for driver_file in sorted(DRIVERS_DIR.glob("*.groovy")):
        # Virtual dev-tool files have no spec and are intentionally excluded.
        if "Virtual" in driver_file.name:
            continue

        is_lib = "Lib.groovy" in driver_file.name
        driver_source = driver_file.read_text(encoding="utf-8")
        command_methods = find_safeintarg_command_methods(driver_source)

        if not command_methods:
            continue

        if is_lib:
            # For a library file, resolve the including drivers and require
            # that at least one of their spec files contains a BP26 spec for
            # each safeIntArg command method.
            lib_name = lib_name_from_include(driver_file)
            if not lib_name:
                # Cannot determine library name — conservatively flag all methods.
                for m in command_methods:
                    uncovered.append(
                        f"{driver_file.name}:{m}  (could not parse library name)"
                    )
                continue

            including_drivers = find_including_drivers(lib_name)
            if not including_drivers:
                for m in command_methods:
                    uncovered.append(
                        f"{driver_file.name}:{m}  (no driver #includes {lib_name})"
                    )
                continue

            for method in command_methods:
                # Collect all spec files for including drivers.
                covered = False
                missing_specs = []
                for drv in including_drivers:
                    spec_file = driver_name_to_spec_file(drv)
                    if not spec_file.exists():
                        missing_specs.append(spec_file.name)
                        continue
                    if has_bp26_spec(spec_file.read_text(encoding="utf-8"), method):
                        covered = True
                        break
                if not covered:
                    including_names = ", ".join(d.name for d in including_drivers)
                    uncovered.append(
                        f"{driver_file.name}:{method}"
                        f"  (no BP26 spec in any including-driver spec:"
                        f" {including_names})"
                    )
        else:
            spec_file = driver_name_to_spec_file(driver_file)
            if not spec_file.exists():
                for m in command_methods:
                    uncovered.append(
                        f"{driver_file.name}:{m}"
                        f"  (no spec file {spec_file.name})"
                    )
                continue

            spec_source = spec_file.read_text(encoding="utf-8")
            for m in command_methods:
                if not has_bp26_spec(spec_source, m):
                    uncovered.append(
                        f"{driver_file.name}:{m}"
                        f"  (no BP26 spec in {spec_file.name})"
                    )

    if uncovered:
        print("MISSING BP26 coercion-throw specs:")
        for line in uncovered:
            print(f"  {line}")
        return 1

    # Empty output = all safeIntArg command methods have BP26 coverage.
    return 0


if __name__ == "__main__":
    sys.exit(main())
