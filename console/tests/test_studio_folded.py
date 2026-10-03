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

"""Build workbench, step 7: Studio folded in. Every old /studio address lands on an equivalent workbench screen (302, query kept
where it means something), Studio's test entities become samples of a Design made from that Sutra, the Versions tab diffs against
the base or an earlier version, saving and proposing follow the rights, and the help guide moved with the page."""
import re

import pytest

from core.backend import BackendError
from test_build_designs import _console, _doc, _files, _new, app_client  # noqa: F401  (the fixture)


def _target(client, url):
    r = client.get(url, follow_redirects=False)
    assert r.status_code == 302, (url, r.status_code)
    return r.headers["location"]


def _design(client, location):
    m = re.match(r"/build/d/(\w+)", location)
    assert m, location
    return client.get(f"/build/designs/{m.group(1)}").json()


# ---- the addresses ------------------------------------------------------------------------------------------------------------

def test_bare_studio_is_a_scratch_design_on_the_default_example_at_the_yaml_tab(app_client):
    loc = _target(app_client, "/studio")
    d = _design(app_client, loc)
    assert loc.endswith("?tab=yaml") and d["scratch"] is True and d["name"] == ""
    assert "sutra: all-panels-showcase" in d["sutra"] and [s["name"] for s in d["samples"]] == ["all-panels-showcase.json"]
    assert "all-panels-showcase" in d["notes"] or d["notes"]                               # the README came along as notes


def test_studio_example_is_a_named_copy_and_the_files_are_untouched(app_client):
    before = (app_client.app.state.examples.get("tree-table").yaml)
    d = _design(app_client, _target(app_client, "/studio?example=tree-table"))
    assert d["name"] == "Tree table (copy)" and d["kind"] == "organisation" and "sutra: tree-table" in d["sutra"]
    assert app_client.app.state.examples.get("tree-table").yaml == before
    blank = _design(app_client, _target(app_client, "/studio?example=../README"))
    assert "all-panels-showcase" not in blank["sutra"] and blank["samples"] == []


def test_studio_sutra_edits_that_sutra_and_carries_the_old_test_entities_over(app_client, backend):
    backend.tests_saved["irs-vanilla"] = [{"kind": "curve", "id": "USD-SOFR"}, {"kind": "curve", "id": "EUR-ESTR"}, {"kind": "secret-kind", "id": "X-1"}]
    try:
        loc = _target(app_client, "/studio?sutra=irs-vanilla@3")
        d = _design(app_client, loc)
        assert d["base"] == "irs-vanilla@3" and "sutra: irs-vanilla" in d["sutra"] and loc.endswith("?tab=yaml")
        refs = [(s["ref"]["kind"], s["ref"]["id"]) for s in d["samples"] if s["type"] == "ref"]
        assert refs == [("curve", "USD-SOFR"), ("curve", "EUR-ESTR")], "the entities the user may open became samples; the rest were left out quietly"
        # the same through New → "an existing Sutra"
        made = app_client.post("/build/designs", json={"name": "From new", "base": "irs-vanilla@3"}).json()
        assert made["migrated"] == 2 and len(app_client.get(f"/build/designs/{made['id']}").json()["samples"]) == 2
        none = app_client.post("/build/designs", json={"name": "Other", "base": "no-tests@1"}).json()
        assert none["migrated"] == 0
    finally:
        backend.tests_saved.pop("irs-vanilla", None)


def test_studio_with_a_stored_entity_and_build_flag(app_client):
    loc = _target(app_client, "/studio?kind=curve&id=USD-SOFR&build=1")
    d = _design(app_client, loc)
    assert loc.endswith("?tab=design") and d["samples"][0]["ref"] == {"kind": "curve", "id": "USD-SOFR"} and "match: { kind: curve }" in d["sutra"]
    both = _design(app_client, _target(app_client, "/studio?sutra=irs-vanilla@3&kind=curve&id=EUR-ESTR"))
    assert both["base"] == "irs-vanilla@3" and both["samples"][0]["ref"]["id"] == "EUR-ESTR"


def test_studio_design_and_sample_open_that_design_and_the_page_selects_the_sample(app_client):
    d = _new(app_client, "Mine", kind="trade", sutra="rachana: 1\nsutra: my-own\nversion: 1\n")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    loc = _target(app_client, f"/studio?design={d['id']}&sample=b.json")
    assert loc == f"/build/d/{d['id']}?tab=yaml&sample=b.json"
    page = app_client.get(loc).text
    assert '"sample": "b.json"' in page and '"tab": "yaml"' in page


def test_old_review_addresses_move_to_the_workbench_and_keep_the_query(app_client):
    assert _target(app_client, "/studio/reviews") == "/build/reviews"
    assert _target(app_client, "/studio/reviews?status=all") == "/build/reviews?status=all"
    assert _target(app_client, "/studio/reviews/P-000042") == "/build/reviews/P-000042"
    r = app_client.post("/studio/reviews/P-000042/approve", data={"comment": "ok"}, follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"] == "/build/reviews/P-000042/approve"      # the decision's form is sent on as it is
    assert app_client.get("/build/reviews").status_code in (200, 403)


def test_studio_when_designs_cannot_be_made_says_why_instead_of_failing(app_client, backend, monkeypatch):
    real = backend.designs

    async def refuse(method, path, ident, body=None, **params):
        if method == "POST" and path == "":
            raise BackendError(413, "DRS-5005", "you keep 50 designs, the most allowed")
        return await real(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "designs", refuse)
    r = app_client.get("/studio", follow_redirects=False)
    assert r.status_code == 413 and "DRS-5005" in r.text


def test_the_json_routes_the_workbench_still_asks_of_the_old_prefix_work(app_client):
    assert app_client.get("/studio/schema").status_code == 200
    assert "rachana: 1" in app_client.post("/studio/summary", json={"yaml": "sutra: x"}).json()["html"]
    assert app_client.get("/studio/example/tree-table").json()["kind"] == "organisation"


# ---- the menu and the help guide ----------------------------------------------------------------------------------------------

def test_the_build_menu_is_create_govern_learn_with_no_studio_entry(app_client):
    page = app_client.get("/build").text
    menu = page.split('class="tbar-menus"')[1].split("</nav>")[0]
    for text in ("Create", "New screen", "My designs", "Examples", "Govern", "Reviews", "Learn", "Screen designer guide", "Sutra guide",
                 "Rachana reference", "Build a pack"):
        assert text in menu, text
    assert "Sutra Studio" not in menu and 'href="/studio' not in menu and "data-review-count" in menu
    assert "Screen Builder guide" not in menu


def test_the_review_count_for_the_menu(app_client, backend, monkeypatch):
    async def settings(ident=None):
        return {"save": True, "review": True}

    async def proposals(ident=None, status="", name=""):
        return {"proposals": [{"id": "P-1"}, {"id": "P-2"}]}
    monkeypatch.setattr(backend, "studio_settings", settings)
    monkeypatch.setattr(backend, "proposals", proposals, raising=False)
    assert app_client.get("/build/review-count").json() == {"review": True, "pending": 2}

    async def off(ident=None):
        return {"save": False, "review": False}
    monkeypatch.setattr(backend, "studio_settings", off)
    assert app_client.get("/build/review-count").json() == {"review": False, "pending": 0}


def test_the_studio_help_guide_moved_to_the_designer_guide(app_client):
    r = app_client.get("/help/sutra-studio", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == "/help/screen-designer"
    assert app_client.get("/help/screen-designer").status_code == 200
    assert "sutra-studio" not in app_client.get("/help").text
    assert not (app_client.app.state.settings.get("studio.enabled") is False)


# ---- the workbench carries Studio's features --------------------------------------------------------------------------------------

def test_the_workbench_page_has_studios_toolbar_and_the_new_panes(app_client):
    d = _new(app_client, "Tools", kind="trade", empty=True)
    page = app_client.get(f"/build/d/{d['id']}").text
    for marker in ("data-file-menu", "data-file-input", ".yaml,.yml,.json", "data-save", "data-palette-open", 'data-tab="versions"', "data-versions", 'data-tab="summary"',
                   "build/saving.js", "build/versions.js", "build/commands.js", "tree-table"):
        assert marker in page, marker
    assert "Open in Studio" not in page and "data-studio-link" not in page


def test_saving_rights_open_design_gated_save_and_review(app_client, backend, monkeypatch):
    d = _new(app_client, "Rights", kind="trade", sutra="rachana: 1\nsutra: mine\nversion: 1\n")
    page_for = lambda: app_client.get(f"/build/d/{d['id']}").text                    # noqa: E731

    async def nothing(ident=None):
        return {"save": False, "review": False}
    monkeypatch.setattr(backend, "studio_settings", nothing)
    # no author right (or saving off): the page says so, designing still works, the server refuses the save
    page = page_for()
    assert '"canSave": false' in page and 'aria-disabled="true"' in page and 'id="wbNote"' not in page
    assert app_client.post(f"/build/designs/{d['id']}/ops", json={"baseRev": d["rev"], "ops": [{"op": "text", "yaml": "sutra: y\n"}]}).status_code == 200

    async def refuse(text, ident=None, note=""):
        raise BackendError(403, "DRS-5002", "ann may not save Sutras: ask for a role with author")
    monkeypatch.setattr(backend, "save_sutra", refuse, raising=False)
    r = app_client.post(f"/build/designs/{d['id']}/propose", json={"note": "x"})
    assert r.status_code == 403 and r.json()["code"] == "DRS-5002"

    # saving on, no review: "Save", no note field; the design's own text is what is saved
    saved = {}

    async def save(text, ident=None, note=""):
        saved.update(text=text, note=note)
        return {"name": "mine", "latest": 2}

    async def on(ident=None):
        return {"save": True, "review": False}
    monkeypatch.setattr(backend, "save_sutra", save, raising=False)
    monkeypatch.setattr(backend, "studio_settings", on)
    page = page_for()
    assert '"canSave": true' in page and "Submit for review" not in page and 'id="wbNote"' not in page and "bi-save" in page
    ok = app_client.post(f"/build/designs/{d['id']}/propose", json={}).json()
    assert ok == {"name": "mine", "version": 2, "status": "live(v2)"} and saved["text"] == "sutra: y\n"

    # review on: "Submit for review", a note for the reviewer, the Reviews button with its count
    async def review(ident=None):
        return {"save": True, "review": True}

    async def waiting(ident=None, status="", name=""):
        return {"proposals": [{"id": "P-1"}]}

    async def propose(text, ident=None, note=""):
        saved.update(note=note)
        return {"proposal": {"id": "P-9", "name": "mine", "version": 2, "status": "pending"}}
    monkeypatch.setattr(backend, "studio_settings", review)
    monkeypatch.setattr(backend, "proposals", waiting, raising=False)
    monkeypatch.setattr(backend, "save_sutra", propose, raising=False)
    page = page_for()
    assert "Submit for review" in page and 'id="wbNote"' in page and '<span class="bell-count">1</span>' in page
    assert app_client.post(f"/build/designs/{d['id']}/propose", json={"note": "please"}).json()["proposal"]["id"] == "P-9" and saved["note"] == "please"


# ---- diff and versions ----------------------------------------------------------------------------------------------------------

def test_diff_against_the_base_and_any_earlier_version_and_restore_as_an_operation(app_client):
    d = app_client.post("/build/designs", json={"name": "Diffed", "base": "irs-vanilla@3"}).json()
    base_text = d["sutra"]
    base = app_client.get(f"/build/designs/{d['id']}/diff?against=base").json()
    assert base["same"] is True and "irs-vanilla v3" in base["label"]
    # two edits, as operations (the typed text is one too)
    r1 = app_client.post(f"/build/designs/{d['id']}/ops", json={"baseRev": d["rev"], "ops": [{"op": "text", "yaml": base_text + "description: first\n"}]}).json()
    r2 = app_client.post(f"/build/designs/{d['id']}/ops", json={"baseRev": r1["rev"], "ops": [{"op": "text", "yaml": base_text + "description: second\n"}]}).json()
    versions = app_client.get(f"/build/designs/{d['id']}/versions").json()
    assert [v["n"] for v in versions["versions"]] == [0, 1, 2] and versions["versions"][-1]["current"] and versions["base"] == "irs-vanilla@3"
    against_base = app_client.get(f"/build/designs/{d['id']}/diff?against=base").json()
    assert against_base["same"] is False and "+description: second" in against_base["html"]
    against_one = app_client.get(f"/build/designs/{d['id']}/diff?against=1").json()
    assert "-description: first" in against_one["html"] and "+description: second" in against_one["html"] and against_one["label"] == "version 1"
    assert app_client.get(f"/build/designs/{d['id']}/diff?against=0").json()["label"] == "version 0"
    assert app_client.get(f"/build/designs/{d['id']}/diff?against=9").status_code == 404
    assert app_client.get(f"/build/designs/{d['id']}/diff?against=nonsense").status_code == 400
    # restore version 1 = a text operation with that version's text: it is in the log, and undo takes it back
    text = app_client.get(f"/build/designs/{d['id']}/versions/1").json()["yaml"]
    assert "description: first" in text
    back = app_client.post(f"/build/designs/{d['id']}/ops", json={"baseRev": r2["rev"], "ops": [{"op": "text", "yaml": text}]}).json()
    assert "description: first" in back["yaml"]
    assert len(app_client.get(f"/build/designs/{d['id']}/versions").json()["versions"]) == 4
    undone = app_client.post(f"/build/designs/{d['id']}/undo", json={"baseRev": back["rev"]}).json()
    assert "description: second" in undone["yaml"]


def test_a_design_without_a_base_has_nothing_to_compare_with_but_its_versions(app_client):
    d = _new(app_client, "Plain", kind="trade", sutra="rachana: 1\nsutra: p\nversion: 1\n")
    r = app_client.get(f"/build/designs/{d['id']}/diff?against=base")
    assert r.status_code == 400 and "no base" in r.json()["detail"]
    versions = app_client.get(f"/build/designs/{d['id']}/versions").json()
    assert versions["base"] == "" and len(versions["versions"]) == 1


def test_nobody_else_reads_a_designs_versions_or_diff(app_client, backend):
    backend.design_rows[("somebody-else", "dforeign0003")] = {"design": {"id": "dforeign0003", "sutra": "x", "samples": []}, "docs": {}}
    for url in ("/versions", "/versions/0", "/diff?against=0"):
        assert app_client.get(f"/build/designs/dforeign0003{url}").status_code == 404
