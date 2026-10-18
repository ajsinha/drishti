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

"""The BI languages on the command line (docs/guides/CLI_GUIDE.md "bi expr, bi migrate, bi sql"): the Drishti expression language (DEL) and Drishti SQL
over REST, as the token's user. Loaded by drishti.py; every command talks to the Java server, which owns the one parser of each language.

  bi expr functions [--scope row|rule|aggregate]       the function catalogue
  bi expr validate EXPR [--scope S] [--model M] [--alias A]   check one expression (exit 1 on an error)
  bi expr convert EXPR                                 the DEL v2 form of an old-syntax expression
  bi migrate --expressions FILE... [--write] [--check] convert every expression of BI item files (comments kept); --check exits 1 when a file would change
  bi migrate --expressions [--dry-run]                 the same for the server's STORED items (bi-admin): audited, the text before kept in each item's history
  bi migrate --models DIR [--dry-run] [--out D] [--keep-old] [--report F]   rachana: 1 datasets of a BI folder to datasets and models, reports rebound
  bi migrate --models [--dry-run]                      the same for the server's stored items (bi-admin)
  bi sql "SELECT ..." --model M [--explain] [--format json|csv]   run Drishti SQL over a model (wave A)
"""
import argparse
import json
import pathlib
import sys


def register(bi, srv, ctx) -> None:
    """Adds `bi expr`, `bi migrate` and `bi sql` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    ex = bi.add_parser("expr", help="bi expr functions|validate|convert: the Drishti expression language (DEL)",
                       description="The Drishti expression language (DEL v2, Python-like): the function catalogue, checking one expression, converting the old syntax.")
    ea = ex.add_subparsers(dest="action", metavar="ACTION", required=True)
    f = ea.add_parser("functions", help="list the DEL functions", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
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
    c = ea.add_parser("convert", help="the DEL v2 form of an old-syntax expression", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi expr convert \"[MTM] / [Trades]\"")
    c.add_argument("expr")
    c.set_defaults(func=lambda a, extra: convert(a, ctx))

    m = bi.add_parser("migrate", help="bi migrate --expressions [FILE...]: convert BI items to DEL v2 (comments kept)", parents=[srv],
                      description="Converts every expression of BI items from the old syntax to DEL v2 with the server's converter: the old parser's tree "
                                  "printed as DEL v2 and checked to compile to the same SQL. Comments, key order and layout are kept; `expressions: 2` is set. "
                                  "With FILEs it converts item files (without --write it prints what would change). Without FILEs it converts the server's "
                                  "STORED items (drafts, proposals and the published text; bi-admin): each change is audited and the text before stays in the "
                                  "item's history; an item that cannot be converted keeps its text and is listed in Admin, BI settings. The server also runs this "
                                  "once at start (drishti.bi.migrate.on-start).",
                      formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi migrate --expressions config/bi/datasets/*.yaml\n  drishti.py bi migrate --expressions config/bi --write\n"
                             "  drishti.py bi migrate --expressions config/bi --check     # CI: exit 1 when a file is still in the old syntax\n"
                             "  drishti.py bi migrate --expressions --dry-run            # the server's stored items: what would change\n"
                             "  drishti.py bi migrate --expressions                      # ... and convert them (bi-admin)\n"
                             "  drishti.py bi migrate --models config/bi --dry-run --report /tmp/plan.md   # datasets to models: the plan\n"
                             "  drishti.py bi migrate --models config/bi                  # write it (old dataset files removed; --keep-old keeps them)\n"
                             "  drishti.py bi migrate --models --dry-run                  # the server's stored items")
    m.add_argument("files", nargs="*", metavar="FILE", help="item files or folders (searched for *.dataset|model|report|board|notebook.yaml); none: the stored items")
    what = m.add_mutually_exclusive_group(required=True)
    what.add_argument("--expressions", action="store_true", help="convert the expressions to DEL v2")
    what.add_argument("--models", action="store_true", help="convert rachana: 1 datasets to datasets and models, and rebind their reports (BI_MODEL_SPEC.md 8.3)")
    m.add_argument("--write", action="store_true", help="FILEs: write the converted files")
    m.add_argument("--check", action="store_true", help="FILEs: change nothing; exit 1 when a file would change or cannot be converted")
    m.add_argument("--dry-run", action="store_true", help="change nothing, show what would change (stored items, and --models; for --expressions FILEs it is the default)")
    m.add_argument("--out", metavar="DIR", help="--models: write the converted files under DIR instead of in place (the old files are then left alone)")
    m.add_argument("--keep-old", action="store_true", help="--models in place: keep the old dataset files (they are removed by default)")
    m.add_argument("--report", metavar="FILE", help="--models: write the plan report (Markdown) to FILE")
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
    s.add_argument("--format", choices=["table", "json", "csv"], default="table")
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


def convert(a, ctx) -> int:
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/expr/convert", {"expr": a.expr})
    if a.json:
        ctx["say_json"](r)
        return 0 if not r.get("problems") else 1
    if r.get("problems"):
        print(problems_text(r["problems"]), file=sys.stderr)
        return 1
    print(r.get("converted"))
    return 0


ITEM_SUFFIXES = (".dataset.yaml", ".model.yaml", ".report.yaml", ".board.yaml", ".notebook.yaml")


def item_files(paths, ctx) -> list:
    out = []
    for p in map(pathlib.Path, paths):
        if p.is_dir():
            out.extend(sorted(x for x in p.rglob("*.yaml") if x.name.endswith(ITEM_SUFFIXES)))
        elif p.exists():
            out.append(p)
        else:
            raise ctx["CliError"](f"{p}: no such file", 2)
    return out


def migrate(a, ctx) -> int:
    api = ctx["Api"].from_args(a)
    if a.models:
        return migrate_models(a, api, ctx)
    if not a.files:
        return migrate_stored(a, api, ctx)
    if a.dry_run:
        a.write = False
    files = item_files(a.files, ctx)
    changed = failed = 0
    report = []
    for f in files:
        r = api.json("POST", "/api/v1/bi/expr/convert", {"yaml": f.read_text(encoding="utf-8")})
        n = len(r.get("changes", []))
        rewrite = bool(r.get("changed"))                                    # an expression converted, or expressions: 2 set
        bad = r.get("problems", [])
        report.append({"file": str(f), "expressions": r.get("expressions", 0), "changes": r.get("changes", []), "problems": bad})
        if bad:
            failed += 1
        if rewrite:
            changed += 1
            if a.write and not a.check:
                f.write_text(r["yaml"], encoding="utf-8")
        if not a.json:
            state = ("converted" if a.write and not a.check else "would change") if rewrite else "DEL v2 already" if r.get("expressions") else "no expressions"
            print(f"{f}: {state} ({n} of {r.get('expressions', 0)} expressions)")
            for c in r.get("changes", []):
                print(f"    {c['path']}: {c['from']}\n      -> {c['to']}")
            for p in bad:
                print(f"    {p['path']}: NOT CONVERTED: {p.get('problem')}")
    if a.json:
        ctx["say_json"]({"files": report, "changed": changed, "failed": failed, "written": bool(a.write and not a.check)})
    elif files:
        print(f"{len(files)} file(s), {changed} with old-syntax expressions" + (", written" if a.write and not a.check and changed else "") + (f", {failed} with problems" if failed else ""))
    if failed or (a.check and changed):
        return 1
    return 0


MODEL_DIRS = ("datasets", "models", "reports", "boards", "notebooks", "python")


def migrate_models(a, api, ctx) -> int:
    """--models: FILE is a BI folder (config/bi) converted by the server's converter; without it, the server's stored items (bi-admin)."""
    if a.write or a.check:
        raise ctx["CliError"]("--write and --check are for --expressions; --models writes unless --dry-run", 2)
    if not a.files:
        r = api.json("POST", "/api/v1/bi/migrate/models", {"dryRun": bool(a.dry_run)})
        if a.report:
            pathlib.Path(a.report).write_text(r.get("report", ""), encoding="utf-8")
        if a.json:
            ctx["say_json"](r)
            return 1 if r.get("problems") else 0
        for i in r.get("items", []):
            print(f"{i['kind']} '{i['name']}' ({i['key']}): {i['state']}" + (f" -> {i['became']}" if i.get("became") else "") + (f": {i['problem']}" if i.get("problem") else ""))
            for line in i.get("manual", []):
                print(f"    manual: {line}")
        done = r.get("wouldConvert" if a.dry_run else "converted", 0)
        print(f"{r.get('scanned', 0)} stored item(s) scanned, {done} " + ("would be converted" if a.dry_run else "converted")
              + (f", {r['problems']} cannot be converted (they keep their text; Admin, BI settings lists them)" if r.get("problems") else ""))
        return 1 if r.get("problems") else 0
    if len(a.files) != 1 or not pathlib.Path(a.files[0]).is_dir():
        raise ctx["CliError"]("--models takes one BI folder (config/bi): its datasets, reports, boards, notebooks and python items are read together", 2)
    root = pathlib.Path(a.files[0])
    files = []
    for d in MODEL_DIRS:
        for f in sorted((root / d).glob("*.yaml")) if (root / d).is_dir() else []:
            files.append({"path": f"{d}/{f.name}", "yaml": f.read_text(encoding="utf-8")})
    r = api.json("POST", "/api/v1/bi/migrate/models/convert", {"files": files})
    if a.report:
        pathlib.Path(a.report).write_text(r.get("report", ""), encoding="utf-8")
    out = pathlib.Path(a.out) if a.out else root
    removed = 0
    if not a.dry_run:
        for path, text in r.get("writes", {}).items():
            target = out / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        if not a.out and not a.keep_old:
            for path in r.get("removes", []):
                (root / path).unlink(missing_ok=True)
                removed += 1
    if a.json:
        ctx["say_json"](r)
        return 1 if r.get("problems") else 0
    for i in r.get("items", []):
        if i["state"] == "unchanged":
            continue
        print(f"{i['key']}: {i['state']}" + (f" -> {i['became']}" if i.get("became") else "") + (f": {i['problem']}" if i.get("problem") else ""))
        for o in i.get("outputs", []):
            print(f"    writes {o['path']}")
        for line in i.get("manual", []):
            print(f"    manual: {line}")
    verb = "would write" if a.dry_run else "wrote"
    print(f"{len(files)} file(s) read, {r.get('converted', 0)} converted, {r.get('problems', 0)} refused; {verb} {len(r.get('writes', {}))} file(s)"
          + (f", removed {removed} old dataset file(s)" if removed else "") + (f"; the report is {a.report}" if a.report else ""))
    return 1 if r.get("problems") else 0


def migrate_stored(a, api, ctx) -> int:
    """The stored items, on the server (bi-admin): a dry run lists, a run converts; exit 1 when an item cannot be converted."""
    if a.write or a.check:
        raise ctx["CliError"]("--write and --check are for FILEs; for stored items use --dry-run (or nothing, to convert)", 2)
    r = api.json("POST", "/api/v1/bi/migrate/expressions", {"dryRun": bool(a.dry_run)})
    if a.json:
        ctx["say_json"](r)
        return 1 if r.get("problems") else 0
    for i in r.get("items", []):
        print(f"{i['kind']} '{i['title']}' ({i['id']}, {i['status']}): {i['state']}" + (f"; the text before is saved state {i['before']}" if i.get("before") else ""))
        for c in i.get("draft", []):
            print(f"    draft {c['path']}: {c['from']}\n      -> {c['to']}")
        for c in i.get("published", []):
            print(f"    published {c['path']}: {c['from']}\n      -> {c['to']}")
        for p in i.get("problems", []):
            print(f"    {p['path']}: NOT CONVERTED: {p.get('problem')}")
    done = r.get("wouldConvert" if a.dry_run else "converted", 0)
    print(f"{r.get('scanned', 0)} stored item(s) scanned, {done} " + ("would be converted" if a.dry_run else "converted")
          + (f", {r['problems']} cannot be converted (they keep their text; Admin, BI settings lists them)" if r.get("problems") else ""))
    return 1 if r.get("problems") else 0


# ---- sql -------------------------------------------------------------------------------------------------------------

def sql(a, ctx) -> int:
    text = pathlib.Path(a.file).read_text(encoding="utf-8") if a.file else a.sql
    if not text:
        raise ctx["CliError"]("bi sql needs a statement (or --file)", 2)
    if a.explain and not text.lstrip().upper().startswith("EXPLAIN"):
        text = "EXPLAIN " + text
    fmt = "json" if a.format == "table" else a.format
    api = ctx["Api"].from_args(a)
    if fmt == "csv":
        _, raw = api.request("POST", "/api/v1/bi/sql", {"model": a.model, "sql": text, "format": "csv"})
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
