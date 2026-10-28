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
# Domain packs

Drishti's core knows no industry. It knows documents, shapes, the Rachana layout grammar, inference, links and live
updates. Everything that belongs to one industry (the commands you type, the screens, the sample data, the lakes the data
lives in, the help pages, the BI datasets) arrives in a **domain pack**: one folder under `config/packs/`.

This guide is for users and administrators. It explains, with examples checked on a running server:

1. [What a pack is](#what-a-pack-is), and [every pack that ships](#the-packs-that-ship), with its commands, its data and
   [the trading pack's several source systems](#trading).
2. [Where the seven packs read their data](#the-demo-lakes-where-the-seven-packs-read-their-data): the Delta lakes that one
   command builds from the committed test data.
3. [How to load packs](#turning-packs-on), [switch them off and on for everyone](#switching-packs-off-and-on-admin--packs)
   and [who sees which pack](#who-sees-which-pack).
4. [Where a pack reads its data](#packs-and-connectors-where-a-pack-reads-its-data): the connector files that packs name,
   and [the BI datasets every pack gives you](#bi-over-the-packs-kinds).
5. [Loading a pack while the server runs](#loading-a-pack-while-the-server-runs), [pack codes](#pack-codes) and
   [upgrading or removing a pack](#upgrading-and-removing-a-pack).

**Writing a pack** (the manifest key by key, links, roles, Sutras, samples, tests, inheritance, generators, the signed
registry, exporting from the Build workbench) is in the [Pack developer guide](PACK_DEVELOPER_GUIDE.md), which builds a help-desk
pack step by step.

## What a pack is

A pack is configuration and content only. It contains no Java and no Python that the server runs (its Calc snippets,
`python/*.py`, run in the user's browser; see [Calc](PACK_DEVELOPER_GUIDE.md#calc-python-snippets)). Here is a small
complete pack, the help-desk pack the [Pack developer guide](PACK_DEVELOPER_GUIDE.md) builds
(`docs/guides/examples/pack/helpdesk/`):

```text
helpdesk/
├── pack.yaml                    the manifest: kinds, commands, links, columns, roles, alerts, connectors, console extras
├── sutras/
│   └── ticket.v1.sutra.yaml     one layout (a Sutra, in YAML) for tickets
├── tests/
│   └── ticket/                  two sample tickets and expect.yaml: what `sutra test` checks about the Sutra
├── config/
│   ├── semantics.yaml           field-name hints and labels for inference ("AGE_HOURS" is an age, in hours)
│   ├── formats.yaml             a number format of the pack (30 → "30 h")
│   ├── about.yaml               what a ticket is and what each of its fields means (About this page)
│   ├── workspaces.yaml          a starter workspace ("Ticket desk")
│   └── help.yaml                the pack's card in the help centre
├── guides/
│   └── helpdesk.md              the guide that card opens
├── python/
│   └── queue-by-status.py       a Calc snippet
└── samples/
    ├── catalog.jsonl             the list of sample entities (kind, id, title, subtitle)
    ├── ticket.jsonl             the sample tickets, one JSON document a line
    ├── agent.jsonl
    └── client.jsonl
```

When the server runs with this pack and its sample data (the demo source, `DRISHTI_DEMO_ENABLED=true`, the default), you can
type `TKT TKT-1001 <GO>` and see a ticket. Without the pack, `TKT` means nothing.

A shipped pack is the same shape, larger. The trading pack, for example, holds 134 Sutras in five folders and six shared
panel blocks, one `samples/trade.jsonl` of 765 trades, a test folder for each Sutra and the templates of the five connectors it
reads through ([Trading](#trading)).

**Every attribute is UPPER_SNAKE.** The shipped packs name every attribute of every kind (counterparties, curves, netting
sets, trades, economies, news topics) the way production systems write them: `COUNTERPARTY_ID`, `NET_MTM`, `ZERO_RATE`,
`TRADE_ID`, `GDP_USD_BN`. Searches, forms, Sutras, About text, BI datasets and the test data all use those names
(`TRD PRODUCT_TYPE=ComSwap`, `CPTY COUNTRY_OF_RISK=US`).

Words used below:

| Word | Meaning | Example |
|---|---|---|
| **kind** | a type of entity | `trade`, `netting-set`, `ticket` |
| **mnemonic** | the short command for a kind | `TRD` opens a `trade` |
| **id** | one entity's identifier | `MX-20000001`, `1000001` |
| **Sutra** | a layout for a kind, written in Rachana: one YAML file starting `rachana: 1` | `sutras/murex/irs-fixfloat.v1.sutra.yaml` |
| **connector** | a configured source of data, owned by the site: one YAML file in `config/connectors/` whose file name is its name; a pack names the connectors it reads through | `credit-store` (a Delta Lake folder) |
| **route** | which connector answers a kind | `netting-set: credit-store` |
| **template** | a pack's suggestion for a connector, written to the connector file once, at the first start | the `connectors:` mapping of a shipped pack |
| **union** | several connectors serving one kind together, each holding its own part (one per source system) | `trade-endur`, `trade-calypso`, `trade-murex` |

## The packs that ship

Eleven packs ship in `config/packs/`; seven are on by default (`drishti.packs.enabled`, below), `us-equities`, `gleif` and `funds` are turned on by adding them to `DRISHTI_PACKS`, and `compute-demo` (a [sample pack](#sample-packs) for reactive connectors) likewise. Each row lists the
pack, its code (typed alone it opens the pack's overview), what it inherits from, its mnemonics, and commands to try. The
example ids are real: they are in the pack's `samples/` and in the [demo lakes](#the-demo-lakes-where-the-seven-packs-read-their-data).

| Pack | Code | Extends | Mnemonics | Try |
|---|---|---|---|---|
| `core` | `CORE` | — | `CPTY` counterparty (with `COUNTRY_OF_RISK` and `UEN`) · `GRP` counterparty group · `ISS` issuer · `CCP` central counterparty · `LE` bank legal entity · `BOOK` book (portfolio) · `DESK` desk · `TRDR` trader · `CAL` holiday calendar · `CLR` clearing account | `CPTY CP-NORTHBRIDGE`, `LE LE-NY` |
| `market-data` | `MKT` | `core` | `CRV` interest rate curve · `REPO` repo curve · `FX` FX spot rate · `FXF` FX forward curve · `FXV` FX volatility surface · `IRV` swaption volatility cube · `CPV` cap/floor volatility surface · `EQ` equity · `EQX` equity index · `DIV` dividend curve · `EQV` equity volatility surface · `CDS` credit curve · `INF` inflation index · `INFC` inflation curve · `CMD` commodity · `CMDC` commodity forward curve · `CMDV` commodity volatility surface · `FIX` rate index fixings · `BND` bond (security master) · `CORR` correlation matrix | `CRV CRV-USD-OIS`, `FXV FXV-EURUSD` |
| `trading` | `TRDS` | `core`, `market-data` | `TRD` trade, from three source systems (ENDUR, CALYPSO, MUREX) in one production schema · `CTRD` counterparty trades · `PTRD` portfolio trades · `LTRD` legal entity trades · `ITRD` index trades (the last four derived from the trades of every system) | `TRD MX-20000001`, `TRD 1000001`, `CTRD CP-ALDERSHOT` |
| `counterparty-risk` | `CCR` | `trading` | `AGR` master agreement (ISDA) · `CSA` credit support annex · `NSET` netting set · `LIM` credit limit · `EXP` exposure profile · `CVA` CVA / XVA · `SACCR` SA-CCR exposure · `COLL` collateral balance · `MC` margin call · `SIMM` ISDA SIMM initial margin | `AGR AGR-NORTHBRIDGE-ISDA`, `NSET NS-SUMMIT-NY` |
| `market-risk` | `MRSK` | `market-data`, `trading` | `VAR` VaR / expected shortfall · `SCN` stress scenario · `STR` stress result · `FRTB` FRTB sensitivities · `PNL` P&L explain · `TVAL` trade valuation (MTM, P&L 1d, DV01 of every trade) | `VAR VAR-RATES`, `TVAL TVAL-MX-20000001` |
| `world-economy` | `WRLD` | — | `ECON` economy · `MACRO` macro indicator · `FCST` IMF forecast · `LABR` labour market · `FISC` fiscal position · `ECG` country group · `REGE` region economy · `JUR` jurisdiction · `PARTY` party · `OFFC` office holder · `ELEC` election · `REGN` world region · `SOCI` social indicator (real public data for about 216 countries: World Bank, IMF, CIA World Factbook, UNDP; [REAL_DATA](REAL_DATA.md); not a sample, but the IMF part is for internal demonstrations only) | `ECON ECON-IND` |
| `news-and-markets` | `NWMK` | — | `TOPIC` news topic · `NEWS` headlines · `CO` listed company · `QUOTE` quote and price history (fifteen topics and twenty well-known companies; headlines from the [news connector](../connectors/NEWS.md), quotes from the [yfinance connector](../connectors/YFINANCE.md); a sample pack, both connectors off as shipped; topics and companies also from the demo lake, connector `news-store`, and offline the quotes and headlines too, from a committed snapshot: [offline mode](#news-and-markets-and-world-economy-offline)) | `TOPIC TOPIC-FED` |
| `us-equities` | `USEQS` | — | `USEQ` US listed company · `IDX` index members · `SIC` SIC sector · `EDGAR` filings browser · `FILING` SEC filing · `FACT` financial fact · `PXH` price history (real public data on the Dow 30, Nasdaq-100 and S&P 500 companies: SEC EDGAR, GLEIF, Wikidata; **off by default**, add it to `DRISHTI_PACKS`; prices are live only, off and for internal use: [its guide](../../config/packs/us-equities/guides/us-equities.md)) | `USEQ AAPL`, `IDX SP500` |
| `gleif` | `GLEIF` | — | `LEI` legal entity · `LEIG` ownership group · `LEIR` parent relationship · `LEIC` country · `LEIM` ISIN, BIC or MIC to LEI (real public data from GLEIF, CC0: the ownership groups of the us-equities companies and of the large banks, insurers, asset managers and market infrastructures, at most 50 entities a group; **off by default**, add it to `DRISHTI_PACKS`; with `us-equities` on, a company and its LEI record link both ways: [its guide](../../config/packs/gleif/guides/gleif.md)) | `LEI 8I5DZWZKVSZI1NUHU748`, `LEIG ALL` |
| `funds` | `FUNDS` | — | `FUND` fund or ETF · `HELD` funds holding a company · `TRACK` funds tracking an index (real public data from the SEC, public domain: the 200 largest US mutual funds and ETFs by net assets with their holdings from Form N-PORT, census facts from Form N-CEN, fees and returns from the prospectus summaries; **off by default**, after `us-equities`, whose companies its holdings open; [its guide](FUNDS_GUIDE.md)) | `FUND QQQ`, `HELD AAPL`, `TRACK SP500` |
| `compute-demo` | `CMPD` | — | `PBOOK` path book · `PSTRIP` P&L strip · `PSUM` book path computed by the sample Python engine · `PSUMJ` the same by the Java engine (synthetic numbers; a sample pack, off unless named in `DRISHTI_PACKS`) | [tutorial 23](../tutorials/23-computed-panels.md) |

What each pack holds, as shipped (counted in `config/packs/`):

| Pack | Kinds | Sutras (and shared blocks) | Sample documents | Sutra test folders | Calc snippets | Connectors it names (plugin) |
|---|---|---|---|---|---|---|
| `core` | 10 | 10 | 129 | 0 | 10 | `reference-store` (delta) |
| `market-data` | 20 | 20 | 220 | 0 | 27 | `market-store` (delta); `nyfed-sofr-feed`, `ecb-estr-feed`, `ecb-fx-feed`, `us-treasury-feed`, `fred-feed` (feeds) |
| `trading` | 5 | 134 (+ 6 blocks) | 765 | 134 | 9 | `trade-endur`, `trade-calypso`, `trade-murex` (delta, a union); `trading-stream` (kafka, off); `trade-pages` (derived) |
| `counterparty-risk` | 10 | 10 | 534 | 8 | 12 | `legal-store`, `credit-store`, `collateral-store` (delta) |
| `market-risk` | 6 | 6 | 923 | 5 | 13 | `risk-store` (delta) |
| `world-economy` | 13 | 13 | 9,582 | 13 | 0 | `macro-store`, `civic-store` (delta) |
| `news-and-markets` | 4 | 4 | 35 | 0 | 0 | `news` (news), `yfinance` (yfinance), `news-store` (delta) |
| `us-equities` | 7 | 8 (+ 4 fragments) | 13,504 | 0 | 0 | `us-equities-prices` (yfinance, off, internal use only) |
| `gleif` | 5 | 7 (+ 2 fragments) | 22,169 | 0 | 0 | none: its samples are its data |
| `funds` | 3 | 3 (+ 1 fragment) | 3 | 0 | 0 | `funds-store` (delta) |
| `compute-demo` | 4 | 1 | 123 | 0 | 0 | `compute-demo-store` (delta); `path-sum-python`, `path-sum-java` (reactive) |

**Links between them.** A `core` counterparty carries its country of risk (`COUNTRY_OF_RISK`, ISO 3166 alpha-2, as the trading
systems name it on a trade: `COUNTERPARTY_COUNTRY_OF_RISK`) and its `UEN`, and two link fields that open other packs when they are on:
`ECONOMY` (`ECON-<alpha-3>`, the country of risk's economy in `world-economy`) and `NEWS_ID` (its headlines in `news-and-markets`). A
country topic and a listed company of `news-and-markets` link to their economy in the same way. Agreements and CSAs belong to
`counterparty-risk`, so `CPTY CP-NORTHBRIDGE` shows its master agreement as a link only when `counterparty-risk` is on.

### Trading

**Ids are text; numeric ids in files are read as text.** `TRADE_ID` and every other id (`LEG_ID`, `FLOW_ID`, `RESET_ID`, `EVENT_ID`, `TRADE_IDS`) is a string in the test data and samples (ENDUR's are digits, `"1000008"`). A real extract that writes an id as a JSON number (`1000008`) is read as `"1000008"`, never `1000008.0`; text ids keep their leading zeros; no id prefix or shape is assumed (`SWP-AB_12`, `mx_2000-01`, `007123` are all ids) — `SOURCE_SYSTEM` says which system a trade comes from. An older lake whose `TRADE_ID` column was written as a number is still read (as text).

A bank books its trades in several systems; the trading pack shows them as one book. Each system feeds a **common schema**: the
47 UPPER_SNAKE attributes a production extract has, nested the same way whatever the system:

| Level | Attributes |
|---|---|
| deal | `SOURCE_SYSTEM` (ENDUR, CALYPSO or MUREX), `SOURCE_UPDATE_TIME`, `TRADE_ID` (unique across the systems: Endur deal numbers `1000001`…, Calypso `CLY-…`, Murex `MX-…`), `TRADE_STATUS`, `TRADE_ACTION`, `TRADE_VERSION`, `TRANSACTION_ID`, `PACKAGE_ID`, `PRODUCT_TYPE` (picks the deal's screen), `PRODUCT_SUB_TYPE`, `PRODUCT_GROUP`, `PRODUCT_SUB_GROUP`, `QUANTITY`, `PRICE`, `BUY_SELL_CODE`, `OPTION_TYPE`, `STRIKE_PRICE`, `COUNTERPARTY` (a `core` counterparty id), `COUNTERPARTY_UEN`, `COUNTERPARTY_COUNTRY_OF_RISK`, `ORIGINAL_COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO` (a `core` book), `TRADER_ID` and the trader's names, the dates (`TRADE_DATE`, `INPUT_DATE`, `START_DATE`, `MATURITY_DATE`, `SETTLEMENT_DATE` …), `CURRENCY` and the flags (`DELIVERY_VERSUS_PAYMENT`, `PRECIOUS_METAL_FLAG` …), then `LEGS` and `EVENTS` |
| `LEGS[]` | `LEG_ID`, `LEG_TYPE` (FIXED, FLOAT, PHYSICAL, OPTION), `LEG_ECONOMIC_TYPE` (Pay, Receive), `NOTIONAL`, `PRICE_UNIT`, `DISCOUNT_INDEX`, reset conventions, `SETTLEMENT_TYPE`, `INDEX {NAME, GROUP, SUB_GROUP}`, `CASH_FLOWS[]` |
| `CASH_FLOWS[]` | `FLOW_ID`, `NOTIONAL`, `RATE`, `FLOAT_SPREAD`, `START_DATE`, `END_DATE`, `PAYMENT_DATE`, `RATE_DETERMINATION_DATE`, `STRIKE_PRICE`, `RESETS[]` (`RESET_ID`, `RESET_DATE`, `RATE` …) |
| `EVENTS[]` | `LEG_ID` (blank for the deal's own events), `EVENT_ID`, `EVENT_TYPE`, `PAYMENT_TYPE`, `EVENT_DATE`, `AMOUNT` (signed: paid is negative), `CURRENCY` |

Endur books the commodity desk's own deals (five product types: `ComSwap`, `ComFut`, `Cash`, `CallNot`, `Commodity`); Calypso
(51 product types) and Murex (74) book the other products of the taxonomy (rates, FX, credit, equity, commodities, inflation, fixed
income, securities financing, money markets, structured). Missing values are spelt as extracts spell them (mostly `""`), so the
screens read them with `nonblank`.

**Three connectors, one kind.** Each system lands in a Delta table of its own (`<root>/trading/trade_endur`, `trade_calypso`,
`trade_murex`, partitioned by business date and `PRODUCT_TYPE`), read by its own connector (`trade-endur`, `trade-calypso`,
`trade-murex`, each with `union: true` and its `table:`), all serving `trade`: opening a trade finds it in whichever system holds it, a
search, a type-ahead, the search form's drop-downs and a BI table over `trade` read all three, and every row says its
`SOURCE_SYSTEM` ([CONNECTORS.md](../connectors/CONNECTORS.md#several-connectors-for-one-kind-a-union)). On the demo lakes,
`TRD MX-200000 <GO>` finds 99 trades among the 15,000 of the three systems, with the pack's pick-list columns:

```text
TRD          Source system  Product type   Trade status  Trade date   Buy sell code  Currency  Quantity     Maturity date  Portfolio     Counterparty
MX-20000001  MUREX          IRS_FIXFLOAT   AMENDED       2025-07-25   S              AUD       242,000,000  2032-06-25     BOOK-RATES-3  CP-MERIDIAN-RE
MX-20000002  MUREX          IRS_FIXFLOAT   VALIDATED     2025-02-06   B              EUR       110,000,000  2031-04-15     BOOK-RATES-1  CP-HALCYON
MX-20000003  MUREX          IRS_FIXFLOAT   VALIDATED     2026-09-18   B              USD       204,000,000  2036-05-13     BOOK-RATES-1  CP-AURORA
…
```

`drishti.py lake demo` builds the three tables from the committed test data ([the demo lakes](#the-demo-lakes-where-the-seven-packs-read-their-data));
for your own extracts, `drishti.py lake build --connector trade-murex …` writes one ([TOOLS.md](TOOLS.md)).

**The deal screen** (one per product type, the same layout for all): **F2 DES** the deal ticket; **F3 LEGS** the legs side by side,
each with its terms, its notional profile (a step line), its fixings and its cash flows opening to their resets; **F4 FLOWS** every
flow of every leg; **F5 EVT** the events by leg, with a table filtered by leg and by dates (the header's controls); **F6 PAY** the
netted payment ladder and payments by month; **F7** the counterparty's trades; **F8** impact; **F9** the raw document.

**Where its Sutras are.** One Sutra per source system and product type, matching
`"$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'ComSwap'"` (priority 10); a product's sub types (`ENGY-SWAP`, `ENGY-B-SWAP`) are
panels with `when:` inside it, never Sutras of their own, so every trade matches exactly one. They are grouped in lower-case folders
for people only: Drishti reads `sutras/` recursively and infers nothing from a folder name (each Sutra's `match:` alone decides).

| Folder | Holds |
|---|---|
| `sutras/common/` | the six shared panel blocks every product Sutra `use:`s: `deal-ticket`, `legs`, `flow-schedule`, `events`, `payments`, `deal-footer` (`*.v1.block.yaml`; [Shared panel blocks](RACHANA_REFERENCE.md#shared-panel-blocks-use)) |
| `sutras/endur/` | Endur's five product types (`endur-comswap.v1.sutra.yaml` …), each with its sub types' own panels |
| `sutras/calypso/` | the 51 product types Calypso books (`govt-bond`, `cds-single`, `fx-option` …) |
| `sutras/murex/` | the 74 product types Murex books (`irs-fixfloat`, `xccy-swap`, `swaption-eur` …) |
| `sutras/pages/` | the counterparty, portfolio, legal-entity and index pages (`CTRD`, `PTRD`, `LTRD`, `ITRD`) |

Admin → Packs → *trading* → **Sutra coverage** lists each source system × product type × sub type in the data with how many trades
have it, the Sutra that lays it out and the panels its `when`s show, flagging *none* (an inferred screen) and *several*. On the demo
lakes it finds 132 combinations, every one laid out by exactly one Sutra (`GET /api/v1/packs/trading/coverage?kind=trade` answers
`combinations: 132, none: 0, several: 0`); the same check is a test (`SutraCoverageTest`, and the generators' `test_trades.py` for every
product and sub type they book).

**Pages over many trades** (derived kinds, rebuilt from the trades of every system by the `trade-pages` connector when a load
changes the data; kept on disk, `store: disk`, so they are complete at any number of trades and served at once after a restart):
`CTRD <counterparty>`, `PTRD <book>`, `LTRD <legal entity>`, `ITRD <index>`. Each shows the live trades (at most 1,000 listed; the
count and every total cover all of them; with a Pivot tab by product and system), payments in the next 7, 30 and 90 days (over every
trade) and the upcoming ones listed, notional by product type (a donut), by index, the
maturity ladder by year and payments by month. On the demo lakes the pack overview (`TRDS <GO>`) counts 15,000 trades, 24
counterparties, 30 portfolios, 4 legal entities and 57 indexes.

**Values are the risk engine's.** A booking carries no mark-to-market; `market-risk`'s **trade valuation** (`TVAL TVAL-<trade
id>`) carries each trade's MTM, P&L 1d and DV01 with the trade's source system, portfolio, desk and netting set, and its MTM ticks
live. The BI samples that add up MTM (`trades-basic`, `trading-risk`, `live-trading`) read the valuations; `trading-cashflows`
reads the bookings of the three systems.

Every pack's overview guide (*Help → Domain packs*, or `config/packs/<name>/guides/`) describes its domain and has a
**Finding things** section with the commands that work for every kind of the pack:

| Command | Does |
|---|---|
| `CPTY <id> <GO>` | Opens that counterparty. A bare identifier works too: its prefix (`CP-…`) tells Drishti the kind. |
| `CPTY <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `CPTY <field>=<value> <GO>` | Lists by field value (`CPTY COUNTRY_OF_RISK=US`). Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `CPTY <GO>` | Lists every counterparty. |

(Every pack guide has the same table for its own kinds. Pick lists are explained in
[USER_GUIDE.md](USER_GUIDE.md#pick-lists-when-a-command-names-several-entities).)

Typical combinations:

| You want | `DRISHTI_PACKS=` |
|---|---|
| Everything that ships (the default) | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets` |
| The risk packs only | `market-risk,counterparty-risk` |
| Trades and their reference data | `trading` |
| Only the world economy | `world-economy` |
| Everything, with the US equities pack | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities` |
| Everything, with US equities and their LEIs | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif` |
| Everything, with the 200 largest US funds and ETFs too | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds` |

You do not have to list `core`, `market-data` or `trading`: the packs that extend them bring them in (see
[Inheritance](PACK_DEVELOPER_GUIDE.md#inheritance)).

### Retired example packs (examples/packs)

Seven earlier packs moved to `examples/packs/` in 2.3.0. They are not shipped in the images and not tested by default (one test,
`AllShippedPacksTest`, checks that each still loads on top of the seven), and they keep their own attribute names. They still load:
name the folder in `drishti.packs.extra-dirs` (`DRISHTI_PACKS_EXTRA_DIRS`) and the pack in `drishti.packs.enabled`. The shipped packs
win a name, and a retired pack may extend them (`retail-banking` extends `core`, `climate-risk` extends `trading`).

| Pack | What it was | Load it with |
|---|---|---|
| `finance` | the small demo behind the four original mockups (`TRD IRS-48213`); it defines `trade` and `counterparty` itself, so it is an alternative to the seven, not an addition | `DRISHTI_PACKS_EXTRA_DIRS=examples/packs DRISHTI_PACKS=finance` |
| `logistics` | shipments, containers, vessels, ports (`SHP SHP-10042`) | `DRISHTI_PACKS_EXTRA_DIRS=examples/packs DRISHTI_PACKS=finance,logistics` |
| `genomics` | genes, variants, proteins, pathways (`GENE GENE-TP53`) | `… DRISHTI_PACKS=<the seven>,genomics` |
| `retail-banking` | customers, accounts, mortgages, cards, IFRS 9 segments (`CUST CUST-100231`) | `… DRISHTI_PACKS=<the seven>,retail-banking` |
| `operational-risk` | loss events, RCSA, KRIs, SMA capital (`LOSS LOSS-2025-0001`) | `… DRISHTI_PACKS=<the seven>,operational-risk` |
| `climate-risk` | climate profiles, PCAF, NGFS stress (`CLIM CLIM-SOLARIS`) | `… DRISHTI_PACKS=<the seven>,climate-risk` |
| `liquidity-risk` | LCR, NSFR, ladders, HQLA (`LCR LCR-NY`) | `… DRISHTI_PACKS=<the seven>,liquidity-risk` |

Four packs were merged into the seven and have no copy of their own: `banking-core` is `core`; `economics` and `politics-society`
are `world-economy`; `news-watch` and `market-watch` are `news-and-markets`. Their kinds, mnemonics and ids are unchanged, so
links, saved views and BI items that name a kind keep working; a list that names an old pack (`DRISHTI_PACKS=economics`) must
name the new one. Agreements and CSAs moved from `banking-core` to `counterparty-risk`, and their Delta tables from the
`reference` domain to the `legal` domain (`<lake>/legal/agreement`, `<lake>/legal/csa`): `drishti.py lake demo` builds them there.

## The demo lakes: where the seven packs read their data

Every kind of the seven packs (except news headlines and quotes, which come from the internet) is served by a **Delta connector**:
a folder of Delta tables, one per kind, under `<root>/<domain>/`. The root is `${DRISHTI_DELTA_ROOT:<data>/delta}` (the data folder,
`drishti.data.dir`, is `./data` unless you move it). The domains are the packs' connectors:

| Connector | Domain folder | Pack | Kinds |
|---|---|---|---|
| `reference-store` | `reference/` | core | the ten kinds of `core` |
| `market-store` | `market/` | market-data | the twenty kinds of `market-data` |
| `trade-endur`, `trade-calypso`, `trade-murex` | `trading/trade_endur`, `trade_calypso`, `trade_murex` | trading | `trade`, one table per source system |
| `legal-store`, `credit-store`, `collateral-store` | `legal/`, `credit/`, `collateral/` | counterparty-risk | agreements and CSAs; netting sets, limits, exposure, CVA, SA-CCR; collateral, margin calls, SIMM |
| `risk-store` | `risk/` | market-risk | the six kinds of `market-risk` |
| `macro-store`, `civic-store` | `macro/`, `civic/` | world-economy | the economic kinds; the political and social kinds |
| `news-store` | `news/` | news-and-markets | `news-topic`, `listed-company`; and `quote`, `news` from the captured market snapshot (offline) |

The committed test data (`data/testdata/`, sixty business days of every dataset, 5,000 trades per source system) becomes those
tables with **one command** (or the IDE run configuration **Build demo lakes**):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

```text
  trading/trade_endur: … rows over 60 days
  trading/trade_calypso: … rows over 60 days
  trading/trade_murex: … rows over 60 days
  legal/agreement: … rows over 60 days
  …
  news/listed-company: … rows over 60 days
  news/news-topic: … rows over 60 days

Demo lake ready: …/data/delta: 85 tables, 60 business days 2026-07-08 to 2026-09-30, built in 943 s
Feeds: …/data/feeds/fixing.jsonl (the file connector's sample feed)
Server: no server at http://127.0.0.1:19590: left …/data/bi-store/lake-rebuilt.json, so a server clears its stored BI columns and rollups at its next start
Connector files:
  civic-store: generated by the server at its first start (reads …/data/delta)
  …
```

(The table lines were captured on a clean clone of 2.5.0, on the seven-day lakes, with `--server` naming a port where nothing ran, so
their row counts are left out; the summary line is from a sixty-day run of 2.6.5 that kept the test data's own dates: 943 s, about
3.4 GB on disk.) By default the sixty days are moved to end on today's business date, so every page opens on data; `--as-of data` keeps the test data's own dates.
Start the server with the samples off so that every page reads the lakes: the run configuration **Drishti server (demo lakes)**, or
`DRISHTI_DEMO_ENABLED=false` with the seven packs. Running `lake demo` again while a server runs is safe: it sends the data-load
signal for every table and date it wrote (`--server`, `--token-file`), and a running server also sees each rebuilt Delta date by
itself and reads it again, so BI never shows old figures. The steps, the demo pages and the timings are in
[data/testdata/README.md](../../data/testdata/README.md); every option of `lake demo` and `lake build` is in [TOOLS.md](TOOLS.md).

### news-and-markets and world-economy offline

A demonstration often runs on a laptop with no internet. Both packs then work fully from the repository:

- **news-and-markets.** Its quotes (`QUOTE-<ticker>`, Yahoo Finance through the `yfinance` connector) and headlines (`NEWS-<name>`,
  Google News through the `news` connector) are computed live, so the repository keeps a **real snapshot** of them:
  `data/testdata/news-and-markets/quote/` (the twenty companies, a year of daily bars each) and `news/` (the companies' and the
  fifteen topics' headlines, filtered and `~all`), captured through the pack's own connectors by `drishti.py testdata capture-market`.
  `lake demo` builds them into `news/quote` and `news/news`, which the pack's `news-store` connector serves beside the topics and
  companies. Every page reads the same documents it reads live (same Sutras, same panels); a quote's *As of* and the headlines'
  *Fetched* end in *(snapshot captured <date>)*, and the quote and headline pages add a **Snapshot** note. The data belongs to its
  providers and is shown for demonstration only. Refresh it with `tools/publicdata/news_and_markets.py` ([PUBLIC_DATA](PUBLIC_DATA.md)).
- **world-economy.** Already offline: all thirteen kinds (`economy`, `macro-indicator`, `economic-forecast`, `labour-market`,
  `fiscal-position`, `country-group`, `region-economy`, `jurisdiction`, `party`, `office-holder`, `election`, `region`,
  `social-indicator`) are the pack's samples (real World Bank, IMF, CIA World Factbook and UNDP figures captured when the pack was
  built; [REAL_DATA](REAL_DATA.md); refresh with `tools/publicdata/world_economy.py`, [PUBLIC_DATA](PUBLIC_DATA.md)), served by `macro-store` and `civic-store` from the demo lake. The pack has no live connector.
- **Offline mode.** Start the server with `DRISHTI_OFFLINE=true` (the run configuration **Drishti server (demo lakes)** sets it):
  the `news`, `yfinance` and public-data `feed` connectors never start, even when a file enables them, so nothing waits on the
  network and Admin → Connectors shows them **off (offline mode)** while `news-store`, `macro-store` and `civic-store` run
  ([CONFIGURATION](../admin/CONFIGURATION.md#drishtioffline--offline-mode-no-internet)). Without it the snapshot still answers
  whenever the live connectors are off (their shipped state); with them on and the internet there, the live connector is asked first.

Refresh the snapshot on a machine with internet: `drishti.py testdata capture-market` (about four minutes: two seconds between calls),
then `drishti.py lake demo` and commit `data/testdata` ([data/testdata/README.md](../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline)).

## Turning packs on

The server reads one setting, `drishti.packs.enabled`, from the environment variable `DRISHTI_PACKS` (a
comma-separated list; default the seven default packs, `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets`).
Packs are looked for in `drishti.packs.dir` (`DRISHTI_PACKS_DIR`, default `./config/packs`, relative to the directory you start the
server in), then in the folders of `drishti.packs.extra-dirs` (`DRISHTI_PACKS_EXTRA_DIRS`, comma-separated, default none; set it to
`examples/packs` for the [retired example packs](#retired-example-packs-examplespacks)).

1. Stop the server.
2. Start it with the packs you want. From the repository root:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   DRISHTI_PACKS=market-risk,world-economy java -jar drishti-server/target/drishti-server-2.9.5-exec.jar
   ```

   (or, from the IDE, the run configuration **Drishti server** with `DRISHTI_PACKS` changed; [IDE_GUIDE.md](IDE_GUIDE.md)).
3. Check what loaded. With security on, an API token goes in the header (`-H "Authorization: Bearer $DRISHTI_TOKEN"`;
   [CLIENTS.md](CLIENTS.md) says how to make one):

   ```bash
   curl -s http://localhost:18480/api/v1/packs -H "Authorization: Bearer $DRISHTI_TOKEN" \
     | python3 -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin)]'
   ```

   You see one line per loaded pack that is switched on, parents included (a pack an admin switched off is missing here;
   *Admin → Packs* lists every pack with its status). With `DRISHTI_PACKS=market-risk,world-economy`, five lines:
   `market-risk`, `core`, `market-data`, `trading` and `world-economy`, each `1.0.0`. With the default seven:

   ```text
   core 1.0.0
   market-data 1.0.0
   trading 1.0.0
   counterparty-risk 1.0.0
   market-risk 1.0.0
   world-economy 1.0.0
   news-and-markets 1.0.0
   ```

   In the console, *Admin → Packs* lists the loaded packs, and *Admin → Health* (`/admin/health`) shows each
   pack with its status, what it extends, its kinds, its Sutras, its connectors and any problems.
4. Open the terminal (`/t`) and type a command from the new pack, for example `ECON ECON-IND <GO>`.

The console needs no setting: it asks the server which packs are on. (`packs.enabled` in
`drishti-console/config/application.yaml` is only a fallback for when the server cannot be reached.) If you moved the
packs folder, set `DRISHTI_PACKS_DIR` (and `DRISHTI_PACKS_EXTRA_DIRS`) for both processes.

What happens at start-up:

- Each listed pack is read from `config/packs/<name>/pack.yaml` (or the first extra folder that has it), and so is every pack it extends.
- The packs' contents become **lowest-precedence** configuration. Anything the site sets in
  `drishti-server/src/main/resources/application.yaml`, an external `application.yaml` or an environment variable
  overrides a pack.
- The server **refuses to start**, with a message naming the packs, when:
  - a listed pack folder or its `pack.yaml` is missing (`pack 'x' not found at config/packs/x/pack.yaml (required by 'y')`);
  - `pack:` inside `pack.yaml` does not match the folder name;
  - packs extend each other in a cycle;
  - two packs own the same kind (`kind trade is defined by both pack 'finance' and pack 'trading'`);
  - two **unrelated** packs define the same mnemonic, link field, badge, role, connector or route differently.

## Switching packs off and on (Admin → Packs)

`DRISHTI_PACKS` decides which packs the server **loads** at start-up. Once it runs, an administrator can
switch any loaded pack **off** for everyone, and back **on**, without a restart: *Admin → Packs* in the
console (`/admin/packs`).

| Status on the page | Meaning | What an admin can do |
|---|---|---|
| **on** | Loaded and in use | **Switch off** (unless another switched-on pack needs it) |
| **off** | Loaded, but switched off for everyone | **Switch on** |
| **not loaded** | A folder under `config/packs/` that `DRISHTI_PACKS` did not name | **Load** (below) |

What switching off does, at every user's next click:

- the pack's kinds cannot be opened (`DRS-5002`), and links to them show disabled;
- its mnemonics, suggestions, examples, guides, starter workspaces and monitors disappear;
- it leaves every user's pack menu, and `GET /api/v1/packs` stops listing it.

Its Sutras, connectors and routes stay loaded, so switching it back on is instant.

**The rules.**

1. **A pack that a switched-on pack builds on cannot be switched off.** `trading` is needed by
   `counterparty-risk` and `market-risk`. Its button is greyed (*Needed by …: switch those off first*), and the API refuses with
   `DRS-5001 'trading' is needed by [counterparty-risk, market-risk]; switch those off first`.
2. **Switching a pack on switches on everything it builds on.** With `trading` and `market-risk` both off,
   switching `market-risk` on switches `trading` on too (and `market-data` and `core`, if they were off).
3. **Packs that were not loaded cannot be switched on**: `DRS-5001 'finance' is not loaded; installed: [core, market-data, trading,
   counterparty-risk, market-risk, world-economy, news-and-markets]` (packs load at start, or with **Load**, below).

**Where the choice is kept.** In the identity database, table `drishti_pack_state` (one row per pack that was
ever switched: `name`, `enabled`, `updated_at`, `updated_by`). It survives restarts. A pack with no row is on.
Each server keeps it in memory and re-reads it every `drishti.identity.refresh-seconds` (15), so with several
servers sharing one database a switch applies at once on the server that made it and within 15 seconds on the
others (roles defined in Admin → Roles behave the same). Each switch is audited as `pack-enabled` or
`pack-disabled`, with the admin's name, in *Admin → Audit log*.

**For programs** (an admin's token when security is on):

```bash
curl -s http://localhost:18480/api/v1/admin/packs -H "Authorization: Bearer $DRISHTI_TOKEN" | python3 -c '
import json,sys
for p in json.load(sys.stdin): print(p["name"], "loaded" if p["loaded"] else "NOT LOADED", "on" if p["enabled"] else "off", p.get("requiredBy") or "")'
```

On a server started with the seven packs you see:

```text
core loaded on ['market-data', 'trading', 'counterparty-risk', 'market-risk']
market-data loaded on ['trading', 'counterparty-risk', 'market-risk']
trading loaded on ['counterparty-risk', 'market-risk']
counterparty-risk loaded on
market-risk loaded on
world-economy loaded on
news-and-markets loaded on
```

To switch a pack, `PUT /api/v1/admin/packs/<name>` with `{"enabled": false}` or `{"enabled": true}`. The answer
lists the packs now on: `{"name": "news-and-markets", "enabled": false, "enabledPacks": [...]}`; a refusal is a `400` with the
`DRS-5001` sentence above. See [API_GUIDE.md](API_GUIDE.md).

## Who sees which pack

Four lists decide what a user sees. Each one narrows the one before it:

| List | Set by | Meaning |
|---|---|---|
| **Loaded** | `DRISHTI_PACKS`, at start-up (and **Load**) | what the server runs (the listed packs and their parents) |
| **Switched on** | an administrator, for everyone (*Admin → Packs*) | which loaded packs are in use; all of them unless switched off ([above](#switching-packs-off-and-on-admin--packs)) |
| **Assigned** | an administrator, per user (*Admin → Users*) | what this user may use. A user who was never given a list of packs (for example one created through the API without `packs`) gets `drishti.packs.default-for-users` (`DRISHTI_DEFAULT_PACKS`; empty means every switched-on pack). A user given an **empty list** (every pack unticked in the dialog, or `"packs": []`) sees no pack: every kind answers `DRS-5002` no access. |
| **Active** | the user, with the pack menu in the top bar | which of their assigned packs they want to see now |

Example: the server loads the seven packs; an admin switches `news-and-markets` off; Priya is assigned
`trading`, `counterparty-risk` and `news-and-markets`; she ticks only `counterparty-risk`. Priya sees
counterparty risk, plus the kinds of the packs it extends (`trading`, `market-data`, `core`), and
nothing of news and markets even though it is assigned to her, because it is off for everyone.

**To assign packs to a user** (administrators), step by step:

1. Open *Admin → Users* (`/admin/users`) and click **Edit** on the user (or **New user**).
2. Under **Packs**, tick the packs this person may use, say `core` and `counterparty-risk`.
   Ticking none means *default packs* (`DRISHTI_DEFAULT_PACKS`, or every switched-on pack when that is empty).
3. Click **Save**. The user list now shows `core, counterparty-risk` in the user's row (or
   *default packs*), and the change is in the audit log.
4. At the user's next click, their pack menu lists only those two packs, and kinds of other packs answer
   `DRS-5002`.

The same with the API (an admin's token when security is on), when creating a user:

```bash
curl -s -X POST localhost:18480/api/v1/admin/users -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"priya","displayName":"Priya Raman","desk":"Credit","roles":["viewer"],
       "packs":["core","counterparty-risk"],"password":"credit-desk-2026"}'
```

`PUT /api/v1/admin/users/priya` with a `packs` list changes it later. See
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) for the user dialog, roles, and where assignments are stored
(`drishti_user_pack`).

**To choose which packs you see** (any user with more than one pack): click the round box tool in the top bar
(it shows how many packs you have on), tick the packs (**all** and **none** help), and click **Apply**. The choice is saved to your
account. (For programs: `GET /api/v1/me/packs` answers `{"assigned": [...], "active": [...]}` for the signed-in user, and `PUT` with
`{"active": [...]}` chooses.)

**Enforcement is on the server.** A kind owned by a pack that is not active for you cannot be opened: the view
answers `DRS-5002` (forbidden). Its mnemonics, suggestions, examples, starter workspaces, guides and alert
suggestions disappear too. Kinds no pack owns are not affected. A user who may see
`market-risk` may also open the kinds of the packs it extends (a VaR result links to its trades and books).

Roles work on top of packs: a role lists the kinds it may open (see [Roles and field masks](PACK_DEVELOPER_GUIDE.md#roles-and-field-masks)).

## Loading a pack while the server runs

Admin → Packs → **Load** brings in a pack that is on disk but not loaded, without anyone touching the machine and **without a restart**
(a pack from a signed registry is installed the same way: [the registry](PACK_DEVELOPER_GUIDE.md#a-signed-pack-registry-publishing-and-installing)).
Every way a pack changes while the server runs (Load, Unload, a deploy, a registry install or rollback, and an edit in the packs folder, below)
takes the same path, so they all behave alike:

1. The server runs the same check it runs at start-up on the loaded packs plus the new one (inheritance, clashes, the
   manifest, the YAML files the pack points at). A pack that would not load is refused with the reason, and nothing changes.
2. For Load, it records the pack in the **pack overlay**, `<data>/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY`), a small
   configuration file the server imports at every start, so the pack is loaded after a restart too:

   ```yaml
   # Packs loaded by an administrator from Admin → Packs. Written by the Drishti server: change them there.
   # Read at every start, together with drishti.packs.enabled.
   drishti:
     packs:
       added:
         - helpdesk
   ```

3. It puts the pack to use **now**: its kinds and owners, its mnemonics (and so the command line, type-ahead and menus), its Sutras
   (re-read, listeners told what changed) and its connectors. A connector template becomes a connector file by the usual rule (written
   once if no file of that name exists, never overwritten afterwards; [Packs and connectors](#packs-and-connectors-where-a-pack-reads-its-data))
   and the connector starts. The process, its sessions and every open live view carry on.
4. It puts the settings a pack contributes to use **now** too: the roles it defines (a signed-in user's kinds follow at the next call),
   the link graph (reference patterns, fields, badges, impact), search columns and pivots, forms, formats, semantic hints, About pages and
   guides, kind routes and the sample-data folders of the demo connector. Each of these is built first from the new packs: if one cannot
   be used (a badge that does not compile, a format file that is not YAML, a pivot that does not read) the whole change is refused with
   the reason and nothing is swapped, so the last good packs keep running. When all are ready they are swapped in one after another
   inside the one reload that runs at a time, and the cached view layouts are dropped last: a request sees the old settings or the new
   ones, never a mixture. Nothing needs a restart (`restartFor` in the answer is always empty).

**Unload** does the same in reverse for a pack loaded here; packs named in `DRISHTI_PACKS` stay. A pack another loaded pack extends cannot be
unloaded: unload the dependants first. Unloading stops the connectors only that pack used (their files stay on disk), and a view someone
has open for one of the pack's kinds says **pack removed** (`DRS-1002`), not an error. Both are audited (`pack-loaded`,
`pack-unloaded`). The API: `POST /api/v1/admin/packs/{name}/load` and `…/unload`; the answer lists what was `loaded`, `reloaded` and `unloaded`,
any `problems` by pack, and `restartFor` (kept for compatibility, always empty); `"restarting"` is always `false`.

### Live reload: the packs folder is watched

The server watches `drishti.packs.dir` (and `drishti.packs.installed-dir`) the way it watches Sutras and connector files:
`drishti.packs.watch` is `auto` (file events, with a poll every `drishti.packs.poll`, 5 s, as the safety net), `poll`, or `off` (only Admin → Packs
acts). A burst of changes (a copy in progress) is acted on once the folder has been quiet for `drishti.packs.reload-debounce` (750 ms). One reload
runs at a time. (Copying the help-desk pack into `config/packs/` of a running server loads it within about ten seconds:
`GET /api/v1/packs` then lists `helpdesk 0.1.0`, and the pack is added to the overlay.)

| What you do in the folder | What happens, with no restart |
|---|---|
| **Add** a pack folder (one with a `pack.yaml`) | Checked together with the loaded packs, then loaded and remembered in the overlay. A folder that does not check out is not loaded; Admin → Packs and Health say why, and it is tried again when you change it **and** by itself (below). |
| **Edit** `pack.yaml`, `about.yaml`, a Sutra or a sample | Checked again and swapped in: the new mnemonics, kinds, Sutras and connector templates apply; a mnemonic you renamed stops working under its old code. A template change does not rewrite a connector file that already exists (use *Reset to pack default*, [below](#packs-and-connectors-where-a-pack-reads-its-data)). |
| **Make a bad edit** (invalid YAML, a kind another pack owns, a missing parent) | **The last good version keeps running.** The problem is shown on the pack's row in Admin → Packs and on the Health page (status DEGRADED), and in the audit log (`pack-problem`). Fix the file and the problem clears. |
| **Delete** a pack folder | The pack is unloaded; connectors only it used stop; open views of its kinds say *pack removed*. If another loaded pack extends it, it is **not** unloaded: the problem says which pack needs it, and it stays as last loaded until that one goes. If `DRISHTI_PACKS` still names a deleted pack the server will not start until that is changed, and the problem says so. |

**Taking a pack out safely.** Deleting the folder works, but **Admin → Artifacts** (and the *Uninstall…* button on each pack in Admin → Packs) first lists what depends on
the pack (packs that extend it, connectors only it uses, BI datasets and reports over its kinds, monitors, alert rules, subscriptions, workspaces, shares, embed hosts),
refuses while anything does and offers *Disable instead*, and otherwise moves the folder to `<data>/uninstalled/` where it can be restored for 30 days. See
[OPERATIONALISING.md section 18](OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely).

### A pack that was refused is tried again by itself

A pack can be refused (a clash over a kind or mnemonic with another pack, an `extends` that is not loaded, a bad file, a setting that
cannot be used) for a reason that later goes away without anyone touching the pack's own folder: the pack it clashed with is
unloaded or deleted, the pack it extends is added, the sample-pack switch changes, a connector file appears. So the server does not wait for the
folder to change. Every pack that is refused, or kept at its last good version, is listed as **failing** and checked again

- as soon as something it may depend on changes (the packs folder, the connector files, the sample-pack switch, the overlay), and
- on a schedule with exponential backoff: after `drishti.retry.packs.initial` (5 s), then twice as long each time, never longer than
  `drishti.retry.packs.max` (5 min), with jitter. An error that retrying cannot fix (the same refusal about a missing setting or
  refused credentials three times in a row, `permanent-after`) is looked at only every `max`.

Admin → Packs and Health show it on the pack's row: *failed — next retry in 20 s (attempt 3)* and the last error. **Retry now** (Admin
→ Packs, or `POST /api/v1/admin/retry/packs`; admin role, audited as `pack-retry-now`; an API token needs the `packs:admin` scope) checks at
once. When a pack loads because its blocker went away, the audit log has `pack-loaded` (actor `retry`) and `pack-recovered`; failures are
audited as `pack-retry-failed`, only on attempts 1, 2, 4, 8, … so a pack that stays broken for a day does not fill the log. The settings
are in [CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings) and the whole behaviour in
[OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself). With `drishti.packs.watch: off` nothing looks at the
folder on a timer, and so nothing retries either: use Retry now.

Each change is audited (`pack-loaded`, `pack-reloaded`, `pack-unloaded`, `pack-problem`). Connectors that only unloaded packs used stay held back until
a loaded pack names them again; after a restart they follow the usual rule (a connector file runs).

## Sample packs

A pack that exists to demonstrate Drishti is marked `sample: true` in its `pack.yaml`. The packs that ship marked so are **compute-demo** (synthetic P&L strips and their sum computed by reactive connectors; not loaded by
default: [tutorial 23](../tutorials/23-computed-panels.md)) and **news-and-markets** (a small list of well-known topics and listed companies, with
news and quotes that [connectors compute on demand](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand) from Google News and Yahoo Finance; their
connectors are off as shipped, the Yahoo endpoint is unofficial and restricted, and names and tickers leave the network when they are on: internal demonstrations only); the retired example packs **genomics** and **logistics** (invented data in domains far from capital
markets) are samples too; **world-economy** is not a sample: it carries real public data ([REAL_DATA](REAL_DATA.md)); the banking, markets and risk packs
are the reference packs real ones build on, and are not samples. Who sees sample packs is the setting `drishti.packs.samples`
(`DRISHTI_PACKS_SAMPLES`):

| Mode | Who sees sample packs |
|---|---|
| `visible` (default) | Everyone with the pack assigned, as any pack. |
| `developers` | Only users with the **author** or **admin** power. For everyone else the server filters them exactly as a pack an administrator switched off: their kinds cannot be opened, their mnemonics, suggestions, menus, search results, About text, examples and the Build examples that point at their records disappear, and `GET /api/v1/packs` does not list them. |
| `hidden` | Nobody. Sample packs are not loaded (unless a loaded pack extends one), and the connectors only they use do not start. |

Once your own packs exist, set `developers`: your people see their domain, and your authors still have the samples to learn from.

**From Admin → Packs.** Sample packs carry a **Sample** badge. A *Sample packs* section shows the mode in force and offers **Hide samples from business
users** (sets `developers`), *Show samples to everyone* and *Hide samples from everyone*. The choice is saved on the server (`<data>/packs/samples-mode`,
`DRISHTI_PACKS_SAMPLES_FILE`), **overrides** the property until you press *Use the configuration*, is applied at once (`hidden` unloads the sample packs
and `visible` brings them back, with no restart) and is audited (`pack-samples`). The API: `GET` and `PUT /api/v1/admin/packs/samples` with
`{"mode": "visible" | "developers" | "hidden" | "default"}`.

**Sample documents are not sample packs.** Every pack has `samples/` (one `samples/<kind>.jsonl` per kind, listed in `samples/catalog.jsonl`),
served by the built-in demo source when `DRISHTI_DEMO_ENABLED` is `true` (the default). The run configuration **Drishti server (demo lakes)**
turns the demo source off, so every page reads the lakes; with it on, a sample answers before the lake for an id both hold.

## Packs and connectors: where a pack reads its data

A pack says **what** it reads; the site says **where**. In `pack.yaml` a pack lists the **names** of the connectors it reads through and routes each of its kinds to one.
From `config/packs/counterparty-risk/pack.yaml`:

```yaml
connectors:                     # a mapping: the names this pack reads through, each with the pack's template
  legal-store: { plugin: delta, kinds: [agreement, csa], settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: legal } }
  credit-store: { plugin: delta, kinds: [netting-set, credit-limit, cva, sa-ccr], settings: { … domain: credit } }
  collateral-store: { plugin: delta, kinds: [collateral-balance, margin-call, simm], settings: { … domain: collateral } }
routes:                         # which connector answers each kind
  agreement: legal-store
  netting-set: credit-store
  margin-call: collateral-store
  # … one line per kind
```

The connector itself is a **site resource**: one YAML file per connector in `config/connectors/` (`drishti.sources.connectors-dir`, `DRISHTI_CONNECTORS_DIR`), and
the file name is the connector's name: `config/connectors/credit-store.yaml` is the connector `credit-store`. The file holds the plugin (a Delta lake, JSON Lines
files, a database, Kafka...), the kinds it serves and its settings. It is edited by the administrator, in an editor or in **Admin → Connectors**, and applied to
the running server without a restart; only that connector restarts. The model, the file format and an example for every store are in
[CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

- **Templates and what gets generated.** A pack may carry a suggestion for each connector it names: the `connectors:` **mapping** of definitions that every pack
  shipped with Drishti uses (read as the pack's templates), or `connector-templates:` beside a plain list of names. At the first start, for each connector a pack
  names, the server writes `config/connectors/<name>.yaml` from the template **if no file of that name exists**, and never overwrites an edited file afterwards (it is logged and
  audited as `connector-generated`). The file starts with the line `# Generated at first start from the connector template of pack '<pack>'. Edit it freely: an edited file is
  never overwritten; an unedited one follows the pack's template at an upgrade.`, then a stamp line (`# drishti-template: …`, the template's fingerprint and the
  text's hash), and the server keeps a marker of what it generated in `<data>/connectors/generated/<name>.properties`. So a shipped pack works out of the box.
  A file nobody edited follows the pack: a new version of the template replaces it (the file before kept beside it). Once edited, the **file is the site's**: it
  replaces the template (its values are never overwritten), and an upgrade only adds the keys the template gained, at any depth
  ([CONNECTOR_FILES.md, Upgrades](../connectors/CONNECTOR_FILES.md#upgrades-295)).
- **Back to the pack's template.** *Reset to pack default* (Admin → Connectors, or `drishti.py connector reset <name>`) rewrites the file from the pack's current template,
  keeping the earlier text in `<data>/connectors/history/<name>.<timestamp>.yaml`:

  ```text
  $ drishti.py connector reset helpdesk-store --server http://localhost:18480
  helpdesk-store is back to the pack's default (running)
  ```

  The file then starts `# The default suggested by pack 'helpdesk'.` A deploy of a new pack version also refreshes a generated file nobody edited when the
  pack's kinds changed; an edited one is flagged with *Reset to pack default*.
- **Kinds go through routes.** The pack's `routes:` say which connector answers each kind (then the default route, then any other connector serving the kind);
  a connector file's `kinds:`, when set, is the exact list the connector serves (it adds and narrows; the rule is in [CONNECTOR_FILES.md, How packs and files fit together](../connectors/CONNECTOR_FILES.md#6-how-packs-and-files-fit-together)). The same connector can therefore serve several packs while each pack stays in charge of its own kinds.
- **Several connectors for one kind.** Connectors with `union: true` that serve the same kind are read together, each holding its own part: the trading pack's three
  source systems ([Trading](#trading)).
- **Shared connectors.** Several packs may name the same connector. Admin → Connectors shows which packs use each one and for which kinds, and asks before you disable or delete a connector that
  packs use. A connector that a pack's template suggests is not deleted (it would come back): reset it to the pack's default, or disable it.
- **A connector nobody defined.** The pack still loads. Its kinds answer `DRS-1011` (connector not configured), Admin → Health shows the pack as degraded with
  `connectorsNotConfigured`, and Admin → Packs → **Data source** marks the connector *not configured* with a **Create** link that opens a new connector pre-filled from the
  pack's template.
- **Archives carry names and templates only.** A pack archive (`pack make`, `pack bundle`, Deploy archive) never contains a site's connection settings. Deploying
  one shows the connectors it names that this site lacks, and a **Create connectors** step writes their files from the templates.
- **Credentials** in a connector file are only `${ENV_VAR}` or `file:/path` references, never a literal.

![Admin -> Packs -> Data source: the pack's connectors, one not configured, with Edit and Create links](../connectors/img/connector_files/07-pack-connectors.jpg)

The precedence for a connector's settings, highest first: environment variables and `drishti.sources.connectors.*` in the server's own configuration (deprecated; they
override a file setting by setting), the connector file, the pack's template. `drishti.py connector list|get|apply|test|reset|...`
([CLI_GUIDE.md](CLI_GUIDE.md#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history)) does from a terminal what Admin → Connectors does.

## History: an entity over time

Type a range after an id: `TRD 1000015 30` (the last 30 business days with data), `EQ EQ-CSCA 3M`, `FX FX-AUDUSD YTD`,
`TRD 1000015 FROM 2026-09-01 TO 2026-09-30`. Every kind a dated store holds has a history; a pack's `history:` block only chooses its
columns, parts and chart ([PACK_DEVELOPER_GUIDE.md](PACK_DEVELOPER_GUIDE.md#history-a-kind-over-time)). The shipped packs declare:

| Pack | Kinds | Shape |
|---|---|---|
| trading | `trade` (key columns, `LEGS` and `EVENTS` as parts, `SOURCE_UPDATE_TIME` and `INPUT_DATE` changes ignored); `counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades` off (derived pages) | document |
| market-data | `equity` (`PRICE`), `equity-index` (`LEVEL`), `fx-spot` (`MID`), `ir-curve` (`TEN_Y`, `SLOPE_2S_10S`), `credit-curve` (`SPREAD_5Y`, `PD_1Y`), `commodity` (`FRONT`), `bond` (`PRICE`, `YIELD`), `inflation-index` (`LATEST`, `YOY`); equity, index, FX and bond fall back to the `HISTORY` list inside the document | market (a chart, high, low, change) |
| counterparty-risk | `netting-set`, `credit-limit`, `cva`: their headline measures as columns | document |
| market-risk | `var`, `trade-valuation`, `pnl-explain`: their measures as columns | document |

## BI over the packs' kinds

A pack's kinds are BI data with nothing to build: every kind of every pack you may open is a **dataset** named `pack.<pack>.<kind>`
(`pack.trading.trade`, `pack.core.counterparty`), certified by its pack, read-only, versioned with the pack, its columns, keys, meanings and
masks taken from the pack ([BI_GUIDE.md](BI_GUIDE.md), *Models in the rail*). Every BI model is rooted in packs: each of its datasets traces
back to pack kinds and their connectors ([BI_DESIGN.md section 14.3](../architecture/BI_DESIGN.md#143-a-model-is-a-mini-database-always-rooted-in-packs)).

- **What ships.** `config/bi/` holds the BI samples over the seven packs (nine models such as `trades-basic`, `trading-risk`,
  `trading-cashflows`, `counterparty-exposure`, `world-economics`, their datasets, reports, boards, a notebook and Python items). A test,
  `ShippedExamplesPacksTest`, checks that every one of them reads only what the seven packs serve (a kind of a shipped pack, a connector a
  shipped pack defines, a `pack.<pack>.<kind>` that exists), so a renamed kind or a retired pack cannot leave a broken BI sample behind.
- **BI inside a pack archive.** A pack may carry its own BI items in `config/bi/` inside the pack folder; deploying the pack imports them
  as a bundle into the shared workspace `packs`, with the pack as their audience ([BI_GOVERNANCE.md](BI_GOVERNANCE.md#bi-content-in-a-pack-archive)).
- **Before an uninstall**, Admin → Artifacts lists the BI datasets and reports over the pack's kinds ([OPERATIONALISING.md section 18](OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely)).

## Deploying an archive, history, and the pack's connectors (Admin → Packs)

Three more things live on **Admin → Packs**; each is written up step by step, with pictures, in
[OPERATIONALISING.md, section 17](OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back).

- **Deploy an archive.** Upload a `.tar.gz` made by `drishti.py pack bundle` or `pack make` (the pack only, never data). The server checks the
  checksums and manifest, that no path leaves the pack folder, the server version it needs, the Sutras (lint and tests), the packs it extends, and an
  optional signature by a trusted publisher; shows what it **changes** from the running version (breaking: kinds or mnemonics removed or renamed;
  selection; layout; change), and, once you confirm, swaps it into `drishti.packs.installed-dir` (`<data>/packs/installed`), keeps the version it replaces
  and puts the new version to use at once, with no restart. If the pack cannot be used, the old files are put back.
- **History and roll back.** Every deployment, rollback and reverted attempt is listed, with the kept versions (`drishti.packs.deploy.keep-versions`, 5)
  and a **Roll back to this** button; `shipped` goes back to the copy that ships with the server.
- **Data source.** Per loaded pack, a **read-only view** of the connectors it names: each one's state, plugin, the kinds this pack takes from it, and what defines it (a
  connector file, the pack's template, or nothing, in which case it is marked *not configured* with a **Create** link). **Edit** links go to Admin → Connectors,
  where settings are changed, tested (**Test connection** lists the newest business dates and row counts per kind) and saved ([above](#packs-and-connectors-where-a-pack-reads-its-data)).
  The earlier per-pack override file `<data>/packs/settings/<pack>.yaml` is moved into connector files once, at the first start after the upgrade.

The same from the terminal: `drishti.py server packs deploy|history|rollback|datasource get`
([CLI_GUIDE.md](CLI_GUIDE.md#server-packs-deploy-history-rollback-and-datasource)). A personal API token needs the `packs:admin` scope; connectors have their own commands, `drishti.py connector ...`.

### One pack: its own page

Each pack title on **Admin → Packs** links to `/admin/packs/<name>` (the *Builds on*, *needed by* and *Connectors* cells link to the packs and connectors they name).

![One pack: status, kinds with mnemonics, Sutras, connectors, loads and history](../connectors/img/connector_files/12-pack-detail.jpg)

The page shows the title, version, code, description, the **Sample** badge, who changed the pack and when, and the status: **on**, **off**, **not loaded** or **refused**, with the
reason and the retry state (*next retry in Ns (attempt k)*, **Retry now**) when the server is trying again. Under it: what the pack **builds on** and what **needs** it; the
**kinds with their mnemonics**, a link that opens a record of each kind (where one exists) and one to `FORM <pack>` for the kind, with its form fields (declared in the `form:` section or
inferred); the **Sutras** with their versions and **Open in Build**; the **Sutra coverage** of a kind whose Sutras match on its fields (the trading pack's source system × product
type); the **connectors it names**, each linked to its page, a name nothing defines flagged **missing** with **Create**;
how many kinds carry About text and glossary terms; today's **data-load expectations** and the last load, linking to the data loads page; and the pack's **deploy history** with
**Roll back to this**. **Switch on/off**, **Load** and **Unload** are the list's buttons with the same rules. The server side is `GET /api/v1/admin/packs/{name}/detail` (administrators; kinds, mnemonics, forms, Sutras
whose files live in the pack, About counts), `GET /api/v1/packs/{name}/coverage?kind=<kind>`, beside the existing `GET /api/v1/admin/packs`, `/packs/{name}/datasource`,
`/loads/{name}` and `/packs/history?pack=`.

## Telling Drishti new data has landed

A pack can say which kinds its ETL is expected to land each business day, by when, and who is told, in a `loads:` section of its `pack.yaml`
(an administrator can override it from Admin → Packs → *Data loads*). The ETL announces each batch with one call (`POST /api/v1/packs/{pack}/loads`
or `drishti.py data landed`); Drishti refreshes, verifies, evaluates alert rules and notifies, and flags a batch that is late on Admin → Health.
`drishti.py lake build --signal URL` and `lake demo` make that call for every business date they write. The manifest key, with examples:
[DATA_LOADS.md](DATA_LOADS.md#6-configuration-reference).

## Pack codes

Every shipped pack has a short `code:`; typed alone on the command line (with `<GO>`) it opens the pack's overview:
every kind you may open, its mnemonic, how many the sources hold for the business date, an example and the key
fields. Each row's mnemonic opens that kind's pick list. Typing the pack's name (`market-data`) works too. Below the kinds
the page shows the pack's README (`config/packs/<pack>/README.md`): its summary, its sources and, folded, the *Data structures*
of the kinds you may open (the server leaves the others out, as it does for the overview). Administrators see the whole README
under Admin → Packs → the pack.

| Pack | Code | | Retired example pack | Code |
|---|---|---|---|---|
| core | `CORE` | | finance | `FIN` |
| market-data | `MKT` | | logistics | `LOGI` |
| trading | `TRDS` | | genomics | `GENO` |
| counterparty-risk | `CCR` | | retail-banking | `RTL` |
| market-risk | `MRSK` | | operational-risk | `OPR` |
| world-economy | `WRLD` | | climate-risk | `CLI` |
| news-and-markets | `NWMK` | | liquidity-risk | `LIQ` |
| us-equities | `USEQS` | | | |
| gleif | `GLEIF` | | | |

The codes of the merged packs changed: `BNK` (banking-core) is `CORE`, `ECO` and `POLS` are `WRLD`, `NEWSW` and `MKTW` are `NWMK`.

The same answer as JSON, here for the trading pack on the demo lakes:

```bash
curl -s localhost:18480/api/v1/packs/TRDS/overview -H "Authorization: Bearer $DRISHTI_TOKEN" | python3 -c '
import json,sys; d=json.load(sys.stdin); print(d["name"], d["code"], [(k["mnemonic"], k["count"], k["example"]) for k in d["kinds"]])'
```

```text
trading TRDS [('TRD', 15000, 'CLY-3000001'), ('CTRD', 24, 'CP-ALDERSHOT'), ('PTRD', 30, 'BOOK-COMM-1'), ('LTRD', 4, 'LE-FRA'), ('ITRD', 57, 'AONIA')]
```

A pack author sets the code with `code:` in `pack.yaml` (in a generated pack, in its generator:
[how the shipped packs are generated](PACK_DEVELOPER_GUIDE.md#how-the-shipped-packs-are-generated)).

## Upgrading and removing a pack

**Upgrading a pack on a running installation**, step by step:

1. Read the pack's change notes (its guide, or the generator's commit). Look for renamed kinds, mnemonics, attributes or
   link fields: saved monitors, workspaces, alert rules and BI items refer to kinds, fields and ids, and a renamed one leaves
   them pointing at nothing. `drishti.py pack diff OLD NEW` lists what changed and marks kinds and mnemonics removed or renamed as **BREAKING**.
2. Replace the folder `config/packs/<name>/` with the new version (for a repository checkout: `git pull`), or deploy its archive from Admin → Packs.
3. If it adds packs it extends, nothing else is needed: parents load with their child.
4. No restart is needed: the server notices the new folder within the poll interval and checks it with the loaded packs (or press
   Admin → Packs → Retry now). Watch Admin → Packs and the log for the pack loader's refusals (a kind now owned by two packs, a clash
   with an unrelated pack); a refused pack keeps its last good version and is tried again by itself.
5. Check *Admin → Health*: the pack shows its new version, `sutraProblems` is empty, and its connectors are
   up and none is *not configured* (a new version may name a connector this site has no file for yet: create it in Admin → Connectors).
   Existing connector files are not changed by an upgrade. Check *Admin → Packs*: a pack switched off before the upgrade stays off (the switch is kept by name).
6. Open one of its example commands from `/t`.

**Removing a pack:** prefer *Uninstall…* (Admin → Packs or Admin → Artifacts), which checks what depends on it first. Deleting its folder (or Unload in Admin
→ Packs if it was loaded there) works too; if `DRISHTI_PACKS` names it, take it out and restart. Saved monitors, workspaces and alert rules that
refer to its kinds stay saved, but those entities cannot be opened until the pack returns. To hide a
pack without a restart, switch it off in *Admin → Packs* instead.

## What stays in the core

- The engine: sources, the view pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity, BI.
- Neutral formats (amounts, signed, percent, compact, dates) and neutral semantic roles (amount, value, count,
  rate, date, label).
- The `viewer`, `author`, `approver` and `admin` roles, and the powers a role can hold (`raw`, `author`, `approve`,
  `admin`, `calc`).
- Calc's runtime and its `drishti` module; packs only switch it on and ship snippets.
- The generic help: using the terminal, panel kinds, the Build workbench, workspaces, and every reference.

## What Admin → Packs shows about a pack

The list names every connector a pack uses (its `connectors:` list, its `connector-templates:` and the connectors its `routes:` send kinds to). **Changed** shows the
time of the last switch or, when the pack's files changed later (a deploy, an edit), that time with *files on disk*. A Sutra that does not parse in a pack's folder is
listed on the row and on the pack's page as `file:line:column DRS-2010 message` (the file relative to the pack's folder); the pack stays on, the Sutra is not in use.
A `pack.yaml` with a syntax error says `<pack>/pack.yaml has a syntax error at line L, column C: ...`; it is not retried until the file changes. A version must be
**X.Y.Z** (`1.4.0`, `2.0.0-rc.1`): the wizard, `drishti.py pack bundle|make` and the server's deploy check all say so. Redeploying the same version keeps no duplicate;
the running version is not offered as a rollback target. A pack named in `drishti.packs.enabled` (`DRISHTI_PACKS`) can only be disabled in Artifacts: the dialog says so
first. Switch off, Unload, Load and Deploy ask in the page's own dialog. The page notices changes on disk within 5 seconds and offers **Refresh**; on a phone each
pack is a card and the introduction folds behind *About this page*.
