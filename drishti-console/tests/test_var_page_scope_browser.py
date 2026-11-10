#
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
#
"""The VaR page as the owner approved it (docs/lenses/mockups/var/var-page.html and why-it-moved.html, layout A; VAR_GUIDE.md sections
3.2 and 3.11), in a real browser over the market-risk pack's committed VaR data:

- the scope tree shows only the chosen scope: the bank its whole tree; a desk that desk and below, with a breadcrumb up that goes back;
  a system basket its own positions by the chosen hierarchy (the engine's GET /tree?within=, figures computed in Java, each node's VaR
  the engine's for the basket's part in it), with "All of the bank" to go back;
- no empty Explain pane under the tree; Explain › on every row (the one control), shown on hover and on the chosen row, opening Why it
  moved; the toolbar in one row (baskets, the matured switch, Backtest, MR cube, What-if, Explain); the data versions behind an ⓘ;
- Why it moved as a page (VAR <scope> EXPLAIN [vs <date>]): the compare date (Previous day, 1 week, Month end, Pick a date) in the link,
  four figures, the waterfall first and wide, a clicked step's biggest contributors and plain words on its right, Formula, Inputs &
  lineage and Checks folded below; no empty box; the page lays out by the page walk's rules.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing."""
import json
import urllib.parse
import urllib.request

import pytest

from page_walk import layout_faults
from test_var_explorer_browser import open_var, painted, shot
from test_workbench_browser import browser, wait  # noqa: F401 - fixtures and helpers
from var_stack import VAR_TRADES, var_data, var_lake, var_server

BASKET = "PORT:PF-USD-RATES"


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    yield from var_server(tmp_path_factory, var_lake(tmp_path_factory, var_data(*VAR_TRADES)), DRISHTI_VAR_SCENARIO_WINDOW="all")


def _get(base, path):
    with urllib.request.urlopen(base + path, timeout=120) as r:
        return json.loads(r.read())


TREE_SCOPES = "[...document.querySelector('[data-vx-tree] drishti-tree').tree.model.all()].map((n) => n.data.scope)"


def _tree_scopes(pg):
    wait(pg, "!!(document.querySelector('[data-vx-tree] drishti-tree') || {}).tree && !document.querySelector('[data-vx-tree]').hasAttribute('aria-busy')")
    return pg.evaluate(TREE_SCOPES)


def test_the_tree_shows_only_the_chosen_scope(stack, browser):
    base = str(stack)
    pg = open_var(browser, base, "/var?scope=BANK", size={"width": 1366, "height": 900})
    painted(pg, "BANK")
    whole = _tree_scopes(pg)
    assert len(whole) > 5 and pg.locator("[data-vx-tree-crumb]").is_hidden()
    # no empty Explain pane under the tree (the tree's grid has no hook of its own: Explain › is on its rows)
    assert pg.evaluate("[...document.querySelectorAll('[data-vx-tree] .dg-split')].every((e) => e.offsetParent === null)")
    assert "no Explain hook" not in pg.locator("[data-vx-tree]").inner_text()
    # the toolbar in one row; the data version behind its ⓘ
    tops = pg.evaluate("[...document.querySelectorAll('.vx-bar > *')].filter((e) => e.offsetParent !== null).map((e) => Math.round(e.getBoundingClientRect().top))")
    assert max(tops) - min(tops) <= 12, tops
    assert pg.locator("[data-vx-version]").is_hidden()
    pg.locator(".vx-ver > summary").click()
    assert pg.locator("[data-vx-version]").is_visible()
    assert layout_faults(pg) == []

    # a desk: only it and below, a breadcrumb up
    desk = "NODE:MGMT~DESK-RATES" if "NODE:MGMT~DESK-RATES" in whole else [s for s in whole if "DESK" in s][0]
    pg.evaluate("(s) => { const t = document.querySelector('[data-vx-tree] drishti-tree').tree; const n = t.model.all().find((x) => x.data.scope === s); t.select(n.id); }", desk)
    painted(pg, desk)
    wait(pg, "!document.querySelector('[data-vx-tree-crumb]').hidden")
    sub = _tree_scopes(pg)
    wait(pg, "%s[0] === %r" % (TREE_SCOPES, desk))
    sub = _tree_scopes(pg)
    assert sub[0] == desk and len(sub) < len(whole) and set(s for s in sub if s.startswith("NODE:")) <= set(whole)
    crumb = pg.locator("[data-vx-tree-crumb] a")
    assert crumb.count() >= 1
    crumb.first.click()                                                            # back up to the bank: the whole tree again
    painted(pg, whole[0])
    wait(pg, "%s.length === %d" % (TREE_SCOPES, len(whole)))

    # Explain › on a row: hidden until the row is hovered or chosen; it opens Why it moved in the drawer
    shot(pg, "var-page-bank")
    links = pg.locator("[data-vx-tree] [data-explain-link]")
    assert links.count() >= 3
    assert pg.evaluate("[...document.querySelectorAll('[data-vx-tree] [data-explain-link]')].every((a) => JSON.parse(a.dataset.explain).kind === 'var' && a.getAttribute('href').includes('tab=why'))")
    chosen = pg.locator("[data-vx-tree] .vx-current [data-explain-link], [data-vx-tree] .dg-selected [data-explain-link]").first
    assert chosen.is_visible()
    chosen.click()
    wait(pg, "document.querySelector('[data-explain-drawer]') && document.querySelector('[data-explain-drawer]').dataset.state === 'ready'")
    assert pg.evaluate("document.querySelector('[data-explain-drawer]').dataset.tab") == "why"
    pg.keyboard.press("Escape")
    pg.close()


def test_a_basket_shows_its_own_positions_by_the_chosen_hierarchy(stack, browser):
    base = str(stack)
    t = _get(base, "/api/var/tree?within=" + urllib.parse.quote(BASKET))
    assert t["within"] == BASKET and t["nodes"][0]["scope"] == BASKET and t["nodes"][0]["level"] == "basket"
    head = _get(base, "/api/var/scope?scope=" + urllib.parse.quote(BASKET))
    assert t["nodes"][0]["var"] == round(head["var"])
    part = t["nodes"][-1]                                                           # a node's figure: the engine's for the basket's part in it
    assert part["scope"].startswith("(" + BASKET + ") & NODE:")
    assert part["var"] == round(_get(base, "/api/var/scope?scope=" + urllib.parse.quote(part["scope"]))["var"])
    pg = open_var(browser, base, "/var?scope=" + urllib.parse.quote(BASKET), size={"width": 1366, "height": 900})
    painted(pg, BASKET)
    pg.wait_for_timeout(3000)
    shot(pg, "var-page-basket")
    wait(pg, "%s[0] === %r" % (TREE_SCOPES, BASKET))
    scopes = _tree_scopes(pg)
    assert len(scopes) == len(t["nodes"]) and all(s == BASKET or s.startswith("(" + BASKET + ") & ") for s in scopes)
    assert "All of the bank" in pg.locator("[data-vx-tree-crumb]").inner_text()
    pg.locator("[data-vx-all-bank]").click()
    painted(pg, "BANK")
    wait(pg, "!%s.some((s) => s.startsWith('('))" % TREE_SCOPES)
    pg.close()


def test_why_it_moved_layout_a_with_the_compare_date(stack, browser):
    base = str(stack)
    pg = browser.new_page(viewport={"width": 1366, "height": 900})
    pg.goto(base + "/go?q=" + urllib.parse.quote("VAR BANK EXPLAIN <GO>"))
    wait(pg, "document.querySelector('[data-explain-page]') && document.querySelector('[data-explain-page]').dataset.state === 'ready'", seconds=120)
    shot(pg, "why-it-moved-a")
    assert pg.evaluate("document.querySelector('[data-explain-page]').dataset.layout") == "why-a"
    assert pg.locator(".dx-kpi").count() == 4
    order = pg.evaluate("['[data-dx-compare]', '.dx-kpis', '.dx-why-row', '.dx-folds'].map((s) => document.querySelector(s).getBoundingClientRect().top)")
    assert order == sorted(order)
    wf = pg.locator("[data-dx-waterfall]").bounding_box()
    side = pg.locator("[data-dx-side]").bounding_box()
    assert wf["width"] > 1.5 * side["width"] and abs(wf["y"] - side["y"]) < 40          # the waterfall first and wide, the contributors on its right
    assert pg.locator("[data-dx-side] h2").inner_text().startswith("Biggest contributors · ")
    assert pg.locator("[data-dx-words]").inner_text().strip()
    assert pg.evaluate("[...document.querySelectorAll('.dx-folds > details')].map((d) => [d.dataset.dxPart, d.open])")[:2] == [["formula", False], ["inputs", False]]
    # no empty boxes
    assert pg.evaluate("[...document.querySelectorAll('.dx-kpi, .dx-card, .dx-fold')].filter((e) => e.offsetParent !== null && !e.textContent.trim()).length") == 0
    assert layout_faults(pg) == []
    to = pg.evaluate("document.querySelector('[data-dx-compare] b:first-of-type').textContent")
    # 1 week: the link carries it and the page compares with the newest business date on or before it
    pg.locator('[data-dx-compare="week"]').click()
    wait(pg, "new URL(location.href).searchParams.get('from') && document.querySelector('[data-explain-page]').dataset.state === 'ready'", seconds=120)
    asked = pg.evaluate("new URL(location.href).searchParams.get('from')")
    import datetime
    assert asked == (datetime.date.fromisoformat(to) - datetime.timedelta(days=7)).isoformat()
    wait(pg, "document.querySelector('[data-dx-compare=\"week\"]').classList.contains('on')")
    shown = pg.evaluate("document.querySelectorAll('[data-dx-compare] b')[1].textContent")
    assert shown <= asked
    # Previous day takes it back out
    pg.locator('[data-dx-compare="prev"]').click()
    wait(pg, "!new URL(location.href).searchParams.get('from') && document.querySelector('[data-explain-page]').dataset.state === 'ready'", seconds=120)
    # the command's own compare date
    pg.goto(base + "/go?q=" + urllib.parse.quote("VAR BANK EXPLAIN vs " + asked + " <GO>"))
    wait(pg, "document.querySelector('[data-explain-page]') && document.querySelector('[data-explain-page]').dataset.state === 'ready'", seconds=120)
    assert pg.evaluate("new URL(location.href).searchParams.get('from')") == asked
    pg.close()
