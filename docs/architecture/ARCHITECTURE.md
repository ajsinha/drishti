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
it is a retired example pack since 2.3.0):

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
- Pricing or risk calculation on the server — values (MTM, DV01, PFE) come from sources. A user may compute on a view in the browser with Calc ([PYTHON_CALC.md](../guides/PYTHON_CALC.md)); a server-side QuantLib service is proposed, not built ([QUANT_SERVICE.md](QUANT_SERVICE.md)).
- A free-form page designer. Screens stay Sutras; the Build workbench and the Sutra Studio edit them with a preview, and the BI report designer lays out report pages ([RUPAKA.md](RUPAKA.md)).

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
others. And BI's Python (Transforms, python visuals, Analyse's cells) runs in the **console's sandboxed runner**, which the
server's planner may call (`drishti.bi.transform.console-url`).

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
                                                                                          drishti.sources.fetch-timeout (5s)
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

**1. The user types `TRD MX-20000001` and presses Enter.** While typing, `command.js` asks the console for
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

**4. The page goes live.** The page does not open its own stream. `live.js` subscribes `view:trade/MX-20000001` on the
browser's single channel (`channel.js` → the hub in `live-hub.js`, a SharedWorker shared by every tab → console
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
paint) before relaying them; `live.js` replaces the strip cell's text and tone, swaps the panel, moves chart data in
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
| Read a new store (a database, a topic, a folder, a service) | A connector: a file `config/connectors/<name>.yaml` with `plugin:` and `settings:` (or Admin → Connectors); the pack names it in `routes:` for the kinds it serves, and may carry a `connectors:` template for it | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Add a domain (new kinds, commands, links) | A pack folder `config/packs/<name>/` with `pack.yaml`, `sutras/`, `samples/`; enable it with `DRISHTI_PACKS` | [PACKS.md](../guides/PACKS.md) |
| Change how one screen looks | Its Sutra (`*.sutra.yaml`), or a Design in the Build workbench, proposed and approved | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md), [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) |
| Change how a figure is formatted | `fmt:` in the Sutra; named formats in the pack's `config/formats.yaml` | RACHANA_REFERENCE.md |
| Change how inference reads field names | The pack's `config/semantics.yaml` | [INFERENCE.md](../architecture/INFERENCE.md) |
| Add a panel kind | The engine (`Binder` and the panel model) and a console renderer (`_macros/panels.html`) | [PANELS.md](../guides/PANELS.md), [DEVELOPER_GUIDE.md](../guides/DEVELOPER_GUIDE.md) |
| Decide who may see what | `roles:` in `pack.yaml` (kinds, `raw`), `drishti.security.redact` (fields shown as `•••`), the Admin pages | [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Add a command word | `mnemonics:` in `pack.yaml` (or under `drishti.commands` for a site) | PACKS.md |
| Add a link between kinds | `graph.fields` in `pack.yaml` and `link(...)` in the Sutra | PACKS.md |
| Change the sentence the About drawer says about a kind | `kinds.<kind>.about` in the pack's `config/about.yaml` (a `${...}` template over the document); for a generated pack, the about source in `tools/packgen/` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#about-text-and-glossary) |
| Explain a field (term, meaning, unit, sign) | `glossary.<field path>` in `about.yaml`, or a shared `vocabulary.<name>` entry in the lowest pack that owns the concept (the glossary layer and field hints are step 4 of [CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md); section 3.9 says what is in today) | PACK_DEVELOPER_GUIDE.md |
| Change how the drawer looks or behaves | `drishti-console/web/templates/_macros/about.html` and `terminal/_about.html`, `static/js/about.js`, `static/css/about.css` | [CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md) |
| Change what the explanation says about the layout and the data | `ExplainService`, `PageContext`, `EmptinessReason` (`drishti-engine`, package `explain`), `MatchTrace` (`SutraMatcher.explain`); the endpoint is `ExplainController`; limits under `drishti.explain.*` | [API_GUIDE.md](../guides/API_GUIDE.md), [CONFIGURATION.md](../admin/CONFIGURATION.md) |
| Decide who may share, comment or be mentioned | the role power `roles.<role>.collaborate` (on unless removed); `drishti.collab.packs.<pack>.share-enabled`; `mentionable-roles`, `directory.scope`, `share.max-recipients`, `limits.*` under `drishti.collab` | [CONFIGURATION.md](../admin/CONFIGURATION.md), [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) |
| Change what a note or comment may contain, or what is masked in it | `drishti.security.redact` (the fields), `drishti.collab.text.on-masked-copy` and `deny-patterns`; the logic is `NoteText`, `Entitlements.maskedValues`, `CommentRenderer` | CONFIGURATION.md, §3.10 |
| Switch on email, or change what it says | `spring.mail.*`, `drishti.collab.console-url`, `email.enabled`; per pack `packs.<pack>.email.content` (`comment`, `title`, `link-only`); templates in `drishti.collab.email.templates-dir`; the code is `EmailNotifier`, `OutboxDispatcher`, `MailRenderer` | [OPERATIONS.md](../admin/OPERATIONS.md#9a-2-collaboration-email-and-the-outbox) |
| Add another channel (a chat bridge) | one more `Notifier` bean: `ShareService` and `ThreadService` hand every bean the audience, so neither service changes | [COLLABORATION.md](../architecture/COLLABORATION.md) (phase 2) |
| Keep, hold or export what people wrote | `drishti.collab.retention.*` and `packs.<pack>.retention-days`; legal holds and the export through `/api/v1/admin/collab` (`CollabPurge`, `HoldService`, `ExportService`, `ChainVerifier`) | [OPERATIONS.md](../admin/OPERATIONS.md#9a-3-collaboration-retention-legal-holds-and-the-compliance-export) |
| Change how the Share dialog, the inbox or the Discussion tab look or behave | `drishti-console/web/templates/terminal/_share.html`, `inbox.html`, `_discussion.html`; `static/js/share.js`, `inbox.js`, `alerts.js`, `discussion*.js`; `static/css/collab.css` | [COLLABORATION.md](../architecture/COLLABORATION.md) (console design) |
| Let people search a kind by filling in a form | the `form:` section of `pack.yaml` (fields, types, operators), or a Design's Form tab; people open it with `FORM <pack>` | [PACK_DEVELOPER_GUIDE.md](../guides/PACK_DEVELOPER_GUIDE.md#form-the-search-form-of-a-kind), [USER_GUIDE.md](../guides/USER_GUIDE.md) |
| Let people choose what a panel shows | `controls:` in the Sutra (dropdowns, sliders, date ranges), read by panels as `ctl.<id>` | [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md#controls) |
| Tell Drishti a batch of data landed (refresh, verify, tell people, flag late data) | `POST /api/v1/packs/{pack}/loads` from your ETL; what a pack expects is its `loads:` section | [DATA_LOADS.md](../guides/DATA_LOADS.md) |
| Reach a source over TLS or through a proxy | `tls.*` and `proxy:` in the connector file | [TLS.md](../connectors/TLS.md), [TLS.md (proxy)](../connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| Make a dataset, report or board (business intelligence) | YAML files in `config/bi/` (watched) or the BI pages (workspaces, designer, Model view); the query runs on the server over DuckDB with the reader's masks and row rules | [BI_GUIDE.md](../guides/BI_GUIDE.md), [DATASETS.md](../guides/DATASETS.md), [REPORTS.md](../guides/REPORTS.md), [RUPAKA.md](../architecture/RUPAKA.md) |
| Show a view inside another web application | register the application in Admin → Embedding; the page uses `<drishti-view>` | [ELEMENTS.md](../architecture/ELEMENTS.md) |
| Use a language model, or an MCP tool server | a provider file in `config/ai/` or `config/mcp/` and `drishti.ai.enabled` / `drishti.mcp.enabled` (both off by default) | [AI_PROVIDERS.md](../architecture/AI_PROVIDERS.md), [MCP_CLIENT.md](../architecture/MCP_CLIENT.md) |
| Run several servers as one (Drishti\*net) | the files of `config/net/` (`node.yaml` with `enabled: true` and the cluster key, a seed in `peers/`), or Admin → Net → Files; members advertise a real address, never a loopback one | [NET.md](../admin/NET.md), [tutorial 19](../tutorials/19-a-cluster-of-three.md) |
| Lay a report out on paper (a statement, one per recipient) | `layout: statement` on a report page and the report's `statement:`; BI Studio's Paper tab | [STATEMENTS.md](../guides/REPORTS.md#24-statements-paginated-reports-page-exact-one-per-reader) |
| Make something that failed (a pack, a connector, a provider) try again | nothing: it is retried with backoff; Retry now in Admin | [OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself) |

---

## 6. Rachana — the screen grammar — and Sutras written in it

Rachana is the grammar; each Sutra is one YAML document written in it (ADR-017, which supersedes ADR-011's
Markdown Sutras). The first key, `rachana: 1`, is the language version, so the grammar can evolve without
misreading old files; `description`, `notes` and a per-panel `description` carry the prose. Files are named
`<name>.v<N>.sutra.yaml` and are found by scanning, recursively, the site directories (`drishti.rachana.dirs`, default `./sutras`) and every enabled
pack's `sutras/` folder (`config/packs/<pack>/sutras/<area>/…`). `SutraBuilder` validates each one at load against the panel
kinds' required and allowed options and reports problems with line and column (codes `DRS-2001`, `DRS-2002`; the
detailed checks are 2009–2012 and 2020–2039; the About files add 2040–2047, search forms 2060). Any other file in a Sutra folder (a `.sutra.md` from before 1.11, a plain `.yaml`)
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
by `ChartBinder` under the `drishti.panels` limits and drawn by `static/js/charts.js`; [PANELS.md](../guides/PANELS.md)
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
   browser   view.js (tabs, keys, line, area, surface) · charts.js (waterfall … graph, donut, map) · tables.js,
             tree-rows.js, pivot.js · live.js (frames from the live channel, same macros server-side)
```

What is code and what is configuration:

| Layer | Written once, used by every kind | Written per pack or kind |
|---|---|---|
| Server | `SutraBuilder`, `SutraMatcher`, `InferenceEngine`, `LayoutMerger`, `Binder`, `ChartBinder`, `PatchDiffer` | nothing |
| Console | `terminal/view.html`, `_macros/view.html`, `_macros/panels.html`, `api_routes._panel_html` (live patches) | nothing |
| Browser | `view.js`, `charts.js`, `geomap.js`, `tables.js`, `tree-rows.js`, `pivot.js`, `live.js` | nothing |
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
    <span class="pnl-code">DES · F2 … <a class="pnl-help" href="/help/panels#kv" …></a></span></header>
  <div class="pnl-b">
  <dl class="kv kv-4">
  <div class="kv-item"><dt>Trade id</dt><dd class="mono" data-path="$.TRADE_ID"><a class="lnk" href="/v/trade/MX-20000001">MX-20000001</a></dd></div>
  <div class="kv-item"><dt>Transaction</dt><dd class="mono" data-path="$.TRANSACTION_ID">20000001002</dd></div>
  <div class="kv-item"><dt>Version</dt><dd class="mono" data-path="$.TRADE_VERSION">2</dd></div> …
```

A field with a `link` becomes an anchor to `/v/{kind}/{id}`; `data-path` is what the About drawer, the live flash
and the export read. When a tick changes the panel, the console renders it again with the same `panel` macro
(`routes/api_routes.py`, `_panel_html`) and `live.js` swaps it in (section 10).

**Panel kind → macro → browser script.** One to one, for all twenty-two kinds of the `PanelKind` enum:

| `kind` | Macro in `_macros/panels.html` | Drawn or enhanced in the browser by |
|---|---|---|
| `kv`, `status` | `fields` | none (plain HTML) |
| `provenance`, `markdown`, `metric`, `gauge` | inline in `pbody` | none |
| `table`, `ladder` | `table` (through `pivotable`; `drill` when the Sutra has `levels:`) | `tables.js`, `colfilter.js`, `tree-rows.js`, `pivot.js` (the Pivot tab) |
| `tabs` | `tabs` (each tab's body is again `pbody`) | `view.js` |
| `line`, `area` | `chart` (`data-chart` JSON) | `view.js` (ECharts) |
| `hbar` | `bars`, or `donut` with `shape: donut` | none; `charts.js` for the donut |
| `surface` | `surface` (`data-surface` JSON) | `view.js` (heatmap or 3D) |
| `links` | `links` | none |
| `waterfall`, `histogram`, `scatter`, `candlestick`, `graph` | the macro of the same name (`xchart`, `data-xchart` JSON) | `charts.js` |
| `timeline` | `timeline` | none |
| `map` | `geomap` | `geomap.js`, `charts.js` |
| `pivot` | `pivot`, `pivot_table` | `tree-rows.js`, `pivot-grid.js` |

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

[INFERENCE.md](INFERENCE.md#from-an-inferred-layout-to-a-sutra) explains the rules and the step from here to a Sutra.

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
reference is [QUERY_BY_FORM.md](../guides/QUERY_BY_FORM.md).

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
**Table | Pivot** switch drawn by `pivot.js` from the `pivot` block above. A kind without `columns:` gets columns
from the condition and the documents; a kind without `pivot:` gets the table alone. The grammar is in
[USER_GUIDE.md, Search by value](../guides/USER_GUIDE.md#search-by-value) and
[Asking in plain words](../guides/USER_GUIDE.md#asking-in-plain-words).

### When code is needed

YAML covers new kinds, packs, screens, forms, searches and pivots. Code is needed only to give the grammar or the
platform a new capability, and then it serves every pack:

| Need | Code to write | Where |
|---|---|---|
| A new panel kind | the enum entry with its required and allowed options; its binding; its macro; its client script if it draws anything; its help entry | `PanelKind` (`drishti-rachana`), `Binder` or `ChartBinder` (`drishti-engine`, package `bind`), `drishti-console/web/templates/_macros/panels.html` (`pbody` and a macro), `drishti-console/web/static/js/view.js` or `charts.js`; [PANELS.md](../guides/PANELS.md), [DEVELOPER_GUIDE.md](../guides/DEVELOPER_GUIDE.md) |
| A new kind of store (a source plugin) | a `SourcePlugin` (`fetch`, optionally `subscribe`, `search`, `reverse`) in a plugin module; after that each store is a connector file | `SourcePlugin` (`drishti-api`), a module under `plugins/` such as `drishti-plugin-file`; [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#the-sourceplugin-spi) |
| A new computed measure | the computation: a Java engine (as VaR and exposure in `drishti-compute`, packages `var` and `ccr`) or a Python engine behind a `reactive` connector; its result is then an ordinary kind, drawn by the generic view | [REACTIVE_CONNECTOR.md](../connectors/REACTIVE_CONNECTOR.md), [VAR_GUIDE.md, Part III](../guides/VAR_GUIDE.md#24-where-the-engine-lives) |

### The hand-built pages and why

Some pages are hand-built. Each is a product feature that works across packs and kinds, built on the same server
APIs, entitlements and masks as the generic view; none is a screen for one product or one mnemonic.

| Page | Routes | Templates | Why it is its own page |
|---|---|---|---|
| VaR explorer | `/var` (`VAR <scope> <GO>`) | `terminal/var.html`, `static/js/var-explorer.js`, `var-charts.js` | Any scope (hierarchy node, book, basket, trade) computed on demand by the VaR engine (`/api/v1/var`), with drill-down, captures and compare; the computed kinds stay ordinary kinds on `/v/…` too. [VAR_GUIDE.md](../guides/VAR_GUIDE.md#3-the-explorer-screen-by-screen) |
| Exposure explorer | `/exposure` (`EXP <scope> <GO>`) | `terminal/exposure.html`, `static/js/exposure-explorer.js`, `exposure-charts.js` | The same for counterparty exposure (`/api/v1/exposure`); `exposure-result` is still a kind. [EXPOSURE_GUIDE.md](../guides/EXPOSURE_GUIDE.md#3-the-explorer-screen-by-screen) |
| BI Studio, report viewer and the BI pages | `/bi/…` | `templates/bi/` | Reports and models over datasets (DuckDB on the server), a different job from one entity's view. [BI_GUIDE.md](../guides/BI_GUIDE.md) |
| Admin | `/admin/…` | `templates/admin/` | Running the system: users, packs, connectors, lakes, Drishti\*net |
| Sutra designer and Build | `/designer`, `/build/…`, `/studio` | `templates/designer/`, `templates/build/`, `templates/studio/` | Tools that write Sutras; their previews are the generic view's macros. [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) |
| Data map | `/datamap` (`DATAMAP <GO>`) | `terminal/datamap.html`, `static/js/datamap.js` | The packs' kinds and joins, checked against the data (`/api/v1/datamap`). [USER_GUIDE.md](../guides/USER_GUIDE.md#the-data-map) |
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
- Navigation keeps a **breadcrumb trail per browser tab** (`view.js`); `Alt+←` goes back. Reverse links (netting
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
- **Console:** `command.js` debounces by 60 ms and cancels stale requests with `AbortController`. ↑/↓ move, `Tab`
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
- **Freshness.** The newest day updates as before (streams on arrival; tables on `refresh-seconds`). A data-load signal
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
                           console channel (ONE per browser tab) ──► live.js applies patches
```

- **Rebuild and diff.** Each rebuild uses the cached layout and is diffed against what the client holds; only
  changed strip cells, changed panels and the provenance travel. (Expressions also report their paths, so
  path-targeted re-binding is possible; measurement showed rebuild-and-diff is fast enough. See LIVE.md.)
- **Chart sources tick too.** A view also listens to the topics of the entities its `line` charts read from (the
  curve behind a swap).
- **Static views.** A picked business date, or a source that does not stream, gets one `view` event and the stream
  ends; no live slot is held.
- **Capacity.** `drishti.live.max-streams` (20,000) caps view and monitor streams together; the slot is taken
  atomically before any work. Over the cap the request is refused with `DRS-5001` ("too many live streams on this server").
- **One channel per browser.** Browsers allow six connections per site over HTTP/1.1, shared by all its tabs, so the
  number of live connections must not grow with tabs or panes (a channel per tab froze a seventh tab, UX-01). No page
  opens its own `EventSource`: views, the alerts bell, monitors and every workspace pane subscribe through
  `channel.js` to one hub per browser (`live-hub.js`, in a SharedWorker; without one, in a tab elected with a Web Lock
  that relays over a BroadcastChannel), which holds the browser's single channel
  (`/api/channel?s=view:trade/MX-20000001&s=alerts&s=monitor:<name>`, at most `live.max_subscriptions`, 32). A key
  held by several tabs is subscribed once; later subscriptions are added to the open channel with
  `POST /api/channel/{id}`. Frames are still built per signed-in user: the hub only serves tabs whose session
  fingerprint (`<meta name="drishti-live">`) matches the channel's (`who`). A tab hidden for 10 s gives its
  subscriptions back and repaints from fresh data when shown. Page loads never wait for the channel. The console
  closes each upstream server stream within seconds of the browser going away. Details in [LIVE.md](LIVE.md).
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
- **Shipped packs** (`config/packs/`, since 2.3.0): `core`, `market-data`, `trading` (production-shaped UPPER_SNAKE trades
  from ENDUR, CALYPSO and MUREX), `counterparty-risk`, `market-risk`, `world-economy` (real public data) and the sample
  `news-and-markets`; the non-financial ones prove the core is neutral. The retired ones (above) are examples and test fixtures.

`GET /api/v1/packs` lists the loaded packs; the console reads their console content from the same folders. See
[PACKS.md](../guides/PACKS.md) and ADR-010.

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
| `drishti-ai` | The LLM provider layer and MCP client, no Spring: `LlmGateway`, `LlmRegistry`, providers (`openai`, `anthropic`, `mock`), `LlmPolicy` (masking, limits, audit), `McpGateway`, `McpRegistry`; off by default ([AI_PROVIDERS.md](AI_PROVIDERS.md), [MCP_CLIENT.md](MCP_CLIENT.md)) | – |
| `drishti-diskcache` | `DiskCache`: a size-bounded, daily-cleared RocksDB cache on local disk for live connectors | – |
| `drishti-messaging` | `MessageStateSource`: the shared half of the ActiveMQ and RabbitMQ connectors (latest document per entity in a persistent RocksDB store plus a memory cache) | – |
| `drishti-lakeio` | Lake file access without Hadoop, shared by the native Delta and Iceberg engines: local disk (Windows included) and S3, byte ranges, Parquet page codecs, the Parquet footer cache | – |
| `drishti-iceberg` | Drishti's own Apache Iceberg engine without Hadoop (reads, writes, path-based and REST catalogs), used by the `iceberg` (engine `native`) and `iceberg-external` connectors ([DRISHTI_ICEBERG.md](DRISHTI_ICEBERG.md)) | – |
| `drishti-deltalake` | A Delta Kernel engine without Hadoop (local disk, Windows included, and S3), used by the Delta connector | – |
| `drishti-lakeload` | The parallel lake loader (2.9.6): JSON Lines (folder or S3) into Delta or Iceberg tables of the connectors' layout, every stage multi-threaded and memory bounded, one commit per business date; `drishti.py lake build --engine java` ([PARALLEL_LOADER.md](PARALLEL_LOADER.md)) | `drishti-lakeio`, `drishti-deltalake`, `drishti-iceberg` |
| `drishti-net` | Drishti*net's core, no Spring ([DRISHTI_NET.md](DRISHTI_NET.md)): the member table and SWIM-style gossip over HTTP (`MemberTable`, `Gossiper`, `PeersFile`), the cluster key and on-behalf-of tokens (`ClusterKey`, `OnBehalfOf`), the `config/net/` folder and its schema (`NetFolder`, `NetSchema`), signed HTTP between nodes (`PeerHttp`) | – |
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

Off by default ([DRISHTI_NET.md](DRISHTI_NET.md), [NET.md](../admin/NET.md)). With `config/net/node.yaml` switched on, a server is a **node**:
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
| Locks | `ReentrantLock` around anything that can block (file I/O, network, subscribes): Java 21 pinned a virtual thread blocked in `synchronized` to its carrier (Java 24 and later no longer do; the rule stays, so locks that may block remain explicit). `synchronized` remains only around short in-memory sections. Read-modify-write of shared records happens under the record's lock on the *current* value |
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
secrets in tracked files. The full key reference is [CONFIGURATION.md](../admin/CONFIGURATION.md).

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
| `POST` | `/packs/{pack}/loads` | the batch-landed signal ([DATA_LOADS.md](../guides/DATA_LOADS.md)) |
| `GET`/`PUT`/`POST` | `/admin/connectors…` · `/admin/packs…` · `/admin/registry…` | connectors as files, packs, deploy, the signed registry |
| `GET`/`POST` | `/threads…` · `/shares…` · `/me/inbox…` | discussion and sharing ([COLLABORATION.md](COLLABORATION.md)) |
| `GET`/`POST`/`PUT` | `/bi/…` | Rūpaka: workspaces, datasets, models and the model query, Drishti SQL, reports (format schemas, themes; boards are report pages), Python, governance |
| `GET`/`POST`/`PUT` | `/net/…` | Drishti\*net between nodes (signed with the cluster key: gossip, digest, fetch, search, group, subscribe, state) and for administrators (members, files, capabilities, access, security, explain, traffic) ([NET.md](../admin/NET.md)) |
| `POST` | `/ai/chat` · `/ai/mcp/{server}/tools/{tool}` | the AI and MCP layers (off by default) |
| `POST` | `/embed/token` and the `embed:` scopes | embedded views for other web applications ([ELEMENTS.md](ELEMENTS.md)) |

Errors are RFC 7807 `application/problem+json` with a `code` (`DRS-nnnn`) member, for example:

```json
{"type":"about:blank","title":"entity not found","status":404,
 "detail":"DRS-1001 no source holds trade/IRS-48213","instance":"/api/v1/views/trade/IRS-48213","code":"DRS-1001"}
```

### 16.1 Data formats on the wire: JSON by default, Arrow where it pays

Every answer is **JSON** unless a client asks otherwise. A client that sends `Accept: application/vnd.apache.arrow.stream` gets
**Arrow IPC** where the server offers it, cell for cell the same table, with the rest of the answer in the schema metadata. Anything
Arrow cannot carry falls back to JSON, and nothing changes for a client that does not ask. Where each format is used, and why
(measured 2026-10-30; [DATA_FORMATS.md section 6](../guides/DATA_FORMATS.md#6-arrow-to-the-browser-where-it-pays)):

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
| A figure per entity derived from its inputs | **Computed (derived) kinds**, computed on demand, never stored ([COMPUTED_ATTRIBUTES.md](COMPUTED_ATTRIBUTES.md)) | NSMTM, CPMTM, SSPNL, the Checks panels |
| Heavy or non-linear models per request | **Python compute workers**, Arrow in and out ([PYTHON_COMPUTE.md](PYTHON_COMPUTE.md)) | pricing, Monte Carlo, what-ifs |
| Aggregates for dashboards and reports | **The BI engine**: rollups and caches ([BI_DESIGN.md](BI_DESIGN.md)) | desk reports, live exposure reports |
| Large vectors per position, re-aggregated over any scope, interactively, beyond one server | **The hot file and sharding** ([VAR_SHARDING.md](VAR_SHARDING.md)) | VaR |

**The mechanism check.** A measure uses the hot file and sharding only if **all four** hold, answered with sizes at bank scale in its
design document before anything is built:

1. **Vectors per position:** each position carries a long array (scenarios, time steps, paths), not a handful of fields.
2. **Any scope, on demand:** people ask for any desk, book, basket or "bank minus X", not only fixed reports.
3. **Partials combine simply:** each server sums its part and the coordinator adds the parts before the final non-linear step (a quantile, a
   maximum, a percentile). When a group must be formed first (exposure's netting sets), the shard key keeps each group on one server.
4. **Too big for one server, or growing toward it,** and needed in under a second.

If all four hold, the measure plugs into the measure-agnostic machinery: it brings a `ShardedMeasure<P>` (its name, key order, bytes per
row, partial type and how two partials combine) and reuses the shard map, ownership, replicas, handover and scatter-gather
([VAR_SHARDING.md section 13](VAR_SHARDING.md#13-decisions)). If not, it uses one of the lighter mechanisms. Likely future users of the
heavy one: exposure paths (sharded by netting set), stress P&L across hundreds of scenarios, FRTB internal-model ES and DRC simulations,
climate scenario losses per position.

### 16.3 How memory-mapped data follows the lake

A hot file is a **copy** of lake data made for speed, so it must never be used after the lake has changed. Three things keep it honest:

1. **The data-load signal (the designed path).** A loader's last step announces "kind K landed for business date D"
   ([DATA_LOADS.md](../guides/DATA_LOADS.md)). Drishti drops the connectors' caches for that kind, and the engines that hold the kind react:
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
`refresh-seconds` (10 s) and BI on its next query; since 2.9.7 an **engine's open date** notices it too, through the **version watch**
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
(Bootstrap 5, Bootstrap Icons, ECharts, ECharts GL, CodeMirror, Vega and Vega-Lite, Graphic Walker, and Pyodide for Calc and the BI
Python console; vanilla JS, no build step); no CDN, no inline script
(strict CSP); static assets are fingerprinted.

**Themes** — tokens only in `web/static/css/tokens.css` (gradient chrome in `gradients.css`); ten themes selected by
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
| `/bi/explore`, `/bi/transform`, `/pyconsole` | Analyse (Slice, SQL and Python cells, worked out by the servers), the Transform editor (Python data prep step by step), the browser Python console on a masked extract |
| `/inbox`, `/share/{id}` | The inbox and the page a shared link opens |
| `/help`, `/help/{slug}`, `/help/search`, `/help/examples` | Help centre: guides and the repository's docs rendered in-app; F1 opens help for the current screen; the Rachana examples |
| `/about`, `/about/competitive` | Version and build, Java, uptime, loaded Sutras, source health, licence and notices; the competitive comparison |
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

**Hero animation** (`web/static/js/landing.js`, canvas 2D, no library): raw JSON fragments drift in, are drawn into
the `{ ◉ }` eye, and emerge as assembled terminal panels that then tick. Colours come from CSS tokens at runtime;
it plays once on view, has a Replay button, shows a static final frame under `prefers-reduced-motion`, and pauses
when hidden.

**Renderer contract** — each panel kind has a Jinja macro (`_macros/panels.html`) for first paint. Live patches
reuse it: the console renders a changed non-chart panel with the same macro and sends the HTML; `live.js` swaps that
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

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
