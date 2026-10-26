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
# The VaR explorer: value at risk for any set of trades

> **Archived: replaced by [VAR_GUIDE.md](../guides/VAR_GUIDE.md) (the explorer is its [section 3](../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen)).** On 2026-10-26 the VaR documents were consolidated into two (product owner): the reference [VAR_GUIDE.md](../guides/VAR_GUIDE.md) and the hands-on [tutorial 25](../tutorials/25-var-from-the-bank-to-a-trade.md). The section numbers this page cites are those of the documents before the consolidation; this is history.

Built in 2.8.0 (unreleased): the explorer screen of [VAR_ENGINE.md](VAR_ENGINE.md). This page is about the screen: how
to open it, its panels, its controls and its settings. **The complete guide** to VaR in Drishti (every measure with worked numbers, the
data flow, positions and portfolios, history and backtesting, the memory tiers and sizing, the computed kinds, the CLI and the API) is
[VAR_GUIDE.md](../guides/VAR_GUIDE.md); **F1** or the **?** on the explorer opens it. The hands-on walk-through is
[tutorial 25](../tutorials/25-var-from-the-bank-to-a-trade.md). The data contract (every input column, structure and answer) is
[VAR.md](VAR.md); the data model is [VAR_DATA_MODEL.md](VAR_DATA_MODEL.md). What-if pricing through a
pricer, intraday recomputes and the cluster's sharded store are later waves.

The explorer answers one question for any set of trades: *how much could this lose in a day, and where does that loss come from?* It
reads the stored P&L vectors (one per trade: its P&L under each of 509 historical scenario days) and adds them up. It never reprices.

## Contents

1. [Open it](#1-open-it)
2. [The screen](#2-the-screen)
3. [Drill down: bank, desk, book, position, trade](#3-drill-down-bank-desk-book-position-trade)
4. [The three tabs](#4-the-three-tabs)
5. [Ad-hoc scopes and baskets](#5-ad-hoc-scopes-and-baskets)
6. [Capture VaR](#6-capture-var)
7. [Portfolios and positions as pages](#7-portfolios-and-positions-as-pages)
8. [The scope grammar](#8-the-scope-grammar)
9. [The API](#9-the-api)
10. [Who sees what](#10-who-sees-what)
11. [Settings](#11-settings)
12. [The demo data](#12-the-demo-data)
13. [Troubleshooting](#13-troubleshooting)

## 1. Open it

Type a command and press Enter (or `<GO>`):

| Command | Opens |
|---|---|
| `VAR BANK <GO>` | the whole bank |
| `VAR DESK DESK-RATES <GO>` | a desk (`VAR DESK RATES` works too) |
| `VAR LE LE-NY <GO>`, `VAR DIV MARKETS <GO>`, `VAR BOOK BOOK-RATES-1 <GO>` | a legal entity, a division, a book |
| `VAR POS POS-BOOK-EQD-2~SEC-WARRANT-USD-01 <GO>` | a position (a security held in a book; an OTC trade's is `POS-<book>~<trade>`) |
| `VAR TRD MX-30000121 <GO>` | one trade |
| `VAR CPTY CP-SUMMIT <GO>` | every trade with a counterparty |
| `VAR PORT PF-USD-RATES <GO>` | a stored portfolio |
| `VAR SCOPE unwind <GO>` | your saved basket `unwind` (`VAR SCOPE asha/unwind` for one shared with you) |
| `VAR SCOPE DESK:DESK-RATES - TRD:MX-30000121 <GO>` | any scope written in the [grammar](#8-the-scope-grammar) |
| `VAR CAPTURE CAP-20260930-12 <GO>` | a capture, reopened exactly as it was taken |

`VAR VAR-RATES <GO>` (the retired stored record's id) opens the explorer at `DESK:RATES` and says so; `VAR VN-MGMT~DESK-FX` opens
`NODE:MGMT~DESK-FX`, and old links (`/v/var/VAR-RATES`) do the same. The page is `/var?scope=<scope>`; every scope you move to is a
browser history entry, so Back works.

## 2. The screen

Three columns, as the approved mock-up:

- **Scopes** (left): the hierarchy (Management: bank, division, desk, book; or Legal entity: bank, legal entity, desk, book) with each
  node's VaR, ES and a bar of its limit use (green, amber at 75%, red at 90%). A book opens to its positions (a LONG or SHORT chip, the
  quantity, VaR, ES, the component VaR in the book); a position opens to its trades. Below: *Ad-hoc scopes*, the stored portfolios and
  your baskets, and **+ New scope**.
- **The scope** (middle): the breadcrumb, six tiles (VaR 99% 1-day, ES 97.5%, the sum of the children's standalone VaR, the
  diversification, the limit use, the backtest exceptions over 250 days with the Basel light), and the tabs.
- **How this number was built** (right): the scope as resolved, the positions and trades included, those without a vector, those
  outside your entitlement, unknown ids, the vector batch and data version, the scenario set and its window, the method and rank rule,
  the ES rule, how components are allocated, the engine and its threads, your view, the **tier** the vector came from (a node's float64
  aggregate, the date's memory-mapped hot file, or memory: [VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file)),
  the members' hash, the time taken and whether it came from the cache. **Export CSV** (the scope's path), **Capture VaR**. The **?** in the
  status line opens the VaR guide.

Every panel says *computing…* while it waits and is painted as soon as its answer arrives; the status line says how long the click
took (*painted in 14 ms · server 2 ms, hit*). Moving to another scope never reloads the page.

## 3. Drill down: bank, desk, book, position, trade

Click any row of the tree, any part of the breadcrumb, or any row of the Contributions tab: every panel moves to that scope. The
children of the node you are on are fetched while you read it, so the next click is answered from memory.

- **A position** is a holding of one instrument in one book; its trades build or reduce it (a buy adds, a sell takes away). Its vector
  is the sum of its trades' vectors, so a long of 100 and a short of 40 of one instrument have the VaR of 60: the position's VaR is never
  the sum of its trades' VaRs.
- **A trade** has its own vector: its VaR is exact, and so is its contribution to its position, its book and any basket.

## 4. The three tabs

**P&L path** (509 scenarios): the distribution of the scope's scenario P&L with the VaR and ES lines (bins wholly beyond VaR in red);
the worst 25 scenarios sorted (the VaR scenario highlighted); the P&L by scenario date with the −VaR line; and the ranked table, 60 rows
a page (**Next page**), with the part that lost most on each scenario. A click on a bar or a row opens the **contributors on that date**:
which children lost most that day.

**VaR / ES history**: for a node of the hierarchy, its official end-of-day figures (section 6) over the last 250 business days: −VaR and
−ES lines over the day's hypothetical P&L, an exception (a loss beyond the previous day's VaR) as a red dot and a row chip, and the
backtest: the count, the Basel zone (green 0–4, amber 5–9, red 10+), Kupiec's and Christoffersen's p-values. Any other scope (a
position, a trade, a basket) replays today's members over the last 250 scenario days against today's VaR: in-sample, and said so.

**Contributions**: the scope's children (a node's child nodes, a book's positions, a position's trades, a basket's positions), **By
position** or **By trade**: standalone VaR, component VaR (they add up to the scope's VaR: Euler allocation on the five scenarios
around the VaR rank), its share, a bar, marginal VaR per million of market value, incremental VaR (the scope's VaR minus its VaR without
the row). A hedge has a negative component. Tick rows and **Add to basket** to start an ad-hoc scope from them.

**Captured results**: the captures of this scope (section 6).

## 5. Ad-hoc scopes and baskets

**+ New scope** opens the builder; the screen follows every change (the VaR is recomputed as you type, about 0.35 s after you stop):

- **Paste ids** (trades and positions, separated by commas, spaces or new lines) and **Add ids**: positions start with `POS-`.
- **Add rule**: a condition over the positions' columns, `CURRENCY == 'EUR' and DIRECTION == 'SHORT'`.
- **Combine**: pick any node, portfolio or basket and **+ union** or **− minus** it.
- **Add to basket** from rows ticked in the Contributions tab.
- Or type the scope directly (the [grammar](#8-the-scope-grammar)).

Overlaps never count twice: a position in two of the terms is in the scope once. Until you save it the scope is *ad-hoc · unsaved*.
**Save** with a name keeps it as a basket (a RULE when it is a single rule), private to you; *Share read-only with* lists users or roles
who may open it, each through their own entitlements. Saved baskets are in the content store (they follow you across the servers of a
cluster), versioned, and listed under *Ad-hoc scopes* as `★ name`. Open one with `VAR SCOPE <name> <GO>`.

## 6. Capture VaR

**Capture VaR** (the right column, or the Captured results tab with a note) stores an immutable snapshot of the scope as it is now:

- the numbers: VaR, ES, the rank rule, each member's component VaR, the worst 25 scenarios, and the full vector;
- the members: the trades the scope resolved to;
- the provenance: the scope as written and resolved, the business date, the data version of the vectors, the scenario set and its
  dates, the method, the engine, you, the time, your note.

A capture never changes: restate the batch, change the basket, and the capture still reopens with the numbers it had
(**Reopen**). **Compare with now** puts it beside the same scope computed on the newest date: VaR, ES, members added and removed,
the components that moved most; two captures of a scope compare the same way. **CSV** downloads it (the vector by scenario date, or
`?part=components`). Captures are listed per scope, newest first; `VAR CAPTURE <id> <GO>` reopens one. Each capture is an audit entry.

**Official end-of-day captures.** When a business date's data has landed (the load signal of the vectors, scenario set, hierarchy,
portfolios or trades), the server reloads the date and captures **every node** of both hierarchies itself, marked *official*: they
are the node history of the History tab, they are listed (*official*) under a node's Captured results for a viewer who sees every node,
and they are kept seven years. A restated date is captured again with the next result version; the earlier ones stay. Each run is
also published for other systems to the risk lake (`risk-results/var-official`, append-only). An administrator can run it for a date:
`POST /api/v1/var/official?asOf=2026-09-30`; `GET /api/v1/var/official?asOf=…` shows the date's record.

## 7. Portfolios and positions as pages

**Positions are Drishti's.** Nobody sends positions: when the server loads a business date it builds them from the date's trades by
the market-risk pack's `positions:` rules (configuration, `pack.yaml`): bonds, bills, shares and warrants by book and security,
exchange-traded futures and options by book and contract, everything else (swaps, FX forwards, OTC options) one position per trade.
Only live trades count (`NEW`, `VALIDATED`, `AMENDED`, not matured). The same trades and rules always give the same positions, and each
official capture records them. The demo date has 7,984 positions over 9,572 trades.

The `portfolio` and `position` kinds have pages of their own (Sutras). They store no figure: the VaR, ES and contributions on them are
the engine's, computed as the page opens, as you (your view of the nodes):

- `PORT PF-RATES-DESK <GO>`: the strip (positions, longs, shorts, gross and net market value, VaR, ES, limit use); the positions table
  (filter any column, **Pivot** tab), a click on a position opens it and its trades beside; the portfolio's P&L distribution; the
  component VaR by product, by currency and long against short; the top contributors; the definition. 28 demo portfolios: one
  HIERARCHY portfolio per desk and per legal entity and `PF-COMM-BOOK-1`; eight RULE portfolios (`PF-USD-RATES`, `PF-GOVT-BONDS`,
  `PF-LISTED`, `PF-OPTIONS` …); five BASKET portfolios (`PF-ASHA-UNWIND`, `PF-NETTED-FLOW`, `PF-PARTIAL-FILLS` …), overlapping on purpose.
- `POS POS-BOOK-EQD-2~SEC-WARRANT-USD-01 <GO>`: the instrument, direction, quantity, market value, the rule that made it, VaR, ES and
  component VaR in its book; its trades with their own VaR and their component of the position's (a long and a short of one instrument
  have components of opposite signs); its P&L distribution (the UI kit fragment `pnl-distribution@1`).
- `DESK DESK-RATES <GO>`: the desk's VaR and limit use in its strip, from the engine.

When the engine cannot answer (off, no vectors, your role may not see VaR) the pages show everything else and say why. The book's own
page (`portfolio-trades`, *all trades*) is unchanged.

## 8. The scope grammar

| Write | Means |
|---|---|
| `BANK` | the bank (the Management tree's root) |
| `DIV:MARKETS`, `DESK:RATES`, `BOOK:BOOK-RATES-1`, `NODE:MGMT~DESK-RATES` | a node of a hierarchy |
| `LE:LE-NY` (or `LE:NY`) | the trades booked by the legal entity (their `LEGAL_ENTITY`); when the Legal tree disagrees, a warning in the provenance |
| `POS:<id>,<id>` | positions (every trade of each) |
| `TRD:<id>,<id>` | trades |
| `CPTY:<id>,<id>` | every trade with those counterparties |
| `PORT:<id>` | a stored portfolio, by its definition |
| `BASKET:<owner>/<name>` | a saved basket |
| `CAPTURE:<id>` | a capture's member trades, on the date you look at |
| `WHERE:<rule>` | the positions whose columns meet the rule: `==`, `!=`, `<`, `<=`, `>`, `>=`, `in ('a', 'b')`, `'hedge' in TAGS`, `and`, `or`, `not` |
| `A + B`, `A - B`, `A & B`, `( … )` | union, difference, intersection; `&` binds first; operators stand between spaces |

A hyphen inside an id is part of the id (`DESK:RATES - TRD:MX-30000121`). Ids the date does not hold are listed under *Unknown ids*,
never silently dropped. Position, trade and counterparty ids may be typed in any case (`trd:mx-30000121`).

## 9. The API

Every answer is computed as the caller (a bearer token or the console's session), on the request's business date (`asOf=`, or the
`X-Drishti-As-Of` header), or the newest date with vectors when that is later.

```
GET  /api/v1/var/scope?scope=DESK:DESK-RATES               headline, ancestors, backtest summary, provenance
GET  /api/v1/var/scope/path?scope=…&page=0&size=60          histogram, worst 25, the 509 values, a page of the ranked table
GET  /api/v1/var/scope/history?scope=…                      250 days of VaR, ES, hypothetical P&L, exceptions, backtest
GET  /api/v1/var/scope/contributions?scope=…&by=child|node|position|trade|desk|book&scenario=2025-01-31
GET  /api/v1/var/tree?hierarchy=MGMT                        every node you see with its figures, and the stored portfolios
GET  /api/v1/var/status                                     the settings, the tiers (hot files open and on disk) and the loaded dates
POST /api/v1/var/reload                                     drop the loaded dates (administrators; after a new load)
GET  /api/v1/var/baskets          POST /api/v1/var/baskets {"name": "unwind", "scope": "POS:… + TRD:…", "sharedWith": ["risk"]}
GET  /api/v1/var/baskets/{owner}/{name}      DELETE /api/v1/var/baskets/{owner}/{name}
POST /api/v1/var/captures {"scope": "BASKET:asha/unwind", "note": "before the unwind"}
GET  /api/v1/var/captures?scope=…   GET /api/v1/var/captures/{id}   GET /api/v1/var/captures/{id}/csv?part=components
GET  /api/v1/var/compare?a=CAP-20260930-1&b=now
```

For example, with a token in `DRISHTI_TOKEN`:

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" "http://localhost:18480/api/v1/var/scope?scope=DESK:DESK-RATES" | python3 -m json.tool
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" -H "Content-Type: application/json" \
     -d '{"scope": "TRD:MX-30000121,MX-30000565", "note": "two swaps"}' http://localhost:18480/api/v1/var/captures
```

## 10. Who sees what

The engine reads as the server and answers as you:

- A role that may not open the `pnl-vector` kind sees no VaR at all (403).
- A role with no entry under `drishti.var.access` sees every node at **positions** level: numbers, children, positions, trades.
- `drishti.var.access.<role>[<node>]: positions | aggregate` restricts a role to some nodes. **aggregate**: the node's numbers and
  every node beneath it, never a position or a trade (the tree stops at books, *By position* is refused). A scope is computed over the
  trades you may see; the rest are excluded and counted (*Outside your view: 4 trades not included*).
- A scope naming positions, trades, counterparties, a rule or a BASKET portfolio may only name trades you see at positions level
  (otherwise a rule could probe single trades through aggregates): refused with 403. A masked counterparty reads `•••` in the trade
  rows, and `CPTY:` is refused to a role that sees it masked.
- Cached answers are keyed by the date, the data version, your view and the scope: two viewers with different rights never share one.
- Every headline answer, every capture, every basket saved or deleted, every reload and every official capture run is an audit entry
  (Admin → Audit): who, the scope, the date, the view, the VaR and ES, the members' hash, the time taken.

```yaml
# config/application.yaml (or -D on the command line)
drishti:
  var:
    access:
      division-head: { "[MGMT~DIV-MARKETS]": aggregate }     # brackets keep the node id whole
      rates-risk:    { "[MGMT~DESK-RATES]": positions }
```

## 11. Settings

`drishti.var.*` in the server's application.yaml:

| Setting | Default | Means |
|---|---|---|
| `drishti.var.enabled` | `true` (`DRISHTI_VAR_ENABLED`) | the engine and its API |
| `drishti.var.confidence`, `drishti.var.es-confidence` | 0.99, 0.975 | VaR's and ES's confidence |
| `drishti.var.rank-rule` | `kth-worst` | k = ceil(N (1 − a)): the 6th worst of 509; or `interpolated` |
| `drishti.var.component-window` | 5 | the scenarios around the VaR rank component VaR averages |
| `drishti.var.threads` | 0: every core (`DRISHTI_VAR_THREADS`) | the bounded pool vector sums run on |
| `drishti.var.memory-budget-mb` | 0: a quarter of the heap (`DRISHTI_VAR_MEMORY_MB`) | the most memory one date's vectors take (the float32 copy when mapped); a date that would exceed it is refused, never loaded in part |
| `drishti.var.hot-dates` | 2 | business dates kept open |
| `drishti.var.tier.*` | `mapped`, `<data folder>/var/hot`, 60 dates on disk | where a date's vectors live: a memory-mapped float32 hot file per date (`heap`: float64 on the heap); [VAR_GUIDE.md section 9](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file) |
| `drishti.var.history-days` | 250 | the history and backtest window |
| `drishti.var.kinds` | the market-risk pack's | the kinds the engine reads (vectors, hierarchy, scenario set, portfolios; positions are served as `position`) |
| `drishti.var.positions` | the live statuses, no rule | the position rules when no pack has a `positions:` section |
| `drishti.var.official.*` | on (`DRISHTI_VAR_OFFICIAL`), published, 7 years | the official end-of-day captures |

## 12. The demo data

`drishti.py lake demo` builds the market-risk pack's VaR tables with every other table (or alone:
`drishti.py lake demo --only market-risk/pnl-vector,market-risk/scenario-set,market-risk/risk-hierarchy,market-risk/portfolio`).
They come from `data/testdata/market-risk/<kind>/`, written by `tools/packgen/var/make_var_data.py` from the committed trades: a vector
for every live trade on the last business date (9,572), 88 nodes of two hierarchies, 28 portfolio definitions; 15.7 MB in all. The
vectors are synthetic (a correlated, fat-tailed factor market), not prices; the security ids on securities and listed trades are
synthetic too. The node history starts with the first official capture.

## 13. Troubleshooting

| You see | Why | Do |
|---|---|---|
| *no P&L vectors in any connector* | the lake has no `pnl-vector` table, or the market-risk pack is off | build the demo lake; enable the pack |
| *no connector keeps pnl-vector.PNL as a vector column* | the connector's layout lacks `vectors: [PNL]` | regenerate the pack (`tools/packgen/banking/make_packs.py`) or add it to your connector file |
| *… need N MB; the vector store's budget is M MB* (503, DRS-5204) | the date is larger than `drishti.var.memory-budget-mb` | raise the budget or the heap |
| *Rows left out* in the provenance (DRS-5201) | vectors of the wrong length, with a null or NaN, in another currency, or failed with a vector | fix the batch's rows and restate the date |
| *two P&L vectors on one business date* (422, DRS-5202) | a trade id twice in the date's batch | restate the date with one row per trade |
| a warning that the date before was answered (DRS-5203) | the date's rows come from two runs or scenario sets | restate the date as one run; the signal reloads it |
| *Not for your role* | your role may not open the vectors, or sees the scope in aggregate only | ask for the level you need |
| *Unknown ids* in the provenance | the ids are not in the date's batch (matured, cancelled, typed wrong) | check them; nothing else changes |
| the history says *Replay* | the scope is not a node, or you see the node in part | as designed: replay is in-sample |
| *the vectors of … were unloaded while this answer was computed* | the date was evicted while you waited | ask again; more in [VAR_GUIDE.md section 15](../guides/VAR_GUIDE.md#13-troubleshooting) |
