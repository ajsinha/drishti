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
"""The screens' write routes of the console: a Design's Form tab (preview, save), the page designer's cancel, a click on a Sutra Next screen, and
the embed API v2's channel (a change of subscriptions, a fresh token, the browser's preflight); each with a request that must be refused with a
4xx and never a 500."""
import time
from types import SimpleNamespace

from core.backend import BackendError
from core.servers import DEFAULT
from routes import api_routes
from test_embed import HOST, make_client, token


def recording(backend, monkeypatch, calls, answer=None, refuse=None):
    async def designs(method, path, ident, body=None, **params):
        calls.append((method, path, body))
        if refuse is not None:
            raise refuse
        return {} if answer is None else answer
    monkeypatch.setattr(backend, "designs", designs)


def test_a_form_is_previewed_and_saved_with_only_its_two_fields(client, backend, monkeypatch):
    calls = []
    recording(backend, monkeypatch, calls, {"ok": True})
    r = client.post("/build/designs/d1/form/preview", json={"text": "form: {}", "other": "dropped"})
    assert r.status_code == 200 and calls[-1] == ("POST", "/d1/form/preview", {"text": "form: {}"})
    r = client.put("/build/designs/d1/form", json={"baseRev": 3, "text": "form: {}", "other": "dropped"})
    assert r.status_code == 200 and calls[-1] == ("PUT", "/d1/form", {"baseRev": 3, "text": "form: {}"})
    n = len(calls)
    for send in (client.post, client.put):
        path = "/build/designs/d1/form/preview" if send == client.post else "/build/designs/d1/form"
        assert send(path, json=[1]).status_code == 400
        assert send(path, content="{nope", headers={"Content-Type": "application/json"}).status_code == 400
    assert len(calls) == n, "a refused request never reaches the server"


def test_a_stale_form_save_is_the_servers_conflict(client, backend, monkeypatch):
    recording(backend, monkeypatch, [], refuse=BackendError(409, "DRS-5004", "the design changed: reload it"))
    r = client.put("/build/designs/d1/form", json={"baseRev": 1, "text": "x"})
    assert r.status_code == 409 and "reload" in r.text
    recording(backend, monkeypatch, [], refuse=BackendError(404, "DRS-5006", "no design 'nope'"))
    assert client.post("/build/designs/nope/form/preview", json={"text": "x"}).status_code == 404


def test_a_designer_draft_is_cancelled_when_the_designer_is_on_and_a_gone_one_is_no_error(client, backend, monkeypatch):
    calls = []
    recording(backend, monkeypatch, calls)
    st = client.app.state.settings._data
    monkeypatch.setitem(st.setdefault("ui", {}), "designer", True)
    assert client.post("/designer/cancel/d9").json() == {"ok": True}
    assert calls[-1] == ("DELETE", "/d9", None)
    recording(backend, monkeypatch, calls, refuse=BackendError(404, "DRS-5006", "no design"))
    assert client.post("/designer/cancel/d9").json() == {"ok": True}
    monkeypatch.setitem(st["ui"], "designer", False)
    n = len(calls)
    assert client.post("/designer/cancel/d9").status_code in (403, 404)
    assert len(calls) == n


def test_a_click_on_a_row_is_relayed_and_a_click_without_a_row_is_refused(client, backend, monkeypatch):
    sent = []

    async def view_event(kind, id_, ident, click, **kw):
        sent.append((kind, id_, click))
        return {"selection": {"trade": "T-1"}, "cleared": [], "panels": [], "children": None}
    monkeypatch.setattr(backend, "view_event", view_event, raising=False)
    r = client.post("/v/trade/IRS-48213/events", json={"panel": "legs", "event": "open", "row": 1, "selections": ["tok-1"]})
    assert r.status_code == 200 and r.json()["selection"] == {"trade": "T-1"} and r.json()["panels"] == []
    assert sent[-1] == ("trade", "IRS-48213", {"panel": "legs", "event": "open", "row": 1, "selections": ["tok-1"]})
    n = len(sent)
    assert client.post("/v/trade/IRS-48213/events", json={"panel": "legs", "event": "open"}).status_code == 400
    assert client.post("/v/trade/IRS-48213/events", json={"panel": "legs", "row": "x"}).status_code == 400
    assert client.post("/v/trade/IRS-48213/events", json=[1]).status_code == 400
    assert len(sent) == n

    async def refused(kind, id_, ident, click, **kw):
        raise BackendError(403, "DRS-5002", "not yours")
    monkeypatch.setattr(backend, "view_event", refused, raising=False)
    assert client.post("/v/trade/IRS-48213/events", json={"panel": "legs", "event": "open", "row": 0}).status_code == 403


def test_the_embed_v2_channel_changes_its_subscriptions_takes_a_fresh_token_and_refuses_strangers(backend):
    c = make_client(backend)
    h = {"Authorization": f"Bearer {token()}", "Origin": HOST}
    subs = []
    refreshed = []
    channel = {"user": "alice", "app": "demo", "server": DEFAULT, "add": subs.append, "remove": lambda s: subs.append("-" + s),
               "session": SimpleNamespace(limit=10, tasks={})}
    api_routes.channels(c.app)["c-1"] = channel
    c.app.state.embed.guards["c-1"] = SimpleNamespace(refresh=lambda me, claims: refreshed.append(claims["sub"]), session=channel["session"])
    try:
        r = c.post("/embed/v2/channel/c-1", json={"add": ["view:trade/A", "alerts"], "remove": ["view:trade/B"]}, headers=h)
        assert r.status_code == 200 and r.json() == {"ok": True} and r.headers["drishti-embed-api"] == "2.0"
        assert subs == ["-view:trade/B", "view:trade/A"], "only view keys are added"
        assert c.post("/embed/v2/channel/c-1", content="{nope", headers={**h, "Content-Type": "application/json"}).status_code == 400
        assert c.post("/embed/v2/channel/c-1", json={"add": ["view:trade/A"]}, headers={"Origin": HOST}).json()["code"] == "DRS-8001"
        stranger = {"Authorization": f"Bearer {token(user='mallory')}", "Origin": HOST}
        assert c.post("/embed/v2/channel/c-1", json={"add": ["view:trade/A"]}, headers=stranger).status_code == 404
        r = c.post("/embed/v2/channel/c-1/token", headers={"Authorization": f"Bearer {token(exp=int(time.time()) + 600)}", "Origin": HOST})
        assert r.status_code == 200 and r.json()["ok"] is True and "expiresAt" in r.json() and refreshed == ["alice"]
        assert c.post("/embed/v2/channel/c-1/token", headers=stranger).status_code == 404
        assert c.post("/embed/v2/channel/nope/token", headers=h).status_code == 404
        assert c.post("/embed/v2/channel/c-1/token", headers={"Origin": HOST}).json()["code"] == "DRS-8001"
    finally:
        api_routes.channels(c.app).pop("c-1", None)
        c.app.state.embed.guards.pop("c-1", None)


def test_the_embed_v2_preflight_answers_a_listed_origin_only(backend):
    c = make_client(backend)
    ok = c.options("/embed/v2/channel/c-1", headers={"Origin": HOST, "Access-Control-Request-Method": "POST"})
    assert ok.status_code == 204 and ok.headers["access-control-allow-origin"] == HOST and "POST" in ok.headers["access-control-allow-methods"]
    assert c.options("/embed/v2/channel/c-1", headers={"Origin": "http://evil.example"}).status_code == 403
    assert c.options("/embed/v2/views/trade/T-1", headers={"Origin": "null"}).status_code == 403
