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
# Screen Builder: from JSON files to a working screen in minutes

Status: design, agreed before building. Owner: the Build menu (console) and the engine's inference.

Someone has a folder of JSON documents (trades, shipments, patients, sensor readings) and wants a screen for them. Today
they write a Sutra by hand, or start from inference on one document and edit YAML. The Screen Builder turns that into
three guided steps, each also usable on its own:

1. **Samples → Shape.** Upload up to 50 JSON files (or sample them from a live store). Drishti merges them into one
   *shape*: a JSON Schema that describes every file, annotated with what each field *is* (an id, a link, a measure, a
   time series, a tree...).
2. **Shape → Design.** Drishti drafts a complete screen from the shape (auto-design). The author refines it visually:
   drag panels from a palette, drop fields onto the canvas, edit options in an inspector, see the real screen as they go.
3. **Design → Check → Save.** One click previews the design against *every* sample and shows which panels break or go
   empty for which files. Then save it as a Studio proposal (four-eyes review as today), or export a pack scaffold.

The result is always an ordinary Sutra: nothing here adds a second layout language or a second renderer.

## Contents

1. [Design principles](#design-principles)
2. [The three artefacts: sample set, shape, Sutra](#the-three-artefacts-sample-set-shape-sutra)
3. [Step 1: Shape (the JSON Schema extractor)](#step-1-shape-the-json-schema-extractor)
4. [Step 2: Design (the visual screen designer)](#step-2-design-the-visual-screen-designer)
5. [Step 3: Check and save](#step-3-check-and-save)
6. [Where it lives in the console](#where-it-lives-in-the-console)
7. [Server API](#server-api)
8. [Security, limits and accessibility](#security-limits-and-accessibility)
9. [How it will be built, step by step](#how-it-will-be-built-step-by-step)
10. [What we chose not to do](#what-we-chose-not-to-do)

## Design principles

These decide every detail below.

| Principle | What it means here |
|---|---|
| **One layout language** | The designer edits a Sutra. Its canvas is the Sutra; its output is the Sutra; the YAML is always one tab away and edits in either place show in the other. No hidden canvas model to drift from the YAML. |
| **One renderer** | The canvas *is* the real view, rendered by the same code that renders a view (the preview API), with selection handles drawn over it. Every panel kind, format, tone, theme and phone width is shown exactly as users will see it, with no second implementation to keep in step. |
| **One inference** | Auto-design and the "suggest a panel" hints use the same rules as runtime inference ([INFERENCE.md](INFERENCE.md)), extended to work from a shape (many documents) instead of one document. What Drishti would show without a Sutra and what the builder drafts never disagree. |
| **One schema of options** | The inspector's forms are generated from the Rachana schema (`GET /api/v1/rachana/schema`), which already lists every panel kind and option. A new option added to the engine appears in the designer with no designer change. |
| **Edits on the server** | Every change is an *operation* (add a panel, move it, set an option, bind a field) applied by the server's Sutra editor (`SutraLayoutEditor`, already used by layout mode), which keeps comments and key order. The browser never rewrites YAML text itself. |
| **Robust over pretty** | A design is checked against all samples, not the one on screen. A field present in 3 of 40 files is flagged before anyone ships a panel built on it. |
| **Keyboard first** | Everything you can drag you can also do from a menu and keys, announced to screen readers, like layout mode today. |

## The three artefacts: sample set, shape, Sutra

```
 JSON files ──upload / sample──▶  SAMPLE SET  ──infer──▶  SHAPE  ──auto-design──▶  SUTRA  ──propose──▶ review ──▶ live
 (≤ 50)                           (per author,            (JSON Schema +          (YAML, edited
                                   expires)               x-drishti roles)         by operations)
                                        └──────────────── check every sample ◀───────────┘
```

- **Sample set.** The uploaded documents, held for the author's session (or saved by name, per user, like workspaces),
  with their file names. It feeds two things: shape inference and the multi-sample check. It is never written to a
  store and never shown to anyone else.
- **Shape.** A JSON Schema (draft 2020-12) of the whole set, plus Drishti annotations under `x-drishti`. It can be
  downloaded and used outside Drishti (it is a valid schema), and uploaded again later instead of the samples.
- **Sutra.** The layout, as today. The designer adds no keys of its own to it.

## Step 1: Shape (the JSON Schema extractor)

### What goes in

- Up to 50 files (setting `drishti.builder.max-samples`), each `.json` (one document) or `.jsonl` (one document per line;
  each line counts as a sample, up to the same total).
- Or **sample from a store**: pick a kind and Drishti reads up to 50 of its entities through the normal sources, with
  the author's field masks applied (a masked field appears in the shape as masked, with no values).
- Or a shape file saved earlier.

### How documents are merged

Each document is walked once; the walks are merged into one tree of *path → observed facts*:

| Observed across the samples | Becomes in the schema |
|---|---|
| Present in every document | `required` |
| Present in some | optional, with `x-drishti.presence` = share of documents (0.25 = one in four) |
| `null` in some | `type: [..., "null"]` |
| Different types (number in some, text in others) | `oneOf` the types seen, flagged as a **conflict** in the report |
| Object whose keys differ from document to document (ids as keys) | a map: `additionalProperties` with the merged value schema, not a record |
| Array | `items` = merge of every element of every document; `minItems`/`maxItems` seen |
| Array of objects that contain an array of the same shape (`children`, `nodes`...) | a recursive `$ref` (a tree) |
| Text with few distinct values (≤ 12, seen ≥ 3 times) | `enum` |
| Text that is always a date / date-time / uuid / e-mail / ISO currency | `format` |
| Numbers | `minimum`/`maximum` seen; integer if never fractional |

### Roles: what makes it more than a schema

Each path also gets a **role**, the fact the designer needs and a plain schema cannot say. Roles come from the same
rules runtime inference uses (field names, value patterns, the packs' link catalogue), so they are explainable:
every role carries the reason it was chosen.

| Role | Detected by | Used by the designer for |
|---|---|---|
| `id` | unique per document, id-like name or pattern (`tradeId`, `MX-2000…`) | the title's id, the match rule |
| `link` | value matches a known kind's ids, or `{id, kind}`/`{id, name}` objects | links, `source:`, the links panel |
| `measure` | number with a money/quantity name or spread | strip figures, chart values, totals |
| `dimension` | low-cardinality text (`book`, `currency`, `status`) | pivot rows/columns, groups, colours |
| `status` | dimension whose values are states (`Live`, `Settled`, `Failed`...) | `tone: status`, status panel |
| `date` | date/date-time | axes, timelines, ladders |
| `series` | array of `{date or tenor, number}` | line, area |
| `ohlc` | array with open/high/low/close | candlestick |
| `distribution` | array of numbers, or of objects with one measure | histogram |
| `grid` | array of rows with several numeric columns on one axis | surface |
| `steps` | array of `{label, signed number}` that sums to another measure | waterfall |
| `graph` | `nodes` + `edges` | graph |
| `tree` | recursive children | tree table, nested pivot |
| `events` | array of `{date, label/description}` | timeline |
| `text` | long text | markdown |

The report beside the schema tree shows, per path: type, role (with its reason), presence, example values, and which
files contributed it. Conflicts and rare fields are listed at the top: they are what bites a screen later.

### Output

- **Download** `shape.json` (the schema).
- **Open in Designer** (step 2).
- **Copy as JSON Schema without annotations**, for use elsewhere.

## Step 2: Design (the visual screen designer)

### The screen

```
┌ Shape ───────────┬ Canvas: the real view, main + side column ─────────────┬ Inspector ────────────┐
│ trade            │  ┌ title: TRD · Government bond  BBG-60000001 ───────┐  │ Panel: dv01 (hbar)    │
│  ├ tradeId   id  │  │ strip: Notional · MTM · DV01                      │  │ title  [DV01 by bucket]│
│  ├ mtm   measure │  └────────────────────────────────────────────────────┘  │ rows   [$.sensitivities]│
│  ├ book      dim │  ┌ kv: terms (8) ────────┐┌ status (4) ┐  ┌ links ┐   │ label  [bucket ▾]      │
│  ├ pnlHistory    │  └───────────────────────┘└────────────┘  │       │   │ value  [dv01 ▾]        │
│  │     series ▸  │  ┌ line: daily P&L (6) ──┐┌ hbar: DV01 ┐  └───────┘   │ fmt    [signed0 ▾]     │
│  └ ...           │  └───────────────────────┘└────────────┘               │ span 6  height 9       │
├ Palette ─────────┤                                                         │                        │
│ kv table pivot … │  Samples ◀ 3 of 40 ▶  · width: desktop / phone · theme  │ ⚠ dv01 missing in 2 files│
└──────────────────┴─────────────────────────────────────────────────────────┴────────────────────────┘
  [ Design | YAML ]                                      [ Check all samples ]  [ Propose… ] [ Export pack ]
```

### Start: auto-design

Opening a shape in the designer starts from **auto-design**: the engine drafts a complete Sutra from the shape's roles,
with a title (id + a dimension), a strip (up to six measures and dates), and one panel per meaningful structure, laid
out by the same packing rules as inference (charts side by side, tables full width, links on the right). Authors
refine rather than face an empty page. Auto-design can be re-run at any time; it never overwrites panels the author
has touched.

### Adding and binding

- **Drag a panel kind from the palette** onto the canvas: it is placed where dropped (a gap in a row or a new row) and
  the inspector opens with the options the kind requires; required ones are highlighted until bound.
- **Drag a field from the shape onto empty canvas**: Drishti suggests panel kinds for that field's role, best first
  (a `series` suggests line, then area; a `tree` suggests tree table, then nested pivot; a `dimension` + `measure` pair
  suggests a pivot with row groups). The author picks one; its options are filled from the shape.
- **Drag a field onto an existing panel**: it becomes a column (table, kv, ladder), a series (area), a value (gauge,
  pivot) or a group level (`by:` of a pivot), whichever the panel kind accepts; an incompatible drop says why.
- **Title, strip, keys and match** have their own small editors at the top of the inspector; expressions get
  autocompletion from the shape (paths, functions, formats) and are checked as you type with the server's parser.

### Arranging

Panels are moved by dragging their header and resized by their edge, on Drishti's grid: main or side column, a span
of 1 to 12 columns, a height of 1 to 24 rows. This is exactly what the Sutra stores (`area`, `span`, `height`), so the
canvas cannot show something the Sutra cannot say. Phone width and every theme can be previewed from the canvas toolbar.

### Two views of one design

**Design** and **YAML** are tabs over the same Sutra. Typing in YAML re-renders the canvas when the YAML parses;
problems are shown with their line, as in Studio. Opening an existing Sutra (from Studio or the registry) opens the
same designer on it, so the designer is not only for new screens.

### Undo

Every change is an operation; the designer keeps the list, so undo and redo are exact, and the operations can be
replayed on a newer copy of the Sutra if someone else changed it in the meantime.

## Step 3: Check and save

### Check every sample

**Check all samples** renders the design against each sample and shows a matrix:

| Panel | file-01 | file-02 | … | file-40 |
|---|---|---|---|---|
| terms (kv) | ok | ok | | ok |
| pnl (line) | ok | **empty** | | ok |
| dv01 (hbar) | ok | **error: no field dv01** | | ok |

Clicking a cell shows that sample on the canvas. This turns "it looked fine on the one I tried" into evidence across
the set. The same check warns at design time when a binding uses a field with low presence or a type conflict.

### Save

- **Propose**: the Sutra goes to Studio's review flow (four-eyes, as today), with the sample set's file names and the
  check matrix attached to the proposal so the reviewer sees the evidence.
- **Export pack scaffold**: a zip with `pack.yaml` (kind, mnemonic suggestion, id field), the Sutra, three samples and a
  README, ready for the steps in [PACKS.md](../guides/PACKS.md), which brings a new domain into Drishti in minutes.
- **Download** the Sutra YAML, or copy it into Studio with a sample (today's Studio flow keeps working).

## Where it lives in the console

The Build menu gains a third group:

```
Build ▾
  Layouts      Sutra Studio · Reviews
  Screen Builder   New screen from JSON files      (/build)          wizard: samples → shape → design
                   Shape extractor                 (/build/shape)    step 1 on its own
                   Designer                        (/build/design)   step 2 on its own (open a shape or a Sutra)
  Learn        Sutra guide · Build a pack · Screen Builder guide
```

Studio gains **Open in Designer** (for the Sutra in the editor) and the designer **Open in Studio**, so both tools work
on the same file.

## Server API

All under `/api/v1/builder`, all requiring the `author` power (as Studio), none writing to stores.

| Endpoint | Does |
|---|---|
| `POST /builder/samples` | upload JSON/JSONL documents (multipart); returns a sample-set id, counts and per-file problems |
| `POST /builder/samples/from-store` | `{kind, count}`: sample entities through the normal sources, masks applied |
| `POST /builder/shape` | `{samples}` or `{sampleSet}`: returns the shape (schema + roles + report) |
| `POST /builder/design` | `{shape}`: auto-design, returns a Sutra (YAML) and its preview |
| `POST /builder/suggest` | `{shape, path, at?}`: ranked panel kinds for a field, each with filled options |
| `POST /builder/edit` | `{yaml, ops[]}`: applies operations with the Sutra editor, returns new YAML, problems, preview |
| `POST /builder/check` | `{yaml, sampleSet}`: the panel × sample matrix |
| `POST /builder/pack` | `{yaml, sampleSet, kind, mnemonic}`: the pack scaffold zip |

Operations are small JSON objects: `{"op":"addPanel","kind":"line","at":{"area":"main","after":"terms"}}`,
`{"op":"set","panel":"pnl","option":"y","value":"pnl"}`, `{"op":"bind","panel":"pnl","path":"$.pnlHistory"}`,
`{"op":"move","panel":"pnl","area":"main","before":"dv01","span":6}`, `{"op":"remove","panel":"pnl"}`,
`{"op":"setTitle"|"setStrip"|"setKeys"|"setMatch",...}`.

Engine placement: a `shape` package in `drishti-engine` (merging, roles), next to the inference it shares rules with;
operations extend `SutraLayoutEditor` in `drishti-rachana`. The console adds templates and vendored JavaScript only;
no new framework.

## Security, limits and accessibility

- **Limits** (all settings): 50 samples, 5 MB per file, 25 MB per set, nesting depth 64 (JSON parsing reuses the
  connectors' hardened reader: bad lines counted and skipped, NaN as no value). Sample sets expire after 24 hours
  unless saved; saved sets count against a per-user quota.
- **Privacy**: samples are the author's own data, kept per user, never in a store, never shared, never logged
  (only counts are). Sampling from a store applies the author's field masks.
- **Requests**: same-origin and JSON content-type checks as the rest of the console; uploads are size-checked before
  parsing.
- **Accessibility**: every drag has a keyboard alternative (an *Add panel* menu; move and resize with keys as in layout
  mode), announced through a live region; the canvas panels are focusable with their kind and title as names.

## How it will be built, step by step

Each step ships on its own, ends with tests that fail before it, its docs, and a drill. Later steps only add.

| Step | Delivers | Proven by |
|---|---|---|
| **1. Shape engine** | `drishti-engine` `shape` package: merge, types, formats, enums, maps, trees, roles with reasons; `POST /builder/shape` | unit tests per rule; a property test that **every sample validates against its inferred schema**; the 10 examples' JSON infer the roles their panels need. **Done in 2d525b4.** |
| **2. Shape extractor page** | `/build/shape`: upload (≤ 50), schema tree with roles and presence, conflicts report, downloads; Build menu entry; sample sets per user | console tests; Playwright upload-and-download; limits refused cleanly. **Done**: [user guide](../../console/web/guides/screen-builder.md); sample sets are kept in the console's memory per user for `builder.ttl_hours`; "Open in Studio" pastes the first sample (no Sutra drafted until step 3). |
| **3. Auto-design and suggest** | inference rules generalised from one document to a shape; `POST /builder/design` and `/suggest` | auto-design of each example's samples renders with no panel errors and uses the expected kinds; runtime inference unchanged on the existing tests |
| **4. Edit operations** | operations on `SutraLayoutEditor`; `POST /builder/edit` with undo-safe results | round-trip tests: operations keep comments and order; every op on every kind; bad ops are located problems |
| **5. Designer** | `/build/design`: canvas over the real preview with handles, palette, field drops with suggestions, inspector from the Rachana schema, YAML tab, undo, keyboard alternatives | Playwright: build the all-panels showcase from its shape without typing YAML; keyboard-only path; phone width |
| **6. Check every sample** | `POST /builder/check`, the matrix, design-time presence and conflict warnings | a sample set with a missing field shows the right empty/error cells |
| **7. Save, export, guide** | propose with evidence attached, pack scaffold, store sampling, the user guide in help with a walkthrough, Studio ↔ Designer links | an end-to-end test: upload samples → auto-design → edit → check → propose → approve → the view serves |

Steps 1 to 3 already give authors a useful tool (shape + a drafted Sutra to refine in Studio); steps 4 and 5 are the
visual designer; 6 and 7 make it safe to ship screens from it.

## What we chose not to do

- **A separate canvas model or layout format.** It would drift from the Sutra; editing the Sutra directly keeps one truth.
- **A client-side renderer for the canvas.** The real preview is already fast enough (tens of milliseconds) and is the
  only way to guarantee what you see is what users get.
- **Free-form pixel positions.** Drishti lays out on a grid that adapts to phones and themes; a pixel canvas would
  promise layouts the views cannot keep.
- **Schema inference in the browser.** One engine on the server serves the extractor, auto-design and runtime
  inference, with the hardened JSON reader and limits.
