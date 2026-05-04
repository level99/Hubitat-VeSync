#!/usr/bin/env -S uv run --python 3.12
"""Build the Hubitat HPM bundle ZIP for the Levoit libraries package.

Produces bundles/levoit_libraries.zip with the layout Hubitat expects:

  level99.LevoitChildBase.groovy        <- renamed copy of LevoitChildBaseLib.groovy
  level99.LevoitDiagnostics.groovy      <- renamed copy of LevoitDiagnosticsLib.groovy
  level99.LevoitCorePurifier.groovy     <- renamed copy of LevoitCorePurifierLib.groovy
  level99.LevoitVitalPurifier.groovy    <- renamed copy of LevoitVitalPurifierLib.groovy
  level99.LevoitHumidifier.groovy       <- renamed copy of LevoitHumidifierBaseLib.groovy
  install.txt                           <- bundle install manifest
  update.txt                            <- bundle update manifest (same content)

The install/update text files declare:

  <namespace>
  <bundle_name>
  library <namespace>.<libName>.groovy
  ...

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
DRIVERS_DIR = REPO_ROOT / "Drivers" / "Levoit"
OUTPUT_DIR = REPO_ROOT / "bundles"
OUTPUT_ZIP = OUTPUT_DIR / "levoit_libraries.zip"

NAMESPACE = "level99"
BUNDLE_NAME = "levoit_libraries"

LIBS = [
    {
        "source": DRIVERS_DIR / "LevoitChildBaseLib.groovy",
        "dest":   f"{NAMESPACE}.LevoitChildBase.groovy",
    },
    {
        "source": DRIVERS_DIR / "LevoitDiagnosticsLib.groovy",
        "dest":   f"{NAMESPACE}.LevoitDiagnostics.groovy",
    },
    {
        "source": DRIVERS_DIR / "LevoitCorePurifierLib.groovy",
        "dest":   f"{NAMESPACE}.LevoitCorePurifier.groovy",
    },
    {
        "source": DRIVERS_DIR / "LevoitVitalPurifierLib.groovy",
        "dest":   f"{NAMESPACE}.LevoitVitalPurifier.groovy",
    },
    {
        "source": DRIVERS_DIR / "LevoitHumidifierBaseLib.groovy",
        "dest":   f"{NAMESPACE}.LevoitHumidifier.groovy",
    },
]


def main() -> int:
    for lib in LIBS:
        if not lib["source"].exists():
            print(f"ERROR: source library not found at {lib['source']}", file=sys.stderr)
            return 1

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    lib_lines = "\n".join(f"library {lib['dest']}" for lib in LIBS)
    manifest = f"{NAMESPACE}\n{BUNDLE_NAME}\n{lib_lines}\n"

    with zipfile.ZipFile(OUTPUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for lib in LIBS:
            zf.writestr(lib["dest"], lib["source"].read_bytes())
        zf.writestr("install.txt", manifest)
        zf.writestr("update.txt", manifest)

    size = OUTPUT_ZIP.stat().st_size
    print(f"Built {OUTPUT_ZIP.relative_to(REPO_ROOT)} ({size:,} bytes)")
    print("Contents:")
    with zipfile.ZipFile(OUTPUT_ZIP) as zf:
        for info in zf.infolist():
            print(f"  {info.filename:50s} {info.file_size:>8,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
