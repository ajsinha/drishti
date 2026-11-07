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
"""Tests of `drishti.py db copy` (tools/dbcli.py): the options become the Java command line of `java -jar <exec jar> db copy ...`, paths
made absolute, with a temp folder outside the data folder. The Java side is tested by drishti-identity's DatabaseCopyTest."""
import argparse
import importlib.util
import os
import pathlib
import sys
import unittest
from unittest import mock

TOOLS = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))


class DbCli(unittest.TestCase):
    def run_cli(self, *argv):
        import dbcli
        seen = {}

        def fake_run(cmd, cwd=None, **kw):
            if "db" not in cmd:                                         # javacmd asking the java for its version
                return mock.Mock(returncode=0, stdout="java.specification.version = 21\n", stderr="")
            seen["cmd"] = cmd
            return mock.Mock(returncode=0)

        parser = argparse.ArgumentParser()
        dbcli.register(parser.add_subparsers(dest="group"), {"find_java": lambda j=None: "java",
                                                             "find_jar": lambda j=None: "/x/drishti-server-exec.jar", "CliError": RuntimeError})
        a = parser.parse_args(["db", *argv])
        with mock.patch.object(dbcli.subprocess, "run", fake_run):
            self.assertEqual(a.func(a), 0)
        cmd = seen["cmd"]
        self.assertIn("/x/drishti-server-exec.jar", cmd)
        self.assertTrue(any(c.startswith("-Djava.io.tmpdir=") for c in cmd))
        return cmd[cmd.index("db") + 1:]

    def test_the_options_become_the_java_command_line(self):
        out = self.run_cli("copy", "--from", "a.yaml", "--to", "b.yaml", "--check", "--drop-columns", "--json")
        self.assertEqual(out, ["copy", "--from", os.path.abspath("a.yaml"), "--to", os.path.abspath("b.yaml"), "--data-dir", os.path.abspath("data"),
                               "--check", "--drop-columns", "--json"])

    def test_the_group_is_in_drishti_py(self):
        spec = importlib.util.spec_from_file_location("drishti_cli_for_db_test", TOOLS / "drishti.py")
        d = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = d
        spec.loader.exec_module(d)
        a = d.build_parser().parse_args(["db", "copy", "--from", "a.yaml", "--to", "b.yaml"])
        self.assertEqual((a.cmd, a.source, a.target, a.check), ("copy", "a.yaml", "b.yaml", False))
        with self.assertRaises(SystemExit):
            d.build_parser().parse_args(["db", "copy", "--from", "a.yaml"])


if __name__ == "__main__":
    unittest.main()
