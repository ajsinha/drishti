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

# title: Legal entity: risk against return by book and desk
# description: The legal entity's books with their VaR, today's P&L and MTM: P&L per unit of VaR by book, books grouped by desk with VaR summed (no diversification, an upper bound) and the desk's P&L over VaR, the books in a risk-return scatter, and the entity's share of P&L and VaR by desk.
# kinds: legal-entity
# example: LE LE-NY

import numpy as np
import pandas as pd

doc = view.doc
books = pd.DataFrame(doc["BOOKS"])
books["P&L / VaR"] = books["PNL"] / books["VAR"]
books["MTM / VaR"] = books["MTM"] / books["VAR"]
show(books.sort_values("P&L / VaR", ascending=False).reset_index(drop=True),
     title=f"{view.id}: {doc.get('NAME', '')} ({doc.get('JURISDICTION', '')}), {len(books)} books")

desks = books.groupby("DESK").agg(books=("BOOK", "size"), trades=("TRADES", "sum"), mtm=("MTM", "sum"), pnl=("PNL", "sum"),
                                  var_sum=("VAR", "sum"))
desks["P&L / VaR (sum)"] = desks["PNL"] / desks["var_sum"]
desks["share of P&L"] = desks["PNL"] / desks["PNL"].abs().sum()
desks["share of VaR"] = desks["var_sum"] / desks["var_sum"].sum()
show(desks.sort_values("var_sum", ascending=False), title="By desk (VaR summed over books: no diversification)")
chart(books, kind="scatter", x="VAR", y="PNL", title="Books: today's P&L against VaR")
chart(desks.reset_index(), kind="bar", x="DESK", y=["share of P&L", "share of VaR"], title="Each desk's share of P&L and VaR")
corr = np.corrcoef(books["VAR"], books["PNL"].abs())[0, 1]
print(f"Total P&L {books['PNL'].sum():,.0f} on summed VaR {books['VAR'].sum():,.0f}; correlation of |P&L| with VaR across books "
      f"{corr:.2f} (risk that pays should move with it)")
