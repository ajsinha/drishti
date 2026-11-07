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
