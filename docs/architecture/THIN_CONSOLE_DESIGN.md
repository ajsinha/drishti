<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# The thin console: the console asks the server

**Internal. Status: S0 to S4, S6 (in part) and S7 built (branch `wip/i5-thin-console`); S5 and S8 to build.** Release 2.9.11.i5. Decided by the product owner on 2026-11-15: i5 makes the Python console
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

## 1. The problem

The console (`drishti-console`, FastAPI: 48,780 lines of Python in `core/`, `routes/` and `plugins/`, 164 templates) is mostly a
client already: most of its route files pass calls to the server as the person. But about a third of `core/` **decides** things the
server also decides, from the same files, in a second implementation. The two drift, and each drift is a wrong screen:

| What drifted (found on `wip/i4`) | The twin | What a person saw |
|---|---|---|
| `packs.extra_dirs` / `DRISHTI_PACKS_EXTRA_DIRS` kept by the Java resolver, dropped by the Python one | `core/resolution.py` against `PackResolver` | packs of an extra folder loaded by the server and unknown to the console |
| the console loaded every pack of every module that is on; the server loaded only its default list | `core/packs.py` fallback and `core/edition.py` against the server's pack start | the USEQ menu drawn for a pack that is off |
| a site connector file giving only `root` over a template naming a lake | `core/resolution.py` (`effective`) against `ConnectorLayers` | the same fix written twice, in Java and in Python |
| the settings-file reader and `${NAME:default}` placeholders | `core/config.py` (a one-level pattern) against `kernel/drishti-settingsfile` (`SettingsFile`, `Placeholders`) | a value the server reads one way and the console another |
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
| `core/config.py` (148) | layers: `application.yaml`, distribution presets, edition presets, the site's file, environment, command line, pins; `${NAME:default}` by a one-level pattern | `kernel/drishti-settingsfile` (`SettingsFile`, `Placeholders`), Spring's order | every console setting | **M** for the placeholder grammar; **H** for the preset layering (it needs the compiled distribution) | **no** |
| `core/settings_page.py` (214) | where each setting is set, "the same rule as the server's `EffectiveSettingsController`"; reads `docs/admin/CONFIGURATION.md` and the bundled YAML | `EffectiveSettingsController` | Help → Configuration | **M** | **yes** for the server's half |
| `core/config_editor.py` (136) | tiers and current values for the editor, from the document and files | the settings proposals API | Admin → Configuration | **M** | **part** |
| `core/builder.py` `Limits`, `core/controls.py`, `core/forms.py` | "the same three limits the server enforces"; the limits of `ctl.` parameters; form criteria limits | the builder, control and form settings | Build, every Sutra with controls, FORM | **M** (a number kept twice) | **part** (the Studio and Calc settings answers serve some) |

### 2.4 Server work that runs in the console's process

| Console file (lines) | What it does | Counterpart | Used by | Risk | Server API today |
|---|---|---|---|---|---|
| `core/lakes.py` (459), `routes/lake_routes.py` (247) | builds a Delta lake **on the console's machine** with `tools/lake/build.py` put on `sys.path` | the lake loader (`lake/drishti-lakeload`) | Admin → Data loads → Lakes | **M** (not a twin, the same engine imported; but it writes the server's data from the console's host and needs its disk) | **no** |
| `core/lake_maint.py` (394), `routes/lake_maint_routes.py` (137) | retention, compaction, checkpoint and vacuum with `tools/lake/maintain.py`, schedules kept by the console | none on the server | Admin → Lakes → Maintain | **M** | **no** |
| `core/packdesigner.py` (307), `core/schemakit/` (3,217) | pack generation as a subprocess of the tools, in workspaces under the console's data folder; `schemakit` is also imported by the tools | the Java auto-designer (drafting only) | Build → New pack | **M** | **part** |
| `core/ai/` (4,678) | a second AI layer: providers, registry, policy, masking, limits, an MCP layer, TLS and proxy rules, each "mirroring the server's" | `kernel/drishti-ai`, `api/tls/TlsHttp`, the connector proxy rules | the assistant, Admin → AI | **M** (policy, masking and audit exist twice; the recommended provider is already `server`) | **yes** (`POST /api/v1/ai/chat`, the MCP call) |
| `core/retry.py` (243) | "the console's twin of the server's `RetryPolicy`" | `common/RetryPolicy` | the AI and MCP calls only | **M** | goes with `core/ai` |
| `core/hostrule.py` (132) | "the rule of the server's HostAddress and `tools/hostaddr.py`": three copies | the server's host-address rule | discovered servers (`core/cluster.py`) | **M** | **yes** (the server already refuses such an address) |

### 2.5 Figures worked out in the console

The standing rule is that every risk number is computed in Java on the server. These read the server's answers as the person and
then compute:

| Console file (lines) | What it computes | Where it belongs | Risk | Server API today |
|---|---|---|---|---|
| `plugins/banking/trading_landing.py` (907) | who "my" is; the Trader desk's and Desk head's figures | the trading module's API | **M** | **no** (the inputs are served, the figures are not) |
| `plugins/banking/market_landing.py` (499) | the Market desk's tiles, curves and biggest moves; the Quality landing's checks | the market-data module's API | **M** | **no** |
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
  - {id: var, title, module: banking, here: true, cluster: true, words: [...], paths: [...], menus: [...], help: [...], tiles: [...],
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
| static files and Drishti Elements (`web/static`, `web/embed`, `core/element_*.py`, `core/render_dist.py`, `core/staticzip.py`), the vendored libraries | the controlled UI statics are served by the console |
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
| **Offline, need "where is everything"** | `tools/contracts.py`, `tools/packgen/**`, `tools/lake/build.py`, `demo.py`, `feedroute.py`, `tools/build_demo_lakes.py`, `tools/packlake.py`, `tools/feeds/**`, `command_catalog.py`, `settings_catalogue.py`, `tools/drill.sh`'s pack list | ask the **Java resolver through the jar** (`resolve --json`, 3.5) and read the JSON through one small module, `tools/resolved.py`: data classes over the document, **no rule** (no layering, no `replaces`, no on/off). The document is cached in the scratch folder under a key of the inputs' modification times, so a generator pays the JVM's start once per change, not per run |
| **Engines the server should run** | `tools/lake/maintain.py`, the pack maker behind Build → New pack | stay tools for the command line; the console reaches them only through the server's job APIs (3.7) |

Why not a shared Python library instead: a library both the console and the tools import **is** the twin, in a new folder. The
choice that removes the second implementation is to let Python read the first implementation's answer. Its cost is that an
offline tool needs the built kernel jar; the drill and the demo-lake build have it already, and `tools/javacmd.py` finds it.
A generator run on a checkout with no build says so in one line with the command that builds it (open question Q2).

`core/schemakit` moves to `tools/schemakit` (its only other user is the pack designer job). After that no tool imports
`drishti-console/`, and a guard test holds it.

## 5. The migration, step by step

Order: highest risk of a wrong screen first; each step ends with its twin **deleted** in the same merge. Sizes are estimates from
the line counts of section 2 (Python removed, Java added including tests), in builder-days for one builder.

| Step | What | Server work (Java) | Deleted in the console and tools | Size | Risk |
|---|---|---|---|---|---|
| **S0** | **Foundations and the proof.** `stamps` in about; the bootstrap builder with `distribution`, `modules`, `engines` first; the console's `ServerFacts` store (per app and server, swapped whole) fed by the watch; **the detached-console test**: the console started on a copy that holds `drishti-console/` only (no `config/`, `modules/`, `docs/contracts`, `tools/`), every browser test tagged core run against it. It fails at first and lists every remaining reader: that list is the checklist of S1 to S6 | about 600 lines | nothing yet | 3 d | the answer's shape changes later: versioned by `version` in the document, additive only |
| **S1** | **Packs.** `me/console` with `packs`; the pack `console` answer and files; `commands` in the bootstrap. The console's pack list, examples, workspaces, Help guides, facts and README come from the server | about 700 lines | `Packs._load`, `_read`, `_signature`, `fallback`; `Editions.default_packs`; `menus._pack_command_on`; every `core.resolution` import in `core/` and `routes/`; the `dir` field of the packs answer; `packs.enabled` on the console | 4 d | Help guides with pictures: the allow-list must cover relative images (a test walks every shipped guide) |
| **S2** | **Distribution, lenses, modules.** The compiled filter, the brand, `console-settings`, the lenses and each module's words, paths, statics and menus in the bootstrap | about 500 lines (the compile exists in `Distributions`; this serialises it) | `core/distribution.py` whole (398); the file half of `core/edition.py` (about 250 of 557: `Editions.load`, `mapping`, `_use_distribution`; `Edition` becomes a view over the filter's sets); `core/house.py`'s folder read; the `words`, `paths`, `statics`, `engines` of `plugin.yaml`; the shipped-beside half of `core/module_parts.py`; `distributions_dir`, `module_manifests_dir`, `lens.dir`, `modules.*` console settings; a console's own `--distribution` (open question Q3) | 5 d | a console that today runs its own distribution over a Drishti All server loses that until Q3 is decided |
| **S3** | **Engines and under-construction.** The catalogue and the registry in the bootstrap | about 150 lines | `drishti-console/config/engines.yaml`, `plugins/banking/engines.yaml`, the equality test that holds them to the server's; `core/under_construction.py`'s file read; about 100 lines of `core/engines.py` | 2 d | low |
| **S4** | **The person's decisions.** `functions`, `engines-opened`, `admin-pages`, `grants` in `me/console` | about 500 lines (the rules exist on the server for enforcement; this exposes their results) | `menus._person_opens`, `engine_opened`, `module_active`'s logic; the role literals in the 33 files (each becomes a `grants` or `admin-pages` lookup); `core/modules.py` `Me` folds into the person's answer | 5 d | a page hidden that a role may open, or the reverse: the test compares, for five demo roles, every menu item drawn with the server's own `403`/`200` for its page |
| | **Drill A** (S0 to S4): the console decides nothing about packs, modules, distributions, engines or access | | | | |
| **S5** | **Tools off the twin.** `resolve --json`; `tools/resolved.py`; the 43 files move to it | about 300 lines (a command over `ConfigResolution`, the JSON writer shared with 3.5) | `core/resolution.py` whole (1,276); `drishti-console/tests/test_resolution.py`; the Python half of `tools/test_config_readers_guard.py` (it becomes: no Python file but `tools/resolved.py` reads a pack, module, connector, bindings or rules folder); `pack-resolution/cases.yaml` and `connector-layers/cases.yaml` stop being shared fixtures and stay as the Java resolver's own tests; `schemakit` moves to `tools/` | 5 d | generators' speed (the cache) and a checkout with no jar (Q2) |
| **S6** | **Settings and limits.** `limits` in the bootstrap; Help → Configuration and Admin → Configuration ask the server | about 200 lines | the "where set" rule of `core/settings_page.py`; the console's copies of the builder, controls and form limits; `core/hostrule.py` (132) and its use in `core/cluster.py` (the server's member list is already checked by the server's rule) | 3 d | low |
| **S7** | **The AI layer.** The console calls the server only | none (exists) | `core/ai/` but the `server` provider and the thin gateway (about 4,400 of 4,678); `core/retry.py` (243); `config/ai`, `config/mcp` reading on the console; `test_ai_layer.py`, `test_ai_proxy.py`, `test_ai_tls.py`, `test_llm_providers.py`, `test_llm_contract.py`, `test_retry.py` (their server twins stay) | 2 d | a site that configured a provider only on the console must move the file to the server (the same format): said in the release notes and on Health (open question Q4) |
| **S8** | **Jobs on the server.** The lake build, lake maintenance and pack design job APIs (3.7); the landing answers of trading and market data | about 1,500 lines, the largest step | `core/lakes.py`, `core/lake_maint.py`, `core/packdesigner.py`'s subprocess half, `plugins/banking/trading_landing.py`, `market_landing.py` (about 2,600) | 8 d | it touches loading and module APIs; it may move to the release after i5 without blocking the rest (open question Q7) |
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
| **S4** | `me/console` with `engines-opened`, `grants` and the person's `modules` (folded in); the session answer's `admin`; `Identity.is_admin` is the server's decision; `menus.engine_opened` a lookup; `menus._person_opens` and `core/modules.py` `Me` deleted | `admin-pages` is not served: every Admin page opens by the server's `admin` today, so the list would say nothing more; the menus-against-enforcement test (5.2) is not written; `lens` in the person's answer stays the console's per-person setting |
| **S7** | `core/ai/` and `core/retry.py` deleted whole (the `server` provider too: `POST /api/ai/chat` passes the call to the server directly); Admin -> AI shows the server's layer, Test connection and Retry now ask the server; the console's `ai.*`, `mcp.*`, `retry.*` settings and the six Python test files deleted | the Health line naming a console provider file a site still keeps (Q4's "said on Health") |
| **S6** | `limits` in the bootstrap: `builder` (`core/builder.py` `Limits.of`), `controls` (`core/controls.py` `of`), `form` (`core/forms.py` `limits`); the console's `builder.max_*` settings and its number copies deleted. Admin -> Configuration shows the server's values as masked by the server and asks `POST /api/v1/admin/settings/secret-keys` for the console's own keys; `is_secret` deleted. `core/hostrule.py` deleted: its rule moved into `tools/hostaddr.py` (the tools'); `core/cluster.py` takes the server's member list as the server checked it; the relay's advertised address is refused only for a loopback, wildcard or link-local literal | Help -> Configuration stays the document (`docs/admin/CONFIGURATION.md`, shipped with the console); the `settings` stamp; a removed console key still set named on Health |

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
| **Recorded answers** | the console's unit tests read `bootstrap.json` and `me-console.json` samples that a Java test **writes** from the demo installation. One direction only (the server makes them, the console reads them): a sample of an API, not a fixture two implementations must both pass |
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
| **Q7** | Is S8 (lake build, lake maintenance, pack design as server jobs; landing figures in the modules) in i5 or the release after? | **Landing figures in i5** (the server-computes-risk rule, and they are module work that can run beside S1 to S4); **the three jobs in the release after**, unless the detached console must also do Admin → Lakes in i5. Until then those three pages say they need a console on the server's machine |
| **Q8** | Push of the stamps (the event stream) in i5, or the 5-second poll only? | **Both in i5**: the poll is S0, the push is a small addition in Drill B once the answers are stable. The poll alone already matches today's delay |
| **Q9** | The release numbers in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) still describe i5 as the cluster and the Spring Boot console. Where does the cluster on standard libraries go? | Keep the cluster item where the plan has it and replace only the console item with this design; move the Spring Boot console row to i7 with "decided after i5". The plan's table is edited once the owner confirms |
