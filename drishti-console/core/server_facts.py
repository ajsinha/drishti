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

"""What the server decided, kept for drawing: the installation's answer (docs/architecture/THIN_CONSOLE_DESIGN.md 3.2).

"The server decides, the console draws." The backend watch (core/backend_watch.py) reads each server's ``/public/about`` every
``backend.check_seconds``; its ``stamps.installation`` is the digest of the server's bootstrap document. When it differs from the digest
held here, :meth:`ServerFacts.follow` asks ``GET /api/v1/console/bootstrap`` with the console's service identity and ``If-None-Match``,
and swaps the held answer whole (a ``304`` keeps it). One answer per app and server: nothing of any person is in it.

Never stale in silence: an answer is used only while its digest is the server's last stamp. When the server is away, or its answer does not
read, the last answer is kept and :meth:`ServerFacts.stale` says so (Admin -> Health shows it with the server-away notice); nothing is made
up from local files. Before the first answer the lookups say "not known" (``None``) and the callers draw as they did before any server
answered.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field

import httpx

LOG = logging.getLogger("drishti.console.facts")

#: said on every page and on Admin -> Health when the server does not give the answers this console asks (an older server)
OLDER = ("This server is older than the console: it does not give the answers the console draws from, so packs and menus are not shown. "
         "Upgrade the servers before the consoles (OPERATIONS_GUIDE.md, upgrade order).")


@dataclass(frozen=True)
class Answer:
    """One installation answer as the server sent it: never changed after it is made (a new one replaces it whole)."""
    digest: str
    doc: dict
    at: float                                  # wall time it was read

    def command(self, word: str) -> dict | None:
        """What a command word needs (``{pack, module, loaded}``), or None when no pack names it."""
        return (self.doc.get("commands") or {}).get(str(word or "").upper())


@dataclass
class _Held:
    answer: Answer | None = None
    stamp: str = ""                            # the server's last stamps.installation
    problem: str = ""                          # why the last ask failed ("" when the held answer is the server's last)
    asked: float = 0.0
    stamps: dict = field(default_factory=dict)
    about: dict = field(default_factory=dict)  # what the server says of itself: version, product, tagline, notice, edition
    older: bool = False                        # the server gives no stamps: older than this console


class ServerFacts:
    """The installation's answer per server of this console (one per app; each worker process holds its own and follows the stamps)."""

    PATH = "/api/v1/console/bootstrap"

    #: what of /public/about the pages may show (the server's words about itself)
    ABOUT_KEYS = ("product", "version", "tagline", "notice", "edition")

    def __init__(self, timeout: float = 10.0, console_build: str = ""):
        self.timeout = float(timeout)
        self.console_build = str(console_build or "")   # diagnostic only (Admin -> Health), never shown as the product's version
        self._held: dict[str, _Held] = {}

    # -- following the server ----------------------------------------------------------------------------------------------------------
    async def follow(self, sid: str, about: dict, base_url: str, headers: dict) -> bool:
        """The watch read ``about`` from server ``sid``: ask its bootstrap again when its installation stamp changed. ``headers``: the
        console's service identity (``Authorization``). True when a new answer was taken."""
        held = self._held.setdefault(sid, _Held())
        if isinstance(about, dict):
            held.about = {k: about.get(k) for k in self.ABOUT_KEYS if about.get(k) is not None}
        stamps = (about or {}).get("stamps") if isinstance(about, dict) else None
        if not isinstance(stamps, dict) or not stamps.get("installation"):
            held.problem = OLDER
            held.older = True
            return False
        held.older = False
        held.stamps = dict(stamps)
        held.stamp = str(stamps["installation"])
        if held.answer is not None and held.answer.digest == held.stamp:
            held.problem = ""
            return False
        return await self._fetch(held, base_url, headers)

    async def _fetch(self, held: _Held, base_url: str, headers: dict) -> bool:
        ask = dict(headers or {})
        if held.answer is not None:
            ask["If-None-Match"] = f'"{held.answer.digest}"'
        held.asked = time.time()
        try:
            status, body = await self.get(base_url.rstrip("/") + self.PATH, ask)
        except Exception as e:  # noqa: BLE001 - the server away for a moment: the last answer stays, with the notice
            held.problem = f"the installation's answer could not be read: {type(e).__name__}"
            return False
        if status == 304 and held.answer is not None:
            held.problem = ""
            return False
        if status in (401, 403):
            held.problem = ("the server refused the console's service identity for its installation's answer "
                            "(auth.token_secret must be the server's drishti.security.secret)")
            LOG.warning(held.problem)
            return False
        if status != 200 or not isinstance(body, dict) or not body.get("digest"):
            held.problem = f"the installation's answer did not read (HTTP {status})"
            return False
        held.answer = Answer(str(body["digest"]), body, time.time())
        held.problem = "" if held.answer.digest == held.stamp else "the answer is older than the server's stamp; asked again at the next check"
        return True

    async def get(self, url: str, headers: dict) -> tuple[int, dict | None]:
        """One GET (tests replace it): the status and the JSON body (None for a 304 or a body that is not JSON)."""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            r = await client.get(url, headers=headers)
        if r.status_code != 200:
            return r.status_code, None
        try:
            return 200, r.json()
        except ValueError:
            return 200, None

    # -- reading -----------------------------------------------------------------------------------------------------------------------
    def answer(self, sid: str | None = None) -> Answer | None:
        """The held answer of server ``sid`` (the request's server when None), or None before the first."""
        held = self._held.get(sid or _current())
        return held.answer if held is not None else None

    def about(self, sid: str | None = None) -> dict:
        """What server ``sid`` (the request's when None) says of itself: ``version``, ``product``, ``tagline``, ``notice``, ``edition``;
        empty before its first answer (the pages then show no version rather than one of the console's)."""
        held = self._held.get(sid or _current())
        return dict(held.about) if held is not None else {}

    def notice(self, sid: str | None = None) -> str:
        """The words every page shows when the server is older than this console ("" otherwise)."""
        held = self._held.get(sid or _current())
        return OLDER if held is not None and held.older else ""

    def command(self, word: str, sid: str | None = None) -> dict | None:
        """What a command word needs, or None when no pack names it or no answer has arrived yet."""
        a = self.answer(sid)
        return a.command(word) if a is not None else None

    def stale(self, sid: str | None = None) -> str:
        """Why the held answer may not be the server's latest ("" when it is): shown beside the server-away notice."""
        held = self._held.get(sid or _current())
        if held is None or held.answer is None:
            return held.problem if held is not None else ""
        if held.answer.digest != held.stamp:
            return held.problem or "the installation's answer is older than the server's stamp"
        return held.problem

    def health(self) -> list[dict]:
        """Per server: the digest held, its age and any problem (Admin -> Health)."""
        now = time.time()
        return [{"server": sid, "digest": h.answer.digest if h.answer else None, "stamp": h.stamp,
                 "ageSeconds": round(now - h.answer.at) if h.answer else None, "problem": self.stale(sid),
                 "serverBuild": h.about.get("version"), "consoleBuild": self.console_build,
                 "buildsDiffer": bool(h.about.get("version")) and bool(self.console_build) and h.about.get("version") != self.console_build}
                for sid, h in sorted(self._held.items())]


def _current() -> str:
    from core import servers
    return servers.current()
