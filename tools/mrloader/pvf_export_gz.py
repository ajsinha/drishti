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

"""Maps joined PVF rows (pvf_join.py) to scenario-pv records by the mapping (kinds/scenario-pv.pvf.yaml) and writes them as the data inbox
and `lake build` take them: one file per kind and business date, named for its table and date, with its .ready marker (and the plain
JSON Lines beside it), the date's scenario set the same way.

examples (from the repository root):
  python3 tools/mrloader/pvf_export_gz.py --joined data/pvf/mr-2026-10-29/pvf_joined.2026-10-29.jsonl --out data/pvf/mr-2026-10-29
  python3 tools/mrloader/pvf_export_gz.py ... --limit 100 --dry-run      map and check the first 100 rows; write nothing

writes, under --out (the date from the rows' BUSINESS_DATE):
  scenario-pv.<date>.jsonl                 the records, plain JSON Lines, in POSITION_ID order (--no-plain: left out)
  scenario-pv.<date>.jsonl.gz (+ .ready)   the same records, the file that is loaded (pack, kind, mode replace, rows, sha256, lake)
  scenario-set.<date>.jsonl, .jsonl.gz (+ .ready)   the date's scenario set (the mapping's scenario-set; --no-scenario-set: none)
  mr-<date>.sha256                         the SHA-256 of every loadable file of the folder (sha256sum -c reads it)
  scenario-pv.<date>.rejects.csv           rows that could not be mapped or break the contract (the reject report: KEY is the position)
  scenario-pv.<date>.summary.json          rows in and out, rejected by reason, statuses, seconds, peak memory
--rows-per-part N splits the date into parts of N records packed as one scenario-pv-<date>.tar (loads completely or not at all) instead
of the one .jsonl.gz: only for a date too large for one file.
Every record is checked against the contract as it is written (required fields but the mapping's omit-required, types, enums, patterns,
UPPER_SNAKE names; the source attributes kept by pass-through ride beside the contract's fields) and the date as a whole (one set, one
batch, every vector as long as the set: never padded). Exit 0 every row written, 2 written with rows rejected, 1 refused (nothing loadable).
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import (DEFAULT_MAPPING, PACK, PvfError, Timer, iso_date, log, lower_keys, peak_memory_mb, read_jsonl,  # noqa: E402
                             setup_logging)
from mrloader.contract import DateCheck, record_rules, scenario_set  # noqa: E402
from mrloader.mapping import Mapping, RowProblem, scenario_dates, scenario_set_record  # noqa: E402
from mrloader.package import KindWriter, write_records, write_sha256  # noqa: E402
from mrloader.rejects import Problem, Rejects  # noqa: E402

CONTRACT_BREACH = "CONTRACT"
WRONG_DATE = "WRONG_BUSINESS_DATE"


@dataclass
class ExportConfig:
    """What one export writes. ``run`` overrides the mapping's run values (None: keep the file's); ``business_date``, when given,
    must be every row's BUSINESS_DATE; ``with_set`` None follows the mapping's scenario-set.emit; ``rows_per_part`` (None: one file)
    splits the date into parts packed as one tar; ``plain`` writes the plain .jsonl beside the loadable file."""

    joined: Path
    out: Path
    mapping: Path = DEFAULT_MAPPING
    run: dict = field(default_factory=dict)
    business_date: str | None = None
    dates: str | None = None
    rows_per_part: int | None = None
    with_set: bool | None = None
    plain: bool = True
    lake: str | None = None
    limit: int | None = None
    dry_run: bool = False

    def __post_init__(self) -> None:
        self.joined, self.out, self.mapping = Path(self.joined), Path(self.out), Path(self.mapping)
        if not self.joined.is_file():
            raise PvfError(f"--joined: {self.joined} is not a file")
        if self.business_date:
            self.business_date = iso_date(self.business_date)
        if (self.rows_per_part is not None and self.rows_per_part < 1) or (self.limit is not None and self.limit < 1):
            raise PvfError("--rows-per-part and --limit must be 1 or more")


class Outputs:
    """The files of one export: the scenario-pv writer (plain and loadable) and the reject report; closed together."""

    def __init__(self, c: ExportConfig, bd: str):
        self.dry = c.dry_run
        self.bd = bd
        self.source = c.joined.name
        self.rejects_path = c.out / f"scenario-pv.{bd}.rejects.csv"
        self.summary_path = c.out / f"scenario-pv.{bd}.summary.json"
        self.written = 0
        self.writer = None
        if not self.dry:
            c.out.mkdir(parents=True, exist_ok=True)
            self.writer = KindWriter(c.out, "scenario-pv", bd, PACK, c.plain, c.rows_per_part)
        self.rejects = Rejects(None if self.dry else self.rejects_path)

    @property
    def audit_rows(self) -> Counter:
        return self.rejects.by_reason

    def record(self, rec: dict) -> None:
        self.written += 1
        if not self.dry:
            self.writer.write(rec)

    def audit(self, row: int, position_id, reason: str, detail: str) -> None:
        if self.dry:
            log.info("would reject %s %s: %s", position_id, reason, detail)
        self.rejects.add(self.source, row, position_id, Problem(reason, detail))

    def close(self) -> None:
        if self.writer is not None:
            self.writer.close()
        self.rejects.close()


def _rows(c: ExportConfig) -> tuple[dict, Iterator[dict]]:
    """The first row (checked: UPPER_SNAKE names, a business date) and an iterator over all rows, at most --limit."""
    it = read_jsonl(c.joined)
    first = next(it, None)
    if first is None:
        raise PvfError(f"{c.joined} has no rows")
    bad = lower_keys(first)
    if bad:
        raise PvfError(f"{c.joined}: attribute names not UPPER_SNAKE ({bad[:5]}): write it with pvf_join.py")

    def all_rows() -> Iterator[dict]:
        yield first
        for n, r in enumerate(it, 2):
            if c.limit and n > c.limit:
                return
            yield r
    return first, all_rows()


def join_facts(joined: Path) -> dict:
    """What the join recorded about the extract (its summary beside the joined file): the PV file's last-modified time."""
    summary = joined.with_name(joined.name.removesuffix(".jsonl") + ".summary.json")
    try:
        s = json.loads(summary.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return {"pv_file_modified": s["PV_FILE_MODIFIED_UTC"]} if s.get("PV_FILE_MODIFIED_UTC") else {}


def map_rows(c: ExportConfig, m: Mapping, ctx: dict, rows: Iterator[dict], out: Outputs, check: DateCheck) -> tuple[int, Counter]:
    """Each row mapped, checked against the contract and the engine's per-record rules, then written or audited; (rows read, statuses)."""
    order, count = str(m.set.get("path_order", "oldest-first")), int(m.set.get("count", 0)) or None
    omitted = frozenset(m.omit)
    statuses: Counter = Counter()
    n = 0
    for n, row in enumerate(rows, 1):
        pid = row.get("POSITION_ID")
        if str(row.get("BUSINESS_DATE", ""))[:10] != ctx["business_date"]:
            out.audit(n, pid, WRONG_DATE, f"BUSINESS_DATE {row.get('BUSINESS_DATE')} is not {ctx['business_date']}: one file is one business date")
            continue
        try:
            rec = m.apply(row, ctx, order, count)
        except RowProblem as e:
            out.audit(n, pid, e.reason, e.detail)
            continue
        problems = m.contract.check(rec, extra_ok=True, omitted=omitted) + record_rules(rec)
        if problems:
            out.audit(n, pid, CONTRACT_BREACH, "; ".join(problems))
            continue
        check.add_pv(rec)
        statuses[rec.get("STATUS")] += 1
        q = rec.get("QUANTITY")
        if isinstance(q, (int, float)):
            statuses["QUANTITY_" + ("NEGATIVE" if q < 0 else "ZERO" if q == 0 else "POSITIVE")] += 1
        out.record(rec)
        if out.written % 50_000 == 0:
            log.info("mapped %s records", f"{out.written:,}")
    return n, statuses


def quantity_note(m: Mapping, sources: dict, signs: dict, written: int) -> str:
    """The summary's line on QUANTITY's sign: "position_amt unsigned: N of N positive" when the unsigned amount fed it unsigned."""
    sf = (m.fields.get("QUANTITY") or {}).get("sign_from")
    counts = f"{signs.get('POSITIVE', 0):,} of {written:,} positive, {signs.get('ZERO', 0):,} zero, {signs.get('NEGATIVE', 0):,} negative"
    if sources.get("POSITION_AMT") and not sf:
        return (f"position_amt unsigned: {counts} (POSITION_AMT fed {sources['POSITION_AMT']:,} rows; no sign in the extract: DIRECTION is "
                "LONG by the contract's rule and not meaningful; VaR reads BASE_PV and SCENARIO_PV)")
    return f"QUANTITY {'signed by ' + sf['column'] + ': ' if sf else ''}{counts}"


def write_set(c: ExportConfig, m: Mapping, ctx: dict, out: Outputs, check: DateCheck) -> dict | None:
    """The date's scenario-set record, checked against its contract, written (unless dry-run); None when not emitted."""
    if not (m.set.get("emit", True) if c.with_set is None else c.with_set):
        return None
    days, words = scenario_dates(m, ctx["business_date"], c.dates)
    rec = scenario_set_record(m, ctx, days, words)
    bad = scenario_set().check(rec)
    if bad:
        raise PvfError("the scenario-set record breaks its contract: " + "; ".join(bad))
    check.add_set(rec)
    return rec


def package(c: ExportConfig, out: Outputs, set_rec: dict | None) -> dict:
    """The markers (each loadable file's .ready), the scenario set's files, and the day's .sha256 list."""
    files = [str(out.writer.finish(c.lake))] if out.written else []
    if set_rec:
        files.insert(0, str(write_records(c.out, "scenario-set", out.bd, PACK, [set_rec], c.lake, plain=c.plain)))
    sha = write_sha256(c.out, out.bd)
    return {"FILES": files, "SHA256": str(sha)}


def export(c: ExportConfig) -> dict:
    """The export: mapping checked first (fail fast), rows mapped and written, the date checked as a whole, then packaged."""
    t = Timer()
    m = Mapping(c.mapping, c.run)
    first, rows = _rows(c)
    bd = c.business_date or iso_date(str(first.get("BUSINESS_DATE") or ""))
    ctx = m.resolve_run(bd, join_facts(c.joined))
    log.info("pvf export of %s%s: %s -> %s (mapping %s, scenario set %s of %s paths, %s)", bd, " (dry run)" if c.dry_run else "", c.joined,
             c.out, c.mapping, ctx["scenario_set_id"], m.set.get("count"), m.set.get("path_order", "oldest-first"))
    out, check = Outputs(c, bd), DateCheck()
    try:
        read, statuses = map_rows(c, m, ctx, rows, out, check)
    finally:
        out.close()
    t.lap(f"mapped {read:,} rows: {out.written:,} records, {sum(out.audit_rows.values()):,} audited {dict(out.audit_rows)}")
    signs = {k.removeprefix("QUANTITY_"): statuses.pop(k) for k in [k for k in statuses if str(k).startswith("QUANTITY_")]}
    q_sources = dict(m.sources.get("QUANTITY", {}))
    sign_note = quantity_note(m, q_sources, signs, out.written)
    log.info("QUANTITY from %s; %s", q_sources or "its rule", sign_note)
    set_rec = write_set(c, m, ctx, out, check)
    problems = [p for p in check.problems() if set_rec or "no scenario-set record" not in p]
    summary = {"BUSINESS_DATE": bd, "DRY_RUN": c.dry_run, "ROWS_READ": read, "RECORDS_WRITTEN": out.written,
               "ROWS_AUDITED": sum(out.audit_rows.values()), "AUDITED_BY_REASON": dict(out.audit_rows), "STATUS": dict(statuses), "QUANTITY_SOURCE": q_sources, "QUANTITY_SIGNS": signs,
               "QUANTITY_NOTE": sign_note, "FIELD_SOURCES": {k: dict(v) for k, v in m.sources.items()},
               "SCENARIO_SET_ID": ctx["scenario_set_id"], "SCENARIO_COUNT": m.set.get("count"),
               "PATH_ORDER": m.set.get("path_order", "oldest-first"), "BATCH_ID": ctx["batch_id"], "SCENARIO_SET_WRITTEN": bool(set_rec),
               "SCENARIO_DATES": (set_rec or {}).get("DESCRIPTION"), "REJECTS": str(out.rejects_path), "DATE_PROBLEMS": problems,
               "PLAIN_JSONL": str(out.writer.plain_path) if out.writer is not None and out.writer.plain_path else None, "CONTRACT_GAPS": m.omit,
               "COMPUTED_AT": ctx["computed_at"]}
    if problems:
        if out.writer is not None:
            out.writer.discard()
        raise PvfError("the date's records do not hold together: " + "; ".join(problems))
    if not c.dry_run:
        summary.update(package(c, out, set_rec))
    summary.update({"SECONDS": t.seconds, "PEAK_MEMORY_MB": peak_memory_mb()})
    if not c.dry_run:
        out.summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    log.info("summary: %s rows in, %s records %s, %s audited; scenario set %s; %s; %ss, peak memory %s MB", f"{read:,}", f"{out.written:,}",
             dict(statuses), summary["ROWS_AUDITED"], ctx["scenario_set_id"], ", ".join(Path(f).name for f in summary.get("FILES") or []) or "nothing written", summary["SECONDS"],
             summary["PEAK_MEMORY_MB"])
    return summary


def add_mapping_arguments(p: argparse.ArgumentParser) -> None:
    """The options the export and the driver (pvf_run.py) share."""
    p.add_argument("--mapping", type=Path, default=DEFAULT_MAPPING, help="the mapping file (default tools/mrloader/kinds/scenario-pv.pvf.yaml)")
    p.add_argument("--scenario-set-id", help="the scenario set's id (^HS-), instead of kinds/scenario-pv.pvf.yaml run.scenario_set_id")
    p.add_argument("--batch-id", help="the batch id, instead of run.batch_id")
    p.add_argument("--computed-at", help="the run's ISO-8601 UTC time (2026-10-29T06:00:00Z), instead of run.computed_at")
    p.add_argument("--restatement", type=int, help="0 for the date's first run, then 1, 2 ... (instead of run.restatement)")
    p.add_argument("--scenario-dates", help="'weekdays' or a file of the set's dates, one YYYY-MM-DD a line, oldest first")
    p.add_argument("--no-scenario-set", action="store_true", help="no scenario-set record (the lake already holds the date's set)")
    p.add_argument("--rows-per-part", type=int, help="split the date into parts of N records packed as one tar (only for a date too large "
                   "for one file; default: one .jsonl.gz)")
    p.add_argument("--no-plain", action="store_true", help="leave out the plain .jsonl beside the loadable file (a very large date)")
    p.add_argument("--lake", help="the lake the .ready file names (default: the inbox's drishti.dataload.lake)")


def run_overrides(a: argparse.Namespace) -> dict:
    return {"scenario_set_id": a.scenario_set_id, "batch_id": a.batch_id, "computed_at": a.computed_at, "restatement": a.restatement}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--joined", required=True, type=Path, help="the joined JSON Lines file pvf_join.py wrote")
    p.add_argument("--out", required=True, type=Path, help="the folder the tables' folders, the .tar and the audit go to")
    p.add_argument("--date", help="the business date YYYY-MM-DD every row must carry (default: the first row's BUSINESS_DATE)")
    p.add_argument("--limit", type=int, help="only the first N rows: a quick trial")
    p.add_argument("--dry-run", action="store_true", help="map and check every row, say what would be written; write nothing")
    add_mapping_arguments(p)
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    a = p.parse_args(argv)
    setup_logging(a.verbose)
    try:
        s = export(ExportConfig(a.joined, a.out, a.mapping, run_overrides(a), a.date, a.scenario_dates, a.rows_per_part,
                                False if a.no_scenario_set else None, not a.no_plain, a.lake, a.limit, a.dry_run))
    except (PvfError, OSError) as e:
        log.error("pvf export refused: %s", e)
        return 1
    return 2 if s["ROWS_AUDITED"] else 0


if __name__ == "__main__":
    sys.exit(main())
