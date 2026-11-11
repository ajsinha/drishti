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

"""The one Approvals queue in a browser, every button of it (HOW_IT_FITS.md section S2): the boxes (Waiting for me, Mine sent, Done,
All), the areas, the kind, Refresh, an item opened with its diff, effect and rules, Approve, Reject (refused without a reason, then with
one), Withdraw, Ask a question, the parameters a kind needs, Open where it lives, and the top bar's count. A console with a stand-in
server: the server's rules are tested in ApprovalApiTest and ApprovalQueueTest."""
from __future__ import annotations

import socket
import threading
import time

import pytest

pytest.importorskip("playwright.sync_api", reason="needs Playwright (pip install playwright)")

from conftest import FakeBackend  # noqa: E402
from test_live_tabs_browser import browser  # noqa: E402,F401 - the shared browser fixture


def _items():
    return {
        ("sutra", "P-000007"): {"kind": "sutra", "id": "P-000007", "group": "screens", "title": "irs-vanilla v3 (change)", "maker": "ana",
                                "at": "2026-11-07T09:00:00Z", "status": "pending", "reason": "clearer title", "due": None, "approvers": ["*"],
                                "link": "/build/design"},
        ("var-report", "annual-report-var:FY2026:v1"): {"kind": "var-report", "id": "annual-report-var:FY2026:v1", "group": "risk",
                                                        "title": "Annual VaR FY2026 v1", "maker": "rao", "at": "2026-11-06T09:00:00Z",
                                                        "status": "pending", "reason": "", "due": "2026-11-09", "approvers": ["*"],
                                                        "link": "/var/reports/annual-report-var?period=FY2026", "stage": "with checker"},
        ("function", "asha:wret@1"): {"kind": "function", "id": "asha:wret@1", "group": "data", "title": "fn:asha/wret@1 (formula)", "maker": "asha",
                                      "at": "2026-11-05T09:00:00Z", "status": "pending", "reason": "", "due": None, "approvers": ["*"], "link": "/pivot"},
        ("bi", "it-0042"): {"kind": "bi", "id": "it-0042", "group": "data", "title": "dataset 'Rates trades' in Desk", "maker": "ann",
                            "at": "2026-11-04T09:00:00Z", "status": "pending", "reason": "", "due": None, "approvers": ["*"], "link": "/bi/items/it-0042"},
        ("change", "chg-1"): {"kind": "change", "id": "chg-1", "group": "data", "title": "trade MX-1: 1 field(s)", "maker": "@me",
                              "at": "2026-11-07T08:00:00Z", "status": "pending", "reason": "typo", "due": None, "approvers": ["kim"], "link": "/v/trade/MX-1"},
    }


class ApprovalsBackend(FakeBackend):
    """The stand-in server with the queue's items; each decision is remembered with what it was sent."""

    def __init__(self):
        super().__init__()
        self.approvals_kept = _items()
        self.approval_questions = {}
        self.decided = []

    async def approvals_act(self, kind, id_, action, body, ident):
        self.decided.append((kind, id_, action, body))
        return await super().approvals_act(kind, id_, action, body, ident)


@pytest.fixture(scope="module")
def approvals_console():
    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = ApprovalsBackend()
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
    yield f"http://127.0.0.1:{port}", app.state.backend
    server.should_exit = True
    thread.join(10)


def _fresh(backend):
    backend.approvals_kept = _items()
    backend.approval_questions = {}
    backend.decided.clear()


def _ids(page):
    return page.eval_on_selector_all("[data-ap-row]", "els => els.map(e => e.getAttribute('data-ap-row'))")


def _loaded(page):
    page.wait_for_function("() => !/loading/.test(document.querySelector('[data-ap-meta]').textContent)")


def test_the_boxes_the_areas_the_kind_and_refresh_filter_the_list(browser, approvals_console):
    url, backend = approvals_console
    _fresh(backend)
    page = browser.new_page()
    page.goto(url + "/approvals")
    _loaded(page)
    assert "4 waiting for you, 1 due soon" in page.locator("[data-ap-meta]").inner_text()
    assert page.locator("[data-ap-soon]").is_visible()                                              # due soon pinned on top
    assert page.locator("[data-ap-soon-items] [data-ap-row]").first.get_attribute("data-ap-row") == "var-report/annual-report-var:FY2026:v1"
    assert "change/chg-1" not in _ids(page)                                                        # never your own in the inbox
    page.locator("[data-ap-box=sent]").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 1")
    assert _ids(page) == ["change/chg-1"] and "box=sent" in page.url
    page.locator("[data-ap-box=all]").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 5")
    page.locator("[data-ap-group=risk]").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 1")
    assert page.locator("[data-ap-group=risk]").get_attribute("aria-pressed") == "true"
    page.locator("[data-ap-group='']").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 5")   # all areas again, before the kind
    page.locator("[data-ap-kind]").select_option("sutra")
    page.wait_for_function("() => [...document.querySelectorAll('[data-ap-row]')].map(e => e.dataset.apRow).join() === 'sutra/P-000007'")
    assert _ids(page) == ["sutra/P-000007"] and "kind=sutra" in page.url
    page.locator("[data-ap-kind]").select_option("")
    page.locator("[data-ap-box=done]").click()
    page.wait_for_selector("[data-ap-none]:not([hidden])")                                         # nothing decided yet
    backend.approvals_kept[("bi", "it-0042")]["status"] = "approved"                               # decided elsewhere meanwhile
    page.locator("[data-ap-refresh]").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 1")
    assert _ids(page) == ["bi/it-0042"]
    page.locator("[data-ap-box=inbox]").click()
    page.wait_for_function("() => document.querySelectorAll('[data-ap-row]').length === 3")
    page.close()


def test_an_item_opens_with_its_diff_effect_rules_and_is_approved(browser, approvals_console):
    url, backend = approvals_console
    _fresh(backend)
    page = browser.new_page()
    page.goto(url + "/approvals")
    _loaded(page)
    page.locator("[data-ap-open='var-report/annual-report-var:FY2026:v1']").click()
    page.wait_for_selector("[data-ap-item]:not([hidden])")
    assert "Annual VaR FY2026 v1" in page.locator("[data-ap-item-title]").inner_text()
    assert "due 2026-11-09" in page.locator("[data-ap-item-status]").inner_text()
    assert "- title: Before" in page.locator("[data-ap-diff]").inner_text() and "+ title: After" in page.locator("[data-ap-diff]").inner_text()
    assert page.locator("[data-ap-effect] li").count() == 1 and page.locator("[data-ap-rules] li").count() == 1
    assert page.locator("[data-ap-item-link]").get_attribute("href") == "/var/reports/annual-report-var?period=FY2026"   # open where it lives
    assert "/approvals/var-report/annual-report-var:FY2026:v1" in page.url
    assert page.locator("[data-ap-act=withdraw]").is_hidden()
    page.fill("[data-ap-comment]", "checked")
    page.locator("[data-ap-act=approve]").click()
    page.wait_for_function("() => document.querySelector('[data-ap-said]').textContent === 'Approved.'")
    assert backend.decided[-1] == ("var-report", "annual-report-var:FY2026:v1", "approve", {"comment": "checked", "params": {}})
    assert "approved" in page.locator("[data-ap-item-status]").inner_text()
    assert page.locator("[data-ap-act=approve]").is_hidden() and page.locator("[data-ap-act=reject]").is_hidden()
    page.close()


def test_reject_needs_a_reason_and_withdraw_is_the_makers(browser, approvals_console):
    url, backend = approvals_console
    _fresh(backend)
    page = browser.new_page()
    page.goto(url + "/approvals/sutra/P-000007")
    page.wait_for_selector("[data-ap-item]:not([hidden])")
    page.locator("[data-ap-act=reject]").click()
    page.wait_for_function("() => /Say why/.test(document.querySelector('[data-ap-said]').textContent)")
    assert backend.decided == []                                                                   # nothing sent without a reason
    page.fill("[data-ap-comment]", "not this title")
    page.locator("[data-ap-act=reject]").click()
    page.wait_for_function("() => document.querySelector('[data-ap-said]').textContent === 'Rejected.'")
    assert backend.decided[-1] == ("sutra", "P-000007", "reject", {"comment": "not this title", "params": {}})
    page.goto(url + "/approvals/change/chg-1")
    page.wait_for_selector("[data-ap-item]:not([hidden])")
    assert page.locator("[data-ap-act=approve]").is_hidden() and page.locator("[data-ap-act=withdraw]").is_visible()
    page.locator("[data-ap-act=withdraw]").click()
    page.wait_for_function("() => document.querySelector('[data-ap-said]').textContent === 'Withdrawn.'")
    assert backend.decided[-1][:3] == ("change", "chg-1", "withdraw")
    page.close()


def test_a_kind_asks_for_what_it_needs_and_a_question_is_a_thread(browser, approvals_console):
    url, backend = approvals_console
    _fresh(backend)
    page = browser.new_page()
    page.goto(url + "/approvals/function/asha:wret@1")
    page.wait_for_selector("[data-ap-item]:not([hidden])")
    page.fill("[data-ap-param=scope]", "group:rates")
    page.fill("[data-ap-comment]", "reviewed")
    page.locator("[data-ap-act=approve]").click()
    page.wait_for_function("() => document.querySelector('[data-ap-said]').textContent === 'Approved.'")
    assert backend.decided[-1] == ("function", "asha:wret@1", "approve", {"comment": "reviewed", "params": {"scope": "group:rates"}})
    page.goto(url + "/approvals/bi/it-0042")
    page.wait_for_selector("[data-ap-item]:not([hidden])")
    page.check("[data-ap-flag=applyRenames]")
    page.locator("[data-ap-act=approve]").click()
    page.wait_for_function("() => document.querySelector('[data-ap-said]').textContent === 'Approved.'")
    assert backend.decided[-1][3]["params"] == {"applyRenames": True}
    page.fill("[data-ap-question]", "Why the new columns?")
    page.locator("[data-ap-ask-btn]").click()
    page.wait_for_function("() => /Why the new columns/.test(document.querySelector('[data-ap-thread]').textContent)")
    assert backend.decided[-1] == ("bi", "it-0042", "questions", {"text": "Why the new columns?"})
    assert page.locator("[data-ap-question]").input_value() == ""
    page.close()


def test_the_top_bar_counts_what_waits_and_opens_the_queue(browser, approvals_console):
    url, backend = approvals_console
    _fresh(backend)
    page = browser.new_page()
    page.goto(url + "/t")
    page.wait_for_selector("[data-approvals-top]:not([hidden])")
    assert page.locator("[data-approvals-top]").inner_text() == "4"
    assert "1 due soon" in page.locator("[data-approvals-tool]").get_attribute("aria-label")
    page.locator("[data-approvals-tool]").click()
    page.wait_for_url("**/approvals")
    page.close()
