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


"""The client-side renderer's phase 2 on the console's side (docs/architecture/CLIENT_RENDERER.md section 16), without a browser:
``ui.render`` (mode, pages, the short form, ``?render=``), the entity page as the client draws it (the embedded ViewModel, the scripts
deferred after the renderer's module, no live.js) and as the server draws it (unchanged), About as JSON, panels answered with their
JSON, the channel's ``vm:`` subscriptions, the embed API v2 (the ViewModel with its contract, its parts as JSON, the renderer's
versioned files, Elements 2.x's address), and the three console fixes phase 1 found (a panel with no data, the candles' texts, a bar
list that never widens the page)."""
import html
import json
import re

from jinja2 import Environment, FileSystemLoader

from conftest import CONSOLE, FIXTURES
from core import channel, render_dist as rd, render_mode
from core.config import Settings, load_settings
from test_embed import HOST, make_client, token

WEB = CONSOLE / "web"


class Req:
    def __init__(self, settings, query=""):
        self.query_params = dict(x.split("=", 1) for x in query.split("&") if "=" in x)
        self.app = type("A", (), {"state": type("S", (), {"settings": settings})()})()


def test_ui_render_is_server_by_default_and_page_by_page():
    s = load_settings(CONSOLE / "config")
    assert render_mode.mode(s) == "server" and render_mode.pages(s) == ("view", "pane")
    assert not render_mode.client(Req(s), "view")
    on = Settings({"ui": {"render": {"mode": "client", "pages": ["view"]}}})
    assert render_mode.client(Req(on), "view") and not render_mode.client(Req(on), "pane")
    assert render_mode.pages(Settings({"ui": {"render": {"pages": "pane, view, nonsense"}}})) == ("pane", "view")
    short = Settings({"ui": {"render": "client"}})                                   # the short form: ui.render: client
    assert render_mode.mode(short) == "client" and render_mode.client(Req(short), "pane")
    assert render_mode.mode(Settings({"ui": {"render": {"mode": "weird"}}})) == "server"
    assert render_mode.client(Req(s, "render=client"), "view") and not render_mode.client(Req(on, "render=server"), "view")


def _page(client, path):
    r = client.get(path)
    assert r.status_code == 200, r.text[:300]
    return r.text


def test_the_server_page_is_unchanged_and_the_client_page_embeds_its_view_model(client):
    server = _page(client, "/v/trade/IRS-48213")
    assert "data-render-host" not in server and "view-render.js" not in server and "live.js" in server
    assert ' defer>' not in server.split("{% block scripts %}")[0] and '<section class="vhead">' in server and 'id="p-legs"' in server
    page = _page(client, "/v/trade/IRS-48213?render=client")
    boot = json.loads(html.unescape(re.search(r'data-render-boot="([^"]+)"', page).group(1)))
    vm = json.loads((FIXTURES / "view_trade_IRS-48213.json").read_text())
    assert boot["vm"]["ref"] == vm["ref"] and [p["id"] for p in boot["vm"]["panels"]] == [p["id"] for p in vm["panels"]]
    assert boot["asOf"] == "live" and boot["backToLive"].startswith("/asof?d=live&next=/v/trade/IRS-48213")
    assert '<section class="vhead">' not in page and 'id="p-legs"' not in page, "the panels are drawn by the library, not the macros"
    module = page.index('src="/static/js/view-render.js')
    assert page.index('src="/static/vendor/echarts/echarts.min.js') < module < page.index('src="/static/js/view.js')
    assert re.search(r'src="/static/js/view\.js\?v=[^"]*" defer>', page) and re.search(r'src="/static/js/tables\.js', page)
    assert "/static/js/live.js" not in page, "the renderer's page follows the channel itself"
    assert 'data-render-chrome' in page and 'data-share=' in page, "the console's tools are on the page, placed after the paint"
    pane = _page(client, "/v/trade/IRS-48213?embed=1&render=client")
    assert "data-render-host" in pane and '"backToLive": null' in html.unescape(pane)


def test_view_render_imports_the_current_renderer():
    text = (WEB / "static/js/view-render.js").read_text(encoding="utf-8")
    assert f"from '../render/{rd.VERSION}/drishti-render.js'" in text


def test_about_answers_json_on_asking(client):
    r = client.get("/v/trade/IRS-48213/about?format=json")
    body = r.json()
    assert r.status_code == 200 and body["explain"]["ref"]["id"] == "IRS-48213" and "terms" in body["index"]
    missing = client.get("/v/trade/NOPE/about?format=json")
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "DRS-1001"
    assert client.get("/v/trade/IRS-48213/about").text.lstrip().startswith("<div class=\"about-card\"")      # the console's HTML, as before


def test_panels_come_with_their_json_and_the_strip_with_its_cells(client):
    body = client.get("/v/trade/IRS-48213/panels?panels=legs").json()
    p = body["panels"][0]
    assert p["html"].strip().startswith("<section") and p["panel"]["id"] == "legs" and p["panel"]["kind"] and "data" in p["panel"]
    assert body["stripCells"] and body["stripCells"][0]["label"]


def test_a_vm_subscription_keeps_its_panels_as_json():
    frame = {"seq": 1, "generation": 2, "patches": [{"op": "panel", "panel": {"id": "p", "kind": "kv", "data": {"fields": []}}}]}
    assert json.loads(channel.raw_view("frame", json.dumps(frame))) == frame
    assert json.loads(channel.raw_view("view", json.dumps({"provenance": {"generation": 7}, "panels": [1, 2]}))) == {"generation": 7}


# ---- the embed API v2 ---------------------------------------------------------------------------------------------------------
def _auth():
    return {"Authorization": f"Bearer {token()}", "Origin": HOST}


def test_v2_answers_the_view_model_with_its_contract_and_its_live_key(backend):
    embed = make_client(backend)
    r = embed.get("/embed/v2/views/trade/IRS-47102", headers=_auth())
    assert r.status_code == 200 and r.headers["drishti-embed-api"] == "2.0" and r.headers["access-control-allow-origin"] == HOST
    vm = r.json()
    assert vm["contract"] == "1.0" and vm["ref"]["id"] == "IRS-47102" and isinstance(vm["panels"][0], dict) and "html" not in vm["panels"][0]
    assert "Drishti-Subscribe" in r.headers["access-control-expose-headers"]
    assert embed.get("/embed/v2/views/trade/IRS-47102", headers={"Origin": HOST}).json()["code"] == "DRS-8001"
    evil = embed.get("/embed/v2/views/trade/IRS-47102", headers={"Authorization": f"Bearer {token()}", "Origin": "http://evil.example"})
    assert evil.status_code == 403 and "access-control-allow-origin" not in evil.headers
    about = embed.get("/embed/v2/views/trade/IRS-48213/about", headers=_auth()).json()
    assert about["explain"]["ref"]["id"] == "IRS-48213" and "panels" in about["index"]
    parts = embed.get("/embed/v2/views/trade/IRS-48213/panels?panels=legs", headers=_auth()).json()
    assert parts["panels"][0]["panel"]["id"] == "legs" and "html" not in parts["panels"][0] and parts["stripCells"]


def test_v2_serves_the_renderer_immutably_with_partial_versions_and_elements_2(backend):
    embed = make_client(backend)
    record = json.loads((rd.ROOT / rd.VERSION / rd.RECORD).read_text())
    for rel in ("drishti-render.js", "drishti-elements.js", "m/elements.js", "drishti-render.css"):
        r = embed.get(f"/embed/v2/render/{rd.VERSION}/{rel}", headers={"Origin": HOST})
        assert r.status_code == 200 and rd.sri(r.content) == record["files"][rel]["integrity"] and "immutable" in r.headers["cache-control"]
    r = embed.get("/embed/v2/render/1/drishti-render.js", headers={"Origin": HOST}, follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == f"/embed/v2/render/{rd.VERSION}/drishti-render.js"
    assert embed.get("/embed/v2/render/0.1/drishti-render.js", follow_redirects=False).headers["location"] == "/embed/v2/render/0.1.0/drishti-render.js"
    gone = embed.get("/embed/v2/render/3.0.0/drishti-render.js")
    assert gone.status_code == 410 and gone.json()["code"] == "DRS-8006"
    e2 = embed.get("/embed/v2/elements/2/drishti-elements.js", follow_redirects=False)
    assert e2.status_code == 302 and e2.headers["location"] == f"/embed/v2/render/{rd.VERSION}/drishti-elements.js"
    assert embed.get("/embed/v2/js/tables.js").status_code == 200 and embed.get("/embed/v2/js/app.js").status_code == 404
    assert embed.get("/embed/v2/vendor/echarts/echarts.min.js").status_code == 200 and embed.get("/embed/v2/vendor/../../core/app.py").status_code == 404
    assert embed.get("/embed/v1/drishti-elements.js").status_code == 200, "Elements 1.x stays, unchanged, beside it"


def test_v2_is_off_with_embedding(client):
    assert client.get("/embed/v2/render/1.0.0/drishti-render.js").status_code == 404


# ---- the three console fixes phase 1 found ------------------------------------------------------------------------------------
def _macros():
    e = Environment(loader=FileSystemLoader(str(WEB / "templates")), extensions=["jinja2.ext.do"], autoescape=True)
    e.globals.update(ASSET_V="", design_on=lambda: True)
    return e.get_template("_macros/panels.html").module


def test_a_panel_with_no_data_and_not_empty_says_no_data():
    out = str(_macros().panel({"id": "x", "kind": "kv", "title": "Terms", "empty": False}, True))
    assert "No data available" in out
    prov = str(_macros().panel({"id": "b", "kind": "provenance", "title": "Built", "empty": False}, True))
    assert '<dl class="prov"></dl>' in prov


def test_the_candles_data_table_prints_the_servers_texts():
    d = {"candles": [{"x": "2026-01-02", "open": 74.0, "high": 75.5, "low": 73.25, "close": 75.0, "volume": 120000.0,
                      "openText": "74.00", "highText": "75.50", "lowText": "73.25", "closeText": "75.00", "volumeText": "120,000"}],
         "volume": True, "last": "75.00", "change": "+1.00", "tone": "pos"}
    out = str(_macros().panel({"id": "k", "kind": "candlestick", "title": "Front month", "empty": False, "data": d}, True))
    assert "74.00" in out and "120,000" in out and ">74.0<" not in out
    older = dict(d, candles=[{k: v for k, v in d["candles"][0].items() if not k.endswith("Text")}])        # a server older than the texts
    assert ">74.0<" in str(_macros().panel({"id": "k", "kind": "candlestick", "title": "F", "empty": False, "data": older}, True))


def test_a_bar_list_never_widens_the_page():
    css = (WEB / "static/css/terminal.css").read_text(encoding="utf-8")
    li = re.search(r"\.bars li \{[^}]*\}", css).group(0)
    bl = re.search(r"\n\.bl \{[^}]*\}", css).group(0)
    assert "fit-content(40%)" in li and "max-content" in li and "minmax(3.4rem, max-content)" not in li
    assert "overflow: hidden" in bl and "text-overflow: ellipsis" in bl
    out = str(_macros().panel({"id": "h", "kind": "hbar", "title": "Types", "empty": False, "data": {"bars": [{"label": "a very long label", "value": 1.0, "text": "1"}]}}, True))
    assert 'title="a very long label"' in out


def test_every_released_renderer_folder_is_immutable():
    for v in rd.versions():
        have = json.loads((rd.ROOT / v / rd.RECORD).read_text(encoding="utf-8"))
        assert have == rd.record(v), f"static/render/{v} changed: a released version is immutable"


def test_the_console_serves_both_renderer_versions(client):
    for v in ("0.1.0", rd.VERSION):
        assert client.get(f"/static/render/{v}/drishti-render.js").status_code == 200
