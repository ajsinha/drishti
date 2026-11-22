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
# Drishti 2.9.11.i7b: release notes

**Drishti is one of five.** The language, the lake, the screens' libraries and the shared utilities are products of their own, in their own
repositories under github.com/drishti-platform; Drishti uses them as jars and bundles. The versions that go together are in the index
repository's COMPATIBILITY.md.

## What changes for administrators

- **Loading, reading and upkeep go through Drishti Lake**: one loader (Java and Python) with the guards `future-days`, `zone`,
  `max-drop-share` and `force-drop`, one archive rule, `lake maintain` for retention, compaction, checkpoints and vacuum (no delta-rs), the read
  API; vectors are stored as numbers in columns with a `{"$vector": NAME}` reference; the lake files' roots are under `${lake.data.dir}`.
- **Get and build the code**: the platform's source bundle, or `clone_all.py` and `build_all.py` of the index repository; the products install
  into `~/.m2` from their tags in the order tools, Rachana, Lake, Drishti.
- **Operator commands are Python only** (`tools/drishti.py`); no shell script and no `.exe` on Windows.
- **IDE launchers** follow one standard across the platform (`Drishti: build all`, `Drishti: run server (demo)`, one `run-configs/` folder); VS Code has
  a guide and a workspace file.

## What changes for developers

- Rachana (one language level, `rachana: 1`), Drishti Elements, Drishti Lake and Drishti Tools are consumed from tags; Drishti no longer carries its own
  copies of the Sutra compiler, the live-table engine, the renderer, the Delta and Iceberg protocol code, the aggregate kernel or the `.xlsx` writers.
- The developer quickstart, the deployment and QA guide, the IDE guide and the new VS Code guide are rewritten for this layout.

# Drishti 2.9.11.i7a — release notes

*Released 2026-11-17.* **Modules end to end: a module is a file.** Each module ships as a verified `.dmod` package in
`<home>/modules`, beside one server jar; installing, upgrading or removing a module is copying a file and restarting. The version
shown everywhere is `2.9.11.i7a`; the release tag is `v2.9.11.i_7a`.

## What changes for administrators

- **Build:** `python tools/drishti.py build all` builds the server jar and every module package; `build jar` alone says which modules
  are missing or stale. A server or cluster refuses to start on a package older than its module's source, naming the module and the
  command that fixes it.
- **The old deployment system is gone**: the module store, plans and four-eyes approvals, staging, hot swap, tombstones and the
  Admin deploy screen. **Admin → Modules** now shows every module and, in a cluster, a nodes × modules matrix of the content each node
  booted, with a warning when nodes disagree and the commands to install or upgrade.
- **`python tools/drishti.py module verify`** is the one verifier (in the server jar); `module pack` is the Python packer.
- **Kernel libraries always win**: a module's own copy of a library the kernel ships is ignored and named in a warning (verify, log,
  Health).
- **Cluster stack:** nodes switch off connectors whose tables their lake does not hold, and build their lakes to the business today.
- **An SDK folder** (`python tools/drishti.py build sdk`) lets a partner build, test, pack and verify a module without Drishti's source.

## What changes for readers

- **The genomics showcase works on an installed server** (the module's server code now ships), and its screens say what failed
  instead of waiting forever. Stacks can offer the **Research** lens (*Open Drishti Research*).
- **World economy reads work on an installed server.** IMF-derived figures stay internal: they are never in the shipped package.
- **FORM rules are checked by the server as people type**; the browser holds no rule code.
- Fixes: ids containing `/` in the command line tools; a first save of a BI board under load; a rare stale read right after a
  write-back correction was applied.

## Known limits

- One Spring context for kernel and modules was dropped by design: each module keeps its own child context.

## Upgrading an install of i6

1. `git pull` (or unpack the release); then `python tools/drishti.py build all` (not `build jar`).
2. Restart the servers, then the consoles. A cluster rebuilds each node's synthetic lake on its first start (about 13 minutes per lake).
3. Leftover folders of the old module store under `<data>/modules` are reported at start and can be deleted.


## The previous release: 2.9.11.i6

*Released 2026-11-17.* **The console draws what the server decides (the thin console), Drishti Elements 2.4.0 with one version for
every library, and the genomics showcase: a linked cohort board and a protein-engineering workbench over two seeded data packs.** The
version shown everywhere (About, the console footer, the image tags, the jar's name) is `2.9.11.i6`, read from `.mvn/maven.config`;
the release tag is `v2.9.11.i_6`.

### What changes for administrators

- **The console reads no pack, module, lens or distribution file.** It asks the server (`GET /api/v1/console/bootstrap`,
  `GET /api/v1/me/console`). **Upgrade the servers before the consoles**; `tools/rolling.py` does so. Console settings removed:
  `packs.enabled`, `distributions_dir`, `module_manifests_dir`, `lens.dir`, `builder.max_*`, `under_construction.file`, `ai.*`, `mcp.*`,
  `retry.*` (each now the server's). `trading.people` moves to the server as `drishti.trading.people`.
- **AI is hidden and off** in this release (`drishti.ai.enabled`, off as shipped; Admin → Health says *AI: off*).
- **The tools read the Java resolver's answer** (`resolve --json` in the server jar): every Python tool and generator needs a built jar
  (`python tools/drishti.py build jar`). Site overrides of module files (`drishti.overrides.dir`) now reach these tools too.
- **A fixed business "today"** for a server whose data ends on a known date (`drishti.business-date.today`, environment
  `DRISHTI_BUSINESS_TODAY`; blank as shipped, so the clock's today).
- **Admin → Lakes on a console away from the server's machine** says so and lists the server's read-only lakes instead of offering
  lake scripts.
- **The demo lake has 103 tables** (89 in i5); the two showcase packs are opt-in.
- **`start cluster <name>`** refuses a name that is no node or console, listing the names there are.

### What changes for readers

- **The genomics showcase** (`GWAS RESPONSE`, `PROTEIN HLXK7`, `DESIGNS HLXK7` in Drishti Genomics or Drishti All): the association plot,
  the cohort matrix, the map of samples and the variant lollipop linked by one selection; the mutational scan, the structure in 3D and
  the design campaign. Load its data with `python tools/drishti.py lake demo --only genomics-showcase,protein-engineering`. The record
  words of the two packs are `ASSOC`, `PROT`, `DESIGN`, `SGENE`, `SSAMPLE`, `SVARIANT`, `VCALL`, `CNV`, `PATHWAY`, `RESIDUE`, `DMS`,
  `CONTACT`, `DMUT`, `DROUND`.
- **Drishti Elements 2.4.0**: new chart kinds (radar, violin, ridgeline, curve, density, segmented scatter, matrix, lollipop, 3D trace),
  the dock layout, the command palette, master-detail grid rows, what-if inputs and the query builder.

### Known limits

- A FORM's rules are still evaluated twice, in the browser (`drishti_form-expr.js`, as people type) and on the server (`FormExpr`); one
  home is planned for i7.

### Upgrading an install of i5

1. `git pull` (or unpack the release), then `git clean -fdX modules/` as for i5.
2. `python tools/drishti.py build jar`, then restart the servers, then the consoles.
3. Remove the console settings listed above from your console settings file; set the server's `drishti.trading.people` if you used
   `trading.people`.

