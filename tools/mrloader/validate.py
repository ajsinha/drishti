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

"""Checks the records of any kind's files against the kind's data contract: required fields, types, enums, patterns, nested records,
derived fields (never loaded), UPPER_SNAKE names, the key unique per business date; the required fields a shipped mapping of the kind
leaves out on purpose (omit-required) are known gaps, errors only with --strict. The kind is read from each file's .ready marker,
else from its name (<kind>.<date>.jsonl[.gz]), else --kind. The VaR engine's own one-date rules of the scenario kinds are pvf_validate.py's.

examples (from the repository root):
  python3 tools/mrloader/validate.py data/mr/mr-2026-10-29/var-limit.2026-10-29.jsonl.gz
  python3 tools/mrloader/validate.py data/mr/mr-2026-10-29                      every loadable file of the day's folder
  python3 tools/mrloader/validate.py --strict data/mr/mr-2026-10-29             attributes beside the contract's fields are errors too

Exit 0 every record holds, 1 a problem (each listed, the first --show of each kind).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

from mrloader.common import PvfError, log, read_any, setup_logging  # noqa: E402
from mrloader.contract import Contract  # noqa: E402
from mrloader.package import loadable_files, read_marker  # noqa: E402

_NAME = re.compile(r"^([A-Za-z0-9_-]+)\.(\d{4}-\d{2}-\d{2})\.jsonl(\.gz)?$")


def kind_of(path: Path, given: str | None) -> str:
    """The kind of a file: its marker's, its name's, or the one given."""
    ready = Path(f"{path}.ready")
    if ready.is_file():
        m = read_marker(ready)
        k = m.get("kind") or next(iter(m.get("folders") or {}), None)
        if k:
            return str(k)
    n = _NAME.match(Path(path).name)
    if n:
        return n.group(1)
    if given:
        return given
    raise PvfError(f"{Path(path).name}: no marker and no <kind>.<date> name: give --kind")


def known_gaps(kind: str) -> frozenset:
    """The required fields the shipped mappings of ``kind`` leave out on purpose (omit-required, with their reasons): known gaps,
    reported as such, not errors (errors only with --strict)."""
    import yaml
    from mrloader.kindmap import KINDS_DIR
    out: set = set()
    for f in [KINDS_DIR / f"{kind}.yaml", *sorted(KINDS_DIR.glob(f"{kind}.*.yaml"))]:
        if f.is_file():
            doc = yaml.safe_load(f.read_text(encoding="utf-8")) or {}
            out |= set((doc.get("omit-required") or doc.get("omit_required") or {}))
    return frozenset(out)


def validate(paths: list[Path], kind: str | None = None, strict: bool = False, show: int = 5) -> dict:
    """Every record of ``paths`` checked against its kind's contract; counts, problems by field with examples, and OK."""
    files: list[Path] = []
    for p in paths:
        files += loadable_files(p) if Path(p).is_dir() else [Path(p)]
    counts, problems, examples = Counter(), Counter(), []
    for f in files:
        k = kind_of(f, kind)
        c = Contract.for_kind(k)
        seen: Counter = Counter()
        for where, r in read_any(f):
            counts[k] += 1
            bad = c.check(r, extra_ok=not strict, omitted=frozenset() if strict else known_gaps(k))
            if c.key:
                seen[(r.get("BUSINESS_DATE"), r.get(c.key))] += 1
                if seen[(r.get("BUSINESS_DATE"), r.get(c.key))] == 2:
                    bad.append(f"{c.key}: {r.get(c.key)!r} more than once")
            for b in bad:
                what = f"{k}.{b.split(':')[0]}"
                problems[what] += 1
                if problems[what] <= show:
                    examples.append(f"{f.name} {r.get(c.key)}: {b}")
    return {"RECORDS": dict(counts), "PROBLEMS": dict(problems), "EXAMPLES": examples, "OK": not problems and sum(counts.values()) > 0}


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("paths", nargs="+", type=Path, help="files (.jsonl, .jsonl.gz, .tar) or a day's folder")
    p.add_argument("--kind", help="the kind, for a file with neither a marker nor a <kind>.<date> name")
    p.add_argument("--strict", action="store_true", help="the contract to the letter: attributes beside the contract's fields are errors")
    p.add_argument("--show", type=int, default=5, help="examples listed per kind of problem (default 5)")
    p.add_argument("--json", action="store_true", help="the result as JSON")
    a = p.parse_args(argv)
    setup_logging()
    try:
        res = validate(a.paths, a.kind, a.strict, a.show)
    except (PvfError, OSError) as e:
        log.error("validate refused: %s", e)
        return 1
    if a.json:
        print(json.dumps(res, indent=2))
    else:
        print(f"records: {res['RECORDS']}")
        for e in res["EXAMPLES"]:
            print(f"  {e}")
        print("OK: every record holds to its contract" if res["OK"] else f"FAILED: {res['PROBLEMS']}")
    return 0 if res["OK"] else 1


if __name__ == "__main__":
    sys.exit(main())
