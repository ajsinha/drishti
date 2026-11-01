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

"""The Data view, DATA (docs/guides/DATA_GUIDE.md "The Data view"): the dataset list by pack with format, dates and the internal-only mark; a
chosen dataset with its facts, dates and columns; DATA <dataset> [date] <GO>; the count and the download relayed with the selection from the
address (every filter line), the download streamed; a refused dataset says so and offers no download; the word and the feature switch it off."""
from __future__ import annotations

from core.backend import BackendError
from core.edition import Edition
from core.edition_gate import hidden

DATASETS = {"internalOnlyRefused": False, "datasets": [
    {"kind": "trade-valuation", "pack": "market-risk", "code": "TVAL", "title": "Trade valuation", "description": "One valuation a trade a day.",
     "class": "fundamental", "connector": "risk-store", "format": "Delta", "table": "<root>/risk/trade-valuation/", "dated": True, "columnCount": 3,
     "internalOnly": False, "refused": False, "dates": 2, "first": "2026-09-29", "last": "2026-09-30"},
    {"kind": "economy", "pack": "world-economy", "code": "ECON", "title": "Economy", "description": "", "class": "fundamental",
     "connector": "world-file", "format": "JSON Lines", "table": "", "dated": False, "columnCount": 2, "internalOnly": True, "refused": True,
     "dates": 0, "first": None, "last": None}]}

TVAL = {**DATASETS["datasets"][0], "grain": "", "key": "VALUATION_ID", "masked": 1, "dates": ["2026-09-29", "2026-09-30"],
        "columns": [{"name": "TRADE_ID", "type": "id", "description": "The trade"}, {"name": "DESK", "type": "text", "description": ""},
                    {"name": "MTM", "type": "amount", "description": ""}]}


class _Upstream:
    headers = {"content-disposition": 'attachment; filename="trade-valuation_2026-09-30.jsonl.gz"'}

    def __init__(self):
        self.closed = False

    async def aiter_raw(self):
        for part in (b"\x1f\x8b-part-1", b"-part-2"):
            yield part

    async def aclose(self):
        self.closed = True


def _fake(backend, monkeypatch, refused=False):
    calls: dict = {}

    async def data_datasets(ident=None, read_timeout=None):
        return DATASETS

    async def data_dataset(kind, ident=None):
        if kind == "economy":
            return {**DATASETS["datasets"][1], "columns": [], "masked": 0, "dates": [], "grain": "", "key": None}
        if kind != "trade-valuation":
            raise BackendError(404, "DRS-1002", f"no stored dataset '{kind}'")
        return {**TVAL, "refused": refused}

    async def data_count(kind, ident, date="", columns="", where=None, read_timeout=None):
        calls["count"] = (kind, date, columns, list(where or []))
        return {"date": date, "columns": ["TRADE_ID", "MTM"], "lines": where, "words": "From trade-valuation on 2026-09-30: 2 columns.",
                "sql": "SELECT TRADE_ID, MTM\nFROM trade-valuation", "pushdown": True, "rows": 412, "total": 12076, "complete": True,
                "bytes": 40000, "gzipBytes": 31000, "read": "column read of risk-store"}

    up = _Upstream()

    async def open_download(path, ident, read_timeout=None):
        calls["download"] = path
        if "NOPE" in path:
            raise BackendError(403, "DRS-5002", "economy is marked internal only")
        return up

    for name, fn in (("data_datasets", data_datasets), ("data_dataset", data_dataset), ("data_count", data_count), ("open_download", open_download)):
        monkeypatch.setattr(backend, name, fn, raising=False)
    calls["upstream"] = up
    return calls


def test_the_list_shows_every_dataset_by_pack_with_format_dates_and_the_internal_only_mark(client, backend, monkeypatch):
    _fake(backend, monkeypatch)
    page = client.get("/data").text
    assert "MARKET RISK" in page and "WORLD ECONOMY" in page
    assert 'href="/data?kind=trade-valuation"' in page and ">Delta<" in page and "JSON Lines" in page
    assert "internal only" in page and "not dated" in page
    assert "Pick a dataset on the left" in page and "download only" in page.lower()


def test_a_chosen_dataset_shows_its_facts_dates_columns_and_the_selection_from_the_address(client, backend, monkeypatch):
    _fake(backend, monkeypatch)
    page = client.get("/data", params=[("kind", "trade-valuation"), ("date", "2026-09-30"), ("columns", "TRADE_ID,MTM"),
                                       ("where", "DESK = DESK-COMM"), ("where", "MTM > 1000000")]).text
    assert "connector risk-store" in page and "2026-09-29 to 2026-09-30 · 2 dates" in page and "1 masked for you, left out" in page
    assert 'value="TRADE_ID" data-col' in page and 'value="MTM" data-col' in page
    assert '<option value="2026-09-30" selected>' in page
    assert "DESK = DESK-COMM" in page and "MTM \\u003e 1000000" in page       # the selection rides in data-sel for the script
    assert "DATA trade-valuation 2026-09-30" in page and "/static/js/data-view.js" in page


def test_data_and_a_dataset_and_a_date_go_to_it(client, backend, monkeypatch):
    _fake(backend, monkeypatch)
    r = client.get("/go", params={"q": "DATA TVAL 2026-09-30 <GO>"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/data?q=TVAL%202026-09-30"
    r = client.get(r.headers["location"], follow_redirects=False)
    assert r.headers["location"] == "/data?kind=trade-valuation&date=2026-09-30"
    assert client.get("/data", params={"q": "nothing-like-it"}, follow_redirects=False).headers["location"] == "/data?find=nothing-like-it"
    assert client.get("/go", params={"q": "DATA <GO>"}, follow_redirects=False).headers["location"] == "/data"
    hints = client.get("/api/suggest", params={"q": "DATA trade"}).json()
    assert hints and hints[0]["mnemonic"] == "DATA" and hints[0]["href"] == "/data?q=trade"


def test_the_count_and_the_download_carry_every_filter_line_and_the_download_streams(client, backend, monkeypatch):
    calls = _fake(backend, monkeypatch)
    sel = [("date", "2026-09-30"), ("columns", "TRADE_ID,MTM"), ("where", "DESK = DESK-COMM"), ("where", "PRODUCT_TYPE in ComSwap,ComFut")]
    j = client.get("/api/data/trade-valuation/count", params=sel).json()
    assert j["rows"] == 412 and calls["count"] == ("trade-valuation", "2026-09-30", "TRADE_ID,MTM", ["DESK = DESK-COMM", "PRODUCT_TYPE in ComSwap,ComFut"])
    r = client.get("/data/trade-valuation/download", params=sel)
    assert r.status_code == 200 and r.content == b"\x1f\x8b-part-1-part-2" and r.headers["content-type"] == "application/gzip"
    assert 'filename="trade-valuation_2026-09-30.jsonl.gz"' in r.headers["content-disposition"]
    assert calls["download"].startswith("/data/trade-valuation/rows?date=2026-09-30&columns=TRADE_ID%2CMTM&where=DESK+%3D+DESK-COMM&where=PRODUCT_TYPE")
    assert calls["upstream"].closed
    r = client.get("/data/economy/download", params=[("where", "NOPE = 1")], follow_redirects=False)
    assert r.status_code == 303 and "kind=economy" in r.headers["location"] and "DRS-5002" in r.headers["location"]
    assert client.get("/api/data/Bad Name/count").status_code == 400


def test_a_refused_dataset_says_so_and_offers_no_download(client, backend, monkeypatch):
    _fake(backend, monkeypatch, refused=True)
    page = client.get("/data", params={"kind": "trade-valuation"}).text
    assert "Not downloadable here" in page and "internal only" in page and "data-download" not in page
    page = client.get("/data", params={"kind": "no-such"}).text
    assert "DRS-1002" in page


def test_the_word_switches_the_view_off(client):
    off = Edition("risk", {"commands": {"words": {"DATA": "off"}}})
    assert hidden(client.app, off, "/data") and hidden(client.app, off, "/api/data/trade/count")
    assert hidden(client.app, off, "/datamap") is None                              # DATAMAP is its own word
