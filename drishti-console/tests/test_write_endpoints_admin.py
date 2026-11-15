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
"""The Admin write routes the console only relays to its server: each one passes its request on as the server's call (checked here by what the
stand-in server was asked), a malformed request is a 4xx of the console's own, and the server's refusal comes back with its status, never a 500."""
import threading
from types import SimpleNamespace

from core.backend import BackendError
from core.lake_maint import maintenance

OCTET = {"Content-Type": "application/octet-stream"}


def serving(backend, monkeypatch, calls, answer=None, refuse=None):
    """The stand-in server's admin calls, recorded as (method, path, body); `refuse` is the BackendError to raise instead of answering."""
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        if refuse is not None:
            raise refuse
        return {} if answer is None else answer

    async def upload(path, ident, data, headers=None, timeout=None):
        calls.append(("UPLOAD", path, data))
        if refuse is not None:
            raise refuse
        return {"stored": True}

    async def stream(path, ident, chunks, params=None, timeout=3600.0):
        got = b""
        async for c in chunks:
            got += c
        calls.append(("STREAM", path, got, params))
        if refuse is not None:
            raise refuse
        return {"rows": 1}

    monkeypatch.setattr(backend, "admin", admin)
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    monkeypatch.setattr(backend, "admin_stream_upload", stream, raising=False)


def test_pick_and_ready_are_passed_to_the_server_and_a_body_that_is_not_an_object_is_refused(client, backend, monkeypatch):
    calls = []
    serving(backend, monkeypatch, calls, {"state": "ready"})
    assert client.post("/admin/api/dataload/pick", json={"file": "trade.jsonl"}).json() == {"state": "ready"}
    assert ("POST", "/dataload/pick", {"file": "trade.jsonl"}) in calls
    assert client.post("/admin/api/dataload/ready/check", json={"mode": "append"}).status_code == 200
    assert ("POST", "/dataload/ready/check", {"mode": "append"}) in calls
    n = len(calls)
    assert client.post("/admin/api/dataload/pick", json=[1, 2]).status_code == 400
    assert client.post("/admin/api/dataload/ready/push", content="{not json", headers={"Content-Type": "application/json"}).status_code == 400
    assert client.post("/admin/api/dataload/ready/frobnicate", json={}).status_code == 404         # only preview, check, read, push
    assert len(calls) == n, "a refused request never reaches the server"


def test_a_server_refusal_keeps_its_status(client, backend, monkeypatch):
    serving(backend, monkeypatch, [], refuse=BackendError(409, "DRS-5011", "a load is running"))
    for path, body in (("/admin/api/dataload/pick", {"file": "x"}), ("/admin/api/dataload/ready/push", {"mode": "append"}),
                       ("/admin/api/registry/trading/rollback", None), ("/admin/api/tokens/t1/revoke", None), ("/admin/collab/api/mail-test", None)):
        r = client.post(path, json=body) if body is not None else client.post(path)
        assert r.status_code == 409, (path, r.status_code, r.text)
        assert "a load is running" in r.text


def test_an_upload_is_streamed_on_and_a_wrong_type_is_refused(client, backend, monkeypatch):
    calls = []
    serving(backend, monkeypatch, calls)
    assert client.post("/admin/api/dataload/upload?file=t.jsonl", content=b'{"a":1}\n', headers=OCTET).status_code == 200
    assert ("STREAM", "/dataload/upload", b'{"a":1}\n', {"file": "t.jsonl"}) in calls
    assert client.post("/admin/api/dataload/upload?file=t.jsonl", json={"a": 1}).status_code == 415
    assert client.post("/admin/api/packs/trading/data?kind=trade&date=2026-11-06", content=b'{"id":"A"}\n', headers=OCTET).status_code == 200
    assert ("STREAM", "/packs/trading/data", b'{"id":"A"}\n', {"kind": "trade", "date": "2026-11-06", "file": None}) in calls
    assert client.post("/admin/api/packs/trading/data", json={"a": 1}).status_code == 415
    serving(backend, monkeypatch, calls, refuse=BackendError(404, "DRS-5011", "no such upload"))
    assert client.delete("/admin/api/dataload/upload/up-9").status_code == 404
    serving(backend, monkeypatch, calls)
    assert client.delete("/admin/api/dataload/upload/up-1").status_code == 200
    assert ("DELETE", "/dataload/upload/up-1", None) in calls


def test_a_maintenance_run_is_cancelled_and_an_unknown_one_is_not_found(client):
    run = SimpleNamespace(cancel=threading.Event(), view=lambda: {"id": "r-1", "state": "running"})
    maintenance(client.app).runs["r-1"] = run
    try:
        r = client.post("/admin/api/lakes/maintain/runs/r-1/cancel", json={})
        assert r.status_code == 200 and r.json()["id"] == "r-1" and run.cancel.is_set()
        assert client.post("/admin/api/lakes/maintain/runs/nope/cancel", json={}).status_code == 404
        assert client.post("/admin/api/lakes/maintain/runs/r-1/cancel", json=[1]).status_code == 400
    finally:
        maintenance(client.app).runs.pop("r-1", None)


def test_rollback_revoke_and_mail_test_are_relayed(client, backend, monkeypatch):
    calls = []
    serving(backend, monkeypatch, calls, {"rolledBack": "1.0.0"})
    assert client.post("/admin/api/registry/trading/rollback").json() == {"rolledBack": "1.0.0"}
    assert ("POST", "/registry/trading/rollback", None) in calls
    assert client.post("/admin/api/tokens/tok-1/revoke").json() == {"ok": True}
    assert ("DELETE", "/tokens/tok-1", None) in calls
    assert client.post("/admin/collab/api/mail-test").status_code == 200
    assert ("POST", "/collab/mail-test", None) in calls


def test_a_connector_preview_sends_the_draft_and_the_kind_and_refuses_a_body_that_is_not_an_object(client, backend, monkeypatch):
    calls = []
    serving(backend, monkeypatch, calls, {"ok": True, "rows": []})
    draft = {"plugin": "file", "settings": {"root": "/x"}, "kinds": ["item"], "kind": "item", "date": "2026-10-05", "limit": 5}
    assert client.post("/admin/connectors/api/api-docs/preview", json=draft).json() == {"ok": True, "rows": []}
    method, path, body = calls[-1]
    assert (method, path) == ("POST", "/connectors/api-docs/preview")
    assert body["kind"] == "item" and body["date"] == "2026-10-05" and body["limit"] == 5 and body["plugin"] == "file"
    assert client.post("/admin/connectors/api/api-docs/preview", json=[draft]).status_code == 400
    assert client.post("/admin/connectors/api/api-docs/preview", content="{nope", headers={"Content-Type": "application/json"}).status_code == 400
    serving(backend, monkeypatch, calls, refuse=BackendError(400, "DRS-5001", "say which kind to preview"))
    assert client.post("/admin/connectors/api/api-docs/preview", json={"plugin": "file"}).status_code == 400


def test_a_module_package_is_stored_and_an_empty_one_is_refused(client, backend, monkeypatch):
    calls = []
    serving(backend, monkeypatch, calls)
    assert client.post("/admin/modules/api/upload", content=b"PK\x03\x04dmod", headers=OCTET).status_code == 200
    assert ("UPLOAD", "/modules/store", b"PK\x03\x04dmod") in calls
    n = len(calls)
    r = client.post("/admin/modules/api/upload", content=b"", headers=OCTET)
    assert r.status_code == 400 and "choose a .dmod" in r.text
    assert len(calls) == n
    serving(backend, monkeypatch, calls, refuse=BackendError(422, "DRS-1031", "not a module"))
    assert client.post("/admin/modules/api/upload", content=b"junk", headers=OCTET).status_code == 422


def test_a_hidden_comment_is_unhidden_and_a_bad_id_never_reaches_the_server(client, backend, monkeypatch):
    seen = []

    async def send(method, path, ident=None, **kw):
        seen.append((method, path))
        return {"hidden": False}

    monkeypatch.setattr(backend, "_send", send)
    r = client.post("/api/comment/c-12/unhide")
    assert r.status_code == 200 and r.json() == {"hidden": False}
    assert ("POST", "/admin/collab/comments/c-12/unhide") in seen
    n = len(seen)
    assert client.post("/api/comment/a%20b%3Bdrop/unhide").status_code == 400
    assert len(seen) == n
