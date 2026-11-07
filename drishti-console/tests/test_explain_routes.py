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
"""One Explain for every number (docs/architecture/SHARED_SCREENS.md section 3) in the console: the proxy passes the request to the
server as the person, the page renders the request it was given, VAR EXPLAIN opens the page at Why it moved, the VaR and exposure pages
carry Explain and no longer their own panels (How calculated, the ? pop-up, the Why it moved tab, the provenance box), the old addresses
are gone, F1 has the guide's section."""
from __future__ import annotations

import json
from pathlib import Path

from conftest import CONSOLE
from routes.functions_routes import CONSOLE_COMMANDS
from routes.var_routes import explain_command, var_command, var_suggestions

WEB = Path(__file__).resolve().parents[1] / "web"


def test_var_explain_opens_the_explain_page_at_why_it_moved():
    page = "/explain?kind=var&measure=var&scope="
    assert var_command("VAR EXPLAIN BANK".split(), "asha") == page + "BANK&tab=why"
    assert var_command("VAR EXPLAIN".split(), "asha") == page + "BANK&tab=why"
    assert var_command("VAR EXPLAIN DESK DESK-RATES".split(), "asha") == page + "DESK%3ADESK-RATES&tab=why"
    assert var_command("VAR DESK DESK-RATES EXPLAIN".split(), "asha") == page + "DESK%3ADESK-RATES&tab=why"
    assert var_command("VAR BANK EXPLAIN <GO>".split(), "asha") == page + "BANK&tab=why"
    assert var_command("VAR EXPLAIN NODE:MGMT~DESK-FX".split(), "asha") == page + "NODE%3AMGMT~DESK-FX&tab=why"
    assert var_command("VAR EXPLAIN BANK 2026-06-30".split(), "asha") == page + "BANK&from=2026-06-30&tab=why"
    assert var_command("VAR EXPLAIN DESK DESK-RATES 2026-09-29 2026-09-30".split(), "asha") == \
        page + "DESK%3ADESK-RATES&from=2026-09-29&to=2026-09-30&tab=why"
    assert explain_command("VAR BANK".split(), "asha") is None                       # not asked: the explorer as before
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"
    assert explain_command("TRD EXPLAIN".split(), "asha") is None
    assert "VAR BANK EXPLAIN" in [s["complete"] for s in var_suggestions("VAR BANK")]


def test_the_var_page_carries_explain_and_none_of_the_old_panels(client):
    html = client.get("/var?scope=DESK:DESK-RATES&tab=why&how=1").text
    assert 'data-tab="path"' in html                                  # tab=why is no tab any more: the default
    assert 'data-tab="why"' not in html and 'data-pane="why"' not in html and "data-vw-" not in html
    assert "data-vx-drawer" not in html and "data-vx-how" not in html and "data-vx-prov" not in html
    assert "drishti_var-explain.js" not in html
    assert "data-vx-explain data-explain-primary" in html and ">Explain ›</button>" in html
    assert "data-vx-whatif" in html                                   # the drawer's What-if moved to the toolbar
    assert "/static/js/drishti_explain.js" in html and "/static/css/drishti_explain.css" in html
    assert html.index("drishti_explain.js") < html.index("drishti_var-explorer.js")   # the component is there before the page binds to it
    ex = client.get("/exposure?scope=BANK").text
    assert "data-xx-prov" not in ex and "How this number was built" not in ex and "data-xx-explain data-explain-primary" in ex


def test_the_old_panels_scripts_and_addresses_are_gone(client):
    assert not (WEB / "static" / "js" / "drishti_var-explain.js").exists()
    for name in ("drishti_var-layout.js", "drishti_var-explorer.js", "drishti_var-parametric.js", "drishti_exposure-explorer.js",
                 "drishti_mr-lens.js"):
        text = (WEB / "static" / "js" / name).read_text(encoding="utf-8")
        for old in ("renderProv", "openHow", "closeHow", "vp-pop", "/explain?", "varExplain"):
            assert old not in text, f"{name} still has {old}"
    assert client.get("/api/var/explain?scope=BANK").status_code == 404
    assert client.get("/api/var/explain/matrix?scope=BANK").status_code == 404
    assert client.get("/api/pivot/mr/explain?scope=BANK").status_code in (404, 405)


def test_the_proxy_asks_the_server_as_the_person(client, backend, monkeypatch):
    seen = []

    async def explain(verb, path, ident, body=None, **params):
        seen.append((verb, path, params, body))
        return {"kind": "var", "title": "VaR 99%, 1 day", "text": "1,234"}
    monkeypatch.setattr(backend, "explain_number", explain, raising=False)
    r = client.get("/api/explain?kind=var&measure=es&scope=BANK&part=preview&tab=why")
    assert r.status_code == 200 and r.headers["cache-control"] == "no-store"
    assert seen[-1] == ("GET", "", {"kind": "var", "measure": "es", "scope": "BANK", "part": "preview"}, None)   # tab is the page's own
    assert client.get("/api/explain/kinds").status_code == 200 and seen[-1][1] == "/kinds"
    body = {"kind": "pivot", "view": {"source": "trade"}, "by": "DESK"}
    r = client.post("/api/explain", json=body)
    assert r.status_code == 200 and seen[-1][0] == "POST" and seen[-1][3] == body
    assert client.post("/api/explain", json=[1, 2]).status_code == 400


def test_the_page_renders_the_request_it_was_given(client):
    html = client.get("/explain?kind=var&measure=var&scope=DESK:RATES&from=2026-09-29&tab=why").text
    assert "data-explain-page" in html and "data-dx-grid" in html and "data-dx-print" in html and "data-dx-back" in html
    req = json.loads(html.split('data-explain-request="')[1].split('"')[0].replace("&#34;", '"').replace("&quot;", '"'))
    assert req == {"kind": "var", "measure": "var", "scope": "DESK:RATES", "from": "2026-09-29"}
    assert 'data-explain-tab="why"' in html
    assert 'data-screen="explain"' in html


def test_the_page_carries_a_pivot_cells_view_sent_in_the_body(client):
    """Open as a page from a pivot cell: the view the drawer posted rides along as one JSON parameter, and the page asks with it."""
    import html as htmllib
    import urllib.parse
    view = {"source": "trade", "rows": ["DESK"], "values": ["sum(MTM)"], "filters": {"BOOK": ["B & C"]}}
    body = {"view": view, "from": {"DESK": "FX"}, "to": {"DESK": "RATES"}, "by": "BOOK"}
    page = client.get("/explain?" + urllib.parse.urlencode({"kind": "pivot", "measure": "cell", "tab": "why", "body": json.dumps(body)}))
    assert page.status_code == 200
    req = json.loads(htmllib.unescape(page.text.split('data-explain-request="')[1].split('"')[0]))
    assert req == {"kind": "pivot", "measure": "cell", "body": body}
    js = (CONSOLE / "web/static/js/drishti_explain.js").read_text(encoding="utf-8")
    assert 'if (req.body) q.set("body", JSON.stringify(req.body));' in js        # the drawer's link carries it
    for bad in ("[1, 2]", "not json"):
        assert client.get("/explain?" + urllib.parse.urlencode({"kind": "pivot", "body": bad})).status_code == 400


def test_func_lists_var_explain_and_f1_has_the_explain_section(client):
    assert any(c[0] == "VAR EXPLAIN" and c[4] == "VAR EXPLAIN BANK" for c in CONSOLE_COMMANDS)
    r = client.get("/help/context/explain", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].endswith("user-guide#explain-one-for-every-number")


def test_a_phone_has_no_old_explain_parts(client):
    from core.phone import PAGES

    for page in ("var", "exposure"):
        assert not [p for p in PAGES[page] if p.startswith("why") or p == "prov"], page
