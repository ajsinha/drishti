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

"""Tests of `drishti.py server engines` and its alias `admin engines` (docs/architecture/COMPUTE_ENGINES.md section 10) against an
in-process stand-in for the server: the list, show with --sources, on and off with a reason and the flags, the refusal (exit 2) and an
engine's command answering DRS-1020 (exit 3, the server's words)."""
import json
import pathlib
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

VIEW = {"node": "drishti-main", "dir": "/srv/config/engines", "memoryBytes": 3 << 30, "installed": 6,
        "engines": [
            {"id": "var", "title": "VaR", "state": "running", "on": True, "liveOn": True, "installed": True, "memoryBytes": 3_300_000_000,
             "needs": [], "uses": [], "neededBy": ["frtb-sa"], "usedBy": [], "file": "config/engines/var.yaml", "source": "engine file",
             "alias": "", "overridden": False, "hides": {"words": ["VAR", "SMAT"]}, "threads": 6, "fileExists": True},
            {"id": "frtb-sa", "title": "FRTB-SA", "state": "off", "on": False, "liveOn": True, "installed": True, "memoryBytes": 0,
             "needs": ["var", "sensitivities"], "uses": [], "neededBy": [], "usedBy": ["mr-cube"], "file": "config/engines/frtb-sa.yaml",
             "source": "local file (old name drishti.frtb.enabled)", "alias": "drishti.frtb.enabled", "overridden": True,
             "hides": {"words": ["FRSA", "PLAY FRTB"]}, "threads": 0, "fileExists": False},
            {"id": "memory-lake", "title": "In-memory lake", "state": "off", "on": False, "liveOn": False, "installed": True, "memoryBytes": 0,
             "needs": [], "uses": [], "neededBy": [], "usedBy": [], "file": "config/engines/memory-lake.yaml", "source": "shipped copy",
             "alias": "", "overridden": False, "hides": {}, "threads": 0, "fileExists": True}],
        "cluster": {"on": True, "self": "drishti-main", "peers": {"drishti-b": {"var": "off", "frtb-sa": "running"}},
                    "available": {"var": True, "frtb-sa": True, "memory-lake": False}}}


class Engines(unittest.TestCase):
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
        FakeServer.routes[("GET", "/api/v1/admin/engines")] = (200, VIEW)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_list_prints_state_memory_needs_and_file(self):
        code, out, _ = self.go("server", "engines", "list")
        self.assertEqual(0, code)
        self.assertIn("ENGINE         STATE", out)
        self.assertRegex(out, r"var\s+running\s+3\.1 GB\s+-\s+config/engines/var.yaml")
        self.assertRegex(out, r"frtb-sa\s+off\s+-\s+var, sensitivities\s+config/engines/frtb-sa.yaml \(set by local file")
        self.assertIn("config/engines/memory-lake.yaml (restart to turn on)", out)

    def test_the_admin_alias_and_the_cluster_matrix(self):
        code, out, _ = self.go("admin", "engines", "list", "--cluster")
        self.assertEqual(0, code)
        self.assertRegex(out, r"frtb-sa\s+off\s+running\s+yes")
        self.assertRegex(out, r"memory-lake\s+off\s+-\s+no")

    def test_show_with_sources_names_the_old_name(self):
        code, out, _ = self.go("server", "engines", "show", "frtb-sa", "--sources")
        self.assertEqual(0, code)
        self.assertIn("off hides words: FRSA, PLAY FRTB", out)
        self.assertIn("the switch comes from: local file (old name drishti.frtb.enabled) (old name drishti.frtb.enabled, DRS-1024)", out)
        self.assertIn("not written yet", out)

    def test_on_with_needs_sends_one_batch_and_says_the_outcome(self):
        FakeServer.routes[("POST", "/api/v1/admin/engines")] = (200, {**VIEW, "outcomes": [
            {"engine": "var", "on": True, "outcome": "unchanged"}, {"engine": "frtb-sa", "on": True, "outcome": "live"}]})
        code, out, _ = self.go("server", "engines", "on", "frtb-sa", "--with-needs", "--reason", "capital desk asked")
        self.assertEqual(0, code, out)
        self.assertIn("var already running; frtb-sa on (live). Written to config/engines/frtb-sa.yaml.", out)
        body = json.loads(FakeServer.requests[-1][5])
        self.assertEqual({"reason": "capital desk asked", "node": "", "changes": [{"engine": "frtb-sa", "on": True}],
                          "withNeeds": True, "withDependents": False}, body)

    def test_a_refusal_exits_2_in_the_servers_words(self):
        FakeServer.routes[("POST", "/api/v1/admin/engines")] = (409, {"code": "DRS-1021", "detail": "DRS-1021 VaR is needed by FRTB-SA on this server."})
        code, _, err = self.go("server", "engines", "off", "var", "--reason", "moved")
        self.assertEqual(2, code)
        self.assertIn("refused (DRS-1021): VaR is needed by FRTB-SA on this server.", err)
        code, _, err = self.go("server", "engines", "off", "var")
        self.assertEqual(2, code)
        self.assertIn("give --reason", err)

    def test_an_engines_command_running_nowhere_exits_3(self):
        FakeServer.routes[("GET", "/api/v1/risk/frtb/sa")] = (404, {"code": "DRS-1020", "engine": "frtb-sa",
                                                              "detail": "DRS-1020 FRTB-SA is not running on this server, and no other server of this cluster runs it."})
        code, _, err = self.go("frtb", "sa", "EXAMPLE")
        self.assertEqual(3, code, err)
        self.assertIn("FRTB-SA is not running on this server", err)


if __name__ == "__main__":
    unittest.main()
