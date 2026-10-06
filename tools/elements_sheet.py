#!/usr/bin/env python3
# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""Builds the stylesheet of <drishti-view> (docs/architecture/ELEMENTS.md, step 8).

    python3 tools/elements_sheet.py           # write drishti-console/web/elements/drishti-view.css and its manifest
    python3 tools/elements_sheet.py --check   # exit 1 when the committed files are stale (what the guard test does)
    python3 tools/elements_sheet.py --icons   # list the glyphs the macros use and where

Run it after changing a console sheet (tokens, theme, terminal, layout, gradients), a macro in templates/_macros/ or an
icon in a root-scoped script. Reproducible: the same sources give the same bytes, and the version is a hash of them.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drishti-console"))
from core import element_sheet as es  # noqa: E402


def main(argv: list[str]) -> int:
    text, manifest = es.build()
    if "--icons" in argv:
        for name, files in es.icons_used().items():
            print(f"bi-{name}: {', '.join(files)}")
        return 0
    if "--check" in argv:
        try:
            have, have_manifest = es.committed()
        except FileNotFoundError:
            print("the element sheet has not been built: python3 tools/elements_sheet.py")
            return 1
        if have != text or have_manifest != manifest:
            print("the element sheet is stale: python3 tools/elements_sheet.py")
            return 1
        print(f"up to date: {manifest['version']}")
        return 0
    es.OUT_DIR.mkdir(parents=True, exist_ok=True)
    (es.OUT_DIR / es.SHEET_FILE).write_text(text, encoding="utf-8")
    (es.OUT_DIR / es.MANIFEST_FILE).write_text(es.manifest_text(manifest), encoding="utf-8")
    flag = "" if manifest["gzip"] <= es.GZIP_TARGET else f"  OVER the {es.GZIP_TARGET // 1024} KB gzip target"
    print(f"{es.SHEET_FILE} {manifest['version']}: {manifest['bytes'] / 1024:.1f} KB, {manifest['gzip'] / 1024:.1f} KB gzip, "
          f"{len(manifest['icons'])} glyphs{flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
