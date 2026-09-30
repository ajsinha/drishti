<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti (दृष्टि, "sight") is a live, keyboard-driven terminal. It renders **any entity from any
system** as a dense, linked, ticking view: a swap, an FX trade, a futures position, a netting set.
It does not need a hand-coded screen per product:
- **Sutra**, a declarative layout grammar, describes the view.
- Where no Sutra exists, or a Sutra leaves gaps, an **inference engine** reads the shape of the data and fills them in.

```
TRD IRS-48213 <GO>        →  swap legs, cashflows, SOFR curve, DV01 ladder, links
NSET NS-NORTH-01 <GO>     →  exposure profile, member trades, CSA, collateral
```

As you type, the command line suggests mnemonics and entities in a dropdown, as the Bloomberg
terminal does.

## Status

Drishti is built in ten waves on `develop`. Each wave is merged to `main` when its build is green.

| Wave | Theme | State |
|---|---|---|
| 1 | Build foundation: Maven reactor, copyright/size/architecture gates, CI | ✅ done |
| 2 | Console shell, 5 themes (incl. *wallstreet*), landing page with hero animation | ✅ done |
| 3 | Data model, plugin SPI, `demo` + `file` sources, routing, fingerprints | ✅ done |
| 4 | Sutra grammar: parser with line/column errors, hot-reloading registry | ✅ done |
| 5 | Sutra-EL expressions, formats, Sutra matching; golden strips for all four mockups | ✅ done |
| 6 | Inference engine: rules, packing, Sutra ⊕ inference merge | ✅ done |
| 7 | View pipeline, entity links, command type-ahead service | ✅ done |
| 8 | REST API and console entity views (the four mockups end to end) | ⏳ next |
| 9 | Live updates over SSE, measured p99 | ◻️ |
| 10 | Sutra Studio, security, ops, v1.0.0 | ◻️ |

## What works today

- **Console** (`console/`). A landing page with an animated hero: raw JSON is drawn into the `{◉}`
  eye and comes out as live panels. It has five themes and a strict CSP. Every asset is vendored
  (no CDN), and the contrast of every theme is checked in tests.
- **Server** (`drishti-server`). A Spring Boot application on virtual threads. It discovers source
  plugins and routes reads by config, with deadlines. It loads and hot-reloads Sutras.
- **Data.** The `demo` plugin serves the 36 entities behind the four mockups. The `file` plugin
  serves JSON/CSV feed directories.
- **Grammar.** Four reference Sutras reproduce the header strips of the mockups exactly (golden
  tests). Sutra-EL is compiled once and shared across threads.
- **Inference.** An entity with no Sutra still gets a sensible layout: strip, tabs, tables, curves,
  bars, ladders, key/value panels and links. Each inferred panel records why it was chosen.
- **Views.** `ViewPipeline` builds the complete ViewModel of any entity: the title, strip, panels,
  linked entities with badges, function keys and provenance. Layouts are cached per data shape, and
  warm p99 is under 50 ms (a test gate). The golden tests reproduce all four mockups' values.
- **Type-ahead.** `SuggestionService` offers mnemonics, recents and entities as you type
  (`T` → `TRD`; `TRD IRS-4` → `IRS-47102`, `IRS-48213`, …), searching the sources in parallel within 30 ms.
- **Not yet:** the REST endpoints and console entity pages (Wave 8), and live ticking (Wave 9).

## Try it

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64      # OpenJDK 21 is required (enforced)
./mvnw -q verify                                         # all modules and tests
java -jar drishti-server/target/drishti-server-*-exec.jar # backend on :18480 (actuator at /actuator/health)

uv venv console/.venv && uv pip install --python console/.venv/bin/python -r console/requirements.txt
console/.venv/bin/python console/run_drishti_web.py      # console on http://localhost:17480
console/.venv/bin/python -m pytest -q console/tests
```

`tools/drill.sh` runs every check, then pushes `develop` and merges it into `main`.

## How it is built

| Layer | Technology |
|---|---|
| Backend | One Spring Boot 3.5 application on OpenJDK 21 and virtual threads. It is never an embedded library. |
| Modules | `api` (Spring-free plugin SPI) · `common` · `sutra` · `inference` · `graph` · `engine` · `server` |
| Plugins | ServiceLoader SPI; extra jars load in isolated class loaders |
| Console | Python, FastAPI and Jinja2; vendored Bootstrap, Bootstrap Icons and ECharts; no CDN; no inline script |

```
drishti/
├── drishti-api/                 plugin SPI, DataNode, EntityRef, HitIndex (no Spring)
├── drishti-common/              error codes, JSON codec, shape fingerprints
├── drishti-sutra/               Sutra model, parser, registry, Sutra-EL, formats
├── drishti-inference/           semantic hints, rules, packer, Sutra ⊕ inference merge
├── drishti-graph/               reference catalogue, link badges
├── drishti-engine/              sources, view pipeline, binder, ViewModel, commands, type-ahead
├── drishti-benchmarks/          JMH hot-path benchmarks
├── drishti-server/              the Spring Boot application
├── drishti-testkit/ drishti-it/ fixtures; architecture, licence-header and file-size gates
├── plugins/drishti-plugin-{demo,file}/
├── console/                     FastAPI + Jinja2 web UI (routes/, core/, web/templates, web/static)
├── sutras/<domain>/<name>.v<N>.yaml
├── tools/                       license_headers.py, drill.sh
└── docs/                        architecture, plan, references, ADRs
```

## Documentation

| Document | What it is |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | **The design.** Pipeline, Sutra, inference, type-ahead, live updates, modules, API, UX. |
| [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | **The waves.** W1–W10 with exit gates. |
| [SUTRA_REFERENCE.md](docs/SUTRA_REFERENCE.md) | **The layout grammar.** Keys, panel kinds, formats, Sutra-EL, problem codes. |
| [INFERENCE.md](docs/INFERENCE.md) | **Layouts from shape.** Rules, packing, merging, semantic hints. |
| [PLUGIN_GUIDE.md](docs/PLUGIN_GUIDE.md) | **Bringing data in.** The source SPI, routing and configuration. |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | **Measured numbers.** JMH hot paths and the end-to-end latency gate. |
| [adr/](docs/adr/README.md) | Architecture decision records. |
| [CHANGELOG.md](CHANGELOG.md) | What changed, wave by wave. |

## Contributing rules

- Work on `develop`. Waves merge to `main` through `tools/drill.sh`.
- No source file may exceed 1500 lines (UX templates excepted). Enforced by tests.
- Every file carries the copyright header (`python3 tools/license_headers.py --fix`). Enforced by tests.
- Modules depend in one direction only, controllers live only in the server, and the SPI is
  Spring-free. Enforced by ArchUnit.
- Front-end assets are vendored. A test rejects any external URL.

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
