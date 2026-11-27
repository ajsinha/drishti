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
"""Each source system's trades in Delta Lake or Apache Iceberg: ``build_demo_lakes.py --store calypso=iceberg`` (docs/guides/BANK_DATA_GUIDE.md section 9,
"Mixed Delta and Iceberg storage").

A system stored in Iceberg is built as Delta first (``lake demo`` or ``lake build``, so its documents and promoted columns are exactly those
of a Delta build), then copied into ``<iceberg root>/trading/trade_<system>`` by Drishti Lake's loader (``DrishtiLakeCli load``, loader rows: tools/lake/loaderows.py), and its Delta table is removed. Its connector file ``trade-<system>.yaml`` is switched with it:
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
if str(ROOT / "tools") not in sys.path:
    sys.path.insert(0, str(ROOT / "tools"))
import resolved as R  # noqa: E402  (configuration: the Java resolver's answer, tools/resolved.py)
from resolved import pack_dir  # noqa: E402  (where a pack is: the Java resolver's answer, tools/resolved.py)

SYSTEMS = ("endur", "calypso", "murex")
STORES = ("delta", "iceberg")
DOMAIN = "trading"
DELTA_DEFAULT = lakeconfig.DEMO_ROOT_REF   # the demo lake: its own folder, <data>/delta/demo (config/lakes/demo.yaml)
ICEBERG_DEFAULT = "${DRISHTI_ICEBERG_ROOT:${lake.data.dir:${drishti.data.dir}}/iceberg}"
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
    """The site's connector files' folder: --connectors-dir, else the resolver's (DRISHTI_CONNECTORS_DIR, else config/connectors)."""
    return pathlib.Path(given).expanduser().resolve() if given else R.current().connectors_dir


def plugin_of(folder: pathlib.Path, name: str) -> str:
    """The plugin connector ``name`` reads with now: its effective definition (template, then the site's override in ``folder``)."""
    e = R.repository(connectors=folder).connectors.find(name)       # the template under the folder's file, as the Java resolver merges them
    return e.plugin if e is not None else "delta"


def current(folder: pathlib.Path) -> dict[str, str]:
    """The store each system's connector reads now (its effective plugin: the template's delta unless the site's override says another)."""
    return {s: "iceberg" if plugin_of(folder, f"trade-{s}") == "iceberg" else "delta" for s in SYSTEMS}


def override_of(name: str, store: str, settings: dict) -> dict | None:
    """The site override that makes connector ``name`` read ``store`` with ``settings``: only the keys that differ from its template
    (``plugin``, each changed setting by its dotted key, ``null`` for a template setting it drops), with ``template: <owner>``; None
    when the template already says it all (no file is needed)."""
    t = R.repository().connectors.templates.get(name)
    if t is None:
        return None
    base = {k: R.text_of(v) for k, v in R.flatten(t.definition.get("settings")).items()}
    want = {k: v for k, v in R.flatten(settings).items()}
    changed = {k: v for k, v in want.items() if R.text_of(v) != base.get(k)}
    changed.update({k: None for k in base if k not in want})
    body: dict = {"template": t.owner}
    if store != str(t.definition.get("plugin") or "delta"):
        body["plugin"] = store
    if changed:
        body["settings"] = changed
    return body if len(body) > 1 else None


def write_override(f: pathlib.Path, body: dict | None, header: str) -> str:
    """Writes the partial override (or removes the one this tool wrote when the template says it all). A site's own file is left alone."""
    import yaml
    if body is None:
        if f.is_file() and f.read_text(encoding="utf-8").startswith(WRITTEN_BY):
            f.unlink()
            return "the template is in force (the override this tool wrote is removed)"
        return "the template is in force" + (f" ({f} is the site's own: left as it is)" if f.is_file() else "")
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(header + yaml.safe_dump(body, sort_keys=False), encoding="utf-8")
    return f"override written ({f}: only the keys that differ from the template)"


def data_of(lake: pathlib.Path) -> pathlib.Path:
    """The data folder a Delta lake sits in: <data>/delta/<lake> (every lake's own folder) gives <data>; a root named delta (the older
    layout, or a DRISHTI_DELTA_ROOT such as /srv/data/delta) gives its parent; anything else, the folder holding it."""
    lake = pathlib.Path(lake)
    if lake.parent.name == "delta" and lake.name != "delta":
        return lake.parent.parent
    return lake.parent


def older_layout(lake: pathlib.Path, lakes_dir: pathlib.Path | None = None) -> list[str]:
    """The demo lake's table folders still directly in <data>/delta (the older layout) when ``lake`` is the demo lake's own folder
    <data>/delta/<name> and does not exist yet: building there now would leave two copies. Empty: nothing in the way."""
    import yaml
    lake = pathlib.Path(lake).resolve()
    placeholder = lake.is_dir() and [c.name for c in lake.iterdir()] == ["README.md"]   # kept in git with only its README.md
    if (lake.exists() and not placeholder) or lake.parent.name != "delta" or lake.name == "delta":
        return []
    f = pathlib.Path(lakes_dir or os.environ.get("LAKE_LAKES_DIR") or (ROOT / "config" / "lakes")) / f"{lake.name}.yaml"
    if not f.is_file():
        return []
    doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
    tops = {(str((v or {}).get("path") or k.replace(".", "/", 1))).split("/")[0] for k, v in (doc.get("tables") or {}).items()}
    return sorted(t for t in tops if (lake.parent / t).is_dir())


def older_layout_words(lake: pathlib.Path, found: list[str]) -> str:
    return (f"the demo lake's tables are still directly in {pathlib.Path(lake).parent} ({', '.join(found[:6])}{' ...' if len(found) > 6 else ''}), "
            f"the older layout: delete them and build again into {lake} (drishti.py lake build), so the lake is not built twice")


def iceberg_root(lake: pathlib.Path) -> pathlib.Path:
    """Where the Iceberg tables go: DRISHTI_ICEBERG_ROOT, else <data>/iceberg beside the lakes' delta/ folder (data/delta/demo: data/iceberg)."""
    given = os.environ.get("DRISHTI_ICEBERG_ROOT")
    return pathlib.Path(given).expanduser().resolve() if given else data_of(lake) / "iceberg"


def table(store: str, system: str, lake: pathlib.Path, ice: pathlib.Path) -> pathlib.Path:
    """The folder of a system's trade table in a store."""
    return (ice if store == "iceberg" else lake) / DOMAIN / f"trade_{system}"


# ---- the loader --------------------------------------------------------------------------------------------------------------------------

def loader_command(data: pathlib.Path, jar: str | None = None, java: str | None = None, *, main: str) -> list[str]:
    """How to start one of Drishti Lake's Java mains (``main``: LakeUndo, LakeRetention, LakeRelocate): LAKE_CLASSPATH (development:
    ``-cp CP``), else the server's exec jar (``jar``, else the newest in <home>/lib), whose
    launcher starts the loader instead of the server (``-Dloader.main``)."""
    for p in (str(HERE), str(ROOT / "tools")):
        if p not in sys.path:
            sys.path.insert(0, p)
    import writers
    from javacmd import java_cmd
    java = writers.find_java(java)
    if not java:
        raise SystemExit("stores: no java (Java 21 or newer: drishti.java.home, DRISHTI_JAVA_HOME, JAVA_HOME or java on the PATH)")
    heap = "-Xmx" + os.environ.get("DRISHTI_LOADER_HEAP", "2g")
    cp = os.environ.get("LAKE_CLASSPATH")
    if cp:
        args = [heap, "-cp", cp, main]
    else:
        jar = writers.find_jar(jar)
        if not jar:
            raise SystemExit("stores: the lake tools run in the server's exec jar: none in <home>/lib: run "
                             "python tools/drishti.py build jar (or pass --jar)")
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
    """Copies one Delta table into ``<root>/trading/<table_name>`` with Drishti Lake's loader (each business day replaced); {rows, newest}."""
    import loaderows
    newest: list = []
    n = 0

    def counted():
        nonlocal n
        for line in rows(delta, table_name, newest, domain):
            n += 1
            if n % 100_000 == 0:
                say(f"    {domain}/{table_name}: {n:,} rows read for Iceberg", flush=True)
            yield line
    work = data / "tmp" / "iceberg"
    try:
        loaderows.load(counted(), root, domain, table_name, work, "iceberg", say=say)
    except loaderows.LoadError as e:
        raise SystemExit(f"stores: the Iceberg load of {table_name} did not finish: {e}") from None
    finally:
        shutil.rmtree(work, ignore_errors=True)
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
    value = value.replace("${lake.data.dir:${drishti.data.dir}}", str(data)).replace("${drishti.data.dir}", str(data))
    p = pathlib.Path(value)
    return (p if p.is_absolute() else ROOT / p).resolve()


ICEBERG_LAKE = "demo-iceberg"


def _pack_manifest(path: pathlib.Path) -> dict:
    """A pack's manifest with the connectors its source modules bring (modules/source-<system>/connectors/delta.yaml): one home."""
    import yaml
    sys.path.insert(0, str(ROOT / "tools"))
    import packlake
    return packlake.with_modules(yaml.safe_load(path.read_text(encoding="utf-8")))


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
                   folder: pathlib.Path | None = None) -> tuple[dict | None, str]:
    """The override of trade-<system> for its store (the source module's template, one source system of the union, table
    trade_<system>; plugin and root set; Iceberg's lake) and its header line; None when the template says it all."""
    pack = _pack_manifest(pack_dir("trading") / "pack.yaml")
    tpl = _template(pack, f"trade-{system}", store, lake, data, ice, folder)
    settings = dict(tpl.get("settings") or {})
    if settings.get("lake") == ICEBERG_LAKE:          # Iceberg: the lake demo-iceberg (its file beside the connector folder)
        where = f"{system.upper()}'s trades in Iceberg ({ice / DOMAIN / f'trade_{system}'}, lake {ICEBERG_LAKE})"
    elif "lake" in settings:                          # the demo lake in Delta: the template as it is (lake: demo)
        where = f"{system.upper()}'s trades in Delta ({lake / DOMAIN / f'trade_{system}'}, config/lakes/demo.yaml)"
    elif store == "iceberg":
        root = ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice)
        settings = {"root": root, **{k: v for k, v in settings.items() if k != "root"}}
        where = f"{system.upper()}'s trades in Iceberg ({ice / DOMAIN / f'trade_{system}'})"
    else:
        settings["root"] = DELTA_DEFAULT if lake.resolve() == _resolve(DELTA_DEFAULT, data, lakeconfig.DEMO_ROOT_ENV) else str(lake)
        where = f"{system.upper()}'s trades in Delta ({lake / DOMAIN / f'trade_{system}'})"
    return (override_of(f"trade-{system}", store, settings),
            f"{WRITTEN_BY} {system}={store}: {where}; every other key is the template's.\n"
            "# Building the lakes with another --store switches it.\n")


def switch(folder: pathlib.Path, system: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path, dry: bool) -> str:
    """Makes trade-<system> read the store: a partial override of its template in the site's folder (only the keys that differ), or
    none when the template says it all. The line that says what was done."""
    f = folder / f"trade-{system}.yaml"
    now = current(folder)[system]
    if now == store and (store == "delta" or f.is_file()):
        return f"trade-{system}: reads {store} already ({f})"
    if dry:
        return f"trade-{system}: would read {store} ({f})"
    body, header = connector_text(system, store, lake, ice, data, folder)
    return f"trade-{system}: now reads {store}, was {now}: {write_override(f, body, header)}; a running server restarts this connector by itself"


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
    """The store the domain's connector reads now (its effective plugin: the pack's template unless the site's override says another)."""
    return "iceberg" if plugin_of(folder, DOMAIN_STORES[domain][0][1]) == "iceberg" else "delta"


def domain_connector_text(domain: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path,
                          pair: tuple[str, str] | None = None, folder: pathlib.Path | None = None) -> tuple[dict | None, str]:
    """The override of one of the domain's connectors for its store (the pack's template, layouts with their vector columns, with plugin
    and root set; Iceberg's lake) and its header line; None when the template says it all. ``pair`` (pack, connector): by
    default the domain's first."""
    pack_name, name = pair or DOMAIN_STORES[domain][0]
    pack = _pack_manifest(pack_dir(pack_name) / "pack.yaml")
    tpl = _template(pack, name, store, lake, data, ice, folder)
    settings = dict(tpl.get("settings") or {})
    if settings.get("lake") == ICEBERG_LAKE:          # Iceberg: the lake demo-iceberg (its file beside the connector folder)
        where = f"every {domain} table in Iceberg ({ice / domain}, lake {ICEBERG_LAKE})"
    elif "lake" in settings:                          # the demo lake in Delta: the template as it is (lake: demo)
        where = f"every {domain} table in Delta ({lake / domain}, config/lakes/demo.yaml)"
    elif store == "iceberg":
        root = ICEBERG_DEFAULT if ice.resolve() == _resolve(ICEBERG_DEFAULT, data, "DRISHTI_ICEBERG_ROOT") else str(ice)
        settings = {"root": root, **{k: v for k, v in settings.items() if k != "root"}}
        where = f"every {domain} table in Iceberg ({ice / domain})"
    else:
        settings["root"] = DELTA_DEFAULT if lake.resolve() == _resolve(DELTA_DEFAULT, data, lakeconfig.DEMO_ROOT_ENV) else str(lake)
        where = f"every {domain} table in Delta ({lake / domain})"
    return (override_of(name, store, settings),
            f"{WRITTEN_BY} {domain}={store}: {where}; every other key is the template of pack '{pack_name}'.\n"
            "# Building the lakes with another --store switches it.\n")


def switch_domain(folder: pathlib.Path, domain: str, store: str, lake: pathlib.Path, ice: pathlib.Path, data: pathlib.Path, dry: bool) -> str:
    """Makes each of the domain's connectors read the store (a partial override each); back to Delta, the overrides this tool wrote go and
    the packs' own templates serve again. The lines that say what was done (one per connector)."""
    return "; ".join(_switch_one(folder, domain, pair, store, lake, ice, data, dry) for pair in DOMAIN_STORES[domain])


def _switch_one(folder: pathlib.Path, domain: str, pair: tuple[str, str], store: str, lake: pathlib.Path, ice: pathlib.Path,
                data: pathlib.Path, dry: bool) -> str:
    f = domain_file(folder, domain, pair[1])
    now = "iceberg" if plugin_of(folder, pair[1]) == "iceberg" else "delta"
    if now == store:
        return f"{f.stem}: reads {store} already ({f})"
    if dry:
        return f"{f.stem}: would read {store} ({f})"
    body, header = domain_connector_text(domain, store, lake, ice, data, pair, folder) if store != "delta" else (None, "")
    return f"{f.stem}: now reads {store}, was {now}: {write_override(f, body, header)}; a running server restarts it by itself"


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
