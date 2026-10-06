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

"""Admin → Embedding in the console (Drishti Elements, step 10): the page lists the host applications with their usage, marks the ones
declared in configuration read-only, the menus link to it, and the same-origin calls reach the server's registry (create, rotate,
disable, enable, delete) with JSON bodies only; a secret comes back with no-store."""
from core.backend import BackendError

APPS = [
    {"id": "client-crm", "name": "Client CRM", "contact": "", "origins": ["https://crm.bank.example"], "kinds": ["trade"], "scopes": ["embed:view"],
     "subjectTypes": ["jwt"], "subjectAudiences": [], "jwks": None, "hasSecret": True, "tokenSeconds": 300, "callsPerMinute": 600,
     "userCallsPerMinute": 60, "enabled": True, "createdAt": "2026-10-06T09:00:00Z", "createdBy": "root", "updatedAt": "2026-10-06T09:00:00Z",
     "updatedBy": "root", "lastUsedAt": None, "fromConfig": False},
    {"id": "gitops", "name": "Declared in config", "contact": "", "origins": ["https://gitops.bank.example"], "kinds": [], "scopes": ["embed:view", "embed:about"],
     "subjectTypes": ["jwt"], "subjectAudiences": [], "jwks": "{\"keys\":[]}", "hasSecret": False, "tokenSeconds": 300, "callsPerMinute": 600,
     "userCallsPerMinute": 60, "enabled": False, "createdAt": "2026-10-06T09:00:00Z", "createdBy": "config", "updatedAt": "2026-10-06T09:00:00Z",
     "updatedBy": "config", "lastUsedAt": None, "fromConfig": True},
]
USAGE = {"since": "2026-10-06T08:00:00Z", "enabled": True,
         "settings": {"scopes": ["embed:about", "embed:view"], "tokenMaxSeconds": 900, "callsPerMinute": 600, "userCallsPerMinute": 60, "allowWildcardOrigins": False},
         "apps": {"client-crm": {"tokensIssued": 12, "calls": 3456, "refusals": 2, "refusalsByCode": {"DRS-8002": 2}, "streamsOpen": 4, "lastUsedAt": "2026-10-06T10:15:00Z"},
                  "gitops": {"tokensIssued": 0, "calls": 0, "refusals": 0, "refusalsByCode": {}, "streamsOpen": 0, "lastUsedAt": None},
                  "(unknown)": {"tokensIssued": 0, "calls": 0, "refusals": 3, "refusalsByCode": {"DRS-8003": 3}, "streamsOpen": 0, "lastUsedAt": None}}}


def with_embed(backend, monkeypatch, calls):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        if path == "/embed/apps" and method == "GET":
            return APPS
        if path == "/embed/usage":
            return USAGE
        if path == "/embed/apps" and method == "POST":
            if body.get("id") == "gitops":
                raise BackendError(409, "DRS-2006", "'gitops' is declared in configuration (drishti.embed.apps): change it there and restart")
            return {"app": {**APPS[0], "id": body["id"], "name": body["name"]}, "secret": "s3cret-shown-once"}
        if path.endswith("/rotate-secret"):
            return {"app": APPS[0], "secret": "new-secret-shown-once"}
        if path.endswith("/disable") or path.endswith("/enable"):
            return {**APPS[0], "enabled": path.endswith("/enable")}
        if path.startswith("/embed/apps/") and method == "DELETE":
            return None
        if path == "/status":
            return {}
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_lists_applications_usage_and_marks_config_apps(client, backend, monkeypatch):
    with_embed(backend, monkeypatch, [])
    page = client.get("/admin/embedding").text
    for text in ("Embedding", "Client CRM", "client-crm", "https://crm.bank.example", "client secret", "public key", "3,456", "12</span> tokens", "DRS-8002: 2",
                 "from config", "read-only", "disabled", "enabled", "embed:view", "every kind the user may open", "New application", "data-new-dialog",
                 "data-secret-dialog", "data-delete-dialog", "admin-embedding.js", "admin-embedding.css", "/help/user-management#embedding-host-applications",
                 "3 requests named no registered application", "DRS-8003: 3"):
        assert text in page, text
    assert 'aria-labelledby="embNewTitle"' in page and '<caption class="visually-hidden">' in page and 'scope="col"' in page
    gitops = page.split('data-app="gitops"')[1].split("</tr>")[0]
    assert "data-rotate" not in gitops and "data-delete" not in gitops and "data-switch" not in gitops      # read-only: no way to change it here
    crm = page.split('data-app="client-crm"')[1].split("</tr>")[0]
    assert "data-rotate" in crm and 'data-switch="disable"' in crm and "data-delete" in crm


def test_the_scopes_on_offer_come_from_the_server(client, backend, monkeypatch):
    with_embed(backend, monkeypatch, [])
    page = client.get("/admin/embedding").text
    assert 'name="scope" value="embed:about"' in page and 'name="scope" value="embed:view"' in page


def test_the_admin_menus_link_to_it(client, backend, monkeypatch):
    with_embed(backend, monkeypatch, [])
    page = client.get("/admin/embedding").text
    assert page.count('href="/admin/embedding"') >= 2            # the Admin tabs and the top bar's Admin menu
    assert 'aria-current="page"' in page.split('href="/admin/embedding"')[1][:60]
    assert 'href="/admin/embedding"' in client.get("/admin/loads").text


def test_register_sends_only_the_draft_and_shows_the_secret_with_no_store(client, backend, monkeypatch):
    calls = []
    with_embed(backend, monkeypatch, calls)
    r = client.post("/admin/embedding/api/apps", json={"id": "new-app", "name": "New", "origins": ["https://n.example"], "scopes": ["embed:view"],
                                                       "secret": True, "createdBy": "mallory", "enabled": False})
    assert r.status_code == 201 and r.json()["secret"] == "s3cret-shown-once"
    assert "no-store" in r.headers["cache-control"]
    sent = [c for c in calls if c[0] == "POST"][0][2]
    assert "createdBy" not in sent and "enabled" not in sent and sent["id"] == "new-app"
    bad = client.post("/admin/embedding/api/apps", json={"id": "gitops", "name": "x", "origins": ["https://x.example"], "scopes": ["embed:view"]})
    assert bad.status_code == 409 and "configuration" in bad.json()["detail"]


def test_rotate_disable_enable_and_delete_reach_the_server(client, backend, monkeypatch):
    calls = []
    with_embed(backend, monkeypatch, calls)
    rot = client.post("/admin/embedding/api/apps/client-crm/rotate", json={"graceSeconds": 0})
    assert rot.json()["secret"] == "new-secret-shown-once" and "no-store" in rot.headers["cache-control"]
    assert ("POST", "/embed/apps/client-crm/rotate-secret", {"graceSeconds": 0}) in calls
    assert client.post("/admin/embedding/api/apps/client-crm/disable").json()["enabled"] is False
    assert client.post("/admin/embedding/api/apps/client-crm/enable").json()["enabled"] is True
    assert client.post("/admin/embedding/api/apps/client-crm/explode").status_code == 404
    assert client.delete("/admin/embedding/api/apps/client-crm").json() == {"deleted": "client-crm"}
    assert ("DELETE", "/embed/apps/client-crm", None) in calls


def test_bodies_must_be_json(client, backend, monkeypatch):
    with_embed(backend, monkeypatch, [])
    r = client.post("/admin/embedding/api/apps", content='{"id":"x"}', headers={"Content-Type": "text/plain"})
    assert r.status_code == 415
