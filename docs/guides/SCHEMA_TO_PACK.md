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
# Schema to pack: generate a pack from JSON Schema

You have **JSON Schema files** (one per kind of thing you hold: trades, counterparties, books ...), and perhaps a **folder of JSON Lines**
with real documents. Drishti turns them into a **pack**: a screen (Sutra) per kind, with the right labels, formats, links and help text,
tests, and a bundle you can deploy. It does it two ways with the same rules:

- **The page**: **Build → New pack** (`/build/pack/new`). Five steps, a preview of every Sutra, a download or a deploy.
- **The command line**: `drishti.py sutra design --schema ...` and `drishti.py pack make --schema ...` ([CLI guide](CLI_GUIDE.md)).

```text
schemas (JSON Schema, JSON or YAML)  +  JSON Lines (optional)
        │  plan: kind, key, links, labels, match column, date, strip, mnemonic          (this guide: "The rules")
        ▼
sample documents: real ones first, then the schema's examples, then synthetic ones
        │  the server's auto-designer drafts a Sutra from each group of samples
        ▼
the draft is labelled from the schema (titles, descriptions, formats, links, strip)
        ▼
pack folder: pack.yaml, sutras/, tests/, samples/, config/about.yaml, README.md   →  bundle (.tar.gz + .sha256)  →  deploy
```

> **Real data wins.** A schema says what a document *may* be; real documents say what it *is*. Where both are given the key must be unique in
> the data, the business date is found in the data, a field the schema does not know is reported, and every Sutra is tested on real
> documents. The schema then adds what data cannot: names, descriptions, units, links, enumerations and the shape of variants.

## Contents

1. [Quick start](#quick-start)
2. [The rules, one by one](#the-rules-one-by-one)
3. [Annotations: `x-drishti-*`](#annotations-x-drishti-)
4. [What is read from a schema](#what-is-read-from-a-schema)
5. [Samples and tests: real, example, synthetic](#samples-and-tests-real-example-synthetic)
6. [The New pack page, step by step](#the-new-pack-page-step-by-step)
7. [The command line](#the-command-line)
8. [The pack that comes out](#the-pack-that-comes-out)
9. [Limits and settings](#limits-and-settings)
10. [Troubleshooting](#troubleshooting)
11. [Not yet, and what this leaves for later](#not-yet-and-what-this-leaves-for-later)
12. [Appendix: A deep trading JSON](#appendix-a-deep-trading-json)

## Quick start

The example schemas of this guide are in `docs/guides/examples/schemas/`: `trade`, `counterparty`, `book` and `instrument`, with small JSON
Lines files under `data/`.

```bash
# schemas alone: samples are synthetic, every Sutra is checked, one folder comes out
python3 tools/drishti.py pack make --schema docs/guides/examples/schemas --name desk-pack --yes

# schemas plus real documents: real samples win, the schemas add labels, descriptions and links
python3 tools/drishti.py pack make --schema docs/guides/examples/schemas docs/guides/examples/schemas/data --name desk-pack --store files --yes
```

Or open **Build → New pack**, choose the four schema files and the `data` folder, and press Next five times.

## The rules, one by one

Every decision below is made by `drishti-console/core/schemakit/plan.py`, shown with its reason in the page's cards and in the command
line's summary, and can be changed there (page) or with a flag or an annotation (anywhere).

### The kind

The **kind** is the name of the thing a document describes (`trade`). In this order:

1. `x-drishti-kind` at the top of the schema;
2. the schema's `title`, when it is at most three words (`"Netting Set"` becomes `netting-set`; `"TradeConfirmation"` becomes `trade-confirmation`);
3. the last segment of the schema's `$id` (`https://example.com/schemas/ledger-entry.json` becomes `ledger-entry`);
4. the file name (`my-thing.schema.json` becomes `my-thing`).

Kinds are letters, digits and `. _ -` (up to 64). Two schemas that give the same kind get `-2`, and a warning. A JSON Lines file with no schema becomes
a kind named after its file (`trades.jsonl` is first matched to a schema kind: `trade`, `trades` and `Trades` all fit).

> A kind belongs to one pack. If the server already runs a pack that defines the kind, the page says so (step 2) and refuses to go on (step 3)
> until you rename it, unless the new pack **is** that pack (a new version keeps its own kinds and mnemonics).

### The key

The key is the field whose value names one document (`tradeId`). It becomes the id in the title and in the pick lists.

1. A property with `x-drishti-role: key` (or `x-drishti-key: true`).
2. Otherwise the candidates are the top-level string or integer properties named `id`, `_id`, `*Id`, `*_id`, `*Key`, `uuid`, or with `format: uuid`, in any case (`TRADE_ID`, `COUNTERPARTY_UEN`, `trade_ref` too); an integer id is a label, never an amount, a strip figure or a total;
   **required** ones first, and, when real documents are given, only those that are **unique** in them.
3. Of the candidates, in order: one named `id`; one named `<kind>Id` or `<kind>_id`; one named after what such a document usually is (`TRADE_ID`, `dealId`, `contractId`, before a `TRANSACTION_ID` that changes on every amendment); the **only** one that does not refer to another kind of the batch.
4. If several remain, the key is **ambiguous**: the first is used, a warning names them all, the page marks the card amber and the command line
   **asks** (when it runs in a terminal; `--yes` takes the first). Choose with `x-drishti-role: key`, `--key kind=FIELD`, or the card.
5. No candidate at all: no key. The page will not go on; `pack make` stops and says `--key`.

### Links

A link makes a field a way to open another kind (the *Linked entities* panel; `link()` in the details). A field is a link when:

- it has `x-drishti-link: <kind>` (or the card says so), or
- it is a string (or an array of strings) named `<kind>Id`, `<kind>_id`, `<kind>Ids`, `<kind>Key`, `<kind>Ref` ... where `<kind>` (singular or plural, or the
  kind's name before you renamed it) is **another kind of the batch**, or is named exactly like one (`book`, `counterparty`), or
- real documents are given and **at least 90% of its distinct values (three or more) are keys of another kind**.

Links go into `pack.yaml` under `graph.fields`. **A link field name means one kind everywhere**: if another loaded pack uses `bookId` for a different
kind the server refuses to start, so the page and the command line warn when the same name is used twice for different kinds in one pack.

### Labels, panel titles and descriptions

| Where | From |
|---|---|
| A field's label | `x-drishti-label`, else `title`, else the name made readable (`tradeDate` → *Trade date*, `counterparty_id` → *Counterparty ID*, `TRADE_ID` → *Trade ID*, `BUY_SELL_CODE` → *Buy sell code*, `mtm` → *MTM*, `dv01` → *DV01*), plus ` (unit)` from `x-drishti-unit` |
| A panel's title (a table, a group) | the title of the array or object field (`legs` with `"title": "Legs"`) |
| The Sutra's `description` | the kind's description, or the variant's |
| *About this page*, the sentence | the kind's `description`: `${$.tradeId}: A bilateral derivative trade ...` (it is a template over the document) |
| *About this page*, the glossary | every field the Sutra shows: `title` as the term, `description` as the meaning, `x-drishti-unit`, `x-drishti-values` for the meaning of each enum value |

A field with no description gets `TODO: what this field means.` so the pack's coverage check (`DRS-2045` to `2047`) names it. Fix it in the schema and
generate again: the schema stays the one source of the words.

### Formats

| Schema | Format in the Sutra |
|---|---|
| `x-drishti-format: <name>` | that named format, always (`money` is `amount2`, `percent` is `pct2`; any name from the [Sutra guide](SUTRA_DEVELOPER_GUIDE.md) such as `signed0`, `compact`, `bp1`) |
| `format: date`, `date-time` | `date` |
| number between 0 and 1 (`minimum`/`maximum`), or unit `%` | `pct2` |
| other numbers | a guess from the name (`pnl`, `mtm`, `delta` → `signed0`; `price`, `rate`, `amount`, `notional` → `amount2`; else `amount0`); integers `amount0` except years, versions and counts |

Only the first three **override** the auto-designer's own choice (which looks at the values); a guess from the name does not.

### Enumerations and statuses

A property with `enum` is a **dimension** (a category). One whose name is `status`, `state`, `stage`, `phase` or `lifecycle` is a **status**: first in the strip, coloured by
the status tone. Every enumerated top-level field is also listed as a **dropdown control suggestion** in the plan and in `MANIFEST.json`
(`controls`): *a hook for the controls feature, nothing is written to a Sutra for it yet* (see [Not yet](#not-yet-and-what-this-leaves-for-later)).
`x-drishti-control: radio|dropdown|text` records another suggestion.

### Arrays, nested objects and maps

The auto-designer decides the panel kinds from the shape of the samples; the schema makes sure the samples have that shape:

- an **array of objects** (`legs`) gets 2 or 3 rows in every sample, so it becomes a **table**, a ladder or tabs by the inference rules ([Panel kinds](PANELS.md));
- a **nested object** (`terms`) becomes a group of fields; its numbers can reach the strip (`terms.spread`);
- a **map of numbers** (`additionalProperties: {type: number}`, a curve by tenor) gets keys from `propertyNames.enum` or `x-drishti-keys`, else the tenors
  `1M 3M 6M 1Y 2Y 5Y 10Y`, so it can become a curve or bars;
- an **array of numbers** gets three values.

### The strip

The strip is the row of figures across the top of a view. Up to six (`--strip N`, the page's *Figures in the strip*):

1. the first **status** field;
2. numbers, most important first: **required and described** first, then required, then described, then the rest; ties by how shallow the field is, then schema order;
3. one required **date**.

A property can say `x-drishti-strip: true` (always in) or `false` (never), and `x-drishti-importance: N` adds to its weight. References, years and
versions are not figures. The first figure is emphasised.

### Splitting a kind into several Sutras

**One Sutra per variant or per value**, each with its own `match.where` and its own samples, plus a catch-all `<kind>-default` at priority 1 (a document
of a value you did not think of still opens). With real data the catch-all is made **only when some rows have no value** in the split column (the card
says how many); when every row has a type, N types make exactly N Sutras, each named after its type (`comswap`, `cash`) and designed from its own
type's rows. The card lists them, "5 types → 5 Sutras", with each type's row count, the rare ones too (every row of every file is counted, not a
sample). A column named like a type (`PRODUCT_TYPE`, `productType`, `recordType`, `assetClass`) with 2 to 40 values is chosen by itself; a second
split column is under *Advanced* and warns of the cross product.

- **`oneOf` / `anyOf` of objects with a discriminator**: the `discriminator.propertyName` (OpenAPI style), `x-drishti-discriminator`, or a property
  every branch fixes to a different `const` (or one-value `enum`), preferring `type`, `kind`, `productType`, `category`. One Sutra per branch
  (`instrument-bond`, `instrument-equity`), each tested on documents of its own branch (a bond has a `coupon` and no `ticker`).
- **A match column**: a property with `x-drishti-match: true`, or the one you choose on the card (*Split into Sutras by*, with the number of Sutras it makes) or with
  `--match`. One Sutra per enum value, or per value found in the data. Two columns make the cross product (up to 40).
- Otherwise **one Sutra** per kind, named `<kind>-default`.

A match column with many values makes many Sutras: the page refuses more than `builder.pack_max_sutras` (200).

### The business date

Used to partition real data by day (a dated store). From `x-drishti-role: date`; else a date field named like a business date (`businessDate`, `asOf`,
`reportingDate`, `date`, `valuationDate`, `tradeDate`); else the one required date field. With several candidates and none named like a date the card asks.

Real documents with **no business-date field** (an export of the book as it is now: trade, start and maturity dates, none of them the day of the
data) are a **snapshot**: the card's *Business date* says *The day it is loaded (snapshot)*, the pack writes `ingest: {key: TRADE_ID, snapshot: true}`,
and every load is stored under the business date of the day it is loaded, so yesterday's book stays as it was. Choose a date field on the card
(or `--date kind=FIELD`) to file each document under its own date instead; `--as-of 2026-10-16` loads a snapshot under another day. Schemas
alone with no date: no dated store, the pack serves its samples.

### Mnemonic, connectors and the pack

The **mnemonic** (`TRD <GO>`) is the initials of a multi-word kind or its first three letters, made unique within the batch and **against the packs already on
the server**. **Connectors**: a pack names its sources by a **logical connector name** only, never by a host or a path; those are the connector's own settings on the
server. A kind with real, dated data gets a `file` or `delta` connector named `<pack>-store` (change it per kind); a kind without data needs none and serves its samples.
The generated `pack.yaml` **names** its connectors and **suggests** their definitions, as [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md) describes:

```yaml
connectors: [desk-pack-store]            # the logical names the pack reads through
routes: {trade: desk-pack-store}         # which connector answers each kind
connector-templates:                     # the connection settings, as the pack's suggestion
  desk-pack-store:
    plugin: delta
    settings: {root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: desk-pack, ...}
```

At the server's first start with the pack on, it writes the suggestion to `config/connectors/desk-pack-store.yaml` once and never overwrites it; from
then on the site edits that file (or Admin → Connectors), several packs may name the same connector, and the pack never carries a host, a path or a
credential of the site's. The same holds for `pack make` and `drishti.py pack new --lake`.

### What is reported rather than decided

Unresolved `$ref`s (open object, warning), a schema file that is not JSON or YAML (named, others carry on), kinds with the same name, an ambiguous key, a link to a kind that is not in the batch,
fields in real documents that the schema does not describe, fields with no description, Sutras tested on synthetic samples only.

## Annotations: `x-drishti-*`

Put these on a property (or the top of the schema). Both spellings work: `"x-drishti-role": "key"` and `"x-drishti": {"role": "key"}` (the form `sutra shape`
already writes). Unknown ones are ignored.

| Annotation | On | Meaning |
|---|---|---|
| `x-drishti-kind` | top | the kind's name |
| `x-drishti-role` | property | `key` (the id), `date` (the business date), `match` (a split column) |
| `x-drishti-key` | property | `true`: the key |
| `x-drishti-link` | property | the kind this field refers to |
| `x-drishti-match` | property | `true`: split the kind by this field's values |
| `x-drishti-discriminator` | `oneOf` | the property that tells the branches apart |
| `x-drishti-label` | property | the label (overrides `title`) |
| `x-drishti-unit` | property | the unit, shown in the label and the glossary (`USD`, `bp`, `%`) |
| `x-drishti-format` | property | a named Sutra format, always applied (`money`, `percent`, `amount2`, `signed0` ...) |
| `x-drishti-hidden` | property | `true`: leave it out of the Sutra and the glossary |
| `x-drishti-strip` | property | `true` always in the strip, `false` never |
| `x-drishti-importance` | property | an integer added to the field's weight when ordering the strip |
| `x-drishti-keys` | map | the keys of a numeric map in synthetic samples (`["1M", "3M", "1Y"]`) |
| `x-drishti-values` | enum | `{value: meaning}` for the glossary |
| `x-drishti-control` | enum | the suggested control: `dropdown`, `radio` or `text` |

Example (the example `trade` schema, shortened):

```json
{
  "title": "Trade",
  "description": "A bilateral derivative trade between the bank and a counterparty, booked in one book.",
  "required": ["tradeId", "counterpartyId", "bookId", "status", "notional", "currency", "tradeDate"],
  "properties": {
    "tradeId":        {"type": "string", "description": "The trade identifier."},
    "counterpartyId": {"type": "string", "description": "The counterparty the trade is with."},
    "status":         {"type": "string", "enum": ["PENDING", "LIVE", "MATURED", "CANCELLED"]},
    "productType":    {"type": "string", "enum": ["IRS", "FXS", "FUT"], "x-drishti-match": true},
    "notional":       {"$ref": "#/$defs/money", "title": "Notional", "x-drishti-unit": "USD"},
    "curve":          {"type": "object", "additionalProperties": {"type": "number"}, "x-drishti-keys": ["1M", "3M", "1Y", "5Y"]}
  }
}
```

## What is read from a schema

| Keyword | Used for |
|---|---|
| `title`, `description`, `$id` | kind, labels, descriptions, About text |
| `type` (also a list with `"null"`), `format`, `pattern`, `minimum`, `maximum`, `minLength` | types, formats, valid synthetic values |
| `properties`, `required` | the fields; what is required |
| `$ref` to `#/$defs/x`, `#/definitions/x`, `#`, another file of the batch (by file name or `$id`) | followed; a recursive `$ref` stops at an open object |
| `allOf` | merged (properties and requirements added) |
| `oneOf`, `anyOf` | objects: variants (above); `[X, null]`: X; consts: an enum; other mixes: the first branch |
| `enum`, `const` | enumerations, discriminators, synthetic values |
| `items`, `prefixItems` (first) | the rows of an array |
| `additionalProperties`, `patternProperties`, `propertyNames.enum` | maps |
| `examples`, `example`, `default` | synthetic values; whole-document `examples` become tests |
| `discriminator.propertyName` | the branch discriminator |
| `if/then`, `not`, `dependentSchemas`, `unevaluatedProperties`, remote `$ref` | ignored (a remote `$ref` is a warning) |

## Samples and tests: real, example, synthetic

Each Sutra has its own samples, in this order, up to the count you choose (5 by default):

1. **Real** documents of that Sutra (those matching its `where`), spread over the key. Prefix `sample-N.json`.
2. The schema's own whole-document `examples` that match it. Prefix `sample-example-N.json`.
3. **Synthetic** documents made from the schema, only to make up the count (always at least two for a Sutra with no real match). `sample-synthetic-N.json`.

Synthetic documents are **deterministic** (the same schema gives the same documents), valid against the schema's types, enums, ranges, patterns
(`^[A-Z]{3}$` gives `ABC`) and formats, and their keys and links **agree across the kinds** (`TRA-0001` links to `COU-0001`, which exists in the pack's `samples/`), so the preview's links
resolve. A variant's samples carry only that variant's fields. They are **marked synthetic** everywhere: in the file name, in the page's list and preview, in the pack's
`README.md`, in `MANIFEST.json` (`syntheticSutras`) and in the catalogue's subtitle. They prove a Sutra renders; they say nothing about your data.

Each Sutra's `tests/<sutra>/expect.yaml` is `noErrors: true`, plus `nonEmpty: [details]` for a specific Sutra.

## The New pack page, step by step

Open **Build → New pack**. It needs the **author** power to download and an administrator to deploy; anyone can plan and preview. The steps are buttons you can also press;
you cannot leave a step that would fail (the message says why).

### 1. Sources

![Step 1: schemas and a folder read in the browser, with rows and sizes](img/schemapack/01-sources.jpg)

Choose or drop files and folders: schemas (`.json`, `.yaml`, `.yml`) and data (`.jsonl`, `.ndjson`, or a `.json` array). A `.json` file that has `$schema`, or `properties`
with a `type`, or `$defs`, is taken as a schema, any other as data. **The files are read in your browser.** A JSON Lines file is streamed a line at a time and a
**reservoir sample** (200 documents, same file same sample) is kept; the page shows each file's progress, rows, size and bad lines, and the totals. Only the schemas, the
sample and the counts are sent: **never the whole folder**. *Reading limits* sets the rows per file, the MB per file and the sample size (up to the console's ceilings); a file
stopped at a cap says so. *Paste a schema instead* takes text. **Resume a draft** appears when you have one.

### 2. Kinds

![Step 2: the graph of kinds and links, and one card per kind](img/schemapack/03-kinds.jpg)

A graph of the kinds and their links, the data files with the kind each was matched to (change it), and **one card per kind**:

![One kind's card: key, split column, date, mnemonic, links, warnings, fields](img/schemapack/04-card.jpg)

*Kind name* (rename it), *Key field* (with why, amber when ambiguous), *Split into Sutras by* (the match column, each with the number of Sutras it makes), *Business date field*,
*Mnemonic*, *Links to other kinds* (change a target, remove one, add one), the Sutra names, the enumerated fields offered as dropdown controls, the card's warnings and every field
with its label, type, format and description. Every change redoes the plan. A kind the server already defines in another pack is flagged:

![A kind another pack already defines](img/schemapack/02-conflict.jpg)

### 3. Pack

![Step 3: name, code, title, version, description, samples, strip, mnemonics and connectors](img/schemapack/05-pack.jpg)

Name (letters, digits, `-`), code, title, version, description. *Samples per Sutra* and *Figures in the strip*. A table of the kinds with their **mnemonic** (checked against the
packs on the server), the **source** (samples only, the File connector, or a Delta lake) and the **logical connector name**. A pack name that exists says that deploying makes a new version.

### 4. Preview

![Step 4: every Sutra rendered on a sample](img/schemapack/06-preview.jpg)

The server drafts every Sutra as a **background job** (one request per Sutra, a few at once): a progress bar, **Cancel**, and the Sutras drafted so far are kept when you cancel. Then a list of the Sutras
with badges (*ok*, empty or failed cells, *synthetic*) and, for the one you choose, five tabs: **Preview** (the server's own rendering on a sample you pick, real, example or synthetic), **Sutra** (the
YAML), **About text** (what the *About this page* drawer will say, TODO entries marked), **Samples** (each labelled by source) and **Checks** (every panel against every sample). *Open in the workbench to refine* makes a design with
the Sutra and its samples and gives you the link; the preview does not change when you edit there, so refine and then copy the YAML into the pack, or edit the schema and generate again.

![The About text tab](img/schemapack/07-about.jpg)

![The Sutra tab](img/schemapack/08-sutra.jpg)

### 5. Output

![Step 5: the bundle, its checksum and the deploy hand-off](img/schemapack/09-output.jpg)

The **bundle** (`<name>-<version>.tar.gz`, with `.sha256` and the manifest) in **exactly the format of `drishti.py pack bundle`**: verify it with `drishti.py pack verify`. Anything synthetic or without a
description is listed. **Deploy** hands the bundle to Admin → Packs → Deploy archive:

![The hand-off: the server's checks and what the deploy would change](img/schemapack/10-deploy.jpg)

The server checks the archive (checksums, `pack.yaml`, server version, `sutra lint` and `sutra test`), shows what changes (breaking changes first, which you must acknowledge), and only on your
confirmation deploys. The version it replaces is kept: **roll back** there ([OPERATIONALISING §17](OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back)).

### Drafts, keyboard, phone, themes

**Save draft** keeps the state (the schemas, your choices, the file names and counts, not the data) as a design named `New pack: <name>` in *My designs*; it is saved when you move between steps and resumed from the
page. Data files are chosen again after a resume. Every control is a native button, field or select: Tab through, Enter or Space to press; the Sutra list takes Up and Down, the tabs Left and Right. In the light and dark themes colours come from the console's tokens
and every state has a word as well as a colour. At phone width the columns stack and every control is 44 px:

![The kinds step at 390 px](img/schemapack/11-phone.jpg)

## The command line

`sutra design --schema` and `pack make --schema` (see [CLI guide](CLI_GUIDE.md#sutra-design---schema-and-pack-make---schema)) run the same rules.

| Option | Meaning |
|---|---|
| `--schema FILE\|DIR ...` | the schemas; a folder takes its `*.schema.json` / `*.schema.yaml`, else every `.json` / `.yaml` in it. A `.jsonl` file or folder written after it is data |
| `INPUT ...` | JSON Lines files or folders (`pack make`; for `sutra design`, the positional paths) |
| `--kind K` | rename the kind of **one** schema (several: use `x-drishti-kind`) |
| `--key`, `--date`, `--match` | `FIELD` for every kind or `kind=FIELD,kind2=FIELD2` (`--match` as `kind=a,b;kind2=c`); `--match none` makes one Sutra per kind |
| `--mnemonic` | `kind=MN,kind2=M2` |
| `--count N` / `--samples N` | samples per Sutra (`sutra design` / `pack make`), default 5 |
| `--strip N` | figures in the strip, default 6 |
| `--store delta\|files`, `--connector NAME`, `--code`, `--description`, `--title`, `--version`, `--out`, `--force` | as in `pack make` |
| `--yes` | never ask which field is the key |

### One Sutra, printed

```text
$ python3 tools/drishti.py sutra design --schema docs/guides/examples/schemas/instrument.schema.json --yes
instrument: key instrumentId (named <kind>Id, required); date (none); match type; 3 Sutra(s); synthetic samples
  warning: no real documents for instrument: their previews and tests use synthetic samples generated from the schema, marked synthetic
  warning: instrument: 4 field(s) have no description (About-this-page text will say TODO): instrumentId, type, maturity, ticker
drafting 3 Sutra(s) with one `sutra design --each` run ...
```

stdout is the Sutras, one after another, separated by `---`. The first (`instrument-bond`, one of three, for the `BOND` branch of the `oneOf`):

```yaml
rachana: 1
sutra: instrument-bond
version: 1
description: "Instrument documents where Type BOND. Generated from instrument.schema.json."
match: { kind: instrument, where: "$.type == 'BOND'", priority: 10 }
title: { pill: "Instrument · ${$.type}", id: "$.instrumentId" }
strip:
  - { label: "Coupon", bind: "$.coupon", fmt: "pct2", emphasis: true }
panels:
  - id: details
    kind: kv
    title: "Details"
    key: "F2"
    columns:
      - { label: "Name", bind: "$.name" }
      - { label: "Type", bind: "$.type" }
      - { label: "Coupon", bind: "$.coupon", fmt: "pct2" }
      - { label: "Maturity", bind: "$.maturity", fmt: "date" }
  - id: built
    kind: provenance
    title: "How this view was built"
  - id: coupon
    kind: metric
    title: "Coupon"
    area: right
    value: "$.coupon"
    fmt: "pct4"
  ...
```

Its strip holds the bond's own figure (`coupon`, formatted `pct2` because the schema says so); the equity branch has `dividendYield` and `ticker` and no `coupon`. With
`--out DIR` the Sutras are written under `DIR/<kind>/` and their samples under `DIR/tests/<sutra>/`, and `sutra lint` runs on the result.

### A whole pack, schemas and real documents

```text
$ python3 tools/drishti.py pack make --schema docs/guides/examples/schemas docs/guides/examples/schemas/data \
      --name desk-pack --store files --yes
book: key bookId (named <kind>Id, required); date businessDate; match (none); 1 Sutra(s); synthetic samples
counterparty: key id (named id, required, unique in all 5 sample documents); date (none); match (none); 1 Sutra(s); links parentId->counterparty; real samples: 5
instrument: key instrumentId (named <kind>Id, required); date (none); match type; 3 Sutra(s); synthetic samples
trade: key tradeId (named <kind>Id, required, unique in all 30 sample documents); date tradeDate; match productType; 3 Sutra(s); links counterpartyId->counterparty, bookId->book; real samples: 30
  warning: no real documents for book, instrument: their previews and tests use synthetic samples generated from the schema, marked synthetic
  warning: book: 3 field(s) have no description (About-this-page text will say TODO): businessDate, createdBy, createdAt
  warning: counterparty: 3 field(s) have no description (About-this-page text will say TODO): headOffice.city, parentId, email
  warning: instrument: 4 field(s) have no description (About-this-page text will say TODO): instrumentId, type, maturity, ticker
  warning: trade: 2 field(s) have no description (About-this-page text will say TODO): executedAt, terms.index
  warning: trade: fields in the samples that the schema does not describe: extra
drafting 8 Sutra(s) with one `sutra design --each` run ...
  trade 2026-03-01: 10 rows -> data/build/desk-pack-1.0.0/data/files/desk-pack/2026-03-01/trade.jsonl
  trade 2026-03-02: 10 rows -> data/build/desk-pack-1.0.0/data/files/desk-pack/2026-03-02/trade.jsonl
  trade 2026-03-03: 10 rows -> data/build/desk-pack-1.0.0/data/files/desk-pack/2026-03-03/trade.jsonl
kind                business date        rows
trade               2026-03-01             10
trade               2026-03-02             10
trade               2026-03-03             10
total rows: 30

== pack check
== desk-pack: sutra lint: exit 0
== desk-pack: sutra test: exit 0

== pack bundle
bundled desk-pack 1.0.0: 66 files
  data/build/desk-pack-1.0.0/bundle/desk-pack-1.0.0.tar.gz
  sha256 6c9a2b925676f946740e95b870056731199217acb2dbf73e4a2297a89559d643

== pack verify
verify data/build/desk-pack-1.0.0/bundle/desk-pack-1.0.0.tar.gz: desk-pack 1.0.0
  checksums ok
  schema    ok
  server    ok
  sutra     ok
verified

pack make: data/build/desk-pack-1.0.0
  8 Sutra(s) for 4 kind(s) (4 tested on synthetic samples only); pack check passed, pack verify passed
next: read data/build/desk-pack-1.0.0/README.txt, then  DRISHTI_JAR=<exec jar> DRISHTI_PORT=18480 data/build/desk-pack-1.0.0/run-server.sh
```

The trade's three Sutras are its three `productType` values (`trade-irs`, `trade-fxs`, `trade-fut`); the instrument's are `instrument-bond`,
`instrument-equity` (the `oneOf` variants) and `instrument-default`. The example schemas and their JSON Lines are camel case
(`tradeId`, `productType`): the rules read any naming, and the same run on UPPER_SNAKE schemas (`TRADE_ID`, `PRODUCT_TYPE`, as the shipped
packs write them) finds the same keys, dates, matches and links.

`trade` and `counterparty` had real documents (30 and 5), so their Sutras were tested on those; `book` and `instrument` had none, so they were tested on synthetic samples, and the run
says so. The `extra` field of `trades.jsonl` is not in the schema: it is reported, shown in the Sutra and given a `TODO` glossary entry. Exit codes are those of `pack make`: 0, 1
(made but a check failed), 2 (usage: no key, a missing file, the folder exists without `--force`).


## The pack that comes out

The folder is the one `pack make` writes ([CLI guide](CLI_GUIDE.md#quickest-path-pack-make)): `README.txt`, `pack/<name>/`, `data/` (only when real, dated documents were given), `bundle/`, `config/`,
`run-server.sh`, `run-server.ps1`, `MANIFEST.json`. In the pack:

```text
pack/desk-pack/
├── pack.yaml                 kinds, mnemonics, columns, graph.fields (the links); connectors (the names), connector-templates (their suggested settings), routes and ingest when real dated data was given
├── sutras/<kind>/<sutra>.v1.sutra.yaml
├── tests/<sutra>/            sample-N.json / sample-example-N.json / sample-synthetic-N.json and expect.yaml
├── samples/                  documents per kind and catalog.json, so the pack opens before any data source
├── config/about.yaml         About this page: a sentence per kind, a glossary entry per shown field
└── README.md                 what was generated, which Sutras are synthetic, next steps
```

`pack.yaml` (the start; real output):

```yaml
# Generated by drishti schemakit (docs/guides/SCHEMA_TO_PACK.md); now yours to edit.
# book: key $.bookId, business date $.businessDate
# counterparty: key $.id
# instrument: key $.instrumentId
# trade: key $.tradeId, business date $.tradeDate
pack: desk-pack
version: 1.0.0
code: DP
title: Desk pack
description: 'Generated from schemas: Book, Counterparty, Instrument, Trade.'
kinds:
- book
- counterparty
- instrument
- trade
sutras: sutras
samples: samples
mnemonics:
  BOO:
    kind: book
    label: Book
  COU:
    kind: counterparty
    label: Counterparty
  INS:
    kind: instrument
    label: Instrument
  TRA:
```

`config/about.yaml` (the start; real output), the words coming from the schema's descriptions and `TODO` where there were none:

```yaml
about: 1
kinds:
  book:
    title: Book
    about: '${$.bookId}: A trading book: the unit risk and profit are reported on.'
    glossary:
      bookId:
        term: Book ID
        means: Book code.
      desk:
        term: Desk
        means: The desk the book belongs to.
      tradeCount:
        term: Trade count
        means: Number of live trades.
      dv01:
        term: DV01
        means: Rate sensitivity per basis point.
      businessDate:
        term: Business date
        means: 'TODO: what this field means.'
      createdBy:
        term: Created by
        means: 'TODO: what this field means.'
      createdAt:
        term: Created at
        means: 'TODO: what this field means.'
```

`MANIFEST.json` records, per kind, the key and why, the date, the match fields, the number of Sutras and the links (with why); `controls` (the dropdown suggestions), `syntheticSutras`, the
warnings, the bundle's checksum and `checks` (`packCheck`, `packVerify`).


## Limits and settings

| Setting | Default | |
|---|---|---|
| `builder.pack_sample_docs` | 200 | documents kept per data file and sent |
| `builder.pack_read_max_rows`, `builder.pack_read_max_mb` | 500000, 1024 | the most the page may be told to read per file |
| `builder.pack_max_files`, `builder.pack_max_docs`, `builder.pack_max_schema_kb`, `builder.pack_max_sutras` | 200, 5000, 512, 200 | refused with `413 DRS-5005` |
| `builder.pack_jobs_per_user`, `builder.pack_job_ttl_min`, `builder.pack_draft_concurrency` | 3, 60, 4 | preview jobs |
| `builder.tools_dir` | `../tools` | where `packbundle.py` is |
| `builder.pack_profile_rows_per_type` | 10000 | rows of each type profiled (fields filled, array sizes); every row is still counted |
| `builder.pack_design_samples_per_type` | 50 | documents of each type kept, varied in shape, to design that type's Sutra |
| `builder.pack_load_max_mb`, `drishti.loads.upload-max-mb` | 2048, 2048 | the largest file **Load data** streams on (console), and takes (server) |

All are in [CONFIGURATION.md](../admin/CONFIGURATION.md).

## Troubleshooting

| You see | Meaning |
|---|---|
| *Another pack on the server already defines the kind* | rename the kind on its card, or name the pack as the one that defines it (a new version) |
| *no key could be chosen* | no field looks like an id: mark one with `x-drishti-role: key`, `--key`, or the card |
| a card in amber, *ambiguous key* | several fields look like ids; choose |
| *no date field ... no dated store* | none named like a business date; choose one on the card to get a dated store |
| *no real documents ... synthetic* | no data was given for that kind; the Sutra is tested on documents made from the schema |
| *fields in the samples that the schema does not describe* | the schema is behind the data; they appear in the Sutra with a `TODO` entry |
| *$ref points at a schema that is not in this batch* | add that file to the batch |
| a Sutra *refused by the server* | the Sutra tab shows the located problems; usually a field name that is not a valid path |
| *501 packbundle.py is not available* | the console cannot find `tools/` (`builder.tools_dir`); the page still plans and previews |
| *429 DRS-5005* | too many preview jobs running: cancel one |
| *N types → N + 1 Sutras* on the card | some rows have no type; the extra one is the catch-all and the card says how many rows it is for |
| a field shown as *TODO* everywhere | blank in every row read (only `""` or spaces): it is hidden; a field that is blank in some rows is shown where it has a value |
| Load data: *types with no Sutra* | rows whose type the pack was not built with; they are stored, and open with the catch-all if there is one: make the pack again from a file that has them |
| *DRS-1001 no source holds deals-counterparty/...* right after a load | the pages per value are rebuilt by the load; if they are not, check the load result's steps say the pack's `-pages` connector was refreshed |

## Not yet, and what this leaves for later

- **Controls.** Enumerated fields are listed as dropdown suggestions (plan and `MANIFEST.json`); the design of controls is in progress (RUPAKA, the BI design note, section 8, has none yet), so nothing is written to a Sutra.
- **Editing the generated Sutras in place.** Open one in the workbench to refine it; to keep the schema as the source, change the schema and generate again (`pack regenerate` merges later changes).

## Appendix: A deep trading JSON

A trading system's export is often **one large JSON array** with no schema: each element a deal, its legs inside it, each leg's cash flows
inside the leg, each cash flow's resets inside that, events beside the legs, names in `UPPER_SNAKE`, blanks written as `""`, and no field
that says which day the file describes. Several products share the file and one column (`PRODUCT_TYPE`) tells them apart. This appendix
takes such a file from nothing to a deployed pack with its data, every step in the browser, with the command line and the YAML beside it.

Every value here is **synthetic** (`tools/packgen/comswap_synth.py`: counterparties `CPTY-ALPHA` ..., traders *Trader A* ..., portfolios
`PF-ENERGY-01` ...). Never put a real export's values in a pack, a test, a sample or a screenshot: **Anonymise samples** (below) exists for that.

```bash
# a whole book in one file: 5,000 deals of five types (ComSwap, ComFut, Cash, Commodity and a rare CallNot), about 66 MB
# --array writes the export's own shape, one pretty-printed JSON array, as a booking system does (the steps below upload it as it comes)
python3 tools/packgen/comswap_synth.py --mix --deals 5000 --array --out deals.json
# without --array: JSON Lines, one deal a line (every generator's output, DATA_FORMATS.md)
python3 tools/packgen/comswap_synth.py --mix --deals 5000 --out deals.jsonl
# small ones are committed: docs/guides/examples/comswap/book-sample.jsonl (40 mixed) and comswap-sample.jsonl (40 swaps)
# another deep shape, four levels, camelCase: a structured note (coupon periods, observations, fixings), one a line
python3 tools/packgen/note_synth.py --notes 30 --out notes.jsonl
```

One deal, shortened:

```json
{ "TRADE_ID": 100004, "TRANSACTION_ID": 700000021, "TRADE_STATUS": "Amended", "PRODUCT_TYPE": "ComSwap",
  "PRODUCT_SUB_TYPE": "Fixed float swap", "COUNTERPARTY": "CPTY-PRAIRIE", "PORTFOLIO": "PF-ENERGY-01", "LEGAL_ENTITY": "LE-SOUTH",
  "TRADE_DATE": "2026-02-17", "START_DATE": "2026-05-01", "MATURITY_DATE": "2027-10-31", "QUANTITY": 180000, "PRECIOUS_METAL_FLAG": "",
  "LEGS": [
    { "LEG_ID": 1, "LEG_TYPE": "Fixed", "NOTIONAL": 10000, "CASH_FLOWS": [ { "FLOW_ID": 5000099, "PAYMENT_DATE": "2026-06-05", "RESETS": [] } ] },
    { "LEG_ID": 2, "LEG_TYPE": "Float", "INDEX": { "NAME": "IDX-NATGAS-TTF", "GROUP": "Energy" },
      "CASH_FLOWS": [ { "FLOW_ID": 5000119, "PAYMENT_DATE": "2026-08-05",
                        "RESETS": [ { "RESET_ID": 1, "RESET_DATE": "2026-07-01", "ACCRUAL_START_DATE": "", "RATE": "31.8669" } ] } ] } ],
  "EVENTS": [ { "EVENT_ID": 9000011, "LEG_ID": 1, "EVENT_TYPE": "Reset", "EVENT_DATE": "2026-06-05", "AMOUNT": 142210.64 } ] }
```

### 1. Read the file (Build → New pack, step 1)

Choose `deals.json` with **Choose files**. The page streams it: one document in memory at a time, whatever its size, and nothing is
uploaded. **Every row is counted** for the columns that could tell record types apart, up to `builder.pack_profile_rows_per_type` rows of
each type are profiled (how often each field has a value, digits written as text, the size of every array at every depth), and
`builder.pack_design_samples_per_type` documents of each type are kept, chosen for variety (with and without events, one leg and two, fixed
and floating). Measured: 66 MB / 5,000 deals in 0.5 s; 2 GB / 500,000 rows in about 20 s with about 110 MB in the page, flat.

![Step 1: one JSON array of 5,000 deals read in the browser, with its rows, size and the documents kept](img/deepjson/01-sources.jpg)

**Anonymise samples** is on by default for data files: before anything leaves the browser's sample, names and ids are replaced the same way
everywhere (`CPTY-0007`), digits by other digits of the same length, amounts scaled; dates and short vocabularies (`Buy`, `Float`) are kept, so
the design is the same. Turn it off only for data you may share.

### 2. The card (step 2)

![The kind's card: 5 types to 5 Sutras with each type's rows, the key, the snapshot date, pages per value and links](img/deepjson/02-card.jpg)

You should see, without choosing anything:

- **Split into Sutras by a type column**: `PRODUCT_TYPE (5 types)`, and the list *5 types → 5 Sutras*: `ComSwap 2,225 → comswap` ...
  `CallNot 52 → callnot`. The rare type is there with its true count, because every row was counted. No catch-all: every row has a type
  (a file where some rows have none gets a sixth, `deals-default`, and the card says for how many rows).
- **Key field** `TRADE_ID`, *named like the id of a trade, unique in all documents (other id-like fields: TRANSACTION_ID)*. An integer id is
  an id: never summed, never a strip figure.
- **Business date**: *Snapshot: the day the file is loaded*. `TRADE_DATE`, `START_DATE` and `MATURITY_DATE` differ from deal to deal, so none
  is the day of the file. Choose one if every document does carry its own day.
- **Pages for each value**: *Counterparty*, *Portfolio* and *Index* (`LEGS[].INDEX.NAME`, found inside the legs) are ticked; each becomes a
  kind of its own, built by the server from the deals (no extra feed), linked both ways: a deal's counterparty opens the counterparty's page,
  which lists its deals.
- **Fields**: `UPPER_SNAKE` read as words (*Trade status*, *Buy sell code*); a field that is blank in every row (`PRECIOUS_METAL_FLAG` above)
  is hidden rather than shown empty; a field filled in a few rows shows its *Filled* share.

The same plan from the command line, where every choice above has a flag:

```bash
python3 tools/drishti.py pack make deals.json --name book --kind deals --store files --yes
#   deals: key TRADE_ID (named like the id of a trade, unique in all 5000 sample documents (other id-like fields: TRANSACTION_ID));
#   date (none); match PRODUCT_TYPE; 5 Sutra(s); links COUNTERPARTY->deals-counterparty, PORTFOLIO->deals-portfolio
#   deals-counterparty / deals-portfolio / deals-index: key id (the value the documents share); links dealsIds->deals
#   deals 2026-10-16: 5000 rows -> data/build/book-1.0.0/data/files/book/2026-10-16/deals.jsonl   (the day: today's business date)
#   pack make: data/build/book-1.0.0   8 Sutra(s) for 4 kind(s); pack check passed, pack verify passed
# --match COL          another type column
# --key TRADE_ID       --date FIELD (instead of a snapshot)       --as-of 2026-10-16 (the day a snapshot is stored under)
# --related none       no pages per value; --related COUNTERPARTY,PORTFOLIO for these only
# --no-anonymise       keep the samples as they are
```

### 3. Pack, preview, deploy (steps 3 to 5)

Name the pack `book` and press Next. The preview drafts the five Sutras and the three page kinds against the server and checks each one.

![Step 4: every Sutra drafted, checked and shown on its own samples](img/deepjson/03-preview.jpg)

Step 5 downloads the pack or **deploys it** (Admin → Packs, with its checks). What comes out, in `pack.yaml` (shortened):

```yaml
pack: book
kinds: [deals, deals-counterparty, deals-portfolio, deals-index]
connectors: [book-pages, book-store]
routes: {deals: book-store, deals-counterparty: book-pages, deals-portfolio: book-pages, deals-index: book-pages}
ingest:
  deals: {key: TRADE_ID, snapshot: true}       # no business-date field: stored under the day it is loaded
connector-templates:
  book-store:                                  # the File connector the data goes into
    plugin: file
    kinds: [deals]
    settings:
      root: ${DRISHTI_FILES_ROOT:${drishti.data.dir:./data}/files}
      domain: book
      layout:
        deals:
          columns: [TRADE_STATUS, BUY_SELL_CODE, QUANTITY, PRODUCT_SUB_TYPE, COUNTERPARTY, PORTFOLIO, START_DATE, TRADE_ID, ...]
          title: [PRODUCT_TYPE, COUNTERPARTY, PORTFOLIO]
  book-pages:                                  # the pages per value, built from the deals
    plugin: derived
    kinds: [deals-counterparty, deals-index, deals-portfolio]
    settings:
      deals-counterparty:
        from: deals
        group-by: $.COUNTERPARTY
        fields: {count: count, totalQuantity: sum $.QUANTITY, portfolios: distinct $.PORTFOLIO}
      deals-index:
        from: deals
        group-by: first($.LEGS[?@.INDEX.NAME != '']).INDEX.NAME
graph:
  fields:
    COUNTERPARTY: {kind: deals-counterparty, label: Counterparty}
    PORTFOLIO: {kind: deals-portfolio, label: Portfolio}
```

`layout.deals.title` puts the type, counterparty and portfolio after the id in the search title, so typing `CPTY-PRAIRIE` in the command bar
offers that counterparty's deals as well as its page. The pack also carries `sutras/deals/{comswap,comfut,commodity,cash,callnot}.v1.sutra.yaml`,
one Sutra for each page kind, anonymised samples and tests, and `config/bi/` (section 7).

### 4. Load the data (Admin → Packs → book → Load data)

![Load data: 5,000 rows streamed into the File connector, checked against the Sutras, then refresh, verify, alerts and notices](img/deepjson/04-load-data.jpg)

Choose the same `deals.json` and press **Load the file**. It is streamed from the browser to the server (never held in memory on the way,
up to `drishti.loads.upload-max-mb`), written into `book-store` under the business date (a snapshot: today's, or the day you choose; a day
loaded again is replaced), and **every row is checked** against the pack's Sutras: *types with no Sutra* (a product the pack was not built
with) and *fields no Sutra shows* are listed with their counts. Then the load is announced like any other data load
([Data loads](DATA_LOADS.md)): caches are dropped (the pages per value are rebuilt from the new deals), the day is verified, alerts and
subscriptions run. 5,000 deals take about a second.

The same from a script, or offline:

```bash
# over REST, with a token that has packs:admin (a JSON array or JSON Lines)
curl -sS -X POST "http://HOST:18480/api/v1/admin/packs/book/data?kind=deals&date=2026-10-16" \
     -H "Authorization: Bearer $DRISHTI_TOKEN" -H "Content-Type: application/json" --data-binary @deals.json
# offline, into the File connector's folder (JSON Lines: what comswap_synth.py writes without --array)
python3 tools/drishti.py data ingest --from deals.jsonl --pack data/build/book-1.0.0/pack/book --store files --root /path/to/files --as-of 2026-10-16
```

### 5. A deal

Type `DEA 100004 <GO>` (or the counterparty's name, and pick the deal).

![A ComSwap: the deal strip, its filled fields, the legs side by side, the payment timeline](img/deepjson/05-deal.jpg)

- the **strip**: status, side, quantity, sub type, counterparty and portfolio (links), start and maturity;
- **Deals** (F2): every field the deal fills, blanks left out;
- **Legs** (F3): a few legs **side by side**, each with its own terms (a fixed leg's price, a floating leg's index and reset convention);
- **Cash flows by leg** (F4): a tab per leg, each schedule a row wide, drilling down level by level (below);
- **Events** (F5): the deal's resets, payments and the like, or *No events* when it has none;
- on the right, the **payment dates of every leg** as one timeline (`flatten($.LEGS[*].CASH_FLOWS)`), and the other deals of the same counterparty.

A ComFut, a Cash payment and a CallNot open with their own Sutras: the same arrangement made from their own fields (a cash deal has no legs
panel because it has no legs).

### 6. Drill down: leg → cash flow → resets

![Leg 2, cash flow 5000119, its resets: the breadcrumb, the cash flow as a ticket, the resets](img/deepjson/06-resets.jpg)

In **Cash flows by leg**, choose *Leg 2 · Float*, then a cash flow's link (`5000119 1 ▸`: one reset). The breadcrumb reads *Deals 100004 ›
Leg 2 · Float › Cash flow 5000119 › Resets*, the cash flow's values sit above as a ticket, and its resets fill the table with their count.
Keyboard: ↑ ↓ to a row, Enter or → to open, **Esc**, Backspace or ← to go up (the row you came from stays selected); the browser's Back goes
up too, and the address keeps the level and the tab, so a copied link opens this very table of resets. The Sutra behind it (shortened):

```yaml
  - id: legs-cash-flows
    kind: tabs
    title: Cash flows by leg
    each: "$.LEGS"
    tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE"
    panels:
      - id: legs-cash-flows-rows
        kind: table
        title: Cash flows
        rows: "@.CASH_FLOWS"
        crumb: "'Cash flow ' + @.FLOW_ID"
        root: "'Deals ' + $.TRADE_ID + ' › ' + 'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE"
        empty: "No cash flows"
        columns:
          - { label: "Payment date", bind: "@.PAYMENT_DATE", fmt: "date" }
          - { label: "Notional", bind: "@.NOTIONAL", fmt: "amount2", total: true }
          - { label: "Resets", bind: "size(@.RESETS)", fmt: "amount0" }
        levels:
          - rows: "@.RESETS"
            title: "Resets"
            crumb: "'Reset ' + @.RESET_ID"
            columns:
              - { label: "Reset date", bind: "@.RESET_DATE", fmt: "date" }
              - { label: "Rate", bind: "@.RATE", fmt: "price4" }
```

Nothing in it is about trades: any array whose elements hold arrays is drawn this way, to any depth (the structured note of
`note_synth.py` drills *coupon period → observations → fixings*). `levels`, `crumb`, `root` and `empty` are in the
[Rachana reference](RACHANA_REFERENCE.md#levels-drill-down-level-by-level).

### 7. A counterparty, the phone, and BI

![The counterparty's page: its deals with their count and total quantity, each deal a link](img/deepjson/07-counterparty.jpg)

The counterparty link opens `DEAC CPTY-PRAIRIE`: how many deals, the total quantity, how many portfolios, and every deal (filter, sort, page;
each id opens the deal). On a phone the same deal stacks: the strip in two columns, panels one under another, the function keys along the
bottom, nothing wider than the screen.

![The deal on a phone, 390 px wide](img/deepjson/08-phone.jpg)

For analysis the pack ships a **BI model** with a table for every level, each row carrying the keys above it, so a cash flow can be counted by
its deal's counterparty or its leg's index:

```yaml
# config/bi/models/book-deals.model.yaml (shortened)
model: book-deals
datasets:
  deals: {dataset: book-deals-deals, role: dimension}
  legs: {dataset: book-deals-legs, role: fact}              # each: LEGS
  cash_flows: {dataset: book-deals-cash-flows, role: fact}  # each: LEGS[].CASH_FLOWS, with ^.LEG_ID
  resets: {dataset: book-deals-resets, role: fact}          # each: LEGS[].CASH_FLOWS[].RESETS, with ^.FLOW_ID and ^^.LEG_ID
  events: {dataset: book-deals-events, role: fact}
relationships:
  - {from: legs.id, to: deals.id}
  - {from: cash_flows.id, to: deals.id}
measures:
  Leg notional: {expr: sum(legs.NOTIONAL), format: amount0}
  Cash flows: {expr: count(cash_flows)}
```

```bash
python3 tools/drishti.py bi mq --model book-deals@1 --select deals.PRODUCT_TYPE --measures "Deals documents,Cash flows,Resets" --server http://HOST:18480 --token-file my-token.txt
```

### What is still short of a trading terminal

The deal screen is generic, so it is not a product-specific one (a swap's pricing and risk screen, a description page per security): there is
no valuation, no curve or fixing history beside a floating leg, no side-by-side comparison of two deals, and the timeline shows dates and
amounts but not a cash-flow ladder netted by date. Those need a quant engine or a hand-written Sutra on top of the generated one.
