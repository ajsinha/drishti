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

"""The launcher guards of docs/plans/LAUNCHER_REVIEW.md section 5: the committed launchers are what tools/launch/launchers.yaml generates
(G1), carry only what has no config home (G2), start the server the terminal starts (G6), and name their paths so that a moved data
folder and a user folder with a space work (G7). Quick file checks: the drill's gate runs them; G6 starts nothing."""
from __future__ import annotations

import argparse
import re
import shlex
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "launch")]
import javacmd  # noqa: E402
import resolved  # noqa: E402
import launchcli  # noqa: E402
import launchers  # noqa: E402
import procs  # noqa: E402
import serverstart  # noqa: E402
import settingsfile  # noqa: E402

LAUNCHERS = sorted((ROOT / launchers.FOLDERS["intellij"]).glob("*.xml")) + sorted((ROOT / launchers.FOLDERS["pycharm"]).glob("*.xml"))


def conf(f: Path) -> ET.Element:
    return ET.parse(f).getroot().find("configuration")


def test_g1_the_committed_launchers_are_what_the_table_generates():
    """Any hand edit of a launcher file or of IDE_GUIDE section 4 fails here: change tools/launch/launchers.yaml, then
    python tools/drishti.py launchers."""
    assert launchers.check(ROOT) == []


def test_every_folder_of_the_owner_holds_a_launcher_and_the_tests_are_in_3_test():
    """i7g gate F4: the owner's folders Drishti-java 1 Build / 2 Run / 3 Test (and the Python four) each hold a launcher; Drishti: test
    is in Drishti-java 3 Test."""
    doc = launchers.table(ROOT)
    for ide, groups in launchers.GROUPS.items():
        used = {row["group"] for row in doc[ide]}
        assert used == set(groups), f"{ide}: empty folders {set(groups) - used}"
    assert {row["name"]: row["group"] for row in doc["intellij"]}["Drishti: test"] == "Drishti-java 3 Test"


# G2: what a launcher may carry. Everything else with a config home lives in a profile (config/profiles/*.ini).
ALLOWED_ENV = {"MALLOC_ARENA_MAX", "PYTHONUNBUFFERED", "DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET"}
ALLOWED_D = ("-Ddrishti.home=", "-Ddrishti.settings.file=")


def test_g2_a_launcher_carries_only_what_has_no_config_home():
    for f in LAUNCHERS:
        c = conf(f)
        for e in c.iter("env"):
            assert e.get("name") in ALLOWED_ENV, f"{f.name}: {e.get('name')} has a config home: put it in a profile (config/profiles/)"
            if e.get("name") in serverstart.DEV:
                assert e.get("value") == serverstart.DEV[e.get("name")], f"{f.name}: the development secret is serverstart.DEV's"
        o = {x.get("name"): x.get("value") or "" for x in c.findall("option")}
        for word in shlex.split(o.get("VM_PARAMETERS", "")):
            if word.startswith("-D"):
                assert word.startswith(ALLOWED_D), f"{f.name}: {word} has a config home: put it in a profile"
            else:
                assert word.startswith("@$USER_HOME$/drishti-envs/jvm-"), f"{f.name}: {word}: JVM options come from the one function"
        params = o.get("PARAMETERS", "")
        assert "--server.port=1748" not in params.replace("--server.port=17481", ""), f"{f.name}: the default port is a default"
        assert "--backend.url" not in params and "--server http" not in params, f"{f.name}: the server's address is the profile's"
        assert "DRISHTI_PACKS" not in ET.tostring(c, encoding="unicode") and "--packs" not in params, f"{f.name}: no pack list in a launcher"


def _flags_of(argfile: Path) -> list[str]:
    return [w for line in argfile.read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#") for w in shlex.split(line)]


def test_g6_start_server_demo_and_the_demo_launcher_start_the_same_server(tmp_path, monkeypatch):
    """drishti.py start server --demo and Drishti: run server (demo): the same JVM flags (from serverstart.jvm_flags), the same settings
    files (dev.ini, then config/profiles/demo.ini), the same environment the launcher sets; nothing apart but the pid file and the log."""
    envs = tmp_path / "drishti-envs"
    envs.mkdir()
    data = tmp_path / "data dir"                                         # a space, as C:\Users\First Last
    dev = envs / "dev.ini"
    dev.write_text(f"drishti.home={ROOT.as_posix()}\ndrishti.data.dir={data.as_posix()}\ndrishti.modules.home={(ROOT / 'modules').as_posix()}\n",
                   encoding="utf-8")
    monkeypatch.delenv("DRISHTI_DATA_DIR", raising=False)
    launchers.machine(ROOT, envs, dev)
    ide, ide_env = launchers.argv(ROOT / ".idea" / "runConfigurations" / "Drishti run server (demo).xml", ROOT, tmp_path)
    vm = ide[1:ide.index("-jar")]
    assert vm[0] == f"@{envs.as_posix()}/jvm-demo.args"
    ide_flags = _flags_of(Path(vm[0][1:])) + vm[1:]

    seen = {}

    class Proc:
        pid = 4242

        def poll(self):
            return 0
    monkeypatch.setattr(javacmd, "find_java", lambda *a, **k: "java")
    monkeypatch.setattr(javacmd, "locate_java", lambda *a, **k: ("java", "test"))
    monkeypatch.setattr(javacmd, "java_version", lambda j: 21)
    monkeypatch.setattr(javacmd, "java_cmd", lambda java, args, **k: [java] + list(args))
    monkeypatch.setattr(serverstart, "find_jar", lambda given, home: ROOT / "lib" / "drishti-server-exec.jar")
    monkeypatch.setattr(serverstart, "check_modules", lambda a, m, j: "modules: test")
    monkeypatch.setattr(procs, "port_in_use", lambda p: False)
    (data / "run").mkdir(parents=True)                                   # setup made the data folder (start makes no root)
    monkeypatch.setattr(serverstart, "demo_lake_ready", lambda *a, **k: None)
    monkeypatch.setattr(procs, "spawn", lambda cmd, cwd, env, **k: seen.update(cmd=cmd, env=env) or Proc())
    monkeypatch.setattr(procs, "write_pid", lambda *a, **k: None)
    monkeypatch.setattr(procs, "run_foreground", lambda proc, pid_file, up: 0)
    javacmd.set_settings(str(dev), settingsfile.read_all(str(dev)))
    try:
        p = argparse.ArgumentParser()
        launchcli.add_options(p, "start", "server")
        a = p.parse_args(["--demo"])
        env = {k: v for k, v in __import__("os").environ.items() if not k.startswith("DRISHTI_")}
        assert serverstart.start(a, env) == 0
    finally:
        javacmd.set_settings(None)
    cmd = seen["cmd"]
    term = cmd[1:cmd.index("-jar")]
    settings = f"-Ddrishti.settings.file={dev},{ROOT / 'config' / 'profiles' / 'demo.ini'}"
    assert settings in term
    assert f"-Ddrishti.settings.file={dev.as_posix()},{ROOT.as_posix()}/config/profiles/demo.ini" in ide_flags
    # the JVM flags: the same list, the terminal's own -Ddrishti.data.dir (the IDE's comes from dev.ini) apart
    norm = lambda xs: sorted(x.replace("\\", "/") for x in xs if not x.startswith(("-Ddrishti.settings.file=", "-Ddrishti.data.dir=", "-Ddrishti.home=")))
    assert norm(term) == norm(ide_flags)
    assert f"-Djava.io.tmpdir={(data / 'tmp' / 'server').as_posix()}" in [x.replace("\\", "/") for x in term]
    assert "-Xmx4g" in term                                                  # the demo profile's drishti.launch.heap
    for k, v in ide_env.items():                                             # what the launcher sets, the terminal sets the same
        assert seen["env"].get(k) == v, k
    assert seen["env"]["DRISHTI_DATA_DIR"] == str(data.resolve())             # the data folder of dev.ini, as the IDE's server reads it


def test_g7_paths_follow_the_data_folder_and_are_quoted():
    for f in LAUNCHERS:
        c = conf(f)
        o = {x.get("name"): x.get("value") or "" for x in c.findall("option")}
        text = ET.tostring(c, encoding="unicode")
        if c.get("type") == "PythonConfigurationType":
            assert o.get("WORKING_DIRECTORY") == "$PROJECT_DIR$/..", f.name        # PyCharm's project is drishti-console: the root is its parent
            assert f.parent == ROOT / "drishti-console" / ".idea" / "runConfigurations", f"{f.name}: a Python launcher is PyCharm's (drishti-console/.idea/runConfigurations)"
        else:
            assert f.parent == ROOT / ".idea" / "runConfigurations", f"{f.name}: a Java launcher is IntelliJ's (.idea/runConfigurations)"
        if c.get("type") == "JarApplication":
            assert o.get("WORKING_DIRECTORY") == "$PROJECT_DIR$", f.name
        for value in (o.get("VM_PARAMETERS", ""), o.get("PARAMETERS", "")):
            for token in re.findall(r'"[^"]*"|\S+', value):              # a macro path is one quoted word: C:\Users\First Last stays whole
                assert token.startswith('"') or "$USER_HOME$" not in token and "$PROJECT_DIR$" not in token, f"{f.name}: {token} unquoted"
        assert "java.io.tmpdir" not in text and "HeapDumpPath" not in text, f"{f.name}: the temp and dump folders follow the data folder"


def test_the_argv_helper_turns_a_python_launcher_into_its_command_line(tmp_path):
    cmd, env = launchers.argv(ROOT / "drishti-console" / ".idea" / "runConfigurations" / "Drishti run console (QA, port 17580).xml", ROOT, tmp_path)
    assert Path(cmd[1]).resolve() == (ROOT / "drishti-console" / "run_drishti_web.py").resolve()
    assert cmd[2:] == ["--settings", f"{tmp_path.as_posix()}/drishti-envs/dev.ini", "--profile", "qa"]
    assert env["PYTHONUNBUFFERED"] == "1"


def test_profiles_hold_no_launcher_values_twice_and_the_demo_names_its_extras_once():
    """The demo profile is the one place the demo's extra packs are named; the default packs follow the modules (D1)."""
    demo = settingsfile.read((ROOT / "config" / "profiles" / "demo.ini"))
    assert demo["drishti.packs.enabled"].split(",")[0] == "default"
    assert "DRISHTI_LENSES" not in demo and "drishti.lenses" not in demo   # the lenses follow the modules that are on
    assert demo["DRISHTI_DATALOAD_ENABLED"] == "true"                       # the data inbox: IDE and terminal alike (F21)
    assert settingsfile.read(ROOT / "config" / "profiles" / "qa.ini")["drishti.data.dir"].startswith("${drishti.home}/")
    assert "DEMO_PACKS" not in (ROOT / "tools" / "launch" / "serverstart.py").read_text(encoding="utf-8")
    assert "DRISHTI_LENSES" not in (ROOT / "tools" / "launch" / "serverstart.py").read_text(encoding="utf-8")


def test_status_and_build_see_a_server_started_from_an_ide(tmp_path, monkeypatch):
    """F20, F25 (D7): a server of this install run from IntelliJ (no pid file) is found by its command line; its port comes from the
    settings files it names; build refuses in one line while it runs."""
    home = ROOT
    ide = (f"/usr/lib/jvm/java-21/bin/java @/home/u/drishti-envs/jvm-qa.args -Ddrishti.home={home} "
           f"-Ddrishti.settings.file={tmp_path / 'dev.ini'},{home / 'config' / 'profiles' / 'qa.ini'} -jar {home}/lib/drishti-server-exec.jar")
    (tmp_path / "dev.ini").write_text("drishti.home=/x\n", encoding="utf-8")
    console = f"python {home}/drishti-console/run_drishti_web.py --settings {tmp_path / 'dev.ini'} --profile qa"
    other = "/usr/bin/java -jar /elsewhere/lib/drishti-server-exec.jar"
    monkeypatch.setattr(procs, "_all_command_lines", lambda: {101: ide, 102: console, 103: other})
    found = {p["pid"]: (p["kind"], p["port"]) for p in procs.drishti_processes(home)}
    assert found == {101: ("server", 18580), 102: ("console", 17580)}, "another install's server is not ours"
    import modulesbuild
    with pytest.raises(procs.LaunchError, match=r"stop the server first: pid 101 on port 18580"):
        modulesbuild.holding_jar(home)
    monkeypatch.setattr(procs, "_all_command_lines", lambda: {103: other})
    modulesbuild.holding_jar(home)


def _params(f: Path) -> str:
    return {x.get("name"): x.get("value") or "" for x in conf(f).findall("option")}.get("PARAMETERS", "")


def test_g3_every_pycharm_launcher_parses_with_the_real_command_line():
    """A retired command or option in a launcher fails here, not in a tester's PyCharm."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_main_for_test", ROOT / "tools" / "drishti.py")
    cli = importlib.util.module_from_spec(spec)
    sys.modules["drishti_main_for_test"] = cli                 # build_parser looks itself up by its module name
    spec.loader.exec_module(cli)
    import yaml
    console_keys = yaml.safe_load((ROOT / "drishti-console" / "config" / "application.yaml").read_text(encoding="utf-8"))
    for f in sorted(p for p in LAUNCHERS if "PythonConfigurationType" in p.read_text(encoding="utf-8")):
        words = shlex.split(_params(f))
        if words[:1] == ["setup"]:      # setup makes dev.ini: it names none (--settings would refuse a file that is not there yet)
            assert "--settings" not in words, f.name
        else:
            assert words[:2] == ["--settings", "$USER_HOME$/drishti-envs/dev.ini"], f.name
            words = words[2:]
        if words[:1] == ["--profile"]:
            assert (ROOT / "config" / "profiles" / f"{words[1]}.ini").is_file(), f.name
            words = words[2:]
        script = {x.get("name"): x.get("value") for x in conf(f).findall("option")}["SCRIPT_NAME"]
        if script.endswith("run_drishti_web.py"):
            for w in words:                                          # --a.b=v: a key of the console's application.yaml
                node = console_keys
                for part in w[2:].split("=", 1)[0].split("."):
                    assert isinstance(node, dict) and part in node, f"{f.name}: {w} is no key of drishti-console/config/application.yaml"
                    node = node[part]
            continue
        a, extra = cli.build_parser().parse_known_args(words)
        assert not extra, f"{f.name}: {extra} are not options of drishti.py {' '.join(words[:2])}"


NAME = __import__("re").compile(r"\b(DRISHTI_[A-Z0-9_]+|drishti\.[a-z0-9.-]+[a-z0-9])\b")


def test_g4_every_setting_a_launcher_profile_or_cluster_file_names_is_documented():
    """A name in a launcher, a profile, the cluster files or the systemd unit is a row of CONFIGURATION.md (F11: no undocumented name)."""
    doc = (ROOT / "docs" / "admin" / "CONFIGURATION.md").read_text(encoding="utf-8")
    files = [*LAUNCHERS, *sorted((ROOT / "config" / "profiles").glob("*.ini")), ROOT / "deploy" / "stack" / "stack.yaml",
             *sorted((ROOT / "deploy" / "stack").glob("*.ini")), ROOT / "deploy" / "systemd" / "drishti-server.service"]
    for f in files:
        code = "\n".join(line for line in f.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#"))
        if f.suffix == ".xml":
            code = ET.tostring(conf(f), encoding="unicode")
        for name in sorted(n for n in set(NAME.findall(code)) if not n.endswith((".env", ".ini", ".yaml", ".service", ".args"))):
            if name in ("drishti.home", "drishti.settings.file"):
                assert name in doc, name
                continue
            assert f"`{name}`" in doc or name in doc, f"{f.relative_to(ROOT)}: {name} is not in docs/admin/CONFIGURATION.md"


def test_g5_every_pack_named_is_a_pack_and_no_tool_or_launcher_holds_a_pack_list():
    """D1: the default packs follow the modules; a profile or the cluster file names extra packs by id, each a pack.yaml on disk; no
    launcher and no tool writes a pack list of its own."""
    known = set(resolved.pack_dirs())
    named = []
    for f in sorted((ROOT / "config" / "profiles").glob("*.ini")):
        named += [p.strip() for p in (settingsfile.read(f).get("drishti.packs.enabled") or "").split(",") if p.strip() and p.strip() != "default"]
    import yaml
    stack = yaml.safe_load((ROOT / "deploy" / "stack" / "stack.yaml").read_text(encoding="utf-8"))
    for n in stack.get("nodes") or []:
        named += [p.strip() for p in str(n.get("packs") or "").split(",") if p.strip()]
    assert named and not [p for p in named if p not in known], [p for p in named if p not in known]
    lists = __import__("re").compile(r"core,market-data,trading")
    launch_tools = [*sorted((ROOT / "tools" / "launch").glob("*.py")), *sorted((ROOT / "tools" / "lake").glob("*.py")),
                    ROOT / "tools" / "drishti.py", ROOT / "tools" / "drishti_parser.py", ROOT / "tools" / "javacmd.py", ROOT / "tools" / "settingsfile.py"]
    for f in [*LAUNCHERS, *launch_tools]:
        if f.name.startswith("test_"):
            continue
        assert not lists.search(f.read_text(encoding="utf-8", errors="replace")), f"{f.relative_to(ROOT)}: a pack list (D1: the modules' manifests are the home)"


def test_g8_the_docs_name_launchers_that_exist_and_no_retired_script():
    import yaml
    doc = yaml.safe_load((ROOT / "tools" / "launch" / "launchers.yaml").read_text(encoding="utf-8"))
    names = {r["name"] for ide in ("intellij", "pycharm") for r in doc[ide]}
    pat = __import__("re").compile(r"\*\*(Drishti: (?:build|test|run|check)[^*]*)\*\*")
    retired = ("start.sh", "stop-cluster.sh", "start-cluster.sh", "drishti-stack/start", "drishti-stack/stop")   # the retired scripts (the drishti-stack repository itself is the platform checkout the docs list)
    for f in sorted((ROOT / "docs").rglob("*.md")) + [ROOT / "README.md", ROOT / "bin" / "README.md", ROOT / "lib" / "README.md", ROOT / "deploy" / "stack" / "README.md"]:
        if "plans" in f.parts or not f.is_file():
            continue
        text = f.read_text(encoding="utf-8")
        for m in pat.finditer(text):
            assert m.group(1) in names, f"{f.relative_to(ROOT)}: **{m.group(1)}** is no launcher (tools/launch/launchers.yaml)"
        for r in retired:
            assert r not in text, f"{f.relative_to(ROOT)}: names the retired {r}"


def test_g9_a_file_a_launcher_names_exists_unless_it_asks_for_it():
    import yaml
    doc = yaml.safe_load((ROOT / "tools" / "launch" / "launchers.yaml").read_text(encoding="utf-8"))
    for row in doc["pycharm"]:
        words = str(row.get("command") or "").split()
        for i, w in enumerate(words):
            if w == "--from":
                path = words[i + 1]
                if row.get("prompt"):
                    continue
                assert (ROOT / path).exists() and path.startswith("data/testdata/"), f"{row['name']}: {path} (a sample under data/testdata/, or prompt: true)"


def test_g10_no_shipped_launcher_or_config_holds_an_os_specific_path():
    import yaml
    files = [*LAUNCHERS, *sorted((ROOT / "config" / "profiles").glob("*.ini")), ROOT / "deploy" / "stack" / "stack.yaml",
             *sorted((ROOT / "deploy" / "stack").glob("*.ini")), ROOT / "config" / "settings.examples" / "dev.ini"]
    bad = __import__("re").compile(r"/usr/lib/jvm|[A-Za-z]:\\\\|[A-Za-z]:/(?:Users|Program)")
    for f in files:
        code = "\n".join(line for line in f.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith(("#", "<!--")))
        assert not bad.search(code), f"{f.relative_to(ROOT)}: an OS-specific absolute path"
    assert "java" not in (yaml.safe_load((ROOT / "deploy" / "stack" / "stack.yaml").read_text(encoding="utf-8")) or {}), "stack.yaml: no java: (the one JDK lookup)"


def test_workspace_setup_tasks_name_no_settings_file():
    """The VS Code tasks honour `settings: false` as the run configurations do: setup makes dev.ini, so --settings would refuse it."""
    import json
    text = (ROOT / "drishti.code-workspace").read_text(encoding="utf-8")
    body = json.loads("".join(l for l in text.splitlines(True) if not l.lstrip().startswith("//")))
    tasks = {t["label"]: t for t in body["tasks"]["tasks"]}
    setups = [t for t in tasks.values() if t["command"].split()[2:3] == ["setup"] or " setup" in t["command"]]
    assert len(setups) >= 2
    for t in setups:
        assert "--settings" not in t["command"] and "--settings" not in t["windows"]["command"], t["label"]
    assert any("--settings" in t["command"] for n, t in tasks.items() if t not in setups and t["command"].startswith("python3 tools/drishti.py"))


def test_workspace_is_portable():
    """VS Code: ${userHome} (never $USER_HOME$ or ~), py -3.13 on Windows with a per-OS override, no Linux-only interpreter or JDK path."""
    import json
    text = (ROOT / "drishti.code-workspace").read_text(encoding="utf-8")
    body = json.loads("".join(l for l in text.splitlines(True) if not l.lstrip().startswith("//")))
    raw = json.dumps(body)
    assert "$USER_HOME$" not in raw and "~/" not in raw and "/usr/lib/jvm" not in raw and "java.configuration.runtimes" not in raw
    assert "python.defaultInterpreterPath" not in raw
    for t in body["tasks"]["tasks"]:
        if t["command"].startswith("python3"):
            assert t["windows"]["command"].startswith("py -3.13 tools/") or t["windows"]["command"].startswith("py -3.13 drishti-console/"), t["label"]
            assert "linux" in t and "osx" in t, t["label"]
