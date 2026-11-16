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

"""The launcher's stop for the console (docs/DEPLOYMENT_AND_QA_GUIDE.md section 7.1): ``python tools/drishti.py stop console`` asks the
console it started to stop itself, the same graceful stop on every operating system (on Windows a background console has no window and
no console a plain ``taskkill`` could reach).

``POST /launcher/stop`` with the header ``X-Drishti-Stop-Key``: answered only when the launcher started this console with a key
(``DRISHTI_STOP_KEY``, kept in its pid file under the data folder, owner-only), only straight from this machine: the socket's own peer
is loopback (uvicorn's proxy-header rewrite is not trusted: the raw peer of the ASGI scope is checked, and any forwarding header refuses
the call), the key compared in constant time. Anything else, with or without a key set: 403. The key is never logged. Not part of the
public API. Taken: 202, then the main
process is signalled as a terminal's stop would (uvicorn's own handlers: requests in flight finish). It sits outside every other
middleware: it needs no session, no server and no module state."""
from __future__ import annotations

import hmac
import json
import os
import signal

PATH = "/launcher/stop"
HEADER = b"x-drishti-stop-key"
#: the headers a proxy adds: a request carrying any of them did not come straight from this machine's launcher
FORWARDING = (b"forwarded", b"x-forwarded-for", b"x-forwarded-host", b"x-forwarded-proto", b"x-real-ip")
KEY_ENV = "DRISHTI_STOP_KEY"
#: the console's main process (run_drishti_web.py sets it before uvicorn starts; with several workers, the supervisor)
MAIN_PID_ENV = "DRISHTI_CONSOLE_MAIN_PID"


def loopback(host: str | None) -> bool:
    h = (host or "").strip("[]")
    return h.startswith("127.") or h in ("::1", "0:0:0:0:0:0:0:1")


def stop_console() -> None:
    """Signals the console's main process as a terminal's stop would: this process itself (one worker), else the workers' supervisor
    (POSIX SIGTERM; Windows CTRL_BREAK to its process group, which the launcher made its own; uvicorn's supervisor and workers take it)."""
    main = int(os.environ.get(MAIN_PID_ENV) or os.getpid())
    if main == os.getpid():
        signal.raise_signal(signal.SIGTERM)
    elif os.name == "nt":
        os.kill(main, signal.CTRL_BREAK_EVENT)  # type: ignore[attr-defined]
    else:
        os.kill(main, signal.SIGTERM)


class LauncherStop:
    """Pure ASGI: answers ``POST /launcher/stop`` itself; every other request goes on untouched."""

    def __init__(self, app, key: str | None = None, stop=stop_console):
        self.app = app
        self.key = (key if key is not None else os.environ.get(KEY_ENV, "")).strip().encode()
        self.stop = stop

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http" or scope.get("path") != PATH:
            return await self.app(scope, receive, send)
        if scope.get("method") != "POST":
            return await _answer(send, 405, {"detail": "POST only"})
        headers = dict(scope.get("headers") or [])
        given = headers.get(HEADER, b"").strip()
        client = (scope.get("client") or (None, None))[0]
        direct = loopback(client) and not any(h in headers for h in FORWARDING)
        if not self.key or not direct or not given or not hmac.compare_digest(given, self.key):
            return await _answer(send, 403, {"detail": "the launcher's stop takes the key of the pid file, from this machine only"})
        await _answer(send, 202, {"stopping": True})
        self.stop()


async def _answer(send, status: int, body: dict) -> None:
    data = json.dumps(body).encode()
    await send({"type": "http.response.start", "status": status,
                "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(data)).encode()), (b"cache-control", b"no-store")]})
    await send({"type": "http.response.body", "body": data})
