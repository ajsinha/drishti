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

"""``drishti.py setup`` never writes into another folder (2.9.11.i7f): a file it writes in the settings folder that is there and differs is
moved into a dated folder there and a fresh one written; a stale ``DRISHTI_*`` variable is reported and never used; the Python resolver has
the Java order (explicit > settings file > environment > this checkout); ``--check`` writes nothing; no pom reads a runtime root. Temp
folders only (Windows-shaped paths through ntpath); pip is never run."""
from __future__ import annotations

import argparse
import ntpath
import re
import subprocess
import sys
from pathlib import Path
from unittest import mock

import pytest

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
for p in (str(TOOLS), str(TOOLS / "launch"), str(TOOLS / "lake")):
    if p not in sys.path:
        sys.path.insert(0, p)
import envsetup  # noqa: E402
import javacmd  # noqa: E402
import launchers  # noqa: E402
import pylibs  # noqa: E402
import settingsfile as sf  # noqa: E402


def ns(tmp: Path, **kw) -> argparse.Namespace:
    d = dict(part="drishti", system=False, check=False, home=None, data_dir=str(tmp / "data"), envs_dir=str(tmp / "envs"), java_home=None,
             python=None, skip_venv=False, reinstall=False)
    d.update(kw)
    return argparse.Namespace(**d)


@pytest.fixture(autouse=True)
def no_pip():
    with mock.patch.object(pylibs, "verify", lambda *a, **k: []):        # the libraries: checked only (Drishti installs nothing)
        yield


def stale_ini(envs: Path, orig: Path) -> str:
    envs.mkdir(parents=True, exist_ok=True)
    text = (f"drishti.home={orig.as_posix()}\ndrishti.data.dir={(orig / 'data').as_posix()}\n"
            f"drishti.modules.home={(orig / 'modules').as_posix()}\n")
    (envs / "dev.ini").write_text(text, encoding="utf-8")
    return text


def test_a_stale_dev_ini_is_moved_to_a_dated_folder_and_nothing_is_made_in_the_old_folder(tmp_path, capsys):
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    old = stale_ini(envs, orig)
    rc = envsetup.setup(ns(tmp_path), {})
    out = capsys.readouterr().out
    assert rc in (0, 1)
    assert not orig.exists(), "setup made a folder the old dev.ini names"
    moved = list(envs.glob("replaced-*"))
    assert len(moved) == 1 and re.fullmatch(r"replaced-\d{4}-\d{2}-\d{2}-\d{4}", moved[0].name)
    assert (moved[0] / "dev.ini").read_text(encoding="utf-8") == old
    assert f"dev.ini moved to {moved[0]}" in out and f"drishti.home={orig.as_posix()}" in out
    fresh = (envs / "dev.ini").read_text(encoding="utf-8")
    assert f"drishti.home={ROOT.as_posix()}" in fresh and f"drishti.data.dir={(tmp_path / 'data').as_posix()}" in fresh
    assert f"data folder {(tmp_path / 'data').as_posix()}" in (envs / "jvm-demo.args").read_text(encoding="utf-8")


def test_the_jvm_arguments_files_never_make_the_folders_they_name(tmp_path):
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    stale_ini(envs, orig)
    written = launchers.machine(ROOT, envs, envs / "dev.ini")
    assert written and not orig.exists()


def test_a_jvm_arguments_file_that_differs_is_moved_and_identical_files_are_untouched(tmp_path, capsys):
    envsetup.setup(ns(tmp_path), {})
    envs = tmp_path / "envs"
    assert not list(envs.glob("replaced-*")), "a first setup has nothing to move"
    capsys.readouterr()
    before = {f.name: f.read_text(encoding="utf-8") for f in envs.glob("*") if f.is_file()}
    envsetup.setup(ns(tmp_path), {})
    assert "moved to" not in capsys.readouterr().out and not list(envs.glob("replaced-*")), "identical files: nothing moved"
    assert {f.name: f.read_text(encoding="utf-8") for f in envs.glob("*") if f.is_file()} == before
    (envs / "jvm-demo.args").write_text("# an old file\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path), {})
    out = capsys.readouterr().out
    moved = list(envs.glob("replaced-*"))
    assert len(moved) == 1 and (moved[0] / "jvm-demo.args").read_text(encoding="utf-8") == "# an old file\n"
    assert sorted(f.name for f in moved[0].iterdir()) == ["jvm-demo.args"] and "jvm-demo.args moved to" in out
    assert (envs / "jvm-demo.args").read_text(encoding="utf-8") == before["jvm-demo.args"]
    (envs / "jvm-demo.args").write_text("# again\n", encoding="utf-8")
    envsetup.setup(ns(tmp_path), {})
    assert len(list(envs.glob("replaced-*"))) == 2, "a second move the same minute gets its own folder"


def test_check_reports_what_would_be_moved_and_writes_nothing(tmp_path, capsys):
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    old = stale_ini(envs, orig)
    (envs / "jvm-demo.args").write_text("# old\n", encoding="utf-8")
    rc = envsetup.setup(ns(tmp_path, check=True), {})
    out = capsys.readouterr().out
    assert rc == 1 and "setup moves it to" in out and "jvm-demo.args differ: setup moves them to" in out
    assert sorted(f.name for f in envs.iterdir()) == ["dev.ini", "jvm-demo.args"]
    assert (envs / "dev.ini").read_text(encoding="utf-8") == old and not orig.exists() and not (tmp_path / "data").exists()


def test_stale_drishti_variables_are_reported_ignored_and_the_file_wins(tmp_path, capsys):
    orig = tmp_path / "drishti-orig"
    env = {"DRISHTI_HOME": str(orig), "DRISHTI_DATA_DIR": str(orig / "data"), "DRISHTI_MODULES_HOME": str(orig / "modules"),
           "DRISHTI_LIB_PATH": str(orig / "lib" / "ext"), "DRISHTI_TMP_DIR": str(orig / "tmp")}
    rc = envsetup.setup(ns(tmp_path, data_dir=None, part="libs"), env)          # the libs part: nothing of the data folder is made
    out = capsys.readouterr().out
    for n in env:
        assert re.search(rf"\[WARN\] {n}\s+{re.escape(env[n])}: names a folder outside this checkout; ignored: your settings file wins", out), n
    assert rc == 0 and not orig.exists()
    steps = out.split("Next steps")[1]
    assert "DRISHTI_" not in steps.replace("DRISHTI_HOME", ""), "a stale variable is a WARNING, never a step to do"
    envs = tmp_path / "envs"
    rc = envsetup.setup(ns(tmp_path), env)
    out = capsys.readouterr().out
    assert not orig.exists() and f"drishti.home={ROOT.as_posix()}" in (envs / "dev.ini").read_text(encoding="utf-8")
    assert "[FAIL]" not in out.replace("[FAIL] java", "")


def test_the_python_resolver_has_the_java_order(tmp_path):
    """explicit option > settings file > environment > this checkout, for every root (LaunchRoots)."""
    env = {"DRISHTI_HOME": str(tmp_path / "orig"), "DRISHTI_DATA_DIR": str(tmp_path / "env-data"),
           "DRISHTI_MODULES_HOME": str(tmp_path / "env-modules")}
    keys = {"drishti.home": str(ROOT), "drishti.data.dir": str(tmp_path / "file-data"), "drishti.modules.home": str(tmp_path / "file-modules")}
    r = javacmd.launch_roots(None, None, env, keys)
    assert r["home"] == (ROOT.resolve(), "drishti.home in the settings file")
    assert r["data"][0] == (tmp_path / "file-data").resolve() and r["modules"][0] == (tmp_path / "file-modules").resolve()
    r = javacmd.launch_roots(None, tmp_path / "opt", env, keys)
    assert r["data"] == ((tmp_path / "opt").resolve(), "--data-dir")
    r = javacmd.launch_roots(None, None, {k: v for k, v in env.items() if k != "DRISHTI_HOME"}, {})
    assert r["data"][1] == "DRISHTI_DATA_DIR" and r["modules"][1] == "DRISHTI_MODULES_HOME"
    assert javacmd.launch_roots(None, None, {}, {})["data"] == (ROOT.resolve() / "data", "<home>/data")
    environ = dict(env)
    sf.apply_roots(keys, environ)
    assert environ["DRISHTI_DATA_DIR"] == keys["drishti.data.dir"] and environ["DRISHTI_HOME"] == keys["drishti.home"]
    sf.REPLACED.clear()


def test_an_explicit_data_dir_wins_and_is_recorded(tmp_path, capsys):
    envs = tmp_path / "envs"
    envs.mkdir()
    mine = f"drishti.home={ROOT.as_posix()}\ndrishti.data.dir={(ROOT / 'data').as_posix()}\n# my own line\n"
    (envs / "dev.ini").write_text(mine, encoding="utf-8")
    target = tmp_path / "D" / "x"
    envsetup.setup(ns(tmp_path, data_dir=str(target)), {})
    capsys.readouterr()
    moved = list(envs.glob("replaced-*"))
    assert len(moved) == 1 and (moved[0] / "dev.ini").read_text(encoding="utf-8") == mine
    assert f"drishti.data.dir={target.as_posix()}" in (envs / "dev.ini").read_text(encoding="utf-8") and (target / "run").is_dir()
    kept = (envs / "dev.ini").read_text(encoding="utf-8") + "# mine too\n"
    (envs / "dev.ini").write_text(kept, encoding="utf-8")
    envsetup.setup(ns(tmp_path, data_dir=str(target)), {})
    assert (envs / "dev.ini").read_text(encoding="utf-8") == kept and len(list(envs.glob("replaced-*"))) == 1


def test_windows_shaped_paths(tmp_path):
    home = r"C:\Users\First Last\IdeaProjects\drishti-platform\drishti"
    assert sf.inside(r"c:/users/first last/IdeaProjects/drishti-platform/drishti/data", home, ntpath)
    assert not sf.inside(r"C:\Users\First Last\IdeaProjects\drishti-platform\drishti-orig\data", home, ntpath)
    assert not sf.inside(r"D:\x", home, ntpath) and sf.inside(home + "\\", home, ntpath)
    ini = tmp_path / "dev.ini"
    ini.write_text("drishti.home=C:/Users/First Last/IdeaProjects/drishti-orig\ndrishti.data.dir=C:/Users/First Last/IdeaProjects/drishti-orig/data\n",
                   encoding="utf-8")
    assert envsetup.ini_collision(ini, home, None, ntpath) == ["drishti.home=C:/Users/First Last/IdeaProjects/drishti-orig"]
    ini.write_text("drishti.home=c:/users/first last/ideaprojects/drishti-platform/drishti\ndrishti.data.dir=D:/x\n", encoding="utf-8")
    assert envsetup.ini_collision(ini, home, None, ntpath) == [], "this checkout's file with its data folder elsewhere is kept"
    assert envsetup.ini_outside(ini, home, ntpath) == ["drishti.data.dir=D:/x"]
    assert envsetup.ini_collision(ini, home, "D:\\X", ntpath) == []
    assert envsetup.ini_collision(ini, home, "E:\\y", ntpath) == ["drishti.data.dir=D:/x, not the --data-dir E:\\y"]
    ini.write_text("drishti.data.dir=D:/x\n", encoding="utf-8")
    assert envsetup.ini_collision(ini, home, None, ntpath) == ["drishti.data.dir=D:/x"], "no drishti.home and a root outside: moved"
    assert sf.foreign_vars({"DRISHTI_DATA_DIR": r"C:\Users\First Last\drishti-orig\data", "DRISHTI_HOME": home}, home, ntpath) == [
        ("DRISHTI_DATA_DIR", r"C:\Users\First Last\drishti-orig\data")]


def test_setup_with_a_foreign_tmp_dir_makes_nothing_there(tmp_path):
    """drishti.py itself takes DRISHTI_TMP_DIR before any command; for setup a folder outside this checkout is never made."""
    tmp = tmp_path / "drishti-orig" / "tmp"
    env = {**{k: v for k, v in __import__("os").environ.items() if not k.startswith("DRISHTI_")}, "DRISHTI_TMP_DIR": str(tmp)}
    r = subprocess.run([sys.executable, str(TOOLS / "drishti.py"), "setup", "libs", "--check"], capture_output=True, text=True, env=env,
                       timeout=120, cwd=str(ROOT))
    assert not tmp.exists(), r.stdout + r.stderr
    assert "DRISHTI_TMP_DIR" in r.stdout and "ignored: your settings file wins" in r.stdout


def test_no_pom_reads_a_runtime_root():
    """The build never reads a runtime root: no pom.xml names ${env.DRISHTI_*} or ${user.home} (a stale variable of an old setx, or the
    user's folder, would send a build to another folder)."""
    poms = subprocess.run(["git", "ls-files", "*pom.xml"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    assert poms
    bad = [f"{p}: {m}" for p in poms for m in re.findall(r"\$\{(?:env\.DRISHTI_\w*|user\.home)\}", (ROOT / p).read_text(encoding="utf-8"))]
    assert not bad, bad


def test_data_dir_then_plain_setup_keeps_the_file_and_makes_nothing_outside(tmp_path, capsys):
    """setup --data-dir X records X; a later plain setup (the IDE's Drishti: setup) keeps that dev.ini (its drishti.home is this checkout),
    says the data folder is outside, and makes nothing there: only a --data-dir in this run makes folders outside the checkout."""
    import shutil
    envs, target = tmp_path / "envs", tmp_path / "D" / "data"
    envsetup.setup(ns(tmp_path, data_dir=str(target)), {})
    capsys.readouterr()
    kept = (envs / "dev.ini").read_text(encoding="utf-8") + "# my own line\n"
    (envs / "dev.ini").write_text(kept, encoding="utf-8")
    shutil.rmtree(target)
    rc = envsetup.setup(ns(tmp_path, data_dir=None), {})
    out = capsys.readouterr().out
    assert (envs / "dev.ini").read_text(encoding="utf-8") == kept and not list(envs.glob("replaced-*"))
    assert f"drishti.data.dir={target.as_posix()}  (from your dev.ini: kept" in out and not target.exists()
    assert rc == 0 or f"setup --data-dir {target}" in out
    assert f"setup --data-dir {target}" in out


def test_launchers_machine_moves_a_differing_file_and_warns_of_an_old_dev_ini(tmp_path, capsys):
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    stale_ini(envs, orig)
    launchers.machine(ROOT, envs, envs / "dev.ini")
    assert "WARNING: your settings file names drishti.home=" in capsys.readouterr().err and not orig.exists()
    before = (envs / "jvm-qa.args").read_text(encoding="utf-8")
    assert launchers.machine(ROOT, envs, envs / "dev.ini") == [] and not list(envs.glob("replaced-*")), "identical: untouched"
    (envs / "jvm-qa.args").write_text("# old\n", encoding="utf-8")
    written = launchers.machine(ROOT, envs, envs / "dev.ini")
    moved = list(envs.glob("replaced-*"))
    assert written == [envs / "jvm-qa.args"] and len(moved) == 1 and (moved[0] / "jvm-qa.args").read_text(encoding="utf-8") == "# old\n"
    assert (envs / "jvm-qa.args").read_text(encoding="utf-8") == before


def test_a_file_that_cannot_be_moved_is_a_fail_line_and_is_never_written_over(tmp_path, capsys):
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    old = stale_ini(envs, orig)

    def locked(src, dst):
        raise PermissionError(13, "The process cannot access the file because it is being used by another process")
    with mock.patch("shutil.move", locked):
        rc = envsetup.setup(ns(tmp_path), {})
    out = capsys.readouterr().out
    assert rc == 1 and "[FAIL] old settings" in out and f"{envs / 'dev.ini'} could not be moved" in out and "close the program" in out
    assert (envs / "dev.ini").read_text(encoding="utf-8") == old and not orig.exists()


def test_settings_naming_an_old_dev_ini_does_not_stop_setup(tmp_path):
    """drishti.py --settings <old dev.ini> setup: setup goes ahead (and moves the file), never refused for the file's drishti.home."""
    envs, orig = tmp_path / "envs", tmp_path / "drishti-orig"
    stale_ini(envs, orig)
    env = {k: v for k, v in __import__("os").environ.items() if not k.startswith("DRISHTI_")}
    r = subprocess.run([sys.executable, str(TOOLS / "drishti.py"), "--settings", str(envs / "dev.ini"), "setup", "drishti", "--envs-dir",
                        str(envs), "--data-dir", str(tmp_path / "data")], capture_output=True, text=True, env=env, timeout=300, cwd=str(ROOT))
    assert r.returncode in (0, 1) and "these tools come from" not in r.stderr, r.stdout + r.stderr
    assert "dev.ini moved to" in r.stdout and "[WARN] DRISHTI_" not in r.stdout and not orig.exists()


def test_a_missing_tmp_dir_outside_the_checkout_is_never_made_by_any_command(tmp_path):
    tmp = tmp_path / "drishti-orig" / "tmp"
    env = {**{k: v for k, v in __import__("os").environ.items() if not k.startswith("DRISHTI_")}, "DRISHTI_TMP_DIR": str(tmp)}
    r = subprocess.run([sys.executable, str(TOOLS / "drishti.py"), "launchers", "--check"], capture_output=True, text=True, env=env,
                       timeout=120, cwd=str(ROOT))
    assert r.returncode == 0 and not tmp.exists() and "DRISHTI_TMP_DIR" in r.stderr and "ignored" in r.stderr, r.stdout + r.stderr
