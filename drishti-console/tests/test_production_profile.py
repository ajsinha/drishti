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

"""The console's production profile (core/production.py, docs/admin/PRODUCTION_STANDARD.md): a safe configuration starts, each unsafe
setting is refused by one line naming the setting and the fix, and nothing is refused outside production."""
import pytest

from core import production
from core.config import Settings, load_settings
from pathlib import Path

SAFE = {"profile": "production",
        "auth": {"enabled": True, "session_secret": "s" * 40, "token_secret": "t" * 40, "secure_cookie": True},
        "server": {"host": "127.0.0.1"}}


@pytest.fixture(autouse=True)
def _site_folder(monkeypatch):
    monkeypatch.setenv("DRISHTI_SITE_DIR", "/etc/drishti")
    monkeypatch.setenv("DRISHTI_HOME", "/opt/drishti/current/drishti")


def test_the_site_folder_is_named_and_outside_the_release(monkeypatch):
    monkeypatch.delenv("DRISHTI_SITE_DIR")
    one(safe(), "drishti.site.dir is not set")
    monkeypatch.setenv("DRISHTI_SITE_DIR", "/opt/drishti/current/drishti/config")
    one(safe(), "inside the release folder")
    monkeypatch.setenv("DRISHTI_SITE_DIR", "/etc/drishti")
    assert production.refusals(safe()) == []


def safe(**changes):
    import copy
    data = copy.deepcopy(SAFE)
    for dotted, value in changes.items():
        node = data
        parts = dotted.split("__")
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = value
    return Settings(data)


def one(settings, names):
    lines = production.refusals(settings)
    assert len(lines) == 1, lines
    assert names in lines[0] and "Fix:" in lines[0] and "\n" not in lines[0]


def test_a_safe_console_starts():
    assert production.refusals(safe()) == []
    production.enforce(safe())


def test_a_proxy_stated_console_may_bind_the_network():
    assert production.refusals(safe(server__host="0.0.0.0", production__tls="proxy")) == []


def test_sign_in_off_is_refused():
    one(safe(auth__enabled=False), "auth.enabled")


def test_weak_secrets_are_refused():
    one(safe(auth__session_secret="short"), "auth.session_secret")
    one(safe(auth__token_secret=""), "auth.token_secret")


def test_an_insecure_cookie_is_refused():
    one(safe(auth__secure_cookie=False), "auth.secure_cookie")


@pytest.mark.parametrize("host", ["0.0.0.0", "::", "", "10.1.2.3", "console.bank.internal"])
def test_the_console_on_the_network_without_a_proxy_is_refused(host):
    one(safe(server__host=host), "server.host")


@pytest.mark.parametrize("host", ["127.0.0.1", "localhost", "::1", "[::1]"])
def test_loopback_is_allowed(host):
    assert production.refusals(safe(server__host=host)) == []


def test_enforce_stops_with_every_line():
    with pytest.raises(SystemExit) as e:
        production.enforce(safe(auth__enabled=False, server__host="0.0.0.0"))
    lines = str(e.value).splitlines()
    assert len(lines) == 3 and "2 settings are unsafe" in lines[0]


@pytest.mark.parametrize("profile", ["", "demo", "development", "qa"])
def test_nothing_is_refused_outside_production(profile):
    s = Settings({"profile": profile, "auth": {"enabled": False}, "server": {"host": "0.0.0.0"}})
    assert production.refusals(s) == []


def test_the_shipped_defaults_are_development_not_production():
    s = load_settings(Path(__file__).resolve().parents[1] / "config", [])
    assert production.profile_name(s) == "development"
    assert production.refusals(s) == []


def test_the_profile_comes_from_the_shared_settings_file(tmp_path):
    ini = tmp_path / "prod.ini"
    ini.write_text("[roots]\ndrishti.profile=production\n", encoding="utf-8")
    s = load_settings(Path(__file__).resolve().parents[1] / "config", ["--settings", str(ini)])
    assert production.is_production(s)
    assert len(production.refusals(s)) >= 3          # sign-in off, no secrets, the shipped host 0.0.0.0
