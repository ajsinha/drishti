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

"""Busy protection in the console (docs/admin/OPERATIONS.md, "Busy protection"; mock-up A). The server decides; the console passes its busy
refusal on unchanged (503, Retry-After, the calm words) to the page that asked, which counts down in place: the sign-in form, a VaR or
exposure panel, Export data's Download card. Admin -> Health draws the "Busy protection" card and sends the operator's override."""
from __future__ import annotations

from fastapi.testclient import TestClient

from core.backend import BackendError
from tests.test_sessions import FORM, _app
import engine_fakes  # noqa: E402


def _busy(code="DRS-9503", title="The download did not start", work="download", wait="30"):
    because = "the server is short of memory: 93% in use after clean-up (new heavy work waits over 85%)"
    e = BackendError(503, code, f"{code} {title}: {because}. Try again in {wait} seconds.")
    e.extra = {"code": code, "busy": True, "title": title, "because": because, "work": work, "retryAfterSeconds": int(wait)}
    e.retry_after = wait
    return e


def test_the_json_routes_pass_a_busy_refusal_on_with_its_retry_after_and_words():
    from core.busy import fields, is_busy, problem

    e = _busy("DRS-9502", "2026-10-01 cannot be opened just yet", "load", "20")
    assert is_busy(e) and fields(e)["retryAfterSeconds"] == 20 and fields(e)["work"] == "load"
    r = problem(e, 503)
    assert r.status_code == 503 and r.headers["retry-after"] == "20"
    assert b'"busy":true' in r.body and b'"title":"2026-10-01 cannot be opened just yet"' in r.body
    other = BackendError(503, "DRS-5218", "not enough free disk")
    assert not is_busy(other) and problem(other, 503).headers.get("retry-after") is None


def test_a_var_panel_gets_the_busy_refusal_with_retry_after(client, backend, monkeypatch):
    async def var(*a, **k):
        raise _busy("DRS-9502", "2026-10-01 cannot be opened just yet", "load", "20")
    engine_fakes.patch(monkeypatch, backend, var=var)
    r = client.get("/api/var/scope", params={"scope": "DESK:DESK-COMM", "asOf": "2026-10-01"})
    assert r.status_code == 503 and r.headers["retry-after"] == "20" and r.json()["busy"] is True and r.json()["code"] == "DRS-9502"


def test_sign_in_waits_in_place_and_keeps_what_was_typed():
    app, fake = _app()

    async def login(username, password, service):
        raise _busy("DRS-9501", "The server is very busy right now", "sign-in", "45")
    fake.login = login
    c = TestClient(app)
    r = c.post("/login", content="user=k.mehta&password=x&next=/t", headers={**FORM, "X-Drishti-Busy": "1"}, follow_redirects=False)
    assert r.status_code == 503 and r.headers["retry-after"] == "45"
    assert r.json()["code"] == "DRS-9501" and r.json()["title"] == "The server is very busy right now"
    page = c.post("/login", content="user=k.mehta&password=x&next=/t", headers=FORM, follow_redirects=False)
    assert page.status_code == 503 and page.headers["retry-after"] == "45"
    assert "data-busy=" in page.text and "DRS-9501" in page.text and 'value="k.mehta"' in page.text
    assert "/static/js/drishti_login-busy.js" in page.text and "/static/js/drishti_busy.js" in page.text
    assert "Unknown user or wrong password" not in page.text


def test_a_download_that_must_wait_comes_back_to_its_card_with_the_countdown(client, backend, monkeypatch):
    from tests.test_data_view import _fake

    _fake(backend, monkeypatch)

    async def open_download(path, ident, read_timeout=None):
        raise _busy()
    monkeypatch.setattr(backend, "open_download", open_download, raising=False)
    r = client.get("/data/trade-valuation/download", params={"date": "2026-09-30", "columns": "TRADE_ID,MTM", "tries": "1"},
                   follow_redirects=False)
    assert r.status_code == 303
    where = r.headers["location"]
    assert "busy=DRS-9503" in where and "wait=30" in where and "tries=1" in where and "error=" not in where
    page = client.get(where).text
    assert "data-busy-host data-busy=" in page and "DRS-9503" in page and "The download did not start" in page
    assert 'role="alert" data-error' not in page                     # calm, not an error


HEALTH_BUSY = {"enabled": True, "state": "new heavy work waits", "waitingSince": "2026-11-02T10:39:51Z",
               "override": {"mode": "automatic", "maxMinutes": 240},
               "gauges": [{"id": "memory", "label": "Memory in use after clean-up", "value": 0.93, "warn": 0.8, "refuse": 0.85, "state": "wait",
                           "words": "new work waits", "usedBytes": 6979321856, "maxBytes": 7516192768, "refuseSignIn": 0.92, "note": "warn at 80%"},
                          {"id": "loads", "label": "Loads running", "value": 1.0, "warn": 0.6, "refuse": 1.0, "state": "wait",
                           "words": "new work waits", "running": 3, "slots": 3, "queued": 2, "engines": [{"engine": "var", "running": 3, "threads": 3}],
                           "openHeap": [], "note": "dates being opened"},
                          {"id": "requests", "label": "Request handlers busy", "value": 0.82, "warn": 0.75, "refuse": 0.9, "state": "warn",
                           "words": "getting full", "busy": 164, "handlers": 200, "queue": 6, "note": "warn at 75%"},
                          {"id": "disk", "label": "Disk used", "value": 0.92, "warn": 0.96, "refuse": 0.98, "state": "ok", "words": "fine",
                           "freeBytes": 44023414784, "totalBytes": 536870912000, "note": "warn under 20 GB free"},
                          # a memory store of the in-memory lake (its use against its own cap; no free or total disk bytes)
                          {"id": "memory-store:news", "label": "Memory store news (off the heap)", "value": 0.004, "warn": 0.85, "refuse": 1.0,
                           "state": "ok", "words": "fine", "usedBytes": 1048576, "maxBytes": 268435456, "note": "held in memory only"}],
               "refusals": {"last15Minutes": 23, "people": 4, "sinceStart": 40, "rows": [
                   {"at": "2026-11-02T10:42:18Z", "who": "k.mehta", "what": "Sign in", "why": "memory 93%", "code": "DRS-9501", "retryAfterSeconds": 45}]}}


def test_health_draws_the_busy_protection_card_and_sends_the_override(client, backend, monkeypatch):
    sent = []
    health = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
              "server": {"version": "dev", "uptimeSeconds": 60, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
              "sources": [], "packs": [], "live": {}, "tmp": {}, "busy": HEALTH_BUSY}

    async def admin(method, path, ident, body=None, **params):
        if path == "/busy/override":
            sent.append((method, body))
            return {**HEALTH_BUSY, "override": {"mode": body["mode"], "reason": body["reason"], "until": "2026-11-02T11:12:00Z"}}
        return {"connectors": []} if path == "/connectors" else health
    monkeypatch.setattr(backend, "admin", admin)
    page = client.get("/admin/health").text
    assert "data-health-busy" in page and "Busy protection" in page and "new heavy work waits" in page
    assert 'data-busy-gauge="memory"' in page and "93% · 6.5 of 7.0 GB" in page and "3 of 3 slots" in page and "164 of 200 · queue 6" in page
    assert 'data-busy-gauge="memory-store:news"' in page and "0% · 1.0 of 256.0 MB" in page
    assert "k.mehta" in page and "DRS-9501" in page and "23 in the last 15 minutes" in page
    assert "data-busy-override" in page and "Hold all new heavy work" in page and "/static/js/drishti_admin-busy.js" in page
    assert 'style="' not in page.split("data-health-busy", 1)[1].split("</section>", 1)[0]      # no inline style (the page's policy)
    r = client.post("/admin/api/busy/override", json={"mode": "hold", "reason": "planned restart", "minutes": "30"})
    assert r.status_code == 200 and sent == [("PUT", {"mode": "hold", "reason": "planned restart", "minutes": 30})]


def test_health_before_the_first_clean_up_says_the_memory_is_not_measured_yet(client, backend, monkeypatch):
    """The server sends usedBytes null until the first garbage collection after start: the page says so instead of failing."""
    first = {**HEALTH_BUSY, "gauges": [{**HEALTH_BUSY["gauges"][0], "value": 0.0, "usedBytes": None}]}
    health = {"status": "OK", "summary": {"sources": 0, "sourcesDown": 0, "failedToStart": 0, "packs": 0, "packsWithProblems": 0},
              "server": {"version": "dev", "uptimeSeconds": 5, "java": "21", "heapUsedMb": 300, "heapMaxMb": 4000, "threads": 60, "cpus": 8},
              "sources": [], "packs": [], "live": {}, "tmp": {}, "busy": first}

    async def admin(method, path, ident, body=None, **params):
        return {"connectors": []} if path == "/connectors" else health
    monkeypatch.setattr(backend, "admin", admin)
    r = client.get("/admin/health")
    assert r.status_code == 200 and 'data-busy-gauge="memory"' in r.text and "not measured yet" in r.text
