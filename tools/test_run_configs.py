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
consoles Drishti console (Risk) and (Finance) included, and the tools). The server launchers are JAR Applications on lib/drishti-server-exec.jar, the jar Drishti build all
installed (the one the modules are stamped for). A Python run configuration uses
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
INTELLIJ = ("Drishti build all", "Drishti build modules", "Drishti server (demo lakes)", "Drishti server (built-in samples)",
            "Drishti server (QA, port 18580)")
PYCHARM = ("Drishti console", "Drishti console (QA, port 17580)", "Drishti console (Risk)", "Drishti console (Finance)", "Build demo lakes",
           "Load a file into the lake", "Drishti doctor", "Drishti doctor (QA)")


def options(conf: ET.Element) -> dict:
    return {o.get("name"): o.get("value") for o in conf.findall("option")}


class RunConfigs(unittest.TestCase):
    def test_the_launcher_set_is_exactly_the_thirteen(self):
        """One launcher per real job (LAUNCHER_REVIEW.md 4.3, G11): a new launcher is a row of tools/launch/launchers.yaml, never a copy."""
        self.assertEqual(sorted(f.name[:-len(".run.xml")] for f in JAVA), sorted(INTELLIJ))
        self.assertEqual(sorted(f.name[:-len(".run.xml")] for f in PYTHON), sorted(PYCHARM))

    def test_intellij_holds_no_python_configuration(self):
        """IntelliJ builds and runs the server with Java only: run-configs/ holds Maven builds and Java launchers, never a Python one."""
        for f in JAVA:
            conf = ET.parse(f).getroot().find("configuration")
            self.assertIn(conf.get("type"), ("JarApplication", "MavenRunConfiguration"), f"{f.name}: a Python configuration belongs in drishti-console/run-configs")
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
        self.assertEqual(python, len(PYCHARM))

    def test_the_distributions_consoles_are_pycharm_s(self):
        for d in ("Finance", "Risk"):
            f = ROOT / "drishti-console" / "run-configs" / f"Drishti console ({d}).run.xml"
            o = options(ET.parse(f).getroot().find("configuration"))
            self.assertEqual(o.get("SCRIPT_NAME"), "$PROJECT_DIR$/run_drishti_web.py", f.name)
            self.assertIn(f"--distribution={d.lower()}", o.get("PARAMETERS", ""), f.name)

    def test_the_maven_builds_are_one_command_each_and_never_install(self):
        """Drishti build all / server jar / modules: the Maven commands drishti.py build runs (Maven itself installs the jar into lib/ and
        the packages into modules/)."""
        want = {"Drishti build all": "clean package -DskipTests -pl :drishti-app,:drishti-modules -am",
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
            if conf.get("type") in ("Application", "JarApplication", "MavenRunConfiguration"):
                self.assertIn(f, JAVA, f"{f.name}: a Java configuration belongs in run-configs/ (IntelliJ's project)")
            if conf.get("type") == "JarApplication":
                self.assertEqual(options(conf).get("WORKING_DIRECTORY"), "$PROJECT_DIR$", f.name)

    def test_the_server_configurations_name_this_machine_s_jvm_options(self):
        """-Djava.io.tmpdir and the heap dumps follow the data folder: a server launcher names ~/drishti-envs/jvm-<profile>.args (the
        options drishti.py start server runs, with this machine's data folder), never a folder of its own."""
        for f in JAVA:
            conf = ET.parse(f).getroot().find("configuration")
            if conf.get("type") != "JarApplication":
                continue
            vm = options(conf).get("VM_PARAMETERS", "")
            self.assertRegex(vm, r'^"@\$USER_HOME\$/drishti-envs/jvm-(demo|samples|qa)\.args" ', f.name)
            self.assertNotIn("java.io.tmpdir", vm, f.name)
            self.assertNotIn("HeapDumpPath", vm, f.name)

    def test_every_launcher_passes_the_settings_file_by_an_absolute_macro_path(self):
        """The one per-developer settings file, $USER_HOME$/drishti-envs/dev.ini (created from config/settings.examples/dev.ini; see the
        Developer Guide): a Java configuration passes -Ddrishti.settings.file=<it> as a VM option, a Python one --settings <it>. A Maven
        build starts no Drishti process, so it carries neither."""
        ini = "$USER_HOME$/drishti-envs/dev.ini"
        checked = 0
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            o = options(conf)
            if conf.get("type") == "JarApplication":
                self.assertIn('"-Ddrishti.settings.file=' + ini + ",$PROJECT_DIR$/config/profiles/", o.get("VM_PARAMETERS", ""), f.name)
            elif conf.get("type") == "PythonConfigurationType":
                self.assertTrue(o.get("PARAMETERS", "").startswith(f'--settings "{ini}" '), f"{f.name}: --settings {ini} first, quoted")
            else:
                self.assertEqual(conf.get("type"), "MavenRunConfiguration", f.name)
                continue
            checked += 1
        self.assertEqual(checked, len(CONFIGS) - 2)


if __name__ == "__main__":
    unittest.main()


IDE_JAR = "$PROJECT_DIR$/lib/drishti-server-exec.jar"


def test_every_server_launcher_runs_the_jar_the_modules_are_stamped_for():
    """A server launcher is a JAR Application on the jar Drishti build all installed into lib/ (the version-free name ModulePack.IDE_JAR,
    the same file as lib/drishti-server-<version>-exec.jar), never IntelliJ's compiled classes: those carry no build-info.properties, so
    the kernel's build is unknown and banking, stamped for the jar's build, is refused ("built for kernel ..., this is an unknown build").
    Nothing is compiled before launch (no Make): the build is the explicit Maven Drishti build all."""
    folder = ROOT / "run-configs"
    servers = sorted(folder.glob("Drishti server*.run.xml"))
    assert len(servers) == 3
    for f in servers:
        conf = ET.parse(f).getroot().find("configuration")
        assert conf.get("type") == "JarApplication", f"{f.name}: a JAR Application on {IDE_JAR}, not {conf.get('type')}"
        o = options(conf)
        assert o.get("JAR_PATH") == IDE_JAR, f.name
        assert o.get("WORKING_DIRECTORY") == "$PROJECT_DIR$", f.name
        text = ET.tostring(conf, encoding="unicode")
        assert "MAIN_CLASS_NAME" not in text and 'name="Make"' not in text, f"{f.name}: no class path, no Make before launch"
    for f in JAVA:   # no class-path launcher at all: every Java configuration of IntelliJ is a Maven build or a JAR Application
        assert ET.parse(f).getroot().find("configuration").get("type") in ("JarApplication", "MavenRunConfiguration"), f.name
    # the name is the one Maven installs (ModulePack.IDE_JAR), and the tools' pattern for the versioned jar never matches it
    pack = (ROOT / "kernel" / "drishti-module-runtime" / "src" / "main" / "java" / "com" / "ash" / "drishti" / "module" / "runtime" / "ModulePack.java")
    assert 'IDE_JAR = "drishti-server-exec.jar"' in pack.read_text(encoding="utf-8")
    import fnmatch
    assert not fnmatch.fnmatch("drishti-server-exec.jar", "drishti-server-*-exec.jar")

