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

"""Tests of `drishti.py bi statement` (tools/bistatement.py) against an in-process stand-in for the server."""
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

PREVIEW = {"title": "Monthly statement", "pageCount": 2, "period": "September 2026", "rows": 80, "paper": {"size": "A4", "orientation": "portrait"},
           "pages": [{"number": n, "ops": [{"t": "text", "role": "header", "text": "Monthly statement"}, {"t": "text", "role": "footer", "text": f"Page {n} of 2"},
                                           {"t": "text", "role": "cell", "text": "70001"}]} for n in (1, 2)]}


class BiStatement(unittest.TestCase):
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
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="drishti-bistatement-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_preview_sends_the_report_date_and_parameters_and_prints_each_page(self):
        FakeServer.routes[("POST", "/api/v1/bi/reports/statement/preview")] = (200, PREVIEW)
        code, out, _ = run("bi", "statement", "preview", "file:report:monthly-statement", "--date", "2026-10-01", "--param", "breakdown=trade.PORTFOLIO",
                           "--param", "shock=0.5", env=self.env)
        self.assertEqual(0, code)
        self.assertIn("Monthly statement: 2 pages, A4 portrait, September 2026, 80 rows", out)
        self.assertIn("page 2: Monthly statement  ...  Page 2 of 2  (1 cells)", out)
        sent = json.loads(FakeServer.requests[-1][5])
        self.assertEqual({"report": "file:report:monthly-statement", "page": "", "date": "2026-10-01", "state": {"params": {"breakdown": "trade.PORTFOLIO", "shock": 0.5}}}, sent)

    def test_file_sends_draft_yaml_and_writes_what_the_server_made(self):
        f = self.tmp / "monthly.report.yaml"
        f.write_text("report: monthly\nmodel: m@1\n", encoding="utf-8")
        FakeServer.routes[("POST", "/api/v1/bi/reports/statement/file")] = (200, b"%PDF-1.4 fake")
        out_file = self.tmp / "september.pdf"
        code, out, _ = run("bi", "statement", "file", "--file", str(f), "-o", str(out_file), env=self.env)
        self.assertEqual(0, code, out)
        self.assertEqual(b"%PDF-1.4 fake", out_file.read_bytes())
        sent = json.loads(FakeServer.requests[-1][5])
        self.assertEqual(("pdf", "report: monthly\nmodel: m@1\n"), (sent["format"], sent["yaml"]))

    def test_without_a_report_or_a_file_it_says_so(self):
        code, _, err = run("bi", "statement", "preview", env=self.env)
        self.assertEqual(2, code)
        self.assertIn("name the report", err)


if __name__ == "__main__":
    unittest.main()
