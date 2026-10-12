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

"""The console fixes of the W1 QA round, in Chromium over the real server on scratch ports 18914 and 17914 (the stack of test_w1_model_designer_browser.py,
its jar taken from the main checkout when the worktree has none): Analyse opens a model or a pack dataset (grouped picker, rows through the model query);
an alert watches a measure of a model through pickers; a model in a private workspace shows Publish disabled with a Move button; a model shows who uses it
and starts reports and boards; the security rule's role picker and the roles note; the Datasets list leaves the Security tab; the Models page puts models
and your datasets before the packs' (grouped, collapsible); the Subscribe form builds its schedule from choices; the new-dataset wizard fits 390 px.

Skipped when Playwright, Chromium or a built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
from __future__ import annotations

import os
from pathlib import Path

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect  # noqa: E402

import test_w1_model_designer_browser as base  # noqa: E402

MAIN = Path(os.environ.get("DRISHTI_MAIN_CHECKOUT", "/home/ashutosh/IdeaProjects/drishti"))
if not list((base.ROOT / "drishti-server" / "target").glob("drishti-server-*-exec.jar")) and (MAIN / "drishti-server" / "target").exists():
    base.ROOT = MAIN                                   # the jar, the packs and the sample config are read-only inputs; the console under test is this checkout's
base.SERVER_PORT, base.CONSOLE_PORT = 18914, 17914
base.SERVER, base.CONSOLE_URL = f"http://127.0.0.1:{base.SERVER_PORT}", f"http://127.0.0.1:{base.CONSOLE_PORT}"
from test_w1_model_designer_browser import browser, clean, fetch, sign_in, stack  # noqa: E402,F401

CONSOLE_URL = base.CONSOLE_URL


def test_analyse_opens_a_pack_dataset_through_the_grouped_picker(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/bi/explore")
    sel = page.locator("[data-dataset]")
    expect(sel.locator("optgroup")).not_to_have_count(0, timeout=20000)
    labels = page.evaluate("[...document.querySelectorAll('[data-dataset] optgroup')].map(g => g.label)")
    assert any(x.startswith("Pack ") for x in labels), labels
    sel.select_option("dataset:pack.trading.trade")
    expect(page.locator("[data-fields-box]")).to_be_visible(timeout=20000)
    page.click("[data-open-go]")
    expect(page.locator("[data-info]")).to_contain_text("rows", timeout=30000)
    clean(page)
    page.context.close()


def test_an_alert_on_a_measure_of_a_model_uses_pickers(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/alerts/new")
    page.select_option('select[name="source_type"]', "model")
    model = page.locator("[data-al-model]")
    expect(model.locator("option")).not_to_have_count(1, timeout=20000)
    model.select_option("dataset:pack.trading.trade")
    expect(page.locator("[data-al-measure] option")).not_to_have_count(1, timeout=20000)
    page.select_option("[data-al-measure]", index=1)
    page.click("[data-al-addfilter]")
    expect(page.locator("[data-al-filter]")).to_have_count(1)
    page.click("[data-check-now]")
    expect(page.locator("[data-check-out]")).not_to_have_text("", timeout=30000)
    clean(page)
    page.context.close()


def test_a_private_model_cannot_dead_end_and_a_model_shows_its_users(browser):
    page = sign_in(browser, "wes")
    made = fetch(page, "POST", "/bi/model-api/new", {"title": "QA private model", "workspace": "me", "datasets": ["pack.trading.trade"]})
    assert made["status"] == 200, made
    page.goto(CONSOLE_URL + made["body"]["href"])
    expect(page.locator('[data-md-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    expect(page.locator("[data-publish]")).to_be_disabled()
    expect(page.locator("[data-move]")).to_be_visible()
    expect(page.locator("[data-report]")).to_contain_text("New report on this model")
    expect(page.locator("[data-board]")).to_be_visible()
    expect(page.locator("[data-used-box]")).to_be_visible()
    page.click('[data-tab="security"]')
    expect(page.locator(".bi-md-side")).to_be_hidden()
    expect(page.locator("[data-roles-note]")).to_contain_text("Roles without a rule see no rows")
    page.click('[data-tab="canvas"]')
    expect(page.locator(".bi-md-side")).to_be_visible()
    clean(page)
    page.context.close()


def test_the_models_page_lists_yours_first_and_the_packs_grouped(browser):
    page = sign_in(browser, "wes")
    expect(page.locator('[data-models-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    heads = page.evaluate("[...document.querySelectorAll('[data-models-root] h2')].map(h => h.textContent.trim())")
    heads = [x for x in heads if x != "New model"]                    # the form of New model is hidden until asked for
    assert heads[0].endswith("Your models") and heads[1].endswith("Your datasets") and "pack" in heads[2].lower(), heads
    expect(page.locator("[data-pack-group]").first).to_be_visible()
    page.context.close()


def test_subscribe_builds_its_schedule_from_choices(browser):
    page = sign_in(browser, "wes")
    page.goto(CONSOLE_URL + "/reports/subscribe?type=report&id=file:report:trades-overview&title=Trades")
    page.select_option("[data-when-kind]", "weekly")
    page.select_option("[data-when-day]", "fri")
    page.fill("[data-when-time]", "18:00")
    assert page.input_value('input[name="schedule"]') == "weekly fri 18:00 business"
    page.select_option("[data-when-kind]", "month-end")
    assert page.input_value('input[name="schedule"]') == "monthly last 18:00 business"
    clean(page)
    page.context.close()


def test_the_new_dataset_wizard_fits_a_phone(browser):
    page = sign_in(browser, "wes", width=390)
    page.goto(CONSOLE_URL + "/bi/datasets/new-layer")
    expect(page.locator('[data-lw-root][data-ready="yes"]')).to_have_count(1, timeout=30000)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1"), "the page scrolls sideways"
    expect(page.locator("[data-guided]")).to_be_visible()
    page.context.close()
