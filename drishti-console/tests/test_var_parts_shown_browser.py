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

"""Rows limited by ``drishti.var.max-parts`` say so in a real browser (docs/guides/VAR_GUIDE.md section 27a.4): on the market-risk pack's
committed VaR data, served with ``max-parts`` set to 3, a desk's contributions by position list the 3 largest positions and the line above
the table says "3 of N positions shown (the largest by gross size); totals are over all N", N being the answer's ``total``; the totals
row is the answer's sums over all N. Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import re
import urllib.request

import pytest

from test_var_explorer_browser import open_var, painted
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, var_data, var_lake, var_server

MAX_PARTS = 3


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from var_server(tmp_path_factory, var_lake(tmp_path_factory, var_data(*VAR_TRADES)), DRISHTI_VAR_MAXPARTS=str(MAX_PARTS))


def test_limited_rows_say_how_many_of_how_many_are_shown(stack, browser):
    base = str(stack)
    with urllib.request.urlopen(base + "/api/var/scope/contributions?scope=DESK:DESK-RATES&by=position", timeout=60) as r:
        d = json.loads(r.read())
    assert d["truncated"] is True and d["shown"] == MAX_PARTS and len(d["rows"]) == MAX_PARTS
    total = d["total"]
    assert total > MAX_PARTS
    pg = open_var(browser, base, "/var?scope=DESK:DESK-RATES")
    painted(pg, "DESK:DESK-RATES")
    pg.locator('.vx-tab[data-tab="contrib"]').click()
    pg.locator('.vx-segb[data-by="position"]').click()
    wait(pg, "document.querySelectorAll('[data-vx-ctable] tbody tr[data-scope^=\"POS:\"]').length === %d" % MAX_PARTS)
    line = pg.locator("[data-vx-ctable-shown]")
    wait(pg, "!document.querySelector('[data-vx-ctable-shown]').hidden")
    text = line.inner_text()
    n = f"{total:,}"
    assert text == f"{MAX_PARTS} of {n} positions shown (the largest by gross size); totals are over all {n}.", text
    assert line.is_visible()
    total_row = pg.locator("[data-vx-ctable] tbody tr.vx-total").inner_text()
    assert f"{round(d['sumComponent']):,}" in total_row and f"{round(d['sumStandalone']):,}" in total_row
    # the desk's own children (its books) are fewer than max-parts: no line
    pg.locator('.vx-segb[data-by="child"]').click()
    wait(pg, "document.querySelector('[data-vx-ctable-shown]').hidden")
    assert re.search(r"of .* shown", pg.locator("[data-vx-ctable-note]").inner_text()) is None
