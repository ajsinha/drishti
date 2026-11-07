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

"""The trader home (core/trader_home.py, routes/trading_routes.py; SHARED_SCREENS.md section 4) against the stand-in server of trading_fake.py:
every figure equals the server's own answer (the VaR engine's, the pivot engine's P&L), a person sees only their books (two people at the
same time, each answer equal to the one they get alone), a server tick reads again what was kept for that person only, "since my last
visit" follows their settings, the routes are the Trading lens's only, and the channel's trading:home opens the server's ticks."""
from __future__ import annotations

import asyncio

import httpx
import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core import channel, market_landing, trader_home, trading_landing
from core.app import create_app
from core.auth import Identity
from core.config import load_settings
from trading_fake import BOOK_VAR, TraderHomeBackend

TRADING_ABOUT = {"product": "Drishti Trading", "tagline": "t", "edition": {"name": "trading", "title": "Drishti Trading"}}
PEOPLE = {"ash": {"trader": "TRDR-ASHAH"}, "kim": {"trader": "TRDR-KMOREAU"}}


@pytest.fixture(autouse=True)
def _empty_caches():
    trading_landing.forget()
    market_landing.forget()
    yield
    trading_landing.forget()
    market_landing.forget()


def _app(backend, trading: bool = True):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    if trading:
        app.state.editions.learn(TRADING_ABOUT)
        app.state.editions.instance.spec["trading"]["people"].update(PEOPLE)
    store: dict[str, dict] = {}

    async def settings(ident=None):
        return dict(store.get(ident.user if ident else "", {}))

    async def patch_settings(body, ident=None):
        mine = store.setdefault(ident.user, {})
        mine.update({k: v for k, v in body.items() if v is not None})
        return mine

    async def current(cookie, _backend):
        if not cookie:
            return None                                      # not signed in
        return Identity(user=cookie, display=cookie.title(), desk="", roles=("*",))

    backend.settings, backend.patch_settings = settings, patch_settings
    app.state.auth.current = current
    app.state.store = store
    return app


def _client(app, user="ash") -> TestClient:
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, user)
    return c


def _home(c, **params) -> dict:
    r = c.get("/api/home/trading/home", params=params)
    assert r.status_code == 200, r.text
    return r.json()


def test_every_figure_is_the_servers_own_answer():
    backend = TraderHomeBackend()
    c = _client(_app(backend))
    h = _home(c)
    assert h["available"] and h["scope"]["id"] == "TRDR-ASHAH" and h["businessDate"] == "2026-09-30"
    me = Identity(user="ash", display="Ash", desk="", roles=("*",))
    union = asyncio.run(backend.var("GET", "/scope", me, scope="BOOK:BOOK-RATES-1 + BOOK:BOOK-RATES-2"))
    head = h["headline"]
    assert head["var"] == union["var"] == 8_000_000 and head["scope"] == "BOOK:BOOK-RATES-1 + BOOK:BOOK-RATES-2"   # the engine's VaR of the books together
    v = c.get("/api/home/trading/valuations").json()
    assert head["pnl"] == v["pnl"] == 480_000                                     # the pivot engine's P&L, the same as the Desk head's read
    assert head["biggest"]["label"] == "USD SOFR swaps 10Y" and head["biggest"]["componentVar"] == 2_310_000
    assert head["breaches"] == [] and head["near"] == ["BOOK-RATES-1"] and head["approvals"] == 0
    rows = {b["book"]: b for b in h["books"]}
    assert list(rows) == ["BOOK-RATES-1", "BOOK-RATES-2"]                            # only the person's books, in their order
    for book, (var, lim) in BOOK_VAR.items():
        if book in rows:
            assert rows[book]["var"] == var and rows[book]["limit"] == lim and rows[book]["limitUse"] == pytest.approx(var / lim)
    by_book = {b["book"]: b["pnl"] for b in v["byBook"]}
    assert rows["BOOK-RATES-1"]["pnl"] == by_book["BOOK-RATES-1"] == 220_000 and rows["BOOK-RATES-2"]["pnl"] == by_book["BOOK-RATES-2"] == 260_000
    assert rows["BOOK-RATES-1"]["state"] == "near" and rows["BOOK-RATES-2"]["state"] == "ok"
    tiles = {t["id"]: t for t in h["markets"]["tiles"]}
    assert set(tiles) == {"ois10y", "slope", "fxvol", "cds"} and tiles["ois10y"]["value"] is not None
    kinds = [f["kind"] for f in h["feed"]]
    assert kinds.count("trade") == 3 and kinds.count("limit") == 1                   # the day's trades of my books, BOOK-RATES-1 near its limit
    assert h["changes"]["counts"] == {"new": 1, "amended": 1, "cancelled": 1} and h["changes"]["pnl"] == 480_000
    assert not h["problems"], h["problems"]


def test_a_person_sees_only_their_books_even_at_the_same_time():
    def text(h):
        return str(h)

    async def ask(app, who):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://console", cookies={app.state.auth.cookie: who}) as c:
            r = await c.get("/api/home/trading/home")
            assert r.status_code == 200, r.text
            out = r.json()
            out.pop("at", None)
            return out

    async def run():
        alone = {}
        for who in ("ash", "kim"):
            trading_landing.forget()
            market_landing.forget()
            alone[who] = await ask(_app(TraderHomeBackend()), who)
        assert [b["book"] for b in alone["ash"]["books"]] == ["BOOK-RATES-1", "BOOK-RATES-2"]
        assert [b["book"] for b in alone["kim"]["books"]] == ["BOOK-RATES-3"]
        assert "BOOK-RATES-3" not in text(alone["ash"]) and "BOOK-RATES-1" not in text(alone["kim"]["books"])
        assert alone["kim"]["headline"]["breaches"] == ["BOOK-RATES-3"]              # 9.0m of 8m: over its limit
        trading_landing.forget()
        market_landing.forget()
        app = _app(TraderHomeBackend())
        got = await asyncio.gather(*[ask(app, who) for _ in range(3) for who in ("ash", "kim")])
        for i, answer in enumerate(got):
            assert answer == alone["ash" if i % 2 == 0 else "kim"], i
    asyncio.run(run())


def test_a_tick_reads_again_for_that_person_only():
    backend = TraderHomeBackend()
    app = _app(backend)
    ash, kim = _client(app, "ash"), _client(app, "kim")
    assert _home(ash)["headline"]["pnl"] == 480_000
    assert _home(kim)["books"][0]["var"] == 9_000_000

    def kept(user):
        return [k for k in list(trading_landing._CACHE._data) if user in k]
    assert kept("ash") and kept("kim")
    kims = kept("kim")
    backend.book_var["BOOK-RATES-1"] = (6_500_000, 7_400_000)
    fresh = _home(ash, fresh="1")
    assert fresh["books"][0]["var"] == 6_500_000 and fresh["books"][0]["state"] == "near"
    assert kept("kim") == kims                                            # kim's kept reads did not go with ash's tick
    assert trading_landing.forget_person(Identity(user="kim", display="Kim", desk="", roles=("*",))) == len(kims)
    assert not kept("kim") and kept("ash")


def test_since_my_last_visit_follows_the_persons_settings():
    app = _app(TraderHomeBackend())
    c = _client(app)
    first = c.post("/api/home/trading/visit").json()
    assert first["lastVisit"] is None and first["visit"]
    app.state.store["ash"]["traderHomeVisit"] = "2026-09-30T12:00:00+00:00"
    h = _home(c, since="visit")
    assert h["lastVisit"] == "2026-09-30T12:00:00+00:00" and h["changes"]["since"] == "visit"
    assert h["changes"]["counts"] == {"new": 1, "amended": 1, "cancelled": 0}          # 13:52 new and 12:10 amended; 11:41 was before the visit
    assert _home(c)["changes"]["counts"] == {"new": 1, "amended": 1, "cancelled": 1}   # since the morning: all three
    again = c.post("/api/home/trading/visit").json()
    assert again["lastVisit"] == "2026-09-30T12:00:00+00:00"
    assert app.state.store.get("kim") is None                                        # nobody else's settings


def test_the_routes_are_the_trading_lens_only():
    app = _app(TraderHomeBackend(), trading=False)
    c = _client(app)
    r = c.get("/api/home/trading/home")
    assert r.status_code == 404 and r.json()["code"] == "DRS-4023"
    assert c.post("/api/home/trading/visit").status_code == 404
    page = c.get("/home/trader", follow_redirects=False)
    assert page.status_code == 303 and "DRS-4023" in page.headers["location"]
    anon = TestClient(_app(TraderHomeBackend()))
    assert anon.get("/api/home/trading/home").status_code == 401
    assert anon.get("/home/trader", follow_redirects=False).headers["location"].startswith("/login")


def test_the_trader_home_page_and_home_draw_it():
    app = _app(TraderHomeBackend())
    c = _client(app)
    for path in ("/home/trader", "/", "/?landing=trader"):
        page = c.get(path).text
        assert 'data-trading-landing="trader"' in page and "data-trader-home" in page, path
        assert "/static/js/drishti_trader-home.js" in page and "data-tl-check" in page, path
        assert ">Trader home<" in page and ">Desk head<" in page, path
    desk = c.get("/?landing=desk").text
    assert "data-trader-home" not in desk and "data-tl-heat" in desk                     # the other landings keep their page


def test_the_channel_opens_the_servers_ticks_for_trading_home_only():
    backend = TraderHomeBackend()
    me = Identity(user="ash", display="Ash", desk="", roles=("*",))
    assert "trading:" in channel.UPSTREAMS
    s = channel.ChannelSession(backend, me, lambda e, d: d, limit=8)
    stream, is_view = s._open("trading:home", [])
    assert stream is not None and is_view is False

    async def first():
        return await stream.__anext__()
    assert asyncio.run(first()) == ("hello", '{"generation": "g1", "changed": []}')
    assert backend.live_paths == ["/trading/home/live"]
    assert s._open("trading:other", [])[0] is None
    with pytest.raises(ValueError):
        channel.register_upstream("view:", lambda *a: None)
    with pytest.raises(ValueError):
        channel.register_upstream("nocolon", lambda *a: None)


def test_pure_parts_keep_every_book_and_order_the_feed():
    scope = trading_landing.Scope(kind="trader", id="T", books=["B1", "B2"])
    rows = trader_home.books(scope, {"byBook": [{"book": "B1", "pnl": 5}]}, {"books": [{"book": "B2", "var": 3, "state": "breach"}]})
    assert [r["book"] for r in rows] == ["B1", "B2"] and rows[0]["var"] is None and rows[1]["pnl"] is None and rows[1]["state"] == "breach"
    feed = trader_home.feed({"rows": [{"trade": "X", "event": "new", "at": "2026-09-30T10:00:00Z", "time": "10:00", "book": "B1"}]}, rows,
                            {"items": [{"item": {"kind": "sutra", "id": "s1", "at": "2026-09-30T11:00:00Z"}, "label": "Screen"}]},
                            [{"id": "a1", "message": "Net above 1000", "raisedAt": "2026-09-30T12:00:00Z"}], "2026-09-30")
    assert [f["kind"] for f in feed] == ["alert", "approval", "trade", "limit"]
    assert len({f["id"] for f in feed}) == len(feed)
    assert trader_home.headline({}, {}, None)["approvals"] is None
