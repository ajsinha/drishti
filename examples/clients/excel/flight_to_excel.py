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

"""An Excel workbook from a Flight read, with no add-in to install (examples/clients/excel/README.md).

    python3 flight_to_excel.py --out desk-rates.xlsx [--scope DESK:RATES] [--date 2026-09-30]

Reads, read only, as the token's person: the trades-basic model by desk, and a scope's VaR scenario P&L with the engine's VaR and ES.
Writes one workbook (openpyxl) on your machine: "By desk", "P&L" (one row per scenario), "Summary" (the engine's figures as values,
and live Excel formulas over the P&L sheet a reader can follow), "About" (what was read, when, as whom).
"""
from __future__ import annotations

import argparse
import datetime
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))          # flight_setup.py, which finds the client
from flight_setup import DATE, SCOPE, FlightRefused, connect, meta  # noqa: E402


def read(scope: str, date: str | None):
    with connect() as f:
        who = f.whoami()
        by_desk = f.model("trades-basic", {"fields": ["trade.DESK"], "measures": ["Notional", "MTM", "Trades"],
                                           "orderBy": [{"by": "Notional", "desc": True}], "limit": 200})
        pnl = f.measure("var.scenarios", scope, date=date)
    return who, by_desk, pnl


def write(out: Path, scope: str, who: dict, by_desk, pnl) -> None:
    from openpyxl import Workbook
    from openpyxl.styles import Font

    bold = Font(bold=True)
    wb = Workbook()

    ws = wb.active
    ws.title = "By desk"
    ws.append(by_desk.column_names)
    for row in by_desk.to_pylist():
        ws.append([row[c] for c in by_desk.column_names])
    for c in ws[1]:
        c.font = bold

    p = wb.create_sheet("P&L")
    p.append(["SCENARIO", "SCENARIO_DATE", "PNL"])
    for row in pnl.select(["SCENARIO", "SCENARIO_DATE", "PNL"]).to_pylist():
        p.append([row["SCENARIO"], row["SCENARIO_DATE"], row["PNL"]])
    for c in p[1]:
        c.font = bold
    last = p.max_row
    pnl_range = f"'P&L'!C2:C{last}"

    s = wb.create_sheet("Summary", 0)
    ccy = meta(pnl, "drishti.currency", "")
    rows = [
        ("Scope", meta(pnl, "drishti.scope", scope), "as the engine names it"),
        ("Business date", meta(pnl, "drishti.date", ""), ""),
        ("Currency", ccy, ""),
        ("VaR (engine)", float(meta(pnl, "drishti.var", "nan")), "the engine's figure, as read"),
        ("ES (engine)", float(meta(pnl, "drishti.es", "nan")), "the engine's figure, as read"),
        ("Scenarios", f"=COUNT({pnl_range})", "a formula over the P&L sheet"),
        ("Worst P&L", f"=MIN({pnl_range})", "a formula over the P&L sheet"),
        ("Scenarios worse than -VaR", f'=COUNTIF({pnl_range},"<"&-B5)', "a formula: how many losses exceed the engine's VaR"),
        ("Average of those", f'=IFERROR(AVERAGEIF({pnl_range},"<"&-B5),"")', "a formula: compare with the engine's ES"),
    ]
    s.append(["Figure", "Value", "Note"])
    for r in rows:
        s.append(list(r))
    for c in s[1]:
        c.font = bold
    for r in range(5, 11):
        s.cell(r, 2).number_format = "#,##0"
    s.column_dimensions["A"].width = 28
    s.column_dimensions["B"].width = 20
    s.column_dimensions["C"].width = 50

    a = wb.create_sheet("About")
    a.append(["Read through Arrow Flight, read only, as", who.get("person", "")])
    a.append(["On behalf of", who.get("onBehalfOf") or ""])
    a.append(["Read at", datetime.datetime.now().isoformat(timespec="seconds")])
    a.append(["Requests", f"model trades-basic by trade.DESK; measure('var.scenarios', '{scope}')"])
    a.append(["Note", "A copy as your person could see it on the read date: masks and row rules applied. Figures are the engine's."])
    a.column_dimensions["A"].width = 40

    wb.save(out)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="An Excel workbook from a Flight read (read only).")
    ap.add_argument("--out", default="flight-read.xlsx", help="the workbook to write on your machine")
    ap.add_argument("--scope", default=SCOPE)
    ap.add_argument("--date", default=DATE)
    a = ap.parse_args(argv)
    try:
        who, by_desk, pnl = read(a.scope, a.date)
    except FlightRefused as e:
        print(str(e), file=sys.stderr)
        return 2
    out = Path(a.out)
    write(out, a.scope, who, by_desk, pnl)
    print(f"wrote {out} ({by_desk.num_rows} desks, {pnl.num_rows} scenarios)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
