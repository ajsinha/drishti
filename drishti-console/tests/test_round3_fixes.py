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

"""QA round 3, the parts that need no browser: a failed business-date call is never kept (the sign-in page has no identity, and must not leave the date box empty for the
next minute), the Tutorials card counts the written tutorials, a SQL promote gets a readable id, and a value that rounds to zero is not signed."""
import asyncio
import json
import shutil
import subprocess
from pathlib import Path

import pytest

from core.asof import BusinessDates
from core.guides import tutorial_count_word

CONSOLE = Path(__file__).resolve().parents[1]


class Backend:
    def __init__(self):
        self.calls = 0

    async def business_date(self, ident=None):
        self.calls += 1
        if ident is None:
            raise RuntimeError("DRS-5010 missing bearer token")
        return {"current": "2026-10-09", "selected": "2026-10-09", "live": True, "holidays": []}


def test_a_failed_business_date_call_is_not_cached_for_the_next_person():
    dates, backend = BusinessDates(), Backend()
    assert asyncio.run(dates.info(backend, None, "live"))["current"] is None            # the sign-in page: nobody yet
    assert asyncio.run(dates.info(backend, object(), "live"))["current"] == "2026-10-09"  # the next page, signed in, is not told the failure again
    assert asyncio.run(dates.info(backend, object(), "live"))["current"] == "2026-10-09"
    assert backend.calls == 2                                                            # the good answer is cached, the failure was not


def test_the_tutorials_card_counts_the_tutorials_it_finds(tmp_path):
    (tmp_path / "tutorials").mkdir()
    for n in range(1, 4):
        (tmp_path / "tutorials" / f"{n:02d}-x.md").write_text("# x")
    (tmp_path / "tutorials" / "README.md").write_text("# index")
    assert tutorial_count_word(tmp_path) == "Three"
    assert tutorial_count_word(CONSOLE.parent / "docs") == "Twenty-seven"


@pytest.mark.skipif(not shutil.which("node"), reason="needs node")
def test_a_value_that_rounds_to_zero_is_never_signed():
    script = ("const e=require(process.argv[1]);"
              "console.log(JSON.stringify([e.format(-0.001,'signed2'),e.format(-0.004,null),e.format(-1.5,'signed2'),e.format(0.001,'signed2'),e.format(-3,'amount0')]))")
    out = subprocess.run(["node", "-e", script, str(CONSOLE / "web/static/js/pivot-engine.js")], capture_output=True, text=True, check=True).stdout
    assert json.loads(out) == ["0.00", "0.00", "−1.50", "0.00", "−3"]
