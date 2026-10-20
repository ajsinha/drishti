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
"""tools/build_demo_lakes.py in --dry-run: the whole lakes, one dataset only, and one system's files only."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "build_demo_lakes.py"


def run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args, "--dry-run"], capture_output=True, text=True)


def files(tmp_path):
    d = tmp_path / "src"
    d.mkdir()
    for day in ("2026-10-15", "2026-10-16"):
        (d / f"{day}.jsonl").write_text("{}\n")
    (d / "notes.txt").write_text("not a dated file")
    return d


def test_everything_ends_on_the_newest_file_and_loads_each_file(tmp_path):
    out = run("--endur-dir", str(files(tmp_path)), "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo --as-of 2026-10-16" in out
    assert out.count("lake build --from") == 2 and "--connector trade-endur" in out
    assert "notes.txt" not in out


def test_files_only_keeps_the_lakes_and_loads_one_system(tmp_path):
    out = run("--source-dir", str(files(tmp_path)), "--system", "calypso", "--files-only", "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo" not in out and "kept as they are" in out
    assert "--connector trade-calypso" in out and "trade_calypso" in out and out.count("lake build --from") == 2


def test_only_rebuilds_the_named_datasets(tmp_path):
    out = run("--only", "trades/endur,trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake")).stdout
    assert "lake demo --as-of 2026-10-16" in out and "--only trades/endur,trades/murex" in out
    assert "lake build" not in out


def test_wrong_combinations_are_refused(tmp_path):
    assert "--files-only needs" in run("--files-only").stderr
    d = files(tmp_path)
    assert "not both" in run("--endur-dir", str(d), "--source-dir", str(d)).stderr
    assert "leave out --only" in run("--endur-dir", str(d), "--files-only", "--only", "trades/endur").stderr
