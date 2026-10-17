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

"""The console's server watch: state, transitions logged once, GET /healthz/backend, the script on every page."""
import asyncio
import logging

from core.backend_watch import BackendWatch
from core.servers import Server


class _Catalogue:
    def __init__(self, *servers):
        self._s = {s.id: s for s in servers}

    def all(self):
        return list(self._s.values())

    def get(self, sid):
        return self._s.get(sid)


def _watch(results):
    """A watch whose probe answers from a list: an Exception is raised, a dict is the server's about."""
    w = BackendWatch(_Catalogue(Server("default", "Main", "http://x:1")), seconds=5)

    async def probe(url):
        r = results[0]
        if isinstance(r, Exception):
            raise r
        return r
    w.probe = probe
    return w


def test_transitions_are_logged_once(caplog):
    results = [OSError("refused")]
    w = _watch(results)
    s = w._servers.get("default")
    with caplog.at_level(logging.WARNING, logger="drishti.console.backend"):
        down = asyncio.run(w.check(s))
        asyncio.run(w.check(s))
        assert down["reachable"] is False and "OSError" in down["lastError"]
        results[0] = {"version": "2.2.0"}
        up = asyncio.run(w.check(s))
        asyncio.run(w.check(s))
    assert up["reachable"] is True and up["version"] == "2.2.0" and up["lastError"] == ""
    texts = [r.getMessage() for r in caplog.records]
    assert len([t for t in texts if "unreachable" in t]) == 1
    assert len([t for t in texts if "reachable again after" in t]) == 1
    assert w.snapshot()[0]["reachable"] is True


def test_healthz_backend_reports_the_watch(client, monkeypatch):
    w = _watch([{"version": "9.9.9"}])
    monkeypatch.setattr(client.app.state, "backend_watch", w)
    body = client.get("/healthz/backend").json()
    assert body["reachable"] is True and body["version"] == "9.9.9" and body["checkSeconds"] == 5
    assert set(body) == {"reachable", "since", "lastError", "version", "checked", "server", "checkSeconds"}
    w._state.clear()
    w.probe = _watch([OSError("refused")]).probe
    assert client.get("/healthz/backend").json()["reachable"] is False


def test_every_page_carries_the_watch_script(client):
    html = client.get("/").text
    assert "/static/js/server-watch.js" in html and "/static/css/server-watch.css" in html
    assert client.get("/static/js/server-watch.js").status_code == 200
    assert "data-watch-probe" in client.get("/login").text


def test_check_seconds_setting_default(client):
    assert client.app.state.backend_watch.seconds == 5 and client.app.state.backend_watch.enabled
