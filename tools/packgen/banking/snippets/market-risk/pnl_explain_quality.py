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

# title: P&L explain quality across books
# description: This book's explain as steps (carry, roll-down, deltas, vega, theta, new trades, unexplained) and every book's explain from one search: the unexplained P&L as a share of actual, books over a 10% threshold flagged, and actual against explained P&L as a scatter. A day's snapshot; FRTB's P&L attribution test runs on a year of daily figures.
# kinds: pnl-explain
# example: PNL PNL-RATES-1

import numpy as np
import pandas as pd

doc = view.doc
steps = pd.DataFrame(doc["explainSteps"])
steps["cumulative"] = steps["pnl"].cumsum()
show(steps, title=f"{view.id} ({doc['book']}, {doc['date']}): actual {doc['actual']:,.0f}, explained {doc['explained']:,.0f}")
chart(steps, kind="bar", x="step", y="pnl", title=f"{doc['book']}: P&L explain by step")

# name every field wanted in the condition ("> -1000bn" keeps every row): a search returns the fields it names
books = await drishti.search_async("PNL where actual != 0 and book != '' and explained > -1000bn and unexplained > -1000bn limit 1000")
books["unexplained % of actual"] = (books["unexplained"] / books["actual"].abs() * 100).round(2)
books["flag"] = np.where(books["unexplained % of actual"].abs() > 10, "over 10%", "")
books = books.sort_values("unexplained % of actual", key=lambda s: s.abs(), ascending=False).reset_index(drop=True)
show(books[["book", "actual", "explained", "unexplained", "unexplained % of actual", "flag"]],
     title=f"Explain quality, {len(books)} books ({(books['flag'] != '').sum()} over the 10% threshold)")
chart(books, kind="scatter", x="explained", y="actual", title="Actual against explained P&L by book")
corr = np.corrcoef(books["actual"], books["explained"])[0, 1]
mean_abs = books["unexplained % of actual"].abs().mean()
print(f"Across books: correlation of actual and explained {corr:.3f}; mean absolute unexplained {mean_abs:.1f}% of actual. "
      f"{doc['book']} ranks {list(books['book']).index(doc['book']) + 1} of {len(books)} by unexplained share.")
