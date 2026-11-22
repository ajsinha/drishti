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

"""The live tables' console side (routes/live_routes.py, core/channel.py): /live/<name> is a page for any table the person may read
and a 404 for one they may not; /api/rdp passes the server's Data Protocol on as the person (the schema, a page, a write, a stream's window)
with the server's own refusals (403 read-only, 409 moved) and never a path outside the protocol; a browser's live:<table> subscription is the
server's stream of that set; the hub passes the stream's rows and resync events; the trading module's menu opens the blotter; the Pivot takes
the layout a live grid hands over."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

from core.backend import BackendError
from core.channel import ChannelSession

CONSOLE = Path(__file__).resolve().parent.parent
SCHEMA = {"name": "trade", "title": "Trade blotter", "key": ["TRADE_ID"], "columns": [{"name": "TRADE_ID", "type": "string", "label": "Trade"}],
          "profile": {"kind": "trade"}, "tray": True}


def serve(backend, monkeypatch, calls):
    async def send(method, path, ident=None, **kw):
        calls.append((method, path, kw.get("json")))
        if path == "/rdp/tables/trade" and method == "GET":
            return SCHEMA
        if path.startswith("/rdp/tables/") and method == "GET":
            raise BackendError(404, "rdp.table.unknown", f"no table '{path.split('/')[-1]}'")
        if path.endswith("/write"):
            raise BackendError(409, "rdp.write.conflict", "trade T-1 is at version 3, changed by R.DIAZ")
        return {"ok": True, "path": path}
    monkeypatch.setattr(backend, "_send", send, raising=False)


def test_the_page_opens_for_a_table_the_person_may_read_and_not_found_for_another(client, backend, monkeypatch):
    calls = []
    serve(backend, monkeypatch, calls)
    page = client.get("/live/trade")
    assert page.status_code == 200 and 'data-live-page' in page.text and 'data-id="trade"' in page.text and "Trade blotter" in page.text
    spec = json.loads(__import__("html").unescape(page.text.split('data-spec="')[1].split('"')[0]))
    assert "drishti_live-page.js" in page.text and spec["id"] == "trade" and spec["key"] == ["TRADE_ID"] and spec["tray"] is True
    assert spec["editable"] is False and spec["readOnly"]                                                  # no write in the schema: read only
    assert client.get("/live/Not_An_Id!").status_code == 404


def test_the_api_passes_each_call_on_and_the_servers_refusals_with_it(client, backend, monkeypatch):
    calls = []
    serve(backend, monkeypatch, calls)
    assert client.get("/api/rdp/tables/trade").json() == SCHEMA
    r = client.post("/api/rdp/tables/trade/query", json={"limit": 50, "sort": [{"col": "TRADE_ID", "dir": "desc"}]})
    assert r.json()["path"] == "/rdp/tables/trade/query" and calls[-1][2] == {"limit": 50, "sort": [{"col": "TRADE_ID", "dir": "desc"}]}
    w = client.post("/api/rdp/changes/stream-0001-abcdef/window", json={"keys": [["T-1"]]})
    assert w.status_code == 200 and calls[-1] == ("POST", "/rdp/changes/stream-0001-abcdef/window", {"keys": [["T-1"]]})
    e = client.post("/api/rdp/tables/trade/write", json={"id": "01J", "op": "update", "key": ["T-1"], "basedOn": 2, "set": {"PRICE": 1}})
    assert e.status_code == 409 and e.json() == {"error": {"code": "rdp.write.conflict", "message": "trade T-1 is at version 3, changed by R.DIAZ"}}
    assert client.post("/api/rdp/tables/trade/write", content="not json", headers={"content-type": "text/plain"}).status_code == 415
    assert client.post("/api/rdp/tables/..%2Fadmin/write", json={}).status_code in (400, 404)               # never a path outside the protocol
    assert client.post("/api/rdp/changes/x/window", json={}).status_code == 400                            # a stream's id is a plain name of 8 or more
    assert client.get("/api/rdp/searches", params={"q": "TRD where CURRENCY = 'EUR'"}).json()["path"].startswith("/rdp/searches?q=TRD")


class Pumped:
    """The server's stream of one table (the minimal SSE form): one msg (a patch), then a gap; the path and body it was opened with are kept."""

    def __init__(self):
        self.paths = []

    def _frames(self):
        yield "msg", json.dumps({"t": "msg", "table": "trade", "epoch": "e1", "seq": 3, "data": {"update": [{"TRADE_ID": "T-1", "PRICE": 2}]}})
        yield "gap", json.dumps({"t": "gap", "recoverable": False})

    async def sse(self, path, me, opened=None):
        self.paths.append(path)
        for f in self._frames():
            yield f
        await asyncio.sleep(30)

    async def sse_post(self, path, me, body, opened=None):
        self.paths.append((path, body))
        for f in self._frames():
            yield f
        await asyncio.sleep(30)


def chunks_of(subs, n):
    backend = Pumped()
    session = ChannelSession(backend, SimpleNamespace(user="ann"), lambda event, data: data, limit=4)

    async def run():
        out = []
        agen = session.events(subs, lambda: asyncio.sleep(0, result=False))
        try:
            async for chunk in agen:
                out.append(chunk)
                if len(out) >= n:
                    break
        finally:
            await agen.aclose()
        return out
    return backend, asyncio.run(run())


def test_a_live_subscription_is_the_servers_stream_of_that_table():
    backend, chunks = chunks_of(["live:trade"], 3)
    assert backend.paths == ["/rdp/changes?table=trade"]
    assert [c.split("\n")[0] for c in chunks[1:]] == ["event: msg", "event: gap"]
    assert json.loads(chunks[1].split("\n")[1][6:])["ch"] == "live:trade"


def test_a_forms_subscription_follows_its_filter():
    import base64
    tree = {"op": "==", "args": [{"col": "CURRENCY"}, {"lit": "EUR"}]}
    sub = "live:q-1~" + base64.urlsafe_b64encode(json.dumps(tree).encode()).decode().rstrip("=")
    backend, _ = chunks_of([sub], 2)
    assert backend.paths == [("/rdp/changes", {"table": "q-1", "filter": tree})]


def test_the_hub_passes_the_tables_msg_and_gap_events():
    hub = (CONSOLE / "web" / "static" / "js" / "drishti_live-hub.js").read_text(encoding="utf-8")
    types = hub.split("var TYPES = [")[1].split("]")[0]
    assert "'msg'" in types and "'gap'" in types


def test_the_trading_modules_menu_opens_the_blotter():
    import yaml
    plugin = yaml.safe_load((CONSOLE.parent / "modules" / "trading" / "console" / "plugin.yaml").read_text(encoding="utf-8"))
    manifest = yaml.safe_load((CONSOLE.parent / "modules" / "trading" / "module.yaml").read_text(encoding="utf-8"))
    assert str(plugin["format"]) == "2" and manifest["console"]["plugin"] == "console/plugin.yaml"
    assert [(m["label"], m["href"]) for m in plugin["menus"]] == [("Trade blotter", "/live/trade")]


def test_the_pivot_takes_the_layout_a_live_grid_hands_over(client, backend):
    lay = {"rows": ["PORTFOLIO"], "columns": [], "values": [{"measure": "sum(QUANTITY)", "as": "QUANTITY"}], "filters": {"PORTFOLIO": {"in": ["BOOK-A"]}}, "evil": 1}
    page = client.get("/pivot", params={"source": "trade", "lay": json.dumps(lay), "live": "1"})
    assert page.status_code == 200 and 'data-live-start="1"' in page.text
    laid = json.loads(__import__("html").unescape(page.text.split('data-preset="')[1].split('"')[0]))
    assert laid["rows"] == ["PORTFOLIO"] and laid["filters"] == {"PORTFOLIO": {"in": ["BOOK-A"]}} and "evil" not in laid
    plain = client.get("/pivot", params={"source": "trade", "lay": "not json"})
    assert plain.status_code == 200 and "data-preset" not in plain.text and "data-live-start" not in plain.text


def test_a_set_of_a_module_this_distribution_lacks_answers_a_plain_page_not_a_404(client, backend, monkeypatch):
    calls = []
    serve(backend, monkeypatch, calls)
    state = client.app.state.editions.modules                         # this app's own state (core/modules.py State), the one its requests read
    before = state.loaded, state.parts
    state.loaded = frozenset({"core", "genomics"})                   # the server's about: the trading module is not loaded
    state.parts = None                                                # and no module part hosted here (a hosted trading part would count as on)
    try:
        page = client.get("/live/trade-gone")
        assert page.status_code == 200 and "Not part of this distribution" in page.text
    finally:
        state.loaded, state.parts = before


def test_signed_out_a_blotter_is_behind_sign_in_and_returns_to_itself():
    from core.app import protected
    from core.nextpath import to_login
    assert protected("/live/trade") and not protected("/static/x")                  # the gate sends a signed-out person to /login, as /t does
    assert to_login("/live/trade") == "/login?next=/live/trade"
