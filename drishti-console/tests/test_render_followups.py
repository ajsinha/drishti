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

"""The renderer follow-ups of 2.9.7 (plan row 2a) on the console's side, without a browser: a view refused because the person's lens (or the
server's edition) hides its kind says so plainly, naming the lens, on the console's page, in /api/view (the renderer's fix) and in the embed
API v2; the playground opens on an entity the server holds and the person may open, in their lens, or says why there is none; and the
renderer's versions (1.1.0 current, 1.0.0 and 0.1.0 still served, unchanged; Elements 2.1 beside 2.0)."""
import json

from core import render_dist as rd
from core.backend import BackendError
from core.edition import hidden_hint
from routes import embed_v2_routes
from test_edition import _app, _client
from test_embed import HOST, make_client, token

HINT = ("You are inside the Drishti World lens, which does not include trades: leave the lens (Views → Leave) or choose an entity it shows.")


def _hidden(monkeypatch, backend):
    async def view(kind, id_, ident, controls=None, panels=None, **kw):
        raise BackendError(404, "DRS-4023", f"the command TRD ({kind}) is not part of the edition 'world' (FUNC lists the commands you can use)")
    monkeypatch.setattr(backend, "view", view)


def test_a_view_the_lens_hides_names_the_lens_never_the_id(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["world"]})
    alice = _client(app)
    alice.get("/lens/world")
    _hidden(monkeypatch, backend)
    r = alice.get("/api/view/trade/IRS-48213")
    assert r.status_code == 404 and r.json()["code"] == "DRS-4023"
    assert r.json()["fix"] == HINT and r.json()["lens"] == {"name": "world", "title": "Drishti World"}
    page = alice.get("/v/trade/IRS-48213")
    assert HINT in page.text and "check the kind" not in page.text.lower() and "check the identifier" not in page.text.lower()
    alice.get("/lens/leave")
    plain = alice.get("/api/view/trade/IRS-48213").json()
    assert plain["lens"] is None and "lens" not in plain["fix"].split(":")[0] and "does not include trades" in plain["fix"]


def test_the_hint_reads_as_a_person_says_it():
    class E:
        active, lens, title = True, True, "Drishti World"
    assert hidden_hint(E(), "netting-set").startswith("You are inside the Drishti World lens, which does not include netting sets:")
    assert "does not include companies" in hidden_hint(E(), "company")
    E.lens = False
    assert hidden_hint(E(), "trade").startswith("This server runs the Drishti World edition, which does not include trades")


def test_the_embed_api_says_it_too(backend, monkeypatch):
    embed = make_client(backend)
    _hidden(monkeypatch, backend)
    r = embed.get("/embed/v2/views/trade/IRS-48213", headers={"Authorization": f"Bearer {token()}", "Origin": HOST})
    assert r.json()["code"] == "DRS-4023" and "does not include trades" in r.json()["fix"]


def test_the_playground_opens_on_an_entity_the_server_holds_in_the_lens(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    app.state.editions.learn({"product": "Drishti", "edition": None, "lenses": ["world"]})
    alice = _client(app)
    asked = []

    async def functions(ident=None):
        return {"packs": [{"name": "world", "commands": [{"code": "CTRY", "kind": "country"}, {"code": "IDX", "kind": "index"}]}]}

    async def search(q, ident=None):
        asked.append(q)
        if q.startswith("CTRY"):
            return {"kind": "country", "rows": [{"ref": {"kind": "country", "id": "CAN"}, "title": "Canada"}]}
        return {"rows": []}
    monkeypatch.setattr(backend, "functions", functions)
    monkeypatch.setattr(backend, "search", search, raising=False)
    alice.get("/lens/world")
    got = alice.get("/render/demo/start").json()
    assert got["ref"] == {"kind": "country", "id": "CAN"} and got["lens"]["title"] == "Drishti World" and asked == ["CTRY limit 1"]
    assert "IRS-48213" not in json.dumps(got)

    async def nothing(q, ident=None):
        return {"rows": []}
    monkeypatch.setattr(backend, "search", nothing, raising=False)
    none = alice.get("/render/demo/start").json()
    assert none["ref"] is None and "country" in none["message"] and "Drishti World lens" in none["message"]


def test_the_playground_names_no_made_up_entity_and_shows_the_lens():
    html = (rd.FOLDER / "demo.html").read_text(encoding="utf-8")
    js = (rd.FOLDER / "demo.js").read_text(encoding="utf-8")
    assert "IRS-48213" not in html and 'value="trade"' not in html and "data-lens" in html
    assert "/render/demo/start" in js


def test_the_versions_1_1_0_current_1_0_0_and_0_1_0_unchanged_elements_2_1_beside_2_0(backend, client):
    assert rd.VERSION == "1.1.0" and {"0.1.0", "1.0.0", "1.1.0"} <= set(rd.versions())
    for v in ("0.1.0", "1.0.0", "1.1.0"):
        assert client.get(f"/static/render/{v}/drishti-render.js").status_code == 200
        assert json.loads((rd.ROOT / v / rd.RECORD).read_text()) == rd.record(v)
    assert "m/signin.js" in rd.record()["files"] and "m/signin.js" not in rd.record("1.0.0")["files"]
    assert embed_v2_routes.ELEMENTS == {"2.0.0": "1.0.0", "2.1.0": "1.1.0"}
    embed = make_client(backend)
    two = embed.get("/embed/v2/elements/2/drishti-elements.js", follow_redirects=False)
    assert two.headers["location"] == "/embed/v2/render/1.1.0/drishti-elements.js"
    old = embed.get("/embed/v2/elements/2.0/drishti-elements.js", follow_redirects=False)
    assert old.headers["location"] == "/embed/v2/render/1.0.0/drishti-elements.js"
    text = (rd.FOLDER / "m" / "elements.js").read_text(encoding="utf-8")
    assert "ELEMENTS_VERSION = '2.1.0'" in text
