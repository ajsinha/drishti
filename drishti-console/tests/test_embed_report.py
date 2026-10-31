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

"""<drishti-report>'s console side (docs/architecture/ELEMENTS.md section 18): the embed API of a published report (definition, one visual's rows, the
visual types) passes the embed caller's own token to the server and trims what the host sees; the element's modules, sheet and report code are served with
CORS; the live key of a report rides the embed channel; the report sheet is not stale. The browser acceptance is in test_embed_report_browser.py."""
import json

import pytest

from core.backend import BackendError
from core import element_sheet as es
from test_embed import HOST, make_client, token

REPORT = {"id": "file:report:sales", "title": "Sales", "description": "d", "status": "published", "shipped": True, "sample": False, "valid": True, "mayEdit": True,
          "problems": [{"level": "warning", "code": "W", "path": "x", "message": "m"}, {"level": "error", "code": "E", "path": "y", "message": "n"}],
          "report": {"report": "sales", "pages": [{"id": "p", "title": "P", "visuals": []}]}, "dataset": {"tables": [], "measures": []}}


@pytest.fixture
def embed(backend):
    seen = []

    async def bi(method, path, ident, body=None, **params):
        seen.append((method, path, ident, body))
        if path == "/reports/catalogue":
            return {"visuals": {"bar": {"group": "chart"}}}
        if path == "/reports/file:report:draft":
            return dict(REPORT, id="file:report:draft", status="draft")
        if path == "/reports/file:report:gone":
            raise BackendError(404, "DRS-9031", "no such report")
        if path.endswith("/query"):
            return {"columns": [], "rows": [], "meta": {"masked": ["trade.trader"]}}
        return REPORT

    backend.bi = bi
    c = make_client(backend)
    c.seen = seen
    return c


def headers(user="alice"):
    return {"Authorization": f"Bearer {token(user=user)}", "Origin": HOST}


def test_the_definition_is_the_reports_model_without_the_yaml_the_warnings_or_the_authoring_flags(embed):
    r = embed.get("/embed/v1/reports/file:report:sales", headers=headers())
    assert r.status_code == 200 and r.headers["access-control-allow-origin"] == HOST and r.headers["cache-control"] == "no-store"
    body = r.json()
    assert body["title"] == "Sales" and body["report"]["pages"][0]["id"] == "p" and body["dataset"] == {"tables": [], "measures": []}
    assert [p["level"] for p in body["problems"]] == ["error"] and "mayEdit" not in body and "status" not in body
    method, path, me, _body = embed.seen[-1]
    assert (method, path) == ("GET", "/reports/file:report:sales") and me.user == "alice" and me.headers()["Authorization"].startswith("Bearer ")


def test_only_a_published_report_is_embedded(embed):
    r = embed.get("/embed/v1/reports/file:report:draft", headers=headers())
    assert r.status_code == 403 and r.json()["code"] == "DRS-8005"


def test_the_servers_refusals_and_a_bad_token_reach_the_host_with_their_codes(embed):
    gone = embed.get("/embed/v1/reports/file:report:gone", headers=headers())
    assert gone.status_code == 404 and gone.json()["code"] == "DRS-9031" and gone.headers["access-control-allow-origin"] == HOST
    assert embed.get("/embed/v1/reports/file:report:sales", headers={"Origin": HOST}).json()["code"] == "DRS-8001"
    evil = embed.get("/embed/v1/reports/file:report:sales", headers={**headers(), "Origin": "http://evil.example"})
    assert evil.status_code == 403 and evil.json()["code"] == "DRS-8002" and "access-control-allow-origin" not in evil.headers
    assert embed.post("/embed/v1/reports/file:report:sales/query", json={}, headers={"Origin": HOST}).json()["code"] == "DRS-8001"


def test_a_visuals_query_passes_the_pages_state_to_the_server_as_the_callers_own_call(embed):
    state = {"page": "p", "visual": "v", "state": {"slicers": {"s": ["FX"]}, "selections": [], "dates": None}}
    r = embed.post("/embed/v1/reports/file:report:sales/query", json=state, headers=headers("bob"))
    assert r.status_code == 200 and r.json()["meta"]["masked"] == ["trade.trader"]
    method, path, me, body = embed.seen[-1]
    assert (method, path, body) == ("POST", "/reports/file:report:sales/query", state) and me.user == "bob"
    too_big = embed.post("/embed/v1/reports/file:report:sales/query", content=json.dumps({"x": "y" * 70_000}), headers={**headers(), "Content-Type": "application/json"})
    assert too_big.status_code in (400, 413)
    assert embed.post("/embed/v1/reports/file:report:sales/query", content="not json", headers={**headers(), "Content-Type": "application/json"}).status_code == 400


def test_the_visual_types_are_served_for_the_element(embed):
    assert embed.get("/embed/v1/reports/catalogue", headers=headers()).json()["visuals"]["bar"]["group"] == "chart"
    assert embed.seen[-1][1] == "/reports/catalogue"


def test_preflight_covers_the_report_paths(embed):
    ok = embed.options("/embed/v1/reports/file:report:sales/query", headers={"Origin": HOST, "Access-Control-Request-Method": "POST"})
    assert ok.status_code == 204 and "POST" in ok.headers["access-control-allow-methods"] and "authorization" in ok.headers["access-control-allow-headers"]


def test_the_report_code_and_sheet_are_served_with_cors_and_the_channel_carries_a_reports_live_key(embed):
    from routes import embed_routes

    assert embed_routes.LIVE_KEYS == ("view:", "vm:", "bilive:")             # vm: a view's frames as panels (the embed API v2, Elements 2.0)
    for name in ("report", "report-page"):
        r = embed.get(f"/embed/v1/m/{name}.js", headers={"Origin": HOST})
        assert r.status_code == 200 and r.headers["access-control-allow-origin"] == HOST, name
    entry = embed.get("/embed/v1/drishti-elements.js", headers={"Origin": HOST})
    assert "./m/report.js" in entry.text
    for name in ("bi-lib", "bi-phone", "bi-visuals", "bi-visuals-format", "bi-grids", "bi-matrix", "bi-multiples", "bi-frame", "bi-live"):
        r = embed.get(f"/embed/v1/js/{name}.js", headers={"Origin": HOST})
        assert r.status_code == 200 and "javascript" in r.headers["content-type"], name
    css = embed.get("/embed/v1/drishti-report.css", headers={"Origin": HOST})
    assert css.status_code == 200 and css.headers["access-control-allow-origin"] == HOST and ".bi-vis" in css.text and ":root" not in css.text
    assert embed.get("/embed/v1/js/bi-board.js", headers={"Origin": HOST}).status_code == 404            # only the listed scripts


def test_the_report_sheet_is_not_stale_and_is_a_small_second_sheet():
    text, manifest = es.build_report()
    have, have_manifest = es.report_committed()
    assert have == text and have_manifest == manifest, "the report sheet is stale; regenerate it: python3 tools/elements_sheet.py"
    assert manifest["gzip"] < 10 * 1024 and "@font-face" not in text
    assert {"table", "x-circle", "funnel"} <= set(manifest["icons"])


def test_a_report_page_the_viewer_and_the_element_share_the_phone_rules():
    """bi-phone.js is one file for the viewer, the element and the designer: the element sheet's container rule and the viewer's media rule agree on 640 px."""
    js = (es.WEB / "static/js/bi-phone.js").read_text(encoding="utf-8")
    assert "P.MAX = 640" in js
    assert "max-width: 640px" in es.REPORT_ADDENDUM
