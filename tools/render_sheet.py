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

"""Builds the client-side renderer's sheet and integrity record (docs/architecture/CLIENT_RENDERER.md section 13).

    python3 tools/render_sheet.py           # write static/render/<version>/drishti-render.css and integrity.json
    python3 tools/render_sheet.py --check   # exit 1 when either is stale (what the guard test does)

A released version's folder never changes: to change the library, copy the folder to a new version, bump VERSION in
drishti-console/core/render_dist.py, and run this.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "drishti-console"))
from core import render_dist as rd  # noqa: E402


def main(argv: list[str]) -> int:
    text, facts = rd.build_sheet()
    sheet = rd.FOLDER / rd.SHEET
    if "--check" in argv:
        rec = rd.record()
        have = (rd.FOLDER / rd.RECORD).read_text(encoding="utf-8") if (rd.FOLDER / rd.RECORD).exists() else ""
        if not sheet.exists() or sheet.read_text(encoding="utf-8") != text or have != rd.record_text(rec):
            print("the renderer's sheet or integrity record is stale: python3 tools/render_sheet.py")
            return 1
        print(f"up to date: drishti-render {rd.VERSION}, sheet {facts['sheet']}")
        return 0
    sheet.write_text(text, encoding="utf-8")
    rec = rd.record()
    (rd.FOLDER / rd.RECORD).write_text(rd.record_text(rec), encoding="utf-8")
    b = rec["bytes"]
    print(f"drishti-render {rd.VERSION}: js {b['js'] / 1024:.1f} KB ({b['js_gzip'] / 1024:.1f} KB gzip), css {b['css'] / 1024:.1f} KB "
          f"({facts['gzip'] / 1024:.1f} KB gzip), {len(facts['icons'])} glyphs, {len(rec['files'])} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
