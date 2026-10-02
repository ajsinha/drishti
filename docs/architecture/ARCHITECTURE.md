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
the `finance` pack (`packs/finance/`), so they open only on a server that enables it (`DRISHTI_PACKS=finance`, the
default):

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
- Pricing or risk calculation — values (MTM, DV01, PFE) come from sources.
- A WYSIWYG layout designer (Sutra is text; a *Sutra Studio* previews it).

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
                                                │ REST + SSE under /api/v1 (JSON ViewModel, frames of patches)
                                                │ Authorization: Bearer <HS256 token minted per call>
                                   ┌────────────▼────────────┐
                                   │  drishti-server         │  Java 25 · Spring Boot 3.5
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
| **Source** | A plugin (`SourcePlugin`) that can `fetch`, and optionally `subscribe`, `search` and `reverse`; every document carries its *generation* (monotonic version). A **connector** is a named instance of a plugin with its own settings (`market-store` is a `delta` connector). |
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
                                                                                          drishti.sources.fetch-timeout (2s)
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

Notes on each stage, checked against the code:

- **Fetch order.** `SourceRouter` tries the plugin routed for the kind (`drishti.sources.routes`), then
  `default-route`, then every other plugin that serves the kind. For **Live**, plugins that stream come first (and a
  real stream before the `demo` samples); for a **picked business date**, dated plugins come first. The first one
  that holds the entity answers. Nobody serving the kind is `DRS-1002`; nobody holding it is `DRS-1001`; a source
  error is `DRS-1003`; running out of time is `DRS-1004`.
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
`murex-rates`); `SutraMatcher` chose `irs-fixfloat v1` from `packs/trading/sutras/rates/`; inference added what the
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

---

## 6. Rachana — the screen grammar — and Sutras written in it

Rachana is the grammar; each Sutra is one YAML document written in it (ADR-017, which supersedes ADR-011's
Markdown Sutras). The first key, `rachana: 1`, is the language version, so the grammar can evolve without
misreading old files; `description`, `notes` and a per-panel `description` carry the prose. Files are named
`<name>.v<N>.sutra.yaml` and are found by scanning, recursively, the site directories (`drishti.rachana.dirs`, default `./sutras`) and every enabled
pack's `sutras/` folder (`packs/<pack>/sutras/<area>/…`). `SutraBuilder` validates each one at load against the panel
kinds' required and allowed options and reports problems with line and column (codes `DRS-2001`, `DRS-2002`; the
detailed checks are 2009–2027). Any other file in a Sutra folder (a `.sutra.md` from before 1.11, a plain `.yaml`)
is reported as `DRS-2004` with the fix. Because the grammar is plain YAML, `RachanaSchema` generates a JSON Schema of
it from `PanelKind`, the formats and the served kinds, served at `GET /api/v1/rachana/schema`; Studio's editor and
any schema-aware editor complete and check Sutras with it. With `hot-reload` on, a `WatchService` reloads changed files (debounced 250 ms); an
invalid edit keeps the last good version and is listed at `GET /api/v1/sutras/problems`. Bindings use a small,
compiled, side-effect-free expression language (**Rachana-EL**) — not scripting.

A real Sutra, abbreviated (`packs/finance/sutras/rates/irs-vanilla.v3.sutra.yaml`, after its copyright comment):

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

**Panel kinds** (the `PanelKind` enum, twenty): `kv`, `table`, `tabs`, `line`, `area`, `hbar`, `ladder`, `links`,
`status`, `provenance`, `markdown`, `gauge`, `surface`, `waterfall`, `histogram`, `scatter`, `candlestick`, `graph`, `timeline`, `pivot`. The chart and aggregate kinds are bound
by `ChartBinder` under the `drishti.panels` limits and drawn by `static/js/charts.js`; [PANELS.md](../guides/PANELS.md)
describes every kind in depth. The full grammar is in
[RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md). A new kind is added to the enum (with its required and allowed
options) and to the binder on the Java side, plus a Jinja macro in `_macros/panels.html` and its client-side
handling on the console.

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
| `LegsRule` | 2–4 structurally similar objects with many fields (legs, tranches) | `tabs` of `kv` |
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
`GBLO`, `EUTA`, `JPTO`, or joint such as `USNY+GBLO`), rolling weekends and holidays back to the previous business
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

Named connectors (`drishti.sources.connectors`, or a pack's `connectors:`) give each domain its own lake
connector, e.g. `market-store` and `trading-store`. Lake retention, compaction and vacuum run outside the server
(`tools/lake/`, `deploy/lake-maintenance.yaml`; see OPERATIONS.md).

---

## 12. Domain packs

The core carries no industry. A **domain pack** (`packs/<name>/pack.yaml`) holds everything specific to one:
kinds, Sutras, mnemonics, identifier patterns, reference fields and link badges, roles, semantic hints and formats,
connectors, starter workspaces, help guides and sample data.

- **Loading.** `drishti.packs.enabled` (`DRISHTI_PACKS`, default `finance`) lists the packs, from
  `drishti.packs.dir` (`./packs`). Before any bean is built, `PackEnvironmentPostProcessor` loads them and adds
  their content as the **lowest-precedence** property source, so site configuration always wins. No core module
  depends on `drishti-packs`; they only read ordinary properties.
- **Inheritance (ADR-015).** A pack may say `extends: [parent, …]`. Enabling a pack loads its ancestors too: the
  running server enabled with
  `DRISHTI_PACKS=market-risk,counterparty-risk,…` also loads `banking-core`, `market-data` and `trading`. The order
  is a C3 linearisation (`PackLineage`, as Python orders classes): where related packs define the same mnemonic,
  field, badge, role, route or connector, the child wins over its parents and the rightmost parent over the others;
  every override is reported (`drishti.packs.overrides`). Unrelated packs defining the same thing stop start-up and
  name both packs, and a kind always belongs to exactly one pack.


  | Pack | `extends` |
  |---|---|
  | `banking-core`, `genomics`, `economics`, `politics-society`, `finance`, `logistics` | – |
  | `market-data`, `retail-banking`, `operational-risk` | `banking-core` |
  | `trading` | `banking-core`, `market-data` |
  | `market-risk`, `counterparty-risk` | `market-data`, `trading` |
  | `liquidity-risk`, `climate-risk` | `trading` |
- **Per-user packs.** Installed packs are what the server runs; an admin **assigns** packs to users (default
  `drishti.packs.default-for-users`), and each user chooses which assigned ones are **active**. A kind owned by an
  inactive pack cannot be opened (`PackAccess`).
- **Shipped packs** (`packs/`): `banking-core`, `market-data`, `trading`, `market-risk`, `counterparty-risk`,
  `liquidity-risk`, `climate-risk`, `operational-risk`, `retail-banking`, `finance` (the reference mockups),
  `logistics` (shipments, containers, vessels, ports), `genomics`, `politics-society`, `economics` — the
  non-financial ones prove the core is neutral.

`GET /api/v1/packs` lists the loaded packs; the console reads their console content from the same folders. See
[PACKS.md](../guides/PACKS.md) and ADR-010.

---

## 13. Module layout (Java)

Maven multi-module reactor on `spring-boot-starter-parent`, `groupId com.ash.drishti`, Java 25, packages
`com.ash.drishti.<module>…`, `package-info.java` everywhere.

| Module | Responsibility | Contributes |
|---|---|---|
| `drishti-api` | Plugin SPI: `SourcePlugin`, `PluginManifest`, `SourceCapabilities`, `DataNode`, `EntityRef`, `EntityDocument`, `Provenance`, `AsOf`, `HitIndex`, `Subscription` (no Spring, so plugins stay light) | – |
| `drishti-common` | Error codes (`ErrorCode`, `DRS-nnnn`), `DrishtiException`, JSON codec, `ShapeFingerprinter`, `BusinessCalendar` | `CommonConfiguration` |
| `drishti-rachana` | Sutra model, YAML parser and validator, the language's JSON Schema (`RachanaSchema`), Rachana-EL compiler, formats and tones, `SutraRegistry` + hot reload, `SutraMatcher` | `RachanaConfiguration` |
| `drishti-inference` | Shape analysis, semantic hints, rules, scorer, packer, `LayoutMerger` (Sutra ⊕ inference) | `InferenceConfiguration` |
| `drishti-graph` | `ReferenceCatalog` (identifier patterns, reference fields), badges | `GraphConfiguration` |
| `drishti-engine` | `ViewPipeline`, `Binder`, caches, `SourceRouter`, `SourceRegistry`, `PluginDiscovery`, `TopicHub`, `ViewStream`, `PatchDiffer`, suggestions, structured search, impact, business dates | `EngineConfiguration` |
| `drishti-identity` | Users, roles, passwords, lockout, audit, per-user preferences (workspaces, settings); stored in a JPA database (section 18) | `IdentityConfiguration` |
| `drishti-packs` | Domain packs: reads `packs/<name>/pack.yaml`, resolves inheritance, contributes lowest-precedence properties (an `EnvironmentPostProcessor`) | `PackRegistry` |
| `drishti-diskcache` | `DiskCache`: a size-bounded, daily-cleared RocksDB cache on local disk for live connectors | – |
| `drishti-messaging` | `MessageStateSource`: the shared half of the ActiveMQ and RabbitMQ connectors (latest document per entity in a persistent RocksDB store plus a memory cache) | – |
| `drishti-server` | **The** Spring Boot application: `DrishtiApplication`, REST controllers, SSE, security (`TokenFilter`, OIDC), governance, alerts, actuator, OpenAPI | controllers, filters |
| `plugins/drishti-plugin-*` | Source plugins: `demo`, `file`, `rest`, `jdbc`, `delta`, `aerospike`, `feeds`, `kafka`, `activemq`, `rabbitmq`, `s3` (ServiceLoader; all are on the server's class path, extra jars from `drishti.sources.plugin-dir` get their own class loader) | – |
| `drishti-testkit` | Shared contract tests for plugins (`DatedSourceContract`, `MessageSourceContract`, `BrokerOutage`) | – |
| `drishti-it` | Architecture (ArchUnit), licence-header and file-size tests | – |
| `drishti-benchmarks` | JMH benchmark of the hot path (`HotPathBenchmark`) | – |

**Naming** (Pravaha convention): `XxxController`, `XxxProperties` (records bound via `@ConfigurationProperties`),
DTO holders `ApiDtos`, `ApiExceptionHandler`, `XxxRegistry` / `XxxCatalog` instead of "Repository" (identity's
Spring Data repositories in `identity.db` are the exception).

---

## 14. Concurrency and performance

| Concern | Approach |
|---|---|
| Request I/O (fetch, links, search) | Spring MVC on virtual threads (`spring.threads.virtual.enabled`); the engine's `drishtiVirtualExecutor` runs one virtual thread per fetch, with a deadline (`orTimeout`) |
| CPU work (bind) | Bounded `ForkJoinPool` (`drishti.engine.bind-parallelism`, 0 = one per core); panels bound in parallel |
| Live state | One topic per `EntityRef`, one source subscription each (connected exactly once, even when subscribers arrive together); ticks land in a latest-wins slot and a single flush per frame delivers them; frames are timed by a small platform-thread scheduler (`drishti-frame-*`); one drainer per view stream, failed rebuilds retried with backoff |
| Caching | Caffeine in the engine: layouts (`layout-cache-size`, 10,000, keyed by Sutra version, kind and fingerprint), fingerprints (`fingerprint-cache-size`, 100,000, keyed by entity, generation and date), compiled expressions (`drishti.rachana.expression-cache-size`, 10,000). Connectors keep their own caches (Delta partitions by size, `cache-mb`; Kafka documents; RocksDB disk caches). The Delta plugin's loads that do I/O use async caches loading on virtual threads, so no lock is held during I/O. A Sutra change clears the layout cache. Admins see and purge every cache at **Admin → Caches** (`GET /api/v1/admin/caches`) |
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
| `packs/<name>/pack.yaml` | a pack's kinds, mnemonics, references, badges, roles, connectors, Sutra and sample folders |
| `packs/<name>/config/*.yaml` | the pack's formats, semantic hints, help and starter workspaces |
| `drishti-rachana/src/main/resources/formats.yaml` (+ `drishti.rachana.formats-file`) | core named number/date formats |
| `drishti-inference/src/main/resources/inference/semantics.yaml` | core semantic hints and density limits |
| `./sutras/**` (`drishti.rachana.dirs`) | site Sutras, in addition to the packs' |
| `console/config/application.yaml` | console host and port, server URL, default theme, sign-in and OIDC, help docs folder |

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

Errors are RFC 7807 `application/problem+json` with a `code` (`DRS-nnnn`) member, for example:

```json
{"type":"about:blank","title":"entity not found","status":404,
 "detail":"DRS-1001 no source holds trade/IRS-48213","instance":"/api/v1/views/trade/IRS-48213","code":"DRS-1001"}
```

---

## 17. Console (UX)

Python 3.12, FastAPI + Jinja2: `core/` (app, settings, auth, backend client, business date), `routes/<area>_routes.py`,
`web/templates/<area>/`, `web/templates/_macros/`, `web/static/{css,js,img,vendor}`. All libraries are **vendored**
(Bootstrap 5, Bootstrap Icons, ECharts, ECharts GL, CodeMirror; vanilla JS, no build step); no CDN, no inline script
(strict CSP); static assets are fingerprinted.

**Themes** — tokens only in `web/static/css/tokens.css` (gradient chrome in `gradients.css`); seven themes selected by
`data-theme` on `<html>`: `terminal` (default, the navy/amber of the mockups), `light` (parchment), `wallstreet`
(Bloomberg Terminal colours: black ground, amber data, yellow commands, green/red ticks, blue highlight — colours
only, not fonts), `blue`, `green`, `crimson` and `crimson-dark`. The choice is saved to the user's account and
mirrored in `localStorage['drishti.theme']` for first paint. Contrast is checked by `console/tests/test_contrast.py`
(≥ 4.5:1 text, ≥ 3:1 non-text).

**Pages**

| Route | Page |
|---|---|
| `/` | Landing: hero animation, stats strip, "one grammar" section, capabilities, mockup showcase |
| `/t` | Terminal home: command line, recent entities, watch list |
| `/go?q=` | Resolves a command and redirects to its view (or to search) |
| `/v/{kind}/{id}` | Entity view (server-rendered first paint, then live patches over the tab's channel) |
| `/s?q=` | Structured search results |
| `/compare/{kind}/{id}` | History: what changed in the entity between two business dates, or since a "known at" time |
| `/impact/{kind}/{id}` | F8 Impact: dependents by reverse lookup, rolled up along pack-configured fields, with summed measures |
| `/m`, `/m/{name}` | Monitors: live watchlists |
| `/alerts` | Alert rules (Rachana-EL, evaluated server-side on every tick, edge-triggered), pack suggestions, history; a bell and toasts on every page |
| `/w`, `/w/{name}` | Workspaces: 2–4 embedded views with follow-the-selection between panes, saved per user on the server |
| `/studio`, `/studio/reviews` | Sutra Studio (editor + live preview) and the review queue |
| `/help`, `/help/{slug}`, `/help/search` | Help centre: guides and the repository's docs rendered in-app; F1 opens help for the current screen |
| `/about` | Version and build, Java, uptime, loaded Sutras, source health, licence and notices |
| `/account` | Own profile, settings and password |
| `/admin/users`, `/admin/audit`, `/admin/health`, `/admin/caches` | User administration, audit log, health of everything, caches |
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
one panel, updates strip cells in place, and hands chart panels new data — so a tick never re-renders the whole view.

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
  (`drishti-it`) and `console/tests/test_assets_policy.py`.
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
