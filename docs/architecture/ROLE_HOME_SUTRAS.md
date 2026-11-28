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
# Role Home Sutras: how a Home is built, chosen, arranged and kept current

**Status: built (milestone H1). Written 2026-11-22 (the owner chose the hybrid: "Exceptions first" and "Since yesterday 17:00" on top of the
KPI strip and mnemonic bar; "arrange my Home" is an option).**
The mock-ups are role-homes/H-risk-manager.html and H-trader.html (branch wip/i7b-products, lenses mock-ups). The model of a Home (a named file
that roles point to) is the i7b plan (branch wip/i7b-products) 3.2; this document fills in the screens and the new server feature.

## 1. One Home Sutra per role, and how a role's Home is chosen

| Piece | Where | What |
|---|---|---|
| The Home | `config/homes/<module>/<home>.home.yaml` | name, title, description, `modules:` it needs, `sutra:`, `arrange: allow \| locked`, `measures:` |
| Its screen | `<home>.sutra.yaml` beside it | a Sutra of `use:` entries only, from the libraries `core` and `finance` of drishti-rachana (no panel is written in it); fragments in `drishti-rachana` branch `i8/role-home-fragments` |
| What it follows | `<home>.measures.yaml` | the KPI figures, the measures the change detection compares, thresholds, the exception rules (4.4) |
| Role to Home | the role definition's `home` field (I7B decision 10), preset by the distribution (`homes.roles`), edited in Admin, Roles | many roles may name one Home |
| The person's start Home | their settings key `home` | one role: no picker; several: the picker; an administrator: all (I7B 3.2) |

Written now: **risk-manager** and **trader** (`config/homes/banking/`). To do, one Home each, from the same fragments:

| Role | Working area (fragments) | Needs first |
|---|---|---|
| Risk officer / CRO | firm VaR and limit use, backtest light, stress tiles, large exposures, breaches list | `finance/backtest-traffic-light`, `finance/var-tiles`, `core/use-bar-table` |
| Credit officer | exposure by counterparty (use bars), margin calls, agreements in force | `finance/margin-call-ladder`, `finance/netting-summary`, `core/use-bar-table` |
| Operations and administrator | loads and feeds health, held changes, data quality, jobs | `core/held-changes`, `core/freshness-badge`, `core/status-badges` (an `ops` library later) |
| Quant and analyst | model runs, backtests, playground entries, data freshness | `finance/var-histogram`, `core/entry-form` |
| Economist (public sector) | indicators with sparklines, release calendar, headlines | `core/kpi-strip`, `core/calendar-list`, `core/headline-rail`, `economy/*` when built |

**Visibility.** Three rules, all existing, none new: (1) a Home is offered only while the modules it names are on; (2) a panel's
`requires:` (a permission name answered by the host, LIBRARIES 19) drops the panel when the person's role lacks it: the Sutras write
`requires: mnemonic.VAR` so a panel disappears when its mnemonic is not active for the site or the role (mnemonic switches); (3) the
tables a panel reads are the person's: rows and columns follow row rules and masks, so an empty table shows the fragment's own empty
words. A Home hides nothing else.

## 2. The data a Home reads

A Home never computes. Each `data:` entry is a **home table**, a read-only table the server serves for the person, built from stored
results of the engines and the change detection (4). Contract names are `home/<name>@1`; each lives in `docs/contracts/` when built.

| Table | Columns | From |
|---|---|---|
| `home_meta` | BUSINESS_DATE, SINCE | the business date; the reference cut-off in words ("yesterday 17:00") |
| `home_kpis` | LABEL, VALUE, DELTA, CAPTION | the Home's `kpis:` (measures file), each answered by its engine now, DELTA against the cut-off snapshot |
| `home_kpi_history` | LABEL, DATE, VALUE | the daily snapshots of the same figures, the last N days |
| `home_exceptions` | SEVERITY, MESSAGE, SUBJECT, COMMAND | the `exceptions:` rules over the figures, load results and feed status |
| `home_changes` | SUBJECT, MEASURE, KIND, THEN, NOW, CHANGE, WHY, COMMAND | the `changes` engine (4.3) |
| `home_desk_limits`, `home_exposure` | name, value, LIMIT, CHANGE | the VaR and exposure engines against the limits store |
| `home_stress` | SCENARIO, PNL, LIMIT | the stress results (computed, never stored: computed-risk-measures rule) |
| `home_positions`, `home_pnl_days`, `home_market_tiles` | as the fragments' columns | the position and P&L results; market data |
| `home_mnemonics`, `home_calendar`, `home_news` | MNEMONIC, ABOUT / DATE, TIME, TITLE, KIND, COMMAND / AT, HEADLINE, SOURCE, LINK | the person's most used mnemonics (settings), the calendar, the headline feeds |

Table names and columns are the proposal to confirm when the contracts are written; the measure codes in the measures files (`PNL_DAY`,
`LIM_USE`, ...) are placeholders to match against the engines' outputs.

## 3. "Arrange my Home": per-person layout, no new JavaScript

Rachana already has it: `layout: { user-can-arrange: true }` lets a reader drag a panel to another place or area and resize it; the
reader's arrangement is `PUT` whole to `/me/arrange/<sutra>` (`GET`, `DELETE` = "Reset layout"), kept per person and per Sutra, never
changing the shared Sutra (`drishti_screen-grid.js`, `core/screen_grid.py`, `routes/terminal_routes.py`; API_REFERENCE). Both Home Sutras
set it. So:

- **Storage:** the existing per-person arrangement store, keyed by the Home Sutra's name (`home-risk-manager`); no new table, no new JS.
- **The option, not the default:** the role's layout (the `areas` picture) is what a person sees first; "arrange my Home" turns on the drag handles
  (the mock-up's pin and drag marks) and "Reset layout" returns to the role's.
- **Pinning** is the arrangement: a panel moved to the top of its area, folded (`collapsed`) or resized is the person's pin. A panel the
  person's role may not see is not in the arrangement.
- **Administrator lock (decision 2 of the mock-up README):** the Home's `arrange: locked` makes the server compile the Home Sutra with
  `user-can-arrange: false` and ignore a stored arrangement. This is the one small server change here (read the flag where the Home's
  Sutra is served); allow is the default.
- **When the role's layout changes** (a new Home version): a stored arrangement that names a panel id no longer there is ignored panel by panel
  (existing behaviour of the grid), new panels appear in the role's place.

## 4. What changed since yesterday: the change detection

### 4.1 What it compares

The same measures, by subject, at two moments: the **reference cut-off** (the previous business day at the Home's cut-off, by default 17:00 in
the site's time zone) and **now**. Subjects: desk, book, trade, counterparty, risk factor (any subject kind a followed measure has). It reports:

| Kind | Rule |
|---|---|
| move | both values exist and the absolute move is at least the measure's `min-abs` and the relative move at least `min-rel` |
| new, removed | the subject has a value only now, or only then |
| event | not a value move: a new or amended trade, a load, a limit state change, since the cut-off (read from the stored events) |

Thresholds are per measure, in the Home's measures file (Admin may edit it like any config); parameters of the engine are the defaults.

### 4.2 What is stored, and what is computed

Three things are stored, all subject-keyed so the row rules apply when they are read, and none is a figure computed twice:

1. **`measure_snapshot`**: at each cut-off, a **snapshot job** (a scheduled service with a side effect: it is not an engine) asks the followed
   engines for each followed measure and writes SUBJECT_KIND, SUBJECT, MEASURE, VALUE, CUTOFF, BUSINESS_DATE to the lake. It also writes the KPI figures,
   which is where `home_kpi_history` comes from. Kept N days (config).
2. **`change_event`**: the load, trade-amendment and limit-state events as they happen (existing audit and load logs), one row per event.
3. **`change_driver`**: the attribution rows (4.4), written after each load or recompute.

The comparison itself, `now` against `then`, is **not stored**: it is the `changes` engine, run when a Home is opened or refreshed, over the
person's rows. It is cheap (two tables of followed measures) and pure.

### 4.3 It is an engine (EN-45), and what is not

EN-45: every figure a person reads is computed by an engine. "Now minus then, kept above a threshold, with each driver's share of the move" is a
figure, so it is the engine **`changes`**, written against `engine-api` in the style of the drishti-engine samples: descriptor
`engines/changes/src/main/resources/META-INF/drishti-engine/changes.engine.yaml`, `ChangesEngine`, `ChangesSession` (Java 21, JDK-only,
`provided` engine-api), kit cases in `engines/changes/tck/cases/changes/changes.yaml`. The folder is outside the Drishti reactor (guard A7) and
moves to the `drishti-engines` repository at M2 (EN-52); it is a standalone Maven project (parent `engine-parent`) meanwhile.

| Piece | Kind | Why |
|---|---|---|
| `changes` (then, now, thresholds, events, drivers in; `changes` out) | **engine**, explain capable | pure, deterministic, an explained function of contract tables; `explain` of CHANGE returns the move and each driver's share as steps |
| the snapshot job, the event writer, the attribution runner | host services | they store or run other engines: side effects and orchestration are not engines (EN-45) |
| the home tables | host views | rows as they are, under the person's rules |

Inputs `then`, `now`, `events` and `drivers` are `mode: request` (read as the caller): a person never gets a change of a subject they may not see. A
missing `then` or `now` is NOT_FOUND naming the table (a Home then says "no cut-off snapshot yet" in words); `thresholds`, `events` and `drivers` are
optional, and without drivers the WHY column says the move is unexplained rather than guessing. Switch: `config/engines/changes.yaml` and Admin,
Engines, like every engine (engine-switches rule); off, the Home's "Since" panel is simply absent.

### 4.4 How a change is explained

The engine only does arithmetic on **attribution rows** the host supplies; the host produces them by running the measure's own engine, which is where
the numbers come from (no second implementation of VaR). For a followed measure whose engine offers explain and what-if overlays (the VaR engine takes
`what-if` tables that overlay its inputs):

1. The host walks a **fixed order of input groups**: rolled window, market data, amended trades, new trades.
2. It runs the measure at the reference inputs, then swaps one group to its current state at a time, cumulatively, and records each step's change as one
   DRIVER row (`new trades`, `market data`, ...) with DELTA; the last run equals "now". Cumulative steps make the deltas add up to the move by
   construction; the order is fixed and shown, because attribution of a non-linear measure depends on the order.
3. The engine turns rows into WHY: up to three drivers by size with their shares ("RF-USD-10Y 71%"), plus "unexplained n%" if the rows do not add up (a
   measure whose engine cannot take a group has only the groups it can).
4. Finer drivers (a risk factor, a trade) come from the measure engine's own explain on the cell; the command `WHY <key>` opens that explanation
   (the why-it-moved view of the desk), so the Home carries a sentence and one click reaches the full steps.

Cost control: attribution runs after each load or recompute for the **followed measures above their thresholds only**, once for the firm (stored
subject-keyed in `change_driver`), never per person.

### 4.5 When and where it runs

Snapshot job at the cut-off; events as they happen; attribution runs after each load or recompute completes (the load-complete event of the loader);
the `changes` engine at request time. All on the server, in Java; the browser only draws the rows (server-computes-risk rule).

## 5. What is written and what is left

Written, unbuilt and unrun (2026-11-22): the Home loader and catalogue (`kernel/drishti-server/.../server/home`: role to Home through `drishti.homes.roles`,
modules, mnemonics, `arrange: allow|locked`), the home tables (`HomeTables`, a module adds `HomeTable` beans), the snapshot job, the event writer,
the attribution runner, the Homes API (`/api/v1/homes`), the VaR and exposure measure sources (banking module), Homes for the seven roles, the
console page `/home/role`, a test per role and a browser walk of both Homes, and the `open0..open7` click events of `core/kpi-strip`.

| Left | Why |
|---|---|
| Host the `changes` engine (`ChangeEngineHost`) | R2: no engine-api host exists in the server; "Since" says so in words until then |
| Draw a Home: the server's view model of a Sutra over the home tables | the Rachana bind of `use:` fragments over the table host is not wired; the page carries the Sutra and tables as data |
| Attribution by input group (`MeasureSource.drivers`), `PNL_MTD`, `DV01`, `POSITION`, stress, margin calls, indicators, calendar, news, model runs, held changes | R2 (hosted engines) or the owning module's `HomeTable` |
| The role definition's `home` field | `RoleDefinition` has no such field; `drishti.homes.roles` carries the mapping until the role store changes |
| Contracts `docs/contracts/HOME.md` for the `home/*` tables | to write with the first run |
| Build, run the tests, regenerate the fragment goldens and hashes | owner: builds later |

## 6. Your Home: the cards every person lands on

`/home` (drishti-console `routes/home_cards_routes.py`, `core/home_cards.py`) is where everyone lands after signing in. It does not replace the
Home Sutras above; it reads the **same home tables** (`GET /api/v1/homes/<home>/tables/<table>`, for the person) and shows them as ranked cards.

| Piece | What |
|---|---|
| Cards | declared by modules (console `plugin.yaml`, `home: role-cards:`) and by the console (`drishti-console/config/home-cards.yaml`): `id`, `kind` (`metric`, `bars`, `table`, `scatter`), `table`, `pick`, `rank` per role Home, `open`, `explain` |
| Layout | Calm; Cockpit for the Homes named in `layouts:` (risk-manager, risk-officer); phone: stacked, Needs you first |
| Needs you | raised alerts, the approvals inbox, late/missing/failed loads (`home_loads`), the high rows of `home_exceptions`; absent from the document when empty |
| The document | a ViewModel (contract 1.2: panels, `layout.areas` and `place`, `phone`, panel `actions`) drawn by drishti-views; button effects (`host`) come back to the page: pin, unpin, hide, show, up, down, snooze, reset, explain, open |
| View as | `All` and one button per Home the person is offered (Drishti Elements button group, one pressed); the choice is `homeCards.viewAs`, checked against the Homes offered; it limits ranking, layout and the tables asked to that Home |
| Since your last visit | `home_digest` (built in): change events of the Home's `events:` kinds and followed measures that moved since the snapshot of that day, from `?since=<UTC>`; the window is the visit before this one (`prevVisit`) while this visit is the one just recorded, else `lastVisit`; none: the Home's cut-off; the console adds the alerts fired since |
| A module's Homes | a module's `MeasureSource` and `HomeTable` beans reach the Homes through the registration bridge (`Extensions`, while the module is on); a table names its Homes (`homes()`), so it is served to them only; the console plugin's `home: needs:` adds a table's rows to Needs you |
| Per person | the person's setting `homeCards` `{pins, hidden, order, snooze, lastVisit, prevVisit, viewAs}` (`PATCH /api/v1/me/settings`), validated by the server; nothing per person is held in the console |

The genomics module's Home (`genomicist`, `config/homes/genomics/`) is the first module Home built this way: its figures (`genomics` measure source), its tables `genomics_gwas_top`, `genomics_manhattan`, `genomics_clinvar`, `genomics_loads`, `genomics_needs`, and its cards in `modules/genomics/console/plugin.yaml`.

