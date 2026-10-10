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

"""Rupaka phase 1 in Chromium, over the real server and console (tests/bi_stack.py: ports 18983 and 17983, security on, five users): the RUPAKA keyword and
its type-ahead, the BI menu (shown to people with the bi power only), the home, the catalogue (search, filters, sort, audience), a workspace created and
shared from the page, publishing with review, the keys, a phone (390 px, finger-sized), both themes, and no policy violation anywhere.

Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import bi_stack  # noqa: E402
from bi_stack import CONSOLE_URL, PASSWORD  # noqa: E402


@pytest.fixture(scope="module")
def stack(tmp_path_factory):
    for port in (bi_stack.SERVER_PORT, bi_stack.CONSOLE_PORT):
        if bi_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        s = bi_stack.Stack(tmp_path_factory.mktemp("bi")).start()
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


def sign_in(browser, user, **ctx):
    context = browser.new_context(**{"viewport": {"width": 1400, "height": 900}, **ctx})
    context.add_init_script(VIOLATIONS)
    page = context.new_page()
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(CONSOLE_URL + "/login?next=/bi")
    page.fill("#lu", user)
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/bi")
    page.errors = errors
    return page


def clean(page):
    assert page.evaluate("window.__csp") == [], "a policy violation"
    assert page.errors == [], page.errors


def test_the_keyword_opens_home_catalogue_and_my_workspace_in_any_case(stack, browser):
    page = sign_in(browser, "cat")
    page.goto(CONSOLE_URL + "/t")
    for typed, path in (("rupaka", "/bi"), ("RUPAKA CATALOGUE", "/bi/catalogue"), ("Rupaka workspace", "/bi/w/"), ("RUPAKA <GO>", "/bi")):
        page.goto(CONSOLE_URL + "/t")
        page.fill("#cmdInput", typed)
        page.press("#cmdInput", "Enter")
        page.wait_for_url(f"**{path}*")
    page.goto(CONSOLE_URL + "/t")
    page.fill("#cmdInput", "rupaka Desk P&L")                     # a report by its title
    page.press("#cmdInput", "Enter")
    page.wait_for_url("**/bi/i/*")
    expect(page.locator("[data-title]")).to_have_text("Desk P&L")
    clean(page)


def test_type_ahead_offers_the_keyword_and_the_reports_you_may_open(stack, browser):
    page = sign_in(browser, "cat")
    page.goto(CONSOLE_URL + "/t")
    page.fill("#cmdInput", "rupa")
    expect(page.locator("#cmdList li").first).to_contain_text("Rupaka")
    page.fill("#cmdInput", "RUPAKA ")
    expect(page.locator("#cmdList")).to_contain_text("Catalogue")
    page.fill("#cmdInput", "rupaka exp")
    expect(page.locator("#cmdList")).to_contain_text("Counterparty exposure")
    clean(page)


def test_the_bi_menu_is_shown_to_people_with_the_power_only(stack, browser):
    cat = sign_in(browser, "cat")
    cat.goto(CONSOLE_URL + "/t")
    assert cat.locator('.tbar-menus a[href="/bi/catalogue"]').count() == 1 and cat.locator('.tbar-menus a[href="/admin/bi"]').count() == 0
    cat.locator("button:has-text('BI'), summary:has-text('BI')").first.click()
    expect(cat.locator('a[href="/bi/w/me"]').first).to_be_visible()
    ctx = browser.new_context()
    page = ctx.new_page()
    page.goto(CONSOLE_URL + "/login?next=/t")
    page.fill("#lu", "nobi")
    page.fill("#lp", PASSWORD)
    page.click("button[type=submit]")
    page.wait_for_url("**/t")
    assert page.locator('.tbar-menus a[href="/bi/catalogue"]').count() == 0
    assert page.goto(CONSOLE_URL + "/bi/catalogue").status == 403
    expect(page.locator("body")).to_contain_text("needs the bi power")
    page.goto(CONSOLE_URL + "/t")
    page.fill("#cmdInput", "RUPAKA")
    page.press("#cmdInput", "Enter")
    page.wait_for_url("**/t?error=*")
    expect(page.locator("body")).to_contain_text("not available to you")
    sign_in(browser, "sue").goto(CONSOLE_URL + "/t")


def test_home_shows_certified_recent_favourites_and_shared(stack, browser):
    page = sign_in(browser, "cat")
    certified = page.locator('[data-sec="certified"] .bi-card')
    expect(certified).to_have_count(1)
    expect(certified.first).to_contain_text("Desk P&L")
    expect(certified.first).to_contain_text("certified")
    expect(page.locator('[data-sec="shared"] .bi-card').first).to_be_visible()        # cat is a viewer of Rates Desk
    certified.first.locator("a").click()
    page.wait_for_url("**/bi/i/*")
    expect(page.locator("[data-meta]")).to_contain_text("owner")                      # the item has loaded: its buttons are wired
    page.click("[data-favourite]")
    expect(page.locator("[data-favourite]")).to_have_attribute("aria-pressed", "true")
    page.goto(CONSOLE_URL + "/bi")
    expect(page.locator('[data-sec="recent"] .bi-card').first).to_contain_text("Desk P&L")
    expect(page.locator('[data-sec="favourites"] .bi-card')).to_have_count(1)
    page.click('[data-sec="favourites"] .bi-fav')                                    # un-star from the card
    expect(page.locator('[data-sec="favourites"] .bi-card')).to_have_count(0)
    clean(page)


def test_the_catalogue_shows_only_what_the_audience_allows_and_searches_filters_sorts(stack, browser):
    cat = sign_in(browser, "cat")
    cat.goto(CONSOLE_URL + "/bi/catalogue")
    titles = lambda p: p.locator(".bi-card h3").all_inner_texts()                      # noqa: E731
    expect(cat.locator(".bi-card").first).to_contain_text("Desk P&L")                  # certified first
    got = titles(cat)
    assert "Desk P&L" in got and "Counterparty exposure" in got and "Liquidity daily" in got and "Trades (shipped)" in got
    assert "Trades dataset" not in got                                                 # a draft: its workspace only
    assert "Risk ladder" in got                                                        # cat is a member of the workspace, which sees every published item
    cat.fill("[data-search]", "exposure")
    expect(cat.locator(".bi-card")).to_have_count(1)
    expect(cat.locator("[data-count]")).to_have_text("1 item")
    assert "q=exposure" in cat.url
    cat.fill("[data-search]", "")
    cat.select_option('[name="kind"]', "dataset")
    expect(cat.locator(".bi-card")).to_have_count(1)
    cat.click("[data-clear]")
    cat.select_option('[name="endorsement"]', "certified")
    expect(cat.locator(".bi-card")).to_have_count(1)
    cat.click("[data-clear]")
    cat.select_option('[name="sort"]', "title")
    expect(cat.locator(".bi-card h3").first).to_have_text("Counterparty exposure")
    cat.fill("[data-search]", "zzzz-nothing")
    expect(cat.locator("[data-empty]")).to_be_visible()
    # sue is a steward outside the workspace: she sees only what its audience names her in
    sue = sign_in(browser, "sue")
    sue.goto(CONSOLE_URL + "/bi/catalogue")
    expect(sue.locator(".bi-card").first).to_be_visible()
    names = titles(sue)
    assert "Desk P&L" in names and "Risk ladder" in names and "Counterparty exposure" not in names
    clean(cat)


def test_a_workspace_is_created_and_shared_from_the_page(stack, browser):
    ann = sign_in(browser, "ann")
    ann.goto(CONSOLE_URL + "/bi/workspaces")
    expect(ann.locator("[data-new-workspace]")).to_be_visible()
    ann.fill('[data-new-workspace] [name="name"]', "Credit Desk")
    ann.fill('[data-new-workspace] [name="description"]', "Counterparty credit")
    ann.click('[data-new-workspace] button[type="submit"]')
    expect(ann.locator('[data-ws="credit-desk"]')).to_be_visible()
    ann.goto(CONSOLE_URL + "/bi/w/credit-desk")
    expect(ann.locator("[data-ws-name]")).to_have_text("Credit Desk")
    ann.fill('[data-add-member] [name="user"]', "cat")
    ann.select_option('[data-add-member] [name="role"]', "viewer")
    ann.click('[data-add-member] button[type="submit"]')
    expect(ann.locator('[data-members] tr[data-user="cat"]')).to_contain_text("viewer")
    ann.fill('[data-new-item] [name="title"]', "Exposure by name")
    ann.click('[data-new-item] button[type="submit"]')
    ann.wait_for_url("**/bi/i/*")
    expect(ann.locator("[data-title]")).to_have_text("Exposure by name")
    ann.click('[data-actions] button:text-is("Publish")')
    expect(ann.locator("[data-meta]")).to_contain_text("published v1")
    cat = sign_in(browser, "cat")
    cat.goto(CONSOLE_URL + "/bi/workspaces")
    expect(cat.locator('[data-ws="credit-desk"]')).to_contain_text("viewer")
    cat.goto(CONSOLE_URL + "/bi/w/credit-desk")
    expect(cat.locator("[data-new-item]")).to_be_hidden()
    expect(cat.locator("[data-add-member]")).to_be_hidden()
    assert cat.goto(CONSOLE_URL + "/bi/api/workspaces/credit-desk").status == 200          # members read the workspace through the page's JSON, the server decides
    clean(ann)


def test_review_before_publish_needs_a_second_person(stack, browser):
    ann = sign_in(browser, "ann")
    ann.goto(CONSOLE_URL + "/bi/w/credit-desk")
    ann.check("[data-review]")
    expect(ann.locator("[data-msg]")).to_contain_text("Review before publishing is on")
    ann.fill('[data-new-item] [name="title"]', "Limits report")
    ann.click('[data-new-item] button[type="submit"]')
    ann.wait_for_url("**/bi/i/*")
    expect(ann.locator('[data-actions] button:has-text("Publish")')).to_have_count(0)
    ann.fill(".bi-note", "please check the limits")
    ann.click("text=Propose for review")
    expect(ann.locator("[data-badges]")).to_contain_text("proposed")
    url = ann.url
    expect(ann.locator('[data-actions] button:has-text("Approve")')).to_have_count(0)
    bob_ws = sign_in(browser, "ann")                                                      # bob must be a member to approve
    bob_ws.goto(CONSOLE_URL + "/bi/w/credit-desk")
    bob_ws.fill('[data-add-member] [name="user"]', "bob")
    bob_ws.select_option('[data-add-member] [name="role"]', "member")
    bob_ws.click('[data-add-member] button[type="submit"]')
    expect(bob_ws.locator('[data-members] tr[data-user="bob"]')).to_be_visible()
    bob = sign_in(browser, "bob")
    bob.goto(url)
    bob.click('text=Approve and publish')
    expect(bob.locator("[data-meta]")).to_contain_text("published v1")
    expect(bob.locator("[data-msg]")).to_contain_text("Approved and published")
    clean(bob)


def test_the_keys_work_and_the_help_opens(stack, browser):
    page = sign_in(browser, "cat")
    page.keyboard.press("g")
    page.keyboard.press("c")
    page.wait_for_url("**/bi/catalogue")
    page.keyboard.press("/")
    expect(page.locator("[data-search]")).to_be_focused()
    page.keyboard.press("Escape")
    page.click("body", position={"x": 5, "y": 300})
    page.keyboard.press("g")
    page.keyboard.press("w")
    page.wait_for_url("**/bi/w/me")
    page.keyboard.press("g")
    page.keyboard.press("h")
    page.wait_for_url("**/bi")
    page.keyboard.press("?")
    expect(page.locator("#biKeys")).to_be_visible()
    expect(page.locator("#biKeys")).to_contain_text("g")
    page.keyboard.press("Escape")
    expect(page.locator("#biKeys")).not_to_be_visible()
    page.keyboard.press("/")                                                              # off the catalogue: goes there, search focused
    page.wait_for_url("**/bi/catalogue?focus=1")
    expect(page.locator("[data-search]")).to_be_focused()
    clean(page)


def test_a_phone_gets_a_scrolling_rail_finger_sized_controls_and_no_sideways_scroll(stack, browser):
    page = sign_in(browser, "ann", viewport={"width": 390, "height": 844}, has_touch=True, is_mobile=True)
    for path in ("/bi", "/bi/catalogue", "/bi/workspaces", "/bi/w/rates-desk", "/bi/datasets"):
        page.goto(CONSOLE_URL + path)
        page.wait_for_timeout(500)
        assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"), path
        small = page.evaluate("""() => [...document.querySelectorAll('.bi-shell a[href], .bi-shell button, .bi-shell input:not([type=hidden]), .bi-shell select')]
          .filter(e => { const r = e.getBoundingClientRect(), s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && !e.closest('[hidden]')
            && !(e.tagName === 'A' && e.closest('.bi-card') && !e.closest('h3')) && !(e.tagName === 'A' && e.closest('p, .bi-empty')); })
          .filter(e => Math.min(e.getBoundingClientRect().width, e.getBoundingClientRect().height) < 43.5 && !(e.type === 'checkbox'))
          .map(e => e.tagName + '.' + e.className + ' ' + Math.round(e.getBoundingClientRect().width) + 'x' + Math.round(e.getBoundingClientRect().height))""")
        assert small == [], (path, small)
    page.goto(CONSOLE_URL + "/bi")
    rail = page.locator(".bi-rail")
    assert rail.evaluate("e => getComputedStyle(e).flexDirection") == "row"
    expect(page.locator('.bi-rail a:has-text("Catalogue")')).to_be_visible()
    clean(page)


def test_both_themes_render_without_losing_the_cards(stack, browser):
    page = sign_in(browser, "cat")
    for theme in ("light", "terminal", "contrast"):
        page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme)
        page.goto(CONSOLE_URL + "/bi/catalogue")
        page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme)
        card = page.locator(".bi-card").first
        expect(card).to_be_visible()
        bg, fg = page.evaluate("[getComputedStyle(document.querySelector('.bi-card')).backgroundColor, getComputedStyle(document.querySelector('.bi-card h3 a')).color]")
        assert bg != fg, theme
    clean(page)


def test_the_admin_page_sets_powers_and_the_workspace_policy(stack, browser):
    page = sign_in(browser, "ann")
    assert page.goto(CONSOLE_URL + "/admin/bi").status == 403
    ctx = browser.new_context()
    adm = ctx.new_page()
    adm.goto(CONSOLE_URL + "/login?next=/admin/bi")
    adm.fill("#lu", "drishti-dev-admin")
    adm.fill("#lp", "drishti-dev-admin123")
    adm.click("button[type=submit]")
    adm.wait_for_url("**/admin/bi")
    expect(adm.locator('[data-roles] tr[data-role="bi-reader"]')).to_be_visible()
    adm.select_option('[data-policy] [name="workspaceCreation"]', "admins")
    adm.click('[data-policy] button[type="submit"]')
    expect(adm.locator("[data-msg]")).to_contain_text("created by admins")
    ann = sign_in(browser, "ann")
    ann.goto(CONSOLE_URL + "/bi/workspaces")
    expect(ann.locator("[data-new-workspace]")).to_be_hidden()
    adm.select_option('[data-policy] [name="workspaceCreation"]', "authors")
    adm.click('[data-policy] button[type="submit"]')
    adm.locator('[data-role="bi-reader"] [data-power="bi-author"]').check()
    adm.click('[data-role="bi-reader"] button')
    expect(adm.locator("[data-msg]")).to_contain_text("Saved the BI powers of bi-reader")
    adm.locator('[data-role="bi-reader"] [data-power="bi-author"]').uncheck()
    adm.click('[data-role="bi-reader"] button')
    expect(adm.locator("[data-msg]")).to_contain_text("Saved the BI powers of bi-reader")


def test_the_bi_folder_is_live_a_file_added_changed_and_deleted_needs_no_restart(stack, browser):
    import time

    folder = stack.tmp / "server" / "config" / "bi" / "reports"
    f = folder / "live-demo.report.yaml"
    page = sign_in(browser, "cat")
    page.goto(CONSOLE_URL + "/bi/catalogue")
    expect(page.locator(".bi-card").first).to_be_visible()
    f.write_text("title: Live demo\ndescription: dropped into the folder\n")
    page.fill("[data-search]", "live demo")
    deadline = time.time() + 20
    while time.time() < deadline and page.locator(".bi-card h3", has_text="Live demo").count() == 0:
        page.fill("[data-search]", "")
        page.fill("[data-search]", "live demo")
        page.wait_for_timeout(700)
    expect(page.locator(".bi-card h3").first).to_have_text("Live demo")
    page.locator(".bi-card h3 a").first.click()
    page.wait_for_url("**/bi/i/file:report:live-demo")
    expect(page.locator("[data-meta]")).to_contain_text("published v1")
    f.write_text("title: Live demo two\ndescription: changed on disk\n")
    deadline = time.time() + 20
    while time.time() < deadline and "Live demo two" not in page.locator("[data-title]").inner_text():
        page.reload()
        page.wait_for_timeout(700)
    expect(page.locator("[data-title]")).to_have_text("Live demo two")
    expect(page.locator("[data-meta]")).to_contain_text("published v2")
    f.write_text("title: [broken\n")                                                          # a bad edit: the last good version stays
    admin = browser.new_context().new_page()
    admin.goto(CONSOLE_URL + "/login?next=/admin/bi")
    admin.fill("#lu", "drishti-dev-admin")
    admin.fill("#lp", "drishti-dev-admin123")
    admin.click("button[type=submit]")
    deadline = time.time() + 20
    while time.time() < deadline and not admin.locator("[data-problems]:not([hidden])").count():
        admin.reload()
        admin.wait_for_timeout(700)
    expect(admin.locator("[data-problems]")).to_contain_text("live-demo.report.yaml")
    expect(admin.locator("[data-problems]")).to_contain_text("last good version is kept")
    page.reload()
    expect(page.locator("[data-title]")).to_have_text("Live demo two")
    f.unlink()                                                                               # deleted: open pages say it is gone
    deadline = time.time() + 20
    while time.time() < deadline and "gone" not in (page.locator("[data-msg]").inner_text() or ""):
        page.reload()
        page.wait_for_timeout(700)
    expect(page.locator("[data-msg]")).to_contain_text("gone")


def test_the_bi_keyword_types_ahead_opens_an_item_and_alone_opens_home(stack, browser):
    page = sign_in(browser, "cat")
    page.goto(CONSOLE_URL + "/t")
    page.fill("#cmdInput", "bi ")
    expect(page.locator("#cmdList")).to_contain_text("Catalogue")
    expect(page.locator("#cmdList")).to_contain_text("My workspace")
    page.fill("#cmdInput", "bi exp")
    expect(page.locator("#cmdList")).to_contain_text("Counterparty exposure")
    page.fill("#cmdInput", "BI Desk P&L")
    page.press("#cmdInput", "Enter")
    page.wait_for_url("**/bi/i/*")
    expect(page.locator("[data-title]")).to_have_text("Desk P&L")
    page.goto(CONSOLE_URL + "/t")
    page.fill("#cmdInput", "BI")
    page.press("#cmdInput", "Enter")
    page.wait_for_url("**/bi")
    clean(page)
