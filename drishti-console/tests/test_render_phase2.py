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


"""The client-side renderer on the console's side (docs/architecture/CLIENT_RENDERER.md), without a browser: one page renderer, Jinja (the
console's pages are the server's; the library draws embeds and <drishti-report> only), About as JSON, panels answered with their JSON (for an
embed's renderer), the channel's ``vm:`` subscriptions, the embed API v2 (the ViewModel with its contract, its parts as JSON, the renderer's
versioned files, Elements 2.x's address), and the three console fixes phase 1 found (a panel with no data, the candles' texts, a bar
list that never widens the page)."""
import html
import json
import re

from jinja2 import Environment, FileSystemLoader
from core import half_up

from conftest import CONSOLE, FIXTURES
from core import channel, render_dist as rd
from core.config import load_settings
from test_embed import HOST, make_client, token

WEB = CONSOLE / "web"


def _page(client, path):
    r = client.get(path)
    assert r.status_code == 200, r.text[:300]
    return r.text


def test_the_console_draws_its_pages_with_jinja_only(client):
    """One page renderer: an entity page is the server's HTML whatever is asked; the console holds no client-render mode any more."""
    for path in ("/v/trade/IRS-48213", "/v/trade/IRS-48213?render=client", "/v/trade/IRS-48213?embed=1&render=client"):
        page = _page(client, path)
        assert "data-render-host" not in page and "drishti_view-render.js" not in page and 'id="p-legs"' in page, path
        assert "modulepreload" not in page and ' defer>' not in page, path
    assert '<section class="vhead">' in _page(client, "/v/trade/IRS-48213") and "drishti_live.js" in _page(client, "/v/trade/IRS-48213")
    assert not (WEB / "static/js/drishti_view-render.js").exists() and not (CONSOLE / "core/render_mode.py").exists()
    assert load_settings(CONSOLE / "config").get("ui.render") is None


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
    for rel in ("drishti-render.js", "drishti-elements.js", "drishti-render.d.ts", "drishti-render.css"):
        r = embed.get(f"/embed/v2/render/{rd.VERSION}/{rel}", headers={"Origin": HOST})
        assert r.status_code == 200 and rd.sri(r.content) == record["files"][rel]["integrity"] and "immutable" in r.headers["cache-control"]
    r = embed.get("/embed/v2/render/2/drishti-render.js", headers={"Origin": HOST}, follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == f"/embed/v2/render/{rd.VERSION}/drishti-render.js"
    r = embed.get("/embed/v2/render/1/drishti-render.js", headers={"Origin": HOST}, follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == "/embed/v2/render/1.2.0/drishti-render.js", "1.x pinned hosts keep the frozen 1.2.0"
    assert embed.get("/embed/v2/render/0.1/drishti-render.js", follow_redirects=False).status_code in (404, 410)     # retired with 1.0.0 and 1.1.0
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
    half_up.install(e)
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
    css = (WEB / "static/css/drishti_terminal.css").read_text(encoding="utf-8")
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


def test_the_console_serves_the_one_renderer_version(client):
    assert client.get(f"/static/render/{rd.VERSION}/drishti-render.js").status_code == 200
    assert client.get("/static/render/0.1.0/drishti-render.js").status_code == 404
