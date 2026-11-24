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

"""``drishti.py setup`` split: the subcommands (drishti, libs, --system), the library verification of --check, the pinned
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


@pytest.fixture(autouse=True)
def no_home_wheel_pack(tmp_path, monkeypatch):
    """~/drishti-wheels of this machine is never read by a test: setup's default wheel folder is a folder that is not there."""
    monkeypatch.setenv("HOME", str(tmp_path / "no-home"))
    monkeypatch.setenv("USERPROFILE", str(tmp_path / "no-home"))
    real = pylibs.find_pack
    monkeypatch.setattr(pylibs, "find_pack", lambda wheels=None, env=None, places=None, **k: real(wheels, env, [] if places is None else places, **k))


def ns(tmp: Path, part=None, **kw) -> argparse.Namespace:
    d = dict(part=part, system=False, check=False, home=None, data_dir=str(tmp / "data"), envs_dir=str(tmp / "envs"), java_home=None, python=None,
             skip_venv=False, reinstall=False)
    d.update(kw)
    return argparse.Namespace(**d)


def quiet(env_python=None):
    return {"DRISHTI_HOME": str(TOOLS.parent)}


@pytest.fixture
def libs():
    """pylibs.verify replaced: it reports what ``state['bad']`` holds. Nothing can install (owner rule): ``installed`` stays empty."""
    state = {"bad": ["alpha is not installed"], "installed": []}
    with mock.patch.object(pylibs, "verify", lambda py, home, *a, **k: list(state["bad"])):
        yield state


def test_setup_drishti_does_only_the_drishti_part(tmp_path, libs, capsys):
    envsetup.setup(ns(tmp_path, "drishti"), quiet())
    out = capsys.readouterr().out
    assert (tmp_path / "envs" / "dev.ini").is_file() and (tmp_path / "data" / "run").is_dir()
    assert "] libraries" not in out and libs["installed"] == []
    assert f"drishti.python={Path(sys.executable).as_posix()}" in (tmp_path / "envs" / "dev.ini").read_text(), "the one Python, whenever setup runs"


def test_setup_libs_checks_the_current_python_installs_nothing_and_writes_drishti_python_once(tmp_path, libs, capsys):
    envs = tmp_path / "envs"
    envs.mkdir()
    (envs / "dev.ini").write_text(f"drishti.home={TOOLS.parent.as_posix()}\n", encoding="utf-8")
    rc = envsetup.setup(ns(tmp_path, "libs"), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and "[FAIL] libraries" in out and "Drishti installs nothing" in out and "-m pip install -r requirements.txt" in out
    libs["bad"] = []
    rc = envsetup.setup(ns(tmp_path, "libs"), quiet())
    assert "[  ok] libraries" in capsys.readouterr().out
    assert f"drishti.python={Path(sys.executable).as_posix()}" in (envs / "dev.ini").read_text() and rc == 0
    assert not (tmp_path / "data").exists(), "setup libs makes nothing of the Drishti part"
    mine = tmp_path / "mine-python"
    mine.write_text("")                                                     # another Python that is there: kept
    (envs / "dev.ini").write_text(f"drishti.python={mine.as_posix()}\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path, "libs"), quiet())
    assert (envs / "dev.ini").read_text() == f"drishti.python={mine.as_posix()}\n"


def test_setup_is_drishti_plus_libs_on_every_system_and_system_changes_nothing(tmp_path, libs, capsys, monkeypatch):
    import procs
    for windows in (True, False):
        monkeypatch.setattr(procs, "windows", lambda w=windows: w)
        for system in (False, True):                  # --system: accepted for old commands, the same run
            libs["bad"], libs["installed"] = ["alpha is not installed"], []
            rc = envsetup.setup(ns(tmp_path, None, system=system), quiet())
            capsys.readouterr()
            assert (tmp_path / "envs" / "dev.ini").is_file() and libs["installed"] == []
            assert f"drishti.python={Path(sys.executable).as_posix()}" in (tmp_path / "envs" / "dev.ini").read_text() and rc in (0, 1)
    assert not hasattr(envsetup, "make_" + "venv") and not hasattr(procs, "venv_python")


def test_without_a_wheel_pack_nothing_is_installed_and_the_owners_command_comes_first(tmp_path, libs, capsys):
    rc = envsetup.setup(ns(tmp_path, None, system=True), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and libs["installed"] == [] and "[FAIL] libraries" in out and "Drishti installs nothing" in out
    first = out.split("Next steps", 1)[1].splitlines()[1]
    assert "drishti-console" in first and "-m pip install -r requirements.txt   then   " in first
    assert "-m pip install -r ../config/compute/locks/python.lock   (uses your package index)" in first
    assert "wheel pack zip in your Downloads folder" in first or "Downloads folder" in first
    assert "start all" not in out.split("Next steps", 1)[1]
    assert out.rstrip().splitlines()[-1].startswith("Drishti is NOT ready:"), "the verdict is the last line (S9)"
    assert f"drishti.python={Path(sys.executable).as_posix()}" in (tmp_path / "envs" / "dev.ini").read_text()


def test_a_drishti_python_that_is_not_there_is_replaced_and_one_that_is_kept(tmp_path, libs, capsys):
    libs["bad"] = []
    envs = tmp_path / "envs"
    envs.mkdir()
    (envs / "dev.ini").write_text("drishti.python=/gone/bin/python\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path, "libs"), quiet())
    out = capsys.readouterr().out
    assert (envs / "dev.ini").read_text() == f"drishti.python={Path(sys.executable).as_posix()}\n" and "no such Python" in out
    assert pylibs.stale_python("/gone/bin/python") is True and pylibs.stale_python(sys.executable) is False


def test_check_reports_the_libraries_by_name_and_changes_nothing(tmp_path, libs, capsys):
    libs["bad"] = ["alpha 1.1 is installed, the requirement is alpha>=1.2", "beta==2.0.1 is not installed"]
    rc = envsetup.setup(ns(tmp_path, "libs", check=True), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and "alpha 1.1 is installed" in out and "beta==2.0.1 is not installed" in out
    assert libs["installed"] == [] and not (tmp_path / "envs").exists()
    libs["bad"] = []
    assert envsetup.setup(ns(tmp_path, "libs", check=True), quiet()) == 1, "the dev.ini line is still to be written"
    assert "every requirement" in capsys.readouterr().out


def test_the_parser_takes_the_subcommands():
    import launchcli
    top = argparse.ArgumentParser()
    launchcli.register(top.add_subparsers(), lambda *a, **k: None)
    for argv, part, system in ((["setup"], None, False), (["setup", "drishti"], "drishti", False),
                               (["setup", "libs", "--check"], "libs", False), (["setup", "--system"], None, True)):
        a = top.parse_args(argv)
        assert a.part == part and a.system is system
    for gone in (["setup", "bogus"], ["setup", "venv"], ["setup", "--reinstall"], ["setup", "--skip-venv"]):
        with pytest.raises(SystemExit):
            top.parse_args(gone)


def test_the_console_runs_the_one_python_and_makes_no_venv(tmp_path):
    """start console: DRISHTI_PYTHON > drishti.python > the Python running drishti.py; never a venv made or looked for, never pip."""
    import consolestart as cs
    py = Path(sys.executable)
    ok = lambda p, home: []
    assert cs.console_python({}, {"drishti.python": str(py)}, tmp_path, verify=ok) == str(py)
    assert cs.console_python({"DRISHTI_PYTHON": str(py)}, {"drishti.python": "/nope"}, tmp_path, verify=ok) == str(py)
    assert cs.console_python({}, {}, tmp_path, verify=ok) == sys.executable                  # no setting: this Python


def test_start_console_refuses_an_unprepared_python_with_the_setup_line_and_makes_nothing(tmp_path):
    import consolestart as cs
    with mock.patch.object(subprocess, "run") as mv:
        with pytest.raises(LaunchError, match="is not there") as e:
            cs.console_python({}, {"drishti.python": str(tmp_path / "no-python")}, tmp_path, settings="C:/u/drishti-envs/dev.ini", verify=lambda p, h: [])
        assert "--settings C:/u/drishti-envs/dev.ini setup" in str(e.value)
        with pytest.raises(LaunchError, match=r"^\[FAIL\] (.|\n)*lacks libraries(.|\n)*installs nothing(.|\n)*alpha>=1.2 is not installed(.|\n)*"
                                              r"Fix: cd .*drishti-console.*then.* -m pip install -r requirements\.txt"):
            cs.console_python({"HOME": str(tmp_path / "h")}, {"drishti.python": sys.executable}, tmp_path, verify=lambda p, h: ["alpha>=1.2 is not installed"])
    assert not mv.called and list(tmp_path.iterdir()) == []


def test_start_python_and_reinstall_point_at_setup():
    import argparse as ap
    import consolestart as cs
    for kw, moved in ((dict(python="/x/python"), "drishti.py setup"), (dict(reinstall=True), "drishti.py setup")):
        with pytest.raises(LaunchError, match=moved):
            cs.prepared_python(ap.Namespace(**{"python": None, "reinstall": False, **kw}), {}, {}, TOOLS.parent)


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


def make_wheels(folder: Path, names=("alpha", "beta")) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    for n in names:
        (folder / f"{n}-1.0-py3-none-any.whl").write_bytes(b"w")
    return folder


def test_wheels_zip_is_unzipped_into_drishti_wheels_and_kept(tmp_path, monkeypatch):
    import zipfile
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("USERPROFILE", str(tmp_path / "home"))
    z = tmp_path / "pack.zip"
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("pack/alpha-1.0-py3-none-any.whl", b"w")
    with pylibs.wheel_folder(str(z)) as folder:
        assert "alpha" in pylibs.wheel_names(folder)
        kept = folder
    assert kept == tmp_path / "home" / "drishti-wheels" / "pack" and kept.exists(), "unzipped once, kept for the next run"
    with pytest.raises(LaunchError, match="no such folder"):
        with pylibs.wheel_folder(str(tmp_path / "nope")):
            pass
    assert pylibs.wheel_folder(None).__enter__() is None


def test_setup_libs_satisfied_never_installs(tmp_path, libs, capsys):
    libs["bad"] = []
    envsetup.setup(ns(tmp_path, "libs"), quiet())
    assert libs["installed"] == [] and "every requirement" in capsys.readouterr().out


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


def test_files_that_disagree_on_a_pin_fail_naming_both(tmp_path):
    home = with_lock(fake_home(tmp_path, reqs="alpha>=1.2\nnumpy==2.4.0\n--find-links ../lib/wheels\ndrishti-lake==0.9.1\n"))
    with pytest.raises(LaunchError, match=r"requirements.txt and config/compute/locks/python.lock disagree on numpy"):
        pylibs.verify("py", home, asked({}))


def test_the_real_lock_and_requirements_agree():
    home = TOOLS.parent
    assert pylibs.lock_requirements(home) and pylibs.all_requirements(home)


def test_an_old_dev_ini_naming_another_checkout_is_moved_to_a_dated_folder_and_a_fresh_one_written(tmp_path, libs, capsys):
    envs = tmp_path / "envs"
    envs.mkdir()
    old = "drishti.home=/somewhere/else/drishti\n"
    (envs / "dev.ini").write_text(old, encoding="utf-8")
    assert envsetup.setup(ns(tmp_path, "drishti", check=True), quiet()) == 1
    out = capsys.readouterr().out
    assert "names drishti.home=/somewhere/else/drishti" in out and "setup moves it to" in out
    assert (envs / "dev.ini").read_text(encoding="utf-8") == old and not list(envs.glob("replaced-*")), "--check moves nothing"
    assert envsetup.setup(ns(tmp_path, "drishti"), quiet()) in (0, 1)
    out = capsys.readouterr().out
    moved = list(envs.glob("replaced-*"))
    assert len(moved) == 1 and (moved[0] / "dev.ini").read_text(encoding="utf-8") == old
    assert f"dev.ini moved to {moved[0]}" in out and "[WARN]" not in out
    assert f"drishti.home={TOOLS.parent.as_posix()}" in (envs / "dev.ini").read_text(encoding="utf-8")
    envsetup.setup(ns(tmp_path, "drishti"), quiet())
    assert "moved to" not in capsys.readouterr().out and len(list(envs.glob("replaced-*"))) == 1


# ---- i7g: setup prepares, start only starts ----------------------------------------------------------------------------------------

def test_next_steps_never_say_start_while_a_library_step_failed(tmp_path, capsys):
    """A library is missing: the first next step is the owner's pip command (Drishti installs nothing), and no start line is printed."""
    with mock.patch.object(pylibs, "verify", lambda *a, **k: ["alpha is not installed"]), mock.patch.object(pylibs, "externally_managed", lambda *a, **k: False):
        rc = envsetup.setup(ns(tmp_path, None, system=True), quiet())
    out = capsys.readouterr().out
    steps = out.split("Next steps", 1)[1].splitlines()[1:]
    assert rc == 1 and "-m pip install" in steps[0] and "Drishti never installs libraries itself" in steps[0]
    assert "start all" not in out.split("Next steps", 1)[1] and "lake demo" not in out.split("Next steps", 1)[1]


def test_next_steps_say_start_when_all_is_ready(tmp_path, libs, capsys):
    libs["bad"] = []
    rc = envsetup.setup(ns(tmp_path, None, system=True), quiet())
    steps = [l for l in capsys.readouterr().out.split("Next steps", 1)[1].splitlines()[1:] if l.strip() and not l.startswith("Drishti is")]
    if any("start all --demo" in l for l in steps):       # only when nothing else is left: the demo lake, then start
        assert len(steps) == 2 and "lake demo" in steps[0] and rc == 0
    else:                                                 # e.g. no server jar in this checkout: the fixes, then setup again
        assert "setup   (again" in steps[-1] and len(steps) > 1


def test_the_tools_take_this_checkouts_dev_ini_by_themselves_and_never_another_copys(tmp_path, monkeypatch):
    """i7g: no --settings needed on the command line: ~/drishti-envs/dev.ini when it names THIS checkout; another copy's is never taken."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("drishti_cli_default_settings", TOOLS / "drishti.py")
    cli = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = cli
    spec.loader.exec_module(cli)
    monkeypatch.setattr(cli.pathlib.Path, "home", classmethod(lambda c: tmp_path))
    monkeypatch.delenv("USERPROFILE", raising=False)
    assert cli.default_settings() is None
    ini = tmp_path / "drishti-envs" / "dev.ini"
    ini.parent.mkdir()
    ini.write_text(f"drishti.home={TOOLS.parent.as_posix()}\n", encoding="utf-8")
    assert cli.default_settings() == str(ini)
    ini.write_text("drishti.home=/another/copy\n", encoding="utf-8")
    assert cli.default_settings() is None


def test_an_operating_system_python_is_one_fail_line_and_pip_never_runs(tmp_path, capsys):
    """PEP 668 (EXTERNALLY-MANAGED): no wall of pip text, no --break-system-packages, no venv: one [FAIL] line naming a dedicated Python."""
    pack = tmp_path / "w"
    pack.mkdir()
    (pack / "alpha-1.2-py3-none-any.whl").write_bytes(b"w")
    with mock.patch.object(pylibs, "verify", lambda *a, **k: ["alpha is not installed"]), \
            mock.patch.object(pylibs, "externally_managed", lambda py, run=None: True):
        rc = envsetup.setup(ns(tmp_path, "libs", wheels=str(pack)), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and out.count(pylibs.MANAGED) >= 1 and "[FAIL] libraries" in out and out.count("[FAIL]") == 1
    assert "break-system-packages" not in out and "-m pip install" not in out


def test_externally_managed_reads_the_marker_of_a_python_outside_a_venv(tmp_path):
    def run(cmd, **kw):
        return subprocess.CompletedProcess(cmd, 0, "MANAGED 1\n", "")
    assert pylibs.externally_managed("/usr/bin/python3", run) is True
    assert pylibs.externally_managed("/x", lambda c, **k: subprocess.CompletedProcess(c, 0, "MANAGED 0\n", "")) is False
    assert pylibs.externally_managed(sys.executable) in (True, False)            # the real probe runs


def test_a_wheel_pack_for_another_python_is_one_fail_line_naming_both_versions(tmp_path, capsys):
    pack = tmp_path / "w"
    pack.mkdir()
    (pack / "numpy-2.5.3-cp313-cp313-manylinux_2_28_x86_64.whl").write_bytes(b"w")
    v314 = lambda cmd, **kw: subprocess.CompletedProcess(cmd, 0, "PYVER 3.14\n", "")
    said = pylibs.pack_mismatch("/usr/bin/python3", pack, v314, windows=False)
    assert "for Python 3.13" in said and "this Python is 3.14" in said and "/opt/python3.13" in said
    assert "py -3.13" in pylibs.pack_mismatch("py", pack, v314, windows=True)
    assert pylibs.pack_mismatch("/p", pack, lambda c, **k: subprocess.CompletedProcess(c, 0, "PYVER 3.13\n", "")) == ""
    with mock.patch.object(pylibs, "verify", lambda *a, **k: ["numpy is not installed"]), \
            mock.patch.object(pylibs, "externally_managed", lambda py, run=None: False), \
            mock.patch.object(pylibs, "python_version", lambda py, run=None: (3, 14)):
        rc = envsetup.setup(ns(tmp_path, "libs", wheels=str(pack)), quiet())
    out = capsys.readouterr().out
    assert rc == 1 and "[FAIL] libraries" in out and "this Python is 3.14" in out


def test_a_settings_file_named_by_settings_gets_drishti_python_and_no_dev_ini_is_made(tmp_path, libs, capsys):
    """Production (/etc/drishti/prod.ini): setup writes drishti.python into THAT file, never moves it, makes no ~/drishti-envs/dev.ini
    and no IDE JVM options."""
    import javacmd
    libs["bad"] = []
    prod = tmp_path / "etc" / "prod.ini"
    prod.parent.mkdir()
    prod.write_text(f"drishti.home={TOOLS.parent.as_posix()}\ndrishti.data.dir={(tmp_path / 'var').as_posix()}\n", encoding="utf-8")
    javacmd.set_settings(str(prod), {})
    try:
        rc = envsetup.setup(ns(tmp_path, None, system=True, data_dir=None), quiet())
    finally:
        javacmd.set_settings(None)
    out = capsys.readouterr().out
    assert f"drishti.python={Path(sys.executable).as_posix()}" in prod.read_text() and "named by --settings" in out
    assert not (tmp_path / "envs").exists(), "no developer settings folder, dev.ini or JVM options"
    assert prod.read_text().startswith(f"drishti.home={TOOLS.parent.as_posix()}") and rc in (0, 1)



