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

"""DATA-21 (QA 2026-10-01): an entity whose id holds a "/" opens through the console and the clients. The server refuses
"/" percent-encoded in a path (SEC-02), so they ask with "~" in the id's place and the id in the query."""
import asyncio
import sys
from pathlib import Path

import httpx

from core.backend import BackendClient, entity_path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "clients" / "python"))
from drishti_client import _entity  # noqa: E402


def test_an_id_a_path_cannot_carry_goes_in_the_query():
    assert entity_path("trade", "MX-1") == ("trade/MX-1", {})
    assert entity_path("trade", "a b:c") == ("trade/a%20b%3Ac", {})
    assert entity_path("trade", "sl/ash-6") == ("trade/~", {"id": "sl/ash-6"})
    assert entity_path("trade", "a\\b") == ("trade/~", {"id": "a\\b"})
    assert entity_path("trade", "..") == ("trade/~", {"id": ".."})
    assert _entity("trade", "sl/ash-6") == ("trade/~", {"id": "sl/ash-6"})
    assert _entity("trade", "MX-1") == ("trade/MX-1", {})


def test_the_console_asks_the_server_with_the_id_in_the_query():
    seen = []

    def answer(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.raw_path.decode(), dict(request.url.params)))
        return httpx.Response(200, json={"ref": {"kind": "trade", "id": request.url.params.get("id")}})

    async def run():
        b = BackendClient("http://server")
        await b._client.aclose()
        b._client = httpx.AsyncClient(base_url="http://server", transport=httpx.MockTransport(answer))
        await b.raw("trade", "sl/ash-6")
        await b.view("trade", "sl/ash-6", None)
        await b.series("trade", "sl/ash-6", "mtm", 5)
        await b.impact("trade", "sl/ash-6")
        await b.notes("trade", "sl/ash-6", None)
        await b.panel_records("trade", "sl/ash-6", "legs", None)
        await b.raw("trade", "MX-1")
        await b.aclose()

    asyncio.run(run())
    paths = [p.split("?")[0] for p, _ in seen]
    assert paths[:6] == ["/api/v1/entities/trade/~/raw", "/api/v1/views/trade/~", "/api/v1/history/trade/~/series", "/api/v1/impact/trade/~",
                         "/api/v1/notes/trade/~", "/api/v1/views/trade/~/panels/legs/records"]
    assert all(q.get("id") == "sl/ash-6" for _, q in seen[:6])
    assert paths[6] == "/api/v1/entities/trade/MX-1/raw" and "id" not in seen[6][1]


def test_console_pages_and_calls_take_an_id_with_a_slash(client, backend):
    r = client.get("/api/raw/trade/sl%2Fash-6")
    assert r.status_code == 200 and r.json()["ref"]["id"] == "sl/ash-6"
    backend.calls.clear()
    client.get("/v/trade/sl%2Fash-6")
    assert ("view", "trade", "sl/ash-6") in [c[:3] for c in backend.calls]
