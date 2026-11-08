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

"""Who a request acts for, carried into work that outlives it (docs/architecture/adr/016-one-console-many-servers.md › As built).

A request's scope lives in context variables the middleware sets: the server it runs against (``servers.CURRENT``), the session's
pin in a pool of servers (``cluster.SESSION``), the lens (``edition._lens``), the share it was opened through (``collab._share``),
the business date and "known at" (``asof``), the app's engines (``engines._current``), the app's modules (``modules._current``) and the
person's module answer (``modules._person``: per request, never shared between people). Work started for the request in a task
of its own (a live channel's subscriptions, a pack job) must run with exactly these values, never with the defaults of a fresh
context: those name the FIRST server, so a stream opened for a person signed in on another server would read that server's data
with their token. :func:`snapshot` captures every one of them; :func:`run_in` starts a task in a fresh context holding the
snapshot (fresh, so the task never inherits the request's cancel scope). A call relayed to another worker carries the snapshot as
:func:`to_wire` and the handler acts inside :func:`from_wire`.

Every request-scoped ContextVar of the console is listed in :data:`VARS`; tests/test_request_context.py fails when one is added
to core/ and not listed here.
"""
from __future__ import annotations

import asyncio
import contextvars
from types import MappingProxyType
from typing import Any, Coroutine, Mapping

from core import asof, cluster, collab, edition, engines, modules, servers

VARS: tuple[contextvars.ContextVar, ...] = (servers.CURRENT, cluster.SESSION, edition._lens, collab._share, asof._current, asof._known,
                                           engines._current, modules._current, modules._person)
# what crosses to another worker (JSON): every variable but the app's engines and modules, which the receiving worker has its own of,
# and the person's module answer, which it asks its server for again (it is that person's, per request)
_WIRE: dict[str, contextvars.ContextVar] = {"server": servers.CURRENT, "session": cluster.SESSION, "lens": edition._lens,
                                           "share": collab._share, "asof": asof._current, "known": asof._known}

Snapshot = Mapping[contextvars.ContextVar, Any]


def snapshot() -> Snapshot:
    """The request-scoped values of the calling context (each variable's default where it was never set)."""
    return MappingProxyType({v: v.get() for v in VARS})


def context(snap: Snapshot) -> contextvars.Context:
    """A fresh context holding only ``snap``: nothing else of the caller (no cancel scope) comes along."""
    ctx = contextvars.Context()
    for var, value in snap.items():
        ctx.run(var.set, value)
    return ctx


def run_in(snap: Snapshot, coro: Coroutine) -> asyncio.Task:
    """``coro`` as a task acting for the person ``snap`` was taken from."""
    return asyncio.get_running_loop().create_task(coro, context=context(snap))


def to_wire(snap: Snapshot | None = None) -> dict:
    """The snapshot as JSON for a relayed call (core/channel_relay.py)."""
    snap = snapshot() if snap is None else snap
    return {k: snap.get(v, v.get()) for k, v in _WIRE.items()}


def from_wire(wire: dict | None, app=None) -> Snapshot:
    """A relayed call's snapshot: an unknown server or a missing one is refused (``ValueError``), never read as the default server;
    the engines are those of ``app`` (the worker that runs the call)."""
    wire = wire if isinstance(wire, dict) else {}
    sid = str(wire.get("server") or "")
    catalogue = getattr(getattr(app, "state", None), "servers", None)
    if not sid or (catalogue is not None and catalogue.get(sid) is None):
        raise ValueError(f"relayed call for no known server ({sid or 'none'})")
    out: dict[contextvars.ContextVar, Any] = {servers.CURRENT: sid, cluster.SESSION: str(wire.get("session") or ""),
                                              edition._lens: wire.get("lens") or None, collab._share: collab.clean_share(wire.get("share")),
                                              asof._current: asof.clean(wire.get("asof")), asof._known: asof.clean_known(wire.get("known"))}
    eds = getattr(getattr(app, "state", None), "editions", None)
    out[engines._current] = getattr(eds, "engines", None) or engines._current.get()
    out[modules._current] = getattr(eds, "modules", None) or modules._current.get()
    out[modules._person] = None                                          # asked again for this person on the receiving worker
    return MappingProxyType(out)


async def acting(snap: Snapshot, coro: Coroutine):
    """``await coro`` acting for ``snap`` (a relayed call's handler): in a task of its own, so the caller's context is untouched."""
    return await run_in(snap, coro)
