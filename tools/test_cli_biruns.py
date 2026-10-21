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

"""Tests of `drishti.py bi runs ...` (tools/biruns.py, BI_RUNS.md section 14.4) and `bi mq --async` / `bi sql --async` against an in-process stand-in
for the server: the paths and bodies sent, the Prefer header, the words printed for each state, the exit codes of `wait`, a 409 explained, the
files written, and the shell completion that knows the new commands."""
import json
import pathlib
import shutil
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

RID = "r-java-2-01JAC4Q8Z6T3M4R9B0X1Y2Z3W4"
RUN = {"id": RID, "title": "MTM by desk and month", "source": {"type": "report", "id": "rpt:desk-pnl", "title": "Desk P&L", "page": "overview", "visual": "mtm-by-desk-month"},
       "runner": "ajsinha", "owner": "ajsinha", "asOf": {"from": "2026-01-02", "to": "2026-09-30", "dates": 182},
       "data": {"words": "trade_endur delta v412"}, "tier": "ad-hoc-heavy", "engine": "local", "state": "running", "progress": 0.29, "phase": "building 12 of 41 dates",
       "estimate": {"readRows": 1840000000, "seconds": {"low": 150, "high": 330}, "basis": "delta-stats", "why": "no rollup has grain desk × month"},
       "actual": {"seconds": 112}, "node": "java-2", "submittedAt": "2026-10-20T09:14:03Z", "shared": {"from": None, "with": []}, "can": {"cancel": True}}


class Recording(FakeServer):
    prefer: list = []

    def handle_any(self):
        Recording.prefer.append(self.headers.get("Prefer"))
        super().handle_any()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_any


class BiRuns(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Recording)
        cls.url = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        FakeServer.requests, FakeServer.routes, Recording.prefer = [], {}, []
        self.env = {"DRISHTI_SERVER": self.url, "DRISHTI_TOKEN": "drk_secret"}
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-biruns-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def sent(self):
        return json.loads(FakeServer.requests[-1][5])

    def test_list_prints_each_run_in_words_with_its_estimate(self):
        done = dict(RUN, id="r-2", title="Notional by counterparty", state="done", progress=1.0, actual={"seconds": 41, "rowsOut": 1204})
        failed = dict(RUN, id="r-3", title="Raw trades 2025", state="failed", code="DRS-9028", reason="4 GB budget")
        FakeServer.routes[("GET", "/api/v1/bi/runs")] = (200, {"runs": [RUN, done, failed], "storage": {"bytes": 1503238553, "results": 48}, "nodes": {"failed": ["java-3"]}})
        code, out, err = self.go("bi", "runs", "list", "--state", "running,done", "--since", "7d")
        self.assertEqual(0, code)
        self.assertIn("running · building 12 of 41 dates · 29 %", out)
        self.assertIn("done · 41 s · 1,204 rows", out)
        self.assertIn("failed: it ran out of its memory budget; 4 GB budget (DRS-9028)", out)
        self.assertIn("est. 3 to 6 min", out)
        self.assertIn("1.4 GB of your results (48)", out)
        self.assertIn("java-3 did not answer", err)
        path = FakeServer.requests[-1][1]
        self.assertIn("state=running%2Cdone", path)
        self.assertIn("since=7d", path)
        self.assertIn("mine=true", path)

    def test_get_shows_the_run_like_the_drawer(self):
        FakeServer.routes[("GET", f"/api/v1/bi/runs/{RID}")] = (200, RUN)
        code, out, _ = self.go("bi", "runs", "get", RID)
        self.assertEqual(0, code)
        for want in ("MTM by desk and month", "as of       2026-01-02 to 2026-09-30 (182 dates)", "trade_endur delta v412", "3 to 6 min, 1,840,000,000 rows read (delta-stats)",
                     "ad-hoc-heavy · local engine", "no rollup has grain desk × month"):
            self.assertIn(want, out)

    def test_submit_sends_prefer_and_prints_the_run_on_202(self):
        FakeServer.routes[("POST", "/api/v1/bi/runs")] = (202, {"run": RUN})
        code, out, _ = self.go("bi", "runs", "submit", "--sql", "SELECT 1", "--model", "trading", "--wait", "0", "--yes", "--title", "One")
        self.assertEqual(0, code)
        self.assertIn(RID, out)
        self.assertIn("bi runs wait " + RID, out)
        self.assertEqual("respond-async, wait=0", Recording.prefer[-1])
        self.assertEqual({"source": {"type": "sql", "sql": "SELECT 1", "model": "trading"}, "options": {"wait": "PT0S", "confirm": True, "title": "One"}}, self.sent())

    def test_submit_prints_the_answer_when_done_in_time(self):
        FakeServer.routes[("POST", "/api/v1/bi/runs")] = (200, {"run": dict(RUN, state="done"), "answer": {"columns": [{"name": "desk"}, {"name": "MTM"}], "rows": [["FX", 12]]}})
        code, out, _ = self.go("bi", "runs", "submit", "--report", "rpt:desk-pnl", "--page", "overview", "--visual", "v1")
        self.assertEqual(0, code)
        self.assertIn("desk\tMTM\nFX\t12", out)
        self.assertIn("answered inline", out)
        self.assertEqual("respond-async, wait=2", Recording.prefer[-1])

    def test_a_question_that_needs_confirming_is_explained_with_exit_2(self):
        FakeServer.routes[("POST", "/api/v1/bi/runs")] = (409, {"code": "DRS-9082", "detail": "confirm", "estimate": RUN["estimate"] | {"dates": 182}, "confirmToken": "ct-1"})
        f = self.tmp / "q.mq.json"
        f.write_text(json.dumps({"mq": 1, "model": "trading"}), encoding="utf-8")
        code, _, err = self.go("bi", "runs", "submit", "--mq", str(f))
        self.assertEqual(2, code)
        self.assertIn("DRS-9082: this question is large: it needs your confirmation (add --yes to accept it)", err)
        self.assertIn("about 1,840,000,000 rows over 182 business days (delta-stats)", err)
        self.assertIn("expected 3 to 6 min", err)

    def test_submit_needs_a_source(self):
        code, _, err = self.go("bi", "runs", "submit")
        self.assertEqual(2, code)
        self.assertIn("--mq FILE, --sql TEXT --model M", err)

    def test_estimate_runs_nothing_and_says_the_tier(self):
        FakeServer.routes[("POST", "/api/v1/bi/runs/estimate")] = (200, {"tier": "ad-hoc-heavy", "engine": "local", "estimate": RUN["estimate"], "reuse": None, "confirm": True})
        code, out, _ = self.go("bi", "runs", "estimate", "--sql", "SELECT 1", "--model", "trading")
        self.assertEqual(0, code)
        self.assertIn("tier ad-hoc-heavy · engine local · asks to confirm", out)

    def test_wait_exits_by_how_the_run_ended(self):
        for state, want in (("done", 0), ("failed", 3), ("cancelled", 4)):
            FakeServer.routes[("GET", f"/api/v1/bi/runs/{RID}")] = (200, dict(RUN, state=state, code="DRS-9024" if state == "failed" else None))
            code, out, _ = self.go("bi", "runs", "wait", RID, "--timeout", "5s")
            self.assertEqual(want, code, state)

    def test_wait_gives_up_after_the_timeout_with_exit_5(self):
        FakeServer.routes[("GET", f"/api/v1/bi/runs/{RID}")] = (200, RUN)
        code, out, err = self.go("bi", "runs", "wait", RID, "--timeout", "0s")
        self.assertEqual(5, code)
        self.assertIn("still running", err)

    def test_result_as_csv_writes_the_mask_for_masked_cells(self):
        FakeServer.routes[("GET", f"/api/v1/bi/runs/{RID}/result")] = (200, {"columns": [{"name": "desk"}, {"name": "MTM", "masked": True}], "rows": [["FX", None]]})
        out_file = self.tmp / "r.csv"
        code, out, _ = self.go("bi", "runs", "result", RID, "--format", "csv", "--out", str(out_file))
        self.assertEqual(0, code)
        self.assertEqual("desk,MTM\nFX,•••\n", out_file.read_text(encoding="utf-8").replace("\r\n", "\n"))
        self.assertIn("format=json", FakeServer.requests[-1][1])

    def test_cancel_rerun_share_promote_keep_and_schedule_send_the_contract(self):
        base = f"/api/v1/bi/runs/{RID}"
        FakeServer.routes.update({
            ("POST", base + "/cancel"): (202, dict(RUN, state="cancelled", reason="cancelled by you")),
            ("POST", base + "/rerun"): (202, {"run": dict(RUN, id="r-9", state="reused")}),
            ("POST", base + "/share"): (200, {"shares": [{"recipient": "bob", "mode": "re-masked"}, {"recipient": "carol", "mode": "recomputed", "recipientRun": "r-c"}]}),
            ("POST", base + "/promote"): (201, {"item": {"id": "bi-7", "kind": "dataset", "workspace": "me", "status": "draft"}, "snapshot": {"name": "desk-pnl-2026", "version": 3}}),
            ("POST", base + "/keep"): (200, dict(RUN, expiresAt="2027-01-18T00:00:00Z")),
            ("POST", base + "/schedule"): (201, {"id": "sch-1", "schedule": "business-days 07:30", "data": "latest", "nextAt": "2026-10-22T07:30:00Z"})})
        code, out, _ = self.go("bi", "runs", "cancel", RID)
        self.assertIn("cancelled: cancelled by you", out)
        code, out, _ = self.go("bi", "runs", "rerun", RID, "--data", "same")
        self.assertEqual({"data": "same", "wait": "PT2S"}, self.sent())
        self.assertIn("r-9  reused", out)
        code, out, _ = self.go("bi", "runs", "share", RID, "--to", "bob", "--to", "role:risk-team")
        self.assertEqual({"to": ["bob", "role:risk-team"]}, self.sent())
        self.assertIn("bob: sees it with their own masks", out)
        self.assertIn("carol: gets their own run, as them (run r-c)", out)
        code, out, _ = self.go("bi", "runs", "promote", RID, "--as", "snapshot", "--name", "desk-pnl-2026")
        self.assertEqual({"as": "snapshot", "name": "desk-pnl-2026"}, self.sent())
        self.assertIn("(snapshot desk-pnl-2026 v3)", out)
        code, out, _ = self.go("bi", "runs", "keep", RID, "--days", "90")
        self.assertEqual({"days": 90}, self.sent())
        self.assertIn("kept until 2027-01-18", out)
        code, out, _ = self.go("bi", "runs", "schedule", RID, "business-days 07:30")
        self.assertEqual({"schedule": "business-days 07:30", "data": "latest"}, self.sent())
        self.assertIn("next 2026-10-22 07:30", out)

    def test_export_writes_the_file(self):
        FakeServer.routes[("POST", f"/api/v1/bi/runs/{RID}/export")] = (200, b"PK\x03\x04xlsx")
        f = self.tmp / "pnl.xlsx"
        code, out, _ = self.go("bi", "runs", "export", RID, "--format", "xlsx", "--out", str(f))
        self.assertEqual(0, code)
        self.assertEqual(b"PK\x03\x04xlsx", f.read_bytes())
        self.assertEqual({"format": "xlsx"}, self.sent())

    def test_schedules_list_and_disable(self):
        FakeServer.routes[("GET", "/api/v1/bi/runs/schedules")] = (200, {"schedules": [{"id": "sch-1", "schedule": "daily 07:00", "enabled": True}]})
        FakeServer.routes[("PUT", "/api/v1/bi/runs/schedules/sch-1")] = (200, {"id": "sch-1", "enabled": False})
        code, out, _ = self.go("bi", "runs", "schedules")
        self.assertIn("sch-1  daily 07:00  on", out)
        code, out, _ = self.go("bi", "runs", "schedules", "disable", "sch-1")
        self.assertIn("sch-1  disabled", out)
        self.assertEqual({"enabled": False}, self.sent())

    def test_storage_for_bi_admins(self):
        FakeServer.routes[("GET", "/api/v1/bi/admin/runs")] = (200, {"storage": {"resultsBytes": 11 * 1024 ** 3, "maxBytes": 20 * 1024 ** 3, "results": 1904}, "queue": {"byClass": {"background": 5}, "byUser": {"carol": 3}},
                                                                   "running": [RUN]})
        code, out, _ = self.go("bi", "runs", "storage")
        self.assertEqual(0, code)
        self.assertIn("results    11.0 GB of 20.0 GB (1,904 results)", out)
        self.assertIn("queue      5 queued; per person: carol 3", out)

    def test_bi_mq_and_bi_sql_async_send_prefer_and_print_the_run(self):
        FakeServer.routes[("POST", "/api/v1/bi/mq")] = (202, {"run": RUN})
        FakeServer.routes[("POST", "/api/v1/bi/sql")] = (202, {"run": RUN})
        f = self.tmp / "q.json"
        f.write_text(json.dumps({"mq": 1, "model": "trading", "select": {"fields": ["desk"]}}), encoding="utf-8")
        code, out, _ = self.go("bi", "mq", str(f), "--async", "--wait", "0")
        self.assertEqual(0, code)
        self.assertIn(RID, out)
        self.assertEqual("respond-async, wait=0", Recording.prefer[-1])
        code, out, _ = self.go("bi", "sql", "SELECT 1", "--model", "trading", "--async")
        self.assertEqual(0, code)
        self.assertEqual("respond-async, wait=2", Recording.prefer[-1])
        self.assertEqual("SELECT 1", self.sent()["sql"])
        self.assertNotIn("confirm", self.sent())
        code, out, _ = self.go("bi", "mq", str(f), "--async", "--yes")            # the hint of a DRS-9082 refusal ("add --yes") works here too (BIR-05)
        self.assertEqual(0, code)
        self.assertIs(True, self.sent()["confirm"])
        code, out, _ = self.go("bi", "sql", "SELECT 1", "--model", "trading", "--async", "--yes")
        self.assertIs(True, self.sent()["confirm"])

    def test_bi_mq_without_async_is_synchronous_as_before(self):
        FakeServer.routes[("POST", "/api/v1/bi/mq")] = (200, {"columns": [{"name": "desk"}], "rows": [["FX"]], "meta": {}})
        f = self.tmp / "q.json"
        f.write_text(json.dumps({"mq": 1, "model": "trading"}), encoding="utf-8")
        code, out, _ = self.go("bi", "mq", str(f))
        self.assertEqual(0, code)
        self.assertIsNone(Recording.prefer[-1])

    def test_completion_knows_the_runs_commands(self):
        code, out, _ = self.go("completion", "bash")
        self.assertEqual(0, code)
        self.assertIn('"bi runs") subs="list get submit estimate wait result cancel rerun export share promote keep schedule schedules storage"', out)
        self.assertIn("--async", out)


if __name__ == "__main__":
    unittest.main()
