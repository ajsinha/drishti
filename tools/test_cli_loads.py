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

"""Tests of `drishti.py data landed` and `data loads` against an in-process stand-in for the server."""
import datetime
import json
import sys
import pathlib
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402
from test_drishti_cli import FakeServer, run  # noqa: E402

LOAD = {"id": "k1-1", "pack": "my-bank", "kind": "trade", "businessDate": "2026-10-06", "status": "ready", "rows": 48213, "rejected": 12,
        "verified": "verified", "attempt": 1, "reload": False, "alerts": 3, "notices": 2, "duplicate": False,
        "summary": "trade for 2026-10-06 loaded: 48,213 rows, 12 rejected; 3 alerts fired",
        "steps": [{"name": "record", "status": "ok", "detail": "recorded", "ms": 0}, {"name": "verify", "status": "ok", "detail": "16 trade entities", "ms": 4}]}


class DataLoads(unittest.TestCase):
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

    def test_landed_posts_the_announcement_and_prints_the_steps(self):
        FakeServer.routes[("POST", "/api/v1/packs/my-bank/loads")] = (201, LOAD)
        code, out, err = run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "2026-10-06", "--rows", "48213", "--rejected", "12",
                             "--batch", "eod-1", "--source", "etl", env=self.env)
        self.assertEqual(0, code, err)
        method, path, auth, _, ctype, body = FakeServer.requests[-1]
        self.assertEqual(("POST", "/api/v1/packs/my-bank/loads", "Bearer drk_secret", "application/json"), (method, path, auth, ctype))
        self.assertEqual({"kind": "trade", "businessDate": "2026-10-06", "status": "ready", "rows": 48213, "rejected": 12, "batchId": "eod-1", "source": "etl"},
                         json.loads(body))
        self.assertIn("trade for 2026-10-06 loaded: 48,213 rows", out)
        self.assertIn("verify", out)
        self.assertNotIn("drk_secret", out + err)

    def test_not_verified_exits_1_but_a_failed_load_that_was_recorded_exits_0(self):
        FakeServer.routes[("POST", "/api/v1/packs/my-bank/loads")] = (201, {**LOAD, "verified": "not-found"})
        self.assertEqual(1, run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "2026-10-06", env=self.env)[0])
        FakeServer.routes[("POST", "/api/v1/packs/my-bank/loads")] = (201, {**LOAD, "status": "failed", "verified": "skipped", "summary": "trade load FAILED"})
        code, out, _ = run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "2026-10-06", "--status", "failed", "--note", "disk full", env=self.env)
        self.assertEqual(0, code)
        self.assertEqual("failed", json.loads(FakeServer.requests[-1][5])["status"])
        self.assertEqual("disk full", json.loads(FakeServer.requests[-1][5])["note"])

    def test_today_and_yesterday_and_a_bad_date(self):
        FakeServer.routes[("POST", "/api/v1/packs/my-bank/loads")] = (201, LOAD)
        run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "yesterday", env=self.env)
        self.assertEqual((datetime.date.today() - datetime.timedelta(days=1)).isoformat(), json.loads(FakeServer.requests[-1][5])["businessDate"])
        code, _, err = run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "06/10/2026", env=self.env)
        self.assertEqual(2, code)
        self.assertIn("yyyy-MM-dd", err)

    def test_a_refusal_is_reported_with_its_code_and_a_hint_about_the_scope(self):
        FakeServer.routes[("POST", "/api/v1/packs/my-bank/loads")] = (403, {"code": "DRS-5002", "detail": "this token lacks the scope loads:write"})
        code, _, err = run("data", "landed", "--pack", "my-bank", "--kind", "trade", "--date", "2026-10-06", env=self.env)
        self.assertEqual(1, code)
        self.assertIn("DRS-5002", err)
        self.assertIn("loads:write", err)

    def test_loads_lists_history_filters_and_expectations_and_json(self):
        FakeServer.routes[("GET", "/api/v1/packs/my-bank/loads")] = (200, {"pack": "my-bank", "loads": [{**LOAD, "receivedAt": "2026-10-06T21:01:02Z", "batchId": "eod-1", "source": "etl"}]})
        FakeServer.routes[("GET", "/api/v1/packs/my-bank/loads/expectations")] = (200, {"expectations": [
            {"businessDate": "2026-10-06", "kind": "trade", "by": "19:00", "zone": "America/New_York", "state": "late"}]})
        code, out, _ = run("data", "loads", "--pack", "my-bank", "--kind", "trade", "--status", "ready", "--expectations", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("kind=trade", FakeServer.requests[0][1])
        self.assertIn("status=ready", FakeServer.requests[0][1])
        self.assertIn("48213", out)
        self.assertIn("eod-1", out)
        self.assertIn("late", out)
        code, out, _ = run("data", "loads", "--pack", "my-bank", "--json", env=self.env)
        self.assertEqual("my-bank", json.loads(out)["pack"])

    def test_the_commands_have_help_and_need_a_pack(self):
        for cmd in ("landed", "loads"):
            self.assertEqual(0, run("data", cmd, "--help")[0])
        self.assertEqual(2, run("data", "loads")[0])
        self.assertEqual(2, run("data", "landed", "--pack", "p", "--kind", "k")[0])


if __name__ == "__main__":
    unittest.main()
