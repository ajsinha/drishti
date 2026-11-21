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

"""``drishti.py data load --store <store>``: the banking packs' demo data (1,791 sample documents x 10 business days, and with
``--trades N`` a generated trade book of N trades a day for ``--days D`` days) into any of the nine stores, in the layout each
connector reads (docs/guides/DATA_GUIDE.md section 11; the store's plugin README for its layout and options).

    python tools/drishti.py data load --store delta [root] [--trades N --days D]
    python tools/drishti.py data load --store files [root] [--future-days N] [--zone Z]
    python tools/drishti.py data load --store postgres [jdbc-url] [--user U --password P --keep-months N --writers N --batch N]
    python tools/drishti.py data load --store duckdb [database] [--keep-days N --memory-limit M --parsers N --threads N]
    python tools/drishti.py data load --store iceberg [root] [--catalog rest --uri U --warehouse W --credential C --token T --set k=v]
    python tools/drishti.py data load --store mongodb [uri] [database] [--keep-days N | --ttl-days N --doc-format string|bson --batch N --in-flight N]
    python tools/drishti.py data load --store redis [uri] [--ttl-days N --codec C --level N --chunk-rows N --in-flight N --threads N
                                                          --keep-replaced-seconds N --cluster --publish --retrain --replace --merge]
    python tools/drishti.py data load --store aerospike [hosts] [namespace] [--ttl-days N]
    python tools/drishti.py data load --store cassandra [contact-points] [datacenter] [--create --create-keyspace RF --keyspace K --ttl-days N
                                                          --keep-days N --buckets N|KIND=N --in-flight N --batch-rows N --consistency C
                                                          --username U --password P --setting k=v --max-drop-share F --force-drop]

Every store but delta and iceberg also takes the date guard's ``--future-days N``, ``--zone Z`` and (not files, aerospike) ``--max-drop-share F``
and ``--force-drop``. The steps: ``make_data.py --jsonl`` writes the samples as a tar of ``<kind>/<YYYY-MM-DD>/`` folders under
``<data>/build/loader-rows`` (``bulk_trades.py --jsonl`` the book beside it), then the store's loader, a main class of the server's exec jar
(``-Dloader.main``, started through javacmd: heap ``DRISHTI_LOADER_HEAP``, default 4g), loads them; the folder is removed afterwards.
Cassandra loads both tars in one run (each business day written once); the others load the samples, then the book. Delta needs no Java:
``make_data.py --lake --no-load``, ``samplegen/load_rows.py``, then ``bulk_trades.py --root``. Iceberg is written by Drishti Lake's one loader
(``samplegen/load_rows.py --format iceberg``, ``DrishtiLakeCli load`` in the exec jar, one load a table, each business day replaced, both tars in one
run); its options are the loader's settings: ``--catalog rest --uri U --warehouse W --credential C --token T`` and ``--set KEY=VALUE`` (``s3.region=..``). ``--dry-run`` prints the
steps. A relative path means what it means where the command is typed."""
from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import javacmd  # noqa: E402  (the one way a Drishti tool starts Java)

MAKE_DATA = TOOLS / "packgen" / "banking" / "make_data.py"
BULK = TOOLS / "samplegen" / "bulk_trades.py"
LOAD_ROWS = TOOLS / "samplegen" / "load_rows.py"
GUARD = ("--future-days", "--zone")                     # the date guard (LoadGuard): every Java loader
DROP = ("--max-drop-share",)                            # the retention guard's valued option; --force-drop its flag


@dataclass(frozen=True)
class Store:
    """One store's loader: its main class, its positional targets [(name, default)], the options it passes on, whether it loads the
    samples and the book in one run (``together``), the options only the samples' run gets, and the default --days."""
    main: str
    targets: tuple
    valued: tuple = ()
    flags: tuple = ()
    together: bool = False
    sample_args: tuple = ()
    days: int = 3
    paths: bool = False                                  # the targets are file paths (made absolute where typed)


#: Iceberg's options: each a setting of Drishti Lake's loader (``--catalog rest`` is ``catalog=rest``; ``--set KEY=VALUE`` any other)
ICEBERG_SETTINGS = ("--catalog", "--uri", "--warehouse", "--credential", "--token", "--set")

#: the nine stores' differences; a default "<data>/x" is under the data folder, "$VAR|fallback" an environment variable first
STORES: dict[str, Store] = {
    "delta": Store("", (("root", "$DRISHTI_DELTA_ROOT|<data>/delta/demo"),), paths=True),
    "files": Store("com.ash.drishti.plugin.file.JsonlLoader", (("root", "<data>/files"),), GUARD, paths=True),
    "postgres": Store("com.ash.drishti.plugin.jdbc.PostgresLoader", (("jdbc-url", "$DRISHTI_PG_URL|jdbc:postgresql://localhost:5432/drishti"),),
                      ("--user", "--password", "--keep-months", "--writers", "--batch", *GUARD, *DROP), ("--force-drop",),
                      sample_args=("--recreate",)),
    "duckdb": Store("com.ash.drishti.plugin.duckdb.DuckDbLoader", (("database", "$DRISHTI_DUCKDB_PATH|<data>/duckdb/drishti.duckdb"),),
                    ("--keep-days", "--memory-limit", "--parsers", "--threads", *GUARD, *DROP), ("--force-drop",),
                    sample_args=("--recreate",), paths=True),
    "iceberg": Store("", (("root", "<data>/iceberg"),), ICEBERG_SETTINGS, together=True, paths=True),
    "mongodb": Store("com.ash.drishti.plugin.mongodb.MongoLoader", (("uri", "mongodb://localhost:27017"), ("database", "drishti")),
                     ("--keep-days", "--ttl-days", "--doc-format", "--batch", "--in-flight", *GUARD, *DROP), ("--force-drop",)),
    "redis": Store("com.ash.drishti.plugin.redis.RedisLoader", (("uri", "redis://localhost:6379"),),
                   ("--ttl-days", "--codec", "--level", "--chunk-rows", "--in-flight", "--threads", "--keep-replaced-seconds", *GUARD, *DROP),
                   ("--cluster", "--publish", "--retrain", "--replace", "--merge", "--force-drop"), days=1),
    "aerospike": Store("com.ash.drishti.plugin.aerospike.AerospikeLoader", (("hosts", "localhost:3000"), ("namespace", "test")),
                       ("--ttl-days", *GUARD)),
    "cassandra": Store("com.ash.drishti.plugin.cassandra.CassandraLoader", (("contact-points", "localhost:9042"), ("datacenter", "datacenter1")),
                       ("--create-keyspace", "--keyspace", "--ttl-days", "--keep-days", "--buckets", "--in-flight", "--batch-rows", "--consistency",
                        "--username", "--password", "--setting", *GUARD, *DROP), ("--create", "--force-drop"), together=True),
}

URI = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]+://")


class LoadError(RuntimeError):
    """A usage problem (an unknown option, too many targets): said in words, exit 2."""


def _default(spec: str, data: Path, env) -> str:
    """A target's default: ``$VAR|fallback`` takes the variable when set; ``<data>`` is the data folder."""
    if spec.startswith("$"):
        var, _, spec = spec[1:].partition("|")
        if (env.get(var) or "").strip():
            return env[var].strip()
    return spec.replace("<data>", str(data))


def options(store: str, extra: list[str]) -> list[str]:
    """The store's own options from ``extra``, in the order given; an option the store does not take is a :class:`LoadError`."""
    s, out, i = STORES[store], [], 0
    while i < len(extra):
        o = extra[i]
        name, eq, _ = o.partition("=")
        if name in s.valued and eq:
            out.append(o)
            i += 1
        elif o in s.valued:
            if i + 1 >= len(extra):
                raise LoadError(f"{o} needs a value")
            out += [o, extra[i + 1]]
            i += 2
        elif o in s.flags:
            out.append(o)
            i += 1
        else:
            raise LoadError(f"unknown option {o} for --store {store}" + (f" (it takes {', '.join(s.valued + s.flags)})" if s.valued or s.flags
                                                                          else " (it takes the root, --trades and --days only)"))
    return out


def targets(store: str, given: list[str], data: Path, env, cwd: Path) -> list[str]:
    """The positional targets: those given, then the defaults; a typed relative path is made absolute where the command was typed."""
    s = STORES[store]
    if len(given) > len(s.targets):
        raise LoadError(f"--store {store} takes at most {len(s.targets)} target(s) ({' '.join(n for n, _ in s.targets)}), not {len(given)}: "
                        + " ".join(given))
    out = list(given) + [_default(d, data, env) for _, d in s.targets[len(given):]]
    if s.paths:
        out = [t if URI.match(t) or t.startswith("s3a:") else str((cwd / t).resolve()) for t in out]
    return out


def plan(store: str, given: list[str], trades: int | None, days: int | None, extra: list[str], *, data: Path, env, cwd: Path,
         java: str = "java", jar: str = "drishti-server-exec.jar") -> tuple[Path, list[list[str]]]:
    """(the loader-rows folder or None, every command in order) for one load; nothing runs and nothing is made."""
    s = STORES[store]
    opts = options(store, extra)
    tg = targets(store, given, data, env, cwd)
    days = s.days if days is None else days
    py = sys.executable
    if store == "delta":
        root = Path(tg[0])
        build = root.parent / "build"
        steps = [[py, str(MAKE_DATA), "--lake", str(root), "--rows", str(build / "lake-rows"), "--no-load"],
                 [py, str(LOAD_ROWS), "--root", str(root), "--rows", str(build / "lake-rows" / "banking-history.tar"),
                  "--restated", str(build / "lake-rows" / "banking-restated.tar")]]
        if trades:
            steps.append([py, str(BULK), "--root", str(root), "--out", str(build / "bulk"), "--trades", str(trades), "--days", str(days)])
        return None, steps
    rows = data / "build" / "loader-rows"
    samples, book = rows / "banking.tar", rows / "bulk-trades.tar"
    heap = "-Xmx" + (env.get("DRISHTI_LOADER_HEAP") or "4g")

    def loader(src: Path, *args: str) -> list[str]:
        return javacmd.java_cmd(java, [heap, f"-Dloader.main={s.main}", "-jar", jar, str(src), *tg, *args], cwd=ROOT, env=env)

    gen_samples = [py, str(MAKE_DATA), "--jsonl", str(samples)]
    gen_book = [py, str(BULK), "--trades", str(trades), "--days", str(days), "--jsonl", str(book)]
    if store == "iceberg":                               # the samples and the book through Drishti Lake's one loader, one load a table
        words, i = [], 0
        while i < len(opts):
            name, eq, val = opts[i].partition("=")
            if not eq:
                val, i = opts[i + 1], i + 1
            words += ["--iceberg-set", val if name == "--set" else f"{name[2:]}={val}"]
            i += 1
        load = [py, str(LOAD_ROWS), "--format", "iceberg", "--root", tg[0], "--work", str(data / "build" / "iceberg-work"), "--jar", jar,
                "--java", java, "--rows", str(samples), *(["--rows", str(book)] if trades else []), *words]
        return rows, [gen_samples, *([gen_book] if trades else []), load]
    if s.together:                                       # one input: each business day of a table written once, holding both
        return rows, [gen_samples, *([gen_book] if trades else []), loader(rows, *opts)]
    first = list(s.sample_args) + opts
    if store == "redis" and trades and "--replace" not in opts:
        first.append("--merge")                          # the book replaces the trade days; the samples (a subset of it) merge
    steps = [gen_samples, loader(samples, *first)]
    if trades:
        steps += [gen_book, loader(book, *opts)]
    return rows, steps


def run(store: str, given: list[str], trades: int | None, days: int | None, extra: list[str], *, dry_run: bool = False,
        java: str | None = None, jar: str | None = None, say=print) -> int:
    """Runs one load (or prints its steps with ``dry_run``); 0 done, 1 a step failed (its own message above), 2 usage."""
    env = dict(os.environ)
    data = javacmd.launch_roots(env=env)["data"][0]
    env["DRISHTI_DATA_DIR"] = str(data)                  # the generators, the loader and its temp folder: one data folder
    cwd = Path.cwd()
    try:
        options(store, extra)
        targets(store, given, data, env, cwd)
    except LoadError as e:
        say(f"data load: {e}", file=sys.stderr)
        return 2
    if store != "delta":
        try:
            java = javacmd.find_java(java, required=not dry_run) or "java"
            jar = str(javacmd.server_jar(jar))
        except (javacmd.JavaError, javacmd.JarError, javacmd.HomeError) as e:
            if not dry_run:
                say(f"data load {store}: {e}", file=sys.stderr)
                return 1
            jar = jar or str(javacmd.lib_dir() / javacmd.JAR_GLOB)
    rows, steps = plan(store, given, trades, days, extra, data=data, env=env, cwd=cwd, java=java or "java", jar=jar or "")
    if dry_run:
        for st in steps:
            say(shlex.join(st))
        return 0
    if rows:
        shutil.rmtree(rows, ignore_errors=True)
        rows.mkdir(parents=True)
    try:
        for st in steps:
            if subprocess.run(st, cwd=cwd, env=env).returncode != 0:
                say(f"data load {store}: the load did not finish: see the line above", file=sys.stderr)
                return 1
    finally:
        if rows:
            shutil.rmtree(rows, ignore_errors=True)
    return 0
