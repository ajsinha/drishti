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
"""Each source system's trades in Delta Lake or Apache Iceberg: ``build_demo_lakes.py --store calypso=iceberg`` (docs/connectors/DEMO_DATA.md,
"Mixed Delta and Iceberg storage").

A system stored in Iceberg is built as Delta first (``lake demo`` or ``lake build``, so its documents and promoted columns are exactly those
of a Delta build), then copied into ``<iceberg root>/trading/trade_<system>`` by Drishti's own Iceberg loader (IcebergLoader, ``engine:
native``: no Hadoop, so it works on Windows), and its Delta table is removed. Its connector file ``trade-<system>.yaml`` is switched with it:
``plugin: iceberg`` reading ``table.trade: trade_<system>``, or back to the pack's Delta template; one connector serves each system, never both.

    python tools/lake/stores.py --from data/delta/trading/trade_calypso --root data/iceberg --table trade_calypso --replace

copies one Delta table into Iceberg (what ``build_demo_lakes.py`` runs, with a heartbeat).
"""
from __future__ import annotations

import argparse
import contextlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import threading
from datetime import datetime

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SYSTEMS = ("endur", "calypso", "murex")
STORES = ("delta", "iceberg")
DOMAIN = "trading"
DELTA_DEFAULT = "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}"
ICEBERG_DEFAULT = "${DRISHTI_ICEBERG_ROOT:${drishti.data.dir:./data}/iceberg}"
LOADER = "com.ash.drishti.plugin.iceberg.IcebergLoader"
WRITTEN_BY = "# Written by tools/build_demo_lakes.py --store"


def parse(values: list[str] | None) -> dict[str, str]:
    """``--store`` values (``calypso=iceberg``, ``all=delta``; ``all`` first, then each system over it) to {system: store}; only the
    systems named. Raises ValueError with the words to show."""
    every, each = None, {}
    for v in values or []:
        system, sep, store = v.strip().lower().partition("=")
        if not sep or store not in STORES or system not in (*SYSTEMS, "all"):
            raise ValueError(f"--store {v}: give <system>=<store>, the system one of {', '.join(SYSTEMS)} or all, the store delta or iceberg")
        if system == "all":
            every = store
        else:
            each[system] = store
    return {s: each.get(s, every) for s in SYSTEMS if each.get(s, every)}


def connectors_dir(given) -> pathlib.Path:
    """The connector files' folder, as lake demo finds it: --connectors-dir, else DRISHTI_CONNECTORS_DIR, else config/connectors."""
    return pathlib.Path(given or os.environ.get("DRISHTI_CONNECTORS_DIR") or (ROOT / "config" / "connectors")).expanduser().resolve()


def current(folder: pathlib.Path) -> dict[str, str]:
    """The store each system's connector file reads now: its plugin (no file: delta, the pack's template)."""
    out = {}
    for s in SYSTEMS:
        f = folder / f"trade-{s}.yaml"
        plugin = "delta"
        if f.is_file():
            m = re.search(r"(?m)^plugin:\s*['\"]?([A-Za-z0-9_-]+)", f.read_text(encoding="utf-8"))
            plugin = m.group(1) if m else "delta"
        out[s] = "iceberg" if plugin == "iceberg" else "delta"
    return out


def iceberg_root(lake: pathlib.Path) -> pathlib.Path:
    """Where the Iceberg tables go: DRISHTI_ICEBERG_ROOT, else the folder beside the Delta lake (data/delta: data/iceberg)."""
    given = os.environ.get("DRISHTI_ICEBERG_ROOT")
    return pathlib.Path(given).expanduser().resolve() if given else lake.parent / "iceberg"


def table(store: str, system: str, lake: pathlib.Path, ice: pathlib.Path) -> pathlib.Path:
    """The folder of a system's trade table in a store."""
    return (ice if store == "iceberg" else lake) / DOMAIN / f"trade_{system}"


# ---- the loader --------------------------------------------------------------------------------------------------------------------------

def loader_command(data: pathlib.Path) -> list[str]:
    """How to start IcebergLoader: DRISHTI_ICEBERG_CLASSPATH (development: ``-cp CP``), else the server's exec jar (DRISHTI_JAR, else the
    newest drishti-server/target/drishti-server-*-exec.jar), whose launcher starts the loader instead of the server (``-Dloader.main``)."""
    for p in (str(HERE), str(ROOT / "tools")):
        if p not in sys.path:
            sys.path.insert(0, p)
    import writers
    from javacmd import java_cmd
    java = writers.find_java()
    if not java:
        raise SystemExit("stores: no java (Java 21 or newer: JAVA_HOME or java on the PATH)")
    heap = "-Xmx" + os.environ.get("DRISHTI_LOADER_HEAP", "2g")
    cp = os.environ.get("DRISHTI_ICEBERG_CLASSPATH")
    if cp:
        args = [heap, "-cp", cp, LOADER]
    else:
        jar = writers.find_jar()
        if not jar:
            raise SystemExit("stores: Iceberg is written by the server's exec jar: build it first (./mvnw -q package -DskipTests; Windows: "
                             ".\\mvnw.cmd -q package -DskipTests) or name it with DRISHTI_JAR")
        args = [heap, f"-Dloader.main={LOADER}", "-jar", jar]
    return java_cmd(java, args, tmp=data / "tmp" / "server")


def rows(delta: pathlib.Path, table_name: str, newest: list):
    """The Delta table's rows as the loader's lines: {domain, kind (the table), id, date, doc, columns (every promoted and partition
    column)}; ``newest`` gets the newest business date."""
    from deltalake import DeltaTable
    ds = DeltaTable(str(delta)).to_pyarrow_dataset()
    names = [n for n in ds.schema.names if n not in ("id", "doc", "business_date")]
    for batch in ds.to_batches(batch_size=2000):
        cols = batch.to_pydict()
        for i, rid in enumerate(cols["id"]):
            day = cols["business_date"][i]
            day = day.isoformat() if hasattr(day, "isoformat") else str(day)
            if not newest or day > newest[0]:
                newest[:] = [day]
            yield json.dumps({"domain": DOMAIN, "kind": table_name, "id": rid, "date": day, "doc": cols["doc"][i],
                              "columns": {n: cols[n][i] for n in names if cols[n][i] is not None}}, ensure_ascii=False,
                             separators=(",", ":")) + "\n"


def copy(delta: pathlib.Path, root: pathlib.Path, table_name: str, data: pathlib.Path, say=print) -> dict:
    """Copies one Delta table into ``<root>/trading/<table_name>`` with IcebergLoader (each business day committed as one overwrite of
    that day); {rows, newest}."""
    spill = data / "tmp" / "iceberg"
    spill.mkdir(parents=True, exist_ok=True)
    cmd = loader_command(data) + ["-", str(root), "--engine", "native", "--spill-dir", str(spill), "--buffer-mb", "512"]
    newest: list = []
    n = 0
    said: list[str] = []
    with subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
                          errors="replace") as proc:
        def echo():
            for text in proc.stdout:                            # the loader's own progress (rows read, days written) shown as it comes
                said.append(text)
                if text.startswith("iceberg:"):
                    say("    " + text.rstrip(), flush=True)
        reader = threading.Thread(target=echo, daemon=True)
        reader.start()
        try:
            for line in rows(delta, table_name, newest):
                proc.stdin.write(line)
                n += 1
                if n % 100_000 == 0:
                    say(f"    {DOMAIN}/{table_name}: {n:,} rows sent to Iceberg", flush=True)
            proc.stdin.close()
        except BrokenPipeError:
            pass
        proc.wait()
        reader.join(timeout=10)
    with_words = [s.rstrip() for s in said if s.strip() and not s.startswith("SLF4J")]
    if proc.returncode:
        raise SystemExit(f"stores: the Iceberg load of {table_name} did not finish: {with_words[-1] if with_words else f'exit {proc.returncode}'}")
    with contextlib.suppress(OSError):
        spill.rmdir()                                           # the loader's run files are gone; the folder too when empty
    return {"rows": n, "newest": newest[0] if newest else None}


# ---- connector files ---------------------------------------------------------------------------------------------------------------------

def _resolve(value: str, data: pathlib.Path, env: str) -> pathlib.Path:
    m = re.fullmatch(r"\$\{" + env + r":(.*)\}", value.strip())
    if m:
        if os.environ.get(env):
            return pathlib.Path(os.environ[env]).resolve()
        value = m.group(1)
    value = value.replace("${drishti.data.dir:./data}", str(data))
    p = pathlib.Path(value)
    return (p if p.is_absolute() else ROOT / p).resolve()


def connector_text(system: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path) -> str:
    """trade-<system>.yaml for its store: the trading pack's template (one source system of the union, table trade_<system>) with
    plugin and root set; ``engine: native`` for Iceberg."""
    import yaml
    pack = yaml.safe_load((ROOT / "config" / "packs" / "trading" / "pack.yaml").read_text(encoding="utf-8"))
    tpl = pack["connectors"][f"trade-{system}"]
    settings = dict(tpl.get("settings") or {})
    if store == "iceberg":
        root = ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice)
        settings = {"root": root, "engine": "native", **{k: v for k, v in settings.items() if k != "root"}}
        where = f"{system.upper()}'s trades in Iceberg ({ice / DOMAIN / f'trade_{system}'})"
    else:
        settings["root"] = DELTA_DEFAULT if lake.resolve() == _resolve(DELTA_DEFAULT, data, "DRISHTI_DELTA_ROOT") else str(lake)
        where = f"{system.upper()}'s trades in Delta ({lake / DOMAIN / f'trade_{system}'})"
    body = {**tpl, "plugin": store, "settings": settings}
    return (f"{WRITTEN_BY} {system}={store}: {where}, from the connector template of pack 'trading'.\n"
            "# Building the lakes with another --store switches it; the text it replaces is kept in <data>/connectors/history/.\n"
            + yaml.safe_dump(body, sort_keys=False))


def switch(folder: pathlib.Path, system: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path, dry: bool) -> str:
    """Writes trade-<system>.yaml for the store when the file reads another store (or is missing and the store is Iceberg); the earlier
    text kept in <data>/connectors/history/. The line that says what was done."""
    f = folder / f"trade-{system}.yaml"
    now = current(folder)[system]
    if now == store and (store == "delta" or f.is_file()):
        return f"trade-{system}: reads {store} already ({f})"
    if dry:
        return f"trade-{system}: would be written to read {store} ({f})"
    if f.is_file():
        hist = data / "connectors" / "history"
        hist.mkdir(parents=True, exist_ok=True)
        (hist / f"trade-{system}.{datetime.now().strftime('%Y%m%dT%H%M%S%f')[:-3]}.yaml").write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    folder.mkdir(parents=True, exist_ok=True)
    f.write_text(connector_text(system, store, lake, ice, data), encoding="utf-8")
    return f"trade-{system}: now reads {store}, was {now} ({f}; a running server restarts this connector by itself)"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Copies one Delta table of the trading domain into Iceberg (IcebergLoader, engine native).")
    ap.add_argument("--from", dest="src", type=pathlib.Path, required=True, help="the Delta table (…/delta/trading/trade_calypso)")
    ap.add_argument("--root", type=pathlib.Path, required=True, help="the Iceberg root (…/iceberg); the table goes to <root>/trading/<table>")
    ap.add_argument("--table", required=True, help="the Iceberg table's name (trade_calypso)")
    ap.add_argument("--data-dir", type=pathlib.Path, help="the data folder for the loader's temporary files (default: the folder holding --root)")
    ap.add_argument("--replace", action="store_true", help="remove the Iceberg table first (else each copied business day replaces that day)")
    a = ap.parse_args(argv)
    src, root = a.src.expanduser().resolve(), a.root.expanduser().resolve()
    data = (a.data_dir or root.parent).expanduser().resolve()
    target = root / DOMAIN / a.table
    if a.replace and target.exists():
        shutil.rmtree(target)
    got = copy(src, root, a.table, data)
    print(f"  {DOMAIN}/{a.table}: {got['rows']:,} rows in Iceberg, newest business date {got['newest']} ({target})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
