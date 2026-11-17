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

"""The stress window selection page in a real browser (docs/guides/VAR_GUIDE.md section 18.9) on the VaR demo lake with its P&L history:
the page draws its controls, the chart and the candidates table across the page's width, with no script error."""
import math

import pytest

from test_var_explorer_browser import open_var
from risk_elements import chart, chart_ready, grid_rows
from select_helpers import choose, value, values  # <drishti-select>: drishti-inputs' Select
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers

pytestmark = pytest.mark.browser


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    """The cached VaR demo lake with its P&L history, ending its fixed date, the server's today (built once per source hash by the Java loader; read-only,
    the server writes its own folders). The VaR files' heap (var_stack's default): the shared lake holds both trade sets, so at 768 MB the
    server's start (P&L vectors, the official capture of the newest date) ran out of heap and the page never loaded (2026-11-16)."""
    import var_stack
    yield from var_stack.var_server(tmp_path_factory, var_stack.shared_lake(tmp_path_factory, needs="risk/pnl-history"))


def test_the_page_draws_its_chart_and_tables_across_the_page(stack, browser):
    errors = []
    pg = open_var(browser, str(stack), "/var/stress-window?scope=BANK&hierarchy=MGMT", {"width": 1600, "height": 1000})
    pg.on("pageerror", lambda e: errors.append(str(e)))
    pg.reload()
    wait(pg, "document.querySelectorAll('[data-sw-cands] .dg-row').length > 0 || !!document.querySelector('[data-sw-alert]:not([hidden])')", 120)
    assert not errors, errors                                         # (an alert line is the analysis' own finding, not a failure)
    width = pg.evaluate("document.querySelector('[data-swin]').getBoundingClientRect().width")
    assert width > 1000, "the page's content is %d px wide" % width
    assert pg.evaluate("document.querySelector('[data-sw-chart]').getBoundingClientRect().height") > 100
    assert pg.locator("[data-sw-ctrls], .sw-ctrls").first.is_visible()
    # the numbers are the server's: the chart a point for every window start it valued, the candidates its own, in its order
    wait(pg, chart_ready("[data-sw-chart]"), 60)
    d = pg.evaluate("fetch('/api/var/stress-window?scope=BANK&hierarchy=MGMT', {credentials: 'same-origin'}).then((r) => r.json())")
    assert chart(pg, "[data-sw-chart]")["series"][0]["n"] == len(d["series"]["var"])
    cands = grid_rows(pg, "[data-sw-cands]")
    assert [c["data"]["first"] for c in cands] == [c["first"] for c in d["candidates"]]
    assert [c["cells"][2] for c in cands] == [f"{math.floor(c['var'] + 0.5):,}" for c in d["candidates"]]
    # the pickers are drishti-inputs' Select: the hierarchies the server listed, the window's length picked asks again
    assert values(pg, "[data-sw-hierarchy]") == list(d.get("hierarchies") or ["MGMT"]) and value(pg, "[data-sw-hierarchy]") == d["node"]["hierarchy"]
    assert value(pg, "[data-sw-length]") == str(d["length"])
    other = next(v for v in values(pg, "[data-sw-length]") if v != str(d["length"]))
    choose(pg, "[data-sw-length]", other)
    wait(pg, "document.querySelector('[data-sw-meta]').textContent.indexOf('which %s-day period') === 0" % other, 120)
    pg.close()
