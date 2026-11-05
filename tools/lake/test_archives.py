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
    feed = tmp_path / "feed.tar.gz"
    feed_tar(feed, {"trade/2026-10-01/a.jsonl": '{"id": "1"}\n\n{"id": "2"}\n'})
    where = [w for _, w in jsonstream.documents([feed])]
    assert where[0] == "feed.tar.gz!/trade/2026-10-01/a.jsonl:1" and where[1] == "feed.tar.gz!/trade/2026-10-01/a.jsonl:3"   # a blank line counts


def feed_tar(path, members: dict) -> None:
    import io
    with tarfile.open(path, "w:gz") as t:
        for name, text in members.items():
            data = text.encode()
            info = tarfile.TarInfo(name)
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))


def test_a_feed_zip_is_one_table_and_a_feed_tar_names_its_tables_and_dates(tmp_path):
    """A loader's feed (docs/architecture/DATA_LOAD_RULES.md): a zip holds loose files only; a tar holds <table>/<YYYY-MM-DD>/ folders."""
    with pytest.raises(A.ArchiveError, match="a .zip is one table and one business date"):
        jsonstream.input_files([FIX / "bundle.zip"], table="trade")
    with pytest.raises(A.ArchiveError, match="refused, nothing was loaded"):
        jsonstream.input_files([FIX / "bundle.tar.gz"])
    feed = tmp_path / "eod.tar.gz"
    feed_tar(feed, {"trade/2026-10-01/t1.jsonl": '{"id": "1"}\n', "position/2026-10-01/p.jsonl": '{"id": "P"}\n'})
    got = jsonstream.input_files([feed], tar_table="trade")
    assert [(m.label, str(m.date)) for m in got] == [("eod.tar.gz!/trade/2026-10-01/t1.jsonl", "2026-10-01")]
    with pytest.raises(A.ArchiveError, match="position"):
        jsonstream.input_files([feed], table="trade")                        # the other table: refused, not silently left out
    plan = A.plan(feed, layout="feed")
    assert sorted(plan.datasets) == ["position", "trade"]
    assert A.plan(FIX / "bundle.tar.gz").folders                              # convert's rule (layout rows) is unchanged


def test_a_folder_of_archives_is_read_with_its_plain_files(tmp_path):
    (tmp_path / "x.jsonl").write_text('{"id": "X"}\n', encoding="utf-8")
    (tmp_path / "trade.zip").write_bytes((FIX / "trade.zip").read_bytes())
    docs = [d["id"] for d, _ in jsonstream.documents([tmp_path])]
    assert docs == ["TRADE-1", "TRADE-2", "TRADE-3", "TRADE-4", "TRADE-5", "TRADE-6", "X"]


def lake_row(table, id_, day, domain="fixture"):
    return json.dumps({"domain": domain, "kind": table, "table": table, "id": id_, "date": day, "doc": json.dumps({"id": id_})}) + "\n"


def test_load_rows_loads_each_folder_as_its_table_under_its_date(tmp_path):
    """Lake rows by the load rules (DATA_LOAD_RULES.md): a tar's <table>/<date>/ folders decide each row's table and business date;
    a row whose own date differs is kept as it is and counted, never moved."""
    pytest.importorskip("deltalake")
    from samplegen import load_rows
    feed = tmp_path / "rows.tar.gz"
    feed_tar(feed, {"trade/2026-10-01/r.jsonl": lake_row("trade", "1", "2026-10-01") + lake_row("trade", "2", "2026-09-30"),
                    "trade/2026-10-02/r.jsonl": lake_row("trade", "1", "2026-10-02"),
                    "position/2026-10-01/r.jsonl": lake_row("position", "P", "2026-10-01")})
    root = tmp_path / "delta"
    said = []
    assert load_rows.load([feed], root, layouts={}, say=said.append) == 4
    from deltalake import DeltaTable
    t = DeltaTable(str(root / "fixture" / "trade")).to_pyarrow_table().to_pylist()
    assert sorted((r["id"], str(r["business_date"])) for r in t) == [("1", "2026-10-01"), ("1", "2026-10-02"), ("2", "2026-10-01")]
    assert DeltaTable(str(root / "fixture" / "position")).to_pyarrow_table().num_rows == 1
    assert "1 row of rows.tar.gz!/trade/2026-10-01/r.jsonl carried another date in date; loaded under 2026-10-01" in said, said
    with pytest.raises(SystemExit, match="--as-of is refused"):
        load_rows.load([feed], tmp_path / "other", layouts={}, as_of=__import__("datetime").date(2026, 10, 3))


def test_load_rows_a_plain_file_is_one_table_and_one_date(tmp_path):
    pytest.importorskip("deltalake")
    from samplegen import load_rows
    one = tmp_path / "trade.2026-10-01.jsonl"
    one.write_text(lake_row("trade", "1", "2026-10-01") + lake_row("trade", "2", "2026-10-01"), encoding="utf-8")
    said = []
    assert load_rows.load([one], tmp_path / "delta", layouts={}, say=said.append) == 2
    assert "trade.2026-10-01.jsonl: business date 2026-10-01 from the file name" in said
    two = tmp_path / "both.2026-10-01.jsonl"
    two.write_text(lake_row("trade", "1", "2026-10-01") + lake_row("position", "P", "2026-10-01"), encoding="utf-8")
    with pytest.raises(SystemExit, match="send a tar"):
        load_rows.load([two], tmp_path / "delta2", layouts={})
    assert not (tmp_path / "delta2").exists()


def test_load_rows_refuses_a_folder_whose_rows_name_another_table(tmp_path):
    from samplegen import load_rows
    bad = tmp_path / "rows.tar.gz"
    feed_tar(bad, {"position/2026-10-01/p.jsonl": lake_row("trade", "1", "2026-10-01")})
    with pytest.raises(SystemExit, match="position"):
        load_rows.load([bad], tmp_path / "delta", layouts={})
    assert not (tmp_path / "delta").exists()


def test_load_rows_writes_nothing_when_one_archive_is_refused(tmp_path):
    from samplegen import load_rows
    with pytest.raises(SystemExit):
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


def test_lake_build_loads_an_archive_and_refuses_a_folder_of_another_table(tmp_path):
    pytest.importorskip("deltalake")
    import subprocess
    cli = [sys.executable, str(HERE.parent / "drishti.py"), "lake", "build", "--domain", "fixture", "--kind", "trade", "--key", "id",
           "--lake", str(tmp_path / "lake")]
    ok = subprocess.run(cli + ["--from", str(FIX / "trade.zip")], capture_output=True, text=True, timeout=300)
    assert ok.returncode == 0, ok.stdout + ok.stderr
    assert "rows written: 6" in ok.stdout
    refused = subprocess.run(cli + ["--from", str(FIX / "bundle.zip")], capture_output=True, text=True, timeout=300)
    assert refused.returncode == 1 and "a .zip is one table and one business date" in refused.stdout + refused.stderr
