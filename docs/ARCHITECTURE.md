<!--
  Project Drishti -- Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Drishti — Architecture

*Revision 1.0 · 2026-09-30 · Author: Ashutosh Sinha*

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
declarative layout grammar (**Sutra**) to a data document, with an **inference
engine** filling whatever the grammar does not say. A brand-new product type with
no Sutra at all still renders a usable view on day one; a Sutra makes it beautiful.

The four reference mockups (`docs/requirements/drishti-*.png`) define the target:

| Mockup | Command | Sutra | Source |
|---|---|---|---|
| Interest rate swap | `TRD IRS-48213 <GO>` | `irs-vanilla v3` + inference | `aero-risk`, live |
| FX swap | `TRD FXS-20931 <GO>` | `fx-swap v2` + inference | `aero-fx`, live |
| Commodity future | `TRD CFT-77120 <GO>` | `listed-future v1` + inference | feed file `eod-futures` |
| Netting set | `NSET NS-NORTH-01 <GO>` | `netting-set v1` | `aero-risk` exposure |

### 1.1 Goals
1. **G1 One grammar** — every view in the product is a Sutra (explicit or inferred).
2. **G2 Any source** — data arrives through a plugin SPI (live cache, REST, JDBC, feed file, Kafka).
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
                │  Landing · Terminal · Views · Sutra Studio · Sources · Help · Admin │
                └───────────────────────────────┬─────────────────────────────────────┘
                                                │ HTTPS (HTML + SSE)
                                   ┌────────────▼────────────┐
                                   │  drishti console        │  Python · FastAPI · Jinja2
                                   │  (port 17480)           │  vendored Bootstrap/ECharts
                                   └────────────┬────────────┘
                                                │ REST + SSE (JSON ViewModel, patches)
                                   ┌────────────▼────────────┐
                                   │  drishti-server         │  Java 21 · Spring Boot 3.5
                                   │  (port 18480)           │  virtual threads
                                   │  ┌───────────────────┐  │
                                   │  │ drishti-engine    │  │  command → fetch → fingerprint
                                   │  │  sutra · infer ·  │  │  → layout → bind → link → view
                                   │  │  graph · live hub │  │
                                   │  └─────────┬─────────┘  │
                                   └────────────┼────────────┘
                                ServiceLoader SPI (isolated class loaders)
          ┌──────────────┬──────────────┬───────┴──────┬──────────────┬──────────────┐
          ▼              ▼              ▼              ▼              ▼              ▼
     aero cache      REST/HTTP        JDBC        feed files       Kafka          mock/demo
     (live)          services        (Postgres)   (CSV/JSON/Parquet) (ticks)      (sample data)
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
| **Mnemonic** | Command-line alias for a kind: `TRD`, `NSET`, `CPTY`, `CSA`, `AGR`, `CRV`, `BOOK`. Config driven. |
| **DataNode** | Immutable, source-neutral tree (object / array / scalar) holding the entity document. |
| **Source** | A plugin that can `fetch`, optionally `subscribe`, and describes its *generation* (monotonic version). |
| **Fingerprint** | Stable hash of a document's **shape** (paths + types, not values), e.g. `a91c…71e4`. Keys the layout cache. |
| **Sutra** | Versioned declarative layout grammar: header strip, panels, bindings, formats, keys, links. |
| **Inference** | Rule engine that derives panels from shape when a Sutra is absent or partial ("Sutra X + inference"). |
| **Layout** | Resolved, data-free plan = Sutra ⊕ inferred panels. Cached per `(sutra@version, fingerprint)`. |
| **ViewModel** | Layout bound to values: what the console renders. Serialisable JSON. |
| **Patch** | Minimal diff to a ViewModel (`path → value`), streamed over SSE when the source ticks. |
| **Link** | A reference from a field to another entity, resolved through the entity graph. |

---

## 4. The view pipeline

Every request, live or not, runs the same seven stages. Stages are pure functions
over immutable inputs so they are safe to run concurrently and cache aggressively.

```
 "TRD IRS-48213 <GO>"
        │
 ① Command    CommandParser → EntityRef(kind=trade, id=IRS-48213)            ~µs
 ② Fetch      SourceRouter  → DataNode + Provenance(source, generation)     I/O, virtual thread
 ③ Classify   Classifier    → product key ("irs-vanilla") via config rules  ~µs
 ④ Shape      Fingerprinter → shape hash (cached per generation)            ~µs–ms
 ⑤ Layout     LayoutResolver: cache[(sutra@v, fp)] else Sutra ⊕ Inference   cold ms, warm µs
 ⑥ Bind       Binder        → panels bound in parallel (ForkJoin/VT)        ~ms
 ⑦ Link       LinkResolver  → fan-out to referenced entities, time-boxed    I/O, partial-OK
        │
   ViewModel ──► REST response  /  initial SSE frame  ──► console renders
```

**Degradation rules.** Stage ⑦ has a per-view budget (default 40 ms). Links that
miss the budget render as `pending` and arrive later as patches. A failing panel
binding renders an inline error chip for that panel only; the view still renders.

---

## 5. Sutra — the layout grammar

Sutra files are YAML, live in `sutras/<domain>/<name>.v<N>.yaml`, are validated
against a JSON Schema at load, and hot-reload via `WatchService`. Bindings use a
small, compiled path/expression language (**Sutra-EL**) — not scripting.

```yaml
# sutras/rates/irs-vanilla.v3.yaml
sutra: irs-vanilla
version: 3
match:  { kind: trade, where: "productType == 'IRS' && legs.size() == 2" }
title:  { pill: "Trade · Interest rate swap", id: "$.tradeId", with: link($.counterparty) }
strip:                                   # header key figures (max 8)
  - { label: Notional (USD), bind: $.notional,  fmt: amount0 }
  - { label: Direction,      bind: "$.legs[?payer].fixed ? 'Pay fixed' : 'Receive fixed'" }
  - { label: MTM (USD),      bind: $.mtm,       fmt: signed0, emphasis: true }
  - { label: DV01 (USD),     bind: $.dv01,      fmt: signed0, tone: sign }
panels:
  - id: legs      key: F2   title: Legs     kind: tabs
    each: $.legs  tabTitle: "'Leg ' + #index + ' · ' + label"
    body: { kind: kv, columns: 4, infer: true }          # let inference lay out leg fields
  - id: cashflows key: F3   title: "Cashflows · Leg 1"  kind: table
    rows: $.legs[0].cashflows   total: [amount, pv]   inferColumns: true
  - id: curve     key: F4   title: USD-SOFR curve       kind: line  area: right
    source: link($.discountCurve)   x: tenor  y: rate   mark: "$.maturityTenor"
  - id: refs                title: Linked entities      kind: links area: right
  - id: dv01      title: DV01 by tenor (USD)  kind: hbar  area: right  rows: $.dv01ByTenor
keys:   { F7: link($.nettingSet), F8: impact, F9: raw }
```

**Panel kinds (v1):** `kv`, `table`, `tabs`, `line`, `area`, `hbar`, `ladder`,
`links`, `status`, `breadcrumb`, `provenance`, `markdown`. New kinds are added via
the `PanelKind` SPI on the Java side plus a Jinja macro + JS renderer on the console.

**Formats** (`fmt`) are named, config driven (`config/formats.yaml`): `amount0`,
`amount2`, `signed0`, `pct4`, `rate5`, `pips1`, `date`, `tenor`, `compact` (4.1m) …
**Tones** (`sign`, `threshold`, `status`) colour values through theme tokens only,
and always pair colour with a glyph or sign (WCAG, as MAYA requires).

Versioning: a view records `sutra@version`; old versions stay loadable so that a
saved link reproduces the same layout. Sutra validation errors carry codes `DRS-2nnn`.

---

## 6. Inference engine

Inference is a **scored rule system** over the document's shape tree — deterministic,
explainable, and cheap enough to run on the cold path only (results are cached by
fingerprint). Each rule proposes panel candidates with a score; a packer places the
winners into areas (`main`, `right`, `strip`) under density limits.

| Rule | Detects | Proposes |
|---|---|---|
| `ScalarStripRule` | top-level scalars with money/rate/date semantics | header strip entries (≤ 8, ranked by semantic weight) |
| `HomogeneousArrayRule` | array of objects with ≥ 80 % shared keys | `table`; numeric columns right-aligned; totals for amount-like columns |
| `TermStructureRule` | array keyed by tenor-like (`1M`,`5Y`,`Z6`,dates) with one numeric | `line` chart |
| `DistributionRule` | small map/array label → number | `hbar` |
| `ReferenceRule` | field matching an id pattern registered for a kind (`NS-*`, `CSA-*`) | entry in `links` panel |
| `NestedObjectRule` | object of scalars | `kv` panel, column count by field count |
| `LegsRule` | array of ≤ 4 structurally similar objects | `tabs` or side-by-side `kv` |
| `TimeSeriesRule` | array of `{date, value…}` sorted by date | `ladder` table + optional spark |

Semantic hints come from field names and value shape (`*notional*`, `*rate*`,
ISO dates, currency codes, tenor strings) and from config
(`config/inference/semantics.yaml`). Every inferred panel carries `origin: inferred`
and the rule name, surfaced in *How this view was built* and in Sutra Studio as
"promote to Sutra" suggestions.

---

## 7. Entity graph and navigation

- **Reference catalog** (`config/references.yaml`) maps id patterns and field names to
  kinds (`^NS-[A-Z]+-\d+$ → netting-set`, `discountCurve → curve`).
- `LinkResolver` builds the `Linked entities` panel: label, target, and a short
  **badge** summary pulled from the target (`EE 4.1m`, `threshold 0`, `live`).
- Navigation keeps a per-session **breadcrumb stack** (`← IRS-48213 / NS-NORTH-01`),
  `Alt+←` pops it. Reverse links (netting set → member trades) are answered by sources
  that implement `ReverseLookup`.

---

## 8. Live updates

```
 Source.subscribe ──► TopicHub (one single-writer per EntityRef)
                          │  coalesce ticks into 50 ms frames (latest-wins)
                          ▼
                     ViewMaintainer: re-bind only panels whose bindings touch changed paths
                          │  diff old vs new ViewModel fragment
                          ▼
                     Patch{path,value,gen} ─► SSE fan-out (per-subscriber bounded queue,
                                                drop-to-latest on slow consumers)
```

- The dependency index `path → panels` is computed once per Layout, so a curve tick
  re-binds the curve panel and the MTM strip cell, nothing else.
- The top-bar `Live, p99 38 ms` is a rolling HdrHistogram of source-tick → SSE-write
  latency, published at `/api/v1/health/live`.

---

## 9. Module layout (Java)

Maven multi-module reactor, `groupId com.ash.drishti`, Java 21, packages
`com.ash.drishti.<module>.<area>`, `package-info.java` everywhere.

| Module | Responsibility | Contributes |
|---|---|---|
| `drishti-bom` | Version alignment (imports the Spring Boot BOM) | – |
| `drishti-api` | Plugin SPI: `SourcePlugin`, `DataNode`, `EntityRef`, `PanelKind`, `InferenceRule`, `Formatter` (no Spring, so plugins stay light) | – |
| `drishti-common` | Error codes (`DRS-nnnn`), JSON, fingerprints, config records | `CommonConfiguration` |
| `drishti-sutra` | Sutra model, YAML parser, JSON-Schema validation, Sutra-EL compiler, registry + hot reload | `SutraConfiguration` |
| `drishti-inference` | Shape analysis, semantic hints, rules, scorer, packer | `InferenceConfiguration` |
| `drishti-graph` | Reference catalog, link resolution, reverse lookups | `GraphConfiguration` |
| `drishti-engine` | Pipeline, `LayoutResolver`, `Binder`, caches, `TopicHub`, `ViewMaintainer`, plugin discovery | `EngineConfiguration` |
| `drishti-server` | **The** Spring Boot application: `DrishtiApplication`, REST, SSE, security, actuator, OpenAPI | controllers, filters |
| `plugins/drishti-plugin-{demo,file,rest,jdbc,kafka,aero}` | Source plugins (ServiceLoader, isolated class loaders) | – |
| `drishti-testkit` | Fixtures, golden ViewModel assertions, `@DrishtiTest` slice | – |
| `drishti-it` | Integration, architecture (ArchUnit), licence-header and file-size tests | – |
| `drishti-benchmarks` | JMH benchmarks for fingerprint, bind, inference, patch | – |

**Naming** (Pravaha convention): `XxxController`, `XxxProperties` (records bound via
`@ConfigurationProperties`), DTO holders `ApiDtos`, `ApiExceptionHandler`,
`XxxRegistry` / `XxxCatalog` / `XxxFactory` instead of "Repository".

---

## 10. Concurrency and performance

| Concern | Approach |
|---|---|
| Request I/O (fetch, links) | Virtual threads (`Executors.newVirtualThreadPerTaskExecutor`), structured fan-out with deadline |
| CPU work (bind, inference) | Bounded `ForkJoinPool` sized to cores; panels bound in parallel |
| Live state | Single writer per `EntityRef` topic; lock-free MPSC queues (JCTools); no shared mutable maps on hot path |
| Caching | Caffeine: `shape(fingerprint)`, `layout(sutra@v,fp)`, `compiledEL(expr)`, `entity(ref,gen)` with size + TTL bounds |
| Allocation | Immutable records; path strings interned; ViewModel JSON streamed with Jackson `JsonGenerator` |
| Back-pressure | Bounded per-subscriber queues, latest-wins coalescing, never unbounded collections (ArchUnit rule) |
| Targets | warm view p99 < 50 ms, cold (first fingerprint) p99 < 150 ms, tick → screen p99 < 40 ms, 10k concurrent SSE subscribers per node |

---

## 11. Configuration (config driven everywhere)

Precedence (low → high), as in MAYA/Pravaha: `application.yaml` → `application.local.yaml`
(git-ignored) → environment → `--key=value`. `${VAR:default}` interpolation; no secrets
in tracked files.

| File | Owns |
|---|---|
| `drishti-server/src/main/resources/application.yaml` | server, `drishti.engine.*` (budgets, pools, cache sizes), `drishti.sources.*` |
| `config/mnemonics.yaml` | `TRD → trade`, `NSET → netting-set`, … |
| `config/classifiers.yaml` | kind + predicate → product key → default Sutra |
| `config/references.yaml` | id patterns and field names → kinds |
| `config/formats.yaml` | named number/date formats |
| `config/inference/*.yaml` | rule weights, semantic hints, density limits |
| `sutras/**` | layout grammar files |
| `console/config/application.yaml` | console port, server URL, theme default, feature flags |

---

## 12. REST and streaming API (`/api/v1`)

| Method | Path | Returns |
|---|---|---|
| `POST` | `/command` `{text}` | `EntityRef` or suggestions (fuzzy) |
| `GET` | `/views/{kind}/{id}` | `ViewModel` |
| `GET` | `/views/{kind}/{id}/stream` | SSE: `view` frame then `patch` frames |
| `GET` | `/entities/{kind}/{id}/raw` | source document + provenance (F9) |
| `GET` | `/sutras` · `/sutras/{name}/{version}` | registry listing / source |
| `POST` | `/sutras/preview` `{yaml, ref}` | ViewModel using an unsaved Sutra (Studio) |
| `GET` | `/sources` | plugins, health, generation |
| `GET` | `/health/live` | latency histogram summary |

Errors: RFC 7807 problem+json with `code: DRS-nnnn`. OpenAPI via springdoc.

---

## 13. Console (UX)

Python 3.12, FastAPI + Jinja2, laid out as MAYA's web layer: `routes/<area>.py`,
`templates/<area>/`, `_partials`, `_macros/`, `static/{css,js,img,vendor}`; all
libraries **vendored** (Bootstrap 5, Bootstrap Icons, ECharts, htmx-free vanilla JS);
no CDN, no inline script (strict CSP).

**Themes** — tokens only in `static/css/tokens.css`; four themes selected by
`data-theme` on `<html>`, persisted in `localStorage['drishti.theme']`:
`terminal` (default, the navy/amber of the mockups), `light` (parchment), `blue`,
`green`. Contrast is computed in CI (≥ 4.5:1 text, ≥ 3:1 non-text).

**Pages**

| Route | Page |
|---|---|
| `/` | Landing: hero animation, stats strip, "one grammar" section, capabilities, four mockup showcase, CTA |
| `/t` | Terminal home: command line, recent entities, watch list |
| `/v/{mnemonic}/{id}` | Entity view (server-rendered first paint, then SSE patches) |
| `/studio` | Sutra Studio: YAML editor + live preview against any entity |
| `/sources` | Source plugins, health, generations |
| `/help/*` | Markdown guides (Sutra reference, keyboard, inference rules) |

**Hero animation** (`static/js/landing.js`, canvas 2D, no library): raw JSON fragments
drift in from the left, are drawn into the `{ ◉ }` eye, and emerge on the right as
assembled terminal panels — a table, a curve, a strip — that then tick live. Colours
from CSS tokens at runtime; plays once on view (IntersectionObserver), Replay button,
static final frame under `prefers-reduced-motion`, pauses when hidden.

**Renderer contract** — each panel kind has a Jinja macro (`_macros/panels.html`) for
first paint and a JS renderer (`static/js/panels/<kind>.js`) that applies patches by
`data-path` attribute, so live updates never re-render a whole view.

---

## 14. Security

- OIDC / form login at the console; server trusts a signed session token (JWT) from the console.
- Entitlements per desk and per kind (`config/entitlements.yaml`); links to entities
  the user cannot see render as disabled with the reason (MAYA rule: *visible, not hidden*).
- Raw JSON (F9) honours field-level redaction rules.
- CSP without `unsafe-inline`; all assets same-origin.

---

## 15. Observability

Micrometer → Prometheus: per-stage pipeline timers, cache hit ratios, source latency,
SSE subscribers, dropped frames. Structured JSON logs with `viewId`, `ref`, `fingerprint`.

---

## 16. Quality rules

- **Every source file ≤ 1500 lines** (UX templates excepted) — enforced by `SourceFileSizeTest` and `console/tests/test_file_sizes.py`.
- **Copyright header on every file** — enforced by `LicenseHeaderTest` and Spotless.
- ArchUnit: controllers only in `drishti-server`; one-way module dependencies (api ← common ← sutra/inference/graph ← engine ← server); `drishti-api` free of Spring; no unbounded collections; no `Serializable`; no field injection.
- Golden ViewModel tests for the four reference entities.
- JaCoCo gates per module; JMH regression gates on the pipeline.

## 17. Key decisions

| # | Decision | Why |
|---|---|---|
| D1 | Backend is one Spring Boot application (never an embedded library); Python console | Single deployable, Spring wiring/config/observability everywhere; domain objects still testable without a context |
| D2 | Layout = Sutra ⊕ inference, cached by shape fingerprint | Unknown data renders immediately; cost paid once per shape |
| D3 | YAML Sutra with a compiled, side-effect-free EL | Reviewable, diffable, safe; no scripting in layouts |
| D4 | Server returns ViewModel, not HTML | Same model feeds console, API clients and golden tests |
| D5 | SSE (not WebSocket) for live | One-way, proxy-friendly, auto-reconnect with `Last-Event-ID` |
| D6 | Vendored front-end assets, no build pipeline | Air-gapped desks; MAYA/Pravaha practice |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
