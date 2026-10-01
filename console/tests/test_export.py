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


"""W18: exports carry what the screen shows (panel, search, comparison, document) and a share link reproduces the view."""
import csv
import io

from core.export import panel_rows, plain


def _read(body: bytes) -> list[list[str]]:
    assert body.startswith("﻿".encode("utf-8"))                   # a BOM, so spreadsheets read UTF-8
    return list(csv.reader(io.StringIO(body.decode("utf-8-sig"))))


def test_numbers_as_shown_become_plain_numbers():
    assert plain("−412,580") == -412580 and plain("+1,234.5") == 1234.5 and plain("4.25%") == 0.0425
    assert plain("BOOK-RATES-3") == "BOOK-RATES-3" and plain("2026-09-30") == "2026-09-30" and plain(None) == ""


def test_every_panel_shape_exports():
    table = {"kind": "table", "data": {"columns": ["Date", "Amount"], "numeric": [False, True],
             "rows": [{"cells": [{"text": "2026-10-01"}, {"text": "−1,250.00"}]}], "total": {"cells": [{"text": "Total"}, {"text": "−1,250.00"}]}}}
    assert panel_rows(table) == (["Date", "Amount"], [["2026-10-01", -1250.0], ["Total", -1250.0]])
    kv = {"kind": "kv", "data": {"fields": [{"label": "Rate", "text": "4.0829%"}]}}
    assert panel_rows(kv) == (["Field", "Value"], [["Rate", 0.040829]])
    line = {"kind": "line", "data": {"x": ["1Y", "2Y"], "series": [{"label": "Zero", "values": [3.6, 3.7]}]}}
    assert panel_rows(line) == (["", "Zero"], [["1Y", 3.6], ["2Y", 3.7]])
    surface = {"kind": "surface", "data": {"x": ["90", "100"], "y": ["1M"], "z": [[0.21, 0.19]]}}
    assert panel_rows(surface) == (["", "90", "100"], [["1M", 0.21, 0.19]])
    bars = {"kind": "hbar", "data": {"bars": [{"label": "5Y", "value": -27722, "text": "−27,722"}]}}
    assert panel_rows(bars)[1] == [["5Y", -27722]]
    assert panel_rows({"kind": "markdown", "data": {"text": "x"}}) == ([], [])
    assert panel_rows({"kind": "table", "empty": True, "data": {}}) == ([], [])


def test_a_panel_downloads_as_csv_with_a_dated_name(client):
    r = client.get("/export/trade/IRS-48213/cashflows.csv")
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
    assert 'filename="IRS-48213-cashflows.csv"' in r.headers["content-disposition"]
    rows = _read(r.content)
    assert len(rows) > 2 and rows[0][0]
    assert client.get("/export/trade/IRS-48213/nope.csv").status_code == 404
    client.cookies.set("drishti_asof", "2026-09-29")
    try:
        assert 'filename="IRS-48213-cashflows-2026-09-29.csv"' in client.get("/export/trade/IRS-48213/cashflows.csv").headers["content-disposition"]
    finally:
        client.cookies.delete("drishti_asof")


def test_document_search_and_comparison_exports(client, backend):
    j = client.get("/export/trade/IRS-48213.json")
    assert j.status_code == 200 and j.json()["data"]["tradeId"] == "IRS-48213" and "attachment" in j.headers["content-disposition"]

    async def search(q, ident=None):
        return {"kind": "trade", "mnemonic": "TRD", "columns": ["$.mtm"], "labels": {"$.mtm": "MTM"},
                "rows": [{"ref": {"kind": "trade", "id": "IRS-48213"}, "title": "IRS-48213", "values": {"$.mtm": -412580.5}}]}
    backend.search = search
    assert _read(client.get("/export/search.csv", params={"q": "TRD where mtm < 0"}).content) == [["TRD", "Title", "MTM"], ["IRS-48213", "IRS-48213", "-412580.5"]]

    async def history_diff(kind, id_, ident=None, **params):
        return {"from": {"businessDate": "2026-09-28"}, "to": {"businessDate": "2026-09-29"},
                "changes": [{"label": "MTM", "path": "mtm", "kind": "changed", "before": 100, "after": 110, "delta": 10}]}
    backend.history_diff = history_diff
    rows = _read(client.get("/export/compare/trade/IRS-48213.csv", params={"from": "2026-09-28"}).content)
    assert rows == [["Field", "Path", "Change", "2026-09-28", "2026-09-29", "Difference"], ["MTM", "mtm", "changed", "100", "110", "10"]]


def test_the_view_offers_share_print_and_csv_and_the_share_link_reproduces_the_date(client):
    page = client.get("/v/trade/IRS-48213").text
    assert 'data-share="http://testserver/v/trade/IRS-48213"' in page and "data-print" in page and "data-export-panel=" in page
    assert '/export/trade/IRS-48213.json' in page
    client.cookies.set("drishti_asof", "2026-09-29")
    client.cookies.set("drishti_knownat", "2026-09-29T14:30:00Z")
    try:
        page = client.get("/v/trade/IRS-48213").text
        assert 'data-share="http://testserver/asof?d=2026-09-29&amp;ki=2026-09-29T14%3A30%3A00Z&amp;next=%2Fv%2Ftrade%2FIRS-48213"' in page
    finally:
        client.cookies.delete("drishti_asof")
        client.cookies.delete("drishti_knownat")
    r = client.get("/asof", params={"d": "2026-09-29", "ki": "2026-09-29T14:30:00Z", "next": "/v/trade/IRS-48213"}, follow_redirects=False)
    cookies = r.headers.get_list("set-cookie")
    client.cookies.clear()                                            # the redirect set them on the shared test client
    assert r.headers["location"] == "/v/trade/IRS-48213" and any("drishti_knownat=2026-09-29T14:30:00Z" in c for c in cookies)


def test_chart_and_aggregate_kinds_export_their_numbers():
    pivot = {"kind": "pivot", "data": {"by": "book", "columns": ["USD"], "rows": [{"label": "B1", "cells": [{"text": "1,000"}], "values": [1000],
                                                                                   "total": {"text": "1,000"}}], "totals": [{"text": "1,000"}, {"text": "1,000"}]}}
    assert panel_rows(pivot) == (["book", "USD", "Total"], [["B1", 1000, 1000], ["Total", 1000, 1000]])
    wf = {"kind": "waterfall", "data": {"steps": [{"label": "Carry", "value": -5, "total": False}]}}
    assert panel_rows(wf) == (["Step", "Amount", "Total"], [["Carry", -5, False]])
    tl = {"kind": "timeline", "data": {"events": [{"date": "2026-01-02", "label": "Booked", "status": "Done"}]}}
    assert panel_rows(tl)[1] == [["2026-01-02", "Booked", "Done", ""]]
    g = {"kind": "graph", "data": {"nodes": [{"id": "A", "label": "a"}], "edges": [{"from": "A", "to": "A"}]}}
    assert panel_rows(g)[1] == [["node", "A", "", "a", ""], ["edge", "A", "A", "", ""]]
    h = {"kind": "histogram", "data": {"bins": [{"from": 0, "to": 1, "count": 4}]}}
    assert panel_rows(h) == (["From", "To", "Count"], [[0, 1, 4]])
