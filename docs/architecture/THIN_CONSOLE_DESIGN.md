<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The thin console: the console asks the server

**Internal. Status: S0 to S5, S6 (in part), S7 and S8a built; S8b (the lake build, lake maintenance and pack design as server jobs) to build.** Decided by the product owner on 2026-11-15: this design makes the Python console
**thin** (the "middle path"); the move of the console to Spring Boot
([KERNEL_AND_MODULES.md section 10](KERNEL_AND_MODULES.md#10-the-drishti-console-on-spring-boot-a-separate-ui-tier)) is decided after
it, for i7, with the numbers this release produces (section 6). The inventory in section 2 is of `wip/i4` at `213e980d2`.

**In plain words:**

1. The server decides; the console draws. Whatever the server also decides (which packs load, which modules are on, what a
   distribution leaves out, what a person may open, what a setting's value is), the console **asks the server** and never works out
   again from files.
2. The console reads no folder but its own: its templates, its static files, its own start-up settings. It runs on a machine that
   holds nothing of the server's `config/`, `modules/`, lakes or data folder.
3. Asking is cheap: two documents (one for the installation, one for the person), kept in memory, replaced when the server's
   version signal changes. A page costs no extra server call while nothing changed.
4. Never stale silently: an answer older than the server's signal is replaced before it is used; while the server is away the last
   answer is shown **with the notice**, and nothing is made up from local files.
5. Each step deletes the Python twin it replaces, with the fixtures that kept the twin in step. No dual machinery.

## Contents

1. [The problem](#1-the-problem)
2. [The inventory](#2-the-inventory)
3. [The target: what the console asks](#3-the-target-what-the-console-asks)
4. [What stays in the console, and the Python tools](#4-what-stays-in-the-console-and-the-python-tools)
5. [The migration, step by step](#5-the-migration-step-by-step)
6. [What this means for i7: the console on Spring Boot](#6-what-this-means-for-i7-the-console-on-spring-boot)
7. [Open questions for the owner](#7-open-questions-for-the-owner)
8. [Shared screens: one Build home, one Approvals queue, one Explain, the trader home, the controls screens and the editors](#8-shared-screens-one-build-home-one-approvals-queue-one-explain-the-trader-home-the-controls-screens-and-the-editors)
9. [Drishti Elements in Drishti](#9-drishti-elements-in-drishti)

## 1. The problem

The console (`drishti-console`, FastAPI: 48,780 lines of Python in `core/`, `routes/` and `plugins/`, 164 templates) is mostly a
client already: most of its route files pass calls to the server as the person. But about a third of `core/` **decides** things the
server also decides, from the same files, in a second implementation. The two drift, and each drift is a wrong screen:

| What drifted (found on `wip/i4`) | The twin | What a person saw |
|---|---|---|
| `packs.extra_dirs` / `DRISHTI_PACKS_EXTRA_DIRS` kept by the Java resolver, dropped by the Python one | `core/resolution.py` against `PackResolver` | packs of an extra folder loaded by the server and unknown to the console |
| the console loaded every pack of every module that is on; the server loaded only its default list | `core/packs.py` fallback and `core/edition.py` against the server's pack start | the USEQ menu drawn for a pack that is off |
| a site connector file giving only `root` over a template naming a lake | `core/resolution.py` (`effective`) against `ConnectorLayers` | the same fix written twice, in Java and in Python |
| the settings-file reader and `${NAME:default}` placeholders | `core/config.py` (a one-level pattern) against Drishti Tools `tools-settings` (`SettingsFile`, `Placeholders`) | a value the server reads one way and the console another |
| the shared fixtures `pack-resolution/cases.yaml` and `connector-layers/cases.yaml` | exist only to hold the twins together | cost with no product value; they cover the cases someone thought of |

Three more costs come with the twins, beyond the bugs:

- **The console must share a disk with the server.** It reads pack folders by the path the server's answer names (`dir`), the
  modules folder, `config/distributions`, `config/lenses` and `config/under-construction.yaml`. A console on its own machine, a
  second console, or a console in front of a cluster node that keeps its packs elsewhere, reads other files or none.
- **Tools import the console.** 43 Python files under `tools/` and `modules/` import `core.resolution` from `drishti-console/`, and `tools/packmake.py` and
  `tools/schemapack.py` import `core/schemakit`. The console is a library for the command line; neither can change alone.
- **Every server rule costs twice** and is tested twice: 264 of the console's 476 test files import `core`.

## 2. The inventory

**Method.** Every Python file under `drishti-console/core`, `routes` and `plugins` was classified from its module documentation and a
search for file reads (`yaml.safe_load`, `read_text`, `glob`, settings that name a folder), for imports of `core.resolution`, for the
words *twin*, *mirror*, *byte for byte* and *same rule as the server*, and for role checks. The route files were not read line by
line: step S0 of section 5 adds the mechanical proof (the detached-console test), which finds any reader this table misses.

**Columns.** *Risk* is the risk of a wrong screen: **H** = decides from files with rules of its own (has drifted or will), **M** = a
copy of a rule, a list or a file that must be kept in step by hand or by a test, **L** = presentation that leans on a server
format. *Server API today*: **yes** = the console could ask now; **part** = the server says some of it; **no** = new.

### 2.1 Configuration resolution (packs, modules, connectors, bindings)

| Console file (lines) | What it works out | Java counterpart | Used by | Risk | Server API today |
|---|---|---|---|---|---|
| `core/resolution.py` (1,276) | the whole waterfall: site, modules on, kernel; pack folders, states and `replaces`; module folders and installed versions; which modules are on (distribution and switch files, "licence not evaluated"); effective connectors (template under site file), bindings (read, live, write), inbox rules, problems, `why` | `com.ash.drishti.api.resolution` (`ConfigResolution`, `PackResolver`, `ResolutionSettings`, `ModuleTemplates`), `ConnectorLayers`, `AccessRegistry` | every row below; the 43 tool and module files; `tools/test_config_readers_guard.py` | **H** | **no** (the server holds the snapshot with a version and a digest and serves none of it) |
| `core/packs.py` (187) | the person's packs from the server (`GET /api/v1/packs`), then **each pack's console files from its folder**: examples, workspace starters, Help guides, contextual help, monitors, alerts, mnemonics, form counts. With no server: the resolver's loaded packs | `PackRegistry`, the pack model | top bar, Home, type-ahead, Help, FORM, workspaces, monitors | **H** (the fallback and the folder read) | **part**: the list, kinds and Python snippets are served; the console contributions are not |
| `core/edition.py` `Editions.default_packs` | "the default packs: every pack the resolver loads here" | the server's pack start | settings layer, Home | **H** (the USEQ evidence) | **part** (`GET /api/v1/packs`) |
| `core/menus.py` `_pack_command_on` | whether a command's pack loads, from the resolver's pack list | `PackRegistry` | every menu item with a pack command | **H** | **part** |
| `core/facts.py` (536) lines 262–271 | every pack's `config/facts.yaml`, found through the resolver | none (console only) | Home's fact of the day | **M** | **no** |
| `core/readmes.py` (75) | a pack's README folder through the resolver (the text itself is the server's) | `Readme` | Admin → Packs, Admin → Connectors | **M** | **yes** (the pack's `readme` answer) |
| `core/datamap.py` (261) lines 101–117 | a pack README's front matter, read from the pack folder | `DataMapService` | DATAMAP | **M** | **part** (`GET /api/v1/datamap`) |
| `routes/pack_routes.py` lines 378–379 | the modules folder a designed pack is written into | `ResolutionSettings` | Build → New pack | **M** | **no** |
| `core/house.py` (112) | every module and its title, from the manifests folder when the server has not said | `ModuleManifests` | Home's house | **M** | **yes** (`GET /public/modules/manifests`) |

### 2.2 What exists and what is shown: distribution, lens, module, engine, menu

| Console file (lines) | What it works out | Java counterpart | Used by | Risk | Server API today |
|---|---|---|---|---|---|
| `core/distribution.py` (398) | reads `config/distributions/drishti.<name>.yaml` and `kernel.yaml`, the module manifests and every pack's commands; compiles the visibility filter, the brand layer and the feature presets | `packs/distribution/Distributions`, `ModuleManifests` | everything visible; Admin → Distributions (`routes/admin_routes.py` 966–984 lists the files itself) | **H** ("the kernel reads the same files the same way" is a promise, not a mechanism) | **part**: the name (`edition` of `GET /public/about`) and the module list; the compiled result is not served |
| `core/edition.py` (557) | `Edition`: every "is this shown?" answer (words, kinds, menus, Admin pages, Help, cards, features); `Editions`: reads lens files `config/lenses/<module>/<lens>.yaml`, maps features to console settings from `kernel.yaml` | `packs/edition/Edition`, `Editions`, `server/edition/EditionCommands`, `registry/Lens` | the gate of every page (`core/edition_gate.py`), menus, Help, search, command line | **H** | **part**: lens names in about; chips and cards in the person's answer; the lens files and the filter are read here |
| `core/modules.py` (477) | per person: asks the server (`GET /api/v1/modules`), good. Per word and path: **which module owns a word, a page or a static file**, from the console's own `plugins/<module>/plugin.yaml` | `AccessRegistry`, the module manifest | command line refusals, page gate, static gate, Elements loader | **M** (ownership is declared twice: manifest and `plugin.yaml`) | **part** |
| `core/engines.py` (207) + `config/engines.yaml` + `plugins/banking/engines.yaml` | what runs: asked (`engines` of about), good. What each engine brings (words, pages, menus, Help, tiles, `opens:` rules): **a copy of the server's catalogue "byte for byte"** | `server/engines/EngineCatalogue` (`engines/catalogue.yaml`), the module's `META-INF/drishti/engines.yaml`, `EngineCluster` | menus, command line, Help, Home tiles, page gate | **M** (two files held equal by a test) | **part** (availability yes; the catalogue only to administrators) |
| `core/menus.py` (523) | the sitemap's `requires:` words; `_person_opens`: **evaluates an engine's `opens:` rules** (`role:<name>`, `pack:<name>`, administrator) for the person; merges the modules' menus from their manifests through the resolver; `module_active` (in the distribution, installed, loaded, on for the person) | the function switches (`server/switches/FunctionsController`), `AccessRegistry`, `EngineCatalogue` | top bar, Admin tabs, BI rail, footer, type-ahead, command line | **H** (an access rule evaluated outside the server) | **part** (`GET /api/v1/functions`, `GET /api/v1/modules`) |
| `core/under_construction.py` (76) | reads `config/under-construction.yaml`, "the file the server reads" | `rachana/UnderConstruction` | menus, Help | **M** | **no** |
| `core/module_parts.py` (423) | hosted console parts: from the server's registry and bundles, good; **also** the modules "the product ships beside the console" from the manifests folder | `ModuleManifests`, the module deploy service | module pages, menus, Help | **M** (the second source) | **yes** (`GET /public/modules/console` and its bundle) |
| role checks in 33 files (`"admin" in roles` and the like; `routes/admin_routes.py` alone 34 times) | whether to draw an Admin page or a control | the server's `RoleController`, entitlements | Admin, AI, Help, Build, lakes, connectors | **M** (the server still enforces; the screen can disagree with it) | **part** (the person's roles are served; which page a role opens is said here) |

### 2.3 Settings and their documents

| Console file (lines) | What it works out | Java counterpart | Used by | Risk | Server API today |
|---|---|---|---|---|---|
| `core/config.py` (148) | layers: `application.yaml`, distribution presets, edition presets, the site's file, environment, command line, pins; `${NAME:default}` by a one-level pattern | Drishti Tools `tools-settings` (`SettingsFile`, `Placeholders`), Spring's order | every console setting | **M** for the placeholder grammar; **H** for the preset layering (it needs the compiled distribution) | **no** |
| `core/settings_page.py` (214) | where each setting is set, "the same rule as the server's `EffectiveSettingsController`"; reads `docs/admin/CONFIGURATION.md` and the bundled YAML | `EffectiveSettingsController` | Help → Configuration | **M** | **yes** for the server's half |
| `core/config_editor.py` (136) | tiers and current values for the editor, from the document and files | the settings proposals API | Admin → Configuration | **M** | **part** |
| `core/builder.py` `Limits`, `core/controls.py`, `core/forms.py` | "the same three limits the server enforces"; the limits of `ctl.` parameters; form criteria limits | the builder, control and form settings | Build, every Sutra with controls, FORM | **M** (a number kept twice) | **part** (the Studio and Calc settings answers serve some) |

### 2.4 Server work that runs in the console's process

| Console file (lines) | What it does | Counterpart | Used by | Risk | Server API today |
|---|---|---|---|---|---|
| `core/lakes.py` (459), `routes/lake_routes.py` (247) | builds a Delta lake **on the console's machine** with `tools/lake/build.py` put on `sys.path` | the lake loader (Drishti Lake's `lake-load`) | Admin → Data loads → Lakes | **M** (not a twin, the same engine imported; but it writes the server's data from the console's host and needs its disk) | **no** |
| `core/lake_maint.py` (394), `routes/lake_maint_routes.py` (137) | retention, compaction, checkpoint and vacuum with `tools/lake/maintain.py`, schedules kept by the console | none on the server | Admin → Lakes → Maintain | **M** | **no** |
| `core/packdesigner.py` (307), `core/schemakit/` (3,217) | pack generation as a subprocess of the tools, in workspaces under the console's data folder; `schemakit` is also imported by the tools | the Java auto-designer (drafting only) | Build → New pack | **M** | **part** |
| `core/ai/` (4,678) | a second AI layer: providers, registry, policy, masking, limits, an MCP layer, TLS and proxy rules, each "mirroring the server's" | `kernel/drishti-ai`, Drishti Tools `tools-net` (`TlsHttp`), the connector proxy rules | the assistant, Admin → AI | **M** (policy, masking and audit exist twice; the recommended provider is already `server`) | **yes** (`POST /api/v1/ai/chat`, the MCP call) |
| `core/retry.py` (243) | "the console's twin of the server's `RetryPolicy`" | `common/RetryPolicy` | the AI and MCP calls only | **M** | goes with `core/ai` |
| `core/hostrule.py` (132) | "the rule of the server's HostAddress and `tools/hostaddr.py`": three copies | the server's host-address rule | discovered servers (`core/cluster.py`) | **M** | **yes** (the server already refuses such an address) |

### 2.5 Figures worked out in the console

The standing rule is that every risk number is computed in Java on the server. These read the server's answers as the person and
then compute:

| Console file (lines) | What it computes | Where it belongs | Risk | Server API today |
|---|---|---|---|---|
| `plugins/banking/trading_landing.py` (907) | who "my" is; the Trader desk's and Desk head's figures | the trading module's API | **M** | **no** (the inputs are served, the figures are not) Deleted in step S8a. |
| `plugins/banking/market_landing.py` (499) | the Market desk's tiles, curves and biggest moves; the Quality landing's checks | the market-data module's API | **M** | **no** Deleted in step S8a. |
| `core/facts.py` (536), `core/facts_expr.py` (409) | Home's fact of the day: an expression language over the server's answers | a kernel service, or stays (section 7, Q6) | **L** | **no** |
| `core/half_up.py` (38) | half-up rounding "as the server's `FormatSpec` rounds" | stays, for drawing only (bar widths, shares); a number shown as a number is the server's formatted text | **L** | not needed |

### 2.6 Already thin: the patterns to copy

`core/asof.py` (the business date is only carried; the server resolves it), `core/settings.py` and `core/bi.py` (the person's
answer, kept per person for a short while), `core/module_parts.py`'s registry half (a snapshot swapped whole, nothing of any person
in it), `core/backend_watch.py` (one small public answer polled every `backend.check_seconds`; the edition, the engines and the
modules already follow it), `core/user_cache.py` and `core/user_stamps.py` (per person, per server, dropped on every worker at a
change), `core/busy.py` (the server's busy answer shown calmly). The target in section 3 is these patterns applied to everything
in 2.1 to 2.5.

### 2.7 The count

| Class | Python lines that leave the console (about) | Share of `core` + `routes` + `plugins` |
|---|---|---|
| 2.1 resolution and pack files | 1,900 | 4% |
| 2.2 visibility | 1,700 of 2,900 (the rest becomes lookups) | 3% |
| 2.3 settings | 300 | 1% |
| 2.4 server work in the console | 9,800 (of it `core/ai` 4,678; `schemakit` 3,217 moves to the tools, it is not deleted) | 20% |
| 2.5 figures | 1,400 (to Java in the modules) | 3% |
| **Total** | **about 15,100** | **31%** |

## 3. The target: what the console asks

```
  browser ──► console (draws)                                   server (decides)
               │  every 5 s (backend.check_seconds), or pushed
               ├─ GET /public/about ─────────────────────────►  stamps: {installation, resolution, modules, engines, settings}
               │  only when a stamp changed (If-None-Match)
               ├─ GET /api/v1/console/bootstrap ─────────────►  the installation's answer: one immutable document, built once per change
               │  per person, on first use and when their stamp changed
               ├─ GET /api/v1/me/console ────────────────────►  the person's answer: packs, grants, lens, menus' facts
               │  on demand, cached by ETag
               ├─ GET /api/v1/packs/{pack}/console[/files/…] ►  a pack's console contributions and its Help files
               └─ GET /api/v1/admin/resolution ──────────────►  administrators and tools: origins, folders, problems, why
```

Five rules hold for every answer below:

1. **One builder on the server.** Each answer is made from the server's own objects (`ConfigResolution`, the access registry, the
   compiled distribution, the engine catalogue, the pack registry) by one class, and swapped whole when one of them changes. The
   console never sees a file path it must open.
2. **Lookups, not rules.** The server sends **decisions** (sets and maps: this word is off with these words to say, this person
   opens this engine). The console's code is `x in set`. A rule with an `if` about roles, packs, switches or licences is the
   server's.
3. **A stamp for every answer.** `GET /public/about` carries the stamps. An answer is used only while its stamp equals the last
   one seen; a changed stamp fetches the answer again before the next page is drawn with it.
4. **Per person means per person.** A person's answer is keyed by server, person and session, held in `core/user_cache.py`, never
   in a module global or a shared map without that key, and sent `Cache-Control: private, no-store`.
5. **Away is said, never guessed.** While the server does not answer, the console keeps the last answers and shows the existing
   server-away notice; it reads no local file in their place. A console that has never had an answer shows the waiting page:
   nobody can sign in without the server anyway.

### 3.1 The stamps: `GET /public/about` (existing, extended)

| | |
|---|---|
| **Added** | `stamps`: `{installation, resolution, modules, engines, settings}`, each a short digest. `installation` is the digest of the bootstrap document (3.2), so one comparison decides whether to fetch. `resolution` is `ConfigResolution`'s `version` and `digest`; `modules` is today's `manifests` fingerprint (kept under its old name too for one release of servers). |
| **Auth** | none (as today). It carries no person's data, no path and no secret. |
| **Caching** | not cached by the console: it **is** the signal. Polled every `backend.check_seconds` (5 s) per server by `core/backend_watch.py`, as today. |
| **Push** | the server's event stream to the console's service session sends `stamps` the moment one changes, so a switch flipped in Admin shows on the next page, not up to 5 s later. The poll stays as the floor: a lost event costs at most one interval. |
| **Cluster** | any node answers. `installation` and `resolution` are cluster-wide values (they follow the cluster's configuration version); a node that is behind answers an older version. The console keeps the highest version it has seen per cluster and, on a lower one, asks another node of `backend.urls` before it believes it (never backwards). |
| **Busy** | outside admission control: it is a few hundred bytes from memory. |
| **Budget** | server p95 under 5 ms; the console spends nothing on the request path (a background task). |

### 3.2 The installation's answer: `GET /api/v1/console/bootstrap` (new)

What every person of this installation shares. One document, so that nothing in it can disagree with another part of it.

```yaml
version: 412                      # the cluster's configuration version it was built from
digest: "b7c1…"                   # = stamps.installation; also the ETag
distribution:
  id: drishti.finance
  brand: {product, tagline, notice, theme}
  console-settings: {features: {...}, ui: {...}}     # the feature presets already mapped to console keys (kernel.yaml features:)
  filter:                                            # the compiled visibility filter: sets, no rules
    words-off: [GENE, ...]   kinds-off: [...]   menus: {top: [...]}   admin-pages-off: [...]   help-off: [...]   cards-off: [...]
  lenses: {default: risk, offered: [risk, market]}
lenses:                                              # each lens file as the server read it: arrangement only
  risk: {title, description, home, menus: {...}, chips: [...], cards: [...], needs: [banking]}
modules:                                             # every module the registry knows, in requirement order
  - {id: banking, title, version, domain, state: loaded | off | removed, reason, requires: [...],
     words: [VAR, "PLAY VAR", ...], paths: ["/var", ...], statics: ["js/drishti_var-*"], menus: [...],
     console-part: {version: "2.9.11", digest: "…"}}  # the bundle to host (GET /public/modules/console/{id}/{version})
engines:                                             # the catalogue and what runs, together
  - {id: var, title, module: banking, here: true, cluster: true, words: [...], paths: [...], api: [...], menus: [...], help: [...], tiles: [...],
     refusal: "VaR runs on no server of this cluster (DRS-1020)"}
commands:                                            # every command word of the installation and what it needs
  USEQ: {pack: us-equities, module: market-data, loaded: false}
  TRD:  {pack: trading, module: trading, loaded: true}
under-construction: {hidden: [...], chip: [...]}
limits: {builder: {max-samples, max-file-mb, ...}, controls: {...}, form: {...}}   # the numbers the console pre-checks with
```

| | |
|---|---|
| **Auth** | the console's **service token** (the one it already holds to verify sign-ins), role `console`. Not public: it lists what the installation has. It holds no folder, no connector setting, no secret and nothing of any person. |
| **Caching** | `ETag` = `digest`. The console sends `If-None-Match`; unchanged is `304` with no body. One copy per console app and server, swapped whole (the `Snapshot` pattern of `core/module_parts.py`); workers of one console each hold their copy and each follow the stamps. |
| **Isolation** | nothing per person may be in it; the builder takes no principal. A test fails the build of the answer if a person's name, role or token is reachable from it. |
| **Cluster** | any node; `X-Drishti-Config-Version` names the version. Per-node facts appear only as cluster totals (`here` is the answering node's, `cluster` is any node's, as `engines` of about today). |
| **Busy and failure** | outside admission control. A timeout, a `5xx` or a body that does not parse keeps the last copy and raises the notice; the next stamp poll tries again. A `401` (service token refused) is a Health problem in words, not a silent empty answer. |
| **Budget** | the server builds it once per change (off the request path) and serves bytes: `200` p95 under 10 ms, `304` under 3 ms. Size: about 100 KB of JSON, under 20 KB compressed, for the full product. The console's cost per page: zero requests; a dictionary lookup. |
| **Replaces** | `core/distribution.py`, the file half of `core/edition.py`, `core/under_construction.py`, the two `engines.yaml` copies, the words, paths and statics of each `plugin.yaml`, the shipped-beside half of `core/module_parts.py`, `core/house.py`'s folder read, `menus._pack_command_on`, the limits in `core/builder.py`, `core/controls.py` and `core/forms.py` |

### 3.3 The person's answer: `GET /api/v1/me/console` (new; gathers three existing calls)

What this person may see and use. It gathers `GET /api/v1/packs`, `GET /api/v1/modules` and `GET /api/v1/functions`, which the
console asks one by one today, and adds the decisions the console still works out.

```yaml
stamp: "p-91c…"                      # changes when the person's roles, packs, lens, switches or the installation change
person: {user, display, roles: [...], admin: false}
lens: {current: risk, open: [risk, market]}
packs:                               # assigned to the person; active says which are on now
  - {name: trading, title, version, active: true, kinds: [...], mnemonics: {TRD: trade}, forms: {trade: 14},
     examples: [...], monitors: {...}, alerts: [...], python: {enabled, snippets}, console: {digest: "…"}}
modules-off: {genomics: "not licensed for you"}
functions:                           # by word: absent = may use
  VAR:  {state: refused, say: "VaR opens to the roles risk and trader."}
  EXP:  {state: needs-data, say: "EXP needs data: exposure-path is not bound.", title: "Exposure"}
engines-opened: [var, frtb-sa]       # the catalogue's opens: rules, evaluated for this person by the server
admin-pages: [users, packs, connectors, ...]   # the Admin pages this person's roles open
grants: [layout, design, export, ...]          # what the pages ask as "may this person …"
```

| | |
|---|---|
| **Auth** | the person's token, as every call the console makes for a person. The server derives everything from the principal; no parameter names a person. |
| **Lens** | `X-Drishti-Lens` as today; the answer names the lens it was made for. |
| **Caching** | per server, person and session in `core/user_cache.py` (the key of `scoped(user)` today), for `ui.user_cache_seconds` (10 s), revalidated with `If-None-Match` (the `stamp`); dropped at once on every worker through `core/user_stamps.py` when the person changes a pack, a lens or a setting through this console, and when `stamps.installation` changes. `Cache-Control: private, no-store`; `Vary: Authorization, X-Drishti-Lens`. |
| **Isolation** | the rule of the session-isolation review holds unchanged: nothing of this answer is put in app state, a module global or a cache without the person's key. The two-people-at-once test (section 5.2) covers it. |
| **Cluster** | any node; roles and switches are cluster state. A person's sticky node (the `sticky` of `core/backend.py`) is preferred, so a change they just made is read where it was written. |
| **Busy and failure** | admitted in the light class (it reads memory and the identity store, never a lake). On a `503` with a busy code the console keeps the person's last answer for at most `ui.user_cache_seconds` × 6 and shows the notice; with no earlier answer the page is the busy page of `core/busy.py`. It **never** falls back to "everything the installation has". |
| **Budget** | server p95 under 15 ms, `304` under 5 ms. The console: one request per person per 10 s at most while they are active (today: up to three), none inside the window. First page after sign-in: this call runs beside the page's own call, not before it. |
| **Replaces** | `core/packs.py`'s folder reads and fallback, `menus._person_opens`, `module_active`'s person half, the role literals of the 33 files (they ask `grants` and `admin-pages`), `core/modules.py` `Me` and `core/bi.py`'s first call fold into it |

### 3.4 A pack's console contributions: `GET /api/v1/packs/{pack}/console` and `/console/files/{path}` (new)

The larger things a pack gives the console, which do not belong in every person's answer: workspace starters
(`console.workspaces`), the Help guide list and contextual help (`console.help`), the facts file, the README's front matter for
the data map. `/console/files/{path}` serves a Help guide's Markdown or picture, and only a file the pack's `console.help` lists
(an allow-list on the server; no path arithmetic in the console).

| | |
|---|---|
| **Auth** | the person's token; a pack not assigned to the person is `404`, as the pack's other answers. |
| **Caching** | `ETag` = the pack's digest (the `console.digest` of 3.3 says when to ask again); kept per pack, shared by people (it is the pack's, not the person's), fetched on first use. Help files are cached on the console's disk under its own temp folder by digest, as module bundles are. |
| **Cluster** | any node that loads the pack; the cluster's routing sends the call on when the answering node does not (packs are placed per node). |
| **Budget** | `200` p95 under 20 ms; a Help page costs no call once its file is cached. |
| **Replaces** | `Packs._load` and `_read` (the folder by `dir`), `core/readmes.py`'s resolver use, the pack half of `core/facts.py` and `core/datamap.py` |

### 3.5 The resolution for administrators and tools: `GET /api/v1/admin/resolution` (new) and `resolve` on the command line

`ConfigResolution` as JSON: packs (name, version, origin, module, folder, state, `replaces`, reason), modules, effective connectors
(each key's layer; secrets as their `${…}` references, never values), bindings, inbox rules, problems, and `?why=pack:trading`.
Admin → Packs, Connectors and Data bindings read their "origin" columns from it.

| | |
|---|---|
| **Auth** | administrators (the role that opens Admin → Packs). Folders are administrator knowledge. |
| **Cluster** | folders are per node: `?node=<id>` is forwarded through the cluster like the other Admin → Net calls; without it, the answering node. |
| **Caching** | `ETag` = the resolution digest. |
| **Offline twin** | the **same Java code** from the command line, with no server: the kernel jar's `resolve --json` (through `tools/javacmd.py`), which prints the same document for a checkout or a site folder (every module on, the *all* distribution, for generators; or `--distribution`). Section 4.2. |
| **Replaces** | `core/resolution.py` for the tools; the Admin pages' own listing |

### 3.6 Settings

- **The console's own start-up settings stay a file** (4.1): the listen address, `backend.urls`, the session and service secrets,
  the temp folder, `base_path`. They are needed before any server can be asked.
- **Everything the distribution presets** (`features.*`, the brand, the theme) arrives composed in the bootstrap's
  `console-settings`; `core/config.py` keeps its `layer` and `pin` (pure merging) and loses the knowledge of what goes in them.
- **Limits the server enforces** arrive in `limits`; the console's copies of the numbers and their keys in
  `drishti-console/config/application.yaml` are deleted, so a limit has one home in [CONFIGURATION.md](../admin/CONFIGURATION.md).
- **Help → Configuration and Admin → Configuration** ask the server's effective-settings answer for the server's half (value,
  where it is set) and add the console's own keys from its own file. The "where is it set" rule is no longer written twice.
- **Placeholders**: the start-up file keeps `${NAME}` and `${NAME:default}` only, one level, documented as exactly that. Nothing
  the server reads is read by the console, so the two grammars no longer need to agree (open question Q5 offers the stricter
  choice).

### 3.7 Work the server does for the console (new job APIs)

| Today in the console's process | The server's API | Notes |
|---|---|---|
| lake build from an uploaded file (`core/lakes.py`) | the upload streamed to the server (`admin_stream_upload` exists), then a load job with progress events | the server's loader reads the archive formats already; the job runs where the lake is |
| lake maintenance and its schedules (`core/lake_maint.py`) | a maintenance job API and schedules kept by the server | the engine is `tools/lake/maintain.py` today: run as a kernel Python worker first, moved into the Java lake modules when storage parity work reaches it |
| pack generation (`core/packdesigner.py`, `schemakit`) | a design job on the server, in a workspace under the server's data folder | `schemakit` moves to `tools/` (it is the engine of `drishti.py pack new`), the job is a kernel Python worker running it |
| the AI layer (`core/ai/`) | `POST /api/v1/ai/chat` and the MCP call (existing) | the console keeps only the `server` provider's 54 lines; keys, policy, masking, limits and audit have one home |
| landing figures (`trading_landing.py`, `market_landing.py`) | one landing answer per landing from the module's API | the server-computes-risk rule |

### 3.8 Performance: the console stays fast

| Page event | Server calls today | After |
|---|---|---|
| any page, nothing changed, person active in the last 10 s | 0 for packs, modules, functions (cached); the page's own calls | the same; less CPU (no YAML parsed, no folder `stat` per request: `Packs._signature` stats every pack's files on each cold read) |
| a person's cold page (first in 10 s) | up to 3 (`packs`, `modules`, `functions`), then per-pack YAML parsing on the event loop | 1 (`me/console`), usually a `304`; no parsing |
| a switch flipped in Admin | up to 5 s (poll), then file reads of the distribution, lenses, manifests | pushed at once, else up to 5 s; one bootstrap fetch per worker |
| console start | reads the distribution, lens, engine and manifest files; then learns the server's state over the first polls | one bootstrap fetch; pages are served when it has arrived |

Gates, measured on the benchmark machine and recorded in [BENCHMARK.md](../admin/BENCHMARK.md): no page of the 30 most used is
slower at p50 or p95 than on `wip/i4`; Home and the top bar at 50 people at once are not slower; the console's memory per worker
does not grow by more than the bootstrap's size. The same harness gives i7 its baseline (section 6).

## 4. What stays in the console, and the Python tools

### 4.1 The console keeps

| Stays | Why it is the console's |
|---|---|
| templates, macros, the composed pages of module parts, the Sutra and view-model rendering (`web/templates`, `core/screen_grid.py`, `core/phone.py`, `core/compare_side.py`, `core/history_rows.py`) | pure presentation of the server's answers |
| static files and Drishti Elements (`web/static`, `core/element_sheet.py`, `core/render_dist.py`, `core/staticzip.py`), the vendored libraries | the controlled UI statics are served by the console |
| `base_path`, CSRF, security headers, the CSP, the 404 page (`core/base_path.py`, `core/csrf.py`, `core/notfound.py`) | the browser-facing surface |
| its own sessions and sign-in until single sign-on (`core/auth.py`, `core/oidc.py`, `routes/auth_routes.py`); embed-token checks against the server's published key (`core/embed_auth.py`, `core/embed_limits.py`) | the console's own job; sessions are already opened and ended on the server. Enterprise SSO is i7's or j's (section 6) |
| the server list, discovery and the watch (`core/servers.py`, `core/cluster.py`, `core/backend_watch.py`), the client (`core/backend.py`), the live channel and its relay | how it reaches servers |
| the top bar's layout: `config/sitemap.yaml` (the kernel's own menus, their order, icons, labels), `config/help.yaml`, `config/workspaces.yaml`, `config/pivot-presets.yaml`, `config/prep-snippets.yaml` | arrangement and wording. Each item's `requires:` becomes a lookup in the two answers; the file holds no rule |
| the designers' document edits (`core/designer*.py`, `core/describe.py`, `core/model_layer.py`, `core/yaml_roundtrip.py`, `core/sutra_diff.py`, `core/transform_editor.py`, `core/notebook.py`) | editing a document a person owns; the server validates, compiles and stores. They lean on the server's formats (risk **L**): the server's check answer is the judge, and no designer accepts what the server refuses |
| exports' formatting of what is on screen (`core/export.py`), `core/half_up.py` for drawing | presentation |
| Help's own content: `web/guides`, the repository's `docs/` shipped with the console, the glossary file | documents, not decisions (a module's Help comes in its console part) |
| the console's start-up settings file (3.6) | needed before a server answers |

### 4.2 The Python tools

The tools are not the console and most are not clients of a running server: the generators, the lake builder and the drill work
on a checkout with no server up. Three kinds:

| Tool kind | Examples | Target |
|---|---|---|
| **Clients of a server** | `tools/drishti.py`'s server commands, `modulecli.py`, `serverpacks.py`, `serverconnectors.py`, `servernet.py`, `enginescli.py` | unchanged: they call the API. Where one also reads folders to answer the same question, the folder half is deleted and it asks `GET /api/v1/admin/resolution` |
| **Offline, need "where is everything"** | `tools/contracts.py`, `tools/packgen/**`, `tools/lake/build.py`, `demo.py`, `feedroute.py`, `tools/build_demo_lakes.py`, `tools/packlake.py`, `tools/loaders/common/**`, `command_catalog.py`, `settings_catalogue.py`, `tools/drill.sh`'s pack list | ask the **Java resolver through the jar** (`resolve --json`, 3.5) and read the JSON through one small module, `tools/resolved.py`: data classes over the document, **no rule** (no layering, no `replaces`, no on/off). The document is cached in the scratch folder under a key of the inputs' modification times, so a generator pays the JVM's start once per change, not per run |
| **Engines the server should run** | `tools/lake/maintain.py`, the pack maker behind Build → New pack | stay tools for the command line; the console reaches them only through the server's job APIs (3.7) |

Why not a shared Python library instead: a library both the console and the tools import **is** the twin, in a new folder. The
choice that removes the second implementation is to let Python read the first implementation's answer. Its cost is that an
offline tool needs the built kernel jar; the drill and the demo-lake build have it already, and `tools/javacmd.py` finds it.
A generator run on a checkout with no build says so in one line with the command that builds it (open question Q2).

`core/schemakit` moves to `tools/schemakit` (its only other user is the pack designer job). After that no tool imports (planned)
`drishti-console/`, and a guard test holds it.

## 5. The migration, step by step

Order: highest risk of a wrong screen first; each step ends with its twin **deleted** in the same merge. Sizes are estimates from
the line counts of section 2 (Python removed, Java added including tests), in builder-days for one builder.

| Step | What | Server work (Java) | Deleted in the console and tools | Size | Risk |
|---|---|---|---|---|---|
| **S0** | **Foundations and the proof.** `stamps` in about; the bootstrap builder with `distribution`, `modules`, `engines` first; the console's `ServerFacts` store (per app and server, swapped whole) fed by the watch; **the detached-console test**: the console started on a copy that holds `drishti-console/` only (no `config/`, `modules/`, `docs/contracts`, `tools/`), every browser test tagged core run against it. It fails at first and lists every remaining reader: that list is the checklist of S1 to S6 | about 600 lines | nothing yet | 3 d | the answer's shape changes later: versioned by `version` in the document, additive only |
| **S1** | **Packs.** `me/console` with `packs`; the pack `console` answer and files; `commands` in the bootstrap. The console's pack list, examples, workspaces, Help guides, facts and README come from the server | about 700 lines | `Packs._load`, `_read`, `_signature`, `fallback`; `Editions.default_packs`; `menus._pack_command_on`; every `core.resolution` import in `core/` and `routes/`; the `dir` field of the packs answer; `packs.enabled` on the console | 4 d | Help guides with pictures: the allow-list must cover relative images (a test walks every shipped guide) |
| **S2** | **Distribution, lenses, modules.** The compiled filter, the brand, `console-settings`, the lenses and each module's words, paths, statics and menus in the bootstrap | about 500 lines (the compile exists in `Distributions`; this serialises it) | `core/distribution.py` whole (398); the file half of `core/edition.py` (about 250 of 557: `Editions.load`, `mapping`, `_use_distribution`; `Edition` becomes a view over the filter's sets); `core/house.py`'s folder read; the `words`, `paths`, `statics`, `engines` of `plugin.yaml`; the shipped-beside half of `core/module_parts.py`; `distributions_dir`, `module_manifests_dir`, `lens.dir`, `modules.*` console settings; a console's own `--distribution` (open question Q3) | 5 d | a console that today runs its own distribution over a Drishti All server loses that until Q3 is decided (`lens.dir` removed) |
| **S3** | **Engines and under-construction.** The catalogue and the registry in the bootstrap | about 150 lines | `drishti-console/config/engines.yaml`, `plugins/banking/engines.yaml`, the equality test that holds them to the server's; `core/under_construction.py`'s file read; about 100 lines of `core/engines.py` | 2 d | low |
| **S4** | **The person's decisions.** `functions`, `engines-opened`, `admin-pages`, `grants` in `me/console` | about 500 lines (the rules exist on the server for enforcement; this exposes their results) | `menus._person_opens`, `engine_opened`, `module_active`'s logic; the role literals in the 33 files (each becomes a `grants` or `admin-pages` lookup); `core/modules.py` `Me` folds into the person's answer | 5 d | a page hidden that a role may open, or the reverse: the test compares, for five demo roles, every menu item drawn with the server's own `403`/`200` for its page |
| | **Drill A** (S0 to S4): the console decides nothing about packs, modules, distributions, engines or access | | | | |
| **S5** | **Tools off the twin.** `resolve --json`; `tools/resolved.py`; the 43 files move to it | about 300 lines (a command over `ConfigResolution`, the JSON writer shared with 3.5) | `core/resolution.py` whole (1,276); `drishti-console/tests/test_resolution.py`; the Python half of `tools/test_config_readers_guard.py` (it becomes: no Python file but `tools/resolved.py` reads a pack, module, connector, bindings or rules folder); `pack-resolution/cases.yaml` and `connector-layers/cases.yaml` stop being shared fixtures and stay as the Java resolver's own tests; `schemakit` moves to `tools/` | 5 d | generators' speed (the cache) and a checkout with no jar (Q2) (deleted) |
| **S6** | **Settings and limits.** `limits` in the bootstrap; Help → Configuration and Admin → Configuration ask the server | about 200 lines | the "where set" rule of `core/settings_page.py`; the console's copies of the builder, controls and form limits; `core/hostrule.py` (132) and its use in `core/cluster.py` (the server's member list is already checked by the server's rule) | 3 d | low |
| **S7** | **The AI layer.** The console calls the server only | none (exists) | `core/ai/` but the `server` provider and the thin gateway (about 4,400 of 4,678); `core/retry.py` (243); `config/ai`, `config/mcp` reading on the console; `test_ai_layer.py`, `test_ai_proxy.py`, `test_ai_tls.py`, `test_llm_providers.py`, `test_llm_contract.py`, `test_retry.py` (their server twins stay) | 2 d | a site that configured a provider only on the console must move the file to the server (the same format): said in the release notes and on Health (open question Q4) |
| **S8** | **Jobs on the server.** The lake build, lake maintenance and pack design job APIs (3.7); the landing answers of trading and market data | about 1,500 lines, the largest step | `core/lakes.py`, `core/lake_maint.py`, `core/packdesigner.py`'s subprocess half, `plugins/banking/trading_landing.py`, `market_landing.py` (about 2,600) | 8 d | it touches loading and module APIs; it may move to the release after i5 without blocking the rest (open question Q7) (`trading_landing.py`, `market_landing.py` deleted in S8a) |
| | **Drill B** (S5 to S8, the performance gates of 3.8, the release) | | | | |

Total: about 37 builder-days; with three builders on independent steps (S1 with S3; S2 then S4; S5 beside them from the start),
about three weeks to Drill A and two more to Drill B.

### 5.0 Where the build stands

| Step | Built | Left in the step |
|---|---|---|
| **S0** | `stamps` on `/public/about` (`installation`, `resolution`, `modules`, `engines`, `packs`); `ConsoleAnswers` and `GET /api/v1/console/bootstrap` with `distribution` (id, about, lenses), `modules`, `engines`; the console's `ServerFacts` (`core/server_facts.py`) fed by the watch, with `If-None-Match`, the last answer kept and said while the server is away; the detached-console test (`test_detached_console_browser.py`, `detached_console.py`: a copy of `drishti-console/` alone, an audit hook in every process) walking the sitemap; the server's version, product and notice shown from `/public/about`, the console's build a diagnostic on Health | the push of the stamps (Drill B, Q8); never-backwards across cluster nodes (a cluster-wide configuration version in the bootstrap); `settings` stamp (S6); the waiting page before the first answer; the "core" browser tests run against the detached console beyond the sitemap walk; recorded `bootstrap.json` / `me-console.json` written by a Java test |
| **S1** | `GET /api/v1/me/console` with `person` and `packs`; `GET /api/v1/packs/{pack}/console` and `/console/files/**` (allow-list: the guides and the pictures they show); `commands` in the bootstrap; `core/packs.py` asks them (no folder, no fallback, guides kept by SHA-256 in the console's temp folder); facts, README sources (DATAMAP) and README links from the answers; `menus._pack_command_on` a lookup; `Editions.default_packs`, `Packs._load`, `_read`, `_signature`, `fallback`, the `dir` of the packs answer and the console's `packs.enabled` deleted, with the `DRISHTI_PACKS` that `bin/start-console`, the compose console and the ECS task definitions set for it (`core/resolution.py` still reads `DRISHTI_PACKS` for the tools until S5); an older server is said on every page and on Health, and the rolling upgrade runs the consoles last | the `core.resolution` imports of `distribution.py`, `edition.py`, `house.py`, `menus.py` (module menus) and `module_parts.py` read modules and distributions, not packs: they go with S2 (`test_thin_console_guard.py` lists them and only lets the list shrink); `lens` in the person's answer (S4 with the rest of the person's decisions); the pack designer's install folder moves with the job (S8) |
| **S2** | the bootstrap's `distribution` compiled by the server (`ConsoleDistribution`: filter, brand, `settings` layers and presets, `feature-keys`, lenses with their arrangement, `help-title`) and each module's `title`, `console-menus`, `domain-label`; `?distribution=` for a console's own distribution (Q3); `GET /api/v1/admin/distributions` for Admin -> White label; the shipped folder modules with a console part listed and handed over by `/public/modules/console`; `core/distribution.py` deleted, `Editions` a view over the answer (`follow`), house, menus, known commands, module parts from the answers; `distributions_dir`, `module_manifests_dir`, `lens.dir` removed; recorded answers in `drishti-console/tests/answers/` written by `ConsoleAnswersRecordTest` | the `words`, `paths`, `statics` and `engines` of the console's own `plugin.yaml` stay: they describe the console's own banking code (its routes and statics), not the server's module; the `engines` list goes with S3 |
| **S3** | the bootstrap's `engines` with the catalogue's screen lists and `opens` (`EngineCluster.console`), its `under-construction` items; `core/engines.py` and `core/under_construction.py` read them from the answer; both console catalogue copies, the byte-equality checks and `under_construction.file` deleted | the `engines:` list of the console's `plugin.yaml` stays (which engines the console's own banking code serves) |
| **S4** | `me/console` with `engines-opened`, `grants` and the person's `modules` (folded in); the session answer's `admin`; `Identity.is_admin` is the server's decision; `menus.engine_opened` a lookup; `menus._person_opens` and `core/modules.py` `Me` deleted | `admin-pages` is served (the one list of Admin page ids is the server's; the console's `ADMIN_PAGES` deleted); the module words and engines come from the installation answer's modules (the console's `plugin.yaml` keeps only its own routers, paths and statics); the menus-against-enforcement test (5.2) is not written; `lens` in the person's answer stays the console's per-person setting |
| **S7** | `core/ai/` and `core/retry.py` deleted whole (the `server` provider too: `POST /api/ai/chat` passes the call to the server directly); Admin -> AI shows the server's layer, Test connection and Retry now ask the server; the console's `ai.*`, `mcp.*`, `retry.*` settings and the six Python test files deleted | the Health line naming a console provider file a site still keeps (Q4's "said on Health") |
| **S6** | `limits` in the bootstrap: `builder` (`core/builder.py` `Limits.of`), `controls` (`core/controls.py` `of`), `form` (`core/forms.py` `limits`); the console's `builder.max_*` settings and its number copies deleted. Admin -> Configuration shows the server's values as masked by the server and asks `POST /api/v1/admin/settings/secret-keys` for the console's own keys; `is_secret` deleted. `core/hostrule.py` deleted: its rule moved into `tools/hostaddr.py` (the tools'); `core/cluster.py` takes the server's member list as the server checked it; the relay's advertised address is refused only for a loopback, wildcard or link-local literal | the `settings` stamp; a removed console key still set named on Health. Built since: Help's documents ship with the console (4.1; `<install>/docs` and the root documents, as the console images copy them, held by `test_console_ships_its_help.py`), Help -> Configuration among them, and the detached-console test ships them with its copy |
| **S5** | `resolve --json` in the kernel jar (`ResolveCli`: packs, modules, effective connectors over a folder, templates, rules, problems; `--root`, `--site`, `--connectors`); `tools/resolved.py` reads it, kept under `<data>/tmp/resolved/` by its inputs' modification times; the tools and the console tests moved to it; `core/resolution.py` and `test_resolution.py` deleted; the guard: no Python resolver comes back; the console start no longer sets `DRISHTI_PACKS` | `core/schemakit` stays in the console until the pack design job moves to the server (S8b, i7); the timing of the slowest generator before and after; the outputs byte-identical before and after |
| **S8a** | the trading and market-data modules' server parts (`modules/<module>/java/…`, in their `.dmod`s) compute the landings' figures (`/api/v1/trading/landing/*`, `/api/v1/market-data/landing/*`) as the person through `KernelCalls` (drishti-kernel-api; the kernel's `LoopbackKernelCalls` calls its own API with the person's headers); the console passes the answers on; `trading_landing.py`, `market_landing.py` and their tests deleted; `trading.people` is the server's `drishti.trading.people`; the Java tests of the landings (`MarketLandingRecordTest`, `TradingLandingRecordTest` over their own stand-in servers: the Python's figures, two people at once) record every answer the console's tests read (`drishti-console/tests/answers/landings/`, kept current by `RecordedAnswers` as the installation answers are), and the console's market and trading stand-ins replay them (`landing_answers.py`), holding no figure | — |

### 5.1 No dual machinery, step by step

A step's merge contains the new call **and** the deletion. There is no flag that chooses between the file reader and the server's
answer, and no fallback to the old reader. During a step's branch the detached-console test is the judge; after its merge the
guard test forbids the deleted reader's return (a console file that opens a path outside `drishti-console/` and the console's own
data and temp folders fails).

Server and console of different versions: the console requires a server that sends `stamps` (the same release). An older server
gets the plain message on Health and on the waiting page, in the words of the version check. Rolling upgrades update the servers
first, as the upgrade guide already says.

### 5.2 Tests

| Tests | What happens |
|---|---|
| **Browser tests** (193 files) | **stay unchanged**: they test over HTTP and cannot tell who decided. They are the proof at every step |
| **Detached console** (new, S0) | the console with only its own folder passes the core browser tests; from Drill A it is a permanent test of the drill |
| **Server contract tests** (new, Java) | one per answer: the JSON's shape (a schema in the API reference), the `ETag` and `304`, no person in the bootstrap, the digest changes exactly when an input changes |
| **Unit tests of deleted Python** (`test_resolution.py`, `test_distributions.py`, the file-reading halves of `test_edition.py`, `test_sitemap.py`, `test_module_menus.py`, `test_lens_menus.py`, `test_engine_off_everywhere.py`) | the rule's test **moves to Java** beside the rule (most exist there already: the resolver, `Distributions`, the registry); the Python test is deleted with the code. What remains in Python tests the lookup: given a recorded bootstrap and person's answer, the menus drawn |
| **Recorded answers** | the console's unit tests read `bootstrap.json` and `me-console.json` samples that a Java test **writes** from the demo installation, and the modules' landing answers their own Java tests write (`answers/landings/`). One direction only (the server makes them, the console reads them): a sample of an API, not a fixture two implementations must both pass. `RecordedAnswers` keeps each: `-Ddrishti.record=true` writes it, otherwise a recording older than the code fails the Java test |
| **Never stale** (new) | flip a module, a pack, an engine and a function switch on the server; the next page after the stamp shows it on every worker; with the server stopped the notice is shown and no menu changes |
| **Two people at once** (new) | two sessions with different roles and lenses ask in parallel on one worker; neither sees the other's packs, grants or menus (the session-isolation rule; a leak blocks the release) |
| **Menus against enforcement** (new, S4) | for the demo roles, every item the console draws opens (`200`), and no page the server would open is hidden |
| **Performance** | the gates of 3.8, in Drill B |

### 5.3 Risks

| Risk | Mitigation |
|---|---|
| The console cannot draw before the first answer | the waiting page; the bootstrap is one small call; start order still does not matter (the watch retries) |
| A stale answer shown as current | the stamp rule (3, rule 3), the push with the poll as floor, the never-stale test; an answer's age is on Health |
| The bootstrap grows into a second configuration dump | it holds decisions only (rule 2); its contract test caps its size and fails on a path or a secret-like key |
| Someone adds a file reader again | the guard and the detached-console test in every drill |
| Tools slow down by starting a JVM | the cached document (4.2); measured in Drill B on the demo-lake build |
| The work is thrown away by i7 | it is not: a Java console would ask the same APIs (section 6) |
| The console and server on one machine lose nothing, yet sites change settings | the removed console settings are listed in the release notes; a removed key still set is named on Health for one release, then ignored |

## 6. What this means for i7: the console on Spring Boot

### 6.1 What is left after the thin console

- About **33,700 lines** of Python in `core/`, `routes/` and `plugins/` (from 48,780), and what is left is of one kind: route
  handlers that assemble a page's context from server answers, the designers' document edits, templates' helpers, serving.
- **No server rule is left to port.** The 2026-11-08 design counted `core/` (20,140 lines then) as "the bulk of the work"; the part
  of it that would have been hardest to port faithfully (the resolver, the distribution compile, the access lookups) is gone.
- **The APIs carry over unchanged.** A Java console must not read the server's folders either (it is a separate tier, possibly a
  separate machine, possibly in front of several kernels). It would call the same `bootstrap`, `me/console` and pack answers. i5's
  server work is i7's server work, done once.
- **The module contract is already declarative** (console parts as bundles from the registry). Nothing in i5 adds Python to a
  module.
- **Measured baselines exist**: page latency at 50 people, memory per worker, the detached console.

### 6.2 What the thin console does not give

Enterprise single sign-on beyond today's OpenID Connect sign-in (SAML, Kerberos, the identity map, tokens per kernel); the
console as a placement role and one console in front of many kernels; virtual-thread fan-out; one runtime library for the
kernel's and the console's module hosts; a console on the Windows QA machine with Java only. These are sections 10.5, 10.6 and
10.11 of [KERNEL_AND_MODULES.md](KERNEL_AND_MODULES.md), unchanged by this design and waiting on the decision below.

### 6.3 The decision table (to fill with i5's numbers)

| Criterion | Keep the thin Python console | Migrate to Spring Boot (i7) | What decides |
|---|---|---|---|
| **Single sign-on** | OIDC works today (`core/oidc.py`). SAML, Kerberos and LDAP would each be a Python library to vet and operate; the server's side is Spring Security already, so two security stacks remain | one stack (Spring Security) for OIDC, SAML 2.0, Kerberos, LDAP; the designed identity map and per-kernel tokens | the first client's requirement: OIDC only → either; SAML or Kerberos → migrate |
| **High availability** | sessions already live on the server; a thin console holds only caches it can refetch, so several consoles behind a balancer work once the live-channel relay (`core/channel_relay.py`) is proved across hosts | Spring Session JDBC; the console as a cluster member with placement | whether two or more consoles are needed before i7 could ship; if so, prove the Python console pair in i5's Drill B |
| **Operations** | two runtimes to patch (JVM and Python with its virtual environment and locked packages); the Windows QA machine needs Python | one runtime, one jar beside the kernel's; nothing but Java on Windows | the client's operations team; the Windows QA rule favours migrate |
| **Performance** | async fan-out works but depends on discipline (one blocking call stalls a worker); after i5 the pages do less work | virtual threads: blocking code is correct code; the design's target is 30% faster fan-out pages | i5's measurement: if the fan-out pages meet their budget at 50 and 200 people on the thin console, performance is not a reason |
| **Development speed** | templates and handlers change with no build; the browser tests exist; nobody waits | every page ported (about 33,700 lines and 164 templates to Jinjava), a strangler period with two consoles; afterwards one language for module authors' context builders | how many module teams will write console code, and when; a port of this size is one release with no product features |
| **Session isolation** | holds by review and tests; a module-level global can still leak | per-request state by construction, an architecture test on singletons | the leak history after i5 (none found in two drills → not a reason by itself) |
| **Many kernels, white-label consoles** | possible (the server list exists) but the per-kernel union of menus would be new Python | designed for it | whether a client needs one console over two kernels within the year |
| **Cost and risk** | none beyond i5 | high: the largest single item on the roadmap; the render-equality and latency gates reduce risk, not size | the owner's roadmap |

**Recommendation.** Do not decide now. Decide at i5's tag with three facts in hand: the measured page latency of the thin
console, the first client's sign-on requirement, and whether a second console is needed in production. If the client needs SAML or
Kerberos, or one console over several kernels, migrate in i7 (it is then about 30% smaller and holds no server logic). If OIDC and
a pair of consoles are enough and the latency budget is met, keep the thin Python console and spend i7 on product.

## 7. Open questions for the owner

| # | Question | Recommendation |
|---|---|---|
| **Q1** | Is the bootstrap behind the console's service token, or public like `GET /public/modules/manifests` today? | **Service token.** It lists everything the installation has; the console already holds the token. `GET /public/modules/manifests` then needs the token too, one release later |
| **Q2** | Offline tools: read the Java resolver's JSON through the jar (the twin is deleted, a build is needed), or keep `resolution.py` as a tools-only module (no build needed, the twin and its fixtures stay)? | **The jar.** It is the only choice that ends the twin. The drill and the demo-lake build have the jar already; a generator on an unbuilt checkout says the one command to run |
| **Q3** | A console may today run its own distribution over a Drishti All server (`--distribution` on the console). Keep that? | **Keep the feature, move the compile**: the console names the distribution (`GET /api/v1/console/bootstrap?distribution=finance`) and the server compiles it and answers only if the installation holds that distribution's modules. The console reads no distribution file |
| **Q4** | Delete the console's own AI providers (S7), leaving the server as the only place a model is called? | **Yes.** Keys, policy, masking, limits and audit in one place; the recommended setting is already the `server` provider. Sites with a console-only provider file move it to the server |
| **Q5** | The console's start-up file: keep its small placeholder grammar, or read it with the Java settings-file rules too? | **Keep it small and say so**: `${NAME}` and `${NAME:default}`, one level, nothing else, since after i5 it holds only start-up keys. No twin to hold in step because the two files never share a key |
| **Q6** | Home's fact of the day: leave its expression language in the console, or move it to the server? | **Leave it in i5** (it draws from served answers, risk L) with the facts file served by the pack answer; revisit with i7, since a Java console would otherwise have to port the expression language |
| **Q7** | Is S8 (lake build, lake maintenance, pack design as server jobs; landing figures in the modules) in i5 or the release after? | **Landing figures in i5** (the server-computes-risk rule, and they are module work that can run beside S1 to S4); **the three jobs in the release after**, unless the detached console must also do Admin → Lakes in i5. Until then those three pages say they need a console on the server's machine (built for Admin → Lakes and a lake table's page: a console with no lake engine beside it says so and lists the server's read-only lake files) |
| **Q8** | Push of the stamps (the event stream) in i5, or the 5-second poll only? | **Both in i5**: the poll is S0, the push is a small addition in Drill B once the answers are stable. The poll alone already matches today's delay |
| **Q9** | The release numbers in [the release notes](../../RELEASE_NOTES.md) still describe i5 as the cluster and the Spring Boot console. Where does the cluster on standard libraries go? | Keep the cluster item where the plan has it and replace only the console item with this design; move the Spring Boot console row to i7 with "decided after i5". The plan's table is edited once the owner confirms |

## 8. Shared screens: one Build home, one Approvals queue, one Explain, the trader home, the controls screens and the editors

Four screens that replace several today, chosen by the product owner from mock-ups, followed by the controls screens (section 6) and
the editors (section 7). Each folds duplicates into one and deletes the old in the same release (*lean & streamlined*). The mock-ups are in
[mockups/shared-screens/](mockups/shared-screens) and open in a browser; the editors they lead to are in [EDITORS.md](#87-the-editors-one-pattern-for-every-object-people-author).

### 8.1 One Build home

Mock-up: [e1-build-home.html](mockups/shared-screens/e1-build-home.html), option **A** as the home, **B** as the first-visit view, **C**'s
lineage columns in A's lists.

- **One rail** grouped **Data** (connectors, lakes, tables and models, Python tables) · **Screens** (Sutras, packs, the UI kit) ·
  **Analysis** (reports and boards, pivot views, functions) · **Work** (my drafts, waiting for review, recently changed), each with a count.
- The middle lists the chosen kind: name, kind, status with versions, **feeds / fed by** (lineage), last change and by whom; opening an
  item goes to its editor (EDITORS.md's three-pane shell).
- **First visit** (or by choice): four task tiles, *Bring data in · Shape it · Show it · Package it*, with "continue where you left off"
  and "waiting for you".
- One search across everything a person may build; + New opens the guided steps; what a person may build follows their role.
- Studio, BI Studio, the workbench and the pack tools redirect here and their separate homes are deleted.

### 8.2 One Approvals queue

Mock-up: [e2-approvals.html](mockups/shared-screens/e2-approvals.html), option **A**, with **B**'s *due soon* items pinned at the top.

- **One inbox** of everything waiting for me, of any kind: Sutras, tables and models, reports, trade corrections, pivot functions, VaR and
  exposure sign-offs, users and roles, API keys, captures. Filters: Data · Screens · Risk · Access; *Mine sent* and *Done*.
- **Due soon** (a sign-off with a deadline) pinned on top.
- The chosen item shows **what changes** (a diff, with the maker's reason), **its effect if approved** (e.g. a scope's VaR and limit use
  before and after, the reports that refresh) and **the rules** that allow me to decide.
- Approve; reject with a reason; or **ask a question** (a thread on the item). The maker can never approve their own. Everything audited;
  a count on the top bar; the per-kind queues redirect here and are deleted.
- A team board by stage (proposed, questions, approved, rejected) only if asked for later.

### 8.3 One Explain for every number

Mock-up: [e3-explain.html](mockups/shared-screens/e3-explain.html), option **A**, with **B** as its preview and **C** through a link.

- Every computed number on every screen (VaR, ES, components, parametric VaR, exposure, FRTB-SA, SIMM, the cubes, Pivot cells, BI
  measures) has one Explain.
- **Preview** (hover, or `?`): a one-line card with the answer and links.
- **Drawer** (click, or `E`) on the right, the page still visible, four tabs, the same everywhere: **Formula** (with the actual values),
  **Inputs & lineage** (down to rows), **Why it moved** (since the previous date, steps that add up), **Workbook** (the live-formula
  Excel); plus checks (inputs complete, equal to the official capture).
- **Full page** from the drawer, for auditors and printing.
- Computed and explained on the server; a stored figure says it is stored. Today's explain panels (How calculated, the ? pop-ups, Why it
  moved and the others) fold into it and are deleted.

### 8.4 The trader home

Mock-up: [e4-trader-home.html](mockups/shared-screens/e4-trader-home.html), option **A**, with **C**'s feed as a column and **B** one
click from a book.

- **Five headline figures**: today's P&L (live), VaR against its limit, the biggest risk, breaches (and near ones), approvals for me.
- **My books**: live P&L, VaR and a limit-use bar each; a click opens the book position by position (P&L, sensitivities, VaR
  contribution).
- **Markets I watch**, live; **what changed since this morning** (new and amended trades, market, with *Why it moved*); a **live feed**
  column (trades booked, market moves, limits approached, approvals waiting).
- What a person sees follows their desk and role; every figure is computed on the server and ticks through the live hub, never in the
  browser; every number opens the Explain; the command line is always at the top. It is the Trading lens's home (configuration, not a
  new product); a pre-deal *what if I add this trade* box joins it, drawn from the Java pre-deal service (built: `POST /api/v1/risk/exposure/predeal`, EXPOSURE_GUIDE.md section 18.9) when its screen is built on the Elements libraries.

### 8.5 Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps (UI, command line and YAML) and its help entry,
and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | One Build home (rail, lists with lineage, first-visit tiles, one search) | Studio, BI Studio, the workbench, the pack tools' homes | ☑ (`/build`; BI Studio's own home `/bi` redirects to it, its four lists the home's For you strip) |
| 2 | One Approvals queue (inbox, due soon, diff, effect, rules, questions) | the per-kind approval queues | ☑ (built: `/approvals`, `/api/v1/approvals`; the access kinds and the effect in risk numbers are under construction) |
| 3 | One Explain (preview, drawer with four tabs, full page) | the five explain panels | ◐ built for VaR, ES, parametric, exposure, the MR cube's cells and the Pivot's Why did it move? card (`GET /api/v1/explain`, `drishti_explain.js`); BI measures and Sutra metrics in the under-construction registry (`explain-*`) |
| 4 | The trader home (headline figures, books, markets, what changed, feed) | the generic home for the Trading lens | ☐ |

### 8.6 Controls screens: run status and sign-off, the breach queue, number honesty

The screens of the controls a risk function runs every day, chosen by the product owner from mock-ups (2026-11-05), built
(trust, RBAC, security and controls; the security rules themselves are settled in that release's design discussion). The mock-ups are
in [mockups/controls/](mockups/controls); people, roles and API keys are in [EDITORS.md](#87-the-editors-one-pattern-for-every-object-people-author) sections 5 and 6; the Approvals
queue and the Explain drawer they use are in [the shared screens part](THIN_CONSOLE_DESIGN.md#8-shared-screens-one-build-home-one-approvals-queue-one-explain-the-trader-home-the-controls-screens-and-the-editors).

#### 8.6.1 Run status and sign-off

Mock-up: [f1-run-signoff.html](mockups/controls/f1-run-signoff.html), option **A** for a day's run, **B** as the overview, **C** as a tab.

- **A day's run** (VaR, exposure) as five steps: **inputs arrived → complete → checks → computed → signed off**, each green, amber or
  waiting. The checks are listed (positions not priced and their share of gross, a day-on-day move over its threshold with *Why it
  moved*, the backtest zone).
- **The sign-off panel**: who prepared it, who signs, by when, the signer's comment, and what signing means: the figures and their capture
  become **Official**.
- **Overview**: a grid of business dates × measures, each cell computed, awaiting sign-off, signed (by whom, when) or **restated**; a cell
  opens the day's run. **Timeline**: everything that happened to the run, as a tab of the day's run.
- Rules: "Official" only after sign-off; the signer is never the person who prepared it; warnings and the comment are stored with the
  capture; a change after sign-off is a restatement with its reason and a second sign-off; an overdue sign-off reaches the Approvals
  queue as *due soon*; everything audited.

#### 8.6.2 One breach and escalation queue

Mock-up: [f2-breaches.html](mockups/controls/f2-breaches.html), option **A** as the risk-control screen, **B** in the Approvals inbox, **C**
as a tab.

- **One list** of open breaches and near-breaches (VaR limits and credit limits); the chosen one is a **case**: the figures with Explain
  and *Why it moved*, the **escalation ladder** (who was told when, the next level and when it is reached), the actions taken, and how it
  may be resolved: back under the limit, an approved temporary increase (four eyes), or a waiver with a reason.
- A breach escalated to a person appears at the top of **their Approvals inbox**.
- A **heat map** (limits × days, coloured by use) as a tab, for trends building toward a breach.
- Rules: **credit-limit use is computed from the engine, never stored**; near-breaches shown from a configurable threshold (e.g. 85%);
  escalation by time and level, configured; built on the one alert engine (today's separate alert lists fold into it); everything audited.

#### 8.6.3 Number honesty: computed, stored, official

Mock-up: [f3-honesty.html](mockups/controls/f3-honesty.html), mixed by context.

| Status | Means | Headline tiles and cards | Tables, trees, the pivot |
|---|---|---|---|
| **Computed** | by Drishti, from its inputs, now | badge `COMPUTED` | green dot |
| **Stored** | a source's figure as delivered, not recomputed | badge `STORED · as delivered` | amber dot |
| **Official** | signed off (section 1) | badge `OFFICIAL · signed <time>` | blue dot |

- One legend per screen with dots; colour is never the only cue (each dot's tooltip says the word).
- The Explain drawer's first line states the status, with the source and the time.
- The server sends each figure's status; the console only displays it; a guard test fails if a figure is drawn without one.
- Exports carry a status column. A stored figure whose computation is under construction keeps the registry's chip
  ([UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)).

#### 8.6.4 Build checklist

Each item ships with a click test for every button, its guide section, its tutorial steps and its help entry, and deletes what it replaces.

| # | Screen | Replaces | Done |
|---|---|---|---|
| 1 | Run status and sign-off (the day's run, the grid, the timeline) for VaR and exposure | the capture pages' partial status | ☐ |
| 2 | One breach and escalation queue (cases, ladder, resolve, inbox, heat map) | the separate alert lists for limits | ☐ |
| 3 | Number honesty (badges, dots, legend, the drawer's first line, export column, guard test) | unlabelled stored figures | ☐ |

### 8.7 The editors: one pattern for every object people author

How Drishti's authoring screens look and behave: tables and virtual tables, semantic models, Python-driven tables and models, people
and roles, and API keys. One shell and one set of rules, so a person who has learned one editor knows them all. The product owner chose
each design from mock-ups (2026-11-05); the mock-ups are in [mockups/editors/](mockups/editors) and are opened in a browser.

Which release builds what: tables, virtual tables, semantic models and Python-driven tables and models come with the BI foundation;
people, roles and API keys come with trust, RBAC and security, whose rules are settled in that release's design discussion (this
document fixes only their screens).

#### 8.7.1 The shell every editor shares

Mock-up: [g1-editor-shell.html](mockups/editors/g1-editor-shell.html), option **C** as the home, option **B** for every *+ New*.

- **Three panes.** Left: the objects (tables, models, Python tables) with search. Middle: the editor, with the **live YAML below it**,
  the line being edited highlighted. Right: a **live preview with checks** (rows, totals computed by the pivot engine, warnings,
  impact).
- **A Visual / YAML / Both toggle**, remembered per person. YAML edits validate as typed and show back in the visual side; a layman can
  keep the YAML collapsed.
- **The header**: where you are, the name, the status with versions (*draft v4*, *approved v3*), and Validate, Preview, History,
  Impact and **Propose for review**.
- **+ New** opens guided steps in plain language (option B); when the object exists it opens in the three-pane editor.
- **As built**: the shell is drishti-designer's `DesignerShell` and the YAML its `YamlPane` over drishti-inputs' `CodeEditor`
  ([ELEMENTS.md section 8.9](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/DESIGN.md)), the same for every designer: the table,
  model and Python editors, the Build workbench's canvas and YAML, the BI report designer's canvas, undo and YAML. Its look is mock-up
  g7's: **studio** (option A, the default: the left rail's tabs, the canvas with the YAML docked under it, the inspector, the preview),
  **side by side** (B: the canvas and the YAML side by side, the inspector a card by the selection) and **terminal** (C: a command line
  on Ctrl+K and the function keys F3 Validate, F4 YAML, F5 Preview, F7 History, F8 Impact, F9 Propose); each person's layout, mode and
  whether the YAML is open are kept by the server. A YAML edit is read on the server after a pause and each problem is marked at its
  line (a sign in the gutter, the list under the editor); the visual side keeps the last valid version.

#### 8.7.2 Tables and virtual tables

Mock-up: [g2-tables.html](mockups/editors/g2-tables.html), option **A** as the editor, option **C** as its Text toggle.

- **The source and its look-ups as rows**; **columns as a checklist** with the name here, where it comes from, its type, and computed
  columns as Rachana formulas. A look-up brings one row of another table by a key (many to one: `related(table.COLUMN, on = key)`).
  A plain table is the same editor with one source and no look-up.
- **Tables use look-ups; models join** (product owner, 2026-11-06): a table enriches its rows with look-ups and never multiplies them;
  combining tables row by row (one to many, several facts) is a semantic model's relationships (section 3).
- **Text toggle**: the same table as one Rachana statement (`table … from … select … where …`, look-ups as `related(…)` columns) with
  type-ahead.
- **A look-up across connectors shows its cost** (rows each side, memory, time) and is refused above a limit until the reader filters.
- The diagram (option B) is not an editing mode; it may appear later as a read-only picture of the look-ups and a model's relationships.

#### 8.7.3 Semantic models

Mock-up: [g3-models.html](mockups/editors/g3-models.html), option **A** as the editor, **B** as the Overview and the *+ New* steps,
**C** for a measure's details and the Access tab.

- **Tabs**: Overview, Tables, Fields, Measures, Hierarchies, Row rules, Masks, Access, each with a count.
- **Overview** answers four questions: what it is about, what people look at it by, what numbers it gives, who may see what. The same
  four questions are the *+ New model* steps.
- **A measure's ?** opens its card: the formula, a plain explanation, a worked test value, and where it is used.
- **Access** is a matrix of roles against rows, columns (masked or not) and editing.
- Whether a total adds up is the engine's answer, never the author's: VaR, ES and ratios are computed per total.

#### 8.7.4 Python-driven tables and models

Mock-up: [g4-python.html](mockups/editors/g4-python.html), option **A** as the editor, **B** (the Notebook) to explore, **C**
(templates) for common cases; all three end in A.

- **Code beside its output contract**: the columns, types, keys and rules the code must return. The inputs, when it runs (on demand
  and cached per business date, or on each load of an input), and its compute pool and limits are on the same page; a sample run and its
  checks on the right.
- **Notebook → Turn into table**: cells explored on the server become a build function, and the contract is filled in.
- **Templates** (join and derive, resample, statistics per group, a file or feed from an approved source) write the Python and show it.
- A Python **model** is the same, with its measures declared in the contract.
- **Where it runs.** The sample run, the Notebook's cells and the table's rows when read all run on the server's compute pool `python`, in
  its strict sandbox: the inputs read as the person running it (row rules and masks), pinned libraries, the code's time and memory limits, no
  network (an approved source is read by the server and handed in as an input), files only in its own folder, every run audited; the rows are
  checked against the contract and a mismatch is refused in words ([bi/NOTEBOOK_AND_PYTHON.md section 19](ARCHITECTURE.md#291-one-python-runtime)).
  Where the server cannot run Python safely (compute off, Windows) the buttons are off and say why.

#### 8.7.5 People and access

Mock-up: [g5-people.html](mockups/editors/g5-people.html); all three options, as the three tabs of **Admin → People & access**.

- **People**: a searchable list; a person's page with sign-in (directory, MFA, last seen), roles, scope (packs, desks, lens), powers,
  API keys, recent activity, and **what this person sees**.
- **Roles**: every role against every power on one page; built-in roles (from configuration) are locked.
- **Check access**: *can this person do this?* answered yes or no **with the reason** (the role, the rule and the attribute that
  decided it) and how to change it.

#### 8.7.6 API keys

Mock-up: [g6-apikeys.html](mockups/editors/g6-apikeys.html); all three options.

- **My keys** (everyone): what each key acts as (its owner, never more than the owner's rights), its scopes in words, its limits (only
  these models, only from this network), expiry and **last use** (when, from where, what), Rotate and Revoke.
- **+ New key** in steps: purpose, what it may do, limits and expiry, then the secret **shown once** (Drishti keeps only a fingerprint).
- **Admin → API keys**: every key with owner, scopes, expiry, last use and flags (expiring, unused, used from outside the network),
  bulk revoke, and the key policy (maximum lifetime, unused keys revoked, a rotation warning).

#### 8.7.7 Rules every editor follows

- **Nothing goes live without review**: a change is proposed and approved by another person (four eyes), versioned and audited.
- **Impact before change**: every editor shows what uses the object (models, Sutras, reports, boards, keys) before it is changed.
- **One grammar**: every formula, filter, row rule and computed column is Rachana, typed in drishti-inputs' code editor (one line for a
  field, the whole text for the statement) with the function catalogue and the editor's names as its completion.
- **Built from Drishti Elements, never by hand** ([ELEMENTS.md section 3.1](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/DESIGN.md)): every pane of
  fields is drishti-inputs' `Form` (live, with the formula widget for a Rachana field: the table's name, rows and look-ups, the model's
  Overview and masks, the Build workbench's inspector through drishti-designer's `Inspector`); the rows of a table of settings (a model's
  tables, relationships, measures, hierarchies, row rules, fields and access matrix; a table's source and look-ups and its columns
  checklist) are drishti-grid with cell editing (`E.rowGrid`): Enter, F2, a double click or typing edits a cell, Enter saves, Escape
  cancels, Ctrl+Z undoes; a select of the offered values, a checkbox that turns at once, and a formula cell that is drishti-inputs'
  one-line Rachana code editor completing from the editor's names and the functions (the grid's `code` editor). Each edit, paste or undo
  is one change of the document; a row's ?, Remove and the engine's "adds up?" are cells the editor draws.
- **One entitlement service**: row rules and masks are enforced on the server for every reader (BI, Pivot, VaR, Flight, the API).
- **Computed on the server**: previews, totals and Python runs happen on the server; the console only displays.
- **Under construction**: an editor or a part of one that depends on unbuilt work is hidden or marked from the registry
  ([UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)).

#### 8.7.8 Build checklist

Each item ships with its tests (a click test for every button), its guide section, its tutorial steps (UI, command line and YAML) and
its help entry.

| # | Editor | Built in | Done |
|---|---|---|---|
| 1 | The three-pane shell, the Visual / YAML / Both toggle, the header, + New steps | BI foundation | ☑ |
| 1a | The shell on the designer kit (DesignerShell, YamlPane) in g7's three layouts; every designer on the kit (ELEMENTS.md 12.3) | Drishti Elements | ☑ shell, YAML; ☐ the editors' own panes |
| 2 | Tables and virtual tables (grid, Text toggle, cross-connector cost) | BI foundation | ☑ |
| 3 | Semantic models (tabs, Overview, measure cards, Access matrix) | BI foundation | ☑ |
| 4 | Python-driven tables and models (code and contract, Notebook → table, templates, the sample run on the compute pool) | BI foundation | ☑ |
| 5 | People & access (People, Roles, Check access) | Trust, RBAC and security | ☐ |
| 6 | API keys (My keys, + New key, Admin overview) | Trust, RBAC and security | ☐ |

## 9. Drishti Elements in Drishti

Drishti Elements is a separate project by the same owner: eight plain-JavaScript libraries (drishti-core, drishti-inputs, drishti-grid
with its pivot, drishti-chart with its maps, drishti-data, drishti-tree, drishti-graph, drishti-designer), their gallery and their docs,
in its own repository, [github.com/drishti-platform/drishti-elements](https://github.com/drishti-platform/drishti-elements), with its docs site at
[drishti-platform.github.io/drishti-elements](https://drishti-platform.github.io/drishti-elements/). Drishti uses it as an external, vendored component at a
pinned version. Everything about the libraries themselves (the design, the API, the contracts, theming, using them in an app, moving from
AG Grid) is in its docs, one home for each fact:
[DESIGN.md](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/DESIGN.md),
[API.md](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/API.md),
[CONTRACTS.md](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/CONTRACTS.md),
[THEMING.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/THEMING.md),
[USING.md](https://github.com/drishti-platform/drishti-elements/blob/v3.3.2-ide3/docs/USING.md) and
[MOVING_FROM_AG_GRID.md](https://github.com/drishti-platform/drishti-elements/blob/v2.4.0/docs/MOVING_FROM_AG_GRID.md).
This page says how Drishti holds and uses it.

### 9.1 The pin

The release tree of a tag of Drishti Elements (built by its `python3 tools/elements/cli.py dist` in a fresh clone of the tag, never from a
working folder) is copied, unchanged, to `drishti-console/web/static/vendor/drishti-elements/<version>/`. **The pin has one home: the name of
that single folder.** `drishti-console/core/elements_pin.py` reads it (`VERSION`, the tree's `ROOT` on disk and its `URL` under the
console) and maps a static path as pages name it (`elements/drishti-grid/drishti_grid.css`, `css/drishti_tokens.css`,
`vendor/echarts/echarts.min.js`) to the vendored file (`static_path`, `static_url`). The tree holds the eight libraries (their modules,
bundles, type declarations and sheets), drishti-core's sheets (`css/`), the themes, the engines with their licences (`vendor/`: Apache ECharts
and ECharts GL, CodeMirror, Apache Arrow JS, the Natural Earth map shapes), the gallery, the two shared tables (`data/`), the build tool
(`tools/elements/build.py`), `LICENSE`, `README.md`, `CHANGELOG.md` and `drishti-elements.json` (the version, the libraries and the
SHA-384 of every file). Its licence is the owner's proprietary licence, the same as Drishti's (`static/vendor/VENDOR.md`).

### 9.2 How pages load it

A template names the copy through one global, `ELEMENTS_DIR` (`/static/vendor/drishti-elements/<version>`, set in `core/app.py` from the
pin): `{{ BASE }}{{ ELEMENTS_DIR }}/elements/drishti-grid/drishti_grid.js`. No template spells the version. `base.html` loads drishti-core's
bundle first, and drishti-core knows the folder it came from: a console script that needs another library's file or an engine asks it
(`Drishti.elements.core.staticUrl('vendor/echarts/echarts.min.js')`); inside an embed (`/embed/v2/`) the scripts keep the
embed routes beside them, which serve the same vendored files (`routes/embed_routes.py`, `routes/embed_v2_routes.py`). The live hub's
shared worker learns the folder from the page (`drishti_channel.js` adds it to the worker's address).

A page loads each library after the libraries it requires, as `drishti-elements.json` lists them: `base.html` loads drishti-core,
drishti-data, drishti-inputs, then drishti-chart before drishti-grid (the grid's bullet cells are drishti-chart's), each once; a page that
draws charts adds only ECharts (`vendor/echarts/echarts.min.js`), which drishti-chart looks up when it draws. The client-side renderer imports
the libraries' modules from the same folder and loads their bundles and the map shapes from it too.

### 9.3 The host script

`static/js/drishti_elements-host.js`, loaded by `base.html` straight after drishti-core's bundle, is the one place Drishti's routes are
told to Elements: `configure({ endpoints: { live: '/api/rdp', changes: '/api/changes', pivotPage: '/pivot', capabilities:
'/api/v1/data/capabilities' } })` (the live-set client, the grid's pivot link and the data client read them; with none they say the
feature is off), and `register` for Drishti's own libraries (section 4), so a page may load them by name with the loader.

### 9.4 Drishti's own libraries

Two libraries stay in Drishti because they are clients of Drishti's server or belong to a module: **drishti-insight** (About and
Explain, search, approvals, workbook export) and **drishti-risk** (the banking module's kinds and presets). The client renderer is not a
library any more: every panel is Elements' painter (`drishti-views`) and Drishti's host part lives in the render folder (section 6).
They live under `static/elements/drishti-{insight,risk}/`, listed in
`static/elements/drishti_elements.json` (no version of their own: the pin), and import the eight through the vendored copy's entry files
(`../../../vendor/drishti-elements/<version>/elements/drishti-core/src/drishti_index.js`). Their bundles are written by the vendored build
tool:

```bash
python3 drishti-console/web/static/vendor/drishti-elements/<version>/tools/elements/build.py \
  --root drishti-console/web/static --manifest drishti-console/web/static/elements/drishti_elements.json --header config/license-header.txt
```

Their Node tests are `drishti-console/tests/elements/*.test.mjs` (`test_elements_node.py`); a domain module's library (drishti-genomics)
is tested the same way.

### 9.5 The two tables the server reads

`world-regions.yaml` (the grouping of countries into sub-regions and continents) and `formats.yaml` (the named formats) have their home
in Drishti Elements (`data/`), because both projects read them. `compute/drishti-rachana/pom.xml` adds the vendored `data/` folder as a
resource folder (the root `pom.xml`'s property `drishti.elements.version` names it; a guard holds it to the pin), so `WorldRegions.java`
and `Formats.java` read the same names from the classpath and git holds no second copy.

### 9.6 The render folder and the embed

`static/render/<version>/` (the public entry of the client-side renderer, its sheet and its integrity record) is Drishti's and is named after the pin; `tools/render_sheet.py` writes it. When the pin moves, a new folder is written and the previous one is removed: one renderer, one Elements version.

### 9.7 The gallery

Build -> Learn -> Elements gallery (`/elements`) opens the gallery of the vendored release tree, offline, with
`?visuals=/api/elements/visuals`: the hosted modules' visual types (a module's own kinds registered in drishti-chart) get their pages too.

### 9.8 The guards

- `tests/test_elements_vendored.py`: exactly one folder under `vendor/drishti-elements/`, its `drishti-elements.json` and the Maven property
  say the same version, `LICENSE` is there; nobody edits the copy (every file matches the SHA-384 of its manifest, nothing added); Drishti
  keeps no second copy of what moved; the host script registers Drishti's libraries as their manifest says.
- `tests/test_elements_version.py`: Drishti's bundles carry the pin, the render folder is named after it, every module's `elements:` range
  admits it, and no path into the copy names another version.
- `tests/test_vendor_licences.py`: the console's vendored folders and the engines inside the copy are on the licence allow list; Drishti
  Elements itself is the one proprietary row (the same owner and `LICENSE`).
- `tests/test_elements_guards.py`: no console script keeps a second implementation of what the libraries do (charts, grids, number
  formats, live connections). The libraries' own guards are Drishti Elements'.

### 9.9 Moving the pin

`static/vendor/VENDOR.md`, "Drishti Elements: updating the pin", lists the steps: the release tree of the new tag into
`vendor/drishti-elements/<new>/` (the old folder deleted), `drishti.elements.version`, the pinned paths of Drishti's libraries and pages,
their bundles, the render folder, the element sheets, and the guards.

### 9.10 Drishti's screens on the libraries

Each step is one branch with its gate; the screen moves, its old code is deleted in the same branch, the allow list of 11 shrinks, and
the screen's tests and screenshots are unchanged or deliberately updated.

1. **drishti-core**: formats, theme, API client, keymap, dialogs, resize; every script switches its formatter and its fetch.
2. **drishti-data**: one live connection, Arrow, the pivot source.
3. **drishti-grid, plain tables**: every server-rendered table (`drishti_tables.js` and `drishti_colfilter.js`), then tree rows and
   drills, then the renderer's tables.
4. **drishti-grid, the pivot**: the Pivot screen and the MR cube, the nested pivot panel, BI's matrix and tables, the basket's sheet, the
   playground's grid, the designer's grid.
5. **drishti-chart**: the renderer's charts, `drishti_view.js` and `drishti_charts.js`, BI's visuals, Chart any table, history charts,
   then the maps (the geo part, `drishti_chart-geohelper.js`); Vega-Lite visuals translated to the spec and Vega deleted (O1).
6. **drishti-inputs**: controls, forms, pickers, the code and formula editors (Build, Studio, the editors, BI).
7. **drishti-insight**: About and Explain, search, approvals, workbook export.
8. **The client renderer**: every panel drawn by Elements' painter (`drishti-views`) behind the same entry.
9. **drishti-risk**: the VaR explorer's and exposure explorer's charts, backtesting, the stress window, the scenario matrix, FRTB-SA,
   SIMM and the MR lens; moved into the banking plugin.
10. **The allow list empty**; the guard strict; the inventory regenerated into section 2.

#### 9.10.1 The migration checklist

One line per screen or part: what it is drawn with now, what was deleted, and how its numbers were checked. A screen is moved when its
old code is gone and its tests run against the new DOM with the same assertions.

| Screen or part | Moved onto | Status | Deleted | Numbers checked by |
|---|---|---|---|---|
| Pivot screen (`drishti_pivot.js`, `terminal/pivot.html`) | drishti-grid, `layout: 'wells'`, `clientAggregation: false`, `serverPivot` (/api/pivot/query, progressive, compare), Explain = provenance | **moved** | `drishti_pivot-grid.js` (the renderer 1.2.0 keeps the name as a loader of the grid), the field list and zones, their CSS | `test_pivot_screen_browser.py`, `test_pivot_switches_browser.py`, `test_pivot_functions_browser.py`, `test_pivot_expand_browser.py` (grand totals per column against the sample), `test_pivot_grid_fmt.py` (`measureText`), a one-off old-against-new parity on the VaR demo |
| MR cube (`MCUBE`) and CCR cube presets | the Pivot screen; the lens's sparklines and the switches' comments as `decorateCell`; live patches flashed by the grid | **moved** | the lens's and switches' writes into the old grid's cells | `test_mr_cube_browser.py` (every cell the VaR engine's scope VaR, a total never the sum of its rows) |
| Pivot functions' cards (why, what-if, functions) | drishti-inputs `DatePicker` for the two dates; the results stay plain tables (no sort, filter or paging) | **moved** | the two text date fields | `test_pivot_functions_browser.py` |
| Tree rows, the nested pivot (`drishti_tree-rows.js`) | drishti-grid pivot mode, `chrome: false`, the panel's own answer | **moved** | the call into the old grid | `test_tree_rows.py` (in Chromium), `test_render_behaviours_browser.py` |
| Tree rows, the table with children (`table.tbl-tree`) | drishti-tree's table tree (`treeTables`: the tree grid on drishti-grid's tree data, the org chart for one chain) | **moved** | the console's toggles, filter and funnels on trees, drishti-grid's `tree` and `treeVisibility` | `test_tree_rows.py`, `tests/elements/tree-panel.test.mjs`, `test_colfilter_browser.py`, `test_render_library_browser.py`, `test_render_parity_browser.py` |
| BI table, perspective grid, a report's rows and as-table views (`drishti_bi-grids.js`) | drishti-grid, `chrome: false`, conditional formats and terminal links as `decorateCell`, data bars, the engine's totals under the grid | **moved** | the table's own `<table>` and sort code, `.bi-sorthead` | `test_bi_reports_browser.py`, `test_bi_new_visuals_browser.py` (the engine's totals), `test_bi_wave_b_browser.py`, `test_bi_live_browser.py` (masks), `test_embed_report_browser.py` |
| Notebook result tables (`drishti_bi-notebook.js`) | drishti-grid | **moved** | the `<table>` | `test_bi_notebook_browser.py`, `test_bi_wave_c_browser.py` |
| Chart any table, View as table (`drishti_table-chart.js`) | drishti-grid | **moved** | the `<table>` | `test_table_chart_browser.py` (every row in the CSV) |
| BI matrix (`drishti_bi-matrix.js`) | drishti-grid pivot mode | **waits**: values on rows and the tabular layout (one column per row level) in drishti-grid first | | |
| The Pivot's chart of a selection | drishti-chart (`kind: 'bar'`, the diversification and the total beside the parts) | **moved** | its ECharts calls | `test_pivot_screen_browser.py` (the engine's gap, never one added up) |
| BI's visuals, Chart any table's chart | drishti-chart | **waits** for the chart migration (step 5) | | |
| BI date and time controls (`bi/report.html`, `notebook.html`) | drishti-inputs `DatePicker`, `DateRangePicker` | **waits** (step 6) | | |
| The pivot engine's key texts and formats (`drishti_pivot-engine.js`) | drishti-core formats | **waits** for the renderer 2.0.0 (it loads the file into host pages) | | |

The embed (the first Elements embed) loads drishti-core and drishti-grid with the console's scripts, and its view sheet carries the grid's style, so
an embedded view or report draws the same grid.

#### 9.10.2 The VaR and exposure family (step 9, 2.9.11.e): the migration checklist

Every table of the family is a `<drishti-grid>` made by drishti-risk's preset `riskGrid` (the `terminal` layout and density, the
product owner's default for VaR screens; the grid's own command line, wells, toolbar and function keys off inside a screen that has
its own; `clientAggregation: false`; VaR, ES, limits and exposures `additive: false`, never summed in the browser), every chart a
`<drishti-chart>` made by `riskChart` (drishti-risk's kinds where one fits), the figures in words by `riskMoney`, `riskSigned`,
`riskInt` and `riskPct` (one home; the texts the screens printed before, so no figure changed). What the libraries gained for it:
drishti-grid (a column's `cellClass` and `cellRenderer`, a row's `getRowClass` and `getRowData`, `fitRows`, `chrome: false`,
`pinnedTopRows` with `pinnedCellClicked`, a tree node's own row in its cell context), drishti-chart (the histogram of the server's
bins), drishti-risk (the P&L distribution from the server's bins with its fitted normal; the presets).

| Screen | Before (file, lines) | After | Tables → grids | Charts | Old code and CSS deleted |
|---|---|---|---|---|---|
| [x] VaR explorer (`VAR <scope>`) | `drishti_var-explorer.js` 1,343, `drishti_var-charts.js` 208, `drishti_var.css` 217 | explorer 1,326, charts deleted, CSS 168 | the scope tree (tree data: nodes, a book's positions and a position's trades asked when opened; the column widths kept per mode), the path, the scenario's contributors, the history, the contributions (Euler totals the server's, under the grid), the captures, a capture reopened, a comparison, the currencies | `risk.pnl-distribution` (the server's bins, VaR and ES), the worst 25 and the P&L by scenario date (bars, the VaR scenario and the tail toned, −VaR line), the history (P&L, −VaR, −ES, exceptions) | the SVG charts and their tip, the hand-built tables and the tree's rows |
| [x] Parametric VaR (`PVAR`) | `drishti_var-parametric.js` 124, `drishti_var-parametric.css` 46 | 132, CSS 35 | the tree's Parametric and Gap columns (grid columns with the ?) | the fitted normal and its marker on the P&L distribution (the server's curve) | the tree cells, the SVG curve, tails and gap bracket (the gap is in its tile) |
| [x] Basket sheet | `drishti_var-basket-sheet.js` 292, sheet CSS 22 lines | 186, 7 | one grid with cell editing: typing, Enter, Tab, Delete, undo; the paste with headings stays the sheet's; each cell toned by the server's check | — | the sheet's own table, editor, keys and windowing |
| [x] Basket what-if | two tables in `drishti_var-basket.js` (831) | grids (807) | the what-if trades, before and after | — | `vb-table` |
| [x] VaR Playground | `drishti_var-playground.js` 425, `drishti_var-playground.css` 118 | 405, CSS 111 | the workbook grid (the six header rows pinned, the label column pinned, 500 × 509 windowed), the Parametric tab's figures, positions and scenarios | — | the canvas, its windowing, keys and scroll code |
| [x] Backtesting (`VAR <scope> BACKTEST`) | `drishti_var-backtesting.js` 235 | 181 | the zone counts, the bank and its desks (a desk redraws in place), the exceptions | `risk.backtest` with the server's zone, count and multiplier on the card (`spec.light`), both exception sets marked | the SVG chart |
| [x] Regulatory screens (`VRES`, reports, statistics) | `drishti_var-reports.js` 283, `drishti_var-regulatory.css` 96 | 281, CSS 77 | the pilot pack (Generate in a cell), the report as filed (a cell opens its lineage; totals marked), the checks | — | the hand-built tables |
| [x] Stress window | `drishti_stress-window.js` 281, CSS 69 | 225, CSS 52 | the candidates, by node, current against proposed, the proposals | an area of the windows' VaR with today's line, the candidates and the selected window; a click selects | the SVG chart |
| [x] Scenario matrix (`SMAT`) | `drishti_scenario-matrix.js` 869, CSS 130 | 669, CSS 91 | the matrix (a column per date, the heat, V and E, the tail outline), each date's VaR, ES and base PV under it | line, scatter, area or bar with −VaR, base PV, the tail and the VaR scenario marked | about 400 lines of SVG and the virtualised table |
| [x] MR lens and cube panes | `drishti_mr-lens.js` 384, CSS 99 | 392, CSS 86 | the components (the server's total row), the heat map, the ladder, the what-if | `risk.pnl-distribution` of the server's bins, a waterfall to the server's total | the string tables and SVG |
| [x] Risk landing | `drishti_risk-landing.js` 254, CSS 97 | 249, CSS 94 | VaR by desk, exposure by counterparty (the limit use in the cell) | — | the hand-built tables |
| [x] Exposure explorer and counterparty screens (`EXP`) | `drishti_exposure-explorer.js` 985, `drishti_exposure-charts.js` 126, `drishti_exposure.css` 142 | 967, charts deleted, CSS 109 | all nine tables (netting sets with the server's peak PFE as their total, a row drills to its scope) | `risk.exposure-profile` (EE, PFE, the server's EEPE, the limit at the page's confidence, the peak; the uncollateralised EE and a netting set's 5/25/75/95% paths as more series) | the SVG profile and its legend |
| [x] FRTB-SA | `drishti_frtb-sa.js` 250, CSS 52 | 269, CSS 44 | class by measure, scenarios, contributions, the bucket drill, compare, the playground's sensitivities (the formula bar follows the focus) | the capital stack as horizontal bars | the hand-built tables |
| [x] SIMM | `drishti_simm.js` 242 | 241 | the netting sets, against the counterparty, the breakdown, compare, the sensitivities | — | the hand-built tables |

drishti-risk also gained `textColumn` and `moneyColumn` (the exposure, FRTB and SIMM columns), `more` series on the exposure profile, and
the shared page partials `terminal/_risk_elements.html` and `_risk_elements_css.html` load the libraries in order.

**Before and after.** `tests/screen_figures.py` reads every table or grid of a screen as rows by heading, the tiles and the playground's
cells, the same way before and after the move; `tests/test_var_family_figures_browser.py` (run only with `DRISHTI_FIGURES_OUT` or
`DRISHTI_FIGURES_BASELINE`) captures sixteen screens and tabs on the market-risk demo data and compares them with a capture of the code
before the move.

**Pickers.** Every picker of the VaR family and the exposure screens is drishti-inputs' `Select` (`<drishti-select>`, section 8.2): the
VaR explorer's reporting currency (its "More…" group), scenarios, horizon, VaR and ES confidence and hierarchy (a hierarchy skipped on the
date disabled with why), the basket sheet's saved baskets (asked when the list opens, searchable), the playground's horizon, confidences,
currency and workbook size (a refused size disabled with its reason), the stress window's hierarchy and window, the period statistics'
measure (a plain form: the hidden input), and the exposure explorer's pre-deal form (the netting set, the candidate, the method, whose
figures). `terminal/_risk_elements.html` loads drishti-inputs. FRTB-SA's and SIMM's own choice selects (side, by, compare) and the
library's own two (a form's enum of more than three, the grid's select editor) are still native.

```bash
DRISHTI_FIGURES_OUT=data/figures/before python3 -m pytest -q drishti-console/tests/test_var_family_figures_browser.py
DRISHTI_FIGURES_BASELINE=data/figures/before python3 -m pytest -q drishti-console/tests/test_var_family_figures_browser.py
```

**Open.** The exposure scope tree is still hand-built (not tree data yet); the scenario matrix's date headings lose their hover note (in the column
description instead); the screens' scripts move into the banking plugin with the module platform (section 8).

#### 9.10.3 Every designer, editor and authoring tool on the kit (the standing rule: no bespoke UI)

The survey of 2026-11-07 (grep for drags, inspectors, palettes, outlines, code and YAML editors, then each authoring screen read). The
old dataset wizard and model designer were deleted; nothing of them survives (the Datasets page's "New table" opens the
table editor, `/build/tables?new=table`; "the model designer" in two notes means the model editor).

| Screen (files) | On the kit | Still its own (moving next) |
|---|---|---|
| Build workbench (`build/drishti_*.js`) | canvas (flow `LayoutCanvas`), drags (`DragHub`), YAML tab (`YamlPane`, schema assistant), about box (`CodeEditor`), the form and controls tabs' re-ordering (`sortableList`), the inspector (`Inspector`: one live `Form` from the Rachana schema), its expressions (the `Form`'s formula widget, completing from `WB.expr.candidates`), the palette (`Palette`); undo stays the server's operation log | the data-shape tree (`drishti_build-shape-tree.js` → `Outline`) |
| BI report designer (`drishti_bi-design*.js`) | canvas (grid `LayoutCanvas`), undo (`SnapshotHistory`), settle, YAML tab (`CodeEditor` with completion), the Phone and Tablet orders (their drag `sortableList`, their model a `LayoutModel` breakpoint); the Format pane and every settings pane beside it (conditional formatting, interactions, tooltip page, page, report, theme, tile: live `Form`s, 8.2); the expression editor (`drishti_bi-expr-editor.js`: a one-line Rachana `CodeEditor`, its completion hook from the fields, measures, functions and keywords); the Visualizations (`Palette`), the wells' Add a field (`Form`); the Fields pane (drishti-tree's `TreeOutline`: folders, a date's parts, the badges, Move to the model, one branch per added dataset with its remove) | |
| Dataset designer: the table editor, virtual tables (`drishti_editor-table.js`) | the shell (`DesignerShell`), the YAML (`YamlPane`), the name, rows and look-up dialog (`Form`), every formula field (the `Form`'s formula widget) and the statement (`CodeEditor`, through `drishti_editor-formula.js`), the source and look-up rows and the columns checklist (drishti-grid editing) | |
| Model designer: the semantic model editor (`drishti_editor-model.js`) | the shell, the YAML, the Overview's name, title and words and the masks (live `Form`s), the rows of the tables, relationships, measures (the ? card, "adds up?"), hierarchies, row rules, fields and the access matrix (drishti-grid editing; formulas in the grid's `code` cell editor) | |
| Python table and model editor (`drishti_editor-python.js`) | the shell, the YAML | the code beside its contract (→ `CodeEditor` Python), the templates |
| + New steps (`drishti_editor-new.js`) | the shell | the guided steps (→ `Form`) |
| Sutra screen designer: Customize, Grid, Outline, Describe (`drishti_designer*.js`, `templates/designer/`) | the drags (`trackPointer`, `DragHub`), the Grid's formula bar (`CodeEditor` with completion), the Outline mode's tree (`Outline`), the columns checklist (`Form`'s checkboxes) | the panel handles (→ `LayoutCanvas` flow), the Grid's cells (→ `LayoutCanvas` grid), the settings and forms panels (→ `Inspector`) |
| Form designer (the workbench's Form and Controls tabs) | the cards' order by drag (`sortableList`) | their fields (→ `Inspector`) |
| BI Studio shell and theme designer (`drishti_bi-studio.js`, `drishti_bi-theme-designer.js`) | the shell (`DesignerShell`: the header and the designer's actions, the Explorer as its objects, the open items as `docs`, Problems and Query as its `dock`, the three layouts, four eyes through `propose`), the theme's settings and series colours (`Form`), the theme YAML (`YamlPane` in the shell's YAML panel, Visual / YAML / Both) | |
| BI items, workspaces, transforms (`templates/bi/item.html`, `workspace.html`, `transform.html`) | | the YAML and Python text areas (→ `CodeEditor`) |
| The Notebook's cells (`drishti_bi-notebook*.js`) | | its cells' code (→ `CodeEditor` Python) |
| Calculator, Pivot computed fields (`terminal/_calc.html`, `terminal/pivot.html`) | | their code areas (→ `CodeEditor`) |
| Admin › Connectors, Drishti*net (`templates/admin/_connector_dialogs.html`, `net.html`) | | the YAML areas (→ `YamlPane`) |
| Reader layout mode, arranging a screen, workspaces (`drishti_layout.js`, `drishti_screen-grid.js`, `drishti_workspace.js`) | | the pointer moves (→ `LayoutCanvas`, `trackPointer`) |
| Forms, record forms, subscriptions (`drishti_form.js`, `drishti_record-forms.js`, `drishti_subscribe-when.js`), the table editor's fields, BI's Format pane, the Build inspector's structured options (`build/drishti_forms.js`) | `Form` (live, embedded, steps, named fields, hooks, custom widgets, time, date-and-time, money: 8.2, "The forms on Form"); the alert form (`drishti_alert-form.js`: the server's schema from `core/alert_form.py`, embedded in its POST form) | the record forms' cells (→ drishti-grid editing) |
| Packs, lake files, lenses, editions, engines, settings (`drishti_pack-new.js`, `drishti_admin-*.js`) | a lake file's fields (drishti-grid editing); every Admin form (sixth pass below): Users, Roles, Access, Audit, Configuration, Connectors and the connector editor, Artifacts, Packs, a pack's page, Lakes and Lakes › Maintain (its row picks are drishti-grid's row selection), Drishti*net, Capacity, Health, Engines, Edits, Compute, Inbox, Caches, Collaboration, Data loads, Embedding, the uninstall dialogs, the banking module's Scopes (`Form`s from `core/admin_forms.py`, one machinery) | `drishti_pack-new.js`; `test_admin_forms_guard.py` holds `templates/admin/` at no raw `<input>`, `<select>` or `<textarea>` (allow-list empty) |
| Approvals' change views (`drishti_approvals.js`, `build/_diff.html`) | | the diffs (→ drishti-inputs' `CodeDiff`) |

**Second pass (the allow-list from 19 to 3).** On drishti-core's `trackPointer` (now with pointer capture) or the kit's `DragHub`: the
Sutra designer's Customize drags (parts and the kit's cards) and Grid drags (a cell onto another), the Outline's drags, the BI
designer's field and visual-type drags and its wells, a reader's layout mode, screen arranging, a workspace's panes (captured across
its frames) and a slider's thumbs; every HTML5 drag of these is deleted. On drishti-inputs' code areas (`<textarea data-di-code>`,
`upgradeCodeArea`: the text area keeps the value for its form and its page's script): the connector and Drishti*net YAML, the theme
designer's YAML, a BI item's and a workspace's YAML, the transform's Python and YAML, the Pivot's function code; the calculator draws its
`CodeEditor` in place. **Still on the allow-list**: the Sutra designer's Grid formula bar (its own suggestion list → `CodeEditor`
completion) and the Outline mode's tree (→ the kit's `Outline`), the workbench's data-shape tree (→ drishti-tree's `TreeOutline`). The editors' own panes in the table
above (inspectors and forms) are not drags or code areas, so the guard does not see them; they move with the forms builder's `Form`.

**Third pass (the allow-list from 3 to 1).** The Grid's formula bar is a `CodeEditor` (Rachana, `height: 'auto'`; Enter keeps, Shift+Enter
a new line, Esc goes back; its suggestions are the editor's completion hook); the Outline mode draws its tree with the kit's `Outline`
(sections and the "Drop a panel here" rows as fixed nodes, the host's `data-ol-*` attributes on the rows; a move becomes the server's
step: move, un-nest, or nest after asking where it opens). The editors' formula fields are `CodeEditor`s through `<input data-di-code>`.
The workbench's data-shape tree is on drishti-tree's `TreeOutline` (8.8): **the allow-list is empty**. The kit's `Outline` and
drishti-tree's `TreeOutline` are now one component (wip/e-admin-1: `drishti_outline.js`, 286 lines, deleted; `drishti_tree-outline.js`
292 → 526 lines with the nested nodes, the selection, the moves and the drag; the kit re-exports it; never two side by side).

**Fourth pass (the panes, wip/e-panes).** The Build workbench's inspector is the kit's `Inspector`: one live `Form` whose schema is made
from the Rachana schema (sections Required, the kind's options, Placement and heading; lists as repeatable groups; a tabs panel's body
by its kind; the structured options through `build/drishti_forms.js`'s converter); its header (the kind, About, Remove panel, Bind
field) is the host's `head`. Its expression fields are the `Form`'s new **formula** widget (`<input data-di-code="rachana">`, a
one-line `CodeEditor` with the host's completion), the hand-made completion list deleted; the palette is the kit's `Palette` over the
page's `DragHub`. The table editor's own formula widget folded into the `Form`'s. A new rule in `test_designer_kit_guard.py`: no
console script draws a label and an input in a loop over a schema's properties or fields (an inspector or form pane made by hand); its
allow-list (2: a lake's classifier and promote checkboxes, a BI transform's parameters) only shrinks and ends at 0.

**The BI designer's own panes.** The panes the guard does not see moved too: every settings pane of the report designer and
the theme designer (and, in the second BI pass, the Filters pane's editor, bookmarks, links, drill-through, the Phone and Tablet rows) is a `Form` through one helper (`drishtiBi.formatPane.form`: fields, values, a handler per top-level field; each
control keeps its `data-*` hook, so tests and tutorials find it), and the ExpressionEditor is a one-line `CodeEditor` (the editor's
completion in place of its own list). Saved reports are unchanged: a field writes only when a person changes it, and what it writes is
what the old control wrote. The second BI pass moved the drill-through target (its fields a row of checkboxes, a field to add), the links between
datasets (repeatable rows), the bookmarks (each name a field; the new one's name and slicer choices a form), the Filters pane's editor (a
submitted `Form` whose fields follow the kind of filter through `when` and a dependent condition list, every control named as before;
Pick from the data a row of checkboxes), the Phone and Tablet rows' hide, wide and height, a report measure's name and formula, the
Copilot's question and a transform step's parameters (`drishti_bi-transform.js` off the form guard's allow-list). The Visualizations are drishti-designer's `Palette` (an entry's own `attrs`, data-* only, so `data-type` stays) and the wells' Add a
field a `Form` select. **The last pass**: the Fields pane is drishti-tree's `TreeOutline` (the one tree component; its field-list
hooks in 8.8: a field a button that adds it and drags through the page's `DragHub`, the badges as marks with their `data-*`
attributes, Move to the model a link mark, a date's parts and Hierarchies closed at the start, an added dataset a branch with its remove
action); its hand-made tree, search and CSS are deleted. The BI Studio shell is the kit's `DesignerShell` (the report and the theme
designer): the header with the designer's actions (each keeps its `data-*` hook: `data-save`, `data-publish`, `data-check`,
`data-undo`, `data-redo`, `data-preview`, `data-view-as`, `data-view-link`, `data-item-link`, `data-save-copy`, the AI's
`data-ai-draft` and `data-ai-suggest`), the status beside the name, the Explorer as the shell's objects, the open items above the
designer and Problems and Query in the dock under it; studio, side by side and terminal, remembered per person in this browser; the
terminal's `check`, `save`, `publish`; a workspace that requires review proposes through the shell's Propose for review (a note
for the reviewer). Deleted: `templates/bi/_studio.html`, the designers' own headers and the Studio's Explorer, tabs and dock CSS.
**Still their own: none** of the BI designer's panes.

**Fifth pass (the rows, wip/e-panes-2).** A document's rows are drishti-grid with cell editing through the editors' one helper
(`E.rowGrid`): the model editor's tables, relationships, measures, hierarchies, row rules, fields and access matrix, the table editor's
source and look-ups and its columns checklist, and Admin › Lakes' fields with their Classifier and Promote checkboxes. drishti-grid gained
the **`code` cell editor** (drishti-inputs' one-line `CodeEditor` in the column's `codeMode`, completing through its `complete` hook; the
completion keeps its own Enter, Tab and Escape) so a formula in a row is the same editor as everywhere else. With the BI pass's transform parameters on `Form`, the hand-made
form allow-list is empty (0) and capped at 0.

The alert form is a `Form` from the server's schema (`core/alert_form.py`), embedded in the page's POST form with every control named as
before (`routes/alert_routes.body_from` unchanged): the source, condition and trigger groups show through `when` (their `data-group` and
`data-when` hooks kept), the model's measures and the filters' fields arrive through `setOptions`, and the filters are an array of objects
the page writes into the hidden `model_query` (`drishti_alert-form.js` 218 → 191 lines, `alert_form.html` 163 → 69, `core/alert_form.py` new, 168: the hand-made groups,
the filter rows and the swapped column select deleted).

**Sixth pass (the Admin forms, part 2, wip/e-admin-2).** Every Admin template but the four of part 1 draws no control of its own. A
page puts `admin_form('<name>', ...)` where its fields go (a Jinja global, `core/admin_forms.py`, new, 271 lines): the builder makes the schema
on the server from the helpers of `core/form_kit.py` (new, 128: `text`, `area`, `select`, `radios`, `check`, `checks`, `date`, `file`,
`slider`, `group`, `host`) and answers the host, `<div data-di-form data-schema="{schema, value, options}">`, which drishti-inputs makes a
`Form` (`formOf`, `upgradeForms`; 8.2). Every field keeps the old control's name, so a page's GET or POST form submits what it always
did (Access, Audit, Users' and Scopes' searches, Configuration's search, each Edits switch with its reason), and its data attributes
(`hook`), so the scripts, tests and tutorials find it; what a page posts as JSON its script reads from the `Form` (`getField`), never
from the controls. **Checkboxes**: a ticked box submits `name=on`, an unticked one nothing, as before; a JSON body says `true` or
`false` for every box (a role's seven powers, a user's *Enabled* and *Must change*, Lakes' *Bind* and *Tell*, the connector's *on*, a
deploy's breaking-change box), never leaves one out. **Security pages**: Users and Roles keep every server check (the routes are
unchanged); the dialogs' `Form`s are per page, nothing per person is shared. The lists the server answers later (a plugin's names, a
pack's kinds, the lake targets, the file's fields) arrive through `setOptions`; a field that follows a choice uses `when` over a value
of the page's state (a user being edited hides the password; a lake that exists fixes its root, domain and kind as read-only). Two forms
are built in the page from a server's description, through the same `Form`: Capacity's inputs (from the planner's list: a fieldset per
group, a list of choices a select, a number read loosely) and the connector editor's settings (from the plugin's description: a group
per section, a secret masked, a boolean or a list of values a select with the default first, the settings without a field as rows;
the server's problems on their fields through `showErrors`). Templates, lines before → after: roles 78 → 66, users 76 → 64, net 335 →
320, lakes 92 → 76, capacity 130 → 125, artifacts 117 → 106, access 52 → 46, `_connector_dialogs` 71 → 65, health 55 → 46,
configuration 89 → 84, packs 167 → 164, pack_detail 192 → 190, connectors 86 → 79, `_uninstall_dialog` 53 → 53, caches, edits,
inbox, engines, audit and scopes one line shorter or the same; scripts: `drishti_admin-connectors.js` 633 → 630 (its hand-made fields,
extra rows and datalist deleted), `drishti_capacity-page.js` 464 → 470 (its hand-made inputs and their CSS deleted),
`drishti_roles.js` 172 → 166, `drishti_admin.js` 235 → 229; the rest within a few lines.

**Sixth pass, part 1, and the fold (wip/e-admin-1 into the train).** Collaboration, Data loads, Embedding and Lakes › Maintain were
converted in parallel on a machinery of their own (a second helper module, `formFromData`, the JSON in `data-di-form`); the fold leaves
one: `core/form_kit.py`'s helpers (`number` and `time` added, `text` and `check` as part 2 had them), one registry (`admin_form` in
`core/admin_forms.py`, the four pages' builders beside part 2's), one host (`data-di-form` + `data-schema`) and one entry (`formOf`,
`upgradeForms`). A page script that needs what JSON cannot carry calls `formOf(host, {onSubmit, check})`: on a Form `upgradeForms`
already drew, those options are added to it, so the order the scripts run in does not matter. The alert form's schema
(`core/alert_form.py`) uses the same helpers and host (`alert_form_host`, found by `formOf`); `core/form_schema.py` and `formFromData`
are deleted. Admin › Collaboration: the thread
search, Only hidden comments, Active holds only, the legal hold (the scope's own fields through `when`, the reason required), the
compliance export, the chain check and a comment's hide reason. Data loads: the history's filter embedded in its GET form, Announce a
load, and the expectations with each expected kind a repeatable row. Embedding: the filter, a new application (the JWK set shown for a
key through `when`, the scopes a row of checkboxes), the secret shown once, rotate's grace period. Lakes › Maintain: Select all and the
domain, the settings, a new schedule (the days a row of checkboxes, the time picker, the cron text); the tables are a drishti-grid with
row selection (a checkbox column, *Select all* in its head), never a checkbox per row of the page's own. The guard,
`test_admin_forms_guard.py`: no Admin template writes a raw `<input>`, `<select>` or `<textarea>` (a hidden input is not a field); its
allow-list is empty and its cap 0. The Build workbench's menus (Add panel, Bind
field, File, Ship, the command palette, a dropped field's kinds) are drishti-inputs' `Listbox` under a search ranked by `searchOptions`
(`build/drishti_menus.js` 132 → 119 lines; the hand-made listbox and its CSS deleted); `Listbox` gained `followActive` (the active option
is the selected one), and `searchOptions` ranks a label that is the word first.
