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

"""Admin -> Health: the platform alerts the server evaluates (OPERATIONS.md section 13.6) and the console's live-channel relay."""
import re


def _flat(page: str) -> str:
    return " ".join(re.sub(r"<[^>]+>", "", page).split())


def _health(client, backend, monkeypatch, alerts):
    data = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
            "server": {"version": "dev", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
            "sources": [], "packs": [], "live": {}, "tmp": {}}
    if alerts is not None:
        data["alerts"] = alerts

    async def admin(method, path, ident, body=None, **params):
        return {"connectors": []} if path == "/connectors" else data
    monkeypatch.setattr(backend, "admin", admin)
    return client.get("/admin/health").text


def test_the_open_platform_alerts_are_listed_most_severe_first(client, backend, monkeypatch):
    page = _health(client, backend, monkeypatch, {"enabled": True, "rules": 17, "found": True, "problems": [], "open": [
        {"rule": "disk-free-under-guard", "tags": {}, "severity": "critical", "summary": "free disk is under the guard: new dates are refused (DRS-5218)",
         "value": 0.8, "threshold": 1, "member": "drishti-node2", "openedAt": "2026-10-31T09:14:00Z", "closedAt": None},
        {"rule": "source-stale", "tags": {"source": "rates"}, "severity": "warn", "summary": "source rates is behind its stale-after",
         "value": 1, "threshold": 0, "member": "drishti-node2", "openedAt": "2026-10-31T09:20:00Z", "closedAt": None}]})
    flat = _flat(page)
    assert 'id="alerts"' in page and "data-health-alerts" in page                     # the inbox notice opens /admin/health#alerts
    assert "Alerts 2 open · 17 rules" in flat
    assert 'data-alert-open="disk-free-under-guard"' in page and "free disk is under the guard" in flat
    assert "(critical · disk-free-under-guard · drishti-node2 · since 2026-10-31 09:14:00 UTC)" in flat
    assert re.search(r'class="t-neg" data-alert-open="disk-free-under-guard"', page)
    assert re.search(r'class="t-warn" data-alert-open="source-stale"', page)


def test_no_rules_file_is_said_and_a_server_without_alerts_shows_no_section(client, backend, monkeypatch):
    page = _health(client, backend, monkeypatch, {"enabled": True, "rules": 0, "found": False, "problems": ["x.yaml: bad"], "open": []})
    flat = _flat(page)
    assert "Alerts none open · 0 rules" in flat and "data-alerts-no-file" in page and "x.yaml: bad" in flat
    assert "data-health-alerts" not in _health(client, backend, monkeypatch, None)


def test_the_relay_of_the_worker_is_shown(client, backend, monkeypatch):
    from core import channel_relay

    monkeypatch.setitem(channel_relay._state, "port", 17490)
    monkeypatch.setitem(channel_relay._state, "host", "10.0.4.11")
    flat = _flat(_health(client, backend, monkeypatch, None))
    assert "relay 10.0.4.11:17490 (across hosts) ·" in flat and "passed on" in flat and "refused" in flat
    monkeypatch.setitem(channel_relay._state, "port", 0)
    assert "data-console-relay" not in _health(client, backend, monkeypatch, None)
