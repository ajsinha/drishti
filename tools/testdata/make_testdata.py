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

"""Writes data/testdata: the committed test data behind `drishti.py lake demo` (sixty business days of every dataset the seven packs read).

    python3 tools/testdata/make_testdata.py                  write data/testdata (about a minute; deterministic)
    python3 tools/testdata/make_testdata.py --out DIR        somewhere else (a test)
    python3 tools/testdata/make_testdata.py --per-system 300 a small book (a test)
    python3 tools/testdata/make_testdata.py --check          fail if data/testdata differs from what would be written

One folder per dataset: trades/endur, trades/calypso, trades/murex (5,000 trades each, the common 47-attribute UPPER_SNAKE schema),
then <pack>/<kind> for every kind of core, market-data, counterparty-risk and market-risk (universe.py). Each folder holds one file per
business date named by the lake table it feeds, <table>.<YYYY-MM-DD>.jsonl.gz (docs/guides/DATA_LAKE_GUIDE.md section 5.2): the
first day's the whole snapshot, each later day's what changed (history.py); the market-risk pack's VaR data (positions, P&L vectors, hierarchies, the
nodes' 250-day history, portfolios) is dated by its files instead (``dated-by``: every row's date its file's; tools/packgen/var/make_var_data.py). world-economy and news-and-markets are static reference data already in the
repository (their packs' samples), so the manifest points at them instead of copying them. The news-and-markets pack's quotes and
headlines are a real snapshot captured by capture_market.py (data/testdata/news-and-markets/, kept here and listed as they are), and
so are the us-equities and gleif packs' public data (data/testdata/us-equities/, data/testdata/gleif/: written by their generators,
publicsnap.py; kept and listed as they are). The manifest, data/testdata/testdata.yaml, says for each dataset its pack, kind, connector, key, partitions, files and counts: `lake demo` reads only it. Never reads a real
extract (`lake demo --with-real` adds those to a local lake only).
"""
from __future__ import annotations

import argparse
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import capture_market as CM  # noqa: E402
import history as H  # noqa: E402
import marketdays as MK  # noqa: E402
import publicsnap as PS  # noqa: E402
import universe as UV  # noqa: E402

PACKS = ("core", "market-data", "trading", "counterparty-risk", "market-risk", "world-economy", "news-and-markets", "compute-demo")
FROM_SAMPLES = ("world-economy", "news-and-markets", "compute-demo")
SYSTEMS = ("ENDUR", "CALYPSO", "MUREX")
KEEP = (CM.PACK, *PS.PACKS)   # real, dated snapshots (capture_market.py, the us-equities and gleif generators): never regenerated here


def has_samples(pack: str, kind: str) -> bool:
    d = ROOT / "config" / "packs" / pack / "samples"
    return any(d.glob(f"{kind}.jsonl*")) or (d / kind).is_dir()


def delta_connectors(packs_dir: Path) -> dict[str, tuple[str, str]]:
    """kind -> (pack, connector) for every Delta connector of these packs (the trade connectors are per system, handled apart)."""
    import yaml
    out = {}
    for pack in PACKS:
        spec = yaml.safe_load((packs_dir / pack / "pack.yaml").read_text(encoding="utf-8"))
        for name, c in (spec.get("connectors") or {}).items():
            if c.get("plugin") != "delta" or (c.get("settings") or {}).get("union"):
                continue
            for kind in c.get("kinds") or []:
                out[kind] = (pack, name)
    return out


def table_of(packs_dir: Path, pack: str, connector: str, kind: str) -> str:
    """The lake table a pack's Delta connector writes a kind into: its lake entry (settings.lake-tables, <domain>.<table>, of the kind:
    the pack's lake: block, DATA_LAKE_GUIDE.md), else its settings.table.<kind> (the older connector form), else the kind: a feed file's name."""
    import yaml
    spec = yaml.safe_load((packs_dir / pack / "pack.yaml").read_text(encoding="utf-8"))
    st = ((spec.get("connectors") or {}).get(connector) or {}).get("settings") or {}
    entries = st.get("lake-tables") or []
    entries = [x.strip() for x in entries.split(",")] if isinstance(entries, str) else [str(x).strip() for x in entries]
    tables = ((spec.get("lake") or {}).get("tables") or {})
    for name in entries:
        folder = name.split(".", 1)[1] if "." in name else name
        if (tables.get(name) or {}).get("kind", folder) == kind:
            return folder
    return str((st.get("table") or {}).get(kind) or kind) if isinstance(st.get("table"), dict) else str(st.get(f"table.{kind}") or kind)


def key_of(doc: dict) -> str:
    return next((k for k in doc if k.endswith("_ID")), next(iter(doc)))


def business_days(n: int) -> list[str]:
    from samplegen.dates import Calendar
    from samplegen.lake import business_days as bd
    return [d.isoformat() for d in bd(UV.AS_OF, n, Calendar.of("USNY"))]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--out", default=str(ROOT / "data" / "testdata"), help="where (default data/testdata)")
    p.add_argument("--per-system", type=int, default=UV.PER_SYSTEM, help=f"trades per source system (default {UV.PER_SYSTEM:,})")
    p.add_argument("--days", type=int, default=60, help="business days ending on the samples' date (default 60)")
    p.add_argument("--check", action="store_true", help="write into <data>/build/testdata-check and compare with --out; nothing changes")
    p.add_argument("--captured-from", help="the folder whose captured market snapshot the manifest lists (default --out)")
    a = p.parse_args(argv)
    if a.check:
        return check(a, argv or [])
    started = time.monotonic()
    out = Path(a.out)
    days = H.Days(business_days(a.days))
    u = UV.Universe(a.per_system).build(days)          # the documents follow each trade's version on the last day
    print(f"universe: {sum(len(b) for b in u.trades.values()):,} trades, {sum(len(v) for v in u.docs.values()):,} other documents "
          f"({time.monotonic() - started:.0f} s)")
    for sub in [d for d in out.iterdir() if d.is_dir() and d.name not in KEEP] if out.exists() else []:   # ours; README.md and the snapshot stay
        shutil.rmtree(sub)
    datasets = []
    booked: dict[str, int] = {}
    for system in SYSTEMS:
        book = u.trades[system]
        for t in book:
            booked[str(t["TRADE_ID"])] = days.booked(t.get("TRADE_DATE"))
        folder = f"trades/{system.lower()}"
        hist = H.DatasetHistory(out / folder, "TRADE_ID", days, table_of(ROOT / "config" / "packs", "trading", f"trade-{system.lower()}",
                                                                          "trade")).write(H.trade_states(book, days))
        datasets.append({"folder": folder, "pack": "trading", "kind": "trade", "connector": f"trade-{system.lower()}", "key": "TRADE_ID",
                         "partition-by": ["PRODUCT_TYPE"], **hist})
        print(f"  {folder}: {len(book):,} trades")
    owners = delta_connectors(ROOT / "config" / "packs")
    market = MK.MarketDays(u.docs, days.dates)            # the market documents of each day, one coherent picture (QA2-201)
    for kind in sorted(u.docs):
        docs = u.docs[kind]
        if not docs or kind == "trade":
            continue
        pack, connector = owners[kind]
        key = key_of(next(iter(docs.values())))
        since = {f"TVAL-{tid}": i for tid, i in booked.items()} if kind == "trade-valuation" else None
        members = booked if kind == "netting-set" else None
        folder = f"{pack}/{kind}"
        versions = H.valuation_versions(u.timeline) if kind == "trade-valuation" else None
        hist = H.DatasetHistory(out / folder, key, days, table_of(ROOT / "config" / "packs", pack, connector, kind)).write(H.doc_states(kind, docs, days, since, members, market, versions))
        datasets.append({"folder": folder, "pack": pack, "kind": kind, "connector": connector, "key": key, **hist})
    # the VaR engine's data (positions netting the trades just written, a P&L vector per trade, hierarchies, the nodes' history,
    # portfolios): tools/packgen/var/make_var_data.py, dated by its files
    sys.path.insert(0, str(ROOT / "tools" / "packgen" / "var"))
    import make_var_data as VD  # noqa: E402
    datasets += VD.write(out, VD.demos(out, days.dates), with_samples=out.resolve() == (ROOT / "data" / "testdata").resolve())
    # the exposure engine's data (a simulation run of the last date and every member trade's paths over its grid, for the committed
    # netting sets just written): tools/packgen/ccr/make_ccr_data.py, dated by its files
    sys.path.insert(0, str(ROOT / "tools" / "packgen" / "ccr"))
    import make_ccr_data as XD  # noqa: E402
    datasets += XD.write_testdata(out, days.dates, side_files=out.resolve() == (ROOT / "data" / "testdata").resolve())
    # the kinds position-sensitivity (SENS) and risk-factor (RFAC) from the VaR data just written (tools/packgen/sensitivity/
    # make_sensitivity_data.py): read by the server's FRTB-SA engine from risk-store, loaded by `lake demo` as every risk kind
    sys.path.insert(0, str(ROOT / "tools" / "packgen" / "sensitivity"))
    import make_sensitivity_data as SD  # noqa: E402
    datasets += SD.datasets(out, SD.write(out))
    for pack in FROM_SAMPLES:
        for kind, (pk, connector) in sorted(owners.items()):
            if pk == pack and has_samples(pack, kind):
                datasets.append({"samples": f"config/packs/{pack}/samples", "pack": pack, "kind": kind, "connector": connector})
    datasets += CM.captured_datasets(Path(a.captured_from) if a.captured_from else out)
    datasets += PS.datasets(Path(a.captured_from) if a.captured_from else out)
    write_manifest(out, days.dates, datasets)
    written = [f for f in out.rglob("*") if f.is_file() and f.name.endswith((".jsonl.gz", ".tar"))]
    total = sum(f.stat().st_size for f in written)
    biggest = max(written, key=lambda f: f.stat().st_size)
    print(f"wrote {out}: {len(datasets)} datasets, {total / 1e6:.1f} MB in {len(written)} files "
          f"(largest {biggest.relative_to(out)} {biggest.stat().st_size / 1e6:.1f} MB), {time.monotonic() - started:.0f} s")
    return 0


def check(a, argv: list[str]) -> int:
    import filecmp
    import os
    scratch = Path(os.environ.get("DRISHTI_DATA_DIR") or ROOT / "data") / "build" / "testdata-check"
    shutil.rmtree(scratch, ignore_errors=True)
    scratch.mkdir(parents=True)
    try:
        rest = [x for x in argv if x != "--check"]
        if "--out" in rest:
            i = rest.index("--out")
            del rest[i:i + 2]
        main(rest + ["--out", str(scratch), "--captured-from", str(a.out)])
        want, got = Path(a.out), scratch
        names = sorted({str(f.relative_to(want)) for f in want.rglob("*") if f.is_file() and f.name != "README.md"
                        and f.relative_to(want).parts[0] not in KEEP}
                       | {str(f.relative_to(got)) for f in got.rglob("*") if f.is_file()})
        stale = [n for n in names if not (want / n).is_file() or not (got / n).is_file() or not filecmp.cmp(want / n, got / n, shallow=False)]
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    if stale:
        print(f"data/testdata out of date ({len(stale)} files, e.g. {stale[:3]}); run tools/testdata/make_testdata.py")
        return 1
    print(f"data/testdata up to date ({len(names)} files)")
    return 0


def write_manifest(out: Path, dates: list[str], datasets: list[dict]) -> None:
    import yaml
    lic = (ROOT / "config" / "license-header.txt").read_text(encoding="utf-8").strip().splitlines()
    head = "".join(f"# {line}".rstrip() + "\n" for line in lic) + "\n" + (
            "# data/testdata/testdata.yaml: written by tools/testdata/make_testdata.py; read by `drishti.py lake demo`. Do not edit by hand.\n"
            "# Each dataset: its folder (one <table>.<YYYY-MM-DD>.jsonl.gz a business date: the first day's snapshot, then each later day's\n"
            "# changes; or tar archives of <table>/<YYYY-MM-DD>/ folders), the pack, kind and Delta connector it\n"
            "# feeds, the key and the partition columns after business_date; or the pack samples it is built from (static reference data).\n")
    body = {"business-dates": dates, "as-of": dates[-1], "datasets": datasets}
    (out / "testdata.yaml").write_text(head + yaml.safe_dump(body, sort_keys=False, width=140), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
