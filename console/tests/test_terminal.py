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
        assert "No data held for 2026-09-25" in past                 # the fixture has no business date
    finally:
        client.cookies.delete("drishti_asof")
    assert client.get("/asof", params={"d": "live", "next": "//evil.example"}, follow_redirects=False).headers["location"] == "/t"


def test_a_past_date_no_store_holds_says_so_and_does_not_pass_current_data_off_as_live(client):
    """UX-08: a date missing from the dated store showed the undated source's current data, ticking, under "aero-risk is
    not a dated source": the date is simply not held. The view says so, is a still snapshot, and never shows Live."""
    client.cookies.set("drishti_asof", "2026-09-26")
    try:
        for params in ({}, {"embed": 1}):                            # the page, and a workspace pane (no top bar)
            past = client.get("/v/trade/IRS-48213", params=params).text
            assert "is not a dated source" not in past
            assert "No data held for 2026-09-25" in past and "no dated store has IRS-48213 for that date" in past
            assert "the current data of <b>aero-risk</b>" in past and "does not update" in past
            assert re.search(r'<div class="view"[^>]*\bdata-static\b', past), "a picked date is a still snapshot, in a pane too"
            assert 'data-live-state="live"' not in past
    finally:
        client.cookies.delete("drishti_asof")
    live = client.get("/v/trade/IRS-48213").text
    assert "No data held" not in live and 'data-live-state="live"' in live and not re.search(r'<div class="view"[^>]*\bdata-static\b', live)


def test_backend_calls_carry_the_business_date_header():
    from core import asof
    assert asof.headers() == {}
    asof.set_current("2026-09-29")
    assert asof.headers() == {"X-Drishti-As-Of": "2026-09-29"}
    asof.set_current("not-a-date")
    assert asof.headers() == {}


KINDS = ["kv", "status", "provenance", "table", "ladder", "tabs", "line", "area", "hbar", "links", "markdown", "gauge", "surface",
         "waterfall", "histogram", "scatter", "candlestick", "graph", "timeline", "pivot"]
BROKEN = [None, {}, {"fields": None, "rows": None, "tabs": None, "x": None, "series": None, "bars": None, "links": None},
          {"columns": ["A"], "numeric": [], "rows": [{"cells": None}], "x": ["1Y"], "series": [{"label": "s", "values": [None]}],
           "bars": [{"label": "a", "value": None, "text": "—"}], "tabs": [{"title": "t", "fields": None}], "value": None, "max": None},
          {"steps": [{"label": None, "text": None}, {}], "bins": [{}], "markers": [{"value": None}], "points": [{"link": {}}], "groups": None,
           "candles": [{}], "volume": True, "nodes": [{"id": None, "link": None}], "edges": None, "events": [{"date": None}, {}],
           "rows": [{"label": "r", "cells": [{"text": "1"}], "values": [None, "x"], "total": None}], "totals": [{}], "heat": True,
           "min": 5, "max": 1, "more": 3, "count": None, "dropped": 2}]


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


def _render(client, kind, data, title="X"):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    return tpl.render(p={"id": "x", "kind": kind, "title": title, "area": "main", "empty": False, "data": data})


def test_chart_kinds_carry_their_data_for_charts_js_and_a_data_table(client):
    w = _render(client, "waterfall", {"steps": [{"label": "Opening", "value": 100, "from": 0, "to": 100, "text": "100", "tone": "link", "total": True},
                                                {"label": "Carry", "value": -20, "from": 100, "to": 80, "text": "−20", "tone": "neg", "total": False}]})
    assert 'data-xchart=' in w and 'data-kind="waterfall"' in w and "2 steps, ending at −20" in w and '<th scope="row">Carry</th>' in w
    h = _render(client, "histogram", {"bins": [{"from": -2, "to": 0, "count": 3, "label": "−2 to 0"}], "count": 3, "dropped": 1,
                                      "markers": [{"label": "VaR 99%", "value": -1.5, "text": "−1.5", "tone": "neg"}]})
    assert "VaR 99%" in h and "sw-neg" in h and "3 values" in h and "1 left out" in h
    s = _render(client, "scatter", {"points": [{"x": 1, "y": 2, "xText": "1", "yText": "2", "label": "BOOK-RATES-1", "group": "Rates",
                                                "link": {"kind": "book", "id": "BOOK-RATES-1"}}], "groups": ["Rates"], "xLabel": "VaR", "yLabel": "P&L"})
    assert 'href="/v/book/BOOK-RATES-1"' in s and "1 points, VaR against P&amp;L" in s
    c = _render(client, "candlestick", {"candles": [{"x": "2026-09-29", "open": 1, "high": 2, "low": 0.5, "close": 1.5, "volume": 10}],
                                        "volume": True, "last": "1.50", "change": "+0.50 (+50.00%)", "tone": "pos"})
    assert "chart xchart candles" in c and "Volume" in c and "t-pos" in c
    g = _render(client, "graph", {"nodes": [{"id": "GRP-A", "label": "Group A", "group": "Group", "link": {"kind": "counterparty-group", "id": "GRP-A"}},
                                            {"id": "CP-A", "label": "A", "group": "Counterparty", "focus": True}],
                                  "edges": [{"from": "GRP-A", "to": "CP-A"}], "layout": "tree"})
    assert "2 entities, 1 relations" in g and 'href="/v/counterparty-group/GRP-A"' in g and "this view" in g
    t = _render(client, "timeline", {"events": [{"date": "2026-01-02", "label": "Booked", "status": "Done", "tone": "ok", "detail": "Captured"}], "more": 2})
    assert 'class="tl"' in t and "tl-i t-ok" in t and "Captured" in t and "2 earlier events" in t
    v = _render(client, "pivot", {"by": "book", "columns": ["USD", "EUR"], "agg": "sum", "heat": True, "min": 1, "max": 10,
                                  "rows": [{"label": "B1", "cells": [{"text": "1"}, {"text": "10"}], "values": [1, 10], "total": {"text": "11"}}],
                                  "totals": [{"text": "1"}, {"text": "10"}, {"text": "11"}]})
    assert "pivot heat" in v and "heat-0" in v and "heat-9" in v and ">11<" in v and "Total" in v


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


def test_a_search_a_source_failed_names_the_source_and_why(client, backend):
    """DATA-01: a failing source is not "no matches": the page names it and why."""
    async def search(q, ident=None):
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm"], "labels": {"$.mtm": "MTM"}, "rows": [],
                "scanned": 0, "matched": 0, "partial": True, "elapsedMs": 4.2,
                "failed": [{"source": "trading-store", "reason": "trade 2026-09-30 cannot be read: the native Delta engine does not decompress LZ4"}]}
    backend.search = search
    page = client.get("/s", params={"q": "TRD where mtm < -100m"}).text
    assert "Incomplete: 1 source could not be read" in page
    assert "trading-store" in page and "does not decompress LZ4" in page
    assert "results may be incomplete" not in page                           # the banner says it, with the reason


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
    """Browsers allow six connections per site: every live feature shares the browser's one channel (channel.js, which
    hands every tab's subscriptions to the hub in live-hub.js, the only code that opens it)."""
    from pathlib import Path
    js = Path(__file__).resolve().parent.parent / "web" / "static" / "js"
    offenders = [f.name for f in js.glob("*.js") if "EventSource(" in f.read_text() and f.name != "live-hub.js"]
    assert offenders == []
    page = (Path(__file__).resolve().parent.parent / "web" / "templates" / "base.html").read_text()
    assert page.index("live-hub.js") < page.index("channel.js") < page.index("alerts.js")


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
        if text.strip() == "TRD MX-200000":
            return {"ref": None, "mnemonic": "TRD", "list": "TRD MX-200000", "matched": 12}
        return {"ref": {"kind": "trade", "id": "MX-20000001"}, "mnemonic": "TRD"}
    monkeypatch.setattr(backend, "command", command, raising=False)
    r = client.get("/go", params={"q": "TRD MX-200000"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/s?q=TRD%20MX-200000"
    one = client.get("/go", params={"q": "TRD MX-20000001"}, follow_redirects=False)
    assert one.headers["location"] == "/v/trade/MX-20000001"


def test_readiness_says_whether_the_server_answers(client, backend, monkeypatch):
    assert client.get("/readyz").json()["status"] == "UP"
    seen = []
    async def spy(ident=None):
        seen.append(ident)
        return {}
    monkeypatch.setattr(backend, "business_date", spy, raising=False)
    client.get("/readyz")
    assert seen and seen[0] is not None and "service" in seen[0].roles      # SEC-12: readiness carries the service identity
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
    assert client.get("/api/history").json() == ["TRD MX-20000001", "MKT"]
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


def test_packs_survive_a_server_restart(client, backend):
    import asyncio
    from core.backend import BackendError as BE
    packs = client.app.state.packs
    packs.forget_all()
    first = asyncio.run(packs.assigned(backend, None))
    real = backend.packs
    async def down(ident=None):
        raise BE(503, "DRS-5003", "backend unreachable")
    backend.packs = down
    packs._cache = {k: (0, v[1], v[2]) for k, v in packs._cache.items()}   # expired: a fetch is due
    assert asyncio.run(packs.assigned(backend, None)) == first             # the server is away: keep what was known
    packs.forget_all()
    asyncio.run(packs.assigned(backend, None))                              # no knowledge: the fallback, not cached
    assert packs._cache == {}
    backend.packs = real



def test_people_make_and_revoke_api_tokens(client, backend):
    page = client.get("/account").text
    assert "API tokens" in page and "Risk notebook" in page and 'data-token="abc123def456"' in page
    made = client.post("/api/tokens", json={"name": "Excel", "days": "90"}).json()
    assert made["secret"].startswith("drk_") and backend.tokens_made[-1] == ("Excel", 90)
    assert client.post("/api/tokens", json={"name": " "}).status_code == 400
    assert client.post("/api/tokens", json={"name": "x", "days": "soon"}).status_code == 400
    assert client.delete("/api/tokens/abc123def456").json()["ok"] is True



def test_field_history_and_searches_compared_between_dates(client):
    r = client.get("/api/series/trade/T-1", params={"path": "$.mtm", "days": 30}).json()
    assert [p["value"] for p in r["points"]] == [110, 125]
    page = client.get("/s", params={"q": "TRD where mtm > 0", "vs": "2026-09-28"}).text
    assert "125  (+25)" in page and "added" in page and 'value="2026-09-28"' in page
    assert "history.js" in client.get("/v/trade/IRS-48213").text
    module = client.app.state.templates.env.get_template("_macros/panels.html").module
    html = str(module.table({"columns": ["MTM"], "numeric": [True], "rows": [{"cells": [{"text": "125", "path": "$.mtm"}]}]}))
    assert 'data-path="$.mtm"' in html


def test_a_view_says_how_fresh_its_data_is_and_warns_when_stale(client, monkeypatch):
    import json as _json
    from tests.conftest import FIXTURES
    vm = _json.loads((FIXTURES / "view_trade_IRS-48213.json").read_text())
    vm["provenance"].update({"updatedAt": "2026-09-30T20:00:00Z", "staleAfter": "PT15M", "stale": True})
    backend = client.app.state.backend

    async def stale_view(kind, id_, user):
        return vm
    monkeypatch.setattr(backend, "view", stale_view)
    page = client.get("/v/trade/IRS-48213").text
    assert "is behind" in page and "15m" in page and 'data-age="2026-09-30T20:00:00Z"' in page
    vm["provenance"].update({"stale": False})
    page = client.get("/v/trade/IRS-48213").text
    assert "is behind" not in page and "updated <span data-age" in page



def test_notes_on_an_entity_and_its_fields(client, backend):
    page = client.get("/v/trade/IRS-48213").text
    assert "data-notes-open" in page and "notes.js" in page and 'data-me="' in page
    r = client.post("/api/notes/trade/IRS-48213", json={"body": "Restated after the fixing", "path": "$.mtm"}).json()
    assert r["author"] and r["path"] == "$.mtm"
    assert [n["body"] for n in client.get("/api/notes/trade/IRS-48213").json()] == ["Restated after the fixing"]
    assert client.put(f"/api/notes/{r['id']}", json={"body": "Restated twice"}).json()["body"] == "Restated twice"
    assert client.delete(f"/api/notes/{r['id']}").json() == {"ok": True}
    assert client.get("/api/notes/trade/IRS-48213").json() == []


def test_workspaces_shared_with_you_and_sharing_yours(client, backend):
    index = client.get("/w").text
    assert "Shared with you" in index and "/w/shared/ravi/Rates%20desk" in index
    shared = client.get("/w/shared/ravi/Rates%20desk").text
    assert "shared by ravi · read-only" in shared and "data-readonly" in shared and "Save a copy" in shared and "data-save>" not in shared
    assert client.get("/w/shared/ravi/nope").status_code == 404
    r = client.post("/w/api/desk/share", json={"roles": ["risk"], "users": ["tess"]}).json()
    assert r["roles"] == ["risk"] and backend.shares["desk"]["users"] == ["tess"]
    assert client.post("/w/api/desk/share", json={"stop": True}).json() == {"shared": False} and "desk" not in backend.shares



def test_scheduled_reports_page(client, backend):
    assert "Schedule…" in client.get("/s", params={"q": "TRD where mtm < 0"}).text
    page = client.get("/reports", params={"q": "TRD where mtm < 0"}).text
    assert 'value="TRD where mtm &lt; 0"' in page and "business-days 18:30" in page
    bad = client.post("/reports", content="name=x&query=TRD&schedule=every+tuesday&deliver=folder&date=today&enabled=on",
                      headers={"Content-Type": "application/x-www-form-urlencoded"})
    assert bad.status_code == 400 and "not a schedule" in bad.text and 'value="x"' in bad.text
    r = client.post("/reports", content="name=Losers&query=TRD+where+mtm+%3C+0&schedule=business-days+18%3A30&deliver=folder&date=previous&enabled=on",
                    headers={"Content-Type": "application/x-www-form-urlencoded"}, follow_redirects=False)
    assert r.status_code == 303 and backend.reports_kept["Losers"]["date"] == "previous"
    assert "12 rows to /data/reports/ash/x.csv" in client.post("/reports/Losers/run").text
    listed = client.get("/reports").text
    assert "Losers" in listed and "the business day before" in listed and "Run now" in listed
    client.post("/reports/Losers/delete")
    assert "Losers" not in backend.reports_kept



def test_plain_words_show_the_search_they_make(client, backend):
    page = client.get("/s", params={"words": "live trades over 5m for the snack desk"}).text
    assert "Your words make this search" in page and "TRD where status = &#39;Live&#39; and mtm &gt; 5000000" in page
    assert "“over 5m”" in page and "Not understood" in page and "snack" in page and "Run it" in page
    assert "say what to look for" in client.get("/s", params={"words": "the weather"}).text


def test_words_that_are_not_a_command_go_to_the_phrase_box(client):
    r = client.get("/go", params={"q": "live trades over 5m <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/s?words=live%20trades%20over%205m"
    assert client.get("/go", params={"q": "NOPE <GO>"}, follow_redirects=False).headers["location"].startswith("/t?error=")
