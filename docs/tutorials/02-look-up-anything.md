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
# Tutorial 2 · Look up anything

Open any entity in seconds: type a mnemonic and an id, let the type-ahead finish it, follow links from one entity to the next, look at yesterday instead of now, and compare
two days. The examples use the shipped trades, counterparties and netting sets, and one country from a pack of real public data.

## Time to complete

About 20 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done: server and console running with sign-in on, and you signed in as `drishti-dev-admin`.
- The sample history built (Step 1 of tutorial 1). Without it the past-date steps show *No data held for that date*.
- For the command-line steps: `DRISHTI_SERVER` and `DRISHTI_TOKEN` set, as at the end of tutorial 1.

## What you will learn

- the command line's grammar: mnemonics, ids, bare ids and names, pick lists;
- what the type-ahead offers and how to use the keys;
- how links and function keys take you from a trade to its book, its counterparty and its netting set;
- the **Live** toggle against a past business date, and what the amber date box means;
- **Compare**: every field that changed between two days, and one number over time.

## Step 1 · Mnemonics: type one letter

Press `/` and type `T`.

![The command line offering the mnemonics that start with T, then recent entities](img/t02/01-mnemonics.jpg)

You should see the mnemonics that start with T (`TRD` trade, `TRDR` trader, `TRIAL` clinical trial and so on), each with its short name, then entities you opened lately. A mnemonic
is the first word of a command; every pack that is switched on brings its own. `TRD` is a trade, `CPTY` a counterparty, `NSET` a netting set (all the trades with one counterparty under
one agreement), `BOOK` a book, `CRV` a curve. [PACKS.md](../guides/PACKS.md) lists them all.

## Step 2 · Type-ahead for an entity

Clear the box and type `NSET SUMMIT`.

![Two netting sets suggested for NSET SUMMIT](img/t02/02-entities.jpg)

You should see two lines: `NS-SUMMIT-LDN` and `NS-SUMMIT-NY`. The type-ahead matches the start of an id **or** a word in its title. Keys: `↓` and `↑` move, `Tab` copies the highlighted
line into the box (the first, when none is highlighted), `Enter` opens it, `Esc` closes the list. Clear the box and type `CPTY north`:

![One counterparty suggested for CPTY north](img/t02/03-find-by-name.jpg)

You should see a single line, `CP-NORTHBRIDGE`. You typed a name, not an id: a counterparty's name is in what the type-ahead searches.

Programs get the same suggestions from the server; this is the call behind the dropdown (the answer is trimmed to the first fields):

```text
$ curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" 'http://localhost:18480/api/v1/command/suggest?q=NSET%20SUMMIT&limit=5'
[
    {
        "type": "entity",
        "mnemonic": "NSET",
        "kind": "netting-set",
        "id": "NS-SUMMIT-LDN",
        "title": "NS-SUMMIT-LDN",
        "subtitle": "Netting set",
        "complete": "NSET NS-SUMMIT-LDN"
    },
    {
        "type": "entity",
        "mnemonic": "NSET",
        "kind": "netting-set",
        "id": "NS-SUMMIT-NY",
        "title": "NS-SUMMIT-NY",
        "subtitle": "Netting set",
        "complete": "NSET NS-SUMMIT-NY"
    }
]
```

### Type-ahead for a past date

Pick a past business date (Step 5 shows how) and type `TRD ` again: the dropdown now offers the ids that existed **on that
date**, not today's. A connector that keeps several days (an administrator's `index.days: 5`) answers this from memory
at once; one that keeps only the newest day offers today's ids and opening one that was not held then says so. The
subtitle `last seen <date>` marks an id offered only because it existed on an earlier day (`index.any-recent-day`).

## Step 3 · Open one entity, or pick from many

Press Enter on `CPTY north`.

![The counterparty view of Northbridge: strip, netting sets, hierarchy and linked entities](img/t02/04-bare-id.jpg)

You should see the counterparty opened straight away, because the command names exactly one. The rule: **one match opens; several give a pick list**. Now type `TRD MX-200000`
(the start of an id) and press Enter:

![A pick list headed Pick a trade: 99 of 750 trades match, with a table to choose from](img/t02/05-pick-list.jpg)

You should see *Pick a trade* with a count such as `99 of 750 trades match` and a table. Click an id to open it. A bare id also works when its shape tells Drishti the kind: type
`MX-20000001` alone and it opens the same trade as `TRD MX-20000001`. Case never matters.

## Step 4 · Follow the links

Open `TRD MX-20000001` and look at the strip and the panels.

![The strip of the swap with the book as a link, and the counterparty named in the title](img/t02/06-links.jpg)

You should see blue names. In the title line `Meridian Reinsurance Ltd` is the counterparty; in the strip `BOOK-RATES-3` is the book. Click one and that entity opens. Function keys do the same from
the keyboard: the bar at the bottom lists them, and **F7** opens the netting set the trade belongs to. Press **F7**.

![The netting set view: exposure profile, net MTM by asset class, linked entities and member trades](img/t02/07-netting-set.jpg)

You should see the netting set `NS-MERIDIAN-RE-NY` with its exposure profile, net MTM split by asset class and the member trades. **Alt+←** takes you back to the trade. Links, impact (**F8**)
and search all follow the date in the top bar, which is the next step.

## Step 5 · A past date, and the Live toggle

The **business date** box in the top bar says which day you are looking at. Empty means **live**: today's business date, updating as data arrives. The demo data ticks **as a whole**: when a rate moves, everything that depends on it moves with it. Open a curve (`CRV CRV-USD-BASIS`) and watch: the 10-year figure, every row of the pillars table and the chart change together, not just one cell ([tutorial 12](12-live-bi-and-boards.md) shows it side by side). Back on the trade, click the date box and
type `2026-09-29`, then press Enter.

![The trade on 29 September: an amber date box, a red Live button and a Compare link](img/t02/08-past-date.jpg)

You should see the same layout with that day's figures. The date box is amber, the round **Live** button beside it turns red with two pause bars (it is paused: this is a still snapshot, the
MTM no longer ticks), and a **Compare** link appears in the title line. Click the red button.

![Back to live: the date box empty again and the Live button green](img/t02/09-live-toggle.jpg)

You should see the date go back to live and the button turn green with a filled dot. The Live button is a toggle: green pauses live updates (a snapshot of the current business date), red resumes.
You can pick another date while paused. The choice follows you from page to page. If you pick a Saturday the box shows `↩ 2026-09-26` and the data is Friday's.

The date is one number to the server too:

```text
$ curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" http://localhost:18480/api/v1/business-date    # without the holiday list
{
  "current": "2026-10-09",
  "selected": "2026-10-09",
  "live": true,
  "knownAt": null,
  "previous": "2026-10-08",
  "earliest": "2021-10-08",
  "calendar": "USNY",
  "zone": "America/New_York"
}
```

And the command line reads the past just as well; this is the same trade on 29 September (compare its MTM with the live view of tutorial 1):

```text
$ python3 tools/drishti.py view get trade/MX-20000001 --date 2026-09-29
Rates · Interest rate swap (fixed/float)  MX-20000001  with Meridian Reinsurance Ltd   [TRD]

Key figures
  figure      value
  ----------  ---------------
  Notional    AUD 242,000,000
  Direction   Receive fixed
  Trade date  2025-07-25
  Maturity    2032-06-25
  MTM (USD)   +1,888,406
  DV01 (USD)  −155,521
  Status      Live
  Book        BOOK-RATES-3
...
```

## Step 6 · Compare two days

Pick `2026-09-30`, open `TRD MX-20000001` and click **Compare**.

![What changed in MX-20000001: every field that differs between two business dates, with before, after and change](img/t02/10-compare.jpg)

You should see *What changed in MX-20000001*, comparing 30 September with the business day before, and a table: one row per field that differs, with its label and path, the value on each date and
the change. Lists are matched by their keys, so the row reads `sensitivities[3M].dv01` rather than a position. Choose any two dates at the top to compare further apart; filter to *changed*,
*added* or *removed*; **CSV** downloads the table. Programs ask the server for the same thing:

```text
$ curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" 'http://localhost:18480/api/v1/history/trade/MX-20000001/diff?from=2026-09-29&to=2026-09-30'    # shortened to the first three changes
{
  "from": "2026-09-29",
  "to": "2026-09-30",
  "changes": 219,
  "first": [
    {
      "path": "mtm",
      "label": "MTM (USD)",
      "kind": "changed",
      "before": 1888406,
      "after": 1876068,
      "delta": -12338.0
    },
    {
      "path": "risk.dv01",
      "label": "DV01",
      "kind": "changed",
      "before": -155521,
      "after": -155245,
      "delta": 276.0
    },
    {
      "path": "sensitivities[3M].dv01",
      "label": "DV01",
      "kind": "changed",
      "before": -5441,
      "after": -5544,
      "delta": -103.0
    }
  ]
}
```

Back on the view, click a number, for example the MTM in the strip.

![MTM over time: a line over 30 business days and the values with the date they are for and the source](img/t02/11-over-time.jpg)

You should see a window with a line over the last 30 business days and the values below, newest first, each with *Data for* and its source. The first line says where the number comes from
(the path in the document, the connector, the business date and the source's generation). Choose 10, 30, 90 or 250 days at the top.

## Step 7 · A different domain: real public data

The packs of other industries use exactly the same commands. The `world-economy` pack holds published data about countries (World Bank, UNDP and the CIA World Factbook, public sources;
[REAL_DATA.md](../guides/REAL_DATA.md) lists them and their licences). Type `JUR JUR-USA`.

![The jurisdiction view of the United States: government, legislature and indicators](img/t02/12-public-data.jpg)

You should see the United States as a jurisdiction: its government type, capital, legislature and social indicators, each linked to related entities (office holders, elections, parties, the
region). The command line prints the same view:

```text
$ python3 tools/drishti.py view get jurisdiction/JUR-USA
Politics and society · Jurisdiction  JUR-USA   [JUR]

Key figures
  figure          value
  --------------  -------------------------------
  Country         United States
  Capital         Washington, D.C.
  Government      constitutional federal republic
  Population (m)  341.79
  Area (km2)      9,833,517
  Currency

Seats by party, lower chamber  (table, legislature, F2)
  Party             Seats
  ----------------  -----
  Republican Party    220
  Democratic Party    215

Society and governance (latest)  (table, indicators, F3)
  Indicator                                    Value  Year  Unit
  -------------------------------------------  -----  ----  -----------------
...
```

## Next steps

- [Tutorial 3 · Query by form](03-query-by-form.md) finds entities by what they hold.
- [USER_GUIDE.md › The command line](../guides/USER_GUIDE.md#the-command-line) lists every reserved word, alias and error message; [Business dates](../guides/USER_GUIDE.md#business-dates-live-or-a-day-in-the-past) explains *known at* (time travel).

## If something goes wrong

| You see | Meaning |
|---|---|
| `DRS-4001 cannot read command` | the first word is not a mnemonic of a pack that is switched on: type its first letter and pick from the list |
| *Pick a trade ... 0 of 0 trades match* | nothing starts with what you typed: type less of the id |
| *No data held for 2026-09-29* | the sample history is missing: build it (tutorial 1, Step 1) |
| the date box refuses a date | dates run from five years back to today |
