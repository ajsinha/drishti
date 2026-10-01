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
# Inference: layouts from the shape of the data

Drishti can show **any** JSON document, even one nobody has written a screen for. When an entity has no
Sutra (no layout), or its Sutra leaves gaps, Drishti looks at the **shape** of the document and builds a
sensible view by itself. This is called **inference**.

This page explains, with worked examples:

1. [what inference does, in one minute](#in-one-minute);
2. [a worked example from a document you could paste yourself](#worked-example-a-warehouse-document);
3. [a real example from the climate-risk pack](#a-real-example-ngfs-delayed);
4. [how the engine decides, step by step](#how-the-engine-decides-step-by-step);
5. [two more real examples, a small one and a big one](#a-worked-example-on-real-data-kri-comm-1);
6. [how to go from an inferred layout to a Sutra](#from-an-inferred-layout-to-a-sutra);
7. [how to see why a panel was chosen](#seeing-why-a-panel-was-chosen);
8. [how a Sutra overrides inference, and how inference fills a Sutra's gaps](#sutra-and-inference-together);
9. [the rules, scores and limits](#the-rules) (the reference part);
10. [semantic hints: how field names become formats and labels](#semantic-hints), and how a site replaces them.

## In one minute

- Inference is **automatic**. You do nothing to turn it on.
- It is **deterministic**: the same document shape always gives the same layout.
- It is **explainable**: every panel it adds carries the rule that chose it, a score and a reason.
- It is **cheap**: the result is cached per (Sutra version, kind, data shape), so it runs once per shape,
  not once per request (`drishti.engine.layout-cache-size`, 10,000 layouts by default).
- **A Sutra always wins.** Inference only fills what a Sutra leaves out.

```
document ─► rules propose candidates ─► best candidate per document path ─► packer ─► inferred layout
                                                                                         │
             Sutra (optional) ────────────────────────────── LayoutMerger ◄─────────────┘
                                                                  │
                                                           effective layout
                                         ("inference only", or "Sutra counterparty v1 + inference")
```

The code is in `drishti-inference/src/main/java/com/ash/drishti/inference/`: `Rules.java` (the six rules),
`InferenceEngine.java` (choosing and packing), `ColumnInference.java` (table and key/value columns),
`LayoutMerger.java` (Sutra ⊕ inference) and `Semantics.java` (field-name hints).

## Worked example: a warehouse document

Suppose a new source sends this document for the kind `warehouse`, and nobody has written a Sutra for it:

```json
{
  "warehouseId": "WH-ROTTERDAM-2",
  "name": "Rotterdam East",
  "region": "Europe",
  "status": "Open",
  "capacity": 52000,
  "utilisation": 0.83,
  "storageCost": 18250,
  "openedDate": "2019-04-01",
  "manager": { "name": "Ines Vos", "email": "ines.vos@example.com", "phone": "+31 10 555 0101" },
  "zones": [
    { "zone": "A", "type": "Ambient", "pallets": 9100, "utilisation": 0.91 },
    { "zone": "B", "type": "Ambient", "pallets": 8800, "utilisation": 0.88 },
    { "zone": "C", "type": "Chilled", "pallets": 4200, "utilisation": 0.70 },
    { "zone": "D", "type": "Frozen",  "pallets": 2600, "utilisation": 0.65 },
    { "zone": "E", "type": "Hazmat",  "pallets":  900, "utilisation": 0.45 }
  ],
  "throughput": [
    { "quarter": "Q1", "inbound": 41000, "outbound": 39500 },
    { "quarter": "Q2", "inbound": 43800, "outbound": 42900 },
    { "quarter": "Q3", "inbound": 45100, "outbound": 44700 },
    { "quarter": "Q4", "inbound": 47600, "outbound": 46200 }
  ],
  "costByCategory": [
    { "category": "Labour", "amount": 9200 },
    { "category": "Energy", "amount": 4100 },
    { "category": "Rent", "amount": 3600 },
    { "category": "Insurance", "amount": 1350 }
  ],
  "dailyPicks": [
    { "date": "2026-09-24", "picks": 4120 },
    { "date": "2026-09-25", "picks": 4385 },
    { "date": "2026-09-28", "picks": 3990 },
    { "date": "2026-09-29", "picks": 4510 },
    { "date": "2026-09-30", "picks": 4650 }
  ]
}
```

To try it without a data source:

1. Open **Studio** (`/studio` in the console).
2. In the top bar, type `warehouse` in the kind box and `WH-ROTTERDAM-2` in the id box.
3. Open the **Sample JSON** tab on the right, paste the document, and tick **Preview against this JSON**.
4. Press **Start from inference**.

You should see the editor fill with a new Sutra named `warehouse-wh-rotterdam-2` (the inferred layout, written
out), and the **Preview** tab render it. Edit anything and press **Ctrl+Enter** to preview again.

Here is what inference makes of it, step by step.

### 1. The title line

| Part | Result | Why |
|---|---|---|
| Pill | `Warehouse` | the kind, in words (`climate-scenario` would read `Climate scenario`); a top-level `product` field would be added as `Warehouse · <product>` |
| Identifier | `WH-ROTTERDAM-2` | the first field present of `{kind}Id`, `id`, `code`, `name`; here `warehouseId` (the kind in camel case + `Id`) |
| "with" | none | only added when the document has a `counterparty` (an object with `id`, or a plain value) |

If none of the identifier fields exists, the first top-level text field whose name ends in `Id` is used.

### 2. The strip (key figures under the title)

Every top-level scalar except the identifier, `product`, `productType` and `counterparty` is a candidate. Each
is given a **role** by its name (see [Semantic hints](#semantic-hints)); the 8 with the highest role weight
are kept and shown in document order. This document has 7, so all appear:

| Field | Role (weight) | Format | Shown as |
|---|---|---|---|
| `name` | label (65) | none | `Rotterdam East` |
| `region` | label (65) | none | `Europe` |
| `status` | label (65) | none | `Open` |
| `capacity` | number (30): no pattern matches | `amount0` | `52,000` |
| `utilisation` | percent (50) | `pct0` | `83%` |
| `storageCost` | money (80): the name contains `cost` | `amount0` | `18,250` |
| `openedDate` | date (60): the name ends in `Date` and the value is an ISO date | `date` | `2019-04-01` |

Labels are the field names in words: `storageCost` reads **Storage cost**, `openedDate` reads **Opened date**.

### 3. The panels

Each rule looks at the arrays and objects and proposes panels with a score. For each document path the
highest score wins:

| Path | Candidates (score) | Winner | Why |
|---|---|---|---|
| `$.zones` | table (0.60) | **table**, main column, `F2` | 5 objects with the same keys. Columns: Zone, Type, Pallets, Utilisation (`91%`) |
| `$.throughput` | area chart (0.85), table (0.59) | **area chart**, main, `F3` | 4 rows keyed by `Q1`…`Q4` (a tenor-like axis) with two numbers: series *Inbound* and *Outbound* |
| `$.costByCategory` | horizontal bars (0.80), table (0.59) | **hbar**, right column | 4 rows of exactly label + number, and `amount` is money |
| `$.dailyPicks` | ladder (0.72), table (0.60), hbar (0.45) | **ladder**, main, `F4` | 5 rows led by an ISO date, in date order; the latest row is highlighted |
| `$.manager` | key/value (0.55) | **kv**, right column | an object with 3 scalar fields: Name, Email, Phone |

So the view reads:

```
[Warehouse] WH-ROTTERDAM-2
Name Rotterdam East · Region Europe · Status Open · Capacity 52,000 · Utilisation 83% · Storage cost 18,250 · Opened date 2019-04-01

MAIN COLUMN                                   RIGHT COLUMN
F2 Zones            (table)                   Cost by category   (horizontal bars)
F3 Throughput       (area chart, 2 series)    Linked entities
F4 Daily picks      (ladder, last row lit)    Manager            (key/value)
How this view was built
```

Two things to notice:

- **Linked entities** goes right after the first panel of the right column. Any identifier in the document that
  matches a known pattern (for example `CP-…` with the banking packs) appears there as a link.
- **How this view was built** always closes the main column. For this view it says **Layout: inference only**.

### 4. What would change the result

| If the document had… | Inference would… |
|---|---|
| `"zones"` with only 2–4 rows, each with 5 or more fields | show them as **tabs** (one tab per zone, `Zone 1`, `Zone 2`, …) instead of a table (LegsRule, 0.90) |
| `"throughput"` with one number per quarter instead of two | draw a **line** chart in the right column (0.82) |
| `"costByCategory"` values named `share` instead of `amount` | still draw bars, but with score 0.45, so a table (0.59) would win |
| `"dailyPicks"` out of date order | not use a ladder; a table would win |
| more than 6 main-column panels | keep the 6 best scoring, shown in document order; only the first five get `F2`–`F6` |
| more than 3 right-column panels | keep the 3 best scoring |
| more than 9 columns in a table | keep the first column and the 8 most important by role weight |

## A real example: NGFS-DELAYED

Inference also helps when you start a Sutra. With the climate-risk pack enabled, this read-only call asks the
server what inference makes of the NGFS *Delayed transition* scenario (`packs/climate-risk/samples/climate-scenario/NGFS-DELAYED.json`),
ignoring the pack's own Sutra:

```bash
curl -s "http://localhost:18480/api/v1/studio/inferred/climate-scenario/NGFS-DELAYED?name=my-scenario"
```

You should see a complete YAML Sutra, ready to save as `my-scenario.v1.sutra.yaml` (option lines inside a
panel, such as `rows`, `label` and `value` here, may come in a different order from one run to the next):

```yaml
# Started from what inference makes of climate-scenario NGFS-DELAYED. Edit freely.
rachana: 1
sutra: my-scenario
version: 1
description: Started from inference for climate-scenario. Edit freely.
match: { kind: climate-scenario, priority: 1 }
title: { pill: "Climate scenario", id: "$.name" }
strip:
  - { label: "Scenario ID", bind: "$.scenarioId" }
  - { label: "Category", bind: "$.category" }
  - { label: "Warming", bind: "$.warming", fmt: "amount0" }
  - { label: "Carbon2030", bind: "$.carbon2030", fmt: "amount0" }
  - { label: "Carbon2050", bind: "$.carbon2050", fmt: "amount0" }
  - { label: "Source", bind: "$.source" }
panels:
  - id: shocks
    kind: table
    title: "Shocks"
    key: "F2"
    rows: "$.shocks"
    columns:
      - { label: "Sector", bind: "@.sector" }
      - { label: "Shock", bind: "@.shock", fmt: "amount0" }
  - id: built
    kind: provenance
    title: "How this view was built"
  - id: carbonpath
    kind: hbar
    title: "Carbon path"
    area: right
    rows: "$.carbonPath"
    value: "price"
    label: "year"
    fmt: "amount0"
  - id: refs
    kind: links
    title: "Linked entities"
    code: "REFS"
    area: right
keys: { F9: "raw" }
```

Reading it against the rules:

- The title uses `$.name`, not `$.scenarioId`: the identifier fields are `climateScenarioId`, `id`, `code`,
  `name`, and only `name` exists. `scenarioId` therefore lands in the strip.
- `carbonPath` (5 rows of `year` text + `price`) becomes **horizontal bars**: `price` is money, so the
  distribution rule scores 0.80, above the table's 0.60.
- `shocks` (8 rows of `sector` + `shock`) becomes a **table**: `shock` is not money, so bars score only 0.45,
  below the table's 0.63.
- `shock` values such as `-0.0164` get `amount0` and would show as `-0`: nothing tells inference they are
  percentages.

The pack's hand-written Sutra (`packs/climate-risk/sutras/climate/climate-scenario.v1.sutra.yaml`) fixes each
of these: it titles the view by `$.scenarioId`, draws the carbon path as a **line** over `year`, and shows the
shocks with `fmt: pct0, tone: sign`. That is the normal workflow: start from inference, then correct what a
person knows better. Open `NGFS NGFS-DELAYED <GO>` in the terminal to see the result.

## How the engine decides, step by step

`InferenceEngine.infer` does the same five things for every document. Knowing them makes any inferred layout
predictable.

1. **Every rule proposes candidates.** The six rules (below) look at the document and add *candidates*: a
   proposed panel, a score between 0 and 1, the rule's name, a one-line reason, and the document path the
   panel shows (`$.schedule`). One path usually gets several candidates (a table **and** a ladder for a dated
   array).
2. **One winner per path.** For each path the highest score wins; on a tie the candidate proposed first is
   kept. The rules run in this order: `LegsRule`, `HomogeneousArrayRule`, `TermStructureRule`,
   `DistributionRule`, `TimeSeriesRule`, `NestedObjectRule`.
3. **Split by column, keep the best.** Winners go to the main column or the right column (each rule decides
   which). The main column keeps the best 6 (`limits.main`), the right column the best 3 (`limits.right` 4,
   minus one place for *Linked entities*). Equal scores keep their order from step 2.
4. **Put them back in reading order.** Kept panels are sorted by their position in the document. Arrays are
   numbered among arrays and objects among all fields, so the order is close to, but not exactly, the
   document's.
5. **Finish the layout.** The first five main panels get `F2`–`F6`; *How this view was built* closes the main
   column; *Linked entities* goes after the first right-column panel (or alone); the title and the strip are
   inferred; `F9` opens the raw JSON. The result is a Sutra named `inferred-<kind>`, version 0, labelled
   **inference only**.

Each kept panel records its explanation as `<Rule> <score>: <reason>`, for example
`TimeSeriesRule 0.72: 20 dated rows in order`.

The result is cached per (Sutra, kind, **shape fingerprint**). The fingerprint describes the document's
structure (field names sorted, value types, the union of an array's element shapes); neither values nor array
lengths change it. So two trades of the same shape share one inferred layout, and inference runs once per shape
(`drishti.engine.layout-cache-size`). One consequence: decisions that depend on sizes are made by the **first**
document of a shape seen since the cache was last cleared. A ladder's `highlight: "#index == 19"` was written for
a 20-row history; another document of the same shape with 25 rows gets the same layout and lights row 20. The
cache is cleared by any Sutra reload and from Admin → Caches. A Sutra avoids the problem with an expression such as
`highlight: "#index == size($.history) - 1"`.

## A worked example on real data: KRI-COMM-1

This document is a key risk indicator from the operational-risk pack. Fetch it as the server holds it:

```bash
curl -s http://localhost:18480/api/v1/entities/key-risk-indicator/KRI-COMM-1/raw
```

You should see (the `data` part, with the history shortened):

```json
{
  "kriId": "KRI-COMM-1",
  "name": "Trade breaks older than 2 days",
  "deskName": "Commodities",
  "value": 8.83,
  "amber": 20.0,
  "red": 32.0,
  "status": "Green",
  "history": [
    { "date": "2026-09-03", "value": 0 },
    { "date": "2026-09-04", "value": 2.46 },
    …
    { "date": "2026-09-30", "value": 8.83 }
  ],
  "definition": { "unit": "count", "frequency": "Daily", "owner": "S. Okafor", "source": "Operations data mart" },
  "desk": "DESK-COMM"
}
```

Ask what inference alone makes of it (the pack's own Sutra is ignored):

```bash
curl -s "http://localhost:18480/api/v1/studio/inferred/key-risk-indicator/KRI-COMM-1?name=kri-draft"
```

You should see this Sutra (option lines inside a panel may come in a different order):

```yaml
# Started from what inference makes of key-risk-indicator KRI-COMM-1. Edit freely.
rachana: 1
sutra: kri-draft
version: 1
description: Started from inference for key-risk-indicator. Edit freely.
match: { kind: key-risk-indicator, priority: 1 }
title: { pill: "Key risk indicator", id: "$.name" }
strip:
  - { label: "Kri ID", bind: "$.kriId" }
  - { label: "Desk name", bind: "$.deskName" }
  - { label: "Value", bind: "$.value", fmt: "amount0" }
  - { label: "Amber", bind: "$.amber", fmt: "amount0" }
  - { label: "Red", bind: "$.red", fmt: "amount0" }
  - { label: "Status", bind: "$.status" }
  - { label: "Desk", bind: "$.desk" }
panels:
  - id: history
    kind: ladder
    title: "History"
    key: "F2"
    highlight: "#index == 19"
    rows: "$.history"
    columns:
      - { label: "Date", bind: "@.date", fmt: "date" }
      - { label: "Value", bind: "@.value", fmt: "amount0" }
  - id: built
    kind: provenance
    title: "How this view was built"
  - id: definition
    kind: kv
    title: "Definition"
    area: right
    rows: "$.definition"
    columns:
      - { label: "Unit", bind: "@.unit" }
      - { label: "Frequency", bind: "@.frequency" }
      - { label: "Owner", bind: "@.owner" }
      - { label: "Source", bind: "@.source" }
  - id: refs
    kind: links
    title: "Linked entities"
    code: "REFS"
    area: right
keys: { F9: "raw" }
```

Why each part came out this way:

| Part | Result | Reason |
|---|---|---|
| Title id | `$.name` | the identifier fields are `keyRiskIndicatorId`, `id`, `code`, `name`; only `name` exists. `kriId` would only be used if none of them did. |
| Pill | `Key risk indicator` | the kind in words; there is no `product` field |
| Strip | all 7 remaining scalars | fewer than 8 candidates, so all are kept, in document order |
| `value` | `amount0` | its name ends in `value`, which the core calls *money*, so `8.83` shows as `9` |
| `amber`, `red` | `amount0` | no pattern matches; a number gets `amount0` |
| `kriId` | label `Kri ID` | `ID` is a core acronym; `Kri` is not |
| `$.history` | **ladder**, `F2` | candidates: ladder 0.72 (TimeSeriesRule: 20 rows led by an ISO date, in order), table 0.65 (HomogeneousArrayRule: 0.55 + 0.01 × 10). No line chart: the dates are not *tenors* (`1M`, `5Y`), and DistributionRule takes at most 12 rows. |
| `$.definition` | **kv**, right | NestedObjectRule 0.55: 4 scalar fields, 6 or fewer, so the right column |

In the console this view would read `[Key risk indicator] Trade breaks older than 2 days` with the strip
`Kri ID KRI-COMM-1 · Desk name Commodities · Value 9 · Amber 20 · Red 32 · Status Green · Desk DESK-COMM`, a
20-row ladder with the last row lit, the definition on the right, and `DESK-COMM` under *Linked entities*.

## A bigger real example: trade MX-20000001

A vanilla swap has 39 top-level fields, five arrays and ten nested objects, so it shows the limits at work.

```bash
curl -s "http://localhost:18480/api/v1/studio/inferred/trade/MX-20000001?name=swap-draft"
```

What happens to each part of the document (with the banking packs enabled, which add no roles for trade fields):

| Path | Candidates (score) | Kept? | Why |
|---|---|---|---|
| `$.legs` (2 objects × 21 fields) | tabs 0.90, table 0.57 | **tabs**, main | LegsRule: 2–4 similar objects with 5+ fields |
| `$.legs[0].cashflows` (7 rows) | table 0.62 | **table**, main | arrays inside the first leg are visited too; titled *Cashflows · Legs 1* |
| `$.schedule` (35 rows) | ladder 0.72, table 0.65 | **ladder**, main | dated rows in order; the last (`#index == 34`) is lit |
| `$.pnlHistory` (20 rows) | ladder 0.72, table 0.65 | **ladder**, main | same; `pnl` is *signed money*, so the column gets `signed0`, a tone and a total |
| `$.sensitivities` (7 rows of `bucket`, `dv01`) | line 0.82, table 0.62, hbar 0.45 | **line**, right | `bucket` values are tenors (`3M` … `7Y`); `dv01` is not money to the core hints, so the line keeps 0.82 and bars only get 0.45 |
| `$.execution` (8 scalars) | kv 0.55 | **kv**, main | more than 6 scalars: main column |
| `$.clearing` (7 scalars) | kv 0.55 | **kv**, main | sixth and last main panel |
| `$.regulatory`, `$.valuation` | kv 0.55 | dropped | the main column is full (6); ties are kept in the order proposed |
| `$.terms` (4), `$.lifecycle` (6) | kv 0.55 | **kv**, right | with the line, the three right-column panels |
| `$.confirmation`, `$.settlementInstructions` | kv 0.55 | dropped | the right column is full (3) |
| `$.counterparty` | none | | an object of just `id` and `name` is a reference, not a panel; it gives the title's `with` |
| `$.risk` | none | | one field only |

The strip keeps eight of the top-level scalars by role weight: `Product name`, `Status`, `Currency`, `MTM currency`
(labels, 65), the three dates (60) and `Notional` (a plain number, 30, ahead of `mtm` because it comes first).
`mtm` itself does not make the strip: without the finance pack's roles it is just a number.

Things a person would change, and that the trading pack's `irs-fixfloat` Sutra does change:

- `MTM` and `DV01` belong in the strip, signed and coloured;
- `Rate` should be `pct4`, not `pct2`; `Year fraction` with `amount0` shows `1` or `0`;
- the sensitivities read better as **bars**, the discount curve (another entity) as a line;
- the schedule's highlighted row should be the next payment (`#index == $.nextIndex`), not the last.

The finance pack's hints make inference itself closer: its roles make `mtm`, `pv` and `dv01` signed money, so
`MTM` is emphasised in the strip and `$.sensitivities` scores hbar 0.80 against line 0.50.

## From an inferred layout to a Sutra

The usual way to write a Sutra for a new kind is to start from inference and correct it.

1. **Get the draft.** In Studio, enter the kind and id and press **Start from inference**, or fetch it:

   ```bash
   curl -s "http://localhost:18480/api/v1/studio/inferred/key-risk-indicator/KRI-COMM-1?name=kri-mine" > kri-mine.v1.sutra.yaml
   ```

   The draft is a complete YAML Sutra (it starts with `rachana: 1`) with `priority: 1`, so it loses to any pack
   Sutra at the normal 10 until you raise it. Replace its `description:` with one sentence about the layout, and
   put anything longer (why a panel is there, what to check) in `notes: |`.
2. **Fix the identity.** Title the view by its real identifier and say what it is:
   `title: { pill: Operational risk · Key risk indicator, id: $.kriId }`.
3. **Fix the formats inference could not know.** `value`, `amber` and `red` are measurements, not money:
   `fmt: price2`. Colour the status: `tone: status`.
4. **Choose better panels.** A history is a chart, not a ladder:

   ```yaml
     - id: history
       kind: line
       title: Last 20 business days
       key: F2
       rows: $.history
       x: date
       y: value
       fmt: price2
   ```

5. **Drop what inference had to spell out.** A kv panel over an object can leave its columns out; inference
   fills them on every view (and picks up new fields when the source adds them):
   `- { id: definition, kind: kv, title: Definition, area: right, rows: $.definition }`.
6. **Add keys.** `keys: { F7: "link($.desk, 'desk')", F8: impact, F9: raw }`.
7. **Preview** (Ctrl+Enter), then **save**: with governance on it becomes a proposal for an approver. Or put the
   file in a Sutra directory (`drishti.rachana.dirs`, or a pack's `sutras/` folder); it loads at once.

After these steps you have, nearly line for line, the operational-risk pack's own
`packs/operational-risk/sutras/operational-and-non-financial-risk/key-risk-indicator.v1.sutra.yaml`. Check the
result:

```bash
curl -s http://localhost:18480/api/v1/views/key-risk-indicator/KRI-COMM-1 | python3 -c "
import json, sys
v = json.load(sys.stdin)
print(v['provenance']['layout'])
print([(s['label'], s['text']) for s in v['strip']])"
```

You should see `Sutra key-risk-indicator v1 + inference` and
`[('Indicator', 'Trade breaks older than 2 days'), ('Desk', 'Commodities'), ('Value', '8.83'), ('Amber', '20.00'), ('Red', '32.00'), ('Status', 'Green')]`.
The `+ inference` is there because the *Definition* panel's fields and the *Linked entities* are filled in at view time.

## Seeing why a panel was chosen

**In the terminal.** Any panel laid out (or completed) by inference has a small **inferred** tag in its header.
Hover over it: the tooltip gives the rule, the score and the reason, for example
`HomogeneousArrayRule 0.60: 5 rows × 4 columns` or `ColumnInference: 5 fields from $.kyc`.

**In "How this view was built"** (the last panel of the main column), *Layout* says which case applies:

| Layout says | Meaning |
|---|---|
| `inference only` | no Sutra matched this entity; everything was inferred |
| `Sutra counterparty v1 + inference` | the Sutra `counterparty`, version 1, with gaps filled by inference (this also appears when the Sutra has a *Linked entities* panel, which is always computed) |
| `Sutra climate-scenario v1` | the Sutra alone |

**Through the API.** Each panel in a view carries `inferred` and `explanation`:

```bash
curl -s http://localhost:18480/api/v1/views/counterparty/CP-NORTHBRIDGE | python3 -c "
import json, sys
v = json.load(sys.stdin)
print(v['provenance']['layout'])
for p in v['panels']:
    if p['inferred']:
        print(p['id'], p['kind'], '-', p['explanation'])"
```

You should see:

```
Sutra counterparty v1 + inference
kyc kv - ColumnInference: 5 fields from $.kyc
```

**In Studio.** Type an entity (kind and id) and press **Start from inference** to load the inferred layout as a
Sutra you can edit; see the *Sutra Studio* tutorial in help.

## Sutra and inference together

The Sutra always wins. Inference only fills in:

- the columns of a `table` or `ladder` panel that has `rows:` but no `columns:` (every scalar field present in
  at least 60% of rows, up to 9 columns);
- the fields of a `kv` panel that has `rows:` but no `columns:` (every scalar field of the object, up to 16; a
  nested object with a `name` shows its name);
- the `kv` body of a `tabs` panel whose body states no columns (the fields of the first element);
- labels nobody wrote: `bind: "@.payDate"` with no `label` reads **Pay date**.

**Example (real).** The counterparty Sutra in `packs/banking-core/sutras/counterparty-and-legal/counterparty.v1.sutra.yaml`
declares its KYC panel without columns:

```yaml
  - id: kyc
    kind: kv
    title: KYC and classification
    area: right
    rows: $.kyc
```

The document has `"kyc": {"status": "Approved", "riskRating": "Enhanced", "lastReview": "2026-02-11",
"emirClassification": "FC", "pd1y": 0.0042}`, so inference supplies five fields: Status, Risk rating, Last
review, EMIR classification, Pd1y. Type `CPTY CP-NORTHBRIDGE <GO>` and hover over the panel's *inferred* tag.

Inference never adds to columns you wrote: as soon as a panel lists one column, it shows exactly the columns
listed. To take control of a panel, list its columns yourself; to let inference choose them, leave `columns:`
out. `infer: true` only marks a panel with the *inferred* tag; it does not make inference add columns to a panel
that has some. Panels of other kinds (`status`, `hbar`, charts) are never filled. To lay out a whole kind by hand,
write a Sutra whose `match` names the kind; see the [Rachana reference](../guides/RACHANA_REFERENCE.md) and the
[Rachana guide](../guides/RACHANA_GUIDE.md).

## The rules

| Rule | Recognises | Proposes | Where | Score |
|---|---|---|---|---|
| `LegsRule` | a top-level array of 2–4 objects whose first element has at least 5 fields (legs, tranches) | `tabs`, one per element, each an inferred `kv` | main | 0.90 |
| `TermStructureRule` | 3 or more objects with a text field matching the tenor pattern (`1M`, `5Y`, `Q3`, `H1`) and numbers | `area` with up to 4 series (two or more numbers), or `line` (one number) | area: main; line: right | area 0.85; line 0.82, or 0.50 when the number is money-like |
| `DistributionRule` | 2–12 objects with exactly two scalar fields, label + number | `hbar` | right | 0.80 when the number is money-like, else 0.45 |
| `TimeSeriesRule` | 3 or more objects whose first field is an ISO date, in date order | `ladder`, latest row highlighted | main | 0.72 |
| `HomogeneousArrayRule` | any array of objects | `table`, with a *Total* row when a column is signed money or a money field named `…amount…` | main | 0.55 + 0.01 per row, up to 0.65 |
| `NestedObjectRule` | a top-level object with at least 2 scalar fields (an object of just `name` plus one field is treated as a link, not a panel) | `kv` | right when it has 6 or fewer scalars, else main | 0.55 |

"Money-like" means the field's role name contains `money` (`money` or `signed-money`).

Rules look at top-level arrays, and at arrays inside the **first** element of a small (up to 4) array of
objects, for example `$.legs[0].cashflows`. Such a panel is titled *Cashflows · Legs 1*.

### Packing

| Area | Limit | Rule |
|---|---|---|
| Strip | 8 | top-level scalars ranked by role weight, shown in document order; the first signed-money field whose name contains `mtm` is emphasised |
| Main column | 6 | the best-scoring main panels, in document order; the first five get `F2`–`F6`; *How this view was built* closes the column |
| Right column | 3 + links | the best-scoring right panels, in document order; *Linked entities* goes after the first one (or alone) |
| Table columns | 9 | the first column always, then the heaviest by role weight |
| Key/value fields | 16 | in document order |

The limits are the `limits:` block of the semantic hints file (`strip: 8, right: 4, main: 6, kvMaxFields: 16,
tableMaxColumns: 9`; `right` counts the *Linked entities* panel).

## Semantic hints

Semantic hints tell inference what a field **means** from its name and a sample value: a format, a tone (colour
by sign) and a weight for the strip. They live in YAML:

| File | Role |
|---|---|
| `drishti-inference/src/main/resources/inference/semantics.yaml` | the core hints, domain-neutral |
| `packs/<pack>/config/semantics.yaml` (or the pack's `semantics:` key) | a pack's hints, tried **before** the core's; with several packs, the more specific pack first |
| the file named by `drishti.inference.semantics-file` | a site file that **replaces** the core hints |

### Core roles

Roles are tried in order; the first whose pattern matches the field name (case-insensitive) wins.

| Role | Matches field names that… | Format | Weight | Extra condition |
|---|---|---|---|---|
| `signed-money` | the whole name (or the part after `_`) is `pnl`, `profit`, `loss`, `change`, `delta` or `variance`; or the name ends in `pnl` or `change` | `signed0`, tone by sign | 90 | number |
| `money` | `amount`, `cost`, `balance`, `revenue`, `budget`, or ends in `value`, `price`, `total` | `amount0` | 80 | number |
| `percent` | `utilisation`/`utilization`, `ratio`, `percent`, or ends in `share`, `pct` | `pct0` | 50 | number |
| `rate` | ends in `rate` | `pct2` | 70 | a number between −1 and 1 |
| `date` | ends in `date`, `at`, `time`; starts with `date`; contains `expiry`, `asof`, `due` | `date` | 60 | an ISO date text |
| `count` | `count`, `quantity`, `qty`, `units`, `items`, or exactly `n` | `amount0` | 55 | number |
| `label` | `name`, `label`, `type`, `status`, `category`, `currency`, `ccy`, `owner`, `region`, or ends in `code` | none | 65 | |

If nothing matches: an ISO date is a *date* (weight 40), another number is a *number* (`amount0`, weight 30),
and anything else is *plain* (weight 10).

The same file sets:

- `tenorPattern`: what counts as a chart axis (`1M`, `5Y`, `H1`, `Q3`, `2026-Q3`). Pack patterns are added to
  it (the finance pack adds `ON`, `TN`, `SN` and contract months such as `Z6`);
- `datePattern`: what counts as a date (`yyyy-mm-dd` at the start);
- `idFields`: the title's identifier fields (`{kind}Id`, `id`, `code`, `name`). Pack entries are tried after
  these;
- `acronyms` and `labels`: how names read. With `acronyms: [UTI]`, `uti` reads **UTI**; with
  `labels: { mtm: MTM (USD) }`, `mtm` reads **MTM (USD)**. Pack entries win over the core's;
- `limits`: the packing limits above.

### What the shipped packs add

| Pack | Adds |
|---|---|
| `finance` | roles for `mtm`, `pv`, `dv01` (signed money), `notional`, `exposure`, `pfe` (money), rates, prices, pips, discount factors; contract-month tenors; `tradeId` as an identifier; finance acronyms |
| `logistics` | roles for delays (hours), weights, temperatures, speed in knots, TEU capacity, ETAs; `shipmentId`, `containerId`, `vesselId`, `portId` as identifiers |
| `banking-core` (and every banking pack through it) | acronyms (`MTM`, `DV01`, `CVA`, `ISDA`, `LEI`, …) and explicit labels (`mtm` → **MTM (USD)**); no roles |

So with the banking packs but not `finance`, a field named `mtm` is a plain *number* to inference. That is
fine in practice, because every banking kind ships with a Sutra; it matters only for new kinds.

### Adding hints for your pack

Create `packs/<your-pack>/config/semantics.yaml` (the default location) with only what you need:

```yaml
# Tried before the core roles: the first matching pattern wins.
roles:
  - { role: signed-money, pattern: "(^|_)(netgain|shock)$", fmt: signed0, tone: sign, weight: 90 }
  - { role: money,        pattern: "premium|fee", fmt: amount0, weight: 80 }
  - { role: percent,      pattern: "shock$", fmt: pct2, weight: 50 }
tenorPattern: "^(M\\d{1,2})$"        # M1, M12 also make chart axes
acronyms: [NGFS, PCAF]
labels:
  carbon2030: Carbon price 2030
```

Keys of a role:

| Key | Meaning |
|---|---|
| `role` | a name; `money` or `signed-money` in it makes the field money-like (bar scores, line scores, totals) |
| `pattern` | a case-insensitive regex, searched in the field name |
| `fmt` | a format from the Rachana reference (`amount0`, `signed0`, `pct0`, `pct2`, `date`, …); a role with a format other than `date` applies only to numbers |
| `tone` | `sign` colours negatives and positives |
| `weight` | rank for the strip (higher first); default 10 |
| `fractionOnly` | only when the value is a number between −1 and 1 |
| `dateOnly` | only when the value is an ISO date text |

Restart the server after changing a semantics file: hints are read once at start-up.

### Replacing the core hints for a site

1. Copy `drishti-inference/src/main/resources/inference/semantics.yaml` to, say, `/etc/drishti/semantics.yaml`,
   and edit it. Keep every block, including `limits:` and `idFields:`; a missing block falls back to built-in
   defaults or to nothing.
2. Point the server at it, either in `application.local.yaml` in the server's working directory:

   ```yaml
   drishti:
     inference:
       semantics-file: /etc/drishti/semantics.yaml
   ```

   or on the command line:

   ```bash
   java -jar drishti-server/target/drishti-server-1.12.0-exec.jar --drishti.inference.semantics-file=/etc/drishti/semantics.yaml
   ```
3. Restart the server, then check a view with no Sutra (or Studio's **Start from inference**).

Pack hints are still tried before the site file. If the path is not a readable file, the server silently uses
the bundled hints, so check the path when your change seems to have no effect.

## See also

- [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md): panel kinds, formats, `infer: true`.
- [PACKS.md](../guides/PACKS.md): where a pack's `config/semantics.yaml` fits.
- [ARCHITECTURE.md](ARCHITECTURE.md): the view pipeline and its caches.
