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
from core.auth import COOKIE, Auth, hash_password, mint_token, verify_password  # noqa: E402
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


def test_passwords_hash_and_verify():
    h = hash_password("s3cret", iterations=1000)
    assert verify_password("s3cret", h) and not verify_password("wrong", h) and not verify_password("x", "junk")


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


def test_sign_in_flow(tmp_path, backend):
    users = tmp_path / "users.yaml"
    users.write_text("users:\n  ash:\n    display: Ash\n    desk: Rates desk\n    roles: [author]\n    password: "
                     + hash_password("pw", iterations=1000) + "\n")
    settings = load_settings(CONSOLE / "config")
    data = settings.as_dict()
    data["auth"] = {"enabled": True, "users_file": str(users), "session_secret": "s" * 40, "token_secret": "t" * 40,
                    "token_ttl_seconds": 60, "session_hours": 1, "secure_cookie": False}
    app = create_app(Settings(data))
    app.state.backend = backend
    c = TestClient(app)
    assert c.get("/t", follow_redirects=False).headers["location"].startswith("/login")
    assert c.get("/api/suggest").status_code == 401
    assert c.get("/").status_code == 200
    assert c.post("/login", content="user=ash&password=nope&next=/t", headers={"Content-Type": "application/x-www-form-urlencoded"}).status_code == 401
    r = c.post("/login", content="user=ash&password=pw&next=//evil.example", follow_redirects=False,
               headers={"Content-Type": "application/x-www-form-urlencoded"})
    assert r.status_code == 303 and r.headers["location"] == "/t"
    assert c.get("/t").status_code == 200 and "sign out" in c.get("/t").text
    ident = app.state.auth.identity(c.cookies.get(COOKIE))
    assert ident.user == "ash" and ident.headers()["Authorization"].startswith("Bearer ")
    c.cookies.set(COOKIE, c.cookies.get(COOKIE).replace("ash", "eve"))
    assert c.get("/t", follow_redirects=False).status_code == 303
