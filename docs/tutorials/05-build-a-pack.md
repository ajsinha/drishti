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
# Tutorial 5 · Build a pack from your data

A **pack** teaches Drishti about one kind of thing: what a quote is, which field is its id, how its screen looks, what each field means. In tutorial 4 you connected a folder of desk quotes,
but no pack knew what a quote is. Now you build that pack from a JSON Schema and the folder itself, check it, deploy it without a restart, roll it back, and do the same from the command line with
`pack make`.

## Time to complete

About 30 minutes.

## Before you start

- [Tutorial 4](04-connect-your-data.md) done: the connector `desk-quotes` exists and runs (check with `python3 tools/drishti.py connector list --origin file`).
- Signed in as the development administrator. The server needs `DRISHTI_STUDIO_SAVE=true` (tutorial 1, Step 2), and deploying a pack needs the administrator.
- `uv` for the command-line part (it installs `deltalake` and `pyarrow` for you, on the fly).
- The data: `docs/tutorials/data/schemas/quote.schema.json` (a JSON Schema with a description for every field) and `docs/tutorials/data/desk-quotes/` (the ten day folders).

## What you will learn

- what **Build → New pack** does with schemas and a folder, in five steps;
- how the schema's descriptions, enums and formats become labels, help text and the look of the screen;
- how to read the checks the server runs before it deploys, and what a deploy changes;
- how to roll a pack back, and what is kept for it;
- `pack make`, `pack verify`, `server packs deploy`, `history` and `rollback`.

## Step 1 · Bring the sources

Open **Build**, then **New pack**. Choose the file `quote.schema.json`, then **Choose a folder** and pick `docs/tutorials/data/desk-quotes`.

![Step 1: one schema and ten data files read in the browser, with their rows, sizes and a sample of each](img/t05/01-sources.jpg)

You should see the schema listed, then the ten `quote.jsonl` files with their rows (6 each), sizes and *6 documents sampled*, and a total line: `1 schema, 10 data files, 60 rows read`. **Your files are
read in your browser**: a reservoir sample of each, with the counts and the schema, goes to the console, never the whole folder. Press **Next**.

## Step 2 · Read what was detected

![Step 2: one kind, quote, with its key, split, business date and mnemonic](img/t05/02-kinds.jpg)

You should see one kind, **quote**, with every data file matched to it, and a card: **Key field** `id` (chosen because it is named id, and required), **Business date field** `asOf` (a date-time named like a
business date), the **mnemonic** `QUO`, links to other kinds (none), and the field list (8 fields). The amber line says `no id-like field is unique in all 60 sample documents: id`. That is a heads-up,
not an error: every day has a quote with the same id, because each day is a snapshot of the same six instruments. Within one day `id` is unique, and the business date separates the days.

The card is where you decide: rename the kind, pick another key, split into Sutras by a field (a Sutra is one screen layout) or add links. Every change redoes the plan. Press **Next**.

## Step 3 · Name the pack

![Step 3: the pack's name, code, title, version and description; the kind's source and connector name](img/t05/03-pack.jpg)

Set name `desk-quotes`, title `Desk quotes`, a description, and leave the version `1.0.0` and the code `QP` (typed alone on the command line, `QP`, opens the pack's overview). In the table of kinds set
the **Source** of `quote` to *File connector (JSON Lines per day)* and its **connector name** to `desk-quotes`: a pack refers to its data by a logical connector name only, and
that is the connector you made in tutorial 4. You should see the table read `quote | QUO | File connector | desk-quotes`. Press **Next**.

## Step 4 · Preview every screen

![Step 4: one Sutra drafted, rendered on a real sample, with tabs Preview, Sutra, About text, Samples and Checks](img/t05/04-preview.jpg)

You should see the server draft the screen (a background job with a progress bar), then a list with one entry, `quote-default`, badged *ok*, and its **Preview**: the real view of a quote built from a real sample
(`Q-BUND10Y` or another), with a strip of mid, bid and ask and a *Details* panel. The `df4` format on `bid`, `ask` and `mid` is in the schema (`x-drishti-format`), because a price of 1.0835 must not
show as `1`. The tabs: **Sutra** is the YAML, **About text** is what *About this page* will say, **Samples** and **Checks** show each panel against each sample.

![The Sutra tab: the YAML the wizard wrote, ready to edit](img/t05/05-sutra.jpg)

You should see the screen as text: a `match` (kind quote), a `title`, a `strip` and a `kv` panel whose labels come from the schema. A Sutra is just a file; *Open in the workbench to refine* makes a design
of it (tutorial 6).

## Step 5 · Output and deploy

![Step 5: the bundle with its checksum, how to verify it, and the hand-off to Admin → Packs](img/t05/06-output.jpg)

You should see the **bundle** `desk-quotes-1.0.0.tar.gz` with its SHA-256 and the command that verifies it (`drishti.py pack verify`). Press **Check and deploy in Admin → Packs**.

![Admin → Packs: the server's checks of the archive, and what a deploy would change](img/t05/07-deploy-checks.jpg)

You should see the server unpack the archive and check it: checksum, unsigned (a note, not an error), no path leaves the pack folder, `pack.yaml`, every file listed and matching, the server version it
needs, `sutra lint` and `sutra test` passed. Under **What it changes**: `desk-quotes 1.0.0 is a new pack: it is loaded when you deploy it`, with counters for breaking, selection, layout and other changes
(all 0 for a new pack). Press **Deploy desk-quotes 1.0.0** and confirm in the page's own dialog (the version must be X.Y.Z, as here).

![The history: a deploy of desk-quotes 1.0.0 by you](img/t05/08-deployed.jpg)

You should see, after a moment, a line in **History and roll back**: `deploy desk-quotes 1.0.0`. The pack is in use now, with no restart. Open one of its quotes:

```text
QUO Q-EURUSD <GO>
```

![The view of a quote: mid, bid and ask with four decimals, and the details](img/t05/09-quote.jpg)

You should see the quote's view: `Q-EURUSD` with the title pill `Quote · USD`, the strip (mid, bid, ask), the panel of details (instrument, currency, venue, when it was struck). The panel *How this view was built* names the layout and the source that served the view: here `demo, gen 0`, because the demo source also holds the sample of the pack (a source that gives
its documents no name is shown under its connector's name, never as an empty `Source:`). The figures are the ones of the desk's quotes of tutorial 4. Type `QP` alone to see the pack at a glance.

## Step 6 · Deploy again, and roll back

Build the pack a second time with the version `1.1.0` (it takes the same steps) and deploy it. Admin → Packs now keeps the version it replaced.

![Versions kept for a rollback, and the history of deployments](img/t05/10-versions-kept.jpg)

You should see a table **Versions kept for a rollback**: `desk-quotes 1.0.0`, with a **Roll back to this** button, above the history. Press it and confirm in the dialog. The running version is never offered here, and a version deployed twice is kept once.

![After the rollback the history shows it, and the pack is back at 1.0.0](img/t05/11-rolled-back.jpg)

You should see a new line in the history, `rollback desk-quotes 1.0.0`, replacing `1.1.0`. A rollback is itself kept, so it can be undone. If a new version cannot be started the server puts the old
files back by itself, and the history says *reverted*.

## Step 7 · The same from the command line: `pack make`

Without the page: one command turns a folder of JSON Lines into one folder you can deploy: the pack, the data in a Delta Lake partitioned by business date, a checksummed bundle, a server
configuration and start scripts. `pack make` wants the ids in what it reads to be unique, so give it one day's file (later days are added with `data ingest`):

```text
$ uv run --with pyyaml --with deltalake --with pyarrow python tools/drishti.py pack make docs/tutorials/data/desk-quotes/2026-09-30/quote.jsonl --kind quote --match venue --name desk-quotes --version 1.2.0 --out build/desk-quotes
read 6 quote document(s)
key:   id  (named id, present and unique in all 6 documents)
date:  asOf  (date-valued, named like a business date, 1 distinct date(s))
6 documents, 1 kind(s), 2 group(s)
drafting 2 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
quote           venue=Reuters                                     6       5  quote-reuters
quote           (all)                                             6       5  quote-default
  quote 2026-09-30: 6 rows
kind                business date        rows
quote               2026-09-30              6
total rows: 6
Dated store (delta): 6 rows written to build/desk-quotes/data/delta (domain desk-quotes); 0 document(s) without a date or id skipped
pack written to build/desk-quotes/pack/desk-quotes
sutra lint: exit 0
ok      build/desk-quotes/pack/desk-quotes/sutras/quote/quote-default.v1.sutra.yaml
ok      build/desk-quotes/pack/desk-quotes/sutras/quote/quote-reuters.v1.sutra.yaml
sutra test: exit 0
     quote-default: help coverage 7/7 (100%)
ok   build/desk-quotes/pack/desk-quotes/sutras/quote/quote-default.v1.sutra.yaml (5 samples)
     quote-reuters: help coverage 7/7 (100%)
ok   build/desk-quotes/pack/desk-quotes/sutras/quote/quote-reuters.v1.sutra.yaml (5 samples)

== pack check
== desk-quotes: sutra lint: exit 0
ok      build/desk-quotes/pack/desk-quotes/sutras/quote/quote-default.v1.sutra.yaml
ok      build/desk-quotes/pack/desk-quotes/sutras/quote/quote-reuters.v1.sutra.yaml
== desk-quotes: sutra test: exit 0
     quote-default: help coverage 7/7 (100%)
ok   build/desk-quotes/pack/desk-quotes/sutras/quote/quote-default.v1.sutra.yaml (5 samples)
     quote-reuters: help coverage 7/7 (100%)
ok   build/desk-quotes/pack/desk-quotes/sutras/quote/quote-reuters.v1.sutra.yaml (5 samples)

== pack bundle
bundled desk-quotes 1.2.0: 24 files
  build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz
  sha256 0240aef835b10edb8e9bf19cd4b8c318daf01b423ad1aca942b70ec7acfc7df6

== pack verify
verify build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz: desk-quotes 1.2.0
  checksums ok
  schema    ok
  server    ok
  sutra     ok
verified

pack make: build/desk-quotes
  2 Sutra group(s) for 6 quote document(s), 1 date(s) in data/delta; pack check passed, pack verify passed
next: read build/desk-quotes/README.txt, then  DRISHTI_JAR=<exec jar> DRISHTI_PORT=18480 build/desk-quotes/run-server.sh
```

You should see it choose the key and the date and say why, one Sutra for each venue (Reuters has one of its own, plus a catch-all), then run `pack check` and `pack verify` and finish with
`pack check passed, pack verify passed`. This is the folder it made:

```text
$ find build/desk-quotes -maxdepth 3
build/desk-quotes
build/desk-quotes/bundle
build/desk-quotes/bundle/desk-quotes-1.2.0.manifest.json
build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz
build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz.sha256
build/desk-quotes/config
build/desk-quotes/config/drishti-site.yaml
build/desk-quotes/data
build/desk-quotes/data/delta
build/desk-quotes/data/delta/desk-quotes
build/desk-quotes/MANIFEST.json
build/desk-quotes/pack
build/desk-quotes/pack/desk-quotes
build/desk-quotes/pack/desk-quotes/config
build/desk-quotes/pack/desk-quotes/generated
build/desk-quotes/pack/desk-quotes/pack.yaml
build/desk-quotes/pack/desk-quotes/README.md
build/desk-quotes/pack/desk-quotes/samples
build/desk-quotes/pack/desk-quotes/sutras
build/desk-quotes/pack/desk-quotes/tests
build/desk-quotes/README.txt
build/desk-quotes/run-server.ps1
build/desk-quotes/run-server.sh
```

Verify the bundle, as a colleague would before deploying it:

```text
$ python3 tools/drishti.py pack verify build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz
verify build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz: desk-quotes 1.2.0
  checksums ok
  schema    ok
  server    ok
  sutra     ok
verified
```

Ask the server what a deploy would do, without doing it (`--preview` discards the upload):

```text
$ python3 tools/drishti.py server packs deploy build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz --preview
archive desk-quotes-1.2.0.tar.gz (4045 bytes, sha256 0240aef835b10edb...)
  ok    archive         4045 bytes, sha256 0240aef835b10edb...
  ok    checksum        matches the sha256 you gave
  warn  signature       not signed; sign it with a publisher key listed in drishti.packs.registry.trusted-keys to prove where it came from
  ok    unpack          25 files, 13 KB; no path leaves the pack folder, no links
  ok    pack.yaml       desk-quotes 1.2.0
  ok    manifest        24 files listed; every checksum matches; nothing unlisted
  ok    server version  needs >=2.0.0, this is 2.2.0
  ok    sutra lint      2 Sutra file(s) passed
  ok    sutra test      2 Sutra file(s) passed
  ok    dependencies    extends no other pack

Preview: desk-quotes 1.0.0 (loaded) -> 1.2.0 (newer)
  SELECTION (1)
    sutra added                quote-reuters   sutras/quote/quote-reuters.v1.sutra.yaml: match $.venue == 'Reuters', priority 10
  LAYOUT (2)
    routes changed             quote
    columns changed            quote   order changed
  CHANGE (11)
    version                    desk-quotes   1.0.0 -> 1.2.0
    sutra changed              quote-default   content changed
    about about                quote   text changed
    glossary changed           quote.asOf
    glossary changed           quote.ask
    glossary changed           quote.bid
    glossary changed           quote.currency
    glossary removed           quote.id
    glossary changed           quote.instrument
    glossary changed           quote.mid
    glossary changed           quote.venue
  summary: 1 selection, 2 layout, 11 change

preview only: nothing was deployed
```

You should see the checks again and a **Preview** that says `desk-quotes 1.0.0 (loaded) -> 1.2.0 (newer)` with the changes counted. Then deploy it for real, look at the history, roll back and
look again:

```text
$ python3 tools/drishti.py server packs deploy build/desk-quotes/bundle/desk-quotes-1.2.0.tar.gz
archive desk-quotes-1.2.0.tar.gz (4045 bytes, sha256 0240aef835b10edb...)
  ok    archive         4045 bytes, sha256 0240aef835b10edb...
  ok    checksum        matches the sha256 you gave
  warn  signature       not signed; sign it with a publisher key listed in drishti.packs.registry.trusted-keys to prove where it came from
  ok    unpack          25 files, 13 KB; no path leaves the pack folder, no links
  ok    pack.yaml       desk-quotes 1.2.0
  ok    manifest        24 files listed; every checksum matches; nothing unlisted
  ok    server version  needs >=2.0.0, this is 2.2.0
  ok    sutra lint      2 Sutra file(s) passed
  ok    sutra test      2 Sutra file(s) passed
  ok    dependencies    extends no other pack

Preview: desk-quotes 1.0.0 (loaded) -> 1.2.0 (newer)
  SELECTION (1)
    sutra added                quote-reuters   sutras/quote/quote-reuters.v1.sutra.yaml: match $.venue == 'Reuters', priority 10
  LAYOUT (2)
    routes changed             quote
    columns changed            quote   order changed
  CHANGE (11)
    version                    desk-quotes   1.0.0 -> 1.2.0
    sutra changed              quote-default   content changed
    about about                quote   text changed
    glossary changed           quote.asOf
    glossary changed           quote.ask
    glossary changed           quote.bid
    glossary changed           quote.currency
    glossary removed           quote.id
    glossary changed           quote.instrument
    glossary changed           quote.mid
    glossary changed           quote.venue
  summary: 1 selection, 2 layout, 11 change

deployed desk-quotes 1.2.0 (replacing 1.0.0): Applied now; no restart.
```

```text
$ python3 tools/drishti.py server packs history --pack desk-quotes --limit 6
  when (UTC)           action    pack         version  replaced  by
  -------------------  --------  -----------  -------  --------  -----------------
  2026-10-12 02:17:43  deploy    desk-quotes  1.2.0    1.0.0     drishti-dev-admin
  2026-10-12 02:16:58  rollback  desk-quotes  1.0.0    1.1.0     drishti-dev-admin
  2026-10-12 02:16:46  deploy    desk-quotes  1.1.0    1.0.0     drishti-dev-admin
  2026-10-12 02:16:29  deploy    desk-quotes  1.0.0              drishti-dev-admin
  kept for rollback, desk-quotes: 1.0.0, 1.1.0
```

```text
$ python3 tools/drishti.py server packs rollback desk-quotes --version 1.0.0
rolled desk-quotes back to 1.0.0 (replacing 1.2.0): Applied now; no restart.
```

```text
$ python3 tools/drishti.py server packs history --pack desk-quotes --limit 3
  when (UTC)           action    pack         version  replaced  by
  -------------------  --------  -----------  -------  --------  -----------------
  2026-10-12 02:17:44  rollback  desk-quotes  1.0.0    1.2.0     drishti-dev-admin
  2026-10-12 02:17:43  deploy    desk-quotes  1.2.0    1.0.0     drishti-dev-admin
  2026-10-12 02:16:58  rollback  desk-quotes  1.0.0    1.1.0     drishti-dev-admin
  kept for rollback, desk-quotes: 1.2.0, 1.1.0
```

You should see the rollback recorded and the pack back at `1.0.0`. A **breaking** change (a kind or mnemonic removed or renamed) makes `deploy` stop with exit 1 until you add `--accept-breaking`.

## Next steps

- [Tutorial 6 · Design a screen](06-design-a-screen.md) improves the screen the wizard drafted.
- [SCHEMA_TO_PACK.md](../guides/SCHEMA_TO_PACK.md) lists every rule (keys, links, formats, enums, splitting a kind); [CLI_GUIDE.md](../guides/CLI_GUIDE.md) the commands; [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md)
  how a pack is laid out; [OPERATIONALISING.md](../guides/OPERATIONALISING.md) how to promote a pack between servers.
- Tutorial 7 tells Drishti when a day's data lands, so the pack's screens refresh.

## If something goes wrong

| You see | Meaning |
|---|---|
| the amber `no id-like field is unique` | your ids repeat across days; with a business-date field that is correct. If the ids should be unique, pick another key in the card |
| a kind flagged *another pack already defines it* | rename your kind in the card: two packs cannot own one kind |
| the deploy checks show a red line | read it: a checksum, a `pack.yaml` problem or a Sutra that fails its tests; the pack is not deployed |
| `QUO` does not open anything | the pack is not loaded: look at Admin → Packs; and the connector must run (tutorial 4) |
| `pack make` says no unique key | pass `--key FIELD`, or give it one day's file |
