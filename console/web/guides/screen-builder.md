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
# Screen Builder · JSON files to a shape

You have a folder of JSON documents (trades, shipments, patients, sensor readings) and want a screen for them. The
**Screen Builder** gets you there in steps. This guide covers what works today, steps 1 to 3; the whole plan is in
[the Screen Builder design](../../../docs/architecture/SCREEN_BUILDER.md).

| Step | What it does | State |
|---|---|---|
| 1. Samples to shape | Merges up to 50 documents into one JSON Schema that says what every field *is*. | Works: `POST /api/v1/builder/shape` |
| 2. Shape extractor page | The page for it: **Build → Screen Builder → Shape extractor** (`/build/shape`). | Works |
| 3. Auto-design | **Draft a screen**: a complete Sutra from the shape and the samples, with a reason for every choice. | Works: `POST /api/v1/builder/design` and `/suggest` |
| 4 to 7 | Visual designer, check against every sample, save and export. | Planned |

Everyone who is signed in may use it: designing is open to all, and only saving or proposing a Sutra needs the **author** power. Nothing you upload is written to any store.

## What a shape is

A shape is a JSON Schema (draft 2020-12) of the whole set, plus Drishti's notes under `x-drishti`:

- **Type and presence.** A field in every file is `required`; one in some files shows the share of files that have it
  (`rare` below half). A field with different types in different files is a **conflict**.
- **Role.** What the field is for, and the reason it was chosen: `id`, `link`, `measure`, `dimension`, `status`, `date`,
  `series`, `ohlc`, `distribution`, `grid`, `steps`, `graph`, `tree`, `events`, `text`. The roles come from the same
  rules that lay out a view with no Sutra, so they are explainable. Hover a role badge, or press Enter on a field, to
  read why.

## Walkthrough: three trades

The examples under `docs/guides/examples/` are ready-made documents.

1. Open **Build → Screen Builder → Shape extractor**.
2. Choose `all-panels-showcase.json`, `pnl-explain.json` and `tree-table.json` (or drop them on the box). Each `.json`
   file is one sample. A `.jsonl` file counts one sample per line.
3. The line under the box says how many samples, paths, conflicts and rare fields there are. A file that is not valid
   JSON, or is too big, is listed with its problem and the rest are used.
4. **Look at these first** lists the conflicts and rare fields. Here most fields are rare: the three files are different
   kinds of document, so a field is in one file out of three (33%). With files of one kind you would see few.
5. In the **Schema** tree, use the arrow keys, or click, to open `sensitivities[]`: `bucket` is a `dimension`, `dv01` a
   `measure`. `children` under `units[]` is a `tree`. Type `pnl` into the filter to narrow the tree.
6. Take the result with you: **shape.json** (the schema with `x-drishti`), **JSON Schema** (annotations removed, for any
   other tool), or **Copy schema**.
7. **Open in Studio** opens Studio with your first file pasted as the sample document and *Preview against this JSON*
   on. Press **Start from inference** for a first Sutra.

## Draft a screen

With files loaded, press **Draft a screen**. Drishti drafts a complete Sutra and shows:

- **Sutra**: the YAML. Title (the id, a category, a link as "with"), a strip of at most six figures, panels chosen from
  what each field is (a `series` becomes a line, `ohlc` candles, a list of records with several dimensions and a measure
  a pivot with row groups, a tree a tree table...), function keys for the links, and the layout: charts side by side,
  tables full width, small panels and links on the right. It is the same rule set that lays out a view with no Sutra.
- **Preview of the first sample**: the real view, as Studio draws it.
- **Left out because the samples could not fill it**: the draft is rendered against *every* sample. A panel that comes
  out empty or in error for more than half of them (`drishti.builder.prune-share`) is replaced by its next-best kind or
  dropped, and listed with the count ("empty in 4 of 5 samples"). A figure missing from most files is left out of the
  strip the same way. A panel that fails for just a few files stays, and its reason says how many.
- **Why each choice, and what else was considered**: a sentence per decision, and under each panel the runner-up kinds
  (for example `area` under a line) with their reasons. Many samples sharpen the draft: a history of 20 points is a line,
  one of 3 a table; a figure that never changes is ranked low in the strip and the one that varies most is emphasised.

**Open in Studio** opens Studio with the drafted Sutra and your first sample as the document. Nothing is saved; edit
the Sutra there as usual. (The visual designer, to drag panels and drop fields on a canvas, is a later step.) Uploading
new files forgets the draft.

Try it with `all-panels-showcase.json`, then with several copies where you delete `pnlHistory` from all but one: that
panel is dropped, with the reason.

## Limits and what is kept

| Limit | Default | Setting |
|---|---|---|
| Samples in one upload (files, and lines of `.jsonl`) | 50 | `builder.max_samples` |
| One file | 5 MB | `builder.max_file_mb` |
| All files together | 25 MB | `builder.max_total_mb` |

An upload over the first or last limit is refused as a whole, with the limit named; a file over the middle one is left
out and reported. The server refuses the same sizes (`drishti.builder.*`, `413` `DRS-5005`).

Your last set is kept in the console's memory for `builder.ttl_hours` (default 24), so a reload shows it again, and is
then forgotten. **Forget files** clears it at once. It is never written to a store or a log; the console logs only counts.

Everything above can be set by an administrator: see [Configuration](../../../docs/admin/CONFIGURATION.md).
