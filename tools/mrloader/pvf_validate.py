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

"""Checks every record of an export against its data contract, read from the market-risk pack (scenario-pv and scenario-set): required
fields, types, enums and patterns, UPPER_SNAKE attribute names, the engine's per-record rules (grain and ids,
direction and quantity, valuation dates) and each business date as a whole (unique ids, one scenario set, one batch, every vector as long
as the set's SCENARIO_DATES, the set's days in order and before the date).

examples (from the repository root):
    python3 tools/mrloader/pvf_validate.py data/pvf/mr-2026-10-29/scenario-pv.2026-10-29.jsonl.gz data/pvf/mr-2026-10-29/scenario-set.2026-10-29.jsonl.gz
    python3 tools/mrloader/pvf_validate.py data/pvf/mr-2026-10-29/scenario-pv.2026-10-29.jsonl data/pvf/mr-2026-10-29/scenario-set.2026-10-29.jsonl
    python3 tools/mrloader/pvf_validate.py --strict data/pvf/mr-2026-10-29/scenario-pv.2026-10-29.jsonl.gz data/pvf/mr-2026-10-29/scenario-set.2026-10-29.jsonl.gz   to the letter

A path is a .jsonl or .jsonl.gz file, a folder of them (recursively) or a .tar. A record is a scenario-set record when it has
SCENARIO_DATES, else a scenario-pv record. The source attributes kept beside the contract's fields (the mapping's pass-through) and the
required fields the mapping's omit-required leaves out are counted and listed as known gaps; --strict makes both errors (the contract to
the letter). Exit 0 every record holds, 1 a problem (each listed, the first --show of each kind).
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import DEFAULT_MAPPING, PvfError, log, read_any, setup_logging  # noqa: E402
from mrloader.contract import DateCheck, record_rules, scenario_pv, scenario_set  # noqa: E402


def known_gaps(mapping: Path | None) -> frozenset:
    """The required fields kinds/scenario-pv.pvf.yaml leaves out on purpose (omit-required); none without a mapping."""
    if mapping is None:
        return frozenset()
    from mrloader.mapping import Mapping
    return frozenset(Mapping(mapping).omit)


def validate(paths: list[Path], strict: bool = False, omitted: frozenset = frozenset(), show: int = 5) -> dict:
    """Every record of ``paths`` checked; the counts, the problems by kind with examples, the date problems, the extra attributes and
    the known gaps seen, and OK."""
    pv, sset, check = scenario_pv(), scenario_set(), DateCheck()
    counts: Counter = Counter()
    extras: Counter = Counter()
    gaps: Counter = Counter()
    problems: Counter = Counter()
    examples: list[str] = []
    for path in paths:
        for where, r in read_any(path):
            is_set = "SCENARIO_DATES" in r
            counts["scenario-set" if is_set else "scenario-pv"] += 1
            bad = sset.check(r) if is_set else pv.check(r, extra_ok=not strict, omitted=frozenset() if strict else omitted) + record_rules(r)
            if not is_set:
                extras.update(pv.extras(r))
                gaps.update(g for g in pv.gaps(r) if g in omitted)
            (check.add_set if is_set else check.add_pv)(r)
            for b in bad:
                kind = b.split(":")[0]
                problems[kind] += 1
                if problems[kind] <= show:
                    examples.append(f"{where} {r.get('SCENARIO_SET_ID') if is_set else r.get('SCENARIO_PV_ID')}: {b}")
    date_problems = check.problems()
    return {"RECORDS": dict(counts), "PROBLEMS": dict(problems), "EXAMPLES": examples, "DATE_PROBLEMS": date_problems,
            "EXTRA_ATTRIBUTES": dict(sorted(extras.items())), "KNOWN_GAPS": dict(sorted(gaps.items())),
            "OK": not problems and not date_problems and sum(counts.values()) > 0}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="+", type=Path, help="files, folders or .tar archives of the export")
    p.add_argument("--mapping", type=Path, default=DEFAULT_MAPPING, help="the mapping whose omit-required are known gaps (default tools/mrloader/kinds/scenario-pv.pvf.yaml)")
    p.add_argument("--strict", action="store_true", help="the contract to the letter: extra attributes and omitted required fields are errors")
    p.add_argument("--show", type=int, default=5, help="examples listed per kind of problem (default 5)")
    p.add_argument("--json", action="store_true", help="the result as JSON")
    a = p.parse_args(argv)
    setup_logging()
    try:
        res = validate(a.paths, a.strict, known_gaps(a.mapping), a.show)
    except (PvfError, FileNotFoundError) as e:
        log.error("pvf validate refused: %s", e)
        return 1
    if a.json:
        print(json.dumps(res, indent=2))
    else:
        print(f"records: {res['RECORDS']}")
        if res["EXTRA_ATTRIBUTES"]:
            print(f"  source attributes beside the contract's fields: {len(res['EXTRA_ATTRIBUTES'])} ({', '.join(res['EXTRA_ATTRIBUTES'])})")
        if res["KNOWN_GAPS"]:
            print(f"  required fields left out on purpose (the mapping's omit-required): {res['KNOWN_GAPS']}")
        for e in res["EXAMPLES"]:
            print(f"  {e}")
        for d in res["DATE_PROBLEMS"]:
            print(f"  date: {d}")
        print(("OK: every record holds to its contract" + (" (with the known gaps above)" if res["KNOWN_GAPS"] else "")) if res["OK"] else f"FAILED: {res['PROBLEMS']} {len(res['DATE_PROBLEMS'])} date problem(s)")
    return 0 if res["OK"] else 1


if __name__ == "__main__":
    sys.exit(main())
