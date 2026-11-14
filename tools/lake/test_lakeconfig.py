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

"""The Python side of the lake files (tools/lake/lakeconfig.py): the one Java reader's JSON for a table, the refusal of a kind the lake
file lacks, lake build refusing a --partition-by that contradicts the lake file, and the shipped demo lake read whole. Skipped where
no Java reader can be found (no exec jar, no compiled drishti-lakeio). Every file is written under a temporary folder."""
from __future__ import annotations

import argparse
import pathlib
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import lakeconfig  # noqa: E402


def _reader() -> bool:
    try:
        lakeconfig.command()
        return True
    except lakeconfig.LakeConfigError:
        return False


pytestmark = pytest.mark.skipif(not _reader(), reason="no Java lake reader (build drishti-lakeio or the server jar)")

LAKE = """lake: main
root: {root}
tables:
  trading.trade_endur:
    kind: trade
    partition-by: [PRODUCT_TYPE]
    promote: [TRADE_ID, PRODUCT_TYPE]
    id: TRADE_ID
"""


def _folder(tmp_path: pathlib.Path) -> pathlib.Path:
    d = tmp_path / "lakes"
    d.mkdir()
    (d / "main.yaml").write_text(LAKE.format(root=tmp_path / "lake"), encoding="utf-8")
    return d


def test_a_table_comes_from_the_one_reader(tmp_path):
    t = lakeconfig.table("main", "trade", folder=_folder(tmp_path))
    assert t["partition-columns"] == ["business_date", "PRODUCT_TYPE"]
    assert t["location"].endswith("/trading/trade_endur")
    assert t["id"] == "TRADE_ID"


def test_a_kind_the_lake_lacks_is_refused(tmp_path):
    with pytest.raises(lakeconfig.LakeConfigError, match="has no table"):
        lakeconfig.table("main", "pnl-vector", folder=_folder(tmp_path))


def test_lake_build_refuses_a_partition_the_lake_file_does_not_say(tmp_path, monkeypatch):
    monkeypatch.setenv("DRISHTI_LAKES_DIR", str(_folder(tmp_path)))
    import build
    a = build.add_arguments(argparse.ArgumentParser()).parse_args(
        ["--from", str(tmp_path), "--kind", "trade", "--table", "trade_endur", "--domain", "trading", "--as-of", "2026-10-28",
         "--lake", "main", "--partition-by", "DESK"])
    with pytest.raises(build.BuildError, match="PRODUCT_TYPE"):
        build.plan(a)
    ok = build.add_arguments(argparse.ArgumentParser()).parse_args(
        ["--from", str(tmp_path), "--kind", "trade", "--table", "trade_endur", "--domain", "trading", "--as-of", "2026-10-28",
         "--lake", "main", "--lake-root", str(tmp_path / "scratch")])
    p = build.plan(ok)
    assert p["partition_by"] == ["PRODUCT_TYPE"] and ok.lake == str(tmp_path / "scratch")


def test_the_shipped_demo_lake_reads_whole():
    out = lakeconfig.dump(lakeconfig.ROOT / "config" / "lakes")
    demo = next(x for x in out["lakes"] if x["lake"] == "demo")
    trades = [t for t in demo["tables"] if t["kind"] == "trade"]
    assert {t["path"] for t in trades} == {"trading/trade_endur", "trading/trade_calypso", "trading/trade_murex", "trading/trades_shared"}
    # Endur by PRODUCT_TYPE, Calypso and Murex by PRODUCT_GROUP (the owner's layout of the demo lake: DATA_LAKE_GUIDE.md)
    assert {t["path"]: t["partition-by"] for t in trades} == {"trading/trade_endur": ["PRODUCT_TYPE"],
                                                           "trading/trade_calypso": ["PRODUCT_GROUP"],
                                                           "trading/trade_murex": ["PRODUCT_GROUP"],
                                                           "trading/trades_shared": ["SOURCE_SYSTEM"]}      # WSS, SwapOne and ICI, one table


def _files(d: pathlib.Path) -> list[str]:
    return sorted(str(p.relative_to(d)) for p in d.rglob("*") if p.is_file()) if d.is_dir() else []


@pytest.mark.parametrize("kind, partition, home", [("trade", "DESK", "lake"), ("pnl-vector", None, "data/lakes/main")])
def test_a_refused_build_writes_nothing_outside_the_lake_or_the_data_folder(tmp_path, monkeypatch, kind, partition, home):
    """No root litter: ``--lake NAME`` is a lake's name, never a folder of the current directory. A run refused by the lake file (a
    --partition-by that contradicts it: the summary in that lake's folder, as a successful run's; a kind it has no entry for: under
    <data>/lakes/<name>) writes nothing where it was started."""
    monkeypatch.setenv("DRISHTI_LAKES_DIR", str(_folder(tmp_path)))
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    cwd = tmp_path / "cwd"
    src = tmp_path / "in"
    cwd.mkdir()
    src.mkdir()
    (src / "trade_2026-10-28.jsonl").write_text('{"TRADE_ID": "T-1", "PRODUCT_TYPE": "IRS"}\n', encoding="utf-8")
    monkeypatch.chdir(cwd)
    import build
    args = ["--from", str(src), "--kind", kind, "--as-of", "2026-10-28", "--lake", "main", "--quiet"]
    if partition:
        args += ["--partition-by", partition]
    a = build.add_arguments(argparse.ArgumentParser()).parse_args(args)
    code, summary = build.run(a, say=lambda *_x, **_k: None)
    assert code == build.EXIT_FAILED and summary["status"] == "failed", summary
    assert _files(cwd) == [], _files(cwd)                                 # nothing where it was started
    state = tmp_path / home / build.STATE
    assert (state / "last-run.json").is_file() and (state / "runs.log").is_file()
    assert summary["summaryFile"].startswith(str(state))
    outside = [f for f in _files(tmp_path) if not f.startswith(("lakes/", "in/", home + "/"))]
    assert outside == [], outside


def test_a_refused_build_with_a_lake_root_keeps_its_summary_there(tmp_path, monkeypatch):
    """lake demo's builds (--lake demo --lake-root DIR): refused before the entry is read (here a lake name whose reader fails), the
    summary goes to DIR's _drishti-lake, never ./demo of the current directory."""
    monkeypatch.setenv("DRISHTI_LAKES_DIR", str(_folder(tmp_path)))
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    cwd = tmp_path / "cwd"
    cwd.mkdir()
    monkeypatch.chdir(cwd)
    import build

    def broken(*_a, **_k):
        raise lakeconfig.LakeConfigError("the lake reader said: no")
    monkeypatch.setattr(lakeconfig, "table", broken)
    a = build.add_arguments(argparse.ArgumentParser()).parse_args(
        ["--from", str(tmp_path / "lakes"), "--kind", "trade", "--as-of", "2026-10-28", "--lake", "main", "--lake-root",
         str(tmp_path / "scratch"), "--quiet"])
    code, summary = build.run(a, say=lambda *_x, **_k: None)
    assert code == build.EXIT_FAILED and "the lake reader said" in summary["error"]
    assert _files(cwd) == []
    assert (tmp_path / "scratch" / build.STATE / "last-run.json").is_file()
