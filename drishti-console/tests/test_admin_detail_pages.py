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

"""Admin -> Connectors / Packs: one page for each name. The list names are links; the detail pages show the summary, state, links to the
related packs and connectors (with Create for a missing one), settings with credentials masked, the file, history and the actions of the list.
Administrators only. Everything is read from the server's admin API (stand-in here)."""
from types import SimpleNamespace

from core.backend import BackendError

CONNECTOR = {
    "name": "trading-lake", "origin": "file", "plugin": "delta", "kinds": ["trade"], "description": "Trading lake", "enabled": True, "state": "RUNNING",
    "health": "UP", "lastUpdate": "2026-10-06T09:30:00Z", "updated": "2026-10-06T09:00:00Z", "etag": "abc", "problems": [],
    "retry": {"attempt": 3, "nextRetryInSeconds": 12, "lastError": "connection refused", "permanent": False},
    "usedBy": [{"pack": "trading", "kinds": ["trade"]}], "template": "trading", "directory": "/srv/c", "file": "/srv/c/trading-lake.yaml",
    "settings": {"root": "/lake", "password": "***"}, "text": "plugin: delta\nkinds: [trade]\n",
    "tls": {"enabled": True, "verifyHostname": True, "protocols": ["TLSv1.3"], "warnings": [],
            "certificates": [{"source": "tls.cert-file", "subject": "CN=drishti", "notBefore": "2026-01-01T00:00:00Z", "notAfter": "2026-10-20T00:00:00Z",
                              "daysLeft": 12, "expired": False}]},
    "proxy": {"enabled": True, "source": "site", "sourceLabel": "site default", "url": "http://proxy.corp:3128", "username": "svc", "password": "***",
              "noProxy": [".corp.example"], "target": "lake.example", "viaProxy": True},
    "history": [{"id": "20261006T080000000", "at": "2026-10-06T08:00:00Z", "bytes": 40}]}
PACKS = [
    {"name": "trading", "title": "Trading", "description": "Trades and books", "version": "1.2.0", "loaded": True, "added": True, "enabled": True, "sample": True,
     "extends": ["core"], "requiredBy": ["risk"], "kinds": ["trade"], "connectors": ["trading-lake", "gone-conn"], "mnemonics": ["TRD"], "problem": None,
     "retry": None, "updatedAt": "2026-10-05T10:00:00Z", "updatedBy": "ann"},
    {"name": "core", "title": "Core", "description": "", "version": "1.0.0", "loaded": True, "added": False, "enabled": True, "extends": [], "requiredBy": ["trading"],
     "kinds": [], "connectors": [], "mnemonics": []},
    {"name": "broken", "title": "Broken", "description": "", "version": "0.1.0", "loaded": False, "enabled": False, "problem": "pack.yaml: kinds must be a list",
     "retry": {"attempt": 2, "nextRetryInSeconds": 30, "lastError": "bad yaml", "permanent": True}}]
DETAIL = {"name": "trading", "code": "TRD", "routes": {"trade": "trading-lake"}, "glossaryTerms": 4,
          "kinds": [{"kind": "trade", "mnemonic": "TRD", "label": "Trade", "declaredForm": True, "formFields": ["mtm", "book"], "about": True, "glossaryTerms": 4}],
          "sutras": [{"name": "trade-view", "version": 2, "versions": [1, 2], "kind": "trade"}]}
SOURCES = {"directory": "/srv/c", "missing": ["gone-conn"], "connectors": [
    {"name": "trading-lake", "defined": True, "state": "RUNNING", "kinds": ["trade"], "editUrl": "/admin/connectors?name=trading-lake"},
    {"name": "gone-conn", "defined": False, "state": "NOT_CONFIGURED", "kinds": [], "createUrl": "/admin/connectors?new=gone-conn"}]}
HISTORY = {"history": [{"at": "2026-10-05T10:00:00Z", "action": "deploy", "pack": "trading", "version": "1.2.0", "previous": "1.1.0", "by": "ann", "detail": "from t.tar.gz"}],
           "kept": {"trading": [{"version": "1.1.0", "keptAt": "2026-10-05T10:00:00Z"}]}}


def serve(backend, monkeypatch, fail=None):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if fail:
            raise fail
        if path == "/connectors/trading-lake":
            return CONNECTOR
        if path.startswith("/connectors/"):
            raise BackendError(404, "DRS-5033", "no connector named 'nope'")
        if path == "/packs":
            return PACKS
        if path == "/packs/trading/detail":
            return DETAIL
        if path == "/packs/trading/datasource":
            return SOURCES
        if path == "/packs/history":
            return HISTORY
        if path == "/loads/trading/expectations":
            return {"expectations": [{"kind": "trade", "state": "late"}]}
        if path == "/loads/trading":
            return {"loads": [{"kind": "trade", "businessDate": "2026-10-05", "receivedAt": "2026-10-05T18:00:00Z"}]}
        if path == "/status":
            return {}
        raise BackendError(404, "DRS-1001", "not here: " + path)

    async def overview(name, ident=None):
        return {"kinds": [{"kind": "trade", "example": "IRS-48213"}]}
    monkeypatch.setattr(backend, "admin", admin)
    monkeypatch.setattr(backend, "pack_overview", overview)


def test_the_connector_page_shows_state_tls_packs_settings_file_and_history(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/connectors/trading-lake").text
    for text in ("trading-lake", "delta", "running", "UP", "next retry in", "(attempt 3)", "Retry now", "connection refused", "CN=drishti", "expires 2026-10-20 (12 days)",
                 'href="/admin/packs/trading"', "***", "plugin: delta", "Test connection", "Disable trading-lake", "Reset trading-lake to the default of pack trading",
                 "Compare or restore", "20261006T080000000", "/help/connector-files", "admin-connectors.js", "data-edit-dialog", 'aria-current="page"'):
        assert text in page, text
    assert "Delete trading-lake" not in page                                           # a pack's connector is reset or disabled, never deleted


def test_the_connector_page_shows_the_effective_proxy_masked_and_where_it_came_from(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/connectors/trading-lake").text
    for text in ("data-proxy", "http://proxy.corp:3128", "from the site default", "svc", ".corp.example"):
        assert text in page, text
    assert "s3cr3t" not in page and 'password <span class="mono">***</span>' in page
    monkeypatch.setitem(CONNECTOR, "proxy", {"enabled": False, "source": "disabled", "sourceLabel": "disabled by the connector"})
    assert "proxy: none" in client.get("/admin/connectors/trading-lake").text
    monkeypatch.setitem(CONNECTOR, "proxy", {"enabled": True, "source": "connector", "sourceLabel": "connector", "url": "http://p:1", "target": "intranet",
                                             "viaProxy": False, "note": "the proxy is configured but intranet is on the no-proxy list, so this connector goes direct"})
    assert "goes direct" in client.get("/admin/connectors/trading-lake").text
    monkeypatch.setitem(CONNECTOR, "proxy", {"error": "proxy.url is missing: give the proxy as http://host:port"})
    assert "proxy.url is missing" in client.get("/admin/connectors/trading-lake").text
    monkeypatch.setitem(CONNECTOR, "proxy", None)
    assert "data-proxy" not in client.get("/admin/connectors/trading-lake").text         # a plugin that does not speak HTTP has no proxy row


def test_a_connector_that_does_not_exist_is_a_404_and_non_admins_are_refused(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    assert client.get("/admin/connectors/nope").status_code == 404
    monkeypatch.setattr("routes.connector_routes.ident", lambda request: SimpleNamespace(is_admin=False))
    assert client.get("/admin/connectors/trading-lake").status_code == 403


def test_the_pack_page_shows_status_links_kinds_sutras_connectors_loads_and_history(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/packs/trading").text
    for text in ("Trading", "Sample", "1.2.0", "TRD", "ann", 'href="/admin/packs/core"', 'href="/admin/packs/risk"', 'href="/v/trade/IRS-48213"',
                 'href="/form/trading?kind=trade"', "2 declared", "trade-view", "v1, v2", "/studio?sutra=trade-view", 'href="/admin/connectors/trading-lake"',
                 'href="/admin/connectors?new=gone-conn"', "missing", "1 of 1 kind", "4 glossary terms", "/admin/packs/trading/loads", "late", "trade 2026-10-05",
                 "Roll back to this", "from t.tar.gz", "Switch off", "Unload", "/help/packs"):
        assert text in page, text
    assert "disabled" in page                                                          # Switch off is disabled: risk needs it


def test_pack_page_for_a_refused_pack_names_the_reason_and_offers_nothing_loaded(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/packs/broken").text
    assert "refused" in page and "pack.yaml: kinds must be a list" in page and "next retry in" in page and 'data-load="load"' in page
    assert "Kinds and mnemonics" not in page
    assert client.get("/admin/packs/missing-pack").status_code == 404


def test_pack_page_is_closed_to_non_admins(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    monkeypatch.setattr("routes.admin_routes.ident", lambda request: SimpleNamespace(is_admin=False))
    assert client.get("/admin/packs/trading").status_code == 403


def test_the_lists_link_every_name(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors" and method == "GET":
            return {"connectors": [{**CONNECTOR}], "directory": "/srv/c", "watch": "WATCHING", "misnamed": [], "fileProblems": {}, "deprecated": [], "knownKinds": []}
        if path == "/connectors/plugins":
            return {"plugins": []}
        if path == "/packs":
            return PACKS
        if path == "/packs/history":
            return {"history": [], "kept": {}}
        if path == "/registry":
            return {"configured": False, "packs": []}
        return {}
    monkeypatch.setattr(backend, "admin", admin)
    conns = client.get("/admin/connectors").text
    assert 'href="/admin/connectors/trading-lake"' in conns and 'href="/admin/packs/trading"' in conns
    packs = client.get("/admin/packs").text
    for href in ("/admin/packs/trading", "/admin/packs/core"):
        assert f'href="{href}"' in packs, href


TYPE_README = {"connector": "delta", "source": "classpath",
               "meta": {"connector": "delta", "category": "plugin", "summary": "Reads Delta Lake tables.", "capabilities": ["fetch", "search", "history"],
                        "auth": "Cloud credentials", "licence": "Delta Kernel (Apache-2.0)", "windows": "Works"},
               "markdown": "# The delta connector\n\n## Data structure\n\n```sql\nCREATE TABLE t (id string)\n```\n\n| Setting | Type |\n|---|---|\n| `root` | path |\n\n"
                           "<img src=x onerror=alert(1)> [Delta](../../docs/connectors/DELTA_CONNECTOR.md)\n"}


def test_the_pack_page_shows_its_readme_with_data_structures_sources_and_safe_links(client, backend, monkeypatch):
    """The README standard: the pack's README (front matter facts and the rendered markdown, its tables wrapped), raw HTML shown as text,
    only http(s) links live, a repository document the help centre holds opened there."""
    serve(backend, monkeypatch)
    page = client.get("/admin/packs/trading").text
    for text in ("data-pack-readme", "README: what the pack holds", "Trades in 125 products.", "internal only", "tools/packgen/readme.py trading",
                 "<code>LEGS[].CASH_FLOWS[]</code>", '<div class="tbl-wrap"><table class="tbl help-tbl">', "/admin/api/packs/trading/readme",
                 'href="https://example.com"', 'href="/help/packs"'):
        assert text in page, text
    assert "<script>alert(1)</script>" not in page and "&lt;script&gt;" in page
    assert 'href="javascript:' not in page
    assert client.get("/admin/api/packs/trading/readme").json()["meta"]["kinds"] == ["trade"]
    assert client.get("/admin/api/packs/core/readme").status_code == 404


def test_a_pack_without_a_readme_has_no_readme_section(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    page = client.get("/admin/packs/core").text
    assert "data-pack-readme" not in page and "Summary" in page


def test_the_connector_page_shows_its_type_readme(client, backend, monkeypatch):
    serve(backend, monkeypatch)
    inner = backend.admin

    async def admin(method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/connectors/types/delta/readme":
            return TYPE_README
        return await inner(method, path, ident, body, timeout, headers, **params)
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/connectors/trading-lake").text
    for text in ('data-type-readme="delta"', "Reads Delta Lake tables.", "Delta Kernel (Apache-2.0)", "CREATE TABLE t", "<code>root</code>",
                 "/admin/connectors/api/types/delta/readme"):
        assert text in page, text
    assert "<img src=x" not in page
    assert client.get("/admin/connectors/api/types/delta/readme").json()["meta"]["category"] == "plugin"
    assert client.get("/admin/connectors/api/types/nope/readme").status_code == 404
