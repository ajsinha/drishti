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

"""Guard: the committed IDE run configurations (run-configs/) work on Windows, macOS and Linux without editing.

A Python run configuration uses the project interpreter (SDK_HOME empty, IS_MODULE_SDK false: PyCharm's "Project Default"), never a
path such as drishti-console/.venv/bin/python, which does not exist on Windows (there it is .venv\\Scripts\\python.exe). Every
configuration runs from the repository root ($PROJECT_DIR$) and parses as XML. docs/guides/QA_ON_WINDOWS_WITH_CUSTOM_DATA.md and
docs/guides/IDE_GUIDE.md section 4 rely on this.
"""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIGS = sorted((ROOT / "run-configs").glob("*.run.xml"))


def options(conf: ET.Element) -> dict:
    return {o.get("name"): o.get("value") for o in conf.findall("option")}


class RunConfigs(unittest.TestCase):
    def test_there_are_run_configurations(self):
        self.assertGreaterEqual(len(CONFIGS), 10)

    def test_python_configurations_use_the_project_interpreter(self):
        python = 0
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            if conf.get("type") != "PythonConfigurationType":
                continue
            python += 1
            o = options(conf)
            self.assertEqual(o.get("SDK_HOME"), "", f"{f.name}: SDK_HOME must be empty (the project interpreter), not {o.get('SDK_HOME')}")
            self.assertEqual(o.get("IS_MODULE_SDK"), "false", f"{f.name}: IS_MODULE_SDK must be false (the project interpreter)")
            self.assertEqual(o.get("WORKING_DIRECTORY"), "$PROJECT_DIR$", f"{f.name}: runs from the repository root")
            self.assertNotIn("/bin/python", f.read_text(encoding="utf-8"), f.name)
        self.assertGreaterEqual(python, 10)

    def test_application_configurations_run_from_the_repository_root(self):
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            if conf.get("type") == "Application":
                self.assertEqual(options(conf).get("WORKING_DIRECTORY"), "$PROJECT_DIR$", f.name)


if __name__ == "__main__":
    unittest.main()
