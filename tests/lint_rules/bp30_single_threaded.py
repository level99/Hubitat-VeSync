"""
bp30_single_threaded.py — RULE48: every child cloud driver must declare
``singleThreaded: true`` in its ``definition()`` block.

Bug Pattern #30 — command-storm non-determinism. A burst of overlapping commands
(multiple ``on()`` / ``setMode()`` / ``setSpeed()`` arriving within a few hundred ms
from Rule Machine double-fire, dashboard taps, or automations) runs CONCURRENTLY on
separate threads when a driver is ``singleThreaded: false`` (the Hubitat default). The
overlapping async cloud writes collide in flight, producing "...write failed" errors
and a device that never settles on its requested state. The BP30 fix is three layers;
Layer 1 — ``singleThreaded: true`` — serializes command + async-callback execution so
the storm-guard state reads/writes (Layers 2 & 3) are race-free. Without Layer 1 the
guards are unsound, so a child cloud driver missing it is BLOCKING.

Class predicate (authoritative, NOT an example list):
  A file is an in-scope child cloud driver iff ALL of:
    * it is a ``.groovy`` file under ``Drivers/Levoit/``;
    * it is NOT a Hubitat library (no top-level ``library(`` block) — libraries have no
      ``definition()`` and run inside their consumer's thread model;
    * it ``#include``s ``level99.LevoitChildBase`` — the shared cloud-write base lib
      (provides ``hubBypass`` / ``ensureSwitchOn`` / the BP30 window guards). This is the
      precise signal that the file is a cloud-talking child driver. The parent app
      (VeSyncIntegration.groovy), the diagnostics/notification utilities, and any future
      non-cloud driver do NOT include it and are excluded by construction — the predicate
      is the membership test, not a hardcoded enumeration.
    * its basename is not in OUT_OF_SCOPE (the virtual test parent, which already declares
      ``singleThreaded: true`` and never touches the cloud).

Detection: the ``definition()`` block must contain ``singleThreaded: true`` (case-sensitive
``true`` — Hubitat's metadata DSL treats the bareword ``true`` as the Boolean). A driver
that declares ``singleThreaded: false`` is treated as MISSING the invariant and flagged.

Severity: FAIL (BLOCKING). The storm guards are unsound without it.
"""

import re

from lint_rules._helpers import is_library_file, make_finding


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# In the driver dir, include the base lib, yet intentionally out-of-scope for this rule.
OUT_OF_SCOPE_BASENAMES = {
    "VeSyncIntegrationVirtual.groovy",  # virtual test parent; already singleThreaded, no cloud writes
}

# Cloud-write child-driver signal: the shared base lib that provides hubBypass/ensureSwitchOn
# and the BP30 window guards. Every child cloud driver includes it; nothing else does.
CHILDBASE_INCLUDE_RE = re.compile(r'(?m)^#include\s+level99\.LevoitChildBase\s*$')

# singleThreaded:true with flexible whitespace; case-sensitive bareword `true`.
SINGLE_THREADED_TRUE_RE = re.compile(r'\bsingleThreaded\s*:\s*true\b')


def check_rule48_single_threaded(path, raw_lines, cleaned_lines, raw_text, config, rel_base):
    """
    RULE48 (Bug Pattern #30): FAIL when an in-scope child cloud driver does not declare
    ``singleThreaded: true``. Returns one FAIL finding or empty list (PASS).
    """
    findings = []

    if path.suffix != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    # Libraries have no definition() / thread model of their own.
    if is_library_file(raw_text):
        return findings

    if path.name in OUT_OF_SCOPE_BASENAMES:
        return findings

    # D1: match against the COMMENT-STRIPPED source so a commented-out `// singleThreaded: true`
    # (or a commented `// #include level99.LevoitChildBase`) does NOT satisfy the rule. The driver
    # must REALLY declare it in live code, not in a comment.
    cleaned_text = '\n'.join(cleaned_lines)

    # Class membership test: only cloud-talking child drivers (those including the base lib).
    if not CHILDBASE_INCLUDE_RE.search(cleaned_text):
        return findings

    if SINGLE_THREADED_TRUE_RE.search(cleaned_text):
        return findings

    file_rel = str(path.relative_to(rel_base)).replace('\\', '/')
    findings.append(make_finding(
        severity='FAIL',
        rule_id="RULE48_missing_single_threaded",
        title="Missing 'singleThreaded: true' in definition() (Bug Pattern #30 Layer 1)",
        file_rel=file_rel,
        lineno=0,
        raw_lines=[],
        why=(
            "Child cloud drivers default to singleThreaded:false, so a burst of overlapping "
            "commands (Rule Machine double-fire, dashboard taps) runs concurrently on separate "
            "threads. The overlapping async cloud writes race the BP30 storm-guard state and "
            "collide in flight, producing '...write failed' errors and a device that never "
            "settles. singleThreaded:true serializes command + async-callback execution, which "
            "is what makes the BP30 power-on window guard and the C3 redundant-write gates sound."
        ),
        fix=(
            "Add 'singleThreaded: true' as an attribute inside the definition() block (it can be "
            "the first attribute, before name:). Example:\n"
            "    definition(\n"
            "        singleThreaded: true,\n"
            "        name: \"Levoit ...\","
        ),
    ))

    return findings


ALL_RULES = [check_rule48_single_threaded]
