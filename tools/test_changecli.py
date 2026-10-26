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

"""`drishti.py change` and `drishti.py edits`: values given as FIELD=value (numbers stay numbers), a proposal carrying what you saw, a
draft submitted by its id, decisions reaching the right paths with the right bodies, and a refused or conflicted change exiting 1 (a
fake Api). Stdlib only.

    python3 -m unittest -q tools/test_changecli.py
"""
from __future__ import annotations

import argparse
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("changecli", Path(__file__).resolve().parent / "changecli.py")
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
        if path == "/api/v1/changes" and method == "POST":
            return {"changes": [{"id": "chg-1", "kind": "trade", "entity": "MX-1", "state": "awaiting", "needed": 1}]}
        if path.startswith("/api/v1/changes/chg-bad"):
            return {"id": "chg-bad", "state": "conflicted", "stateReason": "NOTIONAL: you saw 1, it is now 2", "items": []}
        return {"changes": [], "kind": "trade", "id": "MX-1", "version": 3}


def parser():
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    C.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": lambda x: None})
    return root


def run(*args):
    a = parser().parse_args(list(args))
    return a.func(a, [])


class ChangeCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls = []

    def test_values_are_field_value_and_numbers_stay_numbers(self):
        self.assertEqual(C.values(["NOTIONAL=26000000", "RATE=3.25", "DESK=RATES-NY", "OK=true"], "--set", Err),
                         {"NOTIONAL": 26000000, "RATE": 3.25, "DESK": "RATES-NY", "OK": True})
        with self.assertRaises(Err):
            C.values(["nope"], "--set", Err)

    def test_propose_sends_what_you_saw_and_the_reason(self):
        self.assertEqual(run("change", "propose", "--kind", "trade", "--id", "MX-1", "--set", "NOTIONAL=26", "--from", "NOTIONAL=25", "--reason", "typo"), 0)
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/changes", {"kind": "trade", "entity": "MX-1", "reason": "typo", "via": "cli",
                                                                         "items": [{"field": "NOTIONAL", "from": 25, "to": 26}]}))
        with self.assertRaises(Err):
            run("change", "propose", "--kind", "trade", "--id", "MX-1")

    def test_a_draft_is_submitted_by_its_id_and_decisions_reach_their_paths(self):
        run("change", "submit", "d-4ab964fa3060", "--reason", "typo")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/changes", {"draft": "d-4ab964fa3060", "reason": "typo"}))
        run("change", "approve", "chg-1", "--note", "ok")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/changes/chg-1/approve", {"note": "ok"}))
        run("change", "reject", "chg-1", "--reason", "wrong ticket")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/changes/chg-1/reject", {"reason": "wrong ticket"}))
        run("change", "list", "--state", "awaiting", "--mine")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/changes?state=awaiting&mine=true")
        run("change", "history", "trade", "MX 1")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/entities/trade/MX%201/history")
        run("edits", "status")
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/admin/edits")

    def test_a_conflicted_change_exits_one(self):
        self.assertEqual(run("change", "show", "chg-bad"), 1)


if __name__ == "__main__":
    unittest.main()
