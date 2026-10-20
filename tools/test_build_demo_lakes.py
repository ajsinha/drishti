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


def test_no_server_is_told_by_default_and_the_closing_lines_say_what_to_do(tmp_path):
    out = run("--only", "trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake")).stdout
    assert "--no-signal" in out and "--server" not in out.split("$ ", 1)[1].split("\n")[0]
    assert "picks the new tables up by itself at its next refresh" in out and "restart it to be sure" in out


def test_server_tells_once_per_table_at_the_end(tmp_path):
    out = run("--only", "trades/murex", "--as-of", "2026-10-16", "--lake", str(tmp_path / "lake"), "--server", "http://10.0.0.5:18480").stdout
    cmd = [line for line in out.splitlines() if "lake demo" in line][0]
    assert "--server http://10.0.0.5:18480 --signal-once" in cmd and "--no-signal" not in cmd
    assert "was told once per table" in out


def test_files_only_for_calypso_names_a_calypso_trade_and_needs_no_restart(tmp_path):
    d = tmp_path / "src"
    d.mkdir()
    for day, tid in (("2026-10-15", "CLY-3000157"), ("2026-10-16", "CLY-3000301")):
        (d / f"{day}.jsonl").write_text('{"SOURCE_SYSTEM": "CALYPSO", "TRADE_ID": "%s"}\n' % tid)
    out = run("--source-dir", str(d), "--system", "calypso", "--files-only", "--lake", str(tmp_path / "lake")).stdout
    closing = out.split("3/3 done", 1)[1]
    assert "TRD CLY-3000301 60" in closing and "1000279" not in closing          # an id from the newest file, not an ENDUR one
    assert "no restart is needed" in closing and "restart it" not in closing
    assert "EQ EQ-CSCA" not in closing                                              # the rest of the lakes was not built


def test_files_only_with_a_server_tells_it_once(tmp_path):
    out = run("--source-dir", str(files(tmp_path)), "--system", "murex", "--files-only", "--lake", str(tmp_path / "lake"),
              "--server", "http://10.0.0.5:18480").stdout
    assert out.count("tell http://10.0.0.5:18480/api/v1/packs/trading/loads: trade 2026-10-16") == 1
    assert "TRD MX-20000001 60" in out                                              # the files hold no TRADE_ID: the synthetic one


def test_token_file_needs_a_server(tmp_path):
    assert "--token-file goes with --server" in run("--token-file", str(tmp_path / "t.txt")).stderr


def test_the_heartbeat_says_waiting_when_nothing_is_written(tmp_path):
    import os
    import sys as _sys
    _sys.path.insert(0, str(ROOT / "tools"))
    import build_demo_lakes as b
    f = tmp_path / "trading" / "trade_murex" / "business_date=2026-10-16" / "part.parquet"
    f.parent.mkdir(parents=True)
    f.write_bytes(b"x" * 1000)
    assert "writing trading/trade_murex business_date=2026-10-16" in b.lake_progress(tmp_path)
    old = f.stat().st_mtime - 600
    os.utime(f, (old, old))
    line = b.lake_progress(tmp_path)
    assert "writing" not in line and "nothing written for 600 s (the last: trading/trade_murex" in line
