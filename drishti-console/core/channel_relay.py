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

"""Live-channel changes across the console's worker processes.

The console serves its port from several worker processes (``server.workers``). A live channel's stream is held by the worker
that opened it, and the browser's later calls on it (``POST /api/channel/{cid}``, ``POST /embed/v1/channel/{cid}`` and its
``/token``) reach whichever worker the kernel hands the connection to. Without this relay three calls in four found no such
channel, and the page reopened its channel (an embed element said ``reconnecting`` and repainted).

Each worker listens on a loopback port of its own, and the id of every channel it opens ends with that port (``<random>.<port>``).
A worker that gets a call for a channel it does not hold passes it, already checked, to the worker named in the id, and answers
with that worker's answer. Every message carries the console's relay key (``DRISHTI_CONSOLE_RELAY_KEY``, made by the launcher for
its workers), so nothing else on the machine is listened to. A console of one process has no key and no relay: ids carry no port.
"""
from __future__ import annotations

import asyncio
import contextlib
import hmac
import inspect
import json
import os
from typing import Any, Callable

KEY_ENV = "DRISHTI_CONSOLE_RELAY_KEY"
LINE_LIMIT = 1 << 20                     # a channel change is a few KB (the routes cap their bodies)
TIMEOUT = 3.0

HANDLERS: dict[str, Callable[[Any, str, dict], Any]] = {}   # op -> fn(app, cid, payload) -> dict (or an awaitable of one)
_state: dict[str, Any] = {"port": 0, "server": None}


def key() -> str:
    return os.environ.get(KEY_ENV, "")


def suffix() -> str:
    """What this worker's channel ids end with: ``.<port>`` while the relay listens, else nothing."""
    return f".{_state['port']}" if _state["port"] else ""


def owner_port(cid: str) -> int | None:
    _head, dot, tail = str(cid).rpartition(".")
    return int(tail) if dot and tail.isdigit() and 0 < int(tail) < 65536 else None


async def start(app) -> None:
    """Listens for the other workers (a console with several workers only)."""
    if not key() or _state["server"] is not None:
        return
    server = await asyncio.start_server(lambda r, w: _serve(app, r, w), "127.0.0.1", 0, limit=LINE_LIMIT)
    _state.update(port=server.sockets[0].getsockname()[1], server=server)


async def stop() -> None:
    server, _state["server"], _state["port"] = _state["server"], None, 0
    if server is not None:
        server.close()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(server.wait_closed(), 2)


async def forward(cid: str, op: str, payload: dict) -> dict | None:
    """The answer of the worker that holds channel ``cid``; None when no other worker holds it (or none answers)."""
    port = owner_port(cid)
    if not key() or port is None or port == _state["port"]:
        return None
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", port, limit=LINE_LIMIT), TIMEOUT)
    except (OSError, asyncio.TimeoutError):
        return None
    try:
        writer.write((json.dumps({"key": key(), "op": op, "cid": cid, "payload": payload}) + "\n").encode())
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), TIMEOUT)
        got = json.loads(line) if line else None
        return got if isinstance(got, dict) else None
    except (OSError, asyncio.TimeoutError, ValueError):
        return None
    finally:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()


async def _serve(app, reader, writer) -> None:
    try:
        line = await asyncio.wait_for(reader.readline(), TIMEOUT)
        msg = json.loads(line) if line else {}
        fn = HANDLERS.get(str(msg.get("op", ""))) if isinstance(msg, dict) else None
        if not isinstance(msg, dict) or not hmac.compare_digest(str(msg.get("key", "")), key()) or fn is None:
            out = {"ok": False}
        else:
            out = fn(app, str(msg.get("cid", "")), msg.get("payload") or {})
            if inspect.isawaitable(out):
                out = await out
        writer.write((json.dumps(out) + "\n").encode())
        await writer.drain()
    except Exception:  # noqa: BLE001 - a bad message is dropped; the caller answers "no such channel"
        pass
    finally:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()
