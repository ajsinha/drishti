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

"""QA 2026-10-01 UX-12: a read that times out (the server's DRS-1004, or the console giving up waiting) is a clear 504
problem on the page, not a 502 (bad gateway); other server faults stay 502."""
import asyncio
import sys
from pathlib import Path

import httpx
import pytest

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))

from core.backend import BackendClient, BackendError  # noqa: E402


@pytest.mark.parametrize("status,expected", [(404, 404), (400, 400), (504, 504), (500, 502), (502, 502), (503, 502)])
def test_a_view_page_answers_with_a_status_that_says_what_happened(client, backend, status, expected):
    async def failing(kind, id_, user):
        raise BackendError(status, "DRS-1004" if status == 504 else "DRS-1003", "timed out reading trade/X")

    backend.view = failing
    r = client.get("/v/trade/X")
    assert r.status_code == expected


def test_the_console_giving_up_waiting_is_a_504_naming_the_timeout():
    def slow(request):
        raise httpx.ReadTimeout("read timed out", request=request)

    c = BackendClient("http://server.invalid")
    c._client = httpx.AsyncClient(base_url="http://server.invalid", transport=httpx.MockTransport(slow))
    with pytest.raises(BackendError) as e:
        asyncio.run(c._get("/entities/trade/X/raw"))
    assert e.value.status == 504 and e.value.code == "DRS-1004" and e.value.page_status == 504
    assert "did not answer in time" in e.value.detail


def test_an_unreachable_server_is_still_a_502_page():
    def refused(request):
        raise httpx.ConnectError("refused", request=request)

    c = BackendClient("http://server.invalid")
    c._client = httpx.AsyncClient(base_url="http://server.invalid", transport=httpx.MockTransport(refused))
    with pytest.raises(BackendError) as e:
        asyncio.run(c._get("/entities/trade/X/raw"))
    assert e.value.status == 503 and e.value.page_status == 502
