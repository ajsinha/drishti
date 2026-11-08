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
# Risk

*Knowledge · what the words mean and how Drishti's data connects them. The [Knowledge home](README.md) lists every page.*

The risk a bank's trades carry, in plain words: **market risk** (how much the trades could lose when markets move: VaR, expected
shortfall, contributions, backtesting), **counterparty credit risk** (how much the bank could lose if the other side of its trades
defaults: exposure, netting, collateral) and the **trades and products** both are measured on. Each part says what the idea is, how the
data connects, works an example on the demo data, lists questions to ask and the common confusions; the glossary of all three follows.

## Contents

- [1. Market risk](#1-market-risk)
- [2. Counterparty credit risk](#2-counterparty-credit-risk)
- [3. Trades and products](#3-trades-and-products)
- [4. Glossary](#4-glossary)
- [5. Where to go deeper](#5-where-to-go-deeper)

## 1. Market risk

### Market risk in plain words

A bank holds thousands of trades whose values move with markets: interest rates, exchange rates, share prices, credit spreads,
commodity prices. **Market risk** is the chance that those moves cost the bank money. The bank needs one honest answer to *how much
could we lose tomorrow?*, for the whole bank and for every desk, book and trader, so it can set limits, hold capital and see where the
risk sits.

Drishti answers it the way most banks' internal models do, by **historical simulation**:

1. An upstream risk batch takes the market moves of each of the last **509 business days** (about two years) and applies each one to
   today's market. It prices every live trade under each of those 509 scenarios and delivers, per trade, its **P&L vector**: 509
   numbers, what the trade would make or lose if tomorrow looked like each of those days.
2. Drishti **adds vectors**. The vector of any set of trades is the sum of its trades' vectors, day by day. A long and a short of the
   same thing cancel before anything else is done.
3. It **sorts** the set's 509 P&Ls from worst to best. The 6th worst loss is the **VaR** at 99% (1% of 509 is 5.09, rounded up to 6);
   the average of the 13 worst is the **expected shortfall** at 97.5%.

Because it only adds and sorts, Drishti can answer for **any set of trades** in milliseconds: the bank, a legal entity, a desk, a book,
a position, one trade, a system basket, a rule such as *every short EUR position*, or any union or difference of these. It never
reprices a trade; it never stores a VaR except when asked to keep one (a capture or the official end-of-day record).

Why a bank cares:

- **Limits.** Every desk and legal entity has a VaR limit; limit use above 100% is a breach.
- **Capital.** Regulators base market-risk capital on these measures (Basel's internal models use VaR and, under FRTB, ES).
- **Where the risk is.** Contributions show which desk, book or trade drives the number and which ones hedge.
- **Trust.** Backtesting checks that the model works: losses beyond VaR should happen about one day in a hundred.

### How the market risk data connects

```text
scenario-set  SSET HS-509          the 509 historical days, oldest first (day i is position i of every vector)
   │
pnl-vector    PNLV PNL-<trade>-HS-509  one per live trade: 509 P&Ls in USD, with the trade's LEGAL_ENTITY, DESK, TT_BOOK, COUNTERPARTY
   │  TRADE_ID ───────────────────────► trade  TRD <id>            (trading pack: the deal itself)
scenario-pv   SPV SPV-<trade>-HS-509   or its 509 scenario PVs and base PV in its own currency; Drishti makes the P&L
fx-scenario   FXS FXS-<ccy>-HS-509     each currency's rate today and under each scenario (else derived from fx-spot FX <pair>)
   │
   ▼  Drishti nets a book's trades by the pack's positions: rules
position      POS POS-<book>~<security | contract | trade>   (derived each date, never delivered)
   │  TT_BOOK
   ▼
book          BOOK BOOK-RATES-1 ──► desk DESK DESK-RATES ──► division ──► bank      hierarchy MGMT~<id>  (from the rows' columns)
                                └─► desk ──► legal entity LE LE-NY ──► bank        hierarchy LEGAL~<id> (from the rows' columns)
portfolio     PORT PF-<name>       HIERARCHY (a node), RULE (a condition on positions) or BASKET (picked positions and trades)

any scope text  (BANK, LE:LE-NY, DESK:RATES, PORT:PF-USD-RATES, CPTY:CP-ALDERSHOT, WHERE:<rule>, + - &)
   └─► var-result    VRES <scope>     computed when asked: VaR, ES, parts, path, backtest (never stored)
   └─► var-official  VOFF <node>      the official VaR and ES of every node, published once a day
   └─► what-if       (VAR basket)     the server's what-if with trades added or removed, asked from the basket's sheet

stored results from upstream engines (fundamental, not computed by Drishti):
   stress-scenario SCN SCN-<name> ──► stress-result STR STR-<scenario>-<desk>      (a desk's P&L under the shock)
   frtb-sensitivity FRTB FRTB-<desk>-<n>  (per book: delta, vega, curvature charges)
   pnl-explain PNL PNL-<desk>-<n>         (per book: actual, explained, unexplained)
   trade-valuation TVAL TVAL-<trade>      (per trade: MTM, 1-day P&L, DV01, its netting set)
```

The links are the packs' contract link columns (`config/packs/banking/market-risk/contracts/*.contract.yaml`): `pnl-vector.TRADE_ID → trade`,
`LEGAL_ENTITY → legal-entity`, `DESK → desk`, `TT_BOOK → book`, `COUNTERPARTY → counterparty`; `position.TRADE_IDS → trade`;
`var-limit.NODE_ID` names a node the server builds from the rows' columns; `stress-result.SCENARIO → stress-scenario`, `DESK → desk`;
`frtb-sensitivity.BOOK → book`; `trade-valuation.NETTING_SET → netting-set`, which is where market risk meets
[counterparty credit risk](#2-counterparty-credit-risk).

**Id conventions.** Desks `DESK-<name>` (`DESK-RATES`; a scope may say `DESK:RATES`), books `BOOK-<desk>-<n>`, legal entities
`LE-<city>` (`LE:NY` means `LE-NY`), hierarchy nodes `<tree>~<id>` (`MGMT~DESK-RATES`, `LEGAL~LE-NY`), positions
`POS-<book>~<security>` (`POS-BOOK-EQD-2~SEC-WARRANT-USD-01`), vectors `PV-<trade>-<scenario set>`, system baskets `PF-<name>`, baskets
`BASKET:<owner>/<name>`. Trade ids are the booking system's: numbers (ENDUR), `MX-` (MUREX), `CLY-` (CALYPSO).

#### From scenario PV to the P&L vector

A bank's batch sends each trade's scenario P&L in one of two forms: the finished **P&L vector** in USD, or the trade's
**scenario PVs**, its value under each scenario and its base value, in its own currency, from which Drishti makes the vector itself when
it loads the date. Either way it helps to know how a vector is made, because every VaR figure inherits its quality. For each live trade
and each scenario day *s* of the scenario set:

```text
P&L(s) = PV(s) · FX(s)  −  PV(base) · FX(base)
```

- **PV(base)** is the trade's value in its own currency on the business date, at today's market; **FX(base)** today's rate from that
  currency into the reporting currency (USD in the demo).
- **PV(s)** is the same trade revalued on the **same valuation date** with today's market moved as it moved on day *s*. Nothing ages
  between the two values, so the P&L is the market move alone, not the passage of time (no theta, no carry).
- **The shift** applied to each risk factor is the batch's choice: *absolute* (the rate moved 12 bp on day *s*, so add 12 bp today)
  or *relative* (the share fell 3%, so take 3% off today's price). Rates and spreads are usually shifted absolutely, prices and
  volatilities relatively; the two give different P&L when today's level is far from the level on day *s*.
- **FX(s)** is the scenario's exchange rate, moved like every other factor, so a EUR bond's P&L in USD holds both the bond's move and the
  euro's move on day *s*. Using today's FX for every scenario would drop the currency risk. When Drishti makes the vector, it takes the
  batch's scenario FX when the batch sends it (`fx-scenario`), else derives it from the FX history: today's rate moved by day *s*'s
  1-day move (`FX(s) = FX(base) × H(d_s) / H(d_s − 1)`). Every cross goes **through USD**: EUR into JPY is EUR→USD→JPY, never a direct
  EUR/JPY quote.
- **The same scenario order** for every trade: position *i* of every vector is the scenario set's day *i* (`SSET HS-509` lists them,
  oldest first). That is what makes vectors addable; Drishti adds only vectors of one scenario set and one batch.
- **A scenario the batch could not price is flagged, never filled with zero.** A trade with no vector is `STATUS: failed` and counted in
  the answer's provenance; one priced by a proxy is `proxied`. A scenario PV that is empty (null) leaves the trade out with
  `DRS-5206`, the scenario named, in every answer's provenance. A zero would claim the trade neither gains nor loses on that day, which
  quietly lowers the VaR.
- **Full revaluation or sensitivities.** *Full revaluation* reprices the trade under each scenario. *Sensitivities* approximate the
  P&L from deltas, gammas and vegas (a Taylor expansion): fast, but wrong for large moves and for options near a barrier or strike.
  The scenario set says which the batch used (`REVALUATION`).

**In Drishti.** A trade sent as scenario PVs (`SPV SPV-<trade>-HS-509`) becomes its USD vector when the date loads; the demo's
Endur trades come that way, the rest as vectors. Both PVs must have the same valuation date (the business date), and a currency with no
scenario FX leaves its trades out (`DRS-5207`). From the USD vectors Drishti answers in **any reporting currency** (`--ccy EUR`,
`ccy=EUR`): each trade's USD value under the scenario at the scenario's rate, less today's value at today's rate. So the same bank has a
different VaR in each currency: on 2026-09-30 112.0 million USD is 111.8 million EUR, not the 95.4 million today's rate makes, because a
euro-based owner also carries the bank's 3.65 billion USD of value moving against the euro. Which currency is right is the owner's
question, not the model's. The guide's [section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency)
has the contract, [section 15.10](../guides/VAR_GUIDE.md#1510-reporting-currency) the reporting currencies.

The currencies a bank reports in are **built ahead** (USD, EUR, CAD, JPY and GBP unless configured otherwise), and every node of the
hierarchy keeps its trades' PVs **summed per trade currency**: its P&L in any currency `X` is then
`Σ_c [PVsum_c(s) × FX_c→X(s) − PVbase_c × FX_c→X(base)]`, the same, to the cent, as converting every trade on its own. The VaR page's
**Reporting currency** picker (or `VAR BANK IN EUR`) moves every figure to that currency, and its tab **In other currencies** puts them
side by side: on the test data (2026-09-30) the bank's 112.0 million USD is 111.8 million EUR. A trade page says its **Trade currency**: the currency
its PVs come in. The guide's [section 15.11](../guides/VAR_GUIDE.md#1511-reporting-currencies-built-ahead-per-trade-currency-totals-and-the-picker)
has the details.

**During the day.** A pricing system re-sends only the trades that changed: amended, new or cancelled, priced over the same
scenario days as the morning run (`RUN_KIND: INTRADAY`, a later `COMPUTED_AT`). Drishti replaces each such trade's P&L and adds the
difference to its book and every node above it, so the desk's VaR moves within seconds without reloading the day; the latest price of a
trade wins, and the figures say *intraday*. The official end-of-day numbers stay those of the morning run.

### Market risk: a worked example on the demo data

From a scratch server over the demo lake built on the test data's own date (`drishti.py lake demo --as-of data`, as the
[VaR guide](../guides/VAR_GUIDE.md) and [tutorial 2, Part A](../tutorials/02-var-from-the-bank-to-a-trade.md#part-a--historical-var-from-the-bank-to-a-trade) use it): business date
**2026-09-30**, batch `B-2026-09-30-01`, scenario set `HS-509`. (The default `lake demo` moves the days to end today, and trades
matured since drop out, so its figures differ.)

**The bank.** `VAR BANK <GO>` (or `drishti.py var show BANK`):

```text
VAR BANK  ·  Drishti Bank  ·  node  ·  2026-09-30
  VaR 99%   112,041,646 USD   (the 6th worst of 509)
  ES 97.5%   160,143,809 USD   (mean of the 13 worst)
  limit      150,000,000 USD   use 74.7%
  parts      2 division   standalone sum 130,101,896   diversification 18,060,250
  backtest   amber  5 exceptions in 249 days   Kupiec p 0.160   Christoffersen p 0.076
  9572 trades in 7993 positions
```

(*Drishti Bank* is the root node's name in the demo's hierarchy data; `drishti.var.root-name` sets another.)

**Any form, any grain.** A pricing system may send scenario PVs or P&L vectors, per trade or per position (a position sent
whole, placed by its book, naming its trades): every record says what it is (`VALUE_TYPE`, `GRAIN`, its id, `CURRENCY`,
`SOURCE_SYSTEM`), one system sends one form and one grain on a date, and a system may switch between dates. The same trades sent per
trade or as their positions give the same VaR on every node; a trade inside a supplied position has no VaR of its own (its page says so
and links the position). `drishti.var.inputs` can pin what each system sends, as a cross-check (VAR_GUIDE.md section 15.13).

**One year or two; 99% or 95%.** `drishti.var.scenario-window` uses the newest N scenarios of each date's set (250 is one year;
`all` is the whole set, 509 in the demo) and `drishti.var.confidence` sets VaR at 99% or 95%; every label follows the answer, and
`window=` or `confidence=` on one request compares without changing anything official (VAR_GUIDE.md section 18.5).

Read it as: on the 6th worst of the 509 days the bank would lose 112.0 million; the 13 worst days lose 160.1 million on average, so
the tail beyond VaR is fat; the VaR uses 74.7% of the 150 million limit; the model had 5 exceptions in the last 249 days (amber: 2.5
are expected at 99%).

**Diversification depends on how you cut the bank.** The same 112.0 million against different parts:

| Cut by | Sum of the parts' standalone VaRs | Diversification |
|---|---|---|
| 2 divisions | 130.1 m | 18.1 m |
| 4 legal entities (`drishti.py var tree --hierarchy LEGAL`): LE-NY 144.3 m, LE-LDN 48.4 m, LE-TKY 10.0 m, LE-FRA 1.5 m | 204.1 m | **92.1 m** |
| 10 desks (`drishti.py var parts BANK --by desk`) | 367.9 m | 255.8 m |

The finer the cut, the more the parts offset each other. LE-NY alone (144.3 m) has a larger VaR than the bank.

**Who drives it and who hedges.** `drishti.py var parts BANK --by desk`: the credit desk's component is 89.5 million (79.9% of the
bank's VaR), equity derivatives 13.3 million (11.8%). Three desks have negative components: structured (−6.6 m), money markets (−3.9 m)
and FX (−1.8 m); they gain on the bank's worst days. Rates has a positive component (7.2 m) but an incremental VaR of **−17.5
million**: take the whole desk out and the bank's VaR rises (FX's is −28.4 million).

**A desk by book.** `drishti.py var parts DESK:RATES`: VaR 74.6 million; `BOOK-RATES-3` is the riskiest alone (61.5 m) but
contributes the least (18.7 m, 25.1%): its worst days are not the desk's; `BOOK-RATES-1` contributes most (30.6 m, 41.0%) yet has an
incremental VaR of −5.1 m: take it out and the desk's VaR rises.

**One trade.** `VAR SCOPE DESK:RATES - TRD:MX-30000121 <GO>`: 74,720,890, so this swap's incremental VaR in its desk is −87,210.

**From the credit side.** `drishti.py var show CPTY:CP-ALDERSHOT`: the 469 live trades with Aldershot Pension Trust, across desks, have
a VaR of 25.1 million (ES 37.3 m). The same counterparty's peak PFE on the same date is 401.5 million (`drishti.py exposure show
CPTY:CP-ALDERSHOT`; [Counterparty credit risk](#counterparty-credit-risk-a-worked-example-on-the-demo-data)): two different questions
about the same trades.

**What the data hasn't told you.** The bank uses 74.7% of its 150 million limit, in the Management tree (`VAR BANK`), in the
Legal tree (`drishti.py var tree --hierarchy LEGAL`) and in the limit letters' tree (`--hierarchy LL`) alike: one firm-wide
limit, three ways of splitting it. The same book is held to different limits in each: `BOOK-RATES-2` uses 90.8% of its limit in the
Management tree and 93.6% in the Legal tree, and `BOOK-FX-3` 95.1% in both, the closest to a breach on 2026-09-30, while its desk
(G10 and EM FX) uses 80.2%: a book near its limit that a desk-level report would not show.

### Market risk: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| How much could the bank lose tomorrow, and how bad is the tail? | `VAR BANK <GO>` · `drishti.py var show BANK` | VaR, ES, limit use, diversification, the backtest light |
| Which desk drives the bank's VaR, and which one hedges it? | `drishti.py var parts BANK --by desk` | component VaR per desk (adds up to the bank's); a negative one is a hedge |
| Which book hedges its desk? | `drishti.py var parts DESK:RATES` | a negative **incremental** VaR: take the book out and the desk's VaR rises |
| What does this one trade do to my desk's VaR? | `VAR SCOPE DESK:RATES - TRD:MX-30000121 <GO>` | the desk without the trade; the difference is the trade's incremental VaR |
| Which legal entity is closest to its limit? | `drishti.py var tree --hierarchy LEGAL` | VaR, ES and limit use of every legal entity, desk and book |
| On which days would we lose most, and who loses? | `VAR BANK <GO>` → the P&L path tab · `drishti.py var path BANK` | the 509 days ranked; click one to see each part's P&L that day |
| Is the model working? | `drishti.py var history BANK` | VaR against the day's hypothetical P&L, exceptions, the traffic light, Kupiec and Christoffersen tests |
| What do all my short EUR positions risk? | `drishti.py var show "WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'"` | a rule's VaR across every desk, computed on the fly |
| How much market risk sits in one counterparty's trades? | `drishti.py var show CPTY:CP-ALDERSHOT` | the VaR of every trade with the counterparty, across desks: market risk seen from the credit side |
| Did anything change since this morning? | `drishti.py var capture BANK`, later `drishti.py var compare <capture> now` | figures side by side with the trades added and removed |

### Market risk: common confusions

**VaR or ES?** VaR is a threshold: *on 99 days out of 100 we lose less than this*. It says nothing about the 1 day in 100. ES is the
average of the worst days beyond the threshold, so it sees a fat tail that VaR misses. Basel's FRTB moved capital from VaR to ES at
97.5% for that reason. Drishti shows both; neither replaces the other. ES at 97.5% can even be **below** VaR at 99% when the tail is
short (13 days averaged against the 6th worst).

**Why VaR adds the trades' P&L, not quantity × price move.** For one share held long and short, the two agree: long 100 and short
40 of the same share, on a day the share falls 2, lose 200 and gain 80; the vectors add to −120, which is the net 60 × −2. They part
company as soon as a trade is not linear in one price. Take a call option on 100 shares (delta 0.5) hedged by a short of 50 shares,
with the share at 100 (illustrative prices). Quantity × move sees a net delta of zero and a P&L of zero on every day. Revaluation
does not:

| Day's move | Short 50 shares | Long call (value 1,000 today) | Book |
|---|---|---|---|
| share to 90 | +500 | 620: −380 | **+120** |
| share to 110 | −500 | 1,700: +700 | **+200** |

The hedged book gains on both big days (it is long gamma), and a book short that option would lose on both. The same goes for a bond
and a future on a different bond, for a trade whose value depends on two prices, and for a foreign-currency trade whose P&L moves with
FX. That is why the batch revalues each trade and Drishti adds what it delivers: the P&L vectors, never quantities times moves.

**√10 or the sum of 10 days?** Both make a 10-day VaR from the same 1-day scenarios, and they answer different questions. **√10**
scales the 1-day figures by 3.162: it is exact only if each day is independent of the last and the book's value is a straight line in
the market, and it is the convention most rules accept. **The sum** adds the scope's own P&L over 10 consecutive scenario days (509
days give 500 overlapping windows), so a trend or a run of bad days shows up and a move that reverts partly cancels. On the demo bank
of 2026-09-30 the 1-day VaR is 112.0m; √10 gives **354.3m**, the sum **274.5m** (the 5th worst of 500 windows): ten days in a row here
partly offset each other. Neither revalues an option at a 10-day move, and the overlapping windows share nine days with their
neighbours, so the 500 hold about 50 independent periods. A limit set for 1-day 99% is never compared with a 10-day figure: the VaR
page says *not comparable*. Try `VAR BANK 10D SQRT`, then `VAR BANK 10D SUM`.

**Why the desks' VaRs do not add up to the bank's.** Each desk's VaR is read on its own worst days. Those days differ from desk to
desk, and some desks gain when others lose. So the bank's VaR is far below the sum of the desks', and a single desk can have a VaR
larger than the bank's. The gap is the **diversification**. Never add VaRs; add vectors, then sort.

**Standalone, component, incremental, marginal.** *Standalone*: the part alone. *Component*: the part's share of the whole, measured
on the whole's worst days; components add up to the whole. *Incremental*: the whole with the part minus the whole without it, the
exact answer to *what does this trade do to my VaR?* *Marginal*: component per million of size. A book can have the largest component
and a negative incremental VaR at once: it is big, but at the margin it hedges.

**VaR is not the worst case.** At 99% the bank expects to lose more than VaR about two or three days a year. Stress tests (`STR`) ask
the other question: what would a named crisis do?

**A one-day VaR is not a ten-day VaR.** Drishti's VaR is one-day. Regulatory ten-day VaR and stressed VaR are not built.

**VaR and PFE are different risks.** VaR is what the bank could lose on its trades if markets move against it, whoever the
counterparty. PFE is how much a counterparty could owe the bank at a future date, which matters only if that counterparty defaults
([Counterparty credit risk](#counterparty-credit-risk-common-confusions)). The same trade appears in both, read from different
data: its P&L vector for VaR, its exposure paths for PFE.

**Stored or computed?** Drishti stores no `var` record per desk made by some other system.
every VaR is **computed** from the trades' vectors when asked (`VRES`, `VAR`), so it always matches the trades you can see. Only two
things are kept: captures you ask for and the official end-of-day record (`VOFF`). Stress results, FRTB charges and P&L explain are
still **stored** results from upstream engines; Drishti shows them but does not compute them.

**A position is not delivered.** Drishti derives positions from trades each day (a book's trades in one security net into one
position). Change the trades and the positions change with them.

**P&L vector, hypothetical P&L, P&L explain.** The vector is what each trade *would* make under 509 past days. Hypothetical P&L is
what the day's positions *did* make the next day, unchanged; it is the backtest's yardstick. P&L explain splits a book's *actual*
P&L by risk factor.

## 2. Counterparty credit risk

### Counterparty credit risk in plain words

When a bank lends money, it knows how much it could lose: the loan. When it trades a swap, a forward or an option with a firm, it
does not. Today the swap may be worth nothing; in a year, if rates move, the firm may owe the bank millions. If the firm defaults
then, the bank loses what it was owed. **Counterparty credit risk** is that risk: a counterparty defaulting while its trades are worth
something to the bank.

Two things reduce it:

- **Netting.** Under a master agreement (the ISDA for derivatives) the bank may, on default, end every trade and settle one net amount.
  A trade the bank wins nets against one it loses. The trades that net together form a **netting set**.
- **Collateral.** Under the agreement's credit support annex (the **CSA**) each side posts collateral as the value moves. The bank holds
  what the counterparty posted, so on default it loses only what the collateral does not cover: the exposure below the CSA's
  threshold, and how far the value moves in the days it takes to close the counterparty out (the **margin period of risk**).

Because the amount owed depends on future markets, it is **simulated**. An upstream simulator values every trade on a few hundred
possible market paths at future dates (1 week to 10 years). Drishti then, for any set of trades:

1. adds the trades of each netting set path by path (netting);
2. applies the CSA's collateral on each path, as it would have been called a margin period earlier;
3. floors the result at zero (nobody can owe the bank less than nothing): the **exposure** on that path;
4. reads the average over paths (**EE**), the bad case (**PFE**, the 95th or 99th percentile) and their averages over the first year
   (**EPE**, **EEPE**).

Why a bank cares: **credit limits** are set on peak PFE, so a credit officer needs it before every new trade; the **CVA** desk prices
the expected loss from EE; **regulatory capital** under the internal model method uses EEPE; and a counterparty's whole picture
(agreement, CSA, collateral, margin calls, limit) is what a credit review reads.

### How the counterparty credit risk data connects

```text
counterparty-group  GRP GRP-<name>            the economic group: members, one group limit
   │  GROUP
counterparty        CPTY CP-<name>            rating, sector, country of risk, LEI, ECONOMY ─► economy (world-economy pack)
   │  CREDIT_LIMIT ─► credit-limit  LIM LIM-<name>          peak-PFE limit by tenor bucket and its use
   │  CREDIT_CURVE ─► credit-curve  CDS CDS-<name>           spreads, default probabilities (market-data pack)
   │  COUNTERPARTY ◄─ agreement     AGR AGR-<name>-ISDA      the master agreement: governing law, close-out netting
   │                     │ CSA
   │                     ▼
   │                  csa          CSA CSA-<name>           thresholds, MTA, independent amount, MPOR, eligible collateral
   │                     │ NETTING_SETS, COLLATERAL_BALANCES
   │  NETTING_SETS       ▼
   └──────────────► netting-set   NSET NS-<name>-<legal entity>   one per counterparty and bank legal entity
                        │  LEGAL_ENTITY ─► legal-entity LE LE-<city>   (the bank's booking entity)
                        │  COLLATERAL_BALANCE ─► collateral-balance COLL COLL-<name>-<legal entity>  held, posted, net
                        │  ◄─ margin-call MC MC-<name>-<legal entity>-<n>
                        │  CVA_RESULT ─► cva CVA-<name>-<legal entity>    SACCR ─► sa-ccr SACCR-<name>-<legal entity>
                        │  SIMM ─► simm SIMM-<name>                        (stored results from upstream engines)
                        │  TRADE_IDS ─► trade TRD <id>
                        ▼
simulation-run   XRUN XR-<date>-<nn>        paths, grid of dates, look-back (MPOR)
exposure-path    XPATH <run>~<trade>~<grid index>   one trade's MtM on every path at one grid date
                        │  (NETTING_SET, COUNTERPARTY, LEGAL_ENTITY, DESK, TT_BOOK of the trade)
                        ▼
any scope text  (BANK, LE:LE-LDN, GROUP:GRP-ALDERSHOT, CPTY:CP-ALDERSHOT, NSET:NS-ALDERSHOT-LDN, DESK:, BOOK:, TRD:, WHERE:, + - &)
   └─► exposure-result         EXP <scope>     computed when asked: profile, PFE, EPE, EEPE, contributions (never stored)
   └─► the pre-deal what-if    EXP explorer    POST /api/v1/exposure/what-if: the same engine, exact by paths or the addon proxy
   └─► exposure-official-cpty  XOFC            the official figures of every netting set and counterparty, published once a day
```

The links are the contract link columns of `config/packs/banking/counterparty-risk/contracts/` and `config/packs/core/core/contracts/`:
`netting-set.COUNTERPARTY → counterparty`, `AGREEMENT → agreement`, `CSA → csa`, `CREDIT_LIMIT → credit-limit`,
`CVA_RESULT → cva`, `SACCR → sa-ccr`, `SIMM → simm`, `COLLATERAL_BALANCE → collateral-balance`, `LEGAL_ENTITY → legal-entity`,
`TRADE_IDS → trade`; `counterparty.NETTING_SETS → netting-set`, `GROUP → counterparty-group`, `CREDIT_LIMIT → credit-limit`,
`CREDIT_CURVE → credit-curve`, `LEGAL_ENTITY → legal-entity`, `ECONOMY → economy`; `csa.AGREEMENT → agreement`,
`NETTING_SETS → netting-set`; `exposure-path.RUN_ID → simulation-run`, `TRADE_ID → trade`, `NETTING_SET → netting-set`.

**Id conventions.** A counterparty is `CP-<NAME>` (`CP-ALDERSHOT`), its group `GRP-<NAME>`, its limit `LIM-<NAME>`, its agreement
`AGR-<NAME>-ISDA` and CSA `CSA-<NAME>`. Everything per netting set carries the counterparty and the bank's legal entity:
`NS-ALDERSHOT-LDN` is Aldershot's trades booked by `LE-LDN`; its collateral is `COLL-ALDERSHOT-LDN`, its CVA `CVA-ALDERSHOT-LDN`, its
SA-CCR `SACCR-ALDERSHOT-LDN`, its margin calls `MC-ALDERSHOT-LDN-<n>`. A simulation run is `XR-<business date>-<nn>`. A scope word
accepts an id with or without its prefix (`CPTY:ALDERSHOT` is `CPTY:CP-ALDERSHOT`; `EXP NS-ALDERSHOT-LDN <GO>` opens the set).

### Counterparty credit risk: a worked example on the demo data

From a scratch server over the default demo lake (`drishti.py lake demo`): business date **2026-10-26**, simulation run
`XR-2026-10-26-01` (200 paths, 13 reported dates from 1W to 10Y, collateral lagged by a 10-day margin period, PFE at 95%).

**A counterparty and its four netting sets.** `EXP CP-ALDERSHOT <GO>` (or `drishti.py exposure show CPTY:CP-ALDERSHOT`): Aldershot
Pension Trust trades with all four of the bank's legal entities under one ISDA (`AGR-ALDERSHOT-ISDA`, English law, close-out netting
enforceable) and one CSA per legal entity (`CSA-ALDERSHOT-NY`, `-LDN`, `-TKY`, `-FRA`: thresholds 10 million each way, MTA
500,000, daily calls, MPOR 10 business days, in USD):

| Scope | Trades | CE | EPE | Peak PFE 95% | at |
|---|---|---|---|---|---|
| `NSET:NS-ALDERSHOT-NY` | 233 | 228.5 m | 35.9 m | 357.4 m | 2W |
| `NSET:NS-ALDERSHOT-LDN` | 141 | 0 | 14.8 m | 85.1 m | 2M |
| `NSET:NS-ALDERSHOT-TKY` | 21 | 5.5 m | 12.0 m | 61.4 m | 2M |
| `NSET:NS-ALDERSHOT-FRA` | 22 | 7.1 m | 2.2 m | 23.7 m | 2W |
| **`CPTY:CP-ALDERSHOT`** | 417 | **241.1 m** | 63.5 m | **396.8 m** | 2W |

The trades are the live ones (status `NEW`, `VALIDATED` or `AMENDED`) that have not matured by 2026-10-26.

- **CE adds up**: 228.5 + 0 + 5.5 + 7.1 = 241.1 million.
- **PFE does not**: the four peaks sum to 527.6 million; the counterparty's peak is 396.8 million. The sets' bad paths and dates differ.
- **Where the peak comes from** (`drishti.py exposure parts CPTY:CP-ALDERSHOT --by netting-set`): NY 350.4 m (88.3%), TKY 22.9 m,
  FRA 15.1 m, LDN 8.4 m. LDN's own peak is 85.1 million, but it peaks at 2M; at the counterparty's peak (2W) it adds little.
- Its limit `LIM-ALDERSHOT` is 7,980 million on peak PFE: 5.0% used.

**Collateral that raises exposure.** `EXP NS-ALDERSHOT-LDN <GO>`, the Collateral tab. The set's trades are worth −139.1 million to the
bank today (the bank owes Aldershot) and the bank has *posted* 111.1 million of collateral after haircut (`COLL COLL-ALDERSHOT-LDN <GO>`). At the peak (2M) EE is **19.5 million with collateral
against 3.5 million without: +452%**. On paths where the trades swing in the bank's favour, the bank is owed the trades' value *and*
its posted collateral back until the next call settles. After a year the effect turns: at 5Y EE is 6.8 million with collateral against
86.7 million without.

**Netting at work.** `drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade --figure EE`: at 2M a convertible bond adds 31.7
million to the set's EE of 19.5 million, while a quanto option and a basket swap take away 14.2 and 13.0 million. Alone, each would be
a large exposure; together they largely cancel.

**A group.** `drishti.py exposure show GROUP:GRP-MERIDIAN`: Meridian Life Assurance (peak PFE 151.3 m) and Meridian Reinsurance (284.2 m)
together peak at **359.4 million**, less than the 435.5 million their peaks add to.

**The bank and a legal entity.** `EXP BANK <GO>`: peak PFE 2,938.5 million at 2W, EPE 975.7 million, CE 1,782.5 million, over all 76
netting sets (8,302 live trades). `EXP LE:LE-LDN <GO>` and its counterparty board: peak PFE 694.4 million at 1M; the largest
contributors are Summit Clearing (205.9 m), Kestrel Global Macro Fund (78.8 m) and Northbridge Capital (52.3 m).

**What the data hasn't told you.** The test data's dates were moved to today, but its trades' maturities were not: `NSET
NS-ALDERSHOT-FRA <GO>` lists 35 member trades, all live in the booking system, of which 13 matured between the data's own date
(2026-09-30) and 2026-10-26 (`CLY-4004249` on 2026-10-07, `CLY-4000583` on 2026-10-15 …). The exposure run of the demo leaves them
out (22 trades have paths), so the explorer and the netting-set record count different populations. In a bank this is the
reconciliation a credit officer runs every morning: the record lists what the upstream system last sent, the engine what is live on
the date. (A netting set holds
live trades only.)

The netting-set record carries no stored `UTILISATION`: a ratio from an
upstream engine that nobody can rebuild from
its inputs is not shown: a limit's use is the credit limit's, and the figures beside the stored ones on the netting-set
page are computed from what you can see (the trade valuations, the paths).

### Counterparty credit risk: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| Which counterparty would hurt most if it defaulted? | `EXP LE:LE-LDN <GO>` and its counterparty board · `drishti.py exposure tree` | every counterparty of the legal entity ranked by peak PFE, with EPE and limit use |
| What could CP-ALDERSHOT owe us, on average and in a bad case? | `EXP CP-ALDERSHOT <GO>` · `drishti.py exposure show CPTY:CP-ALDERSHOT` | the profile 1W to 10Y, peak PFE and when, EPE, EEPE, CE |
| Which of its netting sets drives that? | `drishti.py exposure parts CPTY:CP-ALDERSHOT --by netting-set` | PFE contributions that add up to the counterparty's peak |
| Is collateral helping or hurting? | `EXP NS-ALDERSHOT-LDN <GO>` → the Collateral tab | the effect of collateral at the peak; posted collateral can raise exposure |
| How much does netting save? | `drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade` | each trade alone against its contribution inside the set |
| Which asset class makes the exposure? | `drishti.py exposure parts CPTY:CP-ALDERSHOT --by asset` | contributions by asset class |
| Would this unwind free up limit before I deal? | `drishti.py exposure whatif CPTY:CP-ALDERSHOT --like CLY-4003028 --factor -1 --set NS-ALDERSHOT-FRA` | PFE before and after, by the exact paths or the add-on proxy |
| Who is in breach of their limit? | `drishti.py exposure tree` | peak PFE against each counterparty's limit |
| How spread out could the exposure be in a year? | `drishti.py exposure distribution NSET:NS-ALDERSHOT-NY --at 1Y` | the histogram of exposures over the paths, the share at zero |
| What are the terms we trade under with them? | `AGR AGR-ALDERSHOT-ISDA <GO>`, then `CSA CSA-ALDERSHOT-NY <GO>` (one CSA per legal entity) | governing law, close-out netting, thresholds, MTA, MPOR |
| How much market risk sits in the same trades? | `drishti.py var show CPTY:CP-ALDERSHOT` | the VaR of the counterparty's trades ([Market risk](#1-market-risk)) |

### Counterparty credit risk: common confusions

**EE, PFE, EPE, EEPE.** All four start from the same exposures on the paths. **EE** is the average at one date; **PFE** is the bad
case at one date (95th percentile: 5% of paths are worse). Over the dates they make a profile. **EPE** averages EE over the first year;
**EEPE** averages EE's running maximum, so EEPE is never below EPE. Credit limits use peak PFE; CVA uses EE; regulatory capital under
the internal model uses EEPE.

**Why PFE does not add up.** Exposures of different netting sets add path by path, but their bad cases fall on different paths and
dates. The 95th percentile of a sum is below the sum of the 95th percentiles. Add EE freely; never add PFEs. Drishti sums the path
matrices, then takes the percentile.

**Why VaR and PFE do not add up either, and are not the same thing.** VaR is a one-day loss on the bank's trades from market moves,
whoever the counterparty. PFE is what a counterparty could owe at a future date, which becomes a loss only if it defaults. One trade
can lower VaR (it hedges a desk) and raise PFE (it adds to a counterparty's netting set). See [Market risk](#1-market-risk).

**Netting benefit.** A trade alone may show a large exposure; inside its netting set its contribution can be small or negative,
because other trades offset it. Netting happens only inside a netting set: two netting sets of one counterparty do not net against
each other (they are under different agreements or legal entities).

**Why posted collateral raises exposure.** When the bank has *posted* collateral (it owes the counterparty), that collateral is gone
if the counterparty defaults. On paths where markets turn in the bank's favour, the bank is owed the trades' value *and* the posted
collateral back, until the next margin call settles, a margin period later. So in the first weeks EE with collateral can exceed EE
without it. Collateral the bank *holds* always lowers exposure.

**Current exposure is not future exposure.** CE is today's: MtM less collateral, floored at zero. A netting set with CE zero today can
have a large PFE in a month.

**Stored or computed?** Drishti stores no exposure profile per netting set made elsewhere. `EXP` is
computed from the trades' paths when asked, for any scope. **CVA, SA-CCR and SIMM** are still **stored** results from upstream
engines: Drishti shows them (`CVA`, `SACCR`, `SIMM`) but does not compute them. Wrong-way risk is designed in the contracts and not
computed yet.

**EAD is informational.** Drishti shows alpha (1.4) × EEPE as EAD under the internal model method. It is not a regulatory capital
figure: there is no approved model behind it, and SA-CCR's EAD (stored) is a different formula.

**A counterparty is not a legal entity of the bank.** `CP-ALDERSHOT` is the firm the bank trades with; `LE-LDN` is the bank's own
company that booked the trades. A netting set joins one of each. See [Entities and ownership](data.md#2-entities-and-ownership).

## 3. Trades and products

### Trades and products in plain words

A **trade** is one deal: the bank agreed with a counterparty to exchange something, now or later. A swap exchanges fixed for floating
interest for ten years; an FX forward exchanges currencies on a future date; a commodity swap pays the difference between a fixed and
a market price. Every risk number starts from the trades: market risk sums their P&L vectors, counterparty risk sums their exposure
paths, and every report counts, groups or values them.

A trade lives in a **booking system**. Large banks have several, each strong in some products: in the demo, ENDUR (commodities and
energy), MUREX and CALYPSO (rates, FX, credit, equity). Each system names things its own way. Drishti reads all three as one `trade`
kind with production-style UPPER_SNAKE fields (`TRADE_ID`, `COUNTERPARTY`, `PRODUCT_TYPE`), so a question about *all trades with
Aldershot* covers every system at once.

Every trade sits in a **book**, owned by a **desk**, booked by a **trader**, in one of the bank's **legal entities**, with one
**counterparty**. Those five links are what make a trade findable from any direction.

Why a bank cares: the trade population is the bank's inventory. If a trade is missing, mis-booked or in the wrong status, every
figure built on it is wrong. Seeing a trade with everything around it (its counterparty, netting set, valuation, position, VaR and
exposure) is how people find booking errors before the regulator does.

### How the trades and products data connects

```text
trade   TRD <id>     ENDUR 1000001 · MUREX MX-20000005 · CALYPSO CLY-3000001
   │  COUNTERPARTY ─────────► counterparty   CPTY CP-<name>      (and ORIGINAL_COUNTERPARTY after a novation)
   │  LEGAL_ENTITY ─────────► legal-entity   LE LE-<city>        the bank's company that booked it
   │  TT_GROUP ─────────────► desk           DESK DESK-<name>
   │  TT_BOOK, PORTFOLIO ───► book           BOOK BOOK-<desk>-<n>    ──► desk
   │  TRADER_ID ────────────► trader         TRDR TRDR-<initial><surname>
   │  LEGS[], EVENTS[]        nested: each leg's notional, index, schedule; each dated payment or fixing
   │
   ├─◄ trade-valuation   TVAL TVAL-<trade id>        the risk engine's MTM, 1-day P&L, DV01, NETTING_SET
   ├─◄ pnl-vector        PNLV PNL-<trade id>-HS-509  509 scenario P&Ls (market risk)
   ├─◄ position          POS POS-<book>~<security | trade>  the trade netted with others in its book (market risk)
   ├─◄ exposure-path     XPATH <run>~<trade id>~<n>  MtM on every path (counterparty risk)
   └─◄ netting-set       NSET NS-<counterparty>-<legal entity>   TRADE_IDS (counterparty risk)

grouped when asked (computed, never stored):
   counterparty-trades  CTRD <counterparty>   every trade with one counterparty, every system
   legal-entity-trades  LTRD <legal entity>   every trade one of the bank's entities booked
   portfolio-trades     PTRD <book>           every trade of one book
   index-trades         ITRD <index name>     every trade on one floating index
```

The links are the trading pack's contract link columns (`config/packs/banking/trading/contracts/trade.contract.yaml`): `COUNTERPARTY` and
`ORIGINAL_COUNTERPARTY → counterparty`, `LEGAL_ENTITY → legal-entity`, `PORTFOLIO` and `TT_BOOK → book`, `TRADER_ID → trader`,
`TT_GROUP → desk`. The arrows marked `◄` are other kinds that point at the trade (`TRADE_ID`, `TRADE_IDS`).

**Id conventions.** The trade id is the booking system's own: plain numbers for ENDUR, `MX-` for MUREX, `CLY-` for CALYPSO; a trade
keeps its id through amendments (`TRADE_VERSION` counts them). A valuation is `TVAL-<trade id>`, a P&L vector `PV-<trade id>-HS-509`.
Books are `BOOK-<desk>-<n>`, desks `DESK-<name>`, traders `TRDR-<initial><surname>` (`TRDR-ASHAH`).

### Trades and products: a worked example on the demo data

From a scratch server over the default demo lake (`drishti.py lake demo`): business date **2026-10-26**. The demo holds 15,000 trades,
5,000 from each of ENDUR, MUREX and CALYPSO, every status included.

**One trade.** `TRD 1000001 <GO>`: an ENDUR commodity swap (`ComSwap`, sub type `ENGY-SWAP`), the bank sells 72,000 tonnes against
Harbor Point Bank (`CP-HARBORPT`, country of risk US), notional USD 209,962,179, traded 2025-06-20, maturing 2028-07-31, booked by
`LE-NY` in `BOOK-COMM-2` by F. Rossi, status **AMENDED, version 7** (last updated 2026-10-22). Its links open the counterparty, the
book, the desk and the trader; its legs and cash-flow events are tabs of the same page.

**Its valuation.** `TVAL TVAL-1000001 <GO>`: status AMENDED, notional 209,962,179 (the trade's latest version), MTM +5,151,600 USD,
the day's P&L −347,255, DV01 0 (the engine reports no rate sensitivity for it), valued 2026-10-26.

**Every trade with one counterparty.** `CTRD CP-ALDERSHOT <GO>`, grouped when you open it: **641** trades from all 3 systems, 107
product types in 7 currencies, notional 184.4 billion across currencies; payments due −42.3 million in the next 7 days (13 events),
−170.2 million in 30 days (82 events), −507.2 million in 90 days (226 events).

**Three populations of "Aldershot's trades".** The same counterparty counts differently depending on the question:

| Question | Command | Trades |
|---|---|---|
| Every trade booked with it, any status | `CTRD CP-ALDERSHOT <GO>` | 641 |
| Its trades in the exposure run (four netting sets) | `drishti.py exposure show CPTY:CP-ALDERSHOT` | 417 |
| Its live trades with a P&L vector (VaR) | `drishti.py var show CPTY:CP-ALDERSHOT` | 425 |

None is wrong: matured and cancelled trades are in the first, only priced live trades in the last. A report that mixes them
mixes questions.

**What the data hasn't told you.** The three populations above differ for a reason that is easy to miss: maturity. The exposure run
counts the live trades that have not matured by 2026-10-26, while the netting-set records still list trades that matured between the
test data's own date (2026-09-30) and the demo's (the demo moves the dates, not the trades' maturities). And a valuation must be of the
version you see: the valuation carries the trade's status and notional of its day.

### Trades and products: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| What exactly is this trade? | `TRD 1000001 <GO>` | its system, status, product, legs, events, book, desk, trader, legal entity, counterparty |
| What is it worth today? | `TVAL TVAL-1000001 <GO>` | MTM, the day's P&L, DV01 and the netting set it sits in |
| Every trade we have with one counterparty, across systems? | `CTRD CP-ALDERSHOT <GO>` | counts by system and product, notional, payments due in 7, 30 and 90 days |
| How has this trade changed over time? | `TRD 1000015 30 <GO>` | the trade on each of the last 30 business dates, what changed and when |
| Which USD trades are larger than 10 million? | `TRD QUANTITY > 10m and CURRENCY = usd <GO>` | a filtered list of every system's trades you can sort, chart or export |
| What does one desk hold? | `DESK DESK-RATES <GO>` | its books, MTM, asset classes and positions |
| Which trades does one book carry? | `PTRD BOOK-RATES-1 <GO>` | every trade of the book, grouped |
| Does this trade add risk or hedge? | `drishti.py var parts DESK:RATES --by trade --top 10` | its component and incremental VaR in the desk ([Market risk](#1-market-risk)) |
| Does netting help it? | `drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade` | alone against inside its netting set ([Counterparty credit risk](#2-counterparty-credit-risk)) |

### Trades and products: common confusions

**Notional is not value, and not risk.** A 100 million swap is usually worth a few million either way (its MTM), and what it can lose
in a day (its VaR contribution) is smaller again. Summing notionals measures activity, not risk.

**MTM sign.** A positive MTM means the counterparty owes the bank (the trade is an asset); negative means the bank owes. Exposure
counts only the positive side, after netting and collateral.

**Trade, position, netting set.** One trade can be in one **position** (market risk groups trades by book and security) and in one
**netting set** (counterparty risk groups trades by counterparty, agreement and legal entity). The two groupings cut the same trades
differently, which is why a desk's VaR and a counterparty's PFE never share a total.

**Book, portfolio, desk.** In the source systems a trade's `PORTFOLIO` is its book; Drishti's **portfolio** kind (`PORT`) is something
else, a stored set of positions for VaR. A desk owns several books.

**Status matters.** Risk counts NEW, VALIDATED and AMENDED trades. A cancelled or matured trade still opens with `TRD`, but it is in no
VaR or exposure.

**Counterparty and legal entity.** The counterparty is the firm on the other side; the legal entity is the bank's own company that
booked the trade. See [Entities and ownership](data.md#2-entities-and-ownership).

## 4. Glossary

<!-- glossary:start (generated by tools/knowledge.py from docs/knowledge/glossary.yaml: edit that file, not this section) -->

86 terms. Type `WHAT <term>` on the command line to open any of them (`WHAT PFE`).

| Term | Meaning | See it in Drishti |
|---|---|---|
| <a id="term-var"></a>**VaR** (value at risk) | The loss a set of trades should not exceed on 99 days out of 100, over one day. Drishti reads it from the set's P&L under 509 historical days: minus the 6th worst. | `VAR BANK <GO>` · `drishti.py var show BANK` |
| <a id="term-es"></a>**ES** (expected shortfall) | The average loss on the worst days beyond a confidence level: in Drishti the mean of the 13 worst of 509 days (97.5%). It sees how bad the tail is, which VaR does not. | `VAR BANK <GO>` · `drishti.py var show BANK` |
| <a id="term-historical-simulation"></a>**Historical simulation** | VaR from real past market moves: each of the last 509 business days' moves is applied to today's trades, and the losses are ranked. Equal weights, one-day horizon. | `SSET HS-509 <GO>` |
| <a id="term-scenario-set"></a>**Scenario set** | The list of historical days whose moves make the scenarios (HS-509: 509 days ending the day before the business date). Position i of every P&L vector is day i. | `SSET HS-509 <GO>` |
| <a id="term-p-l-vector"></a>**P&L vector** (profit and loss vector) | One trade's P&L under each of the 509 scenario days, priced upstream by the risk batch. Drishti adds vectors element by element; every VaR figure is read from the sum. | `PNLV PNL-MX-20000274-HS-509 <GO>` |
| <a id="term-scenario-matrix"></a>**Scenario matrix** | One trade's, position's or node's scenario P&L laid out by scenario day (rows) and business date (columns), each date's VaR scenario and ES tail outlined: it shows whether a loss scenario is new or has sat in the tail for weeks. | `SMAT DESK:DESK-RATES 20 <GO>` · `drishti.py var matrix DESK:DESK-RATES --days 20` |
| <a id="term-var-playground"></a>**VaR Playground** | A scope's VaR worked out cell by cell: every position's scenario P&L, the portfolio, its rank, VaR, ES, components and both 10-day methods in a grid with the explanation beside it, and the same as an Excel workbook of real formulas whose figures equal the engine's to the cent. | `PLAY VAR DESK:DESK-RATES <GO>` · `drishti.py var playground DESK:DESK-RATES --xlsx rates.xlsx` |
| <a id="term-full-revaluation"></a>**Full revaluation** | Repricing each trade under each scenario to get its P&L, rather than approximating the P&L from sensitivities (delta, gamma, vega). The scenario set says which the batch used. | `SSET HS-509 <GO>` |
| <a id="term-scenario-shift"></a>**Scenario shift** | How a past day's move is applied to today's market: absolute (add the 12 bp the rate moved) or relative (take off the 3% the price fell). Scenario FX is shifted too. | `SSET HS-509 <GO>` |
| <a id="term-confidence-level"></a>**Confidence level** | How far into the tail a figure looks: 99% for VaR (the 6th worst of 509), 97.5% for ES. Exposure uses 95% and 99% for PFE. On the VaR page you may choose another (95%, 97.5%, 99.5%): another rank of the same sorted scenarios. | `VAR BANK 95 <GO>` |
| <a id="term-10-day-var"></a>**10-day VaR** | VaR over 10 days, from the same 1-day scenarios: the 1-day VaR × √10 (independent days, a linear book), or the worst of the sums of 10 consecutive scenario days (509 days give 500 overlapping windows). On the demo bank: 354m by √10, 275m by the sum, against 112m for 1 day. | `VAR BANK 10D SUM <GO>` |
| <a id="term-standalone-var"></a>**Standalone VaR** | The VaR of a part (a desk, a book, a position) measured alone, with its own worst days. Standalone VaRs do not add up to the whole. | `VAR DESK RATES <GO>` · `drishti.py var parts DESK:RATES` |
| <a id="term-component-var"></a>**Component VaR** | A part's share of the whole's VaR, measured on the whole's worst days. Components add up to the VaR exactly; a hedge has a negative component. | `VAR DESK RATES <GO>` · `drishti.py var parts DESK:RATES` |
| <a id="term-incremental-var"></a>**Incremental VaR** | The whole's VaR minus its VaR without the part: what this trade or book does to my VaR. Below zero means removing it would raise the VaR. | `drishti.py var parts DESK:RATES` |
| <a id="term-marginal-var"></a>**Marginal VaR** | Component VaR per million of the part's size (a position's market value, a trade's PV): which positions cost the most risk per dollar. | `drishti.py var parts DESK:RATES --by position` |
| <a id="term-diversification"></a>**Diversification** | The sum of the parts' standalone VaRs minus the whole's VaR: what holding them together saves, because they do not lose on the same days. | `VAR BANK <GO>` |
| <a id="term-scope"></a>**Scope** | Any set of trades written as text: BANK, LE:LE-NY, DESK:RATES, CPTY:CP-ALDERSHOT, a portfolio, a rule, joined with + (union), - (difference) and & (intersection). VaR and exposure use the same grammar. | `VAR SCOPE BANK - DESK:CREDIT <GO>` |
| <a id="term-position"></a>**Position** | One security held in one book (or one OTC trade): the trades that build or reduce it, netted. Drishti derives positions from trades; nobody delivers them. | `POS POS-BOOK-EQD-2~SEC-WARRANT-USD-01 <GO>` |
| <a id="term-system-basket"></a>**System basket** | One of the bank's own baskets, kept in Drishti (never a data feed): a hierarchy node, a rule (every short EUR position) or a basket of chosen positions and trades, the same for everyone, changed only with the permission var:system-baskets; its VaR limit is a var-limit row. | `PORT PF-USD-RATES <GO>` |
| <a id="term-basket"></a>**Basket** | A scope you saved under a name in the VaR explorer (`BASKET:<owner>/<name>`), so you can open it again or compare it over time. | `drishti.py var baskets` |
| <a id="term-hierarchy"></a>**Hierarchy** | A tree VaR and exposure roll up, built from the trades' promoted columns as configured (any number): MGMT (DIVISION > DESK > TT_BOOK), LEGAL (LEGAL_ENTITY > DESK > TT_BOOK), LL (LIMIT_LETTER > DESK). A row with an empty level value sits under a (Blank) node. A node's VaR limit is its var-limit row. | `VAR NODE:LEGAL~LE-NY <GO>` · `drishti.py var tree --hierarchy LEGAL` |
| <a id="term-var-limit"></a>**VaR limit** | The most VaR a node or portfolio may take. Limit use is VaR divided by the limit; above 100% is a breach. | `drishti.py var tree` |
| <a id="term-backtesting"></a>**Backtesting** | Checking VaR against what happened: a day whose loss exceeds the day's VaR is an exception. Basel's traffic light counts them over 250 days (green up to 4, amber 5 to 9, red 10 or more). | `drishti.py var history BANK` |
| <a id="term-hypothetical-p-l"></a>**Hypothetical P&L** | The P&L the day's positions would have made had they been held unchanged through the next day's market move. The backtest compares it with VaR. | `drishti.py var history BANK` |
| <a id="term-capture"></a>**Capture** | A frozen copy of a scope's answer (figures, members, vector, provenance), kept so you can compare it with now or with another capture. | `drishti.py var captures` |
| <a id="term-official-var"></a>**Official VaR** | The end-of-day VaR and ES of every hierarchy node, captured and published once a day as the record of the date (kind var-official). | `VOFF MGMT~DESK-RATES <GO>` · `drishti.py var official` |
| <a id="term-var-what-if"></a>**VaR what-if** | The VaR of a scope with trades added or removed before they are dealt, computed by the server's VaR engine on request (POST /api/v1/var/what-if). | `drishti.py var whatif` |
| <a id="term-stress-test"></a>**Stress test** | The P&L of a desk under a named shock, historical (COVID-19 March 2020) or hypothetical, against a stress limit. Stored results from the upstream risk engine. | `STR STR-COVID2020-COMM <GO>` |
| <a id="term-frtb"></a>**FRTB** (Fundamental Review of the Trading Book) | Basel's market-risk capital rules. Drishti stores the standardised approach's delta, vega and curvature charges per book from an upstream engine; it does not compute them. | `FRTB FRTB-COMM-1 <GO>` |
| <a id="term-sensitivity"></a>**Sensitivity** | How much a value changes for a small move in one risk factor: delta for the price or rate, vega for volatility, curvature for what delta misses. | `FRTB FRTB-COMM-1 <GO>` |
| <a id="term-p-l-explain"></a>**P&L explain** | A book's daily P&L split into what each risk factor's move explains and the unexplained rest. A large residual points to a missing factor or a pricing difference. | `PNL PNL-COMM-1 <GO>` |
| <a id="term-counterparty"></a>**Counterparty** | A firm the bank trades with: its rating, sector, country of risk, group, credit limit and netting sets. | `CPTY CP-ALDERSHOT <GO>` |
| <a id="term-counterparty-credit-risk"></a>**Counterparty credit risk** | The risk that a counterparty defaults while it owes the bank money on its trades. Unlike a loan, the amount owed moves with markets, so it is simulated. | `EXP BANK <GO>` |
| <a id="term-counterparty-group"></a>**Counterparty group** | Counterparties that belong together for credit (a parent and its subsidiaries), with one group limit. The scope `GROUP:<id>` adds their exposures. | `GRP GRP-ALDERSHOT <GO>` |
| <a id="term-master-agreement"></a>**Master agreement** | The legal contract (ISDA for derivatives, GMRA for repo, GMSLA for securities lending) under which all trades with a counterparty sit, including close-out netting. | `AGR AGR-ALDERSHOT-ISDA <GO>` |
| <a id="term-close-out-netting"></a>**Close-out netting** | The right, on default, to end every trade under an agreement and settle one net amount. Exposure may be netted only where a legal opinion says it is enforceable. | `AGR AGR-ALDERSHOT-ISDA <GO>` |
| <a id="term-netting-set"></a>**Netting set** | The trades that net on close-out under one agreement and CSA. In Drishti one per counterparty and bank legal entity: `NS-<counterparty>-<legal entity>`. | `NSET NS-ALDERSHOT-LDN <GO>` |
| <a id="term-csa"></a>**CSA** (credit support annex) | The part of the agreement that sets the collateral terms: thresholds, minimum transfer amount, independent amount, eligible assets, margin frequency and the margin period of risk. | `CSA CSA-ALDERSHOT-FRA <GO>` |
| <a id="term-threshold"></a>**Threshold** | The exposure one side may run before the other may call collateral. Exposure up to the threshold stays uncollateralised. | `CSA CSA-ALDERSHOT-FRA <GO>` |
| <a id="term-mta"></a>**MTA** (minimum transfer amount) | The smallest collateral movement either side may call; smaller changes wait. | `CSA CSA-ALDERSHOT-FRA <GO>` |
| <a id="term-independent-amount"></a>**Independent amount** | Collateral posted up front whatever the exposure, a cushion on top of variation margin. | `CSA CSA-ALDERSHOT-FRA <GO>` |
| <a id="term-variation-margin"></a>**Variation margin** | Collateral that follows today's value of the trades, called as it changes (daily under most CSAs). | `MC MC-ALDERSHOT-FRA-1 <GO>` |
| <a id="term-initial-margin"></a>**Initial margin** | Collateral held against how much the value could move before a defaulted counterparty is closed out. Usually segregated; the bank keeps what it receives on every path. | `SIMM SIMM-ALDERSHOT <GO>` |
| <a id="term-mpor"></a>**MPOR** (margin period of risk) | The time between the last collateral received and closing out a defaulted counterparty (10 business days in the demo). Exposure is measured over it. | `CSA CSA-ALDERSHOT-FRA <GO>` |
| <a id="term-collateral-balance"></a>**Collateral balance** | The collateral held from, and posted to, a counterparty under a CSA for one netting set, after haircuts. Net = held minus posted. | `COLL COLL-ALDERSHOT-LDN <GO>` |
| <a id="term-margin-call"></a>**Margin call** | A demand for collateral under a CSA, its amount, who calls whom, its status and any disputed part. | `MC MC-ALDERSHOT-FRA-1 <GO>` |
| <a id="term-credit-limit"></a>**Credit limit** | The most exposure the bank accepts to a counterparty on a measure (usually peak PFE at 95%), with its use. Above 100% is a breach. | `LIM LIM-ALDERSHOT <GO>` |
| <a id="term-simulation-run"></a>**Simulation run** | The upstream exposure simulation of a business date: how many market paths (200 in the demo), the grid of future dates and the look-back. | `XRUN XR-2026-10-26-01 <GO>` |
| <a id="term-exposure-path"></a>**Exposure path** | One trade's mark to market on every simulated path at one grid date. Every trade uses the same paths, so values add path by path. | `drishti.py exposure status` |
| <a id="term-exposure"></a>**Exposure** | What a counterparty would owe the bank on a path and date after netting and collateral, never below zero: max(value - collateral, 0). | `EXP BANK <GO>` |
| <a id="term-ce"></a>**CE** (current exposure) | Today's exposure: the netting set's mark to market less the collateral held, floored at zero. It adds up across netting sets. | `EXP NS-ALDERSHOT-NY <GO>` |
| <a id="term-ee"></a>**EE** (expected exposure) | The average exposure over all paths at a future date: the input to CVA. It adds up across netting sets. | `EXP NS-ALDERSHOT-LDN <GO>` |
| <a id="term-ene"></a>**ENE** (expected negative exposure) | The average of what the bank would owe the counterparty at a future date, reported negative: the input to DVA. | `EXP NS-ALDERSHOT-LDN <GO>` |
| <a id="term-eee"></a>**EEE** (effective expected exposure) | EE that never falls: the running maximum of EE over the dates. Regulators use it so a trade maturing soon does not hide a later roll-over. | `EXP NS-ALDERSHOT-LDN <GO>` |
| <a id="term-pfe"></a>**PFE** (potential future exposure) | The bad case: the exposure at a future date that only 5% (or 1%) of paths exceed. Credit limits are set on it. It does not add up. | `EXP CP-ALDERSHOT <GO>` · `drishti.py exposure show CPTY:CP-ALDERSHOT` |
| <a id="term-peak-pfe"></a>**Peak PFE** | The largest PFE over the future dates, and when it happens: the single number most credit limits use. | `EXP CP-ALDERSHOT <GO>` |
| <a id="term-epe"></a>**EPE** (expected positive exposure) | EE averaged over the first year (or to the last maturity if sooner): one average figure for the counterparty. | `EXP CP-ALDERSHOT <GO>` |
| <a id="term-eepe"></a>**EEPE** (effective expected positive exposure) | EEE averaged over the first year: the regulatory figure under the internal model method. EEPE is never below EPE. | `EXP CP-ALDERSHOT <GO>` |
| <a id="term-ead"></a>**EAD** (exposure at default) | The exposure capital is held against. Under the internal model method alpha (1.4) times EEPE; Drishti shows it for information, it is not a capital figure. | `EXP BANK <GO>` |
| <a id="term-exposure-profile"></a>**Exposure profile** | EE, ENE, EEE and PFE of a scope at each future date (1W to 10Y): the shape of the exposure over time. Computed when asked (kind exposure-result). | `EXP NS-ALDERSHOT-LDN <GO>` · `drishti.py exposure profile NSET:NS-ALDERSHOT-LDN` |
| <a id="term-netting-benefit"></a>**Netting benefit** | How much lower a netting set's exposure is than the sum of its trades' exposures alone, because gains and losses on its trades offset. | `drishti.py exposure parts NSET:NS-ALDERSHOT-LDN --by trade` |
| <a id="term-collateral-effect"></a>**Collateral effect** | The change in EE that collateral makes at the peak date. Collateral held lowers exposure; collateral the bank has posted can raise it. | `EXP NS-ALDERSHOT-LDN <GO>` |
| <a id="term-exposure-what-if"></a>**Exposure what-if** | What a new trade (or an unwind) would do to a counterparty's PFE before it is dealt: exactly on the paths, or by a conservative add-on proxy. | `drishti.py exposure whatif CPTY:CP-ALDERSHOT --like CLY-4003028 --factor -1 --set NS-ALDERSHOT-FRA` |
| <a id="term-official-exposure"></a>**Official exposure** | The end-of-day exposure figures of every netting set and counterparty, captured and published once a day as the record of the date. | `drishti.py exposure official` |
| <a id="term-cva"></a>**CVA** (credit valuation adjustment) | The market price of the counterparty's possible default, taken off the trades' value: roughly loss given default times EE weighted by default probability. Stored from an upstream XVA engine; Drishti does not compute it yet. | `CVA CVA-ALDERSHOT-FRA <GO>` |
| <a id="term-dva"></a>**DVA** (debit valuation adjustment) | CVA's mirror: the value of the bank's own possible default. FVA prices funding and KVA the cost of capital. All stored on the CVA record; not computed by Drishti. | `CVA CVA-ALDERSHOT-FRA <GO>` |
| <a id="term-sa-ccr"></a>**SA-CCR** (standardised approach for counterparty credit risk) | Basel's formula for EAD without a model: 1.4 times (replacement cost plus multiplier times add-ons by asset class). Stored from an upstream engine; Drishti does not compute it. | `SACCR SACCR-ALDERSHOT-FRA <GO>` |
| <a id="term-simm"></a>**SIMM** (ISDA Standard Initial Margin Model) | The industry model for initial margin on uncleared derivatives, from sensitivities by risk class. Stored, with IM posted and received. | `SIMM SIMM-ALDERSHOT <GO>` |
| <a id="term-ccp"></a>**CCP** (central counterparty) | A clearing house that steps between the two sides of a cleared trade, with its own margin model and default fund. | `CCP CCP-CME <GO>` |
| <a id="term-wrong-way-risk"></a>**Wrong-way risk** | When exposure to a counterparty rises as its credit worsens (it sold you protection on itself, or on its own country). Designed in the contracts; not computed by Drishti yet. |  |
| <a id="term-trade"></a>**Trade** | One deal as the booking system holds it: who, what, how much, when, in which book and legal entity, with its legs and cash-flow events. | `TRD 1000001 <GO>` |
| <a id="term-source-system"></a>**Source system** | The system that booked the trade: ENDUR (numeric ids), MUREX (MX-), CALYPSO (CLY-) in the demo. Drishti reads all three as one trade kind. | `TRD 1000001 <GO>` |
| <a id="term-trade-status"></a>**Trade status** | Where the trade is in its life (NEW, VALIDATED, AMENDED, CANCELLED, MATURED). Risk counts NEW, VALIDATED and AMENDED. | `TRD 1000001 <GO>` |
| <a id="term-product-type"></a>**Product type** | What the trade is: a product type as the system names it (ComSwap, IRS, FXFwd) in a product group (Rates, FX, Credit, Equity, Commodity). | `TRD 1000001 <GO>` |
| <a id="term-notional"></a>**Notional** | The size the trade's payments are computed on. It is not what can be lost: a 100m swap may be worth 2m. | `TRD 1000001 <GO>` |
| <a id="term-mtm"></a>**MTM** (mark to market) | The trade's value today at market prices, positive when the counterparty owes the bank. The risk engine's figure is on the trade valuation. | `TVAL TVAL-1000001 <GO>` |
| <a id="term-dv01"></a>**DV01** | The change in value for a one basis point (0.01%) rise in rates. | `TVAL TVAL-1000001 <GO>` |
| <a id="term-leg"></a>**Leg** | One side of a trade's payments: a swap has a pay leg and a receive leg, each with its notional, rate or index and schedule. | `TRD 1000001 <GO>` |
| <a id="term-cash-flow-event"></a>**Cash-flow event** | A dated payment or fixing of a trade. Counterparty trades sums the next 7, 30 and 90 days' payments. | `CTRD CP-ALDERSHOT <GO>` |
| <a id="term-book"></a>**Book** | The account a trade is booked in, owned by one desk. Books are the leaves of both risk hierarchies. | `BOOK BOOK-RATES-1 <GO>` |
| <a id="term-desk"></a>**Desk** | A trading team and its books (Rates, FX, Credit, Commodities ...), with a head, a legal entity and limits. | `DESK DESK-RATES <GO>` |
| <a id="term-trader"></a>**Trader** | The person who booked the trade, with their desk and books. | `TRDR TRDR-ASHAH <GO>` |
| <a id="term-counterparty-trades"></a>**Counterparty trades** | Every trade with one counterparty from every source system, grouped when asked: counts, notional by product, coming payments. | `CTRD CP-ALDERSHOT <GO>` |
| <a id="term-legal-entity-trades"></a>**Legal entity trades** | Every trade booked by one of the bank's legal entities, grouped when asked. | `LTRD LE-LDN <GO>` |
| <a id="term-trade-valuation"></a>**Trade valuation** | The risk engine's daily figures for a trade: MTM, one-day P&L and DV01, with its netting set. | `TVAL TVAL-1000001 <GO>` |
| <a id="term-maturity"></a>**Maturity** | The date the trade ends. Exposure profiles fall as trades mature; a netting set's EPE horizon stops at its last maturity when that is under a year. | `TRD 1000001 <GO>` |

<!-- glossary:end -->

## 5. Where to go deeper

- [VaR guide](../guides/VAR_GUIDE.md): every measure with worked numbers, the explorer, scopes, captures, backtesting, the data contract;
  [the scenario matrix](../guides/VAR_GUIDE.md#39-the-scenario-matrix-smat) (`SMAT`): a trade, position or node's scenarios across its
  last N business dates, the tail outlined each day.
- [Tutorial 2, Part A: VaR from the bank to a trade](../tutorials/02-var-from-the-bank-to-a-trade.md#part-a--historical-var-from-the-bank-to-a-trade): fifteen hands-on steps, the last the scenario matrix.
- [Exposure guide](../guides/EXPOSURE_GUIDE.md): every exposure measure with worked numbers, the explorer, collateral, the pre-deal what-if, the data contract.
- [Tutorial 3: exposure from the bank to a trade](../tutorials/03-exposure-from-the-bank-to-a-trade.md): eight hands-on steps.
- [Tutorial 1, Part E: an entity over time](../tutorials/01-first-15-minutes.md#part-e--an-entity-over-time) (`TRD 1000015 30 <GO>`).
- [Groupings and analytics guide](../guides/GROUPINGS_ANALYTICS_GUIDE.md).
- The stored results' contracts: [NSET](../contracts/NSET.md), [CVA](../contracts/CVA.md), [SA-CCR](../contracts/SACCR.md),
  [SIMM](../contracts/SIMM.md), [collateral](../contracts/COLL.md), [margin calls](../contracts/MC.md), [limits](../contracts/LIM.md),
  [groups](../contracts/GRP.md), [wrong-way risk](../contracts/WWR.md); the [trading groupings contract](../contracts/TRADING_GROUPINGS.md)
  (CTRD, LTRD, PTRD, ITRD) and [trade valuations](../contracts/TVAL.md).
- [Data](data.md): [entities and ownership](data.md#2-entities-and-ownership) (who the counterparties and the bank's legal entities
  are) and [data and contracts](data.md#3-data-and-contracts) (fundamental, input and computed data; where the trades come from and how
  they are loaded).
