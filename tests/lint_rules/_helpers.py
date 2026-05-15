"""
_helpers.py — Shared utility functions for lint rule modules.

Keep this module dependency-free (stdlib only) so it can be imported by any
rule without pulling in third-party packages.
"""

import re
from pathlib import Path


# ---------------------------------------------------------------------------
# Finding builder — REQUIRED for all lint rules
# ---------------------------------------------------------------------------

def make_finding(severity, rule_id, title, file_rel, lineno, raw_lines, why, fix):
    """
    Build a finding dict with all required keys.

    Parameters
    ----------
    severity : str
        'FAIL' or 'WARN'.  lint.py counts exit-status by this key — omitting it
        produces a silently non-gating rule (false-green ``lint --strict``).
    rule_id : str
        Machine-readable rule identifier, e.g. ``'RULE33_case_sensitivity'``.
    title : str
        Short human-readable description shown in lint output.
    file_rel : str
        Repo-relative file path with forward slashes, e.g.
        ``'Drivers/Levoit/LevoitVital200S.groovy'``.
    lineno : int
        1-based line number of the finding.
    raw_lines : list[str]
        The file's raw source lines (as returned by ``path.read_text().splitlines()``).
        Used to build the context snippet; the list is never mutated.
    why : str
        One-sentence (or short paragraph) rationale — explains WHY this is wrong.
    fix : str
        Suggested corrective action — shown verbatim in lint output.

    Returns
    -------
    dict
        Keys: severity, rule_id, title, file, line, context, why, fix.
    """
    start = max(0, lineno - 2)
    end = min(len(raw_lines), lineno + 1)
    context = '\n'.join(f"    {raw_lines[i]}" for i in range(start, end))
    return {
        'severity': severity,
        'rule_id': rule_id,
        'title': title,
        'file': file_rel,
        'line': lineno,
        'context': context,
        'why': why,
        'fix': fix,
    }


# ---------------------------------------------------------------------------
# Library-file detection
# ---------------------------------------------------------------------------

# Hubitat library files use `library(...)` instead of `definition(...)`.
# A file is a library if it contains a top-level `library(` block.
LIBRARY_BLOCK_RE = re.compile(r'^\s*library\s*\(', re.MULTILINE)


def is_library_file(source_text: str) -> bool:
    """
    Returns True if *source_text* is a Hubitat library (uses ``library()``
    instead of ``definition()``).

    Use this to skip library files in rules that only apply to driver files.
    The canonical example is ``LevoitDiagnosticsLib.groovy`` — it lives under
    ``Drivers/Levoit/`` but is not a device driver and should not be checked by
    driver-level structural rules (RULE25 watchdog, RULE28 diagnostics presence,
    RULE18 manifest drivers-key check, RULE19 readme doc-sync).
    """
    return bool(LIBRARY_BLOCK_RE.search(source_text))


# ---------------------------------------------------------------------------
# #include directive resolution
# ---------------------------------------------------------------------------

# Matches `#include level99.LibraryName` at start of a line.
_INCLUDE_PATTERN = re.compile(r'^#include\s+([\w.]+)\s*$', re.MULTILINE)

# Module-level cache keyed by resolved Path so each lib file is read at most
# once per lint process invocation.
_lib_text_cache: dict = {}


def resolve_lib_path(ns_and_name: str, driver_path: Path) -> "Path | None":
    """
    Resolve a ``#include level99.<LibName>`` directive to the library file on disk.

    Convention: namespace ``level99``, library name ``LevoitChildBase`` ->
    ``Drivers/Levoit/LevoitChildBaseLib.groovy`` (sibling of the driver file).

    Returns None if the file cannot be found.
    """
    parts = ns_and_name.split('.', 1)
    if len(parts) != 2 or parts[0] != 'level99':
        return None
    lib_name = parts[1]
    lib_filename = f"{lib_name}Lib.groovy"
    candidate = driver_path.parent / lib_filename
    if candidate.exists():
        return candidate
    return None


def included_lib_texts(raw_text: str, driver_path: Path) -> list:
    """
    Return the raw text of every library file ``#include``'d by *raw_text*.

    Results are cached per resolved path; missing/unresolvable includes are
    silently skipped.  The returned list contains one string per resolved lib.
    """
    texts = []
    for m in _INCLUDE_PATTERN.finditer(raw_text):
        ns_and_name = m.group(1)
        lib_path = resolve_lib_path(ns_and_name, driver_path)
        if lib_path is None:
            continue
        if lib_path not in _lib_text_cache:
            try:
                _lib_text_cache[lib_path] = lib_path.read_text(encoding='utf-8')
            except OSError:
                _lib_text_cache[lib_path] = ''
        text = _lib_text_cache[lib_path]
        if text:
            texts.append(text)
    return texts


def included_lib_files(raw_text: str, driver_path: Path) -> list:
    """
    Return ``(lib_path, lib_text)`` pairs for every library file
    ``#include``'d by *raw_text*.

    Like ``included_lib_texts()`` but preserves the resolved ``Path`` for
    each library so callers can attribute findings to the correct source file
    rather than the driver that includes it.

    Results share the same module-level ``_lib_text_cache`` as
    ``included_lib_texts()`` — each library file is read at most once per
    lint invocation regardless of which helper is called first.

    Missing/unresolvable includes are silently skipped.
    """
    pairs = []
    for m in _INCLUDE_PATTERN.finditer(raw_text):
        ns_and_name = m.group(1)
        lib_path = resolve_lib_path(ns_and_name, driver_path)
        if lib_path is None:
            continue
        if lib_path not in _lib_text_cache:
            try:
                _lib_text_cache[lib_path] = lib_path.read_text(encoding='utf-8')
            except OSError:
                _lib_text_cache[lib_path] = ''
        text = _lib_text_cache[lib_path]
        if text:
            pairs.append((lib_path, text))
    return pairs
