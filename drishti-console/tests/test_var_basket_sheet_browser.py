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

"""Construct your basket as a sheet in Chromium (docs/guides/VAR_GUIDE.md section 3.5), the console in-process against a stand-in
engine that answers the sheet's checks (an id starting with X is not found; a minus on Z takes nothing away) and keeps the baskets saved:
columns pasted from Excel fill downward (a block with headings lands under its headings), each cell is coloured by the engine's answer
with its reason, "Remove the not found" drops them, Start from the whole bank goes as a flag, Save sends the lists (never a scope
text), the saved basket opens back into the sheet column by column in a fresh browser, Edit keeps its name as a new version, Delete
removes it, a portfolio opens as a copy. The console computes nothing: every figure on the screen is the stand-in's. Skipped without
Playwright or Chromium."""
import os
import threading
import time
from pathlib import Path

import pytest

from conftest import CONSOLE, FakeBackend

sync_api = pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_live_tabs_browser import _free_port  # noqa: E402
from test_workbench_browser import wait  # noqa: E402

COLS = [{"key": k, "title": t} for k, t in (("TRD", "Trade"), ("POS", "Position"), ("PORT", "Portfolio"), ("BOOK", "Book"),
                                            ("DESK", "Desk"), ("LL", "Limit letter"), ("CPTY", "Counterparty"))]


class SheetBackend(FakeBackend):
    """The engine's sheet and basket endpoints over a dict; every other VaR answer is a small fixed one."""

    def __init__(self):
        super().__init__()
        self.kept, self.checked, self.saves, self.deleted = {}, [], [], []

    def answer(self, sheet):
        cells, found, nf = [], 0, 0
        ops = sheet.get("op", [])
        for c in COLS:
            for r, v in enumerate(sheet.get(c["key"], [])):
                if not v:
                    continue
                minus = v.startswith("-") or (r < len(ops) and ops[r] == "-")
                ident = v.lstrip("-")
                if ident.startswith("X"):
                    nf += 1
                    cells.append({"column": c["key"], "row": r, "text": v, "status": "not-found",
                                  "reason": f"{c['key']}:{ident} is not a live trade on 2026-09-30 (no P&L vector)"})
                    continue
                found += 1
                if minus and ident.startswith("Z"):
                    cells.append({"column": c["key"], "row": r, "text": v, "status": "takes-nothing",
                                  "reason": f"{c['key']}:{ident} takes nothing away: none of its 1 trade is in a part added"})
        return {"businessDate": "2026-09-30", "currency": "USD", "wholeBank": sheet.get("wholeBank", False), "columns": COLS,
                "found": found, "notFound": nf, "flagged": len(cells) - nf, "cells": cells, "scope": "TRD:STAND-IN" if found else "",
                "empty": not found, "trades": found, "var": 100.0 * found, "es": 120.0 * found, "sumOfParts": 150.0 * found,
                "diversification": 50.0 * found, "partsAdded": found, "confidence": 0.99, "esConfidence": 0.975, "computeMs": 3}

    async def var(self, method, path, ident, /, body=None, raw=False, accept="", **params):
        from core.backend import BackendError
        if path == "/basket/sheet" and method == "POST":
            self.checked.append(body)
            return self.answer(body)
        if path == "/basket/sheet.xlsx":
            return b"PK\x03\x04stand-in", {"content-disposition": 'attachment; filename="mix.xlsx"'}
        if path == "/basket/sheet" and method == "GET":
            scope = params.get("scope", "")
            if scope.startswith("BASKET:") and scope[7:] in self.kept:
                b = self.kept[scope[7:]]
                return {"columns": COLS, "sheet": b["sheet"], "unplaced": [],
                        "basket": {k: b[k] for k in ("id", "name", "owner", "version", "sharedWith")} | {"editable": True, "own": True, "fromSheet": True}}
            if scope == "PORT:PF-USD-RATES":
                return {"columns": COLS, "sheet": {"wholeBank": False, "op": ["+", "+"], "POS": ["POS-A", "POS-B"]}, "unplaced": [],
                        "portfolio": {"id": "PF-USD-RATES", "name": "USD rates", "note": "a portfolio is the bank's (its feed): never edited, amended or deleted in Drishti; this is a copy you save as your own basket"}}
            return {"columns": COLS, "sheet": {"wholeBank": False, "op": ["+"], "DESK": ["DESK-X"]}, "unplaced": []}
        if path == "/baskets" and method == "POST":
            self.saves.append(body)
            bid = "ash/" + body["name"]
            was = self.kept.get(bid)
            if body.get("version") is not None and was and was["version"] != body["version"]:
                raise BackendError(409, "DRS-5007", "the basket changed since you opened it")
            b = {"id": bid, "name": body.get("title", body["name"]), "owner": "ash", "version": (was["version"] + 1) if was else 1,
                 "sharedWith": body.get("sharedWith", []), "sheet": body["sheet"], "type": "BASKET", "scope": "TRD:STAND-IN"}
            self.kept[bid] = b
            return b
        if path == "/baskets":
            return [{k: v for k, v in b.items() if k != "sheet"} | {"fromSheet": True} for b in self.kept.values()]
        if method == "DELETE" and path.startswith("/baskets/"):
            bid = path[len("/baskets/"):]
            self.deleted.append(bid)
            self.kept.pop(bid, None)
            return None
        if path == "/tree":
            return {"nodes": [], "portfolios": [{"name": "USD rates", "type": "BASKET", "scope": "PORT:PF-USD-RATES"}], "businessDate": "2026-09-30"}
        if path == "/hints":
            return [{"type": "DESK", "id": "DESK-FX", "name": "FX", "scope": "NODE:MGMT~DESK-FX", "trades": 12, "var": 1000}]
        if path == "/scope":
            scope = params.get("scope", "")
            kind = "basket" if scope.startswith("BASKET:") else "adhoc"
            out = {"scope": scope, "canonical": scope, "kind": kind, "label": scope, "businessDate": "2026-09-30", "var": 100.0, "es": 120.0,
                   "trades": 1, "empty": False, "currency": "USD", "confidence": 0.99, "esConfidence": 0.975, "terms": [],
                   "provenance": {"scenarioSet": "SS-1", "notices": [], "warnings": [], "inputs": {}, "trades": 1}, "ancestors": [], "children": 0}
            if kind == "basket" and scope[7:] in self.kept:
                b = self.kept[scope[7:]]
                out["basket"] = {"id": b["id"], "name": b["name"], "owner": "ash", "version": b["version"], "sharedWith": [], "fromSheet": True}
            return out
        raise BackendError(404, "DRS-1003", "nothing here in this test")


@pytest.fixture(scope="module")
def served():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    be = SheetBackend()
    app.state.backend = be
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started
    yield f"http://127.0.0.1:{port}", be
    server.should_exit = True
    thread.join(10)


@pytest.fixture(scope="module")
def browser():
    with sync_api.sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001 - no browser installed
            pytest.skip(f"Chromium is not installed: {e}")
        yield b
        b.close()


def shot(pg, name):
    """A screenshot of the sheet when DRISHTI_VAR_SHOTS names a folder (as the explorer's browser test)."""
    folder = os.environ.get("DRISHTI_VAR_SHOTS")
    if folder:
        Path(folder).mkdir(parents=True, exist_ok=True)
        pg.locator("[data-var-basket]").screenshot(path=str(Path(folder) / f"{name}.png"))


def paste(pg, text):
    """Pastes Excel's text into the sheet at its selected cell, as Ctrl+V does."""
    pg.evaluate("""(t) => { const dt = new DataTransfer(); dt.setData('text/plain', t);
        document.querySelector('[data-vb-sheet]').dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true})); }""", text)


def answered(pg):
    """Waits until the figures are the engine's answer for what the sheet holds now."""
    wait(pg, "(() => { const l = document.querySelector('[data-vb-live]'); return l && l.dataset.sig === window.Drishti.VarBasket.signature()"
             " && !l.classList.contains('computing'); })()", 15)


def cell(pg, key, row):
    return pg.locator(f'[data-vb-cell="{key}:{row}"]')


def test_paste_check_save_reopen_edit_and_delete(served, browser):
    url, be = served
    ctx = browser.new_context(viewport={"width": 1500, "height": 1000})
    pg = ctx.new_page()
    try:
        pg.goto(url + "/var?scope=BANK")
        pg.wait_for_selector("[data-vx-basket-open]")
        assert pg.locator("[data-vx-basket]").is_hidden()                      # the sheet opens only when asked
        pg.click("[data-vx-basket-open]")
        pg.wait_for_selector("[data-vb-sheet]")
        assert "Start from the whole bank" in pg.inner_text("[data-var-basket]")
        # a column pasted from Excel fills downward from the selected cell
        cell(pg, "TRD", 0).click()
        paste(pg, "MX-1\nMX-2\n-ZED-3\nX-404\n")
        answered(pg)
        assert [cell(pg, "TRD", r).inner_text() for r in range(4)] == ["MX-1", "MX-2", "-ZED-3", "X-404"]
        assert be.checked[-1]["TRD"] == ["MX-1", "MX-2", "-ZED-3", "X-404"] and "scope" not in be.checked[-1]   # lists, never a scope text
        assert "okc" in cell(pg, "TRD", 0).get_attribute("class")
        assert "badc" in cell(pg, "TRD", 3).get_attribute("class")
        assert "not a live trade" in cell(pg, "TRD", 3).get_attribute("title")
        assert "warnc" in cell(pg, "TRD", 2).get_attribute("class")              # a minus that takes nothing away, said in words
        checks = pg.inner_text("[data-vb-checks]")
        assert "3 found" in checks and "1 not found" in checks and "takes nothing away" in checks, checks
        assert pg.get_attribute("[data-vb-live]", "data-var") == "300"          # the engine's figure, as answered
        assert "300" in pg.inner_text('[data-vb-kpi="var"]') and "150" in pg.inner_text('[data-vb-kpi="div"]')
        # a block with headings (the sheet's own workbook) lands under its headings
        cell(pg, "TRD", 6).click()
        paste(pg, "Op\tDesk (DESK)\tCounterparty (CPTY)\n+\tDESK-FX\tCP-1\n-\tDESK-COMM\t\n")
        answered(pg)
        assert cell(pg, "DESK", 6).inner_text() == "DESK-FX" and cell(pg, "CPTY", 6).inner_text() == "CP-1"
        assert cell(pg, "DESK", 7).inner_text() == "DESK-COMM"
        assert be.checked[-1]["op"][7] == "-"
        # typing in a cell, then Delete clears it
        cell(pg, "BOOK", 1).click()
        pg.keyboard.type("BOOK-1")
        pg.keyboard.press("Enter")
        answered(pg)
        assert be.checked[-1]["BOOK"][1] == "BOOK-1"
        cell(pg, "BOOK", 1).click()
        pg.keyboard.press("Delete")
        answered(pg)
        assert "BOOK" not in be.checked[-1]
        # Remove the not found
        pg.click("[data-vb-remove-missing]")
        answered(pg)
        assert "X-404" not in be.checked[-1]["TRD"]
        # Start from the whole bank goes as a flag, never a row
        pg.check("[data-vb-bank]")
        answered(pg)
        assert be.checked[-1]["wholeBank"] is True
        pg.uncheck("[data-vb-bank]")
        answered(pg)
        # the type-ahead puts a hint in its kind's column
        pg.fill("[data-vb-find]", "fx")
        pg.wait_for_selector("[data-vb-hint]")
        pg.press("[data-vb-find]", "Enter")
        answered(pg)
        assert "DESK-FX" in [v for v in be.checked[-1]["DESK"] if v]
        shot(pg, "basket-sheet-stand-in")
        # Download as .xlsx: the engine's workbook
        with pg.expect_download() as dl:
            pg.click("[data-vb-xlsx]")
        assert dl.value.suggested_filename.endswith(".xlsx")
        # saved as lists; the explorer opens it
        pg.fill("[data-vb-name]", "mix")
        pg.click("[data-vb-save]")
        wait(pg, "document.querySelector('[data-var]').dataset.saved === 'ash/mix'", 15)
        saved = be.saves[-1]
        assert "sheet" in saved and "scope" not in saved and saved["sheet"]["TRD"][:2] == ["MX-1", "MX-2"]
        # 2.9.11.b: the saved basket joins My baskets, the sheet collapses; a portfolio is a system basket
        pg.wait_for_selector('[data-vx-baskets-mine] [data-vx-edit="BASKET:ash/mix"]', state="attached")
        wait(pg, "document.querySelector('[data-vx-basket]').hidden", 15)
        assert pg.locator('[data-vx-baskets-system] [data-vx-edit="PORT:PF-USD-RATES"]').text_content() == "Edit a copy"
    finally:
        ctx.close()

    # a fresh browser: Edit opens the saved basket back into the sheet, column by column; Save keeps its name as a new version (the
    # explorer's own opening of a basket into the sheet is test_var_explorer_browser's, over the real engine)
    ctx = browser.new_context(viewport={"width": 1500, "height": 1000})
    pg = ctx.new_page()
    try:
        pg.goto(url + "/var?scope=BASKET:ash/mix")
        pg.wait_for_selector('[data-vx-baskets-mine] [data-vx-edit="BASKET:ash/mix"]', state="attached")
        assert pg.locator("[data-vx-basket]").is_hidden()                      # the sheet waits to be asked
        pg.click('[data-vx-pick="mine"] [data-vx-pick-btn]')                   # My baskets (2.9.11.b: the toolbar's drop-down)
        pg.click('[data-vx-edit="BASKET:ash/mix"]')
        pg.wait_for_selector("[data-vb-sheet]")
        answered(pg)
        # the not found were removed (their empty rows with them) and the hint's DESK-FX took the first free + row
        assert cell(pg, "TRD", 0).inner_text() == "MX-1" and cell(pg, "DESK", 0).inner_text() == "DESK-FX"
        assert cell(pg, "DESK", 3).inner_text() == "DESK-FX" and cell(pg, "DESK", 4).inner_text() == "DESK-COMM"
        assert pg.input_value("[data-vb-name]") == "mix"
        assert "Save as version 2" in pg.inner_text("[data-vb-save]")
        cell(pg, "TRD", 1).click()
        pg.keyboard.press("Delete")
        answered(pg)
        pg.click("[data-vb-save]")
        wait(pg, "document.querySelector('[data-vb-msg]').textContent.indexOf('version 2') >= 0", 15)
        assert be.saves[-1]["version"] == 1 and be.saves[-1]["name"] == "mix"
        assert be.kept["ash/mix"]["version"] == 2 and be.kept["ash/mix"]["sheet"]["TRD"][1] == ""
        # a portfolio is the bank's: Edit a copy opens its parts as your own unsaved basket
        pg.click('[data-vx-pick="system"] [data-vx-pick-btn]')                 # System baskets: the bank's portfolios
        pg.click('[data-vx-edit="PORT:PF-USD-RATES"]')
        wait(pg, "window.Drishti.VarBasket.state.copyOf === 'PF-USD-RATES'", 15)
        assert cell(pg, "POS", 1).inner_text() == "POS-B"
        assert "your own basket" in pg.inner_text("[data-vb-save]")
        assert "copy" in pg.inner_text("[data-vb-msg]")
        # Delete, through the confirm dialog
        pg.click('[data-vx-pick="mine"] [data-vx-pick-btn]')
        pg.click('[data-vx-delete="ash/mix"]')
        pg.wait_for_selector("[data-sd-dialog]", state="visible")
        pg.click("[data-sd-yes]")
        pg.locator('[data-vx-delete="ash/mix"]').wait_for(state="detached")
        assert be.deleted == ["ash/mix"]
    finally:
        ctx.close()
