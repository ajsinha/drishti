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

"""The news-and-markets sample packs in Chromium, over the real server and console (tests/news_stack.py: ports 18993 and 17993). The two
on-demand connectors (news, yfinance) are switched on and pointed at local fakes that serve canned Google News RSS and Yahoo chart JSON: nothing reaches
Google or Yahoo. A company page shows its quote, its price chart and its headlines with links that open the article in a new tab; a topic page shows the
headlines, the count by source and the steps that chose them. With the connectors off (their shipped state) the same pages still open and say what is
missing. In offline mode (DRISHTI_OFFLINE=true, over a demo lake built from the committed snapshot) the live connectors never start, the fakes get no
request at all, and the same pages show the captured quotes and headlines with a snapshot note. Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import news_stack  # noqa: E402


def _stack(tmp_path_factory, on, offline=False):
    for port in (news_stack.SERVER_PORT, news_stack.CONSOLE_PORT):
        if news_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    if offline:
        pytest.importorskip("deltalake", reason="lake demo needs deltalake and pyarrow")
    try:
        return news_stack.Stack(tmp_path_factory.mktemp("newsstack"), connectors_on=on, offline=offline).start()
    except FileNotFoundError as e:
        pytest.skip(str(e))


@pytest.fixture(scope="module")
def browser():
    with sync_playwright() as p:
        try:
            b = p.chromium.launch()
        except Exception as e:  # noqa: BLE001
            pytest.skip(f"needs Playwright's Chromium: {str(e)[:100]}")
        yield b
        b.close()


def test_a_company_page_shows_quote_chart_and_headlines_with_links(tmp_path_factory, browser):
    stack = _stack(tmp_path_factory, True)
    try:
        import json
        import urllib.parse
        import urllib.request

        problems = json.load(urllib.request.urlopen(news_stack.SERVER + "/api/v1/sutras/problems", timeout=10))
        assert problems == {}, problems                                                # every Sutra of both packs is valid
        found = urllib.request.urlopen(news_stack.SERVER + "/api/v1/command/suggest?q=" + urllib.parse.quote("CO google"), timeout=10).read().decode()
        assert "CO-GOOGL" in found, found                                              # an alias finds the company
        page = browser.new_page(viewport={"width": 1400, "height": 1000})
        page.goto(news_stack.CONSOLE_URL + "/v/listed-company/CO-AAPL")
        expect(page.locator("#p-quote")).to_contain_text("204.30")
        expect(page.locator("#p-quote")).to_contain_text("USD")
        expect(page.locator("#p-quote")).to_contain_text("201.90")                              # the day range from the fake
        expect(page.locator("#p-history")).to_be_visible()
        rows = page.locator("#p-headlines tbody tr")
        expect(rows).to_have_count(3)                                                 # five in the feed: one duplicate, one that does not name Apple
        first = rows.first
        expect(first).to_contain_text("Apple announces record quarter")
        expect(first).to_contain_text("Reuters")
        link = first.locator("a.ext")
        expect(link).to_have_attribute("href", "https://news.example/a1")
        expect(link).to_have_attribute("target", "_blank")
        expect(link).to_have_attribute("rel", "noopener noreferrer nofollow")
        expect(page.locator("#p-bySource")).to_contain_text("Bloomberg")
        page.screenshot(path=str(stack.tmp / "company.png"))

        page.goto(news_stack.CONSOLE_URL + "/v/news-topic/TOPIC-FED")
        expect(page.locator("#p-headlines tbody tr")).to_have_count(3)
        expect(page.locator("#p-everything tbody tr")).to_have_count(4)               # no relevance filter: the unrelated headline is back
        expect(page.locator("#p-pipeline")).to_contain_text("relevance")
        expect(page.locator("#p-pipeline")).to_contain_text("duplicates")
        expect(page.locator("#p-bySource")).to_contain_text("Reuters")
        page.screenshot(path=str(stack.tmp / "topic.png"))

        page.goto(news_stack.CONSOLE_URL + "/v/news-topic/TOPIC-TOYOTA")              # the Japanese edition: the id carries @ja-JP
        expect(page.locator("#p-headlines tbody tr").first).to_contain_text("Toyota")
        page.close()
    finally:
        stack.close()


def test_with_the_connectors_off_the_pages_open_and_say_what_is_missing(tmp_path_factory, browser):
    stack = _stack(tmp_path_factory, False)
    try:
        page = browser.new_page(viewport={"width": 1400, "height": 1000})
        page.goto(news_stack.CONSOLE_URL + "/v/listed-company/CO-AAPL")
        expect(page.locator(".strip")).to_contain_text("AAPL")
        expect(page.locator("#p-note")).to_contain_text("connectors off")
        expect(page.locator("#p-quote")).not_to_contain_text("204.30")
        expect(page.locator("#p-headlines tbody tr")).to_have_count(0)
        page.close()
    finally:
        stack.close()


def test_offline_mode_serves_the_captured_snapshot_and_never_calls_out(tmp_path_factory, browser):
    import json
    import urllib.request

    snap = news_stack.ROOT / "data" / "testdata" / "news-and-markets" / "quote"
    if not list(snap.glob("snapshot-*.jsonl.gz")):
        pytest.skip("no captured market snapshot in data/testdata (tools/testdata/capture_market.py)")
    stack = _stack(tmp_path_factory, True, offline=True)                         # the connectors switched on in their files: offline wins
    try:
        listed = json.load(urllib.request.urlopen(news_stack.SERVER + "/api/v1/admin/connectors", timeout=10))["connectors"]
        state = {c["name"]: (c["state"], c.get("label")) for c in listed}
        assert state["news"] == ("OFFLINE", "off (offline mode)"), state
        assert state["yfinance"] == ("OFFLINE", "off (offline mode)"), state
        assert state["news-store"][0] == "RUNNING", state
        assert not any(c.get("problems") for c in listed if c["name"] in ("news", "yfinance")), listed
        page = browser.new_page(viewport={"width": 1400, "height": 1000})
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.add_init_script("window.__csp = []; document.addEventListener('securitypolicyviolation', e => window.__csp.push(e.violatedDirective + ' ' + e.blockedURI));")
        page.goto(news_stack.CONSOLE_URL + "/v/listed-company/CO-AAPL")
        expect(page.locator("#p-quote")).to_contain_text("USD")
        expect(page.locator("#p-quote")).to_contain_text("snapshot captured")      # As of ... (snapshot captured <date>)
        expect(page.locator("#p-history")).to_be_visible()
        expect(page.locator("#p-history canvas, #p-history svg").first).to_be_visible()
        # the whole year of closes is drawn, and it ends at the quote's own price (wave C housekeeping: one snapshot, two panels that agree)
        drawn = page.evaluate("""() => { const c = [...document.querySelectorAll('#p-history div')].map(d => window.echarts && echarts.getInstanceByDom(d)).find(Boolean);
            if (!c) return null; const o = c.getOption(); return { x: o.xAxis[0].data.length, y: o.series[0].data.map(v => v && typeof v === 'object' ? v.value : v) }; }""")
        assert drawn and drawn["x"] >= 200 and len(drawn["y"]) == drawn["x"] and all(v is not None for v in drawn["y"]), drawn and drawn["x"]
        price = float(page.locator("#p-quote").inner_text().split("Price")[1].split()[0].replace(",", ""))
        last = drawn["y"][-1][-1] if isinstance(drawn["y"][-1], list) else drawn["y"][-1]
        assert abs(float(last) - price) < 0.01, (last, price)
        assert page.locator("#p-headlines tbody tr").count() > 0
        expect(page.locator("#p-headlines a.ext").first).to_have_attribute("target", "_blank")
        page.screenshot(path=str(stack.tmp / "company-offline.png"))

        page.goto(news_stack.CONSOLE_URL + "/v/quote/QUOTE-MSFT")
        expect(page.locator("#p-snapshot")).to_contain_text("Snapshot captured")
        expect(page.locator("#p-snapshot")).to_contain_text("Yahoo Finance")
        assert page.locator("#p-bars tbody tr").count() >= 200                    # about a year of daily bars

        page.goto(news_stack.CONSOLE_URL + "/v/news-topic/TOPIC-FED")
        assert page.locator("#p-headlines tbody tr").count() > 0
        expect(page.locator("#p-bySource")).to_be_visible()
        expect(page.locator("#p-note")).to_contain_text("captured snapshot")            # the note says what is shown offline, not that the tables are empty
        page.screenshot(path=str(stack.tmp / "topic-offline.png"))

        page.goto(news_stack.CONSOLE_URL + "/v/news/NEWS-Federal_Reserve")
        expect(page.locator("#p-snapshot")).to_contain_text("Google News")
        assert page.evaluate("window.__csp") == [] and errors == [], (page.evaluate("window.__csp"), errors)
        page.close()
        assert news_stack._Fake.requests == [], news_stack._Fake.requests          # nothing tried the network
    finally:
        stack.close()
