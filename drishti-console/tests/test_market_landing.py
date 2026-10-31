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

"""Drishti Market's figures and checks (core/market_landing.py, EDITIONS.md › The design as built, section 13) over the stand-in market data with known problems
(market_fake.py): the tiles' day-over-day changes only where both days hold real data, the USD curves with the week-ago line, the biggest
moves in sigma or none at all, and each quality check, by severity, with the clean share."""
import asyncio
from datetime import date

import pytest

from core import market_landing as ml
from market_fake import MarketBackend


@pytest.fixture(autouse=True)
def _fresh():
    ml.forget()
    yield
    ml.forget()


def run(coro):
    return asyncio.run(coro)


def test_business_days_skip_weekends_and_holidays():
    hol = {"2026-10-12"}
    assert ml.previous_business_day(date(2026, 9, 28), set()) == date(2026, 9, 25)                     # a Monday: the Friday before
    assert ml.previous_business_day(date(2026, 10, 13), hol) == date(2026, 10, 9)                      # over a holiday Monday
    assert ml.business_days_between(date(2026, 9, 24), date(2026, 9, 30), set()) == 4
    assert ml.business_days_between(date(2026, 9, 30), date(2026, 9, 30), set()) == 0
    assert ml.tenor_years("6M") == 0.5 and ml.tenor_years("10Y") == 10 and ml.tenor_years("X6") is None


def test_a_change_needs_real_data_on_both_days():
    s = {"points": [{"date": "2026-09-29", "value": 1.0, "dataDate": "2026-09-29"}, {"date": "2026-09-30", "value": 1.5, "dataDate": "2026-09-30"}]}
    assert ml.day_change(s, set())["delta"] == 0.5
    carried = {"points": [{"date": "2026-09-29", "value": 1.0, "dataDate": "2026-09-28"}, {"date": "2026-09-30", "value": 1.5, "dataDate": "2026-09-30"}]}
    assert ml.day_change(carried, set()) is None                          # the day before is carried from an earlier day
    gap = {"points": [{"date": "2026-09-28", "value": 1.0, "dataDate": "2026-09-28"}, {"date": "2026-09-30", "value": 1.5, "dataDate": "2026-09-30"}]}
    assert ml.day_change(gap, set()) is None                              # not the business day before
    assert ml.day_change(None, set()) is None


def test_the_desk_tiles_and_curves_come_from_the_data():
    d = run(ml.desk(MarketBackend(), None))
    tiles = {t["id"]: t for t in d["tiles"]}
    assert d["businessDate"] == "2026-09-30" and d["dataDate"] == "2026-09-30" and d["curves"] == 28 and d["kinds"] == 20
    assert tiles["ois10y"]["value"] == pytest.approx(3.926491, abs=1e-4)
    assert tiles["ois10y"]["change"] == {"delta": pytest.approx(-4.0), "since": "2026-09-29"}
    assert tiles["slope"]["value"] == 46.0 and tiles["slope"]["change"]["delta"] == pytest.approx(1.0)
    assert tiles["fxvol"]["value"] == 7.8 and tiles["fxvol"]["change"] is None                          # no history the day before
    assert tiles["cds"]["value"] == 70.0 and tiles["cds"]["change"]["delta"] == pytest.approx(1.0)      # the median of three names
    assert tiles["cds"]["sub"] == "CDS · 3 names"
    c = d["curve"]
    assert [p["tenor"] for p in c["ois"]][:3] == ["1M", "3M", "6M"] and len(c["projection"]) == 12
    assert c["weekAgo"]["date"] == "2026-09-23" and c["weekAgo"]["points"][0]["rate"] == pytest.approx(c["ois"][0]["rate"] + 0.05)


def test_the_desk_without_history_shows_no_change_and_no_week_ago():
    d = run(ml.desk(MarketBackend(history=False), None))
    assert all(t["change"] is None for t in d["tiles"])
    assert d["curve"]["weekAgo"] is None and d["curve"]["projection"]


def test_biggest_moves_in_sigma_of_their_own_history():
    m = run(ml.moves(MarketBackend(), None))
    assert m["mode"] == "sigma"
    top = m["items"][0]
    assert top["id"] == "CRV-EUR-OIS" and top["unit"] == "bp" and top["move"] == pytest.approx(31.0, abs=1.1) and top["sigma"] > 3
    assert top["date"] == "2026-09-30" and top["since"] == "2026-09-29" and top["days"] >= ml.SIGMA_MIN_DAYS
    assert all(i["cmd"].startswith(("CRV ", "CDS ")) for i in m["items"])


def test_biggest_moves_are_none_without_history():
    m = run(ml.moves(MarketBackend(history=False), None))
    assert m["mode"] == "none" and m["items"] == []


def test_the_quality_checks_find_each_known_problem():
    q = run(ml.quality(MarketBackend(), None, 2))
    assert q["businessDate"] == "2026-09-30" and q["staleDays"] == 2
    crit = {(e["id"], e["check"]) for e in q["critical"]}
    warn = {(e["id"], e["check"]) for e in q["warnings"]}
    assert crit == {("CRV-EUR-OIS", "discount factor"), ("FXV-USDTRY", "calendar spread"), ("EQV-ZERO", "negative vol")}
    assert warn == {("CRV-GBP-OIS", "stale"), ("INF-UKRPI", "stale"), ("CRV-AUD-PROJ", "gap"), ("CRV-EUR-OIS", "negative forward")}
    by = {(e["id"], e["check"]): e for e in q["critical"] + q["warnings"]}
    assert by[("CRV-GBP-OIS", "stale")]["detail"] == "as of 2026-09-24, 4 business days old"
    assert "latest INF" in by[("INF-UKRPI", "stale")]["detail"]
    assert by[("CRV-AUD-PROJ", "gap")]["detail"].startswith("15Y missing")
    assert by[("CRV-EUR-OIS", "discount factor")]["detail"] == "does not fall at 30Y"
    assert by[("FXV-USDTRY", "calendar spread")]["detail"] == "total variance falls 3M to 6M"
    assert by[("CRV-EUR-OIS", "negative forward")]["cmd"] == "CRV CRV-EUR-OIS"
    assert q["checked"] == 14 and q["clean"] == 8 and q["cleanPct"] == pytest.approx(57.1)
    assert {c["id"]: c["flagged"] for c in q["checks"]} == {"stale": 2, "gap": 1, "forwards": 1, "surface": 2}
    assert q["critical"][0]["severity"] == "critical"


def test_the_stale_threshold_is_the_editions():
    q = run(ml.quality(MarketBackend(), None, 4))
    assert not [e for e in q["warnings"] if e["check"] == "stale"]                                     # four business days is not more than 4
    ml.forget()
    q = run(ml.quality(MarketBackend(), None, 0))
    assert {e["id"] for e in q["warnings"] if e["check"] == "stale"} == {"CRV-GBP-OIS", "INF-UKRPI"}


def test_a_clean_book_of_curves_and_surfaces_has_no_exceptions():
    docs = {"ir-curve": {"CRV-USD-OIS": {"provenance": {"businessDate": "2026-09-30"},
                                         "data": {"AS_OF": "2026-09-30", "CURVE_TYPE": "OIS",
                                                  "POINTS": [{"TENOR": "1Y", "DF": 0.97}, {"TENOR": "2Y", "DF": 0.93}],
                                                  "FORWARDS": [{"TENOR": "1Y", "RATE": 3.1}]}}}}
    out = ml.run_checks(docs, {}, date(2026, 9, 30), set(), 2)
    assert out["critical"] == [] and out["warnings"] == [] and out["checked"] == 1 and out["cleanPct"] == 100.0
