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

"""Makes the pictures and the captured output of docs/tutorials/10-first-bi-report.md and DATASETS.md from the real stack (not a test: run it by hand).

    DRISHTI_BI_DELTA_ROOT=<a Delta lake made with tools/packgen/banking/make_data.py --lake <dir> --days 3> \\
        drishti-console/.venv/bin/python drishti-console/tests/bi_data_shots.py <pictures dir> <captures.json>

Ports 18989 (server) and 17989 (console), a scratch directory, the shipped sample datasets. Pictures are desktop (1600 px: the top bar needs it) and one phone (390 px)."""
import json
import sys
import tempfile
from pathlib import Path

from playwright.sync_api import sync_playwright

import bi_data_stack
from bi_data_stack import CONSOLE_URL, PASSWORD, api


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1600, "height": 900}, **ctx})
    page = context.new_page()
    page.goto(CONSOLE_URL + "/login?next=/bi/datasets")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi/datasets")
    return page


def preview(page, dataset, fields, measures, filt=None, dates=None):
    page.goto(f"{CONSOLE_URL}/bi/d/{dataset}")
    page.wait_for_selector("[data-fields] option", state="attached")
    page.select_option("[data-fields]", fields)
    page.select_option("[data-measures]", measures)
    if filt:
        page.select_option("[data-ffield]", filt[0])
        page.select_option('[name="fop"]', filt[1])
        page.fill('[name="fvalue"]', filt[2])
    page.click("[data-run]")
    page.wait_for_selector("[data-grid] tbody tr")


def main(out: Path, captures: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    got = {}
    with tempfile.TemporaryDirectory() as tmp:
        stack = bi_data_stack.DataStack(Path(tmp)).start()
        try:
            def q(user, roles, ds, body, explain=False):
                s, r = api("POST", f"/bi/datasets/file:dataset:{ds}/query" + ("?explain=true" if explain else ""), user, roles, body)
                return {"status": s, **({"answer": r} if s == 200 else {"error": r})}

            s, got["datasets"] = api("GET", "/bi/datasets", "rita", ["risk"])
            s, got["model_basic"] = api("GET", "/bi/datasets/file:dataset:trades-basic", "rita", ["risk"])
            s, got["model_risk"] = api("GET", "/bi/datasets/file:dataset:trading-risk", "rita", ["risk"])
            got["basic_explain"] = q("wes", ["bi-writer"], "trades-basic", {"fields": ["trade.desk"], "measures": ["Notional", "MTM", "Trades", "Average MTM per trade"]}, True)
            got["basic_by_desk"] = q("wes", ["bi-writer"], "trades-basic", {"fields": ["trade.desk"], "measures": ["Notional", "MTM", "Trades", "Average MTM per trade"]})
            got["basic_again"] = q("wes", ["bi-writer"], "trades-basic", {"fields": ["trade.desk"], "measures": ["Notional", "MTM", "Trades", "Average MTM per trade"]})
            risk_q = {"fields": ["desks.name"], "measures": ["MTM", "Notional", "Trades", "Counterparties"]}
            cp_q = {"fields": ["counterparties.name"], "measures": ["MTM", "Trades"], "orderBy": [{"by": "Trades", "desc": True}], "limit": 5}
            got["risk_rita"] = q("rita", ["risk"], "trading-risk", risk_q)
            got["risk_hana"] = q("hana", ["desk-head"], "trading-risk", risk_q)
            got["risk_ann"] = q("ann", ["analyst"], "trading-risk", risk_q)
            got["cp_rita"] = q("rita", ["risk"], "trading-risk", cp_q)
            got["cp_ann"] = q("ann", ["analyst"], "trading-risk", cp_q)
            got["cp_rita_explain"] = q("drishti-dev-admin", ["admin"], "trading-risk", {"fields": ["desks.name"], "measures": ["MTM"], "filters": [{"field": "trades.currency", "op": "in", "values": ["USD", "EUR"]}]}, True)
            got["exposure_rita"] = q("rita", ["risk"], "trading-risk", {"fields": ["counterparties.name"], "measures": ["Exposure (EE peak)", "Netting sets"], "orderBy": [{"by": "Exposure (EE peak)", "desc": True}], "limit": 5})
            got["exposure_hana"] = q("hana", ["desk-head"], "trading-risk", {"fields": ["counterparties.name"], "measures": ["Exposure (EE peak)"]})
            got["masked_filter_ann"] = q("ann", ["analyst"], "trading-risk", {"measures": ["Trades"], "filters": [{"field": "counterparties.name", "op": "eq", "values": ["Halcyon Shipping plc"]}]})
            got["rollup"] = q("rita", ["risk"], "trading-risk", {"fields": ["trades.desk", "trades.currency"], "measures": ["MTM", "Notional", "Trades"]})
            got["cashflows_month"] = q("rita", ["risk"], "trading-cashflows", {"fields": ["cashflows.payDate.month"], "measures": ["Cashflow amount", "Cashflows"], "limit": 12})
            got["cashflows_desk"] = q("rita", ["risk"], "trading-cashflows", {"fields": ["trades.desk"], "measures": ["Cashflow amount"], "orderBy": [{"by": "Cashflow amount", "desc": True}], "limit": 4})
            got["legs"] = q("rita", ["risk"], "trading-cashflows", {"fields": ["legs.payReceive"], "measures": ["Legs", "Leg notional"]})
            got["legs_bad"] = q("rita", ["risk"], "trading-cashflows", {"fields": ["legs.payReceive"], "measures": ["Cashflow amount"]})
            s, got["start"] = api("POST", "/bi/model/start", "wes", ["bi-writer"], {"kinds": ["trade", "desk", "counterparty", "netting-set"], "name": "my-first-model"})
            s, got["engine"] = api("GET", "/bi/engine", "drishti-dev-admin", ["admin"])
            s, got["validate_bad"] = api("POST", "/bi/datasets/validate", "wes", ["bi-writer"], {"yaml": "dataset: x\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string }\nmeasures:\n  N: { expr: \"sum(t.nope)\" }\n  M: { expr: \"ytd(sum(t.a))\" }\n"})

            with sync_playwright() as p:
                browser = p.chromium.launch()
                page = sign_in(browser, "wes")
                page.wait_for_selector("[data-datasets] .bi-card")
                page.wait_for_selector("[data-kinds] input")
                page.screenshot(path=str(out / "datasets-list.png"), full_page=True)
                page.check('[data-kind="trade"]')
                page.check('[data-kind="desk"]')
                page.check('[data-kind="counterparty"]')
                page.check('[data-kind="netting-set"]')
                page.click("[data-start-run]")
                page.wait_for_selector("[data-draft-yaml]:not(:empty)")
                page.locator("[data-start]").screenshot(path=str(out / "start-from-packs.png"))

                page.goto(CONSOLE_URL + "/bi/d/file:dataset:trades-basic")
                page.wait_for_selector("[data-measures-table] tbody tr")
                page.screenshot(path=str(out / "dataset-basic.png"), full_page=True)
                preview(page, "file:dataset:trades-basic", ["trade.desk"], ["Notional", "MTM", "Trades", "Average MTM per trade"])
                page.locator('[data-preview]').scroll_into_view_if_needed()
                page.locator("section", has=page.locator("[data-preview]")).first.screenshot(path=str(out / "preview-basic.png"))

                page.goto(CONSOLE_URL + "/bi/d/file:dataset:trading-risk")
                page.wait_for_selector("[data-relationships] tbody tr")
                page.locator("details[data-table]").first.evaluate("e => e.open = true")
                page.screenshot(path=str(out / "model-risk.png"), full_page=True)
                for user, name in (("rita", "risk"), ("hana", "deskhead"), ("ann", "analyst")):
                    pg = sign_in(browser, user)
                    preview(pg, "file:dataset:trading-risk", ["desks.name", "counterparties.name"], ["MTM", "Trades"])
                    pg.locator("section", has=pg.locator("[data-preview]")).first.screenshot(path=str(out / f"preview-risk-{name}.png"))
                    pg.context.close()
                pg = sign_in(browser, "rita")
                preview(pg, "file:dataset:trading-cashflows", ["cashflows.payDate.month"], ["Cashflow amount", "Cashflows"])
                pg.locator("section", has=pg.locator("[data-preview]")).first.screenshot(path=str(out / "preview-cashflows.png"))
                ph = sign_in(browser, "rita", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
                preview(ph, "file:dataset:trades-basic", ["trade.desk"], ["MTM", "Trades"])
                ph.screenshot(path=str(out / "dataset-phone-390.png"), full_page=True)
                browser.close()
        finally:
            stack.close()
    captures.write_text(json.dumps(got, indent=1, default=str))
    print("wrote", captures)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
