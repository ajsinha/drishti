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

"""Statements on the command line (docs/guides/REPORTS.md; docs/guides/CLI_GUIDE.md "bi statement"): a report's statement pages made for the
token's user, as the BI Studio's Paper tab previews them and a subscription mails them. Loaded by drishti.py; the server makes everything, with your own
masks and row rules.

  bi statement preview REPORT|--file F [--page P] [--date D] [--param k=v]   the pages: count, paper, period, and each page's header and footer
  bi statement file REPORT|--file F [--format pdf|xlsx|jsonl] [-o OUT] ...    the PDF (page-exact), the Excel workbook (set to print on the paper)
                                                                               or the statement's rows as JSON Lines (one a line)

Both say `cut: <table>: The first 10,000 rows only ...` for each table that stops at drishti.distribution.statements.max-rows.
"""
import argparse
import json
import pathlib
import urllib.parse


def register(bi, srv, ctx) -> None:
    """Adds `bi statement` to the `bi` subcommands; ctx holds Api, CliError and say_json of drishti.py."""
    st = bi.add_parser("statement", help="bi statement preview|file: a statement made for you", description="Statements (paginated reports), made as you.")
    sa = st.add_subparsers(dest="action", metavar="ACTION", required=True)
    for act, what, examples in (
            ("preview", "the statement's pages as the server lays them out: the page count, the paper, the period, each page's header and footer",
             "  drishti.py bi statement preview file:report:monthly-statement --date 2026-10-01\n"
             "  drishti.py bi statement preview --file monthly-statement.report.yaml --param breakdown=trade.PORTFOLIO"),
            ("file", "the statement as a PDF (page-exact) or an Excel workbook (set to print on the statement's paper)",
             "  drishti.py bi statement file file:report:monthly-statement --date 2026-10-01 -o september.pdf\n"
             "  drishti.py bi statement file file:report:monthly-statement --format xlsx -o september.xlsx")):
        p = sa.add_parser(act, help=f"bi statement {act}", description=f"bi statement {act}: {what}", parents=[srv], epilog="examples:\n" + examples,
                          formatter_class=argparse.RawDescriptionHelpFormatter)
        p.add_argument("report", nargs="?", help="the report's id (file:report:<name> for a shipped one)")
        p.add_argument("--file", help="a draft report's YAML instead of a stored report (needs bi-author)")
        p.add_argument("--page", default="", help="one statement page (default: every statement page)")
        p.add_argument("--date", default="", help="the statement's date, yyyy-mm-dd: its period is worked out from it (default: the latest business date)")
        p.add_argument("--param", action="append", default=[], metavar="NAME=VALUE", help="a parameter of the report's model (a field parameter's choice, a what-if value)")
        if act == "file":
            p.add_argument("--format", choices=("pdf", "xlsx", "jsonl"), default="pdf")
            p.add_argument("-o", "--out", help="where to write it (default: the name the server gives it)")
        p.set_defaults(func=(lambda a, extra: preview(a, ctx)) if act == "preview" else (lambda a, extra: make(a, ctx)))


def body_of(a, ctx) -> dict:
    if not a.report and not a.file:
        raise ctx["CliError"]("name the report, or give its YAML with --file", 2)
    body = {"page": a.page, "date": a.date}
    if a.file:
        body["yaml"] = pathlib.Path(a.file).read_text(encoding="utf-8")
    else:
        body["report"] = a.report
    params = {}
    for kv in a.param:
        if "=" not in kv:
            raise ctx["CliError"](f"--param {kv}: write NAME=VALUE", 2)
        k, v = kv.split("=", 1)
        try:
            params[k.strip()] = json.loads(v) if v.strip()[:1] in "-0123456789tfn[{\"" else v
        except ValueError:
            params[k.strip()] = v
    if params:
        body["state"] = {"params": params}
    return body


def plural(n, word: str) -> str:
    """``1 page``, ``2 pages``, ``1,234 rows``: a count and its noun, singular for one (QA3B-09)."""
    return f"{n:,} {word}" + ("" if n == 1 else "s")


def preview(a, ctx) -> int:
    r = ctx["Api"].from_args(a).json("POST", "/api/v1/bi/reports/statement/preview", body_of(a, ctx))
    if a.json:
        ctx["say_json"](r)
        return 0
    paper = r.get("paper") or {}
    print(f"{r.get('title')}: {plural(r.get('pageCount') or 0, 'page')}, {paper.get('size')} {paper.get('orientation')}" + (f", {r['period']}" if r.get("period") else "")
          + f", {plural(r.get('rows') or 0, 'row')}")
    if r.get("hint"):                                                 # no data is held for the dates the statement reads, as the designer says (QA3B-09)
        print(f"  hint: {r['hint']}")
    for c in r.get("cut") or []:                                      # a table that stops at max-rows, as the PDF's note says (QA2-204)
        print(f"  cut: {c}")
    for p in r.get("pages", []):
        head = [o["text"] for o in p.get("ops", []) if o.get("t") == "text" and o.get("role") == "header"]
        foot = [o["text"] for o in p.get("ops", []) if o.get("t") == "text" and o.get("role") == "footer"]
        cells = sum(1 for o in p.get("ops", []) if o.get("t") == "text" and o.get("role") == "cell")
        print(f"  page {p.get('number')}: {' | '.join(head)}  ...  {' | '.join(foot)}  ({plural(cells, 'cell')})")
    return 0


def make(a, ctx) -> int:
    body = body_of(a, ctx)
    body["format"] = a.format
    api = ctx["Api"].from_args(a)
    _, raw = api.request("POST", "/api/v1/bi/reports/statement/file", body, extra={"Accept": "application/pdf, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, application/jsonl, application/json"})
    out = pathlib.Path(a.out) if a.out else pathlib.Path(("statement." + a.format))
    out.write_bytes(raw)
    print(f"wrote {out} ({len(raw):,} bytes)")
    headers = getattr(api, "headers", None)
    for h in (headers.get_all("X-Statement-Hint") or []) if headers is not None else []:
        print(f"  hint: {urllib.parse.unquote_plus(h)}")                # no data for the dates the statement reads (QA3B-09)
    for c in (headers.get_all("X-Statement-Cut") or []) if headers is not None else []:
        print(f"  cut: {urllib.parse.unquote_plus(c)}")                # a table that stops at max-rows, as the file's note says (QA2-204)
    return 0
