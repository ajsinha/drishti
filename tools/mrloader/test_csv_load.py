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

"""tools/mrloader/csv_load.py: the CSV conventions (declared, never guessed), the mapping checked against the contract before a row is
read, the reject report, on-reject, the files and their marker, and the helpers; on small hand-made files (no Java, no server)."""
from __future__ import annotations

import csv
import gzip
import json
import subprocess
import sys
from pathlib import Path

import pytest

yaml = pytest.importorskip("yaml")

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from mrloader import csv_load as L, csvread as R  # noqa: E402
from mrloader.common import PvfError, sha256_of  # noqa: E402
from mrloader.package import read_marker  # noqa: E402
from mrloader.rejects import Problem  # noqa: E402

SAMPLES = Path(__file__).resolve().parent / "samples"
D = "2026-11-18"


def _run(tmp_path, kind, text, *extra, name="in.csv"):
    f = tmp_path / name
    f.write_bytes(text.encode("utf-8") if isinstance(text, str) else text)
    return L.main(["--kind", kind, "--csv", str(f), "--date", D, "--out", str(tmp_path / "o"), *extra]), tmp_path / "o" / f"mr-{D}"


def _rejects(folder, kind):
    with open(folder / f"{kind}.{D}.rejects.csv", newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


# ---- the reader -----------------------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("text,dialect,want", [
    ("1,234,567.89", {}, 1234567.89), ("(1,234.50)", {}, -1234.5), ("1234.50-", {}, -1234.5), ("+1.2E+07", {}, 1.2e7),
    ("1.234.567,89", {"decimal": ",", "thousands": "."}, 1234567.89), ("12", {}, 12.0)])
def test_numbers_accepted(text, dialect, want):
    assert R.number(text, R.Dialect.from_doc(dialect)) == pytest.approx(want)


@pytest.mark.parametrize("text,dialect", [("$12", {}), ("1,23.4", {}), ("12.5", {"decimal": ",", "thousands": "."}), ("EUR 3", {}), ("", {})])
def test_numbers_refused(text, dialect):
    with pytest.raises(Problem) as e:
        R.number(text, R.Dialect.from_doc(dialect))
    assert e.value.reason == "BAD_NUMBER"


@pytest.mark.parametrize("text,fmt,want", [
    ("2026-10-29", None, "2026-10-29"), ("20261029", None, "2026-10-29"), ("2026-10-29 00:00:00", None, "2026-10-29"),
    ("29/10/2026", "DD/MM/YYYY", "2026-10-29"), ("10/29/2026", "MM/DD/YYYY", "2026-10-29"), ("29-Oct-2026", "DD-MMM-YYYY", "2026-10-29"),
    ("46324", "excel-serial", "2026-10-29")])
def test_dates_accepted(text, fmt, want):
    assert R.date(text, R.Dialect.from_doc({"dates": fmt} if fmt else {})) == want


@pytest.mark.parametrize("text,fmt", [("29/10/2026", None), ("29/10/26", "DD/MM/YYYY"), ("31/02/2026", "DD/MM/YYYY"), ("46324", None)])
def test_dates_refused_never_read_both_ways(text, fmt):
    with pytest.raises(Problem) as e:
        R.date(text, R.Dialect.from_doc({"dates": fmt} if fmt else {}))
    assert e.value.reason == "BAD_DATE" and e.value.fix


def test_cells_ids_rates_booleans_timestamps():
    d = R.Dialect()
    assert R.clean("  x ", d) == "x" and R.clean("#N/A", d) is None and R.clean("0", d) == "0" and R.clean("'0123", d) == "0123"
    for bad, code in (("#REF!", "EXCEL_ERROR"), ("=A2*2", "FORMULA")):
        with pytest.raises(Problem) as e:
            R.clean(bad, d)
        assert e.value.reason == code
    with pytest.raises(Problem) as e:
        R.ident("2.00001E+11")
    assert e.value.reason == "EXCEL_NUMBER_ID"
    assert R.ident("000123") == "000123"
    assert R.rate("0.05", d) == 0.05 and R.rate("5%", d, percent=True) == pytest.approx(0.05) and R.rate("5", d, percent=True) == 0.05
    with pytest.raises(Problem):
        R.rate("5%", d)
    assert R.boolean("y", d) is True and R.boolean("0", d) is False
    with pytest.raises(Problem):
        R.boolean("maybe", d)
    assert R.timestamp("2026-10-29T06:10:00Z", d) == "2026-10-29T06:10:00Z"
    with pytest.raises(Problem):
        R.timestamp("2026-10-29T06:10:00", d)
    assert R.timestamp("2026-10-29T06:10:00", R.Dialect.from_doc({"timezone": "America/Toronto"})) == "2026-10-29T10:10:00Z"


def test_file_dialects(tmp_path):
    p = tmp_path / "a.csv"
    p.write_bytes("﻿sep=;\nLegal Entity;limit-var\nA;\"1;2\"\n\n".encode("utf-8"))
    f = R.CsvFile(p, R.Dialect())
    assert f.columns == ["LEGAL_ENTITY", "LIMIT_VAR"] and f.delimiter == ";"
    assert list(f.rows()) == [(3, {"LEGAL_ENTITY": "A", "LIMIT_VAR": "1;2"})]
    p.write_bytes("A,B\n1,2,3\n\"x\ny\",2\n".encode())
    rows = list(R.CsvFile(p, R.Dialect()).rows())
    assert rows[0][1].reason == "BAD_ROW_SHAPE" and rows[1] == (3, {"A": "x\ny", "B": "2"})
    p.write_bytes("A,a\n1,2\n".encode())
    with pytest.raises(PvfError, match="both become"):
        R.CsvFile(p, R.Dialect())
    p.write_bytes("A,B\n\xe9,2\n".encode("cp1252"))
    with pytest.raises(PvfError, match="cp1252"):
        list(R.CsvFile(p, R.Dialect()).rows())
    assert list(R.CsvFile(p, R.Dialect.from_doc({"encoding": "cp1252"})).rows()) == [(2, {"A": "é", "B": "2"})]
    p.write_bytes(b"A,B\n\"open,2\n")
    with pytest.raises(PvfError, match="row 2"):
        list(R.CsvFile(p, R.Dialect()).rows())
    p.write_bytes(b"Title\nA,B,,\n1,2,,\nTotal,3,,\n")
    rows = list(R.CsvFile(p, R.Dialect.from_doc({"skip-rows": 1, "footer": "^Total"})).rows())
    assert rows == [(3, {"A": "1", "B": "2"})]


# ---- var-limit end to end -------------------------------------------------------------------------------------------------------
def test_var_limit_sample_gives_its_expected_records(tmp_path):
    rc, folder = _run(tmp_path, "var-limit", (SAMPLES / "var-limit.csv").read_text())
    assert rc == 0
    got = (folder / f"var-limit.{D}.jsonl").read_text()
    assert got == (SAMPLES / "var-limit.expected.jsonl").read_text()
    gz = folder / f"var-limit.{D}.jsonl.gz"
    assert gzip.open(gz, "rt").read() == got
    assert read_marker(Path(f"{gz}.ready")) == {"pack": "market-risk", "kind": "var-limit", "mode": "replace", "rows": 4, "sha256": sha256_of(gz)}
    assert (folder / f"mr-{D}.sha256").read_text() == f"{sha256_of(gz)}  {gz.name}\n"
    first = gz.read_bytes()
    assert _run(tmp_path, "var-limit", (SAMPLES / "var-limit.csv").read_text())[0] == 0 and gz.read_bytes() == first   # the same bytes


def test_a_rejected_limit_refuses_the_kind_and_says_where(tmp_path):
    text = "HIERARCHY,NODE,LIMIT_VAR,VALID_FROM\nMGMT,DESK-A,100,2026-01-02\nPORTFOLIO,,1200000,2026-06-01\nMGMT,DESK-B,1e,14/03/2026\n"
    rc, folder = _run(tmp_path, "var-limit", text)
    assert rc == 1 and not (folder / f"var-limit.{D}.jsonl.gz").exists()                 # on-reject: refuse: nothing loadable
    rows = _rejects(folder, "var-limit")
    assert [(r["ROW"], r["REASON"], r["FIELD"]) for r in rows] == [("3", "MISSING_REQUIRED", "NODE_ID"), ("4", "BAD_NUMBER", "LIMIT_VAR")]
    s = json.loads((folder / f"var-limit.{D}.summary.json").read_text())
    assert s["STATUS"] == "REFUSED" and s["ROWS_REJECTED"] == 2


def test_duplicate_key_refuses_and_names_the_first_row(tmp_path):
    rc, folder = _run(tmp_path, "var-limit", "HIERARCHY,NODE,LIMIT_VAR\nMGMT,DESK-A,1\nMGMT,DESK-A,2\n")
    assert rc == 1
    r = _rejects(folder, "var-limit")[0]
    assert (r["ROW"], r["REASON"], r["KEY"]) == ("3", "DUPLICATE_KEY", "VL-MGMT~DESK-A") and "row 2" in r["WHY"]


def test_header_missing_a_column_refuses_before_a_row_is_read(tmp_path, caplog):
    rc, folder = _run(tmp_path, "var-limit", "HIERARCHY,NODE,LIMIT\nMGMT,DESK-A,1\n")
    assert rc == 1 and "LIMIT_VAR" in caplog.text and not folder.exists()


def test_computed_columns_are_named_and_never_loaded(tmp_path, caplog):
    caplog.set_level("INFO")
    text = "HIERARCHY,NODE,LIMIT_VAR,UTILISATION\nMGMT,DESK-A,100,0.5\n"
    rc, folder = _run(tmp_path, "var-limit", text)
    assert rc == 0 and "UTILISATION: not loaded" in caplog.text
    assert "UTILISATION" not in (folder / f"var-limit.{D}.jsonl").read_text()
    assert _run(tmp_path, "var-limit", text, "--strict")[0] == 1


def test_european_file_declared_in_a_site_mapping(tmp_path):
    m = yaml.safe_load((L.KINDS_DIR / "var-limit.yaml").read_text())
    m["csv"] = {"delimiter": ";", "decimal": ",", "thousands": ".", "dates": "DD/MM/YYYY"}
    site = tmp_path / "site-var-limit.yaml"
    site.write_text(yaml.safe_dump(m, sort_keys=False))
    rc, folder = _run(tmp_path, "var-limit", "HIERARCHY;NODE;LIMIT_VAR;VALID_FROM\nMGMT;DESK-A;4.100.000,50;02/01/2026\n", "--mapping", str(site))
    assert rc == 0
    r = json.loads((folder / f"var-limit.{D}.jsonl").read_text())
    assert r["LIMIT_VAR"] == 4100000.5 and r["VALID_FROM"] == "2026-01-02"


def test_a_mapping_is_checked_against_the_contract(tmp_path):
    m = yaml.safe_load((L.KINDS_DIR / "var-limit.yaml").read_text())
    m["fields"]["NOT_A_FIELD"] = {"from": "X"}
    del m["fields"]["LIMIT_VAR"]
    p = tmp_path / "bad.yaml"
    p.write_text(yaml.safe_dump(m, sort_keys=False))
    with pytest.raises(PvfError) as e:
        L.KindMapping(p)
    assert "NOT_A_FIELD is not a field" in str(e.value) and "LIMIT_VAR" in str(e.value)


def test_never_kinds_are_refused_with_the_reason(tmp_path, caplog):
    for kind, why in (("frtb-sensitivity", "position-sensitivity"), ("portfolio", "book"), ("var-result", "computed")):
        rc, _ = _run(tmp_path, kind, "A\n1\n")
        assert rc == 1 and why in caplog.text


def test_template_and_kinds_by_path():
    t = subprocess.run([sys.executable, str(TOOLS / "mrloader" / "csv_load.py"), "--kind", "var-limit", "--template"], capture_output=True,
                       text=True, timeout=60)
    assert t.returncode == 0 and t.stdout.splitlines()[0] == "HIERARCHY,NODE_ID,LIMIT_VAR,CURRENCY,HORIZON,CONFIDENCE,VALID_FROM,VALID_TO"
    k = subprocess.run([sys.executable, str(TOOLS / "mrloader" / "csv_load.py"), "--kinds"], capture_output=True, text=True, timeout=60)
    assert k.returncode == 0 and "var-limit" in k.stdout
    h = subprocess.run([sys.executable, str(TOOLS / "mrloader" / "csv_load.py"), "--help"], capture_output=True, text=True, timeout=60)
    assert h.returncode == 0 and "example" in h.stdout.lower()


def test_validate_any_kind_by_its_marker(tmp_path):
    from mrloader import validate as V
    rc, folder = _run(tmp_path, "var-limit", (SAMPLES / "var-limit.csv").read_text())
    assert rc == 0 and V.validate([folder])["OK"]
    bad = tmp_path / f"var-limit.{D}.jsonl"
    bad.write_text('{"LIMIT_ID":"X","HIERARCHY":"MGMT","NODE_ID":"MGMT~A","LIMIT_VAR":1,"CURRENCY":"USD","BUSINESS_DATE":"2026-11-18","VALID_FROM":"2026-01-02"}\n')
    res = V.validate([bad])
    assert not res["OK"] and "var-limit.LIMIT_ID" in res["PROBLEMS"]
