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

"""Help → Configuration (/help/configuration, 2.9.7 C3): the one configuration document, searchable by key, environment variable or
words and filtered by area; an administrator sees the values in effect on the server and in the console, and where each came from;
nobody else does; secrets are never shown."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from core.auth import Identity
from core.backend import BackendError
from core.config import Settings
from core.settings_page import MASK, console_effective, is_secret, searchable, server_effective, shown

ROOT = Path(__file__).resolve().parents[2]
SERVER = {"settings": [
    {"key": "drishti.history.max-days", "value": "90", "origin": "argument", "source": "--drishti.history.max-days", "secret": False},
    {"key": "drishti.security.secret", "value": MASK, "origin": "env", "source": "DRISHTI_TOKEN_SECRET", "secret": True},
    {"key": "server.port", "value": "18480", "origin": "default", "source": "application.yaml", "secret": False},
]}


def _rows(text: str) -> list[str]:
    return re.findall(r'<tr class="cfg-row"[^>]*>', text)


def _visible(text: str) -> list[str]:
    return [r for r in _rows(text) if not r.endswith(" hidden>")]


@pytest.fixture
def server_settings(backend, monkeypatch):
    calls = []
    original = backend.admin

    async def admin(method, path, ident, body=None, **params):
        if path == "/settings":
            calls.append(ident)
            return SERVER
        return await original(method, path, ident, body, **params)
    monkeypatch.setattr(backend, "admin", admin)
    return calls


def test_the_page_renders_the_whole_document_with_every_anchor(client, server_settings):
    page = client.get("/help/configuration")
    assert page.status_code == 200
    text = page.text
    assert 'data-cfg-page' in text and "/static/js/configuration.js" in text and "help-config.css" in text
    for anchor in ("how-configuration-works", "every-setting", "the-server-applicationyaml", "the-console-applicationyaml",
                   "packs-packyaml", "environment-variable-index", "delta--delta-lake", "connectors-every-plugins-settings"):
        assert f'id="{anchor}"' in text, anchor
    rows = _rows(text)
    assert len(rows) > 1500 and len(_visible(text)) == len(rows)          # nothing hidden without a search
    assert '<div class="cfg-explain">' in text                              # the explanation shows


def test_a_search_by_key_env_var_or_words_keeps_only_the_matching_rows(client, server_settings):
    by_key = client.get("/help/configuration", params={"q": "drishti.history.max-days"}).text
    assert any('drishti.history.max-days' in r for r in _visible(by_key)) and len(_visible(by_key)) < 10
    assert '<div class="cfg-explain" hidden>' in by_key

    by_env = client.get("/help/configuration", params={"q": "DRISHTI_PORT"}).text
    assert any("server.port" in r or "drishti_port" in r for r in _visible(by_env))

    by_words = _visible(client.get("/help/configuration", params={"q": "outbound proxy"}).text)
    assert by_words and all("proxy" in r for r in by_words)

    nothing = client.get("/help/configuration", params={"q": "no-such-setting-anywhere"}).text
    assert not _visible(nothing) and "0 of" in nothing and "try fewer words" in nothing


def test_the_area_filter_keeps_one_area(client, server_settings):
    text = client.get("/help/configuration", params={"area": "packs-packyaml"}).text
    visible = _visible(text)
    assert visible and all('data-area="packs-packyaml"' in r for r in visible)
    assert '<option value="packs-packyaml" selected>' in text
    both = _visible(client.get("/help/configuration", params={"area": "packs-packyaml", "q": "mnemonics"}).text)
    assert both and len(both) < len(visible)


def test_an_administrator_sees_the_values_in_effect_and_where_they_came_from(client, server_settings):
    text = client.get("/help/configuration", params={"q": "max-days"}).text            # security off: the acting user is an admin
    assert server_settings, "the page asked the server for its settings"
    assert "<th>In effect</th>" in text
    row = next(r for r in re.findall(r'<tr class="cfg-row"[^>]*data-hay="drishti.history.max-days[^"]*".*?</tr>', text, re.S))
    assert "<code>90</code>" in row and "argument: --drishti.history.max-days" in row and "cfg-differs" in row
    secret = client.get("/help/configuration", params={"q": "drishti.security.secret"}).text
    assert MASK in secret


def test_others_see_no_values(client, server_settings, monkeypatch):
    import routes.help_routes as hr

    monkeypatch.setattr(hr, "ident", lambda request: Identity("vic", "Vic", "Rates", ("viewer",)))
    text = client.get("/help/configuration", params={"q": "max-days"}).text
    assert "In effect" not in text and "cfg-eff" not in text and not server_settings


def test_a_server_that_cannot_answer_is_said_and_the_page_still_renders(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, **params):
        raise BackendError(404, "DRS-5004", "no such endpoint")
    monkeypatch.setattr(backend, "admin", admin)
    text = client.get("/help/configuration", params={"q": "server.port"}).text
    assert "could not be read" in text and _visible(text)


def test_secrets_are_masked_in_the_console_and_on_the_server(tmp_path):
    for key in ("auth.session_secret", "auth.token_secret", "auth.oidc.client_secret", "drishti.security.secret",
                "drishti.http.proxy.password", "drishti.embed.signing-key", "ai.api-key", "drishti.security.metrics-token"):
        assert is_secret(key), key
    for key in ("bi.ai.max-tokens", "drishti.var.keep-on-shutdown", "server.port", "ui.desk"):
        assert not is_secret(key), key
    assert shown("auth.session_secret", "s3cr3t-value") == MASK and shown("auth.session_secret", "") == ""
    assert shown("backend.url", "https://ash:pw@drishti:8480") == f"https://ash:{MASK}@drishti:8480"
    assert server_effective({"settings": [{"key": "drishti.security.secret", "value": "leaked", "origin": "env"}]})[
        "drishti.security.secret"]["value"] == MASK

    (tmp_path / "application.yaml").write_text("auth:\n  session_secret: ${MY_SESSION:}\nserver:\n  port: ${MY_PORT:17480}\n"
                                               "backend:\n  timeout_seconds: 5\nui:\n  desk: Rates\n", encoding="utf-8")
    (tmp_path / "application.local.yaml").write_text("ui:\n  desk: Credit\n", encoding="utf-8")
    base = {"auth": {"session_secret": "x" * 40}, "server": {"port": 17999}, "backend": {"timeout_seconds": 9}, "ui": {"desk": "Credit"}}
    over = {"backend": {"timeout_seconds": 9}, "ui": {"desk": "Credit"}}
    s = Settings(base, base, over)
    eff = console_effective(s, tmp_path, {"MY_SESSION": "x" * 40, "MY_PORT": "17999", "DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS": "9"})
    assert eff["auth.session_secret"] == {"value": MASK, "origin": "env", "source": "MY_SESSION", "secret": True}
    assert eff["server.port"]["origin"] == "env" and eff["server.port"]["value"] == "17999"
    assert eff["backend.timeout_seconds"]["origin"] == "env" and eff["backend.timeout_seconds"]["source"] == "DRISHTI_CONSOLE__BACKEND__TIMEOUT_SECONDS"
    assert eff["ui.desk"] == {"value": "Credit", "origin": "file", "source": "application.local.yaml", "secret": False}


def test_searchable_without_the_generated_half_is_the_page_as_it_is():
    assert searchable("<p>no settings</p>", "x") == ("<p>no settings</p>", 0, 0)


def test_the_help_centre_admin_pages_and_health_link_to_it(client):
    assert 'href="/help/configuration"' in client.get("/help").text
    nav = (ROOT / "drishti-console/web/templates/admin/_nav.html").read_text(encoding="utf-8")
    assert 'href="/help/configuration"' in nav
    health = (ROOT / "drishti-console/web/templates/admin/health.html").read_text(encoding="utf-8")
    assert 'href="/help/configuration"' in health
