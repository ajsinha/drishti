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

"""Independent brute-force checker for Drishti structured search.

Reads the truth (every trade document of a business day) from JSON lines (loader rows or plain docs), generates random
conditions, evaluates them here by an independent implementation of the documented semantics, asks the server, and
compares matched counts, scanned, partial and the ordered result ids.

usage: checker.py --base http://localhost:18982/api/v1 --truth <trade.jsonl> --date 2026-09-30 [--n 300] [--seed 1]
       [--docpath]   (also force the document path by adding a never-true clause on a non-promoted field)
"""
import argparse, json, math, random, sys, urllib.parse, urllib.request, time

PROMOTED = ["tradeId", "productType", "productName", "direction", "currency", "notional", "mtm", "pnl1d", "maturityDate",
            "tradeDate", "book", "desk", "status", "assetClass", "counterparty.id", "counterparty.name", "nettingSet",
            "risk.dv01", "sourceSystem"]
PICK_COLS = ["mtm", "productType", "direction", "currency", "notional", "maturityDate", "book"]
NUM = ["notional", "mtm", "pnl1d", "risk.dv01"]
TEXT = ["productType", "direction", "currency", "book", "desk", "status", "assetClass", "counterparty.id",
        "counterparty.name", "nettingSet", "sourceSystem", "productName"]
DATES = ["maturityDate", "tradeDate"]
import os
NUM = os.environ.get("QA_NUM", ",".join(NUM)).split(",")
TEXT = os.environ.get("QA_TEXT", ",".join(TEXT)).split(",")
DATES = os.environ.get("QA_DATES", ",".join(DATES)).split(",")
ORDERS = os.environ.get("QA_ORDERS", "currency,maturityDate,book,counterparty.name").split(",")


def get(doc, path):
    cur = doc
    for p in path.split("."):
        if isinstance(cur, dict) and p in cur:
            cur = cur[p]
        else:
            return None
    return cur


def num(v):
    if v is None or isinstance(v, (dict, list)):
        return math.nan
    if isinstance(v, bool):
        return 1.0 if v else 0.0
    if isinstance(v, (int, float)):
        return float(v)
    try:
        return float(str(v).strip())
    except ValueError:
        return math.nan


def text(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float) and v == math.floor(v) and abs(v) < 1e15:
        return str(int(v))
    return str(v)


# ---- tiny expression AST: ("cmp", op, path, literal) | ("and", a, b) | ("or", a, b) | ("not", a)
def ev(node, doc):
    t = node[0]
    if t == "and":
        return ev(node[1], doc) and ev(node[2], doc)
    if t == "or":
        return ev(node[1], doc) or ev(node[2], doc)
    if t == "not":
        return not ev(node[1], doc)
    _, op, path, lit = node
    v = get(doc, path)
    if op in ("contains", "startswith"):
        s = text(v).lower()
        return (lit.lower() in s) if op == "contains" else s.startswith(lit.lower())
    if isinstance(lit, str) and op in ("=", "!="):
        eq = text(v).lower() == lit.lower()
        return eq if op == "=" else not eq
    if op in ("=", "!="):
        eq = (v is not None) and num(v) == lit
        return eq if op == "=" else not eq
    if isinstance(lit, str):   # text order (ISO dates)
        if not isinstance(v, str):
            x = num(v)
            y = num(lit)
            if math.isnan(x) or math.isnan(y):
                return False
            return {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y}[op]
        x = num(v); y = num(lit)
        if not (math.isnan(x) or math.isnan(y)):
            return {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y}[op]
        c = (v > lit) - (v < lit)
        return {"<": c < 0, "<=": c <= 0, ">": c > 0, ">=": c >= 0}[op]
    x = num(v)
    if math.isnan(x):
        return False
    return {"<": x < lit, "<=": x <= lit, ">": x > lit, ">=": x >= lit}[op]


def render(node):
    t = node[0]
    if t == "and":
        return f"({render(node[1])} and {render(node[2])})"
    if t == "or":
        return f"({render(node[1])} or {render(node[2])})"
    if t == "not":
        return f"not ({render(node[1])})"
    _, op, path, lit = node
    if isinstance(lit, str):
        return f"{path} {op} '{lit}'"
    return f"{path} {op} {fmtnum(lit)}"


def fmtnum(x):
    if x == int(x) and abs(x) < 1e15:
        x = int(x)
        if x < 0:
            return "-" + fmtnum(-x)
        if x and x % 1_000_000 == 0 and random.random() < .5:
            return f"{x // 1_000_000}m"
        if x and x % 1000 == 0 and random.random() < .5:
            return f"{x // 1000}k"
        return str(x)
    return repr(x) if x >= 0 else "-" + repr(-x)


def leaf(docs, rnd):
    kind = rnd.random()
    d = rnd.choice(docs)
    if kind < .35:
        p = rnd.choice(NUM)
        v = get(d, p)
        if v is None:
            v = rnd.choice([0, 1e6, -5e6])
        v = float(v)
        if rnd.random() < .3:
            v = float(round(v, -3))
        op = rnd.choice(["<", "<=", ">", ">=", "=", "!="])
        return ("cmp", op, p, v)
    if kind < .7:
        p = rnd.choice(TEXT)
        v = get(d, p) or "zzz"
        v = str(v)
        r = rnd.random()
        if r < .3:
            v = v.upper()
        elif r < .5:
            v = v.lower()
        op = rnd.choice(["=", "=", "!=", "contains", "startswith"])
        if op in ("contains", "startswith") and len(v) > 3:
            a = rnd.randrange(0, len(v) - 2) if op == "contains" else 0
            v = v[a:a + rnd.randrange(1, 5)]
        if "'" in v:
            v = v.replace("'", "")
        return ("cmp", op, p, v)
    p = rnd.choice(DATES)
    v = get(d, p) or "2030-01-01"
    op = rnd.choice(["<", "<=", ">", ">=", "=", "!="])
    return ("cmp", op, p, v)


def cond(docs, rnd, depth=0):
    r = rnd.random()
    if depth >= 2 or r < .45:
        return leaf(docs, rnd)
    if r < .7:
        return ("and", cond(docs, rnd, depth + 1), cond(docs, rnd, depth + 1))
    if r < .92:
        return ("or", cond(docs, rnd, depth + 1), cond(docs, rnd, depth + 1))
    return ("not", cond(docs, rnd, depth + 1))


def sortkey_val(v):
    if v is None or isinstance(v, (dict, list)):
        return None
    return v


def cmp(a, b):
    if isinstance(a, (int, float)) and not isinstance(a, bool) and isinstance(b, (int, float)) and not isinstance(b, bool):
        return (a > b) - (a < b)
    x, y = text(a).lower(), text(b).lower()
    return (x > y) - (x < y)


def load(path, date=None):
    docs = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if "doc" in r and "id" in r:
            if date and r.get("date") and r["date"] != date:
                continue
            d = json.loads(r["doc"]) if isinstance(r["doc"], str) else r["doc"]
            docs[r["id"]] = d
        elif "id" in r:
            docs[r["id"]] = r
    return docs


def ask(base, q, as_of):
    url = f"{base}/search?q={urllib.parse.quote(q)}" + (f"&asOf={as_of}" if as_of else "")
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return {"httpError": e.code, "body": e.read().decode()[:500]}


def expected(docs, node, order, desc, limit):
    ids = sorted(i for i, d in docs.items() if node is None or ev(node, d))
    if order:
        keyed = [(i, sortkey_val(get(docs[i], order))) for i in ids]
        import functools

        def c(a, b):
            if a[1] is None or b[1] is None:
                return (1 if a[1] is None else 0) - (1 if b[1] is None else 0)
            r = cmp(a[1], b[1])
            return -r if desc else r
        keyed.sort(key=functools.cmp_to_key(c))
        return ids, keyed[:limit], keyed
    return ids, [(i, None) for i in ids[:limit]], None


def compare_rows(got_rows, exp_page, exp_all, order):
    gids = [r["ref"]["id"] for r in got_rows]
    eids = [i for i, _ in exp_page]
    if gids == eids:
        return None
    if order and len(gids) == len(eids):
        # tolerate ties: the sort keys must agree position by position, and the ids must be a valid choice among ties
        key = {i: k for i, k in exp_all}
        gk = [key.get(i, "MISSING") for i in gids]
        ek = [k for _, k in exp_page]
        if gk == ek:
            return "TIES-ORDER-DIFFERS"
    return f"ids differ: got {gids[:8]} expected {eids[:8]}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--date", default=None)
    ap.add_argument("--asof", default=None)
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--docpath", action="store_true")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    docs = load(a.truth, a.date)
    dl = list(docs.values())
    rnd = random.Random(a.seed)
    random.seed(a.seed)
    stats = {"ok": 0, "ties": 0, "fail": 0, "err": 0}
    t0 = time.time()
    for n in range(a.n):
        node = cond(dl, rnd)
        order = rnd.choice([None, None] + NUM + ORDERS)
        desc = rnd.random() < .5
        limit = rnd.choice([1, 5, 20, 100, 1000])
        cstr = render(node)
        if a.docpath:
            cstr = f"({cstr}) and not (sourceTradeId = 'qa-never')"
        q = f"TRD where {cstr}" + (f" order by {order}" + (" desc" if desc else "") if order else "") + f" limit {limit}"
        ids, page, allk = expected(docs, node, order, desc, limit)
        got = ask(a.base, q, a.asof)
        if "httpError" in got:
            stats["err"] += 1
            print(f"ERR {a.label} #{n} {q}\n   -> {got}")
            continue
        problems = []
        if got.get("matched") != len(ids):
            problems.append(f"matched {got.get('matched')} != expected {len(ids)}")
        if got.get("partial"):
            problems.append("partial=true")
        if got.get("scanned") != len(docs):
            problems.append(f"scanned {got.get('scanned')} != {len(docs)}")
        r = compare_rows(got.get("rows", []), page, allk, order)
        if r == "TIES-ORDER-DIFFERS" and not problems:
            stats["ties"] += 1
            continue
        if r:
            problems.append(r)
        if problems:
            stats["fail"] += 1
            print(f"FAIL {a.label} #{n} {q}\n   " + "; ".join(problems))
        else:
            stats["ok"] += 1
    print(f"SUMMARY {a.label} {stats} docs={len(docs)} {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
