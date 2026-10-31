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

"""The ViewModels the renderer's parity suite draws two ways (CLIENT_RENDERER.md section 14): the captured views under fixtures/,
and the edge cases of VIEWMODEL.md built on the all-panels showcase (every kind, every empty, denied, error, waits and computed state,
masks, banners, links of every sort, both layouts). Not a test module: ``test_render_parity_browser.py`` imports it."""
from __future__ import annotations

import copy
import json
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SHOWCASE = "view_sutra_all-panels-showcase.json"


def fixture_views() -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(FIXTURES.glob("view_*.json"))}


def showcase() -> dict:
    return json.loads((FIXTURES / SHOWCASE).read_text(encoding="utf-8"))


def _by_id(vm: dict) -> dict:
    return {p["id"]: p for p in vm["panels"]}


def every_kind_empty() -> dict:
    """Every kind with nothing to show (the Sutra's own words for one table)."""
    vm = showcase()
    for p in vm["panels"]:
        p["empty"] = True
    _by_id(vm)["versions"]["data"]["emptyLabel"] = "No versions yet"
    return vm


def states() -> dict:
    """error, the four denied texts, waits, the computed states, an unknown kind and a panel without data."""
    vm = showcase()
    ps = vm["panels"]
    keep = [copy.deepcopy(p) for p in ps[:4]]
    extra = [
        {"id": "err", "kind": "table", "title": "a table that could not bind", "area": "main", "inferred": False, "empty": True, "error": "DRS-2041 rows is not a list"},
        {"id": "den1", "kind": "kv", "title": "denied: kind", "area": "main", "inferred": False, "empty": True, "denied": "no access to curve"},
        {"id": "den2", "kind": "kv", "title": "denied: masked", "area": "main", "inferred": False, "empty": True, "denied": "hidden for you"},
        {"id": "den3", "kind": "kv", "title": "denied: role", "area": "right", "inferred": False, "empty": True, "denied": "not available to your role"},
        {"id": "den4", "kind": "line", "title": "denied: off", "area": "right", "inferred": False, "empty": True, "denied": "not active (turned off by an administrator)"},
        {"id": "wait", "kind": "table", "title": "waits on a click", "area": "main", "inferred": False, "empty": False, "wire": {"waits": "Pick a book above to see its trades"},
         "data": {"columns": ["A"], "numeric": [False], "rows": [], "search": True}},
        {"id": "nodata", "kind": "kv", "title": "no data object", "area": "main", "inferred": True, "explanation": "a guess from the shape", "empty": False, "code": "NODT", "key": "F4", "data": None},
        {"id": "cmp1", "kind": "kv", "title": "computing", "area": "main", "inferred": False, "empty": False,
         "compute": {"state": "computing", "requestId": "r-1", "connector": "var-engine", "progress": {"done": 5, "of": 8}, "hasResult": True},
         "data": {"fields": [{"label": "VaR 99%", "text": "−1.1m", "tone": "neg"}]}},
        {"id": "cmp2", "kind": "kv", "title": "computing, no progress", "area": "main", "inferred": False, "empty": False,
         "compute": {"state": "computing", "requestId": "r-2", "connector": "var-engine", "hasResult": False}, "data": {"fields": []}},
        {"id": "cmp3", "kind": "metric", "title": "stale", "area": "right", "inferred": False, "empty": False,
         "compute": {"state": "stale", "requestId": "r-3", "connector": "exposure", "hasResult": True},
         "data": {"value": {"text": "4.1m"}, "delta": None, "unit": "USD", "caption": None}},
        {"id": "cmp4", "kind": "kv", "title": "failed", "area": "main", "inferred": False, "empty": False,
         "compute": {"state": "failed", "requestId": "r-4", "connector": "var-engine", "message": "no scenarios for 2026-10-29", "hasResult": False}, "data": {"fields": []}},
        {"id": "cmp5", "kind": "kv", "title": "refused", "area": "main", "inferred": False, "empty": False,
         "compute": {"state": "refused", "requestId": "r-5", "connector": "var-engine", "hasResult": True}, "data": {"fields": [{"label": "x", "text": "1"}]}},
        {"id": "odd", "kind": "sankey", "title": "a kind from a newer server", "area": "main", "inferred": False, "empty": False, "data": {"nodes": []}},
    ]
    vm["panels"] = keep + extra
    return vm


def links_and_tones() -> dict:
    """Every link and tone a cell can carry: an entity, an inactive command, an outside URL, a related entity in the title, a key per action."""
    vm = showcase()
    vm["title"]["with"] = {"text": "Northbridge Capital LLP", "link": {"kind": "counterparty", "id": "CP-NORTHBRIDGE", "mnemonic": "CPTY"}}
    vm["strip"].append({"label": "Counterparty", "text": "CP-NORTHBRIDGE", "link": {"kind": "counterparty", "id": "CP-NORTHBRIDGE"}})
    vm["strip"].append({"label": "Desk", "text": "DESK-RATES", "tone": "inactive"})
    cells = [{"label": "Entity", "text": "MX-20000001", "link": {"kind": "trade", "id": "MX-20000001", "mnemonic": "TRD"}},
             {"label": "Off", "text": "NS-NB-FX", "tone": "inactive"},
             {"label": "Site", "text": "https://www.sec.gov/cgi-bin/browse-edgar?CIK=0000320193"},
             {"label": "Tone", "text": "Pending", "tone": "warn"},
             {"label": "Odd", "text": "x", "tone": "sparkly"}]
    vm["panels"].insert(0, {"id": "cells", "kind": "table", "title": "cells of every sort", "area": "main", "inferred": False, "empty": False,
                            "data": {"columns": [c["label"] for c in cells], "numeric": [False] * 5, "search": False,
                                     "rows": [{"cells": cells, "highlight": True, "path": "$.x[0]", "ev": "row-1", "pick": "MX-20000001"},
                                              {"cells": cells[::-1], "highlight": False, "path": "$.x[1]"}]}})
    vm["panels"].insert(1, {"id": "kvl", "kind": "kv", "title": "fields with links", "area": "right", "inferred": False, "empty": False, "data": {"fields": cells}})
    vm["panels"].append({"id": "lk", "kind": "links", "title": "every link status", "area": "right", "inferred": False, "empty": False, "data": {"links": [
        {"label": "Book", "text": "RATES-NY-3", "link": {"kind": "book", "id": "RATES-NY-3"}, "badge": "", "status": "resolved"},
        {"label": "Curve", "text": "USD-SOFR", "badge": "no access", "status": "denied"},
        {"label": "CSA", "text": "CSA-9", "badge": "not active", "status": "inactive"},
        {"label": "Bond", "text": "UST-1", "link": {"kind": "bond", "id": "UST-1"}, "badge": "", "status": "pending"},
        {"label": "Issuer", "text": "ISS-1", "badge": "", "status": "missing"}]}})
    vm["keys"] = [{"key": "F2", "label": "Terms", "action": "panel", "panel": "terms"}, {"key": "F7", "label": "Netting set", "action": "link", "link": {"kind": "netting-set", "id": "NS-NB-RATES"}},
                  {"key": "F8", "label": "Impact", "action": "impact"}, {"key": "F9", "label": "Raw JSON", "action": "raw"}, {"key": "F10", "label": "Curve (no access)", "action": "denied"}]
    return vm


def masked() -> dict:
    """A masked view (VIEWMODEL.md section 6): the strip, a gauge and a metric read •••, banners for a partial page and a late source."""
    vm = showcase()
    for c in vm["strip"]:
        if c["label"].startswith(("Notional", "MTM")):
            c["text"] = "•••"
            c.pop("tone", None)
    b = _by_id(vm)
    b["dv01use"]["data"] = {"value": None, "max": 250000.0, "text": "•••", "label": None}
    b["headline"]["data"]["value"]["text"] = "•••"
    b["headline"]["data"]["delta"] = None
    vm["provenance"].update({"masked": 66, "maskedPanels": ["dv01use", "headline"], "partial": True, "missing": ["node drishti-c (dead): not asked"],
                             "stale": True, "updatedAt": "2026-10-28T09:00:00Z", "staleAfter": "PT15M"})
    return vm


def more_shapes() -> dict:
    """Shapes the showcase does not hold: a drill table, tabs as columns with inner panels, a donut, bars and rows with events, a line with
    every option, a heatmap, a region map with shading and links, grouped and sized points, candles without volume, a force graph, a heat
    pivot, a timeline with more, a metric without change."""
    vm = showcase()
    p = []
    p.append({"id": "drill", "kind": "table", "title": "drill: desks", "area": "main", "inferred": False, "empty": False, "data": {
        "columns": ["Desk", "MTM"], "numeric": [False, True], "search": True, "root": "All desks",
        "levels": [{"title": "Books", "columns": ["Book", "MTM"], "numeric": [False, True]}, {"title": "Trades", "columns": ["Trade", "MTM"], "numeric": [False, True]}],
        "rows": [{"cells": [{"text": "Rates"}, {"text": "+1.2m", "tone": "pos"}], "highlight": False, "path": None, "crumb": "Rates",
                  "children": [{"cells": [{"text": "BOOK-1"}, {"text": "+0.9m", "tone": "pos"}], "highlight": False, "path": None, "crumb": "BOOK-1",
                                "children": [{"cells": [{"text": "T-1", "link": {"kind": "trade", "id": "T-1"}}, {"text": "+0.9m"}], "highlight": False, "path": None}],
                                "kidsTotal": {"cells": [{"text": "Total"}, {"text": "+0.9m"}]}},
                               {"cells": [{"text": "BOOK-2"}, {"text": "+0.3m"}], "highlight": False, "path": None, "crumb": "BOOK-2", "children": []}],
                  "kidsTotal": {"cells": [{"text": "Total"}, {"text": "+1.2m"}]}},
                 {"cells": [{"text": ""}, {"text": "−0.4m", "tone": "neg"}], "highlight": True, "path": None, "crumb": "FX", "children": []}],
        "total": {"cells": [{"text": "Total"}, {"text": "+0.8m"}]}, "more": "3 more desks"}})
    p.append({"id": "cols", "kind": "tabs", "title": "legs side by side", "area": "main", "inferred": False, "empty": False, "data": {"layout": "columns", "tabs": [
        {"title": "Pay leg", "fields": [{"label": "Rate", "text": "3.85%"}, {"label": "Notional", "text": "50m", "tone": "neg"}]},
        {"title": "Receive leg", "fields": [{"label": "Index", "text": "SOFR"}], "panels": [
            {"id": "inner", "kind": "hbar", "title": "inner bars", "code": "INR", "area": "main", "inferred": False, "empty": False,
             "data": {"bars": [{"label": "1Y", "value": 3.0, "text": "3", "tone": "pos"}, {"label": "2Y", "value": "x", "text": "?"}]}}]}]}})
    p.append({"id": "tabin", "kind": "tabs", "title": "tabs with panels", "area": "main", "inferred": False, "empty": False, "data": {"layout": "tabs", "tabs": [
        {"title": "One", "fields": [], "panels": [{"id": "t1", "kind": "markdown", "title": "note", "area": "main", "inferred": False, "empty": False, "data": {"text": "<b>not markup</b> & text"}}]},
        {"title": "Two", "fields": [{"label": "A", "text": "1"}]}]}})
    p.append({"id": "donut", "kind": "hbar", "title": "shares", "area": "right", "inferred": False, "empty": False,
              "data": {"shape": "donut", "total": "9.0m", "bars": [{"label": "Rates", "value": 4.5e6, "text": "4.5m"}, {"label": "FX", "value": -3e6, "text": "−3.0m"}, {"label": "Credit", "value": 1.5e6, "text": "1.5m"}]}})
    p.append({"id": "evbars", "kind": "hbar", "title": "bars that select", "area": "right", "inferred": False, "empty": False,
              "data": {"bars": [{"label": "A", "value": 2.0, "text": "2", "tone": "neg", "ev": "a"}, {"label": "B", "value": 1.0, "text": "1", "ev": "b"}]}})
    p.append({"id": "curve", "kind": "line", "title": "curve", "area": "main", "inferred": False, "empty": False, "data": {
        "x": ["1M", "3M", "1Y", "5Y"], "series": [{"label": "SOFR", "values": [3.9, None, 3.7, 3.6], "tone": "link"}, {"label": "Old", "values": [4.0, 3.9, 3.8, "x"], "tone": "accent"}],
        "mark": "5Y", "markText": "5Y point 3.60%", "source": {"kind": "curve", "id": "USD-SOFR"}, "unit": "%", "step": True, "limit": 3.95, "limitLabel": "cap"}})
    p.append({"id": "heat", "kind": "surface", "title": "heat", "area": "main", "inferred": False, "empty": False,
              "data": {"x": ["a", "b"], "y": ["r1", "r2"], "z": [[1.0, None], [2.5, 3.0]], "min": 1.0, "max": 3.0, "view": "heatmap"}})
    p.append({"id": "region", "kind": "map", "title": "states", "area": "right", "inferred": False, "empty": False, "data": {
        "mode": "region", "region": "USA", "shade": {"label": "GDP", "unit": "bn", "items": [{"code": "US-CA", "value": 3.9, "text": "3.9tn"}, {"code": "US-TX", "value": 2.4, "text": "2.4tn"}]},
        "link": {"kind": "us-state", "template": "ST-{code}", "codes": ["US-CA", "US-TX"]}}})
    p.append({"id": "pts", "kind": "scatter", "title": "grouped", "area": "main", "inferred": False, "empty": False, "data": {
        "points": [{"x": 1.0, "y": 2.0, "size": 4.0, "xText": "1", "yText": "2", "group": "g1", "link": {"kind": "book", "id": "B1"}},
                   {"x": 2.0, "y": 1.0, "size": 9.0, "xText": "2", "yText": "1", "label": "two", "group": "g2"}],
        "groups": ["g1", "g2"], "xLabel": "X", "yLabel": "Y", "sized": True, "more": 7}})
    p.append({"id": "cnd", "kind": "candlestick", "title": "no volume", "area": "main", "inferred": False, "empty": False, "data": {
        "candles": [{"x": "2026-09-01", "open": 1.0, "high": 2.5, "low": 0.5, "close": 2.0}, {"x": "2026-09-02", "open": 2.0, "high": 2.0, "low": 1e-5, "close": 1e17}],
        "volume": False}})
    p.append({"id": "force", "kind": "graph", "title": "force", "area": "main", "inferred": False, "empty": False, "data": {
        "nodes": [{"id": "A", "label": "Alpha", "focus": True, "link": {"kind": "counterparty", "id": "A"}}, {"id": "B", "label": "Beta", "group": "g"}],
        "edges": [{"from": "A", "to": "B"}, {"from": "A", "to": "Z"}], "layout": "force", "more": 2}})
    p.append({"id": "hp", "kind": "pivot", "title": "heat pivot", "area": "main", "inferred": False, "empty": False, "data": {
        "by": "desk", "columns": ["USD", "EUR"], "agg": "sum", "heat": True, "min": -2.0, "max": 7.0, "more": 4,
        "rows": [{"label": "Rates", "cells": [{"text": "−2"}, {"text": "7", "tone": "pos"}], "values": [-2.0, 7.0], "total": {"text": "5"}},
                 {"label": "FX", "cells": [{"text": "2.5"}, {}], "values": [2.5, None]}],
        "totals": [{"text": "0.5"}, {"text": "7"}, {"text": "7.5"}]}})
    p.append({"id": "tl", "kind": "timeline", "title": "events", "area": "right", "inferred": False, "empty": False, "data": {
        "events": [{"date": "2026-01-01", "label": "Booked"}, {"date": "2026-02-01", "label": "Broken", "status": "Failed", "tone": "bad", "detail": "x"}], "more": 12}})
    p.append({"id": "met", "kind": "metric", "title": "plain metric", "area": "right", "inferred": False, "empty": False,
              "data": {"value": {"text": "42", "link": {"kind": "book", "id": "B42"}}, "delta": None, "unit": None, "caption": None}})
    p.append({"id": "gz", "kind": "gauge", "title": "no max", "area": "right", "inferred": False, "empty": False, "data": {"value": 0.25, "max": None, "text": "25%", "label": None}})
    p.append({"id": "wf", "kind": "waterfall", "title": "many steps", "area": "main", "inferred": False, "empty": False, "data": {"unit": "USD", "steps": [
        {"label": f"S{i}", "value": float(i), "from": float(i * (i - 1) / 2), "to": float(i * (i + 1) / 2), "text": f"+{i}", "total": False} for i in range(1, 9)]}})
    p.append({"id": "hg", "kind": "histogram", "title": "two markers", "area": "main", "inferred": False, "empty": False, "data": {
        "bins": [{"from": 0.0, "to": 1.0, "count": 3, "label": "0 to 1"}, {"from": 1.0, "to": 2.0, "count": 1, "label": "1 to 2"}],
        "markers": [{"label": "VaR", "value": 0.5, "text": "0.5", "tone": "neg"}, {"value": 1.5, "text": "1.5"}], "count": 4, "dropped": 2, "unit": "m"}})
    p.append({"id": "big", "kind": "table", "title": "a long table", "area": "main", "inferred": False, "empty": False, "data": {
        "columns": ["#", "Amount", "Date"], "numeric": [False, True, False], "search": True, "more": "10 more trades",
        "rows": [{"cells": [{"text": str(i)}, {"text": f"{(i * 7919) % 1000 - 500:,}".replace("-", "−")}, {"text": f"2026-0{1 + i % 9}-1{i % 10}"}], "highlight": False, "path": f"$.r[{i}]"}
                 for i in range(40)]}})
    vm["panels"] = p
    vm["title"]["pill"] = None
    return vm


def grid_areas() -> dict:
    """A Sutra layout: named areas with a split, a tabbed area and a sticky head (RACHANA_REFERENCE.md 13)."""
    vm = showcase()
    ids = [p["id"] for p in vm["panels"]]
    vm["layout"] = {"areas": ["head head", "body side"], "columns": [8, 4], "arrange": False, "tabs": ["side"], "sticky": "head",
                    "split": {"between": ["body", "side"], "resizable": True, "remember": "user"}, "phone": ["side", "terms"],
                    "place": {**{i: {"area": "body"} for i in ids}, "terms": {"area": "head"}, "ops": {"area": "head", "order": 0},
                              "note": {"area": "side"}, "refs": {"area": "side"}, "headline": {"area": "side", "order": 1}, "pnl": {"area": "body", "collapsible": True}}}
    return vm


def grid_flow() -> dict:
    """A Sutra layout without areas: the flow grid with sections, one folded, and a collapsible panel."""
    vm = showcase()
    vm["layout"] = {"arrange": False, "sections": [{"id": "risk", "title": "Risk"}, {"id": "ops", "title": "Operations", "collapsed": True}],
                    "place": {"explain": {"section": "risk"}, "pnl": {"section": "risk", "collapsible": True}, "dv01": {"section": "risk"},
                              "life": {"section": "ops"}, "versions": {"section": "ops"}, "terms": {"order": 0}}}
    return vm


def edge_cases() -> dict[str, dict]:
    return {"showcase": showcase(), "every-kind-empty": every_kind_empty(), "states": states(), "links-and-tones": links_and_tones(),
            "masked": masked(), "more-shapes": more_shapes(), "grid-areas": grid_areas(), "grid-flow": grid_flow()}
