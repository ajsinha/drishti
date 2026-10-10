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

"""Admin → Connectors in the console: the page lists the connectors with origin, status and who uses them, the menus link to it, and the
same-origin calls reach the server (get, save with a version, delete, test, enable, reset, history, render, parse) with JSON bodies only;
a version travels as If-Match and confirm=true only when the caller sent it."""
from core.backend import BackendError

LIST = {
    "directory": "/srv/drishti/config/connectors", "watch": "WATCHING", "misnamed": ["Upper.yaml"], "fileProblems": {}, "deprecated": ["legacy"],
    "knownKinds": ["trade", "item"],
    "connectors": [
        {"name": "trading-lake", "origin": "file", "plugin": "delta", "kinds": ["trade"], "description": "Trading lake", "enabled": True, "state": "RUNNING",
         "health": "UP", "updated": "2026-10-06T09:00:00Z", "etag": "abc", "problems": [], "usedBy": [{"pack": "trading", "kinds": ["trade"]}], "template": "trading"},
        {"name": "legacy", "origin": "application", "plugin": "file", "kinds": [], "description": "", "enabled": True, "state": "FAILED", "health": None, "updated": None,
         "etag": None, "problems": ["no plugin named 'x'"], "usedBy": [], "template": None},
        {"name": "off-one", "origin": "file", "plugin": "jdbc", "kinds": [], "description": "", "enabled": False, "state": "DISABLED", "health": None, "updated": None,
         "etag": "def", "problems": [], "usedBy": [], "template": None}]}
PLUGINS = {"plugins": [{"name": "delta", "tls": False, "declared": True, "settings": [{"name": "root", "type": "path", "required": True, "default": None, "description": "Lake root", "secret": False, "group": "connection"}]}]}


def with_connectors(backend, monkeypatch, calls, fail=None):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, body, headers, params))
        if fail:
            raise fail
        if path == "/connectors" and method == "GET":
            return LIST
        if path == "/connectors/plugins":
            return PLUGINS
        if path == "/status":
            return {}
        return {"name": "trading-lake", "etag": "next"}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_lists_connectors_with_origin_status_and_users(client, backend, monkeypatch):
    with_connectors(backend, monkeypatch, [])
    page = client.get("/admin/connectors").text
    for text in ("Connectors", "trading-lake", "Trading lake", "running", "failed", "disabled", "data-new", "data-edit", "Reset to pack default",
                 "application", "legacy", "Upper.yaml", "WATCHING", "admin-connectors.js", "admin-connectors.css", "/help/connector-files",
                 'data-kinds="trade,item"', "/srv/drishti/config/connectors", "deprecated", 'role="tablist"', 'role="tab"', 'aria-selected="true"', 'aria-labelledby="connTitle"',
                 "Filter by name", "Any status", "Disable trading-lake", "Enable off-one", "Delete off-one"):
        assert text in page, text
    assert 'aria-current="page"' in page.split('href="/admin/connectors"')[1][:60]
    assert page.count('href="/admin/connectors"') >= 2                       # the Admin tabs and the top bar's Admin menu
    assert "Delete trading-lake" not in page                                  # a pack's connector is reset or disabled, never deleted


def test_the_page_is_closed_to_non_admins_and_shows_server_errors(client, backend, monkeypatch):
    with_connectors(backend, monkeypatch, [], fail=BackendError(403, "DRS-5002", "not an administrator"))
    assert client.get("/admin/connectors").status_code == 403


def test_calls_are_forwarded_with_the_version_and_the_confirmation(client, backend, monkeypatch):
    calls = []
    with_connectors(backend, monkeypatch, calls)
    draft = {"plugin": "delta", "enabled": True, "kinds": ["trade"], "settings": {"root": "/lake"}, "junk": "dropped"}
    assert client.get("/admin/connectors/api/trading-lake").status_code == 200
    assert client.put("/admin/connectors/api/trading-lake", json=draft, headers={"If-Match": "abc"}).status_code == 200
    assert client.put("/admin/connectors/api/trading-lake?confirm=true", json={"text": "plugin: delta\n"}).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/test", json=draft).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/test", json={}).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/enabled?confirm=true", json={"enabled": False}, headers={"If-Match": "abc"}).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/reset", headers={"If-Match": "abc"}).status_code == 200
    assert client.delete("/admin/connectors/api/trading-lake", headers={"If-Match": "abc"}).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/render", json=draft).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/parse", json={"text": "plugin: delta\n", "junk": 1}).status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/validate", json=draft).status_code == 200
    assert client.get("/admin/connectors/api/trading-lake/history/20261006T090000000").status_code == 200
    assert client.post("/admin/connectors/api/trading-lake/restore/20261006T090000000", headers={"If-Match": "abc"}).status_code == 200
    assert client.get("/admin/connectors/api/plugins").status_code == 200
    assert client.get("/admin/connectors/api/list").status_code == 200
    by = [(m, p, b, h, q) for m, p, b, h, q in calls if p.startswith("/connectors/")]
    put1 = by[1]
    assert put1[:2] == ("PUT", "/connectors/trading-lake") and "junk" not in put1[2] and put1[3] == {"If-Match": "abc"} and put1[4] == {}
    assert by[2][2] == {"text": "plugin: delta\n"} and not by[2][3] and by[2][4] == {"confirm": "true"}
    assert by[3][2]["settings"] == {"root": "/lake"} and by[4][2] is None                      # the saved file is tested when no draft is sent
    assert by[5][2] == {"enabled": False} and by[5][4] == {"confirm": "true"}
    assert by[9][2] == {"text": "plugin: delta\n"}                                              # only the text goes to parse
    assert by[7][0] == "DELETE" and by[7][3] == {"If-Match": "abc"}


def test_server_problems_come_back_with_their_code_and_status(client, backend, monkeypatch):
    with_connectors(backend, monkeypatch, [], fail=BackendError(409, "DRS-5032", "connector 'x' changed since you read it"))
    r = client.put("/admin/connectors/api/x", json={"text": "plugin: file\n"})
    assert r.status_code == 409 and r.json() == {"code": "DRS-5032", "detail": "connector 'x' changed since you read it"}


def test_bodies_must_be_json(client, backend, monkeypatch):
    with_connectors(backend, monkeypatch, [])
    r = client.put("/admin/connectors/api/x", content="plugin: file", headers={"Content-Type": "text/plain"})
    assert r.status_code == 415
