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
in a strict sandbox ([PYTHON_COMPUTE.md section 19](PYTHON_COMPUTE.md#19-the-python-runtime-for-code-people-write)); the console runs none.

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
| **Source** | A plugin (`SourcePlugin`) that can `fetch`, and optionally `subscribe`, `search` and `reverse`; every document carries its *generation* (monotonic version). A **connector** is a named instance of a plugin with its own settings (`market-store` is a `delta` connector); it is a site resource kept as one file, `config/connectors/market-store.yaml`, that the registry watches and applies live, one connector at a time (see [../guides/DATA_GUIDE.md section 9](../guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors)). |
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

The ViewModel's every field, panel kind and live patch: [VIEWMODEL.md](VIEWMODEL.md).

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
`murex-rates`); `SutraMatcher` chose `irs-fixfloat v1` from `config/packs/banking/trading/sutras/murex/`; inference added what the
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
| Read a new store (a database, a topic, a folder, a service) | A connector: a file `config/connectors/<name>.yaml` with `plugin:` and `settings:` (or Admin → Connectors); the pack names it in `routes:` for the kinds it serves, and may carry a `connectors:` template for it | [../guides/DATA_GUIDE.md section 9](../guides/DATA_GUIDE.md#9-connector-files-and-admin--connectors), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Add a domain (new kinds, commands, links) | A pack folder `config/packs/<domain>/<name>/` with `pack.yaml`, `sutras/`, `samples/`; enable it with `DRISHTI_PACKS` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which) |
| Change how one screen looks | Its Sutra (`*.sutra.yaml`), or a Design in the Build workbench, proposed and approved | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |
| Change how a figure is formatted | `fmt:` in the Sutra; named formats in the pack's `config/formats.yaml` | RACHANA_REFERENCE.md |
| Change how inference reads field names | The pack's `config/semantics.yaml` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra) |
| Add a panel kind | The engine (`Binder` and the panel model) and a console renderer (`_macros/panels.html`) | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#panels-the-twenty-two-kinds-and-developing-a-new-one), [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#part-viii-changing-drishtis-code-the-developer-guide) |
| Decide who may see what | `roles:` in `pack.yaml` (kinds, `raw`), `drishti.security.redact` (fields shown as `•••`), the Admin pages | [ADMIN_GUIDE.md](../guides/ADMIN_GUIDE.md) |
| Add a command word | `mnemonics:` in `pack.yaml` (or under `drishti.commands` for a site) | PACK_DEVELOPER_GUIDE.md |
| Add a link between kinds | `graph.fields` in `pack.yaml` and `link(...)` in the Sutra | PACK_DEVELOPER_GUIDE.md |
| Change the sentence the About drawer says about a kind | `kinds.<kind>.about` in the pack's `config/about.yaml` (a `${...}` template over the document); for a generated pack, the about source in `tools/packgen/` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#about-text-and-glossary) |
| Explain a field (term, meaning, unit, sign) | `glossary.<field path>` in `about.yaml`, or a shared `vocabulary.<name>` entry in the lowest pack that owns the concept (the glossary layer and field hints are step 4 of [RACHANA_REFERENCE.md › About this page: the design of context help](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help); section 3.9 says what is in today) | PACK_DEVELOPER_GUIDE.md |
| Change how the drawer looks or behaves | `drishti-console/web/templates/_macros/about.html` and `terminal/_about.html`, `static/js/drishti_about.js`, `static/css/drishti_about.css` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help) |
| Change what the explanation says about the layout and the data | `ExplainService`, `PageContext`, `EmptinessReason` (`drishti-engine`, package `explain`), `MatchTrace` (`SutraMatcher.explain`); the endpoint is `ExplainController`; limits under `drishti.explain.*` | [API_GUIDE.md](../guides/API_GUIDE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Decide who may share, comment or be mentioned | the role power `roles.<role>.collaborate` (on unless removed); `drishti.collab.packs.<pack>.share-enabled`; `mentionable-roles`, `directory.scope`, `share.max-recipients`, `limits.*` under `drishti.collab` | [CONFIGURATION.md](../admin/CONFIGURATION.md), [ADMIN_GUIDE.md](../guides/ADMIN_GUIDE.md) |
| Change what a note or comment may contain, or what is masked in it | `drishti.security.redact` (the fields), `drishti.collab.text.on-masked-copy` and `deny-patterns`; the logic is `NoteText`, `Entitlements.maskedValues`, `CommentRenderer` | CONFIGURATION.md, §3.10 |
| Switch on email, or change what it says | `spring.mail.*`, `drishti.collab.console-url`, `email.enabled`; per pack `packs.<pack>.email.content` (`comment`, `title`, `link-only`); templates in `drishti.collab.email.templates-dir`; the code is `EmailNotifier`, `OutboxDispatcher`, `MailRenderer` | [OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#122-collaboration-email-and-the-outbox) |
| Add another channel (a chat bridge) | one more `Notifier` bean: `ShareService` and `ThreadService` hand every bean the audience, so neither service changes | [COLLABORATION.md](../architecture/COLLABORATION.md) (phase 2) |
| Keep, hold or export what people wrote | `drishti.collab.retention.*` and `packs.<pack>.retention-days`; legal holds and the export through `/api/v1/admin/collab` (`CollabPurge`, `HoldService`, `ExportService`, `ChainVerifier`) | [OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#123-retention-legal-holds-and-the-compliance-export) |
| Change how the Share dialog, the inbox or the Discussion tab look or behave | `drishti-console/web/templates/terminal/_share.html`, `inbox.html`, `_discussion.html`; `static/js/drishti_share.js`, `drishti_inbox.js`, `drishti_alerts.js`, `discussion*.js`; `static/css/drishti_collab.css` | [COLLABORATION.md](../architecture/COLLABORATION.md) (console design) |
| Let people search a kind by filling in a form | the `form:` section of `pack.yaml` (fields, types, operators), or a Design's Form tab; people open it with `FORM <pack>` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind), [USER_GUIDE.md](../guides/USER_GUIDE.md) |
| Let people choose what a panel shows | `controls:` in the Sutra (dropdowns, sliders, date ranges), read by panels as `ctl.<id>` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#controls) |
| Tell Drishti a batch of data landed (refresh, verify, tell people, flag late data) | `POST /api/v1/packs/{pack}/loads` from your ETL; what a pack expects is its `loads:` section | [DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed) |
| Reach a source over TLS or through a proxy | `tls.*` and `proxy:` in the connector file | [DATA_GUIDE.md section 10](../guides/DATA_GUIDE.md#10-tls-and-the-outbound-proxy), [DATA_GUIDE.md, the outbound proxy](../guides/DATA_GUIDE.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Make a dataset, report or board (business intelligence) | YAML files in `config/bi/` (watched) or the BI pages (workspaces, designer, Model view); the query runs on the server over DuckDB with the reader's masks and row rules | [BI_GUIDE.md](../guides/BI_GUIDE.md), [BI_GUIDE.md](../guides/BI_GUIDE.md#30-datasets-the-yaml-the-expression-language-and-the-security-rules), [BI_GUIDE.md](../guides/BI_GUIDE.md#31-reports-the-yaml-the-visuals-and-how-they-behave), [BI_DESIGN.md](BI_DESIGN.md#16-rupaka-the-original-design-and-every-phase) |
| Show a view inside another web application | register the application in Admin → Embedding; the page uses `<drishti-view>` | [CLIENT_RENDERER.md](CLIENT_RENDERER.md#20-drishti-elements-the-design) |
| Use a language model, or an MCP tool server | a provider file in `config/ai/` or `config/mcp/` and `drishti.ai.enabled` / `drishti.mcp.enabled` (both off by default) | [AI.md](../admin/AI.md#1-model-providers), [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy) |
| Run several servers as one (Drishti\*net) | the files of `config/net/` (`node.yaml` with `enabled: true` and the cluster key, a seed in `peers/`), or Admin → Net → Files; members advertise a real address, never a loopback one | [OPERATIONS_GUIDE.md](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet), [tutorial 19](../tutorials/22-a-cluster-of-three.md) |
| Lay a report out on paper (a statement, one per recipient) | `layout: statement` on a report page and the report's `statement:`; BI Studio's Paper tab | [STATEMENTS.md](../guides/BI_GUIDE.md#24-statements-paginated-reports-page-exact-one-per-reader) |
| Make something that failed (a pack, a connector, a provider) try again | nothing: it is retried with backoff; Retry now in Admin | [OPERATIONS_GUIDE.md](../guides/OPERATIONS_GUIDE.md#138-retries-what-failed-is-tried-again-by-itself) |

---

## 6. Rachana — the screen grammar — and Sutras written in it

Rachana is the grammar; each Sutra is one YAML document written in it (ADR-017, which supersedes ADR-011's
Markdown Sutras). The first key, `rachana: 1`, is the language version, so the grammar can evolve without
misreading old files; `description`, `notes` and a per-panel `description` carry the prose. Files are named
`<name>.v<N>.sutra.yaml` and are found by scanning, recursively, the site directories (`drishti.rachana.dirs`, default `./sutras`) and every enabled
pack's `sutras/` folder (`config/packs/<domain>/<pack>/sutras/<area>/…`). `SutraBuilder` validates each one at load against the panel
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
 config/packs/<domain>/<pack>/sutras/**/*.sutra.yaml        the entity's document (any connector)
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

**The Sutra.** `TRD MX-20000001` matches `config/packs/banking/trading/sutras/murex/irs-fixfloat.v1.sutra.yaml` (generated
by `tools/packgen/banking/make_sutras.py`). One strip cell, and the first panel, which it takes from a fragment:

```yaml
match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }
strip:
  - { label: Notional, bind: "$.CURRENCY + ' ' + fmt(first($.LEGS).NOTIONAL, 'amount0')", emphasis: true }
panels:
  - { use: trading/deal-ticket@1, with: { rate-fmt: pct4, product: "Interest rate swap (fixed/float)" } }
```

The fragment (`config/packs/banking/trading/sutras/common/deal-ticket.v1.fragment.yaml`) is a `kv` panel, still only YAML:

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
`config/packs/banking/market-risk/samples/risk-hierarchy.jsonl` was put in the `file` connector's folder of the scratch
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

**The YAML.** A pack lists the fields people search a kind by; from `config/packs/banking/trading/pack.yaml`:

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
| A new computed measure | the computation: a Java engine (as VaR and exposure in `drishti-compute`, packages `var` and `ccr`) or a Python engine behind a `reactive` connector; its result is then an ordinary kind, drawn by the generic view | [REACTIVE_CONNECTOR.md](../connectors/types/reactive/README.md), [VAR_GUIDE.md, Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) |

### The hand-built pages and why

Some pages are hand-built. Each is a product feature that works across packs and kinds, built on the same server
APIs, entitlements and masks as the generic view; none is a screen for one product or one mnemonic.

| Page | Routes | Templates | Why it is its own page |
|---|---|---|---|
| VaR explorer | `/var` (`VAR <scope> <GO>`) | `terminal/var.html`, `static/js/drishti_var-explorer.js` (drishti-risk's grids and charts) | Any scope (hierarchy node, book, basket, trade) computed on demand by the VaR engine (`/api/v1/risk/var`), with drill-down, captures and compare; the computed kinds stay ordinary kinds on `/v/…` too. [VAR_GUIDE.md](../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen) |
| Exposure explorer | `/exposure` (`EXP <scope> <GO>`) | `terminal/exposure.html`, `static/js/drishti_exposure-explorer.js` (drishti-risk's grids and charts) | The same for counterparty exposure (`/api/v1/risk/exposure`); `exposure-result` is still a kind. [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md#3-the-explorer-screen-by-screen) |
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

The settings are in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtiindex--the-multi-day-type-ahead-index). Streaming
connectors keep the newest day only. Measured: a prefix lookup over 100,000 ids a day for five days takes 0.002 ms on
the heap and 0.04 ms from a segment file read with positional reads (548 KB on disk for 100,000 ids)
([CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md#161-the-servers-knobs)).

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
| `aerospike` | a record per entity per business date (`kind/id/yyyyMMdd`), found through the entity's index record listing its dates ([AEROSPIKE_CONNECTOR.md](../../plugins/drishti-plugin-aerospike/README.md)) |
| `feeds` | keeps dated observations of public feeds |

```
console ──X-Drishti-As-Of──▶ AsOfResolver ─▶ BusinessDates ─▶ ViewPipeline ─▶ SourceRouter ─▶ dated connectors first
                                                                                         └─▶ undated (demo, rest, …)
```

Named connectors (one file each in `config/connectors/`, written at the first start from a pack's `connectors:` template when absent; `drishti.sources.connectors` still works but is deprecated) give each domain its own lake
connector, e.g. `market-store` and `trading-store`. Lake retention, compaction and vacuum run outside the server
(`tools/lake/`, `deploy/lake-maintenance.yaml`; see OPERATIONS_GUIDE.md).

---

## 12. Domain packs

The core carries no industry. A **domain pack** (`config/packs/<domain>/<name>/pack.yaml`) holds everything specific to one:
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
| `drishti-packs` | Domain packs: reads `config/packs/<domain>/<name>/pack.yaml`, resolves inheritance, contributes lowest-precedence properties (an `EnvironmentPostProcessor`) | `PackRegistry` |
| `drishti-ai` | The LLM provider layer and MCP client, no Spring: `LlmGateway`, `LlmRegistry`, providers (`openai`, `anthropic`, `mock`), `LlmPolicy` (masking, limits, audit), `McpGateway`, `McpRegistry`; off by default ([AI.md](../admin/AI.md#1-model-providers), [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy)) | – |
| `drishti-diskcache` | `DiskCache`: a size-bounded, daily-cleared RocksDB cache on local disk for live connectors | – |
| `drishti-messaging` | `MessageStateSource`: the shared half of the ActiveMQ and RabbitMQ connectors (latest document per entity in a persistent RocksDB store plus a memory cache) | – |
| `drishti-lakeio` | Lake file access without Hadoop, shared by the native Delta and Iceberg engines: local disk (Windows included) and S3, byte ranges, Parquet page codecs, the Parquet footer cache; other schemes through a registered `StorageProvider` (`StorageProviders`) | – |
| `drishti-tables` | The table layer, plain Java ([TABLES_GUIDE.md](../guides/TABLES_GUIDE.md), [section 24](#24-virtual-tables-the-central-table-layer)): the catalogue of virtual tables and models (`config/tables`, stored items), their definitions and checks, preview, lineage, the materialisation's API, `tables migrate`; on Rachana and the pivot engine, never on BI | read by the server (`tables` on the command line); BI reads it with the switch |
| `drishti-memlake` | The in-memory lake, plain Java ([IN_MEMORY_LAKE.md](DATA_ARCHITECTURE.md#98-the-module-drishti-memlake)): the `mem://` stores (the `mem` storage provider of `drishti-lakeio`), tables, replenish jobs, eviction, compaction, the owner binding, the guard (`Retention`), the host checks | wired by `server.memlake.MemoryLakeConfiguration` |
| `drishti-iceberg` | Drishti's own Apache Iceberg engine without Hadoop (reads, writes, path-based and REST catalogs), used by the `iceberg` (engine `native`) and `iceberg-external` connectors ([DATA_ARCHITECTURE.md](DATA_ARCHITECTURE.md#3-drishti-iceberg-drishtis-own-apache-iceberg-engine)) | – |
| `drishti-deltalake` | A Delta Kernel engine without Hadoop (local disk, Windows included, and S3), used by the Delta connector | – |
| `drishti-lakeload` | The parallel lake loader: JSON Lines (folder or S3) into Delta or Iceberg tables of the connectors' layout, every stage multi-threaded and memory bounded, one commit per business date; `drishti.py lake build --engine java` ([PARALLEL_LOADER.md](DATA_ARCHITECTURE.md#10-the-parallel-lake-loader-drishti-lakeload)) | `drishti-lakeio`, `drishti-deltalake`, `drishti-iceberg` |
| `drishti-net` | Drishti*net's core, no Spring ([CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)): the member table and SWIM-style gossip over HTTP (`MemberTable`, `Gossiper`, `PeersFile`), the cluster key and on-behalf-of tokens (`ClusterKey`, `OnBehalfOf`), the `config/net/` folder and its schema (`NetFolder`, `NetSchema`), signed HTTP between nodes (`PeerHttp`) | – |
| `drishti-registry` | The access registry, plain Java ([ACCESS_MODEL.md](ACCESS_MODEL.md)): licence → module → lens → function → permission decided in one place (`AccessRegistry`, `EmbeddedAccessRegistry`), over the YAML store (`AccessStore`, `YamlAccessStore`: the modules' manifests, `config/modules/`, `config/access/`); nothing else reads the access model | wired by `server.modules.ModuleConfiguration` |
| `drishti-server` | **The** Spring Boot application, the core: `DrishtiApplication`, REST controllers, SSE, security (`TokenFilter`, OIDC), governance, alerts, actuator, OpenAPI; the SPIs a domain module fills (`PivotSourceProvider`, `CacheGroup`, `CapacityFigures`, `HealthFigures`, `ApiAlias`, `@ConditionalOnModule`) | controllers, filters |
| `drishti-compute-banking` | The banking domain's maths, no Spring ([DOMAIN_MODULES.md](DOMAIN_MODULES.md)): VaR and ES, parametric, components, backtesting, the scope algebra, exposure, FRTB-SA, SIMM, beta and correlation (`com.ash.drishti.banking.compute`) | – |
| `drishti-banking` | The banking domain module ([DOMAIN_MODULES.md](DOMAIN_MODULES.md)): the VaR, exposure, FRTB-SA, SIMM, sensitivities, MR cube and official-results services, storage and API under `/api/v1/risk` (`com.ash.drishti.banking`); its manifest and lenses; loaded only while the module is on | `BankingAutoConfiguration` |
| `drishti-app` | The runnable jar: the core with the domain modules this build ships, at `drishti-server/target/drishti-server-<version>-exec.jar`; no code | – |
| `plugins/drishti-plugin-*` | Source plugins: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `cassandra`, `redis`, `mongodb`, `iceberg`, `iceberg-external`, `duckdb`, `feeds`, `news`, `yfinance`, `kafka`, `activemq`, `rabbitmq`, `s3` (ServiceLoader; all are on the server's class path, extra jars from `drishti.sources.plugin-dir` get their own class loader) | – |
| `drishti-testkit` | Shared contract tests for plugins (`DatedSourceContract`, `MessageSourceContract`, `BrokerOutage`) | – |
| `drishti-it` | Architecture (ArchUnit: the layers, and the domain rules: the core never imports a domain, domains never import each other, only the registry reads the access model), licence-header and file-size tests | – |
| `drishti-benchmarks` | JMH benchmark of the hot path (`HotPathBenchmark`) | – |

**Inside `drishti-server`** (packages of `com.ash.drishti.server`): `api` (the controllers), `security`, `governance`, `collab` (shares, threads, compliance, bridges), `connectors` (connector files and the manager), `deploy` and `registry` (pack archives, the signed registry), `design` (the Build workbench's Designs), `explain` (About this page and Ask), `embed` (Drishti Elements), `loads` (data loads), `reports` (scheduled reports), `pivot`, `calc`, `layout`, `ai` (the AI and MCP endpoints), `cli` (the Java `sutra` tool), `net` (Drishti*net: the node, its endpoints, the call filter that checks the cluster key) and `bi` (Rūpaka: workspaces and catalogue, datasets and the DuckDB query engine, reports, boards, governance, live tables).

**Naming** (Pravaha convention): `XxxController`, `XxxProperties` (records bound via `@ConfigurationProperties`),
DTO holders `ApiDtos`, `ApiExceptionHandler`, `XxxRegistry` / `XxxCatalog` instead of "Repository" (identity's
Spring Data repositories in `identity.db` are the exception).

---

## 13a. Several servers as one (Drishti*net)

How a production cluster is deployed (roles, counts, sharding of VaR and exposure, high availability, load balancing, zero
downtime) is [CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md), the deployment reference.

Off by default ([CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet), [OPERATIONS_GUIDE.md](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)). With `config/net/node.yaml` switched on, a server is a **node**:
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
| Caching | Caffeine in the engine: layouts (`layout-cache-size`, 10,000, keyed by Sutra version, kind and fingerprint), fingerprints (`fingerprint-cache-size`, 100,000, keyed by entity, generation and date), compiled expressions (`drishti.rachana.expression-cache-size`, 10,000). Connectors keep their own caches (Delta partitions by size, `cache-mb`; Kafka documents; RocksDB disk caches). The Delta plugin's loads that do I/O use async caches loading on virtual threads, so no lock is held during I/O. A Sutra change clears the layout cache. Admins see and purge the engine's, history's and the connectors' caches at **Admin → Caches** (`GET /api/v1/admin/caches`); VaR and exposure have their own `reload`, BI its dataset refresh. Every cache and memory-mapped file, its bound and the proposals to bound them all are in [CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md#15-sizing-memory-cpu-and-disk) |
| Locks | `ReentrantLock` around anything that can block (file I/O, network, subscribes): Java 21 pinned a virtual thread blocked in `synchronized` to its carrier (Java 24 and later do not; the rule stays, so locks that may block remain explicit). `synchronized` remains only around short in-memory sections. Read-modify-write of shared records happens under the record's lock on the *current* value |
| Publication | State that readers need together is published in one volatile write of an immutable snapshot (Sutra registry, search index); readers never lock |
| Native resources | RocksDB generations are reference counted: a call enters the current generation and leaves after, and a retired generation closes only when its last caller has left, so a nightly clear or a purge never frees memory under a running read |
| Schedulers | The plugins' shared scheduler runs on virtual-thread workers, so a slow refresh or scan never delays another plugin's ticks or the nightly cache clearing |
| Back-pressure | One coalescing `FrameMailbox` per live client (pending frames merge, newest value wins), bounded alert queues; a slow client costs one slot, never a growing queue |
| Proof | Race tests reproduce each hazard with many threads and fail on the unsafe version: lockout under parallel guessing, admin changes against late sign-ins, the last-admin rule, simultaneous topic subscribers, index rebuilds during search, the stream cap, and RocksDB clear/close under 32 threads |
| Targets | warm view p99 < 50 ms, cold (first fingerprint) p99 < 150 ms, tick → screen p99 < 40 ms, 10k concurrent SSE subscribers per node (see CLUSTER_GUIDE.md for measured numbers) |

---

## 15. Configuration (config driven everywhere)

Precedence (low → high): pack content → `application.yaml` → `application.local.yaml` (git-ignored, imported from
the working directory) → environment variables → `--key=value` arguments. `${VAR:default}` interpolation; no
secrets in tracked files. The full key reference is [CONFIGURATION.md](../admin/CONFIGURATION.md).

| File | Owns |
|---|---|
| `drishti-server/src/main/resources/application.yaml` | server port, actuator, every `drishti.*` default: `sources`, `business-date`, `security`, `identity`, `rachana`, `governance`, `engine`, `packs`, `commands`, `live`, `graph` |
| `config/packs/<domain>/<name>/pack.yaml` | a pack's kinds, mnemonics, references, badges, roles, the names of its connectors with their routes (and connector templates), Sutra and sample folders |
| `config/connectors/<name>.yaml` | one connector of the site: plugin, switch, kinds and settings; watched and applied without a restart |
| `config/packs/<domain>/<name>/config/*.yaml` | the pack's formats, semantic hints, help and starter workspaces |
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
| `GET`/`PUT` | `/me/workspaces`, `/me/monitors`, `/me/settings`, `/alerts/rules` | per-user state |
| `GET`/`POST` | `/alerts`, `/approvals` | the one alert engine (the alerts raised for you) and the one Approvals queue |
| `POST` | `/auth/login` · `/auth/oidc` · `/admin/users…` | identity (see ADMIN_GUIDE.md) |
| `GET`/`POST` | `/forms/{pack}` · `/forms/{pack}/{kind}/compile` | query by form |
| `POST` | `/packs/{pack}/loads` | the batch-landed signal ([DATA_GUIDE.md](../guides/DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)) |
| `GET`/`PUT`/`POST` | `/admin/connectors…` · `/admin/packs…` · `/admin/registry…` | connectors as files, packs, deploy, the signed registry |
| `GET`/`POST` | `/threads…` · `/shares…` · `/me/inbox…` | discussion and sharing ([COLLABORATION.md](COLLABORATION.md)) |
| `GET`/`POST`/`PUT` | `/bi/…` | Rūpaka: workspaces, datasets, models and the model query, Drishti SQL, reports (format schemas, themes; boards are report pages), Python, governance |
| `GET`/`POST`/`PUT` | `/net/…` | Drishti\*net between nodes (signed with the cluster key: gossip, digest, fetch, search, group, subscribe, state) and for administrators (members, files, capabilities, access, security, explain, traffic) ([OPERATIONS_GUIDE.md](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)) |
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
| Server ↔ Python compute workers | Arrow IPC files, memory-mapped | no copy, no parse ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) |
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
| A figure per entity derived from its inputs | **Computed (derived) kinds**, computed on demand, never stored ([DATA_ARCHITECTURE.md section 12](DATA_ARCHITECTURE.md#12-computed-and-derived-attributes)) | NSMTM, CPMTM, SSPNL, the Checks panels |
| Heavy or non-linear models per request | **Python compute workers**, Arrow in and out ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) | pricing, Monte Carlo, what-ifs |
| Aggregates for dashboards and reports | **The BI engine**: rollups and caches ([BI_DESIGN.md](BI_DESIGN.md)) | desk reports, live exposure reports |
| Large vectors per position, re-aggregated over any scope, interactively, beyond one server | **The hot file and sharding** ([DATA_ARCHITECTURE.md section 13](DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built)) | VaR |

**The mechanism check.** A measure uses the hot file and sharding only if **all four** hold, answered with sizes at bank scale in its
design document before anything is built:

1. **Vectors per position:** each position carries a long array (scenarios, time steps, paths), not a handful of fields.
2. **Any scope, on demand:** people ask for any desk, book, basket or "bank minus X", not only fixed reports.
3. **Partials combine simply:** each server sums its part and the coordinator adds the parts before the final non-linear step (a quantile, a
   maximum, a percentile). When a group must be formed first (exposure's netting sets), the shard key keeps each group on one server.
4. **Too big for one server, or growing toward it,** and needed in under a second.

If all four hold, the measure plugs into the measure-agnostic machinery: it brings a `ShardedMeasure<P>` (its name, key order, bytes per
row, partial type and how two partials combine) and reuses the shard map, ownership, replicas, handover and scatter-gather
([DATA_ARCHITECTURE.md section 13](DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built)). If not, it uses one of the lighter mechanisms. Likely future users of the
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
[CLUSTER_GUIDE.md section 4](../guides/CLUSTER_GUIDE.md#84-data-loads-configuration-and-maintenance-without-downtime) and section 14.

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
| `/build`, `/build/d/{id}`, `/build/new`, `/build/pack/new` | The Build home (every kind a person builds, My drafts), the workbench: one design (Sutra, samples, panels, controls, form, About), New pack from schemas |
| `/approvals` | Designs waiting for review; write-back changes waiting for an approver |
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
  signed, expiring session cookie. Details, storage and administration: [ADMIN_GUIDE.md](../guides/ADMIN_GUIDE.md).
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
  ([CONFIGURATION.md](../admin/CONFIGURATION.md#field-masks)).
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
  under-construction registry (`config/under-construction.yaml`, [UNDER_CONSTRUCTION.md](../admin/UNDER_CONSTRUCTION.md)) and shows
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

## 23. One grammar, one engine

Every expression in Drishti is **Rachana**, and every aggregation is the **pivot engine's**. A sum over a trade's legs has one
implementation, one treatment of nulls, decimals, currencies, units and masks, and one spelling, whether a Sutra, a BI measure, a virtual
table, an alert or the pivot screen asks for it. The server computes; the console displays.

| Layer | Module | Owns | Never does |
|---|---|---|---|
| **The grammar** | `drishti-rachana` | the one expression language: paths, operators, filters, templates, links and the scalar functions (`fmt`, `upper`, `days`, `coalesce` …); the parser and the type checker (checked mode) every caller uses | implement an aggregate |
| **The aggregation engine** | `drishti-bicompute` | every aggregate (built-in, once-per-parent, sketch, custom), grouping, pivots, totals, windows and the filter-context functions; the one function catalogue with each function's documentation | parse a language of its own |
| **Measures with their own engines** | the compute engines (VaR, exposure, FRTB-SA, SIMM) | their figures, exactly; reached through their pivot providers and through `measure(...)` | be summed by anyone |
| **Callers** | Sutras, BI, the table layer, the pivot screen, alerts, playgrounds, the API, the command line | write Rachana and ask the engine | carry their own parser or aggregate |

### 23.1 How Rachana reaches the engine

`drishti-rachana` keeps one function table (`rachana.el.Functions`) open to providers (`rachana.el.FunctionProvider`), found once by
`ServiceLoader` when the table is built. `drishti-bicompute` provides every aggregate (`bicompute.rachana.EngineFunctions`, listed in
`META-INF/services`): a list aggregate in an expression (`sum($.LEGS[*].NOTIONAL)`, `percentile(@.PNL, 0.99)`) turns the list's values into
a `Batch` and runs the very `AggFunction` a pivot cell runs (`ListAggregates`); `group(list, key, aggregate)` is a Rachana form (it binds
`@`) that hands the grouping to the engine (`ListGrouping`). The dependency is one way: the engine depends on Rachana for paths and
expressions, never the reverse. Without the engine on the class path an aggregate compiles and is refused with the reason when evaluated
(`DRS-2102`), never computed another way.

| Function | Means | Example |
|---|---|---|
| `sum`, `count`, `avg`, `min`, `max`, `median`, `percentile`, `distinct` … over a list | the engine's built-ins on the list's values | `sum($.LEGS[*].NOTIONAL)`, `count($.EVENTS[?@.EVENT_TYPE == 'BOOKED'])` |
| `group(list, key, aggregate)` | grouping inside one document | `group($.LEGS[*], @.CURRENCY, sum(@.NOTIONAL))` |
| `measure(name, scope?, options?)` | a computed measure from its own engine, exactly (never summed); inside a model, a model measure | `measure('VAR', 'DESK:DESK-COMM')` |
| `cell(view, field, member, ...)` | one cell of a saved pivot view, as the view computes it | `cell('ann/desk-pnl', 'DESK', 'DESK-COMM', 'month', '2026-09')` |
| a Sutra **pivot panel** | groupings as Rachana paths, the value an engine aggregate | `by: "@.BOOK"`, `across: "@.CURRENCY"`, `value: sum(@.MTM)` |

`measure` and `cell` go through the engine's SPI `bicompute.spi.FigureSource`, which the server implements (`server.pivot.RachanaFigures`):
`measure` asks the compute engine whose pivot provider offers the name for one cell; `cell` opens a saved view the person may open. Both
answer for the person of the request (entitlements and masks) and are refused with the reason when the pivot engine is switched off.

### 23.2 One compiler: Java and SQL agree

BI's measures, a virtual table's columns and filters, row rules and the pivot's formulas are Rachana, checked by Rachana's **checked
mode** (the type checker run wherever types are known: a table's source contract, a model's tables, a typed pivot; it only refuses more,
never means something else) and compiled by one compiler in the engine (`bicompute.lang`: `Compiler`, `SqlLowering` for scans,
`RowsEvaluator` for rows in memory, `ScanEvaluator` for SQL over a scan). A row expression becomes the engine's row expression (DuckDB SQL
for a scan); an aggregate an `AggFunction` call (a formula of built-ins the engine's `FormulaFunction`, mergeable when its parts are); a
filter-context function (`calc`, `share_of`, `parent_of`, `as_of`) the engine's sub-plan (`bicompute.context.FilterContext`, with
`Periods` for balances); a window, a window over the answer. A differential test runs every operator, every scalar function, the
aggregates with and without `where =` and every shipped expression through the Java and the SQL forms on a table of edge cases (nulls,
empty lists, masks, zero divisors, mixed currencies) and compares value for value. The pivot's custom formulas are read by the same parser
(`bicompute.formula.FormulaLowering`). How BI uses it, the scopes and the filter-context algebra: [BI_DESIGN.md section
18](BI_DESIGN.md#18-the-bi-foundation-bi-on-the-table-layer-and-rachana).

### 23.3 The function catalogue

Each function lands **once**: scalars in Rachana, aggregates, windows and context functions in the engine. The catalogue page (Help →
Functions, [FUNCTIONS.md](../guides/FUNCTIONS.md)) is generated from the two registries (`FunctionCataloguePage`) and checked against
[FUNCTION_CATALOGUE.md](FUNCTION_CATALOGUE.md), the list function by function with its Excel, SAS and MATLAB equivalents. Every function
has typed arguments and result, a null rule, a mergeable form or the word *holistic* (so totals and the cluster stay right), currency and
unit rules (a sum of two currencies is refused unless converted), masks respected, a worked example the docs test runs, and its Excel
equivalent where one exists, so the pivot's Excel export writes a live formula. Excel and SAS names are documentation, never aliases.

| Family | Examples | Runs in |
|---|---|---|
| Conditional aggregates | any aggregate with `where =`; paths with filters `sum($.LEGS[?@.LEG_TYPE == 'FIXED'][*].NOTIONAL)` (`SUMIFS`, `COUNTIFS`) | engine |
| Descriptive and two-variable statistics | `nmiss`, `range`, `mode`, `stderr`, `cv`, `skewness`, `kurtosis`, `iqr`, `geomean`, `trimmean`, `corr`, `covar`, `slope`, `rsq` | engine (moments and sums of products are mergeable) |
| Shares, ranks, windows, time | `pct_of_total`, `pct_of_row`, `pct_of_parent` (of the visible totals, Excel's "Show values as"), `rank`, `ntile`, `top_n`, `lag`, `lead`, `moving_*`, `running`, `ytd`, `prior_period`, `diff`, `pct_change` | engine |
| Filter context and balances | `calc`, `share_of`, `parent_of` (they drop filters, DAX's `ALL`), `as_of`, `opening`, `closing`, `latest`, `earliest` | engine |
| Text aggregate | `string_agg(x, sep, distinct?)` (`TEXTJOIN`) | engine |
| Math, logic, text, lists, dates | `round`, `power`, `if`, `ifs`, `switch`, `let`, `left`, `split`, `regex_extract`, `sort`, `unique`, `map`, `reduce`, `edate`, `workday(d, n, calendar)`, `yearfrac(a, b, basis)`, `intck`, `intnx` | Rachana (no aggregation: `reduce` with `+` is refused in favour of `sum`) |
| Finance on given flows, distributions | `npv`, `xnpv`, `irr`, `xirr`, `pmt`, `accrued`, `modified_duration`, `norm_dist`, `t_dist` | Rachana; `discount_factor` and `forward_rate` wait for a curve reader agreed with the quant engine |
| MATLAB-style list analytics | `cumsum`, moving windows, `zscore`, `isoutlier`, `polyfit` | registered into Rachana, computed with the engine's `AggFunction`s |
| Matrix functions | `mmult`, `solve`, `inverse`, `cov_matrix`, `eigen`, `pca(k)`, `cholesky` | planned, in the engine on [drishti-native](#25-drishti-native-native-maths-behind-abstractions-with-java-fallbacks)'s linear algebra ([HIGH_PERFORMANCE_NATIVE.md section 6](HIGH_PERFORMANCE_NATIVE.md#6-blas)); small matrices stay in plain Java |

**The boundary.** Pricing a trade from market data is the quant engine's job, and VaR, ES, exposure, FRTB-SA and SIMM come only from their
own engines through `measure(...)`: the catalogue never re-implements them (no `var(vector, p)`). Python custom functions (NumPy in the
sandboxed pool) serve a person's own code and are not a second implementation of a catalogue function. Candidates decided one by one:
frequency tables with chi-square, regression with several variables, `forecast` and `trend`, winsorising, histogram bins as a dimension.

### 23.4 Guards, so it stays one

- `OneGrammarRulesTest` (`drishti-it`, ArchUnit): no class outside `drishti-bicompute` implements `AggFunction` or `FunctionProvider`;
  Rachana never depends on the engine; only `drishti-rachana` parses expressions. The listed exceptions are the VaR scope selector
  (`ScopeExpr`) and the migration tool's reader of BI's old expression language (it reads the texts it rewrites, never evaluates them).
- **The browser computes nothing.** `test_console_does_not_aggregate.py` finds every aggregation idiom in console Python and JavaScript
  (strings and comments left out) and lets through plain counting, sizes and layout arithmetic only. A figure the console needs over rows
  it read (a landing page's MtM and DV01 by book, a market median, a column filter's average) is asked of the pivot engine through
  `POST /api/v1/pivot/inline` (`drishti-console/core/engine_figures.py`) and shown as the engine answers; with the engine switched off the
  figure is left empty with the reason. `ConsoleFiguresTest` (`drishti-bicompute`) proves each on the console tests' fixtures.

## 24. Virtual tables: the central table layer

A **virtual table** is a named, typed table defined by configuration in Rachana over any kind any connector serves (a lake table, a
database table, an in-memory table), over a SQL query, a Python transform, a model's answer or other virtual tables. A **model** relates
virtual tables (keys, relationships, cardinality, direction) and defines **measures** over them once. Entity documents (a trade with its
`LEGS`, `CASH_FLOWS` and `EVENTS` in one JSON `doc`) become rows and typed columns, joined where they relate, and every consumer reads them
the same way. BI keeps what is BI's (reports, visuals, the designer, boards); its datasets and models are the layer's tables and models.
Every key, check and command: [TABLES_GUIDE.md](../guides/TABLES_GUIDE.md); the walk-through: the
[virtual tables tutorial](../tutorials/18-tables-and-models.md).

```
  reports · visuals · designer · boards · publishing        ← BI (Rūpaka): presentation only
                         │ MQ
  ┌──────────────────────┴───────────────────────────────┐
  │ the table layer (drishti-tables)                     │  catalogue · virtual tables · models · checks · materialisation
  │   expressions: Rachana (drishti-rachana, checked)    │  one parser, one checker, one lowering
  │   aggregation: the pivot engine (drishti-bicompute)  │  one function catalogue
  └──────────────────────┬───────────────────────────────┘
        kinds through connectors · lake tables · the in-memory lake · other tables
  consumers beside BI: the pivot · Sutras · Arrow Flight · alerts
```

**The module.** `drishti-tables` is plain Java on `drishti-rachana` and `drishti-bicompute`; `drishti-server` and BI depend on it, never the
reverse (`ArchitectureRulesTest` holds both directions). Its packages: `def` and `yaml` (definitions read by key and written in one
canonical form, `rachana: 3`), `lang` and `check` (Rachana's tree and checked mode with the fields a table's source paths or a model's
`alias.COLUMN` give; `LateBound` turns `fx(...)` and `related(...)` into columns worked out before the expression runs), `catalog` (files
under `config/tables/<domain>/` and the stored items of BI's item store, `was:` names, tables over tables, lineage; `PackTables` generates a
pack's kinds and the lists it names as tables, `trading.trade` and `trading.trade_legs`), `read` (a read for one viewer on one business
date), `answer` (`ModelAnswers`: a model query answered in memory, each table as the reader may see it, joined many-to-one from each
measure's fact, every measure compiled by the one compiler of section 23.2), `materialize` (`LiveStore`: live by key from pushed entities;
`ParquetOnLoad`: a date's rows as Parquet, each build its own file moved in and swept), `preview` and `migrate` (`tables migrate`).
The server's `server.tables.TablesService` reads the files and BI's item store as one catalogue, so the pivot, Sutras and BI see the tables
and models the editors publish.

**How it is read.** Each column goes to the fastest route that holds it (a promoted column of a lake table, the materialised Parquet, a
connector's column scan, else DuckDB's JSON over `doc`); a model's query compiles to the engine's grouping sets; the answer is Arrow
batches. MQ (`mq: 1`, `mq: 2`) stays the one way to ask a model; a table's implicit model is `table:<name>`. The viewer travels with every
call as one `ViewerContext` (user, roles, attributes, raw power, masks, kinds), never in a global, a shared cache or a thread-local.
Materialised files are a cache: rebuilt at will, never the truth, never written into a lake's own tables.

**Decided** (the numbers are cited in code comments):

| # | Decided |
|---|---|
| V1 | A central layer of its own (`drishti-tables`, definitions under `config/tables/<domain>/`), not inside BI and not in the lake file: its sources are any kind any connector serves |
| V2 | BI's datasets are flat virtual tables and BI's models the layer's models, migrated by one tool (`drishti.py tables migrate`, dry run and report); stored items migrate at the server's first start, every version rewritten in place with the text before kept in its history |
| V3 | A flat table's columns are scalar Rachana paths or row expressions; aggregates exist only as a model's measures; a table that is not flat (SQL, Python, `keep: { latest }`) is allowed as a source, and the one way to aggregate into a table is a **summary table**, a model's answer (`from: { model, query }`) |
| V4 | Materialisation is one mechanism: `none`, `on-load` (Parquet per business date), `{ live: { rows, age } }` (in memory by key); a model's `rollups:` keep partial states; a model's `history:` samples measures of a live table |
| V5 | Every consumer reads the layer: BI, the pivot, Sutras, alerts and Arrow Flight; Flight's `table` form (a virtual table read as Arrow with its rules and masks, scope `data:read` or `data:read:<domain>`) and Flight SQL's `tables` and `models` catalogs are designed, as are Export data and the AI reading tables |
| V6 | One migration and one parity proof for the move of BI's datasets and models and of BI's expressions into Rachana |
| V7 | Many tables over one source (`trading.trade_summary` and `trading.trade_legs` both `from: { kind: trade }`); within one request the reads of one source for one business date are shared |
| V8 | A table a data load creates is **offered** a flat virtual table in one click (`drishti.py tables offer`), never made automatically |
| V9 | Administrators and BI authors define tables and models; reading is always as the source (the reader's row rules and masks on the source kind), then the table's own rules and masks, which apply to every reader (BI, the pivot, Flight), then a model's; nothing adds rights. Definitions are audited and versioned like BI items |
| V10 | A model may relate tables from different connectors; the engine joins them, and the scan estimate warns when a side is larger than the model's `scan-warn-rows`, else `drishti.tables.scan-warn-rows` (1,000,000), and unfiltered |
| V10a | Tables use look-ups (`related(…)`, many to one) and never multiply their rows; combining tables row by row is a model's relationships |
| V11 | Dates as the source has them: every read names a business date (none: the source's latest); known-at time travel where the source keeps history; an undated source gives its current rows, dated today |
| V12 | Names are `<domain>.<name>`, unique across virtual tables and stored tables |
| V13 | A Sutra panel may name a virtual table as its source (`source: { table, where, limit, as-of }`), read for the page's viewer; a page built for nobody in particular reads no table |
| V14 | DuckDB is the one SQL engine, required; the layer adds no new place that calls it |
| V15 | Rachana is the layer's only expression language; whatever moved into it was moved, never copied |

**Screens.** The editors (Build › Tables & models, [TABLES_GUIDE.md section 11](../guides/TABLES_GUIDE.md#11-the-editors)) follow the
chosen mock-ups: tables as a visual view over a sample document with the columns and the rows a chosen role reads, switched with a YAML
view ([BC](../lenses/mockups/virtual-tables/BC.html)); models as a diagram with a live answer and a measures-first sheet behind one switch
([model-BC](../lenses/mockups/virtual-tables/model-BC.html)). Every view edits one draft; a live YAML panel below every visual view follows
each edit; invalid YAML is marked at its line and the visual side keeps the last valid version.

## 25. drishti-native: native maths behind abstractions, with Java fallbacks

*Product owner: "Key is we maintain abstractions and have fallbacks so we can run on any environment."* Every native integration of the
server sits behind a Drishti interface with a pure-Java implementation that always works, so Drishti runs anywhere. It realises the seam
of [HIGH_PERFORMANCE_NATIVE.md section 2.8](HIGH_PERFORMANCE_NATIVE.md#28-two-builds-one-code-base-the-baseline-and-the-accelerated-path-side-by-side)
on Java 21 (JNI through JavaCPP) and keeps room for the Java 25 paths of that design (FFM, the Vector API) as further implementations of
the same interfaces.

**What runs today.** Linear algebra: OpenBLAS (bundled through bytedeco for Linux and Windows x86-64) behind `LinearAlgebra` in
`drishti-compute` (`compute.linalg`: `JavaBlas`, `NativeBlas`, `GuardedBlas`, `Canary`) and the server's `server.nativelib`
(`NativeLibraries`, `OpenBlasEngine`), with the Java kernels as the fallback, `drishti.native.blas.mode` (`auto` or `java`), Health
DEGRADED and an alert when it falls back ([HIGH_PERFORMANCE_NATIVE.md section 6](HIGH_PERFORMANCE_NATIVE.md#6-blas)). The module
`drishti-native` below is the design that gathers every native capability into one home.

**The rule.**

1. Every native capability sits behind a Drishti interface; only `drishti-native` imports a native binding (an architecture rule).
2. Every interface has a pure-Java implementation that always works; native code is an optimisation chosen at run time.
3. A native library that is missing, built for another platform, fails its self-test or misbehaves later is replaced by the Java
   implementation: a loud warning in the log, **DEGRADED** in Health, a banner in Admin; the server always starts.
4. `drishti.native.mode` (planned: `auto`, `off`, `on`) for everything, and a mode per capability (`auto`, `java`, `native`; today
   `drishti.native.blas.mode`), in configuration and live in Admin → Engines.
5. Both paths are proven equal by tests and a canary; one drill lane runs with every native capability off.
6. One version of everything: Maven Enforcer `dependencyConvergence`, `requireUpperBoundDeps` and `banDuplicateClasses`; only the
   `linux-x86_64` and `windows-x86_64` classifiers, never the `-platform` artifacts.

| Capability | Interface | Native | Java | Parity |
|---|---|---|---|---|
| Linear algebra, level 1–3 | `LinearAlgebra` (`dgemm`, `dgemv`, `ddot`, `daxpy`) | OpenBLAS (bytedeco) | Drishti's kernels (`JavaBlas`, `MultiSums`) | the near-tie rule makes VaR's order identical; sums within 1e-12 relative |
| Decompositions and solvers | `Lapack` (Cholesky, LU, QR, symmetric eigen, SVD, least squares, solve) | LAPACK from the same OpenBLAS jar (no new dependency) | Hipparchus | within 1e-10 relative on reconstruction; eigenvalues sorted and compared |
| Statistics and optimisation | `Stats`, `Optimise`, `Interpolate`, `Distributions` | none needed | Hipparchus, the one maths library of the server; Rachana's statistical functions call it through `drishti-native` | |
| Compression | `Codecs` (zstd, Snappy, LZ4, gzip) | zstd-jni, snappy-java, lz4-java | aircompressor (the same formats) | byte-identical after a round trip |
| Key-value disk store | `DiskStore` (get, put, remove, scan in key order) | RocksDB | H2's MVStore (on disk, keys in order; each keeps its own folder, so a switch starts the other's cache empty) | the same answers for every key |
| Embedded SQL engine | `SqlEngine` (run a statement with memory, thread and time limits, answer in Arrow) | DuckDB | **none, by decision**: DuckDB is required like the JVM; if it cannot load the server says so in plain words and does not start | |

SQLite, the default database of a single server, is required the same way (its driver registered for status and version; no third
schema). LAPACK's first uses: Cholesky for correlated Monte Carlo paths and positive-definiteness checks, symmetric eigen for PCA of the
scenario P&L matrix and repairing a non-PSD correlation matrix, SVD for low-rank views, least squares for hedge ratios and curve fitting.

**The module.** `com.ash.drishti.nativ.core` (`Capability`, `Registry`, `Guard`, `Canary`, `NativeMode`, the status record), `.linalg`,
`.maths`, `.codec`, `.store`, `.sql`; plain Java on `drishti-common` only, wired by the server from its settings. Its users:
`drishti-compute` (VaR and exposure kernels), the lake modules and loaders (codecs), `drishti-diskcache` (the store), the server (Health,
Admin, settings). The Java 25 sources (FFM, Vector API) compile only under a Maven profile active on JDK 25 and are found by
`ServiceLoader`: one home for every native and accelerated implementation, no second seam.

**The capability contract.** Every capability has the same parts: the interface in Drishti's types (Java arrays and buffers, never a
native type); the Java implementation, always present and the reference for parity; the native implementation, loaded lazily once,
declaring its OS, architecture, library file and Java version; a self-test at start (a mismatch keeps Java); a guard on each call's inputs
(sizes, NaN and Inf, bounds), so a bad input is a Java exception, never a crash; a breaker (a native call that throws or answers a
non-finite result where Java's is finite trips the capability to Java for the rest of the run); a canary every few hours; a switch; and
a status in Health (`native.<capability>`), Admin → Engines, the start log and metrics (planned: `drishti_native{capability, impl}`
and an admin endpoint). Native libraries unpack under `drishti.native.cache-dir` (`<data>/native-cache`).

**Beyond: the Python worker and other capabilities.** What exists only in Python (statsmodels, scikit-learn, the quant engine's models) is
reached through a Python worker beside the server over Arrow Flight (`DoExchange`), with its own switch, timeout, health and canary;
designed, not built. QuantLib is parked: when it is revisited, it runs in single-threaded worker processes (its Java binding,
`io.github.ralfkonrad.quantlib_for_maven:quantlib`, ships Linux, macOS and Windows natives and has no sessions, so its evaluation date is
process-wide), with a pure-Java core as its fallback. Further capabilities are chosen by capability, not by industry, each on the same
contract: adopted are ONNX Runtime for machine-learning inference (Tribuo as the Java side) and Google OR-Tools for optimisation
(Hipparchus simplex and Timefold); pure Java only are JTransforms (FFT) and simdjson-java; candidates when a client asks are GDAL and PROJ
(GeoTools, proj4j), H3, and libdeflate if a benchmark shows gzip as the bottleneck. Not taken: FAISS, CUDA, a second SQL engine.

## 26. The pivot engine

One generalised pivot for all of Drishti: "these measures, broken down by these dimensions on rows and columns, under these filters, as
of this date", for any amount of data and every measure Drishti has (a sum of notional, a distinct count, a 99% VaR, an FRTB charge, a
function a quant wrote this morning), with every total right. How people use it is the [Pivot guide](../guides/PIVOT_GUIDE.md); this
section is how it is built. The mock-ups the screen was chosen from are in [mockups/pivot/](mockups/pivot/A.html) (A, the
Excel-familiar layout, was built; B and C are kept for reference).

### 26.1 The parts

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
   │ tabular   │  │ VaR vector  │  │ FRTB-SA,    │  │ CCR (not      │  │ external SQL  │
   │ DuckDB    │  │ (Java, in   │  │ SIMM        │  │ built): EE,   │  │ backend (not  │
   │ Delta,    │  │ memory)     │  │ (formula)   │  │ PFE, EAD      │  │ built)        │
   │ Iceberg,  │  └─────────────┘  └─────────────┘  └───────────────┘  └───────────────┘
   │ Parquet   │        custom functions: formula · SQL macro · Python (worker pool) · Java plug-in
   └───────────┘
```

**Terms.** A **view** is what the person asked. A **cell key** is a cell's coordinates (dimension → value, or "all" for a total) plus the
view's filters; it names a cell without listing its members. A **function** is an aggregation with four steps
([26.5](#265-custom-aggregation-functions)). A **partial state** is what `add` and `merge` produce for one cell key on one node. A
**provider** turns cell keys and functions into partial states over the data it owns. The **core** merges, finishes and shows; it never
computes a figure itself.

**Why one engine.** The MR cube, the CCR cube, BI's matrix and the Pivot tab each draw a pivot; four pivots would mean four sets of totals,
four budgets, four exports and four ways of being wrong. One engine means one rule for a total, one provenance, one Excel export, one test
suite and one cluster merge. The MR and CCR cubes are **presets** of the pivot screen: a provider set and a landing, not engines of their
own. Rachana's aggregates are the engine's functions too ([section 23](#23-one-grammar-one-engine)).

### 26.2 The pivot model

**The view** (what a saved view stores; the API takes the same as JSON): `source` (a table of a pack, a lake table, a panel's rows, or a
cube's source such as `var`), `date` (or two for a comparison), `as-of` (latest, a Delta version, an Iceberg snapshot), `rows`, `columns`
(each a field, optionally grouped: `{ field: TRADE_DATE, group: month }`), `values` (a measure, its label, `exact`, `show-as`), `filters`,
`slicers`, `totals`, `currency` (report currency and FX source), `sort` and `top` (`{ field, n, others: true }`: never silent, "top 10 of
41, the other 31 in Others"), and the `expanded` nodes.

**Dimensions are typed and ordered**, published by each provider's catalogue: string (manual groups, top N with Others; a configured order
or by value), date and time (day to year, the bank's calendar and fiscal year, buckets from today), number (buckets by width, by edges,
quantile buckets), hierarchy level (MGMT, LEGAL, limit letters, risk-factor trees; ragged), scenario day (the VaR provider). A masked
dimension cannot be used as rows or columns, and says why.

**Cell keys, not member lists.** A view of R rows and C columns has R × C cells, R row totals, C column totals and one grand total, plus
every open subtotal level; the core turns them into cell keys (`{DESK: RATES-NY, month: 2026-10}`, `{DESK: RATES-NY, month: *}`,
`{DESK: *, month: *}`) and each provider decides how to evaluate them: the tabular provider compiles the whole set into **one**
`GROUP BY GROUPING SETS (...)` query; the VaR provider maps each key to the `OrdinalSet` of its positions and sums their vectors; the
formula providers sum the members' sensitivities to the formula's grain. The **axis** (which values appear) is asked first, cheaply, so
the cell count is known before any measure runs and a view over the budget is refused with the count, never truncated.

**The answer** carries per cell its coordinates and values, per total its kind and level, the diversification of non-additive measures
(Σ children − node), the provenance id, the exactness per measure, the layer that answered (a rollup or the lake), progress and timing.
Every value is a number, a number with `approx: true` and its error, or `null` with the reason in `notes` (masked, refused, a shard down, a
currency that cannot be converted); nothing is blank without a reason.

### 26.3 Measure kinds and how each total is made

**A cell or a total is made from its members, never from other cells' figures.** What differs by kind is how cheaply:

| Kind | State | Merge | A total is | Marked |
|---|---|---|---|---|
| **Mergeable** | small and fixed (a sum; a sum and a count; Welford's count, mean and M2) | exact (associative and commutative) | the merge of its cells' states: no second scan | exact |
| **Sketch** | bounded (HyperLogLog registers, a KLL summary, a Space-Saving list) | approximate, within a stated error | the merge of its cells' sketches | ≈ with the error; exact on request |
| **Holistic** | unbounded (every value, or a vector per member) | none that is small | recomputed from its own members | exact (or refused over the budget) |

VaR and ES are **holistic statistics over mergeable inputs**: a scope's state is its summed scenario P&L vector, which merges exactly by
element-wise addition, and the statistic (a rank, a tail mean) is taken per cell. Each cell and total is summed from its own members'
vectors in the engine's fixed order, so it equals the VaR engine's answer for that scope to the bit (recomputing from members was chosen
over merging the cells' vectors, which is faster but equal only to the cent).

**Built-in states.** `sum` (Kahan-compensated float64, or decimal for declared decimal columns), `count`, `min`/`max`, `avg` as (Σx, n),
`wavg` as (Σwx, Σw), `var`/`stddev` as (n, mean, M2) merged by Chan's formula, a formula of mergeable parts as its parts' states,
`first`/`last` by a time column. `distinct`, `percentile` and `median` are holistic and exact by default, with a sketch **form** (not a
second name): HyperLogLog with 2^p registers (p = 12: about 1.6% relative standard error, 1.04 / √m), KLL with k = 200 (about 1.65%
normalised rank error), Space-Saving with 10 × n counters for a level's top N (counts over by at most the smallest counter). Which form
answers is the request's (`"exact"`) or, by default, the size's (`sketches.approximate-above-rows`).

**Exact on request without giving up scale.** A distinct count where the table is partitioned or clustered by the counted column adds
per-node exact counts; otherwise each node sends its distinct values per cell up to a budget (5 million values a view), above which exact
is refused with the size and ≈ kept. Exact percentiles use **iterative selection**: the KLL sketch bounds the answer to a narrow window,
one more mergeable pass counts the values below it and collects those inside it, and the rank is found among them (two passes on most
data, a third when the window holds too many values). VaR and ES are never sketched.

**Correctness the engine enforces.** Non-additive totals are right by the rule above; a sum across currencies is converted at a named FX
source and time (in the provenance) or refused as mixed currencies; percentages, rates and day counts are not summed (the catalogue marks
units, and the editor offers only the functions that make sense); row rules and masks apply in the compiled query before grouping, a masked
column is never a dimension, and an aggregate-only role may require a minimum number of members per cell; the cell budget refuses with the
count, top N always says "of how many"; every value carries its exactness; merges happen in a fixed order (shard order, then cell-key
order), so the same question gives the same digits on every run and every node count.

### 26.4 The providers

**The interface** (`PivotProvider`, one for all): `catalogue` (typed, ordered dimensions, the measures and the functions it evaluates
natively), `capabilities` (grouping sets, sketches, scope text, time travel, live, what-if, partials), `axis`, `estimate` (bytes, rows,
files, seconds per node), `evaluate` (an aggregation plan → partial states per cell key, for this node's part), `drill` (a cell's member
rows), an optional `scopeOf` (the cell as the provider's own scope text) and `version` (the lake versions read, part of every cache key). A
plan is the cell keys, the functions with their kinds and state types, the filters, the date, the as-of version and the lens. A partial is,
per cell key and function, a serialised state with its member count and notes; a provider may answer **final** figures for functions only
it can finish (the regulatory formulas), and the core treats those as holistic.

**The tabular provider (DuckDB)** reads any table a pack declares by three routes chosen per table: the BI layers (the same DuckDB
connection and Parquet files BI reads), a direct lake scan of large Delta or Iceberg tables (DuckDB's `delta` and `iceberg` extensions over
the lake's files with the table's version pinned), and memory-only tables (`mem://`, registered as Arrow views with DuckDB's temporary
directory off). A view compiles to **one** parameterised statement (values bound, never spliced), row rules and masks first:

```sql
SELECT DESK, date_trunc('month', TRADE_DATE) AS month,
       GROUPING(DESK, month)                    AS g,          -- which grouping set a row belongs to
       sum(NOTIONAL_USD)                        AS s0,         -- mergeable state of sum
       sum(PNL_1D), count(PNL_1D)               AS s1a, s1b,   -- avg as (sum, count)
       hll_registers(hash(COUNTERPARTY_ID), 12) AS s2          -- a sketch state
FROM   lake_scan('trading.trades', version => ?) t
WHERE  <row rules> AND PRODUCT_GROUP IN (?, ?) AND TRADE_STATUS <> ?
GROUP BY GROUPING SETS ((DESK, month), (DESK), (month), ())
```

It returns **states**, not figures, so answers merge across nodes, cache and finish in the core. DuckDB's native aggregates are used on a
single node when the answer is final there; their mergeable state forms whenever partials must be merged or cached. A `direct` table (a
PostgreSQL or DuckDB connector) gets the same SQL pushed to its source when every function is native to it. An in-memory provider
(`RowsProvider`) computes the same states by the functions' own `add` over rows held in memory (a panel's rows, Export data's staged
tables).

**The VaR vector provider** (`VarPivotProvider`): members are the date's positions or trades as the person sees them; a cell key maps to an
`OrdinalSet` by dimension partitions (desk, book, limit letter, any hierarchy level, any column of the vectors); the state is the summed
scenario vector (and the stressed vector when asked), summed in the engine's fixed order, on a sharded date one call per shard owner for
all of a view's cells (`VarBook.vectors`, `ShardedVectors.sumSets`); the finish is VaR, ES and stressed VaR at the lens's confidence and
horizon, component VaR as a cell's share of the grand total's tail, and the diversification beside each total. A cell's scope in VaR's
grammar (`DESK:RATES-NY & CCY:USD`) lets any cell be asked of the VaR engine itself. The MR cube's figures, endpoints and landing are this
provider plus the MR lens (`MrCubeExtras`, `MrSensitivities`); nothing in the console computes a figure.

**The FRTB-SA and SIMM providers**: state, the members' net weighted sensitivities summed per risk factor to the formula's grain (a
sparse map, added in shard order); finish, the regulatory formula (bucket K_b, the three correlation scenarios; SIMM per CSA version), on
each cell's own sums. They need those engines on.

**Designed, not built.** The **CCR provider**: state per netting set, the path matrix of MtMs netted and collateralised per netting set
first (non-linear), then summed across netting sets per cell; finish EE, EPE, PFE quantiles, EEPE; EAD from SA-CCR's formula provider; a
netting set never split across shards (with the counterparty engines; the CCR cube preset waits for it). An **external distributed SQL
backend** (Trino, StarRocks or ClickHouse) as a provider, for interactive views over tens of terabytes by many people at once: the tabular
compiler would emit its dialect and its answers come back as mergeable states; parked unless the benchmark shows common views over five
minutes on three nodes.

**Documents: paths, lists and grains.** An entity document (a trade, a counterparty) is stored whole in a JSON column `doc` beside a few
promoted typed columns (`layout.<kind>.columns`). Every pivot field is a **Rachana path** (`$.LEGS[0].CURRENCY`,
`$.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME`, `size($.LEGS)`, `$.LEGS[*].NOTIONAL`), parsed by Rachana's own parser and compiled: a
path a promoted or dataset column holds reads that column; anything else becomes DuckDB JSON over `doc` (`json_extract`, `unnest` for
`[*]`, `list_filter` for `[?...]`); a Rachana function with no SQL form is refused for a pivot, never evaluated a second way (`DocPath`,
`DocFields`, `DocView`). Three routes, fastest first, chosen by the engine: the promoted column, a BI dataset column extracted once per
business date into the `cached` layer, an ad-hoc path at query time (shown by the scan estimate first; a large one refused with the
cheaper route named; administrators see the most used ad-hoc paths with **Promote** and **Add to dataset**). **The grain comes from the
path**: no `[*]` is the entity's grain, each `[*]` steps into a list, and the deepest list a view uses is its grain; a coarser figure in a
finer view is counted **once per parent** (an aggregate over the distinct parents in each cell, `OncePerParent`); two lists not one inside
the other (`LEGS` and `EVENTS`) are never crossed (refused with the reason); a filter on a list field means "any element" at the
entity's grain and "this element" at the list's grain; masks and row rules apply per path.

### 26.5 Custom aggregation functions

**The contract**, the same for built-ins and custom functions in every language:

| Step | Called | Receives | Returns | Rule |
|---|---|---|---|---|
| `start(params)` | once per cell key per worker | the parameters | an empty state | pure; no data |
| `add(state, batch)` | once per columnar batch (Arrow, 64K rows by default) of the cell's rows | the declared input columns only, masked as the reader sees them | the state | vectorised over the batch |
| `merge(a, b)` | when two states of the same cell meet (batches, threads, nodes, the cache) | two states | one state | associative and commutative, within the declared tolerance |
| `finish(state)` | once per cell, on the coordinator | the merged state | a number or a small record | pure |

A state is serialisable with a declared size bound; it crosses processes and nodes and lives in the cache. A function declares its kind:
mergeable, sketch (with the error its author states) or holistic (no `merge`: it gets all of a cell's rows in one call, bounded by
`functions.holistic-max-rows` and `holistic-max-mb`, refused per cell over them; holistic cells are gathered to one node within the budget,
and a view that would gather more is refused before any data moves).

**Four languages, one contract.** A **formula of built-ins** (the first route offered: compiled into the provider's plan, SQL on DuckDB
and Java on the VaR provider, of its parts' kind); a **DuckDB SQL macro** (mergeable when the engine can rewrite it into states of
mergeable aggregates, else holistic); **Python** in the node's sandboxed `pivot-fn` pool, vectorised over Arrow batches; a **Java
plug-in** loaded from an approved, sha256-pinned jar. A function is named `fn:<owner>/<name>@<version>`. User Python never runs inside
the server's DuckDB (DuckDB's Python API has scalar functions only, and the server's process is not user code's place): the provider
feeds the pool Arrow batches.

**The harness** (`FunctionHarness`): a function is not saved, shared or used until its split-and-merge answer equals its one-pass answer.
It runs one pass; batches of 1, 7, 1,000 and 65,536 rows; splits into 2, 3 and 16 parts merged in random orders and tree shapes over five
seeds; empty, null, single-row, all-equal, infinite and NaN inputs; and every state serialised and read back before merging. It fails a
mergeable function beyond the tolerance (1e-12 relative; exact for integers and decimals), a sketch beyond its declared bound, and any
function that is non-deterministic, too slow per million rows or whose state outgrows its bound. The **playground** (Pivot → Functions →
New) shows the code, sample rows (pasted or a seeded, masked draw), the states after each part and merge, the check on every edit, the
measured cost and its projection on a real table.

**Python's sandbox** (`drishti_compute.pivot_fn`, the compute SDK's audit-hook guard and Arrow codec): warm workers, `python -I`, an
empty environment, one call at a time, a worker serving one function version only; no network, no file reads outside the installation
and no writes at all, no processes; the pool's package allow-list; per-call time and per-worker memory limits; only the declared columns
of rows already ruled and masked; a state within its declared size, validated before it is merged or cached. Batches travel as Arrow IPC
stream frames over the worker's pipe, never through scratch files; Python functions never read memory-only stores (the view is refused
in words).

**Versions, approval and labels.** Every save is a new version and a view names the version it uses; a function is private until an
approver who is not its author approves it for a group or everyone (audited, revocable: views then say "function withdrawn"); every figure
from a custom function is labelled with its name, author and version; the official risk measures stay Java engines, and a custom function
can never take an official measure's name or appear in an official run.

### 26.6 Scale

**How a large pivot runs.** (1) Plan: the axes first, so the cell count is known; then the cell keys, functions and kinds. (2) Estimate:
bytes, files and seconds from the lake's metadata after pruning, before any data is read; over the confirm level the person chooses (run,
narrow, use a rollup, run in background). (3) Route: aggregate awareness picks the smallest layer that answers every function exactly (a
rollup, the BI columns layer, the lake); a plan partly answerable from a rollup is split. (4) Scatter: files or shards are assigned to
nodes; each node's executor compiles its part to one DuckDB query (or one vector batch) and returns partial states per cell key. (5)
Gather and merge in node order, then cell-key order. (6) Finish, show, and cache the **states** by (provider data version, cell key,
function).

**One executor per node**: a bounded pool of DuckDB connections (one per four cores by default), each with its threads and memory limit;
a slow node's files are re-assigned (hedged) to an idle node holding the same data after `cluster.hedge-after-seconds`, and the first
answer wins (both equal by construction). On shared storage files are assigned by hashing the file path over the nodes that run the
`pivot` engine, so caches stay warm; node-owned data (VaR shards, a pack bound to a node, memory-only tables) is computed by its owner and
only partial states move; a small table runs on one node. One request per node carries all of a view's cell keys. The ordered combine is
the one VaR sharding uses ([DATA_ARCHITECTURE.md, VaR sharding](DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built)), lifted into the
core for every provider (`OrderedMerge`, `PartialCodec`, `PartialGather`).

**Read less.** Only the plan's columns are read (Parquet is columnar); partition pruning and statistics skipping use Delta's per-file
minimum and maximum (the first 32 columns by default), Iceberg's manifests and Parquet's row-group footers; tables are partitioned by
business date and clustered (Z-order on Delta, sort orders on Iceberg) by the two or three dimensions most used as filters; the lake
readers' disk cache keeps hot files local.

**Sketches.** HyperLogLog is computed in SQL in the same `GROUPING SETS` statement (`hash(x)`: the first p bits pick the register, the
rank of the first 1-bit in the rest is its value; the maximum per register taken in Java; merges are register-wise maxima, exact to the
byte; two hash families never merge). KLL is built in Java from each cell's quantile points (`quantile_disc` at 2k + 1 fractions) and its
count, each point weighted by the values it stands for; its merge's coin is a hash of the sketch's own count, so a fixed order repeats to
the byte. Space-Saving is built from a cell's value histogram; a level's top N by a count of rows may be chosen by one summary of the
parent's cell when ≈ is asked (`TopFirst`), with exact figures and membership within the floor.

**Rollups of partial states, refreshed on load.** A rollup stores the states of mergeable and sketch functions at its grain; an appending
load adds only the new files' states, a restatement recomputes only the partitions it touched; a plan is answered from the smallest rollup
whose grain covers its dimensions, filters and row-rule columns (`AggregateAwareness`, with date coarsening), and the answer names it; the
engine counts views that scanned the lake for want of a rollup and proposes one. Holistic functions are never rollup material.

**Estimates and progressive answers.** Seconds come from the bytes after pruning divided by each node's measured scan rate per storage
kind plus a per-file cost, always as a range (`ScanRates`). A view estimated over `progressive.after-ms` answers twice: first ≈ within
about a second from honest sources only (sketch states in a covering rollup, or a random sample of files within each partition with each
mergeable figure scaled up and its 95% interval; never a coarser figure split by guessed proportions), then exact cell by cell
(`Progressive`). Holistic measures are never sampled.

**Budgets, busy protection, the cache.** `max-cells` and `max-holistic-cells` refuse with the count and the dimension that makes a view
large; views over `busy-cells` pass the admission gate first; the partial-state cache is weighed in bytes in the one memory budget, and a
new data version invalidates only the cells of the tables it changed.

**DuckDB's limits, and what the design does.** One process on one machine: Drishti scatters and gathers states, DuckDB is the per-node
executor. High-cardinality grouping can run out of memory despite spilling: the cell budget bounds the groups, states are kept small,
holistic functions are size-bounded, the per-connection memory limit is set, and an out-of-memory is a refusal in words. Memory-only data
may never spill: such views must fit in memory or are refused. Object-store bandwidth bounds S3 scans: prune first, cache on disk, add
nodes. Many small concurrent queries: a bounded pool per node, queuing, the cache, rollups, coalescing of identical plans. The `delta` and
`iceberg` extensions ship with the distribution and load from it (no download at run time), their versions pinned with DuckDB's.

### 26.7 Beyond Excel: explain, time travel, what-if, provenance, live, references

- **Explain a difference** between two cells or one cell over two dates (`Explain`, `PriceVolume`): for mergeable measures the difference
  splits exactly into contributions by member along any dimension, and by cause where the provider knows causes (new members, members
  gone, value changes; for price × quantity measures, the price and quantity effects in a fixed order with the cross term shown); for VaR,
  the VaR engine's exact steps per cell. The steps always add up, and a residual is named.
- **Time travel and compare**: any view as of a business date and a data version; compare two dates or versions cell by cell, new and gone
  members marked, each side's provenance naming its versions.
- **What-if overlays** (`Overlay`, `WhatIf`, `WhatIfResolver`): exclude members, scale or override an input column, add hypothetical rows;
  the tabular provider joins the overlay into the plan; only affected cells recompute; the original stays beside the result; an overlay
  is private, saved with the view and never written to the lake.
- **Provenance** per answer and cell: data versions, files or shards and nodes, rows counted, filters and row rules in words, functions
  with their versions, the layer, exactness and error, time taken; one click to the rows.
- **Cell references** by coordinates (`pivot://owner/view@version/measure[DIM=value, ...]`): they survive re-sorts, new columns and new
  dates; a gone coordinate says "not in the view", never 0; Rachana's `cell(...)` reads them.
- **Live pivots** (`LivePivots`, `AnswerPatch`): on a signal (a data load of a kind for a date, `LoadService.onReady`, or a live panel's
  rows through the `TopicHub`) the touched views are computed again through `PivotCore.query`; the cache keyed by each date's data version
  means only what moved is read, and the source's rollups are refreshed first. `AnswerPatch` sends `status` (nothing moved), `patch` (the
  moved cells whole) or `reshape` (rows, columns or measures changed); the patch applied to the last answer equals a fresh query, cell by
  cell. Recomputes are coalesced and ordered per view and exact unless the view asked ≈. Not built: the batch-as-merge form that updates
  per-cell states straight from a stream's rows.
- **The screen's switches** (Exact / Auto / ≈, Progressive, Use rollups, Live) are `ScaleOptions` beside the view, never in it; every
  answer carries `computedAs` (what was asked, which measures sketches answered with their errors, which levels a summary chose, the
  routes, the sample, one line of words).
- **Cell comments** (`PivotCellComments`) are kept beside a saved view's numbered versions (one document changed atomically), each naming
  its cell by coordinates and the view version it was written on; audited without the text.

### 26.8 Expand and collapse

Rows and columns are nested levels with **+** and **−**, compact, outline or tabular layout and subtotals above or below. Only expanded
nodes are computed: opening a node is a new plan of that node's children only (one `GROUPING SETS` query, or one vector batch for VaR),
children already computed come from the cache, and the expansion state is part of the saved view and of a link. Any member may be expanded
by a **different** dimension (each node is its own cell key, so totals stay right); hierarchies may be ragged or parent-child (a cycle is
refused at load with the members named); a level's top N has one **Others** computed from its own members (for VaR, from their vectors),
never total minus the top N; each level sorts independently and never moves a child out of its parent; the last level expands into the
member rows, virtualised and paged; the export keeps the outline as Excel's native grouping levels.

### 26.9 BI on the pivot engine

BI keeps its own design (datasets and models, one model per report, MQ as the one way to ask a model, time modes; [BI_DESIGN.md
section 14](BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6)) and its compiler of a model's rows (row rules, masks, joins,
layers, dates). What moved is **who aggregates**: a matrix's totals and subtotals, a table's total row and a model query's `totals` are
every level in one `GROUPING SETS` statement of the engine's function states over the model's rows as the reader may see them; a
measure's additivity is the engine's answer for its function (`plainSum`); the console adds nothing up. BI's compiler writes the model's
**detail** query and does not run it: it becomes the FROM item of the engine's statement (`QueryService.Outer`), run in BI's engine where
the layers are, so no row leaves it; measures of several facts get one statement per fact, the levels joined (`BiMeasures`,
`BiAggregate`). A model's measure that is an expression of aggregates of one fact's rows is a pivot function; time intelligence, running
and analytic functions, filter contexts, balances and Python measures read beyond their group and have no total (a dash, with the reason
in the warnings). A dataset read directly from its source (`mode: direct`) gets no engine totals. The move had one path: parity tests
against figures recorded from the code it replaced (`BiParityTest`, `TrueTotalsTest`), then the old aggregation code deleted in the same
change. Not built: models as sources of the pivot screen, and BI's rollups as rollups of partial states.

### 26.10 The module: drishti-bicompute

A plain Java 21 library, no Spring, holding everything that aggregates; its dependencies are `drishti-api`, `drishti-common`, DuckDB
(`duckdb_jdbc`) and `arrow-format`. **No other module re-implements aggregation**: a build test (`AggregationOwnerTest`) fails any class
outside it that implements the function SPI's steps or emits `GROUP BY` for a pivot.

| Package | Holds |
|---|---|
| `core` | the pivot model, plans, merge and finish: `PivotView`, `CellKey`, `CellSet`, `Axis`, `AggregationPlan`, `PivotCore`, `PivotAnswer`, `ShowAs`, `ScaleOptions`, `TopFirst` |
| `spi` | `PivotProvider`, `AggFunction` (start, add, merge, finish), `Kind` (mergeable, sketch, holistic), `Batch`, `Viewer` |
| `functions` | the built-ins and sketches: `Builtins`, `Functions`, `Hll`, `Kll`, `SpaceSaving`, `Sketches`, `OncePerParent`, `Windows`, `Final` |
| `sql` | `GroupingSetsCompiler`, `DuckDbExecutor`, `TabularProvider`, `TabularSource`, `SampledResolver`, `RowFed` |
| `rows` | `RowsProvider` (rows held in memory) |
| `formula`, `custom`, `python`, `harness` | formula functions and SQL macros, custom-function storage, versions, approval and official-run guards, the Python worker client, the harness and playground |
| `cluster`, `cache`, `rollup`, `scale`, `live`, `explain`, `whatif`, `export` | `PartialCodec`, `OrderedMerge`, `PartialGather`, `ClusterRoute`; `StateCache`; `Rollups`, `AggregateAwareness`; `ScalePolicy`, `ScanRates`, `Progressive`; `LivePivots`, `AnswerPatch`; `Explain`; `WhatIf`; `PivotExport` |

The server wires it (`server.pivot`: `PivotController`, `PivotViews`, `PivotViewVersions`, `PivotCellComments`, `KindSources`,
`PanelSources`, `DatasetLayers`, the document fields, the Python process client, the scale and live configuration): Export data's tables
as sources, the VaR, FRTB-SA and SIMM providers (which implement the SPI; the vector arithmetic stays where it is), REST, busy protection,
the memory budget for the cache, saved views and audit. The console's `drishti_pivot-engine.js` asks and lays the answer out;
`drishti_pivot-grid.js` is the one grid; the presets are `drishti-console/config/pivot-presets.yaml`.

### 26.11 The screen, charts and export

The screen is Excel's PivotTable as people know it (field list and four zones, show-as, calculated fields over states so they are right at
every level, typed grouping, slicers, conditional formats, drill-through) with what Excel lacks where Excel's equivalents would be (the
switches, provenance, comments). On a phone the grid's top-left and the headline come first, charts at a glance, expand, drill, explain and
provenance under More, and the field list and the function playground on the desk only (not built beyond the narrow layout). **Charts of a
selection** are suggested from its shape, linked both ways to the grid, and **never stack a non-additive measure**: VaR by desk is drawn
beside the bank's VaR with the diversification as its own segment, from the engine's own gap; drawn with drishti-chart over the vendored
ECharts. **Export** asks what (the pivot as shown with its outline, a cell's underlying rows, a chart's data, the view definition) and
how: xlsx with Excel's native outline levels, a `SUM` formula only where a total is a plain sum of its parts and the server's value
otherwise, a Provenance sheet (data versions, functions with versions and authors, filters and rules in words, exactness, who and when);
CSV (one row per cell and measure, or the grid; zip and gzip for several or big files); JSON Lines; Arrow. Exports stream from the server,
memory-only data is never written to a temporary file, and every export is audited without its values. Not built: a native PivotTable
with its pivot cache (only measures Excel re-aggregates correctly would be data fields, the rest on a Values sheet), charts bound to it,
references as named ranges, Parquet and Power Query outputs, pinned charts with PNG and SVG export.

### 26.12 The switch, the API, the command line, rights and audit

`pivot` is a compute engine with its own file, `config/engines/pivot.yaml` (the engine catalogue: `mr-cube` needs it, `ccr-cube` will);
off, it does not exist on that server (`PIVOT`, the menu, Help pages, `drishti.py pivot` and the playground disappear, `/api/v1/pivot`
answers `404 DRS-1020`, the cubes cannot be on). Its settings are in [CONFIGURATION.md](../admin/CONFIGURATION.md). The API (`/api/v1/pivot`:
query, estimate, expand, cells and their rows, provenance, compare, views, catalogue, functions and their test, live, export; explain under
`/api/v1/explain` kind `pivot`; function approval in the one Approvals queue) and `drishti.py pivot` use the same words, and a refused view
prints the same sentence as the screen. **Rights**: the provider applies the person's row rules and masks in the plan before grouping (BI's
security rewriter for models, the VaR engine's scope entitlements for VaR); a shared view opens with the reader's entitlements and never
carries the author's numbers; custom functions receive only declared columns of rows the reader may see. **Audit**: every view run (who,
source, data versions, rows read, functions with versions, time), every export, every function save, test, approval and revocation.

### 26.13 Tests that prove it

`GroupingSetsCompilerTest` (one query for cells and every total level, parameters bound, row rules before grouping), `MergeOrderTest` and
`ClusterMergeTest` (the same digits on 1, 2 and 3 nodes and any arrival order), `TrueTotalsTest` (every non-additive total equals the
measure over the union of its members), `SketchErrorTest` (HLL, KLL and Space-Saving within their stated errors on seeded data; exact on
request equals exact), `ExactQuantileTest`, `FunctionHarnessTest`, `CustomFunctionsTest`, `FormulaFunctionTest` and
`PivotFormulaParityTest` (formulas equal in Java and SQL), `test_pivot_fn.py` (Python's four steps and sandbox), `VarPivotApiTest`
(every cell and total equal to `/api/v1/risk/var/scope` to the bit, sharded and whole), `CurrencyAndUnitsTest`, `ShowAsTest`,
`ExplainAddsUpTest`, `WhatIfOverlayTest`, `RollupTest`, `ProgressiveTest`, `ScaleSwitchesTest`, `LivePivotTest`, `ParquetRouteTest`,
`DocumentPivotTest` (paths, grains, once per parent, lists never crossed), `PivotExportTest`, `BiParityTest`, `AggregationOwnerTest`, and
the browser tests of the screen. The benchmark harness is `tools/bench/pivot_bench.py`; its runs (synthetic trades of 5, 25 and 75 GB on
one and three nodes, cold and warm, with and without a rollup; larger sizes only extrapolated and labelled so) and results are in
[BENCHMARK.md](../admin/BENCHMARK.md#16-the-pivot-engine-at-scale).

### 26.14 Not built yet

Beyond the items named above: the per-person limit on running large views; the batch-as-merge form of live pivots on streams; layout
advice (a proposed clustering from the dimensions views filter by); asking for a view in words through the AI layer (it would propose a
definition the person sees before it runs, never computing a figure); automatic insights (outliers, sudden changes, concentration, flagged
with the reason); references in formulas and Excel; the native PivotTable export; tabular reads of the BI layers and of object stores
through the pivot (local Parquet files are read today).

## 27. Compute engines

A compute engine (VaR, exposure, sensitivities, FRTB-SA, SIMM, the MR cube, the pivot engine, the in-memory lake, the data inbox, Arrow
Flight, OpenBLAS) is switched on or off per node, each with its own configuration file, and off means the engine does not exist on that
node. How an administrator uses it (the files, what off means for people, dependencies, Admin → Engines, the cluster, the command line) is
[ADMIN_GUIDE.md section 21](../guides/ADMIN_GUIDE.md#21-compute-engines-on-or-off); this section is how it is built.

### 27.1 The catalogue

The engine catalogue is part of the product: `drishti-server/src/main/resources/engines/catalogue.yaml`, with the console's byte-identical
copy `drishti-console/config/engines.yaml` (`EngineCatalogueTest`). Each entry holds the engine's id, title, file, the settings prefixes it
owns, what it **needs** (on the same node: the needing engine calls the needed one in process) and **uses** (works without it, with the
parts that read it hidden), and everything it brings to the screen: `words`, `menus`, `help`, `landings`, `tiles`, `lenses`, `bi`, `cli`,
`playgrounds`, `connectors`, `kinds`, `ai-tools`, and the console's paths (`console:`; `=/play` is that path only). The console's menus,
Help, tiles and lens links, and Admin → Engines' "what turning it off hides", all read these entries; nothing is coded per engine. The
longest API prefix wins (`/api/v1/risk/sensitivities` is the sensitivities', not FRTB-SA's). A new engine (SA-CCR, FVA, the CCR cube,
liquidity, climate) adds one entry, its files and a `ComputeEngine` bean; `installed: false` marks an engine listed ahead of its build. The
catalogue's build test refuses a cycle in `needs`. A dependency read over Drishti\*net (`needs-anywhere`) is reserved; no engine declares
one.

### 27.2 The files and their order

Each engine's file exists twice, byte for byte: `drishti-server/src/main/resources/engines/<id>.yaml` in the jar and
`config/engines/<id>.yaml` for the site. `application.yaml` imports both, jar copy first, **before** the local file and the packs overlay:

```yaml
spring:
  config:
    import:
      - classpath:engines/var.yaml
      - optional:file:${DRISHTI_ENGINES_DIR:./config/engines}/var.yaml
      # ... one pair per engine, in the catalogue's order
      - optional:file:./application.local.yaml
      - optional:file:${DRISHTI_PACKS_OVERLAY:${drishti.data.dir:./data}/packs/added.yaml}
```

Spring Boot's order gives, strongest first: the command line and environment, the packs overlay (no engine keys), `application.local.yaml`,
the site's engine file, the jar copy, `application.yaml` (no engine keys). `drishti.engines.{dir, drain, watch-seconds}` stay in
`application.yaml`, and so do `drishti.admission.*`, `drishti.memory.*` and `drishti.disk.*`, which guard the whole server. A lens's
presets (`EditionEnvironmentPostProcessor`) sit above the engine files.

**Switch and source** (`EngineSwitches`) reads Spring's own property sources in order and reports which one decided each switch. The keys
`drishti.var.enabled`, `drishti.ccr.exposure.enabled`, `drishti.frtb.enabled`, `drishti.simm.enabled` and `drishti.pivot.enabled`, set above
the shipped files, decide their engine's switch (`DRS-1024` logged once); the in-memory lake is on when its stores are configured in the
local file and nothing sets its switch; `drishti.frtb.sensitivity-kind`, `factor-kind` and `cache-minutes` read `drishti.sensitivities.*`
through a lowest-precedence alias layer (`EngineKeyAliases`). Each lens's `config/lenses/features.yaml` entry names the engine switch
(`var: { server: [drishti.engines.var.enabled] }`).

### 27.3 The lifecycle and what off means on the server

Every engine implements one lifecycle, `ComputeEngine` (`start`, `stop(drain)`, `state`, `memory`, `health`); each engine's configuration
makes one `BasicEngine` bean, and `EngineRegistry` finds every `ComputeEngine` bean, so an engine still to come registers by itself. The
services keep their Spring beans, but they are thin: their open dates, caches, pools, schedulers, watchers and answers are created on first
use and released by `stop` (the same release as Admin → Caches' purge of the engine). Each service asks the registry before any work and
refuses with `EngineOffException` (`404 DRS-1020`). An engine off at start creates no thread, opens no date, registers no connector of its
own and no computed kind (`var-result`, `exposure-result` ...), and reserves no memory in the safety nets (the automatic `open-heap-mb`
shares go to the engines that run); `/actuator/health` shows it `off`, never `down`. The in-memory lake off at start is built with no
store.

**Connectors and kinds.** An engine's catalogue entry splits what it touches: **its own** connectors and computed kinds (not registered
while off) and **what it reads**. An input kind marked `engine-input: true` (large, used only to feed engines: `pnl-vector`, `scenario-pv`,
`exposure-path`, `simulation-run`, `position-sensitivity`) is not read ahead, warmed or pinned on a node where no running engine reads it;
its connector stays registered for the pack's own pages (`XPATH` browses a path). Reference kinds are never affected.

**Live on and off.** Off: the gate refuses new requests, the requests in flight drain (`drishti.engines.drain`, then cancelled with a
message), then `stop` releases everything and unregisters connectors and kinds; the live channel tells open pages. On: `start` reads and
checks parameter files, registers connectors, creates threads. `memory-lake` and `flight` are written to their file and wait for a restart
(direct-memory caps and the Flight port are set at start; the launchers' `tools/lake/memory_caps.py` reads the lake's file).

**Writing the file.** Admin → Engines writes only `enabled` (keeping its `${VARIABLE:default}` form) and `changed`, with every comment and
other key kept; atomically (a temporary file in the same folder, then a rename); refused with `409 DRS-1023` when the file changed on disk
since it was read or the folder is not writable. The folder is watched (`drishti.engines.watch-seconds`), so a hand edit applies as a page
change does and is recorded in History with no actor.

### 27.4 The gate, routing and the digest

`EngineGateFilter` (filter order 4, after the caller is known) maps a request's path to its engine by the catalogue and counts the requests
in flight per engine (the drain). Not running here: `EngineCluster` forwards it to the least loaded peer whose digest says it runs the
engine (`RunCluster.call`, signed with the cluster key, on behalf of the caller; the answer carries `X-Drishti-Engine-Node`), else `404
DRS-1020`. An engine `starting` or `stopping` on a node is not routed to. The capability digest carries the engines in its `services` map
(entry `engines`: `var:running,frtb-sa:off,...`), so the digest's shape is unchanged and an older peer reads it; a change announces a new
`digestHash` at once. A node with `var` off never owns a VaR shard (it leaves the shard map as a draining owner does). A peer's change from
another node's Admin → Engines arrives as `POST /api/v1/net/engines`, signed, and is applied as a local change; both nodes audit it.

### 27.5 The console

`/public/about` (what the console polls) and `/api/v1/about` carry `engines: [{id, title, here, cluster}]`; the console learns them with the
edition (`core/engines.py`, `Editions.learn`) and again at once after a change made on Admin → Engines. `core/engines.py` answers every
question: `engine_available` (running here or on any node), `word_hidden`, `typed_engine`, `path_engine`, `menu_hidden`, `help_hidden`,
`tile_hidden`, `lens_hidden`. What runs is each app's own (`engines.State` on its editions, read per request through `AppEngines` in
`core/app.py`), never shared between apps of one process. It is wired into `Edition.word_on`, `command_shown` and `typed_shown` (so FUNC,
type-ahead, Home cards and chips follow), the route gate (`core/edition_gate.py`), the menus (`requires: engine:<id>` and the catalogue's
`menus:`; an Engines-menu column names its engine with `engine: <id>` and its readers with `opens:`, checked by `core/menus.py`
`engine_refused` for the column and the typed word alike), Help (`help.yaml engine:` and the catalogue's `help:`), the lens links and `/go`.
BI: `EditionBi.engines(...)` drops a dataset or model whose name starts with an engine's `bi:` prefix, and every item on it, while the
engine runs nowhere. Admin → Engines is `routes/admin_engines_routes.py`, `templates/admin/engines.html`,
`static/js/drishti_admin-engines.js` and `static/css/drishti_admin-engines.css`; the command line is `tools/enginescli.py` (`server engines`
and `admin engines`; `problem_error` maps `DRS-1020` to exit 3 for every command).

### 27.6 Configuration document and checks

`tools/settings_catalogue.py` has the area **Compute engines: `config/engines/*.yaml`**, one table per file, read with the same comment
reader as `application.yaml`; CONFIGURATION.md and Help → Configuration list every engine key under its file. `--check` fails when a shipped
engine file and its jar copy differ, when a catalogue entry has no file or a file no entry, and when a key appears in both
`application.yaml` and an engine file.

### 27.7 Decisions

| # | Question | Decided |
|---|---|---|
| 1 | Where the command line lives | `drishti.py server engines` beside `server commands` and `server packs`, with `admin engines` accepted |
| 2 | Off answers `404` or `403` | **404** (`DRS-1020`): nothing is forbidden, the engine is not on this server; the words say so |
| 3 | `memory-lake` turned on from the page | written to the file with **restart needed** |
| 4 | Changing another node's engine from any node | yes, signed with the cluster key and audited on both nodes |
| 5 | Regulatory VaR (backtesting, reports, sign-off) | part of `var`: it reads only VaR's official records; split when a node needs one without the other |

### 27.8 Mock-ups and build checklist

Three options of Admin → Engines, self-contained HTML (dark by default, light with `#light`); **A was chosen**, with C as its Cluster tab:

| Option | What it is | Screenshot |
|---|---|---|
| [A: the engine list with a review panel](mockups/engines/A.html) (**chosen**) | one table of engines on this node (switch, state, memory bar, needs, used by, where the switch comes from); a side panel opened by a change: the reason, the dependency check, what will be hidden, live or restart; History below; the cluster as a second tab | [A.png](mockups/engines/A.png) |
| [B: engine cards and a dependency map](mockups/engines/B.html) | a card per engine arranged by family, with a small dependency map; a change opens a confirm dialog | [B.png](mockups/engines/B.png) |
| [C: the cluster matrix](mockups/engines/C.html) | nodes × engines in one grid, each cell a switch with its state and memory; an "available to people" row | [C.png](mockups/engines/C.png) |

A is the shape administrators know from Functions (a list, staged changes, a review panel with the reason, History) and shows what depends
on an engine and what disappears at the moment of the change; C's matrix is the better view for a large cluster and is A's Cluster tab.

| # | Part | Status |
|---|---|---|
| 1 | The catalogue and its build test | built |
| 2 | The engine files, their jar copies, the imports, the other key names and the start warning, `features.yaml` naming the switches | built |
| 3 | `ComputeEngine`, `BasicEngine`, `EngineRegistry`; every built engine behind `start` and `stop`; `404 DRS-1020` | built |
| 4 | Connectors and kinds registered only while the engine runs; `engine-input` kinds not read ahead | built |
| 5 | Live on and off: drain, release, unregister, the live-channel notice | built; the admission check on an engine coming on (`503 DRS-9502`) waits for its first consumer |
| 6 | Admin → Engines (mock-up A, Cluster tab C), the admin API, writing the file, History, audit | built; the impact answer lists the engines that need or use it and the nodes that still run it, not yet the saved BI reports and monitors that read its kinds |
| 7 | The console everywhere: words, menus, Help, landings, tiles, lenses, FUNC, type-ahead, playgrounds, BI | built; AI tools per engine wait for their consumers |
| 8 | Drishti\*net: `engines` in the digest, routing by engine, the cluster matrix and a peer's change | built |
| 9 | `drishti.py server engines` (and `admin engines`) | built |
| 10 | CONFIGURATION.md's engines area and its checks | built |
| 11 | SA-CCR, FVA and the CCR cube as engines | each adds its entry, files and bean when its engine is built |

### 27.9 Tests that prove it

| Test | Proves |
|---|---|
| `EngineCatalogueTest` | the catalogue and the console's copy are identical; no cycle; every word known |
| `EngineFilesTest` | the import order (environment > local file > engine file > shipped copy), each other key name with its warning, the jar copies identical to the site files |
| `EngineRegistryTest` | an engine off at start creates no thread, opens no date, registers no connector or computed kind, reserves no memory; on, it does |
| `EngineApiTest` | a whole server: an engine turned off answers `404 DRS-1020` in words and on again answers; a dependency refusal (`DRS-1021`); the file written with its switch and `changed`; History; VaR off with its dependents hides its BI datasets and API |
| `EngineClusterTest` (two members) | `frtb-sa` on one member only: the other's console shows FRSA and its answer is the first member's, routed; off there: `404` within the gossip time; the digest carries `engines` |
| `EditionWorldServerTest` | a lens's `var: off` preset switches the engine off |
| `tests/test_engine_off_everywhere.py` (console) | for every engine of the catalogue (generated from it): its words unknown on the command line, in FUNC, type-ahead and search; no menu item, Help entry, tile, chip, BI dataset or playground link on any route; the CLI exits 3 with the words |
| `tests/test_engines_browser.py` | the page: switch, reason, review, refusal words, restart pill, history (with the guide's screenshots) |
| `tools/test_enginescli.py` | the command line and its exit codes |

## 28. Clusters inside: membership, placement and routing

How Drishti\*net and the shard machinery work inside: the member table and gossip, the capability digest, routing, the copying of
identity and user state, the shard map, rendezvous placement, handover and the scatter-gather of partials, and the resource bounds of
one server. Running a cluster (files, Admin → Net, roles, sizing, AWS, tuning) is the operators' [CLUSTER_GUIDE.md](../guides/CLUSTER_GUIDE.md).
Section 13a is the short form.

### 28.1 Membership by gossip (SWIM over HTTP)

`drishti-net` (no Spring, no engine; an ArchUnit rule keeps it so) holds `MemberTable`, `Gossiper`, `PeersFile`, `NodeIdentity`,
`NetFolder` and `NetSchema` (the `config/net/` folder with line numbers, history and `If-Match`), `ClusterKey`, `OnBehalfOf` and `PeerHttp`.
In the server, `NetService` runs the node, `NetCallFilter` checks the cluster key before the token filter, `NetController` and
`NetAdminController` serve the membership and management endpoints.

**The member entry** (`Member`): `nodeId` (generated once, kept in `<data dir>/net/node-id`), `url` (a real address; loopback, wildcard,
link-local and container-bridge addresses in any spelling are refused at the intake by `MemberTable.merge` and `HostAddress.callableUrl`),
`gossipPort`, `incarnation` (raised only by the member itself), `heartbeat` (raised every round), `state` (`alive`, `suspect`, `dead`,
`left`), `digestHash`, `switchesHash`, `version`, `protocol` (`Member.PROTOCOL` = 1; another protocol is listed, never routed), `tags`,
`since` and `drained`. Beside each entry a member keeps local times only (`beatAt`, `heardAt`, a smoothed latency, `stateAt`,
`MemberTable.Known`), so clock skew never makes a member suspect.

**A round** (`Gossiper.round`, every `interval`, on the platform thread `drishti-net-gossip`):

1. raise its own heartbeat;
2. push-pull with `fanout` random members that are alive or suspect (a suspect one hears of its suspicion and can refute), in parallel
   on the eight platform threads `drishti-net-gossip-io-*`, each cut at `max(200 ms, 2 × interval)`: `POST /api/v1/net/gossip` with the
   whole table; the answer is the peer's whole table; both merge;
3. reaching no one: try the join list (seeds, then `peers.json`, alive first), three at a time; and every 30th round exchange with one seed
   or saved member outside the reachable table, which heals a split;
4. detect: an alive member whose heartbeat has not risen here for `suspect-after` is pinged directly on its gossip port, then through
   `indirect-probes` others (`GET /api/v1/net/ping?target=`); still silent, `suspect`, keeping its incarnation;
5. suspect for `dead-after`: `dead`; dead or left entries are forgotten after `forget-after`;
6. tell the listeners (save `peers.json` on a join or leave, pull new digests); save `peers.json` every `persist-every` anyway.

**The merge rule** (`Member.supersededBy`): the higher incarnation wins; at equal incarnation the worse state (`left` > `dead` > `suspect`
> `alive`); at equal incarnation and state the higher heartbeat (it carries the newest url, tags, digest hash and drain flag). A member's
own entry is changed only by itself: news of itself worse than `alive`, or at a higher incarnation, makes it raise its incarnation and stay
`alive`. **Leaving** (`Gossiper.leave(3)`): `left` with a raised heartbeat, pushed to every reachable member three times. **Rejoining**
raises the incarnation. **Joining** happens on Spring's `ApplicationReadyEvent`; the node is *joining* (`DigestService.joining()`) until it
has the digest of every alive peer, at most `join-grace`.

**Two lanes.** `PeerHttp` has a **control lane** (gossip, probes, digest pulls: HTTP/1.1, its own connections, two platform threads, a
connect timeout of half the gossip timeout, to the member's gossip port) and a **data lane** (views, documents, history, searches, state,
the live relay: HTTP/2 when routing.yaml's `peer-protocol` is `http2`). The receiver's **gossip port** (`GossipConnector`) is a second
Tomcat connector with its own acceptor, 4,096 connections and eight platform threads, serving only gossip, ping and digest; on both ports
those three are answered by the first filter (`GossipPath`), from memory, after the cluster key check. A per-path executor is not possible
in Tomcat (an executor belongs to a connector) and a priority filter alone still needs a request thread, which is what a busy server lacks
(`NetGossipBusyTest`: with `gossip-port: off` every node suspected both peers under load; with the port, none). HTTP/2 is primed per peer by
one signed ping made alone (the first call, and the first after 30 s of quiet), so the upgrade happens once; streams per connection are
raised to 1,000 (`NetConfiguration.peerHttp2Limits`); a peer without HTTP/2 answers in HTTP/1.1 on the same call.

**Signatures.** `METHOD\npath?query\ntime\nnode\nsha256(body)`, HMAC-SHA256 with the cluster key, the node in `X-Drishti-Net-Node`, a 60 s
window (`ClusterKey.WINDOW_MS`). The on-behalf-of token names the user, roles, embed app, the forwarding node, the request id and its
audience node (`aud`), valid 30 s. Mutual TLS is the transport on top, never in place of the HMAC (which binds the call to its body, path,
time and node).

### 28.2 Message sizes and convergence

A member entry is about 350 to 450 bytes of JSON. Each round a member sends and receives `fanout` whole tables (estimates):

| Members | Table | Bytes a member a second | Rounds for news to reach all |
|---|---|---|---|
| 8 | ~3.5 KB | ~21 KB | 2 |
| 20 | ~9 KB | ~54 KB | 2–3 |
| 50 | ~22 KB | ~130 KB | 3–4 |
| 100 | ~45 KB | ~270 KB | 4–5 |

With push-pull the informed share grows about fourfold a round: log₄ N rounds plus one or two. A heartbeat seen second-hand is up to that
many rounds old, so at 100 members a healthy member's heartbeat can look 4 to 5 s old, just under `suspect-after`: probes answer, but
their traffic rises, which is why larger clusters raise `suspect-after`.

### 28.3 Failure detection and its timing

With the defaults (1 s, 5 s, 30 s), a member that crashes at t = 0: at 5–6 s peers find its heartbeat stale, the direct ping fails
(refused at once, or cut at 2 s on a black hole), two indirect pings fail; at 5–8 s it is `suspect` everywhere within 1–3 rounds, and
`ShardPeers.peers` and `Member.routable` leave it out; at 35–38 s `dead`; at 24 h forgotten. A member that was only slow answers a probe
or refutes with a higher incarnation, and nothing moved unless it stayed out longer than `stable-seconds`.

### 28.4 The capability digest

`Digest` and `CapabilityTable` live in `drishti-net`; the server's `DigestService` rebuilds this node's digest every round from cheap reads,
announces a new hash at once (a round out of turn), compares peers' hashes every 250 ms, pulls a changed digest (`GET /api/v1/net/digest`)
and swaps the capability table whole. The digest holds `nodeId`, `url`, `version`, `protocol`; `packs` (name, code, version, content hash,
`peerOnly`); `kinds` with their sources (connector, type, table identity, `earliestDate`, `latestDate`, `dataVersion`, columnar, dated,
live); `load` (the average latency of the last full minute, from `RequestActivity`); `calendar`; masked fields, nodes per role and BI
report ids; and `services` (name to compact text: `engines`, the VaR engine's `var-shards`). The hash leaves out the load and the build
time, so it moves only when what the node serves moves; a held digest is refreshed once a minute for the load.

**Table identity** is `SourcePlugin.tableIdentity(kind)`: the Delta connector gives `delta:<id>` from the first commit, else its location (a
local path qualified by the machine's name); the `file` connector its folder and a SHA-256 of the kind's files (`file:<folder>#<hex>`); any
other source nothing, and is a shard of its own node. `SourcePlugin.holdsNone(kind)` leaves out a source that surely holds nothing of the
kind. Two nodes naming the same identity are replicas for the kind; different identities are shards.

**Switches** (commands and packs on or off) carry a hybrid-logical-clock stamp; their hash rides the member entry (`switchesHash`), and a
node whose hash differs pulls the peer's switches and keeps the newer of each.

### 28.5 Routing a request

`NetRouting` answers three questions from the capability table cut to the caller's roles and to the member table's newest word: answer
here (the pack is on, the newest version among reachable nodes, not peer-only here, a local source holds the entity); **view forwarding**
(`ViewForwardFilter`, after the token filter: the whole request, with `X-Drishti-Net-Hop: 1` and an on-behalf-of token, the answer
streamed back unchanged); **data forwarding** (`PeerSources`, the source router's `Fallback`: after the local sources, `PeerSource` fetches
`GET /api/v1/net/fetch/{kind}/{id}` and this node renders and masks again). A `PeerSource` is not in the source registry, so nothing that
lists sources (BI, caches, Health) sees a peer. A forwarded read answered `404` goes to the next renderer (at most three), renderers whose
sources hold data first. Peers are chosen: alive → holds the date → freshest data version → lowest latency → a stable hash of the entity id.
A digest of a member that left or died is kept (never routed to) until it is forgotten, so an answer can name the holder that is down; a down
node counts only for the dates between its sources' `earliestDate` and `latestDate` (`Fallback.downHolders`).

- **History** goes to peers in one call for every date this node lacks (`GET /api/v1/net/history/{kind}/~`), merged by date, the higher
  generation winning an overlap; never cached when failed, partial or empty.
- **Scatter-gather** (`SearchScatter`): the targets are `CapabilityTable.scatterTargets` (shards, never a replica of a node already asked;
  the fewest nodes that cover every table, fastest first), asked in parallel through `GET /api/v1/net/search` within `scatter-budget`;
  rows merged by kind and id (this node's first), re-sorted, cut; `matched` and `scanned` deduplicated through each share's `matchedIds`
  (up to 20,000, removed from the client's answer); a down holder named in `failed`.
- **Grouped pages** (`GroupGather`, `GET /api/v1/net/group/{kind}/{id}`, `DerivedSourcePlugin.share`): exact (members united by id) when
  every node lists all its members up to `max-members`, otherwise combined from each node's totals; partial pages carry `partial` and
  `missing`.
- **Live relay** (`PeerSource.subscribe`, `GET /api/v1/net/subscribe/{kind}/{id}`): the topic hub is partitioned by the subscriber's roles
  while the cluster runs (an upstream is masked at the peer for the user it was opened for); a broken relay reopens with a fresh token.
- **BI reports** route by `NetRouting.report` to a node whose digest lists the report and holds its datasets' kinds; **BI runs** go to the
  model's owner by rendezvous (`RunCluster`), their actions to the run's node (`RunForwardFilter`), adoption after a lapsed lease by one
  conditional update in the shared database.
- **Selections** of a Sutra Next screen are tokens kept on the node that issued them (`tok_<node>~…`); `SelectionForwardFilter` forwards a
  request that reaches another node to the issuing one.
- **Masks across nodes**: a cache of what a peer sent masked is keyed by the caller's security signature (`Principal.securitySignature()`:
  sorted roles and embedding app), through `SourceRouter.partition()`.
- **The node that answered**: `NetServedBy` adds the *Node* line for administrators; `NetCaller.Served` records what a request asked of
  other nodes.

### 28.6 Identity and user state across nodes

`StateSync` (signed `POST /api/v1/net/state`, batches of 200 every quarter second, up to 10,000 waiting per peer) copies the namespaces
`settings`, `layouts`, `pivots` and `workspaces` (`ReplicatedPreferences`), console sessions (the SHA-256 of the id) and, through
`IdentitySync` (a `StateSync.Part`), `identity-users` (with the password hash), `identity-roles` (`RoleStore.adopt`) and `identity-tokens`
(the secret's SHA-256). A change is queued for every member not gone (alive or suspect, drained included) and sent once it is alive; a member
that joins gets a catch-up applied only where absent; a member that goes dead drops its queue and gets the catch-up when back. Applied
changes are written under a thread-local flag so listeners do not echo them. The newest write wins (each node's wall clock, within the key's
60 s); one user name made apart on two nodes is settled by the older creation (`UserStore.putIfEmpty` seeds only into an empty store);
deletions are tombstones (`<data dir>/net/identity-deleted.json`, 90 days). The last-enabled-administrator rule is checked where a change
is made and again where it is applied, under a lock, one change at a time: the disabled administrator with the newest `updatedAt` (then the
name) is enabled again, so every node picks the same; audited `last-admin-kept`, listed in `NetHealth.summary().warnings`. Alerts, monitors
and scheduled reports are not copied: they act.

### 28.7 The shard map

`ShardMap` (`drishti-compute/.../shard/`): per business date, the rows counted per value of the shard key (VaR: `LEGAL_ENTITY`, then
`DESK`), read as columns without the vectors; walked in key order, a shard closes when the next key would take it over `target-shard-mb`
(2048 by default; a row is 4 × scenarios bytes, the float32 hot row). A key value (a desk) is never split; the ranges cover the whole key
space, so every row has exactly one shard. The map is a pure function of the counts, so every member computes the same map; its digest names
the cut, not the data, so an intraday batch that moves no cut keeps the map. At 1-day width (509 scenarios) 2.3M positions are 4.7 GB of
rows, 3 shards at the default; at 1,518 values 14 GB, 7 shards (estimates).

### 28.8 Rendezvous placement

`ShardOwners.owners`:

```
weight(node, key) = first 60 bits of SHA-256( node + "|" + key )      key = the shard's range "LE-A/DESK-X..LE-B/DESK-Y"
ranked            = candidates sorted by weight descending, ties by node id
owners            = the pinned member first (if a candidate), then repeatedly the heaviest candidate not chosen whose zone is unused,
                    else the heaviest candidate not chosen, until `replicas` owners
```

**The candidates** (`ShardMembership`, `ShardPeers.Peer.eligible`): this server and every member that is alive, speaks the protocol, is not
drained, has sharding on, owns shards (not `coordinator-only`) and is not draining. The view moves only once it has been the same for
`stable-seconds`; a draining member leaves at once; it is looked at again at most every 250 ms.

**Why rendezvous and not a ring.** A consistent-hashing ring needs hundreds of virtual nodes per member to balance and a shared ring state;
rendezvous needs neither: each shard ranks the members independently (even in expectation, no tuning), gives the top-k replica set
directly, moves only what a joiner wins or a leaver held, makes the zone rule "the next heaviest in an unused zone", and is a pure function
with nothing to agree on. Its cost, O(members) hashes a shard, is microseconds. **Rebalancing:** with N members and R × S owner slots, a
joiner wins each slot with probability 1/(N + 1); a leaver's slots go each to the next heaviest member (`ShardOwnersTest`: a fourth member
takes 20 to 30% of first owners and replica slots over 2,000 shards). The same rendezvous choice places a BI model's runs (`RunCluster`).

### 28.9 Handover, readiness and the gather of partials

**Handover** (`ShardHandover.decide`, `VarShards` every `rebalance-seconds`): a new owner builds its shard from the lake (its key range only,
the files skipped by statistics), writes `<hot>/<date>-shard-<i>/`, announces it ready in the digest service `var-shards`; the old owner keeps
serving and releases (unmaps, deletes) only `grace-seconds` after every owner announced it ready. So a shard in use always has a ready
holder. A date put together again is swapped in whole (`VarService.swap`) only when complete.

**Readiness in the digest** (`ShardPeers.State`): sharding on, owner, draining, per date the maps held and per shard the first 16 characters
of the data digest it serves, and the dates open (a member of an older release has its held dates counted as open,
`State.openOrHeld`). A change rebuilds the digest at once (`ShardPeers.announce`): readiness needs no protocol of its own. A USD shard book
(and its currencies) of a date open nowhere for `release-after-seconds`, not among the newest `hot-dates` and not read here meanwhile is
released (unmapped; its file left to the disk budget).

**The gather** (`ShardGather`): each shard's part of a scope goes to one member, in this order: owners ready at the date's data version
(first owner first), any other member ready at it (an old owner mid-handover), owners not yet ready (they build). `POST
/api/v1/risk/var/partial`, signed, one hop, on behalf of the caller; the answer is labelled with shard, map digest, data digest and row
count and refused when any differs. Exactly one answer per shard, added in shard order; a shard nobody answers fails the scope (`503
DRS-5215`); a member at another data version answers `409 DRS-5216` and the next is asked. Never a partial VaR. The skeleton (ids,
columns, links, aggregates) is fetched from owners (`GET /api/v1/risk/var/shards/skeleton`, `ShardSkeleton`) and copied to every member;
node figures are summed bottom-up from the shards' book aggregates, equal to one server to the bit (`VarShardClusterTest`,
`ShardAssemblyTest`). The machinery (`com.ash.drishti.compute.shard`, `com.ash.drishti.server.shard`) is measure-agnostic; VaR is its
first `ShardedMeasure`.

### 28.10 Why no consensus protocol

Ownership is a pure function of shared inputs (the shard map from the same lake, the membership gossip converges on). Two members that
briefly disagree cannot produce a wrong number: a partial is served only by a member that holds the shard at the data version asked, so
disagreement costs a refused call. A Raft or ZooKeeper quorum would add a service and a leader for no gain in read correctness. It would help
singleton writers under a partition (one official capture, one PV-stream reader); Drishti uses its database for that, by lease, as BI runs
do. A second site is a separate cluster, never one cluster over a WAN.

### 28.11 Exposure sharding (design, not built)

Exposure runs on one server today. The design reuses `ShardMap`, `ShardOwners`, `ShardHandover`, `ShardMembership` and `ShardPeers` under a
digest service of its own (`exp-shards`), behind `drishti.ccr.exposure.sharding.enabled`:

- **Key** `[COUNTERPARTY, NETTING_SET]`, no group prefix: a netting set is never split, a counterparty's sets sit together.
- **Sharded:** per set the V, E and N matrices (float32, `VMAT`, `EMAT`, `NMAT`) and the load itself, each owner streaming only its sets'
  `exposure-path` rows one set at a time (the heap holds one set's float64 matrix, not the date's). **Copied:** the set index (about 200 B a
  set), the run's grid, memberships, collateral, CSAs, limits, credit curves.
- **The owner** nets paths, applies collateral with the margin period of risk, computes E and N per path and step. **The partial** is the
  summed E matrix of the scope's sets (steps × paths float64: 800 KB at 50 × 2,000), plus per-counterparty EE rows for CVA. **The
  coordinator** adds partials in shard order, then EE, ENE, EEE, EPE, EEPE, PFE quantiles, peaks and CVA: quantiles only after adding.
  Contributions take a second round for the paths around the quantile.
- **Sizes:** 800 KB a set at P = 2,000, G = 50 (80 GB a date for 100,000 sets, 40 GB an owner of 4 with two replicas); 4 MB a set at
  P = 5,000, G = 100.
- **Before switching it on:** bit equality against one server for every scope kind on Delta and Iceberg, with an owner joining, one down and
  both down; official captures of 20 dates identical; a week side by side with the single server as the reference.

### 28.12 Bounded resources inside one server

- **One cache budget** (`MemoryBudget`, `drishti.memory.cache-share` 0.5 of `-Xmx`): weighted pools (`connector.idmaps` 20,
  `connector.columns` 20, `connector.documents` 6, `connector.parts` 4, `engine.answers` 20, `engine.layouts` 10, `small` 20), each a set
  of Caffeine caches weighed in bytes. At `squeeze-at` (0.85 of the old generation after a collection) the connectors' and small pools are
  halved for a minute. `CachesWeighedInBytesTest` fails the build on a count-only cache not listed in `count-only-caches.txt`.
- **One disk budget** (`DiskBudget`): folders register name, path, cap, size and how to free their least recently used items; a writer
  calls `reserve(folder, bytes, what)` before a build (VaR reserves three times its largest file, exposure twice), which trims, checks the
  guard and refuses with `503 DRS-5218` before anything is written; every folder is checked each `drishti.disk.check-interval`.
- **Mapped id maps** (`MappedIdFile`, `IdMapFolder` in `drishti-api`; `FileMaps` for unmapping): one mapping a file, reads inside a guard,
  `close` unmaps once no reader is inside; release by idleness, the folder's cap, an optional TTL, never a map being read or of a date open
  in an engine (`DatesInUse`).
- **Load pools** (`LoadPools`): VaR dates on `drishti-var-load-*`, shard books on `drishti-var-shard-load-*`, exposure on
  `drishti-exposure-load-*`, bounded `ForkJoinPool`s that compensate a nested join; never the common pool.
- **A fixed reduction order** (`ParallelSums.sum`): a set under 1,024 rows in row order, a larger one in blocks aligned to the absolute
  ordinal (about 64 blocks, a power of two), each from zero, partials added in block order: any scope's bits depend on the set only, never on
  the thread count.
- **The skeleton** of a VaR date (ids, columns, links, aggregates; about 0.72 KB a position measured) is on the heap and copied to every
  member; its id indexes are sorted `int` permutations with a binary search (`IdIndex`). **Designed, not built:** memory-mapping it from
  hot-file format 4 (`TIDX`, `PIDX`, mapped `TCOL`, `PCOL`, `AGGS`), which takes an owner from `-Xmx36g` to about `-Xmx20g` at 2.3M and
  makes 10M practical.
- **Busy protection** (`AdmissionGate`, `com.ash.drishti.server.admission`) gates new sign-ins, date loads, exports, BI runs and bulk
  loads on the heap after collection, loads in flight, open dates' heap, requests in flight and free disk; reads of open dates are never
  gated.

### 28.13 Super scale (design, not built)

The target: 500 GB of JSON a day (about 50 million documents) kept seven years (about 88 billion rows, 115 TB of Parquet) in one Delta lake
in S3, read by many servers and consoles as one Drishti, each server's heap 8 GB. What breaks today at that volume: per-day id maps and
column sets on the heap (3 GB and 11.5 GB a day), grouped pages computed by every server, one table per source holding every year (350,000
files in one log), one loader per source (about 3 hours for 500 GB), servers on one lake as replicas that each cache everything. The design:

- **Products computed when loading, not when asked:** link indexes, group totals, rollups and id lists written with the data by the slice
  that has the rows sorted in hand.
- **Find by ranges, not maps:** files of a day hold sorted, disjoint id ranges; the log's per-file min and max id (and Parquet Bloom
  filters on `id`) give a range map, nothing per entity on the heap.
- **Tables by year** (`root: …/{year}`), closed when the year ends.
- **A loader fleet:** a planner cuts the day into slices; loader tasks parse, promote, sort, write and compute products; safe concurrent
  commits (conditional PUT); a publisher and a day register, so a day appears whole or not at all.
- **Each server owns a share:** (table, day, group) slices placed on the Drishti\*net ring; a request is forwarded to the slice's owner
  (one hop), searches and aggregates split over owners, so the cluster's memory adds up instead of repeating; anyone can still read
  anything from S3.
- **Warm tier:** memory-mapped column files of owned days; type-ahead from the day's id-list product.
- **Questions over all of history** go to an external engine (Athena, Trino, Spark) on the same tables.

Steps S1 (range maps, Bloom filters), S2 (tables by year), S3 (the loader fleet), S4 (products), S5 and S6 (slice ownership, split
queries), S7 (the warm tier) and S8 (a benchmark at scale in AWS) are not scheduled.

<!-- h-docs: BI, Python compute, client renderer, collaboration (builder D) -->

<!-- h-docs: other -->

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
