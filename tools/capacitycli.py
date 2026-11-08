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


"""`drishti.py admin capacity`: Admin -> Capacity from the command line (docs/guides/CLUSTER_GUIDE.md 9.7; the API:
/api/v1/admin/capacity, administrators). The planner the server serves (capacity/planner.yaml) is evaluated here, by the same evaluator
as tools/sizing_planner.py's workbook and the page's drishti_capacity.js: the heap to set, RAM, disk and cores, the warnings, the YAML block of
the settings and the whole JVM command line. --live puts this server's measured figures into the inputs (positions, trades, dates in
memory, users, requests a second, Java) and prints them; --set Name=value changes any input; --xlsx FILE downloads the formula workbook
(--inputs plan, live or both), audited on the server as capacity-workbook-downloaded. Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import sys
import urllib.parse
from pathlib import Path

EPILOG = ("examples:\n  drishti.py admin capacity\n  drishti.py admin capacity --set Trades=450000 --set Positions=450000 --set HotDates=12\n"
          "  drishti.py admin capacity --live\n  drishti.py admin capacity --all --json > plan.json\n"
          "  drishti.py admin capacity --xlsx capacity.xlsx --inputs both --set Users=80")

ANSWERS = (("XmxGB", "-Xmx", "GB"), ("GcChoice", "collector", ""), ("SetOpenHeapMB", "drishti.var.open-heap-mb", "MB"),
           ("HeapSteady", "heap after GC, all dates open", "MB"), ("RssPlannedMB", "resident memory planned", "MB"),
           ("RamRecommendedGB", "RAM recommended", "GB"), ("RamMinimumGB", "RAM, smallest workable", "GB"), ("DiskRecommendedGB", "disk recommended", "GB"),
           ("DiskLocalRecommendedGB", "local disk, lakes elsewhere", "GB"), ("CoresRecommended", "cores", ""), ("FirstOpenSec", "first open of a date", "s"))


def _planner():
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import sizing_planner                                                    # the evaluator the workbook is checked with
    return sizing_planner


def fmt(v) -> str:
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (int, float)):
        return f"{v:,.0f}" if abs(v) >= 100 or float(v).is_integer() else f"{v:,.2f}".rstrip("0").rstrip(".")
    return str(v)


def parse_sets(plan: dict, sets: list[str]) -> dict:
    """``Name=value`` pairs typed by each input's kind; an unknown name or a bad number is a usage error with the valid names."""
    kinds = {i["name"]: i["kind"] for i in plan["inputs"]}
    out = {}
    for s in sets or []:
        if "=" not in s:
            raise ValueError(f"--set {s}: give Name=value")
        k, v = s.split("=", 1)
        k = k.strip()
        if k not in kinds:
            raise ValueError(f"--set {k}: no such input; the inputs are {', '.join(sorted(kinds))}")
        if kinds[k] == "text":
            out[k] = v
        else:
            try:
                out[k] = float(v.replace(",", ""))
            except ValueError:
                raise ValueError(f"--set {k}={v}: a number") from None
    return out


def evaluate(plan: dict, overrides: dict):
    sp = _planner()
    return sp.Evaluator(sp.plan_cells(plan, overrides))


def report(plan: dict, ev, say, every: bool = False, live: dict | None = None) -> None:
    """The answers, the warnings that are not OK, the YAML block and the command line (and with ``every`` each result line)."""
    if live:
        s, v, d, ld = live.get("server") or {}, live.get("var") or {}, live.get("disk") or {}, live.get("load") or {}
        say(f"LIVE  measured {live.get('measuredAt')}")
        say(f"  heap {fmt(s.get('heapAfterGcMb'))} MB after GC of {fmt(s.get('heapMaxMb'))} MB max · RSS {fmt(s.get('rssMb'))} MB · "
            f"RAM {fmt(s.get('ramMb'))} MB · {s.get('cpus')} hardware threads · Java {s.get('java')}")
        if v:
            say(f"  VaR: {fmt(v.get('positions'))} positions on {v.get('newestDate')}, {v.get('openDates')} of {v.get('hotDates')} dates open, "
                f"{fmt(v.get('openHeapMb'))} MB of {fmt(v.get('openHeapLimitMb'))} MB safety net")
        if d:
            say(f"  disk: {fmt((d.get('freeBytes') or 0) / 1073741824)} GB free of {fmt((d.get('totalBytes') or 0) / 1073741824)} GB, "
                f"guard {fmt((d.get('guardBytes') or 0) / 1073741824)} GB ({d.get('path')})")
        if ld:
            say(f"  load: {ld.get('usersActive')} people now, {ld.get('usersPeakToday')} today's most; {ld.get('requestsPerSecond')} req/s now, "
                f"{ld.get('requestsPerSecondPeakToday')} at today's busiest minute")
        say("  inputs from it: " + ", ".join(f"{k}={fmt(x)}" for k, x in (live.get("inputs") or {}).items() if x is not None))
        say("")
    say("PLAN  the single-node planner (capacity/planner.yaml)")
    width = max(len(label) for _n, label, _u in ANSWERS)
    for name, label, unit in ANSWERS:
        say(f"  {label.ljust(width)}  {fmt(ev.value(name))}{(' ' + unit) if unit else ''}")
    if every:
        for band in plan["results"]:
            say("")
            say(band["band"])
            for r in band["rows"]:
                say(f"  {r['label'][:58].ljust(58)}  {fmt(ev.value(r['name'])):>16} {'' if r['unit'] == 'text' else r['unit']}")
    warned = [(w["label"], ev.value(w["name"])) for w in plan["warnings"] if ev.value(w["name"]) != "OK"]
    say("")
    say("WARNINGS" + ("  none" if not warned else ""))
    for label, text in warned:
        say(f"  {label}: {text}")
    say("")
    say(f"YAML  for {plan['settingsFile']}")
    for line in ev.value("YamlBlock").split("\n"):
        say("  " + line)
    say("")
    say("COMMAND LINE")
    say("  " + ev.value("JvmCommand"))


def register(groups, srv, ctx: dict):
    """Adds ``admin capacity``; answers the ``admin`` group's commands (``admin engines`` joins them)."""
    import argparse

    Api, CliError, say_json = ctx["Api"], ctx["CliError"], ctx["say_json"]

    def cmd(a, extra) -> int:
        api = Api.from_args(a)
        plan = api.json("GET", "/api/v1/admin/capacity")
        try:
            sets = parse_sets(plan, a.set)
        except ValueError as e:
            raise CliError(str(e), 2) from None
        if a.xlsx:
            q = {"inputs": a.inputs, **{k: (int(v) if isinstance(v, float) and v.is_integer() else v) for k, v in sets.items()}}
            _, raw = api.request("GET", "/api/v1/admin/capacity.xlsx?" + urllib.parse.urlencode(q), extra={"Accept": "*/*"})
            Path(a.xlsx).write_bytes(raw)
            print(f"wrote {a.xlsx} ({len(raw):,} bytes; inputs {a.inputs}; every result a live formula; audited as capacity-workbook-downloaded)")
            return 0
        live = api.json("GET", "/api/v1/admin/capacity/live") if a.live else None
        overrides = {}
        if live:
            kinds = {i["name"]: i["kind"] for i in plan["inputs"]}
            overrides.update({k: (v if kinds.get(k) == "text" else float(v)) for k, v in (live.get("inputs") or {}).items() if v is not None and k in kinds})
        overrides.update(sets)
        ev = evaluate(plan, overrides)
        if a.json:
            names = [r["name"] for b in plan["results"] for r in b["rows"]] + [s["name"] for s in plan["settings"]] + [j["name"] for j in plan["jvm"]] \
                + [w["name"] for w in plan["warnings"]] + ["YamlBlock", "JvmCommand"]
            say_json({"inputs": overrides, "live": live, "results": {n: ev.value(n) for n in names}})
            return 0
        report(plan, ev, print, a.all, live)
        return 0

    g = groups.add_parser("admin", help="Administration over REST (administrators): the capacity planner, the compute engines",
                          description="Administration over REST (administrators): the capacity planner, the compute engines")
    sub = g.add_subparsers(dest="cmd", metavar="COMMAND", required=True)
    p = sub.add_parser("capacity", parents=[srv], help="admin capacity: the heap, RAM, disk and cores for this server's plan, the settings and the command line",
                       description=__doc__.split("\n\n")[1] if "\n\n" in __doc__ else __doc__, epilog=EPILOG, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.set_defaults(func=cmd)
    p.add_argument("--live", action="store_true", help="put this server's measured figures into the inputs (and print them)")
    p.add_argument("--set", action="append", metavar="Name=value", help="an input by its name (repeat): Trades=450000, HotDates=12, Collector=ZGC")
    p.add_argument("--all", action="store_true", help="every result line, band by band")
    p.add_argument("--xlsx", metavar="FILE", help="download the formula workbook to FILE instead")
    p.add_argument("--inputs", choices=("plan", "live", "both"), default="plan", help="with --xlsx: the plan's inputs, the live ones, or both side by side")
    return sub
