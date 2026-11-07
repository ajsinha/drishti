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
"""Tests of `drishti.py tables ...` (tools/tablescli.py): the options become the Java command line of `java -jar <exec jar> tables ...`,
run in the caller's folder with a temp folder outside the data folder. The Java side is tested by drishti-tables' TablesCliTest."""
import importlib.util
import pathlib
import sys
import unittest
from unittest import mock

TOOLS = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))


def drishti():
    spec = importlib.util.spec_from_file_location("drishti_cli_for_tables_test", TOOLS / "drishti.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod
    spec.loader.exec_module(mod)
    return mod


class TablesCli(unittest.TestCase):
    def run_cli(self, *argv):
        import argparse
        import tablescli
        seen = {}

        def fake_run(cmd, cwd=None, **kw):
            if "tables" not in cmd:                                     # javacmd asking the java for its version
                return mock.Mock(returncode=0, stdout="java.specification.version = 21\n", stderr="")
            seen["cmd"], seen["cwd"] = cmd, cwd
            return mock.Mock(returncode=0)

        parser = argparse.ArgumentParser()
        tablescli.register(parser.add_subparsers(dest="group"), {"find_java": lambda j=None: "java",
                                                                 "find_jar": lambda j=None: "/x/drishti-server-exec.jar", "CliError": RuntimeError})
        a = parser.parse_args(["tables", *argv])
        with mock.patch.object(tablescli.subprocess, "run", fake_run):
            self.assertEqual(a.func(a), 0)
        cmd = seen["cmd"]
        self.assertIn("/x/drishti-server-exec.jar", cmd)
        self.assertTrue(any(c.startswith("-Djava.io.tmpdir=") for c in cmd))
        return cmd[cmd.index("tables") + 1:]

    def test_the_options_become_the_java_command_line(self):
        self.assertEqual(self.run_cli("check", "--sample", "bi.t=a.jsonl", "--json"), ["check", "--sample", "bi.t=a.jsonl", "--json"])
        self.assertEqual(self.run_cli("preview", "bi.t", "--sample", "a.jsonl", "--limit", "5"), ["preview", "bi.t", "--limit", "5", "--sample", "a.jsonl"])
        self.assertEqual(self.run_cli("migrate", "--dry-run", "--bi", "x/bi", "--moved-to", "m"), ["migrate", "--bi", "x/bi", "--moved-to", "m", "--dry-run"])
        self.assertEqual(self.run_cli("lineage", "trading-risk"), ["lineage", "trading-risk"])

    def test_the_group_is_in_drishti_py(self):
        d = drishti()
        a = d.build_parser().parse_args(["tables", "migrate", "--check"])
        self.assertEqual(a.cmd, "migrate")
        self.assertTrue(a.check)
        with self.assertRaises(SystemExit):
            d.build_parser().parse_args(["tables", "migrate", "--check", "--dry-run"])


if __name__ == "__main__":
    unittest.main()
