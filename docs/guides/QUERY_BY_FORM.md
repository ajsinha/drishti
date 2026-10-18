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
# Query by form

Learn it by doing in [tutorial 3](../tutorials/03-query-by-form.md); this page is the reference.

If you would rather fill in boxes than write a search, type `FORM` and a pack on the command line. The page is a form for the pack's
kinds: one input for each field people search by, each suited to what the field holds. When you press **Search** the form writes the search
for you, shows it, and runs it as any other search ([Search by value](USER_GUIDE.md#search-by-value)), so what you may see is exactly what it was.

```text
FORM trading <GO>
```

Type-ahead helps: `FORM tra` completes to `FORM trading`, and after a space it lists the kinds you may open in that pack
(`FORM trading trade`). Only the packs and kinds you may open are offered. A pack with several kinds shows a row of them under its title;
pick one to switch forms. `FORM <GO>` alone, or the **Forms** icon in the top bar, opens the forms home.

## The forms home

`/forms` (the **Forms** icon in the top bar, then *All forms*; or `FORM <GO>`) lists every pack that is switched on for you and declares a
`form:` section, each with its description and its kinds as links to `/form/<pack>?kind=<kind>` with the number of fields the form has. Only
what you may open is listed: the server leaves out kinds your roles cannot open and packs hidden by the sample-content policy. The
**filter box** (focused when you arrive, except on a phone) narrows packs and kinds as you type; `Esc` clears it. **Recent forms** are the
last eight forms you opened in this browser (kept in browser storage, per person); **Your saved searches** are the searches you saved from forms.
In the top bar the **Forms** icon is lit on `/forms` and on any form. The old address `/form` leads here.

![The trading pack's form for trades: a text field with its operator, an amount, a relative date, choices, a pasted list of ids and the Search button](img/form/01-form.jpg)

## The inputs

| The field holds | The input | What you can ask |
|---|---|---|
| text (a name, a description) | a box, an operator and *Match case* | equals, contains, starts with, matches a regular expression (case is ignored unless you tick *Match case*) |
| a number or an amount | one box, or two for *between* | `=`, between, `>`, `<`, `≥`, `≤`; amounts take `250k`, `1.5m`, `2bn` |
| a date | a date picker, two for *between*, or text for *relative* | on, between, before, after, or a range on the business-day calendar: `last 5 business days`, `next 10 business days`, `last 30 days`, `this week`, `month to date`, `year to date` |
| one of a few values (up to 6: direction, status) | a row of tick boxes, one for each value the data holds | is one of the ticked values |
| one of many values (asset class, product type, book, desk, currency) | **one dropdown the size of a select**, showing "All", the one value, or "3 selected: ABS, CORP_BOND, +1"; open it (`Enter` or `Space`) for a search box (type to filter), *Select all* / *Clear* (they act on the values the search leaves), and a tick box per value (`↓` `↑` move, `Space` ticks, a letter jumps, `Enter` or `Esc` closes). 44 pixels a row on a touch screen | is one of the ticked values; the address is the same as for tick boxes (`v.PRODUCT_TYPE=CORP_BOND&v.PRODUCT_TYPE=GOVT_BOND`) |
| one of very many values (over 300: counterparties, portfolios on a large book) | the values already chosen, ticked, and a box *Type to find among …* (the count of values): typing lists the values that contain the text (at most 50, with *… and N more: type more*), each with a tick box; ticked values stay when you type something else, so several can be chosen | is one of the ticked values; the same address as for tick boxes |
| an identifier | a box to paste a list: spaces, commas or lines | is one of them; `MX-2*` is any id starting `MX-2` |
| yes or no | Any, Yes, No | is |

The values of every list are kept by the server per field, business date and data version, so a form opens without reading the
data again; a load refreshes them, and a masked field's values are never kept.

Leave a field empty and it takes no part. A field marked **slower: not indexed** is not kept by the source as a column, so the documents are read to answer it:
fine for a few thousand, slow for a million. Below the fields choose how to **sort** and how many results to **show**.

## The search it made

![The results: the command the form made with a Copy button, the count, and the table of trades with its column funnels](img/form/02-results.jpg)

The line under the form is the search, in the grammar of [Search by value](USER_GUIDE.md#search-by-value). With **Currency** EUR and USD
ticked and **Quantity** `>= 100m`, on the quick-start server (the demo lakes):

```text
TRD where (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m limit 100
```

It matches 3,415 of the 15,000 trades of the three source systems; the table shows the first 100 (**Show** below the fields).

**Copy** puts it on the clipboard (paste it into the command line or the search box); **Edit as text** opens it in the search box. The table is the
console's table: sort by a heading, filter each column with its funnel ([Filter a table like Excel](USER_GUIDE.md#filter-a-table-like-excel)), page and walk it by keyboard. The key
cell opens the view; **Alt+Left** brings you back to the form with your criteria in place and the results again, because everything you filled in is in the
page's address (`/form/trading?kind=trade&v.CURRENCY=EUR&v.CURRENCY=USD&op.QUANTITY=>=&v.QUANTITY=100m&run=1`): bookmark it or send it.

## Give the results the room: hide the form

![After Search the form folds to one line of what you asked, above the command and the results](img/form/06-form-folded.jpg)

When results arrive the form folds to **one line of your criteria** (`Currency in EUR,USD · Quantity >= 100m`; beyond three criteria `· +2 more`) so the table gets the screen.
Click the line, press **Show form**, or press **Alt+F** to open it again with every box as you left it; **Hide form** (or Alt+F again) folds it. The button
carries `aria-expanded` and `aria-controls`, and on a phone it is a 44 px target. The state is in the page's address (`&form=hidden` or `&form=shown`), so a
link or **Alt+←** from a result returns to the form as it was; and in this tab a form you opened by hand stays open for the next Search. An administrator who
would rather the form stayed open sets `form.collapse_on_submit: false` in `config/application.yaml` (default `true`).

| Key | What it does on the form page |
|---|---|
| `Enter` | Search |
| `Esc` | Clear every field |
| `Ctrl+Enter` | Search from a list box |
| `Alt+F` | Hide the form or show it again |
| `Alt+←` | Back to the form from a result, criteria kept |

## Keep it: name it, watch it, schedule it, send it

Under the results: **Save as named search** (it appears under *Your saved searches* here and on `FORM`), **Watch as a monitor** (the first 50 results, live),
**Schedule a report** (the search delivered as CSV on a schedule, see [Scheduled reports](USER_GUIDE.md#scheduled-reports)) and **Share link** (copies the form's address, which
opens the same form with the same criteria for whoever has the right to see the pack).

## Regular expressions

*matches regex* uses a safe engine: it cannot be made to run for ever, whatever the pattern. It reads classes (`[A-Z]`, `\d`, `\w`), groups, `|`, `* + ? {n,m}`,
`^` and `$`. It refuses what cannot be done in linear time (back-references such as `(a)\1`, look-around such as `(?=x)`, atomic groups, possessive quantifiers, `\b`),
patterns over 200 characters, and anything too large once expanded, with a message that says which and the code `DRS-4010`; a pattern that takes over 50 ms on one value
stops with `DRS-4011`.

![A regular expression the search refused: the message names the back-reference and the code DRS-4010](img/form/04-regex-refused.jpg)

## Keyboard and phone

Tab walks the fields in reading order; **Enter** searches (in the list of ids, **Ctrl+Enter**); **Esc** clears every field. Everything is labelled for a screen reader.
On a phone the fields stack in one column and every control is at least 44 px.

![The form on a phone, 390 px wide](img/form/03-phone.jpg)

## Who decides what the form offers

The pack's author writes the form (`form:` in `pack.yaml`, see the [Pack developer guide](PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind)); a pack that does not gets one
inferred from its search columns and screen. Authors can shape a kind's form in the workbench's [Form tab](SCREEN_DESIGNER.md#the-form-tab).

