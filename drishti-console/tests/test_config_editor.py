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

"""Admin → Configuration (2.9.11.f; core/config_editor.py): every setting in three tiers with search, the value in effect and its source,
secrets never shown, and changes proposed through four eyes (never live): the stand-in below keeps the server's proposals as the server
does (SettingProposals.java: never your own, one open change per key)."""
from __future__ import annotations

import re

import pytest
import yaml
from fastapi.testclient import TestClient

from conftest import CONSOLE
from core import config_editor
from core.app import create_app
from core.auth import Identity
from core.backend import BackendError
from core.config import load_settings

SERVER = {"settings": [
    {"key": "drishti.var.confidence", "value": "0.975", "origin": "file", "source": "application.local.yaml", "secret": False},
    {"key": "drishti.security.secret", "value": "••••", "origin": "env", "source": "DRISHTI_TOKEN_SECRET", "secret": True},
    {"key": "drishti.lenses", "value": "risk,world", "origin": "env", "source": "DRISHTI_LENSES", "secret": False},
]}


@pytest.fixture
def editor(backend, monkeypatch, tmp_path):
    """A console whose people are told apart by a cookie (alice and li administrators, vic a viewer), the server's proposals kept here."""
    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend
    app.state.console_local_file = tmp_path / "application.local.yaml"
    kept: list[dict] = []
    original = backend.admin

    async def admin(method, path, ident, body=None, **params):
        if path == "/settings":
            return SERVER
        if path == "/settings/proposals" and method == "GET":
            return {"proposals": list(reversed(kept))}
        if path == "/settings/proposals" and method == "POST":
            if any(p["key"] == body["key"] and p["state"] == "proposed" for p in kept):
                raise BackendError(409, "DRS-2006", "already waiting")
            kept.append({**body, "id": f"SP-{len(kept) + 1}", "proposedBy": ident.user, "state": "proposed", "applied": False})
            return kept[-1]
        m = re.fullmatch(r"/settings/proposals/([\w-]+)/(\w+)", path)
        if m:
            p = next(x for x in kept if x["id"] == m.group(1))
            verb = m.group(2)
            if verb in ("approve", "reject") and p["proposedBy"] == ident.user:
                raise BackendError(403, "DRS-2007", "four eyes: a second administrator decides your proposal")
            if verb == "applied":
                p["applied"] = True
            else:
                p["state"] = {"approve": "approved", "reject": "rejected", "withdraw": "withdrawn"}[verb]
                p["applied"] = verb == "approve" and p["area"] != "console"
            return dict(p)
        return await original(method, path, ident, body, **params)

    monkeypatch.setattr(backend, "admin", admin)

    async def current(cookie, _backend):
        who = cookie or "alice"
        return Identity(user=who, display=who.title(), desk="Ops", roles=("viewer",) if who == "vic" else ("admin",))

    monkeypatch.setattr(app.state.auth, "current", current)
    app.state.kept = kept
    return app


def _as(app, who="alice"):
    c = TestClient(app)
    c.cookies.set(app.state.auth.cookie, who)
    return c


def _keys(page: str) -> list[str]:
    return re.findall(r'<tr data-key="([^"]+)"', page)


def test_everyday_is_the_first_tier_and_small(editor):
    page = _as(editor).get("/admin/configuration").text
    keys = _keys(page)
    assert "drishti.lenses" in keys and "drishti.var.confidence" in keys and "features.build" in keys and len(keys) <= 25
    counts = dict(re.findall(r'data-tier="(\w+)"[^>]*>[^<]*<small>([\d,]+)</small>', page))
    assert int(counts["everyday"]) == len(keys) and int(counts["admin"]) > 1000 and int(counts["expert"]) > 100
    assert not [k for k in keys if "branding" in k or "ui-kit" in k]


def test_each_row_shows_its_value_in_effect_and_where_it_came_from(editor):
    page = _as(editor).get("/admin/configuration", params={"q": "drishti.var.confidence"}).text
    row = re.search(r'<tr data-key="drishti.var.confidence".*?</tr>', page, re.S).group(0)
    assert "<code>0.975</code>" in row and "file: application.local.yaml" in row and "data-propose" in row and "everyday" in row


def test_search_finds_across_every_tier_and_secrets_are_never_shown(editor):
    c = _as(editor)
    every = c.get("/admin/configuration", params={"tier": "all", "q": "security secret"}).text
    row = re.search(r'<tr data-key="drishti.security.secret".*?</tr>', every, re.S).group(0)
    assert "••••" in row and "data-propose" not in row and 'data-value=""' in row
    expert = c.get("/admin/configuration", params={"tier": "expert", "q": "pool"}).text
    assert "drishti.db.pool.size" in _keys(expert) and "cfe-tier-admin" not in expert and "cfe-tier-everyday" not in expert
    assert "drishti.branding.product" in _keys(c.get("/admin/configuration", params={"tier": "admin", "q": "branding product"}).text)
    assert "0 settings" in c.get("/admin/configuration", params={"q": "no-such-setting-anywhere"}).text


def test_a_change_is_a_proposal_never_a_live_write(editor):
    alice = _as(editor)
    r = alice.post("/admin/configuration/propose", json={"key": "drishti.var.confidence", "area": "engines", "value": "0.99",
                                                         "was": "0.975", "reason": "the board asked"})
    assert r.status_code == 200 and r.json()["state"] == "proposed"
    assert not editor.state.console_local_file.exists()
    again = alice.post("/admin/configuration/propose", json={"key": "drishti.var.confidence", "area": "engines", "value": "0.95", "reason": "x"})
    assert again.status_code == 409
    secret = alice.post("/admin/configuration/propose", json={"key": "auth.session_secret", "area": "console", "value": "x", "reason": "x"})
    assert secret.status_code == 400 and not any(p["key"] == "auth.session_secret" for p in editor.state.kept)


def test_four_eyes_the_proposer_withdraws_another_administrator_decides(editor):
    alice, li = _as(editor), _as(editor, "li")
    pid = alice.post("/admin/configuration/propose", json={"key": "ui.placeholder", "area": "console", "value": "TRD …",
                                                           "reason": "shorter"}).json()["id"]
    mine = alice.get("/admin/configuration").text
    item = re.search(rf'<li data-proposal="{pid}">.*?</li>', mine, re.S).group(0)
    assert 'data-decide="withdraw"' in item and 'data-decide="approve"' not in item
    theirs = re.search(rf'<li data-proposal="{pid}">.*?</li>', li.get("/admin/configuration").text, re.S).group(0)
    assert 'data-decide="approve"' in theirs and 'data-decide="reject"' in theirs
    assert alice.post(f"/admin/configuration/proposals/{pid}/approve", json={}).status_code == 403
    done = li.post(f"/admin/configuration/proposals/{pid}/approve", json={"comment": "fine"})
    assert done.status_code == 200 and done.json()["applied"] is True                               # a console key: written here
    assert yaml.safe_load(editor.state.console_local_file.read_text())["ui"]["placeholder"] == "TRD …"
    assert "Nothing is waiting" in li.get("/admin/configuration").text


def test_a_viewer_may_not_open_or_propose(editor):
    vic = _as(editor, "vic")
    assert vic.get("/admin/configuration").status_code == 403
    assert vic.post("/admin/configuration/propose", json={"key": "drishti.lenses", "area": "server", "value": "risk", "reason": "x"}).status_code == 403


def test_the_console_file_keeps_its_other_keys(tmp_path):
    f = tmp_path / "application.local.yaml"
    f.write_text("ui:\n  desk: Credit\nbackend:\n  url: http://x\n", encoding="utf-8")
    config_editor.write_console_key(f, "ui.placeholder", "TRD", "note")
    data = yaml.safe_load(f.read_text())
    assert data == {"ui": {"desk": "Credit", "placeholder": "TRD"}, "backend": {"url": "http://x"}}
    with pytest.raises(ValueError):
        config_editor.write_console_key(f, "auth.session_secret", "x", "note")
