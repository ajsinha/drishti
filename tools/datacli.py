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

"""`drishti.py data get ...`: the Data view (DATA) from the command line (docs/guides/DATA_GUIDE.md "The Data view"; the API:
/api/v1/data), as the token's user sees it: the datasets you may download (no dataset named), a selection's row count and read-back
(--count), or a dataset's rows on a date with the chosen columns and filter lines, streamed to a .jsonl.gz file and never held whole.
Exit codes: 0, 1 (the server said no), 2 (usage)."""
from __future__ import annotations

import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

EPILOG = ("examples:\n  drishti.py data get                                  list the datasets you may download\n"
          "  drishti.py data get trade-valuation --date 2026-09-30 \\\n    --columns TRADE_ID,PRODUCT_TYPE,DESK,MTM --where \"DESK = DESK-COMM\" \\\n"
          "    --where \"PRODUCT_TYPE in ComSwap,ComFut\" --where \"MTM > 1000000\" -o trade-valuation_2026-09-30.jsonl.gz\n"
          "  drishti.py data get trade-valuation --date 2026-09-30 --where \"MTM between 0 and 5000\" --count\n"
          "Operators: = (equals), != (not equals), in (one of: a,b,c), contains, starts (starts with), >, <, between (a and b), is empty,\n"
          "is not empty; the --where lines are joined by AND. Your entitlements and masks apply, and the server audits each download.")


def download(api, path: str, out, ApiError, CliError, problem_error=None) -> tuple[int, dict]:
    """A GET whose answer is copied to the binary file ``out`` as it arrives, never held whole: the bytes written and the answer's headers."""
    headers = {"Accept": "application/gzip, application/json;q=0.5"}
    if api.token:
        headers["Authorization"] = "Bearer " + api.token
    elif api.user:
        headers["X-Drishti-User"] = api.user
    try:
        with urllib.request.urlopen(urllib.request.Request(api.base + path, headers=headers), timeout=api.timeout) as r:   # noqa: S310
            n = 0
            for chunk in iter(lambda: r.read(1 << 16), b""):
                out.write(chunk)
                n += len(chunk)
            return n, dict(r.headers)
    except urllib.error.HTTPError as e:
        raw = e.read()
        if problem_error is not None:                    # the server's words, a busy refusal's Retry-After (busy protection)
            raise problem_error(e.code, raw, e.headers, str(e.reason)) from None
        try:
            j = json.loads(raw)
        except ValueError:
            j = {}
        raise ApiError(e.code, j.get("code"), j.get("detail") or raw[:200].decode("utf-8", "replace") or str(e.reason)) from None
    except urllib.error.URLError as e:
        raise CliError(f"cannot reach {api.base}: {e.reason}; is the server running? (--server / DRISHTI_SERVER)", 1) from None


def register(d, add, srv_d, ctx: dict) -> None:
    """Adds ``data get`` to the ``data`` group."""
    Api, ApiError, CliError, say_json, load_date, q = (ctx[k] for k in ("Api", "ApiError", "CliError", "say_json", "load_date", "q"))
    problem_error, busy_wait = ctx.get("problem_error"), ctx.get("busy_wait")

    def fetch(api, path: str, out) -> tuple[int, dict]:
        """The download; with --wait a busy refusal (nothing written yet) is waited for and asked again, three times at most."""
        tries = 0
        while True:
            try:
                return download(api, path, out, ApiError, CliError, problem_error)
            except ApiError as e:
                if busy_wait is None or not busy_wait(api, e, tries):
                    raise
                tries += 1

    def listing(api, a) -> int:
        r = api.json("GET", "/api/v1/data")
        if a.json:
            say_json(r)
            return 0
        print(f"{'dataset':<26}{'pack':<20}{'format':<12}{'dates':>6}  held")
        for x in r.get("datasets", []):
            held = "not dated" if not x.get("dated") else f"{x.get('first')} to {x.get('last')}" if x.get("first") else "-"
            mark = ("  internal only" + (" (refused here)" if x.get("refused") else "")) if x.get("internalOnly") else ""
            print(f"{x['kind']:<26}{x.get('pack', ''):<20}{x.get('format', ''):<12}{x.get('dates', 0):>6}  {held}{mark}")
        return 0

    def get(a, extra) -> int:
        api = Api.from_args(a)
        if not a.dataset:
            return listing(api, a)
        date = a.date and load_date(a.date)
        params = [(k, v) for k, v in (("date", date), ("columns", a.columns)) if v] + [("where", w) for w in a.where or []]
        query = ("?" + urllib.parse.urlencode(params)) if params else ""
        base = f"/api/v1/data/{q(a.dataset)}"
        if a.count:
            r = api.json("GET", base + "/count" + query)
            if a.json:
                say_json(r)
                return 0
            least = "" if r.get("complete") else "at least "
            print(r.get("words", ""))
            print(r.get("sql", ""))
            print(f"rows {least}{r.get('rows', 0):,} of {least}{r.get('total', 0):,}; about {r.get('gzipBytes', 0):,} bytes as .jsonl.gz; "
                  + ("pushed down to the connector" if r.get("pushdown") else "filtered while streaming"))
            return 0
        out = a.output or f"{a.dataset}_{date or 'latest'}.jsonl.gz"
        if out == "-":
            fetch(api, base + "/rows" + query, sys.stdout.buffer)
            return 0
        part = pathlib.Path(out + ".part")
        try:
            with open(part, "wb") as f:
                n, headers = fetch(api, base + "/rows" + query, f)
            part.replace(out)
        finally:
            part.unlink(missing_ok=True)
        print(f"{out}: {n:,} bytes. {headers.get('X-Drishti-Selection') or ''}".rstrip(), file=sys.stderr)
        return 0

    p = add(d, "get", get, "data get: a dataset's rows on a date, with chosen columns and filter lines, streamed to a .jsonl.gz file "
            "(DATA; GET /api/v1/data/{dataset}/rows)", [srv_d], EPILOG)
    p.add_argument("dataset", nargs="?", help="the dataset (its kind, e.g. trade-valuation); none lists the datasets")
    p.add_argument("--date", help="the business date, yyyy-MM-dd (or today, yesterday); default the dataset's latest")
    p.add_argument("--columns", help="the columns, comma-separated (default every column you may see)")
    p.add_argument("--where", action="append", help="a filter line, attribute operator value (repeat it: the lines are joined by AND)")
    p.add_argument("-o", "--output", help="the file to write (default <dataset>_<date>.jsonl.gz; - for standard output)")
    p.add_argument("--wait", dest="wait_busy", action="store_true", help="when the server is busy (503 DRS-9503, busy protection), wait its "
                   "Retry-After and try again by itself, three times at most (DRISHTI_WAIT_BUSY=1 does the same for every command)")
    p.add_argument("--count", action="store_true", help="show the row count and the selection in words and as a SQL-like line; download nothing")
