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

"""Builds a 'bank schema' for JDBC query mode from the 8000-trade book: qm/schema.sql (tables + COPY data) and
qm/truth-<date>.jsonl (the documents query mode should produce, for the checker). NULLs injected on purpose."""
import json, pathlib, random

S = pathlib.Path("/tmp/claude-1000/-home-ashutosh-IdeaProjects-drishti/efa68c11-fb98-4f9a-8faa-96a952090c0e/scratchpad/qa-data")
out = S / "qm"
out.mkdir(exist_ok=True)
rnd = random.Random(3)
trades, legs = [], []
truth = {}
for date in ("2026-09-28", "2026-09-29", "2026-09-30"):
    tr = []
    for l in open(S / f"files/trading/{date}/trade.jsonl"):
        r = json.loads(l)
        d = json.loads(r["doc"])
        row = {"trade_id": r["id"], "business_date": date, "product_type": d["productType"], "currency": d["currency"],
               "notional": d["notional"], "mtm": d["mtm"], "book": d["book"], "desk": d["desk"], "netting_set": d["nettingSet"],
               "counterparty_id": d["counterparty"]["id"], "maturity_date": d["maturityDate"]}
        if rnd.random() < 0.05:
            row["mtm"] = None
        if rnd.random() < 0.05:
            row["book"] = None
        if rnd.random() < 0.03:
            row["currency"] = None
        trades.append(row)
        tr.append(row)
        if date == "2026-09-30":
            for k in range(rnd.choice([0, 1, 2, 3])):
                legs.append({"trade_id": r["id"], "leg_no": k + 1, "pay_receive": rnd.choice(["PAY", "RECEIVE", None]),
                             "fixed_rate": rnd.choice([None, round(rnd.uniform(0, 5), 4)])})
    with open(out / f"truth-{date}.jsonl", "w") as f:
        for row in tr:
            doc = {"tradeId": row["trade_id"], "businessDate": row["business_date"], "productType": row["product_type"],
                   "currency": row["currency"], "notional": row["notional"], "mtm": row["mtm"], "book": row["book"],
                   "desk": row["desk"], "nettingSet": row["netting_set"], "counterparty": {"id": row["counterparty_id"]},
                   "maturityDate": row["maturity_date"]}
            f.write(json.dumps({"id": row["trade_id"], "doc": doc}) + "\n")


def lit(v):
    if v is None:
        return "\\N"
    return str(v).replace("\t", " ")


with open(out / "schema.sql", "w") as f:
    f.write("""DROP SCHEMA IF EXISTS bank CASCADE; CREATE SCHEMA bank;
CREATE TABLE bank.trades (trade_id text, business_date date, product_type text, currency text, notional numeric, mtm numeric,
  book text, desk text, netting_set text, counterparty_id text, maturity_date text, PRIMARY KEY (trade_id, business_date));
CREATE INDEX ON bank.trades (business_date);
CREATE TABLE bank.trade_legs (trade_id text, leg_no int, pay_receive text, fixed_rate numeric);
CREATE INDEX ON bank.trade_legs (trade_id);
COPY bank.trades FROM stdin;
""")
    cols = ["trade_id", "business_date", "product_type", "currency", "notional", "mtm", "book", "desk", "netting_set", "counterparty_id", "maturity_date"]
    for t in trades:
        f.write("\t".join(lit(t[c]) for c in cols) + "\n")
    f.write("\\.\nCOPY bank.trade_legs FROM stdin;\n")
    for g in legs:
        f.write("\t".join(lit(g[c]) for c in ["trade_id", "leg_no", "pay_receive", "fixed_rate"]) + "\n")
    f.write("\\.\nANALYZE bank.trades;\n")
print(len(trades), "trade rows,", len(legs), "legs")
