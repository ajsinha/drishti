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

"""Tests of `drishti.py bi item|dataset` (tools/biitem.py) against an in-process stand-in for the server."""
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


class BiItem(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeServer)
        cls.url = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def setUp(self):
        FakeServer.requests, FakeServer.routes = [], {}
        self.env = {"DRISHTI_SERVER": self.url, "DRISHTI_TOKEN": "drk_secret"}
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-biitem-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_new_reads_the_kind_and_title_from_the_file_and_sends_the_audience(self):
        f = self.tmp / "trades-daily.model.yaml"
        f.write_text("rachana: 2\nmodel: trades-daily\ntitle: \"Trades daily\"   # the title\n", encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/workspaces/economics-desk/items")] = (201, {"id": "bi-1", "kind": "model", "title": "Trades daily", "status": "draft",
                                                                                         "workspaceName": "Economics desk"})
        code, out, _ = self.go("bi", "item", "new", "economics-desk", str(f), "--audience", "risk, desk-head")
        self.assertEqual(0, code)
        self.assertIn("bi-1  model  Trades daily  draft in Economics desk", out)
        sent = json.loads(FakeServer.requests[-1][5])
        self.assertEqual(("model", "Trades daily", ["risk", "desk-head"]), (sent["kind"], sent["title"], sent["audienceRoles"]))
        self.assertIn("model: trades-daily", sent["body"])

    def test_new_without_a_kind_in_the_name_says_how(self):
        f = self.tmp / "plain.yaml"
        f.write_text("title: x\n", encoding="utf-8")
        code, _, err = self.go("bi", "item", "new", "me", str(f))
        self.assertEqual(2, code)
        self.assertIn("--kind", err)

    def test_save_puts_the_body_with_the_message(self):
        f = self.tmp / "x.dataset.yaml"
        f.write_text("rachana: 2\ndataset: x\n", encoding="utf-8")
        FakeServer.routes[("PUT", "/api/v1/bi/items/bi-2")] = (200, {"id": "bi-2", "status": "draft", "publishedVersion": 1})
        code, out, _ = self.go("bi", "item", "save", "bi-2", str(f), "--message", "add a column")
        self.assertEqual(0, code)
        self.assertIn("bi-2  saved  (draft, published v1)", out)
        self.assertEqual({"body": "rachana: 2\ndataset: x\n", "message": "add a column"}, json.loads(FakeServer.requests[-1][5]))

    def test_tests_print_each_result_and_exit_1_on_a_failure(self):
        FakeServer.routes[("POST", "/api/v1/bi/items/bi-3/tests")] = (200, {"total": 2, "passed": 1, "failed": 1, "errors": 0, "ok": False, "role": "desk-head", "results": [
            {"name": "All trades", "status": "pass", "expect": "750", "actual": 750.0},
            {"name": "Close to 100", "status": "fail", "expect": "100 ± 1%", "actual": 750.0, "message": "expected 100 ± 1%, got 750"}]})
        code, out, _ = self.go("bi", "item", "tests", "bi-3", "--role", "desk-head")
        self.assertEqual(1, code)
        self.assertIn("pass  All trades: expected 750, found 750", out)
        self.assertIn("fail  Close to 100: expected 100 ± 1%, found 750", out)
        self.assertIn("1 passed, 1 failed  (as desk-head)", out)
        self.assertEqual({"role": "desk-head"}, json.loads(FakeServer.requests[-1][5]))

    def test_refresh_shows_one_line_for_a_dataset_of_one_table(self):
        FakeServer.routes[("GET", "/api/v1/bi/datasets/bi-4/refresh")] = (200, {"scheduled": True, "zone": "America/New_York", "calendar": "USNY",
            "dataset": {"schedule": "on demand", "status": "ok", "trigger": "manual (ann)", "lastRun": "2026-10-13T06:12:45.9Z", "lastRows": 750, "lastDurationMs": 297},
            "tables": [{"table": "trades_daily", "schedule": "business-days 06:30", "status": "waiting for the scheduler", "nextRun": "2026-10-13T10:30:00Z"}]})
        code, out, _ = self.go("bi", "dataset", "refresh", "bi-4")
        self.assertEqual(0, code)
        self.assertIn("scheduled  (America/New_York, calendar USNY)", out)
        self.assertIn("dataset   business-days 06:30   ok  last run 2026-10-13 06:12:45 UTC by manual (ann): 750 rows in 297 ms  next 2026-10-13T10:30:00Z", out)
        self.assertNotIn("table trades_daily", out)


if __name__ == "__main__":
    unittest.main()
