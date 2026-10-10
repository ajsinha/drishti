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
# The public feeds connector: SOFR, €STR, ECB FX, the Treasury curve and FRED

The `feed` plugin (module `plugins/drishti-plugin-feeds`) brings five public data sets into Drishti as ordinary
entities: the NY Fed's SOFR, the ECB's €STR, the ECB's euro reference FX rates, the US Treasury daily par yield curve,
and any daily FRED series. Each feed is one connector, fetched over HTTPS at start and every hour, held in memory with
its recent history, and served for Live and for picked business dates. This document explains what each feed fetches,
what it turns the response into, how reads are answered, what happens when the internet or the publisher is
unavailable, how to run the feeds behind a proxy or without internet, and every setting.

For a first setup see [the walk-through at the end of this document](#walk-through-step-by-step); the short
reference is [the configuration examples at the end of this document](#configuration-by-example) and
[CONFIGURATION.md](../admin/CONFIGURATION.md#feed--public-data-feeds). For long history next to the feeds see
[DELTA_CONNECTOR.md](DELTA_CONNECTOR.md).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [The five feeds](#2-the-five-feeds)
3. [What each feed fetches and what it becomes](#3-what-each-feed-fetches-and-what-it-becomes)
4. [Configuration](#4-configuration)
5. [How the connector reads](#5-how-the-connector-reads)
6. [Dates and history](#6-dates-and-history)
7. [Scale, limits, and history from a lake](#7-scale-limits-and-history-from-a-lake)
8. [Failure and recovery](#8-failure-and-recovery)
9. [Running without internet: mirrors](#9-running-without-internet-mirrors)
10. [Security](#10-security)
11. [Diagnosing](#11-diagnosing)
12. [Settings](#12-settings)
13. [Checklist for production](#13-checklist-for-production)

---

## 1. When to use it

| Use it for | Prefer another store for |
|---|---|
| real overnight rates (SOFR, €STR) beside a pack's own fixings, for comparison or a demo | an official, audited record of fixings (load them into a dated store you control) |
| ECB reference FX rates for information (crosses for ten pairs) | traded FX prices: the ECB rate is a once-a-day reference, and the connector sets `bid` = `ask` = `mid` |
| the US Treasury par curve as an `ir-curve` | a bootstrapped discount curve: the connector's `zeroRate` is the par yield, not a zero rate (section 3.4) |
| any daily FRED series (`DGS10`, `DFF`, `SOFR`, …) | history beyond the last 60 observations, 90 days of FX or about two months of Treasury curve: archive it ([section 7](#7-scale-limits-and-history-from-a-lake)) |
| a server with outbound HTTPS, or a mirror on disk | a server that must make no outbound request and has no mirror process |

The feeds are small (one to ten entities each) and cost almost nothing to serve: every read is answered from memory.
They are **off by default**: each connector of the `market-data` pack starts only when its variable is `true`.

## 2. The five feeds

| `feed` | Connector (market-data pack) | Switch | Kind | Ids |
|---|---|---|---|---|
| `nyfed-sofr` | `nyfed-sofr-feed` | `DRISHTI_FEED_NYFED_SOFR=true` | `rate-fixing` | `FIX-SOFR-NYFED` |
| `ecb-estr` | `ecb-estr-feed` | `DRISHTI_FEED_ECB_ESTR=true` | `rate-fixing` | `FIX-ESTR-ECB` |
| `ecb-fx` | `ecb-fx-feed` | `DRISHTI_FEED_ECB_FX=true` | `fx-spot` | `FX-EURUSD-ECB`, `FX-EURGBP-ECB`, `FX-EURJPY-ECB`, `FX-EURCHF-ECB`, `FX-GBPUSD-ECB`, `FX-USDJPY-ECB`, `FX-USDCHF-ECB`, `FX-AUDUSD-ECB`, `FX-USDCAD-ECB`, `FX-USDCNH-ECB` |
| `us-treasury` | `us-treasury-feed` | `DRISHTI_FEED_US_TREASURY=true` | `ir-curve` | `CRV-USD-UST` |
| `fred` | `fred-feed` | `DRISHTI_FEED_FRED=true` and `FRED_API_KEY` | `rate-fixing` | `FIX-FRED-<series>`, series from `DRISHTI_FRED_SERIES` (default `DGS10,DFF`) |

Every id names its publisher, so a feed never collides with the pack's samples (`FIX-SOFR`, `CRV-USD-OIS`) or with
your own data. The documents have the same shape as the samples of the same kind, so the market-data pack's Sutras
lay them out unchanged. Users type `FIX FIX-SOFR-NYFED <GO>`, `FX FX-EURUSD-ECB <GO>`, `CRV CRV-USD-UST <GO>`.

## 3. What each feed fetches and what it becomes

Each feed is a small adapter (`Feeds.java`): the URLs to fetch, a parser from the responses to dated observations
per entity, and a function that builds the entity's document for a business date from the observations on or before
it. The examples below are computed from the recorded responses the tests use
(`plugins/drishti-plugin-feeds/src/test/resources/recorded/`).

### 3.1 `nyfed-sofr`: the Secured Overnight Financing Rate

**Fetched:** `https://markets.newyorkfed.org/api/rates/secured/sofr/last/60.json`, one request.

**Response read** (JSON; the fields the connector reads are `effectiveDate`, `percentRate`, `volumeInBillions`,
`percentPercentile1` and `percentPercentile99`):

```json
{ "refRates": [
  { "effectiveDate": "2026-09-29", "type": "SOFR", "percentRate": 3.88, "percentPercentile1": 3.81,
    "percentPercentile25": 3.86, "percentPercentile75": 3.93, "percentPercentile99": 3.97,
    "volumeInBillions": 2967, "revisionIndicator": "" },
  { "effectiveDate": "2026-09-28", "type": "SOFR", "percentRate": 3.90, … , "volumeInBillions": 2964 },
  … ] }
```

**Becomes** one `rate-fixing`, `FIX-SOFR-NYFED`, with one observation per `effectiveDate` (60 at most). The 1st and
99th percentiles are kept with each observation but not shown in the document.

### 3.2 `ecb-estr`: the euro short-term rate

**Fetched:** `https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=60&format=csvdata`,
one request.

**Response read** (CSV with a header row; the connector reads the `TIME_PERIOD` and `OBS_VALUE` columns, found by
name, and skips rows whose value is blank):

```text
KEY,FREQ,BENCHMARK_ITEM,DATA_TYPE_EST,TIME_PERIOD,OBS_VALUE,OBS_STATUS,CONF_STATUS,…,TITLE,TITLE_COMPL,UNIT_INDEX_BASE,UNIT_MEASURE,UNIT_MULT
EST.B.EU000A2X2A25.WT,B,EU000A2X2A25,WT,2026-09-02,2.188,A,F,…,Euro short-term rate,"Euro short-term rate, Volume-weighted trimmed mean rate - …",,PC,0
```

**Becomes** one `rate-fixing`, `FIX-ESTR-ECB` (60 observations at most, no volume).

### 3.3 `ecb-fx`: the euro foreign exchange reference rates

**Fetched:** `https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml`, one request: the last 90 calendar
days, about 64 publication days.

**Response read** (XML; one `Cube time="…"` per day, one `Cube currency="…" rate="…"` per currency, rates per euro):

```xml
<Cube time="2026-09-29"><Cube currency="USD" rate="1.1355"/><Cube currency="JPY" rate="178.41"/>
  <Cube currency="GBP" rate="0.857…"/> … <Cube currency="CNY" rate="…"/></Cube>
```

**Becomes** ten `fx-spot` entities. Each day's rates are put per euro (`EUR` = 1); `CNY` stands in for `CNH`; each
pair's mid is `rate(quote) / rate(base)`, so `USDJPY` = JPY per euro ÷ USD per euro. A pair is produced on a day only
when both of its currencies were published that day. On the recorded file (latest day 2026-09-30) the mids are:

| Pair | Mid | Pair | Mid |
|---|---|---|---|
| `FX-EURUSD-ECB` | 1.1355 | `FX-USDJPY-ECB` | 156.99692 |
| `FX-EURGBP-ECB` | 0.85463 | `FX-USDCHF-ECB` | 0.8347 |
| `FX-EURJPY-ECB` | 178.27 | `FX-AUDUSD-ECB` | 0.69675 |
| `FX-EURCHF-ECB` | 0.9478 | `FX-USDCAD-ECB` | 1.41832 |
| `FX-GBPUSD-ECB` | 1.32865 | `FX-USDCNH-ECB` | 6.70454 |

### 3.4 `us-treasury`: the daily par yield curve

**Fetched:** two requests, the previous calendar month and the current one, the month taken from today's date in
New York:

```text
https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value_month=202608
https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value_month=202609
```

**Response read** (an Atom feed; one `m:properties` per day; the connector reads `NEW_DATE` and the twelve tenors
`BC_1MONTH`, `BC_2MONTH`, `BC_3MONTH`, `BC_6MONTH`, `BC_1YEAR`, `BC_2YEAR`, `BC_3YEAR`, `BC_5YEAR`, `BC_7YEAR`,
`BC_10YEAR`, `BC_20YEAR`, `BC_30YEAR`; others such as `BC_1_5MONTH` and `BC_4MONTH` are ignored, and a blank tenor
is skipped):

```xml
<m:properties>
  <d:NEW_DATE m:type="Edm.DateTime">2026-09-30T00:00:00</d:NEW_DATE>
  <d:BC_1MONTH m:type="Edm.Double">4.02</d:BC_1MONTH>
  <d:BC_2YEAR m:type="Edm.Double">4.88</d:BC_2YEAR>
  <d:BC_10YEAR m:type="Edm.Double">5.29</d:BC_10YEAR>
  <d:BC_30YEAR m:type="Edm.Double">5.64</d:BC_30YEAR>
  …
</m:properties>
```

**Becomes** one `ir-curve`, `CRV-USD-UST`, with one observation per day: between one and two months of curves
(21 days on the recorded September file). For each tenor present the document has a point:

| Field | Value |
|---|---|
| `tenor` | `1M` … `30Y` |
| `maturity` | the curve date plus the tenor in years × 365 days, rounded |
| `instrument` | `Bill (par yield)` below one year, else `Note/bond (par yield)` |
| `quote` | the par yield as a fraction (5.29 → 0.0529) |
| `zeroRate` | the par yield in percent, as published (not a bootstrapped zero rate) |
| `df` | `(1 + par/200)^(-2 × years)`, semi-annual bond basis, 6 decimals |

### 3.5 `fred`: any FRED series

**Fetched:** one request per series in `series` (default `DGS10,DFF`):

```text
https://api.stlouisfed.org/fred/series/observations?series_id=DGS10&api_key=<api-key>&file_type=json&sort_order=desc&limit=60
```

**Response read** (JSON; `date` and `value` of each observation; a value of `"."`, FRED's mark for a missing day, is
skipped):

```json
{"units":"lin","file_type":"json","sort_order":"desc","count":6,"limit":6,"observations":[
 {"realtime_start":"2026-09-30","realtime_end":"2026-09-30","date":"2026-09-29","value":"4.09"},
 {"realtime_start":"2026-09-30","realtime_end":"2026-09-30","date":"2026-09-24","value":"."}, …]}
```

**Becomes** one `rate-fixing` per series, `FIX-FRED-DGS10`, `FIX-FRED-DFF`, …, named `FRED series DGS10`, tenor
`Daily`, currency `USD`. Every series is treated as a rate in percent, whatever FRED's units are.

### 3.6 The documents

A `rate-fixing` (SOFR, €STR, FRED) for a business date holds up to the **20** most recent observations on or before
it, newest first; `rate` is the percent rate as a fraction, rounded to six decimals; `volumeBn` is present for SOFR
only. SOFR on the recorded file, Live:

```json
{ "indexId": "FIX-SOFR-NYFED", "name": "Secured Overnight Financing Rate (NY Fed)", "latest": 0.0388,
  "administrator": "Federal Reserve Bank of New York", "tenor": "Overnight", "currency": "USD",
  "fixings": [ { "date": "2026-09-29", "rate": 0.0388, "ratePct": 3.88, "volumeBn": 2967.0 },
               { "date": "2026-09-28", "rate": 0.039,  "ratePct": 3.9,  "volumeBn": 2964.0 },
               { "date": "2026-09-25", "rate": 0.039,  "ratePct": 3.9,  "volumeBn": 2914.0 }, … ] }
```

€STR has `name` `Euro short-term rate (ECB)`, `administrator` `European Central Bank`, `currency` `EUR`; FRED has
`administrator` `Federal Reserve Bank of St. Louis (FRED)`.

An `fx-spot` holds the latest mid on or before the date, the change from the observation before it, and up to 30
observations of history, oldest first:

```json
{ "pair": "FX-EURGBP-ECB", "pairName": "EUR/GBP", "mid": 0.85463, "bid": 0.85463, "ask": 0.85463,
  "change1d": -0.00297, "spotDate": "2026-09-30",
  "history": [ … { "date": "2026-09-29", "mid": … }, { "date": "2026-09-30", "mid": 0.85463 } ],
  "conventions": { "base": "EUR", "quote": "GBP", "fixing": "ECB reference rate, 14:10 CET",
                   "note": "Reference rate for information; not a traded price" } }
```

`change1d` is `mid / previous mid - 1`, rounded to five decimals; `null` when there is no earlier observation.

An `ir-curve` (the recorded 30 September curve, three of its twelve points):

```json
{ "curveId": "CRV-USD-UST", "currency": "USD", "curveType": "Government (US Treasury par yields)", "index": "UST",
  "tenY": 0.0529, "slope2s10s": 41.0, "asOf": "2026-09-30",
  "points": [ { "tenor": "1Y",  "maturity": "2027-09-30", "instrument": "Note/bond (par yield)", "quote": 0.0454, "zeroRate": 4.54, "df": 0.9561 },
              { "tenor": "10Y", "maturity": "2036-09-27", "instrument": "Note/bond (par yield)", "quote": 0.0529, "zeroRate": 5.29, "df": 0.593259 },
              { "tenor": "30Y", "maturity": "2056-09-22", "instrument": "Note/bond (par yield)", "quote": 0.0564, "zeroRate": 5.64, "df": 0.188514 } ],
  "forwards": [], "source": "U.S. Department of the Treasury, Daily Treasury Par Yield Curve Rates" }
```

`tenY` is the 10-year par yield as a fraction; `slope2s10s` is 10Y minus 2Y in basis points, to 0.1 bp; either is
`null` when a tenor is missing. Numbers are written as Java doubles, so a value such as `0.0402` may show as
`0.04019999999999999`.

## 4. Configuration

### 4.1 The pack form, as shipped

`packs/market-data/pack.yaml` suggests the five connectors as templates (two shown; the other three differ only in name, switch,
`kinds` and `feed`). At the first start the server writes each one to `config/connectors/<name>.yaml` (`nyfed-sofr-feed.yaml`, `fred-feed.yaml`, …), and those files are then
the site's; the file form is in [As a connector file](#as-a-connector-file):

```yaml
connectors:
  nyfed-sofr-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_NYFED_SOFR:false}
    kinds:
    - rate-fixing
    settings:
      feed: nyfed-sofr
      user-agent: ${drishti.branding.product:feed} public data feed connector
      stale-after: 4d
      refresh-minutes: 60
  fred-feed:
    plugin: feed
    enabled: ${DRISHTI_FEED_FRED:false}
    kinds:
    - rate-fixing
    settings:
      feed: fred
      user-agent: ${drishti.branding.product:feed} public data feed connector
      stale-after: 4d
      refresh-minutes: 60
      api-key: ${FRED_API_KEY:}
      series: ${DRISHTI_FRED_SERIES:DGS10,DFF}
routes:
  ir-curve: market-store
  fx-spot: market-store
  rate-fixing: market-store
  # … every market kind routes to the pack's Delta Lake connector
```

With the default branding the `User-Agent` header is `Drishti public data feed connector`. `stale-after: 4d` covers a
weekend and a holiday (see [section 8](#8-failure-and-recovery)).

Switch feeds on with their variables:

```bash
DRISHTI_PACKS=counterparty-risk,market-risk \
DRISHTI_FEED_NYFED_SOFR=true DRISHTI_FEED_ECB_FX=true DRISHTI_FEED_US_TREASURY=true \
DRISHTI_FEED_FRED=true FRED_API_KEY=… DRISHTI_FRED_SERIES=DGS10,DFF,SOFR \
  java -jar drishti-server/target/drishti-server-*-exec.jar
```

### 4.2 The site form

The site edits the files the server wrote, in **Admin → Connectors** or by hand (a file is the whole definition, so the pack's template is not merged into it;
[CONNECTOR_FILES.md, Precedence](CONNECTOR_FILES.md#precedence)). Switching a feed on, whatever its variable says, and changing its schedule is
`config/connectors/ecb-fx-feed.yaml`:

```yaml
plugin: feed
enabled: true                         # on, whatever DRISHTI_FEED_ECB_FX says
kinds: [fx-spot]
settings:
  feed: ecb-fx
  stale-after: 4d
  refresh-minutes: '30'
  timeout-seconds: '10'
```

`config/connectors/fred-feed.yaml` with three series, written with the key in the environment (the pack's template has an empty default, `${FRED_API_KEY:}`, which a
credential reference may not carry in a file you write):

```yaml
plugin: feed
enabled: true
kinds: [rate-fixing]
settings:
  feed: fred
  api-key: ${FRED_API_KEY}
  series: DGS2,DGS10,DGS30            # FIX-FRED-DGS2, FIX-FRED-DGS10, FIX-FRED-DGS30
  refresh-minutes: '60'
  stale-after: 4d
```

A feed off for this site is `enabled: false` in its file (`us-treasury-feed.yaml`).

A feed without the market-data pack is a named connector of your own, `config/connectors/sofr-public.yaml`; it needs `plugin: feed` and `settings.feed`,
and its kinds need a pack mnemonic before a user can type them:

```yaml
plugin: feed
kinds: [rate-fixing]
settings: { feed: nyfed-sofr, refresh-minutes: '60', stale-after: 4d }
```

Two connectors with the same `feed` are independent: each fetches on its own schedule.

### 4.3 Running as itself

The plugin is on the server's class path. Without the market-data pack, and not listed under
`drishti.sources.plugins`, it starts as itself with no `feed` setting, throws `PluginNotConfigured`, and the log says:

```text
source plugin feed is installed but not configured (feed needs settings.feed (nyfed-sofr, ecb-estr, ecb-fx, us-treasury, fred)); it stays idle
```

This is not a failure and does not appear in health. An unknown `feed` value is a failure: the connector is listed
under `failedToStart` with `unknown feed 'x' (nyfed-sofr, ecb-estr, ecb-fx, us-treasury, fred)`.

## 5. How the connector reads

### 5.1 The refresh

At start the connector fetches its URLs once, **inside `start`**, before it is registered; it then fetches again every
`refresh-minutes` (60), measured from the end of the previous fetch (a fixed delay, on the server's shared scheduler).
A refresh:

1. fetches every URL in turn (a `file:` URL is read from disk), with a 10-second connect timeout and a
   `timeout-seconds` (20) request timeout, following redirects except from HTTPS to HTTP;
2. parses all responses; any exception stops the refresh before anything is replaced;
3. if the responses yield no series at all, stops there too: nothing is replaced, and health says
   `DOWN: the feed returned no data (serving the last data)` (or `DOWN: the feed returned no data` when nothing was
   ever fetched);
4. otherwise replaces each parsed entity's observations, and replaces the type-ahead index with the entities just
   parsed;
5. sets `fetchedAt`, health `UP`, and the time of the last update.

Connectors start in parallel, but the server waits for every `start`, so an unreachable feed can lengthen server start
by up to its timeouts (two requests for `us-treasury`, one per series for `fred`).

### 5.2 Every read path and its cost

| Read | How it is answered | Cost |
|---|---|---|
| one entity, Live | the entity's observations from memory, the latest one; the document is built and parsed into Drishti's tree | microseconds; no request to the publisher |
| one entity, a picked date | the newest observation on or before the date (section 6) | the same |
| type-ahead | the in-memory `HitIndex` of the last refresh: ids by prefix, then substring matches of id and subtitle `<kind> · <connector> (public feed)` | microseconds |
| searches, pick lists | from the same documents; at most ten entities per feed | negligible |
| reverse lookups | not supported (`reverseLookup: false`) | — |
| live push | none (`live: false`): a view is a static read; reopening it after a refresh shows the new data | — |

A read never waits for the network and never fails: an entity the connector does not hold, or a date before its
first observation, answers *not held* and the router asks the next connector. The connector therefore never causes
`DRS-1003` or `DRS-1004`.

Every document carries `Provenance(source = connector name, generation = fetchedAt in epoch milliseconds,
fetchedAt, live = false, businessDate = the observation date used)`:

```json
{"source":"nyfed-sofr-feed","generation":1790818508749,"fetchedAt":"2026-10-01T01:35:08.749439520Z","live":false,"businessDate":"2026-09-29"}
```

### 5.3 Where a read goes

The pack routes `rate-fixing`, `fx-spot` and `ir-curve` to `market-store` (Delta Lake). For `FIX-SOFR-NYFED` the
router asks the lake first (route), the demo samples (default route; on Live, being live, they are asked first), then
every other connector serving the kind, which includes the feeds. The lake and the samples do not hold the feed ids,
answer *not held*, and the feed answers. Three feed connectors serve `rate-fixing`; each answers *not held* for the
others' ids.

## 6. Dates and history

The connector is **dated** (`dated: true`). For a read on a date it takes the newest observation on or before that
date; the document is built from the observations up to it, and `businessDate` is that observation's date, which may
be earlier than the date asked:

| Feed | Published | Live shows | History held |
|---|---|---|---|
| `nyfed-sofr` | each business day's rate, the next business day | the previous business day's fixing | the last 60 fixings (about three months) |
| `ecb-estr` | the next TARGET day | the previous day's rate | the last 60 observations |
| `ecb-fx` | each TARGET day | that day's reference rates once published | the 90-day file, about 64 days |
| `us-treasury` | each US business day | that day's curve once published | the previous and current calendar month: 1–2 months |
| `fred` | as each series is updated | the series' latest value | the last 60 observations per series |

Points to know:

- **History is the last fetch's window, not an accumulation.** Each refresh replaces an entity's observations with
  what the publisher returned. Observations that fell out of the window (the 61st SOFR fixing back) are gone, even
  though the connector saw them earlier.
- **A date before the window is not held**, so the next connector answers; a lake with older history answers it
  ([section 7](#7-scale-limits-and-history-from-a-lake)).
- **There is no `lookback-days` limit.** If a feed stopped updating, a picked date long after its last observation
  still answers with that last observation, with its `businessDate` showing how old it is.
- Holidays and weekends need nothing: the date simply resolves to the previous observation (the test reads the
  Treasury curve on Sunday 27 September 2026 and gets Friday 25 September).

## 7. Scale, limits, and history from a lake

### 7.1 Size

| Held in memory | Size |
|---|---|
| `nyfed-sofr`, `ecb-estr` | one entity, 60 observations |
| `ecb-fx` | ten entities, about 64 observations each |
| `us-treasury` | one entity, 20–45 curves of 12 points |
| `fred` | one entity per series, 60 observations each |

All five feeds together hold a few thousand numbers: no cache setting is needed, nothing is written to disk, and the
million-entities-a-day concerns of the other connectors do not arise. The limit is the window each publisher returns
(section 6), not memory.

### 7.2 Requests made

| Feed | Requests per refresh | Per day at `refresh-minutes: 60` |
|---|---|---|
| `nyfed-sofr`, `ecb-estr`, `ecb-fx` | 1 | 24 |
| `us-treasury` | 2 | 48 |
| `fred` | one per series | 24 per series |

Add one refresh per server start and per purge. The connector has no rate limiting, retry or backoff of its own: a
failed refresh waits for the next scheduled one. FRED limits requests per API key; one key shared by many servers
or many series multiplies the count above. Several Drishti servers each fetch independently: use a mirror
([section 9](#9-running-without-internet-mirrors)) to make one request for all of them.

### 7.3 Longer history from a lake

To keep more than the window (years of SOFR fixings, every Treasury curve), archive the feed's documents into a dated
store under the **same ids**, for example a daily job that reads
`GET /api/v1/entities/rate-fixing/FIX-SOFR-NYFED/raw?asOf=<date>` and writes the document as a row for that business
date into the market domain of the lake (see [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md) for the rows and loaders).

The router then asks the connectors in order and takes the first that holds the date. With the pack's route
(`rate-fixing: market-store`) the **lake is asked first**, so once it holds the id it answers every date within its
`lookback-days` (10) of its newest row, Live included, and the feed answers only dates the lake does not cover.
Either keep the archive current (load it every business day), or route the kind to the feed so that the feed answers
inside its window and the lake answers before it:

```yaml
drishti:
  sources:
    routes:
      rate-fixing: nyfed-sofr-feed    # the feed first; dates before its window fall to market-store (dated)
```

A route is per kind: with this route the other `rate-fixing` ids (the samples, `FIX-ESTR-ECB`) are still found, one
*not held* later.

## 8. Failure and recovery

### 8.1 Health

`health()` returns exactly one of:

| Health | When |
|---|---|
| `DOWN: not fetched yet` | before the first refresh completes; since that refresh runs inside `start`, a running connector is not normally seen in this state |
| `UP` | the last refresh parsed at least one entity |
| `DOWN: the feed returned no data (serving the last data)` | the last refresh parsed no entity at all, and the data of an earlier refresh is still served; in practice only `ecb-fx`, when the file has no day with a pair's two currencies |
| `DOWN: the feed returned no data` | the same, but no refresh ever brought data, so nothing is served |
| `DOWN: <Exception>: <message>` | the last refresh failed; `<Exception>` is the Java class's simple name |

Typical exception texts:

| Text | Meaning |
|---|---|
| `DOWN: IllegalStateException: HTTP 503 from markets.newyorkfed.org` | the publisher answered with a status other than 2xx; only the host is named, never the full URL |
| `DOWN: IllegalStateException: HTTP 400 from api.stlouisfed.org` | FRED rejected the request: no or wrong `api-key`, or an unknown series |
| `DOWN: HttpTimeoutException: request timed out` | no response within `timeout-seconds` |
| `DOWN: HttpConnectTimeoutException: …`, `DOWN: ConnectException: …` | no connection within 10 s, or refused; the message may be `null` |
| `DOWN: NoSuchFileException: /srv/mirror/sofr-last-60.json` | a `file:` mirror is missing |
| `DOWN: JsonParseException: …`, `DOWN: SAXParseException: …`, `DOWN: IndexOutOfBoundsException: …` | the body was not in the expected format (a proxy's HTML page, an empty or changed file) |

### 8.2 What is kept

- **A failed refresh keeps the last good data.** Reads, type-ahead and searches go on answering from the previous
  fetch; only health changes. The next scheduled refresh tries again, `refresh-minutes` later.
- **A server started without internet** starts normally, with health `DOWN: <Exception>: …`, and holds nothing:
  reads answer *not held* (`DRS-1001` if nothing else holds the id). Data arrives with the first refresh that
  succeeds, up to `refresh-minutes` later; a purge (below) fetches at once.
- **A response that parses but holds no rows** (a SOFR, €STR, FRED or Treasury answer with no observations, an
  `ecb-fx` file with no usable day) keeps the last good data, reads and type-ahead alike, and health says
  `DOWN: the feed returned no data (serving the last data)`, or `DOWN: the feed returned no data` when nothing was ever
  fetched. A series without a single observation is ignored; the others of the same answer are taken.
- **A purge** (Admin → Caches, or `POST /api/v1/admin/caches/<connector>/purge`) clears the data and refetches at
  once, on the caller's request. If that fetch fails, the data is gone until the next good refresh: do not purge a
  feed while its publisher is unreachable.

### 8.3 Staleness

`stale-after` (an engine setting; the pack sets `4d`) marks the connector stale when no refresh has brought data for
that long: Admin → Health shows it in amber, the overall status becomes `DEGRADED`, and views carry a banner. The
time is that of the last refresh that parsed something, **not** of the newest observation: a publisher that keeps
returning the same old data is not reported stale. To watch for that, compare `businessDate` in provenance with the
expected date.

There is nothing to reconnect: each refresh opens new HTTP connections (the client is shared and reuses them), and
recovery needs no restart ([CONNECTOR_DEVELOPER_GUIDE.md, Reconnecting and failures](CONNECTOR_DEVELOPER_GUIDE.md#reconnecting-and-failures)).

## 9. Running without internet: mirrors

`url` replaces the public URL. A `file:` URL is read straight from disk (as UTF-8); any `http:` or `https:` URL is
fetched like the public one, so an internal web server works as well. The body must be exactly the publisher's
format. For `us-treasury` and `fred`, `url` is a comma list: one URL per month for the Treasury (oldest first; the
connector no longer computes the months, so the mirror job must), one per series for FRED, in the order of `series`
(the ids come from `series`, position by position).

A mirror job (cron, every hour, on a host with internet; write to a temporary name and rename so the connector never
reads half a file):

```bash
M=/srv/mirror
curl -fsS https://markets.newyorkfed.org/api/rates/secured/sofr/last/60.json -o $M/.sofr && mv $M/.sofr $M/sofr-last-60.json
curl -fsS 'https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=60&format=csvdata' -o $M/.estr && mv $M/.estr $M/estr.csv
curl -fsS https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml -o $M/.fx && mv $M/.fx $M/ecb-fx-90d.xml
for m in $(date -d '-1 month' +%Y%m) $(date +%Y%m); do
  curl -fsS "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/pages/xml?data=daily_treasury_yield_curve&field_tdr_date_value_month=$m" -o $M/.ust && mv $M/.ust $M/ust-$m.xml
done
for s in DGS10 DFF; do
  curl -fsS "https://api.stlouisfed.org/fred/series/observations?series_id=$s&api_key=$FRED_API_KEY&file_type=json&sort_order=desc&limit=60" -o $M/.f && mv $M/.f $M/fred-$s.json
done
```

```yaml
# config/connectors/nyfed-sofr-feed.yaml on the servers without internet (one file per feed; the other four differ in name and url)
plugin: feed
enabled: true
kinds: [rate-fixing]
settings:
  feed: nyfed-sofr
  url: file:///srv/mirror/sofr-last-60.json
# the other mirrors, in their own files:
#   ecb-estr-feed.yaml     settings.url: file:///srv/mirror/estr.csv
#   ecb-fx-feed.yaml       settings.url: file:///srv/mirror/ecb-fx-90d.xml
#   us-treasury-feed.yaml  settings.url: file:///srv/mirror/ust-202608.xml,file:///srv/mirror/ust-202609.xml
#   fred-feed.yaml         settings.url: file:///srv/mirror/fred-DGS10.json,file:///srv/mirror/fred-DFF.json   and   settings.series: DGS10,DFF
```

The connector re-reads the files every `refresh-minutes`. Because the Treasury URLs name months, change them (or keep
fixed file names such as `ust-previous.xml` and `ust-current.xml`, written by the job) when the month turns. The
tests use the same mechanism with recorded responses (`src/test/resources/recorded/`, described in `RECORDED.md`).

## 10. Security

- **Outbound requests only.** The connector makes HTTPS `GET` requests to the five publishers' hosts (or your
  mirror) and accepts nothing inbound. Allow `markets.newyorkfed.org`, `data-api.ecb.europa.eu`, `www.ecb.europa.eu`,
  `home.treasury.gov` and `api.stlouisfed.org` for the feeds you switch on.
- **The FRED key** comes from `FRED_API_KEY` through the pack's placeholder (`api-key: ${FRED_API_KEY:}`); keep it in
  the environment or a secret store, never in a YAML file. It travels in the request's query string over TLS; health
  and errors name only the host, so the key does not appear in Admin → Health. A proxy that terminates TLS can see
  it in the URL. With an empty key FRED rejects the request and the connector reports the HTTP status.
- **TLS** uses the JVM's default trust store. A proxy that inspects TLS needs its CA in that trust store
  (`-Djavax.net.ssl.trustStore=…`, or imported into the JDK's `cacerts`); this affects every HTTPS client in the
  server.
- **Proxies.** The HTTP client uses the JVM's default proxy selector, which reads the standard system properties
  (`-Dhttps.proxyHost=proxy.bank.example -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts=…`, or
  `-Djava.net.useSystemProxies=true`), not the `HTTPS_PROXY` environment variable. No proxy credentials are
  configured: behind a proxy that requires authentication, use a mirror.
- **Parsing.** XML responses are parsed with DOCTYPE declarations refused and entity references not expanded, so a
  hostile or tampered response cannot read local files (XXE). JSON and CSV are parsed as data only.
- **`file:` URLs** read any file the server's user can read; they come only from configuration, which only the
  operator controls.
- **Entitlements** apply as for any kind: the server redacts what it serves; the connector itself applies none.

## 11. Diagnosing

`GET /api/v1/admin/health` shows each feed connector; its cache figures are the series, the observations and the
time of the last successful fetch:

```json
{"name": "ecb-fx-feed", "health": "UP", "kinds": ["fx-spot"], "live": false, "dated": true, "search": true,
 "cache": {"series": 10, "observations": 640, "fetchedAt": "2026-10-01T01:35:09.770409225Z"}}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `FIX FIX-SOFR-NYFED` → `DRS-1001` | the feed is off (listed under the pack's `connectorsOff`), or nothing was fetched yet | set its variable to `true` and restart; if health is `DOWN`, see below |
| `DOWN: ConnectException: …`, `HttpConnectTimeoutException`, `HttpTimeoutException` | no outbound internet, a firewall, or a proxy the JVM does not know | allow the host, set the proxy system properties, or use a mirror |
| `DOWN: IllegalStateException: HTTP 400 from api.stlouisfed.org` | no or bad `FRED_API_KEY`, or a misspelt series | get a free key from the St. Louis Fed; check `DRISHTI_FRED_SERIES` |
| `DOWN: IllegalStateException: HTTP 403 …` / `HTTP 429 …` | the publisher refused or throttled the server | lower the frequency (`refresh-minutes`), share one fetch through a mirror |
| `DOWN: JsonParseException …` / `SAXParseException …` | an HTML page instead of data (a proxy login page, a changed endpoint) | fetch the URL by hand from the server host and compare with section 3 |
| health `UP` but the id answers `DRS-1001` | the response parsed but held no rows (section 8.2), or a picked date is before the window | look at `observations` in the cache figures; pick a later date, or add a lake for history |
| Live shows yesterday's SOFR | expected: SOFR and €STR for a day are published the next business day | — |
| a picked date shows an old fixing | the date is after the feed's last observation, and nothing newer is held | check health and `fetchedAt`; the publisher may be behind |
| health amber, *stale* | no refresh has brought data for `stale-after` (4d) | the fetch has been failing that long: read `health` |
| the log says `feed needs settings.feed … it stays idle` | the plugin is running as itself without a feed | harmless; the feeds run as the pack's named connectors |
| `failedToStart`: `unknown feed 'x' (…)` | a misspelt `feed` | use one of `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury`, `fred` |
| `failedToStart`: `For input string: …` | `refresh-minutes` or `timeout-seconds` is not a whole number | fix the value |
| server start takes 20 s or more | a feed's first fetch is waiting for its timeouts | lower `timeout-seconds`, fix the network, or switch the feed off |
| a FRED series shows under another series' id | `url` lists more URLs than `series` names, or in a different order | make `url` and `series` match one to one |

## As a connector file

A connector is a site resource: one YAML file in `config/connectors/`, and the file name is the connector's name. The settings of this document go under `settings:` in that file, with nesting flattened to dotted keys (`layout: {trade: {columns: [...]}}` is `layout.trade.columns`); `${ENV_VAR}` placeholders are resolved when the connector starts, and a credential is only ever an `${ENV_VAR}` or a `file:/path` reference. A pack names the connectors it reads through and may suggest a template; the server writes the template to the file once, at the first start, and the file is then the site's. A complete file:

```yaml
# config/connectors/nyfed-sofr-feed.yaml (generated from the market-data pack's template at the first start)
plugin: feed
enabled: ${DRISHTI_FEED_NYFED_SOFR:false}
kinds:
- rate-fixing
settings:
  feed: nyfed-sofr
  user-agent: Drishti public data feed connector
  stale-after: 4d
  refresh-minutes: '60'
```

The file is applied to the running server within seconds, without a restart, and is edited in the editor of your choice, in **Admin → Connectors** (a form generated from this document's settings, a YAML tab, **Test connection**, history) or with `drishti.py connector apply`. The folder, the format, live reload, precedence and the deprecated `drishti.sources.connectors` form are in [CONNECTOR_FILES.md](CONNECTOR_FILES.md). The five feeds have no hand-written example: their files are generated from the pack's templates (list them with `drishti.py connector list`); the other stores' examples are in [`config/connectors.examples/`](../../config/connectors.examples).

## 12. Settings

In the connector file's `settings:` (see [As a connector file](#as-a-connector-file)):

| Setting | Default | Meaning |
|---|---|---|
| `feed` | none (required) | `nyfed-sofr`, `ecb-estr`, `ecb-fx`, `us-treasury` or `fred`; without it the plugin stays idle, an unknown value fails to start |
| `refresh-minutes` | `60` | minutes between the end of one fetch and the start of the next (a whole number); the first fetch is at start |
| `timeout-seconds` | `20` | the request timeout of each HTTP request (a whole number); the connect timeout is a fixed 10 s |
| `url` | the feed's public URL(s) (section 3) | an override; `file:` URLs are read from disk; for `us-treasury` and `fred` a comma list (one per month, or one per series in the order of `series`) |
| `api-key` | empty | the FRED API key (`fred` only) |
| `series` | `DGS10,DFF` | FRED series ids, comma separated (`fred` only); each becomes `FIX-FRED-<series>` |
| `user-agent` | `public-data-feed-connector` | the `User-Agent` header; the pack sets `<product> public data feed connector` |
| `source-name` | the connector's name (as itself: the `feed` value) | the name in provenance, health and search subtitles |
| `stale-after` | none (the pack sets `4d`) | an engine setting: mark the connector stale when no refresh has brought data for this long |

And on the connector itself: `plugin: feed`, `enabled` (the pack uses `${DRISHTI_FEED_<NAME>:false}`), and `kinds`.

| Variable | Used by |
|---|---|
| `DRISHTI_FEED_NYFED_SOFR`, `DRISHTI_FEED_ECB_ESTR`, `DRISHTI_FEED_ECB_FX`, `DRISHTI_FEED_US_TREASURY`, `DRISHTI_FEED_FRED` | `enabled` of each pack connector (default `false`) |
| `FRED_API_KEY` | `fred-feed`'s `api-key` (default empty) |
| `DRISHTI_FRED_SERIES` | `fred-feed`'s `series` (default `DGS10,DFF`) |

## 13. Checklist for production

1. Switch on only the feeds you use; each is one variable.
2. Check that the server reaches the publishers' hosts (or set the JVM proxy properties); if not, run a mirror job
   and point `url` at it.
3. For FRED, keep `FRED_API_KEY` in the environment or a secret store, and choose `DRISHTI_FRED_SERIES`.
4. Keep `refresh-minutes` at 60 or more; with several servers, fetch once into a mirror and point every server at it.
5. Keep `timeout-seconds` short enough that a dead feed does not hold up server start.
6. Keep `stale-after` (4d) so a fetch failing for days shows in health, and alert on `DEGRADED`.
7. Do not purge a feed while its publisher is unreachable: the purge drops the last good data.
8. If you need history beyond the feeds' windows, archive each business day into a dated store under the same ids
   and decide which connector is asked first ([section 7.3](#73-longer-history-from-a-lake)).
9. Remember what the data is: ECB FX are reference rates (`bid` = `ask` = `mid`), the Treasury curve's `zeroRate` is
   the par yield, and FRED series are all shown as percent rates.

---

## Appendix: walk-through and worked examples

Worked output for the feeds above. The reference is in the numbered sections: the source of each feed and the
entities it makes ([section 3](#3-what-each-feed-fetches-and-what-it-becomes)), configuration
([section 4](#4-configuration)), history ([section 6](#6-dates-and-history)), health and failure
([section 8](#8-failure-and-recovery)) and settings ([section 12](#12-settings)).

### Walk-through, step by step

Real output from a server with the three feeds above on:

```bash
curl -s http://localhost:18480/api/v1/entities/rate-fixing/FIX-SOFR-NYFED/raw | jq -c .provenance
curl -s "http://localhost:18480/api/v1/entities/rate-fixing/FIX-SOFR-NYFED/raw?asOf=2026-09-15" \
  | jq -c '{provenance, latest: .data.latest, first: .data.fixings[0]}'
```

```json
{"source":"nyfed-sofr-feed","generation":1790818508749,"fetchedAt":"2026-10-01T01:35:08.749439520Z","live":false,"businessDate":"2026-09-29"}
{"provenance":{"source":"nyfed-sofr-feed","generation":1790818508749,"fetchedAt":"2026-10-01T01:35:08.749439520Z","live":false,"businessDate":"2026-09-15"},
 "latest":0.0364,"first":{"date":"2026-09-15","rate":0.0364,"ratePct":3.64,"volumeBn":2952.0}}
```

In the terminal: `FIX FIX-SOFR-NYFED <GO>`, `FX FX-EURUSD-ECB <GO>`, `CRV CRV-USD-UST <GO>`. Typing
`FX FX-EURUSD-E` offers `FX-EURUSD-ECB` with the subtitle `fx-spot · ecb-fx-feed (public feed)`. `rate-fixing`,
`fx-spot` and `ir-curve` are routed to `market-store`; neither the lake nor the samples hold these ids, so the read
passes on until the feed connector answers.

Health and what happens when a feed is unreachable are in [section 8](#8-failure-and-recovery); the cache figures
are in [section 11](#11-diagnosing). In `GET /api/v1/admin/health`, a feed you have not switched on is listed under
its pack's `connectorsOff` (`"connectorsOff": ["ecb-estr-feed", "fred-feed"]`), not as a failure.

### Configuration by example

`packs/market-data/pack.yaml` declares all five connectors, each off until its variable is set
([section 4.1](#41-the-pack-form-as-shipped)); every setting is in [section 12](#12-settings).

The SOFR document:

```json
{ "indexId": "FIX-SOFR-NYFED", "name": "Secured Overnight Financing Rate (NY Fed)", "latest": 0.0431,
  "administrator": "Federal Reserve Bank of New York", "tenor": "Overnight", "currency": "USD",
  "fixings": [ { "date": "2026-09-29", "rate": 0.0431, "ratePct": 4.31, "volumeBn": 2512.0 }, … ] }
```

An `fx-spot` document has `pair`, `pairName`, `mid` (also as `bid` and `ask`), `change1d`, `spotDate`, 30 days of
`history` and `conventions`; the `ir-curve` document has `curveId`, `tenY`, `slope2s10s` (bp), `asOf` and `points`
(`tenor`, `maturity`, `quote`, `zeroRate`, `df`) from 1M to 30Y. Which business date a document answers, and how
long the history is, are in [section 6](#6-dates-and-history).
