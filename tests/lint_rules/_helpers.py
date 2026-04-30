"""
_helpers.py — Shared utility functions for lint rule modules.

Keep this module dependency-free (stdlib only) so it can be imported by any
rule without pulling in third-party packages.
"""

import re


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
