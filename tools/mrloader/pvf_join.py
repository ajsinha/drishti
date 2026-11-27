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

"""Joins a PVF extract into one JSON Lines row per position: the position, its portfolio (PORTFOLIO_D2), its base PV and its dense
vector of per-path PVs. A position that cannot be joined goes to an audit file, never into the output.

examples (from the repository root):
  python3 tools/mrloader/pvf_join.py --portfolio data/pvf/portfolio_d2.csv --positions data/pvf/pos_20261029.csv \\
      --paths data/pvf/pvf_path_20261029.csv --out data/pvf/out/pvf_joined_20261029.jsonl
  python3 tools/mrloader/pvf_join.py ... --limit-positions 2000          a quick trial on the first 2,000 positions
  python3 tools/mrloader/pvf_join.py ... --dry-run                       check the inputs and say what would be written; write nothing

the rules (docs/guides/BANK_DATA_GUIDE.md, "The join rules"):
  * the per-path file is read lazily (Polars scan_csv, streaming engine): only POSITION_ID, PATH_NUM, VAL and the filter columns, rows
    of the business date (VALID_DT), --risk-metric, --dataset and --shock-group only; it is never held whole in memory;
  * BASE_PV is the value of path -1; PATH_PV holds paths 0..--max-path (251 values by default). A missing path is filled by
    --fill-policy: forward (the default, the original rule): path 0 takes BASE_PV, then each missing path the previous path's value;
    base: every missing path takes BASE_PV; refuse: a position with a missing path is excluded (INCOMPLETE_PATHS). A filled value means
    "no change" in that scenario and understates risk: PATH0_FROM_BASE and PATHS_FILLED say so on the row, the .fills.csv file lists
    every position with fills and the summary counts them (with the top offenders);
  * a position is excluded and audited when its portfolio is missing (or has no row valid on the business date, or more than one), when
    it has no path -1, when one of its paths carries two different values, or when its POSITION_ID is in the positions file twice;
  * rows are written in POSITION_ID order; every attribute name is UPPER_SNAKE (a portfolio column whose name a position column already
    has gets the suffix _D2).

writes <out> (JSON Lines), <out-stem>.audit.csv (POSITION_ID, REASON, DETAIL), <out-stem>.fills.csv (POSITION_ID, PATH0_FROM_BASE,
PATHS_FILLED, PATHS_SUPPLIED, most filled first) and <out-stem>.summary.json (rows in and out, the audit by reason, the fills, the path
rows dropped and why, seconds, peak memory). Exit 0 written, 1 refused (one line saying why), 2 usage.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import PvfError, Timer, date_in_name, iso_date, log, peak_memory_mb, setup_logging, upper_name  # noqa: E402

try:
    import numpy as np
    import polars as pl
except ImportError as e:                                    # pragma: no cover - said in one line, not a traceback
    raise SystemExit(f"pvf_join needs polars and numpy: pip install -r drishti-console/requirements-polars.txt ({e})")

# the reasons a position is excluded (the audit's REASON column)
MISSING_PORTFOLIO = "MISSING_PORTFOLIO"
AMBIGUOUS_PORTFOLIO = "AMBIGUOUS_PORTFOLIO"
MISSING_BASE_PV = "MISSING_BASE_PV"
CONFLICTING_PATH_VALUES = "CONFLICTING_PATH_VALUES"
DUPLICATE_POSITION = "DUPLICATE_POSITION"
INCOMPLETE_PATHS = "INCOMPLETE_PATHS"
FILL_POLICIES = ("forward", "base", "refuse")

# explicit dtypes: ids as 64-bit integers (the extract's surrogate keys), amounts as floats, every other column as text, as it is
PATH_SCHEMA = {"position_id": pl.Int64, "valid_dt": pl.String, "risk_metric_cd": pl.String, "dataset_cd": pl.String,
               "shock_group_nm": pl.String, "val": pl.Float64, "path_num": pl.Int32, "histo_valid_dt": pl.String}
POSITION_TYPES = {"position_id": pl.Int64, "portfolio_id": pl.Int64, "position_amt": pl.Float64}
ADDED = {"business_date": "BUSINESS_DATE", "base_pv": "BASE_PV", "path0_from_base": "PATH0_FROM_BASE", "paths_filled": "PATHS_FILLED",
         "path_pv": "PATH_PV"}
TOP_FILLED = 20


@dataclass
class JoinConfig:
    """What one run joins. ``business_date`` defaults to the date in the paths file's name; ``audit`` and ``summary`` to files beside
    ``out``. Checked when made (``PvfError`` naming the problem)."""

    portfolio: Path
    positions: Path
    paths: Path
    out: Path
    audit: Path | None = None
    business_date: str | None = None
    max_path: int = 250
    risk_metric: str = "PV"
    dataset: str = "HistoVAR"
    shock_group: str = "ALL"
    limit_positions: int | None = None
    chunk: int = 50_000
    dry_run: bool = False
    fill_policy: str = "forward"
    summary: Path = field(init=False)
    fills: Path = field(init=False)

    def __post_init__(self) -> None:
        self.portfolio, self.positions, self.paths, self.out = map(Path, (self.portfolio, self.positions, self.paths, self.out))
        for name in ("portfolio", "positions", "paths"):
            if not getattr(self, name).is_file():
                raise PvfError(f"--{name}: {getattr(self, name)} is not a file")
        stem = self.out.name.removesuffix(".jsonl")
        self.audit = Path(self.audit) if self.audit else self.out.with_name(stem + ".audit.csv")
        self.summary = self.out.with_name(stem + ".summary.json")
        self.fills = self.out.with_name(stem + ".fills.csv")
        bd = self.business_date or date_in_name(self.paths)
        if not bd:
            raise PvfError(f"no business date: give --business-date (the name {self.paths.name} carries none)")
        self.business_date = iso_date(bd)
        if self.max_path < 0:
            raise PvfError("--max-path must be 0 or more")
        if self.limit_positions is not None and self.limit_positions < 1:
            raise PvfError("--limit-positions must be 1 or more")
        if self.fill_policy not in FILL_POLICIES:
            raise PvfError(f"--fill-policy is one of {', '.join(FILL_POLICIES)}")
        if self.chunk < 1:
            raise PvfError("--chunk must be 1 or more")

    @property
    def width(self) -> int:
        """The length of PATH_PV: paths 0..max_path."""
        return self.max_path + 1


# ---- reading -----------------------------------------------------------------------------------------------------------------------
def modified_utc(path: Path) -> str:
    """When a file was last modified, ISO-8601 UTC to the second: the extract's own time (the run's COMPUTED_AT by default)."""
    return dt.datetime.fromtimestamp(path.stat().st_mtime, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _need(columns: list[str], required: list[str] | tuple[str, ...], what: Path) -> None:
    missing = [c for c in required if c not in columns]
    if missing:
        raise PvfError(f"{what} has no column {', '.join(missing)} (it has {', '.join(columns)})")


def read_positions(c: JoinConfig) -> pl.DataFrame:
    """The positions file (the first --limit-positions rows), text except POSITION_TYPES; a value that is not a number is refused."""
    df = pl.read_csv(c.positions, infer_schema=False, n_rows=c.limit_positions)
    _need(df.columns, ("position_id", "portfolio_id"), c.positions)
    try:
        return df.with_columns(pl.col(k).cast(t, strict=True) for k, t in POSITION_TYPES.items() if k in df.columns)
    except pl.exceptions.InvalidOperationError as e:
        raise PvfError(f"{c.positions}: a value of {', '.join(POSITION_TYPES)} is not a number ({str(e).splitlines()[0]})") from None


def read_portfolios(c: JoinConfig) -> tuple[pl.DataFrame, set[int]]:
    """PORTFOLIO_D2 as text, only its rows valid on the business date (VALID_DT <= date < VALID_TO_DT, when the file has the two
    columns: a slowly changing dimension keeps several versions of a portfolio); and every portfolio id of the file, to tell a
    portfolio that is absent from one that has no version on the date."""
    df = pl.read_csv(c.portfolio, infer_schema=False)
    _need(df.columns, ("portfolio_id",), c.portfolio)
    df = df.with_columns(pl.col("portfolio_id").cast(pl.Int64, strict=True))
    every = set(df.get_column("portfolio_id").to_list())
    if "valid_dt" in df.columns and "valid_to_dt" in df.columns:
        bd = c.business_date
        df = df.filter((pl.col("valid_dt").str.slice(0, 10) <= bd) & (pl.col("valid_to_dt").str.slice(0, 10) > bd))
    return df, every


def path_columns(c: JoinConfig) -> list[str]:
    """The paths file's header, checked for the columns the join reads."""
    names = pl.scan_csv(c.paths, n_rows=0).collect_schema().names()
    filters = [k for k, v in _filters(c).items() if v]
    _need(names, ["position_id", "path_num", "val", "valid_dt", *filters], c.paths)
    return names


def _filters(c: JoinConfig) -> dict[str, str]:
    return {"risk_metric_cd": c.risk_metric, "dataset_cd": c.dataset, "shock_group_nm": c.shock_group}


def scan_paths(c: JoinConfig, ids: pl.DataFrame | None) -> tuple[pl.DataFrame, dict[str, int]]:
    """The paths of the date grouped by (POSITION_ID, PATH_NUM): VAL's min and max (two differ: a conflict) and the row count; sorted.
    And the counts of the rows dropped and why. One streaming pass; with ``ids`` only those positions are kept."""
    names = path_columns(c)
    lf = pl.scan_csv(c.paths, schema_overrides={k: t for k, t in PATH_SCHEMA.items() if k in names})
    keep = pl.col("valid_dt").str.slice(0, 10) == c.business_date
    for col, value in _filters(c).items():
        if value:
            keep = keep & (pl.col(col) == value)
    in_range = pl.col("path_num").is_between(-1, c.max_path)
    counts = lf.select(ROWS=pl.len(), OTHER_DATE_OR_SET=(~keep).sum(), OUT_OF_RANGE_PATHS=(keep & ~in_range).sum(),
                       NULL_VALUES=(keep & in_range & pl.col("val").is_null()).sum())
    rows = lf.filter(keep & in_range & pl.col("val").is_not_null()).select("position_id", "path_num", "val")
    if ids is not None:
        rows = rows.join(ids.lazy(), on="position_id", how="semi")
    grouped = rows.group_by("position_id", "path_num").agg(vmin=pl.col("val").min(), vmax=pl.col("val").max(), n=pl.len())
    g, n = pl.collect_all([grouped, counts], engine="streaming")
    return g.sort("position_id", "path_num"), {k: int(v or 0) for k, v in n.row(0, named=True).items()}


# ---- the rules ---------------------------------------------------------------------------------------------------------------------
def dense(base: np.ndarray, pos_idx: np.ndarray, path: np.ndarray, val: np.ndarray, width: int,
          policy: str = "forward") -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """The (positions x width) matrix of the given (position index, path, value) triples, its missing paths filled by ``policy``:
    forward, path 0 = ``base`` where missing, then each missing path the previous path's value; base, every missing path = ``base``
    (refuse: the caller excluded every incomplete position, nothing is missing). Returns the matrix, the number of filled paths per
    position and whether path 0 was taken from ``base``."""
    m = np.full((len(base), width), np.nan)
    m[pos_idx, path] = val
    missing = np.isnan(m)
    filled, path0 = missing.sum(axis=1), missing[:, 0].copy()
    if policy == "base":
        return np.where(missing, base[:, None], m), filled, path0
    m[:, 0] = np.where(path0, base, m[:, 0])
    idx = np.where(np.isnan(m), 0, np.arange(width))
    np.maximum.accumulate(idx, axis=1, out=idx)
    return m[np.arange(len(base))[:, None], idx], filled, path0


def audit_rows(c: JoinConfig, pos: pl.DataFrame, port: pl.DataFrame, g: pl.DataFrame, every_portfolio: set[int]) -> pl.DataFrame:
    """(POSITION_ID, REASON, DETAIL) of every position that cannot be joined, one row per reason, sorted."""
    bd = c.business_date
    dup = pos.group_by("position_id").len().filter(pl.col("len") > 1)
    with_port = pos.select("position_id", "portfolio_id").unique().join(port.group_by("portfolio_id").len(), on="portfolio_id", how="left")
    miss = with_port.filter(pl.col("len").is_null())
    amb = with_port.filter(pl.col("len") > 1)
    nobase = pos.select("position_id").unique().join(g.filter(pl.col("path_num") == -1), on="position_id", how="anti")
    conf = g.filter(pl.col("vmin") != pl.col("vmax")).group_by("position_id").agg(pl.col("path_num").sort())
    supplied = g.filter(pl.col("path_num") >= 0).group_by("position_id").len()
    short = pos.select("position_id").unique().join(supplied, on="position_id", how="left").filter(pl.col("len").fill_null(0) < c.width)
    parts = [
        dup.select("position_id", REASON=pl.lit(DUPLICATE_POSITION), DETAIL=pl.format("{} rows in the positions file", "len")),
        miss.select("position_id", REASON=pl.lit(MISSING_PORTFOLIO),
                    DETAIL=pl.when(pl.col("portfolio_id").is_in(sorted(every_portfolio)))
                    .then(pl.format("portfolio_id {} has no row valid on " + bd, "portfolio_id"))
                    .otherwise(pl.format("portfolio_id {} is not in PORTFOLIO_D2", "portfolio_id"))),
        amb.select("position_id", REASON=pl.lit(AMBIGUOUS_PORTFOLIO), DETAIL=pl.format("portfolio_id {} has {} rows valid on " + bd, "portfolio_id", "len")),
        nobase.select("position_id", REASON=pl.lit(MISSING_BASE_PV), DETAIL=pl.lit("no row with path_num = -1 on " + bd)),
        conf.select("position_id", REASON=pl.lit(CONFLICTING_PATH_VALUES),
                    DETAIL=pl.lit("paths with two different values: ") + pl.col("path_num").cast(pl.List(pl.String)).list.join(" ")),
    ]
    if c.fill_policy == "refuse":
        parts.append(short.select("position_id", REASON=pl.lit(INCOMPLETE_PATHS),
                                  DETAIL=pl.format("{} of " + str(c.width) + " paths supplied (--fill-policy refuse)", pl.col("len").fill_null(0))))
    return (pl.concat([p.with_columns(pl.col("DETAIL").cast(pl.String)) for p in parts])
            .rename({"position_id": "POSITION_ID"}).sort("POSITION_ID", "REASON"))


def output_names(pos_cols: list[str], port_cols: list[str]) -> tuple[dict[str, str], dict[str, str]]:
    """The portfolio columns' renames (a name the positions already have: suffix _d2) and every output column's UPPER_SNAKE name."""
    rename = {k: (k + "_d2" if k in pos_cols else k) for k in port_cols if k != "portfolio_id"}
    names = {k: upper_name(k) for k in pos_cols + list(rename.values())}
    names.update(ADDED)
    if len(set(names.values())) != len(names):
        raise PvfError(f"two columns become one upper-case name: {sorted(names.values())}")
    return rename, names


# ---- the run -----------------------------------------------------------------------------------------------------------------------
def write_rows(c: JoinConfig, good: pl.DataFrame, paths: pl.DataFrame, port: pl.DataFrame, rename: dict, names: dict) -> tuple[int, pl.DataFrame]:
    """The joined rows, ``c.chunk`` positions at a time (the dense matrix of one chunk is chunk x width floats); returns the rows
    written and (POSITION_ID, PATH0_FROM_BASE, PATHS_FILLED, PATHS_SUPPLIED) of every position with a filled path."""
    order = [*[k for k in good.columns if k != "base_pv"], *[k for k in ADDED if k != "path_pv"], *rename.values(), "path_pv"]
    port_out = port.rename(rename)
    written, fills = 0, []
    c.out.parent.mkdir(parents=True, exist_ok=True)
    with open(c.out, "wb") as f:
        for lo in range(0, good.height, c.chunk):
            chunk = good.slice(lo, c.chunk)
            ids = chunk.get_column("position_id").to_numpy()
            part = paths.filter(pl.col("position_id").is_between(int(ids[0]), int(ids[-1])))
            m, filled, path0 = dense(chunk.get_column("base_pv").to_numpy(), np.searchsorted(ids, part.get_column("position_id").to_numpy()),
                                     part.get_column("path_num").to_numpy(), part.get_column("vmin").to_numpy(), c.width, c.fill_policy)
            rows = chunk.join(port_out, on="portfolio_id", how="left", maintain_order="left").with_columns(
                business_date=pl.lit(c.business_date), path0_from_base=pl.Series(path0), paths_filled=pl.Series(filled, dtype=pl.Int64),
                path_pv=pl.Series(m.tolist(), dtype=pl.List(pl.Float64)))
            rows.select(order).rename(names).write_ndjson(f)
            fills.append(pl.DataFrame({"POSITION_ID": ids, "PATH0_FROM_BASE": path0, "PATHS_FILLED": filled.astype(np.int64)})
                         .filter(pl.col("PATHS_FILLED") > 0))
            written += rows.height
            log.info("wrote %s of %s positions", f"{written:,}", f"{good.height:,}")
    fill = pl.concat(fills) if fills else pl.DataFrame(schema={"POSITION_ID": pl.Int64, "PATH0_FROM_BASE": pl.Boolean, "PATHS_FILLED": pl.Int64})
    return written, fill.with_columns(PATHS_SUPPLIED=c.width - pl.col("PATHS_FILLED"))


def fill_report(c: JoinConfig, fill: pl.DataFrame, written: int) -> dict:
    """The fill statistics of the summary: positions with path 0 from BASE_PV, with forward- (or base-) filled paths, the filled paths
    in all and per position in bands, and the top offenders. Writes the .fills.csv list."""
    fill.sort("PATHS_FILLED", "POSITION_ID", descending=[True, False]).write_csv(c.fills)
    bands = {"1": (1, 1), "2-10": (2, 10), "11-50": (11, 50), "51-100": (51, 100), "101-250": (101, 250), "251+": (251, 10**9)}
    per = fill.get_column("PATHS_FILLED")
    other = fill.filter(pl.col("PATHS_FILLED") > pl.col("PATH0_FROM_BASE").cast(pl.Int64))
    return {"POLICY": c.fill_policy, "POSITIONS_WRITTEN": written, "POSITIONS_WITH_FILLS": fill.height,
            "POSITIONS_PATH0_FROM_BASE": int(fill.get_column("PATH0_FROM_BASE").sum() or 0),
            "POSITIONS_OTHER_PATHS_FILLED": other.height, "PATHS_FILLED": int(per.sum() or 0),
            "SHARE_OF_PATHS_FILLED": round(int(per.sum() or 0) / max(1, written * c.width), 4),
            "POSITIONS_BY_PATHS_FILLED": {k: int(per.is_between(a, b).sum()) for k, (a, b) in bands.items()},
            "TOP": fill.sort("PATHS_FILLED", "POSITION_ID", descending=[True, False]).head(TOP_FILLED).to_dicts(), "LIST": str(c.fills)}


def write_audit(path: Path, bad: pl.DataFrame) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["POSITION_ID", "REASON", "DETAIL"])
        w.writerows(bad.iter_rows())


def run(c: JoinConfig) -> dict:
    """The join: reads, audits, writes (unless dry-run) and returns the summary it also writes beside the output."""
    t = Timer()
    log.info("pvf join of %s%s: positions %s, portfolios %s, paths %s", c.business_date, " (dry run)" if c.dry_run else "",
             c.positions, c.portfolio, c.paths)
    pos = read_positions(c)
    port, every_portfolio = read_portfolios(c)
    path_columns(c)
    t.lap(f"read {pos.height:,} positions and {port.height:,} portfolio rows valid on {c.business_date}")
    rename, names = output_names(pos.columns, port.columns)
    if c.dry_run:
        size = c.paths.stat().st_size / 1e9
        log.info("dry run: would scan %s (%.2f GB) and write %s columns to %s, audit %s", c.paths, size, len(names), c.out, c.audit)
        return {"DRY_RUN": True, "BUSINESS_DATE": c.business_date, "POSITIONS_READ": pos.height, "COLUMNS": list(names.values()),
                "PATHS_FILE_GB": round(size, 2), "OUTPUT": str(c.out)}
    g, counts = scan_paths(c, pos.select("position_id").unique() if c.limit_positions else None)
    t.lap(f"scanned {counts['ROWS']:,} path rows into {g.height:,} (position, path) pairs")
    if g.height == 0:
        raise PvfError(f"{c.paths} has no usable path rows of {c.business_date} ({counts['OTHER_DATE_OR_SET']:,} of another VALID_DT, "
                       f"risk metric, dataset or shock group; {counts['OUT_OF_RANGE_PATHS']:,} outside -1..{c.max_path}): check --date")
    bad = audit_rows(c, pos, port, g, every_portfolio)
    bad_ids = bad.select(pl.col("POSITION_ID").alias("position_id")).unique()
    good = pos.join(bad_ids, on="position_id", how="anti")
    if c.limit_positions is None:
        counts["ORPHAN_PV_POSITIONS"] = g.select("position_id").unique().join(pos.select("position_id"), on="position_id", how="anti").height
    counts["DUPLICATE_PATH_ROWS"] = int(g.filter(pl.col("n") > 1).select((pl.col("n") - 1).sum()).item() or 0)
    base = g.filter(pl.col("path_num") == -1).select("position_id", base_pv="vmin")
    good = good.join(base, on="position_id", how="inner").sort("position_id")
    paths = g.filter(pl.col("path_num") >= 0).join(good.select("position_id"), on="position_id", how="semi")
    written, fill = write_rows(c, good, paths, port, rename, names)
    fills = fill_report(c, fill, written)
    write_audit(c.audit, bad)
    t.lap(f"wrote {written:,} rows to {c.out}")
    reasons = dict(bad.group_by("REASON").len().sort("REASON").iter_rows())
    summary = {"BUSINESS_DATE": c.business_date, "POSITIONS_READ": pos.height, "POSITIONS_WRITTEN": written,
               "POSITIONS_EXCLUDED": bad_ids.height, "EXCLUDED_BY_REASON": reasons, "FILLS": fills,
               "PATHS_PER_POSITION": c.width, "PV_FILE_MODIFIED_UTC": modified_utc(c.paths), "PATH_ROWS": counts, "OUTPUT": str(c.out), "AUDIT": str(c.audit),
               "SECONDS": t.seconds, "PEAK_MEMORY_MB": peak_memory_mb()}
    c.summary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    log.info("summary: %s positions in, %s written, %s excluded %s; fills (%s): %s positions, path 0 from BASE_PV in %s, %s paths "
             "(%.2f%% of all); %ss, peak memory %s MB; %s", f"{pos.height:,}", f"{written:,}", f"{bad_ids.height:,}", reasons, c.fill_policy,
             f"{fills['POSITIONS_WITH_FILLS']:,}", f"{fills['POSITIONS_PATH0_FROM_BASE']:,}", f"{fills['PATHS_FILLED']:,}",
             100 * fills["SHARE_OF_PATHS_FILLED"], summary["SECONDS"], summary["PEAK_MEMORY_MB"], c.summary)
    return summary


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--portfolio", required=True, type=Path, help="PORTFOLIO_D2 as CSV (data/pvf/portfolio_d2.csv)")
    p.add_argument("--positions", required=True, type=Path, help="the positions as CSV (data/pvf/pos_20261029.csv)")
    p.add_argument("--paths", required=True, type=Path, help="the per-path PVs as CSV (data/pvf/pvf_path_20261029.csv); scanned lazily")
    p.add_argument("--out", required=True, type=Path, help="the joined JSON Lines file to write; the audit and the summary go beside it")
    p.add_argument("--audit", type=Path, help="the audit CSV (default: <out-stem>.audit.csv)")
    p.add_argument("--business-date", help="YYYY-MM-DD (default: the date in the --paths file name, 20261029 -> 2026-10-29)")
    p.add_argument("--max-path", type=int, default=250, help="the last path number: PATH_PV holds paths 0..N (default 250: 251 values)")
    p.add_argument("--risk-metric", default="PV", help="keep path rows of this risk_metric_cd (default PV; '' keeps every one)")
    p.add_argument("--dataset", default="HistoVAR", help="keep path rows of this dataset_cd (default HistoVAR; '' keeps every one)")
    p.add_argument("--shock-group", default="ALL", help="keep path rows of this shock_group_nm (default ALL; '' keeps every one)")
    p.add_argument("--limit-positions", type=int, help="only the first N positions of the positions file: a quick trial")
    p.add_argument("--fill-policy", choices=FILL_POLICIES, default="forward",
                   help="a missing path: forward (default: path 0 = BASE_PV, then the previous path's value), base (BASE_PV), refuse (exclude the position)")
    p.add_argument("--chunk", type=int, default=50_000, help="positions written per step; bounds memory to about chunk x 2 KB (default 50,000)")
    p.add_argument("--dry-run", action="store_true", help="check the inputs and the columns, say what would be written; write nothing")
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    return p


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        run(JoinConfig(a.portfolio, a.positions, a.paths, a.out, a.audit, a.business_date, a.max_path, a.risk_metric, a.dataset,
                       a.shock_group, a.limit_positions, a.chunk, a.dry_run, a.fill_policy))
    except (PvfError, OSError, pl.exceptions.PolarsError) as e:
        log.error("pvf join refused: %s", str(e).splitlines()[0] if str(e) else type(e).__name__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
