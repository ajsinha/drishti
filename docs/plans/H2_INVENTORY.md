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

# H2 inventory: the non-risk screens on library fragments

Status: proposed, a write-only pass (nothing built or run on a server). Branch `i8/h2`, from `wip/g4-rehearsal-rx`.

## What the inventory found

1. The non-risk screens are already short Sutras of `use:` fragments. Of 1,538 panels in the 248 non-risk Sutras, 1,325 (86%) are
   `use:` of a Drishti UI-kit fragment (`config/ui-kit/fragments`, 17) or a pack fragment (`sutras/common`). 213 panels remain written in place.
2. Most of the Sutras are generated (trading deal screens 130, market-data 18, core 10, politics 6, example packs, genomics showcase 14).
   The generators (`tools/packgen/*`) already write fragment uses and a `doc:` block; converting a generated Sutra means changing its
   generator, so the conversions here are generator changes plus the regenerated files.
3. The library fragments of Rachana (`core`, `finance`, i8 role-home) are typed, hermetic fragments (`arg.`, columns passed in `with:`,
   `libraries: [core@1]`). Drishti on this branch resolves only the UI-kit and pack fragments (`%{}` text fragments); it has no library
   resolver (item C6, "Drishti's fragments on the library mechanism"). A `use: core/record-card@1` cannot resolve here and cannot be
   proved without a build, so the 213 residual panels are NOT moved to `core/` fragments in this pass (see "Blocked on C6").
4. What could be done without a resolver and without a build was done: see "Converted".

## Inventory of the non-risk Sutras

| Pack | Sutras | Panels | `use:` | In place | In place, by kind |
|---|---|---|---|---|---|
| `core/compute-demo` | 1 | 7 | 2 | 5 | metric 2, line 2, kv 1 |
| `core/core` (generated) | 10 | 34 | 16 | 18 | table 12, kv 2, pivot 2, graph 1, scatter 1 |
| `core/gleif` | 7 | 47 | 29 | 18 | table 9, kv 7, status 2 |
| `trading` deal screens (generated) | 130 | 1047 | 1040 | 7 | kv 7 |
| `trading` derived pages | 5 | 37 | 15 | 22 | hbar 16, ladder 4, kv 2 |
| `market-data` (generated) | 21 | 86 | 59 | 27 | table 13, surface 5, kv 3, candlestick 2, metric 2, ladder 2 |
| `capital-markets/us-equities` | 8 | 42 | 31 | 11 | table 6, kv 5 |
| `capital-markets/funds` | 3 | 23 | 9 | 14 | metric 6, table 3, kv 2, hbar 2, tabs 1 |
| `economy/world-economy` | 13 | 46 | 39 | 7 | table 6, map 1 |
| `economy` (central-banks, gdelt, national-statistics, news-and-markets, oecd, official-news, world-development) | 14 | 59 | 39 | 20 | table 9, kv 7, hbar 2, line 1 |
| `genomics/genomics-showcase` (generated) | 7 | 7 | 0 | 7 | kv 7 |
| `genomics/protein-engineering` (generated) | 7 | 7 | 0 | 7 | kv 7 |
| examples/packs/retail-banking (generated) | 8 | 31 | 24 | 7 | table 4, ladder 3 |
| `examples/genomics` (generated) | 8 | 30 | 22 | 8 | table 8 |
| the finance example (hand-written tutorials) | 5 | 29 | 0 | 29 | links, kv, table, provenance, line, hbar, tabs, ladder, area, status |
| the logistics example (hand-written tutorial) | 1 | 6 | 0 | 6 | table, ladder, kv, provenance, line, links |
| **Total** | **248** | **1538** | **1325** | **213** | table 75, kv 57, hbar 23, ladder 11, metric 10, line 7, links 6, provenance 5, surface 5 |

Out of scope (move with R2): `market-risk` 10, `counterparty-risk` 10, and the risk example packs `climate-risk` 6, `liquidity-risk` 7,
`operational-risk` 8 (41 Sutras).

## Panel shape to fragment

| Shape in place | Fragment that covers it | Where it resolves today | Count |
|---|---|---|---|
| kv with an explicit column list (label, bind, fmt, tone) | `core/record-card` (`facts`) | Rachana library; not here (C6) | 57 |
| table with an explicit column list | `core/data-table` (`columns`) or `core/drill-table` | Rachana library; not here (C6) | 75 |
| hbar, line, ladder, candlestick, surface with extra options | `ranked-bars`, `series-line`, `cashflow-ladder` (UI kit, exact only); `finance/price-bars`, `finance/vol-surface` | UI kit only when exact; finance library not here | 23, 7, 11, 2, 5 |
| metric (a figure) | `core/key-figure`, `core/kpi-strip` | Rachana library; UI kit `key-figure` | 10 |
| provenance, links (footer) | `data-provenance`, `linked-entities`, `source-footer` | UI kit | 11 (all in the hand-written examples) |
| status field list | `core/status-badges` | Rachana library | 3 |
| tabs with `each` (fund views, fx-swap, irs) | none | gap G2 | 3 |
| choropleth map (jurisdiction) | UI kit `region-map` | UI kit; no library twin | 1 |
| tree table with `children` / highlight (lei parents, desk) | `core/tree-table` | Rachana library; not here (C6) | 3 |
| pivot over a table | `core/pivot-table` | Rachana library; not here (C6) | 2 |

## Gaps (a shape no fragment covers)

- G1 `core/record-card` heads a record with name, subtitle and status. The 57 plain record views (genomics showcase, us-equities,
  economy series pages) are a `kv` of columns with no head. Proposal: `core/record-list`, the same `facts` columns with no head.
  Not written: `record-card` with an empty head draws the same panel, so the gap is C6, not a missing fragment. Decide at the C6 review.
- G2 tabs over `each` (a list of views, each with its panels). No fragment in `core` or `finance`; three screens use it. Proposal:
  `core/tabbed-views`. Not written (needs a slot design the typed-fragment spec has to approve: a slot repeated per row).
- G3 a screen footer that reads the Sutra's provenance and links (`data-provenance`, `linked-entities`) is Drishti vocabulary with
  no library twin; keep it a Drishti pack fragment (it reads the screen, not data passed in).

## Converted in this pass

| Change | Sutras | Panels before | after |
|---|---|---|---|
| `fragments=True` for `retail-banking` and `genomics` example packs (generators): hbar, line, provenance and links panels become `ranked-bars`, `series-line`, `data-provenance`, `linked-entities` uses | 16 | 297 lines | 192 lines |
| Structured `doc:` fields on every non-risk Sutra (`tools/packgen/common/sutradoc.py`, called by the generators; the hand-written ones given it once) | 248 | | |
| Full `doc:` block on the six hand-written example Sutras that had none (finance 5, logistics 1) | 6 | | |

Intended differences: none in what a screen shows. The converted panels expand to the same panel (ids, titles, codes, keys, areas, rows,
columns and options unchanged); the generator's equivalence tests (`PackSutraNextEquivalence`, `SutraNextEquivalenceTest`) prove it
on the next build. The doc fields are additive.

## Blocked on C6 (the library resolver)

The 213 residual panels are bespoke bindings (explicit columns, links, tones, highlights). They become `core/record-card`,
`core/data-table`, `core/tree-table`, `core/pivot-table`, `core/key-figure` uses once Drishti resolves `libraries: [core@1]`; each
passes its columns in `with:` and so keeps its bindings. Then: the 57 kv and 75 table panels, about 1,000 lines. Until a build can
compare the compiled ViewModels, writing them is a guess.

## Needs building (nothing was run)

1. Regenerate-check: `python tools/packgen/banking/make_sutras.py --check` (needs the kernel jar) and the genomics and retail generators.
2. `DocumentedSutrasTest`, `PackSutraNextEquivalence`, `SutraNextEquivalenceTest`, the Rachana parse of the new `doc:` keys (the
   parser must accept `category`, `tags`, `use-when`, `avoid-when`, `data`, `example`, `see-also`, `keywords`; the schema `doc` definition is on
   `genui/kit-lite`).
3. `retail-banking` and `genomics` example packs loaded through `DRISHTI_PACKS_EXTRA_DIRS`: open one page of each.

## Status check on i8/develop (2026-11-28, increment 1)

Live tables and blotters: already on the one engine. `DrishtiLiveTables` (Rachana's live tables), `KindSnapshot`, `KindStream`, `KindBindings`,
`FormResults` and `DrishtiAuthoriser` serve `/api/v1/rdp/tables`; the console's `live_routes.py` and `live_results.py` only proxy and shape
pages; the one declaration is `modules/trading/blotters/trade.live.yaml` (a FORM's result is the same table without edits). No `LiveSet` class,
route or template is left, so there is no bespoke path to retire. The BI engine's `LiveTables` (dataset live tables) is a separate feature
(a dataset's in-memory table), not a result set; whether it should read through the same engine is a decision for the owner.

Non-risk screens on library fragments: the resolver (C6, `DrishtiLibraries`) is on the branch but marked "wip: unbuilt", and the 213 residual
panels cannot move one for one. A scan of the hand-written and generated non-risk Sutras (inline `table` and `kv` panels) by the keys they carry
beyond id, kind, title, area, rows and columns:

| Shape | Panels | What blocks the move |
|---|---|---|
| `kv`, nothing else | 29 | `core/record-card` forces a head (name) and draws a status panel; the headless record list is G1 |
| `table`, nothing else | 3 | none (these three are `core/data-table` uses) |
| `table` or `kv` with `key` (a function key) and/or `code` (a mnemonic) | about 70 | `core/data-table` and `core/record-card` take neither; they cannot be passed through `with:` |
| with `source` (a panel reading another kind), `empty`, `waits`, `when`, `totalLabel` beside `limit`, `pivot`, `where`, `span` | about 30 | no such parameter in the core fragments |

Needs the owner (or the Rachana lane): extend `core/data-table`, `core/record-card` (or add `core/record-list`) with `key`, `code`, `source`,
`empty`, `when` and `span` so the moves are faithful, or accept that those screens keep the inline panel (the compiled panel is then exactly
what the fragment would draw). Until C6 builds green and the fragments take those parameters, moving a panel changes what it shows.
