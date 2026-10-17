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
"""The console's one temp folder: made per process under the configured base, left-over ones of dead processes kept (listed, never deleted), ``tempfile.tempdir`` pointed
at it, removed on exit; and no console code makes a temporary file by any other route."""
import os
import re
import tempfile
from pathlib import Path

import pytest

from core import tmpdir

CONSOLE = Path(__file__).resolve().parent.parent


@pytest.fixture()
def fresh(monkeypatch):
    monkeypatch.setattr(tmpdir, "_root", None)
    monkeypatch.setattr(tmpdir, "_base", None)
    monkeypatch.setattr(tempfile, "tempdir", tempfile.tempdir)
    monkeypatch.delenv("DRISHTI_CONSOLE_TMP_DIR", raising=False)
    monkeypatch.delenv("DRISHTI_TMP_DIR", raising=False)
    yield
    tmpdir.clean_up()


def test_start_makes_the_process_folder_keeps_left_over_ones_and_cleans_up(tmp_path, fresh):
    dead = tmp_path / "drishti-console-2147483646"
    dead.mkdir()
    (dead / ".drishti-console-tmp").write_text("x")
    (dead / "spill.bin").write_text("data")
    notours = tmp_path / "drishti-console-2147483645"            # no marker: never removed
    notours.mkdir()
    root = tmpdir.start(str(tmp_path))
    assert root == tmp_path.resolve() / f"drishti-console-{os.getpid()}"
    assert tempfile.tempdir == str(root)
    assert dead.exists() and (dead / "spill.bin").exists() and notours.exists()   # left-over folders are never deleted at start
    folder = tmpdir.mkdtemp("x-")
    assert Path(folder).parent == root
    (Path(folder) / "f.bin").write_bytes(b"12345")
    info = tmpdir.info()
    assert info["path"] == str(root) and info["bytes"] >= 5 and info["files"] >= 2
    assert tmpdir.start(str(tmp_path)) == root                    # a second start is the same folder
    with tmpdir.temp_dir("t-") as t:
        assert Path(t).is_dir()
    assert not Path(t).exists()
    tmpdir.clean_up()
    assert not root.exists()


def test_the_base_falls_back_from_the_setting_to_the_console_variable_to_the_shared_one(tmp_path, fresh, monkeypatch):
    monkeypatch.setenv("DRISHTI_TMP_DIR", str(tmp_path / "shared"))
    assert tmpdir.configured_base("") == str(tmp_path / "shared")
    monkeypatch.setenv("DRISHTI_CONSOLE_TMP_DIR", str(tmp_path / "console"))
    assert tmpdir.configured_base("") == str(tmp_path / "console")
    assert tmpdir.configured_base(str(tmp_path / "setting")) == str(tmp_path / "setting")


def test_no_console_code_makes_a_temporary_file_except_through_the_helper():
    offenders = []
    for folder in ("core", "routes"):
        for py in (CONSOLE / folder).rglob("*.py"):
            if py.name == "tmpdir.py":
                continue
            for n, line in enumerate(py.read_text(encoding="utf-8").splitlines(), 1):
                if re.search(r"\btempfile\b", line) and not line.lstrip().startswith("#"):
                    offenders.append(f"{py.relative_to(CONSOLE)}:{n}: {line.strip()}")
    assert not offenders, "use core.tmpdir (mkdtemp, temp_dir, sub_dir): " + "; ".join(offenders)
