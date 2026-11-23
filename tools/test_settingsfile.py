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

    def test_a_dotted_section_is_a_namespace_and_a_later_file_wins(self):
        base = self.write("[roots]\ndrishti.data.dir=/a\n[lake.demo]\nroot=/a/delta/demo\n[broker.default]\ntype=kafka\n")
        later = self.dir / "secrets.ini"
        later.write_text("[lake.demo]\nroot=/b/delta/demo\n", encoding="utf-8")
        self.assertEqual(sf.read_all(f"{base},{later}"),
                         {"drishti.data.dir": "/a", "lake.demo.root": "/b/delta/demo", "broker.default.type": "kafka"})
        self.assertEqual(sf.java_flag(f"{base}, {later}"), f"{sf.FLAG}{base},{later}")
        with self.assertRaisesRegex(sf.SettingsError, "absolute"):
            sf.files(f"{base},rel.ini")

    def test_a_root_the_file_names_is_not_overridden_by_a_launch_default(self):
        f = self.write("[roots]\ndrishti.data.dir=/from/the/file\n")
        javacmd.set_settings(f, sf.read_all(f))
        cmd = javacmd.java_cmd("java", ["-jar", "drishti-lake.jar"], tmp=self.dir / "tmp", env={})
        self.assertFalse(any(a.startswith("-Ddrishti.data.dir=") for a in cmd), cmd)
        self.assertTrue(any(a.startswith("-Ddrishti.home=") for a in cmd), cmd)
        self.assertEqual(javacmd.server_tmp(env={}), Path("/from/the/file/tmp/server"))

    def test_the_roots_reach_the_python_tools_and_the_file_beats_the_environment(self):
        """The Java order (LaunchRoots): the settings file beats a stale DRISHTI_* variable of an old setx; the old value is remembered."""
        sf.REPLACED.clear()
        env = {"DRISHTI_HOME": "/mine", "DRISHTI_MODULES_HOME": "/file/modules"}
        got = sf.apply_roots({"drishti.home": "/file/home", "drishti.data.dir": "/file/data", "drishti.modules.home": "/file/modules",
                              "lake.demo.root": "/x"}, env)
        self.assertEqual(got, ["DRISHTI_HOME", "DRISHTI_DATA_DIR"])
        self.assertEqual(env, {"DRISHTI_HOME": "/file/home", "DRISHTI_DATA_DIR": "/file/data", "DRISHTI_MODULES_HOME": "/file/modules"})
        self.assertEqual(sf.REPLACED, {"DRISHTI_HOME": "/mine"})
        sf.REPLACED.clear()

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


def test_a_settings_file_naming_another_checkout_is_a_warning(tmp_path):
    """An old dev.ini from an earlier copy: the tools and the console say so (WARNING), and the same folder says nothing."""
    import importlib.util, sys as _sys
    here = Path(__file__).resolve().parents[1]
    other = tmp_path / "drishti-orig"
    other.mkdir()
    w = sf.other_home({"drishti.home": str(other)}, here)
    assert w and w.startswith("WARNING: your settings file names drishti.home=") and "setup" in w
    assert sf.other_home({"drishti.home": str(here)}, here) is None
    assert sf.other_home({"drishti.home": str(here) + "/"}, here) is None
    assert sf.other_home({}, here) is None
    ini = tmp_path / "dev.ini"
    ini.write_text(f"drishti.home={other}\n", encoding="utf-8")
    _sys.path.insert(0, str(here / "drishti-console"))
    spec = importlib.util.spec_from_file_location("run_drishti_web_t", here / "drishti-console" / "run_drishti_web.py")
    web = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(web)
    cw = web.warn_other_home(["--settings", str(ini), "--profile", "demo"])
    assert cw and cw.startswith("WARNING:") and str(other) in cw
    ini.write_text(f"drishti.home={here}\n", encoding="utf-8")
    assert web.warn_other_home([f"--settings={ini}"]) is None
