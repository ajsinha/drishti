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
# Drishti 1.15.0 — release notes

*2026-10-04.* **A KPI tile, five developer guides with generated screenshots, and one source of truth per topic.**

- **`metric`, the 21st panel kind:** a big-number KPI tile with one formatted, toned figure, an optional change beside
  it (`delta`), a unit and a caption. The workbench offers it first for a lone measure, and auto-design drafts one. The
  figure never breaks inside: it shrinks with its tile. See [PANEL_KINDS.md#metric](docs/guides/PANEL_KINDS.md#metric).
- **Five developer guides**, each with real, worked examples and screenshots made by a script, so they stay true:
  - [PANEL_DEVELOPER_GUIDE.md](docs/guides/PANEL_DEVELOPER_GUIDE.md): add a panel kind end to end, worked through the
    real `metric` code, with an ordered checklist and the tests that catch a missed step. Plus
    [PANEL_KINDS.md](docs/guides/PANEL_KINDS.md), the catalogue of all twenty-one kinds (where F1 lands).
  - [CONNECTOR_DEVELOPER_GUIDE.md](docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md): the plugin interface method by
    method, testing with the testkit contracts, and a teaching plugin that is built and contract-tested but not shipped.
  - [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md): a help-desk pack built step by step with real outputs,
    from `pack.yaml` to the signed registry.
  - [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md): writing, testing and shipping Sutras.
  - [BUILD_WORKBENCH_TUTORIAL.md](docs/guides/BUILD_WORKBENCH_TUTORIAL.md): four complete projects in 46 steps with
    66 pictures: from JSON files, by hand, changing a live Sutra, and keyboard only.
- **The documentation was harmonised:** guides teach and link; reference documents own each setting. The retired guides
  (PANELS, PLUGIN_GUIDE, CONNECTOR_GUIDE, RACHANA_GUIDE, SUTRA_CLI and the in-app copies) are short pointers, their help
  addresses redirect, about 70 links were repointed, and about 520 repeated lines were removed from the connector
  references.
- **Fixed in the Build workbench** (found while writing the tutorial): auto-design failed when a top-level field also
  occurs nested; dropping a field on a `kv` panel left it empty; after a typed YAML edit the editor stopped following the
  canvas and could save stale text; a rebase left the "base moved" flag; approving your own design showed "base moved"
  pointing at your own new version.

**Upgrade notes:** none required. Old help addresses (`/help/panels`, `/help/plugins`, `/help/connectors`,
`/help/build-a-pack`, `/help/sutra-guide`, `/help/rachana-guide`) redirect to the new guides.

# Previous release: Drishti 1.14.1 — release notes

*2026-10-03.* **LZ4 Delta tables, clean errors for oversized requests, and masking of copied values.**

- **Delta Lake tables compressed with LZ4 or LZ4_RAW are read** by both engines (tables written by pyarrow, pandas,
  delta-rs, Spark and Hadoop). The native engine decodes them; the Hadoop engine routes LZ4 files through the same
  decoder (`lz4-via-native`, on by default). A corrupt page names its codec, table and file. See
  [DELTA_CONNECTOR §16](docs/connectors/DELTA_CONNECTOR.md#16-engines-native-and-hadoop).
- **Oversized requests get a clear `413 DRS-5005`** naming the size and the limit (before, a very large upload ended in
  a broken connection). Limits per path in `drishti.http.request-limits`; the console says "This file is too large: X MB;
  the limit is Y MB".
- **Masking of copied values (opt-in):** with `drishti.security.mask-copies: true`, a masked field's value is also hidden
  where it is copied into other text of the same document ("Captured by •••"). Best effort: exact copies only; mask
  free-text fields that carry sensitive values too (dotted `redact` paths), and keep sensitive values out of free text at
  the source. The shipped configuration masks `lifecycle.timeline.description` along with the trader.
- Passing builds no longer print an alarming LZ4 stack trace from a test that refuses an unsupported codec on purpose.

**Upgrade notes:** none required. New settings: `drishti.http.request-limits.*`, `server.tomcat.max-swallow-size`
(`DRISHTI_MAX_SWALLOW`), `drishti.security.mask-copies*`, Delta `lz4-decoder` and `lz4-via-native`.

# Older release: Drishti 1.14.0 — release notes

*2026-10-03.* **The Build workbench: from JSON files to a working, reviewed screen in minutes; two rounds of adversarial QA closed.**

- **Build workbench** (Build → New screen). One page to design, test and ship screens:
  - **Bring data:** JSON or JSONL files, a whole folder, a JSON Schema (samples are generated), entities from a store,
    an example, or an existing Sutra. Drishti infers a schema with a role for every field (id, link, measure,
    dimension, series, tree, OHLC, …) and **auto-designs** a first screen, each panel with its reason and alternatives.
  - **Visual canvas:** the real view, with **+ Add panel** on every tab, a palette of all 20 kinds, drag to move,
    edges to resize, drop a field for suggested panels or to bind it, remove with the trash button (with Undo), an
    inspector with an editor for every option, YAML side by side, Notes, and a keyboard path for everything.
  - **Tested as you type** against every sample (panel × sample grid), **preview with any JSON file**, versions and
    diffs, a command palette (Ctrl+K), undo and redo.
  - **Ship:** propose with evidence (check grid, sample names, notes, diff); approval publishes the next version.
    Export a pack fragment, import a pack folder or zip, read-only share links, and file binding to a draft folder for
    IDE editing in development.
  - **Designs are kept on the server** per user (file or database store), with quotas and expiry; examples open as
    your own copy. Designing is open to every signed-in user; saving, proposing and approving keep their rights.
  - Guide: [SCREEN_DESIGNER.md](docs/guides/SCREEN_DESIGNER.md) (with screenshots); design:
    [BUILD_WORKBENCH.md](docs/architecture/BUILD_WORKBENCH.md).
- **Headless `sutra` CLI** in the server jar: `lint | test | shape | design | preview`, JUnit XML and HTML snapshots for
  CI ([SUTRA_CLI.md](docs/guides/SUTRA_CLI.md)).
- **Panels:** `source:` on every data panel (read a linked entity), **expandable row groups** (pivot `by: [a, b, c]` with
  subtotals; tree tables with `children`), ten [examples](docs/guides/examples/README.md) covering all 20 kinds.
- **How it fits together:** [HOW_IT_FITS.md](docs/architecture/HOW_IT_FITS.md) follows a trade and a genomics variant
  through packs, connectors, Sutras and the UI.
- **Quality:** every finding of the [2026-10-01](docs/qa/2026-10-01/README.md) and
  [2026-10-03](docs/qa/2026-10-03/README.md) adversarial QA rounds is fixed or addressed (sessions that follow the
  user, masks everywhere, one live connection per browser, atomic loads, safe retention, stricter grammar, phone width
  and contrast, and more). Browser tests run in the build (Playwright).

**Upgrade notes**

- **Sign in once** after upgrading: console sessions are now kept on the server; upgrade the server and console
  together. Servers behind one load balancer share one identity database.
- **Identity schema:** new tables for console sessions and designs are created at start (SQLite and PostgreSQL).
- **Redis loads replace each day by default;** partial or intraday loads pass `--merge`.
- **Stores** for one kind are consulted in configuration order (after `routes`).
- **Old Studio links** (`/studio?example=…` and the like) show a confirmation page before a design is created.
- **`deploy/compose.yaml`** requires `DRISHTI_ADMIN_USER` and `DRISHTI_ADMIN_PASSWORD` and publishes the server on
  127.0.0.1 only.
- New settings are listed in [CONFIGURATION.md](docs/admin/CONFIGURATION.md) (`drishti.builder.*`, `drishti.panels.*`,
  `drishti.security.token-read-posts`, console `auth.*`, `live.*`, `ui.*`, `builder.*`).
