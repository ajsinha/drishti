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

"""Busy protection in drishti.py (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): a busy refusal (503 DRS-9501 to DRS-9505, Retry-After) is
said in the server's own words, the same the console shows; with --wait (or DRISHTI_WAIT_BUSY=1) it is waited for and tried again, three
times at most. An in-process server stands in for Drishti. Stdlib only.

    python3 -m unittest -q tools/test_cli_busy.py
"""
from __future__ import annotations

import contextlib
import io
import json
import os
import pathlib
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import drishti as D  # noqa: E402

BUSY = {"title": "The download did not start", "status": 503, "code": "DRS-9503", "busy": True, "work": "download", "retryAfterSeconds": 1,
        "because": "the server is short of memory: 93% in use after clean-up (new heavy work waits over 85%)",
        "detail": "DRS-9503 The download did not start: the server is short of memory: 93% in use after clean-up (new heavy work waits over "
                  "85%). Your selection is kept; try again in 1 second."}


class Busy(BaseHTTPRequestHandler):
    refusals = 0          # how many more requests are refused

    def do_GET(self):  # noqa: N802
        if Busy.refusals > 0:
            Busy.refusals -= 1
            body = json.dumps(BUSY).encode()
            self.send_response(503)
            self.send_header("Content-Type", "application/problem+json")
            self.send_header("Retry-After", "1")
            self.end_headers()
            self.wfile.write(body)
            return
        if "/rows" in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip")
            self.end_headers()
            self.wfile.write(b"\x1f\x8b rows")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok": true}')

    def log_message(self, *a):
        pass


def run(*argv, env=None):
    out, err = io.StringIO(), io.StringIO()
    saved = {k: os.environ.get(k) for k in (env or {})}
    os.environ.update(env or {})
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = D.main([str(a) for a in argv])
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
    return code, out.getvalue(), err.getvalue()


class BusyCli(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Busy)
        threading.Thread(target=cls.httpd.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()

    def test_a_busy_refusal_says_the_servers_words_and_how_to_wait(self):
        Busy.refusals = 1
        with self.assertRaises(D.ApiError) as c:
            D.Api(self.base).json("GET", "/api/v1/data")
        msg = str(c.exception)
        self.assertTrue(msg.startswith("The download did not start: the server is short of memory: 93% in use after clean-up"), msg)
        self.assertIn("(DRS-9503; add --wait", msg)
        self.assertNotIn("DRS-9503 The", msg)
        self.assertEqual(c.exception.code, 1)                         # the exit code, not the DRS code
        self.assertEqual(c.exception.retry_after, 1)

    def test_waiting_tries_again_after_retry_after_three_times_at_most(self):
        Busy.refusals = 2
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            self.assertEqual(D.Api(self.base, wait=True).json("GET", "/api/v1/data"), {"ok": True})
        self.assertEqual(err.getvalue().count("The server is short of memory: 93% in use after clean-up (new heavy work waits over 85%); "
                                              "trying again in 1 s (DRS-9503) ..."), 2)
        Busy.refusals = 9
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(D.ApiError):
            D.Api(self.base, wait=True).json("GET", "/api/v1/data")
        self.assertEqual(Busy.refusals, 5, "three tries after the first refusal, then it gives up")
        Busy.refusals = 0

    def test_data_get_wait_downloads_once_there_is_room(self):
        Busy.refusals = 1
        with tempfile.TemporaryDirectory() as tmp:
            out = pathlib.Path(tmp, "t.jsonl.gz")
            code, _, err = run("data", "get", "trade-valuation", "--date", "2026-09-30", "-o", out, "--wait", "--server", self.base)
            self.assertEqual(code, 0, err)
            self.assertIn("trying again in 1 s (DRS-9503) ...", err)
            self.assertTrue(out.is_file())
        Busy.refusals = 1
        with tempfile.TemporaryDirectory() as tmp:
            code, _, err = run("data", "get", "trade-valuation", "-o", pathlib.Path(tmp, "t.jsonl.gz"), "--server", self.base)
            self.assertEqual(code, 1)
            self.assertIn("drishti: The download did not start: the server is short of memory", err)
            self.assertFalse(pathlib.Path(tmp, "t.jsonl.gz").exists())
        Busy.refusals = 0


if __name__ == "__main__":
    unittest.main()
