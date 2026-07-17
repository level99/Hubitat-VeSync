"""Closed-mechanism guard against outbound-text hygiene leaks in shipped source.

Three leak classes are gated across the WHOLE tracked tree — driver/library source,
the Spock specs, and the lint runner/rules:

  1. This-fork PROCESS TOKENS — "cluster N" (the campaign nomenclature) AND the
     broader fork/MCP-pipeline tokens (Tier N, Sweep N, Round N, PR #N, Issue #N,
     Lead-Finding-N, <agent>-N). Like a "PR #N" reference, these are meaningless
     once the work merges and must not live in code, spec, or test comments / method
     names. The behavioural invariant (the BP catalog entry, the neutral description)
     is the durable artifact; the process label that prompted the fix belongs only in
     the commit message. RULE38 (tests/lint_rules/process_token_scrub.py) gates these
     in Drivers/Levoit + tests/ with comment-scoping + an external-provenance
     allowlist, but lint.py does NOT scan src/test/groovy — the Spock specs — so this
     pytest is the layer that covers them there.

  2. INSTALL-SPECIFIC DEVICE IDs — "device 1132" and similar. A maintainer's
     hub device numbers are install-specific and have no place in shipped source.

Accepted exceptions (kept passing):
  - "Phase N Round M" driver-header release-history lines (a changelog-in-header
    convention) — the Round match is exempt when the line also carries "Phase".
  - External upstream citations "pyvesync PR #N" / "HA issue #N" / webdjoe/github/
    Homebridge/... — real references that must stay explicit, not scrubbed.
  - PR/Issue tokens in Drivers/Levoit/*.groovy driver source are ALL external
    upstream citations (mirrors RULE38); PR/Issue is not checked there.

Why a pytest rather than a lint rule: lint.py's file discovery only walks
Drivers/Levoit and tests/, NOT src/test/groovy — but the Spock specs were the
predominant leak site (cluster-N comments + method names, "device 1132"). A
git-ls-files sweep covers what lint.py structurally cannot.

Scope note — user-assigned device LABELS / room names (e.g. a real room name used
as a fixture deviceName) are handled by the scrub itself plus the spec-fixture
"use a generic Test* / model-code name" convention, NOT by a name deny-list here:
hard-coding the real labels into this public test would re-introduce the very leak
it guards against. Reviewers keep watch for that class; these two mechanical
patterns cover the enumerable ones.
"""
import pathlib
import re
import subprocess

REPO = pathlib.Path(__file__).resolve().parent.parent

# "cluster 3", "cluster 3b", "cluster3", "cluster-3" (case-insensitive). Deliberately
# scoped to the campaign "cluster" nomenclature only, so the v2.4 driver-header
# phase/release history lines remain a legitimate changelog-in-header convention
# (a must-not-catch fixture below pins that boundary).
CLUSTER_RE = re.compile(r"\bcluster[\s#-]*\d", re.IGNORECASE)

# "device 1132" — the word "device" immediately followed by a bare 3-4 digit id.
# Model-type literals ("LAP-V102S-WUS") and prose ("the device state") do not match.
DEVICE_ID_RE = re.compile(r"\bdevice\s+\d{3,4}\b", re.IGNORECASE)

# --- Extended fork/MCP process tokens (broader forms of the process-token class) ----
# Each becomes meaningless once its cycle closes (same rationale as "cluster N").
_TIER_RE         = re.compile(r"\bTier[\s#-]*\d", re.IGNORECASE)   # Tier 22 / Tier-25 / Tier#3
_SWEEP_RE        = re.compile(r"\bSweep[\s#-]*\d", re.IGNORECASE)  # Sweep 16 / Sweep #16
_ROUND_RE        = re.compile(r"\bRound\s+\d", re.IGNORECASE)      # Round 3 (spelled out)
_LEAD_FINDING_RE = re.compile(r"\bLead-Finding-?\d", re.IGNORECASE)
_AGENT_RE        = re.compile(r"\b(?:dev|qa|tester)-\d")           # dev-2 / qa-1 / tester-3
_PR_ISSUE_RE     = re.compile(r"\b(?:PR|[Ii]ssue)\s*#\s*\d")       # PR #167 / Issue #42

# "Phase N Round M" driver-header release-history lines are an accepted convention —
# a Round match on a line that also carries "Phase" is exempt.
_PHASE_RE = re.compile(r"\bPhase\b", re.IGNORECASE)

# External upstream citations are allowed for PR #N / Issue #N (they stay explicit).
_EXTERNAL_MARKERS = (
    "pyvesync", "ha pr", "ha issue", "home assistant",
    "homebridge", "smartthings", "webdjoe", "niklas", "github",
)

# Files that legitimately CONTAIN these token forms as documentation / test fixtures,
# so they must not be scanned for the process-token class: the RULE38 rule module (its
# docstring enumerates every form) and the lint test suite (fixture strings that embed
# every token form on purpose). This guard file self-excludes below.
_PROCESS_TOKEN_SKIP_BASENAMES = ("process_token_scrub.py", "lint_test.py")


def _tracked_source_files():
    out = subprocess.check_output(
        ["git", "-C", str(REPO), "ls-files",
         "Drivers/Levoit/*.groovy",
         "src/test/groovy",
         "tests/*.py",
         "tests/lint_rules/*.py"],
        text=True,
    )
    files = []
    for rel in out.splitlines():
        if not rel.strip():
            continue
        # This guard file legitimately contains the patterns (regexes + fixtures).
        if rel.endswith("test_no_process_pii_leak.py"):
            continue
        p = REPO / rel
        if p.suffix in (".groovy", ".py"):
            files.append(p)
    return files


def _scan(pattern):
    hits = []
    for f in _tracked_source_files():
        text = f.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{f.relative_to(REPO).as_posix()}:{lineno}: {line.strip()[:90]}")
    return hits


def test_no_cluster_process_token_in_source():
    hits = _scan(CLUSTER_RE)
    assert not hits, (
        "Campaign 'cluster N' process-token leaked into shipped source "
        "(use the BP catalog ref / a neutral description; the cluster belongs in the "
        "commit message):\n  " + "\n  ".join(hits)
    )


def test_no_install_device_id_in_source():
    hits = _scan(DEVICE_ID_RE)
    assert not hits, (
        "Install-specific device id (e.g. 'device 1132') leaked into shipped source "
        "(replace with a neutral 'a live device'):\n  " + "\n  ".join(hits)
    )


# --- must-catch / must-not-catch fixtures (make the guard non-vacuous) -----------

def test_cluster_pattern_catches_known_forms():
    for s in ("// v2.10 cluster 1 (result.code crash class)",
              'def "off() ... (BP29 - cluster 3b)"()',
              "Cluster 3 (v2.10): on()/off() ...",
              "cluster-4", "cluster#2", "cluster3"):
        assert CLUSTER_RE.search(s), s


def test_cluster_pattern_does_not_overmatch():
    for s in ("2026-05-03: v2.4.2 Phase 4 Round 5 - migrated to lib",   # Round, not cluster
              "BP29 gate: off() must gate the optimistic emit",
              "clustered index",                                        # no adjacent digit
              "state.auto_mode change gate"):
        assert not CLUSTER_RE.search(s), s


def test_device_id_pattern_catches_and_does_not_overmatch():
    assert DEVICE_ID_RE.search("both payload guesses refuted on device 1132 (HTTP 200)")
    assert DEVICE_ID_RE.search("verification (device 1132, 2026-05-01)")
    for s in ('deviceType: "LAP-V102S-WUS"',      # model literal
              "the device state is unchanged",      # prose
              "device.currentValue('switch')",      # api call
              "300 devices"):                        # digits before 'device'
        assert not DEVICE_ID_RE.search(s), s


# --- Extended fork/MCP process-token guard ---------------------------------------

def _has_external_marker(line):
    low = line.lower()
    return any(m in low for m in _EXTERNAL_MARKERS)


def _is_levoit_driver(path):
    return "Drivers/Levoit/" in path.as_posix() and path.suffix == ".groovy"


def _scan_process_tokens():
    """Scan the tracked tree for the broader fork/MCP process tokens, applying the
    documented exceptions (Phase-Round, external PR/Issue citations, driver-source
    PR/Issue). Returns a list of "file:line: [token] snippet" hit strings."""
    hits = []
    for f in _tracked_source_files():
        if f.name in _PROCESS_TOKEN_SKIP_BASENAMES:
            continue
        is_driver = _is_levoit_driver(f)
        text = f.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for label, rx in (("Tier", _TIER_RE), ("Sweep", _SWEEP_RE),
                              ("Lead-Finding", _LEAD_FINDING_RE), ("agent", _AGENT_RE)):
                if rx.search(line):
                    hits.append(f"{f.relative_to(REPO).as_posix()}:{lineno}: [{label}] {line.strip()[:90]}")
            # Round: exempt the "Phase N Round M" driver-header release-history line.
            if _ROUND_RE.search(line) and not _PHASE_RE.search(line):
                hits.append(f"{f.relative_to(REPO).as_posix()}:{lineno}: [Round] {line.strip()[:90]}")
            # PR/Issue: driver .groovy refs are all external upstream citations (skip);
            # elsewhere, a same-line external marker (pyvesync/HA/...) is allowed.
            if not is_driver and _PR_ISSUE_RE.search(line) and not _has_external_marker(line):
                hits.append(f"{f.relative_to(REPO).as_posix()}:{lineno}: [PR/Issue] {line.strip()[:90]}")
    return hits


def test_no_extended_process_token_in_source():
    hits = _scan_process_tokens()
    assert not hits, (
        "Fork/MCP process token (Tier N / Sweep N / Round N / PR #N / Issue #N / "
        "Lead-Finding-N / <agent>-N) leaked into shipped source or specs. Replace with "
        "the BP catalog ref or a neutral description; keep external pyvesync/HA citations "
        "explicit (e.g. 'pyvesync PR #505'):\n  " + "\n  ".join(hits)
    )


# --- must-catch / must-not-catch fixtures for the extended patterns ---------------

def test_extended_pattern_catches_known_forms():
    assert _TIER_RE.search("// Tier 22 guard: ...")
    assert _TIER_RE.search("Post-fix (Tier-25): recover the speed")
    assert _SWEEP_RE.search("Sweep #16 remediation")
    assert _SWEEP_RE.search("sweep 17 finding")
    assert _ROUND_RE.search("// Round 3 fix: added null guard")
    assert _LEAD_FINDING_RE.search("Lead-Finding-1: parity guard")
    assert _AGENT_RE.search("resumed dev-2 with fixes")
    assert _AGENT_RE.search("qa-1 flagged this")
    assert _PR_ISSUE_RE.search("upstream maintainer asked for PR #167 rework")
    assert _PR_ISSUE_RE.search("this-fork issue #42 leaked into a spec")


def test_extended_pattern_respects_exceptions():
    # "Phase N Round M" driver-header release-history line — Round match is exempt via Phase.
    phase_line = "2026-05-03: v2.4.2  Phase 4 Round 5 — migrated to LevoitHumidifierLib"
    assert _ROUND_RE.search(phase_line) and _PHASE_RE.search(phase_line)  # exempted at scan time
    # External pyvesync / HA PR/issue citations are allowed (marker present on the line).
    for line in ("per pyvesync PR #505 (EU firmware auto alias)",
                 "HA issue #160387 references LUH-D301S-WUSR",
                 "https://github.com/webdjoe/pyvesync/issues/295 (issue #295)"):
        assert _PR_ISSUE_RE.search(line) and _has_external_marker(line)
    # Prose / non-token uses must NOT match.
    assert not _TIER_RE.search("the tier list is unrelated")   # no adjacent digit
    assert not _ROUND_RE.search("round-trip latency budget")   # no digit after Round
    assert not _AGENT_RE.search("device.currentValue('switch')")
    assert not _PR_ISSUE_RE.search("the issue is transient")   # no # + digit
