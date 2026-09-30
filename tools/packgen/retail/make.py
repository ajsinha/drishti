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

"""The retail-banking pack: customers, deposit accounts, mortgages, card accounts, personal loans, branches,
collections cases and IFRS 9 portfolio segments, booked in the banking-core legal entities.

    python3 tools/packgen/retail/make.py            write packs/retail-banking
    python3 tools/packgen/retail/make.py --check    fail if the pack differs from what would be written
    uv run --with deltalake --with pyarrow --with pyyaml python tools/packgen/retail/make.py --lake data/delta
"""
from __future__ import annotations

import hashlib
import random
import sys
from datetime import date, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402
import make_data as BANK  # noqa: E402
import taxonomy as T  # noqa: E402
from risk_model import Kind, Panel as P  # noqa: E402

G = "Retail banking"
AS_OF = date(2026, 9, 30)
FIRST = ["Priya", "James", "Mei", "Oluwaseun", "Sofia", "Daniel", "Aisha", "Tomás", "Hannah", "Kenji", "Fatima", "Liam", "Grace", "Mateo", "Zara", "Noah"]
LAST = ["Sharma", "Walker", "Chen", "Adeyemi", "Rossi", "Kowalski", "Rahman", "García", "Müller", "Tanaka", "Haddad", "O'Brien", "Nguyen", "Silva", "Patel", "Evans"]
BRANCHES = [("BRN-NY-5AVE", "Fifth Avenue", "New York", "LE-NY"), ("BRN-NY-BKLN", "Brooklyn Heights", "New York", "LE-NY"),
            ("BRN-LDN-CITY", "City of London", "London", "LE-LDN"), ("BRN-LDN-CAMD", "Camden", "London", "LE-LDN"),
            ("BRN-FRA-MAIN", "Frankfurt Main", "Frankfurt", "LE-FRA"), ("BRN-TKY-MRN", "Marunouchi", "Tokyo", "LE-TKY")]
SPEND = ["Groceries", "Travel", "Dining", "Fuel", "Online retail", "Utilities", "Entertainment"]

KINDS = [
    Kind("customer", "CUST", "CUST-", "Customer", G, "A retail customer: segment, products held, KYC risk and total relationship.", "customerId",
         [("Customer", "$.name", None, None, False), ("Segment", "$.segment", None, None, False), ("Deposits", "$.totalDeposits", "compact", None, True),
          ("Lending", "$.totalLending", "compact", None, False), ("KYC risk", "$.kycRisk", None, "status", False), ("Customer since", "$.since", "date", None, False)],
         [P("table", "products", "Products held", "$.products", [("Product", "@.product", None, None, False), ("Account", "@.id", None, None, False),
            ("Balance", "@.balance", "signed2", "sign", True)], key="F2"),
          P("kv", "profile", "Profile", "$.profile", key="F3", area="right")],
         links={"branch": ("branch", "Home branch")}, badge="$.segment"),
    Kind("deposit-account", "ACCT", "ACCT-", "Deposit account", G, "A current or savings account: balance, rate, recent transactions and balance history.", "accountId",
         [("Type", "$.type", None, None, False), ("Holder", "$.holderName", None, None, False), ("Balance", "$.balance", "amount2", None, True),
          ("Currency", "$.currency", None, None, False), ("Rate", "$.rate", "pct2", None, False), ("Status", "$.status", None, "status", False)],
         [P("ladder", "transactions", "Recent transactions", "$.transactions", [("Date", "@.date", "date", None, False), ("Description", "@.description", None, None, False),
            ("Amount", "@.amount", "signed2", "sign", False), ("Balance", "@.balance", "amount2", None, False)], key="F2"),
          P("line", "history", "Month-end balance", "$.history", x="month", y="balance", fmt="compact", area="right")],
         links={"customer": ("customer", "Holder")}, badge="fmt($.balance, 'compact')", live={"balance": 25.0}),
    Kind("mortgage", "MTG", "MTG-", "Mortgage", G, "A residential mortgage: balance, rate, loan-to-value, amortisation and arrears.", "loanId",
         [("Borrower", "$.borrowerName", None, None, False), ("Balance", "$.balance", "amount0", None, True), ("Rate", "$.rate", "pct2", None, False),
          ("LTV", "$.ltv", "pct0", None, False), ("Remaining term (y)", "$.remainingYears", "amount0", None, False), ("Days past due", "$.daysPastDue", "amount0", None, False)],
         [P("line", "amortisation", "Scheduled balance", "$.amortisation", x="year", y="balance", fmt="compact", key="F2"),
          P("kv", "property", "Property", "$.property", key="F3", area="right"),
          P("table", "payments", "Last payments", "$.payments", [("Due", "@.due", "date", None, False), ("Amount", "@.amount", "amount2", None, False),
            ("Paid", "@.paid", "date", None, False), ("Status", "@.status", None, "status", False)], key="F4")],
         links={"customer": ("customer", "Borrower")}, badge="fmt($.ltv, 'pct0') + ' LTV'"),
    Kind("card-account", "CARD", "CARD-", "Card account", G, "A credit card: limit, utilisation, spend by category and statements.", "cardId",
         [("Holder", "$.holderName", None, None, False), ("Product", "$.product", None, None, False), ("Balance", "$.balance", "amount2", None, True),
          ("Limit", "$.limit", "amount0", None, False), ("Utilisation", "$.utilisation", "pct0", None, False), ("APR", "$.apr", "pct2", None, False),
          ("Status", "$.status", None, "status", False)],
         [P("hbar", "spend", "Spend by category (this cycle)", "$.spend", label="category", value="amount", fmt="amount2", key="F2"),
          P("table", "statements", "Statements", "$.statements", [("Closing", "@.closing", "date", None, False), ("Balance", "@.balance", "amount2", None, False),
            ("Minimum", "@.minimum", "amount2", None, False), ("Paid", "@.paid", "amount2", None, False)], key="F3")],
         links={"customer": ("customer", "Holder")}, badge="fmt($.utilisation, 'pct0') + ' used'", live={"balance": 12.0}),
    Kind("personal-loan", "PLN", "PLN-", "Personal loan", G, "An unsecured instalment loan: purpose, instalments and remaining balance.", "loanId",
         [("Borrower", "$.borrowerName", None, None, False), ("Purpose", "$.purpose", None, None, False), ("Balance", "$.balance", "amount2", None, True),
          ("Instalment", "$.instalment", "amount2", None, False), ("Rate", "$.rate", "pct2", None, False), ("Days past due", "$.daysPastDue", "amount0", None, False)],
         [P("ladder", "schedule", "Next instalments", "$.schedule", [("Due", "@.due", "date", None, False), ("Principal", "@.principal", "amount2", None, False),
            ("Interest", "@.interest", "amount2", None, False), ("Balance after", "@.balance", "amount2", None, False)], key="F2")],
         links={"customer": ("customer", "Borrower")}),
    Kind("branch", "BRN", "BRN-", "Branch", G, "A branch: customers, deposits and lending, product mix and service scores.", "branchId",
         [("Branch", "$.name", None, None, False), ("City", "$.city", None, None, False), ("Customers", "$.customers", "amount0", None, False),
          ("Deposits", "$.deposits", "compact", None, True), ("Lending", "$.lending", "compact", None, False), ("NPS", "$.nps", "amount0", None, False)],
         [P("hbar", "mix", "Balances by product", "$.productMix", label="product", value="balance", fmt="compact", key="F2"),
          P("line", "nps", "Net promoter score by month", "$.npsHistory", x="month", y="nps", fmt="amount0", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
    Kind("collections-case", "COLC", "COLC-", "Collections case", G, "A delinquent account in collections: arrears, stage, strategy and contact history.", "caseId",
         [("Customer", "$.customerName", None, None, False), ("Arrears", "$.arrears", "amount2", None, True), ("Days past due", "$.daysPastDue", "amount0", None, False),
          ("Stage", "$.stage", None, None, False), ("Strategy", "$.strategy", None, None, False), ("Status", "$.status", None, "status", False)],
         [P("ladder", "contacts", "Contact history", "$.contacts", [("Date", "@.date", "date", None, False), ("Channel", "@.channel", None, None, False),
            ("Outcome", "@.outcome", None, None, False)], key="F2")],
         links={"customer": ("customer", "Customer"), "loan": ("personal-loan", "Loan"), "card": ("card-account", "Card")}),
    Kind("retail-portfolio", "RPF", "RPF-", "Retail portfolio (IFRS 9)", G, "A retail portfolio segment: exposure and expected credit loss by IFRS 9 stage, and delinquency.", "portfolioId",
         [("Portfolio", "$.name", None, None, False), ("Exposure", "$.exposure", "compact", None, False), ("ECL", "$.ecl", "compact", None, True),
          ("Coverage", "$.coverage", "pct2", None, False), ("30+ DPD rate", "$.dpd30", "pct2", None, False)],
         [P("table", "stages", "By IFRS 9 stage", "$.stages", [("Stage", "@.stage", None, None, False), ("Exposure", "@.exposure", "compact", None, True),
            ("PD (12m)", "@.pd", "pct2", None, False), ("LGD", "@.lgd", "pct0", None, False), ("ECL", "@.ecl", "compact", None, True)], key="F2"),
          P("line", "delinquency", "30+ days past due rate", "$.delinquency", x="month", y="rate", fmt="pct2", area="right")],
         links={"legalEntity": ("legal-entity", "Legal entity")}),
]


def rng(key: str) -> random.Random:
    return random.Random(int(hashlib.sha256(key.encode()).hexdigest()[:12], 16))


def months(n: int) -> list[str]:
    y, m, out = AS_OF.year, AS_OF.month, []
    for _ in range(n):
        out.append(f"{y}-{m:02d}")
        y, m = (y, m - 1) if m > 1 else (y - 1, 12)
    return out[::-1]


def build(bank: dict) -> dict[str, dict[str, dict]]:
    docs = {k.kind: {} for k in KINDS}
    meta = {"source": "core-banking", "generation": 1}
    for i in range(32):
        r = rng(f"cust{i}")
        bid, _, city, le = BRANCHES[i % len(BRANCHES)]
        ccy = {"New York": "USD", "London": "GBP", "Frankfurt": "EUR", "Tokyo": "JPY"}[city]
        cid = f"CUST-{100231 + i * 7}"
        name = f"{FIRST[i % 16]} {LAST[(i * 5) % 16]}"
        seg = ["Mass market", "Mass affluent", "Private", "Mass market"][i % 4]
        products = []
        # deposits
        for t, lo, hi, rate in [("Current account", 800, 18000, 0.001), ("Savings", 2000, 120000, 0.032)][: 1 + (i % 3 > 0)]:
            aid = f"ACCT-{cid[5:]}-{'CUR' if t.startswith('Current') else 'SAV'}"
            unit = 150 if ccy == "JPY" else 1   # amounts are drawn in dollars; yen accounts are 150x larger
            bal = round(r.uniform(lo, hi) * (4 if seg == "Private" else 1) * unit, 2)
            tx, b, day = [], bal, AS_OF
            for j in range(10):
                amt = round((-r.uniform(8, 450) if r.random() < 0.8 else r.uniform(1500, 6500)) * unit, 2)
                tx.append({"date": day.isoformat(), "description": r.choice(["Card payment · grocer", "Direct debit · utilities", "Salary", "Transfer to savings",
                                                                           "ATM withdrawal", "Card payment · online retail", "Standing order · rent"]) if amt < 0 else "Salary",
                           "amount": amt, "balance": round(b, 2)})
                b -= amt
                day -= timedelta(days=r.randint(1, 3))
            docs["deposit-account"][aid] = {"accountId": aid, "type": t, "holderName": name, "balance": bal, "currency": ccy, "rate": rate,
                                            "status": "Active" if r.random() > 0.05 else "Dormant", "transactions": tx,
                                            "history": [{"month": m, "balance": round(bal * r.uniform(0.7, 1.15), 2)} for m in months(12)],
                                            "customer": cid, "_meta": {**meta, "live": True, "walk": {"balance": 25.0}}}
            products.append({"product": t, "id": aid, "balance": bal})
        # mortgage
        if i % 2 == 0:
            mid = f"MTG-{cid[5:]}"
            value = round(r.uniform(280, 1600) * 1000, -3)
            orig = round(value * r.uniform(0.6, 0.85), -3)
            term, age = 25, r.randint(1, 12)
            rate = round(r.uniform(0.029, 0.061), 4)
            q = rate / 12
            pay = orig * q / (1 - (1 + q) ** (-term * 12))
            bal_at = lambda n: orig * (1 + q) ** n - pay * ((1 + q) ** n - 1) / q  # noqa: E731
            bal = round(bal_at(age * 12), 2)
            dpd = r.choice([0] * 9 + [35])
            docs["mortgage"][mid] = {"loanId": mid, "borrowerName": name, "balance": bal, "rate": rate, "ltv": round(bal / value, 3), "remainingYears": term - age,
                                     "daysPastDue": dpd, "originalAmount": orig, "monthlyPayment": round(pay, 2),
                                     "amortisation": [{"year": str(AS_OF.year - age + y), "balance": round(max(0, bal_at(y * 12)))} for y in range(0, term + 1, 2)],
                                     "property": {"type": r.choice(["Apartment", "Terraced house", "Detached house"]), "city": city, "valuation": value,
                                                  "valuedOn": (AS_OF - timedelta(days=r.randint(30, 900))).isoformat(), "rateType": r.choice(["2y fixed", "5y fixed", "Tracker"])},
                                     "payments": [{"due": (AS_OF.replace(day=1) - timedelta(days=30 * k)).isoformat(), "amount": round(pay, 2),
                                                   "paid": None if (dpd and k == 0) else (AS_OF.replace(day=1) - timedelta(days=30 * k - 1)).isoformat(),
                                                   "status": "Missed" if (dpd and k == 0) else "Paid"} for k in range(6)],
                                     "customer": cid, "_meta": meta}
            products.append({"product": "Mortgage", "id": mid, "balance": -bal})
        # card
        if i % 3 != 2:
            kid = f"CARD-{cid[5:]}"
            limit = r.choice([2500, 5000, 8000, 15000, 25000])
            spend = [{"category": c, "amount": round(r.uniform(20, limit * 0.08), 2)} for c in SPEND]
            bal = round(sum(s["amount"] for s in spend) * r.uniform(0.8, 2.5), 2)
            docs["card-account"][kid] = {"cardId": kid, "holderName": name, "product": r.choice(["Rewards Visa", "Cashback Mastercard", "Travel Platinum"]),
                                         "balance": bal, "limit": limit, "utilisation": round(bal / limit, 3), "apr": round(r.uniform(0.179, 0.289), 4),
                                         "status": "Active" if i % 11 else "Delinquent", "spend": spend,
                                         "statements": [{"closing": (AS_OF.replace(day=15) - timedelta(days=30 * k)).isoformat(), "balance": round(bal * r.uniform(0.6, 1.2), 2),
                                                         "minimum": round(bal * 0.03, 2), "paid": round(bal * r.choice([0.03, 0.5, 1.0]), 2)} for k in range(1, 5)],
                                         "customer": cid, "_meta": {**meta, "live": True, "walk": {"balance": 12.0}}}
            products.append({"product": "Credit card", "id": kid, "balance": -bal})
        # personal loan
        if i % 4 == 1:
            lid = f"PLN-{cid[5:]}"
            amount = round(r.uniform(3, 40) * 1000, -2)
            rate = round(r.uniform(0.065, 0.14), 4)
            q, n = rate / 12, 60
            inst = amount * q / (1 - (1 + q) ** -n)
            paid = r.randint(3, 40)
            bal = amount * (1 + q) ** paid - inst * ((1 + q) ** paid - 1) / q
            sched, b = [], bal
            for k in range(1, 7):
                intr = b * q
                b -= inst - intr
                sched.append({"due": (AS_OF.replace(day=5) + timedelta(days=31 * k)).isoformat(), "principal": round(inst - intr, 2), "interest": round(intr, 2), "balance": round(b, 2)})
            docs["personal-loan"][lid] = {"loanId": lid, "borrowerName": name, "purpose": r.choice(["Car", "Home improvement", "Debt consolidation", "Wedding"]),
                                          "balance": round(bal, 2), "instalment": round(inst, 2), "rate": rate, "daysPastDue": 64 if i % 8 == 5 else 0,
                                          "schedule": sched, "customer": cid, "_meta": meta}
            products.append({"product": "Personal loan", "id": lid, "balance": -round(bal, 2)})
        docs["customer"][cid] = {"customerId": cid, "name": name, "segment": seg, "totalDeposits": round(sum(p["balance"] for p in products if p["balance"] > 0), 2),
                                 "totalLending": round(-sum(p["balance"] for p in products if p["balance"] < 0), 2), "kycRisk": r.choice(["Low", "Low", "Medium", "High"]),
                                 "since": date(r.randint(1998, 2024), r.randint(1, 12), r.randint(1, 28)).isoformat(), "products": products,
                                 "profile": {"city": city, "age": r.randint(22, 78), "employment": r.choice(["Employed", "Self-employed", "Retired", "Student"]),
                                             "channel": r.choice(["Mobile", "Branch", "Online"]), "creditScore": r.randint(560, 830), "bookingEntity": le},
                                 "branch": bid, "_meta": meta}
    # collections: every delinquent card and loan
    n = 0
    for kind, field_, dpd_of in [("card-account", "card", lambda d: 0 if d["status"] == "Active" else 47), ("personal-loan", "loan", lambda d: d["daysPastDue"])]:
        for did, d in docs[kind].items():
            dpd = dpd_of(d)
            if not dpd:
                continue
            n += 1
            r = rng("colc" + did)
            cid = d["customer"]
            case = f"COLC-{2600 + n}"
            docs["collections-case"][case] = {
                "caseId": case, "customerName": d["holderName"] if kind == "card-account" else d["borrowerName"],
                "arrears": round((d["balance"] * 0.03 if kind == "card-account" else d["instalment"]) * (1 + dpd // 30), 2), "daysPastDue": dpd,
                "stage": "Early (30-59)" if dpd < 60 else "Mid (60-89)", "strategy": r.choice(["Self-cure SMS", "Agent call", "Payment plan"]), "status": "Open",
                "contacts": [{"date": (AS_OF - timedelta(days=dpd - 5 * k)).isoformat(), "channel": ch, "outcome": o}
                             for k, (ch, o) in enumerate([("SMS", "Delivered"), ("Call", "No answer"), ("Call", "Promise to pay"), ("Letter", "Sent")][: 2 + dpd // 30])],
                "customer": cid, field_: did, "_meta": {"source": "collections", "generation": 1}}
    for bid, bname, city, le in BRANCHES:
        r = rng(bid)
        custs = [c for c in docs["customer"].values() if c["branch"] == bid]
        mix = {}
        for c in custs:
            for p in c["products"]:
                mix[p["product"]] = mix.get(p["product"], 0) + abs(p["balance"])
        scale = r.uniform(900, 2400)   # the sample customers stand for the branch book
        docs["branch"][bid] = {"branchId": bid, "name": bname, "city": city, "customers": round(len(custs) * scale),
                               "deposits": round(sum(c["totalDeposits"] for c in custs) * scale, -3), "lending": round(sum(c["totalLending"] for c in custs) * scale, -3),
                               "nps": r.randint(18, 62), "productMix": [{"product": k, "balance": round(v * scale, -3)} for k, v in sorted(mix.items(), key=lambda x: -x[1])],
                               "npsHistory": [{"month": m, "nps": r.randint(15, 65)} for m in months(12)], "legalEntity": le, "_meta": meta}
    for pid, name, le, base_pd, lgd, size in [("RPF-US-MORT", "US residential mortgages", "LE-NY", 0.006, 0.18, 42e9), ("RPF-US-CARD", "US credit cards", "LE-NY", 0.035, 0.85, 9e9),
                                               ("RPF-UK-MORT", "UK residential mortgages", "LE-LDN", 0.005, 0.15, 31e9), ("RPF-UK-UPL", "UK unsecured personal loans", "LE-LDN", 0.028, 0.7, 4e9)]:
        r = rng(pid)
        stages = []
        for st, share, mult, life in [("Stage 1", 0.9, 1, 1), ("Stage 2", 0.08, 6, 4), ("Stage 3", 0.02, 1 / base_pd, 1)]:
            exp = round(size * share * r.uniform(0.9, 1.1), -5)
            pd = min(1.0, base_pd * mult)
            stages.append({"stage": st, "exposure": exp, "pd": round(pd, 4), "lgd": lgd, "ecl": round(exp * min(1.0, pd * life) * lgd, -3)})
        exp, ecl = sum(s["exposure"] for s in stages), sum(s["ecl"] for s in stages)
        dq = [{"month": m, "rate": round(base_pd * 0.9 * r.uniform(0.8, 1.3), 4)} for m in months(18)]
        docs["retail-portfolio"][pid] = {"portfolioId": pid, "name": name, "exposure": exp, "ecl": ecl, "coverage": round(ecl / exp, 4), "dpd30": dq[-1]["rate"],
                                         "stages": stages, "delinquency": dq, "legalEntity": le, "_meta": {"source": "impairment-engine", "generation": 1}}
    return docs


def spec(bank: dict) -> PB.PackSpec:
    return PB.PackSpec(
        name="retail-banking", title="Retail banking", requires=["banking-core"], generator="tools/packgen/retail/make.py",
        description="Customers, deposit accounts, mortgages, cards, personal loans, branches, collections and IFRS 9 portfolio segments.",
        domains={"retail": KINDS},
        examples=[("CUST CUST-100231", "Customer · products held and profile"), ("ACCT ACCT-100231-CUR", "Current account · transactions (live)"),
                  ("MTG MTG-100231", "Mortgage · amortisation, property, payments"), ("CARD CARD-100238", "Credit card · spend by category, statements"),
                  ("BRN BRN-LDN-CITY", "Branch · product mix and NPS"), ("RPF RPF-US-CARD", "IFRS 9 · ECL by stage, delinquency")],
        overview="Retail customers belong to branches, and branches and portfolio segments to the banking-core legal entities. A customer view lists "
                 "every product the customer holds; each product links back to its holder, and delinquent cards and loans open collections cases.",
        external_ids={"legal-entity": set(bank["legal-entity"])},
        roles={"retail": {"kinds": [k.kind for k in KINDS] + ["legal-entity"], "raw": True}})


if __name__ == "__main__":
    bank = BANK.build()
    PB.main(spec(bank), build(bank), T.graph_fields())
