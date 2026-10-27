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
# Tutorial 25 · VaR from the bank to a trade

Value at risk answers *how much could this lose in a day, and where does that loss come from?* In this tutorial you build the market-risk
pack's VaR data into a lake, open the bank's VaR, drill down to one trade without a page load, read the P&L path and the contributions,
write your own scopes (a desk minus a book plus a portfolio; a rule), see how Drishti derives positions, save a basket and capture it,
read the history and the backtest, look at how the date sits in memory and on disk, read VaR as an ordinary computed kind, and ask the
Python engine what a hedge would do. Each step shows the UI, the command line (`drishti.py var …`) and the YAML behind it, with real
output from the committed test data. Everything else about VaR (every measure, column, setting, endpoint and error code, the data
contract and how the engine stores a date) is in the one reference, [VAR_GUIDE.md](../guides/VAR_GUIDE.md); each step ends with the guide's sections
that go further.

## Time to complete

About 40 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done: a server and console you can build, start and stop.
- The market-risk pack loaded (it is in the quick-start packs).
- For the command-line steps: `DRISHTI_SERVER=http://localhost:18480` and `DRISHTI_TOKEN=...` (My account → API tokens), or `--user`
  with sign-in off. The outputs below were captured as the user `rita` with sign-in off.
- For step 11 only: the compute workers (a Python 3.10 or newer; the console's own is enough, the VaR engine uses the standard library).

The VaR data is **synthetic** (a correlated, fat-tailed factor market over the committed trades), not prices.

## What you will learn

- where the VaR inputs live (one P&L vector of 509 scenario days per live trade) and how a date is loaded;
- what VaR, ES, component, incremental VaR and diversification mean on real numbers, and that components add up;
- how to drill from the bank to a trade, and how longs and shorts net in a position;
- the scope grammar: nodes, positions, trades, portfolios, rules, with `+`, `-` and `&`;
- how positions are derived by the pack's rules, never delivered;
- baskets, captures and compare; the history, the traffic light, Kupiec's and Christoffersen's tests;
- the memory tiers: the date's memory-mapped hot file, float32 rows summed in float64;
- VaR as a kind (`VRES`) and a what-if by the Python engine (`VWIF`).

## Step 1 · Build the VaR data and open the bank

**From the command line.** Build the four VaR tables into the lake on the test data's own date (2026-09-30), so your numbers are the
ones below (`--as-of today`, the default, moves the date to your today and trades that have matured since drop out):

```bash
python3 tools/drishti.py lake demo --as-of data \
  --only market-risk/pnl-vector,market-risk/scenario-pv,market-risk/fx-scenario,market-risk/scenario-set,market-risk/risk-hierarchy,market-risk/portfolio
```

Start the server and console (tutorial 1), then ask for the bank:

```text
$ drishti.py var show BANK
VAR BANK  ·  Drishti Bank  ·  node  ·  2026-09-30
  VaR 99%   112,041,646 USD   (the 6th worst of 509)
  ES 97.5%   160,143,809 USD   (mean of the 13 worst)
  limit      150,000,000 USD   use 74.7%
  parts      2 division   standalone sum 130,101,896   diversification 18,060,250
  backtest   amber  5 exceptions in 249 days   Kupiec p 0.160   Christoffersen p 0.076
  9572 trades in 7984 positions
  pnl-vector and scenario-pv (1339 trades from scenario PVs) · batch B-2026-09-30-01 of 2026-09-30 · data version …
  in USD, crosses through USD; FX CAD supplied (0.719942 USD); EUR supplied (1.174 USD)
  node aggregate (float64) · historical, equal weights, 1-day, k = 6th worst of 509 · 1 ms (hit)
```

**In the UI.** Type `VAR BANK` and press Enter:

![VAR BANK: the hierarchy with every node's VaR and limit bar, the six tiles, the P&L path](img/t25/01-bank.jpg)

**What it reads.** The market-risk pack's connector `risk-store` keeps the P&L vector as a `list<double>` column of the `pnl-vector`
table, and (2.9.4) the trades a batch sends as **scenario PVs** in their own currency as the `list<double>` column `SCENARIO_PV` of
the `scenario-pv` table, with each currency's scenario FX in `fx-scenario` (`config/packs/market-risk/pack.yaml`):

```yaml
connectors:
  risk-store:
    plugin: delta
    settings:
      domain: risk
      layout:
        pnl-vector:
          columns: [TRADE_ID, SECURITY_ID, INSTRUMENT, LEGAL_ENTITY, DIVISION, DESK, TT_BOOK, PORTFOLIO, ..., QUANTITY, PV, TRADE_STATUS,
                    MATURITY_DATE, STATUS, REPORTING_CCY, SCENARIO_SET_ID, BATCH_ID]
          vectors: [PNL]
        scenario-pv:
          columns: [TRADE_ID, ..., CURRENCY, ..., BASE_PV, VALUATION_DATE, BASE_VALUATION_DATE, ..., SCENARIO_SET_ID, BATCH_ID]
          vectors: [SCENARIO_PV]
        fx-scenario:
          columns: [CURRENCY, RATE, SCENARIO_SET_ID]
```

The demo's Endur trades (1,339) come that way: when the date loads, the engine turns each into its USD P&L,
`PV(s) × FX(s) − PV(base) × FX(base)`, and from then on they are trades like any other.

**You should see** VaR 112,041,646 and ES 160,143,809 on 2026-09-30, over 9,572 trades in 7,984 positions, 1,339 of them from scenario
PVs. On the 6th worst of the 509 days the bank loses 112 million; the two divisions alone would add up to 130 million: holding them
together saves 18 million. (Until 2.9.3 every trade was a P&L vector and the bank's VaR was 112,035,906: the 250 Endur trades in EUR and
CAD now also carry the move of their currency on their value.)

**In another currency.** Every `drishti.py var` command takes `--ccy` (the API: `ccy=EUR`); the console's currency picker is not built
yet:

```text
$ drishti.py var show BANK --ccy EUR
VAR BANK  ·  Drishti Bank  ·  node  ·  2026-09-30
  VaR 99%   111,837,045 EUR   (the 6th worst of 509)
  ES 97.5%   137,631,546 EUR   (mean of the 13 worst)
  …
  in EUR, crosses through USD; FX CAD supplied (0.719942 USD); EUR supplied (1.174 USD)
  …
$ drishti.py var show BANK --ccy INR
the server said 422 DRS-5207: DRS-5207 no INR FX for the scenarios of 2026-09-30: INR: neither supplied (fx-scenario) nor in the FX history
(fx-spot); the currencies with FX on the date: AUD, CAD, CHF, EUR, GBP, JPY, USD
```

**You should see** 111.8 million EUR, not the 95.4 million that 112.0 million USD makes at today's 1.174: in euros the bank's whole value
(3.65 billion USD) moves with the euro on every scenario day, and that move is part of a euro-based owner's risk. The first `--ccy EUR`
of the date builds the euro book from the USD one (once; `drishti.py var status` lists it with `"currency": "EUR"`).

**Further in the guide:** [section 15.1](../guides/VAR_GUIDE.md#151-where-they-live) (the inputs), [section 2.2](../guides/VAR_GUIDE.md#22-on-the-test-data) (the bank's numbers read), [section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency-as-built-294) (scenario PVs and their FX), [section 15.10](../guides/VAR_GUIDE.md#1510-reporting-currency-as-built-294) (reporting currencies).

## Step 2 · Drill down from the bank to a trade

**In the UI.** Click *Markets*, then *Rates · Swaps and options*, then `BOOK-RATES-1`, open the **Contributions** tab, click a position,
then one of its trades. Every panel moves in place; the breadcrumb grows (bank › division › desk › book › position › trade); the
browser's Back steps back up.

![A bond future in BOOK-RATES-1: the breadcrumb from the bank, its own VaR and ES](img/t25/02-trade.jpg)

A book lists its positions, each LONG or SHORT, with its VaR and its component in the book:

![BOOK-EQD-2's positions with their standalone and component VaR](img/t25/03-book-positions.jpg)

**From the command line.** The tree of either hierarchy with every node's figures:

```text
$ drishti.py var tree --hierarchy LEGAL
Node                             VaR          ES           Limit use  Trades
-------------------------------  -----------  -----------  ---------  ------
Drishti Bank                     112,041,646  160,143,809      53.4%    9572
  LE-FRA                           1,536,322    1,665,992      66.8%     611
  LE-LDN                          48,350,793   53,764,068      63.6%    2679
    Equity derivatives            35,827,767   34,901,016      77.9%    1201
      BOOK-EQD-2                  33,619,633   66,673,176      65.9%     374
  LE-NY                          144,262,258  174,617,643      80.1%    6008
    Credit trading               130,670,864  189,194,564      65.3%     943
```

Other ways in: `VAR LE LE-NY`, `VAR BOOK BOOK-RATES-1`, `VAR CPTY CP-SUMMIT` (every trade with a counterparty), `VAR PORT
PF-USD-RATES`; the retired stored ids still work (`VAR VAR-RATES` opens `DESK:RATES` and says so).

(The output lists every node; a few rows are shown.) The same with `--hierarchy MGMT`, and in the UI with the *Legal entity* choice above
the tree.

**You should see** that New York alone (144.3 million) has a larger VaR than the whole bank: London's and Frankfurt's trades gain on
New York's worst days.

**Further in the guide:** [section 3.3](../guides/VAR_GUIDE.md#33-drill-down-bank-desk-book-position-trade) (drill down), [section 10](../guides/VAR_GUIDE.md#10-the-command-line) (the command line).

## Step 3 · Read the P&L path: what the VaR is

**In the UI.** `VAR DESK DESK-RATES`: the distribution of the desk's 509 scenario P&Ls with the VaR and ES lines, the 25 worst sorted,
the P&L by scenario date, and the ranked table. The highlighted row is rank 6: the VaR.

![The rates desk's P&L path: the distribution, the worst 25, the P&L by date and the ranked table](img/t25/04-path.jpg)

**From the command line:**

```text
$ drishti.py var path DESK:RATES --size 8
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

**What it means.** At 99% the VaR is the 6th worst of 509 days (509 × 1% = 5.09, rounded up): 74,633,680. ES at 97.5% is the mean of
the 13 worst (509 × 2.5% = 12.7, rounded up): 75,377,415. Click a row in the UI to see which books lost what that day.

**You should see** rank 6 (2025-12-24) equal to the VaR, and `BOOK-RATES-3` losing most on five of the six worst days.

**Further in the guide:** [section 3.4](../guides/VAR_GUIDE.md#34-the-tabs) (the tabs), [sections 18.1 and 18.2](../guides/VAR_GUIDE.md#181-var) (the formulas).

## Step 4 · Contributions: who makes the VaR

**In the UI.** The **Contributions** tab of the desk: its three books, their standalone, component, incremental and marginal VaR.

![The rates desk's three books: components adding up to the desk's VaR](img/t25/05-contributions.jpg)

**From the command line:**

```text
$ drishti.py var parts DESK:RATES
DESK:RATES  by book  VaR 74,633,680  ES 75,377,415  sum of components 74,633,680
Part          Standalone VaR  Component VaR  % of VaR  Incremental VaR  Component ES
------------  --------------  -------------  --------  ---------------  ------------
BOOK-RATES-1      33,153,425     30,612,282      41.0       -5,131,638    10,403,265
BOOK-RATES-2      30,872,370     25,280,825      33.9       21,654,002    20,263,766
BOOK-RATES-3      61,499,103     18,740,573      25.1       26,690,191    44,710,384
```

`--by position` or `--by trade` breaks the desk down further (`drishti.py var parts BOOK:BOOK-EQD-2 --by position --top 8`).

**You should see** the components adding up to the desk's VaR to the dollar (74,633,680), and `BOOK-RATES-1`'s **negative** incremental
VaR: take the book out and the desk's VaR rises by 5.1 million. `BOOK-RATES-3` is the riskiest alone (61.5 million) but contributes the
least: its worst days are not the desk's.

**Further in the guide:** [section 2](../guides/VAR_GUIDE.md#2-the-measures-with-worked-numbers) (the measures by hand), [section 18.3](../guides/VAR_GUIDE.md#183-decomposition-of-a-scope-into-parts) (the decomposition).

## Step 5 · Your own scope: nodes, portfolios, rules

**In the UI: Construct your basket.** On the rates desk, click **+ basket** on its row in the tree (point at the row to see it): the
basket tray opens as a strip at the top. Type `pf usd` in it: the engine's hints list *PORT · PF-USD-RATES · USD rates exposure*, its
trades and its VaR alone; **Enter** adds it. Type `-book-rates-1` and **Enter**: the book is taken away (a dashed **−** chip; the **+ / −**
on any chip switches it). As you pick, the tray shows the selection's VaR and ES, its trades, the sum of the parts' VaRs and the
diversification, computed by the engine. **Open without saving** moves the explorer to it: *unsaved basket*, its contributions are its
parts. Anywhere else in the console the top bar's **◫ Basket** (or **Alt+B**) opens the same tray as a popup.

![Construct your basket: the rates desk from its row in the tree, the USD rates portfolio from the hints, a book taken away; the selection's VaR live and the explorer on it, its contributions by part](img/t25/06-basket.jpg)

The tray writes `NODE:MGMT~DESK-RATES + PORT:PF-USD-RATES - NODE:MGMT~BOOK-RATES-1`: the parts to add, then those to take away. A part
that matches nothing on the date (try `TRD:10000000`) turns red at once and the tray says why (*not a live trade on 2026-09-30 (no P&L
vector)*), never a VaR of 0.

**From the command line**, a desk without another desk, and the desk without one trade:

```text
$ drishti.py var show 'BANK - DESK:CREDIT'
VAR BANK - DESK:CREDIT  ·  BANK - DESK:CREDIT  ·  adhoc  ·  2026-09-30
  VaR 99%   76,607,919 USD   (the 6th worst of 509)
  ES 97.5%   76,758,288 USD   (mean of the 13 worst)
  ...
  8629 trades in 7041 positions
$ drishti.py var show 'DESK:RATES - TRD:MX-30000121' --json        (abridged)
{"canonical": "DESK:RATES - TRD:MX-30000121", "kind": "adhoc", "var": 74720890, "es": 75093413, ...}
```

and a rule over the positions' columns:

```text
$ drishti.py var show "WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'"
VAR WHERE:CURRENCY == 'EUR' and DIRECTION == 'SHORT'  ·  ...  ·  rule  ·  2026-09-30
  VaR 99%   133,970,477 USD   (the 6th worst of 509)
  ES 97.5%   140,222,022 USD   (mean of the 13 worst)
  parts      595 position   standalone sum 230,956,330   diversification 96,985,853
  700 trades in 595 positions
```

**The grammar** (the full table is in [VAR_GUIDE.md section 5.2](../guides/VAR_GUIDE.md#52-the-grammar)): nodes (`BANK`, `DESK:RATES`,
`BOOK:…`, `LE:NY`), `POS:`, `TRD:`, `CPTY:`, `PORT:`, `BASKET:`, `CAPTURE:`, `WHERE:<rule>`, combined with `+` (union), `-` (minus) and `&`
(both, binding first); operators stand between spaces, so `MX-30000121` stays one id. A bad scope is refused in words:

```text
$ drishti.py var show 'DESK:('
drishti: the server said 400 DRS-5001: DRS-5001 DESK: names nothing
```

**You should see** that the rates desk without the swap `MX-30000121` has a VaR of 74,720,890, 87,210 more than with it: that one swap
hedges the desk (its incremental VaR is −87,210).

**Further in the guide:** [section 5](../guides/VAR_GUIDE.md#5-scopes-portfolios-and-baskets) (scopes, portfolios and baskets).

## Step 6 · Positions are derived, never delivered

**In the UI.** `POS POS-BOOK-EQD-2~SEC-WARRANT-USD-01`: nine trades of one warrant in one book, each with its own VaR and its component
of the position's.

![A position's page: nine trades of one warrant, longs and shorts netting](img/t25/09-position-page.jpg)

**The YAML.** The market-risk pack's `positions:` rules decide what a position is, each time a date loads:

```yaml
positions:
  live-statuses: [NEW, VALIDATED, AMENDED]
  rules:
    - name: securities                  # by book and security
      groups: [Fixed income]
      types: [EQ_CASH, WARRANT, CONVERTIBLE_BOND, INFL_LINKED_BOND, T_BILL, COMMERCIAL_PAPER, CERT_DEPOSIT]
      key: [SECURITY_ID, ISIN, TICKER]
    - name: listed                      # by book and exchange contract
      types: [BOND_FUTURE, STIR_FUTURE, STIR_FUT_OPT, EQ_FUTURE_SINGLE, EQ_INDEX_FUTURE, EQ_OPTION_LISTED, EQ_INDEX_OPTION, CMD_FUTURE,
              CMD_FUT_OPTION, ComFut, EMISSIONS_FUTURE]
      key: [SECURITY_ID, CONTRACT]
    - name: otc                         # everything else: one position per trade
```

**From the command line:**

```text
$ drishti.py var show 'POS:POS-BOOK-EQD-2~SEC-WARRANT-USD-01'
VAR POS:POS-BOOK-EQD-2~SEC-WARRANT-USD-01  ·  POS-BOOK-EQD-2~SEC-WARRANT-USD-01 · Equity warrant USD 2027-05  ·  position  ·  2026-09-30
  VaR 99%   67,209 USD   (the 6th worst of 509)
  ES 97.5%   70,531 USD   (mean of the 13 worst)
  parts      9 trade   standalone sum 111,930   diversification 44,721
  9 trades in 1 positions
```

**You should see** a position VaR (67,209) far below its trades' VaRs added up (111,930): buys and sells of one warrant net before the
quantile is taken. The bond future of step 2 is in `POS-BOOK-RATES-1~XCH-BOND_FUTURE-AUD-02` (the *listed* rule); a swap is a position of
its own (`POS-<book>~<trade>`, the *otc* rule).

**Further in the guide:** [section 4](../guides/VAR_GUIDE.md#4-positions-derived-never-delivered) and [section 15.6](../guides/VAR_GUIDE.md#156-positions-derived-by-drishti-as-built) (every rule and column).

## Step 7 · Save a basket, capture it, compare

**In the UI.** In the basket tray of step 5, name it (`rates-mix`) and **Save basket and open VaR** (tick nothing more: it is private;
name users or roles in *Share read-only with* to share it). The explorer opens `VAR SCOPE BASKET:<you>/rates-mix`, the tray reads
*saved basket · \<you>/rates-mix*, and the basket is listed under *Ad-hoc scopes* as ★ rates-mix. Opening it later (from the list, a
link, `VAR SCOPE rates-mix <GO>`) loads its parts back into the tray to edit and save again. Then the **Captured results** tab, a note,
**Capture VaR**, and **Compare with now**:

![A capture of the FX desk compared with now](img/t25/07-capture.jpg)

**What-if, before you deal.** **+ What-if trade** in the tray: a booked trade (say one of the desk's swaps) and a factor (−1 its unwind):
the basket's VaR and ES before → after and the incremental, by the Python VaR engine. It needs the compute workers
(`DRISHTI_COMPUTE_ENABLED=true`); without them the tray says so ([VAR_GUIDE.md section 8.5](../guides/VAR_GUIDE.md#85-the-pre-deal-what-if-in-the-basket-tray)).

**From the command line**, a basket of the rates desk's shorts, saved, captured and compared:

```text
$ drishti.py var basket rates-short "DESK:RATES & WHERE:DIRECTION == 'SHORT'" --title 'Rates shorts'
{ "id": "rita/rates-short", "name": "Rates shorts", "type": "BASKET", "scope": "DESK:RATES & WHERE:DIRECTION == 'SHORT'",
  "owner": "rita", "sharedWith": [], "version": 1, ... }
$ drishti.py var capture BASKET:rita/rates-short --note 'before the unwind'
{ "id": "CAP-20260930-89", "capturedAt": "2026-10-26T05:12:54.392356455Z", "var": 282428035, "es": 271474611, "memberCount": 800 }
$ drishti.py var compare CAP-20260930-89
{ "a": { "id": "CAP-20260930-89", "label": "Rates shorts", "var": 282428035, ... },
  "b": { "id": "now", "var": 282428035, ... },
  "varChange": 0.0, "esChange": 0.0, "membersAdded": [], "membersRemoved": [], "components": [ ... ] }
```

**CSV** on the capture downloads its vector by scenario date (or, with `?part=components`, its components). Your capture id differs
(the content store numbers them). The basket opens anywhere as `BASKET:rita/rates-short`, or `VAR SCOPE
rates-short <GO>`.

**You should see** the shorts alone with a VaR of 282.4 million, nearly four times the desk's 74.6 million: the desk's longs hedge them.
A capture never changes: restate the batch and it still reopens with these numbers.

**Further in the guide:** [section 5.8](../guides/VAR_GUIDE.md#58-baskets) (baskets), [section 6](../guides/VAR_GUIDE.md#6-captures-and-compare) (captures and compare).

## Step 8 · History and the backtest

**In the UI.** The **VaR / ES history** tab of `VAR DESK DESK-FX`: VaR and ES over 250 days, the P&L, exceptions in red, the traffic
light with Kupiec's and Christoffersen's tests.

![The FX desk's history and backtest](img/t25/08-history.jpg)

**From the command line:**

```text
$ drishti.py var history DESK:FX
history (replay): today's members over the last 250 scenario days against today's VaR: in-sample, not a regulatory backtest
  green  3 exceptions in 249 days   Kupiec LR 0.099 (p 0.753)   Christoffersen LR 0.073 (p 0.786)
Date        VaR         ES          P&L          Exception
----------  ----------  ----------  -----------  ---------
2026-09-09  24,060,533  25,240,488  -15,013,476
2026-09-10  24,060,533  25,240,488   -2,107,799
...
```

**What it means.** With only one official date the history *replays* today's trades over the last 250 scenario days against today's
VaR (in-sample). From the second official end-of-day capture of a node, the history is the official one (each day's VaR against the next
day's hypothetical P&L). Green is up to 4 exceptions in 250 days, amber 5 to 9, red 10 or more; a Kupiec p-value below 0.05 says the
exceptions are too many or too few, a Christoffersen p-value below 0.05 that they cluster.

**You should see** green with 3 exceptions for the FX desk (2.5 expected at 99%); the bank replays amber with 5.

**Further in the guide:** [section 7](../guides/VAR_GUIDE.md#7-history-and-backtesting) (history), [section 18.4](../guides/VAR_GUIDE.md#184-history-and-backtest) (the backtest's formulas), [section 19](../guides/VAR_GUIDE.md#19-official-end-of-day-results) (official records).

## Step 9 · How the date sits in memory and on disk

**In the UI.** *How this number was built*, on the right: **Tier** says where the vector came from. A node is its float64 aggregate; any
other scope sums the date's float32 position rows in float64:

![How this number was built: the rates desk without a book, from the hot file](img/t25/10-provenance.jpg)

**From the command line:**

```text
$ drishti.py var status        (abridged)
  "tier": { "mode": "mapped", "diskDates": 60, "keepOnShutdown": false, "closeGraceSeconds": 60,
            "mapping": "shared arena (unmapped on close)", "built": 1, "reopened": 0, "files": 1, "open": 1, "diskBytes": 23770624 },
  "loaded": [ { "businessDate": "2026-09-30", "trades": 9572, "positions": 7984, "nodes": 88, "vectorBytes": 20457728,
                "tier": "mapped", "aggregateBytes": 480496, "hotFile": "2026-09-30/inputs-a9df6dd969f2a9c2.vhot", "loadMs": 572, ... } ]
```

**The YAML** (the server's `application.yaml`; every key has this default):

```yaml
drishti:
  var:
    hot-dates: 2                     # dates open at once
    tier:
      mode: ${DRISHTI_VAR_TIER:mapped}                 # mapped: a float32 hot file per date; heap: float64 on the heap
      dir: ${DRISHTI_VAR_HOT_DIR:${drishti.data.dir:./data}/var/hot}
      disk-dates: 60                 # hot files kept on disk
      keep-on-shutdown: false
      close-grace-seconds: 60
```

The first request of a date read the lake and wrote `var/hot/2026-09-30/inputs-….vhot` under the data folder (23.8 MB here, against 39
MB of float64 vectors on the heap before 2.8.0). An administrator's `drishti.py var reload` drops the open dates; the next request maps
the same file again (`reopened` goes up) without reading the lake. At shutdown the files are deleted.

**You should see** `mode: mapped`, one file, and `node aggregate (float64)` as the tier of every node; `hot file (float32 rows, summed in
float64)` for any other scope. On the test data both give exactly the lake's numbers ([VAR_GUIDE.md section
26.6](../guides/VAR_GUIDE.md#266-precision-float32-rows-float64-sums)).

**Further in the guide:** [section 26](../guides/VAR_GUIDE.md#26-the-tiered-memory-and-the-hot-file) (the tiers and the hot file), [section 27](../guides/VAR_GUIDE.md#27-sizes-performance-and-the-benchmark) (sizes and the benchmark).

## Step 10 · VaR as an ordinary kind

**In the UI.** `VRES DESK:DESK-RATES`: the computed kind `var-result` drawn like any entity, its worst scenarios, its stamp:

![VRES DESK:DESK-RATES: VaR as a computed kind](img/t25/11-var-result.jpg)

**From the command line:**

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
  DIVERSIFICATION 50891218
  computed 2026-10-26T05:12:54.554938067Z by java:var-engine@2.7.0, pin pin-e0cd3fe57e54607c, sha256:fd1912ea94a8fb93f1acb005507655d3
```

**The YAML** (the market-risk pack's connector, written to `connectors/var-engine.yaml` at first start):

```yaml
var-engine:
  plugin: reactive
  mode: reactive
  kinds:
    var-result:
      compute: var-result
      result: { required: [VAR, ES, PNL], types: { VAR: number, ES: number } }
  engine: { type: java, class: com.ash.drishti.server.var.VarResultEngine, version: "1.0" }
  triggers:
    - { on: data-version, kinds: [pnl-vector, risk-hierarchy, scenario-set, portfolio], affects: all }
```

**You should see** the explorer's figures (74,633,680 and 75,377,415): it is the same engine, computed as you, with your entitlements.

**Further in the guide:** [section 8.1](../guides/VAR_GUIDE.md#81-var-result-the-java-engine) (`var-result`).

## Step 11 · What would a hedge do? The Python engine

**On the server.** Start it with the compute workers on (`DRISHTI_COMPUTE_ENABLED=true DRISHTI_PYTHON=$PWD/drishti-console/.venv/bin/python`).
The pool `var` of `config/compute/compute.yaml` runs the Python VaR engine:

```yaml
pools:
  var:
    type: python
    engines: [drishti_compute.var.whatif:VarWhatIfEngine]
    environment: system            # standard library only: no package to approve
    workers: { min: 0, max: ${DRISHTI_COMPUTE_VAR_WORKERS:4} }
```

**From the command line.** A hypothetical trade is a P&L over the 509 scenario days (priced elsewhere, or copied from a booked trade's
vector) with a factor. Here: a hedge shaped like half of `BOOK-RATES-3`, sold (`new-hedge.json`):

```json
[{"LABEL": "hedge of BOOK-RATES-3 (half)", "FACTOR": -0.5, "PNL": [509 numbers: BOOK-RATES-3's vector, read with VRES BOOK:BOOK-RATES-3]}]
```

```text
$ drishti.py var whatif DESK:RATES --trades new-hedge.json --stress-window 250
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

The command made a private sandbox of the trades and asked the computed kind `var-whatif` (`VWIF DESK:RATES` in the UI). With `--json`
the answer also carries each trade's standalone VaR (34,296,045), its incremental VaR (−27,034,460) and its component in the desk after
the trade (−32,542,152), and the desk's P&L day by day before and after. **In the UI**, `VWIF DESK:RATES` (no sandbox) opens the
desk's stressed window; with a sandbox the id carries it (`DESK:RATES~sandbox=sbx-…`, as every computed id carries its parameters). The
sandbox is yours alone (`drishti.py compute sandboxes`).

**You should see** the desk's VaR fall by 27.0 million (from 74.6 to 47.6 million) and the stressed VaR (the worst run of 250
consecutive scenario days) from 85.3 to 59.6 million: the hedge takes out most of `BOOK-RATES-3`'s tail.

**Further in the guide:** [section 8.2](../guides/VAR_GUIDE.md#82-var-whatif-the-python-engine) and [section 8.3](../guides/VAR_GUIDE.md#83-where-it-runs-the-java-engine-and-the-python-engine-as-built-280) (the Python engine).

## Step 12 · Who sees what

**The YAML.** Restrict a role to nodes, at one of two levels:

```yaml
drishti:
  var:
    access:
      division-head: { "[MGMT~DIV-MARKETS]": aggregate }     # numbers of the division and every node beneath, never a position
      rates-risk:    { "[MGMT~DESK-RATES]": positions }      # the desk's numbers, positions and trades
```

**In the UI and on the command line**, as a user of `rates-risk`, `VAR BANK` shows the bank computed over the rates desk only, with
*Outside your view* counting the rest; `drishti.py var show 'POS:…'` for a position outside the desk answers `403 DRS-5002`. The
computed kinds of steps 10 and 11 answer the same way: they are computed as you.

**You should see** in *How this number was built*, as a restricted user, *View: restricted by node (… trades outside your
entitlement)* for the bank: the bank's 9,572 trades less the rates desk's 1,487, so 8,085 outside, and the bank's VaR equal to the desk's.

**Further in the guide:** [section 9](../guides/VAR_GUIDE.md#9-who-sees-what-entitlements-and-audit) (entitlements and audit).

## Next steps

- Read [VAR_GUIDE.md](../guides/VAR_GUIDE.md): the measures by hand on ten scenarios, the data flow and restatements, the scope grammar in full, the
  data contract (Part II), sizing for a bank of a million trades and the benchmark (Part III), troubleshooting.
- Load your own batch: the columns are in [VAR_GUIDE.md section 15](../guides/VAR_GUIDE.md#15-inputs-from-upstream-engines); send the data-load
  signal ([tutorial 7](07-load-data-daily.md)) and the date is reloaded and captured officially.
- Put a computed VaR on your own screen: a Sutra panel can read `var-result` like [tutorial 23](23-computed-panels.md)'s computed panels.

## When something goes wrong

| You see | Do |
|---|---|
| *no P&L vectors in any connector* | build the lake (step 1); is the market-risk pack loaded? |
| other numbers than these | you built the lake with `--as-of today`: trades matured since 2026-09-30 drop out; rebuild with `--as-of data` |
| the bank's VaR is 112,035,906 and the provenance says only `pnl-vector` | the lake has no `scenario-pv` table (built before 2.9.4, or `--only` without it): the Endur trades are missing too (8,233 trades); rebuild with the `--only` list of step 1 |
| `--ccy` answers `422 DRS-5207` | the date has no rates for that currency: supply them in `fx-scenario`, or load enough FX history in `fx-spot` ([VAR_GUIDE.md section 15.9](../guides/VAR_GUIDE.md#159-scenario-pv-scenario-pvs-per-trade-in-the-trades-currency-as-built-294)) |
| `PORT <id>` says *no source holds portfolio/…* | the page opens on today's date; pick **2026-09-30** in the date box (the explorer finds the newest date by itself) |
| step 11 answers *could not start* | the compute workers are off: `DRISHTI_COMPUTE_ENABLED=true`, and Admin → Compute shows the pool `var` |
| *the vectors of … were unloaded while this answer was computed* | ask again ([VAR_GUIDE.md section 13](../guides/VAR_GUIDE.md#13-troubleshooting)) |
