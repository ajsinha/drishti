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

"""Drishti Trading's figures (core/trading_landing.py; EDITIONS.md › The design as built, section 14) against the stand-in server of trading_fake.py: "my" scope
(a mapping, a trader record, a choice, nobody), P&L today only from valuations on both dates, the pages of the server's search, today's
events, VaR and limits, the official limit use and P&L against VaR, by trader (masked for a role without raw), and the new-trade check
built from one VaR what-if and one pre-deal check."""
from __future__ import annotations

import asyncio
from datetime import date

import pytest

from core import trading_landing as tl
from core.auth import Identity
from core.edition import Edition
from trading_fake import TradingBackend

ED = Edition("trading", {"commands": {"allow": ["TRD"]}, "trading": {"people": {"drishti-dev-admin": {"trader": "TRDR-ASHAH"},
                                                                                "t.brennan": {"desk": "DESK-RATES", "role": "desk-head"}}}})


class Site:
    def __init__(self, values=None):
        self.values = values or {}

    def get(self, key, default=None):
        return self.values.get(key, default)


def who(user, roles=("viewer",), display=None):
    return Identity(user=user, display=display or user, desk="", roles=roles)


def run(coro):
    tl.forget()
    return asyncio.run(coro)


@pytest.fixture(autouse=True)
def _clean():
    tl.forget()
    yield
    tl.forget()


def test_the_mapping_comes_from_the_edition_then_the_console_wins():
    cfg = tl.config(ED, Site({"trading.people": {"DRISHTI-DEV-ADMIN": {"desk": "DESK-COMM"}}}))
    assert cfg["people"]["drishti-dev-admin"] == {"desk": "DESK-COMM"}                  # the console's own wins, any case
    assert cfg["people"]["t.brennan"]["role"] == "desk-head"
    assert tl._mapping({"trader": "TRDR-ASHAH"}) == ("trader", "TRDR-ASHAH", "trader")
    assert tl._mapping({"desk": "DESK-COMM", "role": "desk-head"}) == ("desk", "DESK-COMM", "desk-head")
    assert tl._mapping({"trader": "x' or 1=1"}) is None                                 # an id the search grammar could not hold safely
    assert tl.parse_view("desk:DESK-RATES") == ("desk", "DESK-RATES") and tl.parse_view("nobody:x") is None


def test_a_mapped_person_sees_their_traders_books_and_role():
    s = run(tl.resolve(TradingBackend(), who("drishti-dev-admin"), ED, Site(), {}))
    assert (s.kind, s.id, s.role, s.source) == ("trader", "TRDR-ASHAH", "trader", "config")
    assert s.books == ["BOOK-RATES-1", "BOOK-RATES-2"] and s.name == "A. Shah" and s.desk == "DESK-RATES"
    assert not s.can_pick                                                              # mapped, not an administrator
    head = run(tl.resolve(TradingBackend(), who("t.brennan"), ED, Site(), {"tradingView": "trader:TRDR-KMOREAU"}))
    assert (head.kind, head.id, head.role) == ("desk", "DESK-RATES", "desk-head") and head.head == "T. Brennan"   # a choice cannot override


def test_without_a_mapping_a_trader_record_then_the_remembered_choice():
    s = run(tl.resolve(TradingBackend(), who("kmoreau"), ED, Site(), {}))
    assert (s.kind, s.id, s.source) == ("trader", "TRDR-KMOREAU", "record") and s.books == ["BOOK-RATES-3"]
    by_name = run(tl.resolve(TradingBackend(), who("kim", display="K. Moreau"), ED, Site(), {}))
    assert by_name.id == "TRDR-KMOREAU"
    nobody = run(tl.resolve(TradingBackend(), who("zed"), ED, Site(), {}))
    assert nobody.kind == "" and nobody.can_pick
    chose = run(tl.resolve(TradingBackend(), who("zed"), ED, Site(), {"tradingView": "desk:DESK-RATES"}))
    assert (chose.kind, chose.id, chose.source, chose.role) == ("desk", "DESK-RATES", "choice", "desk-head") and chose.can_pick
    off = Edition("trading", {"trading": {"match-records": False}})
    assert run(tl.resolve(TradingBackend(), who("kmoreau"), off, Site(), {})).kind == ""


def test_an_administrator_may_always_pick_and_their_choice_wins():
    s = run(tl.resolve(TradingBackend(), who("drishti-dev-admin", roles=("admin",)), ED, Site(), {"tradingView": "desk:DESK-RATES"}))
    assert (s.kind, s.id, s.source) == ("desk", "DESK-RATES", "choice") and s.can_pick
    assert tl.trading_role(ED, Site(), who("drishti-dev-admin", roles=("admin",)), {"tradingView": "desk:DESK-RATES"}) == "desk-head"
    assert tl.trading_role(ED, Site(), who("t.brennan"), {}) == "desk-head"
    assert tl.trading_role(ED, Site(), who("drishti-dev-admin"), {}) == "trader"
    assert tl.trading_role(ED, Site(), who("zed"), {}) == ""


def _scope(user="drishti-dev-admin", roles=("viewer",), backend=None):
    b = backend or TradingBackend()
    return b, who(user, roles), run(tl.resolve(b, who(user, roles), ED, Site(), {}))


def test_pnl_today_is_the_mtm_change_of_trades_valued_on_both_dates():
    b, me, s = _scope()
    v = asyncio.run(tl.valuations(b, me, s, {}))
    assert v["businessDate"] == "2026-09-30" and v["previousDate"] == "2026-09-29" and v["hasPrevious"]
    assert v["pnl"] == 480_000 and v["valuedBoth"] == 8 and not v["partial"]
    assert {x["book"]: x["pnl"] for x in v["byBook"]} == {"BOOK-RATES-1": 220_000, "BOOK-RATES-2": 260_000}
    assert v["dv01"] == -48_000 and v["liveTrades"] == 8                                 # the live trades' DV01
    assert v["movers"][0]["trade"] == "MX-20000011" and v["movers"][0]["change"] == 110_000
    assert v["products"] == ["IR_SWAP", "SWAPTION"] and "CP-ALDERSHOT" in v["counterparties"]
    assert v["traderMasked"] and v["byTrader"] == []                                    # a viewer sees TRADER_ID masked
    days = {d for d, q in b.asked if isinstance(q, str) and q.startswith("TVAL")}
    assert days == {"live", "2026-09-29"}                                                # the request's own date box untouched


def test_no_valuations_the_day_before_gives_no_pnl_never_an_invented_one():
    b, me, s = _scope(backend=TradingBackend(previous=False))
    v = asyncio.run(tl.valuations(b, me, s, {}))
    assert v["pnl"] is None and not v["hasPrevious"] and v["movers"] == [] and v["byBook"][0]["pnl"] is None
    assert v["mtm"] is not None                                                         # today's MtM is still today's


def test_valuations_carried_from_an_earlier_day_are_no_days_pnl():
    b, me, s = _scope(backend=TradingBackend(carried="2026-09-28"))
    v = asyncio.run(tl.valuations(b, me, s, {}))
    assert v["businessDate"] == "2026-09-30" and v["dataDate"] == "2026-09-28" and v["previousDate"] == "2026-09-25"
    assert not v["hasPrevious"] and v["pnl"] is None and v["movers"] == []          # the day before holds the same day's data: no P&L
    out = asyncio.run(tl.blotter(b, me, s, {}))
    assert out["businessDate"] == "2026-09-28"                                         # today's trades: the valuations' day


def test_the_valuations_are_read_a_page_at_a_time(monkeypatch):
    monkeypatch.setattr(tl, "PAGE", 3)
    b = TradingBackend()
    me = who("t.brennan", roles=("admin",))
    s = run(tl.resolve(b, me, ED, Site(), {}))
    v = asyncio.run(tl.valuations(b, me, s, {}))
    assert v["trades"] == 12 and v["matched"] == 12 and not v["partial"] and v["pnl"] == 780_000
    assert sum(1 for d, q in b.asked if isinstance(q, str) and q.startswith("TVAL") and d == "live") == 6
    tl.forget()
    capped = asyncio.run(tl.valuations(b, me, s, {"max-rows": 1}))                       # at most PAGE rows: partial, and said
    assert capped["partial"] and capped["trades"] < 12


def test_todays_events_from_action_status_and_update_time():
    b, me, s = _scope()
    out = asyncio.run(tl.blotter(b, me, s, {}))
    assert out["counts"] == {"new": 1, "amended": 1, "cancelled": 1} and out["total"] == 3
    assert [r["trade"] for r in out["rows"]] == ["MX-20000001", "MX-20000002", "MX-20000004"]
    assert out["rows"][0]["time"] == "13:52" and out["rows"][0]["notional"] == 50_000_000
    q = [q for _d, q in b.asked if isinstance(q, str) and q.startswith("TRD")][0]
    assert "SOURCE_UPDATE_TIME startswith '2026-09-30'" in q and "PORTFOLIO = 'BOOK-RATES-1'" in q
    booked = tl.events({"rows": [{"ref": {"id": "X"}, "values": {"$.TRADE_ACTION": "AMEND", "$.TRADE_DATE": "2026-09-30"}}]}, date(2026, 9, 30))
    assert booked["counts"]["new"] == 1                                                  # booked on the day: new


def test_when_the_trades_cannot_answer_in_time_the_events_come_from_the_valuations():
    b, me, s = _scope()
    real = b.search

    async def slow(q, ident=None):
        if q.startswith("TRD "):
            raise tl.BackendError(504, "DRS-1004", "the server did not answer in time")
        return await real(q, ident)
    b.search = slow
    out = asyncio.run(tl.blotter(b, me, s, {}))
    assert out["how"] == "valuations" and "DRS-1004" in out["note"] and out["rows"] == []
    assert out["counts"] == {"new": 0, "amended": 0, "cancelled": 0}                  # the fake's statuses do not change overnight
    moved = tl.changes({"A": {"TRADE_STATUS": "CANCELLED"}, "B": {"TRADE_STATUS": "AMENDED"}, "C": {"TRADE_STATUS": "NEW"},
                        "D": {"TRADE_STATUS": "AMENDED"}},
                       {"A": {"TRADE_STATUS": "AMENDED"}, "B": {"TRADE_STATUS": "VALIDATED"}, "D": {"TRADE_STATUS": "AMENDED"}}, date(2026, 9, 30))
    assert moved["counts"] == {"new": 1, "amended": 1, "cancelled": 1}


def test_var_and_limits_are_the_engines_own():
    b, me, s = _scope()
    r = asyncio.run(tl.risk(b, me, s, {}))
    assert r["scope"] == "BOOK:BOOK-RATES-1 + BOOK:BOOK-RATES-2" and r["var"] == 8_000_000 and r["intraday"]["trades"] == 3
    assert r["worst"]["book"] == "BOOK-RATES-1" and r["worst"]["state"] == "near" and r["breaches"] == []
    assert r["positions"][0]["label"] == "USD SOFR swaps 10Y"
    b, me, d = _scope("t.brennan")
    r = asyncio.run(tl.risk(b, me, d, {}))
    assert r["scope"] == "DESK:DESK-RATES" and r["state"] == "ok" and [x["book"] for x in r["breaches"]] == ["BOOK-RATES-3"]
    assert [x["book"] for x in r["nearLimit"]] == ["BOOK-RATES-1"]
    assert asyncio.run(tl.risk(b, me, d, {"near-limit": 0.5}))["nearLimit"][1]["book"] == "BOOK-RATES-2"


def test_limit_use_by_book_from_the_official_records_with_the_missing_day_said():
    b, me, d = _scope("t.brennan")
    h = asyncio.run(tl.limits(b, me, d, {}))
    assert h["dates"] == ["2026-09-24", "2026-09-25", "2026-09-28", "2026-09-29", "2026-09-30"] and h["missingDates"] == ["2026-09-25"]
    three = h["books"][2]
    assert three["book"] == "BOOK-RATES-3" and three["cells"][1]["state"] == "missing" and three["cells"][-1]["state"] == "breach"
    assert three["cells"][-1]["limitUse"] == pytest.approx(9_000_000 * 0.975 / 8_000_000)


def test_pnl_against_var_only_from_an_official_history():
    b, me, d = _scope("t.brennan")
    p = asyncio.run(tl.pnl_vs_var(b, me, d, {}))
    assert p["available"] and p["days"] == 60 and p["exceptions"] == 1
    b2, me2, d2 = _scope("t.brennan", backend=TradingBackend(history="replay"))
    left = asyncio.run(tl.pnl_vs_var(b2, me2, d2, {}))
    assert not left["available"] and "replay" in left["reason"]
    _b, _m, mine = _scope()
    assert not asyncio.run(tl.pnl_vs_var(b, me, mine, {}))["available"]                 # a trader: a desk's card


def test_by_trader_needs_a_role_that_sees_trader_ids():
    b, me, d = _scope("t.brennan", roles=("admin",))
    t = asyncio.run(tl.traders(b, me, d, {}))
    rows = {r["trader"]: r for r in t["rows"]}
    assert rows["TRDR-ASHAH"]["pnl"] == 480_000 and rows["TRDR-ASHAH"]["trades"] == 8 and rows["TRDR-KMOREAU"]["trades"] == 3
    assert rows["TRDR-ASHAH"]["var"] == sum(1_000_000 + 100_000 * i for i in (0, 1, 3, 4, 6, 7, 9, 10))
    assert rows["TRDR-ASHAH"]["name"] == "A. Shah" and not t["traderMasked"]
    b, me, d = _scope("t.brennan")                                                       # a viewer: masked, the records' counts only
    t = asyncio.run(tl.traders(b, me, d, {}))
    assert t["traderMasked"] and "masked" in t["note"]
    assert all(r["pnl"] is None and r["var"] is None for r in t["rows"]) and {r["recordTrades"] for r in t["rows"]} == {8, 4}


def test_a_traders_desk_head_landing_reads_their_desk():
    b, me, s = _scope()
    d = asyncio.run(tl.desk_of(b, me, s))
    assert (d.kind, d.id, d.head) == ("desk", "DESK-RATES", "T. Brennan") and d.books == ["BOOK-RATES-1", "BOOK-RATES-2", "BOOK-RATES-3"]


def test_amounts_and_the_template_trade():
    assert tl.parse_amount("250m") == 250e6 and tl.parse_amount("1.5bn") == 1.5e9 and tl.parse_amount("400k") == 400e3
    assert tl.parse_amount("250,000,000") == 250e6 and tl.parse_amount("lots") is None
    rows = {t["TRADE_ID"]: t for t in (__import__("trading_fake").trade(i, "2026-09-30") for i in range(12))}
    tid, t = tl.template(rows, "IR_SWAP", "CP-ALDERSHOT", None, tl.LIVE)
    assert t["COUNTERPARTY"] == "CP-ALDERSHOT" and t["PRODUCT_TYPE"] == "IR_SWAP" and tid == "MX-20000010"   # the largest of its trades
    assert tl.template(rows, "CAP", "CP-ALDERSHOT", None, tl.LIVE) is None


def test_the_check_is_one_var_what_if_and_one_pre_deal_check():
    b, me, s = _scope()
    c = asyncio.run(tl.check(b, me, s, {}, {"product": "IR_SWAP", "notional": "250m", "counterparty": "cp-aldershot", "side": "S"}))
    assert c["ok"] and c["template"]["trade"] == "MX-20000010" and c["book"] == "BOOK-RATES-1"
    assert c["factor"] == pytest.approx(250e6 / 500e6)
    v, x = c["var"], c["exposure"]
    assert v["scope"] == "BOOK:BOOK-RATES-1" and v["before"] == 6_120_000 and v["after"] == 6_300_000 and v["state"] == "near"
    assert v["useAfter"] == pytest.approx(6_300_000 / 7_400_000)
    assert x["scope"] == "CPTY:CP-ALDERSHOT" and x["method"] == "paths" and x["nettingSet"] == "NS-ALDERSHOT-NY" and not x["proxy"]
    assert x["state"] == "near" and x["useAfter"] == pytest.approx(409_200_000 / 420_000_000) and x["confidence"] == 0.95
    calls = [a for a in b.asked if isinstance(a, tuple) and a[0] in ("var", "exposure") and a[1] == "POST"]
    assert len(calls) == 2                                                               # one call each
    whatif = calls[0][4] if calls[0][0] == "var" else calls[1][4]
    assert whatif["items"][0]["copyOf"] == "MX-20000010"
    sold = asyncio.run(tl.check(b, me, s, {}, {"product": "IR_SWAP", "notional": "250m", "counterparty": "CP-ALDERSHOT", "side": "B"}))
    assert sold["factor"] < 0                                                            # the other side: the copy's unwind direction


def test_the_check_falls_back_to_the_engines_proxy_and_says_so():
    b, me, s = _scope(backend=TradingBackend(paths=False))
    c = asyncio.run(tl.check(b, me, s, {}, {"product": "SWAPTION", "notional": "75m", "counterparty": "CP-ALDERSHOT"}))
    assert c["exposure"]["proxy"] and c["exposure"]["method"] == "addon" and "DRS-5446" in c["exposure"]["fallback"]
    n = asyncio.run(tl.check(b, me, s, {}, {"product": "IR_SWAP", "notional": "10m", "counterparty": "CP-NORTHPLAINS"}))
    assert n["exposure"]["state"] == "none" and n["exposure"]["proxy"]                     # no limit, no netting set: the add-on, said
    bad = asyncio.run(tl.check(b, me, s, {}, {"product": "IR_SWAP", "notional": "lots", "counterparty": "CP-ALDERSHOT"}))
    assert not bad["ok"] and "notional" in bad["reason"]
    none = asyncio.run(tl.check(b, me, s, {}, {"product": "CAP", "notional": "10m", "counterparty": "CP-ALDERSHOT"}))
    assert not none["ok"] and "copies a booked trade" in none["reason"]
