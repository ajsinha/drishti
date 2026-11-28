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
"""Every command that adds or removes lake data, run for real (the command line, a subprocess) on Delta, Iceberg and files: each adds rows,
the test reads them back, a command removes them, the test reads back again.

The Java tools (Iceberg, delete, undo, maintain, the parallel loader) run from the server's exec jar: build it first (`./mvnw package`).
Keep this module small: a few rows a table."""
from __future__ import annotations

import glob
import http.server
import json
import os
import subprocess
import sys
import threading
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parent
HOME = TOOLS.parent
sys.path.insert(0, str(TOOLS / "lake"))
sys.path.insert(0, str(TOOLS))
import laketables  # noqa: E402
from javacmd import java_cmd  # noqa: E402

D1, D2, D3 = "2026-10-05", "2026-10-06", "2026-10-07"


def _jar() -> str:
    found = sorted(glob.glob(str(HOME / "kernel" / "drishti-server" / "target" / "drishti-server-*-exec.jar")), key=os.path.getmtime)
    found = found or sorted(glob.glob(str(HOME / "lib" / "drishti-server-*-exec.jar")), key=os.path.getmtime)      # `drishti.py build jar` puts it in lib/
    found = [j for j in found if not j.endswith("-sources.jar")]
    assert found, "the server's exec jar is not built: run ./mvnw package first"
    return found[-1]


@pytest.fixture(scope="module")
def jar() -> str:
    return _jar()


@pytest.fixture
def work(tmp_path):
    env = {**os.environ, "DRISHTI_DATA_DIR": str(tmp_path / "data"), "DRISHTI_BI_STORE": str(tmp_path / "bi"), "PYTHONDONTWRITEBYTECODE": "1",
           "DRISHTI_OFFLINE": "true"}
    for k in ("DRISHTI_DELTA_ROOT", "DRISHTI_ICEBERG_ROOT", "DRISHTI_FILES_ROOT", "DRISHTI_SERVER", "DRISHTI_TOKEN"):
        env.pop(k, None)
    (tmp_path / "data").mkdir()
    return type("Work", (), {"dir": tmp_path, "env": env})


def cli(w, *args, ok: bool = True, timeout: int = 600) -> subprocess.CompletedProcess:
    p = subprocess.run([sys.executable, str(TOOLS / "drishti.py"), *map(str, args)], env=w.env, cwd=str(w.dir), capture_output=True, text=True,
                       timeout=timeout)
    if ok:
        assert p.returncode == 0, f"drishti.py {' '.join(map(str, args))}\n{p.stdout[-2500:]}\n{p.stderr[-2500:]}"
    return p


def jsonl(path: Path, n: int, desk: str = "A", start: int = 0) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps({"id": start + i, "desk": desk, "n": 1.5 * i}) + "\n" for i in range(n)))
    return path


def count(fmt: str, root: Path, domain: str = "dm", kind: str = "tk") -> tuple[int, int]:
    """(rows, business dates) of a table as the tools and the server read it."""
    if fmt == "files":
        days = [d for d in (root / domain).glob("*") if (d / f"{kind}.jsonl").is_file()] if (root / domain).is_dir() else []
        return sum(len((d / f"{kind}.jsonl").read_text().splitlines()) for d in days), len(days)
    t = root / domain / kind
    if not t.exists():
        return 0, 0
    s = laketables.summary(t)
    assert "error" not in s, s
    if fmt == "iceberg":                  # Iceberg's summary names dates from the data folders until an expiry; every date here is one file
        return int(s.get("rows") or 0), int(s.get("files") or 0)
    return int(s.get("rows") or 0), int(s.get("dates") or 0)


def lake_root(w, fmt: str) -> Path:
    return w.dir / f"lake-{fmt}"


def ingest(w, fmt, src, as_of, mode="append", jar_path=None):
    if fmt == "iceberg" and mode == "append":
        mode = "overwrite-dates"                       # Iceberg replaces each business date of the input (Delta's append is Delta's only)
    a = ["data", "ingest", "--from", src, "--domain", "dm", "--kind", "tk", "--as-of", as_of, "--mode", mode, "--store", fmt]
    r = lake_root(w, fmt)
    a += ["--root", r] if fmt == "files" else ["--lake", r]
    if fmt == "iceberg":
        a += ["--jar", jar_path]
    return cli(w, *a)


def delete(w, fmt, *dates, jar_path):
    a = ["lake", "delete", "--lake", lake_root(w, fmt), "--table", "dm/tk", "--format", fmt, "--yes", "--no-signal", "--jar", jar_path]
    for d in dates:
        a += ["--date", d]
    return cli(w, *a)


# ---- data ingest and lake delete, on every store ------------------------------------------------------------------------------------------

@pytest.mark.parametrize("fmt", ["delta", "iceberg", "files"])
def test_ingest_adds_rows_and_delete_removes_them(fmt, work, jar):
    src = jsonl(work.dir / "in" / "tk.jsonl", 6)
    root = lake_root(work, fmt)
    assert count(fmt, root) == (0, 0)
    ingest(work, fmt, src, D1, jar_path=jar)
    assert count(fmt, root) == (6, 1)
    ingest(work, fmt, jsonl(work.dir / "in" / "tk.jsonl", 4, start=100), D2, jar_path=jar)
    assert count(fmt, root) == (10, 2)
    if fmt == "iceberg":
        pass
    ingest(work, fmt, jsonl(work.dir / "in" / "tk.jsonl", 3, start=200), D2, mode="overwrite-dates", jar_path=jar)
    assert count(fmt, root) == (9, 2)                                     # D2 replaced, D1 kept
    if fmt == "iceberg":                                                  # Iceberg's only mode is overwrite-dates (no replace, no append)
        ingest(work, fmt, jsonl(work.dir / "in" / "tk.jsonl", 5, start=400), D1, jar_path=jar)
        assert count(fmt, root) == (8, 2)
        remaining = (3, 1)
    else:
        ingest(work, fmt, jsonl(work.dir / "in" / "tk.jsonl", 2, start=300), D3, mode="replace", jar_path=jar)
        assert count(fmt, root) == (2, 1)                                 # the whole kind rewritten
        ingest(work, fmt, jsonl(work.dir / "in" / "tk.jsonl", 5, start=400), D1, jar_path=jar)
        assert count(fmt, root) == (7, 2)
        remaining = (2, 1)
    p = cli(work, "lake", "delete", "--lake", root, "--table", "dm/tk", "--format", fmt, "--date", D1, "--dry-run", "--no-signal", "--jar", jar)
    assert count(fmt, root) == (7 if fmt != "iceberg" else 8, 2), "a dry run changes nothing"
    delete(work, fmt, D1, jar_path=jar)
    assert count(fmt, root) == remaining
    delete(work, fmt, D3 if fmt != "iceberg" else D2, jar_path=jar)
    assert count(fmt, root)[0] == 0


@pytest.mark.parametrize("fmt", ["delta", "iceberg", "files"])
def test_ingest_watch_once_with_a_ready_file(fmt, work, jar):
    drop = work.dir / "drop"
    f = jsonl(drop / "tk.jsonl", 5)
    (drop / "tk.jsonl.ready").write_text(json.dumps({"business-date": D2}))
    root = lake_root(work, fmt)
    a = ["data", "ingest", "--watch", drop, "--once", "--stable-seconds", "0", "--domain", "dm", "--store", fmt, "--done-dir", work.dir / "done"]
    if fmt == "iceberg":
        a += ["--mode", "overwrite-dates"]
    a += ["--root", root] if fmt == "files" else ["--lake", root]
    if fmt == "iceberg":
        a += ["--jar", jar]
    cli(work, *a)
    assert count(fmt, root) == (5, 1)
    assert not f.exists() and (work.dir / "done" / "tk.jsonl").is_file()
    cli(work, "lake", "delete", "--lake", root, "--table", "dm/tk", "--format", fmt, "--date", D2, "--yes", "--no-signal", "--jar", jar)
    assert count(fmt, root)[0] == 0


# ---- lake build: modes, folders, as-of, the signal, undo, on Delta and Iceberg ---------------------------------------------------------

class Signal(http.server.BaseHTTPRequestHandler):
    seen: list = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        Signal.seen.append((self.path, json.loads(body or b"{}")))
        out = b'{"ok":true,"verified":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


def build(w, fmt, src, jar_path, *extra, json_out=True, kind="tk"):
    a = ["lake", "build", "--from", src, "--kind", kind, "--key", "id", "--domain", "dm", "--lake", lake_root(w, fmt), "--format", fmt,
         "--engine", "java", "--jar", jar_path, "--json", "-q"] + list(extra)
    p = cli(w, *a)
    return json.loads(p.stdout[p.stdout.index("{"):]) if json_out else p


@pytest.mark.parametrize("fmt", ["delta", "iceberg"])
def test_build_modes_folder_as_of_signal_and_undo(fmt, work, jar):
    root = lake_root(work, fmt)
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Signal)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    Signal.seen.clear()
    try:
        s1 = build(work, fmt, jsonl(work.dir / "a" / "tk.jsonl", 6), jar, "--as-of", D1, "--mode", "overwrite-dates",
                   "--signal", f"http://127.0.0.1:{httpd.server_address[1]}", "--user", "e2e", "--pack-name", "core")
        assert count(fmt, root) == (6, 1)
        assert Signal.seen and Signal.seen[0][0].endswith("/loads"), Signal.seen
        # a folder of dated files: one business date each from the name
        folder = work.dir / "folder"
        jsonl(folder / f"tk.{D2}.jsonl", 3, start=10)
        jsonl(folder / f"tk.{D3}.jsonl", 4, start=20)
        s2 = build(work, fmt, folder, jar, "--mode", "overwrite-dates")
        assert count(fmt, root) == (13, 3)
        if fmt == "delta":
            s3 = build(work, fmt, jsonl(work.dir / "a" / "tk.jsonl", 6, start=500), jar, "--as-of", D1, "--mode", "append")
            assert count(fmt, root) == (19, 3)
            # undo the append by its load id: the 6 rows go, the rest stays
            lid = next(iter(s3["loads"]))
            cli(work, "lake", "undo", lid, "--lake", root, "--dry-run", "--jar", jar)
            assert count(fmt, root) == (19, 3)
            cli(work, "lake", "undo", lid, "--lake", root, "--yes", "--jar", jar)
            assert count(fmt, root) == (13, 3)
            # overwrite-partitions: only the (date, partition) present is replaced
            build(work, fmt, jsonl(work.dir / "p" / "tk.jsonl", 2, desk="B", start=700), jar, "--as-of", D1, "--mode", "overwrite-partitions",
                  "--partition-by", "desk", kind="tp")
            assert count(fmt, root, kind="tp")[0] == 2
            build(work, fmt, jsonl(work.dir / "p" / "tk.jsonl", 3, desk="C", start=800), jar, "--as-of", D1, "--mode", "overwrite-partitions",
                  "--partition-by", "desk", kind="tp")
            assert count(fmt, root, kind="tp")[0] == 5
            build(work, fmt, jsonl(work.dir / "p" / "tk.jsonl", 4, desk="B", start=900), jar, "--as-of", D1, "--mode", "overwrite-partitions",
                  "--partition-by", "desk", kind="tp")
            assert count(fmt, root, kind="tp")[0] == 7
            cli(work, "lake", "delete", "--lake", root, "--table", "dm/tp", "--format", fmt, "--from", D1, "--to", D3, "--yes", "--no-signal", "--jar", jar)
            assert count(fmt, root, kind="tp")[0] == 0
        else:
            lid = next(iter(s2["loads"]))
            cli(work, "lake", "undo", lid, "--lake", root, "--yes", "--jar", jar)
            assert count(fmt, root)[0] < 13
        before = count(fmt, root)
        cli(work, "lake", "delete", "--lake", root, "--table", "dm/tk", "--format", fmt, "--from", D1, "--to", D3, "--yes", "--no-signal", "--jar", jar)
        assert before[0] > 0 and count(fmt, root)[0] == 0
    finally:
        httpd.shutdown()
        httpd.server_close()


# ---- lake maintain: compaction, retention (keep-days), vacuum --------------------------------------------------------------------------

@pytest.mark.parametrize("fmt", ["delta", "iceberg"])
def test_maintain_compacts_keeps_days_and_vacuums(fmt, work, jar):
    root = lake_root(work, fmt)
    for d, start in ((D1, 0), (D1, 10), (D2, 20), (D2, 30), (D3, 40)):
        build(work, fmt, jsonl(work.dir / "m" / "tk.jsonl", 3, start=start), jar, "--as-of", d, *(["--mode", "append"] if fmt == "delta" else []))
    rows, dates = count(fmt, root)
    assert dates == 3 and rows in (9, 15)
    conf = work.dir / "maintenance.yaml"
    conf.write_text(f"lakes:\n  - root: {root}\n    format: {fmt}\n    keep-business-days: 1\n    max-drop-share: 0.9\n    compact: true\n    checkpoint: true\n    vacuum-hours: 0\n")
    files_before = laketables.summary(root / "dm" / "tk").get("files")
    p = cli(work, "lake", "maintain", "--config", conf, "--once", "--dry-run", "--as-of", D3, "--tables", f"{root}|dm/tk")
    assert count(fmt, root)[1] == 3, p.stdout
    cli(work, "lake", "maintain", "--config", conf, "--once", "--as-of", D3, "--tables", f"{root}|dm/tk")
    rows2, dates2 = count(fmt, root)
    assert dates2 == 2 and rows2 < rows     # the one rule on both stores: today and the N business days before it
    assert (laketables.summary(root / "dm" / "tk").get("files") or 0) <= (files_before or 99)
    cli(work, "lake", "maintain", "--history")
    cli(work, "lake", "delete", "--lake", root, "--table", "dm/tk", "--format", fmt, "--from", D1, "--to", D3, "--yes", "--no-signal", "--jar", jar)
    assert count(fmt, root)[0] == 0


# ---- lake publish: a table into another folder (the S3 route, locally) ---------------------------------------------------------------

def test_publish_adds_the_dates_to_a_target_and_delete_removes_them(work, jar):
    root = lake_root(work, "delta")
    build(work, "delta", jsonl(work.dir / "a" / "tk.jsonl", 5), jar, "--as-of", D1)
    build(work, "delta", jsonl(work.dir / "a" / "tk.jsonl", 4, start=50), jar, "--as-of", D2)
    target = work.dir / "published" / "dm" / "tk"
    src = root / "dm" / "tk"

    def copy_files():                                           # the files go first (aws s3 sync), then publish commits them
        for f in src.rglob("*.parquet"):
            if "_delta_log" not in f.parts:
                (target / f.relative_to(src)).parent.mkdir(parents=True, exist_ok=True)
                (target / f.relative_to(src)).write_bytes(f.read_bytes())
    copy_files()
    cli(work, "lake", "publish", "--from", src, "--to", target, "--dates", D1)
    assert count("delta", work.dir / "published") == (5, 1)
    cli(work, "lake", "publish", "--from", src, "--to", target)
    assert count("delta", work.dir / "published") == (9, 2)
    cli(work, "lake", "delete", "--lake", work.dir / "published", "--table", "dm/tk", "--format", "delta", "--from", D1, "--to", D3, "--yes",
        "--no-signal", "--jar", jar)
    assert count("delta", work.dir / "published")[0] == 0
    assert count("delta", root) == (9, 2), "the source lake is untouched by the published copy's delete"


# ---- data load: the demo data into every store ---------------------------------------------------------------------------------------

@pytest.mark.parametrize("store", ["delta", "iceberg", "files", "duckdb"])
def test_data_load_puts_the_demo_data_into_the_store(store, work, jar):
    target = work.dir / f"store-{store}"
    a = ["data", "load", "--store", store, "--jar", jar]
    a += [str(target / "demo.duckdb")] if store == "duckdb" else [str(target)]
    cli(work, *a, timeout=1200)
    if store == "duckdb":
        duckdb = pytest.importorskip("duckdb")
        con = duckdb.connect(str(target / "demo.duckdb"), read_only=True)
        tables = [f"{r[0]}.{r[1]}" for r in con.execute("select table_schema, table_name from information_schema.tables").fetchall()]
        assert tables and sum(con.execute(f"select count(*) from {t}").fetchone()[0] for t in tables) > 0
        con.close()
        return
    found = [p for p in (target.glob("*/*") if store != "files" else target.glob("*/*/*.jsonl"))]
    assert found, f"nothing under {target}"
    if store == "files":
        before = len(found)
        for f in found[:3]:
            f.unlink()
        assert len(list(target.glob("*/*/*.jsonl"))) == before - 3
        return
    tables = [t for t in target.glob("*/*") if t.is_dir() and not t.parent.name.startswith("_") and not t.name.startswith("_")]
    assert tables
    t = tables[0]
    s = laketables.summary(t)
    assert (s.get("rows") or 0) > 0, s
    dom = t.parent.name
    cli(work, "lake", "delete", "--lake", target, "--table", f"{dom}/{t.name}", "--format", store, "--from", "1990-01-01", "--to", "2999-12-31", "--yes",
        "--no-signal", "--jar", jar)
    assert (laketables.summary(t).get("rows") or 0) == 0


# ---- lake demo (the parallel loader) -------------------------------------------------------------------------------------------------

def test_lake_demo_builds_the_demo_lake_and_its_dates_delete(work, jar):
    lake = work.dir / "demo-lake"
    cli(work, "lake", "demo", "--lake", lake, "--data-dir", work.dir / "data", "--engine", "java", "--jar", jar, "--only", "core", "--no-signal",
        "--as-of", "data", "-q", timeout=1800)
    tables = [t for t in lake.glob("*/*") if t.is_dir() and not t.parent.name.startswith("_") and not t.name.startswith("_")]
    assert tables, f"no tables under {lake}"
    t = next(x for x in tables if (laketables.summary(x).get("rows") or 0) > 0)
    dom = t.parent.name
    assert (laketables.summary(t).get("rows") or 0) > 0
    cli(work, "lake", "delete", "--lake", lake, "--table", f"{dom}/{t.name}", "--from", "1990-01-01", "--to", "2999-12-31", "--yes", "--no-signal", "--jar", jar)
    assert (laketables.summary(t).get("rows") or 0) == 0


# ---- mr_run: the sample day, loaded, and its delete plan run -------------------------------------------------------------------------

def test_mr_run_sample_day_loads_and_its_delete_plan_removes_it(work, jar):
    from loaders.mrloader import mr_gen
    day = "2026-11-18"
    src = work.dir / "src"
    mr_gen.generate(src, day)
    mr_gen.generate_pvf(src, day)
    lake = work.dir / "e2elake"                                   # a name that is no lake file is a folder under the working folder
    dayfile = TOOLS / "loaders" / "mrloader" / "days" / "sample.yaml"
    base = [sys.executable, str(TOOLS / "loaders" / "mrloader" / "mr_run.py"), "--date", day, "--from", str(src), "--day", str(dayfile), "--lake", "e2elake"]
    env = {**work.env, "DRISHTI_JAR": jar}
    r = subprocess.run(base + ["--out", str(work.dir / "out"), "--load"], env=env, cwd=str(work.dir), capture_output=True, text=True, timeout=1800)
    assert r.returncode == 0, r.stdout[-2500:] + r.stderr[-2500:]
    tables = [t for t in lake.glob("*/*") if t.is_dir() and not t.parent.name.startswith("_") and not t.name.startswith("_")]
    loaded = {t: (laketables.summary(t).get("rows") or 0) for t in tables}
    assert loaded and all(n > 0 for n in loaded.values()), loaded
    plan = subprocess.run(base + ["--delete-plan"], env=env, cwd=str(work.dir), capture_output=True, text=True, timeout=300)
    assert plan.returncode == 0, plan.stderr
    lines = [x.strip() for x in plan.stdout.splitlines() if "lake delete" in x]
    assert lines
    for line in lines:
        words = line.split()
        i = words.index("delete")
        cmd = [x for x in words[i + 1:] if x != "--dry-run"] + ["--yes", "--no-signal", "--jar", jar]
        if "--lake" in cmd:
            cmd[cmd.index("--lake") + 1] = str(lake)
        else:
            cmd += ["--lake", str(lake)]
        cli(work, "lake", "delete", *cmd, ok=True)
    left = {t: (laketables.summary(t).get("rows") or 0) for t in tables}
    assert all(n == 0 for n in left.values()), left


# ---- a running server: the data inbox (dataload ready/run/status), data landed, data get, lake delete with its signal -----------------------

def _free_port() -> int:
    import socket
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def server(work, jar):
    import time
    import urllib.request
    port = _free_port()
    data = work.dir / "srv"
    (data / "tmp").mkdir(parents=True)
    env = {**work.env, "DRISHTI_HOME": str(HOME), "DRISHTI_PORT": str(port), "DRISHTI_DATA_DIR": str(data), "DRISHTI_CONNECTORS_DIR": str(data / "connectors"),
           "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_PACKS": "core,trading", "DRISHTI_DATALOAD_ENABLED": "true",
           "DRISHTI_DATALOAD_STABLE_SECONDS": "1", "DRISHTI_SERVER": f"http://127.0.0.1:{port}", "DRISHTI_USER": "admin"}
    log = open(data / "server.log", "w")
    proc = subprocess.Popen(java_cmd("java", ["-Xmx1g", "-jar", jar], tmp=data / "tmp", cwd=str(work.dir), env=env), env=env, cwd=str(work.dir), stdout=log,
                            stderr=subprocess.STDOUT)
    try:
        for _ in range(120):
            assert proc.poll() is None, f"the server stopped: see {data / 'server.log'}"
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=2).close()
                break
            except OSError:
                time.sleep(1)
        else:
            raise AssertionError("the server did not start")
        work.env = env
        yield work
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.close()


def _rows_of(w, kind: str, day: str) -> int:
    out = cli(w, "data", "get", kind, "--date", day, "--count").stdout
    return int(out.split("rows ")[1].split(" of")[0])


def test_the_inbox_loads_a_file_the_server_reads_it_and_delete_removes_it(server, jar):
    import time
    w = server
    day = "2026-07-08"
    src = HOME / "data" / "testdata" / "core" / "desk" / f"desk.{day}.jsonl.gz"
    assert src.is_file(), src
    f = w.dir / src.name
    f.write_bytes(src.read_bytes())
    cli(w, "dataload", "ready", "--file", f, "--table", "desk", "--check", "--push")
    cli(w, "dataload", "run")
    for _ in range(90):
        status = json.loads(cli(w, "dataload", "status", "--json").stdout)
        if status["failed"] or any(x for c in status.get("cycles", []) for x in c.get("files", [])):
            break
        time.sleep(2)
        cli(w, "dataload", "run")                 # a cycle that ran before the file was stable took nothing: Load now again
    assert not status["failed"], status["failed"]
    files = [x for c in status["cycles"] for x in c.get("files", [])]
    assert files and files[0]["outcome"] == "loaded" and files[0]["rows"] == 10
    assert _rows_of(w, "desk", day) == 10
    assert cli(w, "data", "landed", "--pack", "core", "--kind", "desk", "--date", day, "--rows", "10", "--batch", "e2e").returncode == 0
    assert "desk" in cli(w, "data", "loads", "--pack", "core").stdout
    cli(w, "lake", "delete", "--lake", w.dir / "srv" / "delta" / "demo", "--table", "reference/desk", "--date", day, "--yes", "--jar", jar)       # tells the server (the lake by its folder: DRISHTI_DATA_DIR, a folder outside the checkout, is ignored)
    for _ in range(60):                       # the server has heard of the delete when it no longer finds the date: 0 rows, or no such folder
        p = cli(w, "data", "get", "desk", "--date", day, "--count", ok=False)
        if p.returncode != 0 or int(p.stdout.split("rows ")[1].split(" of")[0]) == 0:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("the server still reads the deleted date: " + p.stdout[-300:])
    assert p.returncode != 0 or _rows_of(w, "desk", day) == 0
