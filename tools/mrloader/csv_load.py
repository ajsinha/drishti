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

"""The generic CSV loader: ONE kind's CSV (and its child files) to checked records, written as the data inbox and `lake build` take them.
It converts and loads nothing itself; --inbox or --load hand the files on (docs/plans/CSV_LOADERS.md, tools/mrloader/docs/<kind>.md).

examples (from the repository root):
  python3 tools/mrloader/csv_load.py --kind var-limit --csv data/mr/var_limits_20261029.csv --date 2026-10-29
  python3 tools/mrloader/csv_load.py --kind book --source pvf --csv data/pvf/portfolio_d2.csv --date 2026-10-29 --dry-run
  python3 tools/mrloader/csv_load.py --kind position-sensitivity --csv data/mr/sens_positions_20261029.csv \\
      --child SENSITIVITIES=data/mr/sens_20261029.csv --date 2026-10-29 --source-system MUREX
  python3 tools/mrloader/csv_load.py --kind var-limit --csv FILE --date 2026-10-29 --inbox          ... and hand it to the data inbox
  python3 tools/mrloader/csv_load.py --kind var-limit --csv FILE --date 2026-10-29 --load --lake risklake   ... or load it now
  python3 tools/mrloader/csv_load.py --kind counterparty --template > counterparty.csv              the header and one example row
  python3 tools/mrloader/csv_load.py --kinds                                                         every kind with a mapping, in order

the mapping: tools/mrloader/kinds/<kind>.yaml (the CSV columns named as the contract's fields), kinds/<kind>.<source>.yaml with --source,
or a site's own file by explicit path with --mapping FILE. The file's format (delimiter, encoding, decimal and thousands separators, date
format, null words) is DECLARED in the mapping's csv: block over kinds/shared/csv.yaml: never guessed.

writes, under <out>/mr-<date>/ (--out defaults to the CSV's folder):
  <kind>.<date>.jsonl                the records, plain JSON Lines, in key order (--no-plain: left out)
  <kind>.<date>.jsonl.gz (+ .ready)  the same, the file that is loaded; the marker: pack, kind, mode replace, rows, sha256 (lake with --lake)
  <kind>.<date>.rejects.csv          one line per problem: FILE, ROW, KEY, COLUMN, FIELD, VALUE, REASON, WHY, FIX
  <kind>.<date>.summary.json         rows read, written, rejected by reason, the dialect, columns ignored and refused, known gaps, seconds
  mr-<date>.sha256                   the SHA-256 of every loadable file of the folder
A rejected row in a kind whose mapping says on-reject: refuse (masters, limits) refuses the whole kind: nothing loadable is written; fix
the file and run again. on-reject: skip (the large kinds) writes the good rows, unless more than max-rejects are rejected.
Exit 0 done (the reject report lists what was left out), 1 refused (one line saying why), 2 usage; with --load, lake build's exit code
when it is not zero.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

_TOOLS = str(Path(__file__).resolve().parents[1])
if _TOOLS not in sys.path:
    sys.path.insert(0, _TOOLS)

import yaml  # noqa: E402

from mrloader import csvread as R, hooks  # noqa: E402
from mrloader.common import PvfError, Timer, log, peak_memory_mb, setup_logging, strict_date  # noqa: E402
from mrloader.contract import Contract  # noqa: E402
from mrloader.kindmap import KINDS_DIR, KindMapping, mapping_path, nest  # noqa: E402
from mrloader.package import KindWriter, day_folder, write_sha256  # noqa: E402
from mrloader.rejects import Problem, Rejects  # noqa: E402

NEVER_CLASSES = {"computed": "a figure Drishti computes when asked", "drishti-owned": "kept by Drishti itself"}


@dataclass
class LoadConfig:
    """One kind's run: the kind (or a mapping file), its CSV and child files, the business date, where the files go."""

    kind: str | None
    csv: Path
    business_date: str
    source: str | None = None
    mapping: Path | None = None
    children: dict = field(default_factory=dict)
    out: Path | None = None
    run: dict = field(default_factory=dict)
    lake: str | None = None
    limit: int | None = None
    dry_run: bool = False
    strict: bool = False
    plain: bool = True
    against: list = field(default_factory=list)


def never_reason(kind: str, klass: str | None) -> str | None:
    """Why ``kind`` is never loaded from CSV (its contract's class, or kinds/shared/never.yaml), or None."""
    if klass in NEVER_CLASSES:
        return f"the kind {kind} is {klass}: {NEVER_CLASSES[klass]}; it is never loaded"
    never = yaml.safe_load((KINDS_DIR / "shared" / "never.yaml").read_text(encoding="utf-8")) or {}
    return f"the kind {kind} is never loaded from CSV: {never[kind]}" if kind in never else None


def load_mapping(c: LoadConfig) -> KindMapping:
    """The run's mapping (refused, with the reason, for a kind that is never loaded)."""
    if c.kind:
        why = never_reason(c.kind, None)
        if why is None:
            try:
                why = never_reason(c.kind, Contract.for_kind(c.kind).klass)
            except PvfError:
                pass                                              # no contract: the mapping's own lookup says so
        if why:
            raise PvfError(why)
    path = c.mapping or mapping_path(c.kind, c.source)
    m = KindMapping(path, c.run)
    if c.kind and m.kind != c.kind:
        raise PvfError(f"{path} maps the kind {m.kind}, not {c.kind}")
    why = never_reason(m.kind, m.contract.klass)
    if why:
        raise PvfError(why)
    return m


def _modified_utc(path: Path) -> str:
    t = dt.datetime.fromtimestamp(Path(path).stat().st_mtime, dt.timezone.utc)
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def check_header(m: KindMapping, f: R.CsvFile, child: str | None = None) -> dict:
    """The header against the mapping, before a row is read: every column the inputs require, and for each required field one column of
    its priority list (unless it has a default). Refuses listing the missing columns and their near matches; returns what is absent
    but optional, and the refused computed columns present."""
    need = list(m.columns_needed(child))
    absent = set()
    for name, rule in m.fields.items():
        if (child is None and "[]." in name) or (child is not None and not name.startswith(child + "[].")):
            continue
        src = rule.get("from")
        if not src:
            continue
        cols = [R.column_name(x) for x in (src if isinstance(src, list) else [src])]
        if any(x in f.columns for x in cols):
            continue
        fd = m.contract.field(name) or {}
        if fd.get("presence") == "required" and "default" not in rule and name not in m.omit:
            need.append(cols[0])
        else:
            absent.update(cols)
    missing = f.missing(sorted(set(need)))
    if missing:
        raise PvfError(f"{f.name}: the column(s) the mapping needs are missing: {'; '.join(missing)} (the header has {f.columns})")
    return {"ABSENT_OPTIONAL": sorted(absent), "REFUSED_PRESENT": sorted(c for c in f.columns if c in m.refuse_columns)}


def read_children(m: KindMapping, c: LoadConfig, rejects: Rejects, summary: dict) -> dict:
    """Each child file's cleaned rows grouped by their parent key: {list name: {key: [(file, row, row dict)]}}."""
    out = {}
    for name, spec in m.children.items():
        path = c.children.get(name)
        if path is None:
            raise PvfError(f"the kind {m.kind} reads its {name} from a second file: give --child {name}=FILE")
        f = R.CsvFile(Path(path), m.dialect)
        try:
            info = check_header(m, f, name)
            on = [R.column_name(x) for x in (spec["on"] if isinstance(spec.get("on"), list) else [spec.get("on")])]
            if not any(x in f.columns for x in on):
                raise PvfError(f"{f.name}: none of the parent key column(s) {on} (inputs.children.{name}.on)")
            groups: dict = defaultdict(list)
            n = 0
            for row_no, row in f.rows():
                n += 1
                if isinstance(row, Problem):
                    rejects.add(f.name, row_no, None, row)
                    continue
                try:
                    row = m.clean_row(row)
                except Problem as p:
                    rejects.add(f.name, row_no, None, p)
                    continue
                key = next((row.get(x) for x in on if row.get(x)), None)
                if key is None:
                    rejects.add(f.name, row_no, None, Problem("ORPHAN_CHILD", f"no parent key ({', '.join(on)})", on[0], f"{name}[]"))
                    continue
                groups[str(key)].append((f.name, row_no, row))
            out[name] = {"groups": groups, "on": on, "spec": spec, "file": f.name}
            summary.setdefault("CHILD_FILES", {})[name] = {"FILE": str(path), "ROWS_READ": n, **info}
        finally:
            f.close()
    return out


def _classify(problem: str) -> tuple[str, str]:
    """A contract check's sentence as (reason code, field)."""
    fld = problem.split(":", 1)[0].strip()
    for words, code in (("required, missing", "MISSING_REQUIRED"), ("is not one of", "BAD_ENUM"), ("does not match", "BAD_PATTERN"),
                        ("derived", "DERIVED_FIELD"), ("not a field of", "CONTRACT"), ("UPPER_SNAKE", "CONTRACT")):
        if words in problem:
            return code, fld
    return "BAD_VALUE", fld


def _children_of(m: KindMapping, kids: dict, row: dict, ctx: dict, used: set) -> dict:
    """The child lists of one parent row, each child mapped; Problem on a duplicate or a missing list."""
    out = {}
    for name, k in kids.items():
        key = next((row.get(x) for x in k["on"] if row.get(x)), None)
        rows = k["groups"].get(str(key), []) if key is not None else []
        if key is not None:
            used.add((name, str(key)))
        recs, seen = [], {}
        uniq = [R.column_name(x) for x in k["spec"].get("unique") or []]
        for fname, row_no, crow in rows:
            try:
                rec = m.apply_row(crow, ctx, name)
            except Problem as p:
                p.why = f"{fname} row {row_no}: {p.why}"
                raise
            if uniq:
                sig = tuple(rec.get(u) for u in uniq)
                if sig in seen:
                    raise Problem("DUPLICATE_CHILD", f"{fname} rows {seen[sig]} and {row_no}: the same {', '.join(uniq)} twice", "",
                                  f"{name}[]", "|".join(map(str, sig)), "keep one row per " + ", ".join(uniq))
                seen[sig] = row_no
            recs.append(rec)
        if not recs and k["spec"].get("empty", "reject") == "reject":
            raise Problem("NO_CHILDREN", f"no row in {k['file']} for this record ({', '.join(k['on'])} {key})", k["on"][0], f"{name}[]",
                          key, f"send its {name} rows, or leave the record out")
        if recs or k["spec"].get("empty") != "drop":
            out[name] = recs
    return out


def _when(m: KindMapping, rec: dict) -> list[Problem]:
    """The mapping's conditional rules (checks.when: if a field has a value, these fields are needed)."""
    out = []
    for rule in m.checks.get("when") or []:
        cond, need = rule.get("if") or {}, rule.get("need") or []
        hit = True
        for f, want in cond.items():
            if "[]." in f:
                lst, sub = f.split("[].", 1)
                vals = [x.get(sub) for x in rec.get(lst) or []]
                want = want if isinstance(want, list) else [want]
                hit = hit and any(v in want for v in vals)
            else:
                hit = hit and (rec.get(f) in (want if isinstance(want, list) else [want]))
        if not hit:
            continue
        for n in need:
            if "[]." in n:
                lst, sub = n.split("[].", 1)
                cf, cv = next(iter(cond.items()))
                csub = cf.split("[].", 1)[1] if "[]." in cf else None
                for i, x in enumerate(rec.get(lst) or []):
                    if (csub is None or x.get(csub) in (cv if isinstance(cv, list) else [cv])) and x.get(sub) in (None, ""):
                        out.append(Problem("CONDITION", f"{n} is needed when {cf} is {cv}", "", f"{lst}[{i}].{sub}"))
            elif rec.get(n) in (None, ""):
                out.append(Problem("CONDITION", f"{n} is needed when {cond}", "", n))
    return out


def convert(c: LoadConfig) -> dict:
    """One kind converted: mapping and header checked first (fail fast), every row mapped and checked, the date checked as a whole,
    then the files written (or the kind refused). The summary; PvfError on a refusal."""
    t = Timer()
    m = load_mapping(c)
    folder = day_folder(c.out or Path(c.csv).resolve().parent, c.business_date)
    ctx = m.resolve_run(c.business_date, {"main_file_modified": _modified_utc(c.csv) if Path(c.csv).is_file() else ""})
    stem = folder / f"{m.kind}.{c.business_date}"
    summary: dict = {"KIND": m.kind, "SOURCE": m.source, "MAPPING": str(m.path), "BUSINESS_DATE": c.business_date, "CSV": str(c.csv),
                     "DRY_RUN": c.dry_run, "DIALECT": m.dialect.describe(), "CONTRACT_GAPS": m.omit}
    _needs_run_values(m, ctx)
    rename = {R.column_name(k): R.column_name(v) for k, v in (m.main.get("rename") or {}).items()}
    main = R.CsvFile(Path(c.csv), m.dialect, rename)
    summary["DIALECT"]["DELIMITER"] = main.delimiter
    try:
        info = check_header(m, main)
        if info["REFUSED_PRESENT"]:
            words = "; ".join(f"{x}: not loaded, {m.refuse_columns[x]}" for x in info["REFUSED_PRESENT"])
            if c.strict:
                raise PvfError(f"{main.name}: computed column(s) present (--strict): {words}")
            log.info("%s", words)
        summary["COLUMNS_REFUSED"] = {x: m.refuse_columns[x] for x in info["REFUSED_PRESENT"]}
        summary["COLUMNS_ABSENT"] = info["ABSENT_OPTIONAL"]
        read = m.columns_read() | {R.column_name(x) for x in m.columns_needed()}
        summary["COLUMNS_IGNORED"] = [] if m.pass_columns == "*" else sorted(x for x in main.columns if x not in read and x not in m.refuse_columns)
        if not c.dry_run:
            folder.mkdir(parents=True, exist_ok=True)
        rejects = Rejects(None if c.dry_run else Path(f"{stem}.rejects.csv"))
        kids = read_children(m, c, rejects, summary)
        valid = m.main.get("valid")
        records: list[tuple[str, int, dict]] = []
        used: set = set()
        rows_read = skipped = 0
        for row_no, row in main.rows():
            rows_read += 1
            if c.limit and rows_read > c.limit:
                break
            if isinstance(row, Problem):
                rejects.add(main.name, row_no, None, row)
                continue
            key = None
            try:
                row = m.clean_row(row)
                if valid and not _valid_on(row, valid, c.business_date, m):
                    skipped += 1
                    continue
                flat = m.apply_row(row, ctx)
                key = flat.get(m.contract.key)
                flat.update(_children_of(m, kids, row, ctx, used))
                rec = nest(flat)
                if "BUSINESS_DATE" in rec and rec["BUSINESS_DATE"] != c.business_date:
                    raise Problem("WRONG_BUSINESS_DATE", f"the row's date {rec['BUSINESS_DATE']} is not the run's {c.business_date}: one file "
                                  "is one business date", "BUSINESS_DATE", "BUSINESS_DATE", rec["BUSINESS_DATE"], "run with the file's own --date")
                bad = m.contract.check(rec, extra_ok=bool(m.pass_columns), omitted=frozenset(m.omit))
                problems = [Problem(*_classify(b)[:1], b, "", _classify(b)[1]) for b in bad] + _when(m, rec)
                if problems:
                    for p in problems:
                        rejects.add(main.name, row_no, key, p)
                    continue
                records.append((str(rec.get(m.contract.key)), row_no, rec))
            except Problem as p:
                rejects.add(main.name, row_no, key, p)
        for name, k in kids.items():                              # children whose parent the main file does not have
            for key, rows in k["groups"].items():
                if (name, key) not in used:
                    for fname, row_no, _ in rows:
                        rejects.add(fname, row_no, key, Problem("ORPHAN_CHILD", f"no record in {main.name} has {', '.join(k['on'])} {key}",
                                                                k["on"][0], f"{name}[]", key, "send its parent row, or leave it out"))
        records = _date_rules(m, records, rejects, main.name)
    finally:
        main.close()
    rejects.close()
    rejected_rows = len(rejects.rows)
    summary.update({"ROWS_READ": rows_read, "ROWS_NOT_VALID_ON_DATE": skipped, "RECORDS_WRITTEN": 0, "ROWS_REJECTED": rejected_rows,
                    "PROBLEMS_BY_REASON": dict(rejects.by_reason), "FIRST_PROBLEMS": rejects.first, "ON_REJECT": m.on_reject,
                    "FIELD_SOURCES": {k: dict(v) for k, v in m.sources.items()}})
    refused = _refusal(m, rejected_rows, rows_read, rejects.by_reason.get("DUPLICATE_KEY", 0))
    if refused:
        summary.update({"STATUS": "REFUSED", "WHY": refused, "SECONDS": t.seconds})
        _finish_summary(c, stem, summary)
        raise PvfError(refused + (f"; fix the rows listed in {stem}.rejects.csv" if not c.dry_run else ""), )
    records.sort(key=lambda x: x[0])
    summary["RECORDS_WRITTEN"] = len(records)
    if not records:
        summary.update({"STATUS": "REFUSED", "WHY": "no record to write", "SECONDS": t.seconds})
        _finish_summary(c, stem, summary)
        raise PvfError(f"{m.kind} {c.business_date}: no record to write")
    if not c.dry_run:
        w = KindWriter(folder, m.kind, c.business_date, m.pack, c.plain)
        for _, _, rec in records:
            w.write(rec)
        summary["FILE"] = str(w.finish(c.lake))
        summary["SHA256"] = str(write_sha256(folder, c.business_date))
    summary.update({"STATUS": "DONE", "SECONDS": t.seconds, "PEAK_MEMORY_MB": peak_memory_mb(), "PACK": m.pack, "AFTER": m.after})
    _finish_summary(c, stem, summary)
    summary["_RECORDS"] = [r for _, _, r in records]
    return summary


def _needs_run_values(m: KindMapping, ctx: dict) -> None:
    """A required field built from a run value the run does not have (``{$source_system}`` with no --source-system) refuses the run
    before a row is read, naming the option: the tool never invents it."""
    import re
    for name, rule in m.fields.items():
        f = m.contract.field(name) or {}
        if f.get("presence") != "required" or not isinstance(rule, dict):
            continue
        for ref in re.findall(r"\{\$([A-Za-z_][A-Za-z0-9_]*)\}", str(rule.get("template", ""))):
            if ctx.get(ref) in (None, "") and "default" not in rule:
                raise PvfError(f"{m.kind}: {name} is built from the run value {ref}, which this run does not have: give --{ref.replace('_', '-')}")


def _valid_on(row: dict, valid: dict, date: str, m: KindMapping) -> bool:
    """Whether a row is valid on the date (inputs.main.valid: from and to columns, the to day excluded)."""
    lo = row.get(R.column_name(valid["from"])) if valid.get("from") else None
    hi = row.get(R.column_name(valid["to"])) if valid.get("to") else None
    lo = R.date(lo, m.dialect, valid.get("from", ""), "valid") if lo else None
    hi = R.date(hi, m.dialect, valid.get("to", ""), "valid") if hi else None
    return (lo is None or lo <= date) and (hi is None or date < hi)


def _date_rules(m: KindMapping, records: list, rejects: Rejects, fname: str) -> list:
    """The rules over the date's records: the key unique (and checks.unique), one value (checks.one-value). A repeat is listed for
    every row after the first and, as a duplicate key, refuses the kind whatever on-reject says."""
    keys = [m.contract.key] + [k for k in m.checks.get("unique") or [] if k != m.contract.key]
    first: dict = {}
    keep = []
    for key, row_no, rec in records:
        dup = None
        for k in keys:
            v = rec.get(k)
            if (k, v) in first:
                dup = (k, v, first[(k, v)])
                break
        if dup:
            rejects.add(fname, row_no, key, Problem("DUPLICATE_KEY", f"the same {dup[0]} as row {dup[2]}", dup[0], dup[0], dup[1],
                                                    "keep one row per " + dup[0]))
            continue
        for k in keys:
            first[(k, rec.get(k))] = row_no
        keep.append((key, row_no, rec))
    for k in m.checks.get("one-value") or []:
        vals = Counter(str(r.get(k)) for _, _, r in keep)
        if len(vals) > 1:
            common = vals.most_common(1)[0][0]
            for key, row_no, r in list(keep):
                if str(r.get(k)) != common:
                    rejects.add(fname, row_no, key, Problem("CONDITION", f"{k} {r.get(k)} differs from the date's {common}: one value a date", k, k, r.get(k)))
                    keep.remove((key, row_no, r))
    return keep


def _refusal(m: KindMapping, rejected: int, read: int, duplicates: int = 0) -> str | None:
    """Why the kind is refused for its rejects (on-reject, max-rejects, a duplicate key), or None."""
    if not rejected:
        return None
    if duplicates:
        return f"REFUSED: {duplicates} duplicate key(s) in {m.kind}: one row per {m.contract.key}"
    if m.on_reject == "refuse":
        return f"REFUSED: on-reject is refuse for {m.kind} and {rejected} row(s) were rejected"
    cap = str(m.max_rejects) if m.max_rejects is not None else None
    if cap:
        limit = read * float(cap[:-1]) / 100 if cap.endswith("%") else float(cap)
        if rejected > limit:
            return f"REFUSED: {rejected} of {read} rows rejected, more than max-rejects {cap} for {m.kind}"
    return None


def _finish_summary(c: LoadConfig, stem: Path, summary: dict) -> None:
    if not c.dry_run:
        Path(f"{stem}.summary.json").write_text(json.dumps(summary, indent=2, default=str) + "\n", encoding="utf-8")


def say(summary: dict) -> list[str]:
    """The console's lines (section 10.3)."""
    by = summary.get("PROBLEMS_BY_REASON") or {}
    reasons = f" ({', '.join(f'{k} {v}' for k, v in sorted(by.items()))})" if by else ""
    lines = [f"{summary['KIND']} {summary['BUSINESS_DATE']}: {summary.get('ROWS_READ', 0):,} rows read, {summary.get('RECORDS_WRITTEN', 0):,} "
             f"written, {summary.get('ROWS_REJECTED', 0):,} rejected{reasons}"]
    if summary.get("STATUS") == "REFUSED":
        lines.append(summary.get("WHY", "REFUSED"))
    for p in (summary.get("FIRST_PROBLEMS") or [])[:1]:
        lines.append(f"first: row {p['ROW']}, column {p['COLUMN'] or '-'}: {p['VALUE'] or '(empty)'}: {p['WHY']}")
    for col, why in (summary.get("COLUMNS_REFUSED") or {}).items():
        lines.append(f"{col}: not loaded; {why}")
    return lines


# ---- helpers ------------------------------------------------------------------------------------------------------------------
def template(kind: str, source: str | None = None, mapping: Path | None = None) -> str:
    """The header row and one example row of a kind's CSV (the main file; a child file's columns follow after a blank line)."""
    m = KindMapping(mapping or mapping_path(kind, source))
    out = []
    for child in [None, *m.children]:
        cols, ex = [], []
        for name, rule in m.fields.items():
            if (child is None and "[]." in name) or (child is not None and not name.startswith(child + "[].")):
                continue
            src = rule.get("from")
            if not src:
                continue
            col = R.column_name(src[0] if isinstance(src, list) else src)
            if col in cols:
                continue
            f = m.contract.field(name) or {}
            cols.append(col)
            e = f.get("example", "")
            ex.append(str(e).replace(",", " ") if not isinstance(e, list) else "|".join(map(str, e)))
        if child:
            out.append(f"# --child {child}=FILE")
        out += [",".join(cols), ",".join(ex)]
    return "\n".join(out) + "\n"


def kinds_table() -> list[dict]:
    """Every shipped mapping: kind, source, pack, inputs, after; in delivery order."""
    rows = []
    for p in sorted(KINDS_DIR.glob("*.yaml")):
        try:
            m = None if p.name == "scenario-pv.pvf.yaml" else KindMapping(p)
        except PvfError as e:
            rows.append({"KIND": p.stem, "SOURCE": "?", "ERROR": str(e)})
            continue
        if m is None:
            rows.append({"KIND": "scenario-pv", "SOURCE": "pvf", "PACK": "market-risk", "INPUTS": "pvf_run.py (the PVF join)",
                         "AFTER": ["scenario-set", "fx-scenario"], "FILE": p.name})
            continue
        rows.append({"KIND": m.kind, "SOURCE": m.source, "PACK": m.pack, "INPUTS": ", ".join(["--csv", *[f"--child {k}=" for k in m.children]]),
                     "AFTER": m.after, "FILE": p.name})
    rank = {k: i for i, k in enumerate(hooks.DEFAULT_ORDER)}
    return sorted(rows, key=lambda r: (rank.get(r["KIND"], 99), r["SOURCE"] != "canonical", r["KIND"]))


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--kind", help="the kind (var-limit, counterparty ...): its mapping is kinds/<kind>.yaml")
    p.add_argument("--source", help="a source's own columns: kinds/<kind>.<source>.yaml (pvf ...)")
    p.add_argument("--mapping", type=Path, help="a mapping file by explicit path (a site's own), instead of the shipped one")
    p.add_argument("--csv", type=Path, help="the kind's CSV (one row a record)")
    p.add_argument("--child", action="append", default=[], metavar="NAME=FILE", help="a child file grouped into the list NAME (SENSITIVITIES=sens.csv)")
    p.add_argument("--date", type=strict_date, help="the business date YYYY-MM-DD: names the files; every row's own date must equal it")
    p.add_argument("--out", type=Path, help="the folder mr-<date>/ is written in (default: the CSV's folder)")
    p.add_argument("--lake", help="the lake named in the .ready (and used by --load)")
    p.add_argument("--limit", type=int, help="only the first N rows of the main file: a trial")
    p.add_argument("--dry-run", action="store_true", help="check the mapping, the header and every row; write nothing")
    p.add_argument("--strict", action="store_true", help="a computed column present (refuse-columns) refuses the run")
    p.add_argument("--no-plain", action="store_true", help="leave out the plain .jsonl (a very large kind)")
    r = p.add_argument_group("run values ({$name} in the mapping's templates)")
    r.add_argument("--batch-id")
    r.add_argument("--computed-at", help="ISO-8601 UTC (default: the main file's last-modified time where the mapping uses it)")
    r.add_argument("--restatement", type=int, help="0 for the date's first delivery, then 1, 2 ...")
    r.add_argument("--source-system", help="the system the rows come from (where the mapping asks for it)")
    hooks.add_arguments(p.add_argument_group("hand over or load (optional; neither: the lines to do it by hand are printed)"))
    h = p.add_argument_group("helpers")
    h.add_argument("--template", action="store_true", help="print the kind's CSV header and one example row")
    h.add_argument("--kinds", action="store_true", help="list every kind with a mapping, its inputs and its place in the day")
    p.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    return p


def config(a: argparse.Namespace) -> LoadConfig:
    if not (a.kind or a.mapping) or not a.csv or not a.date:
        raise PvfError("give --kind (or --mapping), --csv and --date")
    kids = {}
    for x in a.child:
        if "=" not in x:
            raise PvfError(f"--child {x}: NAME=FILE")
        k, v = x.split("=", 1)
        kids[k.strip().upper()] = Path(v)
    run = {"batch_id": a.batch_id, "computed_at": a.computed_at, "restatement": a.restatement, "source_system": a.source_system}
    return LoadConfig(a.kind, a.csv, a.date, a.source, a.mapping, kids, a.out, run, a.lake, a.limit, a.dry_run, a.strict, not a.no_plain)


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    setup_logging(a.verbose)
    try:
        if a.kinds:
            for r in kinds_table():
                print(f"{r['KIND']:22} {r['SOURCE']:10} {r.get('PACK', ''):18} after {','.join(r.get('AFTER') or []) or '-':40} {r.get('INPUTS', r.get('ERROR', ''))}")
            return 0
        if a.template:
            if not (a.kind or a.mapping):
                raise PvfError("--template needs --kind")
            sys.stdout.write(template(a.kind, a.source, a.mapping))
            return 0
        if a.load and not a.lake:
            raise PvfError("--load needs --lake NAME")
        s = convert(config(a))
        for line in say(s):
            log.info("%s", line)
        if not a.dry_run:
            res = hooks.deliver(a, [s["FILE"]])
            if res and res.get("EXIT"):
                return res["EXIT"]
    except PvfError as e:
        log.error("csv load refused: %s", str(e).splitlines()[0] if str(e) else type(e).__name__)
        return 1
    except OSError as e:
        log.error("csv load refused: %s", e)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
