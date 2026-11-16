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
"""What the console keeps of one person is never served to another (core/user_cache.py; docs/guides/ADMIN_GUIDE.md section 6). Two
people with different packs and roles ask the same pages at the same time (asyncio.gather over an httpx client on the console's ASGI app),
and each answer must equal the one that person gets alone, from an empty cache: the designer's Describe (its mnemonic table is read as
the person: their packs' overviews), Drishti Trading's landing figures (a role without raw sees TRADER_ID masked), and the same person on
two servers. Before 2.9.11.b the Describe table was one process-wide copy of the first person's packs, and the Trading caches were keyed
without the roles or the server."""
from __future__ import annotations

import server_answers
import asyncio

import httpx
import pytest

from conftest import CONSOLE, FakeBackend
from core import servers
from core.app import create_app
from core.auth import Identity
from core.config import load_settings

FULL_ABOUT = {"product": "Drishti", "edition": None, "lenses": ["trading"]}   # a full instance offering the Trading workspace

#: two people's packs, each pack with a mnemonic only it has
PACKS = {"ann": [{"name": "finance", "active": True}], "bob": [{"name": "trading", "active": True}]}
OVERVIEWS = {"finance": {"name": "finance", "mnemonics": {"FINX": {"kind": "fin-thing", "label": "Finance thing"}}},
             "trading": {"name": "trading", "mnemonics": {"TRDX": {"kind": "trd-thing", "label": "Trading thing"}}}}


def _people(app):
    """A person per cookie ``user|role,role`` (roles ("*",) when none)."""
    async def current(cookie, _backend):
        user, _, roles = (cookie or "ann").partition("|")
        return Identity(user=user, display=user.title(), desk="", roles=tuple(roles.split(",")) if roles else ("*",),
                        admin=not roles or bool({"*", "admin"} & set(roles.split(","))))

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


