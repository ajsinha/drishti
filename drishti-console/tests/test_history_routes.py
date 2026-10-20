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

"""History queries in the console (HISTORY_QUERIES.md sections 4.3, 5 and 15): the /go redirect, the short link, the pick list with a
range, both shapes drawn from the contract's fixtures, DIFF, and the errors."""
import re

from core.backend import BackendError


def _go(client, backend, monkeypatch, answer):
    async def command(text, ident=None):
        return answer
    monkeypatch.setattr(backend, "command", command)
    return client.get("/go", params={"q": "TRD 1000015 30 <GO>"}, follow_redirects=False)


def test_go_opens_the_history_of_one_entity(client, backend, monkeypatch):
    r = _go(client, backend, monkeypatch, {"ref": {"kind": "trade", "id": "1000015"}, "mnemonic": "TRD",
                                           "history": {"range": "30", "days": 30, "diff": False, "query": "days=30"}})
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/1000015/history?days=30"


def test_go_without_a_range_opens_the_view_as_before(client, backend, monkeypatch):
    r = _go(client, backend, monkeypatch, {"ref": {"kind": "trade", "id": "1000015"}, "mnemonic": "TRD", "history": None})
    assert r.headers["location"] == "/v/trade/1000015"


def test_a_pick_list_carries_the_range_to_its_rows(client, backend, monkeypatch):
    r = _go(client, backend, monkeypatch, {"ref": None, "list": "TRD MX-2000", "matched": 3,
                                           "history": {"query": "range=3M&diff=1"}})
    assert r.headers["location"] == "/s?q=TRD%20MX-2000&range=3M&diff=1"

    async def search(q, ident=None):
        return {"kind": "trade", "mnemonic": "TRD", "columns": [], "labels": {}, "matched": 1, "scanned": 1, "elapsedMs": 1, "partial": False,
                "rows": [{"ref": {"kind": "trade", "id": "MX-20001"}, "title": "MX-20001", "values": {}}]}
    monkeypatch.setattr(backend, "search", search, raising=False)
    page = client.get("/s", params={"q": "TRD MX-2000", "range": "3M", "diff": "1"})
    assert 'href="/v/trade/MX-20001/history?range=3M&amp;diff=1"' in page.text


def test_the_short_link_redirects_to_the_history_page(client):
    r = client.get("/v/trade/1000015?days=30", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/v/trade/1000015/history?days=30"
    r = client.get("/v/trade/1000015?from=2026-09-01&to=2026-09-30&diff=1", follow_redirects=False)
    assert r.headers["location"] == "/v/trade/1000015/history?from=2026-09-01&to=2026-09-30&diff=1"


def test_the_document_shape(client, backend):
    r = client.get("/v/trade/1000015/history?days=30")
    assert r.status_code == 200
    t = r.text
    assert "data-history-ready" in t and 'data-shape="document"' in t
    assert "2026-09-07" in t and "2026-10-19" in t                         # the header's dates
    assert 'title="No data on 2026-09-14"' in t                             # the day with no data, named
    rows = re.findall(r'<tr data-row data-date="([0-9-]+)"', t)
    assert len(rows) == 30 and rows[0] == "2026-10-19" and rows[-1] == "2026-09-07"    # newest first
    assert t.count("data-changed") >= 7
    assert "changed from 72K" in t                                           # a changed cell says what it was (never colour alone)
    assert "⟲2" in t                                                         # the restated day
    assert "•••" in t and "masked" in t                                      # the server's masks, as served
    assert 'href="/v/trade/1000015/history?days=30&amp;diff=1"' in t or "diff=1" in t
    assert '/export/history/trade/1000015.csv?days=30' in t
    assert 'data-href="/v/trade/1000015?asOf=2026-10-15&amp;gen=' in t      # Enter opens the day, pinned
    assert ("history", "trade", "1000015", {"days": "30"}) in backend.calls


def test_a_link_with_no_range_asks_for_thirty_days(client, backend):
    client.get("/v/trade/1000015/history")
    assert backend.calls[-1] == ("history", "trade", "1000015", {"days": "30"})


def test_changes_only_is_kept_in_the_link(client):
    t = client.get("/v/trade/1000015/history?days=30&only=1").text
    assert 'class="hx hx-doc hx-only"' in t and 'aria-pressed="true"' in t


def test_the_market_shape(client):
    t = client.get("/v/equity/EQ-CSCA/history?range=3M").text
    assert 'data-shape="market"' in t and "data-hx-chart" in t and "echarts.min.js" in t
    for k in ("Last", "Chg", "High", "Low", "Range", "Avg"):
        assert f'<span class="hx-k">{k}</span>' in t
    assert "19.54" in t and "2026-08-24" in t                                # last, and the high's date
    assert 'data-hx-range-btn="3M"' in t and 'aria-current="true" data-hx-range-btn="3M"' in t
    assert len(re.findall(r'<tr data-row data-date=', t)) == 66


def test_diff_lists_each_changed_day(client, backend):
    t = client.get("/v/trade/1000015/history?days=30&diff=1").text
    assert backend.calls[-1][3].get("diff") == "true"
    days = re.findall(r'<article class="hx-day" data-row data-date="([0-9-]+)"', t)
    assert days and days == sorted(days, reverse=True)
    assert "/compare/trade/1000015?from=" in t and "Open the day" in t


def test_an_unknown_entity_or_a_refused_range_says_why(client, backend, monkeypatch):
    r = client.get("/v/trade/NOPE-1/history?days=30")
    assert r.status_code == 404 and "DRS-1001" in r.text and "Today’s view of NOPE-1" in r.text

    async def history(kind, id_, ident=None, **params):
        raise BackendError(400, "DRS-4021", "366 business days at most (asked 500): narrow it with FROM … TO")
    monkeypatch.setattr(backend, "history", history)
    r = client.get("/v/trade/1000015/history?days=500")
    assert r.status_code == 400 and "DRS-4021" in r.text and 'href="?days=30"' in r.text


def test_exports(client):
    r = client.get("/export/history/trade/1000015.csv?days=30")
    assert r.status_code == 200 and r.content.startswith("﻿".encode()) and b"\r\n" in r.content
    assert 'filename="1000015-history-2026-09-07-2026-10-19.csv"' in r.headers["content-disposition"]
    lines = r.content.decode("utf-8-sig").splitlines()
    assert lines[0].startswith("Date,Trade status") and lines[1].startswith("2026-10-19") and len(lines) == 31
    assert "•••" in lines[1]                                                 # masked as served
    only = client.get("/export/history/trade/1000015.csv?days=30&only=1").content.decode("utf-8-sig").splitlines()
    assert len(only) == 1 + 7
    j = client.get("/export/history/trade/1000015.json?days=30")
    assert j.json()["ref"] == {"kind": "trade", "id": "1000015"} and j.headers["content-disposition"].endswith('.json"')
    x = client.get("/export/history/equity/EQ-CSCA.xlsx?range=3M")
    assert x.status_code == 200 and x.content[:2] == b"PK"


def test_the_legend_names_only_what_the_page_shows(client, backend, monkeypatch):
    t = client.get("/v/trade/1000015/history?days=30").text
    for item in ("changed", "same", "versions", "masked"):
        assert f'data-legend="{item}"' in t, item
    assert "72K</span> same" not in t                                       # no value of one entity in the legend
    assert "2 versions that day" in t

    async def history(kind, id_, ident=None, **params):                    # a counterparty: one version a day, no masks, one change
        return {"ref": {"kind": kind, "id": id_}, "shape": "document", "window": {"from": "2026-10-16", "to": "2026-10-19"}, "parts": [],
                "columns": [{"path": "RATING", "label": "Rating", "type": "text"}],
                "rows": [{"date": "2026-10-16", "values": {"RATING": "A"}, "display": {"RATING": "A"}, "changed": [], "versions": 1},
                         {"date": "2026-10-19", "values": {"RATING": "A-"}, "display": {"RATING": "A-"}, "changed": ["RATING"], "versions": 1}]}
    monkeypatch.setattr(backend, "history", history)
    t = client.get("/v/counterparty/CP-NORTHPLAINS/history?days=30").text
    assert 'data-legend="changed"' in t and 'data-legend="versions"' not in t and 'data-legend="masked"' not in t
    assert 'data-legend="carried"' not in t

    async def unchanged(kind, id_, ident=None, **params):
        d = await history(kind, id_)
        d["rows"][1]["changed"], d["rows"][1]["values"], d["rows"][1]["display"] = [], {"RATING": "A"}, {"RATING": "A"}
        return d
    monkeypatch.setattr(backend, "history", unchanged)
    assert "data-hx-legend" not in client.get("/v/counterparty/CP-NORTHPLAINS/history?days=30").text


def test_plain_words_for_a_history_open_it_or_say_how_they_were_read(client, backend, monkeypatch):
    answer = {"query": "TRD 1000015 FROM 2026-09-01 TO 2026-09-30", "command": "TRD 1000015 FROM 2026-09-01 TO 2026-09-30", "kind": "trade",
              "steps": [{"words": "trade", "meaning": "Trade (TRD)"}, {"words": "1000015", "meaning": "the trade 1000015"},
                        {"words": "last month", "meaning": "from 2026-09-01 to 2026-09-30"}], "ignored": [], "problem": None}

    async def phrase(text, ident=None):
        return dict(answer)
    monkeypatch.setattr(backend, "phrase", phrase)
    r = client.get("/s", params={"words": "trade 1000015 last month"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/go?q=TRD%201000015%20FROM%202026-09-01%20TO%202026-09-30"
    answer["ignored"] = ["please"]
    t = client.get("/s", params={"words": "trade 1000015 last month please"}).text
    assert "Read as" in t and 'href="/go?q=TRD%201000015%20FROM%202026-09-01%20TO%202026-09-30"' in t and "please" in t


def test_a_span_past_the_newest_loaded_day_says_so(client, backend, monkeypatch):
    assert "data-hx-loaded-to" not in client.get("/v/trade/1000015/history?days=30").text     # loadedTo null: nothing said
    plain = backend.history

    async def history(kind, id_, ident=None, **params):
        d = await plain(kind, id_, ident, **params)
        d["window"]["loadedTo"] = "2026-10-16"
        return d
    monkeypatch.setattr(backend, "history", history)
    t = client.get("/v/equity/EQ-CSCA/history?range=3M").text
    assert "data-hx-loaded-to" in t and "not loaded yet after" in t and "2026-10-16" in t


def test_the_csv_keeps_formulas_as_text(client, backend, monkeypatch):
    async def history(kind, id_, ident=None, **params):
        return {"ref": {"kind": kind, "id": id_}, "window": {"from": "2026-10-19", "to": "2026-10-19"}, "parts": [],
                "columns": [{"path": "NOTE", "label": "Note", "type": "text"}],
                "rows": [{"date": "2026-10-19", "values": {"NOTE": "=HYPERLINK(1)"}, "display": {"NOTE": "=HYPERLINK(1)"}, "changed": []}]}
    monkeypatch.setattr(backend, "history", history)
    body = client.get("/export/history/trade/X.csv?days=1").content.decode("utf-8-sig")
    assert "'=HYPERLINK(1)" in body
