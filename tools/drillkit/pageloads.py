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

"""Page-load statistics of a test run (owner 2026-11-10): reads the lines the browser tests appended (drishti-console/tests/pageload_log.py,
{page, secs, test}), prints p95 and median per page, slowest p95 first, and with --write replaces the generated section of
docs/admin/BENCHMARK.md (18.2). Slow pages are reported, not failed: a page over the tests' 10 s budget fails in its own test.

    python3 tools/drillkit/pageloads.py <data>/drill/pageloads.jsonl [--top 25] [--write docs/admin/BENCHMARK.md]
"""
import argparse
import datetime
import json
import math
import re
import statistics
import sys
from pathlib import Path

BEGIN = "<!-- pageloads:begin (tools/drillkit/pageloads.py --write; the last drill's browser lane) -->"
END = "<!-- pageloads:end -->"
SLOW_S = 2.0          # reported as slow (not failed) from here


def p95(xs: list[float]) -> float:
    """The nearest-rank 95th percentile."""
    s = sorted(xs)
    return s[max(0, math.ceil(0.95 * len(s)) - 1)]


def stats(lines) -> list[dict]:
    by: dict[str, list[float]] = {}
    for line in lines:
        try:
            d = json.loads(line)
            by.setdefault(d["page"], []).append(float(d["secs"]))
        except (ValueError, KeyError, TypeError):
            continue                                      # a torn line of a killed worker
    rows = [{"page": p, "n": len(xs), "p95": p95(xs), "median": statistics.median(xs)} for p, xs in by.items()]
    return sorted(rows, key=lambda r: (-r["p95"], r["page"]))


def table(rows: list[dict], top: int | None = None) -> str:
    out = ["| Page | Loads | p95 | Median |", "|---|---:|---:|---:|"]
    for r in rows[:top] if top else rows:
        flag = " (slow)" if r["p95"] >= SLOW_S else ""
        out.append(f"| `{r['page']}`{flag} | {r['n']:,} | {r['p95']:.2f} s | {r['median']:.2f} s |")
    return "\n".join(out)


def write(doc: Path, rows: list[dict], top: int) -> None:
    text = doc.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        raise SystemExit(f"pageloads: {doc} has no {BEGIN} ... {END} section")
    loads = sum(r["n"] for r in rows)
    body = (f"{BEGIN}\nMeasured {datetime.date.today().isoformat()}: {loads:,} page loads of {len(rows)} pages; the {min(top, len(rows))} slowest by p95"
            f" (slow from {SLOW_S:.0f} s).\n\n{table(rows, top)}\n{END}")
    doc.write_text(re.sub(re.escape(BEGIN) + r".*?" + re.escape(END), lambda _: body, text, flags=re.S), encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("log")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--write", type=Path, help="the benchmark document whose generated section is replaced")
    a = ap.parse_args(argv)
    path = Path(a.log)
    rows = stats(path.read_text(encoding="utf-8").splitlines()) if path.is_file() else []
    if not rows:
        print(f"pageloads: no page loads recorded in {path}")
        return 0
    slow = [r for r in rows if r["p95"] >= SLOW_S]
    print(f"pageloads: {sum(r['n'] for r in rows):,} loads of {len(rows)} pages; {len(slow)} slow (p95 >= {SLOW_S:.0f} s); slowest first:")
    print(table(rows, a.top))
    if a.write:
        write(a.write, rows, a.top)
        print(f"pageloads: written to {a.write}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
