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
# Distribution: export and subscriptions

Rūpaka phase 7, part 1 ([RUPAKA.md section 20.6](../architecture/RUPAKA.md#206-phase-7-part-1-as-built)). Two things leave Drishti as files and mail: an **export** (a BI report, a page, one
visual, or a Sutra view, saved as PDF, a picture, an Excel workbook or CSV) and a **subscription** (the same content sent by email on a schedule, or after the day's data lands).
The walk-through with pictures is [BI_TUTORIAL.md chapter 20](BI_TUTORIAL.md#20-export-and-subscribe); this is the reference.

**One rule runs through all of it: the file or the mail is made for the person who gets it.** The numbers are asked of the data engine *as that person*, so their row rules and their
masks apply; nothing is copied from the sender's screen. A desk head's export holds her desk; an analyst whose column is masked gets the mask.

## 1. Export

| Format | What it is | Made from |
|---|---|---|
| **Excel** (`.xlsx`) | a sheet `About` (title, as-of date, the filters, how many rows each sheet holds) and **one sheet per visual** (BI) or per table-like panel (a view); a bold header row that stays in view when you scroll; numbers are numbers (whole, decimal), dates are dates, text is text (never a formula, whatever it starts with) | the tables |
| **CSV** | the same tables; with several, each under its title and a blank line between; UTF-8 with a byte-order mark; text a spreadsheet would run as a formula gets a leading apostrophe | the tables |
| **PDF** | landscape A4 (a setting), **paged**: no table row is split by a page edge, a table that continues repeats its header row, a running header (title; as-of date) and a footer (who it was made for, when, "Page n of m") on every page | the tables and bars |
| **PNG** | one picture, as tall as the content needs (to a limit) | the tables and bars |

For a **BI report** the PDF and PNG draw each visual as what it holds: a *card* as its value, a *chart* (bar, column, line, area, pie, donut, waterfall, treemap) as horizontal bars of its first
measure above its data, every other type (table, matrix, Perspective grid, gauge, KPI, heatmap, scatter, combo) as its data table. Static visuals (text, image, button) and slicers are not exported: the
slicers' choices are written in the header as the filters. A **Sutra view** is drawn the way a share's picture is (the panels as the view draws them, charts included) with the sentence "Exported by …"
in the band and across the page; Excel and CSV hold its table-like panels (tables, fields, pivots, metrics, tabs); a note says which graphic panels they leave out.

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

`drishti.distribution.export.*` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtidistribution-export-and-subscriptions)): rows per table in Excel and CSV (`max-rows`, 50,000; a table is cut there and its
sheet says so), rows drawn per table in a PDF or PNG (`pdf-max-rows`, 500: the picture is for reading, Excel holds the rest), the largest file (`max-bytes`, 25 MB), the longest export (`timeout`, 60 s),
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
| To | user names and `role:NAME` (everyone with the role, today: a role is the group); outside addresses only if an administrator allows them |
| View state | taken from the report or view you pressed Subscribe on: slicers, selections, drill path, carried values; a view's controls and filters |

### Schedules

Everything a [scheduled report](USER_GUIDE.md#scheduled-reports) takes (`daily HH:MM`, `weekdays HH:MM`, `business-days HH:MM`, `hourly`, `cron` and six fields), and:

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
MTM: (masked)

Average MTM per trade
Masked for you: Average MTM per trade.
Average MTM per trade: (masked)

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

* **A recipient who cannot open the target** (the role lost the report, the kind) is **skipped**, with the reason in the history; they are not mailed a refusal.
* **A recipient with no email address**, or a disabled user, is skipped.
* **Outside addresses** (`drishti.distribution.subscriptions.external-recipients`, off) have no rights of their own: they receive the render **as the subscription's owner**. That is why the setting is off,
  why only the owner can add them, and why `external-domains` can limit them to your partners' domains.
* **Who may subscribe whom.** Anyone may subscribe **themselves**. Subscribing other people or a role needs a BI author (power `bi-author`) or an administrator (`DRS-9061` otherwise). The subscriber
  must be able to open the target themselves.

### Running, pausing, history

![Subscriptions](img/bi-dist/subscriptions-list.png)

*Run now* makes and sends everything at once (the status line says `Morning desk pack: 3 sent`). *Pause* stops the schedule (a paused subscription has no next run); *Resume* schedules it from **now**: it does not
catch up on what it missed. *History* keeps the last runs (`keep-runs`, 30; the three newest with every recipient's delivery status, older ones their counts). A run's status is `ok`, `partial`
(some failed), `retrying`, `failed`, or `skipped` (nobody could be sent anything). Everything is audited (`subscription.save`, `.run`, `.pause`, `.resume`, `.delete`). Administrators see everyone's at
**Reports, All subscriptions** (`/reports/all`) and can pause, resume or delete any; only the owner can change what one sends.

### Retries and failures

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

## 4. What is not built

* **Narrative summaries** in a subscription (needs the AI stage), and **alerts on a measure** to the bell, inbox, email and chat bridges: phase 7 part 2.
* **Embedding** (`<drishti-report>`) and the hand-made **phone layout** of a report: phase 7 part 2.
* **A headless-browser rendering** of the page: the PDF and PNG are drawn by the server from the tables (and, for a view, the snapshot layout); they are faithful to the numbers, not to every pixel of the screen
  (chart colours, a heatmap, a scatter, a map are shown as their data).
* **Excel charts and pivot tables** inside the workbook; **XLSX styling** beyond the header, number and date formats.
* **Notifying the owner in the inbox** when a delivery fails for good: the history and the audit say so; the bell does not.
* **Subscribing a Perspective visual's live state**; a subscription reads the data as of the run.
* **Per-recipient pictures of a view with charts in the email body**: a view is sent as its tables (html) or as a PDF.
