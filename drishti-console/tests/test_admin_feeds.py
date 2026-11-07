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

"""Admin → Data loads → Feeds in the console (docs/guides/FEEDS_GUIDE.md section 6): every feed drawn from the server's
GET /api/v1/admin/feeds (last run, last success, rows, duration, failures, the last error, the licence), the latest runs, the Feeds tab with
its failing badge, and Run now passed to the server with its answers."""
from core.backend import BackendError

STATUS = {
    "ledger": "/srv/drishti/data/feeds/ledger.jsonl", "config": "./config/feeds", "failing": 1,
    "feeds": [
        {"feed": "gleif", "title": "GLEIF legal entities", "lake": "referencelake", "load": "java", "enabled": True, "schedule": "daily 05:30",
         "licence": "CC0 1.0", "licenceUrl": "https://www.gleif.org/en/meta/lei-data-terms-of-use", "internalOnly": False,
         "lastRun": "2026-11-07T05:30:12-05:00", "lastStatus": "failed", "lastExit": 1, "lastTrigger": "schedule", "seconds": 9.0,
         "lastSuccess": "2026-11-06T05:30:41-05:00", "rows": 4377, "businessDate": "2026-11-06", "failuresSinceSuccess": 1, "failed": True,
         "lastError": "1 request(s) failed after retries", "running": False},
        {"feed": "world-economy", "title": "World economy", "lake": "worldlake", "load": "java", "enabled": False, "schedule": "daily 06:45",
         "licence": "IMF terms", "licenceUrl": "", "internalOnly": True, "lastRun": None, "lastStatus": None, "rows": None, "seconds": None,
         "lastSuccess": None, "businessDate": None, "failuresSinceSuccess": 0, "failed": False, "lastError": None, "running": False}],
    "runs": [{"at": "2026-11-07T05:30:12-05:00", "feed": "gleif", "status": "failed", "exit": 1, "businessDate": "2026-11-07", "rows": 0,
              "load": None, "seconds": 9.0, "trigger": "schedule", "error": "1 request(s) failed after retries", "run": "r2"},
             {"at": "2026-11-06T05:30:41-05:00", "feed": "gleif", "status": "ok", "exit": 0, "businessDate": "2026-11-06", "rows": 4377,
              "load": {"route": "java"}, "seconds": 41.2, "trigger": "schedule", "run": "r1"}],
}


def with_feeds(backend, monkeypatch, calls, status=STATUS, run_answer=None):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body))
        if path.startswith("/feeds?") and method == "GET":
            if status is None:
                raise BackendError(500, "DRS-5001", "the feeds could not be read")
            return status
        if path == "/feeds/gleif/run":
            return run_answer or {"feed": "gleif", "started": True, "pid": 4242, "log": "/srv/drishti/data/feeds/gleif/admin-run.log", "by": "alice"}
        if path == "/feeds/nope/run":
            raise BackendError(404, "DRS-1001", "no data feed 'nope' in ./config/feeds")
        if path == "/dataload":
            raise BackendError(404, "DRS-5011", "no such endpoint")
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_feeds_page_shows_each_feed_and_the_latest_runs_in_the_servers_words(client, backend, monkeypatch):
    with_feeds(backend, monkeypatch, [])
    page = client.get("/admin/loads/feeds").text
    for text in ("Data loads", 'aria-current="page">Feeds', "data-feeds-failing-badge>1 failing", "gleif", "referencelake", "daily 05:30",
                 "2026-11-07T05:30:12", "failed", "1 request(s) failed after retries", "2026-11-06T05:30:41", "4,377", "CC0 1.0",
                 "https://www.gleif.org/en/meta/lei-data-terms-of-use", "world-economy", "internal only", 'data-feed-run="gleif"', "Run now",
                 "Latest runs", "/srv/drishti/data/feeds/ledger.jsonl", "data-feed-failed", "drishti_admin-feeds.js", 'scope="col"', "<caption"):
        assert text in page, text
    assert 'style="' not in page.split("data-feeds", 1)[1]                     # CSP: no inline styles


def test_the_page_says_when_the_server_cannot_give_the_feeds(client, backend, monkeypatch):
    with_feeds(backend, monkeypatch, [], status=None)
    page = client.get("/admin/loads/feeds").text
    assert "The server did not give the feeds" in page and "the feeds could not be read" in page


def test_run_now_is_passed_to_the_server_and_its_answers_come_back(client, backend, monkeypatch):
    calls = []
    with_feeds(backend, monkeypatch, calls)
    r = client.post("/admin/api/feeds/gleif/run", json={})
    assert r.status_code == 200 and r.json()["started"] is True and r.json()["pid"] == 4242
    assert ("POST", "/feeds/gleif/run", {}) in calls
    r = client.post("/admin/api/feeds/nope/run", json={})
    assert r.status_code == 404 and "no data feed" in r.text
    r = client.post("/admin/api/feeds/Bad_Name/run", json={})
    assert r.status_code == 400


def test_run_now_while_a_run_holds_the_lock_says_so(client, backend, monkeypatch):
    with_feeds(backend, monkeypatch, [], run_answer={"feed": "gleif", "started": False, "skipped": "a run of gleif is in progress"})
    r = client.post("/admin/api/feeds/gleif/run", json={})
    assert r.json() == {"feed": "gleif", "started": False, "skipped": "a run of gleif is in progress"}


def test_the_feeds_tab_is_on_every_data_loads_page(client, backend, monkeypatch):
    with_feeds(backend, monkeypatch, [])
    page = client.get("/admin/loads/feeds").text
    assert 'href="/admin/loads/feeds"' in page and 'href="/admin/lakes"' in page
