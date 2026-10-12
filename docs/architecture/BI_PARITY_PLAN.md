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

# Drishti Rūpaka vs Microsoft Power BI: gap analysis and parity roadmap

Status: proposal for agreement (not yet scheduled). Study date: 2026-10-12. Drishti version studied: 2.1.0 (`drishti-server/pom.xml`). Decisions already taken: no Power Query clone (Python is the data-prep path); breaking BI redesigns are acceptable before production; YAML, Python and datasets + data models stay the foundation.

## 0. Method, sources and confidence

**What Drishti has** comes from the documents, checked against the code where the claim mattered:

- `docs/architecture/RUPAKA.md`: §1 (positioning), §6–8, §20.9–20.14 and **§20.15 "As built at 2.1.0"**, which is the current list.
- `docs/guides/`: BI_GUIDE, DATASETS, REPORTS, BOARDS, BI_GOVERNANCE, PYTHON_BI, ANALYSE, BI_AI, DISTRIBUTION, ELEMENTS and BI_RECIPES.
- Code: `drishti-server/src/main/java/com/ash/drishti/server/bi/**` (99 files, about 24.8k lines; packages `ai`, `board`, `dataset`, `engine`, `gov`, `model`, `python`, `report`, `transform`) and `drishti-console/web/static/js/bi*.js` (32 files, about 10k lines).
- **Searches for features that do not exist.** I grepped the BI code for tooltip pages, small multiples, what-if, field parameters, calculation groups, unpivot, sensitivity labels, XMLA/ODBC, paginated, scorecard, decomposition, key influencers, anomaly, forecast and USERELATIONSHIP. None of them exist. The only "tooltip" hits are ECharts hover tips, and "usage" is only a view counter (`BiProperties.usageDays`, `BiService`).

**What Power BI has** comes from my own knowledge, which is reasonably current to early or mid 2026, plus two web searches:

- Legacy **Q&A is deprecated**: announced December 2025, full retirement December 2026, Copilot is the replacement (MC1218421).
- **Metric sets were retired** in November 2025. **Scorecards** are still supported.
- **DAX user-defined functions** and **calendar-based time intelligence** (fiscal, retail 4-4-5, custom calendars, week functions) were in public preview from September 2025. Their GA status is **verify**.

Items marked **(verify)** below are ones where my Power BI knowledge may be stale, or where the feature was in preview at my last reliable information.

**Legend**

- Status: **B** built, **P** partial, **M** missing, **N/A** deliberately different.
- Importance for Drishti's users (finance, risk and ops analysts, and developers): **H** high, **M** medium, **L** low.
- Effort: **S** about 1 builder-week, **M** 2–4 weeks, **L** 1–2 months, **XL** more than a quarter or a new subsystem.

---

## 1. Executive picture

Rūpaka is already a credible governed BI layer. Its semantic engine (DuckDB over business-date-partitioned column layers, rollups, live tables) is in some ways **more correct than Power BI by construction**:

- Grain-correct measures across facts, with fan-out refused (`DRS-9020`).
- True totals for measures that do not add up.
- Masks applied before aggregation and before joins.
- Business-calendar-aware time intelligence and relative dates.
- Governance as YAML with four-eyes review, expected-value tests that gate publish, rename-aware impact analysis, and bundles with rollback.

The gaps are concentrated in three places:

1. **Modelling and calculation depth.** Bidirectional and many-to-many filtering, inactive relationships used by a measure, parameters, calculation groups, and filter-context manipulation (a `CALCULATE` equivalent).
2. **Authoring polish.** Format-pane depth, themes, tooltip pages, small multiples, sync-slicer groups, per-pair edit interactions, field and what-if parameters.
3. **The data-prep experience.** By decision, Python (Transforms, Analyse, the sandboxed runner) replaces Power Query. It covers every Power Query *job*, but lacks step preview, a snippets library, a profiling view, AI-written transforms, raw-source inputs and precompute-on-refresh (§2.3).

Some Power BI families are deliberately absent and mostly should stay that way: Fabric/OneLake/Direct Lake, the AppSource marketplace, a DAX-compatible language, and R. Others are absent but matter to finance users: Excel live connection (Analyze in Excel), a SQL/semantic endpoint for external tools (XMLA's role), paginated "pixel-perfect" output, and comments on reports.

---

## 2. Gap matrix by area

### 2.1 Data modelling

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Star schema: tables, many-to-one relationships, Model view diagram | **B**. DATASETS §3. The Model view supports drag-to-relate and layout saving (BI_GUIDE §16). Relationships are proposed from data profiling, and pack links arrive as ready-made relationships | H | – | **Ahead**: pack links pre-wire the model; inference shows match rates |
| One-to-one, one-to-many cardinality | **B** (one-to-one joins either way) | H | – | |
| Many-to-many relationships | **P**. Stored; queries "join as written" with a warning (DATASETS §3, §10) | M | M | Do it the Drishti way: a bridge table with distinct-key semi-join semantics, not a raw join |
| Bidirectional cross-filter (`direction: both`) | **P**. Stored, not honoured in queries (RUPAKA §6.2, DATASETS §10) | M | M | Needed so a dimension slicer can filter via a fact (e.g. "counterparties that have trades"). Implement as `EXISTS`/semi-join filters, never as join expansion, to keep fan-out safety |
| Inactive relationships plus `USERELATIONSHIP` | **P**. Stored, skipped, "cannot yet be turned on by a measure" | H (trade date vs settle date vs value date) | M | Typical finance need: role-playing date dimensions |
| Role-playing dimensions | **P**. Possible by duplicating tables | M | S | Solved by the item above |
| Calculated columns | **P**. Row-level, own table only, cannot use another calculated column or a related table (DATASETS §2) | H | M | Add `related(table.col)` along a many-to-one path and chained calculated columns |
| Calculated tables (DAX table expressions) | **P/N/A**. A composed dataset (`from:`/`join:`) or a derived (Python) dataset is the analogue, and the result is a whole dataset of one table | M | M | Add declared **date / calendar tables** generated from the business calendar (see 2.2); avoid general table DAX |
| Hierarchies | **B**. DATASETS §5; drill in visuals | H | – | Ragged/parent-child hierarchies (`PATH`) are **M**: needed for org/book trees (book → desk → division) |
| Parent-child / ragged hierarchies | **M** | M | M | Books and legal-entity trees |
| Display folders, descriptions, formats, synonyms | **P**. Descriptions and formats yes; folders and synonyms no | M | S | Synonyms also help "ask the data" |
| Storage modes: Import / DirectQuery / Dual / composite | **B**, different shape. Each table is `cached`, `direct` or `live`; a dataset may mix them, but **all `direct` tables of one query must be in one store** (DATASETS §7) | H | – / L | **Ahead** on live. Composite "direct + cached in one query" is **M** (L effort: stage the direct side in DuckDB, or ATTACH) |
| Aggregations (user-defined aggregation tables, auto-aggregations) | **B**. `rollups:` with aggregate-aware routing and component re-aggregation (DATASETS §7); incremental live rollups | H | – | Gaps: no rollup for `distinct`, percentile or time measures; no automatic rollup suggestion from query logs (M) |
| Incremental refresh (range partitions, refresh only recent) | **B**, different shape. The columns layer is partitioned by business date and only the loaded date is rebuilt on a data-load signal (DATASETS §7) | H | – / S | Gap: "refresh of an earlier date" is not built (DATASETS §10). Back-dated corrections are common in finance, so this is S–M |
| Hybrid tables (import plus a real-time DirectQuery partition) | **P**. Live tables plus history; "joins the hot state with today's columns" is in the design (§7.1), not stated as built | M | M | Verify in code before claiming |
| Direct Lake (Fabric: Delta/Parquet in OneLake read without import) | **N/A / effectively B**. Cached tables are Parquet per business date in the BI store, read by DuckDB `read_parquet`; Delta scan via connectors | L | – | Do not chase Fabric; Drishti's columns layer is the same idea, on-premises |
| Object-level security (hide tables/columns per role) | **P**. Masks show `•••` and are not grouped on; a table over a kind the reader cannot open returns no rows. There is no "the column does not exist for you" metadata hiding | M | S | Optional `hidden-for:` roles in the model metadata |
| Row-level security, static and dynamic (`USERPRINCIPALNAME`) | **B**. `security.rows` with `user.*` attributes, OR-ed per role, fail-closed refusal when a rule's table is unreachable (DATASETS §6) | H | – | **Ahead**: fail closed instead of silently leaking; masks before aggregation; per-recipient subscriptions |
| Model size and compression (VertiPaq) | **N/A**. DuckDB over Parquet; no in-memory columnar model | M | – | See 2.11 |
| TMDL / model as code | **B**, better. YAML datasets in `config/bi`, schema-validated, diffed, tested | H | – | **Ahead** |

### 2.2 Calculation language (DAX vs Rachana-EL measures)

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Basic aggregates (SUM, AVERAGE, MIN, MAX, COUNT, DISTINCTCOUNT, DIVIDE, PERCENTILE) | **B**. `sum`, `avg`, `min`, `max`, `count`, `distinct`, `ratio`, `percentile`, plus conditional aggregates `sum(x, cond)` (DATASETS §4) | H | – | |
| Measures referencing measures | **B**. `[Name]`, with grain-correct expansion across tables (RUPAKA §20.12–20.13) | H | – | **Ahead**: DAX silently gives a wrong-grain answer in some models; Drishti splits by grain or refuses |
| Filter context and `CALCULATE` (override, remove or add filters: `ALL`, `ALLEXCEPT`, `REMOVEFILTERS`, `KEEPFILTERS`) | **M**. Only per-aggregate row conditions. No "% of total", "% of parent" or "ignore the desk slicer" | **H** | L | The single biggest semantic gap. Proposed: `share_of(agg, over: [fields] or 'all')`, `ignore(agg, fields...)` and `with(agg, filter)`, compiled to window or partition SQL or separate grouped sub-queries joined on the grouping. Keep it closed and side-effect free |
| Time intelligence (TOTALYTD, SAMEPERIODLASTYEAR, DATEADD, PARALLELPERIOD, DATESINPERIOD) | **P/B**. `ytd`, `mtd`, `qtd`, `prior_period`, `same_period_last_year`, `running`; business-day aware for prior and last-year windows (DATASETS §4.1) | H | S–M | **Ahead** on holiday calendars. Gaps: arbitrary `shift(agg, date, -N business-days / months)`, `period_to_date` with fiscal year-start, `opening/closing balance` (finance: **H**), `last_non_empty` for snapshot balances (**H**) |
| Calendar-based time intelligence (fiscal, 4-4-5 and custom calendars; preview from Sep 2025, verify) | **P**. Business calendars (USNY, GBLO, EUTA, JPTO) exist for business days; no fiscal year or custom period table | H | M | Generate a `calendar` table from the pack calendar with fiscal year, period and business-day index. This is the natural Drishti differentiator |
| Semi-additive measures (balances: LASTNONBLANK, CLOSINGBALANCEMONTH) | **M** (only via snapshot semantics: "read one date") | **H** | M | Positions, exposures and cash balances are semi-additive over time. Provide `last(agg, date)`, `first(agg, date)` and `avg_over_dates` |
| Iterators (SUMX, AVERAGEX, MAXX over a table expression) | **P**. Row expressions inside an aggregate (`sum(a*b)`) cover most SUMX use; iterating over a grouped table ("average of per-desk sums") is **M** | M | M | `avg_by(sum(x), table.col)`: a two-level aggregation emitted as a nested GROUP BY |
| Variables (`VAR`/`RETURN`) | **M**, partly covered by referencing measures | L | S | Readability only; add `let` if the language grows |
| Window functions / visual calculations (RUNNINGSUM, MOVINGAVERAGE, PREVIOUS, RANK; visual calcs GA status verify) | **B**. `lag`, `lead`, `rank`, `dense_rank`, `percent_rank`, `moving_*`, `cumulative`, partitioned by visual fields (DATASETS §4.3) | H | – | **At parity or ahead**: ranks are computed after row rules. Gaps: a window in a filter; a window with another fact |
| Calculation groups (one time-intelligence or currency-conversion logic applied to every measure) | **M** | **H** for finance (YTD/MTD/prior over 40 measures; FX conversion; scenario selection) | M | `calc-groups:` in the dataset as templates `{name: YTD, apply: "ytd(${measure}, trades.tradeDate)"}` expanded at compile time. Cheap given the AST compiler |
| Field parameters (the reader switches the measure or dimension of a visual) | **M** | H | M | Report-level `parameters:` of kind `field` bound into wells. Big UX win, compile-time only |
| What-if / numeric parameters (slider value used in a measure) | **M** | **H** (stress shifts, FX shocks, haircut %) | S–M | `param.<name>` in expressions as a bound parameter; a slicer of kind `parameter`. Also the hook for scenario analysis with QLRisk |
| DAX user-defined functions (preview Sep 2025, verify) | **M** | M | S | `functions:` in a dataset or a shared library item; macro-expanded and type-checked |
| Dynamic format strings | **M**. Format per measure only | L | S | |
| Text, DAX statistics and info functions | **P**. A small function set (`upper`, `contains`, `if`, `coalesce`, ...) | L | S | Extend on demand |
| Python measures | **B**, but only in a Python visual, board, or alert; **not inside a SQL query** (`DRS-9013`) | M | L | Ahead of Power BI (sandboxed, typed, tested) but awkward; consider DuckDB Python UDFs on the console side later |
| DAX query view / DAX Studio-style query authoring | **P**. `?explain=true` shows compiled SQL; Analyse SQL cells over extracts | M | S | Add a "query view" for authors: dataset JSON query in, table and SQL out |

### 2.3 Data preparation: the Python path instead of Power Query

**Decision (user, 2026-10-12): Drishti will not build a Power Query/M-like step tool.** Python is the data-prep answer: Transforms, derived datasets, Analyse notebooks and the sandboxed runner. Power Query is therefore listed in §6 (not copied). This section asks a different question: **does the Python path cover the jobs Power Query does, and what is missing from the Python *experience*?**

How the path works today (PYTHON_BI §13, DATASETS §11.2, ANALYSE):

- A `transform` Python item: `run(*frames, params)`. Its inputs are **datasets**, each asked as the reader.
- It returns a DataFrame, which a derived dataset publishes as one table with YAML measures.
- It runs in `core/pyrunner.py`: `python -I`, no network, CPU, memory and time limits, an audit hook.
- The result is kept per reader and per version of the inputs.
- Manifest `tests:` gate publish (`DRS-9076`).
- An Analyse cell that joins extracts can be promoted to a Transform draft.
- The composed-dataset YAML (`from:`/`join:`) covers equal-column joins without Python.

**Job coverage (Power Query job → covered by Python today?)**

| Power Query job | Python path today | Verdict | Notes |
|---|---|---|---|
| Merge (join) | Composed dataset (inner, left or full on equal columns) or pandas `merge` in a Transform, including tolerance and as-of (`merge_asof`) | **Covered** | Only YAML joins are pushed down; Python joins stage the whole input answers (`compose-source-max-rows`, `transform.max-rows`) |
| Append (union) | pandas `concat` in a Transform; no YAML `union:` | **Covered (Python only)** | A trivial job needing a Python item is friction; see snippets below |
| Unpivot / pivot / group by / fill down / split columns / replace / conditional columns | pandas (`melt`, `pivot_table`, `ffill`, `str.split`, `where`) | **Covered** | Needs pandas fluency; there are no guided helpers |
| Type fixes | The Transform result is typed from the frame (`int`→integer, ...); derived `columns:` may declare types; dataset columns cast and null bad values | **Covered** | There is no "detect types" suggestion step |
| Explode nested JSON | `each:` in dataset YAML for arrays; `json_normalize` in Python | **Covered** | |
| Clean **raw** connector data *before* modelling | **Gap.** Transform inputs are *datasets*, not connector tables; the Python **Source** interface is not built (§20.15) | **Partial** | So prep happens after a first, raw dataset is declared. The Source interface (or "a dataset table straight from a connector kind as input") closes it |
| Profiling (column quality, distribution, value counts) | Done by hand in Analyse (`df.describe()`, SQL over the extract); relationship inference profiles key matches only | **Partial** | No profile view |
| Parameterised sources | `params:` in the manifest, overridden by `derived.params` | **Partial** | Params are static per dataset: not bound to report slicers or what-if parameters, and not per business date beyond the query's dates |
| Refresh | The cache drops on a source load or refresh; the next reader re-runs it | **Partial** | No precomputed run on refresh: the first reader of the day pays the latency. Each reader is a separate process run (no warm worker pool), and results cannot be shared between readers (by design, masks) |
| Incremental prep (only the new date) | Re-runs over whatever the inputs return | **Gap** | No "per business date, append to a kept result" mode |
| Reuse (dataflows) | A Python item is reusable across derived datasets; Transform output is a dataset others can compose | **Covered** | |
| Lineage of prep steps | Lineage shows Transform → inputs; inside the code is opaque | **Partial** | Accepted cost of code over steps |
| Testing prep | Manifest `tests:` (inline frames or a dataset sample, `expect`, tolerance) gate publish | **Ahead** of Power Query | |
| Safety | Sandboxed, per reader, no network, audit hook | **Ahead** | |

**Gaps in the Python experience: these are the data-prep parity items**

| # | Item | Imp. | Effort | What it is |
|---|---|---|---|---|
| P1 | **Step history and preview** in the Transform editor | H | M | Run the Transform on a sample of the inputs (as the author) and show the frame after each top-level statement or each marked `# step:` comment: rows, columns, types and a head of the table. Undo by step. It is "applied steps", but the steps are lines of pandas |
| P2 | **Snippets library for common transforms** | H | S–M | Insertable, parameterised, tested pandas snippets: append, unpivot (`melt`), pivot, fill down, split column, replace values, dedupe, type coercion, as-of join (`merge_asof` for prices and FX), business-date alignment with the pack calendar, tenor bucketing. Each ships with a manifest test so a snippet is correct out of the box |
| P3 | **Profiling view** | H | S | For any extract or Transform output: nulls %, distinct count, min/max, top values, histogram, type guess and a "looks like a key of table X" hint. Computed in DuckDB-Wasm in Analyse or in the runner; one panel shared by the Transform editor, Analyse and the dataset wizard |
| P4 | **AI-written transforms** | M–H | M | "Describe the reshape" → a Transform draft over the declared inputs' schemas (column names and types only; no values unless `drishti.bi.ai.values` allows aggregates). It is checked by `/bi/python/check`, run on a sample and shown with a preview and its own generated test. It reuses the designer copilot pattern (`BiAiCopilot`), is off by default and goes through the AI policy |
| P5 | **Python Source interface / connector-kind inputs** | H | M–L | Let a Transform read a pack kind's columns through a connector (as the reader), so raw data can be cleaned before a dataset exists |
| P6 | **Precompute on refresh + warm worker pool** | M–H | M | On a scheduled or after-load refresh, run the Transform for a configured set of personas or roles (masks as each), so first-reader latency disappears; a warm pool of sandboxed workers instead of one process per run (in §20.15 not-built) |
| P7 | **Parameters bound to the report** | M | M | Transform `params` from report what-if or field parameters (2.3 roadmap), part of the cache key |
| P8 | **Incremental Transform mode** | M | M | `incremental: business-date`: run only for the new date's inputs and append to kept per-date partitions |
| P9 | **Promote from Analyse with steps kept** | M | S | Promote several cells (not one) into a Transform with the cells as steps (ties to P1) |
| P10 | **Schema contract and drift warning** | M | S | Declared output `columns:` compared on every run; a drift becomes a warning on the dataset page and the impact analysis, not a silent change |

Net: the Python path covers **every Power Query *job*** for a pandas-literate author, with better testing and safety. What it lacks is Power Query's **discoverability and feedback loop** (see each step, profile, pick a common operation, no code needed for trivial reshapes) and **operational fit** (raw inputs, precompute, incremental). P1–P4 close the discoverability gap for analysts; P5–P8 close the operational gap.

### 2.4 Visuals

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Core set (bar, column, stacked, line, area, combo, scatter, pie/donut, waterfall, funnel, treemap, map, card, multi-row card, KPI, gauge, table, matrix, slicer, text, image, button) | **B**. About 30 types (REPORTS §3) | H | – | 100% stacked, ribbon and new card visual variants are **M** (S each) |
| Matrix depth: stepped layout, expand/collapse all levels, row and column subtotals, show values on rows | **P**. Subtotals for the first row field only (collapsible), true totals; no multi-level expand or values-on-rows | H | M | Matrix is the finance workhorse; prioritise it |
| Conditional formatting: rules, colour scales/gradients, data bars, icons, field-value colours, web URL | **P**. Threshold rules on a measure, data bars. **No gradient scales, icons or field-value colours** | H | S–M | Gradients and icons are quick wins (ECharts `visualMap`, CSS for tables) |
| Small multiples | **M** | M | M | ECharts grid-of-grids; one extra well `split` |
| Decomposition tree | **M** (explain-a-number gives a one-level breakdown, BI_AI §6) | M | M | Very useful for P&L attribution ("why is desk P&L down"); build on the explain endpoint |
| Key influencers, anomaly detection, forecasting on line charts | **M** | L–M | M | Do it through Python items and Analyse rather than black-box ML |
| Q&A visual (retiring Dec 2026) / smart narrative | **P**. "Ask the data" box and narrative summaries in subscriptions (BI_AI §5, §9); no on-canvas narrative visual | L | S | Do not copy Q&A; a narrative visual that reuses `AiNarrator` is S |
| R / Python visuals | **B** Python (sandboxed, ECharts/SVG/PNG/Vega-Lite output, tests); R **M** | L | – | **Ahead** on safety |
| Custom visuals (AppSource, pbiviz SDK, certified visuals) | **P**. Vega-Lite spec visuals (sandboxed interpreter, no URLs) and Python visuals act as the extension point | M | L | A curated, signed visual-plugin SPI (ECharts option builders) is the safe analogue; never a marketplace that fetches code |
| Maps (Azure Maps, ArcGIS, shape map, filled map) | **P**. ECharts geo with vendored shapes, countries and ISO 3166-2 regions, lat/long points; no tiles, no custom shapes (REPORTS §12) | L–M | M | Custom GeoJSON upload is the useful gap |
| Shapes, lines, decorative | **M** (`shape` reserved, `DRS-9033`) | L | S | |
| Live/streaming visuals | **B**, far ahead: Perspective grid, pivot and chart, rolling live chart, live boards, hundreds of updates a second | H | – | **Ahead** |
| Entity drill to the operational record | **B** (entity view, Open in terminal) | H | – | **Ahead** |

### 2.5 Report authoring UX

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Fields, Visualizations (Build/Format), Filters panes; drag fields to make visuals | **B** (BI_GUIDE §15) | H | – | Smart visual choice on drop already exists |
| Format pane depth (fonts, axes min/max/log, gridlines, label density, per-series colours, borders, shadows, padding, titles and subtitles) | **P**. About 12 format keys (REPORTS §5) | **H** | M–L | Largest perceived gap for Power BI authors. Plan a typed format schema per visual family, generated pane |
| Themes (JSON theme files, report theme, per-visual style presets) | **P**. Three palettes (`default`, `colour-blind`, `mono`), following light/dark/high-contrast | M | M | Site theme items (`theme` item kind) versioned like others; a corporate theme for client decks |
| Templates (.pbit) | **P**. "Samples to start from", Save a copy, report-from-words | M | S | A report item with `template: true` plus parameters |
| Bookmarks, selection pane (show/hide visuals), bookmark navigator | **P**. Bookmarks store page, filters and slicers; **no visibility state, no selection pane, no navigator visual** | M | M | |
| Drillthrough (cross-report too), back button | **B** within a report with `?carry=`; **cross-report drillthrough M** | M | S | |
| Report-page tooltips | **M** (ECharts hover tips only) | M | M | |
| Sync slicers (groups across selected pages) | **P**. `scope: page` or `report` only | M | S | Add `sync-group:` |
| Slicer types: list, dropdown, range, relative date, hierarchy, button/tile slicer, search | **P**. List (with search), dropdown, range, relative date (business-day aware); **hierarchy slicer M**, tile slicer **M** | M | S–M | |
| Mobile layout | **B**. Phone and tablet layouts, designer tabs (REPORTS §14) | M | – | At parity |
| Accessibility (keyboard nav, screen reader, alt text, tab order, high contrast) | **B**. Keyboard throughout, reading-order navigation, alt text, A11Y checks in Check, Show as a table | H | – | **Ahead**: checks that block nothing but are always run |
| Performance analyzer | **P**. Query time under each visual; a `PERF` check over 1 s; `?explain=true` SQL | M | S | A per-visual timing panel (queue, engine, layer used, rows, render) is cheap: `meta.layer` already exists |
| Undo/redo, copy/paste visuals, align/distribute, grouping | **P**. Undo/redo (100), duplicate, 12-column snap grid; no grouping, align or freeform positioning | M | M | |
| Source-code view of the report | **B**. The YAML tab with suggestions, two-way sync | H | – | **Ahead** (PBIR is JSON and not edited live in Desktop) |
| Report-level measures, visual-level calculations | **M** | M | S | Report-local `measures:` compiled into the dataset query |
| Data preview while designing | **B**, as the author with masks | H | – | **Ahead** |
| Expected-value tests on numbers | **B**, gating publish | H | – | **Ahead**: Power BI has no equivalent outside third-party tools |

### 2.6 Interactivity

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Cross-filter vs cross-highlight | **P**. Cross-filter everywhere; highlight only for bar and column (REPORTS §12) | M | S–M | Extend highlight to line, area, combo and matrix |
| Edit interactions (per source–target pair: filter / highlight / none) | **P**. Per visual `interacts: false` only | M | S | `interactions: {from: {to: filter, highlight or none}}` |
| Drill down/up, expand next level, drill mode toggles | **P**. Drill down/up along a hierarchy; no "expand all down one level" | M | S | |
| Drillthrough with "keep all filters" | **P** (carried value) | M | S | |
| Interaction on boards (tiles cross-filter, slicers) | **M** (BOARDS §10) | M | M | |
| Cross-filter in embedded reports, element to element | **P**. Within one element only | L | M | |

### 2.7 Service: workspaces, sharing, lifecycle

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Workspaces with roles (Admin, Member, Contributor, Viewer), My workspace | **B**. Same four roles (BI_GUIDE §7) | H | – | |
| Apps (packaged audience views with navigation) | **P**. Catalogue entries with an audience and endorsement; no "app" bundle with its own navigation | M | M | `app` item kind: ordered reports and boards, sections, audience |
| Deployment pipelines (Dev/Test/Prod with rules) | **B**, different shape. Bundles with dry run, checksums, owner and workspace mapping, rollback, and BI in pack archives (BI_GOVERNANCE §7) | H | – / S | Gap: parameter rules per stage (connector or dataset substitutions). Small |
| Git integration and PBIP/PBIR/TMDL developer workflow | **B**, better: `config/bi` YAML files, watched live, diffable; saved states and diffs in the product | H | – / M | Gap: two-way sync between a Git branch and stored items (workspace ↔ repo). Medium |
| Lineage view, impact analysis | **B**. Past the connector; impact by name, type and meaning; rename rewriting (BI_GOVERNANCE §5–6) | H | – | **Ahead**. Gaps: boards and notebooks are not in lineage; relationships and row rules are not compared |
| Endorsement (promoted, certified) | **B**, with automatic revocation on incompatible change | H | – | **Ahead** |
| Sensitivity labels (Purview), label inheritance to exports | **M** ("classification tags" not built, RUPAKA §20.15) | M–H (bank DLP) | M | Labels on datasets, inherited by reports and exports; watermark exports; pair with masks |
| Review and approval | **B**. Four-eyes, steward approval, reject with reason | H | – | **Ahead** (Power BI has no native publish approval) |
| Comments on reports and visuals with @mentions | **M** in BI (Drishti has threads and shares elsewhere: RUPAKA §1 claims "Beat on compliance", but no BI hook was found in `bi-report.js`) | M | S–M | Wire existing collaboration threads onto a report or visual with state, so a comment reproduces the view |
| Usage metrics (views, users, performance per report) | **P**. View counts per item per day (30 days) | M | S–M | A usage report built as a BI dataset over the audit log |
| Data alerts | **B**. Alerts on measures, fields and tiles; hysteresis, cooldown, retries; email, inbox, chat, webhook, SMS (DISTRIBUTION §4) | H | – | **Ahead** (per-person numbers under masks). Gaps: escalation, quiet hours, multi-condition |
| Subscriptions | **B**. Per-recipient renders under that recipient's masks; narrative; business-day schedules | H | – | **Ahead** |
| Paginated reports (RDL, pixel-perfect, parameters, page headers, large printouts) | **P**. Paged PDF of tables with repeated headers; no layout control (DISTRIBUTION §7) | M–H (regulatory packs, client statements) | L | A `paginated` report kind: bands, groups, page breaks, parameters, rendered server-side. See §5 |
| Dashboards (pinned tiles) | **B** (boards, live, kiosk, alerts) | M | – | **Ahead** for live walls |
| Goals / scorecards (metric sets retired Nov 2025; scorecards remain) | **M** | L–M | M | Limits and KRIs (key risk indicators) are the finance form. A board with targets and history may suffice |
| Dataflows, datamarts, Fabric items | **N/A** (reusable Python Transforms and derived datasets play the dataflow role) | L | – | |
| Copilot (report creation, summaries, DAX help, "chat with your data") | **B** in kind. Report from words, ask the data, explain a number, designer copilot, dataset helper, narratives; provider-neutral, off by default, masks in prompts (BI_AI) | M | – | **Ahead** on control. Gaps: follow-up memory, "why did it move between two dates" (H for P&L explain), streaming answers |
| Refresh schedules, failure notifications | **B**. Cron, business-day schedules, after-load, retries, state that survives a restart | H | – | Gaps: back-dated refresh; inbox notice of a final failure |

### 2.8 Embedding

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Power BI Embedded (app-owns-data, user-owns-data), embed tokens | **B**. `<drishti-report>` Web Component with no iframe, token exchange, always masked; `<drishti-about>`, `<drishti-search>` (ELEMENTS, DISTRIBUTION §5) | M | – | **Simpler, no capacity licensing** |
| JS SDK: events, set filters, bookmarks, navigation, export from host | **P**. Events (`drishti:filter-changed`), filters; **drill, export, alerts, Perspective, Vega and Python visuals not in embed** | M | M | |
| Publish to web | **N/A** (deliberately absent) | – | – | Do not copy |
| Embedded authoring (edit in host) | **M** | L | L | Skip |

### 2.9 Excel integration

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Export underlying or summarised data to Excel | **B**. XLSX with real charts (bar, column, line, area, pie, donut), masks honoured | H | – | |
| Analyze in Excel (live PivotTable over the semantic model via XMLA/MDX) | **M** | **H** (finance lives in Excel) | L–XL | Options, cheapest first: (a) Excel **Power Query web connector** over a token-authenticated `/api/v1/bi/datasets/{id}/query` returning CSV/JSON (S–M); (b) an Excel add-in (Office.js) that calls the query API with field pickers (L); (c) an XMLA/MDX endpoint (XL, not advised) |
| Excel PivotTables in exports | **M** | M | M | Write a pivot cache from the export rows (SpreadsheetML pivotCacheDefinition) |
| Connected tables / Excel data types | **M** | L | L | |

### 2.10 APIs, XMLA and external tools

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| REST APIs (workspaces, reports, refresh, export) | **B**. `/api/v1/bi/*` (items, datasets query with Arrow/JSON, refresh, bundles, tests, export) | H | – | |
| XMLA read/write endpoint (Tabular Editor, DAX Studio, SSMS, third-party BI) | **M**. LIVE_BI.md says explicitly: "No SQL endpoint ... no ODBC/JDBC driver for BI tools" | M–H (developers, quants in Python and Excel) | L | Recommend an **Arrow Flight SQL** or **PostgreSQL-wire** read endpoint exposing datasets as views with measures (a semantic SQL in the style of Cube or dbt metrics), every query as the authenticated reader with row rules and masks. Python `drishti_bi` already exists for Calc |
| Execute queries API (DAX over REST) | **B** analogue (dataset JSON query API, `?explain=true`) | H | – | |
| Semantic link (Fabric notebooks reading models) | **B** analogue: Analyse extracts, `drishti_bi.query()` in Calc | M | – | |

### 2.11 Performance at scale

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| VertiPaq in-memory columnar compression, sub-second slice of large imports | **P**. DuckDB over Parquet columns per business date, rollups and a result cache; "about 134 ms over a million trades a day" for Delta promoted columns (RUPAKA §7.1); the LOAD_AND_MEMORY capacity run at a million rows is **not done** (§20.15) | H | M | Must measure before claiming parity. Memory note: keep local runs small (10K trades), so benchmarking at scale needs a dedicated box |
| Query caching, aggregation awareness | **B** (result cache keyed by dataset version, query, row-rule set and data generation; rollup routing) | H | – | |
| Scale-out (capacities, query scale-out replicas) | **M**. A single node; the `AnalyticEngine` interface exists for Trino, ClickHouse and others, but only DuckDB is built | M | L | Second engine only when a customer needs it |
| Large dataset storage format, multi-TB | **P** (Parquet layout scales on disk; untested at multi-TB) | M | – | |

### 2.12 Administration

| Power BI capability | Drishti today | Imp. | Effort | Notes |
|---|---|---|---|---|
| Admin portal, tenant settings, capacity metrics | **P**. Admin BI settings, four powers on roles, query limits, Health, audit; no capacity/usage dashboard | M | S–M | Ship an "Admin BI usage" report as sample content over the audit and query logs |
| Audit log | **B**. Every BI action plus `bi-query` | H | – | **Ahead** (on-prem, full) |
| Information protection / DLP | **P** (masks, redaction everywhere; labels missing) | M | M | See sensitivity labels |
| Licensing tiers (Pro, PPU, capacity) | **N/A** | – | – | Advantage: no per-viewer licence |

---

## 3. Where Drishti is already ahead (keep and market)

1. **Security before arithmetic.** Masks are applied before aggregation and joins, masked dimensions are not grouped on, and filtering or sorting on a masked column is refused (`DRS-9021`). Composed datasets ask every source as the reader before joining. Power BI's object-level security hides objects but cannot produce a "same numbers, opaque labels" view.
2. **Fan-out refusal and grain-correct measures.** Multi-fact queries are answered per fact and joined. Mixed-grain measures are split. A row rule that cannot be honoured refuses the query instead of leaking (`DRS-9020`). DAX can silently over-count with bidirectional or many-to-many filtering.
3. **True totals** for measures that do not add up, computed by the engine per level.
4. **Live by default.** Live tables kept by key, incremental live rollups, Perspective at hundreds of updates a second, live boards with hysteresis alerts, history of live measures. Power BI streaming datasets are being retired (verify) and were always limited.
5. **Business-date awareness.** Holiday calendars in `prior_period`, `same_period_last_year`, relative-date slicers, refresh schedules (`business-days 06:30`, `monthly last business`), after-load refresh.
6. **Governance as code with gates.** YAML in Git, four-eyes approval, expected-value tests that block publish, rename-aware impact with automatic report rewriting, certification revoked automatically, bundles with checksums and rollback.
7. **Every number has a way down.** Show the rows, Open in terminal, entity view, explain-a-number lineage down to the connector table or topic.
8. **Python done safely.** Typed interfaces (Measure, Visual, Transform), sandboxed runner, tests in the manifest, browser Pyodide, per-reader Transform results.
9. **Distribution per recipient.** Each subscriber gets their own masked numbers and narrative; webhooks are signed with HMAC; SMS.
10. **AI that is optional and governed.** Provider-neutral, off by default, masks in prompts, model output validated by the parser before display.
11. **On-premises, no gateway, no capacity licence**, one connector layer shared with the terminal.

---

## 4. Top 15 gaps ranked by value/effort

Score = importance (H=3, M=2, L=1) × user reach ÷ effort (S=1, M=2, L=3, XL=5), then adjusted by judgement for dependencies.

| # | Gap | Imp. | Effort | Why now |
|---|---|---|---|---|
| 1 | **What-if / numeric parameters** (`param.x` in measures, a parameter slicer) | H | S–M | Stress, FX shock and haircut scenarios; a prerequisite for QLRisk scenarios |
| 2 | **Filter-context functions**: `share_of` / `pct_of_total` / `pct_of_parent`, `ignore(fields)`, `with(filter)` (the CALCULATE core) | H | L | The most-asked DAX pattern; blocks "% of desk total", "vs. all desks" |
| 3 | **Calculation groups** (template measures: YTD/MTD/prior/FX) | H | M | Removes measure explosion in finance models; compile-time only |
| 4 | **Semi-additive and balance measures** (`last`, `first`, `opening` / `closing` over a date) | H | M | Positions, balances and exposures over time are wrong without it |
| 5 | **Inactive relationships via a measure + role-playing dates** (`via: trades.settleDate`) | H | M | Trade, settle and value dates |
| 6 | **Conditional formatting depth** (gradient scales, icons, rules on another measure, field-value colour) | H | S–M | Visible, cheap |
| 7 | **Matrix depth** (multi-level expand/collapse, subtotals at every level, values on rows, stepped layout) | H | M | Finance's main visual |
| 8 | **Format pane depth + themes as items** | H | M–L | First impression for Power BI authors |
| 9 | **Field parameters** (switch measure or dimension) | H | M | Collapses many near-duplicate visuals |
| 10 | **Excel live access**: Power Query web connector feed over the query API (phase 1), Excel add-in (phase 2) | H | M (then L) | Finance lives in Excel; Analyze in Excel is the most-missed Power BI feature in banks |
| 11 | **Python data-prep experience**: step preview (P1), snippets library (P2), profiling view (P3), then AI-written transforms (P4) | H | S–M each | Makes the Python path usable by analysts who would have used Power Query (§2.3) |
| 12 | **Fiscal / custom calendar table generated from the pack calendar** | H | M | Extends the business-date lead into fiscal reporting |
| 13 | **Tooltip pages, small multiples, edit interactions per pair, sync-slicer groups, highlight for more charts** | M | M | The authoring-familiarity bundle |
| 14 | **Comments on reports and visuals** (reuse Drishti threads, with captured state) + **usage metrics report** | M | S–M | Collaboration already exists; wiring is cheap |
| 15 | **Semantic SQL endpoint** (Flight SQL or Postgres wire, read-only, as the reader) | M–H | L | Developers, Python and third-party tools; replaces XMLA's role |

Just outside the top 15: paginated reports (M–H, L), sensitivity labels (M–H, M), bidirectional and many-to-many as semi-joins (M, M), decomposition tree (M, M), and back-dated refresh (H, S, which belongs in phase 2.2 as a loose end).

---

## 5. Phased roadmap

### 5.0 Redesign items (breaking changes allowed)

**Guidance (user, 2026-10-12).** Drishti is not in production, so drastic, breaking changes to BI are acceptable and there is no backward-compatibility burden. The **fundamentals are fixed**:

- everything is **YAML** (governance as code);
- **Python** is the extension and data-prep language;
- **datasets and data models** are the core abstraction.

Each redesign below keeps all three. They are proposed where patching the 2.1.0 shapes would give a worse result than reshaping them. Each lands with a one-shot converter for the shipped samples (`config/bi/**`) and tests, then the old shape is deleted (no dual support).

| # | Redesign | Today (why it limits) | Proposed shape (still YAML, Python, datasets and models) | Unlocks | Phase |
|---|---|---|---|---|---|
| R1 | **One table model for every dataset** | Composed and derived datasets are one table (`joined`) with no relationships or rollups (DATASETS §10). Connector tables are a separate path | Every table has a `source:` of one kind: `connector` (kind, as today), `dataset` (a query to another dataset as the reader), `python` (a Transform's output) or `union` (of other tables). Relationships, rollups, calendars, row rules and masks then apply uniformly. A "composed dataset" becomes an ordinary multi-table model | Multi-table models over other datasets, star schemas on Python output, union without new syntax, the P5 Source path | 2.3 |
| R2 | **Measure language v2 with an explicit filter context** | Aggregates with row conditions; time and window functions as special forms; no context override | `rachana: 2` measures: one documented evaluation model (grouping and filters as the context) with `over:` / `ignore:` / `with:` modifiers, `share_of`, semi-additive `last/first/opening/closing`, `via:` relationship activation, `param.*`, `let` bindings and user `functions:`. Calc groups become measure templates. Still closed and compiled to SQL; the grain-splitting and fan-out refusal become rules of the evaluation model rather than compiler special cases | Every DAX-gap item of §2.2 as one coherent design rather than piecemeal functions | 2.3 |
| R3 | **Stable ids for fields and measures** | Renames are detected from `path:`/`expr:` heuristics; a column with neither cannot be renamed safely (BI_GOVERNANCE §10) | Each column and measure carries an immutable `id:` (generated, written back into the YAML); reports and alerts bind to ids and show names. A rename is just a name change | Exact impact analysis; no report rewriting on rename; safer bundles | 2.2 |
| R4 | **Relationship semantics as filter propagation** | Joins follow many-to-one only; `both` and many-to-many are stored but ignored | Relationships declare *filter propagation* (direction, cardinality, active), implemented as semi-joins. Joins for measure evaluation stay grain-safe; fan-out refusal is kept as an invariant with tests | Bidirectional slicers, bridge tables, role-playing dimensions | 2.3 |
| R5 | **Calendar as a model object** | Business calendars live in server settings; time functions take a date column | `calendars:` in the model (from the pack calendar, fiscal year start, retail 4-4-5), and date columns `use:` a calendar. Time intelligence resolves against it | Fiscal and custom periods, business-day offsets, week functions | 2.3 |
| R6 | **Report schema v2: typed format, themes, interactions and parameters** | `format` is a flat map of about 12 keys; three palettes; `interacts: false` only | A per-visual-family JSON-schema-typed `format:` (generated designer pane); a `theme:` reference to a theme item; an `interactions:` matrix; `params:` (what-if, field) and `sync-groups:`; one shared **filter grammar** for report, page and visual filters, bookmarks, alerts and row rules | Authoring depth without one-off keys; schema completion in the YAML tab | 2.2 |
| R7 | **Boards as a report layout** | Boards are a separate item kind with no interactions, slicers or drill (BOARDS §10) | A report page with `layout: board` (tiles, kiosk, rotation, tile alerts) inherits filters, slicers, cross-filter and drill for free; the `board` kind goes away | Removes a parallel code path; interactive live walls | 2.5 |
| R8 | **Python measures in queries** | A Python measure is refused in SQL (`DRS-9013`) and shown only by a python visual | Python measures run as a post-aggregation step on the engine's grouped frame (per reader, sandboxed), declared in YAML with their inputs and grain; usable in any visual, export and subscription | Quant measures (VaR, vol) in ordinary visuals | 2.4 |

The roadmap phases below absorb these redesigns: R3 and R6 in 2.2; R1, R2, R4 and R5 in 2.3; R8 in 2.4; R7 in 2.5.


Assumptions: the 2.1.0 base; breaking changes are allowed (§5.0); frugal, fast waves; one drill per release; at most two builders at a time, one heavy test each (user memory). Effort sizes are builder-time, not calendar time.

### 2.2 "Authoring depth" (the first-ten-minutes experience)

**Scope**

- **Redesigns R3 (stable ids) and R6 (report schema v2)** first; the items below are built on the new schema.
- Format schema per visual family: axes (min, max, log, gridlines, label rotation), per-series colours, title and subtitle, borders and padding, data label density. The pane is generated from the schema.
- Themes as a `theme` item kind (JSON-like YAML: palette, fonts, visual defaults) with site and report scope.
- Conditional formatting: gradients (2- and 3-stop), icons, "colour by another measure", field-value colours.
- Interactions: highlight for line, area, combo and matrix; per-pair `interactions:`; `sync-group` for slicers.
- Hierarchy slicer and tile/button slicer.
- Tooltip pages: a page with `tooltip: true` rendered in a popover with the hovered mark's filter.
- Small multiples: a `split` well on bar, column, line and area.
- Bookmarks capture visual visibility; a selection pane; a bookmark-navigator visual.
- Performance panel: per-visual queue, engine and render ms, `meta.layer`, rollup hit.
- Back-dated refresh: a dataset refresh for a named date (from the 2.1.0 not-built list).

**Dependencies**

- None on the engine, except tooltip pages and small multiples, which reuse `VisualQueries` with an extra filter or group.

**Risks**

- `bi-design-panes.js` and `bi-visuals.js` growing past the 1500-line cap. Split by visual family.
- The strict CSP for inline styles: themes must go through CSS variables, not `style` attributes.

**Acceptance**

- A Power BI author recreates 3 reference finance pages (a P&L by desk matrix with gradients, an exposure trend with small multiples, a KPI strip with a tooltip page) without the YAML tab.
- The designer's Check stays clean.
- No new CSP exceptions.
- Exports honour the theme colours where the exporter draws charts.

### 2.3 "Modelling and calculation depth" (the DAX-gap release)

**Scope**

- **Redesigns R1 (one table model), R2 (measure language v2), R4 (filter propagation) and R5 (calendars)**. The items below are features of that design, not patches to 2.1.0's compiler.
- `params:` (numeric, list and field parameters) and a parameter slicer; `param.x` bound in expressions.
- `calc-groups:` as templates expanded at compile time, chosen by a slicer or a well.
- Filter-context functions:
  - `share_of(agg, over)` and `pct_of_parent` via window partitions;
  - `ignore(agg, fields...)` and `with(agg, filter)` as grouped sub-queries joined on the remaining grouping, reusing the multi-fact join machinery.
- Semi-additive functions: `last(agg, date)`, `first(agg, date)`, `opening` / `closing` (period), `avg_over_dates`.
- Relationships:
  - `via:` to activate an inactive relationship per measure (role-playing dates);
  - `related()` in calculated columns;
  - chained calculated columns.
- Bidirectional and many-to-many relationships implemented as semi-join filter propagation, never join expansion. Fan-out refusal stays.
- Generated calendar table (`calendar: { from-pack-calendar: true, fiscal-year-start: 04-01 }`) with fiscal period, business-day index and week-based functions.
- Report-local measures.
- A "query view" for authors.
- `functions:` (user-defined expression macros).

**Dependencies**

- 2.2's parameter slicer UI.
- `SqlCompiler` / `SqlEmitter` refactor so filter-context functions compose with grain splitting (§20.12–20.13).

**Risks**

- Semantic correctness: CALCULATE-like overrides interact with row rules. **Rule: overrides may only remove user-chosen filters, never row-rule predicates or masks.** Test this with three-reader brute-force checks, as `GrainTest` does.
- Rollup routing for the new functions: refuse, then route to the columns layer.

**Acceptance**

- A finance model passes a brute-force oracle test suite (DuckDB hand-written SQL vs compiled) on: % of total under a slicer, YTD for 40 measures via one calc group, a closing balance by month, settle-date vs trade-date volumes, and an FX what-if.
- Masked readers can never recover a masked value through `ignore()`.
- A DAX→Drishti mapping table in DATASETS.md.

### 2.4 "Python data prep and Excel"

**Scope**

- **Redesign R8** (Python measures in queries).
- The data-prep parity items of §2.3, all on the Python path; no step language, no M:
  - P3 profiling view, shared by the Transform editor, Analyse and the wizard;
  - P2 snippets library with tests, including `merge_asof` for prices and FX, union, unpivot and business-date alignment;
  - P1 step history and preview in the Transform editor;
  - P9 multi-cell promote;
  - P10 schema drift warning;
  - P5 the Python Source interface / connector-kind inputs;
  - P6 precompute on refresh for configured roles, plus a warm worker pool;
  - P4 AI-written transforms (off by default, under the AI policy);
  - P7 and P8 (report-bound params, incremental mode) after 2.3's parameters.
- Union without Python comes from R1 (`source: { union: [...] }` on a table); no other YAML prep syntax.
- Excel, part 1: a token-authenticated **feed URL** per visual or dataset query (CSV/JSON/OData-lite) for the Excel Power Query web connector; masks and row rules as the token's user.
- Excel pivot tables in exports.

**Dependencies**

- `core/pyrunner.py` and `transform_cli.py`, `TransformService`, the Analyse page, and `BiAiCopilot` for P4.
- Embed/API token scopes (`embed_auth`) for feeds.

**Risks**

- Precompute per role multiplies runs. Bound it by configuration and keep results per reader; never share across readers with different masks.
- Preview runs read real data as the author, so they must go through the same as-the-reader path and limits.
- AI-written code is only a draft: it must pass the check, the sandbox and its generated test before it can be saved.
- Feed tokens in Excel workbooks are long-lived credentials. Make them short-lived with refresh, revocable and audited.
- Memory: the warm pool's size and limits must respect the local-machine constraints.

**Acceptance**

- An analyst who does not know pandas does three typical Power Query cleanups using snippets and the step preview, with results equal to a reference: a tenor-bucket unpivot, a two-region append, and an FX as-of join.
- The first reader after an after-load refresh gets a precomputed Transform result.
- The profile panel matches a DuckDB oracle.
- An Excel workbook refreshes a masked dataset for two users, and each sees their own rows.

### 2.5 "Service and operations"

**Scope**

- **Redesign R7** (boards become a report layout; this supersedes the separate "board interactivity" item below).
- Comments on reports and visuals: existing Drishti threads, with the captured view state and @mentions.
- A usage-metrics dataset and report (shipped sample) over audit and query logs.
- Sensitivity labels / classification on datasets, inherited by reports, exports (watermark and header), subscriptions and embed.
- An `app` item kind (an ordered navigation of reports and boards for an audience).
- Deployment rules per environment in bundles (connector and dataset substitutions).
- Re-certification schedules; multi-reviewer approval chains.
- Alert escalation and quiet hours.
- Board interactivity (tile cross-filter, board slicers).
- Lineage for boards and notebooks.
- Two-way Git sync of workspace items (export to a branch, import from a commit through the existing bundle checks).

**Dependencies**

- The collaboration and identity modules.
- The bundle import path.

**Risks**

- Scope creep. Keep each item a thin layer on existing machinery.

**Acceptance**

- A steward can see who used what and when.
- A labelled dataset's export carries the label everywhere it goes.
- A dev→test→prod promotion with a connector substitution passes a dry run and rolls back.

### 2.6 "Paginated and statements"

**Scope**

- A `paginated` report kind: bands (header, group, detail, footer), page breaks, keep-together, parameters, sub-reports.
- Server-rendered PDF, and later DOCX; per-recipient bursting through subscriptions.
- A headless pixel-faithful PNG/PDF of interactive pages (Chromium, optional).

**Risks**

- The rendering library choice (licence) and CSP.
- Fonts on-prem.

**Acceptance**

- A regulatory pack and a client statement are produced per recipient, masked, with page numbering and repeated group headers, matching a reference PDF layout.

### 3.0 "Open semantic layer and scale"

**Scope**

- A read-only **semantic SQL endpoint** (Arrow Flight SQL first, then Postgres wire): datasets as schemas, measures as `MEASURE()` or aggregate views; every query as the authenticated reader (row rules, masks, limits, audit).
- An Excel add-in over the query API.
- A second `AnalyticEngine` (Trino or ClickHouse) for multi-TB datasets.
- The LOAD_AND_MEMORY run at a million rows and beyond on a dedicated machine.
- Decomposition tree and "why did it move between two dates" on the explain engine.
- A signed visual-plugin SPI.
- Custom GeoJSON maps.

**Risks**

- External SQL bypassing report-level intent. Mitigate: only published, certified-or-promoted datasets are exposed, per-token scopes, and the same limits.

**Acceptance**

- Tableau or Excel (ODBC via Flight SQL), Python ADBC and a dbt-style client query a dataset and get identical, masked numbers to the report for three personas.

---

## 6. Deliberately NOT copy

| Power BI thing | Why not |
|---|---|
| **DAX compatibility / full DAX** | Context transition, iterators over virtual tables and bidirectional ambiguity are the main source of wrong numbers in Power BI. Drishti's closed, compiled, grain-checked language is a feature. Copy the *patterns* (filter overrides, calc groups, time intelligence), not the language. Publish a mapping table |
| **Power Query / M (step-based data-prep tool) and its gateway / 300-connector world** | **Decided (user): Python is the data-prep answer.** Transforms, Analyse and the sandboxed runner already cover every Power Query job (merge, append, unpivot, type fixes, parameters) with tests, per-reader safety and no new language to secure. Effort goes into the Python *experience* instead (§2.3 P1–P10). Drishti also owns its connectors (one audit, one TLS, one health), so a gateway is unnecessary |
| **AppSource marketplace / arbitrary custom-visual JavaScript** | Breaks the strict CSP and the "nothing in a report executes" rule. Use the sandboxed Vega-Lite and Python visuals and a signed in-house plugin SPI |
| **Publish to web (anonymous public links)** | Incompatible with masks and with the regulated audience |
| **Fabric/OneLake, Direct Lake, capacities, licensing tiers** | Drishti is on-prem first; the business-date Parquet columns layer is already the "Direct Lake" idea |
| **Legacy Q&A (retiring Dec 2026) and linguistic schema editing** | Microsoft itself is retiring it; Drishti's "ask the data" via the validated query is the right shape |
| **R visuals** | Little demand in this user base; doubles the sandbox surface |
| **Dashboards as static pinned tiles** | Boards are already live; do not regress to snapshots |
| **Metric sets** (retired Nov 2025) and heavy goals UX | Limits and KRIs are better as datasets plus boards plus alerts |
| **Bidirectional filtering as join expansion** | Implement as semi-join propagation; keep fan-out refusal |
| **Freeform pixel canvas** | The 12-column grid is what makes phone/tablet layouts, keyboard order and A11Y checks work. Offer finer grid steps instead |

---

## 7. Where Drishti should differentiate

1. **"Correct by construction" BI.** Market grain safety, true totals, fan-out refusal, fail-closed row rules and expected-value tests as *numbers you can sign*. Add a "number certificate": a figure's lineage, its tests, its as-of, and who certified it.
2. **Risk-native semantics.** Business calendars, as-of joins, semi-additive balances, FX calc groups, what-if shocks feeding the QLRisk `QuantEngine` (scenario, VaR and sensitivity measures), and limits/KRIs as first-class datasets.
3. **Live operational BI.** Perspective plus live boards plus incremental rollups plus drill to the trade. Power BI cannot follow a trading day; Drishti can. Extend with live slicers on boards and live paginated "intraday snapshots".
4. **Per-reader everything.** Masked extracts, per-recipient subscriptions and narratives, per-reader Transforms, and embed with masks. Make "the same report, different people, provably different numbers" a demo.
5. **Governance as code with gates.** Git-native YAML, review, tests-before-publish, rename-aware impact and rollback. Add two-way Git sync and CI (a `drishti bi check` CLI in pipelines).
6. **Analyst plus developer workbench in one.** Analyse (DuckDB-Wasm, Pyodide, notebooks, promote to visual), typed Python items with tests, and an open semantic SQL endpoint. That is "Power BI plus Jupyter", governed.
7. **Optional, provider-neutral AI** with masks applied to prompts and model output validated by the same parser.

---

## 8. Uncertainty (verify before quoting externally)

- The GA status of visual calculations, DAX user-defined functions, calendar-based time intelligence, PBIR/PBIP and TMDL in the Service, Git integration scope, and translytical task flows (write-back): all **verify**.
- The retirement timeline of Power BI real-time streaming datasets: **verify**.
- The current Copilot scope (standalone Copilot over all data, DAX query Copilot) and licence requirements (Fabric F-SKU vs Pro or PPU): **verify**.
- Whether Analyze in Excel now works over Direct Lake and composite models without XMLA write: **verify**.
- The Drishti "hybrid hot + columns join for live tables" (RUPAKA §7.1) is design text; I did not confirm it in code. Check `LiveTables` / `Layers` before citing it.
- Drishti BI comments: RUPAKA §1 claims collaboration, but no BI comment hook was found in `bi-report.js`. Treated as missing for BI.

## 9. Sources (web)

- [Deprecating Power BI Q&A (Power BI blog)](https://powerbi.microsoft.com/blog/deprecating-power-bi-qa)
- [MC1218421: Retirement of Power BI Q&A](https://mc.merill.net/message/MC1218421)
- [Deprecation of metric sets in Power BI](https://powerbi.microsoft.com/blog/deprecation-of-metric-sets-in-power-bi)
- [SQLBI: what happened in the DAX world in 2025](https://www.sqlbi.com/blog/marco/2025/12/29/what-happened-in-the-dax-world-in-2025/)
- [Power BI custom calendars (calendar-based time intelligence)](https://databear.com/power-bi-custom-calendars/)
