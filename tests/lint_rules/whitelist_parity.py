"""
whitelist_parity.py — RULE22: isLevoitClimateDevice() whitelist parity with deviceType().

Every prefix (startsWith check) and literal (case / 'in' list) that deviceType()
recognizes must also appear in isLevoitClimateDevice(). A gap means a device class
gets discovered, added as a child, and polled -- but the Generic-driver filter's
isLevoitClimateDevice() guard skips it, so it never gets a real driver attached.

Checks:
  RULE22a — every prefix in deviceType() has a matching startsWith in isLevoitClimateDevice()
  RULE22b — every literal in deviceType() is covered by isLevoitClimateDevice() via either:
             (a) a matching startsWith prefix (e.g. "LAP-C401S-WUSR" covered by "LAP-"), OR
             (b) an explicit entry in the `code in [...]` literal list (e.g. "Classic300S")

Skips:
  - Regex patterns (case ~/.../:) are too brittle to parse reliably; they are logged
    as debug skips rather than false-positive failures. Manual review covers them.
  - The default "GENERIC" return in deviceType() has no corresponding whitelist entry
    by design; that is the fallthrough, not a recognized device class.
"""

import re
from pathlib import Path


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _extract_function_body(source: str, fn_pattern: str) -> str:
    """
    Extract the body of the first function matching fn_pattern via brace balancing.
    fn_pattern is a regex that matches the function signature line.
    Returns the text between the opening and closing braces, or '' if not found.
    """
    match = re.search(fn_pattern, source, re.MULTILINE)
    if not match:
        return ''

    # The regex pattern includes \{ as part of the signature, so the opening brace
    # is within the match span, not forward of match.end(). Using rfind within
    # [match.start(), match.end()] locates the correct opening brace.
    # Searching forward from match.end() would land on the next function's brace.
    brace_pos = source.rfind('{', match.start(), match.end() + 1)
    if brace_pos == -1:
        return ''

    depth = 0
    i = brace_pos
    while i < len(source):
        ch = source[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return source[brace_pos + 1 : i]
        i += 1
    return ''


def _strip_comments(text: str) -> str:
    """Strip // line comments and /* */ block comments from Groovy source."""
    # Block comments first
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.DOTALL)
    # Line comments
    text = re.sub(r'//[^\n]*', ' ', text)
    return text


def _extract_starts_with_prefixes(body: str) -> set:
    """
    Find all code.startsWith("XXX") calls in the function body.
    Returns the set of prefix strings (without quotes).
    """
    return set(re.findall(r'\.startsWith\(\s*"([^"]+)"\s*\)', body))


def _extract_literal_cases(body: str) -> set:
    """
    Find all literal string values that appear in:
      case "XXX":
      code in ["A", "B", ...]
    Returns the set of literal strings (without quotes).
    """
    literals = set()

    # case "XXX": pattern (Groovy switch)
    for m in re.finditer(r'\bcase\s+"([^"]+)"\s*:', body):
        literals.add(m.group(1))

    # code in ["A", "B", ...] pattern
    for m in re.finditer(r'\bin\s*\[([^\]]+)\]', body):
        inner = m.group(1)
        literals.update(re.findall(r'"([^"]+)"', inner))

    return literals


def _has_regex_cases(body: str) -> bool:
    """Return True if the function body contains regex-style case patterns (case ~/.../:)."""
    return bool(re.search(r'\bcase\s*~/', body))


def _making_finding(severity, rule_id, title, file_str, lineno, context, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": file_str,
        "line": lineno,
        "context": context,
        "why": why,
        "fix": fix,
    }


# ---------------------------------------------------------------------------
# Rule entry point
# ---------------------------------------------------------------------------

def check_rule22_whitelist_parity(repo_root: Path, config: dict):
    """
    RULE22: Assert that every prefix and literal recognized by deviceType() in
    VeSyncIntegration.groovy is also covered by isLevoitClimateDevice().

    Returns list of findings (dicts in standard format).
    """
    findings = []
    parent_path = repo_root / "Drivers" / "Levoit" / "VeSyncIntegration.groovy"
    rel_path = "Drivers/Levoit/VeSyncIntegration.groovy"

    if not parent_path.exists():
        findings.append(_making_finding(
            severity="WARN",
            rule_id="RULE22_file_missing",
            title="VeSyncIntegration.groovy not found -- RULE22 skipped",
            file_str=rel_path,
            lineno=0,
            context="",
            why="RULE22 (Whitelist Parity): cannot check isLevoitClimateDevice() parity without "
                "the parent driver source.",
            fix="Ensure Drivers/Levoit/VeSyncIntegration.groovy exists.",
        ))
        return findings

    source = parent_path.read_text(encoding='utf-8', errors='replace')
    clean = _strip_comments(source)

    # Extract deviceType() body
    device_type_body = _extract_function_body(
        clean,
        r'\bprivate\b[^{]*\bdeviceType\b\s*\([^)]*\)\s*\{'
        r'|'
        r'\bdef\b\s+deviceType\b\s*\([^)]*\)\s*\{',
    )
    if not device_type_body:
        findings.append(_making_finding(
            severity="WARN",
            rule_id="RULE22_deviceType_not_found",
            title="Could not locate deviceType() body -- RULE22 skipped",
            file_str=rel_path,
            lineno=0,
            context="",
            why="RULE22 (Whitelist Parity): brace-balanced extraction of deviceType() failed. "
                "The function signature may have changed.",
            fix="Verify deviceType() exists in VeSyncIntegration.groovy and matches the expected "
                "signature (private deviceType(code) or def deviceType(code)).",
        ))
        return findings

    # Extract isLevoitClimateDevice() body
    whitelist_body = _extract_function_body(
        clean,
        r'\bprivate\b[^{]*\bisLevoitClimateDevice\b\s*\([^)]*\)\s*\{'
        r'|'
        r'\bdef\b\s+isLevoitClimateDevice\b\s*\([^)]*\)\s*\{',
    )
    if not whitelist_body:
        findings.append(_making_finding(
            severity="WARN",
            rule_id="RULE22_whitelist_not_found",
            title="Could not locate isLevoitClimateDevice() body -- RULE22 skipped",
            file_str=rel_path,
            lineno=0,
            context="",
            why="RULE22 (Whitelist Parity): brace-balanced extraction of isLevoitClimateDevice() "
                "failed. The function signature may have changed.",
            fix="Verify isLevoitClimateDevice() exists in VeSyncIntegration.groovy.",
        ))
        return findings

    # Parse deviceType() for prefixes + literals
    dt_prefixes = _extract_starts_with_prefixes(device_type_body)
    dt_literals = _extract_literal_cases(device_type_body)

    # Parse isLevoitClimateDevice() for prefixes + literals
    wl_prefixes = _extract_starts_with_prefixes(whitelist_body)
    wl_literals = _extract_literal_cases(whitelist_body)

    # Note if deviceType() has regex cases (we skip those; just warn once)
    if _has_regex_cases(device_type_body):
        findings.append(_making_finding(
            severity="WARN",
            rule_id="RULE22_regex_cases_skipped",
            title="deviceType() contains regex case patterns -- RULE22 cannot verify parity for those",
            file_str=rel_path,
            lineno=0,
            context="    Found: case ~/.../ patterns in deviceType()",
            why="RULE22 (Whitelist Parity): regex case patterns (case ~/.../:) are too brittle to "
                "parse statically. Their prefixes are not checked by this rule. Ensure any device "
                "family matched by a regex case also has a corresponding prefix in "
                "isLevoitClimateDevice().",
            fix="Manually verify that regex-matched device code families (e.g. LEH-S60[12]S-*) "
                "have a matching prefix in isLevoitClimateDevice() (e.g. startsWith(\"LEH-\")).",
        ))

    # RULE22a: prefix parity
    for prefix in sorted(dt_prefixes):
        if prefix not in wl_prefixes:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE22a_prefix_missing_from_whitelist",
                title=f'deviceType() uses startsWith("{prefix}") but isLevoitClimateDevice() has no matching prefix',
                file_str=rel_path,
                lineno=0,
                context=f'    deviceType() prefix: "{prefix}"',
                why="RULE22a (Whitelist Parity / prefix): a device code prefix recognized by "
                    "deviceType() must also appear in isLevoitClimateDevice() so the Generic-driver "
                    "filter does not silently skip those devices. Missing prefix means discovered "
                    "devices with this prefix get an orphaned Hubitat child with no driver.",
                fix=f'Add `if (code.startsWith("{prefix}")) return true` to isLevoitClimateDevice() '
                    f'in {rel_path}.',
            ))

    # RULE22b: literal parity
    # Exclude the default GENERIC fallthrough — that is not a device class literal.
    # Also exclude empty strings (parsing artefacts).
    #
    # A literal is considered covered if EITHER:
    #   (a) it appears directly in isLevoitClimateDevice()'s `code in [...]` literal list, OR
    #   (b) it matches any prefix in isLevoitClimateDevice() via startsWith semantics
    #       (i.e., the literal starts with one of the whitelisted prefixes).
    # This prevents false-positive FAILs for model-code literals like "LAP-C401S-WUSR"
    # that are correctly covered by the "LAP-" prefix, not by an explicit literal entry.
    for literal in sorted(dt_literals - {"GENERIC", ""}):
        covered_by_literal = literal in wl_literals
        covered_by_prefix = any(literal.startswith(p) for p in wl_prefixes)
        if not (covered_by_literal or covered_by_prefix):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE22b_literal_missing_from_whitelist",
                title=f'deviceType() recognizes literal "{literal}" but isLevoitClimateDevice() does not cover it',
                file_str=rel_path,
                lineno=0,
                context=f'    deviceType() literal: "{literal}"',
                why="RULE22b (Whitelist Parity / literal): a device code literal recognized by "
                    "deviceType() must be covered by isLevoitClimateDevice() — either via a "
                    "matching startsWith prefix or an explicit entry in the `code in [...]` list. "
                    "A gap means devices reporting that code string are filtered out by the "
                    "Generic-driver guard before driver assignment.",
                fix=f'Add a startsWith prefix or explicit literal "{literal}" to '
                    f'isLevoitClimateDevice() in {rel_path}.',
            ))

    return findings
