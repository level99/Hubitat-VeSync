"""
bp29_noon_write_feedback.py — RULE46: a NO-ON setter must report its write failures.

Bug Pattern #29 — a NO-ON (preference / config / maintenance / scheduling) setter
that issues a bypassV2 write and inspects the result with ``if (httpOk(resp))`` but
has NO failure branch routing through ``reportWriteFailure`` silently swallows BOTH
a genuine cloud failure AND the expected device-off (11005000) rejection. The user
gets zero feedback when a set-and-forget command fails. The fix is an
``else { reportWriteFailure("<tag> failed", resp, [method:"<cloudMethod>"]) }`` branch,
which downgrades the device-off case to a single WARN and logs/records genuine faults.

This rule closes the class mechanically so a future NO-ON setter cannot regress to the
silent-swallow shape that ``LevoitVital200S.setLightDetection`` shipped with through v2.9.

Scope (authoritative predicate, not an example list):
  File: any ``.groovy`` source file (driver OR library) under ``Drivers/Levoit/``.
  Match: a method DEFINITION immediately preceded (allowing only blank lines and
    other ``//`` comment lines in between) by a ``// BP24: NO-ON`` classification
    comment — the same marker enforced by ``tests/check_bp24_classification.py``.
    The marker is the authoritative NO-ON signal; the rule does NOT re-derive
    NO-ON-vs-SHOULD-ON intent from the method name (that disambiguation is exactly
    what the source comment exists to settle).
  Flag iff the method body (brace-balanced) contains BOTH:
      - a ``hubBypass(`` call (the bypassV2 write), AND
      - an ``if (httpOk(resp))`` result check,
    but does NOT contain ``reportWriteFailure(`` anywhere in the body.

Why the marker keys the predicate (not the method name):
  NO-ON vs SHOULD-ON is not derivable from the method name reliably — ``setMode`` is
  SHOULD-ON, ``setDisplay`` is NO-ON, and the same name (``setLightDetection``) is
  NO-ON on both EverestAir and Vital. Keying on the mandatory ``// BP24: NO-ON``
  comment makes the rule low-false-positive: SHOULD-ON setters carry a different
  marker (``// BP24: SHOULD-ON``) and use ``reportWriteError`` after ``ensureSwitchOn()``,
  so they are out of scope by construction; power/update paths carry no NO-ON marker.

Out of scope (correctly not flagged):
  - SHOULD-ON setters (``// BP24: SHOULD-ON`` marker): a device-off rejection after an
    intended power-on is a genuine fault; they keep ``reportWriteError``.
  - Power (``on``/``off``) and self-fetch (``update``) paths: no NO-ON marker.
  - A NO-ON setter that delegates to a shared ``doSet*`` helper and has no
    ``hubBypass(`` / ``if (httpOk(resp))`` of its own (the helper carries the branch).
  - Private boolean helpers like ``writeSpeedPreferred`` (no NO-ON marker).
"""

import re
from lint_rules._helpers import make_finding_for_path


DRIVER_DIR_FRAGMENT = "Drivers/Levoit/"

# A NO-ON classification comment line. Whitespace- and dash-tolerant:
#   // BP24: NO-ON — configures a device preference; ...
#   // BP24: NO-ON - maintenance action; ...
_NOON_MARKER_RE = re.compile(r'^\s*//\s*BP24:\s*NO-ON\b', re.IGNORECASE)

# A method definition opening a brace on the same line.
_METHOD_DEF_RE = re.compile(r'^\s*def\s+(\w+)\s*\([^)]*\)\s*\{', re.MULTILINE)

# Body-content predicates.
_HUBBYPASS_RE = re.compile(r'\bhubBypass\s*\(')
_HTTPOK_CHECK_RE = re.compile(r'\bif\s*\(\s*httpOk\s*\(\s*resp\s*\)\s*\)')
_REPORT_WRITE_FAILURE_RE = re.compile(r'\breportWriteFailure\s*\(')


def _find_method_body_end(source: str, brace_start: int) -> int:
    """
    Walk forward from ``brace_start`` (the opening ``{`` of a method body)
    counting brace depth; return the index just past the matching ``}``.
    Returns -1 if the body is unterminated.
    """
    depth = 0
    for i in range(brace_start, len(source)):
        c = source[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
    return -1


def _preceded_by_noon_marker(raw_lines, def_line_idx) -> bool:
    """
    True iff the method whose ``def`` is at 0-based ``def_line_idx`` is preceded
    by a ``// BP24: NO-ON`` comment, allowing only blank lines and other ``//``
    comment lines between the marker and the ``def``.

    Scanning upward stops at the first non-blank, non-comment line (code, brace,
    annotation, etc.) — so a NO-ON marker that documents an EARLIER method does
    not leak onto a later one.
    """
    i = def_line_idx - 1
    while i >= 0:
        line = raw_lines[i]
        stripped = line.strip()
        if stripped == '':
            i -= 1
            continue
        if stripped.startswith('//'):
            if _NOON_MARKER_RE.match(line):
                return True
            i -= 1
            continue
        # First non-blank, non-comment line above the def — stop.
        return False
    return False


def check_rule46_noon_write_feedback(
    path, raw_lines, cleaned_lines, raw_text, config, rel_base
):
    """
    RULE46 (Bug Pattern #29): a NO-ON setter that issues a bypassV2 write and
    checks ``if (httpOk(resp))`` must route its failure branch through
    ``reportWriteFailure`` — otherwise a genuine cloud failure (and the expected
    device-off rejection) is silently swallowed with no user feedback.

    Fires on ``.groovy`` files under ``Drivers/Levoit/`` (drivers AND libs).
    Keyed on the mandatory ``// BP24: NO-ON`` classification comment so the
    NO-ON-vs-SHOULD-ON judgment is read from source, not re-derived.
    """
    findings = []

    if path.suffix.lower() != '.groovy':
        return findings

    path_str = str(path).replace('\\', '/')
    if DRIVER_DIR_FRAGMENT not in path_str:
        return findings

    for m in _METHOD_DEF_RE.finditer(raw_text):
        method_name = m.group(1)
        def_line_idx = raw_text.count('\n', 0, m.start())  # 0-based

        if not _preceded_by_noon_marker(raw_lines, def_line_idx):
            continue

        brace_start = m.end() - 1  # last char of match is '{'
        body_end = _find_method_body_end(raw_text, brace_start)
        if body_end == -1:
            continue  # malformed; skip
        body = raw_text[brace_start:body_end]

        # In-scope only if the method does its OWN write + httpOk check.
        # A NO-ON setter that purely delegates to a doSet* helper has neither and
        # is correctly skipped (the helper carries the reportWriteFailure branch).
        if not _HUBBYPASS_RE.search(body):
            continue
        if not _HTTPOK_CHECK_RE.search(body):
            continue

        # The fix is present iff reportWriteFailure is somewhere in the body.
        if _REPORT_WRITE_FAILURE_RE.search(body):
            continue

        findings.append(make_finding_for_path(
            severity='FAIL',
            rule_id='RULE46_noon_write_feedback',
            title=(
                f"NO-ON setter {method_name}() swallows write failures — "
                "needs a reportWriteFailure branch (Bug Pattern #29)"
            ),
            path=path,
            rel_base=rel_base,
            lineno=def_line_idx + 1,
            lines=raw_lines,
            why=(
                f"``{method_name}()`` is marked ``// BP24: NO-ON`` and issues a bypassV2 "
                "write checked with ``if (httpOk(resp))`` but has no failure branch. A "
                "genuine cloud failure — and the expected device-off (11005000) rejection — "
                "produces ZERO user feedback: no WARN, no ERROR, no diagnostics record. This "
                "is the silent-swallow shape that LevoitVital200S.setLightDetection shipped "
                "with through v2.9 (BP29 class-completion)."
            ),
            fix=(
                "Add an else branch routing the failure through reportWriteFailure, e.g.\n"
                "    if (httpOk(resp)) { ...success... }\n"
                "    else { reportWriteFailure(\"<descriptive tag> failed\", resp, "
                "[method:\"<cloudMethod>\"]) }\n"
                "reportWriteFailure downgrades the device-off case to a single WARN and "
                "logs/records genuine faults — leak-free per-call (no cross-call sentinel)."
            ),
        ))

    return findings


ALL_RULES = [check_rule46_noon_write_feedback]
