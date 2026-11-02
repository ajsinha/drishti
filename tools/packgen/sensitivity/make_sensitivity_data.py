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

"""Writes the demo position sensitivities and the factor dictionary (docs/architecture/SENSITIVITIES_AND_FRTB_SA.md, planned kinds
``position-sensitivity`` and ``risk-factor`` of the market-risk pack) into data/testdata/market-risk/, as a bank's sensitivity batch
would deliver them:

    position-sensitivity/   one record per position on the business date: its placement and terms, and the sparse list SENSITIVITIES
                            (DV01, CS01, IE01 per +1 bp; DELTA per +1% relative move; VEGA per +1 vol point; CURVATURE_UP and
                            CURVATURE_DOWN, the change in value for the regulatory curvature shock of MAR21.5, with SHOCK_SIZE)
    risk-factor/            the factor dictionary: every factor named by the standard, with its FRTB and SIMM mapping and the names
                            three source systems give it (factor_dictionary.py)

**From the VaR demo's factor model, so the two agree.** The positions are those the server derives from the committed VaR inputs of the
business date (the pnl-vector and scenario-pv files; the pack's position rules, positions.Rules); each position's factor loadings
are the VaR model's (factors.loadings) and its one-sigma daily P&L the same seeded draw the VaR batch uses (positions.Batch). A loading
becomes a sensitivity by the factor's daily volatility (DAILY_SIGMA): a USD rates position whose one-sigma P&L is 600,000 has a DV01
of about −100,000 per bp. Rate and credit sensitivities are split onto the two vertices around the position's maturity (MAR21.8); an
option position also gets vega at the vertex of its expiry and curvature from the same short (or long) gamma the VaR model gives it.

Planned kinds: the files are not listed in testdata.yaml and ``lake demo`` does not load them until the kinds have a command. Run after
the VaR data (tools/packgen/var/make_var_data.py; make_testdata.py runs both). Deterministic: the same inputs give the same files, byte
for byte.

    python3 tools/packgen/sensitivity/make_sensitivity_data.py               write data/testdata/market-risk/{position-sensitivity,risk-factor}/
    python3 tools/packgen/sensitivity/make_sensitivity_data.py --check       fail if they differ from what would be written
    python3 tools/packgen/sensitivity/make_sensitivity_data.py --out DIR     write elsewhere (reads --testdata)
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "var"))
import factor_dictionary as D  # noqa: E402
import factors as F  # noqa: E402
import positions as P  # noqa: E402

PACK = "market-risk"
SEED = "drishti-sensitivity-demo"
PACK_YAML = ROOT / "config" / "packs" / PACK / "pack.yaml"
#: one daily standard deviation of each VaR model factor's move, in the unit its sensitivity is quoted in (bp, % or vol points)
DAILY_SIGMA = {"IR": 6.0, "INF": 4.0, "CR.IG": 4.0, "CR.HY": 15.0, "FX": 0.6, "EQ": 1.1, "CO": 1.7, "VOL": 0.9}
#: the curvature shock of each class (MAR21.5, MAR21.98-101): GIRR the largest delta risk weight as bp; the others the bucket's risk weight
CURVATURE_SHOCK = {"GIRR": 170.0, "FX": 0.15}
CSR_RW = {1: 0.005, 2: 0.01, 3: 0.05, 4: 0.03, 5: 0.03, 6: 0.02, 7: 0.015, 8: 0.025, 9: 0.02, 10: 0.04, 11: 0.12, 12: 0.07, 13: 0.085,
          14: 0.055, 15: 0.05, 16: 0.12, 17: 0.015, 18: 0.05}
EQ_RW = {1: 0.55, 2: 0.60, 3: 0.45, 4: 0.55, 5: 0.30, 6: 0.35, 7: 0.40, 8: 0.50, 9: 0.70, 10: 0.50, 11: 0.70, 12: 0.15, 13: 0.25}
CM_RW = {1: 0.30, 2: 0.35, 3: 0.60, 4: 0.80, 5: 0.40, 6: 0.45, 7: 0.20, 8: 0.35, 9: 0.25, 10: 0.35, 11: 0.50}
IMPLIED_VOL = {"GIRR": 0.28, "FX": 0.09, "EQ": 0.21, "COMM": 0.32, "CSR_NS": 0.45}
#: the source systems that send sensitivities per trade (GRAIN TRADE) rather than per position, as some banks' systems do
TRADE_GRAIN_SYSTEMS = ("ENDUR",)
#: share of records sent as proxied (a missing surface or curve valued from a proxy), about one in a hundred
PROXIED_EVERY = 97


def _h(*parts) -> int:
    return P._h("|".join(map(str, parts)))


def read_terms(testdata: Path, cob: str) -> list[dict]:
    """The live trades of the business date with their terms, from the committed VaR inputs (vectors left out)."""
    rows = []
    for kind in ("pnl-vector", "scenario-pv"):
        for f in sorted((testdata / PACK / kind).glob(f"cob-{cob}*.jsonl.gz")):
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                for line in fh:
                    if line.strip():
                        d = json.loads(line)
                        d.pop("PNL", None)
                        d.pop("SCENARIO_PV", None)
                        rows.append(d)
    return sorted(rows, key=lambda d: str(d["TRADE_ID"]))


def cob_of(testdata: Path) -> str:
    files = sorted((testdata / PACK / "scenario-set").glob("cob-*.jsonl.gz"))
    if not files:
        raise SystemExit(f"no scenario set under {testdata / PACK / 'scenario-set'}: run tools/packgen/var/make_var_data.py first")
    return files[-1].name[4:14]


def years_between(a: str, b: str | None) -> float:
    if not b:
        return 5.0
    try:
        return max(0.0, (date.fromisoformat(str(b)[:10]) - date.fromisoformat(a)).days / 365.25)
    except ValueError:
        return 5.0


class Position:
    """One position: its trades' terms, and the factors it is sensitive to with the one-sigma P&L of each."""

    def __init__(self, pid: str, trades: list[dict], cob: str):
        self.pid, self.trades, self.cob = pid, trades, cob
        t0 = trades[0]
        self.t0 = t0
        self.group = t0.get("PRODUCT_GROUP") or "Commodity"
        self.ptype = str(t0.get("PRODUCT_TYPE") or "")
        self.ccy = (t0.get("TRADE_CURRENCY") or t0.get("CURRENCY") or "USD").upper()
        self.option = F.is_option(self.ptype)
        self.net = sum(float(t.get("QUANTITY") or 0.0) for t in trades)
        gross = sum(abs(float(t.get("QUANTITY") or 0.0)) for t in trades) or 1.0
        r = F.seeded("instrument", t0.get("TT_BOOK") or "", pid)         # the VaR batch's draw: the same one-sigma P&L
        risk = F.BASE_RISK.get(self.group.lower(), 150_000) * r.lognormvariate(0.0, 0.55)
        self.scale = self.net / gross * risk                            # one-sigma P&L per unit of loading, signed
        self.years = years_between(cob, t0.get("MATURITY_DATE"))
        self.loads = F.loadings(self.group, self.ptype, t0.get("INSTRUMENT"), self.ccy)


def _ccy(pos: Position, known, fallback: str) -> str:
    if pos.ccy in known:
        return pos.ccy
    em = [c for c in D.EM_CCYS if c in known]
    return em[_h(pos.pid, "em") % len(em)] if fallback == "EM" and em else fallback


def _issuer(pos: Position, quality: str) -> tuple[str, str]:
    """The issuer (or index) a credit position is on, and BOND or CDS."""
    kind = "CDS" if any(w in pos.ptype for w in ("CDS", "FIRST_TO_DEFAULT", "CLN", "TRANCHE")) else "BOND"
    if "INDEX" in pos.ptype or "TRANCHE" in pos.ptype:
        idx = [i for i, (_, q) in D.CREDIT_INDICES.items() if q == quality]
        return idx[_h(pos.pid, "idx") % len(idx)], "CDS"
    names = [i for i, _, q in D.issuers() if q == quality]
    return names[_h(pos.pid, "issuer") % len(names)], kind


def _equity(pos: Position, region: str) -> str:
    if "INDEX" in pos.ptype or pos.group.lower() == "structured":
        return D.REGION_INDEX[region]
    names = [u for u, v in D.EQUITIES.items() if v[0] == region and not v[6]]
    return names[_h(pos.pid, "eq") % len(names)] if names else D.REGION_INDEX[region]


def _commodity(pos: Position, sector: str) -> str:
    p = pos.ptype.upper() + " " + str(pos.t0.get("INSTRUMENT") or "").upper()
    if "FREIGHT" in p:
        return "FREIGHT"
    if "EMISSION" in p:
        return "EUA"
    if "GAS" in p:
        return "NG"
    names = D.SECTOR_COMMODITIES[sector]
    return names[_h(pos.pid, "cm") % len(names)]


def elements(pos: Position) -> list[dict]:
    """The sparse list of a position's sensitivities (VALUE in USD, rounded to cents)."""
    acc: dict[tuple[str, str], float] = defaultdict(float)
    extra: dict[tuple[str, str], dict] = {}
    curv: list[tuple[str, str, float, float]] = []          # (curve factor, class, linear P&L of the shock, shock size)
    underlyings: dict[str, str] = {}                        # VaR class -> the factor qualifier a vol loading attaches to

    def ladder(prefix: str, tenors: dict, measure: str, total: float) -> None:
        for t, w in D.nearest_two(tenors, pos.years):
            acc[(f"{prefix}/{t}", measure)] += total * w

    for f, w in sorted(pos.loads.items()):
        one_sigma = pos.scale * w
        head, _, part = f.partition(".")
        if head == "IR":
            ccy = _ccy(pos, D.CURVES, "EM" if part == "EM" else part)
            curve = D.CURVES[ccy][0]
            dv01 = -one_sigma / DAILY_SIGMA["IR"]
            ladder(f"IR/CURVE/{ccy}/{curve}", D.GIRR_TENORS, "DV01", dv01)
            underlyings["IR"] = f"{ccy}/{curve}"
            if pos.option:
                curv.append((f"IR/CURVE/{ccy}", "GIRR", dv01 * CURVATURE_SHOCK["GIRR"], CURVATURE_SHOCK["GIRR"]))
        elif head == "INFL":
            index = D.INFLATION.get(part, "USCPI")
            ladder(f"INF/{index}", D.INF_TENORS, "IE01", -one_sigma / DAILY_SIGMA["INF"])
        elif head == "CR":
            iss, kind = _issuer(pos, "IG" if part == "IG" else "HY")
            cs01 = -one_sigma / DAILY_SIGMA.get(f, DAILY_SIGMA["CR.HY"])
            ladder(f"CR/SPREAD/{iss}/{kind}", D.CSR_TENORS, "CS01", cs01)
            underlyings["CR"] = iss
            if pos.option:
                rw = CSR_RW[int(D.by_name()[f"CR/SPREAD/{iss}"]["FRTB_BUCKET"])]
                curv.append((f"CR/SPREAD/{iss}", "CSR_NS", cs01 * rw * 1e4, rw * 1e4))
        elif head == "FX":
            ccy = part if part in D.FX_CCYS else _ccy(pos, D.FX_CCYS, "EM")
            delta = one_sigma / DAILY_SIGMA["FX"]
            acc[(f"FX/SPOT/{ccy}", "DELTA")] += delta
            underlyings["FX"] = ccy
            if pos.option:
                curv.append((f"FX/SPOT/{ccy}", "FX", delta * CURVATURE_SHOCK["FX"] * 100, CURVATURE_SHOCK["FX"]))
        elif head == "EQ":
            und = _equity(pos, f)
            delta = one_sigma / DAILY_SIGMA["EQ"]
            acc[(f"EQ/SPOT/{und}", "DELTA")] += delta
            underlyings["EQ"] = und
            if pos.option:
                rw = EQ_RW[D.EQUITIES[und][1]]
                curv.append((f"EQ/SPOT/{und}", "EQ", delta * rw * 100, rw))
        elif head == "CO":
            und = _commodity(pos, f)
            loc = D.COMMODITIES[und][0]
            delta = one_sigma / DAILY_SIGMA["CO"]
            ladder(f"CM/PRICE/{und}/{loc}", D.CM_TENORS, "DELTA", delta)
            underlyings["CM"] = und
            if pos.option:
                rw = CM_RW[D.COMMODITIES[und][2]]
                curv.append((f"CM/PRICE/{und}", "COMM", delta * rw * 100, rw))
        elif head == "VOL":
            expiry = D.nearest_two(D.VEGA_EXPIRIES, pos.years)
            vega = one_sigma / DAILY_SIGMA["VOL"]
            if part == "IR":
                base = underlyings.get("IR") or f"{_ccy(pos, D.CURVES, 'USD')}/{D.CURVES[_ccy(pos, D.CURVES, 'USD')][0]}"
                names, cls = [(f"VOL/IR/{base}/{e}/10Y", s) for e, s in expiry], "GIRR"
            elif part == "FX":
                names, cls = [(f"VOL/FX/{underlyings.get('FX', 'EUR')}/{e}", s) for e, s in expiry], "FX"
            elif "CM" in underlyings:                        # an option on a commodity (CALLNOT's equity-like vol)
                names, cls = [(f"VOL/CM/{underlyings['CM']}/{e}", s) for e, s in expiry], "COMM"
            else:
                names, cls = [(f"VOL/EQ/{underlyings.get('EQ', 'SPX')}/{e}", s) for e, s in expiry], "EQ"
            for name, share in names:
                acc[(name, "VEGA")] += vega * share
                extra[(name, "VEGA")] = {"IMPLIED_VOL": IMPLIED_VOL[cls]}
    if pos.option:                                          # the VaR model's gamma: short for a long quantity, long for a short one
        g = 0.08 + 0.27 * (_h(pos.pid, "gamma") % 1000) / 1000.0
        for name, cls, linear, shock in curv:
            h = (1 if pos.net >= 0 else -1) * g * abs(linear)
            acc[(name, "CURVATURE_UP")] += linear - h
            acc[(name, "CURVATURE_DOWN")] += -linear - h
            extra[(name, "CURVATURE_UP")] = extra[(name, "CURVATURE_DOWN")] = {"SHOCK_SIZE": shock}
    dictionary = D.by_name()
    out = []
    for (name, measure), v in sorted(acc.items()):
        if abs(v) < 0.005:
            continue
        fac = dictionary[name]
        el = {"RISK_FACTOR": name, "MEASURE": measure, "VALUE": round(v, 2), "CURRENCY": "USD", "RISK_CLASS": fac["RISK_CLASS"],
              "FRTB_BUCKET": fac["FRTB_BUCKET"], "TENOR": fac["TENOR"], "SIMM_BUCKET": fac["SIMM_BUCKET"]}
        el.update(extra.get((name, measure), {}))
        out.append(el)
    return out


def positions(terms: list[dict], rules: "P.Rules") -> dict[str, list[dict]]:
    """position id -> its trades, derived as the server derives them (the pack's rules over the trades' terms)."""
    out: dict[str, list[dict]] = defaultdict(list)
    for t in terms:
        if not rules.is_live(t.get("TRADE_STATUS")):
            continue
        d = rules.derive(str(t["TRADE_ID"]), t.get("TT_BOOK") or "", t.get("PRODUCT_GROUP"), t.get("PRODUCT_TYPE"), lambda k: t.get(k))
        out[d["POSITION_ID"]].append(t)
    return dict(sorted(out.items()))


def records(terms: list[dict], cob: str, rules: "P.Rules | None" = None) -> list[dict]:
    """The position-sensitivity records of the date: one per position, or, from a trade-grain system (TRADE_GRAIN_SYSTEMS), one per
    trade, a position's sensitivities shared among its trades by quantity (they add up to the position's)."""
    rules = rules or P.Rules.of_pack(PACK_YAML)
    batch = f"B-SENS-{cob}-01"
    out = []
    for pid, trades in positions(terms, rules).items():
        pos = Position(pid, trades, cob)
        els = elements(pos)
        if pos.t0.get("SOURCE_SYSTEM") not in TRADE_GRAIN_SYSTEMS:
            out.append(_record(pos, pid, "POSITION", trades, els, batch, cob))
            continue
        for t in trades:
            q = float(t.get("QUANTITY") or 0.0)
            share = q / pos.net if pos.net else 1.0 / len(trades)
            mine = [{**e, "VALUE": round(e["VALUE"] * share, 2)} for e in els] if len(trades) > 1 else els
            out.append(_record(pos, str(t["TRADE_ID"]), "TRADE", [t], [e for e in mine if e["VALUE"] != 0], batch, cob))
    return out


def _record(pos: Position, ident: str, grain: str, trades: list[dict], els: list[dict], batch: str, cob: str) -> dict:
    t0 = trades[0]
    proxied = _h(pos.pid, "proxy") % PROXIED_EVERY == 0
    rec = {"SENSITIVITY_ID": f"SENS-{ident}-{batch}", "GRAIN": grain, "POSITION_ID": pos.pid}
    if grain == "TRADE":
        rec["TRADE_ID"] = ident
    rec.update({"TRADE_IDS": sorted(str(t["TRADE_ID"]) for t in trades), "SOURCE_SYSTEM": t0.get("SOURCE_SYSTEM"), "BUSINESS_DATE": cob,
                "BATCH_ID": batch, "RUN_KIND": "FULL", "COMPUTED_AT": f"{cob}T06:10:00Z", "RESTATEMENT": 0,
                "STATUS": "proxied" if proxied else "ok"})
    if proxied:
        rec["PROXY_REASON"] = "volatility surface missing on the date: the underlying's index surface used"
    rec.update({"PRICER": "demo-factor-model", "MODEL_VERSION": "demo-sens-1", "LEGAL_ENTITY": t0.get("LEGAL_ENTITY"),
                "DIVISION": t0.get("DIVISION"), "DESK": t0.get("DESK"), "TT_BOOK": t0.get("TT_BOOK"),
                "LIMIT_LETTER": t0.get("LIMIT_LETTER"), "FRTB_DESK": "FD-" + str(t0.get("DESK") or "").removeprefix("DESK-"),
                "PRODUCT_GROUP": pos.group, "PRODUCT_TYPE": pos.ptype, "TRADE_STATUS": t0.get("TRADE_STATUS"),
                "MATURITY_DATE": t0.get("MATURITY_DATE"), "CURRENCY": pos.ccy, "SENSITIVITIES": els})
    return rec


def dictionary_rows(cob: str) -> list[dict]:
    return [{**r, "VALID_FROM": "2026-01-02", "BUSINESS_DATE": cob, "SOURCE": "demo-factor-master"} for r in D.factors()]


def dumps(d: dict) -> str:
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False)


def gz_bytes(rows: list[dict]) -> bytes:
    import io
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        gz.write(("\n".join(dumps(r) for r in rows) + "\n").encode("utf-8"))
    return buf.getvalue()


def files(testdata: Path) -> dict[Path, bytes]:
    """The files to write, relative to the test-data folder."""
    cob = cob_of(testdata)
    return {Path(PACK) / "position-sensitivity" / f"cob-{cob}.jsonl.gz": gz_bytes(records(read_terms(testdata, cob), cob)),
            Path(PACK) / "risk-factor" / f"cob-{cob}.jsonl.gz": gz_bytes(dictionary_rows(cob))}


def write(out: Path, testdata: Path | None = None) -> list[Path]:
    """Writes the two folders under ``out`` (their earlier files removed); returns the files."""
    written = []
    for rel, data in files(testdata or out).items():
        folder = (out / rel).parent
        folder.mkdir(parents=True, exist_ok=True)
        for old in folder.glob("*.jsonl.gz"):
            old.unlink()
        (out / rel).write_bytes(data)
        written.append(out / rel)
    return written


#: the testdata.yaml entry of each kind: its key (the lake's business key)
KEYS = {"position-sensitivity": "SENSITIVITY_ID", "risk-factor": "RISK_FACTOR"}


def datasets(out: Path, written: list[Path]) -> list[dict]:
    """The testdata.yaml entries of the files just written (read by `drishti.py lake demo` into risk-store, as every risk kind)."""
    entries = []
    for f in sorted(written, key=lambda x: x.parent.name):
        kind = f.parent.name
        cob = f.name[4:14]
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            documents = sum(1 for _ in fh)
        entries.append({"folder": f"{PACK}/{kind}", "pack": PACK, "kind": kind, "connector": "risk-store", "key": KEYS[kind],
                        "dated-by": "BUSINESS_DATE", "first-date": cob, "last-date": cob, "documents": documents, "files": [f.name],
                        "bytes": f.stat().st_size})
    return entries


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="make_sensitivity_data.py", description=__doc__.split("\n\n")[0])
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the committed test data to read (default data/testdata)")
    p.add_argument("--out", help="where to write (default --testdata)")
    p.add_argument("--check", action="store_true", help="fail if the committed files differ from what would be written")
    a = p.parse_args(argv)
    td = Path(a.testdata)
    if a.check:
        bad = [str(rel) for rel, data in files(td).items() if not (td / rel).is_file() or (td / rel).read_bytes() != data]
        print("\n".join(f"stale: {b}" for b in bad) or "position-sensitivity and risk-factor current")
        return 1 if bad else 0
    for f in write(Path(a.out) if a.out else td, td):
        print(f"wrote {f} ({f.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
