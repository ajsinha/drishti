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

"""Writes the SA-CCR engine's demo input, ``saccr-trade`` (docs/contracts/SACCR.md section 3.3; docs/guides/SACCR_GUIDE.md section 3),
into data/testdata/counterparty-risk/saccr-trade/, as a bank's capital process would deliver it: one classification row per live
derivative of every netting set on the business date (and a second row, DRIVER 1, for the FX leg of a cross-currency swap and the sold
floor of a collar), derived by the rules below from the trade valuations (``PRODUCT_TYPE``, ``CURRENCY``, ``NOTIONAL``,
``MATURITY_DATE``, ``BUY_SELL_CODE``): the shipped example of ``drishti.ccr.saccr.derive: rules``, so every row says ``DERIVED_BY: rules``.
Securities financing, cash products, bonds, loans and funded notes get no row: they are not derivatives under CRE52 and the engine counts
them excluded. A few derivatives are left without a row on purpose (UNCLASSIFIED below) so the demo shows an incomplete set. No figure is
written: every supervisory duration, delta, add-on and EAD is the engine's, computed when asked.

The option terms a booking system does not carry (the strike against the forward, a swaption's exercise date, a call or a put) are drawn
from a seeded generator (Python's Random seeded by the trade id), so the same trades give the same rows, byte for byte (gzip mtime 0).

    python3 tools/packgen/ccr/make_saccr_data.py            write saccr-trade, its testdata.yaml entry, the pack sample and the
                                                            engine's parity reference (drishti-compute/src/test/resources/saccr/reference.json)
    python3 tools/packgen/ccr/make_saccr_data.py --check    fail if they differ from what would be written
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / "tools" / "packgen" / "var"))
import saccrmath as S  # noqa: E402
from positions import LIVE, replay  # noqa: E402  (a dataset's documents on its last day; the live trade statuses, as VaR's)

PACK, KIND, CONNECTOR, KEY = "counterparty-risk", "saccr-trade", "exposure-store", "SACCR_ROW_ID"
FOLDER = f"{PACK}/{KIND}"
REFERENCE = ROOT / "drishti-compute" / "src" / "test" / "resources" / "saccr" / "reference.json"
SAMPLE = ROOT / "config" / "packs" / PACK / "samples" / f"{KIND}.jsonl"
#: derivatives deliberately left without a row: the demo's incomplete set (one trade in one set)
UNCLASSIFIED = {"NS-AURORA-LDN"}    # the first derivative of these sets gets no row (the demo's incomplete set)

# PRODUCT_TYPE -> (ASSET_CLASS, TRANSACTION_TYPE, rule). Rules: "ccy" IR hedging set the currency; "pair" FX pair with USD; "basis" an IR
# basis set; "fxvol"/"eqvol" volatility sets; "single"/"index" credit or equity reference; "energy", "power", "metals", "agri", "other"
# commodity hedging sets; "swaption" an option on a swap (S = T = exercise, E = M); "cap"/"floor" options on a rate; "collar" a bought cap
# and a sold floor (two drivers); "xccy" IR in the currency plus the FX leg (two drivers); "fwd" a forward-starting period.
RULES: dict[str, tuple[str, str, str]] = {
    "IRS_FIXFLOAT": ("IR", "LINEAR", "ccy"), "OIS": ("IR", "LINEAR", "ccy"), "ZC_SWAP": ("IR", "LINEAR", "ccy"), "AMORT_SWAP": ("IR", "LINEAR", "ccy"),
    "ACCRETING_SWAP": ("IR", "LINEAR", "ccy"), "CALLABLE_SWAP": ("IR", "LINEAR", "ccy"), "SNOWBALL_SWAP": ("IR", "LINEAR", "ccy"),
    "RANGE_ACCRUAL_SWAP": ("IR", "LINEAR", "ccy"), "TARN_RATES": ("IR", "LINEAR", "ccy"), "CMS_SWAP": ("IR", "LINEAR", "ccy"),
    "FRA": ("IR", "LINEAR", "fwd"), "FWD_START_SWAP": ("IR", "LINEAR", "fwd"), "STIR_FUTURE": ("IR", "LINEAR", "fwd"), "BOND_FUTURE": ("IR", "LINEAR", "ccy"),
    "BASIS_SWAP": ("IR", "BASIS", "basis"), "XCCY_SWAP": ("IR", "LINEAR", "xccy"), "XCCY_SWAP_MTM": ("IR", "LINEAR", "xccy"),
    "CAP": ("IR", "OPTION", "cap"), "FLOOR": ("IR", "OPTION", "floor"), "COLLAR": ("IR", "OPTION", "collar"), "CMS_CAP": ("IR", "OPTION", "cap"),
    "CMS_SPREAD_OPT": ("IR", "OPTION", "cap"), "STIR_FUT_OPT": ("IR", "OPTION", "cap"), "SWAPTION_EUR": ("IR", "OPTION", "swaption"),
    "SWAPTION_AMER": ("IR", "OPTION", "swaption"), "SWAPTION_BERM": ("IR", "OPTION", "swaption"),
    "ZC_INFL_SWAP": ("IR", "LINEAR", "ccy"), "YOY_INFL_SWAP": ("IR", "LINEAR", "ccy"), "LPI_SWAP": ("IR", "LINEAR", "ccy"),
    "INFL_ASSET_SWAP": ("IR", "LINEAR", "ccy"), "INFL_CAP_FLOOR": ("IR", "OPTION", "cap"),
    "FX_FORWARD": ("FX", "LINEAR", "pair"), "FX_SWAP": ("FX", "LINEAR", "pair"), "FX_NDF": ("FX", "LINEAR", "pair"), "FX_TARF": ("FX", "LINEAR", "pair"),
    "FX_ACCUMULATOR": ("FX", "LINEAR", "pair"), "FX_OPTION": ("FX", "OPTION", "pair"), "FX_ASIAN": ("FX", "OPTION", "pair"),
    "FX_BARRIER": ("FX", "OPTION", "pair"), "FX_DIGITAL": ("FX", "OPTION", "pair"), "FX_DOUBLE_BARRIER": ("FX", "OPTION", "pair"),
    "FX_TOUCH": ("FX", "OPTION", "pair"), "FX_QUANTO": ("FX", "OPTION", "pair"), "FX_VARIANCE_SWAP": ("FX", "VOLATILITY", "fxvol"),
    "CDS_SINGLE": ("CREDIT", "LINEAR", "single"), "TRS_BOND": ("CREDIT", "LINEAR", "single"), "FIRST_TO_DEFAULT": ("CREDIT", "LINEAR", "single"),
    "CDS_INDEX": ("CREDIT", "LINEAR", "index"), "TRS_LOAN_INDEX": ("CREDIT", "LINEAR", "index"), "CDS_TRANCHE": ("CREDIT", "CDO_TRANCHE", "index"),
    "CDS_SWAPTION": ("CREDIT", "OPTION", "index"), "CREDIT_SPREAD_OPT": ("CREDIT", "OPTION", "single"),
    "EQ_FORWARD": ("EQUITY", "LINEAR", "single"), "EQ_FUTURE_SINGLE": ("EQUITY", "LINEAR", "single"), "EQ_TRS": ("EQUITY", "LINEAR", "single"),
    "CFD": ("EQUITY", "LINEAR", "single"), "DIVIDEND_SWAP": ("EQUITY", "LINEAR", "index"), "PORTFOLIO_SWAP": ("EQUITY", "LINEAR", "index"),
    "EQ_INDEX_FUTURE": ("EQUITY", "LINEAR", "index"), "EQ_OPTION_OTC": ("EQUITY", "OPTION", "single"), "EQ_OPTION_LISTED": ("EQUITY", "OPTION", "single"),
    "EQ_ASIAN": ("EQUITY", "OPTION", "single"), "EQ_BARRIER": ("EQUITY", "OPTION", "single"), "EQ_DIGITAL": ("EQUITY", "OPTION", "single"),
    "EQ_CLIQUET": ("EQUITY", "OPTION", "index"), "WARRANT": ("EQUITY", "OPTION", "single"), "EQ_INDEX_OPTION": ("EQUITY", "OPTION", "index"),
    "EQ_BASKET_OPTION": ("EQUITY", "OPTION", "index"), "EQ_VARIANCE_SWAP": ("EQUITY", "VOLATILITY", "eqvol"),
    "EQ_VOL_SWAP": ("EQUITY", "VOLATILITY", "eqvol"),
    "ComSwap": ("COMMODITY", "LINEAR", "energy"), "ComFut": ("COMMODITY", "LINEAR", "energy"), "CMD_SWAP": ("COMMODITY", "LINEAR", "energy"),
    "CMD_FORWARD": ("COMMODITY", "LINEAR", "metals"), "CMD_FUTURE": ("COMMODITY", "LINEAR", "agri"), "CMD_INDEX_SWAP": ("COMMODITY", "LINEAR", "energy"),
    "CMD_CALENDAR_SPREAD": ("COMMODITY", "LINEAR", "energy"), "POWER_SWAP": ("COMMODITY", "LINEAR", "power"),
    "CMD_BASIS_SWAP": ("COMMODITY", "BASIS", "energy"), "CMD_APO": ("COMMODITY", "OPTION", "energy"), "CMD_FUT_OPTION": ("COMMODITY", "OPTION", "metals"),
    "CMD_SPREAD_OPT": ("COMMODITY", "OPTION", "energy"), "CMD_SWAPTION": ("COMMODITY", "OPTION", "energy"), "GAS_SWING": ("COMMODITY", "OPTION", "energy"),
    "EMISSIONS_FUTURE": ("COMMODITY", "LINEAR", "other"), "FREIGHT_FFA": ("COMMODITY", "LINEAR", "other"), "WEATHER_HDD": ("COMMODITY", "LINEAR", "other"),
}
TYPES = {"energy": ("ENERGY", ["CRUDE_OIL", "NATURAL_GAS", "REFINED_PRODUCTS"]), "power": ("ENERGY", ["ELECTRICITY"]),
         "metals": ("METALS", ["GOLD", "SILVER", "COPPER"]), "agri": ("AGRICULTURAL", ["WHEAT", "CORN", "SOYBEANS"]),
         "other": ("OTHER", ["CARBON_EMISSIONS", "FREIGHT", "WEATHER"])}
FOREIGN = ["EUR", "JPY", "GBP", "CAD", "CHF", "AUD"]
EQ_INDICES = ["EQX-SPX", "EQX-NDX", "EQX-SX5E", "EQX-NKY", "EQX-UKX"]
EQ_SINGLES = ["EQ-NVTK", "EQ-ORBT", "EQ-MRDN", "EQ-SLRS", "EQ-GRNT", "EQ-HRBR", "EQ-KSTR", "EQ-AURA"]
CREDIT_INDICES = [("IDX-CDX-IG", "IG"), ("IDX-ITRAXX-MAIN", "IG"), ("IDX-CDX-HY", "SG"), ("IDX-ITRAXX-XOVER", "SG")]


def quality(rating: str) -> str:
    r = (rating or "BBB").rstrip("+-").upper()
    return r if r in S.CREDIT_SINGLE else "BBB"


class Inputs:
    """The committed data of the business date: live trade valuations by netting set, netting sets, CSAs, collateral, issuers, FX."""

    def __init__(self, testdata: Path, dates: list[str]):
        self.cob = dates[-1]
        self.vals = replay(testdata / "market-risk" / "trade-valuation", "TRADE_ID", dates)
        self.sets = replay(testdata / PACK / "netting-set", "NETTING_SET_ID", dates)
        self.csas = replay(testdata / PACK / "csa", "CSA_ID", dates)
        self.balances = replay(testdata / PACK / "collateral-balance", "BALANCE_ID", dates)
        issuers = replay(testdata / "core" / "issuer", "ISSUER_ID", dates)
        self.issuers = sorted((i.get("LEI") or k, quality(i.get("RATING"))) for k, i in issuers.items() if i.get("SECTOR") != "Sovereign")
        self.usd_per = {"USD": 1.0}
        for p in replay(testdata / "market-data" / "fx-spot", "PAIR", dates).values():
            pair, mid = str(p.get("PAIR", "")).replace("FX-", ""), float(p.get("MID") or 0)
            if len(pair) == 6 and mid > 0:
                if pair.endswith("USD"):
                    self.usd_per[pair[:3]] = mid
                elif pair.startswith("USD"):
                    self.usd_per[pair[3:]] = 1 / mid

    def live(self, v: dict) -> bool:
        return str(v.get("TRADE_STATUS") or "").upper() in LIVE and bool(v.get("NETTING_SET")) and str(v.get("MATURITY_DATE") or "9999") >= self.cob

    def members(self) -> dict[str, list[dict]]:
        out: dict[str, list[dict]] = {}
        for tid in sorted(self.vals):
            v = self.vals[tid]
            if self.live(v):
                out.setdefault(v["NETTING_SET"], []).append(v)
        return out


def row_id(tid: str, driver: int) -> str:
    return f"{tid}~{driver}"


def rows_of(v: dict, inp: Inputs) -> list[dict]:
    """The classification rows of one live trade valuation ([] for a non-derivative)."""
    rule = RULES.get(str(v.get("PRODUCT_TYPE")))
    if rule is None:
        return []
    ac, tt, how = rule
    tid = str(v["TRADE_ID"])
    rnd = random.Random(f"saccr|{tid}")
    cob = date.fromisoformat(inp.cob)
    mat = date.fromisoformat(str(v["MATURITY_DATE"])[:10])
    ccy = str(v.get("CURRENCY") or "USD").upper()
    notional_usd = round(float(v.get("NOTIONAL") or 0) * inp.usd_per.get(ccy, 1.0), 2)
    buy = str(v.get("BUY_SELL_CODE") or "B").upper().startswith("B")
    base = {"SACCR_ROW_ID": row_id(tid, 0), "BUSINESS_DATE": inp.cob, "TRADE_ID": tid, "SOURCE_SYSTEM": v.get("SOURCE_SYSTEM"), "DRIVER": 0,
            "NETTING_SET": v.get("NETTING_SET"), "COUNTERPARTY": v.get("COUNTERPARTY"),
            "LEGAL_ENTITY": (inp.sets.get(v.get("NETTING_SET")) or {}).get("LEGAL_ENTITY"), "ASSET_CLASS": ac, "TRANSACTION_TYPE": tt,
            "DIRECTION": "LONG" if buy else "SHORT", "NOTIONAL_USD": notional_usd, "MATURITY_DATE": mat.isoformat(), "CLEARED": False,
            "DERIVED_BY": "rules", "PRODUCT_TYPE": v.get("PRODUCT_TYPE"), "BATCH_ID": f"SB-{inp.cob}-01"}
    days = max((mat - cob).days, 1)
    if ac in ("IR", "CREDIT"):
        base["START_DATE"], base["END_DATE"] = inp.cob, mat.isoformat()
    if how == "fwd":
        start = cob + timedelta(days=int(days * rnd.uniform(0.1, 0.5)))
        base["START_DATE"] = start.isoformat()
    if ac == "IR":
        base["HEDGING_SET"] = f"{ccy}-BASIS" if how == "basis" else ccy
    elif ac == "FX":
        other = rnd.choice(FOREIGN) if ccy == "USD" else ccy
        pair = "".join(sorted([other, "USD"]))
        base["HEDGING_SET"] = f"FX-VOL-{pair}" if how == "fxvol" else pair
    elif ac == "CREDIT":
        base["HEDGING_SET"] = "CREDIT"
        if how == "index":
            ref, q = rnd.choice(CREDIT_INDICES)
            base.update(REFERENCE=ref, IS_INDEX=True, CREDIT_QUALITY=q)
        else:
            ref, q = rnd.choice(inp.issuers) if inp.issuers else ("LEI-DEMO", "BBB")
            base.update(REFERENCE=ref, IS_INDEX=False, CREDIT_QUALITY=q)
        base["DIRECTION"] = "SHORT" if buy else "LONG"           # bought protection is short credit quality
    elif ac == "EQUITY":
        base["HEDGING_SET"] = "EQUITY-VOL" if how == "eqvol" else "EQUITY"
        idx = how in ("index", "eqvol") and how != "single"
        base.update(REFERENCE=rnd.choice(EQ_INDICES if idx else EQ_SINGLES), IS_INDEX=idx)
    else:
        hs, types = TYPES["power" if how == "power" else how]
        base.update(HEDGING_SET=f"{hs}-BASIS" if tt == "BASIS" else hs, REFERENCE=rnd.choice(types))
    if tt == "OPTION":
        base.update(BOUGHT_SOLD="BOUGHT" if buy else "SOLD", OPTION_TYPE="PUT" if how == "floor" else "CALL" if how == "cap" else rnd.choice(["CALL", "PUT"]))
        base.pop("DIRECTION")
        if ac in ("IR",) or how == "swaption":
            fwd = round(rnd.uniform(0.02, 0.045), 4)
            base["UNDERLYING_FORWARD"], base["STRIKE"] = fwd, round(fwd + rnd.choice([-0.005, -0.0025, 0, 0.0025, 0.005]), 4)
            if ccy in ("EUR", "CHF", "JPY"):
                base["SHIFT"] = 0.01
        elif ac == "CREDIT":
            fwd = round(rnd.uniform(0.006, 0.03), 4)
            base["UNDERLYING_FORWARD"], base["STRIKE"] = fwd, round(fwd * rnd.uniform(0.8, 1.25), 4)
        else:
            fwd = round(rnd.uniform(20, 500), 2) if ac != "FX" else round(rnd.uniform(0.6, 1.6), 4)
            base["UNDERLYING_FORWARD"], base["STRIKE"] = fwd, round(fwd * rnd.uniform(0.85, 1.15), 4 if ac == "FX" else 2)
        if how == "swaption":
            ex = cob + timedelta(days=max(1, int(days * rnd.uniform(0.15, 0.4))))
            base["EXERCISE_DATE"], base["START_DATE"] = ex.isoformat(), ex.isoformat()
        else:
            base["EXERCISE_DATE"] = max(mat, cob + timedelta(days=1)).isoformat()
    if tt == "CDO_TRANCHE":
        a = rnd.choice([0.0, 0.03, 0.07, 0.15])
        base.update(ATTACHMENT=a, DETACHMENT={0.0: 0.03, 0.03: 0.07, 0.07: 0.15, 0.15: 1.0}[a], BOUGHT_SOLD="BOUGHT" if buy else "SOLD")
        base.pop("DIRECTION")
    rows = [base]
    if how == "xccy":                                             # the FX leg: the second driver
        other = rnd.choice(FOREIGN) if ccy == "USD" else ccy
        rows.append({**{k: base[k] for k in ("BUSINESS_DATE", "TRADE_ID", "SOURCE_SYSTEM", "NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY",
                                               "NOTIONAL_USD", "MATURITY_DATE", "CLEARED", "DERIVED_BY", "PRODUCT_TYPE", "BATCH_ID")},
                     "SACCR_ROW_ID": row_id(tid, 1), "DRIVER": 1, "ASSET_CLASS": "FX", "HEDGING_SET": "".join(sorted([other, "USD"])),
                     "TRANSACTION_TYPE": "LINEAR", "DIRECTION": "SHORT" if buy else "LONG"})
    if how == "collar":                                           # the sold floor: the second driver
        rows.append({**base, "SACCR_ROW_ID": row_id(tid, 1), "DRIVER": 1, "OPTION_TYPE": "PUT", "BOUGHT_SOLD": "SOLD" if buy else "BOUGHT",
                     "STRIKE": round(base["UNDERLYING_FORWARD"] - 0.01, 4)})
    return rows


def years(cob: date, d: str | None) -> float | None:
    return None if not d else (date.fromisoformat(d[:10]) - cob).days / 365.0


def header(inp: Inputs, ns: str, members: list[dict], classified: set[str]) -> dict:
    """The netting set's SA-CCR header by the server's rules (SaccrData): V over classified members, C and NICA, the CSA's terms."""
    s = inp.sets.get(ns) or {}
    csa = inp.csas.get(s.get("CSA") or "") or {}
    bal = inp.balances.get(s.get("COLLATERAL_BALANCE") or "") or {}
    nica = float(csa.get("INDEPENDENT_AMOUNT") or 0) + float(bal.get("IM_HELD") or 0) - float(bal.get("IM_POSTED_UNSEGREGATED") or 0)
    th, mta = csa.get("THRESHOLD_THEM"), csa.get("MTA_THEM", csa.get("MTA"))
    margined = "VM" in str(csa.get("MARGIN_TYPE") or "") and th is not None and mta is not None and csa.get("MPOR_DAYS") is not None
    return {"V": math.fsum(float(m.get("MTM") or 0) for m in members if str(m["TRADE_ID"]) in classified),
            "C": float(bal.get("NET") or 0) + nica, "NICA": nica, "TH": float(th or 0), "MTA": float(mta or 0), "MARGINED": margined,
            "CLEARED": bool(s.get("CLEARED")), "MPOR_DAYS": int(float(csa.get("MPOR_DAYS") or 0)),
            "MARGIN_FREQUENCY_DAYS": int(float(csa.get("MARGIN_FREQUENCY_DAYS") or 1)), "DISPUTES": int(float(csa.get("DISPUTES_LAST_2Q") or 0)),
            "ILLIQUID": bool(csa.get("ILLIQUID_COLLATERAL")), "TRADES_IN_SET": len(members)}


def engine_trade(r: dict, cob: date, mtm: float | None) -> dict:
    t = dict(r)
    t.update(S=years(cob, r.get("START_DATE")) or 0.0, E=years(cob, r.get("END_DATE")) or years(cob, r["MATURITY_DATE"]), M=years(cob, r["MATURITY_DATE"]),
             T=years(cob, r.get("EXERCISE_DATE")), D_NOTIONAL=r["NOTIONAL_USD"], MTM=mtm)
    if t.get("TRANSACTION_TYPE") in ("OPTION", "CDO_TRANCHE") and "DIRECTION" not in t:
        t["DIRECTION"] = "LONG"
    return t


def build(inp: Inputs) -> tuple[list[dict], dict]:
    """Every row of the date and the engine's reference figures of every netting set."""
    cob = date.fromisoformat(inp.cob)
    rows: list[dict] = []
    ref: dict = {"businessDate": inp.cob, "regulation": "BCBS 1.0", "sets": {}}
    for ns, members in sorted(inp.members().items()):
        set_rows: list[dict] = []
        skipped = None
        for m in members:
            rs = rows_of(m, inp)
            if rs and ns in UNCLASSIFIED and skipped is None:
                skipped = str(m["TRADE_ID"])
                continue
            set_rows += rs
        rows += set_rows
        classified = {r["TRADE_ID"] for r in set_rows}
        mtm = {str(m["TRADE_ID"]): float(m.get("MTM") or 0) for m in members}
        h = header(inp, ns, members, classified)
        trades = [engine_trade(r, cob, mtm.get(r["TRADE_ID"])) for r in set_rows]
        if not trades:                                            # no derivative: SA-CCR has nothing to measure (its collateral is the SFTs')
            ref["sets"][ns] = {"header": h, "rows": 0, "unclassified": [skipped] if skipped else [], "excluded": len(members), "EAD": 0.0,
                               "STATUS": "NO_DERIVATIVES"}
            continue
        res = S.ead(h, trades)
        ref["sets"][ns] = {"header": h, "rows": len(set_rows), "unclassified": [skipped] if skipped else [],
                           "excluded": sum(1 for m in members if str(m["TRADE_ID"]) not in classified) - (1 if skipped else 0),
                           "RC": res.get("RC_M", res["RC_U"]), "ADDON_U": res["ADDON_U"]["AGG"], "ADDON_M": res.get("ADDON_M", {}).get("AGG"),
                           "EAD_U": res["EAD_U"], "EAD_M": res.get("EAD_M"), "EAD": res["EAD"], "CAPPED": res["CAPPED"], "MPOR": res["MPOR"]}
    return rows, ref


def dumps(d: dict) -> str:
    return json.dumps(d, separators=(",", ":"), ensure_ascii=False)


def gz_bytes(lines: list[str]) -> bytes:
    import io
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
        gz.write(("\n".join(lines) + "\n").encode("utf-8"))
    return buf.getvalue()


def reference_text(ref: dict) -> str:
    def rounded(x):
        if isinstance(x, float):
            return round(x, 6)
        if isinstance(x, dict):
            return {k: rounded(v) for k, v in x.items()}
        return x
    return json.dumps(rounded(ref), indent=1, ensure_ascii=False) + "\n"


def entry(cob: str, n: int, name: str, size: int) -> dict:
    return {"folder": FOLDER, "pack": PACK, "kind": KIND, "connector": CONNECTOR, "key": KEY, "dated-by": "BUSINESS_DATE", "first-date": cob,
            "last-date": cob, "documents": n, "files": [name], "bytes": size}


def write_testdata(out: Path, dates: list[str], side_files: bool) -> list[dict]:
    """saccr-trade into a test-data folder whose valuations and netting sets were just written (tools/testdata/make_testdata.py)."""
    inp = Inputs(out, dates)
    rows, ref = build(inp)
    if not rows:
        return []
    folder = out / FOLDER
    folder.mkdir(parents=True, exist_ok=True)
    for old in folder.glob("*.jsonl.gz"):
        old.unlink()
    name = f"cob-{inp.cob}.jsonl.gz"
    data = gz_bytes([dumps(r) for r in rows])
    (folder / name).write_bytes(data)
    if side_files:
        REFERENCE.parent.mkdir(parents=True, exist_ok=True)
        REFERENCE.write_text(reference_text(ref), encoding="utf-8")
        SAMPLE.parent.mkdir(parents=True, exist_ok=True)
        SAMPLE.write_text("".join(dumps(r) + "\n" for r in rows[:3]), encoding="utf-8")
    return [entry(inp.cob, len(rows), name, len(data))]


def update_manifest(testdata: Path, entries: list[dict]) -> None:
    """testdata.yaml with this entry in place of an earlier one (after the pack's other datasets), its header and the rest kept."""
    import yaml
    path = testdata / "testdata.yaml"
    text = path.read_text(encoding="utf-8")
    head = []
    for ln in text.splitlines():
        if ln.startswith("#") or not ln.strip():
            head.append(ln)
        else:
            break
    m = yaml.safe_load(text)
    keep = [d for d in m["datasets"] if d.get("folder") != FOLDER]
    at = max((i for i, d in enumerate(keep) if d.get("pack") == PACK), default=len(keep) - 1) + 1
    m["datasets"] = keep[:at] + entries + keep[at:]
    path.write_text("\n".join(head) + "\n" + yaml.safe_dump(m, sort_keys=False, width=140), encoding="utf-8")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--testdata", default=str(ROOT / "data" / "testdata"), help="the test data the valuations and netting sets are read from")
    p.add_argument("--check", action="store_true", help="fail if the committed files differ from what would be written")
    a = p.parse_args(argv)
    import yaml
    td = Path(a.testdata)
    dates = yaml.safe_load((td / "testdata.yaml").read_text(encoding="utf-8"))["business-dates"]
    if a.check:
        rows, ref = build(Inputs(td, dates))
        f = td / FOLDER / f"cob-{dates[-1]}.jsonl.gz"
        stale = []
        if not f.is_file() or gzip.decompress(f.read_bytes()) != gzip.decompress(gz_bytes([dumps(r) for r in rows])):
            stale.append(str(f.relative_to(ROOT)) if f.is_relative_to(ROOT) else str(f))
        if not REFERENCE.is_file() or REFERENCE.read_text(encoding="utf-8") != reference_text(ref):
            stale.append(str(REFERENCE.relative_to(ROOT)))
        if stale:
            print(f"SA-CCR demo data out of date ({stale}); run tools/packgen/ccr/make_saccr_data.py")
            return 1
        print("SA-CCR demo data up to date")
        return 0
    entries = write_testdata(td, dates, True)
    update_manifest(td, entries)
    print(f"wrote {entries[0]['documents']:,} saccr-trade rows ({entries[0]['bytes'] / 1e3:.0f} kB) and the parity reference")
    return 0


if __name__ == "__main__":
    sys.exit(main())
