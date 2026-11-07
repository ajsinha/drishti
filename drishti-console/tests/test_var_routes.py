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


def test_the_basket_is_on_the_var_page_not_in_the_top_bar(client):
    """2.9.4: the top bar has no Basket button; the VaR explorer offers "Construct your basket" (Alt+B there) and loads the tray."""
    html = client.get("/t").text
    assert "data-basket-btn" not in html and "/static/js/drishti_var-basket.js" not in html and "/static/css/drishti_var-basket.css" not in html
    page = client.get("/var?scope=BANK").text
    assert "data-basket-btn" not in page
    assert "data-vx-basket-open" in page and 'aria-keyshortcuts="Alt+B"' in page and "data-basket-count" in page
    assert "/static/js/drishti_var-basket.js" in page and "/static/css/drishti_var-basket.css" in page


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
    js = client.get("/static/js/drishti_var-explorer.js").text               # the tables are grids: their headings are the columns'
    assert js.count('header: "Trade currency"') >= 3                        # the contributions, the worst scenarios and a scenario's parts
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


def test_apply_now_of_a_pv_stream_goes_to_the_engine(client, backend, monkeypatch):
    """The var-pv-stream connector's Apply now (VAR_GUIDE.md section 8.4): the console passes the POST on as the user (the engine
    checks the administrator), and the streams' status; any other path under intraday/ is refused."""
    seen = []

    async def var(method, path, ident, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        return {"status": "applied", "rows": 3, "batchId": "K-2026-09-30-1"}
    monkeypatch.setattr(backend, "var", var, raising=False)
    r = client.post("/api/var/intraday/streams/pv-updates/apply")
    assert r.status_code == 200 and r.json()["rows"] == 3
    assert seen[-1][:2] == ("POST", "/intraday/streams/pv-updates/apply")
    assert client.get("/api/var/intraday/streams").status_code == 200 and seen[-1][1] == "/intraday/streams"
    assert client.post("/api/var/intraday/other/apply").status_code == 404


def test_no_confidence_is_hard_coded_the_labels_follow_the_answer(client):
    """VaR at 99% or 95% (drishti.var.confidence, VAR_GUIDE.md section 18.5): the page, the explorer and the basket label the figures
    from the answer's confidence and esConfidence, never a fixed 99% or 97.5%."""
    from pathlib import Path
    html = client.get("/var?scope=BANK").text
    assert "VaR 99%" not in html and "ES 97.5%" not in html
    js = Path(__file__).resolve().parents[1] / "web" / "static" / "js"
    for f in ("drishti_var-explorer.js", "drishti_var-basket.js"):
        text = (js / f).read_text(encoding="utf-8")
        assert "99%" not in text and "97.5%" not in text, f
    assert "d.confidence" in (js / "drishti_var-explorer.js").read_text(encoding="utf-8")
    assert "w.confidence" in (js / "drishti_var-basket.js").read_text(encoding="utf-8")


def test_any_configured_hierarchy_is_kept_in_the_page_and_a_configured_word_opens_by_scope(client):
    """2.9.5 (docs/guides/VAR_GUIDE.md section 15.14): the page takes any well-formed hierarchy id (LL), upper case, and fills its
    selector from the engine's tree (hierarchyInfo); anything else is MGMT. A configured word's node opens as scope text."""
    html = client.get("/var?scope=BANK&hierarchy=ll").text
    assert 'data-hierarchy="LL"' in html
    assert 'data-vx-hierarchy' in html and '<option value="LL">LL</option>' in html
    assert 'data-hierarchy="MGMT"' in client.get("/var?scope=BANK&hierarchy=ll-1%3Cx%3E").text
    assert var_command("VAR SCOPE LL:LL-0003".split(), "asha") == "/var?scope=LL%3ALL-0003"
    assert var_command("VAR NODE LL~LL-0003/DESK-RATES".split(), "asha") == "/var?scope=NODE%3ALL~LL-0003/DESK-RATES"
    js = client.get("/static/js/drishti_var-explorer.js").text
    assert "/^NODE:(MGMT|LEGAL)~/" not in js and "hierarchyInfo" in js and "setHierarchy" in js


def test_the_lens_in_a_command_opens_the_page_with_it():
    """2.9.7 (docs/guides/VAR_GUIDE.md section 18.6): ``10D`` with ``SUM`` or ``SQRT`` (none: the setting's), a confidence and ``ES <q>``,
    in any order and beside ``IN <currency>``; never taken from a trade id that is a number."""
    assert var_command("VAR BANK 10D".split(), "asha") == "/var?scope=BANK&h=10d"
    assert var_command("VAR BANK 10D SUM".split(), "asha") == "/var?scope=BANK&h=10d&m=sum"
    assert var_command("var bank 10d sqrt".split(), "asha") == "/var?scope=BANK&h=10d&m=sqrt10"
    assert var_command("VAR BANK 95".split(), "asha") == "/var?scope=BANK&q=0.95"
    assert var_command("VAR BANK 10D SUM 97.5 <GO>".split(), "asha") == "/var?scope=BANK&h=10d&m=sum&q=0.975"
    assert var_command("VAR BANK ES 95 99.5".split(), "asha") == "/var?scope=BANK&q=0.995&esq=0.95"
    assert var_command("VAR BANK SUM".split(), "asha") == "/var?scope=BANK&h=10d&m=sum"       # a method names the 10 days
    assert var_command("VAR BANK 1D 99".split(), "asha") == "/var?scope=BANK&q=0.99"
    assert var_command("VAR DESK DESK-RATES 10D IN EUR".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&h=10d&ccy=EUR"
    assert var_command("VAR BANK IN EUR 10D".split(), "asha") == "/var?scope=BANK&h=10d&ccy=EUR"
    assert var_command("VAR TRD 99".split(), "asha") == "/var?scope=TRD%3A99"                 # a trade id, never a confidence
    assert var_command("VAR TRD 99 95".split(), "asha") == "/var?scope=TRD%3A99&q=0.95"


def test_the_type_ahead_offers_the_horizons_and_confidences_once_a_scope_is_typed():
    from routes.var_routes import var_suggestions
    got = [s["complete"] for s in var_suggestions("VAR BANK")]
    assert got[:3] == ["VAR BANK 10D", "VAR BANK 10D SUM", "VAR BANK 10D SQRT"] and "VAR BANK 95" in got and "VAR BANK ES 97.5" in got
    assert [s["complete"] for s in var_suggestions("VAR DESK DESK-RATES 10D")] == ["VAR DESK DESK-RATES 10D", "VAR DESK DESK-RATES 10D SUM",
                                                                                 "VAR DESK DESK-RATES 10D SQRT"]
    assert var_suggestions("VAR DESK") == [] and var_suggestions("VAR") == [] and var_suggestions("TRD 1") == []


def test_the_page_has_the_lens_pickers_and_keeps_the_lens_of_its_link(client, backend, monkeypatch):
    html = client.get("/var?scope=BANK&h=10d&m=sum&q=0.95&esq=0.975").text
    assert 'data-h="10d"' in html and 'data-m="sum"' in html and 'data-q="0.95"' in html and 'data-esq="0.975"' in html
    assert 'aria-label="Horizon"' in html and '<option value="10d:sum">10-day (sum)</option>' in html and '10-day (√10)' in html
    assert 'aria-label="VaR confidence"' in html and 'aria-label="ES confidence"' in html and "data-vx-method" in html
    bad = client.get("/var?scope=BANK&h=5d&m=full&q=x").text                # not a lens: the default
    assert 'data-h=""' in bad and 'data-m=""' in bad and 'data-q=""' in bad
    seen = []

    async def var(verb, path, ident, /, body=None, raw=False, **params):      # positional only: the lens's method is a parameter
        seen.append((verb, path, body, params))
        return {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    for url in ("/api/var/scope", "/api/var/scope/path", "/api/var/scope/history", "/api/var/scope/contributions", "/api/var/scope/currencies",
                "/api/var/tree"):
        assert client.get(url + "?scope=BANK&horizon=10d&method=sum&confidence=0.95&esConfidence=0.975").status_code == 200
        assert {k: seen[-1][3].get(k) for k in ("horizon", "method", "confidence", "esConfidence")} == \
            {"horizon": "10d", "method": "sum", "confidence": "0.95", "esConfidence": "0.975"}, url
    assert client.get("/api/var/hints?q=x&horizon=10d").status_code == 200 and "horizon" not in seen[-1][3]   # the tray's hints stay 1-day
    assert client.post("/api/var/captures", json={"scope": "BANK", "horizon": "10d", "method": "sum"}).status_code == 200
    assert seen[-1][2]["horizon"] == "10d"                                    # a capture through the page's lens


def test_stressed_var_in_a_command_the_page_and_every_answer(client, backend, monkeypatch):
    """Stressed VaR (docs/guides/VAR_GUIDE.md section 18.8): ``SVAR`` in a command, ``set=stressed`` in the link, the Normal / Stressed
    picker beside the Horizon, and ``set`` passed to every answer that takes a lens."""
    assert var_command("VAR BANK SVAR".split(), "asha") == "/var?scope=BANK&set=stressed"
    assert var_command("VAR BANK SVAR 10D SUM".split(), "asha") == "/var?scope=BANK&h=10d&m=sum&set=stressed"
    assert var_command("VAR DESK DESK-RATES 10D SQRT SVAR".split(), "asha") == "/var?scope=DESK%3ADESK-RATES&h=10d&m=sqrt10&set=stressed"
    from routes.var_routes import var_suggestions
    assert "VAR BANK SVAR" in [s["complete"] for s in var_suggestions("VAR BANK")]
    html = client.get("/var?scope=BANK&set=stressed").text
    assert 'data-set="stressed"' in html and 'aria-label="Normal or stressed VaR"' in html and '<option value="stressed" selected>' in html
    assert 'data-set=""' in client.get("/var?scope=BANK&set=crisis").text                  # not a set: Normal
    seen = []

    async def var(verb, path, ident, /, body=None, raw=False, **params):
        seen.append(params)
        return {"ok": 1}
    monkeypatch.setattr(backend, "var", var, raising=False)
    for url in ("/api/var/scope", "/api/var/scope/path", "/api/var/scope/history", "/api/var/scope/contributions", "/api/var/tree"):
        assert client.get(url + "?scope=BANK&set=stressed").status_code == 200
        assert seen[-1].get("set") == "stressed", url


def test_deleting_a_basket_goes_to_the_engine_with_its_reason_and_version(client, backend, monkeypatch):
    """Delete on a saved basket (VAR_GUIDE.md section 3.5): the console passes DELETE /baskets/<owner>/<name> on as the user, with the
    optional reason and the version the dialog showed; the engine decides who may (the owner, or an administrator) and says no in words."""
    from core.backend import BackendError
    seen = []

    async def var(method, path, ident, /, body=None, raw=False, **params):
        seen.append((method, path, params))
        if path.endswith("/nobody/gone"):
            raise BackendError(404, "DRS-1003", "no basket nobody/gone of yours")
        return None
    monkeypatch.setattr(backend, "var", var, raising=False)
    r = client.delete("/api/var/baskets/rita/rates-hedges?reason=left%20the%20desk&version=2")
    assert r.status_code == 204
    assert seen[-1] == ("DELETE", "/baskets/rita/rates-hedges", {"reason": "left the desk", "version": "2"})
    assert client.delete("/api/var/baskets/rita/x").status_code == 204 and seen[-1][2] == {}
    r = client.delete("/api/var/baskets/nobody/gone")
    assert r.status_code == 404 and "of yours" in r.json()["detail"]


def test_the_basket_sheet_goes_to_the_engine_as_lists_and_comes_back_as_its_answer(client, backend, monkeypatch):
    """Construct your basket is a sheet (VAR_GUIDE.md section 3.5): its columns go to the engine as lists (POST /basket/sheet, with the
    page's date and currency), a scope is laid into it by the engine (GET /basket/sheet?scope=), the workbook is the engine's (raw
    bytes passed on); the console checks and computes nothing."""
    seen = []
    sheet = {"wholeBank": True, "op": ["+", "-"], "TRD": ["MX-1", ""], "DESK": ["", "DESK-COMM"]}

    async def var(method, path, ident, /, body=None, raw=False, **params):
        seen.append((method, path, body, params))
        if raw:
            return b"PK\x03\x04", {"content-disposition": 'attachment; filename="mix.xlsx"'}
        return {"found": 2, "notFound": 0, "cells": [], "var": 1.0} if method == "POST" else {"sheet": sheet}
    monkeypatch.setattr(backend, "var", var, raising=False)
    r = client.post("/api/var/basket/sheet?asOf=2026-09-30&ccy=EUR", json=sheet)
    assert r.status_code == 200 and r.json()["found"] == 2
    assert seen[-1] == ("POST", "/basket/sheet", sheet, {"asOf": "2026-09-30", "ccy": "EUR"})
    r = client.get("/api/var/basket/sheet?scope=BASKET:rita/mix")
    assert r.status_code == 200 and r.json()["sheet"] == sheet
    assert seen[-1][:2] == ("GET", "/basket/sheet") and seen[-1][3]["scope"] == "BASKET:rita/mix"
    r = client.post("/api/var/basket/sheet.xlsx", json={"name": "mix", "sheet": sheet})
    assert r.status_code == 200 and r.content.startswith(b"PK") and "mix.xlsx" in r.headers["content-disposition"]
    assert seen[-1][:3] == ("POST", "/basket/sheet.xlsx", {"name": "mix", "sheet": sheet})


def test_the_var_page_draws_the_basket_as_a_sheet_with_edit(client):
    """The chip tray is retired: the page loads the sheet's grid and the sheet; a saved basket has Edit, a portfolio Edit a copy."""
    page = client.get("/var?scope=BANK").text
    assert page.index("/static/js/drishti_var-basket-sheet.js") < page.index("/static/js/drishti_var-basket.js")
    js = client.get("/static/js/drishti_var-basket.js").text
    assert "basket/sheet" in js and "Start from the whole bank" in js and "Download as .xlsx" in js and "vb-chip" not in js
    vx = client.get("/static/js/drishti_var-explorer.js").text
    assert "Edit a copy" in vx and "B.edit(scope)" in vx


def test_the_var_page_offers_delete_through_the_confirm_dialog(client):
    page = client.get("/var?scope=BANK").text
    assert "/static/js/drishti_scope-delete.js" in page and 'data-admin="true"' in page          # the test console's user is an administrator
    js = client.get("/static/js/drishti_var-explorer.js").text
    assert "Drishti.ScopeDelete.ask" in js and "b.owner === S.user || S.admin" in js
    sd = client.get("/static/js/drishti_scope-delete.js").text
    assert "showModal" in sd and "version" in sd and "reason" in sd


def test_admin_ad_hoc_scopes_lists_every_persons_baskets_with_delete(client, backend, monkeypatch):
    """Admin -> Ad-hoc scopes: every person's baskets from GET /admin/baskets (filtered by owner), each row with Delete; in the tabs."""
    seen = []

    async def var(method, path, ident, /, body=None, raw=False, **params):
        seen.append((method, path, params))
        return [{"id": "rita/unwind", "owner": "rita", "name": "Unwind candidates", "type": "BASKET", "scope": "TRD:T1,T2",
                 "sharedWith": ["ola", "role:risk"], "createdAt": "2026-10-01T09:00:00Z", "updatedAt": "2026-10-02T09:00:00Z",
                 "version": 3, "bytes": 312}]
    monkeypatch.setattr(backend, "var", var, raising=False)
    page = client.get("/admin/scopes?owner=rit")
    assert page.status_code == 200
    assert seen[-1] == ("GET", "/admin/baskets", {"owner": "rit"})
    html = page.text
    assert 'data-basket="rita/unwind"' in html and 'data-version="3"' in html and 'data-shared="ola,role:risk"' in html
    assert "Unwind candidates" in html and "ola, role:risk" in html and "312 B" in html and "data-scope-delete" in html
    assert "/static/js/drishti_scope-delete.js" in html and "/static/js/drishti_admin-scopes.js" in html
    assert 'href="/admin/scopes"' in client.get("/admin/users").text


def test_admin_ad_hoc_scopes_says_when_the_engine_is_off(client, backend, monkeypatch):
    from core.backend import BackendError

    async def var(method, path, ident, /, body=None, raw=False, **params):
        raise BackendError(503, "DRS-5204", "the VaR engine is off")
    monkeypatch.setattr(backend, "var", var, raising=False)
    page = client.get("/admin/scopes")
    assert page.status_code == 200 and "the VaR engine is off" in page.text
