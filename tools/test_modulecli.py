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
from test_pack_deploy import ServerCase  # noqa: E402
import modulecli  # noqa: E402


class Module(ServerCase):
    def dmod(self):
        p = pathlib.Path(self.tmp.name) / "greeter-1.0.0.dmod"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("module.yaml", "format: 1\nid: greeter\nversion: \"1.0.0\"\nparts:\n  api: {class: light}\n  compute: {class: heavy}\n")
        return p

    def test_run_lays_out_one_package_placed_here(self):
        d = pathlib.Path(self.tmp.name) / "run"
        m = modulecli.prepare_run(self.dmod(), d)
        self.assertEqual(("greeter", "1.0.0"), (m["id"], m["version"]))
        self.assertTrue((d / "modules/greeter-1.0.0.dmod").is_file())
        self.assertEqual(["greeter-1.0.0.dmod"], sorted(p.name for p in (d / "modules").iterdir()))
        placement = (d / "placement.yaml").read_text()
        self.assertIn("api: { nodes: [local]", placement)
        self.assertIn("compute: { nodes: [local]", placement)

    def test_a_second_run_starts_from_an_empty_modules_folder(self):
        d = pathlib.Path(self.tmp.name) / "run"
        modulecli.prepare_run(self.dmod(), d)
        (d / "modules/old-0.1.0.dmod").write_text("x")
        modulecli.prepare_run(self.dmod(), d)
        self.assertEqual(["greeter-1.0.0.dmod"], sorted(p.name for p in (d / "modules").iterdir()))


if __name__ == "__main__":
    unittest.main()
