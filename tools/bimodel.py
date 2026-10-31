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

"""Models and model queries on the command line (docs/guides/CLI_GUIDE.md "bi model, bi mq"): what the model designer and a report's visuals do,
over REST as the token's user. Loaded by drishti.py; the Java server checks every model and answers every query (with the reader's row rules and masks).

  bi model list [--kind model|implicit|compat]          the models and datasets you may read
  bi model describe REF                                 a model as you see it: datasets, fields (with their properties), measures, perspectives
  bi model check FILE                                   the server's Check of a model or dataset YAML (exit 1 on an error)
  bi model consumers REF                                what reads the model: reports, boards, alerts, notebooks, datasets
  bi mq FILE | --select F,... --measures M,... --model REF   ask one model query (YAML or JSON, or built from the options)
"""
import argparse
import json
import pathlib


def register(bi, srv, ctx) -> None:
    """Adds `bi model` and `bi mq` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    md = bi.add_parser("model", help="bi model list|describe|check|consumers: the models you may read", description="Models of the BI model layer, as you see them.")
    ma = md.add_subparsers(dest="action", metavar="ACTION", required=True)
    ls = ma.add_parser("list", help="the models and datasets you may read", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi model list\n  drishti.py bi model list --kind model --json")
    ls.add_argument("--kind", choices=["model", "implicit", "compat"], help="only models (model), datasets read as their implicit model, or version 1 datasets")
    ls.set_defaults(func=lambda a, extra: model_list(a, ctx))
    ds = ma.add_parser("describe", help="a model as you see it: fields with their properties, measures, perspectives", parents=[srv],
                       formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi model describe trading-risk@1\n  drishti.py bi model describe dataset:pack.trading.trade --json")
    ds.add_argument("ref", help="name@major, a model item id, or dataset:<name>")
    ds.set_defaults(func=lambda a, extra: model_describe(a, ctx))
    ck = ma.add_parser("check", help="the server's Check of a model or dataset YAML (exit 1 on an error)", parents=[srv],
                       formatter_class=argparse.RawDescriptionHelpFormatter, epilog="examples:\n  drishti.py bi model check my.model.yaml")
    ck.add_argument("file")
    ck.set_defaults(func=lambda a, extra: model_check(a, ctx))
    cs = ma.add_parser("consumers", help="what reads the model", parents=[srv], formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi model consumers trading-risk@1")
    cs.add_argument("ref")
    cs.set_defaults(func=lambda a, extra: model_consumers(a, ctx))

    q = bi.add_parser("mq", help="bi mq FILE | --model M --select ... --measures ...: ask one model query, as you", parents=[srv],
                      description="Asks one model query (mq: 1) of the server, as you: the same request a report visual, an alert or Analyse makes.",
                      formatter_class=argparse.RawDescriptionHelpFormatter,
                      epilog="examples:\n  drishti.py bi mq query.yaml\n  drishti.py bi mq --model trading-risk@1 --select desks.NAME --measures MTM,Trades --sort=-MTM --limit 3\n"
                             "  drishti.py bi mq --model trading-risk@1 --select desks.NAME --define \"Validated MTM=sum(trades.MTM, where=trades.TRADE_STATUS == 'VALIDATED')\" --measures \"Validated MTM\"")
    q.add_argument("file", nargs="?", help="a YAML or JSON model query")
    q.add_argument("--async", dest="as_run", action="store_true", help="ask it as a run (BI_DESIGN.md): the answer if done within --wait, else the run's id")
    q.add_argument("--wait", default="2s", help="with --async: how long to wait inline (default 2s; 0 returns the run at once)")
    q.add_argument("--yes", action="store_true", help="with --async: accept the confirmation of a large run in advance (DRS-9082)")
    q.add_argument("--model", help="the model (name@major) or dataset:<name>")
    q.add_argument("--select", default="", help="fields, comma-separated")
    q.add_argument("--measures", default="", help="measures, comma-separated")
    q.add_argument("--define", action="append", default=[], help="a measure of this query only: \"Name=DEL expression\" (repeatable)")
    q.add_argument("--where", action="append", default=[], help="a filter: field=value (repeatable)")
    q.add_argument("--sort", action="append", default=[], help="sort by a field or measure; a leading '-' sorts descending (write it --sort=-MTM); repeatable")
    q.add_argument("--limit", type=int)
    q.add_argument("--as-of", dest="as_of", help="a business date (YYYY-MM-DD)")
    q.set_defaults(func=lambda a, extra: mq(a, ctx))


def table(cols, rows) -> str:
    def cell(v):
        return "" if v is None else f"{v:,.2f}" if isinstance(v, float) else str(v)
    widths = [max([len(c)] + [len(cell(r[i])) for r in rows]) for i, c in enumerate(cols)]
    lines = ["  ".join(c.ljust(w) for c, w in zip(cols, widths)), "  ".join("-" * w for w in widths)]
    lines += ["  ".join(cell(v).ljust(w) for v, w in zip(r, widths)) for r in rows]
    return "\n".join(lines)


def model_list(a, ctx) -> int:
    r = ctx["Api"].from_args(a).json("GET", "/api/v1/bi/models")
    rows = [m for m in r.get("models", []) if not a.kind or m.get("kind") == a.kind]
    if a.json:
        ctx["say_json"]({"models": rows})
        return 0
    ref = lambda m: m.get("query") or (f"{m.get('name')}@{str(m.get('version') or '1').split('.')[0]}" if m.get("kind") == "model" else m.get("name"))  # noqa: E731
    print(table(["query", "kind", "title", "version", "state"], [[ref(m), m.get("kind"), m.get("title"), m.get("version") or "",
                                                               "draft" if m.get("draft") else m.get("status") or "published"] for m in rows]))
    return 0


def model_describe(a, ctx) -> int:
    from urllib.parse import quote
    r = ctx["Api"].from_args(a).json("GET", "/api/v1/bi/models/" + quote(a.ref, safe="") + "/describe")
    if a.json:
        ctx["say_json"](r)
        return 0
    m = r.get("model") or {}
    ref = a.ref if a.ref.startswith("dataset:") or not m.get("name") else f"{m.get('name')}@{str(m.get('version') or '1').split('.')[0]}"
    print(f"{r.get('title') or m.get('name') or a.ref}  ({ref}" + (", implicit" if m.get("implicit") or a.ref.startswith("dataset:") else "") + ")")
    for d in r.get("datasets", []):
        print(f"\n{d.get('alias')}  {d.get('role')}  {d.get('dataset')} {d.get('version') or ''}")
        for f in d.get("fields", []):
            props = [p for p in (f.get("display") and f"as {f['display']}", f.get("folder") and f"folder {f['folder']}", f.get("format"),
                                 "masked" if f.get("masked") else None) if p]
            print(f"  {f.get('field'):<34} {f.get('type') or '':<9} {f.get('meaning') or f.get('role') or '':<14}" + ", ".join(props))
    print("\nmeasures")
    for x in r.get("measures", []):
        print(f"  {x.get('name'):<34}{x.get('expr') or ('python: ' + x['python'] if x.get('python') else '')}" + ("  (default)" if x.get("implicit") else ""))
    for name, p in (r.get("perspectives") or {}).items():
        print(f"\nperspective {name}: " + ", ".join((p.get("fields") or []) + (p.get("measures") or [])))
    return 0


def model_check(a, ctx) -> int:
    text = pathlib.Path(a.file).read_text(encoding="utf-8")
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/models/validate", {"yaml": text})
    if a.json:
        ctx["say_json"](r)
    else:
        print(("valid" if r.get("valid") else "INVALID") + f"  ({r.get('kind')} {r.get('name') or ''})")
        for p in r.get("problems", []):
            print(f"  {p.get('level'):<8}{p.get('code')}  {p.get('path') or '(item)'}: {p.get('message')}" + (f"\n      fix: {p['fix']}" if p.get("fix") else ""))
        for x in r.get("exposures", []):
            print(f"  safeguard: the role {x.get('role')} sees every row of {x.get('dataset')}")
    return 0 if r.get("valid") else 1


def model_consumers(a, ctx) -> int:
    from urllib.parse import quote
    r = ctx["Api"].from_args(a).json("GET", "/api/v1/bi/models/" + quote(a.ref, safe="") + "/consumers")
    if a.json:
        ctx["say_json"](r)
        return 0
    rows = r.get("consumers", [])
    print(table(["kind", "id", "title"], [[c.get("kind"), c.get("id"), c.get("title") or ""] for c in rows]) if rows else "nothing reads this model yet")
    return 0


def query_of(a, ctx) -> dict:
    if a.file:
        import yaml
        q = yaml.safe_load(pathlib.Path(a.file).read_text(encoding="utf-8"))
        if not isinstance(q, dict):
            raise ctx["CliError"](f"{a.file}: a model query is a mapping (mq: 1, model, select, ...)", 2)
        q = json.loads(json.dumps(q, default=str))         # a date written without quotes (2026-10-16) is read by YAML as a date: send it as text
        if a.model:
            q["model"] = a.model
        return q
    if not a.model:
        raise ctx["CliError"]("bi mq needs a query file, or --model with --select and --measures", 2)
    q = {"mq": 1, "model": a.model, "select": {"fields": [f.strip() for f in a.select.split(",") if f.strip()],
                                                "measures": [m.strip() for m in a.measures.split(",") if m.strip()]}}
    for d in a.define:
        name, _, expr = d.partition("=")
        if not expr:
            raise ctx["CliError"](f"--define takes Name=expression, not {d!r}", 2)
        q.setdefault("define", {})[name.strip()] = expr.strip()
    for w in a.where:
        field, _, value = w.partition("=")
        q.setdefault("where", []).append({"field": field.strip(), "op": "eq", "value": value.strip()})
    for s in a.sort:
        by = s.strip()
        if not by or by == "-":
            raise ctx["CliError"](f"--sort takes a field or measure, '-' in front for descending, not {s!r}", 2)
        q.setdefault("sort", []).append({"by": by[1:].strip(), "desc": True} if by.startswith("-") else {"by": by, "desc": False})
    if a.limit:
        q["limit"] = a.limit
    if a.as_of:
        q["time"] = {"as-of": a.as_of}
    return q


def mq(a, ctx) -> int:
    q = query_of(a, ctx)
    if getattr(a, "as_run", False):                                       # bi mq --async: Prefer: respond-async (tools/biruns.py says the outcome)
        return ctx["runs"].asked(a, ctx, "/api/v1/bi/mq", q)
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/mq", q)
    if a.json:
        ctx["say_json"](r)
        return 0
    print(table([c["name"] for c in r.get("columns", [])], r.get("rows", [])))
    meta = r.get("meta", {})
    print(f"{len(r.get('rows', []))} row(s)" + (f"  as of {meta.get('asOf')}" if meta.get("asOf") else "") + (f"  plan {meta.get('plan')}" if meta.get("plan") else "")
          + (f"  masked {', '.join(meta['masked'])}" if meta.get("masked") else "") + ("  (cached)" if meta.get("cached") else "")
          + (f"  layer {meta['layer']}" if meta.get("layer") and meta.get("layer") != "columns" else "") + (f" (rollup {meta['rollup']})" if meta.get("rollup") else ""))
    if meta.get("sql"):                                                   # options: { explain: true } (BI authors): what ran
        print("sql: " + meta["sql"])
    for w in meta.get("warnings", []):
        print(f"warning: {w}")
    return 0
