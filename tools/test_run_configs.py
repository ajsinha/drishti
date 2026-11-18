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
run-configs/ (the Maven builds and the server launchers: no Python configuration at all, so IntelliJ needs neither Python nor its Python
plugin); PyCharm opens drishti-console/ and reads the Python ones in drishti-console/run-configs/ (the consoles, the distributions'
consoles Drishti console (Risk), (Finance), (All) included, and the tools). A Python run configuration uses
the project interpreter (SDK_HOME empty, IS_MODULE_SDK false: PyCharm's "Project Default"), never a path such as
drishti-console/.venv/bin/python, which does not exist on Windows (there it is .venv\\Scripts\\python.exe). Every configuration runs
from the repository root ($PROJECT_DIR$ for IntelliJ, $PROJECT_DIR$/.. for PyCharm) and parses as XML. docs/architecture/TEST_HARNESS.md and
docs/guides/IDE_GUIDE.md rely on this.
"""
import pathlib
import unittest
import xml.etree.ElementTree as ET

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA = sorted((ROOT / "run-configs").glob("*.run.xml"))
PYTHON = sorted((ROOT / "drishti-console" / "run-configs").glob("*.run.xml"))
CONFIGS = JAVA + PYTHON


def options(conf: ET.Element) -> dict:
    return {o.get("name"): o.get("value") for o in conf.findall("option")}


class RunConfigs(unittest.TestCase):
    def test_there_are_run_configurations(self):
        self.assertGreaterEqual(len(CONFIGS), 10)

    def test_intellij_holds_no_python_configuration(self):
        """IntelliJ builds and runs the server with Java only: run-configs/ holds Maven builds and Java launchers, never a Python one."""
        for f in JAVA:
            conf = ET.parse(f).getroot().find("configuration")
            self.assertIn(conf.get("type"), ("Application", "MavenRunConfiguration"), f"{f.name}: a Python configuration belongs in drishti-console/run-configs")
            text = ET.tostring(conf, encoding="unicode")                 # what IntelliJ runs (a comment may name a PyCharm step)
            self.assertNotIn("PythonConfigurationType", text, f"{f.name}: a before-launch step on a Python configuration")
            self.assertNotIn("drishti.py", text, f.name)

    def test_python_configurations_use_the_project_interpreter(self):
        python = 0
        for f in PYTHON:
            conf = ET.parse(f).getroot().find("configuration")
            self.assertEqual(conf.get("type"), "PythonConfigurationType", f"{f.name}: PyCharm's run-configs/ holds Python configurations")
            python += 1
            o = options(conf)
            self.assertEqual(o.get("SDK_HOME"), "", f"{f.name}: SDK_HOME must be empty (the project interpreter), not {o.get('SDK_HOME')}")
            self.assertEqual(o.get("IS_MODULE_SDK"), "false", f"{f.name}: IS_MODULE_SDK must be false (the project interpreter)")
            # PyCharm's project is drishti-console/: the repository root is $PROJECT_DIR$/..
            self.assertEqual(o.get("WORKING_DIRECTORY"), "$PROJECT_DIR$/..", f"{f.name}: runs from the repository root")
            self.assertNotIn("$PROJECT_DIR$/tools", f.read_text(encoding="utf-8"), f"{f.name}: PyCharm's $PROJECT_DIR$ is drishti-console")
            self.assertNotIn("/bin/python", f.read_text(encoding="utf-8"), f.name)
            self.assertNotRegex(o.get("PARAMETERS", ""), r"\bbuild (jar|modules|all)\b", f"{f.name}: building is IntelliJ's (Maven) or the terminal's")
        self.assertGreaterEqual(python, 15)

    def test_the_distributions_consoles_are_pycharm_s(self):
        for d in ("All", "Finance", "Risk"):
            f = ROOT / "drishti-console" / "run-configs" / f"Drishti console ({d}).run.xml"
            o = options(ET.parse(f).getroot().find("configuration"))
            self.assertEqual(o.get("SCRIPT_NAME"), "$PROJECT_DIR$/run_drishti_web.py", f.name)
            self.assertIn(f"--distribution={d.lower()}", o.get("PARAMETERS", ""), f.name)

    def test_the_maven_builds_are_one_command_each_and_never_install(self):
        """Drishti build all / server jar / modules: the Maven commands drishti.py build runs (Maven itself installs the jar into lib/ and
        the packages into modules/)."""
        want = {"Drishti build all": "clean package -DskipTests -pl :drishti-app,:drishti-modules -am",
                "Drishti build server jar": "clean package -DskipTests -pl :drishti-app -am",
                "Drishti build modules": "clean package -DskipTests -pl :drishti-modules -am -Pmodules-only"}
        for name, goals in want.items():
            conf = ET.parse(ROOT / "run-configs" / f"{name}.run.xml").getroot().find("configuration")
            self.assertEqual(conf.get("type"), "MavenRunConfiguration", name)
            self.assertEqual(conf.get("name"), name)
            got = [o.get("value") for o in conf.find("MavenSettings").iter("option") if o.get("name") is None and o.get("value")]
            self.assertEqual(" ".join(got), goals, name)
            params = conf.find("MavenSettings").find("option[@name='myRunnerParameters']").find("MavenRunnerParameters")
            self.assertEqual(options(params).get("workingDirPath"), "$PROJECT_DIR$", name)

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


    def test_every_launcher_passes_the_settings_file_by_an_absolute_macro_path(self):
        """The one per-developer settings file, $USER_HOME$/drishti-envs/dev.ini (created from config/settings.examples/dev.ini; see the
        Developer Guide): a Java configuration passes -Ddrishti.settings.file=<it> as a VM option, a Python one --settings <it>. A Maven
        build starts no Drishti process, so it carries neither."""
        ini = "$USER_HOME$/drishti-envs/dev.ini"
        checked = 0
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            o = options(conf)
            if conf.get("type") == "Application":
                self.assertIn("-Ddrishti.settings.file=" + ini, o.get("VM_PARAMETERS", ""), f.name)
            elif conf.get("type") == "PythonConfigurationType":
                self.assertTrue(o.get("PARAMETERS", "").startswith("--settings " + ini + " "), f"{f.name}: --settings {ini} first")
            else:
                self.assertEqual(conf.get("type"), "MavenRunConfiguration", f.name)
                continue
            checked += 1
        self.assertGreaterEqual(checked, 25)


if __name__ == "__main__":
    unittest.main()


def test_the_server_run_configurations_carry_the_domain_modules():
    """drishti-server alone has no banking on its class path: VAR would say 'not installed'. drishti-app is the core with the modules."""
    folder = pathlib.Path(__file__).resolve().parents[1] / "run-configs"
    servers = sorted(folder.glob("Drishti server*.run.xml"))
    assert servers
    for f in servers:
        assert '<module name="drishti-app" />' in f.read_text(encoding="utf-8"), f.name
    build = (folder / "Drishti build server jar.run.xml").read_text(encoding="utf-8")
    # one way to build: Maven clean package of drishti-app, which installs the jar into <home>/lib, so lib/ never goes stale
    assert "MavenRunConfiguration" in build and "drishti.py" not in build


def test_the_debug_configuration_builds_the_modules_first_and_the_dev_switch_one_does_not():
    """A module is a package beside the jar: the debug server builds them before it launches (the Maven "Drishti build modules");
    the dev switch configuration loads the module folders as they are (DRISHTI_MODULES_DEV) and builds nothing."""
    folder = ROOT / "run-configs"
    debug = (folder / "Drishti server (debug-friendly).run.xml").read_text(encoding="utf-8")
    assert 'run_configuration_name="Drishti build modules" run_configuration_type="MavenRunConfiguration"' in debug
    dev = (folder / "Drishti server (dev switch).run.xml").read_text(encoding="utf-8")
    assert 'name="DRISHTI_MODULES_DEV" value="true"' in dev and "RunConfigurationTask" not in dev

