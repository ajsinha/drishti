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
# Groupings and analytics guide

*Revision 1.0 · 2026-10-26 · release 2.9.2 · the one reference for the groupings and analytics computed instead of stored. The
hands-on companion is [tutorial 27](../tutorials/27-groupings-and-analytics.md).*

Seven pages that used to be **stored copies** of figures are **computed from the fundamental data when they are opened** since 2.9.2:

| Command | What you open | Computed from | By |
|---|---|---|---|
| `LEIG` (`lei-group`) | an ownership group: its tree, members, counts | every GLEIF legal entity in the lake | the derived connector `gleif-groupings` |
| `LEIC` (`lei-country`) | a country: its entities, groups, lapsed and late renewals | every GLEIF legal entity | `gleif-groupings` |
| `SIC` (`sic-sector`) | a SIC sector: its companies, revenue, net income | every us-equities company | `us-equities-groupings` |
| `ECG` (`country-group`) | a World Bank region, income group or the world | every economy | `macro-groupings` |
| `CORR` (`correlation-matrix`, `correlation-result`) | a factor set's correlations | the factors' daily closes | the reactive `correlation-engine` (Java) |
| `USEQ` ratios (`us-company`), `FACT` changes, `IDX` totals | net margin, revenue growth, a fact's change on the year, an index's members' revenue and divisions | the company's financial facts, the index's members | the page itself |
| `PXH` analytics (`price-history`) | returns, volatility, drawdown, beta | the price history just read | the price connector (`yfinance`) |
| `EQB` (`equity-beta`; the `EQ` page's beta) | an equity's beta against its index | the equity's and the index's daily closes | the reactive `equity-analytics` (Java) |

The rule is the one the [implementation plan](../architecture/IMPLEMENTATION_PLAN.md#groupings-and-analytics-track-b-shipped-in-290-or-2100-as-each-is-ready)
sets for every computed measure: **fundamental data is kept as it is; whatever can be derived from it is computed when asked and never
stored**, and the stored version is retired in the same release. A stored copy of a computed figure drifts: the stored ownership groups
were capped at 50 members, the stored countries listed 400 entities, the stored correlations were synthetic and did not match the prices in
the same lake.

**Part I** shows each command on the committed data, **Part II** is the data contract of every computed kind (every column, its formula,
its inputs and its errors) with the reconciliation against the retired stored pages, and **Part III** says how each is computed and where
its inputs come from.

## Contents

- **Part I · Using them**
  - [1. What changed for you](#1-what-changed-for-you)
  - [2. LEIG: an ownership group](#2-leig-an-ownership-group)
  - [3. LEIC: a country](#3-leic-a-country)
  - [4. SIC: a sector](#4-sic-a-sector)
  - [5. ECG: a country group](#5-ecg-a-country-group)
  - [6. CORR: correlations from price history](#6-corr-correlations-from-price-history)
  - [7. USEQ, FACT and IDX: margins, changes and totals](#7-useq-fact-and-idx-margins-changes-and-totals)
  - [8. PXH: returns, volatility and beta](#8-pxh-returns-volatility-and-beta)
  - [9. Old ids, links and commands](#9-old-ids-links-and-commands)
- **Part II · The data contract**
  - [10. The contract at a glance](#10-the-contract-at-a-glance)
  - [11. lei-group](#11-lei-group)
  - [12. lei-country](#12-lei-country)
  - [13. sic-sector](#13-sic-sector)
  - [14. country-group](#14-country-group)
  - [15. correlation-result](#15-correlation-result)
  - [16. The USEQ ratios](#16-the-useq-ratios)
  - [17. The PXH analytics](#17-the-pxh-analytics)
  - [18. Reconciliation: stored against computed](#18-reconciliation-stored-against-computed)
- **Part III · How they are computed**
  - [19. The derived connector](#19-the-derived-connector)
  - [20. The correlation engine](#20-the-correlation-engine)
  - [21. The price analytics](#21-the-price-analytics)
  - [22. Where the inputs come from](#22-where-the-inputs-come-from)
  - [23. The retirement guard](#23-the-retirement-guard)
  - [24. Settings and limits](#24-settings-and-limits)

---

# Part I · Using them

## 1. What changed for you

Very little on the screen, a lot underneath:

- **The commands, ids and links are the same.** `LEIG 8I5DZWZKVSZI1NUHU748`, `LEIC GB`, `SIC 3674`, `ECG ECG-SAS` and `CORR CORR-EU-EQ` open as
  before; a bookmark, a link from another page, an export or a BI dataset that names one of them reads the computed page. The `ALL`
  directories (`LEIG ALL`, `LEIC ALL`, `SIC ALL`) are computed too.
- **No cap.** A group lists every member the lake holds, a country every entity (the stored country page listed the first 400).
- **The pill says computed.** Each page's title pill ends in *(computed)*, and an explainer on the right says how.
- **New figures** that the stored pages could not have: LEIG's lapsed registrations and each member's level, SIC's net margin and the
  number of filers not reporting in USD, ECG's median inflation and every member economy, CORR's window and estimator, PXH's returns,
  volatility and beta.
- **Gone:** the stored figures themselves (the `lei-group`, `lei-country` and `sic-sector` snapshots, the stored country groups, the
  stored correlation pairs and the stored ratios). [Section 18](#18-reconciliation-stored-against-computed) explains every difference.

The pages are computed **once per business date** and shared by every reader (a refresh of 60 seconds, rebuilt when the lake loads), so
opening one is as fast as before. A date picked in the top bar computes that date's pages from that date's entities.

## 2. LEIG: an ownership group

```text
LEIG 8I5DZWZKVSZI1NUHU748 <GO>
```

JPMorgan Chase & Co.'s group, computed from the 50 legal entities of the committed data whose ownership group (`LEI_GROUP`) it is:

| Strip | Value | How |
|---|---|---|
| Members | 50 | the entities of the group |
| Members (GLEIF) | 173 | the ultimate parent's `GLEIF_GROUP_SIZE` (GLEIF's golden copy) |
| Levels | 2 | the deepest level of the tree: 1 ultimate parent, 20 entities at level 1, 29 at level 2 |
| Countries | 13 | US 27, GB 8, BR 3, IE 2, AU 2, ... |
| Late renewals | 5 | members whose LEI renewal is late |
| Home | United States of America (the) | the ultimate parent's country |

The **tree** opens two levels: the ultimate parent, its direct children, theirs. It is built from each member's `DIRECT_PARENT`; a member
whose direct parent is not in the group hangs under the ultimate parent. The **members** table lists all 50 with their country and level;
a click opens the member's key facts beside it. The **Cap** line says *GLEIF has 173 entities in the group; the committed data keeps 50*:
the committed snapshot keeps at most 50 entities a group to stay small, the computation itself has no cap (a wider refresh, [section 22](#22-where-the-inputs-come-from),
brings more and the page counts them all).

Other groups: `LEIG MLU0ZO3ML4LN2LL2TL39` (HSBC Holdings: 50 kept of 461 in GLEIF, one level, 18 countries, 9 late),
`LEIG HWUPKR0MPOU8FGXBT394` (Apple: the whole group, 21 of 21, three levels, 15 countries, none late). `LEIG ALL` is the pack's front page:
9,937 entities in 590 groups across 133 countries, 2,256 lapsed and 2,288 late renewals, every group's ultimate parent largest in GLEIF
first, and the search over every entity.

The type-ahead finds a group by its ultimate parent's name: `LEIG Ford Motor` suggests Ford's group.

## 3. LEIC: a country

```text
LEIC GB <GO>
```

| Strip | Value |
|---|---|
| Entities | 1,274 |
| Groups present | 263 |
| Groups headquartered | 27 |
| Lapsed | 225 |
| Late renewals | 227 late: renewal failed |

Every one of the 1,274 entities is listed by name (the stored page stopped at 400), with its city and the late flag; a click opens the
entity. The groups table counts each ownership group's entities here, largest first (the group key, a link to `LEIG`). On the right:
entities by legal form and by city (`LONDON` 494, `London` 245, `EDINBURGH` 36: GLEIF keeps the city as each entity wrote it).
`LEIC US`: 2,704 entities, 521 groups present, 451 headquartered, 713 lapsed, 724 late. `LEIC ALL`: 133 countries, 9,937 entities,
each country with its entities, largest first.

## 4. SIC: a sector

```text
SIC 3674 <GO>
```

Semiconductors & Related Devices: 15 companies; revenue 588.8bn and net income 251.0bn USD over their latest fiscal years; **net margin
42.6%**; none reporting in another currency. The companies are ranked by revenue (NVDA 215.9bn at a 55.6% margin, MU 133.2bn, AVGO 63.9bn,
INTC 52.9bn at −0.5%, AMD 34.6bn); a click opens a company's key facts and ten years of financials beside the table.

`SIC 6022` (State Commercial Banks) shows the currency rule: 15 companies, revenue 70.0bn and net income 25.5bn USD over **14**: the
fifteenth, BNS, reports in CAD, so it is counted and listed but **not summed** (the strip says *1 not summed*). `SIC ALL` lists the 181
codes and totals revenue by SIC division: 503 companies, 17.45 trillion USD of revenue (the 492 USD reporters), 2.24 trillion of net income.

## 5. ECG: a country group

```text
ECG ECG-SAS <GO>
```

South Asia, a World Bank region, computed from its six member economies:

| Strip | Value | How |
|---|---|---|
| Economies | 6 | every economy whose region or income group is ECG-SAS |
| GDP (USD bn) | 4,578 | the members' GDP summed |
| GDP per capita (USD) | 2,705 | that GDP over the members' population (1,692.26m) |
| Real growth | 7.07% | weighted by GDP: India 3,956bn at 7.57%, Bangladesh 456bn at 3.49%, ... |
| Inflation (median) | 3.10% | the median of the members, as the World Bank aggregates prices |
| Latest year (members table) | 2025 | the latest year among the members' figures (each member's own in its row) |

Below: the largest economies, every member economy (a click opens it), and the GDP path, the members' GDP summed by year. `ECG ECG-WLD`
(the world): 213 economies, 117,327bn USD, 8,161.8m people, 14,375 USD a head, growth 2.80%, median inflation 3.11%. `ECG ECG-HIC` (high
income): 84 economies, 75,910bn, 54,209 a head, growth 1.88%: the stored page had **no figures** for the income groups, the computed one
has them all.

## 6. CORR: correlations from price history

```text
CORR CORR-EU-EQ <GO>
```

The correlation set keeps only its definition: *European equities*, four factors (EQ-ORBT, EQ-RHNB, EQ-GRNT, EQ-LUMN). The panels ask the
computed kind `correlation-result` for the window and estimator picked at the top of the page:

| Window | Estimator | Returns used | Average ρ | Lowest | Highest |
|---|---|---|---|---|---|
| All (0, the default) | Pearson (the default) | 29 (the test data's 2026-08-19 to 2026-09-30) | 0.096 | −0.156 (RHNB, GRNT) | 0.382 (RHNB, LUMN) |
| 20 | Pearson | 20 (from 2026-09-01) | 0.142 | −0.165 | 0.407 |
| All | Ledoit-Wolf | 29 | 0.000 | 0.000 | 0.000 (shrinkage 1.00) |

Ledoit-Wolf shrinks the sample correlations toward none by the intensity that minimises the expected error; on 29 returns of four
factors whose sample correlations are within noise, the optimal intensity is 1 and every pair is shrunk to zero. That is the estimator
saying *these 29 days do not show a correlation you can trust*; a longer history moves it below 1. The factors table gives each factor's
last close, its return over the window and its annualised volatility (EQ-ORBT 22.7%).

The computed kind opens on its own too: `CORRC CORR-G10FX` (G10 FX, Pearson over 29 returns: average −0.012, EURUSD and GBPUSD 0.222),
with parameters riding on the id: `CORRC CORR-G10FX~estimator=ledoit-wolf~window=20`.

## 7. USEQ, FACT and IDX: margins, changes and totals

```text
USEQ AAPL <GO>
```

The strip's **Net margin** (26.9%) and **Revenue growth** (+6.4%) are worked out as the page opens from the company's own facts: fiscal 2025
revenue 416.16bn and net income 112.01bn; growth against fiscal 2024's revenue in the financials table. The financials table's net
margin column is computed per year the same way. `USEQ JPM`: 31.3% and +2.8%; `USEQ F`: −4.4% and +1.2%. The index pages (`IDX SP500`)
and the sector pages compute a member's margin the same way.

`FACT AAPL-REVENUE` works out each year's change against the year above it in the table, and the strip's *Change on the year* from the
last two values (+6.4%). `IDX SP500` adds up its 459 members' rows as it opens: revenue 17.20 trillion and net income 2.17 trillion USD
(the USD reporters), 9 SIC divisions, members by division on the right.

## 8. PXH: returns, volatility and beta

```text
PXH PX-AAPL <GO>
```

**Internal use only, and off as shipped**, like the prices themselves (`DRISHTI_US_EQUITIES_PRICES_ENABLED=false`). With the connector on
(a site's decision: free price feeds forbid redistribution), the page's **Analytics** panel shows, computed from the year of daily closes
the connector has just read: the 1, 3 and 6 month returns and the return over the whole history, the annualised volatility of the daily
log returns, the largest drawdown, and beta and correlation against the benchmark (`SPY` unless the site sets `DRISHTI_US_EQUITIES_BENCHMARK`).
Nothing is stored: the analytics are as fresh, and as internal, as the prices. With the connector off the page has no data.

### 8.1 EQB: an equity's beta from price history

```text
EQ EQ-NVTK <GO>          the equity page: its Beta panel
EQB EQ-NVTK <GO>         the computed kind itself
```

The security master's stored `BETA` (1.28 for Novatek) is gone. The equity page's **Beta** panel asks the computed kind `equity-beta`
for the equity's beta against its index (`EQUITY_INDEX`: EQX-NDX), the regression of the two daily log return series on the dates both
have: over the 29 returns to 2026-09-30, **−0.07** with a correlation of −0.03. The demo's prices are independent synthetic walks, so
their measured betas are near zero; the stored ones were set by hand. An equity whose record names no index (EQ-CSCA, EQ-LUMN) gets a
refusal in words instead of a number. `EQB EQ-NVTK~window=20` takes the last 20 returns. `EQB` has its own page since 2.9.2
(`config/packs/market-data/sutras/market-data/equity-beta.v1.sutra.yaml`): the beta, correlation and R² in the strip to 2 decimals, and
the regression to 4 (beta −0.0725, alpha −0.0881% a day, correlation −0.0333, R² 0.0011, volatility 26.72% against the index's 12.27%),
where the inferred layout had rounded them to whole numbers ("0").

## 9. Old ids, links and commands

| Old | Now |
|---|---|
| `LEIG <LEI>`, `/v/lei-group/<LEI>`, `LEIG ALL` | the same ids, computed from the entities (no member cap); `MEMBERS_IN_GLEIF` from the ultimate parent's `GLEIF_GROUP_SIZE` |
| `LEIC <cc>`, `/v/lei-country/<cc>`, `LEIC ALL` | the same ids, computed (no 400-row cap) |
| `SIC <code>`, `/v/sic-sector/<code>`, `SIC ALL` | the same ids, computed from the companies |
| `ECG ECG-<code>`, `/v/country-group/ECG-<code>` | the same ids, computed from the economies |
| `CORR CORR-<set>`, `/v/correlation-matrix/CORR-<set>` | the same ids: the set's definition, its correlations computed (`CORRC CORR-<set>`) |
| `us-company` fields `NET_MARGIN`, `REVENUE_GROWTH`, `FINANCIALS[].NET_MARGIN`; `index-membership` `ROWS[].NET_MARGIN` | removed from the records; the pages compute them (a saved search on `NET_MARGIN` no longer finds anything) |
| `correlation-matrix` fields `AVERAGE`, `PAIRS`, `WINDOW`, `ESTIMATOR` | removed; `correlation-result` answers them |
| `financial-fact` `CHANGE_PCT`, `HISTORY[].CHANGE_PCT` | removed; the page computes the change |
| `index-membership` `SECTORS`, `REVENUE`, `NET_INCOME`, `BY_SECTOR` | removed; the page sums the members' rows (the membership and `MEMBERS` stay) |
| `equity` `BETA` | removed; `EQB <ticker>` and the equity page's Beta panel compute it |
| the search form of `lei-group` (`FORM gleif`) | gone: the type-ahead finds a group by its name, and the `LEIG ALL` search finds entities |

---

# Part II · The data contract

## 10. The contract at a glance

Every computed kind is an ordinary kind: it opens by command, link, API, CLI and AI, with the reader's entitlements and masks applied to
its inputs. The machine-readable contracts are `config/packs/<pack>/contracts/<kind>.contract.yaml` (class `computed`; the pages
[docs/contracts/packs/](../contracts/packs/README.md)); this part says what each field means.

| Kind | Command | Connector (plugin) | Key | Inputs | Dated by |
|---|---|---|---|---|---|
| `lei-group` | LEIG | `gleif-groupings` (derived) | `GROUP_ID`: an ultimate parent's LEI, or `ALL` | `lei-entity` | the business date asked (the entities' date) |
| `lei-country` | LEIC | `gleif-groupings` (derived) | `COUNTRY`: an ISO 3166 code, or `ALL` | `lei-entity` | as above |
| `sic-sector` | SIC | `us-equities-groupings` (derived) | `SIC`: a four-digit code, or `ALL` | `us-company` | as above |
| `country-group` | ECG | `macro-groupings` (derived) | `GROUP_ID`: `ECG-<World Bank code>` | `economy` | as above |
| `correlation-result` | CORRC | `correlation-engine` (reactive, Java) | the set's id, `~window=`, `~estimator=` | `correlation-matrix`, `equity`, `fx-spot` | the request |
| USEQ ratios | USEQ | none: the `us-company` page | `TICKER` | `us-company` | the company record's |
| PXH analytics | PXH | `us-equities-prices` (yfinance) | `PX-<ticker>` | the price history read live | the request (live only) |
| `equity-beta` | EQB | `equity-analytics` (reactive, Java) | the equity's id, `~window=` | `equity`, `equity-index` | the request |
| FACT changes, IDX totals | FACT, IDX | none: the pages | `FACT_ID`, `INDEX` | `financial-fact`, `index-membership` | the record's |

Every derived page also carries `id` (the key), `derivedFrom` (the kind it groups), `memberCount` and the member ids (`MEMBER_LEIS`,
`TICKERS`, `MEMBER_ECONOMIES`). A pane `ALL` keeps its aggregates and the lists its `then` fields make, but **not** its member ids, rows or
tree (`unlisted`, [section 19](#19-the-derived-connector)).

**Number rules:** counts are whole numbers; `sum` skips a member without the figure (a missing number is never a zero); a ratio whose
denominator is zero or missing is empty; percentages are as published (12.3 means 12.3%) except where a field says *a fraction*.

## 11. lei-group

One page per ultimate parent: the members are every `lei-entity` whose `LEI_GROUP` is the key. Every entity is also in `ALL`.

| Field | Type | Formula | Notes |
|---|---|---|---|
| `GROUP_ID` | id | the key | the ultimate parent's LEI |
| `MEMBERS` | integer | count of members | no cap |
| `LATE_RENEWALS` | integer | count where `RENEWAL_LATE` | |
| `LAPSED` | integer | count where `REGISTRATION_STATUS == 'LAPSED'` | new in 2.9.2 |
| `COUNTRY_LIST`, `GROUP_LIST` | text list | the distinct `LEI_COUNTRY`, `LEI_GROUP` | helpers |
| `ROOT_NAME`, `ROOT_COUNTRY`, `ROOT_COUNTRY_NAME`, `ROOT_LEGAL_FORM`, `ROOT_SEGMENT`, `ROOT_US_COMPANY` | text list | the field of the member whose `LEI == LEI_GROUP` | helpers |
| `GLEIF_GROUP_SIZE` | integer | the ultimate parent's `GLEIF_GROUP_SIZE` | an input from GLEIF |
| `NAME`, `LEI_COUNTRY`, `COUNTRY_NAME`, `LEGAL_FORM`, `SEGMENT`, `US_COMPANY` | text | the first of the matching `ROOT_*` | `NAME` is *Every ownership group of the pack* on `ALL` |
| `ENTITY_LEI` | id | the key (empty on `ALL`) | a link to the entity |
| `COUNTRIES`, `GROUPS` | integer | `size(COUNTRY_LIST)`, `size(GROUP_LIST)` | |
| `MEMBERS_IN_GLEIF` | integer | `max(GLEIF_GROUP_SIZE, MEMBERS)`; `MEMBERS` when GLEIF says nothing | |
| `CAPPED` | boolean | `GLEIF_GROUP_SIZE > MEMBERS` | the committed snapshot keeps at most 50 a group |
| `ROWS[]` | record list | one row a member: `LEI`, `NAME`, `COUNTRY`, `CITY`, `LEGAL_FORM` (*Not given* when blank), `PARENT` (`DIRECT_PARENT`), `STATUS`, `LATE`, `SEGMENT`, `GROUP`, `GLEIF_GROUP_SIZE`, `US_COMPANY`, `LEVEL` | in member id order |
| `TREE` | record list | the rows nested under the key by `PARENT`; each node a row with `CHILDREN` | a parent outside the group, a missing one or a cycle hangs the row under the key |
| `LEVELS` | integer | the deepest `LEVEL` (the key is 0) | |
| `BY_COUNTRY`, `BY_LEGAL_FORM`, `BY_SEGMENT` | record list | `group(ROWS, field)`: rows of `label`, `value` (members), `count`; largest first, the top 20 and 15 | |
| `GROUP_ROWS` | record list | on `ALL`: the rows of every ultimate parent, largest `GLEIF_GROUP_SIZE` first | empty on a group |
| `PROVENANCE` | record | the first member's (GLEIF, CC0, the retrieval date) | |

**Errors:** an id that is no entity's group answers *entity not found* (`DRS-1001`, as any missing entity). A member without `LEI_GROUP` is in no
group (none in the committed data). A field a member lacks is skipped; an expression that fails on one member leaves that member's value
empty and never fails the page.

## 12. lei-country

One page per legal-address country (`LEI_COUNTRY`), and `ALL`.

| Field | Type | Formula |
|---|---|---|
| `COUNTRY` | id | the key |
| `ENTITIES` | integer | count of members |
| `GROUPS_HEADQUARTERED` | integer | count where `LEI == LEI_GROUP` (an ultimate parent with its legal address here) |
| `LAPSED`, `LATE_RENEWALS` | integer | as in `lei-group` |
| `NAME` | text | the members' `COUNTRY_NAME` (*Every country of the entities in the pack* on `ALL`) |
| `COUNTRIES`, `GROUPS_PRESENT` | integer | the distinct countries and groups among the members |
| `ROWS[]` | record list | one row an entity: `LEI`, `NAME`, `COUNTRY`, `COUNTRY_NAME`, `CITY`, `LEGAL_FORM`, `GROUP`, `STATUS`, `LATE`; by name; **every** entity |
| `BY_LEGAL_FORM`, `BY_CITY` | record list | `group(ROWS, ...)`, the top 15 |
| `GROUP_ROWS` | record list | `group(ROWS, 'GROUP')`: each group key with its entities here, largest first |
| `BY_COUNTRY` | record list | by country name, the top 20 (useful on `ALL`) |
| `COUNTRY_ROWS` | record list | on `ALL`: every country code with its entities, largest first |
| `PROVENANCE` | record | the first member's |

## 13. sic-sector

One page per SIC code reported by at least one us-equities company, and `ALL`.

| Field | Type | Formula |
|---|---|---|
| `SIC` | id | the key |
| `COMPANIES` | integer | count of companies (every currency) |
| `REVENUE` | amount | sum of `REVENUE` over the companies with `CURRENCY == 'USD'` |
| `NET_INCOME` | amount | the same for `NET_INCOME` |
| `NET_MARGIN` | rate | `NET_INCOME / REVENUE × 100` (empty when `REVENUE` is 0) |
| `NON_USD` | integer | count with `CURRENCY != 'USD'`: listed, not summed |
| `DESCRIPTION`, `DIVISION`, `SECTOR` | text | the companies' `SIC_DESCRIPTION`, `DIVISION`, `SECTOR` |
| `CODES` | integer | the distinct SIC codes (181 on `ALL`) |
| `ROWS[]` | record list | one row a company: `TICKER`, `NAME`, `EXCHANGE`, `INDEX_TEXT`, `SECTOR`, `REVENUE`, `NET_INCOME`, `NET_MARGIN` (the company's, computed), `CURRENCY`, `FISCAL_YEAR`; largest revenue first |
| `BY_REVENUE` | record list | the USD reporters with revenue, the top 15 |
| `BY_DIVISION` | record list | `group(ROWS[USD], 'SECTOR', 'REVENUE')`, largest first |
| `PROVENANCE` | record | the first company's (SEC EDGAR, public domain) |

**Why USD only:** a sum of USD, CAD, GBP and EUR figures means nothing; the stored pages did the same, so the totals reconcile exactly.

## 14. country-group

One page per World Bank region and income group, and the world. An economy is in its `REGION_GROUP`, its `INCOME_GROUP` and `ECG-WLD`;
each counts at its latest year (`YEAR`).

| Field | Type | Formula | Weights |
|---|---|---|---|
| `GROUP_ID` | id | the key | |
| `ECONOMIES` | integer | count of member economies | |
| `GDP_USD_BN` | amount | sum of `GDP_USD_BN` | |
| `POPULATION_M` | amount | sum of `POPULATION_M` | |
| `GDP_PER_CAPITA` | amount | `GDP_PC_GDP × 1000 / GDP_PC_POP`: GDP over population, both summed over the members that have both | |
| `GROWTH` | rate | `GROWTH_WEIGHTED / GROWTH_WEIGHT` = Σ GDP·growth / Σ GDP over the members with both | GDP in USD |
| `INFLATION` | rate | the median of the members' `INFLATION` (an even count: the mean of the middle two) | none: the median, as the World Bank aggregates consumer prices |
| `WITH_GROWTH`, `WITH_INFLATION` | integer | the members each figure is taken over | |
| `NAME`, `GROUP_TYPE` | text | the region's or income group's name (from the members' `REGION_NAME`, `INCOME_NAME`), or *World* | |
| `YEAR` | text | the latest of the members' years | |
| `ROWS[]` | record list | one row a member: `ECONOMY_ID`, `ECONOMY`, `ISO3`, `GDP_USD_BN`, `POPULATION_M`, `GDP_PER_CAPITA`, `GROWTH`, `INFLATION`, `YEAR`, `GDP_PATH`; largest GDP first | |
| `TOP_ECONOMIES` | record list | the ten largest rows | |
| `GDP_PATH` | record list | `group(flatten(ROWS[*].GDP_PATH), 'YEAR', 'GDP')`: each year's GDP summed over the members with a figure that year (`label` year, `value` USD bn, `count` members) | |
| `PROVENANCE` | record | the first member's (World Bank WDI CC BY 4.0, IMF, CIA Factbook) | |

The stored page's `GROWTH_PATH` (the World Bank's aggregate growth by year) is not computed: a GDP-weighted growth per year needs each
member's GDP and growth of the same year joined, which the page does not do; each member economy's page has its own growth path.

## 15. correlation-result

The answer for one correlation set (`correlation-matrix`, which keeps `MATRIX_ID`, `NAME`, `FACTORS`, `EQUITY_IDS`, `FX_IDS`), one window
and one estimator.

**Parameters** (on a panel's `source: params`, on the id as `CORR-EU-EQ~window=20~estimator=ledoit-wolf`, or `--param` on the CLI):

| Parameter | Type | Default | Allowed |
|---|---|---|---|
| `window` | int | `0`: every return the history holds | 0 to 5,000 daily returns |
| `estimator` | enum | `pearson` | `pearson`, `ledoit-wolf` |

**The computation:**
1. Read the set, then each factor's document: an equity's `HISTORY[]` of `DATE`, `CLOSE`; an FX pair's of `DATE`, `MID` (as of the business
   date asked; the committed data holds the last 30 closes on each date).
2. Keep the dates **every** factor has; take the last `window + 1` closes (all when `window` is 0 or longer than the history).
3. Daily log returns `r_t = ln(P_t / P_{t-1})`; each factor's mean `μ` and standard deviation `σ` (divided by `n`).
4. **Pearson:** `ρ_ij = (1/n) Σ_t z_it z_jt` with `z = (r − μ)/σ`.
5. **Ledoit-Wolf** (2004, toward the scaled identity): `S` the matrix above, `m = tr(S)/p`, `d² = ‖S − mI‖²/p`,
   `b̄² = (1/n²) Σ_t ‖z_t z_tᵀ − S‖²/p`, `b² = min(b̄², d²)`, intensity `δ = b²/d²`; `Σ* = (1−δ)S + δmI`, read back as correlations.

| Field | Type | Meaning |
|---|---|---|
| `MATRIX_ID`, `NAME` | id, text | the set |
| `FACTORS` | id list | the factors, equities then FX |
| `ESTIMATOR`, `WINDOW`, `AVAILABLE` | text, integer, integer | the estimator, the returns used, the returns the common dates allow |
| `FROM`, `TO` | date | the window's first and last close |
| `AVERAGE`, `MIN_RHO`, `MAX_RHO` | rate | the mean, lowest and highest pairwise ρ (four decimals) |
| `SHRINKAGE` | rate | Ledoit-Wolf only: `δ` |
| `PAIRS[]` | record list | `A`, `B`, `RHO` for every pair, in factor order |
| `FACTOR_STATS[]` | record list | `FACTOR`, `LAST` (the window's last close), `RETURN` (simple, a fraction), `VOLATILITY` (`σ√252`, a fraction) |
| `STAMP` | record | how the answer was computed (pin, inputs, engine, where it ran), as every reactive answer |

**Errors** (the answer's state is *failed* and its message says which, as `drishti.py compute run` prints it; a parameter outside its
options is *refused* before any work: `estimator kendall is not allowed for correlation-result; allowed: pearson, ledoit-wolf`; a set
that does not exist opened as a page is `DRS-1001`): no such set; fewer than two factors; a factor without price history;
fewer than three common dates; a price of zero or less; a factor that did not move over the window (its correlation is undefined); an
estimator other than the two.

## 16. The USEQ ratios

Computed by the `us-company` page (and by the index, sector and company-card panels) from the record's facts as it is drawn:

| Ratio | Formula | Empty when |
|---|---|---|
| Net margin (strip, card, rows) | `NET_INCOME / REVENUE × 100` | `REVENUE` is 0 or missing |
| Revenue growth (strip) | `(REVENUE − R₋₁) / abs(R₋₁) × 100`, `R₋₁` the `FINANCIALS[]` revenue of `FISCAL_YEAR − 1` | that year is missing or its revenue is 0 |
| Net margin per year (financials table) | `NET_INCOME / REVENUE × 100` of the row | as above |

`REVENUE`, `NET_INCOME`, `FISCAL_YEAR` and `FINANCIALS[]` are the facts as filed with the SEC (XBRL company facts, `financial-fact`); the
ratios are not on the record any more. The same way:

| Page | Figure | Formula |
|---|---|---|
| `FACT` (financial-fact) | change on the year (strip) | `(LATEST − V₋₁) / abs(V₋₁) × 100`, `V₋₁` the `HISTORY[]` value before the last; empty with one value or a zero |
| `FACT` | change (each row) | the row's value against the row above it (the previous reported year) |
| `IDX` (index-membership) | members' revenue, net income | `sum(ROWS[CURRENCY = USD], REVENUE)`, the same for `NET_INCOME` |
| `IDX` | SIC divisions; members by division | `size(group(ROWS, 'SECTOR'))`; `group(ROWS, 'SECTOR')` |

## 17. The PXH analytics

Computed by the price connector (the `yfinance` plugin, `PriceAnalytics`) from the daily closes it has just read, in the field `ANALYTICS`:

| Field | Formula |
|---|---|
| `OBSERVATIONS` | the closes (a year as shipped: `range: 1y`, `interval: 1d`) |
| `RETURN_1M`, `RETURN_3M`, `RETURN_6M` | `P_last / P_{last−k} − 1` for k = 21, 63, 126 trading days; empty when the history is shorter |
| `RETURN_PERIOD` | `P_last / P_first − 1` |
| `VOLATILITY` | the sample standard deviation of the daily log returns × √252 |
| `MAX_DRAWDOWN` | the most negative `P_t / max(P_≤t) − 1` |
| `BENCHMARK` | the connector's `benchmark` setting (`SPY` as shipped) |
| `BETA` | `cov(r, r_b) / var(r_b)` of the daily log returns on the dates both have; empty with fewer than 21 common closes |
| `CORRELATION` | `cov(r, r_b) / (σ σ_b)` on the same dates |

All are fractions (0.123 is 12.3%). The benchmark's history is read through the same connector and cache; when it cannot be read, beta
and correlation are empty and the rest is shown.

### 17.1 equity-beta

`EQB` (and the equity page's Beta panel): the regression of the equity's daily log returns on its index's (`EQUITY_INDEX`), on the
dates both histories have, over the last `window` returns (parameter `window`, default 0: every return). Engine
`com.ash.drishti.compute.reactive.analytics.BetaEngine`, connector `equity-analytics` (reads `equity` `{id}` and `equity-index`
`{inputs.equity.EQUITY_INDEX}`).

| Field | Type | Formula |
|---|---|---|
| `EQUITY`, `INDEX` | id | the equity asked for, its index |
| `WINDOW`, `AVAILABLE`, `FROM`, `TO` | integer, date | as in `correlation-result` |
| `BETA` | rate | `cov(r, r_i) / var(r_i)` |
| `ALPHA` | rate | `mean(r) − BETA × mean(r_i)`, a daily log return |
| `CORRELATION`, `R_SQUARED` | rate | `ρ(r, r_i)` and its square |
| `VOLATILITY`, `INDEX_VOLATILITY` | rate | the sample standard deviations × √252 (fractions) |

**Errors** (the answer's state *failed*, its message in words: `EQ-CSCA names no index (EQUITY_INDEX): its beta has nothing to be measured
against`): no such equity; an equity that names no index; an index without price history; fewer than three
common dates; an index that did not move over the window.

## 18. Reconciliation: stored against computed

On the committed data (gleif 2026-10-25, us-equities 2026-10-24, world-economy's World Bank and IMF data of 2026-10-10, market data on
2026-09-30), the retired stored pages against the computed ones:

| Kind | Pages | Same | Different, and why |
|---|---|---|---|
| `lei-group` | 590 groups + ALL | members 589, countries 590, late renewals 589, levels 571; ALL: groups 590, entities 9,937, countries 133, late 2,288 | **Levels, 19 groups:** the stored depth was set in the order members were listed, so a member listed before its parent got its parent's depth as 0 (`VGRQXHF3J8VDLUA7XE92`: stored 2, computed 3); the computed depth walks the parent chain. **Members and late, 1 group:** BorgWarner Propulsion Systems (`254900XW4597ZTRCMN82`) was in two groups' fetch lists; its record names one group, so `549300DSFX2IE88NSX47` counts 36 members (stored 37) and 3 late (stored 4). **Members in GLEIF:** equal on every group (`GLEIF_GROUP_SIZE` is the figure the stored page held); 86 groups capped (stored 85: the group above now holds 36 of the 37 GLEIF lists) |
| `lei-country` | 133 countries + ALL | every count on every country | the stored page listed the first 400 entities of the six largest countries; the computed one lists all |
| `sic-sector` | 181 codes + ALL | companies, revenue and net income on every code (USD reporters); ALL 503 companies, 17,453,625,180,000 revenue, 2,239,293,329,000 net income | none |
| `country-group` | 12 groups | members on every group | the stored figures were the **World Bank's own aggregates** for 2025, which estimate members that have not reported; the computed ones add up the members, each at its latest year. GDP within 3% (ECG-EAS 32,711 against 33,657: three members' latest GDP is from 2022), population within 2.1%, growth within 0.32 points (ECG-MEA 3.06 against 2.75), inflation as the median within 0.5 points (ECG-LCN 2.58 against 2.08). The four income groups had no stored figures; they have computed ones now |
| `correlation-matrix` | 3 sets | the factors | the stored pairs were **synthetic**: drawn at random between 0.15 and 0.78 and labelled *2 years, Ledoit-Wolf*, with no price history behind them. Computed from the lake's 29 daily returns to 2026-09-30, Pearson: CORR-EU-EQ average 0.096 (stored 0.385), CORR-G10FX −0.012 (0.512), CORR-US-EQ 0.092 (0.406) |
| USEQ ratios | 503 companies | net margin on all 503 and every year of the financials; revenue growth on 500 | **3 companies** whose previous fiscal year is missing from the financials (SNA: 2023, 2024, 2026): the stored growth compared with the last year reported (two years earlier); the computed growth is against the previous fiscal year only, and empty when it is missing |
| PXH analytics | live only | nothing was stored | new |
| FACT changes | 3,480 facts, 34,160 yearly rows | every change, on every row and every strip | none |
| IDX totals | 6 indices and lists | revenue, net income and divisions on all 6 (USD reporters, as stored) | the divisions are listed alphabetically, not largest first |
| `equity` `BETA` | 10 equities | none | the stored betas were **set by hand** (`tools/packgen/banking/data_names.py`), with no price series behind them; the demo's price walks are independent, so the computed betas over the 29 returns to 2026-09-30 are near zero (EQ-NVTK −0.07 against NDX, stored 1.28; EQ-GRNT 0.73 against UKX, stored 1.05; EQ-SLRS 0.32, stored 0.52). EQ-CSCA and EQ-LUMN name no index: no beta (stored 1.46 and 1.19) |

---

# Part III · How they are computed

## 19. The derived connector

`LEIG`, `LEIC`, `SIC` and `ECG` use the lightest mechanism Drishti has for a computed grouping: the **derived connector** (plugin
`derived`), the one the trading pack's `CTRD`, `PTRD`, `LTRD` and `ITRD` pages use. It reads every document of a kind through the router
(as of the business date asked), groups them by an expression and builds one page per group, once per date, shared by every reader.
Release 2.9.2 adds six settings to it, all generic:

```yaml
# config/packs/world-economy/pack.yaml (written by tools/packgen/economics/make.py)
macro-groupings:
  plugin: derived
  kinds: [country-group]
  settings:
    country-group:
      from: economy
      group-by: $.REGION_GROUP
      also-group-by: { income: $.INCOME_GROUP, world: "'ECG-WLD'" }   # an economy is in three groups
      id-field: GROUP_ID
      search-fields: NAME                                  # the type-ahead finds a group by its name
      members: MEMBER_ECONOMIES
      max-members: 100000                                  # no cap
      rows-field: ROWS
      fields:
        GDP_USD_BN: sum $.GDP_USD_BN
        INFLATION: median $.INFLATION                      # new aggregate: median
        GROWTH_WEIGHTED: 'sum $.GROWTH == null || $.GDP_USD_BN == null ? null : $.GDP_USD_BN * $.GROWTH'
        GROWTH_WEIGHT: 'sum $.GROWTH == null || $.GDP_USD_BN == null ? null : $.GDP_USD_BN'
      rows: { ECONOMY_ID: $.ECONOMY_ID, ECONOMY: $.NAME, GDP_USD_BN: $.GDP_USD_BN, GDP_PATH: $.GDP_PATH }
      then:                                                # over the page once its aggregates and rows are in
        GROWTH: '$.GROWTH_WEIGHT > 0 ? $.GROWTH_WEIGHTED / $.GROWTH_WEIGHT : null'
        GDP_PATH: group(flatten($.ROWS[*].GDP_PATH), 'YEAR', 'GDP')
        TOP_ECONOMIES: $.ROWS
      sort: { ROWS: GDP_USD_BN desc, TOP_ECONOMIES: GDP_USD_BN desc 10 }
```

| Setting | What it does |
|---|---|
| `also-group-by` | further group keys a member joins (a map of name → expression); a member is read once and counted in each group |
| `unlisted` | keys whose page keeps no member ids, rows or tree once its `then` fields are worked out (`ALL`: a page over every member) |
| `median` | a new aggregate beside `count`, `sum`, `avg`, `min`, `max`, `distinct` and `first` |
| `then` | fields worked out over the page (its aggregates, its rows, `$.id`) with Rachana-EL: ratios, weighted averages, picks, `group(...)` counts. Each reads the page as the aggregates and rows left it, not another `then` field; a field that fails is empty |
| `sort` | a list field in order: `COLUMN [asc|desc] [N]`; rows without the column go last |
| `tree` | `{ id: LEI, parent: PARENT }`: each row's `LEVEL` (the key 0, a parent outside the group 1 below the key), the nested `TREE` (`CHILDREN`) and the deepest `LEVELS` |
| `search-fields` | page fields a search matches besides the id; the first labels the hit |

The order on a page: the aggregates, the rows, the tree, the `then` fields, the sorts, then `unlisted`. Pages live in the heap by default
(`store: disk` keeps them on disk for very large kinds, as the trading pages do). Across Drishti*net nodes a group is gathered from every
node's share ([DRISHTI_NET.md](../architecture/DRISHTI_NET.md)); a median cannot be combined from parts, so a group whose members are split
over nodes without every member listed shows no median.

The code: `drishti-engine/.../source/derived/DerivedKind.java` (grouping, aggregates), `DerivedPage.java` (`then`, `sort`, `tree`),
`DerivedSourcePlugin.java` (reading, caching, search), `GroupShares.java` (Drishti*net).

## 20. The correlation engine

`CORR` is not a grouping: it needs statistics over aligned time series, which a derived page cannot do. It is a **reactive connector**
with a Java engine in the server's own process (the pattern of VaR's `var-result`, [VAR_GUIDE.md Part III](VAR_GUIDE.md#24-where-the-engine-lives)):

```yaml
# config/packs/market-data/pack.yaml (written by tools/packgen/banking/make_packs.py from corr_kinds.py)
correlation-engine:
  plugin: reactive
  kinds:
    correlation-result:
      compute: correlation
      params: { window: { type: int, default: 0, min: 0, max: 5000 }, estimator: { type: enum, options: [pearson, ledoit-wolf], default: pearson } }
  reads:
    - { kind: correlation-matrix, as: set, id: "{id}" }
    - { kind: equity, as: equities, ids: "{inputs.set.EQUITY_IDS}", limit: 200 }
    - { kind: fx-spot, as: fx, ids: "{inputs.set.FX_IDS}", limit: 200 }
  engine: { type: java, class: com.ash.drishti.compute.reactive.analytics.CorrelationEngine }
  triggers: [{ on: data-version, kinds: [correlation-matrix, equity, fx-spot], affects: all }]
```

Every input is read **as the person asking** (their entitlements and masks apply before a number reaches the engine); an answer is cached
per set, parameters, date and view signature, and recomputed when the inputs load. No compute pool is needed: the engine runs in the
server even with `DRISHTI_COMPUTE_ENABLED=false`. The code is `drishti-compute/.../reactive/analytics/CorrelationEngine.java`; the
`correlation-matrix` Sutra is hand-written (`tools/packgen/banking/make_sutras.py`, `HAND_WRITTEN`).

## 21. The price analytics

PXH's prices are read live, never stored; the analytics are worked out in the same step, by the `yfinance` plugin
(`plugins/drishti-plugin-yfinance/.../PriceAnalytics.java`), from the history it has just parsed, and the benchmark's history read
through the same cache. The connector `us-equities-prices` is **off as shipped** and **internal use only**: switching it on sends each
ticker opened to Yahoo Finance, and the prices and everything computed from them may not be redistributed.

## 22. Where the inputs come from

| Computed | Inputs | External data | Refresh script |
|---|---|---|---|
| LEIG, LEIC | `lei-entity`: `LEI_GROUP`, `DIRECT_PARENT`, `LEI_COUNTRY`, `RENEWAL_LATE`, `REGISTRATION_STATUS`, `GLEIF_GROUP_SIZE`, ... | GLEIF golden copy level 1 and 2, LEI API, mapping files (CC0) | `tools/publicdata/gleif.py refresh` (`gleif.sh`, `publicdata-gleif.ps1`) → `tools/packgen/gleif/make.py` |
| SIC, USEQ ratios | `us-company`: `SIC`, `SIC_DESCRIPTION`, `CURRENCY`, `REVENUE`, `NET_INCOME`, `FISCAL_YEAR`, `FINANCIALS[]` | SEC EDGAR submissions and XBRL company facts (public domain) | `tools/publicdata/us_equities.py refresh` (`us-equities.sh`) → `tools/packgen/us_equities/make.py` |
| ECG | `economy`: `REGION_GROUP`, `INCOME_GROUP`, `GDP_USD_BN`, `POPULATION_M`, `GROWTH`, `INFLATION`, `YEAR`, `GDP_PATH` | World Bank WDI (CC BY 4.0), IMF WEO (internal demonstrations only), CIA Factbook | `tools/publicdata/world_economy.py` (`world-economy.sh`) → `tools/realdata/realdata.py` → `tools/packgen/world/make.py` |
| CORR | `correlation-matrix` (the factors), `equity` and `fx-spot` `HISTORY[]` | none: the market-data pack's synthetic data | `tools/packgen/banking/make_data.py`, `tools/testdata/make_testdata.py` |
| PXH analytics | the price history read live | Yahoo Finance's public chart endpoint (internal use only) | none: live, off as shipped |
| FACT changes, IDX totals | `financial-fact` `HISTORY[]`, `index-membership` `ROWS[]` | SEC EDGAR, Wikidata | `tools/publicdata/us_equities.py` |
| EQB | `equity` and `equity-index` `HISTORY[]` | none: synthetic | `tools/packgen/banking/make_data.py`, `tools/testdata/make_testdata.py` |

The refresh scripts **no longer write any computed figure**: the gleif generator writes no `lei-group` or `lei-country` snapshot and an
ultimate parent carries `GLEIF_GROUP_SIZE` (its group's size in GLEIF, an input the page reads); the us-equities generator writes no
`sic-sector` snapshot and no ratio; `tools/realdata/build_docs.py` builds no country group; the market-data generator writes the
correlation sets' factors only. `tools/testdata/publicsnap.py` lists the kinds it writes, and `COMPUTED` the ones it never writes again.

## 23. The retirement guard

`tools/contracts.policy.yaml` records the decision, and `tools/test_contracts.py` fails the build if it is undone:

- `computed-in-place:` `lei-group`, `lei-country`, `sic-sector`, `country-group` (2.9.2): each is routed to a derived connector, no Delta
  connector serves or lays it out, no sample, test data or test-data folder holds it, no public-data generator writes it, and its contract
  is class `computed` over plugin `derived`;
- `removed:` `us-company` `NET_MARGIN`, `REVENUE_GROWTH`, `FINANCIALS[].NET_MARGIN`; `index-membership` `ROWS[].NET_MARGIN`,
  `SECTORS`, `REVENUE`, `NET_INCOME`, `BY_SECTOR`; `financial-fact` `CHANGE_PCT`, `HISTORY[].CHANGE_PCT`; `correlation-matrix` `AVERAGE`,
  `PAIRS`, `WINDOW`, `ESTIMATOR`; `equity` `BETA`: no record or contract carries them again;
- `engine-columns: correlation` (`RHO`, `SHRINKAGE`, `MIN_RHO`, `MAX_RHO`, `FACTOR_STATS`): no fundamental or input kind carries one;
- `classes: computed` lists the six computed kinds; the CORR and EQB tests check the engine classes exist and every set names its factors.

## 24. Settings and limits

| Setting | Default | Where |
|---|---|---|
| `DRISHTI_LAKE_ENABLED` | `true` | switches the derived connectors and the correlation engine with the lake they read |
| a derived connector's `refresh` | `60s` | how often a date's pages are rebuilt when the data's version cannot be asked |
| `max-scan` | `50000` | the most documents a derived connector reads of a kind (9,937 entities, 503 companies, 213 economies) |
| `DRISHTI_US_EQUITIES_PRICES_ENABLED` | `false` | PXH and its analytics |
| `DRISHTI_US_EQUITIES_BENCHMARK` | `SPY` | the symbol PXH's beta is measured against |
| `correlation-engine` `limits` | timeout 30 s, wait 5 s, 4 in flight a user | as every reactive connector ([REACTIVE_CONNECTORS.md](../architecture/REACTIVE_CONNECTORS.md)) |

Memory: a derived page set holds each page's aggregates and rows; the gleif pages of the committed data (590 groups, 133 countries) are a
few megabytes, built in about a second from the 9,937 entities.
