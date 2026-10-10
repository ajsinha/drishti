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

The `demo` connector serves the sample documents that ship inside each enabled pack (`config/packs/<pack>/samples/`). It
needs nothing installed: at start it reads every sample into memory, answers reads, type-ahead and reverse lookups from
there, and moves the numbers of live samples a little every 400 ms while someone watches them. It is the server's
`default-route`, so a fresh checkout shows working screens before any store is configured. This document explains what
it reads, how packs feed it, how it ticks, where it sits in the routing, what it costs, and why it should be off in
production.

The code is `plugins/drishti-plugin-demo` (`DemoSourcePlugin`, `DemoTicker`). For the short version see
[the walk-through at the end of this document](#walk-through-step-by-step) and [the configuration examples at the end of this document](#configuration-by-example).
How the samples are generated, and how the same data is loaded into every real store, is in
[DEMO_DATA.md](DEMO_DATA.md); the sample format from a pack author's side is in
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#samples).

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
| a first look at Drishti on a laptop: nothing to install, every pack's screens filled | production data of any kind |
| demos and screenshots: live views tick without a stream | history: it has no business dates, every date gets the same document |
| writing a pack: put a few documents in `samples/`, see them laid out at once | more than a few thousand documents: everything is held in memory and scanned for reverse lookups |
| tests: `ticking: false` freezes every document (the golden tests run that way) | data that changes outside the server: files are read once, at start |

## 2. What it serves

Every enabled pack that has a `samples/` folder contributes it. The folder holds a catalogue and one JSON file per
entity:

```
config/packs/finance/samples/
├── catalog.json                 the list of samples: kind, id, title, subtitle
├── trade/
│   ├── IRS-48213.json           one document per entity: <kind>/<id>.json
│   ├── FXS-20931.json
│   └── CFT-77120.json
├── netting-set/
│   └── NS-NORTH-01.json
└── curve/
    ├── USD-SOFR.json
    └── …
```

`catalog.json` is an array. Only the entries it lists are loaded; a file in a kind folder that the catalogue does not
name is ignored. Each entry gives the entity's `kind` and `id` (which together name the file), and the `title` and
`subtitle` that the type-ahead dropdown shows:

```json
[
  {"kind": "trade", "id": "IRS-48213", "title": "IRS-48213", "subtitle": "Interest rate swap · Northbridge Capital · USD 50m · 5Y"},
  {"kind": "trade", "id": "FXS-20931", "title": "FXS-20931", "subtitle": "FX swap · Harbor Point Bank · EUR/USD 25m"},
  {"kind": "trade", "id": "CFT-77120", "title": "CFT-77120", "subtitle": "Commodity future · NYMEX CL Z6 · Long 150"}
]
```

The packs in this repository ship these samples (counted from the catalogues on 2026-10-01):

| Pack | Samples | Kinds | Live | With `walk` | JSON on disk |
|---|---|---|---|---|---|
| `finance` (the default pack) | 36 | 12 | 8 | 0 | 196 KB |
| `banking-core` | 165 | 12 | 30 | 30 | 135 KB |
| `market-data` | 220 | 20 | 119 | 119 | 337 KB |
| `trading` | 750 | 1 | 360 | 750 | 5.7 MB |
| `counterparty-risk` | 498 | 8 | 66 | 66 | 553 KB |
| `market-risk` | 158 | 5 | 0 | 0 | 117 KB |
| `liquidity-risk` | 104 | 7 | 10 | 10 | 136 KB |
| `operational-risk` | 136 | 8 | 40 | 40 | 132 KB |
| `retail-banking` | 147 | 8 | 75 | 75 | 215 KB |
| `climate-risk` | 93 | 6 | 0 | 0 | 115 KB |
| `economics` | 77 | 8 | 0 | 0 | 121 KB |
| `genomics` | 58 | 8 | 0 | 0 | 50 KB |
| `politics-society` | 96 | 8 | 0 | 0 | 93 KB |
| `logistics` | 8 | 4 | 7 | 7 | 5 KB |
| all packs | 2,546 | | 715 | | 7.9 MB |

The five banking packs (`banking-core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`) hold the 1,791
documents that [DEMO_DATA.md](DEMO_DATA.md#the-samples) loads into every store; they are written by
`python3 tools/packgen/banking/make_data.py`. The other packs are written by their own generators under
`tools/packgen/` (built on `tools/packgen/common/packbuild.py`). Note that 390 of the 750 trading samples carry a
`walk` but are not `live`: they never tick ([section 7](#7-live-ticks)).

## 3. A sample document

A sample is the entity's document as its system of record would hold it, plus one block, `_meta`, that the connector
removes and turns into the document's provenance. A live trade of the trading pack, shortened:

```json
{
  "tradeId": "CLY-3000019",
  "sourceSystem": "Calypso",
  "productType": "CDS_SWAPTION",
  "currency": "AUD",
  "notional": 77000000.0,
  "mtm": -244202,
  "counterparty": {"id": "…", "name": "…", "lei": "…"},
  "nettingSet": "NS-CASCADIA-NY",
  "book": "BOOK-CREDIT-3",
  "…": "…",
  "_meta": {"source": "murex-credit", "generation": 1, "live": true, "walk": {"mtm": 1258}}
}
```

A vessel of the logistics pack, complete, which walks three fields:

```json
{
  "vesselId": "VSL-9811000", "name": "MSC Aurora", "imo": "9811000", "flag": "Panama",
  "speedKnots": 17.4, "headingDeg": 292, "latitude": 12.91, "longitude": 53.44,
  "nextPort": "PORT-EGPSD", "eta": "2026-10-02", "teuCapacity": 14000, "teuLoaded": 12180, "utilisation": 0.87,
  "_meta": {"source": "port-ops", "generation": 512, "live": true,
            "walk": {"speedKnots": 0.3, "latitude": 0.02, "longitude": 0.03}}
}
```

| `_meta` key | Read as | Becomes |
|---|---|---|
| `source` | text | `provenance.source`: the name users see (`murex-credit`, `aero-risk`, `port-ops`), not `demo` |
| `generation` | a number, truncated to a whole number | `provenance.generation`; each tick adds 1 |
| `live` | boolean | `provenance.live`; only live samples can be subscribed to and tick |
| `walk` | object `{field: step}` | the fields that move on each tick ([section 7](#7-live-ticks)); optional |

A document without `_meta` still loads: its source is empty, its generation 0 and it is not live. The document must be a
JSON object; anything else stops the connector from starting.

## 4. How packs supply samples

A pack does not declare a `demo` connector. Its `pack.yaml` may name its samples folder (`samples: samples`, the
default when the key is absent); when that folder exists, the pack loader (`PackLoader`) adds it to one setting:

```
drishti.sources.plugins.demo.settings.dirs = <packs>/banking-core/samples,<packs>/market-data/samples,<packs>/trading/samples,…
```

The folders are listed **most general pack first**: with `DRISHTI_PACKS=counterparty-risk`, its parents
(`banking-core`, `market-data`, `trading`) come before `counterparty-risk` itself. The connector loads the folders in
that order, and a sample with the same kind and id in a later folder **replaces** the earlier one, so a child pack can
override a parent's sample by shipping the same `<kind>/<id>.json`. Type-ahead keeps one entry per entity as well (the
later one wins).

Only enabled packs (`DRISHTI_PACKS`, default `finance`) contribute. A pack switched off for users in Admin → Packs still
has its samples loaded: that switch hides kinds from users; it does not restart connectors.

## 5. Configuration

The connector runs **as itself** (`drishti.sources.plugins.demo`), never as a named connector; its manifest name is
always `demo`. What ships:

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

`dirs` comes from the packs; `ticking` and `tick-ms` are not in the shipped file and take the plugin's defaults
(`true`, `400`). A site changes them in `application.local.yaml`:

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

When it is off, `default-route: demo` names a connector that does not run; the router skips a route to a missing
connector, so nothing else needs changing. A kind that no other connector serves then answers `DRS-1002 no source
serves kind '<kind>'` (unless the `file` plugin, which serves every kind, is on).

Setting `dirs` by hand replaces what the packs put there (the site sits above the packs), which is how a site can serve
a folder of its own samples; the folder needs the same `catalog.json` and `<kind>/<id>.json` layout.

## 6. How the connector reads

At start the connector reads, for each folder in `dirs`, `catalog.json` and then every file it lists, once. It keeps
three things in memory: the documents (a concurrent map from kind and id to document), each document's `walk`, and a
type-ahead index (`HitIndex`) of every catalogue entry. It never reads the folders again; a changed or added sample is
seen only after a restart.

| Read | How | Cost |
|---|---|---|
| one entity (`TRD IRS-48213`, `GET /api/v1/entities/trade/IRS-48213/raw`) | one map lookup; the document is shared, a new provenance with the current `fetchedAt` is attached | a hash lookup; no I/O |
| a picked business date | the same document: the connector is not dated, so the date is ignored | as above; the console says the source is not dated ([section 8](#8-its-place-in-routing)) |
| type-ahead (`TRD IRS-4`) | `HitIndex`: ids that start with the text (a binary search of each kind's sorted ids), then ids that contain it, then titles and subtitles that contain it, case-insensitive, up to the limit | the prefix step is a binary search; the "contains" steps scan the kind's entries (or every kind when none is given) until the limit is reached |
| a search, a pick list, a derived kind, impact | the engine lists the kind through the same index (`search(kind, "", max-scan + 1)`) and reads each document; the connector keeps no promoted columns | one map lookup per entity; bounded by `drishti.search.max-scan` (20,000) and `budget` (3 s) |
| reverse lookup (*Linked entities*: trades of `NS-NORTH-01`) | every document held is checked: is it of the kind asked, is it not the target itself, and does a value **at any depth** (a field, a nested object's field, an array element) equal the target id as text? Matches come back sorted by id | a scan of every sample (2,546 with every pack on) per call |
| live updates | [section 7](#7-live-ticks) | a timer task every `tick-ms` |

Reverse lookups look at every depth. In the trading samples both `"nettingSet": "NS-CASCADIA-NY"` and
`"counterparty": {"id": "CP-…"}` are found, so a counterparty's *Linked entities* lists its sample trades. The entity
itself is never listed as its own referrer. The other connectors that declare reverse lookups (Delta Lake, PostgreSQL
table mode, Aerospike and the rest) are asked as well, and their referrers are added.

Dated reads are not supported: the manifest declares `live`, `reverseLookup` and `search`, not `dated`, so the router
calls the undated `fetch`, `search` and `reverse`, and the documents carry no `businessDate`.

## 7. Live ticks

### 7.1 What subscribes

A view opened on Live subscribes to its entity. The connector accepts a subscription only for a sample whose `_meta.live`
is `true`; for any other it answers "no subscription", and the router tries the next live source. Subscribers are kept
per entity; when the last one leaves, the entity's entry is removed, so the ticker stops moving entities nobody
watches.

### 7.2 The timer

With `ticking: true` (the default) one task runs at a fixed rate of `tick-ms` (400 ms), on the server's source
scheduler, starting `tick-ms` after start. Each run:

1. records the time as the connector's `lastUpdate` (so with ticking on the demo is never *stale*, even with no
   subscribers);
2. for every entity that has at least one subscriber and is live: computes the next document, stores it **in place of
   the old one**, with `generation + 1` and a new `fetchedAt`, and hands it to each subscriber. A subscriber that
   throws is ignored, so one broken view does not stop the others.

Because the stepped document replaces the stored one, a value keeps walking from where it got to: it does not return
to the file's value until the server restarts, and every later read (from any user) sees the moved value and the
higher generation.

With `ticking: false` no task runs: subscriptions are accepted, but no update is ever sent.

### 7.3 How a field moves

The steps are random draws from a normal distribution (Box–Muller over a `SplittableRandom` seeded with 42, shared by
the whole connector, so a run's sequence of draws is repeatable but depends on which entities are watched).

**With `walk`** (`{"mtm": 1258}`): each named field that is a **top-level number** gets `value + N(0, step)` (the step
is the standard deviation, not a bound). A step of 1 or more rounds the result to a whole number; a smaller step keeps
the step's own precision: the result is rounded to two more decimal places than the step has (at least two), so a step
of 0.0004 keeps six decimals and a step of 0.3 keeps three. A named field that is missing or not a number is left
alone. Nested fields cannot be walked.

**Without `walk`**, four kinds have built-in walks (written for the finance pack's mockups):

| Kind | What moves |
|---|---|
| `curve` | every point of `points`: the field `rate` (step 0.004, 3 decimals), else `price` (0.03, 2 decimals), else `points` (0.3, 2 decimals); one shared shift for the curve plus a third of the step per point |
| `fx-spot` | `spot` by N(0, 0.00008), 5 decimals |
| `netting-set` | `netMtm` by N(0, 2,500); every point of `exposure` (`ee`, `pfe95`) scaled by one factor 1 + N(0, 0.004) |
| `trade`, `productType: FUT` | `lastPrice` by N(0, 0.03); `mtm = (lastPrice − tradePrice) × lots × 1000`; the last entry of `settlements` gets the new settle, change, variation margin and cumulative; `margin.variationMarginToday` follows |
| `trade`, other products | `mtm` by N(0, 0.2% of \|mtm\| + 400), `dv01` by N(0, 15), `swapPoints` by N(0, 0.1), each when present |

Any other kind without `walk` ticks with an unchanged document and a higher generation.

**Two consequences of the rounding rule** worth knowing when you write samples:

- the market-data FX spots walk `mid` with a step of 0.0004, and keep six decimals, so `FX-EURGBP` moves from 0.871 by
  typically about 0.0004 a tick, as a spot should;
- whole-number fields with a fractional step (`speedKnots: 0.3`) become decimals after the first tick.

## 8. Its place in routing

The router ([CONNECTOR_DEVELOPER_GUIDE.md](CONNECTOR_DEVELOPER_GUIDE.md#how-a-request-picks-a-connector)) builds the candidates for a
kind as: the kind's route, then the `default-route` (`demo`), then every other connector serving the kind. The demo's
manifest lists no kinds, so it is a candidate for **every** kind, and answers "not held" for ids it has no sample of.

| The user asks for | Where the demo stands | Effect |
|---|---|---|
| Live | live sources first, and among live sources the `default-route` last: a real stream (Kafka, ActiveMQ, RabbitMQ, Redis) before the samples, then the samples, then non-live stores | with no stream for the kind, a sample **answers before** the lake or database for an id both hold |
| a picked date | dated sources first, then undated ones (the demo among them) | the stores answer for dates they hold; for a date older than every store's history, the sample answers, with the banner *No data held for 2026-08-01: the current data of murex-rates, a source that keeps no dates* (the name is the sample's `_meta.source`) |
| ticks | the first live source that **holds** the entity, in live order | a view read from a real stream ticks from the stream; one read from a sample ticks from the sample |
| type-ahead, search | every search-capable source is asked and hits are merged | samples appear beside the stores' ids |
| reverse lookups | every source that declares them | sample referrers are added to the stores' |

Provenance shows the sample's own source name, never `demo`:

```bash
curl -s http://localhost:18480/api/v1/entities/trade/IRS-48213/raw | jq -c .provenance
```

```json
{"source":"aero-risk","generation":1742,"fetchedAt":"…","live":true,"businessDate":null}
```

## 9. Memory and size

Every document is held in memory, parsed, for the life of the server, beside one type-ahead entry and one `walk` per
sample. The parsed form takes several times the JSON's size on disk; with every pack enabled the JSON is 7.9 MB
(2,546 documents, of which the 750 trades are 5.7 MB), a modest share of the server's heap.

The design does not scale to large books, and is not meant to:

| Grows with the number of samples | Why |
|---|---|
| start time | every file is read and parsed at start, one after the other |
| heap | every document is kept parsed |
| each reverse lookup | a scan of every document |
| each tick | a walk of the subscribed entities only (cheap); the timer itself runs whatever the count |

For a large book (a million trades a day) use a real store: [DELTA_CONNECTOR.md](DELTA_CONNECTOR.md),
[POSTGRES_CONNECTOR.md](POSTGRES_CONNECTOR.md), [AEROSPIKE_CONNECTOR.md](AEROSPIKE_CONNECTOR.md) or
[FILE_CONNECTOR.md](FILE_CONNECTOR.md), loaded with the same samples and a generated book by the scripts in
[DEMO_DATA.md](DEMO_DATA.md). The demo has no history to combine with a lake; what it adds beside one is only the
Live answer and the ticks of section 8.

## 10. Failure and recovery

There is no store to lose: after start the connector reads nothing from disk or network.

| Event | What happens |
|---|---|
| a folder in `dirs` has no `catalog.json`, or the catalogue names a file that does not exist | `start` fails: the log says `source plugin demo failed to start` with the exception, and `GET /api/v1/admin/health` lists it under `failedToStart` as `"demo": "<message>"` (for a missing file, the exception's message, the file's path). No sample from any folder is served; the server runs on |
| a sample is not valid JSON, or not an object | as above: the whole connector fails to start |
| a catalogue entry has no `title` or `subtitle` | it loads; the dropdown shows empty text |
| a sample has no `_meta` | it loads, not live, with an empty source name |
| a subscriber throws while being handed a tick | ignored; the others still receive it |
| a sample cannot tick (the built-in walk of a `curve` meets a curve without `points`) | that sample is skipped on every tick and keeps its document; every other entity goes on ticking. Give such samples a `walk`, or `live: false` |
| a catalogue entry whose kind or id would lead outside the folder (`../`) | skipped: nothing outside the pack's samples folder is read |

**Health.** The connector does not override health, so it is always `UP` while running. It reports no cache figures and a purge
does nothing. `lastUpdate` is the time of the last tick (or of start,
with `ticking: false`), so a `stale-after` set on it only fires when ticking is off.

## 11. Security

- **It serves made-up data under real-looking names.** Samples carry system names (`murex-rates`, `aero-risk`), LEIs
  with valid check digits and plausible amounts. On a production server, a user who types an id that the real store
  does not hold, or picks a date older than its history, can be shown a sample as if it were real (with only the
  *No data held for <date>* banner on picked dates). Switch it off: `DRISHTI_DEMO_ENABLED=false`.
- **Entitlements still apply.** Samples pass through the same role and redaction rules as any other source; the
  connector applies none of its own.
- **No credentials, no network, no TLS.** It reads only the folders in `dirs`, and only the files a catalogue lists
  (`<dir>/<kind>/<id>.json`, with kind and id taken from the catalogue). An entry whose kind or id would name a path
  outside its folder is skipped, so a catalogue cannot make the connector read other files.

## 12. Limits and trade-offs

- **Read once at start.** A changed sample needs a restart.
- **Undated.** Every business date gets the same document; there is no `snapshot`/`effective` behaviour and no
  *known at*.
- **No promoted columns.** Searches and aggregates read documents, within `max-scan` (20,000) per kind.
- **Reverse lookups scan every document** of the kind asked, at every depth: fine for samples, not for a large book.
- **Ticks drift without bound** and persist until restart; values never return to the file's.
- **One tick rate** for every sample (`tick-ms`).
- **Runs only as itself**: there is one demo connector per server, fed by every enabled pack.

## 13. Diagnosing

```bash
B=http://localhost:18480/api/v1
curl -s $B/sources | jq -c '.sources[] | select(.name=="demo")'
curl -s $B/admin/health | jq '.failedToStart'
curl -s "$B/command/suggest?q=TRD%20IRS" | jq .
```

```json
{"name":"demo","kinds":[],"live":true,"search":true,"reverseLookup":true,"health":"UP"}
```

| Symptom | Likely cause | What to do |
|---|---|---|
| `demo` missing from `/sources`, `failedToStart` has `demo` | a catalogue entry without its file, a file that is not a JSON object, a `dirs` folder without `catalog.json` | fix the sample named in the message (regenerate with the pack's generator); restart |
| a pack's samples are not served | the pack is not in `DRISHTI_PACKS`, or its `samples` folder does not exist | enable the pack; check `samples:` in its `pack.yaml` |
| an edited sample shows the old content | samples are read once at start | restart the server |
| a parent's sample shows instead of the child's (or the reverse) | the later folder in `dirs` wins; folders run from most general to most specific | put the override in the more specific pack |
| a live view does not tick | the sample's `_meta.live` is false; or `ticking: false`; or a real stream holds the entity and ticks instead | set `live: true`; check the settings; check `provenance.source` |
| a walked field does not move | it is nested, or not a number, or the sample is not live | walk top-level numbers only |
| one sample never ticks, the others do | it cannot tick (a `curve` without `points` and without `walk`) and is skipped | fix the sample, or give it a `walk`; restart |
| real ids answer with sample data, or a picked old date shows a sample | the demo is on beside real stores | `DRISHTI_DEMO_ENABLED=false` |
| `DRS-1002 no source serves kind 'x'` after switching it off | the demo (and `file`) served that kind | configure a connector for the kind |

## As a connector file

The demo connector is not a named connector, so it has **no connector file**: it runs as itself under `drishti.sources.plugins.demo`, and its settings
([section 14](#14-settings)) stay in `application.yaml` and `application.local.yaml` as shown in [section 5](#5-configuration). Connector files, one YAML file
per connector in `config/connectors/`, are for the connectors that read your systems ([CONNECTOR_FILES.md](CONNECTOR_FILES.md)); the other stores' examples are in
[`config/connectors.examples/`](../../config/connectors.examples). In production, switch the demo off (`DRISHTI_DEMO_ENABLED=false`) and give each kind a connector file.

## 14. Settings

Under `drishti.sources.plugins.demo`:

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `${DRISHTI_DEMO_ENABLED:true}` (shipped) | whether the connector starts |
| `settings.dirs` | filled by the packs: every enabled pack's samples folder, most general first | comma-separated folders, each with `catalog.json` and `<kind>/<id>.json`; blank entries are skipped; empty means no samples |
| `settings.ticking` | `true` | `false`: no tick task; documents never move |
| `settings.tick-ms` | `400` | milliseconds between ticks (fixed rate) |
| `settings.stale-after` | none | the engine's staleness warning; see [CONFIGURATION.md](../admin/CONFIGURATION.md#connector-settings-plugin-by-plugin) |

The connector reads no `source-name` (provenance carries each sample's `_meta.source`) and no `kinds` (it serves every
kind). The server-wide `drishti.sources.default-route` (`demo`) decides its place in the order. Every key is also in
[CONFIGURATION.md](../admin/CONFIGURATION.md#demo--sample-data).

## 15. Checklist for production

1. Set `DRISHTI_DEMO_ENABLED=false` in the server's environment, and check `GET /api/v1/sources` no longer lists
   `demo`.
2. Make sure every kind users open has a real connector (a route or `kinds`), or the `file` plugin as a fallback;
   otherwise reads answer `DRS-1002`.
3. Change `default-route` if you want a real connector asked second for every kind (it names a connector; with the demo
   off, `demo` is simply skipped).
4. On a demonstration server that keeps it on, say so on screen, keep its packs to the ones being shown, and leave
   `ticking` on (with it off, ticks never come and a `stale-after` would fire).
5. When writing a pack's samples, give each live sample a `walk` of top-level numbers with steps sized to the field
   (1 or more for whole numbers), and regenerate them with the pack's generator rather than by hand.

---

## Appendix: walk-through and worked examples

Moved here from the former connector guides, so that everything about this connector is in one document.

### Walk-through, step by step

1. Start the server with the shipped configuration ([section 5](#5-configuration)): the demo is on and is the
   `default-route`, so nothing needs installing or configuring.
2. Type `TRD IRS-48213 <GO>` or `NSET NS-NORTH-01 <GO>`. Provenance shows the sample's `_meta.source` (`aero-risk`,
   `aero-fx`, ...), live ([section 3](#3-a-sample-document), [section 7](#7-live-ticks)). Health shows `demo`, always `UP`
   ([section 10](#10-failure-and-recovery)).
3. For production, switch it off (`DRISHTI_DEMO_ENABLED=false`): otherwise an id your stores do not hold may be answered
   by a sample with the same id, on Live and for dates older than your history (see the 1 August example in
   [how a request picks a connector](CONNECTOR_DEVELOPER_GUIDE.md#how-a-request-picks-a-connector) and the
   [checklist](#15-checklist-for-production)).

### Configuration by example

The packs supply the directories; the site only switches the connector on or off, and may change `ticking` and
`tick-ms` ([section 5](#5-configuration), [section 14](#14-settings)). The documents are described in
[section 2](#2-what-it-serves) and [section 3](#3-a-sample-document); the tick rules are in
[section 7.3](#73-how-a-field-moves).

Two documents as they are written, the first an FX spot with no `walk` (the built-in walk of its kind applies), the
second a trade that walks its MTM (`config/packs/trading/samples/trade/MX-20000001.json`, shortened):

```json
{
  "pair": "EUR/USD", "spot": 1.174, "bid": 1.17396, "ask": 1.17404, "spotDate": "2026-10-02",
  "_meta": { "source": "aero-fx", "generation": 88, "live": true }
}
```

```json
{
  "tradeId": "MX-20000001", "productType": "...", "notional": 242000000.0, "mtm": 1875863, "nettingSet": "...",
  "_meta": { "source": "murex-rates", "generation": 1, "live": true, "walk": { "mtm": 6172 } }
}
```
