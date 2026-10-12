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

"""Admin → Artifacts in the console: the page lists packs, connector files and BI files with state and origin, has the Uninstalled tab and the
install cards, the menus link to it, and the same-origin calls reach the server (impact, disable, enable, uninstall, restore, delete, BI check
and upload) with JSON bodies only; a refusal comes back with its impact; the shared safe-uninstall dialog is on the Packs and Connectors pages."""
from core.backend import BackendError

ARTIFACTS = {
    "uninstalledDir": "/srv/drishti/data/uninstalled", "keepDays": 30,
    "artifacts": [
        {"kind": "pack", "subtype": "", "name": "trading", "title": "Trading", "state": "on", "origin": "shipped", "version": "1.1.0", "modified": "2026-10-06T09:00:00Z",
         "problems": [], "link": "/admin/packs/trading", "added": False},
        {"kind": "pack", "subtype": "", "name": "widgets", "title": "Widgets", "state": "problem", "origin": "archive", "version": "1.0.0", "modified": None,
         "problems": ["kind 'trade' is owned by pack trading"], "link": "/admin/packs/widgets", "added": True},
        {"kind": "connector", "subtype": "", "name": "trading-lake", "title": "delta connector", "state": "on", "origin": "template", "version": "", "modified": "2026-10-06T09:00:00Z",
         "problems": [], "link": "/admin/connectors/trading-lake", "hasFile": True},
        {"kind": "connector", "subtype": "", "name": "scratch", "title": "file connector", "state": "off", "origin": "ui", "version": "", "modified": None,
         "problems": [], "link": "/admin/connectors/scratch", "hasFile": True},
        {"kind": "bi", "subtype": "dataset", "name": "trades-basic", "title": "Trades, the basics", "state": "on", "origin": "shipped", "version": "v1",
         "modified": "2026-10-06T09:00:00Z", "problems": [], "link": "/bi/i/file:dataset:trades-basic", "hidden": False},
        {"kind": "bi", "subtype": "report", "name": "broken", "title": "broken", "state": "problem", "origin": "shipped", "version": "", "modified": None,
         "problems": ["not YAML"], "link": "/admin/bi", "hidden": False}]}
ARCHIVES = {"directory": "/srv/drishti/data/uninstalled", "keepDays": 30, "archives": [
    {"id": "20261006T090000-connector-scratch", "kind": "connector", "subtype": "", "name": "scratch", "area": "connectors", "rel": "scratch.yaml",
     "at": "2026-10-06T09:00:00Z", "by": "ann", "summary": "nothing depends on it", "bytes": 120}]}
IMPACT = {"kind": "pack", "subtype": "", "name": "trading", "total": 2, "blocked": True, "summary": "1 packs that extend it, 1 monitors", "templated": False, "templatePack": "",
          "notes": [], "groups": [{"category": "packs", "label": "Packs that extend it", "count": 1, "more": 0, "items": [{"name": "risk", "owner": "", "detail": "extends trading"}]}]}


def with_artifacts(backend, monkeypatch, calls, fail=None):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        calls.append((method, path, body, params))
        if fail and path.startswith("/artifacts"):
            raise fail
        if path == "/artifacts":
            return ARTIFACTS
        if path == "/artifacts/uninstalled" and method == "GET":
            return ARCHIVES
        if path == "/artifacts/impact":
            return IMPACT
        if path == "/status":
            return {}
        return {"ok": True, "note": "done", "also": []}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_page_lists_every_kind_with_state_origin_and_problems(client, backend, monkeypatch):
    with_artifacts(backend, monkeypatch, [])
    page = client.get("/admin/artifacts").text
    for text in ("Artifacts", "trading", "widgets", "trading-lake", "scratch", "trades-basic", "broken", "kind &#39;trade&#39; is owned by pack trading", "admin-artifacts.js",
                 "admin-artifacts.css", "artifact-uninstall.js", "/help/admin-artifacts", "Installed (6)", "Uninstalled (1)", "2 packs, 2 connectors, 2 BI files", "2 with a problem",
                 "Filter by name", "Any origin", "Place in config/bi", "Deploy a pack archive", "Open Connectors", "/srv/drishti/data/uninstalled", "30 days",
                 'aria-label="Uninstall trading"', 'aria-label="Disable trading"', 'aria-label="Enable scratch"', 'data-kind="bi" data-subtype="dataset" data-name="trades-basic"',
                 "data-uninstall-dialog", "data-typed-dialog", 'role="tablist"', "20261006T090000-connector-scratch", "Restore scratch", "Delete scratch permanently"):
        assert text in page, text
    assert 'aria-label="Uninstall trading-lake"' not in page                       # a pack-template connector is disabled or reset, never uninstalled
    assert 'href="/admin/artifacts"' in page and 'aria-current="page"' in page.split('href="/admin/artifacts"')[1][:60]
    assert 'href="/admin/artifacts"' in client.get("/admin/loads").text            # the Admin tabs and the top bar's Admin menu both link to it


def test_the_uninstalled_tab_can_be_opened_directly(client, backend, monkeypatch):
    with_artifacts(backend, monkeypatch, [])
    page = client.get("/admin/artifacts?tab=uninstalled").text
    assert 'data-tab="uninstalled"' in page and 'aria-selected="true" tabindex="-1"' not in page.split('id="artTabUninstalled"')[1][:200]


def test_the_page_is_closed_to_non_admins_and_shows_server_errors(client, backend, monkeypatch):
    with_artifacts(backend, monkeypatch, [], fail=BackendError(403, "DRS-5002", "not an administrator"))
    assert client.get("/admin/artifacts").status_code == 403


def test_actions_are_forwarded_with_only_the_fields_they_take(client, backend, monkeypatch):
    calls = []
    with_artifacts(backend, monkeypatch, calls)
    assert client.get("/admin/artifacts/api/impact?kind=pack&name=trading").json()["blocked"] is True
    assert client.post("/admin/artifacts/api/uninstall", json={"kind": "pack", "name": "trading", "cascade": True, "confirm": "trading", "junk": 1}).status_code == 200
    assert client.post("/admin/artifacts/api/disable", json={"kind": "bi", "subtype": "report", "name": "x"}).status_code == 200
    assert client.post("/admin/artifacts/api/enable", json={"kind": "connector", "name": "scratch"}).status_code == 200
    assert client.post("/admin/artifacts/api/uninstalled/20261006T090000-connector-scratch/restore", json={"replace": True, "confirm": "scratch", "junk": 1}).status_code == 200
    assert client.delete("/admin/artifacts/api/uninstalled/20261006T090000-connector-scratch?confirm=scratch").status_code == 200
    by = [c for c in calls if c[1].startswith("/artifacts") and c[1] not in ("/artifacts", "/artifacts/uninstalled")]
    assert by[0][:2] == ("GET", "/artifacts/impact") and by[0][3] == {"kind": "pack", "subtype": "", "name": "trading"}
    assert by[1][:3] == ("POST", "/artifacts/uninstall", {"kind": "pack", "name": "trading", "cascade": True, "confirm": "trading"})
    assert by[2][2] == {"kind": "bi", "subtype": "report", "name": "x"}
    assert by[4][:3] == ("POST", "/artifacts/uninstalled/20261006T090000-connector-scratch/restore", {"replace": True, "confirm": "scratch"})
    assert by[5][0] == "DELETE" and by[5][3] == {"confirm": "scratch"}


def test_an_unknown_action_is_a_404_and_a_body_must_be_json(client, backend, monkeypatch):
    with_artifacts(backend, monkeypatch, [])
    assert client.post("/admin/artifacts/api/explode", json={}).status_code == 404
    assert client.post("/admin/artifacts/api/uninstall", content="kind=pack", headers={"Content-Type": "text/plain"}).status_code == 415


def test_a_refusal_comes_back_with_its_impact_for_the_dialog(client, backend, monkeypatch):
    err = BackendError(409, "DRS-5041", "pack 'trading' is used by 1 packs that extend it")
    err.extra = {"impact": IMPACT}
    with_artifacts(backend, monkeypatch, [], fail=err)
    r = client.post("/admin/artifacts/api/uninstall", json={"kind": "pack", "name": "trading"})
    assert r.status_code == 409 and r.json()["code"] == "DRS-5041" and r.json()["impact"]["groups"][0]["items"][0]["name"] == "risk"


def test_a_bi_file_is_checked_and_placed_as_raw_bytes_with_its_name(client, backend, monkeypatch):
    sent = []

    async def upload(path, ident, data, headers, timeout=120.0):
        sent.append((path, data, headers))
        return {"valid": True, "name": "x", "subtype": "dataset", "problems": [], "warnings": [], "exists": False}
    monkeypatch.setattr(backend, "admin_upload", upload, raising=False)
    h = {"content-type": "application/octet-stream"}
    assert client.post("/admin/artifacts/api/bi/check?file=x.dataset.yaml", content=b"dataset: x\n", headers=h).json()["valid"] is True
    assert client.post("/admin/artifacts/api/bi?file=x.dataset.yaml&replace=true&confirm=x", content=b"dataset: x\n", headers=h).status_code == 200
    assert sent[0][0] == "/artifacts/bi/check?file=x.dataset.yaml" and sent[0][1] == b"dataset: x\n"
    assert sent[1][0] == "/artifacts/bi?file=x.dataset.yaml&replace=true&confirm=x"
    assert client.post("/admin/artifacts/api/bi/check?file=x.dataset.yaml", json={"a": 1}).status_code == 415
    assert client.post("/admin/artifacts/api/bi/check?file=x.dataset.yaml", content=b"", headers=h).status_code == 400


def test_the_packs_and_connectors_pages_carry_the_shared_safe_uninstall(client, backend, monkeypatch):
    from test_admin_connectors import with_connectors
    from test_admin_packs import with_admin
    with_admin(backend, monkeypatch, [])
    packs = client.get("/admin/packs").text
    for text in ("data-uninstall-dialog", 'data-kind="pack" data-name="trading"', "artifact-uninstall.js", "admin-artifacts.css"):
        assert text in packs, text
    with_connectors(backend, monkeypatch, [])
    conns = client.get("/admin/connectors").text
    assert "data-uninstall-dialog" in conns and 'data-kind="connector" data-name="off-one"' in conns and "artifact-uninstall.js" in conns
    assert 'data-kind="connector" data-name="trading-lake"' not in conns            # a pack-template connector has no Uninstall
