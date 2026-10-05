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

"""Ask about this page in the console (docs/architecture/CONTEXT_HELP.md, Optional: Ask): the box is drawn only when the server says Ask is on
for the page's pack, the console only proxies the question, and a problem keeps the server's code so the drawer can say what happened."""
import json

from conftest import FIXTURES
from core.backend import BackendError


def _explain_with(monkeypatch, backend, ask):
    ex = json.loads((FIXTURES / "explain_trade_IRS-48213.json").read_text())
    if ask is not None:
        ex["ask"] = ask

    async def explain(kind, id_, user, generation=None, locale=None, accept_language=None):
        return ex
    monkeypatch.setattr(backend, "explain", explain)


def test_the_box_is_not_drawn_unless_the_server_says_ask_is_on(client, backend, monkeypatch):
    assert "data-about-ask" not in client.get("/v/trade/IRS-48213/about").text            # the fixture says nothing: off by default
    _explain_with(monkeypatch, backend, {"enabled": False})
    assert "data-about-ask" not in client.get("/v/trade/IRS-48213/about").text
    _explain_with(monkeypatch, backend, {"enabled": True})
    h = client.get("/v/trade/IRS-48213/about").text
    assert "data-about-ask" in h and 'data-ask-url="/v/trade/IRS-48213/ask"' in h and "Ask about this page" in h
    assert "data-layer=\"data\"" in h                                                      # the offline layers are all still there


def test_the_console_proxies_the_question_and_returns_the_answer(client, backend, monkeypatch):
    seen = {}

    async def ask(kind, id_, user, question, locale=None, accept_language=None):
        seen.update(kind=kind, id=id_, question=question)
        return {"answer": "It is a swap.", "sources": ["this page's context"]}
    monkeypatch.setattr(backend, "ask", ask, raising=False)
    r = client.post("/v/trade/IRS-48213/ask", json={"question": "What is this?"})
    assert r.status_code == 200 and r.json() == {"answer": "It is a swap.", "sources": ["this page's context"]}
    assert seen == {"kind": "trade", "id": "IRS-48213", "question": "What is this?"}


def test_a_problem_keeps_the_servers_code(client, backend, monkeypatch):
    for status, code in ((404, "DRS-4007"), (502, "DRS-4008"), (429, "DRS-4009")):
        async def ask(kind, id_, user, question, locale=None, accept_language=None, _s=status, _c=code):
            raise BackendError(_s, _c, "nope")
        monkeypatch.setattr(backend, "ask", ask, raising=False)
        r = client.post("/v/trade/IRS-48213/ask", json={"question": "q"})
        assert r.status_code == status and r.json()["code"] == code


def test_a_body_that_is_not_json_is_refused(client):
    r = client.post("/v/trade/IRS-48213/ask", content="question=hi", headers={"content-type": "text/plain"})
    assert r.status_code == 415
