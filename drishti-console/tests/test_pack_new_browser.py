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

"""Build -> New pack in a real browser (Chromium through Playwright): three schemas and a small JSON Lines folder are chosen, read and sampled in the
page, planned (kinds, keys, links, graph), edited, previewed (every Sutra drafted as a job and rendered), the bundle downloaded; keyboard use and
phone width are covered. The stand-in server answers the auto-designer with a canned draft, so this needs no Java; the bundle is checked with the
same offline checks `pack verify` runs. The deploy into a scratch server (a real one) is in test_pack_new_deploy_browser.py."""
import json
import pathlib
import re
import socket
import tarfile
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import CONSOLE, FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture

FIX = pathlib.Path(__file__).parent / "fixtures" / "schemas"
TOOLS = CONSOLE.parent / "tools"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class AuthorBackend(FakeBackend):
    async def _send(self, method, path, ident, **kw):          # the inbox count the top bar asks for
        return {"unread": 0}

    async def studio_settings(self, ident=None):
        return {"save": True, "author": True, "review": False, "approve": False}


@pytest.fixture(scope="module")
def pack_console():
    import uvicorn

    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = AuthorBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started, "the console did not start"
    yield f"http://127.0.0.1:{port}", app.state.backend
    server.should_exit = True
    thread.join(10)


@pytest.fixture
def data_folder(tmp_path):
    d = tmp_path / "jsonl"
    d.mkdir()
    with open(d / "trades.jsonl", "w") as f:
        for i in range(1, 31):
            f.write(json.dumps({"tradeId": f"TRD-{i:04d}", "counterpartyId": f"CPT-{i % 3 + 1:04d}", "bookId": "BK-0001", "status": ["LIVE", "PENDING"][i % 2],
                                "productType": ["IRS", "FXS", "FUT"][i % 3], "notional": 1000.0 * i, "currency": "USD", "tradeDate": f"2026-03-0{1 + i % 2}", "mtm": 5.5 * i}) + "\n")
        f.write("this line is not json\n")
    with open(d / "counterparties.jsonl", "w") as f:
        for i in range(1, 4):
            f.write(json.dumps({"id": f"CPT-{i:04d}", "name": f"C{i}", "rating": "AA"}) + "\n")
    return d


def schema_files():
    return [str(FIX / n) for n in ("trade.schema.json", "counterparty.schema.json", "book.schema.json")]


def to_step(page, n):
    page.click(f'[data-go="{n}"]')


def test_the_whole_flow_three_schemas_and_a_folder_to_a_downloaded_bundle(browser, pack_console, data_folder, tmp_path):
    url, backend = pack_console
    page = browser.new_page(accept_downloads=True)
    problems = []
    page.on("pageerror", lambda e: problems.append(str(e)))
    page.on("console", lambda m: problems.append(m.text) if m.type == "error" else None)
    page.goto(url + "/build/pack/new")
    assert page.locator("h1").inner_text() == "New pack"
    # 1. sources: three schema files, then a folder of JSON Lines
    page.set_input_files("[data-files]", schema_files())
    page.locator("[data-total]:has-text('3 schemas')").wait_for()
    page.set_input_files("[data-folder]", str(data_folder))
    page.locator("[data-total]:has-text('2 data files')").wait_for()
    total = page.locator("[data-total]").inner_text()
    assert "3 schemas, 2 data files" in total and "33 rows read" in total and "your files stay in this browser" in total
    srcs = page.locator("[data-sources]").inner_text()
    assert "trade.schema.json" in srcs and "jsonl/trades.jsonl" in srcs and "30 rows" in srcs and "1 bad line(s) skipped" in srcs
    # 2. kinds
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for()
    cards = page.locator(".pk-card")
    assert cards.count() == 3
    text = page.locator("[data-kinds]").inner_text()
    assert "3 kinds, 6 Sutras to create" in text
    assert page.locator("svg.pk-graph").count() == 1 and "trade to counterparty" in page.locator("svg.pk-graph title").text_content()
    trade = page.locator(".pk-card", has=page.locator("h3", has_text="Trade"))
    assert "trades.jsonl" in trade.inner_text() and "30 rows" in trade.inner_text()
    assert trade.locator("select[data-focus^=key-]").input_value() == "tradeId"
    assert trade.locator("select[data-focus^=match-]").input_value() == "productType"
    assert "4 Sutras" in trade.inner_text() or "4 Sutra" in trade.inner_text()
    # change the match column: the plan is redone
    trade.locator("select[data-focus^=match-]").select_option("")
    page.locator(".pk-card h3:has-text('Trade')").wait_for()
    page.wait_for_function("() => document.querySelector('[data-kinds]').innerText.includes('3 kinds, 3 Sutras to create')")
    trade.locator("select[data-focus^=match-]").select_option("productType")
    page.wait_for_function("() => document.querySelector('[data-kinds]').innerText.includes('3 kinds, 6 Sutras to create')")
    # 3. the pack
    page.click("[data-next]")
    page.fill("#pkp-name", "sg-browser")
    page.fill("#pkp-title", "Browser test pack")
    page.locator("#pkp-name").dispatch_event("change")
    page.locator("table select[aria-label='Source of trade']").wait_for()
    assert page.locator("table select[aria-label='Source of trade']").input_value() == "delta"       # real, dated documents: a lake
    assert page.locator("table select[aria-label='Source of book']").input_value() == "samples"
    # 4. preview
    page.click("[data-next]")
    page.locator(".pk-item").first.wait_for(timeout=20000)
    assert page.locator(".pk-item").count() == 6
    summary = page.locator(".pk-sum").inner_text()
    assert "6 Sutras drafted" in summary and "synthetic samples only" in summary
    page.locator(".pk-item[data-sutra='trade-irs']").click()
    page.locator(".pk-pv .view, .pk-pv *").first.wait_for()
    assert "trade-irs" in page.locator(".pk-detail-t").inner_text()
    page.click("#pkt-yaml")
    assert "sutra: trade-irs" in page.locator(".bs-yaml").inner_text()
    page.click("#pkt-about")
    assert "About this page: Trade" in page.locator(".pk-about").inner_text()
    page.click("#pkt-samples")
    assert "sample-1.json" in page.locator(".pk-samples").inner_text()
    page.click("#pkt-checks")
    assert page.locator(".pk-panel table").count() == 1
    # 5. output
    page.click("[data-next]")
    page.locator("[data-download]").wait_for()
    out = page.locator("[data-output]").inner_text()
    assert "sg-browser-1.0.0.tar.gz" in out and "SHA-256" in out and "Sutras" in out
    with page.expect_download() as dl:
        page.click("[data-download]")
    archive = tmp_path / dl.value.suggested_filename
    dl.value.save_as(archive)
    sha = re.search(r"SHA-256\s*([0-9a-f]{64})", out.replace("\n", " ")).group(1)
    import hashlib
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == sha
    with tarfile.open(archive) as t:
        names = t.getnames()
    assert "sg-browser/pack.yaml" in names and "sg-browser/tests/trade-irs/sample-1.json" in names
    assert "sg-browser/tests/book-default/sample-synthetic-1.json" in names
    assert not problems, problems
    page.close()


def test_a_big_jsonl_file_is_streamed_and_sampled_not_uploaded(browser, pack_console, tmp_path):
    url, backend = pack_console
    big = tmp_path / "events.jsonl"
    with open(big, "w") as f:
        for i in range(60000):
            f.write(json.dumps({"eventId": f"E{i}", "kind": ["a", "b", "c"][i % 3], "n": i}) + "\n")
    page = browser.new_page()
    sent = []
    page.on("request", lambda r: sent.append(len(r.post_data or "")) if r.method == "POST" else None)
    page.goto(url + "/build/pack/new")
    page.set_input_files("[data-files]", str(big))
    page.locator("[data-total]:has-text('60,000 rows read')").wait_for(timeout=30000)
    assert "200 documents sampled" in page.locator("[data-total]").inner_text()
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for()
    assert max(sent) < 400_000, "only the sample is sent, not the file"         # 200 small documents, not 60,000 rows
    assert "60,000 rows, 200 sampled" in page.locator("[data-kinds]").inner_text()
    page.close()


def test_reading_limits_stop_a_file_and_say_so(browser, pack_console, tmp_path):
    url, _ = pack_console
    f = tmp_path / "rows.jsonl"
    f.write_text("".join(json.dumps({"rowId": f"R{i}", "v": i}) + "\n" for i in range(5000)))
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    page.locator("summary:has-text('Reading limits')").click()
    page.fill("[data-max-rows]", "1000")
    page.set_input_files("[data-files]", str(f))
    page.locator("[data-sources]:has-text('stopped at the cap')").wait_for()
    assert "1,000 rows read" in page.locator("[data-total]").inner_text()
    page.close()


def test_a_schema_can_be_pasted_and_a_bad_file_is_named(browser, pack_console, tmp_path):
    url, _ = pack_console
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    page.locator("summary:has-text('Paste a schema')").click()
    page.fill("[data-paste]", json.dumps({"title": "Widget", "type": "object", "required": ["widgetId"], "properties": {"widgetId": {"type": "string"}, "weight": {"type": "number"}}}))
    page.click("[data-paste-add]")
    page.locator("[data-sources]:has-text('pasted.schema.json')").wait_for()
    bad = tmp_path / "broken.json"
    bad.write_text("{not json")
    page.set_input_files("[data-files]", str(bad))
    page.locator("[data-msg]:has-text('broken.json')").wait_for()
    assert "is not valid JSON" in page.locator("[data-msg]").inner_text()
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for()
    assert "widget" in page.locator("[data-kinds]").inner_text()
    page.close()


def test_an_ambiguous_key_must_be_chosen_before_going_on(browser, pack_console):
    url, _ = pack_console
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    page.locator("summary:has-text('Paste a schema')").click()
    page.fill("[data-paste]", json.dumps({"title": "Ticket", "type": "object", "required": ["aId", "bId"], "properties": {"aId": {"type": "string"}, "bId": {"type": "string"}}}))
    page.click("[data-paste-add]")
    page.click("[data-next]")
    page.locator(".pk-card.pk-needs").wait_for()
    assert "Ambiguous" in page.locator(".pk-card").inner_text()
    page.select_option("select[data-focus^=key-]", "bId")
    page.wait_for_function("() => document.querySelector('.pk-card').innerText.includes('chosen by you')")
    page.close()


def test_a_draft_is_saved_and_resumed(browser, pack_console, tmp_path):
    url, backend = pack_console
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    page.set_input_files("[data-files]", [str(FIX / "trade.schema.json"), str(FIX / "book.schema.json")])
    page.locator("[data-total]:has-text('2 schemas')").wait_for()
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for()
    page.click("[data-next]")
    page.fill("#pkp-name", "draft-pack")
    page.locator("#pkp-name").dispatch_event("change")
    page.click("[data-save]")
    page.locator("[data-save-msg]:has-text('Draft saved')").wait_for()
    page.close()
    page2 = browser.new_page()
    page2.goto(url + "/build/pack/new")
    page2.locator("[data-resume]:not([hidden])").wait_for()
    page2.select_option("#pkDraft", label="draft-pack")
    page2.click("[data-resume] button")
    page2.locator("[data-total]:has-text('2 schemas')").wait_for()
    page2.click("[data-next]")
    page2.locator(".pk-card").first.wait_for()
    page2.click("[data-next]")
    assert page2.input_value("#pkp-name") == "draft-pack"
    page2.click("[data-discard]")
    page2.locator("[data-save-msg]:has-text('discarded')").wait_for()
    page2.close()


def test_keyboard_only_through_the_steps(browser, pack_console):
    url, _ = pack_console
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    page.locator("summary:has-text('Paste a schema')").click()
    page.fill("[data-paste]", json.dumps({"title": "Thing", "type": "object", "required": ["thingId"], "properties": {"thingId": {"type": "string"}, "size": {"type": "number", "description": "How big."}}}))
    page.focus("[data-paste-add]")
    page.keyboard.press("Enter")
    page.locator("[data-sources]:has-text('pasted')").wait_for()
    page.focus("[data-next]")
    page.keyboard.press("Enter")
    page.locator(".pk-card").first.wait_for()
    assert page.evaluate("document.activeElement.closest('[data-step]') && document.activeElement.closest('[data-step]').dataset.step") == "2"   # focus moved into the step
    page.focus("[data-next]")
    page.keyboard.press("Enter")
    page.focus("[data-next]")
    page.keyboard.press("Enter")
    page.locator(".pk-item").first.wait_for(timeout=20000)
    page.focus(".pk-item")
    page.keyboard.press("ArrowDown")
    for tab in ("#pkt-preview", ):
        page.focus(tab)
        page.keyboard.press("ArrowRight")
    assert page.get_attribute("#pkt-yaml", "aria-selected") == "true"
    assert page.evaluate("document.activeElement.id") == "pkt-yaml"
    page.close()


def test_phone_width_never_scrolls_sideways_and_controls_are_finger_sized(browser, pack_console, data_folder):
    url, _ = pack_console
    ctx = browser.new_context(viewport={"width": 390, "height": 800}, has_touch=True, is_mobile=True)
    page = ctx.new_page()
    page.goto(url + "/build/pack/new")
    page.set_input_files("[data-files]", schema_files())
    page.set_input_files("[data-folder]", str(data_folder))
    page.locator("[data-total]:has-text('2 data files')").wait_for()
    audit = """() => { const out = []; const doc = document.documentElement;
      if (doc.scrollWidth > doc.clientWidth + 1) out.push('scrolls sideways ' + doc.scrollWidth + ' > ' + doc.clientWidth);
      for (const e of document.querySelectorAll('[data-pack-new] button, [data-pack-new] select, [data-pack-new] input:not([type=file]):not([type=checkbox]), [data-pack-new] summary')) {
        const r = e.getBoundingClientRect(); const s = getComputedStyle(e);
        if (r.width && s.display !== 'none' && s.visibility !== 'hidden' && (r.height < 43.5 || r.width < 43.5) && !e.closest('[hidden]')) out.push((e.className || e.tagName) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
      } return out; }"""
    assert page.evaluate(audit) == []
    page.click("[data-next]")
    page.locator(".pk-card").first.wait_for()
    assert page.evaluate(audit) == []
    page.click("[data-next]")
    page.locator("table select").first.wait_for()
    assert page.evaluate(audit) == []
    page.click("[data-next]")
    page.locator(".pk-item").first.wait_for(timeout=20000)
    assert page.evaluate(audit) == []
    page.click("[data-next]")
    page.locator("[data-download]").wait_for()
    assert page.evaluate(audit) == []
    ctx.close()


def test_themes_keep_the_page_readable(browser, pack_console):
    url, _ = pack_console
    page = browser.new_page()
    page.goto(url + "/build/pack/new")
    for theme in ("light", "dark"):
        page.evaluate("t => document.documentElement.setAttribute('data-theme', t)", theme)
        color = page.evaluate("getComputedStyle(document.querySelector('.pk-step[aria-current=step]')).color")
        bg = page.evaluate("getComputedStyle(document.querySelector('.pk-step[aria-current=step]')).backgroundColor")
        assert color != bg
    page.close()
