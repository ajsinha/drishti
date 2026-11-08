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

"""Delta interoperability: Drishti's lakes follow the Delta protocol's path rules, tested against delta-rs (the ``deltalake`` package)
both ways, with partition values that need escaping (a space, ``%``, ``:``, ``/``, ``=``, ``#``, ``?``, quotes, non-ASCII, an empty
string, a null) and a date partition (``business_date``).

1. delta-rs writes, Drishti's Java reader reads (Delta Kernel on Drishti's ``NativeEngine``, the server's lake reader): every row and
   every partition value equal.
2. Drishti writes, delta-rs (``DeltaTable``) and ``pyarrow.dataset`` over the files read: every row equal. Drishti's writers are the
   Java lake writer (``lake build --engine drishti``), the parallel Java loader (``--engine java``) and the Python path
   (``--engine deltalake``); each table is also read back by the Java reader.
3. On disk: a Drishti Java writer names a partition folder with Hive escaping exactly as Spark does (a space and non-ASCII literal,
   ``"#%'*/:=?\\{[]^`` and control characters as %XX); every writer's ``add.path`` is a URI that decodes to the file's relative
   path on disk; ``partitionValues`` hold the raw values.

An empty partition string reads back as null, as Spark and delta-rs read it. The Java parts need the server's exec jar (DRISHTI_JAR,
else drishti-server/target) and a JDK (javac compiles the small reader against the jar's own libraries).

    uv run --with deltalake --with pyarrow --with pyyaml --with pytest python -m pytest tools/lake/test_delta_interop.py -q
"""
import datetime
import json
import os
import pathlib
import subprocess
import sys
import urllib.parse
import zipfile

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(1, str(HERE.parent))
import build  # noqa: E402
import writers  # noqa: E402
from javacmd import java_cmd  # noqa: E402

deltalake = pytest.importorskip("deltalake")
pa = pytest.importorskip("pyarrow")
import pyarrow.dataset as pads  # noqa: E402

VALUES = ["Money market", " lead", "trail ", "100%", "%2F", "%", "a:b", "x/y", "/lead", "trail/", "a//b", "../escape", ".", "..",
          "a.b", "back\\slash", "..\\up", "tab\there", "new\nline", "ctl\x01", "del\x7f", "k=v", "a#b", "a?b", "a*b", "q\"q", "it's",
          "{c}", "[b]", "a^b", "a|b", "<a>", "a&b", "a+b", "a~b", "a@b", "a!b", "a$b", "a,b", "a;b", "Zürich", "東京", "😀🚀",
          "x" * 200, "__HIVE_DEFAULT_PARTITION__", "", None]
SENTINEL = "__HIVE_DEFAULT_PARTITION__"
DATES = ["2026-11-05", "2026-11-06"]
SPARK_ESCAPED = set('"#%\'*/:=?\\{[]^\x7f') | {chr(c) for c in range(1, 32)}


def spark_escape(value: str) -> str:
    """Spark's ExternalCatalogUtils.escapePathName: the Hive folder name of a partition value."""
    return "".join(f"%{ord(c):02X}" if c in SPARK_ESCAPED else c for c in value)


def expected_rows() -> dict:
    """id -> (business_date, PRODUCT_GROUP) as every reader must see them: an empty string is null, as in Spark."""
    out = {}
    for d, day in enumerate(DATES):
        for i, v in enumerate(VALUES):
            out[f"{d}-{i:02d}"] = (day, v if v != "" else None)
    return out


def norm(v):
    return None if v == "" else v


# ---- the Java reader ----------------------------------------------------------------------------------------------------------------

READER = r'''
import com.ash.drishti.deltalake.NativeEngine;
import io.delta.kernel.Scan;
import io.delta.kernel.Table;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.internal.InternalScanFileUtils;
import io.delta.kernel.internal.data.ScanStateRow;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class DeltaInteropRead {
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Engine engine = NativeEngine.create();
        Scan scan = Table.forPath(engine, args[0]).getLatestSnapshot(engine).getScanBuilder().build();
        Row state = scan.getScanState(engine);
        StructType physical = ScanStateRow.getPhysicalDataReadSchema(state);
        try (CloseableIterator<FilteredColumnarBatch> files = scan.getScanFiles(engine)) {
            while (files.hasNext()) {
                try (CloseableIterator<Row> fr = files.next().getRows()) {
                    while (fr.hasNext()) {
                        Row file = fr.next();
                        FileStatus fs = InternalScanFileUtils.getAddFileStatus(file);
                        Map<String, String> pv = new TreeMap<>(InternalScanFileUtils.getPartitionValues(file));
                        StringBuilder m = new StringBuilder("{");
                        pv.forEach((k, v) -> m.append(m.length() > 1 ? "," : "").append(q(k)).append(':').append(q(v)));
                        out.println("{\"file\":" + q(fs.getPath()) + ",\"partitionValues\":" + m + "}}");
                        try (CloseableIterator<FileReadResult> raw = engine.getParquetHandler()
                                .readParquetFiles(Utils.singletonCloseableIterator(fs), physical, Optional.empty());
                             CloseableIterator<FilteredColumnarBatch> data = Scan.transformPhysicalData(engine, state, file,
                                     raw.map(FileReadResult::getData))) {
                            while (data.hasNext()) {
                                try (CloseableIterator<Row> rows = data.next().getRows()) {
                                    while (rows.hasNext()) {
                                        out.println("{\"row\":" + row(rows.next()) + "}");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static String row(Row r) {
        StringBuilder b = new StringBuilder("{");
        StructType s = r.getSchema();
        for (int i = 0; i < s.length(); i++) {
            DataType t = s.at(i).getDataType();
            String v = r.isNullAt(i) ? null : t instanceof StringType ? r.getString(i)
                    : t instanceof DateType ? LocalDate.ofEpochDay(r.getInt(i)).toString() : null;
            if (t instanceof StringType || t instanceof DateType) {
                b.append(b.length() > 1 ? "," : "").append(q(s.at(i).getName())).append(':').append(q(v));
            }
        }
        return b.append('}').toString();
    }

    private static String q(String v) {
        if (v == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
'''


@pytest.fixture(scope="module")
def java_reader(tmp_path_factory):
    """A function reading a Delta table with Drishti's Java reader: (files [(path, partitionValues)], rows [dict])."""
    jar = writers.find_jar()
    java = writers.find_java()
    if not jar or not java:
        pytest.skip("no server exec jar (DRISHTI_JAR or drishti-server/target) or no java")
    javac = pathlib.Path(java).with_name("javac")
    if not javac.is_file():
        pytest.skip(f"no javac next to {java}")
    work = tmp_path_factory.mktemp("java-reader")
    libs, classes = work / "libs", work / "classes"
    libs.mkdir()
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            base = n.rsplit("/", 1)[-1]
            if n.startswith("BOOT-INF/lib/") and base.startswith(("delta-kernel-api-", "drishti-deltalake-", "drishti-api-",
                                                                   "drishti-lakeio-")):
                (libs / base).write_bytes(z.read(n))
    src = work / "DeltaInteropRead.java"
    src.write_text(READER, encoding="utf-8")
    done = subprocess.run([str(javac), "-d", str(classes), "-cp", str(libs / "*"), str(src)], capture_output=True, text=True)
    assert done.returncode == 0, done.stderr

    def read(table: pathlib.Path):
        cmd = java_cmd(java, [f"-Dloader.path={classes}", "-Dloader.main=DeltaInteropRead", "-jar", jar, str(table)], tmp=work)
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=300)
        assert r.returncode == 0, r.stderr[-4000:]
        files, rows = [], []
        for line in r.stdout.splitlines():
            if line.startswith("{\"file\""):
                j = json.loads(line)
                files.append((j["file"], j["partitionValues"]))
            elif line.startswith("{\"row\""):
                rows.append(json.loads(line)["row"])
        return files, rows
    return read


# ---- checks shared by every case -----------------------------------------------------------------------------------------------------

def log_adds(table: pathlib.Path) -> list[dict]:
    adds, removed = {}, set()
    for f in sorted((table / "_delta_log").glob("*.json")):
        for line in f.read_text(encoding="utf-8").splitlines():
            j = json.loads(line)
            if "add" in j:
                adds[j["add"]["path"]] = j["add"]
            elif "remove" in j:
                removed.add(j["remove"]["path"])
    return [a for p, a in adds.items() if p not in removed]


def check_paths(table: pathlib.Path, hive: bool, empty_folder: bool = False):
    """Every live add.path decodes to a file on disk; its partitionValues are the raw values; with ``hive`` the folders are Spark's."""
    adds = log_adds(table)
    assert adds
    raw = {v for v in VALUES if v} | {None}
    for a in adds:
        rel = urllib.parse.unquote(a["path"])
        assert not urllib.parse.urlsplit(a["path"]).scheme, a["path"]
        assert (table / rel).is_file(), f"add.path {a['path']!r} decodes to {rel!r}, which is not on disk"
        pv = a["partitionValues"]
        assert pv["business_date"] in DATES
        assert norm(pv["PRODUCT_GROUP"]) in raw, pv
        if hive:
            g = pv["PRODUCT_GROUP"]
            folder = "__HIVE_DEFAULT_PARTITION__" if g in (None, "") else spark_escape(g)
            assert rel.split("/")[:2] == [f"business_date={pv['business_date']}", f"PRODUCT_GROUP={folder}"], rel
    groups = {norm(a["partitionValues"]["PRODUCT_GROUP"]) for a in adds}
    assert groups == raw, groups
    # nothing nests or escapes: every data file is exactly <table>/business_date=…/PRODUCT_GROUP=…/<file>, inside the table
    for f in table.parent.rglob("*.parquet"):
        rel = f.relative_to(table).as_posix()                # raises when a file landed outside the table
        if not rel.startswith("_delta_log/"):
            parts = rel.split("/")
            assert len(parts) == 3 and parts[0].startswith("business_date=") and parts[1].startswith("PRODUCT_GROUP="), rel
    folders = {p.name for d in table.glob("business_date=*") for p in d.iterdir()}
    assert len(folders) == len({v for v in VALUES if v and v != SENTINEL}) + 1 + empty_folder, sorted(folders)


def by_id(rows) -> dict:
    return {r["id"]: (str(r["business_date"]), norm(r["PRODUCT_GROUP"])) for r in rows}


def check_java(java_reader, table: pathlib.Path):
    files, rows = java_reader(table)
    assert by_id(rows) == expected_rows()
    adds = {urllib.parse.unquote(a["path"]): a["partitionValues"] for a in log_adds(table)}
    assert len(files) == len(adds)
    for path, pv in files:
        rel = path[len("file:"):] if path.startswith("file:") else path      # Kernel hands a plain path (file:/…), never a URI
        rel = os.path.relpath(rel, table.resolve()).replace(os.sep, "/")
        assert rel in adds, (path, rel)
        assert {k: norm(v) for k, v in pv.items()} == {k: norm(v) for k, v in adds[rel].items()}


def check_python(table: pathlib.Path):
    t = deltalake.DeltaTable(str(table))
    assert by_id(t.to_pyarrow_table().to_pylist()) == expected_rows()
    ds = pads.dataset(str(table), format="parquet", partitioning="hive",
                      exclude_invalid_files=True, ignore_prefixes=["_", "."])
    got = {}
    for r in ds.to_table().to_pylist():
        got[r["id"]] = (str(r["business_date"]), norm(r["PRODUCT_GROUP"]))
    # a folder reader (no log) cannot tell the literal text __HIVE_DEFAULT_PARTITION__ from null: Hive's own ambiguity
    want = {k: (d, None if v == SENTINEL else v) for k, (d, v) in expected_rows().items()}
    assert got == want


# ---- the cases ------------------------------------------------------------------------------------------------------------------------

def test_spark_escaping_matches_the_reference():
    assert spark_escape("Money market") == "Money market"
    assert spark_escape("100%") == "100%25"
    assert spark_escape("a:b/c=d") == "a%3Ab%2Fc%3Dd"
    assert spark_escape("it's \"q\"") == "it%27s %22q%22"
    assert spark_escape("Zürich") == "Zürich"


def test_delta_rs_writes_and_drishtis_java_reader_reads(tmp_path, java_reader):
    ids, days, groups = [], [], []
    for d, day in enumerate(DATES):
        for i, v in enumerate(VALUES):
            ids.append(f"{d}-{i:02d}")
            days.append(datetime.date.fromisoformat(day))
            groups.append(v)
    t = pa.table({"id": ids, "doc": ["{}"] * len(ids), "business_date": pa.array(days, pa.date32()),
                  "PRODUCT_GROUP": pa.array(groups, pa.string())})
    table = tmp_path / "rs"
    deltalake.write_deltalake(str(table), t, partition_by=["business_date", "PRODUCT_GROUP"])
    check_paths(table, hive=False, empty_folder=True)   # delta-rs percent-encodes its folders its own way; add.path still decodes to them
    check_java(java_reader, table)
    check_python(table)


def docs_folder(root: pathlib.Path) -> pathlib.Path:
    src = root / "src"
    src.mkdir()
    for d, day in enumerate(DATES):
        with open(src / f"deal.{day}.jsonl", "w", encoding="utf-8") as fh:
            for i, v in enumerate(VALUES):
                fh.write(json.dumps({"TRADE_ID": f"{d}-{i:02d}", "TRADE_DATE": day, "PRODUCT_GROUP": v,
                                     "NOTIONAL": 1000.0 * i}, ensure_ascii=False) + "\n")
    return src


@pytest.mark.parametrize("engine", ["drishti", "java", "deltalake"])
def test_drishti_writes_and_delta_rs_and_the_java_reader_read(tmp_path, engine, java_reader, monkeypatch):
    if engine == "drishti" and not writers.jar_has_writer(writers.find_jar() or ""):
        pytest.skip("the exec jar has no lake writer")
    monkeypatch.setenv("DRISHTI_BI_STORE", str(tmp_path / "bi"))
    src = docs_folder(tmp_path)
    lake = tmp_path / "lake"
    a = build.build_parser().parse_args([])
    for k, v in dict(src=[str(src)], lake=str(lake), domain="trading", kind="deal", key="TRADE_ID", date="TRADE_DATE",
                     partition_by=["PRODUCT_GROUP"], promote=["NOTIONAL"], engine=engine, no_stamp=True, quiet=True).items():
        setattr(a, k, v)
    said = []
    code, s = build.run(a, lambda *m, **_: said.append(" ".join(map(str, m))))
    assert code == 0, f"{s.get('error')}\n" + "\n".join(said)
    if engine == "java":
        assert s.get("engine") == "java-loader", s.get("engine")
    table = lake / "trading" / "deal"
    check_paths(table, hive=engine != "deltalake")
    check_python(table)
    check_java(java_reader, table)


def test_an_empty_partition_value_is_null_in_the_python_writer():
    assert writers.predicate_for("2026-11-06", ["PRODUCT_GROUP"], ("",)) == "business_date = '2026-11-06' AND PRODUCT_GROUP IS NULL"
    assert writers.predicate_for("2026-11-06", ["PRODUCT_GROUP"], ("it's",)) == \
        "business_date = '2026-11-06' AND PRODUCT_GROUP = 'it''s'"


@pytest.mark.parametrize("name", ["PRODUCT GROUP", "a=b", "a/b", "a,b", "a%b", "tab\there"])
def test_a_partition_column_name_a_folder_would_change_is_refused(tmp_path, name):
    """Kernel and Spark escape a partition VALUE, never the column's NAME: a name Spark refuses without column mapping, or one a folder
    name would change, is refused before anything is written."""
    with pytest.raises(build.BuildError, match="cannot name a Delta partition folder"):
        build.check_partition_names([name])
    build.check_partition_names(["PRODUCT_GROUP", "counterparty.id", "risk-class"])      # plain names pass (a dotted path: a__b)
