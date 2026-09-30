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

"""The landing page renders, carries security headers, the copyright, themes and the hero."""


def test_landing_renders(client):
    r = client.get("/")
    assert r.status_code == 200
    html = r.text
    assert 'id="lpHero"' in html
    assert "One grammar." in html
    assert "Copyright © 2026 Ashutosh Sinha" in html
    for theme in ("terminal", "light", "wallstreet", "blue", "green"):
        assert f'data-theme-choice="{theme}"' in html
    for shot in ("irs", "fx-swap", "commodity-future", "netting-set"):
        assert f"shot-{shot}.png" in html


def test_security_headers(client):
    r = client.get("/")
    assert "script-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-content-type-options"] == "nosniff"


def test_static_assets_served(client):
    for path in ("/static/js/landing.js", "/static/css/tokens.css", "/static/vendor/bootstrap/css/bootstrap.min.css"):
        assert client.get(path).status_code == 200


def test_health(client):
    assert client.get("/healthz").json() == {"status": "UP"}
