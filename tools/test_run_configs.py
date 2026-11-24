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

Two projects, each with only its own launchers: IntelliJ IDEA opens the repository (`drishti`) and sees run-configs/, the Java launchers
(the Maven builds and the JAR Application servers, folderName="Drishti-java"); PyCharm opens `drishti/drishti-console` and sees
drishti-console/run-configs/, the Python ones (the consoles, the distributions' consoles, setup, the tools; folderName="Drishti-python"),
which reach the install folder as $PROJECT_DIR$/... No Python launcher in run-configs/, no Java one in drishti-console/run-configs/,
no .venv in any. The server launchers are JAR Applications on lib/drishti-server-exec.jar,
the jar Drishti: build all installed (the one the modules are stamped for). A Python run configuration uses the project interpreter
(SDK_HOME empty, IS_MODULE_SDK false: PyCharm's "Project Default"), never a path such as drishti-console/.venv/bin/python, which does not
exist on Windows and is never made (Drishti makes no venv). Every configuration runs from the repository root (IntelliJ $PROJECT_DIR$,
PyCharm $PROJECT_DIR$/..) and parses as XML.
docs/architecture/TEST_HARNESS.md and docs/guides/IDE_GUIDE.md rely on this.
"""
import pathlib
import unittest
import xml.etree.ElementTree as ET

import pytest

pytestmark = pytest.mark.static   # a quick file check: the drill's gate runs it (TEST_HARNESS.md, "Quick file checks")

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAVA_DIR, PYTHON_DIR = ROOT / "run-configs", ROOT / "drishti-console" / "run-configs"      # IntelliJ's project, PyCharm's project
CONFIGS = sorted(JAVA_DIR.glob("*.run.xml")) + sorted(PYTHON_DIR.glob("*.run.xml"))


def _type(f) -> str:
    return ET.parse(f).getroot().find("configuration").get("type")


def _name(f) -> str:
    return ET.parse(f).getroot().find("configuration").get("name")


JAVA = sorted(JAVA_DIR.glob("*.run.xml"))
PYTHON = sorted(PYTHON_DIR.glob("*.run.xml"))
INTELLIJ = ("Drishti: build all", "Drishti: test", "Drishti: build modules", "Drishti: run server (demo)", "Drishti: run server (built-in samples)",
            "Drishti: run server (QA, port 18580)")
PYCHARM = ("Drishti: run console", "Drishti: run console (QA, port 17580)", "Drishti: run console (Risk)", "Drishti: run console (Finance)", "Drishti: build demo lakes",
           "Drishti: run loader (file into the lake)", "Drishti: check environment", "Drishti: check environment (QA)",
           "Drishti: setup", "Drishti: setup (package index)")


def options(conf: ET.Element) -> dict:
    return {o.get("name"): o.get("value") for o in conf.findall("option")}


class RunConfigs(unittest.TestCase):
    def test_the_launcher_set_is_exactly_the_table(self):
        """One launcher per real job (LAUNCHER_REVIEW.md 4.3, G11): a new launcher is a row of tools/launch/launchers.yaml, never a copy."""
        self.assertEqual(sorted(_name(f) for f in JAVA), sorted(INTELLIJ))
        self.assertEqual(sorted(_name(f) for f in PYTHON), sorted(PYCHARM))

    def test_each_project_holds_only_its_own_launchers_and_none_names_a_venv(self):
        """i7g: IntelliJ's run-configs/ holds no Python launcher, PyCharm's drishti-console/run-configs/ no Java one; no .venv anywhere."""
        for f in JAVA:
            self.assertNotEqual(_type(f), "PythonConfigurationType", f"{f.name}: a Python launcher belongs in drishti-console/run-configs/")
        for f in PYTHON:
            self.assertEqual(_type(f), "PythonConfigurationType", f"{f.name}: a Java launcher belongs in run-configs/")
        for f in [*CONFIGS, ROOT / "drishti.code-workspace"]:
            self.assertNotIn(".venv", f.read_text(encoding="utf-8"), f"{f.name}: names a .venv")
        self.assertEqual([p for p in ROOT.rglob("*.run.xml") if p.parent not in (JAVA_DIR, PYTHON_DIR)
                          and not {"node_modules", "target", "vendor", ".git"} & set(p.parts)], [])

    def test_launchers_are_grouped_by_folder(self):
        """Every Maven and JAR Application launcher is in the folder Drishti-java, every Python one in Drishti-python."""
        for f in CONFIGS:
            conf = ET.parse(f).getroot().find("configuration")
            want = "Drishti-python" if conf.get("type") == "PythonConfigurationType" else "Drishti-java"
            self.assertEqual(conf.get("folderName"), want, f.name)

    def test_the_loader_asks_for_its_file(self):
        """No file only one person has: the loader launcher takes its file from PyCharm's prompt macro."""
        f = PYTHON_DIR / "Drishti run loader (file into the lake).run.xml"
        params = options(ET.parse(f).getroot().find("configuration")).get("PARAMETERS", "")
        self.assertIn('--from "$Prompt$"', params)
        self.assertNotIn("drishti-private", params)

    def test_intellij_holds_no_python_configuration(self):
        """IntelliJ builds and runs the server with Java only: run-configs/ holds Maven builds and Java launchers, never a Python one."""
        for f in JAVA:
            conf = ET.parse(f).getroot().find("configuration")
            self.assertIn(conf.get("type"), ("JarApplication", "MavenRunConfiguration"), f"{f.name}: a Python configuration is a PyCharm one")
            text = ET.tostring(conf, encoding="unicode")                 # what IntelliJ runs (a comment may name a PyCharm step)
            self.assertNotIn("PythonConfigurationType", text, f"{f.name}: a before-launch step on a Python configuration")
            self.assertNotIn("drishti.py", text, f.name)

    def test_python_configurations_use_the_project_interpreter(self):
        python = 0
        for f in PYTHON:
            conf = ET.parse(f).getroot().find("configuration")
            self.assertEqual(conf.get("type"), "PythonConfigurationType", f"{f.name}: PyCharm's drishti-console/run-configs/ holds Python configurations")
            python += 1
            o = options(conf)
            self.assertEqual(o.get("SDK_HOME"), "", f"{f.name}: SDK_HOME must be empty (the project interpreter), not {o.get('SDK_HOME')}")
            self.assertEqual(o.get("IS_MODULE_SDK"), "false", f"{f.name}: IS_MODULE_SDK must be false (the project interpreter)")
            # PyCharm's project is drishti-console: the repository root (the install folder) is $PROJECT_DIR$/..
            self.assertEqual(o.get("WORKING_DIRECTORY"), "$PROJECT_DIR$/..", f"{f.name}: runs from the repository root")
            script = o.get("SCRIPT_NAME") or ""
            self.assertTrue(script.startswith("$PROJECT_DIR$/../") or script == "$PROJECT_DIR$/run_drishti_web.py",
                            f"{f.name}: the script from the install folder (or the console's own, in the project)")
            self.assertNotIn("/bin/python", f.read_text(encoding="utf-8"), f.name)
            self.assertNotRegex(o.get("PARAMETERS", ""), r"\bbuild (jar|modules|all)\b", f"{f.name}: building is IntelliJ's (Maven) or the terminal's")
        self.assertEqual(python, len(PYCHARM))

    def test_the_distributions_consoles_are_pycharm_s(self):
        for d in ("Finance", "Risk"):
            f = PYTHON_DIR / f"Drishti run console ({d}).run.xml"
            o = options(ET.parse(f).getroot().find("configuration"))
            self.assertEqual(o.get("SCRIPT_NAME"), "$PROJECT_DIR$/run_drishti_web.py", f.name)
            self.assertIn(f"--distribution={d.lower()}", o.get("PARAMETERS", ""), f.name)

    def test_the_maven_builds_are_one_command_each_and_never_install(self):
        """Drishti: build all / server jar / modules: the Maven commands drishti.py build runs (Maven itself installs the jar into lib/ and
        the packages into modules/)."""
        want = {"Drishti: build all": "clean package -DskipTests -pl :drishti-app,:drishti-modules -am",
                "Drishti: build modules": "clean package -DskipTests -pl :drishti-modules -am -Pmodules-only"}
        for name, goals in want.items():
            conf = ET.parse(ROOT / "run-configs" / f"{name.replace(':', '')}.run.xml").getroot().find("configuration")
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
                if o.get("PARAMETERS", "").startswith("setup"):          # setup makes dev.ini: it names none
                    self.assertNotIn("--settings", o.get("PARAMETERS", ""), f.name)
                    checked += 1
                    continue
                self.assertTrue(o.get("PARAMETERS", "").startswith(f'--settings "{ini}" '), f"{f.name}: --settings {ini} first, quoted")
            else:
                self.assertEqual(conf.get("type"), "MavenRunConfiguration", f.name)
                continue
            checked += 1
        self.assertEqual(checked, len(CONFIGS) - 3)               # all but the three Maven builds (build all, test, build modules)


if __name__ == "__main__":
    unittest.main()


IDE_JAR = "$PROJECT_DIR$/lib/drishti-server-exec.jar"


def test_every_server_launcher_runs_the_jar_the_modules_are_stamped_for():
    """A server launcher is a JAR Application on the jar Drishti: build all installed into lib/ (the version-free name ModulePack.IDE_JAR,
    the same file as lib/drishti-server-<version>-exec.jar), never IntelliJ's compiled classes: those carry no build-info.properties, so
    the kernel's build is unknown and banking, stamped for the jar's build, is refused ("built for kernel ..., this is an unknown build").
    Nothing is compiled before launch (no Make): the build is the explicit Maven Drishti: build all."""
    folder = ROOT / "run-configs"
    servers = sorted(folder.glob("Drishti run server*.run.xml"))
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

