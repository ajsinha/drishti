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

"""Embedded views (docs/architecture/ELEMENTS.md, steps 1 to 3 on the server): off by default, an exact-origin CORS allow-list taken
from the server's registered host applications, the embed token passed to the server untouched (it verifies it on every call and
always masks), and nothing weakened for the console's own pages. The browser acceptance is in test_embed_elements_browser.py."""
import json
import time

import pytest
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core.embed_auth import EmbedAuth
from embed_keys import Signer
from core.app import CSP, create_app
from core.config import load_settings
from routes.embed_routes import shadow_css

HOST = "http://127.0.0.1:17968"
ARGS = ["--embed.enabled=true"]


SIGNER = Signer()


def token(user="alice", exp=None, typ="drishti-embed+jwt", app="demo") -> str:
    """A token as the server issues it (ES256, the signer's key is what the console's JWKS fetch returns)."""
    return SIGNER.token(user=user, app=app, exp=exp, typ=typ)


def make_client(backend, **overrides):
    """A console with embedding on whose server says one host application is registered, with the origin HOST."""
    app = create_app(load_settings(CONSOLE / "config", ARGS))
    app.state.backend = backend

    async def keys():
        return SIGNER.jwks()

    app.state.embed.auth = EmbedAuth(keys, audiences=["http://console.test"], **overrides)
    app.state.embed._origins = frozenset({HOST})            # what the server's registered host applications say
    app.state.embed._fetched = time.monotonic() + 3600
    return TestClient(app)


@pytest.fixture
def embed(backend):
    return make_client(backend)


def test_off_by_default(client):
    assert client.get("/embed/v1/drishti-view.css").status_code == 404
    assert client.get("/embed/v1/views/trade/X").status_code == 404
    assert client.post("/embed/v1/poc/token", json={}).status_code == 404


def test_the_poc_paths_and_the_dev_token_path_are_gone(embed):
    assert embed.get("/embed/v1/poc/drishti-view.css", headers={"Origin": HOST}).status_code in (404, 405)
    assert embed.post("/embed/v1/poc/token", json={"app": "demo", "secret": "x", "user": "a"}).status_code in (404, 405)


def test_view_is_cors_allow_listed_and_calls_the_server_with_the_embed_token(embed, backend):
    tok = token()
    ok = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {tok}", "Origin": HOST})
    assert ok.status_code == 200 and ok.headers["access-control-allow-origin"] == HOST and "Origin" in ok.headers["vary"]
    body = ok.json()
    assert body["ref"]["id"] == "IRS-47102" and body["panels"] and body["head"].startswith("<section")
    me = backend.calls[-1][3]
    assert me.token == tok and me.user == "alice"                       # the server gets the embed token itself, never a console token
    assert me.headers() == {"Authorization": f"Bearer {tok}", "Origin": HOST}   # and the browser's Origin, to check against the app's
    evil = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {tok}", "Origin": "http://evil.example"})
    assert evil.status_code == 403 and evil.json()["code"] == "DRS-8002" and "access-control-allow-origin" not in evil.headers
    assert embed.get("/embed/v1/views/trade/IRS-47102", headers={"Origin": HOST}).json()["code"] == "DRS-8001"
    assert embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": "Bearer a.b.c", "Origin": HOST}).status_code == 401
    expired = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {token(exp=int(time.time()) - 5)}", "Origin": HOST})
    assert expired.status_code == 401 and expired.json()["code"] == "DRS-8001"
    other = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {token(typ='JWT')}", "Origin": HOST})
    assert other.status_code == 401                                      # a console or personal token is not an embed token


def test_the_masked_count_is_the_servers(embed, backend):
    from conftest import FIXTURES

    vm = json.loads((FIXTURES / "view_trade_IRS-47102.json").read_text())
    vm.setdefault("provenance", {}).update({"masked": 3, "maskedPanels": ["terms"]})

    async def view(kind, id_, user):
        return vm

    backend.view = view
    body = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {token()}", "Origin": HOST}).json()
    assert body["provenance"]["masked"] == 3 and body["masked"] == {"count": 3, "panels": ["terms"]}


def test_a_refusal_of_the_server_reaches_the_host_with_its_code_and_retry_after(embed, backend):
    from core.backend import BackendError

    async def view(kind, id_, user):
        e = BackendError(429, "DRS-8004", "over the embed call rate")
        e.retry_after = "7"
        raise e

    backend.view = view
    r = embed.get("/embed/v1/views/trade/IRS-47102", headers={"Authorization": f"Bearer {token()}", "Origin": HOST})
    assert r.status_code == 429 and r.json()["code"] == "DRS-8004" and r.headers["retry-after"] == "7"


def test_preflight_only_for_listed_origins(embed):
    ok = embed.options("/embed/v1/channel/x", headers={"Origin": HOST, "Access-Control-Request-Method": "POST"})
    assert ok.status_code == 204 and ok.headers["access-control-allow-origin"] == HOST and "authorization" in ok.headers["access-control-allow-headers"]
    assert embed.options("/embed/v1/channel/x", headers={"Origin": "http://evil.example"}).status_code == 403
    assert embed.options("/embed/v1/channel/x", headers={"Origin": "null"}).status_code == 403


def test_channel_accepts_only_view_keys_and_an_owner(embed):
    h = {"Authorization": f"Bearer {token()}", "Origin": HOST}
    assert embed.post("/embed/v1/channel/nope", json={"add": ["alerts"]}, headers=h).status_code == 404
    assert embed.post("/embed/v1/channel/nope", json={}, headers={"Origin": HOST}).json()["code"] == "DRS-8001"


def test_the_element_modules_are_served_with_cors_and_only_those(embed):
    entry = embed.get("/embed/v1/drishti-elements.js", headers={"Origin": HOST})
    assert entry.status_code == 200 and "./m/element.js" in entry.text
    for name in ("config", "tokens", "sanitize", "assets", "connection", "enhancers", "frames", "element"):
        r = embed.get(f"/embed/v1/m/{name}.js", headers={"Origin": HOST})
        assert r.status_code == 200 and r.headers["access-control-allow-origin"] == HOST and "javascript" in r.headers["content-type"], name
    for bad in ("drishti-elements", "nothing", "A"):
        assert embed.get(f"/embed/v1/m/{bad}.js", headers={"Origin": HOST}).status_code == 404, bad


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


def _get(embed, path="/embed/v1/views/trade/IRS-47102", **kw):
    return embed.get(path, headers={"Authorization": f"Bearer {kw.pop('tok', None) or token()}", "Origin": kw.pop("origin", HOST), **kw})


def test_a_forged_or_foreign_token_is_refused_here_before_the_server_is_asked(embed, backend):
    n = len(backend.calls)
    assert _get(embed, tok=Signer("k1").token()).json()["code"] == "DRS-8001"            # same kid, another key: a forgery
    assert _get(embed, tok=SIGNER.token(aud="http://elsewhere.test")).json()["code"] == "DRS-8001"
    assert _get(embed, tok=SIGNER.token(origins=("http://other.example",))).json()["code"] == "DRS-8002"    # not this app's origin
    assert len(backend.calls) == n


def test_the_apps_own_origins_count_not_just_any_registered_one(embed):
    r = _get(embed, tok=SIGNER.token(origins=("http://127.0.0.1:1",)))
    assert r.status_code == 403 and r.json()["code"] == "DRS-8002" and r.headers.get("access-control-allow-origin") == HOST


def test_the_contract_version_is_checked(embed):
    assert _get(embed, **{"Drishti-Embed-Api": "1.0"}).status_code == 200
    r = _get(embed, **{"Drishti-Embed-Api": "9.0"})
    assert r.status_code == 410 and r.json()["code"] == "DRS-8006"


def test_rate_per_host_application(backend):
    c = make_client(backend)
    c.app.state.embed.limits.rate = 3
    codes = [_get(c).status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429]
    over = _get(c)
    assert over.json()["code"] == "DRS-8004" and int(over.headers["retry-after"]) >= 1 and over.headers["access-control-allow-origin"] == HOST
    assert _get(c, tok=token(app="other")).status_code == 200          # another application has its own budget


def test_max_streams_refuses_a_channel_open(backend):
    c = make_client(backend)
    c.app.state.embed.limits.max_streams = 1

    class Held:                                                          # one open channel holding one stream already
        session = type("S", (), {"tasks": {"view:trade/X": object()}})()

    c.app.state.embed.guards["held"] = Held()
    r = _get(c, "/embed/v1/channel?s=view:trade/A")
    assert r.status_code == 429 and r.json()["code"] == "DRS-8004" and r.headers["retry-after"] == "5"


def test_the_token_of_an_unknown_channel_is_refused(embed):
    r = embed.post("/embed/v1/channel/nope/token", headers={"Authorization": f"Bearer {token()}", "Origin": HOST})
    assert r.status_code == 404
    assert embed.post("/embed/v1/channel/nope/token", headers={"Origin": HOST}).json()["code"] == "DRS-8001"


def test_payloads_are_compressed_when_asked(embed):
    big = _get(embed, **{"Accept-Encoding": "gzip"})
    assert big.headers["content-encoding"] == "gzip" and big.json()["ref"]["id"] == "IRS-47102"      # httpx decodes it
    plain = _get(embed, **{"Accept-Encoding": "identity"})
    assert "content-encoding" not in plain.headers and "Accept-Encoding" in plain.headers["vary"]


def test_about_lives_under_views(embed):
    r = embed.get("/embed/v1/views/trade/IRS-48213/about", headers={"Authorization": f"Bearer {token()}", "Origin": HOST})
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/html")
