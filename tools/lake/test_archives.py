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

"""The archive rule (DATA_GUIDE.md section 2.7), Python side: every case of the shared fixtures (testdata/archives/expected.json, the
same cases drishti-lakeload's ArchivesTest runs), the row counts of the unpacked files, and the loaders that read archives: lake build's
reader (jsonstream), load_rows.py and convert.

    python3 -m pytest -q tools/lake/test_archives.py
"""
from __future__ import annotations

import gzip
import json
import pathlib
import sys
import tarfile
import zipfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
FIX = HERE / "testdata" / "archives"
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import archives as A  # noqa: E402
import jsonstream  # noqa: E402

EXPECTED = json.loads((FIX / "expected.json").read_text(encoding="utf-8"))
CASES = EXPECTED["cases"]


def limits(case) -> A.Limits:
    return A.Limits.from_env(env={}, **(case.get("limits") or {}))


@pytest.mark.parametrize("case", CASES, ids=[c["case"] for c in CASES])
def test_the_shared_cases(case):
    path = FIX / case["file"]
    lim = limits(case)
    if "refused" in case:
        with pytest.raises(A.ArchiveError) as e:
            p = A.plan(path, case.get("dataset"), case.get("configured"), lim)
            A.row_counts(p, lim)
        assert case["refused"] in str(e.value), str(e.value)
        return
    p = A.plan(path, case.get("dataset"), case.get("configured"), lim)
    want = case["expect"]
    assert sorted(p.datasets) == sorted(want["datasets"])
    for name, d in want["datasets"].items():
        assert [m.name for m in p.datasets[name]] == d["members"]
    assert A.row_counts(p, lim) == {n: d["rows"] for n, d in want["datasets"].items()}
    assert len(p.notes) == want["notes"], p.notes


def test_the_defaults_are_the_shared_ones():
    d = EXPECTED["defaults"]
    assert (A.DEFAULT_MAX_MB, A.DEFAULT_MAX_RATIO, A.DEFAULT_MAX_MEMBERS, A.FLOOR) == (d["max_mb"], d["max_ratio"], d["max_members"], d["floor_bytes"])
    assert A.Limits.from_env(env={"DRISHTI_ARCHIVE_MAX_MB": "5", "DRISHTI_ARCHIVE_MAX_RATIO": "7", "DRISHTI_ARCHIVE_MAX_MEMBERS": "9"}) \
        == A.Limits(5, 7, 9)


def unpacked_rows(archive: pathlib.Path, into: pathlib.Path) -> dict[str, int]:
    """The archive unpacked by the standard tools, then its JSON Lines counted per dataset as plain files."""
    if archive.name.endswith(".zip"):
        zipfile.ZipFile(archive).extractall(into)
    else:
        with tarfile.open(archive) as t:
            t.extractall(into, filter="data")
    out: dict[str, int] = {}
    for f in sorted(into.rglob("*")):
        rel = f.relative_to(into).parts
        if not f.is_file() or not A.is_jsonl(f.name) or any(p.startswith(".") or p == "__MACOSX" for p in rel):
            continue
        ds = rel[0] if len(rel) == 2 else A.archive_stem(archive.name)
        opener = gzip.open if f.name.endswith(".gz") else open
        with opener(f, "rt", encoding="utf-8") as fh:
            out[ds] = out.get(ds, 0) + sum(1 for line in fh if line.strip())
    return out


@pytest.mark.parametrize("name", ["trade.tar.gz", "trade.tgz", "trade.tar", "trade.zip", "bundle.tar.gz", "bundle.zip"])
def test_the_same_rows_as_the_unpacked_files(name, tmp_path):
    p = A.plan(FIX / name, limits=A.Limits())
    assert A.row_counts(p) == unpacked_rows(FIX / name, tmp_path)


def test_lake_builds_reader_reads_an_archive_in_name_order(tmp_path):
    files = jsonstream.input_files([FIX / "trade.zip"])
    assert [m.label for m in files] == ["trade.zip!/a.jsonl", "trade.zip!/b.jsonl.gz", "trade.zip!/c.ndjson"]
    docs = [d for d, _ in jsonstream.documents([FIX / "trade.zip"])]
    assert [d["id"] for d in docs] == ["TRADE-1", "TRADE-2", "TRADE-3", "TRADE-4", "TRADE-5", "TRADE-6"]
    where = [w for _, w in jsonstream.documents([FIX / "trade.tar.gz"])]
    assert where[0] == "trade.tar.gz!/a.jsonl:1" and where[1] == "trade.tar.gz!/a.jsonl:3"          # the blank line still counts


def test_lake_builds_reader_takes_the_folder_of_its_table_only(tmp_path):
    one = tmp_path / "day.zip"
    with zipfile.ZipFile(FIX / "bundle.zip") as src, zipfile.ZipFile(one, "w") as z:
        z.writestr("trade/t1.jsonl", src.read("trade/t1.jsonl"))
    assert [m.label for m in jsonstream.input_files([one], table="trade")] == ["day.zip!/trade/t1.jsonl"]
    with pytest.raises(A.ArchiveError, match="position"):
        jsonstream.input_files([FIX / "bundle.zip"], table="trade")           # the other folder: refused, not silently left out
    with pytest.raises(A.ArchiveError, match="one table"):
        jsonstream.input_files([FIX / "bundle.tar.gz"])


def test_a_folder_of_archives_is_read_with_its_plain_files(tmp_path):
    (tmp_path / "x.jsonl").write_text('{"id": "X"}\n', encoding="utf-8")
    (tmp_path / "trade.tgz").write_bytes((FIX / "trade.tgz").read_bytes())
    docs = [d["id"] for d, _ in jsonstream.documents([tmp_path])]
    assert docs == ["TRADE-1", "TRADE-2", "TRADE-3", "TRADE-4", "TRADE-5", "TRADE-6", "X"]


def test_load_rows_loads_each_folder_as_its_table(tmp_path):
    pytest.importorskip("deltalake")
    from samplegen import load_rows
    root = tmp_path / "delta"
    said = []
    total = load_rows.load([FIX / "bundle.tar.gz"], root, layouts={}, say=said.append)
    assert total == 7
    from deltalake import DeltaTable
    assert DeltaTable(str(root / "fixture" / "trade")).to_pyarrow_table().num_rows == 4
    assert DeltaTable(str(root / "fixture" / "position")).to_pyarrow_table().num_rows == 3
    assert any("position" in s and "1 file" in s for s in said), said


def test_load_rows_refuses_a_folder_whose_rows_name_another_table(tmp_path):
    from samplegen import load_rows
    bad = tmp_path / "rows.zip"
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("position/p.jsonl", json.dumps({"domain": "fixture", "kind": "trade", "id": "1", "date": "2026-10-01", "doc": "{}"}) + "\n")
    with pytest.raises(SystemExit, match="position"):
        load_rows.load([bad], tmp_path / "delta", layouts={})
    assert not (tmp_path / "delta").exists()


def test_load_rows_writes_nothing_when_one_archive_is_refused(tmp_path):
    from samplegen import load_rows
    with pytest.raises(SystemExit, match="mixed"):
        load_rows.load([FIX / "bundle.zip", FIX / "mixed.tar.gz"], tmp_path / "delta", layouts={})
    assert not (tmp_path / "delta").exists()


def test_convert_unpacks_an_archive_into_json_lines(tmp_path):
    import convertcmd as C
    out = tmp_path / "flat"
    n = C.convert(FIX / "trade.tar.gz", str(out / "trade.jsonl"), say=lambda m: None)
    assert n == 6 and len((out / "trade.jsonl").read_text().splitlines()) == 6
    said = []
    n = C.convert(FIX / "bundle.zip", str(tmp_path / "bundle"), say=said.append)
    assert n == 7
    assert len((tmp_path / "bundle" / "trade.jsonl").read_text().splitlines()) == 4
    assert len((tmp_path / "bundle" / "position.jsonl").read_text().splitlines()) == 3
    with pytest.raises(ValueError, match="unsafe"):
        C.convert(FIX / "slip.zip", str(tmp_path / "slip.jsonl"), say=lambda m: None)


def test_the_caps_come_from_the_environment(monkeypatch):
    monkeypatch.setenv("DRISHTI_ARCHIVE_MAX_RATIO", "2")
    with pytest.raises(A.ArchiveError, match="max-ratio"):
        A.plan(FIX / "bomb.zip")
    monkeypatch.delenv("DRISHTI_ARCHIVE_MAX_RATIO")
    assert A.plan(FIX / "bomb.zip").datasets


def test_names(tmp_path):
    assert [A.archive_stem(n) for n in ("trade.tar.gz", "trade.TGZ", "trade.zip", "trade.tar", "a.b.zip")] == ["trade", "trade", "trade", "trade", "a.b"]
    assert A.valid_name("trade_endur") and A.valid_name("scenario-pv") and not A.valid_name("bad name") and not A.valid_name(".x")
    with pytest.raises(A.ArchiveError, match="unsafe"):
        A.safe_parts("..\\x.jsonl")
    with pytest.raises(A.ArchiveError, match="unsafe"):
        A.safe_parts("C:/x.jsonl")
