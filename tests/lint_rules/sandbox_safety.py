"""
sandbox_safety.py — Hubitat sandbox safety checks.

Rule 17 — Forbidden imports and reflection patterns:
  java.io.*
  java.lang.Runtime
  java.lang.ProcessBuilder
  groovy.lang.GroovyShell
  org.codehaus.groovy.control.*
  Eval.me(
  evaluate(
  Class.forName(
"""

import re
from pathlib import Path


FORBIDDEN_IMPORTS = [
    (re.compile(r'^\s*import\s+java\.io\b'), "java.io.*"),
    (re.compile(r'^\s*import\s+java\.lang\.Runtime\b'), "java.lang.Runtime"),
    (re.compile(r'^\s*import\s+java\.lang\.ProcessBuilder\b'), "java.lang.ProcessBuilder"),
    (re.compile(r'^\s*import\s+groovy\.lang\.GroovyShell\b'), "groovy.lang.GroovyShell"),
    (re.compile(r'^\s*import\s+org\.codehaus\.groovy\.control\b'), "org.codehaus.groovy.control.*"),
]

FORBIDDEN_PATTERNS = [
    (re.compile(r'\bEval\.me\s*\('), "Eval.me()"),
    (re.compile(r'\bevaluate\s*\('), "evaluate()"),
    (re.compile(r'\bClass\.forName\s*\('), "Class.forName()"),
]


def _context(lines, lineno, window=1):
    start = max(0, lineno - 1 - window)
    end = min(len(lines), lineno + window)
    return '\n'.join(f"    {lines[i]}" for i in range(start, end))


def _making_finding(severity, rule_id, title, path, rel_base, lineno, lines, why, fix):
    return {
        "severity": severity,
        "rule_id": rule_id,
        "title": title,
        "file": str(path.relative_to(rel_base)).replace('\\', '/'),
        "line": lineno,
        "context": _context(lines, lineno),
        "why": why,
        "fix": fix,
    }


def check_rule17_sandbox_forbidden(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Flag imports or patterns that break out of the Hubitat Groovy sandbox.
    These are FAIL severity — driver sandbox escapes are security violations.
    """
    findings = []
    if path.suffix != '.groovy':
        return findings

    for i, line in enumerate(raw_lines, 1):
        for pattern, label in FORBIDDEN_IMPORTS:
            if pattern.search(line):
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE17_forbidden_import",
                    title=f"Forbidden import: {label}",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=f"Rule 17 (Sandbox Safety): importing {label} allows code to break out of "
                        "the Hubitat driver sandbox. Hubitat blocks these at runtime; the import "
                        "itself indicates a design violation.",
                    fix=f"Remove the {label} import. Use only: groovy.transform.Field, "
                        "groovy.json.JsonSlurper, java.security.MessageDigest.",
                ))

        for pattern, label in FORBIDDEN_PATTERNS:
            if pattern.search(line):
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE17_forbidden_reflection",
                    title=f"Sandbox-escape reflection pattern: {label}",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why=f"Rule 17 (Sandbox Safety): {label} executes arbitrary Groovy code / "
                        "loads arbitrary classes, bypassing the Hubitat driver sandbox. "
                        "This is a security violation.",
                    fix=f"Remove the {label} call. Use only approved Hubitat APIs.",
                ))

    return findings


ALL_RULES = [check_rule17_sandbox_forbidden]
