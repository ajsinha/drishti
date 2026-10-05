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

"""The metric panel in the workbench: added from the palette (family facts), drawn as a big figure in a named group, and edited in
the inspector (the unit and the change). Fixtures: test_workbench_browser.py's."""
import json

from test_workbench_browser import browser, open_design, page, settle, state, wait  # noqa: F401 - fixtures and helpers
from wb_live import live_console  # noqa: F401 - the fixture

SAMPLE = json.dumps({"tradeId": "T-1", "mtm": 1250, "pnl1d": -40})
SUTRA = ("rachana: 1\nsutra: metric-test\nversion: 1\nmatch: { kind: trade }\ntitle: { id: $.tradeId }\n"
         "panels:\n  - { id: facts, kind: kv, title: Facts, columns: [ { label: MTM, bind: $.mtm } ] }\n")


def option(page, panel, name):
    return page.evaluate("([p, o]) => window.DrishtiWB.model(window.drishtiWorkbench.store.state.yaml).panels.filter(x => x.id === p)[0].values[o]",
                         [panel, name])


def test_a_metric_is_added_from_the_palette_drawn_as_a_named_figure_and_edited_in_the_inspector(live_console, page):
    open_design(page, live_console, "metric", sutra=SUTRA, files={"t.json": SAMPLE})
    tile = page.locator('[data-palette] .wb-kind[data-kind="metric"]')
    assert tile.count() == 1 and "facts" in tile.get_attribute("data-text") and "headline figure" in tile.get_attribute("data-text")
    rev = state(page, "rev")
    page.get_by_role("button", name="Add a metric panel").click()
    settle(page, rev)
    wait(page, "!!document.querySelector('[data-preview] .wb-sel[data-panel]')")
    new = page.evaluate("document.querySelector('[data-preview] .wb-sel[data-panel]').dataset.panel")
    assert option(page, new, "value") == "$.mtm"                              # seeded with the first number of the samples
    box = page.locator(f'[data-preview] [data-panel="{new}"] .metric')
    box.wait_for()
    assert box.get_attribute("role") == "group" and box.get_attribute("aria-label") == "Metric: 1250"
    assert box.locator(".metric-v").inner_text() == "1250"
    page.locator(f'[data-preview] [data-panel="{new}"] .pnl-h').click()
    page.locator("[data-inspector] .bs-role").first.wait_for()
    for name, text in (("unit", "USD"), ("delta", "$.pnl1d"), ("deltaFmt", "signed0")):
        rev = state(page, "rev")
        field = page.get_by_label(name, exact=True)
        field.fill(text)
        field.blur()
        settle(page, rev)
        assert option(page, new, name) == text
    wait(page, f"!!document.querySelector('[data-preview] [data-panel=\"{new}\"] .metric-d')")
    box = page.locator(f'[data-preview] [data-panel="{new}"] .metric')
    assert box.locator(".metric-d").inner_text() == "−40" and box.locator(".metric-d").get_attribute("class").endswith("t-neg")
    assert box.locator(".metric-u").inner_text() == "USD"
    assert "USD" in box.get_attribute("aria-label") and "change −40" in box.get_attribute("aria-label")
