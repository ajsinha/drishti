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

"""The live result sets' console side (routes/live_routes.py, core/channel.py): /live/<id> is a page for any declaration the person may open
and a 404 for one they may not; /api/live passes the server's answers on as the person (rows, one record whole, a stream's window, an edit)
with the server's own refusals (403 read-only, 409 moved) and never a path outside the live set; a browser's live:<id> subscription is the
server's stream of that set; the hub passes the stream's rows and resync events; the trading module's menu opens the blotter; the Pivot takes
the layout a live grid hands over."""
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace

from core.backend import BackendError
from core.channel import ChannelSession

CONSOLE = Path(__file__).resolve().parent.parent
SPEC = {"id": "trade", "title": "Trade blotter", "kind": "trade", "columns": [{"field": "TRADE_ID", "label": "Trade", "type": "id"}], "groupBy": [],
        "subtotals": [], "links": [], "editable": False, "readOnly": "your role holds no edit power", "tray": True}


def serve(backend, monkeypatch, calls):
    async def send(method, path, ident=None, **kw):
        calls.append((method, path, kw.get("json")))
        if path == "/live":
            return {"sets": [SPEC]}
        if path.endswith("/edit"):
            raise BackendError(409, "DRS-9301", "trade T-1 is at version 3, changed by R.DIAZ")
        return {"ok": True, "path": path}
    monkeypatch.setattr(backend, "_send", send, raising=False)


def test_the_page_opens_for_a_declaration_the_person_may_open_and_not_found_for_another(client, backend, monkeypatch):
    calls = []
    serve(backend, monkeypatch, calls)
    page = client.get("/live/trade")
    assert page.status_code == 200 and 'data-live-page' in page.text and 'data-id="trade"' in page.text and "Trade blotter" in page.text
    assert "drishti_live-page.js" in page.text and json.loads(__import__("html").unescape(page.text.split('data-spec="')[1].split('"')[0]))["id"] == "trade"
    assert client.get("/live/other").status_code == 404
    assert client.get("/live/Not_An_Id").status_code == 404


def test_the_api_passes_each_call_on_and_the_servers_refusals_with_it(client, backend, monkeypatch):
    calls = []
    serve(backend, monkeypatch, calls)
    assert client.get("/api/live").json() == {"sets": [SPEC]}
    r = client.get("/api/live/trade/rows", params={"limit": 50, "sort": "TRADE_ID", "descending": "true", "evil": "x"})
    assert r.json()["path"] == "/live/trade/rows?limit=50&sort=TRADE_ID&descending=true"                 # only the known parameters
    assert client.get("/api/live/trade/rows/T%2F1").json()["path"] == "/live/trade/rows/T%2F1"          # one record whole, its key encoded
    w = client.post("/api/live/trade/stream/stream-0001-abcdef/window", json={"ids": ["T-1"]})
    assert w.status_code == 200 and calls[-1] == ("POST", "/live/trade/stream/stream-0001-abcdef/window", {"ids": ["T-1"]})
    e = client.post("/api/live/trade/edit", json={"entity": "T-1", "items": [], "basedOn": 2})
    assert e.status_code == 409 and e.json() == {"code": "DRS-9301", "detail": "trade T-1 is at version 3, changed by R.DIAZ"}
    assert client.post("/api/live/trade/edit", content="not json", headers={"content-type": "text/plain"}).status_code == 415
    assert client.post("/api/live/..%2Fadmin/edit", json={}).status_code in (400, 404)                   # never a path outside the live set
    assert client.post("/api/live/trade/stream/x/window", json={"all": True}).status_code == 400          # a stream's id is a plain name of 8 or more


class Pumped:
    """The server's stream of one set: hello, then one rows event; the path it was opened on is kept."""

    def __init__(self):
        self.paths = []

    async def sse(self, path, me, opened=None):
        self.paths.append(path)
        yield "hello", json.dumps({"stream": "stream-0001-abcdef", "total": 1, "live": True})
        yield "rows", json.dumps({"type": "rows", "upserts": [{"id": "T-1", "version": 2, "seq": 3, "doc": {"TRADE_ID": "T-1"}}], "removes": [], "total": 1, "seq": 3})
        yield "resync", json.dumps({"type": "resync", "total": 1, "seq": 4})
        await asyncio.sleep(30)


def test_a_live_subscription_is_the_servers_stream_of_that_set():
    backend = Pumped()
    session = ChannelSession(backend, SimpleNamespace(user="ann"), lambda event, data: data, limit=4)

    async def run():
        out = []
        agen = session.events(["live:trade"], lambda: asyncio.sleep(0, result=False))
        try:
            async for chunk in agen:
                out.append(chunk)
                if len(out) >= 4:
                    break
        finally:
            await agen.aclose()
        return out

    chunks = asyncio.run(run())
    assert backend.paths == ["/live/trade/stream"]
    events = [c.split("\n")[0] for c in chunks[1:]]
    assert events == ["event: hello", "event: rows", "event: resync"]
    assert json.loads(chunks[2].split("\n")[1][6:])["ch"] == "live:trade"


def test_the_hub_passes_the_streams_rows_and_resync_events():
    hub = (CONSOLE / "web" / "static" / "js" / "drishti_live-hub.js").read_text(encoding="utf-8")
    types = hub.split("var TYPES = [")[1].split("]")[0]
    assert "'rows'" in types and "'resync'" in types and "'hello'" in types


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
    from core import modules
    calls = []
    serve(backend, monkeypatch, calls)
    state = modules.state()
    before = state.loaded
    state.loaded = frozenset({"core", "genomics"})                   # the server's about: the trading module is not loaded
    try:
        page = client.get("/live/trade-gone")
        assert page.status_code == 200 and "Not part of this distribution" in page.text
    finally:
        state.loaded = before
