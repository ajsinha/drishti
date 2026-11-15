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

"""The installation's answer kept for drawing (core/server_facts.py, docs/architecture/THIN_CONSOLE_DESIGN.md 3.1, 3.2): asked only when the
server's stamp changed, with If-None-Match; swapped whole; never stale in silence (the last answer stays while the server is away, with the
reason said); a refused service identity is a problem in words; a menu item whose command is a pack's shows only while that pack loads."""
import asyncio

from core.server_facts import ServerFacts

ABOUT = {"version": "dev", "stamps": {"installation": "d1", "resolution": "r1", "packs": "1"}}
DOC = {"digest": "d1", "schema": 1, "commands": {"USEQ": {"pack": "us-equities", "module": "capital-markets", "loaded": False},
                                                 "TRD": {"pack": "trading", "module": "trading", "loaded": True}}}


class Facts(ServerFacts):
    """The store with the server's answers given by the test: each ask recorded with its headers."""

    def __init__(self, answers):
        super().__init__()
        self.answers = list(answers)
        self.asks = []

    async def get(self, url, headers):
        self.asks.append((url, dict(headers)))
        a = self.answers.pop(0)
        if isinstance(a, Exception):
            raise a
        return a


def follow(f, about=ABOUT):
    return asyncio.run(f.follow("default", about, "http://server:18480", {"Authorization": "Bearer svc"}))


def test_the_bootstrap_is_asked_only_when_the_installation_stamp_changes_and_revalidated_with_its_digest():
    f = Facts([(200, DOC), (304, None)])
    assert follow(f) is True
    assert f.asks == [("http://server:18480/api/v1/console/bootstrap", {"Authorization": "Bearer svc"})]
    assert follow(f) is False and len(f.asks) == 1, "an unchanged stamp: no call at all"
    changed = {**ABOUT, "stamps": {**ABOUT["stamps"], "installation": "d2"}}
    assert follow(f, changed) is False and f.asks[1][1]["If-None-Match"] == '"d1"'
    assert f.answer("default").digest == "d1"
    assert f.stale("default"), "a 304 to a changed stamp: the answer is older than the stamp, and it says so"


def test_a_new_answer_is_swapped_whole():
    f = Facts([(200, DOC), (200, {**DOC, "digest": "d2", "commands": {}})])
    follow(f)
    first = f.answer("default")
    follow(f, {**ABOUT, "stamps": {**ABOUT["stamps"], "installation": "d2"}})
    second = f.answer("default")
    assert first is not second and first.doc["commands"] and second.doc["commands"] == {}
    assert f.stale("default") == ""


def test_while_the_server_is_away_the_last_answer_is_kept_and_the_notice_says_why():
    f = Facts([(200, DOC), OSError("refused")])
    follow(f)
    follow(f, {**ABOUT, "stamps": {**ABOUT["stamps"], "installation": "d9"}})
    assert f.answer("default").digest == "d1", "the last answer, never a file"
    assert "could not be read" in f.stale("default")
    assert f.health()[0]["digest"] == "d1" and f.health()[0]["problem"]


def test_a_refused_service_identity_is_said_in_words_and_an_old_server_is_named():
    f = Facts([(403, None)])
    follow(f)
    assert f.answer("default") is None and "service identity" in f.stale("default")
    old = Facts([])
    asyncio.run(old.follow("default", {"version": "2.9.11"}, "http://server:18480", {}))
    assert "older than this console" in old.stale("default") and old.asks == []


def test_a_pack_s_command_shows_in_the_menus_only_while_a_pack_that_has_it_loads():
    from types import SimpleNamespace

    from core.menus import _pack_command_on
    f = Facts([(200, DOC)])
    req = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(server_facts=f)))
    assert _pack_command_on(req, "USEQ AAPL") is True, "no answer yet: nothing is guessed"
    follow(f)
    assert _pack_command_on(req, "USEQ AAPL") is False, "the us-equities pack is not loaded"
    assert _pack_command_on(req, "TRD T-1") is True
    assert _pack_command_on(req, "DATAMAP") is True, "a word no pack names holds"


def test_the_version_people_see_is_the_server_s_and_the_console_s_build_is_only_a_diagnostic(client):
    """Owner rule (2026-11-15): the version and notice the pages show come from the server's /public/about; the console's own build is
    kept beside it on Admin -> Health (console build against server build), never shown as the product's version."""
    from core.app import ASSET_VERSION
    facts = client.app.state.server_facts
    asyncio.run(facts.follow("default", {"version": "9.9.9-server", "product": "Drishti"}, "http://unused", {}))
    try:
        page = client.get("/t").text
        footer = page[page.index('class="site-footer"'):]
        assert "data-server-version>9.9.9-server<" in footer
        assert ASSET_VERSION not in footer.replace(f"v={ASSET_VERSION}", ""), "the console's own build is not shown as the version"
        [h] = [x for x in facts.health() if x["server"] == "default"]
        assert h["serverBuild"] == "9.9.9-server" and h["consoleBuild"] == ASSET_VERSION and h["buildsDiffer"]
    finally:
        facts._held.pop("default", None)
