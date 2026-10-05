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

"""Ask about this page in a real browser against a real server and an in-process FAKE model endpoint (never a real model or service)
(docs/architecture/CONTEXT_HELP.md, Optional: Ask): the box is drawn only for a pack that opted in, a question reaches the fake endpoint
as a delimited prompt with labels only, the answer is shown as text, and a failing endpoint leaves the drawer's layers intact with the
DRS-4008 message.

Skipped when Playwright, Chromium, the built server jar or a JDK is missing (wb_live.py)."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from test_workbench_browser import browser, page, wait  # noqa: F401 - fixtures and helpers
from wb_live import BROWSER_WAIT_MS, _stack

VIEW = "/v/var/VAR-COMM"
OFF_VIEW = "/v/variant/VRNT-APOE-E4"                                                 # genomics: a pack that did not opt in


class FakeModel:
    """An OpenAI-compatible chat completions endpoint on loopback that records what it is sent."""

    def __init__(self):
        self.requests = []
        self.fail = False
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                outer.requests.append(body)
                if outer.fail:
                    self.send_response(500)
                    self.end_headers()
                    return
                out = json.dumps({"choices": [{"message": {"role": "assistant", "content": "A one-day 99% value at risk."}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1/chat/completions"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


@pytest.fixture(scope="module")
def model():
    m = FakeModel()
    yield m
    m.close()


@pytest.fixture(scope="module")
def live_console(tmp_path_factory, model):
    yield from _stack(tmp_path_factory, {"DRISHTI_PACKS": "market-risk,genomics", "DRISHTI_ASK_ENABLED": "true", "DRISHTI_ASK_ENDPOINT": model.url,
                                         "DRISHTI_ASK_MODEL": "fake", "DRISHTI_EXPLAIN_ASK_PACKS": "market-risk"})


def open_drawer(page, base, view):
    page.goto(base + view)
    page.locator(".vtitle .vid").wait_for()
    page.locator("[data-about-open]").first.click()
    wait(page, "!!document.querySelector('#aboutDrawer [data-layer=\"data\"]')")


def test_the_box_is_drawn_only_for_the_pack_that_opted_in(live_console, page):
    open_drawer(page, live_console, OFF_VIEW)
    assert page.locator("#aboutDrawer [data-about-ask]").count() == 0
    open_drawer(page, live_console, VIEW)
    assert page.locator("#aboutDrawer [data-about-ask]").count() == 1


def test_a_question_reaches_the_fake_endpoint_with_labels_only_and_the_answer_is_text(live_console, page, model):
    model.fail = False
    model.requests.clear()
    open_drawer(page, live_console, VIEW)
    page.locator("#aboutDrawer [data-ask-input]").fill("What is VaR 99%?")
    page.locator("#aboutDrawer [data-ask-send]").click()
    answer = page.locator("#aboutDrawer [data-ask-answer]")
    answer.wait_for(timeout=BROWSER_WAIT_MS)
    wait(page, "document.querySelector('#aboutDrawer [data-ask-answer]').textContent.includes('99% value at risk')")
    assert len(model.requests) == 1
    user = model.requests[0]["messages"][1]["content"]
    assert "What is VaR 99%?" in user and "<<<BEGIN UNTRUSTED glossary" in user and "VaR 99% 1D" in user
    assert "renderedSummary" not in user and "10.9" not in user and "58%" not in user          # labels only: no rendered value of the page is sent
    assert not page.errors


def test_a_failing_endpoint_says_so_and_the_layers_are_untouched(live_console, page, model):
    model.fail = True
    open_drawer(page, live_console, VIEW)
    layers_before = page.locator("#aboutDrawer [data-layer]").count()
    page.locator("#aboutDrawer [data-ask-input]").fill("Anything?")
    page.locator("#aboutDrawer [data-ask-send]").click()
    wait(page, "document.querySelector('#aboutDrawer [data-ask-answer]').textContent.includes('Ask is unavailable')")
    assert page.locator("#aboutDrawer [data-layer]").count() == layers_before >= 3
    model.fail = False
