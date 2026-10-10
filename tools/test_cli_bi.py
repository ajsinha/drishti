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

"""Tests of `drishti.py bi workspace|catalogue list|get|publish` against an in-process stand-in for the server."""
import json
import pathlib
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_drishti_cli import FakeServer, run  # noqa: E402

ITEM = {"id": "bi-75d8", "workspace": "rates-desk", "workspaceName": "Rates Desk", "kind": "report", "title": "Desk P&L", "description": "Daily P&L", "owner": "ann", "tags": ["pnl"],
        "status": "published", "publishedVersion": 2, "endorsement": "certified", "views": 7, "audience": {"roles": ["reader"], "users": [], "packs": []}, "body": "pages: []\n"}
WS = [{"id": "u-ann-1a2b", "name": "My workspace", "role": "admin", "items": 1, "personal": True, "reviewRequired": False},
      {"id": "rates-desk", "name": "Rates Desk", "role": "member", "items": 4, "personal": False, "reviewRequired": True}]


class Bi(unittest.TestCase):
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

    def go(self, *argv):
        return run(*argv, env=self.env)

    def test_workspace_list_prints_role_items_and_marks(self):
        FakeServer.routes[("GET", "/api/v1/bi/workspaces")] = (200, WS)
        code, out, _ = self.go("bi", "workspace", "list")
        self.assertEqual(0, code)
        self.assertIn("rates-desk", out)
        self.assertIn("member", out)
        self.assertIn("[personal]", out)
        self.assertIn("[review before publish]", out)
        self.assertEqual("Bearer drk_secret", FakeServer.requests[0][2])
        self.assertNotIn("drk_secret", out)

    def test_workspace_list_json(self):
        FakeServer.routes[("GET", "/api/v1/bi/workspaces")] = (200, WS)
        code, out, _ = self.go("bi", "workspace", "list", "--json")
        self.assertEqual(0, code)
        self.assertEqual("rates-desk", json.loads(out)[1]["id"])

    def test_workspace_get_shows_members_and_items(self):
        FakeServer.routes[("GET", "/api/v1/bi/workspaces/rates-desk")] = (200, {**WS[1], "members": [{"username": "ann", "role": "admin"}]})
        FakeServer.routes[("GET", "/api/v1/bi/workspaces/rates-desk/items")] = (200, [ITEM])
        code, out, _ = self.go("bi", "workspace", "get", "rates-desk")
        self.assertEqual(0, code)
        self.assertIn("member ann", out)
        self.assertIn("Desk P&L", out)
        self.assertEqual(2, self.go("bi", "workspace", "get")[0])                 # an id is needed

    def test_catalogue_list_passes_the_filters(self):
        FakeServer.routes[("GET", "/api/v1/bi/catalogue")] = (200, {"total": 3, "items": [ITEM], "facets": {}})
        code, out, _ = self.go("bi", "catalogue", "list", "--q", "desk p&l", "--kind", "report", "--endorsement", "certified", "--sort", "date", "--limit", "5")
        self.assertEqual(0, code)
        path = FakeServer.requests[0][1]
        for part in ("q=desk%20p%26l", "kind=report", "endorsement=certified", "sort=date", "limit=5"):
            self.assertIn(part, path)
        self.assertIn("certified", out)
        self.assertIn("3 item(s), showing 1", out)

    def test_catalogue_get_with_and_without_the_body(self):
        FakeServer.routes[("GET", "/api/v1/bi/items/bi-75d8")] = (200, ITEM)
        code, out, _ = self.go("bi", "catalogue", "get", "bi-75d8")
        self.assertEqual(0, code)
        self.assertIn("tags: pnl", out)
        self.assertNotIn("pages: []", out)
        self.assertIn("pages: []", self.go("bi", "catalogue", "get", "bi-75d8", "--body")[1])

    def test_catalogue_publish_posts_the_note(self):
        FakeServer.routes[("POST", "/api/v1/bi/items/bi-75d8/publish")] = (200, ITEM)
        code, out, _ = self.go("bi", "catalogue", "publish", "bi-75d8", "--note", "month end")
        self.assertEqual(0, code)
        self.assertIn("version 2", out)
        self.assertEqual({"note": "month end"}, json.loads(FakeServer.requests[0][5]))

    def test_the_servers_refusal_is_shown_with_its_code_and_exit_code_1(self):
        FakeServer.routes[("POST", "/api/v1/bi/items/bi-75d8/publish")] = (403, {"code": "DRS-9002", "detail": "you are a viewer of Rates Desk; publishing needs member"})
        code, out, err = self.go("bi", "catalogue", "publish", "bi-75d8")
        self.assertNotEqual(0, code)
        self.assertIn("DRS-9002", out + err)
        self.assertIn("viewer", out + err)

    def test_a_missing_item_is_a_usage_error(self):
        self.assertEqual(2, self.go("bi", "catalogue", "publish")[0])


if __name__ == "__main__":
    unittest.main()
