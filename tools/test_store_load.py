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

"""drishti.py data load (tools/storeload.py): the demo data into any of the nine stores, in Python on every system. The command lines it
builds are checked with javacmd's version probe stubbed (no Java runs); a run is checked with the steps faked. QA2-05: Iceberg and Cassandra
get the samples and the trade book as ONE input (each business day written once); QA2-21: every loader's heap is DRISHTI_LOADER_HEAP and its
input lives under the data folder and is removed after the load; QA3B-12: a failed load ends with the loader's line and one of ours.
The generator (make_data.py) keeps its own checks here too.

    drishti-console/.venv/bin/python -m pytest -q tools/test_store_load.py
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tools") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools"))
import javacmd  # noqa: E402
import storeload as SL  # noqa: E402
from resolved import pack_dirs  # noqa: E402  (where a pack is: the Java resolver's answer, tools/resolved.py)

DATA = Path("/srv/drishti/data")
JAR = "/srv/drishti/lib/drishti-server-9.9.9-exec.jar"


@pytest.fixture(autouse=True)
def no_java_probe(monkeypatch):
    """java_cmd asks the binary's version once; the tests only build command lines."""
    monkeypatch.setattr(javacmd, "java_version", lambda java: 21)


def plan(store, given=(), trades=None, days=None, extra=(), env=None, cwd=Path("/work")):
    return SL.plan(store, list(given), trades, days, list(extra), data=DATA, env=env or {}, cwd=cwd, java="/jdk/bin/java", jar=JAR)


def javas(steps):
    return [s for s in steps if s[0] == "/jdk/bin/java"]


def loader_args(step):
    """What follows ``-jar <jar>``: the loader's own arguments."""
    return step[step.index(JAR) + 1:]


def test_every_loader_is_a_main_class_of_the_server_jar_through_javacmd():
    for store, s in SL.STORES.items():
        if store == "delta":
            continue
        _, steps = plan(store, trades=5, env={"DRISHTI_LOADER_HEAP": "3g"})
        for j in javas(steps):
            assert j[1].startswith("-Djava.io.tmpdir="), store                 # javacmd: never the system /tmp
            assert "-Djdk.nio.maxCachedBufferSize=262144" in j
            assert "-Xmx3g" in j and f"-Dloader.main={s.main}" in j and j[j.index("-jar") + 1] == JAR
        assert not any("-Xmx4g" in j for j in javas(steps)), "DRISHTI_LOADER_HEAP wins"


def test_the_heap_defaults_to_4g():
    _, steps = plan("postgres")
    assert "-Xmx4g" in javas(steps)[0]


@pytest.mark.parametrize("store", ["iceberg", "cassandra"])
def test_iceberg_and_cassandra_load_the_samples_and_the_book_in_one_run(store):
    rows, steps = plan(store, trades=3, days=1)
    assert rows == DATA / "build" / "loader-rows"
    assert [s[1].rsplit("/", 1)[-1] for s in steps[:2]] == ["make_data.py", "bulk_trades.py"]
    assert len(javas(steps)) == 1 and loader_args(javas(steps)[0])[0] == str(rows)
    _, alone = plan(store)
    assert len(alone) == 2 and loader_args(javas(alone)[0])[0] == str(rows)


@pytest.mark.parametrize("store", ["files", "postgres", "duckdb", "mongodb", "redis", "aerospike"])
def test_the_others_load_the_samples_then_the_book(store):
    rows, steps = plan(store, trades=3, days=2)
    assert [s[1].rsplit("/", 1)[-1] if s[0] != "/jdk/bin/java" else "java" for s in steps] == ["make_data.py", "java", "bulk_trades.py", "java"]
    first, second = (loader_args(j) for j in javas(steps))
    assert first[0] == str(rows / "banking.tar") and second[0] == str(rows / "bulk-trades.tar")
    assert steps[0][-2:] == ["--jsonl", str(rows / "banking.tar")]          # the load rules: tars, never --jsonl -
    assert steps[2][2:] == ["--trades", "3", "--days", "2", "--jsonl", str(rows / "bulk-trades.tar")]


def test_postgres_and_duckdb_recreate_with_the_samples_only():
    for store in ("postgres", "duckdb"):
        _, steps = plan(store, trades=3, extra=["--keep-days" if store == "duckdb" else "--keep-months", "4", "--force-drop"])
        first, second = (loader_args(j) for j in javas(steps))
        assert first[2] == "--recreate" and "--recreate" not in second
        assert first[-3:] == second[-3:] and second[-1] == "--force-drop"


def test_redis_merges_the_samples_under_a_book_and_defaults_to_one_day():
    _, steps = plan("redis", trades=3)
    first, second = (loader_args(j) for j in javas(steps))
    assert first[-1] == "--merge" and "--merge" not in second
    assert steps[2][steps[2].index("--days") + 1] == "1"
    _, steps = plan("redis", trades=3, extra=["--replace"])
    assert "--merge" not in loader_args(javas(steps)[0])
    _, steps = plan("redis")
    assert "--merge" not in loader_args(javas(steps)[0])


def test_the_targets_and_their_defaults():
    def targets(store, given=(), env=None):
        _, steps = plan(store, given, env=env)
        return loader_args(javas(steps)[0])[1:1 + len(SL.STORES[store].targets)]
    assert targets("files") == [str(DATA / "files")]
    assert targets("iceberg") == [str(DATA / "iceberg")]
    assert targets("duckdb") == [str(DATA / "duckdb" / "drishti.duckdb")]
    assert targets("duckdb", env={"DRISHTI_DUCKDB_PATH": "/db/x.duckdb"}) == ["/db/x.duckdb"]
    assert targets("postgres") == ["jdbc:postgresql://localhost:5432/drishti"]
    assert targets("postgres", env={"DRISHTI_PG_URL": "jdbc:postgresql://pg:5433/d"}) == ["jdbc:postgresql://pg:5433/d"]
    assert targets("mongodb") == ["mongodb://localhost:27017", "drishti"]
    assert targets("mongodb", ["mongodb://m:1"]) == ["mongodb://m:1", "drishti"]
    assert targets("redis") == ["redis://localhost:6379"]
    assert targets("aerospike") == ["localhost:3000", "test"]
    assert targets("cassandra") == ["localhost:9042", "datacenter1"]


def test_a_typed_relative_path_means_where_the_command_was_typed():
    _, steps = plan("files", ["out/files"], cwd=Path("/home/me"))
    assert loader_args(javas(steps)[0])[1] == "/home/me/out/files"
    _, steps = plan("iceberg", ["s3a://lake/iceberg"])
    assert loader_args(javas(steps)[0])[1] == "s3a://lake/iceberg"
    _, steps = plan("postgres", ["jdbc:postgresql://h/d"])                    # not a path: never touched
    assert loader_args(javas(steps)[0])[1] == "jdbc:postgresql://h/d"


def test_delta_needs_no_java():
    rows, steps = plan("delta", trades=7, days=2, env={"DRISHTI_DELTA_ROOT": "/lakes/demo"})
    assert rows is None and not javas(steps)
    assert steps[0][1:] == [str(SL.MAKE_DATA), "--lake", "/lakes/demo", "--rows", "/lakes/build/lake-rows", "--no-load"]
    assert steps[1][1:] == [str(SL.LOAD_ROWS), "--root", "/lakes/demo", "--rows", "/lakes/build/lake-rows/banking-history.tar",
                            "--restated", "/lakes/build/lake-rows/banking-restated.tar"]
    assert steps[2][1:] == [str(SL.BULK), "--root", "/lakes/demo", "--out", "/lakes/build/bulk", "--trades", "7", "--days", "2"]
    _, steps = plan("delta")
    assert len(steps) == 2 and steps[0][3] == str(DATA / "delta" / "demo")


def test_options_are_checked_against_the_store():
    assert SL.options("cassandra", ["--create", "--setting", "tls.enabled=true", "--buckets=trade=64"]) == \
        ["--create", "--setting", "tls.enabled=true", "--buckets=trade=64"]
    for store, extra, words in (("aerospike", ["--create"], "unknown option --create for --store aerospike"),
                                ("delta", ["--zone", "UTC"], "it takes the root, --trades and --days only"),
                                ("files", ["--zone"], "--zone needs a value")):
        with pytest.raises(SL.LoadError, match=words):
            SL.options(store, extra)
    with pytest.raises(SL.LoadError, match="at most 2 target"):
        plan("mongodb", ["a", "b", "c"])


def test_the_date_and_retention_guards_reach_every_java_loader():
    for store, s in SL.STORES.items():
        if store != "delta":
            assert {"--future-days", "--zone"} <= set(s.valued), store
            if store not in ("files", "aerospike"):
                assert "--max-drop-share" in s.valued and "--force-drop" in s.flags, store


class _Ran:
    def __init__(self, fail_on=None):
        self.calls, self.fail_on = [], fail_on

    def __call__(self, cmd, cwd=None, env=None):
        self.calls.append((cmd, env))
        rows = Path(env["DRISHTI_DATA_DIR"]) / "build" / "loader-rows"
        assert rows.is_dir()                                                     # made before the first step
        code = 1 if self.fail_on and self.fail_on in " ".join(cmd) else 0
        if code:
            print("iceberg: the bucket 'warehouse' does not exist at http://localhost:9000", file=sys.stderr)
        return subprocess.CompletedProcess(cmd, code)


@pytest.fixture
def ran(monkeypatch, tmp_path):
    monkeypatch.setenv("DRISHTI_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setattr(javacmd, "find_java", lambda explicit=None, required=True: "/jdk/bin/java")
    monkeypatch.setattr(javacmd, "server_jar", lambda explicit=None: Path(JAR))
    fake = _Ran()
    monkeypatch.setattr(SL.subprocess, "run", fake)
    return fake, tmp_path / "data"


def test_a_run_uses_one_data_folder_and_removes_its_input(ran):
    fake, data = ran
    assert SL.run("aerospike", [], 3, 1, ["--ttl-days", "30"]) == 0
    assert len(fake.calls) == 4
    assert all(env["DRISHTI_DATA_DIR"] == str(data) for _, env in fake.calls)
    assert not (data / "build" / "loader-rows").exists()
    assert fake.calls[1][0][-2:] == ["--ttl-days", "30"]


def test_a_failed_load_ends_with_the_loaders_line_then_ours(ran, capsys):
    fake, data = ran
    fake.fail_on = "IcebergLoader"
    assert SL.run("iceberg", [], None, None, []) == 1
    err = capsys.readouterr().err.strip().splitlines()
    assert err == ["iceberg: the bucket 'warehouse' does not exist at http://localhost:9000",
                   "data load iceberg: the load did not finish: see the line above"]
    assert not (data / "build" / "loader-rows").exists()


def test_a_usage_error_runs_nothing(ran, capsys):
    fake, _ = ran
    assert SL.run("redis", [], None, None, ["--keyspace", "k"]) == 2
    assert not fake.calls and "unknown option --keyspace for --store redis" in capsys.readouterr().err


def test_no_shell_loaders_remain():
    assert not list((ROOT / "tools").glob("load-*.sh")) and not list((ROOT / "tools").glob("load-*.ps1"))


def test_calc_install_unpacks_only_the_wanted_closure_and_refuses_another_hash(tmp_path, monkeypatch):
    """drishti.py calc install (tools/calcruntime.py): the hash is checked first; the lock file lists only what was unpacked."""
    import io
    import tarfile
    import calcruntime as CR
    lock = {"packages": {"numpy": {"file_name": "numpy.whl", "depends": []}, "pandas": {"file_name": "pandas.whl", "depends": ["numpy"]},
                         "extra": {"file_name": "extra.whl", "depends": []}}}
    tarball = tmp_path / "p.tar.bz2"
    with tarfile.open(tarball, "w:bz2") as tar:
        for name, data in [("pyodide/pyodide-lock.json", json.dumps(lock).encode()), *[(f"pyodide/{n}", b"x") for n in
                           ("numpy.whl", "pandas.whl", "extra.whl", *sorted(CR.CORE))]]:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    dest = tmp_path / "pyodide"
    assert CR.install(dest, tarball, say=lambda *a, **k: None) == 1                       # not the pinned hash: refused
    assert not dest.exists()
    monkeypatch.setattr(CR, "SHA256", CR.sha256(tarball))
    kept = CR.unpack(tarball, tmp_path / "out", ("pandas",))
    assert kept == ["numpy", "pandas"] and not (tmp_path / "out" / "extra.whl").exists()
    assert sorted(json.loads((tmp_path / "out" / "pyodide-lock.json").read_text())["packages"]) == ["numpy", "pandas"]
    said = []
    dest.mkdir()
    (dest / CR.STAMP).write_text(f"version={CR.VERSION}\nsha256={CR.SHA256}\n")
    assert CR.install(dest, tarball, say=lambda *a, **k: said.append(a[0])) == 0 and "already installed" in said[-1]


def test_bicompute_unit_picks_the_newest_release_and_names_a_missing_jar(tmp_path):
    sys.path.insert(0, str(ROOT / "tools" / "dev"))
    import bicompute_unit as BU
    common = tmp_path / "com/ash/drishti/drishti-common"
    for v in ("2.9.8", "2.9.11", "2.9.11.c", "2.9.11.1", "maven-metadata"):
        (common / v).mkdir(parents=True)
    assert BU.newest(common) == "2.9.11.c"
    (common / "2.9.11.c" / "drishti-common-2.9.11.c.jar").write_bytes(b"")
    (common / "2.9.11.c" / "drishti-common-2.9.11.c-sources.jar").write_bytes(b"")
    assert BU.jar(tmp_path, "com/ash/drishti/drishti-common", "2.9.11.c").endswith("drishti-common-2.9.11.c.jar")
    with pytest.raises(BU.UnitError, match="no jar in"):
        BU.classpaths(tmp_path, "2.9.11.c")


class MakeData(unittest.TestCase):
    """The generator every store load starts with."""

    def test_make_data_writes_the_feeds_under_drishti_data_dir(self):
        """QA2-21: make_data.py wrote data/feeds in the repository even with DRISHTI_DATA_DIR set."""
        with tempfile.TemporaryDirectory() as tmp:
            code = ("import sys; sys.path.insert(0, 'tools/packgen/banking'); import make_data as m; "
                    "print(m.data_dir()); print(m.write_feeds(m.data_dir() / 'feeds'))")
            r = subprocess.run(["python3", "-c", code], cwd=ROOT, env={**os.environ, "DRISHTI_DATA_DIR": tmp},
                               capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.split("\n")[0], tmp)
            self.assertTrue((Path(tmp) / "feeds" / "fixing.jsonl").is_file())
        text = (ROOT / "tools" / "packgen" / "banking" / "make_data.py").read_text()
        self.assertNotIn("ROOT / 'data' / 'feeds'", text)

    def test_the_feed_is_jsonl_and_replaces_the_old_csv(self):
        """2.9.4: the sample feed is fixing.jsonl (one series a line, FIX SOFR-HISTORY); the unchanged CSV of earlier releases goes, and
        a loader's run (rewrite=False) leaves an existing feed alone."""
        sys.path.insert(0, str(ROOT / "tools" / "packgen" / "banking"))
        import make_data as m
        with tempfile.TemporaryDirectory() as tmp:
            old = Path(tmp) / "fixing" / "SOFR-HISTORY.csv"
            old.parent.mkdir()
            old.write_text(m.OLD_FEED_CSV, encoding="utf-8")
            f = m.write_feeds(Path(tmp))
            line = json.loads(f.read_text(encoding="utf-8"))
            self.assertEqual((line["id"], len(line["rows"]), line["rows"][0]), ("SOFR-HISTORY", 22, {"date": "2026-09-01", "rate": 3.95, "volume_bn": 1910}))
            self.assertFalse(old.parent.exists())
            f.write_text("{}\n", encoding="utf-8")
            m.write_feeds(Path(tmp), rewrite=False)
            self.assertEqual(f.read_text(encoding="utf-8"), "{}\n")

    def test_rows_runs_never_rewrite_the_samples_tests_or_feed(self):
        """make_data.py --jsonl/--lake only write rows: the generator's own outputs come from a plain run (generation and loading split)."""
        samples = sorted(f for d in pack_dirs().values() for f in d.glob("samples/*.jsonl")) + sorted((ROOT / "modules" / "banking" / "packs" / "trading" / "tests").rglob("*.json*"))
        before = {f: f.stat().st_mtime_ns for f in samples}
        with tempfile.TemporaryDirectory() as tmp:
            r = subprocess.run([sys.executable, "tools/packgen/banking/make_data.py", "--lake", str(Path(tmp) / "delta"), "--no-load", "--days", "2"],
                               cwd=ROOT, env={**os.environ, "DRISHTI_DATA_DIR": tmp}, capture_output=True, text=True, timeout=300)
            self.assertEqual(r.returncode, 0, r.stderr)
            rows = Path(tmp) / "build" / "lake-rows"
            self.assertEqual(sorted(f.name for f in rows.iterdir()), ["banking-history.tar", "banking-restated.tar"])   # the load rules: tars
            import tarfile
            with tarfile.open(rows / "banking-history.tar") as t:
                m = t.getmembers()[0]
                table, day, _ = m.name.split("/")                          # <table>/<YYYY-MM-DD>/rows.jsonl
                first = json.loads(t.extractfile(m).readline())
            self.assertEqual(sorted(first), ["date", "doc", "domain", "id", "kind", "table"])
            self.assertEqual((first["table"], first["date"]), (table, day))
            self.assertTrue((Path(tmp) / "feeds" / "fixing.jsonl").is_file())       # created, as the data folder had none
            self.assertFalse((Path(tmp) / "delta").exists())                         # --no-load: nothing loaded
        self.assertEqual({f: f.stat().st_mtime_ns for f in samples}, before)

