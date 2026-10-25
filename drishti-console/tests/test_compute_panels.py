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

"""Computed panels (REACTIVE_CONNECTORS.md 12.4) as the console draws them, without a server: a panel never blank (computing with its
bar, stale, failed or refused in words, the last result dimmed under it), its request id for compute.js, and the browser's channel
mapping compute:<request> to the server's stream of that request."""
from core.app import create_app
from core.channel import ChannelSession
from core.config import load_settings

from conftest import CONSOLE


def panel(state, has=True, **more):
    p = {"id": "var", "kind": "metric", "title": "VaR", "data": {"value": {"text": "41,200,000", "label": "VaR 99%"}}, "empty": False,
         "compute": {"state": state, "requestId": "req-1", "connector": "var-engine", "hasResult": has, **more}}
    env = create_app(load_settings(CONSOLE / "config")).state.templates.env
    return str(env.get_template("_macros/panels.html").module.panel(p, False))


def test_a_ready_panel_is_drawn_as_any_panel_and_carries_its_request():
    html = panel("ready")
    assert 'data-compute="ready"' in html and 'data-compute-req="req-1"' in html
    assert "data-compute-strip" not in html and "41,200,000" in html


def test_computing_shows_the_bar_and_the_percentage_over_the_last_result():
    html = panel("computing", progress={"done": 7, "of": 12})
    assert 'data-compute-bar data-w="58"' in html and "58%" in html and "Computing" in html
    assert 'class="cmp-last"' in html and "41,200,000" in html                     # the last result, dimmed under it


def test_computing_with_no_result_yet_is_never_blank():
    html = panel("computing", has=False)
    assert "Computing" in html and "var-engine" in html and "41,200,000" not in html and "No data available" not in html


def test_failed_and_refused_say_why_in_words():
    failed = panel("failed", has=False, message="the worker ran out of memory")
    assert "var-engine could not compute this: the worker ran out of memory" in failed and "request req-1" in failed and 'role="alert"' in failed
    refused = panel("refused", has=False, message="the scope has 6.2 million positions; the limit is 5 million")
    assert "Not computed: the scope has 6.2 million positions" in refused
    stale = panel("stale", message="var-engine is recomputing after 3 changes")
    assert "Stale · var-engine is recomputing after 3 changes" in stale and 'class="cmp-last"' in stale


def test_the_channel_follows_a_computed_panels_request_on_the_server():
    seen = {}

    class Backend:
        def sse(self, path, me, opened=None, **kw):
            seen["path"] = path
            return "upstream"

    s = ChannelSession.__new__(ChannelSession)
    s.backend, s.me = Backend(), None
    assert s._open("compute:req-1", []) == ("upstream", False)
    assert seen["path"] == "/compute/requests/req-1/stream"
    assert s._open("compute:", [])[0] is None
