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
                 "Filter by name", "Any status", "Disable trading-lake", "Enable off-one", "Uninstall off-one"):
        assert text in page, text
    assert 'aria-current="page"' in page.split('href="/admin/connectors"')[1][:60]
    assert page.count('href="/admin/connectors"') >= 2                       # the Admin tabs and the top bar's Admin menu
    assert "Delete trading-lake" not in page                                  # a pack's connector is reset or disabled, never deleted
    assert "data-delete" not in page and "Delete off-one" not in page         # one way out for a file connector: Uninstall (archived, restorable from Artifacts)


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


def test_the_detail_page_shows_the_connection_pool_and_a_leak_warning(client, backend, monkeypatch):
    """Pool size, in use, idle, waiting and the longest current borrow; an embedded DuckDB file says it is not pooled."""
    pool = {"type": "jdbc", "exempt": False, "max": 20, "open": 6, "inUse": 4, "idle": 2, "waiting": 0, "longestBorrowMillis": 2300, "leaksDetected": 1, "timeouts": 0}
    detail = {**LIST["connectors"][0], "pool": pool, "settings": {}, "directory": "/srv/drishti/config/connectors", "history": [], "text": "plugin: jdbc\n", "tls": None}

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        return {} if path == "/status" else detail
    monkeypatch.setattr(backend, "admin", admin)
    import re
    flat = " ".join(re.sub(r"<[^>]+>", " ", client.get("/admin/connectors/trading-lake").text).replace(" ,", ",").replace(" .", ".").split())
    assert "Connection pool 4 of 20 in use 2 idle, 6 open, 0 waiting, longest current borrow 2.3 s" in flat
    assert "1 borrow held past pool-leak-threshold" in flat
    detail["pool"] = {**pool, "type": "duckdb", "exempt": True, "max": 4, "inUse": 1, "waiting": 0}
    flat = " ".join(re.sub(r"<[^>]+>", " ", client.get("/admin/connectors/trading-lake").text).replace(" ,", ",").replace(" .", ".").split())
    assert "embedded, in-process file: not pooled. 1 read running of 4 allowed at once, 0 waiting." in flat
    detail["pool"] = None
    assert "Connection pool" not in client.get("/admin/connectors/trading-lake").text


def test_the_badge_uses_the_servers_words_so_a_down_connector_is_never_running(client, backend, monkeypatch):
    rows = [dict(LIST["connectors"][0], level="down", label="down, retrying", health="DOWN: no directory /lake"),
            dict(LIST["connectors"][2], level="off", label="disabled"),
            dict(LIST["connectors"][0], name="note-files", level="warn", label="running, with a warning", state="RUNNING",
                 health="UP (0 rows served for 2026-10-12: 'note' holds 2099-01-05, none on or before 2026-10-12)")]

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors":
            return dict(LIST, connectors=rows)
        return {"plugins": []} if path == "/connectors/plugins" else {}
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/connectors").text
    assert "down, retrying" in page and "running, with a warning" in page and "disabled" in page
    assert "DOWN: no directory /lake" in page and "0 rows served for 2026-10-12" in page          # the hint is text on the row, not a tooltip
    assert 'data-level="down"' in page and 'data-level="warn"' in page
    assert "admin-watch.js" in page and 'data-watch="/admin/connectors/api/stamp"' in page


def test_the_stamp_is_a_short_digest_that_moves_when_a_connector_changes_but_not_for_a_health_sentence(client, backend, monkeypatch):
    state = {"rows": [dict(LIST["connectors"][0], level="ok", label="running", health="UP")]}

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        return dict(LIST, connectors=state["rows"])
    monkeypatch.setattr(backend, "admin", admin)
    first = client.get("/admin/connectors/api/stamp").json()
    assert first["rows"][0]["name"] == "trading-lake" and len(first["stamp"]) == 40
    state["rows"] = [dict(state["rows"][0], health="UP (ping 12 ms)")]
    assert client.get("/admin/connectors/api/stamp").json()["stamp"] == first["stamp"]            # a changing figure is not a change
    state["rows"] = [dict(state["rows"][0], level="down", label="down, retrying")]
    assert client.get("/admin/connectors/api/stamp").json()["stamp"] != first["stamp"]


def test_the_history_and_the_editor_say_what_the_buttons_are_for(client, backend, monkeypatch):
    with_connectors(backend, monkeypatch, [])
    page = client.get("/admin/connectors").text
    assert 'data-edit-msg' in page and page.index("data-edit-msg") > page.index("adm-dlg-actions ds-actions")       # the message sits in the sticky bar beside Save
    assert 'data-zone=' in page and "connDescHelp" in page                                                         # history times in the page's zone; Description has its own help
