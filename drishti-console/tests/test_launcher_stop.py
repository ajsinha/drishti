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

"""The console's side of the launcher's stop (core/launcher_stop.py): no key, no endpoint; the pid file's key from this machine stops the
console after the answer; a wrong key, another machine or another method never does; every other request passes untouched."""
from __future__ import annotations

import asyncio
import json
import os
import signal
import sys
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import launcher_stop  # noqa: E402


def _call(mw, path="/launcher/stop", method="POST", key=b"k3y", client="127.0.0.1"):
    sent = []

    async def receive():
        return {"type": "http.request", "body": b""}

    async def send(m):
        sent.append(m)
    headers = [(b"x-drishti-stop-key", key)] if key is not None else []
    asyncio.run(mw({"type": "http", "path": path, "method": method, "headers": headers, "client": (client, 5555)}, receive, send))
    status = next((m["status"] for m in sent if m["type"] == "http.response.start"), None)
    body = b"".join(m.get("body", b"") for m in sent if m["type"] == "http.response.body")
    return status, json.loads(body) if body else None


def _app(calls):
    async def app(scope, receive, send):
        calls.append(scope["path"])
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"{}"})
    return app


def test_without_a_key_the_path_is_an_ordinary_page():
    calls, stops = [], []
    mw = launcher_stop.LauncherStop(_app(calls), key="", stop=lambda: stops.append(1))
    assert _call(mw)[0] == 200 and calls == ["/launcher/stop"] and not stops


def test_a_wrong_key_another_machine_or_method_never_stops():
    calls, stops = [], []
    mw = launcher_stop.LauncherStop(_app(calls), key="k3y", stop=lambda: stops.append(1))
    assert _call(mw, key=b"wrong")[0] == 403
    assert _call(mw, key=None)[0] == 403
    assert _call(mw, client="10.0.0.7")[0] == 403
    assert _call(mw, method="GET")[0] == 405
    assert _call(mw, path="/healthz")[0] == 200 and calls == ["/healthz"]
    assert not stops


def test_the_key_from_this_machine_stops_the_console_after_the_answer():
    stops = []
    mw = launcher_stop.LauncherStop(_app([]), key="k3y", stop=lambda: stops.append(1))
    assert _call(mw) == (202, {"stopping": True}) and stops == [1]
    assert _call(mw, client="::1")[0] == 202 and stops == [1, 1]


def test_the_stop_signals_the_main_process_as_a_terminal_would():
    with mock.patch.dict(os.environ, {launcher_stop.MAIN_PID_ENV: str(os.getpid())}), mock.patch.object(signal, "raise_signal") as raised:
        launcher_stop.stop_console()
    raised.assert_called_once_with(signal.SIGTERM)                       # one process: uvicorn's own handler, a graceful stop
    with mock.patch.dict(os.environ, {launcher_stop.MAIN_PID_ENV: "4242"}), mock.patch.object(os, "kill") as kill:
        launcher_stop.stop_console()
        kill.assert_called_once_with(4242, signal.SIGTERM)               # several workers: the supervisor
        kill.reset_mock()
        with mock.patch.object(os, "name", "nt"), mock.patch.object(signal, "CTRL_BREAK_EVENT", 1, create=True):
            launcher_stop.stop_console()
        kill.assert_called_once_with(4242, 1)                            # Windows: CTRL_BREAK to the supervisor's own process group
