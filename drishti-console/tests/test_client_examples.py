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

"""The client examples (examples/clients/README.md).

Always: every notebook is valid, starts with the copyright notice, has a note before every code cell, stores no outputs, holds no token
and makes no write call; every code cell parses; the index lists every notebook and example.

Against a Flight server (the heavy run, on the demo lake): every notebook's code cells run in order in one namespace, as Jupyter would,
then the Excel script, then the Java example when DRISHTI_EXAMPLES_JAVA_CP names its jars. Skipped when DRISHTI_FLIGHT_URL and a token
are not set or the server does not answer; the direct lake notebook is skipped when the demo lake is not on disk.
"""
import ast
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
EXAMPLES = REPO / "examples" / "clients"
READ_KIND_EXAMPLE = EXAMPLES.joinpath("java", "ReadKindAndVarPnl.java")   # the Java example (read as text here)
PY = EXAMPLES / "python"
NOTEBOOKS = sorted(PY.glob("*.ipynb"))
MARK = "Copyright (c) 2026 Ashutosh Sinha"

# What each notebook imports beyond pyarrow (skipped where missing, so a lighter environment still runs the rest).
NEEDS = {"01": ["pandas"], "02": ["pandas", "polars"], "03": ["pandas"], "04": ["pandas"], "05": ["pandas", "numpy", "matplotlib"],
         "06": [], "07": ["pandas", "numpy", "duckdb"], "08": ["pandas"], "09": ["yaml", "deltalake", "pandas"]}
WRITES = ("do_put", "do_exchange", "write_deltalake", ".to_csv(", ".to_parquet(", ".to_excel(", "requests.post", "requests.put",
          "requests.delete", ".delete(", ".vacuum(", ".optimize")


def _cells(nb):
    return json.loads(nb.read_text(encoding="utf-8"))["cells"]


def _code(cells):
    return ["".join(c["source"]) for c in cells if c["cell_type"] == "code"]


def test_nine_notebooks_in_order():
    assert [n.name[:2] for n in NOTEBOOKS] == [f"{i:02d}" for i in range(1, 10)], NOTEBOOKS


@pytest.mark.parametrize("nb", NOTEBOOKS, ids=lambda p: p.stem)
def test_notebook_form(nb):
    doc = json.loads(nb.read_text(encoding="utf-8"))
    assert doc["nbformat"] == 4
    cells = doc["cells"]
    assert cells[0]["cell_type"] == "markdown" and MARK in "".join(cells[0]["source"]), "the notice is the first cell"
    assert cells[1]["cell_type"] == "markdown" and "".join(cells[1]["source"]).startswith("# "), "a title cell follows the notice"
    for i, c in enumerate(cells):
        if c["cell_type"] == "code":
            assert c.get("outputs") == [] and c.get("execution_count") is None, f"cell {i}: outputs stripped"
            ast.parse("".join(c["source"]))
            closing = "".join(c["source"]).strip() == "f.close()"          # the last cell: closing the client needs no note
            assert cells[i - 1]["cell_type"] == "markdown" or closing, f"cell {i}: a markdown note before it"
    text = nb.read_text(encoding="utf-8")
    assert not re.search(r"drk_[A-Za-z0-9]{6,}", text), "no token in a notebook"
    code = "\n".join(_code(cells))
    for w in WRITES:
        assert w not in code, f"read only: {w}"


def test_direct_lake_notebook_is_labelled():
    text = "".join(_cells(PY / "09_direct_lake_read.ipynb")[1]["source"])
    assert "for users who already hold lake access" in text
    for words in ("no entitlements and no masks", "no in-memory tables", "no computed figures", "Read only"):
        assert words in text


def test_index_lists_every_example():
    index = (EXAMPLES / "README.md").read_text(encoding="utf-8")
    for p in NOTEBOOKS + [EXAMPLES.joinpath("java", "ReadKindAndVarPnl.java"), EXAMPLES.joinpath("java", "JdbcFlightSql.java"),
                          EXAMPLES.joinpath("excel", "flight_to_excel.py")]:
        assert p.relative_to(EXAMPLES).as_posix() in index, p
    assert "C#/.NET, R and Go" in index.split("## Later", 1)[1]


def test_example_sources_parse():
    for p in [PY / "flight_setup.py", EXAMPLES / "excel" / "flight_to_excel.py"]:
        ast.parse(p.read_text(encoding="utf-8"))


def _contract_fields(kind):
    """The fields of a kind's data contract (config/packs/<domain>/<pack>/contracts/<kind>.contract.yaml): what a Flight read of the kind may ask for."""
    import yaml
    from core.pack_paths import pack_dirs
    for p in sorted(f for d in pack_dirs(REPO / "config" / "packs").values() for f in d.glob(f"contracts/{kind}.contract.yaml")):
        doc = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        if doc.get("kind") == kind:
            return {f["name"] for f in doc.get("fields") or []}
    return None


def _kind_reads():
    """Every kind read in the examples (the Java example, the notebooks, the Excel script): (where, kind, paths)."""
    texts = [(p.name, p.read_text(encoding="utf-8")) for p in [READ_KIND_EXAMPLE, EXAMPLES / "excel" / "flight_to_excel.py"]]
    for nb in NOTEBOOKS:
        cells = json.loads(nb.read_text(encoding="utf-8"))["cells"]
        texts.append((nb.name, "\n".join("".join(c["source"]) for c in cells if c["cell_type"] == "code")))
    out = []
    for where, text in texts:
        consts = {}
        for m in re.finditer(r"^(\w+) = (\[[^\]]*\])", text, re.M):
            try:
                consts[m.group(1)] = ast.literal_eval(m.group(2))
            except (ValueError, SyntaxError):
                pass                                     # not a list of names (a comprehension)
        for m in re.finditer(r'"kind": "([\w.-]+)", "paths": (\[[^\]]*\])', text):
            out.append((where, m.group(1), json.loads(m.group(2))))
        for m in re.finditer(r'\.kind\("([\w.-]+)", paths=(\[[^\]]*\]|\w+)', text):
            paths = consts.get(m.group(2)) if re.fullmatch(r"\w+", m.group(2)) else ast.literal_eval(m.group(2))
            out.append((where, m.group(1), paths))
    return out


def _mq_keys():
    """The keys a model query may have: MqParser.TOP in the server's source (anything else is DRS-9150 "unknown key")."""
    src = (REPO / "drishti-server/src/main/java/com/ash/drishti/server/bi/mq/MqParser.java").read_text(encoding="utf-8")
    m = re.search(r"Set<String> TOP = Set\.of\(([^;]*)\);", src)
    return set(re.findall(r'"([\w-]+)"', m.group(1)))


def _model_queries():
    """Every model query in the examples, the Python client and the Flight docs: (where, query)."""
    out = []
    sources = [(p.name, p.read_text(encoding="utf-8")) for p in [EXAMPLES / "excel" / "flight_to_excel.py",
                                                                    REPO / "clients" / "python" / "drishti_flight.py",
                                                                    REPO / "docs" / "guides" / "FLIGHT_REFERENCE.md"]]   # the Python client section (§7) of the one Flight reference
    sources += [(nb.name, "\n".join("".join(c["source"]) for c in json.loads(nb.read_text(encoding="utf-8"))["cells"])) for nb in NOTEBOOKS]
    for where, text in sources:
        for m in re.finditer(r'\.model\("[\w@.-]+", (\{.*?\})\)', text, re.S):
            out.append((where, ast.literal_eval(m.group(1).replace("true", "True"))))
    for doc in ("FLIGHT_REFERENCE.md", "FLIGHT_GUIDE.md"):
        for line in (REPO / "docs" / "guides" / doc).read_text(encoding="utf-8").splitlines():
            if line.startswith('{"form": "model"'):
                out.append((doc, json.loads(line)["request"]["query"]))
    return out


def test_every_model_query_uses_the_servers_keys():
    """A Flight model query is the MQ JSON (BI_GUIDE.md): select {fields, measures}, where, sort, limit ...; the old report-visual body
    ({"fields", "measures", "orderBy"}) is refused with DRS-9150 "unknown key 'fields'"."""
    keys, queries = _mq_keys(), _model_queries()
    assert {"select", "where", "sort", "limit"} <= keys
    found = {w for w, _ in queries}
    assert {"03_bi_model_query.ipynb", "flight_to_excel.py", "drishti_flight.py", "FLIGHT_REFERENCE.md"} <= found, found
    for where, q in queries:
        assert set(q) <= keys, f"{where}: unknown key(s) {sorted(set(q) - keys)} in {q}"
        assert set(q.get("select") or {}) <= {"fields", "measures"}, (where, q)


def test_every_kind_read_asks_for_fields_of_the_kind():
    """A Flight read of a kind checks its paths against the kind's data contract (DRS-5603: "trade has no column 'NOTIONAL'"): each
    example asks only for fields the contract names, so it runs on the demo lake."""
    reads = _kind_reads()
    assert any(w == "ReadKindAndVarPnl.java" for w, _, _ in reads) and any(w.startswith("02_") for w, _, _ in reads)
    for where, kind, paths in reads:
        fields = _contract_fields(kind)
        if fields is None:
            continue                                     # a kind with no contract (the error example's no-such-kind)
        assert paths, (where, kind)
        missing = [p for p in paths if p not in fields]
        assert not missing, f"{where}: {kind} has no field {missing} in its data contract"


# ---- against a Flight server (the heavy run) -------------------------------------------------------------------------------------

def _configured():
    return os.environ.get("DRISHTI_FLIGHT_URL") and (os.environ.get("DRISHTI_TOKEN") or os.environ.get("DRISHTI_TOKEN_FILE"))


@pytest.fixture(scope="module")
def server():
    if not _configured():
        pytest.skip("no Flight server: set DRISHTI_FLIGHT_URL and DRISHTI_TOKEN (or DRISHTI_TOKEN_FILE)")
    pytest.importorskip("pyarrow.flight")
    sys.path.insert(0, str(PY))
    from flight_setup import connect
    try:
        with connect(timeout=10) as f:
            return f.whoami()
    except Exception as e:  # noqa: BLE001 - any failure to reach it means no server for this run
        pytest.skip(f"the Flight server does not answer: {e}")


def _run_notebook(nb: Path, monkeypatch):
    monkeypatch.chdir(PY)
    monkeypatch.setenv("MPLBACKEND", "Agg")
    monkeypatch.setenv("DRISHTI_EXAMPLE_STREAM_WAIT", os.environ.get("DRISHTI_EXAMPLE_STREAM_WAIT", "20"))
    monkeypatch.syspath_prepend(str(PY))
    ns = {"__name__": "__main__"}
    for i, src in enumerate(_code(_cells(nb))):
        try:
            exec(compile(src, f"{nb.name}[code {i}]", "exec"), ns)
        except SystemExit as e:
            pytest.fail(f"{nb.name} code cell {i} exited: {e}")
    return ns


@pytest.mark.parametrize("nb", [n for n in NOTEBOOKS if not n.name.startswith("09")], ids=lambda p: p.stem)
def test_notebook_runs_against_the_demo_lake(nb, server, monkeypatch):
    for m in NEEDS[nb.name[:2]]:
        pytest.importorskip(m)
    ns = _run_notebook(nb, monkeypatch)
    if nb.name.startswith("05"):
        assert ns["VAR"] > 0 and ns["ES"] >= ns["VAR"] * 0.5 and len(ns["df"]) > 0
        assert ns["k"] >= 1


def test_direct_lake_notebook_runs(monkeypatch):
    for m in NEEDS["09"]:
        pytest.importorskip(m)
    root = Path(os.environ.get("DRISHTI_DELTA_ROOT") or REPO / "data" / "delta" / "demo")
    if not (root / "trading" / "trade_endur").is_dir():
        pytest.skip(f"the demo lake is not on disk at {root}")
    ns = _run_notebook(PY / "09_direct_lake_read.ipynb", monkeypatch)
    assert ns["rows"].num_rows > 0


def test_excel_script(server, tmp_path):
    pytest.importorskip("openpyxl")
    sys.path.insert(0, str(EXAMPLES / "excel"))
    import flight_to_excel
    out = tmp_path / "read.xlsx"
    assert flight_to_excel.main(["--out", str(out)]) == 0
    from openpyxl import load_workbook
    wb = load_workbook(out)
    assert wb.sheetnames == ["Summary", "By desk", "P&L", "About"]
    assert wb["P&L"].max_row > 1


def test_java_example(server):
    cp = os.environ.get("DRISHTI_EXAMPLES_JAVA_CP")
    if not cp or not shutil.which("java"):
        pytest.skip("set DRISHTI_EXAMPLES_JAVA_CP to the example's jars (examples/clients/java/README.md)")
    sys.path.insert(0, str(REPO / "tools"))
    from javacmd import java_cmd                          # every java a test starts names its temp folder
    r = subprocess.run(java_cmd("java", ["--add-opens=java.base/java.nio=ALL-UNNAMED", "-cp", cp, "ReadKindAndVarPnl.java"]),
                       cwd=EXAMPLES / "java", capture_output=True, text=True, timeout=300)
    assert r.returncode == 0, r.stderr
    assert "trades" in r.stdout and "scenarios" in r.stdout, r.stdout
