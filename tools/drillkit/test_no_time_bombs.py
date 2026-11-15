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
"""Guard: a console test that writes a dated folder for a file connector must not age out (docs/architecture/TEST_HARNESS.md, "Time bombs").

The file connector serves the newest day on or before the date asked, within ``lookback-days`` (default 10). A fixture that writes a
folder named with a literal date and lets the console ask for "today" passes for ten days and then fails with DRS-1001. A test file
that builds such a folder from a literal date must therefore do one of three things:

* derive the date from today (``datetime.date.today() - datetime.timedelta(days=5)``, so it stays inside the lookback);
* declare the fixture's connector with ``lookback-days`` (a fixed-date lake that is always read as "latest");
* be listed in ``ALLOWED`` below with the reason it cannot age out (it always passes an explicit date, or reads a lake the test itself dates).

Dates that are fixed by design (golden data, loader tests that pass an explicit date, path strings in fake responses) are not matched:
only a pathlib join or a ``mkdir`` with a literal date, in a console test file.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / "drishti-console" / "tests"

#: a day folder built from a literal date: ``root / "2026-10-08"`` or ``.mkdir`` on a path that names one
_DAY_FOLDER = re.compile(r'/\s*"20\d\d-\d\d-\d\d"|mkdir[^#\n]*20\d\d-\d\d-\d\d')

#: file -> why a literal day folder there cannot age out
ALLOWED: dict[str, str] = {
    "test_bi_wave_b_browser.py": "appends to the lake the w1 stack writes (test_w1_model_designer_browser.py), whose file connectors set lookback-days",
}


def offenders() -> list[str]:
    out: list[str] = []
    for f in sorted(TESTS.glob("*.py")):
        if f.name in ALLOWED:
            continue
        text = f.read_text(encoding="utf-8", errors="replace")
        code = "\n".join(line.split("  #")[0] for line in text.splitlines())
        if _DAY_FOLDER.search(code) and "lookback-days" not in text:
            out.append(f.name)
    return out


class NoTimeBombs(unittest.TestCase):
    def test_a_literal_day_folder_is_derived_from_today_or_read_without_a_lookback(self):
        bad = offenders()
        self.assertEqual([], bad, "console tests that write a dated folder from a literal date and never set lookback-days: derive the "
                                  "date from today (five days back) or set lookback-days on the connector, or list the file in ALLOWED with the reason")

    def test_every_allowed_file_exists_and_still_needs_listing(self):
        for name in ALLOWED:
            self.assertTrue((TESTS / name).is_file(), f"{name} is listed in ALLOWED but does not exist")
            self.assertTrue(ALLOWED[name].strip(), f"{name}: the reason is empty")

    def test_the_pattern_matches_what_it_guards(self):
        self.assertTrue(_DAY_FOLDER.search('lake = work / "lake" / "2026-10-12"'))
        self.assertTrue(_DAY_FOLDER.search('(Path(d) / "risk" / "2026-11-14").mkdir(parents=True)'))
        self.assertIsNone(_DAY_FOLDER.search('"failed/2026-11-03/r/" + body["file"]'))
        self.assertIsNone(_DAY_FOLDER.search('day = root / (datetime.date.today() - datetime.timedelta(days=5)).isoformat()'))


if __name__ == "__main__":
    unittest.main()
