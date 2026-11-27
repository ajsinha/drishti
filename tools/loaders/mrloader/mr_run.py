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

"""The daily driver: ONE day of the CSV loaders' inputs, every kind of the day's folder converted in dependency order, the kinds checked
against each other, then (optionally) handed to the data inbox or loaded, in that order. Nothing is delivered until every kind has been
converted and checked: a day that fails leaves the lake as it was (docs/guides/BANK_DATA_GUIDE.md section 12; the runbook:
docs/guides/BANK_DATA_GUIDE.md).

examples (from the repository root):
  python3 tools/loaders/mrloader/mr_run.py --date 2026-11-18 --from tools/loaders/mrloader/samples --day tools/loaders/mrloader/days/sample.yaml --dry-run
  python3 tools/loaders/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day /path/to/site/day.yaml --inbox
  python3 tools/loaders/mrloader/mr_run.py --date 2026-10-29 --from data/mr/2026-10-29 --day FILE --load --lake risklake
  python3 tools/loaders/mrloader/mr_run.py --date 2026-10-29 --from DIR --day FILE --delete-plan   the lake delete lines to remove the day (runs nothing)

the day file (YAML) says which file of the day's folder feeds which kind; {YYYYMMDD} and {YYYY-MM-DD} are the run's --date:
  format: 1
  lake: risklake                        optional: written into every .ready and used by --load
  kinds:
    legal-entity: {csv: "legal_entity.csv"}
    book:         {csv: "portfolio_d2.csv", source: pvf}
    scenario-pv:  {run: pvf, pv: "pvf_path_{YYYYMMDD}.csv", positions: "pos_{YYYYMMDD}.csv", portfolio: "portfolio_d2.csv", fx: "fx_{YYYYMMDD}.csv"}
    var-limit:    {csv: "var_limits_{YYYYMMDD}.csv"}
    risk-factor:  {csv: "risk_factors.csv", optional: true}
    position-sensitivity: {csv: "sens_positions_{YYYYMMDD}.csv", child: {SENSITIVITIES: "sens_{YYYYMMDD}.csv"}, source-system: MUREX}
  required: [scenario-pv]               the day is refused without these; another missing kind is reported and skipped
A kind's entry may also name `mapping:` (a site's own mapping file, by explicit path). A site's day file lives with its settings (an
explicit path); tools/loaders/mrloader/days/sample.yaml is the sample day's.

writes, under <out>/mr-<date>/ (--out defaults to the --from folder): every kind's files (csv_load.py's and pvf_run.py's),
links-<date>.csv (every unresolved link value, and every link not checked), mr-<date>.summary.json and mr-<date>.sha256. Prints one table:
kind, rows read, written, rejected, link warnings, seconds.
Exit 0 the day is ready (or delivered), 1 refused (a required kind missing, a kind refused, a link under policy refuse), 2 usage; with
--load, lake build's exit code when it is not zero.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[2])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

import yaml  # noqa: E402

from loaders.mrloader import csv_load, hooks, links  # noqa: E402
from loaders.mrloader.common import ROOT, PvfError, Timer, log, read_jsonl, setup_logging, strict_date  # noqa: E402
from loaders.mrloader.kindmap import KindMapping, mapping_path  # noqa: E402
from loaders.mrloader.package import day_folder, loadable_files, write_sha256  # noqa: E402

ENTRY_KEYS = {"csv", "source", "child", "optional", "mapping", "run", "pv", "positions", "portfolio", "fx", "source-system", "batch-id"}


def read_day(path: Path) -> dict:
    """The day file, checked: format 1, known keys per kind."""
    try:
        doc = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as e:
        raise PvfError(f"cannot read the day file {path}: {e}") from None
    if doc.get("format") != 1 or not isinstance(doc.get("kinds"), dict):
        raise PvfError(f"{path}: format: 1 and kinds: are required")
    for k, e in doc["kinds"].items():
        bad = sorted(set(e or {}) - ENTRY_KEYS)
        if bad:
            raise PvfError(f"{path}: kinds.{k}: unknown key(s) {bad} (known: {sorted(ENTRY_KEYS)})")
    return doc


def fill(name: str, date: str) -> str:
    return str(name).replace("{YYYYMMDD}", date.replace("-", "")).replace("{YYYY-MM-DD}", date)


def order(kinds: list[str], after: dict[str, list[str]]) -> list[str]:
    """The kinds in delivery order: the topological order of their mappings' ``after:`` lists (a kind not in the run is ignored), ties
    broken by the fixed order of the kinds (masters, then the scenario kinds, then the large kinds)."""
    rank = {k: i for i, k in enumerate(hooks.DEFAULT_ORDER)}
    left = set(kinds)
    out: list[str] = []
    while left:
        ready = [k for k in left if not (set(after.get(k, [])) & left)]
        if not ready:
            raise PvfError(f"the kinds' after: lists form a cycle among {sorted(left)}")
        k = min(ready, key=lambda x: (rank.get(x, len(rank)), x))
        out.append(k)
        left.remove(k)
    return out


def after_of(kind: str, entry: dict) -> list[str]:
    if entry.get("run") == "pvf":
        return ["scenario-set", "fx-scenario"]
    try:
        return KindMapping(Path(entry["mapping"]) if entry.get("mapping") else mapping_path(kind, entry.get("source"))).after
    except PvfError:
        return []


def run_pvf(a, entry: dict, src: Path, folder_out: Path) -> dict:
    """The scenario PVs of the day through pvf_run.py (the PVF join: its own step in front of the same files)."""
    cmd = [sys.executable, str(ROOT / "tools" / "loaders" / "mrloader" / "pvf_run.py"), "--pv", str(src / fill(entry["pv"], a.date)),
           "--positions", str(src / fill(entry["positions"], a.date)), "--portfolio", str(src / fill(entry["portfolio"], a.date)),
           "--date", a.date, "--out", str(folder_out)]
    if entry.get("fx"):
        cmd += ["--fx", str(src / fill(entry["fx"], a.date))]
    if entry.get("mapping"):
        cmd += ["--mapping", str(entry["mapping"])]
    if entry.get("batch-id"):
        cmd += ["--batch-id", str(entry["batch-id"])]
    if a.lake:
        cmd += ["--lake", a.lake]
    if a.dry_run:
        cmd.append("--dry-run")
    log.info("running: %s", " ".join(cmd[1:]))
    rc = subprocess.run(cmd, check=False).returncode
    if rc:
        raise PvfError(f"pvf_run.py exited {rc}")
    s = {} if a.dry_run else json.loads((day_folder(folder_out, a.date) / f"scenario-pv.{a.date}.summary.json").read_text())
    return {"KIND": "scenario-pv", "STATUS": "DONE", "ROWS_READ": s.get("POSITIONS_READ", 0), "RECORDS_WRITTEN": s.get("RECORDS_WRITTEN", 0),
            "ROWS_REJECTED": (s.get("JOIN_EXCLUDED") or 0) + (s.get("EXPORT_AUDITED") or 0), "SECONDS": s.get("SECONDS"),
            "FILES": s.get("FILES") or []}


def run(a: argparse.Namespace) -> dict:
    t = Timer()
    day = read_day(a.day)
    a.lake = a.lake or day.get("lake")
    entries = {k: dict(v or {}) for k, v in day["kinds"].items()}
    if a.delete_plan:
        kinds = list(entries)
        pvf = entries.get("scenario-pv", {})
        if pvf.get("run") == "pvf":                               # the PV run writes the set (and the FX) too
            kinds += ["scenario-set"] + (["fx-scenario"] if pvf.get("fx") else [])
        lines = [f"python3 tools/drishti.py lake delete --lake {a.lake or '<lake>'} --kind {k} --date {a.date} --dry-run"
                 for k in reversed(order(kinds, {k: after_of(k, entries.get(k, {})) for k in kinds}))]
        print("\n".join(lines))
        return {"DELETE_PLAN": lines}
    if a.load and not a.lake:
        raise PvfError("--load needs --lake NAME (or lake: in the day file)")
    src = Path(a.source)
    out = a.out or src
    folder = day_folder(out, a.date)
    present, missing = [], []
    for k, e in entries.items():
        f = src / fill(e.get("pv") if e.get("run") == "pvf" else e.get("csv", ""), a.date)
        (present if f.is_file() else missing).append(k)
    req_missing = [k for k in missing if k in (day.get("required") or [])]
    if req_missing:
        raise PvfError(f"the day is refused: the required kind(s) {req_missing} have no file in {src}")
    for k in missing:
        if not entries[k].get("optional"):
            log.warning("%s: no file in %s: skipped", k, src)
    kinds = order(present, {k: after_of(k, entries[k]) for k in present})
    results, refused, records = {}, [], {}
    for k in kinds:
        e = entries[k]
        if any(d in refused for d in after_of(k, e)):
            results[k] = {"KIND": k, "STATUS": "SKIPPED", "WHY": "a kind it depends on was refused"}
            continue
        try:
            if e.get("run") == "pvf":
                results[k] = run_pvf(a, e, src, out)
                if not a.dry_run:
                    records[k] = list(read_jsonl(folder / f"scenario-pv.{a.date}.jsonl.gz"))
                continue
            kids = {n.upper(): src / fill(f, a.date) for n, f in (e.get("child") or {}).items()}
            run_values = {"source_system": e.get("source-system"), "batch_id": e.get("batch-id")}
            s = csv_load.convert(csv_load.LoadConfig(k, src / fill(e["csv"], a.date), a.date, e.get("source"),
                                                    Path(e["mapping"]) if e.get("mapping") else None, kids, out, run_values, a.lake,
                                                    dry_run=a.dry_run))
            records[k] = s.pop("_RECORDS")
            results[k] = s
        except PvfError as err:
            results[k] = {"KIND": k, "STATUS": "REFUSED", "WHY": str(err).splitlines()[0]}
            refused.append(k)
            log.error("%s refused: %s", k, results[k]["WHY"])
            if not a.keep_going:
                break
    link_results = []
    for k, recs in records.items():
        e = entries[k]
        checks = {} if e.get("run") == "pvf" else KindMapping(Path(e["mapping"]) if e.get("mapping") else mapping_path(k, e.get("source"))).checks
        res = links.check(k, recs, checks, folder, a.date, [Path(x) for x in a.against], a.strict_links) if not a.dry_run else []
        link_results += res
        results[k]["LINK_WARNINGS"] = sum(sum(r["UNRESOLVED"].values()) for r in res)
        if any(r["REFUSE"] for r in res):
            refused.append(k)
            results[k]["STATUS"] = "REFUSED"
            results[k]["WHY"] = "a link under policy refuse does not resolve"
    summary = {"BUSINESS_DATE": a.date, "FOLDER": str(folder), "KINDS": results, "ORDER": kinds, "MISSING": missing, "REFUSED": refused,
               "LINKS": link_results, "SECONDS": t.seconds, "DRY_RUN": a.dry_run}
    for line in table(kinds, results):
        print(line)
    for line in links.say(link_results):
        log.warning("link: %s", line)
    if not a.dry_run:
        folder.mkdir(parents=True, exist_ok=True)
        links.write_report(folder / f"links-{a.date}.csv", link_results)
        write_sha256(folder, a.date)
        (folder / f"mr-{a.date}.summary.json").write_text(json.dumps(summary, indent=2, default=str) + "\n", encoding="utf-8")
    if refused and not a.keep_going:
        raise PvfError(f"the day is refused ({', '.join(refused)}): nothing was delivered; see {folder}")
    files = [f for f in loadable_files(folder) if hooks.kind_of(f) in kinds and hooks.kind_of(f) not in refused] if not a.dry_run else []
    if "scenario-pv" in kinds and not a.dry_run:                 # the PV run's set and FX go with it
        files += [f for f in loadable_files(folder) if hooks.kind_of(f) in ("scenario-set", "fx-scenario")]
    if not a.dry_run:
        delivered = sorted({hooks.kind_of(f) for f in files})
        summary["DELIVERY"] = hooks.deliver(a, files, order(delivered, {k: after_of(k, entries.get(k, {})) for k in delivered}))
    return summary


def table(kinds: list[str], results: dict) -> list[str]:
    head = f"{'kind':22} {'status':9} {'read':>8} {'written':>8} {'rejected':>8} {'links':>6} {'seconds':>7}"
    lines = [head, "-" * len(head)]
    for k in kinds:
        r = results.get(k, {})
        lines.append(f"{k:22} {r.get('STATUS', '-'):9} {r.get('ROWS_READ', 0):>8,} {r.get('RECORDS_WRITTEN', 0):>8,} {r.get('ROWS_REJECTED', 0):>8,} "
                     f"{r.get('LINK_WARNINGS', 0):>6,} {r.get('SECONDS') or 0:>7}")
    return lines


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--date", required=True, type=strict_date, help="the business date YYYY-MM-DD")
    p.add_argument("--from", dest="source", required=True, type=Path, help="the day's folder of CSV files (an explicit path)")
    p.add_argument("--day", required=True, type=Path, help="the day file: which file feeds which kind (an explicit path)")
    p.add_argument("--out", type=Path, help="the folder mr-<date>/ is written in (default: --from)")
    p.add_argument("--lake", help="the lake named in the markers and used by --load (default: the day file's lake:)")
    p.add_argument("--dry-run", action="store_true", help="convert and check every kind; write and deliver nothing")
    p.add_argument("--keep-going", action="store_true", help="a refused kind skips only the kinds that depend on it; the rest is delivered")
    p.add_argument("--strict-links", action="store_true", help="every unresolved link refuses its kind")
    p.add_argument("--against", action="append", default=[], metavar="DIR", help="earlier outputs the links may resolve against (repeatable)")
    p.add_argument("--delete-plan", action="store_true", help="print the lake delete lines that remove the day (vectors first); run nothing")
    hooks.add_arguments(p.add_argument_group("hand over or load (optional)"))
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    return p


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        s = run(a)
    except PvfError as e:
        log.error("mr run refused: %s", str(e).splitlines()[0])
        return 1
    return ((s.get("DELIVERY") or {}).get("EXIT")) or 0


if __name__ == "__main__":
    sys.exit(main())
