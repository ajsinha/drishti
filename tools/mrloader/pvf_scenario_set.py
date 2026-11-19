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

"""The scenario set of a business date: ADD it, or REMOVE it. The set lists the historical days behind every scenario: index i of every
scenario-PV vector, P&L vector and FX scenario of the date is SCENARIO_DATES[i], oldest first. A date has one normal set, used only for
its own date; the VaR engine refuses a vector of another length than the set, so the set goes in before the date's FX and PVs.

examples (from the repository root):
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates data/pvf/scenario_dates_20261118.txt            add (oldest first in the file)
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates FILE --order newest-first                        the file lists newest first
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --weekdays 251                                           the 251 weekdays before the date
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates FILE --inbox --lake risklake                     ... and hand it to the data inbox
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --dates FILE --dry-run                                   check only; write nothing
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --remove --lake risklake --dry-run                       what removing would delete
  python3 tools/mrloader/pvf_scenario_set.py --date 2026-11-18 --remove --lake risklake --yes --no-signal

the days: --dates FILE (one YYYY-MM-DD a line, blank lines ignored; --order oldest-first (default) or newest-first says how the file is
ordered and it is refused if it is not) or --weekdays N (the N weekdays before the date, no holiday calendar: say so in --description if
that is not your calendar). Checked before anything is written: no repeated day, every day before the business date (a stress set may
use any past days), SCENARIO_COUNT = the number of days, WINDOW_START/WINDOW_END the first and last, every field of the scenario-set
contract. The PV vectors must be as long as the set: tools/mrloader/kinds/scenario-pv.pvf.yaml scenario_set.count (251) is the PV export's count and a
different length is warned about here.

writes under <out>/mr-<date>/ (default out: data/pvf): scenario-set.<date>.jsonl, scenario-set.<date>.jsonl.gz and its .ready (--lake
names the lake in it), and mr-<date>.sha256; --inbox copies the file, then its .ready, to the data inbox the running server watches;
--load --lake NAME loads it now with `drishti.py lake build --engine java`.
The set id and batch default to tools/mrloader/kinds/scenario-pv.pvf.yaml's run values (HS-PVF-251, B-<date>-PVF), so the PV export of the day names this
set; --scenario-set-id / --batch-id override. --set-type stress writes a stress set (SET_TYPE STRESS, id HS-STRESS-...) as
scenario-set-stress.<date>.jsonl.gz with mode append (its marker routes it to the scenario-set kind), so it sits beside the date's
normal set; load the normal set first.
--remove deletes the date from the lake with `tools/drishti.py lake delete --kind scenario-set` (whole dates: the normal and the stress
set of the date both go) and warns that the date's scenario-pv and fx-scenario rows are unusable without a set.
Exit 0 done, 1 refused (one line saying why), 2 usage.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader import hooks  # noqa: E402
from mrloader.common import (DEFAULT_MAPPING, DEFAULT_OUT, PACK, PvfError, add_remove_arguments, lake_delete, log,  # noqa: E402
                             read_dates_file, setup_logging, strict_date)
from mrloader.package import day_folder, write_records, write_sha256  # noqa: E402
from mrloader.contract import DateCheck, scenario_set  # noqa: E402
from mrloader.mapping import Mapping, weekdays_before  # noqa: E402

KIND = "scenario-set"
STRESS_PREFIX = "HS-STRESS-"


def build_record(date: str, days: list[str], set_id: str, batch: str, set_type: str = "normal", horizon: int = 1,
                 revaluation: str = "full", description: str | None = None) -> dict:
    """The date's scenario-set record (UPPER_SNAKE, exactly the contract's fields), checked: the contract, then the date's rules
    (distinct, oldest first, count and window, before the business date for a normal set). Refused with every problem found."""
    stress = set_type == "stress"
    words = description or (f"Stress scenario set: {len(days)} days, {days[0]} to {days[-1]}" if stress else
                            f"Historical simulation: {len(days)} scenario days, {days[0]} to {days[-1]}, equal weights")
    rec = {"SCENARIO_SET_ID": set_id, "BUSINESS_DATE": date, "SCENARIO_DATES": days, "SCENARIO_COUNT": len(days), "WINDOW_START": days[0],
           "WINDOW_END": days[-1], "HORIZON_DAYS": horizon, "REVALUATION": revaluation, "WEIGHTS": None, "SOURCE_BATCH": batch,
           "DESCRIPTION": words, "SET_TYPE": "STRESS" if stress else "HISTORICAL"}
    problems = scenario_set().check(rec)
    if stress and not set_id.startswith(STRESS_PREFIX):
        problems.append(f"a stress set's id starts with {STRESS_PREFIX} (drishti.var.stress.id-prefix): {set_id}")
    if not stress and set_id.startswith(STRESS_PREFIX):
        problems.append(f"{set_id} is a stress id: use --set-type stress")
    check = DateCheck()
    check.add_set(rec)
    problems += [p for p in check.problems() if not (stress and "normal scenario sets" in p)]   # a stress set sits beside the normal one
    if problems:
        raise PvfError("the scenario set is refused: " + "; ".join(problems))
    return rec


def days_for(a: argparse.Namespace) -> list[str]:
    """The set's days, oldest first, from --dates (and --order) or --weekdays."""
    if bool(a.dates) == bool(a.weekdays):
        raise PvfError("give the days one way: --dates FILE or --weekdays N")
    if a.dates:
        return read_dates_file(a.dates, a.order)
    if a.weekdays < 1:
        raise PvfError("--weekdays N: N is 1 or more")
    return weekdays_before(a.date, a.weekdays)


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--date", required=True, type=strict_date, help="the business date YYYY-MM-DD")
    p.add_argument("--remove", action="store_true", help="remove the date's set from the lake instead of adding it")
    d = p.add_argument_group("add: the days")
    d.add_argument("--dates", type=Path, metavar="FILE", help="the days, one YYYY-MM-DD a line")
    d.add_argument("--order", choices=("oldest-first", "newest-first"), default="oldest-first", help="how --dates is ordered (default oldest-first)")
    d.add_argument("--weekdays", type=int, metavar="N", help="instead: the N weekdays before the date (no holiday calendar)")
    r = p.add_argument_group("add: the record")
    r.add_argument("--set-type", choices=("normal", "stress"), default="normal", help="normal (default) or stress (stressed VaR)")
    r.add_argument("--scenario-set-id", help="the set's id, ^HS- (default: kinds/scenario-pv.pvf.yaml run.scenario_set_id; stress: HS-STRESS-<count>)")
    r.add_argument("--batch-id", help="SOURCE_BATCH, the date's batch (default: kinds/scenario-pv.pvf.yaml run.batch_id)")
    r.add_argument("--horizon-days", type=int, default=1, help="the holding period in business days (default 1)")
    r.add_argument("--revaluation", default="full", help="full (default) or sensitivities, as the batch did it")
    r.add_argument("--description", help="the set in words (default: says how many days and from when to when)")
    r.add_argument("--mapping", type=Path, default=DEFAULT_MAPPING, help="the PV mapping the id, batch and expected count come from")
    o = p.add_argument_group("add: output")
    o.add_argument("--out", type=Path, help="the folder mr-<date>/ is written in (default data/pvf)")
    hooks.add_arguments(o, shared=False)
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    add_remove_arguments(p)
    return p


def run(a: argparse.Namespace) -> dict:
    if a.remove:
        if not a.lake:
            raise PvfError("--remove needs --lake (the lake by name or folder)")
        log.warning("removing the scenario set of %s makes the date's scenario-pv and fx-scenario rows unusable (the engine sizes a date's "
                    "vectors from its set): VaR on the date answers 'not loaded' until a set is added again", a.date)
        rc = lake_delete(KIND, a.date, a.lake, a.server, a.token_file, a.settings, a.dry_run, a.yes, a.no_signal)
        if rc:
            raise PvfError(f"lake delete exited {rc}")
        return {"REMOVED": a.date}
    days = days_for(a)
    ctx = Mapping(a.mapping, {"scenario_set_id": a.scenario_set_id, "batch_id": a.batch_id, "computed_at": "-"}).resolve_run(a.date, {})
    stress = a.set_type == "stress"
    set_id = a.scenario_set_id or (f"{STRESS_PREFIX}{len(days)}" if stress else ctx["scenario_set_id"])
    rec = build_record(a.date, days, set_id, a.batch_id or ctx["batch_id"], a.set_type, a.horizon_days, a.revaluation, a.description)
    expected = Mapping(a.mapping).set.get("count")
    if not stress and expected and expected != len(days):
        log.warning("the set has %d days but %s expects %s-long PV vectors: the date's PVs are refused unless their length is %d",
                    len(days), Path(a.mapping).name, expected, len(days))
    log.info("scenario set %s of %s: %d days, %s to %s%s", set_id, a.date, len(days), days[0], days[-1], " (dry run: nothing written)" if a.dry_run else "")
    if a.dry_run:
        return {"DRY_RUN": True, "RECORD": rec}
    folder = day_folder(a.out or DEFAULT_OUT, a.date)
    f = write_records(folder, KIND, a.date, PACK, [rec], a.lake, "append" if stress else "replace", name=f"{KIND}-stress" if stress else None)
    write_sha256(folder, a.date)
    log.info("wrote %s (+ .ready)", f)
    res = {"RECORD": rec, "FILE": str(f)}
    res["INBOX" if a.inbox else "LOAD"] = hooks.deliver(a, [f])
    return res


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        res = run(a)
    except (PvfError, OSError) as e:
        log.error("pvf scenario set refused: %s", e)
        return 1
    return (res.get("LOAD") or {}).get("EXIT") or 0


if __name__ == "__main__":
    sys.exit(main())
