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
import json
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


def test_a_diff_with_no_change_says_so_and_offers_the_table_and_a_wider_range(client, backend, monkeypatch):
    real = backend.history

    async def history(kind, id_, ident=None, **params):
        data = await real(kind, id_, ident=ident, **params)
        for r in data.get("rows") or []:
            r["changed"], r["changes"] = False, []
        return data
    monkeypatch.setattr(backend, "history", history)
    t = client.get("/v/trade/1000015/history?days=30&diff=1").text
    assert "data-hx-empty" in t and "No field of 1000015 changed" in t
    assert "Show the table" in t and 'href="?range=1Y&amp;diff=1"' in t
    assert '<article class="hx-day"' not in t


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
    jl = client.get("/export/history/trade/1000015.jsonl?days=30")             # 2.9.4: a day a line, keyed as the CSV's header
    assert jl.status_code == 200 and jl.headers["content-type"].startswith("application/jsonl") and jl.headers["content-disposition"].endswith('.jsonl"')
    days = [json.loads(x) for x in jl.text.splitlines()]
    assert len(days) == 30 and list(days[0])[:2] == ["Date", "Trade status"] and days[0]["Date"] == "2026-10-19"
    x = client.get("/export/history/equity/EQ-CSCA.xlsx?range=3M")
    assert x.status_code == 200 and x.content[:2] == b"PK"
    # QA1-17: with changes only on, the JSON holds the changed days too; in Excel the dates are dates (yyyy-mm-dd), not text
    jo = client.get("/export/history/trade/1000015.json?days=30&only=1").json()
    assert len(jo["rows"]) == 7 and all(r["changed"] for r in jo["rows"]) and jo["onlyChanged"] is True
    import io
    import zipfile
    sheet = zipfile.ZipFile(io.BytesIO(client.get("/export/history/trade/1000015.xlsx?days=30").content)).read("xl/worksheets/sheet1.xml").decode()
    assert '<c r="A2" s="2"><v>46314</v></c>' in sheet                       # 2026-10-19 as a date serial
    styles = zipfile.ZipFile(io.BytesIO(client.get("/export/history/trade/1000015.xlsx?days=30").content)).read("xl/styles.xml").decode()
    assert 'formatCode="yyyy-mm-dd"' in styles


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


def test_a_bad_range_after_an_entity_is_shown_not_plain_words(client, backend, monkeypatch):
    """QA1-03: the server names a range it cannot read after one entity (DRS-4022, DRS-4021); the console shows that message with what to
    type, never today's view and never plain words that drop the id."""
    async def command(text, ident=None):
        raise BackendError(400, "DRS-4022", "DRS-4022 '-5' is not a range: use a number of days (30), a span (30D, 2W, 3M, 1Y), YTD")
    monkeypatch.setattr(backend, "command", command)
    r = client.get("/go", params={"q": "TRD 1000001 -5 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/t?error=DRS-4022")
    page = client.get(r.headers["location"]).text
    assert "DRS-4022" in page and "&#39;-5&#39; is not a range" in page and "3M" in page


def test_a_bare_id_with_a_bad_range_in_plain_words_says_what_to_type(client, backend, monkeypatch):
    """QA1-203: `1000015 -5` goes from /go to plain words; the server's phrase answers DRS-4022 and the console shows it, never "say what
    to look for"."""
    async def command(text, ident=None):
        raise BackendError(404, "DRS-1001", "no source holds 1000015 -5")

    async def phrase(text, ident=None):
        raise BackendError(400, "DRS-4022", "DRS-4022 '-5' is not a range: use a number of days (30), a span (30D, 2W, 3M, 1Y), YTD")
    monkeypatch.setattr(backend, "command", command)
    monkeypatch.setattr(backend, "phrase", phrase)
    r = client.get("/go", params={"q": "1000015 -5"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/s?words=")
    r = client.get(r.headers["location"], follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/t?error=DRS-4022")
    assert "&#39;-5&#39; is not a range" in client.get(r.headers["location"]).text


def test_the_csv_keeps_formulas_as_text(client, backend, monkeypatch):
    async def history(kind, id_, ident=None, **params):
        return {"ref": {"kind": kind, "id": id_}, "window": {"from": "2026-10-19", "to": "2026-10-19"}, "parts": [],
                "columns": [{"path": "NOTE", "label": "Note", "type": "text"}],
                "rows": [{"date": "2026-10-19", "values": {"NOTE": "=HYPERLINK(1)"}, "display": {"NOTE": "=HYPERLINK(1)"}, "changed": []}]}
    monkeypatch.setattr(backend, "history", history)
    body = client.get("/export/history/trade/X.csv?days=1").content.decode("utf-8-sig")
    assert "'=HYPERLINK(1)" in body


def test_no_data_in_the_window_links_to_the_first_day_with_data(client, backend, monkeypatch):
    """QA1-11: DRS-1013 names the entity's days with data; the page links to them, from the first."""
    async def history(kind, id_, ident=None, **params):
        raise BackendError(404, "DRS-1013", "DRS-1013 trade/1000015 has no data from 2026-09-01 to 2026-09-30; its days with data run from "
                                            "2026-10-09 to 2026-10-20")
    monkeypatch.setattr(backend, "history", history)
    page = client.get("/v/trade/1000015/history", params={"from": "2026-09-01", "to": "2026-09-30"}).text
    assert 'data-hx-first-day href="?from=2026-10-09&amp;to=2026-10-20"' in page and "From its first day with data, 2026-10-09" in page

    async def one_day(kind, id_, ident=None, **params):
        raise BackendError(404, "DRS-1013", "DRS-1013 trade/1000015 has no data from 2026-09-01 to 2026-09-30; its only day with data is 2026-10-20")
    monkeypatch.setattr(backend, "history", one_day)
    assert 'href="?from=2026-10-20&amp;to=2026-10-20"' in client.get("/v/trade/1000015/history", params={"days": "5"}).text

    async def other(kind, id_, ident=None, **params):
        raise BackendError(404, "DRS-1001", "DRS-1001 no source holds trade/1")
    monkeypatch.setattr(backend, "history", other)
    assert "data-hx-first-day" not in client.get("/v/trade/1/history").text


def _banner_text(html):
    m = re.search(r'<p class="asof-banner pin-banner".*?</p>', html, re.S)
    return (m.group(0), re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", m.group(0)))) if m else ("", "")


def test_open_the_day_says_it_came_from_the_history_and_links_back(client):
    """QA1-16: a day opened from the history (Enter, Open the day) said "as it was shared"; nothing was shared. It says it came from the
    history and links back to it."""
    t = client.get("/v/trade/1000015/history?days=30").text
    hrefs = re.findall(r'data-href="([^"]+)"', t)
    assert hrefs and all(h.endswith("&amp;via=history") for h in hrefs), hrefs[:2]
    day = "/v/trade/IRS-48213?asOf=2026-09-29&gen=1742&via=history"                   # the stand-in server's trade, opened as a history row opens it
    banner, text = _banner_text(client.get(day).text)
    assert 'data-pin-via="history"' in banner and "from its history" in text and "shared" not in text, text
    assert "data-pin-history" in banner and "Back to the history" in text
    assert "as it was shared" in _banner_text(client.get(day.replace("&via=history", "")).text)[1]     # a shared link keeps its words


def test_a_series_row_reads_its_parts_as_not_read(client, backend, monkeypatch):
    """QA1-15: a row from the embedded series showed 0 under each part; the server now sends no count (not read that day) and the page
    shows a dash with why, never 0 and no expander."""
    real = backend.history

    async def history(kind, id_, ident=None, **params):
        data = await real(kind, id_, ident=ident, **params)
        data["parts"] = [{"path": "HISTORY", "label": "History"}]
        for i, r in enumerate(data["rows"]):
            r["source"] = "series" if i < 3 else "market-store"
            r["parts"] = {"HISTORY": None if i < 3 else 60}
        return data
    monkeypatch.setattr(backend, "history", history)
    t = client.get("/v/equity/EQ-CSCA/history?range=3M").text
    cells = re.findall(r'<td class="num mono hx-part[^"]*" data-path="HISTORY"([^>]*)>(.*?)</td>', t)
    not_read = [c for c in cells if "data-not-read" in c[0]]
    assert len(not_read) == 3 and all(c[1] == "—" and "embedded series" in c[0] for c in not_read), not_read
    assert all(c[1].startswith("60 ") for c in cells if "data-not-read" not in c[0])

