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
# Financial crime: AML, fraud and market-abuse engines for the financial crime unit

Status: design, not built. Requested by the product owner on 2026-11-02 ("put AML / fraud / financial-crime unit engines on the
roadmap; design first"); proposed for releases 2.9.21 and 2.9.22, placement to confirm
([IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#2921-and-2922-financial-crime-aml-fraud-fcu-proposed)). It follows the engine pattern of
[FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md) and [IFRS9_ECL.md](IFRS9_ECL.md): method, data contracts, outputs, API and
screens, playground, hand-built cases, parameters as configuration, decisions.

**What Drishti is, and is not, here.** Drishti provides **explainable, testable engines**: scenarios and scores that run in Java on the
server over the bank's data, show the evidence for every alert, can be tuned and challenged in a playground with an Excel workbook of
live formulas, and are audited end to end. **The bank's financial crime unit (FCU) owns the rules, the thresholds, the models, the
decisions on every alert and case, and every report to a regulator or financial intelligence unit.** Drishti ships documented starting
scenarios and default thresholds clearly labelled as examples to be calibrated by the bank; it never files a report on its own, and its
defaults are not a compliance programme.

## Contents

- [1. Scope and principles](#1-scope-and-principles)
- [2. The data: contracts](#2-the-data-contracts)
- [3. The scenario framework every engine shares](#3-the-scenario-framework-every-engine-shares)
- [4. Trade surveillance and market abuse](#4-trade-surveillance-and-market-abuse)
- [5. Transaction monitoring](#5-transaction-monitoring)
- [6. The threshold-tuning playground](#6-the-threshold-tuning-playground)
- [7. Network analytics](#7-network-analytics)
- [8. Customer risk rating](#8-customer-risk-rating)
- [9. Sanctions and PEP screening](#9-sanctions-and-pep-screening)
- [10. Payment and card fraud scoring](#10-payment-and-card-fraud-scoring)
- [11. Alerts and case management](#11-alerts-and-case-management)
- [12. Regulatory reporting](#12-regulatory-reporting)
- [13. Rule effectiveness and model risk](#13-rule-effectiveness-and-model-risk)
- [14. Data protection](#14-data-protection)
- [15. API, command line and screens](#15-api-command-line-and-screens)
- [16. Parameters as configuration](#16-parameters-as-configuration)
- [17. Hand-built cases](#17-hand-built-cases)
- [18. Demo data](#18-demo-data)
- [19. Sizing](#19-sizing)
- [20. Decisions](#20-decisions)
- [21. Build checklist](#21-build-checklist)
- [22. Sources](#22-sources)

## 1. Scope and principles

| Engine | Answers | Main references |
|---|---|---|
| (a) Trade surveillance | spoofing and layering, wash trades, front-running, marking the close, insider timing, on the bank's orders and trades | IOSCO principles; EU MAR art. 12 and 16; CIRO UMIR (Canada) |
| (b) Transaction monitoring | structuring, rapid movement of funds, high-risk jurisdictions, dormancy, round amounts, velocity | FATF Recommendations 10, 20; FINTRAC guidance |
| (c) Network analytics | mule rings, circular flows, shared attributes, hops to sanctioned or high-risk parties, ownership through GLEIF | FATF Recommendations 10, 24, 25 (beneficial ownership) |
| (d) Customer risk rating | a weighted, explainable risk score and band per customer | FATF Recommendation 1 (risk-based approach), 10 |
| (e) Sanctions and PEP screening | fuzzy name matching with transliteration and aliases, scores, audited discounting, rescreening on list change | FATF Recommendations 6, 7, 12 |
| (f) Payment and card fraud | rule and model scores with reason codes, near real time | the bank's fraud policy |
| (g) Alerts and cases | triage, investigation, four-eyes decisions, full audit, on write-back approvals | FINTRAC record keeping |
| (h) Regulatory reporting | FINTRAC STR, LCTR, EFTR (Canada first); FinCEN SAR and CTR, EU AMLR later | FINTRAC; 31 CFR 1020.320; Regulation (EU) 2024/1624 |
| (i) Rule effectiveness and model risk | productivity, coverage, overlap, drift, below-the-line testing, model validation evidence | OSFI E-23; Federal Reserve SR 11-7 |

**Principles.**

1. **Every alert carries its evidence.** An alert names the scenario and its version, the parameters in force, the measured values against
   the thresholds, and the exact records (transactions, orders, trades, list entries) that produced it, pinned to their data versions. An
   investigator never asks "why did this fire?".
2. **Computed in Java on the server.** Scenarios, scores, screening and network algorithms run in the server; the console shows results
   and evidence only. The same code answers the API, the command line and the playground.
3. **Scenarios are configuration, not code.** A scenario is a YAML definition (section 3) with typed parameters, versioned and approved;
   a change of threshold is a new version with its approval and its tuning evidence.
4. **Testable.** Every scenario ships with hand-built cases (section 17) and a playground whose workbook recomputes its alerts with
   spreadsheet formulas, equal to the engine.
5. **Need to know.** FCU data and alerts are visible only to FCU roles; nothing leaks into other screens, search, AI, shares or exports
   (section 14). Tipping off is prevented by design.
6. **Switchable.** Each engine has its own file `config/engines/fcu-<engine>.yaml` with `enabled: false` as shipped; when off its commands
   answer "not active" everywhere.
7. **Honest about time.** Drishti monitors in batch and near real time (seconds, from streams); it is **not** an inline authorisation
   system for payments or cards (section 10).

## 2. The data: contracts

The contracts follow the [contract rules](../contracts/README.md) and are YAML files of a new pack `financial-crime` when built. All data
is JSON Lines. Names are UPPER_SNAKE. Personal data fields are marked `pii: true` in the contract, which drives masking (section 14).
Any of these sources may be memory-only ([DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk)) when the bank's data agreement forbids a copy at rest.

### 2.1 `fcu-customer` (KYC)

| Field | Type | PII | Meaning | Example |
|---|---|---|---|---|
| `CUSTOMER_ID` | string | | the bank's customer id | `C-88812` |
| `BUSINESS_DATE`, `SOURCE_SYSTEM` | | | as-of and provenance | `2026-10-31`, `KYC-HUB` |
| `CUSTOMER_TYPE` | enum | | `INDIVIDUAL`, `SOLE_PROPRIETOR`, `CORPORATION`, `TRUST`, `NPO`, `FI` | `INDIVIDUAL` |
| `FULL_NAME`, `NAME_ORIGINAL_SCRIPT`, `ALIASES[]` | string | Y | the name as on the identity document, in its script, and known aliases | `Ivan Petrov`, `Иван Петров` |
| `DATE_OF_BIRTH` or `INCORPORATION_DATE` | date | Y | | `1979-04-12` |
| `NATIONALITIES[]`, `RESIDENCE_COUNTRY` | ISO 3166 | Y | | `["CA","RU"]`, `CA` |
| `ADDRESS`, `PHONE`, `EMAIL` | string | Y | normalised; used for shared-attribute links | |
| `ID_DOCUMENTS[{TYPE, NUMBER_HASH, COUNTRY}]` | | Y | identity documents, the number as a keyed hash | |
| `OCCUPATION` or `INDUSTRY_CODE` | string | | NAICS for entities | `523110` |
| `LEI` | string | | joins the GLEIF pack for entities | `5493001KJTIIGC8Y1R12` |
| `BENEFICIAL_OWNERS[{CUSTOMER_ID or NAME, SHARE}]` | | Y | owners of 25 % or more (FINTRAC beneficial ownership) | |
| `PEP_STATUS`, `PEP_CATEGORY` | enum | | `NONE`, `FOREIGN_PEP`, `DOMESTIC_PEP`, `HIO`, `FAMILY`, `CLOSE_ASSOCIATE` | `NONE` |
| `ONBOARDED_AT`, `CHANNEL`, `RELATIONSHIP_MANAGER` | | | | `2019-06-02`, `BRANCH`, `RM-0441` |
| `EXPECTED_ACTIVITY{MONTHLY_VALUE, MONTHLY_COUNT, CASH_SHARE}` | | | declared at onboarding | |

```json
{"CUSTOMER_ID":"C-88812","BUSINESS_DATE":"2026-10-31","SOURCE_SYSTEM":"KYC-HUB","CUSTOMER_TYPE":"INDIVIDUAL","FULL_NAME":"Ivan Petrov","NAME_ORIGINAL_SCRIPT":"Иван Петров","ALIASES":[],"DATE_OF_BIRTH":"1979-04-12","NATIONALITIES":["CA","RU"],"RESIDENCE_COUNTRY":"CA","ADDRESS":"100 KING ST W, TORONTO ON M5X 1A1","PHONE":"+14165550100","EMAIL":"ivan.p@example.com","OCCUPATION":"ENGINEER","PEP_STATUS":"NONE","ONBOARDED_AT":"2019-06-02","CHANNEL":"BRANCH","EXPECTED_ACTIVITY":{"MONTHLY_VALUE":12000,"MONTHLY_COUNT":40,"CASH_SHARE":0.05}}
```

### 2.2 `fcu-account`

`ACCOUNT_ID`, `CUSTOMER_IDS[]` (holders and their roles), `PRODUCT` (`CHEQUING`, `SAVINGS`, `CARD`, `LOAN`, `BROKERAGE`, `CORRESPONDENT`),
`CURRENCY`, `OPENED_AT`, `CLOSED_AT`, `BRANCH`, `STATUS` (`ACTIVE`, `DORMANT`, `FROZEN`, `CLOSED`), `LAST_CUSTOMER_ACTIVITY_AT`.

```json
{"ACCOUNT_ID":"A-1002201","BUSINESS_DATE":"2026-10-31","CUSTOMER_IDS":[{"CUSTOMER_ID":"C-88812","ROLE":"PRIMARY"}],"PRODUCT":"CHEQUING","CURRENCY":"CAD","OPENED_AT":"2019-06-02","BRANCH":"BR-0107","STATUS":"ACTIVE","LAST_CUSTOMER_ACTIVITY_AT":"2026-10-30T16:12:00Z"}
```

### 2.3 `payment-transaction`

| Field | Type | PII | Meaning | Example |
|---|---|---|---|---|
| `TXN_ID`, `BOOKED_AT`, `VALUE_DATE` | | | key; the timestamp to the second (UTC) | `T-90011223`, `2026-10-30T09:10:22Z` |
| `ACCOUNT_ID`, `DIRECTION` | | | `IN` or `OUT` | `A-1002201`, `IN` |
| `CHANNEL` | enum | | `CASH_BRANCH`, `ATM`, `EFT`, `WIRE`, `INTERAC`, `CHEQUE`, `CARD`, `VIRTUAL_CURRENCY` | `CASH_BRANCH` |
| `AMOUNT`, `CURRENCY`, `AMOUNT_CAD` | decimal | | the amount and its value in the reporting currency at the booking rate | `9500.00`, `CAD`, `9500.00` |
| `COUNTERPARTY_NAME`, `COUNTERPARTY_ACCOUNT`, `COUNTERPARTY_BANK_BIC`, `COUNTERPARTY_COUNTRY` | | Y (name, account) | the other side; internal transfers carry the internal account | |
| `CONDUCTOR_ID`, `ON_BEHALF_OF` | | Y | who conducted it, and the third party (LCTR) | |
| `ORIGINATOR_COUNTRY`, `BENEFICIARY_COUNTRY`, `INTERMEDIARY_BICS[]` | | | for wires (FATF Recommendation 16 data) | |
| `PURPOSE`, `REFERENCE` | string | Y | free text | |
| `DEVICE_ID`, `IP_COUNTRY` | | | digital channels | |

```json
{"TXN_ID":"T-90011223","BOOKED_AT":"2026-10-30T09:10:22Z","VALUE_DATE":"2026-10-30","ACCOUNT_ID":"A-1002201","DIRECTION":"IN","CHANNEL":"CASH_BRANCH","AMOUNT":9500.00,"CURRENCY":"CAD","AMOUNT_CAD":9500.00,"CONDUCTOR_ID":"C-88812","BRANCH":"BR-0107"}
```

### 2.4 `order-event` and executions (for trade surveillance)

Drishti's trade records today carry the trade date, not the order book: `TRADE_ID`, `TRADE_DATE`, `PORTFOLIO`, `TRADER_ID`,
`COUNTERPARTY`, `INTERNAL_EXTERNAL_CODE`, `QUANTITY`, `PRICE`, `BUY_SELL_CODE` (the Endur, Calypso and Murex demo trades). Market-abuse
scenarios need **order lifecycle events with timestamps** and **intraday market data**, so two contracts are added:

| `order-event` field | Meaning | Example |
|---|---|---|
| `ORDER_ID`, `EVENT_ID`, `EVENT_TYPE` | `NEW`, `AMEND`, `CANCEL`, `FILL`, `PARTIAL_FILL`, `REJECT`, `EXPIRE` | `O-55120`, `E-1`, `NEW` |
| `EVENT_TIME` | UTC to the millisecond (microseconds when delivered) | `2026-10-30T14:02:01.200Z` |
| `INSTRUMENT_ID`, `VENUE`, `SIDE`, `ORDER_TYPE`, `LIMIT_PRICE`, `QUANTITY`, `FILLED_QUANTITY`, `FILL_PRICE` | | `XTSE:ABC`, `XTSE`, `SELL`, `LIMIT`, `50.12`, `20000` |
| `TRADER_ID`, `PORTFOLIO`, `ACCOUNT_TYPE`, `CLIENT_ID`, `BENEFICIAL_OWNER` | `PROPRIETARY`, `CLIENT`, `EMPLOYEE`; the owner on whose behalf | `TRDR-FROSSI`, `BOOK-EQ-1`, `PROPRIETARY` |
| `TRADE_ID` | the trade a fill became (joins `trade`) | `1000279` |

`market-tick` (intraday): `INSTRUMENT_ID`, `VENUE`, `TIME`, `BID`, `ASK`, `BID_SIZE`, `ASK_SIZE`, `LAST`, `LAST_SIZE`, and the venue's
`CLOSE` and auction flag. Daily closes already in the market-data pack suffice for the end-of-day checks of marking the close; the intraday
scenarios need ticks. Price-sensitive events come from the news-and-markets pack (`news`) and the bank's own insider and restricted lists
(`insider-list`: `INSTRUMENT_ID`, `PERSON_ID`, `FROM`, `TO`, `REASON`).

```json
{"ORDER_ID":"O-55120","EVENT_ID":"E-1","EVENT_TYPE":"NEW","EVENT_TIME":"2026-10-30T14:02:01.200Z","INSTRUMENT_ID":"XTSE:ABC","VENUE":"XTSE","SIDE":"SELL","ORDER_TYPE":"LIMIT","LIMIT_PRICE":50.12,"QUANTITY":20000,"TRADER_ID":"TRDR-FROSSI","PORTFOLIO":"BOOK-EQ-1","ACCOUNT_TYPE":"PROPRIETARY","BENEFICIAL_OWNER":"LE-CA"}
```

### 2.5 `watchlist-entry` (sanctions, PEP, internal lists)

| Field | Meaning | Example |
|---|---|---|
| `LIST_ID`, `LIST_VERSION`, `ENTRY_ID` | the list (`CA-SEMA`, `UN-SC`, `OFAC-SDN`, `EU-CFSP`, a vendor PEP list, the bank's internal list), its version | `CA-SEMA`, `2026-10-29`, `CA-SEMA-01734` |
| `ENTRY_TYPE` | `INDIVIDUAL`, `ENTITY`, `VESSEL`, `AIRCRAFT` | `INDIVIDUAL` |
| `NAMES[{NAME, SCRIPT, TYPE}]` | primary, aliases (strong and weak), original-script forms | `[{"NAME":"Ivan PETROV","TYPE":"PRIMARY"},{"NAME":"Иван Петров","SCRIPT":"Cyrl","TYPE":"ORIGINAL"}]` |
| `DATES_OF_BIRTH[]`, `PLACES_OF_BIRTH[]`, `NATIONALITIES[]`, `ID_NUMBERS[]`, `ADDRESSES[]` | secondary identifiers (a year alone is allowed) | `["1979"]` |
| `PROGRAMME`, `LISTED_AT`, `DELISTED_AT` | the sanctions programme | `Russia regulations` |

### 2.6 `device-session` and `card-authorisation`

`device-session`: `SESSION_ID`, `CUSTOMER_ID`, `DEVICE_ID` (a stable fingerprint hash), `IP`, `IP_COUNTRY`, `STARTED_AT`, `EVENTS[]`
(login, payee added, password reset). `card-authorisation`: `AUTH_ID`, `CARD_ID_HASH`, `ACCOUNT_ID`, `TIME`, `AMOUNT`, `CURRENCY`,
`MCC`, `MERCHANT_ID`, `MERCHANT_COUNTRY`, `POS_ENTRY_MODE`, `CARD_PRESENT`, `DECISION` (the processor's), `MODEL_SCORE` and `REASON_CODES[]`
(the bank's fraud platform's, when delivered).

### 2.7 Drishti-owned records

`fcu-alert`, `fcu-case`, `fcu-case-action`, `fcu-crr-override`, `fcu-screening-decision`, `fcu-report` (section 11 and 12): written by
Drishti through write-back with approvals, held in the Drishti database, never in a shared lake.

## 3. The scenario framework every engine shares

A **scenario** is a definition with typed parameters; the engine evaluates it over a population on a schedule (end of day, intraday every
*n* minutes, or on each event from a stream) and produces **alerts**:

```yaml
# config/engines/fcu-scenarios/structuring-cash.yaml (example)
id: TM-STRUCT-01
version: 3
engine: transaction-monitoring
title: Cash deposits just under the reporting threshold
population: { kind: payment-transaction, where: "CHANNEL in ('CASH_BRANCH','ATM') and DIRECTION = 'IN'" }
group-by: [CONDUCTOR_ID]            # or ON_BEHALF_OF, ACCOUNT_ID, CUSTOMER_ID
window: { length: 24h, kind: rolling }
measures:
  near-threshold-count: "count(AMOUNT_CAD >= :band-low and AMOUNT_CAD < :band-high)"
  total: "sum(AMOUNT_CAD)"
condition: "near-threshold-count >= :min-count and total >= :min-total"
parameters: { band-low: 8000, band-high: 10000, min-count: 2, min-total: 10000 }
segments:                           # parameters by customer segment, each a calibrated value
  BUSINESS-CASH-INTENSIVE: { min-count: 4 }
score: "min(100, 40 + 20 * (near-threshold-count - :min-count) + 10 * total / :min-total)"
suppress: { same-group-days: 7 }    # one alert per group a week; later evidence joins it
owner: FCU-TM                        # the team that owns and approves it
```

**Evaluation.** Windowed aggregates per group are computed in one pass over the population, sorted by group and time (a sliding window with
running sums, so each event is touched twice); the expression language is the one Drishti already uses for computed attributes, compiled
to Java. Segments override parameters per customer segment (from the CRR, section 8).

**Alert.** One `fcu-alert` per group and window that meets the condition: the scenario and version, the parameters in force, every
measure's value against its threshold, the score, the contributing records (ids, pinned to their table versions), the period, the customer
and account, and a sentence: *"4 cash deposits between 8,000 and 9,999 within 24 hours, total 36,400 (threshold 2 and 10,000)."*

**Above and below the line.** Every evaluation also records, for tuning only, the groups that came close (within the scenario's
`btl-band`, by default the measures between 80 % and 100 % of each threshold) as `fcu-btl-event`: never an alert, never shown outside
tuning, kept for the BTL retention period (section 14).

**Suppression and grouping.** Repeat alerts for the same group within the suppression window join the open alert as new evidence; alerts
for one customer group into a case (section 11).

## 4. Trade surveillance and market abuse

Inputs: `order-event`, `trade` (the Endur, Calypso and Murex records), `market-tick`, the market-data pack's closes, `news` and
`insider-list`. Groups: trader, portfolio, beneficial owner, client.

| Scenario | Method (default parameters, each a calibrated setting) | Evidence shown |
|---|---|---|
| **Spoofing and layering** | around each execution on side *S* by a trader, the orders the same trader placed on the **opposite** side within `window` (default 30 s before) and cancelled within `cancel-within` (5 s) after the execution: `opposite-cancelled-qty ÷ executed-qty ≥ 10`, cancel ratio of those orders `≥ 90 %`, `≥ 3` distinct price levels (layering), and a price move toward the execution `≥ 2` ticks while they rested | the order book replay: the orders as bars on the price ladder over time, the execution, the cancels |
| **Wash trades** | buy and sell of the same instrument with the **same beneficial owner** (or two books of one owner, internal trades `INTERNAL_EXTERNAL_CODE = Internal`) within `5 s`, quantities within `5 %`, prices within `0.1 %`; repeated pairs raise the score | the paired orders and fills, the owner chain |
| **Front-running** | a proprietary or employee order in the same instrument and direction placed within `15 min` before a client order of at least `5 %` of average daily volume, closed within `1 day` at a profit | the timeline: own order, client order, own close, P&L |
| **Marking the close** | orders in the last `10 min` before the close (or in the closing auction) whose fills moved the closing price `≥ 50 bp` against the previous trade price, by a trader holding a position whose valuation benefits, more often on month, quarter or year ends | the close build-up, the trader's contribution to the closing volume, the position's P&L at the close |
| **Insider timing** | trading in an instrument within `10 business days` before a price-sensitive event (`news` items of category earnings, M&A, guidance, or the bank's insider list) in the direction of the event's abnormal return (`≥ 2` standard deviations of the instrument's own daily returns), and any trading by a person on the insider list for that instrument | the trades against the event, the abnormal return, the list entry |

**What the demo can and cannot show.** The demo trades have no order events or ticks; wash trades between internal books and marking the
close on daily closes run on today's data, and the order-book scenarios run on a synthetic order and tick set planted for them (section 18).

**Playground (`PLAY SURV <alert>`).** The alert's records as the Data tab; each measure as a formula (`SUMIFS` over the orders by side and
time, the cancel ratio, the distinct price levels with `SUMPRODUCT(1/COUNTIF(...))`, common to every spreadsheet); the thresholds on the
Config tab as switches; the alert decision as a formula equal to the engine.

**Size:** L (the order-book replay, five scenarios, the new contracts).

## 5. Transaction monitoring

Inputs: `payment-transaction`, `fcu-account`, `fcu-customer`, the CRR. Each scenario a YAML file as in section 3; the starting set:

| Scenario | Condition (default parameters) | Groups |
|---|---|---|
| **Structuring** (`TM-STRUCT-01`) | at least 2 cash deposits each in [8,000, 10,000) within 24 hours, total ≥ 10,000; or at least 3 within 7 days | conductor, beneficiary, account |
| **Rapid movement of funds** (`TM-RMF-01`) | outgoing ≥ 90 % of incoming within 48 hours, incoming ≥ 25,000, the account's balance back near its starting level | account |
| **High-risk jurisdictions** (`TM-HRJ-01`) | wires to or from a country on the configured list (the FATF high-risk and increased-monitoring lists, the bank's own), value ≥ 10,000 in 30 days, or any for the FATF call-for-action list | customer |
| **Dormant account reactivated** (`TM-DORM-01`) | no customer-initiated activity for 12 months, then ≥ 10,000 in or out within 30 days | account |
| **Round amounts** (`TM-ROUND-01`) | ≥ 5 transactions of exact multiples of 1,000, each ≥ 3,000, within 30 days, ≥ 60 % of the period's count | account |
| **Velocity against the customer's own profile** (`TM-VEL-01`) | 30-day value or count ≥ 3 × the customer's declared expected activity and ≥ 4 standard deviations above the customer's own 12-month monthly mean (with at least 6 months of history; else against the segment) | customer |
| **Large cash, reportable** (`TM-LCTR-01`) | cash received ≥ 10,000 in one transaction, or in several within 24 consecutive hours by or for the same person or entity (the FINTRAC 24-hour rule): **not an alert but a report obligation**, sent to the LCTR queue (section 12) | conductor, on-behalf-of, beneficiary |
| **EFT, reportable** (`TM-EFTR-01`) | international EFTs of 10,000 or more, single or under the 24-hour rule: to the EFTR queue | originator, beneficiary |

Reportable-transaction rules (LCTR, EFTR) are deterministic obligations, separate from suspicion scenarios: they produce report drafts,
not alerts, and are tested against FINTRAC's own examples.

**Output:** `fcu-alert` records with evidence; the customer's alert history; per scenario the counts by day and segment.

**Size:** L (the framework of section 3, eight scenarios, the windows in one pass).

## 6. The threshold-tuning playground

`PLAY TUNE <scenario>`: the evidence an FCU and its validators need to set or defend a threshold, as a playground and an Excel workbook of
live formulas.

**What it computes.** Over a chosen history (default 12 months) of one scenario:

| Measure | Formula |
|---|---|
| Alerts at threshold θ | the count of events whose measure ≥ θ (each event a group-window with its measure values, from the alerts and the `fcu-btl-event` records) |
| Productive alerts | alerts whose case outcome was *escalated* (investigated beyond triage) |
| Productivity | productive ÷ alerts |
| Alert-to-STR rate | alerts that led to a filed STR (or SAR) ÷ alerts |
| Below the line (BTL) | events in [`btl-low` · θ, θ): sampled, reviewed by analysts as if alerted, their outcomes recorded (`BTL_REVIEW`); the BTL productive rate |
| Sample size for a BTL test | `n = z² · p · (1 − p) / e²`, rounded up (z for the confidence, p the expected productive rate, e the margin) |
| The curve | alerts, productive and STR counts for θ across a range (a step chosen on the screen) |

**The method** (above-the-line and below-the-line testing): lower a threshold step by step and measure whether the newly alerted
population is productive; raise it while the alerts lost are unproductive. Productive events below the line are evidence the threshold is
too high; the FCU decides, with the evidence attached to the new scenario version's approval.

**The workbook.** Data (the events with their measures and outcomes, values only), Config (θ, the band, the confidence, the margin: cells
with drop-downs), Curve (`COUNTIFS` of measures ≥ θ and of outcomes per θ step), BTL (the band's events, the sample drawn by a seeded
`RAND`-free rank so it reproduces), Sample (the sample-size formula), Main (the figures at θ against the engine's), Explain, Provenance.
The switches: θ per segment, the outcome counted as productive (`ESCALATED` or `STR_FILED`), the history window, the BTL band.

**Recipes and reproduction** as every playground ([FRTB_SA_GUIDE.md section 8.1](../guides/FRTB_SA_GUIDE.md#81-critique-the-choices-panel)):
a tuning run is a recipe (the scenario version, the data versions, the switches), replayable to the same counts.

**Size:** M.

## 7. Network analytics

**The graph.** Built per run from the data, in Java, as compressed adjacency arrays (one array of offsets and one of neighbours per edge
type), not as objects per node:

| Edge | From | Weight and attributes |
|---|---|---|
| `PAYS` | account → account (internal and external counterparties as nodes keyed by account or a hash of name and bank) | sum and count of amounts in the window, first and last time |
| `HOLDS` | customer → account | the role |
| `SHARES` | customer ↔ attribute node (phone, email, address, device, IP, identity document hash) | |
| `OWNS` | entity → entity, from the customer's beneficial owners and from the GLEIF pack's parent relationships (`lei-relationship`, direct and ultimate parents) | the share |
| `LISTED` | node → watchlist entry, from confirmed screening matches | the list |

Drishti's `drishti-graph` module today resolves entity references and links between records (which field names which entity); the
network engine is new, and uses `drishti-graph` to turn every node of a result into a link to its record page. The GLEIF pack
([its README](../../config/packs/gleif/README.md)) gives corporate ownership chains for entities with an LEI.

| Pattern | Algorithm (defaults) | Evidence |
|---|---|---|
| **Mule rings** | accounts with fan-in from ≥ 5 unrelated senders and fan-out within 48 h of ≥ 80 % of the inflow; connected components of such accounts joined by `PAYS` or `SHARES` with ≥ 3 members | the component drawn, each account's in and out |
| **Circular flows** | time-respecting cycles of length 2 to 6 on `PAYS` within 30 days, each hop after the previous one, retaining ≥ 70 % of the starting amount around the cycle | the cycle with times and amounts |
| **Shared attributes** | customers linked through the same phone, device, address or document hash where the bank does not expect it (not household members); clusters of ≥ 3 | the shared values (masked by role) |
| **Hops to sanctioned or high-risk** | breadth-first search from confirmed-listed nodes, high-risk-jurisdiction counterparties and exited customers, up to 3 hops over `PAYS`, `OWNS` and `SHARES`; the shortest path and its value | the path |
| **Ownership** | the ultimate owners of an entity customer from `OWNS` (GLEIF and KYC beneficial owners), flagged when any is a PEP or listed | the ownership tree |

The results feed alerts (scenarios `NET-*`), the CRR (a factor) and the case screen (the network tab).

**Playground (`PLAY NET <alert>`).** The subgraph of the alert as an edge list on the Data tab; the measures as formulas (fan-in and
fan-out with `SUMIFS`, the cycle's retention as a product of the hop ratios, hop counts as given by the engine and checked edge by edge).
Graph search itself is not a spreadsheet formula; the workbook checks every claim the engine makes about the path or cycle.

**Size:** M for components, bounded cycles, BFS and ownership; L if the bank asks for community detection or graph learning (later).

## 8. Customer risk rating

A weighted, explainable score per customer, refreshed daily and on a trigger (KYC change, alert, screening match):

```
score = Σ_f  w_f · s_f          w_f the factor weights (sum to 1), s_f the factor score 1 (low) to 5 (high) from a mapping table
band  = LOW (score < 2.0), MEDIUM (2.0 ≤ score < 3.0), HIGH (score ≥ 3.0)
then the hard rules (a minimum band): a foreign PEP → HIGH; a confirmed sanctions nexus → PROHIBITED (exit review); a filed STR in 12 months → HIGH
then an approved override (four eyes, with a reason and an expiry), never below the hard rules' minimum
```

| Factor (default weight) | Mapping (examples) |
|---|---|
| Customer type (0.20) | individual 2, corporation 3, trust 4, NPO 4, money services business 5 |
| Geography (0.20) | residence and nationalities against the country risk table (FATF lists, the bank's own): the highest |
| Products and services (0.15) | chequing 2, private banking 4, correspondent banking 5 |
| Channel (0.10) | branch 2, online 3, non-face-to-face without strong identification 4 |
| Occupation or industry (0.15) | from a code table (cash-intensive businesses, dealers in precious metals 5) |
| Behaviour (0.20) | alerts and network results in 12 months, activity against the declared profile |

Every rating shows each factor's value, score, weight and contribution, the hard rule that applied, the override, and the change since the
last rating with its cause. The bank's own factors, weights and tables replace the defaults; a statistical model's output can be one
factor (its reason codes kept).

**Playground (`PLAY CRR <customer or segment>`):** the factor table as formulas (`SUMPRODUCT` of weights and scores, the band by `IF`,
the hard rules by `MAX` on a band rank), switches for the weights and cut-offs, and a population view: how many customers change band
if a weight changes (the bank's calibration question).

**Size:** M.

## 9. Sanctions and PEP screening

**Lists.** Loaded as `watchlist-entry` versions: Canada's consolidated autonomous sanctions list (SEMA, JVCFOA) and the Criminal Code
list, the UN Security Council consolidated list, OFAC SDN, the EU consolidated list as the bank subscribes, a vendor PEP list, the bank's
internal list. Each list version is kept; a change triggers rescreening.

**Matching pipeline** (every step logged in the match's evidence):

1. **Normalise:** Unicode NFKC, case folding, diacritics removed, punctuation and honorifics dropped, legal-form words (Ltd, LLC, ООО)
   mapped to one token, name parts split.
2. **Transliterate:** a name in a non-Latin script to Latin by the ICU transforms (Cyrillic, Arabic, Greek, Han to Pinyin, and others),
   keeping the original for exact script matches; known variant spellings (Mohammed, Muhammad, Mohamed) as equivalence classes from a
   maintained table.
3. **Block:** candidate entries share at least one name token's phonetic key (Double Metaphone) or character trigram above a
   threshold, so a name is compared with tens of entries, not millions.
4. **Score:** name similarity as the best token alignment of Jaro-Winkler similarities (order-free: "PETROV IVAN" against "IVAN PETROV"),
   weighted with the secondary identifiers:
   `score = 0.60 · name + 0.25 · date of birth + 0.15 · country` (a date of birth exact 1, same year 0.5, different 0, absent excluded and
   the weights renormalised). An alert at `score ≥ 0.80` (default; sanctions and PEP lists may differ).
5. **Decide:** the analyst confirms (true match: freeze or report as the law requires) or **discounts** with a reason from a fixed list
   (different date of birth, different nationality, different gender, the entry is a vessel) and free text; a discount needs a second
   approver for sanctions lists (`approvals: two`). Each decision is an `fcu-screening-decision` with the list version.
6. **Remember:** a discounted pair (customer version, entry version) is not re-alerted until either changes; a change to either
   re-screens and shows the previous decision beside the new evidence.
7. **Rescreen on change:** a new list version screens the whole customer base against the **added and changed** entries only; a customer
   change screens that customer against every list. Payments are screened in near real time on the parties' names, countries and BICs
   (a stream, section 10's limits apply).

**Explainability:** every alert shows the two names aligned token by token with each similarity, the transliteration applied, the
secondary identifiers compared and the weights, so a discount or a confirmation is a reasoned record.

**Playground (`PLAY SCREEN <alert>`):** the token pairs and the score as formulas; Jaro-Winkler itself is computed by the engine and
shown with its matches and transpositions per pair (a spreadsheet can recompute it for short names with the supplied helper columns); the
threshold and weights as switches; the effect of a threshold on the alert count over the last list change.

**Size:** L.

## 10. Payment and card fraud scoring

**Rules** in the scenario framework, evaluated per event from a stream (Kafka) or in batch: velocity (≥ 5 authorisations in 10 minutes),
geography (two card-present uses farther apart than travel allows), a new device or payee followed within 30 minutes by a transfer of
≥ 80 % of the balance, a high-risk merchant category, first use abroad, a password reset then a new payee.

**Model scores** from the bank's fraud platform are inputs with their reason codes (`MODEL_SCORE`, `REASON_CODES[]`). A transparent model
may also run in Drishti: a scorecard or logistic model whose coefficients are a versioned configuration file, scored in Java, with each
feature's contribution as its reason code. Opaque models are not hosted in this release (decision 7).

**Combined score:** `max(rule scores, model score)` by default, a weighted sum as a switch; every alert lists the rules fired and the
top reason codes.

**Real time, stated honestly.** Drishti consumes events from streams and scores within seconds of their arrival (end-to-end latency
depending on the stream and the server's load; a target of under 2 s at the 99th percentile on a dedicated node, to be measured). It is
**not** in the card authorisation path, where decisions are needed in tens of milliseconds with the processor's availability guarantees;
the approve or decline decision stays with the bank's fraud platform or processor. Drishti's role is near-real-time monitoring,
investigation, rule testing and the evidence that tunes the platform.

**Playground (`PLAY FRAUD <alert>`):** the event and its features, each rule's test as a formula, the scorecard's points as
`SUMPRODUCT`, the reason codes as a ranking; switches for thresholds and the combination rule.

**Size:** M.

## 11. Alerts and case management

Built on write-back's Drishti-owned records and approvals ([WRITE_BACK.md](../guides/WRITE_BACK.md#10-approval)): alerts, cases and
actions are `owned` records, every action is a form, every decision that ends a case or files a report needs a second approver.

| State | Who | Action (each a form, each audited) |
|---|---|---|
| New | the queue | assigned by rule (scenario, segment, workload) or taken |
| Triage | analyst (`fcu-analyst`) | **close as not suspicious** (reason, a second approver for the high-risk band) or **escalate** to a case |
| Investigation | investigator (`fcu-investigator`) | gather evidence (the alert's records, the customer's history, the network, the screening results, notes and attachments); request information (internal only) |
| Decision | investigator proposes, the reporting officer (`fcu-mlro`, the CAMLO's delegate) approves | **file an STR** (opens the report, section 12), **no STR** (reason), **exit the customer** (a recommendation to the business, without the reason) |
| Closed | | kept for the retention period; reopened only by `fcu-mlro` |

**Service levels:** every state has a clock (triage within 5 business days, decision within 30 days of the alert, defaults the bank
sets); breaches show on the queue. FINTRAC expects STRs **as soon as practicable** after the measures that establish reasonable grounds
to suspect, so the case shows the time since escalation in plain words.

**Evidence is pinned.** A case keeps the ids and data versions of every record it used (as a playground recipe), so it opens identically
years later even after the lake moved on; for a memory-only source only the fields the bank's policy allows are copied into the case.

**Audit:** every view of a case, every field changed, every export, each with the user and time, in the audit log with the write-back
log's signatures; an auditor role reads, never changes.

**Size:** L (states, forms, queues, service levels, evidence pinning, the four-eyes rules).

## 12. Regulatory reporting

On 2.9.8's report framework (definitions, checks, frozen runs, sign-off;
[REGULATORY_VAR_REPORTING.md section 6](../guides/REGULATORY_VAR_REPORTING.md#6-controls-definitions-checks-frozen-runs-and-sign-off)):
a report is a definition mapping Drishti's records to the regulator's fields, checked, signed off by the reporting officer, and exported in
the regulator's electronic format. **Drishti prepares; the bank submits** through its FINTRAC reporting channel (the API or web
reporting) unless the bank later asks Drishti to submit.

| Report | Trigger | Deadline (FINTRAC) | Source in Drishti |
|---|---|---|---|
| **STR** suspicious transaction report | reasonable grounds to suspect money laundering, terrorist financing or sanctions evasion, including attempted transactions | as soon as practicable | the case's decision: the transactions, parties and the narrative the investigator writes (the grounds for suspicion, the indicators) |
| **LCTR** large cash transaction report | cash of 10,000 or more received, in one transaction or several within 24 consecutive hours by or for the same person or entity, or for the same beneficiary (the 24-hour rule) | 15 calendar days | `TM-LCTR-01` drafts |
| **EFTR** electronic funds transfer report | international EFTs of 10,000 or more sent or received, single or under the 24-hour rule | 5 working days | `TM-EFTR-01` drafts |

When a suspicious transaction is also a reportable large cash transaction or EFT, both reports are filed; the queue shows the link.
The thresholds and windows are configuration with FINTRAC's guidance referenced; the field mappings are written against FINTRAC's
current report schema, confirmed with the bank's reporting team before build.

**Later:** the large virtual currency transaction report, the listed person or entity property report and the terrorist property report
(Canada); **FinCEN** SAR (within 30 calendar days of initial detection, 31 CFR 1020.320) and CTR (cash over 10,000 USD, 31 CFR
1010.311) for US entities; the **EU** regime under the AML Regulation (EU) 2024/1624 and the member-state FIUs' formats (goAML for
many). Each is a report definition on the same framework.

**Size:** M (three reports, the 24-hour aggregation, the case-to-STR mapping).

## 13. Rule effectiveness and model risk

`FCU EFFECTIVENESS`: per scenario and per model, monthly:

| Measure | Meaning |
|---|---|
| Alerts, productive, STR conversion | section 6's measures over time, by segment |
| Coverage | the bank's typology list (structuring, mules, trade-based laundering, sanctions evasion, fraud types) against the scenarios that cover each; typologies with no scenario flagged |
| Overlap | Jaccard of the customers alerted by two scenarios; a scenario mostly duplicated by another is a candidate to retire |
| Silent scenarios | no alert in 90 days |
| Drift | the population's measure distribution against the calibration period (population stability index); a shift over 0.25 flagged |
| BTL testing schedule | the last below-the-line test of each scenario and when the next is due (annual by default) |
| Data completeness | the share of transactions missing fields a scenario needs (counterparty country, conductor), by source |

**Model risk.** Each scenario, score and model is an inventory item with its owner, version, purpose, limitations, validation date and
evidence (the hand-built cases, the tuning runs, the effectiveness history), exportable for the bank's model risk management (OSFI E-23;
SR 11-7 for US entities). Drishti supplies the evidence; the validation and approval are the bank's.

**Size:** M.

## 14. Data protection

| Control | Design |
|---|---|
| **Roles** | `fcu-analyst`, `fcu-investigator`, `fcu-mlro`, `fcu-admin` (scenarios and lists), `fcu-auditor` (read-only), `fcu-tuner` (tuning playground, de-identified data by default); none granted by default; FCU roles are not implied by administrator |
| **Entitlements on alerts and cases** | by the FCU team, region and legal entity; a case may be restricted further to named investigators (an employee or a high-profile customer); access to a restricted case is logged and reviewed |
| **PII masking** | contract fields marked `pii: true` are masked for every role without the field's grant (Drishti's masking); tuning and effectiveness views use de-identified ids; exports carry the masks |
| **Tipping off** | the PCMLTFA (section 8) forbids disclosing that an STR was made, or its contents, with intent to prejudice a criminal investigation; Drishti enforces a stricter design rule: FCU records never appear outside FCU roles: not in customer or account pages, relationship-manager screens, search, AI, shares, comments, notifications or exports; an exit recommendation reaches the business without its reason |
| **Memory-only sources** | a source the bank must not store at rest is a memory-only table ([DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk)); alerts and cases keep only the fields the policy lists |
| **AI** | off for FCU data by default; when on, only through the same entitlements and masks, and never for a decision |
| **Retention** | records the PCMLTFA requires kept for at least 5 years (FINTRAC record keeping); cases, alerts and reports 5 years after closing by default, configurable longer; BTL events and tuning data 2 years; legal hold suspends deletion; deletion is logged |
| **Audit** | every view, search, export and decision, signed as the write-back log is |
| **Segregation** | the FCU's Drishti database schema separate from the shared one, encrypted at rest; on a cluster the FCU engines on named nodes |

## 15. API, command line and screens

| What | API | Command (terminal) | CLI |
|---|---|---|---|
| Alerts queue and one alert | `GET /api/v1/fcu/alerts?queue=…`, `GET /api/v1/fcu/alerts/{id}` | `FCU ALERTS`, `FCU ALERT <id>` | `drishti.py fcu alerts` |
| Cases | `GET /api/v1/fcu/cases…`; actions as write-back forms | `FCU CASE <id>` | `drishti.py fcu cases` |
| Run a scenario (ad hoc, on a date range) | `POST /api/v1/fcu/scenarios/{id}/run` | | `drishti.py fcu run` |
| Customer risk rating | `GET /api/v1/fcu/crr/{customer}` | `CRR <customer>` | `drishti.py fcu crr` |
| Screening | `POST /api/v1/fcu/screen` (a name and identifiers), `GET /api/v1/fcu/screening/alerts` | `SCREEN <name>` | `drishti.py fcu screen` |
| Network of a customer or alert | `GET /api/v1/fcu/network?node=…&hops=…` | `FCU NET <customer>` | `drishti.py fcu network` |
| Reports | the report framework | `FCU REPORTS` | `drishti.py fcu reports` |
| Effectiveness | `GET /api/v1/fcu/effectiveness` | `FCU EFFECTIVENESS` | `drishti.py fcu effectiveness` |
| Playgrounds | `/api/v1/fcu/playground/{kind}` (and `.xlsx`) | `PLAY TUNE`, `PLAY SURV`, `PLAY NET`, `PLAY CRR`, `PLAY SCREEN`, `PLAY FRAUD` | `drishti.py play fcu` |

**Screens (mock-ups first, three options each, before the build; decision 8):** the alert queue (a list with scores and SLA clocks; a
board by state; a map of alerts by customer), the case (evidence tabs; a timeline; the network first), the order-book replay
(surveillance), the screening match (two names aligned), the CRR (factor bars; history), tuning (the curve; the BTL sample; the
segment grid), effectiveness (scorecard; coverage matrix; drift).

## 16. Parameters as configuration

| File (planned) | Holds |
|---|---|
| `config/engines/fcu-surveillance.yaml` | on or off; the venues, the schedule, the order-book replay limits |
| `config/engines/fcu-transaction-monitoring.yaml` | on or off; schedules; the reportable-transaction rules (thresholds 10,000, the 24-hour window) |
| `config/engines/fcu-scenarios/*.yaml` | one file per scenario (section 3), versioned, each change approved |
| `config/engines/fcu-network.yaml` | windows, fan-in and fan-out, cycle lengths, hop limits |
| `config/engines/fcu-crr.yaml` | factors, weights, mapping tables, bands, hard rules |
| `config/engines/fcu-screening.yaml` | lists and their sources, normalisation tables, weights, thresholds per list, rescreen schedule |
| `config/engines/fcu-fraud.yaml` | rules, the scorecard's coefficients, the combination |
| `config/engines/fcu-cases.yaml` | states, queues, SLAs, approvals, retention, restricted-case rules |

Country lists (FATF, the bank's own) and code tables are data with a version and source, refreshed by a documented script. A scenario
version in force is recorded on every alert; a changed file is a new version, never an edit of the one in force.

## 17. Hand-built cases

Each becomes an engine unit test, a playground check-cell test and a drill row.

**Case 1: structuring and the 24-hour rule.** One conductor deposits cash 9,500 at 09:10 on day 1, 9,800 at 15:40 on day 1 and 4,000 at
08:00 on day 2 (22 h 50 min after the first). `TM-STRUCT-01`: two deposits in [8,000, 10,000) within 24 h, total in the window
23,300 ≥ 10,000: **alert**; score `min(100, 40 + 20 · 0 + 10 · 2.33) = 63.3`. `TM-LCTR-01`: 9,500 + 9,800 = 19,300 ≥ 10,000 within 24
consecutive hours by the same person: **an LCTR obligation** regardless of the alert, due 15 calendar days after.

**Case 2: wash trade.** Book `BOOK-EQ-1` buys 10,000 `XTSE:ABC` at 50.00 at 10:00:01.200; book `BOOK-EQ-2` of the same beneficial owner
`LE-CA` sells 10,000 at 50.00 at 10:00:01.900. Δt 0.7 s ≤ 5 s, quantity difference 0 % ≤ 5 %, price difference 0 % ≤ 0.1 %, same owner:
**alert**. With the owners different: no alert.

**Case 3: spoofing.** A trader rests five sell orders of 20,000 at three price levels above the best offer from 14:02:01 to 14:02:28,
buys 5,000 at 14:02:30, cancels all five sell orders by 14:02:31.5. Opposite-side quantity 100,000 ÷ executed 5,000 = 20 ≥ 10; cancel
ratio 100 % ≥ 90 %; 3 levels ≥ 3: **alert** (with the price move test on the ticks).

**Case 4: threshold tuning.** Ten events of `TM-STRUCT-01`'s 24-hour cash total with outcomes:

| Measure | 25,000 | 18,000 | 15,500 | 14,000 | 12,000 | 11,000 | 10,500 | 9,600 | 9,200 | 8,700 |
|---|---|---|---|---|---|---|---|---|---|---|
| Productive | Y | Y | N | Y | N | N | Y | N | Y | N |
| STR filed | Y | Y | N | N | N | N | N | N | Y | N |

At θ = 10,000: 7 alerts, 4 productive (57.1 %), alert-to-STR 2/7 (28.6 %); below the line [8,000, 10,000): 3 events, 1 productive
(33.3 %). At θ = 9,000: 9 alerts, 5 productive (55.6 %), alert-to-STR 3/9 (33.3 %). The BTL evidence (a productive event and an STR at
9,200) argues for testing θ = 9,000 on a larger sample. Sample size for 90 % confidence (z = 1.645), expected productive rate 5 %, margin
3 %: `n = 1.645² · 0.05 · 0.95 / 0.03² = 142.8`, so **143** events.

**Case 5: customer risk rating.** Customer type 2 (weight 0.20), geography 4 (0.20), products 3 (0.15), channel 2 (0.10), occupation 3
(0.15), behaviour 4 (0.20): `score = 0.40 + 0.80 + 0.45 + 0.20 + 0.45 + 0.80 = 3.10` → **HIGH**. The largest contributions: geography and
behaviour, 0.80 each. With geography 2 the score is 2.70 → MEDIUM; if the customer is a foreign PEP, the hard rule keeps HIGH.

**Case 6: screening.** Customer "MARTHA" against list entry "MARHTA" (one transposition): Jaro 0.9444 (6 matches, 1 transposition:
`(6/6 + 6/6 + 5/6) / 3`), Jaro-Winkler with the common prefix of 3 and p = 0.1: `0.9444 + 3 · 0.1 · (1 − 0.9444) = 0.9611`. Date of
birth exact (1), country different (0): `score = 0.60 · 0.9611 + 0.25 · 1 + 0.15 · 0 = 0.8267 ≥ 0.80`: **alert**. With only the year
of birth matching (0.5): 0.7017, no alert. "IVANOV" against "IVANOFF": Jaro-Winkler 0.9095.

**Case 7: circular flow.** A → B 50,000 (day 1), B → C 48,000 (day 2), C → A 45,000 (day 4): a time-respecting cycle of length 3 within
30 days; retention `45,000 / 50,000 = 90 % ≥ 70 %`: **alert**. With C → A on day 0 (before A → B): not time-respecting, no alert.

**Case 8: hops to a listed party.** Customer X pays Y; Y shares a phone number with Z; Z is a confirmed match on a sanctions list: X is 2
hops away (`PAYS`, `SHARES`), within the limit of 3: X's network factor rises and the case shows the path.

## 18. Demo data

A seeded generator writes a **synthetic** population (`data/testdata/financial-crime/<kind>/*.jsonl.gz`, loaded into Delta): 5,000
customers, 7,000 accounts, about 200,000 payment transactions over 6 months (a 20,000-transaction subset for quick local tests), a synthetic watchlist of 2,000 invented names (no real list
entry, no real person), synthetic orders and ticks for 20 instruments over 10 days, and the existing demo trades. **Planted patterns**,
each labelled: structuring (30 conductors), a mule ring (12 accounts), circular flows (8), shared-device clusters (5), a dormant
reactivation (10), wash trades (6 pairs on internal books), spoofing sequences (4), marking the close at month end (3), insider timing
before synthetic news events (3), screening near-matches with transliteration (40). Every record carries `SYNTHETIC: true`; the ground
truth of what was planted is a separate file the tests read (recall and precision of each scenario on the demo), never shown to the
engines. Names are generated, never taken from real lists or people.

## 19. Sizing

| Workload | Volume (a large bank) | Approach | Estimate (one warm server, 16 cores; to be measured) |
|---|---|---|---|
| Transaction monitoring, end of day | 20 million transactions a day, 30 scenarios, windows up to 12 months | per-group running aggregates kept between days (incremental), so a day adds its transactions only | minutes |
| Screening, full rescreen | 15 million customers × 1.5 million list names (with aliases) | blocking to tens of candidates per name; parallel | under an hour; a list delta in minutes |
| Payment screening and fraud rules, streaming | 1,000 to 5,000 events a second at peak | in-memory per-key state | under 2 s at the 99th percentile (target) |
| Network | 50 million `PAYS` edges in 30 days | compressed arrays, about 1 GB; bounded cycles from flagged seeds only | minutes |
| Surveillance | 50 million order events a day | per-instrument time-ordered passes | minutes |

The demo runs in seconds. A large bank's FCU engines belong on dedicated nodes of a cluster.

## 20. Decisions

**All decided by the product owner on 2026-11-02, as recommended.**

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | Split | **two releases**: 2.9.21 *AML core* (transaction monitoring with tuning, alerts and cases, CRR, FINTRAC reports, effectiveness) and 2.9.22 *Screening, networks, surveillance and fraud*; each keeps two large items |
| 2 | First jurisdiction | **Canada (FINTRAC)** first, as for the regulatory VaR reports; FinCEN and the EU later as report definitions |
| 3 | Who submits reports | **the bank**, from Drishti's signed export; direct submission only on request, later |
| 4 | Scenario library | ship the starting scenarios of sections 4 and 5 as **documented examples, off until the bank's FCU approves each version** |
| 5 | Order and tick data | add `order-event` and `market-tick` contracts; the demo plants a synthetic set; the bank's order management and market data feed them |
| 6 | Lists | Drishti loads lists the bank supplies or subscribes to; it ships none (the demo's list is invented) |
| 7 | Models | host only **transparent** models (scorecards, logistic) in Java; consume opaque models' scores and reason codes as inputs |
| 8 | Screens | mock-ups first, three options each (alert queue, case, replay, screening match, CRR, tuning, effectiveness); recommended default A with B and C as views |
| 9 | Real time | near-real-time monitoring from streams only; **never in the authorisation path** |
| 10 | Data protection | FCU schema separate and encrypted; AI off for FCU data; FCU roles never implied by administrator; retention 5 years after closing |
| 11 | Network depth | bounded algorithms (components, cycles to 6, 3 hops) in 2.9.22; community detection and graph learning later on request |

## 21. Build checklist

| # | Item | Release | Size |
|---|---|---|---|
| F1 | The `financial-crime` pack: contracts of section 2 (customer, account, transaction, watchlist, device, card authorisation) with pages; the seeded generator with planted patterns and ground truth | 2.9.21 | M |
| F2 | The scenario framework and transaction monitoring: windows in one pass, segments, suppression, BTL events, the eight scenarios; `config/engines/fcu-transaction-monitoring.yaml` | 2.9.21 | **L** |
| F3 | The threshold-tuning playground and workbook | 2.9.21 | M |
| F4 | Alerts and case management on write-back: states, queues, SLAs, four eyes, evidence pinning, audit; data protection (roles, masking, tipping-off rule, retention, the separate schema) | 2.9.21 | **L** |
| F5 | Customer risk rating with its playground | 2.9.21 | M |
| F6 | FINTRAC STR, LCTR and EFTR on the report framework | 2.9.21 | M |
| F7 | Rule effectiveness and the model inventory | 2.9.21 | S |
| F8 | Sanctions and PEP screening: lists, normalisation, transliteration, blocking, scoring, decisions, rescreening, its playground | 2.9.22 | **L** |
| F9 | Trade surveillance: `order-event`, `market-tick`, five scenarios, the order-book replay, its playground | 2.9.22 | **L** |
| F10 | Network analytics: the graph, five patterns, the network tab, its playground | 2.9.22 | M |
| F11 | Fraud scoring: streaming rules, the transparent scorecard, reason codes, its playground | 2.9.22 | M |
| F12 | The hand-built cases as tests and drill rows; the guide and a tutorial per engine | both | S each |

## 22. Sources

- FATF, *The FATF Recommendations* (the 40 Recommendations and their interpretive notes):
  [fatf-gafi.org](https://www.fatf-gafi.org/en/publications/Fatfrecommendations/Fatf-recommendations.html); high-risk and other monitored
  jurisdictions: [fatf-gafi.org](https://www.fatf-gafi.org/en/countries/black-and-grey-lists.html)
- FINTRAC, *Reporting suspicious transactions to FINTRAC*:
  [fintrac-canafe.canada.ca](https://fintrac-canafe.canada.ca/guidance-directives/transaction-operation/str-dod/str-dod-eng)
- FINTRAC, *Reporting large cash transactions to FINTRAC*:
  [fintrac-canafe.canada.ca](https://fintrac-canafe.canada.ca/guidance-directives/transaction-operation/lctr-doie/lctr-doie-eng)
- FINTRAC, *Reporting electronic funds transfers to FINTRAC*:
  [fintrac-canafe.canada.ca](https://fintrac-canafe.canada.ca/guidance-directives/transaction-operation/eft-dt/eft-dt-eng)
- FINTRAC, *Reporting transactions to FINTRAC: the 24-hour rule*:
  [fintrac-canafe.canada.ca](https://fintrac-canafe.canada.ca/guidance-directives/transaction-operation/24hour/1-eng)
- *Proceeds of Crime (Money Laundering) and Terrorist Financing Act* (Canada), section 8 (no disclosure):
  [laws-lois.justice.gc.ca](https://laws-lois.justice.gc.ca/eng/acts/p-24.501/)
- Global Affairs Canada, Consolidated Canadian Autonomous Sanctions List:
  [international.gc.ca](https://www.international.gc.ca/world-monde/international_relations-relations_internationales/sanctions/consolidated-consolidee.aspx?lang=eng);
  UN Security Council Consolidated List: [un.org](https://main.un.org/securitycouncil/en/content/un-sc-consolidated-list); OFAC SDN list:
  [treasury.gov](https://ofac.treasury.gov/specially-designated-nationals-and-blocked-persons-list-sdn-human-readable-lists)
- IOSCO, *Objectives and Principles of Securities Regulation* (market integrity, the prevention of manipulation):
  [iosco.org](https://www.iosco.org/library/pubdocs/pdf/IOSCOPD561.pdf)
- EU Market Abuse Regulation (EU) No 596/2014 (article 12 market manipulation, article 16 detection and reporting):
  [eur-lex.europa.eu](https://eur-lex.europa.eu/eli/reg/2014/596/oj); Commission Delegated Regulation (EU) 2016/957 (arrangements to
  detect and report suspicious orders and transactions): [eur-lex.europa.eu](https://eur-lex.europa.eu/eli/reg_del/2016/957/oj)
- CIRO, Universal Market Integrity Rules (Canada's trading rules on manipulative and deceptive activity): [ciro.ca](https://www.ciro.ca)
- FinCEN, SAR rule for banks, 31 CFR 1020.320: [ecfr.gov](https://www.ecfr.gov/current/title-31/subtitle-B/chapter-X/part-1020/subpart-C/section-1020.320);
  currency transaction reports, 31 CFR 1010.311: [ecfr.gov](https://www.ecfr.gov/current/title-31/subtitle-B/chapter-X/part-1010/subpart-C/section-1010.311)
- EU Anti-Money Laundering Regulation (EU) 2024/1624: [eur-lex.europa.eu](https://eur-lex.europa.eu/eli/reg/2024/1624/oj)
- OSFI Guideline E-23, Model Risk Management: [osfi-bsif.gc.ca](https://www.osfi-bsif.gc.ca/en/guidance/guidance-library);
  Federal Reserve SR 11-7, Guidance on Model Risk Management: [federalreserve.gov](https://www.federalreserve.gov/supervisionreg/srletters/sr1107.htm)
