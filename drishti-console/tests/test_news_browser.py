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
missing. Skipped when Playwright, Chromium or the built server jar is missing, or a scratch port is in use. Never run with pytest -n (the ports are fixed)."""
import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")
from playwright.sync_api import expect, sync_playwright  # noqa: E402

import news_stack  # noqa: E402


def _stack(tmp_path_factory, on):
    for port in (news_stack.SERVER_PORT, news_stack.CONSOLE_PORT):
        if news_stack.listening(port):
            pytest.skip(f"port {port} is in use: stop whatever listens there")
    try:
        return news_stack.Stack(tmp_path_factory.mktemp("newsstack"), connectors_on=on).start()
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
        expect(page.locator("#p-note")).to_contain_text("connectors are off")
        expect(page.locator("#p-quote")).not_to_contain_text("204.30")
        expect(page.locator("#p-headlines tbody tr")).to_have_count(0)
        page.close()
    finally:
        stack.close()
