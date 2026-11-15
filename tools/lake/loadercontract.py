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

"""The loader-contract runner: one fixture, every engine (docs/architecture/POLARS_LOADER_DESIGN.md section 4.1).

``lake/drishti-lakeload/src/test/resources/loader-contract/cases.yaml`` holds the cases; each states what a load of some files does,
by the contract rules C1 to C31. This module builds a case's files and pack in a scratch folder, runs ``lake build`` in process once per
engine, reads the table back with the one reader (Delta Kernel, ``deltaread``) and compares it with the case's ``expect``.

Engines (``ENGINES``): ``java`` (the parallel loader, ``--engine java``: the contract), ``python`` (the pure-Python path,
``--engine deltalake``), ``polars`` (not built: reported as unavailable). A case lists in ``known`` the engines that differ from it
today and why (the design's section 2.4); a known engine must still differ, so the list cannot go stale.

A case::

    name: text                       rules: [C11]
    table: {kind, key, date, partition-by, promote, vectors, flatten, set, latest-by}    # defaults: deal, ID, DATE
    files: {<name>: [line | {object} ...] | {gen: ...} | {archive: zip|tar|tar.gz, members: {<name>: [lines]}} | {gzip-members: [[lines]]}}
    ready: {<file name>: {business-date: ...}}
    run: {mode, as-of, date-from, batch-id, loader-set: [K=V], dry-run, plan}
    before: [{table, files, ready, run}, ...]                                            # earlier loads that set the table up
    expect: {exit, error, rows, rejects, rejected, other-date-rows, file-dates, docs, columns, types, duplicates, commits, absent, summary-keys}

A line that is text is written as it is; a mapping is written as compact JSON. ``$today`` stands for the business today."""
from __future__ import annotations

import gzip
import io
import json
import os
import pathlib
import shutil
import sys
import tarfile
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CASES = ROOT / "lake" / "drishti-lakeload" / "src" / "test" / "resources" / "loader-contract" / "cases.yaml"
for p in (str(HERE), str(ROOT / "tools")):
    if p not in sys.path:
        sys.path.insert(0, p)

#: engine name -> the ``--engine`` flag of ``lake build``; None: not built yet
ENGINES = {"java": "java", "python": "deltalake", "polars": None}
DEFAULT_TABLE = {"kind": "deal", "key": "ID", "date": "DATE"}
DOMAIN = "contract"


def load_cases(path: pathlib.Path = CASES) -> list[dict]:
    import yaml
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    cases = data.get("cases") or []
    names = [c["name"] for c in cases]
    if len(set(names)) != len(names):
        raise ValueError("loader-contract cases: a name is used twice")
    return cases


# ------------------------------------------------------------------------------------------------ building a case's inputs

def _lines(spec, today: str) -> list[str]:
    """A file's lines: text as it is, a mapping as compact JSON, a generator expanded."""
    if isinstance(spec, dict) and "gen" in spec:
        g = spec["gen"]
        out = []
        for i in range(int(g["count"])):
            row = {g.get("id-field", "ID"): str(g.get("id", "g{i}")).format(i=i), g.get("date-field", "DATE"): g.get("date", today)}
            row.update({k: v for k, v in (g.get("fields") or {}).items()})
            v = g.get("vector")
            if v:
                row[v["field"]] = [round((i + 1) * (j + 1) / 7.0, 6) for j in range(int(v["length"]))]
            out.append(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
        return out
    return [x if isinstance(x, str) else json.dumps(x, ensure_ascii=False, separators=(",", ":")) for x in spec]


def _text(lines: list[str], today: str) -> str:
    return "".join(l.replace("$today", today) + "\n" for l in lines)


def write_files(folder: pathlib.Path, files: dict, today: str) -> None:
    for name, spec in (files or {}).items():
        target = folder / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(spec, dict) and "archive" in spec:
            members = {m: _text(_lines(ls, today), today).encode("utf-8") for m, ls in (spec.get("members") or {}).items()}
            kind = spec["archive"]
            if kind == "zip":
                with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
                    for m, b in members.items():
                        z.writestr(m, b)
            else:
                with tarfile.open(target, "w:gz" if kind == "tar.gz" else "w") as t:
                    for m, b in members.items():
                        ti = tarfile.TarInfo(m)
                        ti.size = len(b)
                        t.addfile(ti, io.BytesIO(b))
        elif isinstance(spec, dict) and "gzip-members" in spec:       # a multi-member gzip: each member compressed on its own
            with open(target, "wb") as fh:
                for m in spec["gzip-members"]:
                    fh.write(gzip.compress(_text(_lines(m, today), today).encode("utf-8")))
        elif isinstance(spec, dict) and spec.get("gzip"):
            target.write_bytes(gzip.compress(_text(_lines(spec["lines"], today), today).encode("utf-8")))
        else:
            target.write_text(_text(_lines(spec, today), today), encoding="utf-8")


def write_pack(folder: pathlib.Path, table: dict) -> None:
    """A pack with one Delta connector for the case's table: the ingest key and date, the layout, the connector's ``set:`` constants."""
    import yaml
    t = {**DEFAULT_TABLE, **(table or {})}
    kind = t["kind"]
    ing = {"key": t["key"], "date": t["date"]}
    if t.get("latest-by"):
        ing["latest-by"] = t["latest-by"]
    if t.get("flatten"):
        ing["flatten"] = t["flatten"]
    layout = {"columns": list(t.get("promote") or [])}
    if t.get("vectors"):
        layout["vectors"] = list(t["vectors"])
    if t.get("flatten"):
        layout["flatten"] = t["flatten"]
    settings = {"domain": DOMAIN, "layout": {kind: layout}}
    if t.get("set"):
        settings["set"] = t["set"]
    pack = {"pack": "contract", "ingest": {kind: ing},
            "connectors": {f"delta-{kind}": {"plugin": "delta", "kinds": [kind], "settings": settings}}}
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "pack.yaml").write_text(yaml.safe_dump(pack, sort_keys=False), encoding="utf-8")


def argv(step: dict, table: dict, src: pathlib.Path, pack: pathlib.Path, lake: pathlib.Path, engine_flag: str) -> list[str]:
    t = {**DEFAULT_TABLE, **(table or {})}
    run = step.get("run") or {}
    a = ["--from", str(src), "--pack", str(pack), "--kind", t["kind"], "--lake", str(lake), "--engine", engine_flag, "--json", "-q"]
    if t.get("partition-by"):
        a += ["--partition-by", ",".join(t["partition-by"])]
    if run.get("mode"):
        a += ["--mode", run["mode"]]
    if run.get("as-of"):
        a += ["--as-of", run["as-of"]]
    if run.get("date-from"):
        a += ["--date-from", run["date-from"]]
    if run.get("batch-id"):
        a += ["--batch-id", run["batch-id"]]
    for kv in run.get("loader-set") or []:
        a += ["--loader-set", kv]
    if run.get("dry-run"):
        a.append("--dry-run")
    if run.get("plan"):
        a.append("--plan")
    return a


# ------------------------------------------------------------------------------------------------ running

class Outcome:
    def __init__(self, code: int, summary: dict, table: list[dict] | None, schema: dict | None, commits: int, lake: pathlib.Path):
        self.code, self.summary, self.table, self.schema, self.commits, self.lake = code, summary, table, schema, commits, lake


def _log_versions(table: pathlib.Path) -> dict[int, bool]:
    """version -> whether that commit adds files, from ``_delta_log`` (the log is read as text: one action a line)."""
    out = {}
    log = table / "_delta_log"
    for f in sorted(log.glob("*.json")) if log.is_dir() else []:
        adds = any('"add"' in line[:8] for line in f.read_text(encoding="utf-8").splitlines())
        out[int(f.stem)] = adds
    return out


def _read(table: pathlib.Path):
    import deltaread
    t = deltaread.read(str(table))
    return t.to_pylist(), {f.name: str(f.type) for f in t.schema}


def run_case(case: dict, engine: str, scratch: pathlib.Path) -> Outcome:
    """Runs the case's ``before`` steps and its own load with ``engine`` in ``scratch`` and reads the table back."""
    import build
    import filedate
    flag = ENGINES[engine]
    today = filedate.business_today().isoformat()
    lake = scratch / "lake"
    steps = list(case.get("before") or []) + [case]
    code, summary, versions_before = 0, {}, {}
    for n, step in enumerate(steps):
        table = {**(case.get("table") or {}), **(step.get("table") or {})} if step is not case else (case.get("table") or {})
        src, pack = scratch / f"src{n}", scratch / f"pack{n}"
        shutil.rmtree(src, ignore_errors=True)
        src.mkdir(parents=True)
        write_files(src, step.get("files"), today)
        for name, body in (step.get("ready") or {}).items():
            import yaml
            (src / (name + ".ready")).write_text(yaml.safe_dump(body), encoding="utf-8")
        write_pack(pack, table)
        kind = {**DEFAULT_TABLE, **table}["kind"]
        if step is case:
            versions_before = _log_versions(lake / DOMAIN / kind)
        os.environ["DRISHTI_BI_STORE"] = str(scratch / "bi")
        a = build.build_parser().parse_args(argv(step, table, src, pack, lake, flag))
        code, summary = build.run(a, lambda *_: None)
    kind = {**DEFAULT_TABLE, **(case.get("table") or {})}["kind"]
    path = lake / DOMAIN / kind
    rows = schema = None
    if (path / "_delta_log").is_dir():
        rows, schema = _read(path)
    after = _log_versions(path)
    new = sum(1 for v, adds in after.items() if v not in versions_before and adds)
    return Outcome(code, summary, rows, schema, new, lake)


# ------------------------------------------------------------------------------------------------ comparing

def check(case: dict, o: Outcome, today: str) -> list[str]:
    """The ways the outcome differs from the case's ``expect``; [] when it is the contract."""
    e = case.get("expect") or {}
    bad: list[str] = []
    s = o.summary or {}

    def sub(x):
        return json.loads(json.dumps(x).replace("$today", today))

    if "exit" in e and o.code != e["exit"]:
        bad.append(f"exit {o.code}, expected {e['exit']} ({s.get('error') or s.get('status')})")
    if e.get("error") and e["error"] not in str(s.get("error") or ""):
        bad.append(f"error {s.get('error')!r} lacks {e['error']!r}")
    rows = o.table or []
    if "rows" in e:
        got: dict = {}
        for r in rows:
            got.setdefault(str(r["business_date"]), []).append(r["id"])
        got = {d: sorted(v) for d, v in got.items()}
        want = {d: sorted(v) for d, v in sub(e["rows"]).items()}
        if got != want:
            bad.append(f"rows {got}, expected {want}")
    if e.get("absent") and o.table is not None:
        bad.append("a table was written, none expected")
    if "rejected" in e and s.get("rejected") != e["rejected"]:
        bad.append(f"rejected {s.get('rejected')}, expected {e['rejected']}")
    if "rejects" in e:
        got = (s.get("rejects") or {}).get("byReason") or {}
        if got != e["rejects"]:
            bad.append(f"rejects {got}, expected {e['rejects']}")
    if "other-date-rows" in e and s.get("otherDateRows") != e["other-date-rows"]:
        bad.append(f"otherDateRows {s.get('otherDateRows')}, expected {e['other-date-rows']}")
    if "file-dates" in e:
        got = {f["file"]: (f["date"], f["source"]) for f in s.get("fileDates") or []}
        want = {k: tuple(sub(v)) for k, v in e["file-dates"].items()}
        if {k: v for k, v in got.items() if k in want} != want:
            bad.append(f"fileDates {got}, expected {want}")
    if "duplicates" in e and dict(s.get("duplicates") or {}) != e["duplicates"]:
        bad.append(f"duplicates {s.get('duplicates')}, expected {e['duplicates']}")
    by_id = {r["id"]: r for r in rows}
    for ident, text in (e.get("docs") or {}).items():
        got = by_id.get(ident, {}).get("doc")
        if got != sub(text):
            bad.append(f"doc of {ident}: {got!r}, expected {text!r}")
    for ident, cols in (e.get("columns") or {}).items():
        for c, want in cols.items():
            got = by_id.get(ident, {}).get(c, "<no row or column>")
            if got != want:
                bad.append(f"{ident}.{c} = {got!r}, expected {want!r}")
    for c, t in (e.get("types") or {}).items():
        got = (o.schema or {}).get(c)
        if got != t:
            bad.append(f"column {c} is {got}, expected {t}")
    if "commits" in e and o.commits != e["commits"]:
        bad.append(f"{o.commits} commits that add files, expected {e['commits']}")
    for k in e.get("summary-keys") or []:
        if k not in s:
            bad.append(f"the summary lacks {k!r}")
    if e.get("run-log-lines") is not None:
        log = o.lake / "_drishti-lake" / "runs.log"
        n = len(log.read_text(encoding="utf-8").strip().splitlines()) if log.is_file() else 0
        if n != e["run-log-lines"]:
            bad.append(f"runs.log has {n} lines, expected {e['run-log-lines']}")
    return bad


def run_all(engines=("java", "python"), only: str | None = None) -> dict:
    """Every case on every engine: {(case, engine): [differences]} (the outcome of a ``known`` engine is reported in the open, not
    hidden). For a command-line look; the test (test_loader_contract.py) decides pass or fail."""
    import filedate
    today = filedate.business_today().isoformat()
    out = {}
    for case in load_cases():
        if only and only not in case["name"]:
            continue
        for eng in engines:
            with tempfile.TemporaryDirectory(prefix="loader-contract-") as tmp:
                try:
                    out[(case["name"], eng)] = check(case, run_case(case, eng, pathlib.Path(tmp)), today)
                except Exception as ex:                      # a case that cannot run is a finding, not a crash
                    out[(case["name"], eng)] = [f"did not run: {type(ex).__name__}: {ex}"]
    return out


if __name__ == "__main__":
    wanted = tuple(a for a in sys.argv[1:] if a in ENGINES) or ("java", "python")
    result = run_all(wanted, next((a for a in sys.argv[1:] if a not in ENGINES), None))
    for (name, eng), diffs in result.items():
        print(("ok    " if not diffs else "DIFFER") + f" {eng:7} {name}")
        for d in diffs:
            print("          " + d)
    sys.exit(1 if any(result.values()) else 0)
