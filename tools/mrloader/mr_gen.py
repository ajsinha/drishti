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

"""Writes a synthetic day of the CSV loaders' inputs, one canonical CSV per kind, seeded, that hangs together: the same four legal
entities, six desks, twelve books and N positions appear in every file, so every link resolves (docs/guides/BANK_DATA_GUIDE.md section 16).
Made-up names and numbers only; nothing from any real extract. The committed samples (tools/mrloader/samples/) are this script's output
with the defaults; a test checks they still are.

examples (from the repository root):
  python3 tools/mrloader/mr_gen.py --day tools/mrloader/samples                     the committed sample day (20 positions, seed 7)
  python3 tools/mrloader/mr_gen.py --day data/mr/trial --positions 2000             a trial day of realistic size (never committed)

writes, under --day: legal-entity.csv, desk.csv, book.csv, risk-factor.csv, var-limit.csv, desk-pnl.csv, position-sensitivity.csv and its
child file position-sensitivity.SENSITIVITIES.csv; the exposure masters of wave 2 (counterparty-group, counterparty, agreement, csa
and csa.ELIGIBLE, netting-set, collateral-balance and collateral-balance.POSITIONS, credit-limit and credit-limit.BUCKETS,
trade-valuation); with --with-pvf the same day's PVF extract (portfolio_d2.csv, pos_<YYYYMMDD>.csv,
pvf_path_<YYYYMMDD>.csv), whose scenario PVs build the nodes the limits name.
The business date is --date (2026-11-18); the sample day file tools/mrloader/days/sample.yaml names each file's kind.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import random
import re
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import log, setup_logging, strict_date  # noqa: E402

#: the four legal entities, six desks (each booking in one entity) and their asset classes: the PVF generator's names
ENTITIES = [("GMO_SPOG", "Drishti Bank plc", "GB", "PRA / FCA"), ("CDADMM_H4705", "Drishti Bank Canada", "CA", "OSFI"),
            ("GLOBAL_EQ_FIN", "Drishti Global Equity Finance Inc.", "US", "SEC / FINRA"),
            ("MARKETS_INC", "Drishti Markets Inc.", "US", "SEC / CFTC")]
DESKS = [("RATES TRADING-LON", "Rates Trading London", "GMO_SPOG", "Rates"), ("FX OPTIONS-ZRH", "FX Options Zurich", "GMO_SPOG", "FX"),
         ("VOL POSITIONING-CRCHI-TOR", "Volatility Positioning Toronto", "CDADMM_H4705", "Equity|FX"),
         ("EQUITY SWAPS-NYC", "Equity Swaps New York", "GLOBAL_EQ_FIN", "Equity"),
         ("CREDIT FLOW-HKG", "Credit Flow Hong Kong", "MARKETS_INC", "Credit"),
         ("GLOBAL COMMODITIES-SGP", "Global Commodities Singapore", "MARKETS_INC", "Commodities")]
HEADS = ["A. Moreau", "B. Keller", "C. Tremblay", "D. Okafor", "E. Lam", "F. Tan"]
#: the risk factors the sample sensitivities use: (name, type, class, qualifier, curve, underlying, tenor, FRTB bucket, SIMM class,
#: SIMM bucket, SIMM label1, liquidity horizon, the source systems' names)
FACTORS = [
    ("IR/CURVE/USD/SOFR/2Y", "CURVE", "GIRR", "USD", "SOFR", "", "2Y", "USD", "InterestRate", "1", "2y", 10, "IR.CURVE.USD.SOFR.2Y"),
    ("IR/CURVE/USD/SOFR/5Y", "CURVE", "GIRR", "USD", "SOFR", "", "5Y", "USD", "InterestRate", "1", "5y", 10, "IR.CURVE.USD.SOFR.5Y|USD-SOFR-5Y"),
    ("IR/CURVE/USD/SOFR/10Y", "CURVE", "GIRR", "USD", "SOFR", "", "10Y", "USD", "InterestRate", "1", "10y", 10, "IR.CURVE.USD.SOFR.10Y"),
    ("IR/CURVE/USD", "CURVE", "GIRR", "USD", "", "", "", "USD", "InterestRate", "1", "", 10, "IR.CURVE.USD"),
    ("FX/SPOT/EUR", "SPOT", "FX", "EUR", "", "EURUSD", "", "EUR", "FX", "1", "", 10, "FX.SPOT.EUR|EURUSD"),
    ("FX/SPOT/CAD", "SPOT", "FX", "CAD", "", "USDCAD", "", "CAD", "FX", "1", "", 10, "FX.SPOT.CAD"),
    ("EQ/SPOT/SPX", "SPOT", "EQ", "SPX", "", "SPX", "", "11", "Equity", "11", "", 10, "EQ.SPOT.SPX"),
    ("VOL/EQ/SPX/1Y", "VOL", "EQ", "SPX", "", "SPX", "1Y", "11", "Equity", "11", "1y", 20, "VOL.EQ.SPX.1Y"),
    ("CR/SPREAD/ACME/CDS/5Y", "SPREAD", "CSR_NS", "ACME", "CDS", "", "5Y", "3", "CreditQ", "3", "5y", 40, "CR.SPREAD.ACME.CDS.5Y"),
    ("CM/PRICE/BRENT", "PRICE", "COMM", "BRENT", "", "BRENT", "", "2", "Commodity", "2", "", 20, "CM.PRICE.BRENT"),
]
#: the factors each desk's positions are sensitive to, with the measure
DESK_RISK = {"RATES TRADING-LON": [("IR/CURVE/USD/SOFR/2Y", "DV01"), ("IR/CURVE/USD/SOFR/5Y", "DV01"), ("IR/CURVE/USD/SOFR/10Y", "DV01")],
             "FX OPTIONS-ZRH": [("FX/SPOT/EUR", "DELTA"), ("FX/SPOT/CAD", "DELTA")],
             "VOL POSITIONING-CRCHI-TOR": [("VOL/EQ/SPX/1Y", "VEGA"), ("EQ/SPOT/SPX", "CURVATURE_UP"), ("EQ/SPOT/SPX", "CURVATURE_DOWN")],
             "EQUITY SWAPS-NYC": [("EQ/SPOT/SPX", "DELTA"), ("IR/CURVE/USD/SOFR/5Y", "DV01")],
             "CREDIT FLOW-HKG": [("CR/SPREAD/ACME/CDS/5Y", "CS01"), ("IR/CURVE/USD/SOFR/5Y", "DV01")],
             "GLOBAL COMMODITIES-SGP": [("CM/PRICE/BRENT", "DELTA")]}
PRODUCTS = {"RATES TRADING-LON": ("Rates", "Swap"), "FX OPTIONS-ZRH": ("FX", "Option"), "VOL POSITIONING-CRCHI-TOR": ("Equity", "Option"),
            "EQUITY SWAPS-NYC": ("Equity", "Swap"), "CREDIT FLOW-HKG": ("Credit", "CDS"), "GLOBAL COMMODITIES-SGP": ("Commodities", "Future")}


def slug(text: str) -> str:
    """The id rule of the mappings (slug: true): upper case, every run of other characters one '-'."""
    return re.sub(r"[^A-Z0-9]+", "-", text.upper()).strip("-")


def lei(name: str) -> str:
    """A made-up LEI of the right shape (18 characters and two digits), the same for the same name."""
    h = hashlib.sha256(name.encode()).hexdigest().upper()
    return "5493" + h[:14] + str(int(h[14:18], 16) % 90 + 10)


def _write(path: Path, header: list[str], rows: list[list]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(header)
        w.writerows(rows)
    return path


def books() -> list[tuple[str, str]]:
    """Twelve books, two per desk: (portfolio name, desk)."""
    return [(f"PORT_{100000 + i}", DESKS[i // 2][0]) for i in range(12)]


def generate(day: Path, business_date: str, n_positions: int = 20, seed: int = 7) -> dict[str, Path]:
    """The day's CSV files; {kind: path} (the child file under its kind's name + '.SENSITIVITIES')."""
    rng = random.Random(seed)
    le_of = {d[0]: d[2] for d in DESKS}
    out = {"legal-entity": _write(day / "legal-entity.csv", ["ENTITY_ID", "NAME", "LEI", "JURISDICTION", "REGULATOR"],
                                  [[e, n, lei(n), j, r] for e, n, j, r in ENTITIES])}
    out["desk"] = _write(day / "desk.csv", ["DESK_ID", "NAME", "HEAD", "LEGAL_ENTITY", "ASSET_CLASSES"],
                         [[d, n, HEADS[i], le, ac] for i, (d, n, le, ac) in enumerate(DESKS)])
    bk = books()
    out["book"] = _write(day / "book.csv", ["BOOK_ID", "NAME", "DESK", "ACCOUNTING_TREATMENT", "REGULATORY_BOOK", "VOLCKER"],
                         [[b, f"{d.title()} book {i % 2 + 1}", d, "Fair value (FVTPL)", "Trading book", "Market making"] for i, (b, d) in enumerate(bk)])
    out["risk-factor"] = _write(day / "risk-factor.csv", ["RISK_FACTOR", "FACTOR_TYPE", "RISK_CLASS", "QUALIFIER", "CURVE", "UNDERLYING", "TENOR",
                                                         "FRTB_BUCKET", "SIMM_RISK_CLASS", "SIMM_BUCKET", "SIMM_LABEL1", "LIQUIDITY_HORIZON",
                                                         "SOURCE_NAMES", "VALID_FROM", "SOURCE"],
                                [[*f, "2026-01-02", "synthetic-factor-master"] for f in FACTORS])
    limits = [["MGMT", f"DESK-{slug(d)}", "", rng.randrange(20, 90) * 100_000, "USD", "1D", "0.99", "2026-01-02", ""] for d, *_ in DESKS]
    limits += [["LEGAL", f"LE-{slug(le)}/DESK-{slug(d)}", "", rng.randrange(10, 60) * 100_000, "USD", "1D", "0.99", "2026-01-02", ""] for d, _, le, _ in DESKS]
    limits.append(["PORTFOLIO", "", "PF-USD-RATES", 1_200_000, "USD", "1D", "0.99", "2026-06-01", ""])
    out["var-limit"] = _write(day / "var-limit.csv", ["HIERARCHY", "NODE", "NODE_ID", "LIMIT_VAR", "CURRENCY", "HORIZON", "CONFIDENCE",
                                                     "VALID_FROM", "VALID_TO"], limits)
    out["desk-pnl"] = _write(day / "desk-pnl.csv", ["DESK_ID", "LEGAL_ENTITY", "ACTUAL_PNL", "HYPOTHETICAL_PNL", "FEES_AND_COMMISSIONS", "CURRENCY",
                                                   "SOURCE", "AS_OF"],
                             [[d, le, rng.randrange(-900, 900) * 1000, rng.randrange(-900, 900) * 1000, rng.randrange(0, 300) * 1000, "USD",
                               "synthetic-finance", f"{business_date}T22:00:00Z"] for d, _, le, _ in DESKS])
    parents, kids = [], []
    for i in range(n_positions):
        pid = f"POS-PVF-{200000000 + i}"
        book, desk = bk[i % len(bk)]
        group, ptype = PRODUCTS[desk]
        year = 2027 + rng.randrange(0, 9)
        parents.append(["POSITION", pid, le_of[desk], "MARKETS", desk, book, group, ptype, "VALIDATED", f"{year}-06-15", "USD"])
        for factor, measure in DESK_RISK[desk]:
            v = round(rng.uniform(-50_000, 50_000), 2)
            kids.append([pid, factor, measure, f"{v:.2f}", "USD", next(f[6] for f in FACTORS if f[0] == factor),
                         "0.25" if measure.startswith("CURVATURE") else ""])
    out["position-sensitivity"] = _write(day / "position-sensitivity.csv", ["GRAIN", "POSITION_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                                                                           "PRODUCT_GROUP", "PRODUCT_TYPE", "TRADE_STATUS", "MATURITY_DATE",
                                                                           "CURRENCY"], parents)
    out["position-sensitivity.SENSITIVITIES"] = _write(day / "position-sensitivity.SENSITIVITIES.csv",
                                                       ["POSITION_ID", "RISK_FACTOR", "MEASURE", "VALUE", "VALUE_CURRENCY", "TENOR", "SHOCK_SIZE"], kids)
    out.update(generate_ccr(day, business_date, rng))
    return out


GROUPS = [("ALDERSHOT", "Aldershot Pension Group", "Pensions", "GB", "Pension fund"), ("BRAMBLE", "Bramble Holdings", "Banking", "US", "Bank"),
          ("CASCADIA", "Cascadia Energy Group", "Utilities", "CA", "Corporate"), ("DUNMORE", "Dunmore Capital Group", "Asset management", "DE", "Fund")]
RATINGS = ["AA-", "A+", "A", "BBB+", "BBB", "A-", "AA", "BBB-"]


def generate_ccr(day: Path, business_date: str, rng: random.Random) -> dict[str, Path]:
    """The exposure masters of the same day (wave 2): four groups, eight counterparties (two a group), each with its agreement, CSA,
    netting set, credit limit and three trade valuations in the wave 1 books; six of the eight sets margined, with a collateral balance."""
    out = {}
    out["counterparty-group"] = _write(day / "counterparty-group.csv", ["GROUP_ID", "NAME", "LIMIT"],
                                       [[f"GRP-{g}", n, rng.randrange(20, 60) * 10_000_000] for g, n, *_ in GROUPS])
    cps = [(f"CP-{g}-{suffix}", f"{n.split(' Group')[0].split(' Holdings')[0]} {suffix.title()}", g, sector, country, kind, ENTITIES[i % 4][0])
           for i, ((g, n, sector, country, kind), suffix) in enumerate((x, s) for x in GROUPS for s in ("FUND", "TRUST"))]
    out["counterparty"] = _write(day / "counterparty.csv", ["COUNTERPARTY_ID", "NAME", "LEI", "RATING", "SECTOR", "COUNTRY", "TYPE", "GROUP",
                                                           "CREDIT_LIMIT", "LEGAL_ENTITY", "KYC_STATUS", "KYC_RISK_RATING", "KYC_LAST_REVIEW",
                                                           "EMIR_CLASSIFICATION", "PD_1Y"],
                                 [[cp, n, lei(n), RATINGS[i], sec, ctry, kind, f"GRP-{g}", f"LIM-{cp[3:]}", f"LE-{slug(le)}", "Approved",
                                   "Standard" if RATINGS[i].startswith("A") else "Enhanced", "2026-03-14", "FC", f"{0.0004 + i * 0.0003:.4f}"]
                                  for i, (cp, n, g, sec, ctry, kind, le) in enumerate(cps)])
    out["agreement"] = _write(day / "agreement.csv", ["AGREEMENT_ID", "TYPE", "GOVERNING_LAW", "SIGNED", "CLOSE_OUT_NETTING", "COUNTERPARTY",
                                                     "AUTOMATIC_EARLY_TERMINATION", "SET_OFF", "TERMINATION_CURRENCY"],
                              [[f"AGR-{cp[3:]}-ISDA", "ISDA 2002 Master", "English" if i % 2 == 0 else "New York", f"20{15 + i}-12-10",
                                "Enforceable", cp, "N", "Y", "USD"] for i, (cp, *_) in enumerate(cps)])
    margined = [c for i, c in enumerate(cps) if i not in (3, 6)]
    out["csa"] = _write(day / "csa.csv", ["CSA_ID", "MARGIN_TYPE", "THRESHOLD_US", "THRESHOLD_THEM", "MTA", "INDEPENDENT_AMOUNT", "FREQUENCY",
                                         "CSA_CURRENCY", "MTA_US", "MTA_THEM", "MARGIN_FREQUENCY_DAYS", "MPOR_DAYS", "LEGAL_ENTITY", "AGREEMENT",
                                         "COUNTERPARTY"],
                        [[f"CSA-{cp[3:]}", "VM", 10_000_000, 10_000_000, 500_000, 0, "Daily", "USD", 500_000, 500_000, 1, 10, f"LE-{slug(le)}",
                          f"AGR-{cp[3:]}-ISDA", cp] for cp, _, _, _, _, _, le in margined])
    out["csa.ELIGIBLE"] = _write(day / "csa.ELIGIBLE.csv", ["CSA_ID", "ASSET", "HAIRCUT"],
                                 [[f"CSA-{cp[3:]}", a, h] for cp, *_ in margined for a, h in (("USD cash", "0"), ("UST 1-5Y", "0.02"))])
    out["netting-set"] = _write(day / "netting-set.csv", ["NETTING_SET_ID", "COUNTERPARTY", "AGREEMENT", "CSA", "CREDIT_LIMIT",
                                                         "COLLATERAL_BALANCE", "LEGAL_ENTITY", "NETTING_ENFORCEABLE"],
                                [[f"NS-{cp[3:]}", cp, f"AGR-{cp[3:]}-ISDA", f"CSA-{cp[3:]}" if (cp, *r) in margined else "",
                                  f"LIM-{cp[3:]}", f"COLL-{cp[3:]}" if (cp, *r) in margined else "", f"LE-{slug(le)}", "Y"]
                                 for cp, *r in cps for le in [r[-1]]])
    out["collateral-balance"] = _write(day / "collateral-balance.csv", ["BALANCE_ID", "AS_OF", "CSA", "NETTING_SET"],
                                       [[f"COLL-{cp[3:]}", business_date, f"CSA-{cp[3:]}", f"NS-{cp[3:]}"] for cp, *_ in margined])
    out["collateral-balance.POSITIONS"] = _write(day / "collateral-balance.POSITIONS.csv", ["BALANCE_ID", "ASSET", "DIRECTION", "MARKET_VALUE",
                                                                                           "HAIRCUT"],
                                                 [[f"COLL-{cp[3:]}", a, d, rng.randrange(1, 90) * 100_000, h] for cp, *_ in margined
                                                  for a, d, h in (("USD cash", "Received", "0"), ("UST 1-5Y", "Posted", "0.02"))])
    out["credit-limit"] = _write(day / "credit-limit.csv", ["LIMIT_ID", "MEASURE", "LIMIT", "COUNTERPARTY", "APPROVED_BY", "REVIEW_DUE"],
                                 [[f"LIM-{cp[3:]}", "PFE 95 peak", rng.randrange(5, 40) * 10_000_000, cp, "Credit Risk Committee", "2027-03-19"]
                                  for cp, *_ in cps])
    out["credit-limit.BUCKETS"] = _write(day / "credit-limit.BUCKETS.csv", ["LIMIT_ID", "BUCKET", "LIMIT"],
                                         [[f"LIM-{cp[3:]}", b, rng.randrange(5, 40) * 10_000_000] for cp, *_ in cps for b in ("0-1Y", "1-5Y", "5Y+")])
    bk = books()
    tv = []
    for i, (cp, *_) in enumerate(cps):
        for j in range(3):
            n = 3 * i + j
            book, desk = bk[n % len(bk)]
            group, ptype = PRODUCTS[desk]
            tv.append([7000001 + n, "ENDUR" if n % 2 else "MUREX", ptype, group, book, desk, cp, f"NS-{cp[3:]}", "S" if n % 3 == 0 else "B",
                       "VALIDATED", "USD", rng.randrange(1, 200) * 1_000_000, rng.randrange(-5000, 5000) * 1000, "2025-06-20",
                       f"{2027 + n % 8}-07-31"])
    out["trade-valuation"] = _write(day / "trade-valuation.csv", ["TRADE_ID", "SOURCE_SYSTEM", "PRODUCT_TYPE", "PRODUCT_GROUP", "PORTFOLIO", "DESK",
                                                                 "COUNTERPARTY", "NETTING_SET", "BUY_SELL_CODE", "TRADE_STATUS", "CURRENCY",
                                                                 "NOTIONAL", "MTM", "TRADE_DATE", "MATURITY_DATE"], tv)
    return out


PORTFOLIO_COLUMNS = ["portfolio_id", "system_id", "portfolio_nm", "portfolio_desc", "cost_center", "transit_id", "portfolio_group_nm",
                     "portfolio_group_desc", "pnl_group_nm", "pnl_group_desc", "pnl_sheet_nm", "pnl_sheet_desc", "loss_limit_nm", "desk_nm",
                     "desk_desc", "limit_letter_nm", "limit_letter_desc", "business_sub_group_nm", "business_sub_group_desc",
                     "business_group_nm", "business_group_desc", "valid_dt", "valid_to_dt", "audit_from_ts", "audit_to_ts", "person_id",
                     "tax_residency_id", "comment_tx", "audit_user_id", "capital_treatment_nm", "funding_currency_id", "mf1_pnl_type_override",
                     "is_fx_shocked_when_optional", "is_cash_balance", "strategy", "ifrs9", "is_regw", "remittance_currency", "system_currency",
                     "is_tv_valued", "Fo_portfolio_nm", "legal_entity", "booking_location"]


def generate_pvf(day: Path, business_date: str, n_positions: int = 20, seed: int = 7, max_path: int = 250) -> dict[str, Path]:
    """The same day's PVF extract (portfolio_d2.csv, pos_<YYYYMMDD>.csv, pvf_path_<YYYYMMDD>.csv): the same books, desks, entities and
    positions as the CSV kinds, every path -1..max_path, so the scenario PVs build the nodes the limits name. Small; never committed."""
    rng = random.Random(seed + 1)
    ymd = business_date.replace("-", "")
    le_of = {d[0]: d[2] for d in DESKS}
    rows = []
    for i, (book, desk) in enumerate(books()):
        r = dict.fromkeys(PORTFOLIO_COLUMNS, "")
        r.update({"portfolio_id": 100000 + i, "system_id": 7, "portfolio_nm": book, "portfolio_desc": f"{desk.title()} book {i % 2 + 1}",
                  "portfolio_group_nm": desk, "desk_nm": desk, "business_group_nm": "MARKETS", "pnl_sheet_nm": "Trade Currency",
                  "valid_dt": "2019-05-03 00:00:00", "valid_to_dt": "9999-12-31 00:00:00", "capital_treatment_nm": "Trading Book",
                  "legal_entity": le_of[desk], "booking_location": "Trade Currency"})
        rows.append([r[c] for c in PORTFOLIO_COLUMNS])
    out = {"portfolio": _write(day / "portfolio_d2.csv", PORTFOLIO_COLUMNS, rows)}
    bk = books()
    pos = [[200000000 + i, f"CAL:{40000000 + i}.CAL:{40000000 + i}", 100000 + i % len(bk), "USD", bk[i % len(bk)][1], "T1",
            round(rng.uniform(1e5, 5e7), 2), 40000000 + i, PRODUCTS[bk[i % len(bk)][1]][1], "StandardForwardFunding"] for i in range(n_positions)]
    out["positions"] = _write(day / f"pos_{ymd}.csv", ["position_id", "src_position_id", "portfolio_id", "currency_cd", "portfolio_nm", "transit_cd",
                                                      "position_amt", "trade_id", "tv_product_type", "mf1_pnl_type"], pos)
    paths = [[200000000 + i, f"{business_date} 00:00:00", "PV", "HistoVAR", "ALL", round(rng.uniform(-1e4, 1.5e4), 2), p, "2026-10-06 00:00:00"]
             for i in range(n_positions) for p in range(-1, max_path + 1)]
    out["paths"] = _write(day / f"pvf_path_{ymd}.csv", ["position_id", "valid_dt", "risk_metric_cd", "dataset_cd", "shock_group_nm", "val",
                                                       "path_num", "histo_valid_dt"], paths)
    return out


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--day", required=True, type=Path, help="the folder the day's CSV files are written to")
    p.add_argument("--date", type=strict_date, default="2026-11-18", help="the business date (default 2026-11-18, the samples' date)")
    p.add_argument("--positions", type=int, default=20, help="positions (default 20: the committed samples; keep local trials small)")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--with-pvf", action="store_true", help="also write the day's PVF extract (portfolio_d2.csv, pos_, pvf_path_): the "
                   "scenario PVs whose positions and nodes the other kinds name (not committed)")
    a = p.parse_args(argv)
    setup_logging()
    files = generate(a.day, a.date, a.positions, a.seed)
    if a.with_pvf:
        files.update(generate_pvf(a.day, a.date, a.positions, a.seed))
    log.info("wrote %d files under %s", len(files), a.day)
    return 0


if __name__ == "__main__":
    sys.exit(main())
