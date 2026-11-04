<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# Tutorial 37 · Custom functions, "why did it move" and what-if

The built-in measures cover most questions; the rest you write yourself. In this tutorial you put a formula straight into a pivot, write a
weighted-return function in the playground and watch the harness prove that its split-and-merge answer equals one pass, save it, have
someone else approve it and use it in a view, withdraw it, then explain why one product's notional differs from another's in steps that
add up, and try a what-if. Each step shows the UI, the command line and the JSON or YAML. The guide is
[PIVOT_GUIDE.md, sections 19 to 22](../guides/PIVOT_GUIDE.md#19-custom-functions).

> Screenshots marked *(filled in the heavy run)* are taken when the steps are run against a built server (`tools/docs/screenshots.py`);
> until then the marker stands where the picture goes.

## Time to complete

About 25 minutes.

## Before you start

- [Tutorial 33](33-the-pivot-engine.md) done: `PIVOT trade <GO>` answers with the demo trades.
- Two users: you (an analyst) and a second user in the role `pivot-approver` (or an administrator) for step 4
  ([USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md)).
- A token for the command line in `DRISHTI_TOKEN` or a `--token-file` ([TOOLS.md](../guides/TOOLS.md)).

## What you will learn

- how a formula of built-ins is one function whose totals are made from states, never from the figures shown;
- what the harness checks before a function is saved, shared or used;
- why sharing needs an approver who is not the author, and what a withdrawn function looks like;
- how a difference is split into steps that add up, for sums, ratios and holistic measures;
- how a what-if overlay changes only the cells it touches, beside the original.

## Step 1 · A formula in a view

**UI.** `PIVOT trade <GO>`, `productType` on Rows. In Values, edit a value's text to `sum(notional, where=notional > 1000000) / sum(notional)`
(the share of notional in large trades). Every row and the grand total show the ratio; the grand total is the ratio over all the trades, not
an average of the rows' ratios.

**CLI.**

```
drishti.py pivot run --source trade --rows productType --values "sum(notional, where=notional > 1000000) / sum(notional)"
```

**JSON.**

```json
{"source": "trade", "rows": ["productType"], "values": [{"measure": "sum(notional, where=notional > 1000000) / sum(notional)", "as": "Large share"}]}
```

*(screenshot filled in the heavy run: the trades by product type with the large-notional share as a value)*

**You should see** one ratio per product type and a grand total that equals the sum of the large trades' notional divided by the sum of all notional (`DATA trade`, filter and sum).

## Step 2 · The playground: a function and its harness

**UI.** Press `U` (or **Functions**). Name `wret`, language **formula (DEL)**, inputs `r, w`, code `sum(r * w) / sum(w)`; leave the sample
rows and the group column `g`. Press **Check**. You see *Passes the harness*, the checks (one pass; batches of 1, 7, 1,000 and 65,536;
2, 3 and 16 random parts merged left-deep and balanced under five seeds; an empty cell; a part of nulls; one row; equal rows; infinities;
the state read back; the same bits twice; the cost; the state's size), the state after each part and after each merge (two sums, `[Σrw, Σw]`
as the engine keeps them) with the final figure beside the one-pass figure, and the little pivot of the sample by `g` whose total is made
from the groups' states.

**CLI.** Write `wret.yaml` and `rows.csv`:

```yaml
name: wret
language: formula
code: sum(r * w) / sum(w)
inputs: [r, w]
description: the weighted mean of returns
```

```
r,w,g
0.012,3,A
-0.004,1,B
0.020,2,A
0.001,5,B
```

```
drishti.py pivot functions test wret.yaml --sample rows.csv --group g
```

**JSON** (`POST /api/v1/pivot/functions/test`): the YAML's keys, plus `"sample": [{"r": 0.012, "w": 3, "g": "A"}, ...]` and `"group": "g"`.

*(screenshot filled in the heavy run: the playground with "Passes the harness", the states part by part and the sample's pivot)*

**You should see** *Passes the harness*; the final figure is (0.012·3 − 0.004·1 + 0.020·2 + 0.001·5) / 11 = 0.077 / 11 = 0.007.

## Step 3 · An SQL macro: rewritten into states, or holistic

**UI.** Language **SQL macro**, code `CREATE MACRO wret2(r, w) AS sum(r * w) / sum(w)`, **Check**: the kind is *mergeable* (the engine
rewrote the macro into the states of its sums). Now `CREATE MACRO slope(r, w) AS regr_slope(r, w)`: the kind is *holistic* (no merge;
each cell's figure is computed by DuckDB from its own rows) and the harness runs only the checks that apply.

**CLI.** The same with `language: sql` and `code: "CREATE MACRO slope(r, w) AS regr_slope(r, w)"`.

**You should see** *mergeable* for `wret2` and *holistic* for `slope`; a macro that names a table or a file (`read_csv(...)`) is refused in words (DRS-5710): a macro is SQL over its inputs only.

## Step 4 · Save, approve, use

**UI.** Back to `wret` (formula), **Save version**: `fn:you/wret@1`, private. As the second user, open **Functions**, find
`fn:you/wret@1` and press **Approve**, giving the reason "reviewed: a weighted mean". As yourself, in Values add
`fn:you/wret@1(PNL_FIELD, notional)` (your trades' P&L field and the notional): the column header says `fn:you/wret@1 ... by you`.

**CLI.**

```
drishti.py pivot functions save wret.yaml --sample rows.csv
drishti.py pivot functions approve you/wret@1 --reason "reviewed: a weighted mean"      # as the approver
drishti.py pivot run --source trade --rows productType --values "fn:you/wret@1(mtm, notional)"
```

**JSON.** `POST /api/v1/pivot/functions/you/wret@1/approve` with `{"scope": "everyone", "reason": "reviewed: a weighted mean"}`; the view's
value is `{"measure": "fn:you/wret@1(mtm, notional)"}`.

*(screenshot filled in the heavy run: the view with the column `fn:you/wret@1 ... by you`)*

**You should see** the new column in every row and the total; approving your own function is refused (DRS-5712: "an author never approves their own function"); Admin → Audit shows
`pivot.function.save`, `pivot.function.approve` and `pivot.function.use`, with the version.

## Step 5 · Withdraw

**UI.** As the approver, **Withdraw** `fn:you/wret@1` with a reason. Run your view again: every cell of that column says "function withdrawn
by … on …: reason", with no figure; the other values answer as before.

**CLI.** `drishti.py pivot functions revoke you/wret@1 --reason "superseded"`.

**You should see** "function withdrawn by …" in each cell of the column and no figure; the function's list marks it *withdrawn*.

## Step 6 · Why did it move?

**UI.** In the `productType` pivot with `sum(notional)`, select two product rows (click one, Shift-click the other) and press `W`. **Split
by** `counterparty`, **Explain**. The words say how much the second differs from the first and the largest steps; the waterfall has one bar
per counterparty (new, gone or changed) and the steps add up to the difference. Change the value to `avg(notional)` and explain again: each
step is now that counterparty's effect on the average given the steps before it, the order is shown (switch **Order** to "largest first"
and the same difference splits differently), and they still add up.

**CLI.**

```
drishti.py pivot explain --source trade --rows productType --values "sum(notional)" --cell productType=IRS --other-cell productType=FXFWD --by counterparty
```

**JSON** (`POST /api/v1/pivot/explain`):

```json
{"view": {"source": "trade", "rows": ["productType"], "values": ["sum(notional)"]},
 "from": {"productType": "IRS"}, "to": {"productType": "FXFWD"}, "by": "counterparty"}
```

*(screenshot filled in the heavy run: the waterfall of two product types split by counterparty)*

**You should see** the words and the waterfall; the sum of the steps plus the residual equals `difference`; the residual is named ("rounding"). For a cell on two dates, give
`fromDate` and `toDate` (`--from`, `--to`) instead of a second cell.

## Step 7 · What-if

**UI.** Press `I`. **Leave out** `productType=FXFWD`, **Change** `notional*1.1 where counterparty=CP-001`, **Run the what-if**. The list
shows each cell that changes and by how much (what-if minus original); nothing was written anywhere.

**CLI.**

```
drishti.py pivot whatif --source trade --rows productType --values "sum(notional)" --exclude productType=FXFWD --scale notional=1.1@counterparty=CP-001
```

**JSON** (`POST /api/v1/pivot/whatif`):

```json
{"view": {"source": "trade", "rows": ["productType"], "values": ["sum(notional)"]},
 "whatIf": {"exclude": {"productType": ["FXFWD"]}, "changes": [{"field": "notional", "scale": 1.1, "where": {"counterparty": ["CP-001"]}}]}}
```

*(screenshot filled in the heavy run: the what-if beside the original, the changed cells listed)*

**You should see** the what-if beside the original: FXFWD is gone from the what-if side, and the grand total moved by 10 % of CP-001's notional less FXFWD's.

## Step 8 · Python (an administrator turns it on)

Python functions are off as shipped. With `drishti.pivot.functions.python.enabled: true` in `config/engines/pivot.yaml` (and a restart),
language **Python** takes a class decorated with `@function` ([PIVOT_GUIDE.md section 19](../guides/PIVOT_GUIDE.md#19-custom-functions));
it runs in the node's sandboxed `pivot-fn` workers (no network, no files, no processes), fed Arrow batches over a pipe, and passes the same
harness. Python functions never read memory-only data.

**You should see** **Python** offered in the language list once it is on (and, while it is off, refused in words: DRS-5713).

## Step 9 · BI's totals are the engine's

Open a BI report with a matrix (the sample *trades overview*). Every total and subtotal row comes from the pivot engine's
one statement over the model's rows: the average's total is the average over all the trades, a sum's total is the engine's sum. A
year-to-date measure shows a dash in the total row; the answer's warnings say why ([BI_GUIDE.md section 3.2](../guides/BI_GUIDE.md#32-true-totals)).

**You should see** the matrix's totals and subtotals as before (the figures are the same: `BiParityTest`) and a dash, with its reason,
where a measure has no total.

## Next steps

- [PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md) sections 19 to 22: the languages, the harness, approval, explain, what-if, BI's totals.
- [PIVOT_ENGINE.md](../architecture/PIVOT_ENGINE.md) sections 8, 10 and 12: the design.
