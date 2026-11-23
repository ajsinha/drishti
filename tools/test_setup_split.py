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

"""``drishti.py setup`` split: the subcommands (drishti, venv, libs, --system, --skip-venv), the library verification of --check, the pinned
Lake wheel installed first, the Windows certificate store for pip, and the write-once drishti.python line of dev.ini. Temp folders only;
pip is never run (a recording stand-in), nothing touches a network."""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch"), str(TOOLS / "lake")):
    if p not in sys.path:
        sys.path.insert(0, p)
import envsetup  # noqa: E402
import pylibs  # noqa: E402
from procs import LaunchError  # noqa: E402


def fake_home(tmp: Path, reqs: str = "alpha>=1.2\nbeta==2.0.1\n--find-links ../lib/wheels\ndrishti-lake==0.9.1\n", wheel: bool = True) -> Path:
    (tmp / "drishti-console").mkdir(parents=True)
    (tmp / "drishti-console" / "requirements.txt").write_text("# c\n" + reqs, encoding="utf-8")
    (tmp / "lib" / "wheels").mkdir(parents=True)
    (tmp / "lib" / "lake-wheel.pin").write_text("# pin\nref=v0.9.1\nversion=0.9.1\nsha256=x\n", encoding="utf-8")
    if wheel:
        (tmp / "lib" / "wheels" / "drishti_lake-0.9.1-py3-none-any.whl").write_bytes(b"w")
    return tmp


def asked(versions: dict):
    """A stand-in for subprocess.run: answers the metadata probe with ``versions``, records every other command."""
    calls = []

    def run(cmd, **kw):
        calls.append(cmd)
        if cmd[1:3] == ["-m", "pip"] and "--version" in cmd:
            return subprocess.CompletedProcess(cmd, 0, f"pip {versions.get('_pip', '25.0')} from /x (python 3.13)\n", "")
        if cmd[1] == "-c":
            return subprocess.CompletedProcess(cmd, 0, json.dumps({n: versions.get(n) for n in cmd[3:]}) + "\n", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")
    run.calls = calls
    return run


def test_requirements_skip_options_and_comments(tmp_path):
    home = fake_home(tmp_path)
    assert pylibs.requirements(home) == [("alpha", ">=1.2"), ("beta", "==2.0.1"), ("drishti-lake", "==0.9.1")]


def test_the_real_requirements_parse_and_name_the_lake_wheel_at_its_pin():
    root = TOOLS.parent
    names = dict(pylibs.requirements(root))
    assert names["drishti-lake"] == "==" + pylibs.lake_pin(root)["version"] and "fastapi" in names and "pyarrow" in names


@pytest.mark.parametrize("version,spec,ok", [("1.2", ">=1.2", True), ("1.1.9", ">=1.2", False), ("2.0.1", "==2.0.1", True), ("2.0.2", "==2.0.1", False),
                                             ("0.141.0", ">=0.141", True), ("1.4.3", "~=1.4", True), ("2.0", "~=1.4", False), ("2.0", ">=1,<3", True),
                                             ("3.0", ">=1,<3", False), ("1.2.3", "==1.2.*", True), ("1.3", "==1.2.*", False), ("5", "", True),
                                             ("1.0rc1", ">=1.0", True)])
def test_version_specs(version, spec, ok):
    assert pylibs.satisfies(version, spec) is ok


def test_verify_names_exactly_what_is_missing_or_wrong(tmp_path):
    home = fake_home(tmp_path)
    assert pylibs.verify("py", home, asked({"alpha": "1.2", "beta": "2.0.1", "drishti-lake": "0.9.1"})) == []
    bad = pylibs.verify("py", home, asked({"alpha": "1.1", "beta": None, "drishti-lake": "0.9.0"}))
    assert any("alpha 1.1 is installed" in b and "alpha>=1.2" in b for b in bad)
    assert any(b.startswith("beta==2.0.1 is not installed") for b in bad)
    assert any("drishti-lake 0.9.0" in b and "==0.9.1" in b for b in bad) and any("pins 0.9.1" in b for b in bad)


def test_the_lake_wheel_goes_in_first_then_the_requirements(tmp_path):
    home = fake_home(tmp_path)
    run = asked({})
    pylibs.install("py", home, windows=False, run=run)
    pips = [c for c in run.calls if c[1:4] == ["-m", "pip", "install"]]
    assert len(pips) == 2 and pips[0][-1].endswith("drishti_lake-0.9.1-py3-none-any.whl") and pips[1][-2:] == ["-r", str(home / "drishti-console" / "requirements.txt")]
    assert not any("--use-feature=truststore" in c for c in run.calls)


def test_install_without_the_wheel_says_how(tmp_path):
    with pytest.raises(LaunchError, match="lake-wheel.pin"):
        pylibs.install("py", fake_home(tmp_path, wheel=False), windows=False, run=asked({}))


@pytest.mark.parametrize("pip,windows,flag", [("24.0", True, True), ("24.1.2", True, True), ("24.2", True, False), ("25.0", True, False),
                                              ("24.0", False, False), ("23.1", True, True)])
def test_windows_pip_uses_the_certificate_store(tmp_path, pip, windows, flag):
    run = asked({"_pip": pip})
    assert (pylibs.pip_extra("py", windows, run) == ["--use-feature=truststore"]) is flag
    home = fake_home(tmp_path)
    pylibs.install("py", home, windows=windows, run=run)
    assert any("--use-feature=truststore" in c for c in run.calls if c[1:4] == ["-m", "pip", "install"]) is flag


def test_an_old_pip_that_cannot_is_told_to_upgrade(capsys):
    assert pylibs.pip_extra("py", True, asked({"_pip": "22.0"})) == []
    assert "upgrade it" in capsys.readouterr().out


def test_drishti_python_is_written_once_and_never_over_a_value(tmp_path):
    ini = tmp_path / "dev.ini"
    ini.write_text("drishti.home=/h\n# drishti.python=C:/Python313/python.exe   (example)\n[server]\n# x\n", encoding="utf-8")
    assert pylibs.ensure_setting(ini, "/usr/bin/python3", check=True)[0] == "todo" and "/usr/bin" not in ini.read_text()
    assert pylibs.ensure_setting(ini, "/usr/bin/python3")[0] == "made"
    assert ini.read_text().count("drishti.python=/usr/bin/python3") == 1 and "[server]" in ini.read_text()
    state, detail = pylibs.ensure_setting(ini, "/other/python")
    assert state == "ok" and "kept as it is" in detail and "/other/python" not in ini.read_text()
    bare = tmp_path / "bare.ini"
    bare.write_text("drishti.home=/h\n[server]\nserver.port=1\n", encoding="utf-8")
    pylibs.ensure_setting(bare, "/p/python")
    assert bare.read_text().index("drishti.python=/p/python") < bare.read_text().index("[server]")
    blank = tmp_path / "blank.ini"
    blank.write_text("drishti.python=\n", encoding="utf-8")
    assert pylibs.ensure_setting(blank, "/p/python")[0] == "made" and blank.read_text() == "drishti.python=/p/python\n"
    assert pylibs.ensure_setting(tmp_path / "missing.ini", "/p")[0] == "todo"


def ns(tmp: Path, part=None, **kw) -> argparse.Namespace:
    d = dict(part=part, system=False, check=False, home=None, data_dir=str(tmp / "data"), envs_dir=str(tmp / "envs"), java_home=None, python=None,
             skip_venv=False, reinstall=False)
    d.update(kw)
    return argparse.Namespace(**d)


def quiet(env_python=None):
    return {"DRISHTI_HOME": str(TOOLS.parent)}


@pytest.fixture
def libs():
    """pylibs.verify/install replaced: verify reports what ``state['bad']`` holds; install records and then everything is fine."""
    state = {"bad": ["alpha is not installed"], "installed": []}

    def install(py, home, *a, **k):
        state["installed"].append(py)
        state["bad"] = []
    with mock.patch.object(pylibs, "verify", lambda py, home, *a, **k: list(state["bad"])), mock.patch.object(pylibs, "install", install):
        yield state


def test_setup_drishti_does_only_the_drishti_part(tmp_path, libs, capsys):
    envsetup.setup(ns(tmp_path, "drishti"), quiet())
    out = capsys.readouterr().out
    assert (tmp_path / "envs" / "dev.ini").is_file() and (tmp_path / "data" / "run").is_dir()
    assert "libraries" not in out and "console .venv" not in out and "drishti.python" not in out.replace("drishti.python of", "")
    assert libs["installed"] == []


def test_skip_venv_is_setup_drishti(tmp_path, libs, capsys):
    envsetup.setup(ns(tmp_path, None, skip_venv=True), quiet())
    out = capsys.readouterr().out
    assert (tmp_path / "envs" / "dev.ini").is_file() and "--skip-venv" in out and libs["installed"] == []


def test_setup_libs_installs_into_the_current_python_and_writes_drishti_python_once(tmp_path, libs, capsys):
    envs = tmp_path / "envs"
    envs.mkdir()
    (envs / "dev.ini").write_text("drishti.home=/h\n", encoding="utf-8")
    rc = envsetup.setup(ns(tmp_path, "libs"), quiet())
    out = capsys.readouterr().out
    assert libs["installed"] == [sys.executable] and "[made] libraries" in out.replace("[ made]", "[made]") or "libraries" in out
    assert f"drishti.python={Path(sys.executable).as_posix()}" in (envs / "dev.ini").read_text() and rc == 0
    assert not (tmp_path / "data").exists(), "setup libs makes nothing of the Drishti part"
    (envs / "dev.ini").write_text("drishti.python=/mine/python\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path, "libs"), quiet())
    assert (envs / "dev.ini").read_text() == "drishti.python=/mine/python\n"


def test_setup_system_is_drishti_plus_libs_and_no_venv(tmp_path, libs, capsys):
    rc = envsetup.setup(ns(tmp_path, None, system=True), quiet())
    out = capsys.readouterr().out
    assert (tmp_path / "envs" / "dev.ini").is_file() and libs["installed"] == [sys.executable] and "console .venv" not in out
    assert f"drishti.python={Path(sys.executable).as_posix()}" in (tmp_path / "envs" / "dev.ini").read_text() and rc in (0, 1)
    with pytest.raises(LaunchError, match="--system"):
        envsetup.setup(ns(tmp_path, "venv", system=True), quiet())


def test_check_reports_the_libraries_by_name_and_changes_nothing(tmp_path, libs, capsys):
    libs["bad"] = ["alpha 1.1 is installed, the requirement is alpha>=1.2", "beta==2.0.1 is not installed"]
    rc = envsetup.setup(ns(tmp_path, "libs", check=True), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and "alpha 1.1 is installed" in out and "beta==2.0.1 is not installed" in out
    assert libs["installed"] == [] and not (tmp_path / "envs").exists()
    libs["bad"] = []
    assert envsetup.setup(ns(tmp_path, "libs", check=True), quiet()) == 1, "the dev.ini line is still to be written"
    assert "every requirement" in capsys.readouterr().out


def test_setup_venv_checks_the_venv_python_it_finds(tmp_path, libs, capsys):
    py = TOOLS.parent / "drishti-console" / ".venv"
    if py.exists():
        pytest.skip("this clone has a .venv of its own")
    rc = envsetup.setup(ns(tmp_path, "venv", check=True), quiet())
    assert rc == 1 and "console .venv" in capsys.readouterr().out and not (tmp_path / "data").exists()


def test_the_parser_takes_the_subcommands():
    import launchcli
    top = argparse.ArgumentParser()
    launchcli.register(top.add_subparsers(), lambda *a, **k: None)
    for argv, part, system in ((["setup"], None, False), (["setup", "drishti"], "drishti", False), (["setup", "venv", "--reinstall"], "venv", False),
                               (["setup", "libs", "--check"], "libs", False), (["setup", "--system"], None, True)):
        a = top.parse_args(argv)
        assert a.part == part and a.system is system
    with pytest.raises(SystemExit):
        top.parse_args(["setup", "bogus"])
