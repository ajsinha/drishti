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
"""Driving a <drishti-select> (drishti-inputs' Select) from a screen's browser test the way a person does: open it with a click, then
click the option. The component is tested deeply against its harness (test_elements_inputs_browser.py); screens test their wiring."""

OPTIONS = "(s) => { const c = document.querySelector(s).component; return c ? c.options.map((o) => ({ value: String(o.value), label: o.label, disabled: o.disabled, group: o.group })) : []; }"


def options(pg, sel):
    """The options of the select (sync Playwright page): [{value, label, disabled, group}], values as text."""
    return pg.evaluate(OPTIONS, sel)


def values(pg, sel):
    """The option values, as text."""
    return [o["value"] for o in options(pg, sel)]


def labels(pg, sel):
    """The option labels."""
    return [o["label"] for o in options(pg, sel)]


def value(pg, sel):
    """The value picked, as text ('' for the empty value, None when nothing is picked)."""
    v = pg.evaluate("(s) => document.querySelector(s).value", sel)
    return None if v is None else str(v)


def choose(pg, sel, val):
    """Opens the select and clicks the option whose value is val (compared as text); the list closes and drishti:change fires."""
    pg.locator(sel + " .di-select-control").click()
    pg.wait_for_selector(".di-select-pop [role=listbox]")
    i = pg.evaluate("([s, v]) => document.querySelector(s).component.matches.findIndex((o) => String(o.value) === v)", [sel, str(val)])
    assert i >= 0, (sel, val, values(pg, sel))
    pg.locator(".di-select-pop [role=option][data-i='%d']" % i).click()


# ---- Playwright's select_option and input_value on a <drishti-select> --------------------------------------------------------------
# drishti-inputs' Form draws its lists as <drishti-select> (ELEMENTS.md section 8.2), so a screen's test that picked from a native select
# with select_option picks from the Select the way a person does (open it, click the option); input_value reads the element's value. A
# native <select> is untouched. Installed once for the test session by conftest.py.

IS_SELECT = "(e) => !!e && e.tagName === 'DRISHTI-SELECT'"
OPTION_AT = ("(e, want) => { const w = [].concat(want.value, want.label).map(String);"
             " return e.component.matches.findIndex((o) => w.indexOf(String(o.value)) >= 0 || w.indexOf(String(o.label)) >= 0); }")


def _pick(locator, value=None, label=None):
    """Opens the Select under locator and clicks the option whose value or label is asked (a list: any of them)."""
    as_list = lambda x: [] if x is None else list(x) if isinstance(x, (list, tuple)) else [x]   # noqa: E731
    want = {"value": as_list(value), "label": as_list(label)}
    locator.locator(".di-select-control").click()
    page = locator.page
    page.wait_for_selector(".di-select-pop [role=listbox]")
    i = locator.evaluate(OPTION_AT, want)
    assert i >= 0, ("no such option", want, locator.evaluate("e => e.component.options.map((o) => [o.value, o.label])"))
    page.locator(".di-select-pop [role=option][data-i='%d']" % i).click()
    return [str(locator.evaluate("e => e.value"))]


def install_playwright_shim():
    """Makes Page.select_option, Locator.select_option and Locator.input_value work on a <drishti-select> (sync API)."""
    try:
        from playwright.sync_api import Locator, Page
    except ImportError:                                            # no Playwright: nothing to do
        return
    if getattr(Locator, "_drishti_select", False):
        return
    loc_select, loc_value = Locator.select_option, Locator.input_value

    def select_option(self, value=None, *, index=None, label=None, element=None, **kw):
        if index is None and element is None and self.first.evaluate(IS_SELECT):
            return _pick(self.first, value, label)
        return loc_select(self, value, index=index, label=label, element=element, **kw)

    def input_value(self, **kw):
        if self.evaluate(IS_SELECT):
            v = self.evaluate("e => e.value")
            return "" if v is None else str(v)
        return loc_value(self, **kw)

    def page_select_option(self, selector, value=None, *, index=None, label=None, element=None, **kw):
        return select_option(self.locator(selector), value, index=index, label=label, element=element, **kw)

    Locator.select_option, Locator.input_value, Page.select_option = select_option, input_value, page_select_option
    Locator._drishti_select = True
