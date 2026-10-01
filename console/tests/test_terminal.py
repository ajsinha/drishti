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

"""The terminal: command dispatch, entity views for the four mockups, errors and the JSON proxy."""
import re


def text(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html))


def test_irs_view_renders_the_mockup(client):
    r = client.get("/v/trade/IRS-48213")
    assert r.status_code == 200
    h, t = r.text, text(r.text)
    assert "Trade · Interest rate swap" in t and "IRS-48213" in t and "Northbridge Capital LLP" in t
    for v in ("50,000,000", "Pay fixed", "3.8500%", "−412,580", "+22,310"):
        assert v in t, v
    assert 'class="mono t-neg emph"' in h and 'data-path="$.mtm"' in h
    for panel in ("legs", "cashflows", "leg2", "built", "curve", "refs", "dv01"):
        assert f'id="p-{panel}"' in h, panel
    assert 'role="tab"' in h and "Leg 1 · Pay fixed 3.8500%" in t
    assert "−9,764,027.78" in t and "Total" in t
    assert 'data-chart="' in h and 'data-kind="line"' in h
    assert 'href="/v/netting-set/NS-NORTH-01"' in h and "EE 4.1m" in t
    assert "Sutra irs-vanilla v3 + inference" in t and "aero-risk, gen 1742" in t
    assert 'data-fkey="F9"' in h and "Raw JSON" in t and 'data-fkey="F7"' in h
    assert 'value="TRD IRS-48213 &lt;GO&gt;"' in h


def test_other_reference_views(client):
    fx = text(client.get("/v/trade/FXS-20931").text)
    assert "EUR 25,000,000.00" in fx and "+55.8" in fx and "Confirmed" in fx
    fut = client.get("/v/trade/CFT-77120").text
    assert 'class="hl"' in fut and "2026-09-30 (intraday)" in fut and "+409,500" in fut
    ns = client.get("/v/netting-set/NS-NORTH-01").text
    assert "10 more trades" in ns and 'data-kind="area"' in ns and 'href="/v/trade/IRS-47102"' in ns
    inferred = client.get("/v/trade/IRS-47102").text
    assert "inference only" in inferred and "inferred" in inferred


def test_go_dispatches_commands(client):
    r = client.get("/go", params={"q": "TRD IRS-48213 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/IRS-48213"
    bad = client.get("/go", params={"q": "nonsense"}, follow_redirects=False)
    assert bad.headers["location"].startswith("/t?error=")


def test_missing_entity_is_a_friendly_page(client):
    r = client.get("/v/trade/NOPE-1")
    assert r.status_code == 404 and "DRS-1001" in r.text


def test_home_lists_examples(client):
    r = client.get("/t")
    assert r.status_code == 200 and "NSET NS-NORTH-01" in r.text and 'role="combobox"' in r.text


def test_json_proxy_for_the_browser(client, backend):
    s = client.get("/api/suggest", params={"q": "TRD IRS-4"}).json()
    assert s[0]["complete"].startswith("TRD IRS-4")
    assert client.get("/api/raw/trade/IRS-48213").json()["provenance"]["source"] == "aero-risk"
    assert client.get("/api/view/trade/NOPE-1").status_code == 404


def test_live_views_load_the_live_script(client):
    assert "/static/js/live.js" in client.get("/v/trade/IRS-48213").text
    assert "/static/js/live.js" not in client.get("/v/trade/CFT-77120").text


def test_stream_relays_frames_with_server_rendered_panels(client, backend):
    import json as _json

    irs = _json.loads((__import__("pathlib").Path(__file__).parent / "fixtures" / "view_trade_IRS-48213.json").read_text())
    dv01 = next(p for p in irs["panels"] if p["id"] == "dv01")
    curve = next(p for p in irs["panels"] if p["id"] == "curve")
    frame = {"seq": 1, "generation": 1743, "latencyMs": 3.1, "p99Ms": 12.0, "patches": [
        {"op": "strip", "index": 5, "cell": {"label": "MTM (USD)", "text": "−410,000", "tone": "neg"}},
        {"op": "panel", "panel": dv01}, {"op": "panel", "panel": curve}]}

    async def fake_stream(kind, id_, ident=None):
        yield "view", _json.dumps(irs)
        yield "frame", _json.dumps(frame)

    backend.stream = fake_stream
    with client.stream("GET", "/api/stream/trade/IRS-48213") as r:
        body = "".join(r.iter_text())
    assert "event: view" in body and '"generation": 1742' in body
    data = _json.loads(body.split("event: frame\ndata: ")[1].split("\n\n")[0])
    assert data["patches"][0]["cell"]["text"] == "−410,000"
    assert 'id="p-dv01"' in data["patches"][1]["html"] and "+7,030" in data["patches"][1]["html"]
    assert "html" not in data["patches"][2] and data["patches"][2]["panel"]["data"]["mark"] == "5Y"


def test_impact_page(client, backend):
    async def impact(kind, id_, ident=None):
        return {"ref": {"kind": kind, "id": id_}, "elapsedMs": 3.2, "groups": [
            {"level": 1, "kind": "trade", "mnemonic": "TRD", "hidden": 0, "total": "−394,160",
             "items": [{"ref": {"kind": "trade", "id": "IRS-48213"}, "via": None, "measure": "−412,580"}]},
            {"level": 2, "kind": "netting-set", "mnemonic": "NSET", "hidden": 1, "total": None, "items": []}]}
    backend.impact = impact
    r = client.get("/impact/curve/USD-SOFR")
    assert r.status_code == 200 and "Impact of" in r.text and "IRS-48213" in r.text and "total −394,160" in r.text
    assert "1 netting set you do not have access to" in r.text
    assert "window.location.href = '/impact/'" in client.get("/static/js/view.js").text


def test_the_business_date_is_chosen_in_the_top_bar_and_sent_to_the_server(client, backend):
    page = client.get("/v/trade/IRS-48213").text
    assert 'data-asof' in page and 'type="date"' in page and 'max="2026-09-30"' in page and "asof-live on" in page
    assert "2026-11-26" in page                                      # holidays go to the picker
    r = client.get("/asof", params={"d": "2026-09-26", "next": "/v/trade/IRS-48213"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/IRS-48213" and "drishti_asof=2026-09-26" in r.headers["set-cookie"]
    client.cookies.set("drishti_asof", "2026-09-26")
    try:
        past = client.get("/v/trade/IRS-48213").text
        assert "asof-past" in past and 'value="2026-09-25"' in past and "↩ 2026-09-26" in past
        assert "is not a dated source" in past                       # the fixture has no business date
    finally:
        client.cookies.delete("drishti_asof")
    assert client.get("/asof", params={"d": "live", "next": "//evil.example"}, follow_redirects=False).headers["location"] == "/t"


def test_backend_calls_carry_the_business_date_header():
    from core import asof
    assert asof.headers() == {}
    asof.set_current("2026-09-29")
    assert asof.headers() == {"X-Drishti-As-Of": "2026-09-29"}
    asof.set_current("not-a-date")
    assert asof.headers() == {}


KINDS = ["kv", "status", "provenance", "table", "ladder", "tabs", "line", "area", "hbar", "links", "markdown", "gauge", "surface"]
BROKEN = [None, {}, {"fields": None, "rows": None, "tabs": None, "x": None, "series": None, "bars": None, "links": None},
          {"columns": ["A"], "numeric": [], "rows": [{"cells": None}], "x": ["1Y"], "series": [{"label": "s", "values": [None]}],
           "bars": [{"label": "a", "value": None, "text": "—"}], "tabs": [{"title": "t", "fields": None}], "value": None, "max": None}]


def test_every_panel_kind_renders_imperfect_data_without_failing(client):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    for kind in KINDS:
        for data in BROKEN:
            for empty, error in [(False, None), (True, None), (True, "cannot read rows")]:
                out = tpl.render(p={"id": "x", "kind": kind, "title": "X", "area": "main", "data": data, "empty": empty, "error": error})
                assert 'class="pnl"' in out, (kind, data)
                if empty:
                    assert "No data available" in out or "No linked entities" in out
    ok = tpl.render(p={"id": "t", "kind": "table", "title": "T", "area": "main", "empty": False,
                       "data": {"columns": ["A"], "numeric": [False], "rows": [{"cells": [{"text": "1"}]}]}})
    assert "No data available" not in ok and ">1<" in ok


def test_a_surface_panel_renders_a_heatmap_with_a_3d_toggle(client):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    out = tpl.render(p={"id": "s", "kind": "surface", "title": "Smile", "area": "main", "empty": False,
                        "data": {"x": ["25D P", "ATM", "25D C"], "y": ["1M", "1Y"], "z": [[7.1, 6.7, 6.8], [7.6, None, 7.3]], "min": 6.7, "max": 7.6}})
    assert 'data-surface=' in out and 'data-surface-view="3d"' in out and "2 × 3 grid" in out
    empty = tpl.render(p={"id": "s", "kind": "surface", "title": "Smile", "area": "main", "empty": True, "data": {"x": [], "y": [], "z": []}})
    assert "No data available" in empty


def test_known_at_is_set_in_the_business_zone_and_sent_only_with_a_picked_date(client):
    from core import asof
    r = client.get("/asof", params={"d": "2026-09-29", "k": "2026-09-29T10:30", "next": "/t"}, follow_redirects=False)
    cookies = r.headers.get_list("set-cookie")
    assert any("drishti_knownat=2026-09-29T14:30:00Z" in c for c in cookies)      # 10:30 New York (EDT) is 14:30 UTC
    assert asof.to_local("2026-09-29T14:30:00Z", "America/New_York") == "2026-09-29T10:30"
    assert asof.to_instant("garbage", "America/New_York") is None
    client.cookies.set("drishti_asof", "2026-09-29")
    client.cookies.set("drishti_knownat", "2026-09-29T14:30:00Z")
    try:
        page = client.get("/v/trade/IRS-48213").text
        assert 'data-asof-known' in page and 'value="2026-09-29T10:30"' in page and "asof-known on" in page
    finally:
        client.cookies.delete("drishti_asof")
        client.cookies.delete("drishti_knownat")
    # live never sends a known-at, even if a stale cookie is around
    asof.set_current("live")
    asof.set_known("2026-09-29T14:30:00Z")
    assert asof.headers() == {}
    asof.set_current("2026-09-29")
    assert asof.headers() == {"X-Drishti-As-Of": "2026-09-29", "X-Drishti-Known-At": "2026-09-29T14:30:00Z"}
    asof.set_current("live")
    asof.set_known(None)
    live = client.get("/asof", params={"d": "live", "next": "/t"}, follow_redirects=False).headers.get_list("set-cookie")
    assert any(c.startswith("drishti_knownat=") for c in live)                   # cleared with the date


def test_compare_page_shows_what_changed(client, backend):
    seen = {}

    async def history_diff(kind, id_, ident=None, **params):
        seen.update(params)
        return {"ref": {"kind": kind, "id": id_}, "added": 1, "removed": 0, "changed": 1, "truncated": False,
                "from": {"businessDate": "2026-09-28", "knownAt": None, "provenance": {"source": "trading-store", "generation": 7}},
                "to": {"businessDate": "2026-09-29", "knownAt": None, "provenance": {"source": "trading-store", "generation": 9}},
                "changes": [{"path": "valuation.mtm", "label": "MTM", "kind": "changed", "before": -412580.5, "after": -398120, "delta": 14460.5},
                            {"path": "lifecycle.events[E7].type", "label": "Type [E7]", "kind": "added", "before": None, "after": "Reset", "delta": None}]}
    backend.history_diff = history_diff
    r = client.get("/compare/trade/IRS-48213", params={"from": "2026-09-28", "to": "2026-09-29"})
    assert r.status_code == 200 and "What changed in" in r.text
    assert "MTM" in r.text and "valuation.mtm" in r.text and "−412,580.5" in r.text and "+14,460.5" in r.text
    assert "Reset" in r.text and "cmp-k added" in r.text
    assert seen == {"from": "2026-09-28", "to": "2026-09-29"}
    only = client.get("/compare/trade/IRS-48213", params={"from": "2026-09-28", "to": "2026-09-29", "only": "added"}).text
    assert "Reset" in only and "valuation.mtm" not in only


def test_a_search_on_the_command_line_opens_the_results(client, backend):
    r = client.get("/go", params={"q": "TRD where mtm > 1m order by mtm desc <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/s?q=TRD%20where%20mtm")
    asked = {}

    async def search(q, ident=None):
        asked["q"] = q
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm", "$.currency"], "labels": {"$.mtm": "MTM", "$.currency": "Currency"},
                "rows": [{"ref": {"kind": "trade", "id": "IRS-48213"}, "title": "IRS-48213", "values": {"$.mtm": -412580.5, "$.currency": "USD"}}],
                "scanned": 36, "matched": 1, "partial": False, "elapsedMs": 4.2}
    backend.search = search
    page = client.get("/s", params={"q": "TRD where mtm > 1m"}).text
    assert asked["q"] == "TRD where mtm > 1m limit 100"                        # the user's default result size
    assert "<b>1</b> of 36 trades match" in page and "MTM" in page and "−412,580.5" in page and 'href="/v/trade/IRS-48213"' in page
    assert "Examples" in client.get("/s").text
    assert "Watch as a monitor" in page
    saved = {}

    async def mine(method, path, ident, body=None, **params):
        saved.update(method=method, path=path, body=body)
        return body
    backend.mine = mine
    r = client.post("/s/watch", data={"q": "TRD where mtm > 1m", "name": "Big <MTM>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/m/Big%20MTM"
    assert saved == {"method": "PUT", "path": "/monitors/Big%20MTM", "body": {"entities": [{"kind": "trade", "id": "IRS-48213"}]}}


def test_a_bad_search_says_why(client, backend):
    from core.backend import BackendError

    async def search(q, ident=None):
        raise BackendError(400, "DRS-4004", "a string is not closed: 'open")
    backend.search = search
    page = client.get("/s", params={"q": "TRD where name = 'open"}).text
    assert "a string is not closed" in page and "DRS-4004" in page


def test_one_channel_carries_every_live_subscription_of_a_tab(client, backend, monkeypatch):
    import json as _json

    irs = _json.loads((__import__("pathlib").Path(__file__).parent / "fixtures" / "view_trade_IRS-48213.json").read_text())

    async def fake_stream(kind, id_, ident=None, opened=None):
        yield "view", _json.dumps(irs)
        yield "frame", _json.dumps({"seq": 1, "generation": 9, "p99Ms": 2.0, "patches": [
            {"op": "strip", "index": 5, "cell": {"label": "MTM", "text": id_}}]})
    monkeypatch.setattr(backend, "stream", fake_stream, raising=False)
    seen = []
    with client.stream("GET", "/api/channel", params=[("s", "view:trade/IRS-48213"), ("s", "view:trade/FXS-20931"), ("s", "alerts")]) as r:
        event = None
        for line in r.iter_lines():
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                seen.append((event, _json.loads(line[6:])))
                if len([s for s in seen if s[0] == "frame"]) == 2 and any(s[0] == "alert" for s in seen):
                    break
    frames = {m["ch"]: m["d"]["patches"][0]["cell"]["text"] for e, m in seen if e == "frame"}
    assert frames == {"view:trade/IRS-48213": "IRS-48213", "view:trade/FXS-20931": "FXS-20931"}   # two views, one connection
    assert any(e == "alert" and m["ch"] == "alerts" and m["d"]["severity"] == "critical" for e, m in seen)
    assert any(e == "view" and m["d"] == {"generation": 1742} for e, m in seen)


def test_pages_never_open_their_own_event_streams():
    """Browsers allow six connections per site: every live feature must share the tab's channel (channel.js)."""
    from pathlib import Path
    js = Path(__file__).resolve().parent.parent / "web" / "static" / "js"
    offenders = [f.name for f in js.glob("*.js") if "new EventSource" in f.read_text() and f.name != "channel.js"]
    assert offenders == []
    page = Path(__file__).resolve().parent.parent / "web" / "templates" / "base.html"
    assert page.read_text().index("channel.js") < page.read_text().index("alerts.js")


def test_a_channel_takes_new_subscriptions_without_reconnecting(client):
    from routes import api_routes
    calls = []
    api_routes.CHANNELS["c1"] = {"user": "ash", "add": lambda s: calls.append(("add", s)), "remove": lambda s: calls.append(("remove", s))}
    try:
        r = client.post("/api/channel/c1", json={"add": ["view:trade/T-2"], "remove": ["view:trade/T-1"]})
        assert r.status_code == 200 and calls == [("remove", "view:trade/T-1"), ("add", "view:trade/T-2")]
        api_routes.CHANNELS["c2"] = {"user": "someone-else", "add": calls.append, "remove": calls.append}
        assert client.post("/api/channel/c2", json={"add": ["alerts"]}).status_code == 404       # not yours
        assert client.post("/api/channel/nope", json={"add": ["alerts"]}).status_code == 404    # gone: the page opens a new one
    finally:
        api_routes.CHANNELS.pop("c1", None)
        api_routes.CHANNELS.pop("c2", None)


def test_a_command_naming_several_entities_shows_a_pick_list(client, backend, monkeypatch):
    async def command(text, ident=None):
        if text.strip() == "TRD T-100":
            return {"ref": None, "mnemonic": "TRD", "list": "TRD T-100", "matched": 12}
        return {"ref": {"kind": "trade", "id": "T-10001"}, "mnemonic": "TRD"}
    monkeypatch.setattr(backend, "command", command, raising=False)
    r = client.get("/go", params={"q": "TRD T-100"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/s?q=TRD%20T-100"
    one = client.get("/go", params={"q": "TRD T-10001"}, follow_redirects=False)
    assert one.headers["location"] == "/v/trade/T-10001"


def test_readiness_says_whether_the_server_answers(client, backend, monkeypatch):
    assert client.get("/readyz").json()["status"] == "UP"
    async def down(ident=None):
        raise BackendError(503, "DRS-5003", "backend unreachable")
    monkeypatch.setattr(backend, "business_date", down, raising=False)
    r = client.get("/readyz")
    assert r.status_code == 503 and r.json()["server"] == "unreachable"
    assert client.get("/healthz").json()["status"] == "UP"                  # liveness: the process itself



def test_a_packs_code_opens_its_overview(client):
    r = client.get("/go", params={"q": "MKT"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/p/market-data"
    page = client.get("/p/market-data").text
    assert "Market data" in page and "FX volatility surface" in page and "1,234" in page
    assert 'href="/s?q=FXV"' in page and 'href="/v/fx-vol-surface/FXV-EURUSD"' in page and "currency, index" in page
    assert client.get("/p/astrology").status_code == 400



def test_history_and_aliases(client, backend):
    assert client.get("/api/history").json() == ["TRD T-10001", "MKT"]
    page = client.get("/account").text
    assert 'value="MYBOOK"' in page and 'value="BOOK BOOK-RATES-1"' in page and "data-plain" in page
    r = client.put("/api/aliases", json={"revs": "TRD productType=Revolver"})
    assert r.status_code == 200 and r.json() == {"REVS": "TRD productType=Revolver"}
    bad = client.put("/api/aliases", json={"TRD": "TRD T-1"})
    assert bad.status_code == 400 and "mnemonic" in bad.json()["detail"]



def test_studio_keeps_test_entities_and_runs_them(client, backend):
    assert client.get("/studio/tests/irs-fixfloat").json() == []
    saved = client.put("/studio/tests/irs-fixfloat", json=[{"kind": "trade", "id": "IRS-48213"}])
    assert saved.status_code == 200 and client.get("/studio/tests/irs-fixfloat").json() == [{"kind": "trade", "id": "IRS-48213"}]
    ok = client.post("/studio/test", json={"yaml": "sutra: x", "kind": "trade", "id": "IRS-48213"}).json()
    assert ok["ok"] is True and ok["panels"] > 0 and "ms" in ok
    bad = client.post("/studio/test", json={"yaml": "BROKEN", "kind": "trade", "id": "IRS-48213"}).json()
    assert bad["ok"] is False and bad["code"] == "DRS-2002"


def test_a_table_panel_can_turn_its_search_off(client):
    module = client.app.state.templates.env.get_template("_macros/panels.html").module
    on = str(module.table({"columns": ["A"], "numeric": [False], "rows": [{"cells": [{"text": "x"}]}]}))
    off = str(module.table({"columns": ["A"], "numeric": [False], "rows": [{"cells": [{"text": "x"}]}], "search": False}))
    assert "data-no-search" not in on and "data-no-search" in off
