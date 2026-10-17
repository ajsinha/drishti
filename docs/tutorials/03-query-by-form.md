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
# Tutorial 3 · Query by form

Find trades by filling in boxes, not by writing a search. You will combine a choice of counterparty, an amount, a date range and a choice of currencies, read the
search the form wrote for you, turn it into a regular expression, and then keep the search: name it, watch it as a live monitor, or schedule it (tutorial 9).

## Time to complete

About 20 minutes.

## Before you start

[Tutorial 1](01-first-15-minutes.md) done (demo lakes built, server and console running, signed in). The form belongs to the `trading` pack, one of the seven the tutorials
switch on, and searches the trades of all three booking systems (ENDUR, CALYPSO, MUREX) at once.

## What you will learn

- the inputs a form offers and what each one asks: text and its operators, amounts (`100m`), dates (between, relative), choices;
- why a long list of values is **one dropdown with tick boxes**, and a short one a row of tick boxes;
- the search the form writes, and how to copy or edit it;
- regular expressions, and what the safe engine refuses and why;
- saving a search under a name and as a live monitor.

## Step 1 · Open the form

**In the UI.** Type `FORM trading trade` and press Enter (or **Views**, **Forms**, **Trading**, then **Trade**).

![The empty form for trades: identity, product, where, money and dates](img/t03/01-empty-form.jpg)

You should see a form headed **Trading** with the kind **Trade** selected, and groups of inputs: **Identity** (trade id, source system, counterparty), **Product** (product
type, product sub type, status, buy / sell), **Where** (currency, legal entity, portfolio, trader), **Money** (quantity) and **Dates** (trade date, maturity date). Each kind of
field has its input:

| The field holds | The input |
|---|---|
| text (a name, a description) | a box, an operator (equals, contains, starts with, matches regex) and *Match case* |
| a number or an amount (`QUANTITY`) | an operator (`≥`, `≤`, between, `=`) and a box; amounts take `250k`, `1.5m`, `2bn` |
| a date (`TRADE_DATE`, `MATURITY_DATE`) | on, between, before, after, or *relative* (`next 10 business days`, `last 30 days`, `month to date`) |
| one of **a few** values (source system, status, buy / sell) | a row of tick boxes |
| one of **many** values (counterparty, product type, currency, portfolio, trader) | **one dropdown** that shows *All*, one value, or `2 selected: EUR, USD` |
| a list of ids (`TRADE_ID`) | a box where you paste ids separated by spaces, commas or lines; `MX-2*` means any id starting `MX-2` (ENDUR ids are numbers) |

Leave a field empty and it takes no part in the search. Below the fields choose how to sort and how many to show. The trading pack's form has no free-text field: its
counterparty is a dropdown of the 24 counterparty ids the data holds. A pack whose kind has text fields (a name, a description) offers the text operators.

**From the command line.** The form is the pack's `form:` (in `config/packs/trading/pack.yaml`), and the server serves it with the values the data holds:

```yaml
form:
  trade:
  - { path: COUNTERPARTY, label: Counterparty, type: enum, group: Identity, order: 3, help: "Type in the list to find a counterparty (CP-SOLARIS)." }
  - { path: QUANTITY, label: Quantity, type: amount, group: Money, order: 12, operators: [">=", "<=", between, "="], help: "Amounts take k, m and bn: 250k, 1.5m, 2bn." }
  - { path: TRADE_DATE, label: Trade date, type: date, group: Dates, order: 13 }
  # ... and the rest
```

```text
$ curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" 'http://localhost:18480/api/v1/forms/trading?kind=trade' \
    | python3 -c "import json,sys; [print(f['path'], f['type'], f['operators'], len(f.get('values') or [])) for f in json.load(sys.stdin)['form']['fields']]"
TRADE_ID id ['in'] 0
SOURCE_SYSTEM enum ['in'] 3
COUNTERPARTY enum ['in'] 24
PRODUCT_TYPE enum ['in'] 130
PRODUCT_SUB_TYPE enum ['in'] 132
TRADE_STATUS enum ['in'] 5
BUY_SELL_CODE enum ['in'] 2
CURRENCY enum ['in'] 7
LEGAL_ENTITY enum ['in'] 4
PORTFOLIO enum ['in'] 30
TRADER_ID enum ['in'] 20
QUANTITY amount ['>=', '<=', 'between', '='] 0
TRADE_DATE date ['on', 'between', 'relative', 'before', 'after'] 0
MATURITY_DATE date ['on', 'between', 'relative', 'before', 'after'] 0
```

## Step 2 · Say what you want

Fill in:

1. **Counterparty**: open the dropdown, type `meridian` in its filter and tick `CP-MERIDIAN-RE`.
2. **Quantity**: operator `≥`, `100m`.
3. **Trade date**: operator *between*, from `2025-01-01` to `2025-12-31`.
4. **Currency**: open the dropdown (click it, or press Enter or Space on it).

![The currency dropdown open: a filter box, Select all and Clear, and a tick box for each value](img/t03/03-checkbox-dropdown.jpg)

You should see, inside the dropdown, a box to filter the values as you type, **Select all** and **Clear** (they act on the values the filter leaves), and a tick box for each of
the seven currencies (`AUD`, `CAD`, `CHF`, `EUR`, `GBP`, `JPY`, `USD`). Tick `EUR` and `USD`. The arrow keys move, Space ticks, a letter jumps to a value, Enter or Esc closes.
When it is closed:

![The form filled in: counterparty CP-MERIDIAN-RE, quantity at least 100m, a year of trade dates, currency 2 selected: EUR, USD](img/t03/02-filled.jpg)

You should see the dropdown read `2 selected: EUR, USD` and your other boxes filled as above. Press **Search** (or Enter).

## Step 3 · Read the search it wrote

![The results: the form folded to one line, the command the form made, the count and the table](img/t03/04-results.jpg)

You should see the form fold to **one line of your criteria** (`Counterparty in CP-MERIDIAN-RE · Currency in EUR,USD · Quantity >= 100m · +1 more`) so the table gets the room.
Under it is **the search this form made**, in the grammar of Search by value:

```text
TRD where COUNTERPARTY = 'CP-MERIDIAN-RE' and (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m and (TRADE_DATE >= '2025-01-01' and TRADE_DATE < '2026-01-01') limit 100
```

then the line `65 of 15000 trades match`, and the table (CALYPSO, ENDUR and MUREX trades together; `CLY-3000041` first): click a heading to sort, a funnel to filter a
column, an id to open the trade. **Copy** puts the command on the clipboard (paste it into the command line); **Edit as text** opens it in the search box. Click the one-line
summary, press **Show form** or `Alt+F` to unfold the form with every box as you left it. Everything you filled in is in the page's address, so bookmark it or send it to a
colleague; `Alt+←` from a trade returns here with the results:

```text
/form/trading?kind=trade&op.COUNTERPARTY=in&v.COUNTERPARTY=CP-MERIDIAN-RE&op.CURRENCY=in&v.CURRENCY=EUR&v.CURRENCY=USD&op.QUANTITY=%3E%3D&v.QUANTITY=100m&op.TRADE_DATE=between&v.TRADE_DATE=2025-01-01&v2.TRADE_DATE=2025-12-31&run=1
```

**From the command line.** The server turns the same criteria into the command, and the command into the answer (the page makes exactly these two calls):

```text
$ curl -s -X POST -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' http://localhost:18480/api/v1/forms/trading/trade/compile -d '{"limit": 100, "criteria": [
    {"path": "COUNTERPARTY", "op": "in", "values": ["CP-MERIDIAN-RE"]}, {"path": "CURRENCY", "op": "in", "values": ["EUR", "USD"]},
    {"path": "QUANTITY", "op": ">=", "values": ["100m"]}, {"path": "TRADE_DATE", "op": "between", "values": ["2025-01-01", "2025-12-31"]}]}'
{"command": "TRD where COUNTERPARTY = 'CP-MERIDIAN-RE' and (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m and (TRADE_DATE >= '2025-01-01' and TRADE_DATE < '2026-01-01') limit 100"}
$ curl -s -G -H "Authorization: Bearer $DRISHTI_TOKEN" http://localhost:18480/api/v1/search --data-urlencode "q=TRD where COUNTERPARTY = 'CP-MERIDIAN-RE' and ..." \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['matched'], 'of', d['scanned'], 'trades match')"
65 of 15000 trades match
```

## Step 4 · A regular expression

Press **Edit as text**: the command opens in the search box. Replace the counterparty condition with a regular expression over the field, and drop the rest:

```text
TRD where matches(COUNTERPARTY, '^CP-(MERIDIAN|NORTHBRIDGE)$') limit 100
```

![COUNTERPARTY matches ^CP-(MERIDIAN|NORTHBRIDGE)$: the command with matches(), the count and the table](img/t03/06-regex.jpg)

You should see `1200 of 15000 trades match`: the trades of `CP-MERIDIAN` and `CP-NORTHBRIDGE` (not `CP-MERIDIAN-RE`: the `$` anchors the end). On a form with a text field the
same is the operator *matches regex*. The regex engine is a safe one: it cannot run for ever whatever the pattern. It reads classes (`[A-Z]`, `\d`), groups, `|`,
`* + ? {n,m}`, `^` and `$`. Try a back-reference, `TRD where matches(COUNTERPARTY, '(a)\1')`:

![DRS-4010: back-references are not offered (they cannot be matched in linear time)](img/t03/07-regex-refused.jpg)

You should see a message in an amber box: `DRS-4010 back-references are not offered (they cannot be matched in linear time)`. Back-references, look-around and `\b` are refused
for that reason, patterns over 200 characters too (`DRS-4010`), and a pattern that takes over 50 ms on one value stops with `DRS-4011`.

**From the command line:**

```text
$ curl -s -G -H "Authorization: Bearer $DRISHTI_TOKEN" http://localhost:18480/api/v1/search --data-urlencode "q=TRD where matches(COUNTERPARTY, '(a)\1') limit 100"
{"type":"about:blank","title":"bad search","status":400,"detail":"DRS-4010 back-references are not offered (they cannot be matched in linear time)", ...,"code":"DRS-4010"}
```

## Step 5 · Keep the search

Go back to the results of Step 3. Under the table are four ways to keep it.

![Save as named search, Watch as a monitor, Schedule a report and Share link](img/t03/05-save-and-watch.jpg)

You should see a box with a button **Watch as a monitor**, a link **Schedule a report**, a box **name this search** with **Save as named search**, and **Share link**. Type
`Meridian RE EUR and USD 2025` in the name box and press **Save as named search**.

![Saved. Find it under your saved searches](img/t03/08-saved-search.jpg)

You should see `Saved. Find it under your saved searches.` The search now appears under *Your saved searches* on the forms home and under the form, with its criteria. Next, name the
monitor `Meridian RE big trades` and press **Watch as a monitor**.

![The monitor: one row for each trade, each with its figures](img/t03/09-monitor.jpg)

You should see a monitor page: a list with one card for each of the first 50 results, each showing that trade's strip (source system, side, notional, status, portfolio). The
monitor is kept under your account. **Add an entity** adds a row (type `TRD MX-20000001`), **×** removes one, **Delete monitor** removes the list. **Share link** copies the
form's address, which opens the same form with the same criteria for anyone who may see the pack. **Schedule a report** is tutorial 9. (On the demo lakes the cards change when
a day is loaded; a live source would make them tick.)

## Next steps

- [Tutorial 9 · Reports](09-reports.md) runs a search on a schedule.
- [USER_GUIDE.md › Search with a form](../guides/USER_GUIDE.md#search-with-a-form-form-pack) is the reference, including keyboard and phone use; the pack's author decides what the form offers
  ([PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind)).

The form has no `drishti.py` command of its own: the search it writes is an ordinary command, which you can paste into the command line, send to `/api/v1/search` (as above) or put in a scheduled report.

## If something goes wrong

| You see | Meaning |
|---|---|
| `0 of 15000 trades match` | the conditions together match nothing: loosen one (clear a field with `Esc` and search again) |
| *slower: not indexed* beside a field | the source does not keep it as a column, so documents are read to answer: fine for thousands, slow for millions |
| `DRS-4004 no trade has a field 'mtm'` | the search names a field the trades do not have: names are the document's own, in UPPER_SNAKE (`QUANTITY`, `COUNTERPARTY`) |
| `DRS-4010` or `DRS-4011` | the regular expression is refused or too slow; simplify it |
| a value is missing from a dropdown | the lists are built from the data the pack knows; ask the pack's author to add it |
