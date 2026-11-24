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

"""``drishti.py launchers --list`` / ``--run`` (i7j): the committed run-configs read as they are; the command, folder and environment
a launcher holds are what runs. Unit only: subprocess.run is stubbed, nothing starts."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.static

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools" / "launch")]
import launchrun  # noqa: E402
import launchers  # noqa: E402
import procs  # noqa: E402

FILES = sorted((ROOT / "run-configs").glob("*.run.xml")) + sorted((ROOT / "drishti-console" / "run-configs").glob("*.run.xml"))


def items(env=None):
    return launchrun.load(ROOT, Path("/home/u"), win=False, keys={}, env=env or {})


def test_list_parses_every_committed_launcher_none_skipped():
    got = items()
    assert FILES and len(got) == len(FILES)
    assert {l.file for l in got} == set(FILES)
    for l in got:
        assert l.words and all(w for w in l.words), l.name
        assert l.group and l.name.startswith("Drishti: ")
        assert l.name in launchrun.listing(got, False)
    assert {l.kind for l in got} == {"MavenRunConfiguration", "JarApplication", "PythonConfigurationType"}


def run_stub(monkeypatch, name, found="/jdk/bin/java", env=None):
    calls = []
    monkeypatch.setattr(launchrun.subprocess, "run", lambda words, **kw: calls.append((words, kw)) or argparse.Namespace(returncode=7))
    l = launchrun.find(items(env), name)
    assert launchrun.run(l, lambda explicit, alt: found) == 7
    (words, kw), = calls
    return l, words, kw


def test_run_maven_launcher(monkeypatch):
    l, words, kw = run_stub(monkeypatch, "Drishti: build modules")
    assert words == [str(ROOT / "mvnw"), "clean", "package", "-DskipTests", "-pl", ":drishti-modules", "-am", "-Pmodules-only"]
    assert kw["cwd"] == str(ROOT)


def test_run_python_launcher(monkeypatch):
    l, words, kw = run_stub(monkeypatch, "Drishti: run console", env={"DRISHTI_PYTHON": "/my/python"})
    assert words[:2] == ["/my/python", str(ROOT / "drishti-console" / "run_drishti_web.py")]
    assert words[2:] == ["--settings", "/home/u/drishti-envs/dev.ini", "--profile", "demo"]
    assert kw["cwd"] == str(ROOT)
    assert kw["env"]["PYTHONUNBUFFERED"] == "1" and kw["env"]["DRISHTI_TOKEN_SECRET"]


def test_run_jar_launcher(monkeypatch):
    l, words, kw = run_stub(monkeypatch, "Drishti: run server (demo)")
    assert words[0] == "/jdk/bin/java" and words[-2:] == ["-jar", str(ROOT / "lib" / "drishti-server-exec.jar")]
    assert any(w.startswith("@") and w.endswith("jvm-demo.args") for w in words) and f"-Ddrishti.home={ROOT}" in words
    assert kw["cwd"] == str(ROOT) and kw["env"]["MALLOC_ARENA_MAX"] == "2"


@pytest.mark.parametrize("name, why", [("Drishti: run", "8 launchers"), ("nonesuch", "no launcher has that name"), ("", "no name given")])
def test_ambiguous_unknown_and_empty_fail_with_fix(name, why, capsys):
    a = argparse.Namespace(run=name, list=False)
    assert launchrun.main(a, ROOT) == 1
    err = capsys.readouterr().err
    assert err.startswith("[FAIL] launcher ") and why in err and "Fix: " in err and err.rstrip().endswith("launchers --list")


def test_a_unique_prefix_and_the_exact_name_win():
    assert launchrun.find(items(), "Drishti: run console").name == "Drishti: run console"   # exact, though a prefix of three more
    assert launchrun.find(items(), "run server (demo").name == "Drishti: run server (demo)"
    assert launchrun.find(items(), "Drishti: build m").name == "Drishti: build modules"


def test_a_launcher_that_prompts_needs_input(monkeypatch, tmp_path):
    """--run of a launcher holding the IDE's $Prompt$ takes the value from --input; without it one [FAIL] with the command to use."""
    import launchrun, procs, pytest
    l = launchrun.Launcher.__new__(launchrun.Launcher)
    monkeypatch.setattr(l, "argv", lambda: ["python", "tools/drishti.py", "lake", "build", "--from", "$Prompt$"], raising=False)
    for k, v in (("name", "Drishti: run loader"), ("group", "PyCharm-console-data"), ("cwd", tmp_path), ("env", {}), ("java", None)):
        object.__setattr__(l, k, v) if hasattr(type(l), "__dataclass_fields__") else setattr(l, k, v)
    with pytest.raises(procs.LaunchError) as e:
        launchrun.run(l)
    assert "--input" in str(e.value) and "Fix:" in str(e.value)
    seen = {}
    monkeypatch.setattr(launchrun.subprocess, "run", lambda argv, **kw: seen.setdefault("argv", argv) and type("R", (), {"returncode": 0})())
    assert launchrun.run(l, value="my.jsonl") == 0 and seen["argv"][-1] == "my.jsonl"
