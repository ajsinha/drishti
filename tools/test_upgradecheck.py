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

"""``upgrade check``: the report of what an operator must do before switching releases. Read-only, installs nothing (no pip is ever run)."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent / "launch"))
import upgradecheck as U  # noqa: E402


def release(root: Path, name: str, requirements="fastapi>=0.141\nmarkdown>=3.6\n", lock="pyyaml==6.0.2\n", lake="0.9.1", modules=("banking",), version="2.9.11") -> Path:
    h = root / name
    (h / "drishti-console").mkdir(parents=True)
    (h / "drishti-console" / "requirements.txt").write_text(requirements, encoding="utf-8")
    (h / "config" / "compute" / "locks").mkdir(parents=True)
    (h / "config" / "compute" / "locks" / "python.lock").write_text(lock, encoding="utf-8")
    (h / "lib").mkdir()
    (h / "lib" / "lake-wheel.pin").write_text(f"version={lake}\n", encoding="utf-8")
    (h / "tools").mkdir()
    (h / "tools" / "drishti.py").write_text("", encoding="utf-8")
    (h / "modules").mkdir()
    for m in modules:
        (h / "modules" / f"{m}-1.0.0.dmod").write_text("", encoding="utf-8")
    (h / ".mvn").mkdir()
    (h / ".mvn" / "maven.config").write_text(f"-Drevision={version}\n", encoding="utf-8")
    (h / "docs" / "admin").mkdir(parents=True)
    (h / "docs" / "admin" / "CONFIGURATION.md").write_text("`drishti.data.dir` `server.port`\n", encoding="utf-8")
    return h


def fake_run(have: dict[str, str]):
    def run(cmd, **kw):
        names = cmd[3:]
        return subprocess.CompletedProcess(cmd, 0, json.dumps({n: have.get(n) for n in names}), "")
    return run


def py(root: Path, name: str) -> str:
    b = root / name / "bin"
    b.mkdir(parents=True)
    f = b / "python"
    f.write_text("", encoding="utf-8")
    return str(f)


HAVE = {"fastapi": "0.150", "markdown": "3.7.1", "pyyaml": "6.0.2", "drishti-lake": "0.9.1"}
levels = lambda rep: [lvl for lvl, _ in rep]            # noqa: E731
text = lambda rep: "\n".join(t for _, t in rep)         # noqa: E731


def test_unchanged_pins_let_one_python_serve_both(tmp_path):
    old, new, p = release(tmp_path, "a"), release(tmp_path, "b", version="2.9.12"), py(tmp_path, "py1")
    rep = U.check(str(new), str(old), p, keys={"drishti.python": p}, run=fake_run(HAVE))
    assert U.FAIL not in levels(rep) and "unchanged" in text(rep)


def test_changed_pins_in_the_running_python_block_the_switch(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b", requirements="fastapi>=0.141\nmarkdown>=3.7\n", version="2.9.12")
    p = py(tmp_path, "py1")
    rep = U.check(str(new), str(old), p, keys={"drishti.python": p}, run=fake_run(HAVE))
    assert U.FAIL in levels(rep)
    assert "markdown>=3.6  ->  markdown>=3.7" in text(rep) and "would change under the running" in text(rep)


def test_a_separate_python_with_the_new_pins_passes(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b", requirements="fastapi>=0.141\nmarkdown>=3.7\n")
    rep = U.check(str(new), str(old), py(tmp_path, "py2"), keys={"drishti.python": py(tmp_path, "py1")}, run=fake_run(HAVE))
    assert U.FAIL not in levels(rep) and "has every library" in text(rep)


def test_a_missing_library_is_a_fail_with_the_pip_command_for_the_operator_and_nothing_is_run(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b", requirements="fastapi>=0.141\nmarkdown>=3.9\n")
    calls = []
    inner = fake_run(HAVE)
    rep = U.check(str(new), str(old), py(tmp_path, "py2"), keys={"drishti.python": py(tmp_path, "py1")}, run=lambda c, **k: (calls.append(c), inner(c, **k))[1])
    t = text(rep)
    assert U.FAIL in levels(rep) and "markdown 3.7.1 is installed, the requirement is markdown>=3.9" in t
    assert "run yourself" in t and "-m pip install" in t
    assert all("pip" not in c[1:3] and "install" not in c for c in calls), "the check only asks what is installed"


def test_a_python_that_is_not_a_file_fails_in_words(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b")
    rep = U.check(str(new), str(old), str(tmp_path / "nope" / "bin" / "python"), run=fake_run(HAVE))
    assert U.FAIL in levels(rep) and "not a file" in text(rep)


def test_the_new_release_over_the_running_one_and_an_incomplete_folder_fail(tmp_path):
    old = release(tmp_path, "a")
    assert U.FAIL in levels(U.check(str(old), str(old), py(tmp_path, "p"), run=fake_run(HAVE)))
    empty = tmp_path / "empty"
    (empty / "drishti-console").mkdir(parents=True)
    (empty / "drishti-console" / "requirements.txt").write_text("a\n", encoding="utf-8")
    rep = U.check(str(empty), None, None, run=fake_run(HAVE))
    assert levels(rep) == [U.FAIL] and "not a complete release" in text(rep)


def test_a_release_unpacked_whole_is_found_under_its_drishti_folder(tmp_path):
    inner = release(tmp_path / "whole", "drishti")
    assert U.release_home(tmp_path / "whole") == inner.resolve()


def test_site_keys_the_new_catalogue_does_not_know_are_listed_never_changed(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b")
    ini = tmp_path / "prod.ini"
    ini.write_text("[roots]\n# comment\ndrishti.data.dir=/d\nold.switch=on\nserver.port=1\n", encoding="utf-8")
    rep = U.check(str(new), str(old), py(tmp_path, "p"), settings=[str(ini)], run=fake_run(HAVE))
    t = text(rep)
    assert "old.switch (in prod.ini) is not in the new release's CONFIGURATION.md" in t and "REFUSES to start" in t
    assert "drishti.data.dir (in" not in t
    assert ini.read_text(encoding="utf-8").count("old.switch") == 1


def test_modules_dropped_by_the_new_release_are_said(tmp_path):
    old, new = release(tmp_path, "a", modules=("banking", "genomics")), release(tmp_path, "b", modules=("banking", "economy"))
    t = text(U.check(str(new), str(old), py(tmp_path, "p"), run=fake_run(HAVE)))
    assert "lacks: genomics" in t and "new in the release: economy" in t


def test_the_prefix_not_the_interpreter_file_decides_whether_two_pythons_are_one(tmp_path):
    base = tmp_path / "base" / "bin"
    base.mkdir(parents=True)
    (base / "python").write_text("", encoding="utf-8")
    for n in ("v1", "v2"):
        (tmp_path / n / "bin").mkdir(parents=True)
        (tmp_path / n / "bin" / "python").symlink_to(base / "python")      # a virtual environment's python links to its base
    (tmp_path / "link").symlink_to(tmp_path / "v1")
    assert not U.same_prefix(str(tmp_path / "v1/bin/python"), str(tmp_path / "v2/bin/python"))
    assert U.same_prefix(str(tmp_path / "link/bin/python"), str(tmp_path / "v1/bin/python"))


def test_the_command_runs_from_the_command_line_changes_nothing_and_says_json(tmp_path):
    old, new = release(tmp_path, "a"), release(tmp_path, "b")
    before = sorted(x.relative_to(tmp_path).as_posix() for x in tmp_path.rglob("*"))
    r = subprocess.run([sys.executable, str(Path(__file__).resolve().parent / "drishti.py"), "upgrade", "check", str(new), "--current", str(old),
                        "--python", sys.executable, "--json"], capture_output=True, text=True, timeout=120)
    assert sorted(x.relative_to(tmp_path).as_posix() for x in tmp_path.rglob("*")) == before
    doc = json.loads(r.stdout[r.stdout.index("{"):])
    assert r.returncode == (0 if doc["ok"] else 1) and any("new release is" in x["text"] for x in doc["lines"])
