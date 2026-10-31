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

"""Writes the banking packs' sample data and, as separate steps, the dated rows the loaders put into the lake and the other stores.

    python3 tools/packgen/banking/make_data.py                 write config/packs/<pack>/samples (the latest business date): catalog.jsonl
                                                                and samples/<kind>.jsonl, one document a line (--sample-part-mb N splits
                                                                a kind above N MB into <kind>.part-001.jsonl ..., default 50), the trading
                                                                pack's Sutra tests and the file connector's sample feed (<data>/feeds/fixing.jsonl)
    python3 tools/packgen/banking/make_data.py --check         verify the data is consistent and the files are current
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/banking/make_data.py --lake data/delta [--days 10] [--rows DIR] [--no-load]
                                                                write the business-day history as JSONL rows (DIR, default <lake>/../build/lake-rows:
                                                                banking-history.jsonl and banking-restated.jsonl), then load them with
                                                                tools/samplegen/load_rows.py into data/delta/<domain>/<kind>/, each kind in the
                                                                layout its pack declares (the trade table's); --no-load stops after the JSONL
    python3 tools/packgen/banking/make_data.py --jsonl data/banking.jsonl   rows for tools/load-postgres.sh and load-aerospike.sh

Generation and loading are separate steps (docs/guides/DATA_GUIDE.md): every output is JSON Lines. --lake and --jsonl write rows
only: they never rewrite the packs' samples, the Sutra tests or the feed (the feed is written when the data folder has none, so the
file connector starts up). The samples are what the demo source serves live; the lake is the history (a table per kind, partitioned
by business date, in the data domain that owns the kind, see layout.py). Everything is deterministic.

Checks: every reference field points at an entity that exists; every netting set's net MTM is the sum of its
trades; every path a Sutra's header reads is present in each document of its kind.
"""
from __future__ import annotations

import json
import re
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "trades"))
import data_market
import data_names as N
import data_results
import data_trades
import layout
import taxonomy as T
import var_kinds as VK  # noqa: E402  (the VaR kinds: their samples are written by tools/packgen/var/make_var_data.py)
import ccr_kinds as XK  # noqa: E402  (the exposure engine's inputs: their samples are written by tools/packgen/ccr/make_ccr_data.py)
import convert as TC  # noqa: E402  (tools/packgen/trades: the common trade schema every source system writes)
import endur as TE  # noqa: E402
import schema as TSCHEMA  # noqa: E402
import tests as TT  # noqa: E402  (the trading pack's Sutra tests, one sample per deal Sutra)

ROOT = Path(__file__).resolve().parents[3]
PACKS = ROOT / "config" / "packs"
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "common"))
import packsamples as SAMPLES  # noqa: E402  (samples/<kind>.jsonl, one document a line)
import upper as U  # noqa: E402
import walks  # noqa: E402

LIVE_WALK = {"ir-curve": {"tenY": 0.0003}, "fx-spot": {"mid": 0.0004}, "equity": {"price": 0.3}, "equity-index": {"level": 4.0},
             "commodity": {"front": 0.05}, "commodity-curve": {"front": 0.05}, "credit-curve": {"spread5y": 0.4}, "bond": {"price": 0.02}}
ID_FIELD = {k.kind: k.id_field for k in T.KINDS + [T.TRADE]}


# the rich internal model of the trades (data_trades.py) the other kinds are computed from; the trade samples are what the source
# systems write: the common schema (tools/packgen/trades), CALYPSO and MUREX converted from the model, ENDUR deals of its own
RICH: dict[str, dict] = {}
ENDUR_PER_PRODUCT = 3


def trade_samples(rich: dict[str, dict]) -> dict[str, dict]:
    out = {}
    for tid, d in rich.items():
        t = TC.convert(d, N.AS_OF.isoformat())
        t["_meta"] = {"source": t["SOURCE_SYSTEM"].lower(), "generation": 1}
        out[tid] = t
    for t in TE.Endur(as_of=N.AS_OF).samples(ENDUR_PER_PRODUCT):
        t["_meta"] = {"source": "endur", "generation": 1}
        out[str(t["TRADE_ID"])] = t
    return out


def valuations(rich: dict[str, dict], trades: dict[str, dict]) -> dict[str, dict]:
    """The risk engine's valuation of every trade of every source system (market-risk's trade-valuation, TVAL-<trade id>): the model's MTM,
    P&L and DV01 for CALYPSO and MUREX (so netting sets still add up), a deterministic mark for ENDUR deals. Its MTM ticks live."""
    import random
    out = {}
    for tid, t in trades.items():
        r = rich.get(tid)
        leg = (t.get("LEGS") or [{}])[0]
        if r:
            mtm, pnl, dv01, desk, notional = r["mtm"], r["pnl1d"], (r.get("risk") or {}).get("dv01", 0), r["desk"], r["notional"]
        else:
            g = random.Random(f"tval/{tid}")
            notional = float(leg.get("NOTIONAL") or 0.0)
            mtm = round(notional * g.gauss(0, 0.015))
            pnl, dv01, desk = round(mtm * g.gauss(0, 0.04)), 0, "DESK-COMM"
        vid = f"TVAL-{tid}"
        step = max(500, round(abs(mtm) * 0.002 + notional * 1e-5))
        out[vid] = {"VALUATION_ID": vid, "TRADE_ID": t["TRADE_ID"], "SOURCE_SYSTEM": t["SOURCE_SYSTEM"], "PRODUCT_TYPE": t["PRODUCT_TYPE"],
                    "PRODUCT_GROUP": t["PRODUCT_GROUP"] or "Commodity", "PORTFOLIO": t["PORTFOLIO"], "DESK": desk, "COUNTERPARTY": t["COUNTERPARTY"],
                    "NETTING_SET": (r or {}).get("nettingSet", ""), "TRADER_ID": t["TRADER_ID"], "BUY_SELL_CODE": t["BUY_SELL_CODE"], "TRADE_STATUS": t["TRADE_STATUS"], "CURRENCY": t["CURRENCY"],
                    "NOTIONAL": notional, "MTM": mtm, "MTM_CURRENCY": "USD", "PNL_1D": pnl, "DV01": dv01, "TRADE_DATE": t["TRADE_DATE"],
                    "MATURITY_DATE": t["MATURITY_DATE"], "VALUATION_DATE": N.AS_OF.isoformat(),
                    "_meta": {"source": "risk-engine", "generation": 1, "live": True,
                              "walk": {"MTM": step, "derive": [walks._derive("PNL_1D", f"$.MTM - {mtm - pnl}")]}}}
    return out


def build(upper: bool = True) -> dict[str, dict[str, dict]]:
    """Every banking document; upper=False keeps the generator's short names (the retired example packs built on this data read those)."""
    docs = {k: dict(v) for k, v in data_market.build().items()}
    for kind, ds in docs.items():
        for d in ds.values():
            d["_meta"] = {"source": "md-hub", "generation": 1, "live": kind in LIVE_WALK, **({"walk": LIVE_WALK[kind]} if kind in LIVE_WALK else {})}
    trades = data_trades.build()
    RICH.clear()
    RICH.update(trades)
    for kind, ds in data_results.build(trades).items():
        docs.setdefault(kind, {}).update(ds)
    for kind, ds in docs.items():
        for d in ds.values():
            for k in [k for k, v in d.items() if v is None]:
                del d[k]
    for kind, ds in docs.items():                       # the coherent live walk of each kind (tools/packgen/common/walks.py)
        for d in ds.values():
            walks.apply(kind, d)
    if upper:
        docs = {kind: {i: U.record(d) for i, d in ds.items()} for kind, ds in docs.items()}   # the names a pack writes: UPPER_SNAKE
    docs["trade"] = trade_samples(trades)               # nulls kept: an extract writes them (PRICE, OPTION_TYPE, STRATEGY ...)
    docs["trade-valuation"] = valuations(trades, docs["trade"])
    return docs


def title(kind: str, d: dict) -> tuple[str, str]:
    spec = next((k for k in T.KINDS + [T.TRADE] if k.kind == kind), None)
    label = spec.label if spec else kind
    if kind == "trade":
        notional = (d["LEGS"][0]["NOTIONAL"] if d.get("LEGS") else 0.0) or 0.0
        return str(d["TRADE_ID"]), (f"{label} · {d['SOURCE_SYSTEM']} · {d['PRODUCT_TYPE']} · {d['COUNTERPARTY']} · "
                                    f"{d['CURRENCY']} {notional / 1e6:,.1f}m")
    for f in ("PRODUCT_NAME", "NAME", "ISSUER_NAME", "SCENARIO_NAME", "COUNTERPARTY_NAME", "PAIR_NAME", "COMMODITY", "UNDERLIER", "PAIR", "CURRENCY", "BOOK"):
        if isinstance(d.get(f), str):
            return d[ID_FIELD[kind]], f"{label} · {d[f]}"
    return d[ID_FIELD[kind]], label


# ---- checks ----------------------------------------------------------------------------------------------------
PATH = re.compile(r"\$\.([A-Za-z_][A-Za-z0-9_]*)")
#: fields a page reads that the server adds as it opens (the VaR engine's figures on a desk, docs/guides/VAR_GUIDE.md section 21.3)
AUGMENTED = {"varEngine", "VAR_ENGINE", "exposureEngine", "EXPOSURE_ENGINE"}


def check(docs: dict) -> list[str]:
    problems = []
    index = {kind: set(ds) for kind, ds in docs.items()}
    fields = T.graph_fields()
    for kind, ds in docs.items():
        for id_, d in ds.items():
            if str(d.get(ID_FIELD[kind])) != id_:
                problems.append(f"{kind}/{id_}: id field {ID_FIELD[kind]} is {d.get(ID_FIELD[kind])!r}")
            for f, (target, _) in fields.items():
                v = d.get(f)
                vals = [v] if isinstance(v, str) else [x for x in v if isinstance(x, str)] if isinstance(v, list) else \
                    [v["ID"]] if isinstance(v, dict) and "ID" in v else []
                for x in vals:
                    if x and x not in index.get(target, ()):          # a blank reference (an extract's "") points nowhere
                        problems.append(f"{kind}/{id_}: {f} -> {target}/{x} does not exist")
    for id_, d in docs.get("trade", {}).items():                       # every source system writes exactly the common schema
        problems += TSCHEMA.problems({k: v for k, v in d.items() if k != "_meta"})
    for ns, d in docs.get("netting-set", {}).items():
        total = sum(t["mtm"] for t in RICH.values() if t["nettingSet"] == ns)
        if total != d["NET_MTM"]:
            problems.append(f"netting-set/{ns}: NET_MTM {d['NET_MTM']} != sum of trades {total}")
    for id_, d in RICH.items():                                        # deep trades: legs add up, a cashflow's rate lies among its fixings
        for leg in d.get("legs", []):
            flows = leg.get("cashflows")
            if not flows:
                continue
            if abs(sum(f["pv"] for f in flows) - leg["pv"]) > 0.05:
                problems.append(f"trade/{id_}: leg {leg['leg']} pv {leg['pv']} != sum of its cashflows")
            for f in flows:
                rates = [x["rate"] for x in f.get("fixings", [])]
                if rates and not min(rates) - 1e-6 <= f["rate"] <= max(rates) + 1e-6:
                    problems.append(f"trade/{id_}: leg {leg['leg']} cashflow {f['n']} rate {f['rate']} is outside its fixings {min(rates)}..{max(rates)}")
    for spec in T.KINDS:
        need = {m for s in spec.strip for m in PATH.findall(s[1])} | {m for p in spec.panels for path in p.paths() for m in PATH.findall(path)}
        need -= AUGMENTED                                              # added when the page opens (an engine's figures), never stored
        for id_, d in docs.get(spec.kind, {}).items():
            missing = [f for f in sorted(need) if f not in d]
            if missing:
                problems.append(f"{spec.kind}/{id_}: missing {missing} (read by its Sutra)")
                break
    return problems


def sample_files(docs: dict) -> dict[Path, str]:
    """Each pack's samples/: catalog.jsonl and one JSON Lines file per kind (tools/packgen/common/samples.py)."""
    out: dict[Path, str] = {}
    by_pack: dict[str, dict[str, dict]] = {}
    catalogs: dict[str, list] = {}
    for kind, ds in sorted(docs.items()):
        pack = layout.pack_of(kind)
        by_pack.setdefault(pack, {})[kind] = ds
        for id_, d in sorted(ds.items()):
            t, sub = title(kind, d)
            catalogs.setdefault(pack, []).append({"kind": kind, "id": id_, "title": t, "subtitle": sub})
    for pack, kinds in by_pack.items():
        for name, text in SAMPLES.files(kinds, catalogs[pack], SAMPLES.part_mb(sys.argv)).items():
            out[PACKS / pack / "samples" / name] = text
    return out


# The SOFR fixing history the feed folder serves (FIX SOFR-HISTORY): business date, rate (%), volume ($bn).
SOFR_HISTORY = [("2026-09-01", 3.95, 1910), ("2026-09-02", 3.95, 1879), ("2026-09-03", 3.95, 1996), ("2026-09-04", 3.93, 2053),
                ("2026-09-07", 3.91, 2023), ("2026-09-08", 3.89, 1886), ("2026-09-09", 3.89, 2181), ("2026-09-10", 3.87, 1939),
                ("2026-09-11", 3.87, 2229), ("2026-09-14", 3.87, 2009), ("2026-09-15", 3.89, 1869), ("2026-09-16", 3.9, 1966),
                ("2026-09-17", 3.89, 1897), ("2026-09-18", 3.88, 2176), ("2026-09-21", 3.87, 2083), ("2026-09-22", 3.87, 1999),
                ("2026-09-23", 3.87, 1875), ("2026-09-24", 3.85, 1932), ("2026-09-25", 3.86, 2021), ("2026-09-28", 3.85, 2084),
                ("2026-09-29", 3.85, 1970), ("2026-09-30", 3.86, 2130)]


def data_dir() -> Path:
    """The data folder: --data-dir DIR, else DRISHTI_DATA_DIR, else the repository's data/ (QA2-21: never the repository's when either is given)."""
    if "--data-dir" in sys.argv:
        return Path(sys.argv[sys.argv.index("--data-dir") + 1])
    return Path(os.environ.get("DRISHTI_DATA_DIR") or (ROOT / "data"))


#: the CSV feed file earlier releases wrote (CSV input is deprecated): removed when the JSONL feed replaces it, if it is unchanged
OLD_FEED_CSV = "date,rate,volume_bn\n" + "".join(f"{d},{r},{v}\n" for d, r, v in SOFR_HISTORY)


def feed_text() -> str:
    """fixing.jsonl: one fixing series a line (id SOFR-HISTORY and its rows), what `FIX SOFR-HISTORY` opens."""
    rows = [{"date": d, "rate": r, "volume_bn": v} for d, r, v in SOFR_HISTORY]
    return json.dumps({"id": "SOFR-HISTORY", "rows": rows}, ensure_ascii=False, separators=(",", ":")) + "\n"


def write_feeds(root: Path, rewrite: bool = True) -> Path:
    """<data>/feeds (not in git, like everything under data/): the feed folder the shipped configuration serves, as JSON Lines
    (fixing.jsonl). rewrite=False writes it only when missing (a loader's run never rewrites it). The CSV of earlier releases goes."""
    f = root / "fixing.jsonl"
    text = feed_text()
    f.parent.mkdir(parents=True, exist_ok=True)
    if not f.exists() or rewrite and f.read_text(encoding="utf-8") != text:
        f.write_text(text, encoding="utf-8")
    old = root / "fixing" / "SOFR-HISTORY.csv"
    if old.is_file() and old.read_text(encoding="utf-8") == OLD_FEED_CSV:
        old.unlink()
        if not any(old.parent.iterdir()):
            old.parent.rmdir()
    return f


def lake_tables(docs: dict) -> list[tuple[str, str, str, dict]]:
    """(domain, table, kind, docs) of every lake table, in load order: the trades one table per source system (trade_endur ...),
    each its own connector's."""
    out = []
    for domain in layout.DOMAINS:
        kinds = {k: docs.get(k, {}) for k in (x for p in layout.PACKS.values() for d, ks in p["kinds"].items() if d == domain for x in ks)}
        tables = {k: (k, v) for k, v in kinds.items()}
        if "trade" in tables:
            trades = tables.pop("trade")[1]
            for system in TSCHEMA.SYSTEMS:
                tables[f"trade_{system.lower()}"] = ("trade", {i: t for i, t in trades.items() if t["SOURCE_SYSTEM"] == system})
        out += [(domain, table, kind, ds) for table, (kind, ds) in sorted(tables.items())]
    return out


def main() -> None:
    docs = build()
    problems = check(docs)
    if problems:
        raise SystemExit("banking data is inconsistent:\n  " + "\n  ".join(problems[:40]))
    files = sample_files(docs)
    targets = [PACKS / p / "samples" for p in layout.PACKS]
    existing = {f for t in targets if t.exists() for f in t.rglob("*.json*")}    # the JSONL files, and any older <kind>/<id>.json
    # the VaR engine's reference samples are tools/packgen/var/make_var_data.py's (from the committed test data), not this generator's
    existing = {f for f in existing if not (f.parent == PACKS / "market-risk" / "samples" and f.name.split(".")[0] in VK.KINDS)}
    # and the intraday sample of tools/pvgen (VAR_GUIDE.md section 15.12): a batch file the scripts write, not a banking document
    existing = {f for f in existing if f.parent != PACKS / "market-risk" / "samples" / "intraday"}
    existing = {f for f in existing if not (f.parent == PACKS / "counterparty-risk" / "samples" and f.name.split(".")[0] in XK.KINDS)}
    if "--check" in sys.argv:
        stale = [str(f) for f, t in files.items() if not f.exists() or f.read_text(encoding="utf-8") != t] + TT.stale(docs["trade"])
        extra = [str(f) for f in existing - set(files)]
        if stale or extra:
            raise SystemExit(f"banking samples out of date; run make_data.py. stale={stale[:3]} extra={extra[:3]}")
        print(f"{sum(len(v) for v in docs.values())} banking documents consistent and up to date")
        return
    rows_only = "--jsonl" in sys.argv or "--lake" in sys.argv
    if not rows_only:                                   # the generator's own outputs: a loader's run never rewrites them
        for f in existing - set(files):
            f.unlink()
        for t in targets:                               # the folders the older one-file-a-document layout left empty
            for d in sorted((x for x in t.glob("*") if x.is_dir()), reverse=True) if t.exists() else ():
                if not any(d.iterdir()):
                    d.rmdir()
        for f, t in files.items():
            f.parent.mkdir(parents=True, exist_ok=True)
            if not f.exists() or f.read_text(encoding="utf-8") != t:
                f.write_text(t, encoding="utf-8")
        print(f"wrote {sum(len(v) for v in docs.values())} documents into {len(layout.PACKS)} packs' samples; {TT.write(docs['trade'])} trading test files")
    print(f"feeds: {write_feeds(data_dir() / 'feeds', rewrite=not rows_only)}")
    if "--jsonl" in sys.argv:
        from samplegen.dates import Calendar
        from samplegen.lake import final_rows
        from samplegen.layout import layouts_for_domain, promote

        out = Path(sys.argv[sys.argv.index("--jsonl") + 1])
        days = int(sys.argv[sys.argv.index("--days") + 1]) if "--days" in sys.argv else 10
        n = 0
        out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", encoding="utf-8") as f:
            for domain in layout.DOMAINS:
                kinds = {k: docs.get(k, {}) for k in (x for p in layout.PACKS.values() for d, ks in p["kinds"].items() if d == domain for x in ks)}
                layouts = layouts_for_domain(domain)
                for kind, id_, d, body in final_rows(kinds, N.AS_OF, days, Calendar.of("USNY")):
                    row = {"domain": domain, "kind": kind, "id": id_, "date": d.isoformat(), "doc": body}
                    lay = layouts.get(kind)
                    if lay:                             # the fields the pack promotes, by path (Aerospike stores them as bins)
                        names = dict(zip(lay.names, lay.columns))
                        row["columns"] = {names[k]: v for k, v in promote(json.loads(body), lay).items()}
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
                    n += 1
        print(f"jsonl: {n} rows in {out} (tools/load-postgres.sh and tools/load-aerospike.sh load them)")
    if "--lake" in sys.argv:
        from samplegen.dates import Calendar
        from samplegen.lake import rows_dir, write_rows

        root = Path(sys.argv[sys.argv.index("--lake") + 1])
        days = int(sys.argv[sys.argv.index("--days") + 1]) if "--days" in sys.argv else 10
        rows = Path(sys.argv[sys.argv.index("--rows") + 1]) if "--rows" in sys.argv else rows_dir(root)
        history, restated, n = write_rows(rows, "banking", lake_tables(docs), N.AS_OF, days, Calendar.of("USNY"))
        print(f"rows: {n} in {history} (restatements in {restated.name})")
        if "--no-load" not in sys.argv:                 # the loading step: JSONL rows into Delta (tools/samplegen/load_rows.py)
            from samplegen import load_rows
            total = load_rows.load([history], root, [restated])
            print(f"lake: {total} rows under {root} ({', '.join(layout.DOMAINS)})")


if __name__ == "__main__":
    main()
