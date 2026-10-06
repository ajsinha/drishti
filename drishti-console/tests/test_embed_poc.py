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

"""Embedded views, proof of concept (docs/architecture/ELEMENTS.md, step 0): off by default, an exact-origin CORS allow-list,
embedded calls always masked, and nothing weakened for the console's own pages. The browser acceptance is in
test_embed_elements_browser.py."""
import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.app import CSP, create_app
from core.config import load_settings
from routes.embed_routes import shadow_css

HOST = "http://127.0.0.1:17968"
APP = "--embed.poc.apps.demo."
ARGS = ["--embed.poc.enabled=true", "--embed.poc.signing_key=poc-signing-key-0123456789abcdef0123456", f"{APP}secret=s3cret",
        f"{APP}origins={HOST}", f"{APP}role_map.viewer=viewer", f"{APP}role_map.author=viewer"]


@pytest.fixture
def embed(backend):
    app = create_app(load_settings(CONSOLE / "config", ARGS))
    app.state.backend = backend
    return TestClient(app)


def token(embed, roles=("author",), secret="s3cret"):
    r = embed.post("/embed/v1/poc/token", json={"app": "demo", "secret": secret, "user": "alice", "roles": list(roles)})
    return r.json().get("access_token"), r


def test_off_by_default(client):
    assert client.get("/embed/v1/poc/drishti-view.css").status_code == 404
    assert client.post("/embed/v1/poc/token", json={}).status_code == 404


def test_dev_token_needs_the_app_secret_and_no_browser_origin(embed):
    assert token(embed, secret="wrong")[1].status_code == 401
    assert embed.post("/embed/v1/poc/token", json={"app": "demo", "secret": "s3cret", "user": "a"}, headers={"Origin": HOST}).status_code == 403
    assert token(embed)[0]


def test_view_is_cors_allow_listed_and_always_masked(embed, backend):
    tok, _ = token(embed, roles=("author", "admin"))
    ok = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {tok}", "Origin": HOST})
    assert ok.status_code == 200 and ok.headers["access-control-allow-origin"] == HOST and "Origin" in ok.headers["vary"]
    body = ok.json()
    assert body["ref"]["id"] == "IRS-47102" and body["panels"] and body["head"].startswith("<section")
    assert backend.calls[-1][3].roles == ("viewer",)                 # author and admin both run as the masked role
    evil = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {tok}", "Origin": "http://evil.example"})
    assert evil.status_code == 403 and evil.json()["code"] == "DRS-8002" and "access-control-allow-origin" not in evil.headers
    assert embed.get("/embed/v1/views/trade/IRS-47102", headers={"Origin": HOST}).json()["code"] == "DRS-8001"
    assert embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": "Bearer a.b.c", "Origin": HOST}).status_code == 401


def test_preflight_only_for_listed_origins(embed):
    ok = embed.options("/embed/v1/channel/x", headers={"Origin": HOST, "Access-Control-Request-Method": "POST"})
    assert ok.status_code == 204 and ok.headers["access-control-allow-origin"] == HOST and "authorization" in ok.headers["access-control-allow-headers"]
    assert embed.options("/embed/v1/channel/x", headers={"Origin": "http://evil.example"}).status_code == 403
    assert embed.options("/embed/v1/channel/x", headers={"Origin": "null"}).status_code == 403


def test_channel_accepts_only_view_keys_and_an_owner(embed):
    tok, _ = token(embed)
    h = {"Authorization": f"Bearer {tok}", "Origin": HOST}
    assert embed.post("/embed/v1/channel/nope", json={"add": ["alerts"]}, headers=h).status_code == 404
    assert embed.post("/embed/v1/channel/nope", json={}, headers={"Origin": HOST}).json()["code"] == "DRS-8001"


def test_the_consoles_own_pages_are_unchanged(embed):
    page = embed.get("/login")
    assert page.headers["content-security-policy"] == CSP and "frame-ancestors 'self'" in CSP
    assert page.headers["x-frame-options"] == "SAMEORIGIN" and "access-control-allow-origin" not in page.headers
    refused = embed.post("/api/packs", json={"active": []}, headers={"Origin": HOST})      # a listed embed origin is still a foreign site here
    assert refused.status_code == 403 and refused.json()["code"] == "DRS-5002"


def test_shadow_css_turns_page_selectors_into_the_host():
    css = ':root, :root[data-theme="terminal"] { --a: 1 }\n:root[data-theme="light"] { --a: 2 }\n  :root:not([data-theme]) { --a: 3 }\n'
    css += 'html, body { color: red }\nbody.terminal { font-size: 14px }\n@font-face { font-family: "x"; src: url(a.woff2) }\n.pnl { color: blue }\n'
    out = shadow_css(css)
    assert ':host, :host([data-theme="terminal"])' in out and ':host([data-theme="light"])' in out and ":host(:not([data-theme]))" in out
    assert ":host { color: red }" in out and ":host { font-size: 14px }" in out and "@font-face" not in out and ".pnl { color: blue }" in out
