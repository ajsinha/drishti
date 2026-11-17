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

"""The stand-in server of Drishti Market's landings (test_edition.py, test_edition_market_browser.py). The landings' figures (the Market desk's
tiles and curves, the biggest moves, the Quality landing's checks) are the market-data module's, worked out on the server
(/api/v1/market-data/landing, THIN_CONSOLE_DESIGN.md step S8a): this stand-in replays the module's own recorded answers
(answers/landings/market-data.json, written by MarketLandingRecordTest over the module's stand-in market data with known problems; see
landing_answers.py) and holds no figure of its own. What the console still asks the server itself is answered here: the Explorer's counts
(the server's search of ``<CMD> limit 1``) and the person's recent commands. The business date is 2026-09-30 (FakeBackend's)."""
from __future__ import annotations

from conftest import FakeBackend
from core.backend import BackendError
from landing_answers import Recorded

#: the market-data pack's kinds, by command, with the records each holds (the Explorer's counts)
COUNTS = {"CRV": 28, "REPO": 7, "IRV": 7, "CPV": 7, "FIX": 7, "INF": 4, "INFC": 4, "FX": 12, "FXF": 12, "FXV": 12, "CDS": 16, "BND": 32,
          "EQ": 10, "EQX": 5, "DIV": 15, "EQV": 15, "CMD": 8, "CMDC": 8, "CMDV": 8, "CORR": 3}
LANDINGS = Recorded("market-data", "/market-data/landing")


class MarketBackend(FakeBackend):
    """The server's answers to Drishti Market's landings: the module's recorded figures, the pack's counts, the person's recent commands."""

    def __init__(self):
        super().__init__()
        self.asked: list[str] = []

    async def search(self, q, ident=None):
        self.asked.append(q)
        head = q.split()[0]
        if head not in COUNTS:
            raise BackendError(404, "DRS-4023", f"{head} is not part of the edition")
        return {"kind": head.lower(), "matched": COUNTS[head], "scanned": COUNTS[head], "rows": []}

    async def engine(self, method, path, ident, /, body=None, raw=False, accept="", timeout=None, **params):
        if LANDINGS.answers(path):
            return await LANDINGS.call(self, method, path, ident, body, params)
        return await super().engine(method, path, ident, body=body, raw=raw, accept=accept, timeout=timeout, **params)

    async def command_history(self, ident=None):
        return ["CRV CRV-USD-OIS", "ECON CAN", "VAR BANK", "FXV FXV-EURUSD", "CRV CRV-USD-OIS", "TRD MX-20000001"]
