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

"""Token life of one embed channel (docs/architecture/ELEMENTS.md, section 7.2; build step 7).

A live stream outlives the token it was opened with, but not unchecked. ``StreamGuard`` watches one ``ChannelSession``:

* at the token's expiry it sends ``event: token`` (``{"ch": "", "d": {"expiresIn": 0}}``); the element answers with a fresh token
  on ``POST /embed/v1/channel/{cid}/token`` (verified like any call, same user and application) and the stream carries on;
* with no fresh token within ``embed.token_grace_seconds`` the stream ends with ``gone`` ``DRS-8001``: expiry is enforced;
* every ``embed.recheck_seconds`` it asks the server whether the token still passes every check (user, application, scopes) and
  ends the stream with the server's own code when it does not.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import time

from core.backend import BackendError
from core.embed import EmbedError


class StreamGuard:
    def __init__(self, session, claims: dict, *, grace: float = 30.0, recheck: float = 60.0, clock=time.time):
        self.session, self.app, self.user = session, str(claims["azp"]), str(claims["sub"])
        self.exp = float(claims["exp"])
        self.grace, self.recheck, self._clock = grace, recheck, clock
        self._refreshed = asyncio.Event()
        self.task: asyncio.Task | None = None

    def start(self) -> None:
        self.task = self.session._detached(self._run())

    def stop(self) -> None:
        if self.task is not None:
            self.task.cancel()

    def refresh(self, identity, claims: dict) -> None:
        """A fresh token for the same user and application replaces the old one; later upstream calls use it."""
        if str(claims["sub"]) != self.user or str(claims["azp"]) != self.app:
            raise EmbedError(401, "DRS-8001", "the fresh token is for another user or host application")
        if float(claims["exp"]) <= self.exp:
            raise EmbedError(401, "DRS-8001", "the fresh token does not extend the session")
        self.exp = float(claims["exp"])
        self.session.me = identity
        self._refreshed.set()

    def _say(self, event: str, body: dict) -> None:
        with contextlib.suppress(asyncio.QueueFull):
            self.session.queue.put_nowait(("", event, json.dumps(body)))

    async def _run(self) -> None:
        s = self.session
        last_check = time.monotonic()
        try:
            while not s.ended:
                left = self.exp - self._clock()
                if left > 0:
                    self._refreshed.clear()
                    wait = min(left, max(0.2, self.recheck - (time.monotonic() - last_check)))
                    with contextlib.suppress(asyncio.TimeoutError):
                        await asyncio.wait_for(self._refreshed.wait(), timeout=wait)
                    if self.recheck > 0 and time.monotonic() - last_check >= self.recheck:
                        last_check = time.monotonic()
                        if not await self._still_valid():
                            return
                    continue
                self._refreshed.clear()
                self._say("token", {"expiresIn": 0, "graceSeconds": self.grace})
                try:
                    await asyncio.wait_for(self._refreshed.wait(), timeout=self.grace)
                except asyncio.TimeoutError:
                    await self._end("DRS-8001", "the embed token expired and no fresh one arrived: the stream is closed")
                    return
        except asyncio.CancelledError:
            raise

    async def _still_valid(self) -> bool:
        try:
            await self.session.backend._get("/embed/check", self.session.me)
            return True
        except BackendError as e:
            if e.status in (401, 403, 404, 410) or e.code.startswith("DRS-800"):
                await self._end(e.code, e.detail)
                return False
            return True                                   # the server is busy or away: the next re-check decides
        except Exception:                                 # noqa: BLE001
            return True

    async def _end(self, code: str, detail: str) -> None:
        self._say("gone", {"code": code, "detail": detail})
        await asyncio.sleep(0.3)                          # let the frame out before the stream closes
        self.session.end()
