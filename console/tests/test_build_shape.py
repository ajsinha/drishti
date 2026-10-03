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

"""Screen Builder step 2: the shape extractor page's routes. Limits are refused whole and cleanly, a bad file is reported
without failing the rest, the author power is the server's, a set expires, and the downloads are the schema with and
without Drishti's annotations."""
import json

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import create_app
from core.builder import SampleSets, plain_schema
from core.config import Settings, load_settings


def _files(*pairs):
    return {"files": [{"name": n, "text": t} for n, t in pairs]}


def _doc(i=1):
    return json.dumps({"tradeId": f"T-{i}", "book": "rates"})


@pytest.fixture()
def app_client(backend, request):
    """A console with its own settings (per test), the stand-in server behind it."""
    data = load_settings(CONSOLE / "config").as_dict()
    data.setdefault("builder", {}).update(getattr(request, "param", {}))
    app = create_app(Settings(data))
    app.state.backend = backend
    backend.shaped.clear()
    backend.builder_allowed = True
    return TestClient(app)


def test_the_page_opens_and_states_the_limits(client):
    r = client.get("/build/shape")
    assert r.status_code == 200 and "data-shape" in r.text and 'data-max-samples="50"' in r.text
    assert 'role="tree"' in r.text and 'aria-live="polite"' in r.text and "build-shape.js" in r.text


def test_the_build_menu_has_a_screen_builder_group(client):
    page = client.get("/build/shape").text
    assert "Screen Builder" in page and 'href="/build/shape"' in page and 'href="/help/screen-builder"' in page


def test_upload_shapes_and_keeps_the_set(app_client, backend):
    r = app_client.post("/build/shape", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    assert r.status_code == 200
    body = r.json()
    assert body["sampleCount"] == 2 and [f["samples"] for f in body["files"]] == [1, 1]
    assert body["shape"]["report"]["samples"] == 2 and body["expiresInSeconds"] > 86000
    assert [s["name"] for s in backend.shaped[0]] == ["a.json", "b.json"]
    again = app_client.get("/build/shape/last").json()                 # a reload keeps it
    assert again["sampleCount"] == 2 and again["shape"]["schema"]["properties"]["tradeId"]


def test_jsonl_lines_are_samples(app_client, backend):
    text = _doc(1) + "\n\nnot json\n" + _doc(2) + "\n"
    r = app_client.post("/build/shape", json=_files(("rows.jsonl", text)))
    body = r.json()
    assert r.status_code == 200 and body["sampleCount"] == 2
    assert body["files"][0]["problems"] == ["line 3 is not valid JSON"]
    assert [s["name"] for s in backend.shaped[0]] == ["rows.jsonl:1", "rows.jsonl:4"]


def test_a_bad_file_is_reported_and_the_rest_are_shaped(app_client):
    r = app_client.post("/build/shape", json=_files(("ok.json", _doc()), ("bad.json", "{oops"), ("notes.txt", "hi")))
    body = r.json()
    assert r.status_code == 200 and body["sampleCount"] == 1
    assert body["files"][0]["problems"] == [] and "not valid JSON" in body["files"][1]["problems"][0]
    assert "not a .json" in body["files"][2]["problems"][0]


@pytest.mark.parametrize("app_client", [{"max_file_mb": 0.001}], indirect=True)
def test_a_file_over_the_size_limit_is_left_out(app_client):
    big = json.dumps({"tradeId": "x" * 2000})
    r = app_client.post("/build/shape", json={"files": [{"name": "big.json", "text": big}, {"name": "ok.json", "text": _doc()},
                                                        {"name": "huge.json", "size": 9_000_000}]})
    body = r.json()
    assert r.status_code == 200 and body["sampleCount"] == 1
    assert "over the limit" in body["files"][0]["problems"][0] and "builder.max_file_mb" in body["files"][0]["problems"][0]
    assert "over the limit" in body["files"][2]["problems"][0]


@pytest.mark.parametrize("app_client", [{"max_samples": 3}], indirect=True)
def test_too_many_files_or_samples_are_refused_whole(app_client, backend):
    r = app_client.post("/build/shape", json=_files(*[(f"{i}.json", _doc(i)) for i in range(4)]))
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "builder.max_samples" in r.json()["detail"]
    r = app_client.post("/build/shape", json=_files(("rows.jsonl", "\n".join(_doc(i) for i in range(4)))))
    assert r.status_code == 413 and "more than 3 samples" in r.json()["detail"]
    assert backend.shaped == [] and app_client.get("/build/shape/last").status_code == 404


@pytest.mark.parametrize("app_client", [{"max_total_mb": 0.002}], indirect=True)
def test_a_total_over_the_limit_is_refused_whole(app_client, backend):
    pad = json.dumps({"tradeId": "x" * 900})
    r = app_client.post("/build/shape", json=_files(("a.json", pad), ("b.json", pad), ("c.json", pad)))
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "builder.max_total_mb" in r.json()["detail"]
    assert backend.shaped == []


def test_nothing_usable_is_a_422_with_the_problems(app_client, backend):
    r = app_client.post("/build/shape", json=_files(("bad.json", "{")))
    assert r.status_code == 422 and r.json()["files"][0]["problems"] and backend.shaped == []


def test_bad_bodies_are_400_and_415(app_client):
    assert app_client.post("/build/shape", json={"files": []}).status_code == 400
    assert app_client.post("/build/shape", json=[1]).status_code == 400
    assert app_client.post("/build/shape", content="files", headers={"Content-Type": "text/plain"}).status_code == 415


def test_only_authors_may_shape(app_client, backend):
    backend.builder_allowed = False
    r = app_client.post("/build/shape", json=_files(("a.json", _doc())))
    assert r.status_code == 403 and r.json()["code"] == "DRS-5002" and r.json()["files"]
    assert app_client.get("/build/shape/last").status_code == 404           # nothing kept for a refused upload


def test_a_set_expires(backend):
    now = [1000.0]
    sets = SampleSets(3600, clock=lambda: now[0])
    s = sets.put("ana", [{"name": "a", "document": {}}], [], {})
    assert sets.get("ana") is s and sets.seconds_left(s) == 3600
    now[0] += 3599
    assert sets.get("ana") is s and sets.get("bob") is None                  # per user
    now[0] += 2
    assert sets.get("ana") is None


def test_the_ttl_is_a_setting(backend):
    assert SampleSets(0.0).get("ana") is None
    data = load_settings(CONSOLE / "config").as_dict()
    data["builder"]["ttl_hours"] = 2
    assert create_app(Settings(data)).state.sample_sets.ttl == 7200


def test_forget_clears_the_set(app_client):
    app_client.post("/build/shape", json=_files(("a.json", _doc())))
    assert app_client.delete("/build/shape/last").status_code == 204
    assert app_client.get("/build/shape/last").status_code == 404


def test_downloads_have_the_right_types_and_strip_annotations(app_client):
    assert app_client.get("/build/shape/download").status_code == 404        # nothing uploaded yet
    app_client.post("/build/shape", json=_files(("a.json", _doc())))
    s = app_client.get("/build/shape/download?kind=shape")
    assert s.headers["content-type"].startswith("application/json") and 'filename="shape.json"' in s.headers["content-disposition"]
    assert "x-drishti" in s.text and s.json()["properties"]["tradeId"]["x-drishti"]["role"] == "id"
    p = app_client.get("/build/shape/download?kind=schema")
    assert p.headers["content-type"].startswith("application/json") and 'filename="schema.json"' in p.headers["content-disposition"]
    assert "x-drishti" not in p.text and p.json()["properties"]["tradeId"] == {"type": "string"}
    assert app_client.get("/build/shape/download?kind=other").status_code == 400


def test_plain_schema_strips_only_annotations():
    schema = {"type": "object", "x-drishti": {"a": 1},
              "properties": {"x-drishti": {"type": "string", "x-drishti": {"role": "id"}}, "n": {"type": "string", "enum": [{"x-drishti": 1}]}},
              "$defs": {"d": {"x-drishti": {}, "type": "integer"}}, "items": [{"x-drishti": 1, "type": "null"}]}
    assert plain_schema(schema) == {"type": "object", "properties": {"x-drishti": {"type": "string"}, "n": {"type": "string", "enum": [{"x-drishti": 1}]}},
                                    "$defs": {"d": {"type": "integer"}}, "items": [{"type": "null"}]}


def test_open_in_studio_pastes_the_first_sample(app_client):
    assert "Preview against" in app_client.get("/studio?build=1").text            # nothing kept: Studio opens as usual
    app_client.post("/build/shape", json=_files(("first.json", _doc(7)), ("second.json", _doc(8))))
    page = app_client.get("/studio?build=1").text
    assert "T-7" in page and "T-8" not in page and 'value="first.json"' in page and 'value="sample"' in page
    assert "data-use-json checked" in page


def test_the_help_guide_and_context_exist(client):
    r = client.get("/help/screen-builder")
    assert r.status_code == 200 and "Shape extractor" in r.text and "the Screen Builder design" in r.text
    assert "SCREEN_BUILDER" in r.text or "screen-builder" in r.text
    assert client.get("/help/context/build", follow_redirects=False).headers["location"] == "/help/screen-builder"


# ---- step 3: draft a screen ---------------------------------------------------------------------------------------

def test_draft_needs_a_sample_set(app_client):
    r = app_client.post("/build/design", json={})
    assert r.status_code == 404 and r.json()["code"] == "DRS-1001"


def test_draft_sends_the_set_with_the_studio_kind_and_returns_the_preview_html(app_client, backend):
    app_client.post("/build/shape", json=_files(("a.json", _doc(1)), ("b.json", _doc(2))))
    backend.designed.clear()
    r = app_client.post("/build/design", json={})
    assert r.status_code == 200
    body = r.json()
    assert "sutra: sample-auto" in body["yaml"] and body["samples"] == 2 and "preview" not in body
    assert body["pruned"][0]["reason"] == "empty in 4 of 5 samples" and body["alternatives"]["pnl"][0]["kind"] == "area"
    assert "studio-view" in body["previewHtml"]
    assert [s["name"] for s in backend.designed[0][0]] == ["a.json", "b.json"] and backend.designed[0][1] == "sample"


def test_draft_is_refused_for_non_authors(app_client, backend):
    app_client.post("/build/shape", json=_files(("a.json", _doc(1))))
    backend.builder_allowed = False
    r = app_client.post("/build/design", json={})
    assert r.status_code == 403 and r.json()["code"] == "DRS-5002"


def test_open_in_studio_with_the_draft_loads_the_drafted_sutra_and_the_first_sample(app_client):
    app_client.post("/build/shape", json=_files(("first.json", _doc(7)), ("second.json", _doc(8))))
    assert "sutra: sample-auto" not in app_client.get("/studio?build=1&draft=1").text           # nothing drafted yet
    app_client.post("/build/design", json={})
    page = app_client.get("/studio?build=1&draft=1").text
    assert "sutra: sample-auto" in page and "T-7" in page and 'value="first.json"' in page
    assert "sutra: sample-auto" not in app_client.get("/studio?build=1").text                    # without draft=1 as before


def test_a_new_upload_forgets_the_old_draft(app_client):
    app_client.post("/build/shape", json=_files(("first.json", _doc(7))))
    app_client.post("/build/design", json={})
    app_client.post("/build/shape", json=_files(("other.json", _doc(9))))
    assert "sutra: sample-auto" not in app_client.get("/studio?build=1&draft=1").text


def test_the_page_has_the_draft_button_and_its_result_area(client):
    page = client.get("/build/shape").text
    assert "data-design" in page and "Draft a screen" in page and "data-draft-yaml" in page and "data-draft-preview" in page
    assert 'href="/studio?build=1&amp;draft=1"' in page and "echarts.min.js" in page
