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

"""Writes a synthetic PVF extract in the source's CSV shape: PORTFOLIO_D2 (portfolio_d2.csv), the positions (pos_YYYYMMDD.csv) and their
per-path PVs (pvf_path_YYYYMMDD.csv), shuffled as an extract arrives. The same --seed gives the same files.

example (from the repository root):
  python3 tools/mrloader/pvf_gen.py --out data/pvf/synthetic --positions 2000 --portfolios 150 --date 2026-10-29

Per position: 70% carry path -1 and every path 0..250; 5% have no path -1 (audited by pvf_join.py as MISSING_BASE_PV); 25% have path -1
and a random gapped subset of 0..250 (filled by pvf_join.py). --missing-portfolio adds positions whose portfolio is not in PORTFOLIO_D2.
The owner's full extract was 1,500 portfolios, 200,000 positions and about 37 million path rows (3.8 GB): keep local runs small.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import iso_date, log, setup_logging  # noqa: E402

try:
    import numpy as np
    import polars as pl
except ImportError as e:                                    # pragma: no cover
    raise SystemExit(f"pvf_gen needs polars and numpy: pip install -r drishti-console/requirements-polars.txt ({e})")

PRODUCT_TYPES = ["Future", "Swap", "Option", "Forward"]
PNL_TYPES = ["StandardBackwardFunding", "StandardForwardFunding"]
TRANSIT_CDS = ["T8734", "T9021", "T4410", "T1092", "T3301", "T5542"]
PORTFOLIO_GROUPS = ["VOL POSITIONING-CRCHI-TOR", "EQUITY SWAPS-NYC", "RATES TRADING-LON", "CREDIT FLOW-HKG", "GLOBAL COMMODITIES-SGP",
                    "FX OPTIONS-ZRH"]
LEGAL_ENTITIES = ["GMO_SPOG", "CDADMM_H4705", "GLOBAL_EQ_FIN", "MARKETS_INC"]
BOOKING_LOCATIONS = ["Trade Currency", "Global Equity Finance and Prime Services", "Corporate Treasury"]
EMPTY_PORTFOLIO_COLUMNS = ["transit_id", "portfolio_group_desc", "pnl_group_nm", "pnl_group_desc", "pnl_sheet_desc", "loss_limit_nm", "desk_nm",
                           "desk_desc", "limit_letter_nm", "limit_letter_desc", "business_sub_group_nm", "business_sub_group_desc",
                           "business_group_nm", "business_group_desc"]


def portfolios(rng: np.random.Generator, n: int) -> pl.DataFrame:
    ids = np.arange(100_000, 100_000 + n, dtype=np.int64)
    const = {"portfolio_desc": "Trading Book,1,,true,false,,FVT,,,Trade Currency,true,,,", "valid_dt": "2019-05-03 00:00:00",
             "valid_to_dt": "9999-12-31 00:00:00", "audit_from_ts": "2019-05-04 04:19:27", "audit_to_ts": "9999-12-31 00:00:00",
             "person_id": "", "tax_residency_id": "", "comment_tx": "", "audit_user_id": "80183", "capital_treatment_nm": "Trading Book",
             "funding_currency_id": "1", "mf1_pnl_type_override": "", "is_fx_shocked_when_optional": "true", "is_cash_balance": "false",
             "strategy": "", "ifrs9": "FVT", "is_regw": "", "remittance_currency": "", "system_currency": "Trade Currency",
             "is_tv_valued": "true", "Fo_portfolio_nm": ""}
    df = pl.DataFrame({"portfolio_id": ids, "system_id": rng.integers(1, 21, n), "portfolio_nm": [f"PORT_{i}" for i in ids],
                       "cost_center": [f"270{x}" for x in rng.integers(100, 1000, n)],
                       "portfolio_group_nm": rng.choice(PORTFOLIO_GROUPS, n), "pnl_sheet_nm": rng.choice(BOOKING_LOCATIONS, n),
                       "legal_entity": rng.choice(LEGAL_ENTITIES, n), "booking_location": rng.choice(BOOKING_LOCATIONS, n)})
    df = df.with_columns([pl.lit(v).alias(k) for k, v in const.items()] + [pl.lit("").alias(c) for c in EMPTY_PORTFOLIO_COLUMNS])
    order = ["portfolio_id", "system_id", "portfolio_nm", "portfolio_desc", "cost_center", "transit_id", "portfolio_group_nm",
             "portfolio_group_desc", "pnl_group_nm", "pnl_group_desc", "pnl_sheet_nm", "pnl_sheet_desc", "loss_limit_nm", "desk_nm", "desk_desc",
             "limit_letter_nm", "limit_letter_desc", "business_sub_group_nm", "business_sub_group_desc", "business_group_nm",
             "business_group_desc", "valid_dt", "valid_to_dt", "audit_from_ts", "audit_to_ts", "person_id", "tax_residency_id", "comment_tx",
             "audit_user_id", "capital_treatment_nm", "funding_currency_id", "mf1_pnl_type_override", "is_fx_shocked_when_optional",
             "is_cash_balance", "strategy", "ifrs9", "is_regw", "remittance_currency", "system_currency", "is_tv_valued", "Fo_portfolio_nm",
             "legal_entity", "booking_location"]
    return df.select(order)


def positions(rng: np.random.Generator, n: int, port_ids: np.ndarray, missing_portfolio: int) -> pl.DataFrame:
    ids = np.arange(200_000_000, 200_000_000 + n, dtype=np.int64)
    trades = rng.integers(40_000_000, 50_000_000, n)
    pids = rng.choice(port_ids, n)
    if missing_portfolio:
        pids[:missing_portfolio] = 999_999                               # a portfolio PORTFOLIO_D2 does not have
    amt = rng.uniform(100_000.0, 50_000_000.0, n) * np.where(rng.random(n) < 0.3, -1.0, 1.0)
    df = pl.DataFrame({"position_id": ids, "src_position_id": [f"CAL:{t}.CAL:{t}" for t in trades], "portfolio_id": pids,
                       "currency_cd": "USD", "portfolio_nm": rng.choice(PORTFOLIO_GROUPS, n), "transit_cd": rng.choice(TRANSIT_CDS, n),
                       "position_amt": amt, "trade_id": trades, "tv_product_type": rng.choice(PRODUCT_TYPES, n),
                       "mf1_pnl_type": rng.choice(PNL_TYPES, n)})
    return df.sample(fraction=1.0, shuffle=True, seed=int(rng.integers(0, 2**31)))


def paths(rng: np.random.Generator, ids: np.ndarray, valid: str, histo: str, max_path: int) -> pl.DataFrame:
    every = np.arange(0, max_path + 1, dtype=np.int32)
    pos_list, path_list = [], []
    for pid, roll in zip(ids, rng.random(len(ids))):
        if roll < 0.70:
            p = np.arange(-1, max_path + 1, dtype=np.int32)
        elif roll < 0.75:
            p = np.sort(rng.choice(every, size=int(rng.integers(1, max_path + 2)), replace=False))
        else:
            p = np.insert(np.sort(rng.choice(every, size=int(rng.integers(1, max_path + 1)), replace=False)), 0, -1)
        pos_list.append(np.full(len(p), pid, dtype=np.int64))
        path_list.append(p)
    flat_pos, flat_path = np.concatenate(pos_list), np.concatenate(path_list)
    n = len(flat_pos)
    df = pl.DataFrame({"position_id": flat_pos, "valid_dt": pl.repeat(valid, n, eager=True), "risk_metric_cd": pl.repeat("PV", n, eager=True),
                       "dataset_cd": pl.repeat("HistoVAR", n, eager=True), "shock_group_nm": pl.repeat("ALL", n, eager=True),
                       "val": rng.uniform(-10_000.0, 15_000.0, n), "path_num": flat_path, "histo_valid_dt": pl.repeat(histo, n, eager=True)})
    return df.sample(fraction=1.0, shuffle=True, seed=int(rng.integers(0, 2**31)))


def generate(out: Path, n_positions: int, n_portfolios: int, date: str, seed: int = 7, max_path: int = 250,
             missing_portfolio: int = 0) -> dict[str, Path]:
    rng = np.random.default_rng(seed)
    out.mkdir(parents=True, exist_ok=True)
    ymd = date.replace("-", "")
    files = {"portfolio": out / "portfolio_d2.csv", "positions": out / f"pos_{ymd}.csv", "paths": out / f"pvf_path_{ymd}.csv"}
    port = portfolios(rng, n_portfolios)
    port.write_csv(files["portfolio"])
    pos = positions(rng, n_positions, port.get_column("portfolio_id").to_numpy(), missing_portfolio)
    pos.write_csv(files["positions"])
    paths(rng, np.sort(pos.get_column("position_id").to_numpy()), f"{date} 00:00:00", "2026-10-06 00:00:00", max_path).write_csv(files["paths"])
    log.info("wrote %s", ", ".join(str(p) for p in files.values()))
    return files


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", required=True, type=Path, help="the folder the three CSV files are written to")
    p.add_argument("--positions", type=int, default=2_000, help="positions (default 2,000; the owner's extract had 200,000)")
    p.add_argument("--portfolios", type=int, default=150, help="portfolios (default 150)")
    p.add_argument("--date", default="2026-10-29", help="the business date (VALID_DT and the file names)")
    p.add_argument("--max-path", type=int, default=250, help="the last path number (default 250)")
    p.add_argument("--missing-portfolio", type=int, default=0, help="this many positions name a portfolio PORTFOLIO_D2 does not have")
    p.add_argument("--seed", type=int, default=7)
    a = p.parse_args(argv)
    setup_logging()
    generate(a.out, a.positions, a.portfolios, iso_date(a.date), a.seed, a.max_path, a.missing_portfolio)
    return 0


if __name__ == "__main__":
    sys.exit(main())
