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
# Compute engines on or off, each with its own configuration file

Status: built (2.9.11 row 14, [the plan](IMPLEMENTATION_PLAN.md#2911-sensitivities-and-market-risk-capital-released-2026-11-02)); mock-up A, with C as
its Cluster tab. How it was built, and where it differs from the text below, is [section 17](#17-as-built). The administrator's guide is
[OPERATIONS.md section 21](../admin/OPERATIONS.md#21-compute-engines-on-or-off).

Companion to [EDITIONS.md](../admin/EDITIONS.md) (editions and lenses: what people may see), [USER_MANAGEMENT.md, Functions](../admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off)
(a command switched off for everyone), [OPERATIONS.md section 18](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one)
and [CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md) (Drishti\*net: nodes, capability digests, routing),
[PYTHON_COMPUTE.md](PYTHON_COMPUTE.md) (the compute workers and their own file `config/compute/compute.yaml`) and
[CONFIGURATION.md](../admin/CONFIGURATION.md) (every setting, generated).

**What the product owner asked for (2026-11-02, in their words):** "using admin I will be able to enable or disable frtb saccr etc etc
and when done so they should disappear from commands and everywhere"; "Basically I should be able to enable or disable compute engines
using config and or admin ui"; "Since not all instances will be running everything"; "I think different compute engines should have
their own config yaml file and main application.yaml should refer to these, else it will become very hard to manage".

## Contents

- [1. The idea in one page](#1-the-idea-in-one-page)
- [2. The engines](#2-the-engines)
- [3. One file per engine](#3-one-file-per-engine)
- [4. Moving today's settings without breaking an install](#4-moving-todays-settings-without-breaking-an-install)
- [5. What "off" means](#5-what-off-means)
- [6. Dependencies between engines](#6-dependencies-between-engines)
- [7. Turning an engine on or off while the server runs](#7-turning-an-engine-on-or-off-while-the-server-runs)
- [8. Admin → Engines](#8-admin--engines)
- [9. In a cluster: each node runs its own engines](#9-in-a-cluster-each-node-runs-its-own-engines)
- [10. The command line](#10-the-command-line)
- [11. The API](#11-the-api)
- [12. Configuration document and checks](#12-configuration-document-and-checks)
- [13. Tests that prove it](#13-tests-that-prove-it)
- [14. Build checklist](#14-build-checklist)
- [15. Mock-ups: Admin → Engines](#15-mock-ups-admin--engines)
- [16. Decisions](#16-decisions)
- [17. As built](#17-as-built)

## 1. The idea in one page

A **compute engine** is a part of the server that computes risk figures when asked: VaR, exposure, FRTB-SA, SIMM, SA-CCR, FVA, the two
cubes, the in-memory lake. Not every server runs every engine: a market-risk node has no use for SA-CCR, a counterparty node no use for
FRTB-SA, a small demo server no use for the in-memory lake. Each engine therefore has:

1. **Its own configuration file**, `config/engines/<engine>.yaml`, holding its switch (`enabled`) and every setting of that engine. The
   main `application.yaml` names these files (it imports them); it no longer holds their settings.
2. **One switch with one meaning.** Off, the engine **does not exist on that server**: it is not started (no memory, no threads, no
   loads, no connectors of its own), its API answers `404` in words, and everything that leads to it disappears from the console: command
   words, menus, Help entries, lens chips, landing tiles, search hints, command line tool commands, BI datasets and playgrounds.
3. **Dependencies stated and enforced.** An engine that needs another (FRTB-SA needs VaR's scopes; the CCR cube needs exposure) cannot be
   switched on without it, and the one it needs cannot be switched off under it. The refusal says which and why.
4. **Admin → Engines** lists every engine with its switch, its state, the memory it holds, what it needs and what turning it off hides.
   A change there writes the engine's file (audited: who, when, why) and takes effect at once where that is safe, or says "restart needed".
5. **Per node.** In a cluster each node runs only its engines; Drishti\*net routes an engine's requests to a node that runs it, exactly
   as it routes packs today, and the console shows an engine as available when any node runs it.

Three switches exist after this, each with its own job, and they combine:

| Switch | Who sets it | Scope | What it decides |
|---|---|---|---|
| **Engine** (this design) | an administrator, per server (file or Admin → Engines) | one node | whether the engine runs on this node at all |
| **Edition and lens** ([EDITIONS.md](../admin/EDITIONS.md)) | the site (`DRISHTI_EDITION`), or a person (lens) | every node, or one person | which commands and pages people are offered |
| **Command switch** ([Functions](../admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off)) | an administrator, for everyone | the whole cluster (gossip-merged) | whether one mnemonic of a pack may be used |

A word is shown when **all three** allow it: an engine running somewhere in the cluster, the edition or lens offering it, and its command
switch on.

## 2. The engines

The engine catalogue is part of the product (inside the server jar, `engines/catalogue.yaml`): each engine's id, title, file, the
settings prefixes it owns, what it needs and uses, and everything it brings to the screen. The site's files hold only the switch and the
settings.

| Id | Engine | File | Settings it owns (today's keys) | Brings to the screen | Needs | Uses when on |
|---|---|---|---|---|---|---|
| `var` | VaR, ES, stressed VaR, the what-if, why it moved, regulatory VaR (backtesting, report runs, sign-off) | `var.yaml` | `drishti.var.*`, `drishti.regulatory.*` | `VAR`, `VAR EXPLAIN`, `SMAT`, `PLAY VAR`; its column of the Engines menu and its page; the VaR landings and tiles; VAR_GUIDE's Help pages; `drishti.py var`; BI `market-risk-var-*` | none | |
| `exposure` | EE, ENE, PFE, EPE, EEPE, the exposure what-if and its playground | `exposure.yaml` | `drishti.ccr.exposure.*` | `EXP`, `PLAY EXP`; its column of the Engines menu and its page; tiles; EXPOSURE_GUIDE's Help pages; `drishti.py exposure`; BI `counterparty-exposure-*`, `live-exposure*` | none | |
| `sensitivities` | position sensitivities and the factor dictionary: load, checks, sums by bucket | `sensitivities.yaml` | `drishti.sensitivities.*` (new; today in `drishti.frtb.sensitivity-kind`, `factor-kind`, `cache-minutes`) | `SENS`, `RFAC` pages, the factor dictionary screen | none | |
| `frtb-sa` | FRTB-SA: SBM, DRC, RRAO, its playground | `frtb-sa.yaml` | `drishti.frtb.*` | `FRSA`, `PLAY FRTB`; the capital page; FRTB_SA_GUIDE's Help pages; `drishti.py frtb` | `sensitivities`, `var` (scope resolution and hierarchies) | |
| `simm` | ISDA SIMM initial margin per netting set and regime, its playground | `simm.yaml` | `drishti.simm.*` (new) | `SIMM`, `PLAY SIMM`; the netting set page's IM panel | `sensitivities` | `exposure` (IM in the exposure page's collateral) |
| `sa-ccr` | SA-CCR exposure at default, its playground | `sa-ccr.yaml` | `drishti.ccr.saccr.*` | `SACCR`, `PLAY SACCR`; the SA-CCR page; BI `counterparty-saccr-*` | none | |
| `fva` | funding cost and benefit (the xVA engine; CVA and DVA join later), its playground | `fva.yaml` | `drishti.ccr.fva.*` | `FVA`, `PLAY FVA`; FVA on the netting set and counterparty pages | `exposure` | |
| `pivot` | the pivot engine: any table or VaR as a pivot, nested rows opened lazily, totals from their own members, exports ([PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md)) | `pivot.yaml` | `drishti.pivot.*` (its old switch name `drishti.pivot.enabled` still works and still hides the Pivot tab) | `PIVOT`, Views → Pivot, Pivot on a Export data table, `drishti.py pivot` | none | `var` (the `var` source) |
| `mr-cube` | the market-risk cube on the pivot engine (the VaR source's hierarchy levels and MR measures, ladders, heat map, explain the move; MR_CUBE_GUIDE.md) | `mr-cube.yaml` | `drishti.cube.mr.*` | `MCUBE` (the pivot screen with the MR lens), `/mcube`, `/api/v1/risk/mr` | `var`, `pivot` | `sensitivities` (ladders, heat map), `frtb-sa` (the capital measure chip) |
| `ccr-cube` | the counterparty cube (limit board, allocation, group roll-up, pre-deal overlay) | `ccr-cube.yaml` | `drishti.cube.ccr.*` (new) | the CCR Cube lens, `/ccube`, its landing and chips | `exposure` | `sa-ccr` (the EAD chip, incremental EAD tile), `fva` (the FVA chip and tile) |
| `openblas` | linear algebra on the bundled OpenBLAS, **off as shipped**; off: the pure-Java kernels, the same figures, chosen (OPERATIONS.md section 2.1) | `openblas.yaml` | `drishti.native.*` | nothing hidden: off switches the many-scope sums to Java, live; on runs the self-test first; `drishti.py server blas` | none | |
| `memory-lake` | Delta tables held in memory only, with their replenish jobs | `memory-lake.yaml` | `drishti.lake.memory.*` | Admin → Lakes' Memory tab, `drishti.py lake memory`, `mem://` stores | none | |

Not engines, and not in this design: the compute workers (they already have their own file, `config/compute/compute.yaml`, and switch,
`compute.enabled`; Admin → Engines shows them as a row of their own, read from that file, so one page answers "what runs here"), BI,
Export data and the other platform features (their switches stay where they are, in `application.yaml` and the editions' `features:`).
A future engine (liquidity, climate, CVA) adds one catalogue entry and one file; nothing else in this design changes.

## 3. One file per engine

### 3.1 The folder

```
config/engines/                 DRISHTI_ENGINES_DIR (default ./config/engines), shipped with every distribution and image
├── var.yaml
├── exposure.yaml
├── sensitivities.yaml
├── frtb-sa.yaml
├── simm.yaml
├── sa-ccr.yaml
├── fva.yaml
├── mr-cube.yaml
├── ccr-cube.yaml
└── memory-lake.yaml
```

The same ten files are inside the server jar (`classpath:engines/<id>.yaml`), byte for byte (a build test checks it). A site that deletes
a file, or an install that predates the folder, gets the shipped values; a site's file overrides them key by key.

### 3.2 What a file holds

Every file has the same first block, `drishti.engines.<id>`, then the engine's own settings under the prefix it already has. The keys keep
their names, so nothing that reads them changes.

```yaml
# config/engines/frtb-sa.yaml: the FRTB-SA engine (docs/guides/FRTB_SA_GUIDE.md). Read at start, imported by application.yaml.
# Admin -> Engines writes this file (the switch and the reason); every other key is edited here by hand.
drishti:
  engines:
    frtb-sa:
      enabled: ${DRISHTI_FRTB_ENABLED:true}   # run FRTB-SA on this node (live: on and off without a restart)
      changed: { by: "", at: "", reason: "" } # the last change made from Admin -> Engines or drishti.py (written by the server)
  frtb:
    parameters-dir: ${DRISHTI_FRTB_PARAMETERS:./config/packs/banking/market-risk/capital}
    default-risk-kind: default-risk
    residual-risk-kind: residual-risk
    rrao-from-positions: true
    max-factors: 2000
    recipes-dir: ${drishti.data.dir:./data}/frtb/recipes
    playground-template: ""
```

```yaml
# config/engines/memory-lake.yaml: the in-memory lake (docs/guides/IN_MEMORY_LAKE_GUIDE.md).
drishti:
  engines:
    memory-lake:
      enabled: ${DRISHTI_MEMORY_LAKE_ENABLED:false}   # off as shipped; turning it on needs a restart (the direct-memory limit is set at start)
      changed: { by: "", at: "", reason: "" }
  lake:
    memory:
      stores: {}
      tables: []
      job-threads: 2
      compaction: { enabled: true, idle-only: false, headroom-mb: 512 }
```

Each engine's switch keeps the environment variable it has today where it has one (`DRISHTI_VAR_ENABLED`, `DRISHTI_EXPOSURE_ENABLED`,
`DRISHTI_FRTB_ENABLED`) and gains one named `DRISHTI_<ID>_ENABLED` where it has none (`DRISHTI_SIMM_ENABLED`, `DRISHTI_SENSITIVITIES_ENABLED`,
`DRISHTI_MR_CUBE_ENABLED`, `DRISHTI_MEMORY_LAKE_ENABLED`; an engine still to be built, such as SA-CCR or FVA, gets its own when it is). Shipped defaults: every engine on except `memory-lake` (off: it needs stores configured) and the engines
not yet built (shipped off until their release).

### 3.3 How application.yaml names them

`spring.config.import` gains one entry pair per engine, **before** the local file and the packs overlay, so the site's local file still
wins over an engine file:

```yaml
spring:
  config:
    import:
      # the engines' own files (docs/architecture/COMPUTE_ENGINES.md): the jar's copy, then the site's, which wins key by key
      - classpath:engines/var.yaml
      - optional:file:${DRISHTI_ENGINES_DIR:./config/engines}/var.yaml
      - classpath:engines/exposure.yaml
      - optional:file:${DRISHTI_ENGINES_DIR:./config/engines}/exposure.yaml
      # ... one pair for each engine of section 2, in the catalogue's order
      - optional:file:./application.local.yaml
      - optional:file:${DRISHTI_PACKS_OVERLAY:${drishti.data.dir:./data}/packs/added.yaml}
```

**What wins, from strongest to weakest** (Spring Boot's own order: a later import overrides an earlier one, and the environment and the
command line override every file):

1. `--key=value` on the command line, then environment variables (`DRISHTI_FRTB_ENABLED=false`, or any key as
   `DRISHTI_FRTB_MAX_FACTORS`-style relaxed binding).
2. The packs overlay (it holds no engine keys).
3. `application.local.yaml` (the site's local file, as today).
4. `config/engines/<id>.yaml`, the site's engine file.
5. `classpath:engines/<id>.yaml`, the shipped copy.
6. `application.yaml` (holds no engine keys any more).

The order is the reason the move breaks nothing (section 4): whatever a site set before, in its local file or its environment, still
wins. Admin → Engines shows, for each switch, **where its value comes from**, and when that is above the engine file (the environment or
the local file) it says so and explains that a change on the page is written to the file but will not take effect until that override
is removed.

## 4. Moving today's settings without breaking an install

| Today, in `application.yaml` | After | An existing install |
|---|---|---|
| `drishti.var.*`, `drishti.regulatory.*` | `config/engines/var.yaml`, same keys | unchanged: its overrides in `application.local.yaml` or the environment still win |
| `drishti.var.enabled` | `drishti.engines.var.enabled` (the switch) | `drishti.var.enabled` keeps working as an **alias** (below) |
| `drishti.ccr.exposure.*` | `config/engines/exposure.yaml`, same keys | unchanged |
| `drishti.ccr.exposure.enabled` | `drishti.engines.exposure.enabled` | alias |
| `drishti.frtb.*` | `config/engines/frtb-sa.yaml` (the sensitivity keys to `sensitivities.yaml`) | unchanged; `drishti.frtb.sensitivity-kind`, `factor-kind`, `cache-minutes` read as aliases of the `drishti.sensitivities.*` keys |
| `drishti.frtb.enabled` | `drishti.engines.frtb-sa.enabled` | alias |
| `drishti.lake.memory.*` | `config/engines/memory-lake.yaml`, same keys | unchanged; `drishti.engines.memory-lake.enabled` defaults to **true when a store is configured** in the old place, so an install that uses it keeps it on |
| the engine's `config/lenses/features.yaml` entries (`var: { server: [drishti.var.enabled] }`) | name the new switch (`drishti.engines.var.enabled`) | an edition that presets `var: off` still turns VaR off |
| `drishti.admission.*`, `drishti.memory.*`, `drishti.disk.*` | stay in `application.yaml`: they guard the whole server, not one engine | unchanged |

**The aliases.** For one engine the effective switch is: the new key, unless a site sets the old one (`drishti.var.enabled`,
`drishti.ccr.exposure.enabled`, `drishti.frtb.enabled`) anywhere above the shipped files, in which case the old key wins and the server
writes one line at start:

```
WARN  drishti.var.enabled is the old name of drishti.engines.var.enabled (set in application.local.yaml); it still works.
      Move it to config/engines/var.yaml, or use Admin -> Engines (DRS-1024).
```

Admin → Engines shows the same note beside the switch. The environment variables do not change, so no stack, Docker or ECS file needs
editing. The old names stay accepted until a later release announces their removal in its release notes; the check of section 12 lists
every install file that still uses one.

**What an administrator does on upgrade:** nothing is required. To tidy up, move the engine keys from `application.local.yaml` to the
engine's file (`drishti.py server engines show var --sources` lists each key with the file it comes from).

## 5. What "off" means

### 5.1 On the server

Every engine implements one lifecycle, `ComputeEngine` (`start`, `stop(drain)`, `state`, `memory`, `health`), registered with the
`EngineRegistry`. The engine's Spring beans exist either way, but they are thin: the books, caches, thread pools, schedulers, file watchers
and connectors are created in `start` and released in `stop`. So an engine that is off at start:

| | Off |
|---|---|
| Memory | nothing: no date open, no cache, no direct buffer, no parameter file read |
| Threads | none: no load pool, no scheduler, no watcher, no compute pool of its own requested from the workers |
| Loads | none: no date loaded at start or on a data version, no warm-up, no pinned date, no replenish job |
| Connectors | its own connectors are not registered (`exposure-engine`, `var-engine`, the reactive connectors in section 5.3) |
| Kinds it computes | not registered: `var-result`, `exposure-result`, `saccr-result`, `fva-result` and the like are unknown on this node |
| API | `/api/v1/<engine>/**` answers `404` with words (below) |
| Admission gate | its loads are not counted; its memory is not reserved in the safety nets (`open-heap-mb` automatic shares go to the engines that run) |
| Health | `/actuator/health` shows the engine as `off`, never `down` (off is not a fault) |

The `404` (not `403`: nothing is forbidden, the thing is not here):

```json
{"status": 404, "code": "DRS-1020", "engine": "frtb-sa",
 "message": "FRTB-SA is not running on this server, and no other server of this cluster runs it. An administrator can turn it on in Admin → Engines."}
```

When another node runs it, the request never sees this: it is routed (section 9). Today's answers of an engine switched off by its
`enabled` key (`403 FORBIDDEN`, "switched off on this server") become this `404`.

### 5.2 In the console, everywhere

The console learns the engines from `GET /api/v1/about` (a new `engines` field: each id with `here`, `cluster` and `title`), refreshed on
the live channel when it changes. One helper, `engine_available(id)` beside `edition.word_on`, answers for every place below; an engine
is available when it runs on this node or on any node of the cluster.

| Place | When the engine is available nowhere |
|---|---|
| Command line | its words are unknown, as a word an edition hides: `FRSA DESK-RATES <GO>` answers "FRTB-SA is not running on this server" (`DRS-1020`) |
| Type-ahead, search hints, FUNC, examples | its words, examples and command rows are not offered |
| Who opens it (2.9.11.a) | a running engine's column of the Engines menu and its command words follow the column's `opens:` in `sitemap.yaml` (`role:<name>`, `pack:<name>`; administrators always): a role that does not open it does not see the column, is not offered its words in the type-ahead and is told "<title> is not opened for your role; ask an administrator." when it types one (`core/menus.py` `engine_refused`, one rule for both). Help is documentation and is not gated by it. A console-side check: the server's own entitlements still decide the data |
| Menus | its column of the Engines menu is not drawn (a column names its engine with `engine: <id>` in `sitemap.yaml`, and its `opens:` the roles that see it), nor any item of another menu that names it (`requires: engine:<id>`); a menu left empty is not drawn |
| Help | its pages are not listed in the Help centre, search or contextual help (a Help entry names `engine: <id>` in `help.yaml`); a direct link opens a short page saying the engine is not running here |
| Home and landings | its tiles and quick cards are not drawn; a landing that is all one engine's (the VaR landing) is not offered on Home's switch |
| Lenses | a lens whose engine is off is not offered (MR Cube, CCR Cube); in another lens the engine's chips are not drawn (the CCR cube's EAD chip without `sa-ccr`) |
| Other pages | a panel that shows the engine's figure is not drawn (FVA on the netting set page), a link to its page is not a link, a button to its playground is not shown |
| Playgrounds | `PLAY <ENGINE>` unknown; `/play/<engine>` answers 404 |
| BI | the datasets and models built on its computed kinds are not listed and a report on them says the engine is not running (as a dataset of a kind a person may not open) |
| `drishti.py` | its commands answer with the same words and exit code 3 (`drishti.py frtb sa ...`) |
| AI and MCP tools | its tools are not offered |

The console's words, menus and Help come from catalogue entries, not from code paths per engine: `engines/catalogue.yaml` names for
each engine its `words`, `menus`, `help`, `landings`, `tiles`, `lenses`, `bi`, `cli`, `playgrounds`, `connectors`, `kinds` and
`ai-tools`. Admin → Engines reads the same entries to say what turning an engine off hides.

### 5.3 Connectors and kinds

An engine's catalogue entry splits what it touches in two:

- **Its own:** the connectors it registers and the kinds it computes. Off: not loaded, not registered.
- **What it reads:** input kinds from packs (`pnl-vector`, `scenario-pv`, `exposure-path`, `simulation-run`, `position-sensitivity`,
  `saccr-trade`). An input kind marked `engine-input: true` in the catalogue (its only use is to feed engines, and it is large) is not
  loaded on a node where **no running engine reads it**: its connector stays registered for the pack's own pages (`XPATH` browses a
  path), but no date of it is read ahead, warmed or pinned. Reference kinds (trades, counterparties, netting sets, books) are never
  affected: the packs' pages use them.

So a node with only `var` on never reads exposure paths, and a node with `exposure` and `sa-ccr` never reads P&L vectors, while both
still show the counterparty's page.

## 6. Dependencies between engines

Two kinds of link, both in the catalogue:

| Link | Meaning | Example |
|---|---|---|
| **needs** | cannot run without the other **on the same node** (it calls it in process) | `frtb-sa` needs `var` (its scopes, hierarchies and limit letters are VaR's scope resolution) and `sensitivities`; `simm` needs `sensitivities`; `fva` needs `exposure` (the EE and ENE profile); `mr-cube` needs `var` and `pivot`; `ccr-cube` needs `exposure` (the per-set path matrices) |
| **uses** | works without the other, with the parts that read it hidden | `ccr-cube` uses `sa-ccr` (EAD chip, incremental EAD tile) and `fva` (FVA chip, tile); `mr-cube` uses `sensitivities` and `frtb-sa`; `simm` uses `exposure` |

**Enforced, with words:**

| Attempt | Answer |
|---|---|
| Turn on `frtb-sa` while `var` is off | refused, `409 DRS-1021`: "FRTB-SA needs VaR on this server: its scopes (desks, books, limit letters) are VaR's. Turn on VaR first, or both together." Admin → Engines offers **Turn on both** |
| Turn off `exposure` while `fva` or `ccr-cube` is on | refused, `409 DRS-1021`: "Exposure is needed by FVA and the CCR cube on this server. Turn them off first, or all three together." The page offers **Turn off all three** |
| Turn off `sa-ccr` while `ccr-cube` is on | allowed (a *uses* link): "The CCR cube will hide its EAD chip and the incremental EAD tile." |
| Start with `frtb-sa: true` and `var: false` in the files | the server starts; `frtb-sa` is not started, its state is `blocked` with the reason, Health shows it, the start log says it in one line (`DRS-1022`); nothing else fails |
| A cycle in the catalogue | impossible by construction; the catalogue's build test refuses one |

The rule for a cluster: **needs are local** (a node running FRTB-SA runs VaR too, even if VaR's own pages are offered from another node),
because the needing engine calls the needed one in process. A later engine whose dependency is a kind read over Drishti\*net (FVA reading
exposure profiles from a peer) may declare `needs-anywhere`; none does in this design.

## 7. Turning an engine on or off while the server runs

| Change | Effect | When |
|---|---|---|
| **Off** (any engine) | the engine stops taking new requests at once (`404`, routed elsewhere in a cluster); requests in flight finish (up to `drishti.engines.drain`, default 30 s, then cancelled with a clear message); its dates close, caches and buffers are released, threads end, its connectors and computed kinds are unregistered; open pages are told on the live channel and redraw without it | live |
| **On** (every engine but `memory-lake`) | `start`: parameter files read and checked, connectors registered, threads created; the newest business date loads on first use as today (or ahead, per the engine's own warm-up settings); the console is told and shows the words | live |
| **On** `memory-lake` | written to the file; Admin → Engines says **restart needed**: the stores' caps are added to `-XX:MaxDirectMemorySize` by the launchers at start | restart |
| A setting other than `enabled` | each key says `live` or `restart` in CONFIGURATION.md as today; Admin → Engines edits only the switch and names the file for the rest | as the key says |

**Restart needed** is shown as a pill on the engine's row, kept until the server restarts, with the time of the change and who made it.
The admission gate applies to an engine coming on: if the server is short of memory, turning on an engine that loads ahead is refused
politely (`503 DRS-9502`) and the page says so.

## 8. Admin → Engines

A page of the Admin menu (`/admin/engines`, admin page id `engines`, so an edition can list or hide it as any Admin page):

- **One row per engine**: title and id; the switch; the **state** (`running`, `off`, `starting`, `stopping`, `blocked` with its reason,
  `restart needed`); the **memory** it holds now (heap of open dates and caches, direct buffers) and its share of the server; threads;
  dates open; **needs** and **used by**; **where the switch comes from** (engine file, local file, environment, alias).
- **What turning it off hides**, opened from the row (or shown before confirming): its words, menus, Help pages, tiles, lenses, BI
  datasets, CLI commands and playgrounds, from the catalogue, and the saved things that read its kinds (BI reports, monitors, alerts,
  subscriptions, workspaces), counted as Functions counts them for a command.
- **Change with a reason**: a change is staged, then **Review and apply** asks for the reason (required), shows the dependency check,
  what will be hidden or shown, and whether it is live or needs a restart. Apply writes the engine's file.
- **The cluster** (when Drishti\*net is on): each node's engines side by side, and which engines are available to people (any node runs
  them). An administrator may change another node's engine from here; the request goes to that node, which writes its own file.
- **History**: every change, newest first: when, which engine, on or off, who, why, on which node, and the outcome (live, restart
  needed, refused with the reason).

**Writing the file.** The server writes only the `enabled` and `changed` keys of the site's `config/engines/<id>.yaml` (creating it from
the shipped copy when the site has none), keeping every comment and every other key; the write is atomic (a temporary file in the same
folder, then a rename). It is refused (`409 DRS-1023`) when the file changed on disk since the page read it ("the file was edited by hand
since you opened this page; reload"), and when the folder is not writable (the page names the folder and the setting
`DRISHTI_ENGINES_DIR`). The file is watched: an edit by hand takes effect as an edit from the page does (live or restart as above), and
shows in History as "edited in the file", with no name and no reason.

**Audit.** Each change is in the audit log (`engine-enabled`, `engine-disabled`, `engine-refused`) with the actor, the reason, the node,
the file and its checksum before and after. Only an administrator may change an engine (the API refuses anyone else with `403`); a
personal API token never may.

## 9. In a cluster: each node runs its own engines

Each node reads its own `config/engines/` folder, so nodes differ by design: the VaR shard owners run `var`, the counterparty nodes run
`exposure`, `sa-ccr`, `fva` and `ccr-cube`, a small console node runs none.

- **The capability digest** ([CLUSTER_DEPLOYMENT.md section 6](CLUSTER_DEPLOYMENT.md#6-what-a-node-offers-the-capability-digest)) gains
  `engines`: each engine running here with its state and version. A change raises `digestHash`; peers pull it within a gossip round.
- **Routing** follows the capability table, as for packs: a request for `/api/v1/risk/frtb/**`, an `FRSA` command, a `PLAY FRTB` page or a BI
  query on an engine's computed kind, arriving at a node that does not run `frtb-sa`, is forwarded to a node that does (the least loaded,
  as view forwarding chooses today), signed with the cluster key. No node runs it: the `404` of section 5.1. An engine that is
  `starting` or `stopping` on a node is not routed to.
- **What people see** is the cluster's union: an engine is available when any reachable node runs it. A node going down that was the
  only one running an engine makes its words disappear within the failure detector's time, and come back when it returns.
- **Admin → Engines** on any node shows the matrix of nodes × engines and changes any node's engine (section 8);
  `drishti.py server engines list --cluster` prints the same.
- **Editions:** every member still runs the same edition ([EDITIONS.md section 8](../admin/EDITIONS.md#8-in-a-cluster)). Engines are per
  node; editions are per cluster. An edition that hides a word hides it whatever runs.
- **VaR sharding** is unchanged: a shard owner must run `var`; a node with `var` off is never a shard owner (it leaves the shard map as a
  draining owner does).

## 10. The command line

Under `drishti.py server`, beside `server commands` and `server packs` (and accepted as `drishti.py admin engines`, as the product owner
asked):

```
$ python3 tools/drishti.py server engines list
ENGINE         STATE     MEMORY    NEEDS                 FILE
var            running   3.1 GB    -                     config/engines/var.yaml
exposure       running   1.4 GB    -                     config/engines/exposure.yaml
sensitivities  running   210 MB    -                     config/engines/sensitivities.yaml
frtb-sa        off       -         var, sensitivities    config/engines/frtb-sa.yaml
sa-ccr         running   95 MB     -                     config/engines/sa-ccr.yaml
fva            running   12 MB     exposure              config/engines/fva.yaml
memory-lake    off       -         -                     config/engines/memory-lake.yaml (restart to turn on)

$ python3 tools/drishti.py server engines off frtb-sa --reason "market-risk node only"
refused: FRTB-SA is already off on this server

$ python3 tools/drishti.py server engines off exposure --reason "moved to node b"
refused (DRS-1021): Exposure is needed by FVA and the CCR cube on this server. Turn them off first, or add --with-dependents.

$ python3 tools/drishti.py server engines on frtb-sa --with-needs --reason "capital desk asked"
var already running; sensitivities already running; frtb-sa on (live). Written to config/engines/frtb-sa.yaml.
```

`list` (`--cluster`: every node, `--json`), `show <id>` (state, memory, needs, used by, what it hides, `--sources`: each key and the file
it comes from), `on <id>... [--with-needs]`, `off <id>... [--with-dependents]`, `history`. `on` and `off` need `--reason` and an
administrator, as `server commands` does. Exit codes: 0 done, 2 refused, 3 not running anywhere.

## 11. The API

| Method and path | What |
|---|---|
| `GET /api/v1/admin/engines` | every engine: catalogue entry, state, memory, threads, needs, used by, sources of the switch, restart needed |
| `GET /api/v1/admin/engines/{id}/impact` | what turning it off hides (from the catalogue) and the saved things that read its kinds |
| `POST /api/v1/admin/engines` | a batch `{"reason": "...", "node": "<id or blank>", "changes": [{"engine": "fva", "on": false}]}`; checked as a whole for dependencies; answers each change's outcome (live, restart needed, refused with the reason) |
| `GET /api/v1/admin/engines/history` | the changes, newest first |
| `GET /api/v1/about` | gains `engines`: `[{id, title, here, cluster}]`, what the console needs to show or hide |
| `GET /api/v1/net/digest` | gains `engines` (section 9) |
| `POST /api/v1/net/engines` | a change for this node from a peer's Admin → Engines, signed with the cluster key, then as `POST /api/v1/admin/engines` |

New codes: `DRS-1020` (engine not running here or anywhere, 404), `DRS-1021` (a dependency refuses the change, 409), `DRS-1022` (an
engine blocked at start by a missing dependency, logged), `DRS-1023` (the engine file changed on disk or cannot be written, 409),
`DRS-1024` (an old key name in use, a warning).

## 12. Configuration document and checks

- `tools/settings_catalogue.py` gains the area **Engines: `config/engines/*.yaml`**, one table per engine file, read with the same
  comment reader as `application.yaml` (each key's meaning is its comment; `${VAR:default}` gives the variable). CONFIGURATION.md and
  Help → Configuration list every engine key under its file; the server area no longer lists the moved keys, and the moved keys' old
  names are listed once as aliases with the new name.
- `--check` fails when a shipped engine file and its jar copy differ, when a catalogue entry has no file or a file no entry, and when a
  key appears in both `application.yaml` and an engine file.
- `drishti.py server engines show <id> --sources` and Admin → Engines name the file each value comes from (section 3.3).

## 13. Tests that prove it

| Test | Proves |
|---|---|
| `EngineRegistryTest` (server) | an engine off at start creates no thread (thread names checked), opens no date, registers no connector or computed kind, reserves no memory; on, it does |
| `EngineSwitchLiveTest` | on → off while a request runs: the request finishes, the next answers `404 DRS-1020`, the heap of its open dates is released (measured), threads end; off → on: the next request answers |
| `EngineDependencyTest` | every refusal of section 6 with its words; `--with-needs` and `--with-dependents`; a file with `frtb-sa: true, var: false` starts with `frtb-sa` blocked and everything else up |
| `EngineFilesTest` | the import order of section 3.3 (environment > local file > engine file > shipped copy), each old key as an alias with its warning, an install with today's `application.local.yaml` starting with the same effective settings as before (every key compared) |
| `EngineFileWriteTest` | the page's write keeps every comment and key, is atomic, refuses a file changed on disk, writes `changed`; a hand edit takes effect and lands in History |
| `test_engine_off_everywhere.py` (console) | with `frtb-sa` off: `FRSA` and `PLAY FRTB` unknown on the command line and in FUNC, type-ahead and search; no FRTB menu item, Help entry, tile, chip, BI dataset or playground link on any page (every route of the app crawled for the words and links); the CLI exits 3 with the words. Repeated for every engine of the catalogue, generated from it |
| `test_engine_uses.py` | `ccr-cube` on with `sa-ccr` off: the cube opens, the EAD chip and the incremental EAD tile are not drawn |
| `EngineClusterTest` (two members) | `frtb-sa` on A only: B's console shows FRSA; B's `FRSA DESK-RATES` answers A's figure to the cent (routed); A off: B answers `404` within the gossip time; the digest carries `engines` |
| `test_settings_catalogue.py` | the Engines area is generated, every engine key documented, the jar copies identical |
| Browser test (Admin → Engines) | the chosen mock-up's page: switch, reason, review, refusal words, restart pill, history |

## 14. Build checklist

| # | Part | Size |
|---|---|---|
| 1 | The engine catalogue (`engines/catalogue.yaml`): ten entries with needs, uses, words, menus, Help, tiles, lenses, BI, CLI, playgrounds, connectors, kinds; its build test (no cycle, every word known) | S |
| 2 | The ten engine files and their jar copies; the imports in `application.yaml`; the moved keys out of `application.yaml`; aliases and the start warning; `features.yaml` naming the new switches | S to M |
| 3 | `ComputeEngine` and `EngineRegistry`; VaR, exposure and FRTB-SA (built) moved behind `start` and `stop` (their books, pools, schedulers and connectors created in `start`); the built engines' `403` answers become `404 DRS-1020` | M |
| 4 | Connectors and kinds: an engine's own registered only when it runs; `engine-input` kinds not read ahead when no running engine reads them | S |
| 5 | Live on and off: drain, release, unregister; the live-channel notice; the admission check on an engine coming on | M |
| 6 | Admin → Engines (the chosen mock-up), the admin API, writing the file, History, audit | M |
| 7 | The console everywhere: `engine_available`, `requires: engine:` in menus and Help, landings, tiles, lenses, FUNC, type-ahead, search, playgrounds, BI, AI tools | M |
| 8 | Drishti\*net: `engines` in the digest, routing by engine, the cluster matrix and a peer's change | S to M |
| 9 | `drishti.py server engines` (and `admin engines`) | S |
| 10 | CONFIGURATION.md's Engines area and its checks | S |
| 11 | Tests of section 13 | M |
| 12 | Docs: OPERATIONS.md (an engines section), EDITIONS.md (the three switches), USER_MANAGEMENT.md (Functions beside engines), CLUSTER_DEPLOYMENT.md (digest and routing), each engine's guide (its file), GETTING_STARTED (the folder), a tutorial with screenshots of the page, CHANGELOG | S to M |

Overall **M**: the engines that are built (VaR, exposure, FRTB-SA, the in-memory lake) are moved behind the lifecycle; the engines still
to build (sensitivities, SIMM, SA-CCR, FVA, the cubes) are written against it from the start, which costs them nothing extra.

## 15. Mock-ups: Admin → Engines

Three options, self-contained HTML (system fonts, everything inline, nothing from a CDN), dark by default and light with `#light`:

| Option | What it is | Screenshot |
|---|---|---|
| [A: the engine list with a review panel](mockups/engines/A.html) (**recommended**) | one table of engines on this node (switch, state, memory bar, needs, used by, where the switch comes from); a side panel opened by a change: the reason, the dependency check, what will be hidden, live or restart; History below; the cluster as a second tab | [A.png](mockups/engines/A.png) |
| [B: engine cards and a dependency map](mockups/engines/B.html) | a card per engine (state, memory, a short "hides" list) arranged by family, with a small dependency map; a change opens a confirm dialog | [B.png](mockups/engines/B.png) |
| [C: the cluster matrix](mockups/engines/C.html) | nodes × engines in one grid, each cell a switch with its state and memory; an "available to people" row at the bottom; a change on any node from one place | [C.png](mockups/engines/C.png) |

**Recommended: A.** It is the shape administrators already know from Functions (a list, staged changes, a review panel with the reason,
History), so there is nothing new to learn; it shows the two facts a change needs before it is made (what depends on it and what
disappears) at the moment of the change; it reads well on one server, which is most installs, and puts the cluster one tab away rather
than making every install look like a cluster. C's matrix is the better view for a large cluster and is A's Cluster tab; B looks
friendliest but spends the screen on cards and hides the memory and source details an administrator needs.

The mock-up is picked by the product owner before the build.

## 16. Decisions

**All decided by the product owner on 2026-11-02, as recommended; mock-up A picked** ([section 15](#15-mock-ups-admin--engines)).

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | The CLI's place: `drishti.py server engines` (beside `server commands` and `server packs`) with `admin engines` as an alias, or `admin engines` only? | **`server engines`, with `admin engines` accepted**: one place for administration over REST, and the words the product owner used still work |
| 2 | Off answers `404` (not here) rather than today's `403` (switched off)? | **404**: nothing is forbidden, the engine is not on this server; the words say so |
| 3 | `memory-lake` turned on from the page: write the file and say "restart needed", or refuse from the page? | **Write and say "restart needed"**: the administrator's choice is recorded and takes effect at the next start |
| 4 | An administrator changing another node's engine from any node's Admin → Engines? | **Yes**, signed with the cluster key and audited on both nodes |
| 5 | Regulatory VaR (backtesting, reports, sign-off) as part of `var`, or an engine of its own? | **Part of `var`** for now: it reads only VaR's official records; split when a node needs one without the other |

**All decided by the product owner on 2026-11-02, as recommended; mock-up A picked** ([section 15](#15-mock-ups-admin--engines)).

## 17. As built

| Part | Where | Notes |
|---|---|---|
| The catalogue | `drishti-server/src/main/resources/engines/catalogue.yaml`; the console's copy `drishti-console/config/engines.yaml` (same bytes, `EngineCatalogueTest`) | one entry per built engine (VaR, exposure, sensitivities, FRTB-SA, SIMM, the MR cube, the in-memory lake, data loads, the pivot engine, Flight); SA-CCR, FVA and the CCR cube of this design are not listed and have no file until their engines are built (each then adds its entry, its files and a `ComputeEngine` bean); `installed: false` stays for an engine listed ahead of its build; the console's paths per engine are its `console:` list (`=/play` is that path only); the longest API prefix wins (`/api/v1/risk/sensitivities` is the sensitivities', not FRTB-SA's) |
| The files | `config/engines/*.yaml` and `drishti-server/src/main/resources/engines/*.yaml`, imported by `application.yaml` (jar copy, then the site's) | `drishti.engines.{dir, drain, watch-seconds}` stay in `application.yaml`; `drishti.frtb.sensitivity-kind`, `factor-kind`, `cache-minutes` read `drishti.sensitivities.*` through a lowest-precedence alias layer (`EngineKeyAliases`) |
| Switch and source | `EngineSwitches` | reads Spring's own sources in order; an old name set above the shipped files decides (DRS-1024 logged once); the in-memory lake on when its stores are in the old place; an edition's presets sit above the engine files (`EditionEnvironmentPostProcessor`) |
| Lifecycle | `ComputeEngine`, `BasicEngine`, `EngineRegistry` | each engine's configuration makes one `BasicEngine` bean (VaR, exposure, sensitivities, FRTB-SA, SIMM, the in-memory lake); the registry finds every `ComputeEngine` bean, so an engine still to come registers by itself. The services keep their beans; their open dates, caches and answers are created on first use and released by `stop` (the Admin, Caches purge of the engine); each service asks the registry before any work and refuses with `EngineOffException` (404 DRS-1020). The in-memory lake off at start is built with no store |
| The API gate and routing | `EngineGateFilter` (filter order 4, after the caller is known), `EngineCluster` | counts the requests in flight per engine (the drain); not running here: forwarded to the least loaded peer whose digest says it runs the engine (`RunCluster.call`, signed, on behalf of the caller; `X-Drishti-Engine-Node`), else 404 |
| The digest | the capability digest's `services` map, entry `engines` (`var:running,frtb-sa:off,...`) | no change to the digest's shape, so an older peer reads it; a change announces a new hash at once |
| About | `/public/about` (what the console polls) and `/api/v1/about` both carry `engines: [{id, title, here, cluster}]` | the console learns it with the edition (`core/engines.py`, `Editions.learn`), and again at once after a change made on Admin → Engines |
| The console | `core/engines.py`: `engine_available`, `word_hidden`, `typed_engine`, `path_engine`, `menu_hidden`, `help_hidden`, `tile_hidden`, `lens_hidden`; what runs is each app's own (`engines.State` on its editions, read per request through `AppEngines` in `core/app.py`), never shared between apps of one process | wired into `Edition.word_on`, `command_shown` and `typed_shown` (so FUNC, type-ahead, Home cards and chips follow), the route gate (`core/edition_gate.py`), the menus (`requires: engine:<id>` and the catalogue's `menus:`), Help (`help.yaml engine:` and the catalogue's `help:`), the lens links and `/go` (DRS-1020 in the server's words) |
| BI | `EditionBi.engines(...)` | a dataset or model whose name starts with an engine's `bi:` prefix, and every item on it, is not offered while the engine runs nowhere |
| Admin → Engines | `routes/admin_engines_routes.py`, `templates/admin/engines.html`, `static/js/drishti_admin-engines.js`, `static/css/drishti_admin-engines.css` | mock-up A; the Cluster tab is C's matrix; History below the table and as a tab |
| The command line | `tools/enginescli.py` | `server engines` and `admin engines`; `problem_error` maps DRS-1020 to exit 3 for every command |
| Not built here | | the admission check on an engine coming on (DRS-9502) and AI tools per engine wait for their consumers; "saved things that read its kinds" on the impact answer lists the engines that need or use it and the nodes that still run it, not yet the saved BI reports and monitors |

Tests: `EngineCatalogueTest`, `EngineFilesTest`, `EngineRegistryTest`, `EngineApiTest` (a whole server), `EngineClusterTest` (two
members), `EditionWorldServerTest` (an edition's `var: off`); `tests/test_engine_off_everywhere.py` (generated from the catalogue),
`tests/test_engines_browser.py` (the page, with the guide's screenshots), `tools/test_enginescli.py`.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
