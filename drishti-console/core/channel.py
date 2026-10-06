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

"""One live channel: everything live one caller needs on ONE connection (docs/architecture/ELEMENTS.md, build step 4).

Extracted from the console's ``/api/channel`` so the embed stream (``/embed/v1/channel``) runs the very same logic. The class
knows no cookies, requests or session: the identity it runs as, the callable that turns an upstream event into what the
browser wants, the subscription limit and the ``who`` fingerprint are all passed in; the owner of a channel is the user plus
the host application (empty for the console), so one caller can never add to another's channel.
"""
from __future__ import annotations

import asyncio
import contextlib
import contextvars
import json
import secrets
import time
from typing import AsyncIterator, Awaitable, Callable
from urllib.parse import quote

from core import asof
from core.backend import BackendError

Render = Callable[[str, str], str]      # (event, data) -> data as the caller wants it (panel patches as HTML)


class ChannelSession:
    """The subscriptions of one open channel and the SSE text they produce, built for ``identity``."""

    def __init__(self, backend, identity, render: Render, *, limit: int, who: str = "", app: str = "",
                 registry: dict | None = None, queue_size: int = 2000, watchdog_seconds: float = 5.0):
        self.backend, self.me, self.render = backend, identity, render
        self.limit, self.who, self.app = limit, who, app
        self.registry = registry if registry is not None else {}
        self.cid = secrets.token_urlsafe(12)
        self.user = identity.user if identity is not None else ""
        self.queue: asyncio.Queue = asyncio.Queue(maxsize=queue_size)
        self.tasks: dict[str, asyncio.Task] = {}
        self.opened: dict[str, list] = {}          # subscription -> its upstream HTTP responses, closed to end it
        self.stopping = asyncio.Event()
        self.ended = False
        self.last_pull = time.monotonic()
        self.watchdog_seconds = watchdog_seconds

    # -- who may change it ------------------------------------------------------------------------------------------
    def owned_by(self, user: str, app: str = "") -> bool:
        return self.user == user and self.app == app

    def change(self, add=(), remove=()) -> None:
        """Adds and removes subscriptions ({"add": [...], "remove": [...]} of a POST), within the limit."""
        for sub in list(remove or [])[:self.limit]:
            self.remove(str(sub))
        for sub in list(add or [])[:self.limit * 2]:
            self.add(str(sub))

    # -- subscriptions ----------------------------------------------------------------------------------------------
    def add(self, sub: str) -> None:
        if not sub or sub in self.tasks or self.ended:
            return
        if len(self.tasks) >= self.limit:             # say so: a view that silently never ticks looks broken
            with contextlib.suppress(asyncio.QueueFull):
                self.queue.put_nowait((sub, "gone", json.dumps({"code": "DRS-5003", "detail": f"this browser already follows {self.limit} "
                                                                "live subscriptions (live.max_subscriptions): close some tabs or panes"})))
            return
        self.tasks[sub] = self._detached(self._pump(sub))

    def remove(self, sub: str) -> None:
        task = self.tasks.pop(sub, None)
        if task is not None:
            self._detached(self._close_sub(sub, task))

    def end(self) -> None:
        if not self.ended:
            self.ended = True
            self._detached(self._stop())

    async def _pump(self, sub: str) -> None:
        responses = self.opened.setdefault(sub, [])
        if sub.startswith("view:") and "/" in sub:
            kind, _, id_ = sub[5:].partition("/")
            upstream, is_view = self.backend.stream(kind, id_, self.me, opened=responses), True
        elif sub == "alerts":
            upstream, is_view = self.backend.sse("/me/alerts/stream", self.me, opened=responses), False
        elif sub.startswith("monitor:"):
            upstream, is_view = self.backend.sse(f"/me/monitors/{quote(sub[8:])}/stream", self.me, opened=responses), False
        else:
            return
        try:
            async for event, data in upstream:
                await self.queue.put((sub, event, self.render(event, data) if is_view else data))
        except BackendError as e:
            await self.queue.put((sub, "gone", json.dumps({"code": e.code, "detail": e.detail})))
        except Exception as e:  # noqa: BLE001 - one broken subscription must not end the others (nor a closed channel)
            if not self.stopping.is_set() and sub in self.tasks:
                await self.queue.put((sub, "gone", json.dumps({"code": "DRS-5003", "detail": str(e)})))

    async def _close_sub(self, sub: str, task) -> None:
        for r in list(self.opened.pop(sub, [])):
            try:
                await r.aclose()                        # the pump's pending read fails and it returns
            except Exception:  # noqa: BLE001 - already closed
                pass
        await asyncio.sleep(0.5)
        if task is not None:
            task.cancel()                               # still opening its request

    async def _stop(self) -> None:
        """Ends every upstream stream by closing its response (never by cancelling a reading task: see _detached)."""
        self.stopping.set()
        self.registry.pop(self.cid, None)
        await asyncio.gather(*(self._close_sub(s, t) for s, t in list(self.tasks.items())), return_exceptions=True)

    @staticmethod
    def _detached(coro):
        # Upstream work runs in tasks with a fresh context holding only the business date and "known at". Anything
        # started from the request's context inherits Starlette's (anyio's) cancel scope, which, once the request has
        # ended, cancels every await in it for good: the streams' clean-up never completed and their connections to the
        # server stayed open (the console ran out of connections, and the UI froze). Ending a channel therefore closes
        # the responses from a detached task too.
        ctx = contextvars.Context()
        ctx.run(asof.set_current, asof.current())
        ctx.run(asof.set_known, asof.known_at())
        return asyncio.get_running_loop().create_task(coro, context=ctx)

    async def _watchdog(self) -> None:
        # Starlette may stop reading this stream when the browser goes away without closing it. A live channel is read
        # at least every two seconds; one nobody has read for five has lost its reader.
        while not self.ended:
            await asyncio.sleep(1.0)
            if time.monotonic() - self.last_pull > self.watchdog_seconds:
                self.end()

    # -- the stream -------------------------------------------------------------------------------------------------
    async def events(self, asked: list[str], is_disconnected: Callable[[], Awaitable[bool]]) -> AsyncIterator[str]:
        """The SSE text of the channel: a ``channel`` event first, then every subscription's events, a comment every ~15 s."""
        for x in asked:
            self.add(x)
        self.registry[self.cid] = {"user": self.user, "app": self.app, "add": self.add, "remove": self.remove, "session": self}
        self._detached(self._watchdog())
        try:
            yield f"event: channel\ndata: {json.dumps({'ch': '', 'd': {'id': self.cid, 'subs': asked, 'who': self.who}})}\n\n"
            idle = 0
            checked = time.monotonic()
            while True:
                self.last_pull = time.monotonic()
                if time.monotonic() - checked > 1.0:                # busy or quiet: notice a closed tab within a second
                    checked = time.monotonic()
                    if await is_disconnected():
                        break
                current = list(self.tasks.values())
                if current and self.queue.empty() and all(t.done() for t in current):
                    yield "event: end\ndata: {}\n\n"                # every subscription has ended: say so, and close
                    break
                try:
                    sub, event, data = await asyncio.wait_for(self.queue.get(), timeout=2.0)
                    idle = 0
                except asyncio.TimeoutError:
                    if await is_disconnected():
                        break
                    idle += 1
                    if idle % 7 == 0:                               # a comment every ~15 s keeps proxies from closing it
                        yield ": hb\n\n"
                    continue
                try:
                    body = json.loads(data)
                except (TypeError, ValueError):
                    body = data
                yield f"event: {event}\ndata: {json.dumps({'ch': sub, 'd': body}, ensure_ascii=False)}\n\n"
        finally:
            self.end()
