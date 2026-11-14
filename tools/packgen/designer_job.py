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

"""The Pack designer's server-side job (console: Build -> New pack): the same code path as `drishti.py pack new`, run in its own process.

    python3 tools/packgen/designer_job.py analyse <workspace>     # every record read, the Sutras drafted: <workspace>/analysis.json
    python3 tools/packgen/designer_job.py finalize <workspace>    # the module written, bound, packed and checked: <workspace>/dist/<name>-<version>.dmod

<workspace>/request.json holds the choices ({name, version, title, description, kindPrefix, match, key, date, minDocs, connector, filesRoot, edits}),
<workspace>/in/ the uploaded files. The job writes <workspace>/status.json as it goes ({state, rows, seconds, lines}). The analysis is
packprofile.scan over every record (`pack new --dry-run` is the same pass); the drafts are `sutra design --each` (tools/sutragen.py);
finalize is module_from_jsonl.run with the edits file (`pack new --sutra-edits`). Nothing is duplicated here: this file only maps the
request onto the command's options and the analysis onto JSON for the page."""
from __future__ import annotations

import json
import os
import pathlib
import sys
import time
import traceback

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "common"))

LINES = 400


class Status:
    def __init__(self, wd: pathlib.Path):
        self.f, self.d = wd / "status.json", {"state": "running", "rows": 0, "seconds": 0.0, "lines": [], "t0": time.time(), "error": None}
        self.write()

    def write(self) -> None:
        self.d["seconds"] = round(time.time() - self.d["t0"], 1)
        tmp = self.f.with_name("status.json.tmp")
        tmp.write_text(json.dumps(self.d), encoding="utf-8")
        os.replace(tmp, self.f)

    def say(self, text="") -> None:
        for line in str(text).splitlines() or [""]:
            self.d["lines"].append(line)
            if line.lstrip().endswith(" s") and "rows" in line:           # "  12,345 rows, 3 s": the scan's progress
                try:
                    self.d["rows"] = int(line.split("rows")[0].replace(",", "").strip())
                except ValueError:
                    pass
        self.d["lines"] = self.d["lines"][-LINES:]
        self.write()

    def finish(self, state: str, error: str | None = None) -> None:
        self.d["state"], self.d["error"] = state, error
        self.write()


def argv_of(req: dict, wd: pathlib.Path) -> list[str]:
    """The `pack new` command line of the request (the options the page chooses)."""
    a = [str(wd / "in"), "--name", req["name"], "--recursive", "--version", req.get("version") or "1.0.0", "--out", str(wd / "module"),
         "--dist", str(wd / "dist"), "--force", "--site", str(req.get("site") or wd / "site"), "--min-docs", str(int(req.get("minDocs") or 1)),
         "--publisher", req.get("publisher") or "local"]
    for flag, key in (("--title", "title"), ("--description", "description"), ("--kind-prefix", "kindPrefix"), ("--key", "key"), ("--date", "date"),
                      ("--match", "match"), ("--mnemonic", "mnemonic"), ("--files-root", "filesRoot"), ("--connector", "connector"),
                      ("--kind", "kind")):
        if req.get(key):
            a += [flag, str(req[key])]
    if req.get("workers"):
        a += ["--workers", str(int(req["workers"]))]
    return a


def report(an, groups) -> dict:
    """The analysis as the page shows it: files and rows, per kind its fields, discriminator candidates, key and date checks, and the Sutras."""
    import packprofile as PP
    p = an.prof
    kinds = {}
    for kind, kp in sorted(p.kinds.items()):
        c = PP.candidates(kind, kp.head)
        scalar = [{"path": f, "docs": s.docs, "types": dict(s.types), "examples": s.examples[:3]} for f, s in kp.fields.items()
                  if not ({"object", "array"} & set(s.types))]
        kinds[kind] = {
            "rows": kp.rows, "key": an.keys.get(kind), "date": an.dates.get(kind), "match": list(an.match.get(kind) or []), "why": an.why.get(kind, []),
            "fields": scalar, "matchCandidates": c.get("matchCandidates", [])[:30], "keyCandidates": c.get("keyCandidates", [])[:8],
            "dateCandidates": c.get("dateCandidates", [])[:8],
            "unique": kp.unique(), "dupRows": kp.dup_rows, "dupNamed": kp.dup_named, "noKey": kp.no_key, "noDate": kp.no_date,
            "dates": sorted(kp.dates.items())[:60], "dateCount": len(kp.dates), "combinations": len(kp.combos)}
    sutras = []
    for g in groups:
        sutras.append({"name": g.name, "kind": g.kind, "fields": g.fields, "values": ["" if v is None else v for v in g.values], "label": g.label,
                       "count": int(getattr(g, "count", 0) or 0), "yaml": g.path.read_text(encoding="utf-8") if g.path else "", "samples": g.samples[:5],
                       "priority": g.priority})
    total = sum(s["count"] for s in sutras) or 1
    rare = max(1, int(total * 0.001))
    for s in sutras:
        s["rare"] = s["count"] <= rare and len(sutras) > 3
    return {"files": p.files, "rows": p.rows, "bad": p.bad, "badNamed": p.bad_named, "undated": p.undated[:10], "seconds": round(p.seconds, 1),
            "kinds": kinds, "sutras": sutras, "skipped": {k: [[list(v), n] for v, n in vs] for k, vs in an.skipped.items()}}


def analyse(wd: pathlib.Path, st: Status) -> None:
    import module_from_jsonl as MFJ
    import pack_from_jsonl as PFJ
    SG = PFJ.SG
    req = json.loads((wd / "request.json").read_text(encoding="utf-8"))
    a = MFJ.build_parser().parse_args(argv_of(req, wd))
    an = PFJ.analyse(a, st.say)
    if not an.groups:
        raise RuntimeError("no combination has documents: nothing to design")
    a.jar, a.java = SG.find_jar(a.jar), SG.find_java(a.java)
    draft = wd / "draft"
    st.say(f"drafting {len(an.groups)} Sutra(s) ...")
    SG.draft(an.groups, a, draft / "sutras", draft / "tests", st.say)
    out = report(an, an.groups)
    (wd / "analysis.json").write_text(json.dumps(out, default=str), encoding="utf-8")
    st.say(f"{out['rows']:,} rows in {out['files']} file(s) read; {len(out['sutras'])} Sutra(s)")


def finalize(wd: pathlib.Path, st: Status) -> None:
    import drishti as cli
    import module_from_jsonl as MFJ
    req = json.loads((wd / "request.json").read_text(encoding="utf-8"))
    a = MFJ.build_parser().parse_args(argv_of(req, wd) + ["--allow-shared-kinds"] * bool(req.get("allowSharedKinds")))
    edits = wd / "edits.json"
    edits.write_text(json.dumps(req.get("edits") or {}), encoding="utf-8")
    a.sutra_edits = edits
    a.load = bool(req.get("load", True))
    rc = MFJ.run(a, cli, st.say)
    if rc:
        raise RuntimeError("the module was not made: see the log")
    dmod = wd / "dist" / f"{a.name}-{a.version}.dmod"
    out = {"dmod": dmod.name, "bytes": dmod.stat().st_size, "name": a.name, "version": a.version}
    (wd / "result.json").write_text(json.dumps(out), encoding="utf-8")


def main(argv) -> int:
    op, wd = argv[0], pathlib.Path(argv[1]).resolve()
    st = Status(wd)
    try:
        {"analyse": analyse, "finalize": finalize}[op](wd, st)
    except SystemExit as e:                       # the tools stop with a message
        st.finish("failed", str(e.code) if not isinstance(e.code, int) else "stopped with exit " + str(e.code))
        return 1
    except Exception as e:                        # noqa: BLE001 - the page shows the words, the log keeps the trace
        st.say(traceback.format_exc())
        st.finish("failed", str(e) or type(e).__name__)
        return 1
    st.finish("done")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
