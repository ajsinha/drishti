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

"""Tests of `drishti.py bi expr|migrate|sql` (tools/bilang.py) against an in-process stand-in for the server, and that every DEL example of the
function catalogue is a valid Python expression (docs/architecture/BI_DESIGN.md section 3.3: DEL v2 parses with ast.parse(text, mode="eval"))."""
import ast
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

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOGUE = ROOT / "drishti-server" / "src" / "main" / "resources" / "bi" / "expr" / "functions.yaml"


class DelIsPython(unittest.TestCase):
    def test_every_catalogue_example_is_a_python_expression(self):
        import yaml
        cat = yaml.safe_load(CATALOGUE.read_text(encoding="utf-8"))
        n = 0
        for f in cat["functions"]:
            for e in f["examples"]:
                ast.parse(e["expr"], mode="eval")
                n += 1
        self.assertGreater(n, 100)


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

    def test_convert_prints_the_del_text(self):
        FakeServer.routes[("POST", "/api/v1/bi/expr/convert")] = (200, {"expr": "[MTM] / [Trades]", "converted": "measure('MTM') / measure('Trades')", "changed": True, "problems": []})
        code, out, _ = self.go("bi", "expr", "convert", "[MTM] / [Trades]")
        self.assertEqual(0, code)
        self.assertEqual("measure('MTM') / measure('Trades')", out.strip())

    def test_migrate_writes_only_with_write_and_check_fails_on_change(self):
        f = self.tmp / "x.dataset.yaml"
        f.write_text("measures:\n  R: { expr: \"[A] / [B]\" }\n", encoding="utf-8")
        (self.tmp / "notes.yaml").write_text("not an item\n", encoding="utf-8")
        new = "expressions: 2\nmeasures:\n  R: { expr: \"measure('A') / measure('B')\" }\n"
        FakeServer.routes[("POST", "/api/v1/bi/expr/convert")] = (200, {"yaml": new, "expressions": 1, "changed": True,
                                                                         "changes": [{"path": "measures.R.expr", "from": "[A] / [B]", "to": "measure('A') / measure('B')"}], "problems": []})
        code, out, _ = self.go("bi", "migrate", "--expressions", str(self.tmp))
        self.assertEqual(0, code)
        self.assertIn("would change (1 of 1", out)
        self.assertIn("[A] / [B]", f.read_text())
        self.assertEqual(1, len(FakeServer.requests))                           # notes.yaml is not a BI item
        code, _, _ = self.go("bi", "migrate", "--expressions", str(self.tmp), "--check")
        self.assertEqual(1, code)
        code, out, _ = self.go("bi", "migrate", "--expressions", str(f), "--write")
        self.assertEqual(0, code)
        self.assertEqual(new, f.read_text())

    def test_migrate_without_files_works_on_the_stored_items(self):
        FakeServer.routes[("POST", "/api/v1/bi/migrate/expressions")] = (200, {
            "step": "expressions", "dryRun": True, "scanned": 4, "wouldConvert": 1, "problems": 1, "items": [
                {"id": "it-1", "title": "Old trades", "kind": "dataset", "workspace": "ws", "status": "published", "state": "would convert",
                 "draft": [{"path": "measures.Per.expr", "from": "[MTM] / [Live]", "to": "measure('MTM') / measure('Live')"}], "published": [], "problems": []},
                {"id": "it-2", "title": "Broken", "kind": "dataset", "workspace": "ws", "status": "draft", "state": "problem", "draft": [], "published": [],
                 "problems": [{"path": "measures.X.expr", "from": "sum(a", "problem": "DRS-9127 expected ')'"}]}]})
        code, out, _ = self.go("bi", "migrate", "--expressions", "--dry-run")
        self.assertEqual(1, code)                                               # an item cannot be converted
        self.assertEqual({"dryRun": True}, json.loads(FakeServer.requests[0][5]))
        self.assertIn("dataset 'Old trades' (it-1, published): would convert", out)
        self.assertIn("draft measures.Per.expr: [MTM] / [Live]", out)
        self.assertIn("measures.X.expr: NOT CONVERTED: DRS-9127", out)
        self.assertIn("4 stored item(s) scanned, 1 would be converted, 1 cannot be converted", out)
        code, _, err = self.go("bi", "migrate", "--expressions", "--write")
        self.assertEqual(2, code)
        self.assertIn("--write and --check are for FILEs", err)

    def test_migrate_models_writes_the_converted_folder_and_removes_old_datasets(self):
        (self.tmp / "datasets").mkdir()
        (self.tmp / "reports").mkdir()
        (self.tmp / "datasets" / "x.dataset.yaml").write_text("rachana: 1\ndataset: x\n", encoding="utf-8")
        (self.tmp / "reports" / "r.report.yaml").write_text("rachana: 1\nreport: r\ndataset: x\n", encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/migrate/models/convert")] = (200, {
            "writes": {"datasets/x-t.dataset.yaml": "rachana: 2\n", "models/x.model.yaml": "rachana: 2\nmodel: x\n", "reports/r.report.yaml": "rachana: 2\nmodel: x@1\n"},
            "removes": ["datasets/x.dataset.yaml"], "converted": 2, "problems": 0, "report": "# report\n",
            "items": [{"key": "datasets/x.dataset.yaml", "kind": "dataset", "name": "x", "state": "converted", "became": "model x@1 over [x-t]",
                       "outputs": [{"kind": "dataset", "name": "x-t", "path": "datasets/x-t.dataset.yaml"}], "manual": [], "problem": None}]})
        code, out, _ = self.go("bi", "migrate", "--models", str(self.tmp), "--dry-run")
        self.assertEqual(0, code)
        self.assertIn("would write 3 file(s)", out)
        self.assertTrue((self.tmp / "datasets" / "x.dataset.yaml").exists())
        sent = json.loads(FakeServer.requests[0][5])["files"]
        self.assertEqual({"datasets/x.dataset.yaml", "reports/r.report.yaml"}, {f["path"] for f in sent})
        report = self.tmp / "plan.md"
        code, out, _ = self.go("bi", "migrate", "--models", str(self.tmp), "--report", str(report))
        self.assertEqual(0, code)
        self.assertIn("removed 1 old dataset file(s)", out)
        self.assertFalse((self.tmp / "datasets" / "x.dataset.yaml").exists())
        self.assertEqual("rachana: 2\nmodel: x@1\n", (self.tmp / "reports" / "r.report.yaml").read_text())
        self.assertEqual("# report\n", report.read_text())

    def test_sql_prints_a_table(self):
        FakeServer.routes[("POST", "/api/v1/bi/sql")] = (200, {"columns": [{"name": "desk"}, {"name": "mtm"}], "rows": [["RATES", 1234.5], ["FX", None]],
                                                              "meta": {"shape": "mq", "plan": "p_1"}})
        code, out, _ = self.go("bi", "sql", "SELECT trades.desk AS desk, MEASURE('MTM') AS mtm FROM trades GROUP BY trades.desk", "--model", "m@1")
        self.assertEqual(0, code)
        self.assertIn("RATES", out)
        self.assertIn("1,234.50", out)
        self.assertIn("2 row(s)", out)
        self.assertEqual("m@1", json.loads(FakeServer.requests[0][5])["model"])


if __name__ == "__main__":
    unittest.main()
