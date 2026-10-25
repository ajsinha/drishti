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

"""Admin → Compute in the console (docs/admin/COMPUTE.md): the page shows the switch, pools with their workers here and on other nodes,
queues, latency, the requests running, approval requests (with their items; no decision buttons on one's own request: four eyes),
environments and recent requests; decisions, builds, tests and cancels reach the server's compute API as JSON."""
from core.backend import BackendError

OVERVIEW = {
    "enabled": True, "node": "drishti-main", "origin": "config/compute/compute.yaml", "python": "python3", "root": "/srv/drishti/data/compute",
    "build": "manual", "me": "admin1",
    "pools": [{"name": "risk", "node": "drishti-main", "mode": "fork", "environment": "locks/risk.lock",
               "engines": ["drishti_compute.samples.vectors:VectorEngine"], "workersMin": 2, "workersMax": 32, "busy": 3, "idle": 5, "starting": 0,
               "queued": 4, "queues": {"interactive": 1, "event": 0, "batch": 3}, "batchSlots": 8, "served": 12345, "crashed": 1, "recycled": 7,
               "failures": {"timeout": 2}, "queueMs": {"p50": 0, "p95": 12}, "runMs": {"p50": 8, "p95": 41},
               "workers": [{"id": "drishti-main/risk#3", "state": "busy", "request": "req-abc", "user": "asha", "class": "interactive",
                            "kind": "sum-vectors", "forMs": 1200}],
               "ready": "ready", "peers": [{"node": "drishti-b", "workers": 64, "busy": 10, "queued": 0}]}],
    "recent": [{"id": "req-old", "user": "ben", "pool": "risk", "kind": "sum", "class": "batch", "outcome": "timeout", "queuedMs": 3, "runMs": 30000,
                "worker": "drishti-main/risk#1"}],
    "environments": [{"id": "f9dc94904d9d8554", "built": True, "python": "3.12", "platform": "linux-amd64", "packages": ["numpy==2.1.3"],
                      "builtAt": "2026-10-24T10:00:00Z", "builtBy": "admin2", "bytes": 52428800}],
    "approvals": [
        {"id": "cr-1", "subject": "connector:var-engine", "state": "pending", "requestedBy": "dev1", "requestedAt": "2026-10-24T09:00:00Z",
         "items": [{"kind": "python", "name": "numpy", "version": "2.1.3", "hash": "a" * 64, "source": "shipped", "nativeCode": True},
                   {"kind": "java", "name": "org.quantlib:quantlib-java", "version": "1.36.0", "hash": "b" * 64, "source": "shipped", "nativeCode": True}]},
        {"id": "cr-2", "subject": "pool:risk", "state": "pending", "requestedBy": "admin1", "requestedAt": "2026-10-24T09:05:00Z",
         "items": [{"kind": "python", "name": "polars", "version": "1.12.0", "hash": "c" * 64, "source": "bank-pypi", "nativeCode": True}]},
        {"id": "cr-3", "subject": "pool:risk", "state": "approved", "requestedBy": "compute.yaml", "requestedAt": "2026-10-23T09:00:00Z",
         "decidedBy": "admin2", "reason": "reviewed", "items": []},
    ],
}


def with_compute(backend, monkeypatch, calls, fail=None):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        if fail and path.endswith(fail[0]):
            raise BackendError(fail[1], fail[2], fail[3])
        if path == "/compute":
            return OVERVIEW
        if path == "/status":
            return {}
        if path.endswith("/test"):
            return {"ok": True, "worker": "drishti-main/risk#2", "rows": [], "queuedMs": 0, "runMs": 6, "totalMs": 9}
        if path.endswith("/build"):
            return {"id": "f9dc94904d9d8554", "reused": False, "log": []}
        return {"id": path.split("/")[3], "state": "approved"}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_shows_pools_workers_queues_approvals_and_environments(client, backend, monkeypatch):
    with_compute(backend, monkeypatch, [])
    page = client.get("/admin/compute").text
    for text in ("Compute", "drishti-main", "risk", "32 workers", "drishti-b", "interactive", "12,345", "1 crashed", "7 recycled", "timeout 2",
                 "p95 <span class=\"mono\">41</span>", "req-abc", "asha", "sum-vectors", "connector:var-engine", "1 python, 1 java", "org.quantlib:quantlib-java",
                 "numpy==2.1.3", "50.0 MB", "req-old", "2 approval requests waiting", "admin-compute.js", "admin-compute.css", 'data-decide-dialog',
                 'aria-current="page"', "/admin/compute"):
        assert text in page, text
    assert '<caption class="visually-hidden">' in page and 'scope="col"' in page
    theirs = page.split('data-request="cr-1"')[1].split('data-request=')[0]
    assert 'data-decide="approve"' in theirs and 'data-decide="reject"' in theirs
    mine = page.split('data-request="cr-2"')[1].split('data-request=')[0]
    assert "data-decide" not in mine and "you asked" in mine                     # four eyes: no buttons on one's own request
    approved = page.split('data-request="cr-3"')[1].split('data-envs')[0]
    assert 'data-decide="revoke"' in approved


def test_compute_off_says_so(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        return {**OVERVIEW, "enabled": False, "pools": [], "approvals": []} if path == "/compute" else {}
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/compute").text
    assert "Compute is switched off" in page and "compute.enabled" in page and "No pool is configured" in page


def test_decisions_builds_tests_and_cancels_reach_the_server(client, backend, monkeypatch):
    calls = []
    with_compute(backend, monkeypatch, calls)
    assert client.post("/admin/compute/api/requests/cr-1/approve", json={"reason": "scanned"}).status_code == 200
    assert ("POST", "/compute/requests/cr-1/approve", {"reason": "scanned"}) in calls
    assert client.post("/admin/compute/api/requests/cr-1/delete", json={}).status_code == 404
    assert client.post("/admin/compute/api/pools/risk/test", json={}).json()["worker"] == "drishti-main/risk#2"
    assert client.post("/admin/compute/api/pools/risk/build", json={}).json()["id"] == "f9dc94904d9d8554"
    client.post("/admin/compute/api/running/req-abc/cancel", json={})
    assert ("POST", "/compute/running/req-abc/cancel", {}) in calls


def test_a_refusal_comes_back_with_its_code(client, backend, monkeypatch):
    with_compute(backend, monkeypatch, [], fail=("/approve", 409, "DRS-9202", "four eyes: admin1 asked for cr-2 and cannot approve it"))
    r = client.post("/admin/compute/api/requests/cr-2/approve", json={"reason": "mine"})
    assert r.status_code == 409 and r.json()["code"] == "DRS-9202" and "four eyes" in r.json()["detail"]
