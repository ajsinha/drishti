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

"""The BI languages on the command line (docs/guides/CLI_REFERENCE.md "bi expr, bi migrate, bi sql"): Rachana, BI's expression language, and Drishti SQL
over REST, as the token's user. Loaded by drishti.py; every command talks to the Java server, which owns the one parser of each language. Datasets,
models and expressions in BI's old forms are migrated by `drishti.py tables migrate`.

  bi expr functions [--scope row|rule|aggregate]       the function catalogue
  bi expr validate EXPR [--scope S] [--model M] [--alias A]   check one expression (exit 1 on an error)
  bi migrate --boards FILE... [--write] [--keep-old]   2.3 boards to reports with board pages (2.4.0, R7); --write writes <name>.report.yaml beside reports/
  bi migrate --boards [--dry-run]                      the same for the server's stored boards (bi-admin; the server also does it at start)
  bi sql "SELECT ..." --model M [--explain] [--format json|csv|jsonl]   run Drishti SQL over a model (wave A)
"""
import argparse
import json
import pathlib
import sys


def register(bi, srv, ctx) -> None:
    """Adds `bi expr`, `bi migrate` and `bi sql` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    ex = bi.add_parser("expr", help="bi expr functions|validate: Rachana, BI's expression language",
                       description="Rachana, BI's expression language: the function catalogue and checking one expression.")
    ea = ex.add_subparsers(dest="action", metavar="ACTION", required=True)
    f = ea.add_parser("functions", help="list the functions", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi expr functions\n  drishti.py bi expr functions --scope row --json")
    f.add_argument("--scope", choices=["row", "rule", "aggregate"], help="only the functions allowed there")
    f.set_defaults(func=lambda a, extra: functions(a, ctx))
    v = ea.add_parser("validate", help="check one expression in its scope (exit 1 on an error)", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi expr validate \"sum(trades.MTM, where=trades.TRADE_STATUS == 'VALIDATED')\" --model trading-risk@1\n"
                             "  drishti.py bi expr validate \"notional * 2\" --scope row --model dataset:pack.trading.trade")
    v.add_argument("expr")
    v.add_argument("--scope", choices=["row", "rule", "aggregate"], default="aggregate")
    v.add_argument("--model", help="a model reference (name@major) or dataset:<name>; without it only the language and the catalogue are checked")
    v.add_argument("--alias", help="row and rule scope: the dataset alias whose row is in context")
    v.set_defaults(func=lambda a, extra: validate(a, ctx))
    m = bi.add_parser("migrate", help="bi migrate --boards [FILE...]: 2.3 boards to reports with board pages", parents=[srv],
                      description="Converts 2.3 boards into reports whose pages have layout: board (2.4.0, R7). With FILEs it converts board files (without "
                                  "--write it prints what it would write). Without FILEs it converts the server's STORED boards (bi-admin), which the server "
                                  "also does once at start. Datasets, models and expressions in BI's old forms: drishti.py tables migrate.",
                      formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi migrate --boards config/bi/boards --write   # 2.3 boards to reports with board pages (2.4.0)\n"
                             "  drishti.py bi migrate --boards --dry-run                  # the server's stored boards")
    m.add_argument("files", nargs="*", metavar="FILE", help="board files or folders (searched for *.board.yaml); none: the stored boards")
    m.add_argument("--boards", action="store_true", required=True, help="convert 2.3 boards to reports whose pages have layout: board (docs/guides/REPORTS_GUIDE.md)")
    m.add_argument("--write", action="store_true", help="FILEs: write the converted files")
    m.add_argument("--dry-run", action="store_true", help="stored boards: change nothing, show what would change")
    m.add_argument("--keep-old", action="store_true", help="keep the board files")
    m.set_defaults(func=lambda a, extra: migrate(a, ctx))

    s = bi.add_parser("sql", help="bi sql \"SELECT ...\" --model M: Drishti SQL over a model, as you", parents=[srv],
                      description="Runs one read-only Drishti SQL statement over a model (tables are the model's aliases, measures are MEASURE('name')), "
                                  "compiled into the same plan as a model query, with your row rules and masks.",
                      formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi sql \"SELECT trades.desk, MEASURE('MTM') FROM trades GROUP BY trades.desk\" --model trading-risk@1\n"
                             "  drishti.py bi sql \"EXPLAIN SELECT ...\" --model trading-risk@1\n  drishti.py bi sql --file q.sql --model trading-risk@1 --format csv")
    s.add_argument("sql", nargs="?", help="the statement (or --file)")
    s.add_argument("--file", help="read the statement from this file")
    s.add_argument("--model", required=True, help="the model (name@major) or dataset:<name>")
    s.add_argument("--explain", action="store_true", help="the plan instead of the answer")
    s.add_argument("--format", choices=["table", "json", "csv", "jsonl"], default="table")
    s.add_argument("--async", dest="as_run", action="store_true", help="ask it as a run (ARCHITECTURE.md §28): the answer if done within --wait, else the run's id")
    s.add_argument("--wait", default="2s", help="with --async: how long to wait inline (default 2s; 0 returns the run at once)")
    s.add_argument("--yes", action="store_true", help="with --async: accept the confirmation of a large run in advance (DRS-9082)")
    s.set_defaults(func=lambda a, extra: sql(a, ctx))


# ---- expr ------------------------------------------------------------------------------------------------------------

def functions(a, ctx) -> int:
    path = "/api/v1/bi/expr/functions" + (f"?scope={a.scope}" if a.scope else "")
    r = ctx["Api"].from_args(a).json("GET", path)
    if a.json:
        ctx["say_json"](r)
        return 0
    family = None
    for fn in r.get("functions", []):
        if fn["family"] != family:
            family = fn["family"]
            print(f"\n{family}")
        mark = "" if fn["status"] == "built" else f"  (wave {fn['status']})"
        print(f"  {fn['signature']:<58}{fn['description'][:90]}{mark}")
    return 0


def problems_text(problems) -> str:
    out = []
    for p in problems:
        at = p.get("at") or {}
        fix = f"\n      fix: {p['fix']}" if p.get("fix") else ""
        out.append(f"  {p['level']:<8}{p['code']}  at {at.get('start', 0)}-{at.get('end', 0)}: {p['message']}{fix}")
    return "\n".join(out)


def validate(a, ctx) -> int:
    body = {"expr": a.expr, "scope": a.scope}
    if a.model:
        body["model"] = a.model
    if a.alias:
        body["alias"] = a.alias
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/expr/validate", body)
    if a.json:
        ctx["say_json"](r)
    else:
        print(("valid" if r.get("valid") else "INVALID") + f"  ({r.get('syntax')}, {r.get('scope')} scope" + (f", type {r['type']}" if r.get("type") else "") + ")")
        if r.get("converted"):
            print(f"  in DEL v2: {r['converted']}")
        if r.get("problems"):
            print(problems_text(r["problems"]))
    return 0 if r.get("valid") else 1


def migrate(a, ctx) -> int:
    return migrate_boards(a, ctx["Api"].from_args(a), ctx)


def migrate_boards(a, api, ctx) -> int:
    """--boards (2.4.0, R7): board FILEs (or folders of *.board.yaml) converted by the server's converter into reports with board pages, written as
    <name>.report.yaml in the BI folder's reports/ (beside the file when it is not in a boards/ folder) with --write; without FILEs the server's stored boards."""
    if not a.files:
        r = api.json("POST", "/api/v1/bi/migrate/boards", {"dryRun": bool(a.dry_run)})
        if a.json:
            ctx["say_json"](r)
            return 1 if r.get("problems") else 0
        for i in r.get("items", []):
            print(f"board '{i['title']}' ({i['id']}, {i['status']}): {i['state']}" + (f"; the text before is saved state {i['before']}" if i.get("before") else ""))
        done = r.get("wouldConvert" if a.dry_run else "converted", 0)
        print(f"{r.get('scanned', 0)} stored board(s), {done} " + ("would become reports" if a.dry_run else "became reports")
              + (f", {r['problems']} cannot be converted (they keep their text; Admin, BI settings lists them)" if r.get("problems") else ""))
        return 1 if r.get("problems") else 0
    files = []
    for f in a.files:
        p = pathlib.Path(f)
        files.extend(sorted(p.glob("*.board.yaml")) if p.is_dir() else [p])
    failed = 0
    report = []
    for f in files:
        r = api.json("POST", "/api/v1/bi/boards/convert", {"yaml": f.read_text(encoding="utf-8")})
        name = f.name[: -len(".board.yaml")] if f.name.endswith(".board.yaml") else f.stem
        target = (f.parent.parent / "reports" if f.parent.name == "boards" else f.parent) / (name + ".report.yaml")
        report.append({"file": str(f), "converted": r.get("converted"), "to": str(target), "problems": r.get("problems", [])})
        if not r.get("converted"):
            failed += 1
            if not a.json:
                print(f"{f}: NOT CONVERTED: " + "; ".join(p["path"] + ": " + p["message"] for p in r.get("problems", []) if p.get("level") == "error"))
            continue
        if a.write:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(r["yaml"], encoding="utf-8")
            if not a.keep_old:
                f.unlink()
        if not a.json:
            print(f"{f}: {'wrote' if a.write else 'would write'} {target}")
    if a.json:
        ctx["say_json"]({"files": report, "failed": failed, "written": bool(a.write)})
    else:
        print(f"{len(files)} board file(s), {len(files) - failed} converted" + (", written" if a.write else " (add --write to write them)") + (f", {failed} with problems" if failed else ""))
    return 1 if failed else 0


# ---- sql -------------------------------------------------------------------------------------------------------------

def sql(a, ctx) -> int:
    text = pathlib.Path(a.file).read_text(encoding="utf-8") if a.file else a.sql
    if not text:
        raise ctx["CliError"]("bi sql needs a statement (or --file)", 2)
    if a.explain and not text.lstrip().upper().startswith("EXPLAIN"):
        text = "EXPLAIN " + text
    fmt = "json" if a.format == "table" else a.format
    if getattr(a, "as_run", False):                                       # bi sql --async: Prefer: respond-async (tools/biruns.py says the outcome)
        return ctx["runs"].asked(a, ctx, "/api/v1/bi/sql", {"model": a.model, "sql": text, "format": "json"})
    api = ctx["Api"].from_args(a)
    if fmt in ("csv", "jsonl"):                                            # the server's own file: CSV, or JSON Lines (a row a line)
        _, raw = api.request("POST", "/api/v1/bi/sql", {"model": a.model, "sql": text, "format": fmt})
        sys.stdout.write(raw.decode("utf-8"))
        return 0
    r = api.json("POST", "/api/v1/bi/sql", {"model": a.model, "sql": text, "format": "json"})
    if a.json or a.format == "json":
        ctx["say_json"](r)
        return 0
    if "plan" in r and "rows" not in r:
        print(r.get("text") or json.dumps(r.get("plan"), indent=2))
        return 0
    cols = [c["name"] for c in r.get("columns", [])]
    rows = r.get("rows", [])
    widths = [max([len(c)] + [len(fmt_cell(row[i])) for row in rows]) for i, c in enumerate(cols)]
    print("  ".join(c.ljust(w) for c, w in zip(cols, widths)))
    print("  ".join("-" * w for w in widths))
    for row in rows:
        print("  ".join(fmt_cell(v).ljust(w) for v, w in zip(row, widths)))
    meta = r.get("meta", {})
    print(f"{len(rows)} row(s)" + (f"  shape {meta.get('shape')}" if meta.get("shape") else "") + (f"  plan {meta.get('plan')}" if meta.get("plan") else "")
          + ("  (cached)" if meta.get("cached") else ""))
    for w in meta.get("warnings", []):
        print(f"warning: {w}")
    return 0


def fmt_cell(v) -> str:
    if v is None:
        return ""
    if isinstance(v, float):
        return f"{v:,.2f}"
    return str(v)
