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
# Drishti Trading: what traders and desk heads need, what the lens gives them today, and what comes next

**Status: analysis and product description: built parts as built, the rest proposals.** Every "built" or "partly" statement cites the
document, section or file it was read from on 2026-10-31 (develop at b22ff3850). Features built on a branch are cited as "branch, merging
in 2.9.7". Everything in sections 5 and 8 is a proposal; the rows of section 8 are candidates in the
[implementation plan](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started), not started.

**The request (product owner, 2026-10-31):** "We should have similar and fuller standalone document for other lenses too", after the
market risk oversight analysis (RISK_OVERSIGHT.md, on branch `wip/risk-oversight`, merging in 2.9.7). This is the Trading lens's.

**Read with:** [EDITIONS.md section 14](../architecture/EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297) (the lens as built),
[EDITIONS_GUIDE.md section 12](../admin/EDITIONS_GUIDE.md#12-drishti-trading) (how to run it), the edition file
[`config/editions/trading.yaml`](../../config/editions/trading.yaml), [VAR_GUIDE.md](../guides/VAR_GUIDE.md),
[EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md), [COMPUTED_ATTRIBUTES.md](../architecture/COMPUTED_ATTRIBUTES.md) and the contracts
[TVAL.md](../contracts/TVAL.md) (valuation totals) and [PNL.md](../contracts/PNL.md) (P&L explain). The sibling lens documents:
[RISK_LENS.md](RISK_LENS.md), [MARKET_LENS.md](MARKET_LENS.md), [WORLD_LENS.md](WORLD_LENS.md).

## Contents

1. [Who uses it](#1-who-uses-it)
2. [Their questions: the morning, intraday, the close, month-end](#2-their-questions-the-morning-intraday-the-close-month-end)
3. [Needs by area, with status](#3-needs-by-area-with-status)
4. [The landings as built](#4-the-landings-as-built)
5. [Gaps, with design sketches](#5-gaps-with-design-sketches)
6. [Data needed](#6-data-needed)
7. [Relationship to the other lenses](#7-relationship-to-the-other-lenses)
8. [A roadmap](#8-a-roadmap)
9. [Decisions for the product owner](#9-decisions-for-the-product-owner)
10. [Glossary](#10-glossary)

**Status words.** **Built**: on develop, documented as built. **Partly**: some of it is built; the cell says what is missing.
**Branch**: built on a branch, merging in 2.9.7. **Planned (row)**: a row of the implementation plan names it. **Not built**: neither
built nor planned; section 5 sketches it and section 8 proposes a row.

---

## 1. Who uses it

The **first line of defence**: the people who take the risk and earn the P&L. Drishti Trading is for two of them
([`config/editions/trading.yaml`](../../config/editions/trading.yaml), its header: "for traders and desk heads: my books, my P&L, my VaR
and limits, my counterparties, and 'does this new trade fit?'").

| Persona | Accountable for | What they need from a system |
|---|---|---|
| **Trader** (a market maker or a risk taker on a desk; runs one or a few books) | The P&L of their books; staying inside their delegated limits (VaR, sensitivities, stop-loss, notional); pricing and booking client and hedge trades correctly; explaining their P&L to product control and their risk to market risk | *My* books on one page: P&L today, risk, limit use, today's trades; a fast pre-deal check; the counterparty's room under its credit limit |
| **Desk head** (head of a desk: rates, FX, credit, equities, commodities; several traders and books) | The desk's P&L and its budget; the desk's limits and any breach; the traders' use of the delegated limits; explaining a breach and asking for a temporary increase; the desk's concentrations and largest counterparties | The desk at a glance: P&L by book and trader, limit use over days, breaches and books near limit, P&L against VaR, the movers |
| *Also served*: the **business manager** or **desk risk analyst** (COO function of a business) | The desk's controls, limit paperwork, the daily P&L sign-off with product control | The same pages, often through **View as** |

**What the first line does not own** (and the lens does not try to show): the limit framework itself, the model, independent challenge and
breach approval (the second line: the Risk and proposed Oversight lenses, section 7); collateral operations and regulatory capital (left
out of the edition: `trading.yaml`'s comment "left out: regulatory (FRTB, SACCR, CVA, SIMM), stress (SCN, STR, SSPNL), collateral
operations (COLL, MC) …").

### 1.1 A day, a month and a year

| When | Trader | Desk head |
|---|---|---|
| **Before the open** (06:30 to 08:00) | Yesterday's P&L as product control struck it; overnight moves on the books; today's VaR and limit headroom; expiries, fixings and settlements due today | The desk's P&L and VaR; any breach from yesterday's official run and its explanation to market risk; the morning meeting with risk |
| **Intraday** | Quote and price; "does this trade fit?"; hedge; watch P&L, risk and limit use as trades are booked | Approve large trades; watch the desk's limit use; react to a trader near a limit |
| **The close** (16:00 to 19:00) | Mark the book; check the booked trades (amendments, cancellations); the flash P&L | Sign the desk's flash P&L; note breaches |
| **Month-end** | Month-to-date P&L against budget and stop-loss | The desk's P&L, VaR and limit use over the month; the P&L-against-VaR exceptions and their explanations; limit review input |
| **Year** | — | The budget and the limit request for next year (the use statistics: average, peak, days near limit); the year's P&L and its explanation |

---

## 2. Their questions: the morning, intraday, the close, month-end

### 2.1 The morning

| Question | Drishti today | Gap |
|---|---|---|
| *What did my books make yesterday?* | **Built**: **P&L today**, the MtM change of every trade valued on both the business date and the business day before, summed over my books; no valuations the day before: no number, said ([EDITIONS.md section 14](../architecture/EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297), the Trader desk) | Product control's struck P&L (actual, with cash, fees and reserves) is not received: the figure is MtM change only. The bank's P&L feed is 2.9.8 row 3 |
| *Why did it move?* | **Partly**: **Overnight movers** (the trades whose MtM moved most) on the Desk head landing; the trade events (new, amended, cancelled) on the Trader desk | A P&L explain (market by risk factor, time, new and amended trades, unexplained): the contract is proposed and parked ([PNL.md section 1](../contracts/PNL.md#1-summary)); section 5.4 |
| *What is my VaR and how close am I to my limit?* | **Built**: **My VaR** (the union of my books, `BOOK:A + BOOK:B`), labelled intraday when updates were applied; **Limit use**: the highest of my books' VaR ÷ its `var-limit` (EDITIONS.md section 14) | Limits other than VaR (DV01, CS01, vega, stop-loss, notional): not built (section 5.7); 10-day and other confidences (**branch** `wip/var-horizons`: VAR_GUIDE section 18.6 there, merging in 2.9.7) |
| *Did any of my books breach?* | **Built** for VaR limits: **Breaches** and **Near limit** tiles (Desk head; `trading.near-limit` 0.8); **Limit use by book** over 5 business dates from the official records | A breach as a record the desk explains (RO2 of the oversight analysis; section 5.6) |
| *Did yesterday's P&L exceed VaR?* | **Partly**: **P&L against VaR**, the desk's official history (hypothetical P&L against the VaR before it), last 60 days, exceptions circled; **left out** without official history (EDITIONS.md section 14) | The bank's actual and hypothetical P&L (2.9.8 rows 3 and 4); section 5.3 |
| *What did I book yesterday, and is it right?* | **Built**: **Trades today** (new, amended, cancelled) and **Today's blotter**; on the demo data "new" reads 0 (no trade is booked on its business date) | New trades on the demo data (section 5.1) |

### 2.2 Intraday

| Question | Drishti today | Gap |
|---|---|---|
| *Does this new trade fit my VaR limit and the counterparty's credit limit?* | **Built**: **Check a new trade**, one VaR what-if on the book and one pre-deal check on the counterparty, each on a scaled copy of a booked trade of the product ([EDITIONS.md section 14, The new-trade check](../architecture/EDITIONS.md#the-new-trade-check)) | A real price of the new trade (not a scaled copy): QLRisk pricing, parked until its API is stable ([VAR_GUIDE section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes-what-if-planned-intraday-updates-as-built-in-294)); section 5.8 |
| *Where am I now, after this morning's trades?* | **Built** for VaR: intraday updates from files and Kafka change today's VaR (VAR_GUIDE section 8.4); the tile says **intraday** with the trades updated and the time | Intraday P&L (P&L today is end-of-day valuations only); section 5.9 |
| *Tell me when I get near my limit.* | **Partly**: alerts on numbers and monitors are on in the edition (`trading.yaml` `features: alerts: on, monitors: on`); each person sets their own ([USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers)) | A one-click "watch my books" from the landing; limit alerts from intraday VaR (section 5.10) |
| *What is the largest counterparty on my desk?* | **Partly**: **Largest counterparty** is its share of the desk's **positive MtM** over live trades, not exposure (EDITIONS.md section 14) | The desk's share of each counterparty's exposure (section 5.2) |
| *What is a curve or vol doing?* | **Built**: `CRV`, `FX`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS` in the edition (`trading.yaml` `commands.allow`) | The Market lens's moves and quality checks are not on a Trading landing (section 7) |

### 2.3 The close

| Question | Drishti today | Gap |
|---|---|---|
| *Is my blotter complete and right?* | **Partly**: Today's blotter newest first with each trade's notional from its valuation; `TRD`, `CTRD`, `PTRD`, `LTRD`, `ITRD` | A reconciliation against the booking system or the trades valued (a trade with no valuation, a valuation of a cancelled trade): the load checks count rejected rows but completeness against trades booked is not checked ([VAR_GUIDE section 20.3](../guides/VAR_GUIDE.md#203-what-the-load-checks-as-built)) |
| *What is my flash P&L?* | **Partly**: P&L today recomputes when the date's valuations land (cached two minutes per person) | Flash P&L is the source's; Drishti shows MtM change. Not a gap Drishti should fill: Drishti does not price |
| *Will my end-of-day VaR breach?* | **Partly**: the intraday VaR and the limit use (Trader desk) | A projected official limit use with the trades of the day (it is the intraday figure; say so on the tile) |

### 2.4 Month-end and the year

| Task | Drishti today | Gap |
|---|---|---|
| Month-to-date and year-to-date P&L against budget and stop-loss | **Not built**: no P&L series is stored or received; the valuations give daily MtM change only | The bank's P&L feed (2.9.8 row 3), then a stop-loss check (RO5 of the oversight analysis); section 5.11 |
| The desk's limit use over the month (average, peak, days near) | **Partly**: the official records carry `LIMIT_USE` per node and date, read in BI (`market-risk-var-official` is in the edition's BI datasets, `trading.yaml` `bi.datasets`) | A use-statistics card on the Desk head landing (section 5.12) |
| P&L-against-VaR exceptions with their explanations | **Partly**: exceptions circled on the card | An exception commentary (one comment thread per exception, as for breaches) |
| Next year's limit request | **Partly**: BI over the official records | The statistics above, as a desk report |

---

## 3. Needs by area, with status

### 3.1 P&L

| Need | Status | Evidence or row |
|---|---|---|
| Daily MtM change of my books | **Built** | P&L today ([EDITIONS.md section 14](../architecture/EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)) |
| Actual P&L (with cash, fees, reserves) | **Not built**; needs the bank's P&L feed | 2.9.8 row 3 ([IMPLEMENTATION_PLAN 2.9.8](../architecture/IMPLEMENTATION_PLAN.md#298-regulatory-var-reporting-phase-1-product-owner-2026-10-30-not-started)) |
| P&L explain (by risk factor, time, new and amended trades, unexplained) | **Not built**; contract proposed, parked | [PNL.md](../contracts/PNL.md); the stored `pnl-explain` (PNL) is a generator's, with known inconsistencies ([COMPUTED_ATTRIBUTES.md section 4.10](../architecture/COMPUTED_ATTRIBUTES.md#410-pnl-explain-pnl)) |
| P&L by trader | **Built** from the valuations' `TRADER_ID`; masked for a role without raw, then said | EDITIONS.md section 14, Desk head |
| The pricing system's own `PNL_1D` | **Not used**: a different figure on the demo data | EDITIONS.md section 14; agrees with MtM change on 201 of 15,000 trades (F14, [COMPUTED_ATTRIBUTES.md section 4.1](../architecture/COMPUTED_ATTRIBUTES.md#41-trade-valuation-tval)) |
| Month-to-date, year-to-date | **Not built** | section 5.11 |
| Intraday P&L | **Not built** | section 5.9 |

### 3.2 Risk on my books

| Need | Status | Evidence or row |
|---|---|---|
| VaR of my books, desk, any union | **Built** | My VaR, Desk VaR (EDITIONS.md section 14); scopes ([VAR_GUIDE section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets)) |
| Top positions by VaR contribution | **Built** | `/scope/contributions?by=position` (Trader desk) |
| VaR by trader (adds up to the desk) | **Built** to `max-parts` (5,000 trades, the largest said) | Desk head, By trader |
| 10-day VaR and the confidence chosen | **Branch** (`wip/var-horizons`, merging in 2.9.7; plan 2.9.7 row 2b) | VAR_GUIDE section 18.6 on the branch: √10 and the 10-day sum, a confidence picker, limit use only when comparable |
| Stressed VaR | **Planned** (2.9.7 row 2c) | |
| The scenario matrix (is this tail scenario new?) | **Branch** (`wip/scenario-matrix`, merging in 2.9.7) | VAR_GUIDE section 3.9 on the branch, `SMAT`; not in the edition's command list yet (section 9, decision 5) |
| DV01 of my books | **Partly**: the sum of the live trades' `DV01` when any holds one; else the tile is left out | EDITIONS.md section 14 |
| DV01 by tenor and currency, CS01, vega, FX delta | **Not built**: per-trade `DV01` only, no bucketed sensitivities | [COMPUTED_ATTRIBUTES.md section 4.9](../architecture/COMPUTED_ATTRIBUTES.md#49-frtb-sensitivity-frtb); RO4 of the oversight analysis; section 5.7 |
| Stress on my book | **Hidden** in the edition (stress commands left out) | `trading.yaml`; section 9, decision 4 |

### 3.3 Limits and breaches

| Need | Status | Evidence or row |
|---|---|---|
| VaR limit use per book and desk | **Built** | `var-limit` ([VAR_GUIDE section 15.15](../guides/VAR_GUIDE.md#1515-var-limit-the-nodes-var-limits-as-built-295)); Limit use tile |
| Limit use history by book (official) | **Built**: 5 business dates; a missing record shows "—" | Desk head |
| Near-limit threshold | **Built**: `trading.near-limit` 0.8 | A different number from the VaR explorer's amber 75% and red 90% ([VAR_GUIDE section 3.2](../guides/VAR_GUIDE.md#32-the-screen)); section 9, decision 7 |
| Sensitivity, stop-loss, notional limits | **Not built** | RO3 to RO6 of the oversight analysis |
| A breach the desk explains | **Not built** | RO2 of the oversight analysis; section 5.6 |

### 3.4 Trades and the blotter

| Need | Status | Evidence or row |
|---|---|---|
| Today's trades: new, amended, cancelled | **Built**, with a fallback from the two days' valuations when the search by `SOURCE_UPDATE_TIME` does not answer in time (`DRS-1004`) | EDITIONS.md section 14, Trades today |
| New trades on the demo data | **Not served**: every change of a day is an amendment or a cancellation | section 5.1 |
| Today's blotter, newest first | **Built** | |
| Trade pages: legs, cash flows, resets, events | **Built** (trading pack) | [trading pack README](../../config/packs/trading/README.md) |
| A trade's history over dates | **Built** (history queries, `TRD 1000015 30`) | [HISTORY_QUERIES.md](../architecture/HISTORY_QUERIES.md) |

### 3.5 Counterparties

| Need | Status | Evidence or row |
|---|---|---|
| A counterparty's trades, netting sets, MtM | **Built**: `CTRD`, `NSET`, `NSMTM`, `CPMTM` (netting-set and counterparty MtM derived in 2.9.7) | [COMPUTED_ATTRIBUTES.md section 13.1](../architecture/COMPUTED_ATTRIBUTES.md#131-the-derived-kinds-and-the-sutra-panels-q1-to-q4) |
| Pre-deal PFE against the credit limit | **Built**: exact `paths` method, or the engine's `addon` proxy labelled proxy | [EXPOSURE_GUIDE section 7](../guides/EXPOSURE_GUIDE.md#7-pre-deal-what-if); EDITIONS.md section 14 |
| The desk's largest counterparty by exposure | **Partly**: by positive MtM | section 5.2 |
| Credit limit use on the counterparty's page | **Built as read**; the stored limits read very low use (sized on the retired profiles) | [EXPOSURE_GUIDE section 28](../guides/EXPOSURE_GUIDE.md#28-open-items) |

### 3.6 "My", people and access

| Need | Status | Evidence or row |
|---|---|---|
| Who is who: user → trader or desk | **Built**: `trading.people` (edition, then console), a matching trader record, then View as | [EDITIONS.md section 14, "My"](../architecture/EDITIONS.md#my-who-a-person-is-configured) |
| The landing by trading role | **Built**: `trading.landing-by-role: {desk-head: desk, trader: trader}`, below the person's own choice | |
| Never widening access | **Built**, tested (`test_scoping_never_widens_access_every_read_is_the_persons_own`) | |
| "My" from the bank's directory (groups, claims) | **Not built** | section 5.13 |
| Who sees the lens link (`lens.roles`) | **Not wired** (a hook) | [EDITIONS.md section 8](../architecture/EDITIONS.md#8-lenses) |

---

## 4. The landings as built

One template, `home/trading_landing.html`, with `trading-landing.js` and `trading-landing.css`, a **green accent**; figures worked out by
`drishti-console/core/trading_landing.py` and served read-only by `drishti-console/routes/trading_routes.py`
(`/api/home/trading/{me,valuations,blotter,risk,limits,pnl-var,traders}` and `POST /api/home/trading/check`; `404 DRS-4023` unless the
person's edition or lens shows `TRD`), cached two minutes per person, date and scope ([EDITIONS.md section 14](../architecture/EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)).

### 4.1 A: Trader desk (`trader`, the default)

| Tile or card | What is real | Left out, and why |
|---|---|---|
| **P&L today** | MtM change of every trade of my books valued on both days (`TVAL`), summed; the date box moves both days; when the lake's latest valuations are older than the business date, the page says "the latest valuations are of …" | No valuations the day before: no number. The valuations' own `PNL_1D` is not used (a different figure on the demo data) |
| **My VaR** | The VaR engine's figure for the union of my books; labelled **intraday** with the trades updated and the time | — |
| **Limit use** | The highest of my books' VaR ÷ its `var-limit`, the book and limit named | A limit at another horizon or confidence (comparable only like for like once `wip/var-horizons` merges) |
| **DV01** | Σ live trades' `DV01` when any holds a number | Left out when none does |
| **Trades today** | New, amended, cancelled by `SOURCE_UPDATE_TIME` on the business date; on a large lake (`DRS-1004`) from the two days' valuations, with no time of day, said | "New" reads 0 on the demo data |
| **Check a new trade** | Product (from my live trades' products), side, notional (`250m`, `1.5bn`), counterparty, book → one VaR what-if and one pre-deal check (section 4.4) | A trade of a product I have never traded (no copy to scale) |
| **Today's blotter** | Newest first, each trade's notional from its valuation | — |
| **Top positions** | By VaR contribution: market value and component VaR | — |
| **Quick commands** | My trader, my book's VaR, my book, my most traded counterparty, then the file's cards | — |

### 4.2 B: Desk head (`desk`)

| Tile or card | What is real | Left out, and why |
|---|---|---|
| **Desk P&L today** | As P&L today, over the desk, with the best book | As above |
| **Desk VaR** and limit use | `DESK:<id>` from the VaR engine | — |
| **Breaches**, **Near limit** | Books above 100% and at or above `trading.near-limit` | — |
| **Largest counterparty** | Its share of the desk's **positive MtM** over live trades (current exposure before netting and collateral) | Exposure: EDITIONS.md says the exposure engine has no desk scope. **Finding:** the engine does accept `DESK:` as a `partial` scope that cuts netting sets, recomputed in seconds per touched set ([EXPOSURE_GUIDE section 4](../guides/EXPOSURE_GUIDE.md#4-scopes)); what it lacks is a desk's **share of each counterparty's netted, collateralised exposure** and a one-call ranking. Section 5.2 |
| **Limit use by book** | Last `trading.limit-days` (5) business dates, each cell VaR ÷ limit from that date's **official record**, each cell printing its percentage | A date without a record: "—", named under the grid; a role that sees only some nodes: "not available" |
| **By trader** | Each trader's P&L and live trades from the valuations' `TRADER_ID`; VaR as the sum of their trades' component VaR in the desk | With `TRADER_ID` masked: "—", record counts with a star, the reason said; beyond `max-parts` the largest only |
| **P&L against VaR** | The desk's official history: each day's hypothetical P&L against the VaR before it, `trading.pnl-var-days` (60), exceptions circled | Left out when the history is a replay (scenario P&L, not a day's) |
| **Overnight movers** | Trades whose MtM moved most since the day before | Without valuations the day before |

### 4.3 C: Explorer (`explorer`)

A big command line; the chips of `home.chips` (`TRD 1000001`, `BOOK BOOK-RATES-1`, `CTRD CP-ALDERSHOT`, `POS BOOK-RATES-1`,
`VAR DESK DESK-RATES`, `XWIF`, `CRV CRV-USD-OIS`); the commands grouped (blotter, books and positions, risk on my book, counterparties,
market data); Recently opened (the person's own history, Trading's commands only).

### 4.4 The new-trade check, as built

From [EDITIONS.md section 14, The new-trade check](../architecture/EDITIONS.md#the-new-trade-check): the engines price a **copy of a booked
trade**, so the check picks one (a live trade of the product in my books: the counterparty's own first, then the chosen book's, then the
largest notional) and scales it (factor = notional ÷ its notional, negative when the side differs).

- **VaR**: `POST /api/v1/var/what-if` on `BOOK:<book>` with the copy and factor (the Python VaR engine) → VaR before and after, limit use
  before and after: **fits**, **near**, **breaches**; without the Python engine the half says so (`DRS-5205`).
- **Counterparty**: `POST /api/v1/exposure/what-if` on `CPTY:<cp>` (method `paths`, the copy into the counterparty's largest netting set) →
  peak PFE before and after against the credit limit; the `addon` proxy, labelled, when `paths` cannot run (`DRS-5446`, or no netting set).

**What a trader should know about it.** A scaled copy is exact for a **linear** product with the same terms (a swap of the same tenor and
currency); for an option, a different strike or tenor, or a new currency pair, it is an approximation that the answer does not quantify.
Section 5.8 proposes saying so on the answer and, later, pricing the real trade.

### 4.5 What the data does not give (as the builder reported)

From [EDITIONS.md section 14](../architecture/EDITIONS.md#what-the-data-does-not-give-left-out-or-labelled-never-invented): the DV01 tile
without DV01s; P&L, by book, by trader and the movers without valuations the day before; the P&L-against-VaR card without official
history; by-trader figures for a masked role; a book's limit use on a date without an official record; VaR by trader beyond `max-parts`;
"new" trades on data that books none on the day; a time of day other than `SOURCE_UPDATE_TIME`; the largest counterparty's share of
**exposure**; a scope above `trading.max-rows` (20,000 valuations a date) is read in part and every figure says **partial**.

### 4.6 What is missing from the landings (the gaps this document addresses)

1. New trades on the demo data (5.1). 2. Exposure, not MtM, for the largest counterparty (5.2). 3. P&L against VaR from the bank's P&L
(5.3). 4. A P&L explain (5.4). 5. Stressed and 10-day VaR, confidence (5.5). 6. My breaches to explain (5.6). 7. Sensitivity and other
limits (5.7). 8. A check on the real trade (5.8). 9. Intraday P&L (5.9). 10. Watch my books (5.10). 11. Month-to-date P&L and
stop-loss (5.11). 12. Limit statistics (5.12). 13. "My" from the directory (5.13).

---

## 5. Gaps, with design sketches

Every design follows Drishti's rules ([COMPUTED_ATTRIBUTES.md section 1.2](../architecture/COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)):
fundamental inputs arrive under a **data contract** as JSON Lines into the lake; measures are **computed on demand** from trade-grain
inputs and **never stored**, except as an immutable capture or an official end-of-day record; Drishti-owned records (a comment, an
acknowledgement) are versioned in the content store; **BI is the report layer**; a landing is a reader of the engines' answers, read as
the person, never widening access. Sizes: **S** days, **M** one to three weeks, **L** more than three weeks, one builder. Ids TL1 to TL13
are this document's (Trading lens); RO ids are the oversight analysis's.

| Id | Gap | Size | Depends on |
|---|---|---|---|
| TL1 | New trades on the demo data; `SOURCE_UPDATE_TIME` searchable | S | the test-data generators (seeds plan) |
| TL2 | The desk's share of each counterparty's exposure | M | exposure contributions by trade (built at small sizes) |
| TL3 | P&L against VaR from the bank's P&L | S | 2.9.8 rows 3 and 4 |
| TL4 | P&L explain: TL4a by trade events (S), TL4b by risk factor (L) | S; L | TL4a none; TL4b per-trade sensitivities, market moves ([PNL.md](../contracts/PNL.md)) |
| TL5 | Stressed VaR, 10-day and confidence on the landings and the check | S | 2.9.7 rows 2b (branch) and 2c |
| TL6 | My breaches to explain | S | RO2 |
| TL7 | Sensitivity ladders and limits on my books | M after RO4 | RO3, RO4 |
| TL8 | The check on the real trade | S (labels) then L | QLRisk API stable (parked) |
| TL9 | Intraday P&L | M | the bank's intraday base PVs |
| TL10 | Watch my books (alerts from the landing) | S | alerts on numbers (built) |
| TL11 | Month-to-date and year-to-date P&L, stop-loss | S | 2.9.8 row 3; RO5 |
| TL12 | Limit use statistics on the Desk head | S | official records (built) |
| TL13 | "My" from the directory | M | single sign-on groups; RBAC later |

### 5.1 TL1: new trades on the demo data, and a searchable update time

**Today.** "New" reads 0 on the demo data because no trade is booked on its business date; the search by `SOURCE_UPDATE_TIME` reads every
trade document because the field is not a store column, and on the product owner's stack it may not answer in time (`DRS-1004`), so the
events fall back to the two days' valuations ([EDITIONS.md section 14](../architecture/EDITIONS.md#14-drishti-trading-built-2026-10-30-for-297)).

**Design.**

- **Data.** The trading test-data generator books a share of each business date's trades **on** that date (a configurable rate, for
  example 0.5% of the live book a day, products weighted as the book), with `TRADE_ACTION` NEW, `TRADE_DATE` the date, a
  `SOURCE_UPDATE_TIME` within the trading day, and a valuation and P&L vector from that date on. Matured and cancelled trades leave the
  same way they do today. This fits the plan to regenerate test data from seeds (TESTDATA_FROM_SEEDS_PLAN.md); synthetic only, Endur-style
  names kept (house rule).
- **Store.** `SOURCE_UPDATE_TIME` (and `TRADE_ACTION`) as store columns of the trade lake table, so "the trades of these books updated
  today" is a pruned columnar read, not a document scan.
- **Contract.** The trading pack's `trade` contract already has the fields; the change is the generator and the store column list.

**Data needs.** None from the bank: a real bank's feed books trades on their date. **Size** S. **Tests:** on the regenerated demo, a
date's new trades are counted, carry a time and appear in Trades today and the blotter; the valuations fallback agrees with the search
on the same date; a book with no activity shows 0 with the reason.

### 5.2 TL2: the desk's share of each counterparty's exposure

**The question.** *Which counterparty is my desk's largest credit exposure, and how much of its limit is my desk using?* Exposure is
netted and collateralised **per netting set**, across desks: a desk's trades are part of a set, not a set. Two honest answers exist:

| Answer | Method | What it means | Cost |
|---|---|---|---|
| **Standalone** (built, `partial`) | `EXP DESK:RATES & CPTY:CP-X`: the desk's trades in each touched set treated as if they were the set ([EXPOSURE_GUIDE section 4](../guides/EXPOSURE_GUIDE.md#4-scopes)) | The exposure the desk would have alone; ignores netting with other desks and splits collateral wrongly | seconds per touched set (a lake read) |
| **Allocated** (proposed) | the counterparty's exposure allocated to its trades and summed by desk: on each path and date, the netted collateralised exposure E of the set shared among its trades in proportion to their value where it is positive (an Euler-style allocation of EE and EPE, which are additive across sets; PFE allocated at the PFE path and date) | The desk's part of the bank's real exposure; desks add up to the counterparty | one pass over a set's trade paths (already read for contributions) |

**Design.** A `by=desk` grouping on the exposure engine's contributions (the engine already computes contributions by trade for a scope;
[EXPOSURE_GUIDE section 28](../guides/EXPOSURE_GUIDE.md#28-open-items) notes their cost at a large bank's size); a new answer
`GET /api/v1/exposure/scope/contributions?scope=DESK:<id>&by=counterparty&measure=EPE` (proposed) that returns, per counterparty, the
desk's allocated EPE and peak-PFE share, and the counterparty's credit-limit use. The **Largest counterparty** tile then reads "CP-X ·
desk's EPE 41.2m of 118.0m · limit use 62%" and keeps the positive-MtM figure as a second line, named.

**Data needs.** Held: `exposure-path` per trade, the CSAs, the netting sets. **Size** M. **Dependencies:** exposure contributions by
trade; pruning trade-path reads by netting set (planned, EXPOSURE_GUIDE section 28) for size. **Tests:** desks' allocated EE and EPE of a
counterparty add to its EE and EPE to the cent; a desk alone in a set gets the whole set; a desk whose trades all have negative value
gets zero; the standalone figure is shown beside it only when asked, labelled.

### 5.3 TL3: P&L against VaR from the bank's P&L

**Today.** The card reads the official history: each day's hypothetical P&L (`HYPO_PNL`, the node's P&L under the newest scenario day of
its date's set, derived by Drishti) against the VaR before it ([VAR_GUIDE section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting));
left out without official history.

**Design.** When the bank's daily P&L per desk arrives (2.9.8 row 3: actual from finance, hypothetical from the front office), the card
shows **actual** and **hypothetical** against VaR, with Drishti's own derived figure named as **risk-theoretical**; exceptions counted
separately per series (2.9.8 row 4's backtesting engine). The card's default series is a site setting (proposed); a desk without a P&L
row on a date shows "—" for the date, never zero. With no official history the card stays left out, as today: a replay is not a day's
P&L. **Size** S after 2.9.8. **Tests:** crafted P&L rows give the right exceptions per series; a missing day is a gap; the card never
mixes series.

### 5.4 TL4: a P&L explain

**Today.** P&L today and the movers; the stored `pnl-explain` documents are a generator's and disagree with themselves (F19,
[COMPUTED_ATTRIBUTES.md section 4.10](../architecture/COMPUTED_ATTRIBUTES.md#410-pnl-explain-pnl)); [PNL.md](../contracts/PNL.md) proposes
the engine.

**TL4a, by trade events (S, no new data).** From the two dates' valuations and the trade events the landing already reads: split P&L today
into **continuing trades** (MtM today − MtM yesterday), **new** (MtM today; upfront and fees when the trade carries them), **ended**
(− MtM yesterday, plus final cash when the `EVENTS[]` carry it), **amended** (a trade whose terms changed: its change separated). The parts
add up to P&L today exactly. The card reads "P&L +2.4m = continuing +1.9m · new +0.6m · ended −0.1m · amended 0.0m". This is PNL.md's
step 4 without the risk-based step 2; it is derived from inputs held, so it stays computed, never stored.

**TL4b, by risk factor (L).** PNL.md as proposed: per-trade sensitivities at T−1 (the FRTB.md `sensitivity` table), market moves per risk
factor (`market-move`), trade events (`pnl-event`); explained = Σ delta × move + ½ gamma × move² + vega × vol move + theta; unexplained
and its tolerance. The card shows the explain by risk class and the trades with the largest unexplained P&L; the Desk head shows it by
book. **Data needs:** sensitivities per trade (section 6); market moves can be **derived** from the market-data pack's history for the
factors it holds (the Market lens's day-over-day changes, MARKET_LENS.md section 5.5). **Size** L. **Tests:** PNL.md section 15's worked
example; continuing + new + ended + amended = actual to the cent; a trade without sensitivities counts as unexplained, said.

### 5.5 TL5: stressed VaR, 10-day and confidence on the landings and the check

When `wip/var-horizons` merges (plan row 2b) and stressed VaR is built (row 2c), the Trader desk's and Desk head's VaR tiles follow the
person's **Horizon**, **Confidence** and **Normal / Stressed** choice from the VaR page (kept per person); **limit use is shown only when
the answer's horizon and confidence are the limit's own**, otherwise "not comparable" (VAR_GUIDE section 18.6 on the branch); the
new-trade check answers at the official horizon and confidence and says so; the P&L-against-VaR card stays 1-day (backtesting is 1-day).
**Size** S. **Tests:** the tile matches the VaR page for each choice; limit use empty when not comparable; the check unchanged.

### 5.6 TL6: my breaches to explain

**Depends on** RO2 of the oversight analysis (a breach record per limit and date created by the official run, the desk's explanation,
review, approval, escalation). The Desk head's **Breaches** tile opens **my open breaches**: each with its limit, use, days open, status
and an **Explain** action (cause, action, expected duration, then free text) for a person with the power (proposed `breach.explain`);
the Trader desk shows a banner when one of my books has an open breach awaiting explanation. The Trading lens **explains**; the Oversight
lens **reviews and approves** (RISK_OVERSIGHT.md section 5.2 on its branch). **Size** S after RO2. **Tests:** a crafted breach appears for
the desk's people and not others; the explanation is recorded with who and when; a person without the power sees it read-only.

### 5.7 TL7: sensitivity ladders and limits on my books

**Depends on** per-trade sensitivities (the FRTB.md `sensitivity` table) and RO3 and RO4 of the oversight analysis (one limit catalogue;
sensitivities summed by bucket for any scope). On the Trader desk: a **DV01 ladder** (currency × tenor bucket) of my books, each bucket
against its limit, every cell printing its value; on the Desk head: the worst bucket use per book. The DV01 tile becomes the ladder's
total with a link. **Size** M after RO4 (the landing part). **Tests:** the ladder's total equals the DV01 tile; a crafted bucket limit
breached; two-sided limits.

### 5.8 TL8: the check on the real trade

**Now (S).** Say what the check is: the answer names the trade copied, the factor, and a **fidelity** line: *exact for a linear product
with the same terms; approximate for options and different terms* (from the product group of the copy and the request; a request whose
tenor or strike the person typed differs from the copy's). Offer the product's tenor and maturity bucket as a choice so the copy picked is
the nearest.

**Later (L, parked).** Price the trade itself over the scenarios through QLRisk once its API is declared stable (VAR_GUIDE section 8.4's
V4: the reprice check and *Book via write-back…*), and over the exposure paths through the exposure engine's upstream what-if
(`upstreamRef`, not built, EXPOSURE_GUIDE section 28). **Tests:** the fidelity label per product group; a priced trade's VaR equals the
scaled copy's for a linear product of the same terms.

### 5.9 TL9: intraday P&L

**Today** P&L today uses end-of-day valuations; intraday VaR is live from the PV topics (VAR_GUIDE section 8.4). **Design:** where the
bank's intraday stream carries each trade's **base PV** (the `scenario-pv` contract has `BASE_PV` per row,
[VAR_GUIDE section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency-as-built-294)), the
intraday P&L of my books is Σ (intraday base PV − yesterday's close MtM) over the trades updated, plus the closing P&L of the others,
labelled **intraday estimate** with the time of the latest update. Where the stream carries P&L vectors only, the tile stays end-of-day.
**Size** M. **Data:** intraday base PVs per trade. **Tests:** a crafted intraday batch moves the tile by its base PV change; no base PV:
no intraday figure, said.

### 5.10 TL10: watch my books

A **Watch** action on the Trader desk and Desk head that creates, for the person, alert rules on numbers for each of their books' VaR
limit use at `trading.near-limit` and at 100% (reusing the alert machinery: inbox, email, chat, hysteresis and cooldown,
[USER_GUIDE.md](../guides/USER_GUIDE.md#alerts-on-numbers)), on the official run and, when the engine supports it, on intraday updates.
One click, removable, visible in Alerts. **Size** S. **Tests:** the rules created match the books; a crafted use above the threshold
notifies once with cooldown.

### 5.11 TL11: month-to-date and year-to-date P&L, stop-loss

From the bank's daily P&L per desk (2.9.8 row 3; per book or trade when delivered, so any scope): month-to-date and year-to-date sums
(derived, a sum over dates), against the desk's **stop-loss** limit from the limit catalogue (RO3, RO5 of the oversight analysis). A tile
on the Desk head ("MTD −3.1m · stop-loss 10m · 31%") and a line on the Trader desk. **Size** S after 2.9.8 row 3 and RO5.

### 5.12 TL12: limit use statistics on the Desk head

Per book over the month and the year, from the official records (built): average, peak, days at or above `near-limit`, days in breach,
the date of the peak. A card under **Limit use by book**, and the same figures as a BI report for the limit review. Computed when asked
from the official table, never stored. **Size** S. **Tests:** the statistics over a crafted month; a missing record counted as missing,
never as zero.

### 5.13 TL13: "my" from the directory

Today "my" is configuration, a matching trader record or View as. **Design:** a fourth source, first in order when configured: the single
sign-on token's groups or a claim (`trader_id`, `desk`), mapped by a rule (`trading.people-from: {claim: trader_id}` or
`{group-prefix: desk-}`, proposed); the trader record stays the fallback; View as stays for administrators. It still never widens access:
scoping chooses books, roles decide what is read. **Size** M (with the OIDC mapping tests). **Depends on** single sign-on in production;
the RBAC work that wires `lens.roles` later.

---

## 6. Data needed

| Data | Grain and cadence | Needed by | Source and note |
|---|---|---|---|
| Trade valuations (MtM, DV01, notional) on consecutive business dates | per trade per date | P&L today, movers, by trader (built) | the bank's valuation system; `NOTIONAL`'s unit is disputed (F10, [COMPUTED_ATTRIBUTES.md section 6](../architecture/COMPUTED_ATTRIBUTES.md#6-findings-duplicates-and-inconsistencies)) |
| Trades booked on their date, with `SOURCE_UPDATE_TIME`, `TRADE_ACTION`, `TRADE_STATUS` | per trade, intraday or end of day | Trades today (built), TL1, TL4a | the booking systems; the demo needs TL1 |
| Actual and hypothetical P&L | per desk (better per book or trade) per date | TL3, TL11 | product control and finance; the 2.9.8 row 3 contract |
| **Position sensitivities** (delta by risk factor and bucket: DV01 by currency and tenor, CS01 by curve and tenor, vega by underlying and expiry, FX delta by currency; gamma where options matter) | per trade per date | TL4b, TL7 | the bank's risk engine; FRTB.md's per-trade `sensitivity` table (proposed); RO4 of the oversight analysis (RISK_OVERSIGHT.md section 4.4 on its branch) designs the aggregation. Today only per-trade `DV01` and FRTB buckets per book are held |
| Market moves per risk factor | per factor per date | TL4b | derivable from the market-data pack's history for the factors it holds; the rest from the bank |
| Intraday base PVs | per trade per update | TL9 | the intraday PV stream (`scenario-pv`'s `BASE_PV`) |
| Limits beyond VaR (stop-loss, sensitivity buckets, notional) | per limit, on change | TL7, TL11 | the bank's limit system (RO3) |
| Who is who: user → trader or desk | per person, on change | "my" (built by configuration), TL13 | the directory (single sign-on groups or claims) |
| Exposure paths and CSAs | per trade per path and date | the check (built), TL2 | the exposure simulator (built contract) |

No public data is needed. None of this data leaves the bank.

---

## 7. Relationship to the other lenses

| Lens | What Trading shares with it | What Trading deliberately hides | When a Trading user should switch |
|---|---|---|---|
| **Drishti Risk** ([RISK_LENS.md](RISK_LENS.md)) | The same five packs and engines, the VaR and exposure explorers, `var-limit`, the official records | The scenario inputs (`SPV`, `FXS`, `SSET`, `VOFF`), exposure runs and paths (`XRUN`, `XPATH`, `XOFC`), collateral (`COLL`, `MC`), agreements, the data map, the bank-wide Risk map | To see the bank or another desk, the hierarchies, a node's official record, or exposure in depth |
| **Drishti Market** ([MARKET_LENS.md](MARKET_LENS.md)) | The market data commands a trader reads (`CRV`, `FX`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`) | The Quality checks, fixings, vol cubes, correlations, the moves in σ | When a P&L move looks like a data problem: is the curve stale or jumping? |
| **Drishti World** ([WORLD_LENS.md](WORLD_LENS.md)) | Nothing: no pack in common | Macro economics (left out by design, `trading.yaml`) | For context: an economy's data, a company's filings, a counterparty's legal-entity group |
| **Drishti Oversight** (proposed; RISK_OVERSIGHT.md on its branch) | The limit use by book over N days, the P&L-against-VaR card, `near-limit`, "my" turned into **coverage** | Governance actions other than explaining a breach | Never for a trader; a desk head sees their breaches' review there |

**Shared design rules.** "My" chooses scope and never widens access (the same rule the Oversight analysis reuses as coverage); a figure
the data cannot give is left out or labelled, never invented; the engines are unchanged under every lens
([EDITIONS.md section 11](../architecture/EDITIONS.md#11-later-editions)).

---

## 8. A roadmap

The rows below are added to the plan's [Lens product candidates (not started)](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started)
section. Ordered by value for the persona and by what they need first.

| Order | Row | Why here | Size | Depends on |
|---|---|---|---|---|
| 1 | **TL1** new trades on the demo data; searchable update time | The lens's first tile reads 0 "new" on the demo and times out on a large lake | S | seeds plan |
| 2 | **TL4a** P&L by trade events | Explains most of a day's surprises with no new data | S | — |
| 3 | **TL5** horizons, confidence, stressed VaR on the landings | Follows 2.9.7 rows 2b and 2c at once | S | 2b, 2c |
| 4 | **TL12** limit use statistics; **TL10** watch my books | Small, read only what is held | S each | — |
| 5 | **TL8** fidelity labels on the check (now) | The check's honesty for options | S | — |
| 6 | **TL2** the desk's share of counterparty exposure | Replaces the positive-MtM stand-in | M | exposure contributions |
| 7 | **TL3** P&L against VaR from the bank's P&L; **TL11** MTD, YTD, stop-loss | Need 2.9.8's P&L feed | S each | 2.9.8 rows 3, 4; RO5 |
| 8 | **TL6** my breaches to explain | The first line's half of RO2 | S | RO2 |
| 9 | **TL13** "my" from the directory | For a real deployment | M | SSO groups |
| 10 | **TL9** intraday P&L | Needs the bank's intraday base PVs | M | data |
| 11 | **TL7** sensitivity ladders and limits | Needs per-trade sensitivities | M | RO3, RO4 |
| 12 | **TL4b** P&L explain by risk factor; **TL8** pricing the real trade | The largest; QLRisk parked | L each | sensitivities; QLRisk |

---

## 9. Decisions for the product owner

1. **New trades on the demo (TL1).** Regenerate the trading test data with trades booked on their date (in the seeds work), or keep the
   data and leave "new" at 0 with its note.
2. **The largest counterparty (TL2).** Allocated exposure (recommended: desks add up to the counterparty), the standalone `partial`
   scope, or keep positive MtM.
3. **The P&L series on the P&L-against-VaR card (TL3).** Hypothetical by default (the regulators' first test), actual, or both.
4. **Stress in Trading.** Stays hidden (as for Drishti Risk, product owner 2026-10-30), or the stored stress results of my desk
   (`STR`, `SSPNL`) shown read-only on the Desk head.
5. **The scenario matrix (`SMAT`) in the Trading lens** once it merges: add it to the edition's commands (recommended: "is this tail
   scenario new?" is a desk question), or leave it to Risk.
6. **TL4a before PNL.md.** Ship the trade-events split now as "P&L by trade events", or wait for the full explain.
7. **One near-limit threshold.** Trading's 0.8, the VaR explorer's 75% and 90%, and the proposed catalogue's `WARNING_AT`: unify on the
   limit's own warning level with a site default (as the oversight analysis's decision 8).
8. **Fidelity of the check (TL8).** Show the fidelity line now (recommended), or wait for real pricing.
9. **Explain a breach from Trading (TL6).** The desk explains in the Trading lens and the second line approves in Oversight
   (recommended), or both in Oversight.
10. **"My" from the directory (TL13).** Claims, groups, or configuration only.

---

## 10. Glossary

| Term | In plain words |
|---|---|
| **First line** | The desks that take risk and earn P&L |
| **Book** | A set of trades a trader manages and is measured on |
| **Desk** | A team of traders and their books under a desk head |
| **MtM** (mark to market) | What a trade is worth today at market prices |
| **P&L today** | In this lens: the change in MtM of my trades since the business day before |
| **Actual P&L** | The day's real P&L from finance, with cash, fees and reserves |
| **Hypothetical P&L** | The P&L of yesterday's positions at today's prices, with no new trades |
| **P&L explain** | Splitting a day's P&L into its causes: market moves by factor, time, new and changed trades, and what cannot be explained |
| **VaR** | The loss that a day's market move should exceed only 1 day in 100, from the last two years of moves |
| **Limit use** | VaR divided by its limit; above 100% is a breach |
| **Near limit** | Use at or above a warning share of the limit (0.8 here) |
| **DV01** | The P&L for a one basis point fall in rates |
| **Sensitivity ladder** | A sensitivity split into buckets (currency × tenor), each against its own limit |
| **Stop-loss** | A limit on losses over a month or a year |
| **PFE** | A bad-case future amount a counterparty could owe us, at a high confidence |
| **EPE** | The average positive exposure over the first year: what a counterparty owes us on average |
| **Netting set** | The trades with one counterparty under one agreement, whose values offset |
| **Allocated exposure** | A counterparty's exposure shared out among the trades (and so the desks) that cause it, adding up to the whole |
| **Pre-deal check** | Asking, before dealing, what a trade would do to a limit |
| **Blotter** | The list of trades booked, newest first |
| **View as** | Choosing whose books the landing shows; it never changes what you may read |
| **Lens** | One person's focused view of Drishti, opened by a link; it hides, it does not secure |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
