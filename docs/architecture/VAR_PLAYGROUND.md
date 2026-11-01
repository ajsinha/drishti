<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The VaR Playground: every VaR figure, worked in a grid and in a workbook you can recalculate

**Status: design, not built; planned for 2.9.8** (market-risk regulatory foundation and debugging), with the shared playground frame
that every later metric's playground reuses. Asked for by the product owner on 2026-10-31; moved from 2.9.7 Drill B to 2.9.8 the same
day to keep Drill B small. During 2.9.7 only this document and the screen and workbook mock-ups are made; the build waits for the product
owner's go for 2.9.8. The plan's rows are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#298-market-risk-regulatory-foundation-and-debugging-planned)
and its [Playgrounds](IMPLEMENTATION_PLAN.md#playgrounds-one-per-metric-in-the-metrics-release) list.

Related: [VAR_GUIDE.md section 18](../guides/VAR_GUIDE.md#18-the-computation) (the formulas the engine uses, which the workbook mirrors
cell for cell), [section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency-as-built-294) (P&L
from scenario PVs and the scenario FX), [section 18.6](../guides/VAR_GUIDE.md#186-horizons-the-two-10-day-methods-and-the-confidence-chosen-as-built-297)
(horizons and confidences), [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md) (compute, never store), [CONFIGURATION.md](../admin/CONFIGURATION.md)
(branding and every setting). The word *playground* here means a metric's worked calculation; it is not the client renderer's
`/render/demo` page.

## Contents

1. [Purpose](#1-purpose)
2. [The screen](#2-the-screen)
3. [Scope: from one position to the VaR screen's scope](#3-scope-from-one-position-to-the-var-screens-scope)
4. [Where the inputs come from](#4-where-the-inputs-come-from)
5. [The template: version-controlled, built into the .xlsx](#5-the-template-version-controlled-built-into-the-xlsx)
6. [The tabs](#6-the-tabs)
7. [The formulas, cell by cell](#7-the-formulas-cell-by-cell)
8. [Check cells](#8-check-cells)
9. [The look](#9-the-look)
10. [Excel, LibreOffice and Google Sheets](#10-excel-libreoffice-and-google-sheets)
11. [Settings, commands and the API](#11-settings-commands-and-the-api)
12. [Tests in the drill](#12-tests-in-the-drill)
13. [The playground family and the rule](#13-the-playground-family-and-the-rule)
14. [Build plan and documentation](#14-build-plan-and-documentation)
15. [Decisions](#15-decisions)
16. [Mock-ups: the screens to build](#16-mock-ups-the-screens-to-build)

---

## 1. Purpose

A risk manager, a validator, an auditor or a regulator asks *how was this VaR worked out?* Today the answer is the provenance, the
scenario matrix (`SMAT`) and VAR_GUIDE's formulas. The playground answers it with the numbers themselves: for one position, a handful,
or the whole scope on the VaR screen, it shows every scenario PV, every FX rate, every P&L, the ranking, the VaR, the ES, the components
and the 10-day figures, in a grid on screen with a tall explanation panel beside it, and **exports the same calculation as an .xlsx with
real formulas**, so the whole thing recalculates on the person's desktop, offline, and can be changed and debugged there.

Two uses:

- **Offline debugging.** Why is this desk's VaR 12.4m? Export, open, change a PV, watch the VaR move.
- **Independent verification.** The workbook's figures equal the engine's to the cent, checked by cells in the workbook itself and by a
  drill test. Every figure turns from *trust us* into *check it yourself*.

Nothing the playground shows is stored: it is computed from the same inputs the engine reads, at the moment it is asked (the
compute-first rule, [COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md)).

## 2. The screen

```
┌ VaR Playground · BANK › RATES › DESK-RATES-NY · 2026-09-30 · USD · 1-day 99% · 509 scenarios ────────────────────────────────┐
│ ┌ grid (Excel-like) ─────────────────────────────────────────────────────┐ ┌ explanation ─────────────────────────────────┐ │
│ │      A            B           C           D          …    Z (rest)       │ │ VaR 99%, 1-day = 4,812,330.17 USD             │ │
│ │ 1 Position    POS-10442   POS-10587   POS-11003        Rest of scope    │ │                                                │ │
│ │ 2 Currency    USD         EUR         JPY                USD            │ │ 1. Each position's P&L under each scenario:    │ │
│ │ 3 Base PV     12,004,113  -3,880,212  1,204,551,000      n/a            │ │    P&L(s) = PV(s) × FX(s) − PV(base) × FX(base)│ │
│ │ 4 Input       PV          PV          PnL                PnL            │ │    POS-10587, scenario 2008-10-10:             │ │
│ │ 5 Scenario 1  -31,220     8,004       -1,100,000         -412,004       │ │    −3,901,004 × 1.2712 − (−3,880,212 × 1.0835) │ │
│ │ …             …           …           …                  …              │ │    = −754,214.55                               │ │
│ │ 513 Portfolio P&L, rank, V/E marks                                      │ │ 2. Portfolio P&L = Σ positions                │ │
│ │ 515 VaR  ES  standalone  component  incremental  diversification        │ │ 3. Ranked worst first; k = ⌈509 × 1%⌉ = 6     │ │
│ └─────────────────────────────────────────────────────────────────────────┘ │ 4. VaR = −(6th worst) = 4,812,330.17           │ │
│  [Horizon 1-day ▾] [Confidence 99% ▾] [ES 97.5% ▾] [Currency USD ▾]         │ …  each step with the real numbers             │ │
│  [Export to Excel]  [Copy formulas]  [Open in SMAT]                        └────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **The grid** looks like a spreadsheet (column letters, row numbers, frozen headers), positions as columns, scenarios as rows. Selecting a
  cell shows its formula in a formula bar above the grid, in the same words the workbook uses.
- **The explanation panel** (tall, on the right) walks through the calculation step by step with the real numbers: P&L of the selected
  position under the selected scenario, the sum, the rank, the VaR scenario and its date, ES as the mean of the tail, the component
  window, the 10-day methods. Selecting a cell moves the panel to the step that produced it.
- **The pickers** are the VaR page's: Horizon (1-day, 10-day √10, 10-day sum), Confidence (`drishti.var.confidences`), ES confidence
  (`drishti.var.es-confidences`), Currency, and Normal / Stressed once stressed VaR is built (2.9.7 row 2c). They change the screen and
  the export alike.
- **Export to Excel** downloads the workbook of section 6 for exactly what is on screen.
- **Phone:** a glance form (the headline, the explanation's steps); the grid and the export are desk-tier (MOBILE_DISPLAY's tiers).
- **Mock-ups first** (2 or 3 variants of the screen, and a screenshot of each workbook tab), chosen by the product owner before the build.

## 3. Scope: from one position to the VaR screen's scope

| Way in | What it takes | Limit |
|---|---|---|
| `PLAY VAR <id> [<id> …]` | one to 50 trades or positions typed or picked, with the person's date, currency and pickers | 50 ids (`drishti.var.playground.max-ids`) |
| **Playground** on the VaR screen (in the browser) | **every position of the scope on screen** (a node, an ad-hoc set, a what-if basket) | at most **500 positions** in the browser, never more: `drishti.var.playground.max-positions` (default 500, values above 500 refused) |
| **Export to Excel** (from the Playground or the VaR screen) | the same scope | chosen in a drop-down beside the button: **100, 500 (default), 1,000, 10,000 or All** (`drishti.var.playground.export-choices`) |
| The trade, position, `PNLV`, `SPV` and `SMAT` pages | that one trade or position | 1 |

**Over the limit.** A scope with more positions than `max-positions` gets the largest positions by |component VaR| as their own columns
(500 by default) and **one "Rest of scope" column**: the summed P&L of every other position, already in the reporting currency, entered as
data. The portfolio P&L is then still the scope's P&L, so **the sheet's portfolio VaR equals the screen's VaR to the cent**; the sheet and
the screen say *500 of 18,412 positions shown, the rest combined in one column*.

**The browser and the workbook have separate limits.** The browser grid never holds more than 500 positions, so the page stays light;
the workbook is built on the server and streamed, so it can hold far more. Before the download the screen states the workbook's estimated
size, its recalculation time and the applications it fits: Excel and LibreOffice Calc (7.4 or later) hold 16,384 columns, older Calc
1,024; Google Sheets holds 10 million cells in a whole file, about 9,500 positions at 509 scenarios. A choice no application can open
(more positions than Excel's columns) is refused with that reason. Whatever the count, the positions beyond it go into the "Rest of scope"
column, so the workbook's portfolio VaR equals the screen's to the cent.

**Which figures for which positions.** Standalone VaR and ES and **component VaR and ES for every position** (cheap: a sum over the
scope's tail scenarios); **incremental VaR for the top 50 by |component VaR|** (`drishti.var.playground.incremental-positions`, default
50), because each needs its own re-ranked portfolio column; the others show *not computed (top 50 only)*. Diversification for the scope.

**Size.** About 20 to 40 MB at 500 positions × 509 scenarios with formulas and cached values, growing with the count chosen (estimate,
to be measured at build); the export streams and states its size before the download starts.

## 4. Where the inputs come from

A server endpoint builds the inputs **as the person** (their entitlements and masks, as every VaR answer), from the same date, batch,
scenario window and scenario set the engine used, and says so in the Provenance tab:

| Input | Source | Note |
|---|---|---|
| **Scenario PVs** per position, in the position's own currency | `scenario-pv` (VAR_GUIDE 15.9) | **the preferred input** (compute-first: P&L is derived, never stored) |
| **P&L vectors** | `pnl-vector` | accepted; the position is flagged *input form PnL* |
| Base PV | `scenario-pv`'s base value | blank and *n/a* for a P&L-vector position |
| Scenario FX per currency, base FX | `fx-scenario` (supplied) or derived from the FX history (`ScenarioFx.derive`) | each currency's column says *supplied* or *derived* |
| Scenario dates, window, set id, stress flag | the date's scenario set, `drishti.var.scenario-window` | newest N when a window is set |
| Rank rule, component window, confidences, horizon, 10-day method | `drishti.var.*` and the pickers | written to Main as inputs |
| The engine's own figures | the VaR engine, for the same scope and pickers | written to Main's check cells (section 8) |

A trade or position the engine refused (a missing PV, an unknown currency) is listed in Provenance with its DRS code and left out, as the
engine leaves it out; never zero-filled.

## 5. The template: version-controlled, built into the .xlsx

The workbook is **a crafted template, always available, kept in the repository**; an export only fills its data cells.

- **Kept as text.** The template's OOXML parts (`[Content_Types].xml`, `xl/workbook.xml`, `xl/styles.xml`, `xl/worksheets/sheet*.xml`,
  `xl/sharedStrings.xml`, `docProps/*.xml`) are committed unzipped, under a proposed `drishti-server/src/main/resources/playground/var/template/`,
  so every change is a reviewable diff. The build zips them into a resource, `var-playground.xlsx`, with the template version stamped in
  the workbook (Main and the document properties).
- **Formulas built once for the largest layout.** Every formula is written for the maximum layout (500 positions and the longest
  configured window, sizes from configuration) with **COUNT-based ranges**, so unused rows and columns stay blank and cost nothing; the
  export never writes a formula.
- **Filled by the server.** The in-house, JDK-only writer (`drishti-server` `dist/XlsxWriter`) is extended to open the template and fill
  values and **cached results** of the formula cells (so a viewer that does not recalculate still shows correct numbers). It writes only
  values; a formula cell is the template's, never text supplied by a person.
- **A bank's own house style.** `drishti.var.playground.template` may name a bank's own template; it must keep the **named cells** of
  section 6 (checked when loaded: a missing name refuses the template with the list of what is missing).
- **Works alone.** The empty template is downloadable (Help and `GET /api/v1/var/playground/template`): paste scenario PVs into Data,
  fill Config and FX, and it calculates offline.

## 6. The tabs

Positions are **columns**, scenarios are **rows**, in the scenario set's order (oldest first), the same layout in every tab.

| Tab | What it holds | Typed or formula |
|---|---|---|
| **Main** | A title band (product name, scope, date, currency, horizon, confidence); the headline inputs (named cells: `Confidence`, `EsConfidence`, `Horizon`, `TenDayMethod`, `RankRule`, `ComponentWindow`, `ReportingCurrency`, `ScenarioCount`); in its lower part the **results**: VaR, ES, the VaR scenario's rank and date, 10-day √10 and 10-day sum VaR and ES, every configured confidence side by side, and per position standalone VaR and ES, component VaR and ES, % of VaR, incremental VaR (top 50), diversification; the check cells | inputs typed; results formulas over PnL |
| **Config** | One row per position: id, kind (trade or position), currency, **base PV** in its own currency, **base FX** (USD per unit), **input form `PV` (default) or `PnL`**, its source flag | typed (values) |
| **Data** | **Values only, the one place data is pasted:** the scenario PVs of each position in its own currency (input form PV), or the source's P&L vector as delivered (input form PnL); the "Rest of scope" column's P&L in the reporting currency | values |
| **FX** | **One column per currency** (not per position): the scenario rate of each scenario (USD per unit), the base rate in a header row, *supplied* or *derived* under the code; USD is 1 throughout | values |
| **PnL** | **Every cell a formula, nothing typed:** each position's P&L in USD (from Data, FX and Config, switching on the input form), then in the reporting currency; the portfolio P&L; its tie-broken rank; the V and E marks; the 10-day sums; the incremental helper columns (top 50) | formulas |
| **Explain** | The calculation in words, each step referencing its cells (*VaR = −PnL!B518, the 6th worst; see Main!C12*), the formulas as written in VAR_GUIDE 18 | text with references |
| **Sensitivities** (when delta-gamma-vega VaR is built, parked; [VAR_GUIDE 18.12](../guides/VAR_GUIDE.md#1812-delta-gamma-vega-var-on-request-design-not-built-parked)) | Each position's deltas, gammas and vegas per risk factor, the factor shocks per scenario day, and each DGV P&L cell as the expansion Σ δ·Δx + ½ Σ Γ·Δx² + Σ ν·Δσ in formulas; DGV VaR and ES beside the full-revaluation figures, with a check cell against the engine | inputs and formulas |
| **Factors** (when statistical factor VaR is built, parked; [VAR_GUIDE 18.13](../guides/VAR_GUIDE.md#1813-statistical-factor-var-estimated-from-scenario-pl-design-not-built-parked)) | Labelled a statistical estimation: the drivers' scenario-day series and each position's loadings and curvature as data; each fitted P&L cell, the factor VaR and each position's R² as formulas; a check cell against the engine | data and formulas |
| **Provenance** | Date, batch, data versions, scenario set id and dates, window, stress flag, source per position (`scenario-pv` or `pnl-vector`), refused items with DRS codes, the engine version, the template version, who exported it and when, the scope's full position count | values |

The **Config flag switches the PnL formula**, so a person can see and flip it (section 7.1).

## 7. The formulas, cell by cell

The workbook mirrors the engine exactly (VAR_GUIDE section 18; `ScenarioFx` in `drishti-compute`); the column and row names below stand
for the template's named ranges.

### 7.1 P&L of a position, in USD

| Input form | Formula | Engine |
|---|---|---|
| **PV** (default) | `P&L_USD(s) = PV(s) × FX_c(s) − PV(base) × FX_c(base)`; for a USD position `PV(s) − PV(base)` | `ScenarioFx.pnlFromPv` |
| **PnL**, delivered in USD | `= Data!x` (a reference; no PV arithmetic, base PV *n/a*) | the vector as delivered |
| **PnL**, delivered in another currency | that P&L converted at each scenario's rate, as the engine converts a non-USD `pnl-vector` | `ScenarioFx` |

In the cell: `=IF(Config!InputForm="PV", Data!x * FX!c_s - Config!BasePV * FX!c_base, <PnL branch>)`, with `c` looked up by
`INDEX` from the position's currency.

### 7.2 Into the reporting currency

With `p` the position's USD value today (`PV(base) × FX_c(base)`, 0 for a P&L-vector position) and `FX_r` the reporting currency's
USD-per-unit rates: `P&L_r(s) = (p + P&L_USD(s)) / FX_r(s) − p / FX_r(base)` (`ScenarioFx.convert`; every cross through USD). For USD
reporting it is the USD P&L. The "Rest of scope" column is already in the reporting currency.

### 7.3 The portfolio, its rank and the 1-day figures

| Figure | Formula in the sheet | Engine (VAR_GUIDE) |
|---|---|---|
| Portfolio P&L `v(s)` | `SUM` across the positions' reporting-currency P&L columns (and Rest of scope) | the scope's vector |
| Rank, worst first, ties by index | `=SUMPRODUCT((v<v_s)*1) + SUMPRODUCT((v_above=v_s)*1) + 1` | `o`, ties by index (18.1) |
| `k` (kth-worst) | `=MAX(1, MIN(N, ROUNDUP(N*(1-Confidence) - 1E-9, 0)))` | 18.1 |
| VaR, `kth-worst` | `=-SUMPRODUCT((Rank=k)*v)` | `−v[o[k−1]]` |
| VaR, `interpolated` (when configured) | the two neighbouring ranks weighted by `w`, as 18.1 | 18.1 |
| `k_ES`, ES | `=-SUMPRODUCT((Rank<=k_ES)*v)/k_ES` | 18.2 |
| Tail window `T` | ranks `k−h … k+h`, `h = ROUNDDOWN(ComponentWindow/2, 0)`, clamped to 1..N | 18.3 |
| Scale `c` | `VaR / S`, `S = −mean over T of v` (1 when the scaling is off or `S` is 0) | 18.3 |
| Component VaR of a position `p` | `=-c * SUMPRODUCT((Rank>=lo)*(Rank<=hi)*p) / COUNT(window)` | 18.3 |
| Component ES | `=-SUMPRODUCT((Rank<=k_ES)*p)/k_ES` | 18.3 |
| Standalone VaR and ES | the same on the position's own column with its own rank column | 18.3 |
| Incremental VaR (top 50) | `VaR(v) − VaR(v − p)`: a helper column `v − p` per position, its own rank, its kth-worst | 18.3 |
| Diversification | `Σ standalone VaR − VaR` | 18.3 |

Every confidence in `drishti.var.confidences` and every ES confidence in `drishti.var.es-confidences` has its own row of results on Main
(a different rank of the same ranked vector: no extra data).

### 7.4 10-day

| Method | Formula |
|---|---|
| **√10** | `= VaR_1d * SQRT(10)` (and ES) |
| **10-day sum** (overlapping) | a PnL column of `SUM` over 10 consecutive scenario rows: N − 9 windows (509 give 500); then rank, VaR, ES and components over those windows exactly as 7.3 |

### 7.5 Stressed VaR

When stressed VaR exists (2.9.7 row 2c), the Normal / Stressed picker exports the stress scenario set instead: the same tabs and formulas
over its 250 scenarios, the set id and window on Main and in Provenance. A date with no stress set says so; never zeros.

### 7.6 Where the simulation is the input

The rule for every playground: where a metric's core is a large simulation (exposure paths, projected IM or capital), the simulation's
output is **data** and everything after it is **formulas**, and the sheet says so on Main and in Explain. VaR's core is already a sum and
a rank, so every step after the scenario PVs is a formula.

## 8. Check cells

| Check | Pass when |
|---|---|
| Portfolio VaR, ES (every confidence, both 10-day methods) | the sheet's figure equals the engine's figure written beside it, to the cent (`ABS(sheet − engine) < 0.005`) |
| Components add up | `Σ component VaR = VaR` and `Σ component ES = ES` to the cent |
| Positions shown | `positions shown + positions in Rest of scope = the scope's count` |
| Scenario count | `COUNT` of each Data column = `ScenarioCount` |
| FX present | every position's currency has an FX column |

A green **ALL CHECKS PASS** band on Main, or a red band naming the failed check. A person who changes a PV sees the engine checks turn
red (the engine's figure is for the original inputs), and the *adds up* checks stay green.

## 9. The look

Professional grade, decided by the product owner: one font family with clear sizes; muted header fills; **input cells visibly distinct
from formula cells** (a light input fill and a legend); losses red and gains green with **colour-blind-safe markers** as well (*V* on the
VaR scenario, *E* on the ES tail); number formats with thousands separators and two decimals for money; frozen headers; set column
widths; print set up landscape, fit to width, with header and footer; a title band on Main. **Copyright and product name** in a footer on
every tab and in the workbook's properties, taken **only from the branding configuration** (`drishti.branding.*`), never literals in the
template (the template holds placeholders the export fills). The look is mocked up (a screenshot of each tab) and chosen before the
build.

## 10. Excel, LibreOffice and Google Sheets

**One file for all three**, no switch. Formulas use only functions the three share: `SUM`, `SUMPRODUCT`, `SMALL`, `LARGE`, `AVERAGE`,
`COUNT`, `INDEX`, `ROUNDDOWN`, `ROUNDUP`, `SQRT`, `IF`, `ABS`, `MAX`, `MIN`. **Never** Excel-365-only dynamic arrays (`FILTER`, `SORT`,
`SEQUENCE`, `LET`, `LAMBDA`, `XLOOKUP`), structured table references, volatile `INDIRECT` or `OFFSET`, or macros. Cached values are written
with every formula, so a viewer that does not recalculate (a browser preview, a mail client) shows the right numbers. Google Sheets opens
the file as uploaded; its row and cell limits are well above the 500-position layout (checked at build).

## 11. Settings, commands and the API

| Setting (proposed) | Default | Meaning |
|---|---|---|
| `drishti.var.playground.enabled` | `true` | the screen, the command and the export |
| `drishti.var.playground.max-positions` | `500` | positions shown as their own columns from a scope; the rest go into Rest of scope |
| `drishti.var.playground.incremental-positions` | `50` | positions that get incremental VaR (the largest by \|component VaR\|) |
| `drishti.var.playground.max-ids` | `50` | ids `PLAY VAR` takes |
| `drishti.var.playground.template` | the built-in template | a bank's own template (it must keep the named cells) |

Each goes into CONFIGURATION.md through the settings catalogue when built.

- **Command:** `PLAY VAR <id …>` and `PLAY VAR <scope>` (the word `PLAY` checked free against every pack's mnemonics at build; switchable
  per edition like every command word).
- **API (proposed):** `GET /api/v1/var/playground?scope=…&date=…&currency=…&horizon=…&confidence=…` (the grid as JSON, Arrow for a large
  one), `…/playground.xlsx` (the export), `…/playground/template` (the empty template).
- **CLI (proposed):** `drishti.py var playground <scope|ids> --xlsx out.xlsx`.

## 12. Tests in the drill

- **To the cent.** The workbook's results equal the engine's for 1, 50 and 500 positions (and a scope over 500 with Rest of scope), at
  every configured confidence, ES confidence and both 10-day methods, PV and PnL input forms, USD and non-USD positions and reporting
  currencies, both rank rules.
- **Headless LibreOffice recalculation.** Where LibreOffice is installed, `soffice --headless` recalculates the exported file and the
  test compares every result cell to the engine to the cent; it **skips** where LibreOffice is absent (the Windows QA machine).
- **The template.** Its formulas against the engine on the demo data; a bank template missing a named cell is refused; no forbidden
  function anywhere (a scan of every formula); branding placeholders filled from configuration.
- **The screen.** A browser test of the grid, the panel's steps and the export at 390 and 1280 px.

## 13. The playground family and the rule

**The rule (product owner, 2026-10-31): every metric calculation ships with its playground, in the same release as the metric, never
deferred.** A metric is not done without it: `PLAY <METRIC> <ids|scope>`; the screen (inputs grid and tall explanation panel with real
numbers); the export from a version-controlled template (text OOXML parts in git) with Main, Config, Data (values only), formula tabs,
Explain and Provenance; every derived cell a formula; check cells (sheet = engine to the cent); the professional look; copyright from
the branding configuration; Excel, LibreOffice and Google Sheets; a drill test with headless LibreOffice recalculation. Every builder
brief and every metric's design document has its playground section.

**The frame is built once, in 2.9.8, with VaR:** the screen's grid and explanation panel, the template loader and filler on `XlsxWriter`,
the check-cell and look conventions, the formula scanner and the LibreOffice test harness. Each later playground is a template and a
calculation on that frame.

| Playground | Metric | Release (the plan's themed releases) |
|---|---|---|
| **VaR** | VaR, ES, components, incremental, 10-day, stressed | 2.9.8 (planned), with the frame |
| Exposure | EE, EPE, PFE for one netting set (the paths as data) | 2.9.11 (proposed) |
| SA-CCR | EAD by CRE52 (replacement cost, add-ons, the multiplier) | 2.9.11 (proposed), with the engine |
| FVA | EPE and ENE × funding spread | 2.9.11 (proposed) |
| FRTB SA | SBM, DRC, RRAO | 2.9.12 (proposed) |
| SIMM | IM per netting set and regime | 2.9.12 (proposed) |
| CVA, BA-CVA, SA-CVA | CVA (EE × DF × ΔPD × LGD), its capital | 2.9.13 (proposed) |
| FRTB IMA | ES by liquidity horizon, stressed calibration | 2.9.14 (proposed) |
| MVA, KVA | from projected IM and capital (as data) | 2.9.14 (proposed) |

## 14. Build plan and documentation

1. Mock-ups: two or three screen variants and a screenshot of each workbook tab; the product owner picks.
2. The template parts in git and the build step that zips them; `XlsxWriter` extended (template fill, cached values, named cells).
3. The server endpoint and the calculation (shared with the engine's code where it exists: `ScenarioFx`, the rank and the components).
4. The screen (grid, panel, pickers, export), the command and the CLI.
5. Tests of section 12; the drill's LibreOffice stage.

**Documents updated with the build:** VAR_GUIDE (a playground section and its formulas), USER_GUIDE (the screen), a tutorial step in
tutorial 25 (CLI and UI, pictures), CONFIGURATION (the settings), API_GUIDE and CLI_GUIDE, a Knowledge entry, the help entries, the decks
(the VaR deck's verification slide), CHANGELOG; this document marked *as built*.

## 15. Decisions

**Decided (product owner, 2026-10-31):** the playground itself (one to many positions, the grid and the explanation panel, Export to
Excel with real formulas); every confidence, the ES confidences, both 10-day methods and currency conversion; the version-controlled
template; the professional look with copyright from branding; one file for Excel, LibreOffice and Google Sheets; the scope from the VaR
screen up to 500 positions (configurable) with Rest of scope; per-position currency, base PV and base FX; the Config tab with the input
form PV or PnL; the simplified tabs (Main, Config, Data values only, FX, PnL formulas only, Explain, Provenance); placement in 2.9.8 with
the frame; the rule that every metric has its playground in its own release.

**Mock-ups made (2026-10-31), section 16:** three screen options and a picture of each workbook tab. Option A of the screen and the
workbook look shown are recommended (chosen on the recommendation, product owner to review).

## 16. Mock-ups: the screens to build

The mock-ups are under [`mockups/var-playground/`](mockups/var-playground/), each a self-contained HTML page (system fonts, everything
inline, nothing from a CDN) with a headless-Chrome screenshot beside it, 1600 px wide; the dark theme is the default and `#light` at the
end of a page's address shows the light theme (`A-light.png`). The figures come from one exact model of the calculation: the BANK
figures are the demo's on 2026-09-30 (VaR 1-day 99% 112,041,646.00; 95% 68,355,411; 10-day √10 354,306,794; 10-day sum 274,544,283),
and every ES, component, incremental and 10-day figure on a page is computed from the same vectors as section 7 says, so the explanation
panel's arithmetic is right. **This checklist is the source of truth at build time**: a panel is done when its row says built.

| Mock-up | What it shows | Status |
|---|---|---|
| [A](mockups/var-playground/A.html): grid with a tall explanation beside it | a desk from the VaR screen (8 positions); the formula bar; the explanation of the selected cell | **recommended** (chosen on the recommendation, product owner to review) |
| [B](mockups/var-playground/B.html): three panes with the position list | BANK from the VaR screen: 500 of 18,412 positions, Rest of scope pinned at the right | alternative |
| [C](mockups/var-playground/C.html): explanation first with tabbed views | `PLAY VAR` with three ids; the tail ranked worst first; the results | alternative |
| [Workbook tabs](mockups/var-playground/workbook-main.html): Main, Config, Data, FX, PnL, Explain, Provenance | one picture per tab, the professional look of section 9 | **recommended** (chosen on the recommendation, product owner to review) |

**Why A.** It is the layout this document designed (section 2) and the one every later playground can reuse unchanged: the grid looks
like the workbook a person will export (positions as columns, scenarios as rows, the formula bar showing the cell's formula in the
workbook's own words), and the explanation stays next to the cell it explains, so selecting a cell and reading why are one motion. It
serves one position and five hundred alike: a scope over the limit only adds the pinned Rest of scope column (B's case), and B's position
list can be a drawer of A rather than a third pane that narrows the grid. C reads well as a worked example but hides the spreadsheet a
validator expects to check, and does not match the export.

![VaR Playground, option A](mockups/var-playground/A.png)

![The workbook's Main tab](mockups/var-playground/workbook-main.png)

The other tabs: [Config](mockups/var-playground/workbook-config.png), [Data](mockups/var-playground/workbook-data.png),
[FX](mockups/var-playground/workbook-fx.png), [PnL](mockups/var-playground/workbook-pnl.png),
[Explain](mockups/var-playground/workbook-explain.png), [Provenance](mockups/var-playground/workbook-provenance.png); the other screen
options: [B](mockups/var-playground/B.png), [C](mockups/var-playground/C.png).

### 16.1 The screen (option A)

| # | Panel | Contents on the mock-up | Data, API or contract | Interaction | Status |
|---|---|---|---|---|---|
| P1 | Header | *VaR Playground*, the scope's path (BANK › MARKETS › RATES › DESK-RATES-NY), date, currency, horizon and confidence, the scenario count and dates, the position count, the input sources | the playground endpoint (section 11) | each path part opens that node's VaR | not built (2.9.8 row 9) |
| P2 | Pickers | Horizon, Confidence, ES, Currency, Normal / Stressed; the grid's view P&L / Scenario PV / FX | `drishti.var.confidences`, `es-confidences`; the VaR page's pickers | change the grid, the panel and the export alike | not built |
| P3 | Actions | Open in SMAT, Copy formulas, **Export to Excel** with the size stated | the export of section 11 | download the workbook for exactly what is on screen | not built |
| P4 | Formula bar | the selected cell's reference and its formula as the workbook writes it | the template's formulas | follows the selection | not built |
| P5 | Grid: header rows | rows 1 to 6: Position, Kind, Currency, Base PV, Base FX, Input form; input cells tinted | Config (section 6) | — | not built |
| P6 | Grid: scenarios | one row per scenario (index and date), each position's P&L in the reporting currency, Portfolio, Rank, Mark (V on the VaR scenario, E on the ES tail); long runs folded (⋮) around the first rows, the VaR scenario and the last rows | PnL (section 7) | select a cell; scroll; the V row is outlined | not built |
| P7 | Grid: results rows | Standalone VaR 99%, Component VaR, % of VaR, Incremental VaR, Component ES 97.5% per position | Main's results (section 7.3) | select a figure: the panel explains it | not built |
| P8 | Rest of scope | a pinned last column when the scope has more than `max-positions` positions, its P&L entered as data | section 3 | — | not built |
| P9 | Explanation panel | the headline (VaR, ES, diversification); steps 1 to 8 with the real numbers: the cell's P&L, the portfolio sum, the rank k, VaR, ES, the component (window and scale), the two 10-day methods, every confidence; the check line | the same endpoint | selecting a cell moves the panel to its step | not built |
| P10 | Check line | *Σ component VaR = VaR to the cent · sheet = engine* | section 8 | red with the failed check named | not built |
| P11 | Note | inputs tinted; every other figure a formula; the Rest of scope rule | — | — | not built |

**States.** *Loading:* the grid's skeleton with the header filled and `…` in the cells. *Over the limit:* the banner of option B
(*500 of 18,412 positions shown …*). *Refused inputs:* a line under the header naming them with their DRS codes, as Provenance does.
*Phone:* a glance form, the headline and the explanation's steps; the grid and the export are desk-tier.

### 16.2 The workbook (the look shown)

| # | Tab | Contents on the mock-up | Typed or formula | Status |
|---|---|---|---|---|
| W1 | Every tab | the file name band, the formula bar, the legend (input, formula, heading, check passed, loss, V, E), column letters and row numbers, the sheet tabs, the footer *<copyright from branding config> · <product name from branding config>* and the template version | — | not built |
| W2 | Main | the title band and the scope band; the named inputs (amber fill, blue figures); the results beside the engine's figures with a check each; every confidence (k, VaR, ES); the per-position table with totals; the ALL CHECKS PASS band | inputs typed; results formulas | not built |
| W3 | Config | one row per position: id, kind, currency, base PV, base FX, input form, source, base value in USD | typed; the last column a formula | not built |
| W4 | Data | scenario PVs in each position's currency, or the P&L vector as delivered; the form row under the header | values only | not built |
| W5 | FX | one column per currency; the base rate and *derived* or *supplied* in header rows | values | not built |
| W6 | PnL | each position's P&L, Portfolio, Rank, Mark, the 10-day sums (from the 10th scenario); the selected cell's formula in the bar | formulas only | not built |
| W7 | Explain | ten steps, each naming the cells it uses | text with references | not built |
| W8 | Provenance | date, batch, scope, counts, scenario set, data versions, sources, refused items with codes, engine and template versions, who exported it, the settings | values | not built |

**Left for the build to settle:** the exact fills and fonts (the mock-up uses navy headings, a light-yellow input fill with blue figures,
black formula figures, red losses and the V and E markers), checked in Excel, LibreOffice and Google Sheets.
