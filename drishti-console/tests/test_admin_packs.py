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

"""Admin → Packs in the console: Deploy archive, history with Roll back, and the Data source panel (same-origin JSON that the server checks)."""
from core.backend import BackendError

HISTORY = {"history": [{"at": "2026-10-05T10:00:00Z", "action": "deploy", "pack": "trading", "version": "1.1.0", "previous": "1.0.0", "by": "ann",
                        "detail": "from trading-1.1.0.tar.gz"}],
           "kept": {"trading": [{"version": "1.0.0", "keptAt": "2026-10-05T10:00:00Z"}, {"version": "shipped", "keptAt": None, "shippedVersion": "0.9.0"}]}}


def with_admin(backend, monkeypatch, calls, extra=None):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body, params))
        if extra:
            hit = extra(method, path, body, params)
            if hit is not None:
                return hit
        if path == "/packs":
            return [{"name": "trading", "title": "Trading", "description": "", "version": "1.1.0", "loaded": True, "added": False, "enabled": True,
                     "extends": [], "requiredBy": [], "kinds": ["trade"], "connectors": ["lake"], "mnemonics": ["TRD"], "dataSourceOverridden": True}]
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        if path == "/packs/history":
            return HISTORY
        return {"note": "ok", "restarting": False}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_offers_deploy_history_rollback_and_the_data_source(client, backend, monkeypatch):
    with_admin(backend, monkeypatch, [])
    page = client.get("/admin/packs").text
    for text in ("Deploy an archive", "data-deploy", 'data-max-mb="50"', "pack only, never data", "History and roll back", "data-rollback", "Roll back to this",
                 "shipped with the server (0.9.0)", "data-datasource", "data source overridden", "data-ds-dialog", "admin-packs.js", "admin-packs.css",
                 "from trading-1.1.0.tar.gz", "Test connection", "Reset to the pack"):
        assert text in page, text
    assert 'aria-labelledby="dsTitle"' in page and 'role="status"' in page                      # the dialog is named; messages are announced


def test_an_upload_is_sent_on_as_raw_bytes_with_its_headers(client, backend, monkeypatch):
    sent = {}

    async def upload(path, ident, data, headers, timeout=120.0):
        sent.update(path=path, data=data, headers=headers)
        return {"ok": True, "uploadId": "u1", "checks": [], "pack": "trading", "version": "1.1.0"}
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    r = client.post("/admin/api/packs/deploy", content=b"ARCHIVE-BYTES", headers={"content-type": "application/octet-stream", "X-Drishti-Filename": "t.tar.gz",
                                                                                   "X-Drishti-Signature": "c2ln", "X-Drishti-Publisher": "acme"})
    assert r.status_code == 200 and r.json()["uploadId"] == "u1"
    assert sent["path"] == "/packs/deploy" and sent["data"] == b"ARCHIVE-BYTES"
    assert sent["headers"] == {"X-Drishti-Filename": "t.tar.gz", "X-Drishti-Signature": "c2ln", "X-Drishti-Publisher": "acme"}


def test_an_upload_must_be_octet_stream_non_empty_and_within_the_limit(client, backend, monkeypatch):
    async def upload(path, ident, data, headers, timeout=120.0):
        raise AssertionError("must not reach the server")
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    assert client.post("/admin/api/packs/deploy", json={"a": 1}).status_code == 415
    assert client.post("/admin/api/packs/deploy", content=b"", headers={"content-type": "application/octet-stream"}).status_code == 400
    client.app.state.pack_upload_limit = 1024
    try:
        r = client.post("/admin/api/packs/deploy", content=b"x" * 2048, headers={"content-type": "application/octet-stream"})
    finally:
        client.app.state.pack_upload_limit = 50 * 1048576
    assert r.status_code == 413 and r.json()["code"] == "DRS-5005" and "too large" in r.json()["detail"]


def test_a_server_refusal_reaches_the_page_as_a_problem(client, backend, monkeypatch):
    async def upload(path, ident, data, headers, timeout=120.0):
        raise BackendError(413, "DRS-5005", "the archive is larger than 50 MB")
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    r = client.post("/admin/api/packs/deploy", content=b"x", headers={"content-type": "application/octet-stream"})
    assert r.status_code == 413 and "larger than 50 MB" in r.json()["detail"]


def test_confirm_discard_history_and_rollback_are_forwarded(client, backend, monkeypatch):
    calls = []
    with_admin(backend, monkeypatch, calls)
    assert client.post("/admin/api/packs/deploy/u1", json={"acceptBreaking": True}).status_code == 200
    assert client.post("/admin/api/packs/deploy/u2", json={}).status_code == 200
    assert client.delete("/admin/api/packs/deploy/u1").status_code == 200
    assert client.get("/admin/api/packs/history", params={"pack": "trading"}).json()["history"][0]["action"] == "deploy"
    assert client.post("/admin/api/packs/trading/rollback", json={"version": "1.0.0"}).status_code == 200
    assert client.post("/admin/api/packs/trading/rollback", json={}).status_code == 200
    by = {(m, p): (b, q) for m, p, b, q in calls}
    assert by[("POST", "/packs/deploy/u1")][1]["acceptBreaking"] == "true"
    assert by[("POST", "/packs/deploy/u2")][1]["acceptBreaking"] == "false"
    assert ("DELETE", "/packs/deploy/u1") in by
    assert by[("GET", "/packs/history")][1] == {"limit": 100, "pack": "trading"}
    rollbacks = [q for m, p, b, q in calls if p == "/packs/trading/rollback"]
    assert rollbacks == [{"version": "1.0.0"}, {}]


def test_the_data_source_routes_are_forwarded_and_the_pack_switcher_forgets(client, backend, monkeypatch):
    calls = []
    with_admin(backend, monkeypatch, calls)
    forgot = []
    monkeypatch.setattr(client.app.state.packs, "forget_all", lambda: forgot.append(1))
    assert client.get("/admin/api/packs/trading/datasource").status_code == 200
    edit = {"connectors": {"lake": {"settings": {"root": "/mnt/lake"}}}}
    assert client.put("/admin/api/packs/trading/datasource", json=edit).status_code == 200
    assert client.post("/admin/api/packs/trading/datasource/test", json={"connector": "lake", "connectors": edit["connectors"]}).status_code == 200
    assert client.post("/admin/api/packs/trading/datasource/test", json={}).status_code == 200
    assert client.delete("/admin/api/packs/trading/datasource", params={"connector": "lake"}).status_code == 200
    assert client.delete("/admin/api/packs/trading/datasource").status_code == 200
    seen = [(m, p, b, q) for m, p, b, q in calls if "datasource" in p]
    assert seen[0][:2] == ("GET", "/packs/trading/datasource")
    assert seen[1][2] == edit and seen[1][0] == "PUT"
    assert seen[2][2] == {"connector": "lake", "connectors": edit["connectors"]}
    assert seen[3][2] == {"connector": None, "connectors": None}
    assert seen[4][3] == {"connector": "lake"} and seen[5][3] == {}
    assert len(forgot) == 3                                                      # save and the two resets change what users see; get and test do not


def test_data_source_problems_are_shown_not_swallowed(client, backend, monkeypatch):
    def extra(method, path, body, params):
        if method == "PUT":
            raise BackendError(400, "DRS-5001", "the data source is not valid: lake.settings.password: a credential is never stored here")
    with_admin(backend, monkeypatch, [], extra)
    r = client.put("/admin/api/packs/trading/datasource", json={"connectors": {"lake": {"settings": {"password": "x"}}}})
    assert r.status_code == 400 and "never stored here" in r.json()["detail"]


def test_deploy_routes_do_not_shadow_the_existing_pack_actions(client, backend, monkeypatch):
    calls = []
    with_admin(backend, monkeypatch, calls)
    client.post("/admin/api/packs/trading", json={"enabled": False})            # the switch
    client.post("/admin/api/packs/genomics/load")                               # the load
    assert [(m, p) for m, p, b, q in calls] == [("PUT", "/packs/trading"), ("POST", "/packs/genomics/load")]
