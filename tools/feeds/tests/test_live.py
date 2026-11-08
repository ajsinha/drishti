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

"""One live smoke per shipped feed, against its real source, for a person to run by hand (`--live`; never in a drill): a small pull into a
scratch data folder (DRISHTI_DATA_DIR under pytest's temporary folder), written as dated JSONL.gz, nothing loaded. Set
DRISHTI_FEEDS_USER_AGENT to your organisation and a team mailbox first (the SEC asks for a contact).

    python3 -m pytest tools/feeds/tests/test_live.py --live -k market-data
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parent / "sources")]
import feedbase as FB  # noqa: E402
import feedrunner as R  # noqa: E402

# per feed: the source's arguments that keep the smoke small (a feed not listed runs as configured)
SMALL = {
    "gleif": ["api", "--scope", "NAME:moody,CAP:5"],
    "us-equities": ["--scope", "AAPL"],
    "funds": ["--scope", "FUNDS:3,HOLDINGS:50"],
    "market-data": ["estr"],
    "world-economy": ["worldbank", "--no-imf", "--scope", "NY.GDP.MKTP.CD"],
    "news-and-markets": ["news", "--scope", "NEWS-TESLA"],
    "yahoo-prices": ["--scope", "AAPL"],
}
# per feed: options overridden as --set would, for the feeds whose selection is their options (the public-sector feeds)
SMALL_SETS = {
    "world-development": {"countries": ["CAN", "USA"], "years": 3, "indicators": [{"code": "SP.POP.TOTL", "field": "POPULATION", "unit": "persons"}]},
    "central-banks": {"history-days": 30, "series": [{"source": "boc", "code": "V39079", "bank": "BOC", "unit": "%"}]},
    "national-statistics": {"history-years": 1, "vectors": [{"vector": 2062815, "title": "Unemployment rate", "unit": "%"}]},
    "oecd": {"countries": ["CAN"], "start-period": "2026-01"},
    "official-news": {"keep-per-source": 3},
    "gdelt-events": {"backfill-days": 1, "rows-per-day": 5000},
}


@pytest.mark.live
@pytest.mark.parametrize("feed", sorted(s.name for s in FB.all_specs()))
def test_live_smoke(feed, tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    spec = FB.load_spec(feed)
    o = R.RunOptions(business_date=date.today() - timedelta(days=1), load="none", extra_args=SMALL.get(feed, []),
                     sets=SMALL_SETS.get(feed, {}))
    logs: list[str] = []
    rc, entry = R.run_feed(spec, o, log=logs.append)
    assert rc == 0, "\n".join(logs[-30:])
    assert entry["files"] >= 1 and entry["rows"] > 0, entry
