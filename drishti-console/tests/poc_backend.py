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

"""RUPAKA PHASE 0 PROOF OF CONCEPT (tests): the stand-in server with the POC's endpoints. The Arrow answers are real ones, captured
from the server's own writer (tests/fixtures/poc_*.arrows); the row feed ticks."""
import asyncio
import json
from pathlib import Path

from conftest import FakeBackend

FIXTURES = Path(__file__).resolve().parent / "fixtures"
DESKS = ["DESK-FI", "DESK-FX", "DESK-EQ", "DESK-CMD", "DESK-RATES", "DESK-CREDIT"]


def row(n: int, mtm: float, generation: int) -> dict:
    return {"tradeId": f"POC-{n:03d}", "desk": DESKS[n % 6], "currency": "USD", "productType": "IRS_FIXFLOAT", "side": "BUY" if n % 2 else "SELL",
            "trader": "•••", "notional": 1e6 * (n + 1), "mtm": mtm, "generation": generation, "at": "2026-10-10T00:00:00Z"}


class PocBackend(FakeBackend):
    queries: list = []

    async def poc_query(self, body, ident):
        self.queries.append(body)
        if body.get("groupBy") == ["trader"]:
            name = "poc_trader_masked" if body.get("preview") == "masked" else "poc_trader_raw"
            masked = "trader" if body.get("preview") == "masked" else ""
        else:
            name, masked = "poc_extract", ""
        return (FIXTURES / f"{name}.arrows").read_bytes(), {"x-poc-masked": masked, "x-poc-rows": "960", "server-timing": "query;dur=1.5",
                                                            "x-poc-layout": "typed", "content-type": "application/vnd.apache.arrow.stream"}

    async def poc_bench(self, runs, ident):
        return {"runs": runs}

    async def sse(self, path, ident=None, opened=None):
        if path == "/bi/poc/rows":
            yield "view", json.dumps([row(n, 100.0 * n, 1) for n in range(12)])
            g = 1
            while True:
                await asyncio.sleep(0.1)
                g += 1
                yield "row", json.dumps([row(n, 100.0 * n + g, g) for n in range(12)])
        else:
            yield "hello", "{}"
            while True:
                await asyncio.sleep(3600)
