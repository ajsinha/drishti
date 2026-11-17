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

"""The modules' recorded landing answers, replayed by the console's stand-ins (docs/architecture/THIN_CONSOLE_DESIGN.md step S8a; 5.2,
"Recorded answers"): ``tests/answers/landings/<module>.json``, written by the module's own Java test from its stand-in server's data
(``MarketLandingRecordTest``, ``TradingLandingRecordTest``; ``-Ddrishti.record=true`` writes them again, and the Java test fails while a
file differs from what the module answers now). One direction only: the module computes the figures, the console passes them on and draws
them, and its tests replay what the module said. No figure is written here.

Each recording holds, per person (user, roles, lens and the settings the module reads: ``tradingView``), the module's answer of every
``GET <path>[?for=desk]`` and of each new-trade check body posted. A call with no recording raises ``BackendError(404)`` that names the
person and the call: add them to the Java test's people and record again."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlencode

from core import edition
from core.backend import BackendError

ANSWERS = Path(__file__).resolve().parent / "answers" / "landings"


@lru_cache(maxsize=None)
def recording(module: str) -> dict:
    """``answers/landings/<module>.json`` as written by the module's Java test."""
    return json.loads((ANSWERS / f"{module}.json").read_text(encoding="utf-8"))


def _lens() -> str:
    """The lens the console sends the server for this request (the header core/backend.py adds)."""
    return next(iter(edition.headers().values()), "")


class Recorded:
    """One module's recorded answers (``market-data``, ``trading``) and the API prefix they answer (``/market-data/landing``)."""

    def __init__(self, module: str, prefix: str):
        self.module, self.prefix = module, prefix

    def answers(self, path: str) -> bool:
        return path == self.prefix or path.startswith(self.prefix + "/")

    async def call(self, backend, method: str, path: str, ident, body=None, params: dict | None = None):
        settings = await backend.settings(ident) if hasattr(backend, "settings") else {}
        view = (settings or {}).get("tradingView") or ""
        who = (getattr(ident, "user", ""), sorted(getattr(ident, "roles", ()) or ()), _lens(), view)
        person = next((p for p in recording(self.module)["people"]
                       if (p["user"], sorted(p["roles"]), p["lens"], (p.get("settings") or {}).get("tradingView") or "") == who), None)
        if person is None:
            raise BackendError(404, "DRS-4040", f"no recorded {self.module} answers for {who}: add the person to the module's landing record test")
        if method == "POST":
            hit = next((c["answer"] for c in person.get("checks") or [] if c["body"] == body), None)
            if hit is None:
                raise BackendError(404, "DRS-4040", f"no recorded {self.module} answer of POST {path} {body} for {who}")
            return json.loads(json.dumps(hit))
        params = {k: v for k, v in (params or {}).items() if v not in (None, "", [])}
        key = f"{method} {path}" + (f"?{urlencode(sorted(params.items()))}" if params else "")
        if key not in person["answers"]:
            raise BackendError(404, "DRS-4040", f"no recorded {self.module} answer of {key} for {who}")
        return json.loads(json.dumps(person["answers"][key]))         # a fresh copy: a route may change what it passes on
