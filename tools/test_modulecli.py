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
"""`drishti.py module deploy|plan|apply|list|...|run` against a fake server: the same calls as Admin -> Modules -> Deploy, the exit codes,
and `module run`'s one-module layout (the end-to-end run on three nodes is drishti-server's ModuleDeployClusterTest)."""
import json
import pathlib
import sys
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_pack_deploy import Fake, ServerCase, run  # noqa: E402
import modulecli  # noqa: E402

PLAN = {"id": "dpl-1", "action": "install", "module": "greeter", "version": "1.0.0", "previous": None, "state": "proposed",
        "fingerprint": "abc", "reason": "first", "proposedBy": "alice",
        "nodes": [{"node": "n-a", "placed": True, "now": "-", "after": "1.0.0", "action": "stage 1.0.0, activate with the others", "status": "planned"},
                  {"node": "n-c", "placed": False, "now": "-", "after": "-", "action": "nothing (not placed): forwards its requests", "status": "planned"}],
        "warnings": []}


class Module(ServerCase):
    def dmod(self):
        p = pathlib.Path(self.tmp.name) / "greeter-1.0.0.dmod"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("module.yaml", "format: 1\nid: greeter\nversion: \"1.0.0\"\nparts:\n  api: {class: light}\n  compute: {class: heavy}\n")
        return p

    def test_deploy_uploads_once_then_plans_and_prints_per_node(self):
        Fake.routes[("POST", "/api/v1/admin/modules/store")] = {"id": "greeter", "version": "1.0.0", "sha": "f" * 64, "bytes": 1234,
                                                                 "checks": ["C1 x: passed"] * 15}
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/plans")] = PLAN
        out = pathlib.Path(self.tmp.name) / "plan.json"
        code, so, se = run("module", "deploy", self.dmod(), "--reason", "first", "--server", self.url, "--out", out)
        self.assertEqual(0, code, se)
        up = self.calls("POST")[0]
        self.assertEqual("application/octet-stream", up["headers"]["Content-Type"])
        self.assertTrue(up["body"].startswith(b"PK"))
        self.assertEqual({"action": "install", "module": "greeter", "version": "1.0.0", "reason": "first"}, json.loads(self.calls("POST")[1]["body"]))
        self.assertIn("all 15 checks passed", so)
        self.assertIn("n-c", so)
        self.assertIn("not placed", so)
        self.assertEqual("dpl-1", json.loads(out.read_text())["id"])

    def test_apply_exit_codes(self):
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/dpl-1/apply")] = {**PLAN, "state": "applied", "outcome": "installed 1.0.0 on n-a"}
        self.assertEqual(0, run("module", "apply", "dpl-1", "--server", self.url)[0])
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/dpl-1/apply")] = {**PLAN, "state": "failed", "outcome": "rolled back on every node: n-b"}
        code, so, _ = run("module", "apply", "dpl-1", "--server", self.url)
        self.assertEqual(1, code)
        self.assertIn("rolled back", so)
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/dpl-1/apply")] = lambda h: (409, {"code": "DRS-1031", "detail": "DRS-1031 the cluster changed"})
        self.assertEqual(2, run("module", "apply", "dpl-1", "--server", self.url)[0])
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/dpl-1/apply")] = lambda h: (403, {"code": "DRS-2007", "detail": "DRS-2007 four eyes"})
        self.assertEqual(3, run("module", "apply", "dpl-1", "--server", self.url)[0])

    def test_remove_is_a_plan_and_needs_a_reason(self):
        Fake.routes[("POST", "/api/v1/admin/modules/deployments/plans")] = {**PLAN, "action": "remove"}
        self.assertEqual(2, run("module", "remove", "greeter", "--server", self.url)[0])
        self.assertEqual(0, run("module", "remove", "greeter", "--reason", "gone", "--server", self.url)[0])
        self.assertEqual("remove", json.loads(self.calls("POST")[-1]["body"])["action"])

    def test_list(self):
        Fake.routes[("GET", "/api/v1/admin/modules/deployments")] = {"node": "n-a", "fingerprint": "abc", "modules": [
            {"id": "greeter", "version": "1.0.0", "state": "active", "placed": ["n-a", "n-b"], "versions": [{"version": "1.0.0"}], "title": "Greeter"}],
            "waiting": [{**PLAN, "proposedBy": "alice"}]}
        code, so, _ = run("module", "list", "--server", self.url)
        self.assertEqual(0, code)
        self.assertIn("greeter", so)
        self.assertIn("n-a, n-b", so)
        self.assertIn("waiting for a second administrator: dpl-1", so)

    def test_run_lays_out_one_module_placed_here(self):
        d = pathlib.Path(self.tmp.name) / "run"
        m = modulecli.prepare_run(self.dmod(), d)
        self.assertEqual(("greeter", "1.0.0"), (m["id"], m["version"]))
        self.assertTrue((d / "modules/installed/greeter/1.0.0/module.yaml").is_file())
        self.assertIn('greeter: "1.0.0"', (d / "modules/installed/active.yaml").read_text())
        placement = (d / "placement.yaml").read_text()
        self.assertIn("api: { nodes: [local]", placement)
        self.assertIn("compute: { nodes: [local]", placement)


if __name__ == "__main__":
    unittest.main()
