<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti (दृष्टि, "sight") is a live, keyboard-driven terminal that renders **any entity
from any system** — a swap, an FX trade, a futures position, a netting set — as a dense,
linked, ticking view. It does not use a hand-coded screen per product. A declarative layout
grammar, **Sutra**, describes the view. Where no Sutra exists, or a Sutra leaves gaps,
an **inference engine** reads the shape of the data and fills them in.

```
TRD IRS-48213 <GO>        →  swap legs, cashflows, SOFR curve, DV01 ladder, links
NSET NS-NORTH-01 <GO>     →  exposure profile, member trades, CSA, collateral
```

## Status — read this first

This repository is at **design stage (Wave 1 pending)**. The architecture and plan are
written; code arrives wave by wave on `develop` and is merged to `main` as each wave
closes. Nothing below "What it will do" is built yet.

## What it will do

- **One grammar.** Every view is a Sutra (YAML), explicit or inferred, versioned and reproducible.
- **Any source.** Live caches, REST, JDBC, feed files and Kafka, all plugged in through a Java SPI.
- **Linked.** Every identifier is navigable; breadcrumbs and `Alt+←` take you back.
- **Live.** Views tick over SSE; the measured p99 latency is shown in the top bar.
- **Provenance.** Each view states which Sutra and version, which data fingerprint, and which source and generation built it.
- **Keyboard first.** A command line, F-keys per view, and `F9` raw JSON everywhere.

## How it is built

| Layer | Technology |
|---|---|
| Backend | One Spring Boot 3.5 application (Java 21, virtual threads) — never an embedded library. Modules: Sutra, inference, graph, pipeline, live hub; REST + SSE, springdoc, Micrometer |
| Plugins | ServiceLoader SPI with isolated class loaders |
| Console | Python 3.12, FastAPI + Jinja2, vendored Bootstrap / ECharts, no CDN |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

## Repository layout (target)

```
drishti/
├── drishti-api/ drishti-common/                  plugin SPI + shared utils
├── drishti-sutra/ drishti-inference/             grammar and inference
├── drishti-graph/ drishti-engine/                links and view pipeline
├── drishti-server/                               the Spring Boot application
├── drishti-testkit/ drishti-it/ drishti-benchmarks/
├── plugins/drishti-plugin-{demo,file,rest,jdbc,kafka,aero}/
├── console/                                      FastAPI + Jinja2 web UI
├── sutras/<domain>/<name>.v<N>.yaml              layout grammar files
├── config/                                       mnemonics, references, formats, inference
└── docs/                                         architecture, plan, guides, ADRs
```

## Documentation

| Document | What it is |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | **The design.** Pipeline, Sutra, inference, live, modules, API, UX |
| [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | **The waves.** W1–W10 with exit gates |
| [requirements/](docs/requirements/) | Reference mockups and brand assets |

## Building (from Wave 1)

```bash
./mvnw -q verify                       # engine, server, plugins, tests
python console/run_drishti_web.py      # console on http://localhost:17480
```

## Contributing rules

- Work on `develop`; waves merge to `main`.
- No source file over 1500 lines (UX templates excepted). This is enforced by tests.
- Every file carries the copyright header. This is enforced by tests.
- One-way module dependencies, controllers only in `drishti-server`, no unbounded collections. ArchUnit enforces this.

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification,
distribution or disclosure without the express prior written permission of the copyright
holder is prohibited. See [LICENSE](LICENSE).
