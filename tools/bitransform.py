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

"""Python data prep on the command line (docs/guides/CLI_GUIDE.md "bi transform"; docs/guides/PYTHON_BI.md section 17): what the Transform editor's inputs
and snippets picker do. Loaded by drishti.py.

  bi transform input FILE [--input NAME] [--limit N] [--as-of DATE]   each input of a Python dataset (FILE.dataset.yaml) as you: rows, masked columns,
                                                                    and a short profile of every column (asked of the server, POST /bi/python/input)
  bi transform snippets [--json]                                    the data-prep snippets library (drishti-console/config/prep-snippets.yaml)
  bi transform snippet ID [NAME=VALUE ...] [--inputs a,b]           the step a snippet makes, each value written as a literal (paste it into a step)

The steps themselves run where every Python item runs, in the console's sandbox (the editor's preview), or on the server when the dataset is read.
"""
import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SNIPPETS = ROOT / "drishti-console" / "config" / "prep-snippets.yaml"


def register(bi, srv, ctx) -> None:
    """Adds `bi transform` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    tr = bi.add_parser("transform", help="bi transform input|snippets|snippet: a Python Transform's inputs as you, and the snippets library",
                       description="Python data prep: a Transform's inputs as you see them, and the snippets the Transform editor offers.")
    ta = tr.add_subparsers(dest="action", metavar="ACTION", required=True)
    ip = ta.add_parser("input", help="each input of a Python dataset as you: rows, masked columns, a profile", parents=[srv],
                       formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi transform input docs/tutorials/data/fx-book-value.dataset.yaml\n"
                              "  drishti.py bi transform input my.dataset.yaml --input trades --limit 50 --as-of 2026-10-08 --json")
    ip.add_argument("file", help="a Python dataset's YAML (source: {python, inputs})")
    ip.add_argument("--input", help="only this input")
    ip.add_argument("--limit", type=int, default=1000, help="rows of each input (default 1000; the server caps it at drishti.bi.transform.max-input-rows)")
    ip.add_argument("--as-of", dest="as_of", help="the business date (default: the latest)")
    ip.add_argument("--rows", type=int, default=5, help="rows to print (default 5)")
    ip.set_defaults(func=lambda a, extra: transform_input(a, ctx))
    ls = ta.add_parser("snippets", help="the data-prep snippets library", formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi transform snippets\n  drishti.py bi transform snippets --json")
    ls.add_argument("--json", action="store_true")
    ls.set_defaults(func=lambda a, extra: snippets(a, ctx))
    sn = ta.add_parser("snippet", help="the step a snippet makes with these values", formatter_class=argparse.RawDescriptionHelpFormatter,
                       epilog="examples:\n  drishti.py bi transform snippet type-fix-number column=NOTIONAL\n"
                              "  drishti.py bi transform snippet asof-fx amount=NOTIONAL currency=CURRENCY date=TRADE_DATE fx=rates --inputs trades,rates\n"
                              "  drishti.py bi transform snippet filter-values column=PORTFOLIO values=BOOK-FX-1,BOOK-FX-2")
    sn.add_argument("id")
    sn.add_argument("values", nargs="*", help="NAME=VALUE (a list: comma-separated; a mapping: from:to,from:to; true or false)")
    sn.add_argument("--inputs", default="", help="the Transform's input names, comma-separated (an input parameter must be one of them)")
    sn.set_defaults(func=lambda a, extra: snippet(a, ctx))


def _library(ctx):
    sys.path.insert(0, str(ROOT / "drishti-console"))
    try:
        from core.prep_snippets import SnippetError, SnippetLibrary
    except ImportError as e:
        raise ctx["CliError"](f"the snippets library needs PyYAML (pip install pyyaml): {e}") from None
    return SnippetLibrary([SNIPPETS]), SnippetError


def snippets(a, ctx) -> int:
    lib, _ = _library(ctx)
    listed = lib.list()
    if a.json:
        ctx["say_json"](listed)
        return 0
    group = None
    for s in listed:
        if s["group"] != group:
            group = s["group"]
            print(f"\n{group}")
        params = ", ".join(p["name"] + ("" if "default" in p else "*") for p in s["params"])
        print(f"  {s['id']:<20} {s['title']}  ({params})")
    print("\n* has no default: give it as NAME=VALUE to bi transform snippet")
    return 0


def _value(p: dict, text: str):
    kind = p["kind"]
    if kind in ("columns", "values"):
        return [x.strip() for x in text.split(",") if x.strip()]
    if kind == "mapping":
        return dict(x.split(":", 1) for x in text.split(",") if ":" in x)
    if kind == "bool":
        return text.strip().lower() in ("true", "yes", "1", "on")
    if kind == "number":
        return float(text)
    return text


def snippet(a, ctx) -> int:
    lib, SnippetError = _library(ctx)
    try:
        s = lib.get(a.id)
        kinds = {p["name"]: p for p in s.get("params") or []}
        values = {}
        for nv in a.values:
            name, eq, text = nv.partition("=")
            if not eq or name not in kinds:
                raise SnippetError(f"'{nv}' is not NAME=VALUE for a parameter of {a.id} ({', '.join(kinds)})")
            values[name] = _value(kinds[name], text)
        step = lib.render(a.id, values, [x.strip() for x in a.inputs.split(",") if x.strip()])
    except (SnippetError, ValueError) as e:
        raise ctx["CliError"](str(e)) from None
    print(f"# step: {step['label']}")
    print(step["code"])
    return 0


def _profile(columns: list[dict], rows: list[list]) -> list[str]:
    out = []
    for i, c in enumerate(columns):
        values = [r[i] for r in rows]
        present = [v for v in values if v is not None]
        nums = [v for v in present if isinstance(v, (int, float)) and not isinstance(v, bool)]
        span = f"{min(nums):,.6g} .. {max(nums):,.6g}" if nums else (f"{min(map(str, present))} .. {max(map(str, present))}" if present else "")
        out.append(f"  {c['name']:<28} {c.get('type') or '':<9} empty {len(values) - len(present):>5}  distinct {len(set(map(str, present))):>6}  {span}")
    return out


def transform_input(a, ctx) -> int:
    try:
        import yaml
    except ImportError:
        raise ctx["CliError"]("reading the dataset needs PyYAML (pip install pyyaml)") from None
    try:
        doc = yaml.safe_load(pathlib.Path(a.file).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as e:
        raise ctx["CliError"](f"cannot read {a.file}: {e}") from None
    inputs = ((doc.get("source") or {}).get("inputs") or {}) if isinstance(doc, dict) else {}
    if not inputs:
        raise ctx["CliError"](f"{a.file} is not a Python dataset: it has no source.inputs")
    if a.input and a.input not in inputs:
        raise ctx["CliError"](f"no input '{a.input}' in {a.file} (it has {', '.join(inputs)})")
    api = ctx["Api"].from_args(a)
    answers = {}
    for name, spec in inputs.items():
        if a.input and name != a.input:
            continue
        body = {"input": spec, "limit": a.limit}
        if a.as_of:
            body["asOf"] = a.as_of
        answers[name] = api.json("POST", "/api/v1/bi/python/input", body)
    if a.json:
        ctx["say_json"](answers)
        return 0
    for name, r in answers.items():
        masked = r.get("masked") or []
        print(f"{name}: {r.get('rowCount', 0):,} rows of {r.get('model')} as of {r.get('asOf')}" + (f"; masked for you: {', '.join(masked)}" if masked else ""))
        print("\n".join(_profile(r.get("columns") or [], r.get("rows") or [])))
        for row in (r.get("rows") or [])[:max(0, a.rows)]:
            print("    " + " | ".join("" if v is None else str(v) for v in row))
        if len(answers) > 1:
            print()
    return 0
