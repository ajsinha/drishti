<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Architecture

*Revision 1.1 · 2026-09-30 · Author: Ashutosh Sinha*

> **Any data. Any domain. One grammar.**
> Drishti (दृष्टि, "sight") turns any record from any system into a dense,
> keyboard-driven, live terminal view — without a hand-built screen per product.

**New to Drishti?** [Section 5](#5-walk-through-one-request) follows one command from the keyboard to a live screen, and ends with [where to change what](#where-to-change-what). [Section 7a](#7a-how-a-screen-is-made-generic-not-bespoke) shows, with real outputs, how every screen is drawn by the same generic code.

**The compute side** is at the end: [the pivot engine](#23-the-pivot-engine) (section 23), [compute workers](#24-compute-workers-python-and-java-libraries-approved-pinned-and-isolated)
for Python and Java libraries (section 24) and [the compute engines' switches](#25-compute-engines-on-or-off-each-with-its-own-configuration-file)
(section 25). The data layer and loading are [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md).

---

## 1. Problem and intent

Front-office, risk and operations staff look at hundreds of entity kinds: interest
rate swaps, FX swaps, listed futures, netting sets, CSAs, counterparties, curves.
Each one traditionally gets a bespoke screen, written once and rotting forever.
The data changes shape faster than screens can be rebuilt.

Drishti inverts this. A screen is **not code** — it is the result of applying a
layout written in the declarative screen grammar **Rachana** (a **Sutra**) to a data document, with an **inference
engine** filling whatever the grammar does not say. A brand-new product type with
no Sutra at all still renders a usable view on day one; a Sutra makes it beautiful.

The four reference mockups (`docs/requirements/drishti-*.png`) define the target. Their entities and Sutras ship in
the `finance` pack (`examples/packs/finance/`), so they open only on a server that enables it (`DRISHTI_PACKS_EXTRA_DIRS=examples/packs DRISHTI_PACKS=finance`;
it is a retired example pack):

| Mockup | Command | Sutra | Source |
|---|---|---|---|
| Interest rate swap | `TRD IRS-48213 <GO>` | `irs-vanilla v3` + inference | `aero-risk`, live |
| FX swap | `TRD FXS-20931 <GO>` | `fx-swap v2` + inference | `aero-fx`, live |
| Commodity future | `TRD CFT-77120 <GO>` | `listed-future v1` + inference | feed file `eod-futures` |
| Netting set | `NSET NS-NORTH-01 <GO>` | `netting-set v1` | `aero-risk` exposure |

### 1.1 Goals
1. **G1 One grammar** — every view in the product is a Sutra (explicit or inferred).
2. **G2 Any source** — data arrives through a plugin SPI (Delta Lake, JDBC, Aerospike, REST, files, S3, Kafka,
   ActiveMQ, RabbitMQ, public data feeds).
3. **G3 Linked** — every identifier that names another entity is navigable (`Linked entities`, breadcrumbs, `Alt+←`).
4. **G4 Live** — views tick; p99 end-to-end view latency is measured and shown in the top bar.
5. **G5 Provenance** — every view shows *how it was built*: Sutra + version, data fingerprint, source + generation.
6. **G6 Keyboard first** — command line (`<MNEMONIC> <ID> <GO>`), function keys per view, `F9 Raw JSON` always.

### 1.2 Non-goals (v1)
- Trade capture / booking (Drishti is read-only).
- Pricing or risk calculation on the server — values (MTM, DV01, PFE) come from sources. A user may compute on a view in the browser with Calc ([USER_GUIDE.md](../guides/USER_GUIDE.md#calc-python-on-any-view)); a server-side QuantLib service is proposed, not built ([AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed)).
- A free-form page designer. Screens stay Sutras; the Build workbench and the Sutra Studio edit them with a preview, and the BI report designer lays out report pages ([BI_DESIGN.md](BI_DESIGN.md#16-rupaka-the-original-design-and-every-phase)).

---

## 2. System context

```
                ┌────────────────────────── Users (browser) ──────────────────────────┐
                │  Landing · Terminal · Views · Workspaces · Monitors · Studio · Help │
                └───────────────────────────────┬─────────────────────────────────────┘
                                                │ HTTP(S): HTML pages + ONE SSE channel per browser
                                   ┌────────────▼────────────┐
                                   │  drishti console        │  Python · FastAPI · Jinja2
                                   │  (port 17480)           │  vendored Bootstrap/ECharts
                                   └────────────┬────────────┘
                                                │ REST + SSE under /api/v1 (JSON ViewModel, frames of patches; Arrow on request, 16.1)
                                                │ Authorization: Bearer <HS256 token minted per call>
                                   ┌────────────▼────────────┐
                                   │  drishti-server         │  Java 21+ · Spring Boot 3.5
                                   │  (port 18480)           │  virtual threads
                                   │  ┌───────────────────┐  │
                                   │  │ drishti-engine    │  │  fetch → match → fingerprint
                                   │  │  rachana · infer ·│  │  → layout → links → bind → ViewModel
                                   │  │  graph · TopicHub │  │
                                   │  └─────────┬─────────┘  │
                                   └────────────┼────────────┘
                     SourcePlugin SPI (ServiceLoader; jars in plugin-dir get their own class loader)
     ┌──────────┬──────────┬──────────┬─────────┴┬──────────┬──────────┬──────────┬──────────┐
     ▼          ▼          ▼          ▼          ▼          ▼          ▼          ▼          ▼
   demo      file / s3    delta      jdbc     aerospike   rest     kafka     activemq /   feeds
 (pack      (JSON/CSV   (Delta     (Postgres  (records   (HTTP     (topics,  rabbitmq     (NY Fed,
 samples,   folders,    Lake, by   or any     per date)   JSON)    live)     (queues,     ECB, UST,
 ticking)   dated)      bus. date) driver)                                   live)        FRED)
```

Two things sit beside this picture. With **Drishti\*net** switched on (section 13a), several such servers gossip with each
other, each with its own sources, and any of them answers any request; the console is given one server and learns the
others. And every Python run (Transforms, python visuals, Python-driven tables, Notebook cells) is the server's, on its compute pool `python`
in a strict sandbox ([section 24.19](#2419-the-python-runtime-for-code-people-write)); the console runs none.

**Deployment model — a departure from Pravaha.** The Drishti backend is always a
**Spring Boot application**; it is never shipped as an embeddable library. The Maven
modules are internal building blocks of that one application, each contributing its
beans through a module `@Configuration` class and `@ConfigurationProperties` records.
Domain classes (grammar, inference, pipeline) remain plain, constructor-injected Java
objects so they unit-test without a Spring context, but Spring is a first-class,
permitted dependency everywhere. The UI is a separate **Python console** laid out the
way MAYA's web layer is.

---

## 3. Core concepts

| Concept | Meaning |
|---|---|
| **Entity** | Any addressable record: `(kind, id)` → e.g. `(trade, IRS-48213)`, `(netting-set, NS-NORTH-01)`. |
| **Mnemonic** | Command-line alias for a kind: `TRD`, `NSET`, `CPTY`, `CSA`, `AGR`, `CRV`, `BOOK`. Declared by packs (`mnemonics:` in `pack.yaml`); sites add more under `drishti.commands.mnemonics`. |
| **DataNode** | Immutable, source-neutral tree (object / array / scalar) holding the entity document. |
| **Source** | A plugin (`SourcePlugin`) that can `fetch`, and optionally `subscribe`, `search` and `reverse`; every document carries its *generation* (monotonic version). A **connector** is a named instance of a plugin with its own settings (`market-store` is a `delta` connector); it is a site resource kept as one file, `config/connectors/market-store.yaml`, that the registry watches and applies live, one connector at a time (see [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). |
| **Fingerprint** | Stable hash of a document's **shape** (keys + types, not values or array lengths), shown as e.g. `b7df…1372`. Keys the layout cache. |
| **Rachana** | The declarative screen grammar (रचना, *composition*): so no product ever gets its own coded screen. |
| **Sutra** | One layout written in Rachana, versioned (`irs-vanilla v3`): header strip, panels, bindings, formats, keys, links. |
| **Inference** | Rule engine that derives panels from shape when a Sutra is absent or partial ("Sutra X + inference"). |
| **Layout** | Resolved, data-free plan = Sutra ⊕ inferred panels. Cached per `(Sutra version, kind, fingerprint)`. |
| **ViewModel** | Layout bound to values: what the console renders. Serialisable JSON. |
| **Patch** | Minimal change to a ViewModel: one strip cell (`op: strip`), one whole panel (`op: panel`) or the provenance. Patches travel in **frames** over SSE when the source ticks. |
| **Link** | A reference from a field to another entity, resolved through the entity graph. |

---

## 4. The view pipeline

Every view, live or not, is built by `ViewPipeline` (`drishti-engine`) in the same stages. The stages work on
immutable inputs, so they are safe to run concurrently and their results cache well.

```
 "TRD MX-20000001 <GO>"
        │
 ① Command    CommandParser + Mnemonics → EntityRef(kind=trade, id=MX-20000001)               ~µs
 ② Fetch      SourceRouter → EntityDocument (DataNode + Provenance)                       I/O, virtual thread,
                                                                                          drishti.sources.fetch-timeout (10s)
 ③ Match      SutraMatcher → highest-priority Sutra of the kind whose `where` holds (or none)   ~µs
 ④ Shape      ShapeFingerprinter → Fingerprint, cached per (entity, generation, date)     ~µs–ms
 ⑤ Layout     LayoutMerger: cache[(Sutra version, kind, fingerprint)] else Sutra ⊕ inference   cold ms, warm µs
 ⑥ Links      ReferenceCatalog.discover + chart sources → SourceRouter.fetchAll            I/O in parallel,
                                                                                          drishti.graph.link-budget (40ms)
 ⑦ Bind       Binder: every panel bound in parallel on the bind pool; strip and title     ~ms
        │
   ViewModel {ref, mnemonic, title, strip, panels, keys, provenance, timings}
        └─► REST response  /  the first `view` event of a live stream
```

The ViewModel's every field, panel kind and live patch: [VIEWMODEL.md](../reference/VIEWMODEL.md).

Notes on each stage, checked against the code:

- **Fetch order.** `SourceRouter` tries the plugin routed for the kind (`drishti.sources.routes`), then
  `default-route`, then every other plugin that serves the kind. For **Live**, plugins that stream come first (and a
  real stream before the `demo` samples); for a **picked business date**, dated plugins come first. The first one
  that holds the entity answers. A dated plugin that holds the date (`SourcePlugin.coverage` is `HELD`) is
  authoritative for it: an entity it does not list is not held and the plugins behind it are not asked (searches list
  the kind only up to it). Nobody serving the kind is `DRS-1002`; nobody holding it is `DRS-1001`; a source error is
  `DRS-1003`; running out of time is `DRS-1004`; a *known at* read of a dated plugin without time travel
  (`SourcePlugin.timeTravel`) that may hold the date is `DRS-1007`.
- **Links before binding.** Linked entities (counterparty, netting set, the curve behind a chart) are fetched in
  parallel within `link-budget`. Those that miss it are marked `pending` in the `Linked entities` panel; a later
  rebuild (the next tick, or a reload) fills them in.
- **Failures stay local.** An expression that fails in the strip shows a dash; a failing panel binding shows an
  inline error for that panel only. The view still renders.
- **Timings.** Each ViewModel reports how long it took, per stage, in milliseconds
  (`"timings": {"fetch": 0.11, "layout": 0.14, "links": 0.15, "bind": 0.26, "total": 0.66}`).

---

## 5. Walk through one request

This follows a single command from the keyboard to the screen and then through one live tick. The outputs are
real, captured from a running server with the `trading` pack enabled (security off, so no token is needed).

**1. The user types `TRD MX-20000001` and presses Enter.** While typing, `drishti_command.js` asks the console for
suggestions (debounced 60 ms, stale requests cancelled); the console forwards to the server:

```bash
curl -s 'http://localhost:18480/api/v1/command/suggest?q=TRD%20T-1000&limit=2'
```

```json
[{"type":"entity","mnemonic":"TRD","kind":"trade","id":"MX-20000001","title":"MX-20000001",
  "subtitle":"Trade · Interest rate swap (fixed/float) · Meridian Reinsurance Ltd · AUD 242m","complete":"TRD MX-20000001"}, …]
```

**2. The console resolves the command.** The form goes to the console's `GET /go?q=TRD MX-20000001`. The console
calls the server's `POST /api/v1/command` with `{"text": "TRD MX-20000001"}`, receives `{"ref": {"kind": "trade",
"id": "MX-20000001"}, "mnemonic": "TRD"}`, and redirects the browser to `/v/trade/MX-20000001`.

**3. The console renders the first paint.** For `/v/trade/MX-20000001` the console calls
`GET /api/v1/views/trade/MX-20000001`. Each call carries `X-Drishti-User`, `Authorization: Bearer <token>` (when sign-in
is on; the console mints a fresh HS256 token for every call) and `X-Drishti-As-Of` when a business date is picked.
On the server, `TokenFilter` turns the token into a `Principal`, `Entitlements` checks the role and pack may open
`trade`, `AsOfResolver` resolves the business date, and `ViewPipeline.view` runs the stages of section 4:

```bash
curl -s http://localhost:18480/api/v1/views/trade/MX-20000001 | python3 -m json.tool | head -40
```

Abbreviated:

```json
{
  "ref": {"kind": "trade", "id": "MX-20000001"},
  "mnemonic": "TRD",
  "title": {"pill": "Rates · Interest rate swap (fixed/float)", "id": "MX-20000001",
            "with": {"text": "Meridian Reinsurance Ltd",
                     "link": {"kind": "counterparty", "id": "CP-MERIDIAN-RE", "mnemonic": "CPTY"}}},
  "strip": [{"label": "Notional", "text": "AUD 242,000,000", "path": "$.currency"},
            {"label": "Direction", "text": "Receive fixed", "path": "$.direction"}, …],
  "panels": [{"id": "terms", "kind": "kv", "title": "Terms", "key": "F2", "area": "main", "inferred": false,
              "data": {"fields": [{"label": "Fixed rate", "text": "4.0829%", "path": "$.terms.fixedRate"}, …]}},
             {"id": "legs", "kind": "tabs", …}, {"id": "schedule", "kind": "ladder", …},
             {"id": "built", "kind": "provenance", …}, {"id": "marketData", "kind": "line", …},
             {"id": "sensitivities", "kind": "hbar", …}, {"id": "pnl", "kind": "line", …},
             {"id": "refs", "kind": "links",
              "data": {"links": [{"label": "Netting set", "text": "NS-MERIDIAN-RE-NY",
                                  "link": {"kind": "netting-set", "id": "NS-MERIDIAN-RE-NY", "mnemonic": "NSET"},
                                  "badge": "PFE 46.0m", "status": "resolved"}, …]}}],
  "keys": [{"key": "F2", "label": "Terms", "action": "panel", "panel": "terms"}, …],
  "provenance": {"layout": "Sutra irs-fixfloat v1 + inference", "fingerprint": "b7df…1372",
                 "source": "murex-rates", "generation": 1701, "live": true, "businessDate": null, …},
  "timings": {"fetch": 0.11, "layout": 0.14, "links": 0.15, "bind": 0.26, "total": 0.66}
}
```

Reading it: the router found the trade in the `demo` plugin (whose sample's `_meta` names the source system
`murex-rates`); `SutraMatcher` chose `irs-fixfloat v1` from `config/packs/trading/sutras/murex/`; inference added what the
Sutra did not say ("+ inference"); the counterparty, netting set, book and trader were fetched as links and given
badges from the pack's `badges:` expressions. The console renders the panels with the Jinja macros in
`web/templates/_macros/panels.html`, main-area panels on the left and `area: right` panels on the right.

**4. The page goes live.** The page does not open its own stream. `drishti_live.js` subscribes `view:trade/MX-20000001` on the
browser's single channel (`drishti_channel.js` → the hub in `drishti_live-hub.js`, a SharedWorker shared by every tab → console
`GET /api/channel?s=view:trade/MX-20000001&s=alerts`). The console opens
the server stream for that subscription and relays its events, each wrapped with its subscription
(`{"ch": "view:trade/MX-20000001", "d": …}`). Directly against the server:

```bash
curl -sN http://localhost:18480/api/v1/views/trade/MX-20000001/stream
```

```
event:view
id:0
data:{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD","title":{…},"strip":[…],"panels":[…],…}

event:frame
id:1
data:{"seq":1,"generation":1710,"patches":[{"op":"strip","index":4,"cell":{"label":"MTM (USD)","text":"+1,625,979","tone":"pos","emphasis":true,"path":"$.mtm"}},{"op":"panel","panel":{"id":"built","kind":"provenance",…}}]}
```

Press Ctrl+C to stop. Every stream starts with the full view (`view`), then sends `frame` events.

**5. A tick arrives.** The demo plugin moves the trade's MTM and bumps the generation. `TopicHub` (one topic per
entity, one source subscription however many people watch) puts the new document in a latest-wins slot and
delivers it at most once per 50 ms frame. The `ViewStream` for this client rebuilds the view (layout from cache,
so only binding runs), `PatchDiffer` compares it with what the client holds, and the result goes into the client's
one-slot `FrameMailbox`. The client's writer thread sends it as the `frame` above: only the MTM strip cell (index 4)
and the provenance panel changed, so only they travel.

**6. The browser applies it.** The console turns non-chart panel patches into ready HTML (same macro as first
paint) before relaying them; `drishti_live.js` replaces the strip cell's text and tone, swaps the panel, moves chart data in
place, and flashes what changed. The top bar's `Live, p99 N ms` comes from `GET /api/v1/health/live`:

```bash
curl -s http://localhost:18480/api/v1/health/live
```

```json
{"streams":1,"topics":2,"frames":11684,"p50Ms":1.304,"p99Ms":1.761}
```

### Where to change what

Each row names what to change for a common need and the document that explains it.

| I want to... | Change | Read |
|---|---|---|
| Read a new store (a database, a topic, a folder, a service) | A connector: a file `config/connectors/<name>.yaml` with `plugin:` and `settings:` (or Admin → Connectors); the pack names it in `routes:` for the kinds it serves, and may carry a `connectors:` template for it | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md), [CONFIGURATION.md](../reference/CONFIGURATION.md) |
| Add a domain (new kinds, commands, links) | A pack folder `config/packs/<name>/` with `pack.yaml`, `sutras/`, `samples/`; enable it with `DRISHTI_PACKS` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which) |
| Change how one screen looks | Its Sutra (`*.sutra.yaml`), or a Design in the Build workbench, proposed and approved | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |
| Change how a figure is formatted | `fmt:` in the Sutra; named formats in the pack's `config/formats.yaml` | RACHANA_REFERENCE.md |
| Change how inference reads field names | The pack's `config/semantics.yaml` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra) |
| Add a panel kind | The engine (`Binder` and the panel model) and a console renderer (`_macros/panels.html`) | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one), [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-viii-changing-drishtis-code-the-developer-guide) |
| Decide who may see what | `roles:` in `pack.yaml` (kinds, `raw`), `drishti.security.redact` (fields shown as `•••`), the Admin pages | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Add a command word | `mnemonics:` in `pack.yaml` (or under `drishti.commands` for a site) | PACK_DEVELOPER_GUIDE.md |
| Add a link between kinds | `graph.fields` in `pack.yaml` and `link(...)` in the Sutra | PACK_DEVELOPER_GUIDE.md |
| Change the sentence the About drawer says about a kind | `kinds.<kind>.about` in the pack's `config/about.yaml` (a `${...}` template over the document); for a generated pack, the about source in `tools/packgen/` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#about-text-and-glossary) |
| Explain a field (term, meaning, unit, sign) | `glossary.<field path>` in `about.yaml`, or a shared `vocabulary.<name>` entry in the lowest pack that owns the concept (the glossary layer and field hints are step 4 of [RACHANA_REFERENCE.md › About this page: the design of context help](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help); section 3.9 says what is in today) | PACK_DEVELOPER_GUIDE.md |
| Change how the drawer looks or behaves | `drishti-console/web/templates/_macros/about.html` and `terminal/_about.html`, `static/js/drishti_about.js`, `static/css/drishti_about.css` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help) |
| Change what the explanation says about the layout and the data | `ExplainService`, `PageContext`, `EmptinessReason` (`drishti-engine`, package `explain`), `MatchTrace` (`SutraMatcher.explain`); the endpoint is `ExplainController`; limits under `drishti.explain.*` | [API_GUIDE.md](../guides/API_GUIDE.md), [CONFIGURATION.md](../reference/CONFIGURATION.md) |
| Decide who may share, comment or be mentioned | the role power `roles.<role>.collaborate` (on unless removed); `drishti.collab.packs.<pack>.share-enabled`; `mentionable-roles`, `directory.scope`, `share.max-recipients`, `limits.*` under `drishti.collab` | [CONFIGURATION.md](../reference/CONFIGURATION.md), [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Change what a note or comment may contain, or what is masked in it | `drishti.security.redact` (the fields), `drishti.collab.text.on-masked-copy` and `deny-patterns`; the logic is `NoteText`, `Entitlements.maskedValues`, `CommentRenderer` | CONFIGURATION.md |
| Switch on email, or change what it says | `spring.mail.*`, `drishti.collab.console-url`, `email.enabled`; per pack `packs.<pack>.email.content` (`comment`, `title`, `link-only`); templates in `drishti.collab.email.templates-dir`; the code is `EmailNotifier`, `OutboxDispatcher`, `MailRenderer` | [OPERATIONS.md](../admin/OPERATIONS.md#9a-2-collaboration-email-and-the-outbox) |
| Add another channel (a chat bridge) | one more `Notifier` bean: `ShareService` and `ThreadService` hand every bean the audience, so neither service changes | [COLLABORATION.md](../architecture/COLLABORATION.md) (phase 2) |
| Keep, hold or export what people wrote | `drishti.collab.retention.*` and `packs.<pack>.retention-days`; legal holds and the export through `/api/v1/admin/collab` (`CollabPurge`, `HoldService`, `ExportService`, `ChainVerifier`) | [OPERATIONS.md](../admin/OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export) |
| Change how the Share dialog, the inbox or the Discussion tab look or behave | `drishti-console/web/templates/terminal/_share.html`, `inbox.html`, `_discussion.html`; `static/js/drishti_share.js`, `drishti_inbox.js`, `drishti_alerts.js`, `discussion*.js`; `static/css/drishti_collab.css` | [COLLABORATION.md](../architecture/COLLABORATION.md) (console design) |
| Let people search a kind by filling in a form | the `form:` section of `pack.yaml` (fields, types, operators), or a Design's Form tab; people open it with `FORM <pack>` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind), [USER_GUIDE.md](../guides/USER_GUIDE.md) |
| Let people choose what a panel shows | `controls:` in the Sutra (dropdowns, sliders, date ranges), read by panels as `ctl.<id>` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#controls) |
| Tell Drishti a batch of data landed (refresh, verify, tell people, flag late data) | `POST /api/v1/packs/{pack}/loads` from your ETL; what a pack expects is its `loads:` section | [DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) |
| Reach a source over TLS or through a proxy | `tls.*` and `proxy:` in the connector file | [TLS.md](../connectors/TLS.md), [TLS.md (proxy)](../connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Make a dataset, report or board (business intelligence) | YAML files in `config/bi/` (watched) or the BI pages (workspaces, designer, Model view); the query runs on the server over DuckDB with the reader's masks and row rules | [BI_GUIDE.md](../guides/BI_GUIDE.md), [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), [BI_DESIGN.md](BI_DESIGN.md#16-rupaka-the-original-design-and-every-phase) |
| Show a view inside another web application | register the application in Admin → Embedding; the page uses `<drishti-view>` | [CLIENT_RENDERER.md](CLIENT_RENDERER.md#20-drishti-elements-the-design) |
| Use a language model, or an MCP tool server | a provider file in `config/ai/` or `config/mcp/` and `drishti.ai.enabled` / `drishti.mcp.enabled` (both off by default) | [AI.md](../admin/AI.md#1-model-providers), [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) |
| Run several servers as one (Drishti\*net) | the files of `config/net/` (`node.yaml` with `enabled: true` and the cluster key, a seed in `peers/`), or Admin → Net → Files; members advertise a real address, never a loopback one | [OPERATIONS.md](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one), [tutorial 19](../tutorials/19-a-cluster-of-three.md) |
| Lay a report out on paper (a statement, one per recipient) | `layout: statement` on a report page and the report's `statement:`; BI Studio's Paper tab | [STATEMENTS.md](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader) |
| Make something that failed (a pack, a connector, a provider) try again | nothing: it is retried with backoff; Retry now in Admin | [OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself) |

---

## 6. Rachana — the screen grammar — and Sutras written in it

Rachana is the grammar; each Sutra is one YAML document written in it (ADR-017, which supersedes ADR-011's
Markdown Sutras). The first key, `rachana: 1`, is the language version, so the grammar can evolve without
misreading old files; `description`, `notes` and a per-panel `description` carry the prose. Files are named
`<name>.v<N>.sutra.yaml` and are found by scanning, recursively, the site directories (`drishti.rachana.dirs`, default `./sutras`) and every enabled
pack's `sutras/` folder (`config/packs/<pack>/sutras/<area>/…`). `SutraBuilder` validates each one at load against the panel
kinds' required and allowed options and reports problems with line and column (codes `DRS-2001`, `DRS-2002`; the
detailed checks are 2009–2012 and 2020–2039; the About files add 2040–2047, search forms 2060). Any other file in a Sutra folder (a `.sutra.md`, a plain `.yaml`)
is reported as `DRS-2004` with the fix. Because the grammar is plain YAML, `RachanaSchema` generates a JSON Schema of
it from `PanelKind`, the formats and the served kinds, served at `GET /api/v1/rachana/schema`; Studio's editor and
any schema-aware editor complete and check Sutras with it. With `hot-reload` on, file events reload changed files (debounced 250 ms); every watcher in the JVM (Sutras, packs, connectors, BI, AI, MCP) shares one `WatchService` and one dispatcher thread through `SharedWatchService` in `drishti-common`, so a process holds one inotify instance however many contexts or folders it watches; an
invalid edit keeps the last good version and is listed at `GET /api/v1/sutras/problems`. Bindings use a small,
compiled, side-effect-free expression language (**Rachana-EL**) — not scripting.

A real Sutra, abbreviated (`examples/packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml`, after its copyright comment):

```yaml
rachana: 1
sutra: irs-vanilla
version: 3
description: Vanilla fixed/float interest rate swap (mockup drishti-irs.png).
match: { kind: trade, where: "$.productType == 'IRS' && size($.legs) == 2", priority: 10 }
title: { pill: "Trade · Interest rate swap", id: $.tradeId, with: "link($.counterparty.id, 'counterparty', $.counterparty.name)" }
strip:                                   # header key figures (at most 8)
  - { label: Notional (USD), bind: $.notional, fmt: amount0 }
  - { label: Direction, bind: "$.direction == 'PAY_FIXED' ? 'Pay fixed' : 'Receive fixed'" }
  - { label: MTM (USD), bind: $.mtm, fmt: signed0, tone: sign, emphasis: true }
  - { label: DV01 (USD), bind: $.dv01, fmt: signed0, tone: sign }
panels:
  - id: legs
    kind: tabs
    title: Legs
    key: F2
    each: $.legs
    tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
    body: { kind: kv, columns: [ { label: Currency, bind: "@.currency" }, … ] }
  - id: cashflows
    kind: table
    title: Cashflows · Leg 1
    key: F3
    rows: $.legs[0].cashflows
    totalLabel: Total
    columns:
      - { label: Amount (USD), bind: "@.amount", fmt: signed2, tone: sign, total: true }
      - …
```

**Panel kinds** (the `PanelKind` enum, twenty-two): `kv`, `table`, `tabs`, `line`, `area`, `hbar`, `ladder`, `links`,
`status`, `provenance`, `markdown`, `gauge`, `surface`, `waterfall`, `histogram`, `scatter`, `candlestick`, `graph`, `timeline`, `pivot`,
`metric`, `map`. The chart and aggregate kinds are bound
by `ChartBinder` under the `drishti.panels` limits and drawn by `static/js/drishti_charts.js`; [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one)
describes every kind in depth. The full grammar is in
[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md). A new kind is added to the enum (with its required and allowed
options) and to the binder on the Java side, plus a Jinja macro in `_macros/panels.html` and its client-side
handling on the console ([section 7a](#7a-how-a-screen-is-made-generic-not-bespoke) maps each kind to its macro).

**Formats** (`fmt`) are named and config driven: the core set is in `drishti-rachana/src/main/resources/formats.yaml`
(`text`, `amount0`, `amount2`, `signed0`, `signed2`, `pct0`, `pct2`, `price2`, `compact` (4.1m), `date`, `dmy`), a
site file can be named in `drishti.rachana.formats-file`, and packs add their own (the finance pack's
`config/formats.yaml` adds `pct4`, `rate5`, `pips1`, `df4`, `bp1`). **Tones** (`sign`, `threshold`, `status`) colour
values through theme tokens only, and always pair colour with a glyph or sign (WCAG, as MAYA requires).

Versioning: a view records `Sutra name vN` in its provenance; every version stays loadable. With review on
(`drishti.governance.enabled`, ADR-013), a Studio save becomes a proposal that an approver makes live.

---

## 7. Inference engine

Inference (`drishti-inference`) is a **scored rule system** over the document's shape — deterministic, explainable,
and run on the cold path only (results are cached by fingerprint). Each rule proposes panel candidates with a
score; `InferenceEngine` packs the winners into areas (`main`, `right`) under density limits, and builds the header
strip itself from the top-scoring scalars (at most 8). A `Linked entities` panel (`refs`) is always added.

| Rule (`Rules.java`) | Detects | Proposes |
|---|---|---|
| `LegsRule` | 2–4 structurally similar objects with many fields (legs, tranches); or 1–8 records that hold lists of their own (legs with cashflows with fixings) | `tabs` of `kv`; or `tabs` with `panels:` (a `kv` and a table or tree table per list) |
| `HomogeneousArrayRule` | array of objects sharing most keys | `table`; numeric columns right-aligned; totals for amount-like columns |
| `TermStructureRule` | array keyed by tenor-like values (`1M`, `5Y`, dates) with one numeric | `line` chart |
| `DistributionRule` | small map/array label → number | `hbar` |
| `TimeSeriesRule` | array of `{date, value…}` sorted by date | `ladder` |
| `NestedObjectRule` | object of scalars | `kv` panel, column count by field count |

The strip comes from top-level scalars ranked by semantic weight (money, rate, date); references (`NS-*`, `CSA-*`)
are found by the entity graph, not by a rule (section 8). Semantic hints come from field names and value shape
(`*notional*`, `*rate*`, ISO dates, currency codes, tenor strings) and from config: the core
`drishti-inference/src/main/resources/inference/semantics.yaml` plus each pack's `semantics:` file. Every inferred
panel carries `"inferred": true`, and the provenance label says `+ inference`; Sutra Studio can show the inferred
layout as a Sutra to start from (`GET /api/v1/studio/inferred/{kind}/{id}`).

---

## 7a. How a screen is made: generic, not bespoke

A Sutra is **not** compiled to JavaScript or HTML, and no code is generated from it. It is YAML that the server reads
and interprets: `SutraBuilder` turns it into a model, `LayoutMerger` combines it with what inference proposes into a
**data-free layout**, and `Binder` (with `ChartBinder` for the chart kinds) binds that layout to one document into a
**JSON ViewModel**. The console draws every ViewModel with the same page template and **one Jinja macro per panel
kind**, and the browser runs the same generic scripts on every page: charts, tables, tabs, keys and live patches.
Every entity of every mnemonic, in every pack, goes through this one path. There is no screen per product, per kind
or per mnemonic.

```
 config/packs/<pack>/sutras/**/*.sutra.yaml        the entity's document (any connector)
          │  SutraBuilder (load, validate)                    │
          ▼                                                   ▼
   Sutra model ── SutraMatcher ─► LayoutMerger ◄── InferenceEngine (shape rules)
                                       │
                         Layout: data-free, cached per (Sutra version, kind, fingerprint)
                                       │  Binder, ChartBinder (drishti-engine)
                                       ▼
                         ViewModel JSON   GET /api/v1/views/{kind}/{id}
                                       │
   console   terminal/view.html ─ _macros/view.html (title, strip) ─ _macros/panels.html (one macro per panel kind)
                                       │
   browser   drishti_view.js (tabs, keys, line, area, surface) · drishti_charts.js (waterfall … graph, donut, map) · drishti_tables.js,
             drishti_tree-rows.js, drishti_pivot.js · drishti_live.js (frames from the live channel, same macros server-side)
```

What is code and what is configuration:

| Layer | Written once, used by every kind | Written per pack or kind |
|---|---|---|
| Server | `SutraBuilder`, `SutraMatcher`, `InferenceEngine`, `LayoutMerger`, `Binder`, `ChartBinder`, `PatchDiffer` | nothing |
| Console | `terminal/view.html`, `_macros/view.html`, `_macros/panels.html`, `api_routes._panel_html` (live patches) | nothing |
| Browser | `drishti_view.js`, `drishti_charts.js`, `drishti_geomap.js`, `drishti_tables.js`, `drishti_tree-rows.js`, `drishti_pivot.js`, `drishti_live.js` | nothing |
| Pack | — | YAML only: Sutras (or none), fragments, `form:`, `columns:`, `pivot:`, `semantics:`, formats |

The default packs hold 219 Sutras (`config/packs/*/sutras/`) and the UI kit and packs 36 fragments; all of them are
drawn by the one page template and the one macro file. Search, query by form and the pivot are generic in the same
way (examples 3 and 4). The examples below were captured from a server started from the repository with the default
packs and `DRISHTI_LAKE_ENABLED=false`, so the `demo` connector's committed samples answered, and a console pointed at
it; the commands use the quick-start port 18480.

### Example 1: Sutra → ViewModel → HTML

**The Sutra.** `TRD MX-20000001` matches `config/packs/trading/sutras/murex/irs-fixfloat.v1.sutra.yaml` (generated
by `tools/packgen/banking/make_sutras.py`). One strip cell, and the first panel, which it takes from a fragment:

```yaml
match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }
strip:
  - { label: Notional, bind: "$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')", emphasis: true }
panels:
  - { use: trading/deal-ticket@1, with: { rate-fmt: pct4, product: "Interest rate swap (fixed/float)" } }
```

The fragment (`config/packs/trading/sutras/common/deal-ticket.v1.fragment.yaml`) is a `kv` panel, still only YAML:

```yaml
panels:
  - id: ticket
    kind: kv
    title: Deal ticket
    code: DES
    key: F2
    columns:
      - { label: Trade id, bind: $.TRADE_ID }
      - { label: Transaction, bind: "nonblank($.TRANSACTION_ID, '–')" }
      - { label: Version, bind: "nonblank($.TRADE_VERSION, '–')" }
      - …                                   # 31 fields in all
```

**The ViewModel.** The server binds it to the document; abbreviated:

```bash
curl -s http://localhost:18480/api/v1/views/trade/MX-20000001
```

```json
{
  "ref": {"kind": "trade", "id": "MX-20000001"}, "mnemonic": "TRD",
  "strip": [ …, {"label": "Notional", "text": "AUD 242,000,000", "emphasis": true, "path": "$.CURRENCY"}, … ],
  "panels": [
    {"id": "ticket", "kind": "kv", "title": "Deal ticket", "code": "DES", "key": "F2", "area": "main", "inferred": false,
     "data": {"fields": [
        {"label": "Trade id", "text": "MX-20000001", "link": {"kind": "trade", "id": "MX-20000001", "mnemonic": "TRD"}, "path": "$.TRADE_ID"},
        {"label": "Transaction", "text": "20000001002", "path": "$.TRANSACTION_ID"},
        {"label": "Version", "text": "2", "path": "$.TRADE_VERSION"}, … ]},
     "empty": false},
    {"id": "legs", "kind": "tabs", …}, {"id": "flows", "kind": "table", …}, {"id": "events", "kind": "timeline", …}, …],
  "provenance": {"layout": "Sutra irs-fixfloat v1 + inference", "sutra": "irs-fixfloat", "source": "murex", …}
}
```

The ViewModel is the whole contract between server and console: labels, formatted text, tones, links and the JSON
path of every value. Nothing in it says "trade" or "swap" to the console.

**The macro.** For each panel the page calls `panel(p)` in `drishti-console/web/templates/_macros/panels.html`; it
draws the frame (`<section>`, header, code, key, download and help) and dispatches on `p.kind` in `pbody`:

```jinja
{% elif p.kind in ['kv', 'status'] %}{{ fields(p.data.fields, 4 if p.area == 'main' else 2) }}
…
{% macro fields(list, cols=4) -%}
<dl class="kv kv-{{ cols }}">
  {% for f in (list or []) %}<div class="kv-item"><dt>{{ f.label }}</dt><dd class="mono{{ tone(f) }}"{% if f.path %} data-path="{{ f.path }}"{% endif %}>{{ cell_text(f) }}</dd></div>{% endfor %}
</dl>
{%- endmacro %}
```

**The HTML.** What the console sent for `/v/trade/MX-20000001` (abbreviated; the download menu left out):

```html
<section class="pnl" id="p-ticket" data-panel="ticket" data-kind="kv" data-key="F2" data-span="12" data-height="0" …>
  <header class="pnl-h"><h3 id="h-ticket">Deal ticket</h3>
    <span class="pnl-code">DES · F2 … <a class="pnl-help" href="/help/rachana-reference#kv" …></a></span></header>
  <div class="pnl-b">
  <dl class="kv kv-4">
  <div class="kv-item"><dt>Trade id</dt><dd class="mono" data-path="$.TRADE_ID"><a class="lnk" href="/v/trade/MX-20000001">MX-20000001</a></dd></div>
  <div class="kv-item"><dt>Transaction</dt><dd class="mono" data-path="$.TRANSACTION_ID">20000001002</dd></div>
  <div class="kv-item"><dt>Version</dt><dd class="mono" data-path="$.TRADE_VERSION">2</dd></div> …
```

A field with a `link` becomes an anchor to `/v/{kind}/{id}`; `data-path` is what the About drawer, the live flash
and the export read. When a tick changes the panel, the console renders it again with the same `panel` macro
(`routes/api_routes.py`, `_panel_html`) and `drishti_live.js` swaps it in (section 10).

**Panel kind → macro → browser script.** One to one, for all twenty-two kinds of the `PanelKind` enum:

| `kind` | Macro in `_macros/panels.html` | Drawn or enhanced in the browser by |
|---|---|---|
| `kv`, `status` | `fields` | none (plain HTML) |
| `provenance`, `markdown`, `metric`, `gauge` | inline in `pbody` | none |
| `table`, `ladder` | `table` (through `pivotable`; `drill` when the Sutra has `levels:`) | `drishti_tables.js`, `drishti_colfilter.js`, `drishti_tree-rows.js`, `drishti_pivot.js` (the Pivot tab) |
| `tabs` | `tabs` (each tab's body is again `pbody`) | `drishti_view.js` |
| `line`, `area` | `chart` (`data-chart` JSON) | `drishti_view.js` (ECharts) |
| `hbar` | `bars`, or `donut` with `shape: donut` | none; `drishti_charts.js` for the donut |
| `surface` | `surface` (`data-surface` JSON) | `drishti_view.js` (heatmap or 3D) |
| `links` | `links` | none |
| `waterfall`, `histogram`, `scatter`, `candlestick`, `graph` | the macro of the same name (`xchart`, `data-xchart` JSON) | `drishti_charts.js` |
| `timeline` | `timeline` | none |
| `map` | `geomap` | `drishti_geomap.js`, `drishti_charts.js` |
| `pivot` | `pivot`, `pivot_table` | `drishti_tree-rows.js`, `drishti_pivot-grid.js` |

### Example 2: a kind with no Sutra

No Sutra in the default packs matches `risk-hierarchy` (`HIER`, a node of a VaR hierarchy; it is normally read from
the `risk-store` lake). To show what inference alone makes of it, the first line of the committed sample
`config/packs/market-risk/samples/risk-hierarchy.jsonl` was put in the `file` connector's folder of the scratch
server (`<data>/feeds/risk-hierarchy/LEGAL~LE-NY.json`). The document is flat UPPER_SNAKE fields, a `REF` object and
two lists of ids (`CHILDREN`, `BOOKS`).

```bash
curl -s 'http://localhost:18480/api/v1/views/risk-hierarchy/LEGAL~LE-NY'
```

Abbreviated:

```json
{
  "title": {"pill": "Risk hierarchy", "id": "risk-hierarchy", "with": null},
  "strip": [{"label": "Node ID", "text": "LEGAL~LE-NY", "path": "$.NODE_ID"},
            {"label": "Hierarchy", "text": "LEGAL", "path": "$.HIERARCHY"},
            {"label": "Limit VaR", "text": "180,000,000", "path": "$.LIMIT_VAR"},
            {"label": "Business date", "text": "2026-09-30", "path": "$.BUSINESS_DATE"}, …],
  "panels": [
    {"id": "built", "kind": "provenance", "title": "How this view was built", "inferred": true,
     "data": {"fields": [{"label": "Layout", "text": "inference only"}, …]}},
    {"id": "ref", "kind": "kv", "title": "Ref", "area": "right", "inferred": true,
     "explanation": "NestedObjectRule 0.55: 2 fields",
     "data": {"fields": [{"label": "Kind", "text": "legal-entity"}, {"label": "ID", "text": "LE-NY"}]}},
    {"id": "refs", "kind": "links", "title": "Linked entities", "area": "right", "inferred": true, "empty": true, …}],
  "provenance": {"layout": "inference only", "sutra": null, "source": "feed-file", "fingerprint": "fd66…eda6", …}
}
```

Reading it: `provenance.layout` is `inference only` and `sutra` is `null`; every panel says `"inferred": true` and
why (`explanation`); the strip is the eight top-ranked scalars. The lists of plain ids gave no panel, and the
identifier rules look for camelCase names (`riskHierarchyId`, `id`, `code`, `name`), so the title falls back to the
kind's name; a Sutra with `title: { id: $.NODE_ID }` fixes both. The ViewModel has the same shape as example 1, so
the same template and macros draw it. To start that Sutra from what inference made:

```bash
curl -s 'http://localhost:18480/api/v1/studio/inferred/risk-hierarchy/LEGAL~LE-NY?name=risk-node'
```

```yaml
# Started from what inference makes of risk-hierarchy LEGAL~LE-NY. Edit freely.
rachana: 1
sutra: risk-node
version: 1
match: { kind: risk-hierarchy, priority: 1 }
title: { pill: "Risk hierarchy", id: "'risk-hierarchy'" }
strip:
  - { label: "Node ID", bind: "$.NODE_ID" }
  - …
panels:
  - id: ref
    kind: kv
    title: "Ref"
    area: right
    rows: "$.REF"
    columns:
      - { label: "Kind", bind: "@.KIND" }
      - { label: "ID", bind: "@.ID" }
```

[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#from-an-inferred-layout-to-a-sutra) explains the rules and the step from here to a Sutra.

### Example 3: query by form

**The YAML.** A pack lists the fields people search a kind by; from `config/packs/trading/pack.yaml`:

```yaml
form:
  trade:
  - path: SOURCE_SYSTEM
    label: Source system
    type: enum
    group: Identity
    order: 2
    help: 'ENDUR, CALYPSO or MUREX: the booking system the trade comes from.'
  - path: CURRENCY
    label: Currency
    type: enum
    group: Where
    order: 8
  - path: QUANTITY
    label: Quantity
    type: amount
    group: Money
    order: 12
    operators: ['>=', '<=', between, '=']
```

**The page.** `FORM trading <GO>` goes to `/form/trading` (`drishti-console/routes/form_routes.py`, `form_page`).
The route asks the server for the form (`GET /api/v1/forms/trading?kind=trade`: the fields with their operators and
the values the data holds, e.g. `"values": ["CALYPSO", "ENDUR", "MUREX"]`), and `core/forms.py` groups the fields for
`web/templates/terminal/form.html`, which has one branch per field **type** (`text`, `number`/`amount`, `date`,
`enum`, `id`, `bool`), never per pack or field. The address holds the criteria:

```text
/form/trading?kind=trade&op.SOURCE_SYSTEM=in&v.SOURCE_SYSTEM=MUREX&op.CURRENCY=in&v.CURRENCY=EUR&v.CURRENCY=USD&op.QUANTITY=%3E%3D&v.QUANTITY=100m&run=1
```

**The search it writes.** `forms.criteria_from` reads the criteria from the address and the server compiles them to
the search grammar:

```bash
curl -s -X POST -H 'Content-Type: application/json' http://localhost:18480/api/v1/forms/trading/trade/compile \
  -d '{"criteria":[{"path":"SOURCE_SYSTEM","op":"in","values":["MUREX"]},
                   {"path":"CURRENCY","op":"in","values":["EUR","USD"]},
                   {"path":"QUANTITY","op":">=","values":["100m"]}],"limit":100}'
```

```json
{"command":"TRD where SOURCE_SYSTEM = 'MUREX' and (CURRENCY = 'EUR' or CURRENCY = 'USD') and QUANTITY >= 100m limit 100"}
```

The route runs that command as any search (`GET /api/v1/search?q=…`, example 4) and the page shows it with Copy and
Edit as text, then the results table (`127 of 765 trades` on the demo samples). Masks, kinds per role and packs per
user apply because it is an ordinary search. A pack adds a form by adding YAML; no form has code of its own. The
reference is [USER_GUIDE.md](../guides/USER_GUIDE.md#query-by-form).

### Example 4: search and the pivot

**Plain words.** `murex trades in eur over 100m <GO>` is not a command, so `/go` sends it to
`/s?words=murex%20trades%20in%20eur%20over%20100m`; the console asks the server what the words mean:

```bash
curl -s 'http://localhost:18480/api/v1/phrase?text=murex%20trades%20in%20eur%20over%20100m'
```

```json
{"query": "TRD where SOURCE_SYSTEM = 'MUREX' and CURRENCY = 'EUR' and QUANTITY > 100000000",
 "kind": "trade",
 "steps": [{"words": "trades", "meaning": "Trade (TRD)"}, {"words": "murex", "meaning": "source system is MUREX"},
           {"words": "eur", "meaning": "currency is EUR"}, {"words": "over 100m", "meaning": "quantity > 100000000"}],
 "ignored": [], "problem": null, "command": null}
```

The page says *Your words make this search:* and links the query; following it runs the structured search.

**The search.** Typed directly (`TRD where … <GO>`) or reached from words or a form, it is one call:

```bash
curl -s "http://localhost:18480/api/v1/search?q=TRD%20where%20SOURCE_SYSTEM%20%3D%20'MUREX'%20and%20CURRENCY%20%3D%20'EUR'%20and%20QUANTITY%20%3E%20100000000%20limit%20100"
```

Abbreviated:

```json
{"query": "TRD where SOURCE_SYSTEM = 'MUREX' and CURRENCY = 'EUR' and QUANTITY > 100000000 limit 100",
 "kind": "trade", "mnemonic": "TRD",
 "condition": "lower($.SOURCE_SYSTEM) == lower('MUREX') && lower($.CURRENCY) == lower('EUR') && $.QUANTITY > 100000000",
 "columns": ["$.SOURCE_SYSTEM", "$.CURRENCY", "$.QUANTITY", "$.PRODUCT_TYPE", …],
 "labels": {"$.SOURCE_SYSTEM": "Source system", "$.CURRENCY": "Currency", …},
 "rows": [{"ref": {"kind": "trade", "id": "MX-20000002"}, "title": "MX-20000002",
           "values": {"$.SOURCE_SYSTEM": "MUREX", "$.CURRENCY": "EUR", "$.QUANTITY": 110000000.0, …}}, …],
 "scanned": 765, "matched": 21, "elapsedMs": 7.72,
 "pivot": {"rows": ["PRODUCT_TYPE"], "columns": ["SOURCE_SYSTEM"], "values": [{"field": "QUANTITY", "agg": "sum"}], …}}
```

**The results page.** `/s` (`routes/terminal_routes.py`, `search`) renders `web/templates/terminal/search.html`
for every kind: `21 of 765 trades match`, a table whose columns come from the pack's `columns:` for the kind (each
row links to `/v/{kind}/{id}`), a chart of the rows, and, because the trading pack declares `pivot:` for `trade`, a
**Table | Pivot** switch drawn by `drishti_pivot.js` from the `pivot` block above. A kind without `columns:` gets columns
from the condition and the documents; a kind without `pivot:` gets the table alone. The grammar is in
[USER_GUIDE.md, Search by value](../guides/USER_GUIDE.md#search-by-value) and
[Asking in plain words](../guides/USER_GUIDE.md#asking-in-plain-words).

### When code is needed

YAML covers new kinds, packs, screens, forms, searches and pivots. Code is needed only to give the grammar or the
platform a new capability, and then it serves every pack:

| Need | Code to write | Where |
|---|---|---|
| A new panel kind | the enum entry with its required and allowed options; its binding; its macro; its client script if it draws anything; its help entry | `PanelKind` (`drishti-rachana`), `Binder` or `ChartBinder` (`drishti-engine`, package `bind`), `drishti-console/web/templates/_macros/panels.html` (`pbody` and a macro), `drishti-console/web/static/js/drishti_view.js` or `drishti_charts.js`; [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one), [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-viii-changing-drishtis-code-the-developer-guide) |
| A new kind of store (a source plugin) | a `SourcePlugin` (`fetch`, optionally `subscribe`, `search`, `reverse`) in a plugin module; after that each store is a connector file | `SourcePlugin` (`drishti-api`), a module under `plugins/` such as `drishti-plugin-file`; [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi) |
| A new computed measure | the computation: a Java engine (as VaR and exposure in `drishti-compute`, packages `var` and `ccr`) or a Python engine behind a `reactive` connector; its result is then an ordinary kind, drawn by the generic view | [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md), [VAR_GUIDE.md, Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) |

### The hand-built pages and why

Some pages are hand-built. Each is a product feature that works across packs and kinds, built on the same server
APIs, entitlements and masks as the generic view; none is a screen for one product or one mnemonic.

| Page | Routes | Templates | Why it is its own page |
|---|---|---|---|
| VaR explorer | `/var` (`VAR <scope> <GO>`) | `terminal/var.html`, `static/js/drishti_var-explorer.js`, `drishti_var-charts.js` | Any scope (hierarchy node, book, basket, trade) computed on demand by the VaR engine (`/api/v1/var`), with drill-down, captures and compare; the computed kinds stay ordinary kinds on `/v/…` too. [VAR_GUIDE.md](../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen) |
| Exposure explorer | `/exposure` (`EXP <scope> <GO>`) | `terminal/exposure.html`, `static/js/drishti_exposure-explorer.js`, `drishti_exposure-charts.js` | The same for counterparty exposure (`/api/v1/exposure`); `exposure-result` is still a kind. [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md#3-the-explorer-screen-by-screen) |
| BI Studio, report viewer and the BI pages | `/bi/…` | `templates/bi/` | Reports and models over datasets (DuckDB on the server), a different job from one entity's view. [BI_GUIDE.md](../guides/BI_GUIDE.md) |
| Admin | `/admin/…` | `templates/admin/` | Running the system: users, packs, connectors, lakes, Drishti\*net |
| Sutra designer and Build | `/designer`, `/build/…`, `/studio` | `templates/designer/`, `templates/build/`, `templates/studio/` | Tools that write Sutras; their previews are the generic view's macros. [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |
| Data map | `/datamap` (`DATAMAP <GO>`) | `terminal/datamap.html`, `static/js/drishti_datamap.js` | The packs' kinds and joins, checked against the data (`/api/v1/datamap`). [USER_GUIDE.md](../guides/USER_GUIDE.md#the-data-map) |
| Landing and Home | `/`, `/t` | `landing.html`, `terminal/home.html` | Entry pages: the command line, recent entities, the watch list |
| Workspaces and Monitors | `/w/…`, `/m/…`, `/alerts` | `templates/workspace/`, `templates/monitor/` | Arrangements of generic views and searches, saved per user; a workspace pane is a `/v/…` view. [USER_GUIDE.md](../guides/USER_GUIDE.md#workspaces) |
| Help centre | `/help/…`, `/what` | `templates/help/` | Renders the repository's guides and the packs' Knowledge pages |

---

## 8. Entity graph and navigation

- **Reference catalog** (`drishti-graph`, `ReferenceCatalog`) maps identifier patterns and field names to kinds.
  Packs declare them in `pack.yaml` (`graph: {id-patterns, fields, badges}`), sites add more under
  `drishti.graph.id-patterns` and `drishti.graph.fields`, e.g. `^NS-[A-Z]+-\d+$ → netting-set`,
  `nettingSet → netting-set`.
- The `Linked entities` panel shows label, target, a **badge** computed from the target by a Rachana-EL expression
  (`drishti.graph.badges`, e.g. `PFE 46.0m`), and a status (`resolved`, `pending`, or denied with the reason).
- Navigation keeps a **breadcrumb trail per browser tab** (`drishti_view.js`); `Alt+←` goes back. Reverse links (netting
  set → member trades) and **F8 Impact** (`/api/v1/impact/{kind}/{id}`) are answered by plugins that implement
  `SourcePlugin.reverse`, rolled up along `drishti.graph.impact.follow`.

---

## 9. Command suggestions (type-ahead)

As in the Bloomberg terminal, the command line suggests while the user types, in a dropdown under the input:

```
┌ TRD MX-2000█ ───────────────────────────────────────────────────────────────────────┐
│ TRD  MX-20000001   Trade · Interest rate swap (fixed/float) · Meridian Reinsurance Ltd │  ← highlighted
│ TRD  MX-20000002   Trade · Interest rate swap (fixed/float) · Halcyon Shipping plc     │
└────────────────────────────────────────────────────────────────────────────────────┘
```

- **What it matches**, by the state of the input:
  - an empty or partial first token matches **mnemonics** (`T` → `TRD Trade`, …);
  - after a mnemonic, it matches **entities of that kind** by identifier or name;
  - a bare identifier matches any kind, with the mnemonic filled in;
  - recently viewed entities (`drishti.commands.recent-size`, 20 per user) rank first.
- **Engine:** `SuggestionService` merges the mnemonic table, the per-user recent list and `SourcePlugin.search`.
  Plugins are queried in parallel on virtual threads within `drishti.commands.suggest-budget` (30 ms); late answers
  are dropped. Each plugin answers from an in-memory `HitIndex` (`drishti-api`), so most queries take microseconds.
- **API:** `GET /api/v1/command/suggest?q=TRD%20T-1000&limit=10` →
  `[{type, mnemonic, kind, id, title, subtitle, complete}]` (see section 5 for a real answer).
- **Console:** `drishti_command.js` debounces by 60 ms and cancels stale requests with `AbortController`. ↑/↓ move, `Tab`
  completes, `Enter` (`<GO>`) opens, `Esc` closes; matched characters are highlighted; ARIA `combobox`/`listbox`.

### 9.1 Several business days

A connector keeps the newest business day's ids by default. With `index.days` above 1 it keeps that many days, and a
type-ahead for a past business date answers from that day's own ids, never from another day's.

- **Date keys everywhere.** The index is a map of business date to day segment, for every query, today included. "Live"
  or "current" is resolved to a concrete business date (the kind's newest date) before the lookup and the day is found by
  that date; there is no "empty" or "current" key. A kind that has no dates uses the explicit key `UNDATED`.
- **Structure** (`DatedHitIndex`, `drishti-api`). Per kind: one dictionary of ids, each stored once, sorted for prefix
  binary search, with title and subtitle; and one compact bitmap per business day marking which ids existed that day. A
  prefix search is a binary search in the dictionary and a membership test in the day's bitmap.
- **Tiers.** The newest day and, by default, the closed days are on the heap. When the connector's `index.max-memory` is
  reached (or `index.storage: mmap` is set) a closed day is written once to a **segment file** (`DaySegment`): ids sorted
  and compressed in blocks of 128, read with **positional reads** of an open file channel (no memory map: a mapped
  file's blocks are freed only when the garbage collector unmaps it, so `df` would keep counting a deleted day). The
  block index (first id and offset per block) is on the heap; a prefix search binary-searches it, reads and decodes one
  or two blocks, and keeps a small bounded LRU of decoded blocks per segment (`index.block-cache`, 16). **Release is
  deterministic**: every lookup holds the segment (a reader count); a roll, a disk-cap eviction, a reload swap or a
  stale or corrupt file *retires* it, which removes the file and closes the channel as soon as the last running lookup
  lets go, so the disk is free at once and a lookup racing a retirement never fails (it looks again at the current
  segment). A day neither tier holds is built on demand: the connector answers as it
  always did (the newest day's ids) while the day is built in the background.
- **Files.** Under `drishti.index.dir` (`DRISHTI_INDEX_DIR`; default `<data dir>/index`, which is `./data/index`; never the temporary folder), one folder per
  connector, capped by `index.max-disk`. A file is written under a temporary name in its folder and moved into place
  atomically, ends in a CRC, and records the **watermark** of its source (a load time, a table version, a snapshot, a
  folder's modification time). After a restart a file is reused only if the source's watermark is unchanged; otherwise
  the day is rebuilt. A truncated or corrupt file is deleted and treated as absent, and temporary files left by a crash
  are removed at the next start.
- **Freshness.** The newest day updates (streams on arrival; tables on `refresh-seconds`). A data-load signal
  (the loader's load time, a Delta commit, an Iceberg snapshot, a replaced DuckDB file, a changed folder or file) rebuilds
  the kept past days in the background. On a business-date roll the new date's segment is added, the "today" pointer
  moves (one immutable snapshot swap, so readers are lock-free and never see a half-rolled index), the oldest day's
  bitmap or file is dropped, and dictionary ids no kept day holds are purged: memory and disk stay flat. `max-memory` moves
  the oldest closed days to disk (or drops them without a disk) early; `index.max-disk` drops the oldest segments.
- **Reload (admin and automatic).** `DatedHitIndex.reload(kind, day)` rebuilds chosen days from the source on a
  background thread: the new day is read in full, written (a heap day's bitmap; a disk day's temporary file, then an
  atomic move), and swapped in in one step, so the old day keeps answering until the new one replaces it and a reader
  never sees a mix. A day that cannot be read keeps its old content and shows the error. **One job per connector**: a
  manual reload and the automatic check share the same slot (a second request is refused with `409 DRS-5032`; an
  automatic cycle that finds a job running is skipped), so they never overlap. The automatic check
  (`index.reload-every`, default `10m`, `0` = off; per kind with `index.kinds.<kind>.reload-every`) runs on one shared
  daemon scheduler: each connector's first check is jittered, and a cycle compares every *past* day (the newest day has
  its own faster refresh) with the watermark recorded when it was built (load time, Delta commit, Iceberg snapshot,
  file modification time) and rebuilds only the days whose watermark changed; a source with no reliable watermark (S3,
  a table without a load time) has each past day rebuilt once per cycle. Rebuilds of one cycle are spaced by a random
  pause so a lake is not hit all at once. This is what catches a **past day restated with no signal**. Admin: `GET` and
  `POST /api/v1/admin/connectors/{name}/index[/reload]` ([API_GUIDE.md](../guides/API_GUIDE.md)); the status gives, per
  kind and day, ids, tier, bytes, build time, whether it is rebuilding and the last error, and the automatic check's
  last check, next check and last change found.
- **Warm-up.** After start, past days are built in the background, newest first; start-up is not delayed.
  `index.warm: lazy` builds a day on the first search for it instead.
- **`index.any-recent-day: true`.** A bare id on the newest date may also suggest ids seen only on earlier kept days,
  marked `last seen <date>` in the subtitle.
- **Health.** Admin, Health and the connector page show per connector: `type-ahead index: 5 days (4 on disk, 0.1 MB),
  1,200 ids, 0.3 MB, warm`. The connector's cache statistics add `indexDays`, `indexDaysOnDisk`, `indexIds`,
  `indexMemoryMb`, `indexDiskMb` (the segment files' real size), `indexWarm`, `indexReloadEvery`, `indexLastCheck`,
  `indexNextCheck`, `indexLastChange`, `indexReloading`.

The settings are in [CONFIGURATION.md](../reference/CONFIGURATION.md#drishtiindex--the-multi-day-type-ahead-index). Streaming
connectors keep the newest day only. Measured: a prefix lookup over 100,000 ids a day for five days takes 0.002 ms on
the heap and 0.04 ms from a segment file read with positional reads (548 KB on disk for 100,000 ids)
([PERFORMANCE.md](../admin/PERFORMANCE.md#multi-day-type-ahead)).

---

## 10. Live updates

```
 SourcePlugin.subscribe ──► TopicHub (one topic per EntityRef, one source subscription, single writer)
                                │  latest-wins slot; at most one delivery per 50 ms frame (leading edge)
                                ▼
                           ViewStream (one per client view): rebuild with cached layout, PatchDiffer
                                │  rebuilds never overlap; a change during one causes exactly one more
                                ▼
                           FrameMailbox (one slot per client, pending frames merge)
                                │
                                ▼
                           SSE writer (virtual thread): `view` once, then `frame` events, `: hb` every 15 s
                                │
                                ▼
                           console channel (ONE per browser tab) ──► drishti_live.js applies patches
```

- **Rebuild and diff.** Each rebuild uses the cached layout and is diffed against what the client holds; only
  changed strip cells, changed panels and the provenance travel. (Expressions also report their paths, so
  path-targeted re-binding is possible; measurement showed rebuild-and-diff is fast enough. See DATA_ARCHITECTURE.md.)
- **Chart sources tick too.** A view also listens to the topics of the entities its `line` charts read from (the
  curve behind a swap).
- **Static views.** A picked business date, or a source that does not stream, gets one `view` event and the stream
  ends; no live slot is held.
- **Capacity.** `drishti.live.max-streams` (20,000) caps view and monitor streams together; the slot is taken
  atomically before any work. Over the cap the request is refused with `DRS-5001` ("too many live streams on this server").
- **One channel per browser.** Browsers allow six connections per site over HTTP/1.1, shared by all its tabs, so the
  number of live connections must not grow with tabs or panes (a channel per tab froze a seventh tab, UX-01). No page
  opens its own `EventSource`: views, the alerts bell, monitors and every workspace pane subscribe through
  `drishti_channel.js` to one hub per browser (`drishti_live-hub.js`, in a SharedWorker; without one, in a tab elected with a Web Lock
  that relays over a BroadcastChannel), which holds the browser's single channel
  (`/api/channel?s=view:trade/MX-20000001&s=alerts&s=monitor:<name>`, at most `live.max_subscriptions`, 32). A key
  held by several tabs is subscribed once; later subscriptions are added to the open channel with
  `POST /api/channel/{id}`. Frames are still built per signed-in user: the hub only serves tabs whose session
  fingerprint (`<meta name="drishti-live">`) matches the channel's (`who`). A tab hidden for 10 s gives its
  subscriptions back and repaints from fresh data when shown. Page loads never wait for the channel. The console
  closes each upstream server stream within seconds of the browser going away. Details in [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#1-live-updates).
- **Latency.** The top bar's `Live, p99 N ms` is a rolling (30 s) HdrHistogram of source tick → frame built,
  published at `GET /api/v1/health/live` and as the Prometheus gauge `drishti_live_latency_p99_milliseconds`.
- **Alerts and monitors.** The alert engine (`drishti-server`, `AlertEngine`) subscribes to the topics of every
  entity an enabled rule watches, evaluates each rule's Rachana-EL condition on every frame and fires on the
  false → true edge. A monitor stream holds one `ViewStream` per row and multiplexes their strip patches as `row`
  events.

---

## 11. Business dates and history (ADR-012)

Every read is for a business date. The console keeps the user's choice (**Live**, or a picked date) and sends it
as `X-Drishti-As-Of` (and `X-Drishti-Known-At` for "as known at"); it never reads data itself. The server's
`AsOfResolver` and `BusinessDates` resolve it on the configured calendar (`drishti.business-date.calendar`: `USNY`,
`GBLO`, `EUTA`, `JPTO`, `CATO` (Toronto), or joint such as `USNY+GBLO`), rolling weekends and holidays back to the previous business
day, and the `AsOf` value travels through the pipeline, links, impact and suggestions to every plugin. Live streams;
a picked date is a static snapshot.

Dated sources stamp `Provenance.businessDate` and are tried first for a picked date:

| Plugin | How it is dated |
|---|---|
| `delta` | Delta Lake through Delta Kernel: `<root>/<domain>/<kind>/business_date=yyyy-MM-dd/`; `root` may be local or object storage (`s3a://…`, through `LakeStore`); time travel answers "as known at" |
| `file`, `s3` | dated folders `<root or prefix>/<yyyy-MM-dd>/<kind>/<id>.json`, newest on or before the date |
| `jdbc` | a query that uses `:asOf` |
| `aerospike` | a record per entity per business date (`kind/id/yyyyMMdd`), found through the entity's index record listing its dates ([AEROSPIKE_CONNECTOR.md](../connectors/AEROSPIKE_CONNECTOR.md)) |
| `feeds` | keeps dated observations of public feeds |

```
console ──X-Drishti-As-Of──▶ AsOfResolver ─▶ BusinessDates ─▶ ViewPipeline ─▶ SourceRouter ─▶ dated connectors first
                                                                                         └─▶ undated (demo, rest, …)
```

Named connectors (one file each in `config/connectors/`, written at the first start from a pack's `connectors:` template when absent; `drishti.sources.connectors` still works but is deprecated) give each domain its own lake
connector, e.g. `market-store` and `trading-store`. Lake retention, compaction and vacuum run outside the server
(`tools/lake/`, `deploy/lake-maintenance.yaml`; see OPERATIONS.md).

---

## 12. Domain packs

The core carries no industry. A **domain pack** (`config/packs/<name>/pack.yaml`) holds everything specific to one:
kinds, Sutras, mnemonics, identifier patterns, reference fields and link badges, roles, semantic hints and formats,
connectors, starter workspaces, help guides and sample data.

- **Loading.** `drishti.packs.enabled` (`DRISHTI_PACKS`, default the seven shipped packs) lists the packs, from
  `drishti.packs.dir` (`./config/packs`). Before any bean is built, `PackEnvironmentPostProcessor` loads them and adds
  their content as the **lowest-precedence** property source, so site configuration always wins. No core module
  depends on `drishti-packs`; they only read ordinary properties.
- **Inheritance (ADR-015).** A pack may say `extends: [parent, …]`. Enabling a pack loads its ancestors too: the
  running server enabled with
  `DRISHTI_PACKS=market-risk,counterparty-risk,…` also loads `core`, `market-data` and `trading`. The order
  is a C3 linearisation (`PackLineage`, as Python orders classes): where related packs define the same mnemonic,
  field, badge, role, route or connector, the child wins over its parents and the rightmost parent over the others;
  every override is reported (`drishti.packs.overrides`). Unrelated packs defining the same thing stop start-up and
  name both packs, and a kind always belongs to exactly one pack.


  | Pack | `extends` |
  |---|---|
  | `core`, `world-economy`, `news-and-markets` | – |
  | `market-data` | `core` |
  | `trading` | `core`, `market-data` |
  | `market-risk` | `market-data`, `trading` |
  | `counterparty-risk` | `trading` |

  The retired example packs under `examples/packs/` (`finance`, `logistics`, `genomics`, `retail-banking`, `climate-risk`,
  `operational-risk`, `liquidity-risk`) keep their own `extends` and load with `DRISHTI_PACKS_EXTRA_DIRS=examples/packs`.
- **Per-user packs.** Installed packs are what the server runs; an admin **assigns** packs to users (default
  `drishti.packs.default-for-users`), and each user chooses which assigned ones are **active**. A kind owned by an
  inactive pack cannot be opened (`PackAccess`).
- **Shipped packs** (`config/packs/`): `core`, `market-data`, `trading` (production-shaped UPPER_SNAKE trades
  from ENDUR, CALYPSO and MUREX), `counterparty-risk`, `market-risk`, `world-economy` (real public data) and the sample
  `news-and-markets`; the non-financial ones prove the core is neutral. The example packs (above) are examples and test fixtures.

`GET /api/v1/packs` lists the loaded packs; the console reads their console content from the same folders. See
[PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which) and ADR-010.

---

## 13. Module layout (Java)

Maven multi-module reactor on `spring-boot-starter-parent`, `groupId com.ash.drishti`, Java 21 or newer (production runs 21; 25 also supported), packages
`com.ash.drishti.<module>…`, `package-info.java` everywhere.

| Module | Responsibility | Contributes |
|---|---|---|
| `drishti-api` | Plugin SPI: `SourcePlugin`, `PluginManifest`, `SourceCapabilities`, `DataNode`, `EntityRef`, `EntityDocument`, `Provenance`, `AsOf`, `HitIndex`, `Subscription` (no Spring, so plugins stay light) | – |
| `drishti-common` | Error codes (`ErrorCode`, `DRS-nnnn`), `DrishtiException`, JSON codec, `ShapeFingerprinter`, `BusinessCalendar` | `CommonConfiguration` |
| `drishti-rachana` | Sutra model, YAML parser and validator, the language's JSON Schema (`RachanaSchema`), Rachana-EL compiler, formats and tones, `SutraRegistry` + hot reload, `SutraMatcher` | `RachanaConfiguration` |
| `drishti-inference` | Shape analysis, semantic hints, rules, scorer, packer, `LayoutMerger` (Sutra ⊕ inference) | `InferenceConfiguration` |
| `drishti-graph` | `ReferenceCatalog` (identifier patterns, reference fields), badges | `GraphConfiguration` |
| `drishti-engine` | `ViewPipeline`, `Binder`, caches, `SourceRouter`, `SourceRegistry`, `PluginDiscovery`, `TopicHub`, `ViewStream`, `PatchDiffer`, suggestions, structured search, impact, business dates | `EngineConfiguration` |
| `drishti-identity` | Users, roles, passwords, lockout, audit, per-user preferences (workspaces, settings); the collaboration records (`com.ash.drishti.identity.collab`: shares, inbox, outbox, threads, holds, `HashChain`); stored in a JPA database (section 18) | `IdentityConfiguration` |
| `drishti-packs` | Domain packs: reads `config/packs/<name>/pack.yaml`, resolves inheritance, contributes lowest-precedence properties (an `EnvironmentPostProcessor`) | `PackRegistry` |
| `drishti-ai` | The LLM provider layer and MCP client, no Spring: `LlmGateway`, `LlmRegistry`, providers (`openai`, `anthropic`, `mock`), `LlmPolicy` (masking, limits, audit), `McpGateway`, `McpRegistry`; off by default ([AI.md](../admin/AI.md#1-model-providers), [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)) | – |
| `drishti-diskcache` | `DiskCache`: a size-bounded, daily-cleared RocksDB cache on local disk for live connectors | – |
| `drishti-messaging` | `MessageStateSource`: the shared half of the ActiveMQ and RabbitMQ connectors (latest document per entity in a persistent RocksDB store plus a memory cache) | – |
| `drishti-lakeio` | Lake file access without Hadoop, shared by the native Delta and Iceberg engines: local disk (Windows included) and S3, byte ranges, Parquet page codecs, the Parquet footer cache; other schemes through a registered `StorageProvider` (`StorageProviders`) | – |
| `drishti-tables` | The table layer, plain Java ([TABLES_GUIDE.md](../guides/TABLES_GUIDE.md), [BI_FOUNDATION_2911E.md](BI_FOUNDATION_2911E.md#10-as-built-part-b-the-table-layer)): the catalogue of virtual tables and models (`config/tables`, stored items), their definitions and checks, preview, lineage, the materialisation's API, `tables migrate`; on Rachana and the pivot engine, never on BI | read by the server (`tables` on the command line); BI reads it with the switch |
| `drishti-memlake` | The in-memory lake, plain Java ([DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#716-the-module-drishti-memlake)): the `mem://` stores (the `mem` storage provider of `drishti-lakeio`), tables, replenish jobs, eviction, compaction, the owner binding, the guard (`Retention`), the host checks | wired by `server.memlake.MemoryLakeConfiguration` |
| `drishti-iceberg` | Drishti's own Apache Iceberg engine without Hadoop (reads, writes, path-based and REST catalogs), used by the `iceberg` (engine `native`) and `iceberg-external` connectors ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine)) | – |
| `drishti-deltalake` | A Delta Kernel engine without Hadoop (local disk, Windows included, and S3), used by the Delta connector | – |
| `drishti-lakeload` | The parallel lake loader: JSON Lines (folder or S3) into Delta or Iceberg tables of the connectors' layout, every stage multi-threaded and memory bounded, one commit per business date; `drishti.py lake build --engine java` ([DATA_ARCHITECTURE.md section 11](DATA_ARCHITECTURE.md#11-the-parallel-lake-loader-drishti-lakeload)) | `drishti-lakeio`, `drishti-deltalake`, `drishti-iceberg` |
| `drishti-net` | Drishti*net's core, no Spring ([CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md#appendix-a-drishtinet-many-servers-one-drishti)): the member table and SWIM-style gossip over HTTP (`MemberTable`, `Gossiper`, `PeersFile`), the cluster key and on-behalf-of tokens (`ClusterKey`, `OnBehalfOf`), the `config/net/` folder and its schema (`NetFolder`, `NetSchema`), signed HTTP between nodes (`PeerHttp`) | – |
| `drishti-server` | **The** Spring Boot application: `DrishtiApplication`, REST controllers, SSE, security (`TokenFilter`, OIDC), governance, alerts, actuator, OpenAPI | controllers, filters |
| `plugins/drishti-plugin-*` | Source plugins: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `cassandra`, `redis`, `mongodb`, `iceberg`, `iceberg-external`, `duckdb`, `feeds`, `news`, `yfinance`, `kafka`, `activemq`, `rabbitmq`, `s3` (ServiceLoader; all are on the server's class path, extra jars from `drishti.sources.plugin-dir` get their own class loader) | – |
| `drishti-testkit` | Shared contract tests for plugins (`DatedSourceContract`, `MessageSourceContract`, `BrokerOutage`) | – |
| `drishti-it` | Architecture (ArchUnit), licence-header and file-size tests | – |
| `drishti-benchmarks` | JMH benchmark of the hot path (`HotPathBenchmark`) | – |

**Inside `drishti-server`** (packages of `com.ash.drishti.server`): `api` (the controllers), `security`, `governance`, `collab` (shares, threads, compliance, bridges), `connectors` (connector files and the manager), `deploy` and `registry` (pack archives, the signed registry), `design` (the Build workbench's Designs), `explain` (About this page and Ask), `embed` (Drishti Elements), `loads` (data loads), `reports` (scheduled reports), `pivot`, `calc`, `layout`, `ai` (the AI and MCP endpoints), `cli` (the Java `sutra` tool), `net` (Drishti*net: the node, its endpoints, the call filter that checks the cluster key) and `bi` (Rūpaka: workspaces and catalogue, datasets and the DuckDB query engine, reports, boards, governance, live tables).

**Naming** (Pravaha convention): `XxxController`, `XxxProperties` (records bound via `@ConfigurationProperties`),
DTO holders `ApiDtos`, `ApiExceptionHandler`, `XxxRegistry` / `XxxCatalog` instead of "Repository" (identity's
Spring Data repositories in `identity.db` are the exception).

---

## 13a. Several servers as one (Drishti*net)

How a production cluster is deployed (roles, counts, sharding of VaR and exposure, high availability, load balancing, zero
downtime) is [CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md), the deployment reference.

Off by default ([CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md#appendix-a-drishtinet-many-servers-one-drishti), [OPERATIONS.md](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one)). With `config/net/node.yaml` switched on, a server is a **node**:
`NetService` keeps the member table and runs a gossip round every second on a virtual thread (push-pull with three random members over
`POST /api/v1/net/gossip`, probes, suspicion, death), saves `<data dir>/net/peers.json` and rejoins from it after a restart. `NetCallFilter`
(before the token filter) checks the cluster key on every call between nodes. What each node offers, and the routing of a request to the
node that can answer it, sit behind the source router (`SourceRouter`), so nothing above it learns about the cluster.
`IdentitySync` and `StateSync` copy users, roles and user state between nodes over signed batches; a peer's change that would leave a node
with no enabled administrator is repaired as it is applied (audited `last-admin-kept`). A member advertises only a real address
(`HostAddress`: `advertise-url`, else this machine's primary IPv4 with the port; loopback and wildcard addresses are refused in the files
and never kept from gossip).

```text
   console ──► any node (B)            A ◄──gossip──► B ◄──gossip──► C
                 │                      every node: the member table, saved to <data dir>/net/peers.json
                 └ NetCallFilter (cluster key) ─► TokenFilter ─► controllers ─► SourceRouter ─► local sources
```

## 14. Concurrency and performance

| Concern | Approach |
|---|---|
| Request I/O (fetch, links, search) | Spring MVC on virtual threads (`spring.threads.virtual.enabled`); the engine's `drishtiVirtualExecutor` runs one virtual thread per fetch, with a deadline (`orTimeout`) |
| CPU work (bind) | Bounded `ForkJoinPool` (`drishti.engine.bind-parallelism`, 0 = one per core); panels bound in parallel |
| Live state | One topic per `EntityRef`, one source subscription each (connected exactly once, even when subscribers arrive together); ticks land in a latest-wins slot and a single flush per frame delivers them; frames are timed by a small platform-thread scheduler (`drishti-frame-*`); one drainer per view stream, failed rebuilds retried with backoff |
| Caching | Caffeine in the engine: layouts (`layout-cache-size`, 10,000, keyed by Sutra version, kind and fingerprint), fingerprints (`fingerprint-cache-size`, 100,000, keyed by entity, generation and date), compiled expressions (`drishti.rachana.expression-cache-size`, 10,000). Connectors keep their own caches (Delta partitions by size, `cache-mb`; Kafka documents; RocksDB disk caches). The Delta plugin's loads that do I/O use async caches loading on virtual threads, so no lock is held during I/O. A Sutra change clears the layout cache. Admins see and purge the engine's, history's and the connectors' caches at **Admin → Caches** (`GET /api/v1/admin/caches`); VaR and exposure have their own `reload`, BI its dataset refresh. Every cache and memory-mapped file, its bound and the proposals to bound them all are in [RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) |
| Locks | `ReentrantLock` around anything that can block (file I/O, network, subscribes): Java 21 pinned a virtual thread blocked in `synchronized` to its carrier (Java 24 and later do not; the rule stays, so locks that may block remain explicit). `synchronized` remains only around short in-memory sections. Read-modify-write of shared records happens under the record's lock on the *current* value |
| Publication | State that readers need together is published in one volatile write of an immutable snapshot (Sutra registry, search index); readers never lock |
| Native resources | RocksDB generations are reference counted: a call enters the current generation and leaves after, and a retired generation closes only when its last caller has left, so a nightly clear or a purge never frees memory under a running read |
| Schedulers | The plugins' shared scheduler runs on virtual-thread workers, so a slow refresh or scan never delays another plugin's ticks or the nightly cache clearing |
| Back-pressure | One coalescing `FrameMailbox` per live client (pending frames merge, newest value wins), bounded alert queues; a slow client costs one slot, never a growing queue |
| Proof | Race tests reproduce each hazard with many threads and fail on the unsafe version: lockout under parallel guessing, admin changes against late sign-ins, the last-admin rule, simultaneous topic subscribers, index rebuilds during search, the stream cap, and RocksDB clear/close under 32 threads |
| Targets | warm view p99 < 50 ms, cold (first fingerprint) p99 < 150 ms, tick → screen p99 < 40 ms, 10k concurrent SSE subscribers per node (see PERFORMANCE.md for measured numbers) |

---

## 15. Configuration (config driven everywhere)

Precedence (low → high): pack content → `application.yaml` → `application.local.yaml` (git-ignored, imported from
the working directory) → environment variables → `--key=value` arguments. `${VAR:default}` interpolation; no
secrets in tracked files. The full key reference is [CONFIGURATION.md](../reference/CONFIGURATION.md).

| File | Owns |
|---|---|
| `drishti-server/src/main/resources/application.yaml` | server port, actuator, every `drishti.*` default: `sources`, `business-date`, `security`, `identity`, `rachana`, `governance`, `engine`, `packs`, `commands`, `live`, `graph` |
| `config/packs/<name>/pack.yaml` | a pack's kinds, mnemonics, references, badges, roles, the names of its connectors with their routes (and connector templates), Sutra and sample folders |
| `config/connectors/<name>.yaml` | one connector of the site: plugin, switch, kinds and settings; watched and applied without a restart |
| `config/packs/<name>/config/*.yaml` | the pack's formats, semantic hints, help and starter workspaces |
| `drishti-rachana/src/main/resources/formats.yaml` (+ `drishti.rachana.formats-file`) | core named number/date formats |
| `drishti-inference/src/main/resources/inference/semantics.yaml` | core semantic hints and density limits |
| `./sutras/**` (`drishti.rachana.dirs`) | site Sutras, in addition to the packs' |
| `drishti-console/config/application.yaml` | console host and port, server URL, default theme, sign-in and OIDC, help docs folder |

---

## 16. REST and streaming API (`/api/v1`)

The main endpoints; [API_GUIDE.md](../guides/API_GUIDE.md) lists every one with examples. OpenAPI is served at `/api/docs`
(Swagger UI at `/api/docs/ui`).

| Method | Path | Returns |
|---|---|---|
| `POST` | `/command` `{text}` | `{ref, mnemonic}` for `<MNEMONIC> <ID>` |
| `GET` | `/command/suggest?q=&limit=` | type-ahead entries (mnemonics, recents, entity hits) |
| `GET` | `/views/{kind}/{id}` | `ViewModel` |
| `GET` | `/views/{kind}/{id}/stream` | SSE: one `view` event, then `frame` events |
| `GET` | `/entities/{kind}/{id}/raw` | source document + provenance (F9), field masks applied for roles without `raw` |
| `GET` | `/search?q=` | structured search across entities |
| `GET` | `/impact/{kind}/{id}` · `/history/{kind}/{id}/diff` | F8 impact · what changed between dates |
| `GET` | `/sutras` · `/sutras/{name}/{version}` · `/sutras/problems` | registry listing · one Sutra · load problems |
| `GET` | `/rachana/schema` | the JSON Schema of a Sutra (Rachana language 1), with this server's kinds and formats, for editors |
| `POST` | `/studio/preview` · `/sutras` | ViewModel from an unsaved Sutra · save (a proposal when review is on) |
| `GET` | `/sutras/proposals` (+ `approve`/`reject`/`withdraw`) | Sutra governance |
| `GET` | `/sources` · `/packs` · `/about` · `/business-date` | plugins and health · packs · build info · resolved date |
| `GET` | `/health/live` · `/admin/health` · `/admin/caches` | live latency · everything's health · caches |
| `GET`/`PUT` | `/me/workspaces`, `/me/monitors`, `/me/alerts`, `/me/settings` | per-user state |
| `POST` | `/auth/login` · `/auth/oidc` · `/admin/users…` | identity (see USER_MANAGEMENT.md) |
| `GET`/`POST` | `/forms/{pack}` · `/forms/{pack}/{kind}/compile` | query by form |
| `POST` | `/packs/{pack}/loads` | the batch-landed signal ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)) |
| `GET`/`PUT`/`POST` | `/admin/connectors…` · `/admin/packs…` · `/admin/registry…` | connectors as files, packs, deploy, the signed registry |
| `GET`/`POST` | `/threads…` · `/shares…` · `/me/inbox…` | discussion and sharing ([COLLABORATION.md](COLLABORATION.md)) |
| `GET`/`POST`/`PUT` | `/bi/…` | Rūpaka: workspaces, datasets, models and the model query, Drishti SQL, reports (format schemas, themes; boards are report pages), Python, governance |
| `GET`/`POST`/`PUT` | `/net/…` | Drishti\*net between nodes (signed with the cluster key: gossip, digest, fetch, search, group, subscribe, state) and for administrators (members, files, capabilities, access, security, explain, traffic) ([OPERATIONS.md](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one)) |
| `POST` | `/ai/chat` · `/ai/mcp/{server}/tools/{tool}` | the AI and MCP layers (off by default) |
| `POST` | `/embed/token` and the `embed:` scopes | embedded views for other web applications ([CLIENT_RENDERER.md](CLIENT_RENDERER.md#20-drishti-elements-the-design)) |

Errors are RFC 7807 `application/problem+json` with a `code` (`DRS-nnnn`) member, for example:

```json
{"type":"about:blank","title":"entity not found","status":404,
 "detail":"DRS-1001 no source holds trade/IRS-48213","instance":"/api/v1/views/trade/IRS-48213","code":"DRS-1001"}
```

### 16.1 Data formats on the wire: JSON by default, Arrow where it pays

Every answer is **JSON** unless a client asks otherwise. A client that sends `Accept: application/vnd.apache.arrow.stream` gets
**Arrow IPC** where the server offers it, cell for cell the same table, with the rest of the answer in the schema metadata. Anything
Arrow cannot carry falls back to JSON, and nothing changes for a client that does not ask. Where each format is used, and why
(measured 2026-10-30; [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6](../guides/DATA_GUIDE.md#6-arrow-to-the-browser-where-it-pays)):

| Boundary | Format | Why |
|---|---|---|
| Lake | Parquet (Delta or Iceberg) | Arrow's partner on disk: compressed columns, statistics to skip files |
| VaR hot files | Drishti's own float32 rows in hierarchy order, memory-mapped, float64 node totals | summing rows runs near memory bandwidth (about 70 GB/s on 24 threads); Arrow would not be faster |
| Shard partials between servers | raw vectors of doubles | already minimal; Arrow would add framing |
| Server ↔ Python compute workers | Arrow IPC files, memory-mapped | no copy, no parse ([section 24](#24-compute-workers-python-and-java-libraries-approved-pinned-and-isolated)) |
| Server → console → browser: **VaR contributions** by trade or position | **Arrow** on request; Arrow JS in the browser (vendored) | 61–65% fewer bytes, faster fetch |
| BI queries | Arrow on request (API clients); JSON in the console's own pages | mostly text: Arrow 8–14% smaller after gzip, parsed slower, painted no sooner |
| VaR P&L paths, entity pages, ViewModels | JSON | small answers; Arrow is no smaller |
| Search results | HTML drawn on the server | no table reaches the browser; drawing it in the browser belongs with the client-side renderer |

Every front-end library, Arrow JS included, is vendored under `drishti-console/web/static/vendor/` and listed in its `VENDOR.md`;
no page loads anything from a CDN.

### 16.2 Choosing a data mechanism

Drishti has five ways to hold and serve data. Each new kind or measure uses the **lightest one that meets its need**; the heaviest, the
hot file with sharding, is kept for the few measures that cannot do without it.

| Shape of the need | Mechanism | Examples |
|---|---|---|
| Entity documents read one at a time or searched | **The lake** (Delta or Iceberg) through connectors, drawn by Sutras | trades, reference data, market data, the public-data packs |
| A figure per entity derived from its inputs | **Computed (derived) kinds**, computed on demand, never stored ([COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md)) | NSMTM, CPMTM, SSPNL, the Checks panels |
| Heavy or non-linear models per request | **Python compute workers**, Arrow in and out ([section 24](#24-compute-workers-python-and-java-libraries-approved-pinned-and-isolated)) | pricing, Monte Carlo, what-ifs |
| Aggregates for dashboards and reports | **The BI engine**: rollups and caches ([BI_DESIGN.md](BI_DESIGN.md)) | desk reports, live exposure reports |
| Large vectors per position, re-aggregated over any scope, interactively, beyond one server | **The hot file and sharding** ([CLUSTER_DEPLOYMENT.md section 5](CLUSTER_DEPLOYMENT.md#5-var-sharding-in-depth-built)) | VaR |

**The mechanism check.** A measure uses the hot file and sharding only if **all four** hold, answered with sizes at bank scale in its
design document before anything is built:

1. **Vectors per position:** each position carries a long array (scenarios, time steps, paths), not a handful of fields.
2. **Any scope, on demand:** people ask for any desk, book, basket or "bank minus X", not only fixed reports.
3. **Partials combine simply:** each server sums its part and the coordinator adds the parts before the final non-linear step (a quantile, a
   maximum, a percentile). When a group must be formed first (exposure's netting sets), the shard key keeps each group on one server.
4. **Too big for one server, or growing toward it,** and needed in under a second.

If all four hold, the measure plugs into the measure-agnostic machinery: it brings a `ShardedMeasure<P>` (its name, key order, bytes per
row, partial type and how two partials combine) and reuses the shard map, ownership, replicas, handover and scatter-gather
([CLUSTER_DEPLOYMENT.md section 5.12](CLUSTER_DEPLOYMENT.md#512-the-machinery-measure-agnostic-and-open-items)). If not, it uses one of the lighter mechanisms. Likely future users of the
heavy one: exposure paths (sharded by netting set), stress P&L across hundreds of scenarios, FRTB internal-model ES and DRC simulations,
climate scenario losses per position.

### 16.3 How memory-mapped data follows the lake

A hot file is a **copy** of lake data made for speed, so it must never be used after the lake has changed. Three things keep it honest:

1. **The data-load signal (the designed path).** A loader's last step announces "kind K landed for business date D"
   ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)). Drishti drops the connectors' caches for that kind, and the engines that hold the kind react:
   a full load of D reloads that date (a new hot file), an intraday batch is applied on top of the morning's without a reload, and the
   official records follow. The same signal fires alerts and tells people.
2. **The inputs' digest (the safety net).** A hot file is named after a digest of its inputs: the **data versions** of the tables it was
   built from (for Delta, a stamp of the date's files in the table's current version, read from its `_delta_log`, so a commit to another
   date leaves it unchanged; for Iceberg, the date's data files in the current snapshot), the scenario set, the hierarchies and the
   position rules ([VAR_GUIDE.md section 26.2](../guides/VAR_GUIDE.md#262-the-life-of-a-date)). When a date is opened or reloaded and any
   version has moved, the digest no longer matches: the file is not used, a new one is built from the lake, and the old one is deleted. A
   file is never patched in place to follow a restatement.
3. **Provenance on every answer.** Every figure names the data version, batch and scenario set it came from, so anyone can see which state
   of the lake an answer reflects.

**Without a signal** (a table changed by hand, a loader that skipped its last step), the connectors see the new table version within
`refresh-seconds` (10 s) and BI on its next query; an **engine's open date** notices it too, through the **version watch**
(O9): on a request, at most every `drishti.var.version-check` (30 s; exposure's `drishti.ccr.exposure.version-check`) the date's lake
version is compared with the one it was loaded from; when it moved, the date is reloaded in the background and swapped in, and the
answers meanwhile carry the warning "the lake's data of D changed at T; a new version is loading". A VaR date with an intraday layer is
left to its signal. Staleness without a signal is so bounded by the interval plus a load; the signal still makes the change visible at
once, which is why it stays part of every production loader. Detail in
[RESOURCES_AND_SCALE.md section 4](RESOURCES_AND_SCALE.md#4-freshness-and-invalidation) and section 14.

---

## 17. Console (UX)

Python 3.12, FastAPI + Jinja2: `core/` (app, settings, auth, backend client, business date), `routes/<area>_routes.py`,
`web/templates/<area>/`, `web/templates/_macros/`, `web/static/{css,js,img,vendor}`. All libraries are **vendored**
(Bootstrap 5, Bootstrap Icons, ECharts, ECharts GL, CodeMirror, Vega and Vega-Lite, and Pyodide for Calc; vanilla JS, no build step); no CDN, no inline script
(strict CSP); static assets are fingerprinted.

**Themes** — tokens only in `web/static/css/drishti_tokens.css` (gradient chrome in `drishti_gradients.css`); ten themes selected by
`data-theme` on `<html>`: `terminal` (default, the navy/amber of the mockups), `light` (parchment), `wallstreet`
(Bloomberg Terminal colours: black ground, amber data, yellow commands, green/red ticks, blue highlight — colours
only, not fonts), `blue`, `green`, `crimson`, `crimson-dark`, and `terminal-green`, `terminal-crimson` and `terminal-blue` (the Terminal
look in one dominant hue: phosphor green with lime, crimson with blush, azure with periwinkle). The choice is saved to the user's account and
mirrored in `localStorage['drishti.theme']` for first paint. Contrast is checked by `drishti-console/tests/test_contrast.py`
(≥ 4.5:1 for every token used as text — ink, muted, faint, link, accent, accent-strong, pos, neg, ok, warn, bad — on
bg, bg-2, surface and surface-2, and for `--d-on-accent` on accent fills; ≥ 3:1 non-text). Layout is checked at 390,
1600 and 2560 px by `drishti-console/tests/test_page_widths_browser.py` (no page scrolls sideways; images fit their column).

**Pages**

| Route | Page |
|---|---|
| `/` | Landing: hero animation, stats strip, "one grammar" section, capabilities, mockup showcase |
| `/t` | Terminal home: command line, recent entities, watch list |
| `/go?q=` | Resolves a command and redirects to its view (or to search) |
| `/v/{kind}/{id}` | Entity view (server-rendered first paint, then live patches over the tab's channel) |
| `/s?q=`, `/s?words=` | Structured search results (one generic page for every kind, with the Pivot tab where the pack declares `pivot:`); plain words shown as the search they make |
| `/p/{name}` | A pack at a glance (its code typed alone, e.g. `MKT`): its kinds, mnemonics, counts and README |
| `/v/{kind}/{id}/history` | History query: the entity over time (`TRD <id> 30`) |
| `/compare/{kind}/{id}` | History: what changed in the entity between two business dates, or since a "known at" time |
| `/impact/{kind}/{id}` | F8 Impact: dependents by reverse lookup, rolled up along pack-configured fields, with summed measures |
| `/var` | VaR explorer (`VAR <scope>`): any scope computed on demand, drill-down, captures and compare (hand-built, section 7a) |
| `/exposure` | Exposure explorer (`EXP <scope>`): counterparty exposure computed for any scope (hand-built, section 7a) |
| `/datamap` | Data map (`DATAMAP`): the packs' kinds and their joins, checked against the data |
| `/func` | Functions (`FUNC`): the command directory for everyone; administrators switch commands on and off there (`/admin/commands` redirects here) |
| `/what` | Knowledge (`WHAT <term>`): what the words mean |
| `/m`, `/m/{name}` | Monitors: live watchlists |
| `/alerts`, `/alerts/all` | Alert rules (Rachana-EL, evaluated server-side on every tick, edge-triggered), pack suggestions, history; a bell and toasts on every page |
| `/w`, `/w/{name}` | Workspaces: 2–4 embedded views with follow-the-selection between panes, saved per user on the server |
| `/studio`, `/studio/reviews` | Sutra Studio (editor + live preview) and the review queue |
| `/designer`, `/designer/new`, `/designer/grid`, `/designer/outline` | The Sutra designer and its modes (`ui.designer`, off by default) |
| `/build`, `/build/d/{id}`, `/build/new`, `/build/pack/new` | The Build workbench: My designs, one design (Sutra, samples, panels, controls, form, About), New pack from schemas |
| `/build/reviews`, `/review/changes` | Designs waiting for review; write-back changes waiting for an approver |
| `/forms`, `/form/{pack}` | Query by form: the forms home and one pack's form |
| `/reports`, `/reports/all` | Saved and scheduled reports, and BI reports |
| `/bi`, `/bi/catalogue`, `/bi/workspaces`, `/bi/models`, `/bi/d/{id}`, `/bi/r/{id}`, `/bi/designer`, `/bi/design/theme/{id}`, `/bi/m/{dataset}` | Rūpaka: home, catalogue, workspaces, models, datasets, report viewer (boards are report pages; `/bi/b/{id}` opens the viewer), BI Studio's report and model designers and the theme designer, Model view |
| `/bi/datasets`, `/bi/w/{workspace}`, `/bi/environments`, `/bi/run/{id}` | BI datasets, one BI workspace, environments (export and import a workspace), where a BI run opens |
| `/bi/notebook`, `/bi/transform` | the Notebook (SQL cells run by the server, Python cells on its compute pool), the Transform editor (Python data prep step by step) |
| `/inbox`, `/share/{id}` | The inbox and the page a shared link opens |
| `/help`, `/help/{slug}`, `/help/search`, `/help/examples` | Help centre: guides and the repository's docs rendered in-app; F1 opens help for the current screen; the Rachana examples |
| `/about` | Version and build, Java, uptime, loaded Sutras, source health, licence and notices; for administrators, the link to the competitive analysis (Help, `competitive-analysis`; `/about/competitive` leads there) |
| `/login`, `/logout`, `/account` | Sign-in and sign-out; own profile, settings and password |
| `/asof` | Sets the business date (and "known at") for the session, then returns to the page |
| `/servers` | One console, many servers: the list of servers and the one this browser works with |
| `/admin/users`, `/admin/roles`, `/admin/tokens`, `/admin/audit`, `/admin/access`, `/admin/health`, `/admin/caches`, `/admin/edits` | User administration, roles, API tokens, audit log, who looked at what, health of everything, caches, write-back edits |
| `/admin/packs`, `/admin/packs/{name}`, `/admin/connectors`, `/admin/connectors/{name}`, `/admin/loads` | Packs (switch, load, deploy, registry), connectors, data loads |
| `/admin/embedding`, `/admin/collab`, `/admin/ai`, `/admin/bi`, `/admin/bi/runs`, `/admin/bi/usage`, `/admin/compute` | Host applications for embedding, collaboration compliance, AI and MCP, BI settings, BI runs and usage, compute pools and requests |
| `/admin/lakes`, `/admin/artifacts`, `/admin/net` | Delta lakes and their maintenance, installed artifacts (install and uninstall safely), Drishti\*net (members, files, capability map, security, access, routing) |
| `/api/channel` | The browser's single live channel, shared by its tabs (section 10); `/api/channel/who` the session it carries |

**Phones and tablets.** The console is responsive down to 360 px, with no separate app: views stack into one
column and the strip shows two figures per row; tables scroll inside their panel; F-keys become a swipeable row of
tap targets; inputs are 16 px so iOS does not zoom on focus; safe-area insets respect the notch; a web-app manifest
lets people add Drishti to the home screen.

**Hero animation** (`web/static/js/drishti_landing.js`, canvas 2D, no library): raw JSON fragments drift in, are drawn into
the `{ ◉ }` eye, and emerge as assembled terminal panels that then tick. Colours come from CSS tokens at runtime;
it plays once on view, has a Replay button, shows a static final frame under `prefers-reduced-motion`, and pauses
when hidden.

**Renderer contract** — each panel kind has a Jinja macro (`_macros/panels.html`) for first paint. Live patches
reuse it: the console renders a changed non-chart panel with the same macro and sends the HTML; `drishti_live.js` swaps that
one panel, updates strip cells in place, and hands chart panels new data — so a tick never re-renders the whole view. The kind-to-macro table and worked examples are in
[section 7a](#7a-how-a-screen-is-made-generic-not-bespoke).

---

## 18. Security and identity

- **Sign-in.** Users, roles, preferences and the audit trail live in the **server** (`drishti-identity`), in a JPA
  database: SQLite by default (one file) or PostgreSQL. The console verifies a sign-in by calling
  `POST /api/v1/auth/login` with its own service token and never sees password hashes (PBKDF2). It then keeps a
  signed, expiring session cookie. Details, storage and administration: [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md).
- **Token boundary.** For every server call the console mints a short-lived HS256 token (`auth.token_secret` =
  server `drishti.security.secret`, `DRISHTI_TOKEN_SECRET`; 300 s). With `drishti.security.enabled`, `TokenFilter`
  requires it on every `/api/**` request (algorithm pinned, constant-time check) and answers 401 problem+json
  otherwise. Filters decide on the decoded path the dispatcher serves (`RequestPaths.routed`), and `PathGuard` first
  refuses, with `400 DRS-5001`, any request line under `/api` or `/actuator` that does not spell that path plainly
  (path parameters, needless `%`-escapes, dot or empty segments), so `/api/v1;x/…` or `/api/%761/…` cannot skip the
  token check (QA SEC-02). With security off (local development), the caller is taken from `X-Drishti-User` with every role.
- **Single sign-on** (OIDC, ADR-014): the console runs the authorization code flow with PKCE; the server verifies the
  ID token itself against the provider's keys (`RS256`/`ES256`, no `none` or HMAC), maps groups to roles and
  provisions the user. The token boundary is unchanged.
- **Entitlements** per role and kind (`drishti.security.roles`) and per user pack (section 12). Links to entities the
  user cannot open stay visible but disabled, with the reason (MAYA rule: *visible, not hidden*).
- **Field masks.** For roles without `raw`, the fields in `drishti.security.redact` read `•••` on every path where
  their value could be seen or inferred: raw JSON (F9), search, CSV, compare, history, Calc, pivots, views (strip,
  title, tables and totals, panel records, values derived from them), the live stream, monitors, Studio previews,
  Impact, the type-ahead, phrase search and alert rules. One function (`Entitlements.redactor`) masks the documents
  before anything reads them; Rachana-EL carries the mask through (a value computed from it is masked, a condition on
  it is never true), so a masked field can be neither seen nor probed
  ([CONFIGURATION.md](../reference/CONFIGURATION.md#field-masks)).
- **Collaboration** (share with a note, comment threads, the inbox; package `com.ash.drishti.server.collab`, records in `drishti-identity`).
  What travels is a person's words and a pinned link, never data: every share, comment, notice and email is rendered **for the reader, when
  they look**, from what they may see now (`Entitlements.mayReach`, `maskedValues`, `CommentRenderer`), and the records are tamper-evident
  (`HashChain`). The whole path is worked through in [COLLABORATION.md, Share and Discussion end to end](COLLABORATION.md#share-and-discussion-end-to-end), the design and
  its as-built notes are [COLLABORATION.md](COLLABORATION.md), the decision is ADR-020.
- **Console** CSP without `unsafe-inline`; all assets same-origin.

---

## 19. Observability

- **Health.** `GET /api/v1/admin/health` (Admin → Health) reports each connector's state, traffic, latency and last
  error, each pack's connectors and Sutras, live streaming and the server, with an overall `OK`, `DEGRADED` or
  `DOWN`. Spring actuator serves `/actuator/health` (with liveness/readiness probes), `/actuator/info`,
  `/actuator/metrics` and `/actuator/prometheus`.
- **Metrics** (Micrometer → Prometheus): `drishti_view_seconds` (time to build a view, p50/p99),
  `drishti_live_streams`, `drishti_live_topics`, `drishti_live_frames_total`, `drishti_live_latency_p99_milliseconds`, plus the
  standard JVM, HTTP and executor metrics. Cache sizes and hit rates are on Admin → Caches. A Grafana dashboard
  ships in `deploy/grafana/`.
- **Per-view timings** are in every ViewModel (`timings`), and Admin → Health shows per-connector latency.

---

## 20. Quality rules

- **Every source file ≤ 1500 lines** (UX templates, styles and scripts excepted) — enforced by `SourceFileSizeTest`
  (`drishti-it`) and `drishti-console/tests/test_assets_policy.py`.
- **Copyright header on every file** — enforced by `LicenseHeaderTest` (`drishti-it`); `tools/license_headers.py`
  adds missing ones.
- **ArchUnit** (`ArchitectureRulesTest`): `drishti-api` is free of Spring and of every internal module; `common`,
  `rachana`, `inference`, `graph` and `identity` never see `engine` or `server`; `engine` never sees `server`;
  `@RestController` only in `drishti-server`; no field injection; no `Serializable`.
- **Nothing unbuilt looks built:** every figure, panel, menu item, help page or decision that depends on unbuilt work is in the
  under-construction registry (`config/under-construction.yaml`, [UNDER_CONSTRUCTION.md](../reference/UNDER_CONSTRUCTION.md)) and shows
  *Under construction*, or is hidden; one mechanism (the matched Sutra is rewritten; the console reads the same file), enforced by
  `UnderConstructionTest` (drishti-rachana) and `test_under_construction.py` (`tools/under_construction.py --check`).
- Golden ViewModel tests for the reference entities (`ViewPipelineTest`, `ReferenceSutrasTest`); plugin contract tests
  from `drishti-testkit`; a JMH benchmark of the hot path in `drishti-benchmarks`.

## 21. Key decisions

| # | Decision | Why |
|---|---|---|
| D1 | Backend is one Spring Boot application (never an embedded library); Python console | Single deployable, Spring wiring/config/observability everywhere; domain objects still testable without a context |
| D2 | Layout = Sutra ⊕ inference, cached by shape fingerprint | Unknown data renders immediately; cost paid once per shape |
| D3 | Sutras are YAML files (`*.sutra.yaml`, `rachana: 1`) with a compiled, side-effect-free EL (ADR-003, ADR-017) | Reviewable, diffable, safe; ordinary YAML tooling and a served JSON Schema; no scripting in layouts |
| D4 | Server returns ViewModel, not HTML | Same model feeds console, API clients and golden tests |
| D5 | SSE (not WebSocket) for live, one channel per browser (shared by its tabs through a SharedWorker or an elected tab) | One-way and proxy-friendly; a reconnect simply receives a fresh `view` event, so nothing is replayed; one channel per browser leaves five of the browser's six connections to pages, however many tabs are open |
| D6 | Vendored front-end assets, no build pipeline | Air-gapped desks; MAYA/Pravaha practice |
| D7 | Industries are packs that inherit; users live in the server | Neutral core; any number of consoles share users (ADR-009, ADR-010, ADR-015) |

The full records are in [`docs/architecture/adr/`](adr/).

## 22. Testing architecture

**The pyramid, from the bottom.**

| Layer | What | Where |
|---|---|---|
| Unit and module suites | JUnit 5 in every Maven module (about 900 test classes): the grammar, inference, the engines, the stores, the connectors' contract tests from `drishti-testkit`, golden ViewModels of the reference entities (`ViewPipelineTest`, `ReferenceSutrasTest`); container suites (Testcontainers) for the JDBC, MongoDB, Redis, Cassandra and Aerospike plugins | `*/src/test/java` |
| Rule tests | ArchUnit (`ArchitectureRulesTest`, `OneGrammarRulesTest`) and the repository rules of `drishti-it`: file size, licence headers, branding literals, caches weighed in bytes, no blocking under `synchronized`, connection discipline, schema parity; `TokenScopeGuardTest` | `drishti-it`, `drishti-server` |
| Parity and regression checks | the BI parity recorder and `BiParityTest` (BI's figures against recorded ones), the render parity suites (the console's macros against the client renderer, every pack Sutra), the VaR regression check of a release (bit-exact on the Java path against the previous release, within 1e-12 on OpenBLAS; [BENCHMARK.md section 17](../admin/BENCHMARK.md#17-many-var-scopes-at-once-blas)) | `drishti-server`, `drishti-console/tests` |
| Console, other lane | about 220 pytest files run in-process against a stand-in server (`conftest.py`'s `FakeBackend`): routes, templates, the help centre, the docs checks | `drishti-console/tests` |
| Console, browser lane | about 160 pytest files that drive Chromium through Playwright, against a stand-in console, a real server (`wb_live`) or a stack of their own (`*_stack.py`) | `drishti-console/tests/*_browser.py` |
| Tool tests | the `unittest` and pytest suites of `tools/` | `tools/` |

**The drill** (`tools/drill.sh`, [PACK_DEVELOPER_GUIDE.md section 2.6](../guides/PACK_DEVELOPER_GUIDE.md#26-the-drill-toolsdrillsh))
is how work reaches `main`. Stage 1, the gate: the static checks, the docs and help console tests, the quick file checks (every test
marked `static`: it reads files and starts nothing, so it runs here and never in a lane) and one Java 21 build that runs only the rule
tests, stopping at the first failure. Stage 2, at once: the console's browser lane and other lane, the tool tests, and the
Java 21 and Java 25 verifies each in a copy of the committed tree. A failed console test runs once more, file by file; one that passes
then is logged as a flake. Nothing is pushed unless everything passes.

**How the tests share the machine.** The other lane runs on up to eight pytest-xdist workers, a file per worker. The browser lane
runs on as many workers as the memory available holds (2.5 GB each, after the Java verify's share; at most the CPU threads less two
and at most twelve), handed out **by test**: a file that keeps a server or state between its tests is one group on one worker, and a
small independent test marked `spread` is a unit of its own that any worker takes. Each worker starts its expensive things once and
reuses them: one Chromium (its contexts reused per width, reset between tests), one stand-in console, a pool that keeps at most one
idle real server, and the pack ViewModels bound once per run for every worker. Each worker's scratch ports are a block of their own.
The lane's memory is sampled through the run.

**Rules for writing tests.** Small and independent (a test that can run alone on any worker is marked `spread`); no fixed pause over
100 ms in a browser test: wait on the page's settled signal (`Drishti.settled`: charts drawn, nothing aria-busy, no request
unanswered, the page quiet) or on the condition itself; real servers only through the pool, never one per test; 300 s per browser
file, or it is split or listed with its reason (the drill flags any other); a page is reloaded once, and only when Chromium cancelled
its requests because the network changed (`net::ERR_NETWORK_CHANGED`); browser tests never run beside the Docker-based suites, whose
containers change the network under Chrome. `test_browser_rules.py` checks the rules on the source. The full design, its
measurements and the numbered rules: [TEST_HARNESS.md](TEST_HARNESS.md).

## 23. The pivot engine

**Status: phase P1 partly built (2.9.11.a, unreleased) and phase P3 partly built (2.9.11.b, on `wip/pivot-c`, its tests written and
not yet run: sketches, rollups and aggregate awareness, progressive answers, the cluster's partial states, refined estimates, the benchmark
harness); the rest design, not built.** Built, and described for users in
[PIVOT_GUIDE.md](../guides/PIVOT_GUIDE.md): the module `drishti-bicompute` (the core with declarative cell sets, the provider and function
SPIs, the mergeable and exact holistic built-ins, the GROUPING SETS compiler and the DuckDB tabular provider over Export data's tables, the
ordered merge, the partial-state cache, the split-and-merge harness, xlsx and CSV export), the VaR provider on the new SPI, the API, the
CLI and the screen of mock-up A. Everything the build checklist ([section 24](#2324-build-checklist)) leaves unticked is not built. The plan's
rows are in [IMPLEMENTATION_PLAN.md, 2.9.11.a](IMPLEMENTATION_PLAN.md#2911a-the-pivot-engine-then-the-mr-cube).

**What the product owner asked for (2026-11-02, in their words):** "build capability of a super scalable very generalized pivot
capability. Think of MS Excel but more capable. Built in aggregation functions but people can also supply custom aggregation functions.
And it should operate on extremely large volume of data." Then: the MR cube should use it; custom functions may be in Python; "pause MR
cube then and build this capability first, then come back to MR cube". And: "we need to do better than Excel and better than anything out
there" ([section 10](#2310-beyond-anything-out-there)).

**Read with:** [LENSES.md section 6.4](LENSES.md#64-the-shared-cube-core-mr-and-ccr) (the shared cube core this
engine grows from), [LENSES.md section 7](LENSES.md#7-the-ccr-cube-a-counterparty-credit-risk-cube-lens-that-answers-what-fis-adaptiv-answers-and-shows-why) (the second cube on it), [BI_GUIDE.md](../guides/BI_GUIDE.md) (the BI
query engine on DuckDB, rollups, true totals, run cards, Python measures), [BI_LANGUAGE_REFERENCE.md](../guides/BI_LANGUAGE_REFERENCE.md)
(DEL, the expression language), [section 24](#24-compute-workers-python-and-java-libraries-approved-pinned-and-isolated) (the compute worker pools), [CLUSTER_DEPLOYMENT.md section 5](CLUSTER_DEPLOYMENT.md#5-var-sharding-in-depth-built)
(scatter and gather of partial sums across Drishti\*net), [section 25](#25-compute-engines-on-or-off-each-with-its-own-configuration-file) (one file and one switch per engine),
[DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk) (data that may never touch a disk), [RESOURCES_AND_SCALE.md](RESOURCES_AND_SCALE.md) (memory
budgets and busy protection) and [CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md).

### 23.1 The idea in one page

A **pivot** is a question of the form "these measures, broken down by these dimensions on rows and columns, under these filters, as of this
date". Excel answers it for the rows that fit in a sheet, with sums that are right and totals that are wrong whenever a measure does not
add up. Drishti answers it for any amount of data, for every measure it has (a sum of notional, a distinct count of counterparties, a
99% VaR, an FRTB charge, a function a quant wrote this morning), and every total is right.

1. **One pivot engine for all of Drishti.** The shared cube core of the MR and CCR cubes ([LENSES.md section 6.4](LENSES.md#64-the-shared-cube-core-mr-and-ccr))
   grown up: it owns the view, the cells, the totals, the budget, the cache, provenance, saved views, explain, export and the cluster
   merge. It never computes a figure itself: **measure providers** behind one interface do.
2. **Providers.** A **tabular** provider (DuckDB scans of Delta, Iceberg and Parquet, the BI engine Drishti already runs) for any table
   of any pack; the **VaR vector** provider (per-cell statistics from summed scenario vectors, in Java); **FRTB-SA** and **SIMM** formula
   providers; later the **CCR cube's** (EE, PFE, EAD). The MR cube and the CCR cube become **sets of providers and their landings** on
   this engine, not engines of their own.
3. **Three kinds of measure**, and the kind decides how a total is made: **mergeable** (sum, count, minimum, maximum, average as sum and
   count, variance, weighted average: totals merged from the cells' small states, exactly); **sketch** (distinct count, percentiles,
   top N: bounded states that merge with a stated error, shown "≈", exact on request); **holistic** (VaR, ES, exact median: each cell and
   each total recomputed from its own members, bounded by the cell budget, busy protection and the cell cache).
4. **Custom aggregation functions** with one four-step contract, **start → add (a columnar batch) → merge → finish**, written as a
   formula of built-ins (DEL), a DuckDB SQL macro, Python (vectorised over Arrow batches in a sandboxed worker pool on each node), or a
   Java plug-in. A function is tested by a harness that fails it when its split-and-merge answer differs from its one-pass answer; it has a
   **playground**, versions, an author, an audit trail and an administrator's approval before anyone else may use it. Official risk
   measures stay Java engines; custom functions are user analytics, labelled with their author and version.
5. **Scale.** Each node runs its own DuckDB executor over the files it is given; Drishti\*net scatters the plan and gathers **partial
   states per cell**, merged in a fixed order (as VaR shards do today). Column pruning and Parquet statistics skip what is not needed;
   tables are laid out by the dimensions people pivot by; **rollups** are refreshed on load and chosen automatically; sketches keep
   distinct counts and percentiles cheap; answers are **progressive** (≈ first, then exact); every large question gets a **scan estimate**
   before it runs ("≈180 GB, ≈2 min on 3 nodes") with cheaper options. DuckDB's limits are stated honestly
   ([section 9.10](#23910-duckdbs-limits-stated-honestly)); a distributed SQL backend is a future provider, not part of this design's build.
6. **Excel's pivot, all of it, and more:** drag fields to rows, columns, values and filters; calculated fields; show values as % of
   total, % of parent, running total, difference from, rank; date and number grouping; slicers; subtotals; conditional formats;
   drill-through to the rows; and beyond Excel: no row limit, correct non-additive totals, cell provenance, explain any difference, time
   travel, what-if, live cells, entitlements and masks applied before aggregation, saved and shared views, the cluster. Export to xlsx
   **with a native PivotTable**, csv, Arrow and Power Query.
7. **An engine with its own switch:** `pivot`, in `config/engines/pivot.yaml` ([section 25](#25-compute-engines-on-or-off-each-with-its-own-configuration-file)), off means it
   does not exist on that server.
8. **Expand and collapse better than Excel** ([section 11](#2311-expand-and-collapse-nested-levels-better-than-excel)): nested rows and
   columns computed lazily per opened node, "expand by" a different dimension per member, ragged hierarchies, a correct Others, down to
   the trades inline; and **pick-and-choose charts** linked both ways to the pivot ([section 14.3](#23143-pivot-charts-pick-and-choose)).
9. **BI runs on it** ([section 12](#2312-bi-on-the-pivot-engine)): BI's matrix, totals, rollups and the semantic model's measures move onto
   the engine behind a parity gate, so a measure defined once works in BI, the pivot screen, the cubes and every export.
10. **One library**, the Maven module `drishti-bicompute` ([section 13](#2313-the-module-drishti-bicompute)): plain Java, no Spring; every
    component that aggregates calls it, and none re-implements aggregation.
11. **Export to Excel and CSV**, complete ([section 15](#2315-export)): the pivot as shown with its outline, the underlying rows, chart
    data or the definition; formulas only where right, a Provenance sheet, Excel's limits handled in words, big exports streamed.

### 23.2 What Drishti has today

Read from the guides and the code on 2026-11-02. The engine reuses every row of this table; none of it is rebuilt.

| What | Where | Built | What the pivot engine takes from it |
|---|---|---|---|
| BI query engine: datasets, DEL compiled to parameterised SQL, row rules and masks, embedded DuckDB (`AnalyticEngine`, `DuckDbEngine`) | [BI_GUIDE.md section 30](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules) | yes | the tabular provider's compiler, security and connection handling |
| Layers and rollups: a rollup stores the **components** of its measures (sums, counts, minimums, maximums; `avg` as sum and count) per date and group, and a query is routed to the smallest layer that covers it | [BI_GUIDE.md, modes, layers and rollups](../guides/BI_GUIDE.md#7-modes-layers-and-rollups) | yes | aggregate awareness and the rollup store, extended to sketches |
| True totals: a total of a measure that does not add up is one more query per level, never the sum of the figures shown | [BI_GUIDE.md, true totals](../guides/BI_GUIDE.md#32-true-totals) | yes | the rule, generalised to every provider |
| Runs and run cards: a range estimate from Delta statistics, "why it is large", confirm above a minute or 500 million rows, run in background | [BI_GUIDE.md](../guides/BI_GUIDE.md) (runs) | yes | the scan estimate and the background job |
| The `matrix` visual (rows × columns, subtotals at every level) | [BI_GUIDE.md](../guides/BI_GUIDE.md) | yes | replaced by the pivot engine's answer (it keeps its YAML; [section 6.2](#2362-the-tabular-provider-duckdb)); the console's own slice of an extract is cut: slicing is the Pivot's, one analysis path, Pivot then Report |
| The Pivot tab on a table or ladder (Rachana `pivot`) | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#pivot-pivot-on-a-table-or-ladder), [USER_GUIDE.md](../guides/USER_GUIDE.md#pivot-slice-a-table-your-way) | yes | becomes a pivot view over the panel's rows |
| Python measures in BI: `compute(table, params)` per group, sandboxed (isolated process, empty environment, no network, scratch folder only, CPU, memory, output limits, package allow-list) | [BI_GUIDE.md section 34](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python), [its sandbox](../guides/BI_GUIDE.md#7-the-sandbox-exactly) | yes | the sandbox's guards, unchanged; a Python measure is a holistic custom function |
| Compute worker pools (`drishti-compute`): fork-server per worker on Linux, warm workers on Windows, Arrow IPC scratch transport, limits, fan-out over nodes | [section 24.7](#247-the-worker-pool) | yes (C1 to C3) | the per-node pool that runs Python custom functions |
| VaR sharding: partial vectors per shard added in shard order, bit-equal to the whole (`ShardedMeasure`, `ShardGather.Combine`) | [CLUSTER_DEPLOYMENT.md section 5](CLUSTER_DEPLOYMENT.md#5-var-sharding-in-depth-built) | yes | the cluster merge, lifted into the core for every provider |
| Busy protection: one admission gate refusing new heavy work politely | [RESOURCES_AND_SCALE.md section 5.7](RESOURCES_AND_SCALE.md#57-busy-protection-refuse-politely-before-the-heap-runs-out), [OPERATIONS.md section 12.5](../admin/OPERATIONS.md#125-busy-protection-refuse-politely-never-fall-over) | yes (unreleased) | every pivot passes the gate |
| The in-memory lake: tables on `mem://`, DuckDB's spill switched off for them, the never-on-disk guard at every sink | [DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk) | yes (unreleased) | the rule that a memory-only table is never spilled or written for user code |
| Why VaR moved (RO1): exact steps that add up to the change | [LENSES.md section 5.4.1](LENSES.md#541-ro1-var-change-explanation) | yes | the method behind "explain the difference" ([section 10](#2310-beyond-anything-out-there), item 1) |
| The shared cube core and the MR source, on the paused branch `wip/mr-cube` | the branch (not merged) | built there, paused | the code this engine grows from ([section 7](#237-from-the-mr-cubes-core-to-the-pivot-engine-what-is-kept-what-changes)) |

### 23.3 One engine, many measure providers

```
 console grid · ViewModel · drishti.py pivot · REST · Arrow · xlsx            (people and programs ask; nothing computes in the browser)
                       │  a pivot view (dimensions, measures, filters, date, lens)
                       ▼
 ┌──────────────────────────── the pivot engine (the core) ────────────────────────────┐
 │ view → plan: cell keys (cells, row and column totals, grand total), per measure its kind │
 │ budget · busy gate · cache of partial states · estimate · progressive answer           │
 │ cluster: scatter the plan, gather partial states per cell key, merge in a fixed order    │
 │ finish → figures · show-as · provenance ids · explain · references · export              │
 └───────┬───────────────┬────────────────┬──────────────────┬───────────────┬───────────┘
         │ evaluate(cell keys, functions) → partial states per cell key (one interface)       │
   ┌─────▼─────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌───────▼───────┐  ┌────▼──────────┐
   │ tabular   │  │ VaR vector  │  │ FRTB-SA,    │  │ CCR (later):  │  │ external SQL  │
   │ DuckDB    │  │ (Java, in   │  │ SIMM        │  │ EE, PFE, EAD  │  │ backend       │
   │ Delta,    │  │ memory)     │  │ (formula)   │  │ (path matrix) │  │ (future)      │
   │ Iceberg,  │  └─────────────┘  └─────────────┘  └───────────────┘  └───────────────┘
   │ Parquet   │        custom functions: DEL formula · SQL macro · Python (worker pool) · Java plug-in
   └───────────┘
```

**Terms.** A **view** is what the person asked. A **cell key** is a cell's coordinates (dimension → value, or "all" for a total) plus
the view's filters; it names a cell without listing its members. A **function** is an aggregation with the four steps of
[section 8.1](#2381-the-contract-start-add-merge-finish). A **partial state** is what `add` and `merge` produce for one cell key on one
node. A **provider** turns cell keys and functions into partial states, over the data it owns. The **core** merges, finishes, shows.

**Why one engine.** The MR cube, the CCR cube, BI's matrix and the Pivot tab each draw a pivot; four
pivots would mean four sets of totals, four budgets, four exports and four ways of being wrong. One engine means one rule for a total,
one provenance, one Excel export, one test suite, one cluster merge.

### 23.4 The pivot model

#### 23.4.1 The view

```yaml
# a pivot view (what a saved view stores; the API takes the same as JSON)
pivot: 1
source: trading.trades                 # a provider's source: a BI dataset table, a lake table, or a cube (mr, ccr)
date: 2026-10-30                       # a business date, a range, or two for a comparison (section 10.2)
as-of: { version: latest }             # or { version: 1842 } (Delta) / { snapshot: 7731... } (Iceberg) / { at: 2026-10-30T18:00Z }
rows: [LEGAL_ENTITY, DESK]
columns: [{ field: TRADE_DATE, group: month }]
values:
  - { measure: sum(NOTIONAL_USD), as: Notional }
  - { measure: count_distinct(COUNTERPARTY_ID), exact: false }    # a sketch: shown ≈ with its error
  - { measure: var(99, 1d), provider: var }                        # holistic: each cell its own VaR
  - { measure: fn:asha/trimmed_mean@3(PNL_1D, 0.05) }              # a custom function, author and version named
  - { measure: sum(NOTIONAL_USD), show-as: { percent-of: parent-row } }
filters: { PRODUCT_GROUP: [RATES, FX], TRADE_STATUS: { not: [MATURED] } }
slicers: [CURRENCY]
totals: { rows: true, columns: true, subtotals: all }
currency: { report: USD, fx: { source: market-data, at: close } }
sort: { by: Notional, desc: true }
top: { field: DESK, n: 10, others: true }                          # never silent: "top 10 of 41, the other 31 in Others"
```

#### 23.4.2 Dimensions: typed and ordered

A provider publishes a **catalogue** of its dimensions. Each has a type and an order, so the grid sorts, groups and labels the same way
everywhere:

| Type | Examples | Grouping offered | Order |
|---|---|---|---|
| string | `DESK`, `CURRENCY`, `COUNTERPARTY_ID` | manual groups ("G10", "EM"), top N with Others | a configured order, else by value |
| date and time | `TRADE_DATE`, `BUSINESS_DATE`, `MATURITY_DATE` | day, week, month, quarter, year, the bank's calendar and fiscal year, buckets from today | by time |
| number | `NOTIONAL_USD`, `TENOR_YEARS`, `RATING_SCORE` | buckets by width, by edges (`[0, 1, 2, 5, 10, 30]`), quantile buckets (a sketch) | by value |
| hierarchy level | MGMT (bank → division → desk → book), LEGAL, limit letters, risk-factor trees | expand and collapse per level, ragged hierarchies | the hierarchy's order |
| scenario day | VaR's 509 days (the VaR provider only) | by year, by stress window | by date |

A dimension that is masked for the person cannot be used as rows or columns, and says why ([section 19](#2319-who-sees-what-and-audit)).

#### 23.4.3 Cell keys, not member lists

A view of R rows and C columns has R × C cells, R row totals, C column totals and one grand total, plus every subtotal level that is
open. The core turns them into **cell keys**: `{DESK: RATES-NY, month: 2026-10}`, `{DESK: RATES-NY, month: *}`, `{DESK: *, month: *}`.
A provider decides how to evaluate them:

- the **tabular** provider compiles the whole set into **one** `GROUP BY GROUPING SETS (...)` query (cells, each total level and the
  grand total in one scan; [section 6.2](#2362-the-tabular-provider-duckdb));
- the **VaR** provider maps each key to the set of its members (an `OrdinalSet` of positions, exactly as the MR cube does today) and sums
  their vectors;
- the **formula** providers map each key to the sensitivities of its members, summed to the formula's grain.

The **axis** (which values appear on rows and columns) is asked first, cheaply (distinct values under the filters, from the catalogue's
statistics or a small query), so the cell count is known **before** any measure runs, and a view over the budget is refused with the
count, never truncated.

#### 23.4.4 The answer

```json
{ "view": "...", "cells": [ { "key": "c:7f3a…", "coords": {"DESK": "RATES-NY", "month": "2026-10"},
    "values": { "Notional": 1.284e9, "Counterparties": {"v": 412, "approx": true, "error": "±1.6% (95%)"},
                "VaR 99 1d": 4.82e6, "trimmed_mean": 1031.7 },
    "notes": {} } ],
  "totals": [ … the same, kind: row | column | total, per level ],
  "diversification": { "VaR 99 1d": { "rows": [...], "columns": [...] } },
  "provenance": "pv:2026-10-30:9b21…", "exactness": "partial: Counterparties ≈", "layer": "rollup by_desk_month",
  "progress": { "final": true }, "took_ms": 840 }
```

Every value is either a number, or a number with `approx: true` and its error, or `null` with the reason in `notes` (masked, refused,
a shard down, a currency that cannot be converted). Nothing is blank without a reason.

### 23.5 Three kinds of measure, and how each total is made

#### 23.5.1 The rule

**A cell or a total is made from its members, never from other cells' figures.** What differs by kind is how cheaply:

| Kind | State | Merge | A total is | Marked |
|---|---|---|---|---|
| **Mergeable** | small and fixed (a sum; a sum and a count; Welford's count, mean and M2) | exact (associative and commutative) | the merge of its cells' states: no second scan | exact |
| **Sketch** | bounded (an HLL register array, a KLL or t-digest summary, a Space-Saving list) | approximate, within a stated error | the merge of its cells' sketches | ≈ with the error; **exact on request** |
| **Holistic** | unbounded (every value, or a vector per member) | none that is small, or merge is exact only on inputs | recomputed from its own members | exact (or refused over the budget) |

VaR and ES are **holistic statistics over mergeable inputs**: a scope's state is its summed scenario P&L vector (509 values, 4 KB), which
merges exactly by element-wise addition, and the statistic (a rank, a tail mean) is taken per cell. The MR cube computes every cell and
total from its own members' vectors in a fixed order, so each equals the VaR engine's answer for that scope to the bit; the engine keeps
that ([section 6.3](#2363-the-var-vector-provider)). An exact median has no small state at all: it needs the values.

#### 23.5.2 The built-in functions

| Function | Kind | State | Finish | In Excel's export |
|---|---|---|---|---|
| `sum(x)` | mergeable | Σx (Kahan-compensated float64, or decimal for money columns declared decimal) | Σx | SUM; in a native PivotTable a data field |
| `count(x)`, `count()` | mergeable | n | n | COUNT; a data field |
| `min(x)`, `max(x)` | mergeable | the extreme | it | MIN, MAX; data fields |
| `avg(x)` | mergeable | (Σx, n) | Σx / n | a calculated field `sum_x / count_x` (right at every level) |
| `wavg(x, w)` | mergeable | (Σwx, Σw) | Σwx / Σw | a calculated field |
| `var(x)`, `stddev(x)` (sample and population) | mergeable | (n, mean, M2), merged by Chan's formula | M2 / (n − 1) | values only |
| `ratio(sum(a), sum(b))` and any DEL of mergeable parts | mergeable | each part's state | the formula | a calculated field when every part is a sum |
| `first(x by t)`, `last(x by t)` | mergeable | (t, x) of the extreme t | x | values only |
| `distinct(x)` (`count_distinct`) | holistic, exact; ≈ form: sketch (HLL) | the values; ≈: 2^p registers (p = 12: 4,096 bytes) | the count; ≈: the HLL estimate | values only |
| `percentile(x, q)`, `median(x)` | holistic, exact; ≈ form: sketch (KLL, k = 200) | the values; ≈: a KLL summary | the quantile; ≈: the rank query | values only |
| `top_n` of a level | the engine's Top N, exact; ≈ form: sketch (Space-Saving, capacity 10 × n), state built, not yet used by a level | counters | the n largest, each with its error bound | values only |
| `mode(x)` | holistic | the values | exact | values only |

**One name each; the sketch is a form, not a function** (FUNCTION_CATALOGUE.md, 2026-11-03). Which form answers is the request's
(`"exact": true` or `false`) or, by default, the size's (`sketches.approximate-above-rows`); a sketched call's id carries `≈`. The earlier
spellings of this section (`median(x, exact)`, separate approximate names) are not used.
| `var(c, h)`, `es(c, h)`, stressed VaR, component VaR | holistic (VaR provider) | the scenario vector | rank or tail mean | values only, with the vector sheet when asked |
| `sbm()`, `drc()`, `simm()` | holistic (formula providers) | sensitivities at the formula's grain | the regulatory formula | values only |

Error statements, measured and printed with every ≈ value: HyperLogLog's relative standard error is about 1.04 / √m for m registers
(Flajolet and others, 2007), so 1.6% at m = 4,096 and 0.8% at m = 16,384 (p = 14); a KLL sketch with k = 200 has a normalised rank
error of about 1.65% (Apache DataSketches); Space-Saving's counts are over-estimates by at most the smallest counter (Metwally and others,
2005). The configured precision is a setting ([section 16](#2316-the-engine-switch-and-its-configuration)), and the stated error is the
one the test suite measures on the site's data, not only the formula.

#### 23.5.3 "Exact on request" without giving up scale

- **Distinct count.** Exact needs the set of values. Where the table is partitioned or clustered by the counted column (counterparty on
  the CCR tables, for example) per-node exact counts add up and the engine says so; otherwise each node sends its distinct values per
  cell up to a budget (default 5 million values a view), and above it the engine refuses exact with the size and keeps ≈.
- **Percentile and median.** Exact by **iterative selection**: the KLL sketch bounds the answer to a narrow value window; one more pass
  counts the values below the window (a mergeable count) and collects the few values inside it; the exact rank is found among them. Two
  passes on most data, a third when the window holds too many values; every pass is mergeable, so it scales across nodes. The answer
  says how many passes it took.
- **VaR and ES** are always exact (they are computed from vectors, never sketched).

#### 23.5.4 Correctness Excel does not have

- **Non-additive totals are right**: by the rule above, a total is never the sum or average of the figures shown.
- **Currencies are explicit**: a money measure has a currency. A sum across currencies is converted at a named FX source and time
  (`currency.fx`), shown in the cell's provenance, or refused as "mixed currencies" with the list; never added raw.
- **Units are respected**: a percentage, a rate or a count of days is not summed; the catalogue marks a column's unit and the editor
  offers only the functions that make sense for it (a sum of a percentage is offered as an average, with a word why).
- **Masks before aggregation**: row rules and masks are applied in the compiled query before grouping; a masked column cannot be a
  dimension, and an aggregate-only role may set a **minimum number of members per cell** (default 1; a site may set 5) so a cell cannot
  reveal one trade.
- **No silent truncation**: the cell budget refuses with the count; top N always shows "of how many" and an Others row; a cell a shard
  could not answer says so (never a partial figure).
- **Exact or ≈, always shown**: every value carries its exactness; the column header shows the mix.
- **Floating point is ordered**: merges happen in a fixed order (shard order, then cell-key order), so the same question on the same
  data gives the same digits on every run and every node count.

### 23.6 The providers

#### 23.6.1 The interface

```java
/** A measure provider: turns cell keys and functions into partial states over the data it owns (one interface for all). */
public interface PivotProvider {
    String id();                                         // tabular, var, frtb-sa, simm, ccr
    Catalogue catalogue(Principal p, SourceRef source);  // dimensions (typed, ordered), measures, functions it can evaluate natively
    Capabilities capabilities();                         // grouping sets, sketches, scope text, time travel, live, what-if, partials
    Axis axis(Principal p, PivotView view, String dimension, Budget b);   // the values a dimension takes under the filters
    Estimate estimate(Principal p, AggregationPlan plan);                 // bytes, rows, files, seconds per node (section 9.6)
    Partials evaluate(Principal p, AggregationPlan plan, Shard shard);    // partial states per cell key, for this node's part
    Rows drill(Principal p, CellKey key, Page page);                      // the member rows of a cell (drill-through)
    default Optional<String> scopeOf(CellKey key) { return Optional.empty(); }   // the cell as the provider's own scope text
    DataVersion version(SourceRef source, AsOf asOf);                     // the lake versions read; part of every cache key
}
```

An **aggregation plan** is the cell keys, the functions (built-in or custom, each with its kind and its state type), the filters, the
date, the as-of version and the lens. **Partials** are, per cell key and function, a serialised state (`PartialState`: a few bytes for a
sum, 4 KB for an HLL or a VaR vector) with the members counted and any note. The core merges partials across shards and finishes them.
A provider may also answer **final** figures for functions only it can finish (the regulatory formulas); the core then treats the cell as
holistic for that measure.

#### 23.6.2 The tabular provider (DuckDB)

**What it reads:** any table a pack declares, through three routes, chosen per table:

| Route | When | How |
|---|---|---|
| the BI layers | a BI dataset table (the `cached`, `rollup`, `live` layers of [BI_GUIDE.md](../guides/BI_GUIDE.md#7-modes-layers-and-rollups)) | the same DuckDB connection and Parquet files BI reads; nothing copied |
| direct lake scan | large Delta or Iceberg tables (hundreds of GB and more), which must not be copied into the BI columns layer | DuckDB's `delta` and `iceberg` extensions over the lake's files (local disk, EFS, S3), with the table's version pinned |
| memory-only tables | tables on `mem://` | the server's Arrow reads, registered in DuckDB as Arrow views, DuckDB's temporary directory off (as BI does today) |

**How a view compiles.** One SQL statement per view, parameterised (values bound, never spliced), with the row rules and masks first:

```sql
SELECT DESK, date_trunc('month', TRADE_DATE) AS month,
       GROUPING(DESK, month)                    AS g,          -- which grouping set a row belongs to
       sum(NOTIONAL_USD)                        AS s0,         -- mergeable state of sum
       sum(PNL_1D), count(PNL_1D)               AS s1a, s1b,   -- avg as (sum, count)
       hll_registers(hash(COUNTERPARTY_ID), 12) AS s2          -- a sketch state (section 9.5)
FROM   lake_scan('trading.trades', version => ?) t
WHERE  <row rules> AND PRODUCT_GROUP IN (?, ?) AND TRADE_STATUS <> ?
GROUP BY GROUPING SETS ((DESK, month), (DESK), (month), ())
```

The provider returns **states**, not figures, so a node's answer can be merged with another node's, cached, and finished by the core.
Functions DuckDB has natively (`sum`, `count`, `min`, `max`, `avg`, `weighted_avg`, `var_samp`, `approx_count_distinct`,
`approx_quantile`, `quantile_cont`, `approx_top_k`) are used on a single node when the answer is final there; their **mergeable state
forms** (sums and counts, Welford parts, sketch states) are used whenever partials must be merged across nodes or cached. A
`direct` table (a PostgreSQL or DuckDB connector) gets the same SQL pushed to its source when every function is native to it.

**What it replaces.** BI's `matrix` visual and the Pivot tab keep their YAML and their screens, and ask the pivot engine
instead of their own code paths (phase PB, [section 12](#2312-bi-on-the-pivot-engine) and [section 22](#2322-phases-and-sizes)); their totals already follow the true-totals rule, so their
figures do not change, which a test proves on every shipped sample report.

#### 23.6.3 The VaR vector provider

The paused MR cube's `VarCubeSource` becomes this provider, unchanged in its arithmetic:

- **Members**: the positions (or trades) of the business date as the person sees them; a cell key maps to an `OrdinalSet` by the
  dimension partitions it already builds (desk, book, limit letter, any hierarchy level, any column of the vectors).
- **State**: the summed scenario P&L vector of the cell's members (and the stressed vector when asked), summed in the engine's fixed order;
  on a sharded date one call per owner for all of a view's cells (`VarBook.vectors`, `ShardedVectors.sumSets`, built on the branch).
- **Finish**: VaR, ES, stressed VaR at the lens's confidence and horizon; component VaR as a cell's share of the grand total's tail
  scenario; diversification shown beside each total (Σ cells − total).
- **Totals**: each total is its own set's vector, summed from members, so it equals `GET /api/v1/var/scope` for that scope to the bit
  (the branch's tests already prove it). Merging cells' vectors would be faster but equal only to the cent; it is not used for official
  figures (decision 3, [section 23](#2323-decisions)).
- **Scope text**: a cell's scope in VaR's grammar (`DESK:RATES-NY & CCY:USD`) so any cell can be asked of the VaR engine itself.

#### 23.6.4 The FRTB-SA and SIMM providers

State: the members' net weighted sensitivities summed per risk factor to the formula's grain (a sparse map factor → sum, added in shard
order); finish: the regulatory formula (bucket K_b, the three correlation scenarios, the charge; SIMM per CSA version). Each cell and
total runs the formula on its own members' sums. They come with the 2.9.11 FRTB-SA and SIMM engines
([FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md)) and need those engines on.

#### 23.6.5 The CCR provider (later)

State per netting set: the path matrix of MtMs (paths × time steps), netted and collateralised **per netting set first** (non-linear), then
summed across netting sets per cell; finish: EE, EPE, PFE quantiles, EEPE; EAD from SA-CCR's formula provider. A netting set is never
split across shards. Built with the CCR cube ([LENSES.md section 7](LENSES.md#7-the-ccr-cube-a-counterparty-credit-risk-cube-lens-that-answers-what-fis-adaptiv-answers-and-shows-why)), in 2.9.12.

#### 23.6.6 An external distributed backend (future, not built)

When one table is far beyond what a few Drishti nodes scan in minutes (tens of terabytes queried interactively by many people), a
**distributed SQL engine** (Trino, StarRocks or ClickHouse) can be a provider: the tabular provider's compiler emits its dialect, its
answers come back as mergeable states, and the core is unchanged. Not in this design's build; [section 9.10](#23910-duckdbs-limits-stated-honestly)
says when it is needed, and decision 8 asks whether to keep it parked.

#### 23.6.7 Documents: paths, arrays and grains

*Product owner, 2026-11-03: "our data in lake is in json format so then how pivot will work on it?"; the design below, recommended and
asked for.*

**How the lake holds an entity.** A kind of reference or numeric data (a risk vector, a scenario PV, a limit) is stored as typed
columns. An **entity document** (a trade, a counterparty, a netting set) is stored as the whole source document in a text column, `doc`
(JSON), beside a few **promoted** columns that the pack names in the connector's `layout.<kind>.columns` (for trades `TRADE_ID`,
`PRODUCT_TYPE`, `COUNTERPARTY`, `PORTFOLIO` …, holding exactly the document's values). A trade from Calypso carries nested parts inside
`doc`: `LEGS` (a list; each leg has `LEG_ID`, `LEG_TYPE`, `CURRENCY`, `NOTIONAL`, an `INDEX` object …) and `EVENTS` (a list; each event
has `EVENT_TYPE`, `EVENT_DATE`, `AMOUNT`, `CURRENCY` …).

**One notation: Rachana's paths** (product owner, 2026-11-03: *"$.ABC.DEF kind of Sutra-like notation for nested columns ... keeps our
pivot and BI in sync"*; *"Rachana grammar can be part of our pivot design"*). Every pivot field is a Rachana path or expression
([RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), the document `$`, the element `@`), exactly as a Sutra binds it and as a BI
dataset column's `path:` names it. The field list writes the path for a person who picks from it; a person may also type one.

| Path | Means | Grain |
|---|---|---|
| `$.PRODUCT_TYPE` | a top-level field | trade |
| `$.LEGS[0].CURRENCY` | the first leg's currency: one value per trade | trade |
| `$.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME` | the floating leg's index, wherever the leg sits | trade |
| `size($.LEGS)` | how many legs a trade has (a Rachana function) | trade |
| `$.LEGS[*].NOTIONAL` | every leg's notional | **leg** (the `[*]` makes the grain) |
| `$.LEGS[?@.LEG_TYPE == 'FIXED'][*].NOTIONAL` | the fixed legs' notionals | leg |
| `$.LEGS[*].CASH_FLOWS[*].AMOUNT` | every cash flow of every leg | **cash flow** (two `[*]`: a list inside a list) |
| `$.EVENTS[*].EVENT_TYPE` | every event's type | **event** |

**The grain comes from the path:** no `[*]` is the entity's grain; each `[*]` steps into a list, and the deepest list a view uses is its
grain. Two paths whose `[*]` lists are not one inside the other (`$.LEGS[*]` and `$.EVENTS[*]`) are separate lists and are never crossed.

**One parser, no second dialect.** The pivot engine parses paths with Rachana's own expression parser (the `drishti-rachana` module) and
compiles them: a path a promoted or dataset column holds reads that column; anything else becomes DuckDB JSON over `doc`
(`json_extract`, `unnest` for `[*]`, `list_filter` for `[?...]`). A Rachana function with no SQL form is refused for a pivot with the
reason, never evaluated a second way. BI datasets name document columns with the same paths: `path: $.LEGS[0].CURRENCY`, and
`each: $.LEGS` is `$.LEGS[*]`; the BI guide's examples use the `$.` form, and BI's older dotted form (`LEGS.0.CURRENCY`) is read as the
same path for existing datasets.

**Three routes from a document field to a pivot, chosen per field, fastest first:**

| Route | What it reads | Cost | When |
|---|---|---|---|
| **promoted column** | a typed column of the table | a column scan: fast at any size | the field is in the connector's `layout.<kind>.columns` |
| **dataset column** | a BI dataset's column (a `path:` into the document, or an `each:` table), extracted once per business date into the BI `cached` layer (Parquet) | a column scan after the first build | the field is declared in a dataset ([BI_GUIDE.md](../guides/BI_GUIDE.md), "Tables and columns") |
| **ad-hoc path** | DuckDB's JSON functions over `doc` at query time (`json_extract_string(doc, '$.LEGS[*].CURRENCY')`) | every document parsed for the scanned dates | anything else; the scan estimate ([section 9.7](#2397-scan-estimates-before-anything-runs)) shows the cost first, and a large one is refused with the cheaper route named ("promote `LEGS.CURRENCY`, or add it to the trades dataset") |

The person never chooses the route: the field list shows the field once, and the engine takes the fastest route that holds it. An
administrator sees, per pack, the ad-hoc paths people use most with **Promote** and **Add to dataset** buttons.

**The field list follows the document's shape.** It is built from the kind's data contract (every field, its type and meaning) and,
where the contract is silent, from a sample of documents:

```
Trade                         (grain: one row per trade)
  TRADE_ID, TRADE_DATE, PRODUCT_TYPE, COUNTERPARTY, PORTFOLIO, CURRENCY, QUANTITY, PRICE …
  Legs                        (grain: one row per leg; a list in the document)
    LEG_ID, LEG_TYPE, LEG_ECONOMIC_TYPE, CURRENCY, NOTIONAL, SETTLEMENT_TYPE
    Index                     (an object inside the leg: one value per leg)
      NAME, TENOR …
  Events                      (grain: one row per event)
    EVENT_TYPE, EVENT_DATE, AMOUNT, CURRENCY
```

A **nested object** (a value, not a list) is just more fields of its parent, named by their path (`LEGS.INDEX.NAME` shows as
*Legs › Index › Name*). A **list** is a **grain** of its own.

**One grain per view, and figures never counted twice.**

1. **The view's grain is the finest one its rows, columns and values use.** Rows *Desk* and values *Sum of Legs › Notional* is a leg-grain
   view: one row per leg is aggregated. The grid's header says so: *per leg*.
2. **A coarser figure in a finer view is counted once per parent.** *Sum of Quantity* (a trade field) in a leg-grain view sums each trade's
   quantity once, by its id, never once per leg; the header says *per trade, once*. A trade with three legs adds its quantity once. The
   engine compiles it as an aggregate over the distinct parents in each cell (a grouping on the parent id first, then the cell), which
   the split-and-merge harness tests.
3. **Two different lists are never crossed.** Legs and Events of the same trade are independent lists: a view that would put a leg field
   and an event field on the same row is refused with the reason ("Legs and Events are separate lists of a trade; pick one, or compare
   them side by side as two values"), because crossing them multiplies rows (three legs and four events would give twelve).
4. **Filters say which grain they mean.** *Legs › Currency = JPY* on a trade-grain view keeps the trades **with any** JPY leg (shown as
   *trades with a leg in JPY*); on a leg-grain view it keeps the JPY legs. A filter with *all legs* is offered for the trade grain too.
5. **Masks and row rules apply per path**: a masked field is not offered, at any depth; the trade's row rules decide its legs and events.

**Worked examples on the demo lake** (the pivot guide carries each one with its real figures, its screen, its command line and its
saved view in YAML, all produced by running it on `drishti.py lake demo`):

| Question | Rows × columns | Values | Grain |
|---|---|---|---|
| Notional by desk and month | `$.DESK` × `$.TRADE_DATE` (month) | Sum of `$.LEGS[*].NOTIONAL` | leg |
| How many trades have a JPY leg, by product | `$.PRODUCT_TYPE` | Count of trades, filter `$.LEGS[*].CURRENCY = 'JPY'` (any leg) | trade |
| Fixed against floating legs per counterparty | `$.COUNTERPARTY` × `$.LEGS[*].LEG_TYPE` | Count of legs, Sum of `$.LEGS[*].NOTIONAL` | leg |
| Quantity per desk without double counting | `$.DESK` | Sum of `$.QUANTITY` (*per trade, once*) beside Count of legs | leg, the quantity once per trade |
| Booking events this week by type | `$.EVENTS[*].EVENT_TYPE` × `$.EVENTS[*].EVENT_DATE` (day) | Count of events | event |
| VaR beside notional | Desk | VaR (the VaR provider), Sum of Legs › Notional | trade and leg: two providers side by side, each exact |

### 23.7 From the MR cube's core to the pivot engine: what is kept, what changes

The paused branch `wip/mr-cube` (at `17a4af0ea`; compiles; its fifteen server tests pass) holds the shared cube core in
its `drishti-server` package `com.ash.drishti.server.cube` (on that branch only: `CubeView` with the `MCUBE` text grammar, `CubeMeasure` with
`Kind {ADDITIVE, VECTOR, FORMULA}` and `needs`, `CubeDimension`, the `CubeSource<A>` and `Universe<A>` SPI on `OrdinalSet` members,
`CubeEngine` evaluating one batch of R × C + R + C + 1 sets with totals, diversification, the cell budget, busy protection and a
byte-weighed cache `cube.cells`, `CubeViews` for saved and shared views, `CubeExport`, `CubeController` at `/api/v1/cube`), the VaR
source (`VarCubeSource`, `VarCubeExtras`, `VarCubeController`) and the console's `cube-core.js`. The pivot engine is that code moved to
`com.ash.drishti.server.pivot` and changed as follows; the migration is the first step of phase P1.

| # | Today on the branch | In the pivot engine | Why |
|---|---|---|---|
| 1 | cells are materialised `OrdinalSet`s of members | cells are **declarative cell keys** (coordinates and filters); the tabular provider compiles them to one `GROUP BY GROUPING SETS`; the VaR provider maps keys to `OrdinalSet`s inside itself | a billion-row table cannot list its members per cell |
| 2 | SPI `Universe.aggregates(List<OrdinalSet>)` | SPI `evaluate(AggregationPlan)` returning partials per cell key; `aggregates(sets)` remains as one strategy, the VaR provider's | one interface for set-based and query-based providers |
| 3 | `CubeMeasure.Kind {ADDITIVE, VECTOR, FORMULA}` | measures are **functions** with start, add, merge, finish and a kind **mergeable, sketch, holistic**; totals merged from cell states when mergeable, recomputed only when holistic. Mapping: ADDITIVE → mergeable; VECTOR → holistic over a mergeable vector state; FORMULA → holistic | custom functions need the same contract as built-ins |
| 4 | shard partials inside the VaR source | **partial aggregation in the core**: every node returns partial states per cell key, the coordinator merges in a fixed order; VaR's vector sum is one such partial; `ShardGather.Combine` lifted from `drishti-server/.../shard` into the core | every provider scales out the same way |
| 5 | `CubeDimension` is a name and a label | **typed, ordered dimensions** (string, date, number buckets, hierarchy levels) from the provider's catalogue | grouping, sorting and Excel's date and number grouping |
| 6 | cache of aggregates keyed by the source's cache key and the set | cache of **partial states keyed by (provider data version, cell key, function)** | a changed filter or a new column reuses every cell still valid |
| 7 | `scopeOf` always expected | the cell's scope text is **optional**, a provider capability (VaR: its scope grammar; DuckDB: the `WHERE` clause shown in the provenance) | not every provider has a scope grammar |
| 8 | export writes `SUM` formulas for additive measures | export by **function kind**: Excel formulas only where a measure merges by plain sum (or is a ratio of sums); values otherwise; a native PivotTable for the mergeable ones ([section 15](#2315-export)) | a formula that re-adds a VaR would be wrong |

**Kept as they are:** saved and shared views (`CubeViews`, now `PivotViews`: a shared view opens with the reader's entitlements, never
a copy of numbers), provenance ids per answer, the cell budget and the busy-protection check, the diversification view, the console's
grid (`cube-core.js` becomes `drishti_pivot-grid.js`, the MR cube's landings keep their look), the `MCUBE` command word and its text grammar (it
becomes the MR cube's preset of the `PIVOT` grammar), the MR source's arithmetic and every test of the branch, which must pass
unchanged against the engine (the MR cube's acceptance test of phase P1).

**API compatibility:** `/api/v1/cube/{cube}` stays as the MR and CCR cubes' address, answered by the pivot engine with the provider set
of that cube; nothing calling it changes. The paused branch's own guide text (MR cube guide, API section) is rewritten on the engine
when the MR cube resumes.

### 23.8 Custom aggregation functions

#### 23.8.1 The contract: start, add, merge, finish

Every function, built-in or custom, in any language, is four steps:

| Step | Called | Receives | Returns | Rule |
|---|---|---|---|---|
| `start(params)` | once per cell key per worker | the function's parameters (a trim fraction, a confidence) | an empty state | pure; no data |
| `add(state, batch)` | once per **columnar batch** (an Arrow record batch, 64K rows by default) of the cell's rows | the declared input columns only, masked as the reader sees them | the state, updated | vectorised over the batch; never row by row in Python |
| `merge(a, b)` | when two partial states of the same cell meet (two batches, two threads, two nodes, a cached cell and a new one) | two states | one state | associative and commutative, within the declared tolerance |
| `finish(state)` | once per cell, on the coordinator | the merged state | a number (or a small record: value, lower, upper, note) | pure |

A state is **serialisable** (Arrow or bytes, declared size bound); it crosses processes and nodes and lives in the cache. A function
declares its **kind**: mergeable (a fixed-size state and an exact merge), sketch (a bounded state and a merge with an error the author
states), or holistic (no `merge`: the engine gives it all the rows of a cell in one call, so it is size-bounded; [section 8.4](#2384-holistic-functions-and-their-bounds)).

```python
# A mergeable custom function: the weighted mean of returns (state: two sums), vectorised over each Arrow batch.
from drishti_pivot import function, Mergeable
import pyarrow.compute as pc

@function(name="wavg_return", kind=Mergeable, inputs={"r": "float64", "w": "float64"}, state={"swr": "float64", "sw": "float64"})
class WeightedReturn:
    def start(self, params):          return {"swr": 0.0, "sw": 0.0}
    def add(self, s, batch):
        r, w = batch["r"], batch["w"]
        swr = pc.sum(pc.multiply(r, w)).as_py() or 0.0      # None for an all-null batch
        sw = pc.sum(w).as_py() or 0.0
        return {"swr": s["swr"] + swr, "sw": s["sw"] + sw}
    def merge(self, a, b):            return {"swr": a["swr"] + b["swr"], "sw": a["sw"] + b["sw"]}
    def finish(self, s):              return None if s["sw"] == 0 else s["swr"] / s["sw"]
```

#### 23.8.2 Four languages, one contract

| Language | For | Where it runs | Kind it can be | Who may write it |
|---|---|---|---|---|
| **Formula of built-ins (DEL)** | ratios and combinations: `sum(pnl) / sum(abs(pnl))`, `share_of(...)`, a conditional sum | compiled into the provider's plan: SQL on DuckDB, Java on the VaR provider | the kind of its parts (a ratio of sums is mergeable) | any analyst with the authoring power |
| **SQL aggregate macro (DuckDB)** | anything expressible as SQL aggregates: `CREATE MACRO wret(r, w) AS sum(r * w) / sum(w)`; DuckDB treats a scalar macro that references an aggregate as an aggregate | inside DuckDB, on every node | mergeable when it is a formula of mergeable aggregates (the engine checks it by rewriting the macro into states), else holistic | authors; reviewed |
| **Python** | statistics no formula gives (a fitted slope, a robust estimator, a custom risk score) | a sandboxed **per-node worker pool** (`pivot-fn`, [section 8.5](#2385-python-the-sandbox-and-the-worker-pool)), vectorised over Arrow batches | mergeable, sketch or holistic, as declared and proven by the harness | authors; approved before shared |
| **Java plug-in (SPI)** | production-grade functions a site maintains, fast and in process | in the server, loaded from an approved jar (sha256-pinned, as compute's Java libraries) | any | the site's developers; approved by an administrator |

The DEL route is the first one offered: most custom measures are formulas, and a formula is fast, safe and exportable. A function is
named `fn:<owner>/<name>@<version>` and used in a view like a built-in.

**Why not DuckDB's own extension points for user code.** DuckDB's C API has aggregate functions with init, update, combine and finalize
callbacks (the same four steps), but its Python API documents scalar functions only, and running user code inside the server's DuckDB
would put it in the server's process. Python therefore runs in the worker pool, fed Arrow batches by the provider; Java plug-ins run in
the server (reviewed code); SQL macros run in DuckDB (they are SQL, not code).

#### 23.8.3 The harness: split-and-merge must equal one pass

A function is not saved, shared or used in production until its **harness** passes. The harness runs it on sample data (the author's
sample rows, then a seeded draw from the real table as the author sees it) in several ways and compares:

1. **one pass**: `start`, one `add` with all the rows, `finish`;
2. **batches**: the rows in batches of 1, 7, 1,000 and 65,536;
3. **splits and merges**: the rows split into 2, 3 and 16 random parts, each added separately and merged in random orders and tree
   shapes (left-deep, balanced), five seeds;
4. **empty and null**: an empty part, a part of nulls, a single row, all equal values, infinities and NaN in the inputs;
5. **state round trip**: every state serialised and read back before merging (as the cluster does).

It fails a **mergeable** function whose answers differ beyond the declared tolerance (default 1e-12 relative, or exact for integers and
decimals); a **sketch** function whose error exceeds its declared bound on the sample's exact answer; any function that is
non-deterministic for the same input order, too slow (over the cost limit per million rows), or whose state outgrows its declared bound.
The report shows each run, the largest difference and the run that produced it. A holistic function runs only the one-pass and empty
checks, and its size bound is shown.

#### 23.8.4 Holistic functions and their bounds

A function without `merge` receives **all the rows of a cell in one call**, so it is bounded: a cell over `functions.holistic-max-rows`
(default 5 million rows) or `holistic-max-mb` (default 512 MB) is refused for that cell with the size, never truncated, and the editor
suggests a sketch or a mergeable form. Holistic functions never run on cluster partials: the rows of a cell are gathered to one node
(within the budget), and a view whose holistic cells would gather more than the budget is refused before any data moves.

#### 23.8.5 Python: the sandbox and the worker pool

Python custom functions run in the node's **`pivot-fn` pool** (`drishti_compute.pivot_fn`, part of the compute SDK: its audit-hook
guard and its Arrow codec, [section 24.7](#247-the-worker-pool)), so the rows never leave the node that
scanned them:

| Guard | How (unchanged from BI and compute unless said) |
|---|---|
| process | warm workers (`functions.python.workers`), `python -I`, an empty environment; one call at a time; a worker serves **one function version only** (a function's rows never meet another function's code); a call past its time stops its worker |
| no network | the audit hook refusing every socket |
| no files | no reads outside the Python installation; no writes at all for pivot functions (they receive batches and return states; no scratch writes) |
| no processes | the audit hook refuses `subprocess`, `os.system`, `exec*`, `fork`, `ctypes` |
| packages | the pool's allow-list (numpy, pyarrow, scipy, pandas, polars by default) and safe standard-library modules; `exec`, `eval`, `compile` not given |
| wall time, memory | per call: `functions.python.timeout-seconds` (default 30); per worker: `memory-mb` (default 1,024, the process's address space) |
| inputs | only the declared input columns of the cell's rows, already filtered, ruled and masked for the reader |
| output | a state within its declared size; validated by the server before it is merged or cached |

**Transport.** Batches go to the worker as Arrow IPC stream bytes over its pipe (length-prefixed frames: a JSON header, a body), never
the scratch-folder files compute uses for its engines, so nothing is written for a function call; a state comes back as bytes (exact JSON,
floats to the bit). **Memory-only stores**: Python functions never read them (the source says so and the view is refused in words;
[DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk)).

**Throughput.** Per node, the pool's worker count (configuration only) times the per-worker rate; a vectorised `add` over 64K-row batches
runs at numpy speed (tens to hundreds of millions of values a second per worker for simple arithmetic), so Python functions scale with
nodes and workers. A function that loops row by row is reported by the playground's cost line and slowed by its own design, not the
server's.

#### 23.8.6 The playground for custom functions

**Pivot → Functions → New** opens the playground ([section 25](#2325-mock-ups-the-screens-to-build), scene 2 of each mock-up):

- **Code** (DEL, SQL macro or Python), with the contract's four steps outlined and the declared kind, inputs, state and tolerance;
- **Sample rows**: pasted, typed, or drawn from a table the author may see (seeded, masked), shown as a grid;
- **States**: the partial state after each part and after each merge, the final value, side by side with the one-pass value;
- **The check**: the harness of [section 8.3](#2383-the-harness-split-and-merge-must-equal-one-pass), run on every edit, green or red with
  the first difference;
- **Cost**: measured rows a second, memory per state, state bytes, and the projection on a real table ("≈3.2 s per billion rows on 3
  nodes × 8 workers");
- **Use it**: try the function in a pivot of the sample before saving; Save (private), Submit for approval (to share).

#### 23.8.7 Versions, audit, approval and labels

- Every save is a new version `@n`; a view names the version it uses, so a function's change never changes a saved view's figures until the
  view's owner moves it to the new version (the view shows "a newer version exists").
- A function is **private** to its author until an administrator (or a delegated approver, never the author) **approves** it for a group
  or everyone, seeing the code, the harness report, the cost and the packages; approval is audited (who, when, version, reason), and
  revoking it makes views that use it say "function withdrawn" in the cell.
- Every figure from a custom function is **labelled** with the function's name, author and version in the column header and the
  provenance; the regulatory and risk engines' measures (VaR, ES, FRTB-SA, SIMM, SA-CCR, exposure) remain **Java engines**, and a custom
  function can never take an official measure's name or appear in an official report run or sign-off.
- The audit log keeps every run of a custom function in a view (who, which version, the data versions, the rows read, the time).

### 23.9 Scale: extremely large data

#### 23.9.1 How a large pivot runs

1. **Plan.** The view becomes an aggregation plan: the axes first (distinct values under the filters, cheap), so the cell count is known;
   then the cell keys, the functions and their kinds.
2. **Estimate** ([section 9.7](#2397-scan-estimates-before-anything-runs)). Bytes, files and seconds, from the lake's metadata, before any
   data is read. Over the confirm level, the person chooses (run, narrow, use a rollup, background).
3. **Route.** Aggregate awareness picks the smallest layer that answers every function of the plan exactly (a rollup, the BI columns
   layer, the lake); a plan partly answerable from a rollup is split.
4. **Scatter.** The files (or shards) to read are assigned to nodes; each node's executor compiles its part to one DuckDB query (or one
   vector batch) and returns **partial states per cell key**.
5. **Gather and merge.** The coordinator merges partials **in node order, then cell-key order**, so the digits are the same on every run.
6. **Finish, show, cache.** States are finished into figures; states (not figures) are cached by (provider data version, cell key,
   function), so the next view that shares cells reads them from the cache.

#### 23.9.2 One DuckDB executor per node

Each node runs **one pivot executor**: a bounded pool of DuckDB connections (default one per four cores, `duckdb.connections`), each with
its `threads` and `memory_limit`, reading the files it is given. A node never answers more than its share; a slow node's files are
re-assigned (hedged) to an idle node holding the same data after `cluster.hedge-after-seconds`, and the first answer wins (both are equal
by construction). On a single server the executor is the whole engine.

#### 23.9.3 Scatter and gather across Drishti\*net

The VaR engine's sharding ([CLUSTER_DEPLOYMENT.md section 5](CLUSTER_DEPLOYMENT.md#5-var-sharding-in-depth-built)) already adds partial vectors from shard owners in shard order, bit-equal to
the whole. The pivot engine lifts that into the core for every provider:

| Data placement | How files or shards are assigned | Example |
|---|---|---|
| shared storage (S3, EFS, a lake on a shared path) | any node may read any file; files are assigned by consistent hashing of the file path over the nodes that run the `pivot` engine, so each node's DuckDB and disk cache stay warm for the same files | a 3 TB trades table on S3 |
| node-owned data (VaR shards, a pack bound to a node, memory-only tables) | the owner computes its partials; nothing moves but partial states | VaR by `LEGAL_ENTITY` shards; a `mem://` table on its owner |
| a small table | one node, no scatter | reference data |

One request per node carries **all** of a view's cell keys (as the cube already batches per owner). A node that does not answer within
the deadline makes its cells say so (never a partial figure); the person may retry those cells alone.

#### 23.9.4 Read less: pruning, skipping and layout

- **Column pruning**: only the columns of the plan (dimensions, measures' inputs, filters, row-rule columns) are read; Parquet is
  columnar, so a pivot of three columns of a sixty-column table reads a few percent of it.
- **Partition pruning and statistics skipping**: Delta keeps per-file minimum and maximum values in its log (for the first 32 columns by
  default), Iceberg in its manifests, Parquet per row group in its footer; filters on dates, desks or currencies skip whole files and
  row groups before reading them. DuckDB pushes filters into its Parquet reader and skips row groups by these statistics.
- **Layout by the dimensions people pivot by**: tables are partitioned by business date and **clustered** (Z-order on Delta, sort orders on
  Iceberg; liquid clustering where the Delta writer supports it) by the two or three dimensions most used as filters (desk, legal entity,
  product group), so statistics skipping works. The engine records which dimensions views filter by and the **layout advice** (phase P4)
  proposes a clustering with its expected saving, for an administrator to apply in Admin → Lakes.
- **The disk cache** of the lake readers keeps hot files local on each node.

#### 23.9.5 Sketches

Distinct counts and percentiles are expensive exactly and cheap approximately. States the engine keeps:

| Sketch | State | How a node makes it | Merge |
|---|---|---|---|
| HyperLogLog (distinct count) | 2^p registers (p = 12 by default; 11 to 16) | in SQL, in the same `GROUPING SETS` statement: `hash(x)` (64 bits); the first p bits pick the register, the rank of the first 1-bit in the rest is its value (its bit length found exactly); the cell's distinct (register, rank) pairs, the maximum per register taken in Java | register-wise maximum: exact for the sketch, associative and commutative to the byte; two hash families (DuckDB's and Java's) never merge |
| KLL (percentiles) | a summary of about 3k values (k = 200 by default) | built in Java (decision 6) from the cell's quantile points (`quantile_disc` at 2k + 1 fractions) and its count, each point weighted by the values it stands for (adding at most 1/(4k) to the rank error); written in the module, as Apache DataSketches (decision 2) is not resolvable in the offline build (only `com.yahoo.datasketches` 0.9.0, without KLL) | KLL merge (Karnin, Lang, Liberty, 2016), its coin a hash of the sketch's own count so a fixed order repeats to the byte |
| Space-Saving (top N) | 10 × n counters and a floor (the most any value not listed may count) | from the cell's `histogram` of values, kept to its counters; as built, a level's top N by a count of rows is chosen by it when the view asks ≈ (`TopFirst`: one summary for the parent's cell, then only the chosen members' cells are read; figures exact, membership within the floor) | counts added (a missing value counts the other's floor), the largest kept; commutative to the byte |

The stated error is printed with every ≈ value; on request the exact answer runs ([section 5.3](#2353-exact-on-request-without-giving-up-scale)).
Rollups may hold sketch states, so a distinct count by desk and month comes from the rollup.

#### 23.9.6 Rollups refreshed on load, chosen automatically

BI's rollups ([BI_GUIDE.md](../guides/BI_GUIDE.md#7-modes-layers-and-rollups)) store **components** of measures per date and group. The
pivot engine generalises them: a rollup stores **partial states** of any mergeable or sketch function (sums, counts, Welford parts, HLL
registers, KLL summaries) at its grain. Because the states merge:

- **Incremental refresh on load**: a load that appends files adds only the new files' states to the rollup (merge), instead of rebuilding
  it; a restatement or delete (a new Delta version that removes files) recomputes only the partitions it touched.
- **Aggregate awareness**: a plan is answered from the smallest rollup whose grain covers its dimensions, filters and row-rule columns and
  that holds every function's state; a coarser view merges the rollup's rows. The answer says which rollup answered.
- **Advice**: the engine counts the views that scanned the lake for want of a rollup ("asked 37 times this week, about 4 min each") and
  proposes one, as BI already does.
- Holistic functions are never rollup material; a rollup never holds VaR (the VaR engine's own per-node sums are its rollup).

#### 23.9.7 Scan estimates before anything runs

```
 This pivot reads ≈180 GB (41,220 of 96,400 files after the date and desk filters; 6 of 58 columns), ≈2 min on 3 nodes.
 No rollup covers DESK × COUNTERPARTY × month.
 [ Run (≈2 min) ]  [ Narrow: last 3 months (≈18 s) ]  [ Use rollup by DESK × month, drop COUNTERPARTY (instant) ]  [ Run in background ]  [ Not now ]
```

Bytes come from the lake's metadata after pruning and skipping (file sizes and the column chunks' sizes in the Parquet footers); seconds
from the bytes divided by each node's **measured** scan rate (kept from recent scans, per storage kind) plus a per-file cost; always a
range, with how it was made, as BI's run cards do today. The confirm level is BI's (a minute or 500 million rows, settings). An unknown
size asks first.

#### 23.9.8 Progressive answers: ≈ first, then exact

A view estimated over `progressive.after-ms` (default 2 s) answers twice: first an **approximate** answer within about a second,
from honest sources only (sketch states in a rollup that covers the view, or a **sample** of files chosen at random within each
partition, each mergeable figure scaled up with its 95% interval; never a coarser figure split by guessed proportions), marked ≈ with
its interval; then the exact answer replaces it cell by cell as nodes finish, with a progress line. Holistic measures (VaR) are
never sampled: they show "computing" until exact. The person may stop at the approximate answer.

#### 23.9.9 Budgets, busy protection and the cache

- **Cell budget**: views over `max-cells` (default 20,000 visible cells, totals included) are refused with the count and the dimension
  that makes them large; holistic cells have their own budget (`max-holistic-cells`, default 5,000), as the MR cube today.
- **Busy protection**: every view passes the admission gate ([OPERATIONS.md section 12.5](../admin/OPERATIONS.md#125-busy-protection-refuse-politely-never-fall-over));
  views over `busy-cells` or estimated over a minute ask it first; a refusal is the gate's polite message with its retry time.
- **Partial-state cache**: in the one memory budget, weighed in bytes (`cache-mb`); keyed by provider data version, cell key and function;
  a new data version invalidates only the cells of the tables it changed.
- **Per-person fairness**: at most `max-running-per-person` large views at once (default 2); background runs queue as BI runs do.

#### 23.9.10 DuckDB's limits, stated honestly

| Limit | What it means here | What the design does |
|---|---|---|
| **One process, one machine** | DuckDB itself does not distribute a query | Drishti scatters and gathers partial states ([section 9.3](#2393-scatter-and-gather-across-drishtinet)); DuckDB is the per-node executor |
| **High-cardinality GROUP BY** | DuckDB spills grouping, joins, sorts and windows to its temporary directory when memory runs out, but some complex aggregate states cannot yet be offloaded, and a query with several blocking operators may still run out of memory (DuckDB's tuning guide) | the cell budget bounds the groups; states are kept small (mergeable kinds); holistic functions are size-bounded; the per-connection `memory_limit` is set, and an out-of-memory becomes a refusal in words |
| **Spill forbidden for memory-only data** | a `mem://` table may never touch a disk, so DuckDB's temporary directory is off for it | such views must fit in memory: over it they are refused, never spilled ([DATA_ARCHITECTURE.md section 7](DATA_ARCHITECTURE.md#7-the-in-memory-lake-delta-tables-that-never-touch-a-disk)) |
| **Object-store bandwidth** | a scan of S3 is bounded by the node's network (a 10 Gbit/s link moves at most about 1.2 GB/s) | pruning and skipping first; the disk cache; more nodes for more bandwidth; the estimate uses the measured rate |
| **Concurrency** | DuckDB is not designed to run many small queries at once quickly (its tuning guide) | a bounded connection pool per node, queuing, the partial-state cache, rollups for dashboards, coalescing of identical plans |
| **Extensions** | the `delta` and `iceberg` extensions are separate files DuckDB normally downloads | shipped with the distribution and loaded from it (no download at run time, auto-install off); their versions pinned with DuckDB's |

**When this is not enough** (decision 8): interactive views over tens of terabytes by many people at once, where three to ten Drishti
nodes cannot answer in minutes. Then a distributed SQL engine (Trino, StarRocks, ClickHouse) becomes a provider
([section 6.6](#2366-an-external-distributed-backend-future-not-built)). The benchmark of [section 21](#2321-the-benchmark-plan) decides
when, on measured numbers.

### 23.10 Beyond anything out there

The product owner's bar (2026-11-02): "we need to do better than Excel and better than anything out there". Each item says how it
works, its phase ([section 22](#2322-phases-and-sizes)) and its size. The most differentiating (1, 2, 3, 4, 9, 12, 13) are in the first
phases.

| # | Capability | How it works | Phase | Size |
|---|---|---|---|---|
| 1 | **Explain the difference between any two cells, or one cell over time** ("why is EMEA higher than last month") | for mergeable measures the difference is split **exactly** into contributions by member (the children of the cell along any dimension the person picks, each child's difference adding up to the total) and by cause where the provider knows causes (new members, members gone, value changes; for a price × quantity measure, the price and quantity effects in a fixed order with the cross term shown); for VaR, RO1's exact steps (restatement, hierarchy, trades out, window roll, market, trades in) per cell ([LENSES.md section 5.4.1](LENSES.md#541-ro1-var-change-explanation)); shown as a waterfall beside the cell; steps always add up to the difference, and the residual (if any) is named | P2 | L |
| 2 | **Time travel and diff** | any view as of any business date and any **data version** (a Delta version or an Iceberg snapshot, `as-of`); **compare** two dates or versions side by side or as a diff grid with changed cells highlighted and new and gone members marked; each side's provenance names its versions | P1 | M |
| 3 | **What-if inside the pivot** | an **overlay**: exclude members (a book), scale or override an input column (a price, a weight, a notional), add hypothetical rows; the tabular provider joins the overlay into the plan, the VaR provider uses its what-if arithmetic (`VarWhatIfMath`); only affected cells recompute; the original stays beside the what-if with the difference; an overlay is private, saved with the view, never written to the lake | P2 | M to L |
| 4 | **Cell provenance and lineage** | every cell's popover: the data versions read, files or shards and nodes, rows counted, filters and row rules applied (in words), functions with their versions, the layer (rollup or scan), exact or ≈ with the error, time taken; one click to the rows (drill-through, paged, as the person may see them) | P1 | M |
| 5 | **Live pivots** | on streaming tables (BI's live tables, the Kafka PV stream, intraday files) the engine keeps **mergeable states per cell** and applies each batch as a merge (BI's `LiveAggregate` does this for rollups today); holistic cells recompute at most once per batch, coalesced; cells tick with a flash and a freshness age; a cell more than one batch behind says so (as built: [section 10.1](#23101-as-built-live-pivots-the-screens-switches-and-cell-comments)) | P3 | M |
| 6 | **Ask in words** | "VaR by desk and currency for last quarter, top 10" → the AI layer (when on) **proposes** a view definition; the person sees the definition (rows, columns, values, filters) before it runs, edits it, and nothing is hidden; the AI never computes a figure | P4 | S to M |
| 7 | **Automatic insights** | on request or as a toggle: outliers (robust z-score against the cell's peers), sudden changes (against the cell's own history), concentration (a member's share above a threshold), flagged in the grid with the reason in words; computed by the engine, never guessed | P4 | M |
| 8 | **Robust references to cells** | a stable name for a cell: `pivot://asha/desk-risk@4/Notional[DESK=RATES-NY, month=2026-10]` (view, version, measure, coordinates); survives re-sorting, new columns and a new date (it names coordinates, not positions); usable in DEL formulas (`pivot_ref(...)`), alerts, report cards and the Excel export (named ranges); a reference whose coordinate is gone says "not in the view", never 0 | P1 (names), P4 (formulas, Excel) | S, then M |
| 9 | **Correctness Excel lacks** | non-additive totals right, currencies converted explicitly, units respected, masks before aggregation, no silent truncation, exact or ≈ always shown ([section 5.4](#2354-correctness-excel-does-not-have)) | P1 | M (in the core) |
| 10 | **Collaboration** | shared live views (the reader's entitlements), comments anchored to a cell's coordinates (survive re-sorts and new dates; [COLLABORATION.md](COLLABORATION.md)), **view versions** with a diff of definitions and restore (as built: [section 10.1](#23101-as-built-live-pivots-the-screens-switches-and-cell-comments)) | P2 | S to M |
| 11 | **Terminal-grade experience** | keyboard first (`PIVOT <GO>`, every action a key, the Bloomberg-bar standard), instant first answers on big data through progressive answers and rollups | P1 (keys), P3 (progressive) | S, then in P3 |
| 12 | **Expand and collapse, better than Excel** | nested rows and columns, lazy evaluation per expanded node, "expand by" a different dimension per member, ragged hierarchies, correct Others, inline drill to the rows ([section 11](#2311-expand-and-collapse-nested-levels-better-than-excel)) | P1 (core), P2 (expand by, Others for holistic) | M, then M |
| 13 | **Pick-and-choose charts** | select cells, a row, a column, a range or a level and chart it; the chart is suggested from the selection's shape, linked both ways to the pivot, and never stacks a non-additive measure ([section 14.3](#23143-pivot-charts-pick-and-choose)) | P1 (pick, suggest, link), P4 (Excel charts bound to the PivotTable) | M, then S |

#### 23.10.1 As built: live pivots, the screen's switches and cell comments

**Live pivots (item 5).** Built on the existing change signals and the one core, with no second aggregation path: `LivePivots`
(module `live`) holds each live view (its person, view and options) and, on a **signal**, computes the touched views again through
`PivotCore.query`. Signals come from the server's existing channels: `LoadService.onReady` (a data load of a kind for a business date,
the signal BI's layers rebuild on) and the `TopicHub` (a page panel's rows, `panel/<kind>/<panel>/<id>`, followed through the channel the
Sutra live views stream from; one hub subscription per live panel while a live view reads it). A view on another source or date is not
touched; a view on the latest date is touched by any load of its source. Only what moved is read again: the partial-state cache is keyed
by each date's data version, so the sets of untouched dates come from the cache, and before the recompute the rollups of the signal's
source are refreshed for its dates (their new files' states merged), so a rollup-answered view moves with the load. `AnswerPatch` compares
the new answer with the last one: no cell moved (`status`), only cells moved (`patch`: those cells whole, the cells gone, the new
provenance and `computedAs`), or the rows, columns or measures changed (`reshape`: the whole answer). The patch applied to the last
answer equals a fresh query, cell by cell (`LivePivotTest`). Recomputes are coalesced per view (`drishti.pivot.live.min-interval-ms`), run one at a time
per view so patches leave in order, and are **exact** unless the view asked ≈ (`ScaleOptions.live()`: never sampled, never progressive).
Limits are refused in words (`DRS-5721`): the switch, per person, per node, `drishti.pivot.live.max-cells`, `drishti.pivot.live.max-rows` for an exact live view, a
view pinned with `asOf`; a view nobody streams ends after `drishti.pivot.live.idle-seconds`. The API is BI's standing-query shape: `POST
/api/v1/pivot/live` (the first answer, its id, the node that holds it), `GET /live/{id}/events` (SSE: `answer`, `patch`, `reshape`,
`status`, `error`, `ended`), `DELETE /live/{id}`. The batch-as-merge form of the item (per-cell states updated from a stream's rows
without a query) is not built: a live table's update is a recompute through the cache.

**The screen's switches.** Exact / Auto / ≈, Progressive and Use rollups are `ScaleOptions` (`exact`, `progressive`, `rollups`) beside the
view, never in it; `rollups: false` makes `AggregateAwareness` step aside. Every answer carries `computedAs` (what was asked; the measures
answered by sketches with their errors; the levels whose top N a summary chose; the routes that answered, a rollup or the cluster; the
sample; one line of words), which the screen shows beside the switches and in the provenance popover. The browser computes nothing.

**A level's top N by Space-Saving.** `TopFirst` (core): when the view asks ≈ and a level's top N is ranked by a count of rows on a plain
text field, the parent's cell is asked for one Space-Saving summary of the field (through the hooks, so a rollup or the cluster may
answer it), its top n become the level (`Axis.Group.only`: one restricted cell set per member) and the rest one Others (`exclude`), its
words in `computedAs.topChosen`. Exact (the default) ranks every member as before. Bounds tested in `ScaleSwitchesTest`.

**Cell comments (item 10).** `PivotCellComments` keeps a saved view's comments beside its numbered versions
(`pivot/view-versions/<owner>/<name>/comments.json`, one document changed atomically); each names its cell by coordinates (readable
`cell`, and `cellId`, the answer's cell key the screen marks), an optional measure, who, when, the text, resolved by whom and when, and the
view version it was written on (`older` once the view is saved again). Readers of the view comment; the author or the owner resolves,
reopens and deletes. Audited without the text. Token scope `pivot:comment`.

### 23.11 Expand and collapse: nested levels, better than Excel

Product owner (2026-11-02): "In Excel pivot can also have expandable rows. We should have that capability too. Build something better
than Excel."

| # | What | How |
|---|---|---|
| 1 | **Rows and columns as nested levels** | every member of a row or column level has **+** and **−**; expand or collapse one, all at a level, or **expand to level N**; Excel's three layouts (**compact**: one indented column; **outline**: a column per level; **tabular**: a column per level with every label repeated) and **subtotals above or below** each group, per view |
| 2 | **Lazy evaluation** | only expanded nodes are computed: opening a node is a new plan of that node's children only (one `GROUPING SETS` query for the tabular provider, one vector batch for VaR), so a ten-level hierarchy over billions of rows opens as fast as one level; children already computed come from the partial-state cache; the **expansion state** (which nodes are open, by coordinates) is part of the saved view and of a link |
| 3 | **Expand by…, per member** | on any member choose a **different** dimension to break it down by, inline: EMEA by desk, APAC by currency, in the same grid; Excel's *Show Details* asks for one field once for the whole field; here each member keeps its own breakdown, and the totals stay right because each node is its own cell key |
| 4 | **Ragged and parent-child hierarchies** | hierarchies of uneven depth (VaR's MGMT and limit-letter trees, GLEIF ownership chains) and parent-child tables (a parent column), as well as hierarchies **built from columns** (division → desk → book); a member with no children has no +; a cycle in a parent-child table is refused at load with the members named |
| 5 | **Top N per level with a correct Others** | each level may show its top N by a measure with one **Others** row; Others' figure is computed from **its own members** (for VaR, the VaR of the members not shown, from their vectors), never total minus the top N, and says how many members it holds |
| 6 | **Sort within each level** | each level sorts independently (by label, by a measure, by a configured order); a sort never moves a child out of its parent |
| 7 | **Holistic measures right at every level** | a node's VaR is from its members' vectors, never the sum of its children; **diversification** (Σ children − node) shown on request as its own row or column |
| 8 | **Down to the rows, and back up** | the last level expands into the **member rows** (positions, trades) inline, virtualised (only the rows on screen are drawn; millions scroll), paged from the server as the person may see them; from a trade, **Show in hierarchy** opens the path from the top to it |
| 9 | **Keyboard** | `→` expand, `←` collapse, `Shift+→` expand all below, `Shift+←` collapse all below, `1` to `9` expand the whole axis to that level, `E` expand by…, `Enter` drill to rows; the Bloomberg-bar standard: every action has a key, shown in the help line |
| 10 | **Differences and time travel per node** | in compare mode each node shows its change and **Why** opens the explanation by its children ([section 10](#2310-beyond-anything-out-there), item 1) |
| 11 | **Export keeps the outline** | the xlsx uses Excel's native row and column **outline levels** (grouping with + and −, summary rows above or below as on screen) and the native PivotTable keeps the same fields nested ([section 15](#2315-export)) |

Phase P1 builds 1, 2, 4, 6, 7, 8 and 9 (size M); P2 builds 3, 5 (for holistic measures), 10 and 11 (size M).

### 23.12 BI on the pivot engine

Product owner (2026-11-02): the pivot engine is also BI's; there is no shadow mode and no old path kept: BI moves onto the engine where it
makes sense and the aggregation code it replaces is removed. BI's own design stays: datasets and data models (D1), one model per report
(D2), pack-generated datasets (D3), **MQ as the one way to ask a model for data** (D5), time modes (D6)
([BI_DESIGN.md section 14](BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6)), its compiler of a model's rows (row rules,
masks, joins, layers, dates) and its security. What changes is **who aggregates**: one engine, one set of aggregation functions, the same
correctness. Drishti's BI is Power BI-like, not a replica: where a Power BI semantic would cost scale or reuse, the engine's design is
chosen and said here.

#### 23.12.1 What is on the engine

| BI | On the pivot engine | Unchanged |
|---|---|---|
| a **matrix**'s totals and subtotals (every total row and column), a **table**'s total row (`format.totals`), a model query's `totals` (grand, subtotals, explicit levels) | every level in **one** `GROUP BY GROUPING SETS` statement of the engine's function states, over the model's rows as the reader may see them; each level from its own rows | the visual's YAML, wells and format; MQ's `totals` request and answer |
| the measures' additivity (`additive` in MQ's columns, the matrix's adding up) | the engine's own answer for the measure's function (`plainSum`: sums and counts added and subtracted) | an author's explicit `additive:` |
| the console adding up a matrix's additive totals | nothing: every total is the engine's | the matrix as drawn |
| a model's measures (DEL) | each measure that is an expression of aggregates of one fact's rows is a **pivot function** ([section 12.2](#23122-a-measure-defined-once)) | the measure's name, text and the model's YAML |

What stays the model's own query: a visual's grouped cells (BI's compiler already computes them in one statement, with joins across facts,
filter contexts, time intelligence and windows), rollups and live aggregates (their state arithmetic moves with phase P3's rollups of
partial states), and standing queries' incremental maintenance.

#### 23.12.2 A measure defined once

A model's measure is **a function**: `sum(trades.MTM)`, `avg(...)`, `min`, `max`, `count(trades)` (rows), `distinct(...)` (an exact
distinct count), `percentile(x, q)`, `ratio(a, b)`, `where=` conditions, arithmetic, row functions (`abs`, `round`, ...) and other measures
by `measure('Name')` translate to the engine's formula of built-ins, with its kind and states. A measure that reads beyond the rows of its
group is not a pivot function and has no total: time intelligence (`ytd`, `prior_period`, ...), running and analytic functions (a running
sum or a rank has no total that means anything), filter contexts (`calc`, `share_of`, ...), balances and Python measures. The answer says
so in its warnings ("no total for YTD: ...").

#### 23.12.3 How a total is computed

BI's compiler writes the model's **detail** query as the reader (row rules, masks, joins along many-to-one paths, the layers and dates of
the question) and **does not run it**: it becomes the FROM item of the engine's `GROUPING SETS` statement (`QueryService.Outer`). The
statement runs in BI's engine, where the model's layers are, so no row leaves it and a billion-row model is totalled by one scan; the
engine reads each set's states (`fromSql`) and finishes them. Measures of several facts get one statement per fact (a measure aggregates its own fact's rows), the levels joined. A masked field is never grouped (that level is not made); a masked measure's
total is empty. A dataset read directly from its source (`mode: direct`) gets no engine totals (they would read every row back).

#### 23.12.4 Nothing breaks: the migration

*One path, migrated cleanly (product owner, 2026-11-02: "No need to keep BI on old engine ... do a clean migration"; no dual machinery).*

1. **Parity proved by tests before the switch**, on the branch that moves BI, not by running two paths in production: every BI test
   suite and every shipped sample report is answered by the pivot engine and compared with the figures the current code gives, recorded
   once from it before the move (a fixture per report, visual and cell); any difference beyond float tolerance (1e-9 relative; exact
   for counts and decimals) fails, naming the report, visual, cell and both values.
2. **Known, intended differences** (listed, reviewed, each with a test): none for sums, counts, minimums and maximums; averages and
   ratios identical (they were already true totals, now one statement instead of one query per level); a distinct count stays
   **exact**. The answer's shape: every measure that is a pivot function has a total (a sum's total is the engine's, the console adds
   nothing up; a count is a number with a decimal point in the JSON); a matrix without `format.subtotals` gets its grand total and row
   totals from the engine too; a time-intelligence, running, context or Python measure has no total (a dash), with the reason in the
   answer's warnings.
3. **One switch, for everything, in the same release**: when the parity tests pass, BI's totals and subtotals and its measures' additivity
   run on the engine and **BI's old aggregation code for them is deleted** in the same change (the history keeps it); rollups move with
   P3's rollups of partial states, the same way. No setting chooses between two paths;
   a model's YAML needs no change.
4. **The release's drills** run every BI suite on the new path only.

#### 23.12.5 When

Phase **PB**, after P1 and P2 ([section 22](#2322-phases-and-sizes)): the engine first proves itself on the pivot screen and the MR cube; BI
then moves, its parity proved by tests and the old code deleted (section 12.4). Size L.

### 23.13 The module: drishti-bicompute

Product owner (2026-11-02): "a holistic bi-compute maven module which can be used by any and all components of drishti".

#### 23.13.1 What it is

`drishti-bicompute` is a new Maven module: a **plain Java 21 library, no Spring**, holding everything that aggregates. Its dependencies:
`drishti-api` and `drishti-common` and DuckDB (`duckdb_jdbc`, already in the build), plus, by decision 2, `arrow-format` (already used by
`drishti-compute`, for Arrow batches to Python workers) and the sketch library if one is chosen. **Rule: no other module re-implements
aggregation**; a module that needs a sum, a total, a sketch or a custom function calls this one. A build test (an architecture rule)
fails any new class outside it that implements the function SPI's steps or emits `GROUP BY` for a pivot.

#### 23.13.2 Package layout and API surface

| Package | Holds | Main types |
|---|---|---|
| `com.ash.drishti.bicompute.core` | the pivot model, plans, the merge and finish | `PivotView`, `CellKey`, `Axis`, `AggregationPlan`, `PivotCore`, `PivotAnswer`, `Expansion` |
| `com.ash.drishti.bicompute.spi` | the provider SPI and the function SPI | `PivotProvider`, `Catalogue`, `Capabilities`, `Partials`, `AggFunction<S>` (start, add, merge, finish), `Kind {MERGEABLE, SKETCH, HOLISTIC}`, `StateCodec<S>` |
| `com.ash.drishti.bicompute.functions` | the built-ins | `Sum`, `Count`, `MinMax`, `Avg`, `WeightedAvg`, `Welford`, `FirstLast`, `ExactQuantile` |
| `com.ash.drishti.bicompute.sketch` | sketches and their codecs | `Hll`, `Kll`, `SpaceSaving` |
| `com.ash.drishti.bicompute.sql` | the grouping-sets compiler and the DuckDB provider | `GroupingSetsCompiler`, `DuckDbExecutor`, `TabularProvider`, `LakeScans` |
| `com.ash.drishti.bicompute.formula` | DEL: parser, checker, lowering, function catalogue (moved from BI) | `DelParser`, `DelChecker`, `DelLowering`, `FunctionCatalog`, `FormulaFunction`, `SqlMacroFunction` |
| `com.ash.drishti.bicompute.python` | the client side of the Python worker protocol | `PythonFunctionClient`, `ArrowBatchWriter`, `WorkerRequest` |
| `com.ash.drishti.bicompute.harness` | the split-and-merge test harness | `FunctionHarness`, `HarnessReport` |
| `com.ash.drishti.bicompute.cluster` | partial states over the wire, fixed-order merge | `PartialCodec`, `OrderedMerge` (from `ShardGather.Combine`) |
| `com.ash.drishti.bicompute.cache` | cache keys and weighing | `StateKey`, `Weigher` |
| `com.ash.drishti.bicompute.export` | xlsx (values, outline, native PivotTable, charts), csv, Arrow | `XlsxPivotWriter`, `PivotCacheWriter`, `CsvWriter` |

#### 23.13.3 What moves into it

| From | What | Note |
|---|---|---|
| `wip/mr-cube`: `server/cube/` | `CubeEngine`'s pivot (axes, totals, diversification, budget check), `CubeView` and its text grammar, `CubeMeasure`, `CubeDimension`, `CubeExport` | changed as [section 7](#237-from-the-mr-cubes-core-to-the-pivot-engine-what-is-kept-what-changes) says; `CubeController`, `CubeViews` (storage), `CubeConfiguration` and the busy-gate wiring stay in the server |
| `drishti-server`: `server/bi/expr/` | DEL (`DelParser`, `DelChecker`, `DelAst`, `DelLowering`, `DelPrinter`, `DelType`, `FunctionCatalog`, `FunctionDocs`) | Spring-free today; the `web` part stays in the server |
| `drishti-server`: `server/bi/engine/` | `AnalyticEngine`, `DuckDbEngine`, `Dialect`, the aggregate and totals parts of `SqlCompiler` and `SqlEmitter`, `ArrowIpcWriter`, `LiveAggregate`'s state arithmetic | the security rewriter, layers, live feeds and BI's plan stay in the server and call the module |
| `drishti-server`: `server/shard/` | `ShardGather.Combine` (the ordered combine) | `ShardedMeasure` stays with VaR and implements the module's partial codec |

#### 23.13.4 Who uses it

| User | How |
|---|---|
| `drishti-server` | wires providers, REST, entitlements, busy protection, the memory budget for the cache, the cluster transport; hosts the VaR, FRTB-SA, SIMM and (later) CCR providers, which implement the module's SPI (the vector arithmetic stays where it is) |
| BI | matrix visuals, totals, rollups and the semantic model's measures ([section 12](#2312-bi-on-the-pivot-engine)) |
| the MR and CCR cubes | providers and landings on the core |
| Export data, playgrounds, reports, the CLI | pivot views, function tests, exports |

### 23.14 The screens and pivot charts

#### 23.14.1 The pivot screen

`PIVOT <GO>` (or **Views → Pivot**, or **Pivot** on any table, BI model or cube) opens the screen chosen from the mock-ups
([section 25](#2325-mock-ups-the-screens-to-build)). Everything Excel's PivotTable does:

| Excel | Here |
|---|---|
| field list; drag to Rows, Columns, Values, Filters | the same four zones, plus the keyboard (`R`, `C`, `V`, `F` on a field) and the command line |
| summarise values by sum, count, average, max, min, product, count numbers, stdev, var | every built-in of [section 5.2](#2352-the-built-in-functions) and every approved custom function |
| show values as % of grand total, column, row, parent row, parent column, parent total, difference from, % difference from, running total, % running total, rank smallest to largest and largest to smallest, index | the same list, each computed by the engine from figures (show-as transforms finished figures, so it never breaks a total) |
| calculated fields and items | calculated fields in DEL (over measures' states where mergeable, so they are right at every level, unlike Excel's calculated fields over sums); calculated items as named member groups |
| group dates (days, months, quarters, years) and numbers (by width) | typed grouping ([section 4.2](#2342-dimensions-typed-and-ordered)), including fiscal calendars and edges |
| slicers and timelines | slicers and a date timeline, shared across pivots and charts on the page |
| subtotals, grand totals, layouts | [section 11](#2311-expand-and-collapse-nested-levels-better-than-excel) |
| conditional formats | colour scales, data bars, icon sets and rules, on figures; a ≈ figure keeps its marker |
| double-click a value to see its rows | drill-through inline or in a side panel, paged, as the person may see them |
| refresh | live refresh, time travel, compare |
| (none) | the switches Exact / Auto / ≈, Progressive, Use rollups and Live, with how the answer was computed beside them ([section 10.1](#23101-as-built-live-pivots-the-screens-switches-and-cell-comments)) |
| comments | comments on a saved view's cells by coordinates, a marker on the cell, listed in the provenance popover, kept with the view's versions |

#### 23.14.2 Phone tiers

As the cube ([MOBILE_DISPLAY.md](MOBILE_DISPLAY.md)): the top-left of the grid and the headline are **must**; charts **glance**; expand,
drill, explain and provenance **more**; the field list and the function playground **desk**.

#### 23.14.3 Pivot charts: pick and choose

Product owner (2026-11-02): "And pick and choose charting as well."

1. **Pick and chart**: select cells, a row, a column, a range or a level, press `G` (or **Chart**): bar, stacked bar, line, area,
   **waterfall** (for explain and differences), heat map, **treemap and sunburst** (hierarchies), scatter, **histogram and distribution**
   (for vector measures: a cell's scenario P&L path), **sparklines in cells**, combo with a second axis.
2. **Suggested from the selection's shape**, overridable: time on an axis → line; parts of a whole (an additive measure over the children of
   one node) → stacked bar or treemap; one measure across members → bar; a change → waterfall; two measures over members → scatter; a
   vector measure in one cell → histogram with the VaR and ES lines.
3. **Linked both ways**: expand and collapse, filters, slicers, time travel and what-if update the chart live; clicking a bar or a
   treemap tile drills or filters the pivot.
4. **Correct**: a **non-additive measure is never stacked** as if its parts added up: the VaR of desks does not stack to the bank's VaR,
   so the chart shows the parts beside the total with **diversification as its own segment**, or refuses stacking in words; ≈ values are
   drawn hatched with their error bars; axes carry units and currency; mixed currencies are refused unless converted.
5. **Several charts** from one pivot pinned beside it, saved in the view, shared with it, **embedded in BI reports and Sutra pages**
   (they are BI visuals over the pivot's answer, drawn with the vendored ECharts and Vega-Lite libraries, nothing from a CDN), exported to
   Excel as **native charts bound to the PivotTable** (P4) and to PNG and SVG.
6. **Keyboard**: select with `Shift` and the arrows, `G` charts, `Shift+G` cycles the suggestions, `P` pins.

Phase P1 builds 1 to 4 and 6 with PNG and SVG export (size M); P4 binds charts to the native PivotTable (size S).

### 23.15 Export

Product owner (2026-11-02): "And data export to Excel and/or CSV." The **Export** menu (`X`, top right of the pivot screen; the same from
the API and `drishti.py pivot export`) asks two things: **what** and **how**.

#### 23.15.1 What is exported, chosen by the person

| What | Contents |
|---|---|
| **The pivot as shown** | the visible cells with the outline (expanded levels, subtotals above or below as on screen), the show-as figures, the formats; collapsed nodes exported collapsed (Excel's outline keeps them openable) |
| **The underlying rows** | the drill-through of the whole view or of one cell: the member rows (trades, positions) with the columns the person picks, as they may see them |
| **The chart data** | the series behind a pinned chart, one column per series |
| **The view definition** | the view's YAML (section 4.1): to share, version or rebuild it elsewhere; no figures |

#### 23.15.2 Excel (xlsx)

- **Values with formats**: one sheet for the pivot, numbers as numbers with the screen's number formats, dates as dates, the outline as
  Excel's native row and column **grouping levels** (summary rows above or below as on screen), conditional formats as Excel conditional
  formats where Excel has the same rule.
- **Formulas only where they are right**: a total of a measure that merges by **plain sum** is written as `SUM` of its cells (or a ratio
  of two such sums); everything else (VaR, a distinct count, a percentile, a custom function) is a **value** with a note in its cell's
  comment, "how this was computed" (the function, its version, exact or ≈ with the error).
- **A Provenance sheet**: the data versions read (Delta versions, Iceberg snapshots), the functions with their versions and authors, the
  filters, the row rules and masks applied (in words, never the hidden values), ≈ against exact per measure, who exported and when.
- **A native PivotTable** (phase P4): a real PivotTable with its **pivot cache** (the view's leaf cells as records, pre-aggregated by the
  engine, kept on a Data sheet so Excel's Refresh works offline) and a **pivot table definition** with the view's rows, columns and
  filters, written from the SpreadsheetML parts for PivotTables (`pivotCacheDefinition`, `pivotCacheRecords`, `pivotTableDefinition`;
  ECMA-376 Part 1, section 18.10). Only measures Excel re-aggregates correctly from records are data fields: sums, counts (as sums of a
  count field), minimums, maximums, and averages as a **calculated field** `sum_x / count_x`. Sketch, holistic and custom measures go to a
  Values sheet beside it, never into the PivotTable, with a note why. **Native charts** bound to the PivotTable come with it (P4).
- **Excel's limits, handled in words**: a worksheet holds 1,048,576 rows and 16,384 columns. Rows beyond it are **split over sheets**
  (`Rows 1`, `Rows 2`, ...) up to `export.xlsx-max-rows` (default 5 million); beyond that, or when a native PivotTable's records would not
  fit, the menu says so with the count and offers CSV, Parquet or Arrow instead, or a coarser grain. Never a silently cut file.

#### 23.15.3 CSV

UTF-8 (with an optional byte-order mark for Excel on Windows), a chosen delimiter (comma, semicolon, tab), ISO dates and timestamps,
numbers unformatted (full precision, `.` as decimal point), a header row, one file per sheet (pivot, rows, provenance) in a **zip** when
there are several, and **gzip** for big files. The pivot as shown exports one row per cell (coordinates, measure, value, exact or ≈, error)
or the grid as shown, as the person picks.

#### 23.15.4 Big exports

- **A size estimate first** (rows, columns, bytes, time), as for a query ([section 9.7](#2397-scan-estimates-before-anything-runs)).
- **Streamed from the server**: rows go from DuckDB (or the provider) straight into the file being sent, in batches; no temporary file on
  the server for **memory-only data** (the never-on-disk guard), and none needed for the rest. A streamed answer is bounded by the
  server's async request timeout (`DRISHTI_ASYNC_TIMEOUT`, 30 minutes as shipped) and passes busy protection.
- **Large ones as a background job**: above the inline size (default 1 million rows or 200 MB), the export becomes a run with its card
  and a link when ready (as BI runs); the file is kept for `export.keep-hours` (default 24) under the data folder, unless the source is
  memory-only, when the job streams to the person's open download only and is refused as a stored file, in words.

#### 23.15.5 Analysts: Arrow, Parquet and Power Query

**Arrow** (`Accept: application/vnd.apache.arrow.stream`) and **Parquet** files of the cells or the rows; **Power Query**: a REST endpoint
per saved view returning the cells, a personal token in the header, and a generated Power Query (M) text to paste into Excel or Power BI.

#### 23.15.6 Who may export, and audit

Masks and entitlements are applied **before** export (the file holds what the screen showed this person, no more); an export of rows
needs the same right as drill-through; datasets marked internal only follow Export data's rule. **Every export is audited**: who, the
view, what (pivot, rows, chart, definition), format, rows and columns, size, data versions; **never the values**.

#### 23.15.7 Phases

P1: xlsx values with outline, formulas where right and the Provenance sheet; CSV; rows; chart data; the view definition; Arrow, Parquet,
Power Query; streaming, estimate, background job, audit (size M). P4: the native PivotTable and charts bound to it; references as named
ranges (size M).

```
drishti.py pivot export asha/desk-risk --what pivot|rows|chart|definition [--cell "DESK=EMEA"] --format xlsx|xlsx-pivot|csv|parquet|arrow [--delimiter ";"] [--gzip] [--wait] -o out.xlsx
POST /api/v1/pivot/export   { "view": "asha/desk-risk", "what": "rows", "cell": "c:7f3a…", "format": "csv", "csv": { "delimiter": ";", "gzip": true } }
```

### 23.16 The engine switch and its configuration

The `pivot` engine joins the catalogue of [section 25](#252-the-engines): id `pivot`, file `pivot.yaml`,
needs nothing; `mr-cube` and `ccr-cube` **need** it; BI **uses** it once phase PB is done (until then BI does not depend on it). Off, the
engine does not exist on that server: `PIVOT`, the menu, Help pages, the CLI's `pivot` commands and the function playground disappear, and
the cubes cannot be on.

```yaml
# config/engines/pivot.yaml: the pivot engine (docs/architecture/ARCHITECTURE.md section 23). Read at start, imported by application.yaml.
drishti:
  engines:
    pivot:
      enabled: ${DRISHTI_PIVOT_ENABLED:true}      # live: on and off without a restart
      changed: { by: "", at: "", reason: "" }
  pivot:
    max-cells: 20000                 # visible cells a view may have, totals included; over it: refused with the count
    max-holistic-cells: 5000         # cells of holistic measures (VaR, exact median, holistic custom functions)
    busy-cells: 2000                 # a view of more cells asks busy protection first
    cache-mb: 512                    # partial-state cache, weighed in bytes, in the one memory budget
    max-running-per-person: 2
    duckdb:
      connections: 0                 # 0: one per four cores
      threads: 0                     # 0: DuckDB's default
      memory-limit-mb: 4096          # per connection
      temp-directory: ${drishti.data.dir:./data}/pivot/spill   # never used for memory-only tables
      extensions-dir: ./lib/duckdb-extensions                  # shipped delta and iceberg extensions; nothing downloaded
    estimate:
      confirm-above-seconds: 60
      confirm-above-rows: 500000000
    progressive: { enabled: true, after-ms: 2000, sample-fraction: 0.02 }
    sketches: { hll-precision: 12, kll-k: 200, top-capacity-factor: 10, exact-distinct-budget: 5000000 }
    rollups: { incremental: true, advise-after-scans: 10 }
    cluster: { enabled: true, hedge-after-seconds: 20, deadline-seconds: 300 }
    aggregate-only: { min-members-per-cell: 1 }
    functions:
      formula: true                  # DEL formulas
      sql-macros: true
      require-approval: true         # sharing a function needs an approver who is not its author
      holistic-max-rows: 5000000
      holistic-max-mb: 512
      harness: { tolerance: 1.0e-12, seeds: 5, max-ns-per-row: 2000 }
      approver-role: pivot-approver  # members of this role approve; administrators always may
      python: { enabled: false, interpreter: "", workers: 2, timeout-seconds: 30, memory-mb: 1024, batch-rows: 65536 }
      java: { enabled: false, dir: ./plugins/pivot-functions }
```

Python custom functions never read memory-only data (decision 4: the source says `userCode: false` and the view is refused in words);
a per-store allowance is a later step. Every key is in [CONFIGURATION.md](../reference/CONFIGURATION.md) when built.

### 23.17 The API

| Call | Does |
|---|---|
| `POST /api/v1/pivot/query` | a view (JSON of [section 4.1](#2341-the-view)) → the answer; `Accept: application/vnd.apache.arrow.stream` for Arrow; `?progressive=true` streams the ≈ answer then the exact one (server-sent events) |
| `POST /api/v1/pivot/estimate` | the scan estimate and the options, nothing read |
| `POST /api/v1/pivot/expand` | a node's children (lazy expansion: the view, the node's coordinates, the dimension to expand by) |
| `GET /api/v1/pivot/cells/{ref}` | one cell by reference ([section 10](#2310-beyond-anything-out-there), item 8): value, exactness, provenance id |
| `GET /api/v1/pivot/cells/{ref}/rows` | drill-through, paged |
| `GET /api/v1/pivot/provenance/{id}` | the provenance of an answer or a cell |
| `POST /api/v1/pivot/explain` | the difference between two cells or two dates, as steps that add up |
| `POST /api/v1/pivot/compare` | two dates or versions, cell by cell |
| `GET, POST, PUT, DELETE /api/v1/pivot/views[/{owner}/{name}]` | saved views, versions, share, restore |
| `GET /api/v1/pivot/catalogue?source=` | dimensions, measures and functions a source offers this person |
| `GET, POST /api/v1/pivot/functions[/{owner}/{name}[@{v}]]` | custom functions: create, new version, list |
| `POST /api/v1/pivot/functions/test` | run the harness on sample rows: states, one-pass versus split-and-merge, cost |
| `POST /api/v1/pivot/functions/{owner}/{name}@{v}/approve` | approval (an approver, never the author), audited; `.../revoke` |
| `POST /api/v1/pivot/export` | an export: what (pivot, rows, chart, definition), format, options ([section 15](#2315-export)) |
| `/api/v1/cube/{cube}` | the MR and CCR cubes' address, kept; answered by the pivot engine |

Errors are in words with a `DRS-` code assigned when built and listed in API_GUIDE's error code table.

### 23.18 The command line

```
drishti.py pivot sources
drishti.py pivot fields trade
drishti.py pivot run --source trade --rows DESK --cols TRADE_DATE:month --values "sum(NOTIONAL_USD)" --date 2026-10-30 [--csv out.csv]
drishti.py pivot estimate --view asha/desk-risk
drishti.py pivot export --view asha/desk-risk --format xlsx -o out.xlsx      (also csv, arrow; --what rows --cell DESK=EMEA; --what definition)
drishti.py pivot views
drishti.py pivot save desk-risk --source trade --rows DESK --values "sum(NOTIONAL_USD)" --share role:risk
```

The same words as the API; `--wait` for background runs; a refused view prints the same sentence as the screen.

### 23.19 Who sees what, and audit

- **Entitlements and masks before aggregation**: the provider applies the person's row rules and masks in the plan before grouping
  (BI's security rewriter for models; the VaR engine's scope entitlements for VaR). A masked column cannot be a dimension; a cell whose
  members the person may see only in aggregate refuses drill-through with the reason; `aggregate-only.min-members-per-cell` hides cells
  with fewer members.
- **A shared view** opens with the reader's entitlements; it never carries the author's numbers.
- **Custom functions** receive only declared columns of rows the reader may see; they cannot widen what a person sees.
- **Audit**: every view run (who, source, data versions, rows read, functions with versions, time), every export, every function save,
  test, approval and revocation.
- **Memory-only data**: never spilled, never exported to a file unless the store allows downloads (audited), user code off by default.

### 23.20 Tests that prove it

| Test | Proves |
|---|---|
| `GroupingSetsCompilerTest` | one query for cells and every total level; parameters bound, never spliced; row rules before grouping |
| `MergeOrderTest` | the same digits for the same question on 1, 2 and 3 nodes and any arrival order |
| `TrueTotalsTest` | every non-additive total equals the measure on the union of its members (avg, ratio, distinct, percentile, VaR) |
| `SketchErrorTest` | HLL, KLL and Space-Saving within their stated errors on seeded data of 10K rows (the local test size); exact on request equals exact |
| `ExactQuantileTest` | iterative selection equals a sort on the same data, across splits |
| `FunctionHarnessTest` | the harness fails a function whose merge is wrong, non-deterministic, too slow or too large, and passes the built-ins |
| `PythonFunctionSandboxTest` | no network, no files, no processes, the time and memory limits, only declared columns; nothing written for memory-only data |
| `VarProviderParityTest` | the paused branch's tests unchanged: every cell and total equal to `/api/v1/var/scope` to the bit, sharded and whole |
| `LazyExpandTest` | expanding a node computes only its children; the expansion state round-trips through a saved view and a link |
| `OthersTest` | Others equals the measure over its own members (VaR from vectors), not total minus top N |
| `CurrencyAndUnitsTest` | mixed currencies refused unless converted; percentages not summed |
| `MaskBeforeAggregationTest` | a masked column cannot be a dimension; aggregate-only minimum members honoured |
| `CellReferenceTest` | references survive re-sort, new columns and a new date; a gone coordinate says so |
| `ExplainAddsUpTest` | the steps of every explanation add up to the difference |
| `ChartStackingTest` | a non-additive measure is never stacked; diversification drawn as its own segment |
| `XlsxPivotTableTest` | the native PivotTable opens with the view's layout and its totals equal the engine's; non-mergeable measures are not data fields |
| `BiParityTest` (phase PB) | every BI test suite and shipped report equal to the figures recorded from the code it replaces |
| `TrueTotalsTest`, `MqEngineTest`, `BiReportApiTest` (phase PB) | every BI total and subtotal from the engine's one statement equals the brute-force figure over the rows of its group |
| `FormulaFunctionTest`, `CustomFunctionsTest`, `test_pivot_fn.py` | formulas equal in Java and SQL; the harness gate, versions, approval, withdrawal, official runs; Python's four steps and sandbox |
| `PivotOffTest` | the engine switched off: 404 in words, the words gone everywhere, the cubes refused |
| browser test | the screen of the chosen mock-up: drag, expand, chart, provenance, playground; dark and light; phone width |

### 23.21 The benchmark plan

Results go to [BENCHMARK.md](../admin/BENCHMARK.md) when the runs are made (one benchmark document). Not run on a developer machine
(small local tests only); on the benchmark machines, one heavy job at a time.

| Run | Data | Nodes | Cache | Rollups | Views |
|---|---|---|---|---|---|
| B1 (warm-up) | 5 GB of synthetic trades (Parquet in Delta, 40 columns, 5 years of business dates) | 1, then 3 | cold, then warm | without, then with `DESK × month × CURRENCY` | V1 sum by desk × month; V2 + distinct counterparties (≈ and exact); V3 + p99 of P&L (≈ and exact); V4 a 6-level expansion; V5 a Python mergeable function |
| B2 | 25 GB | 1, then 3 | cold, warm | without, with | V1 to V5 |
| B3 | 75 GB (25 GB a node) | 3 | cold, warm | without, with | V1 to V5 |
| 100 GB, 1 TB, 3 TB | **EXTRAPOLATED** from B1 to B3 (a straight line through the measured points), labelled so, never presented as measured | 1 and 3 | | | |

**The sizes (product owner, 2026-11-02).** The benchmark machine's disk is limited: at most 25 GB read by one node and about 75 GB of
lake in all (three nodes), with a smaller warm-up size first. The data goes on the data disk under a folder the runner is given (never
`/tmp`, a 31 GB tmpfs shared with RAM); the runner refuses to start under 150 GB free and deletes what it generated when the run ends
(`--keep` keeps it). The earlier plan of 100 GB, 1 TB and 3 TB runs (3 TB on S3, 20 people at once) waits for machines that hold them;
until then those sizes are extrapolations. The harness is `tools/bench/pivot_bench.py`
([BENCHMARK.md section 16](../admin/BENCHMARK.md#16-the-pivot-engine-at-scale)).

Measured: time to the first (≈) and the exact answer, bytes read against the estimate, the estimate's error, peak memory per node,
spill bytes, the cache's hit rate, the merge's share of the time, and the cost of each function per billion rows. The synthetic trades
come from the seeded generators (JSON Lines to Delta, as every pack's data), clustered by date and desk.

### 23.22 Phases and sizes

| Phase | What | Size | Proposed release |
|---|---|---|---|
| **P1: the core, the tabular provider, the built-ins and the screen** | the module `drishti-bicompute` with the core migrated from `wip/mr-cube` (section 7) and DEL moved from BI; cell keys and grouping sets; mergeable and exact holistic built-ins; the tabular provider (BI layers, lake scans, memory-only); the VaR provider moved; the partial-state cache, budgets, busy protection, the cluster merge; correctness (item 9); provenance (4); time travel and compare (2); cell reference names (8); expand and collapse (section 11, P1 part); the pivot screen of the chosen mock-up with show-as, grouping, slicers, conditional formats, drill-through; pick-and-choose charts (14.3, P1 part); exports xlsx values with outline, csv, Arrow, Power Query, PNG, SVG; the engine switch; API and CLI | XL (two large parts: the core and provider; the screen) | 2.9.11.a |
| **MR cube on the engine** | the paused branch resumed: its VaR source as a provider, its landings A, B and C on the pivot screen's grid, its tests unchanged; FRTB-SA and SIMM providers when those engines ship | L | 2.9.11.a |
| **P2: custom functions, explain, what-if** | the four languages, the harness, the playground, versions, approval, audit; Python in the per-node pool (shipped off); explain the difference (1); what-if (3); view versions and cell comments (10); expand by and holistic Others (section 11, P2 part) | L | 2.9.11.b |
| **PB: BI on the pivot engine** | section 12: parity proved by tests, models as sources, measures as functions, one switch with the old code deleted | L | 2.9.11.b |
| **P3: scale** | sketches (HLL, KLL, Space-Saving) and exact on request; rollups of partial states refreshed incrementally; aggregate awareness; progressive answers; scan estimates with options; scatter and gather of tabular partials across nodes with hedging; live pivots (5); the benchmark B1 to B3 | L | 2.9.11.b |
| **P4: Excel and advice** | the native PivotTable export and charts bound to it; references in formulas and Excel (8); Python functions on by default where approved; layout advice; ask in words (6); automatic insights (7) | M to L | unscheduled candidate |

### 23.23 Decisions

**All 14 decided by the product owner on 2026-11-02, as recommended; mock-up A picked.**

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | Placement: three releases, 2.9.11.a (P1 + MR cube), 2.9.11.b (P2 + BI), 2.9.11.c (P3), each with at most two large items | yes, as proposed; the MR cube right after P1, because it needs only the core and the VaR provider |
| 2 | The module's dependencies beyond `drishti-api`, `drishti-common` and DuckDB | allow `arrow-format` (already in the build) and Apache DataSketches (Apache 2.0, pure Java) for KLL; HLL written in the module (it is small, and computed in SQL) |
| 3 | VaR totals: recompute from members (bit-equal to the VaR engine) or merge cells' vectors (equal to the cent, faster) | recompute from members, as the MR cube does today |
| 4 | Python custom functions on memory-only stores | off by default per store; a store's owner may allow it; data passed in memory only |
| 5 | Sharing a custom function | an approver who is not the author, per group or everyone; audited; revocable |
| 6 | Percentile partials across nodes: KLL built in Java from streamed values, or per-node `approx_quantile` points merged | KLL in Java (a stated, tested error); revisit if the benchmark shows the stream costs too much |
| 7 | Progressive answers on by default for views estimated over 2 s | yes; sampled figures always marked ≈ with their interval; never for VaR |
| 8 | A distributed SQL backend (Trino, StarRocks, ClickHouse) | parked; revisit only if B3 shows common views over 5 min on 3 nodes |
| 9 | BI on the engine | in 2.9.11.b: parity proved by tests against recorded figures, then one switch with BI's old aggregation deleted in the same release (product owner, 2026-11-02 and 2026-11-03: no dual machinery) |
| 10 | Where the screen lives | `PIVOT <GO>`, Views → Pivot, and **Pivot** on every table, model and cube |
| 11 | The mock-up | A ([section 25](#2325-mock-ups-the-screens-to-build)) |
| 12 | Aggregate-only roles: minimum members per cell | 1 as shipped (no change for anyone); a site may raise it |
| 13 | The native PivotTable carries only measures Excel re-aggregates correctly | yes; the rest on a Values sheet with a note |
| 14 | Custom functions in official runs | never; official measures stay Java engines |

### 23.24 Build checklist

- [x] `drishti-bicompute` module (no Spring; drishti-api, drishti-common, DuckDB); the architecture rule "no other module re-implements aggregation" is a build test (AggregationOwnerTest)
- [ ] core moved from `wip/mr-cube` with the eight changes of section 7 (built: changes 1 to 8 in the module; the branch's own tests run when the MR cube resumes on the engine)
- [ ] DEL moved from `server/bi/expr`; BI calls the module
- [x] function SPI, kinds, codecs; built-ins of section 5.2 except `first`/`last` and the sketches (P3); exact quantile from the values (selection with P3)
- [ ] grouping-sets compiler; DuckDB executor; tabular provider (built: Export data's tables staged in memory, direct Delta and Iceberg lake reads of local Parquet files with pruning and skipping, a panel's rows; not yet: BI layers, object stores)
- [ ] VaR provider (built: `VarPivotProvider`, each cell the engine's scope; `VarCubeSource`'s batching, hierarchy levels and measures with the MR cube); FRTB-SA and SIMM providers with their engines
- [ ] partial-state cache, cell budgets, busy protection, per-person limits (built: all but the per-person limit)
- [ ] cluster: partial codec, ordered merge, file assignment, hedging, a node down said in the cell (built: state codecs, the ordered merge of local shards; P3 on `wip/pivot-c`: the plan and partial-state codec, `/api/v1/net/pivot/partials`, files by rendezvous hashing or row shards, the gather in node order with hedging and a deadline; a node with no answer refuses the whole view (DRS-5720) rather than marking its cells)
- [ ] correctness: currencies, units, masks, no truncation, exact or ≈ (built: masks before aggregation, no truncation, exact or holistic marked, currencies (explicit conversion, mixed refused per cell), units (rates and percentages never summed, units on headers))
- [x] provenance per answer and cell; time travel (by business date; data versions pinned with P3); compare; cell references
- [x] expand and collapse (section 11, P1 part and expand by), lazy, saved in views and links
- [ ] the screen (chosen mock-up), show-as, grouping, slicers, conditional formats, drill-through, keyboard, phone tiers (built: mock-up A, show-as in the API, grouping, slicers, conditional formats (colour scale, data bars), drill-through, keyboard; not yet: phone tiers)
- [ ] pivot charts (section 14.3), linked both ways, stacking rule (built: chart of a selection, the stacking rule, a click opens the member; not yet: pinned charts, PNG and SVG export)
- [ ] exports: xlsx values with outline, csv, Arrow, Power Query, PNG, SVG; later the native PivotTable and bound charts (built: xlsx with outline, formulas and Provenance, csv, Arrow, rows, definition)
- [x] custom functions: four languages, harness, playground, versions, approval, audit, labels; Python pool `pivot-fn` (the compute SDK's guard and Arrow codec)
- [ ] explain the difference; what-if overlays; view versions; cell comments (built: explain, what-if, view versions with a diff of definitions and restore, cell comments kept with the versions (section 10.1); not yet: what-if on VaR through the engine)
- [ ] BI parity tests against recorded figures, models as sources, measures as functions, the old aggregation deleted (built: totals and subtotals of matrices, tables and model queries on the engine, measures as functions, additivity, the old per-level queries deleted, the parity recorder and `BiParityTest`; not yet: the fixtures recorded, models as pivot sources on the pivot screen, the Slice, rollups of states)
- [ ] sketches, rollups of states, incremental refresh, aggregate awareness, progressive answers, scan estimates, live pivots (built on `wip/pivot-c`, tests written and not yet run: HyperLogLog, KLL and Space-Saving with exact on request and exact or ≈ by size; rollups of partial states per partition, refreshed by version checks with only new files read, aggregate awareness with date coarsening, advice; progressive answers from a sample with intervals; estimates from measured rates with options; live pivots on data loads and live panels through the cache, patches and reshapes (`LivePivotTest`, run); the screen's switches exact / auto / ≈, progressive, use rollups and live with `computedAs` (`ScaleSwitchesTest`, run); a level's top N by Space-Saving when ≈ is asked (section 10.1); not yet: the batch-as-merge form of live pivots on streams)
- [ ] `config/engines/pivot.yaml`, the catalogue entry (`mr-cube` and `ccr-cube` need `pivot`), CONFIGURATION.md (built: `config/engines/pivot.yaml`, the catalogue entry, `mr-cube` needs `pivot`; `ccr-cube` when it is built)
- [x] API, `drishti.py pivot`, error codes in API_GUIDE
- [ ] tests of section 20; the benchmark of section 21 into BENCHMARK.md (P3: `SketchErrorTest`, `RollupTest`, `ClusterMergeTest`, `ProgressiveTest` written; the harness `tools/bench/pivot_bench.py` and BENCHMARK.md section 16 written; the runs to be made)
- [ ] docs: a pivot guide and a tutorial (UI, CLI and YAML at every step), Help entries, a custom-function guide, the MR cube guide on the engine (built: the guide, tutorial 33, Help entries)
- [ ] Rachana paths as the field notation (one parser, compiled to columns or DuckDB JSON; the grain from `[*]`); BI's examples in the `$.` form (built: the pivot side; the BI datasets' `$.` form to come)
- [x] documents ([section 6.7](#2367-documents-paths-arrays-and-grains)): the field list from the contract and the document's shape; promoted, dataset and ad-hoc routes with the scan estimate; one grain per view; coarser figures once per parent; separate lists never crossed; filters per grain; Promote and Add to dataset for administrators
- [x] the pivot guide's worked examples of section 6.7, each with its real figures from the demo lake, a screenshot, the command line and the saved view's YAML

### 23.25 Mock-ups: the screens to build

Made 2026-11-02 for the product owner to choose; self-contained HTML (system fonts, everything inline, nothing from a CDN), dark by default
and light with `#light`, phone-safe. Each shows two scenes: **the pivot screen** (field list, drop zones, grid with ≈ and exact markers, a
holistic VaR column beside mergeable sums, nested expandable rows, a provenance popover, a scan estimate, a chart from a selection) and
**the playground for a custom aggregation function** (Python code, sample rows, one pass against split and merge, cost).

| Option | Idea | Screenshot |
|---|---|---|
| [A: Excel-familiar, field list on the right](mockups/pivot/A.html) (**recommended**) | the PivotTable people know: field list and four zones on the right, the grid with outline rows, the provenance popover on a VaR cell, the scan estimate above, a pinned chart beside it; the playground as three panes: code, sample rows, the check | [A.png](mockups/pivot/A.png) |
| [B: terminal first](mockups/pivot/B.html) | the command line writes the view (`PIVOT rows DESK cols month values ...`), zones as chips under it, explain and what-if in a right rail; the playground as a notebook of the four steps | [B.png](mockups/pivot/B.png) |
| [C: compare and insight canvas](mockups/pivot/C.html) | two dates side by side with changed cells highlighted and insights flagged; the playground as a four-step wizard | [C.png](mockups/pivot/C.png) |

**Why A:** nothing new to learn for anyone who has used Excel; everything beyond Excel (≈ and exact, VaR beside sums, provenance,
estimates, expand by, charts) sits where Excel's equivalents would be; B's command line and C's compare mode are both reachable from A (the
command line is always there; compare is a toggle).

### 23.26 Sources

- DuckDB, aggregate functions: `approx_count_distinct` (HyperLogLog), `approx_quantile` (T-Digest), `reservoir_quantile`,
  `approx_top_k` (Filtered Space-Saving), `weighted_avg`, `quantile_cont`, `median`, `mad`. https://duckdb.org/docs/stable/sql/functions/aggregates
- DuckDB, tuning workloads: larger-than-memory processing by spilling for grouping, joins, sorting and windowing; some aggregate states
  cannot yet be offloaded; not designed for many small concurrent queries; thread settings. https://duckdb.org/docs/stable/guides/performance/how_to_tune_workloads
- DuckDB, `CREATE MACRO`: a scalar macro that references an aggregate behaves as an aggregate. https://duckdb.org/docs/stable/sql/statements/create_macro
- DuckDB, Python functions: scalar UDFs, Arrow batches of up to 2,048 rows. https://duckdb.org/docs/stable/clients/python/function
- DuckDB C API aggregate functions (init, update, combine, finalize callbacks): `duckdb_create_aggregate_function`,
  `duckdb_aggregate_function_set_functions`. https://duckdb.org/docs/stable/clients/c/api
- DuckDB community extension `datasketches` (Apache DataSketches inside DuckDB; community maintained). https://duckdb.org/community_extensions/extensions/datasketches
- P. Flajolet, É. Fusy, O. Gandouet, F. Meunier, "HyperLogLog: the analysis of a near-optimal cardinality estimation algorithm", AofA 2007
  (relative standard error about 1.04 / √m).
- Z. Karnin, K. Lang, E. Liberty, "Optimal quantile approximation in streams", FOCS 2016 (KLL).
- Apache DataSketches, KLL sketch: k = 200 gives a normalised rank error of about 1.65%; sketches merge. https://datasketches.apache.org/docs/KLL/KLLSketch.html
- T. Dunning, O. Ertl, "Computing extremely accurate quantiles using t-digests", 2019 (arXiv:1902.04023).
- A. Metwally, D. Agrawal, A. El Abbadi, "Efficient computation of frequent and top-k elements in data streams", ICDT 2005 (Space-Saving).
- T. F. Chan, G. H. Golub, R. J. LeVeque, "Algorithms for computing the sample variance", 1983 (merging variance states).
- ECMA-376, Office Open XML, Part 1, section 18.10 (PivotTables: pivot cache definition, records, pivot table definition).
- Microsoft, Excel specifications and limits (1,048,576 rows by 16,384 columns per worksheet). https://support.microsoft.com/en-us/office/excel-specifications-and-limits-1672b34d-7043-467e-8e27-269d656771c3
- Delta Lake, data skipping (per-file statistics, `delta.dataSkippingNumIndexedCols`, 32 by default). https://docs.delta.io/latest/optimizations-oss.html

## 24. Compute workers: Python and Java libraries, approved, pinned and isolated

Status: **steps C1 to C3 built and built (2026-10-26)**; C4 to C6 planned, in no release yet ([section 16](#2416-build-plan)). Built: the `drishti-compute`
module (supervisor, Python pools in fork and warm mode, JSON-lines control, Arrow scratch transport, limits and the sandbox, fair
scheduling, fan-out over N workers and Drishti\*net nodes), the pure-Python worker SDK `drishti_compute` with its sample engine,
`requires:` on connector files, locked environments with manifests, Java libraries by sha256, the four-eyes approval in the Drishti
database, and Admin → Compute ([OPERATIONS.md](../admin/OPERATIONS.md#19-compute-workers), the operator's guide). Still **planned**: the pricer interface and the
stand-in (C4), QLRisk (C5, and **design only** until its author declares its API stable), the QuantLib Java worker (C6), the reactive
connectors that send work to the pools (DATA_ARCHITECTURE.md R1 to R6), the planned `drishti.py compute`, the pre-approval file and per-pool OS
users. Each section says what differs from the build where it does. Related: [DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#4-reactive-connectors) (the connectors that use these workers),
[VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) (the first user), [AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed) (the earlier pricing-service proposal,
reconciled in [section 14](#2414-how-this-fits-with-what-exists)), [BI_GUIDE.md](../guides/BI_GUIDE.md#34-python-in-bi-measures-visuals-and-data-prep-written-in-python) (Python items, run on the pool
`python`: [section 19](#2419-the-python-runtime-for-code-people-write)).

### 24.1 The ask

Reactive connectors ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#4-reactive-connectors)) compute their answers. The computations a bank wants are
mostly written in Python (numpy, polars, scipy, QuantLib, the product owner's QLRisk) and sometimes in Java (QuantLib's own Java
binding, a vendor or in-house pricer). This document says **where that code runs**, **how its libraries arrive** (pinned, verified,
approved by an administrator) and **how it is kept from harming the server, the data or other users**.

### 24.2 Decisions

| # | Decision (product owner, 2026-10-24) | Consequence |
|---|---|---|
| 1 | Python runs in **out-of-process workers**: a pool, data passed as **Arrow**, run **as the user**, from a **pinned environment**, **Windows-safe**. | A worker crash, a memory leak or a long calculation never touches the server's JVM. |
| 2 | **No FFI and no embedded CPython; Java stays on release 21.** | No Panama/FFM, no JEP or GraalPy in the server process. |
| 3 | A connector declares **`requires:`**: `python` (a locked environment with pinned wheels, including QLRisk), `java` (Maven coordinates with sha256, from the bank's repository or shipped), and `plugins`. **An administrator approves before use.** | Nothing is downloaded or run on a connector author's word alone. |
| 4 | Java pricing uses **QuantLib's own Java binding (QuantLib-SWIG Java)**: the native library built per OS, **Linux first**, packaged in a versioned jar, **extracted under the configurable data folder** (never `/tmp`), loaded **in an isolated Java worker process**. Its tests **skip on Windows** (no native binaries may be placed on the QA machine). | The server never loads a native library. |
| 5 | **QLRisk is the default pricing provider, behind a pluggable pricer SPI.** It is used once its API is declared stable; what-if pricing goes through QLRisk only once it is stable. | The SPI and a parity test are built whatever QLRisk's state. |

### 24.3 The shape

```
 Drishti server (Java 21)                                            Worker processes (children of the server's supervisor)
 ┌─────────────────────────────────────────────────────┐             ┌──────────────────────────────────────────────┐
 │ reactive connector ── ComputeEngine bridge           │  control:   │ Python worker  (env A: drishti-var, numpy,   │
 │   │                                                  │  JSON lines │   polars, qlrisk 2.4.0, QuantLib 1.x wheels) │
 │   ▼                                                  │ ──────────▶ │   one request at a time; no network          │
 │ drishti-compute (module, built: C1-C3)               │  data:      │   scratch: <data dir>/compute/scratch/<req>/ │
 │   planner · admission · routing · Arrow transport    │  Arrow IPC  ├──────────────────────────────────────────────┤
 │   result cache · stamps · audit                      │ ◀────────── │ Python worker  (env B: another lock)         │
 │   supervisor: start, watch, limit, recycle, kill     │             ├──────────────────────────────────────────────┤
 │   environment builder · approval store               │             │ Java worker (JDK 21, own classpath):         │
 └─────────────────────────────────────────────────────┘             │   QuantLib-SWIG jar + libQuantLibJNI.so from │
                                                                     │   <data dir>/compute/native/quantlib/<ver>/  │
                                                                     └──────────────────────────────────────────────┘
```

- **`drishti-compute`** (the Maven module, built) is generic: nothing in it knows about VaR. It plans and admits requests, moves data as
  Arrow, supervises workers, builds environments and records approvals.
- **The VaR maths** is a separate, versioned Python package in the repository (planned: `compute/drishti-var`, its own
  `pyproject.toml` and tests), installed into the var-engine's locked environment like any other wheel. Drishti's own compute packages
  are versioned and approved exactly like third-party ones.
- **Workers never read data themselves.** The server reads inputs through the connector router as the requesting user, masks them, and
  hands the worker Arrow batches ([DATA_ARCHITECTURE.md › Reactive connectors, section 6](DATA_ARCHITECTURE.md#6-the-request-and-its-lifecycle)).

### 24.4 `requires:`: what a connector needs to run

```yaml
# in config/connectors/var-engine.yaml                         (built: parsed, approved, gated; the reactive plugin is R1)
requires:
  python:
    version: "3.12"                          # the interpreter the environment is built with
    lock: requires/var-engine.lock           # the lock file, beside the connector file (below)
    index: bank-pypi                         # a named package source of config/compute/compute.yaml, or "shipped"
  java:
    - coords: org.quantlib:quantlib-java:1.36.0-drishti.1     # built by us, shipped or in the bank's repository
      sha256: 4b1f…e09a
      classifier: linux-x86_64
      source: shipped                        # shipped | bank-maven
      native: true                           # carries a native library: Java worker only, never in the server
  plugins:
    - name: drishti-plugin-delta             # a connector plugin the computation depends on (must be installed and enabled)
```

*Implementation:* `requires:` is a key of every connector file (`ConnectorFiles`); a connector whose requirements are not all approved stays off
and says why (state `NOT_APPROVED`, *waiting for approval of N requirements (request cr-…)*). Plugins are recorded as approval items;
checking that they are installed arrives with the reactive plugin (R1). `requested-by:` (optional) names the person who asked, for four
eyes; without it, the administrator who last saved the file through Admin → Connectors.

The **lock file** pins every wheel, including transitive ones, with its hash. It is generated once by a tool (planned:
`drishti.py compute lock`, planned), reviewed, committed with the connector, and never resolved again at install time:

```text
# requires/var-engine.lock            (planned, not built) python 3.12, platforms: manylinux_2_28_x86_64, win_amd64
drishti-var==0.1.0     --hash=sha256:0d6a…      # Drishti's own package, built from compute/drishti-var
numpy==2.1.3           --hash=sha256:a7c1…  --hash=sha256:51e9…
polars==1.12.0         --hash=sha256:3f20…
pyarrow==18.0.0        --hash=sha256:9be4…
scipy==1.14.1          --hash=sha256:c2d8…
QuantLib==1.36         --hash=sha256:77ab…
qlrisk==2.4.0          --hash=sha256:1e5f…      # from the author's wheel (qlrisk-2.4.0-py3-none-any.whl), shipped
```

- **Python packages** come from one of: `shipped` (wheels inside the Drishti distribution or a pack archive, under a `wheels/`
  folder), a **named index** the site configures (the bank's internal PyPI mirror, Artifactory, Nexus), or a **local path** an
  administrator names. Public PyPI is never used unless the site names it as an index.
- **Java libraries** are Maven coordinates with a sha256, from the bank's Maven repository (the site's `settings.xml` mirror) or
  shipped. A jar marked `native: true` (or found to contain a `.so`, `.dll` or `.dylib`) can only run in a Java worker.
- **Plugins** are Drishti connector plugins the computation needs (installed jars, as today); they are checked, not installed.
- **The same mechanism serves any connector**, not only reactive ones: a connector plugin of a vendor that needs a Java client
  library can declare it the same way.

### 24.5 Locked environments

| Rule | How |
|---|---|
| **One environment per lock**, shared by every connector with the same lock | the environment's id is the sha256 of (Python version, platform, lock file); two connectors with the same lock use one environment |
| **Built under the data folder**, never in a system location or `/tmp` | `<data dir>/compute/envs/<env id>/` (a venv); its wheels cache `<data dir>/compute/wheels/` |
| **Offline and exact** | `python -m venv`, then `pip install --require-hashes --no-deps --only-binary=:all:` from the approved sources; no source builds, no resolution, no network beyond the named index |
| **Read-only once built** | the environment folder is made read-only; a worker cannot install into it; a changed file is detected by a manifest of hashes checked at worker start |
| **Never changed in place** | a new lock is a new environment; the old one stays until no approved connector uses it, then is removed by `drishti.py compute gc` (planned; `Environments.gc` is built) |
| **The interpreter** | the site's Python (`compute.python`, a path; `python3` by default). Drishti does not download interpreters. |

Building an environment is an administrator action (or automatic after approval, `compute.build: on-approval`), shown with its log in
Admin → Compute. *Implementation (C2):* environments of **pools** (`pools.<p>.environment`) are built and verified at every worker start; a
connector's own `requires.python` is approved now and gets its environment when the reactive plugin runs its engine (R2). Java libraries
are checked by sha256 and scanned for native code, and staged under `<data dir>/compute/jars/<sha256>/`. A build that fails (a hash mismatch, a wheel missing for the platform) leaves no environment behind and names the
wheel.

### 24.6 Approval

Nothing in `requires:` is fetched, built or run until an administrator approves it.

```
 author commits a connector with requires: ──▶ server loads it: "waiting for approval" (Health DOWN, panels "not approved yet")
        │
        ▼
 Admin → Compute → Requests: the diff against what is approved today, per package:
   name, version, hash, source, licence, size, native code (yes/no), known vulnerabilities (from the site's scanner feed, if set)
        │
   approve (with a reason) ──▶ environment built ──▶ connector starts        reject (with a reason) ──▶ stays off
```

- **What needs approval:** a new package, a new version, a new hash for the same version, a new source, a new Java library. Removing a
  package does not.
- **Who:** an administrator (built: any administrator, or only those holding one of `compute.approval.roles`; a dedicated
  `compute-approve` role power is planned); never the person who changed the connector file (four
  eyes, as for write-back approvals).
- **Recorded:** each decision is an audit entry (who, when, what, why), and the approved set is kept in the Drishti database
  ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#6-the-one-drishti-database-design-not-built)) as rows of a planned `drishti_compute_approval` table, so every node of a cluster sees the same
  approvals.
- **Revocation:** an administrator can revoke an approval; new requests are refused at once, in-flight ones finish.
- **Signed packs:** a pack archive from the signed registry may ship wheels and jars; the signature proves where they came from, not
  that the site wants them: approval is still required.
- **A site may pre-approve** a list in `config/compute/approved.yaml` (planned, open question 5; not built) for automated deployments; the file is itself an
  administrator's change and audited when loaded.

### 24.7 The worker pool

#### 24.7.1 Pools

A **pool** is a set of worker processes of one type and environment, configured once per site (built; the shipped file is
`config/compute/compute.yaml`, every key in [OPERATIONS.md › Compute workers, section 2](../admin/OPERATIONS.md#2-pools-and-how-many-workers)):

```yaml
# config/compute/compute.yaml                                  (built)
compute:
  enabled: false                    # off as shipped: no worker starts, reactive connectors say "compute is off"
  python: ${DRISHTI_PYTHON:}        # the interpreter; empty: python3
  pools:
    risk:
      type: python
      engines: [drishti_var.connector:VarEngine]                       # module:Class, loaded once per worker
      environment: { python: "3.12", lock: locks/risk.lock, index: shipped }   # or: system (the site's interpreter and the SDK)
      workers: { min: 2, max: 8 }   # started on demand up to max; idle ones beyond min stop after 5 min
      memory: 4GB                   # per worker (address-space limit on Linux; watched on Windows)
      cpu-seconds: 300              # per request
      recycle-after: 200            # requests, then the worker is replaced (bounds slow leaks)
      batch-share: 0.25
    pricing-java:                   # planned (C6): type java is refused until then
      type: java
      workers: { min: 0, max: 2 }
      memory: 3GB                   # -Xmx plus native headroom
      jvm-args: ["-XX:+UseZGC"]
  nodes:                            # built: a node's own worker counts, by its Drishti*net id
    drishti-big-1:
      pools:
        risk: { workers: { max: 64 } }
```

**As many workers as the site wants** (product owner, 2026-10-24, a hard requirement). The worker count is configuration only, per pool
and per node; the code has no upper bound and no part of it assumes one worker. Pools scale out by adding Drishti\*net nodes that serve
the same pool, each with its own count, and one request can be spread over all of them ([section 7.6](#2476-fan-out-and-scale-out)).

#### 24.7.2 Process model

- **One request at a time per worker.** Concurrency comes from the number of workers. A worker never holds two users' data at once.
- **Linux: fork-server.** A parent imports the
  environment's heavy modules once (numpy, pyarrow, QuantLib) and **forks one child per request**; the child runs the request and exits.
  Start-up cost is paid once; isolation between requests is total (a fresh address space). *Implementation:* each worker is such a parent (N
  workers, N parents, each forking one child at a time), so a request's child can be killed alone at a timeout or cancel and the worker
  stays.
- **Windows: warm workers, reset between requests.** There is no `fork`; workers are started with `spawn`, kept warm, and **reset**
  between requests (module-level caches cleared by the engine contract, temporary files removed, the worker recycled after
  `recycle-after` requests or on any error). Recycling is more frequent by default on Windows (`recycle-after: 50`).
- **Java workers** are separate JVMs (`java -cp <approved jars> com.ash.drishti.compute.worker.Main`), one request at a time, recycled
  like Python workers; a native crash kills only that JVM.

#### 24.7.3 Transport

| Channel | Carries | How |
|---|---|---|
| control | request, progress, result metadata, errors | JSON lines on the worker's standard input and output |
| data in | the inputs (Arrow record batches) | Arrow IPC files in the request's scratch folder `<data dir>/compute/scratch/<request id>/in/` (memory-mapped by the worker); small inputs inline |
| data out | large results (a 509-row path, a contribution table) | Arrow IPC files in `…/out/`, read by the server, validated, then the folder is deleted |

Files rather than sockets: they work the same on Linux and Windows, need no port, and let the worker memory-map large inputs. The
scratch folder is created per request, readable only by the server's OS user, and deleted when the request ends (also after a crash,
by the supervisor's sweep at start).

#### 24.7.4 "As the user"

A worker runs **on behalf of** the requesting user in four ways: its inputs were read and masked as that user; the request carries the
user's identity for audit (never credentials); results are delivered only to that user's request id; and its limits count against the
user's quota. It does **not** switch operating-system user by default (that would need the server to run as root); a site that wants
OS-level separation can run each pool under its own service account (`pools.<p>.os-user`, Linux only, planned), which isolates pools
from one another and from the server's files.

#### 24.7.5 Limits

| Limit | Linux | Windows |
|---|---|---|
| wall time | the supervisor kills the process group at the request's timeout | the supervisor kills the process at the timeout |
| CPU time | `RLIMIT_CPU` | watched by the supervisor (planned; the wall time is enforced) |
| memory | `RLIMIT_AS` | working set watched every 250 ms; killed above the limit |
| files | `RLIMIT_FSIZE`; writes only under the scratch folder (audit hook) | writes only under the scratch folder (audit hook) |
| network | none: a network namespace with no interfaces where the machine allows it, plus an audit hook refusing sockets (built: the audit hook; the namespace is planned) | an audit hook refusing sockets |
| processes | an audit hook refusing `subprocess`, `os.fork`, `os.exec*` | the same |

#### 24.7.6 Fan-out and scale-out

Built in C1 (product owner's requirement: work fans out across N workers and nodes). A request may be **split into chunks** and gathered:
one named input is cut into chunks of `compute.fan-out.chunk-rows` rows; every other input (market data, scenarios) goes whole with each
chunk; each chunk is an ordinary request with its own id (`<parent>-c<i>`) and the parent's viewer, class and budget, so fairness,
limits and isolation apply per chunk; answers are gathered **in chunk order** (rows appended, tables concatenated). Chunks go to the
target with the lowest *(outstanding + 1) / workers*: this node's pool and every other Drishti\*net member alive that serves it (asked
for its load, cached two seconds). Chunks for another node travel signed, on behalf of the viewer, one hop (the receiving node runs them
on its own workers only). An unreachable node's chunk runs here; any other failure fails the whole request and cancels what is left. At
most twice the total worker count of chunks is outstanding (back-pressure). This is the shape what-if pricing needs: trades × 509
scenario days spread over every worker of the cluster.

#### 24.7.7 Fair scheduling

One queue per pool and class (`interactive`, then `event`, then `batch`); `batch` holds at most `batch-share` of the workers; within a
class users take turns and none runs more than `compute.scheduling.max-inflight-per-user` at once; each class's queue is bounded
(`compute.scheduling.queue`) and a full queue refuses at once (`DRS-9203`), which a fan-out answers by waiting and retrying. Waiting for a
worker counts against the request's budget.

### 24.8 The engine contract (Python)

```python
# compute/drishti-var/src/drishti_var/connector.py             (planned, not built)
from drishti_compute import Engine, Request, Inputs, Progress, Result    # the worker SDK shipped with Drishti (built, pure Python)

class VarEngine(Engine):
    kinds = ("var-result", "var-contribution", "var-path", "var-history", "what-if")

    def compute(self, req: Request, inputs: Inputs, progress: Progress) -> Result:
        vectors = inputs.arrow("pnl-vector")            # a pyarrow.Table, already filtered and masked as the user
                                                        # (inputs.table(...) gives the SDK's own table: no pyarrow needed)
        ...
        progress.partial({"done": 7, "of": 12, "estimate": {"VAR_99": 41_200_000}})
        return Result(rows=[...], arrow=None)           # validated against the kind's schema by the server

    def incremental(self, req, previous, events, inputs):  # optional (DATA_ARCHITECTURE.md › Reactive connectors, section 10.2)
        ...
```

- **Pure:** no network, no files outside the scratch folder, no global state that outlives a request (the SDK clears caches registered
  with `register_cache` between requests in warm mode; fork mode needs nothing).
- **Deterministic:** the same inputs and request give the same result (random numbers only from a seed in the request).
- **Typed errors:** `ComputeError("the scope is empty")` reaches the panel in words; any other exception is *the computation failed*
  with the request id, and the traceback goes to the server's log, never to the browser.

### 24.9 Pricing: one SPI, two engines

Base VaR never prices (it adds precomputed vectors, [VAR_GUIDE.md Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives)). Pricing is needed for **what-if** (a hypothetical,
amended or partly unwound trade priced over the 509 scenario days) and for stresses.

#### 24.9.1 The pricer SPI

```python
class Pricer(Protocol):                                  # planned (C4), not built, in drishti_compute.pricing
    name: str                                            # "qlrisk", "quantlib-java", "stand-in", a bank's own
    def products(self) -> set[str]: ...                  # product codes it can price (from the pack's pricer mapping)
    def price(self, trades: list[TradeDoc], market: MarketState) -> list[Valuation]: ...
    def scenario_pnl(self, trades: list[TradeDoc], base: MarketState, scenarios: ScenarioSet) -> "pyarrow.Table": ...
                                                         # one row per trade, a 509-long P&L vector each
```

- The **pack's pricer mapping** (the `quant: pricers:` block proposed in [AI.md › Server-side pricing: the quant service (proposed), section 6](../admin/AI.md#6-packs-declare-how-products-are-priced))
  maps a trade document's UPPER_SNAKE fields to a product and its parameters; it is shared by every pricer, so a product is mapped
  once.
- The connector chooses its provider: `pricing: { provider: qlrisk, fallback: none }`. **QLRisk is the default.**
- Every valuation says which provider, which version and which model priced it (in the result's stamp).

#### 24.9.2 QLRisk (default, Python)

- **Design only until the API is declared stable** (the product owner, 2026-10-10 and 2026-10-24). Drishti neither imports nor
  modifies QLRisk until then; the adapter is the only file that will import it.
- **What QLRisk 2.4.0 offers** (read from its README, not yet relied on): `MarketRiskEngine.scenario_pnl(trades, market, scenarios)`
  returning a P&L object with `var()` and `es()`; historical, age-weighted and filtered scenarios; Euler and incremental
  decomposition; `SensitivityEngine`; `apply_shocks` for stresses; 90+ products. The adapter maps the pack's trade documents to QLRisk
  products and Drishti's scenario set to QLRisk's historical scenarios, and returns the per-trade P&L vector as Arrow.
- **Installation:** the author's wheel (`qlrisk-2.4.0-py3-none-any.whl`) is shipped or placed in the bank's index and pinned by hash
  in the lock; its dependencies (numpy, scipy, QuantLib) are pinned in the same lock.
- **Its temporary folder:** QLRisk 2.4.0 writes temporary files to `/tmp` unless told otherwise; the worker sets `QLRISK_TMP_DIR` to
  the request's scratch folder under the data folder, so nothing is written outside it (Drishti's rule: nothing at the machine's
  temporary folder).
- **Its own thread pool:** QLRisk has a multi-threaded engine (`engine.max_workers`); inside a worker it is limited to the worker's
  CPU share (default 1 thread per worker, so the pool, not the library, decides parallelism).
- **When QLRisk is not stable in time:** what-if is limited to what needs no pricing (removing or scaling a linear position,
  [VAR_GUIDE.md section 8.4](../guides/VAR_GUIDE.md#84-what-if-pricing-and-intraday-recomputes)) and the stand-in pricer for tests; full
  what-if pricing moves to 2.8.x.

#### 24.9.3 QuantLib-SWIG Java (decided)

| Topic | Design |
|---|---|
| **Build** | QuantLib (C++) and QuantLib-SWIG's Java binding built by us from a pinned QuantLib release, per OS and architecture: **linux-x86_64 first**, then linux-aarch64; Windows and macOS later and only if wanted. The build is a script in the repository (planned: `tools/quantlib-java/build.sh`), run in a container, producing `quantlib-java-<ql version>-drishti.<n>-<classifier>.jar` with the native library (`libQuantLibJNI.so`) inside under `native/<classifier>/`. |
| **Pinned** | the QuantLib version and our build number are in the jar's version; the jar's and the native library's sha256 are in the lock; the parity test (below) runs for every new build. |
| **Extracted under the data folder** | at worker start the native library is extracted to `<data dir>/compute/native/quantlib/<version>/<sha256>/libQuantLibJNI.so` (never `/tmp`, never `java.io.tmpdir`), its sha256 checked **before** `System.load`, the folder made read-only. A second extraction of the same hash reuses it. |
| **Isolated** | loaded only in a **Java worker process** of a `type: java` pool, never in the server's JVM; a segmentation fault in native code kills that worker, the supervisor replaces it and the request fails with *the pricer crashed*. |
| **Java 21** | the worker runs on the same JDK 21 as the server; JNI, not FFM. |
| **Windows** | not built for Windows; the QuantLib-Java tests **skip on Windows** with the reason *native QuantLib library: no native binaries on the Windows QA machine* ([section 12](#2412-windows)). |
| **Licence** | QuantLib is BSD-licensed; the notice goes into `THIRD-PARTY-NOTICES.md` with the jar. |

#### 24.9.4 Parity between the two

A **parity test** prices the same set of trades (one per supported product, on the sample market of a fixed date, plus the 509
scenario days for a handful) with QLRisk and with QuantLib-Java and requires agreement within a tolerance per product (default: NPV
within 1e-6 relative or 0.01 currency units; scenario P&L vectors within 1e-6 relative at every point). It runs in the build of either
engine and on every new QuantLib, QLRisk or adapter version; a failure blocks approval of the new version.

### 24.10 Java compute libraries

A connector may use a Java library (a vendor pricer, an in-house risk library) behind a planned Java compute SPI
(`ComputeEngine`, [DATA_ARCHITECTURE.md › Reactive connectors, section 16](DATA_ARCHITECTURE.md#16-the-spi-and-the-apis)):

| Library is | Runs | Class loading |
|---|---|---|
| pure Java, trusted, light (a few ms per call, bounded memory) | in the server process (built, R1: Drishti's own engines always; others if the site allows it, `compute.in-process-java: false` by default) | its own classloader per connector, parent = the API only (no Spring, no server classes), like plugins today |
| heavy, untrusted, or carrying native code | in a Java worker | its own JVM; the approved jars only |

### 24.11 Security and sandboxing

- **Inputs are already masked.** A worker never receives a field the requesting user cannot see.
- **No credentials, no network.** Workers start with an empty environment (only what the engine needs: `PYTHONHASHSEED`, `QLRISK_TMP_DIR`,
  thread limits), no tokens, and no network ([section 7.5](#2475-limits)).
- **Read-only code.** The environment and the extracted native libraries are read-only and checked against their manifest at start.
- **No code from requests.** A request carries parameters, never code; the only code that runs is the approved engine. The one exception
  is the pool `python`, whose engine runs code people write, and only in the strict sandbox ([section 19](#2419-the-python-runtime-for-code-people-write)).
- **Outputs are untrusted.** The server validates each result against the kind's schema and size limits before it is cached or shown;
  text in results is escaped like any data.
- **Supply chain.** Hash-pinned wheels and jars, approved sources only, a vulnerability feed shown at approval, a native-code flag per
  package, and the approval audit trail.
- **Resource fairness.** Pools, per-user in-flight limits and the batch share ([DATA_ARCHITECTURE.md › Reactive connectors, section 10](DATA_ARCHITECTURE.md#10-coalescing-incremental-recompute-and-back-pressure)).

### 24.12 Windows

The Windows QA machine accepts **no `.exe` and no native binary placed by hand**; Java (jars, Maven dependencies) and the console's
Python venv (pip packages) are allowed.

| Part | On Windows |
|---|---|
| Python workers | work: `spawn` instead of `fork`, warm workers reset between requests, limits watched by the supervisor ([section 7](#247-the-worker-pool)) |
| Locked environments | work: built from `win_amd64` wheels with `pip --require-hashes`, under the data folder; the lock lists both platforms |
| QuantLib for Python, QLRisk | QuantLib's Python wheel is a pip package (it contains a compiled extension); whether this counts as allowed on the QA machine is **an open question** ([section 18](#2418-open-questions-for-the-product-owner)); until answered, QLRisk-based tests on Windows skip with a reason, and the stand-in pricer covers the flow |
| QuantLib-SWIG Java | not built for Windows; its tests skip with the reason *native QuantLib library: no native binaries on the Windows QA machine* |
| Network isolation | no namespace: the audit hook alone refuses sockets, so the pool `python` (code people write, strict isolation) does not run on Windows ([section 19](#2419-the-python-runtime-for-code-people-write)) |
| Paths | everything under the data folder; no `C:\Temp`, no `%TEMP%` |

### 24.13 Configuration

| Setting (`config/compute/compute.yaml`, planned) | Default | Meaning |
|---|---|---|
| `compute.enabled` | `false` | built: master switch: off as shipped; a fresh install or an upgrade starts no worker |
| `compute.python` | `python3` (`python` on Windows) | built: the interpreter workers run and environments are built with (`DRISHTI_PYTHON`) |
| `compute.indexes.<name>.url` | — | built: a package source (the bank's mirror); `trust` and credentials planned |
| `compute.java-repository` | — | built: a folder or URL in Maven layout for `java:` requirements with `source: bank-maven` (replaces the planned `compute.maven.settings`) |
| `compute.shipped` | `<data dir>/compute/shipped` | built: wheels and jars of the `shipped` source |
| `compute.build` | `manual` | built: `manual` or `on-approval`: when environments are built |
| `compute.approval.roles` | `[]` | built: roles whose administrators may approve (empty: any administrator) |
| `compute.scheduling.max-inflight-per-user` | `4` | built: one user's requests running at once per pool |
| `compute.scheduling.queue.interactive/event/batch` | `256`, `10000`, `100000` | built: waiting requests per pool and class; full: busy |
| `compute.fan-out.chunk-rows`, `compute.fan-out.peers` | `5000`, `true` | built: chunk size, and whether chunks go to other nodes ([section 7.6](#2476-fan-out-and-scale-out)) |
| `compute.limits.max-result-bytes` | `256MB` | built: the largest result file a worker may hand back |
| `compute.in-process-java` | `false` | built (R1): allow Java engines that are not Drishti's own to run in the server process (Drishti's own, `com.ash.drishti.*`, always may) |
| `compute.pools.<p>.*` | — | built: `type` (python), `engines`, `environment` (`system` or `{python, lock, index}`), `workers.min/max`, `mode`, `memory`, `cpu-seconds`, `recycle-after`, `batch-share`, `idle-stop`, `timeout`, `timeout-batch`, `start-timeout`, `python-path`; planned: `jvm-args` (C6), `os-user` |
| `compute.nodes.<node>.pools.<p>.workers` | — | built: this node's own worker counts |
| `compute.scratch` | `<data dir>/compute/scratch` | built: request folders |
| `compute.approved` | `config/compute/approved.yaml` | planned: optional pre-approvals (open question 5) |

Folders under the data folder: `compute/envs/`, `compute/shipped/`, `compute/jars/`, `compute/sdk/`, `compute/scratch/`, `compute/work/`
(built); `compute/native/`, `compute/results/` (planned).

### 24.14 How this fits with what exists

| Exists today | Relation |
|---|---|
| **Code people write** (Python items, Python-driven tables, Notebook cells) | Runs on these pools too: the pool `python` and its engine `drishti_compute.items`, in the strict sandbox with pinned libraries ([section 19](#2419-the-python-runtime-for-code-people-write)). Different trust from an approved engine, so a stricter sandbox; the same supervisor, queue, limits and audit. |
| **Arrow to the browser** (`ArrowAnswers` and `core/arrow_answer.py`) | The same Arrow IPC stream format as the workers' transport, used where it was measured to pay: the VaR explorer's contribution tables by position and by trade; the P&L path, BI grids and search stay JSON or HTML. Where Arrow is used and where it is not, with the numbers: [DATA_GUIDE.md › Data formats: JSON Lines is the standard, section 6](../guides/DATA_GUIDE.md#6-arrow-to-the-browser-where-it-pays) |
| **The pricing-service proposal** ([AI.md](../admin/AI.md#4-server-side-pricing-the-quant-service-proposed), ADR-019) | Superseded in its **deployment shape**: instead of a separate HTTP service, pricing runs in compute workers supervised by the server. Its other decisions stand: pricing outside the server's JVM, the `quant` power, pack pricer mappings, market data assembled by the server for a business date, caching by trade version and date. A remote pool (workers on other machines, reached over the network) is a later option and would look much like that service. |

### 24.15 Operations

- **Admin → Compute** (built, C3: pools, workers busy, queues, latency, environments, approval requests, recent requests; Test, Build,
  Cancel, Approve, Reject, Revoke; the per-connector view arrives with R6): the master switch; pools (workers, busy, memory, recycled, crashed); environments (id, lock, size,
  built when, used by); approval requests and decisions; native libraries extracted; the per-connector view of
  [DATA_ARCHITECTURE.md › Reactive connectors, section 15](DATA_ARCHITECTURE.md#15-observability-and-audit).
- **Health:** a pool whose workers keep crashing is `DEGRADED`; an environment that fails its manifest check is `DOWN` with the file
  named.
- **CLI** (planned): `drishti.py compute status|lock|build|approve|revoke|gc`. The HTTP API behind Admin → Compute is built
  (`/api/v1/admin/compute`, [OPERATIONS.md › Compute workers, section 7](../admin/OPERATIONS.md#7-admin--compute)).
- **Upgrades:** a Drishti upgrade never changes an approved environment; new versions of Drishti's own compute packages arrive as a
  new lock and an approval request.

### 24.16 Build plan

| Step | Delivers | Accepted when |
|---|---|---|
| C1 (**built**) | `drishti-compute` module: supervisor, Python pools (Linux fork-server, Windows spawn), JSON-lines control, Arrow scratch transport, limits, the `drishti_compute` shim and engine contract | a test engine computes over 1 million Arrow rows in a worker; a crashing or looping engine is killed and replaced; the scratch folder is gone after every request |
| C2 (**built**) | `requires:` parsing, lock files, environment builder (`--require-hashes`, offline), read-only environments with manifests | an environment builds from a local wheel folder with no network; a tampered wheel fails the build; a tampered file in a built environment stops the worker |
| C3 (**built**) | approval: requests, Admin → Compute, the approval table, four eyes, revocation, audit | a connector with unapproved requirements stays off and says so; approval by its author is refused |
| C4 (planned) | the pricer SPI, the stand-in pricer, the pack pricer mappings (shared with AI.md › Server-side pricing: the quant service (proposed), section 6) | a what-if on a sample swap prices with the stand-in through the SPI |
| C5 (planned) | QLRisk provider (**only if its API is declared stable**; otherwise 2.8.x) | QLRisk prices the sample products; the parity test runs |
| C6 (planned) | QuantLib-SWIG Java: the build script (linux-x86_64), the jar, extraction under the data folder, Java worker pools, parity with QLRisk | the parity test passes on Linux; the tests skip on Windows with the stated reason |

### 24.17 Tests

*Implementation (C1 to C3):* `drishti-compute`'s `ComputePoolTest` (a million rows, crash, loop, CPU and memory limits, isolation, progress,
the sandbox, cancel), `WarmModeTest` (the Windows way, on every platform), `FanOutTest` (two nodes, a node down), `EnvironmentTest` (an
offline build from a local wheel, a tampered wheel, a tampered environment, Java libraries), `ApprovalsTest` (memory and SQLite),
`FairQueueTest`, `ComputeSettingsTest`, the configurable `ComputeLoadTest`; the SDK's pytest suite (`drishti-compute/src/test/python`);
`ComputeApiTest` in the server (a connector off until approved, four eyes, revocation) and the console's `test_admin_compute.py`. The
"nothing written outside `<data dir>/compute/`" check is by construction (temporary folders, scratch and environments are all under it).

- **Isolation:** two requests of different users on one pool never share a process at the same time; a module-level variable set by
  one request is not visible to the next (Linux: fork; Windows: reset and recycle).
- **Limits:** memory, CPU, wall time, file writes outside the scratch folder, sockets, subprocesses: each refused or killed, each with
  a clear error.
- **Supply chain:** a hash mismatch, a missing platform wheel, an unapproved version, a jar with an undeclared native library.
- **Paths:** nothing is written outside `<data dir>/compute/` (checked by listing the machine's temporary folder before and after).
- **Parity:** QLRisk and QuantLib-Java agree within tolerance (Linux only).
- **Windows:** the Python pool, environments and the stand-in pricer pass on the Windows QA machine; native tests skip with reasons.

### 24.18 Open questions for the product owner

1. **QuantLib's Python wheel on the Windows QA machine.** It is a pip package but contains a compiled extension (`.pyd`). Is that
   allowed (it is installed by pip into the venv, not placed by hand), or must QLRisk-based tests skip on Windows?
2. **QLRisk's stability date.** When is QLRisk's API expected to be declared stable? If not, the what-if pricing step (C5)
   moves to 2.8.x as agreed; please confirm.
3. **Where our QuantLib-Java jar is published.** Shipped inside the Drishti distribution, or pushed to the bank's Maven repository by
   the bank (we provide the build script)?
4. **Operating-system users for pools.** Is per-pool OS-user separation (Linux) wanted, or is the process isolation of
   [section 7](#247-the-worker-pool) enough to start?
5. **Pre-approval file.** Should automated deployments be allowed to pre-approve requirements in the planned `config/compute/approved.yaml`, or
   must every approval be made in Admin → Compute?
6. **Vulnerability feed.** Does the bank have a scanner feed (for example an OSV mirror) Drishti should show at approval time?


### 24.19 The Python runtime for code people write

Every Python run in Drishti is the server's, on the compute pool **`python`**: python visuals and Python measures of reports, boards and
alerts, Transforms behind Python datasets, Python-driven tables and models (their sample run in the editor and their rows when read), the
Notebook's Python cells, the Transform editor's step-by-step run, the profile of a frame and the tests an item ships with. The console runs
none, imports no compute library (a static test holds it: `drishti-console/tests/test_console_runs_no_compute.py`), and the server never calls
the console to run Python. One way per job: one runtime, one sandbox, one set of pinned libraries.

**The engine.** The pool loads `drishti_compute.items:ItemsEngine` (`drishti-compute/src/main/python/drishti_compute/items.py`): kinds `run`
(an item: its manifest with the source, its parameters, the input files and limits), `check` (the source parsed, its entry and imports checked;
nothing runs) and `about` (the interpreter's versions, for the pin check). A request carries the rows as **files** (Arrow IPC streams in `in/`,
handed over as they are, since a BI frame holds dates and decimals the SDK's own codec does not read) and a Transform's table comes back as a
file (`out/result.parquet`); `ComputeRequest.files` and `ComputeResult.files` carry them, also between Drishti*net members. A failure of the
author's code is an **answer** (`ok: false`, its kind and the author's own traceback), never a crash of the worker.

**The server's side.** `PythonRuns` (drishti-server, `bi/python`) is the one door: it refuses in words while code cannot run safely (below),
checks the item's packages against the pinned allow-list and the input size, caps the item's time and memory
(`drishti.bi.report.python-max-seconds`, `python-max-memory-mb`), submits to the pool **as the person** (their viewer, for fairness and the
audit), maps the answer to `DRS-9070` to `9074` (the traceback for authors only) and writes one audit line per run (`python-run`: who, what,
the pool, the outcome, the time, the bytes). `PythonFrames` reads the rows a run needs **as the person running it** (a model query, a dataset's
fields, a Transform input, a visual's rows): their row rules and masks apply before anything reaches Python. A Transform's result is checked
against its **output contract** (the columns and types the dataset or table declares; a Python-driven table's sample run also checks its keys
and never-empty columns) and refused in words when it does not match. Dates reach Python as ISO text, so a text column that the contract
declares a `date` or a `timestamp` is read as one on the pool before the check. A value that does not read leaves the column as text, and
the check refuses it.

**The strict sandbox** (`isolation: strict` on the pool; [section 11](#2411-security-and-sandboxing) is the standard one):

| Part | How | Where it cannot be had |
|---|---|---|
| No network | the worker moves itself into a user and network namespace of its own at start (no interface; Landlock also refuses TCP from ABI 4) | the worker does not start and says why |
| Files | each request's fork child is held by **Landlock** to reading the Python installation, the module path, the system libraries and its scratch folder, and to writing that folder only, before the request's code runs (so C and C++ code is held too) | as above |
| Processes, loader | the engine's audit hook refuses `subprocess`, `os.system`, `exec*`, `spawn*`, `fork`, `ctypes`; `exec`, `eval`, `compile` are not given | |
| Imports | only the manifest's packages (each on the pinned allow-list) and safe standard-library modules | |
| Time, memory | `RLIMIT_CPU` and `RLIMIT_AS` of the item's own (lowered from the pool's), the pool's wall-time budget | |
| Pinned libraries | `config/compute/locks/python.lock` (hashes of every file, from `python.in` by `tools/python_lock.py`): a locked environment installs exactly those; with `environment: system` the server asks the pool for its versions first and refuses every run while one differs | |
| Audit | one line per run | |

**Where it cannot be safe, it does not run.** The strict sandbox needs Linux (fork mode, user namespaces, Landlock 5.13+). On Windows, where
neither a network namespace nor Landlock exists and no `.exe` may be placed, Python runs are off: every run answers `DRS-9074` with the
reason, and the Notebook's Run and the editor's Sample run are off and say why. Approved network sources are read by the server and handed
in as inputs; the code itself never reaches a network.

**Configuration.** `compute.enabled` (off as shipped), `compute.pools.python` (`config/compute/compute.yaml`: workers, memory, CPU seconds,
timeouts, `isolation: strict`, `environment`), `drishti.bi.report.python-*`, `drishti.bi.transform.*`. Status: `GET /api/v1/bi/python/runtime`.

## 25. Compute engines on or off, each with its own configuration file

Status: built (2.9.11 row 14, [the plan](IMPLEMENTATION_PLAN.md#2911-sensitivities-and-market-risk-capital-released-2026-11-02)); mock-up A, with C as
its Cluster tab. How it was built, and where it differs from the text below, is [section 17](#2517-as-built). The administrator's guide is
[OPERATIONS.md section 21](../admin/OPERATIONS.md#21-compute-engines-on-or-off).

Companion to [EDITIONS.md](../admin/EDITIONS.md) (editions and lenses: what people may see), [USER_MANAGEMENT.md, Functions](../admin/USER_MANAGEMENT.md#functions-func-every-command-and-turning-one-on-or-off)
(a command switched off for everyone), [OPERATIONS.md section 18](../admin/OPERATIONS.md#18-drishtinet-running-several-servers-as-one)
and [CLUSTER_DEPLOYMENT.md](CLUSTER_DEPLOYMENT.md) (Drishti\*net: nodes, capability digests, routing),
[section 24](#24-compute-workers-python-and-java-libraries-approved-pinned-and-isolated) (the compute workers and their own file `config/compute/compute.yaml`) and
[CONFIGURATION.md](../reference/CONFIGURATION.md) (every setting, generated).

**What the product owner asked for (2026-11-02, in their words):** "using admin I will be able to enable or disable frtb saccr etc etc
and when done so they should disappear from commands and everywhere"; "Basically I should be able to enable or disable compute engines
using config and or admin ui"; "Since not all instances will be running everything"; "I think different compute engines should have
their own config yaml file and main application.yaml should refer to these, else it will become very hard to manage".

### 25.1 The idea in one page

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

### 25.2 The engines

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
| `mr-cube` | the market-risk cube on the pivot engine (the VaR source's hierarchy levels and MR measures, ladders, heat map, explain the move; MR_CUBE_GUIDE.md) | `mr-cube.yaml` | `drishti.cube.mr.*` | `MCUBE` (the pivot screen with the MR lens), `/mcube`, `/api/v1/pivot/mr` | `var`, `pivot` | `sensitivities` (ladders, heat map), `frtb-sa` (the capital measure chip) |
| `ccr-cube` | the counterparty cube (limit board, allocation, group roll-up, pre-deal overlay) | `ccr-cube.yaml` | `drishti.cube.ccr.*` (new) | the CCR Cube lens, `/ccube`, its landing and chips | `exposure` | `sa-ccr` (the EAD chip, incremental EAD tile), `fva` (the FVA chip and tile) |
| `openblas` | linear algebra on the bundled OpenBLAS, **off as shipped**; off: the pure-Java kernels, the same figures, chosen (OPERATIONS.md section 2.1) | `openblas.yaml` | `drishti.native.*` | nothing hidden: off switches the many-scope sums to Java, live; on runs the self-test first; `drishti.py server blas` | none | |
| `memory-lake` | Delta tables held in memory only, with their replenish jobs | `memory-lake.yaml` | `drishti.lake.memory.*` | Admin → Lakes' Memory tab, `drishti.py lake memory`, `mem://` stores | none | |

Not engines, and not in this design: the compute workers (they already have their own file, `config/compute/compute.yaml`, and switch,
`compute.enabled`; Admin → Engines shows them as a row of their own, read from that file, so one page answers "what runs here"), BI,
Export data and the other platform features (their switches stay where they are, in `application.yaml` and the editions' `features:`).
A future engine (liquidity, climate, CVA) adds one catalogue entry and one file; nothing else in this design changes.

### 25.3 One file per engine

#### 25.3.1 The folder

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

#### 25.3.2 What a file holds

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
    parameters-dir: ${DRISHTI_FRTB_PARAMETERS:./config/packs/market-risk/capital}
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

#### 25.3.3 How application.yaml names them

`spring.config.import` gains one entry pair per engine, **before** the local file and the packs overlay, so the site's local file still
wins over an engine file:

```yaml
spring:
  config:
    import:
      # the engines' own files (docs/architecture/ARCHITECTURE.md section 25): the jar's copy, then the site's, which wins key by key
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

### 25.4 Moving today's settings without breaking an install

| Today, in `application.yaml` | After | An existing install |
|---|---|---|
| `drishti.var.*`, `drishti.regulatory.*` | `config/engines/var.yaml`, same keys | unchanged: its overrides in `application.local.yaml` or the environment still win |
| `drishti.var.enabled` | `drishti.engines.var.enabled` (the switch) | `drishti.var.enabled` keeps working as an **alias** (below) |
| `drishti.ccr.exposure.*` | `config/engines/exposure.yaml`, same keys | unchanged |
| `drishti.ccr.exposure.enabled` | `drishti.engines.exposure.enabled` | alias |
| `drishti.frtb.*` | `config/engines/frtb-sa.yaml` (the sensitivity keys to `sensitivities.yaml`) | unchanged; `drishti.frtb.sensitivity-kind`, `factor-kind`, `cache-minutes` read as aliases of the `drishti.sensitivities.*` keys |
| `drishti.frtb.enabled` | `drishti.engines.frtb-sa.enabled` | alias |
| `drishti.lake.memory.*` | `config/engines/memory-lake.yaml`, same keys | unchanged; `drishti.engines.memory-lake.enabled` defaults to **true when a store is configured** in the old place, so an install that uses it keeps it on |
| the engine's `config/editions/features.yaml` entries (`var: { server: [drishti.var.enabled] }`) | name the new switch (`drishti.engines.var.enabled`) | an edition that presets `var: off` still turns VaR off |
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

### 25.5 What "off" means

#### 25.5.1 On the server

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

#### 25.5.2 In the console, everywhere

The console learns the engines from `GET /api/v1/about` (a new `engines` field: each id with `here`, `cluster` and `title`), refreshed on
the live channel when it changes. One helper, `engine_available(id)` beside `edition.word_on`, answers for every place below; an engine
is available when it runs on this node or on any node of the cluster.

| Place | When the engine is available nowhere |
|---|---|
| Command line | its words are unknown, as a word an edition hides: `FRSA DESK-RATES <GO>` answers "FRTB-SA is not running on this server" (`DRS-1020`) |
| Type-ahead, search hints, FUNC, examples | its words, examples and command rows are not offered |
| Who opens it (2.9.11.a) | a running engine's column of the Engines menu and its command words follow the column's `opens:` in `menus.yaml` (`role:<name>`, `pack:<name>`; administrators always): a role that does not open it does not see the column, is not offered its words in the type-ahead and is told "<title> is not opened for your role; ask an administrator." when it types one (`core/menus.py` `engine_refused`, one rule for both). Help is documentation and is not gated by it. A console-side check: the server's own entitlements still decide the data |
| Menus | its column of the Engines menu is not drawn (a column names its engine with `engine: <id>` in `menus.yaml`, and its `opens:` the roles that see it), nor any item of another menu that names it (`requires: engine:<id>`); a menu left empty is not drawn |
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

#### 25.5.3 Connectors and kinds

An engine's catalogue entry splits what it touches in two:

- **Its own:** the connectors it registers and the kinds it computes. Off: not loaded, not registered.
- **What it reads:** input kinds from packs (`pnl-vector`, `scenario-pv`, `exposure-path`, `simulation-run`, `position-sensitivity`,
  `saccr-trade`). An input kind marked `engine-input: true` in the catalogue (its only use is to feed engines, and it is large) is not
  loaded on a node where **no running engine reads it**: its connector stays registered for the pack's own pages (`XPATH` browses a
  path), but no date of it is read ahead, warmed or pinned. Reference kinds (trades, counterparties, netting sets, books) are never
  affected: the packs' pages use them.

So a node with only `var` on never reads exposure paths, and a node with `exposure` and `sa-ccr` never reads P&L vectors, while both
still show the counterparty's page.

### 25.6 Dependencies between engines

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

### 25.7 Turning an engine on or off while the server runs

| Change | Effect | When |
|---|---|---|
| **Off** (any engine) | the engine stops taking new requests at once (`404`, routed elsewhere in a cluster); requests in flight finish (up to `drishti.engines.drain`, default 30 s, then cancelled with a clear message); its dates close, caches and buffers are released, threads end, its connectors and computed kinds are unregistered; open pages are told on the live channel and redraw without it | live |
| **On** (every engine but `memory-lake`) | `start`: parameter files read and checked, connectors registered, threads created; the newest business date loads on first use as today (or ahead, per the engine's own warm-up settings); the console is told and shows the words | live |
| **On** `memory-lake` | written to the file; Admin → Engines says **restart needed**: the stores' caps are added to `-XX:MaxDirectMemorySize` by the launchers at start | restart |
| A setting other than `enabled` | each key says `live` or `restart` in CONFIGURATION.md as today; Admin → Engines edits only the switch and names the file for the rest | as the key says |

**Restart needed** is shown as a pill on the engine's row, kept until the server restarts, with the time of the change and who made it.
The admission gate applies to an engine coming on: if the server is short of memory, turning on an engine that loads ahead is refused
politely (`503 DRS-9502`) and the page says so.

### 25.8 Admin → Engines

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

### 25.9 In a cluster: each node runs its own engines

Each node reads its own `config/engines/` folder, so nodes differ by design: the VaR shard owners run `var`, the counterparty nodes run
`exposure`, `sa-ccr`, `fva` and `ccr-cube`, a small console node runs none.

- **The capability digest** ([CLUSTER_DEPLOYMENT.md section 6](CLUSTER_DEPLOYMENT.md#6-what-a-node-offers-the-capability-digest)) gains
  `engines`: each engine running here with its state and version. A change raises `digestHash`; peers pull it within a gossip round.
- **Routing** follows the capability table, as for packs: a request for `/api/v1/frtb/**`, an `FRSA` command, a `PLAY FRTB` page or a BI
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

### 25.10 The command line

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

### 25.11 The API

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

### 25.12 Configuration document and checks

- `tools/settings_catalogue.py` gains the area **Engines: `config/engines/*.yaml`**, one table per engine file, read with the same
  comment reader as `application.yaml` (each key's meaning is its comment; `${VAR:default}` gives the variable). CONFIGURATION.md and
  Help → Configuration list every engine key under its file; the server area no longer lists the moved keys, and the moved keys' old
  names are listed once as aliases with the new name.
- `--check` fails when a shipped engine file and its jar copy differ, when a catalogue entry has no file or a file no entry, and when a
  key appears in both `application.yaml` and an engine file.
- `drishti.py server engines show <id> --sources` and Admin → Engines name the file each value comes from (section 3.3).

### 25.13 Tests that prove it

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

### 25.14 Build checklist

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

### 25.15 Mock-ups: Admin → Engines

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

### 25.16 Decisions

**All decided by the product owner on 2026-11-02, as recommended; mock-up A picked** ([section 15](#2515-mock-ups-admin--engines)).

| # | Question | Decided (as recommended) |
|---|---|---|
| 1 | The CLI's place: `drishti.py server engines` (beside `server commands` and `server packs`) with `admin engines` as an alias, or `admin engines` only? | **`server engines`, with `admin engines` accepted**: one place for administration over REST, and the words the product owner used still work |
| 2 | Off answers `404` (not here) rather than today's `403` (switched off)? | **404**: nothing is forbidden, the engine is not on this server; the words say so |
| 3 | `memory-lake` turned on from the page: write the file and say "restart needed", or refuse from the page? | **Write and say "restart needed"**: the administrator's choice is recorded and takes effect at the next start |
| 4 | An administrator changing another node's engine from any node's Admin → Engines? | **Yes**, signed with the cluster key and audited on both nodes |
| 5 | Regulatory VaR (backtesting, reports, sign-off) as part of `var`, or an engine of its own? | **Part of `var`** for now: it reads only VaR's official records; split when a node needs one without the other |

**All decided by the product owner on 2026-11-02, as recommended; mock-up A picked** ([section 15](#2515-mock-ups-admin--engines)).

### 25.17 As built

| Part | Where | Notes |
|---|---|---|
| The catalogue | `drishti-server/src/main/resources/engines/catalogue.yaml`; the console's copy `drishti-console/config/engines.yaml` (same bytes, `EngineCatalogueTest`) | one entry per built engine (VaR, exposure, sensitivities, FRTB-SA, SIMM, the MR cube, the in-memory lake, data loads, the pivot engine, Flight); SA-CCR, FVA and the CCR cube of this design are not listed and have no file until their engines are built (each then adds its entry, its files and a `ComputeEngine` bean); `installed: false` stays for an engine listed ahead of its build; the console's paths per engine are its `console:` list (`=/play` is that path only); the longest API prefix wins (`/api/v1/sensitivities` is the sensitivities', not FRTB-SA's) |
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
