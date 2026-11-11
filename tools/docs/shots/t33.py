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

"""Pictures of tutorial 7, Part A, the pivot engine (docs/guides/BI_GUIDE.md), written to docs/tutorials/img/t33/, and of the pivot
guide's worked examples over entity documents (docs/guides/BI_GUIDE.md, Documents), written to docs/guides/img/pivot/. Run it on its own
(it builds its own lake from the demo test data, the trades of the three source systems and the VaR data, and starts its own scratch server
and console):

    drishti-console/.venv/bin/python tools/docs/screenshots.py --guide t33

Every view is saved through the API and opened on the pivot screen; the answer of each is written into the scratch folder as
pivot-figures.json, so the guide quotes the figures the run produced (DRISHTI_SHOTS_WORK keeps the folder)."""
from __future__ import annotations

import json

import _var
from _risk import settled  # noqa: F401 - the shared waits
from _tut import OUT
from shotlib import ROOT, Ctx, register

_var.VAR_DATA = ",".join([_var.VAR_DATA, "trades/calypso", "trades/endur"])
SERVER_ENV = {"DRISHTI_SECURITY_ENABLED": "false"}    # runs on its own (--guide t33): it starts its own stack


def start_servers(work) -> list:
    """The VaR pictures' scratch stack (its lake built from the demo test data, here with the trades of the three source systems)."""
    WORK[:] = [work]
    return _var.start_servers(work)


shot = register("t33", OUT / "t33")
GUIDE = ROOT / "docs" / "guides" / "img" / "pivot"
FIGURES: dict = {}
WORK: list = []                                       # the scratch folder the stack runs in (the figures are written there)

# the guide's worked examples (HOW_IT_FITS.md section A26.4), each a saved view
EXAMPLES = {
    "notional-by-desk-and-year": {"source": "trade", "rows": ["$.TT_GROUP"], "columns": ["$.TRADE_DATE:year"],
                                   "values": [{"measure": "sum($.LEGS[*].NOTIONAL)", "as": "Notional"}]},
    "trades-with-a-jpy-leg": {"source": "trade", "rows": ["$.PRODUCT_TYPE"], "filters": {"$.LEGS[*].CURRENCY": {"in": ["JPY"]}},
                              "values": [{"measure": "count()", "as": "Trades"}]},
    "fixed-and-floating-legs": {"source": "trade", "rows": ["$.COUNTERPARTY"], "columns": ["$.LEGS[*].LEG_TYPE"], "top": {"$.COUNTERPARTY": {"n": 8}},
                                "values": [{"measure": "count()", "as": "Legs"}, {"measure": "sum($.LEGS[*].NOTIONAL)", "as": "Notional"}]},
    "quantity-once-per-trade": {"source": "trade", "rows": ["$.TT_GROUP"], "filters": {"$.CURRENCY": {"in": ["USD"]}},
                                "values": [{"measure": "sum($.QUANTITY)", "as": "Quantity"}, {"measure": "count($.LEGS[*].LEG_ID)", "as": "Legs"}]},
    "booking-events-by-type": {"source": "trade", "rows": ["$.EVENTS[*].EVENT_TYPE"], "columns": ["$.EVENTS[*].EVENT_DATE:year"],
                               "values": [{"measure": "count()", "as": "Events"}]},
    "var-by-desk": {"source": "var", "rows": ["DESK"], "values": [{"measure": "var()", "as": "VaR"}, {"measure": "es()", "as": "ES"}]},
}


def _save(c: Ctx, name: str, view: dict) -> str:
    r = c.api("/api/pivot/views", {"name": name, "view": view})
    if "id" not in r:
        raise RuntimeError(f"{name}: {r}")
    ans = c.api("/api/pivot/query", {"view": r["id"]})
    if "_catalogue" not in FIGURES:
        FIGURES["_catalogue"] = c.page.request.get(c.base + "/api/pivot/catalogue?source=trade").json()
    FIGURES[name] = {"view": view, "id": r["id"], "rows": ans.get("rows"), "columns": ans.get("columns"), "cells": ans.get("cells"),
                     "measures": ans.get("measures"), "provenance": ans.get("provenance"), "notes": ans.get("notes"), "error": ans.get("detail")}
    import tempfile
    out = (WORK[0] if WORK else __import__("pathlib").Path(tempfile.gettempdir())) / "pivot-figures.json"   # the scratch folder, never the repository
    out.write_text(json.dumps(FIGURES, indent=1, default=str))
    return r["id"]


def _open(c: Ctx, path: str) -> None:
    c.page.set_viewport_size({"width": 1440, "height": 900})
    c.page.goto(c.base + path)
    c.until("document.querySelectorAll('[data-pv-grid] .dg-cell[data-cell]').length > 0 || !document.querySelector('[data-pv-notice]').hidden", 300)
    c.page.wait_for_timeout(900)


def _example(c: Ctx, name: str, file: str) -> None:
    vid = _save(c, name, EXAMPLES[name])
    _open(c, "/pivot?view=" + vid)
    c.guide = "pivot"
    GUIDE.mkdir(parents=True, exist_ok=True)
    c.page.screenshot(path=str(GUIDE / file), type="jpeg", quality=84)
    print("  wrote", (GUIDE / file).relative_to(ROOT))


@shot("01-first-pivot.jpg")
def first(c: Ctx):
    vid = _save(c, "trades-by-product", {"source": "trade", "rows": ["PRODUCT_TYPE"], "values": [{"measure": "count()", "as": "Trades"},
                                                                                                    {"measure": "count_distinct(COUNTERPARTY)", "as": "Counterparties"}]})
    _open(c, "/pivot?view=" + vid)
    c.save("01-first-pivot.jpg")


@shot("02-open-a-row.jpg")
def open_row(c: Ctx):
    vid = _save(c, "trades-by-product-and-book", {"source": "trade", "rows": ["PRODUCT_TYPE", "PORTFOLIO"], "values": [{"measure": "count()", "as": "Trades"}],
                                                  "expandTo": {"rows": 2}, "top": {"PORTFOLIO": {"n": 5}}})
    _open(c, "/pivot?view=" + vid)
    c.save("02-open-a-row.jpg")


@shot("03-documents-field-list.jpg")
def field_list(c: Ctx):
    _open(c, "/pivot?source=trade")
    c.page.wait_for_selector("[data-pv-grid] .dg-field-search")
    c.page.fill("[data-pv-grid] .dg-field-search", "legs")              # the legs' fields, under their grain's heading
    c.until("Array.from(document.querySelectorAll('[data-pv-grid] .dg-fgroup')).some(g => g.textContent.indexOf('Each leg') >= 0)", 60)
    c.page.wait_for_timeout(400)
    c.save("03-documents-field-list.jpg", locator="[data-pv-grid] .dg-fieldpane")


@shot("04-legs-per-desk.jpg", also=("notional-by-desk-and-year.jpg", "trades-with-a-jpy-leg.jpg", "fixed-and-floating-legs.jpg",
                                     "quantity-once-per-trade.jpg", "booking-events-by-type.jpg", "var-by-desk.jpg"))
def legs(c: Ctx):
    # the check the guide quotes: the quantity once per trade at the leg grain equals the plain sum at the trade grain
    _save(c, "quantity-at-the-trade-grain", {"source": "trade", "rows": ["$.TT_GROUP"], "filters": {"$.CURRENCY": {"in": ["USD"]}},
                                             "values": [{"measure": "sum($.QUANTITY)", "as": "Quantity"}, {"measure": "count()", "as": "Trades"}]})
    for name in EXAMPLES:
        _example(c, name, name + ".jpg")
    c.guide = "t33"
    vid = FIGURES["quantity-once-per-trade"]["id"]
    _open(c, "/pivot?view=" + vid)
    c.save("04-legs-per-desk.jpg")


@shot("05-var-by-desk.jpg")
def var(c: Ctx):
    _open(c, "/pivot?view=" + FIGURES["var-by-desk"]["id"])
    c.save("05-var-by-desk.jpg")
