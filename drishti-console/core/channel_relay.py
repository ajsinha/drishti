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

"""Live-channel changes across the console's worker processes and across console hosts (PO-11, CLUSTER_DEPLOYMENT.md section 9.4).

The console serves its port from several worker processes (``server.workers``), and a site may run several consoles behind one load
balancer. A live channel's stream is held by the worker that opened it, and the browser's later calls on it (``POST /api/channel/{cid}``,
``POST /embed/v1/channel/{cid}`` and its ``/token``) reach whichever worker, on whichever host, the kernel or the load balancer hands the
connection to. The relay passes such a call to the worker that holds the channel and answers with that worker's answer.

Each worker listens on a relay port of its own, and the id of every channel (and pack job) it opens names it:

* ``<random>.<port>``: a worker of this host (the relay listens on loopback, any free port);
* ``<random>~<host>.<port>``: a worker of the console at ``<host>`` (``live.relay.across_hosts``: the relay listens on
  ``live.relay.bind``, the first free port of ``live.relay.port`` .. ``port + server.workers - 1``, and ``<host>`` is
  ``live.relay.advertise`` or this machine's own LAN address, never a loopback one).

A worker that gets a call for a channel it does not hold passes it, already checked, to the worker named in the id. Every message is signed
with the relay key (``DRISHTI_CONSOLE_RELAY_KEY``: HMAC-SHA256 over the message and its time), every answer with the same key over the
question's signature, so nothing else on the network is listened to or believed; a message older than ``live.relay.window_seconds`` or seen
before is refused. The launcher makes a key for the workers of one host; across hosts every console is given the same key. A console of one
process without a key has no relay: ids carry no port.

Every message carries the caller's request scope (core/request_context.py: its server above all) and the handler acts in it, never in the
listener's own context (the lifespan's, whose server is the console's first); a message naming no known server is refused. The relay is
per app (``app.state.relay``), never per process.
"""
from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import inspect
import json
import logging
import os
import re
import socket
import time
from typing import Any, Callable

KEY_ENV = "DRISHTI_CONSOLE_RELAY_KEY"
MADE_ENV = "DRISHTI_CONSOLE_RELAY_KEY_MADE"   # set by the launcher when it made the key itself: good for its own workers only
LINE_LIMIT = 1 << 20                     # a channel change is a few KB (the routes cap their bodies)
TIMEOUT = 3.0
WINDOW = 30.0                            # a message's time may differ from ours by this much (clock skew between hosts)
LOOPBACK = "127.0.0.1"
_HOST = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
LOG = logging.getLogger("drishti.console.relay")

HANDLERS: dict[str, Callable[[Any, str, dict], Any]] = {}   # op -> fn(app, cid, payload) -> dict (or an awaitable of one)


class Relay:
    """One app's relay (``app.state.relay``): its port, the replayed messages it refuses and what it did. Per app, never per process, so
    a handler always gets the app whose worker holds the channel (two apps in one process, as tests have, each answer for their own)."""

    def __init__(self) -> None:
        self.port, self.server, self.host = 0, None, ""
        self.timeout, self.window = TIMEOUT, WINDOW
        self.seen: dict[str, float] = {}             # signatures accepted within the window: a replayed message is refused
        self.counts: dict[str, int] = {"forwarded": 0, "answered": 0, "refused": 0, "failed": 0}

    def suffix(self) -> str:
        if not self.port:
            return ""
        return f"~{self.host}.{self.port}" if self.host else f".{self.port}"

    def mine(self, o: tuple[str, int]) -> bool:
        host, port = o
        return port == self.port and (host == LOOPBACK or host == self.host)


def of(app) -> Relay:
    """The relay of ``app`` (made on first use)."""
    st = getattr(app, "state", None)
    relay = getattr(st, "relay", None) if st is not None else None
    if not isinstance(relay, Relay):
        relay = Relay()
        if st is not None:
            st.relay = relay
    return relay


def key() -> str:
    return os.environ.get(KEY_ENV, "")


def suffix(app) -> str:
    """What the channel ids of ``app``'s worker end with: ``.<port>`` (this host) or ``~<host>.<port>`` (across hosts) while its relay listens."""
    return of(app).suffix()


def owner(cid: str) -> tuple[str, int] | None:
    """The (host, port) of the worker an id names, or None when it names none."""
    text = str(cid)
    _head, tilde, tail = text.rpartition("~")
    if tilde:
        host, dot, port = tail.rpartition(".")
        return (host, int(port)) if dot and _HOST.match(host) and _port(port) else None
    _head, dot, port = text.rpartition(".")
    return (LOOPBACK, int(port)) if dot and _port(port) else None


def owner_port(cid: str) -> int | None:
    """The relay port an id names (None: it names no worker); the routes ask it to tell another worker's channel from a gone one."""
    o = owner(cid)
    return o[1] if o else None


def stats(app) -> dict:
    """What this worker's relay did: calls passed on, calls answered for others, messages refused, calls that found no worker."""
    r = of(app)
    return {**r.counts, "port": r.port, "host": r.host or None, "acrossHosts": bool(r.host)}


def _port(text: str) -> bool:
    return text.isdigit() and 0 < int(text) < 65536


def _flag(v: Any) -> bool:
    return v is not None and str(v).strip().lower() in ("1", "true", "yes", "on")


def _number(v: Any, default: float) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return float(default)


def _settings(app) -> dict:
    s = getattr(getattr(app, "state", None), "settings", None)

    def get(k: str, d: Any = None) -> Any:
        return s.get(k, d) if s is not None and hasattr(s, "get") else d
    return {"across": _flag(get("live.relay.across_hosts", False)), "advertise": str(get("live.relay.advertise", "") or "").strip(),
            "bind": str(get("live.relay.bind", "0.0.0.0") or "0.0.0.0"), "port": int(_number(get("live.relay.port", 0), 0)),
            "span": max(1, int(_number(get("server.workers", 1), 1))), "timeout": _number(get("live.relay.timeout_seconds", TIMEOUT), TIMEOUT),
            "window": _number(get("live.relay.window_seconds", WINDOW), WINDOW)}


def primary_ipv4() -> str | None:
    """This machine's address on its default route (no packet is sent: connecting a UDP socket only picks the route)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            ip = s.getsockname()[0]
    except OSError:
        return None
    from core.hostrule import why

    return ip if ip and why(ip) is None else None


def _advertised(asked: str) -> str | None:
    from core.hostrule import why

    if asked:
        bad = why(asked)
        if bad is None and _HOST.match(asked):
            return asked
        LOG.warning("live.relay.advertise %s is no address another console can call (%s); using this machine's own address", asked,
                    bad or "not a host name")
    return primary_ipv4()


async def start(app) -> None:
    """Listens for the other workers (several workers) and, with ``live.relay.across_hosts``, for the other consoles."""
    relay = of(app)
    if not key() or relay.server is not None:
        return
    cfg = _settings(app)
    relay.timeout, relay.window = max(0.5, cfg["timeout"]), max(1.0, cfg["window"])
    host = None
    if cfg["across"]:
        if os.environ.get(MADE_ENV):
            LOG.warning("live.relay.across_hosts is on but %s was made by this console for its own workers: give every console the same "
                        "key; the relay stays on this host", KEY_ENV)
        else:
            host = _advertised(cfg["advertise"])
            if host is None:
                LOG.warning("live.relay.across_hosts is on but this machine has no address another console can call; the relay stays on this host")
    server = None
    if host:
        for port in range(cfg["port"], cfg["port"] + cfg["span"]) if cfg["port"] else [0]:
            try:
                server = await asyncio.start_server(lambda r, w: _serve(app, r, w), cfg["bind"], port, limit=LINE_LIMIT)
                break
            except OSError:
                continue
        if server is None:
            LOG.warning("no relay port free in %s..%s on %s; the relay stays on this host", cfg["port"], cfg["port"] + cfg["span"] - 1, cfg["bind"])
            host = None
    if server is None:
        server = await asyncio.start_server(lambda r, w: _serve(app, r, w), LOOPBACK, 0, limit=LINE_LIMIT)
    relay.port, relay.server, relay.host = server.sockets[0].getsockname()[1], server, host or ""
    if host:
        LOG.info("live-channel relay across hosts: %s:%s (bound to %s)", host, relay.port, cfg["bind"])


async def stop(app) -> None:
    relay = of(app)
    server, relay.server, relay.port, relay.host = relay.server, None, 0, ""
    if server is not None:
        server.close()
        with contextlib.suppress(Exception):
            await asyncio.wait_for(server.wait_closed(), 2)


def _sign(*parts: str) -> str:
    return hmac.new(key().encode(), "\n".join(parts).encode(), hashlib.sha256).hexdigest()


def _split(line: bytes) -> tuple[str, str] | None:
    text = line.decode("utf-8", "replace").rstrip("\n")
    sig, space, body = text.partition(" ")
    return (sig, body) if space and len(sig) == 64 else None


async def forward(app, cid: str, op: str, payload: dict, limit: int = LINE_LIMIT, timeout: float | None = None) -> dict | None:
    """The answer of the worker that holds channel ``cid`` (or another object named so: a pack job), on this host or another; None when no
    other worker holds it (or none answers, or its answer is not signed with the relay key). ``limit`` bounds the answer's line, ``timeout``
    the wait for it."""
    relay = of(app)
    o = owner(cid)
    if not key() or o is None or relay.mine(o):
        return None
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(o[0], o[1], limit=limit), relay.timeout)
    except (OSError, asyncio.TimeoutError):
        relay.counts["failed"] += 1
        return None
    try:
        from core import request_context

        # the caller's scope (its server above all) travels with the call: the handler acts in it (_serve), never in the relay's own context
        body = json.dumps({"at": time.time(), "op": op, "cid": cid, "payload": payload, "scope": request_context.to_wire()}, separators=(",", ":"))
        sig = _sign("ask", body)
        writer.write(f"{sig} {body}\n".encode())
        await writer.drain()
        parts = _split(await asyncio.wait_for(reader.readline(), timeout if timeout is not None else relay.timeout))
        if parts is None or not hmac.compare_digest(parts[0], _sign("answer", sig, parts[1])):
            relay.counts["failed"] += 1
            return None
        got = json.loads(parts[1])
        relay.counts["forwarded"] += 1
        return got if isinstance(got, dict) else None
    except (OSError, asyncio.TimeoutError, ValueError):
        relay.counts["failed"] += 1
        return None
    finally:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()


def _accepted(relay: Relay, sig: str, body: str) -> dict | None:
    """The message when it is signed with the relay key, recent, and not seen before; else None."""
    if not hmac.compare_digest(sig, _sign("ask", body)):
        return None
    msg = json.loads(body)
    now = time.time()
    if not isinstance(msg, dict) or abs(now - _number(msg.get("at"), 0)) > relay.window or sig in relay.seen:
        return None
    for old in [s for s, t in relay.seen.items() if t < now - 2 * relay.window]:
        relay.seen.pop(old, None)
    relay.seen[sig] = now
    return msg


async def _handle(app, fn, msg: dict) -> Any:
    """Runs a handler acting for the caller: in the scope the message carries (core/request_context.py), never in this listener's own
    (the lifespan's, whose server is the console's first). A message naming no known server is refused."""
    from core import request_context

    try:
        scope = request_context.from_wire(msg.get("scope"), app)
    except ValueError:
        return {"ok": False}

    async def call():
        out = fn(app, str(msg.get("cid", "")), msg.get("payload") or {})
        return await out if inspect.isawaitable(out) else out
    return await request_context.acting(scope, call())


async def _serve(app, reader, writer) -> None:
    relay = of(app)
    try:
        parts = _split(await asyncio.wait_for(reader.readline(), relay.timeout))
        msg = _accepted(relay, *parts) if parts is not None else None
        if msg is None:
            relay.counts["refused"] += 1
            writer.write(b'{"ok": false}\n')                  # unsigned: whoever sent it cannot take it for the relay's answer
        else:
            fn = HANDLERS.get(str(msg.get("op", "")))
            out = {"ok": False} if fn is None else await _handle(app, fn, msg)
            body = json.dumps(out, separators=(",", ":"))
            writer.write(f"{_sign('answer', parts[0], body)} {body}\n".encode())
            relay.counts["answered"] += 1
        await writer.drain()
    except Exception:  # noqa: BLE001 - a bad message is dropped; the caller answers "no such channel"
        pass
    finally:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()
