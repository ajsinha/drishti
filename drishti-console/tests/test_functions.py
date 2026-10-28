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

"""Functions, FUNC (docs/admin/USER_MANAGEMENT.md): the command directory for everyone (a user sees only the commands that are on, no switches; an
administrator sees the matrix: commands × Everyone (no role columns until per-role access is built), off commands, the batch sent with its reason, the history and impact
passed through); the reserved word FUNC; /admin/commands redirecting; and the "not active" page a command an administrator turned off
answers with, by its page or the typed command; links and panels that read it say so."""
import pytest

from core.auth import Identity
from core.backend import BackendError

LISTING = {"node": "n-a", "hash": "abcdef0123456789", "newest": "2026-10-24T09:00:00Z", "total": 3, "off": 1, "roles": ["auditor", "trader"],
           "nodes": [{"node": "n-a", "self": True, "state": "alive", "hash": "abcdef0123456789", "inStep": True},
                     {"node": "n-b", "self": False, "state": "alive", "hash": "0000", "inStep": False}],
           "packs": [{"name": "trading", "code": "TRD", "title": "Trading", "on": True, "neededBy": [],
                      "commands": [{"code": "TRD", "kind": "trade", "label": "Trade", "description": "One trade with its legs and cashflows.",
                                    "example": "TRD 1000015", "on": True}]},
                     {"name": "market-risk", "code": "MKR", "title": "Market risk", "on": True, "neededBy": ["counterparty-risk"],
                      "commands": [{"code": "FRTB", "kind": "frtb-sensitivity", "label": "FRTB sensitivities", "description": "FRTB SA sensitivities.",
                                    "example": "FRTB FRTB-RATES-1", "on": False, "since": "2026-10-24T09:00:00Z", "reason": "audit asked"},
                                   {"code": "VAR", "kind": "var", "label": "VaR explorer", "description": "Value at risk.", "example": "", "on": True}]}]}


def with_admin(backend, monkeypatch, calls):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body, params))
        if path == "/commands" and method == "GET":
            return LISTING
        if path == "/commands" and method == "POST":
            return {**LISTING, "off": 2}
        if path == "/commands/history":
            return {"items": [{"scope": "command", "name": "FRTB", "on": False, "reason": "audit asked", "actor": "ann", "at": "2026-10-24T09:00:00Z", "node": "n-a"}]}
        if path.endswith("/impact"):
            return {"code": "FRTB", "kind": "frtb-sensitivity", "page": "/v/frtb-sensitivity/…", "uses": [{"label": "BI datasets", "count": 1, "items": [{"name": "capital"}]}]}
        return {}
    monkeypatch.setattr(backend, "admin", admin)


@pytest.fixture
def as_user(monkeypatch):
    """Signed in as someone who is not an administrator (the same rule the page uses: Identity.is_admin)."""
    monkeypatch.setattr(Identity, "is_admin", property(lambda self: False))


def test_an_administrator_sees_the_matrix_with_off_commands_the_everyone_switch_and_each_servers_version(client, backend, monkeypatch):
    with_admin(backend, monkeypatch, [])
    page = client.get("/func").text
    for text in ("data-functions", "data-commands", 'data-matrix', "Everyone",
                 'data-command="FRTB"', 'data-on="false"', "FRTB SA sensitivities.", "FRTB FRTB-RATES-1", 'data-switch="pack" data-name="market-risk"',
                 'data-needed-by="counterparty-risk"', "Review changes", "data-undo", "Reason (kept in the audit log)", "data-show",
                 "Applies on every server of the cluster within seconds", "Version on each server", "n-b", "catching up", "in step",
                 "1 of 2 in step", "data-cluster", "History", "functions.js", "functions.css"):
        assert text in page, text
    assert "1 off" in page and "3 commands" in page
    assert ">auditor<" not in page and "cmd-role" not in page and "role columns" not in page, "only the Everyone column (the approved mock-up)"
    assert '<span>FRTB</span> <span class="st st-bad">off</span>' in page, "an off command is struck through and not a link"
    assert 'href="/go?q=FRTB' not in page
    assert '<a href="/go?q=TRD%201000015" data-go="TRD 1000015"' in page
    assert '<a href="/form/market-risk?kind=var" data-go="FORM market-risk var"' in page, "no example: the kind's search form"
    assert '<a class="cmd-pack-name" href="/form/trading"' in page


def test_a_user_sees_only_the_commands_they_can_use_and_nothing_of_the_switches(client, backend, monkeypatch, as_user):
    calls = []
    with_admin(backend, monkeypatch, calls)
    page = client.get("/func").text
    for text in ("data-functions", 'data-command="TRD"', "One trade.", "TRD T-1", "1 command you can use", '<a href="/go?q=TRD%20T-1"',
                 '<a class="cmd-pack-name" href="/form/finance" title="Its search form: FORM finance">Finance</a>', "functions.js"):
        assert text in page, text
    for text in ("data-commands", "data-switch", "Everyone", "Review changes", "data-undo", "Reason (kept in the audit log)", "data-show",
                 "Version on each server", "data-history", "data-cluster", " off<"):
        assert text not in page, text
    assert calls == [], "a user's page never asks the administrators' listing"


def test_func_filters_by_code_words_and_pack(client, backend, monkeypatch):
    with_admin(backend, monkeypatch, [])
    page = client.get("/func", params={"q": "trading"}).text
    assert '<tr data-command="TRD" data-kind="trade" data-on="true" data-text=' in page and 'data-command="VAR"' in page
    assert 'data-pack="market-risk" data-pack-text="market-risk market risk" data-pack-on="true" data-needed-by="counterparty-risk" hidden' in page
    page = client.get("/func", params={"q": "value at"}).text
    assert 'data-pack="trading" data-pack-text="trading trading" data-pack-on="true" hidden' in page
    assert 'value="value at"' in page and 'value="FUNC value at"' in page, "the filter and the command line say what was typed"
    page = client.get("/func", params={"q": "nothing-like-it"}).text
    assert "No command matches" in page and "data-empty hidden" not in page


def test_admin_commands_redirects_to_functions(client):
    r = client.get("/admin/commands", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/func"


@pytest.mark.parametrize("typed,where", [("FUNC <GO>", "/func"), ("func", "/func"), ("FUNC exposure <GO>", "/func?q=exposure"),
                                         ("FUNC VAR", "/func?q=VAR"), ("Func market risk", "/func?q=market%20risk")])
def test_the_reserved_word_func_opens_the_page_filtered(client, typed, where):
    r = client.get("/go", params={"q": typed}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == where


def test_func_in_the_type_ahead_offers_the_page(client):
    s = client.get("/api/suggest", params={"q": "FUNC expo"}).json()
    assert s[0]["mnemonic"] == "FUNC" and s[0]["href"] == "/func?q=expo" and s[0]["complete"] == "FUNC expo"


def test_functions_is_in_the_views_menu_for_everyone(client, backend, monkeypatch, as_user):
    with_admin(backend, monkeypatch, [])
    page = client.get("/t").text
    assert 'href="/func"' in page and "Every command you can use, what it does · also FUNC &lt;GO&gt;" in page


def test_a_batch_goes_to_the_server_with_its_reason_and_the_packs_are_read_again(client, backend, monkeypatch):
    calls = []
    with_admin(backend, monkeypatch, calls)
    forgot = []
    monkeypatch.setattr(client.app.state.packs, "forget_all", lambda: forgot.append(1))
    r = client.post("/admin/api/commands", json={"reason": "quarter end", "changes": [{"scope": "command", "name": "VAR", "on": False}, "junk"]})
    assert r.status_code == 200 and r.json()["off"] == 2
    assert ("POST", "/commands", {"reason": "quarter end", "changes": [{"scope": "command", "name": "VAR", "on": False}]}, {}) in calls
    assert forgot == [1]
    assert client.get("/admin/api/commands/history?scope=command&name=FRTB").json()["items"][0]["reason"] == "audit asked"
    assert ("GET", "/commands/history", None, {"limit": 100, "scope": "command", "name": "FRTB"}) in calls
    assert client.get("/admin/api/commands/FRTB/impact").json()["uses"][0]["count"] == 1


def test_a_refusal_is_passed_on_and_a_non_administrator_sees_the_forbidden_page(client, backend, monkeypatch):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        raise BackendError(400, "DRS-5001", "no loaded pack defines the command 'NOPE'")
    monkeypatch.setattr(backend, "admin", admin)
    r = client.post("/admin/api/commands", json={"changes": [{"scope": "command", "name": "NOPE", "on": False}]})
    assert r.status_code == 400 and r.json()["code"] == "DRS-5001"


def _off(e_kind="frtb-sensitivity"):
    e = BackendError(403, "DRS-1016", "DRS-1016 FRTB is not active on this server")
    e.extra = {"code": "DRS-1016", "command": "FRTB", "kind": e_kind, "since": "2026-10-24"}
    return e


def test_the_page_of_a_command_turned_off_says_not_active_and_shows_nothing_of_it(client, backend, monkeypatch):
    async def view(kind, id_, user, **kw):
        raise _off()
    monkeypatch.setattr(backend, "view", view)
    r = client.get("/v/frtb-sensitivity/FRTB-RATES-1")
    assert r.status_code == 403
    for text in ("Not active", "FRTB is not active on this server", "An administrator has turned this command off", "Off since",
                 "2026-10-24", "Ask an administrator if you need it", "Back to where I was", "Commands that are on", 'data-command-off="FRTB"'):
        assert text in r.text, text


def test_the_typed_command_of_a_command_turned_off_answers_the_same_page(client, backend, monkeypatch):
    async def command(text, ident):
        raise _off()
    monkeypatch.setattr(backend, "command", command)
    r = client.get("/go", params={"q": "FRTB FRTB-RATES-1 <GO>"}, follow_redirects=False)
    assert r.status_code == 403 and "FRTB is not active on this server" in r.text


def test_links_and_panels_that_read_a_command_turned_off_say_not_active():
    from jinja2 import Environment, FileSystemLoader

    from conftest import CONSOLE
    env = Environment(loader=FileSystemLoader(str(CONSOLE / "web" / "templates")))
    env.globals.update(drs_message=lambda *a: "", drs_advice=lambda *a: "")
    macros = env.get_template("_macros/panels.html").module
    cell = str(macros.cell_text({"text": "FRTB-RATES-1", "tone": "inactive", "link": None}))
    assert "lnk-off" in cell and "not active" in cell and "href" not in cell
    links = str(macros.links({"links": [{"label": "Risk", "text": "FRTB-RATES-1", "link": None, "badge": "not active", "status": "inactive"}]}))
    assert 'class="lk-inactive"' in links and "not active" in links and "href" not in links
    panel = str(macros.no_data({"denied": "not active (turned off by an administrator)", "kind": "table", "data": None}))
    assert "Not active (turned off by an administrator)" in panel and "turned off the command this panel reads" in panel
