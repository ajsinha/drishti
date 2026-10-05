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

"""QA 2026-10-01 GRAM-08 and GRAM-09: Studio preview requests the console cannot use, and business dates the server
would refuse, are answered cleanly instead of a 500 or a cookie that breaks every page for twelve hours."""
import sys
from pathlib import Path

import pytest

CONSOLE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(CONSOLE))


@pytest.mark.parametrize("body", [{"yaml": None, "kind": "trade", "id": "IRS-48213"}, [1, 2], {"yaml": 5, "kind": "trade", "id": "X"},
                                  {"yaml": "sutra: x", "kind": None, "id": "IRS-48213"}])
@pytest.mark.parametrize("path", ["/studio/preview", "/studio/test"])
def test_a_preview_body_of_the_wrong_shape_is_a_400_problem(client, path, body):
    r = client.post(path, json=body)
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "DRS-5001" and r.json()["detail"]


def test_a_preview_body_that_is_not_json_is_a_400_problem(client):
    r = client.post("/studio/preview", content=b"{not json", headers={"Content-Type": "application/json"})
    assert r.status_code == 400 and r.json()["code"] == "DRS-5001"


@pytest.mark.parametrize("d", ["9999-99-99", "2026-02-30", "2030-01-01", "1900-01-01", "garbage", "2026-09-29'<script>"])
def test_a_date_that_cannot_be_picked_is_refused_and_not_stored(client, d):
    r = client.get("/asof", params={"d": d, "next": "/v/trade/IRS-48213"}, follow_redirects=False)
    assert r.status_code == 303
    assert "drishti_asof" not in r.headers.get("set-cookie", "")
    assert r.headers["location"].startswith("/v/trade/IRS-48213?asofRefused=1")
    page = client.get(r.headers["location"]).text
    assert "asof-refused" in page and "from 2021-09-30" in page and "script>" not in page.split("asof-refused")[1][:300]


def test_a_date_the_server_accepts_is_still_stored(client):
    r = client.get("/asof", params={"d": "2026-09-26", "next": "/t"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/t" and "drishti_asof=2026-09-26" in r.headers["set-cookie"]


@pytest.mark.parametrize("cookie", ["2030-01-01", "1900-01-01", "9999-99-99"])
def test_a_stored_date_the_server_refuses_is_ignored_and_cleared(client, cookie):
    client.cookies.set("drishti_asof", cookie)
    try:
        r = client.get("/v/trade/IRS-48213")
        assert r.status_code == 200, r.text[:300]
        assert "asof-live on" in r.text                                  # the page is live, not a DRS-4003 page
        assert 'drishti_asof=""' in r.headers.get("set-cookie", "") or "drishti_asof=;" in r.headers.get("set-cookie", "")
    finally:
        client.cookies.delete("drishti_asof")
