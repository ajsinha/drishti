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
# Drishti Market: what the market-data team needs, what the lens gives it today, and what comes next

**Status: analysis and product description: built parts as built, the rest proposals.** Every "built" or "partly" statement cites the
document, section or file it was read from on 2026-10-31 (develop at b22ff3850). Everything in sections 5 and 8 is a proposal; the rows of
section 8 are candidates in the [implementation plan](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started), not
started, except where a 2.9.9 row already names the work (cited as such).

**The request (product owner, 2026-10-31):** "We should have similar and fuller standalone document for other lenses too", after the
market risk oversight analysis (RISK_OVERSIGHT.md, on branch `wip/risk-oversight`, merging in 2.9.7). This is the Market lens's.

**Read with:** [EDITIONS.md section 13](../architecture/EDITIONS.md#13-drishti-market-built-2026-10-29-for-297) (the lens as built),
[EDITIONS_GUIDE.md section 11](../admin/EDITIONS_GUIDE.md#11-drishti-market) (how to run it), the edition file
[`config/editions/market.yaml`](../../config/editions/market.yaml), the [market-data pack README](../../config/packs/market-data/README.md)
(every kind and field), [HISTORY_QUERIES.md](../architecture/HISTORY_QUERIES.md) (an item over time), [VAR_GUIDE.md](../guides/VAR_GUIDE.md)
(how market data reaches VaR: through the upstream P&L vectors) and the plan's
[2.9.9 rows 1 and 2](../architecture/IMPLEMENTATION_PLAN.md#299-moved-from-298-on-2026-10-30-candidates-not-started). The sibling lens
documents: [RISK_LENS.md](RISK_LENS.md), [TRADING_LENS.md](TRADING_LENS.md), [WORLD_LENS.md](WORLD_LENS.md).

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
**Planned (row)**: a row of the implementation plan names it. **Not built**: neither built nor planned; section 5 sketches it and section 8
proposes a row.

---

## 1. Who uses it

Drishti Market is "the bank's own market data in one product: curves, fixings, FX, volatility surfaces and cubes, credit curves, equities,
inflation, commodities, bonds and correlations, the inputs that feed risk and valuations. No trades and no VaR"
([`config/editions/market.yaml`](../../config/editions/market.yaml), its header).

| Persona | Accountable for | What they need from a system |
|---|---|---|
| **Market data analyst** (the market-data team; in many banks inside risk technology or a "market data management" function) | The end-of-day market data set that the valuation and risk batches use: complete, on time, consistent, correct; curve and surface construction inputs; fixings; corrections and their audit | Every item's latest value and history; checks that catch stale, missing, inconsistent and jumping data before the batch; who uses each item |
| **Valuation control** (product control's independent price verification, IPV) | That the marks the desks use agree with independent sources within tolerance; the month-end IPV report and reserves | Two sources side by side, the differences against tolerance, the trades affected |
| **Market risk methodology / time series** | The historical moves that become VaR's 509 scenario days: clean, complete, proxies documented | Long histories, jumps and gaps flagged, the proxies listed, the impact of a bad day on VaR |
| *Also served*: a **quant** or **structurer** reading a curve or a surface | Pricing inputs | The pages, the forms, the history |

**What they do not own:** the trades, the VaR and the limits (the Risk and Trading lenses); the vendor contracts (procurement), though
licensing shapes what they may show (section 6).

### 1.1 A day, a month and a year

| When | What the team does |
|---|---|
| **Before the batch** (end of day, 17:00 to 20:00) | The official close snap arrives from vendors and internal sources; curves and surfaces are built; the team checks completeness, staleness, arbitrage and jumps; corrects or proxies; releases the set to the valuation and VaR batches |
| **The morning** | Reviews overnight exceptions; answers the desks' and risk's questions ("why did my P&L move: is the curve right?"); re-releases a corrected item when needed |
| **Intraday** | Monitors live sources (for intraday risk and pricing); onboards new instruments and curves |
| **Month-end** | IPV: compares desk marks to independent sources; computes reserves; reviews proxies and long-stale items; reports data quality statistics |
| **Year** | Vendor review (coverage, cost, licences); the VaR history review with methodology (gaps, proxies, the stress window's data) |

---

## 2. Their questions: the morning, intraday, the close, month-end

### 2.1 The close (the batch window)

| Question | Drishti today | Gap |
|---|---|---|
| *Did everything arrive?* | **Partly**: **Stale** check per curve and surface (`AS_OF` older than the business date by more than `quality.stale-days`, 2 business days) and per other kind by its latest load ([EDITIONS.md section 13](../architecture/EDITIONS.md#13-drishti-market-built-2026-10-29-for-297), Quality); late loads by expectation ([DATA_LOADS.md section 8](../guides/DATA_LOADS.md#8-expectations-and-late-data)) | Completeness against an **expected list** of ids per date (a new curve that never arrived is not stale, it is missing): section 5.8 |
| *Is anything internally inconsistent?* | **Built**: **Gaps** (a curve missing a tenor every other curve of its type has), **Discount factors and forwards** (a DF that does not fall with maturity or is outside (0, 1]: critical; a negative forward: warning), **Vol surfaces** (a vol at or below zero; ATM total variance falling with expiry on FX and equity surfaces) | Calendar and smile checks on the commodity, cap and swaption grids (2.9.9 row 2); section 5.4 |
| *Did anything jump?* | **Partly**: the desk's **Biggest moves** ranks the latest move in σ of each item's own daily moves (a "jump" at 3σ or more), but only as a ranking on the desk, not as a Quality check | σ-jump as a check (2.9.9 row 2); section 5.2 |
| *Do two sources disagree?* | **Not built**: the lake holds one source per kind | Section 5.3 (2.9.9 row 2) |
| *If this is wrong, what does it cost?* | **Not built**: the tile reads *Impact on VaR and valuations comes in a later release* | VaR at stake (2.9.9 row 1); section 5.1 |

### 2.2 The morning

| Question | Drishti today | Gap |
|---|---|---|
| *What moved overnight, and is it real?* | **Built**: **Biggest moves** over 21 business days in σ (10 or more moves held), else largest moves against the day before, else "no history" ([EDITIONS.md section 13](../architecture/EDITIONS.md#13-drishti-market-built-2026-10-29-for-297)); a day-over-day change on tiles only when both days hold real data | Moves per pillar (the desk checks `TEN_Y`, `SPREAD_5Y`, `MID`, `FRONT`, `LEVEL`); a curve's shift, twist and butterfly (section 5.5) |
| *A desk says its P&L is wrong because of a curve: which trades use it?* | **Not built**: no link from an item to the trades and VaR inputs that use it | Section 5.1, "Used by" |
| *What did we correct, when and why?* | **Partly**: an item's history over dates ([HISTORY_QUERIES.md](../architecture/HISTORY_QUERIES.md)); the load's provenance | Corrections as records with a reason (section 5.7) |

### 2.3 Intraday

| Question | Drishti today | Gap |
|---|---|---|
| *What is the live level?* | **Partly**: the lake holds end-of-day documents; live connectors exist in the platform but the market-data pack is served from the demo lake (`market-store`) | Live market data is the bank's systems' job; Drishti shows what is loaded. Not a gap to fill in the lens |
| *Watch this curve for me* | **Built**: monitors and alerts on numbers are on (`market.yaml` `features`) | — |

### 2.4 Month-end and the year

| Task | Drishti today | Gap |
|---|---|---|
| IPV: marks against independent sources | **Not built** (one source) | Section 5.3 with a tolerance table and a report |
| Proxy review: which instruments have no data of their own | **Partly**: each P&L vector and scenario-PV row says `ok`, `proxied` or `failed` ([VAR_GUIDE section 15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade-as-built)), but in the Risk packs, not the Market lens | A proxy list in the Market lens (section 5.9) |
| Data quality statistics over the month | **Not built**: the checks run when the Quality landing opens; nothing keeps their results | Section 5.7 |
| Market data in BI | **Not built**: the pack ships no BI dataset; BI offers its themes only (`market.yaml` `bi`) | Section 5.6 |
| The VaR history review | **Partly**: an item's history; the moves in σ | Jumps across the whole history; the VaR at stake of a bad historical day (section 5.1) |

---

## 3. Needs by area, with status

### 3.1 Coverage: what the lens shows

| Need | Status | Evidence |
|---|---|---|
| Rates: IR curves, repo, swaption and cap vols, fixings, inflation | **Built**: `CRV`, `REPO`, `IRV`, `CPV`, `FIX`, `INF`, `INFC` | `market.yaml` `commands.allow`; [market-data README](../../config/packs/market-data/README.md) |
| FX: spot, forwards, vol surfaces | **Built**: `FX`, `FXF`, `FXV` | |
| Credit and bonds | **Built**: `CDS`, `BND` | |
| Equities and commodities, their curves and vols, dividends | **Built**: `EQ`, `EQX`, `DIV`, `EQV`, `CMD`, `CMDC`, `CMDV` | |
| Correlations, computed on request from history | **Built**: `CORR`, `CORRC`, `EQB` | |
| Issuers and holiday calendars | **Loaded, hidden**: core's commands (`CAL`, `ISS`) are not in the allow-list; calendars are used by the checks (business days) | [EDITIONS.md section 13](../architecture/EDITIONS.md#13-drishti-market-built-2026-10-29-for-297); section 9, decision 6 |
| Query by form | **Built** (`forms: on`) | |
| History of an item | **Built** for dated kinds; the pack's `history:` block shapes equities, indexes, FX spots, IR and credit curves, commodities, bonds, inflation | `config/packs/market-data/pack.yaml` (`history:`) |

### 3.2 Quality checks

| Check | Status | Evidence or row |
|---|---|---|
| Stale (`quality.stale-days`) | **Built** | EDITIONS.md section 13, check 1 |
| Gaps (missing tenor against the type) | **Built** for types of three or more | check 2 |
| Discount factors monotone and in (0, 1]; negative forwards | **Built** | check 3 |
| Vol surfaces: non-positive vols; ATM total variance falling with expiry (FX, equity) | **Built** | check 4 |
| Calendar checks on commodity, cap and swaption grids; smile (butterfly) checks | **Planned** (2.9.9 row 2) | section 5.4 |
| Jumps over n σ as a check | **Planned** (2.9.9 row 2) | section 5.2 |
| Two sources disagreeing | **Planned** (2.9.9 row 2), needs a second source per kind | section 5.3 |
| Completeness against an expected list | **Not built** | section 5.8 |
| Quality results kept, with acknowledgements | **Not built** | section 5.7 |

### 3.3 Impact and lineage

| Need | Status | Evidence or row |
|---|---|---|
| Who uses each item (trades, books, VaR inputs) | **Not built**; trades carry `LEGS[].INDEX` (a benchmark name) and `DISCOUNT_INDEX`, not market-data ids ([trading pack README](../../config/packs/trading/README.md)); IR curves carry an `INDEX` field ([market-data README](../../config/packs/market-data/README.md)) | section 5.1 |
| VaR at stake of a flagged item | **Planned** (2.9.9 row 1) | section 5.1 |
| Valuations at stake | **Planned** (2.9.9 row 1: "the valuations of which trades") | section 5.1 |
| The market-data line of a data-trust card | **Proposed** as RO13 of the oversight analysis (reusing the Quality checks) | RISK_OVERSIGHT.md section 4.6 on its branch |

### 3.4 Analytics

| Need | Status | Evidence or row |
|---|---|---|
| Moves in σ of their own history | **Built** (desk ranking) | EDITIONS.md section 13 |
| A curve today against a week ago | **Built** when the history holds that day | USD curves card |
| Shift, twist, butterfly of a curve's move | **Not built** | section 5.5 |
| Correlations and betas | **Built** (`CORR`, `EQB`) | |
| Market data BI | **Not built** (no dataset) | section 5.6 |

---

## 4. The landings as built

One template, `home/market_landing.html`, with `market-landing.js` and `market-landing.css`, a **violet accent**. The figures are worked out
by `drishti-console/core/market_landing.py` from the pack's own answers (search, raw documents, the business date, one field's history
through `/api/v1/history/{kind}/{id}/series`), read as the person, cached two minutes per person, and served by `/api/home/market/desk`,
`/api/home/market/moves` and `/api/home/market/quality` (`404 DRS-4023` unless the edition or lens shows `CRV`)
([EDITIONS.md section 13](../architecture/EDITIONS.md#13-drishti-market-built-2026-10-29-for-297)).

### 4.1 A: Market desk (`desk`, the default)

| Tile or card | What is real | Left out, and why |
|---|---|---|
| **USD OIS 10Y** | `CRV-USD-OIS`'s `TEN_Y` | A change only when the history holds real data for the latest day and the business day before (a point whose data date is its date, not carried) |
| **USD 2s10s** | `CRV-USD-PROJ`'s `SLOPE_2S_10S` | as above |
| **EUR/USD 1M ATM vol** | `FXV-EURUSD`'s `ATM_1M` | as above |
| **CDS 5Y, median** | the median of every credit curve's `SPREAD_5Y` | as above |
| **Data exceptions** | the Quality checks' count; opens C | — |
| **USD curves** | OIS zero rates today against five business days before (dashed) when held, and projection | Without that history: OIS against projection |
| **Biggest moves** | each curve's `TEN_Y`, credit's `SPREAD_5Y`, FX's `MID`, commodity's `FRONT`, index's `LEVEL` over 21 business days, in σ when ten or more moves are held ("jump" at 3σ) | Less history: largest moves against the day before; none: says so and ranks nothing |
| **Quick cards** | `CRV CRV-USD-OIS`, `FXV FXV-EURUSD`, `IRV IRV-USD`, `FIX FIX-SOFR` | — |

### 4.2 B: Explorer (`explorer`)

A big command line; the chips of `home.chips` (`CRV CRV-USD-OIS`, `CRV CRV-CAD-GOVT`, `FXF FXF-EURUSD`, `FXV FXV-USDJPY`, `IRV IRV-USD`,
`CDS CDS-SOLARIS`, `INFC INFC-USCPI`, `CMDC CMDC-WTI`, `CORR CORR-G10FX`, `DATAMAP`); counts by asset class (each command's `matched`); the
commands grouped by asset class with their counts; Recently opened (Market's commands only), Knowledge, Data map, Learn.

### 4.3 C: Quality (`quality`)

The checks the data supports, by severity, worst first, each item opening its command (section 3.2's built rows). Tiles: **Critical**,
**Warnings**, **Clean** (the share of items checked with no exception) and, in place of the mock-up's **VaR at stake**, the note *Impact
on VaR and valuations comes in a later release*. A **Checks run** card gives each check's items and flags.

**On the committed demo data every check is clean**: the market data is consistent and its dates move with the lake's business dates
(EDITIONS.md section 13). The checks are proved on crafted bad data in `test_market_landing.py` (stale, gap, discount factor, forward and
surface problems).

### 4.4 What the builder left out, and why

- **VaR at stake** and the Impact card: no link from market data to the VaR and valuation inputs that use it (2.9.9 row 1).
- **Jumps over n σ as a check**: the desk ranks moves in σ; turning that into a check with thresholds and severities is 2.9.9 row 2.
- **Two sources disagreeing**: one source per kind in the lake.
- **Calendar spreads on commodity, cap and swaption grids**: a different underlying per row, so the FX and equity rule does not apply.
- **Market data BI**: the pack ships no dataset; the edition keeps BI to `market-data-*` names so it never offers VaR or exposure items
  (`market.yaml` `bi`, its comment).

**A small finding.** The module docstring of `drishti-console/core/market_landing.py` still says the impact, jump and two-source checks
are planned in 2.9.8; the plan moved them to 2.9.9 (2.9.9 rows 1 and 2). A one-word fix for the next code change there.

---

## 5. Gaps, with design sketches

Drishti's rules ([COMPUTED_ATTRIBUTES.md section 1.2](../architecture/COMPUTED_ATTRIBUTES.md#12-the-rule-for-deciding-which)): market
data is **fundamental** (delivered under the market-data pack's contracts as JSON Lines into the lake); every check, move, impact and
link is **computed on demand**, never stored; an acknowledgement or a correction note is a **Drishti-owned** record, versioned; **BI is
the report layer**. Sizes: **S** days, **M** one to three weeks, **L** more than three weeks. Ids ML1 to ML10 are this document's.

| Id | Gap | Size | Depends on |
|---|---|---|---|
| ML1 | Usage map, "Used by", and VaR and valuations at stake | M (map, used by), then S (at stake) | a usage rule set; VaR contributions (built) |
| ML2 | σ-jump check | S | history (built) |
| ML3 | Two sources and tolerance (and IPV) | M | a second source per kind |
| ML4 | Grid calendar and smile checks | M | — |
| ML5 | Curve move decomposition (shift, twist, butterfly) and pillar moves | S | history (built) |
| ML6 | A market-data BI dataset | S | — |
| ML7 | Quality results kept, acknowledged and corrected, with statistics | M | content store (built) |
| ML8 | Completeness against an expected list | S | expectations (built for loads) |
| ML9 | The proxy list in the Market lens | S | P&L vector status (built) |
| ML10 | A historical-day check for the VaR window | M | ML2, ML1 |

### 5.1 ML1: who uses each item, and what is at stake

**Why it is not trivial.** Drishti's VaR does **not** read market data: an upstream risk batch revalues each trade under 509 historical
scenarios with the bank's market data and sends a P&L vector or scenario PVs per trade
([VAR_GUIDE section 1](../guides/VAR_GUIDE.md#1-the-question-var-answers)). So "VaR at stake" is not a recomputation with a corrected
curve; it is a measure of **how much of VaR and MtM rides on the trades that use the flagged item**. The plan's own words: "who uses each
flagged point (VaR scenarios, the valuations of which trades) and the VaR at stake if it is wrong, computed, never invented; needs the
market data linked to the VaR and valuation inputs that use it" (2.9.9 row 1).

**Step 1: the usage map (M).** A computed kind `market-data-usage` (proposed), keyed by market-data id and date, built from the trades
by **rules** (configuration), or from the bank's pricing configuration when it can supply one (fundamental, preferred):

| Market data | Used by a trade when (rule, illustrative) | Evidence the rule can use |
|---|---|---|
| IR projection curve `CRV-*` | a leg's `INDEX.NAME` equals the curve's `INDEX` | trades' `LEGS[].INDEX` ([trading pack README](../../config/packs/trading/README.md)); curves' `INDEX` ([market-data README](../../config/packs/market-data/README.md)) |
| IR discount curve | a leg's `DISCOUNT_INDEX`, else the leg currency's OIS curve (a site rule `CRV-<CCY>-OIS`) | `LEGS[].DISCOUNT_INDEX`, `LEGS[].CURRENCY` |
| Rate fixing `FIX-*` | a floating leg's index with a reset in the window | `LEGS[].CASH_FLOWS[].RESETS[]` |
| FX spot, forward curve, vol surface | the trade's currency pair (legs in two currencies, or an FX product) | legs' currencies; the FX kinds' `FX_SPOT`, `FX_FORWARD_CURVE`, `FX_VOL_SURFACE` links |
| Equity, dividend curve, equity vol | the trade's underlying equity or index | the equity kinds' `DIVIDEND_CURVE`, `EQUITY_VOL_SURFACE` links |
| Commodity curve and vol | the leg's index or commodity | `COMMODITY_CURVE`, `COMMODITY_VOL_SURFACE` |
| Credit curve | the reference entity or issuer | `ISSUER` |

The map answers per item: the trades, books and desks that use it, their count and **MtM** (from the valuations), the **pillars** a trade
is sensitive to (by maturity bucket, when sensitivities arrive). A **Used by** panel on each market-data page (CRV, FXV, CDS …) and a
column on the Quality list. Unmapped trades are counted as *unknown usage*, never dropped.

**Step 2: what is at stake (S after step 1).**

| Figure | How | Meaning |
|---|---|---|
| **MtM at stake** | Σ \|MtM\| of the trades that use the item (valuations, built) | Valuations exposed to an error in it |
| **Component VaR at stake** | Σ of those trades' component VaR in `BANK` (the engine's contributions, [VAR_GUIDE section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts), by trade, adding up to the bank's VaR) | How much of today's VaR rides on them |
| **Incremental VaR** | `VAR BANK` − `VAR BANK - TRD:<those trades>` (one more scope, exact) | VaR if those trades were wrong enough to remove; an upper bound of the error's reach |
| **Sensitivity at stake** (when per-trade sensitivities exist) | Σ sensitivity to the flagged pillar × the suspected error (the stale gap, or the jump less a normal move) | A first-order P&L of the error |

The Quality tile **VaR at stake** reads the largest component VaR at stake among critical items, with the item named; each item in the
list shows its MtM and component VaR at stake. All computed when the Quality landing opens, cached like the rest, never stored.

**A bad historical day** (ML10): when the flagged point is in the **history** (a past day's move), the question is different: is that
scenario day in today's tail? If the day is the VaR scenario (rank k), VaR at stake is `VaR − VaR without that scenario` (the next rank:
exact, a different rank of the same sorted vector); if it is in the ES tail, the same for ES. This assumes the upstream batch builds its
scenarios from the same history the lake holds: the answer says so.

**Data needs.** The trades and valuations (held); a usage rule set or the bank's pricing configuration (section 6); sensitivities by
pillar for the last row. **Tests:** crafted trades using a crafted curve appear under Used by; the component VaR at stake of a curve used
by every trade equals the bank's VaR; removing the scenario day that is the VaR scenario gives the next rank; an unmapped trade is
counted as unknown.

### 5.2 ML2: the σ-jump check

The desk already measures the latest move in σ of the item's own daily moves over 21 business days when ten or more are held
(`drishti-console/core/market_landing.py`, `moves`). The check: for every item and **every pillar** (a curve's `POINTS[].ZERO_RATE`, a
surface's grid, not only the headline field), flag |move| ≥ `quality.jump-sigma` σ (proposed setting, default 4 for a warning; 6 for
critical, configurable), measured in the item's natural unit (basis points for rates and spreads, log return for prices, vol points for
vols); fewer than ten moves held: not checked, counted as such. Two jumps of opposite sign on consecutive days (a spike that reverts) are
flagged as a **suspected bad print**. **Size** S. **Tests:** a crafted spike at 5σ is a warning, at 7σ critical; a spike-and-revert pair is
named; an item with nine moves is "not checked".

### 5.3 ML3: two sources, tolerance and IPV

**Data.** Each kind may carry a second source: the same id and date with a `SOURCE` (for example `INTERNAL`, `VENDOR-A`), delivered as a
second load of the same contract (a source column in the lake table) rather than a second kind. The IR curve already lists the quote
sources it was built from (`SOURCES`, [market-data README](../../config/packs/market-data/README.md)): the lake today holds one built curve
per id.

**The check.** Per field and pillar, |a − b| against a **tolerance table** by asset class and tenor (configuration: 0.5 bp for G10 OIS
pillars, 2 bp for credit spreads, 0.25 vol points for liquid FX vols, 0.1% for equity closes …); breaches as warnings, a multiple of the
tolerance as critical; the source the batch used marked. **IPV** at month-end: the same comparison of the desk's marks (the official close)
against the independent source, aggregated by desk with the **MtM at stake** of ML1, as a BI report signed off through the 2.9.8 framework.
**Size** M. **Depends on** a second source per kind (data). **Tests:** crafted pairs inside and outside tolerance; a missing second source
is "single source", never "agreed".

### 5.4 ML4: grid calendar and smile checks

| Grid | Calendar check | Smile check |
|---|---|---|
| **FX, equity surfaces** | Built: ATM total variance rising with expiry; extend to every delta or strike column | Call prices convex and decreasing in strike (no butterfly or call-spread arbitrage), computed with Black from each expiry's vols (FX surfaces converted from delta to strike first) |
| **Commodity vol surface** | Per contract (row), total variance rising with option expiry where a row has several expiries; never across rows (different underlyings) | As FX and equity, per row |
| **Cap vol surface** | Caplet (forward) variance positive after stripping the flat cap vols by maturity: σ²(T₂)·T₂ ≥ σ²(T₁)·T₁ as a first test, the stripped caplet vols ≥ 0 as the full one | Convexity in strike per maturity |
| **Swaption cube** | Along a **co-terminal** line (expiry + tenor fixed), total variance rising with expiry; not along a fixed tenor (a different underlying) | Convexity in strike per expiry and tenor |

Each finding names the cell and its neighbours. **Size** M. **Tests:** a crafted arbitrage per grid type; a clean demo stays clean.

### 5.5 ML5: curve moves decomposed, and moves per pillar

For a curve and two dates: the move per pillar in basis points; **shift** (the mean move), **twist** (2s10s or a slope regression),
**butterfly** (the belly against the wings), with the share of the move each explains; for a surface, the ATM level move and the skew
move. A card on the Market desk under Biggest moves, and a tab on the curve page. Derived from two dated documents; **S**. Also the input
TRADING_LENS.md section 5.4 needs for a P&L explain (market moves per factor).

### 5.6 ML6: a market-data BI dataset

Datasets `market-data-curves` (curve × pillar × date: zero rate, DF, forward), `market-data-surfaces` (surface × expiry × strike or
delta × date), `market-data-quality` (item × check × date, from ML7's records) and a model `market-data`; the edition already keeps BI to
these names (`market.yaml` `bi`). **Size** S. **Tests:** the dataset's rows equal the documents' pillars; the edition offers only these.

### 5.7 ML7: quality results kept, acknowledged and corrected

**Today** the checks run when the Quality landing opens; nothing is kept. **Design:** the official end-of-day run of the checks (after the
market data load, beside the VaR official run) writes **one immutable record per date** of every exception (check, item, pillar, value,
threshold) and the counts per check; a **Drishti-owned acknowledgement** per exception (accepted as correct with a reason, corrected with
the new load's batch id, proxied), versioned, four eyes optional; statistics over the month (exceptions per check and per source, time
to resolve) in BI (ML6). The record points at the load's batch id; it never copies a corrected value as truth. **Size** M. **Tests:** an
exception acknowledged stays acknowledged on reopen; a corrected load clears it and links the batch; the month statistics.

### 5.8 ML8: completeness against an expected list

An **expected list** per date: the ids each kind should have (configuration or derived: every id loaded on most of the last N business
days, or used by a live trade through ML1). Missing ids are a **critical** "missing" exception (distinct from stale). Reuses the load
expectations ([DATA_LOADS.md section 8](../guides/DATA_LOADS.md#8-expectations-and-late-data)) one level down: from "the kind loaded" to
"each id loaded". **Size** S.

### 5.9 ML9: the proxy list

The P&L vector and scenario-PV rows say `ok`, `proxied` or `failed` per trade ([VAR_GUIDE section 15.2](../guides/VAR_GUIDE.md#152-pnl-vector-one-pl-vector-per-trade-as-built)).
With ML1's usage map, the Market lens lists the **market data behind the proxies**: which items are missing so that trades are proxied,
the trades, their MtM and component VaR. Reading the VaR engine's status needs the market-risk pack's read access, not its commands: the
Market lens hides VaR, so the list is a read-only card, or the item lives in the Risk lens with a link (section 9, decision 3). **Size** S.

### 5.10 ML10: the historical-day check for the VaR window

The VaR window is the last 509 business days of moves. Run ML2 over the **whole window** of every item the VaR uses (by ML1), not only
the latest day; list suspected bad days with their **VaR at stake** (section 5.1: the scenario's rank today and the VaR without it). A
monthly task for methodology, a BI report, and the evidence for the stress window review (2.9.8 row 8 chooses the window; this checks its
data). **Size** M.

---

## 6. Data needed

| Data | Grain and cadence | Needed by | Source and licensing note |
|---|---|---|---|
| End-of-day market data, every kind of the pack | per id per business date | everything (built) | the bank's market data system and vendors. **Vendor licences** decide who may see vendor data in another application: a display licence for the users, sometimes a per-user count; derived data (a built curve) is usually freer than raw quotes. The bank's market-data team owns this; the Market lens should show source and licence per item (as the public-data packs' `PROVENANCE` does) |
| History of at least 21 business days (10 for σ) and the 509-day window for ML10 | per id per date | moves, ML2, ML10 | the same; the demo lake moves with its business dates |
| A second source per kind | per id per date | ML3 | a second vendor or the internal mark |
| A usage map (pricing configuration: which curves, surfaces and fixings each product or trade uses) | per trade or per product rule | ML1, ML9, ML10 | the bank's pricing system; otherwise Drishti's rules (section 5.1) |
| **Position sensitivities by pillar** (DV01 by curve and tenor, vega by surface point) | per trade per date | ML1's sensitivity at stake | the bank's risk engine; FRTB.md's per-trade `sensitivity` table (proposed); RO4 of the oversight analysis (RISK_OVERSIGHT.md section 4.4 on its branch) |
| An expected list of ids | per kind, on change | ML8 | configuration or derived |
| Tolerances by asset class and tenor | configuration | ML3 | valuation control's policy |

---

## 7. Relationship to the other lenses

| Lens | What Market shares with it | What Market deliberately hides | When a Market user should switch |
|---|---|---|---|
| **Drishti Risk** ([RISK_LENS.md](RISK_LENS.md)) | The market-data pack (Risk loads it; its commands are hidden in Risk except `FX`) | Trades, VaR, exposure, limits | To see the VaR a flagged curve touches, until ML1 brings the figure into Market |
| **Drishti Trading** ([TRADING_LENS.md](TRADING_LENS.md)) | The market data a trader reads (`CRV`, `FX`, `FXV`, `EQ`, `EQX`, `CMD`, `CMDC`, `BND`, `CDS`) | Fixings, cubes, correlations, the Quality checks | When a desk asks "is my P&L a data problem?": the desk opens Market to see the item's checks |
| **Drishti World** ([WORLD_LENS.md](WORLD_LENS.md)) | Nothing: no pack in common; the World lens's quotes are a public snapshot, not the bank's marks | — | For macro context of a move (an economy, a central bank's news topic) |
| **Drishti Oversight** (proposed) | The Quality checks become the market-data line of its data-trust card (RO13) | — | — |

**The boundary.** Market never shows trades or VaR (`market.yaml`: "No trades and no VaR"). ML1's figures are computed by the engines
and **shown** in Market as numbers attached to market data (MtM at stake, VaR at stake); the trades behind them open in Risk or Trading.
Section 9, decision 2.

---

## 8. A roadmap

The rows below are added to the plan's [Lens product candidates (not started)](../architecture/IMPLEMENTATION_PLAN.md#lens-product-candidates-not-started)
section. ML1 (with ML2 to ML4) is the plan's 2.9.9 rows 1 and 2 broken into buildable steps.

| Order | Row | Why here | Size | Depends on |
|---|---|---|---|---|
| 1 | **ML2** σ-jump check | Small; the desk computes it already | S | — |
| 2 | **ML1** usage map and Used by; then at stake | The lens's empty tile; the most asked question ("who uses this?") | M then S | usage rules; 2.9.9 row 1 |
| 3 | **ML8** completeness against an expected list | Missing is worse than stale | S | — |
| 4 | **ML4** grid calendar and smile checks | 2.9.9 row 2 | M | — |
| 5 | **ML7** quality results kept and acknowledged | Turns checks into a control with an audit trail | M | — |
| 6 | **ML6** market-data BI; **ML5** curve moves decomposed | Reports and the P&L explain's input | S each | ML7 for the quality dataset |
| 7 | **ML9** the proxy list | Needs ML1 | S | ML1 |
| 8 | **ML3** two sources and IPV | Needs a second source | M | data |
| 9 | **ML10** the historical-day check | Methodology's monthly review; feeds the stress window work | M | ML1, ML2 |

---

## 9. Decisions for the product owner

1. **VaR at stake: which figure leads** the tile: the component VaR at stake (recommended: adds up, cheap), the incremental VaR, or the
   MtM at stake.
2. **VaR figures inside Market.** Show ML1's at-stake numbers in Market while trades and VaR stay hidden (recommended), or link to Risk
   only.
3. **The proxy list (ML9).** In Market (read-only card) or in Risk with a link.
4. **Usage rules (ML1).** Drishti's configured rules now, the bank's pricing configuration when available (recommended), or wait for the
   bank's map.
5. **Jump thresholds.** 4σ warning and 6σ critical by default (proposed), or the desk's 3σ.
6. **Core's `CAL` and `ISS` in Market.** Show the holiday calendars and issuers (the checks use calendars), or keep them hidden.
7. **Quality records (ML7).** An official end-of-day quality record per date (recommended), or checks on open only.
8. **A second source for the demo (ML3).** A synthetic second source with small, labelled differences so the check shows something, or
   none until a bank provides one.
9. **Source and licence per item.** Show each item's source and licence on its page as the public-data packs do (recommended), or not.

---

## 10. Glossary

| Term | In plain words |
|---|---|
| **Market data** | The prices, rates, curves and volatilities the bank values trades with |
| **Curve** | Rates (or spreads, or prices) by maturity, built from market quotes |
| **Pillar** | One maturity point of a curve (the 10-year rate) |
| **Discount factor** | What 1 paid at a future date is worth today; must fall with maturity and stay between 0 and 1 |
| **Forward rate** | The rate implied today for a future period |
| **Vol surface** | Implied volatility by expiry and strike (or delta) |
| **Total variance** | Volatility squared times time; must rise with expiry for the same underlying |
| **Smile, butterfly** | How vol changes with strike; a butterfly check makes sure option prices are consistent across strikes |
| **Co-terminal** | Swaptions whose underlying swaps end on the same date |
| **Stale** | Not updated for longer than allowed |
| **Gap** | A missing pillar that similar curves have |
| **σ (sigma)** | The usual size of a day's move, from the item's own history; a 4σ move is very unusual |
| **Bad print** | A wrong value from a source, often a spike that reverts the next day |
| **IPV** | Independent price verification: checking the desks' marks against independent sources |
| **Tolerance** | How far two sources may differ before it counts as a disagreement |
| **Proxy** | A stand-in price or history used for an instrument without its own |
| **Usage map** | Which trades use which market data |
| **VaR at stake** | How much of today's VaR rides on trades that use a doubtful item |
| **Scenario day** | One past day's market move, applied to today's trades in VaR |
| **Lens** | One person's focused view of Drishti, opened by a link; it hides, it does not secure |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
