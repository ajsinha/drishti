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

"""The pivot's + in a real browser over the real server and the core pack's shipped issuers (PIVOT_GUIDE.md section 5): the owner's case,
rows Issuer ID only, columns Equity, values Rows and the count of each bond's coupon (one row per bond):

- a member on the last row level is a leaf: no +, and → does not ask the server to open it;
- with a second row level every member has a +, and a click opens its children under it (each issuer by its bonds' ISINs);
- the Grand total row's figures are right for every column: Rows counts the bonds of the issuers in the column, the coupons count the same,
  and the grand total of a column is the sum of its members' counts (count is mergeable), checked against the shipped sample itself.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
from collections import Counter
from pathlib import Path

import pytest

from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
SAMPLE = ROOT / "config" / "packs" / "core" / "samples" / "issuer.jsonl"
VALUES = [{"measure": "count()", "as": "Rows"}, {"measure": "count(BONDS.COUPON)", "as": "Coupons"}]


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from _stack(tmp_path_factory, {}, shared=True)


def issuers():
    return [json.loads(line) for line in SAMPLE.read_text().splitlines() if line.strip()]


def settled(pg):
    pg.evaluate("(o) => window.Drishti.settled(o)", {"timeout": 60000, "animations": False})


def pivot(browser, base, rows):
    """The pivot screen on the issuers with the owner's view, the given rows; waits for the answer drawn."""
    pg = browser.new_page(viewport={"width": 1500, "height": 950})
    pg.errors = []
    pg.on("pageerror", lambda e: pg.errors.append(str(e)))
    pg.goto(base + "/pivot?source=issuer")
    wait(pg, "!!(window.Drishti.PivotScreen && window.Drishti.PivotScreen.view())", 120)
    settled(pg)
    pg.evaluate("""(rows) => window.Drishti.PivotScreen.run((v) => { v.rows = rows; v.columns = ['EQUITY'];
        v.values = %s; v.expanded = []; })""" % json.dumps(VALUES), rows)
    wait(pg, "(() => { const a = window.Drishti.PivotScreen.answer(); return a && a.view && JSON.stringify(a.view.rows) === %r"
             " && document.querySelectorAll('[data-pv-grid] .dg-cell[data-cell]').length > 0; })()" % json.dumps(rows, separators=(",", ":")), 120)
    settled(pg)
    return pg


def nodes(pg):
    return pg.evaluate("window.Drishti.PivotScreen.answer().rows.filter((r) => r.kind === 'node')")


def test_one_row_level_draws_no_plus_and_the_grand_total_is_right_per_column(stack, browser):
    pg = pivot(browser, str(stack), ["ISSUER_ID"])
    rows = nodes(pg)
    assert len(rows) == len({i["ISSUER_ID"] for i in issuers()})
    assert all(r["expandable"] is False for r in rows), "a member on the last level has nothing under it"
    assert pg.locator("[data-pv-grid] .dg-row[aria-expanded]").count() == 0, "no + (nor −) on a leaf"
    # → on a leaf asks nothing of the server (no answer is asked again)
    asked = []
    pg.on("request", lambda r: asked.append(r.url) if "/expand" in r.url or "/api/pivot/query" in r.url else None)
    pg.locator("[data-pv-grid] .dg-cell[data-cell]").first.click()
    pg.keyboard.press("ArrowRight")
    settled(pg)
    assert asked == []
    # the Grand total row, column by column, against the shipped sample (one row per bond)
    a = pg.evaluate("window.Drishti.PivotScreen.answer()")
    grand = [r for r in a["rows"] if r["kind"] == "grand"][0]
    want = Counter()
    for i in issuers():
        want[i.get("EQUITY")] += len(i.get("BONDS") or [])
    for col in a["columns"]:
        cell = a["cells"].get(grand["key"] + "|" + col["key"])
        assert cell is not None, col
        expect = sum(want.values()) if col["kind"] == "grand" else want[col["value"] if col["value"] not in ("", "(blank)") else None]
        assert cell["values"]["Rows"] == expect, (col["label"], cell)
        assert cell["values"]["Coupons"] == expect, (col["label"], cell)
        members = sum(a["cells"][r["key"] + "|" + col["key"]]["values"]["Rows"] or 0 for r in a["rows"]
                      if r["kind"] == "node" and r["key"] + "|" + col["key"] in a["cells"])
        assert members == cell["values"]["Rows"], col["label"]
    assert pg.errors == []
    pg.close()


def test_with_a_next_level_the_plus_opens_the_member(stack, browser):
    pg = pivot(browser, str(stack), ["ISSUER_ID", "BONDS.ISIN"])
    rows = nodes(pg)
    assert rows and all(r["expandable"] is True for r in rows)
    plus = pg.locator('[data-pv-grid] .dg-row[aria-expanded="false"]')
    assert plus.count() == len(rows)
    first = rows[0]
    plus.first.locator(".dg-tw").click()
    wait(pg, "window.Drishti.PivotScreen.answer().rows.some((r) => r.path.length === 2 && r.path[0][1] === %s)" % json.dumps(first["value"]), 120)
    settled(pg)
    kids = pg.evaluate("window.Drishti.PivotScreen.answer().rows.filter((r) => r.kind === 'node' && r.path.length === 2)")
    bonds = [b["ISIN"] for i in issuers() if i["ISSUER_ID"] == first["value"] for b in i.get("BONDS") or []]
    assert sorted(k["value"] for k in kids) == sorted(bonds)
    assert all(k["expandable"] is False for k in kids), "the last level's members are leaves"
    assert pg.locator('[data-pv-grid] .dg-row:not(.dg-k-total)[aria-expanded="true"]').count() == 1   # one member open (its total line closes it too)
    assert pg.errors == []
    pg.close()
