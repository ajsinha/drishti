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

"""A stand-in server for BI runs (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), sections 6, 7, 14): synthetic data only. It answers the endpoints of section 14.1 with
the run object of 14.3, moves a run queued -> running (progress 0 to 1, a phase) -> done on a clock, publishes every change on
``/bi/runs/events`` (and a ``bi-run`` notice on the bell's stream when a visible run ends), and answers a report visual's question with
``Prefer: respond-async`` as 200 (in time), 202 (a run), 409 DRS-9082 (confirm first) or 409 DRS-9089 (an interaction that needs a run).
Cancel, re-run (same data reuses), share (same / re-masked / recomputed / refused, a role expanded, the recipient's own view of a run shared
with them), unshare, keep, delete, promote, schedules (the subscription grammar, list, edit, remove), export (CSV, Excel), the directory the people
picker reads, the settings, the Admin view and its rollup suggestions (accept, dismiss) are there too. Used by test_bi_runs_*.py and bi_runs_shots.py."""
from __future__ import annotations

import asyncio
import copy
import itertools
import json
import re
import threading
import time

from bi_backend import BiBackend, DATASET_MODEL
from core.backend import BackendError

REPORT_ID = "file:report:desk-pnl"
DESKS = ["DESK-FX", "DESK-FI", "DESK-EQD", "DESK-RATES"]
MASK_FOR = {"bob": ["MTM"]}                  # the viewer's extra masks: a re-masked share shows the mask in this column
SHARE_MODE = {"bob": "re-masked", "carol": "recomputed", "eve": None}      # anyone else sees the same; eve may not read the model
ROLES = {"risk-team": ["dave", "erin"]}
DIRECTORY = [{"type": "user", "name": "bob", "displayName": "Bob Viewer", "desk": "DESK-FX"}, {"type": "user", "name": "carol", "displayName": "Carol Risk", "desk": "DESK-FI"},
             {"type": "user", "name": "eve", "displayName": "Eve Outside", "desk": ""}, {"type": "role", "name": "risk-team", "size": 2}]
SCHEDULE = re.compile(r"(daily|weekdays|business-days) ([01]\d|2[0-3]):[0-5]\d|weekly (mon|tue|wed|thu|fri|sat|sun) ([01]\d|2[0-3]):[0-5]\d|monthly last ([01]\d|2[0-3]):[0-5]\d( business)?")
SUGGESTIONS = [{"id": "sg-1", "model": "trading", "grain": ["desk", "month"], "filters": ["region in"], "runs": 37, "medianSeconds": 240, "status": "suggested",
                "rollup": {"name": "by_desk_month", "table": "trade", "by": ["desk", "tradeDate.month"], "measures": ["MTM", "NOTIONAL"]},
                "yaml": "rollups:\n  by_desk_month:\n    table: trade\n    by: [desk, tradeDate.month]\n    measures: [MTM, NOTIONAL]\n",
                "words": "asked 37 times this week, about 4 min each: make it a rollup refreshed at load", "decidedBy": None, "item": None},
               {"id": "sg-2", "model": "trading", "grain": ["counterparty"], "filters": [], "runs": 6, "medianSeconds": 75, "status": "suggested",
                "rollup": {"name": "by_counterparty", "table": "trade", "by": ["counterparty"], "measures": ["NOTIONAL"]},
                "yaml": "rollups:\n  by_counterparty:\n    table: trade\n    by: [counterparty]\n    measures: [NOTIONAL]\n",
                "words": "asked 6 times this week, about 1 min each", "decidedBy": None, "item": None}]

REPORT_MODEL = {"rachana": 1, "report": "desk-pnl", "version": 1, "title": "Desk P&L", "dataset": "trades-basic",
                "pages": [{"id": "overview", "title": "Overview", "visuals": [
                    {"id": "desk", "type": "slicer", "title": "Desk", "at": {"x": 0, "y": 0, "w": 3, "h": 4}, "wells": {"field": ["trade.desk"]}},
                    {"id": "mtm", "type": "card", "title": "Mark to market", "at": {"x": 3, "y": 0, "w": 3, "h": 2}, "wells": {"values": ["MTM"]}},
                    {"id": "mtm-by-desk-month", "type": "bar", "title": "MTM by desk and month", "at": {"x": 6, "y": 0, "w": 6, "h": 4},
                     "wells": {"axis": ["trade.desk"], "values": ["MTM"]}}]},
                          {"id": "notes", "title": "Notes", "visuals": [
                              {"id": "count", "type": "card", "title": "Trades", "at": {"x": 0, "y": 0, "w": 3, "h": 2}, "wells": {"values": ["MTM"]}}]}]}
REPORT = {"id": REPORT_ID, "title": "Desk P&L", "description": "Daily P&L by desk", "status": "published", "shipped": False, "sample": False, "valid": True, "problems": [],
          "report": REPORT_MODEL, "dataset": DATASET_MODEL, "mayEdit": False}
CATALOGUE = {"visuals": {"card": {"group": "card", "wells": {"values": {"kind": "measure", "required": True, "max": 1}}},
                         "bar": {"group": "chart", "wells": {"axis": {"kind": "field", "required": True, "max": 1}, "values": {"kind": "measure", "required": True, "max": 0}}},
                         "slicer": {"group": "slicer", "wells": {"field": {"kind": "field", "required": True, "max": 1}}}},
             "reserved": ["map"], "slicerModes": ["dropdown", "list", "range", "relative-date"]}

ESTIMATE = {"readRows": 1840000000, "buildRows": 410000000, "bytes": 62000000000, "rowsOut": 4320, "seconds": {"low": 150, "high": 330},
            "basis": "delta-stats", "dates": 182, "datesToBuild": 41, "covered": False,
            "why": "182 business dates of trade_endur (about 10.1 million rows a day; 41 dates not built yet); no rollup has grain desk × month"}


def answer(masked=()):
    rows = [[d, round(1_250_000.0 * (i + 1) - 400_000 * (i % 2), 2)] for i, d in enumerate(DESKS)]
    if "MTM" in masked:
        rows = [[d, None] for d, _ in rows]
    return {"columns": [{"name": "trade.desk", "type": "string", "masked": False}, {"name": "MTM", "type": "decimal", "kind": "measure", "masked": "MTM" in masked}],
            "rows": rows, "rowCount": len(rows),
            "meta": {"layer": "columns", "rollup": None, "rows": len(rows), "millis": 4.0, "cached": False, "dates": ["2026-09-30"], "tables": ["trade"],
                     "masked": list(masked), "warnings": [], "generation": 0}}


def iso(t: float | None) -> str | None:
    return None if t is None else time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


class RunsBackend(BiBackend):
    """``run_seconds``: how long a background run takes (progress moves in steps); ``user``: who the stand-in server answers as (``bob``, the viewer)."""

    def __init__(self, run_seconds: float = 4.0, seed: bool = True):
        super().__init__(("bi", "bi-author", "bi-admin"))
        self.run_seconds = run_seconds
        self.runs: dict[str, dict] = {}
        self.seq = itertools.count(1)
        self.user = "ajsinha"
        self.events: list[dict] = []             # every change, in order: the event stream sends them
        self.notices: list[dict] = []            # bi-run notices for the bell's stream
        self.asked: list[tuple] = []             # (path, body, prefer) of every asynchronous question
        self.lock = threading.Lock()
        self.cancelled_by: dict[str, str] = {}
        self.schedules: dict[str, dict] = {}
        self.suggestions = copy.deepcopy(SUGGESTIONS)
        self.snapshots: dict[str, dict] = {}     # a promoted snapshot name: its owner and newest version ("taken" belongs to someone else)
        self.promotions: list[dict] = []         # each promote body, with its run
        if seed:
            self._seed()

    # ---- the runs ------------------------------------------------------------------------------------------------------------------------------
    def _new(self, title, state="queued", source=None, runner=None, **kw):
        n = next(self.seq)
        rid = f"r-java-2-01JAC4Q8Z6T3M4R9B0X1Y{n:05d}"
        now = time.time()
        src = source or {"type": "report", "id": REPORT_ID, "title": "Desk P&L", "page": "overview", "visual": "mtm-by-desk-month"}
        if src.get("type") == "report":
            src = dict(src, href=f"/bi/r/{REPORT_ID}/{src.get('page', 'overview')}?run={rid}")
        run = {"id": rid, "title": title, "source": src, "runner": runner or self.user, "owner": runner or self.user, "workspace": "me",
               "params": {"slicers": {"DESK": ["DESK-FX", "DESK-FI"]}, "params": {"currency": "USD"}, "filters": [{"field": "PRODUCT_TYPE", "op": "!=", "values": ["FX_SPOT"]}]},
               "asOf": {"from": "2026-01-02", "to": "2026-09-30", "dates": 182},
               "data": {"version": "a91c", "words": "trade_endur delta v412 · desk_dim layers 2026-10-20 07:31",
                        "tables": [{"table": "trade_endur", "connector": "trade-endur", "versions": "v412 (182 dates)"}, {"table": "desk_dim", "connector": "reference-store", "versions": "layers 2026-10-20 07:31"}]},
               "tier": "ad-hoc-heavy", "engine": "local", "class": "background", "state": state, "progress": None, "phase": None,
               "estimate": copy.deepcopy(ESTIMATE), "actual": {"rowsRead": None, "rowsOut": None, "bytesOut": None, "seconds": None},
               "code": None, "reason": None, "node": "java-2", "attempt": 1, "submittedAt": iso(now), "startedAt": None, "finishedAt": None, "expiresAt": None,
               "reuse": None, "shared": {"from": None, "with": []}, "group": None, "rerunOf": None, "promotedTo": None,
               "links": {"self": f"/api/v1/bi/runs/{rid}", "result": f"/api/v1/bi/runs/{rid}/result", "events": "/api/v1/bi/runs/events"},
               "_t0": now, "_visible": True}
        run.update(kw)
        self.runs[rid] = run
        return run

    def _seed(self):
        past = time.time() - 3600
        done = self._new("Notional by counterparty", "done", source={"type": "mq", "model": "trading", "id": "trading"})
        done.update(progress=1.0, startedAt=iso(past), finishedAt=iso(past + 41), expiresAt="2026-11-19T09:14:00Z", asOf={"from": "2026-09-30", "to": "2026-09-30", "dates": 1},
                    actual={"rowsRead": 12_400_000, "rowsOut": 1204, "bytesOut": 88_000, "seconds": 41},
                    estimate=dict(ESTIMATE, seconds={"low": 30, "high": 90}, readRows=12_400_000, dates=1, datesToBuild=0, why="one business date of trade_endur"))
        shared = self._new("VaR by book", "done", runner="anna", source={"type": "report", "id": "rpt:var", "title": "VaR", "page": "books", "visual": "var-by-book"})
        shared.update(owner=self.user, shared={"from": "anna", "with": []}, reuse={"of": done["id"], "mode": "re-masked"}, startedAt=iso(past), finishedAt=iso(past),
                      asOf={"from": "2026-09-30", "to": "2026-09-30", "dates": 1}, data=dict(done["data"], words="trade_endur delta v410"), actual={"rowsOut": 88, "seconds": None})
        failed = self._new("Raw trades 2025", "failed", source={"type": "sql", "model": "trading", "id": "trading"})
        failed.update(code="DRS-9028", reason="the run ran out of its memory budget (4 GB)", startedAt=iso(past), finishedAt=iso(past + 243), actual={"seconds": 243},
                      asOf={"from": "2025-01-02", "to": "2025-12-31", "dates": 252}, estimate=dict(ESTIMATE, seconds={"low": 120, "high": 540}))
        cancelled = self._new("Cash ladder 90 days", "cancelled")
        cancelled.update(reason="cancelled by you", startedAt=iso(past), finishedAt=iso(past + 12), actual={"seconds": 12}, asOf={"from": "2026-09-30", "to": "2026-09-30", "dates": 1},
                         estimate=dict(ESTIMATE, seconds={"low": 60, "high": 120}))
        expired = self._new("Exposure by legal entity", "expired")
        expired.update(startedAt=iso(past - 86400 * 31), finishedAt=iso(past - 86400 * 31 + 130), actual={"rowsOut": 52, "seconds": 130},
                       asOf={"from": "2026-08-29", "to": "2026-08-29", "dates": 1}, data=dict(done["data"], words="trade_endur delta v388"))
        for i, r in enumerate(list(self.runs.values())[::-1]):          # the seeded runs are older than anything asked while the stand-in runs
            r["_t0"] -= 7200 + 60 * i

    def recipient(self, run):
        """True when the current user sees the run because it was shared with them (not their own)."""
        return run["runner"] != self.user and run["owner"] != self.user and any(x["recipient"] == self.user for x in run["shared"]["with"])

    def can(self, run):
        mine = run["runner"] == self.user or run["owner"] == self.user
        active = run["state"] in ("queued", "running", "storing", "awaiting-confirm")
        has = run["state"] in ("done", "reused")
        return {"open": has, "cancel": active and mine, "rerun": mine and not active, "export": has, "share": has and mine, "promote": has and mine and run["source"].get("type") in ("mq", "sql"),
                "schedule": mine, "keep": has and mine, "delete": has and mine}

    def tick(self):
        """Moves each running run along its clock; every change becomes an event (and, at the end, a notice)."""
        with self.lock:
            now = time.time()
            for r in self.runs.values():
                if r["state"] not in ("queued", "running", "storing"):
                    continue
                t = now - r["_t0"]
                if t < 0.4:
                    st, prog, phase = "queued", None, None
                elif t < self.run_seconds:
                    frac = (t - 0.4) / max(0.1, self.run_seconds - 0.4)
                    built = min(41, int(frac * 41) + 1)
                    st, prog, phase = "running", round(frac, 2), f"building {built} of 41 dates"
                else:
                    st, prog, phase = "done", 1.0, None
                if (st, prog, phase) != (r["state"], r["progress"], r["phase"]):
                    r.update(state=st, progress=prog, phase=phase)
                    if st != "queued" and not r["startedAt"]:
                        r["startedAt"] = iso(r["_t0"] + 0.4)
                    if st == "done":
                        r.update(finishedAt=iso(now), actual={"rowsRead": 1_790_000_000, "rowsOut": len(DESKS), "bytesOut": 1_100_000, "seconds": round(now - r["_t0"])},
                                 expiresAt="2026-11-19T09:14:00Z")
                        self._notice(r, "Run finished: " + r["title"])
                    self._event(r)

    def _event(self, r):
        self.events.append({"id": r["id"], "state": r["state"], "progress": r["progress"], "phase": r["phase"], "rowsOut": (r.get("actual") or {}).get("rowsOut"), "code": r["code"]})

    def _notice(self, r, title):
        if r.get("_visible"):
            self.notices.append({"type": "bi-run", "kind": "bi", "id": r["id"], "entityId": r["id"], "title": title, "access": True})

    def view_of(self, r):
        out = {k: copy.deepcopy(v) for k, v in r.items() if not k.startswith("_")}
        out.setdefault("schedule", None)
        out["can"] = self.can(r)
        if self.recipient(r):                    # the recipient's own view: shared by the runner, nothing of the runner's other shares
            out.update(shared={"from": r["runner"], "with": []}, schedule=None)
            out["can"] = {k: k in ("open", "export") and r["state"] in ("done", "reused") for k in out["can"]}
        return out

    def sees(self, r):
        return r.get("_visible") and (r["runner"] == self.user or r["owner"] == self.user or self.recipient(r))

    # ---- the stand-in's answers ----------------------------------------------------------------------------------------------------------------------
    async def sse(self, path, ident=None, opened=None):
        if path == "/bi/runs/events":
            self.tick()
            sent = len(self.events)
            active =[self.view_of(r) for r in self.runs.values() if r["state"] in ("queued", "running", "storing", "awaiting-confirm")]
            yield "hello", json.dumps({"runs": [{"id": r["id"], "state": r["state"], "progress": r["progress"], "phase": r["phase"]} for r in active]})
            while True:
                await asyncio.sleep(0.2)
                self.tick()
                while sent < len(self.events):
                    yield "run", json.dumps(self.events[sent])
                    sent += 1
        if path == "/me/alerts/stream":
            yield "hello", "{}"
            sent = len(self.notices)
            while True:
                await asyncio.sleep(0.25)
                self.tick()
                while sent < len(self.notices):
                    yield "notice", json.dumps(self.notices[sent])
                    sent += 1
        yield "hello", "{}"
        while True:
            await asyncio.sleep(3600)

    async def _send(self, method, path, ident=None, **kw):
        if path.endswith("/inbox/count"):
            return {"unread": len(self.notices)}
        if path == "/directory":
            q = str((kw.get("params") or {}).get("q") or "").lower()
            return [e for e in DIRECTORY if q in e["name"] or q in e.get("displayName", "").lower()]
        if path == "/me/inbox":
            return [dict(n, seq=i + 1, read=False, createdAt=iso(time.time()), sender="drishti") for i, n in enumerate(self.notices)]
        return await super()._send(method, path, ident, **kw)

    async def bi_async(self, method, path, ident, body=None, prefer="", wait=None, **params):
        self.asked.append((path, copy.deepcopy(body), prefer))
        self.tick()
        body = body or {}
        if path == f"/reports/{REPORT_ID}/query" and body.get("visual") == "mtm-by-desk-month":
            if body.get("confirm") or body.get("confirmToken"):
                return 202, {"run": self.view_of(self._new("MTM by desk and month"))}
            if body.get("interaction"):
                e = BackendError(409, "DRS-9089", "this selection needs a background run")
                e.extra = {"code": "DRS-9089", "detail": "this selection needs a background run", "estimate": copy.deepcopy(ESTIMATE)}
                raise e
            r = self._new("MTM by desk and month", "awaiting-confirm")
            e = BackendError(409, "DRS-9082", "this question is ad-hoc heavy: confirm it first")
            e.extra = {"code": "DRS-9082", "detail": "confirm it first", "estimate": copy.deepcopy(ESTIMATE), "confirmToken": "ct-" + r["id"][-5:], "runId": r["id"]}
            raise e
        if path.startswith("/runs/") and path.endswith("/rerun"):
            rid = path.split("/")[2]
            old = self.runs[rid]
            data, fresh = body.get("data") or "same", bool(body.get("fresh"))

            def said(r):                              # as RunsController.rerun: the run's view with `rerunData` (same|latest) and `fresh` beside its `data`
                return dict(self.view_of(r), rerunData=data, fresh=fresh)
            if getattr(self, "at_once", False):      # done inside the inline wait (the real server's 200 {run, answer}): no row of it is kept or listed
                r = self._new(old["title"], "done", source=old["source"], _visible=False)
                r.update(rerunOf=rid, progress=1.0, startedAt=iso(time.time()), finishedAt=iso(time.time()), actual={"rowsRead": None, "rowsOut": 1, "bytesOut": None, "seconds": 0})
                return 200, {"run": said(r), "answer": answer()}
            if data == "same" and old["state"] in ("done", "reused"):
                r = self._new(old["title"], "reused", source=old["source"])
                r.update(reuse={"of": rid, "mode": "same"}, rerunOf=rid, progress=1.0, startedAt=iso(time.time()), finishedAt=iso(time.time()), actual=dict(old["actual"]))
                self._event(r)
                return 202, {"run": said(r)}
            if data == "same" and getattr(self, "reuse_of", None):          # the answer was there: given at once, with the run it was recorded as
                was = self.runs[self.reuse_of]
                r = self._new(old["title"], "reused", source=old["source"])
                r.update(reuse={"of": was["id"], "mode": "same"}, rerunOf=rid, progress=1.0, startedAt=iso(time.time()), finishedAt=iso(time.time()), actual=dict(was["actual"]))
                self._event(r)
                return 200, {"run": said(r), "answer": answer()}
            r = self._new(old["title"], source=old["source"])
            r["rerunOf"] = rid
            return 202, {"run": said(r)}
        if path.startswith("/reports/") and path.endswith("/query"):
            return 200, answer()
        return 200, await self.bi(method, path, ident, body, **params)

    async def bi_file(self, method, path, ident, body=None, wait=None):
        rid = path.split("/")[2]
        fmt = (body or {}).get("format", "csv")
        self.bi_calls.append((method, path, body, {}))
        if fmt not in ("csv", "xlsx", "jsonl"):
            raise BackendError(400, "DRS-9060", f"a run exports as csv, xlsx or jsonl, not {fmt}")
        if fmt == "jsonl":
            return b'{"DESK":"DESK-FX","MTM":1250000}\n', {"content-type": "application/jsonl", "content-disposition": f'attachment; filename="{rid}.jsonl"'}
        if fmt == "xlsx":
            return b"PK\x03\x04 stand-in workbook", {"content-type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                    "content-disposition": f'attachment; filename="{rid}.xlsx"'}
        return b"DESK,MTM\nDESK-FX,1250000\n", {"content-type": "text/csv", "content-disposition": f'attachment; filename="{rid}.csv"'}

    async def bi(self, method, path, ident, body=None, **params):
        self.bi_calls.append((method, path, body, params))
        self.tick()
        if path == f"/reports/{REPORT_ID}":
            return REPORT
        if path == "/reports/catalogue":
            return CATALOGUE
        if path == f"/reports/{REPORT_ID}/query":
            return answer()
        if path in ("/workspaces/me", "/workspaces/u-ash-0000"):
            return {"id": "u-ash-0000", "name": "My workspace", "description": "", "personal": True, "shipped": False, "owner": self.user, "reviewRequired": False, "role": "admin", "members": []}
        if path in ("/workspaces/me/items", "/workspaces/u-ash-0000/items"):
            return []
        if path == "/runs/settings":
            return {"inlineWait": "PT2S", "maxWait": "PT10S", "confirmAboveSeconds": 60, "retentionDays": 30, "eventsUrl": "/api/v1/bi/runs/events"}
        if path == "/runs":
            states = [s for s in str(params.get("state") or "").split(",") if s]
            rows = [self.view_of(r) for r in sorted(self.runs.values(), key=lambda r: -r["_t0"]) if self.sees(r)]
            if states:
                rows = [r for r in rows if r["state"] in states]
            if params.get("source"):
                rows = [r for r in rows if r["source"]["type"] == params["source"]]
            if params.get("q"):
                rows = [r for r in rows if params["q"].lower() in r["title"].lower()]
            if params.get("mine") == "false":
                rows = [r for r in rows if r["shared"]["from"]]
            if params.get("shared") == "false":
                rows = [r for r in rows if not r["shared"]["from"]]
            return {"runs": rows, "next": None, "storage": {"bytes": 1_400_000_000, "results": 48}, "nodes": {"failed": []}}
        if path == "/admin/runs":
            running = [self.view_of(r) for r in self.runs.values() if r["state"] in ("running", "storing")]
            return {"now": {"slots": {"interactive": {"used": 3, "of": 8}, "standing": {"used": 1, "cap": 2}, "background": {"used": 2, "cap": 2}}, "admissionWaitP95Ms": 12},
                    "queue": {"byClass": {"background": 5}, "byUser": {"carol": 3, "dave": 2}, "oldestSeconds": 120, "oldestUser": "carol"},
                    "running": running, "storage": {"resultsBytes": 11_200_000_000, "maxBytes": 20 * 1024 ** 3, "results": 1904, "oldest": "2026-09-21", "snapshotsBytes": 3_400_000_000, "snapshots": 12,
                                                    "topUsers": [{"user": "ajsinha", "bytes": 1_900_000_000}, {"user": "bob", "bytes": 1_700_000_000}]},
                    "accuracy": {"medianRatio": 1.08, "p10": 0.6, "p90": 1.9, "basis": "delta-stats", "unknownShare": 0.14},
                    "inline": {"inlineShare": 0.81, "jobMedianSeconds": 220}, "settings": {"file": "config/runs/runs.yaml", "inlineWait": "PT2S", "confirmAboveSeconds": 60, "retentionDays": 30},
                    "nodes": [{"node": "java-1", "alive": True}, {"node": "java-2", "alive": True}],
                    "suggestions": copy.deepcopy(self.suggestions)}
        if path.startswith("/admin/runs/suggestions/"):
            sid, verb = path.split("/")[4:6]
            s = next((x for x in self.suggestions if x["id"] == sid), None)
            if s is None:
                raise BackendError(404, "DRS-9001", "no such suggestion")
            if s["status"] != "suggested":
                raise BackendError(409, "DRS-9004", f"the suggestion is {s['status']} already")
            s.update(status="accepted" if verb == "accept" else "dismissed", decidedBy=self.user, item="bi-model-trading" if verb == "accept" else None)
            return copy.deepcopy(s)
        if path.startswith("/admin/runs/") and path.endswith("/cancel"):
            rid = path.split("/")[3]
            return self._cancel(rid, "cancelled by an administrator (ops1)")
        if path == "/runs/schedules" and method == "GET":
            return {"schedules": [{k: v for k, v in x.items() if k != "owner"} for x in self.schedules.values() if x["owner"] == self.user]}
        if path.startswith("/runs/schedules/"):
            sid = path.split("/")[3]
            if sid not in self.schedules:
                raise BackendError(404, "DRS-9061", "no such schedule")
            sch = self.schedules[sid]
            if method == "DELETE":
                del self.schedules[sid]
                self.runs[sch["runId"]]["schedule"] = None
                return None
            b = body or {}
            if b.get("schedule"):
                self._check_schedule(b["schedule"])
                sch.update(schedule=b["schedule"], nextAt=self._next_at(b["schedule"]))
            if "enabled" in b:
                sch.update(enabled=bool(b["enabled"]), nextAt=self._next_at(sch["schedule"]) if b["enabled"] else None)
            if b.get("data"):
                sch["data"] = b["data"]
            self.runs[sch["runId"]]["schedule"] = {k: v for k, v in sch.items() if k != "owner"}
            return {k: v for k, v in sch.items() if k != "owner"}
        if path.startswith("/runs/"):
            parts = path.split("/")
            rid = parts[2]
            if rid not in self.runs or not self.sees(self.runs[rid]):
                raise BackendError(404, "DRS-9080", "no such run")
            r = self.runs[rid]
            tail = "/".join(parts[3:])
            if not tail and method == "GET":
                return self.view_of(r)
            if tail == "result" and method == "GET":
                if r["state"] == "expired":
                    raise BackendError(410, "DRS-9084", "the result was removed on 2026-11-19 (kept 30 days)")
                if r["state"] not in ("done", "reused"):
                    raise BackendError(409, "DRS-9083", f"the run is {r['state']}")
                return answer(MASK_FOR.get(self.user, ()))
            if tail == "result" and method == "DELETE":
                r.update(state="expired", expiresAt=None)
                self._event(r)
                return None
            if tail == "cancel":
                return self._cancel(rid, "cancelled by you")
            if tail == "confirm":
                r.update(state="queued", _t0=time.time())
                self._event(r)
                return self.view_of(r)
            if tail == "share":
                return {"shares": self._share(r, (body or {}).get("to", []))}
            if tail.startswith("share/") and method == "DELETE":
                r["shared"]["with"] = [s for s in r["shared"]["with"] if s["recipient"] != parts[4]]
                return None
            if tail == "keep":
                r["expiresAt"] = "2027-01-18T09:14:00Z"
                return self.view_of(r)
            if tail == "promote":                                                          # as RunPromotion (BI_DESIGN › BI runs, 9.4 as built)
                b = body or {}
                name, to = b.get("name") or r["id"], b.get("to") or "dataset"
                if to not in ("dataset", "report"):
                    raise BackendError(400, "DRS-0400", f"to is dataset or report, not '{to}'")
                if r["source"].get("type") not in ("mq", "sql"):
                    raise BackendError(400, "DRS-0400", f"a {r['source'].get('type')} run cannot be promoted: only a model query or a SQL statement's table")
                owner = self.snapshots.setdefault(name, {"owner": "someone-else" if name == "taken" else self.user, "version": 0})
                if owner["owner"] != self.user:
                    raise BackendError(409, "DRS-9004", f"the snapshot name '{name}' is taken by another person's promotion: choose another name")
                owner["version"] += 1
                self.promotions.append(dict(b, run=rid))
                r["promotedTo"] = f"{name}@{owner['version']}"
                return {"snapshot": name, "version": owner["version"], "dataset": {"name": name, "item": f"bi-ds-{name}"},
                        "report": {"item": f"bi-rpt-{name}"} if to == "report" else None, "yaml": f"rachana: 2\ndataset: {name}\n"}
            if tail == "schedule":
                b = body or {}
                self._check_schedule(b.get("schedule"))
                sid = f"sch-{len(self.schedules) + 1}"
                sch = {"id": sid, "runId": rid, "title": r["title"], "source": copy.deepcopy(r["source"]), "schedule": b["schedule"], "data": b.get("data") or "latest",
                       "enabled": True, "lastRun": None, "nextAt": self._next_at(b["schedule"]), "createdAt": iso(time.time()), "owner": self.user}
                self.schedules[sid] = sch
                r["schedule"] = {k: v for k, v in sch.items() if k != "owner"}
                return copy.deepcopy(r["schedule"])
        return await super().bi(method, path, ident, body, **params)

    def _share(self, r, to):
        """One share per person (a role expanded to its members now): same, re-masked, recomputed (a run of their own) or refused (mode null with its code)."""
        people = []
        for t in to:
            people += ROLES.get(t[5:], []) if t.startswith("role:") else [t]
        shares = []
        for who in dict.fromkeys(people):
            mode = SHARE_MODE.get(who, "same")
            if mode is None:
                shares.append({"recipient": who, "mode": None, "code": "DRS-9021", "detail": f"{who} may not read the model trading", "recipientRun": None})
                continue
            run = None
            if mode == "recomputed":
                run = self._new(r["title"], "queued", source=r["source"], runner=who)
                run["shared"] = {"from": self.user, "with": []}
            shares.append({"recipient": who, "mode": mode, "recipientRun": run["id"] if run else None})
        kept = [x for x in shares if x["mode"]]
        r["shared"]["with"] = [x for x in r["shared"]["with"] if x["recipient"] not in {k["recipient"] for k in kept}] + kept
        return shares

    @staticmethod
    def _check_schedule(text):
        if not text or not SCHEDULE.fullmatch(str(text).strip()):
            raise BackendError(422, "DRS-9061", f"not a schedule: {text!r} (daily HH:MM, weekdays HH:MM, business-days HH:MM, weekly mon HH:MM, monthly last HH:MM [business])")

    @staticmethod
    def _next_at(text):
        hhmm = re.search(r"(\d\d):(\d\d)", text).group(0)
        return ("2026-10-31T" if text.startswith("monthly") else "2026-10-22T") + hhmm + ":00Z"

    def _cancel(self, rid, why):
        r = self.runs[rid]
        r.update(state="cancelled", reason=why, finishedAt=iso(time.time()))
        self._event(r)
        return self.view_of(r)

    def share_as_viewer(self, rid, viewer="bob"):
        """What the viewer sees after a share: the run listed as shared with them (the stand-in answers as them from now on)."""
        r = self.runs[rid]
        if not any(x["recipient"] == viewer for x in r["shared"]["with"]):
            r["shared"]["with"].append({"recipient": viewer, "mode": SHARE_MODE.get(viewer) or "same", "recipientRun": None})
        self.user = viewer


def serve(backend=None):
    """Starts the console in this process against the stand-in server; returns (url, backend, stop)."""
    import uvicorn

    from conftest import CONSOLE
    from core.app import create_app
    from core.config import load_settings
    from test_live_tabs_browser import _free_port

    app = create_app(load_settings(CONSOLE / "config"))
    app.state.backend = backend or RunsBackend()
    port = _free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 15
    while not server.started and time.monotonic() < deadline:
        time.sleep(0.05)
    assert server.started

    def stop():
        server.should_exit = True
        thread.join(10)
    return f"http://127.0.0.1:{port}", app.state.backend, stop
