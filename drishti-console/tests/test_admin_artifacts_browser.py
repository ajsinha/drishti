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

"""Admin → Artifacts in Chromium, over the real server and console (tests/artifact_stack.py: ports 18936 and 17936): the list shows packs, connector files
and BI files with state and origin; uninstalling a pack other things depend on is refused with the list of what depends on it, and the administrator disables
it instead; an unused connector is uninstalled (moved to the archive), shown on the Uninstalled tab, and restored; a name that was typed wrongly does nothing;
a phone gets finger-sized controls; and no page breaks the content policy.

The tests run in order and share one stack. Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with
pytest -n (the ports are fixed)."""
import re

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import artifact_stack  # noqa: E402
from artifact_stack import CONSOLE_URL  # noqa: E402


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (artifact_stack.SERVER_PORT, artifact_stack.CONSOLE_PORT):
        if artifact_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = artifact_stack.Stack(tmp_path_factory.mktemp("artifacts")).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))
    yield s
    s.close()


@pytest.fixture(scope="module")
def browser(stack):
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


VIOLATIONS = "window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));"


def open_page(browser, path="/admin/artifacts", wait="[data-artifacts]", **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 1000}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + path)
    page.wait_for_selector(wait)
    page.errors = errors
    return page


def row(page, name):
    return page.locator(f"tr[data-artifact][data-name='{name}']")


def test_the_list_shows_packs_connectors_and_bi_files_with_state_and_origin(stack, browser):
    page = open_page(browser)
    for name in ("lab-core", "lab-base", "lab-child", "lab-solo", "lab-files", "scratch-conn", "lab-items", "lab-overview", "lab-spare"):
        expect(row(page, name)).to_have_count(1)
    expect(row(page, "lab-core").locator("td").nth(2)).to_contain_text("shipped")          # a pack of the packs folder
    expect(row(page, "lab-base").locator("td").nth(2)).to_contain_text("archive")          # deployed from an archive
    expect(row(page, "lab-base").locator("td").nth(1)).to_contain_text("on")
    expect(row(page, "lab-items").locator("td").first).to_contain_text("dataset")
    expect(row(page, "lab-files").locator("td").nth(2)).to_contain_text("file")
    # the filter narrows the list; kind and status filters work together
    page.fill("[data-filter]", "lab-s")
    expect(page.locator("tr[data-artifact]:not([hidden])")).to_have_count(2)               # lab-solo and lab-spare
    page.fill("[data-filter]", "")
    page.select_option("[data-kind-filter]", "connector")
    expect(page.locator("tr[data-artifact]:not([hidden])")).to_have_count(2)
    assert page.evaluate("window.__csp") == [] and page.errors == []


def test_uninstalling_a_pack_with_dependants_is_refused_and_disabling_is_the_way_out(stack, browser):
    page = open_page(browser)
    row(page, "lab-base").get_by_role("button", name="Uninstall lab-base").click()
    dlg = page.locator("[data-uninstall-dialog]")
    expect(dlg).to_be_visible()
    expect(dlg.locator("[data-art-text]")).to_contain_text("is in use")
    expect(dlg.locator("[data-group='packs']")).to_contain_text("lab-child")                 # the pack that extends it
    expect(dlg.locator("[data-group='datasets']")).to_contain_text("lab-items")               # the dataset over its kind
    expect(dlg.locator("[data-group='reports']")).to_contain_text("lab-overview")             # and the report on that dataset
    expect(dlg.locator("[data-art-go]")).to_be_hidden()                                       # plain Uninstall is not offered while it is in use
    expect(dlg.locator("[data-art-disable]")).to_be_visible()
    # taking the dependants along needs the name typed
    dlg.locator("[data-art-cascade]").check()
    expect(dlg.locator("[data-art-go]")).to_be_visible()
    expect(dlg.locator("[data-art-go]")).to_be_disabled()
    dlg.locator("[data-art-typed]").fill("lab-bas")
    expect(dlg.locator("[data-art-go]")).to_be_disabled()
    dlg.locator("[data-art-cascade]").uncheck()
    # disable instead: kept on disk, switched off; the child that builds on it must go too
    dlg.locator("[data-art-disable]").click()
    expect(dlg.locator("[data-art-msg]")).to_contain_text("DRS-5041")
    expect(dlg.locator("[data-art-text]")).to_contain_text("Disabling keeps lab-base")
    dlg.locator("[data-art-cascade]").check()
    dlg.locator("[data-art-go]").click()
    page.wait_for_selector("tr[data-artifact][data-name='lab-base'][data-state='off']")
    expect(row(page, "lab-child")).to_have_attribute("data-state", "off")
    expect(page.locator("[data-msg]")).to_contain_text("Disabled pack lab-base")
    expect(row(page, "lab-base").get_by_role("button", name="Enable lab-base")).to_be_visible()
    # switching the child on switches on what it builds on: both are back, and the kinds return
    row(page, "lab-child").get_by_role("button", name="Enable lab-child").click()
    page.wait_for_selector("tr[data-artifact][data-name='lab-child'][data-state='on']")
    expect(row(page, "lab-base")).to_have_attribute("data-state", "on")
    assert page.evaluate("window.__csp") == [] and page.errors == []


def test_a_pack_named_in_the_site_configuration_is_not_uninstalled(stack, browser):
    page = open_page(browser)
    row(page, "lab-core").get_by_role("button", name="Uninstall lab-core").click()
    dlg = page.locator("[data-uninstall-dialog]")
    expect(dlg.locator("[data-art-text]")).to_contain_text("Nothing depends on lab-core")
    dlg.locator("[data-art-go]").click()
    expect(dlg.locator("[data-art-msg]")).to_contain_text("DRS-5044")
    expect(dlg.locator("[data-art-msg]")).to_contain_text("drishti.packs.enabled")
    dlg.locator("[data-art-keep]").click()


def test_an_unused_connector_is_archived_and_restored(stack, browser):
    page = open_page(browser)
    row(page, "scratch-conn").get_by_role("button", name="Uninstall scratch-conn").click()
    dlg = page.locator("[data-uninstall-dialog]")
    expect(dlg.locator("[data-art-text]")).to_contain_text("Nothing depends on scratch-conn")
    expect(dlg.locator("[data-art-text]")).to_contain_text("restored")
    dlg.locator("[data-art-go]").click()
    page.wait_for_selector("[data-msg]:has-text('Uninstalled connector scratch-conn')")
    expect(row(page, "scratch-conn")).to_have_count(0)
    assert not (stack.work / "connectors" / "scratch-conn.yaml").exists()                    # moved out of the folder the server watches
    assert list((stack.work / "data" / "uninstalled").glob("*-connector-scratch-conn/payload/scratch-conn.yaml"))
    page.click("#artTabUninstalled")
    expect(page.locator("tr[data-archive]")).to_have_count(1)
    expect(page.locator("tr[data-archive]")).to_contain_text("scratch-conn")
    page.get_by_role("button", name="Restore scratch-conn").click()
    page.wait_for_selector("[data-msg]:has-text('Restored scratch-conn')")
    expect(row(page, "scratch-conn")).to_have_count(1)
    assert (stack.work / "connectors" / "scratch-conn.yaml").exists()
    page.click("#artTabUninstalled")
    expect(page.locator("tr[data-archive]")).to_have_count(0)


def test_a_wrongly_typed_name_deletes_nothing_and_the_right_one_does(stack, browser):
    page = open_page(browser)
    row(page, "lab-spare").get_by_role("button", name="Uninstall lab-spare").click()
    page.locator("[data-uninstall-dialog] [data-art-go]").click()
    page.wait_for_selector("[data-msg]:has-text('Uninstalled python lab-spare')")
    page.click("#artTabUninstalled")
    page.get_by_role("button", name="Delete lab-spare permanently").click()
    typed = page.locator("[data-typed-dialog]")
    expect(typed).to_be_visible()
    typed.locator("[data-typed-input]").fill("lab-spar")
    typed.locator("[data-typed-yes]").click()
    expect(typed.locator("[data-typed-msg]")).to_contain_text("does not match")
    assert list((stack.work / "data" / "uninstalled").glob("*-bi-lab-spare"))                # still there
    typed.locator("[data-typed-input]").fill("lab-spare")
    typed.locator("[data-typed-yes]").click()
    page.wait_for_selector("[data-msg]:has-text('Deleted the archive of lab-spare permanently')")
    assert not list((stack.work / "data" / "uninstalled").glob("*-bi-lab-spare"))


def test_a_bi_file_is_checked_before_it_is_placed(stack, browser, tmp_path):
    page = open_page(browser)
    good = tmp_path / "lab-more.dataset.yaml"
    good.write_text("dataset: lab-more\ntitle: Lab more\ntables:\n  item:\n    source: { connector: lab-files, kind: lab-item }\n    columns:\n      itemId: { type: string, key: true }\n", encoding="utf-8")
    page.set_input_files("[data-bi-file]", str(good))
    expect(page.locator("[data-bi-result]")).to_contain_text("Valid dataset lab-more")
    assert not (stack.work / "bi" / "datasets" / "lab-more.dataset.yaml").exists()           # a check writes nothing
    page.click("[data-bi-place]")
    page.wait_for_selector("[data-msg]:has-text('Placed lab-more.dataset.yaml')")
    assert (stack.work / "bi" / "datasets" / "lab-more.dataset.yaml").exists()
    expect(row(page, "lab-more")).to_have_count(1)
    bad = tmp_path / "lab-bad.dataset.yaml"
    bad.write_text("dataset: other\n", encoding="utf-8")
    page.set_input_files("[data-bi-file]", str(bad))
    expect(page.locator("[data-bi-result]")).to_contain_text("rename the file")
    expect(page.locator("[data-bi-place]")).to_be_hidden()


def test_the_packs_and_connectors_pages_use_the_same_dialog(stack, browser):
    page = open_page(browser, "/admin/packs", "[data-pack-admin]")
    page.locator("tr[data-pack='lab-solo']").get_by_role("button", name="Uninstall lab-solo").click()
    dlg = page.locator("[data-uninstall-dialog]")
    expect(dlg.locator("[data-art-text]")).to_contain_text("Nothing depends on lab-solo")
    dlg.locator("[data-art-keep]").click()
    page = open_page(browser, "/admin/connectors", "[data-connectors]")
    page.locator("tr[data-conn='scratch-conn']").get_by_role("button", name="Uninstall scratch-conn").click()
    expect(page.locator("[data-uninstall-dialog] [data-art-text]")).to_contain_text("Nothing depends on scratch-conn")


def test_a_phone_gets_finger_sized_controls_and_no_sideways_scroll(stack, browser):
    page = open_page(browser, viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
    small = page.evaluate("""() => [...document.querySelectorAll('[data-artifacts] .adm-actions .fk, [data-artifacts] [role=tab], [data-artifacts] .adm-bar select, [data-artifacts] .adm-bar input')]
        .filter(e => e.offsetParent !== null).map(e => [e.textContent.trim().slice(0, 20), Math.round(e.getBoundingClientRect().height)]).filter(x => x[1] < 44)""")
    assert small == [], small
    row(page, "lab-solo").get_by_role("button", name="Uninstall lab-solo").click()
    dlg = page.locator("[data-uninstall-dialog]")
    expect(dlg.locator("[data-art-text]")).to_contain_text("Nothing depends on lab-solo")
    expect(dlg.locator("[data-art-cascade-row]")).to_be_hidden()                              # nothing to take along
    boxes = page.evaluate("""() => [...document.querySelectorAll('[data-uninstall-dialog] button')].filter(e => e.offsetParent !== null)
        .map(e => [e.textContent.trim(), Math.round(e.getBoundingClientRect().height)]).filter(x => x[1] < 44)""")
    assert boxes == [], boxes
    assert re.search(r"Nothing depends", dlg.inner_text())
