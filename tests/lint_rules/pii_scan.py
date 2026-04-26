"""
pii_scan.py — PII and hardcoded secret detection.

Rule 16 — Scans ALL committed file types for:
  - Hub-LAN IP literals (10.x.x.x, 192.168.x.x, 172.16-31.x.x)
  - Email addresses
  - MAC addresses
  - Personal domain references (configurable block list)
  - VeSync-token-shaped strings near "token" / "accountID" keywords
  - Hardcoded passwords (password = "..." literals)

All are FAIL — committed PII in a public GitHub repo is an immediate risk.

Allow-list: config['pii_allow_patterns'] can contain regex strings that,
if matched on a line, suppress the finding for that line. Used for lines
in documentation that DESCRIBE these patterns without containing real values
(e.g., "Email: your@email.com" in a readme example).
"""

import re
from pathlib import Path


# ---------------------------------------------------------------------------
# Pattern definitions
# ---------------------------------------------------------------------------

# RFC-1918 private IP ranges (hub-LAN IPs)
LAN_IP_PATTERN = re.compile(
    r'\b(?:'
    r'10\.\d{1,3}\.\d{1,3}\.\d{1,3}'
    r'|192\.168\.\d{1,3}\.\d{1,3}'
    r'|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}'
    r')\b'
)

EMAIL_PATTERN = re.compile(
    r'\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b'
)

MAC_PATTERN = re.compile(
    r'\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b'
)

# Token-shaped: 32-64 char alphanumeric, near "token" / "accountID" on the same line
TOKEN_SHAPED = re.compile(r'[A-Za-z0-9]{32,64}')
TOKEN_CONTEXT = re.compile(r'\b(token|accountID|accountId|api_key|apiKey)\b', re.IGNORECASE)

HARDCODED_PASSWORD = re.compile(
    r'\bpassword\s*[=:]\s*"[^"]{3,}"',
    re.IGNORECASE
)


# ---------------------------------------------------------------------------
# Files / lines to skip entirely (not user-configurable — hard-coded exclusions)
# ---------------------------------------------------------------------------

# Paths (relative to repo root) that are allowed to contain IP-ish strings
# without being flagged — e.g., the lint_config.yaml itself has CIDR explanations.
ALWAYS_SKIP_FILES = {
    # The linter config uses placeholder examples — allow those
    "tests/lint_config.yaml",
    # README examples sometimes show placeholder IPs
    # (we rely on the allow_pattern config for those instead)
    # Linter test file: intentionally contains fake IPs, emails, and MACs as test fixtures
    # so the rule can assert they ARE detected. Scanning it would be a false-positive tautology.
    "tests/lint_test.py",
    # Linter source: docstring describes the patterns it detects (e.g. password = "..."),
    # which the HARDCODED_PASSWORD regex matches as a false positive.
    "tests/lint_rules/pii_scan.py",
}


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


def _is_allowed(line, allow_patterns):
    """Return True if any allow pattern matches this line (suppresses findings)."""
    for pat in allow_patterns:
        if re.search(pat, line):
            return True
    return False


def check_rule16_pii_scan(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    Scan for PII and hardcoded secrets across all file types.
    """
    findings = []

    # Determine relative path for skip checks
    try:
        rel_path_str = str(path.relative_to(rel_base)).replace('\\', '/')
    except ValueError:
        rel_path_str = str(path)

    if rel_path_str in ALWAYS_SKIP_FILES:
        return findings

    # Skip binary files (git objects, jars, etc.)
    suffix = path.suffix.lower()
    if suffix in {'.jar', '.class', '.bin', '.idx', '.pack', '.rev', '.png', '.jpg', '.gif'}:
        return findings

    allow_patterns = [re.compile(p) for p in config.get('pii_allow_patterns', [])]
    blocked_domains = config.get('blocked_domains', [])
    personal_domain_patterns = [
        re.compile(re.escape(d).replace(r'\*', r'[\w.-]+'))
        for d in blocked_domains
    ]

    for i, line in enumerate(raw_lines, 1):
        if _is_allowed(line, allow_patterns):
            continue

        # LAN IPs
        m = LAN_IP_PATTERN.search(line)
        if m:
            ip = m.group(0)
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE16_lan_ip",
                title=f"Hardcoded LAN IP address: {ip}",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Rule 16 (PII Scan): a hub-LAN IP literal in a public repo may expose "
                    "network topology. Even non-personal IPs signal an install-specific value "
                    "that should not be committed.",
                fix="Remove the hardcoded IP. Use a preference input or the Hubitat hub IP "
                    "discovery mechanism instead.",
            ))

        # Emails
        for m in EMAIL_PATTERN.finditer(line):
            email = m.group(0)
            # Skip common placeholder-style emails in documentation
            if email.lower() in {'your@email.com', 'example@example.com', 'user@example.com',
                                  'noreply@anthropic.com'}:
                continue
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE16_email",
                title=f"Email address found: {email}",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Rule 16 (PII Scan): a real email address committed to a public repo "
                    "enables spam/phishing targeting. Even developer emails in comments "
                    "should be omitted.",
                fix="Remove or replace with a placeholder like 'your@email.com'.",
            ))

        # MAC addresses
        m = MAC_PATTERN.search(line)
        if m:
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE16_mac_address",
                title=f"MAC address found: {m.group(0)}",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Rule 16 (PII Scan): a MAC address in a public repo identifies a specific "
                    "network device, enabling device fingerprinting.",
                fix="Remove the MAC address.",
            ))

        # Hardcoded passwords
        if HARDCODED_PASSWORD.search(line):
            findings.append(_making_finding(
                severity="FAIL",
                rule_id="RULE16_hardcoded_password",
                title="Hardcoded password literal",
                path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                why="Rule 16 (PII Scan): a hardcoded password literal in source code is a "
                    "credential leak. This is a public repo.",
                fix="Remove the hardcoded password. Use a preference input with type 'password'.",
            ))

        # Token-shaped strings near token/accountID keywords
        if TOKEN_CONTEXT.search(line):
            for m in TOKEN_SHAPED.finditer(line):
                tok = m.group(0)
                # Only flag if it looks non-trivial (not a method name etc.)
                # Heuristic: all-lowercase or all-uppercase is likely a variable name, skip
                if tok.isupper() or tok.islower() or tok.isalpha():
                    continue
                # Skip known false-positive values (test fixture UUIDs etc.)
                skip_tokens = config.get('pii_token_allowlist', [])
                if tok in skip_tokens:
                    continue
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE16_token_shaped",
                    title=f"Token-shaped string near auth keyword: {tok[:12]}...",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why="Rule 16 (PII Scan): a 32-64 character alphanumeric string near 'token' "
                        "or 'accountID' in a public repo is likely a real credential.",
                    fix="Remove the hardcoded token value. Token should come from state.token "
                        "populated at runtime via login().",
                ))

        # Personal domains
        for pat in personal_domain_patterns:
            if pat.search(line):
                findings.append(_making_finding(
                    severity="FAIL",
                    rule_id="RULE16_personal_domain",
                    title=f"Personal domain reference found",
                    path=path, rel_base=rel_base, lineno=i, lines=raw_lines,
                    why="Rule 16 (PII Scan): a personal domain in a public repo is a privacy "
                        "exposure. Remove any domain names associated with personal networks or "
                        "identities.",
                    fix="Remove or replace with a generic placeholder.",
                ))
                break  # one per line per domain type

    return findings


ALL_RULES = [check_rule16_pii_scan]
