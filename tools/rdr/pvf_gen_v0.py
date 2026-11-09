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

import csv
import random
import numpy as np

# Files
PORTFOLIO_FILE = "portfolio_d2.csv"
POS_FILE = "pos_20261029.csv"
PVF_FILE = "pvf_path_20261029.csv"

# Configuration
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
BOOKING_LOCATIONS = ["Trade Currency", "Global Equity Finance and Prime Services", "Corporate Treasury"]

# ---------------------------------------------------------
# Step 1: Generate PORTFOLIO_D2.csv (1,500 portfolios)
# ---------------------------------------------------------
print(f"Generating {PORTFOLIO_FILE} with {NUM_PORTFOLIOS} records...")

portfolio_headers = [
    "portfolio_id", "system_id", "portfolio_nm", "portfolio_desc", "cost_center",
    "transit_id", "portfolio_group_nm", "portfolio_group_desc", "pnl_group_nm",
    "pnl_group_desc", "pnl_sheet_nm", "pnl_sheet_desc", "loss_limit_nm", "desk_nm",
    "desk_desc", "limit_letter_nm", "limit_letter_desc", "business_sub_group_nm",
    "business_sub_group_desc", "business_group_nm", "business_group_desc", "valid_dt",
    "valid_to_dt", "audit_from_ts", "audit_to_ts", "person_id", "tax_residency_id",
    "comment_tx", "audit_user_id", "capital_treatment_nm", "funding_currency_id",
    "mf1_pnl_type_override", "is_fx_shocked_when_optional", "is_cash_balance", "strategy",
    "is_regw", "ifrs9", "remittance_currency", "system_currency", "is_tv_valued",
    "Fo_portfolio_nm", "legal_entity", "booking_location"
]

portfolio_ids = []
start_port_id = 100_000

with open(PORTFOLIO_FILE, mode="w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(portfolio_headers)

    for i in range(NUM_PORTFOLIOS):
        port_id = start_port_id + i
        portfolio_ids.append(str(port_id))
        sys_id = random.randint(1, 20)
        port_nm = f"PORT_{port_id}"
        cost_center = f"270{random.randint(100, 999)}"
        le = random.choice(LEGAL_ENTITIES)
        bk_loc = random.choice(BOOKING_LOCATIONS)

        writer.writerow([
            port_id, sys_id, port_nm, "Trading Book,1,,true,false,,FVT,,,Trade Currency,true,,,",
            cost_center, "", le, "", "", "", bk_loc, "", "", "", "", "", "", "", "", "", "",
            "2019-05-03 00:00:00", "9999-12-31 00:00:00", "2019-05-04 04:19:27", "9999-12-31 00:00:00",
            "", "", "", "80183", "Trading Book", "1", "", "true", "false", "", "FVT", "", "",
            "Trade Currency", "true", "", "", ""
        ])

print(f"Successfully generated {PORTFOLIO_FILE} with {len(portfolio_ids)} portfolio IDs.")

# ---------------------------------------------------------
# Step 2: Generate & Randomize pos_20261029.csv (200,000 positions)
# ---------------------------------------------------------
print(f"Generating and randomizing records for {POS_FILE}...")
start_pos_id = 200_000_000
position_ids = np.arange(start_pos_id, start_pos_id + NUM_POSITIONS, dtype=np.int64)

pos_headers = [
    "position_id",
    "src_position_id",
    "portfolio_id",
    "currency_cd",
    "portfolio_nm",
    "transit_cd",
    "position_amt",
    "trade_id",
    "tv_product_type",
    "mf1_pnl_type",
]

pos_rows = []
for pos_id in position_ids:
    trade_id = random.randint(40000000, 49999999)
    src_pos = f"CAL:{trade_id}.CAL:{trade_id}"
    port_id = random.choice(portfolio_ids)
    port_nm = random.choice(PORTFOLIO_GROUPS)
    transit = random.choice(TRANSIT_CDS)
    amt = f"{random.uniform(100000.0, 50000000.0):.10f}"
    prod_type = random.choice(PRODUCT_TYPES)
    pnl_type = random.choice(PNL_TYPES)

    pos_rows.append([
        pos_id, src_pos, port_id, "USD", port_nm,
        transit, amt, trade_id, prod_type, pnl_type
    ])

# Randomize position rows
random.shuffle(pos_rows)

with open(POS_FILE, mode="w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(pos_headers)
    writer.writerows(pos_rows)

print(f"Successfully saved randomized {POS_FILE}.")

# Clear memory
del pos_rows

# ---------------------------------------------------------
# Step 3: Generate & Randomize pvf_path_20261029.csv based on Rules
# ---------------------------------------------------------
print(f"Generating paths for {PVF_FILE}...")

pvf_headers = [
    "position_id",
    "valid_dt",
    "risk_metric_cd",
    "dataset_cd",
    "shock_group_nm",
    "val",
    "path_num",
    "histo_valid_dt",
]

all_positive_paths = np.arange(0, 251)
pvf_rows = []

for pos_id in position_ids:
    roll = random.random()

    if roll < 0.70:
        # 70% Perfect Records: [-1, 0, 1, ..., 250] contiguous
        paths = np.arange(-1, 251)
    elif roll < 0.75:
        # 5% Bad Records: Missing -1, random selection from [0..250] with potential gaps
        k = random.randint(1, 251)
        paths = np.sort(np.random.choice(all_positive_paths, size=k, replace=False))
    else:
        # 25% Imperfect Records: Includes -1, followed by random selection from [0..250] with gaps
        k = random.randint(1, 250)
        positive_selection = np.sort(np.random.choice(all_positive_paths, size=k, replace=False))
        paths = np.insert(positive_selection, 0, -1)

    for path in paths:
        val = f"{random.uniform(-10000.0, 15000.0):.12f}"
        pvf_rows.append([
            pos_id,
            VALID_DATE,
            "PV",
            "HistoVAR",
            "ALL",
            val,
            path,
            HISTO_VALID_DATE
        ])

print(f"Randomizing ~{len(pvf_rows):,} path records...")
random.shuffle(pvf_rows)

print(f"Writing randomized data to {PVF_FILE}...")
with open(PVF_FILE, mode="w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(pvf_headers)
    writer.writerows(pvf_rows)

print(f"Successfully saved randomized {PVF_FILE}.")