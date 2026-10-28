#!/usr/bin/env python3
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

"""lake build: load a large JSON or JSON Lines export into a Delta lake the Delta connector reads, memory flat, safe to re-run.

    python3 tools/drishti.py lake build --from exports/book-2026-10-01.json --kind deal --key TRADE_ID --date TRADE_DATE \\
        --partition-by PRODUCT_TYPE --lake /var/lib/drishti/delta --domain trading
    python3 tools/drishti.py lake build --from drop/ --pack config/packs/comswap --lake data/delta --as-of 2026-10-01

Input (--from, repeatable): a file or a folder (*.json, *.jsonl, *.ndjson, *.gz; -r for subfolders). A JSON array is streamed
element by element (never read whole); JSON Lines line by line; gzip either way. Each document is one row: id (--key), the whole
document as JSON (nested arrays stay inside it), business_date (--date FIELD, or --as-of DATE for every document), the partition
columns (--partition-by A,B: Hive folders business_date=.../A=.../B=...) and the promoted columns (--promote a,b.c: real columns
readers can filter on without parsing the document) and the vector columns (--vector PNL: a list of numbers as a list<double>
column, the document keeping only a marker; the P&L vectors of VAR_GUIDE.md section 29.3).

Table: <lake>/<domain>/<kind>/. With --pack DIR the domain, the promoted columns (settings.layout.<kind>.columns of its Delta
connector) and the key and date fields (its ingest: block) come from the pack; options given on the command line win.

Modes: overwrite-dates (default) replaces the business dates present in the input and keeps every other date, so re-running a day
replaces that day only; overwrite-partitions replaces only the (date, partition values) present; append adds rows.

Formats (--format): delta (the default, as every connector file of the packs reads) or iceberg: the same staged rows written into an
Apache Iceberg table <lake>/<domain>/<table> by Drishti's Iceberg loader in the server's exec jar (only overwrite-dates; partition columns
are kept as columns, the table is partitioned by business date; tools/lake/icebergwrite.py). Iceberg is chosen by this flag, never by
default. An Iceberg lake may be in object storage (--lake s3://bucket/prefix, or a REST catalog such as AWS Glue's through
--iceberg-set catalog=rest --iceberg-set uri=...): the loader commits there itself (an Iceberg commit is atomic), the rows are staged in a
local scratch folder (TMPDIR) and no run record is kept in the lake (deploy/aws/iceberg/load-day.sh keeps its own).

Engines (--engine): auto (default) uses Drishti's own Java writer in the server's exec jar when one is found (--jar, DRISHTI_JAR,
drishti-server/target), else the Python deltalake package; the run says which ran and why. java (2.9.6): the whole build by the parallel
lake loader in the same jar (tools/lake/javaload.py; Delta or Iceberg, every stage multi-threaded, memory bounded, input from a folder or
s3://, a Delta lake in s3:// too; --loader-set KEY=VALUE for its settings), only when asked: the Python path stays the default. With
--java-above-mb N, auto also picks it for every input JSON Lines of at least N MB in mode overwrite-dates.

For schedulers: exit 0 ok, 2 written with rejects (or the data-load signal failed), 1 failure (nothing was committed for the
table that failed), 3 another run holds the table's lock (or lake maintain the lake's). Each run appends one line to <lake>/_drishti-lake/runs.log and writes a
JSON summary (rows per date and partition, rejects with reasons, timings, engine) to <lake>/_drishti-lake/runs/<run>.json and
last-run.json (or --summary FILE). --signal URL then tells that server the data landed (POST /api/v1/packs/{pack}/loads)."""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import socket
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timezone

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import jsonstream  # noqa: E402
import stage  # noqa: E402
import writers  # noqa: E402

EXIT_OK, EXIT_FAILED, EXIT_PARTIAL, EXIT_LOCKED = 0, 1, 2, 3
MODES = ("overwrite-dates", "overwrite-partitions", "append")
STATE = "_drishti-lake"
RESERVED = {"id", "doc", "business_date"}


class BuildError(RuntimeError):
    pass


class Locked(BuildError):
    pass


# ------------------------------------------------------------------------------------------------ the lock

class LakeLock:
    """Who may write the lake: an OS lock on <lake>/_drishti-lake/lock (released by the OS if the run dies, so a crashed run never
    blocks the next). Without a table it is exclusive (lake maintain, one run at a time over the whole lake). With a table (a build)
    it is shared on the lake and exclusive on locks/<domain>/<table>.lock, so builds of different tables run side by side (one
    system's trades and another's, as they land) and two builds of one table never do; on Windows, whose file locks have no shared
    mode, a build takes the whole lake as before. The file of an exclusive lock says who holds it."""

    def __init__(self, lake: pathlib.Path, table: str | None = None):
        self.table = table if os.name != "nt" else None
        self.lake = lake
        self.table_lock = None
        old = lake / ".drishti-lake"                       # the hidden name of earlier builds: renamed once
        if old.is_dir() and not (lake / STATE).exists():
            old.rename(lake / STATE)
            print(f"renamed {old} to {lake / STATE} (Drishti no longer uses hidden names)", file=sys.stderr)
        self.path = lake / STATE / "lock"
        self.fh = None

    def __enter__(self):
        if self.table:
            self.fh = _flock(self.path, shared=True, what="the lake")
            try:
                self.table_lock = LakeLock._exclusive(self.lake / STATE / "locks" / f"{self.table}.lock", f"table {self.table}")
            except Locked:
                self.fh.close()
                raise
            return self
        self.fh = LakeLock._exclusive(self.path, "the lake")
        return self

    @staticmethod
    def _exclusive(path: pathlib.Path, what: str):
        fh = _flock(path, shared=False, what=what)
        fh.seek(0)
        fh.truncate()
        fh.write(json.dumps({"pid": os.getpid(), "host": socket.gethostname(), "since": now_iso()}))
        fh.flush()
        return fh

    def __exit__(self, *exc):
        for fh in (self.table_lock, self.fh):
            if fh is None:
                continue
            try:
                if fh is self.table_lock or not self.table:
                    fh.seek(0)
                    fh.truncate()
            finally:
                fh.close()           # closing releases the lock
        return False


def _flock(path: pathlib.Path, shared: bool, what: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    fh = open(path, "a+", encoding="utf-8")
    try:
            if os.name == "nt":
                import msvcrt
                fh.seek(0)
                msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(fh.fileno(), (fcntl.LOCK_SH if shared else fcntl.LOCK_EX) | fcntl.LOCK_NB)
                # a POSIX lock on the same byte as well: the parallel loader run on its own (Java's FileLock is a POSIX lock, which a
                # flock does not see) and this build exclude each other (2.9.6)
                fcntl.lockf(fh.fileno(), (fcntl.LOCK_SH if shared else fcntl.LOCK_EX) | fcntl.LOCK_NB, 1, 0)
    except OSError:
        fh.seek(0)
        holder = fh.read().strip() or "another run"
        fh.close()
        raise Locked(f"{what} is locked by {holder}") from None
    return fh


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


# ------------------------------------------------------------------------------------------------ what to build

def pack_defaults(pack: pathlib.Path, kind: str | None, connector: str | None = None) -> dict:
    """{pack, domain, kind, key, date, promote, connector, table} from a pack folder (its pack.yaml), whatever it declares. With
    ``connector`` that Delta connector of the pack (one of several serving a kind, one per source system: trade-endur ...), else the
    first; ``table`` is its ``table.<kind>`` folder when it names one (trade_endur), else the kind."""
    import yaml
    m = yaml.safe_load((pack / "pack.yaml").read_text(encoding="utf-8")) or {}
    out: dict = {"pack": m.get("pack") or pack.name}
    defs = m.get("connectors") if isinstance(m.get("connectors"), dict) else (m.get("connector-templates") or {})
    delta = [(n, c) for n, c in (defs or {}).items() if isinstance(c, dict) and c.get("plugin") == "delta"]
    if connector:
        delta = [(n, c) for n, c in delta if n == connector]
        if not delta:
            raise BuildError(f"the pack has no Delta connector named {connector!r}")
    ing = m.get("ingest") or {}
    kinds = [k for k in ing] or [k for _, c in delta for k in (c.get("kinds") or [])]
    kind = kind or (kinds[0] if len(kinds) == 1 else None)
    out["kind"] = kind
    if delta:
        name, c = delta[0]
        st = c.get("settings") or {}
        out["connector"], out["domain"] = name, st.get("domain") or out["pack"]
        if kind:
            lay = (st.get("layout") or {}).get(kind) if isinstance(st.get("layout"), dict) else None
            flat = st.get(f"layout.{kind}.columns")
            cols = (lay or {}).get("columns") or flat or []
            out["promote"] = [x.strip() for x in (cols.split(",") if isinstance(cols, str) else cols) if str(x).strip()]
            fl = (lay or {}).get("flatten") or {}
            out["flatten"] = {str(n): str(p) for n, p in fl.items()} if isinstance(fl, dict) else {}
            vec = (lay or {}).get("vectors") or st.get(f"layout.{kind}.vectors") or []
            out["vectors"] = [x.strip() for x in (vec.split(",") if isinstance(vec, str) else vec) if str(x).strip()]
            named = (st.get("table") or {}).get(kind) if isinstance(st.get("table"), dict) else st.get(f"table.{kind}")
            out["table"] = named or None
    if kind and isinstance(ing.get(kind), dict):
        out["key"] = ing[kind].get("key")
        out["date"] = ing[kind].get("date")
        fl = ing[kind].get("flatten") or {}
        if isinstance(fl, dict) and fl:
            out["flatten"] = {str(n): str(p) for n, p in fl.items()}
    return out


def plan(a) -> dict:
    """The run's settings: the options, filled from --pack where they are missing."""
    p = {"pack": a.pack_name, "domain": a.domain, "kind": a.kind, "key": a.key, "date": a.date, "promote": split(a.promote),
         "partition_by": split(a.partition_by), "connector": getattr(a, "connector", None), "table": getattr(a, "table", None),
         "vectors": split(getattr(a, "vector", None))}
    if a.pack:
        d = pack_defaults(pathlib.Path(a.pack), a.kind, p["connector"])
        for k in ("pack", "domain", "kind", "key", "date", "connector", "table"):
            p[k] = p[k] or d.get(k)
        if not p["promote"] and a.promote is None:
            p["promote"] = d.get("promote") or []
        p["flatten"] = d.get("flatten") or {}
        p["vectors"] = p.get("vectors") or d.get("vectors") or []
    p["key"] = p["key"] or "id"
    if p["table"] and (not str(p["table"]).replace("_", "").replace("-", "").isalnum()):
        raise BuildError(f"--table is a folder name under the domain, not a path: {p['table']}")
    if not p["kind"]:
        raise BuildError("give --kind (or a --pack with one kind)")
    if not p["domain"]:
        raise BuildError("give --domain (or --pack)")
    if not a.as_of and not p["date"]:
        raise BuildError("give --date FIELD (the business date of each document) or --as-of YYYY-MM-DD (one date for all)")
    for col in p["partition_by"] + p["promote"]:
        if stage.column_name(col) in RESERVED:
            raise BuildError(f"'{col}' is a column the lake keeps itself (id, doc, business_date): choose another name")
    if len(p["partition_by"]) > 4:
        raise BuildError("at most 4 partition columns (each multiplies the folders of a date)")
    if getattr(a, "format", "delta") == "iceberg" and a.mode != "overwrite-dates":
        raise BuildError(f"--format iceberg replaces each business date of the input (overwrite-dates); --mode {a.mode} is Delta's only")
    return p


def split(s) -> list[str]:
    if s is None:
        return []
    items = s if isinstance(s, list) else [s]
    return [x.strip() for i in items for x in str(i).split(",") if x.strip()]


# ------------------------------------------------------------------------------------------------ the run

class Progress:
    """Phases and counts, for the console's progress bar (--progress FILE, rewritten at most every half second) and -v."""

    def __init__(self, file: str | None, verbose: bool):
        self.file, self.verbose, self.state, self.last = file, verbose, {"phase": "starting"}, 0.0

    def update(self, force: bool = False, **kw) -> None:
        self.state.update(kw)
        t = time.monotonic()
        if not force and t - self.last < 0.5:
            return
        self.last = t
        if self.file:
            tmp = self.file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(self.state, fh)
            os.replace(tmp, self.file)
        if self.verbose:
            print("  " + " ".join(f"{k}={v}" for k, v in self.state.items()), file=sys.stderr, flush=True)


def run(a, say=print) -> tuple[int, dict]:
    """The whole build; returns (exit code, summary). Never raises for a data problem: it is in the summary."""
    started = time.monotonic()
    remote = bool(a.lake) and "://" in str(a.lake) and not str(a.lake).startswith("file:")
    lake = pathlib.Path(a.lake) if a.lake and not remote else None
    run_id = datetime.now().strftime("%Y%m%dT%H%M%S") + f"-{os.getpid()}"
    summary: dict = {"run": run_id, "startedAt": now_iso(), "status": "failed", "dryRun": bool(a.dry_run), "mode": a.mode}
    prog = Progress(a.progress, a.verbose)
    try:
        if not a.src:
            raise BuildError("give --from (a file or a folder)")
        if remote and getattr(a, "format", "delta") != "iceberg" and getattr(a, "engine", "auto") != "java":
            raise BuildError("--lake s3://... is for --format iceberg: a Delta table in object storage is built locally and published with lake publish")
        if not lake and not remote and not a.dry_run:
            raise BuildError("give --lake DIR (the Delta root the connector reads)")
        p = plan(a)
        as_of = date.fromisoformat(a.as_of) if a.as_of else None
        summary.update({k: p[k] for k in ("pack", "domain", "kind", "key", "promote")}, partitionBy=p["partition_by"],
                       date=None if as_of else p["date"], asOf=as_of.isoformat() if as_of else None)
        table = str(lake / p["domain"] / (p["table"] or p["kind"])) if lake else None
        if remote:
            table = str(a.lake).rstrip("/") + "/" + p["domain"] + "/" + (p["table"] or p["kind"])
        summary["table"] = table
        if a.dry_run:
            return _go(a, p, as_of, None, table, summary, prog, started, say)
        if remote:                                         # the caller holds the table's lock (load-day.sh: a flock on EFS)
            return _go(a, p, as_of, None, table, summary, prog, started, say, remote_root=str(a.lake).rstrip("/"))
        with LakeLock(lake, table=f"{p['domain']}/{p['table'] or p['kind']}"):
            return _go(a, p, as_of, lake, table, summary, prog, started, say)
    except Locked as e:
        summary.update(status="locked", error=str(e))
        return _finish(a, lake, summary, EXIT_LOCKED, started, say, write_files=False)
    except (BuildError, writers.WriteError, FileNotFoundError, ValueError, OSError) as e:
        summary.update(status="failed", error=f"{e}" if not isinstance(e, FileNotFoundError) else f"{e}: no such file or folder")
        return _finish(a, lake, summary, EXIT_FAILED, started, say)


def _go(a, p, as_of, lake, table, summary, prog, started, say, remote_root: str | None = None) -> tuple[int, dict]:
    staging = None
    if remote_root:
        import tempfile
        staging = pathlib.Path(tempfile.mkdtemp(prefix="drishti-build-")) / summary["run"]
    elif lake:
        table_path = pathlib.Path(summary["table"])
        old = lake / STATE / "staging" / f"{table_path.parent.name}--{table_path.name}"
        if old.is_dir():                                   # left by a run of this table that died: we hold its lock
            shutil.rmtree(old, ignore_errors=True)
        staging = old / summary["run"]
    iceberg = getattr(a, "format", "delta") == "iceberg"
    summary["format"] = "iceberg" if iceberg else "delta"
    engine = getattr(a, "engine", "auto")
    if any(str(s).startswith(("s3://", "s3a://")) for s in a.src) and engine != "java":
        raise BuildError("--from s3://... is read by the parallel loader: add --engine java")
    if engine == "java" or engine == "auto" and not a.dry_run:
        import javaload
        use, why = (True, "--engine java: the parallel lake loader") if engine == "java" else \
            javaload.wanted(a, jsonstream.input_files(a.src, a.recursive), a.jar, a.java)
        if use:
            return _go_java(a, p, as_of, lake, table, summary, prog, started, say, staging, remote_root, iceberg, why)
    st = stage.Stager(staging, p["key"], p["date"], as_of, p["partition_by"], p["promote"], p.get("flatten"), p.get("vectors"))
    try:
        if table and not iceberg:                                          # a table of another partitioning fails before anything is read
            writers.check_layout(writers.Table(table, [stage.column_name(c) for c in p["partition_by"]], {}, a.mode, a.batch_rows, []))
        files = jsonstream.input_files(a.src, a.recursive)
        if not files:
            raise BuildError("no *.json, *.jsonl, *.ndjson or *.gz files in " + ", ".join(map(str, a.src)))
        total_bytes = sum(f.stat().st_size for f in files)
        done_bytes = 0
        t0 = time.monotonic()
        for f in files:
            r = jsonstream.Reader(f)
            for doc, where in r:
                st.add(doc, where)
                if st.stats.read % 2000 == 0:
                    prog.update(phase="reading", file=f.name, bytesRead=done_bytes + r.bytes_read, bytesTotal=total_bytes,
                                documents=st.stats.read, rejected=st.stats.rejected)
            done_bytes += r.size
            st.stats.files.append({"file": str(f), "bytes": r.size})
        st.close()
        prog.update(force=True, phase="read", bytesRead=total_bytes, bytesTotal=total_bytes, documents=st.stats.read, rejected=st.stats.rejected)
        read_s = time.monotonic() - t0
        by_day = st.by_day()
        names = [stage.column_name(c) for c in p["partition_by"]]
        summary["rows"] = {d.isoformat(): sum(x.rows for x in parts) for d, parts in by_day.items()}
        summary["partitions"] = [{"date": d.isoformat(), "values": dict(zip(names, x.values)), "rows": x.rows}
                                 for d, parts in by_day.items() for x in parts]
        summary.update(documents=st.stats.read, accepted=st.stats.accepted, rejected=st.stats.rejected,
                       rejects={"byReason": dict(st.stats.rejects.most_common()), "first": st.stats.reject_samples},
                       duplicates=dict(st.stats.duplicates), files=st.stats.files,
                       sampleId=st.stats.sample[0] if st.stats.sample else None, sampleDate=st.stats.sample[1] if st.stats.sample else None)
        timings = {"readSeconds": round(read_s, 3)}
        summary["timings"] = timings
        if a.dry_run:
            summary["status"] = "dry-run"
            summary["engine"] = None
            code = EXIT_PARTIAL if st.stats.rejected else EXIT_OK
            return _finish(a, None, summary, code, started, say, write_files=False)
        if not by_day:
            raise BuildError("no document could be loaded (every one was rejected: see rejects)")
        engine, why = ("iceberg-native", "--format iceberg: Drishti's Iceberg loader in the server's exec jar") if iceberg \
            else writers.choose(a.engine, a.jar, a.java)
        summary["engine"], summary["engineWhy"] = engine, why
        promoted = st.types()
        avg = st.stats.doc_bytes / max(1, st.stats.accepted)
        batch = min(a.batch_rows, max(100, int(a.batch_mb * 1048576 / max(1.0, avg))))      # rows per batch, bounded by bytes too
        summary["batchRows"], summary["averageDocumentBytes"] = batch, round(avg)
        t = writers.Table(table, names, promoted, a.mode, batch, ["id", "business_date", *names, *promoted])
        t1 = time.monotonic()
        prog.update(force=True, phase="writing", engine=engine, partsDone=0, partsTotal=len(summary["partitions"]))

        def tick(done, total):
            prog.update(phase="writing", partsDone=done, partsTotal=total)
        if iceberg:
            import icebergwrite
            res = icebergwrite.write(remote_root or lake, p["domain"], table.rstrip("/").rsplit("/", 1)[-1], by_day, names, staging, a.jar, a.java,
                                     progress=tick, settings=getattr(a, "iceberg_set", None))
        elif engine == "drishti":
            res = writers.write_drishti(t, by_day, staging, a.jar, a.java, progress=tick)
        else:
            res = writers.write_deltalake(t, by_day, progress=tick)
        timings["writeSeconds"] = round(time.monotonic() - t1, 3)
        summary["commit"] = res
        summary["columns"] = {"partition": names, "promoted": promoted}
        return _written(a, p, lake, table, summary, prog, started, say, st.stats.rejected)
    finally:
        st.close()
        if staging and staging.exists() and not a.keep_staging:
            shutil.rmtree(staging, ignore_errors=True)
            try:
                staging.parent.rmdir()                     # the staging folder only while a run uses it
            except OSError:
                pass
        if remote_root and staging and not a.keep_staging:
            shutil.rmtree(staging.parent, ignore_errors=True)   # the scratch folder of an object-storage build


def _written(a, p, lake, table, summary, prog, started, say, rejected: int) -> tuple[int, dict]:
    """After a commit: the status, the data-load signal, the BI-store stamp, the summary."""
    summary["status"] = "partial" if rejected else "ok"
    code = EXIT_PARTIAL if rejected else EXIT_OK
    if a.signal:
        sig = signal(a, p, summary)
        summary["signal"] = sig
        if any(not s.get("ok") for s in sig):
            code = EXIT_PARTIAL
            summary["status"] = "partial"
    if not a.no_stamp and served_lake(lake) and (not a.signal or any(not s.get("ok") for s in summary.get("signal") or [])):
        summary["stamp"] = str(leave_stamp(lake, table, summary))
    prog.update(force=True, phase="done", status=summary["status"])
    return _finish(a, lake, summary, code, started, say)


def _go_java(a, p, as_of, lake, table, summary, prog, started, say, staging, remote_root, iceberg: bool, why: str) -> tuple[int, dict]:
    """The whole build by the parallel lake loader (tools/lake/javaload.py): this run holds the lock and keeps the run log."""
    import tempfile
    import javaload
    summary["engine"], summary["engineWhy"] = "java-loader", why
    scratch = None
    if staging is None:                                    # a dry run without --lake: a scratch folder for the loader's files
        scratch = pathlib.Path(tempfile.mkdtemp(prefix="drishti-build-"))
        staging = scratch / summary["run"]
    target = str(remote_root or lake or staging / "dry-run-lake")
    prog.update(force=True, phase="loading", engine="java-loader")
    try:
        if a.dry_run:
            a.loader_set = list(getattr(a, "loader_set", None) or []) + ["report.dry-run=true"]
        res = javaload.run(a, p, as_of, target, iceberg, staging, a.progress)
        javaload.merge(summary, res)
        if a.dry_run:
            summary["status"] = "dry-run"
            summary["engine"] = None
            return _finish(a, None, summary, EXIT_PARTIAL if summary.get("rejected") else EXIT_OK, started, say, write_files=False)
        return _written(a, p, lake, table, summary, prog, started, say, int(summary.get("rejected") or 0))
    except javaload.LoaderError as e:
        raise writers.WriteError(str(e)) from None
    finally:
        if staging.exists() and not a.keep_staging:
            shutil.rmtree(staging, ignore_errors=True)
            try:
                staging.parent.rmdir()
            except OSError:
                pass
        if scratch is not None or remote_root:
            shutil.rmtree(scratch or staging.parent, ignore_errors=True)


# ------------------------------------------------------------------------------------------------ the data-load signal

def signal(a, p, summary) -> list[dict]:
    """POST /api/v1/packs/{pack}/loads per business date written: the server refreshes, verifies, checks alerts and tells people.
    The server is the one --signal names (never a default); the token comes from --token-file or DRISHTI_TOKEN (never printed)."""
    if not p.get("pack"):
        return [{"ok": False, "error": "--signal needs the pack's name (--pack DIR or --pack-name NAME)"}]
    token = None
    if a.token_file:
        token = pathlib.Path(a.token_file).read_text(encoding="utf-8").strip()
    token = token or os.environ.get("DRISHTI_TOKEN")
    out = []
    for day, rows in summary["rows"].items():
        body = {"kind": p["kind"], "businessDate": day, "status": "ready", "rows": rows, "rejected": summary["rejected"],
                "batchId": summary["run"], "source": "lake build"}
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        elif a.user or os.environ.get("DRISHTI_USER"):
            headers["X-Drishti-User"] = a.user or os.environ.get("DRISHTI_USER")
        url = a.signal.rstrip("/") + f"/api/v1/packs/{urllib.request.quote(p['pack'], safe='')}/loads"
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:     # noqa: S310 - the operator's own --signal URL
                answer = json.loads(r.read() or b"{}")
                out.append({"date": day, "ok": True, "status": r.status, "verified": answer.get("verified")})
        except urllib.error.HTTPError as e:
            out.append({"date": day, "ok": False, "status": e.code, "error": e.read().decode("utf-8", "replace")[:300]})
        except (urllib.error.URLError, OSError) as e:
            out.append({"date": day, "ok": False, "error": str(getattr(e, "reason", e))})
    return out


def bi_store() -> pathlib.Path:
    """The server's BI store: DRISHTI_BI_STORE, else <data>/bi-store (DRISHTI_DATA_DIR, else the repository's data folder)."""
    data = pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (HERE.parents[1] / "data"))
    return pathlib.Path(os.environ.get("DRISHTI_BI_STORE") or (data / "bi-store"))


def served_lake(lake) -> bool:
    """Whether the lake is the one the server reads: DRISHTI_DELTA_ROOT, else <data>/delta. A scratch lake elsewhere leaves no stamp, so a
    server's next start does not clear its BI store for data it never reads."""
    if lake is None:
        return False
    data = pathlib.Path(os.environ.get("DRISHTI_DATA_DIR") or (HERE.parents[1] / "data"))
    served = pathlib.Path(os.environ.get("DRISHTI_DELTA_ROOT") or (data / "delta"))
    return pathlib.Path(lake).resolve() == served.resolve()


def leave_stamp(lake, table, summary) -> pathlib.Path:
    """No server took the data-load signal: <bi-store>/lake-rebuilt.json tells the server, at its next start, to clear the BI columns and
    rollups it read from the lake (a running server notices a changed Delta date by itself)."""
    stamp = bi_store() / "lake-rebuilt.json"
    stamp.parent.mkdir(parents=True, exist_ok=True)
    stamp.write_text(json.dumps({"at": now_iso(), "by": "drishti.py lake build", "lake": str(lake), "table": str(table),
                                 "dates": sorted(summary.get("rows") or {})}, indent=1) + "\n", encoding="utf-8")
    return stamp


# ------------------------------------------------------------------------------------------------ summary, log, output

def _finish(a, lake, summary, code, started, say, write_files: bool = True) -> tuple[int, dict]:
    summary["exitCode"] = code
    summary["finishedAt"] = now_iso()
    summary.setdefault("timings", {})["totalSeconds"] = round(time.monotonic() - started, 3)
    try:
        import resource
        summary["peakMemoryMb"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)
    except (ImportError, AttributeError):
        pass
    target = pathlib.Path(a.summary) if a.summary else None
    if write_files and lake is not None:
        runs = lake / STATE / "runs"
        runs.mkdir(parents=True, exist_ok=True)
        text = json.dumps(summary, indent=2, default=str)
        (runs / f"{summary['run']}.json").write_text(text, encoding="utf-8")
        last = lake / STATE / f"last-run.json.{os.getpid()}"
        last.write_text(text, encoding="utf-8")
        os.replace(last, lake / STATE / "last-run.json")     # whole, even with builds of other tables finishing at once
        if summary.get("status") in ("ok", "partial") and not summary.get("dryRun"):
            import ownership                               # the table is Drishti's to maintain (lake maintain, Admin -> Lakes)
            ownership.record(lake, summary)
        summary["summaryFile"] = str(target or runs / f"{summary['run']}.json")
    if target:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    line = log_line(summary)
    if write_files and lake is not None:
        with open(lake / STATE / "runs.log", "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    if a.json:
        say(json.dumps(summary, indent=2, default=str))
    else:
        if not a.quiet:
            report(summary, say)
        say(line)
    return code, summary


def log_line(s: dict) -> str:
    parts = [now_iso(), "lake-build", s["status"], f"exit={s.get('exitCode')}", f"run={s['run']}"]
    for k in ("domain", "kind", "engine", "mode"):
        if s.get(k):
            parts.append(f"{k}={s[k]}")
    if "accepted" in s:
        parts += [f"rows={s['accepted']}", f"rejected={s['rejected']}", f"dates={len(s.get('rows', {}))}",
                  f"partitions={len(s.get('partitions', []))}"]
    parts.append(f"seconds={s['timings']['totalSeconds']}")
    if s.get("error"):
        parts.append("error=" + json.dumps(s["error"]))
    return " ".join(parts)


def report(s: dict, say) -> None:
    if s.get("error"):
        say(f"lake build: {s['error']}")
    if s.get("engine"):
        say(f"engine: {s['engine']} ({s.get('engineWhy')})")
    if s.get("partitions"):
        names = list(s["partitions"][0]["values"])
        say(f"{'business date':<15}" + "".join(f"{n:<18}" for n in names) + f"{'rows':>9}" + ("   (dry run)" if s.get("dryRun") else ""))
        for x in s["partitions"]:
            say(f"{x['date']:<15}" + "".join(f"{str(v if v is not None else '(none)'):<18}" for v in x["values"].values()) + f"{x['rows']:>9}")
        written = 0 if s.get("dryRun") or s["status"] == "failed" else s["accepted"]
        say(f"documents read: {s['documents']}, rows written: {written}, rejected: {s['rejected']}" + (" (nothing was committed)" if s["status"] == "failed" else ""))
    for reason, n in (s.get("rejects") or {}).get("byReason", {}).items():
        say(f"  rejected {n}: {reason}")
    for x in (s.get("rejects") or {}).get("first", [])[:5]:
        say(f"    e.g. {x['where']}: {x['reason']}")
    for d, n in (s.get("duplicates") or {}).items():
        say(f"  {n} id(s) appear more than once on {d} (a reader keeps one of them)")
    for x in s.get("signal") or []:
        say(f"  signal {x.get('date', '')}: " + ("sent" + (f", {x['verified']}" if x.get("verified") else "") if x.get("ok") else f"FAILED {x.get('status', '')} {x.get('error', '')}"))
    if s.get("summaryFile"):
        say(f"summary: {s['summaryFile']}")


# ------------------------------------------------------------------------------------------------ the command line

def add_arguments(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
    g = p.add_argument_group("what to read")
    g.add_argument("--from", dest="src", action="append", metavar="PATH", help="a .json (array), .jsonl or .gz file, or a folder of them; repeatable")
    g.add_argument("-r", "--recursive", action="store_true", help="also read the folders' subfolders")
    g = p.add_argument_group("what the rows are")
    g.add_argument("--kind", help="the entity kind (the table's name); default: the pack's only kind")
    g.add_argument("--key", help="the id field, a dotted path (default: the pack's ingest key, else id)")
    g.add_argument("--date", help="the business-date field, a dotted path (ISO date or date-time, or yyyymmdd)")
    g.add_argument("--as-of", metavar="YYYY-MM-DD", help="one business date for every document (a snapshot) instead of --date")
    g.add_argument("--partition-by", action="append", metavar="COL[,COL]", help="partition columns after business_date (dotted paths; up to 4)")
    g.add_argument("--promote", action="append", metavar="COL[,COL]", help="fields to keep as real columns too (default: the pack's layout columns)")
    g.add_argument("--vector", action="append", metavar="COL[,COL]",
                   help="list-of-numbers fields kept as list<double> columns, out of the document (default: the pack's layout vectors)")
    g = p.add_argument_group("where")
    g.add_argument("--lake", metavar="DIR", help="the lake root (the connector's root: DRISHTI_DELTA_ROOT, or DRISHTI_ICEBERG_ROOT with --format iceberg)")
    g.add_argument("--domain", help="the lake domain (default: the pack's Delta connector's domain)")
    g.add_argument("--pack", metavar="DIR", help="a pack folder: domain, kind, key, date and promoted columns from its pack.yaml")
    g.add_argument("--connector", help="which of the pack's Delta connectors the table is for (one per source system: trade-endur); default: the first")
    g.add_argument("--table", help="the table's folder under the domain when it is not the kind (trade_endur); default: the connector's table.<kind>")
    g.add_argument("--pack-name", help="the pack's name for --signal when there is no --pack folder")
    g = p.add_argument_group("how")
    g.add_argument("--format", choices=("delta", "iceberg"), default="delta",
                   help="delta (default) or iceberg: the table format written (Iceberg by Drishti's Iceberg loader in the server's exec jar)")
    g.add_argument("--iceberg-set", action="append", metavar="KEY=VALUE",
                   help="--format iceberg: a setting for the Iceberg loader (s3.region=eu-west-2, catalog=rest, uri=..., warehouse=..., "
                        "catalog.rest.signing-name=glue); repeatable")
    g.add_argument("--engine", choices=("auto", "drishti", "deltalake", "java"), default="auto",
                   help="auto (default): Drishti's Java writer when the server jar is found, else deltalake; java: the parallel lake loader "
                        "(Delta or Iceberg, multi-threaded, memory bounded; only when asked: the Python path is the default)")
    g.add_argument("--java-above-mb", type=int, default=None,
                   help="--engine auto: JSON Lines inputs of at least this many MB go to the parallel loader (not set: never; the Python path is "
                        "the default, --engine java asks for the loader)")
    g.add_argument("--loader-set", action="append", metavar="KEY=VALUE",
                   help="--engine java: a setting of the parallel loader (memory.budget-mb=4096, memory.spill-dir=/mnt/ebs, parallel.writers=16, "
                        "target.layout.sort-by=[LEGAL_ENTITY,DESK,id]; docs/guides/TOOLS.md section 4.13); repeatable")
    g.add_argument("--mode", choices=MODES, default="overwrite-dates", help="overwrite-dates (default), overwrite-partitions or append")
    g.add_argument("--batch-rows", type=int, default=50_000, help="most rows per batch written (default 50,000; also the most rows sorted at once)")
    g.add_argument("--batch-mb", type=int, default=128, help="most MB of documents per batch (default 128): large documents make smaller batches, so memory stays flat")
    g.add_argument("--jar", help="the drishti-server exec jar (default: DRISHTI_JAR, then drishti-server/target)")
    g.add_argument("--java", help="the java binary (default: JAVA_HOME, then java on the PATH)")
    g.add_argument("--dry-run", action="store_true", help="read and count, write nothing")
    g.add_argument("--no-stamp", action="store_true", help="without --signal, leave no <bi-store>/lake-rebuilt.json for the server's next start")
    g.add_argument("--keep-staging", action="store_true", help="keep the staged rows under <lake>/_drishti-lake/staging (to debug)")
    g = p.add_argument_group("afterwards")
    g.add_argument("--signal", metavar="URL", help="tell this server the data landed (POST /api/v1/packs/{pack}/loads); needs a token with loads:write")
    g.add_argument("--token-file", help="the API token for --signal (default: DRISHTI_TOKEN); never printed")
    g.add_argument("--user", help="the user name to send to a server with sign-in off (default: DRISHTI_USER)")
    g = p.add_argument_group("output")
    g.add_argument("--summary", metavar="FILE", help="also write the JSON summary here")
    g.add_argument("--progress", metavar="FILE", help="keep the progress (phase, bytes, documents) in this JSON file while running")
    g.add_argument("--json", action="store_true", help="print the summary as JSON")
    g.add_argument("-q", "--quiet", action="store_true", help="print only the one log line")
    g.add_argument("-v", "--verbose", action="store_true", help="progress lines on standard error")
    return p


def build_parser(add_help: bool = True) -> argparse.ArgumentParser:
    return add_arguments(argparse.ArgumentParser(prog="lake build", description=__doc__.split("\n\n")[0], add_help=add_help,
                                                 epilog=__doc__.split("\n\n", 1)[1], formatter_class=argparse.RawDescriptionHelpFormatter))


def main(argv=None) -> int:
    a = build_parser().parse_args(argv)
    code, _ = run(a)
    return code


if __name__ == "__main__":
    sys.exit(main())
