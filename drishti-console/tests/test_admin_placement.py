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

"""Admin → Cluster → Modules (option A): the node × part matrix from GET /api/v1/admin/cluster/modules, every cell in words, the singleton
owner starred, the plan form as drishti-inputs' Form, and plans and decisions passed to the server's placement endpoints unchanged."""
from tests.test_admin_net import with_net

MODULES = {
    "self": "n-a", "file": "/srv/drishti/config/placement.yaml", "version": 3, "fileProblem": None,
    "nodes": [{"id": "n-a", "tags": ["web"], "up": True, "self": True}, {"id": "n-b", "tags": ["heavy"], "up": True, "self": False},
              {"id": "n-c", "tags": [], "up": False, "self": False}],
    "parts": [
        {"module": "sample", "version": "1.0.0", "part": "compute", "class": "heavy", "memoryMb": 6144, "maxNodes": 1, "placed": ["n-b"], "up": ["n-b"],
         "owner": None, "status": "placed", "cells": {"n-a": "NOT_PLACED", "n-b": "ACTIVE", "n-c": "NOT_PLACED"}},
        {"module": "sample", "version": "1.0.0", "part": "feeds", "class": "feed", "memoryMb": 0, "maxNodes": 0, "placed": ["n-a", "n-c"], "up": ["n-a"],
         "owner": "n-a", "status": "placed", "cells": {"n-a": "ACTIVE", "n-b": "NOT_PLACED", "n-c": "DOWN"}},
        {"module": "banking", "version": "built-in", "part": "compute", "class": "heavy", "memoryMb": 0, "maxNodes": 0, "placed": ["n-a", "n-b", "n-c"],
         "up": ["n-a", "n-b"], "owner": None, "status": "in-process", "cells": {"n-a": "IN_PROCESS", "n-b": "IN_PROCESS", "n-c": "IN_PROCESS"}}],
    "problems": ["genomics is not placed: it runs on no node"],
    "placement": "format: 1\nversion: 3\n",
    "plans": [{"id": "pl-1", "reason": "move compute", "proposedBy": "someone-else", "proposedAt": "2026-11-10T10:00:00Z", "warnings": []}]}


def with_modules(backend, monkeypatch, calls):
    with_net(backend, monkeypatch, [])

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, body))
        if path == "/cluster/modules":
            return MODULES
        return {"state": "applied"}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_modules_tab_shows_the_matrix_in_words(client, backend, monkeypatch):
    with_modules(backend, monkeypatch, [])
    page = client.get("/admin/net?tab=modules").text
    pane = page.split('id="netPaneModules"')[1].split("</section>")[0]
    for s in ["data-placement-matrix", "sample.compute", "heavy · max 1 · 6,144 MB", "not placed", "active", "down", "in process", "★",
              "genomics is not placed", "built in: every node", "data-placement-fields", "Plan (a second administrator applies)", "pl-1",
              'data-plan-act="apply"', "/help/cluster#19-module-placement"]:
        assert s in pane, s
    assert 'aria-selected="true"' in page.split('id="netTabModules"')[1][:200]
    assert "drishti_admin-placement.js" in page


def test_plans_and_decisions_reach_the_server(client, backend, monkeypatch):
    calls = []
    with_modules(backend, monkeypatch, calls)
    assert client.post("/admin/net/api/placement/plan", json={"placement": "modules: {}", "reason": "why"}).status_code == 200
    assert client.post("/admin/net/api/placement/apply", json={"id": "pl-1", "comment": "ok"}).status_code == 200
    assert client.post("/admin/net/api/placement/nonsense", json={}).status_code == 400
    assert ("POST", "/cluster/placement/plans", {"placement": "modules: {}", "reason": "why"}) in calls
    assert ("POST", "/cluster/placement/plans/pl-1/apply", {"comment": "ok"}) in calls
