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

PORTFOLIO_FILE = "portfolio_d2.csv"
POS_FILE = "pos_20261029.csv"
PVF_FILE = "pvf_path_20261029.csv"
OUTPUT_JSONL_FILE = "path_pv_final.jsonl"
BAD_POSITIONS_LOG = "bad_positions_audit.log"

print("Loading CSV files into Polars DataFrames...")
df_port = pl.read_csv(PORTFOLIO_FILE)
df_pos = pl.read_csv(POS_FILE)
df_pvf = pl.read_csv(PVF_FILE)

# ---------------------------------------------------------
# Step 1: Filter & Log Bad Positions
# ---------------------------------------------------------
print("Auditing referential integrity and base paths...")

# Rule 1a: Check portfolio_id referential integrity
valid_portfolio_ids = df_port.select("portfolio_id").unique()
invalid_port_positions = df_pos.join(
    valid_portfolio_ids, on="portfolio_id", how="anti"
).select("position_id", pl.lit("Missing portfolio_id in PORTFOLIO_D2").alias("reason"))

# Rule 1b: Identify positions lacking path_num = -1 in PVF
valid_base_path_positions = df_pvf.filter(pl.col("path_num") == -1).select("position_id").unique()
missing_base_positions = df_pos.join(
    valid_base_path_positions, on="position_id", how="anti"
).select("position_id", pl.lit("Missing path_num = -1 in PVF").alias("reason"))

# Combine and log bad positions
df_bad_positions = pl.concat([invalid_port_positions, missing_base_positions]).unique(subset=["position_id"])
df_bad_positions.write_csv(BAD_POSITIONS_LOG)

bad_pos_ids = df_bad_positions.select("position_id")
print(f"Logged {len(df_bad_positions):,} bad positions to {BAD_POSITIONS_LOG}.")

# Filter out bad positions to isolate good positions
df_pos_good = df_pos.join(bad_pos_ids, on="position_id", how="anti")
df_pvf_good = df_pvf.join(bad_pos_ids, on="position_id", how="anti")

# ---------------------------------------------------------
# Step 2: Extract base_pv (val where path_num = -1)
# ---------------------------------------------------------
print("Extracting base_pv and building dense [0..250] array matrix...")

df_base_pv = df_pvf_good.filter(pl.col("path_num") == -1).select(
    pl.col("position_id"),
    pl.col("val").alias("base_pv")
)

# Join base_pv directly onto good positions DataFrame
df_pos_good = df_pos_good.join(df_base_pv, on="position_id", how="inner")

# ---------------------------------------------------------
# Step 3: Construct Dense 251-Element Array with Forward Fill
# ---------------------------------------------------------
# Filter path_num to [0..250] range
df_paths_0_250 = df_pvf_good.filter((pl.col("path_num") >= 0) & (pl.col("path_num") <= 250))

# Create dense grid of (good position_id x path_num [0..250])
df_good_pos_ids = df_pos_good.select("position_id")
df_full_paths = pl.DataFrame({"path_num": np.arange(0, 251, dtype=np.int16)})
df_grid = df_good_pos_ids.join(df_full_paths, how="cross")

# Join sparse paths onto dense grid
df_dense_paths = df_grid.join(
    df_paths_0_250.select("position_id", "path_num", "val"),
    on=["position_id", "path_num"],
    how="left"
).join(
    df_base_pv, on="position_id", how="inner"
)

# Apply forward fill rules:
# 1. If path_num = 0 is missing, default to base_pv
# 2. Sequential forward fill down path_num [1..250] per position_id
df_dense_filled = df_dense_paths.with_columns(
    pl.when((pl.col("path_num") == 0) & pl.col("val").is_null())
    .then(pl.col("base_pv"))
    .otherwise(pl.col("val"))
    .alias("val_filled")
).sort(["position_id", "path_num"]).with_columns(
    pl.col("val_filled").forward_fill().over("position_id")
)

# Group filled values into a single 251-element Polars List array per position
df_path_arrays = df_dense_filled.group_by("position_id", maintain_order=True).agg(
    pl.col("val_filled").alias("path_pv")
)

# ---------------------------------------------------------
# Step 4: Final Join & Export to JSON Lines (.jsonl)
# ---------------------------------------------------------
print("Assembling final dataset and writing to JSON Lines format...")

df_final = df_pos_good.join(
    df_port, on="portfolio_id", how="inner"
).join(
    df_path_arrays, on="position_id", how="inner"
)

# Write directly to JSON Lines format using Polars
df_final.write_ndjson(OUTPUT_JSONL_FILE)

print(f"Successfully generated {OUTPUT_JSONL_FILE} with {len(df_final):,} JSON lines.")