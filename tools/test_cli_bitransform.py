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

"""Tests of `drishti.py bi transform input|snippets|snippet` (tools/bitransform.py) against an in-process stand-in for the server."""
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

DATASET = """rachana: 2
dataset: fx-book-value
source:
  python: fx-book-value
  inputs:
    trades: { kind: trade, connector: prep-store, fields: [TRADE_ID, PORTFOLIO, QUANTITY] }
grain: { keys: [CURRENCY] }
"""
ANSWER = {"model": "dataset:pack.trading.trade", "kind": True, "columns": [{"name": "TRADE_ID", "type": "text"}, {"name": "PORTFOLIO", "type": "text"},
                                                                           {"name": "QUANTITY", "type": "float64"}],
          "rows": [["T1", "BOOK-FX-1", 1000.0], ["T2", "BOOK-FX-1", None], ["T3", "BOOK-FI-1", 3000.0]], "rowCount": 3, "masked": ["QUANTITY"], "asOf": "2026-10-08"}


class BiTransform(unittest.TestCase):
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
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-bitransform-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_input_asks_each_input_as_you_and_profiles_it(self):
        f = self.tmp / "fx.dataset.yaml"
        f.write_text(DATASET, encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/python/input")] = (200, ANSWER)
        code, out, _ = run("bi", "transform", "input", f, "--as-of", "2026-10-08", "--limit", "50", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("trades: 3 rows of dataset:pack.trading.trade as of 2026-10-08; masked for you: QUANTITY", out)
        self.assertRegex(out, r"QUANTITY\s+float64\s+empty\s+1\s+distinct\s+2\s+1,000 \.\. 3,000")
        self.assertIn("T1 | BOOK-FX-1 | 1000.0", out)
        sent = json.loads([r for r in FakeServer.requests if r[1] == "/api/v1/bi/python/input"][0][5])
        self.assertEqual({"input": {"kind": "trade", "connector": "prep-store", "fields": ["TRADE_ID", "PORTFOLIO", "QUANTITY"]}, "limit": 50, "asOf": "2026-10-08"}, sent)

    def test_input_refuses_a_file_that_is_not_a_python_dataset(self):
        f = self.tmp / "x.dataset.yaml"
        f.write_text("rachana: 2\ndataset: x\nsource: { kind: trade }\n", encoding="utf-8")
        code, _, err = run("bi", "transform", "input", f, env=self.env)
        self.assertNotEqual(0, code)
        self.assertIn("not a Python dataset", err)

    def test_snippets_list_and_a_snippet_writes_values_as_literals(self):
        code, out, _ = run("bi", "transform", "snippets", env=self.env)
        self.assertEqual(0, code)
        for sid in ("asof-fx", "asof-prices", "unpivot", "append", "type-fix-number", "business-date"):
            self.assertIn(sid, out)
        code, out, _ = run("bi", "transform", "snippet", "filter-values", "column=PORTFOLIO", "values=BOOK-FX-1,BOOK-FX-2", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("df = df[df['PORTFOLIO'].isin(['BOOK-FX-1', 'BOOK-FX-2']) == True]", out)
        code, _, err = run("bi", "transform", "snippet", "append", "other=__import__('os')", "--inputs", "trades,rates", env=self.env)
        self.assertNotEqual(0, code)
        self.assertIn("one of the Transform's inputs", err)


if __name__ == "__main__":
    unittest.main()
