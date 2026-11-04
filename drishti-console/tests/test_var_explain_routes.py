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

"""Why it moved (docs/guides/VAR_GUIDE.md section 3.11): VAR EXPLAIN <scope> opens the VaR page on its tab, the page has the tab, the view
switch and the export, the view comes from the link or the person's settings, the JSON goes to the engine with its dates, FUNC lists the
command, F1 has its section and a phone tiers the tab's parts."""
from __future__ import annotations

from routes.functions_routes import CONSOLE_COMMANDS
from routes.var_routes import explain_command, var_command, var_suggestions


def test_var_explain_opens_the_var_page_on_its_tab():
    assert var_command("VAR EXPLAIN BANK".split(), "asha") == "/var?scope=BANK&tab=why"
    assert var_command("VAR EXPLAIN".split(), "asha") == "/var?scope=BANK&tab=why"
    assert var_command("VAR EXPLAIN DESK DESK-RATES".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&tab=why"
    assert var_command("VAR DESK DESK-RATES EXPLAIN".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&tab=why"
    assert var_command("VAR BANK EXPLAIN <GO>".split(), "asha") == "/var?scope=BANK&tab=why"
    assert var_command("VAR EXPLAIN NODE:MGMT~DESK-FX".split(), "asha") == "/var?scope=NODE%3AMGMT~DESK-FX&tab=why"
    assert var_command("VAR EXPLAIN BANK 2026-06-30".split(), "asha") == "/var?scope=BANK&tab=why&efrom=2026-06-30"
    assert var_command("VAR EXPLAIN DESK DESK-RATES 2026-09-29 2026-09-30".split(), "asha") == \
        "/var?scope=DESK%3ADESK-RATES&tab=why&efrom=2026-09-29&eto=2026-09-30"
    assert explain_command("VAR BANK".split(), "asha") is None                       # not asked: the explorer as before
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"
    assert explain_command("TRD EXPLAIN".split(), "asha") is None
    assert "VAR BANK EXPLAIN" in [s["complete"] for s in var_suggestions("VAR BANK")]


def test_the_page_has_the_tab_the_view_switch_and_the_export(client):
    html = client.get("/var?scope=DESK:DESK-RATES&tab=why&view=matrix").text
    assert 'data-tab="why"' in html and 'data-tab="why" aria-selected' in html and ">Why it moved</button>" in html
    assert 'data-pane="why" data-view="matrix"' in html
    for v in ("waterfall", "matrix", "sentences"):
        assert f'data-vw-view="{v}"' in html
    assert 'data-vw-view="matrix" aria-pressed="true"' in html
    assert "Download the working (.xlsx)" in html and "/api/var/explain.xlsx?scope=DESK%3ADESK-RATES" in html
    assert "/static/js/drishti_var-explain.js" in html and html.index("drishti_var-explorer.js") < html.index("drishti_var-explain.js")
    plain = client.get("/var?scope=BANK&tab=nonsense&view=pie").text                # not a tab, not a view: the defaults
    assert 'data-tab="path"' in plain and 'data-pane="why" data-view="waterfall"' in plain


def test_the_view_comes_from_the_persons_settings_when_the_link_names_none(client, monkeypatch):
    from routes import var_routes

    class Req:
        class state:
            settings = {"varExplainView": "sentences"}
    assert var_routes.explain_view(Req, "") == "sentences"
    assert var_routes.explain_view(Req, "matrix") == "matrix"
    Req.state.settings = {"varExplainView": "pie"}
    assert var_routes.explain_view(Req, "") == "waterfall"


def test_the_json_goes_to_the_engine_with_its_dates(client, backend, monkeypatch):
    seen = []

    async def var(verb, path, ident, /, body=None, raw=False, **params):
        seen.append((verb, path, params))
        return {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    r = client.get("/api/var/explain?scope=BANK&from=2026-09-29&to=2026-09-30&confidence=0.95&junk=1")
    assert r.status_code == 200
    assert seen[-1] == ("GET", "/explain", {"scope": "BANK", "from": "2026-09-29", "to": "2026-09-30", "confidence": "0.95"})
    assert client.get("/api/var/explain/matrix?scope=BANK").status_code == 200
    assert seen[-1][1] == "/explain/matrix" and seen[-1][2]["scope"] == "BANK"
    bad = client.get("/api/var/explain.xlsx")
    assert bad.status_code == 400 and "scope" in bad.json()["detail"]


def test_func_lists_var_explain_and_f1_has_its_section(client):
    assert any(c[0] == "VAR EXPLAIN" and c[4] == "VAR EXPLAIN BANK" for c in CONSOLE_COMMANDS)
    r = client.get("/help/context/var-explain", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].endswith("#311-why-it-moved-the-var-change-explained")


def test_a_phone_tiers_the_tab_and_its_parts(client):
    client.cookies.clear()
    client.cookies.set("drishti-device", "phone")
    try:
        html = client.get("/var?scope=BANK&tab=why").text
    finally:
        client.cookies.clear()
    assert 'data-phone-part="why" data-phone-tier="glance"' in html
    assert 'data-phone-part="why-steps" data-phone-tier="glance"' in html
