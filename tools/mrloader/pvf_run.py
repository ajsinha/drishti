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

"""The pvf driver: from a PVF extract (the per-path PV file, the portfolio file and the positions file) to Drishti scenario PVs in one
go: join, map by mapping.yaml (UPPER_SNAKE names), check against the contract, write the plain .jsonl and the loadable .jsonl.gz layout.

examples (from the repository root):
  python3 tools/mrloader/pvf_run.py --pv data/pvf/pvf_path_20261029.csv --portfolio data/pvf/portfolio_d2.csv \\
      --positions data/pvf/pos_20261029.csv --date 2026-10-29
  python3 tools/mrloader/pvf_run.py ... --limit 2000             a quick trial on the first 2,000 positions
  python3 tools/mrloader/pvf_run.py ... --dry-run                check the inputs and the mapping; write nothing
  python3 tools/mrloader/pvf_run.py ... --fx data/pvf/fx_20261029.csv --inbox    the day in one go: set, FX, PVs, handed over in that order
  python3 tools/mrloader/pvf_run.py ... --inbox                  also hand both archives to the data inbox (data/dataload/inbox):
                                                            the running server loads them (the scenario set first)

--positions is required: the PV file carries POSITION_ID only (no portfolio, currency, trade or amount), so the positions file is what
links each position to its portfolio and gives the record its attributes; a PV file alone cannot be joined.
--date is the business date (BUSINESS_DATE, VALUATION_DATE and BASE_VALUATION_DATE of every record): only PV rows whose VALID_DT is
that date are read, and it names the output folder and files.

writes, under <out>/pvf-<date>/ (--out defaults to the PV file's folder):
  pvf_joined.<date>.jsonl          the join: one row per position, every source attribute (UPPER_SNAKE), BASE_PV, PATH_PV, PATHS_FILLED
  pvf_joined.<date>.audit.csv      positions the join excluded (MISSING_PORTFOLIO, MISSING_BASE_PV, ...), POSITION_ID, REASON, DETAIL
  pvf_joined.<date>.fills.csv      every position with a filled path (PATH0_FROM_BASE, PATHS_FILLED, PATHS_SUPPLIED), most filled first
  scenario_pv.<date>.jsonl         the scenario-pv records with every other source attribute beside them (UPPER_SNAKE), one plain
                                   JSON Lines file (loadable on its own: table and date in its name)
  scenario-pv/<date>/cob.part-NNN.jsonl.gz, scenario-set/<date>/cob.jsonl.gz   the loadable layout, and one archive per kind:
  scenario-pv-<date>.tar, scenario-set-<date>.tar (each with its .ready), pvf-<date>.sha256 (every part and archive)
  pvf-<date>.export-audit.csv      rows the mapping or the contract refused
  pvf-<date>.summary.json          the whole run: rows in and out at each step, audited by reason, seconds, peak memory
Exit 0 done (the audits list what was left out), 1 refused (one line saying why), 2 usage.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import (DEFAULT_INBOX, INBOX_ORDER, PvfError, Timer, log, peak_memory_mb, setup_logging, strict_date,  # noqa: E402,F401
                        to_inbox)
from mrloader.mapping import Mapping  # noqa: E402
from mrloader.pvf_fx_scenario import FxConfig, build_fx, read_fx_file  # noqa: E402
from mrloader.mapping import scenario_dates  # noqa: E402
from mrloader.pvf_export_gz import ExportConfig, add_mapping_arguments, export, run_overrides  # noqa: E402
from mrloader.pvf_join import FILL_POLICIES, JoinConfig, modified_utc, run as join  # noqa: E402


REPO = Path(__file__).resolve().parents[2]


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    i = p.add_argument_group("inputs (all three required)")
    i.add_argument("--pv", required=True, type=Path, help="the per-path PV file, CSV (pvf_path_YYYYMMDD.csv); scanned lazily")
    i.add_argument("--portfolio", required=True, type=Path, help="PORTFOLIO_D2, CSV (portfolio_d2.csv)")
    i.add_argument("--positions", required=True, type=Path, help="the positions, CSV (pos_YYYYMMDD.csv): links each position to its portfolio")
    i.add_argument("--date", required=True, type=strict_date, help="the business date YYYY-MM-DD (the PV rows' VALID_DT)")
    o = p.add_argument_group("output and run")
    o.add_argument("--out", type=Path, help="the folder pvf-<date>/ is written in (default: the PV file's folder)")
    o.add_argument("--limit", type=int, help="only the first N positions of the positions file: a quick trial")
    o.add_argument("--dry-run", action="store_true", help="check the inputs, the columns and the mapping; write nothing")
    o.add_argument("--max-path", type=int, default=250, help="the last path number: vectors of paths 0..N (default 250: 251 values)")
    o.add_argument("--fill-policy", choices=FILL_POLICIES, default="forward",
                   help="a missing path: forward (default, the original rule: path 0 = BASE_PV, then the previous path), base (BASE_PV), "
                        "refuse (exclude the position); fills are listed in pvf_joined.<date>.fills.csv")
    o.add_argument("--chunk", type=int, default=50_000, help="positions joined per step (memory: about chunk x 2 KB; default 50,000)")
    o.add_argument("--inbox", nargs="?", type=Path, const=DEFAULT_INBOX, metavar="DIR",
                   help="after the export, copy each archive and then its .ready into the data inbox the running server watches "
                        f"(default {DEFAULT_INBOX.relative_to(REPO)}); the scenario set goes first, so the PVs find their set")
    o.add_argument("--fx", type=Path, metavar="FILE",
                   help="also build the date's scenario FX from this CSV (pvf_fx_scenario.py's long or wide form) against the set this run "
                        "writes: fx-scenario-<date>.tar beside the others; with --inbox the order is set, FX, PVs")
    o.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    add_mapping_arguments(p.add_argument_group("mapping (tools/mrloader/mapping.yaml)"))
    return p


def run(a: argparse.Namespace) -> dict:
    t = Timer()
    folder = (a.out or a.pv.resolve().parent) / f"pvf-{a.date}"
    ctx = Mapping(a.mapping, run_overrides(a)).resolve_run(a.date, {"pv_file_modified": modified_utc(a.pv)})   # fail fast, before 4 GB are read
    fx_table = None
    if a.fx:                                                     # the FX file is checked against the set's days before the PV file is read
        if a.no_scenario_set:
            raise PvfError("--fx joins to the scenario set this run writes: use pvf_fx_scenario.py with --scenario-set for a set already loaded")
        days, _ = scenario_dates(Mapping(a.mapping, run_overrides(a)), a.date, a.scenario_dates)
        fx_table = read_fx_file(a.fx, a.date, days)
    joined = folder / f"pvf_joined.{a.date}.jsonl"
    j = join(JoinConfig(a.portfolio, a.positions, a.pv, joined, business_date=a.date, max_path=a.max_path,
                        limit_positions=a.limit, chunk=a.chunk, dry_run=a.dry_run, fill_policy=a.fill_policy))
    if a.dry_run:
        log.info("dry run: the join and the mapping are ready; would write %s", folder)
        return {"DRY_RUN": True, "JOIN": j}
    e = export(ExportConfig(joined, folder, a.mapping, run_overrides(a), a.date, a.scenario_dates, a.rows_per_part,
                            False if a.no_scenario_set else None, not a.no_tar, folder / f"scenario_pv.{a.date}.jsonl", a.lake))
    fx = None
    if fx_table is not None:
        fx = build_fx(FxConfig(a.date, fx_table, ctx["scenario_set_id"], days, ctx["batch_id"], folder, a.lake))
        e["TARS"] = [*(e.get("TARS") or []), fx["TAR"]]
    summary = {"BUSINESS_DATE": a.date, "FOLDER": str(folder), "POSITIONS_READ": j["POSITIONS_READ"],
               "JOIN_EXCLUDED": j["POSITIONS_EXCLUDED"], "JOIN_EXCLUDED_BY_REASON": j["EXCLUDED_BY_REASON"], "FILLS": j["FILLS"],
               "EXPORT_AUDITED": e["ROWS_AUDITED"], "EXPORT_AUDITED_BY_REASON": e["AUDITED_BY_REASON"], "RECORDS_WRITTEN": e["RECORDS_WRITTEN"],
               "STATUS": e["STATUS"], "QUANTITY_NOTE": e["QUANTITY_NOTE"], "SCENARIO_SET_ID": e["SCENARIO_SET_ID"], "TARS": e.get("TARS"), "PLAIN_JSONL": e["PLAIN_JSONL"],
               "SECONDS": t.seconds, "PEAK_MEMORY_MB": peak_memory_mb(), "JOIN": j, "EXPORT": e, "FX": fx}
    if a.inbox:
        if not e.get("TARS"):
            raise PvfError("--inbox needs the archives: run without --no-tar")
        summary["INBOX"] = {"FOLDER": str(a.inbox), "DELIVERED": to_inbox(e["TARS"], a.inbox)}
    (folder / f"pvf-{a.date}.summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    log.info("pvf run of %s: %s positions in, %s excluded by the join, %s by the mapping, %s records written; %ss, peak memory %s MB; %s",
             a.date, f"{j['POSITIONS_READ']:,}", f"{j['POSITIONS_EXCLUDED']:,}", e["ROWS_AUDITED"], f"{e['RECORDS_WRITTEN']:,}",
             summary["SECONDS"], summary["PEAK_MEMORY_MB"], folder)
    return summary


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        s = run(a)
    except (PvfError, OSError) as e:
        log.error("pvf run refused: %s", str(e).splitlines()[0] if str(e) else type(e).__name__)
        return 1
    except Exception as e:                                       # a Polars error: one line, never a traceback for the owner
        if type(e).__module__.startswith("polars"):
            log.error("pvf run refused: %s", str(e).splitlines()[0])
            return 1
        raise
    if not s.get("DRY_RUN") and (s["JOIN_EXCLUDED"] or s["EXPORT_AUDITED"]):
        log.info("left out: %s positions by the join, %s rows by the mapping: see the audit files", s["JOIN_EXCLUDED"], s["EXPORT_AUDITED"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
