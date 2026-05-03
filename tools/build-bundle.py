#!/usr/bin/env -S uv run --python 3.12
"""Build the Hubitat HPM bundle ZIP for the Levoit libraries package.

Produces bundles/levoit_libraries.zip with the layout Hubitat expects:

  level99.LevoitDiagnostics.groovy   <- renamed copy of LevoitDiagnosticsLib.groovy
  install.txt                        <- bundle install manifest (3 lines)
  update.txt                         <- bundle update manifest (same content)

The install/update text files declare:

  <namespace>
  <bundle_name>
  library <namespace>.<libName>.groovy

When HPM installs the bundle, Hubitat extracts the .groovy files into the
Libraries Code section and registers them under the declared namespace + name,
making them resolvable via `#include level99.LevoitDiagnostics` in driver code.

The ZIP is uploaded as a GitHub Release Asset (NEVER committed to git — the
bundles/ directory is gitignored). For v2.4.2 the upload happens manually via
`gh release create`. For v2.5+, a GitHub Actions workflow on tag push will run
this script and attach the result.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SOURCE_LIB = REPO_ROOT / "Drivers" / "Levoit" / "LevoitDiagnosticsLib.groovy"
OUTPUT_DIR = REPO_ROOT / "bundles"
OUTPUT_ZIP = OUTPUT_DIR / "levoit_libraries.zip"

NAMESPACE = "level99"
BUNDLE_NAME = "levoit_libraries"
LIB_FILENAME = "level99.LevoitDiagnostics.groovy"

INSTALL_TXT = f"{NAMESPACE}\n{BUNDLE_NAME}\nlibrary {LIB_FILENAME}\n"


def main() -> int:
    if not SOURCE_LIB.exists():
        print(f"ERROR: source library not found at {SOURCE_LIB}", file=sys.stderr)
        return 1

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    lib_bytes = SOURCE_LIB.read_bytes()

    with zipfile.ZipFile(OUTPUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(LIB_FILENAME, lib_bytes)
        zf.writestr("install.txt", INSTALL_TXT)
        zf.writestr("update.txt", INSTALL_TXT)

    size = OUTPUT_ZIP.stat().st_size
    print(f"Built {OUTPUT_ZIP.relative_to(REPO_ROOT)} ({size:,} bytes)")
    print("Contents:")
    with zipfile.ZipFile(OUTPUT_ZIP) as zf:
        for info in zf.infolist():
            print(f"  {info.filename:40s} {info.file_size:>8,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
