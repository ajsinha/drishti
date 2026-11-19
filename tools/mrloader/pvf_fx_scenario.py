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

"""Scenario FX of a business date: ADD it from a CSV you produce every day, or REMOVE it. One row per currency and business date, in
the market-risk pack's fx-scenario kind: USD per 1 unit of the currency today (RATE) and under each scenario day (RATES, one per day of
the date's scenario set, in the set's SCENARIO_DATES order). The VaR engine converts each scenario's PV with it:
P&L(s) = PV(s) x FX(s) - PV(base) x FX(base); without a currency here a trade priced in it is rejected (DRS-5207).

examples (from the repository root):
  python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --fx data/pvf/fx_20261118.csv                 add: builds fx-scenario-2026-11-18.tar
  python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --fx data/pvf/fx_20261118.csv --inbox         ... and hands it to the data inbox
  python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --fx FILE --scenario-set path/scenario-set-2026-11-18.tar
  python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --remove --lake risklake --dry-run            remove: what would go (all currencies of the date)
  python3 tools/mrloader/pvf_fx_scenario.py --date 2026-11-18 --remove --lake risklake --yes --no-signal

the FX file, either form (a header row; names in any case, columns in any order, # comment lines allowed):
  long   CURRENCY,SCENARIO_DATE,RATE       one row per currency and day; the row whose SCENARIO_DATE is the business date is today's rate
  wide   CURRENCY,RATE_TODAY,2026-01-02,2026-01-05,...   one row per currency; one column per scenario day (the column heading is the day)
Rates are USD per 1 unit of the currency (EUR 1.08 means 1 EUR = 1.08 USD). USD itself is never sent (it is 1 by definition) and is
refused. Rows may be in any order: each rate is placed by its day, so RATES follows the set's SCENARIO_DATES whatever the file's order.
A currency with a missing or extra day, a day not in the set, a repeated row, or a rate that is not a positive number refuses the
whole file, listing every problem (nothing is written).

the scenario set the days come from (the date's one normal set), first found:
  --scenario-set PATH      a scenario-set .tar, .jsonl[.gz] or folder (what pvf_scenario_set.py wrote)
  --dates FILE [--order]   with --scenario-set-id: the days of a dates file (one YYYY-MM-DD a line; --order says how it is ordered)
  neither                  <out>/pvf-<date>/scenario-set/<date>/cob.jsonl.gz, the set just made (--out default data/pvf)

writes under <out>/pvf-<date>/: fx-scenario/<date>/cob.jsonl.gz, fx-scenario-<date>.tar and its .ready (--lake names the lake in it).
--inbox copies the archive and then its .ready to the data inbox the running server watches (the date's scenario set must be loaded
first; pvf_run.py --fx hands set, FX and PVs over in that order).
--remove deletes the date's fx-scenario rows with `tools/drishti.py lake delete --kind fx-scenario` (whole dates only: every currency
of the date goes). Exit 0 done, 1 refused (one line saying why), 2 usage.
"""
from __future__ import annotations

import argparse
import csv
import io
import math
import sys
from dataclasses import dataclass
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import (DEFAULT_INBOX, DEFAULT_OUT, PvfError, add_remove_arguments, iso_date, lake_delete, log, read_any, read_dates_file,  # noqa: E402
                        setup_logging, strict_date, to_inbox, write_kind)
from mrloader.contract import fx_scenario  # noqa: E402

KIND = "fx-scenario"
SOURCE = "pvf-daily-fx"
_SHOWN = 20                                                       # problems listed in a refusal


@dataclass
class FxConfig:
    """What one FX build writes: the date, its currencies (``table``: currency -> {day: rate}, today's rate under the date itself),
    the set it joins to, and where it goes."""

    business_date: str
    table: dict
    scenario_set_id: str
    days: list
    batch_id: str
    out: Path
    lake: str | None = None
    source: str = SOURCE


# ---- reading the file ----------------------------------------------------------------------------------------------------------------
def _rate(text: str, where: str, problems: list[str]) -> float | None:
    try:
        v = float(str(text).strip())
    except ValueError:
        problems.append(f"{where}: {text!r} is not a number")
        return None
    if not math.isfinite(v) or v <= 0:
        problems.append(f"{where}: the rate {text!r} is not a positive number")
        return None
    return v


def _currency(text: str, where: str, problems: list[str]) -> str | None:
    c = str(text).strip().upper()
    if len(c) != 3 or not c.isalpha():
        problems.append(f"{where}: {text!r} is not a currency code (three letters)")
    elif c == "USD":
        problems.append(f"{where}: USD is never sent (it is 1 by definition): remove its row")
    else:
        return c
    return None


def parse_fx_text(text: str, business_date: str, name: str = "the FX file") -> dict:
    """The FX CSV (long or wide, see the module help) as {currency: {day: rate}}, today's rate under ``business_date``. Refuses a bad
    code, date, rate, USD or a repeat, listing every problem found (the first 20)."""
    rows = [r for r in csv.reader(io.StringIO(text.lstrip("﻿"))) if r and any(c.strip() for c in r) and not r[0].lstrip().startswith("#")]
    if not rows:
        raise PvfError(f"{name} has no rows")
    head = [h.strip().upper() for h in rows[0]]
    problems: list[str] = []
    table: dict = {}

    def put(ccy: str, day: str, rate: float, where: str) -> None:
        if day in table.setdefault(ccy, {}):
            problems.append(f"{where}: {ccy} on {day} appears more than once")
        else:
            table[ccy][day] = rate
    if "CURRENCY" not in head:
        raise PvfError(f"{name}: the header needs CURRENCY (long form CURRENCY,SCENARIO_DATE,RATE; wide form CURRENCY,RATE_TODAY,<day>,...)")
    ci = head.index("CURRENCY")
    if "SCENARIO_DATE" in head and "RATE" in head:                 # long
        di, ri = head.index("SCENARIO_DATE"), head.index("RATE")
        for n, r in enumerate(rows[1:], 2):
            where = f"{name} line {n}"
            if len(r) <= max(ci, di, ri):
                problems.append(f"{where}: too few columns")
                continue
            ccy, rate = _currency(r[ci], where, problems), _rate(r[ri], where, problems)
            try:
                day = iso_date(r[di])
            except PvfError as e:
                problems.append(f"{where}: {e}")
                continue
            if ccy and rate is not None:
                put(ccy, day, rate, where)
    elif "RATE_TODAY" in head:                                     # wide
        ti = head.index("RATE_TODAY")
        days: dict[int, str] = {}
        for i, h in enumerate(rows[0]):
            if i in (ci, ti):
                continue
            try:
                days[i] = iso_date(h)
            except PvfError:
                problems.append(f"{name} header: column {h!r} is neither CURRENCY, RATE_TODAY nor a day YYYY-MM-DD")
        for n, r in enumerate(rows[1:], 2):
            where = f"{name} line {n}"
            if len(r) < len(head):
                problems.append(f"{where}: {len(r)} columns, the header has {len(head)}")
                continue
            ccy = _currency(r[ci], where, problems)
            if not ccy:
                continue
            for i, v in [(ti, business_date), *days.items()]:
                day = business_date if i == ti else v
                rate = _rate(r[i], f"{where} {ccy} {day}", problems)
                if rate is not None:
                    put(ccy, day, rate, where)
            if len(days) != len(set(days.values())):
                problems.append(f"{name} header: a day appears in two columns")
    else:
        raise PvfError(f"{name}: not the long form (CURRENCY,SCENARIO_DATE,RATE) nor the wide form (CURRENCY,RATE_TODAY,<day>,...)")
    _refuse(problems)
    return table


def _refuse(problems: list[str]) -> None:
    if problems:
        more = f" (and {len(problems) - _SHOWN} more)" if len(problems) > _SHOWN else ""
        raise PvfError(f"{len(problems)} problem(s): " + "; ".join(problems[:_SHOWN]) + more)


def read_fx_file(path: Path, business_date: str, days: list[str]) -> dict:
    """The FX file, parsed and joined to the set's ``days``: every currency has today's rate and exactly one rate per day of the set."""
    path = Path(path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise PvfError(f"cannot read the FX file {path}: {e}") from None
    table = parse_fx_text(text, business_date, path.name)
    check_days(table, business_date, days)
    return table


def check_days(table: dict, business_date: str, days: list[str]) -> None:
    """Every currency: today's rate, each day of the set once, no day the set does not have."""
    want, problems = set(days), []
    for ccy, byday in sorted(table.items()):
        have = set(byday) - {business_date}
        if business_date not in byday:
            problems.append(f"{ccy}: no rate for today ({business_date}): the row (long) or RATE_TODAY (wide)")
        if want - have:
            miss = sorted(want - have)
            problems.append(f"{ccy}: {len(miss)} scenario day(s) missing, first {miss[0]}")
        if have - want:
            extra = sorted(have - want)
            problems.append(f"{ccy}: {len(extra)} day(s) not in the scenario set, first {extra[0]}")
    _refuse(problems)


# ---- the set the days come from ------------------------------------------------------------------------------------------------------
def load_set(path: Path, business_date: str, set_id: str | None = None) -> dict:
    """The date's normal scenario-set record from a .tar, .jsonl[.gz] or folder (the one named ``set_id`` when several)."""
    found = [r for _, r in read_any(Path(path)) if r.get("BUSINESS_DATE") == business_date and (
        r.get("SCENARIO_SET_ID") == set_id if set_id else r.get("SET_TYPE") in (None, "HISTORICAL"))]
    if len(found) != 1:
        raise PvfError(f"{path}: {len(found)} scenario set(s) for {business_date}{' named ' + set_id if set_id else ''} (one expected)")
    return found[0]


def set_for(a: argparse.Namespace) -> dict:
    """The scenario set this run joins to, from --scenario-set, --dates or the folder pvf_scenario_set.py wrote."""
    if a.scenario_set:
        return load_set(a.scenario_set, a.date, a.scenario_set_id)
    if a.dates:
        if not a.scenario_set_id:
            raise PvfError("--dates needs --scenario-set-id (the id of the date's set)")
        return {"SCENARIO_SET_ID": a.scenario_set_id, "SCENARIO_DATES": read_dates_file(a.dates, a.order), "SOURCE_BATCH": a.batch_id}
    own = (a.out or DEFAULT_OUT) / f"pvf-{a.date}" / "scenario-set"
    if not own.is_dir():
        raise PvfError(f"no scenario set: give --scenario-set PATH, or --dates FILE with --scenario-set-id, or run pvf_scenario_set.py first ({own})")
    return load_set(own, a.date, a.scenario_set_id)


# ---- building ------------------------------------------------------------------------------------------------------------------------
def fx_records(c: FxConfig) -> list[dict]:
    """One fx-scenario record per currency, checked against the contract and the date's rules; RATES in the set's day order."""
    check_days(c.table, c.business_date, c.days)
    contract, recs = fx_scenario(), []
    for ccy in sorted(c.table):
        byday = c.table[ccy]
        rec = {"FX_SCENARIO_ID": f"FXS-{ccy}-{c.scenario_set_id}", "BUSINESS_DATE": c.business_date, "SCENARIO_SET_ID": c.scenario_set_id,
               "CURRENCY": ccy, "QUOTE": f"USD per 1 {ccy}", "RATE": byday[c.business_date], "RATES": [byday[d] for d in c.days],
               "SOURCE": c.source, "BATCH_ID": c.batch_id}
        bad = contract.check(rec)
        if bad:
            raise PvfError(f"{ccy} breaks the fx-scenario contract: " + "; ".join(bad))
        recs.append(rec)
    return recs


def build_fx(c: FxConfig) -> dict:
    """Write the date's FX (layout, tar, .ready); the summary."""
    recs = fx_records(c)
    tar = write_kind(c.out, KIND, c.business_date, recs, c.lake)
    log.info("scenario FX of %s: %d currencies (%s), %d rates each, set %s, batch %s -> %s", c.business_date, len(recs),
             ", ".join(r["CURRENCY"] for r in recs), len(c.days), c.scenario_set_id, c.batch_id, tar)
    return {"BUSINESS_DATE": c.business_date, "CURRENCIES": [r["CURRENCY"] for r in recs], "RATES_PER_CURRENCY": len(c.days),
            "SCENARIO_SET_ID": c.scenario_set_id, "BATCH_ID": c.batch_id, "TAR": str(tar)}


def add_fx_options(p: argparse.ArgumentParser) -> None:
    """The options naming the scenario set the FX joins to."""
    s = p.add_argument_group("the scenario set the days come from")
    s.add_argument("--scenario-set", type=Path, metavar="PATH", help="a scenario-set .tar, .jsonl[.gz] or folder (pvf_scenario_set.py's output)")
    s.add_argument("--dates", type=Path, metavar="FILE", help="instead: a dates file, one YYYY-MM-DD a line (needs --scenario-set-id)")
    s.add_argument("--order", choices=("oldest-first", "newest-first"), default="oldest-first", help="how --dates is ordered (default oldest-first)")
    s.add_argument("--scenario-set-id", help="the set's id (with --dates; or to pick one of several sets of the date)")
    s.add_argument("--batch-id", help="the batch id (default: the set's SOURCE_BATCH: one batch per date)")


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--date", required=True, type=strict_date, help="the business date YYYY-MM-DD")
    p.add_argument("--remove", action="store_true", help="remove the date's FX from the lake instead of adding it")
    p.add_argument("--fx", type=Path, metavar="FILE", help="add: the FX CSV, long or wide form (see below)")
    p.add_argument("--out", type=Path, help=f"add: the folder pvf-<date>/ is written in (default {DEFAULT_OUT.name}/ under data/)")
    p.add_argument("--source", default=SOURCE, help=f"add: the SOURCE of the rates (default {SOURCE})")
    p.add_argument("--inbox", nargs="?", type=Path, const=DEFAULT_INBOX, metavar="DIR",
                   help="add: copy the archive and then its .ready into the data inbox the running server watches")
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    add_fx_options(p)
    add_remove_arguments(p)
    return p


def run(a: argparse.Namespace) -> dict:
    if a.remove:
        if not a.lake:
            raise PvfError("--remove needs --lake (the lake by name or folder)")
        log.warning("removing the scenario FX of %s removes EVERY currency of the date; trades priced in a currency other than USD are "
                    "rejected (DRS-5207) and other reporting currencies cannot be asked until it is added again", a.date)
        rc = lake_delete(KIND, a.date, a.lake, a.server, a.token_file, a.settings, a.dry_run, a.yes, a.no_signal)
        if rc:
            raise PvfError(f"lake delete exited {rc}")
        return {"REMOVED": a.date}
    if not a.fx:
        raise PvfError("--fx FILE is required to add (or --remove)")
    s = set_for(a)
    days = list(s["SCENARIO_DATES"])
    batch = a.batch_id or s.get("SOURCE_BATCH") or f"B-{a.date}-PVF"
    folder = (a.out or DEFAULT_OUT) / f"pvf-{a.date}"
    table = read_fx_file(a.fx, a.date, days)
    if a.dry_run:
        fx_records(FxConfig(a.date, table, s["SCENARIO_SET_ID"], days, batch, folder, a.lake, a.source))
        log.info("dry run: %d currencies are complete for %d days; would write %s", len(table), len(days), folder)
        return {"DRY_RUN": True, "CURRENCIES": sorted(table)}
    res = build_fx(FxConfig(a.date, table, s["SCENARIO_SET_ID"], days, batch, folder, a.lake, a.source))
    if a.inbox:
        res["INBOX"] = {"FOLDER": str(a.inbox), "DELIVERED": to_inbox([res["TAR"]], a.inbox)}
    return res


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        run(a)
    except (PvfError, OSError) as e:
        log.error("pvf fx refused: %s", e)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
