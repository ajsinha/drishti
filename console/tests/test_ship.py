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

"""Build workbench, step 8 (ship and scale), console side: propose with evidence, pack fragment export and import, development file
binding, read-only share links, and the lifecycle status shown on My designs and the workbench. The stand-in server (FakeBackend)
answers as the real one does; the real server's rules are tested in DesignShipApiTest and DesignFileBindingTest."""
import io
import json
import zipfile

import pytest

from conftest import CONSOLE
from core.backend import BackendError
from test_build_designs import _doc, _files, _new, app_client  # noqa: F401 - the fixture

JS = CONSOLE / "web" / "static" / "js"


@pytest.fixture(autouse=True)
def _fresh(backend):
    backend.shares = {}
    backend.proposed = []
    backend.file_writes = []
    backend.file_edit = None
    backend.imported = []
    backend.designs_binding = False


def _design(c, **kw):
    d = _new(c, "Ship", kind="trade", sutra="rachana: 1\nsutra: ship\nversion: 1\n", **kw)
    c.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    return d["id"]


def _save_as(backend, monkeypatch, result):
    seen = {}

    async def save(text, ident=None, note=""):
        seen.update(text=text, note=note)
        return result
    monkeypatch.setattr(backend, "save_sutra", save, raising=False)
    return seen


# ---- propose with evidence ------------------------------------------------------------------------------------------------------

def test_submit_for_review_goes_through_the_evidence_endpoint_and_the_status_follows(app_client, backend, monkeypatch):
    id_ = _design(app_client)
    seen = _save_as(backend, monkeypatch, {"proposal": {"id": "P-000042", "name": "ship", "version": 1, "status": "pending"}})
    r = app_client.post(f"/build/designs/{id_}/propose", json={"note": "first cut"})
    assert r.status_code == 200 and r.json()["proposal"]["id"] == "P-000042" and r.json()["status"] == "proposed(P-000042)"
    assert seen["note"] == "first cut" and backend.proposed == [{"id": id_, "note": "first cut"}]
    assert app_client.get(f"/build/designs/{id_}").json()["status"] == "proposed(P-000042)"
    assert "/propose" in (JS / "build" / "saving.js").read_text() and "/save'" not in (JS / "build" / "saving.js").read_text()


def test_the_old_save_route_is_gone_the_server_endpoint_replaced_it(app_client):
    id_ = _design(app_client)
    assert app_client.post(f"/build/designs/{id_}/save", json={}).status_code in (404, 405)


def test_my_designs_and_the_workbench_show_proposed_and_live(app_client, backend, monkeypatch):
    id_ = _design(app_client)
    _save_as(backend, monkeypatch, {"proposal": {"id": "P-000042", "name": "ship", "version": 1, "status": "pending"}})
    app_client.post(f"/build/designs/{id_}/propose", json={})
    list_page = app_client.get("/build").text
    assert 'href="/build/reviews/P-000042"' in list_page and "proposed" in list_page
    bench = app_client.get(f"/build/d/{id_}").text
    assert "data-status-chip" in bench and "proposed(P-000042)" in bench
    _save_as(backend, monkeypatch, {"name": "ship", "latest": 3})
    app_client.post(f"/build/designs/{id_}/propose", json={})
    assert "<b>live v3</b>" in app_client.get("/build").text


# ---- the review page shows the evidence -----------------------------------------------------------------------------------------------

def test_a_reviewer_sees_the_check_matrix_sample_names_and_notes(client, backend, monkeypatch):
    matrix = {"ok": False, "counts": {"ok": 2, "empty": 1, "error": 1, "noAccess": 0}, "samples": [{"name": "a.json", "status": "ok"}, {"name": "b.json", "status": "ok"}],
              "panels": [{"id": "terms", "cells": [{"status": "ok"}, {"status": "ok"}]}, {"id": "legs", "cells": [{"status": "empty"}, {"status": "error", "message": "boom"}]}]}

    async def proposal(id_, ident=None):
        return {"id": id_, "name": "ship", "version": 1, "note": "", "author": "ana", "createdAt": "2026-09-30T14:02:11Z", "status": "pending", "reviewer": None,
                "comment": None, "newVersion": True, "stale": False, "mayApprove": True, "mayWithdraw": False, "text": "rachana: 1\nsutra: ship\nversion: 1\n",
                "baseText": "", "liveText": "", "previousText": "",
                "evidence": {"designId": "d1", "designName": "Ship", "rev": 7, "sampleNames": ["a.json", "b.json", "synthetic-1"], "syntheticSamples": ["synthetic-1"],
                             "notes": "reviewer: look at legs", "matrix": matrix}}
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    page = client.get("/build/reviews/P-000042").text
    assert "data-evidence" in page and "some samples fail" in page and 'class="wb-cell-error"' in page and 'title="boom"' in page
    assert "a.json, b.json, synthetic-1" in page and "reviewer: look at legs" in page and "not real data" in page
    assert "SECRET" not in page


def test_a_proposal_without_evidence_has_no_evidence_section(client, backend, monkeypatch):
    async def proposal(id_, ident=None):
        return {"id": id_, "name": "ship", "version": 1, "note": "", "author": "ana", "createdAt": "2026-09-30T14:02:11Z", "status": "pending", "reviewer": None,
                "comment": None, "newVersion": True, "stale": False, "mayApprove": False, "mayWithdraw": False, "text": "x", "baseText": "", "liveText": "", "previousText": ""}
    monkeypatch.setattr(backend, "proposal", proposal, raising=False)
    assert "data-evidence" not in client.get("/build/reviews/P-1").text


# ---- pack fragments -----------------------------------------------------------------------------------------------------------------

def test_export_is_a_zip_download(app_client):
    id_ = _design(app_client)
    r = app_client.get(f"/build/designs/{id_}/export")
    assert r.status_code == 200 and r.headers["content-type"] == "application/zip" and "attachment" in r.headers["content-disposition"]
    assert "frag/pack.yaml" in zipfile.ZipFile(io.BytesIO(r.content)).namelist()
    assert app_client.get("/build/designs/nope/export").status_code == 404


def test_a_zip_is_imported_as_it_is_and_a_folder_is_zipped_without_odd_paths(app_client, backend):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("p/sutras/x/x.v1.sutra.yaml", "rachana: 1\n")
    r = app_client.post("/build/import", content=buf.getvalue(), headers={"Content-Type": "application/zip"})
    assert r.status_code == 201 and r.json()["designs"][0]["id"] == "imp1" and backend.imported[-1][0] == "application/zip"

    files = [{"path": "p/sutras/x/x.v1.sutra.yaml", "text": "rachana: 1\n"}, {"path": "p/tests/x/a.json", "text": "{}"},
             {"path": "p/../evil.yaml", "text": "x"}, {"path": "p/run.sh", "text": "rm -rf /"}, {"path": "/abs/b.json", "text": "{}"}]
    r = app_client.post("/build/import", json={"files": files})
    assert r.status_code == 201
    names = r.json()["designs"][0]["names"]
    assert names == ["p/sutras/x/x.v1.sutra.yaml", "p/tests/x/a.json", "abs/b.json"]


def test_import_refuses_other_bodies(app_client):
    assert app_client.post("/build/import", content=b"x", headers={"Content-Type": "text/plain"}).status_code == 415
    assert app_client.post("/build/import", json={"files": "nope"}).status_code == 400
    assert app_client.post("/build/import", json={"files": [{"path": 3}]}).status_code == 400
    too_many = [{"path": f"p/{i}.json", "text": "{}"} for i in range(1001)]
    assert app_client.post("/build/import", json={"files": too_many}).status_code == 413


def test_new_offers_the_import_and_the_script_is_served(app_client):
    page = app_client.get("/build/new").text
    assert 'id="import"' in page and "data-import-zip" in page and "data-import-folder" in page and "build-import.js" in page
    assert app_client.get("/static/js/build-import.js").status_code == 200


# ---- sharing --------------------------------------------------------------------------------------------------------------------------

def test_a_share_link_shows_sutra_ops_and_sample_names_never_contents_and_can_be_revoked(app_client):
    id_ = _design(app_client)
    made = app_client.post(f"/build/designs/{id_}/share").json()
    assert made["path"] == f"/build/d/{id_}?share={made['token']}" and made["url"].endswith(made["path"])
    page = app_client.get(made["path"])
    assert page.status_code == 200 and "data-shared" in page.text and "read-only" in page.text
    assert "a.json" in page.text and "b.json" in page.text and "sutra: ship" in page.text
    assert "T-1" not in page.text and "rates" not in page.text                    # the sample contents never reach the page
    assert "data-save" not in page.text and "data-yaml-src" not in page.text       # no editing controls
    assert "/static/js/build/shared.js" in page.text and app_client.get("/static/js/build/shared.js").status_code == 200
    assert app_client.get(f"/build/d/{id_}?share=wrong").status_code == 404
    assert app_client.get(f"/build/designs/{id_}").json()["shared"] is True
    assert app_client.delete(f"/build/designs/{id_}/share").json() == {"shared": False}
    gone = app_client.get(made["path"])
    assert gone.status_code == 404 and "not valid" in gone.text


def test_a_link_holder_previews_with_their_own_json_or_a_stored_entity(app_client, backend, monkeypatch):
    id_ = _design(app_client)
    token = app_client.post(f"/build/designs/{id_}/share").json()["token"]
    seen = {}

    async def preview(yaml, kind, id_, ident=None, document=None):
        seen.update(yaml=yaml, kind=kind, id=id_, document=document)
        return await backend.view("trade", "IRS-48213", ident)
    monkeypatch.setattr(backend, "preview", preview, raising=False)
    r = app_client.post(f"/build/shared/{id_}/preview", json={"token": token, "document": {"mine": 1}, "kind": "trade"})
    assert r.status_code == 200 and "studio-view" in r.json()["previewHtml"] and seen["document"] == {"mine": 1} and "sutra: ship" in seen["yaml"]
    r = app_client.post(f"/build/shared/{id_}/preview", json={"token": token, "kind": "trade", "id": "IRS-48213"})
    assert r.status_code == 200 and seen["id"] == "IRS-48213" and seen["document"] is None
    assert app_client.post(f"/build/shared/{id_}/preview", json={"token": "nope", "document": {}}).status_code == 404


def test_the_workbench_has_the_ship_menu_and_a_share_box(app_client):
    id_ = _design(app_client)
    page = app_client.get(f"/build/d/{id_}").text
    assert "data-ship-menu" in page and "data-share-box" in page and "/static/js/build/ship.js" in page
    assert "Bind to a file" not in app_client.get("/static/js/build/ship.js").text.split("fileBinding")[0]    # the item only appears with the setting


# ---- file binding ---------------------------------------------------------------------------------------------------------------------------

def test_binding_is_off_by_default_and_the_server_says_so(app_client):
    id_ = _design(app_client)
    assert app_client.get("/build/binding").json()["enabled"] is False
    r = app_client.post(f"/build/designs/{id_}/bind", json={"file": "x/y.v1.sutra.yaml"})
    assert r.status_code == 403 and r.json()["code"] == "DRS-5002"
    assert '"fileBinding": false' in app_client.get(f"/build/d/{id_}").text


def test_with_binding_on_save_writes_the_file_and_a_disk_edit_is_synced(app_client, backend):
    backend.designs_binding = True
    id_ = _design(app_client)
    assert app_client.post(f"/build/designs/{id_}/bind", json={"file": "x/y.v1.sutra.yaml"}).json()["boundFile"] == "x/y.v1.sutra.yaml"
    page = app_client.get(f"/build/d/{id_}").text
    assert '"fileBinding": true' in page and '"boundFile": "x/y.v1.sutra.yaml"' in page
    assert app_client.post(f"/build/designs/{id_}/save-file").status_code == 200 and backend.file_writes[-1][0] == "x/y.v1.sutra.yaml"
    assert app_client.post(f"/build/designs/{id_}/sync").json()["changed"] is False
    backend.file_edit = "rachana: 1\nsutra: edited\nversion: 1\n"
    synced = app_client.post(f"/build/designs/{id_}/sync").json()
    assert synced["changed"] is True and app_client.get(f"/build/designs/{id_}").json()["sutra"] == "rachana: 1\nsutra: edited\nversion: 1\n"
    assert "boundFile" not in app_client.delete(f"/build/designs/{id_}/bind").json()


def test_the_ship_script_saves_to_the_file_when_bound_and_polls_for_disk_edits():
    ship = (JS / "build" / "ship.js").read_text()
    saving = (JS / "build" / "saving.js").read_text()
    assert "hooks.ship.bound()" in saving and "/save-file" in ship and "/sync" in ship and "setInterval(sync" in ship
    assert len(ship.splitlines()) < 400


def test_the_workbench_reloads_a_design_from_its_sutra_field():
    """GET /build/designs/{id} answers the Sutra as ``sutra`` (the server's field); the reloads after a 409, an auto-design and a disk sync read that."""
    for name in ("ops.js", "workbench.js", "ship.js"):
        text = (JS / "build" / name).read_text()
        assert "g.body.yaml" not in text and "r.body.yaml || ''" not in text, name


def test_a_design_whose_base_moved_is_flagged_and_the_rebase_is_forwarded(app_client, backend, monkeypatch):
    """M-2: the page carries the flag (status chip + Problems entry + Ship menu item) and Rebase reaches the server with the revision."""
    id_ = _design(app_client)
    row = next(r for (u, i), r in backend.design_rows.items() if i == id_)["design"]
    row["baseMoved"] = {"base": "book@1", "name": "book", "from": 1, "to": 2, "latest": "book@2"}
    page = app_client.get(f"/build/d/{id_}").text
    assert '"baseMoved": {' in page and "book@2" in page and "data-base-chip" in page
    real, seen = backend.designs, []

    async def spy(method, path, ident, body=None, **params):
        if path.endswith("/rebase"):
            seen.append((method, path, body))
            return {"base": "book@2", "replayed": 1, "problems": [], "rev": 3}
        return await real(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "designs", spy)
    r = app_client.post(f"/build/designs/{id_}/rebase", json={"baseRev": 2})
    assert r.status_code == 200 and r.json()["replayed"] == 1 and seen == [("POST", f"/{id_}/rebase", {"baseRev": 2})]
    ship = (JS / "build" / "ship.js").read_text()
    problems = (JS / "build" / "problems.js").read_text()
    assert "/rebase" in ship and "baseMoved" in problems
