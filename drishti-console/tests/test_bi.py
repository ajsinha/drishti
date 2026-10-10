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

"""Rupaka phase 1 (docs/architecture/RUPAKA.md sections 4 and 5), the console's routes: the RUPAKA keyword in any case, the BI top-bar menu
for people with the bi power only, the pages and their rail, the same-origin JSON passed on to the server (and the server's refusals
passed back), the admin page, type-ahead, the proof of concept page kept for administrators."""
import pytest
from fastapi.testclient import TestClient

from bi_backend import BiBackend
from conftest import CONSOLE
from core.app import create_app
from core.config import load_settings


def make(powers=("bi", "bi-author", "bi-admin"), enabled=True, poc=False):
    settings = load_settings(CONSOLE / "config")
    if poc:
        settings.as_dict().setdefault("bi", {})["poc_enabled"] = True
    app = create_app(settings)
    app.state.backend = BiBackend(powers, enabled)
    return TestClient(app), app.state.backend


@pytest.fixture()
def bi():
    return make()


def go(client, text):
    return client.get("/go", params={"q": text}, follow_redirects=False)


@pytest.mark.parametrize("typed", ["RUPAKA <GO>", "rupaka <GO>", "Rupaka", "  rupaka  "])
def test_the_keyword_opens_the_home_in_any_case(bi, typed):
    client, _ = bi
    r = go(client, typed)
    assert r.status_code == 303 and r.headers["location"] == "/bi"


def test_the_keyword_opens_my_workspace_and_the_catalogue(bi):
    client, _ = bi
    assert go(client, "RUPAKA WORKSPACE <GO>").headers["location"] == "/bi/w/me"
    assert go(client, "rupaka workspace").headers["location"] == "/bi/w/me"
    assert go(client, "RUPAKA CATALOGUE <GO>").headers["location"] == "/bi/catalogue"
    assert go(client, "Rupaka catalogue").headers["location"] == "/bi/catalogue"


def test_the_keyword_opens_a_report_by_title_or_id_or_searches(bi):
    client, backend = bi
    assert go(client, "RUPAKA bi-1 <GO>").headers["location"] == "/bi/i/bi-1"
    assert go(client, "RUPAKA desk p&l").headers["location"] == "/bi/i/bi-1"
    assert go(client, "RUPAKA nothing-like-it").headers["location"] == "/bi/catalogue?q=nothing-like-it"
    assert any(c[1] == "/catalogue" and c[3].get("q") == "desk p&l" for c in backend.bi_calls)


def test_the_keyword_says_why_when_bi_is_not_for_you():
    client, _ = make(powers=())
    r = go(client, "RUPAKA <GO>")
    assert r.status_code == 303 and r.headers["location"].startswith("/t?error=") and "bi%20power" in r.headers["location"]
    client, _ = make(enabled=False)
    assert go(client, "RUPAKA").headers["location"].startswith("/t?error=")


def test_the_menu_is_shown_to_people_with_the_bi_power_only(bi):
    client, _ = bi
    top = client.get("/t").text
    assert "bar-chart-line" in top and 'href="/bi"' in top and 'href="/bi/catalogue"' in top and 'href="/bi/w/me"' in top
    assert "Desk P&amp;L" in top                                                          # a recent item
    assert top.index(">Views<") < top.index(">BI<") < top.index(">Build<")
    for powers in ((), ):
        none, _ = make(powers=powers)
        assert 'href="/bi/catalogue"' not in none.get("/t").text
    off, _ = make(enabled=False)
    assert 'href="/bi/catalogue"' not in off.get("/t").text


def test_bi_settings_are_in_the_menu_only_for_bi_admin():
    admin, _ = make(powers=("bi", "bi-admin"))
    assert 'href="/admin/bi"' in admin.get("/bi").text
    author, _ = make(powers=("bi", "bi-author"))
    assert "BI settings" not in author.get("/bi").text
    assert author.get("/admin/bi").status_code == 403


@pytest.mark.parametrize("path,page", [("/bi", "home"), ("/bi/catalogue", "catalogue"), ("/bi/workspaces", "workspaces"), ("/bi/w/me", "workspace"),
                                       ("/bi/i/bi-1", "item"), ("/bi/datasets", "datasets"), ("/bi/designer", "designer"), ("/bi/explore", "explore")])
def test_every_page_has_the_rail_and_the_shared_top_bar(bi, path, page):
    client, _ = bi
    r = client.get(path)
    assert r.status_code == 200
    assert f'data-bi-page="{page}"' in r.text and 'class="bi-rail"' in r.text and 'class="tbar"' in r.text or 'class="tbar-row1"' in r.text
    for label in ("Home", "Catalogue", "My workspace", "Workspaces", "Datasets", "Designer", "Explore"):
        assert label in r.text
    assert "f-keys" not in r.text and "fkeys" not in r.text.lower()                        # the terminal's F-key footer is not shown
    assert "<script>" not in r.text.replace("<script src", "") and " onclick=" not in r.text


def test_placeholders_say_what_phase_builds_them(bi):
    client, _ = bi
    assert "phase 2" in client.get("/bi/datasets").text and "Not built yet" in client.get("/bi/datasets").text
    assert "phase 3" in client.get("/bi/designer").text
    assert "Explore" in client.get("/bi/explore").text and "Not built yet" in client.get("/bi/explore").text


def test_a_page_without_the_power_is_refused_with_the_reason():
    client, _ = make(powers=())
    r = client.get("/bi/catalogue")
    assert r.status_code == 403 and "needs the" in r.text and "bi" in r.text


def test_the_json_goes_to_the_server_as_the_caller(bi):
    client, backend = bi
    assert client.get("/bi/api/catalogue", params={"q": "desk", "kind": "report"}).json()["total"] == 1
    assert ("GET", "/catalogue", None, {"q": "desk", "kind": "report"}) in backend.bi_calls
    assert client.post("/bi/api/items/bi-1/publish", json={"note": "n"}).json()["echo"] == {"note": "n"}
    assert client.put("/bi/api/items/bi-1/favourite", json={"on": True}).status_code == 200
    assert client.delete("/bi/api/items/bi-1").status_code == 200


def test_nothing_but_bi_paths_are_passed_on(bi):
    client, _ = bi
    assert client.get("/bi/api/admin/users").status_code == 404
    assert client.get("/bi/api/items/../../admin/users").status_code in (404, 400)


def test_the_servers_refusals_come_back_with_their_code(bi):
    client, backend = bi
    backend.refuse = ("DRS-9002", 403, "you are a viewer of Rates; publishing needs member")
    r = client.post("/bi/api/items/bi-1/publish", json={})
    assert r.status_code == 403 and r.json()["code"] == "DRS-9002" and "viewer" in r.json()["detail"]
    backend.refuse = ("DRS-9001", 404, "no item")
    assert client.get("/bi/api/items/zzz").status_code == 404


def test_writes_need_json_and_the_same_site(bi):
    client, _ = bi
    assert client.post("/bi/api/items/bi-1/publish", content="x=1", headers={"content-type": "text/plain"}).status_code in (400, 415, 422)
    assert client.post("/bi/api/items/bi-1/publish", json={}, headers={"origin": "https://evil.example"}).status_code == 403


def test_role_powers_are_saved_through_the_role_definition_api(bi):
    client, backend = bi
    seen = {}

    async def admin(method, path, ident, body=None, **kw):
        seen[(method, path)] = body
        if method == "GET":
            return {"name": "desk", "description": "d", "kinds": ["*"], "raw": False, "author": False, "approve": False, "admin": False, "calc": False,
                    "layout": True, "collaborate": True, "compliance": False, "bi": []}
        return {"name": "desk", "bi": body["bi"]}

    backend.admin = admin
    r = client.put("/bi/api-roles/desk", json={"bi": ["bi", "bi-author"]})
    assert r.json() == {"name": "desk", "bi": ["bi", "bi-author"]}
    assert seen[("PUT", "/role-definitions/desk")]["kinds"] == ["*"] and seen[("PUT", "/role-definitions/desk")]["bi"] == ["bi", "bi-author"]
    assert client.put("/bi/api-roles/desk", json={"bi": "bi"}).status_code == 422


def test_the_command_line_offers_rupaka(bi):
    client, _ = bi
    word = client.get("/api/suggest", params={"q": "rupa"}).json()
    assert word[0]["mnemonic"] == "RUPAKA" and word[0]["complete"] == "RUPAKA "
    pages = client.get("/api/suggest", params={"q": "RUPAKA "}).json()
    assert [p["href"] for p in pages[:3]] == ["/bi", "/bi/w/me", "/bi/catalogue"]
    cat = client.get("/api/suggest", params={"q": "rupaka cat"}).json()
    assert cat[0]["href"] == "/bi/catalogue"
    rep = client.get("/api/suggest", params={"q": "rupaka desk"}).json()
    assert rep[0]["href"] == "/bi/i/bi-1" and rep[0]["title"] == "Desk P&L"
    none, _ = make(powers=())
    assert none.get("/api/suggest", params={"q": "rupaka "}).json() == []


def test_the_proof_of_concept_stays_off_unless_enabled_and_is_for_administrators(bi):
    client, _ = bi
    assert client.get("/bi/poc").status_code == 404 and "/bi/poc" not in client.get("/t").text
    on, _ = make(poc=True)
    assert 'href="/bi/poc"' in on.get("/t").text
    assert on.get("/bi/poc").status_code == 200                                         # the default identity is an administrator


def test_the_help_screen_exists(bi):
    client, _ = bi
    assert client.get("/help/context/bi").status_code == 200


def test_bi_can_be_switched_off_in_the_console():
    settings = load_settings(CONSOLE / "config")
    settings.as_dict().setdefault("bi", {})["enabled"] = False
    app = create_app(settings)
    app.state.backend = BiBackend()
    client = TestClient(app)
    assert client.get("/bi").status_code == 404 and 'href="/bi/catalogue"' not in client.get("/t").text
    assert go(client, "RUPAKA").headers["location"].startswith("/t?error=")


def test_bi_is_the_keyword_and_rupaka_its_alias(bi):
    client, _ = bi
    for typed in ("BI <GO>", "bi", "  Bi  "):
        assert go(client, typed).headers["location"] == "/bi"
    assert go(client, "BI WORKSPACE <GO>").headers["location"] == "/bi/w/me"
    assert go(client, "bi catalogue").headers["location"] == "/bi/catalogue"
    assert go(client, "BI bi-1 <GO>").headers["location"] == "/bi/i/bi-1"
    assert go(client, "bi desk p&l").headers["location"] == "/bi/i/bi-1"
    word = client.get("/api/suggest", params={"q": "b"}).json()
    assert word[0]["mnemonic"] == "BI" and word[0]["complete"] == "BI "
    pages = client.get("/api/suggest", params={"q": "BI "}).json()
    assert [p["href"] for p in pages[:3]] == ["/bi", "/bi/w/me", "/bi/catalogue"] and pages[0]["complete"] == "BI"
    rep = client.get("/api/suggest", params={"q": "bi desk"}).json()
    assert rep[0]["href"] == "/bi/i/bi-1" and rep[0]["complete"] == "BI Desk P&L"
    none, _ = make(powers=())
    assert none.get("/api/suggest", params={"q": "bi "}).json() == []
