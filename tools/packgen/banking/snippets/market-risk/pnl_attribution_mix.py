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

# title: P&L attribution by factor across books and desks
# description: Every book's P&L attribution read in full and pivoted: factor by book, and summed by desk (the desk taken from each book's own record), with totals; each factor's share of the absolute explained P&L, and the desks' factor mix as a heatmap (matplotlib).
# kinds: pnl-explain
# example: PNL PNL-EQD-1

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

found = await drishti.search_async("PNL where actual != 0 and book != '' limit 1000")
books = await drishti.search_async("BOOK where desk != '' limit 1000")
desk_of = dict(zip(books["id"], books["desk"]))
rows = []
for rid in found["id"]:
    d = await drishti.get_async("pnl-explain", rid)
    for a in d["attribution"]:
        rows.append({"book": d["book"], "desk": desk_of.get(d["book"], "?"), "factor": a["factor"], "pnl": a["pnl"]})
att = pd.DataFrame(rows)

by_book = att.pivot_table(index="book", columns="factor", values="pnl", aggfunc="sum", fill_value=0, margins=True, margins_name="Total")
show(by_book, title=f"Attribution by book ({att['book'].nunique()} books)")
by_desk = att.pivot_table(index="desk", columns="factor", values="pnl", aggfunc="sum", fill_value=0)
show(by_desk.assign(Total=by_desk.sum(axis=1)), title="Attribution by desk")
share = att.groupby("factor")["pnl"].apply(lambda s: s.abs().sum()) / att["pnl"].abs().sum()
show(share.sort_values(ascending=False).to_frame("share of absolute P&L"), title="What drives P&L, firm-wide")
me = view.doc.get("book")
if me in by_book.index:
    print(f"{me}: largest factor {by_book.loc[me].drop('Total').abs().idxmax()}; book total {by_book.loc[me, 'Total']:,.0f}")

mix = by_desk.div(by_desk.abs().sum(axis=1), axis=0)              # each desk's signed factor mix
fig, ax = plt.subplots(figsize=(7, 3.6))
im = ax.imshow(mix.to_numpy(), cmap="RdBu", vmin=-1, vmax=1, aspect="auto")
ax.set_xticks(range(len(mix.columns)), mix.columns, rotation=30, ha="right", fontsize=7)
ax.set_yticks(range(len(mix.index)), mix.index, fontsize=7)
for (i, j), v in np.ndenumerate(mix.to_numpy()):
    ax.text(j, i, f"{v:.0%}", ha="center", va="center", fontsize=6)
ax.set_title("Factor mix of each desk's P&L (share of absolute)")
fig.colorbar(im, ax=ax, shrink=0.8)
