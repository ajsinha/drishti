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



def test_admins_see_and_purge_caches(client):
    page = client.get("/admin/caches").text
    assert "trading-stream" in page and 'data-purge="all"' in page and "diskMb" in page
    r = client.post("/admin/api/caches/trading-stream/purge")
    assert r.status_code == 200 and r.json()["purged"] == ["trading-stream"]


def test_the_caches_page_shows_totals_sizes_dates_and_purges_a_date(client, backend):
    """Admin -> Modules (mock-up C, contract admin-modules/1) over the server's recorded answer (tests/answers/admin-modules.json): the page's tiles,
the nodes-disagree banner with its copy button, the nodes x modules matrix with the differing cell, the detail, the install steps, and the
administrators-only rule."""
import pytest
from conftest import BackendError


def test_the_page_draws_the_recorded_answer(client):
    page = client.get("/admin/modules").text
    for tile in ("modules", "nodes", "fingerprint", "problems", "stale", "notLoaded"):
        assert f'data-tile="{tile}"' in page, tile
    assert "2 / 3" in page and "b1ac0a79" in page
    assert page.count("data-modules-banner") == 1 and 'data-banner-module="trading"' in page and "Copy fix command" in page
    assert "trading-2.9.11.i9.dmod" in page                                       # the fix command names the module's file
    for node in ("drishti-main", "drishti-b", "drishti-c"):
        assert f'data-node="{node}"' in page
    assert page.count("<tr data-module=") == 10
    row = page[page.index('<tr data-module="trading"'):]
    row = row[:row.index("</tr>")]
    assert row.count('data-differs="true"') == 1 and "dcc296b5" in row and "0035e3be" in row
    assert 'data-state="stale"' in page and 'data-state="refused"' in page
    assert "python3 tools/drishti.py module verify" in page and "/opt/drishti/modules" in page and "nothing deploys from the browser" in page
    for f in ("all", "problems", "off", "development"):
        assert f">{f}</button>" in page


def test_the_chosen_module_shows_its_detail(client):
    page = client.get("/admin/modules?module=capitalmarkets").text
    assert page.count("data-module-detail=") == 10
    on = page[page.index('data-module-detail="capitalmarkets"'):]
    assert "hidden" not in on[:on.index(">")] and 'data-problem-kind="stale"' in on[:on.index("</section>")]
    other = page[page.index('data-module-detail="trading"'):]
    assert "hidden" in other[:other.index(">")]


def test_a_page_with_no_differing_node_has_no_banner(client, backend, monkeypatch):
    import server_answers
    answer = server_answers.admin_modules()
    answer["disagree"] = []
    answer["counts"].update(nodesAgree=3, nodesDiffer=0)
    real = backend.admin

    async def admin(method, path, ident, body=None, **params):
        return answer if path == "/modules" and method == "GET" else await real(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/modules").text
    assert "data-modules-banner" not in page and "3 / 3" in page


def test_the_page_says_when_the_server_does_not_answer(client, backend, monkeypatch):
    real = backend.admin

    async def admin(method, path, ident, body=None, **params):
        if path == "/modules":
            raise BackendError(503, "DRS-1032", "the server is not reachable")
        return await real(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/modules").text
    assert "data-modules-error" in page and "data-modules-tiles" not in page
