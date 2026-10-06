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

"""The browser tests' one-reload rule for net::ERR_NETWORK_CHANGED, and the wait() that applies it."""
import pytest

from wb_live import should_reload
import test_workbench_browser as wb


@pytest.mark.parametrize("network,reloaded,expected", [
    (["request failed: http://x/a.js (net::ERR_NETWORK_CHANGED)"], False, True),
    (["HTTP 500: http://x/a.js", "request failed: http://x/b (net::ERR_NETWORK_CHANGED)"], False, True),
    (["request failed: http://x/a.js (net::ERR_NETWORK_CHANGED)"], True, False),     # once only
    (["request failed: http://x/a.js (net::ERR_CONNECTION_REFUSED)"], False, False),  # another cause fails as before
    (["HTTP 404: http://x/a.js"], False, False),
    ([], False, False),
    (None, False, False),
])
def test_reload_only_once_and_only_for_a_changed_network(network, reloaded, expected):
    assert should_reload(network, reloaded) is expected


class FakePage:
    def __init__(self, network, ready_after_reload=True):
        self.network, self.reloads, self.ready, self.errors = network, 0, False, []
        self.ready_after_reload = ready_after_reload

    def evaluate(self, js):
        return self.ready if js == "ok" else {}

    def wait_for_timeout(self, ms):
        pass

    def reload(self):
        self.reloads += 1
        self.ready = self.ready_after_reload


def test_wait_reloads_once_after_a_network_change_and_then_succeeds():
    page = FakePage(["request failed: u (net::ERR_NETWORK_CHANGED)"])
    wb.wait(page, "ok", seconds=0.2)
    assert page.reloads == 1


def test_wait_does_not_reload_for_any_other_cause():
    page = FakePage(["HTTP 500: u"])
    with pytest.raises(AssertionError):
        wb.wait(page, "ok", seconds=0.2)
    assert page.reloads == 0


def test_wait_reloads_only_once_when_the_page_is_still_wrong():
    page = FakePage(["request failed: u (net::ERR_NETWORK_CHANGED)"], ready_after_reload=False)
    with pytest.raises(AssertionError):
        wb.wait(page, "ok", seconds=0.2)
    assert page.reloads == 1
