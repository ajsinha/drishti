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

"""The workbench's console routes (step 6): operations, undo and redo, the check, suggestions, a file previewed on the spot, and the
pieces the page loads. The stand-in server applies operations in miniature (a stale revision is its 409, each operation leaves a
line in the Sutra); the real server's behaviour is exercised by test_workbench_browser.py."""
from pathlib import Path

from conftest import CONSOLE
from test_build_designs import _doc, _files, _new, app_client  # noqa: F401 - the fixture

JS = CONSOLE / "web" / "static" / "js" / "build"


def _design(c, **kw):
    d = _new(c, "Bench", empty=True, kind="trade", **kw)
    c.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    return d["id"], c.get(f"/build/designs/{d['id']}").json()


def test_operations_come_back_with_the_preview_drawn_as_html(app_client):
    id_, d = _design(app_client)
    r = app_client.post(f"/build/designs/{id_}/ops", json={"baseRev": d["rev"], "ops": [{"op": "setOption", "panel": "p", "option": "span", "value": 6}]})
    body = r.json()
    assert r.status_code == 200 and body["rev"] == d["rev"] + 1 and "studio-view" in body["previewHtml"] and "preview" not in body
    assert body["problems"] == [] and body["applied"] == 1 and body["opsAt"] == 1


def test_a_stale_base_revision_is_a_409_with_the_servers_code_and_nothing_changes(app_client):
    id_, d = _design(app_client)
    r = app_client.post(f"/build/designs/{id_}/ops", json={"baseRev": d["rev"] + 5, "ops": [{"op": "remove", "panel": "p"}]})
    assert r.status_code == 409 and r.json()["code"] == "DRS-5007"
    assert app_client.get(f"/build/designs/{id_}").json()["rev"] == d["rev"]


def test_a_refused_operation_is_a_located_problem_not_an_error(app_client):
    id_, d = _design(app_client)
    body = app_client.post(f"/build/designs/{id_}/ops", json={"baseRev": d["rev"], "ops": [{"op": "remove", "panel": "nope"}]}).json()
    assert body["applied"] == 0 and body["problems"][0]["code"] == "DRS-5021" and body["rev"] == d["rev"]


def test_undo_and_redo_move_the_log_and_say_when_there_is_nothing(app_client):
    id_, d = _design(app_client)
    assert app_client.post(f"/build/designs/{id_}/undo", json={}).status_code == 409
    done = app_client.post(f"/build/designs/{id_}/ops", json={"baseRev": d["rev"], "ops": [{"op": "setOption", "panel": "p", "option": "span", "value": 6}]}).json()
    undone = app_client.post(f"/build/designs/{id_}/undo", json={"baseRev": done["rev"]}).json()
    assert undone["yaml"] == d["sutra"] and undone["opsAt"] == 0 and "previewHtml" in undone
    redone = app_client.post(f"/build/designs/{id_}/redo", json={}).json()
    assert redone["yaml"] == done["yaml"] and redone["opsAt"] == 1
    assert app_client.post(f"/build/designs/{id_}/sideways", json={}).status_code in (404, 405)


def test_the_check_returns_the_panel_by_sample_matrix(app_client):
    id_, _ = _design(app_client)
    m = app_client.post(f"/build/designs/{id_}/check", json={}).json()
    assert m["ok"] is True and [s["name"] for s in m["samples"]] == ["a.json", "b.json"] and m["panels"] and m["rev"]


def test_a_check_without_samples_is_refused(app_client):
    d = _new(app_client, "No samples", empty=True, kind="trade")
    assert app_client.post(f"/build/designs/{d['id']}/check", json={}).status_code == 400


def test_suggestions_ask_for_a_field_of_the_designs_shape(app_client):
    id_, _ = _design(app_client)
    r = app_client.post(f"/build/designs/{id_}/suggest", json={"path": "$.book"})
    assert r.status_code == 200 and r.json()["path"] == "$.book" and r.json()["suggestions"][0]["kind"] == "kv"
    assert app_client.post(f"/build/designs/{id_}/suggest", json={"path": "book"}).status_code == 400
    assert app_client.post(f"/build/designs/{id_}/suggest", json={}).status_code == 400


def test_a_file_is_previewed_against_the_current_sutra_and_kept_nowhere(app_client):
    id_, d = _design(app_client)
    r = app_client.post(f"/build/designs/{id_}/preview-file", json={"document": {"tradeId": "FILE-9", "book": "rates"}})
    assert r.status_code == 200 and "FILE-9" in r.json()["previewHtml"]
    after = app_client.get(f"/build/designs/{id_}").json()
    assert [s["name"] for s in after["samples"]] == ["a.json", "b.json"] and after["rev"] == d["rev"]
    assert app_client.post(f"/build/designs/{id_}/preview-file", json={}).status_code == 400


def test_a_design_is_read_back_with_its_log_position_and_a_sample_document_on_request(app_client):
    id_, _ = _design(app_client)
    d = app_client.get(f"/build/designs/{id_}").json()
    assert d["id"] == id_ and "sutra" in d and "rev" in d
    assert app_client.get(f"/build/designs/{id_}/sample?name=a.json").json()["tradeId"] == "T-1"
    assert app_client.get("/build/designs/nope0000000/ops").status_code in (404, 405)


def test_other_peoples_designs_and_missing_ones_answer_404(app_client):
    assert app_client.post("/build/designs/doesnotexist/ops", json={"baseRev": 1, "ops": []}).status_code == 404
    assert app_client.post("/build/designs/doesnotexist/check", json={}).status_code == 404


def test_the_workbench_scripts_are_small_and_all_served(app_client):
    names = sorted(p.name for p in JS.glob("*.js"))
    assert {"grid-keys.js", "canvas.js", "inspector.js", "actions.js", "workbench.js", "tests.js", "problems.js", "yamltab.js", "data.js", "palette.js"} <= set(names)
    for p in JS.glob("*.js"):
        assert len(p.read_text().splitlines()) <= 420, f"{p.name} is over the module size the design allows"
        assert app_client.get(f"/static/js/build/{p.name}").status_code == 200
    page = app_client.get(f"/build/d/{_design(app_client)[0]}").text
    for n in names:
        if n != "wb_live.py":
            assert f"/static/js/build/{n}" in page, f"{n} is not loaded by the page"
    assert Path(CONSOLE / "web/static/css/build-workbench.css").exists()


def test_the_page_vendors_everything_no_cdn(app_client):
    page = app_client.get(f"/build/d/{_design(app_client)[0]}").text
    assert "cdn." not in page and "https://" not in page.replace("https://drishti", "")
