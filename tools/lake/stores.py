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
"""Each source system's trades in Delta Lake or Apache Iceberg: ``build_demo_lakes.py --store calypso=iceberg`` (docs/guides/DATA_GUIDE.md section 11,
"Mixed Delta and Iceberg storage").

A system stored in Iceberg is built as Delta first (``lake demo`` or ``lake build``, so its documents and promoted columns are exactly those
of a Delta build), then copied into ``<iceberg root>/trading/trade_<system>`` by Drishti's own Iceberg loader (IcebergLoader, ``engine:
native``: no Hadoop, so it works on Windows), and its Delta table is removed. Its connector file ``trade-<system>.yaml`` is switched with it:
``plugin: iceberg`` reading ``table.trade: trade_<system>``, or back to the pack's Delta template; one connector serves each system, never both.

    python tools/lake/stores.py --from data/delta/trading/trade_calypso --root data/iceberg --table trade_calypso --replace

copies one Delta table into Iceberg (what ``build_demo_lakes.py`` runs, with a heartbeat); ``--domain risk`` copies a table of another
domain (``build_demo_lakes.py --store risk=iceberg`` moves every table of the risk domain, P&L vectors included, and switches the
``risk-store`` connector file; Delta stays the default).
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
from datetime import datetime, timezone

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import lakeconfig  # noqa: E402  (where the lakes are: config/lakes/<lake>.yaml)
if str(ROOT / "drishti-console") not in sys.path:
    sys.path.insert(0, str(ROOT / "drishti-console"))
from core.pack_paths import pack_dir  # noqa: E402  (the one rule for where a pack's folder is)

SYSTEMS = ("endur", "calypso", "murex")
STORES = ("delta", "iceberg")
DOMAIN = "trading"
DELTA_DEFAULT = lakeconfig.DEMO_ROOT_REF   # the demo lake: its own folder, <data>/delta/demo (config/lakes/demo.yaml)
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


def data_of(lake: pathlib.Path) -> pathlib.Path:
    """The data folder a Delta lake sits in: <data>/delta/<lake> (every lake's own folder) gives <data>; a root named delta (the older
    layout, or a DRISHTI_DELTA_ROOT such as /srv/data/delta) gives its parent; anything else, the folder holding it."""
    lake = pathlib.Path(lake)
    if lake.parent.name == "delta" and lake.name != "delta":
        return lake.parent.parent
    return lake.parent


def older_layout(lake: pathlib.Path, lakes_dir: pathlib.Path | None = None) -> list[str]:
    """The demo lake's table folders still directly in <data>/delta (the older layout) when ``lake`` is the demo lake's own folder
    <data>/delta/<name> and does not exist yet: building there now would leave two copies. Empty: nothing in the way. The server moves them
    at start, or ``drishti.py lake relocate demo`` (docs/guides/OPERATIONS_GUIDE.md)."""
    import yaml
    lake = pathlib.Path(lake).resolve()
    placeholder = lake.is_dir() and [c.name for c in lake.iterdir()] == ["README.md"]   # kept in git with only its README.md
    if (lake.exists() and not placeholder) or lake.parent.name != "delta" or lake.name == "delta":
        return []
    f = pathlib.Path(lakes_dir or os.environ.get("DRISHTI_LAKES_DIR") or (ROOT / "config" / "lakes")) / f"{lake.name}.yaml"
    if not f.is_file():
        return []
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    tops = {(str((v or {}).get("path") or k.replace(".", "/", 1))).split("/")[0] for k, v in (doc.get("tables") or {}).items()}
    return sorted(t for t in tops if (lake.parent / t).is_dir())


def older_layout_words(lake: pathlib.Path, found: list[str]) -> str:
    return (f"the demo lake's tables are still directly in {pathlib.Path(lake).parent} ({', '.join(found[:6])}{' ...' if len(found) > 6 else ''}), "
            f"the older layout: move them into {lake} first (start the server once, or drishti.py lake relocate {pathlib.Path(lake).name}), "
            "so the lake is not built twice")


def iceberg_root(lake: pathlib.Path) -> pathlib.Path:
    """Where the Iceberg tables go: DRISHTI_ICEBERG_ROOT, else <data>/iceberg beside the lakes' delta/ folder (data/delta/demo: data/iceberg)."""
    given = os.environ.get("DRISHTI_ICEBERG_ROOT")
    return pathlib.Path(given).expanduser().resolve() if given else data_of(lake) / "iceberg"


def table(store: str, system: str, lake: pathlib.Path, ice: pathlib.Path) -> pathlib.Path:
    """The folder of a system's trade table in a store."""
    return (ice if store == "iceberg" else lake) / DOMAIN / f"trade_{system}"


# ---- the loader --------------------------------------------------------------------------------------------------------------------------

def loader_command(data: pathlib.Path, jar: str | None = None, java: str | None = None, main: str = LOADER) -> list[str]:
    """How to start IcebergLoader (or another class of the plugin, ``main``: IcebergMaintenance): DRISHTI_ICEBERG_CLASSPATH (development:
    ``-cp CP``), else the server's exec jar (``jar``, DRISHTI_JAR, else the newest kernel/drishti-server/target/drishti-server-*-exec.jar), whose
    launcher starts the loader instead of the server (``-Dloader.main``)."""
    for p in (str(HERE), str(ROOT / "tools")):
        if p not in sys.path:
            sys.path.insert(0, p)
    import writers
    from javacmd import java_cmd
    java = writers.find_java(java)
    if not java:
        raise SystemExit("stores: no java (Java 21 or newer: JAVA_HOME or java on the PATH)")
    heap = "-Xmx" + os.environ.get("DRISHTI_LOADER_HEAP", "2g")
    cp = os.environ.get("DRISHTI_ICEBERG_CLASSPATH")
    if cp:
        args = [heap, "-cp", cp, main]
    else:
        jar = writers.find_jar(jar)
        if not jar:
            raise SystemExit("stores: Iceberg is written by the server's exec jar: build it first (./mvnw -q package -DskipTests; Windows: "
                             ".\\mvnw.cmd -q package -DskipTests) or name it with DRISHTI_JAR")
        args = [heap, f"-Dloader.main={main}", "-jar", jar]
    return java_cmd(java, args, tmp=data / "tmp" / "server")


def rows(delta: pathlib.Path, table_name: str, newest: list, domain: str = DOMAIN):
    """The Delta table's rows as the loader's lines: {domain, kind (the table), id, date, doc, columns (every promoted and partition
    column, a null kept: a column null on every row, such as POSITION_ID on trade-grain scenario PVs, is still a column of the Iceberg
    table, as it is of the Delta one, so the layout check finds every column the pack declares)}; ``newest`` gets the newest business
    date."""
    if str(HERE) not in sys.path:
        sys.path.insert(0, str(HERE))
    import deltaread                                    # every reader feature honoured (DATA_LAKE_GUIDE.md section 14)
    ds = deltaread.read(delta)
    names = [n for n in ds.schema.names if n not in ("id", "doc", "business_date")]
    for batch in ds.to_batches(max_chunksize=2000):
        cols = batch.to_pydict()
        for i, rid in enumerate(cols["id"]):
            day = cols["business_date"][i]
            day = day.isoformat() if hasattr(day, "isoformat") else str(day)
            if not newest or day > newest[0]:
                newest[:] = [day]
            yield json.dumps({"domain": domain, "kind": table_name, "id": rid, "date": day, "doc": cols["doc"][i],
                              "columns": {n: cols[n][i] for n in names}}, ensure_ascii=False,
                             separators=(",", ":")) + "\n"


def copy(delta: pathlib.Path, root: pathlib.Path, table_name: str, data: pathlib.Path, say=print, domain: str = DOMAIN) -> dict:
    """Copies one Delta table into ``<root>/trading/<table_name>`` with IcebergLoader (each business day committed as one overwrite of
    that day); {rows, newest}."""
    if str(HERE) not in sys.path:
        sys.path.insert(0, str(HERE))
    import feedfiles  # noqa: E402  (tools/lake/feedfiles.py: the rows as a tar of <table>/<YYYY-MM-DD>/ folders)
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
            # the load rules (DATA_LAKE_GUIDE.md): rows of several dates go as a tar of <table>/<YYYY-MM-DD>/ folders, each row
            # in its Delta business date's folder
            out = feedfiles.RowsTarStream(proc.stdin.buffer)
            for line in rows(delta, table_name, newest, domain):
                out.add(table_name, json.loads(line)["date"], line)
                n += 1
                if n % 100_000 == 0:
                    say(f"    {domain}/{table_name}: {n:,} rows sent to Iceberg", flush=True)
            out.close()
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
    record_build(delta, root, table_name, domain, n)
    return {"rows": n, "newest": newest[0] if newest else None}


def record_build(delta: pathlib.Path, root: pathlib.Path, table_name: str, domain: str, rows: int) -> None:
    """The copy's build record under the Iceberg root, as ``lake build --format iceberg`` writes one (tools/lake/ownership.py): the
    table is Drishti's to maintain (Admin -> Lakes, ``lake maintain``), not another system's read-only table. The pack and kind are
    the Delta table's own record's (none: the kind is the table's name)."""
    import ownership
    src = ownership.owner(delta.parent.parent, f"{delta.parent.name}/{delta.name}") or {}
    now = datetime.now(timezone.utc)
    ownership.record(root, {"table": str(root / domain / table_name), "pack": src.get("pack"), "kind": src.get("kind") or table_name,
                            "finishedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"), "run": f"stores-copy-{now.strftime('%Y%m%dT%H%M%S%f')}",
                            "rows": rows})


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


ICEBERG_LAKE = "demo-iceberg"


def _template(pack: dict, name: str, store: str, lake: pathlib.Path, data: pathlib.Path, ice: pathlib.Path | None = None,
              folder: pathlib.Path | None = None) -> dict:
    """A pack's template for the store: as it is (``lake: demo``) for Delta at the demo lake's own root; for Iceberg, the template naming
    the lake ``demo-iceberg`` (its lake file written beside the connector folder, :func:`iceberg_lake`); for Delta in another folder,
    its tables written out from the pack's lake: entries with that root (the older form: the server says so once)."""
    sys.path.insert(0, str(ROOT / "tools"))
    import packlake
    raw = pack["connectors"][name]
    if store == "delta" and lake.resolve() == _resolve(DELTA_DEFAULT, data, lakeconfig.DEMO_ROOT_ENV):
        return raw
    if store == "iceberg" and "lake" in (raw.get("settings") or {}) and ice is not None and folder is not None:
        st = dict(raw["settings"])
        iceberg_lake(folder, pack, raw, ice, data)
        st["lake"] = ICEBERG_LAKE
        st["engine"] = "native"
        return {**raw, "settings": st}
    return packlake.view(pack)["connectors"][name]


def lakes_folder(connectors: pathlib.Path) -> pathlib.Path:
    """The lake folder beside a connector folder (config/connectors -> config/lakes), where the server reads lake files."""
    return connectors.parent / "lakes"


def iceberg_lake(connectors: pathlib.Path, pack: dict, template: dict, ice: pathlib.Path, data: pathlib.Path) -> pathlib.Path:
    """``demo-iceberg.yaml``: the Iceberg store of the demo tables moved by --store, one entry per table the template reads, copied from
    the pack's recommended entries (DATA_LAKE_GUIDE.md section 12; Iceberg keeps a partition field as a promoted column). Entries already
    there are kept as they are: the lake file is the truth once written."""
    import yaml
    sys.path.insert(0, str(ROOT / "tools"))
    import packlake
    f = lakes_folder(connectors) / f"{ICEBERG_LAKE}.yaml"
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) if f.is_file() else None
    if not isinstance(doc, dict):
        doc = {"lake": ICEBERG_LAKE, "format": "iceberg",
               "root": ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice),
               "tables": {}}
    tables = doc.setdefault("tables", {}) or {}
    doc["tables"] = tables
    for kind in template.get("kinds") or []:
        e = packlake.entry_of(pack, template, kind)
        if not e:
            continue
        name = e.pop("name")
        e.pop("domain")
        part = e.pop("partition-by", None) or []
        if part:
            e["promote"] = [p for p in part if p not in (e.get("promote") or [])] + list(e.get("promote") or [])
        tables.setdefault(name, e or None)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(f"# Written by tools/build_demo_lakes.py --store: the demo tables kept in Iceberg (docs/guides/DATA_LAKE_GUIDE.md); edit here.\n"
                 + yaml.safe_dump(doc, sort_keys=False, width=140), encoding="utf-8")
    return f


def connector_text(system: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path,
                   folder: pathlib.Path | None = None) -> str:
    """trade-<system>.yaml for its store: the trading pack's template (one source system of the union, table trade_<system>) with
    plugin and root set; ``engine: native`` for Iceberg."""
    import yaml
    pack = yaml.safe_load((ROOT / "config" / "packs" / "banking" / "trading" / "pack.yaml").read_text(encoding="utf-8"))
    tpl = _template(pack, f"trade-{system}", store, lake, data, ice, folder)
    settings = dict(tpl.get("settings") or {})
    if settings.get("lake") == ICEBERG_LAKE:          # Iceberg: the lake demo-iceberg (its file beside the connector folder)
        where = f"{system.upper()}'s trades in Iceberg ({ice / DOMAIN / f'trade_{system}'}, lake {ICEBERG_LAKE})"
    elif "lake" in settings:                          # the demo lake in Delta: the template as it is (lake: demo)
        where = f"{system.upper()}'s trades in Delta ({lake / DOMAIN / f'trade_{system}'}, config/lakes/demo.yaml)"
    elif store == "iceberg":
        root = ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice)
        settings = {"root": root, "engine": "native", **{k: v for k, v in settings.items() if k != "root"}}
        where = f"{system.upper()}'s trades in Iceberg ({ice / DOMAIN / f'trade_{system}'})"
    else:
        settings["root"] = DELTA_DEFAULT if lake.resolve() == _resolve(DELTA_DEFAULT, data, lakeconfig.DEMO_ROOT_ENV) else str(lake)
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
    f.write_text(connector_text(system, store, lake, ice, data, folder), encoding="utf-8")
    return f"trade-{system}: now reads {store}, was {now} ({f}; a running server restarts this connector by itself)"


# ---- a whole domain in Iceberg (--store risk=iceberg) ------------------------------------------------------------------------------------

#: the domains whose every table may be stored in Iceberg instead of Delta: domain -> the (pack, connector) pairs reading its tables,
#: every one switched together (the risk domain: the market-risk pack's risk-store, and the counterparty-risk pack's exposure-store,
#: which reads its simulation runs and exposure paths); the first is the one whose file says the domain's store
DOMAIN_STORES = {"risk": (("market-risk", "risk-store"), ("counterparty-risk", "exposure-store"))}


def split_domains(values: list[str] | None) -> tuple[list[str], dict[str, str]]:
    """``--store`` values split into the source systems' (for :func:`parse`) and the domains' ({risk: iceberg}). Raises ValueError."""
    systems, domains = [], {}
    for v in values or []:
        name, sep, store = v.strip().lower().partition("=")
        if name in DOMAIN_STORES:
            if not sep or store not in STORES:
                raise ValueError(f"--store {v}: give {name}=delta or {name}=iceberg")
            domains[name] = store
        else:
            systems.append(v)
    return systems, domains


def domain_file(folder: pathlib.Path, domain: str, name: str | None = None) -> pathlib.Path:
    return folder / f"{name or DOMAIN_STORES[domain][0][1]}.yaml"


def current_domain(folder: pathlib.Path, domain: str) -> str:
    """The store the domain's connector file reads now (no file: delta, the pack's template)."""
    f = domain_file(folder, domain)
    if not f.is_file():
        return "delta"
    m = re.search(r"(?m)^plugin:\s*['\"]?([A-Za-z0-9_-]+)", f.read_text(encoding="utf-8"))
    return "iceberg" if m and m.group(1) == "iceberg" else "delta"


def domain_connector_text(domain: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path,
                          pair: tuple[str, str] | None = None, folder: pathlib.Path | None = None) -> str:
    """One of the domain's connector files for its store: the pack's template (kinds, layouts with their vector columns) with plugin and
    root set; ``engine: native`` for Iceberg. ``pair`` (pack, connector): by default the domain's first."""
    import yaml
    pack_name, name = pair or DOMAIN_STORES[domain][0]
    pack = yaml.safe_load((pack_dir(pack_name) / "pack.yaml").read_text(encoding="utf-8"))
    tpl = _template(pack, name, store, lake, data, ice, folder)
    settings = dict(tpl.get("settings") or {})
    if settings.get("lake") == ICEBERG_LAKE:          # Iceberg: the lake demo-iceberg (its file beside the connector folder)
        where = f"every {domain} table in Iceberg ({ice / domain}, lake {ICEBERG_LAKE})"
    elif "lake" in settings:                          # the demo lake in Delta: the template as it is (lake: demo)
        where = f"every {domain} table in Delta ({lake / domain}, config/lakes/demo.yaml)"
    elif store == "iceberg":
        root = ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice)
        settings = {"root": root, "engine": "native", **{k: v for k, v in settings.items() if k != "root"}}
        where = f"every {domain} table in Iceberg ({ice / domain})"
    else:
        settings["root"] = DELTA_DEFAULT if lake.resolve() == _resolve(DELTA_DEFAULT, data, lakeconfig.DEMO_ROOT_ENV) else str(lake)
        where = f"every {domain} table in Delta ({lake / domain})"
    body = {**tpl, "plugin": store, "settings": settings}
    return (f"{WRITTEN_BY} {domain}={store}: {where}, from the connector template of pack '{pack_name}'.\n"
            "# Building the lakes with another --store switches it; the text it replaces is kept in <data>/connectors/history/.\n"
            + yaml.safe_dump(body, sort_keys=False))


def switch_domain(folder: pathlib.Path, domain: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path, dry: bool) -> str:
    """Writes each of the domain's connector files for the store (the earlier text kept in <data>/connectors/history/); back to Delta,
    the files go and the packs' own templates serve again. The lines that say what was done (one per connector)."""
    return "; ".join(_switch_one(folder, domain, pair, store, lake, ice, data, dry) for pair in DOMAIN_STORES[domain])


def _switch_one(folder: pathlib.Path, domain: str, pair: tuple[str, str], store: str, lake: pathlib.Path, ice: pathlib.Path,
                data: pathlib.Path, dry: bool) -> str:
    f = domain_file(folder, domain, pair[1])
    m = re.search(r"(?m)^plugin:\s*['\"]?([A-Za-z0-9_-]+)", f.read_text(encoding="utf-8")) if f.is_file() else None
    now = "iceberg" if m and m.group(1) == "iceberg" else "delta"
    if now == store:
        return f"{f.stem}: reads {store} already ({f})"
    if dry:
        return f"{f.stem}: would be written to read {store} ({f})"
    if f.is_file():
        hist = data / "connectors" / "history"
        hist.mkdir(parents=True, exist_ok=True)
        (hist / f"{f.stem}.{datetime.now().strftime('%Y%m%dT%H%M%S%f')[:-3]}.yaml").write_text(f.read_text(encoding="utf-8"), encoding="utf-8")
    if store == "delta":
        f.unlink()
        return f"{f.stem}: now reads delta, was {now} (the pack's own connector; a running server restarts it by itself)"
    folder.mkdir(parents=True, exist_ok=True)
    f.write_text(domain_connector_text(domain, store, lake, ice, data, pair, folder), encoding="utf-8")
    return f"{f.stem}: now reads {store}, was {now} ({f}; a running server restarts this connector by itself)"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Copies one Delta table of the trading domain into Iceberg (IcebergLoader, engine native).")
    ap.add_argument("--from", dest="src", type=pathlib.Path, required=True, help="the Delta table (…/delta/trading/trade_calypso)")
    ap.add_argument("--root", type=pathlib.Path, required=True, help="the Iceberg root (…/iceberg); the table goes to <root>/trading/<table>")
    ap.add_argument("--table", required=True, help="the Iceberg table's name (trade_calypso)")
    ap.add_argument("--domain", default=DOMAIN, help="the table's domain (default trading; risk for the risk lake)")
    ap.add_argument("--data-dir", type=pathlib.Path, help="the data folder for the loader's temporary files (default: the folder holding --root)")
    ap.add_argument("--replace", action="store_true", help="remove the Iceberg table first (else each copied business day replaces that day)")
    a = ap.parse_args(argv)
    src, root = a.src.expanduser().resolve(), a.root.expanduser().resolve()
    data = (a.data_dir or root.parent).expanduser().resolve()
    target = root / a.domain / a.table
    if a.replace and target.exists():
        shutil.rmtree(target)
    got = copy(src, root, a.table, data, domain=a.domain)
    print(f"  {a.domain}/{a.table}: {got['rows']:,} rows in Iceberg, newest business date {got['newest']} ({target})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
