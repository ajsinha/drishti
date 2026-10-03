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

"""Build workbench, step 4: My designs, New, a Design's page, bringing data in (files, limits), examples as Design copies,
and the old shape extractor address. The server keeps the Designs; the stand-in server (FakeBackend) does here, shared by
every console built on it, which is what lets a test restart a console or run two."""
import hashlib
from conftest import open_studio  # noqa: E402
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core import designs
from core.builder import plain_schema
from core.config import Settings, load_settings

EXAMPLES = CONSOLE.parent / "docs" / "guides" / "examples"


def _files(*pairs):
    return {"files": [{"name": n, "text": t} for n, t in pairs]}


def _doc(i=1):
    return json.dumps({"tradeId": f"T-{i}", "book": "rates"})


def _console(backend, **builder):
    data = load_settings(CONSOLE / "config").as_dict()
    data.setdefault("builder", {}).update(builder)
    app = create_app(Settings(data))
    app.state.backend = backend
    return TestClient(app)


@pytest.fixture()
def app_client(backend, request):
    """A console with its own settings (per test), the stand-in server behind it, holding no Designs yet."""
    backend.design_rows = {}
    backend.builder_allowed = True
    backend.shaped.clear()
    return _console(backend, **getattr(request, "param", {}))


def _land(client, url):
    """Follows an old Studio address one step: it must be a 302 into the workbench; returns (location, the page it lands on)."""
    r = open_studio(client, url)
    assert r.status_code in (302, 303) and r.headers["location"].startswith("/build/d/"), (url, r.status_code, r.headers.get("location"))
    return r.headers["location"], client.get(r.headers["location"]).text


def _new(c, name="Mine", **more):
    r = c.post("/build/designs", json={"name": name, **more})
    assert r.status_code == 201, r.text
    return r.json()


# ---- the pages --------------------------------------------------------------------------------------------------------

def test_new_page_offers_the_five_ways_to_bring_data_and_the_start_choices(app_client):
    page = app_client.get("/build/new").text
    assert "data-new" in page and 'data-max-samples="50"' in page and "webkitdirectory" in page
    for anchor in ("files", "schema", "store", "examples", "sutra"):
        assert f'id="{anchor}"' in page
    assert 'value="auto" checked' in page and 'value="existing"' in page and 'value="empty"' in page
    assert "build-new.js" in page and "build-files.js" in page and 'aria-live="polite"' in page
    names = sorted(p.name[: -len(".sutra.yaml")] for p in EXAMPLES.glob("*.sutra.yaml"))
    assert len(names) >= 10 and all(f'data-example="{n}"' in page for n in names)       # every example is a card
    assert "irs-vanilla@3" in page                                                       # the registry's Sutras


def test_the_build_menu_has_create_my_designs_and_examples(app_client):
    page = app_client.get("/build").text
    assert 'href="/build/new"' in page and 'href="/build"' in page and 'href="/build/new#examples"' in page
    assert "New screen" in page and "My designs" in page and "Examples" in page
    assert 'href="/build/reviews"' in page and "Govern" in page and "Rachana reference" in page and "Screen designer guide" in page
    assert 'href="/studio"' not in page and "Sutra Studio" not in page                    # no separate Studio entry any more


def test_the_old_shape_extractor_address_redirects_and_keeps_the_query(app_client):
    r = app_client.get("/build/shape", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == "/build/new#files"
    r = app_client.get("/build/shape?x=1&y=two", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == "/build/new?x=1&y=two#files"
    assert app_client.get("/build/shape").status_code == 200                              # followed: lands on New


def test_my_designs_lists_renames_duplicates_and_deletes(app_client):
    assert "Nothing here yet" in app_client.get("/build").text
    d = _new(app_client, "Rates screen", kind="trade")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1))))
    page = app_client.get("/build").text
    assert "Rates screen" in page and f'href="/build/d/{d["id"]}"' in page and "data-rename" in page and "data-duplicate" in page
    assert app_client.patch(f"/build/designs/{d['id']}", json={"name": "Rates v2"}).json()["name"] == "Rates v2"
    c = app_client.post(f"/build/designs/{d['id']}/duplicate", json={}).json()
    assert c["name"] == "Rates v2 copy" and len(c["samples"]) == 1 and c["id"] != d["id"]
    page = app_client.get("/build").text
    assert "Rates v2 copy" in page
    assert app_client.delete(f"/build/designs/{d['id']}").status_code == 204
    assert app_client.get(f"/build/d/{d['id']}").status_code == 404
    assert "Rates v2 copy" in app_client.get("/build").text and f'href="/build/d/{d["id"]}"' not in app_client.get("/build").text


def test_a_scratch_design_is_marked(app_client):
    d = app_client.post("/build/designs", json={}).json()
    assert d["scratch"] is True
    assert "scratch" in app_client.get("/build").text


def test_the_design_page_is_the_workbench_with_its_panes_and_samples(app_client):
    d = _new(app_client, "Page", empty=True, kind="trade")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    page = app_client.get(f"/build/d/{d['id']}").text
    assert "data-workbench" in page and "data-design-page" in page and 'role="tree"' in page and "sutra: my-layout" in page
    for pane in ("Design", "YAML", "Summary", "Inspector", "Problems", "Tests"):
        assert f'role="tab"' in page and f">{pane}" in page
    assert '"name": "a.json"' in page.replace("&#34;", '"') and '"name": "b.json"' in page.replace("&#34;", '"')
    assert "data-autodesign" in page and "build/workbench.js" in page and "build-design.js" not in page
    assert "data-preview-file" in page and "data-undo" in page and "data-redo" in page and 'aria-live="polite"' in page
    assert "build-workbench.css" in page


def test_a_missing_or_foreign_design_is_a_404_page(app_client, backend):
    assert app_client.get("/build/d/nope").status_code == 404
    backend.design_rows[("somebody-else", "dforeign0001")] = {"design": {"id": "dforeign0001", "name": "Theirs", "samples": []}, "docs": {}}
    page = app_client.get("/build/d/dforeign0001")
    assert page.status_code == 404 and "Theirs" not in page.text
    assert "Theirs" not in app_client.get("/build").text


# ---- a set survives a restart; two consoles see the same Design ------------------------------------------------------------

def test_a_sample_set_survives_a_console_restart(backend):
    backend.design_rows = {}
    first = _console(backend)
    d = _new(first, "Kept", kind="trade")
    assert first.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2)))).status_code == 200
    del first                                                    # the console goes away; the server keeps the Design
    second = _console(backend)
    page = second.get(f"/build/d/{d['id']}").text
    assert 'data-sample="a.json"' in page and 'data-sample="b.json"' in page and "Kept" in second.get("/build").text
    shape = second.get(f"/build/designs/{d['id']}/shape").json()
    assert shape["report"]["samples"] == 2 and shape["schema"]["properties"]["tradeId"]


def test_two_consoles_see_the_same_design(backend):
    backend.design_rows = {}
    a, b = _console(backend), _console(backend)
    d = _new(a, "Shared between consoles")
    assert "Shared between consoles" in b.get("/build").text
    b.patch(f"/build/designs/{d['id']}", json={"name": "Renamed on the other"})
    assert "Renamed on the other" in a.get("/build").text
    b.post(f"/build/designs/{d['id']}/files", json=_files(("x.json", _doc(5))))
    assert 'data-sample="x.json"' in a.get(f"/build/d/{d['id']}").text


# ---- bringing data in --------------------------------------------------------------------------------------------------------

def test_files_become_samples_of_the_design(app_client, backend):
    d = _new(app_client)
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    assert r.status_code == 200
    body = r.json()
    assert [s["name"] for s in body["samples"]] == ["a.json", "b.json"] and [f["samples"] for f in body["files"]] == [1, 1]
    shape = app_client.get(f"/build/designs/{d['id']}/shape").json()
    assert shape["report"]["samples"] == 2 and [s["name"] for s in backend.shaped[0]] == ["a.json", "b.json"]


def test_a_folder_keeps_relative_names_and_jsonl_lines_are_samples(app_client):
    d = _new(app_client)
    text = _doc(1) + "\n\nnot json\n" + _doc(2) + "\n"
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("examples/rows.jsonl", text), ("examples/sub/one.json", _doc(3))))
    body = r.json()
    assert r.status_code == 200 and [s["name"] for s in body["samples"]] == ["examples/rows.jsonl:1", "examples/rows.jsonl:4", "examples/sub/one.json"]
    assert body["files"][0]["problems"] == ["line 3 is not valid JSON"]


def test_a_bad_file_is_reported_and_the_rest_are_kept(app_client):
    d = _new(app_client)
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("ok.json", _doc()), ("bad.json", "{oops"), ("notes.txt", "hi")))
    body = r.json()
    assert r.status_code == 200 and len(body["samples"]) == 1
    assert body["files"][0]["problems"] == [] and "not valid JSON" in body["files"][1]["problems"][0] and "not a .json" in body["files"][2]["problems"][0]


@pytest.mark.parametrize("app_client", [{"max_file_mb": 0.001}], indirect=True)
def test_a_file_over_the_size_limit_is_left_out(app_client):
    d = _new(app_client)
    big = json.dumps({"tradeId": "x" * 2000})
    r = app_client.post(f"/build/designs/{d['id']}/files", json={"files": [{"name": "big.json", "text": big}, {"name": "ok.json", "text": _doc()},
                                                                           {"name": "huge.json", "size": 9_000_000}]})
    body = r.json()
    assert r.status_code == 200 and len(body["samples"]) == 1
    assert "builder.max_file_mb" in body["files"][0]["problems"][0] and "over the limit" in body["files"][2]["problems"][0]


@pytest.mark.parametrize("app_client", [{"max_samples": 3}], indirect=True)
def test_too_many_files_or_samples_are_refused_whole(app_client):
    d = _new(app_client)
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(*[(f"{i}.json", _doc(i)) for i in range(4)]))
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "builder.max_samples" in r.json()["detail"]
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("rows.jsonl", "\n".join(_doc(i) for i in range(4)))))
    assert r.status_code == 413 and "more than 3 samples" in r.json()["detail"]
    assert app_client.get(f"/build/d/{d['id']}").text.count("data-sample=") == 0


@pytest.mark.parametrize("app_client", [{"max_total_mb": 0.002}], indirect=True)
def test_a_total_over_the_limit_is_refused_whole(app_client):
    d = _new(app_client)
    pad = json.dumps({"tradeId": "x" * 900})
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", pad), ("b.json", pad), ("c.json", pad)))
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "builder.max_total_mb" in r.json()["detail"]


def test_the_servers_limits_answer_with_their_code(app_client, backend):
    backend.designs_limits = {**backend.designs_limits, "maxPerUser": 1, "maxSamples": 2}
    try:
        _new(app_client, "one")
        r = app_client.post("/build/designs", json={"name": "two"})
        assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "max-per-user" in r.json()["detail"]
        d = next(iter(backend.design_rows.values()))["design"]
        r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("1.json", "{}"), ("2.json", "{}"), ("3.json", "{}")))
        assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and r.json()["files"]
    finally:
        backend.designs_limits = {**backend.designs_limits, "maxPerUser": 50, "maxSamples": 50}


def test_nothing_usable_is_a_422_with_the_problems(app_client):
    d = _new(app_client)
    r = app_client.post(f"/build/designs/{d['id']}/files", json=_files(("bad.json", "{")))
    assert r.status_code == 422 and r.json()["files"][0]["problems"]


def test_bad_bodies_are_400_and_415(app_client):
    d = _new(app_client)
    assert app_client.post(f"/build/designs/{d['id']}/files", json={"files": []}).status_code == 400
    assert app_client.post(f"/build/designs/{d['id']}/files", json=[1]).status_code == 400
    assert app_client.post(f"/build/designs/{d['id']}/files", content="files", headers={"Content-Type": "text/plain"}).status_code == 415
    assert app_client.post(f"/build/designs/{d['id']}/samples", json={}).status_code == 400


def test_a_schema_gives_synthetic_samples_and_store_refs_are_references(app_client):
    d = _new(app_client)
    s = app_client.post(f"/build/designs/{d['id']}/samples", json={"schema": {"type": "object"}, "count": 3}).json()
    assert [x["type"] for x in s["samples"]] == ["synthetic"] * 3 and all(x["synthetic"] for x in s["samples"])
    r = app_client.post(f"/build/designs/{d['id']}/samples", json={"refs": {"kind": "curve", "ids": ["USD-SOFR"]}}).json()
    assert r["samples"][-1]["ref"] == {"kind": "curve", "id": "USD-SOFR"}
    page = app_client.get(f"/build/d/{d['id']}").text
    assert "synthetic" in page and "stored entity" in page
    assert app_client.delete(f"/build/designs/{d['id']}/samples?name=synthetic-1").json()["samples"][0]["name"] == "synthetic-2"


def test_a_reference_to_a_kind_the_user_may_not_open_previews_no_access(app_client, backend):
    d = _new(app_client, "Refs", empty=True)
    app_client.post(f"/build/designs/{d['id']}/samples", json={"refs": {"kind": "curve", "ids": ["USD-SOFR"]}})
    assert app_client.get(f"/build/designs/{d['id']}/preview?sample=curve%20USD-SOFR").status_code == 200
    backend.designs_open = set()                              # the role no longer opens curves
    try:
        r = app_client.get(f"/build/designs/{d['id']}/preview?sample=curve%20USD-SOFR")
        assert r.status_code == 403 and r.json()["code"] == "DRS-5002" and "no access" in r.json()["detail"]
    finally:
        backend.designs_open = {"curve"}


def test_downloads_have_the_right_types_and_strip_annotations(app_client):
    d = _new(app_client)
    assert app_client.get(f"/build/designs/{d['id']}/shape/download").status_code == 400          # no samples yet
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc())))
    s = app_client.get(f"/build/designs/{d['id']}/shape/download?kind=shape")
    assert s.headers["content-type"].startswith("application/json") and 'filename="shape.json"' in s.headers["content-disposition"]
    assert "x-drishti" in s.text and s.json()["properties"]["tradeId"]["x-drishti"]["role"] == "id"
    p = app_client.get(f"/build/designs/{d['id']}/shape/download?kind=schema")
    assert 'filename="schema.json"' in p.headers["content-disposition"] and "x-drishti" not in p.text
    assert p.json()["properties"]["tradeId"] == {"type": "string"}
    assert app_client.get(f"/build/designs/{d['id']}/shape/download?kind=other").status_code == 400


def test_plain_schema_strips_only_annotations():
    schema = {"type": "object", "x-drishti": {"a": 1},
              "properties": {"x-drishti": {"type": "string", "x-drishti": {"role": "id"}}, "n": {"type": "string", "enum": [{"x-drishti": 1}]}},
              "$defs": {"d": {"x-drishti": {}, "type": "integer"}}, "items": [{"x-drishti": 1, "type": "null"}]}
    assert plain_schema(schema) == {"type": "object", "properties": {"x-drishti": {"type": "string"}, "n": {"type": "string", "enum": [{"x-drishti": 1}]}},
                                    "$defs": {"d": {"type": "integer"}}, "items": [{"type": "null"}]}


# ---- auto-design, preview, Studio ------------------------------------------------------------------------------------------

def test_autodesign_keeps_the_draft_as_the_designs_sutra_and_returns_the_preview_html(app_client, backend):
    d = _new(app_client, kind="trade")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    r = app_client.post(f"/build/designs/{d['id']}/autodesign", json={})
    body = r.json()
    assert r.status_code == 200 and "sutra: trade-auto" in body["yaml"] and body["rev"] == 1 and "preview" not in body
    assert body["pruned"][0]["reason"] == "empty in 4 of 5 samples" and body["alternatives"]["pnl"][0]["kind"] == "area" and "studio-view" in body["previewHtml"]
    assert "sutra: trade-auto" in app_client.get(f"/build/d/{d['id']}").text
    assert app_client.post(f"/build/designs/{d['id']}/autodesign", json={}).json()["rev"] == 1          # same draft, same revision


def test_autodesign_without_samples_is_refused(app_client):
    d = _new(app_client)
    assert app_client.post(f"/build/designs/{d['id']}/autodesign", json={}).status_code == 400


def test_preview_switches_samples(app_client):
    d = _new(app_client, empty=True, kind="trade")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("first.json", _doc(7)), ("second.json", _doc(8))))
    one = app_client.get(f"/build/designs/{d['id']}/preview?sample=first.json").json()
    two = app_client.get(f"/build/designs/{d['id']}/preview?sample=second.json").json()
    assert "T-7" in one["previewHtml"] and "T-8" in two["previewHtml"] and "T-8" not in one["previewHtml"]


def test_the_old_studio_design_address_lands_on_the_design_with_its_sample(app_client):
    d = _new(app_client, "For Studio", kind="trade", sutra="rachana: 1\nsutra: my-own\nversion: 1\n")
    app_client.post(f"/build/designs/{d['id']}/files", json=_files(("first.json", _doc(7)), ("second.json", _doc(8))))
    r = app_client.get(f"/studio?design={d['id']}&sample=second.json", follow_redirects=False)
    assert r.status_code == 302 and r.headers["location"] == f"/build/d/{d['id']}?tab=split&sample=second.json"
    page = app_client.get(r.headers["location"]).text
    assert "sutra: my-own" in page and '"sample": "second.json"' in page
    assert app_client.get(f"/studio?design={d['id']}", follow_redirects=False).headers["location"] == f"/build/d/{d['id']}?tab=split"


def test_studio_with_a_stored_entity_makes_a_design_with_it_as_a_sample_and_ignores_a_foreign_design(app_client, backend):
    r = open_studio(app_client, "/studio?kind=curve&id=USD-SOFR")
    assert r.status_code == 303
    d = app_client.get(r.headers["location"].split("?")[0].replace("/build/d/", "/build/designs/")).json()
    assert d["samples"][0]["ref"] == {"kind": "curve", "id": "USD-SOFR"} and d["kind"] == "curve" and "match: { kind: curve }" in d["sutra"]
    backend.design_rows[("somebody-else", "dforeign0002")] = {"design": {"id": "dforeign0002", "sutra": "rachana: 1\nsutra: theirs\n", "samples": []}, "docs": {}}
    loc = open_studio(app_client, "/studio?design=dforeign0002").headers["location"]
    assert "dforeign0002" not in loc and "sutra: theirs" not in app_client.get(loc).text      # not theirs to open: a new Design, as Studio opened as usual


def test_studio_and_its_examples_keep_working(app_client):
    loc, page = _land(app_client, "/studio?example=all-panels-showcase")
    assert "sutra: all-panels-showcase" in page and "all-panels-showcase.json" in page and loc.endswith("?tab=split")
    assert "all-panels-showcase" in app_client.get("/help/examples").text and 'data-example-copy="all-panels-showcase"' in app_client.get("/help/examples").text
    assert "sutra: all-panels-showcase" in _land(app_client, "/studio")[1]            # ui.studio_example: the showcase is the default


# ---- examples open as Design copies ----------------------------------------------------------------------------------------

def _row(backend, id_):
    return next(r for (_, i), r in backend.design_rows.items() if i == id_)


def _digest(directory: Path) -> str:
    h = hashlib.sha256()
    for p in sorted(directory.rglob("*")):
        if p.is_file():
            h.update(p.name.encode() + p.read_bytes())
    return h.hexdigest()


def test_every_example_opens_as_a_design_copy_and_previews_and_the_example_files_are_unchanged(app_client, backend):
    before = _digest(EXAMPLES)
    names = sorted(p.name[: -len(".sutra.yaml")] for p in EXAMPLES.glob("*.sutra.yaml"))
    assert len(names) >= 10
    for name in names:
        opened = app_client.post(f"/build/examples/{name}/open")
        assert opened.status_code == 201, name
        row = _row(backend, opened.json()["id"])
        d = row["design"]
        assert d["sutra"] == (EXAMPLES / f"{name}.sutra.yaml").read_text(encoding="utf-8"), name
        assert d["notes"] == designs.strip_licence((EXAMPLES / f"{name}.md").read_text(encoding="utf-8")) and d["name"].endswith("(copy)") and not d["scratch"]
        assert row["docs"][f"{name}.json"] == json.loads((EXAMPLES / f"{name}.json").read_text(encoding="utf-8"))
        page = app_client.get(opened.json()["url"])
        assert page.status_code == 200 and f'data-sample="{name}.json"' in page.text
        prev = app_client.get(f"/build/designs/{d['id']}/preview?sample={name}.json")
        assert prev.status_code == 200 and "studio-view" in prev.json()["previewHtml"] and "did not have the shape" not in prev.json()["previewHtml"]
    assert _digest(EXAMPLES) == before                                  # the workbench only ever read the example files
    assert app_client.post("/build/examples/nope/open").status_code == 404
    assert app_client.post("/build/examples/..%2Fetc/open").status_code in (404, 422)


def test_the_showcase_copy_previews_all_twenty_one_panels(app_client):
    opened = app_client.post("/build/examples/all-panels-showcase/open").json()
    assert "(copy)" in app_client.get("/build").text
    html = app_client.get(f"/build/designs/{opened['id']}/preview").json()["previewHtml"]
    assert html.count('data-panel="') == 21 and "did not have the shape" not in html


def test_a_get_to_studio_creates_nothing_and_only_the_confirmed_post_does(app_client, backend):
    """S2-04: opening an address (a cross-site link, a bookmark) asks first; only the button's POST starts the design."""
    before = len(backend.design_rows)
    for url in ("/studio", "/studio?example=tree-table", "/studio?sutra=irs-vanilla@3", "/studio?kind=curve&id=USD-SOFR"):
        r = app_client.get(url, follow_redirects=False)
        assert r.status_code == 200 and "data-start-form" in r.text and 'method="post"' in r.text, url
    assert len(backend.design_rows) == before
    cross = app_client.post("/studio", data={"example": "tree-table"}, headers={"Origin": "https://evil.example"}, follow_redirects=False)
    assert cross.status_code == 403 and len(backend.design_rows) == before
    ok = app_client.post("/studio", data={"example": "tree-table"}, follow_redirects=False)
    assert ok.status_code == 303 and ok.headers["location"].startswith("/build/d/") and len(backend.design_rows) == before + 1


def test_my_designs_can_delete_all_scratch_designs_at_once(app_client):
    named = _new(app_client, "Keep me")
    app_client.post("/studio", data={})
    app_client.post("/studio", data={})
    page = app_client.get("/build").text
    assert "data-delete-scratch" in page and "scratch ones" in page
    r = app_client.delete("/build/designs")
    assert r.status_code == 200 and r.json()["deleted"] >= 2
    assert app_client.get(f"/build/designs/{named['id']}").status_code == 200
    assert "data-delete-scratch" not in app_client.get("/build").text


def test_a_chunked_body_is_refused_while_it_streams(app_client):
    """S2-09: no Content-Length, so the limit is enforced on the stream, not after reading it all."""
    def gen():
        for _ in range(80):
            yield b"x" * (1024 * 1024)
    d = _new(app_client, "Chunky")
    r = app_client.post(f"/build/designs/{d['id']}/files", content=gen(), headers={"Content-Type": "application/json"})
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005"
    z = app_client.post("/build/import", content=gen(), headers={"Content-Type": "application/zip"})
    assert z.status_code == 413 and z.json()["code"] == "DRS-5005"
