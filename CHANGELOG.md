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
# Changelog

## Unreleased — Wave 6: inference engine
- `drishti-inference`: config-driven `Semantics` (roles from field names and value classes, memoised), `ColumnInference`, six rules (legs → tabs, term structure → line/area, distribution → hbar, time series → ladder, arrays → table, nested objects → kv), and a packer with density limits.
- `LayoutMerger`: Sutra ⊕ inference, where the Sutra always wins. It produces an `EffectiveLayout` with the provenance label (`Sutra irs-vanilla v3 + inference` / `inference only`) and a per-panel explanation.
- Tests: an unknown product (equity option) renders; each reference entity stays usable without its Sutra; the gaps in the listed-future Sutra are filled.
- README rewritten to show the real state of the project: a status table per wave, what works, how to run it. It is now updated with every wave.
- Docs: `INFERENCE.md`.

## Unreleased — Wave 5: Sutra-EL, formats & matching
- Sutra-EL: lexer, recursive-descent parser (the EBNF is in `SUTRA_REFERENCE.md`), and immutable closure trees with cached compilation. Paths, filters, ternary, arithmetic, twelve pure functions, `link(...)`, and `${...}` templates.
- Every expression reports the document paths it reads, ready for dependency-driven live updates.
- Every expression in a Sutra is compiled at load; errors are reported against the file (`DRS-2101`).
- `Formats` (bundled `formats.yaml` plus a site override; true minus sign, grouping, percent, compact `4.1m`, dates) and `Tones`.
- `SutraMatcher`: highest-priority Sutra whose `where` holds; no match means inference only.
- Golden test: the four reference Sutras reproduce the mockups' header strips exactly. jqwik property tests check the arithmetic and comparisons.

## Unreleased — Wave 4: Sutra grammar
- `drishti-sutra`: immutable model (`Sutra`, `Match`, `Title`, `StripItem`, `Panel`, `Column`, twelve `PanelKind`s with per-kind required and optional options).
- A position-aware YAML reader and a validator that reports **every** problem with its line and column (`DRS-20xx`).
- `SutraRegistry`: `name@version` lookup, per-kind matching by priority, lock-free snapshot reads, `WatchService` hot reload with debounce, last good version kept on error, change listeners.
- `sutra.schema.json` for editors. The four reference Sutras for the mockups.
- Docs: `SUTRA_REFERENCE.md`.

## Unreleased — Wave 3: data model & sources
- `drishti-api`: `DataNode` (immutable tree; navigation never throws), `EntityRef`, `EntityDocument`, `Provenance`, and the `SourcePlugin` SPI with `search` (for the type-ahead) and `reverse`. `HitIndex` provides in-memory search.
- `drishti-common`: `DRS-nnnn` error codes, a streaming `JsonCodec`, and `ShapeFingerprinter` (canonical shape, FNV-1a 64; values and array lengths do not change it).
- `drishti-engine`: `PluginDiscovery` (class path plus isolated plugin jars), `SourceRegistry` (parallel start on virtual threads, failures isolated), and `SourceRouter` (config-driven routes, deadlines, partial `fetchAll`, parallel `search` under a time budget).
- Plugins: `demo` (36 reference entities) and `file` (JSON/CSV feed directory).
- The build is pinned to OpenJDK 21 by the enforcer.
- Docs: `PLUGIN_GUIDE.md`; ARCHITECTURE §7a (command suggestions).

## Unreleased — Wave 2: console shell & landing
- FastAPI + Jinja2 console (`console/`) with a layered config (YAML → local → env → CLI), a strict CSP and security headers.
- Five themes, tokens only in `tokens.css`: terminal (default), parchment, **wallstreet** (Bloomberg Terminal colour scheme), blue, green.
- Landing page: a canvas hero (JSON → `{◉}` → assembled, ticking panels) with replay, reduced motion and pause-when-hidden; stats strip; Sutra example; animated pipeline; capabilities; the four reference views.
- Every front-end asset is vendored (Bootstrap, Bootstrap Icons, ECharts). Tests fail on any external URL, inline script or inline handler.
- WCAG contrast is computed for every theme in tests.

## Unreleased — Wave 1: build foundation
- Maven reactor on `spring-boot-starter-parent` 3.5.16, Java 21, wrapper included.
- Modules: api, common, sutra, inference, graph, engine, two plugins (demo, file), server, testkit, it.
- The `DrishtiApplication` Spring Boot skeleton runs on virtual threads, with actuator and Prometheus.
- Repository gates: licence headers, 1500-line limit, architecture rules (ArchUnit).
- `tools/license_headers.py` to check and insert headers; GitHub Actions `fast.yml`.
- ADR-001 to ADR-007.
