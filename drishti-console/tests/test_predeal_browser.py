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

"""Counterparties → Pre-deal check in Chromium (EXPOSURE_GUIDE.md section 18.9): the console over a stand-in server (port 17873) that
answers the exposure tree, a netting set's trades and the pre-deal service as the server writes them (ExposurePreDeal: the limit and
its tenor buckets within, near or over, the exposure before, with and incremental with the profile, the IMM EAD), and the one Explain
of kind predeal. The page draws what the server sent and computes nothing:

- PREDEAL typed with a notional checks at once: within (approve), near (approve, a bucket at 90% of its limit) and over (decline),
  each with its decision, lamp, status pill, the limit and bucket rows, the tiles and the what-if (the overlay, the incremental bars,
  the figures current and with the trade, the table by tenor); a proxy refers;
- the form: counterparty, netting set and a booked trade from the server's lists, the notional as money, Check;
- Explain › opens the one Explain's drawer with the server's formula;
- at phone width the page does not scroll sideways and the verdict comes first;
- with the banking module off the page, its API, its word and its script are gone.

Skipped when Playwright or its Chromium is missing."""
from __future__ import annotations

import threading
import time
from urllib.parse import quote, unquote

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from core.backend import BackendError  # noqa: E402
import engine_fakes  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture
from scratch_ports import worker_port  # noqa: E402

PORT = worker_port(17873)
CP = "CP-ALDERSHOT"
SETS = {"NS-ALDERSHOT-NY": 300_000_000, "NS-ALDERSHOT-LDN": 90_000_000}
TENORS = [("1M", 0.083), ("6M", 0.5), ("1Y", 1.0), ("5Y", 5.0)]
LIMIT = 420_000_000


def _profile(before: float, add: float) -> list[dict]:
    rows = []
    for i, (tenor, t) in enumerate(TENORS):
        pb = before * (1 - 0.2 * i)
        rows.append({"tenor": tenor, "date": "2026-10-30", "t": t, "eeBase": round(pb * 0.3), "ee": round((pb + add) * 0.3), "pfeBase": round(pb),
                     "pfe": round(pb + add * (1 - 0.2 * i)), "incrementalEe": round(add * 0.3), "incrementalPfe": round(add * (1 - 0.2 * i))})
    return rows


def answer(command: str) -> dict:
    """The pre-deal answer for a command, shaped as ExposurePreDeal writes it: 5M within, 40M near (the 0-1Y bucket at 90%), 100M over."""
    w = command.split()
    exact = "LIKE" in w
    notional = next((x for x in w if x.endswith("M") and x[:-1].isdigit()), "5M")
    add = {"5M": 2_000_000, "40M": 15_000_000, "100M": 30_000_000}.get(notional, 2_000_000)
    before = 380_000_000
    after = before + add
    b0, b1 = 300_000_000, {"5M": 305_000_000, "40M": 342_000_000, "100M": 365_000_000}.get(notional, 305_000_000)
    bucket_limit = 380_000_000 if notional != "100M" else 350_000_000
    bstat = "breach" if b1 > bucket_limit else "near" if b1 / bucket_limit >= 0.8 else "within"
    status = "breach" if after > LIMIT or bstat == "breach" else "near" if after / LIMIT >= 0.8 and notional != "5M" or bstat == "near" else "within"
    if notional == "5M":
        bstat, status = "within", "within"
    buckets = [{"bucket": "0-1Y", "limit": bucket_limit, "before": b0, "after": b1, "increment": b1 - b0, "useBefore": b0 / bucket_limit,
                "useAfter": b1 / bucket_limit, "headroomAfter": bucket_limit - b1, "status": bstat},
               {"bucket": "1Y+", "limit": LIMIT, "before": 200_000_000, "after": 201_000_000, "increment": 1_000_000, "useBefore": 200 / 420,
                "useAfter": 201 / 420, "headroomAfter": LIMIT - 201_000_000, "status": "within"}]
    lim = {"id": "LIM-ALDERSHOT", "measure": "PFE 95 peak", "confidence": 0.95, "limit": LIMIT, "before": before, "after": after, "increment": add,
           "useBefore": before / LIMIT, "useAfter": after / LIMIT, "headroomBefore": LIMIT - before, "headroomAfter": LIMIT - after, "near": 0.8,
           "buckets": buckets, "status": status}
    if not exact:
        decision, reasons = "refer", ["a proxy answer (method addon) only refers, never approves: give a template (LIKE <trade>) for the exact answer"]
    elif status == "breach":
        decision, reasons = "decline", ["exact: with the trade, the 0-1Y bucket's peak is 365000000, over its limit 350000000"]
    else:
        decision = "approve"
        reasons = ["exact: with the trade, PFE 95 peak is %d of the limit %d (%.1f%%)%s" % (after, LIMIT, after / LIMIT * 100,
                   "; near the limit (80.0% or more of it: the 0-1Y bucket at 90.0% of its limit)" if status == "near" else "")]
    base = {"epe": 120_000_000, "eepe": 130_000_000, "peakPfe": before, "peakEe": 140_000_000, "ce": 60_000_000}
    with_ = {k: v + add // 2 for k, v in base.items()}
    with_["peakPfe"] = after
    exp = {"base": dict(base), "with": dict(with_), "incremental": {k: with_[k] - base[k] for k in base}}
    exp["incremental"]["profile"] = _profile(before, add)
    if not exact:                                       # the proxy (addon) computes EPE, peak PFE and peak EE: no EEPE, no CE
        for part in ("base", "with", "incremental"):
            exp[part].pop("eepe", None)
            exp[part].pop("ce", None)
    if exact:
        exp["base"]["ead"], exp["with"]["ead"] = round(1.4 * base["eepe"]), round(1.4 * with_["eepe"])
        exp["incremental"]["ead"], exp["alpha"] = exp["with"]["ead"] - exp["base"]["ead"], 1.4
    text = " ".join(w)
    return {"checkId": "PDC-TEST", "command": text, "counterparty": CP, "scope": "CPTY:" + CP, "label": "Aldershot Pension Trust",
            "businessDate": "2026-09-30", "runId": "XR-2026-09-30-01", "confidence": 0.95, "audited": True, "decision": decision,
            "reason": reasons[0], "reasons": reasons,
            "method": {"id": "paths" if exact else "addon", "exact": exact, "name": "exact: the template's paths" if exact else "proxy: an SA-CCR-style add-on",
                       "text": "copied and scaled" if exact else "a netting set of its own"},
            "template": {"tradeId": "T-1", "product": "IR_SWAP", "assetClass": "Interest Rate", "factor": 0.5, "side": "same", "candidateNotional": 5e6}
            if exact else {"tradeId": None, "assetClass": "RATES", "maturity": "2031-09-30", "candidateNotional": 1e7, "side": "same"},
            "nettingSet": {"id": "NS-ALDERSHOT-NY", "how": "the template's", "sets": list(SETS)}, "limit": lim, "exposure": exp,
            "saCcr": {"status": "under-construction", "release": "2.9.11.k", "note": "SA-CCR's incremental EAD: Under construction"},
            "flags": ["exact" if exact else "proxy"], "engine": "exposure (Java)"}


class PreDealBackend(FakeBackend):
    engine = engine_fakes.method

    def __init__(self):
        super().__init__()
        self.checks = []
        self.explained = []

    async def exposure(self, method, path, ident, body=None, raw=False, **params):
        if method == "GET" and path == "/tree":
            kids = [{"scope": "NSET:" + s, "id": s, "peakPfe": p, "csa": "CSA"} for s, p in SETS.items()]
            return {"businessDate": "2026-09-30", "runId": "XR-2026-09-30-01", "confidence": 0.95, "groups": [{"id": "GRP-ALDERSHOT", "children": [
                {"scope": "CPTY:" + CP, "id": CP, "label": "Aldershot Pension Trust", "peakPfe": 380_000_000, "children": kids,
                 "limit": {"limit": LIMIT, "use": 380 / 420}},
                {"scope": "CPTY:CP-NORTHPLAINS", "id": "CP-NORTHPLAINS", "label": "Northplains", "peakPfe": 50_000_000,
                 "children": [{"scope": "NSET:NS-NORTHPLAINS", "id": "NS-NORTHPLAINS", "peakPfe": 50_000_000}]}]}]}
        if method == "GET" and path == "/scope":
            return {"scope": params.get("scope"), "peakPfeTenor": "6M"}
        if method == "GET" and path == "/scope/contributions":
            s = params.get("scope", "")
            return {"rows": [{"key": "T-1" if "NY" in s else "T-9", "product": "IR_SWAP", "notional": 10_000_000, "maturity": "2031-09-30"},
                             {"key": "T-2" if "NY" in s else "T-8", "product": "FX_FWD", "notional": 25_000_000, "maturity": "2027-03-31"}]}
        if method == "POST" and path == "/predeal":
            self.checks.append(body)
            if body.get("command"):
                return answer(body["command"])
            if not body.get("counterparty"):
                raise BackendError(400, "DRS-5440", "a pre-deal check names its counterparty")
            n = body.get("notional")
            words = ["PREDEAL", body["counterparty"]] + (["LIKE", body["like"]] if body.get("like") else [body.get("assetClass") or "RATES"])
            words += [f"{int(n / 1e6)}M"] if n else []
            return answer(" ".join(words))
        raise BackendError(404, "DRS-1001", f"no exposure endpoint {path}")

    async def explain_number(self, method, path, ident, body=None, **params):
        if path == "/kinds":
            return {"kinds": [{"kind": "predeal", "measures": ["decision", "headroom", "increment", "ead"]}]}
        self.explained.append(dict(params))
        a = answer(params.get("command", "PREDEAL CP-ALDERSHOT LIKE T-1 5M"))
        lim = a["limit"]
        return {"kind": "predeal", "measure": params.get("measure", "decision"), "subject": "CPTY:" + CP, "title": "Pre-deal decision: " + a["command"],
                "value": lim["after"], "text": f"{lim['after']:,}", "unit": "USD", "asOf": "2026-09-30", "stored": False, "part": params.get("part", "all"),
                "origin": "computed by the exposure engine in the server for you", "preview": a["decision"].upper() + ": " + a["reason"], "facts": [],
                "formula": {"lines": [f"use = with ÷ limit = {lim['after']:,} ÷ {LIMIT:,}", f"headroom = limit − with = {lim['headroomAfter']:,}"]},
                "inputs": {"rows": [{"label": "Command", "value": a["command"]}], "lineage": []},
                "why": {"available": False, "note": "a pre-deal check is of one moment"}, "workbook": {"available": False, "links": []},
                "checks": [{"ok": True, "text": a["reason"]}], "links": []}


@pytest.fixture(scope="module")
def predeal_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = PreDealBackend()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=PORT, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{PORT}", app.state.backend
    server.should_exit = True
    thread.join(10)


def _open(browser, url, width=1400):
    ctx = browser.new_context(viewport={"width": width, "height": 950}, is_mobile=width < 600, has_touch=width < 600)
    pg = ctx.new_page()
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(url, wait_until="load")
    return pg


def _decided(pg):
    pg.wait_for_function("() => { const r = document.querySelector('[data-predeal]'); return r && r.dataset.decision; }", timeout=20_000)


@pytest.mark.parametrize("notional, decision, status", [("5M", "approve", "within"), ("40M", "approve", "near"), ("100M", "decline", "breach")])
def test_the_verdict_within_near_and_over_as_the_server_says(browser, predeal_console, notional, decision, status):
    base, _ = predeal_console
    pg = _open(browser, f"{base}/go?q=" + quote(f"PREDEAL {CP} LIKE T-1 {notional} <GO>"))
    try:
        assert "/predeal?command=" in pg.url
        _decided(pg)
        root = pg.locator("[data-predeal]")
        assert root.get_attribute("data-decision") == decision and root.get_attribute("data-status") == status
        assert pg.locator("[data-pd-decision]").inner_text().lower() == decision
        assert pg.locator(f'[data-lamp="{decision}"]').get_attribute("class").endswith("on")
        assert pg.locator("[data-pd-status]").get_attribute("data-state") == status
        a = answer(f"PREDEAL {CP} LIKE T-1 {notional}")
        assert pg.locator("[data-pd-reason]").inner_text() == a["reason"]
        # the limit and its buckets: the server's status per row, its numbers as sent (compact money)
        pg.wait_for_function("() => document.querySelector('[data-pd-limits] drishti-grid') && document.querySelector('[data-pd-limits] drishti-grid').grid.model.rowCount() === 3")
        states = pg.eval_on_selector_all("[data-pd-limits] [data-state]", "els => els.map(e => e.dataset.state)")
        assert states[:3] == [status, a["limit"]["buckets"][0]["status"], "within"], states
        tiles = pg.locator("[data-pd-tiles]").text_content()
        assert "Headroom after" in tiles and "EAD (IMM)" in tiles and "under construction (2.9.11.k)" in tiles
        assert pg.locator("[data-pd-tile=use] [data-state]").get_attribute("data-state") == status
        # the what-if: the overlay against the limit, the incremental bars, the figures and the table by tenor
        assert pg.locator("[data-pd-overlay] drishti-chart").count() == 1 and pg.locator("[data-pd-bars] drishti-chart").count() == 1
        built = pg.evaluate("document.querySelector('[data-pd-overlay] drishti-chart').chart.built.figures")
        assert built["limit"] == LIMIT and built["computedHere"] is False
        assert pg.evaluate("document.querySelector('[data-pd-compare] drishti-grid').grid.model.rowCount()") == 6
        assert pg.evaluate("document.querySelector('[data-pd-tenor] drishti-grid').grid.model.rowCount()") == len(TENORS)
        assert f"drishti.py exposure predeal \"PREDEAL {CP} LIKE T-1 {notional}\"" in pg.locator("[data-pd-cli]").inner_text()
        assert pg.input_value("[data-pd-counterparty]") == CP and pg.input_value("[data-pd-like]") == "T-1"   # the trade put back in the form
        assert pg.locator("[data-pd-method-option=paths]").is_checked() and pg.locator("[data-pd-method-option=addon]").count() == 1
        # a figure of a table explained in the grid's own pane, by the server's explanation (the one Explain, kind predeal)
        assert pg.evaluate("document.querySelector('[data-pd-limits] drishti-grid').grid.explain(0, 1).then(r => String(r))") \
            .find("headroom = limit − with") >= 0
        assert not pg.errors, pg.errors
    finally:
        pg.context.close()


def test_a_proxy_refers_and_the_form_checks_a_booked_trade(browser, predeal_console):
    base, backend = predeal_console
    pg = _open(browser, f"{base}/predeal?command=" + quote(f"PREDEAL {CP} RATES 10M 5Y"))
    try:
        _decided(pg)
        assert pg.get_attribute("[data-predeal]", "data-decision") == "refer"
        assert "never approves" in pg.locator("[data-pd-reason]").inner_text()
        assert "EAD (IMM)" not in pg.locator("[data-pd-tiles]").text_content()                # the proxy has no EEPE: no EAD said
        assert pg.evaluate("document.querySelector('[data-pd-overlay] drishti-chart').chart.built.figures.eepe") is None
        # the form: the netting set picked, its trades from the server, a notional as money, Check
        pg.evaluate("delete document.querySelector('[data-predeal]').dataset.decision")
        pg.locator("[data-pd-method-option=paths]").check()
        pg.select_option("[data-pd-set]", "NS-ALDERSHOT-LDN")
        pg.wait_for_function("() => [...document.querySelector('[data-pd-like]').options].some(o => o.value === 'T-9')")
        pg.select_option("[data-pd-like]", "T-9")
        pg.locator("[data-pd-notional]").fill("40m")
        pg.locator("[data-pd-notional]").press("Tab")
        pg.locator("[data-pd-check]").click()
        _decided(pg)
        sent = backend.checks[-1]
        assert sent["counterparty"] == CP and sent["like"] == "T-9" and sent["nettingSet"] == "NS-ALDERSHOT-LDN" and sent["notional"] == 40_000_000
        assert pg.get_attribute("[data-predeal]", "data-status") == "near"
        assert unquote(pg.url).endswith("/predeal?command=PREDEAL CP-ALDERSHOT LIKE T-9 40M")
    finally:
        pg.context.close()


def test_explain_opens_the_one_explain_with_the_servers_formula(browser, predeal_console):
    base, backend = predeal_console
    pg = _open(browser, f"{base}/predeal?command=" + quote(f"PREDEAL {CP} LIKE T-1 40M"))
    try:
        _decided(pg)
        pg.locator("[data-pd-explain]").click()
        pg.wait_for_function("() => { const d = document.querySelector('[data-explain-drawer]'); return d && !d.hidden && d.dataset.state === 'ready'; }",
                             timeout=20_000)
        text = pg.locator("[data-explain-drawer]").inner_text()
        assert "headroom = limit − with" in text and "use = with ÷ limit" in text
        asked = backend.explained[-1]
        assert asked["kind"] == "predeal" and asked["command"] == f"PREDEAL {CP} LIKE T-1 40M" and asked["date"] == "2026-09-30"
    finally:
        pg.context.close()


def test_on_a_phone_nothing_scrolls_sideways_and_the_verdict_comes_first(browser, predeal_console):
    base, _ = predeal_console
    pg = _open(browser, f"{base}/predeal?command=" + quote(f"PREDEAL {CP} LIKE T-1 100M"), width=390)
    try:
        _decided(pg)
        pg.wait_for_function("() => document.querySelector('[data-pd-tenor] drishti-grid')")
        assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), pg.evaluate("document.documentElement.scrollWidth")
        v = pg.locator("[data-pd-verdict-card]").bounding_box()
        f = pg.locator("[data-pd-form]").bounding_box()
        assert v["y"] < f["y"] and v["width"] <= 390
        assert pg.locator("[data-pd-decision]").is_visible()
    finally:
        pg.context.close()


def test_with_banking_off_the_check_is_gone(backend, monkeypatch):
    """The module rule (ACCESS_MODEL.md section 6): banking off, the page, its API, its word and its script are gone."""
    from test_modules_off import BANKING_OFF, _answer, _client, _console

    c = _client(_console(backend, monkeypatch, BANKING_OFF, _answer(off_modules=("banking",))))
    assert c.get("/predeal", follow_redirects=False).status_code == 404
    assert c.post("/api/exposure/predeal", json={"command": "PREDEAL CP-X LIKE T-1 5M"}).status_code == 404
    assert c.get("/static/js/drishti_exposure-predeal.js").status_code == 404
    said = unquote(c.get("/go", params={"q": "PREDEAL CP-X LIKE T-1 5M <GO>"}, follow_redirects=False).headers["location"])
    assert said.startswith("/t?error=PREDEAL") and "is not available to you" in said
    assert "/predeal" not in c.get("/t").text
