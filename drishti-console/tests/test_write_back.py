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

"""Write-back, the console's half (docs/architecture/WRITE_BACK.md): /api/changes passes each call to the server as the signed-in person
(the review sheet, submit, approve, a record's history) and hands back the server's refusal with its code; Review -> Changes lists the
changes waiting for an approver with Approve and Reject for one the server says the reader may decide; Admin -> Edits shows the store,
the lanes, the warnings and each connector's writes with its switch; a page with a form loads write-back.js; the live connection
carries the edit events."""
from pathlib import Path

from core.backend import BackendError

WEB = Path(__file__).resolve().parents[1] / "web"
CHG = "chg-01JB3Z5V9Q8R7S6T5U4V3W2X1Y"


def _sender(calls, answers):
    async def send(method, path, ident=None, **kw):
        calls.append((method, path, kw.get("json")))
        for (m, p), a in answers.items():
            if m == method and path.startswith(p):
                if isinstance(a, BackendError):
                    raise a
                return a
        return {}
    return send


def test_changes_pass_through_as_the_person_with_the_servers_refusal(client, backend, monkeypatch):
    calls = []
    off = BackendError(403, "DRS-9301", "editing is off on this server")
    monkeypatch.setattr(backend, "_send", _sender(calls, {
        ("GET", "/changes/settings"): {"enabled": True, "mayEdit": True, "approver": False, "kinds": ["trade"]},
        ("POST", "/changes/preview"): {"ok": True, "changes": [{"kind": "trade", "entity": "MX-1", "diff": [], "approvals": 1}]},
        ("POST", "/changes/" + CHG + "/approve"): off,
        ("POST", "/changes"): {"changes": [{"id": CHG, "state": "awaiting", "needed": 1}]},
    }))
    assert client.get("/api/changes/settings").json()["mayEdit"] is True
    assert client.post("/api/changes/preview", json={"draft": "d-0123456789ab"}).json()["ok"] is True
    sent = client.post("/api/changes", json={"draft": "d-0123456789ab", "reason": "typo"})
    assert sent.status_code == 202 and sent.json()["changes"][0]["state"] == "awaiting"
    assert ("POST", "/changes", {"draft": "d-0123456789ab", "reason": "typo"}) in calls
    refused = client.post("/api/changes/" + CHG + "/approve", json={})
    assert refused.status_code == 403 and refused.json()["code"] == "DRS-9301"
    assert client.post("/api/changes/nope/approve", json={}).status_code == 400
    assert client.post("/api/changes/" + CHG + "/delete", json={}).status_code == 400
    client.get("/api/entities/trade/MX 1/history")
    assert calls[-1][1] == "/entities/trade/MX%201/history"
    assert client.post("/api/changes", content="x", headers={"Content-Type": "text/plain"}).status_code == 415


def test_review_changes_lists_what_waits_with_approve_and_reject_for_an_approver(client, backend, monkeypatch):
    calls = []
    change = {"id": CHG, "kind": "trade", "entity": "MX-1", "target": "overlay", "by": "asha", "at": "2026-10-25T10:00:00Z", "reason": "typo",
              "items": [{"path": "$.NOTIONAL", "from": 1000, "to": 1250}], "state": "awaiting", "needed": 1, "approvals": [], "mayDecide": True}
    monkeypatch.setattr(backend, "_send", _sender(calls, {
        ("GET", "/changes/settings"): {"enabled": True, "mayEdit": True, "approver": True},
        ("GET", "/changes?"): {"changes": [change]},
    }))
    page = client.get("/review/changes")
    assert page.status_code == 200
    assert "NOTIONAL: 1000 → <b>1250</b>" in page.text and "data-approve=\"" + CHG in page.text and "data-reject=\"" + CHG in page.text
    assert ("GET", "/changes?limit=200&state=awaiting", None) in calls
    got = client.post("/review/changes/" + CHG + "/reject", data={"note": "wrong ticket"}, follow_redirects=False)
    assert got.status_code == 303 and calls[-1] == ("POST", "/changes/" + CHG + "/reject", {"reason": "wrong ticket"})
    monkeypatch.setattr(backend, "_send", _sender(calls, {("GET", "/changes/settings"): {"enabled": False}, ("GET", "/changes?"): {"changes": []}}))
    off = client.get("/review/changes").text
    assert "data-edit-off" in off and "data-none" in off


def test_admin_edits_shows_the_store_lanes_warnings_and_connector_writes(client, backend, monkeypatch):
    calls = []
    status = {"enabled": True, "origin": "config/edit/edit.yaml", "mode": "single", "store": "sqlite", "why": "auto: this node is alone", "node": "local",
              "lanes": [{"lane": 3, "owner": "local", "epoch": 2, "leased": True, "depth": 1, "mine": True, "oldestWaitMs": 12}],
              "unconfirmed": [], "warnings": ["lane 3: its oldest change has waited 3000 ms (lane-warn 2000 ms)"],
              "connectors": [{"connector": "trade-murex", "plugin": "jdbc", "commands": ["amend-notional"], "problems": {}, "on": False}],
              "editableKinds": ["trade"]}
    monkeypatch.setattr(backend, "_send", _sender(calls, {("GET", "/admin/edits/overlays"): {"overlays": []}, ("GET", "/admin/edits"): status}))
    page = client.get("/admin/edits")
    assert page.status_code == 200
    assert "auto: this node is alone" in page.text and "data-warning" in page.text and 'data-writes-switch="trade-murex"' in page.text
    got = client.post("/admin/edits/connectors/trade-murex", data={"on": "true", "reason": "pilot approved"}, follow_redirects=False)
    assert got.status_code == 303 and calls[-1] == ("POST", "/admin/edits/connectors/trade-murex", {"on": True, "reason": "pilot approved"})


def test_a_page_with_a_form_loads_write_back_and_the_live_connection_carries_edits(client, backend, monkeypatch):
    real = backend.view

    async def view(kind, id_, user, controls=None, panels=None):
        vm = await real(kind, id_, user, controls, panels)
        vm["panels"][0]["form"] = {"edit": "beside", "key": "trade_id", "fields": [{"id": "trade_id"}], "rules": [], "hints": [], "rows": [],
                                   "save": {"target": "overlay", "approval": 1, "reason": True}}
        return vm
    monkeypatch.setattr(backend, "view", view)
    page = client.get("/v/trade/IRS-47102").text
    assert "write-back.js" in page and page.index("write-back.js") < page.index("record-forms.js")
    hub = (WEB / "static" / "js" / "live-hub.js").read_text(encoding="utf-8")
    assert "'edit'" in hub and "'switches'" in hub
    forms = (WEB / "static" / "js" / "record-forms.js").read_text(encoding="utf-8")
    assert "Sending to the source arrives in 2.8.0" not in forms and "data-fm-submit" in forms
    alerts = (WEB / "static" / "js" / "alerts.js").read_text(encoding="utf-8")
    assert "edit: onEdit" in alerts and "drishti:edit" in alerts
