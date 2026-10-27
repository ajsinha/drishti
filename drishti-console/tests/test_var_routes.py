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

"""The VaR explorer's command words (routes/var_routes.py): VAR <word> <rest> opens /var on the scope it means; an id of the retired
stored VaR (VAR VAR-COMM, /v/var/VAR-COMM, /v/var-node/VN-<node>) opens the explorer at the matching scope with a notice; the page and
its JSON need no server to answer a bad path."""
from routes.var_routes import retired_link, var_command


def test_command_words_open_the_explorer_on_their_scope():
    assert var_command("VAR BANK".split(), "asha") == "/var?scope=BANK"
    assert var_command("VAR DESK DESK-RATES".split(), "asha") == "/var?scope=DESK%3ADESK-RATES"
    assert var_command("var desk rates".split(), "asha") == "/var?scope=DESK%3Arates"
    assert var_command("VAR LE LE-NY".split(), "asha") == "/var?scope=LE%3ALE-NY"
    assert var_command("VAR POS POS-BOOK-RATES-1~CMS".split(), "asha") == "/var?scope=POS%3APOS-BOOK-RATES-1~CMS"
    assert var_command("VAR TRD MX-1,MX-2".split(), "asha") == "/var?scope=TRD%3AMX-1%2CMX-2"
    assert var_command("VAR CPTY CP-KESTREL".split(), "asha") == "/var?scope=CPTY%3ACP-KESTREL"
    assert var_command("VAR PORT PF-USD-RATES".split(), "asha") == "/var?scope=PORT%3APF-USD-RATES"
    assert var_command("VAR SCOPE unwind".split(), "asha") == "/var?scope=BASKET%3Aasha/unwind"
    assert var_command("VAR SCOPE ola/unwind".split(), "asha") == "/var?scope=BASKET%3Aola/unwind"
    assert var_command("VAR SCOPE PF-EUR-ALL".split(), "asha") == "/var?scope=PORT%3APF-EUR-ALL"
    assert var_command("VAR SCOPE DESK:DESK-FX - TRD:MX-1".split(), "asha") == "/var?scope=DESK%3ADESK-FX%20-%20TRD%3AMX-1"
    assert var_command("VAR CAPTURE CAP-20260930-3".split(), "asha") == "/var?capture=CAP-20260930-3"
    assert var_command(["VAR"], "asha") == "/var"


def test_an_id_of_the_retired_stored_var_opens_the_explorer_at_its_scope():
    assert var_command("VAR VAR-COMM".split(), "asha") == "/var?scope=DESK%3ACOMM&from=VAR-COMM"
    assert var_command("var var-rates".split(), "asha") == "/var?scope=DESK%3ARATES&from=var-rates"
    assert var_command("VAR VN-MGMT~DESK-RATES".split(), "asha") == "/var?scope=NODE%3AMGMT~DESK-RATES&from=VN-MGMT~DESK-RATES"
    assert retired_link("var", "VAR-COMM") == "/var?scope=DESK%3ACOMM&from=VAR-COMM"
    assert retired_link("var-node", "VN-LEGAL~LE-NY") == "/var?scope=NODE%3ALEGAL~LE-NY&from=VN-LEGAL~LE-NY"
    assert retired_link("var", "SOMETHING") == "/var?scope=BANK&from=SOMETHING"          # names no scope: the bank, with the notice
    assert retired_link("trade", "VAR-COMM") is None


def test_the_old_links_redirect(client):
    r = client.get("/v/var/VAR-COMM", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/var?scope=DESK%3ACOMM&from=VAR-COMM"
    r = client.get("/v/var-node/VN-MGMT~DESK-FX", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"].startswith("/var?scope=NODE%3AMGMT~DESK-FX")


def test_other_commands_are_left_to_the_server():
    assert var_command("VAR DESK".split(), "asha") is None               # nothing named
    assert var_command("VAR SOMETHING ELSE".split(), "asha") is None
    assert var_command("TRD MX-20000001".split(), "asha") is None


def test_an_unknown_engine_path_is_refused_by_the_console(client):
    r = client.get("/api/var/nothing-here")
    assert r.status_code == 404 and r.json()["code"] == "DRS-1001"
    r = client.post("/api/var/reload", json={})
    assert r.status_code == 404


def test_the_basket_trays_hints_and_what_if_go_to_the_engine_as_the_user(client, backend, monkeypatch):
    """Construct your basket (VAR_GUIDE.md section 3.5): the tray's type-ahead (GET /api/var/hints) and its pre-deal what-if
    (POST /api/var/what-if, with the page's business date) are the engine's; the console passes them on and nothing else."""
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        return [{"type": "PORT", "id": "PF-USD-RATES", "scope": "PORT:PF-USD-RATES", "trades": 918, "var": 28968862}] if path == "/hints" else {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    r = client.get("/api/var/hints?q=pf%20usd&limit=8")
    assert r.status_code == 200 and r.json()[0]["scope"] == "PORT:PF-USD-RATES"
    assert seen[-1] == ("GET", "/hints", None, {"q": "pf usd", "limit": "8"})
    r = client.post("/api/var/what-if?asOf=2026-09-30", json={"scope": "BASKET:asha/unwind", "items": [{"copyOf": "MX-30000121", "factor": -1}]})
    assert r.status_code == 200
    assert seen[-1][:3] == ("POST", "/what-if", {"scope": "BASKET:asha/unwind", "items": [{"copyOf": "MX-30000121", "factor": -1}]})
    assert seen[-1][3] == {"asOf": "2026-09-30"}


def test_the_top_bar_has_the_basket_button_and_alt_b(client):
    html = client.get("/t").text
    assert "data-basket-btn" in html and 'aria-keyshortcuts="Alt+B"' in html and "Basket" in html
    assert "/static/js/var-basket.js" in html and "/static/css/var-basket.css" in html


def test_in_a_currency_the_command_opens_the_page_in_it():
    """``VAR ... IN EUR`` (docs/guides/VAR_GUIDE.md section 15.11): the page in that reporting currency, its link carrying ccy=EUR."""
    assert var_command("VAR DESK DESK-RATES IN EUR".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&ccy=EUR"
    assert var_command("var bank in gbp".split(), "asha") == "/var?scope=BANK&ccy=GBP"
    assert var_command("VAR TRD MX-1 IN CAD <GO>".split(), "asha") == "/var?scope=TRD%3AMX-1&ccy=CAD"
    assert var_command("VAR CAPTURE CAP-1 IN JPY".split(), "asha") == "/var?capture=CAP-1&ccy=JPY"
    assert var_command("VAR IN EUR".split(), "asha") == "/var?ccy=EUR"
    assert var_command("VAR SCOPE DESK:DESK-FX - TRD:MX-1 IN EUR".split(), "asha") == "/var?scope=DESK%3ADESK-FX%20-%20TRD%3AMX-1&ccy=EUR"
    assert var_command("VAR DESK IN EUR".split(), "asha") is None          # nothing named before IN
    assert var_command("VAR DESK DESK-RATES IN EURO".split(), "asha") == "/var?scope=DESK%3ADESK-RATES-IN-EURO"   # not a currency: words of the id


def test_the_page_has_the_reporting_currency_picker_and_the_tab(client):
    html = client.get("/var?scope=DESK:DESK-RATES&ccy=eur").text
    assert 'data-ccy="EUR"' in html
    assert 'aria-label="Reporting currency"' in html and "data-vx-ccy" in html
    assert 'data-tab="ccy"' in html and "In other currencies" in html and "data-vx-ccytable" in html
    assert "<th>Trade currency</th>" in html                               # the contributions and the worst scenarios list it
    assert 'data-ccy=""' in client.get("/var?scope=BANK&ccy=12").text       # not a currency: the session's, else USD
    assert "data-vx-intraday" in html                                       # the intraday badge (filled when the figures include updates)


def test_every_answer_passes_the_reporting_currency_to_the_engine(client, backend, monkeypatch):
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        return {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    for url in ("/api/var/scope?scope=BANK&ccy=EUR", "/api/var/scope/path?scope=BANK&page=0&ccy=EUR", "/api/var/tree?hierarchy=MGMT&ccy=EUR",
                "/api/var/scope/contributions?scope=BANK&by=trade&ccy=EUR", "/api/var/hints?q=x&ccy=EUR"):
        assert client.get(url).status_code == 200
        assert seen[-1][3]["ccy"] == "EUR", url
    # the tab "In other currencies" and the picker's list are the engine's too
    assert client.get("/api/var/scope/currencies?scope=DESK%3ADESK-RATES&ccy=CAD").status_code == 200
    assert seen[-1][1] == "/scope/currencies" and seen[-1][3] == {"scope": "DESK:DESK-RATES", "ccy": "CAD"}
    assert client.get("/api/var/currencies").status_code == 200 and seen[-1][1] == "/currencies"
    # a capture in the page's currency
    assert client.post("/api/var/captures?ccy=EUR", json={"scope": "BANK"}).status_code == 200
    assert seen[-1][3] == {"asOf": "", "ccy": "EUR"}
