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
"""Build -> New pack, the Pack designer's HTTP routes (/build/pack/api/ws...): a workspace made, its files put up and taken away, the jobs
started (the job's own process stands in: no process runs here), the live screen's preview and check, and deploy; each with the request that
must be refused (a 4xx, never a 500)."""
import json

import pytest

from core import packdesigner as PD

OCTET = {"Content-Type": "application/octet-stream"}


@pytest.fixture
def author(backend, monkeypatch):
    async def studio_settings(ident=None):
        return {"save": True, "author": True}
    monkeypatch.setattr(backend, "studio_settings", studio_settings)


@pytest.fixture
def jobs(monkeypatch):
    """The workspace's job start recorded instead of run: (operation, request)."""
    started = []
    monkeypatch.setattr(PD.Workspaces, "start", lambda self, scope, d, op, request, env=None: started.append((op, request)))
    return started


def make(client) -> str:
    r = client.post("/build/pack/api/ws")
    assert r.status_code == 201, r.text
    return r.json()["id"]


def folder(client, wid):
    ws = client.app.state.pack_designer
    return next(p for p in ws.base.rglob(wid) if p.is_dir())


def analysed(client, wid):
    (folder(client, wid) / "analysis.json").write_text(json.dumps({"sutras": [{"name": "s1", "kind": "trade", "samples": [{"tradeId": "T-1"}]}]}))


def test_a_workspace_needs_the_author_power_and_is_removed(client, author):
    wid = make(client)
    assert client.get(f"/build/pack/api/ws/{wid}").json()["files"] == []
    assert client.post(f"/build/pack/api/ws/{wid}/cancel").json() == {"cancelled": False}
    assert client.delete(f"/build/pack/api/ws/{wid}").status_code == 204
    assert client.delete(f"/build/pack/api/ws/{wid}").status_code == 404
    assert client.post(f"/build/pack/api/ws/{wid}/cancel").status_code == 404


def test_starting_a_pack_without_the_author_power_is_refused(client, backend, monkeypatch):
    async def studio_settings(ident=None):
        return {"save": False}
    monkeypatch.setattr(backend, "studio_settings", studio_settings)
    assert client.post("/build/pack/api/ws").status_code == 403


def test_files_go_up_one_by_one_and_a_path_that_is_not_a_data_file_is_refused(client, author):
    wid = make(client)
    r = client.post(f"/build/pack/api/ws/{wid}/file?path=trades/day1.jsonl", content=b'{"a":1}\n', headers=OCTET)
    assert r.status_code == 200 and r.json() == {"path": "trades/day1.jsonl", "bytes": 8}
    assert [f["path"] for f in client.get(f"/build/pack/api/ws/{wid}").json()["files"]] == ["trades/day1.jsonl"]
    assert client.post(f"/build/pack/api/ws/{wid}/file?path=notes.csv", content=b"a,b\n", headers=OCTET).status_code == 415
    assert client.post(f"/build/pack/api/ws/{wid}/file?path=../escape.jsonl", content=b"{}\n", headers=OCTET).status_code == 400
    assert client.delete(f"/build/pack/api/ws/{wid}/file?path=../escape.jsonl").status_code == 400
    assert client.delete(f"/build/pack/api/ws/{wid}/file?path=trades/day1.jsonl").status_code == 204
    assert client.get(f"/build/pack/api/ws/{wid}").json()["files"] == []
    assert client.post(f"/build/pack/api/ws/no-such-ws/file?path=a.jsonl", content=b"{}\n", headers=OCTET).status_code == 404


def test_analyse_starts_a_job_from_valid_choices_only(client, author, jobs):
    wid = make(client)
    r = client.post(f"/build/pack/api/ws/{wid}/analyse", json={"name": "my-bank", "kind": "trade", "key": "ID"})
    assert r.status_code == 202 and r.json() == {"id": wid}
    assert jobs[-1][0] == "analyse" and jobs[-1][1]["name"] == "my-bank"
    n = len(jobs)
    assert client.post(f"/build/pack/api/ws/{wid}/analyse", json={"name": "Bad Name"}).status_code == 400
    assert client.post(f"/build/pack/api/ws/{wid}/analyse", json=[1]).status_code == 400
    assert client.post(f"/build/pack/api/ws/no-such-ws/analyse", json={"name": "my-bank"}).status_code == 404
    assert len(jobs) == n


def test_analyse_finalize_and_map_need_the_author_power(client, backend, monkeypatch, jobs):
    wid_holder = []

    async def studio(ident=None):
        return {"author": bool(wid_holder)}
    monkeypatch.setattr(backend, "studio_settings", studio)
    wid_holder.append(1)
    wid = make(client)
    wid_holder.clear()
    for step in ("analyse", "finalize", "map"):
        assert client.post(f"/build/pack/api/ws/{wid}/{step}", json={"name": "my-bank"}).status_code == 403, step
    assert jobs == []


def test_finalize_needs_the_analysis_first_then_starts_the_job(client, author, jobs):
    wid = make(client)
    assert client.post(f"/build/pack/api/ws/{wid}/finalize", json={"name": "my-bank"}).status_code == 409
    analysed(client, wid)
    r = client.post(f"/build/pack/api/ws/{wid}/finalize", json={"name": "my-bank", "edits": {"s1": "sutra: s1\n"}})
    assert r.status_code == 202 and jobs[-1][0] == "finalize" and jobs[-1][1]["edits"] == {"s1": "sutra: s1\n"}
    assert client.post(f"/build/pack/api/ws/{wid}/finalize", json={"name": "my-bank", "mode": "source", "sourceFor": "trade", "system": "ENDUR"}).status_code == 409      # the field map comes first
    assert client.post(f"/build/pack/api/ws/{wid}/finalize", json={"name": "Bad Name"}).status_code == 400


def test_map_proposes_the_field_map_of_a_source(client, author, jobs):
    wid = make(client)
    r = client.post(f"/build/pack/api/ws/{wid}/map", json={"sourceFor": "trade", "system": "ENDUR_X"})
    assert r.status_code == 202 and jobs[-1][0] == "map" and jobs[-1][1]["name"] == "source-endur-x" and jobs[-1][1]["mode"] == "source"
    assert client.post(f"/build/pack/api/ws/{wid}/map", json={"name": "Not Valid"}).status_code == 400
    assert client.post(f"/build/pack/api/ws/{wid}/map", json=[]).status_code == 400


def test_the_live_screen_previews_and_checks_the_analysed_sutra(client, author):
    wid = make(client)
    analysed(client, wid)
    ok = client.post(f"/build/pack/api/ws/{wid}/preview", json={"sutra": "s1", "yaml": "sutra: s1\n", "sample": 0})
    assert ok.status_code == 200 and ok.json()["samples"] == 1 and "previewHtml" in ok.json()
    checked = client.post(f"/build/pack/api/ws/{wid}/check", json={"sutra": "s1", "yaml": "sutra: s1\n"})
    assert checked.status_code == 200 and "counts" in checked.json() and "panels" in checked.json()
    broken = client.post(f"/build/pack/api/ws/{wid}/check", json={"sutra": "s1", "yaml": "sutra: BROKEN\n"})
    assert broken.status_code == 200 and broken.json()["refused"] is True and broken.json()["problems"]
    for step in ("preview", "check"):
        assert client.post(f"/build/pack/api/ws/{wid}/{step}", json={"sutra": "nope", "yaml": "x"}).status_code == 404, step
        assert client.post(f"/build/pack/api/ws/{wid}/{step}", json={"sutra": "s1"}).status_code == 404, step            # no YAML to draw
        assert client.post(f"/build/pack/api/ws/{wid}/{step}", json=[1]).status_code == 400, step


def test_deploy_installs_or_plans_a_made_module_and_refuses_what_is_not_ready(client, author, backend, monkeypatch, tmp_path):
    wid = make(client)
    assert client.post(f"/build/pack/api/ws/{wid}/deploy/install", json={}).status_code == 409       # no module made yet
    assert client.post(f"/build/pack/api/ws/{wid}/deploy/plan", json={}).status_code == 409
    assert client.post(f"/build/pack/api/ws/{wid}/deploy/frobnicate", json={}).status_code == 400
    assert client.post(f"/build/pack/api/ws/{wid}/deploy/install", json=[1]).status_code == 400
    assert client.post(f"/build/pack/api/ws/no-such-ws/deploy/install", json={}).status_code == 404
    d = folder(client, wid)
    (d / "dist").mkdir()
    (d / "dist" / "my-bank-1.0.0.dmod").write_bytes(b"PK\x03\x04")
    (d / "result.json").write_text(json.dumps({"dmod": "my-bank-1.0.0.dmod", "name": "my-bank", "version": "1.0.0"}))
    modules = tmp_path / "modules"
    monkeypatch.setitem(client.app.state.settings._data.setdefault("builder", {}), "pack_designer_modules_dir", str(modules))
    r = client.post(f"/build/pack/api/ws/{wid}/deploy/install", json={"reason": "first"})
    assert r.status_code == 200 and (modules / "my-bank-1.0.0.dmod").is_file()
    calls = []

    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        return {"id": "plan-1"}

    async def upload(path, ident, data, headers=None, timeout=None):
        calls.append(("UPLOAD", path, data))
        return {"stored": True}
    monkeypatch.setattr(backend, "admin", admin)
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    r = client.post(f"/build/pack/api/ws/{wid}/deploy/plan", json={"reason": "first"})
    assert r.status_code == 200 and r.json()["plan"] == {"id": "plan-1"}
    assert ("UPLOAD", "/modules/store", b"PK\x03\x04") in calls
    assert ("POST", "/modules/deployments/plans", {"action": "install", "module": "my-bank", "version": "1.0.0", "reason": "first"}) in calls
