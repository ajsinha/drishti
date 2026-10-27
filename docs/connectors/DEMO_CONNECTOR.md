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
# The demo connector: every pack's samples, in memory, ticking

The `demo` connector serves the sample documents that ship inside each enabled pack (`config/packs/<pack>/samples/`). It needs
nothing installed: at start it reads every sample into memory, answers reads, type-ahead and reverse lookups from there, and moves the
numbers of live samples a little every 400 ms while someone watches them. It is the server's `default-route`, so a server started with
no lake and no store shows working screens. This document explains what it reads, how packs feed it, how it ticks, where it sits in
the routing, what it costs, and why it should be off in production.

**On the demo lakes it is off.** The run configuration **Drishti server (demo lakes)** starts the server with
`DRISHTI_DEMO_ENABLED=false`, so every page reads the lakes `drishti.py lake demo` built (sixty business days, 15,000 trades a day,
[DEMO_DATA.md](DEMO_DATA.md)). Turn it on (`DRISHTI_DEMO_ENABLED=true`, the run configuration **Drishti server**, or the default of a
bare `java -jar`) for what only it gives: values that tick live (tutorial 12's live BI and boards), and screens with no store at all.
With it on beside the lakes, a sample answers before the lake for an id both hold ([section 8](#8-its-place-in-routing)).

The code is `plugins/drishti-plugin-demo` (`DemoSourcePlugin`, `DemoTicker`, `DemoWalk`). For the short version see
[the walk-through at the end of this document](#walk-through-step-by-step) and
[the configuration examples at the end of this document](#configuration-by-example). How the samples are generated, and how the same
data is loaded into every real store, is in [DEMO_DATA.md](DEMO_DATA.md); the sample format from a pack author's side is in
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_MODELLING_GUIDE.md#samples).

## Contents

1. [When to use it](#1-when-to-use-it)
2. [What it serves](#2-what-it-serves)
3. [A sample document](#3-a-sample-document)
4. [How packs supply samples](#4-how-packs-supply-samples)
5. [Configuration](#5-configuration)
6. [How the connector reads](#6-how-the-connector-reads)
7. [Live ticks](#7-live-ticks)
8. [Its place in routing](#8-its-place-in-routing)
9. [Memory and size](#9-memory-and-size)
10. [Failure and recovery](#10-failure-and-recovery)
11. [Security](#11-security)
12. [Limits and trade-offs](#12-limits-and-trade-offs)
13. [Diagnosing](#13-diagnosing)
14. [Settings](#14-settings)
15. [Checklist for production](#15-checklist-for-production)

---

## 1. When to use it

| Use it for | Do not use it for |
|---|---|
| a first look at Drishti on a laptop with no lake: every pack's screens filled | production data of any kind |
| live demos: valuations, curves, FX, netting sets tick without a stream (tutorial 12) | history: it has no business dates, every date gets the same document (the demo lakes have seven) |
| writing a pack: put a few documents in `samples/<kind>.jsonl`, see them laid out at once | more than a few thousand documents per kind: everything is held in memory and scanned for reverse lookups |
| tests: `ticking: false` freezes every document (the golden tests run that way) | data that changes outside the server: files are read once, at start |

## 2. What it serves

Every enabled pack that has a `samples/` folder contributes it. The folder holds a catalogue and one JSON Lines file per kind (the
layout of every shipped pack):

```
config/packs/market-data/samples/
├── catalog.jsonl            the list of samples: kind, id, title, subtitle
├── ir-curve.jsonl          one document a line, in the catalogue's order
├── fx-spot.jsonl
├── rate-fixing.jsonl
└── …                       (a kind above about 50 MB is split: <kind>.part-001.jsonl, <kind>.part-002.jsonl …)
```

The older layout, one file per document (`<kind>/<id>.json`), is still read, as the retired example packs in `examples/packs/` are
written (`PackSamples` in `drishti-common` reads both).

`catalog.jsonl` holds one entry a line (2.9.4; the `catalog.json` array of earlier releases is still read). Only the entries it lists are loaded. Each entry gives the entity's `kind` and `id`, and the `title` and
`subtitle` that the type-ahead dropdown shows; a JSON Lines document is matched to its entry by position (the writers keep the
catalogue's order), else by its id value:

```json
[
 {"kind": "bond", "id": "BND-BRVERD054608", "title": "BND-BRVERD054608", "subtitle": "Bond (security master) · Verdant Agri Holdings"},
 {"kind": "bond", "id": "BND-BRVERD178449", "title": "BND-BRVERD178449", "subtitle": "Bond (security master) · Verdant Agri Holdings"},
 …
]
```

The seven packs ship these samples (counted from the files in this release):

| Pack | Samples | Kinds | Live | With `walk` | On disk |
|---|---|---|---|---|---|
| `core` | 129 | 10 | 30 | 30 | 247 KB |
| `market-data` | 220 | 20 | 119 | 119 | 409 KB |
| `trading` | 765 | 1 | 0 | 0 | 8.0 MB |
| `counterparty-risk` | 534 | 10 | 66 | 66 | 643 KB |
| `market-risk` | 923 | 6 | 765 | 765 | 736 KB |
| `world-economy` | 9,582 | 13 | 0 | 0 | 19.0 MB |
| `news-and-markets` | 35 | 2 | 0 | 0 | 16 KB |
| the seven | 12,188 | 62 | 980 | 980 | 29 MB |

The five banking packs (`core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`) hold the 2,571 documents that
`python3 tools/packgen/banking/make_data.py` writes and [DEMO_DATA.md](DEMO_DATA.md#the-samples) loads into every other store;
world-economy and news-and-markets are written by their own generators under `tools/packgen/`. The trades themselves do not tick (a
trade's terms do not move during the day); each trade's **valuation** (`trade-valuation`, market-risk) does, with its `MTM` and
`PNL_1D`. The retired example packs (`finance`, `genomics`, `logistics`, `retail-banking`, `climate-risk`, `operational-risk`,
`liquidity-risk`) keep their samples in `examples/packs/<pack>/samples/` and contribute only when loaded
(`DRISHTI_PACKS_EXTRA_DIRS=examples/packs`).

## 3. A sample document

A sample is the entity's document as its system of record would hold it (UPPER_SNAKE, as everywhere in the shipped packs), plus one
block, `_meta`, that the connector removes and turns into the document's provenance. A live trade valuation of the market-risk pack,
complete (`config/packs/market-risk/samples/trade-valuation.jsonl`, the first line):

```json
{"VALUATION_ID": "TVAL-1000001", "TRADE_ID": 1000001, "SOURCE_SYSTEM": "ENDUR", "PRODUCT_TYPE": "ComSwap", "PRODUCT_GROUP": "Commodity",
 "PORTFOLIO": "BOOK-COMM-2", "DESK": "DESK-COMM", "COUNTERPARTY": "CP-HARBORPT", "NETTING_SET": "", "TRADER_ID": "TRDR-FROSSI",
 "BUY_SELL_CODE": "S", "TRADE_STATUS": "VALIDATED", "CURRENCY": "USD", "NOTIONAL": 167969743.2, "MTM": 4121280, "MTM_CURRENCY": "USD",
 "PNL_1D": -277804, "DV01": 0, "TRADE_DATE": "2025-06-20", "MATURITY_DATE": "2028-07-31", "VALUATION_DATE": "2026-09-30",
 "_meta": {"source": "risk-engine", "generation": 1, "live": true,
           "walk": {"MTM": 9922, "derive": [{"set": "PNL_1D", "expr": "$.MTM - 4399084"}]}}}
```

A trade of the trading pack carries the 47 attributes of the common trade schema (`SOURCE_SYSTEM`, `TRADE_ID`, `PRODUCT_TYPE`,
`COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO`, …, `LEGS` → `CASH_FLOWS` → `RESETS`, `EVENTS`) and a `_meta` without `live`:
`{"source": "endur", "generation": 1}`.

| `_meta` key | Read as | Becomes |
|---|---|---|
| `source` | text | `provenance.source`: the name users see (`endur`, `risk-engine`, `md-hub`), not `demo` |
| `generation` | a number, truncated to a whole number | `provenance.generation`; each tick adds 1 |
| `live` | boolean | `provenance.live`; only live samples can be subscribed to and tick |
| `walk` | object `{FIELD: step}`, or coherent entries, `derive` and `append` | the fields that move on each tick, and what moves with them ([sections 7.3 and 7.4](#74-coherent-walks-a-group-that-moves-together)); optional |

The `_meta` block's own keys stay lower case; the fields and paths inside a walk are the document's (UPPER_SNAKE). A document without
`_meta` still loads: its source is empty, its generation 0 and it is not live. The document must be a JSON object; anything else stops
the connector from starting.

## 4. How packs supply samples

A pack does not declare a `demo` connector. Its `pack.yaml` may name its samples folder (`samples: samples`, the default when the key is
absent); when that folder exists, the pack loader (`PackLoader`) adds it to one setting:

```
drishti.sources.plugins.demo.settings.dirs = <packs>/core/samples,<packs>/market-data/samples,<packs>/trading/samples,…
```

The folders are listed **most general pack first**: with `DRISHTI_PACKS=counterparty-risk`, its parents (`core`, `market-data`,
`trading`) come before `counterparty-risk` itself. The connector loads the folders in that order, and a sample with the same kind and id
in a later folder **replaces** the earlier one, so a child pack can override a parent's sample by shipping the same kind and id. Type-ahead
keeps one entry per entity as well (the later one wins).

Only enabled packs (`DRISHTI_PACKS`, default the seven shipped packs) contribute. A pack switched off for users in Admin → Packs still has
its samples loaded: that switch hides kinds from users; it does not restart connectors.

## 5. Configuration

The connector runs **as itself** (`drishti.sources.plugins.demo`), never as a named connector; its manifest name is always `demo`.
What ships:

```yaml
# drishti-server/src/main/resources/application.yaml (shipped)
drishti:
  sources:
    default-route: demo                     # tried after a kind's own route
    routes: {}
    plugins:
      demo:
        enabled: ${DRISHTI_DEMO_ENABLED:true}
```

`dirs` comes from the packs; `ticking` and `tick-ms` are not in the shipped file and take the plugin's defaults (`true`, `400`). A site
changes them in `application.local.yaml`:

```yaml
# application.local.yaml
drishti:
  sources:
    plugins:
      demo:
        settings:
          ticking: false        # freeze every document (screenshots, golden tests)
          tick-ms: 1000         # or tick once a second instead of every 400 ms
```

Switching it off for one start, or for good:

```bash
DRISHTI_DEMO_ENABLED=false java -jar drishti-server/target/drishti-server-*-exec.jar
java -jar drishti-server/target/drishti-server-*-exec.jar --drishti.sources.plugins.demo.enabled=false
```

The run configurations set it: **Drishti server (demo lakes)** has `DRISHTI_DEMO_ENABLED=false`; **Drishti server** leaves the default
(on). When it is off, `default-route: demo` names a connector that does not run; the router skips a route to a missing connector, so
nothing else needs changing. A kind that no other connector serves then answers `DRS-1002 no source serves kind '<kind>'` (unless the
`file` plugin, which serves every kind, is on; it is, reading `<data>/feeds`).

Setting `dirs` by hand replaces what the packs put there (the site sits above the packs), which is how a site can serve a folder of its
own samples; the folder needs a `catalog.jsonl` and either `<kind>.jsonl` files or `<kind>/<id>.json`.

## 6. How the connector reads

At start the connector reads, for each folder in `dirs`, `catalog.jsonl` and then each kind's documents, once. It keeps three things in
memory: the documents (a concurrent map from kind and id to document), each document's `walk`, and a type-ahead index (`HitIndex`) of
every catalogue entry. It never reads the folders again; a changed or added sample is seen only after a restart.

| Read | How | Cost |
|---|---|---|
| one entity (`TVAL TVAL-1000001`, `GET /api/v1/entities/trade-valuation/TVAL-1000001/raw`) | one map lookup; the document is shared, a new provenance with the current `fetchedAt` is attached | a hash lookup; no I/O |
| a picked business date | the same document: the connector is not dated, so the date is ignored | as above; the console says the source is not dated ([section 8](#8-its-place-in-routing)) |
| type-ahead (`TRD MX-2`) | `HitIndex`: ids that start with the text (a binary search of each kind's sorted ids), then ids that contain it, then titles and subtitles that contain it, case-insensitive, up to the limit | the prefix step is a binary search; the "contains" steps scan the kind's entries (or every kind when none is given) until the limit is reached |
| a search, a pick list, a derived kind, impact | the engine lists the kind through the same index (`search(kind, "", max-scan + 1)`) and reads each document; the connector keeps no promoted columns | one map lookup per entity; bounded by `drishti.search.max-scan` (20,000) and `budget` (3 s) |
| reverse lookup (*Linked entities*: the valuations of a counterparty) | every document held is checked: is it of the kind asked, is it not the target itself, and does a value **at any depth** (a field, a nested object's field, an array element) equal the target id as text? Matches come back sorted by id | a scan of every sample (12,188 with the seven packs) per call |
| live updates | [section 7](#7-live-ticks) | a timer task every `tick-ms` |

Reverse lookups look at every depth. In the samples both a top-level `"COUNTERPARTY": "CP-HARBORPT"` and a nested
`"TRADES": [{"TRADE_ID": …}]` are found, so a counterparty's *Linked entities* lists its sample valuations and trades. The entity
itself is never listed as its own referrer. The other connectors that declare reverse lookups (Delta Lake, PostgreSQL table mode,
Aerospike and the rest) are asked as well, and their referrers are added.

Dated reads are not supported: the manifest declares `live`, `reverseLookup` and `search`, not `dated`, so the router calls the undated
`fetch`, `search` and `reverse`, and the documents carry no `businessDate`.

## 7. Live ticks

### 7.1 What subscribes

A view (or a live BI visual, or a board tile) on Live subscribes to its entities. The connector accepts a subscription only for a sample
whose `_meta.live` is `true`; for any other it answers "no subscription", and the router tries the next live source. Subscribers are
kept per entity; when the last one leaves, the entity's entry is removed, so the ticker stops moving entities nobody watches.

### 7.2 The timer

With `ticking: true` (the default) one task runs at a fixed rate of `tick-ms` (400 ms), on the server's source scheduler, starting
`tick-ms` after start. Each run:

1. records the time as the connector's `lastUpdate` (so with ticking on the demo is never *stale*, even with no subscribers);
2. for every entity that has at least one subscriber and is live: computes the next document, stores it **in place of the old one**,
   with `generation + 1` and a new `fetchedAt`, and hands it to each subscriber. A subscriber that throws is ignored, so one broken
   view does not stop the others.

Because the stepped document replaces the stored one, a value keeps walking from where it got to: it does not return to the file's
value until the server restarts, and every later read (from any user) sees the moved value and the higher generation.

With `ticking: false` no task runs: subscriptions are accepted, but no update is ever sent.

### 7.3 How a field moves

The steps are random draws from a normal distribution (Box–Muller over a `SplittableRandom` seeded with 42, shared by the whole
connector, so a run's sequence of draws is repeatable but depends on which entities are watched).

**With `walk`** (`{"MTM": 9922}`): each named field that is a **top-level number** gets `value + N(0, step)` (the step is the standard
deviation, not a bound). A field that holds a whole number stays whole when its step is 1 or more (`MTM`, a count of hours); any other
value keeps the step's own precision: the result is rounded to two more decimal places than the step has (at least two), so a step of
0.0004 keeps six decimals and a step of 0.3 keeps three. A named field that is missing or not a number is left alone. A field may also
be a **path into the document**: `TOP_TRADES.0.MTM` is the MTM of the first of a book's top trades (names step into objects, whole
numbers into lists, counting from 0). A path that leaves the document, or ends at something that is not a number, is left alone.

**Without `walk`**, four kinds have built-in walks, written for the retired `finance` pack's mockups and its camelCase fields (no live
sample of the seven packs is without a `walk`, so they apply only to that pack):

| Kind | What moves |
|---|---|
| `curve` | every point of `points`: the field `rate` (step 0.004, 3 decimals), else `price` (0.03, 2 decimals), else `points` (0.3, 2 decimals); one shared shift for the curve plus a third of the step per point |
| `fx-spot` | `spot` by N(0, 0.00008), 5 decimals |
| `netting-set` | `netMtm` by N(0, 2,500); every point of `exposure` (`ee`, `pfe95`) scaled by one factor 1 + N(0, 0.004) |
| `trade`, `productType: FUT` | `lastPrice` by N(0, 0.03); `mtm = (lastPrice − tradePrice) × lots × 1000`; the last entry of `settlements` gets the new settle, change, variation margin and cumulative |
| `trade`, other products | `mtm` by N(0, 0.2% of \|mtm\| + 400), `dv01` by N(0, 15), `swapPoints` by N(0, 0.1), each when present |

Any other kind without `walk` ticks with an unchanged document and a higher generation.

**Two consequences of the rounding rule** worth knowing when you write samples:

- the market-data FX spots walk `MID` with a step of about 0.06% of the spot (0.0004 for `FX-AUDUSD` at 0.661) and keep five decimals,
  so a spot moves by about a pip a tick, as a spot should;
- whole-number fields with a fractional step become decimals after the first tick.

### 7.4 Coherent walks: a group that moves together

A plain walk moves one number. A real market does not: when the 10-year rate ticks, the whole curve, its forwards, its discount factors
and its slope tick with it. A coherent walk says so, in the sample, with no code. A `walk` object has these kinds of entry:

| Entry | Form | Does |
|---|---|---|
| a step | `"FIELD": 0.0003` | that one number, by N(0, step) |
| a group step | `"FIELD": {"step": …, "with": […], …}` | ONE random draw moves the field **and** every path in `with` |
| `derive` | `[{"set": path, "expr": "…", "places": n}]` | after the steps, recomputes what depends on the moved numbers |
| `append` | `[{"to": path, "key": field, "format": "…", "row": {…}, "keep": n}]` | keeps a time series current |

Steps run first, then `derive` entries in the order written (a later one sees an earlier one's result), then `append`. Every entry
stands alone: one that cannot be applied (a path that left the document, an expression that does not parse) is skipped, and the others
still run.

**Paths** are the dotted paths of section 7.3 with three additions:

| Path | Means |
|---|---|
| `POINTS.*.ZERO_RATE` | the `ZERO_RATE` of **every** element of `POINTS` |
| `HISTORY.-1.MID`, `HISTORY.-2.MID` | the last row, the one before it |
| `POINTS.TENOR=10Y.ZERO_RATE` | the element whose `TENOR` is `10Y` |

**A group step** takes these options (all but `step` optional; the option names are lower case):

| Option | Default | Meaning |
|---|---|---|
| `step` | required | the standard deviation of the one draw |
| `with` | none | the other paths that move with it: a path, or `{"path": …, "scale": …, "places": …}` |
| `scale` | 1 | each `with` path moves by the draw times its scale, so a rate held as a decimal (`TEN_Y: 0.0382`) and the same rate in percent (`ZERO_RATE: 3.82`) move together with `scale: 100`; a negative scale moves against (a bond's spread against its price); a path's own `scale` wins |
| `shape` | `parallel` | `parallel`: every element of a `*` path gets the same shift; `twist`: also a random tilt, small at one end of the list and large at the other, so a curve steepens or flattens |
| `jitter` | 0 | an independent extra draw for every element, as a fraction of `step` (0.15 is a blur of 15%) |
| `mode` | absolute | `rel`: `step` is a fraction, and every value moves by its own size times the draw (an exposure profile scaling as one) |
| `places` | from the step | decimals kept; a `with` path may set its own |
| `min`, `max` | none | clamp the walked field (a reading never below zero) |

**`derive`** evaluates `expr` for every place `set` reaches (for `POINTS.*.DF`, once per point, with that point as the current
element). A name is looked up in the current element first, then in the document; `$.NAME` is always the document, and the way to name
the document's field from inside a list. The language is small on purpose:

| Part | Forms |
|---|---|
| numbers, text | `0.75`, `1e-4`, `'Red'` |
| arithmetic | `+ - * /`, parentheses, unary minus |
| comparison, choice | `>= <= == != > <`, `test ? a : b` (nests) |
| a list | a path with `*`: `sum(LEGS.*.PV)` |
| functions | `exp ln sqrt abs pow round clamp min max sum sumpos sumneg avg count tenor noise` |

`tenor('1M')` is 1/12, `tenor('10Y')` is 10; `sumneg` and `sumpos` add the negative or the positive entries; `noise(sd)` is a draw. A
number is stored with `places` decimals, else the old value's, and a whole number stays whole. A second form,
`{"set": "LEGS.0.CASH_FLOWS.*.PV", "scaleTo": "$.LEGS.0.PV"}`, rescales every number the path reaches by one factor so that they add
up to the target.

**`append`** keeps a series current by a clock label. The label is the current time in `format` (a Java `DateTimeFormatter` pattern:
`HH:mm`, `yyyy-MM-dd`, `'H'k` for the hour 1 to 24). If a row of `to` already carries that label in its `key`, the row is updated;
otherwise a new row is added at the end and the oldest rows fall off at `keep`. Each `row` field is an expression, with `last.NAME` the
previous version of that row (or the row before it, for a new one).

**An example**, the cross-currency basis curve `CRV-USD-BASIS` as shipped (`config/packs/market-data/samples/ir-curve.jsonl`), whose
headline `TEN_Y` is 0.0382 (decimal) while its zero rates are in percent, its quotes decimal and its forwards in percent:

```json
"walk": {
  "TEN_Y": {"step": 0.0003, "scale": 100, "shape": "twist", "jitter": 0.15,
            "with": ["POINTS.*.ZERO_RATE", {"path": "POINTS.*.QUOTE", "scale": 1}, "FORWARDS.*.RATE"]},
  "derive": [
    {"set": "POINTS.*.DF", "expr": "exp(-ZERO_RATE / 100 * tenor(TENOR))", "places": 6},
    {"set": "TEN_Y", "expr": "POINTS.TENOR=10Y.ZERO_RATE / 100", "places": 6},
    {"set": "SLOPE_2S_10S", "expr": "(POINTS.TENOR=10Y.ZERO_RATE - POINTS.TENOR=2Y.ZERO_RATE) * 100", "places": 1}
  ]
}
```

One draw of N(0, 0.0003) moves every zero rate by that times 100, every quote by that, and every forward by that times 100, each with a
tilt along the curve and 15% of per-tenor blur; then each discount factor is recomputed from its zero rate, the headline becomes the
10-year point, and the 2s10s slope is recomputed in basis points. In the table and the chart of `/v/ir-curve/CRV-USD-BASIS` every row
changes, not only the 10Y cell: the short end more than the long end (the tilt), and every discount factor falls as its zero rate
rises.

More examples, one per idea (each is in a shipped sample; [DEMO_DATA.md](DEMO_DATA.md#live-walks-by-kind) lists every walking kind):

| Idea | Entry (where it ships) |
|---|---|
| a group: the spread stays constant | `"MID": {"step": 0.0004, "places": 5, "with": [{"path": "BID", "places": 5}, {"path": "ASK", "places": 5}]}` (fx-spot) |
| today's row of a history follows the price | `{"set": "HISTORY.-1.MID", "expr": "$.MID", "places": 5}` (fx-spot) |
| a change against yesterday's close | `{"set": "CHANGE_1D", "expr": "MID / HISTORY.-2.MID - 1", "places": 5}` (fx-spot) |
| a P&L against the opening value | `{"set": "PNL_1D", "expr": "$.MTM - 4399084"}` (trade-valuation) |
| a status re-judged against its thresholds | `{"set": "status", "expr": "value >= red ? 'Red' : value >= amber ? 'Amber' : 'Green'"}` (the retired operational-risk pack) |

**Where the specs come from.** Never by hand: `tools/packgen/common/walks.py` builds each kind's walk from the finished sample (so it
names exactly the paths that sample has), and every generator calls it (`make_data.py` for the banking packs, which then writes every
field and path in UPPER_SNAKE). `python3 tools/packgen/banking/make_data.py --check` fails when the samples differ from what the
generators write.

**What stays still on purpose** (a bond's coupon schedule, a trade's terms, risk and sensitivities, end-of-day figures) is listed per
kind in DEMO_DATA.md and in `walks.py`; the test `DemoPackWalksTest` fails on a walked headline whose declared dependents never move,
and on a walk that names a path its sample does not have.

## 8. Its place in routing

The router ([CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md#how-a-request-picks-a-connector)) builds the candidates for a
kind as: the kind's route, then the `default-route` (`demo`), then every other connector serving the kind. The demo's manifest lists no
kinds, so it is a candidate for **every** kind, and answers "not held" for ids it has no sample of.

| The user asks for | Where the demo stands | Effect |
|---|---|---|
| Live | live sources first, and among live sources the `default-route` last: a real stream (Kafka, ActiveMQ, RabbitMQ, Redis) before the samples, then the samples, then non-live stores | with no stream for the kind, a sample **answers before** the lake for an id both hold (`MX-20000001` is both a sample and a lake trade) |
| a picked date | dated sources first, then undated ones (the demo among them) | the lakes answer for the sixty dates they hold; for a date older than every store's history, the sample answers, with the banner *No data held for 2026-08-01: the current data of endur, a source that keeps no dates* (the name is the sample's `_meta.source`) |
| ticks | the first live source that **holds** the entity, in live order | a view read from a real stream ticks from the stream; one read from a sample ticks from the sample |
| type-ahead, search | every search-capable source is asked and hits are merged | samples appear beside the lakes' ids |
| reverse lookups | every source that declares them | sample referrers are added to the stores' |

Provenance shows the sample's own source name, never `demo`:

```bash
curl -s http://localhost:18480/api/v1/entities/trade-valuation/TVAL-1000001/raw | jq -c .provenance
```

```json
{"source":"risk-engine","generation":1742,"fetchedAt":"…","live":true,"businessDate":null}
```

## 9. Memory and size

Every document is held in memory, parsed, for the life of the server, beside one type-ahead entry and one `walk` per sample. The parsed
form takes several times the JSON's size on disk; with the seven packs the JSON is about 29 MB (12,188 documents, of which world-economy
is 19 MB and the 765 trades 8 MB), a modest share of the server's heap.

The design does not scale to large books, and is not meant to:

| Grows with the number of samples | Why |
|---|---|
| start time | every file is read and parsed at start, one after the other |
| heap | every document is kept parsed |
| each reverse lookup | a scan of every document |
| each tick | a walk of the subscribed entities only (cheap); the timer itself runs whatever the count |

For a large book use a real store: the demo lakes ([DEMO_DATA.md](DEMO_DATA.md), [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md)),
[POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md), [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md) or [FILE_CONNECTOR.md](FILE_CONNECTOR.md).
The demo has no history to combine with a lake; what it adds beside one is only the Live answer and the ticks of section 8.

## 10. Failure and recovery

There is no store to lose: after start the connector reads nothing from disk or network.

| Event | What happens |
|---|---|
| a folder in `dirs` has no `catalog.jsonl` | `start` fails: the log says `source plugin demo failed to start` with the exception, and `GET /api/v1/admin/health` lists it under `failedToStart` as `"demo": "<message>"`. No sample from any folder is served; the server runs on |
| the catalogue names an entry the kind's file does not hold | that entry is skipped; the others load |
| a sample is not valid JSON, or not an object | the whole connector fails to start, as above |
| a catalogue entry has no `title` or `subtitle` | it loads; the dropdown shows empty text |
| a sample has no `_meta` | it loads, not live, with an empty source name |
| a subscriber throws while being handed a tick | ignored; the others still receive it |
| a sample cannot tick (a built-in walk meets a document without its fields) | that sample is skipped on every tick and keeps its document; every other entity goes on ticking. Give such samples a `walk`, or `live: false` |
| a catalogue entry whose kind or id would lead outside the folder (`../`) | skipped: nothing outside the pack's samples folder is read |

**Health.** The connector does not override health, so it is always `UP` while running. It reports no cache figures and a purge does
nothing. `lastUpdate` is the time of the last tick (or of start, with `ticking: false`), so a `stale-after` set on it only fires when
ticking is off.

## 11. Security

- **It serves made-up data under real-looking names.** Samples carry system names (`endur`, `risk-engine`, `md-hub`), UENs and plausible
  amounts. On a production server, a user who types an id that the real store does not hold, or picks a date older than its history,
  can be shown a sample as if it were real (with only the *No data held for <date>* banner on picked dates). Switch it off:
  `DRISHTI_DEMO_ENABLED=false`.
- **Entitlements still apply.** Samples pass through the same role and redaction rules as any other source; the connector applies none
  of its own.
- **No credentials, no network, no TLS.** It reads only the folders in `dirs`, and only the kinds and ids a catalogue lists. An entry
  whose kind or id would name a path outside its folder is skipped, so a catalogue cannot make the connector read other files.

## 12. Limits and trade-offs

- **Read once at start.** A changed sample needs a restart.
- **Undated.** Every business date gets the same document; there is no `snapshot`/`effective` behaviour and no *known at*.
- **No promoted columns.** Searches and aggregates read documents, within `max-scan` (20,000) per kind.
- **Reverse lookups scan every document** of the kind asked, at every depth: fine for samples, not for a large book.
- **Ticks drift without bound** and persist until restart; values never return to the file's.
- **One tick rate** for every sample (`tick-ms`).
- **Runs only as itself**: there is one demo connector per server, fed by every enabled pack.

## 13. Diagnosing

```bash
B=http://localhost:18480/api/v1
curl -s $B/sources -H "Authorization: Bearer $DRISHTI_TOKEN" | jq -c '.sources[] | select(.name=="demo")'
curl -s $B/admin/health -H "Authorization: Bearer $DRISHTI_TOKEN" | jq '.failedToStart'
curl -s "$B/command/suggest?q=TRD%20MX-2" -H "Authorization: Bearer $DRISHTI_TOKEN" | jq .
```

```json
{"name":"demo","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"}
```

On the demo-lakes server (`DRISHTI_DEMO_ENABLED=false`) `demo` is not in `/sources` at all: the list starts `file`, `civic-store`,
`collateral-store`, … `trade-calypso`, `trade-endur`, `trade-murex`, `trade-pages`.

| Symptom | Likely cause | What to do |
|---|---|---|
| `demo` missing from `/sources`, `failedToStart` has `demo` | a `dirs` folder without `catalog.jsonl`, a document that is not a JSON object | fix the sample named in the message (regenerate with the pack's generator); restart |
| `demo` missing from `/sources`, no failure | it is off (`DRISHTI_DEMO_ENABLED=false`, the demo-lakes run configuration) | turn it on if you want samples and ticks |
| a pack's samples are not served | the pack is not in `DRISHTI_PACKS`, or its `samples` folder does not exist | enable the pack; check `samples:` in its `pack.yaml` |
| an edited sample shows the old content | samples are read once at start | restart the server |
| a parent's sample shows instead of the child's (or the reverse) | the later folder in `dirs` wins; folders run from most general to most specific | put the override in the more specific pack |
| a live view does not tick | the sample's `_meta.live` is false (every trade: watch its valuation, `trade-valuation`); or `ticking: false`; or the demo is off; or a real stream holds the entity and ticks instead | set `live: true`; check the settings; check `provenance.source` |
| a walked field does not move | the path leaves the document, or ends at something that is not a number, or the sample is not live | check the path against the sample's UPPER_SNAKE names (`TOP_TRADES.0.MTM`, not `topTrades.0.mtm`) |
| a detail list does not follow its headline | the sample walks the headline alone (the plain `{FIELD: step}` form) | use a coherent walk, [section 7.4](#74-coherent-walks-a-group-that-moves-together); the pack test `DemoPackWalksTest` flags a declared dependent that never moves |
| a `derive` or `append` entry has no effect | its expression does not parse, or names a field the sample does not have: that one entry is skipped, silently, so the others keep walking | run `DemoPackWalksTest` (it parses every expression and resolves every path of every shipped sample) |
| real ids answer with sample data, or a picked old date shows a sample | the demo is on beside the lakes | `DRISHTI_DEMO_ENABLED=false` |
| `DRS-1002 no source serves kind 'x'` after switching it off | the demo (and `file`) served that kind | configure a connector for the kind |

## As a connector file

The demo connector is not a named connector, so it has **no connector file**: it runs as itself under `drishti.sources.plugins.demo`,
and its settings ([section 14](#14-settings)) stay in `application.yaml` and `application.local.yaml` as shown in
[section 5](#5-configuration). Connector files, one YAML file per connector in `config/connectors/`, are for the connectors that read
your systems ([CONNECTOR_FILES.md](CONNECTOR_FILES.md)); the other stores' examples are in
[`config/connectors.examples/`](../../config/connectors.examples). In production, switch the demo off (`DRISHTI_DEMO_ENABLED=false`) and
give each kind a connector file.

## 14. Settings

Under `drishti.sources.plugins.demo`:

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `${DRISHTI_DEMO_ENABLED:true}` (shipped) | whether the connector starts |
| `settings.dirs` | filled by the packs: every enabled pack's samples folder, most general first | comma-separated folders, each with `catalog.jsonl` and `<kind>.jsonl` (or `<kind>/<id>.json`); blank entries are skipped; empty means no samples |
| `settings.ticking` | `true` | `false`: no tick task; documents never move |
| `settings.tick-ms` | `400` | milliseconds between ticks (fixed rate) |
| `settings.stale-after` | none | the engine's staleness warning; see [CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin) |

The connector reads no `source-name` (provenance carries each sample's `_meta.source`) and no `kinds` (it serves every kind). The
server-wide `drishti.sources.default-route` (`demo`) decides its place in the order. Every key is also in
[CONFIGURATION.md](../admin/CONFIGURATION.md#demo--sample-data).

## 15. Checklist for production

1. Set `DRISHTI_DEMO_ENABLED=false` in the server's environment, and check `GET /api/v1/sources` no longer lists `demo`.
2. Make sure every kind users open has a real connector (a route or `kinds`), or the `file` plugin as a fallback; otherwise reads answer
   `DRS-1002`.
3. Change `default-route` if you want a real connector asked second for every kind (it names a connector; with the demo off, `demo` is
   simply skipped).
4. On a demonstration server that keeps it on, say so on screen, keep its packs to the ones being shown, and leave `ticking` on (with it
   off, ticks never come and a `stale-after` would fire).
5. When writing a pack's samples, give each live sample a `walk` of top-level numbers with steps sized to the field (1 or more for whole
   numbers), and regenerate them with the pack's generator rather than by hand.

---

## Appendix: walk-through and worked examples

### Walk-through, step by step

1. Start the server with the demo on: the run configuration **Drishti server**, or `DRISHTI_DEMO_ENABLED=true` (the default of a bare
   `java -jar`); it is the `default-route`, so nothing needs installing or configuring.
2. Open a live sample: `CRV CRV-USD-BASIS <GO>` (a curve whose every row ticks), `FX FX-EURUSD <GO>`, or a netting set
   `NSET NS-SUMMIT-NY <GO>`. Provenance shows the sample's `_meta.source` (`md-hub`, `risk-engine`, …), live
   ([section 3](#3-a-sample-document), [section 7](#7-live-ticks)). Health shows `demo`, always `UP`
   ([section 10](#10-failure-and-recovery)).
3. Live BI: tutorial 12's live tiles over trade valuations tick from this source (`MTM` and `PNL_1D` of each `trade-valuation`); on the
   demo-lakes server (demo off) the same visuals say **Listening**, because daily lakes do not tick.
4. For production, switch it off (`DRISHTI_DEMO_ENABLED=false`): otherwise an id your stores do not hold may be answered by a sample with
   the same id, on Live and for dates older than your history (see
   [how a request picks a connector](CONNECTOR_DEVELOPER_GUIDE.md#how-a-request-picks-a-connector) and the
   [checklist](#15-checklist-for-production)).

### Configuration by example

The packs supply the directories; the site only switches the connector on or off, and may change `ticking` and `tick-ms`
([section 5](#5-configuration), [section 14](#14-settings)). The documents are described in [section 2](#2-what-it-serves) and
[section 3](#3-a-sample-document); the tick rules are in [section 7.3](#73-how-a-field-moves).

Two documents as they are shipped, the first an FX spot that walks its mid with the bid and ask and keeps its change and history in
step, the second a trade valuation that walks its MTM (both shortened):

```json
{"PAIR": "FX-AUDUSD", "PAIR_NAME": "AUD/USD", "MID": 0.661, "BID": 0.66097, "ASK": 0.66103, "CHANGE_1D": -0.01092, "SPOT_DATE": "2026-10-02",
 "HISTORY": [{"DATE": "2026-08-19", "MID": 0.69653}, …],
 "_meta": {"source": "md-hub", "generation": 1, "live": true,
           "walk": {"MID": {"step": 0.0004, "places": 5, "with": [{"path": "BID", "places": 5}, {"path": "ASK", "places": 5}]},
                    "derive": [{"set": "CHANGE_1D", "expr": "MID / HISTORY.-2.MID - 1", "places": 5},
                               {"set": "HISTORY.-1.MID", "expr": "$.MID", "places": 5}]}}}
```

```json
{"VALUATION_ID": "TVAL-1000001", "TRADE_ID": 1000001, "MTM": 4121280, "PNL_1D": -277804, …,
 "_meta": {"source": "risk-engine", "generation": 1, "live": true, "walk": {"MTM": 9922, "derive": [{"set": "PNL_1D", "expr": "$.MTM - 4399084"}]}}}
```
