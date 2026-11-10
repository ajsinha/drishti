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
"""Admin → Data bindings in the console (docs/guides/ADMIN_GUIDE.md, "Data bindings"; mock-up A): one row per kind from the server's
GET /api/v1/admin/bindings, the banner when a required need is unbound, the deprecated kinds: lists, the proposals, and a proposal or a
decision passed to the server with its refusals."""
from core.backend import BackendError

STATUS = {
    "file": "/srv/drishti/config/bindings.yaml", "etag": "abc", "fileProblem": None,
    "counts": {"BOUND": 1, "AUTO": 0, "DEMO": 1, "STALE": 0, "MISMATCH": 0, "UNBOUND": 1, "COMPUTED": 1},
    "unboundRequired": ["scenario-set"], "off": {"VRES": "VRES needs scenario-set: no source is bound; ask your data administrator"},
    "kinds": [
        {"kind": "trade", "state": "BOUND", "origin": "SITE", "read": ["trade-endur", "trade-murex"], "neededBy": ["banking"], "required": True,
         "usedBy": ["TRD", "VRES"], "lastLoad": "2026-11-09", "reason": "", "binding": {"read": ["trade-endur", "trade-murex"], "union": True, "live": "trade-kafka"}},
        {"kind": "var-limit", "state": "DEMO", "origin": "DEMO", "read": ["demo"], "neededBy": ["banking"], "required": False, "usedBy": ["VLIM"],
         "lastLoad": None, "reason": "demo data", "binding": None},
        {"kind": "scenario-set", "state": "UNBOUND", "origin": "NONE", "read": [], "neededBy": ["banking"], "required": True, "usedBy": ["VRES"],
         "lastLoad": None, "reason": "no source is bound (hint: a lake table risk.scenario-set)", "binding": None},
        {"kind": "position", "state": "COMPUTED", "origin": "COMPUTED", "read": ["var-positions"], "neededBy": [], "required": False, "usedBy": [],
         "lastLoad": None, "reason": "computed by banking; not bindable", "binding": None}],
    "problems": ["var-limit is bound to 'nowhere', which no connector file defines"],
    "deprecated": ["trade-endur.yaml lists kinds: [trade]: read this release only; the binding in bindings.yaml is the one home, remove the list"],
    "connectors": ["risk-store", "trade-endur", "trade-murex"],
    "proposals": [{"id": "p1", "kind": "scenario-set", "binding": {"read": ["risk-store"]}, "was": None, "reason": "the bank's sets",
                   "proposedBy": "someone-else", "proposedAt": "2026-11-10T09:00:00Z", "state": "proposed"}],
}


def with_bindings(backend, monkeypatch, calls, status=STATUS):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        if path == "/bindings" and method == "GET":
            if status is None:
                raise BackendError(500, "DRS-5001", "the bindings could not be read")
            return status
        if path == "/bindings/proposals":
            if (body or {}).get("kind") == "position":
                raise BackendError(422, "DRS-1029", "position is computed by a module: it is not bound to a connector")
            return {"id": "p2", "kind": body["kind"], "state": "proposed"}
        if path == "/bindings/proposals/p1/approve":
            return {"id": "p1", "state": "approved"}
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_shows_every_kind_its_state_and_what_is_off(client, backend, monkeypatch):
    with_bindings(backend, monkeypatch, [])
    page = client.get("/admin/bindings").text
    for text in ("Data bindings", "trade-endur, trade-murex", "(union)", "trade-kafka", "site binding", "DEMO", "UNBOUND", "COMPUTED",
                 "1 required need unbound", "scenario-set", "VRES", "Deprecated: trade-endur.yaml lists kinds", "nowhere",
                 'data-binding-edit="trade"', 'data-proposal="p1"', 'data-proposal-act="approve"', "drishti_admin-bindings.js",
                 'scope="col"', "<caption", "/srv/drishti/config/bindings.yaml"):
        assert text in page, text
    assert 'data-binding-edit="position"' not in page                           # a computed kind is not bindable
    assert 'style="' not in page.split("data-bindings", 1)[1]                   # CSP: no inline styles


def test_the_page_says_when_the_server_cannot_give_the_bindings(client, backend, monkeypatch):
    with_bindings(backend, monkeypatch, [], status=None)
    page = client.get("/admin/bindings").text
    assert "The server did not give the bindings" in page and "the bindings could not be read" in page


def test_a_proposal_and_a_decision_reach_the_server_and_its_refusals_come_back(client, backend, monkeypatch):
    calls = []
    with_bindings(backend, monkeypatch, calls)
    r = client.post("/admin/api/bindings/propose", json={"kind": "scenario-set", "binding": {"read": ["risk-store"]}, "reason": "the bank's sets"})
    assert r.status_code == 200 and r.json()["state"] == "proposed"
    assert ("POST", "/bindings/proposals", {"kind": "scenario-set", "binding": {"read": ["risk-store"]}, "reason": "the bank's sets"}) in calls
    r = client.post("/admin/api/bindings/propose", json={"kind": "position", "binding": {"read": ["x"]}, "reason": "r"})
    assert r.status_code == 422 and "DRS-1029" in r.text
    r = client.post("/admin/api/bindings/approve", json={"id": "p1"})
    assert r.json()["state"] == "approved"
    assert client.post("/admin/api/bindings/nonsense", json={}).status_code == 400
