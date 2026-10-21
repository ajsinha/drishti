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

"""A stand-in server for BI runs (docs/architecture/BI_RUNS.md sections 6, 7, 14): synthetic data only. It answers the endpoints of section 14.1 with
the run object of 14.3, moves a run queued -> running (progress 0 to 1, a phase) -> done on a clock, publishes every change on
``/bi/runs/events`` (and a ``bi-run`` notice on the bell's stream when a visible run ends), and answers a report visual's question with
``Prefer: respond-async`` as 200 (in time), 202 (a run), 409 DRS-9082 (confirm first) or 409 DRS-9089 (an interaction that needs a run).
Cancel, re-run (same data reuses), share (same / re-masked / recomputed), keep, delete, promote, schedule, export, the settings and the
Admin view are there too. Used by test_bi_runs_*.py and bi_runs_shots.py."""
from __future__ import annotations

import asyncio
import copy
import itertools
import json
import threading
import time

from bi_backend import BiBackend, DATASET_MODEL
from core.backend import BackendError

REPORT_ID = "file:report:desk-pnl"
DESKS = ["DESK-FX", "DESK-FI", "DESK-EQD", "DESK-RATES"]
MASK_FOR = {"bob": ["MTM"]}                  # the viewer's extra masks: a re-masked share shows the mask in this column

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

    def can(self, run):
        mine = run["runner"] == self.user or run["owner"] == self.user
        active = run["state"] in ("queued", "running", "storing", "awaiting-confirm")
        has = run["state"] in ("done", "reused")
        return {"open": has, "cancel": active and mine, "rerun": mine and not active, "export": has, "share": has and mine, "promote": has and mine,
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
        out = {k: v for k, v in r.items() if not k.startswith("_")}
        out["can"] = self.can(r)
        return out

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
            if body.get("data") == "same" and old["state"] in ("done", "reused"):
                r = self._new(old["title"], "reused", source=old["source"])
                r.update(reuse={"of": rid, "mode": "same"}, rerunOf=rid, progress=1.0, startedAt=iso(time.time()), finishedAt=iso(time.time()), actual=dict(old["actual"]))
                self._event(r)
                return 202, {"run": self.view_of(r)}
            r = self._new(old["title"], source=old["source"])
            r["rerunOf"] = rid
            return 202, {"run": self.view_of(r)}
        if path.startswith("/reports/") and path.endswith("/query"):
            return 200, answer()
        return 200, await self.bi(method, path, ident, body, **params)

    async def bi_file(self, method, path, ident, body=None, wait=None):
        rid = path.split("/")[2]
        fmt = (body or {}).get("format", "csv")
        return b"DESK,MTM\nDESK-FX,1250000\n", {"content-type": "text/csv", "content-disposition": f'attachment; filename="{rid}.{fmt}"'}

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
            rows = [self.view_of(r) for r in sorted(self.runs.values(), key=lambda r: -r["_t0"]) if r.get("_visible")]
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
                    "suggestions": [{"model": "trading", "grain": ["desk", "month"], "runs": 37, "medianSeconds": 240, "status": "rollup drafted for review"}]}
        if path.startswith("/admin/runs/") and path.endswith("/cancel"):
            rid = path.split("/")[3]
            return self._cancel(rid, "cancelled by an administrator (ops1)")
        if path.startswith("/runs/"):
            parts = path.split("/")
            rid = parts[2]
            if rid not in self.runs:
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
                mode = {"bob": "re-masked", "carol": "recomputed"}
                shares = [{"recipient": t, "mode": mode.get(t, "same"), "recipientRun": None} for t in (body or {}).get("to", [])]
                r["shared"]["with"] = [s for s in r["shared"]["with"] if s["recipient"] not in {x["recipient"] for x in shares}] + shares
                return {"shares": shares}
            if tail.startswith("share/") and method == "DELETE":
                r["shared"]["with"] = [s for s in r["shared"]["with"] if s["recipient"] != parts[4]]
                return None
            if tail == "keep":
                r["expiresAt"] = "2027-01-18T09:14:00Z"
                return self.view_of(r)
            if tail == "promote":
                b = body or {}
                return {"item": {"id": "bi-snap-1", "kind": "dataset" if b.get("as") == "snapshot" else "report", "workspace": "me", "status": "draft"},
                        "snapshot": {"name": b.get("name"), "version": 1} if b.get("as") == "snapshot" else None}
            if tail == "schedule":
                return {"id": "sch-1", "schedule": (body or {}).get("schedule"), "data": (body or {}).get("data"), "nextAt": "2026-10-22T07:30:00Z"}
        return await super().bi(method, path, ident, body, **params)

    def _cancel(self, rid, why):
        r = self.runs[rid]
        r.update(state="cancelled", reason=why, finishedAt=iso(time.time()))
        self._event(r)
        return self.view_of(r)

    def share_as_viewer(self, rid, viewer="bob"):
        """What the viewer sees after a share: the run listed as shared with them (the stand-in answers as them from now on)."""
        r = self.runs[rid]
        self.user = viewer
        r.update(shared={"from": r["runner"], "with": []}, owner=viewer)


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
