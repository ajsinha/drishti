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
import launchcli  # noqa: E402
import launchers  # noqa: E402
import procs  # noqa: E402
import serverstart  # noqa: E402
import settingsfile  # noqa: E402

LAUNCHERS = sorted((ROOT / "run-configs").glob("*.run.xml")) + sorted((ROOT / "drishti-console" / "run-configs").glob("*.run.xml"))


def conf(f: Path) -> ET.Element:
    return ET.parse(f).getroot().find("configuration")


def test_g1_the_committed_launchers_are_what_the_table_generates():
    """Any hand edit of a .run.xml or of IDE_GUIDE section 4 fails here: change tools/launch/launchers.yaml, then
    python tools/drishti.py launchers."""
    assert launchers.check(ROOT) == []


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
    """drishti.py start server --demo and Drishti server (demo lakes): the same JVM flags (from serverstart.jvm_flags), the same settings
    files (dev.ini, then config/profiles/demo.ini), the same environment the launcher sets; nothing apart but the pid file and the log."""
    envs = tmp_path / "drishti-envs"
    envs.mkdir()
    data = tmp_path / "data dir"                                         # a space, as C:\Users\First Last
    dev = envs / "dev.ini"
    dev.write_text(f"drishti.home={ROOT.as_posix()}\ndrishti.data.dir={data.as_posix()}\ndrishti.modules.home={(ROOT / 'modules').as_posix()}\n",
                   encoding="utf-8")
    monkeypatch.delenv("DRISHTI_DATA_DIR", raising=False)
    launchers.machine(ROOT, envs, dev)
    ide, ide_env = launchers.argv(ROOT / "run-configs" / "Drishti server (demo lakes).run.xml", ROOT, tmp_path)
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
    monkeypatch.setattr(javacmd, "java_cmd", lambda java, args, **k: [java, *args])
    monkeypatch.setattr(serverstart, "find_jar", lambda given, home: ROOT / "lib" / "drishti-server-exec.jar")
    monkeypatch.setattr(serverstart, "check_modules", lambda a, m, j: "modules: test")
    monkeypatch.setattr(procs, "port_in_use", lambda p: False)
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
            assert o.get("WORKING_DIRECTORY") == "$PROJECT_DIR$/..", f.name
            assert "$PROJECT_DIR$/data" not in text, f"{f.name}: PyCharm's $PROJECT_DIR$ is drishti-console/"
        if c.get("type") == "JarApplication":
            assert o.get("WORKING_DIRECTORY") == "$PROJECT_DIR$", f.name
        for value in (o.get("VM_PARAMETERS", ""), o.get("PARAMETERS", "")):
            for token in re.findall(r'"[^"]*"|\S+', value):              # a macro path is one quoted word: C:\Users\First Last stays whole
                assert token.startswith('"') or "$USER_HOME$" not in token and "$PROJECT_DIR$" not in token, f"{f.name}: {token} unquoted"
        assert "java.io.tmpdir" not in text and "HeapDumpPath" not in text, f"{f.name}: the temp and dump folders follow the data folder"


def test_the_argv_helper_turns_a_python_launcher_into_its_command_line(tmp_path):
    cmd, env = launchers.argv(ROOT / "drishti-console" / "run-configs" / "Drishti console (QA, port 17580).run.xml", ROOT, tmp_path)
    assert cmd[1] == f"{ROOT.as_posix()}/drishti-console/run_drishti_web.py"
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
