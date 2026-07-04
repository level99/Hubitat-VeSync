"""Closed-mechanism guard against outbound-text hygiene leaks in shipped source.

Two leak classes are gated across the WHOLE tracked tree — driver/library source,
the Spock specs, and the lint runner/rules:

  1. This-fork PROCESS TOKENS — "cluster N" (the campaign nomenclature). Like a
     "PR #N" reference, a cluster label is meaningless once the work merges and
     must not live in code, spec, or test comments / method names. The behavioural
     invariant (the BP catalog entry, the neutral description) is the durable
     artifact; the cluster that prompted the fix belongs only in the commit message.

  2. INSTALL-SPECIFIC DEVICE IDs — "device 1132" and similar. A maintainer's
     hub device numbers are install-specific and have no place in shipped source.

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
