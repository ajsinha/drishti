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

Status: agreed architecture; steps 4 to 8 are built; replaces steps 4–7 of [SCREEN_BUILDER.md](SCREEN_BUILDER.md) (steps 1–3 are built and stay:
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
- **Versions are never rewritten.** A Design made from `name@v` proposes `name@(latest+1)` (the `version:` line is renumbered as an
  undoable step); a Design not based on an existing `name@version` that says the same name and version is refused at *propose*
  time with `DRS-2028` (saying when a pack owns it), not at approval.
- **Rebase.** When the base has a newer live version the Design says so (`baseMoved` on open, check and propose; a status-bar
  chip and a Problems entry) and *Ship -> Rebase* (`POST /{id}/rebase`) replays the operations on the new base. A step that no longer
  applies, a hand-typed text edit, and a full step log become problems (`DRS-5025`-coded) naming the step; the log starts again from
  the new base (undo does not go back past a rebase).
- **Quotas count everything.** Sutra, notes and tests are counted with the samples in the Design and user limits and have caps of their
  own (`max-sutra-kb`, `max-notes-kb`, `max-tests-kb`, `DRS-5005`). Scratch Designs have a separate small cap (`max-scratch`, oldest
  evicted first) and never fill the named quota; `GET /studio?...` only asks (a confirmation page); `POST /studio` creates.
- **Evidence and links show only what the viewer may see.** Reference samples carry entity ids in their names: other viewers see
  `kind (reference)`; the notes go only to the author and to approvers.
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
  tree with roles (today's extractor), and the palette of the 21 panel kinds.
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

### Examples stay, and open as your own copy

The eleven examples in `docs/guides/examples/` (including the big all-panels showcase: all 21 panel kinds with its data)
are kept and remain the reference set. In the workbench every example opens as a **new Design that is a copy**: its
Sutra, its JSON as samples and its README as notes, ready to view, tweak, check and even propose; the files in
`docs/guides/examples/` are never changed by the workbench. The all-panels showcase is what a scratch Design opens on
when nothing else is asked for (the setting that makes Studio open on it today, `ui.studio_example`, moves with it).
The examples test (every kind covered, nested pivot and tree table present) stays and also checks that each example
opens as a Design and previews with no panel errors.

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
  **As built (step 5):** `Op` (sealed) with `AddPanel` (`kind`, `id?`, `at {area, before|after, span, height}`, `options`), `Move` (through
  `SutraLayoutEditor`, which now can leave the version line alone), `SetOption` (null removes), `Bind` (roles are the options the kind
  accepts that hold a path, plus `column`, `field`, `series` which append; no role: the kind's next unfilled one), `Remove`, `SetTitle`,
  `SetStrip`, `SetKeys`, `SetMatch`, `Text`; `Ops` reads and writes their JSON (`{"op":"addPanel",...}`) and names the operation and
  field that is wrong. The line helpers the editors share moved to `SutraText`. `OpApplier` applies a list, re-parses after each
  operation and skips one that fails or would make an invalid Sutra, with an `OpProblem {op, name, code, message, line}`. Values are written
  as one-line flow YAML (plain when that reads back the same, else quoted), so a panel written as a flow mapping stays one. Panels
  inside a `tabs` body and a `panels:` written as one flow list cannot be edited (`DRS-5024`).
- **The log.** A design's `ops` is a list of steps `{ops, before, after, at}` with a cursor `opsAt`: `/ops` appends (dropping the redo
  tail), `/undo` and `/redo` move the cursor and restore the text, every move is a new `rev`; a text change made by `PATCH` or
  auto-design is a step too; at most `drishti.builder.designs.max-ops` (100) steps are kept. A stale `baseRev` is `409 DRS-5007`.
- **The checker as built.** `SampleChecker.check(panelIds, inputs, renderer)` in `engine.design` returns `Matrix {ok, samples, panels
  [{id, cells, counts}], strip [{label, blank}], counts}`; the renderer (the server's `SampleCheckService`, or the previewer auto-design
  is given) carries the caller's masks and open rights, throws `NoAccess` for a reference to a kind the caller may not open (a panel whose
  `source` is one is `noAccess` too), and a render failure is an `error` column. Auto-design's pruning reads its counts from the
  matrix; the console's `POST /studio/test` asks `/builder/check` for one sample and reshapes the answer as before.
- **Persistence.** A `DesignStore` interface with a file store (`data/designs/<user>/<id>/`, files readable by the
  server only) and a JPA store, like the preference store. The console keeps no sample sets in memory any more.
  Settings: 50 designs per user, 50 samples and 25 MiB per design, 250 MiB per user (binary megabytes, 1,048,576 bytes; the messages say MiB); an upload over the limit is answered `413` after the body is read, never a closed connection; scratch designs expire after a day,
  named ones after 90 days untouched (with a warning at 75). Only writes extend a Design's life; reads (opening, previewing, exporting) do not. Sample contents are never logged; deleting a Design
  deletes its samples at once.
- **Checking cost.** Checks run with a per-user concurrency cap and a time budget, cancel when superseded, and skip
  panels whose inputs did not change.

**Console.** Routes in `console/routes/build/` (pages and a thin proxy). JavaScript in `static/js/build/` as plain
modules, each under about 400 lines: `editor.js` (from Studio's), `preview.js`, `canvas.js`, `grid-keys.js` (taken out
of `layout.js` and shared with layout mode), `inspector.js`, `samples.js`, `problems.js`, `tests.js`, `ops.js`,
`palette.js`, `workbench.js`. Vendored only.

**As built (step 6).** The page is `templates/build/design.html` plus `static/css/build-workbench.css`; the scripts in `static/js/build/`
are `ops.js` (the store: state, the event bus and the one way to change a design, `send(ops)`: serialised, with `baseRev`, a 409
reloads the design and says so), `yamlmodel.js` (reads the Sutra with Studio's forgiving reader, joining multi-line flow maps, for
the inspector), `drag.js` (one pointer-driven drag for palette kinds, shape fields and panel headings; no HTML5 drag-and-drop),
`menus.js` (the keyboard-first menu behind *Add panel…*, *Bind field…* and the ranked suggestions), `palette.js`, `expr.js`
(`$.`/`@.`/function completion), `actions.js` (add, move, size, remove, bind and suggest as operations; the drag and its key share
it), `canvas.js` (decorates the real preview: selection, hover, move, resize, drop bars, keys), `inspector.js` (the form from the
Rachana schema), `data.js` (samples and the shape tree), `yamltab.js` (Studio's CodeMirror; typing is a `text` operation),
`problems.js`, `tests.js` (check on idle, stale runs dropped), `draft.js`, `workbench.js` (wiring, tabs, status bar) and `grid-keys.js`.
The console adds `POST /build/designs/{id}/ops|undo|redo|check|suggest|preview-file`, `GET /build/designs/{id}` and
`GET /build/designs/{id}/sample`; they proxy the server and render the preview model with Studio's own partial. Decisions: resizing is
`setOption span|height` (a `move` with only a size sends the panel to the end of its column); a palette drop supplies the kind's required
options from the shape (the server refuses a panel without them) and the inspector marks them to check; a dropped field binds when it
lands inside a panel and asks for suggestions when it lands on a panel's rim, between panels or on empty space; the suggestions call the
Design's shape then `/builder/suggest` (a design-level `/{id}/suggest` on the server was not needed). Browser tests run against the real
server jar (`console/tests/wb_live.py`; skipped when the jar or JDK 25 is missing).

**As built (step 8).** *Propose with evidence:* `POST /builder/designs/{id}/propose {note}` (the workbench's *Submit for review*; the console's
`/save` route is gone) re-runs the check and calls `SutraGovernance.propose(text, note, who, evidence)`; `Proposal` has an `evidence` field
(design id, name, owner, revision, base, notes, `sampleNames`, `syntheticSamples`, the matrix; never sample contents), shown on
`/build/reviews/{id}` as the panel by sample table. `SutraGovernance` tells `ProposalListener`s after every decision, and `DesignShip`
keeps the Design's status in step: `proposed(P-n)` when proposed, `live(vN)` on approval, `draft` on rejection or withdrawal, each only
if the Design is still at the proposed revision (any later edit already made it `draft`). With governance off the Sutra is saved and the
Design is `live(vN)` at once. *Pack fragments:* `PackFragment` builds and reads the zip (`GET /{id}/export`, `POST /import` with a zip or
the console's folder as JSON; zip entries are read in memory with count and size limits, nothing touches the disk); the layout is
`pack.yaml` stub, `sutras/<domain>/`, `tests/<sutra>/*.json` + `expect.yaml` (`noErrors`, `nonEmpty` from the matrix), `samples/<kind>/`,
README. *CLI:* `com.ash.drishti.server.cli` (`DrishtiApplication.main` hands `sutra ...` to `CliLauncher`, a non-web Spring context with
temporary identity, governance and design folders); `SutraCliTest` runs `sutra test` over every shipped pack's `tests/` folder and the ten
examples; the format is in `docs/guides/SUTRA_CLI.md`. *File binding:* `drishti.builder.file-binding` (default false) in `drishti.builder.dev-dir/<user>/` (never a directory the registry loads,
so saving cannot make anything live; `DesignBinding`); the Design keeps
`boundFile` and `boundSync` (the SHA-256 of the text last written or read); save writes through an exclusively created temporary file
(no symbolic link followed) after `SutraRegistry.check`; a changed
file with an unsynced Design is `409 DRS-5007`; `POST /{id}/sync` (polled by the workbench every 3 s and on focus) turns an outside edit into
a `text` step. *Sharing:* `POST|DELETE /{id}/share`; the token is `base64url(owner).secret`, only its SHA-256 is stored, and
`GET /shared/{id}?token=` answers Sutra, operations and sample names (a bad or revoked token is the same `404 DRS-5006`); the console page
`/build/d/{id}?share=token` previews against the viewer's own JSON or a stored entity under the viewer's rights. Decisions: file binding on
development servers first and read-only links without sample contents, as the product owner chose.

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
| **4. Designs** (done) | `DesignStore` (file, JPA), `/builder/designs` with samples, quotas and expiry; `/build` (My designs) and `/build/new` (files, folder, schema with synthetic samples, store references, examples, existing Sutra); the extractor becomes the Data view; `/build/shape` redirects | a sample set survives a console restart; two consoles see the same Design; limits answer with DRS codes; a reference to a kind the user may no longer open previews as "no access" |
| **5. Operations and one checker** (done) | the `design.ops` package; stateless `/builder/edit` and `/builder/check`; `SampleChecker` behind Studio's test and auto-design's pruning | every operation on all 20 kinds keeps comments and order; a bad operation is a located problem; a sample set with a missing field shows the right empty and error cells |
| **6. Workbench with the visual canvas** (done) | `/build/d/{id}`: Data, Design (canvas, palette, field drops with suggestions, inspector, undo/redo, keyboard path, `grid-keys.js` shared with layout mode), YAML, Problems, Tests as you type, sample switcher, *Preview with a file…*, phone and theme toggles; the guide `docs/guides/SCREEN_DESIGNER.md` and its help entry | the all-panels showcase is built from its samples without typing YAML, by mouse and by keyboard only; a folder → auto-design → switch samples → fix → green matrix test; layout mode's tests pass with the shared module |
| **7. Studio folded in** (done) | all `/studio*` page addresses redirect (302) into the workbench, the review pages moved to `/build/reviews`, Studio's page, template and script are gone (its JSON routes under `/studio/` remain for the workbench and for scripts); Studio's keys (Ctrl+S, Ctrl+Enter), File menu, Summary, Save and Submit for review live in the workbench; Studio's test entities become stored-entity samples of a Design made from that Sutra; command palette (Ctrl+K); the Versions tab (`GET /builder/designs/{id}/versions[/n]`, diff against the base or any version with `sutra_diff`, restore as a `text` operation); Build menu: Create, Govern, Learn; Studio's test entities become tests on a Design; the guide grows four chapters | every old Studio address in the console tests lands on an equivalent screen; keyboard-only use across panes |
| **8. Ship and scale** (done) | propose with evidence, reviewers see the matrix, approval makes it live; pack fragment export and folder/zip import; the headless CLI with the `packs/<p>/tests/` convention run over every shipped pack in the build; development file binding; read-only share links; the guide's end-to-end walkthrough | folder → design → check → propose → approve → the view serves the new version; an exported fragment loads through Admin → Packs and passes `sutra test` |

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
