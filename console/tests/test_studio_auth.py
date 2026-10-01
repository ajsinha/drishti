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

"""Sutra Studio pages and the sign-in flow."""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.app import create_app  # noqa: E402
from core.auth import COOKIE, mint_token  # noqa: E402
from core.config import Settings, load_settings  # noqa: E402


def test_studio_page_and_preview(client):
    r = client.get("/studio")
    assert r.status_code == 200 and "data-studio" in r.text and "irs-vanilla v3" in r.text
    assert "codemirror.js" in r.text and "cm-yaml.js" in r.text
    ok = client.post("/studio/preview", json={"yaml": "sutra: x", "kind": "trade", "id": "IRS-48213"})
    assert ok.status_code == 200 and 'id="p-cashflows"' in ok.text and "Sutra irs-vanilla v3 + inference" in ok.text
    bad = client.post("/studio/preview", json={"yaml": "BROKEN", "kind": "trade", "id": "IRS-48213"})
    assert bad.status_code == 422 and bad.json()["problems"][0]["location"]["line"] == 4
    assert client.get("/studio/inferred/trade/IRS-47102", params={"name": "irs-plain"}).text.startswith("sutra: irs-plain")


def test_studio_previews_and_infers_from_pasted_json(client):
    page = client.get("/studio").text
    assert "Sample JSON" in page and "data-load-json" in page and "data-use-json" in page
    r = client.post("/studio/preview", json={"yaml": "sutra: x", "kind": "trade", "id": "P-1", "document": {"tradeId": "P-1", "mtm": 5}})
    assert r.status_code == 200 and "P-1" in r.text
    y = client.post("/studio/inferred", json={"kind": "trade", "id": "P-1", "name": "pasted", "document": {"tradeId": "P-1", "mtm": 5}})
    assert y.text.startswith("sutra: pasted") and "mtm,tradeId" in y.text


def test_tokens_are_hs256_and_verifiable():
    import base64
    import hashlib
    import hmac
    import json

    t = mint_token("ash", ["author"], "k" * 32, 60)
    h, c, s = t.split(".")
    pad = lambda x: x + "=" * (-len(x) % 4)  # noqa: E731
    assert json.loads(base64.urlsafe_b64decode(pad(h)))["alg"] == "HS256"
    assert json.loads(base64.urlsafe_b64decode(pad(c)))["sub"] == "ash"
    expect = hmac.new(b"k" * 32, f"{h}.{c}".encode(), hashlib.sha256).digest()
    assert base64.urlsafe_b64decode(pad(s)) == expect


def _secure_app(backend):
    settings = load_settings(CONSOLE / "config")
    data = settings.as_dict()
    data["auth"] = {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40,
                    "token_ttl_seconds": 60, "session_hours": 1, "secure_cookie": False}
    app = create_app(Settings(data))
    app.state.backend = backend
    return app


FORM = {"Content-Type": "application/x-www-form-urlencoded"}


def test_sign_in_goes_through_the_server(backend):
    app = _secure_app(backend)
    c = TestClient(app)
    assert c.get("/t", follow_redirects=False).headers["location"].startswith("/login")
    assert c.get("/admin/users", follow_redirects=False).status_code == 303
    assert c.get("/api/suggest").status_code == 401
    assert c.get("/").status_code == 200
    bad = c.post("/login", content="user=drishti-dev-admin&password=nope&next=/t", headers=FORM)
    assert bad.status_code == 401 and "wrong password" in bad.text
    r = c.post("/login", content="user=drishti-dev-admin&password=drishti-dev-admin123&next=//evil.example",
               follow_redirects=False, headers=FORM)
    assert r.status_code == 303 and r.headers["location"] == "/t"
    home = c.get("/t").text
    assert "Sign out" in home and 'href="/admin/users"' in home
    ident = app.state.auth.identity(c.cookies.get(COOKIE))
    assert ident.user == "drishti-dev-admin" and ident.is_admin and ident.headers()["Authorization"].startswith("Bearer ")
    payload, sig = c.cookies.get(COOKIE).split(".")
    c.cookies.set(COOKIE, payload[:-2] + "xx." + sig)
    assert c.get("/t", follow_redirects=False).status_code == 303


def test_admin_pages_and_actions(client, backend):
    page = client.get("/admin/users")
    assert page.status_code == 200 and "drishti-dev-admin" in page.text and "default password" in page.text
    assert 'name="mustChangePassword">' in page.text  # not pre-ticked unless configured
    assert "user-seeded" in client.get("/admin/audit").text
    created = client.post("/admin/api/users", json={"username": "tina", "roles": ["trader"], "password": "trader-pass-1"})
    assert created.status_code == 201
    dup = client.post("/admin/api/users", json={"username": "drishti-dev-admin", "password": "x"})
    assert dup.status_code == 409 and dup.json()["code"] == "DRS-6002"
    assert client.post("/admin/api/users/tina/enabled", json={"enabled": False}).status_code == 200
    assert client.post("/admin/api/users/tina/nonsense", json={}).status_code == 400


def test_roles_page_defines_and_deletes_roles(client, backend):
    page = client.get("/admin/roles")
    assert page.status_code == 200 and "credit-analyst" in page.text and "built-in" in page.text and "every kind" in page.text
    assert 'href="/admin/roles"' in client.get("/admin/users").text            # in the admin tabs
    saved = client.post("/admin/api/roles/fx-viewer", json={"kinds": ["fx-spot"], "raw": False})
    assert saved.status_code == 200 and saved.json()["name"] == "fx-viewer"
    assert ("admin", "PUT", "/role-definitions/fx-viewer", {"kinds": ["fx-spot"], "raw": False}) in backend.calls
    bad = client.post("/admin/api/roles/empty", json={"kinds": []})
    assert bad.status_code == 400 and bad.json()["code"] == "DRS-5001"
    assert client.post("/admin/api/roles/credit-analyst/delete").json()["ok"]
    assert client.post("/admin/api/roles/admin/delete").status_code == 400


def test_account_page_and_password_change(client):
    assert "Change password" in client.get("/account").text
    assert client.post("/account/password", json={"current": "wrong", "next": "x"}).status_code == 401
    assert client.post("/account/password", json={"current": "drishti-dev-admin123", "next": "better-pass-99"}).json()["ok"]


def test_non_admins_do_not_see_admin_pages(backend):
    app = _secure_app(backend)
    c = TestClient(app)
    c.cookies.set(COOKIE, app.state.auth.session_for({"username": "tina", "displayName": "Tina", "desk": "FX", "roles": ["trader"]}))
    assert c.get("/admin/users").status_code == 403
    assert 'href="/admin/users"' not in c.get("/t").text


def test_studio_is_a_yaml_editor(client):
    page = client.get("/studio", params={"sutra": ""}).text
    for marker in ("data-insert", "data-outline", "data-complete", "data-field-help", 'data-tab="summary"', "studio-yaml.js", "studio-assist.js"):
        assert marker in page
    for gone in ("data-md=", 'data-tab="doc"', "cm-sutra-md.js", "md-editor.js"):
        assert gone not in page
    assert "rachana: 1\nsutra: my-layout\nversion: 1" in page and "```" not in page  # the new-Sutra skeleton is YAML
    assert client.get("/static/js/cm-sutra-md.js").status_code == 404 and client.get("/static/js/md-editor.js").status_code == 404
    assert client.post("/studio/render", json={"yaml": "x"}).status_code in (404, 405)


def test_studio_proxies_the_rachana_schema(client):
    r = client.get("/studio/schema")
    assert r.status_code == 200 and r.json()["properties"]["rachana"] == {"const": 1}
    assert r.json()["x-rachana-functions"]["size"] == {"min": 1, "max": 1}


def test_studio_summary_reads_the_yaml_back_safely(client):
    y = ("rachana: 1\nsutra: demo\nversion: 2\ndescription: Shows <script>alert(1)</script> trades\nnotes: |\n  line one\n  line two\n"
         "match: { kind: trade, where: \"$.x == 1\" }\nstrip:\n  - { label: MTM, bind: $.mtm, fmt: signed0 }\n"
         "panels:\n  - id: legs\n    kind: tabs\n    title: Legs\n    description: Each leg\n    each: $.legs\n    body:\n      kind: kv\n"
         "      columns:\n        - { label: Currency, bind: \"@.currency\" }\nkeys: { F5: \"link($.book, 'book')\" }\n")
    d = client.post("/studio/summary", json={"yaml": y}).json()
    h = d["html"]
    assert "<script>" not in h and "&lt;script&gt;" in h
    assert "demo" in h and "v2" in h and "line one" in h and "$.x == 1" in h and "MTM" in h
    assert "Legs" in h and "Each leg" in h and "tabs · kv" in h and "Currency" in h and "F5" in h
    bad = client.post("/studio/summary", json={"yaml": "a: [1, 2\nb: c"}).json()
    assert bad["error"] and "cannot be read" in bad["html"]
    assert "rachana: 1" in client.post("/studio/summary", json={"yaml": "sutra: x"}).json()["html"]  # nudges for the language key
    assert client.post("/studio/summary", json={"yaml": "- 1\n- 2"}).json()["error"]


def test_gauge_without_numbers_renders_empty(client):
    tpl = client.app.state.templates.env.from_string('{% from "_macros/panels.html" import panel %}{{ panel(p) }}')
    for data in ({"value": "—", "max": "—", "text": "—"}, {"value": 5, "max": None, "text": "5"}):
        out = tpl.render(p={"id": "g", "kind": "gauge", "title": "G", "area": "right", "data": data})
        assert 'class="gauge"' in out


def test_packs_page_loads_and_unloads(client, backend):
    calls = []
    async def admin(method, path, ident, body=None, **params):
        calls.append((method, path))
        if path == "/packs":
            return [{"name": "trading", "title": "Trading", "description": "", "version": "1", "loaded": True, "added": True, "enabled": True,
                     "extends": [], "requiredBy": [], "kinds": ["trade"], "connectors": [], "mnemonics": ["TRD"]},
                    {"name": "genomics", "title": "Genomics", "description": "", "version": "1", "loaded": False, "enabled": False}]
        if path == "/registry":
            return {"url": "", "configured": False, "packs": []}
        return {"name": path.split("/")[2], "added": [], "restarting": True, "note": "The server restarts in place now."}
    backend.admin = admin
    page = client.get("/admin/packs").text
    assert 'data-load="load"' in page and 'data-load="unload"' in page
    assert client.post("/admin/api/packs/genomics/load").json()["restarting"] is True
    assert ("POST", "/packs/genomics/load") in calls
    assert client.post("/admin/api/packs/genomics/explode").status_code == 400



def test_the_access_log_page(client, backend):
    calls = []

    async def admin(method, path, ident, body=None, **params):
        calls.append((path, params))
        if path == "/access/stats":
            return {"written": 3, "dropped": 0, "queued": 0, "keepDays": 90}
        return [{"at": "2026-10-01T09:00:00Z", "user": "tess", "action": "view", "kind": "trade", "entityId": "IRS-48213",
                 "detail": None, "businessDate": "2026-09-29"},
                {"at": "2026-10-01T08:59:00Z", "user": "tess", "action": "search", "kind": "TRD", "entityId": None,
                 "detail": "TRD where mtm < 0", "businessDate": None}]
    original = backend.admin
    backend.admin = admin
    try:
        page = client.get("/admin/access", params={"kind": "trade", "id": "IRS-48213", "from": "2026-09-30"}).text
    finally:
        backend.admin = original
    assert "Who looked at what" in page and "IRS-48213" in page and "TRD where mtm &lt; 0" in page and "2026-09-29" in page
    assert calls[0] == ("/access", {"kind": "trade", "id": "IRS-48213", "from": "2026-09-30", "limit": 200})
    assert "Viewed by" in client.get("/v/trade/IRS-48213").text



def test_the_registry_on_the_packs_page(client, backend):
    calls = []

    async def admin(method, path, ident, body=None, **params):
        calls.append((method, path))
        if path == "/registry":
            return {"url": "https://packs.example/registry", "configured": True, "packs": [
                {"name": "widgets", "version": "1.1.0", "title": "Widgets", "description": "", "requires": [], "publisher": "acme",
                 "trusted": True, "installedVersion": "1.0.0", "loadedVersion": "1.0.0"},
                {"name": "widgets", "version": "1.0.0", "title": "Widgets", "description": "", "requires": [], "publisher": "acme",
                 "trusted": True, "installedVersion": "1.0.0", "loadedVersion": "1.0.0"},
                {"name": "rogue", "version": "9.9.9", "title": "Rogue", "description": "", "requires": [], "publisher": "nobody",
                 "trusted": False, "installedVersion": None, "loadedVersion": None}]}
        if path.endswith("/install"):
            return {"installed": "1.1.0", "replaced": "1.0.0", "restarting": False, "note": "Saved; it takes effect when the server next starts."}
        return []
    original = backend.admin
    backend.admin = admin
    try:
        page = client.get("/admin/packs").text
        out = client.post("/admin/api/registry/widgets/1.1.0/install").json()
    finally:
        backend.admin = original
    assert "From the registry" in page and "packs.example" in page and "Install this version" in page and "Roll back" in page
    assert "not trusted" in page and page.count('data-registry="install"') == 1        # only the trusted, not-installed version
    assert out["replaced"] == "1.0.0" and ("POST", "/registry/widgets/1.1.0/install") in calls
