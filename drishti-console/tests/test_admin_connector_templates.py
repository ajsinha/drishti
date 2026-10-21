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

"""Admin → Connectors → Template updates: the page lists every connector drifted from its pack's template with its missing, changed and obsolete
parts and both texts side by side; the buttons reach the server with the connector's version; the Admin tabs carry the drift count."""
from core.backend import BackendError

DRIFT = {
    "mode": "notify", "count": 1, "additive": 1, "notices": [{"at": "2026-10-21T09:00:00Z", "name": "civic-store", "what": "added from pack world-economy's template: kinds [office-holder]"}],
    "drifts": [{"name": "macro-store", "pack": "world-economy", "missingKinds": ["region-economy", "country-group"], "newSettings": ["layout.region-economy"],
                "conflicting": [{"key": "root", "connector": "/lake/edited", "template": "/lake/macro"}], "obsoleteKinds": ["central-bank-decision"],
                "additive": True, "etag": "e1", "text": "plugin: delta\nkinds: [macro-indicator, central-bank-decision]\n",
                "templateText": "plugin: delta\nkinds: [macro-indicator, region-economy, country-group]\n"}]}


def with_backend(backend, monkeypatch, calls, fail=None, drift=DRIFT):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, headers))
        if fail:
            raise fail
        if path == "/connectors/template-updates" and method == "GET":
            return drift
        if path == "/status":
            return {"connectorTemplateDrift": drift["count"]}
        if path.endswith("/fix-all"):
            return {"fixed": ["macro-store"]}
        return {"name": "macro-store", "etag": "e2", "changed": []}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_lists_each_drift_side_by_side_with_its_buttons(client, backend, monkeypatch):
    with_backend(backend, monkeypatch, [])
    page = client.get("/admin/connectors/template-updates").text
    for text in ("Template updates", "macro-store", "world-economy", "region-economy, country-group", "layout.region-economy", "central-bank-decision",
                 "/lake/edited", "Pack template", "Add missing kinds", "Regenerate from template", "Remove obsolete kinds", "Fix all additive (1)",
                 'data-etag="e1"', "civic-store", "office-holder", "admin-connector-templates.js", "data-drift-badge", "notify"):
        assert text in page, text


def test_nothing_drifted_says_so(client, backend, monkeypatch):
    with_backend(backend, monkeypatch, [], drift={"mode": "additive", "count": 0, "additive": 0, "notices": [], "drifts": []})
    page = client.get("/admin/connectors/template-updates").text
    assert "Every connector matches its pack's template." in page and "data-drift-badge" not in page


def test_the_buttons_reach_the_server_with_the_version(client, backend, monkeypatch):
    calls = []
    with_backend(backend, monkeypatch, calls)
    for act in ("add-missing", "regenerate", "remove-obsolete"):
        assert client.post(f"/admin/connectors/api/template-updates/macro-store/{act}", headers={"If-Match": "e1"}).status_code == 200
        assert ("POST", f"/connectors/template-updates/macro-store/{act}", {"If-Match": "e1"}) in calls
    assert client.post("/admin/connectors/api/template-updates/fix-all").json() == {"fixed": ["macro-store"]}
    assert client.post("/admin/connectors/api/template-updates/macro-store/drop-table").status_code == 404


def test_the_page_is_closed_to_non_admins_and_shows_server_errors(client, backend, monkeypatch):
    with_backend(backend, monkeypatch, [], fail=BackendError(403, "DRS-5002", "not an administrator"))
    assert client.get("/admin/connectors/template-updates").status_code == 403
    assert client.post("/admin/connectors/api/template-updates/fix-all").status_code == 403
