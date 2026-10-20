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
# Distribution: export, subscriptions, alerts and embedding

Rūpaka phase 7 ([RUPAKA.md section 20.6](../architecture/RUPAKA.md#206-phase-7-part-1-as-built)). Part 1: two things leave Drishti as files and mail: an **export** (a BI report, a page, one
visual, or a Sutra view, saved as PDF, a picture, an Excel workbook or CSV) and a **subscription** (the same content sent by email on a schedule, or after the day's data lands).
Part 2 (section 4 here): **alerts** on a number, and notifications from a board tile, to the inbox, email and the chat bridges.
Part 3 (section 5): a report **embedded** in another web application (`<drishti-report>`, with `<drishti-about>` and `<drishti-search>`), and the report's own phone layout ([REPORTS.md](REPORTS.md#14-phone-and-tablet-layouts)).
Wave D: charts in Excel exports (section 1), a written summary at the top of a subscription's email (section 2), retries of a failed alert delivery (section 4.6), and webhook and text-message channels for alerts and subscriptions (section 6).
The walk-throughs with pictures are [tutorial 11](../tutorials/11-bi-that-runs-itself.md) (export, subscribe and alerts); this is the reference.

**One rule runs through all of it: the file or the mail is made for the person who gets it.** The numbers are asked of the data engine *as that person*, so their row rules and their
masks apply; nothing is copied from the sender's screen. A desk head's export holds her desk; an analyst whose column is masked gets the mask. **A masked
cell reads `•••` in every format** (CSV, Excel, PDF, PNG and the mail's body), as on the screen.

## 1. Export

| Format | What it is | Made from |
|---|---|---|
| **Excel** (`.xlsx`) | a sheet `About` (title, as-of date, the filters, how many rows each sheet holds) and **one sheet per visual** (BI) or per table-like panel (a view); a bold header row that stays in view when you scroll; numbers are numbers (whole, decimal), dates are dates, text is text (never a formula, whatever it starts with); a **chart visual** also gets a **native Excel chart** next to its data (below) | the tables |
| **CSV** | the same tables; with several, each under its title and a blank line between; UTF-8 with a byte-order mark; text a spreadsheet would run as a formula gets a leading apostrophe | the tables |
| **PDF** | landscape A4 (a setting), **paged**: no table row is split by a page edge, a table that continues repeats its header row, a running header (title; as-of date) and a footer (who it was made for, when, "Page n of m") on every page | the tables and bars |
| **PNG** | one picture, as tall as the content needs (to a limit) | the tables and bars |

For a **BI report** the PDF and PNG draw each visual as what it holds: a *card* as its value, a *chart* (bar, column, line, area, pie, donut, waterfall, treemap) as horizontal bars of its first
measure above its data, every other type (table, matrix, a 2.1 `perspective-*` grid, gauge, KPI, heatmap, scatter, combo) as its data table. Static visuals (text, image, button) and slicers are not exported: the
slicers' choices are written in the header as the filters. A **Sutra view** is drawn the way a share's picture is (the panels as the view draws them, charts included) with the sentence "Exported by …"
in the band and across the page; Excel and CSV hold its table-like panels (tables, fields, pivots, metrics, tabs); a note says which graphic panels they leave out.

**Charts in the workbook.** A bar, column, line, area, pie or donut visual is written with a real Excel chart (DrawingML parts in the .xlsx, drawn by Excel and LibreOffice, editable there) on its data sheet, to the right
of the table, reading the sheet's own cells: the first column is the category (a date column is a date axis), each numeric column after it is a series (at most 6; a pie or donut draws the first), and at most 500
categories are plotted. A *bar* is horizontal, a *column* vertical; every other chart type (waterfall, treemap, funnel, histogram, ...) is drawn as columns, and a map, a gauge, a table or a card gets the data
sheet alone. **A column masked for the reader is text (`•••`) in the sheet, so it is never a series**; a visual with nothing numeric left to plot has no chart. The chart is part of the file the reader's own render makes, so
each person of a subscription gets charts of their own figures. The chart sheet is set to print on one page's width. This is the sheet of one visual (*MTM by desk*) of the export in LibreOffice:

![A native Excel chart in the export, opened in LibreOffice](img/bi-dist/export-excel-chart.jpg)

**Where.** On a BI report: the *Export* bar under the page tabs (a choice of *this page* or one visual, then PDF, PNG, Excel, CSV). On a view: *Export* in the view's header. Both pass the state of the
screen: a report's slicers, selected marks, drill path, carried values and business date; a view's control values and column filters (`ctl.*`, `f.*`) and its business date.

![The export bar on a report](img/bi-dist/report-export-bar.png)

![The Export menu on a view](img/bi-dist/view-export-menu.png)

This is what the PNG of the sample page ("Trades overview", as the person who sees everything) looks like, unedited:

![The PNG of a report page](img/bi-dist/export-page.png)

### Real output

The Excel file of that page: `Trades overview.xlsx`, 6362 bytes, sheets

```
  1. About
  2. Trades
  3. Notional
  4. Mark to market
  5. Average MTM per trade
  6. MTM by desk (click a bar to fil
  7. Notional by trade date
  8. Trades by desk and currency
```

and the sheet `MTM by desk (click a bar to fil` (sheet names are cut at Excel's 31 characters) holds `desk` and `MTM` in bold, then `DESK-EQD 425000`, `DESK-FI -75000`, `DESK-FX -575000` as numbers. The CSV
of that one visual, `Trades overview - MTM by desk -click a bar to filter the page-.csv`:

```
desk,MTM
DESK-EQD,425000
DESK-FI,-75000
DESK-FX,-575000
```

The PDF is 2938 bytes on 2 pages. A view's file is named by its title and date, `FX - FX forward BBG-1008000 2026-10-08.xlsx`.

### Limits (settings)

`drishti.distribution.export.*` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtidistribution-export-and-subscriptions)): rows per table in Excel and CSV (`max-rows`, 50,000: a table or matrix is read whole up to
there, past the 5,000 rows one answer to the screen holds; a table is cut there and its sheet says so, and any other visual the engine cut says *The first
N of M rows*), rows drawn per table in a PDF or PNG (`pdf-max-rows`, 500: the picture is for reading, Excel holds the rest; the table under a chart is cut at `pdf-chart-rows`, 15, with a line saying how many more rows are in the Excel/CSV export. A **line or area chart** is drawn as lines over the whole series, sampled to 200 points; the other chart types are drawn as bars of their first 12 rows), the largest file (`max-bytes`, 25 MB), the longest export (`timeout`, 60 s),
visuals per export (`max-visuals`, 40), the layout width, orientation and the tallest PNG. Over a limit the answer is `422 DRS-9060` and says which setting.

### The API

`POST /api/v1/export/{format}` (`pdf`, `png`, `xlsx`, `csv`), body `{type: "report", id, page?, visual?, state?, date?}` or `{type: "view", id: "kind/id", date?, view?}`. The answer is the file, with
`Content-Disposition: attachment` and `X-Export-Rows`. The console serves it at `/dist/bi/<report id>.<format>?page=&visual=&state=<JSON>&date=` and `/dist/view/<kind>/<id>.<format>` (with the page's
`ctl.*` and `f.*` query). Every export is audited (`export`, who, what, rows, bytes). See [API_GUIDE.md](API_GUIDE.md#export-and-subscriptions-apiv1).

## 2. Subscriptions

A subscription says **what** (a report, a page or one visual, with the view state; or a view with its controls), **when**, **in what form**, and **to whom**.

![The Subscribe form](img/bi-dist/subscribe-form.png)

| Field | Meaning |
|---|---|
| Name | 1 to 64 letters, digits, spaces, dots, dashes, underscores; yours alone |
| When | the schedule, below |
| Data for | `today` (the current business date), `previous` (the business day before: the pack calendar's), or `load` (the business date of the load that triggered an `after-load` run) |
| Send as | `html` (the tables are in the email), `pdf` or `xlsx` (the file is attached; the body says what it holds) |
| To | user names and `role:NAME` (everyone with the role, today: a role is the group). **Never an email address**: the form refuses it (`DRS-9061`) |
| View state | taken from the report or view you pressed Subscribe on: slicers, selections, drill path, carried values; a view's controls and filters |

### Schedules

Everything a [scheduled report](SCHEDULED_REPORTS.md#scheduled-reports) takes (`daily HH:MM`, `weekdays HH:MM`, `business-days HH:MM`, `hourly`, `cron` and six fields), and:

| Text | Runs |
|---|---|
| `weekly mon 07:30` | on that weekday (`mon` to `sun`) |
| `monthly 1 07:30` | on that day of the month (`1` to `31`; a short month uses its last day) |
| `monthly last 18:00` | on the last day of the month |
| `weekly …` or `monthly …` followed by `business` | business-day aware: a day that is a **holiday in the server's calendar** (`drishti.business-date.calendar`, for example `USNY`) moves to the next business day; `monthly last 18:00 business` is the **last business day** of the month |
| `after-load` or `after-load TRD` | not at a time but after the data lands: shortly (`load-delay`, 5 s, so the BI layers rebuild first) after a load **finished ready** (for the kind named, or any) |

Times are in the business-date zone. `POST /api/v1/subscriptions/preview` shows the next three runs of a schedule text.

### Each recipient gets their own

For every recipient the server builds that person's rights (their roles as they are *now*) and asks the report engine as them. The sender never sees, and the mail never carries, a figure the recipient may
not see. Look at what the fake SMTP of the tests received after one *Run now* of the same subscription, for three people.

Hana is a desk head (row rule: her desk) and sees one desk:

```
Trades overview · 2026-10-08

Trades overview
Page: Overview · Data as of 2026-10-08
Filters: none beyond the report's own

Trades
Trades: 20

Notional
Notional: 79,000,000

Mark to market
MTM: -575,000

Average MTM per trade
Average MTM per trade: -28,750

MTM by desk (click a bar to filter the page)
desk | MTM
DESK-FX | -575,000

Notional by trade date
tradeDate | Notional
2026-10-08 | 79,000,000

Trades by desk and currency
desk | currency | Trades | Notional | MTM
DESK-FX | USD | 20 | 79,000,000 | -575,000
```

Ann is an analyst: every desk, but `mtm` is masked for her role:

```
Trades overview · 2026-10-08

Trades overview
Page: Overview · Data as of 2026-10-08
Filters: none beyond the report's own

Trades
Trades: 60

Notional
Notional: 234,000,000

Mark to market
Masked for you: MTM.
MTM: •••

Average MTM per trade
Masked for you: Average MTM per trade.
Average MTM per trade: •••

MTM by desk (click a bar to filter the page)
Not available: DRS-9021 'MTM' is masked for you: it cannot be sorted on
No data

Notional by trade date
tradeDate | Notional
2026-10-08 | 234,000,000

Trades by desk and currency
Masked for you: MTM.
desk | currency | Trades | Notional | MTM
DESK-EQD | GBP | 20 | 77,000,000 | 
DESK-FI | EUR | 20 | 78,000,000 | 
DESK-FX | USD | 20 | 79,000,000 |
```

and the one for Rita (everything) has `MTM by desk` with the three desks and `-225,000`. The same mails as pictures:

![Rita's mail](img/bi-dist/mail-rita.png)

![Hana's mail](img/bi-dist/mail-hana.png)

![Ann's mail](img/bi-dist/mail-ann.png)

The headers are the product's usual: `From: drishti@desk.test`, `Subject: Drishti: Trades overview · 2026-10-08`, `Auto-Submitted: auto-generated`, and a `Message-ID` of its own per mail. A subscription that attaches
a PDF sends a body like this and the file `Trades overview.pdf` (2942 bytes):

```
Trades overview · 2026-10-08

Trades overview
Page: Overview · Data as of 2026-10-08
Filters: none beyond the report's own

The report is attached: Trades overview.pdf
- Trades: 1 rows
- Notional: 1 rows
- Mark to market: 1 rows
- Average MTM per trade: 1 rows
- MTM by desk (click a bar to filter the page): 3 rows
- Notional by trade date: 1 rows
- Trades by desk and currency: 3 rows

Open it in Drishti:
http://127.0.0.1:17950/bi/r/file%3Areport%3Atrades-overview/overview

You are receiving this because you are a recipient of a subscription in Drishti. The figures are the ones you are entitled to see; they are rendered for you, not copied from the sender.
Ask the owner of the subscription, o
```

* **A recipient who cannot open the target** (the role lost the report, the kind, a workspace report not shared with them) is **skipped**, with the reason in
  the history in words (*rita may not open the report: it is not shared with them, or their roles do not reach it*); they are not mailed a refusal.
* **A recipient with no email address**, or a disabled user named in the subscription, is skipped and listed with the reason (*the account is disabled*);
  a role's disabled members are not among its members.
* **Outside addresses are refused.** A person who is not a user has no rights, so no render can be made for them without handing over the owner's view; creating or changing a subscription with an address
  fails with `DRS-9061` and says to name a user or `role:NAME`. (There was an administrator setting for this; it is gone.) A subscription saved earlier with an address **keeps it, but the address is skipped at
  every send** with the reason (`outside address: subscriptions go to users only`) in the history; edit the subscription to remove it.
* **Who may subscribe whom.** Anyone may subscribe **themselves**. Subscribing other people or a role needs a BI author (power `bi-author`) or an administrator (`DRS-9061` otherwise). The subscriber
  must be able to open the target themselves.

### Statements: one per recipient (2.6.0)

A subscription to a **statement page** (a page with `layout: statement`, [STATEMENTS.md](STATEMENTS.md)) in `pdf` or `xlsx` **bursts** it: each recipient gets
their own statement, laid out page by page and made **as them**, so two recipients' statements differ only by their masks and row rules (and their name in
the header). The statement's `period` is worked out from the run's business date (*Data for*: `previous` on the first of the month makes a statement of the
month before), and the subscription's view state may carry the model's parameters (`target.state.params`). The file is named after the statement, its period
and the recipient (*Monthly statement September 2026 hana.pdf*); each delivery in the history has `statement: true`, its `pages` and its `file`. The
acceptance is a monthly statement burst to ten recipients of two roles, every PDF checked for its page count and its header on every page
(`StatementApiTest`, [STATEMENTS.md section 6](STATEMENTS.md#6-pdf-excel-and-bursting-through-subscriptions)). A table sorted by a measure that is masked for a
recipient is drawn for them in the query's own order (its order would tell the masked figures), with a note above it, and their delivery says so in
`notes` (*Positions: Not sorted: 'MTM' is masked for you …*). An Excel statement carries the same notes as the PDF (its *About* sheet and beside each
table), and the mail's subject ends with the page count (*· 1 page*, *· 3 pages*). The **Subscribe** form says so when it opens on a statement page
(*This is a statement page: each recipient gets one statement of their own*) and offers a PDF first; on any other report page it says that a statement
page is sent as one statement per person.

An export of a statement page (**Export → PDF** or **Excel** on the page, `POST /api/v1/export/pdf`) is the statement too, made for you.

### A written summary at the top (`narrative`)

The tick-box *Open the email with a short written summary* appears on the form **only when AI is switched on** for this server (`drishti.ai.enabled`); without AI there is nothing to tick (and `narrative: true` is refused). With it, tick it (or send `narrative: true`). Each recipient's email then opens with two or three sentences by an AI model that word **their own** figures: the model is called **as that recipient**
under the feature `subscription-narrative`, from the tables as that recipient's render holds them with every masked column left out, and **only when the AI policy lets the model see values**
(`drishti.bi.ai.values: aggregates`, [BI_AI.md section 9](BI_AI.md#9-a-written-summary-at-the-top-of-a-subscriptions-email)). Otherwise (labels-only, the AI layer off, the policy refusing the recipient, the provider failing) the mail
is sent as usual **without** it and the delivery says `summary: skipped: <why>` in the history. The summary is labelled as AI-written.

### Running, pausing, history

![Subscriptions](img/bi-dist/subscriptions-list.png)

*Run now* makes and sends everything at once (the status line says `Morning desk pack: 3 sent`). *Pause* stops the schedule (a paused subscription has no next run); *Resume* schedules it from **now**: it does not
catch up on what it missed. *History* keeps the last runs (`keep-runs`, 30; the three newest with every recipient's delivery status, older ones their counts). A run's status is `ok`, `partial`
(some failed), `retrying`, `failed`, or `skipped` (nobody could be sent anything). Everything is audited (`subscription.save`, `.run`, `.pause`, `.resume`, `.delete`). Administrators see everyone's at
**Reports, All subscriptions** (`/reports/all`) and can pause, resume or delete any; only the owner can change what one sends.

### Retries and failures

A subscription can also **post to webhooks** and **text** its recipients (section 6), and can open each email with a **written summary** by an AI model (below); both are checked at save and recorded in the run's history, a webhook post and a text as deliveries of their own (`webhook:NAME`, `sms:USER`) that retry like a mail.

A mail that could not be sent (the server unreachable, a timeout) is **retried with the shared retry policy** (`drishti.retry.distribution.*`, falling back to `drishti.retry.*`: 5 s, doubling, to 5 min, 20 % jitter)
up to `max-attempts` (4) in all; the delivery says `retry`, its attempt count and when it will next try, and the history shows each outcome. A refused address or an SMTP 5xx is **permanent** at once.
Mail goes through the server's mail settings (`spring.mail.*`, `drishti.collab.email.from`, `drishti.collab.console-url`): the same as share and comment mail. With no SMTP host every delivery fails with
"no SMTP host: set spring.mail.host" and is not retried.

### Where it is kept

A subscription is a document in the owner's preferences (namespace `subscriptions`, with the preferences' size limit of 64 KB: hence history detail for the newest runs only, at most 40 deliveries a run in
detail, problems first). `per-user` (20) caps how many one person keeps (the preference store allows 50 of a kind). Run the scheduler on **one** server of a group (`drishti.distribution.subscriptions.enabled`).

## 3. The list in the console

**Reports** in the top bar leads to `/reports`: your scheduled reports (CSV to a folder or webhook) and your subscriptions in **one list**, by name, with what each is, when it runs, who gets it, its next run
(UTC) and its last run. Existing scheduled reports are unchanged. *Subscribe* is on every BI report (the Export bar) and every view (the header).

## 4. Alerts on numbers

An **alert** watches one number and tells you when it crosses a limit, moves too far or does not arrive. **The number is read as you**: with your row rules and your masks, by the same engine call the screen
makes. An alert never judges, and never says, a figure you may not see; a number that is masked for you is not judged at all.

![Alert me on a card](img/bi-alerts/report-alert-me.png)

*Alert me* (the bell in the corner of a visual's frame) opens the form with the report, the page, the visual and the filters on your screen filled in:

![The alert form](img/bi-alerts/alert-form.png)

On a phone the form is one column, with finger-sized controls:

![The alert form on a phone, 390 px wide](img/bi-alerts/alert-form-phone-390.png)

### 4.1 What can be watched

| Source | You give | The number is | Live (on change) |
|---|---|---|---|
| **A visual of a BI report** (`type: visual`) | `report`, `page`, `visual`, optionally `measure` (a column; the first number column when blank), `reduce` and `state` (slicers, selections, drill path: the filters you had on the screen; the date is never kept, an alert follows the data's own date) | the column of the visual's answer. A card or KPI is one row; for a visual with many rows `reduce` says how they become one number: `first` (default), `last`, `sum`, `min`, `max`, `avg`, `count` | when the report's dataset has a live table |
| **A measure of a dataset** (`type: dataset`) | `dataset`, `measure`, optionally `filters` (`[{field, op, values}]`, the same as a query) | the measure over the latest business date, no grouping | when the dataset has a live table |
| **A field of a Sutra entity** (`type: field`) | `kind`, `id`, `field` (a path such as `mtm` or `$.limits.utilisation`, or any Rachana expression over the entity) | the field, read through your field masks | when the source pushes changes |
| **A board tile** (from the board's YAML; section 4.8) | the tile | its colour as a level: 0 normal, 1 amber, 2 red | when the report's dataset has a live table |

*Alert me* is on every visual that shows numbers (not text, image, button or slicer), and in the dialog that opens when you click a number on a view (*Alert me*, beside *Discuss*): that is the Sutra
field, with its path. A number that is a **metric tile** or a cell of the view's summary strip is a field too.

#### Python measures in alerts

The `measure` of a dataset source may be a **Python measure** (a dataset measure written `{ python: <item>, inputs, by }`: [PYTHON_BI.md](PYTHON_BI.md)); so may the visual of a visual source, when it is a **python visual** that shows one. It is read **for each person it is judged for**, in the console's sandbox, on the alert's schedule, after a load or by hand, like any other number:

1. the server builds **that person's rows** (their row rules and masks applied by the engine) for the measure's `inputs` and `by` columns, up to `drishti.bi.report.python-rows`;
2. it writes the Python item and the rows to a scratch folder and starts the console's runner (`transform_cli.py`, in `measure` mode: the same sandbox as a report's Python: no network, a scratch folder, CPU, memory and time limits), the way it runs a Transform (`drishti.bi.transform.python`, `.runner`, `.timeout`, `.work-dir`; [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibitransform--python-transforms-and-composed-datasets));
3. the item's number for each group comes back and is reduced to **one number**: for a dataset source the whole input is one group (no grouping); for a visual source `reduce` (`first` by default; `sum`, `min`, `max`, `avg`, `last`, `count`) is applied to the groups, as for any visual with many rows.

* A column **masked for the person** makes the reading *masked*: nothing is run and no figure is shown. The alert never judges a figure the person cannot see.
  An alert whose number is masked for its owner **when it is saved** is refused (`422`, *the value this alert watches (MTM) is masked for you, so it could
  never be read: an alert only watches a value its owner may see*); a mask added later makes its readings *masked*, as above.
* A visual or model query that answers **several rows** needs the number chosen: one cell (a `state` that filters it to one row) or an aggregate
  (`source.reduce`). Saving one without `reduce` is refused: *this visual answers 3 rows, so say which number to watch: one cell (filter it to one row with
  source.state) or an aggregate of the rows (source.reduce: sum, min, max, avg, count, first or last)*.
* A run that fails (the code raised, a limit, the sandbox refused something, no Python on the machine the server runs on, the runner switched off) is an **error reading** with the DRS code and its words in the history; an error reading never fires and never clears.
* A python visual that draws a **chart** (no `measure` in its wells) has no number to watch: the alert reads as an error that says to watch a Python measure.
* *Live* is as for the dataset behind it. A Python alert is not cheap (it starts a process for each person each time): prefer `schedule` or `after-load` to `live`.

```
PUT /api/v1/me/measure-alerts/Vol
{"title": "Desk P&L volatility", "source": {"type": "dataset", "dataset": "file:dataset:trades-basic", "measure": "Desk P&L volatility"},
 "condition": {"type": "threshold", "op": "above", "value": 5000}, "trigger": {"mode": "after-load"}}
```

A **board tile** that is a Python visual has no threshold alert of its own ([BOARDS.md](BOARDS.md#python-tiles)); an alert like the one above is how to be told. [PYTHON_BI.md section 16](PYTHON_BI.md#16-python-in-alerts-and-on-boards) has the rest, and the tests that read it against the real runner.

### 4.2 When it fires: the condition

| Condition | Keys | Fires when |
|---|---|---|
| **Threshold** | `op`: `above`, `below`, `between`, `outside`; `value` (the limit, or the low edge); `high` (the high edge, for `between` and `outside`) | the number is above (`>`) or below (`<`) the limit; **between** `value` and `high` (edges included); **outside** them (`<` low or `>` high) |
| **Change** | `mode`: `pct` or `abs`; `op`: `rises`, `falls` or `moves`; `value` (above zero); `against`: `previous-run` or `previous-day` | the number rose by at least `value` (`rises`), fell by at least `value` (`falls`) or did either (`moves`). `pct` is `(now - before) / abs(before) x 100`; `previous-run` compares with what the alert read the last time it looked, `previous-day` with the same source on the previous business day of the data's date |
| **No data** | `by`: a time of day (`09:00`), in the business-date zone | on a business day, from `by` on, the source shows no data for today's business date: no rows, or an as-of date before it. It clears when data lands |

A change with nothing to compare with (the first look, or a before of zero for a percentage) does not fire; the history says so. A reading that **cannot be judged** (masked for you, no data yet, the
source gave an error) never fires and never clears an alert that is active.

### 4.3 When it looks: the trigger

| Trigger | Keys | The alert looks |
|---|---|---|
| **schedule** (default `hourly`) | `schedule`: `hourly`, `daily 07:00`, `weekdays …`, `business-days 07:30`, `weekly mon 07:30`, `monthly last 18:00 business`, `cron …` (the words of [subscriptions](#schedules)) | at those times, in the business-date zone, holiday-aware where the words say so. After a restart it is scheduled from now (it does not catch up) |
| **after-load** | `kind` (optional): only a load of this kind | `load-delay` (5 s) after a data load announced ready ([DATA_LOADS.md](DATA_LOADS.md)), so the BI layers have rebuilt. A "no data" alert is looked at after **every** load, whatever its trigger |
| **live** | `debounce` (default 10 s, at least 1 s) | when the source **changes**, once the changes have been quiet for the debounce; and at least every `live-max-wait` (2 min) while changes never stop. Needs a live source (the table above), else the save is refused. A live alert keeps its live table open: it is read by the engine as long as the alert exists |

A new or edited alert is also looked at **once, when you save it**: an alert whose condition already holds fires then (a change alert only takes its starting point). *Look now* (the list and the edit page)
looks at once. Two looks of one alert for one person closer than `min-gap` (5 s) are one.

### 4.4 No flapping: armed, active, hysteresis, cooldown

An alert is a small state machine for each person it is judged for.

| State | Meaning |
|---|---|
| **armed** | watching; nothing is wrong |
| **active** | it fired and the number has not come back yet; it does **not** fire again while active |
| **paused** | switched off by you (or an administrator); nothing is looked at. *Resume* arms it afresh |

* **Hysteresis** (a number, default 0): once active, the alert re-arms only when the number is back inside the limit by at least this much. `above 100` with hysteresis `10` fires at 101, stays active at 95,
  and re-arms at 90. For `change` it is the same margin on the size of the change; for `between` and `outside` the range is widened or narrowed by it.
* **Cooldown** (default `drishti.alerts.measure.cooldown`, 30 min; `0` for none): after it fires, it will not fire again for this person within the cooldown, even if it re-armed. A breach inside the cooldown is
  written to the history as **held** (once) and fires when the cooldown ends **if the condition still holds**.

### 4.5 Each person is judged on their own number

An alert is **evaluated as the person it is for**: for your own alert, you. The dataset engine applies your row rules and your masks; a Sutra field is read through your field masks.

* A number **masked for you** is a *masked* reading: the alert records `skipped: masked for you, so it cannot be judged` once, and never fires, never clears, and never shows a figure. *Check now* in the form says so.
* A visual or dataset you can **no longer open** is an error reading; nothing fires.
* The message (inbox, mail, history) carries **your** number, formatted with thousands separators: `MTM floor — now -725,000 (below -300,000)`. A chat bridge post carries **no number** at all.

### 4.6 Delivery

| Channel | What | Rules |
|---|---|---|
| **Inbox** (always on unless you turn it off) | a row of type `alert` in your inbox (the *Alerts* tab) and a toast and a count on the bell, live. The row is the one line, cut to 100 characters (the full text is in the history), and opens what the alert is about | the same inbox as shares ([COLLABORATION.md](../architecture/COLLABORATION.md)) |
| **Email** | one mail to **the address on your account**, `Drishti alert: <title>` | users only: an alert cannot name an address. No address, no mail (the history says `skipped: no email address on file`). **A send that fails is tried again** by the shared retry policy (`drishti.retry.alerts.*`; `drishti.alerts.measure.max-attempts`, 4 in all): the history says `retrying: attempt 1 of 4 failed (connection refused); next try in 5 s`, then `sent (attempt 3 of 4)` or `failed after 4 attempts: …` (and `emailAttempts: 3`); a refused address is `failed (permanent): … (not retried)`. A pending retry is in memory, so a restart drops it. Off for the server with `drishti.alerts.measure.email` |
| **Webhook** (`delivery.webhooks: [name]`) | a signed JSON post to a webhook an administrator defined | section 6. One post per alert per cooldown (a minute at least), not one per person; it names the alert, its severity, what it watches and the condition and where it opens, **never a number**. Retried like the email; `webhook:NAME` in the history |
| **Text** (`delivery.sms: true`) | a text to **your own** number | section 6. The title, severity and a link; a figure only if the administrator set `sms.include-values`. Retried like the email; `sms` in the history |
| **Chat bridges** | a post to every bridge whose route lists the event `alert` and covers the kind: a Sutra kind for a field alert, `bi` for everything else | the post names the alert, what it watches and the condition, the owner and a link, **never a number**, because a bridge is a room, not a person. At most one post per alert per cooldown (a minute at least), however many people it fired for. Configure with `events: [alert]` in a route ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishticollab--share-with-a-note-and-the-inbox)); off with `drishti.alerts.measure.bridges` |

The **history** (on the edit page; the last `keep-history`, 50) has an entry for each firing, clearing, held breach and skipped reason, with the time, what triggered it (`saved`, `schedule`, `load`, `live`, `by hand`,
`resumed`), the number, the message, and what each channel did:

![History](img/bi-alerts/alert-history.png)

and **the inbox** has the row, rendered for you when you read it:

![The inbox, Alerts tab](img/bi-alerts/inbox-alerts.png)

The audit log has `alert.save`, `alert.delete`, `alert.pause`, `alert.resume` and `alert.fire` (who, which alert, what triggered it; no number).

### 4.7 Where it is kept, and limits

An alert is a document in the owner's preferences (namespace `measure-alerts`, 64 KB, so the history is capped), with its state for each person and its history. `per-user` (20) caps how many one person
keeps. Alerts are looked at by the server that runs the scheduler: with several servers, turn `drishti.alerts.measure.enabled` off on all but one. The whole list is on the **Alerts** page (`/alerts`:
*Alerts on numbers*, beside the older rules on one entity, which are unchanged), linked from **Reports** in the top bar and from the inbox. The old per-entity rules ([USER_GUIDE.md](USER_GUIDE.md#alerts)) stay: they watch an
expression on every change of one entity; an alert on a number adds the BI sources, the change and no-data conditions, hysteresis, cooldown and delivery.

### 4.8 Board notifications

A board tile's colour (`alert:` with `warn` and `crit`; [BOARDS.md section 4](BOARDS.md#4-alerts-a-tile-changes-colour)) can also **tell people** when it turns red. The board's author says so in the YAML:

```yaml
board: desk-alerts
notify:                               # defaults for every tile that says `notify:`
  channels: [inbox, email]            # inbox (always), email, chat (the bridges)
  cooldown: 1h
pages:
  - id: p1
    tiles:
      - id: mtm
        report: trades-overview
        visual: mtm
        at: { x: 0, y: 0, w: 6, h: 3 }
        alert:
          warn: { op: lt, value: -100000 }
          crit: { op: lt, value: -300000 }
          notify: true                 # or { level: warn, channels: [chat], trigger: live, severity: critical }
```

| Key | Default | Meaning |
|---|---|---|
| `level` | `crit` | the colour that notifies: `crit` (red), or `warn` (amber or red). `crit` needs a `crit` threshold on the tile, `warn` a `warn` or `crit` one |
| `channels` | `[inbox]` | `inbox`, `email`, `chat` (the chat bridges, as in 4.6) |
| `cooldown` | `drishti.alerts.measure.cooldown` | no second notification for the same person within it |
| `trigger` | `after-load` | `after-load`, `live` (needs a live report; on change, after the debounce) or a schedule (`business-days 07:30`, `hourly`) |
| `severity` | `critical` for `crit`, else `warn` | `info`, `warn`, `critical` |

`notify: true` takes the board's `notify:` defaults, or the built-in ones. The tile's level is judged exactly as the viewer colours it (the first number, or `on:`; the worst of the rows; `crit` before `warn`).
**Who is told**: everyone who may read the board (the BI power and the right to read the item), each **judged on their own number** by the report's engine call as them: a desk head is told on her desk's number, an
analyst whose number is masked is not told at all. The message is the tile's name and its colour (`Desk wall · Mark to market — is now red`); it opens the board, where each person sees their own figure. A board with more
readers than `max-audience` (300) tells nobody and says so in the history. A person is told when the tile **enters** the colour and again only after it has gone below it (and the cooldown has passed). The board's YAML **is** the
definition: publish a change and the next look uses it; nothing else is stored but the state and the history. Administrators see the tiles that notify, who is alerted now and the history at **Alerts, Everyone's alerts**
(`/alerts/all`):

![Everyone's alerts and the board tiles](img/bi-alerts/alerts-all.png)

### 4.9 Real output

Rita (who sees every figure) makes the alert of the pictures: *the MTM card of Trades overview, below -300,000, after a data load, with hysteresis 25,000, by inbox and email*. *Check now* answers (the server's
`POST /api/v1/me/measure-alerts/preview`, saving nothing):

```
{
  "status": "ok",
  "value": -725000.0,
  "asOf": "2026-10-08",
  "note": "",
  "condition": "below -300,000",
  "holds": true,
  "live": false
}
```

The ETL adds ten losing trades and announces the load (`POST /api/v1/packs/trading/loads`); a second after, the alert as the server holds it (abridged):

```json
{
  "name": "MTM floor",
  "owner": "rita",
  "source": {
    "type": "visual",
    "report": "file:report:trades-overview",
    "page": "overview",
    "visual": "mtm",
    "measure": "",
    "reduce": "first",
    "state": {
      "carry": [],
      "slicers": {},
      "selections": [],
      "drill": []
    }
  },
  "condition": {
    "type": "threshold",
    "op": "below",
    "value": -300000.0
  },
  "trigger": {
    "mode": "after-load"
  },
  "hysteresis": 25000.0,
  "cooldown": "30m",
  "delivery": {
    "inbox": true,
    "email": true,
    "bridge": false
  }
}
```

```json
{
  "state": {
    "rita": {
      "active": true,
      "firedAt": "2026-10-11T20:21:53.529000506Z",
      "lastValue": -725000.0,
      "lastEval": "2026-10-11T20:21:53.529000506Z"
    }
  },
  "history": [
    {
      "at": "2026-10-11T20:21:53.529000506Z",
      "user": "rita",
      "action": "fired",
      "severity": "warn",
      "trigger": "load",
      "value": -725000.0,
      "asOf": "2026-10-08",
      "message": "MTM floor \u2014 now -725,000 (below -300,000)",
      "id": 1791750102507,
      "delivered": {
        "inbox": "ok",
        "email": "sent",
        "bridge": "off"
      }
    }
  ]
}
```

Her inbox (`GET /api/v1/me/inbox?type=alert`, the row is rendered for her as she reads it; the first is the board tile, the others her two alerts):

```json
[
  {
    "seq": 7,
    "type": "alert",
    "kind": "alert",
    "id": "/bi/r/file:report:trades-overview/overview",
    "access": true,
    "title": "MTM floor \u2014 now -725,000 (below -300,000)"
  },
  {
    "seq": 6,
    "type": "alert",
    "kind": "alert",
    "id": "/bi/i/file:dataset:trades-basic",
    "access": true,
    "title": "Trade count moves \u2014 now 70, up 16.67% from 60 (previous run)"
  },
  {
    "seq": 4,
    "type": "alert",
    "kind": "alert",
    "id": "/bi/b/file:board:desk-alerts",
    "access": true,
    "title": "Desk wall \u00b7 Mark to market \u2014 is now red"
  }
]
```

and the mail the fake SMTP received for her alert (the figure is hers; a chat bridge would have got no figure):

```
Subject: Drishti alert: Desk wall · Mark to market
To: rita@desk.test

Desk wall · Mark to market

is now red

Severity: critical
Watching: trades-overview · mtm
Condition: reaches crit
Open it in Drishti:
http://127.0.0.1:17951/bi/b/file:board:desk-alerts

You are receiving this because an alert you own, or one on a board you can read, fired in Drishti. The figure is the one you are entitled to see; it was read for you.
Pause or change the alert on the Alerts page.
```

![The alert mail](img/bi-alerts/alert-mail.png)

**Ann**, whose `mtm` column is masked, makes the same alert. *Check now* says `That number is masked for you, so an alert on it would never judge it. Ask for the access, or watch another number.`; after the same load her history holds one entry and her inbox none:

```json
[
  {
    "at": "2026-10-11T20:21:55.707388871Z",
    "user": "ann",
    "action": "skipped",
    "severity": "warn",
    "trigger": "saved",
    "message": "masked for you, so it cannot be judged (MTM)"
  }
]
```

![Ann's history](img/bi-alerts/alert-history-masked.png)

A bad alert is `422 DRS-9062` and names the key:

```
422 {"type": "about:blank", "title": "alert invalid", "status": 422, "detail": "DRS-9062 condition.op is above, below, between or outside", "instance": "/api/v1/me/measure-alerts/Bad", "code": "DRS-9062"}
```

### 4.10 Phone

The Alerts page, the form, the history and the inbox keep to the screen at 390 px (no sideways scrolling, 44 px targets):

![The Alerts page at 390 px](img/bi-alerts/alerts-phone-390.png)

## 5. Embedding a report in another web application

*Phase 7, part 3 ([RUPAKA.md section 20.6](../architecture/RUPAKA.md#206-part-3-embedding-and-phone-layouts-as-built-phase-7-complete)).* Export and subscriptions send a report **out**; embedding **shows it in place**, in another application's own page, live, for that application's user. It is a Drishti Element,
`<drishti-report>`, in a Shadow DOM, with **no iframe**. The reference of every attribute, property, method and event is the developer guide ([ELEMENTS.md](ELEMENTS.md#drishti-report)); this is what a BI author and an administrator need to know.

**What the host writes.**

```html
<script type="module" src="https://drishti.bank.example/embed/v1/elements/1.4.3/drishti-elements.js" crossorigin="anonymous"></script>

<drishti-report id="rep" report="file:report:live-trading-desk" theme="light"></drishti-report>
<drishti-report id="one" report="file:report:live-trading-desk" visual="by-desk" chrome="bare"></drishti-report>
```

The host's own code gives the element a token provider (its backend exchanges the host's secret and its signed-in user for a short embed token at the Drishti server, [API_GUIDE.md](API_GUIDE.md#embedding-views-in-another-web-application)) and listens to what it says:

```js
DrishtiElements.configure({ tokenProvider: async () => (await fetch('/api/drishti-token?scope=embed:report')).json() });
document.addEventListener('drishti:filter-changed', (e) => console.log(e.target.id, e.detail.selections));
```

This is the host page of the test (`tools/elements-demo`, its own origin, a strict policy: no inline script or style, no frames, only the console named beyond itself), with the *Live trading desk* report in a card. It is the real page, the real server and the demo source ticking:

![A host application's page with the live report in a card](img/bi-elements/host-report.png)

**What is in the element.** Everything the console viewer draws from the model: the page tabs, the slicers, the business date, bookmarks, *click a mark to filter or highlight*, **cross-filtering** between the visuals, *show as a table* and the live bar. A report over a live table follows it
over the page's one embed connection, and each tick asks the visuals again **as the person looking**. A click on a bar below filters the other visuals, and the host is told, with the filters in force:

![The same card after a click on the DESK-RATES bar](img/bi-elements/host-report-selected.png)

What the host heard (`drishti:filter-changed`, captured from that page):

```json
{
  "page": "desk", "slicers": {}, "dates": null,
  "selections": [ { "visual": "by-desk", "field": "trade.desk", "values": [ "DESK-RATES" ] } ],
  "carry": [], "mode": "filter", "source": "user"
}
```

`visual="by-desk"` shows one visual alone, with no chrome, as a card of the host's own:

![One visual of a report](img/bi-elements/host-one-visual.png)

**Always masked.** An embedded call masks `drishti.security.redact` fields and the dataset's masked columns **whatever the user's roles** (a user with `raw` still sees `•••`, in the host's card; the same user in the console reads the real names). The element tells the host which columns were masked
(`drishti:masked`, here `{"columns": ["trade.trader"]}`); the *Worst trades* table above shows the mask. Row rules are the user's, per call.

**Which hosts may embed which reports (Admin → Embedding).** Register the host application (exact origins, a secret or a public key) with the scope **`embed:report`**. Its **Kinds and reports it may show** list is the allow-list: `report:file:report:live-trading-desk` allows that one report, `report:*` every report, an
empty list everything the user's roles open. A host whose list names only entity kinds may show no report, and a report off the list is refused `DRS-8005` (the element shows the code and tells the host with `drishti:error`). Only a **published** report is embedded: a draft is refused. The server checks the list on every call.

**Phone.** `<drishti-report>` uses the report's `layouts.phone` when **the element** is 640 px or narrower, so a report in a narrow card of a wide page, or a full-width one on a phone, gets the arranged page; see [REPORTS.md section 14](REPORTS.md#14-phone-and-tablet-layouts):

![The report in the host's page at 390 px, with the report's phone layout](img/bi-elements/host-phone-390.png)

**Live and tokens.** The report keeps its live stream while the page is visible and gives it back when hidden (and takes it again); a token renews in place on the open channel, so nothing redraws.

**Not in the element.** Drill down and through, *Alert me*, *Show the rows behind this visual*, Export and Subscribe (use the console for those). A 2.1 `perspective-*` visual is drawn there as in the viewer, Drishti's own grid of the server's answer (since 2.3.0 no BI page loads WebAssembly). Two elements of one report do not cross-filter each other.
The full list is in [RUPAKA.md section 20.6, part 3](../architecture/RUPAKA.md#206-part-3-embedding-and-phone-layouts-as-built-phase-7-complete).

**Also in the library (1.4.0).** `<drishti-about kind entity>` shows the *About this page* layers of a view (sources, lineage, the masks and rules that applied to the reader) in a panel of the host's own; the application needs the scope `embed:about`.
`<drishti-search>` is a search box across Drishti for the host's page: the console's own type-ahead for the signed-in user, **read-only and always masked** (masked fields are neither shown nor matched), cut to the kinds the application may show, at most
`drishti.embed.search.max-results` results, and **rate-limited** per user and application (`drishti.embed.limits.searches-per-user-per-minute`, 30 a minute, `DRS-8004` with `Retry-After`). It needs the scope **`embed:search`**, which opens
`GET /api/v1/embed/search` and nothing else. React, Vue and Angular sample host pages that embed all four elements are in `docs/guides/examples/embed/` ([ELEMENTS.md](ELEMENTS.md#react-angular-and-vue-sample-pages)).

## 6. Webhooks and text messages

*Wave D.* Alerts and subscriptions can also **post to a webhook** and **send a text**. Both are set up by an administrator (`drishti.notify.*`,
[CONFIGURATION.md](../admin/CONFIGURATION.md#drishtinotify--webhooks-and-text-messages-for-alerts-and-subscriptions)); a person only picks from what exists. Nothing is on until it is defined.

**Webhooks.** An administrator defines a webhook by a **name**, a URL and a secret (`drishti.notify.webhooks.<name>.*`) and lists the hosts webhooks may reach (`drishti.notify.webhook.allowed-hosts`, exact names or `*.suffix`; empty means
none is sent, and the host is checked at every send). A person ticks the names on the alert or Subscribe form (`delivery.webhooks`, `webhooks`); they cannot type a URL. A post is `POST` JSON with
`X-Drishti-Event` (`alert` or `subscription`), `X-Drishti-Timestamp` and **`X-Drishti-Signature: sha256=<hex>`**, the HMAC-SHA256 of `<timestamp>.<body>` with the secret, which the receiver recomputes to check the sender and reject
an old timestamp. TLS and proxy settings are the connectors' (`webhooks.<name>.tls.*`, `.proxy.*`, the site default); redirects are never followed; the answer's body is never read; an error never contains the URL. A
2xx is sent; a timeout, a connection failure, 408, 429 and 5xx are retried by the shared retry policy; any other answer, a host off the allow-list, `http://` without `allow-http`, and a secret whose `${ENV}` variable is not set are permanent.

```
POST https://hooks.corp.example/drishti
X-Drishti-Event: alert
X-Drishti-Timestamp: 1782000000
X-Drishti-Signature: sha256=3c3b0f6c0b6e7f2a0d5c...
{"event":"alert","id":1782000000123,"at":"2026-10-12T14:00:00Z","alert":"Exposure over limit","key":"alice/Exposure","severity":"critical",
 "watching":"Exposure (trading-risk)","condition":"above 100","link":"https://drishti.bank.example/bi/r/file:report:daily/main"}
```

A subscription's post is `{"event":"subscription","subscription","title","owner","trigger","at","recipients","link","target":{type,id,page,visual}}`: the title and where it opens, with the number of recipients and no name or figure.

**Text messages.** The gateway is any HTTP SMS service, described by templates (`drishti.notify.sms.url`, `method`, `body`, `content-type`, headers with `${ENV}` credentials, `sender`): `{to}`, `{text}` and `{sender}` are filled in and
escaped for where they land. No vendor is built in. A person gives **their own** mobile number (the alert or Subscribe form, or `PUT /api/v1/me/phone`; only its last two digits are shown back) and ticks *text me* / *text each
recipient*. A text is cut to `sms.max-chars` (160) with an ellipsis and holds the title, severity or a link, **never a figure** (an alert's own one-line message with the person's number only if the administrator sets
`sms.include-values`; a masked number is never judged or sent). A recipient with no number is skipped with the reason in the history, as is someone who cannot open the report.

![The Subscribe form with the summary and the channels](img/bi-dist/subscribe-form-channels.png)

Both channels are in the history: an alert's `delivered` has `webhook:NAME` and `sms` (and the attempts), a subscription's run has a delivery for each (`webhook:NAME`, `sms:USER`).

## 7. What is not built

* **In an embedded report** (section 5): drill, alerts, Export and Subscribe, Vega-Lite and Python visuals, and cross-filtering between two elements. Embedding and the hand-made phone and tablet layouts are built.
* **For alerts**: **escalation** (tell someone else if no one reacts), alerts that are **shared** between people (a board's
  audience is the only way an alert reaches more than its owner), a **calendar of quiet hours**, an alert on **several numbers at once** (make one alert each), and **forecast** conditions. An alert on a visual's *number* is a
  threshold on one value: per-row thresholds ("any desk below zero") are a board tile with `crit`, which judges the worst row.
* **A headless-browser rendering** of the page: the PDF and PNG are drawn by the server from the tables (and, for a view, the snapshot layout); they are faithful to the numbers, not to every pixel of the screen
  (chart colours, a heatmap, a scatter, a map are shown as their data).
* **Excel pivot tables** inside the workbook, and charts other than bar, column, line, area, pie and donut in an Excel export (they are drawn as columns; the six are real Excel charts); **XLSX styling** beyond the header, number and date formats.
* **Notifying the owner in the inbox** when a delivery fails for good: the history and the audit say so; the bell does not.
* **Subscribing a live visual's state as it ticks**; a subscription reads the data as of the run.
* **Charts on a statement page** (a statement draws text, cards, tables and matrices; [STATEMENTS.md section 10](STATEMENTS.md#10-built-and-not-built)).
* **Per-recipient pictures of a view with charts in the email body**: a view is sent as its tables (html) or as a PDF.
