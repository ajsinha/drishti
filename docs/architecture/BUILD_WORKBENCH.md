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
# Build workbench: one place to design, test and ship screens

Status: agreed architecture; replaces steps 4–7 of [SCREEN_BUILDER.md](SCREEN_BUILDER.md) (steps 1–3 are built and stay:
the shape API, the shape extractor, auto-design and suggest). Owner: the Build menu, the builder APIs, the Rachana editor.

## Why

The Build tools grew one at a time and do not know about each other:

- **Four kinds of "sample", stored four ways.** Studio's pasted JSON is one document in a text box and is never kept;
  Studio's test entities are only `{kind, id}` references to stored entities (`StudioTestsController`), so someone who has
  only JSON files cannot have tests; the Screen Builder's sample sets live in the console's memory (one set per user,
  lost on a console restart, wrong with two console replicas); the examples are a fourth source read from
  `docs/guides/examples/`.
- **Hand-offs by query flags.** Shape extractor → "Open in Studio" carries the *first* sample only; the other 49 are
  lost, and nothing goes back the other way.
- **Three checks that answer one question** ("does this layout work on my data?"): Studio's test of one entity,
  auto-design's pruning over all samples, and the planned check-all matrix. They would drift apart.
- **Two "draft me a layout" buttons** on two pages (Start from inference, Draft a screen) that give different results
  for the same data.
- **APIs under four roots** (`/api/v1/studio`, `/api/v1/me/studio-tests`, `/api/v1/builder`, `/api/v1/sutras/proposals`)
  and two console prefixes (`/studio`, `/build`).
- **Two grid editors planned.** Layout mode already moves and resizes panels on the grid with keyboard alternatives
  (`layout.js` over `SutraLayoutEditor`); the designer needs the same and the old plan did not share it.
- **No front door, no "my work".** The Build menu has no *New screen*; work cannot be named, resumed, versioned or
  proposed with evidence.
- **Pack authors outside the loop.** No lint or test command for CI; pack export was the very last step.

The cost, for the person with 40 JSON files: they upload, draft, land in Studio with one document, cannot flip to file
17 to see why a panel is empty, cannot keep the samples as tests, and lose everything tomorrow.

## The core object: a Design

Everything in the workbench is a view of one **Design**, owned by one user and kept on the server.

```
Design { id, name, owner, kind, base: "trade-blotter@7" | null,
         samples: [ {name, document} | {name, ref: {kind, id}} | {name, synthetic: true, document} ],
         shape:   derived from the samples, cached by their hash
         sutra:   YAML text + revision number
         ops:     the operation log since base (undo, redo, replay when the base moves)
         tests:   [ {sample, expect: {noErrors, nonEmpty: [panel ids], ...}} ]
         notes:   markdown
         status:  draft | checked | proposed(proposalId) | approved | live(version) }
```

- **Samples** are files you brought, references to stored entities (re-read each time through the normal sources,
  with your current rights and field masks: never snapshots), or synthetic documents generated from a schema when you
  started from a schema alone. Synthetic samples are labelled and never count as evidence.
- **Lifecycle.** `checked` means the last check is green for the current YAML; any edit returns to `draft`. *Propose*
  uses the existing four-eyes review, with the check matrix, sample names and notes attached as evidence. Approval
  makes it `live(vN)` through the registry's versioning. *Edit* on a live Sutra opens a new Design with `base` set; its
  operations replay when the base version moves, and conflicts show as problems, never as silently dropped edits.
- **Scratch designs.** Opening the workbench without naming anything creates an unnamed Design that expires after a day.
  Trying never asks for a name or a permission.

## One workbench page

```
┌ Data ────────────┬ [ Design | YAML | Summary ]   split view ──────┬ [ Inspector | Problems | Tests ] ┐
│ Samples (40) ▸   │  canvas: the real view + selection handles     │ form for the selected element,     │
│ Shape tree, roles│  or the YAML editor (same document)            │ generated from the Rachana schema  │
│ Palette          │                                                │ problems: YAML, bindings, presence │
│                  │                                                │ matrix: panel × sample             │
├──────────────────┴─ status bar: ◀ sample 3/40 ▶ · desktop/phone · theme · ✓ 38/40 · rev 12 · Propose… ┤
```

- **Data** (left): the samples (add files, a folder, store references, examples; switch the previewed one), the shape
  tree with roles (today's extractor), and the palette of the 20 panel kinds.
- **Design** (centre): the visual canvas, drawn by the same renderer users see, with handles to select, move and resize
  on the grid; fields dropped from the shape become panels or columns (suggestions from `/builder/suggest`).
- **YAML**: the Sutra text (today's Studio editor). Typing is an operation like any other, so undo and the revision
  history cover it. **Summary**: today's Studio summary.
- **Inspector / Problems / Tests** (right): every option of the selection; every problem with a jump to its line or
  panel; the panel × sample matrix, re-run as you type.
- **Try on the spot**: the status bar switches the previewed sample, and *Preview with a file…* previews any JSON file
  immediately without adding it to the Design.

Studio, the extractor and the designer stop being pages: they are the YAML tab, the Data pane and the Design tab of one
page. Studio's keys (Ctrl+S, Ctrl+Enter), its File menu and its tabs stay inside the YAML view.

## One "New" flow

`/build/new` asks two things:

1. **Bring data** (any mix): JSON or JSONL files or a whole folder; `shape.json` or any plain JSON Schema (synthetic
   samples are generated from its types, enums, formats and examples); entities from a store (a kind and a count, or
   ids); an example; or an existing Sutra (from the registry or a `.yaml` file).
2. **Start from**: auto-design (the default when there are samples), an existing Sutra, or an empty Sutra for the kind.

"Start from inference" stops being a separate button: with one sample, auto-design *is* inference, which makes the
"one inference" rule visible.

## Menu and addresses

```
Build ▾
  Create   New screen (/build/new) · My designs (/build) · Examples (/build/new#examples)
  Govern   Reviews (/build/reviews)  [pending count]
  Learn    Screen designer guide · Sutra guide · Rachana reference · Build a pack
```

Old addresses keep working (302, query kept, covered by tests): `/studio` → a scratch Design's YAML tab;
`/studio?example=x`, `?sutra=n@v`, `?kind=&id=` → the same, started from that example, Sutra or entity;
`/studio/reviews[/{id}]` → `/build/reviews[/{id}]`; `/build/shape` → `/build/new#files`.

## Productivity multipliers, in order

| # | Multiplier | Why | Cost |
|---|---|---|---|
| 1 | Persistent named Designs and *My designs* (resume, rename, duplicate) | No lost work; the base for the rest | M |
| 2 | Sample switcher and a live Problems panel (YAML, bindings, presence, conflicts; each jumps to its place) | Ends "it worked on the one file I tried" | S |
| 3 | Tests as you type: on idle, every sample is checked, stale runs are cancelled, the matrix and status bar update | Evidence all the time, not at the end | M |
| 4 | Visual canvas with field drops and suggestions, inspector from the Rachana schema, undo/redo | Screens without writing YAML | L |
| 5 | Command palette (Ctrl+K): add panel, go to panel, next sample, run check, switch tab, propose | Keyboard-first; also the accessible path for every drag | S |
| 6 | Diff and versions: against the base, the live version or any revision; restore a revision | Reviewers and authors see the change, not the file | S |
| 7 | Headless CLI for CI: `java -jar drishti-server.jar sutra lint\|test\|shape\|design\|preview <pack-dir>`, exit codes, JUnit XML, preview snapshots; tests in `packs/<p>/tests/<sutra>/*.json` with `expect.yaml` | Pack authors run in CI exactly what the workbench runs | M |
| 8 | Export a pack fragment (pack.yaml stub, Sutra, tests, three samples); import a pack folder or zip as Designs | From JSON files to a pack in minutes | M |
| 9 | Snippets per panel kind, generated from the Rachana schema; examples as templates | Fewer trips to the reference | S |
| 10 | File binding in development: a Design bound to a file under `drishti.rachana.dirs`; save writes it, hot reload brings IDE edits back | Pack authors keep their IDE and git | M |
| 11 | Read-only share link to a Design (Sutra, operations, sample *names*, never sample contents) | Pairing and review without leaking data | S–M |

## Architecture

**Server.** The stateless endpoints stay for the CLI and for composition: `/api/v1/builder/{shape, design, suggest}`,
plus `edit` (`{yaml, ops}` → `{yaml, problems}`) and `check` (`{yaml, samples}` → matrix). One project resource composes
them, with no logic of its own: `/api/v1/builder/designs` (list, create, read, update, delete), `/{id}/samples`
(upload, from store, remove), `/{id}/ops` (`{baseRev, ops}` → `{rev, yaml, problems, preview}`, 409 when stale),
`/{id}/preview?sample=`, `/{id}/check`, `/{id}/suggest`, `/{id}/autodesign`, `/{id}/propose`, `/{id}/export`.
`/api/v1/studio/*` stays for one release as thin aliases.

- **One checker.** `SampleChecker` in the engine answers "does this Sutra work on these samples": Studio's test,
  auto-design's pruning and the matrix all delegate to it.
- **Operations** live in a `design.ops` package in `drishti-rachana`: a sealed `Op` interface with one small class per
  operation (`AddPanel`, `Move`, `SetOption`, `Bind`, `Remove`, `SetTitle`, `SetStrip`, `SetKeys`, `SetMatch`, `Text`)
  and an `OpApplier`; `SutraLayoutEditor` keeps placement and comment preservation and is reused, not grown.
- **Persistence.** A `DesignStore` interface with a file store (`data/designs/<user>/<id>/`, files readable by the
  server only) and a JPA store, like the preference store. The console keeps no sample sets in memory any more.
  Settings: 50 designs per user, 50 samples and 25 MB per design, 250 MB per user; scratch designs expire after a day,
  named ones after 90 days untouched (with a warning at 75). Sample contents are never logged; deleting a Design
  deletes its samples at once.
- **Checking cost.** Checks run with a per-user concurrency cap and a time budget, cancel when superseded, and skip
  panels whose inputs did not change.

**Console.** Routes in `console/routes/build/` (pages and a thin proxy). JavaScript in `static/js/build/` as plain
modules, each under about 400 lines: `editor.js` (from Studio's), `preview.js`, `canvas.js`, `grid-keys.js` (taken out
of `layout.js` and shared with layout mode), `inspector.js`, `samples.js`, `problems.js`, `tests.js`, `ops.js`,
`palette.js`, `workbench.js`. Vendored only.

**Permissions** (product owner's rule). Creating, editing, checking, previewing pasted or referenced samples, and
exporting your own Design are open to every signed-in user. Saving to the registry or to a bound file needs the author
right and Studio saving switched on; proposing needs the author right; approving needs the approve right; loading a
pack is for administrators. Previews of stored entities and `source:` links keep the kind rights and field masks.

**Not built:** a client-side renderer or a canvas model of its own; real-time co-editing (revisions are enough); a
visual expression builder (autocomplete and the server's parser instead); git in the browser (file binding instead);
publishing packs from the workbench (`tools/packreg` stays); per-Design access lists in the first version.

## Build plan

Each step ships on its own, with tests that fail before it, its documents and a drill. The visual canvas comes before
Studio is folded in, because it is what users are waiting for.

| Step | Delivers | Accepted when |
|---|---|---|
| **4. Designs** | `DesignStore` (file, JPA), `/builder/designs` with samples, quotas and expiry; `/build` (My designs) and `/build/new` (files, folder, schema with synthetic samples, store references, examples, existing Sutra); the extractor becomes the Data view; `/build/shape` redirects | a sample set survives a console restart; two consoles see the same Design; limits answer with DRS codes; a reference to a kind the user may no longer open previews as "no access" |
| **5. Operations and one checker** | the `design.ops` package; stateless `/builder/edit` and `/builder/check`; `SampleChecker` behind Studio's test and auto-design's pruning | every operation on all 20 kinds keeps comments and order; a bad operation is a located problem; a sample set with a missing field shows the right empty and error cells |
| **6. Workbench with the visual canvas** | `/build/d/{id}`: Data, Design (canvas, palette, field drops with suggestions, inspector, undo/redo, keyboard path, `grid-keys.js` shared with layout mode), YAML, Problems, Tests as you type, sample switcher, *Preview with a file…*, phone and theme toggles; the guide `docs/guides/SCREEN_DESIGNER.md` and its help entry | the all-panels showcase is built from its samples without typing YAML, by mouse and by keyboard only; a folder → auto-design → switch samples → fix → green matrix test; layout mode's tests pass with the shared module |
| **7. Studio folded in** | all `/studio*` addresses redirect into the workbench; Studio's test entities become tests on a Design; command palette; diff and versions; the guide grows these chapters | every old Studio address in the console tests lands on an equivalent screen; keyboard-only use across panes |
| **8. Ship and scale** | propose with evidence, reviewers see the matrix, approval makes it live; pack fragment export and folder/zip import; the headless CLI with the `packs/<p>/tests/` convention run over every shipped pack in the build; development file binding; read-only share links; the guide's end-to-end walkthrough | folder → design → check → propose → approve → the view serves the new version; an exported fragment loads through Admin → Packs and passes `sutra test` |

## Risks

- **Samples on the server.** This changes the promise in SCREEN_BUILDER.md that samples are never kept; it needs the
  owner's decision below, server-only file permissions, no logging and immediate deletion.
- **Checking on every pause** over 50 samples: bounded by debounce, cancellation, a per-user cap and skipping unchanged
  panels.
- **Studio users' habits**: kept by its keys, tabs and File menu living on in the YAML view.
- **Rebasing operations** when the base Sutra changed: conflicts are shown as problems.
- **Two editors on one Sutra** (layout mode and the workbench): both go through revisions, so the second writer is told.

## Decisions for the product owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | Where samples are kept | On the server per user (named designs 90 days, scratch one day), files readable by the server only |
| 2 | Store samples: references or snapshots | References, re-read with the user's current rights and masks |
| 3 | Studio's page | Retire it behind redirects in step 7 (same keys and tabs in the YAML view) |
| 4 | Sharing | Read-only links without sample contents, in step 8 |
| 5 | File binding | Development servers first; authoring servers with Studio saving switched on later, if wanted |
