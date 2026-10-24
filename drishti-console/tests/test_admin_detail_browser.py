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

"""Admin -> Connectors and Packs in a real browser: from each list a name opens its page (by click, and by keyboard), the pages link to each other,
the editor opens from a connector's page, and a phone shows both pages with finger-sized targets. Runs Chromium through Playwright against a console
with a stand-in server; skipped when Playwright or its Chromium is missing."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from test_admin_connectors_browser import ConnectorBackend, conn_console  # noqa: E402,F401 - the stand-in with files in memory, and its console
from test_admin_detail_pages import DETAIL, HISTORY, SOURCES  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

PACK_ROW = {"name": "lakepack", "title": "Lake pack", "description": "Items from a lake", "version": "2.0.0", "loaded": True, "added": False, "enabled": True,
            "sample": False, "extends": [], "requiredBy": [], "kinds": ["item"], "connectors": ["item-store", "gone-conn"], "mnemonics": ["ITM"]}


class DetailBackend(ConnectorBackend):
    """The connector stand-in, plus what the pack's page reads."""

    async def admin(self, method, path, ident, body=None, timeout=None, headers=None, **params):
        if path == "/packs" and method == "GET":
            return [PACK_ROW]
        if path == "/packs/lakepack/detail":
            return {**DETAIL, "name": "lakepack", "kinds": [{**DETAIL["kinds"][0], "kind": "item"}]}
        if path == "/packs/lakepack/datasource":
            return SOURCES | {"connectors": [{"name": "item-store", "defined": True, "state": "RUNNING", "kinds": ["item"]},
                                              {"name": "gone-conn", "defined": False, "state": "NOT_CONFIGURED", "kinds": [], "createUrl": "/admin/connectors?new=gone-conn"}]}
        if path == "/packs/history":
            return {"history": HISTORY["history"], "kept": {}}
        if path == "/registry":
            return {"configured": False, "packs": []}
        if path.startswith("/loads/lakepack"):
            return {"expectations": [], "loads": []}
        if path == "/connectors/types/file/readme":
            return {"connector": "file", "source": "classpath", "meta": {"connector": "file", "category": "plugin", "summary": "Reads JSON Lines files.",
                    "capabilities": ["fetch", "search", "history"], "licence": "Part of Drishti", "windows": "Works"}, "markdown": README_MD}
        return await super().admin(method, path, ident, body, timeout, headers, **params)

    async def pack_overview(self, name, ident=None):
        return {"kinds": [{"kind": "item", "example": "I-1"}]}

    async def pack_readme(self, name, ident=None):
        return {"pack": "lakepack", "meta": {"pack": "lakepack", "summary": "Items from a lake.", "kinds": ["item"],
                                             "sources": [{"name": "A licensed feed", "licence": "Restricted", "internal-only": True}]},
                "markdown": README_MD}


# a README with what makes a phone scroll sideways if it is not contained: a wide table, a long code line, long identifiers
README_MD = ("# The pack\n\n## Data structures\n\n### `item`\n\n| Field | Type | Example | Description |\n|---|---|---|---|\n"
             + "".join(f"| `ITEM_FIELD_WITH_A_LONG_NAME_{i}` | string | `EXAMPLE-VALUE-{i}-ABCDEFGHIJKLMNOP` | A description that runs on for a while |\n" for i in range(6))
             + "\nNested: `LEGS[].CASH_FLOWS[].RESETS[]`\n\n```sql\nCREATE TABLE \"trading\".\"trade\" (\"business_date\" date, \"generation\" int, \"bucket\" smallint, \"id\" text, \"doc\" text)\n```\n")


@pytest.fixture(scope="module")
def detail_console():
    import socket
    import threading
    import time

    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = DetailBackend()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(10)


def test_list_to_detail_to_the_linked_pack_and_connector(browser, detail_console):
    page = browser.new_page()
    page.goto(detail_console + "/admin/connectors")
    page.get_by_role("link", name="item-store").first.click()                                  # a name opens its page
    page.wait_for_url("**/admin/connectors/item-store")
    assert "item-store" in page.locator("h1").inner_text().lower()
    assert page.locator("[data-state]").inner_text() == "running"
    assert "root: /data/items" in page.locator("[data-file-text]").inner_text()                # the file, read only
    page.locator("[data-act=edit]").click()                                                    # Edit opens the list's own editor
    dlg = page.locator("[data-edit-dialog]")
    dlg.locator("[data-f-name]").wait_for()
    assert dlg.locator("[data-title]").inner_text() == "Connector item-store"
    page.keyboard.press("Escape")
    page.locator("[data-used-by] a", has_text="lakepack").click()                              # the pack that uses it
    page.wait_for_url("**/admin/packs/lakepack")
    assert "lake pack" in page.locator("h1").inner_text().lower()
    assert page.locator("[data-kinds] [data-example]").get_attribute("href") == "/v/item/I-1"
    assert page.locator("[data-kinds] [data-form-link]").get_attribute("href") == "/form/lakepack?kind=item"
    missing = page.locator("[data-connectors] tr[data-connector=gone-conn]")
    assert "missing" in missing.inner_text() and missing.get_by_role("link", name="Create").get_attribute("href") == "/admin/connectors?new=gone-conn"
    page.locator("[data-connectors] tr[data-connector=item-store] a").first.click()            # and back to the connector
    page.wait_for_url("**/admin/connectors/item-store")
    page.close()


def test_a_name_in_a_list_is_reached_and_opened_from_the_keyboard(browser, detail_console):
    page = browser.new_page()
    page.goto(detail_console + "/admin/packs")
    link = page.locator("tr[data-pack=lakepack] a[data-detail-link]")
    link.focus()
    page.keyboard.press("Enter")
    page.wait_for_url("**/admin/packs/lakepack")
    assert page.locator("[data-status]").inner_text().startswith("on")
    page.close()


def test_a_phone_shows_both_pages_without_scrolling_sideways_and_with_finger_sized_links(browser, detail_console):
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    page = ctx.new_page()
    try:
        for url in ("/admin/connectors/item-store", "/admin/packs/lakepack"):
            page.goto(detail_console + url)
            assert page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"), url
            small = page.evaluate("""() => [...document.querySelectorAll('.det-sec a, .det-sec button, .adm-crumb a')].filter(e => !e.closest('.readme-article'))
                .filter(e => { const r = e.getBoundingClientRect(); return r.width && Math.min(r.width, r.height) < 43; })
                .map(e => e.textContent.trim().slice(0, 30))""")
            assert not small, (url, small)
    finally:
        ctx.close()


def test_the_pack_and_connector_pages_show_their_readmes_and_a_phone_reads_them_without_scrolling_sideways(browser, detail_console):
    """The README standard on screen: the pack's README (summary, internal-only source, the data-structure table) and the connector type's
    (capabilities, then its README folded), on a desktop and on a phone, where wide tables and code scroll inside their own box."""
    page = browser.new_page()
    page.goto(detail_console + "/admin/packs/lakepack")
    sec = page.locator("[data-pack-readme]")
    assert "Items from a lake." in sec.locator("[data-readme-summary]").inner_text()
    assert "internal only" in sec.locator("[data-readme-sources]").inner_text()
    assert sec.locator(".readme-article table th", has_text="Field").count() == 1
    assert "ITEM_FIELD_WITH_A_LONG_NAME_0" in sec.locator(".readme-article").inner_text()
    page.goto(detail_console + "/admin/connectors/item-store")
    t = page.locator("[data-type-readme=file]")
    assert "Reads JSON Lines files." in t.inner_text() and "history" in t.inner_text()
    assert not t.locator(".readme-article").is_visible()                                      # folded until asked
    t.locator("[data-readme-body] > summary").click()
    assert t.locator(".readme-article pre").is_visible()
    page.close()
    ctx = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True)
    phone = ctx.new_page()
    try:
        for url in ("/admin/packs/lakepack", "/admin/connectors/item-store"):
            phone.goto(detail_console + url)
            phone.evaluate("document.querySelectorAll('[data-readme-body]').forEach(d => d.open = true)")
            assert phone.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"), url
    finally:
        ctx.close()
