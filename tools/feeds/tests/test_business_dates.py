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

"""A feed's dates are days of the business calendar (local time: filedate.business_today, the "not in the future" check), never the
UTC day: at 23:00 in New York it is already tomorrow in UTC, and a snapshot dated by its UTC download day was refused as "in the
future" (the funds feed, every evening). The clock is pinned at 2026-11-07 23:00 America/New_York = 2026-11-08 04:00 UTC."""
from __future__ import annotations

import importlib.util
import json
import os
import shutil
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "tools" / "lake"))
import filedate  # noqa: E402

EVENING_UTC = "2026-11-08T04:00:00Z"                     # 23:00 on 2026-11-07 in New York
EVENING = datetime(2026, 11, 8, 4, 0, tzinfo=timezone.utc).timestamp()
LOCAL_DAY = date(2026, 11, 7)


@pytest.fixture()
def new_york(monkeypatch):
    monkeypatch.setenv("TZ", "America/New_York")
    time.tzset()
    yield
    monkeypatch.undo()
    time.tzset()


def test_a_moment_is_dated_by_the_local_calendar(new_york):
    assert filedate.local_day(EVENING_UTC) == LOCAL_DAY                      # a cache's "fetched" stamp
    assert filedate.local_day(EVENING) == LOCAL_DAY                          # a file time
    assert filedate.local_day("2026-11-08T04:00:00") == LOCAL_DAY            # no offset: UTC, as the caches write
    assert filedate.local_day("2026-11-07") == LOCAL_DAY                     # a calendar date stays as it is
    assert filedate.parse(f"fund.{filedate.local_day(EVENING)}.jsonl.gz", today=LOCAL_DAY).error is None
    assert "in the future" in filedate.parse("fund.2026-11-08.jsonl.gz", today=LOCAL_DAY).error   # what the UTC day was refused for


def _funds_make():
    spec = importlib.util.spec_from_file_location("funds_make_dates", ROOT / "tools" / "packgen" / "funds" / "make.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_the_funds_snapshot_downloaded_in_the_evening_is_dated_today(new_york, tmp_path):
    cache = tmp_path / "cache"
    shutil.copytree(HERE / "fixtures" / "funds", cache)
    for z in (cache / "nport").glob("*_nport.zip"):
        os.utime(z, (EVENING, EVENING))
    make = _funds_make()
    assert make.retrieved_at(cache) == LOCAL_DAY.isoformat()                 # by its file time
    newest = sorted((cache / "nport").glob("*_nport.zip"))[-1]
    (cache / "cache-index.json").write_text(json.dumps({f"nport/{newest.name}": {"fetched": EVENING_UTC}}))
    assert make.retrieved_at(cache) == LOCAL_DAY.isoformat()                 # by its cache entry
