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

"""Tests of `drishti.py bi expr|migrate|sql` (tools/bilang.py) against an in-process stand-in for the server."""
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

class BiLang(unittest.TestCase):
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
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-bilang-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_functions_lists_by_family_with_the_wave(self):
        FakeServer.routes[("GET", "/api/v1/bi/expr/functions")] = (200, {"functions": [
            {"name": "sum", "family": "aggregate", "signature": "sum(x, where=None)", "status": "built", "description": "The sum."},
            {"name": "calc", "family": "filter context", "signature": "calc(agg, ...)", "status": "B", "description": "Edited context."}]})
        code, out, _ = self.go("bi", "expr", "functions", "--scope", "aggregate")
        self.assertEqual(0, code)
        self.assertIn("sum(x, where=None)", out)
        self.assertIn("(wave B)", out)
        self.assertTrue(FakeServer.requests[0][1].endswith("?scope=aggregate"))

    def test_validate_prints_problems_with_the_fix_and_exits_1(self):
        FakeServer.routes[("POST", "/api/v1/bi/expr/validate")] = (200, {"valid": False, "syntax": "del2", "scope": "aggregate", "problems": [
            {"code": "DRS-9127", "level": "error", "message": "the column trades.mtm is outside an aggregate", "fix": "wrap it: sum(trades.mtm)", "at": {"start": 0, "end": 10}}]})
        code, out, _ = self.go("bi", "expr", "validate", "trades.mtm * 2", "--model", "trading-risk@1")
        self.assertEqual(1, code)
        self.assertIn("INVALID", out)
        self.assertIn("fix: wrap it: sum(trades.mtm)", out)
        body = json.loads(FakeServer.requests[0][5])
        self.assertEqual({"expr": "trades.mtm * 2", "scope": "aggregate", "model": "trading-risk@1"}, body)

    def test_migrate_boards_without_files_works_on_the_stored_boards(self):
        FakeServer.routes[("POST", "/api/v1/bi/migrate/boards")] = (200, {"scanned": 1, "wouldConvert": 1, "problems": 0, "items": [
            {"id": "b1", "title": "Desk board", "status": "published", "state": "would become a report"}]})
        code, out, _ = self.go("bi", "migrate", "--boards", "--dry-run")
        self.assertEqual(0, code)
        self.assertIn("1 stored board(s), 1 would become reports", out)
        self.assertEqual({"dryRun": True}, json.loads(FakeServer.requests[0][5]))

    def test_the_old_migrations_are_gone(self):
        code, _, err = self.go("bi", "migrate", "--expressions")
        self.assertNotEqual(0, code)
        code, _, err = self.go("bi", "expr", "convert", "[A] / [B]")
        self.assertNotEqual(0, code)

    def test_sql_prints_a_table(self):
        FakeServer.routes[("POST", "/api/v1/bi/sql")] = (200, {"columns": [{"name": "desk"}, {"name": "mtm"}], "rows": [["RATES", 1234.5], ["FX", None]],
                                                              "meta": {"shape": "mq", "plan": "p_1"}})
        code, out, _ = self.go("bi", "sql", "SELECT trades.desk AS desk, MEASURE('MTM') AS mtm FROM trades GROUP BY trades.desk", "--model", "m@1")
        self.assertEqual(0, code)
        self.assertIn("RATES", out)
        self.assertIn("1,234.50", out)
        self.assertIn("2 row(s)", out)
        self.assertEqual("m@1", json.loads(FakeServer.requests[0][5])["model"])

    def test_sql_as_jsonl_prints_the_servers_lines(self):
        FakeServer.routes[("POST", "/api/v1/bi/sql")] = (200, b'{"desk":"RATES","mtm":1234.5}\n')
        code, out, _ = self.go("bi", "sql", "SELECT 1", "--model", "m@1", "--format", "jsonl")
        self.assertEqual(0, code)
        self.assertEqual('{"desk":"RATES","mtm":1234.5}\n', out)
        self.assertEqual("jsonl", json.loads(FakeServer.requests[0][5])["format"])


if __name__ == "__main__":
    unittest.main()
