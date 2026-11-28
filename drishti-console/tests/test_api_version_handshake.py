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

"""The console's API handshake: the range it supports (core/api_compat.py), the start command's refusal, the watch's record."""
import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import run_drishti_web
from core import api_compat
from core.backend_watch import BackendWatch
from core.servers import Server


def test_the_range_is_inclusive_and_one_line():
    assert api_compat.problem("1.0") is None and api_compat.problem(api_compat.SUPPORTED_MAX) is None and api_compat.problem("1.7") is None
    for api, word in (("2.0", "newer"), ("0.9", "older"), (None, "no API version"), ("", "no API version"), ("one", "not major.minor")):
        line = api_compat.problem(api, "http://s:1")
        assert line and "http://s:1" in line and word in line and "\n" not in line, (api, line)


def _server(routes):
    class H(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            hit = routes.get(self.path)
            self.send_response(hit[0] if hit else 404)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(hit[1] if hit else {}).encode())

        def log_message(self, *a):  # noqa: D401
            pass
    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.server_address[1]}"


def test_preflight_accepts_a_compatible_server_and_refuses_the_others():
    for routes, expect in (({"/api/v1/version": (200, {"api": "1.0"})}, None),
                           ({"/api/v1/version": (200, {"api": "3.1"})}, "newer"),
                           ({"/public/about": (200, {"version": "2.9"})}, "no API version"),    # a server before the versioned API
                           ({"/api/v1/version": (200, {"api": "1.2"}), "/public/about": (200, {})}, None)):
        srv, url = _server(routes)
        try:
            got = api_compat.preflight(url)
        finally:
            srv.shutdown()
        assert (got is None) if expect is None else (expect in got), (routes, got)


def test_a_server_that_is_away_is_not_a_refusal():
    srv, url = _server({})
    srv.shutdown()
    srv.server_close()
    assert api_compat.preflight(url, timeout=0.5) is None


class _Settings(dict):
    def get(self, key, default=None):
        return super().get(key, default)


def test_the_start_command_names_the_refusal_and_the_switch_turns_it_off():
    srv, url = _server({"/api/v1/version": (200, {"api": "2.0"})})
    try:
        line = run_drishti_web.api_refusal(_Settings({"backend.url": url}))
        assert line and "2.0" in line
        assert run_drishti_web.api_refusal(_Settings({"backend.url": url, "backend.require_compatible_api": False})) is None
        assert run_drishti_web.api_refusal(_Settings({"backend.urls": "http://127.0.0.1:1," + url})) == line      # every listed server is asked
    finally:
        srv.shutdown()


class _Catalogue:
    def __init__(self, s):
        self._s = s

    def all(self):
        return [self._s]

    def get(self, sid):
        return self._s


@pytest.mark.parametrize("about,problem", [({"version": "2.9", "api": "1.0"}, ""), ({"version": "2.9", "api": "2.0"}, "newer"), ({"version": "2.9"}, "no API version")])
def test_the_watch_records_the_api_and_its_problem(about, problem):
    s = Server("default", "Main", "http://x:1")
    w = BackendWatch(_Catalogue(s), seconds=5)

    async def probe(url):
        return about
    w.probe = probe
    st = asyncio.run(w.check(s))
    assert st["reachable"] is True and st["api"] == about.get("api", "")
    assert (problem in st["apiProblem"]) if problem else st["apiProblem"] == ""
