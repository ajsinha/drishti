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
"""What the console keeps of one person is never served to another (core/user_cache.py; docs/admin/USER_MANAGEMENT.md section 6). Two
people with different packs and roles ask the same pages at the same time (asyncio.gather over an httpx client on the console's ASGI app),
and each answer must equal the one that person gets alone, from an empty cache: the designer's Describe (its mnemonic table is read as
the person: their packs' overviews), Drishti Trading's landing figures (a role without raw sees TRADER_ID masked), and the same person on
two servers. Before 2.9.11.b the Describe table was one process-wide copy of the first person's packs, and the Trading caches were keyed
without the roles or the server."""
from __future__ import annotations

import asyncio

import httpx
import pytest

from conftest import CONSOLE, FakeBackend
from core import servers
from plugins.banking import trading_landing
from core.app import create_app
from core.auth import Identity
from core.config import load_settings
from trading_fake import TradingBackend

TRADING_ABOUT = {"product": "Drishti Trading", "tagline": "t", "edition": {"name": "trading", "title": "Drishti Trading"}}

#: two people's packs, each pack with a mnemonic only it has
PACKS = {"ann": [{"name": "finance", "active": True}], "bob": [{"name": "trading", "active": True}]}
OVERVIEWS = {"finance": {"name": "finance", "mnemonics": {"FINX": {"kind": "fin-thing", "label": "Finance thing"}}},
             "trading": {"name": "trading", "mnemonics": {"TRDX": {"kind": "trd-thing", "label": "Trading thing"}}}}


def _people(app):
    """A person per cookie ``user|role,role`` (roles ("*",) when none)."""
    async def current(cookie, _backend):
        user, _, roles = (cookie or "ann").partition("|")
        return Identity(user=user, display=user.title(), desk="", roles=tuple(roles.split(",")) if roles else ("*",))

    app.state.auth.current = current


def _client(app, cookie: str) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://console", cookies={app.state.auth.cookie: cookie})


def _describe_app(backend: FakeBackend):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.settings._data.setdefault("ui", {})["designer"] = True
    app.state.backend = backend
    _people(app)

    async def packs(ident=None):
        await asyncio.sleep(0.005)                               # interleave the two people's reads
        return PACKS.get(getattr(ident, "user", ""), [])

    async def overview(name, ident=None):
        await asyncio.sleep(0.005)
        return OVERVIEWS[name]

    async def designs(method, path, ident, body=None, **params):
        return {"id": "D-" + getattr(ident, "user", "")}

    async def view(*a, **k):
        return {"panels": [], "provenance": {}}

    backend.packs, backend.pack_overview, backend.designs, backend.view = packs, overview, designs, view
    return app


async def _describe(app, cookie: str, text: str) -> dict:
    async with _client(app, cookie) as c:
        r = await c.post("/designer/describe", json={"text": text})
        assert r.status_code == 200, r.text
        return r.json()


def test_describe_reads_each_persons_own_mnemonics_at_the_same_time():
    async def run():
        alone = {}
        for who, text in (("ann", "FINX F-1"), ("bob", "FINX F-1")):
            app = _describe_app(FakeBackend())                 # each alone, from an empty cache
            alone[who] = await _describe(app, who, text)
        assert alone["ann"]["tokens"] != alone["bob"]["tokens"], "the two people's tables must differ for this test to mean anything"
        app = _describe_app(FakeBackend())
        got = await asyncio.gather(*[_describe(app, who, "FINX F-1") for _ in range(6) for who in ("ann", "bob")])
        for i, answer in enumerate(got):
            assert answer["tokens"] == alone["ann" if i % 2 == 0 else "bob"]["tokens"], (i, answer)
    asyncio.run(run())


def _trading_app(backend):
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    app.state.editions.learn(TRADING_ABOUT)
    app.state.editions.instance.spec["trading"]["people"]["t.brennan"] = {"desk": "DESK-RATES", "role": "desk-head"}
    _people(app)

    async def settings(ident=None):
        return {}

    backend.settings = settings
    return app


async def _trading(app, cookie: str, part: str) -> dict:
    async with _client(app, cookie) as c:
        r = await c.get(f"/api/home/trading/{part}")
        assert r.status_code == 200, r.text
        return r.json()


@pytest.mark.parametrize("part", ["valuations", "traders"])
def test_trading_figures_follow_the_persons_roles_at_the_same_time(part):
    people = ("t.brennan|viewer", "t.brennan|author")                 # the same person, a role with raw and one without
    async def run():
        alone = {}
        for who in people:
            trading_landing.forget()
            alone[who] = await _trading(_trading_app(TradingBackend()), who, part)
            assert alone[who].get("available"), alone[who]
        assert alone[people[0]] != alone[people[1]], "a role without raw must see less for this test to mean anything"
        trading_landing.forget()
        app = _trading_app(TradingBackend())
        got = await asyncio.gather(*[_trading(app, who, part) for _ in range(4) for who in people])
        for i, answer in enumerate(got):
            assert answer == alone[people[i % 2]], (i, people[i % 2])
        trading_landing.forget()
    asyncio.run(run())


def test_the_same_person_on_two_servers_is_two_people():
    """The keys carry the server: what one server answered is never served for another (core.servers.CURRENT is the request's)."""
    class TwoServers(TradingBackend):
        async def search(self, q, ident=None):
            out = await super().search(q, ident)
            if servers.current() == "other" and q.split()[0].upper() == "TVAL":
                for r in out.get("rows") or []:
                    v = r.get("values") or {}
                    if "$.MTM" in v:
                        v["$.MTM"] = v["$.MTM"] * 2                           # the other server's figures differ
            return out

    async def on(server: str, backend):
        token = servers.CURRENT.set(server)
        try:
            me = Identity(user="t.brennan", display="T", desk="", roles=("*",))
            scope = trading_landing.Scope(kind="desk", id="DESK-RATES", books=["BOOK-RATES-1", "BOOK-RATES-2", "BOOK-RATES-3"])
            return trading_landing.public(await trading_landing.valuations(backend, me, scope, {}))
        finally:
            servers.CURRENT.reset(token)

    async def run():
        alone = {}
        for s in ("default", "other"):
            trading_landing.forget()
            alone[s] = await on(s, TwoServers())
        assert alone["default"] != alone["other"]
        trading_landing.forget()
        backend = TwoServers()
        got = await asyncio.gather(*[on(s, backend) for _ in range(3) for s in ("default", "other")])
        for i, answer in enumerate(got):
            assert answer == alone["default" if i % 2 == 0 else "other"], i
        trading_landing.forget()
    asyncio.run(run())
