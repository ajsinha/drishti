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

"""Admin → Data loads → Inbox in the console (DATALOAD_INBOX.md section 10, mock-up A): the board drawn from the server's status (every
figure the server's words), its partial for the page's own refresh, Load now and Retry passed to the server with its answers, the report
link, the Data loads tabs, and the page gone with the engine."""
from urllib.parse import unquote

from core.backend import BackendError

RUN = "20261103T150000Z-drishti-main"
QUIET = {
    "on": True, "owner": True, "node": "drishti-main", "dir": "/srv/drishti/data/dataload", "interval": "PT10M", "zone": "Z",
    "nextCycleAt": "2026-11-03T14:50:00Z", "nextCycleText": "14:50 (in 3 min 40 s)", "ownerText": "drishti-main (this node)", "lockText": "free",
    "running": None, "lock": None, "markersWithoutFile": [], "readyAll": False, "waiting": [], "failed": [], "ledger": [], "cycles": [],
    "board": {
        "waitingCount": 3, "waitingText": None, "failedTotal": 0, "failures": [], "duplicates": [], "duplicatesCount": 0,
        "failedText": "No failed files in the last 30 days (keep-outbox-days).",
        "waiting": [
            {"file": "trades-endur-2026-11-03.jsonl.gz", "size": 432013312, "sizeText": "412 MB", "code": "ready", "label": "ready",
             "detail": "stable 4 min · marker empty", "next": "taken at the next cycle, 14:50",
             "rule": {"n": 1, "text": "rule 1 → trading / trade · trade-endur · 2026-11-03 from the name"}},
            {"file": "scenario-pv-2026-11-03-b.jsonl.gz", "size": 1932735283, "sizeText": "1.8 GB", "code": "not-stable", "label": "not yet stable",
             "detail": "changed 9 s ago · marker present", "next": "taken once unchanged for 30 s (stable-seconds)",
             "rule": {"n": 2, "text": "rule 2 → market-risk / scenario-pv · the pack's · each row's BUSINESS_DATE"}},
            {"file": "positions-2026-11-03.jsonl", "size": 23068672, "sizeText": "22 MB", "code": "no-marker", "label": "no marker", "detail": "since 13:58",
             "next": "never taken without positions-2026-11-03.jsonl.ready or ready.all",
             "rule": {"error": "no routing rule matches", "text": "no rule matches this name: once ready it would go to failed/ (never guessed)"}},
        ]},
    "lastCycles": [
        {"time": "14:40", "run": "20261103T144000Z-drishti-main", "outcome": "loaded", "label": "loaded", "todayCount": 2, "skippedToday": 0,
         "text": "3 files · 2 tables · 2026-11-02, 2026-11-03 · 1,284,551 rows · 0 rejects · 1 min 12 s",
         "versionsText": "trading/trade v416 · market-risk/scenario-pv v88, v89"},
        {"time": "14:30", "run": None, "outcome": "nothing", "label": "nothing ready", "text": None}],
    "rules": {"source": "config/dataload.yaml", "unmatched": "fail", "unmatchedText": "anything else: unmatched: fail, to failed/ with the reason, never guessed",
              "note": "Read-only, from dataload.yaml (read again every cycle).",
              "rules": [{"n": 1, "match": "trades-endur-*.jsonl.gz", "pack": "trading", "kind": "trade", "table": "trade-endur", "date": "from the name"},
                        {"n": 2, "match": "scenario-pv-*.jsonl.gz", "pack": "market-risk", "kind": "scenario-pv", "table": "the pack's",
                         "date": "each row's BUSINESS_DATE"}]},
    "settings": {"text": "interval 10m · stable-seconds 30 · loader-heap 4g · load-timeout 2h · reject-max-percent 0 · max-files off · max-gb off · "
                         "reload-duplicates false · keep-outbox-days 30"},
    "folders": {"outbox": "outbox/2026-11-03/20261103T144000Z-drishti-main/", "failed": "failed/2026-11-03/", "ledger": "ledger/ledger-2026-11.jsonl"},
}
FAILED_REPORT = "failed/2026-11-03/20261103T145000Z-drishti-main/scenario-pv-2026-11-02-eod.jsonl.gz.failed.json"
BUSY = {
    **QUIET,
    "nextCycleText": "15:30 (in 6 min 50 s)",
    "lockText": f"held by drishti-main (this node), run {RUN}, heartbeat 4 s ago",
    "running": {"run": RUN, "startedAt": "2026-11-03T15:00:00Z", "elapsedText": "23 min 10 s", "folder": f"inprocess/{RUN}/", "fileCount": 4,
                "sizeText": "7.0 GB", "tableCount": 2, "tableText": "table 2 of 2", "heap": "4g", "nice": True,
                "summaryText": "started 15:00:00 · running 23 min 10 s · 4 files, 7.0 GB · load-timeout 2h (a load stops at 17:00)",
                "note": "The pack signals go out when the run ends (connectors refresh, checks, alerts, notices).",
                "tables": [
                    {"n": 1, "state": "committed", "position": "committed 1 of 2", "name": "trading / trade", "timeText": "2 min 40 s",
                     "files": [{"file": "trades-endur-2026-11-03.jsonl.gz", "sizeText": "300 MB"}], "detail": "2026-11-03 → v418 · 96,410 rows · 0 rejects"},
                    {"n": 2, "state": "loading", "position": "loading 2 of 2", "name": "market-risk / scenario-pv", "timeText": "20 min 30 s",
                     "files": [{"file": "scenario-pv-2026-11-03.tar.gz", "sizeText": "6.2 GB"}], "detail": "the loader is writing"}]},
    "board": {**QUIET["board"], "readyAll": {"sinceText": "since 15:12", "text": "every data file here is ready at the next cycle"},
              "failedTotal": 1, "failedText": "1, needs a person",
              "failures": [{"file": "scenario-pv-2026-11-02-eod.jsonl.gz", "day": "2026-11-03", "run": "20261103T145000Z-drishti-main",
                            "reason": "3,212 of 229,400 rows (1.4 %) rejected (no BUSINESS_DATE), over reject-max-percent 0. Nothing committed (loader exit 1).",
                            "report": FAILED_REPORT, "atText": "2026-11-03 14:50", "pack": "market-risk", "kind": "scenario-pv",
                            "retryText": "Retry puts the file and its marker back in the inbox; the next cycle or Load now loads it. Fix the file or the rules first."}],
              "duplicates": [{"file": "trades-endur-2026-11-02.jsonl.gz", "run": "20261103T145000Z-drishti-main",
                              "text": "run 20261103T145000Z-drishti-main · moved to the outbox, not loaded again",
                              "reason": "already loaded in run 20261102T221000Z-drishti-main on 2026-11-02T22:10:00Z (the same SHA-256): not loaded again"}],
              "duplicatesCount": 1},
    "lastCycles": [
        {"time": "15:00", "run": RUN, "outcome": "loading", "label": "loading", "text": "see Loading · 23 min 10 s", "todayCount": 4, "skippedToday": 2},
        {"time": "15:20", "run": None, "outcome": "skipped", "label": "skipped", "text": f"run {RUN} still loading (20 min)"},
        {"time": "15:10", "run": None, "outcome": "skipped", "label": "skipped", "text": f"run {RUN} still loading (10 min)"},
        {"time": "14:50", "run": "20261103T145000Z-drishti-main", "outcome": "failed-files", "label": "1 failed",
         "text": "3 files: 1 duplicate, 1 failed, 1 loaded · trading/trade · 2026-11-03 · 48,213 rows · 0 rejects · 46 s"}],
}
SKIPPED = f"skipped: run {RUN} still loading (23 min)"


def with_inbox(backend, monkeypatch, calls, status=None, run_answer=None):
    async def admin(method, path, ident, body=None, timeout=None, **params):
        calls.append((method, path, body, params))
        if path == "/dataload" and method == "GET":
            if status is None:
                raise BackendError(404, "DRS-5011", "no such endpoint")
            return status
        if path == "/dataload/run":
            return run_answer or {"started": True, "by": "alice"}
        if path == "/dataload/retry":
            if body.get("file") == "nope.jsonl":
                raise BackendError(404, "DRS-5011", "no failed file is named nope.jsonl")
            return {"file": body["file"], "from": "failed/2026-11-03/r/" + body["file"], "to": "inbox/" + body["file"],
                    "next": "the next cycle loads it (or Load now)"}
        if path.startswith("/dataload/report?path="):
            return {"file": "x", "reason": "rejected", "path": unquote(path.split("=", 1)[1])}
        if path == "/packs":
            return []
        return {}
    monkeypatch.setattr(backend, "admin", admin)


def test_the_quiet_board_shows_the_three_columns_rules_and_settings_in_the_servers_words(client, backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], QUIET)
    page = client.get("/admin/loads/inbox").text
    for text in ("Data loads", "Inbox", "Load a file into a lake", "Waiting", "Loading", "Last cycles", "inbox/", "inprocess/", "outbox/2026-11-03/",
                 "drishti-main (this node)", "14:50 (in 3 min 40 s)", "Load now", "412 MB", "1.8 GB", "not yet stable", "no marker",
                 "rule 1 → trading / trade · trade-endur · 2026-11-03 from the name", "no rule matches this name", "Nothing loading: inprocess/ is empty",
                 "1,284,551 rows", "trading/trade v416", "nothing ready", "No failed files in the last 30 days", "Routing rules in effect", "read-only",
                 "trades-endur-*.jsonl.gz", "anything else: unmatched: fail", "reload-duplicates false", "ledger/ledger-2026-11.jsonl",
                 "admin-inbox.js", "admin-inbox.css", 'data-running="0"', 'aria-current="page">Inbox'):
        assert text in page, text
    assert 'style="' not in page.split("data-inbox-body", 1)[1]                 # CSP: no inline styles on the board
    assert 'scope="col"' in page and "<caption" in page


def test_the_busy_board_shows_the_run_table_by_table_the_failure_with_retry_and_the_duplicates(client, backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], BUSY)
    page = client.get("/admin/loads/inbox").text
    for text in (f"inprocess/{RUN}/", "23 min 10 s · table 2 of 2", "committed 1 of 2", "loading 2 of 2", "market-risk / scenario-pv",
                 "2026-11-03 → v418 · 96,410 rows · 0 rejects", "the loader is writing", "4 files, 7.0 GB", "heap 4g", "ready.all", "since 15:12",
                 "2 skipped", f"run {RUN} still loading (20 min)", "1 failed", "1, needs a person", "3,212 of 229,400 rows",
                 'data-retry="scenario-pv-2026-11-02-eod.jsonl.gz"', "/admin/api/dataload/report?path=failed/2026-11-03/", "Duplicates today",
                 "already loaded in run 20261102T221000Z-drishti-main", f"held by drishti-main (this node), run {RUN}", 'data-running="1"',
                 'data-inbox-failed-badge>1 failed'):
        assert text in page, text


def test_the_partial_is_the_board_alone(client, backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], BUSY)
    part = client.get("/admin/loads/inbox?partial=1").text
    assert "data-inbox-board" in part and "loading 2 of 2" in part
    assert "<html" not in part and "adm-tabs" not in part and "data-inbox-said" not in part


def test_load_now_and_retry_pass_the_servers_answers(client, backend, monkeypatch):
    calls = []
    with_inbox(backend, monkeypatch, calls, BUSY, run_answer={"started": False, "skipped": SKIPPED, "by": "alice"})
    r = client.post("/admin/api/dataload/run", json={})
    assert r.status_code == 200 and r.json() == {"started": False, "skipped": SKIPPED, "by": "alice"}
    assert ("POST", "/dataload/run", {}, {}) in calls
    r = client.post("/admin/api/dataload/retry", json={"file": "scenario-pv-2026-11-02-eod.jsonl.gz"})
    assert r.status_code == 200 and r.json()["to"] == "inbox/scenario-pv-2026-11-02-eod.jsonl.gz"
    assert ("POST", "/dataload/retry", {"file": "scenario-pv-2026-11-02-eod.jsonl.gz"}, {}) in calls
    r = client.post("/admin/api/dataload/retry", json={"file": "nope.jsonl"})
    assert r.status_code == 404 and "no failed file is named nope.jsonl" in r.json()["detail"]
    r = client.post("/admin/api/dataload/retry", json={})
    assert r.status_code == 400


def test_the_report_link_is_passed_to_the_server(client, backend, monkeypatch):
    calls = []
    with_inbox(backend, monkeypatch, calls, BUSY)
    r = client.get("/admin/api/dataload/report", params={"path": FAILED_REPORT})
    assert r.status_code == 200 and r.json()["path"] == FAILED_REPORT
    assert ("GET", "/dataload/report?path=" + FAILED_REPORT, None, {}) in calls


def test_a_server_without_the_inbox_is_a_clear_line_not_a_crash(client, backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], None)
    r = client.get("/admin/loads/inbox")
    assert r.status_code == 200 and "data-inbox-error" in r.text and "Engines" in r.text


def test_the_loads_page_has_the_tabs(client, backend, monkeypatch):
    with_inbox(backend, monkeypatch, [], BUSY)
    page = client.get("/admin/loads").text
    assert 'href="/admin/loads/inbox"' in page and "data-inbox-failed-badge" in page and 'aria-current="page">Loads' in page
