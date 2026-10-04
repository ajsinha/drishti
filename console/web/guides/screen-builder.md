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
# Screen Builder · JSON files to a screen

You have a folder of JSON documents (trades, shipments, patients, sensor readings) and want a screen for them. The
**Build workbench** gets you there in steps. This guide covers what works today; the whole plan is in
[the Build workbench design](../../../docs/architecture/BUILD_WORKBENCH.md).

| Step | What it does | State |
|---|---|---|
| 1. Samples to shape | Merges up to 50 documents into one JSON Schema that says what every field *is*. | Works: `POST /api/v1/builder/shape` |
| 2. Auto-design | A complete Sutra from the shape and the samples, with a reason for every choice. | Works: `POST /api/v1/builder/design` and `/suggest` |
| 3. **Designs** | Your work is kept on the server as a *design*: **New screen**, **My designs**, examples as your own copies. | Works: **Build → New screen** (`/build/new`), **Build → My designs** (`/build`) |
| 4 to 7 | Operations and one checker, the visual workbench, Studio folded in (done), ship and scale. | Planned |

Everyone who is signed in may use it: designing is open to all, and only saving or proposing a Sutra to the registry needs the
**author** power.

## What is a design

A **design** is your work on one screen: the samples you brought, the Sutra (its text and revision number), notes, and the
kind of entity the screen is for. It is kept on the server, **in your name only**: nobody else can list or open it (they get
"not found"), and it is there after you close the browser, restart the console, or sign in from another computer.

- A design with **no name** is a scratch design and is forgotten after a day. **Name it** (in *My designs*) and it stays 90
  days after you last touched it; from day 75 it is listed with an "expires soon" warning. Opening it or editing it touches it.
- Limits (an administrator can change them): 50 designs, 50 samples and 25 MB of samples per design, 250 MB in all. Over one,
  the answer names it (`413`, `DRS-5005`).
- Deleting a design deletes its samples at once. Sample contents are never written to a log.

## New screen: bring data, then start

**Build → New screen** asks two things.

**1. Bring data**, any mix of:

- **JSON files, or a whole folder.** Choose files, drop them, or press **Choose a folder**: every `.json` file inside is taken
  (a `.jsonl` file counts one sample per line; other files are counted and left out). A file that is not valid JSON, or too
  big, is listed with its problem and the rest are used.
- **A JSON Schema or a `shape.json`.** Documents are *generated* from its types, enums, formats, examples and bounds. They
  are labelled **synthetic**: good for trying a layout, never evidence that it works on real data.
- **Entities from a store**: a kind and some ids, or a kind and "take this many". They are kept as **references** and read
  again every time you preview, with your current rights and field masks. If your role stops opening that kind, the preview
  says *no access*; nothing is copied.
- **An example.** The cards list every example in `docs/guides/examples/`. **Open as my copy** makes a new design with the
  example's Sutra, its JSON as a sample and its README as notes. Change anything you like: the example files are never
  touched.
- **An existing Sutra**, from the registry or a `.yaml` file.

**2. Start from**: **Auto-design** (the default; needs data), the **existing Sutra** you chose, or an **empty** Sutra for the
kind. Name the design if you want to keep it, then **Create design**. You land on the design's page.

## My designs

**Build → My designs** lists your designs, newest first, with their kind, samples, Sutra revision and when each expires.
**Open**, **Rename**, **Duplicate** (a named copy with its samples) and **Delete** are on each row.

## A design's page: the workbench

A design opens in the **workbench**: Data on the left (the samples, the shape tree and the palette of panel kinds), the screen in the
middle (**Design**, **YAML**, **Summary**), and **Inspector**, **Problems** and **Tests** on the right, with a status bar below.
The Design tab is the real screen with an editing layer: select, move and size panels, drop panels from the palette and fields from
the shape, edit options in the inspector. **Auto-design** drafts a Sutra from the samples as the design's next revision (after
asking, if it would replace one; Undo brings the old one back), and lists what it left out and why.
Studio no longer has a page of its own: this is the editor, with the real preview and the YAML tab beside the canvas.

The whole workbench is explained step by step, with screenshots, in [the Screen designer guide](/help/screen-designer). In the Data
pane, **shape.json** (the schema with `x-drishti`) and **JSON Schema** (annotations removed, for any other tool) download.

## What a shape is

A shape is a JSON Schema (draft 2020-12) of the whole set, plus Drishti's notes under `x-drishti`:

- **Type and presence.** A field in every file is `required`; one in some files shows the share of files that have it
  (`rare` below half). A field with different types in different files is a **conflict**.
- **Role.** What the field is for, and the reason it was chosen: `id`, `link`, `measure`, `dimension`, `status`, `date`,
  `series`, `ohlc`, `distribution`, `grid`, `steps`, `graph`, `tree`, `events`, `text`. The roles come from the same
  rules that lay out a view with no Sutra, so they are explainable.

## Walkthrough: three trades

1. **Build → New screen**. Choose `all-panels-showcase.json`, `pnl-explain.json` and `tree-table.json` from
   `docs/guides/examples/` (or drop them, or choose the whole folder).
2. Leave **Auto-design** selected, type a name, **Create design**.
3. On the design's page, **Data** says what was found. **Look at these first** lists the conflicts and rare fields. Here
   most fields are rare: the three files are different kinds of document. With files of one kind you would see few.
4. In the tree, open `sensitivities[]`: `bucket` is a `dimension`, `dv01` a `measure`. `children` under `units[]` is a `tree`.
5. Read **Sutra** and the list of what auto-design left out ("empty in 2 of 3 samples"). Switch the **Sample** under
   **Preview**. When you want to edit, edit on the canvas or the **YAML** tab.

Try it again with several copies of one example where you delete `pnlHistory` from all but one: that panel is dropped, with
the reason.

Or start from a finished screen: **Build → Examples** (`/build/new#examples`), **Open as my copy** on `all-panels-showcase`.

## Draft a screen: how auto-design decides

Auto-design drafts a complete Sutra and shows:

- **Sutra**: the YAML. Title (the id, a category, a link as "with"), a strip of at most six figures, panels chosen from
  what each field is (a `series` becomes a line, `ohlc` candles, a list of records with several dimensions and a measure
  a pivot with row groups, a tree a tree table...), function keys for the links, and the layout: charts side by side,
  tables full width, small panels and links on the right.
- **Left out because the samples could not fill it**: the draft is rendered against *every* sample. A panel that comes
  out empty or in error for more than half of them (`drishti.builder.prune-share`) is replaced by its next-best kind or
  dropped, and listed with the count ("empty in 4 of 5 samples"). A panel that fails for just a few files stays, and its
  reason says how many.
- **Why each choice, and what else was considered**: a sentence per decision, and under each panel the runner-up kinds
  (for example `area` under a line) with their reasons. Many samples sharpen the draft: a history of 20 points is a line,
  one of 3 a table; a figure that never changes is ranked low in the strip and the one that varies most is emphasised.

## Limits

| Limit | Default | Setting |
|---|---|---|
| Samples in one upload (files, and lines of `.jsonl`) | 50 | `builder.max_samples` |
| One file | 5 MB | `builder.max_file_mb` |
| All files together | 25 MB | `builder.max_total_mb` |
| Designs, samples, megabytes, expiry | 50 / 50 / 25 (250 in all) / 1 day, 90 days | `drishti.builder.designs.*` on the server |

An upload over the first or last limit is refused as a whole, with the limit named; a file over the middle one is left out
and reported. The server refuses the same sizes (`drishti.builder.*`, `413` `DRS-5005`).

Everything above can be set by an administrator: see [Configuration](../../../docs/admin/CONFIGURATION.md).
