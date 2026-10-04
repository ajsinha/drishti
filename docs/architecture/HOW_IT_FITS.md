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
# How Drishti fits together

*For someone new to Drishti who wants the whole working in one read: how a data store, a pack and a Sutra relate, and
what happens between typing `TRD MX-20000001` and a live screen.* Every path, key, endpoint and output below is real:
the files are in this repository and the outputs were captured from a server started with the QUICKSTART packs
(`market-risk`, `counterparty-risk`, `liquidity-risk`, `climate-risk`, `operational-risk`, `retail-banking`,
`genomics`, `politics-society`, `economics`) on `http://localhost:18480`. Values that tick (MTM, generation) will
differ when you try it.

## Contents

1. [The one-page picture](#1-the-one-page-picture)
2. [The pieces and how they refer to each other](#2-the-pieces-and-how-they-refer-to-each-other)
3. [Worked example A: a trade, end to end](#3-worked-example-a-a-trade-end-to-end)
4. [Worked example B: a gene variant, another domain](#4-worked-example-b-a-gene-variant-another-domain)
5. [What happens without a Sutra](#5-what-happens-without-a-sutra)
6. [How a new screen gets made today](#6-how-a-new-screen-gets-made-today)
7. [Where to change what](#7-where-to-change-what)
8. [Glossary and further reading](#8-glossary-and-further-reading)

---

## 1. The one-page picture

Drishti draws a screen for any record. The record comes from a **store** (through a **connector**), is matched to a
**Sutra** (a layout), and is bound into a **ViewModel**: plain JSON that the browser draws. Nothing is coded per
product. Packs and Sutras are configuration read when the server starts (Sutras are re-read when a file changes).

```
 BROWSER   typing "TRD MX-20000001 <GO>"; draws panels; receives live patches
    |  HTML pages + one SSE channel per browser
    v
 CONSOLE   console/  (Python, FastAPI + Jinja2, port 17480)
    |      console/routes/terminal_routes.py (/go, /v/...), console/core/backend.py (a thin client)
    |      console/web/templates (_macros/panels.html) + console/web/static/js (command.js, live.js, ...)
    |  REST + SSE under /api/v1, ViewModel JSON
    v
 SERVER    drishti-server/  (Java 21+, Spring Boot, port 18480)
    |      controllers: CommandController, ViewController, StreamController, DesignController, ...
    v
 ENGINE    drishti-engine/ ViewPipeline
    |       command -> fetch -> match -> fingerprint -> layout -> link -> bind
    |          |         |        |                        |                |
    |          |         |        |                        |                +-- Binder (drishti-engine)
    |          |         |        |                        +-- LayoutMerger + InferenceEngine (drishti-inference)
    |          |         |        +-- SutraMatcher + SutraRegistry (drishti-rachana)
    |          |         +-- SourceRouter (drishti-engine)
    |          +-- CommandParser (mnemonics, id patterns)
    |                    |
    |                    v
    |   CONNECTORS  plugins/drishti-plugin-*  (SourcePlugin interface in drishti-api)
    |     demo (pack samples, ticking) | delta | jdbc | kafka | file | s3 | rest | redis | ...
    |                    |
    |                    v
    |   STORES   Delta Lake folders under data/delta/<domain>/<kind>/business_date=...,
    |            databases, queues, files, services
    |
    +-- CONFIGURATION loaded at start
    |     packs/<name>/pack.yaml   kinds, mnemonics, links, connectors, routes, roles
    |     packs/<name>/sutras/**   the layouts      packs/<name>/samples/**   example documents
    |     drishti-server/src/main/resources/application.yaml   drishti.sources, drishti.rachana, ...
    |
 BUILD     the Build workbench (console /build, server DesignController / GovernanceController)
           makes new Sutras and proposes them; an approved Sutra is saved into the registry
           (SutraRegistry) and the next view uses it.
```

The Maven modules: `drishti-api` (the plugin interface and shared types), `drishti-engine` (pipeline, router, binder),
`drishti-rachana` (the grammar, parser, matcher, registry), `drishti-inference` (layouts from the shape of data),
`drishti-packs` (pack loading), `drishti-server` (HTTP, security, governance), `plugins/*` (one connector each).
The UI is the separate Python project in `console/`. The full module list is in
[ARCHITECTURE.md §13](ARCHITECTURE.md#13-module-layout-java).

---

## 2. The pieces and how they refer to each other

| Piece | What it is | Where it lives |
|---|---|---|
| **Kind** | A type of record: `trade`, `netting-set`, `variant`. | Named in `pack.yaml` `kinds:` |
| **Entity id** | The name of one record of a kind: `MX-20000001`. An entity is `(kind, id)`. | In the data; recognised by `graph.id-patterns` |
| **Mnemonic** | The short command for a kind: `TRD` for `trade`. | `pack.yaml` `mnemonics:` |
| **Pack** | A folder `packs/<name>/` that adds a domain: kinds, mnemonics, links, connectors, Sutras, samples, roles. | `packs/<name>/pack.yaml` |
| **Connector** | A named instance of a source plugin: which plugin, its settings, which kinds it serves. | `pack.yaml` `connectors:` or `drishti.sources.connectors` |
| **Source plugin** | The code that reads one kind of store (`delta`, `kafka`, `jdbc`, `demo`, ...). | `plugins/drishti-plugin-*` |
| **Sutra** | One layout, written in the Rachana grammar: which records it fits, a title, a strip, panels, keys. | `packs/<name>/sutras/**.sutra.yaml` |
| **Link** | A field that names another entity, so it opens that entity's own view. | `graph.fields` in `pack.yaml`; `link(...)` in a Sutra |
| **Role / mask** | Who may open which kinds, and which fields read `•••`. | `pack.yaml` `roles:`; `drishti.security.redact` |

How they point at each other:

| From | To | By what key |
|---|---|---|
| Typed command `TRD MX-20000001` | a kind | the mnemonic `TRD` (`mnemonics:` of any enabled pack) |
| An id typed alone (`MX-20000001`) | a kind | `graph.id-patterns` (`^(MX\|CLY\|END\|IMG\|BBG\|WSS)-\d+$` is `trade`) |
| A pack | other packs | `extends:` (`market-risk` extends `market-data` and `trading`, so a server running `market-risk` also serves `trade`) |
| A kind | the connector that holds it | the connector's `kinds:` list, and `routes:` (kind to connector name; becomes `drishti.sources.routes.<kind>`) |
| A connector | its plugin and store | `plugin:` (`delta`) and `settings:` (`root`, `domain`) |
| A document | its Sutra | the Sutra's `match: { kind: ..., where: ..., priority: ... }`; the highest priority whose `where` holds wins |
| A Sutra panel | the document's values | `$.path` expressions (`bind: $.terms.fixedRate`) with `fmt:` formats; inside a row list `@.field` |
| A Sutra | another entity | `link($.counterparty.id, 'counterparty', ...)` and `keys: { F7: "link($.nettingSet, 'netting-set')" }`: the second argument is a kind |
| A document field | another entity | `graph.fields` (a field named `tradeIds` holds ids of kind `trade`) |
| A chart panel | another entity's data | `source: "link($.discountCurve, 'ir-curve')"` fetches that entity and plots its rows |
| A caller | what they may see | `roles:` (`kinds:`, `raw:`) and `drishti.security.redact` (field names) |
| A Sutra | its pack | the folder (`sutras: sutras` in `pack.yaml`); extra site Sutras in `drishti.rachana.dirs` |

Two rules hold the whole thing together. A **kind** is the only thing a store, a Sutra and a command have in common:
the connector says "I hold kind X", the Sutra says "I draw kind X", the mnemonic says "this word means kind X". And a
Sutra never says where data comes from; a connector never says how data looks.

---

## 3. Worked example A: a trade, end to end

### 3.1 The pack declares the kind and the mnemonic

`packs/trading/pack.yaml` (the `trading` pack is pulled in by `market-risk` through `extends:`):

```yaml
pack: trading
code: TRDS
kinds:
- trade
- desk-pnl
sutras: sutras
mnemonics:
  TRD:
    kind: trade
    label: Trade
graph:
  id-patterns:
  - pattern: ^(MX|CLY|END|IMG|BBG|WSS)-\d+$
    kind: trade
```

So `TRD` means `trade`, and `MX-20000001` alone is recognised as a trade.

### 3.2 The connector serves the kind

In the same file:

```yaml
connectors:
  trading-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds:
    - trade
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: trading
```

`trading-store` is the `delta` plugin reading Delta Lake tables under `./data/delta/trading/trade/business_date=...`
(four dated folders on this machine, from 2026-09-28). `GET /api/v1/sources` confirms it: `"name":"trading-store",
"kinds":["trade"],"live":false`. The pack also declares `trading-stream` (a Kafka connector for live ticks), which is
off unless `DRISHTI_STREAM_TRADING=true`.

A second source also serves trades. `drishti.sources.default-route: demo` in `application.yaml` names the **demo**
plugin, which serves the sample documents in every enabled pack's `samples/` folder
(`packs/trading/samples/trade/MX-20000001.json` is one) and makes their numbers tick. Together:

| You ask for | Answered by | Evidence in the view's `provenance` |
|---|---|---|
| Today's trade (the default) | `demo`, because a live source is asked first (`SourceRouter.liveFirst`) | `"source":"murex-rates","live":true,"businessDate":null` (`murex-rates` is the originating system recorded in the sample's `_meta`) |
| A past business date (`?asOf=2026-09-30`) | `trading-store`, the dated Delta table | `"source":"trading-store","live":false,"businessDate":"2026-09-30"` |

In production you would turn the demo plugin off (`DRISHTI_DEMO_ENABLED=false`) and `trading-store` (and the Kafka
stream) would answer for today too. The order in which sources are asked is decided in
`drishti-engine/src/main/java/com/ash/drishti/engine/source/SourceRouter.java`: `candidates()` (the kind's route, then
the default route, then any plugin that serves the kind), `readOrder()` and `liveFirst()`, and `readFirst()` (the first
source that holds the entity answers; one that fails stops the read, so another store's data is never shown in its
place).

### 3.3 The Sutra that draws it

125 Sutras in `packs/trading/sutras/` match kind `trade`, one per product, all at priority 10, each with a `where`
that picks its product. The one for `MX-20000001` is `packs/trading/sutras/rates/irs-fixfloat.v1.sutra.yaml`:

```yaml
rachana: 1
sutra: irs-fixfloat
version: 1
match: { kind: trade, where: "$.productType == 'IRS_FIXFLOAT'", priority: 10 }
title: { pill: Rates · Interest rate swap (fixed/float), id: $.tradeId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip:
  - { label: Notional, bind: "$.currency + ' ' + fmt($.notional, 'amount0')" }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  ...
panels:
  - id: terms
    kind: kv
    title: Terms
    code: TRM
    key: F2
    columns:
      - { label: Fixed rate, bind: $.terms.fixedRate, fmt: pct4 }
      ...
  - id: sensitivities
    kind: hbar
    area: right
    rows: $.sensitivities
    label: bucket
    value: dv01
keys: { F7: "link($.nettingSet, 'netting-set')", F8: impact, F9: raw }
```

**Why this Sutra and no other.** `SutraRegistry.forKind("trade")` lists the Sutras of the kind, highest `priority`
first and then by name. `SutraMatcher.match()` walks that list and returns the first whose `where` is true for the
document. This document has `"productType":"IRS_FIXFLOAT"`, so only `irs-fixfloat` is true. A Sutra with no `where`
matches every document of its kind, which is how you write a catch-all (give it a lower priority).

### 3.4 The document

`GET /api/v1/entities/trade/MX-20000001/raw`, trimmed:

```json
{"ref":{"kind":"trade","id":"MX-20000001"},
 "provenance":{"source":"murex-rates","generation":1,"live":true,"businessDate":null},
 "data":{"tradeId":"MX-20000001","productType":"IRS_FIXFLOAT","assetClass":"Rates","status":"Live",
  "direction":"Receive fixed","tradeDate":"2025-07-25","maturityDate":"2032-06-25","currency":"AUD",
  "notional":2.42E8,"mtm":1875863,"pnl1d":126986,
  "counterparty":{"id":"CP-MERIDIAN-RE","name":"Meridian Reinsurance Ltd"},
  "nettingSet":"NS-MERIDIAN-RE-NY","book":"BOOK-RATES-3","discountCurve":"CRV-AUD-OIS",
  "terms":{"fixedRate":0.040829,"payFrequency":"Annual","dayCount":"ACT/365F"},
  "risk":{"dv01":-155245},
  "sensitivities":[{"bucket":"3M","dv01":-5544},{"bucket":"6M","dv01":-11089}, ...],
  "legs":[{"leg":1,"label":"Receive fixed 4.0829%","cashflows":[ ... ]}, ...]}}
```

The envelope (`ref`, `provenance`) is Drishti's; `data` is the source's document, untouched. Every `$.path` in the Sutra
is a path into `data`.

### 3.5 The runtime sequence for `TRD MX-20000001`

```
 browser              console (FastAPI)                     server (Spring)                  engine
 -------              -----------------                     ---------------                  ------
 1 type + Enter --> 2 GET /go?q=TRD MX-20000001
                      terminal_routes.go()
                      backend.command() -------------> 3 POST /api/v1/command
                                                          CommandController.command()
                                                          CommandParser.parse()  (TRD -> trade)
                      <-- {"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD"}
                    4 303 redirect to /v/trade/MX-20000001
 5 GET /v/trade/..  terminal_routes.view()
                      backend.view() ----------------> 6 GET /api/v1/views/trade/MX-20000001
                                                          ViewController.view()
                                                          ViewPipeline.view() ------------> 7 SourceRouter.fetch()  -> document
                                                                                            8 SutraMatcher.match()
                                                                                            9 LayoutMerger.merge()
                                                                                           10 SourceRouter.fetchAll()  (linked)
                                                                                           11 Binder.bind() per panel
                      <-- ViewModel JSON <------------------------------------------------ ViewModel
                   12 Jinja renders the panels
 13 live.js opens the view's live channel; frames of patches arrive and change the page in place
```

Step by step, with the code to open:

| # | What happens | Where |
|---|---|---|
| 1 | The command box submits the line to the console. | `console/web/static/js/command.js` |
| 2 | A line that is a search (`TRD where ...`) goes to `/s`; otherwise the console asks the server what the line means. | `console/routes/terminal_routes.py:go` |
| 3 | The server splits mnemonic and id, finds the kind, checks the caller may open it, and checks the entity exists. Answer: `{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD","list":null,"matched":null,"pack":null}`. | `drishti-server/.../api/CommandController.java:command`, `resolve`; `drishti-engine/.../command/CommandParser.java:parse` |
| 4 | The console redirects to the view URL. | `terminal_routes.py:go` |
| 5 | The view page asks the server for the ViewModel. | `terminal_routes.py:view`, `console/core/backend.py:view` |
| 6 | The server applies the caller's roles and field masks and calls the pipeline. | `drishti-server/.../api/ViewController.java:view` |
| 7 | **Fetch.** The router picks the store (section 3.2) and returns the document with its provenance. | `SourceRouter.java:fetch`, `readFirst` |
| 8 | **Match.** The first Sutra of kind `trade` whose `where` is true: `irs-fixfloat v1`. If none, inference alone builds the layout (section 5). | `drishti-rachana/.../SutraMatcher.java:match` |
| 9 | **Layout.** The document's shape is fingerprinted (keys and types, not values). The layout is the Sutra's panels plus inference for what the Sutra left out ("Sutra irs-fixfloat v1 + inference"). It is cached per `(Sutra version, kind, fingerprint)`, so this runs once per shape. | `drishti-engine/.../ViewPipeline.java:build` (the `layouts` cache), `drishti-inference/.../LayoutMerger.java:merge`, `InferenceEngine.java:infer` |
| 10 | **Links.** Entities the layout refers to (a panel's `source:`, the badges of linked entities) are fetched in parallel within a time budget (`drishti.graph.link-budget`, 40 ms). One that is slower shows as pending. | `ViewPipeline.java:build` calling `SourceRouter.fetchAll` |
| 11 | **Bind.** Each panel evaluates its `$.paths` against the document, applies the format (`fmt: pct4` turns `0.040829` into `4.0829%`) and produces cells, rows and series. Panels bind in parallel. A field the document lacks shows a dash, not an error. | `drishti-engine/.../bind/Binder.java:bind`, `cell`; expressions in `drishti-rachana/.../el/` |
| 12 | The console draws each panel with a Jinja macro. | `console/web/templates/terminal/view.html`, `console/web/templates/_macros/panels.html:panel` |
| 13 | Live updates (section 3.7). | `console/web/static/js/live.js`, `StreamController.java` |

### 3.6 The ViewModel (what the server answers)

`GET /api/v1/views/trade/MX-20000001`, trimmed. This is all the browser needs; the text is already formatted:

```json
{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD",
 "title":{"pill":"Rates · Interest rate swap (fixed/float)","id":"MX-20000001",
          "with":{"text":"Meridian Reinsurance Ltd","link":{"kind":"counterparty","id":"CP-MERIDIAN-RE","mnemonic":"CPTY"}}},
 "strip":[{"label":"Notional","text":"AUD 242,000,000","path":"$.currency"}, ...,
          {"label":"MTM (USD)","text":"+1,875,863","tone":"pos","emphasis":true,"path":"$.mtm"}, ...],
 "panels":[
  {"id":"terms","kind":"kv","title":"Terms","code":"TRM","key":"F2","area":"main","inferred":false,
   "data":{"fields":[{"label":"Fixed rate","text":"4.0829%","path":"$.terms.fixedRate"}, ...]}},
  {"id":"sensitivities","kind":"hbar","title":"DV01 by bucket (USD)","area":"right",
   "data":{"bars":[{"label":"3M","value":-5544.0,"text":"−5,544","tone":"neg"}, ...]}}, ...],
 "keys":[{"key":"F2","label":"Terms","action":"panel","panel":"terms"}, ...,
         {"key":"F7","label":"Netting set","action":"link","link":{"kind":"netting-set","id":"NS-MERIDIAN-RE-NY","mnemonic":"NSET"}}],
 "provenance":{"layout":"Sutra irs-fixfloat v1 + inference","source":"murex-rates","generation":1,"live":true,"sutra":"irs-fixfloat"},
 "timings":{"fetch":0.16,"layout":0.73,"links":2.45,"bind":1.27,"total":4.61}}
```

How the Sutra became this: `title.with` is the `link(...)` of the Sutra's title; each `strip` cell is a Sutra `strip`
entry (`path` remembers the source path, which is what makes a cell patchable and noteable); `terms` is the Sutra's `kv`
panel with `fmt: pct4` applied; `keys` are the panels' `key:` fields plus the Sutra's `keys:`. The view has ten panels:
`terms`, `legs`, `schedule`, `explain`, `lifecycle`, `built` in the main area; `marketData`, `sensitivities`, `pnl`,
`refs` on the right. `timings` are milliseconds.

### 3.7 How the UI is bound: panels and live updates

The console does not know what a trade is. It knows panel kinds (`kv`, `table`, `ladder`, `tabs`, `line`, `hbar`,
`waterfall`, `timeline`, `links`, ...; the full list is in [PANEL_KINDS.md](../guides/PANEL_KINDS.md)).
`_macros/panels.html:panel` picks the macro by `p.kind`; the same macro draws an `hbar` for a trade's DV01 and for
anything else. This is why a new product needs a Sutra but no UI work.

Live: after the page loads, `live.js` subscribes the page to `view:trade/MX-20000001` on the browser's one live channel
(`/api/channel`, `console/routes/api_routes.py`). The server side is `StreamController.java`
(`GET /api/v1/views/{kind}/{id}/stream`): `ViewStream` rebuilds the view when the source pushes a new document, and
`PatchDiffer` sends only the difference. The first message is the whole view, then frames follow. Real output
(`curl -N -H 'Accept: text/event-stream' http://localhost:18480/api/v1/views/trade/MX-20000001/stream`, cut):

```
event:view
id:0
data:{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD", ... "provenance":{... "generation":1,"live":true}}

event:frame
id:1
data:{"seq":1,"generation":2,"patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+1,883,736",
      "tone":"pos","emphasis":true,"path":"$.mtm"}},{"op":"panel","panel":{"id":"built","kind":"provenance", ...}}, ...]}
```

Patch `op`s are `strip` (one header cell), `panel` (a whole panel), `provenance`, and `deleted` / `restored`. The console
turns each panel patch into ready HTML (`api_routes.py:_view_event`) and `live.js:onFrame` swaps it in. A view of a past
business date never ticks: the source is the dated Delta table and the status shows Static. Details in [LIVE.md](LIVE.md).

### 3.8 Links and F-keys open another kind, with its own Sutra

The counterparty name in the title, the netting set on **F7**, and the **Linked entities** panel are all links. A link is
`{kind, id, mnemonic}`; clicking it is the same as typing `NSET NS-MERIDIAN-RE-NY`. Section 3.5 then runs again for
kind `netting-set`, and this time:

| | Trade | Netting set |
|---|---|---|
| Pack | `trading` (through `market-risk`) | `counterparty-risk` |
| Connector | `trading-store` (`delta`, domain `trading`) | `credit-store` (`delta`, domain `credit`) |
| Sutra | `irs-fixfloat v1`, chosen by `where: $.productType == 'IRS_FIXFLOAT'` | `netting-set v1` in `packs/counterparty-risk/sutras/exposure-and-capital/netting-set.v1.sutra.yaml`, `match: { kind: netting-set, priority: 10 }` (no `where`: one layout for the kind) |
| Strip | Notional, MTM, DV01, ... | Trades, Net MTM, Collateral, PFE 95 peak, Utilisation, ... |
| Real `provenance.layout` | `Sutra irs-fixfloat v1 + inference` | `Sutra netting-set v1 + inference` |

Different pack, different store, different Sutra, same pipeline. The two views know about each other only through the
value `"nettingSet":"NS-MERIDIAN-RE-NY"` in the trade and the Sutra key `F7: "link($.nettingSet, 'netting-set')"`.

---

## 4. Worked example B: a gene variant, another domain

The `genomics` pack has nothing to do with banking. Type `VRNT VRNT-BRAF-V600E <GO>`.

**Pack** (`packs/genomics/pack.yaml`): `extends: []` (it stands alone), kinds `gene`, `variant`, `protein`, ..., and

```yaml
mnemonics:
  VRNT:
    kind: variant
    label: Variant
connectors:
  genomics-store:
    plugin: delta
    kinds: [gene, variant, protein, pathway, sample, sequencing-run, expression-study, clinical-trial]
    settings:
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: genomics
routes:
  variant: genomics-store
```

This pack states the route for every kind (`routes:` becomes `drishti.sources.routes.variant`, read by
`SourceRouter.candidates()`). The lake data is `data/delta/genomics/variant/business_date=2026-09-17` and later. As in
example A, today's read is answered from the pack's samples (`packs/genomics/samples/variant/`) by the demo plugin, and
`?asOf=2026-09-30` is answered by `genomics-store` (`"source":"genomics-store","businessDate":"2026-09-30"`).

**Document** (`GET /api/v1/entities/variant/VRNT-BRAF-V600E/raw`, trimmed): `variantId`, `geneSymbol: BRAF`,
`proteinChange: p.Val600Glu`, `significance: Pathogenic (somatic)`, `frequencies: [{population, af}, ...]`,
`evidence: [{source, assertion, stars, date}, ...]`, `gene: GENE-BRAF`.

**Sutra** (`packs/genomics/sutras/genomics-and-biology/variant.v1.sutra.yaml`, trimmed):

```yaml
match: { kind: variant, priority: 10 }
title: { pill: Genomics and biology · Variant, id: $.variantId }
strip:
  - { label: Protein change, bind: $.proteinChange, emphasis: true }
panels:
  - { id: frequencies, kind: hbar, title: Allele frequency by population, key: F2,
      rows: $.frequencies, label: population, value: af, fmt: pct4 }
  - id: evidence
    kind: table
    key: F3
    rows: $.evidence
    columns:
      - { label: Source, bind: "@.source" }
      - { label: Assertion, bind: "@.assertion" }
keys: { F7: "link($.gene, 'gene')", F8: impact, F9: raw }
```

**Real ViewModel summary**: mnemonic `VRNT`; `provenance.layout` is `Sutra variant v1 + inference`; panels
`frequencies` (hbar), `evidence` (table), `annotation` (kv, right; the Sutra gives it only `rows: $.annotation`, so
inference fills its fields and the view marks it `inferred: true`), `built` (provenance), `refs` (links); keys F2, F3,
F7 (gene `GENE-BRAF`, mnemonic `GENE`), F8, F9; the whole view is built in about 2 ms.

| | Trade (A) | Variant (B) |
|---|---|---|
| Pack | `trading` | `genomics` |
| Kind, mnemonic | `trade`, `TRD` | `variant`, `VRNT` |
| Store | Delta lake, domain `trading` (plus a Kafka stream, plus demo samples) | Delta lake, domain `genomics` (plus demo samples) |
| Sutra | `irs-fixfloat`, with a `where` on `productType` | `variant`, no `where` |
| Panels | kv, tabs, ladder, waterfall, timeline, line, hbar, links | hbar, table, kv, links |
| Link out | counterparty, netting set, book, curve | gene |
| **Unchanged** | the grammar (`match`, `strip`, `panels`, `bind: $.path`, `fmt`, `keys`, `link`), the pipeline (section 3.5), the ViewModel shape, the console macros, live patches, roles and masks | the same |

That is "any data, any domain, one grammar": a new domain is a new folder of YAML and JSON, not new code.

---

## 5. What happens without a Sutra

If no Sutra matches, `SutraMatcher.match()` returns nothing, and `LayoutMerger.merge(Optional.empty(), doc, kind)` uses
the inference engine alone (`drishti-inference`: rules that read the shape of the document and the names of its
fields). The same trade, with its Sutra left out, is what `GET /api/v1/studio/inferred/trade/MX-20000001` returns (as
Sutra text, which you can edit and keep). Real summary of that output:

| | With `irs-fixfloat` | Inference alone |
|---|---|---|
| Name | `irs-fixfloat` v1 | `trade-custom` (match `{ kind: trade, priority: 1 }`) |
| Strip | 8 chosen cells (Notional, Direction, Trade date, Maturity, MTM, DV01, Status, Book) | 8 cells: Product name, Status, Trade date, Effective date, Maturity date, Currency, Notional, MTM currency |
| Panels | 10, with a P&L waterfall, a lifecycle timeline and DV01 bars | 11: tables for cashflows and P&L explain, ladders, tabs for legs, kv for terms, execution and lifecycle, a line for sensitivities, links, provenance |
| Keys | F2 to F9, named | `F9` raw JSON only |

It is a usable screen on day one, with every value shown and formatted by field name; a Sutra decides which figures
lead, which chart suits which series, and what the keys do. A Sutra may also be partial: whatever it does not say,
inference fills (*How this view was built* then says "Sutra X + inference"). The rules are in
[INFERENCE.md](INFERENCE.md).

---

## 6. How a new screen gets made today

A screen is a Sutra, so making one means making a Sutra and getting it into the registry. The Build workbench
(console `/build`, [BUILD_WORKBENCH.md](BUILD_WORKBENCH.md)) does it without writing YAML by hand:

```
 Build > New     pick samples (JSON files), a schema, or an existing Sutra or example (it opens as a COPY)
     |           a Design is saved on the server (DesignController, /api/v1/builder/designs)
     v
 Data            the samples are shaped: keys, types, roles
     |
     v
 Design          auto-design drafts a layout from the samples (.../autodesign); you drag panels and fields
     |           on the canvas over a real preview; the YAML tab shows the same Sutra
     v
 Check           the Sutra runs against every sample (.../check): which panels are empty or fail
     |
     v
 Propose         POST /api/v1/builder/designs/{id}/propose   (DesignShipController)
     |           recorded as a pending proposal, with evidence
     v
 Approve         an approver (role flag approve; not the author when four-eyes is on)
     |           POST /api/v1/sutras/proposals/{id}/approve   (GovernanceController -> SutraGovernance.approve)
     v
 Registry        SutraRegistry.save(text) writes the Sutra and loads it; ViewPipeline drops its cached layouts
     |
     v
 Next view       SutraMatcher finds the new Sutra by its match: kind, where, priority; the user sees it
```

Tie-back to sections 2 and 3: the Design's output is a `.sutra.yaml` like `irs-fixfloat`. Its `match:` decides which
documents it draws, and its panels' `$.paths` are checked against your samples. Governance is on by default
(`drishti.governance.enabled`, `four-eyes`). Sutras you write belong in a site folder (`drishti.rachana.dirs`,
`DRISHTI_SUTRAS`) or in a pack. Guides: [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) and
[SCREEN_BUILDER.md](SCREEN_BUILDER.md).

---

## 7. Where to change what

| I want to... | Change | Read |
|---|---|---|
| Read a new store (a database, a topic, a folder, a service) | A connector: `plugin:` and `settings:` under `connectors:` in `pack.yaml`, or `drishti.sources.connectors` in `application.yaml`; `routes:` for the kinds it serves | [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Add a domain (new kinds, commands, links) | A pack folder `packs/<name>/` with `pack.yaml`, `sutras/`, `samples/`; enable it with `DRISHTI_PACKS` | [PACKS.md](../guides/PACKS.md) |
| Change how one screen looks | Its Sutra (`*.sutra.yaml`), or a Design in the Build workbench, proposed and approved | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) |
| Change how a figure is formatted | `fmt:` in the Sutra; named formats in the pack's `config/formats.yaml` | RACHANA_REFERENCE.md |
| Change how inference reads field names | The pack's `config/semantics.yaml` | [INFERENCE.md](INFERENCE.md) |
| Add a panel kind | The engine (`Binder` and the panel model) and a console renderer (`_macros/panels.html`) | [PANEL_KINDS.md](../guides/PANEL_KINDS.md), [DEVELOPER_GUIDE.md](../guides/DEVELOPER_GUIDE.md) |
| Decide who may see what | `roles:` in `pack.yaml` (kinds, `raw`), `drishti.security.redact` (fields shown as `•••`), the Admin pages | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Add a command word | `mnemonics:` in `pack.yaml` (or under `drishti.commands` for a site) | PACKS.md |
| Add a link between kinds | `graph.fields` in `pack.yaml` and `link(...)` in the Sutra | PACKS.md |

---

## 8. Glossary and further reading

The glossary is [ARCHITECTURE.md §3](ARCHITECTURE.md#3-core-concepts) (entity, mnemonic, DataNode, source, fingerprint,
Rachana, Sutra, inference, layout, ViewModel, patch, link). Then:

- [ARCHITECTURE.md](ARCHITECTURE.md): the pipeline, the grammar, modules and security; [section 5](ARCHITECTURE.md#5-walk-through-one-request) is a second walk through one request.
- [INFERENCE.md](INFERENCE.md): layouts without a Sutra.
- [LIVE.md](LIVE.md): topics, frames, patches and reconnects.
- [BUILD_WORKBENCH.md](BUILD_WORKBENCH.md) and [SCREEN_BUILDER.md](SCREEN_BUILDER.md): how screens are made.
- [PACKS.md](../guides/PACKS.md): every key of `pack.yaml`.
- [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md): every key of a Sutra.
- [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md): connectors.
