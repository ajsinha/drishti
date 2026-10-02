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

"""Builds a hostile file-connector root: hostile/trading/<date>/trade.jsonl, one hostile case per business date.
Writes truth/<date>.jsonl (plain {"id":..,"doc":..} per entity as the documents should be read) for the checker."""
import json, os, pathlib, random, shutil

S = pathlib.Path("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data")
SRC = S / "files/trading/2026-09-30/trade.jsonl"
OUT = S / "hostile/trading"
TRUTH = S / "hostile-truth"
shutil.rmtree(S / "hostile", ignore_errors=True)
shutil.rmtree(TRUTH, ignore_errors=True)
OUT.mkdir(parents=True)
TRUTH.mkdir()
rows = [json.loads(l) for l in open(SRC, encoding="utf-8")][:400]
PROMOTED = ["tradeId", "productType", "productName", "direction", "currency", "notional", "mtm", "pnl1d", "maturityDate",
            "tradeDate", "book", "desk", "status", "assetClass", "counterparty.id", "counterparty.name", "nettingSet",
            "risk.dv01", "sourceSystem"]


def get(d, p):
    for k in p.split("."):
        if not isinstance(d, dict):
            return None
        d = d.get(k)
    return d


def env(id_, doc, date, columns=True, doc_as_object=False):
    r = {"domain": "trading", "kind": "trade", "id": id_, "date": date,
         "doc": doc if doc_as_object else json.dumps(doc, ensure_ascii=False)}
    if columns:
        r["columns"] = {p: get(doc, p) for p in PROMOTED}
    return json.dumps(r, ensure_ascii=False)


def write(date, lines, truth, raw_prefix=b"", newline="\n"):
    d = OUT / date
    d.mkdir(parents=True, exist_ok=True)
    data = raw_prefix + newline.join(lines).encode("utf-8") + (newline.encode() if lines else b"")
    (d / "trade.jsonl").write_bytes(data)
    with open(TRUTH / f"{date}.jsonl", "w", encoding="utf-8") as f:
        for id_, doc in truth:
            f.write(json.dumps({"id": id_, "doc": doc}, ensure_ascii=False) + "\n")


docs = [(r["id"], json.loads(r["doc"])) for r in rows]

# 09-01: one malformed (truncated) line in the middle of 400 good lines
lines = [env(i, d, "2026-09-01") for i, d in docs]
lines.insert(200, lines[200][: len(lines[200]) // 2])
write("2026-09-01", lines, docs)

# 09-02: duplicate ids - every one of the first 10 ids appears twice, with a different mtm the second time
dup = [(i, dict(d, mtm=d["mtm"] + 1_000_000_000)) for i, d in docs[:10]]
write("2026-09-02", [env(i, d, "2026-09-02") for i, d in docs + dup], docs)   # truth: undefined which; record first

# 09-03: plain documents (no envelope), mtm mixed types: one "N/A" text and one 1e20 number; ids in field id
plain = []
truth = []
for k, (i, d) in enumerate(docs):
    d = dict(d, id=i)
    if k == 5:
        d["mtm"] = "N/A"
    if k == 6:
        d["mtm"] = 1e20
    if k == 7:
        d["mtm"] = 12345678901234567890123
    plain.append(json.dumps(d, ensure_ascii=False))
    truth.append((i, d))
write("2026-09-03", plain, truth)

# 09-04: hostile ids - unicode, emoji, very long, spaces, quotes, slashes, case twins
odd = ["ÜNÏCØDÉ-1", "😀-EMOJI-2", "X" * 5000, "has space 4", 'quo"te-5', "sl/ash-6", "MX-CASE", "mx-case", "..%2F..%2Fetc", "<script>"]
oddd = []
for k, oid in enumerate(odd):
    d = dict(docs[k][1], tradeId=oid)
    oddd.append((oid, d))
write("2026-09-04", [env(i, d, "2026-09-04") for i, d in docs[10:] + oddd], docs[10:] + oddd)

# 09-05: envelope rows whose doc is a JSON object and which carry no columns (documented as allowed)
write("2026-09-05", [env(i, d, "2026-09-05", columns=False, doc_as_object=True) for i, d in docs], docs)

# 09-08: a UTF-8 byte order mark at the start of the file
write("2026-09-08", [env(i, d, "2026-09-08") for i, d in docs], docs, raw_prefix=b"\xef\xbb\xbf")

# 09-09: an empty file (zero bytes)
write("2026-09-09", [], [])

# 09-10: CRLF line ends and blank lines between rows
crlf = []
for i, d in docs:
    crlf += [env(i, d, "2026-09-10"), ""]
write("2026-09-10", crlf, docs, newline="\r\n")

# 09-11: a huge document (about 30 MB) and a deeply nested one (depth 3000)
big = dict(docs[0][1], blob="x" * 30_000_000)
deep = {}
cur = deep
for _ in range(3000):
    cur["n"] = {}
    cur = cur["n"]
deepdoc = dict(docs[1][1], deep=deep)
rest = docs[2:]
write("2026-09-11", [env(docs[0][0], big, "2026-09-11"), env(docs[1][0], deepdoc, "2026-09-11")] + [env(i, d, "2026-09-11") for i, d in rest],
      [(docs[0][0], big), (docs[1][0], deepdoc)] + rest)

# 09-14: a column whose type differs from the previous day (book as a number), and columns that disagree in type
chg = []
for k, (i, d) in enumerate(docs):
    d = dict(d)
    d["book"] = 1000 + k % 7          # a number where every other day has text
    chg.append((i, d))
write("2026-09-14", [env(i, d, "2026-09-14") for i, d in chg], chg)
print("hostile root written")
