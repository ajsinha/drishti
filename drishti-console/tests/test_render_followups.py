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
renderer's one version (1.2.0; 0.1.0 to 1.1.0 retired, and Elements 2.0 and 2.1 with them)."""
import server_answers
import json

from core import render_dist as rd
from core.backend import BackendError
from core.edition import hidden_hint
from routes import embed_v2_routes
from test_edition import _app, _client
from test_embed import HOST, make_client, token

HINT = "This server does not include trades: choose an entity it shows, or ask an administrator."     # a lens never hides: it is never the reason


def _hidden(monkeypatch, backend):
    async def view(kind, id_, ident, controls=None, panels=None, **kw):
        raise BackendError(404, "DRS-4023", f"the command TRD ({kind}) is not part of the edition 'world' (FUNC lists the commands you can use)")
    monkeypatch.setattr(backend, "view", view)


def test_a_view_the_lens_hides_names_the_lens_never_the_id(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, {"product": "Drishti", "edition": None, "lenses": ["world"]})
    alice = _client(app)
    alice.post("/lens/world")
    _hidden(monkeypatch, backend)
    r = alice.get("/api/view/trade/IRS-48213")
    assert r.status_code == 404 and r.json()["code"] == "DRS-4023"
    assert r.json()["fix"] == HINT and r.json()["lens"] == {"name": "world", "title": "Drishti World"}
    page = alice.get("/v/trade/IRS-48213")
    assert HINT in page.text and "check the kind" not in page.text.lower() and "check the identifier" not in page.text.lower()
    alice.post("/lens/leave")
    plain = alice.get("/api/view/trade/IRS-48213").json()
    assert plain["lens"] is None and "lens" not in plain["fix"].split(":")[0] and "does not include trades" in plain["fix"]


def test_the_hint_reads_as_a_person_says_it():
    class E:
        active, lens, title = True, True, "Drishti World"
    assert hidden_hint(E(), "netting-set").startswith("This server does not include netting sets:")
    assert "does not include companies" in hidden_hint(E(), "company")
    E.lens = False
    assert hidden_hint(E(), "trade").startswith("Trades are not in Drishti World:")


def test_the_embed_api_says_it_too(backend, monkeypatch):
    embed = make_client(backend)
    _hidden(monkeypatch, backend)
    r = embed.get("/embed/v2/views/trade/IRS-48213", headers={"Authorization": f"Bearer {token()}", "Origin": HOST})
    assert r.json()["code"] == "DRS-4023" and "does not include trades" in r.json()["fix"]


def test_the_playground_opens_on_an_entity_the_server_holds_in_the_lens(backend, monkeypatch):
    app = _app(backend, monkeypatch)
    server_answers.follow(app, {"product": "Drishti", "edition": None, "lenses": ["world"]})
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
    alice.post("/lens/world")
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


def test_one_renderer_version_is_served_and_the_retired_ones_say_so(backend, client):
    """One page renderer (Jinja); the client-side renderer draws embeds and Explain: the one Elements version (Drishti's host part in m/, every panel
    drawn by Elements' painter, drishti-views) and nothing beside it: every earlier version is gone (410, no redirect)."""
    V = rd.VERSION          # the one Elements version (drishti_elements.json)
    assert rd.versions() == [V]
    for v in ("0.1.0", "1.0.0", "1.1.0"):
        assert not (rd.ROOT / v).exists() and client.get(f"/static/render/{v}/drishti-render.js").status_code == 404
    assert "m/drishti_signin.js" in rd.record()["files"]
    assert embed_v2_routes.ELEMENTS == {"2.4.0": V, V: V} and "pivot" not in embed_v2_routes.SCRIPTS
    embed = make_client(backend)
    two = embed.get("/embed/v2/elements/2/drishti-elements.js", follow_redirects=False)
    assert two.headers["location"] == f"/embed/v2/render/{V}/drishti-elements.js"
    lib = embed.get("/embed/v2/elements/drishti-views/src/drishti_index.js")
    assert lib.status_code == 200 and "drishti-views" in lib.text
    assert embed.get("/embed/v2/elements/drishti-renderer/src/drishti_index.js").status_code == 404          # the old renderer library is gone
    assert embed.get("/embed/v2/elements/drishti-views/src/../../../js/drishti_app.js").status_code == 404
    prev = embed.get("/embed/v2/render/3.1.0/drishti-render.js", follow_redirects=False)   # the previous pin: gone, no redirect (no shim)
    assert prev.status_code == 410 and prev.json()["code"] == "DRS-8006" and V in prev.json()["detail"]
    assert client.get("/static/render/3.1.0/drishti-render.js", follow_redirects=False).status_code == 404
    for old in ("2.1", "2.0.0", "2.2", "2.3.0"):
        r = embed.get(f"/embed/v2/elements/{old}/drishti-elements.js", follow_redirects=False)
        assert r.status_code in (404, 410) and r.json()["code"] == "DRS-8006", old
    for url in ("/embed/v2/render/2.0.0/drishti-render.js", "/embed/v2/render/1.2.0/drishti-render.js"):
        r = embed.get(url, follow_redirects=False)            # a retired renderer: gone, no redirect
        assert r.status_code == 410 and r.json()["code"] == "DRS-8006", url
    assert client.get("/static/render/2.0.0/drishti-render.js", follow_redirects=False).status_code == 404
    gone = embed.get("/embed/v2/render/1.1.0/drishti-render.js", follow_redirects=False)
    assert gone.status_code == 410 and gone.json()["code"] == "DRS-8006" and V in gone.json()["detail"]
    assert "ELEMENTS_VERSION = ONE_VERSION" in (rd.LIBRARY / "drishti_embed.js").read_text(encoding="utf-8")
    assert rd.LIBRARY == rd.ROOT / V / "m" and not (rd.ROOT / "3.1.0").exists()
