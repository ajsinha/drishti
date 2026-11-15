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
"""The optional settings file on the Python side: same format and rules as the Java SettingsFile."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drishti  # noqa: E402
import javacmd  # noqa: E402
import settingsfile as sf  # noqa: E402


class SettingsFileTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="drishti-settings-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.dir, ignore_errors=True))
        self.addCleanup(javacmd.set_settings, None)

    def write(self, text):
        f = self.dir / "drishti.ini"
        f.write_text(text, encoding="utf-8")
        return f

    def test_entries_sections_and_comments(self):
        f = self.write("# c\n; c\n[paths]\ndrishti.data.dir = /x/y\n\n[more]\na.b=1=2\n")
        self.assertEqual(sf.read(f), {"drishti.data.dir": "/x/y", "a.b": "1=2"})

    def test_explicit_beats_the_file(self):
        f = self.write("a=1\nb=2\n")
        self.assertEqual(sf.resolve(f, {"a": "mine"}), {"b": "2"})

    def test_relative_missing_and_bad_line_are_clear_errors(self):
        with self.assertRaisesRegex(sf.SettingsError, "absolute"):
            sf.check("drishti.ini")
        with self.assertRaisesRegex(sf.SettingsError, "not a file"):
            sf.check(self.dir / "nope.ini")
        with self.assertRaisesRegex(sf.SettingsError, ":1:"):
            sf.read(self.write("no equals\n"))

    def test_the_option_is_taken_off_the_command_line(self):
        f = self.write("a=1\n")
        self.assertEqual(sf.pop_option(["x", "--settings", str(f), "y"]), (["x", "y"], str(f)))
        self.assertEqual(sf.pop_option(["--settings=" + str(f)]), ([], str(f)))
        self.assertEqual(sf.pop_option(["x"]), (["x"], None))
        with self.assertRaises(sf.SettingsError):
            sf.pop_option(["--settings", "rel.ini"])

    def test_every_jvm_the_tools_start_gets_the_flag(self):
        f = self.write("a=1\n")
        javacmd.set_settings(f)
        cmd = javacmd.java_cmd("java", ["-jar", "x.jar"], tmp=self.dir / "tmp")
        self.assertIn(sf.FLAG + str(f), cmd)
        own = javacmd.java_cmd("java", [sf.FLAG + "/other.ini"], tmp=self.dir / "tmp")
        self.assertEqual([a for a in own if a.startswith(sf.FLAG)], [sf.FLAG + "/other.ini"])

    def test_the_cli_refuses_a_relative_settings_path(self):
        self.assertEqual(drishti.main(["--settings", "rel.ini", "--help"]), 2)


if __name__ == "__main__":
    unittest.main()
