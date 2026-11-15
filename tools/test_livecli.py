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

"""`drishti.py live` against a fake server: the list in Health's words, a kind's rules, on/off as a binding proposal (four eyes), pause,
apply now and rejects (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 9.1)."""
from __future__ import annotations

import argparse
import contextlib
import importlib.util
import io
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("livecli", HERE / "livecli.py")
X = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(X)

FEEDS = [
    {"connector": "trade-endur-live", "plugin": "kafka", "companionOf": "trade-endur", "kinds": ["trade"], "state": "LIVE", "attention": "ok",
     "perMinute": 412, "rows": 1840, "counts": {"applied": 9000, "rejected.contract": 3},
     "reconcileLine": "load of 2026-11-13 (trade): 1800 absorbed, 40 carried, 0 orphans"},
    {"connector": "trade-wss-live", "plugin": "activemq", "companionOf": "trade-wss", "kinds": ["trade"], "state": "OFF", "attention": "ok"},
]
RULES = {"trade": {"Applied": "at once over the lake's rows", "Newer means": "a higher TRADE_VERSION, then a later SOURCE_UPDATE_TIME"}}


class Err(Exception):
    def __init__(self, msg, code):
        super().__init__(msg)
        self.code = code


class FakeApi:
    calls: list = []

    @classmethod
    def from_args(cls, a):
        return cls()

    def json(self, method, path, body=None):
        FakeApi.calls.append((method, path, body))
        if path == "/api/v1/admin/live":
            return {"feeds": FEEDS}
        if path.startswith("/api/v1/admin/live?kind="):
            k = path.split("=", 1)[1]
            return {"feeds": [f for f in FEEDS if k in f["kinds"]]}
        if path == "/api/v1/admin/live/trade-endur-live":
            return dict(FEEDS[0], rules=RULES)
        if path.endswith("/pause"):
            return dict(FEEDS[0], state="PAUSED", paused={"by": "ops-1", "skips": True})
        if path == "/api/v1/admin/bindings":
            return {"kinds": [{"kind": "trade", "read": ["trade-endur", "trade-wss"], "binding": None}]}
        if path == "/api/v1/admin/bindings/proposals":
            return {"id": "P-7", "kind": body["kind"], "binding": body["binding"]}
        if "/rejects" in path:
            return {"rejects": ['{"kind":"trade","code":"DRS-1065"}']}
        raise AssertionError(path)


def run(*argv) -> tuple[int, str, object]:
    srv = argparse.ArgumentParser(add_help=False)
    srv.add_argument("--json", action="store_true")
    root = argparse.ArgumentParser()
    groups = root.add_subparsers(dest="group")
    said = []
    X.register(groups, srv, {"Api": FakeApi, "CliError": Err, "say_json": said.append})
    a = root.parse_args(["live", *argv])
    FakeApi.calls = []
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = a.func(a, [])
    return code, out.getvalue(), said[0] if said else None


class LiveCli(unittest.TestCase):

    def test_the_list_says_each_feed_in_healths_words(self):
        code, out, _ = run("list")
        self.assertEqual(code, 0)
        self.assertIn("trade-endur-live", out)
        self.assertIn("LIVE", out)
        self.assertIn("412", out)
        self.assertIn("last reconcile: load of 2026-11-13", out)

    def test_show_gives_the_kinds_rules_in_words(self):
        _, out, _ = run("show", "trade")
        self.assertIn("Newer means", out)
        self.assertIn("TRADE_VERSION", out)

    def test_on_is_a_binding_proposal_keeping_the_read(self):
        code, out, _ = run("on", "trade", "--connector", "trade-wss-live", "--reason", "WSS go-live")
        self.assertEqual(code, 0)
        posted = [c for c in FakeApi.calls if c[1] == "/api/v1/admin/bindings/proposals"][0][2]
        self.assertEqual(posted["binding"], {"read": ["trade-endur", "trade-wss"], "live": ["trade-endur-live", "trade-wss-live"]})
        self.assertIn("P-7", out)

    def test_off_without_a_reason_is_refused(self):
        with self.assertRaises(Err) as e:
            run("off", "trade")
        self.assertEqual(e.exception.code, 2)

    def test_off_of_one_keeps_the_others(self):
        run("off", "trade", "--connector", "trade-endur-live", "--reason", "Endur cut-over")
        posted = [c for c in FakeApi.calls if c[1] == "/api/v1/admin/bindings/proposals"][0][2]
        self.assertEqual(posted["binding"], {"read": ["trade-endur", "trade-wss"]})

    def test_a_proposal_keeps_apply_both(self):
        b = X.proposal({"binding": {"read": ["r"], "live": {"connectors": ["a"], "apply": "both"}}}, [{"connector": "b", "state": "LIVE"}], True, ["b"])
        self.assertEqual(b["live"], {"connectors": ["a", "b"], "apply": "both"})

    def test_pause_says_what_happens_to_the_messages(self):
        _, out, _ = run("pause", "trade-endur-live")
        self.assertIn("PAUSED", out)
        self.assertIn("skipped", out)

    def test_apply_now_of_a_kind_nothing_lands_is_refused(self):
        with self.assertRaises(Err):
            run("apply-now", "trade")

    def test_rejects_name_their_log(self):
        _, out, _ = run("rejects", "trade")
        self.assertIn("DRS-1065", out)
        self.assertIn("live/rejects/trade-endur-live", out)


if __name__ == "__main__":
    unittest.main()
