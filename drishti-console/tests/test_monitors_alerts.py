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

"""Monitors and alerts in the console."""


def test_monitor_starters_rows_and_stream(client, backend):
    index = client.get("/m").text
    assert "Credit watch" in index and "Fleet watch" in index
    r = client.get("/m/Watch", params={"template": "Credit watch"})
    assert r.status_code == 200 and 'data-row="netting-set/NS-NORTH-01"' in r.text and "50,000,000" in r.text
    assert "Watch" in client.get("/m").text
    with client.stream("GET", "/api/monitor-stream/Watch") as s:
        body = "".join(s.iter_text())
    assert "event: row" in body
    assert client.post("/m/api/Watch/delete").json()["ok"]
    assert client.get("/m/Gone").status_code == 404


def test_alerts_page_rules_and_bell(client, backend):
    page = client.get("/alerts", params={"kind": "netting-set", "id": "NS-NORTH-01"})
    assert page.status_code == 200 and "PFE near limit" in page.text and 'value="NS-NORTH-01"' in page.text
    assert "Delayed more than 12 h" not in page.text
    ok = client.post("/alerts/api/Near limit", json={"kind": "netting-set", "id": "NS-NORTH-01", "when": "$.utilisation > 0.8"})
    assert ok.status_code == 200 and "Near limit" in client.get("/alerts").text
    bad = client.post("/alerts/api/Bad", json={"kind": "trade", "id": "IRS-48213", "when": "$.mtm <"})
    assert bad.status_code == 422 and bad.json()["code"] == "DRS-2101"
    view = client.get("/v/trade/IRS-48213").text
    assert "data-bell" in view and "/static/js/alerts.js" in view and "/alerts?kind=trade&id=IRS-48213" in view
    with client.stream("GET", "/api/alerts/stream") as s:
        assert "event: alert" in "".join(s.iter_text())
