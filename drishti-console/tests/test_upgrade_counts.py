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

"""The upgrade from the last release keeps every record (docs/architecture/pack-resolution-inventory.md 8.3 and 8.4): one demo lake, the demo
off, this release started twice: on an empty site (fresh) and on what a site of v2.9.11.i_2_3 holds (its generated connector files with their
markers, tools/upgrade_fixture.py, built from the tag's own files). Every kind the bindings serve on the fresh server has the same record count
on the upgraded one, the trades of each source system among them, and no site file is changed by the start. The count table (kind, fresh,
upgraded) is written to $DRISHTI_COUNT_TABLE when set."""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import pytest

from wb_live import _stack

ROOT = Path(__file__).resolve().parents[2]
FROM = "v2.9.11.i_2_3"
ONLY = "trading,core,market-data,counterparty-risk,market-risk"   # the demo packs whose connectors the tag generated: trades per system, VaR inputs
#: every trade of three systems and the VaR inputs on one server: past the default heap (OutOfMemoryError reading the trades)
HEAP = "3072m"
#: the VaR inputs a module computes (positions from the trades, the seeded system baskets): counted although no lake table holds them
VAR_COMPUTED = ("position", "system-basket", "pnl-vector")
SYSTEMS = ("ENDUR", "CALYPSO", "MUREX", "WSS", "SWAPONE", "ICI")


def _get(base: str, path: str):
    with urllib.request.urlopen(base + path, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


#: the VaR-computed kinds the demo always holds: asked right after the start, they answer rows or say they cannot yet, never a silent 0
VAR_READY = ("position", "system-basket")


def _var_count(server: str, kind: str) -> int | None:
    """A VaR-computed kind's count, asked as soon as the server is up: while the engine's inputs load (or busy protection refuses the
    load) the answer is a refusal with its reason, asked again for up to three minutes; an answer of 0 rows is never a placeholder."""
    deadline = time.monotonic() + 180
    while True:
        try:
            c = _get(server, f"/api/v1/data/{kind}/count")
            n = c.get("total", c.get("rows"))
            assert n, f"{kind}: {n} rows answered while the demo holds some: an empty answer that looks true ({c})"
            return n
        except urllib.error.HTTPError as e:
            if time.monotonic() > deadline:
                raise AssertionError(f"{kind}: still refused after three minutes: {e.code} {e.read()[:300]!r}") from e
            time.sleep(5)


def _counts(server: str) -> dict[str, object]:
    """Every kind of the bindings but the computed ones, and its record count on the newest date (None: the Data view does not open it).
    With the demo off a kind the demo lake holds says UNBOUND in the bindings and is still read: it is counted."""
    out: dict[str, object] = {}
    for row in _get(server, "/api/v1/admin/bindings").get("kinds", []):
        if row.get("state") == "COMPUTED" and row["kind"] not in VAR_COMPUTED:
            continue
        if row["kind"] in VAR_READY:
            out[row["kind"]] = _var_count(server, row["kind"])
            continue
        try:
            c = _get(server, f"/api/v1/data/{row['kind']}/count")
            out[row["kind"]] = c.get("total", c.get("rows"))
        except Exception:                              # noqa: BLE001  (a kind the Data view does not serve: listed without a count)
            out[row["kind"]] = None
    for system in SYSTEMS:                             # the trades of each source system: the regression of 2026-11-14 lost them all
        q = urllib.parse.quote(f"SOURCE_SYSTEM = {system}")
        try:
            c = _get(server, f"/api/v1/data/trade/count?where={q}")
            out[f"trade[{system}]"] = c.get("rows", c.get("total"))
        except Exception:                              # noqa: BLE001
            out[f"trade[{system}]"] = None
    return out


def rows_newest(folder: Path) -> int | None:
    """A Delta table's rows on its newest business date, from its log's add actions (no data file read); None when there is no table."""
    if not (folder / "_delta_log").is_dir():
        return None
    from deltalake import DeltaTable
    import pyarrow as pa
    acts = pa.table(DeltaTable(str(folder)).get_add_actions(flatten=True)).to_pydict()
    n = acts.get("num_records") or []
    dates = next((v for k, v in acts.items() if k.lower() == "partition.business_date"), None)
    if dates is None:                                  # not partitioned by date: every row
        return int(sum(x or 0 for x in n))
    newest = max(d for d in dates if d is not None) if any(d is not None for d in dates) else None
    return int(sum(x or 0 for x, d in zip(n, dates) if d == newest))


def _lake_entries() -> dict[str, dict]:
    import yaml
    doc = yaml.safe_load((ROOT / "config" / "lakes" / "demo.yaml").read_text(encoding="utf-8")) or {}
    return {k: (v or {}) for k, v in (doc.get("tables") or {}).items()}


def _tables_of(kind: str, settings: dict, entries: dict[str, dict]) -> list[str]:
    """The demo lake's tables a connector of the release reads for ``kind``: its lake-tables, else the lake file's entries of the kind."""
    named = [x.strip() for x in str(settings.get("lake-tables") or "").split(",") if x.strip()]
    if named:
        return [t for t in named if (entries.get(t) or {}).get("kind", t.split(".", 1)[1]) in (kind, kind.replace("-", "_"))] or (named if len(named) == 1 else [])
    return [t for t, e in entries.items() if e.get("kind", t.split(".", 1)[1]) == kind]


def _main(site: Path, lake: Path) -> dict[str, object]:
    """What the release of the fixture served from this lake: for each kind, the rows of the newest business date in the tables its
    generated connector files read (lake-tables, else the lake file's entry of the kind), summed over the connectors that name the kind
    (a union); per source system for trades (each of its trade connectors stamps one system). Read from the Delta logs; no server of that
    release is started."""
    import yaml
    try:
        import deltalake  # noqa: F401
    except ImportError:
        return {}
    entries = _lake_entries()
    out: dict[str, int] = {}
    for f in sorted((site / "connectors").glob("*.yaml")):
        d = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
        st = d.get("settings") or {}
        if d.get("plugin") != "delta" or st.get("lake") != "demo":
            continue
        for kind in d.get("kinds") or []:
            mine = _tables_of(kind, st, entries)
            if not mine:
                continue
            n = sum(rows_newest(lake / t.replace(".", "/", 1)) or 0 for t in mine)
            out[kind] = out.get(kind, 0) + n
            # the system: the connector's stamp, else its one table's (trading.trade_endur: ENDUR, as that release named them)
            stamp = st.get("set").get("SOURCE_SYSTEM") if isinstance(st.get("set"), dict) else st.get("set.SOURCE_SYSTEM")
            system = str(stamp or (mine[0].rsplit("_", 1)[-1].upper() if len(mine) == 1 and "trade_" in mine[0] else ""))
            if kind == "trade" and system:
                out[f"trade[{system}]"] = out.get(f"trade[{system}]", 0) + n
    return out


def _binding_map(server: str) -> dict[str, list[dict]]:
    """The server's binding map (GET /api/v1/admin/bindings/map): each kind's read connectors, each with the table it reads and that
    table's rows on its newest business date, read here from the very path the server resolved."""
    out: dict[str, list[dict]] = {}
    for k in _get(server, "/api/v1/admin/bindings/map").get("kinds", []):
        reads = []
        for c in k.get("reads") or []:
            t = c.get("table")
            c = {**c, "rows": rows_newest(Path(t)) if t and "://" not in t else None}
            reads.append(c)
        out[k["kind"]] = reads
    return out


def _hashes(folder: Path) -> dict[str, str]:
    return {str(f.relative_to(folder)): hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(folder.rglob("*")) if f.is_file()}


@pytest.fixture(scope="module")
def lake():
    sys.path.insert(0, str(ROOT / "tools" / "drillkit"))
    import lake_cache
    try:
        return lake_cache.demo_lake(ONLY, "today") / "delta"
    except RuntimeError as e:
        pytest.skip("could not build the demo lake: " + str(e))


@pytest.fixture(scope="module")
def site(tmp_path_factory) -> Path:
    out = tmp_path_factory.mktemp("upgrade-site")
    subprocess.run([sys.executable, str(ROOT / "tools" / "upgrade_fixture.py"), "--from", FROM, "--out", str(out / "fixture")], check=True,
                   cwd=ROOT, capture_output=True)
    return out / "fixture"


def test_every_demo_kind_keeps_its_record_count_after_the_upgrade(tmp_path_factory, lake, site):
    env = {"DRISHTI_DELTA_ROOT": str(lake), "DRISHTI_DEMO_ENABLED": "false", "DRISHTI_OFFLINE": "true", "DRISHTI_VAR_SYSTEM_BASKETS_SEED": "true"}
    gen = _stack(tmp_path_factory, env, heap=HEAP)
    st = next(gen)
    try:
        fresh = _counts(st.server)
        fresh_map = _binding_map(st.server)
    finally:
        next(gen, None)

    work = tmp_path_factory.mktemp("upgraded-server")
    (work / "data" / "connectors").mkdir(parents=True)
    for f in (site / "data" / "connectors" / "generated").iterdir():        # the markers the earlier server kept beside its data
        (work / "data" / "connectors" / "generated").mkdir(exist_ok=True)
        (work / "data" / "connectors" / "generated" / f.name).write_bytes(f.read_bytes())
    before = _hashes(site / "connectors")
    up_env = {**env, "DRISHTI_CONNECTORS_DIR": str(site / "connectors")}
    if (site / "bindings.yaml").is_file():
        up_env["DRISHTI_BINDINGS_FILE"] = str(site / "bindings.yaml")
    gen = _stack(tmp_path_factory, up_env, work=work, heap=HEAP)
    st = next(gen)
    try:
        upgraded = _counts(st.server)
        upgraded_map = _binding_map(st.server)
    finally:
        next(gen, None)

    main = _main(site, lake)
    table = [{"kind": k, "main": main.get(k), "fresh": fresh.get(k), "upgraded": upgraded.get(k)} for k in sorted(set(fresh) | set(upgraded))]
    if os.environ.get("DRISHTI_COUNT_TABLE"):
        Path(os.environ["DRISHTI_COUNT_TABLE"]).write_text(json.dumps(table, indent=1) + "\n", encoding="utf-8")
    # the binding map: every connector a read asks points at a table that exists and holds rows wherever the release held some
    bmap = [{"kind": k, "site": site_name, "connector": c["connector"], "layer": c.get("layer"), "plugin": c.get("plugin"), "lake": c.get("lake"),
             "table": c.get("table"), "exists": c.get("exists"), "rows": c.get("rows"), "main": main.get(k)}
            for site_name, m in (("fresh", fresh_map), ("upgraded", upgraded_map)) for k in sorted(m) for c in m[k]]
    if os.environ.get("DRISHTI_BINDING_MAP"):
        Path(os.environ["DRISHTI_BINDING_MAP"]).write_text(json.dumps(bmap, indent=1) + "\n", encoding="utf-8")
    pointing_wrong = [r for r in bmap if r["table"] and (r["main"] or 0) > 0 and (r["exists"] is False or not r["rows"])]
    assert not pointing_wrong, "connectors read a missing or empty table where the release had data:\n" + "\n".join(f"  {r}" for r in pointing_wrong)
    # every kind the release served from the lake is served now, with the same rows on the newest date
    # the trades of the systems the release had no connector for (its source modules are new: WSS, SWAPONE, ICI) are added, never lost
    new_systems = {site_name: sum((m.get(f"trade[{s}]") or 0) for s in SYSTEMS if main.get(f"trade[{s}]") is None)
                   for site_name, m in (("fresh", fresh), ("upgraded", upgraded))}
    for r in table:
        if r["kind"] == "trade":
            r["fresh_less_new"], r["upgraded_less_new"] = (r["fresh"] or 0) - new_systems["fresh"], (r["upgraded"] or 0) - new_systems["upgraded"]
    drop = [r for r in table if (r["main"] or 0) > 0 and (r.get("fresh_less_new", r["fresh"]) != r["main"]
                                                          or r.get("upgraded_less_new", r["upgraded"]) != r["main"])]
    assert not drop, "counts differ from the release's:\n" + "\n".join(f"  {r}" for r in drop)
    # the VaR inputs the lake does not hold (positions from trades, the seeded system baskets) are served on both
    for k in ("position", "system-basket"):
        assert fresh.get(k) and upgraded.get(k), f"{k}: fresh {fresh.get(k)}, upgraded {upgraded.get(k)}"
    assert _hashes(site / "connectors") == before, "the server changed a site connector file at start"
    assert fresh.get("trade"), f"no trades on a fresh site: {fresh}"
    no_trades = [s for s in SYSTEMS if (main.get(f"trade[{s}]") or 0) > 0 and not upgraded.get(f"trade[{s}]")]
    assert not no_trades, f"the upgraded site lost the trades of {no_trades}: {table}"
    lost = [r for r in table if (r["fresh"] or 0) != (r["upgraded"] or 0)]
    assert not lost, "record counts differ after the upgrade:\n" + "\n".join(f"  {r}" for r in lost)
