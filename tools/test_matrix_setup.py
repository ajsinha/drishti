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

"""The first-run matrix, setup's rows (docs/plans/FIRST_RUN_ROBUSTNESS.md section B2: 02, 03, 09, 10, 12, 13, 14, 15, 16, 17, 18, 20, 35,
37, 40, 41, 44, 48, 69, 70). Each row runs ``tools/drishti.py setup`` as a person does, in a scratch home whose name holds a space, under
the three oracles of tools/matrix_oracles.py (write set, output, network with a poisoned PATH). The data folder is always a scratch one
(--data-dir), so this checkout's data/ is never touched. Rows that fail because of the launch tools (lane A) are xfail with the row."""
from __future__ import annotations

import ast
import os
import shlex
import subprocess
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
for p in (str(TOOLS), str(TOOLS / "launch")):
    if p not in sys.path:
        sys.path.insert(0, p)
import matrix_oracles as mo  # noqa: E402
import pylibs  # noqa: E402

ROOT = mo.ROOT


class Run:
    """A scratch person: ``home`` (with a space), ``data`` (their data folder), ``env`` under the oracles; ``setup(...)`` runs it."""

    def __init__(self, tmp: Path, **extra):
        self.tmp, self.home, self.data = tmp, tmp / "John Smith", tmp / "John Smith" / "drishti data"
        self.env = mo.oracle_env(tmp, home=self.home, **extra)
        self.ini = self.home / "drishti-envs" / "dev.ini"

    def __call__(self, *argv, data: bool = True):
        a = list(argv)
        if data and a and a[0] == "setup" and "--data-dir" not in a:
            a += ["--data-dir", str(self.data)]
        r = mo.drishti(*a, env=self.env, timeout=600)
        r.text = r.stdout + r.stderr
        return r

    def snap(self) -> dict:
        return mo.snapshot(self.tmp, skip=("/oracle/",))

    def clean(self) -> list[str]:
        """The network and PATH oracles: nothing caught."""
        return mo.net(self.tmp) + mo.calls(self.tmp)


@pytest.fixture()
def person(tmp_path):
    return Run(tmp_path)


def verdict(text: str) -> str:
    return text.rstrip().splitlines()[-1]


def problems(r) -> list[str]:
    """The output oracle for setup: its verdict is the last line of stdout; warnings may come on stderr."""
    return mo.output_problems(r.stdout, setup=True) + mo.output_problems(r.stderr)


# ---- row 02: setup a second time --------------------------------------------------------------------------------------------------

def test_row02_setup_twice_changes_no_byte_and_runs_no_pip(person):
    first = person("setup")
    assert "Traceback" not in first.text and first.returncode in (0, 1)
    before = person.snap()
    second = person("setup")
    assert second.returncode == first.returncode and person.snap() == before, mo.changed(before, person.snap())
    assert "[made]" not in second.stdout and "[move]" not in second.stdout and not list(person.home.glob("drishti-envs/replaced-*"))
    assert not mo.output_problems(second.text, setup=False) and verdict(second.stdout) in (verdict(first.stdout),)
    assert not person.clean(), person.clean()


# ---- row 03: --check before anything ----------------------------------------------------------------------------------------------

def test_row03_check_before_anything_lists_todo_exits_1_and_changes_no_byte(person):
    before = person.snap()
    r = person("setup", "--check")
    assert r.returncode == 1 and "[todo]" in r.stdout and verdict(r.stdout).startswith("Drishti is NOT ready:")
    assert person.snap() == before and not person.ini.exists() and not person.data.exists()
    assert not problems(r) and not person.clean()


# ---- row 09: interrupted ----------------------------------------------------------------------------------------------------------

def test_row09_an_interrupt_is_one_line_and_exit_130(monkeypatch, capsys):
    import drishti
    import envsetup
    monkeypatch.setattr(envsetup, "setup", lambda *a, **k: (_ for _ in ()).throw(KeyboardInterrupt()))
    assert drishti.main(["setup"]) == 130
    err = capsys.readouterr().err.strip().splitlines()
    assert len(err) >= 1 and err[-1].startswith("[FAIL] interrupted") and "Traceback" not in "\n".join(err)


# ---- rows 10, 20: a dev.ini of another clone, or of a folder renamed since ---------------------------------------------------------

@pytest.mark.parametrize("row", ["10-another-clone", "20-renamed"])
def test_rows10_20_an_old_dev_ini_is_moved_aside_and_nothing_is_made_in_the_old_folder(person, row):
    old = person.tmp / ("old clone" if row.startswith("10") else "renamed away")
    if row.startswith("10"):
        old.mkdir()
    person.ini.parent.mkdir(parents=True)
    person.ini.write_text(f"[drishti]\ndrishti.home={old}\ndrishti.data.dir={old}/data\n", encoding="utf-8")
    r = person("setup")
    assert "[move]" in r.stdout and "replaced-" in r.stdout and "[made] settings file" in r.stdout
    moved = list(person.home.glob("drishti-envs/replaced-*/dev.ini"))
    assert moved and str(old) in moved[0].read_text(encoding="utf-8")
    assert f"drishti.home={ROOT.as_posix()}" in person.ini.read_text(encoding="utf-8") or str(ROOT) in person.ini.read_text(encoding="utf-8")
    assert (not old.exists() or not any(old.iterdir())), "nothing made in the old folder"
    assert not problems(r) and not person.clean()


# ---- row 12: stale setx variables naming another folder -----------------------------------------------------------------------------

def test_row12_stale_folder_variables_are_warned_one_by_one_ignored_and_nothing_is_made_there(tmp_path):
    old = tmp_path / "old clone"
    person = Run(tmp_path, DRISHTI_HOME=old, DRISHTI_DATA_DIR=old / "data", DRISHTI_MODULES_HOME=old / "modules",
                 DRISHTI_LIB_PATH=old / "lib", DRISHTI_TMP_DIR=old / "tmp")
    r = person("setup")
    for name in ("DRISHTI_HOME", "DRISHTI_DATA_DIR", "DRISHTI_MODULES_HOME", "DRISHTI_TMP_DIR"):
        assert f"[WARN] {name}" in r.text, name
    assert not old.exists() and not problems(r) and not person.clean()


# ---- row 13: DRISHTI_PYTHON names a Python that is gone -----------------------------------------------------------------------------

@pytest.mark.xfail(reason="lane A: row 13 (setup says 'Drishti is ready.' while DRISHTI_PYTHON names a Python that is gone; P4 wants one [FAIL])", strict=False)
def test_row13_setup_with_a_gone_drishti_python_is_a_fail(tmp_path):
    person = Run(tmp_path, DRISHTI_PYTHON=tmp_path / "gone" / "python")
    r = person("setup")
    assert r.returncode == 1 and "DRISHTI_PYTHON" in r.text and "not there" in r.text


# ---- row 14: drishti.python in this checkout's dev.ini is stale -------------------------------------------------------------------

def test_row14_a_stale_drishti_python_is_replaced_and_said(person):
    person("setup")
    text = person.ini.read_text(encoding="utf-8")
    gone = (person.tmp / "old venv" / "bin" / "python").as_posix()
    person.ini.write_text("\n".join(f"drishti.python={gone}" if l.startswith("drishti.python=") else l for l in text.splitlines()) + "\n", encoding="utf-8")
    r = person("setup")
    assert f"[made] drishti.python" in r.stdout and f"(was {gone}: no such Python)" in r.stdout
    assert f"drishti.python={Path(sys.executable).as_posix()}" in person.ini.read_text(encoding="utf-8")


# ---- row 15: other left-over variables are listed, a secret never printed -----------------------------------------------------------

def test_row15_left_over_variables_are_listed_and_a_secret_is_named_never_shown(tmp_path):
    person = Run(tmp_path, DRISHTI_PORT="18994", DRISHTI_CONSOLE_PORT="17994", DRISHTI_PACKS="x", DRISHTI_TOKEN_SECRET="s3cr3t-value",
                 DRISHTI_SESSION_SECRET="an0ther-value")
    r = person("setup", "--check")
    for name in ("DRISHTI_PORT", "DRISHTI_CONSOLE_PORT", "DRISHTI_PACKS", "DRISHTI_TOKEN_SECRET", "DRISHTI_SESSION_SECRET"):
        assert any(l.strip().startswith(name) for l in r.stdout.splitlines()), name
    assert "s3cr3t-value" not in r.text and "an0ther-value" not in r.text and "set (not shown)" in r.stdout


# ---- row 16: the data folder elsewhere on purpose ---------------------------------------------------------------------------------

def test_row16_a_data_folder_elsewhere_is_kept_and_todo_until_it_exists(person):
    person("setup")
    import shutil
    shutil.rmtree(person.data)
    before = person.snap()
    r = person("setup", data=False)
    assert "[todo] data" in r.stdout and "--data-dir" in r.text and not person.data.exists()
    assert person.snap() == before and verdict(r.stdout).startswith("Drishti is NOT ready:")


# ---- row 17: a dev.ini saved by Notepad, or unreadable ------------------------------------------------------------------------------

@pytest.mark.xfail(reason="lane A: row 17 (a dev.ini with a byte-order mark is 'cannot be read' and moved aside; F6 wants it read)", strict=False)
def test_row17_a_dev_ini_saved_by_notepad_bom_and_crlf_is_read_normally(person):
    person("setup")
    raw = person.ini.read_text(encoding="utf-8")
    person.ini.write_bytes(b"\xef\xbb\xbf" + raw.replace("\n", "\r\n").encode("utf-8"))
    r = person("setup")
    assert "[move]" not in r.stdout and not list(person.home.glob("drishti-envs/replaced-*"))


def test_row17_crlf_alone_is_read_normally(person):
    person("setup")
    person.ini.write_bytes(person.ini.read_text(encoding="utf-8").replace("\n", "\r\n").encode("utf-8"))
    r = person("setup")
    assert "[move] old settings           dev.ini" not in r.stdout and "Traceback" not in r.text


def test_row17_an_unreadable_dev_ini_is_moved_aside_never_a_traceback(person):
    person.ini.parent.mkdir(parents=True)
    person.ini.write_text("garbage [[[\n=\n", encoding="utf-8")
    r = person("setup")
    assert "[move]" in r.stdout and "cannot be read" in r.stdout and "Traceback" not in r.text
    assert list(person.home.glob("drishti-envs/replaced-*/dev.ini"))


# ---- row 18: a file that cannot be moved ----------------------------------------------------------------------------------------

@pytest.mark.xfail(reason="lane A: row 18 (a dev.ini that cannot be moved gives the catch-all '[FAIL] PermissionError ... details in a log', no 'could not be moved' and no Fix)", strict=False)
def test_row18_a_dev_ini_that_cannot_be_moved_is_one_fail_and_is_not_written_over(person):
    person.ini.parent.mkdir(parents=True)
    person.ini.write_text("[drishti]\ndrishti.home=/somewhere/else\n", encoding="utf-8")
    person.ini.parent.chmod(0o555)
    try:
        r = person("setup")
    finally:
        person.ini.parent.chmod(0o755)
    assert person.ini.read_text(encoding="utf-8") == "[drishti]\ndrishti.home=/somewhere/else\n"
    assert "could not be moved" in r.text and not mo.output_problems(r.text)


# ---- row 35: the operating system's Python (PEP 668) ---------------------------------------------------------------------------------

def test_row35_an_os_python_is_one_sentence_and_never_break_system_packages():
    seen = []

    def run(cmd, **kw):
        seen.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, "MANAGED 1\n", "")
    assert pylibs.externally_managed("/usr/bin/python3", run=run)
    assert "managed by the operating system" in pylibs.MANAGED and "virtual environment is fine" in pylibs.MANAGED
    assert "--break-system-packages" not in pylibs.MANAGED


# ---- row 37: an old Python gets one line, never a SyntaxError -------------------------------------------------------------------------

ENTRY = ("tools/drishti.py", "drishti-console/run_drishti_web.py")


@pytest.mark.parametrize("rel", ENTRY)
def test_row37_entry_scripts_parse_on_old_pythons(rel):
    ast.parse((ROOT / rel).read_text(encoding="utf-8"), feature_version=(3, 6))


@pytest.mark.parametrize("rel", ENTRY)
@pytest.mark.xfail(reason="lane A: row 37 / P6 (no version check before the first import; 'from __future__ import annotations' is a SyntaxError on 3.6)", strict=False)
def test_row37_entry_scripts_check_the_python_version_first(rel):
    tree = ast.parse((ROOT / rel).read_text(encoding="utf-8"))
    body = [n for n in tree.body if not (isinstance(n, ast.Expr) and isinstance(getattr(n, "value", None), ast.Constant))]
    assert not any(isinstance(n, ast.ImportFrom) and n.module == "__future__" for n in body), "a __future__ import fails on Python 3.6"
    first = next(i for i, n in enumerate(body) if "version_info" in ast.unparse(n) and isinstance(n, ast.If))
    imports = [i for i, n in enumerate(body) if isinstance(n, (ast.Import, ast.ImportFrom)) and not ast.unparse(n) in ("import sys",)]
    assert first < min(imports)


# ---- rows 40, 41: no Java, an old Java -------------------------------------------------------------------------------------------------

def fake_java(folder: Path, version: int) -> Path:
    (folder / "bin").mkdir(parents=True)
    java = folder / "bin" / "java"
    java.write_text(f'#!/bin/sh\necho "    java.specification.version = {version}" >&2\necho "openjdk version \\"{version}.0.2\\"" >&2\n', encoding="utf-8")
    java.chmod(0o755)
    return folder


def test_row41_an_old_java_is_a_fail_naming_21_and_where_it_came_from(tmp_path):
    person = Run(tmp_path, JAVA_HOME=fake_java(tmp_path / "jdk 17", 17))
    r = person("setup", "--check")
    assert "[FAIL] java" in r.stdout and "Java 17 is older than 21" in r.stdout and "JAVA_HOME" in r.stdout
    assert r.returncode == 1 and "Traceback" not in r.text


@pytest.mark.xfail(reason="lane A: row 41 / E1 (setup's closing '[FAIL] Drishti runs on Java 21 or newer ...' line carries no 'Fix:')", strict=False)
def test_row41_the_old_java_line_carries_a_fix(tmp_path):
    person = Run(tmp_path, JAVA_HOME=fake_java(tmp_path / "jdk 17", 17))
    assert not problems(person("setup", "--check"))


def test_row40_no_java_anywhere_is_a_fail_with_the_fix(tmp_path):
    person = Run(tmp_path)
    person.env.pop("JAVA_HOME", None)
    person.env.pop("DRISHTI_JAVA_HOME", None)
    person.env["PATH"] = str(tmp_path / "oracle" / "shims")             # no java on the PATH (and no python, pip, git by name)
    r = person("setup", "--check")
    assert r.returncode == 1 and "[FAIL] java" in r.stdout and "Traceback" not in r.text, r.text[-2000:]


# ---- row 44: a space in the user name and '(1)' in the folder: every printed command pastes whole ------------------------------------

def test_row44_printed_commands_paste_whole_with_a_space_in_the_path(tmp_path):
    person = Run(tmp_path)
    person.data = tmp_path / "John Smith" / "drishti-platform (1)" / "data"
    r = person("setup", "--check")
    for line in r.stdout.splitlines():
        if "tools/drishti.py" in line and ("--data-dir" in line or "--settings" in line):
            cmd = line.split(":", 1)[-1].strip() if ": " in line else line.strip()
            cmd = cmd.split("   (")[0]
            words = shlex.split(cmd[cmd.index(sys.executable):] if sys.executable in cmd else cmd)
            assert any(w.endswith("drishti.py") for w in words), line


# ---- row 48: a data folder that cannot be written --------------------------------------------------------------------------------------

@pytest.mark.xfail(reason="lane A: row 48 (a folder that cannot be written gives the catch-all '[FAIL] PermissionError ... details in a log'; R/E1 want '<folder> is not writable' with a Fix)", strict=False)
def test_row48_a_folder_that_cannot_be_written_is_one_fail_with_a_fix(tmp_path):
    ro = tmp_path / "read only"
    ro.mkdir()
    ro.chmod(0o555)
    person = Run(tmp_path)
    try:
        r = person("setup", "--data-dir", str(ro / "data"))
    finally:
        ro.chmod(0o755)
    assert "not writable" in r.text and not mo.output_problems(r.text)


# ---- rows 69, 70: the company's pip configuration and index ------------------------------------------------------------------------------

def test_row69_pip_ini_and_pip_variables_play_no_part_and_no_index_is_contacted(tmp_path):
    cfg = tmp_path / "John Smith" / ".config" / "pip"
    cfg.mkdir(parents=True)
    (cfg / "pip.conf").write_text("[global]\nindex-url = http://127.0.0.1:9/simple\nrequire-virtualenv = true\nuser = true\n", encoding="utf-8")
    person = Run(tmp_path, PIP_USER="1", PIP_TARGET=str(tmp_path / "target"), PIP_FIND_LINKS=str(tmp_path / "links"))
    person("setup")
    assert not person.clean() and not (tmp_path / "target").exists()


