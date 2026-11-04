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

"""Tests of `drishti.py server blas java|native|status` (docs/admin/OPERATIONS.md section 2.1) against an in-process stand-in: the switch is
the engine openblas of Admin -> Engines (the same POST, a reason required), native refused in words when the self-test fails, status with the
cluster's path and each member's."""
import pathlib
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

BLAS_NATIVE = {"status": "OK", "line": "BLAS: mode auto, native (bundled OpenBLAS 0.3.26), 1 thread per call", "clusterPath": "native",
               "members": {"drishti-b": "native 0.3.26"}}
BLAS_CHOSEN = {"status": "OK", "line": "BLAS: mode java, java (chosen by admin: switched off by alice at 2026-11-04T00:10:00Z (bench))",
               "clusterPath": "java", "members": {}}


def view(state, blas, reason=""):
    return {"node": "drishti-main", "engines": [{"id": "openblas", "title": "Linear algebra: OpenBLAS", "state": state, "reason": reason}],
            "native": {"blas": blas}}


class Blas(unittest.TestCase):
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

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_status_prints_the_line_the_cluster_and_the_members(self):
        FakeServer.routes[("GET", "/api/v1/admin/engines")] = (200, view("running", BLAS_NATIVE))
        code, out, _ = self.go("server", "blas", "status")
        self.assertEqual(0, code)
        self.assertIn("native (bundled OpenBLAS 0.3.26)", out)
        self.assertIn("cluster sums: native", out)
        self.assertIn("member drishti-b: native 0.3.26", out)

    def test_java_is_the_engine_switch_off_with_its_reason(self):
        res = {**view("off", BLAS_CHOSEN), "outcomes": [{"engine": "openblas", "on": False, "outcome": "done"}]}
        FakeServer.routes[("POST", "/api/v1/admin/engines")] = (200, res)
        code, out, _ = self.go("admin", "blas", "java", "--reason", "bench")
        self.assertEqual(0, code)
        self.assertIn("pure Java (chosen)", out)
        self.assertIn("chosen by admin", out)
        body = __import__("json").loads(FakeServer.requests[-1][5])
        self.assertEqual([{"engine": "openblas", "on": False}], body["changes"])
        self.assertEqual("bench", body["reason"])

    def test_native_refused_in_words_when_the_self_test_fails(self):
        res = {**view("blocked", BLAS_CHOSEN, "did not start: OpenBLAS failed its self-test against Java (dgemm 1 x 1 x 1 ...); the pure-Java path stays"),
               "outcomes": [{"engine": "openblas", "on": True, "outcome": "blocked"}]}
        FakeServer.routes[("POST", "/api/v1/admin/engines")] = (200, res)
        code, out, _ = self.go("server", "blas", "native", "--reason", "back")
        self.assertEqual(1, code)
        self.assertIn("refused: did not start: OpenBLAS failed its self-test", out)

    def test_a_reason_is_required(self):
        code, _, err = self.go("server", "blas", "java")
        self.assertEqual(2, code)
        self.assertIn("give --reason", err)


if __name__ == "__main__":
    unittest.main()
