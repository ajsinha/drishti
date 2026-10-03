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

"""Error pages advise to fit the DRS code, and unknown console URLs get a proper 404 (QA 2026-10-01 UX-11)."""
from core.backend import BackendError


def _fail(backend, monkeypatch, status, code, detail):
    async def view(kind, id_, user):
        raise BackendError(status, code, detail)
    monkeypatch.setattr(backend, "view", view)


def test_a_timeout_is_not_told_to_check_the_identifier(client, backend, monkeypatch):
    _fail(backend, monkeypatch, 504, "DRS-1004", "DRS-1004 timed out reading trade/X")
    r = client.get("/v/trade/X")
    assert "Check the identifier" not in r.text and "took too long" in r.text


def test_a_bad_date_says_to_pick_another_date(client, backend, monkeypatch):
    _fail(backend, monkeypatch, 400, "DRS-4003", "business date 2030-01-01 is in the future")
    r = client.get("/v/trade/X")
    assert "Check the identifier" not in r.text and "Pick another business date" in r.text


def test_an_unknown_kind_says_so(client, backend, monkeypatch):
    _fail(backend, monkeypatch, 404, "DRS-1002", "no source for kind nosuchkind")
    r = client.get("/v/nosuchkind/X")
    assert "No source serves the kind" in r.text and "Check the identifier" not in r.text


def test_a_missing_entity_still_says_to_check_the_identifier(client):
    assert "Check the identifier" in client.get("/v/trade/NOPE-404").text


def test_an_unknown_page_gets_the_consoles_own_404(client):
    r = client.get("/no-such-page")
    assert r.status_code == 404 and "text/html" in r.headers["content-type"] and "Page not found" in r.text
    assert "<h1>" in r.text and '{"detail"' not in r.text


def test_an_unknown_api_url_gets_a_json_problem(client):
    r = client.get("/api/no-such-thing")
    assert r.status_code == 404 and r.json()["code"] == "DRS-1001" and "detail" in r.json()
