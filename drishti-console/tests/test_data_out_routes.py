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

"""Admin -> Data out's console part (routes/data_out_routes.py) against a stand-in server: the page opens in the view asked (the page by
default, the map with ?view=map) with the server's answer in it, the API passes the answer and Stop through, and a refusal keeps the
server's code."""
import json

from core.backend import BackendError

ANSWER = {"service": {"enabled": True, "running": True, "port": 18815, "tls": True, "mutualTls": False, "memory": {"used": 0, "cap": 4 << 30},
                      "problem": None, "forms": ["kind", "measure"], "batchRows": 65536},
          "nodes": [{"id": "drishti-main", "host": "10.20.4.11", "port": 18815, "self": True}],
          "live": [{"id": "f1", "person": "svc-var", "onBehalfOf": "alice", "token": "t1", "form": "measure", "request": "measure('var.scenarios', 'BANK')",
                    "part": 0, "stream": True, "started": "2026-11-03T09:00:00Z", "rows": 509, "bytes": 4096, "sequence": 3, "memory": 0,
                    "node": "drishti-main"}],
          "recent": [], "limits": {"default": {"rows": 50000000, "bytes": 8 << 30, "seconds": 600, "memory": 512 << 20}, "roles": {}}, "tokens": []}


def _fake(backend, monkeypatch, calls):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path))
        if path == "/status":
            return {}
        if path == "/data-out":
            return ANSWER
        if path.endswith("/f1/stop"):
            return {"stopped": "f1", "node": "drishti-main"}
        raise BackendError(404, "DRS-1001", "no live Flight read on this node")
    monkeypatch.setattr(backend, "admin", admin, raising=False)


def test_the_page_opens_in_the_view_asked_with_the_answer(client, backend, monkeypatch):
    calls = []
    _fake(backend, monkeypatch, calls)
    page = client.get("/admin/data-out")
    assert page.status_code == 200 and 'data-dout-view="page"' in page.text and "drishti_admin-data-out.js" in page.text
    assert "drishti-main" in page.text and "10.20.4.11" in page.text                                  # the answer, in the page's JSON
    assert 'data-dout-view="map"' in client.get("/admin/data-out?view=map").text
    assert ("GET", "/data-out") in calls


def test_the_api_and_stop_pass_through_and_a_refusal_keeps_its_code(client, backend, monkeypatch):
    calls = []
    _fake(backend, monkeypatch, calls)
    assert client.get("/admin/api/data-out").json()["live"][0]["sequence"] == 3
    ok = client.post("/admin/api/data-out/streams/f1/stop", content=json.dumps({}), headers={"Content-Type": "application/json"})
    assert ok.status_code == 200 and ok.json()["stopped"] == "f1" and ("POST", "/data-out/streams/f1/stop") in calls
    no = client.post("/admin/api/data-out/streams/f9/stop", content=json.dumps({}), headers={"Content-Type": "application/json"})
    assert no.status_code == 404 and no.json()["code"] == "DRS-1001"
