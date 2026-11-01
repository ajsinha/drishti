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

"""`drishti.py data get`: no dataset lists the datasets; --count asks the count with every --where line; a download streams the answer of a
real (local, in-process) server into the file, named <dataset>_<date>.jsonl.gz by default, and a refusal leaves no file. Stdlib only.

    python3 -m unittest -q tools/test_datacli.py
"""
from __future__ import annotations

import argparse
import gzip
import http.server
import importlib.util
import json
import os
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("datacli", Path(__file__).resolve().parent / "datacli.py")
D = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(D)

ROWS = gzip.compress(b'{"TRADE_ID":"1","MTM":5}\n{"TRADE_ID":"2","MTM":7}\n')


class Err(Exception):
    def __init__(self, msg, code=2):
        super().__init__(msg)
        self.code = code


class ApiErr(Err):
    def __init__(self, status, code, detail):
        super().__init__(f"{status} {code} {detail}", 1)


class Handler(http.server.BaseHTTPRequestHandler):
    seen: list = []

    def do_GET(self):  # noqa: N802
        Handler.seen.append(self.path)
        if "/rows" in self.path and "refused" not in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip")
            self.send_header("X-Drishti-Selection", "From t on 2026-09-30: 2 columns, every row.")
            self.end_headers()
            self.wfile.write(ROWS)
            return
        body = json.dumps({"code": "DRS-5002", "detail": "refused is marked internal only"}).encode()
        self.send_response(403)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


class FakeApi:
    calls: list = []
    base = ""

    def __init__(self):
        self.token, self.user, self.timeout = "t", None, 10

    @classmethod
    def from_args(cls, a):
        api = cls()
        api.base = FakeApi.base
        return api

    def json(self, method, path, body=None):
        FakeApi.calls.append(path)
        if path == "/api/v1/data":
            return {"datasets": [{"kind": "trade-valuation", "pack": "market-risk", "format": "Delta", "dates": 2, "dated": True,
                                  "first": "2026-09-29", "last": "2026-09-30"},
                                 {"kind": "economy", "pack": "world-economy", "format": "JSON Lines", "dated": False, "internalOnly": True, "refused": True}]}
        return {"words": "From t: 2 columns.", "sql": "SELECT A, B\nFROM t", "rows": 412, "total": 12076, "complete": True, "gzipBytes": 31000,
                "pushdown": True}


def run(*argv):
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    d = root.add_subparsers(dest="cmd")

    def add(sub, name, func, help_, parents=(), epilog=None):
        p = sub.add_parser(name, parents=list(parents), epilog=epilog)
        p.set_defaults(func=func)
        return p
    D.register(d, add, srv, {"Api": FakeApi, "ApiError": ApiErr, "CliError": Err, "say_json": print, "load_date": lambda s: s,
                             "q": lambda s: urllib.parse.quote(s, safe="")})
    a = root.parse_args(["get", *argv])
    return a.func(a, [])


class DataGet(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        FakeApi.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def setUp(self):
        FakeApi.calls, Handler.seen = [], []
        self.cwd = os.getcwd()
        self.dir = tempfile.TemporaryDirectory()
        os.chdir(self.dir.name)

    def tearDown(self):
        os.chdir(self.cwd)
        self.dir.cleanup()

    def test_no_dataset_lists_them_and_count_carries_every_line(self):
        self.assertEqual(run(), 0)
        self.assertEqual(FakeApi.calls, ["/api/v1/data"])
        self.assertEqual(run("trade-valuation", "--date", "2026-09-30", "--where", "DESK = DESK-COMM", "--where", "MTM > 1000000", "--count"), 0)
        path, query = FakeApi.calls[-1].split("?")
        self.assertEqual(path, "/api/v1/data/trade-valuation/count")
        self.assertEqual(urllib.parse.parse_qs(query), {"date": ["2026-09-30"], "where": ["DESK = DESK-COMM", "MTM > 1000000"]})

    def test_a_download_streams_into_the_named_file_and_a_refusal_leaves_none(self):
        self.assertEqual(run("trade-valuation", "--date", "2026-09-30", "--columns", "TRADE_ID,MTM", "--where", "MTM > 1"), 0)
        out = Path("trade-valuation_2026-09-30.jsonl.gz")
        self.assertEqual(gzip.decompress(out.read_bytes()).decode().splitlines(), ['{"TRADE_ID":"1","MTM":5}', '{"TRADE_ID":"2","MTM":7}'])
        self.assertTrue(Handler.seen[-1].startswith("/api/v1/data/trade-valuation/rows?date=2026-09-30&columns=TRADE_ID%2CMTM&where=MTM+%3E+1"))
        with self.assertRaises(ApiErr):
            run("refused", "-o", "economy.jsonl.gz")
        self.assertEqual(sorted(p.name for p in Path(".").iterdir()), ["trade-valuation_2026-09-30.jsonl.gz"])


if __name__ == "__main__":
    unittest.main()
