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

import numpy as np
import polars as pl

# File Paths
PORTFOLIO_FILE = "portfolio_d2.csv"
POS_FILE = "pos_20261029.csv"
PVF_FILE = "pvf_path_20261029.csv"

# Configuration Constants
NUM_PORTFOLIOS = 1_500
NUM_POSITIONS = 200_000
VALID_DATE = "2026-10-29 00:00:00"
HISTO_VALID_DATE = "2026-10-06 00:00:00"

PRODUCT_TYPES = ["Future", "Swap", "Option", "Forward"]
PNL_TYPES = ["StandardBackwardFunding", "StandardForwardFunding"]
TRANSIT_CDS = ["T0001", "T0002", "T0003", "T0004", "T0005", "T0006"]
PORTFOLIO_GROUPS = [
    "GROUP-A",
    "GROUP-B",
    "GROUP-C",
    "GROUP-D",
    "GROUP-E",
    "GROUP-F",
]
LEGAL_ENTITIES = ["LE_ALPHA", "LE_BETA", "LE_GAMMA", "LE_DELTA"]
BOOKING_LOCATIONS = [
    "Trade Currency",
    "Global Equity Finance and Prime Services",
    "Corporate Treasury",
]

# ---------------------------------------------------------
# Step 1: Generate PORTFOLIO_D2.csv using Polars
# ---------------------------------------------------------
print(f"Generating {PORTFOLIO_FILE} with {NUM_PORTFOLIOS:,} records...")

port_ids = np.arange(100_000, 100_000 + NUM_PORTFOLIOS, dtype=np.int64)

df_portfolio = pl.DataFrame({
    "portfolio_id": port_ids,
    "system_id": np.random.randint(1, 21, size=NUM_PORTFOLIOS, dtype=np.int32),
    "portfolio_nm": [f"PORT_{pid}" for pid in port_ids],
    "portfolio_desc": ["Trading Book,1,,true,false,,FVT,,,Trade Currency,true,,,"] * NUM_PORTFOLIOS,
    "cost_center": [f"270{np.random.randint(100, 1000)}" for _ in range(NUM_PORTFOLIOS)],
    "transit_id": [""] * NUM_PORTFOLIOS,
    "portfolio_group_nm": np.random.choice(PORTFOLIO_GROUPS, size=NUM_PORTFOLIOS),
    "portfolio_group_desc": [""] * NUM_PORTFOLIOS,
    "pnl_group_nm": [""] * NUM_PORTFOLIOS,
    "pnl_group_desc": [""] * NUM_PORTFOLIOS,
    "pnl_sheet_nm": np.random.choice(BOOKING_LOCATIONS, size=NUM_PORTFOLIOS),
    "pnl_sheet_desc": [""] * NUM_PORTFOLIOS,
    "loss_limit_nm": [""] * NUM_PORTFOLIOS,
    "desk_nm": [""] * NUM_PORTFOLIOS,
    "desk_desc": [""] * NUM_PORTFOLIOS,
    "limit_letter_nm": [""] * NUM_PORTFOLIOS,
    "limit_letter_desc": [""] * NUM_PORTFOLIOS,
    "business_sub_group_nm": [""] * NUM_PORTFOLIOS,
    "business_sub_group_desc": [""] * NUM_PORTFOLIOS,
    "business_group_nm": [""] * NUM_PORTFOLIOS,
    "business_group_desc": [""] * NUM_PORTFOLIOS,
    "valid_dt": ["2019-05-03 00:00:00"] * NUM_PORTFOLIOS,
    "valid_to_dt": ["9999-12-31 00:00:00"] * NUM_PORTFOLIOS,
    "audit_from_ts": ["2019-05-04 04:19:27"] * NUM_PORTFOLIOS,
    "audit_to_ts": ["9999-12-31 00:00:00"] * NUM_PORTFOLIOS,
    "person_id": [""] * NUM_PORTFOLIOS,
    "tax_residency_id": [""] * NUM_PORTFOLIOS,
    "comment_tx": [""] * NUM_PORTFOLIOS,
    "audit_user_id": ["80183"] * NUM_PORTFOLIOS,
    "capital_treatment_nm": ["Trading Book"] * NUM_PORTFOLIOS,
    "funding_currency_id": ["1"] * NUM_PORTFOLIOS,
    "mf1_pnl_type_override": [""] * NUM_PORTFOLIOS,
    "is_fx_shocked_when_optional": ["true"] * NUM_PORTFOLIOS,
    "is_cash_balance": ["false"] * NUM_PORTFOLIOS,
    "strategy": [""] * NUM_PORTFOLIOS,
    "ifrs9": ["FVT"] * NUM_PORTFOLIOS,
    "is_regw": [""] * NUM_PORTFOLIOS,
    "remittance_currency": [""] * NUM_PORTFOLIOS,
    "system_currency": ["Trade Currency"] * NUM_PORTFOLIOS,
    "is_tv_valued": ["true"] * NUM_PORTFOLIOS,
    "Fo_portfolio_nm": [""] * NUM_PORTFOLIOS,
    "legal_entity": np.random.choice(LEGAL_ENTITIES, size=NUM_PORTFOLIOS),
    "booking_location": np.random.choice(BOOKING_LOCATIONS, size=NUM_PORTFOLIOS),
})

df_portfolio.write_csv(PORTFOLIO_FILE)
print(f"Successfully created {PORTFOLIO_FILE}.")

# ---------------------------------------------------------
# Step 2: Generate & Randomize pos_20261029.csv using Polars
# ---------------------------------------------------------
print(f"Generating and randomizing {POS_FILE} with {NUM_POSITIONS:,} records...")

position_ids = np.arange(200_000_000, 200_000_000 + NUM_POSITIONS, dtype=np.int64)
trade_ids = np.random.randint(40_000_000, 50_000_000, size=NUM_POSITIONS, dtype=np.int64)

df_pos = pl.DataFrame({
    "position_id": position_ids,
    "src_position_id": [f"CAL:{tid}.CAL:{tid}" for tid in trade_ids],
    "portfolio_id": np.random.choice(port_ids, size=NUM_POSITIONS),
    "currency_cd": ["USD"] * NUM_POSITIONS,
    "portfolio_nm": np.random.choice(PORTFOLIO_GROUPS, size=NUM_POSITIONS),
    "transit_cd": np.random.choice(TRANSIT_CDS, size=NUM_POSITIONS),
    "position_amt": np.random.uniform(100_000.0, 50_000_000.0, size=NUM_POSITIONS),
    "trade_id": trade_ids,
    "tv_product_type": np.random.choice(PRODUCT_TYPES, size=NUM_POSITIONS),
    "mf1_pnl_type": np.random.choice(PNL_TYPES, size=NUM_POSITIONS),
})

# In-memory sample for linear row shuffling
df_pos = df_pos.sample(fraction=1.0, shuffle=True)
df_pos.write_csv(POS_FILE)

print(f"Successfully saved randomized {POS_FILE}.")
del df_pos

# ---------------------------------------------------------
# Step 3: Efficiently Generate & Randomize pvf_path_20261029.csv
# ---------------------------------------------------------
print(f"Generating dynamic paths for {PVF_FILE}...")

# Sample rules across positions
rolls = np.random.random(NUM_POSITIONS)
all_positive_paths = np.arange(0, 251, dtype=np.int16)

pos_list = []
path_list = []

for idx, pos_id in enumerate(position_ids):
    roll = rolls[idx]
    if roll < 0.70:
        # 70% Perfect: [-1, 0..250] contiguous
        paths = np.arange(-1, 251, dtype=np.int16)
    elif roll < 0.75:
        # 5% Bad: Missing -1, random selection from [0..250]
        k = np.random.randint(1, 252)
        paths = np.sort(np.random.choice(all_positive_paths, size=k, replace=False))
    else:
        # 25% Imperfect: Contains -1 + random gapped paths from [0..250]
        k = np.random.randint(1, 251)
        positive_selection = np.sort(np.random.choice(all_positive_paths, size=k, replace=False))
        paths = np.insert(positive_selection, 0, -1)

    pos_list.append(np.full(len(paths), pos_id, dtype=np.int64))
    path_list.append(paths)

# Concatenate flattened arrays for columnar memory layout
flat_positions = np.concatenate(pos_list)
flat_paths = np.concatenate(path_list)
total_path_rows = len(flat_positions)

print(f"Constructing Polars table with {total_path_rows:,} path records...")

df_pvf = pl.DataFrame({
    "position_id": flat_positions,
    "valid_dt": pl.repeat(VALID_DATE, n=total_path_rows, eager=True),
    "risk_metric_cd": pl.repeat("PV", n=total_path_rows, eager=True),
    "dataset_cd": pl.repeat("HistoVAR", n=total_path_rows, eager=True),
    "shock_group_nm": pl.repeat("ALL", n=total_path_rows, eager=True),
    "val": np.random.uniform(-10000.0, 15000.0, size=total_path_rows),
    "path_num": flat_paths,
    "histo_valid_dt": pl.repeat(HISTO_VALID_DATE, n=total_path_rows, eager=True),
})

# Free intermediate arrays
del pos_list, path_list, flat_positions, flat_paths

print(f"Shuffling {total_path_rows:,} records in Polars...")
df_pvf = df_pvf.sample(fraction=1.0, shuffle=True)

print(f"Writing output to {PVF_FILE}...")
df_pvf.write_csv(PVF_FILE)

print(f"Successfully generated and saved randomized {PVF_FILE}.")