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

"""`drishti.py approvals`: each command reaches its path of the one Approvals API with the body it needs, a reject needs a reason, and
the list and the item are printed in words."""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("approvalscli", Path(__file__).resolve().parent / "approvalscli.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


ITEM = {"kind": "sutra", "id": "P-000042", "title": "book v12 (change)", "maker": "ana", "status": "pending", "due": None, "stage": None,
        "reason": "clearer title"}


class FakeApi:
    calls = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path, body))
        if path.startswith("/api/v1/approvals?"):
            return {"inbox": 2, "dueSoon": 1, "items": [{"item": dict(ITEM, kind="var-report", id="annual:FY2026:v1", due="2026-11-09"), "dueSoon": True,
                                                         "questions": 0}, {"item": ITEM, "dueSoon": False, "questions": 2}], "unavailable": []}
        if method == "GET":
            return {"label": "Sutra", "detail": {"item": ITEM, "diff": {"format": "lines", "rows": [{"op": "changed", "left": "title: A", "right": "title: B"}]},
                                                  "effect": ["views change at once"], "rules": ["four eyes"]},
                    "questions": [{"by": "rui", "at": "2026-11-07T10:00:00Z", "text": "Why?"}], "actions": ["approve", "reject", "ask"]}
        return dict(ITEM, status="approved")


def parser():
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    C.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": lambda x: None})
    return root


def run(*args):
    a = parser().parse_args(list(args))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue()


class ApprovalsCli(unittest.TestCase):
    def setUp(self):
        FakeApi.calls.clear()

    def test_the_list_is_due_soon_first_and_filtered(self):
        code, out = run("approvals", "list", "--box", "sent", "--group", "risk")
        self.assertEqual(0, code)
        self.assertEqual(FakeApi.calls[-1][1], "/api/v1/approvals?box=sent&group=risk")
        self.assertIn("2 waiting for you, 1 due soon", out)
        self.assertIn("!", out.splitlines()[1])
        self.assertIn("(2 q)", out)

    def test_an_item_shows_what_changes_its_effect_rules_and_questions(self):
        code, out = run("approvals", "show", "sutra", "P-000042")
        self.assertEqual(FakeApi.calls[-1][:2], ("GET", "/api/v1/approvals/sutra/P-000042"))
        for text in ("- title: A", "+ title: B", "views change at once", "four eyes", "? rui", "you may: approve, reject, ask"):
            self.assertIn(text, out)

    def test_decisions_reach_their_paths_with_the_comment_and_the_params(self):
        run("approvals", "approve", "function", "asha:wret@1", "--comment", "reviewed", "--param", "scope=group:rates")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/approvals/function/asha:wret@1/approve",
                                             {"comment": "reviewed", "params": {"scope": "group:rates"}}))
        run("approvals", "withdraw", "change", "chg-1")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/approvals/change/chg-1/withdraw", {"comment": "", "params": {}}))
        run("approvals", "ask", "sutra", "P-000042", "Why the new title?")
        self.assertEqual(FakeApi.calls[-1], ("POST", "/api/v1/approvals/sutra/P-000042/questions", {"text": "Why the new title?"}))
        with self.assertRaises(Err):
            run("approvals", "reject", "bi", "it-1")                       # a reason is required
        with self.assertRaises(Err):
            run("approvals", "approve", "bi", "it-1", "--param", "force")  # NAME=VALUE
        with self.assertRaises(SystemExit):
            run("approvals", "approve", "nope", "x")                       # not a kind


if __name__ == "__main__":
    unittest.main()
