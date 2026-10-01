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
# Calc: Python on any view

Calc puts a Python prompt beside every view, the way BQuant sits beside a Bloomberg screen. Press `Alt+C` on a
trade, a netting set, a VaR result or a curve: a panel slides in with an editor, and the code you write there can
read the screen you are on, search Drishti, and draw tables and charts in the terminal's own style.

The code runs **in your browser**, in [Pyodide](https://pyodide.org) (CPython 3.14 compiled to WebAssembly), inside a
Web Worker, so a long calculation never freezes the page. numpy, pandas, scipy, statsmodels and matplotlib are
there. Nothing runs on the console or the server: every read your code makes is an ordinary read with your session,
so your roles and the field masking apply exactly as on the screen.

**Contents**

1. [When to use Calc, and when not](#1-when-to-use-calc-and-when-not)
2. [Opening it](#2-opening-it)
3. [The panel and its keys](#3-the-panel-and-its-keys)
4. [A first session: VaR from the scenarios](#4-a-first-session-var-from-the-scenarios)
5. [The `drishti` module](#5-the-drishti-module)
6. [Output: print, show, chart, figures](#6-output-print-show-chart-figures)
7. [Packages](#7-packages)
8. [Packs: switching Calc on, and starter snippets](#8-packs-switching-calc-on-and-starter-snippets)
9. [Roles: who may use Calc](#9-roles-who-may-use-calc)
10. [Saving your own snippets](#10-saving-your-own-snippets)
11. [Security model](#11-security-model)
12. [Installing the Python runtime](#12-installing-the-python-runtime)
13. [Sizes and performance, measured](#13-sizes-and-performance-measured)
14. [Limits](#14-limits)
15. [Configuration and API](#15-configuration-and-api)
16. [Troubleshooting](#16-troubleshooting)
17. [The `drishti.quant` module](#17-the-drishtiquant-module)
18. [Snippet catalogue](#18-snippet-catalogue)

---

## 1. When to use Calc, and when not

| You want to… | Use |
|---|---|
| Re-price a trade under a rate move, recompute a VaR, interpolate a curve, pivot a desk's P&L your own way, once | **Calc** |
| Try an idea on what is on the screen before asking for a panel | **Calc** |
| A figure everyone sees on a view, every time | a panel in the kind's Sutra ([Sutra guide](../../console/web/guides/sutra-guide.md)): computed on the server, cached, live |
| Find entities by value (`TRD where mtm > 1m`) | [search](USER_GUIDE.md#search-by-value), on the command line |
| Be told when a figure crosses a line | an [alert](USER_GUIDE.md#alerts): the server checks it on every change |
| The same search as a file every morning | a [scheduled report](USER_GUIDE.md#scheduled-reports) |
| A long notebook, a model you keep in git, millions of rows, your own libraries | Python on your machine with a personal API token ([CLIENTS.md](CLIENTS.md)) |

Calc is for questions that are yours, now, about the data in front of you. It is not a batch engine: it runs in one
browser tab, with that tab's memory, and it reads what you could read by clicking (see [Limits](#14-limits)).

## 2. Opening it

- Press **`Alt+C`** on a view, or click **Alt+C Calc** in the row of keys at the bottom of the screen. Press `Alt+C`
  or `Esc` again to close it. (On a Mac, `Option+C`.)
- The first time in a browser tab, Python starts: the status line says *Starting Python…* and then
  *Python ready · Pyodide 314.0.7 · 1.3 s*. Later opens in the same tab are instant; Python keeps running while the
  drawer is closed, and your variables are kept until you press **Stop** or leave the page.

`Alt+C` is chosen because the function keys belong to each view: a Sutra may bind any of `F2`–`F12` to its panels and
links (see [Keyboard](USER_GUIDE.md#keyboard)), and `Alt+` keys are the terminal's own (`Alt+←` back, `Alt+1`…`Alt+4`
workspace panes).

A view offers Calc when both hold:

1. **The pack that owns the view's kind switches Calc on** (`python: { enabled: true }` in its `pack.yaml`, see
   [section 8](#8-packs-switching-calc-on-and-starter-snippets)). The banking packs and `finance` do.
2. **One of your roles has the `calc` power** (see [section 9](#9-roles-who-may-use-calc)).

Without the role, the key is still shown, greyed, with the reason: *You do not have access: Calc needs a role with
calc (ask an administrator)*. On a kind whose pack does not switch Calc on, there is no key. Views inside workspace
panes have no Calc; open the view on its own.

## 3. The panel and its keys

```text
| Calc  Python · VAR-RATES   Done · 1.9 s · packages 373 ms · Python 1.5 s · memory 79 MB          ?  × |
| [▶ Run] [■ Stop] [VaR and expected shortfall … ▾] Save  Delete  Clear        Ctrl+Enter run · Esc close |
|  1 import numpy as np                                                                                 |
|  2 pnl = np.asarray(view.doc["scenarioPnl"], dtype=float)                                             |
|  …                                                                                                    |
| Output | History                                                                                       |
| VAR-RATES: 500 scenarios        [Filter rows] « ‹ 1–4 of 4 › » ▲ ▼ [25 rows]                           |
| measure     recomputed      engine                                                                    |
| VaR 99%      9,611,219   9,611,219                                                                    |
| …                                                                                                     |
```

| Key or control | Does |
|---|---|
| `Alt+C` | Open or close Calc |
| `Ctrl+Enter` (`Cmd+Enter` on a Mac), **Run** | Run the code in the editor |
| **Stop** | End the Python worker at once (a loop that never ends, a calculation too slow) and start a fresh one. Variables from earlier runs are gone |
| `Tab`, `Shift+Tab` | Indent and outdent by four spaces |
| `Esc` | Close the panel (Python keeps running) |
| **Snippets ▾** | Load a snippet: *From the packs* for this kind, and *Mine* |
| **Save** | Save the code as one of your snippets (asks for a name; the name of the snippet you loaded is offered) |
| **Delete** | Delete the snippet of yours you loaded |
| **Clear** | Clear the output |
| **Output**, **History** | The output of the last run; your last 30 runs in this browser (click one to put its code back in the editor) |
| `?` | This guide |

The status line says what Python is doing (*Loading numpy, pandas…*, *Reading search…*, *Running…*) and, after a
run, how long it took, split into **packages** (loading libraries the code imports, the first time only) and
**Python** (your code), how many reads it made and how much they weighed, and the memory Python holds.

- **Each run clears the output** and runs the whole editor. Variables persist between runs (as in a notebook cell
  run again), so you can build on earlier results; **Stop** starts clean.
- **The last expression is shown**, as in a notebook: a DataFrame as a table, anything else as text.
- **An error** shows its message and the traceback from your code (Python's own machinery is left out), and the line
  is highlighted in the editor.
- **Your draft is kept** per kind in this browser: open Calc on another trade and the code you were writing is there.

## 4. A first session: VaR from the scenarios

1. Open `VAR VAR-RATES <GO>` and press `Alt+C`. Wait for *Python ready*.
2. Pick **VaR and expected shortfall from the scenario P&L** in **Snippets ▾**. The editor fills with:

   ```python
   import numpy as np
   import pandas as pd

   pnl = np.asarray(view.doc["scenarioPnl"], dtype=float)
   worst = np.sort(pnl)
   n = len(pnl)
   var99 = -worst[n // 100 - 1]                  # the 1% quantile: the 5th worst of 500 scenarios
   es975 = -worst[: int(np.ceil(n * 0.025))].mean()   # the mean of the worst 2.5%
   …
   chart(pd.DataFrame({"pnl": pnl}), kind="hist", y="pnl", bins=40, title="Scenario P&L")
   ```

3. Press `Ctrl+Enter`. The first run loads numpy and pandas (about 2 s); you should see a table with
   **VaR 99% 9,611,219** recomputed beside the engine's **9,611,219**, ES 97.5% **9,802,031** (the engine's sample
   figure is **10,860,677**), a histogram of the 500 scenarios, and *VaR uses 57% of the limit 16,740,000*.
4. Change `n // 100 - 1` to `int(n * 0.025) - 1` and run again: the 97.5% VaR, in 6 ms this time.

## 5. The `drishti` module

Every run starts with these names defined (no import needed): `view`, `drishti`, `show` and `chart`. In your own
modules or functions, `import drishti` works too, and `from drishti import view, show, chart`.

The pricing and risk maths the snippets share (Black-Scholes, Black-76, Garman-Kohlhagen, Bachelier, curves, bonds,
VaR, CVA, FRTB aggregation, …) is a submodule: `from drishti import quant as q` ([section 17](#17-the-drishtiquant-module)).

### 5.1 `view`: the screen you ran from

| Attribute | Type | What it is |
|---|---|---|
| `view.kind`, `view.id` | str | The entity: `'var'`, `'VAR-RATES'` |
| `view.title` | str | The title the view shows |
| `view.business_date` | str or None | The business date of the data (`'2026-09-30'`), or None for undated data |
| `view.provenance` | dict | Source, generation, business date, last update (as in the footer of the view) |
| `view.doc` | dict | The entity's document as your role may see it (the raw JSON of `F9`, masked fields read `•••`) |
| `view.strip` | dict | The strip at the top of the view: label → shown text |
| `view.tables` | dict | Every panel that holds rows, as a pandas DataFrame, by the panel's title |
| `view.table(name)` | DataFrame | One panel's rows, by title or by panel id |
| `view.panels` | list | The view model's panels as the server sent them (`id`, `kind`, `title`, `data`) |

`view` is read fresh at every run: on a live view, run again and you get the latest figures.

How each panel kind becomes a DataFrame in `view.tables`:

| Panel kind | Columns |
|---|---|
| `table`, `ladder` | the panel's columns; numeric columns parsed to numbers (below) |
| `kv`, `status`, `provenance` | `label`, `value` |
| `line`, `area` | index `x`, a column per series |
| `bars` | `label`, `value` |
| `waterfall` | `label`, `value`, `from`, `to`, `total` |
| `histogram` | `from`, `to`, `count`, `label` |
| `scatter` | `label`, `group`, `x`, `y`, `size` |
| `pivot` | index the row labels, a column per pivot column |
| `candlestick` | `x`, `open`, `high`, `low`, `close`, `volume` |
| `timeline` | `date`, `label`, `detail`, `status` |
| `surface` | index `y`, a column per `x` |

Tables hold what the screen shows, so their numbers are **as formatted**: `"−1,234"` is read as `-1234.0`,
`"USD 1,300,000"` as `1300000.0`, `"1.5m"` as `1500000.0`, and `"4.08%"` as `4.08` (as written, not 0.0408).
`drishti.number(text)` does the same to any text. For exact values, read `view.doc`.

```python
trades = view.tables["Member trades"]                  # NSET NS-NORTH-01 (finance pack)
trades.groupby("Product")["MTM (USD)"].sum()
```

### 5.2 Reading data

Each call is a request from your browser to the console with your session, exactly as when you click: your roles
decide what you may open, and fields masked for your role stay masked. A refused or failed read raises
`drishti.DrishtiError` with its `code` (`DRS-5002` not allowed, `DRS-1001` not found, …), `detail` and `status`.

**`drishti.get(kind, id, as_of=None) -> dict`**

An entity's document (as `F9` shows it to you). `as_of` is a business date (`'2026-09-25'` or a `date`); by default,
the date the screen shows.

```python
nset = drishti.get("netting-set", view.doc["nettingSet"])
nset["netMtm"], nset["utilisation"]
```

**`drishti.search(query, as_of=None) -> DataFrame`**

A structured search, as typed on the command line ([Search by value](USER_GUIDE.md#search-by-value)): a column `id`,
`title` when it says more than the id, then the fields the query names and the kind's key fields, without `$.`.
At most 1000 rows (`limit 1000`; the default is 100). When more matched, a note says how many; `df.attrs` holds
`matched`, `scanned` and `partial`.

```python
trades = drishti.search("TRD where counterparty.id = 'CP-NORTHBRIDGE' and nettingSet != '' limit 1000")
trades.groupby("nettingSet")["mtm"].agg(["count", "sum"])
```

A field appears as a column when the query names it, so name the fields you want to group by in the condition
(`and nettingSet != ''` above).

**`drishti.columns(kind, paths, as_of=None, limit=None) -> DataFrame`**

Every entity of a kind on the business date, with the fields a source keeps as columns (a Delta table laid out by its
pack, Aerospike's promoted bins, the PostgreSQL entity table): the whole day, without reading a document. Indexed by
id; `df.attrs` holds `businessDate`, `total` and `masked` (fields your role sees masked). `drishti.columns("TRD", [])`
lists the fields kept as columns.

```python
df = drishti.columns("TRD", ["mtm", "book", "desk", "risk.dv01", "counterparty.id"])
len(df)                                                   # 10000 with tools/load-delta.sh --trades 10000
df.groupby("desk")["risk.dv01"].sum()
```

It needs a source that keeps columns: on the demo samples (no lake) it answers *no source of trade keeps its fields
as columns here: use drishti.search()*. It returns at most `drishti.calc.max-column-rows` entities (250,000), the
first by id, with a note when cut; it needs the `calc` power and is recorded in the access log as a search.

**`drishti.history(kind, id, field, days=30) -> DataFrame`**

One field of an entity over the last `days` business days (2–260), oldest first, indexed by date: the same series as
clicking a value on a view ([A number over time](USER_GUIDE.md#a-number-over-time)).

```python
h = drishti.history("trade", view.id, "mtm", 60)
h["mtm"].diff().describe()
```

**Async variants: they work in every browser.** `get_async`, `search_async`, `columns_async` and `history_async`
take the same arguments and are awaited: `doc = await drishti.get_async("trade", "MX-20000001")`. Top-level `await`
works in Calc, and a last line that is an un-awaited read (`drishti.get_async("trade", "MX-20000001")`) is awaited for
you. The plain functions pause Python while the page fetches, which needs JavaScript Promise Integration (JSPI):
current Chrome, Edge and Firefox have it (verified in Firefox 155), Safari does not, and a browser can have it
switched off. Without it:

- the status line says *reads need await (see help)*, and a note beside the output tabs says *This browser cannot
  pause Python for a read: write await drishti.get_async(…), search_async, columns_async or history_async. view works
  as it is.*;
- `view` (the screen's document and tables) works as it is: it is read before the code runs;
- a plain call raises an error that spells the call out with `await`, for example *drishti.get() needs JavaScript
  Promise Integration … write `await drishti.get_async('trade', 'MX-20000001')` instead*;
- every starter snippet the packs ship runs: they read with the `_async` forms (a test keeps it so).

This was run in a real Firefox 155 with JSPI switched off (`javascript.options.wasm_js_promise_integration: false`),
on the sample data: the two snippets that read (`desk_pnl_by_book.py`, `mtm_concentration.py`) and the four that use
`view` only all finished.

**`drishti.help()`** prints this summary; **`drishti.LIMITS`** is the output budget (below).

## 6. Output: print, show, chart, figures

| You write | You get |
|---|---|
| `print(...)` | Text in the output (standard error in amber) |
| an expression on the last line | It is shown: a DataFrame as a table, anything else as text |
| `show(df, title=None)` | A table, sorted, filtered and paged like every Drishti table (25 rows a page, `Filter rows`, `▲ ▼`), numbers right-aligned with separators and negatives in the negative colour. A Series, a list of dicts, a dict and a numpy array work too; anything else is shown as text |
| `chart(df, kind="line", x=None, y=None, title=None, bins=30)` | A chart drawn by the page with the vendored ECharts in the current theme: `line`, `bar`, `scatter` or `hist`. `x` is a column (default the index); `y` a column or a list (default every numeric column). `hist` counts one column in `bins` bins |
| a matplotlib figure | A PNG image (the `agg` backend): every open figure is shown after the run, and closed. `show(fig)` shows one at once |

The output of one run is capped (`drishti.LIMITS`): 2,000 rows per table (and 200,000 cells), 5,000 points per chart
series (more are thinned, with a note), 8 MB in all, and 200,000 characters of text. Beyond a cap you get a note,
not an error: show a smaller piece (`df.head(50)`, a `groupby`).

## 7. Packages

| Package | Version | Loads when |
|---|---|---|
| The standard library | CPython 3.14.2 | Python starts (first open) |
| numpy, pandas (with python-dateutil, pytz, six) | 2.4.6, 3.0.2 | the code first uses data (`view.tables`, `search`, `show`, `chart`, `pd`, `np`, …) |
| scipy | 1.18.0 | the code imports it |
| statsmodels (with patsy, packaging) | 0.14.6 | the code imports it |
| matplotlib (with contourpy, cycler, fonttools, kiwisolver, pillow, pyparsing) | 3.10.8 | the code imports it |

Packages load once per tab and come from the console itself. Anything else (`import sklearn`) fails at once with
*No module named 'sklearn' (Calc has numpy, pandas, scipy, statsmodels, matplotlib and the standard library)*:
there is no `pip` and no internet in Calc.

## 8. Packs: switching Calc on, and starter snippets

A pack switches Calc on for the kinds it owns, and may ship snippets, in its `pack.yaml`:

```yaml
python:                         # Calc on this pack's kinds (Alt+C on a view)
  enabled: true
  snippets:                     # optional: starters offered in Snippets ▾
    - title: MTM under parallel rate moves
      description: The swap's MTM after parallel moves of -100 to +100 bp from its DV01.
      kinds: [trade]            # the kinds it is offered on; none: every kind of the pack
      code: |
        mtm, dv01 = view.doc["mtm"], view.doc["risk"]["dv01"]
        ...
  dir: python                   # optional: where snippet files are (default python/)
```

Snippets can also be files, `packs/<pack>/python/*.py`, read in name order after those in `pack.yaml`. A file starts
with comment lines naming it; what comes after them is the code (the copyright header above them is not):

```python
# title: VaR and expected shortfall from the scenario P&L
# description: Historical-simulation VaR (99%) and ES (97.5%) recomputed with numpy from the scenario P&Ls.
# kinds: var

import numpy as np
...
```

Rules:

- **Calc is offered on a kind's views when the pack that owns the kind enables it.** A pack's snippets are offered on
  the kinds they name (any kind, even one another pack owns) or, naming none, on the pack's own kinds; only packs
  that enable Calc offer snippets, and only the user's active packs.
- The server reads `python:` and the files at start-up (`GET /api/v1/packs` carries them to the console, with the
  pack's kinds); restart the server after changing them. A file larger than 64 KB is skipped; at most 100 snippets
  a pack.
- Snippets are starters, not programs: they are code the user sees, edits and runs, with the user's own rights.

### The banking packs' snippets

The five banking packs ship **75 snippets** and `finance` **10** (two inline in its `pack.yaml`, eight files): curves,
FX, vol surfaces, options, bonds, credit, VaR and its backtest, stress, FRTB, exposure, CVA, collateral, SA-CCR,
desks, books and counterparties, each written the way a trader or a quant would use it on that screen. The
[Snippet catalogue](#18-snippet-catalogue) lists every one, with the sample entity it was checked on and what it
computes.

The banking ones are written by `tools/packgen/banking/make_packs.py` from their sources,
`tools/packgen/banking/snippets/<pack>/<name>.py` (read by `calc_snippets.py`; never edit the generated
`packs/<pack>/python/` files: change the source and run the generator, see
[PACKS.md](PACKS.md#how-the-shipped-packs-are-generated)). A source starts, after its copyright header, with

```python
# title: Key-rate DV01 by bump and reprice
# description: A 10Y receive-fixed swap at par (100m) repriced with each pillar moved 1 bp in turn, ...
# kinds: ir-curve
# example: CRV CRV-USD-OIS
```

`example` names the sample entity the snippet is checked on in a browser (it is not copied into the pack); the
generator refuses a source without the four lines or with more than 60 lines of code, and `make_packs.py --check`
fails when a generated file is stale or has no source. `finance` is hand-written: its files are
`packs/finance/python/*.py`.

How they are written, so that each runs anywhere:

- **Reads use the `_async` forms** (`await drishti.search_async(...)`, `get_async`), so a snippet runs in every
  browser, not only those with JavaScript Promise Integration (section 5.2).
- **A search returns the fields its condition names**, so each names the fields it uses: `and pnl1d > -1000bn`
  names a field without dropping any row; a missing field reads as "not 0", so `and risk.dv01 != 0` keeps every trade.
- **The screen's own document first** (`view.doc`), then the entities it links to (`discountCurve`, `fxVolSurface`,
  `creditCurve`, …), then one search for book-wide work. No snippet needs a lake (`drishti.columns()`), so each runs
  on the samples as shipped.
- **Simplifications are said** in the description and a comment: single-curve swaps, flat cap vols, European pricing
  of American options, first-order stress, the FRTB and SIMM aggregation formulas without their full parameter sets.
- **The samples are synthetic**: some booked strikes sit far from spot, and some booked figures (MTM, DV01, Z-spread)
  do not follow from the market data. The option snippets then price at the money and say so; the others show their
  figure beside the booked one rather than hide the gap.

## 9. Roles: who may use Calc

Calc is a power of a role, like raw JSON or authoring Sutras: **`calc`**. A person may use Calc if any of their roles
has it. What their code may read is decided as always, call by call, by the kinds their roles open and the packs
they have.

Who has it out of the box:

| Role | From | `calc` |
|---|---|---|
| `admin`, `author`, `approver` | configuration (`drishti.security.roles`) | yes |
| `viewer` | configuration | **no** |
| `trader` (`trading`), `market-risk` (`market-risk`), `credit-risk` (`counterparty-risk`) | the banking packs | yes |
| `trader`, `risk` (`finance`) | the finance pack | yes |
| roles of packs that do not enable Calc (`climate-analyst`, `economist`, `scientist`, `treasury`, `oprisk`, `retail`, `analyst`, `ops`) | those packs | no (their kinds offer no Calc) |
| roles you define in Admin → Roles | the identity database | as you tick it |

Give it:

- **Admin → Roles**: tick *Use Calc: Python in the browser on what the role opens* in the role's dialog. The roles
  list shows **Calc** among each role's powers. It applies at the holder's next click (within a minute for the
  console's offer of the panel), and is audited (`role-updated … calc`).
- **Configuration or a pack**: `calc: true` on the role (`roles: { quant: { kinds: ["*"], calc: true } }`).
- **API**: `PUT /api/v1/admin/role-definitions/{name}` with `"calc": true` ([USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md#7-the-api)).

The power is kept in the identity database's `drishti_role_power` table (one row per role and power), which a
database made by an earlier release gains at the next start, without a migration.

## 10. Saving your own snippets

**Save** keeps the editor's code on the server as one of your snippets: a name (1–80 letters, digits, spaces and
`. _ ( ) -`), the code (at most 50,000 characters), and the kind you wrote it on. They follow you to any browser,
are listed under **Mine** in **Snippets ▾** on every view with Calc, and only you see them. Load one, change it and
**Save** again to replace it; **Delete** removes it.

They are kept with your other saved documents (workspaces, monitors) in the identity database
(`drishti_preference`, namespace `calc-snippets`), at most 50 per person, and deleted with the person.

## 11. Security model

| Question | Answer |
|---|---|
| Where does my code run? | In your browser, in a Web Worker: CPython compiled to WebAssembly. Never on the console or the server. Closing the tab ends it. `drishti.quant` is a file the worker fetches from the console's own `/static/calc/quant.py` the first time a cell names `quant`. |
| What can it read? | Only what you can: every read is a request from your page to the console with your session (`/api/view`, `/api/raw`, `/api/series`, `/api/calc/search`, `/api/calc/columns`), and the console asks the server as you. Your roles and packs decide; fields masked for your role (`drishti.security.redact`) stay masked. The worker itself holds no credential: it asks the page by message, and the page fetches. |
| Can it reach the network? | No. The worker's Content Security Policy is `default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'`: scripts and data from the console's own origin only. The runtime and its packages are served by the console under `/pyodide/<version>/`, and Pyodide is told every location explicitly (index, packages, lock file, standard library), so it never looks at its default CDN. Measured: no request left the origin in any test run (section 13). |
| Can it change data? | No more than you can by clicking. The only writes Calc adds are your own snippets. |
| What else changed in the page's security? | The page's policy adds `worker-src 'self'`; `'wasm-unsafe-eval'` (compiling WebAssembly) is granted to the Calc worker's script alone, and `'unsafe-eval'` to nothing. |
| Is it recorded? | The reads are recorded as any read is ([access log](../admin/USER_MANAGEMENT.md#who-looked-at-what-the-access-log)): `view`, `raw`, `history`, and `search` for searches and for `drishti.columns()` (with the fields read). The code itself is not sent anywhere unless you save it. |
| Who may use it? | Roles with `calc` ([section 9](#9-roles-who-may-use-calc)); the server checks it on its Calc endpoints, the console on its own. |
| Can one user's code affect another's? | No: each tab has its own worker and memory. A loop that never ends uses one core of your machine until you press **Stop**. |

## 12. Installing the Python runtime

The runtime is not in git (53 MB, which `du -sh` shows as 51M). `tools/fetch-pyodide.sh` installs it once:

```bash
tools/fetch-pyodide.sh
```

You should see:

```text
downloading https://github.com/pyodide/pyodide/releases/download/314.0.7/pyodide-314.0.7.tar.bz2 (about 340 MB, once)
verified /home/you/.cache/drishti/pyodide-314.0.7.tar.bz2 (SHA-256 192b5864e6e6d30a…)
packages: contourpy, cycler, fonttools, kiwisolver, matplotlib, numpy, packaging, pandas, patsy, pillow, pyparsing, python-dateutil, pytz, scipy, six, statsmodels
installed Pyodide 314.0.7 in …/console/web/static/vendor/pyodide: 51M on disk, 23 files
```

What it does:

1. Downloads the pinned release (Pyodide **314.0.7**, the full distribution from the official GitHub releases) into
   `~/.cache/drishti/` (`--cache` elsewhere), once.
2. Checks its **SHA-256** against the one pinned in the script, and refuses any other file.
3. Unpacks only the core runtime (`pyodide.mjs`, `pyodide.asm.mjs`, `pyodide.asm.wasm`, `python_stdlib.zip`) and
   numpy, pandas, scipy, statsmodels and matplotlib with everything they depend on, and writes a lock file that lists
   only those (so an import of anything else fails at once instead of asking for a file).
4. Writes `.drishti-pyodide` (version, SHA-256, packages) and replaces the folder as a whole.

Run it again and it says *already installed*; `--force` unpacks again. It needs bash and python3, and curl, wget or
python3 to download.

| Situation | Do |
|---|---|
| A machine with internet | `tools/fetch-pyodide.sh` |
| An offline machine | download `pyodide-314.0.7.tar.bz2` elsewhere, copy it over, `tools/fetch-pyodide.sh --tarball pyodide-314.0.7.tar.bz2` (the hash is still checked) |
| The console Docker image | nothing: `deploy/console.Dockerfile` runs the script in a build stage (it reuses a runtime already in the build context), so the image carries the runtime and a running console needs no internet |
| Upgrading Pyodide | change `VERSION` and `SHA256` in the script (the release's `.tar.bz2` digest), run it, restart the console. The URL names the version, so browsers fetch the new runtime at once |

Restart the console after installing: it mounts the runtime at `/pyodide/<version>/` at start-up, served with
`Cache-Control: public, max-age=31536000, immutable` (the version is in the URL), so a browser downloads it once.

Check it:

- `curl -s localhost:17480/healthz` says `{"status":"UP","pythonRuntime":"314.0.7"}`;
- **Help → About** shows *Python runtime (Calc): Pyodide 314.0.7 · 53.2 MB, served by this console*.

Without it, views still offer Calc to those allowed, and the panel says **Python runtime not installed: run
tools/fetch-pyodide.sh** (Run is disabled); `/healthz` and About say the same.

## 13. Sizes and performance, measured

Measured on 2026-10-01 with headless Chrome 154 on the development machine (24 cores), console and server on
localhost, the banking packs' sample data and a scratch lake of 10,000 trades a day (`tools/load-delta.sh --trades
10000`). Each figure is a fresh browser (an empty cache) unless it says *warm*.

| What | Measured |
|---|---|
| Runtime on disk | **53.2 MB** (51 MiB), 23 files: the core 13.4 MB (`pyodide.asm.wasm` 9.6 MB, `python_stdlib.zip` 2.5 MB, `pyodide.asm.mjs` 1.3 MB), numpy and pandas 7.9 MB with their dependencies, scipy 14.0 MB, statsmodels 8.2 MB, matplotlib 9.5 MB with its dependencies |
| First open, `Alt+C` to *Python ready* (core runtime) | **1.3 s** (the worker itself: 1.1 s) |
| After **Stop** (runtime from the browser's cache) | **1.0 s** |
| First run that uses data (numpy and pandas loaded and imported) | **1.9–2.0 s** (loading 0.37 s, first `import pandas` and the run 1.5–1.6 s) |
| First run with scipy and matplotlib (the curve snippet) | **5.0 s** (loading 1.3 s, imports and run 3.7 s) |
| First run with statsmodels | **5.5 s** (loading 1.3 s, imports and run 4.2 s) |
| The same runs again (warm) | VaR **7 ms**, rate shift **7 ms**, concentration 35–49 ms (one search), desk pivot **87 ms** (one search), curve with a figure **99 ms** |
| `drishti.columns()` of 10,000 trades × 5 fields | one read of 646 KB; the whole cell (read, two groupbys, a 50-bin histogram) **92 ms** warm |
| Memory Python holds (WebAssembly heap) | 31 MB started, **79 MB** with pandas, **163 MB** with scipy, matplotlib or statsmodels |
| Requests leaving the console's origin | **none**, in every run (page and worker requests recorded) |

Speed beside native CPython 3.14 on the same machine (best of three):

| Work | Native | Calc | Calc is |
|---|---|---|---|
| A pure-Python loop of a million steps | 20.8 ms | 78.8 ms | 3.8× slower |
| numpy: sort a million floats | 6.1 ms | 78.2 ms | 13× slower |
| numpy: 500 × 500 matrix product | 1.1 ms | 72.1 ms | 65× slower (no multithreaded BLAS, no SIMD) |
| pandas: groupby-sum of 200,000 rows | 8.8 ms | 13.6 ms | 1.5× slower |

## 14. Limits

- **Memory per tab.** Python's memory is WebAssembly memory in the tab: at most 4 GB, in practice 1–2 GB before the
  browser refuses to grow it. Every run's data stays until you drop it (`del df`) or press **Stop**; the status line
  shows what Python holds.
- **Speed.** Pure Python runs at a third to a quarter of native speed; vectorised pandas is close to native; heavy
  linear algebra is far slower (one thread, no SIMD). Calc is for seconds of work, not minutes.
- **Data volume.** A read moves JSON from the server through the console to the page and into Python: about 13 bytes
  a value for `drishti.columns()` (10,000 rows × 5 fields: 646 KB). 250,000 rows × 5 fields is about 16 MB, and a few
  seconds. Ask for the fields you need, aggregate with a search where you can, and use a script with a token
  ([CLIENTS.md](CLIENTS.md)) for millions of rows.
- **Searches** return at most 1000 rows (`limit`), and read at most `drishti.search.max-scan` documents (20,000) when
  the source keeps no columns. `drishti.columns()` returns at most `drishti.calc.max-column-rows` (250,000).
- **Output** is capped per run ([section 6](#6-output-print-show-chart-figures)).
- **No network and no installs**: only the packages in [section 7](#7-packages).
- **One run at a time** per tab; **Stop** to interrupt one.
- **Browsers.** A current Chrome, Edge, Firefox or Safari runs Pyodide in a module worker; the plain (non-`await`)
  reads need JavaScript Promise Integration (current Chrome, Edge and Firefox; not Safari); the `_async` forms, and
  the starter snippets, work everywhere ([section 5.2](#52-reading-data)).

## 15. Configuration and API

| Setting | Default | Meaning |
|---|---|---|
| Server `drishti.calc.enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Calc at all; off, nobody may use it and its endpoints refuse (`DRS-5002`) |
| Server `drishti.calc.max-column-rows` | `250000` | `drishti.columns()` returns at most this many entities |
| Server `drishti.calc.columns-budget` | `20s` | How long a columns read may wait for its source |
| Server `drishti.calc.max-snippet-chars` | `50000` | A saved snippet's code, at most (60,000 at most in any case) |
| Console `calc.enabled` | `true` (`DRISHTI_CALC_ENABLED`) | Off, no view offers Calc, whatever packs and roles say |

See [CONFIGURATION.md](../admin/CONFIGURATION.md#drishticalc--python-in-the-browser).

Server endpoints (under `/api/v1`, with a token as usual):

| Method | Path | Does |
|---|---|---|
| `GET` | `/calc/settings` | `{"enabled", "allowed", "maxColumnRows", "maxSnippetChars"}` for the caller |
| `GET` | `/me/calc-snippets` | The caller's snippets (needs `calc`) |
| `PUT` / `DELETE` | `/me/calc-snippets/{name}` | Save `{"code", "description", "kind"}` / delete one (needs `calc`) |
| `GET` | `/search/columns/{kind}?paths=mtm,book&limit=` | Whole columns of the business date (needs `calc` and the kind); without `paths`, the fields kept as columns |
| `GET` | `/packs` | Each pack now carries `python: {enabled, snippets: [{title, description, kinds, code, file}]}` |

The console's own (same-origin, for the page): `GET /api/calc/snippets?kind=`, `PUT` / `DELETE /api/calc/snippets/{name}`,
`GET /api/calc/search?q=`, `GET /api/calc/columns/{kind}?paths=&limit=`, each refusing users without `calc` (`403
DRS-5002`), and the runtime under `/pyodide/<version>/`.

## 16. Troubleshooting

| You see | Why | Do |
|---|---|---|
| No **Alt+C Calc** key on a view | The pack that owns the kind does not enable Calc, or the view is in a workspace pane | Enable it in the pack (`python: { enabled: true }`, [section 8](#8-packs-switching-calc-on-and-starter-snippets)), restart the server; open the view on its own |
| The key is greyed: *Calc needs a role with calc* | None of your roles has the `calc` power | Ask an administrator ([section 9](#9-roles-who-may-use-calc)) |
| *Python runtime not installed: run tools/fetch-pyodide.sh* | The console has no runtime folder | Run the script on the console's machine (or rebuild the image), restart the console ([section 12](#12-installing-the-python-runtime)) |
| *Python could not start: … Content Security Policy …* | A proxy in front of the console rewrites security headers | Let the console's own headers through for `/static/js/calc-worker.js` |
| *Python could not start: Failed to fetch* | The runtime was upgraded while the page was open | Reload the page |
| *No module named 'sklearn' (Calc has numpy, pandas, …)* | Only the packages of [section 7](#7-packages) exist | Use what is there, or a script with a token |
| *drishti.get() needs JavaScript Promise Integration … write `await drishti.get_async('trade', 'MX-20000001')` instead* | No JavaScript Promise Integration (Safari, or a browser with it switched off) | Write the call as the message says: `await drishti.get_async(...)` (and `search_async`, `columns_async`, `history_async`) |
| `DrishtiError: DRS-5002: tina may not open var entities` | Your roles do not open that kind | As on the screen: ask for a role that opens it |
| `DrishtiError: DRS-5001: no source of trade keeps its fields as columns here` | `drishti.columns()` needs a laid-out source (a lake) | Use `drishti.search()`, or load the lake (`tools/load-delta.sh`) |
| `DrishtiError: DRS-5001: not kept as columns for trade: [notional]` | That field is not promoted | `drishti.columns("TRD", [])` lists those that are |
| A column of `•••` | Your role sees that field masked | As on the screen: `raw` access shows it |
| *output capped at 8 MB for one run* | The output budget ([section 6](#6-output-print-show-chart-figures)) | Show less: `df.head()`, a `groupby` |
| The run never ends | A loop that does not stop, or work too heavy | **Stop**, then run something smaller |
| The tab grows slow or crashes | Python's memory (the status line shows it) | `del` large frames, **Stop**, read fewer rows |
| *search: 1,000 of 1,234 matching shown* | A search returns at most 1000 rows | Narrow the condition, or `drishti.columns()` on a lake |

## 17. The `drishti.quant` module

`drishti.quant` is the pricing and risk maths the snippets share, in one file (`console/web/static/calc/quant.py`, about
740 lines of Python and numpy, no scipy, so it loads in a blink). The worker fetches it from the console the first time a
cell names `quant`; outside Calc it is an ordinary module.

```python
from drishti import quant as q

curve = q.ZeroCurve.from_points(view.doc["points"])          # CRV CRV-USD-OIS: zero rates in %, log-linear on DFs
par = q.par_swap_rate(curve, 10)                             # 0.038633: the 10Y par rate, annual fixed
q.swap_pv(curve.shifted(+1), par, 100e6, 10)                 # -84,732: receiving 100m at par loses this for +1 bp
q.black_scholes(100, 100, 1, 0.05, 0.2)                      # 10.4506
q.bs_greeks(100, 110, 0.75, 0.04, 0.3, q=0.01)["vega"]       # 33.91 per 1.00 of vol: 0.339 a vol point
q.implied_vol(12.3, lambda s: q.black_scholes(100, 105, 1, 0.03, s))   # 0.3299
q.historical_var(view.doc["scenarioPnl"], 0.99)              # VAR VAR-RATES: 9,611,219, the engine's figure
```

Conventions: times in years (ACT/365F); rates, vols and yields as decimals (`0.035`, not `3.5`); zero rates
continuously compounded (`df = exp(-z t)`); spreads in bp where an argument says `_bp`. Pricing functions take
scalars or numpy arrays (broadcast) and return the same shape.

| Function | Does |
|---|---|
| **Dates and tenors** | |
| `tenor_years(tenor)` | `'3M'` 0.25, `'18M'` 1.5, `'10Y'` 10, `'1W'`, `'ON'`, `'1Y6M'`; a number is returned as it is |
| `month_code_years(code, as_of)` | A futures month code (`'X6'`: November 2026, `'Z27'`) as years from `as_of` to the middle of the month |
| `year_fraction(start, end, basis)` | ACT/365F, ACT/360, 30/360, ACT/ACT (simplified, days/365.25) |
| **Root finding and the normal distribution** | |
| `brent(f, a, b, tol, maxiter)` | Brent's method on a bracket (raises `ValueError` if `f(a)`, `f(b)` share a sign) |
| `newton(f, fprime, x0)` | Newton's method |
| `norm_cdf(x)`, `norm_pdf(x)`, `norm_ppf(p)` | The standard normal: `math.erfc` exactly; the quantile by Acklam's approximation and a Halley step (error below 1e-14) |
| **Curves** | |
| `ZeroCurve(times, zeros, method)` | A curve of continuously compounded zero rates: `'loglinear'` (linear in log DF, flat forwards: how OIS curves are built; the default), `'linear'` on zeros, `'cubic'` (a natural spline); flat outside the pillars |
| `ZeroCurve.from_points(points, tenor, rate, pct)` | From a document's points (`view.doc["points"]`, `["pillars"]`): tenor and zero rate in % |
| `ZeroCurve.from_dfs(times, dfs)` | From discount factors |
| `.zero(t)`, `.df(t)`, `.forward(t1, t2, simple)` | Zero rate, discount factor, forward rate (simple or continuously compounded) |
| `.shifted(bp, twist_bp, fly_bp, pivot, wing)` | A scenario curve: parallel; a twist pivoting at 5Y, linear in log maturity, scaled so 2s10s moves by `twist_bp`; a butterfly (wings up, belly down) |
| `.key_rate_bumped(i, bp)` | Pillar `i` alone moved (a triangular key-rate bump) |
| `df_from_zero`, `zero_from_df`, `forward_rate(df1, df2, t1, t2, simple)` | The conversions |
| `annuity(curve, maturity, freq, start)` | PV01 annuity of a fixed leg: sum of year fraction x DF |
| `par_swap_rate(curve, maturity, freq, start, projection)` | Par fixed rate, single-curve or with a projection curve for the forwards |
| `swap_pv(curve, fixed_rate, notional, maturity, freq, receive_fixed, start, projection)` | (fixed - par) x annuity x notional: a curve-risk tool (no stubs, fixings or accrued) |
| `bootstrap_par(maturities, par_rates, freq)` | Discount factors from par swap rates (the classic single-curve bootstrap) |
| **Bonds** | |
| `bond_cashflows(coupon, years, freq, face)` | Times, amounts and accrued of a bullet bond (a short first period when the years do not divide) |
| `bond_price(yld, coupon, years, freq, face, clean)` | Price from yield (street convention), clean by default |
| `bond_yield(price, ...)` | Yield from price (Brent) |
| `bond_risk(yld, coupon, years, freq, face)` | Macaulay and modified duration, convexity, DV01 per 100 face, dirty price |
| `z_spread(dirty_price, times, amounts, curve)` | The spread over a zero curve that reprices the cashflows |
| **Options** | |
| `black_scholes(S, K, T, r, sigma, q, call)`, `bs_greeks(...)` | Black-Scholes-Merton with a yield `q`; Greeks: price, delta, gamma, vega (per 1.00 of vol), theta (per year), rho |
| `black76(F, K, T, r, sigma, call)`, `black76_greeks(...)` | Options on forwards and futures |
| `garman_kohlhagen(S, K, T, rd, rf, sigma, call)`, `gk_greeks(...)` | FX options (Black-Scholes with the foreign rate as the yield) |
| `bachelier(F, K, T, sigma_n, df, call)` | The normal model, for rates options quoted in normal vol |
| `implied_vol(price, pricer, lo, hi)` | The vol at which `pricer(vol)` equals `price` (Brent) |
| `fx_strike_from_delta(S, T, rd, rf, sigma, delta, call)` | The strike of a spot delta (premium unadjusted) |
| `smile_from_rr_bf(atm, rr, bf)` | 25-delta call and put vols from ATM, risk reversal and butterfly |
| `svi_total_variance(k, a, b, rho, m, sigma)` | Raw SVI total variance (fit it with `scipy.optimize.least_squares`) |
| **Realised volatility** | |
| `close_to_close_vol(close)`, `parkinson_vol(high, low)`, `garman_klass_vol(open, high, low, close)` | Annualised (252 days) |
| **Risk** | |
| `historical_var(pnl, confidence)`, `expected_shortfall(pnl, confidence)` | As positive losses: the k-th worst scenario, the mean of the worst |
| `parametric_var(sigma, confidence, mu, horizon_days)` | Normal VaR, square root of time |
| `kupiec_pof(exceptions, n, confidence)` | Kupiec's test: likelihood ratio, p-value, Basel traffic light scaled to 250 days |
| `max_drawdown(values)`, `sharpe(pnl, periods)`, `hhi(amounts)` | Drawdown (amount, peak, trough, series), annualised Sharpe, Herfindahl index |
| **Credit** | |
| `hazard_from_spread(spread_bp, recovery)` | The credit triangle, s / (1 - R) |
| `forward_hazards(times, term_hazards)` | Piecewise-flat hazards from term (flat-to-tenor) hazards |
| `survival(t, times, hazards)` | Survival under piecewise-flat hazards |
| `cva_from_profile(times, ee, hazard_times, hazards, recovery, discount, rate)` | (1 - R) x sum EE x DF x marginal PD, with contributions by period |
| **FRTB (sensitivities-based method)** | |
| `frtb_bucket_charge(ws, rho)` | K_b = sqrt(max(0, sum WS^2 + sum rho WS_k WS_l)) |
| `frtb_across_buckets(kb, sb, gamma)` | sqrt(sum K_b^2 + sum gamma S_b S_c), with the capped S_b when negative |

**Tests.** `console/tests/test_quant.py` checks it against textbook values: Black-Scholes 10.4506 and 5.5735 (S = K
= 100, one year, 5%, 20% vol) and Hull's 4.76 and 0.81, put-call parity with a yield, every Greek against finite
differences, Hull's Black-76 put (1.12) and Garman-Kohlhagen call (0.0430), Bachelier at the money and its parity,
implied vol round trips, a 25-delta strike's delta, curves reproducing their pillars (the natural spline against
scipy's), flat-curve par rates, annuities and swap PVs, a bootstrap of flat par rates, a par bond at 100 and the
5-year 8% bond's Macaulay duration 4.3121 and modified 3.9927, convexity and DV01 against finite differences,
Z-spread round trips, the three vol estimators on a simulated path, the sample VaR (9,611,219, the engine's), Kupiec
against the chi-squared distribution, survival, CVA and the FRTB formulas. The console's own tests skip it (the
console needs no numpy); run it with numpy and scipy installed:

```bash
python -m pytest console/tests/test_quant.py        # 29 passed
```

## 18. Snippet catalogue

Every snippet the packs ship, by pack: **Snippets ▾** lists those for the view's kind. *On* is the kinds it is offered
on and, in brackets, the sample entity it was checked on (type it, `<GO>`, `Alt+C`, pick it). *Method* names the
`drishti.quant` functions, the libraries beyond numpy and pandas, and the reads it makes (`get`, `search`, `history`).
Where a formula is simplified, *Computes* says so.

All 85 were run in headless Chrome 154 (87 runs: one `finance` snippet on three curve types) against a scratch server and console with the samples as shipped (the banking
packs together, then `finance` on its own), each opened on its example view and run twice: all pass. The first run,
which loads numpy and pandas (and scipy, statsmodels or matplotlib where used), took 1.9 to 6.1 s (median 2.1 s); a
warm run 6 to 830 ms (median 39 ms; 64 of 87 runs under 100 ms; the slowest read every trade of a book).

### `banking-core` (10)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Book DV01 by currency and tenor, and the par-swap hedge** · `book_dv01_hedge.py` | `book` (`BOOK BOOK-RATES-2`) | Every trade of the book read for its bucketed DV01, summed by currency and tenor; then the hedge: for each bucket, the notional of a par swap at that tenor (on the currency's OIS curve, annual fixed, its DV01 from the annuity) that offsets the bucket's DV01, receive or pay. Each hedge is sized on its own bucket; the spill of a hedge's own risk into shorter buckets is ignored, a simplification. | quant: `ZeroCurve`, `annuity`, `par_swap_rate`, `tenor_years`; reads: search, get |
| **Counterparty capital: Basel IRB risk weight from PD** · `counterparty_irb.py` | `counterparty` (`CPTY CP-HALCYON`) | The counterparty's exposure at default from its netting sets' SA-CCR results, its one-year PD from KYC (floored at 0.03%), LGD 45% (foundation IRB, senior unsecured) and an effective maturity of 2.5 years: the Basel IRB corporate formula for capital (asset correlation, maturity adjustment, 99.9% conditional PD), risk-weighted assets, expected loss, and how capital moves with PD. The corporate curve for every counterparty, a simplification (banks and sovereigns have their own treatment). | quant: `norm_cdf`, `norm_ppf`; reads: get |
| **Currency exposure and maturity ladder** · `currency_exposure.py` | `desk`, `book`, `legal-entity` (`BOOK BOOK-RATES-1`) | The desk's or book's trades from one search, pivoted by currency and product family: MTM and notional; notional by currency and maturity bucket (a maturity ladder, 0-1Y to 30Y+), the notional-weighted average maturity per currency, and DV01 by currency where the trades carry it. | reads: search |
| **Desk performance: Sharpe, hit rate and return on VaR** · `desk_performance.py` | `desk` (`DESK DESK-RATES`) | The desk's daily P&L series (from its VaR result): cumulative P&L, annualised Sharpe ratio (no risk-free deduction), hit rate, average win against average loss, the worst day, maximum drawdown and the days to recover, and return on VaR (cumulative P&L over today's VaR); a rolling 20-day Sharpe and the cumulative P&L drawn. | quant: `max_drawdown`, `sharpe`; reads: get |
| **P&L by book: a pandas pivot** · `desk_pnl_by_book.py` | `desk` (`DESK DESK-RATES`) | The desk's positions pivoted by book and product family (MTM), and today's P&L by book and currency from the trades themselves (drishti.search_async()). | reads: search |
| **Top movers and P&L contribution** · `desk_top_movers.py` | `desk`, `book` (`DESK DESK-EQD`) | The desk's trades from one search: the ten best and worst by today's P&L, P&L by product with each product's share of the absolute move, and a Pareto view (how few trades make most of the day's P&L), with the desk's total against its derived P&L. | reads: search |
| **Counterparty group: members, exposure, expected loss** · `group_exposure.py` | `counterparty-group` (`GRP GRP-SUMMIT`) | The group's members read in full: rating, sector, net MTM, peak PFE and one-year PD; the group's exposure against its limit, each member's share, expected loss as PD x LGD 60% x peak PFE, a rating-weighted PD for the group, and the netting sets behind each member. | reads: get |
| **Issuer: bond spreads against CDS, the basis** · `issuer_bond_cds_basis.py` | `issuer` (`ISS ISS-GRANITE`) | The issuer's bonds read in full: yield, Z-spread over their benchmark curve recomputed from price (bullet, street convention), and the CDS spread at each bond's maturity interpolated on the issuer's credit curve; the CDS-bond basis (CDS less Z-spread, negative: the bond is cheap to the CDS), with the term structures drawn. Z-spread against a government curve, not the par-equivalent spread a desk would use: an approximation. | quant: `ZeroCurve`, `bond_cashflows`, `bond_yield`, `tenor_years`, `z_spread`; reads: get |
| **Legal entity: risk against return by book and desk** · `legal_entity_risk_return.py` | `legal-entity` (`LE LE-NY`) | The legal entity's books with their VaR, today's P&L and MTM: P&L per unit of VaR by book, books grouped by desk with VaR summed (no diversification, an upper bound) and the desk's P&L over VaR, the books in a risk-return scatter, and the entity's share of P&L and VaR by desk. | pandas |
| **A trader's book: mix, P&L and hit rate** · `trader_book.py` | `trader` (`TRDR TRDR-ASHAH`) | Every trade the trader booked (one search): count, notional and MTM by product and currency, today's P&L with the share of winning trades and the average win against the average loss, the ten largest positions by MTM, and how the trader's day compares with the desk's other traders. | reads: search |

### `counterparty-risk` (12)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Collateral: haircuts, concentration and a haircut stress** · `collateral_haircuts.py` | `collateral-balance` (`COLL COLL-HALCYON-NY`) | The collateral balance's positions: market value, haircut and value after haircut (checked against the booked value), concentration of the collateral by asset (HHI), collateral issued by the counterparty itself (wrong-way collateral), and the collateral value when haircuts double and triple and market values fall 5%, against what the netting set's CSA requires (net MTM less the counterparty's threshold): the shortfall a stress would leave. | quant: `hhi`; reads: get |
| **Collateralised exposure by Monte Carlo: threshold, MTA and MPoR** · `collateral_mpor_mc.py` | `netting-set` (`NSET NS-HALCYON-NY`) | A Monte Carlo of the netting set's value as a driftless Brownian motion whose volatility is read from the engine's 1Y expected exposure (EE = sigma sqrt(t / 2 pi)), with daily margining under the CSA: the collateral follows the value less the threshold, moves only above the minimum transfer amount, and lags by the margin period of risk. EE and PFE 95% with and without collateral by tenor (2,000 paths, a fixed seed). A teaching model, not the engine's. | quant: `tenor_years`; reads: get |
| **Exposure concentration: HHI by name, sector, rating and country** · `concentration_hhi.py` | `credit-limit`, `counterparty-group`, `counterparty` (`LIM LIM-SUMMIT`) | Every counterparty's peak PFE (one search) as the exposure measure: the Herfindahl-Hirschman index by counterparty, sector, rating and country (and the equivalent number of equal names, 1/HHI), the top five names' share, expected loss as PD x LGD 60% x peak PFE from each counterparty's one-year PD, and exposure by rating grade. Peak PFE overstates expected exposure; it is the limit measure. | quant: `hhi`; reads: search, get |
| **CVA from the exposure profile and the counterparty's spread** · `cva_from_ee.py` | `netting-set`, `exposure-profile`, `cva` (`NSET NS-HALCYON-NY`) | Unilateral CVA approximated as (1 - R) x sum of EE x discount factor x marginal default probability over the profile's periods: default probabilities from the counterparty's CDS curve when there is one (term hazards as forward hazards) or else from its one-year PD as a flat hazard; discounting on the USD OIS curve; CVA by period, its CS01 for a 1 bp spread bump, beside the engine's CVA. EE at each period's end and no wrong-way risk: a simplification. | quant: `ZeroCurve`, `cva_from_profile`, `forward_hazards`, `tenor_years`; reads: get |
| **Exposure profile: EPE, effective EE and EEPE** · `exposure_profile_stats.py` | `exposure-profile` (`EXP EXP-SUMMIT-NY`) | From the simulated profile: expected exposure (EE), negative exposure and PFE at 95% and 99% by tenor; EPE (the time-weighted average of EE over the first year), effective EE (EE never allowed to fall, the Basel rule) and effective EPE (its average over the first year), the peak PFE and its tenor, set beside the engine's own figures, and the profile drawn. | quant: `tenor_years` |
| **Credit limit utilisation heatmap** · `limit_heatmap.py` | `credit-limit`, `counterparty` (`LIM LIM-SUMMIT`) | Every credit limit read in full: utilisation by counterparty and tenor bucket as a table and a heatmap (matplotlib), the buckets in breach (100% or more) or early warning (80% or more), headroom by bucket, and where this limit stands; limits sorted by their worst bucket. | matplotlib; reads: search, get |
| **Margin calls: status, disputes and ageing** · `margin_calls.py` | `margin-call`, `netting-set`, `csa` (`MC MC-SUMMIT-NY-1`) | Every margin call in one search: by status, direction and margin type, the amounts called and disputed and the dispute rate, the calls still open with their age in days, the largest disputes, and this netting set's calls; a bar of called and disputed amounts by netting set. | reads: search |
| **MTM concentration by product and netting set** · `mtm_concentration.py` | `netting-set`, `counterparty` (`NSET NS-MERIDIAN-RE-NY`) | The counterparty's trades from drishti.search_async(): MTM by product and by netting set, each one's share of gross MTM, and a Herfindahl index of how concentrated it is. | reads: search |
| **Netting benefit and collateral under the CSA** · `netting_collateral.py` | `netting-set` (`NSET NS-HALCYON-NY`) | The netting set's trades: positive and negative MTM, net MTM, the netting benefit and the net-to-gross ratio; then the CSA's terms (thresholds, minimum transfer amount, independent amount) turned into the collateral either side should hold, the call due today against what is held, and the exposure left after netting and collateral. Uses the trades shown on the netting set (the largest, when it lists only some). | reads: get |
| **SA-CCR exposure at default, recomputed** · `saccr_recompute.py` | `sa-ccr` (`SACCR SACCR-SUMMIT-NY`) | The SA-CCR calculation rebuilt from its parts: each hedging set's add-on as supervisory factor x effective notional, summed by asset class (no correlation inside a hedging set, a simplification for commodities and equities), the PFE multiplier min(1, 5% + 95% exp((V - C) / (2 x 95% x AddOn))), and EAD = alpha x (RC + multiplier x AddOn), set beside the engine's figures; the add-on by asset class drawn. | reads: get |
| **SIMM: initial margin across risk classes** · `simm_aggregation.py` | `simm` (`SIMM SIMM-SUMMIT`) | The SIMM result's initial margin by risk class, the simple sum against the reported total, the total aggregated across risk classes with a correlation matrix in the shape of ISDA SIMM's (illustrative values: use the published table of the version in force), the single flat correlation the reported total implies, the diversification benefit, and margin posted against received. | pandas |
| **Wrong-way risk flags on a counterparty's trades** · `wrong_way_flags.py` | `counterparty`, `netting-set` (`CPTY CP-SOLARIS`) | Every trade with the counterparty (search, then each trade): specific wrong-way risk where the trade references the counterparty itself or its group (as issuer, underlying or reference entity), general wrong-way risk where the reference issuer shares its sector or country, and protection sold or equity sold on names that move with it; flagged trades with their MTM, and the share of positive MTM they carry. Heuristic flags from reference data, not a model. | reads: search, get |

### `market-data` (27)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Bond yield, duration, convexity and Z-spread** · `bond_analytics.py` | `bond` (`BND BND-GBGRAN031444`) | The bond's yield to maturity solved from its clean price (street convention, bullet, coupons on a regular schedule back from maturity), Macaulay and modified duration, convexity and DV01 per 1m face, set beside the security master's yield and duration, and its Z-spread over the benchmark curve (continuously compounded), beside the master's. | quant: `ZeroCurve`, `bond_cashflows`, `bond_price`, `bond_risk`, `bond_yield`, `z_spread`; reads: get |
| **Bond P&L under yield moves: duration, convexity and full repricing** · `bond_scenarios.py` | `bond` (`BND BND-US0UST151040`) | The bond repriced at yields from -200 to +200 bp against two approximations, duration alone and duration with convexity, with the error of each; and the 1-year horizon return (coupon carry plus price change) under each move, the breakeven yield rise that wipes out a year's carry. | quant: `bond_price`, `bond_risk`, `bond_yield` |
| **Cap prices from the normal-vol surface** · `cap_pricer.py` | `cap-vol-surface` (`CPV CPV-USD`) | Caps of 1Y to 10Y priced as strips of quarterly caplets with the Bachelier formula: forwards from the currency's projection curve, discounting on its OIS curve, each cap at its own flat normal vol from the surface (ATM at the cap's par rate, and the fixed strikes k2 to k5 read as 2% to 5%); premium in bp of notional and per 100m. Flat cap vols, not stripped caplet vols: a simplification. | quant: `ZeroCurve`, `bachelier`, `tenor_years`; reads: get |
| **CDS par spreads, risky annuity and upfront** · `cds_pricer.py` | `credit-curve` (`CDS CDS-GRANITE`) | Standard CDS of 1Y to 10Y priced on the curve's hazards (term hazards turned into piecewise-flat forward hazards), discounted on the currency's OIS curve: protection and premium legs (quarterly, accrual on default as half a period), RPV01, par spread against the quote, the upfront for a 100 bp (or 500 bp) coupon, and CS01 per 10m from a 1 bp spread bump (hazards moved by the credit triangle). | quant: `ZeroCurve`, `forward_hazards`, `survival`, `tenor_years`; reads: get |
| **Futures curve: contango, backwardation and roll yield** · `cmd_curve_roll.py` | `commodity-curve` (`CMDC CMDC-WTI`) | The futures strip by contract month: each month's spread to the next and to the front, the annualised roll yield ln(F1/F2)/(T2 - T1) that a long position earns (backwardation) or pays (contango) rolling forward, the 12-month carry, and the curve drawn with open interest beneath it. | quant: `month_code_years`; matplotlib |
| **Black-76 options on each futures month** · `cmd_options_b76.py` | `commodity-vol-surface` (`CMDV CMDV-WTI`) | For every contract month on the commodity vol surface: the future from the commodity curve, Black-76 prices of the ATM straddle and of 90% puts and 110% calls at the surface's vols, the Greeks (delta, gamma, vega per vol point, theta per day) per contract, and the skew; discounting at the USD OIS curve. Options are taken to expire at the middle of the contract month, a simplification. | quant: `ZeroCurve`, `black76`, `black76_greeks`, `month_code_years`; reads: get |
| **Hazard rates, survival and default probabilities** · `credit_hazard.py` | `credit-curve` (`CDS CDS-GRANITE`) | From the CDS spreads: hazard rates by the credit triangle (spread over one minus recovery, an approximation) beside the curve's term hazards, the forward (piecewise-flat) hazards between tenors, survival probabilities, and cumulative, period and conditional default probabilities, year by year. | quant: `forward_hazards`, `hazard_from_spread`, `survival`, `tenor_years`; matplotlib |
| **Carry and roll-down of receivers** · `curve_carry_roll.py` | `ir-curve` (`CRV CRV-USD-OIS`) | Three months' carry and roll-down of receiving par swaps along the curve, in bp and per 100m, and per unit of DV01: carry is the fixed rate against the 3M forward it is funded at, roll-down the fall in par rate as the swap ages on an unchanged curve. A simplified, curve-only view (no fixing, no bid-offer). | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **OIS curves across currencies, and swap spreads** · `curve_compare_ccy.py` | `ir-curve` (`CRV CRV-EUR-OIS`) | Every OIS discount curve (drishti.search_async, then each curve's pillars): 2Y, 5Y, 10Y and 30Y zero rates, 2s10s and 5s30s in bp, drawn together; and for this curve's currency, the OIS curve against the government curve (the swap spread by tenor). | quant: `ZeroCurve`; reads: search, get |
| **Zero, discount and forward curves** · `curve_forwards.py` | `ir-curve` (`CRV CRV-USD-OIS`) | From the curve's pillars: discount factors, 3M simple forwards and 1Y forwards on a quarterly grid under log-linear interpolation of discount factors (flat forwards between pillars), set beside the curve's own forwards. | quant: `ZeroCurve`, `tenor_years`; matplotlib |
| **Key-rate DV01 by bump and reprice** · `curve_key_rate_dv01.py` | `ir-curve` (`CRV CRV-USD-OIS`) | A 10Y receive-fixed swap at par (100m) repriced with each pillar of the curve moved 1 bp in turn (triangular key-rate bumps): DV01 by pillar, their sum against a parallel 1 bp move, and the convexity of a 25 bp move. | quant: `ZeroCurve`, `par_swap_rate`, `swap_pv`, `tenor_years` |
| **Par swap rates, annuities and curve slopes** · `curve_par_swaps.py` | `ir-curve` (`CRV CRV-USD-OIS`) | Par fixed rates and PV01 annuities of spot-starting swaps from 1Y to 30Y off the curve (single-curve, annual fixed leg), the par curve against the zero curve, and the 2s10s, 5s30s slopes and the 2s5s10s butterfly in bp. | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **Parallel, twist and butterfly scenarios** · `curve_scenarios.py` | `ir-curve` (`CRV CRV-USD-OIS`) | The curve moved in parallel, steepened and flattened (a twist pivoting at 5Y) and bent (a butterfly), and the full-revaluation P&L of receiving 2Y, 5Y, 10Y and 30Y par swaps (100m each) under every scenario, with the shocked curves drawn. Illustrative shapes, not a calibrated scenario set. | quant: `ZeroCurve`, `par_swap_rate`, `swap_pv`; matplotlib |
| **Interpolate the curve at any tenor** · `curve_tenor.py` | `ir-curve` (`CRV CRV-USD-OIS`) | Zero rates at tenors the curve does not quote: linear on zero rates, log-linear on discount factors (how the curve is built) and a cubic spline (scipy), with a matplotlib figure. | scipy, matplotlib |
| **Beta, correlation and return distribution against the index** · `eq_beta_returns.py` | `equity` (`EQ EQ-HLXP`) | Daily log returns of the stock and of its index from their price histories, aligned by date: an OLS regression with statsmodels (beta, alpha, R squared, the summary), the correlation, set beside the beta the security master holds, and the returns' skew, excess kurtosis and Jarque-Bera normality test (scipy). | scipy, statsmodels; reads: get |
| **Realised volatility: close-to-close, Parkinson, Garman-Klass** · `eq_realised_vol.py` | `equity`, `commodity` (`EQ EQ-NVTK`) | Annualised realised volatility from the daily bars by three estimators (close-to-close, Parkinson's high-low, Garman-Klass's OHLC; the range estimators ignore overnight gaps), a rolling 20-day volatility, and the gap to the front implied volatility of the linked surface: the volatility risk premium. | quant: `close_to_close_vol`, `garman_klass_vol`, `parkinson_vol`; reads: get |
| **SVI fit of the smile, expiry by expiry** · `eq_svi_fit.py` | `equity-vol-surface`, `commodity-vol-surface` (`EQV EQV-GRNT`) | A raw SVI curve (Gatheral) fitted to each expiry's total variance in log-moneyness with scipy's least squares: the five parameters, the fit error in vol points, a check of the wings against Lee's moment bound (total variance rising at most 2 per unit of log-moneyness), and the fitted smiles drawn over the quotes. Spot moneyness is used as forward moneyness, a simplification. | quant: `month_code_years`, `svi_total_variance`, `tenor_years`; scipy, matplotlib |
| **Equity vol surface: skew, term structure and arbitrage checks** · `eq_vol_arbitrage.py` | `equity-vol-surface` (`EQV EQV-SPX`) | The surface's smile per expiry and its ATM term structure; skew (90 less 110) and wings; a calendar check (total variance must not fall with expiry at any moneyness) and a butterfly check (undiscounted Black call prices must be convex and decreasing in strike). Moneyness is read as forward moneyness, a simplification. | quant: `black76`, `tenor_years`; matplotlib |
| **Overnight rate compounded in arrears** · `fixing_compounding.py` | `rate-fixing` (`FIX FIX-ESTR`) | The overnight fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360, as SOFR and ESTR swaps and the published averages do) against their simple average, the annualised compounded rate over the window, the interest on 100m, and the rate and volume drawn. | pandas |
| **Cross rates and a triangular arbitrage check** · `fx_cross_triangle.py` | `fx-spot` (`FX FX-EURJPY`) | Every FX spot read once, each pair re-derived through USD (EUR/JPY from EUR/USD and USD/JPY, ...) and compared with its own mid in pips, and a check on bids and asks: buying through the two legs and selling the direct pair (and the reverse) must not make money. | reads: search, get |
| **Forward points, implied yield differential and basis** · `fx_forward_points.py` | `fx-forward-curve` (`FXF FXF-EURUSD`) | Outrights from spot and forward points, the yield differential the forwards imply (ln(F/S)/T, continuously compounded) against the two currencies' OIS curves, and the gap between them: a rough cross-currency basis (it ignores spot lag, day counts and the CSA). | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **FX correlations and principal components** · `fx_pca.py` | `fx-spot` (`FX FX-EURUSD`) | Daily log returns of every currency against USD from the spot rates' price history, their correlation matrix (a heatmap), and a principal component analysis with numpy: how much the first component (the dollar factor) explains, each currency's loadings, and this pair's realised volatility. | matplotlib; reads: search, get |
| **FX smile: strikes from deltas, RR and BF checks** · `fx_smile.py` | `fx-vol-surface` (`FXV FXV-USDJPY`) | Each expiry's 10 and 25 delta vols turned into strikes (spot delta, premium unadjusted, Garman-Kohlhagen) with forwards from the forward curve and rates from the OIS curves, the quoted 25D risk reversal and butterfly checked against the vols, the ATM term structure and a calendar check on total variance; the smiles drawn against strike. | quant: `ZeroCurve`, `fx_strike_from_delta`, `tenor_years`; matplotlib; reads: get |
| **Price straddles, risk reversals and strangles on the surface** · `fx_vanilla_pricer.py` | `fx-vol-surface` (`FXV FXV-EURUSD`) | Garman-Kohlhagen premiums and Greeks, per expiry, of the ATM straddle, the 25 delta risk reversal and the 25 delta strangle on 10m of the base currency, at the surface's vols, with forwards from the forward curve and the quote currency's OIS rate: premium in quote currency, in pips and in % of base notional; delta, vega per vol point. | quant: `ZeroCurve`, `fx_strike_from_delta`, `gk_greeks`, `tenor_years`; reads: get |
| **Breakevens, forward inflation and real rates** · `inflation_breakeven.py` | `inflation-curve` (`INFC INFC-USCPI`) | The inflation curve's zero-coupon breakevens by tenor, forward breakevens between tenors (the 5y5y and its kin, from compounded breakevens), real rates as the currency's OIS zero rate less the breakeven (Fisher, ignoring the risk premium and convexity), the latest index fixings' year-on-year rate, and the seasonality the curve applies by month. | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **Drawdown, worst days and the return histogram** · `price_drawdown.py` | `equity`, `equity-index`, `commodity`, `bond`, `fx-spot` (`EQX EQX-SPX`) | From the closes the view holds (daily bars, or the price history): cumulative return, the running drawdown from the high, the largest peak-to-trough fall and whether it has recovered, the five worst and best days, and a histogram of daily returns with the normal density of the same mean and volatility (matplotlib). | quant: `max_drawdown`, `norm_pdf`; matplotlib |
| **Swaption premiums from the normal-vol grid** · `swaption_cube.py` | `ir-vol-cube` (`IRV IRV-EUR`) | ATM payer swaptions for every expiry and swap tenor of the cube: the forward swap rate and its annuity from the currency's OIS curve (single-curve, annual fixed leg), the Bachelier premium at the grid's normal vol, in bp of notional and per 100m, and vega per bp of vol; the vol grid as a heatmap. | quant: `ZeroCurve`, `annuity`, `bachelier`, `norm_pdf`, `par_swap_rate`, `tenor_years`; matplotlib; reads: get |

### `market-risk` (13)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **FRTB GIRR delta charge from the book's trades (simplified SBM)** · `frtb_girr_delta.py` | `frtb-sensitivity`, `book` (`FRTB FRTB-RATES-1`) | The sensitivities-based method for interest-rate delta, built bottom-up: every trade of the book read, DV01 by tenor bucket summed by currency (a GIRR bucket is a currency), weighted by the Basel tenor risk weights, aggregated within each currency with the tenor correlation max(exp(-3% /Tk - Tl/ / min(Tk, Tl)), 40%) and across currencies at 50%, under the low, medium and high correlation scenarios. Simplified: one curve per currency, no basis or inflation risk factors, no reduced weights for major currencies. | quant: `frtb_across_buckets`, `frtb_bucket_charge`, `tenor_years`; reads: search, get |
| **FRTB charges across books: delta, vega, curvature** · `frtb_overview.py` | `frtb-sensitivity` (`FRTB FRTB-FX-1`) | Every book's FRTB standardised result (one search, then each document): delta, vega and curvature charges and the total by book and by risk class, each component's share, a check that the three components add up to the total, and the books ranked by charge with this one marked. | reads: search, get |
| **P&L attribution by factor across books and desks** · `pnl_attribution_mix.py` | `pnl-explain` (`PNL PNL-EQD-1`) | Every book's P&L attribution read in full and pivoted: factor by book, and summed by desk (the desk taken from each book's own record), with totals; each factor's share of the absolute explained P&L, and the desks' factor mix as a heatmap (matplotlib). | matplotlib; reads: search, get |
| **P&L explain quality across books** · `pnl_explain_quality.py` | `pnl-explain` (`PNL PNL-RATES-1`) | This book's explain as steps (carry, roll-down, deltas, vega, theta, new trades, unexplained) and every book's explain from one search: the unexplained P&L as a share of actual, books over a 10% threshold flagged, and actual against explained P&L as a scatter. A day's snapshot; FRTB's P&L attribution test runs on a year of daily figures. | reads: search |
| **Stress scenario P&L rebuilt from trade sensitivities** · `stress_from_sensitivities.py` | `stress-scenario` (`SCN SCN-GFC2008`) | The scenario's shocks applied to every trade's booked sensitivities (one search for all of them), first order only: rate moves times DV01 (a currency's rates, or 2Y and 10Y interpolated by maturity), spread moves times CS01, breakevens times IE01, equity, oil and gas moves times delta, equity vol times vega, USD moves times FX delta; by desk, set beside the engine's stress results. Assumes delta and FX delta are P&L per 1% move; ignores convexity, cross effects and basis. | reads: search |
| **Stress results: scenarios by desk, limits and the worst case** · `stress_heatmap.py` | `stress-result`, `stress-scenario` (`STR STR-GFC2008-RATES`) | Every stress result in one search, pivoted scenario by desk with totals; each desk's worst scenario and its use of the stress limit (results read in full for their limits), the scenarios ranked by firm-wide loss, and a heatmap of the matrix (matplotlib). | matplotlib; reads: search, get |
| **VaR backtest: exceptions, Kupiec and the traffic light** · `var_backtest.py` | `var` (`VAR VAR-FX`) | The desk's daily P&L series against today's 99% VaR: the days the loss exceeded VaR, Kupiec's proportion-of-failures test (likelihood ratio and p-value), the Basel traffic light scaled to 250 days, and the P&L drawn against the VaR line. Uses one VaR figure for every day, a simplification: a real backtest compares each day with that day's VaR. | quant: `kupiec_pof` |
| **Firm-wide VaR, diversification and component VaR by desk** · `var_component.py` | `var` (`VAR VAR-RATES`) | Every desk's scenario P&L vector (one search, then each VaR result), summed scenario by scenario into a firm-wide historical VaR (the scenarios are read as the same dates for every desk, an assumption); stand-alone VaRs and the diversification benefit; component VaR by the Euler allocation (each desk's average loss in the scenarios around the firm's VaR scenario, which adds up to the firm VaR), and incremental VaR (the firm without the desk). | quant: `historical_var`; reads: search, get |
| **VaR and expected shortfall from the scenario P&L** · `var_es.py` | `var` (`VAR VAR-RATES`) | Historical-simulation VaR (99%) and ES (97.5%) recomputed with numpy from the scenario P&Ls, set beside the engine's figures, and the P&L histogram. | pandas |
| **Desk P&L regressed on market factors (statsmodels)** · `var_factor_regression.py` | `var` (`VAR VAR-COMM`) | The desk's daily P&L series regressed with statsmodels OLS on daily returns of market factors taken from the daily bars Drishti holds (oil, gold, copper and two equities as proxies; edit FACTORS), aligned by date: the summary, the factor betas in P&L per 1% move with confidence intervals, R squared and the residual volatility, and the factor that explains most. | statsmodels; reads: get |
| **VaR, ES and stressed VaR against limits, every desk** · `var_limits.py` | `var` (`VAR VAR-CREDIT`) | Every desk's VaR result in one table (drishti.search_async): VaR 99%, ES 97.5%, stressed VaR and the limit; limit use, ES over VaR and stressed over current VaR as tail and regime indicators, and a simplified Basel 2.5-style capital figure (3 x (VaR + sVaR) x the square root of 10, using today's figures for the 60-day averages). | reads: search |
| **VaR four ways, with a bootstrap interval** · `var_methods.py` | `var` (`VAR VAR-RATES`) | The scenario P&Ls turned into VaR and ES four ways: historical simulation, normal (parametric), Cornish-Fisher (normal adjusted for skew and kurtosis) and age-weighted historical simulation (Boudoukh-Richardson-Whitelaw, lambda 0.99, reading the scenarios as oldest first, an assumption); and a 90% bootstrap interval for the historical VaR (1,000 resamples, a fixed seed). | quant: `expected_shortfall`, `historical_var`, `norm_ppf`, `parametric_var`; scipy |
| **Scenario P&L tails: QQ plot, fat tails and the Hill estimator** · `var_tails.py` | `var` (`VAR VAR-EQD`) | How far the scenario P&L is from normal: skew, excess kurtosis and the Jarque-Bera test (scipy), the ratio of each tail quantile to the normal one, a Hill estimate of the loss tail index (the 25 largest losses; a lower index is a fatter tail), and a normal QQ plot (matplotlib). | quant: `norm_ppf`; scipy, matplotlib |

### `trading` (13)

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Bond position: yield risk, spread risk and scenarios** · `bond_position.py` | `trade` (`TRD BBG-60000011`) | The bond position's yield from its booked clean price, its modified duration and convexity, DV01 of the face held (against the booked DV01), CS01 as the Z-spread equivalent of DV01 for a credit bond, and the P&L of the position for yield moves of -100 to +100 bp by full repricing; long or short as booked. | quant: `bond_price`, `bond_risk`, `bond_yield` |
| **CDS position: mark, CS01 and jump to default** · `cds_trade.py` | `trade` (`TRD CLY-3000003`) | The single-name CDS marked on the issuer's credit curve (term hazards as forward hazards, quarterly premium with half-period accrual on default, discounting on the currency's OIS curve): the par spread at its maturity, the risky annuity, the MTM at its running coupon for protection bought or sold, CS01 for a 1 bp spread bump, and jump to default at the curve's recovery, beside the booked figures. | quant: `ZeroCurve`, `forward_hazards`, `survival`, `tenor_years`; reads: get |
| **Commodity option on a future: Black-76** · `cmd_option_b76.py` | `trade` (`TRD END-1000027`) | The option on a commodity future priced with Black-76: the future for the contract month nearest the expiry from the commodity curve, the vol at that month and the strike's moneyness from the commodity vol surface (linear between 90%, 100% and 110%), USD OIS discounting; premium and Greeks per unit and for the position (notional over the future as units; American exercise priced as European), against the booked figures. | quant: `ZeroCurve`, `black76_greeks`, `month_code_years`; reads: get |
| **Equity option: Black-Scholes with the surface, and implied vol** · `eq_option_bs.py` | `trade` (`TRD IMG-400037`) | The equity or index option priced with Black-Scholes-Merton (European; an American put or call is priced as European, a lower bound): spot from the underlying, the dividend yield from its dividend curve, the rate from the currency's OIS curve, and the vol read off the equity vol surface at the strike's moneyness and the expiry (bilinear in moneyness and total variance); Greeks per unit and for the position, and the implied vol solved back from the model price (Brent). | quant: `ZeroCurve`, `black_scholes`, `bs_greeks`, `implied_vol`, `tenor_years`; reads: get |
| **FX forward: mark to market from the forward curve** · `fx_forward_mtm.py` | `trade` (`TRD WSS-1500008`) | The FX forward (or NDF) marked against today's forward outright at its value date, interpolated on the forward curve's points (linear in time), discounted on the quote currency's OIS curve: forward points, the MTM in the quote currency and in USD, the implied yield differential, and the P&L for spot moves of -5% to +5% with the points held (first order). | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **FX option: Garman-Kohlhagen price and Greeks** · `fx_option_gk.py` | `trade` (`TRD WSS-1500026`) | The FX option repriced with Garman-Kohlhagen on today's market: spot, the forward curve's yield differential, the quote currency's OIS rate, and a vol read off the surface at the expiry (ATM interpolated in total variance) and at the strike's delta (linear in call delta between the 10 and 25 delta pillars and ATM); premium, delta, gamma, vega and theta for the position (notional read as units of the base currency), beside the booked MTM and risk. | quant: `ZeroCurve`, `gk_greeks`, `tenor_years`; reads: get |
| **Option P&L across spot and vol (full revaluation)** · `option_scenario_grid.py` | `trade` (`TRD IMG-400032`) | The option's P&L on a grid of spot moves (-20% to +20%) and vol moves (-10 to +10 points), by full Black-Scholes revaluation at zero rates and carry (a scenario shape, not a booking price), against the delta-gamma approximation along the spot axis; a heatmap. Works for equity, index, FX and commodity options; a strike far from spot (as in parts of the samples) is priced at the money instead, and says so. | quant: `black_scholes`, `bs_greeks`; matplotlib; reads: get |
| **MTM under parallel rate moves** · `rate_shift.py` | `trade` (`TRD MX-20000001`) | The trade's MTM after parallel moves of -100 to +100 bp from its DV01 (first order), and the tenors that drive it. | pandas |
| **Swap key-rate DV01 by bump and reprice** · `swap_key_rate.py` | `trade` (`TRD MX-20000001`) | The swap's unsettled cashflows repriced with each pillar of its curves moved 1 bp in turn (discount and projection curves bumped together, triangular bumps): key-rate DV01 by pillar from full revaluation, their sum against a parallel bump, and the booked DV01 and bucketed sensitivities beside them. | quant: `ZeroCurve`, `tenor_years`; reads: get |
| **Swap legs repriced off the curves** · `swap_reprice.py` | `trade` (`TRD MX-20000001`) | Every unsettled cashflow of the swap's legs: fixed amounts as booked, floating amounts re-projected from the forward curve (simple forwards over each accrual period plus the spread; a period already fixing keeps its booked amount), all discounted on the discount curve; each leg's PV against the booked PV, and a cashflow ladder by year. | quant: `ZeroCurve`; reads: get |
| **Swaption: Bachelier price, DV01 and vega** · `swaption_bachelier.py` | `trade` (`TRD MX-20000101`) | The European swaption priced with the Bachelier (normal) model: the forward swap rate from the projection curve and the annuity from the discount curve (annual fixed leg), the normal vol read off the currency's vol cube at its expiry and swap tenor (linear in both), premium, delta as DV01, gamma and vega per bp of vol for the position; a ladder of the premium against forward-rate moves. | quant: `ZeroCurve`, `annuity`, `bachelier`, `par_swap_rate`, `tenor_years`; reads: get |
| **Trade P&L history: hit rate, Sharpe, drawdown and explain** · `trade_pnl_history.py` | `trade` (`TRD MX-20000001`) | The trade's daily P&L history (and the MTM Drishti recorded, from drishti.history): cumulative P&L, mean and volatility, an annualised Sharpe ratio (no risk-free deduction), hit rate and win/loss ratio, the worst day, lag-1 autocorrelation and the deepest drawdown of cumulative P&L; then today's P&L explain as a waterfall-style bar chart with the unexplained share. | quant: `max_drawdown`, `sharpe`; reads: history |
| **The trade among its peers** · `trade_vs_peers.py` | `trade` (`TRD MX-20000001`) | Every trade of the same product (drishti.search_async): notional, MTM, 1-day P&L and MTM as a share of notional; where this trade ranks (percentiles) in each, the peers by book and currency, and a scatter of MTM against notional with this trade marked. | reads: search |

### `finance` (10)

Two inline in `pack.yaml`: *MTM under parallel rate moves* (`trade`, `TRD IRS-48213`) and *Member trades, summed
from the screen* (`netting-set`, `NS-NORTH-01`, from `view.tables`); and eight files, `packs/finance/python/*.py`
(hand-written, not generated):

| Snippet · file | On | Computes | Method |
|---|---|---|---|
| **Swap repriced on the curve, with key-rate DV01** · `a_swap_key_rate.py` | `trade` | The swap's projected cashflows repriced on its discount curve's pillars (log-linear on discount factors): fixed amounts as booked, floating amounts projected from the curve's simple forwards; each leg's PV against the booked one, then key-rate DV01 by bumping each pillar 1 bp in turn (full revaluation), set beside the booked DV01 by tenor. | quant: `ZeroCurve`; reads: get |
| **Swaption: Bachelier price, implied normal vol and Greeks** · `b_swaption_bachelier.py` | `trade` | The swaption repriced with the Bachelier (normal) model from its own forward swap rate, annuity and normal vol; the normal vol implied by the booked MTM (Brent); delta (DV01), gamma and vega per bp of vol; and the premium across forward-rate and vol moves. | quant: `bachelier`, `implied_vol` |
| **FX swap: points, carry and mark to market** · `c_fx_swap.py` | `trade` | The FX swap's near and far legs; the booked swap points against today's points at the far date interpolated on the forward curve, the yield differential they imply (ln(F/S)/T) against the two OIS curves, the MTM recomputed from the cashflows (each currency discounted on its own curve, converted at spot), and the MTM for spot and points moves. | quant: `ZeroCurve`; reads: get |
| **Futures position: variation margin and the curve's roll** · `d_future_margin.py` | `trade` | The futures position's daily settlements: variation margin recomputed as lots x contract size x the settlement change, checked against the booked margin and the MTM; the value of a one-dollar move; and the futures curve it sits on: contango or backwardation month by month and the annualised roll yield from this contract to the next. | reads: get |
| **Netting set: EPE, EEPE, CVA and collateral** · `e_netting_set_cva.py` | `netting-set` | The netting set's exposure profile: EPE and effective EPE over the first year, peak PFE; CVA approximated as (1 - R) x sum of EE x discount factor x marginal PD with a flat hazard from the counterparty's one-year PD (recovery 40%, USD SOFR discounting; a simplification); and the CSA's terms turned into today's collateral position against the net MTM. | quant: `ZeroCurve`, `cva_from_profile`, `tenor_years`; reads: get |
| **Overnight index: compounded in arrears and averages** · `f_sofr_compounding.py` | `index` | The index's daily fixings compounded in arrears (each rate weighted by the calendar days it applies, ACT/360) against the simple average, over the whole window and over the last 30 calendar days (as the published 30-day averages), the daily spread between the 1st and 99th percentile of transactions, and the latest fixing against the discount curve's 1-month zero rate. | quant: `ZeroCurve`; reads: get |
| **Curve analytics: rates, futures or FX forwards** · `g_curve_analytics.py` | `curve` | Whatever the curve holds: for a rates curve, zero rates, discount factors, 3M forwards and par swap rates with their PV01 per 10m (log-linear on discount factors); for a futures strip, contango or backwardation and the annualised roll yield month by month; for an FX forward curve, outrights and the yield differential the points imply (ln(F/S)/T). | quant: `ZeroCurve`, `annuity`, `par_swap_rate` |
| **Credit limit: utilisation, headroom and the PFE profile** · `h_credit_limit.py` | `credit-limit` | The limit's buckets: used against limit, utilisation against the early-warning line, headroom; and the peak PFE of the counterparty's netting sets in each bucket's tenor range (their exposure profiles, read in full), set against the bucket's limit: how much a new trade could add before a bucket breaches. | quant: `tenor_years`; reads: search, get |
