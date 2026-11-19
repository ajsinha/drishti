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
# Plan: release i7b (eventing, role Homes, K5) with Rachana R0 alongside

**Scope change proposed, 2026-11-19:** the owner has directed that i7b become the two products (Elements, Rachana) and Drishti's
migration onto them; see [I7B_PRODUCTS_PLAN.md](I7B_PRODUCTS_PLAN.md). Until its decisions are taken this document stands; after,
its eventing sections (3.1, 4.2, 4.3) serve the new i7b and its role Homes, K5 and carry-over sections serve i8.

**Owner decision, 2026-11-17: all 21 recommendations in section 7 are accepted** (decision 7 on the basis that no page outside this machine pins `render/1.2.0`, `render/2.4.0` or `/embed/v1/channel`).

**Status: analysis and plan, written 2026-11-17 22:00. Nothing built, nothing run** (i7a was being released on this machine).
Read from git only: this worktree (`wip/i7b` = `wip/i7a` at `42047f059`), `drishti-elements` (`main` at `39ca755`, `design/eventing` at
`a974666`, `feature/events` = the design merge only), `drishti-rachana` (`main` at `2235a42`, `docs/plans/R0_R1_I8_PLAN.md`).
Every statement about behaviour is from reading code; an inference says **(inferred)**. Paths are relative to the repository root.
Rigour and vocabulary of `I7A_IMPACT.md`. Deleted at the i7b release with the other plan documents.

Sizes are **builder-days (bd)**, the unit of the i7a plan. Grades: **O** = Opus, **S** = Sonnet, **F** = Fable (design and review only).
At most **three Opus builders at once; a fourth builder only Sonnet**; heavy jobs one at a time.

## Contents

1. [Scope and what is out](#1-scope-and-what-is-out) · 2. [Dependency analysis](#2-dependency-analysis) · 3. [Impact by item](#3-impact-by-item) ·
4. [The safe sequence](#4-the-safe-sequence) · 5. [Risks and the guards to write first](#5-risks-and-the-guards-to-write-first) ·
6. [The efficient delivery path](#6-the-efficient-delivery-path) · 7. [Decisions for the owner](#7-decisions-for-the-owner) ·
8. [Pre-drill verification checklist](#8-pre-drill-verification-checklist-for-i7b)

---

## 1. Scope and what is out

| # | In i7b | Size | Where |
|---|---|---|---|
| 1a | **drishti-events**, the library, complete per `EVENTING_DESIGN.md` (12 steps) and released as part of Drishti Elements | 36.5 bd | `drishti-elements` |
| 1b | **Drishti on `events.v1`**: one events endpoint on the server, every live feed a topic provider, the console a pass-through, the old channel, hub, relay and per-resource streams deleted | 38 bd | this repository |
| 2 | **Lenses retired into role Homes**: design and mock-ups first (2), the Home model, the picker, Admin → role Homes, the six shipped lenses converted, every lens name gone (20.5) | 22.5 bd | this repository |
| 3 | **K5**: banking's kernel-internals allowance down by five packages (41 → 36 on today's tree, see 3.3) | 2.5 bd | `modules/banking`, `drishti-kernel-api` |
| 4 | **Rachana R0** alongside (P1, P2, H1–H8, G1), by its own plan | 31 bd | `drishti-rachana` + test-side files here |
| 5 | Carry-overs: drill lanes as systemd user scopes; `module verify` first-party by default; Download `.dmod` in Admin → Modules and its non-admin refusal test; genomics showcase padding; BI and role Homes (inside item 2) | 3 bd | `tools/`, console, server |
| – | Guards first, the eventing design, the final pin, joint snapshot approval, release prep, review fixes (allowance 3) | 9 bd | |
| | **Total** | **about 143 bd** (112 without R0) | |

**Out, on purpose.** The console on Spring Boot (the design's appendix assumed it; it stays Python in i7b). Member-to-member feeds
between cluster nodes (`NetDataController`) stay as they are. The Views painter and live patches in Elements (i8b, V1–V5). Any change to the
patch **model** (`engine/live/{Frame,Patch,PatchDiffer}`): R0 freezes it. Function grants per role and actions per module (security
release j). Renaming the distribution filter's classes (`Edition*`, see 3.2). `NetLinksTest`'s "pending under load": tested in i8 (owner);
i7b only makes sure nothing new depends on it (5, R14). Presence, history, binary frames, the frame scope (design Q7–Q9).

## 2. Dependency analysis

### 2.1 What rests on what

| Item | Needs first | Blocks |
|---|---|---|
| Library kernel (E2) | E1 (wildcards in core's `EventBus`) | everything else in the library |
| Conformance vectors (E4) | E2 | the server endpoint S1 (the vectors are its test): **S1 can start 7 bd of library work in, not 36** |
| Elements `3.0.0-rc.1` = the design's "first usable release" (E1–E6: API, connection, sequence, flow, mock) | the chain E1 → E2 → E3 → E6 (13.5 bd, all Opus but E1) | the browser switch-over C1–C5. **This is the critical chain of the release** |
| Elements `3.0.0-rc.2` (+ E7 tabs and shared hub, E9 badge) | E6 | C2b only. The shared hub must be on before release: today's `drishti_live-hub.js` already gives one connection for all tabs, and a connection per tab is a regression against `max_subscriptions` and the backend pool. Between C2 and C2b the branch runs a connection per tab; never released so |
| Elements `3.0.0` final (E8 queue, E10 inspector, E11 gallery, E12 docs) | rc.2 | only the pin swap before the review (P-final); off the critical chain |
| Server providers (S3, S4) | S1, S2 | callers C2, C3; deletion S8 |
| Console pass-through (C2) | S3, rc.1 vendored (C1) | every caller's move |
| Role Homes server model (H1) | owner's decisions 9, 10, 13, 14; **not** the mock-up choice | H2–H8 |
| Picker and Admin page (H3, H4) | **the owner's mock-up choice** (P0b) | H5, snapshots |
| K5 | nothing in i7b (K2's `Access`, `Person`, `ModuleContext.kinds()`, `audit` exist at `kernelApi=0.5`) | – |
| R0 H1–H6 | the released i7a | i8a's A1 (through G1) |
| R0 H7 (baselines), H8 (frame goldens) | i7b's eventing merged: S3 rewrites the transport half of `engine/live` | i8a |
| i8 (Views' live patches, Data Protocol live, `<rachana-view>` live, B7) | drishti-events released; the wire protocol published; the events endpoint in Drishti | – |

**What i8 depends on from i7b, exactly:** (1) Elements 3.0.0 with `drishti-events` and the protocol in `CONTRACTS.md` with its vectors;
(2) the events endpoint and the topic-provider interface on the server (B7 adds `rdp.<table>` providers); (3) `engine/live`'s patch model
byte-unchanged (guard G16) and its transport already behind a provider, so A13/A14 move a model nobody is editing; (4) Home and
navigation snapshots approved once, before B6 replaces the renderer; (5) a released i7b tag as A1's base.

### 2.2 What can front-load now (no machine while i7a finalises: text and code only, nothing compiled or run)

| Now | Why safe |
|---|---|
| **P0a** the Drishti-side eventing design (appendix A moved here and corrected: 3.1), **P0b** the role-Homes design and the mock-ups as HTML (PNG captures wait for the machine) | documents only; the owner can decide (section 7) while builders are idle |
| **E1, E2** as code in `drishti-elements` on `feature/events` (Node tests when the drill ends) | another repository; nothing in Drishti's tree |
| Guards of section 5 as code, `@Disabled`/report mode (P1) | new files only |
| The drill's scope helper as code (P2); **merged into `tools/drill.sh` only at the start of i7b**, proven on a non-release run, then frozen | the i7a drill's tool is not touched |
| Rachana F1–F9 (its plan, 8.1) | its repository only |

Must wait for the i7a tag: everything that edits this tree (base = the tag, merged forward with `git merge`, never rebased).

## 3. Impact by item

### 3.0 What the inputs get wrong against today's tree

| # | Assumed | Found | Class | Hits |
|---|---|---|---|---|
| A1 | Design appendix: "the console (moving to Spring Boot in i6) no longer multiplexes or relays" | The console is Python (`run_drishti_web.py`, `core/channel.py` 274, `core/channel_relay.py` 330, `core/embed_stream.py`). The hop must be designed: 3.1 | stop | C2 |
| A2 | Appendix A.1: ten SSE endpoints | 11 `SseEmitter` controllers in `drishti-server` (`alerts/AlertController`, `api/MonitorController`, `api/StreamController`, `bi/mq/MqController`, `bi/report/BiReportController`, `bi/runs/RunsController`, `compute/ReactiveController`, `liveset/LiveSetController`, `pivot/PivotLiveController`, `pivot/PivotScaleController`, `net/NetDataController`) **plus four in banking that stream** (`frtb/FrtbController`, `simm/SimmController`, `var/VarExplainController`, `var/VarPlaygroundController`) and console routes that stream (`api_routes`, `bi_routes`, `collab_admin_routes`, `data_routes`, `embed_routes`, `monitor_routes`, `pivot_routes`, `core/workbook_stream.py`). Each is either a **subscription** (becomes a topic) or **one answer that streams** (stays HTTP, read with `readSse`): classify all in P0a | cost | S3, S4, G4 |
| A3 | The console's relay "turns a view frame's panels into HTML … stays wherever i6 places view rendering" | It is `channel.py`'s `Render` callable (`(event, data) -> data`, panel patches as HTML from `_macros/panels.html`); `raw_view` for the client-side renderer. It must survive inside the pass-through as a per-topic transform until i8b's painter | stop | C2 |
| A4 | Elements version: the design says **3.0.0** (drishti-data loses `LiveClient`, `pipeToGrid`, `FeedMonitor`; core loses `SELECTION_EVENT`); the Rachana plan says "2.5.0 arrives with i7b, 2.6.0 with B6" | They disagree; decision 2 | stop | C1, R0 plan text |
| A5 | Removing the old names touches only the vendored copy | Drishti's own users: `web/static/elements/drishti-renderer/{drishti_renderer.js,src/drishti_embed.js,src/drishti_net.js}`, `web/static/js/drishti_server-watch.js`, `modules/genomics/console/static/js/drishti_genomics-showcase.js`, `tests/test_elements_guards.py`, `tests/test_live_hub.py`, `tests/elements/states-palette.test.mjs`; 55 files outside the vendor folder name `drishti-elements/2.4.0` | cost | C1 |
| A6 | Embed moves to `connect()` and `connection.js` is deleted | Published renderer trees are pinned by embedders: `web/static/render/1.2.0` (25 files, its own `m/net.js`) and `render/2.4.0`; both speak `/embed/v1/channel`. Deleting the channel breaks a page pinned to them | stop | C4, decision 7 |
| A7 | One endpoint replaces per-path streams | Per-path filters guard today's streams: `PlacementGuardFilter`, `EngineGateFilter`, `ModuleGateFilter`, `ApiAliasFilter`, token scopes (`TokenScopeGuardTest`), `RequestsInFlight`. One address bypasses all of them unless each provider's "may this person hear this" re-states its gate | silent | S1, G5 |
| A8 | SSE `send` reaches the stream's holder | With several console workers and several server nodes a `POST <events>/send` lands anywhere. Today `channel_relay.py` solves it for the console (signed relay, `DRISHTI_CONSOLE_RELAY_*`, port 17490+n). New rule: **the console holds no channel state**; the session id names the server node (`s-<node>-…`), the console sends to that node (`core/servers`), a node that is not the holder forwards over `net`. The relay, its six settings and its port range are deleted **(inferred design; P0a confirms)** | stop | S1, S7, C2 |
| A9 | Owner's estimate: lenses in "~45 Java + ~33 console files" | By word (lens, lenses, Lens*, `*_lens`, `DRISHTI_LENS*`), VaR-lens files set aside: **110 Java files, 69 console source files, 64 console tests, 24 under `config/` and `deploy/`, 14 under `tools/`, 126 documents**, and 219 files under `docs/lenses/mockups/`. "Open/Leave Drishti" is in 14 files | cost | H1–H9 |
| A10 | A lens is one thing | Three things carry the word: (a) the **workspace file** `config/lenses/<module>/<lens>.yaml` (menus, landings, phone rules, module blocks `trading:`, `quality:`), six shipped; (b) the **registry lens** `META-INF/drishti/lenses.yaml` (kernel: `world`, `markets`, `publicsector`; banking: `risk`, `trading`, `market`, `cro`, `mr-cube`, `ccr-cube`) with modules needed, function bundle, cards, chips, help, read into `registry/Lens`, `AccessModel`, `Role(lenses, actions)`; (c) the **VaR lens** (`banking/compute/var/VarLens`, `VarPivotLens`, `drishti_mr-lens.js`, `test_var_lens_browser.py`, the settings key `varView`): horizon, method and confidences of a VaR reading, 42 files, **not** a role lens | stop | H1, decisions 12, 13 |
| A11 | Roles grant lenses | `Role.opens/allows` and `AccessRegistry.allowed(subject, lens, action)` have **no production caller**; `lensesFor` has one (`ModuleAdminController:183`), `lensDeclared/lensOffered` one (`EditionConfiguration:43`); no `config/access/roles.yaml` ships; the console's `lens.roles: {}` is "read, not wired". The per-lens permission layer is a dormant hook: delete it, do not convert it | cost | H1, H7 |
| A12 | The server ignores the person's lens | True for filtering (`EditionContext`), but `RequestPerson` copies `X-Drishti-Lens` into `Person.lens()` (kernel API), and `TradingLanding:181`, `MarketLanding:131` read `kernel.lenses().block(c.lens(), …)`; `PersonCache` keys include the lens; recordings `tests/answers/landings/*.json` are made per lens | silent | H1, H2 |
| A13 | "Edition" is the old word for lens | `packs/edition/{Edition,Editions,EditionEnvironmentPostProcessor}` and `server/edition/{EditionBi,EditionCommands,EditionConfiguration,EditionContext}` are the **distribution's filter** today and stay. Only their lens parts go (`Editions.LENSES`, `lensNames`, `EditionContext.lenses`, `HEADER`). Stale leftovers of the removed `drishti.edition` to clean in H6: `deploy/aws/ecs/register.sh:35`, `templates/node-task.json:34`, `deploy/aws/env.example:66-67`, `deploy/docker/compose.yaml:29-30` | cost | H6, H7 |
| A14 | K5: "43 → 39" | The list holds **41** packages today (40 from sources, 1 bytecode). D2 already moved `DrishtiException` and `ErrorCode`, so `common` is now reached from 15 main sites only (`TempFiles` 4, `Branding` 9 fully-qualified in the playground and explain workbooks and SIMM, `BusinessCalendar` 2). But `server.security`, `engine.source`, `server.engines` are reached by more than the plan's classes: 3.3 | cost | K1 |
| A15 | `docs/architecture/LENSES.md` is the lens mechanism's document | It is 4,356 lines (over the 2,500-line cap), nine product chapters (Risk, Trading, Market, World, Oversight, MR cube, CCR cube, CRO, CIO) and a 70-line mechanism (section 10). The product content stays; it needs a new home | cost | H9 |
| A16 | The owner's stack file follows the repository | `deploy/stack/stack.yaml` has an uncommitted local change on `main` and line 39 sets `DRISHTI_LENSES`. H6 edits that line: tell the owner before merging to `main` | cost | H6, 8.5 |

### 3.1 Eventing

**Where the library lives (decision 1, recommended).** In `drishti-elements` as its ninth library `elements/drishti-events/`, its own
bundle, requiring only drishti-core, released with Elements; the protocol in `docs/CONTRACTS.md`, the vectors in
`tests/events/protocol-v1.json`, the runner `tools/events/conformance.mjs`. Reasons: its `page` scope **is** core's shared bus and the
wildcard matcher goes into core, so a separate repository would either copy the bus (two homes) or depend on Elements anyway; it reuses
the build tool, byte budgets, gallery, CI and the no-domain-words guard; Drishti keeps **one** pin (`drishti.elements.version`) instead
of a third. "Standalone" is held by structure and a guard, not by a repository: it imports core through one seam file, names no host
(design section 11), and can be lifted out in about a day if an adopter ever wants events without Elements. The **server** half of the
protocol is Drishti's: a new Maven module `kernel/drishti-events-server` (codec, session, subscription table with wildcards, stream
log, limits; no Drishti domain type, Spring only in its two transport adapters) with the providers in `drishti-server/.../server/events/`.

**Code, server (new and changed).**
- New `kernel/drishti-events-server`; `server/events/{EventsController (stream, send), EventsSocketHandler, TopicProvider, EventsAccess,
  EventsCluster}`; root `pom.xml` module and BOM rows; `kernel-libraries.txt` regenerated.
- Providers from the nine subscription controllers; each keeps its HTTP reads and loses its `SseEmitter` method, event names, hello and
  heartbeat comment. `engine/live/{TopicHub,ViewStream,LiveMetrics,LiveProperties}` become the view provider's internals;
  **`Frame`, `Patch`, `PatchDiffer` are not edited** (R0).
- Stays HTTP that streams: `pivot/PivotScaleController` (the progressive answer), banking's four, the console's export and workbook
  streams (to be confirmed per endpoint in P0a). `net/NetDataController` unchanged.
- `TokenScopeGuardTest` rows, `tools/test_api_docs.py` rows and `API_REFERENCE.md` for `/api/v1/events/**`; `ApiAliasFilter` rows for
  the deleted paths are deleted, not aliased. `clients/python/drishti_flight.py` is Flight, untouched; no SDK client reads SSE
  (`drishti-sdk`'s three hits are comments and a test: verify in S8).
- Settings: new `drishti.events.*` (heartbeat, limits, ring size, retained cap); `ui.live.max_subscriptions` becomes `limits.subs`;
  the relay block (`relay.across_hosts`, `advertise`, `bind`, `port`, `timeout_seconds`, `window_seconds`, `DRISHTI_CONSOLE_RELAY_KEY`)
  deleted; `CONFIGURATION.md` and the settings catalogue regenerated, never merged by hand.

**Code, console and browser.**
- New: `routes/events_routes.py` (pass-through of `stream`, `send` and the WebSocket; identity from the session; the view transform),
  `web/static/js/drishti_events-host.js` (about 100 lines: address, `share: true`, namespace, routes, the key → topic table).
- Deleted: `web/static/js/drishti_channel.js` (171), `drishti_live-hub.js` (337), `core/channel.py`, `core/channel_relay.py`,
  `core/embed_stream.py` (inferred), `web/embed/connection.js` (136), the hand-written SSE reader in `drishti_pivot-switches.js`.
- Callers moved to `events.subscribe(topic).byKind(handlers)`: `drishti_live.js`, `drishti_live-page.js`, `drishti_alerts.js`,
  `drishti_discussion.js`, `drishti_monitor.js`, `drishti_records.js`, `drishti_bi-live.js`, `drishti_bi-consume.js`,
  `drishti_bi-runs.js`, `drishti_compute.js`; routes `api_routes.py`, `live_routes.py`, `embed_routes.py`, `embed_v2_routes.py`,
  `admin_routes.py`, `core/app.py` lose their channel code.
- Vendoring: `web/static/vendor/drishti-elements/3.0.0*/`, `VENDOR.md`, `drishti.elements.version`, the 55 files that name the folder,
  `test_elements_vendored.py`, `test_elements_version.py`; renderer tree `web/static/render/3.0.0/`.

**Tests.** Java: 13 test classes name `SseEmitter` or a stream path (rewritten onto the endpoint; `BiLiveApiTest` in banking);
new `EventsConformanceTest`, `EventsGateParityTest`, `EventsIsolationTest`, `EventsClusterTest`, `StreamLogTest`. Console: about 40
files in the live area, among them `test_live_hub.py`, `test_channel_relay.py`, `test_channel_relay_hosts.py`,
`test_channel_session.py` (deleted with their subjects), `test_live_routes.py`, `test_live_results.py`, `test_live_grid_browser.py`,
`test_live_tabs_browser.py`, `test_live_toggle_browser.py`, `test_alerts_browser.py`, `test_alert_routes.py`, `test_bi_live.py`,
`test_bi_live_browser.py`, `test_mq_stream_holder.py`, `test_monitors_alerts.py`, `test_every_mnemonic_opens_live.py`, the twelve
`test_embed*.py`, helpers `live_backend.py`, `alerts_stack.py`, `bi_live_stack.py`, `wb_live.py`.
**Recordings and snapshots.** No recording under `tests/answers/` holds a stream today (verify in C6); the status badge replaces the
feed badge and the server-reachable banner on every page, so navigation snapshots change: approved once, in Z1.
**Launchers and deployment.** `deploy/docker/compose.yaml`, ECS templates, `tools/launch/{clusterctl,envsetup}.py`,
`deploy/stack/stack.yaml`: relay variables and ports out; any reverse proxy must pass `text/event-stream` unbuffered and the WebSocket
upgrade (state it in `DEPLOYMENT_AND_QA_GUIDE.md`, `CLUSTER_GUIDE.md` 9.4).
**Docs.** New `docs/architecture/EVENTING.md` (appendix A's corrected content: topics, providers, the hop, cluster; the Elements
branch then loses its appendix, design A5); `ARCHITECTURE.md` live sections, `API_GUIDE.md`, the embed guide, `TROUBLESHOOTING.md`
("why didn't I get it", the inspector), `ELEMENTS_IN_DRISHTI.md`, `TEST_HARNESS.md`, `CONFIGURATION.md`, `CHANGELOG.md`.

### 3.2 Lenses into role Homes

**The model proposed (P0b writes it out; decisions 9–14).** A **Home** is today's workspace file with the registry lens's Home facts
folded in: one file `config/homes/<module>/<home>.yaml` (title, description, `modules:` it needs, `home:` landings and template,
`menus:`, `phone:`, `cards:`, `chips:`, the module blocks). A **role names its Home**: a field on the role definition
(`identity/RoleDefinition`, both schema files, the file store), preset by the distribution (`homes: { default: …, roles: { trader:
trading, … } }`), edited in Admin → Roles. A person's Homes are their roles' Homes (an administrator: all); with more than one, a
picker sets the **start Home** (`/api/v1/me/settings` key `home`, was `lens`); with none, the distribution's default. A Home is
offered only while its modules are on, as a lens was. Nothing is hidden by a Home (unchanged rule). No ambient header: landing calls
name the Home.

**Code, server.** `registry/{Lens → Home, AccessModel, AccessRegistry, EmbeddedAccessRegistry, YamlAccessStore, Role (deleted: A11)}`;
`packs/distribution/{Distribution,Distributions}`, `packs/edition/Editions` (lens parts), `server/edition/{EditionContext,
EditionConfiguration}`, `server/console/{ConsoleDistribution (lenses → homes), ConsoleAnswers, ConsoleController}`,
`server/modules/ModuleAdminController`, `server/api/{MySettings, SettingsController, PublicAboutController, CatalogController}`,
`server/kernelapi/{KernelLenses → KernelHomes, KernelServices, RequestPerson, OperationController}`, `module/runtime/{ModuleCandidate,
DmodManifest, Claims}` (the manifest's `lenses:` key becomes `homes:`), `identity/{RoleDefinition, RoleStore, FileUserStore/Jpa…}`.
**Kernel API:** `services/Lenses → Homes`, `Person.lens() → home()`, `Kernel.lenses() → homes()`, `module/Asker`, `pivot/PivotView`
comments; `kernelApi` 0.5 → 0.6; `kernel-api-surface.txt` regenerated. Modules: `TradingLanding`, `MarketLanding`,
`MarketLandingController`, the stand-ins `TradingStandIn`, `MarketStandIn`; `modules/{banking,economy,genomics,capital-markets}/
{module.yaml,content.yaml}` (`lenses: lenses/risk.yaml`, the `copy` rows); `META-INF/drishti/lenses.yaml` × 2 deleted.
**Code, console.** `core/edition.py` (542; the lens half), `edition_gate.py`, `routes/edition_routes.py` (`/lens/<name>`,
`/lens/leave` deleted; `/home/<id>`), `core/menus.py:244` (the "Open …" links become the picker), `core/{app, backend,
request_context, user_cache, modules, module_parts, phone, facts, search, packs, plugins, engines, admin_forms}.py`,
`routes/{home, admin, module_admin, functions, help, terminal, render, api, common}_routes.py`, `config/{application.yaml (lens:),
sitemap.yaml, help.yaml}`, templates `landing.html`, `admin/{modules,white_label}.html`, `confirm_post.html`, the banking, economy,
genomics and trading plugins' landings (`home/{risk,trading,market}_landing.html` and their `drishti_*-landing.{js,css}`).
`pivot_presets.py` and `drishti_pivot.js` name the cube "lens" (a Pivot preset): decision 12.
**Config and deployment.** `config/lenses/**` (6) → `config/homes/**`; `config/distributions/*.yaml` (4: `lenses:` → `homes:`) and
`kernel.yaml`; `drishti.lenses`, `drishti.lenses-dir`, `DRISHTI_LENSES`, `DRISHTI_LENS_DIR`, the console's `lens.dir` deleted (Health
names a site's old key once, as it does for `drishti.edition`); `deploy/docker`, `deploy/aws`, `deploy/stack`; five browser sweeps set
`DRISHTI_LENSES=` in their environment.
**Tests.** Java: `EditionNoneAndLensServerTest`, `EditionTwoLensesServerTest`, `PublicSectorLensTest`, `EditionTradingServerTest`,
`DistributionFinanceServerTest`, `DistributionsTest`, `EditionsTest`, `AccessRegistryTest` (23 mentions), `SettingsTest`,
`SettingProposalsTest`, `TypedCallParityTest`, `OperationsTest`, `ConsoleAnswersRecordTest`, `ConsoleAnswersServerTest`,
`AdminModulesRecordTest`, `GenomicsDistributionServerTest`, `TradingLandingRecordTest`, `MarketLandingRecordTest`, the three
`*ModuleOffTest`. Console: `test_edition.py`, the four `test_edition*_browser.py`, `test_lens_menus.py`, `test_lens_menus_browser.py`,
`test_public_sector_browser.py`, `test_modules_off.py`, `test_market_landing.py`, `test_trading_landing.py`, `test_config_editor.py`,
`test_settings_catalogue.py`, the sweeps, `server_answers.py`, `landing_answers.py`.
**Recordings** (must change in keys only; review the diff): `tests/answers/{bootstrap-all,bootstrap-finance,bootstrap-genomics,
bootstrap-risk,admin-modules,admin-pages}.json`, `answers/landings/*.json`. **Snapshots** (185 files): Home, navigation, Admin →
Modules, White label and each landing, re-approved once in Z1. **Tutorial images:** `docs/tutorials/img/t25/19-lens-phone.jpg`,
`t38/04-lens.jpg` and any step that clicks "Open Drishti …" (targeted retakes only).
**Docs.** `LENSES.md` split into `docs/homes/<home>.md` (product chapters, each under the cap) and `docs/architecture/ROLE_HOMES.md`
(the mechanism); `docs/lenses/mockups/**` → `docs/mockups/**` (one scripted `git mv` commit with link rewrite, nothing else merged
that hour); `ACCESS_MODEL.md` (the chain loses its lens layer), `DISTRIBUTIONS.md`, `DOMAIN_MODULES.md`, `ADMIN_GUIDE.md` section 12,
`USER_GUIDE.md`, `KERNEL_AND_MODULES.md` (+ PDF), `CONFIGURATION.md`, `HOW_IT_FITS.md` (+ PDF), `README.md`, Help (`help.yaml`).
`CHANGELOG.md` and `RELEASE_NOTES.md` keep their history and get an upgrade note (old key → new).

### 3.3 K5

Reached from banking's **main** sources today (`BankingReachTest` counts main only):

| Package on the list | Reached by | Replaced by | Leaves the list |
|---|---|---|---|
| `identity` | `AuditLog` 7 files | `ModuleContext.audit` | yes |
| `server.security` | `Entitlements` 15 files (`TokenVerifier` 44, `TokenScopes`, `SecurityProperties`: tests only) | `Access` + `Person` | yes |
| `engine.source` | `SourceRouter` 11, **`SourceRegistry` 2** | `ModuleContext.kinds()`; STOP on a call `Kinds` cannot express (list it, keep the package) | if no STOP |
| `server.engines` | `BasicEngine` 1, **`EngineRegistry` 1** | `kernel.api.engines` | if `EngineRegistry`'s one use has an API form |
| `common` | `TempFiles` 4, `Branding` 9 sites in 6 files, `BusinessCalendar` 2 | `TempFiles` into `drishti-api`; the product name through `ModuleContext` (a `brand()` fact); holidays through `services/BusinessDates` | yes: 15 sites, it fits (owner's "pair with `common.*`") |

Target **41 → 36**; each STOP is one fewer and is reported, not forced. API additions go through `ApiSurfaceTest` (regenerate) in the
same `kernelApi` 0.6 step as role Homes (H2), so the baseline is rewritten once. Guard: `BankingReachTest` only (no second guard).
Proof: banking's suite, `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `PackagedVarGoldenTest`, `module verify` with zero library
warnings. Docs: `KERNEL_AND_MODULES.md` (the allowance count), `CHANGELOG.md`.

### 3.4 Rachana R0 beside i7b

R0's own plan stands (section 8.2 there). What i7b adds to it:

| Point | Rule |
|---|---|
| Builders | R0 uses the **Sonnet slot** (H1, H2a/b, H3, H4, H5, H7, H8) and an Opus slot only when one of the three is free: **P1 first** (wave 1, light machine time, never beside a drill, benchmark or the smoke), P2 after H1, H6 in wave 4 |
| Files | R0 edits test-side files of `compute/drishti-rachana`, `testing/drishti-it`, `testing/drishti-modules-it`, `packaging/pinned/**`, and the root `pom.xml` (the pin property). The root `pom.xml` is also edited by S1 (new module) and C1 (Elements version): **H1's `pom.xml` row merges first, in wave 1**; later ones are one-line merges |
| Order | H7 and H8 after S3 (the view provider) **and** after Z1; H8's frame goldens are recorded through the new transport once. H7 needs a quiet machine: it runs while the Fable review reads (8.6), nothing else running |
| Guard | G16 holds `Frame`, `Patch`, `PatchDiffer` byte-equal to the i7a tag for the whole release |
| Drill | the pinned cases jar is a committed file repository, so the drill's copies and the anywhere lane resolve it offline: proven in the pre-check, not first in the drill |
| Plan text | R0's "Elements 2.5.0 / 2.6.0" becomes 3.0.0 / 3.1.0 if decision 2 is taken (one edit in its repository) |

### 3.5 Carry-overs

| Item | Placement |
|---|---|
| Drill lanes as systemd user scopes, `CPUWeight`/`IOWeight` steerable both ways while running | **P2, first thing of i7b**, then `drill.sh` frozen. `start_job` wraps a lane in `systemd-run --user --scope --unit=drishti-drill-<run>-<lane> -p CPUWeight=… -p IOWeight=…`; `tools/drillkit/drill_steer.py` (`drishti.py drill steer <lane> --cpu 300 --io 50`) calls `systemctl --user set-property`; `kill_tree` gains `systemctl --user stop` of the scope; without a user manager or with the `cpu`/`io` controllers not delegated it falls back to today's `niced` and says so. `IOWeight` on a user scope needs the controllers delegated to the user manager (a one-time root drop-in for `user@.service`): decision 17 |
| `module verify` needs `--first-party` by default | P3: `tools/drishti.py:157` passes the `first-party.yaml` beside the packages unless told otherwise; `test_drishti_cli.py`, `TOOLS_GUIDE` |
| Download `.dmod` route missing in Admin → Modules; non-admin refusal test | P3: `ModuleAdminController` (a `GET …/admin/modules/{id}/package`, scope and API-docs rows), `routes/module_admin_routes.py`, `admin/modules.html`; `test_admin_modules.py` gains the non-admin 403 for the page and the download; recording `admin-modules.json` |
| Genomics showcase page padding | P3: `modules/genomics/console/` style; its snapshot re-approved in Z1 |
| BI and role Homes | H4: a Home may name its BI start (`menus.bi` order and `home.bi.start`: a board or report id, shown only if the person's BI powers allow it); BI visibility stays with `RoleDefinition.bi` and the distribution's `EditionBi`. Decision 16 |
| `NetLinksTest` "pending" under load | i8 (owner). i7b: `EventsClusterTest` waits on the link state it needs and never on timing; if `NetLinksTest` fails in the pre-check it is reported, not retried into green |

## 4. The safe sequence

Every increment: its change, its tests, its documents and `CHANGELOG.md`, the pre-merge gate (`tools/drill.sh --gate` plus the changed
modules' tests plus every browser file touching changed UI, whole files), merged with `git merge`. **No increment touches more than 40
files** except the three scripted ones marked †, each merged alone. Lanes are file-disjoint: **E** `drishti-elements`; **S** server
Java for eventing; **C** console and browser for eventing; **H** role Homes; **K** banking imports; **R** Rachana R0; **T** tools.
The old way keeps working until the new one is proved; the overlap inside the release is at most three increments per feature.

### 4.1 Prepare

| # | Increment | Lane | Grade | bd | After | Done means | Targeted tests · browser samples |
|---|---|---|---|---|---|---|---|
| P0a | Eventing in Drishti: `docs/architecture/EVENTING.md` (the hop, session affinity, topic table, the 15 + 8 endpoints classified, gates per provider, cluster, embed) | – | F | 1 | now | decisions 5–8 answerable from it; no "to be seen" | – |
| P0b | Role Homes: `docs/architecture/ROLE_HOMES.md` and **mock-ups, three options each**: the Home picker (A in the person menu, B a switcher in the top bar, C a first-visit chooser on Home) and Admin → role Homes (A a column on Roles, B its own page with a preview, C a matrix of roles × Homes); HTML now, PNG after the drill, under `docs/mockups/role-homes/` | – | F design, S mock-ups | 2 | now | the owner can pick (decision 11); the role → Home table per distribution is written | – |
| P1 | Base and guards first: branch from the i7a tag; guards of section 5 written, disabled or in report mode with the increment that enables each; the lens inventory recorded (G1's list) | T | S | 1.5 | i7a tag | gate green; every guard file exists | the guards themselves |
| P2 | Drill scopes (3.5), proven on a non-release run of two lanes, weights changed up and down mid-run; then `drill.sh` frozen | T | S (O reads the diff) | 1.5 | i7a drill ended | 8.7's dry-run boxes ticked | `test_drill_lanes.py`, `test_drill_plan.py`, new `test_drill_scopes.py` |
| P3 | Carry-overs: verify default, Download `.dmod`, refusal test, genomics padding | S, C | S | 1.5 | P1 | a non-admin gets 403 on page and file; an admin downloads the same bytes as `modules/<id>.dmod` | `test_admin_modules.py`, `test_drishti_cli.py`, `AdminModulesRecordTest` · `test_admin_modules_browser.py`, `test_genomics_module.py` |

### 4.2 The library (drishti-elements; steps of the design, section 10.1)

| # | Increment | Grade | bd | After | Done means |
|---|---|---|---|---|---|
| E1 | Core: segment wildcards; selection and filter channels as topics (chart, graph, tree, chip, grid) | S | 1.5 | now | every existing Elements test green; `SELECTION_EVENT` gone |
| E2 | Kernel: envelope, topics, `Events`, routes, page scope, request and reply, streams, kinds, the clock | O | 4 | E1 | Node tests on the test clock |
| E3 | Connection: state machine, three transports, codec and `raw` mode, auth, `LiveClient` deleted | O | 5 | E2 | state machine tests; the address guard |
| E4 | Mock server, fault injection, `MockTabs`, **conformance vectors and runner** | S | 3 | E2 | the mock passes every vector; `conformance.mjs --url` runs |
| E5 | Flow: conflation, frames, buffers, the grid sink | O | 3 | E2 | memory flat under the burst test |
| E6 | Sequence: order, duplicates, gaps, replay, resume, retained, the feed monitor | O | 3 | E3 | property tests with the mock's faults |
| – | **tag `v3.0.0-rc.1`** (E1–E6) | | | | Drishti vendors it (C1) |
| E7 | Tabs: the `tabs` scope, leader tab, shared hub and its worker file | O | 4 | E6 | three-tab browser test; fallbacks |
| E9 | Metrics, recorder, trace, status badge | S | 3 | E5, E6 | badge announces states; "why" trace |
| – | **tag `v3.0.0-rc.2`** (+ E7, E9) | | | | Drishti pins it (C2b) |
| E8 | The offline queue (memory, IndexedDB) | S | 2 | E3 | reload test |
| E10 | The inspector | S | 3 | E9 | keyboard and names |
| E11 | Gallery's Eventing section, browser tests, the throughput target | S | 3 | E5, E4, E10 | section 9.4's first two targets met |
| E12 | Docs, `.d.ts`, budgets, one-home and no-host-words guards; design file deleted; **tag `v3.0.0`** | S | 2 | all | CI green on the tag |

### 4.3 Drishti on events.v1

| # | Increment | Lane | Grade | bd | After | Done means | Targeted tests · browser samples |
|---|---|---|---|---|---|---|---|
| S1 | `drishti-events-server`: codec, session (node in the id), subscriptions, heartbeat, auth and re-check, limits; SSE `stream` and `send`; `EventsAccess` with a gate row per provider kind; scope and API-docs rows | S | O | 4 | E4, P0a | the library's vectors pass against a started server (G3 on) | `EventsConformanceTest`, `TokenScopeGuardTest`, `test_api_docs.py` · none |
| S2 | The stream log: `seq`, `epoch`, bounded replay ring, retained by key; budgets | S | O | 2 | S1 | replay and resync vectors; memory bound proved | `StreamLogTest`; `drill-budgets.txt` rows |
| C1 | Vendor Elements `3.0.0-rc.1`; A5's users moved off the removed names; one folder, one version | C | S | 1.5 | rc.1 | no page loads two Elements versions; old live path still works on the old scripts | `test_elements_vendored.py`, `test_elements_version.py`, `test_elements_guards.py` · `test_elements_renderer_browser.py`, `test_live_grid_browser.py` |
| S3 | Providers: views (`TopicHub`, `ViewStream` behind it) and alerts; old endpoints still answer | S | O | 2 | S2 | G5 rows for both equal the old endpoints' refusals | `EventsGateParityTest`, `EventsIsolationTest`, the view and alert API tests |
| C2 | The console pass-through with the view transform; `drishti_events-host.js`; views, alerts and discussion on topics; `drishti_channel.js`, `drishti_live-hub.js`, `channel.py`, `channel_relay.py` deleted | C | O | 4 | S3, C1 | a view ticks and an alert arrives in two tabs, across two console workers with no relay | `test_live_routes.py`, `test_alert_routes.py`, new `test_events_passthrough.py` · `test_live_tabs_browser.py`, `test_alerts_browser.py`, `test_live_toggle_browser.py` |
| C2b | Pin `3.0.0-rc.2`: the shared hub on (`share: true`), the status badge in the page frame in place of the feed badge and the `drishti_server-watch.js` probe | C | S | 1 | rc.2, C2 | two tabs, one connection (the endpoint's session count) | `test_elements_vendored.py` · `test_live_tabs_browser.py`, `test_page_walk_browser.py` |
| S4a | Providers: monitors, live result sets, live pivots (conflation by key) | S | O | 2 | S3 | G5 rows; `byKind` shapes equal today's | their API tests |
| S4b | Providers: BI report live, BI queues, BI runs, compute requests | S | S | 2 | S3 | as S4a | `BiLiveApiTest` and the bi, compute tests |
| C3 | Callers: monitors, records and live pages, pivot live, BI live, consume, runs, compute (two halves, file-disjoint) | C | S | 3 | S4a, S4b, C2 | no `Drishti.Channel` left (G13) | `test_live_results.py`, `test_bi_live.py`, `test_mq_stream_holder.py`, `test_monitors_alerts.py` · `test_bi_live_browser.py`, `test_live_grid_browser.py`, `test_every_mnemonic_opens_live.py` |
| S5 | The WebSocket transport on the same codec; pass-through upgrade in the console | S, C | S (O reads) | 2 | S1, C2 | the same vectors over WebSocket; close codes | `EventsConformanceTest` (ws) · one live page forced to ws |
| C4 | Embed and renderer on `connect()`; `render/3.0.0`; `connection.js` and (decision 7) the old trees deleted | C | S | 2.5 | C2 | an embedded view ticks with a renewed token | the twelve `test_embed*.py` |
| S6 + C5 | Polling to push: cluster members, data-out streams, capacity, module state, approvals; the server-reachable banner follows `$events.state` | S, C | S | 3 | S3, C2 | the timers are gone from those pages | their admin tests · `test_page_walk_browser.py` |
| S7 | Cluster: a topic owned elsewhere is opened there and forwarded; `send` to a non-holder forwarded; restart gives a new epoch and a resync | S | O | 2 | S4a | two members: subscribe on A, produced on B; B restarts | `EventsClusterTest` |
| S8 † | Delete: the nine `SseEmitter` methods, their paths, alias rows, relay settings; Java tests rewritten; G4 and G13 to fail mode; settings and API documents regenerated | S | S | 2 | C2b, C3, C4, C5 | G4: no stream outside the endpoint and the allow-list | whole `drishti-server` test module; `test_settings_catalogue.py` |
| C6 | Console tests: deleted subjects' tests removed, the rest on the new helpers (`live_backend.py` speaks the protocol); two halves | C | S | 3 | S8 | no skipped live test; the skip count equals i7a's | the live-area files, whole |
| C7 | Documents of 3.1 | – | S | 2 | S8 | docs tests green | `gate_docs` |

### 4.4 Role Homes and K5

| # | Increment | Lane | Grade | bd | After | Done means | Targeted tests · browser samples |
|---|---|---|---|---|---|---|---|
| H1 | Server model: `Home` in the registry (workspace file + registry lens folded), role → Home on the role definition (both schema files), distribution `homes:`, the person's start Home in settings; **the console's answers keep today's `lenses` shape, derived from Homes** | H | O | 3 | decisions 9, 10, 13, 14 | every lens test still green on the derived shape; G12 on | `AccessRegistryTest`, `DistributionsTest`, `EditionsTest`, `SettingsTest`, `IdentityStoreContract` |
| H2 + K1 | Kernel API 0.6 in one step: `Homes`, `Person.home()`; landings and stand-ins; module manifests `homes:`; **K5's API additions and banking's imports** (3.3) | H, K | S (O for the API), K1 S | 2 + 2.5 | H1 | surface baseline regenerated once; landings' recordings differ in the key only; allowance 36 (or 36 + STOPs, listed) | `ApiSurfaceTest`, `TypedCallParityTest`, `TradingLandingRecordTest`, `MarketLandingRecordTest`, `BankingReachTest`, banking's suite, the three VaR golden tests · `test_var_explorer_browser.py` |
| H3 | Admin → role Homes: API and page per the chosen mock-up; four-eyes as other role edits; non-admin refused | H | S | 2 | H1, decision 11 | a role's Home changes for its people at the next request | new `RoleHomesApiTest`, `test_admin_role_homes.py` · `test_admin_role_homes_browser.py` |
| H4 | Console core: Homes for a person, the picker per the chosen mock-up, menus arranged by Home, `/home/<id>`, the start Home; `/lens/*` and "Open/Leave Drishti …" gone; BI start (decision 16) | H | O | 3 | H1, decision 11 | one role: no picker; two roles: picker, choice kept; an administrator: all | `test_edition.py` (renamed `test_homes.py`), `test_lens_menus.py` (renamed) · new `test_homes_browser.py`, `test_edition_browser.py` |
| H5 | Console plugins and the modules' console parts (banking, trading, market-data, economy, capital-markets, genomics landings) | H | S | 2 | H4 | each shipped Home draws its landings | `test_market_landing.py`, `test_trading_landing.py` · the four `test_edition_*_browser.py` (renamed), `test_public_sector_browser.py` |
| H6 | Convert the shipped: six files to `config/homes/`, four distributions, `publicsector` as a Home, `content.yaml` rows, deployment files (A13, A16) | H | S | 1.5 | H2, H5 | G12 green for every distribution; `drishti.py build modules` packs them | `DistributionFinanceServerTest`, `GenomicsDistributionServerTest`, `test_modulepack.py`, `test_modules_fresh.py` |
| H7 † | Delete: `Lens`, `Role`, the derived `lenses` answers, `lenses.yaml` × 2, the settings and variables, the header; G1 to fail mode; settings, API and surface documents regenerated | H | S | 2 | H6 | G1: the word is gone outside its allow-list | `drishti-registry`, `drishti-server` test modules whole |
| H8 | Tests and recordings: Java and console tests renamed and rewritten (two halves), `bootstrap-*.json`, `admin-modules.json`, `admin-pages.json`, `landings/*.json` re-recorded and the diff read | H | S | 2 | H7 | recordings differ in names only | `ConsoleAnswersRecordTest`, `AdminModulesRecordTest`, `test_python_runtime_answers.py`, the sweeps |
| H9a † | `LENSES.md` split into `docs/homes/*.md` + `ROLE_HOMES.md`; `docs/lenses/mockups` → `docs/mockups` (scripted move and link rewrite) | – | S | 1.5 | H7 | no file over the cap; docs link test green | `gate_docs` |
| H9b | Guides, Help, tutorials' targeted images, `HOW_IT_FITS`, PDFs | – | S | 1.5 | H9a | no "lens" in a guide (G1 covers `docs/`) | `gate_docs`; targeted shots |

### 4.5 Close

| # | Increment | Grade | bd | After | Done means |
|---|---|---|---|---|---|
| P-final | Pin Elements `3.0.0` (folder swap); `render/3.0.0` rebuilt | S | 0.5 | E12, C6 | `test_elements_vendored.py` |
| Z1 | **One** snapshot approval for both features (Home, navigation, admin pages, landings, the badge), the page walk, page-load figures into `BENCHMARK.md` | S | 1.5 | H8, C6, P-final | every changed golden explained in the commit message |
| R: H7, H8 | Rachana baselines and goldens (its plan) | S | 5 | Z1 | – |
| Z2 | Freeze; **version bump to 2.9.11.i_7b** (`tools/version.py`, `test_one_version.py`); README, RELEASE_NOTES, the deck's catch-up, plan documents listed for deletion | S | 1.5 | all merged | section 8.6 |
| Z3 | Fable review → fixes (allowance 3 bd) → pre-check → smoke → the drill | F, then by finding | 3 | Z2 | section 8 |

### 4.6 Waves (at most three Opus and one Sonnet at once)

| Wave | Opus 1 | Opus 2 | Opus 3 | Sonnet |
|---|---|---|---|---|
| 0 (now, text and code only) | (F) P0a | (F) P0b | – | E1; P1 and P2 as code; mock-up HTML; Rachana F-waves |
| 1 | E2 | H1 | R: P1 | P2 → P3 → R: H1 (its `pom.xml` row first) |
| 2 | E3 | E5 | S1 (after E4: until then H4, if the mock-up is chosen) | E4 → H2 + K1 |
| 3 | E6 → tag rc.1 | S2 → S3 | H4 | H3 → H5 → R: H2a |
| 4 | E7 | C2 | S4a → S7 | C1 (first) → E9 → H6 → S4b → R: H2b, H3 |
| 5 | R: H6 | R: P2 | (free: reads S8's diff) | tag rc.2 → C2b → C3 → C4 → S5 → S6 + C5 → H7 → H8 |
| 6 | – | – | – | E8, E10, E11, E12 (Elements; they start earlier whenever the Sonnet slot is free) → S8 → C6 → C7 → H9a → H9b → R: H4, H5 |
| 7 | – | – | – | P-final → Z1 → R: H7, H8 → Z2 → Z3 |

A wave ends when its Opus items merge; the Sonnet column is a queue and runs on. Role Homes (H1–H9) never waits for the library.

## 5. Risks and the guards to write first

All written in **P1** (disabled, with the increment that enables them) unless noted.

| # | Risk (what breaks silently) | Guard |
|---|---|---|
| R1 | **A gate lost in the move to one endpoint** (A7): a module switched off, an engine off, a placement rule, a token scope or a kind entitlement no longer stops a stream | **G5** `EventsGateParityTest`: per provider, the old endpoint's refusals are recorded before S3/S4 and the topic must refuse the same people for the same reason; kept after S8 as fixed expectations |
| R2 | **One person hears another's topic** (shared hub, namespace, the pass-through reusing an upstream, `alerts.me`) | **G6** `EventsIsolationTest` (two people on shared threads, as `TypedCallParityTest`); `test_no_shared_user_state.py` row for `events_routes.py`; a browser test with two sign-ins in one profile |
| R3 | **The server drifts from the published protocol** | **G3** `EventsConformanceTest` reads the vectors from the vendored Elements folder, over SSE and WebSocket; a vector failing fails the build |
| R4 | **A second transport left behind** (one way) | **G4** `test_one_transport.py`: outside the vendored Elements no `EventSource`, `WebSocket`, `BroadcastChannel`, `SharedWorker`; in Java `SseEmitter` only in `server/events` and a written allow-list of streaming answers; **G13** `test_one_way_i7b.py` (deleted at release): each old name absent from the increment that says so |
| R5 | **`send` reaches the wrong node or worker** (A8) | `EventsClusterTest` case; `test_events_passthrough.py` with two console workers and no relay |
| R6 | **Memory**: a replay ring and retained values per stream on every node | **G7** bounds in `StreamLogTest`; `tools/drill-budgets.txt` rows; ring and retained caps are settings with defaults sized in P0a |
| R7 | **The patch model changes under R0** | **G16** `test_patch_model_frozen.py`: the blob hashes of `Frame`, `Patch`, `PatchDiffer` equal the i7a tag's (deleted at release) |
| R8 | **Pinned embeds break** (A6) | decision 7; if old trees are kept one release, a browser test loads `render/2.4.0` against the new server |
| R9 | **Lens words survive, or the VaR lens is deleted by a sweep** (A10) | **G1** `tools/test_no_lens_words.py`: report mode with the recorded file list (it may only shrink), fail mode in H7; a written allow-list (the VaR lens's 42 files and its settings key, `CHANGELOG.md`, `RELEASE_NOTES.md` history); it also refuses "Open Drishti", "Leave Drishti", `/lens/`, `X-Drishti-Lens`, `DRISHTI_LENS`, `drishti.lenses` |
| R10 | **A role with no Home, a Home whose module is off or not in the distribution, a default that does not exist** | **G12** `HomeForEveryRoleTest` over every distribution file and every shipped role; the console falls to the distribution's default and says so on Admin → role Homes |
| R11 | **Recordings and snapshots churn or are approved blind** | recordings re-recorded in H8 only, snapshots in Z1 only; both commits list each changed file with its reason; `test_no_cache_paths_recorded.py` extended: no session id, epoch or time in a recording |
| R12 | **Kernel API baseline merged by hand** (H2, K1, R0) | `ApiSurfaceTest`; one increment rewrites it; rule: regenerate, never merge |
| R13 | **K5 changes a VaR number** (another `TempFiles` root, another calendar) | the three golden tests in K1's pre-merge gate, not only in the drill; `PackagedVarGoldenTest` |
| R14 | **Timing-dependent cluster tests under steered CPU weights** | new cluster tests wait on states; the dry run of P2 includes the java21 lane at a low weight; `NetLinksTest` reported, not retried |
| R15 | **Drill scopes leak processes or hide a failure** (a lane's exit code through `systemd-run`, a scope left running) | `test_drill_scopes.py`; `drill_cleanup` stops every `drishti-drill-<run>-*` scope; the dry run kills a lane on purpose |
| R16 | **Skips counted as passes** in the rewritten live tests | the drill's skip count against i7a's (i7a R15); C6's done-means |
| R17 | **Old settings silently ignored** (`drishti.lenses`, the relay keys, `ui.live.max_subscriptions`) | Health names each old key a site still sets; `SettingsTest` case; upgrade table in RELEASE_NOTES |
| R18 | **Stale module packages** after `config/lenses` moves (the i7a R2 guard reads `content.yaml` copies) | `test_modules_fresh.py` stays green through H6; H6 rebuilds the packages in its gate |
| R19 | **Data and licence**: the restricted IMF samples, writes under `data/` | existing guards, named in 8.5; no i7b increment may touch `content.yaml`'s `restricted:` rows |

## 6. The efficient delivery path

**Pace.** i7a's plan was about 55 bd; its 136 commits landed between 11-16 20:00 and 11-17 20:00 with three builders and
front-loaded branches. At that observed pace the critical chain decides, not the total:

| Day (from the i7a tag) | Reached | Critical item |
|---|---|---|
| 0 (tonight) | P0a, P0b written; the owner's decisions 1–14; E1 | the mock-up choice |
| 1 | P1–P3; E1, E2, E4; H1, H2 + K1; R0's P1 running, H1 | E2 → E3 |
| 2 | E3, E5, E6, **rc.1**; S1, S2, S3; H3, H4, H5 | E6; S1 |
| 3 | C1, C2; E7, E9, **rc.2**; S4, S7; H6–H8 | C2 |
| 4 | C2b, C3–C5, S5, S6, S8, C6, C7; E8, E10–E12, **3.0.0**; H9 | S8, C6 |
| 5 | P-final, Z1; R0's H7, H8; Z2; Fable review; fixes | the review |
| 6 | pre-check, smoke on the installed stack, **the one drill**; tag `v2.9.11.i_7b` | the drill |

About **six working days**, plus or minus two: the chain E2 → E3 → E6 → C1 → C2 → C3 → S8 → C6 is 25.5 bd of serial work and cannot be
bought with builders. Role Homes, K5 and the carry-overs are done by day 3 and wait for eventing.

**What keeps it to one drill.** Guards first; a gate log per merge; fixes verified by whole files; the drill tool changed once, on
day 1, and frozen; recordings and snapshots each approved once; generated files regenerated, never merged; the pre-check runs every
lane's own command on the changed set, and the smoke runs on a real install before the drill is allowed to start.

**Cut lines, in the order they are taken** (each decided at a named moment, not during the drill):

| # | If | Then |
|---|---|---|
| CL1 | E11 (gallery, throughput) or E10 (inspector) is not done by day 4 | Drishti ships on **rc.2, everything a Drishti page loads**; Elements 3.0.0 follows without a Drishti release (the inspector and gallery are not loaded by a Drishti page). Not taken if the owner wants "complete or nothing" (decision 4) |
| CL2 | S5 (WebSocket) is late | SSE only in i7b; WebSocket in i8 (the library has it either way) |
| CL3 | S6 + C5 (polling to push) is late | the five timers stay one release; listed in RELEASE_NOTES |
| CL4 | **rc.1 is not vendored by the end of day 3, or rc.2 by the end of day 4** | **the Drishti switch-over (S3 onward, C1–C7) moves to i7c**; i7b releases role Homes, K5, the carry-overs and R0's H1–H6; S1 and S2 stay on a branch, unmerged (no unused endpoint in a release); R0's H7, H8 move with it. Costs one more drill; decision 3 |
| CL5 | K1 meets a STOP | that package stays; the count is reported |

## 7. Decisions for the owner

| # | Decision | Recommendation |
|---|---|---|
| 1 | Where `drishti-events` lives | **In `drishti-elements`, the ninth library**, its own bundle, core only, one seam file, the no-host guard; the server half a Maven module `kernel/drishti-events-server` here. Not its own repository now (3.1); liftable later |
| 2 | Its version | **Elements 3.0.0** (public names are removed, no aliases); Views then ships as 3.1.0; the Rachana plan's 2.5.0/2.6.0 are corrected |
| 3 | One release or two | **One release, one drill**, with CL4 agreed today as the automatic split point |
| 4 | The whole library before Drishti releases | **Yes as the plan** (all twelve steps by day 4); CL1 allowed as the fallback |
| 5 | The console hop | **A pass-through in the Python console** with the view-to-HTML transform until i8b's painter; **no console channel state, the relay and its settings deleted**; session ids name the server node |
| 6 | SSE first, WebSocket in the same release (design A2) | Yes; CL2 as the fallback |
| 7 | Delete the per-resource streams and `/embed/v1/channel` (design A1), and the published `render/1.2.0` and `render/2.4.0` trees | **Yes, if no page outside this machine pins them** (owner to confirm); otherwise keep the two trees and the embed channel for one release with R8's test |
| 8 | Member-to-member feeds unchanged (design A3) | Yes |
| 9 | The Home model | **Named Homes that roles point to** (many roles, one Home; the six lens files convert one to one; a site's own roles can use shipped Homes), not one file per role |
| 10 | Where a role's Home is kept | **On the role definition** (identity store, both schema files, no migration), preset by the distribution, edited in Admin; a site file is not needed |
| 11 | The mock-ups (after P0b) | pick one picker (A, B, C) and one Admin page (A, B, C); P0b will name its own recommendation |
| 12 | The **VaR lens** and the cube "lens" presets (42 files; `VarLens`, `test_var_lens_browser.py`, `drishti_mr-lens.js`) | **Keep the name in i7b** behind G1's allow-list: it is a VaR reading, not a role lens, and sits in golden-critical code. Rename ("VaR view") in a later release if the word must go everywhere |
| 13 | The registry lenses with no workspace file (`publicsector`, `cro`, `mr-cube`, `ccr-cube`), the lenses' function bundles, and `Role(lenses, actions)` | `publicsector` **becomes a Home**; `cro`, `mr-cube`, `ccr-cube` are dropped as entries (their designs stay in `docs/homes/`); **bundles and the dormant per-lens role rules are deleted** (A11), and release j designs grants per function and module afresh |
| 14 | No ambient header: a landing call names its Home | Yes |
| 15 | K5's target | **41 → 36** including `common` (15 sites), each STOP reported |
| 16 | BI and role Homes | A Home may name a BI start and order the BI menu; visibility stays with role powers. In H4 if it is under half a day, else i8 |
| 17 | Drill scopes need `cpu` and `io` delegated to the user manager: one root command, once | **Yes** (the owner runs it; the command is printed by `drishti.py drill doctor`); without it the drill falls back to `nice` and says so |
| 18 | `LENSES.md` (4,356 lines) and `docs/lenses/mockups/` | split into `docs/homes/<home>.md`; the mock-ups move to `docs/mockups/`; the "lens mock-ups in docs" rule keeps its meaning under the new folder |
| 19 | R0 beside i7b: P1's light machine week (Rachana N13), the Sonnet slot for the harvest, H7 in the review window | Yes |
| 20 | `NetLinksTest` | i8, as decided; i7b reports it if it fails and never retries it into green |
| 21 | The owner's `deploy/stack/stack.yaml` (local change; `DRISHTI_LENSES` on line 39) | the owner commits or stashes it before H6 reaches `main`; the line is removed by the release |

## 8. Pre-drill verification checklist for i7b

Tick every box, top to bottom. A box that cannot be ticked stops the drill. "Whole file" always: never one test of a file.
(new) = written by the increment named in section 4.

### 8.1 Eventing: the library and the switch-over

- [ ] `drishti-elements` tag `v3.0.0` (or the agreed rc, CL1) exists; its CI is green on the tag; `node --test tests/elements/events*.test.mjs` green.
- [ ] The vendored folder is that tag: `test_elements_vendored.py`, `test_elements_version.py`, `test_elements_guards.py`; one folder under `web/static/vendor/drishti-elements/`; `drishti.elements.version` and `VENDOR.md` say the same.
- [ ] `EventsConformanceTest` (new) green over SSE and over WebSocket; the vectors are read from the vendored folder.
- [ ] `EventsGateParityTest` (new): one row per provider (views, alerts, monitors, live sets, live pivots, BI report, BI queues, BI runs, compute, the admin topics); module off, engine off, placement, scope, kind.
- [ ] `EventsIsolationTest`, `StreamLogTest`, `EventsClusterTest` (new) green; `TokenScopeGuardTest`, `tools/test_api_docs.py` green with the events rows.
- [ ] `test_one_transport.py` and `test_one_way_i7b.py` (new) in fail mode and green: no `drishti_channel.js`, `drishti_live-hub.js`, `channel.py`, `channel_relay.py`, `connection.js`; `SseEmitter` only where the allow-list says.
- [ ] `test_patch_model_frozen.py` (new) green.
- [ ] Console files, whole: `test_events_passthrough.py` (new), `test_live_routes.py`, `test_live_results.py`, `test_alert_routes.py`, `test_bi_live.py`, `test_mq_stream_holder.py`, `test_monitors_alerts.py`, the twelve `test_embed*.py`, `test_no_shared_user_state.py`.
- [ ] Browser samples, whole: `test_live_grid_browser.py`, `test_live_tabs_browser.py`, `test_live_toggle_browser.py`, `test_alerts_browser.py`, `test_bi_live_browser.py`, `test_every_mnemonic_opens_live.py`, `test_embed_elements_browser.py`, `test_embed_report_browser.py`.
- [ ] The skip count of the live-area files equals i7a's.
- [ ] Settings: `CONFIGURATION.md` regenerated; no relay key, no `max_subscriptions`; Health names an old key when set (`SettingsTest`).

### 8.2 Role Homes and lens removal

- [ ] `tools/test_no_lens_words.py` (new) in fail mode and green; its allow-list holds only the VaR lens files and the two history files.
- [ ] `git grep -n -E "Open Drishti|Leave Drishti|/lens/|X-Drishti-Lens|DRISHTI_LENS|drishti\.lenses"` prints nothing outside `CHANGELOG.md` and `RELEASE_NOTES.md`.
- [ ] `HomeForEveryRoleTest` (new) green for `drishti.all`, `drishti.finance`, `drishti.genomics`, `drishti.risk`.
- [ ] Java, whole modules: `drishti-registry`, `drishti-packs`, `drishti-identity` (both stores), `drishti-kernel-api` (`ApiSurfaceTest`, `kernelApi=0.6`).
- [ ] `testing/drishti-modules-it` whole (`ConsoleAnswersRecordTest`, `AdminModulesRecordTest`, `TypedCallParityTest`, `DistributionsTest`, `GenomicsDistributionServerTest`, `RoleHomesApiTest` (new)).
- [ ] Recordings: `bootstrap-all/finance/genomics/risk.json`, `admin-modules.json`, `admin-pages.json`, `answers/landings/*.json` re-recorded once; the diff shows renamed keys only.
- [ ] Console, whole: `test_homes.py`, the menus test, `test_admin_role_homes.py` (new), `test_market_landing.py`, `test_trading_landing.py`, `test_modules_off.py`, `test_config_editor.py`, `test_settings_catalogue.py`, `test_python_runtime_answers.py`.
- [ ] Browser, whole: `test_homes_browser.py` (new), `test_admin_role_homes_browser.py` (new), the four Home landing files, `test_public_sector_browser.py`, `test_page_walk_browser.py`, `test_command_sweep_browser.py`, `test_api_sweep_browser.py`.
- [ ] Snapshots: the four `test_snapshots_*_browser.py` green on goldens approved **once** in Z1; the approval commit lists each changed golden.
- [ ] `docs/architecture/LENSES.md` and `docs/lenses/` do not exist; no document over 2,500 lines; docs link tests green.

### 8.3 K5

- [ ] `modules/banking/java/kernel-internals.txt` holds 36 packages (or 36 plus the STOPs listed in `CHANGELOG.md`); `BankingReachTest` green.
- [ ] Banking's suite whole; `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, `PackagedVarGoldenTest` exact.
- [ ] `python tools/drishti.py module verify` on banking: first-party by default, zero library warnings.

### 8.4 Rachana R0 in this tree

- [ ] `RachanaPinGuardTest` green; the cases jar resolves from `packaging/pinned/` with the network off.
- [ ] The conformance runner green in `compute/drishti-rachana` (every `el` case `verified: true`); the harvested tests run the cases and keep no second assertion.
- [ ] `RachanaCoreReachTest` and "no second copy" in report mode, their lists committed.
- [ ] The language definition and schema stale check, the `functions.yaml` coverage gate, the grammar recogniser test green.
- [ ] `ViewModelGoldenTest` green three times in a row with identical bytes; baselines written to `BENCHMARK.md` with machine and commit.
- [ ] `JS_PROOF_RESULT.md` exists in `drishti-rachana` (P1, P2); G1's written go or no-go is in the review.

### 8.5 Carry-overs, data and licence

- [ ] `test_admin_modules.py`: a non-admin gets 403 for the page and for the download; an admin's download equals the file in `modules/`.
- [ ] The genomics showcase snapshot approved with the padding fix.
- [ ] IMF: the packaged economy module carries none of the five restricted samples (the content-module test and the packaged-economy test); `content.yaml`'s `restricted:` rows unchanged against the i7a tag.
- [ ] `git status --porcelain --ignored -- data/ config/` empty after the pre-check (the drill's `data_unchanged` and `config_unchanged` run by hand); the IMF-reading tests use their internal fixture.
- [ ] No hidden files, licence headers, `test_asset_names.py`, `test_assets_policy.py` (no CDN) green.
- [ ] The owner's `deploy/stack/stack.yaml` change is committed or stashed (decision 21).

### 8.6 Version, review, pre-check

- [ ] Code freeze: every lane merged into `wip/i7b`; no builder branch unmerged; worktrees of merged branches removed.
- [ ] **Version bumped to 2.9.11.i_7b before the review** (`tools/version.py`, `test_one_version.py` green; footer, About, `RELEASE_NOTES.md`, README rewritten from the file system).
- [ ] Fable pre-drill review written (`docs/plans/I7B_PREDRILL_REVIEW.md`: findings by likelihood × cost, each with lane, where, why, fix, grade), including R0's G1.
- [ ] Every finding fixed or accepted in writing; each fix verified by its whole files plus the gate.
- [ ] Scripted pre-check green on the frozen commit: `tools/drill.sh --gate`; then `--lanes tools-a,tools-b`; then `mvn clean verify` of `testing/drishti-modules-it`; then every browser file changed since `v2.9.11.i_7a`. One heavy job at a time.
- [ ] `test_one_way_i7b.py`, `test_patch_model_frozen.py` and the plan documents are listed for deletion in the release commit.

### 8.7 Drill tooling and the machine

- [ ] `cat /sys/fs/cgroup/user.slice/user-$UID.slice/user@$UID.service/cgroup.controllers` lists `cpu` and `io`; if not, decision 17's command was run.
- [ ] Dry run on a non-release commit (`--lanes tools-a,java21 --no-push`): each lane is its own `drishti-drill-<run>-<lane>.scope`; `drishti.py drill steer java21 --cpu 50` and back to `--cpu 300` show in `systemctl --user show`; a lane killed on purpose fails the run with its name; no scope is left after `drill_cleanup`.
- [ ] `test_drill_scopes.py`, `test_drill_lanes.py`, `test_drill_plan.py` green; `tools/drill.sh` unchanged since the dry run (`git log -1 -- tools/drill.sh` is P2's commit).
- [ ] Machine: the stack is down; no server whose working folder is a checkout (`servers_here`); no builder JVM, no Maven, no Chrome left over; more than 40 GB available; disk free above the guard; stale `/tmp` and scratch folders pruned; R0's P1 builds and benchmarks are not running.

### 8.8 The real install (smoke, before the drill)

`python tools/drishti.py build all` → an owner-style stack from the built tree (`drishti-stack`: drishti-main plus two members, the
console on another port), LAN addresses, never loopback.

- [ ] All three nodes healthy; Admin → Cluster shows three members; every module loaded from its `.dmod`; Health names no old key.
- [ ] **Events across nodes:** a view opened through node A ticks from data produced on node B; an alert raised on B reaches a person signed in through A; the status badge says Live; node B restarted: the badge says Reconnecting, then Live with the missed count, with no page reload.
- [ ] Two tabs of one person share one connection (the inspector or the endpoint's session count); two people in two browsers do not see each other's alerts.
- [ ] A monitor, a live result set (`/live/trade`), a live pivot, a BI live report and a compute request each update; an embedded view with a token ticks and survives a token renewal.
- [ ] **Role Homes:** for each shipped role of each shipped Home (risk, trading, market, markets, world, research, public sector) a person lands on the right Home with its menu order; a person with two roles sees the picker, picks, signs out and in, and starts there; a person with no mapped role gets the distribution's default.
- [ ] No "Open Drishti …" or "Leave Drishti …" anywhere (the page walk's text search); `/lens/risk` answers 404.
- [ ] Admin → role Homes changes a role's Home and the person sees it at the next request; a non-admin is refused; Admin → Modules lists and downloads; White label shows Homes per distribution.
- [ ] **VaR golden on the installed stack: 167,372,692 (1-day 99.5%) and ES 210,131,972**, by API and on the VaR page; `drishti.py server smoke` green.
- [ ] The stack is stopped and its temporary files removed before the drill starts.

### 8.9 The drill

- [ ] Every box above is ticked on the **same commit**; the freeze holds; builders code-only.
- [ ] One all-lanes drill, started in the background, watched by its log's modification time; a second full drill only if the first needed a fix.
- [ ] Green: push `develop`, merge `main`, tag `v2.9.11.i_7b`; plan documents deleted; the deck's chapters refreshed; R0's base for A1 is this tag.

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
