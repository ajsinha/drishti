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
# Data

*Knowledge · what the words mean and how Drishti's data connects them. The [Knowledge home](README.md) lists every page.*

The data a bank's numbers are made from, in plain words: **market data** (curves, fixings, FX, volatility surfaces, credit curves,
prices), **entities and ownership** (the LEI, who owns whom, the bank's legal entities, counterparties and issuers) and **data and
contracts** (packs, kinds and commands, fundamental against computed data, business dates, where data comes from). Each part says what
the idea is, how the data connects, works an example on the demo data, lists questions to ask and the common confusions; the glossary
of all three follows.

## Contents

- [1. Market data](#1-market-data)
- [2. Entities and ownership](#2-entities-and-ownership)
- [3. Data and contracts](#3-data-and-contracts)
- [4. Glossary](#4-glossary)
- [5. Where to go deeper](#5-where-to-go-deeper)

## 1. Market data

### Market data in plain words

A trade's value depends on market prices: the interest rate for each future date, the exchange rate, the price of oil, how volatile a
share is expected to be. **Market data** is that set of prices, captured every business day. The bank's pricing and risk engines read it
to value trades (MTM), to build scenarios (VaR) and to simulate paths (exposure).

Most market data is not one number but a shape:

- a **curve** gives a rate or price for each future date (an interest rate curve, an FX forward curve, a commodity forward curve, a
  credit curve of CDS spreads);
- a **surface** or **cube** gives an option's implied volatility for each expiry and strike (and, for swaptions, each swap tenor);
- a **fixing** is the published value of an index on a day (SOFR, SONIA, €STR), which decides what floating legs pay.

Drishti does not price with this data; it shows it, links it to what uses it (a bond to its issuer and benchmark curve, an equity to
its index, volatility surface and dividends) and computes a few analytics from price histories when asked: **correlations** between
factors and an equity's **beta** against its index.

### How the market data data connects

```text
Rates
ir-curve        CRV CRV-<ccy>-<OIS | PROJ | GOVT | BASIS>   CALENDAR ─► calendar CAL CAL-<code>   FIXING_INDEX ─► rate-fixing FIX FIX-<index>
repo-curve      REPO REPO-<ccy>
ir-vol-cube     IRV IRV-<ccy>           cap-vol-surface CPV CPV-<ccy>
inflation-index INF INF-<index> ◄──► inflation-curve INFC INFC-<index>

FX
fx-spot         FX FX-<pair>  ──► fx-forward-curve FXF FXF-<pair>  ──► fx-vol-surface FXV FXV-<pair>   (each links back to the spot)

Equity
equity          EQ EQ-<ticker>  ISSUER ─► issuer ISS   EQUITY_INDEX ─► equity-index EQX EQX-<index>
                                EQUITY_VOL_SURFACE ─► EQV EQV-<ticker>   DIVIDEND_CURVE ─► DIV DIV-<ticker>
equity-beta     EQB EQ-<ticker>[~window=N]     computed: beta, alpha, correlation, R² against EQUITY_INDEX

Credit
credit-curve    CDS CDS-<name>  ISSUER ─► issuer ISS   ◄── counterparty.CREDIT_CURVE, cva.CREDIT_CURVE
bond            BND BND-<ISIN>  ISSUER ─► issuer   BENCHMARK_CURVE ─► ir-curve

Commodities
commodity       CMD CMD-<name>  ──► commodity-curve CMDC CMDC-<name>, commodity-vol-surface CMDV CMDV-<name>

Correlations
correlation-matrix  CORR CORR-<set>   EQUITY_IDS ─► equity, FX_IDS ─► fx-spot    (the set's factors)
correlation-result  CORR CORR-<set>[~window=N~estimator=pearson | ledoit-wolf]   computed from the factors' daily closes
```

The links are the market-data pack's contract link columns (`config/packs/banking/market-data/contracts/*.contract.yaml`). Every curve and
surface is kept per business date, so `CRV CRV-USD-OIS <GO>` on yesterday's date shows yesterday's curve.

**Id conventions.** A kind's prefix and the instrument: `CRV-USD-OIS`, `FX-EURUSD`, `FXV-EURUSD`, `FIX-SOFR`, `CDS-BUND`, `EQ-CSCA`,
`EQX-SPX`, `CMD-BRENT`, `BND-DEBUND064772`. Parameters ride on a computed id after `~`: `EQ-NVTK~window=20`.

### Market data: a worked example on the demo data

From a scratch server over the default demo lake (`drishti.py lake demo`): business date **2026-10-26**. The demo's market data is
synthetic but shaped like the real thing.

| Ask | What it shows on 2026-10-26 |
|---|---|
| `CRV CRV-USD-OIS <GO>` | the USD discount curve (OIS on SOFR): 12 points, 10-year rate 3.80%, 2s10s slope +22.6 bp |
| `FIX FIX-SOFR <GO>` | SOFR, administered by the New York Fed: 3.9060% on 2026-10-26 on 1,696 billion of volume; 3.9074% on 2026-10-23 |
| `FX FX-EURUSD <GO>` | EUR/USD mid 1.17400 (bid 1.17395, ask 1.17405), up 0.92% on the day; 30-day range 1.14318 to 1.17661 |
| `CDS CDS-BUND <GO>` | the Federal Republic of Germany: 5-year spread 9.0 bp, recovery 25%, a one-year default probability of 0.07% |
| `CORR CORR-EU-EQ <GO>` | four European equities, computed from their closes: average correlation 0.10 (Pearson, 29 daily returns from 2026-09-14), lowest −0.16, highest 0.38 |
| `EQ EQ-NVTK <GO>`, its Beta panel, or `EQB EQ-NVTK <GO>` | Novatek against its index EQX-NDX: beta −0.07 (−0.0725), correlation −0.03, R² 0.00 over 29 daily returns; alpha −0.0881% a day, volatility 26.72% against the index's 12.27% |

**Reading them together.** SOFR fixed at 3.906% while the OIS curve's 10-year point is 3.80% and the 2s10s slope is positive: the
curve prices overnight rates a little lower over the next years (2-year below 10-year below today's fixing). The Bund's 9 bp spread is what the market charges to
insure against Germany's default: 0.07% a year.

**What the data hasn't told you.** Two things a quick look misses:

- **Beta near zero is not a hedge signal here.** The demo's prices are independent synthetic walks, so measured betas and correlations
  are near zero. A beta is only as good as the prices behind it, which is why Drishti computes it from the closes rather than storing
  one.
- **Dates that move with the business date.** `FX FX-EURUSD` settles on **2026-10-28**, two business days after 2026-10-26: when the
  demo's dates are moved to today, an FX spot's value date and an IR curve's pillar maturities move with them. A stale field inside a fresh record is what a field-by-field compare
  (`Compare` on the page) finds and a summary report does not.

### Market data: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| What does the USD discount curve look like today? | `CRV CRV-USD-OIS <GO>` | rates by tenor, the 10-year point, the 2s10s slope, forwards |
| How did it move since last month? | `CRV CRV-USD-OIS 20 <GO>` | the curve on each of the last 20 business dates |
| What did SOFR fix at? | `FIX FIX-SOFR <GO>` | the latest fixing and its history |
| How expensive is EUR/USD optionality? | `FXV FXV-EURUSD <GO>` | at-the-money vols by expiry, risk reversals and butterflies |
| How risky does the market think this issuer is? | `CDS CDS-BUND <GO>` | spreads by tenor, recovery, implied default probability |
| Do these factors move together? | `CORR CORR-EU-EQ <GO>` | every pair's correlation from the closes, with the window and estimator |
| How much does this share move with its index? | `EQ EQ-NVTK <GO>`, its Beta panel | beta, correlation and R² from daily returns (`EQB EQ-NVTK <GO>` is the computed kind itself) |
| What curve does this bond price off? | `BND BND-DEBUND064772 <GO>` | its issuer, coupon, maturity and benchmark curve |

### Market data: common confusions

**Spot, forward, fixing.** Spot is today's price for near delivery; a forward is today's price for a later date; a fixing is what an
index actually printed on a past day. A forward is not a forecast: it follows from today's rates.

**Implied and historical volatility.** Implied volatility is read from option prices (what the market charges); historical
volatility is measured from past returns. A surface holds implied volatilities; `PXH` and `EQB` compute historical ones.

**Correlation is not beta.** Correlation says how closely two returns move together (from -1 to 1). Beta says how much one moves for a
1% move in the other, so it also depends on their volatilities. A share can be highly correlated with its index and still have a beta
below 1.

**Correlations depend on the window.** The same two factors can show 0.8 over a calm year and 0.3 over a month of stress. Drishti says
which window and estimator it used; Ledoit-Wolf shrinks noisy estimates towards zero.

**Stored or computed?** Curves, surfaces, fixings and prices are fundamental, as delivered. Correlations (`CORR`) and beta (`EQB`) are
computed from the closes when asked, so they always describe the prices on the page.

**Market data is not what VaR reads.** Drishti's VaR reads P&L vectors that an upstream batch made from market moves; it does not
reprice from these curves. See [Market risk](risk.md#1-market-risk).

## 2. Entities and ownership

### Entities and ownership in plain words

Every trade has two companies behind it: the bank's own **legal entity** that booked it, and the **counterparty** on the other side.
Both are legal entities in the law's sense, and both belong to larger groups. A bank needs to know *who* it is really dealing with:
a fund in Dublin may belong to an insurer in Paris, whose troubles would hit both. Credit limits, regulatory reporting (large
exposures, EMIR, MiFID) and sanctions screening all depend on knowing the entity and its owners.

The world's shared answer is the **LEI**, the Legal Entity Identifier: a 20-character code every legal entity that trades in financial
markets must hold, renewed every year. **GLEIF**, the Global Legal Entity Identifier Foundation, publishes every LEI with its name,
address and status, and since 2017 who owns whom: each entity's **direct parent** and **ultimate parent**. Drishti's gleif pack reads
that data, and from it computes **ownership groups** (an ultimate parent and everything under it) and **countries** when you open
them.

Inside the bank there is a second, private view: the **counterparty** records the credit function keeps (rating, sector, country of
risk, limit, agreements), joined into **counterparty groups** (obligor groups) that share one group limit. The two views meet on the
LEI: a counterparty's `LEI` is the same code GLEIF publishes.

### How the entities and ownership data connects

```text
The bank's side (core pack)
legal-entity   LE LE-<city>            LE-NY, LE-LDN, LE-FRA, LE-TKY: the bank's booking companies, each with its LEI and regulator
   │  JURISDICTION ─► jurisdiction JUR <country>   (world-economy pack)
   ◄── desk.LEGAL_ENTITY, trade.LEGAL_ENTITY, netting-set.LEGAL_ENTITY

counterparty-group  GRP GRP-<name>     the obligor group: members, one group limit
   ◄── counterparty.GROUP
counterparty   CPTY CP-<name>          rating, sector, country of risk, LEI, KYC, its netting sets
   │  LEGAL_ENTITY ─► legal-entity     the bank entity that owns the relationship
   │  ECONOMY ─► economy ECON ECON-<ISO3>   CREDIT_CURVE ─► credit-curve CDS <id>
issuer         ISS ISS-<name>          the entity behind bonds and CDS: CREDIT_CURVE, EQUITY

The world's side (gleif pack, public, CC0)
lei-entity     LEI <20 characters>     name, legal form, addresses, status, renewal, PARENTS, CHILDREN
   │  LEI_GROUP ─► lei-group LEIG <ultimate parent's LEI>    computed: the tree, members, countries, late renewals
   │  LEI_COUNTRY ─► lei-country LEIC <ISO2>                 computed: every entity and group of a country
   │  JURISDICTION ─► jurisdiction    US_COMPANY ─► us-company USEQ <ticker>    MAPPING_IDS ─► lei-mapping
lei-relationship  LEIR LEIREL-<child LEI>-DIRECT | -ULTIMATE   CHILD_ENTITY, PARENT_ENTITY ─► lei-entity
lei-mapping    LEIM <ISIN | BIC | MIC>   ENTITY_LEI ─► lei-entity, LEI_GROUP ─► lei-group
```

The links are the contract link columns of `config/packs/core/core/contracts/` and `config/packs/core/gleif/contracts/`. A counterparty's `LEI`
and a legal entity's `LEI` are plain fields, not links: the demo's counterparties are fictitious, so their LEIs are not in the GLEIF
snapshot. With real counterparties the same code finds the GLEIF record (`LEI <code> <GO>`) and its group.

**Id conventions.** An LEI is its own id (`HWUPKR0MPOU8FGXBT394`, Apple Inc.). An ownership group is named by its ultimate parent's LEI
(`LEIG 8I5DZWZKVSZI1NUHU748`, JPMorgan Chase & Co.; `LEIG ALL` lists every group). A country is its ISO code (`LEIC GB`). A
relationship is `LEIREL-<child>-DIRECT` or `-ULTIMATE`. On the bank's side: `LE-<city>`, `CP-<NAME>`, `GRP-<NAME>`, `ISS-<NAME>`.

### Entities and ownership: a worked example on the demo data

Two sides, two sources: the GLEIF snapshot the gleif pack commits (retrieved 2026-10-25: 9,937 legal entities in 590 ownership groups
across 133 countries) and the bank's own core records, read on a scratch server over the default demo lake (business date 2026-10-26).

**Who owns whom, from GLEIF.** `LEI 010PWNH4K3BLIC3I7R03 <GO>` is *Gestion Placements Desjardins Inc.*, Montreal. Its record names its
parents:

```text
FÉDÉRATION DES CAISSES DESJARDINS DU QUÉBEC   549300B2Q47IR0CR5B54   ultimate parent (level 2), LEI issued, renews 2027-11-10
  └─ Desjardins Holding financier inc.        549300COKYB5EGSU1838   direct parent (level 1), LEI issued
       └─ Gestion Placements Desjardins Inc.  010PWNH4K3BLIC3I7R03   LEI LAPSED: renewal was due 2024-10-07
```

The relationship itself is a record too: `LEIR LEIREL-010PWNH4K3BLIC3I7R03-DIRECT <GO>`. The group, computed when opened:
`LEIG 549300B2Q47IR0CR5B54 <GO>` has 26 members (all of GLEIF's 26) in 2 countries, and **7 of them have let their LEI lapse**. A
credit officer onboarding any of them would want to know.

**A large group.** `LEIG 8I5DZWZKVSZI1NUHU748 <GO>` (JPMorgan Chase & Co.): 50 members in the committed snapshot of 173 in GLEIF's
golden copy (the snapshot keeps at most 50 a group; the computation has no cap), 13 countries, 2 levels, 5 late renewals.
`LEIG HWUPKR0MPOU8FGXBT394 <GO>` (Apple Inc.): the whole group, 21 of 21, in 15 countries, none late; its record links to the
us-equities company `AAPL` and to 949 ISINs. `LEIC GB <GO>`: 1,274 entities registered in the United Kingdom, 225 of them lapsed.

**The bank's side.** `LE LE-FRA <GO>` is *Drishti Bank Europe AG*, LEI `549300Z9DXU4T18OK744`, jurisdiction DE, regulated by the ECB and
BaFin; it books the repo and securities lending desk. The bank has four such entities: `LE-NY`, `LE-LDN`, `LE-FRA`, `LE-TKY`.

`CPTY CP-MERIDIAN <GO>` (Meridian Life Assurance, a US insurer rated A+) and `CPTY CP-MERIDIAN-RE <GO>` (Meridian Reinsurance Ltd,
Bermuda, rated A) are two counterparties in one obligor group, `GRP GRP-MERIDIAN <GO>`, with one group limit of 8.67 billion USD on
2026-09-30: the group's own approved limit, smaller than its members' limits added (it was the sum, 13.6 billion).
`drishti.py exposure show GROUP:GRP-MERIDIAN` adds their netting sets' exposures path by path: peak PFE **361.1 million** at 2W, against 151.2
million for Meridian Life and 285.9 million for Meridian Re alone (437.2 million added up). The group record's own stored
`UTILISATION` says 98% of the 8.67 billion group limit; the computed peak is about 4% of it. The stored figure came from elsewhere.

**Country of risk at work.** `CPTY CP-KESTREL <GO>` (Kestrel Global Macro Fund, a hedge fund, BBB-) is registered in the Cayman
Islands, and its country of risk and economy link are the Cayman Islands too (`ECON-CYM`). Every one of the 24 counterparties' records
names `LE-NY` as its relationship owner, while their trades are booked by all four legal entities: `NS-ALDERSHOT-LDN` holds Aldershot's
trades booked in London.

**What the data hasn't told you.** The demo counterparties are fictitious: their LEIs (`549300H5KZ2QJHEAMB55` for Aldershot) are not in
the GLEIF snapshot, so `LEI` finds nothing for them. With real counterparties, the same code is the bridge from the bank's credit view
to the public ownership tree, and a counterparty group that disagrees with the LEI group is worth a credit officer's look.

### Entities and ownership: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| Who owns this company, all the way up? | `LEI 010PWNH4K3BLIC3I7R03 <GO>` | its direct and ultimate parent, from GLEIF's level 2 data |
| What is in this group? | `LEIG 8I5DZWZKVSZI1NUHU748 <GO>` | the ownership tree, every member, their countries, late renewals |
| Which groups are the largest? | `LEIG ALL <GO>` | every group, largest in GLEIF first, and a search over every entity |
| Which entities of a country have let their LEI lapse? | `LEIC GB <GO>` | every entity of the country, its groups, lapsed and late renewals |
| Which company issued this bond? | `LEIM AU3CB0253789 <GO>` (an ISIN) | the issuer's LEI and group from GLEIF's mapping files |
| Who is the counterparty, really? | `CPTY CP-ALDERSHOT <GO>` | its LEI, rating, sector, country of risk, KYC, group and netting sets |
| What is our total with one group? | `GRP GRP-ALDERSHOT <GO>` and `drishti.py exposure show GROUP:GRP-ALDERSHOT` | the group's members and limit; its exposure summed over the members |
| Which of our entities carries the risk? | `LE LE-LDN <GO>`, `EXP LE:LE-LDN <GO>` | the entity's desks and books; its counterparties by exposure |

### Entities and ownership: common confusions

**Legal entity, counterparty, issuer.** All three are legal entities. *Legal entity* in Drishti (`LE`) means the bank's own booking
companies. A *counterparty* (`CPTY`) is a firm the bank trades with. An *issuer* (`ISS`) is a firm whose bonds or credit the bank
holds or trades. One real company can be all three to different trades.

**Counterparty group or ownership group?** A **counterparty group** (`GRP`) is the bank's own credit decision: which counterparties it
treats as one risk, with one limit. An **ownership group** (`LEIG`) is GLEIF's public record of who consolidates whom. They usually
agree; when they do not, the difference is worth a credit officer's look.

**Direct and ultimate parent.** The direct parent consolidates the entity directly; the ultimate parent is the top of the chain. A
group's tree is built from each member's direct parent.

**Lapsed is not dead.** A lapsed LEI is one whose yearly renewal is overdue; the entity may be alive and trading. The data behind it
may be stale.

**Country, country of risk, jurisdiction.** A counterparty's *country* is where it is registered; its *country of risk* is where its
ability to pay depends; the *jurisdiction* is the legal system a GLEIF record or a bank entity sits in. A Cayman fund run from London
has the Cayman Islands as its country and the United Kingdom as its country of risk.

**Stored or computed?** LEI records and relationships are fundamental, as GLEIF publishes them. Ownership groups (`LEIG`) and countries
(`LEIC`) are computed from them when opened, with every member. Counterparty groups (`GRP`) are stored, as
the credit function maintains them.

## 3. Data and contracts

### Data and contracts in plain words

Drishti does not own a bank's data. Trades live in booking systems, counterparties in a credit system, curves in a market-data system,
P&L vectors and exposure paths in risk engines. Drishti reads each where it lives, through a **connector**, and shows it as **kinds**
of record (trade, counterparty, netting set) that you open with a short **command** (`TRD`, `CPTY`, `NSET`). A **pack** brings one
domain's kinds, commands, links and screens.

Every kind falls into one of four classes, and the class tells you how far to trust a number and where to fix it:

| Class | What it is | Who writes it | Examples |
|---|---|---|---|
| **Fundamental** | data as its owner delivers it: masters, terms, market and public data | source systems, through their packs | trades, counterparties, CSAs, curves, GLEIF records, economies |
| **Engine input** | large data an upstream engine delivers for Drishti to compute with | risk engines, once a business date | P&L vectors, scenario sets, exposure paths, simulation runs, hierarchies |
| **Drishti's own** | made by Drishti from the inputs or from users' actions | Drishti | derived positions, baskets, captures, official end-of-day records |
| **Computed** | worked out when asked, never stored | Drishti | VaR, ES, exposure, ownership groups, country groups, correlations, beta |

The rule behind it: **keep fundamental data as it is, compute everything that can be derived from it, and never store a computed
figure** (except as a capture someone asked for). A stored copy of a computed figure drifts away from its inputs; a computed one
cannot.

A **data contract** writes down what each kind is: its grain (what one row means), its key, every field with its type, unit and
meaning, the links to other kinds, where it is stored, how often it arrives, and for a computed kind its formulas. Every command has
one (`config/packs/<domain>/<pack>/contracts/<kind>.contract.yaml`), and the docs are generated from them.

### How the data and contracts data connects

```text
where data comes from                  how Drishti reads it                       what you open
─────────────────────                  ────────────────────                       ─────────────
booking systems (ENDUR, MUREX,  ──►  lake: Delta tables per kind and     ──►  fundamental kinds   TRD, CPTY, NSET, CSA ...
CALYPSO), credit, market data        business date, read by the pack's
                                     connector (data/testdata in the demo)
risk engines (P&L vectors,      ──►  the common risk lake               ──►  engine inputs        PNLV, XRUN, XPATH, SSET, HIER
exposure paths)                                                                  │
                                                                                 ▼
                                     Drishti's engines (Java; Python    ──►  computed kinds       VRES, EXP, LEIG, LEIC, ECG, CORR, EQB
                                     for what-ifs), when asked                    │  (a capture keeps one; the official record keeps the day's)
                                                                                 ▼
                                                                          Drishti's own            POS, VOFF, XOFC, captures, baskets
public sources (World Bank, IMF, ──►  one refresh script per pack     ──►  fundamental kinds   ECON, FCST, LEI, USEQ, NEWS ...
GLEIF, SEC EDGAR, ECB ...)            writes data/testdata, then the lake
```

Each kind's contract names its **key** (`NETTING_SET_ID`), its **id pattern** (`^NS-`), its **links** (a field whose value is another
kind's id: `netting-set.COUNTERPARTY → counterparty`) and its **storage** (connector, plugin, table). The links are what turn the tables
into a graph you can walk: from a trade to its counterparty, from there to its netting sets, their CSA and collateral, and on to the
counterparty's economy. Every page in this Knowledge section draws its diagram from those link columns.

`DATAMAP <GO>` draws all of it for the packs you have: every kind with how many entities it holds today, every link its contract
declares, and how well the data keeps each link (*15,000 of 15,000 ids match*; a link whose ids mostly name nothing is listed as a
finding). Nothing on it is drawn by hand: it is read from the contracts and checked against the lake when you open it
([The data map](../guides/USER_GUIDE.md#the-data-map)).

**Id conventions.** A kind's ids start with its prefix (`CP-`, `NS-`, `CSA-`, `LIM-`, `CRV-`, `ECON-`), except where the world already
has an id (an LEI, an ISIN, a ticker, a booking system's trade id). A computed kind's id is what you asked for: a scope
(`DESK:RATES`), a group's key (`ECG-WLD`), or an id with parameters after `~` (`CORR-EU-EQ~window=20`).

### Data and contracts: a worked example on the demo data

**The classes, counted.** `drishti.py contract list` names every command's contract: of the 100 commands of the shipped packs, **73**
are fundamental, **8** engine inputs (`PNLV`, `SSET`, `HIER`, `PORT`, `XRUN`, `XPATH` and the compute demo's two), **1** is Drishti's own
(`POS`, the derived positions) and **18** are computed (among them `VRES`, `EXP`, `LEIG`, `LEIC`, `SIC`, `ECG`, `CORRC`, `EQB`, `CTRD`).

**One contract, read.** `drishti.py contract show NSET` (the netting-set kind, `config/packs/banking/counterparty-risk/contracts/netting-set.contract.yaml`):

```text
kind: netting-set   mnemonic: NSET   pack: counterparty-risk   class: fundamental   source: xva-engine
grain: One row per netting set per business date, keyed by NETTING_SET_ID.     id-pattern: ^NS-     cadence: daily
storage: the credit-store connector, a Delta table <root>/credit/netting-set/, test data data/testdata/counterparty-risk/netting-set
fields: NETTING_SET_ID, TRADE_COUNT, NET_MTM (USD), COLLATERAL (USD, held less posted, after haircut), LIMIT, CVA, TRADES[], BY_ASSET[] ...
links:  COUNTERPARTY → counterparty, AGREEMENT → agreement, CSA → csa, CREDIT_LIMIT → credit-limit, CVA_RESULT → cva, SACCR → sa-ccr,
        SIMM → simm, COLLATERAL_BALANCE → collateral-balance, LEGAL_ENTITY → legal-entity, TRADE_IDS → trade
```

**One trade, walked along the links.** Each step is a click on a link field, or the command beside it:

```text
TRD CLY-4003028           a CALYPSO reverse repo, USD 994,297,500, book BOOK-SFT-1, booked by LE-FRA, counterparty CP-ALDERSHOT
 └─ NSET NS-ALDERSHOT-FRA  its netting set: Aldershot's live trades booked in Frankfurt (35 trades)
     ├─ CSA CSA-ALDERSHOT-FRA  the collateral terms of the Frankfurt entity: thresholds 10m each way, MTA 500,000, MPOR 10 business days
     │   └─ AGR AGR-ALDERSHOT-ISDA   the ISDA 2002 Master, English law, close-out netting enforceable
     ├─ COLL COLL-ALDERSHOT-FRA     the collateral held and posted for this set
     └─ CPTY CP-ALDERSHOT  the counterparty: Aldershot Pension Trust, AA-, country of risk GB
         └─ ECON ECON-GBR  its economy (world-economy pack): GDP, growth, inflation, debt
```

Five packs (trading, counterparty-risk, core, world-economy, and market-data for the credit curve) answer that one path, each from its
own source, joined only by ids that the contracts declare as links.

Read on the demo lake on business date **2026-10-26**. The walk also shows why the links matter: the netting set lists 35 member
trades, but 13 of them matured between the test data's own date and the demo's (their maturities were not moved with the dates), so the
exposure run carries 22 ([Counterparty credit risk](risk.md#counterparty-credit-risk-a-worked-example-on-the-demo-data)). Each record is
right for its own source; only following the link shows that the sources describe different populations. A netting set holds the booking system's live trades only.

### Data and contracts: questions to ask your data

| Question | Ask | What it reveals |
|---|---|---|
| What commands can I use, and what does each show? | `FUNC <GO>` (or `FUNC exposure <GO>`) | every command you may use, by pack, with what it does and an example |
| How do the kinds join, and does the data keep the joins? | `DATAMAP <GO>` (or `DATAMAP netting-set <GO>`) | every kind with its count and every contract link, each checked against the data |
| What exactly is in a netting set row? | `drishti.py contract show NSET` | the contract: grain, key, every field, links, storage, cadence |
| Which commands are computed, which stored? | `drishti.py contract list` | every command's class and status |
| Where did this number come from? | the page's **?** (About this page), or `drishti.py var show BANK` | the inputs, batch, data version and rules behind it |
| How did a record look last week? | `NSET NS-ALDERSHOT-LDN 5 <GO>` | the record on each of the last five business dates, what changed |
| Where does this public data come from, and may I share it? | [External data](../guides/EXTERNAL_DATA.md) | each source, its licence, whether it is internal only, its refresh script |
| Is today's data in? | `drishti.py data loads --pack trading --expectations` | the pack's loads for the date, what is due and what is late |

### Data and contracts: common confusions

**Stored or computed?** If a figure can be derived from data Drishti already has, it is computed (a VaR, a PFE, a group's total, a
correlation). If it is a fact someone else owns (a trade, a rating, a curve point, a GDP), it is stored as delivered. Where a page
says *(computed)* in its title, the number did not exist until you opened it.

**Computed is not cached forever.** A computed page is shared by every reader for a business date and rebuilt when the lake loads.
Change the inputs and the answer changes; keep an answer with a capture.

**Engine input is not a result.** A P&L vector or an exposure path is an engine's *input to* VaR or PFE, not a risk figure. Do not
sum P&L vectors' worst values: sum the vectors, then read the figure.

**Business date and today.** Every record is kept per business date. The top bar's date decides which one every page reads; a date
with no data says so and offers the newest one.

**A contract is not a schema.** A schema says a column is a number; a contract also says what it means, its unit, its sign, who
delivers it, when, and what Drishti does with it.

**External data has licences.** World Bank, GLEIF and SEC data are free to reuse; IMF forecasts and Yahoo Finance prices are for
internal use only. The External data page says which is which.

## 4. Glossary

<!-- glossary:start (generated by tools/knowledge.py from docs/knowledge/glossary.yaml: edit that file, not this section) -->

44 terms. Type `WHAT <term>` on the command line to open any of them (`WHAT PFE`).

| Term | Meaning | See it in Drishti |
|---|---|---|
| <a id="term-lei"></a>**LEI** (legal entity identifier) | The 20-character global id of a legal entity (ISO 17442), issued and renewed every year through GLEIF's network. | `LEI HWUPKR0MPOU8FGXBT394 <GO>` |
| <a id="term-gleif"></a>**GLEIF** (Global Legal Entity Identifier Foundation) | The foundation that publishes every LEI, its reference data and who owns whom (level 2 data), free to use. The gleif pack reads it. | `LEIG ALL <GO>` |
| <a id="term-lou"></a>**LOU** (local operating unit) | The organisation that issued an LEI and keeps it up to date (Bloomberg, the London Stock Exchange ...). | `LEI HWUPKR0MPOU8FGXBT394 <GO>` |
| <a id="term-direct-parent"></a>**Direct parent** | The entity that consolidates this one directly in its accounts, as reported to GLEIF. | `LEIR LEIREL-010PWNH4K3BLIC3I7R03-DIRECT <GO>` |
| <a id="term-ultimate-parent"></a>**Ultimate parent** | The top of the ownership chain: the entity no one else consolidates. Its LEI names the ownership group. | `LEIG HWUPKR0MPOU8FGXBT394 <GO>` |
| <a id="term-ownership-group"></a>**Ownership group** | An ultimate parent and every entity under it, as a tree, computed from the LEI records when opened. | `LEIG 8I5DZWZKVSZI1NUHU748 <GO>` |
| <a id="term-lei-country"></a>**LEI country** | A country's legal entities and groups in the GLEIF data, with lapsed and late renewals, computed when opened. | `LEIC GB <GO>` |
| <a id="term-identifier-mapping"></a>**Identifier mapping** | GLEIF's files that give an ISIN (a security), BIC (a bank) or MIC (an exchange) its LEI, so a bond or a payment finds its issuer. | `LEIM WABTUS3WXXX <GO>` |
| <a id="term-lapsed-lei"></a>**Lapsed LEI** | An LEI whose yearly renewal is overdue: its reference data may be stale. Many regulators expect counterparties to keep theirs current. | `LEIC GB <GO>` |
| <a id="term-bank-legal-entity"></a>**Bank legal entity** | One of the bank's own companies that books trades (LE-NY, LE-LDN, LE-FRA, LE-TKY), with its regulator and desks. | `LE LE-LDN <GO>` |
| <a id="term-issuer"></a>**Issuer** | The entity behind a bond or the reference entity of a CDS, with its rating and credit curve. | `ISS ISS-BUND <GO>` |
| <a id="term-country-of-risk"></a>**Country of risk** | The country whose events would most affect the counterparty's ability to pay, which may differ from where it is registered. | `CPTY CP-ALDERSHOT <GO>` |
| <a id="term-interest-rate-curve"></a>**Interest rate curve** | Rates by maturity for one currency and purpose: OIS for discounting, projection for a floating index, government or cross-currency basis. | `CRV CRV-USD-OIS <GO>` |
| <a id="term-rate-fixing"></a>**Rate fixing** | The published value of a rate index on a day (SOFR, SONIA, €STR ...): what floating legs pay. | `FIX FIX-SOFR <GO>` |
| <a id="term-repo-curve"></a>**Repo curve** | The rate for borrowing cash against securities, by maturity. | `REPO REPO-USD <GO>` |
| <a id="term-fx-spot"></a>**FX spot** | The price of one currency in another for delivery on the spot date (usually two business days ahead), with bid and ask. | `FX FX-EURUSD <GO>` |
| <a id="term-fx-forward-curve"></a>**FX forward curve** | The exchange rate agreed today for later dates; the gap to spot comes from the two currencies' interest rates. | `FXF FXF-EURUSD <GO>` |
| <a id="term-volatility-surface"></a>**Volatility surface** | Implied volatility by expiry and strike (or delta), read from option prices. ATM is at the money; risk reversals and butterflies describe the smile. | `FXV FXV-EURUSD <GO>` |
| <a id="term-swaption-cube"></a>**Swaption cube** | Interest-rate option volatilities by expiry, swap tenor and strike; cap and floor volatilities are a surface beside it. | `IRV IRV-USD <GO>` |
| <a id="term-credit-curve"></a>**Credit curve** | CDS spreads by maturity for one reference entity, with the recovery rate and the default probabilities they imply. CVA reads it. | `CDS CDS-BUND <GO>` |
| <a id="term-dividend-curve"></a>**Dividend curve** | The dividends expected from an equity or index over time, used to price equity forwards and options. | `DIV DIV-CSCA <GO>` |
| <a id="term-inflation-index"></a>**Inflation index** | A published price index (HICP ex tobacco, US CPI) and the curve of its expected values, for inflation swaps and linkers. | `INF INF-HICPX <GO>` |
| <a id="term-commodity-curve"></a>**Commodity curve** | Forward prices of a commodity by delivery month (Brent, WTI, gold, corn ...). | `CMDC CMDC-BRENT <GO>` |
| <a id="term-equity"></a>**Equity** | A share with its price, issuer, index, volatility surface and dividend curve. | `EQ EQ-CSCA <GO>` |
| <a id="term-equity-index"></a>**Equity index** | A basket of shares published as one level (S&P 500, Nasdaq-100, Euro Stoxx 50 ...). | `EQX EQX-SPX <GO>` |
| <a id="term-bond"></a>**Bond** | A debt security with its issuer, coupon, maturity and benchmark curve. | `BND BND-DEBUND064772 <GO>` |
| <a id="term-correlation"></a>**Correlation** | How closely two factors' daily returns move together, from -1 to 1. Drishti computes a set's correlations from price history when asked. | `CORR CORR-EU-EQ <GO>` |
| <a id="term-beta"></a>**Beta** | How much an equity moves for a 1% move in its index, from the regression of daily returns. Computed from the closes when asked. | `EQB EQ-NVTK <GO>` |
| <a id="term-holiday-calendar"></a>**Holiday calendar** | The days a market or currency is closed (TARGET2, USNY ...), which decide payment and fixing dates. | `CAL CAL-EUTA <GO>` |
| <a id="term-pack"></a>**Pack** | A folder that teaches Drishti one domain: its kinds, commands, links, screens, connectors and contracts (trading, counterparty-risk, market-risk ...). | `FUNC <GO>` |
| <a id="term-kind"></a>**Kind** | One type of record (trade, netting-set, economy) with its key and fields. Every command opens a kind. | `FUNC <GO>` |
| <a id="term-mnemonic"></a>**Mnemonic** | The short word that opens a kind (TRD, CPTY, NSET): type it with an id and press Enter. | `FUNC <GO>` |
| <a id="term-fundamental-data"></a>**Fundamental data** | Data as its owner delivers it (trades, counterparties, curves, GLEIF records). Drishti keeps it as it is and computes everything else from it. | `TRD 1000001 <GO>` |
| <a id="term-computed-data"></a>**Computed data** | Figures Drishti works out from fundamental data when asked (VaR, exposure, ownership groups, correlations), never stored, so they never drift from their inputs. | `VRES BANK <GO>` |
| <a id="term-input-data"></a>**Input data** | Large data an upstream engine delivers for Drishti to compute with: P&L vectors, exposure paths, scenario sets, hierarchies. | `XRUN XR-2026-10-26-01 <GO>` |
| <a id="term-data-contract"></a>**Data contract** | The written agreement for one kind: its grain, key, every field with type and meaning, links, where it is stored and how often it comes. | `drishti.py contract show netting-set` |
| <a id="term-business-date"></a>**Business date** | The day the data describes. Every fundamental row is kept per business date; the top bar's date picks which one you read. |  |
| <a id="term-grain"></a>**Grain** | What one row means: one netting set per business date, one trade's path values at one grid date. | `drishti.py contract show netting-set` |
| <a id="term-link"></a>**Link** | A field that holds another kind's id (a trade's COUNTERPARTY): click it to open that record. Links make the data a graph. | `CPTY CP-ALDERSHOT <GO>` |
| <a id="term-lake"></a>**Lake** | The Delta tables a pack's connector reads, partitioned by business date. drishti.py lake demo builds them from the committed test data. | `drishti.py lake demo` |
| <a id="term-connector"></a>**Connector** | What reads a kind from where it lives (Delta, Postgres, a REST API, a feed). Several connectors may serve one kind. |  |
| <a id="term-provenance"></a>**Provenance** | What an answer was made from: the data version, the batch, the run, the rules. Every computed answer names its inputs. | `drishti.py var show BANK` |
| <a id="term-entitlement"></a>**Entitlement** | What a user may see: whole kinds, rows (a desk's trades) or fields (a masked counterparty). Computed answers are over the trades you may see. |  |
| <a id="term-external-data"></a>**External data** | Data from outside the bank (World Bank, IMF, GLEIF, SEC EDGAR, Yahoo Finance ...), each with its licence and refresh script. IMF and Yahoo data are for internal use only. |  |

<!-- glossary:end -->

## 5. Where to go deeper

- [Groupings and analytics guide](../guides/GROUPINGS_ANALYTICS_GUIDE.md): sections 2 and 3 (LEIG and LEIC), 6 and 8 (CORR, PXH and
  beta) with worked numbers, and the computed groupings.
- [Tutorial 27: groupings and analytics](../tutorials/27-groupings-and-analytics.md).
- [External data](../guides/EXTERNAL_DATA.md): every external source (ECB, US Treasury, NY Fed, Yahoo Finance, GLEIF ...), its
  licence and refresh script.
- [Data contracts](../contracts/README.md): the rules every contract shares, and [every command's contract](../contracts/packs/README.md).
- [The command catalog](../guides/COMMAND_CATALOG.md): every command with its class.
- [Data loads](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) and [connectors](../connectors/CONNECTORS.md).
- [Risk](risk.md): [market risk](risk.md#1-market-risk), [counterparty credit risk](risk.md#2-counterparty-credit-risk) and
  [trades and products](risk.md#3-trades-and-products), what this data feeds; [Economies](world.md#2-economies), where a counterparty's
  country leads.
