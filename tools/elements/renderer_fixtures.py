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

"""The ViewModel fixtures of drishti-renderer's static harness, written from the parity suite's cases (one home: render_cases.py).

    python3 tools/elements/renderer_fixtures.py           # write static/elements/drishti-renderer/drishti_fixtures.json
    python3 tools/elements/renderer_fixtures.py --check   # exit 1 when it is stale
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "drishti-console" / "tests"))
OUT = ROOT / "drishti-console" / "web" / "static" / "elements" / "drishti-renderer" / "drishti_fixtures.json"
CASES = ("showcase", "more-shapes", "states", "links-and-tones", "grid-areas")


def text() -> str:
    import render_cases as cases  # noqa: E402 - the parity suite's ViewModels
    edges = cases.edge_cases()
    return json.dumps({name: edges[name] for name in CASES}, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n"


def main(argv: list[str]) -> int:
    want = text()
    if "--check" in argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != want:
            print("drishti-renderer's harness fixtures are stale: python3 tools/elements/renderer_fixtures.py")
            return 1
        return 0
    OUT.write_text(want, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(want) // 1024} KB, {len(CASES)} ViewModels)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
