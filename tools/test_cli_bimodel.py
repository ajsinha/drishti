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

"""Tests of `drishti.py bi model|mq` (tools/bimodel.py) against an in-process stand-in for the server."""
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

DESCRIBED = {"model": {"id": "bi-m1", "name": "trading-risk", "version": "1.0.0"}, "title": "Trading risk",
             "datasets": [{"alias": "trades", "dataset": "pack.trading.trade", "version": "1.0.0", "role": "fact",
                           "fields": [{"field": "trades.mtm", "type": "decimal", "meaning": "amount", "display": "MTM", "folder": "Risk"},
                                      {"field": "trades.book", "type": "string", "role": "dimension", "masked": True}]}],
             "measures": [{"name": "MTM", "expr": "sum(trades.mtm)"}, {"name": "Count of trades", "expr": "count(trades)", "implicit": True}],
             "perspectives": {"desk-view": {"fields": ["trades.desk"], "measures": ["MTM"]}}}


class BiModel(unittest.TestCase):
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
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-bimodel-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_list_keeps_a_kind(self):
        FakeServer.routes[("GET", "/api/v1/bi/models")] = (200, {"models": [{"query": "trading-risk@1", "kind": "model", "title": "Trading risk", "version": "1.0.0"},
                                                                           {"query": "dataset:pack.trading.trade", "kind": "implicit", "title": "Trade"}]})
        code, out, _ = self.go("bi", "model", "list", "--kind", "model")
        self.assertEqual(0, code)
        self.assertIn("trading-risk@1", out)
        self.assertNotIn("pack.trading.trade", out)

    def test_describe_shows_fields_with_their_properties_and_the_perspectives(self):
        FakeServer.routes[("GET", "/api/v1/bi/models/trading-risk%401/describe")] = (200, DESCRIBED)
        code, out, _ = self.go("bi", "model", "describe", "trading-risk@1")
        self.assertEqual(0, code)
        self.assertIn("as MTM, folder Risk", out)
        self.assertIn("masked", out)
        self.assertIn("Count of trades", out)
        self.assertIn("(default)", out)
        self.assertIn("perspective desk-view: trades.desk, MTM", out)

    def test_check_prints_problems_and_exits_1(self):
        f = self.tmp / "m.model.yaml"
        f.write_text("rachana: 2\nmodel: m\n", encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/models/validate")] = (200, {"kind": "model", "name": "m", "valid": False, "problems": [
            {"level": "error", "code": "DRS-9120", "path": "datasets", "message": "a model names its datasets"}], "exposures": [{"role": "desk-head", "dataset": "desks"}]})
        code, out, _ = self.go("bi", "model", "check", str(f))
        self.assertEqual(1, code)
        self.assertIn("DRS-9120  datasets: a model names its datasets", out)
        self.assertIn("safeguard: the role desk-head sees every row of desks", out)
        self.assertEqual("rachana: 2\nmodel: m\n", json.loads(FakeServer.requests[0][5])["yaml"])

    def test_mq_builds_the_query_from_the_options_with_a_local_measure(self):
        FakeServer.routes[("POST", "/api/v1/bi/mq")] = (200, {"columns": [{"name": "desks.name"}, {"name": "Open MTM"}], "rows": [["Rates", 1234.5]],
                                                             "meta": {"plan": "p1", "asOf": "2026-10-09"}})
        code, out, _ = self.go("bi", "mq", "--model", "trading-risk@1", "--select", "desks.name", "--measures", "Open MTM",
                               "--define", "Open MTM=sum(trades.mtm, where=trades.status == 'Live')", "--where", "trades.currency=USD", "--limit", "5")
        self.assertEqual(0, code)
        self.assertIn("1,234.50", out)
        self.assertIn("1 row(s)  as of 2026-10-09  plan p1", out)
        self.assertEqual({"mq": 1, "model": "trading-risk@1", "select": {"fields": ["desks.name"], "measures": ["Open MTM"]},
                          "define": {"Open MTM": "sum(trades.mtm, where=trades.status == 'Live')"},
                          "where": [{"field": "trades.currency", "op": "eq", "value": "USD"}], "limit": 5}, json.loads(FakeServer.requests[0][5]))

    def test_mq_sorts_by_a_field_or_measure_descending_with_a_minus(self):
        FakeServer.routes[("POST", "/api/v1/bi/mq")] = (200, {"columns": [{"name": "desks.name"}, {"name": "MTM"}], "rows": [], "meta": {}})
        code, _, _ = self.go("bi", "mq", "--model", "trading-risk@1", "--select", "desks.name", "--measures", "MTM", "--sort=-MTM", "--sort", "desks.name")
        self.assertEqual(0, code)
        self.assertEqual([{"by": "MTM", "desc": True}, {"by": "desks.name", "desc": False}], json.loads(FakeServer.requests[0][5])["sort"])
        code, _, err = self.go("bi", "mq", "--model", "trading-risk@1", "--measures", "MTM", "--sort=-")
        self.assertEqual(2, code)

    def test_mq_reads_a_yaml_file(self):
        f = self.tmp / "q.yaml"
        f.write_text("mq: 1\nmodel: trading-risk@1\nselect: {fields: [desks.name], measures: [MTM]}\n", encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/mq")] = (200, {"columns": [{"name": "desks.name"}, {"name": "MTM"}], "rows": [], "meta": {}})
        code, out, _ = self.go("bi", "mq", str(f))
        self.assertEqual(0, code)
        self.assertEqual("trading-risk@1", json.loads(FakeServer.requests[0][5])["model"])


if __name__ == "__main__":
    unittest.main()
