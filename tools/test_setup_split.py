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
    assert len(pips) == 2 and pips[0][-1].endswith("drishti_lake-0.9.1-py3-none-any.whl") and pips[1][-2] == "-r" and pips[1][-1].endswith("requirements.txt") and "find-links" not in Path(pips[1][-1]).read_text(encoding="utf-8")
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


def test_the_console_runs_the_python_setup_named_and_makes_no_venv_then(tmp_path):
    """start console on a machine with no .venv: drishti.python from the settings is used and no venv is made."""
    import importlib, sys as _sys
    _sys.path.insert(0, str(Path(__file__).resolve().parent))
    cs = importlib.import_module("launch.consolestart")
    made = []
    venv = lambda home, base, reinstall: made.append(base) or tmp_path / "venv-python"
    keys = {"drishti.python": "C:/Program Files/Python313/python.exe"}
    assert cs.console_python({}, keys, tmp_path, venv=venv) == "C:/Program Files/Python313/python.exe" and not made
    assert cs.console_python({"DRISHTI_PYTHON": "/opt/py"}, keys, tmp_path, venv=venv) == "/opt/py" and not made
    assert cs.console_python({}, {}, tmp_path, venv=venv) == str(tmp_path / "venv-python") and made     # no setting: the .venv
    made.clear()
    assert cs.console_python({}, keys, tmp_path, reinstall=True, venv=venv) == str(tmp_path / "venv-python") and made   # asked for the .venv


# ---- a Python with only the standard library (the company Python of a machine that allows no venv) ---------------------------------

def test_setup_libs_check_runs_without_pyyaml():
    """python -S has no site packages: the library report, exit 0 or 1, never the 'yaml is not installed' exit 2."""
    r = subprocess.run([sys.executable, "-S", "-B", str(TOOLS / "drishti.py"), "setup", "libs", "--check"], capture_output=True, text=True)
    assert r.returncode in (0, 1), r.stderr
    assert "yaml is not installed" not in r.stderr and "libraries" in r.stdout


def test_setup_system_check_runs_without_pyyaml_and_says_what_it_needs():
    r = subprocess.run([sys.executable, "-S", "-B", str(TOOLS / "drishti.py"), "setup", "--check", "--system"], capture_output=True, text=True)
    assert r.returncode == 1 and "ModuleNotFoundError" not in r.stderr and "yaml is not installed" not in r.stderr
    assert "needs PyYAML" in r.stdout


def _args(**kw):
    d = dict(check=False, part=None, system=False, skip_venv=False, python=None, reinstall=False)
    d.update(kw)
    return argparse.Namespace(**d)


def test_bootstrap_system_installs_with_pip_then_runs_the_same_command_again(tmp_path):
    home = fake_home(tmp_path)
    calls = []

    def run(cmd, **kw):
        calls.append((list(cmd), kw))
        return subprocess.CompletedProcess(cmd, 0, "", "")
    with mock.patch.object(pylibs, "install") as inst:
        rc = envsetup.bootstrap(_args(system=True), home, {}, ["/x/tools/drishti.py", "--settings", "a.ini", "setup", "--system"], run, lambda: False)
    assert rc == 0 and inst.call_args[0][0] == sys.executable
    cmd, kw = calls[-1]
    assert cmd[0] == sys.executable and cmd[2:] == ["--settings", "a.ini", "setup", "--system"]
    assert kw["env"][envsetup.REEXEC_ENV] == "1"


def test_bootstrap_default_makes_the_venv_and_reruns_under_it(tmp_path):
    home = fake_home(tmp_path)
    calls = []
    with mock.patch("consolestart.make_venv", return_value=tmp_path / "v" / "python") as mv:
        rc = envsetup.bootstrap(_args(), home, {}, ["/x/drishti.py", "setup"], lambda cmd, **kw: calls.append(cmd) or subprocess.CompletedProcess(cmd, 0), lambda: False)
    assert rc == 0 and mv.called and calls[-1][0] == str(tmp_path / "v" / "python") and calls[-1][-1] == "setup"


def test_bootstrap_is_skipped_with_yaml_or_check_and_never_loops(tmp_path):
    home = fake_home(tmp_path)
    assert envsetup.bootstrap(_args(system=True), home, {}, ["x"], yaml_here=lambda: True) is None
    assert envsetup.bootstrap(_args(system=True, check=True), home, {}, ["x"], yaml_here=lambda: False) is None
    assert envsetup.bootstrap(_args(part="libs"), home, {}, ["x"], yaml_here=lambda: False) is None
    with pytest.raises(LaunchError):
        envsetup.bootstrap(_args(system=True), home, {envsetup.REEXEC_ENV: "1"}, ["x"], yaml_here=lambda: False)


# ---- i7d: no pip for what is satisfied, a plain sentence when pip fails, --wheels (offline) ------------------------------------------

def pip_run(versions: dict, pip_out: str = "", rc: int = 0):
    """asked(), but a pip install answers ``rc`` with ``pip_out`` on stderr; the probe still answers ``versions``."""
    base = asked(versions)

    def run(cmd, **kw):
        if cmd[1:4] == ["-m", "pip", "install"]:
            base.calls.append(cmd)
            return subprocess.CompletedProcess(cmd, rc, "", pip_out)
        return base(cmd, **kw)
    run.calls = base.calls
    return run


def pips(run):
    return [c for c in run.calls if c[1:4] == ["-m", "pip", "install"]]


def test_install_never_calls_pip_when_everything_is_satisfied(tmp_path, capsys):
    home = fake_home(tmp_path)
    run = pip_run({"alpha": "1.3", "beta": "2.0.1", "drishti-lake": "0.9.1"})
    pylibs.install("py", home, windows=False, run=run)
    assert pips(run) == [] and "pip is not run" in capsys.readouterr().out


def test_install_skips_the_lake_wheel_when_it_is_at_its_pin_and_installs_the_rest(tmp_path):
    home = fake_home(tmp_path)
    run = pip_run({"alpha": None, "beta": "2.0.1", "drishti-lake": "0.9.1"})
    pylibs.install("py", home, windows=False, run=run)
    assert len(pips(run)) == 1 and pips(run)[0][-2] == "-r"
    run = pip_run({"alpha": "1.3", "beta": "2.0.1", "drishti-lake": "0.9.0"})   # only the Lake wheel is wrong
    pylibs.install("py", home, windows=False, run=run)
    assert len(pips(run)) == 1 and pips(run)[0][-1].endswith(".whl")


def test_a_401_gives_one_sentence_and_one_line_to_copy_and_pips_output_is_shown(tmp_path, capsys):
    home = fake_home(tmp_path)
    err = "WARNING: Looking in indexes: https://mirror.corp.example/simple\nERROR: 401 Client Error: Credentials not correct for url: https://mirror.corp.example/simple/alpha/\n"
    run = pip_run({"alpha": None, "beta": None, "drishti-lake": "0.9.1"}, err, 1)
    with pytest.raises(LaunchError) as e:
        pylibs.install("py", home, windows=False, run=run)
    lines = str(e.value).splitlines()
    assert lines[0].startswith("your package index (mirror.corp.example) rejected the login: fix its credentials in pip.ini, or install offline with --wheels <folder>")
    assert lines[-1].endswith('-m pip install "alpha>=1.2" beta==2.0.1') and len(lines) == 2
    assert "401 Client Error" in capsys.readouterr().out


@pytest.mark.parametrize("err,words", [("SSLError(SSLCertVerificationError CERTIFICATE_VERIFY_FAILED) host='pypi.corp.example'", "certificate was not trusted"),
                                       ("Max retries exceeded with url: /simple/alpha/ HTTPSConnectionPool(host='pypi.corp.example', port=443)", "could not be reached"),
                                       ("ERROR: No matching distribution found for beta==2.0.1", "lacks beta==2.0.1")])
def test_other_pip_failures_get_their_sentence(tmp_path, err, words):
    run = pip_run({"alpha": None, "beta": None, "drishti-lake": "0.9.1"}, err, 1)
    with pytest.raises(LaunchError, match=words):
        pylibs.install("py", fake_home(tmp_path), windows=False, run=run)


def make_wheels(folder: Path, names=("alpha", "beta")) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    for n in names:
        (folder / f"{n}-1.0-py3-none-any.whl").write_bytes(b"w")
    return folder


def test_wheels_folder_installs_only_from_it_lake_first(tmp_path):
    home = fake_home(tmp_path / "h")
    wheels = make_wheels(tmp_path / "w")
    run = pip_run({})
    pylibs.install("py", home, windows=True, run=run, wheels=wheels)
    a, b = pips(run)
    assert a[-1].endswith("drishti_lake-0.9.1-py3-none-any.whl") and b[-2] == "-r"
    for c in (a, b):
        assert "--no-index" in c and c[c.index("--find-links") + 1] == str(wheels) and "--use-feature=truststore" not in c


def test_a_pack_unzipped_one_folder_deep_gives_pip_the_folder_with_the_wheels(tmp_path):
    home = fake_home(tmp_path / "h")
    wheels = make_wheels(tmp_path / "w" / "drishti-wheels")
    run = pip_run({})
    pylibs.install("py", home, windows=False, run=run, wheels=tmp_path / "w")
    for c in pips(run):
        assert c[c.index("--find-links") + 1] == str(wheels)


def test_a_pack_for_another_python_is_refused_in_one_sentence_and_pip_never_runs(tmp_path):
    home = fake_home(tmp_path / "h")
    wheels = make_wheels(tmp_path / "w", ("beta",))
    (wheels / "alpha-1.0-cp313-cp313-win_amd64.whl").write_bytes(b"w")
    run = pip_run({})
    base = run

    def probing(cmd, **kw):
        if cmd[1] == "-c" and "PYVER" in cmd[2]:
            return subprocess.CompletedProcess(cmd, 0, "PYVER 3.14\n", "")
        return base(cmd, **kw)
    probing.calls = base.calls
    with pytest.raises(LaunchError) as e:
        pylibs.install("py", home, windows=True, run=probing, wheels=wheels)
    assert "this pack is for Python 3.13; this Python is 3.14: run it with `py -3.13" in str(e.value) and "index" not in str(e.value)
    assert not pips(probing)


def test_pip_failing_under_wheels_names_the_wheel_and_never_the_index(tmp_path):
    home = fake_home(tmp_path / "h")
    run = pip_run({}, "ERROR: No matching distribution found for numpy==2.4.0", 1)
    with pytest.raises(LaunchError) as e:
        pylibs.install("py", home, windows=False, run=run, wheels=make_wheels(tmp_path / "w"))
    assert "numpy==2.4.0" in str(e.value) and "index" not in str(e.value.args[0]).replace("pip download on a machine with an index", "") and "--wheels" not in str(e.value)


def test_wheels_zip_is_unzipped_and_removed_afterwards(tmp_path):
    import zipfile
    z = tmp_path / "pack.zip"
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("pack/alpha-1.0-py3-none-any.whl", b"w")
    with pylibs.wheel_folder(str(z)) as folder:
        assert "alpha" in pylibs.wheel_names(folder)
        kept = folder
    assert not kept.exists()
    with pytest.raises(LaunchError, match="no such folder"):
        with pylibs.wheel_folder(str(tmp_path / "nope")):
            pass
    assert pylibs.wheel_folder(None).__enter__() is None


def test_a_wheel_folder_that_lacks_a_wheel_is_refused_in_words_and_pip_never_runs(tmp_path):
    home = fake_home(tmp_path / "h")
    run = pip_run({})
    with pytest.raises(LaunchError, match="no wheel for beta"):
        pylibs.install("py", home, windows=False, run=run, wheels=make_wheels(tmp_path / "w", ("alpha",)))
    assert pips(run) == []
    with pytest.raises(LaunchError, match="pinned Lake wheel is not in"):
        pylibs.install("py", fake_home(tmp_path / "g", wheel=False), windows=False, run=run, wheels=make_wheels(tmp_path / "w2"))


def test_setup_libs_wheels_and_the_env_variable_reach_install(tmp_path, capsys):
    wheels = make_wheels(tmp_path / "w")
    seen = []
    with mock.patch.object(pylibs, "verify", return_value=["alpha is not installed"]), mock.patch.object(pylibs, "install", lambda *a, **k: seen.append(k.get("wheels"))):
        envsetup.setup(ns(tmp_path, "libs", wheels=str(wheels)), quiet())
        envsetup.setup(ns(tmp_path, "libs"), {**quiet(), "DRISHTI_WHEELS": str(wheels)})
        envsetup.setup(ns(tmp_path, "libs", check=True, wheels=str(wheels)), quiet())
    assert seen == [wheels, wheels]


def test_setup_libs_satisfied_never_installs(tmp_path, libs, capsys):
    libs["bad"] = []
    envsetup.setup(ns(tmp_path, "libs"), quiet())
    assert libs["installed"] == [] and "every requirement" in capsys.readouterr().out


def test_stdlib_only_python_with_wheels_names_the_missing_wheel(tmp_path):
    w = tmp_path / "w"
    w.mkdir()
    r = subprocess.run([sys.executable, "-S", "-B", str(TOOLS / "drishti.py"), "setup", "libs", "--wheels", str(w)], capture_output=True, text=True)
    assert "ModuleNotFoundError" not in r.stderr and "wheel" in (r.stdout + r.stderr)


# ---- python.lock: the compute pool's environment is installed and checked with requirements.txt ------------------------------------

LOCK_TEXT = "# lock\nnumpy==2.5.3 \\\n    --hash=sha256:aa \\\n    --hash=sha256:bb\nsix==1.17.0 \\\n    --hash=sha256:cc\n"


def with_lock(home: Path, text: str = LOCK_TEXT) -> Path:
    f = home / "config" / "compute" / "locks" / "python.lock"
    f.parent.mkdir(parents=True)
    f.write_text(text, encoding="utf-8")
    return home


ALL_OK = {"alpha": "1.3", "beta": "2.0.1", "drishti-lake": "0.9.1", "numpy": "2.5.3", "six": "1.17.0"}


def test_the_lock_is_parsed_without_its_hash_lines(tmp_path):
    home = with_lock(fake_home(tmp_path))
    assert pylibs.lock_requirements(home) == [("numpy", "==2.5.3"), ("six", "==1.17.0")]
    assert [n for n, _, _ in pylibs.all_requirements(home)] == ["alpha", "beta", "drishti-lake", "numpy", "six"]


def test_check_names_the_file_a_missing_pin_comes_from(tmp_path):
    home = with_lock(fake_home(tmp_path))
    bad = pylibs.verify("py", home, asked({**ALL_OK, "numpy": None, "alpha": None, "six": "1.16.0"}))
    assert any("numpy==2.5.3 is not installed" in b and "config/compute/locks/python.lock" in b for b in bad)
    assert any(b.startswith("six 1.16.0") and "python.lock" in b for b in bad)
    assert any(b.startswith("alpha>=1.2") and "drishti-console/requirements.txt" in b for b in bad)
    assert pylibs.verify("py", home, asked(ALL_OK)) == []


def test_install_adds_the_lock_after_requirements_and_skips_what_is_satisfied(tmp_path):
    home = with_lock(fake_home(tmp_path))
    run = pip_run({**ALL_OK, "numpy": None, "alpha": None})
    pylibs.install("py", home, windows=False, run=run)
    a, b = pips(run)
    assert a[-1].endswith("requirements.txt") and b[-1] == str(home / "config" / "compute" / "locks" / "python.lock")
    run = pip_run({**ALL_OK, "six": None})                  # only the lock is short: one pip, the lock alone
    pylibs.install("py", home, windows=False, run=run)
    assert [c[-1] for c in pips(run)] == [str(home / "config" / "compute" / "locks" / "python.lock")]
    run = pip_run(ALL_OK)
    pylibs.install("py", home, windows=False, run=run)
    assert pips(run) == []


def test_wheels_cover_the_lock_and_a_missing_one_is_named(tmp_path):
    home = with_lock(fake_home(tmp_path / "h"))
    run = pip_run({})
    with pytest.raises(LaunchError, match="no wheel for numpy"):
        pylibs.install("py", home, windows=False, run=run, wheels=make_wheels(tmp_path / "w", ("alpha", "beta", "six")))
    assert pips(run) == []
    wheels = make_wheels(tmp_path / "w2", ("alpha", "beta", "numpy", "six"))
    pylibs.install("py", home, windows=False, run=run, wheels=wheels)
    assert len(pips(run)) == 3 and all("--no-index" in c for c in pips(run)) and pips(run)[-1][-1].endswith("python.lock")


def test_files_that_disagree_on_a_pin_fail_naming_both(tmp_path):
    home = with_lock(fake_home(tmp_path, reqs="alpha>=1.2\nnumpy==2.4.0\n--find-links ../lib/wheels\ndrishti-lake==0.9.1\n"))
    with pytest.raises(LaunchError, match=r"requirements.txt and config/compute/locks/python.lock disagree on numpy"):
        pylibs.verify("py", home, asked({}))
    with pytest.raises(LaunchError, match="disagree"):
        pylibs.install("py", home, windows=False, run=pip_run({}))


def test_the_real_lock_and_requirements_agree():
    home = TOOLS.parent
    assert pylibs.lock_requirements(home) and pylibs.all_requirements(home)


def test_pip_reads_the_requirements_without_their_find_links_line(tmp_path):
    """The requirements' --find-links ../lib/wheels is relative to drishti-console/: pip would warn once per package; setup drops it."""
    import importlib, sys as _sys
    _sys.path.insert(0, str(Path(__file__).resolve().parent / "launch"))
    pylibs = importlib.import_module("pylibs")
    req = tmp_path / "requirements.txt"
    req.write_text("# comment\n--find-links ../lib/wheels\n-f ../other\nfastapi>=0.110\ndrishti-lake==0.9.1\n", encoding="utf-8")
    text = pylibs.plain_requirements(req).read_text(encoding="utf-8")
    assert "find-links" not in text and "-f " not in text
    assert "fastapi>=0.110" in text and "drishti-lake==0.9.1" in text and "# comment" in text


def test_an_old_dev_ini_naming_another_checkout_is_reported_and_never_overwritten(tmp_path, libs, capsys):
    envs = tmp_path / "envs"
    envs.mkdir()
    old = "drishti.home=/somewhere/else/drishti\n"
    (envs / "dev.ini").write_text(old, encoding="utf-8")
    assert envsetup.setup(ns(tmp_path, "drishti", check=True), quiet()) == 1
    out = capsys.readouterr().out
    assert "[FAIL] settings file" in out and "points at /somewhere/else/drishti, not this one" in out and "dev-old.ini" in out and "run setup again" in out
    assert envsetup.setup(ns(tmp_path, "drishti"), quiet()) in (0, 1)
    out = capsys.readouterr().out
    assert "[WARN] settings file" in out and "points at /somewhere/else/drishti, not this one" in out
    assert (envs / "dev.ini").read_text(encoding="utf-8") == old
    (envs / "dev.ini").write_text(f"drishti.home={TOOLS.parent.as_posix()}\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path, "drishti"), quiet())
    assert "points at" not in capsys.readouterr().out
