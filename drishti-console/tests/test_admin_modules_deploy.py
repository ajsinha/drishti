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
"""Admin → Modules (mock-up A) and Deploy (mock-up C) over GET /api/v1/admin/modules/deployments, the plan and decisions passed to the server
unchanged, the removed-module page (410 with the notice, 404 when off) and alert rules marked "needs module X"."""
from tests.test_admin_net import with_net

PLAN = {"id": "dpl-1", "action": "install", "module": "greeter", "version": "1.0.0", "previous": None, "state": "proposed", "fingerprint": "f00",
        "reason": "first", "proposedBy": "someone-else", "proposedAt": "2026-11-10T10:00:00Z", "warnings": [],
        "nodes": [{"node": "n-a", "placed": True, "now": "-", "after": "1.0.0", "action": "stage 1.0.0, activate with the others", "status": "planned"},
                  {"node": "n-c", "placed": False, "now": "-", "after": "-", "action": "nothing (not placed): forwards its requests", "status": "planned"}]}
OVERVIEW = {"node": "n-a", "fingerprint": "f00", "nodes": [{"id": "n-a", "tags": ["web"], "up": True}, {"id": "n-c", "tags": [], "up": True}],
            "modules": [{"id": "greeter", "version": "1.0.0", "state": "active", "title": "Test greeter", "prefix": "greeter",
                         "claims": ["API prefix /api/v1/greeter", "function GRT"], "placed": ["n-a"],
                         "versions": [{"id": "greeter", "version": "1.0.0", "sha": "ab" * 32, "bytes": 2048, "checks": ["C1 No kernel internals: passed"],
                                       "parts": ["api"], "storedBy": "admin"}]}],
            "waiting": [PLAN], "deployments": [PLAN]}


def with_modules(backend, monkeypatch, calls):
    with_net(backend, monkeypatch, [])

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, body))
        if path == "/modules/deployments":
            return OVERVIEW
        if path == "/modules/deployments/dpl-1":
            return PLAN
        return {**PLAN, "state": "applied"}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_modules_page_lists_modules_waiting_plans_and_the_detail(client, backend, monkeypatch):
    with_modules(backend, monkeypatch, [])
    page = client.get("/admin/modules").text
    for s in ["data-modules-table", 'data-module="greeter"', "LOADED", "WAITING FOR A SECOND ADMINISTRATOR", "/admin/modules/deploy?plan=dpl-1",
              'data-module-detail="greeter"', "function GRT", "C1 No kernel internals: passed", "Upgrade…", "Remove…", "Install module…"]:
        assert s in page, s


def test_the_deploy_page_shows_the_plan_per_node_and_the_decision(client, backend, monkeypatch):
    with_modules(backend, monkeypatch, [])
    page = client.get("/admin/modules/deploy?plan=dpl-1").text
    for s in ['data-plan-node="n-c"', "not placed", "receive nothing", 'data-module-act="apply"', "waiting for a second administrator",
              "data-module-upload-fields", "drishti_admin-modules.js"]:
        assert s in page, s
    fresh = client.get("/admin/modules/deploy?module=greeter&action=remove").text
    assert "data-module-plan-fields" in fresh and "Plan (changes nothing)" in fresh


def test_plans_and_decisions_reach_the_server(client, backend, monkeypatch):
    calls = []
    with_modules(backend, monkeypatch, calls)
    body = {"action": "remove", "module": "greeter", "version": "", "reason": "gone"}
    assert client.post("/admin/modules/api/plan", json=body).status_code == 200
    assert client.post("/admin/modules/api/apply", json={"id": "dpl-1"}).status_code == 200
    assert client.post("/admin/modules/api/nonsense", json={}).status_code == 400
    assert ("POST", "/modules/deployments/plans", body) in calls
    assert ("POST", "/modules/deployments/dpl-1/apply", {"comment": ""}) in calls


def test_a_removed_modules_page_answers_410_with_the_notice(client, backend, monkeypatch):
    async def send(method, path, ident, **kw):
        assert path == "/modules/route"
        if "greeter" in kw["params"]["path"]:
            return {"status": 410, "module": "greeter", "title": "Test greeter", "state": "removed", "by": "bob", "at": "2026-11-10T10:42:00Z",
                    "deployment": "dpl-9"}
        return {"status": 404, "module": None}
    monkeypatch.setattr(backend, "_send", send)
    r = client.get("/m/greeter/greet/asha")
    assert r.status_code == 410
    assert "no longer installed" in r.text and "needs module greeter" in r.text
    assert r.headers["cache-control"] == "no-store"
    assert client.get("/m/other/x").status_code == 404
