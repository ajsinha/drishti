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
    assert "One grammar." in html                                  # the tagline, under the headline
    assert "See what your data" in html and 'class="lp-grad">hasn&#39;t told you yet.</span>' in html
    assert "Copyright © 2026 Ashutosh Sinha" in html
    for theme in ("terminal", "light", "wallstreet", "blue", "green", "crimson", "crimson-dark", "terminal-green", "terminal-crimson", "terminal-blue"):
        assert f'data-theme-choice="{theme}"' in html
    for shot in ("irs", "fx-swap", "commodity-future", "netting-set"):
        assert f"drishti_shot-{shot}.png" in html


def test_second_slogan_line_shows_from_configuration(client):
    html = client.get("/").text
    assert '<p class="lp-sentinel" data-headline-second>Your data is a treasury; <b>Drishti is its sentinel.</b></p>' in html
    assert html.index('class="lp-title"') < html.index('class="lp-sentinel"') < html.index('class="lp-sub"')
    assert "See what your data" in html and 'class="lp-grad">hasn&#39;t told you yet.</span>' in html     # the headline is unchanged


def test_second_slogan_line_hidden_when_blank(backend):
    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings
    from fastapi.testclient import TestClient
    settings = load_settings(CONSOLE / "config")
    settings.layer("test", {"ui": {"headline_second": "", "headline_second_accent": ""}})
    app = create_app(settings)
    app.state.backend = backend
    html = TestClient(app).get("/").text
    assert "data-headline-second" not in html and 'class="lp-sentinel"' not in html
    assert 'class="lp-grad">hasn&#39;t told you yet.</span>' in html


def test_security_headers(client):
    r = client.get("/")
    assert "script-src 'self'" in r.headers["content-security-policy"]
    assert r.headers["x-content-type-options"] == "nosniff"


def test_static_assets_served(client):
    for path in ("/static/js/drishti_landing.js", "/static/css/drishti_tokens.css", "/static/vendor/bootstrap/css/bootstrap.min.css"):
        assert client.get(path).status_code == 200


def test_health(client):
    health = client.get("/healthz").json()
    assert health["status"] == "UP" and "pythonRuntime" in health          # Calc's runtime: a version, or how to install it
