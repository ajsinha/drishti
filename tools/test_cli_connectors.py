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

"""Tests of `drishti.py connector list|get|apply|delete|test|plugins|enable|disable|reset|history` against an in-process fake server."""
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_pack_deploy import Fake, ServerCase, run  # noqa: E402

LIST = {"directory": "/srv/config/connectors", "watch": "WATCHING", "misnamed": ["Upper.yaml"], "deprecated": ["legacy"], "fileProblems": {}, "knownKinds": ["trade"],
        "connectors": [
            {"name": "trading-lake", "origin": "file", "plugin": "delta", "kinds": ["trade"], "state": "RUNNING", "usedBy": [{"pack": "trading", "kinds": ["trade"]}], "problems": []},
            {"name": "legacy", "origin": "application", "plugin": "file", "kinds": [], "state": "FAILED", "usedBy": [], "problems": ["no plugin named 'x'"]}]}
DETAIL = {"name": "trading-lake", "origin": "file", "plugin": "delta", "state": "RUNNING", "etag": "e1", "kinds": ["trade"], "file": "/srv/config/connectors/trading-lake.yaml",
          "usedBy": [{"pack": "trading", "kinds": ["trade"]}], "problems": [], "settings": {"root": "/lake", "s3.secret-key": "${LAKE_SECRET}"},
          "text": "plugin: delta\nsettings:\n  root: /lake\n", "history": [{"id": "20261006T090000000", "at": "2026-10-06T09:00:00Z", "bytes": 40}]}
TEST_OK = {"connector": "trading-lake", "plugin": "delta", "ok": True, "health": "UP", "ms": 9, "error": None, "hint": None, "warnings": [],
           "kinds": [{"kind": "trade", "exact": True, "note": "", "dates": [{"date": "2026-10-05", "rows": 120}]}]}
TEST_BAD = {"connector": "trading-lake", "plugin": "delta", "ok": False, "health": "DOWN", "ms": 3, "error": "PKIX path building failed", "kinds": [], "warnings": [],
            "hint": "The server's certificate is not trusted."}
PLUGINS = {"plugins": [{"name": "jdbc", "tls": True, "declared": True, "settings": [
    {"name": "url", "type": "string", "required": True, "default": None, "description": "JDBC URL", "secret": False, "group": "connection"},
    {"name": "password", "type": "string", "required": False, "default": None, "description": "Password", "secret": True, "group": "connection"}]},
    {"name": "file", "tls": False, "declared": True, "settings": []}]}


class Connector(ServerCase):
    base = "/api/v1/admin/connectors"

    def setUp(self):
        super().setUp()
        Fake.routes[("GET", self.base)] = LIST
        Fake.routes[("GET", self.base + "/trading-lake")] = DETAIL
        self.file = pathlib.Path(self.tmp.name) / "trading-lake.yaml"
        self.file.write_text("plugin: delta\nsettings:\n  root: /mnt/lake\n", encoding="utf-8")

    def test_list_shows_origin_state_users_problems_and_deprecations(self):
        code, out, _ = run("connector", "list", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("2 connector(s) in /srv/config/connectors", "trading-lake", "file", "delta", "running", "trading", "legacy", "application", "failed",
                     "no plugin named 'x'", "Upper.yaml", "deprecated: legacy"):
            self.assertIn(text, out)
        code, out, _ = run("connector", "list", "--origin", "application", "--json", "--server", self.url)
        self.assertEqual(["legacy"], [c["name"] for c in json.loads(out)["connectors"]])
        code, out, _ = run("connector", "list", "--status", "running", "--json", "--server", self.url)
        self.assertEqual(["trading-lake"], [c["name"] for c in json.loads(out)["connectors"]])

    def test_get_prints_the_effective_settings_the_file_and_the_yaml(self):
        code, out, _ = run("connector", "get", "trading-lake", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("origin file", "version e1", "used by pack trading (trade)", "root", "/lake", "${LAKE_SECRET}", "1 earlier version(s) kept"):
            self.assertIn(text, out)
        code, out, _ = run("connector", "get", "trading-lake", "--yaml", "--server", self.url)
        self.assertEqual("plugin: delta\nsettings:\n  root: /lake\n", out)
        self.assertEqual(2, run("connector", "get", "Bad_Name", "--server", self.url)[0])

    def test_apply_reads_the_version_first_and_sends_it_back_as_if_match(self):
        Fake.routes[("PUT", self.base + "/trading-lake")] = {"state": "RUNNING", "changed": ["root"], "etag": "e2", "warnings": []}
        code, out, _ = run("connector", "apply", self.file, "--server", self.url)
        self.assertEqual(0, code)
        put = self.calls("PUT")[0]
        self.assertEqual("e1", put["headers"]["If-Match"])                                       # the version just read
        self.assertEqual({"text": self.file.read_text(encoding="utf-8")}, json.loads(put["body"]))
        self.assertIn("applied trading-lake: running; changed root; version e2", out)
        run("connector", "apply", self.file, "--if-match", "zzz", "--server", self.url)
        self.assertEqual("zzz", self.calls("PUT")[1]["headers"]["If-Match"])

    def test_apply_creates_a_new_connector_without_a_version(self):
        Fake.routes[("PUT", self.base + "/new-lake")] = {"state": "RUNNING", "changed": ["(new connector)"], "etag": "n1"}
        new = pathlib.Path(self.tmp.name) / "new-lake.yaml"
        new.write_text("plugin: file\n", encoding="utf-8")
        code, out, _ = run("connector", "apply", new, "--server", self.url)                   # GET answers 404 here: nothing to match
        self.assertEqual(0, code)
        self.assertNotIn("If-Match", self.calls("PUT")[0]["headers"])

    def test_apply_with_test_first_stops_on_a_failure_and_a_refusal_exits_1(self):
        Fake.routes[("POST", self.base + "/trading-lake/test")] = TEST_BAD
        code, out, _ = run("connector", "apply", self.file, "--test-first", "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("PROBLEM: PKIX path building failed", out)
        self.assertIn("not applied: the test failed", out)
        self.assertEqual([], self.calls("PUT"))
        Fake.routes[("PUT", self.base + "/trading-lake")] = lambda h: (422, {"code": "DRS-5031", "detail": "settings.password: a credential is never written into a connector file"})
        code, _, err = run("connector", "apply", self.file, "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("never written into a connector file", err)

    def test_a_missing_file_or_a_bad_name_is_a_usage_error(self):
        self.assertEqual(2, run("connector", "apply", "/no/such/file.yaml", "--server", self.url)[0])
        bad = pathlib.Path(self.tmp.name) / "Bad_Name.yaml"
        bad.write_text("plugin: file\n", encoding="utf-8")
        self.assertEqual(2, run("connector", "apply", bad, "--server", self.url)[0])

    def test_confirm_travels_as_a_query_parameter(self):
        Fake.routes[("DELETE", self.base + "/trading-lake")] = {"name": "trading-lake", "deleted": True}
        Fake.routes[("POST", self.base + "/trading-lake/enabled")] = {"state": "DISABLED"}
        run("connector", "delete", "trading-lake", "--confirm", "--server", self.url)
        self.assertIn("confirm=true", self.calls("DELETE")[0]["path"])
        self.assertEqual("e1", self.calls("DELETE")[0]["headers"]["If-Match"])
        code, out, _ = run("connector", "disable", "trading-lake", "--confirm", "--server", self.url)
        self.assertEqual(0, code)
        self.assertEqual({"enabled": False}, json.loads(self.calls("POST")[0]["body"]))
        self.assertIn("confirm=true", self.calls("POST")[0]["path"])
        self.assertIn("trading-lake is off", out)
        run("connector", "enable", "trading-lake", "--server", self.url)
        self.assertEqual({"enabled": True}, json.loads(self.calls("POST")[1]["body"]))
        self.assertNotIn("confirm", self.calls("POST")[1]["path"])

    def test_test_prints_dates_and_rows_and_exits_1_with_the_hint_on_a_failure(self):
        Fake.routes[("POST", self.base + "/trading-lake/test")] = TEST_OK
        code, out, _ = run("connector", "test", "trading-lake", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("reachable, health UP", "2026-10-05", "120"):
            self.assertIn(text, out)
        Fake.routes[("POST", self.base + "/trading-lake/test")] = TEST_BAD
        code, out, _ = run("connector", "test", "trading-lake", "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("PROBLEM: PKIX path building failed", out)
        self.assertIn("hint: The server's certificate is not trusted.", out)
        code, out, _ = run("connector", "test", "--file", self.file, "--json", "--server", self.url)
        self.assertEqual(1, code)
        self.assertEqual({"text": self.file.read_text(encoding="utf-8")}, json.loads(self.calls("POST")[-1]["body"]))
        self.assertFalse(json.loads(out)["ok"])
        self.assertEqual(2, run("connector", "test", "--server", self.url)[0])

    def test_plugins_lists_them_and_shows_one_with_its_settings(self):
        Fake.routes[("GET", self.base + "/plugins")] = PLUGINS
        code, out, _ = run("connector", "plugins", "--server", self.url)
        self.assertEqual(0, code)
        for text in ("jdbc", "yes", "file"):
            self.assertIn(text, out)
        code, out, _ = run("connector", "plugins", "jdbc", "--server", self.url)
        for text in ("supports the tls.* settings", "url", "required", "password", "secret: ${ENV} or file:"):
            self.assertIn(text, out)
        self.assertEqual(1, run("connector", "plugins", "nope", "--server", self.url)[0])

    def test_history_and_reset(self):
        code, out, _ = run("connector", "history", "trading-lake", "--server", self.url)
        self.assertIn("20261006T090000000", out)
        Fake.routes[("POST", self.base + "/trading-lake/reset")] = {"state": "RUNNING"}
        code, out, _ = run("connector", "reset", "trading-lake", "--server", self.url)
        self.assertEqual(0, code)
        self.assertIn("back to the pack's default", out)


if __name__ == "__main__":
    unittest.main()
