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
"""Every "rows of records" result is a live result set (core/live_results.py): a FORM's result and the search page's come from the server's
live set of the search (GET /api/v1/live/query), the same rows, columns and order as before, marked with the set so the grid's live mode can
follow it (ticking only when the kind has a live binding); a bare mnemonic (POS <GO>) opens the kind's own set a page at a time."""
from __future__ import annotations

import re

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from form_backend import FormBackend

from core import live_results
from core.app import create_app
from core.config import load_settings


@pytest.fixture
def be():
    return FormBackend()


@pytest.fixture
def cl(be):
    app = create_app(load_settings(CONSOLE / "config" / "application.yaml"))
    app.state.backend = be
    with TestClient(app) as c:
        yield c


def host_spec(page):
    """The declaration the records grid's host carries (data-spec), as the grid reads it."""
    import html as h
    import json
    return json.loads(h.unescape(re.search(r'data-records[^>]*data-spec="([^"]*)"', page).group(1)))


def record(be, name):
    """Keeps the first argument of each call of the backend's ``name`` and lets the call through."""
    seen = []
    real = getattr(be, name)

    async def wrapped(*a, **k):
        seen.append(a[0] if a else None)
        return await real(*a, **k)
    setattr(be, name, wrapped)
    return seen


def test_a_forms_result_comes_from_the_live_set_with_the_same_rows_and_columns(cl, be):
    asked = record(be, "live_query")
    page = cl.get("/form/trading", params=[("kind", "trade"), ("run", "1"), ("op.counterparty.name", "contains"), ("v.counterparty.name", "Meridian"),
                                           ("order", "mtm"), ("desc", "1"), ("limit", "50")]).text
    assert asked and asked[-1].startswith("TRD where") and asked[-1].endswith("limit 50")     # the compiled command, through the engine
    assert "<b>2</b> of 36 trades match" in page and "<table" not in page.split('data-panel="results"')[1].split("</section>")[0]   # no table of its own: the grid draws the rows
    spec = host_spec(page)
    assert [c["field"] for c in spec["columns"]][:1] == ["__id"] and spec["kind"] == "trade" and spec["editable"] is False
    assert 'data-live-set="qu-stand-in"' in page and 'data-live="off"' in page and "data-live-badge" not in page   # static: no live binding


def test_a_form_over_a_kind_with_a_live_binding_ticks(cl, be):
    be.live_kinds = ("trade",)
    page = cl.get("/form/trading", params=[("kind", "trade"), ("run", "1"), ("op.counterparty.name", "contains"), ("v.counterparty.name", "Meridian")]).text
    assert 'data-live="on"' in page and "data-live-badge" in page


def test_the_search_page_reads_the_live_set_and_a_comparison_stays_a_comparison(cl, be):
    asked = record(be, "live_query")
    page = cl.get("/s", params={"q": "TRD where mtm > 1m"}).text
    assert asked and asked[-1].startswith("TRD where mtm > 1m") and "data-live-set=" in page
    compared = []

    async def compare(q, from_, to, ident=None):
        compared.append(q)
        return {**(await be.search(q, ident)), "rows": []}
    be.search_compare = compare
    cl.get("/s", params={"q": "TRD where mtm > 1m", "vs": "2026-09-29"})
    assert compared and len(asked) == 1                                                       # vs= is the search's own comparison


def test_a_bare_mnemonic_opens_the_kinds_own_set_a_page_at_a_time(cl, be):
    page = cl.get("/s", params={"q": "TRD"}).text
    backend = be
    assert backend.kind_pages[-1][:2] == ("TRD", 0)
    assert 'data-live-set="kind-' in page and "data-pager" in page and 'data-offset' not in page.split("data-records")[1].split(">")[0]
    total = int(re.search(r"of ([\d,]+)</span>", page).group(1).replace(",", ""))
    if total > backend.kind_pages[-1][2]:
        assert 'rel="next"' in page


def test_values_and_cells_are_shaped_in_one_place():
    page = {"set": {"id": "qu-1"}, "live": True, "total": 1, "seq": 9, "search": {"kind": "trade", "columns": ["$.a.b", "$.legs", "$.n"]},
            "rows": [{"id": "T1", "doc": {"a": {"b": "x"}, "legs": [1, 2, 3], "n": -1234.5, "_title": "Swap"}}]}
    data = live_results.as_search(page)
    assert data["rows"] == [{"ref": {"kind": "trade", "id": "T1"}, "title": "Swap", "values": {"$.a.b": "x", "$.legs": 3, "$.n": -1234.5}}]
    assert data["live"] == {"set": "qu-1", "live": True, "total": 1, "seq": 9}
    assert live_results.table_rows(data)[0]["cells"] == [("x", False), ("3", True), ("−1,234.5", True)]
