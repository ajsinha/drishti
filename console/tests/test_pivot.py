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

"""The Pivot tab (USER_GUIDE.md, The Pivot tab): offered only where the Sutra or the pack opts in, the user's saved
arrangement on the page, the JSON routes to the server's engines (records, cube, drill-down, values) with their problems,
saved pivots, an author's promotion with its diff, the grid's CSV and Excel export, and heat shading that keeps contrast."""
import copy
import io
import json
import re
import zipfile
from pathlib import Path

import pytest

from core.backend import BackendError
from core.pivots import Pivots

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SPEC = {"fields": [{"name": "product", "label": "Product", "numeric": False, "fmt": None},
                   {"name": "currency", "label": "Currency", "numeric": False, "fmt": None},
                   {"name": "mtm", "label": "MTM (USD)", "numeric": True, "fmt": "signed0"}],
        "rows": ["product"], "columns": ["currency"], "values": [{"field": "mtm", "agg": "sum"}], "filters": [], "heat": False,
        "chart": None, "totals": True, "maxRows": 50000}


def _ns(pivot=True):
    vm = json.loads((FIXTURES / "view_netting-set_NS-NORTH-01.json").read_text())
    vm["provenance"]["sutra"] = "netting-set"
    if pivot:
        trades = next(p for p in vm["panels"] if p["id"] == "trades")
        trades["data"]["pivot"] = copy.deepcopy(SPEC)
    return vm


@pytest.fixture()
def pivot_backend(backend, monkeypatch):
    """The fake server, opting the netting set's trades into a Pivot tab and keeping saved pivots in memory."""
    state = {"kept": {}, "promote": False, "calls": []}

    async def view(kind, id_, user):
        if (kind, id_) == ("netting-set", "NS-NORTH-01"):
            return _ns(state.get("opt", True))
        raise BackendError(404, "DRS-1001", f"no source holds {kind}/{id_}")

    async def pivots(ident):
        state["calls"].append("pivots")
        return {"enabled": True, "promote": state["promote"], "review": True, "pivots": list(state["kept"].values())}

    async def saved_pivot(method, scope, ident, body=None):
        state["calls"].append((method, scope, body))
        if method == "PUT":
            if any(r not in ("product", "currency", "mtm") for r in body.get("rows") or []):
                raise BackendError(400, "DRS-5001", "pivot rows name 'desk', which is not one of its fields (currency, mtm, product)")
            parts = scope.split("/")
            doc = dict(body, scope=parts[0], updatedAt="2026-10-01T00:00:00Z")
            doc.update({"sutra": parts[1], "panel": parts[2], "kind": "netting-set"} if parts[0] == "panel" else {"kind": parts[1]})
            state["kept"][scope] = doc
            return doc
        if method == "DELETE":
            if state["kept"].pop(scope, None) is None:
                raise BackendError(404, "DRS-1001", "no saved pivot")
            return None
        if scope not in state["kept"]:
            raise BackendError(404, "DRS-1001", "no saved pivot")
        return state["kept"][scope]

    async def panel_records(kind, id_, panel, ident):
        state["calls"].append(("records", kind, id_, panel))
        if panel != "trades":
            raise BackendError(404, "DRS-1001", f"the view of {id_} has no panel '{panel}' that offers a pivot")
        return {"panel": panel, "fields": [{"name": "product"}, {"name": "mtm"}], "rows": [["IRS", 1], ["XCS", -2]], "total": 2,
                "truncated": False, "limit": 50000}

    async def search_pivot(kind, body, ident, part=""):
        state["calls"].append(("search", kind, part, body))
        if not body.get("documents") and part == "":
            raise BackendError(400, "DRS-5001", "no source of trade keeps its fields as columns here. Ask for a document read "
                                                "(documents: true) to read up to 20000 trade documents instead")
        return {"source": "documents", "partial": False, "rowKeys": [["BOOK-A"]], "columnKeys": [], "cells": {}, "part": part}

    async def pivot_promotion(scope, ident, note=None):
        if note is None:
            base = "rachana: 1\nsutra: netting-set\nversion: 1\npanels:\n  - id: trades\n    kind: table\n    pivot: true\n"
            return {"sutra": "netting-set", "panel": "trades", "fromVersion": 1, "version": 2, "review": True, "base": base,
                    "text": base.replace("version: 1", "version: 2").replace("pivot: true", "pivot:\n      rows: [currency]"),
                    "changes": ["the pivot of 'trades' opens with rows currency (was none)"]}
        state["calls"].append(("promote", scope, note))
        return {"proposal": {"id": "P-000077", "name": "netting-set", "version": 2, "status": "pending"}}

    async def search(q, ident=None):
        return {"query": q, "kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm"], "labels": {"$.mtm": "MTM"}, "matched": 1,
                "scanned": 1, "partial": False, "elapsedMs": 1.0, "pivot": copy.deepcopy(state.get("searchPivot")),
                "rows": [{"ref": {"kind": "trade", "id": "IRS-1"}, "title": "IRS-1", "values": {"$.mtm": 5}}]}

    async def search_compare(q, from_, to, ident=None):
        out = await search(q)
        out["rows"][0]["values"] = {"$.mtm": {"from": 1, "to": 5, "delta": 4}}
        return out

    for name, fn in {"view": view, "pivots": pivots, "saved_pivot": saved_pivot, "panel_records": panel_records,
                     "search_pivot": search_pivot, "pivot_promotion": pivot_promotion, "search": search, "search_compare": search_compare}.items():
        monkeypatch.setattr(backend, name, fn, raising=False)
    return state


@pytest.fixture(autouse=True)
def _fresh(client):
    client.app.state.pivots._kept.clear()
    yield
    client.app.state.pivots._kept.clear()


def test_a_table_without_the_option_has_no_pivot_tab(client, pivot_backend):
    pivot_backend["opt"] = False
    html = client.get("/v/netting-set/NS-NORTH-01").text
    assert "data-pv-tab" not in html and "pv-host" not in html and "data-pivot=" not in html
    assert 'class="tbl"' in html                                   # the table itself, as always


def test_a_table_whose_sutra_opts_in_offers_table_and_pivot(client, pivot_backend):
    html = client.get("/v/netting-set/NS-NORTH-01").text
    assert html.count('data-pv-tab="pivot"') == 1 and 'data-pv-tab="table"' in html   # one panel opts in, the others do not
    host = re.search(r'<div class="pv-host"[^>]*>', html).group(0)
    assert 'data-pivot-panel="trades"' in host and "hidden" in host and "data-pivot-saved" not in host
    spec = json.loads(re.search(r'data-pivot="([^"]*)"', host).group(1).replace("&#34;", '"').replace("&quot;", '"').replace("&amp;", "&"))
    assert spec["rows"] == ["product"] and spec["fields"][2]["label"] == "MTM (USD)"
    assert 'data-view-sutra="netting-set"' in html and "data-pivot-promote" not in html
    for js in ("pivot-engine.js", "pivot-grid.js", "pivot.js"):
        assert f"/static/js/{js}" in html
    assert "/static/css/pivot.css" in html


def test_the_users_saved_arrangement_and_the_promote_power_reach_the_page(client, pivot_backend):
    pivot_backend["kept"]["panel/netting-set/trades"] = {"scope": "panel", "sutra": "netting-set", "panel": "trades", "kind": "netting-set",
                                                         "rows": ["currency"], "values": [{"field": "mtm", "agg": "avg"}], "heat": True,
                                                         "updatedAt": "2026-10-01T00:00:00Z"}
    pivot_backend["promote"] = True
    html = client.get("/v/netting-set/NS-NORTH-01").text
    saved = re.search(r'data-pivot-saved="([^"]*)"', html).group(1).replace("&#34;", '"').replace("&quot;", '"')
    assert json.loads(saved) == {"rows": ["currency"], "values": [{"field": "mtm", "agg": "avg"}], "heat": True}   # only the arrangement
    assert "data-pivot-promote" in html and "data-pivot-review" in html
    assert Pivots.saved_panel({"pivots": [{"scope": "panel", "sutra": "x", "panel": "p", "rows": []}]}, "x", "p") == {"rows": []}
    assert Pivots.saved_panel({"pivots": []}, None, "p") is None


def test_records_come_from_the_server_and_problems_keep_their_status(client, pivot_backend):
    r = client.get("/api/pivot/records/netting-set/NS-NORTH-01/trades")
    assert r.status_code == 200 and r.json()["rows"] == [["IRS", 1], ["XCS", -2]]
    assert ("records", "netting-set", "NS-NORTH-01", "trades") in pivot_backend["calls"]
    bad = client.get("/api/pivot/records/netting-set/NS-NORTH-01/exposure")
    assert bad.status_code == 404 and bad.json() == {"code": "DRS-1001", "detail": "the view of NS-NORTH-01 has no panel 'exposure' that offers a pivot"}


def test_search_results_offer_a_pivot_only_when_the_pack_says_so(client, pivot_backend):
    pivot_backend["searchPivot"] = None
    html = client.get("/s", params={"q": "TRD where mtm > 0"}).text
    assert "pv-host" not in html and "pivot.js" not in html
    pivot_backend["searchPivot"] = dict(copy.deepcopy(SPEC), fields=[dict(f, promoted=False) for f in SPEC["fields"]])
    pivot_backend["kept"]["search/trade"] = {"scope": "search", "kind": "trade", "rows": ["currency"]}
    html = client.get("/s", params={"q": "TRD where mtm > 0"}).text
    host = re.search(r'<div class="pv-host"[^>]*>', html).group(0)
    assert 'data-pivot-search="trade"' in host and 'data-pivot-q="TRD where mtm &gt; 0"' in host and "data-pivot-saved" in host
    assert "/static/vendor/echarts/echarts.min.js" in html and "/static/js/pivot.js" in html
    compared = client.get("/s", params={"q": "TRD where mtm > 0", "vs": "2026-09-29"}).text      # a comparison has no Pivot tab
    assert "pv-host" not in compared


def test_the_search_engine_is_asked_through_the_console_with_only_known_keys(client, pivot_backend):
    body = {"q": "TRD", "rows": ["book"], "values": [{"field": "mtm", "agg": "sum"}], "evil": "x"}
    refused = client.post("/api/pivot/search/trade", json=body)
    assert refused.status_code == 400 and "documents: true" in refused.json()["detail"] and refused.json()["code"] == "DRS-5001"
    ok = client.post("/api/pivot/search/trade", json=dict(body, documents=True))
    assert ok.status_code == 200 and ok.json()["source"] == "documents"
    sent = [c for c in pivot_backend["calls"] if c[0] == "search"][-1]
    assert "evil" not in sent[3] and sent[3]["documents"] is True and sent[2] == ""
    client.post("/api/pivot/search/trade/drill", json=dict(body, cell={"rows": ["BOOK-A"], "columns": []}, offset=0, size=50))
    assert [c for c in pivot_backend["calls"] if c[0] == "search"][-1][2] == "drill"
    client.post("/api/pivot/search/trade/values", json={"q": "TRD", "field": "currency"})
    last = [c for c in pivot_backend["calls"] if c[0] == "search"][-1]
    assert last[2] == "values" and last[3] == {"q": "TRD", "field": "currency"}


def test_saved_pivots_keep_only_the_arrangement_and_are_forgotten_on_change(client, pivot_backend):
    r = client.put("/api/pivot/saved/panel/netting-set/trades", json={"rows": ["currency"], "values": [], "scope": "evil", "sutra": "x"})
    assert r.status_code == 200 and r.json()["rows"] == ["currency"]
    put = [c for c in pivot_backend["calls"] if isinstance(c, tuple) and c[0] == "PUT"][-1]
    assert put[1] == "panel/netting-set/trades" and put[2] == {"rows": ["currency"], "values": []}
    bad = client.put("/api/pivot/saved/panel/netting-set/trades", json={"rows": ["desk"]})
    assert bad.status_code == 400 and "'desk'" in bad.json()["detail"]
    assert client.get("/api/pivot/saved/panel/netting-set/trades").json()["rows"] == ["currency"]
    assert client.delete("/api/pivot/saved/panel/netting-set/trades").json() == {"ok": True}
    assert client.delete("/api/pivot/saved/panel/netting-set/trades").json() == {"ok": True}     # nothing to forget is fine
    assert client.get("/api/pivot/saved/panel/netting-set/trades").status_code == 404
    assert client.put("/api/pivot/saved/search/trade", json={"rows": ["product"]}).json()["kind"] == "trade"
    assert client.delete("/api/pivot/saved/search/trade").json() == {"ok": True}


def test_promotion_shows_the_diff_and_proposes_for_review(client, pivot_backend):
    p = client.get("/api/pivot/saved/panel/netting-set/trades/promotion").json()
    assert p["fromVersion"] == 1 and p["version"] == 2 and p["changes"] == ["the pivot of 'trades' opens with rows currency (was none)"]
    lines = [tuple(x) for x in p["diff"]]
    assert any(cls == "del" and "pivot: true" in text for cls, text in lines) or any("pivot: true" in text for _, text in lines)
    assert any("rows: [currency]" in text for _, text in lines)
    r = client.post("/api/pivot/saved/panel/netting-set/trades/promotion", json={"note": "desk default"})
    assert r.json()["href"] == "/studio/reviews/P-000077" and r.json()["proposal"]["version"] == 2
    assert ("promote", "panel/netting-set/trades", "desk default") in pivot_backend["calls"]


def test_the_grid_exports_as_csv_and_as_an_excel_workbook(client):
    grid = {"name": "NS-NORTH-01-trades-pivot", "header": ["Product", "USD · Sum of MTM", "Total"],
            "rows": [["Interest rate swap", -412580, 1.5], ["=HYPERLINK(1)", 2, None], ["Total", -412578, 1.5]]}
    csv = client.post("/export/grid.csv", json=grid)
    assert csv.status_code == 200 and csv.headers["content-type"].startswith("text/csv")
    assert 'filename="NS-NORTH-01-trades-pivot.csv"' in csv.headers["content-disposition"]
    text = csv.content.decode("utf-8-sig")
    assert text.splitlines()[0] == "Product,USD · Sum of MTM,Total" and "Interest rate swap,-412580,1.5" in text and "'=HYPERLINK(1)" in text
    x = client.post("/export/grid.xlsx", json=grid)
    assert x.status_code == 200 and x.headers["content-type"].startswith("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    z = zipfile.ZipFile(io.BytesIO(x.content))
    assert {"[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml", "_rels/.rels"} <= set(z.namelist())
    sheet = z.read("xl/worksheets/sheet1.xml").decode()
    assert '<c r="A1" t="inlineStr" s="1"><is><t xml:space="preserve">Product</t></is></c>' in sheet      # bold header
    assert '<c r="B2"><v>-412580</v></c>' in sheet and '<c r="C2"><v>1.5</v></c>' in sheet                   # numbers stay numbers
    assert "<t xml:space=\"preserve\">=HYPERLINK(1)</t>" in sheet and "<f>" not in sheet                      # text, never a formula
    assert 'state="frozen"' in sheet
    empty = client.post("/export/grid.xlsx", content=b"not json")
    assert empty.status_code == 200 and zipfile.ZipFile(io.BytesIO(empty.content)).testzip() is None


def test_heat_shading_keeps_text_readable_in_every_theme():
    """Ink on the strongest heat tint (accent, positive and negative over the surface, scaled per theme) stays at 4.5:1."""
    from tests.test_contrast import _ratio, _theme
    css = (Path(__file__).resolve().parent.parent / "web/static/css/pivot.css").read_text()
    steps = re.findall(r"color-mix\(in srgb, var\(--d-(?:accent|pos|neg)\) calc\((\d+)% \* var\(--pv-heat\)\), var\(--d-surface\)\)", css)
    assert len(steps) == 30, "ten steps for each of accent, positive and negative"
    strongest = max(int(m) for m in steps)
    assert strongest <= 38
    scale = {name: float(v) for name, v in re.findall(r':root\[data-theme="([a-z-]+)"\] \{ --pv-heat: ([0-9.]+); \}', css)}

    def mix(a, b, w):
        ca = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
        cb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
        return "#" + "".join(f"{round(x * w + y * (1 - w)):02x}" for x, y in zip(ca, cb))

    for name in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark"):
        t = _theme(name)
        for tone in ("accent", "pos", "neg"):
            bg = mix(t[tone], t["surface"], strongest * scale.get(name, 1.0) / 100)
            assert _ratio(t["ink"], bg) >= 4.5, (name, tone, round(_ratio(t["ink"], bg), 2))
