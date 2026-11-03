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

"""Tests of `drishti.py dataload status|run|retry` (docs/architecture/DATALOAD_INBOX.md section 10) against an in-process stand-in for
the server: the status as text and JSON, Load now started and skipped while a run loads, Retry, a retry of an unknown file (exit 1) and
the inbox off on every server (DRS-1020, exit 3)."""
import json
import pathlib
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

STATUS = {"on": True, "owner": True, "node": "drishti-main", "dir": "/srv/data/dataload", "interval": "PT10M",
          "nextCycleAt": "2026-11-03T15:10:00Z", "running": None,
          "lock": {"run": "20261103T150000Z-drishti-main", "node": "drishti-main", "pid": 4242, "heartbeat": "2026-11-03T15:01:00Z"},
          "waiting": [{"file": "trades-endur-2026-11-03.jsonl.gz", "size": 2_400_000, "modified": "2026-11-03T14:59:00Z",
                       "state": "not stable yet: changed less than 30 s ago"},
                      {"file": "positions.jsonl", "size": 120, "modified": "2026-11-03T14:00:00Z", "state": "no positions.jsonl.ready yet"}],
          "markersWithoutFile": ["gone.jsonl.ready"], "readyAll": False,
          "failed": [{"file": "mystery.jsonl", "day": "2026-11-03", "run": "20261103T140000Z-drishti-main",
                      "reason": "no routing rule matches mystery.jsonl"}],
          "cycles": [{"run": "20261103T150000Z-drishti-main", "trigger": "schedule", "started": "2026-11-03T15:00:00Z", "state": "done",
                      "files": [{"file": "a.jsonl", "outcome": "loaded"}, {"file": "b.jsonl", "outcome": "loaded"},
                                {"file": "c.jsonl", "outcome": "duplicate"}], "notices": []},
                     {"run": "-", "trigger": "by a.sinha", "started": "2026-11-03T14:55:00Z", "state": "skipped",
                      "why": "run 20261103T144000Z-drishti-main still loading (15 min)", "files": [], "notices": []}]}


class Dataload(unittest.TestCase):
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
        FakeServer.routes[("GET", "/api/v1/admin/dataload")] = (200, STATUS)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_status_shows_the_waiting_files_the_failures_and_the_cycles(self):
        code, out, _ = self.go("dataload", "status")
        self.assertEqual(0, code, out)
        self.assertIn("data inbox on, node drishti-main, every PT10M, folder /srv/data/dataload", out)
        self.assertIn("folder lock held by drishti-main (process 4242)", out)
        self.assertRegex(out, r"trades-endur-2026-11-03\.jsonl\.gz\s+2\.3 MB\s+not stable yet")
        self.assertIn("gone.jsonl.ready", out)
        self.assertRegex(out, r"mystery\.jsonl\s+2026-11-03\s+no routing rule matches mystery\.jsonl")
        self.assertRegex(out, r"20261103T150000Z-drishti-main\s+done\s+1 duplicate, 2 loaded")
        self.assertRegex(out, r"-\s+skipped\s+run 20261103T144000Z-drishti-main still loading \(15 min\)")

    def test_status_json_is_the_servers_answer(self):
        code, out, _ = self.go("dataload", "status", "--json")
        self.assertEqual(0, code)
        self.assertEqual(STATUS["failed"], json.loads(out)["failed"])

    def test_run_says_whether_a_cycle_started_or_a_run_still_loads(self):
        FakeServer.routes[("POST", "/api/v1/admin/dataload/run")] = (200, {"started": True, "by": "admin"})
        code, out, _ = self.go("dataload", "run")
        self.assertEqual(0, code)
        self.assertIn("Load now: a cycle started", out)
        FakeServer.routes[("POST", "/api/v1/admin/dataload/run")] = (200, {"started": False, "by": "admin",
                                                                          "skipped": "skipped: run 20261103T150000Z-drishti-main still loading (3 min)"})
        code, out, _ = self.go("dataload", "run")
        self.assertEqual(0, code)
        self.assertIn("Load now: skipped: run 20261103T150000Z-drishti-main still loading (3 min) (runs never overlap", out)

    def test_retry_sends_the_file_and_an_unknown_one_exits_1(self):
        FakeServer.routes[("POST", "/api/v1/admin/dataload/retry")] = (200, {"file": "mystery.jsonl", "from": "failed/2026-11-03/r/mystery.jsonl",
                                                                            "to": "inbox/mystery.jsonl", "next": "the next cycle loads it (or Load now)"})
        code, out, _ = self.go("dataload", "retry", "mystery.jsonl")
        self.assertEqual(0, code, out)
        self.assertIn("mystery.jsonl: back in the inbox (from failed/2026-11-03/r/mystery.jsonl)", out)
        self.assertEqual({"file": "mystery.jsonl"}, json.loads(FakeServer.requests[-1][5]))
        FakeServer.routes[("POST", "/api/v1/admin/dataload/retry")] = (404, {"code": "DRS-1001", "detail": "DRS-1001 no failed file is named x"})
        code, _, err = self.go("dataload", "retry", "x.jsonl")
        self.assertEqual(1, code)
        self.assertIn("no failed file is named x.jsonl", err)

    def test_the_inbox_off_everywhere_exits_3(self):
        FakeServer.routes[("GET", "/api/v1/admin/dataload")] = (404, {"code": "DRS-1020", "engine": "dataload",
                                                                     "detail": "DRS-1020 Data inbox is not running on this server, and no other server of this cluster runs it."})
        code, _, err = self.go("dataload", "status")
        self.assertEqual(3, code, err)
        self.assertIn("Data inbox is not running on this server", err)


if __name__ == "__main__":
    unittest.main()
