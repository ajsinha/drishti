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

"""Guard: the committed IDE run configurations work on Windows, macOS and Linux without editing.

Two projects, so IntelliJ and PyCharm never share one .idea folder: IntelliJ opens the repository root and reads the Java ones in
run-configs/; PyCharm opens drishti-console/ and reads the Python ones in drishti-console/run-configs/. A Python run configuration uses
the project interpreter (SDK_HOME empty, IS_MODULE_SDK false: PyCharm's "Project Default"), never a path such as
drishti-console/.venv/bin/python, which does not exist on Windows (there it is .venv\\Scripts\\python.exe). Every configuration runs
from the repository root ($PROJECT_DIR$ for IntelliJ, $PROJECT_DIR$/.. for PyCharm) and parses as XML. docs/qa/windows-custom-data.md and
docs/guides/GETTING_STARTED.md (Running from IntelliJ IDEA and PyCharm, section 4) rely on this.
"""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = sorted((ROOT / "run-configs").glob("*.run.xml"))
PYTHON = sorted((ROOT / "drishti-console" / "run-configs").glob("*.run.xml"))
CONFIGS = JAVA + PYTHON


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
            self.assertIn(f, PYTHON, f"{f.name}: a Python configuration belongs in drishti-console/run-configs (PyCharm's project)")
            self.assertEqual(o.get("WORKING_DIRECTORY"), "$PROJECT_DIR$/..", f"{f.name}: runs from the repository root")
            self.assertNotIn("$PROJECT_DIR$/tools", f.read_text(encoding="utf-8"), f"{f.name}: PyCharm's $PROJECT_DIR$ is drishti-console")
            self.assertNotIn("/bin/python", f.read_text(encoding="utf-8"), f.name)
        self.assertGreaterEqual(python, 10)

    def test_application_configurations_run_from_the_repository_root(self):
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            if conf.get("type") in ("Application", "MavenRunConfiguration"):
                self.assertIn(f, JAVA, f"{f.name}: a Java configuration belongs in run-configs/ (IntelliJ's project)")
            if conf.get("type") == "Application":
                self.assertEqual(options(conf).get("WORKING_DIRECTORY"), "$PROJECT_DIR$", f.name)

    def test_the_server_configurations_keep_native_libraries_out_of_the_system_temp_folder(self):
        """-Djava.io.tmpdir=<data dir>/tmp/server, the server's own temp base: DuckDB, RocksDB and CatBoost unpack there, not in /tmp."""
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            if conf.get("type") != "Application":
                continue
            envs = {e.get("name"): e.get("value") for e in conf.iter("env")}
            data = envs.get("DRISHTI_DATA_DIR", "$PROJECT_DIR$/data")
            self.assertIn(f"-Djava.io.tmpdir={data}/tmp/server", options(conf).get("VM_PARAMETERS", ""), f.name)


if __name__ == "__main__":
    unittest.main()
