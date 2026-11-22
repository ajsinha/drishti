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

"""The role Homes in a real browser, a real server behind (docs/architecture/ROLE_HOME_SUTRAS.md): the risk manager's Home and the trader's,
each opened by name. Written with the Homes and not yet run. It checks what the console page owns: the Home is found and titled, the
picker appears only when more than one Home is offered (the stack's person is an administrator, so both are offered), the choice is kept
and the start Home follows it after a reload, "Arrange my Home" is offered only where the Home allows it and toggles the arrange mode, the
Home's Sutra and its home tables reach the page as data (every table the Sutra reads is there, as rows or empty), no sideways scroll on a
phone, and a click event from a figure opens its mnemonic in the terminal. The figures themselves are the engines' and are tested there."""
import json

import pytest

from test_designer_browser import page_in, stack  # noqa: F401 - the stack and pages
from test_workbench_browser import browser, wait  # noqa: F401

HOMES = ("risk-manager", "trader")


def data(pg):
    return json.loads(pg.evaluate("document.getElementById('role-home-data').textContent"))


@pytest.mark.parametrize("home", HOMES)
def test_a_home_opens_with_its_sutra_and_every_table_it_reads(browser, stack, home):
    pg = page_in(browser, "dark", {"width": 1440, "height": 950})
    pg.goto(f"{stack}/home/role/{home}")
    wait(pg, "document.querySelector('[data-role-home]')")
    assert pg.locator("[data-role-home]").get_attribute("data-role-home") == home
    d = data(pg)
    assert d["sutraName"] == f"home-{home}" and "user-can-arrange" in d["sutra"]
    import re
    wanted = set(re.findall(r"table:\s*(home_[a-z_]+)", d["sutra"]))
    assert wanted and wanted <= set(d["tables"]), f"tables the Sutra reads but the page lacks: {wanted - set(d['tables'])}"
    assert all(isinstance(rows, list) for rows in d["tables"].values())
    pg.close()


def test_the_picker_the_choice_and_arrange(browser, stack):
    pg = page_in(browser, "dark", {"width": 1440, "height": 950})
    pg.goto(f"{stack}/home/role/risk-manager")
    wait(pg, "document.querySelector('[data-role-home]')")
    pick = pg.locator("[data-role-home-pick]")
    assert pick.count() == 1, "an administrator is offered more than one Home: the picker shows"
    with pg.expect_navigation():
        pick.select_option("trader")
    assert pg.locator("[data-role-home]").get_attribute("data-role-home") == "trader"
    pg.goto(f"{stack}/home/role")                                       # the start Home follows the choice
    wait(pg, "document.querySelector('[data-role-home]')")
    assert pg.locator("[data-role-home]").get_attribute("data-role-home") == "trader"
    arrange = pg.locator("[data-role-home-arrange]")
    assert arrange.count() == 1 and arrange.get_attribute("aria-pressed") == "false"
    arrange.click()
    assert arrange.get_attribute("aria-pressed") == "true" and pg.evaluate("document.querySelector('[data-role-home]').classList.contains('is-arranging')")
    arrange.click()
    assert arrange.get_attribute("aria-pressed") == "false"
    pg.close()


def test_a_home_on_a_phone_does_not_scroll_sideways_and_a_figure_click_opens_its_mnemonic(browser, stack):
    pg = page_in(browser, "light", {"width": 390, "height": 800})
    pg.goto(f"{stack}/home/role/risk-manager")
    wait(pg, "document.querySelector('[data-role-home]')")
    assert pg.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    pg.evaluate("document.querySelector('[data-role-home]').dispatchEvent(new CustomEvent('rachana:event', "
                "{bubbles: true, detail: {name: 'open0', payload: {command: 'VAR BANK'}}}))")
    pg.wait_for_url("**/go?q=VAR%20BANK*", timeout=5000)
    pg.close()
