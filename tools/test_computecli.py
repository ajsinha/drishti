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

"""`drishti.py compute`: parameters given as name=value, the answer printed in a few lines with its exit code, and the commands
reaching the right paths with the right bodies (a fake Api). Stdlib only.

    python3 -m unittest -q tools/test_computecli.py
"""
from __future__ import annotations

import argparse
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("computecli", Path(__file__).resolve().parent / "computecli.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


class FakeApi:
    calls = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path, body))
        if path.startswith("/api/v1/compute/") and method == "POST" and "events" not in path:
            return {"state": "refused" if body["params"].get("delay-ms") == 900 else "ready", "requestId": "req-1", "connector": "c", "id": "B",
                    "result": None if body["params"].get("delay-ms") == 900 else {"SUM": 6, "STAMP": {"pin": "pin-1", "engine": "java:x"}},
                    "message": "delay-ms for k is 900" if body["params"].get("delay-ms") == 900 else None}
        return {"ok": True}


def parser():
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    C.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": lambda x: None})
    return root


class ComputeCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_parameters_are_name_value_and_whole_numbers_stay_numbers(self):
        self.assertEqual(C.pairs(["delay-ms=20", "scope=DESK:RATES", "n=-3"], "--param", Err), {"delay-ms": 20, "scope": "DESK:RATES", "n": -3})
        with self.assertRaises(Err):
            C.pairs(["nope"], "--param", Err)

    def test_run_asks_the_connector_and_exits_one_when_refused(self):
        a = parser().parse_args(["compute", "run", "c", "k", "B", "--param", "x=3"])
        self.assertEqual(a.func(a, []), 0)
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/compute/c/k", {"id": "B", "params": {"x": 3}, "wait": 5000}))
        a = parser().parse_args(["compute", "run", "c", "k", "B", "--param", "delay-ms=900"])
        self.assertEqual(a.func(a, []), 1)

    def test_an_event_carries_its_payload_and_watermark(self):
        a = parser().parse_args(["compute", "event", "write-back", "chg-1", "--kind", "trade", "--entity", "T-1", "--payload", "BOOK=B1",
                                 "--watermark", "L3=12"])
        a.func(a, [])
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/compute/events")
        self.assertEqual(FakeApi.calls[-1][2]["payload"], {"BOOK": "B1"})
        self.assertEqual(FakeApi.calls[-1][2]["watermark"], {"L3": 12})

    def test_an_answer_is_printed_in_a_few_lines(self):
        out = []
        code = C.render_answer({"state": "ready", "connector": "c", "id": "B~x=3", "requestId": "req-1",
                                "result": {"SUM": 6, "RESULTS": [], "STAMP": {"pin": "pin-1", "engine": "java:x", "computedAt": "t", "resultHash": "h"}}}, out.append)
        self.assertEqual(code, 0)
        self.assertEqual(out[0], "c B~x=3  READY  (req-1)")
        self.assertIn("  SUM          6", out)
        self.assertTrue(out[-1].startswith("  computed t by java:x, pin pin-1"))


if __name__ == "__main__":
    unittest.main()
