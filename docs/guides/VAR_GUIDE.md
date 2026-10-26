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
# Value at risk in Drishti: the guide

Built in 2.8.0. This is the complete guide to Drishti's VaR: what every number means and how it is computed, where the data comes from
and how it flows, how positions and portfolios are formed, how to name any set of trades, the explorer screen by screen, captures,
history and backtesting, how the engine keeps a bank's vectors in memory and on local disk, the computed kinds, who sees what, the
command line and the API, and what to do when something goes wrong.

Shorter companions: the explorer's own page, [VAR_EXPLORER.md](VAR_EXPLORER.md) (the screen, its commands and settings); the hands-on
[tutorial 25](../tutorials/25-var-from-the-bank-to-a-trade.md) (twelve steps on the test data). The reference behind this guide is the
data contract [VAR.md](../contracts/VAR.md) (every column, formula, answer and error code); the design is
[VAR_ENGINE.md](../architecture/VAR_ENGINE.md) and [VAR_DATA_MODEL.md](../architecture/VAR_DATA_MODEL.md). In the console, **F1** on the
explorer (or its **?**) opens this guide.

Every number below that is not a hand-made example was read from a server over the committed test data: the market-risk pack's VaR
inputs of **2026-09-30** (`data/testdata/market-risk/`: 9,572 live trades, 7,984 derived positions, 88 hierarchy nodes, 28 portfolio
definitions, 509 scenario days), built into a lake with `drishti.py lake demo --as-of data`. The vectors are synthetic (a correlated,
fat-tailed factor market), not prices.

## Contents

1. [The question VaR answers](#1-the-question-var-answers)
2. [The measures, with worked numbers](#2-the-measures-with-worked-numbers)
3. [The data flow: from the risk batch to your screen](#3-the-data-flow-from-the-risk-batch-to-your-screen)
4. [Positions and the position rules](#4-positions-and-the-position-rules)
5. [Portfolios, baskets and ad-hoc scopes](#5-portfolios-baskets-and-ad-hoc-scopes)
6. [The explorer, screen by screen](#6-the-explorer-screen-by-screen)
7. [Captures and compare](#7-captures-and-compare)
8. [History and backtesting](#8-history-and-backtesting)
9. [The memory model: heap, hot files and disk](#9-the-memory-model-heap-hot-files-and-disk)
10. [VaR as a kind: var-result and var-whatif](#10-var-as-a-kind-var-result-and-var-whatif)
11. [Who sees what](#11-who-sees-what)
12. [The command line](#12-the-command-line)
13. [The API](#13-the-api)
14. [Settings](#14-settings)
15. [Troubleshooting](#15-troubleshooting)

## 1. The question VaR answers

*How much could this set of trades lose in one day, at 99% confidence, and where does that loss come from?*

Drishti answers it for **any** set of trades: the bank, a legal entity, a desk, a book, a position, one trade, a stored portfolio, a
rule (*every short EUR position*), a basket you picked, or any union, difference or intersection of these. It never reprices anything.
An upstream risk batch values every live trade under each of **509 historical scenario days** (the market moves of the last two years,
applied to today's market), and delivers, per trade, its **P&L vector**: 509 numbers, the P&L the trade would make if the market moved as
it did on each of those days. Drishti adds vectors: the vector of a set of trades is the sum of its trades' vectors, element by element,
and every measure is read from that one vector of 509 numbers.

Three consequences matter to every reader:

- **Longs and shorts net.** A long of 100 and a short of 40 of one instrument have the VaR of a long 60: the vectors cancel before the
  quantile is taken. A desk's VaR is never the sum of its trades' VaRs.
- **Overlaps count once.** A trade in two terms of a scope (a desk and a portfolio holding part of it) is in the set once.
- **Every number is reproducible.** An answer names the vector table's data version, the batch id, the scenario set and the position
  rules it used; a capture keeps the vector itself.

## 2. The measures, with worked numbers

The contract's formulas are in [VAR.md section 6](../contracts/VAR.md#6-the-computation). In words, with the scope's vector `v` (N
values, gains positive) and its scenarios sorted worst first (equal P&Ls: the older scenario first):

| Measure | Definition | At N = 509 |
|---|---|---|
| **VaR(α)** | minus the k-th worst P&L, k = ⌈N (1 − α)⌉ | α = 99%: the **6th worst** |
| **ES(α)** (expected shortfall) | minus the mean of the k worst | α = 97.5%: the **mean of the 13 worst** |
| **Standalone VaR** of a part | the VaR of the part alone (its own sort) | |
| **Component VaR** of a part | minus the part's mean P&L over the scope's scenarios ranked k − 2 to k + 2, scaled so the parts add up to the scope's VaR | ranks 4 to 8 |
| **Component ES** | minus the part's mean over the scope's 13 worst scenarios (adds up to ES exactly) | |
| **Incremental VaR** | the scope's VaR minus its VaR without the part | |
| **Marginal VaR** | component VaR ÷ \|size\| × 1,000,000 (size: a position's market value, a trade's PV) | per million |
| **Diversification** | Σ standalone VaR of the parts − the scope's VaR | |

VaR and ES are reported **positive for a loss**. A component is signed: a hedge has a negative component, and an incremental VaR below
zero means *taking this out would raise the VaR*.

### 2.1 By hand: ten scenarios

Real vectors have 509 values; here N = 10, so every step can be checked by hand, with α = 80% for both measures (k = ⌈10 × 0.2⌉ = 2) and
a component window of 3. One book holds two positions: `P1` (trades `T1` long, `T2` short, one instrument) and `P2` (trade `T3`).

| Vector | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|---|
| `T1` | 5 | −8 | 3 | −12 | 6 | −2 | 4 | −5 | 7 | 1 |
| `T2` | −2 | 3 | −1 | 4 | −3 | 1 | −2 | 2 | −3 | 0 |
| `T3` = `P2` | −4 | 2 | −6 | 1 | −9 | 3 | −1 | 0 | 2 | −7 |
| `P1` = T1 + T2 | 3 | −5 | 2 | −8 | 3 | −1 | 2 | −3 | 4 | 1 |
| **Book** = P1 + P2 | −1 | −3 | −4 | **−7** | **−6** | 2 | 1 | −3 | 6 | **−6** |

The book sorted worst first: scenario 3 (−7), 4 (−6), 9 (−6, after 4: the older first), 2 (−4), 1 (−3), 7 (−3), 0 (−1), 6 (1), 5 (2), 8 (6).

| Figure | Computation | Value |
|---|---|---|
| VaR (k = 2) | minus the 2nd worst: −v[4] | **6** |
| ES (k = 2) | −(−7 − 6) / 2 | **6.5** |
| the component tail (ranks 1 to 3) | scenarios 3, 4, 9 | |
| smoothed VaR | −(−7 − 6 − 6) / 3 | 6.333 |
| scale c | 6 / 6.333 | 0.9474 |
| component VaR of P1 | −(−8 + 3 + 1) / 3 × c | **1.2632** |
| component VaR of P2 | −(1 − 9 − 7) / 3 × c | **4.7368** |
| their sum | 1.2632 + 4.7368 | **6 = the VaR** |
| component ES of P1, P2 | −(−8 + 3) / 2 = 2.5; −(1 − 9) / 2 = 4 | 2.5 + 4 = **6.5 = the ES** |
| standalone VaR of P1, P2 | P1's 2nd worst is −5; P2's is −7 | 5, 7 |
| diversification | 5 + 7 − 6 | **6** |
| incremental VaR of P1 | VaR(book) − VaR(P2) = 6 − 7 | **−1**: a hedge |
| incremental VaR of P2 | 6 − VaR(P1) = 6 − 5 | **1** |
| P1's trades | component of T1 = −(−12 + 6 + 1)/3 × c = 1.5789; of T2 = −(4 − 3 + 0)/3 × c = −0.3158 | they add up to P1's 1.2632 |

Take the short trade out (`BOOK:BOOK-X - TRD:T2`): the vector becomes T1 + T3 = (1, −6, −3, **−11**, −3, 1, 3, −5, 9, **−6**), so VaR =
**6** (unchanged: T2's incremental VaR is 0) but ES = −(−11 − 6)/2 = **8.5** (up from 6.5): the short hedged the worst day, which ES sees
and VaR at this rank does not. The engine's Python twin (`drishti_compute.var.measures`) and the Java engine (`VarMath`) are tested on
exactly these numbers.

### 2.2 On the test data

The bank on 2026-09-30 (`drishti.py var show BANK`):

```text
VAR BANK  ·  Drishti Bank  ·  node  ·  2026-09-30
  VaR 99%   112,035,906 USD   (the 6th worst of 509)
  ES 97.5%   160,132,107 USD   (mean of the 13 worst)
  limit      150,000,000 USD   use 74.7%
  parts      2 division   standalone sum 130,105,348   diversification 18,069,442
  backtest   amber  5 exceptions in 249 days   Kupiec p 0.160   Christoffersen p 0.076
  9572 trades in 7984 positions
  pnl-vector · batch B-2026-09-30-01 of 2026-09-30 · data version risk-store=dc731161ff617ac4
  node aggregate (float64) · historical, equal weights, 1-day, k = 6th worst of 509 · 1 ms (hit)
```

Read it as: on the 6th worst of the 509 historical days the whole bank would lose 112.0 million USD; the 13 worst days lose 160.1 million
on average; the two divisions alone add up to 130.1 million, so holding them together saves 18.1 million (diversification); the VaR uses
74.7% of the bank's 150 million limit.

The ten desks (the VaR column is the engine's on 2026-09-30, VAR.md section 14.2):

| Desk | VaR | Live trades |
|---|---|---|
| COMM | 17,523,118 | 2,141 |
| CREDIT | 130,670,864 | 943 |
| EQD | 35,827,767 | 1,201 |
| FI | 33,274,561 | 1,013 |
| FX | 24,060,533 | 1,164 |
| INFL | 24,933,130 | 314 |
| MM | 15,446,375 | 424 |
| RATES | 74,633,680 | 1,487 |
| SFT | 1,536,322 | 611 |
| STRUCT | 9,987,431 | 274 |

Their VaRs add up to 367.9 million; the bank's is 112.0 million. The credit desk alone has a larger VaR than the bank: other desks
gain on the credit desk's worst days.

**Components add up.** The rates desk by book (`drishti.py var parts DESK:RATES`):

```text
DESK:RATES  by book  VaR 74,633,680  ES 75,377,415  sum of components 74,633,680
Part          Standalone VaR  Component VaR  % of VaR  Incremental VaR  Component ES
------------  --------------  -------------  --------  ---------------  ------------
BOOK-RATES-1      33,153,425     30,612,282      41.0       -5,131,638    10,403,265
BOOK-RATES-2      30,872,370     25,280,825      33.9       21,654,002    20,263,766
BOOK-RATES-3      61,499,103     18,740,573      25.1       26,690,191    44,710,384
```

The components add up to the desk's VaR to the dollar. `BOOK-RATES-3` has the largest VaR alone (61.5 million) but the smallest share of
the desk's (18.7 million): its worst days are not the desk's. `BOOK-RATES-1`'s incremental VaR is **negative** (−5.1 million): take the
book out and the desk's VaR rises, so at the margin it hedges the other two, although its component is the largest.

**One trade.** `DESK:RATES - TRD:MX-30000121` has VaR **74,720,890**: the desk without that one swap has a VaR 87,210 USD higher, so the
swap's incremental VaR in the desk is −87,210.

**A position nets its trades.** `POS:POS-BOOK-EQD-2~SEC-WARRANT-USD-01` (nine trades of one equity warrant in `BOOK-EQD-2`) has a VaR of
**67,209**; its nine trades alone add up to 111,930: the buys and sells net (diversification 44,721).

**ES and VaR are different questions.** A scope's ES at 97.5% can be *below* its VaR at 99% (the basket of the rates desk's shorts in
section 7: VaR 282.4 million, ES 271.5 million): the 13 worst days at 97.5% average less than the 6th worst at 99% when the tail is
short. Both are shown; neither replaces the other.

## 3. The data flow: from the risk batch to your screen

```
 upstream risk batch (nightly)                 the risk lake (Delta)                     Drishti
 ─────────────────────────────                 ─────────────────────                     ───────
 values every live trade × 509 scenarios ───►  risk/pnl-vector/business_date=D/      ─┐
 the scenario days of the set ─────────────►  risk/scenario-set/business_date=D/     ─┼──► the VaR engine loads D:
 the hierarchies (MGMT, LEGAL) and limits ──►  risk/risk-hierarchy/business_date=D/   ─┤      checks every row, derives positions,
 portfolio definitions ────────────────────►  risk/portfolio/business_date=D/        ─┘      writes D's hot file, maps it
 POST /api/v1/packs/market-risk/loads ───────────────────────────────────────────────────►  the load signal: D is reloaded and
                                                                                            captured officially (every node)
                                               risk-results/var-official/  ◄──────────────  the official figures, appended
```

1. **The inputs** are four Delta tables of the market-risk pack's connector `risk-store`, one partition per business date: one row per
   live trade with its 509-value `PNL` vector (a `list<double>` column) and the trade's terms as the batch priced them (legal entity,
   desk, book, product, currency, counterparty, signed quantity, PV, status, maturity); one scenario set (`HS-509`: the 509 scenario
   days, oldest first); the hierarchy nodes with their books and VaR limits; the portfolio definitions. The columns are in
   [VAR.md section 3](../contracts/VAR.md#3-inputs-from-upstream-engines). Vectors are kept seven years (Delta log and removed files).
2. **The load signal.** When the batch has written a date it posts `POST /api/v1/packs/market-risk/loads` (or `drishti.py data landed`)
   for `pnl-vector`, `scenario-set`, `risk-hierarchy` or `portfolio`. The engine drops that date (so a restated date is never stale) and
   captures it officially in the background. A **new** date needs no signal: every request asks for the newest date with vectors.
3. **The load** checks every row (a vector of the wrong length, with a null or NaN, in another currency, or a failed row carrying a
   vector is rejected, `DRS-5201`, counted and listed; a trade not live is left out), refuses a date with a trade twice (`DRS-5202`) or
   from two runs (`DRS-5203`: the date before is answered meanwhile, with a warning), derives the positions (section 4), sorts the trades
   into hierarchy order, sums every book and node in float64, and writes the date's hot file (section 9).
4. **Restatements.** A batch that rewrites a date (`RESTATEMENT` + 1, a new `BATCH_ID`) changes the vector table's data version: the
   signal reloads the date, a new hot file is built (its name is the digest of the new inputs) and the official capture is taken again
   with the next **result version**; the earlier one is kept.
5. **Official end-of-day captures.** For each date the server captures **every node of both hierarchies** (88 in the test data) as the
   official record: the history of section 8 reads them. Each run is also **published** to `risk-results/var-official` (a Delta table,
   append-only, seven years): one row per node, date and result version, for other systems (`VAR`, `ES`, `HYPO_PNL`, `LIMIT_USE`,
   `EXCEPTIONS_250`, `TRAFFIC_LIGHT` …, [VAR.md section 9.6](../contracts/VAR.md#96-captures)). No engine reads it back. The rates desk's
   official record on 2026-09-30:

```json
{"captureId": "CAP-20260930-72", "hierarchy": "MGMT", "level": "desk", "name": "Rates · Swaps and options", "parent": "MGMT~DIV-MARKETS",
 "var": 74633680, "es": 75377415, "hypoPnl": -2066043, "limit": 100000000, "limitUse": 0.7463368, "sumStandalone": 125524898,
 "diversification": 50891218, "trades": 1487, "positions": 1349, "exceptions250": 0, "light": "green"}
```

## 4. Positions and the position rules

Nobody sends positions: Drishti **derives** them each time it loads a date, from the date's trades and the market-risk pack's
`positions:` rules (configuration, never code):

```yaml
positions:
  live-statuses: [NEW, VALIDATED, AMENDED]
  rules:
    - name: securities                  # bonds, notes, bills, shares and warrants: by book and security
      groups: [Fixed income]
      types: [EQ_CASH, WARRANT, CONVERTIBLE_BOND, INFL_LINKED_BOND, T_BILL, COMMERCIAL_PAPER, CERT_DEPOSIT]
      key: [SECURITY_ID, ISIN, TICKER]
    - name: listed                      # exchange-traded futures and options: by book and contract
      types: [BOND_FUTURE, STIR_FUTURE, STIR_FUT_OPT, EQ_FUTURE_SINGLE, EQ_INDEX_FUTURE, EQ_OPTION_LISTED, EQ_INDEX_OPTION, CMD_FUTURE,
              CMD_FUT_OPTION, ComFut, EMISSIONS_FUTURE]
      key: [SECURITY_ID, CONTRACT]
    - name: otc                         # everything else: one position per trade
```

| Rule | A position is | Its id |
|---|---|---|
| **live** | only trades with a live status (`NEW`, `VALIDATED`, `AMENDED`; a row with no status counts) and not matured before the date | |
| **securities** | every trade of one book in one security (the first of `SECURITY_ID`, `ISIN`, `TICKER` the trade carries) | `POS-<book>~<security>` |
| **listed** | every trade of one book in one exchange contract (`SECURITY_ID`, `CONTRACT`) | `POS-<book>~<contract>` |
| **otc** | one trade (a swap, an FX forward, an OTC option; a security without a key too) | `POS-<book>~<trade>` |

The first rule whose `groups` hold the trade's `PRODUCT_GROUP` or whose `types` hold its `PRODUCT_TYPE` takes it. The **quantity is
signed** (bought positive, sold negative), so a position's quantity is the net of its trades; its direction is `LONG`, `SHORT` or
`FLAT`; `POSITION_TYPE` says `fungible` (several trades) or `single-trade`. On 2026-09-30 the rules give **7,984 positions**: 359
securities, 212 listed, 7,413 OTC; 476 are fungible and 111 net buys against sells. Example: `POS-BOOK-EQD-2~SEC-WARRANT-USD-01` is
nine trades of one warrant in `BOOK-EQD-2`, with a VaR of 67,209 against 111,930 for the trades alone.

The same trades and rules always give the same positions, and every official capture records them (each position with its trades, the
rules' text and digest), so an official figure can be recomputed years later. The positions are served as the `position` kind
(`POS <id> <GO>`) by the `var-positions` connector, with no figure stored on them: their page shows the engine's, computed as it opens.

## 5. Portfolios, baskets and ad-hoc scopes

### 5.1 Stored portfolios

A **portfolio** (`PORT <id> <GO>`) is a definition, an input of the batch; its figures are always the engine's:

| Type | Definition | Becomes the scope | Test data |
|---|---|---|---|
| `HIERARCHY` | a node of a hierarchy | `NODE:<node>` | `PF-RATES-DESK` … one per desk and legal entity, `PF-COMM-BOOK-1` |
| `RULE` | a condition on the positions' columns | `WHERE:<rule>` | `PF-USD-RATES`, `PF-EUR-ALL`, `PF-SHORT-RISK`, `PF-GOVT-BONDS`, `PF-LISTED`, `PF-OPTIONS`, `PF-FX-CROSSES`, `PF-LARGE-LONGS` |
| `BASKET` | positions and trades someone picked | `POS:… + TRD:…` | `PF-ASHA-UNWIND`, `PF-NETTED-FLOW`, `PF-HEDGE-PAIRS`, `PF-TOP-SECURITIES`, `PF-PARTIAL-FILLS` |

`PORT:PF-USD-RATES` (*USD rates exposure*, a rule): 918 trades in 563 positions, VaR 28,968,862, 69.0% of its 42 million limit.

### 5.2 The scope grammar

Any set of trades is written as text. The same text is the explorer's address (`/var?scope=…`), the API's `scope` parameter, the
`var-result` kind's id and a basket's definition.

| Write | Means |
|---|---|
| `BANK` | the bank (the Management tree's root) |
| `DIV:MARKETS`, `DESK:RATES`, `BOOK:BOOK-RATES-1`, `NODE:MGMT~DESK-RATES` | a node (`DESK:RATES` and `DESK:DESK-RATES` are the same) |
| `LE:LE-NY` (or `LE:NY`) | the trades booked by a legal entity (their `LEGAL_ENTITY`); a disagreement with the Legal tree is a warning |
| `POS:<id>,<id>` | positions, every trade of each |
| `TRD:<id>,<id>` | single trades |
| `CPTY:<id>,<id>` | every trade with those counterparties |
| `PORT:<id>` | a stored portfolio |
| `BASKET:<owner>/<name>` | a saved basket |
| `CAPTURE:<id>` | a capture's member trades, on the date you look at |
| `WHERE:<rule>` | the positions whose columns meet the rule |
| `A + B`, `A - B`, `A & B`, `( … )` | union, difference, intersection; `&` binds first; operators stand between spaces |

The rule language: `==`, `!=`, `<`, `<=`, `>`, `>=`, `in ('a', 'b')`, `'x' in TAGS`, `and`, `or`, `not`, parentheses; text in single
quotes; any position column (`PRODUCT_GROUP`, `CURRENCY`, `DIRECTION`, `MARKET_VALUE`, `QUANTITY`, `DESK`, `TT_BOOK`, `TAGS`,
`POSITION_RULE` …). A field the position does not have compares unequal. The full grammar is [VAR.md section 8](../contracts/VAR.md#8-scopes).

Examples on the test data:

| Scope | VaR | Trades |
|---|---|---|
| `BANK` | 112,035,906 | 9,572 |
| `LE:NY` | 144,267,105 | 6,008 |
| `DESK:RATES` | 74,633,680 | 1,487 |
| `BANK - DESK:CREDIT` | 76,607,919 | 8,629 |
| `DESK:RATES - TRD:MX-30000121` | 74,720,890 | 1,486 |
| `PORT:PF-USD-RATES` | 28,968,862 | 918 |
| `WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'` | 133,970,477 | 700 |
| `DESK:RATES & WHERE:DIRECTION == 'SHORT'` (saved as `BASKET:rita/rates-short`) | 282,428,035 | 800 |
| `POS:POS-BOOK-EQD-2~SEC-WARRANT-USD-01` | 67,209 | 9 |

The rules that apply when a scope resolves: an id the date does not hold is listed under *unknown*, never silently dropped (the answer
is still 200); a trade in two terms counts once; `WHERE:` and `POS:` take whole positions, `TRD:` single trades (a position held in part
is shown *partial*); ids of positions, trades and counterparties may be typed in any case; `PORT`, `BASKET` and `CAPTURE` may refer to
each other at most 8 deep. A scope that does not parse answers `400 DRS-5001` with the reason.

### 5.3 Baskets

A **basket** is a scope you save under a name (explorer: **+ New scope**, then **Save**; CLI: `drishti.py var basket`; API: `POST
/api/v1/var/baskets`). It is private unless you share it read-only with users or roles (each reader sees it through their own
entitlements), versioned in the content store (it follows you across a cluster), and opened as `BASKET:<owner>/<name>` or `VAR SCOPE
<name> <GO>`. A basket of nodes and rules is **dynamic** (what matches on the date you look at); one of ids is **static** (a later date may
not hold them: *unknown*).

## 6. The explorer, screen by screen

`VAR <word> <id> <GO>` opens it: `VAR BANK`, `VAR DESK DESK-RATES`, `VAR LE LE-NY`, `VAR POS <id>`, `VAR TRD <id>`, `VAR PORT <id>`,
`VAR SCOPE <any scope or basket name>`, `VAR CAPTURE <id>`. Every move to another scope repaints the panels in place, never reloading the
page, and is a browser history entry (Back works). [VAR_EXPLORER.md](VAR_EXPLORER.md) lists every control.

**The three columns.** On the left the scopes: the hierarchy (Management or Legal entity) with each node's VaR, ES and a bar of its limit
use (amber at 75%, red at 90%), then your ad-hoc scopes, the stored portfolios and baskets. In the middle the scope: the breadcrumb, six
tiles (VaR 99% 1-day, ES 97.5%, the sum of standalone VaRs of the children, the diversification, the limit use, the backtest with its
light) and the tabs. On the right *How this number was built*.

![The explorer on the rates desk: the hierarchy with each node's VaR and limit bar, the six tiles, the P&L path, and how the number was built](img/var/01-explorer-desk.jpg)

**P&L path.** The distribution of the scope's 509 scenario P&Ls with the VaR and ES lines, the 25 worst sorted (the VaR scenario
highlighted), the P&L by scenario date with the −VaR line, and the ranked table, 60 rows a page, naming the part that lost most on each
day. A click on a day opens **the contributors on that date**: which children lost what.

![A scenario day's contributors: the rates desk's worst day and the books that lost most on it](img/var/02-scenario-contributors.jpg)

The ranked table from the command line (`drishti.py var path DESK:RATES --size 8`): rank 6 is the VaR scenario.

```text
DESK:RATES  VaR 74,633,680 (rank 6)  ES 75,377,415 (worst 13)
Rank  Scenario day  P&L           Lost most
----  ------------  ------------  -------------------------
1       2025-12-22  -119,535,289  BOOK-RATES-3 -128,077,862
2       2026-01-02   -93,752,610   BOOK-RATES-3 -75,146,021
3       2025-06-03   -85,318,588   BOOK-RATES-3 -77,022,026
4       2025-01-31   -83,146,544  BOOK-RATES-1 -127,936,055
5       2025-01-24   -76,715,667   BOOK-RATES-3 -61,161,962
6       2025-12-24   -74,633,680   BOOK-RATES-3 -81,640,585
7       2026-01-12   -71,889,167   BOOK-RATES-1 -28,904,703
8       2026-03-04   -66,957,357   BOOK-RATES-3 -39,177,971
```

**VaR / ES history** (section 8): the lines over 250 days, exceptions as red dots, the backtest.

![The history tab: VaR and ES over the replayed scenario days, the P&L, exceptions and the traffic light with Kupiec's and Christoffersen's tests](img/var/03-history.jpg)

**Contributions.** The scope's children: a node's child nodes, a book's positions, a position's trades, a basket's positions. Standalone,
component (with its share and a bar), incremental and marginal VaR, component ES; **By position** or **By trade**. Tick rows and **Add to
basket** to start an ad-hoc scope.

![The rates desk's contributions: three books, their components adding up to the desk's VaR](img/var/04-contributions.jpg)

A book opens to its positions, each with a LONG or SHORT chip, its quantity, its VaR and its component in the book:

![BOOK-EQD-2's positions: options, variance swaps and futures with their standalone and component VaR](img/var/05-book-positions.jpg)

**+ New scope.** Type a scope, paste ids, add a rule, combine nodes, portfolios and baskets with **+ union** and **− minus**: the VaR is
recomputed as you type. Until you save it the scope is *ad-hoc · unsaved*.

![The new-scope builder: the rates desk's short USD positions, computed as the rule is typed](img/var/06-builder-rule.jpg)

**How this number was built.** The scope as resolved, the trades and positions counted, those outside your entitlement, unknown ids, the
batch and data version, the scenario set and window, the method, the ES rule, the components' allocation, the engine and its threads,
your view, **the tier** the vector came from (section 9), the members' hash, the time and whether the cache answered.

![How this number was built: the rates desk without one book, from the hot file](img/var/08-provenance.jpg)

**The portfolio and position pages.** `PORT <id> <GO>` and `POS <id> <GO>` are pages of their own (Sutras): the engine's figures are
added as they open, as you. A portfolio lists its positions (a click opens one and its trades beside), its P&L distribution, the
component VaR by product, currency and direction, the top contributors and its definition; a position shows its trades with their own
VaR and their component of the position's.

![PORT PF-RATES-DESK: the positions, a position's trades beside, the figures computed as the page opened](img/var/09-portfolio-page.jpg)

![POS POS-BOOK-EQD-2~SEC-WARRANT-USD-01: nine trades of one warrant, longs and shorts netting](img/var/10-position-page.jpg)

**On a phone** the three columns stack, with no sideways scrolling:

![The explorer on a 390 px phone](img/var/11-phone.jpg)

## 7. Captures and compare

**Capture VaR** (the right column or the *Captured results* tab, with a note) stores an immutable record of the answer: VaR, ES, each
part's component, the 25 worst scenarios, the full vector (whole dollars), the member trades, and the provenance (scope as written and
resolved, date, data version, scenario set, method, engine, you, the time, your note). It never changes: restate the batch, change the
basket, and it still reopens with its numbers. **Compare with now** puts it beside the same scope on the newest date: VaR and ES change,
members added and removed, the components that moved most. **CSV** downloads the vector or, with `?part=components`, the components.

![A capture of PORT:PF-USD-RATES compared with now](img/var/07-capture-compare.jpg)

From the command line, a basket saved, captured and compared (real output):

```text
$ drishti.py var basket rates-short "DESK:RATES & WHERE:DIRECTION == 'SHORT'" --title 'Rates shorts'
{ "id": "rita/rates-short", "name": "Rates shorts", "type": "BASKET", "scope": "DESK:RATES & WHERE:DIRECTION == 'SHORT'",
  "owner": "rita", "sharedWith": [], "version": 1, ... }
$ drishti.py var capture BASKET:rita/rates-short --note 'before the unwind'
{ "id": "CAP-20260930-89", "capturedAt": "2026-10-26T05:12:54.392356455Z", "var": 282428035, "es": 271474611, "memberCount": 800 }
$ drishti.py var compare CAP-20260930-89
{ "a": { "id": "CAP-20260930-89", "label": "Rates shorts", "var": 282428035, "es": 271474611, "memberCount": 800, ... },
  "b": { "id": "now", "var": 282428035, "es": 271474611, "memberCount": 800, ... },
  "varChange": 0.0, "esChange": 0.0, "membersAdded": [], "membersRemoved": [],
  "components": [ { "key": "POS-BOOK-RATES-2~MX-30000951", "label": "Forward rate agreement CHF 2027-08", "a": 2783132, "b": 2783132,
                    "change": 0.0 }, ... ] }
```

The **official** captures of section 3 are captures too (owner `drishti`, marked *official*), listed under a node's *Captured results*
for a viewer who sees every node, and the source of the node history.

## 8. History and backtesting

The *VaR / ES history* tab and `GET /api/v1/var/scope/history` answer in one of two modes:

| Mode | When | Rows | Backtest |
|---|---|---|---|
| **official** | the scope is one node, you see every node, and the node has official records on more than one day | the node's official figures, a row per date (the latest result version), at most 250 | each day's hypothetical P&L against the **previous** day's VaR |
| **replay** | anything else | today's members over the last 250 scenario days | each day's scenario P&L against **today's** VaR: in-sample, labelled *not a regulatory backtest* |

A day is an **exception** when its P&L is a loss larger than the VaR it is tested against. Over the days:

- **The Basel traffic light**: green for up to 4 exceptions in 250 days, amber 5 to 9, red 10 or more. With fewer days the limits scale
  (`round(4 × days/250)`, `round(9 × days/250)`): with 4 days one exception is already red, below 14 days one is red, below 32 one is
  amber. The screens show the light *with its days*; it is not a regulatory zone before 250.
- **Kupiec's test** (proportion of failures): is the share of exceptions consistent with 1%? A p-value below 0.05 says no.
- **Christoffersen's test** (independence): do exceptions cluster (an exception more likely the day after one)? A p-value below 0.05
  says they do.

The FX desk replayed (`drishti.py var history DESK:FX`, the last rows):

```text
history (replay): today's members over the last 250 scenario days against today's VaR: in-sample, not a regulatory backtest
  green  3 exceptions in 249 days   Kupiec LR 0.099 (p 0.753)   Christoffersen LR 0.073 (p 0.786)
Date        VaR         ES          P&L          Exception
----------  ----------  ----------  -----------  ---------
2026-09-22  24,060,533  25,240,488   31,198,624
2026-09-23  24,060,533  25,240,488   11,195,465
2026-09-29  24,060,533  25,240,488     -818,954
```

Three exceptions in 249 days is fewer than the 2.5 expected: green, and neither test rejects. The bank replayed shows 5 exceptions
(amber; Kupiec p 0.160, Christoffersen p 0.076). The official mode begins with the second official date: each day's official VaR, ES and
hypothetical P&L (the node's P&L under the newest scenario day of its date).

## 9. The memory model: heap, hot files and disk

A bank's vectors are large: 509 values per trade, 4,072 bytes in float64, **4.07 GB a day for a million trades**. Drishti keeps them in
three tiers ([VAR.md section 7.3](../contracts/VAR.md#73-the-tiered-memory-and-the-hot-file-as-built-280)):

| Tier | Holds | Precision |
|---|---|---|
| **The lake** (Delta) | every trade's vector, every date, seven years | float64, as delivered |
| **The hot file** of each loaded date, on local disk, memory-mapped | one row per **position** in hierarchy order, the rows of the trades of fungible positions, every book's and node's aggregate, the columns, ids and links | rows **float32**, aggregates **float64** |
| **The heap** | the open dates' aggregates, columns, ids and links, the last 512 answers | every sum in float64 |

(`drishti.var.tier.mode: heap` keeps the 2.8.0-before behaviour instead: every trade's vector in float64 on the heap.)

### 9.1 The life of a date

1. **First request for a date.** The engine computes the digest of the date's inputs (the data versions of `pnl-vector`,
   `risk-hierarchy` and `scenario-set`, and the position rules). If `<data folder>/var/hot/<date>/inputs-<digest>.vhot` exists, it is
   **mapped** and the date is open: no lake read (23 ms for a 100,000-trade date on a workstation). Otherwise the rows stream from the
   lake to a local spill file (float64, the heap holds no vector), the trades are sorted into hierarchy order, every book and node is
   summed in float64, every position's row is written once as float32, and the file is moved into place and mapped.
2. **Requests.** A node is answered from its float64 aggregate (under 1 ms); any other scope sums float32 position rows into float64
   accumulators on every core; a trade is read only when a scope needs it alone (a drill below a position, `TRD:`, a basket of trades, a
   position held in part).
3. **Eviction.** At most `hot-dates` (2) dates are open. An evicted date is unmapped after `close-grace-seconds` (60) so requests still
   reading it finish; its file stays on disk, so asking for the date again maps it (the **disk tier**). At most `disk-dates` (60) files
   are kept, the least recently used beyond are deleted; a file of inputs that have changed (a restatement) is deleted when the new one
   opens.
4. **Shutdown and start.** At shutdown every date is unmapped and the files deleted (unless `keep-on-shutdown`); at start any left over
   are deleted (or, kept, reused when their digest still matches). Nothing is hidden: visible names, all under the data folder.

`drishti.py var status` shows it (test data, one date open):

```text
  "tier": { "mode": "mapped", "diskDates": 60, "keepOnShutdown": false, "closeGraceSeconds": 60,
            "mapping": "shared arena (unmapped on close)", "built": 1, "reopened": 0, "files": 1, "open": 1, "diskBytes": 23770624 },
  "loaded": [ { "businessDate": "2026-09-30", "trades": 9572, "positions": 7984, "nodes": 88, "vectorBytes": 20457728, "tier": "mapped",
                "aggregateBytes": 480496, "hotFile": "2026-09-30/inputs-a9df6dd969f2a9c2.vhot", "loadMs": 572, ... } ]
```

The test date is a 23.8 MB hot file (20.5 MB of float32 rows) against 39.0 MB of float64 vectors on the heap before 2.8.0.

### 9.2 The hot file

A little-endian file, written once: a 256-byte header (magic `DRSHVAR1`, format 1, the date, N, the counts, the scenario set, the
inputs' digest, the batch id), a section table, then sections, each with a CRC32C: the load's record (`META`), the scenario days, trade
and position ids, columns and runs, books and nodes, the aggregates (float64), the trade-to-row map, and the two matrices: `PMAT` (float32,
a row per position) and `TMAT` (float32, the trades of positions of more than one trade; absent when every position is one trade). The
byte layout is in [VAR.md section 7.3](../contracts/VAR.md#73-the-tiered-memory-and-the-hot-file-as-built-280). A damaged small section is
refused when the file opens, and the date is read from the lake again.

### 9.3 Precision: float32 rows, float64 sums

Each stored value is rounded once to float32 (a relative error of at most 2⁻²⁴, about 6 × 10⁻⁸); every sum is float64; and VaR and ES
are order statistics, so moving every scenario's P&L by at most *E* moves them by at most *E*. Hence:

- a **node's** VaR and ES are the lake's **to the bit** (its float64 aggregate);
- **any other scope** is within 2⁻²⁴ × the date's gross P&L of its worst scenario of the float64 answer.

Measured:

| Data | Largest VaR difference | Bound |
|---|---|---|
| the test data (9,572 trades, every node, 12 ad-hoc scopes, all 7,984 positions) | **0 USD** (whole dollars, every position below 2²⁴: float32 holds them exactly) | 919.5 USD |
| the same trades × 1,000.37 (a bank VaR of 112 billion) | **340.93 USD**, 5.86 × 10⁻⁸ of the VaR | 919,868 USD |

The answer says which tier it came from (`provenance.tier`): `node aggregate (float64)`, `hot file (float32 rows, summed in float64)` or
`memory (float64 rows)`.

### 9.4 Sizing

| Per date | 100,000 trades (measured) | 1,000,000 trades (extrapolated) |
|---|---|---|
| vectors in the lake (float64) | 407 MB | 4.07 GB |
| the hot file (float32 positions + columns + aggregates; one trade a position) | 237.7 MB | about 2.4 GB |
| the heap of an open date (columns, ids, links, aggregates) | about 80 MB | about 0.8 GB |
| the first load (spill, sort, aggregates, the file), after the lake read | 0.8 to 1.2 s | about 10 to 12 s |
| reopening a date from its file | 23 ms | about 0.25 s |
| a node (VaR, ES) | 0.48 ms | the same |
| the bank minus a desk | 1.1 ms | a few ms |
| the whole bank summed from its float32 rows (resident) | 3.4 ms, about 60 GB/s | about 34 ms |
| a basket of 50,000 scattered positions | 2.6 ms | about the same (it reads only its rows) |

Measured by the opt-in benchmark `VarTierBenchmark` (100,000 synthetic trades × 5 dates on a workstation with 24 hardware threads; the
heap tier sums the same bank in 6.6 ms, twice the bytes). **Fifty dates of a million trades** hold about 120 GB of hot files on local disk
and only the pages in use in memory; with `hot-dates: 2` the heap holds about 1.6 GB, against 8.1 GB of float64 vectors in the heap tier.
The full benchmark, on a server with about 110 GB of local disk:

```bash
./mvnw -o test -pl drishti-compute -Dtest=VarTierBenchmark -Ddrishti.var.tier.trades=1000000 -Ddrishti.var.tier.dates=50 \
    -Ddrishti.var.tier.dir=/local/var-bench -DargLine=-Xmx16g
```

The figures and their extrapolation are in [VAR.md section 12.2](../contracts/VAR.md#122-the-tiered-store-measured-and-extrapolated-as-built-280).
A memory budget still applies (`drishti.var.memory-budget-mb`): in the mapped tier it measures the float32 copy (4 bytes a value); a date
over it answers `503 DRS-5204`.

**Sharding across Drishti\*net is planned, not built**: each server holds whole dates
([VAR.md section 7.4](../contracts/VAR.md#74-sharding-across-drishtinet-planned-not-built)).

## 10. VaR as a kind: var-result and var-whatif

Two **computed kinds** of the market-risk pack make VaR an ordinary kind (a view, the entities API, the CLI, search, the AI tools), read
through reactive connectors as the person asking, with the explorer's entitlements
([VAR.md section 9.7](../contracts/VAR.md#97-the-computed-kinds-as-built-280)).

**`var-result`** (`VRES <scope> <GO>`, connector `var-engine`, the server's Java engine): the id is the scope; the record is the headline
in UPPER_SNAKE (`VAR`, `ES`, `K`, `LIMIT_USE`, `DIVERSIFICATION`, `BACKTEST` …), the default parts (`CHILDREN`), the 25 worst scenarios
(`WORST`), the unrounded vector (`PNL`) and `PROVENANCE`. A `~` inside a node id is written `%7E` in an id (`NODE:MGMT%7EDESK-RATES`), as
every computed id writes it; prefer `DESK:RATES`.

```text
$ drishti.py compute run var-engine var-result DESK:RATES
var-engine DESK:RATES  READY  (req-mvosw177-c6042d15feb9)
  SCOPE        DESK:RATES
  SCOPE_KIND   node
  LABEL        Rates · Swaps and options
  NODE         MGMT~DESK-RATES
  BUSINESS_DATE 2026-09-30
  VAR          74633680
  ES           75377415
  K            6
  LIMIT_USE    0.7463368
  SUM_STANDALONE 125524898
  DIVERSIFICATION 50891218
  computed 2026-10-26T05:12:54.554938067Z by java:var-engine@2.7.0, pin pin-e0cd3fe57e54607c, sha256:fd1912ea94a8fb93f1acb005507655d3
```

![VRES DESK:DESK-RATES: the computed kind as a page](img/var/12-var-result-kind.jpg)

**`var-whatif`** (`VWIF <scope> <GO>`, connector `var-whatif`, the **Python VaR engine** in the compute pool `var`): the scope's VaR
before and after hypothetical trades, and its stressed window. Java keeps the additive aggregation (it reads the scope's `var-result`);
Python does what does not add. The hypothetical trades live in your private **sandbox**, each with its P&L over the 509 scenario days
(priced elsewhere, or copied from a booked trade's vector) and a `FACTOR`; taking trades out is the scope's own algebra (`DESK:RATES -
TRD:X`), so it is exact. Parameters: `sandbox`, `stress-window` (250 days; 0 none: the largest VaR over every run of that many consecutive
scenario days), `component-window` (5). The answer: `BEFORE_VAR`, `AFTER_VAR`, `CHANGE_VAR`, the same for ES, the limit use before and
after, the stressed VaR before and after, and the tables `items` (each trade's standalone, incremental in order and alone, component VaR
and ES) and `pnl` (each day before and after). It needs the compute workers (`DRISHTI_COMPUTE_ENABLED=true`).

```bash
drishti.py var whatif DESK:RATES --trades new-hedge.json --stress-window 250
```

`new-hedge.json` is a list of trades: `[{"LABEL": "hedge of BOOK-RATES-3 (half)", "PNL": [509 numbers], "FACTOR": -0.5}]` (here the
vector of `BOOK-RATES-3`, read with `VRES BOOK:BOOK-RATES-3`, sold at half). The command makes the sandbox and asks the engine:

```text
what-if on DESK:RATES  READY  (req-mvot2fc8-0c51138d9548)
  BEFORE_VAR           74,633,680
  AFTER_VAR            47,599,220
  CHANGE_VAR           -27,034,460
  BEFORE_ES            75,377,415
  AFTER_ES             56,247,260
  CHANGE_ES            -19,130,155
  STRESSED_VAR_BEFORE  85,318,588
  STRESSED_VAR_AFTER   59,556,766
```

The `items` table of the same answer (`--json`): the hedge alone has a VaR of 34,296,045; its incremental VaR is −27,034,460 (the
change); its component in the desk after the trade is −32,542,151 (a hedge's component is negative). The engine ran on one worker of
the pool `var` in 22 ms.

## 11. Who sees what

The engine reads as the server and answers as you:

- a role that may not open `pnl-vector` sees no VaR (403);
- a role with no entry under `drishti.var.access` sees every node at **positions** level (numbers, children, positions, trades);
- `drishti.var.access.<role>[<node>]: positions | aggregate` restricts a role: **aggregate** gives a node's numbers and those of every
  node beneath it, never a position or a trade; a scope is computed over the trades you may see and the rest counted (*hidden*);
- a scope naming positions, trades, counterparties, a rule, a basket, a capture or a BASKET portfolio may only name trades you see at
  positions level (403 otherwise: a rule could probe single trades through aggregates);
- field masks apply: a masked counterparty reads `•••` in trade rows, and `CPTY:` is refused to a role that sees it masked;
- answers are cached per date, data version, view and scope: two people with different rights never share one; the computed kinds key
  their cache by the same view signature;
- tokens need `risk:write` to save baskets and captures; reloads and official captures are for administrators only;
- every headline, capture, basket change, reload and official run is an audit entry (Admin → Audit).

```yaml
drishti:
  var:
    access:
      division-head: { "[MGMT~DIV-MARKETS]": aggregate }     # brackets keep the node id whole
      rates-risk:    { "[MGMT~DESK-RATES]": positions }
```

## 12. The command line

`drishti.py var …` asks the server as your token's user ([TOOLS.md section 5.12](TOOLS.md#512-var), [CLI_GUIDE.md](CLI_GUIDE.md#var-the-var-engine-280)):

| Command | Does |
|---|---|
| `var show <scope> [--date D]` | the headline (VaR, ES, limit, parts, backtest, provenance) |
| `var parts <scope> [--by child\|node\|position\|trade\|desk\|book] [--scenario D] [--top N]` | contributions |
| `var path <scope> [--size N] [--page P]` | the ranked P&L path |
| `var history <scope>` | history and backtest |
| `var tree [--hierarchy MGMT\|LEGAL]` | every node's VaR, ES, limit use |
| `var status` | settings, tiers, loaded dates |
| `var basket <name> <scope>`, `var baskets` | save and list baskets |
| `var capture <scope> [--note]`, `var captures`, `var compare <a> [b]` | captures |
| `var official [--capture] [--date D]`, `var reload` | the official record; a reload (administrators) |
| `var whatif <scope> --trades FILE [--stress-window N]` | the Python engine's what-if |

Add `--json` for the server's answer as it is. A scope with spaces goes in quotes: `drishti.py var show 'BANK - DESK:CREDIT'`:

```text
VAR BANK - DESK:CREDIT  ·  BANK - DESK:CREDIT  ·  adhoc  ·  2026-09-30
  VaR 99%   76,607,919 USD   (the 6th worst of 509)
  ES 97.5%   76,758,288 USD   (mean of the 13 worst)
  parts      5000 position   standalone sum 2,598,894,398   diversification 2,522,286,479
  backtest   green  4 exceptions in 249 days   Kupiec p 0.377   Christoffersen p 0.717
  8629 trades in 7041 positions
  pnl-vector · batch B-2026-09-30-01 of 2026-09-30 · data version risk-store=dc731161ff617ac4
  hot file (float32 rows, summed in float64) · historical, equal weights, 1-day, k = 6th worst of 509 · 1 ms (miss)
```

(*5000 position*: an ad-hoc scope's parts are its positions, at most `max-parts`, the largest by gross size.) The tree of the Legal
hierarchy (`drishti.py var tree --hierarchy LEGAL`, the first rows):

```text
Node                             VaR          ES           Limit use  Trades
-------------------------------  -----------  -----------  ---------  ------
Drishti Bank                     112,035,906  160,132,107      53.4%    9572
  LE-FRA                           1,536,322    1,665,992      66.8%     611
    Repo and securities lending    1,536,322    1,665,992      61.5%     611
  LE-LDN                          48,350,793   53,764,068      63.6%    2679
    Equity derivatives            35,827,767   34,901,016      77.9%    1201
  LE-NY                          144,267,105  174,614,379      80.1%    6008
    Credit trading               130,670,864  189,194,564      65.3%     943
```

## 13. The API

`/api/v1/var` answers as the caller, on `asOf=` (default: the newest date with vectors); errors are RFC 7807 problems with a `code`. The
endpoints are in [API_GUIDE.md](API_GUIDE.md#var-engine-apiv1var-280) and [VAR.md section 9](../contracts/VAR.md#9-what-drishti-answers):
`GET /scope`, `/scope/path`, `/scope/history`, `/scope/contributions`, `/tree`, `/status`, `/official`; `POST /reload`, `/official`;
baskets, captures, compare. The computed kinds: `POST /api/v1/compute/var-engine/var-result` and `/api/v1/compute/var-whatif/var-whatif`
(`{id, params, asOf, wait}`), or `GET /api/v1/views/var-result/DESK:RATES`.

```bash
curl -s -H "Authorization: Bearer $DRISHTI_TOKEN" \
  "http://localhost:18480/api/v1/var/scope?scope=DESK:RATES%20-%20TRD:MX-30000121"
```

```json
{"scope": "DESK:RATES - TRD:MX-30000121", "canonical": "DESK:RATES - TRD:MX-30000121", "kind": "adhoc", "businessDate": "2026-09-30",
 "var": 74720890, "es": 75093413, "k": 6, "esCount": 13, "scenarios": 509, "confidence": 0.99, "esConfidence": 0.975,
 "children": 1348, "childKind": "position", "sumStandalone": 738294628, "diversification": 663573738, "limit": null,
 "backtest": {"days": 249, "exceptions": 2, "light": "green", "kupiecPValue": 0.7466, "christoffersenPValue": 0.8569},
 "provenance": {"resolved": ["node MGMT~DESK-RATES"], "trades": 1486, "positions": 1348, "hidden": 0, "unknown": [],
   "tier": "hot file (float32 rows, summed in float64)",
   "inputs": {"pnl-vector": "risk-store=dc731161ff617ac4", "risk-hierarchy": "risk-store=5e04cd896a85d342",
              "scenario-set": "risk-store=403c7f5e83f6df91", "portfolio": "risk-store=efcc196b8cbfb4a8", "scenarioSet": "HS-509",
              "batchIds": ["B-2026-09-30-01"], "positionRules": "ab645dcc102d6a9a"},
   "computeMs": 6, "cache": "miss", ...}}
```

## 14. Settings

`drishti.var.*` in the server's `application.yaml` ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtivar--the-var-engine)):

| Setting | Default | Means |
|---|---|---|
| `enabled` | `true` (`DRISHTI_VAR_ENABLED`) | the engine, its API and its computed kinds |
| `confidence`, `es-confidence`, `rank-rule`, `component-window` | 0.99, 0.975, `kth-worst`, 5 | the measures (section 2) |
| `threads`, `parallel-rows` | every core (`DRISHTI_VAR_THREADS`), 4,096 | the sum pool |
| `memory-budget-mb` | a quarter of the heap (`DRISHTI_VAR_MEMORY_MB`) | the most one date's vectors may take (float32 copy when mapped) |
| `hot-dates` | 2 | dates open at once |
| `tier.mode` | `mapped` (`DRISHTI_VAR_TIER`) | `mapped` (hot files) or `heap` |
| `tier.dir` | `<data folder>/var/hot` (`DRISHTI_VAR_HOT_DIR`) | the hot files' folder, one folder per date; put it on fast local disk |
| `tier.disk-dates` | 60 | hot files kept on disk |
| `tier.keep-on-shutdown` | `false` | keep the files for the next start (reused when their digest matches) |
| `tier.close-grace-seconds` | 60 | how long an evicted date stays mapped for requests still reading it |
| `history-days`, `max-parts`, `path-page`, `histogram-bins` | 250, 5,000, 60, 42 | the history window and the answers' sizes |
| `access`, `positions`, `official.*` | | sections 11, 4 and 3 |

The compute pool `var` of `config/compute/compute.yaml` (workers `DRISHTI_COMPUTE_VAR_WORKERS`, 4) runs the Python engine.

## 15. Troubleshooting

| You see | Why | Do |
|---|---|---|
| *no P&L vectors in any connector* (404 `DRS-1001`) | no `pnl-vector` table, or the market-risk pack is off | build the lake (`drishti.py lake demo`); enable the pack |
| *no connector keeps pnl-vector.PNL as a vector column* (502 `DRS-1003`) | the connector's layout lacks `vectors: [PNL]` | regenerate the pack or add it to your connector file |
| *… names nothing*, *an unknown word*, *trailing text* (400 `DRS-5001`) | the scope does not parse | check the grammar (section 5.2); operators stand between spaces |
| *your role may not see VaR*, *… only in aggregate* (403 `DRS-5002`) | entitlements (section 11) | ask for the level you need |
| *Rows left out* in the provenance (`DRS-5201`) | rows of the wrong length, with a NaN, in another currency, or failed with a vector | fix the batch's rows and restate the date |
| *two P&L vectors on one business date* (422 `DRS-5202`) | a trade id twice | restate the date with one row per trade |
| a warning that the date before was answered (`DRS-5203`) | the date's rows come from two runs or scenario sets | restate as one run; the signal reloads it |
| *… need N MB; the vector store's budget is M MB* (503 `DRS-5204`) | the date exceeds `memory-budget-mb` | raise it (mapped: it counts 4 bytes a value) |
| *the VaR hot folder … cannot be written* | `tier.dir` missing, read-only or full | point `DRISHTI_VAR_HOT_DIR` at writable local disk with room for `disk-dates` files |
| *the hot file … is not usable* in the log | a damaged or old-format file | nothing: the date is read from the lake and the file rebuilt |
| *the vectors of … were unloaded while this answer was computed; ask again* | a request outlived its date's eviction plus the grace | ask again; raise `close-grace-seconds` or `hot-dates` if it recurs |
| the first request of a date is slow | the date is read from the lake and its hot file built | expected once per date and set of inputs; later requests map the file |
| disk fills up | many dates kept, or a million-trade bank (about 2.4 GB a date) | lower `disk-dates`; keep `keep-on-shutdown: false` |
| `var-whatif` answers *could not start* | the compute workers are off or the pool `var` has no worker | `DRISHTI_COMPUTE_ENABLED=true`; Admin → Compute |
| *Unknown ids* in the provenance | ids not in the date's batch (matured, cancelled, typed wrong) | check them; nothing else changes |
| the history says *Replay* | the scope is not one node, you see it in part, or it has one official date | as designed: the replay is in-sample |

The server's log names every load, hot file built or reopened, and refusal; `GET /api/v1/var/status` shows what is open and on disk.
