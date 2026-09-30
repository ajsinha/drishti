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
- **Rachana** (रचना, *composition*) is a declarative screen grammar. Each product family
  gets a **Sutra**, a short versioned layout written in Rachana, instead of a coded screen.
- Where no Sutra exists, or a Sutra leaves gaps, an **inference engine** reads the shape of the data and fills them in.

```
TRD IRS-48213 <GO>        →  swap legs, cashflows, SOFR curve, DV01 ladder, links
NSET NS-NORTH-01 <GO>     →  exposure profile, member trades, CSA, collateral
```

As you type, the command line suggests mnemonics and entities in a dropdown, as the Bloomberg
terminal does.

## Status

**Drishti 1.0.0 is released** (see [RELEASE_NOTES.md](RELEASE_NOTES.md)). It was built in ten waves on `develop`, each merged to `main` when its build was green.

| Wave | Theme | State |
|---|---|---|
| 1 | Build foundation: Maven reactor, copyright/size/architecture gates, CI | ✅ done |
| 2 | Console shell, 5 themes (incl. *wallstreet*), landing page with hero animation | ✅ done |
| 3 | Data model, plugin SPI, `demo` + `file` sources, routing, fingerprints | ✅ done |
| 4 | Sutra grammar: parser with line/column errors, hot-reloading registry | ✅ done |
| 5 | Rachana-EL expressions, formats, Sutra matching; golden strips for all four mockups | ✅ done |
| 6 | Inference engine: rules, packing, Sutra ⊕ inference merge | ✅ done |
| 7 | View pipeline, entity links, command type-ahead service | ✅ done |
| 8 | REST API and console entity views (the four mockups end to end) | ✅ done |
| 9 | Live updates over SSE, measured p99 | ✅ done |
| 10 | Sutra Studio, security, ops, v1.0.0 | ✅ done |

## What works today

- **Console** (`console/`). A landing page with an animated hero: raw JSON is drawn into the `{◉}`
  eye and comes out as live panels. It has five themes and a strict CSP. Every asset is vendored
  (no CDN), and the contrast of every theme is checked in tests.
- **Server** (`drishti-server`). A Spring Boot application on virtual threads. It discovers source
  plugins and routes reads by config, with deadlines. It loads and hot-reloads Sutras.
- **Data.** The `demo` plugin serves the 36 entities behind the four mockups. The `file` plugin
  serves JSON/CSV feed directories.
- **Grammar.** Four reference Sutras reproduce the header strips of the mockups exactly (golden
  tests). Rachana-EL is compiled once and shared across threads.
- **Inference.** An entity with no Sutra still gets a sensible layout: strip, tabs, tables, curves,
  bars, ladders, key/value panels and links. Each inferred panel records why it was chosen.
- **Views.** `ViewPipeline` builds the complete ViewModel of any entity: the title, strip, panels,
  linked entities with badges, function keys and provenance. Layouts are cached per data shape, and
  warm p99 is under 50 ms (a test gate). The golden tests reproduce all four mockups' values.
- **Type-ahead.** `SuggestionService` offers mnemonics, recents and entities as you type
  (`T` → `TRD`; `TRD IRS-4` → `IRS-47102`, `IRS-48213`, …), searching the sources in parallel within 30 ms.
- **Terminal.** Open `/t` in the console, type `TRD IRS-48213 <GO>`, and the view renders like the
  mockup. Suggestions drop down as you type, F-keys jump between panels, F9 shows the raw JSON,
  links open other entities, and breadcrumbs lead back. REST API under `/api/v1` (OpenAPI at `/api/docs`).
- **Live.** Views of live entities tick over server-sent events: MTM, curves, exposure and settlements
  move in place, changed values flash, and the top bar shows the measured p99 (about 11 ms).
- **Sutra Studio.** At `/studio` you can edit a Sutra with highlighting, press Ctrl+Enter to preview it
  against any entity, see problems by line, and start a new Sutra from what inference makes of an entity.
- **Security.** Sign-in, per-role entitlements (denied links are shown disabled with the reason),
  raw JSON redaction, and signed tokens between the console and the server. It is off by default for
  local development.
- **Operations.** Prometheus metrics, a Grafana dashboard, Dockerfiles and compose, and runbooks.
- **Not built** (see the release notes): OIDC/SSO, the `aero` plugin, and F8 Impact.

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
├── drishti-rachana/             Rachana grammar: Sutra model, parser, registry, Rachana-EL, formats
├── drishti-inference/           semantic hints, rules, packer, Sutra ⊕ inference merge
├── drishti-graph/               reference catalogue, link badges
├── drishti-engine/              sources, view pipeline, binder, ViewModel, commands, type-ahead
├── drishti-benchmarks/          JMH hot-path benchmarks
├── drishti-server/              the Spring Boot application
├── drishti-testkit/ drishti-it/ fixtures; architecture, licence-header and file-size gates
├── plugins/drishti-plugin-{demo,file,rest,jdbc}/
├── console/                     FastAPI + Jinja2 web UI (routes/, core/, web/templates, web/static)
├── sutras/<domain>/<name>.v<N>.yaml
├── deploy/                      Dockerfiles, compose, Grafana dashboard
├── tools/                       license_headers.py, drill.sh
└── docs/                        architecture, plan, references, ADRs
```

## Documentation

| Document | What it is |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | **The design.** Pipeline, Sutra, inference, type-ahead, live updates, modules, API, UX. |
| [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | **The waves.** W1–W10 with exit gates. |
| [RACHANA_REFERENCE.md](docs/RACHANA_REFERENCE.md) | **The screen grammar.** Keys, panel kinds, formats, Rachana-EL, problem codes. |
| [INFERENCE.md](docs/INFERENCE.md) | **Layouts from shape.** Rules, packing, merging, semantic hints. |
| [PLUGIN_GUIDE.md](docs/PLUGIN_GUIDE.md) | **Bringing data in.** The source SPI, routing and configuration. |
| [USER_GUIDE.md](docs/USER_GUIDE.md) | **Using the terminal.** Commands, suggestions, keyboard, reading a view. |
| [API_GUIDE.md](docs/API_GUIDE.md) | **The REST API** and the ViewModel contract. |
| [CONFIGURATION.md](docs/CONFIGURATION.md) | **Every setting**, server and console. |
| [LIVE.md](docs/LIVE.md) | **Live updates.** Topics, frames, patches, slow clients, reconnects. |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | **Measured numbers.** JMH hot paths and the end-to-end latency gate. |
| [adr/](docs/adr/README.md) | Architecture decision records. |
| [OPERATIONS.md](docs/OPERATIONS.md) | **Running it.** Deploy, security checklist, monitoring, runbooks. |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Symptoms, causes and fixes. |
| [CHANGELOG.md](CHANGELOG.md) · [RELEASE_NOTES.md](RELEASE_NOTES.md) | What changed, wave by wave; what 1.0.0 is. |

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
