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
# Drishti Lake: the storage and loading layer as a product

**Status: design for the owner, written 2026-11-21. Nothing built, nothing run, no decision taken.** Read from git only: Drishti
`main` at `9ce7c3170`; `drishti-rachana` `docs/design/SPLIT_PLAN.md`; [I7B_PRODUCTS_PLAN](I7B_PRODUCTS_PLAN.md). Line counts are
`wc -l` over `src/main` and `src/test` on that commit. The Apache XTable facts are from its own site on this date (two pages,
section 8); nothing of XTable was run.

**The request.** Take Drishti's lake layer out into its own private repository `drishti-lake` (proprietary), usable by other
projects, Drishti its first customer; inside i7b on the Rachana pattern (built in its own repository while Drishti is read-only,
Drishti migrates in G4, one drill, the VaR and ES goldens prove the numbers). If it is not ready at the G3 gate it moves to i8.

## Contents

0. [The answer in one page](#0-the-answer-in-one-page) · 1. [Positioning](#1-positioning) · 2. [Scope](#2-scope-what-moves-what-stays) ·
3. [Public API and SPI](#3-public-api-and-plug-in-spi) · 4. [The parity TCK](#4-the-parity-tck-the-conformance-gate) ·
5. [Data contracts](#5-data-contracts-home) · 6. [Repository and coordinates](#6-repository-layout-and-coordinates) ·
7. [The move, step by step](#7-the-move-step-by-step) · 8. [Apache XTable](#8-apache-xtable-assessment) ·
9. [Drishti's migration (G4)](#9-drishtis-migration-g4) · 10. [Docs](#10-docs-plan) · 11. [Risks](#11-risks) ·
12. [Estimate](#12-estimate) · 13. [Decisions](#13-decisions-lk-1-to-lk-16)

## 0. The answer in one page

**Is it a simpler refactoring than Rachana? Yes in kind, no in size; three knots must be cut that the owner's picture does not
show.** About **64 bd** (74 with 15% contingency) against the Rachana split's 55, with less deep work in it.

| | Rachana split | Drishti Lake | Reading |
|---|---|---|---|
| Lines that move (main / test) | about 27,800 / 7,000 | about 40,000 / 18,500 Java, plus 7,700 / 4,000 Python | larger |
| Host types the code names | product exception, `DataNode`, Spring, Jackson, Caffeine, registry, binder | 25 types in the four lake modules (TLS and proxy settings, `FileDates`, `RowRetention`, `ConfigResolution`, `DomainVocabulary`, `SourceRegistry`, `DrishtiException`) | far fewer, all at the edges |
| Files outside that import it | about 170 | 15 main files (server 10, banking 4, packs 1) and about 13 test files | a tenth |
| Dependencies to remove | Spring, Jackson, Caffeine, own YAML and JSON written | none: delta-kernel, iceberg-core, Parquet, AWS SDK stay | no excision work |
| Semantics that can drift | a language, a binder, a second runtime (JavaScript) | none of its own: the formats are standards, proven by interop tests both ways | lower risk |
| Framework in the code | Spring | none: `ArchitectureRulesTest` already keeps `memlake`, `dataload`, `lakeio`, `lakeconfig` free of Spring and the server | already clean |
| Conformance suite | harvested in R0 before the split | does not exist as one thing: parity tests sit in four modules and `tools/lake` | **new work** |

**The three knots** (evidence in section 2.2):

1. **The lake configuration knows packs and modules.** `lakeconfig` (2,698 lines, inside `drishti-lakeio`) reads
   `ConfigResolution`, `ModuleTemplates`, `ModuleLoaders`, `DomainVocabulary`; 27 lake files name packs, 8 name source systems.
   It must be divided into a lake-file model (product) and a pack resolver (Drishti).
2. **The three connector plugins are two things in one.** `DeltaSourcePlugin` (1,530), `IcebergSourcePlugin` (1,231) and
   `IcebergExternalPlugin` (627) implement Drishti's `SourcePlugin` (entity documents, hit index, provenance): they are adapters
   and stay. The table layer beside them (`DeltaTable`, maintenance, layout, `IcebergLoader`, `SortedRuns`, `OrphanFiles`, about
   7,300 lines) is product code and moves. Today both live in one module with one test suite.
3. **The Python path leans on Drishti's tools.** `tools/lake` imports `resolved` (7 files), `javacmd` (9), `packlake` (6),
   `samplegen` (6), `demonames` (2), and holds demo and source-system code (`demo.py`, `stores.py` with `endur`, `calypso`,
   `murex`). About 90 Python files elsewhere in Drishti call into it.

Two smaller findings: the **files store is not in the lake modules** (the `file` connector in `drishti-connectors`, 2,291
lines), so "three stores, feature-identical" has no single home today; and the Delta connector still offers **two engines**
(`native`, `hadoop`, `EngineKind`), which the one-way rule and the product must settle (LK-9).

**Recommendation.** Do it in i7b as one extra lane (one Opus, one Sonnet, never more), start after G2's first wave, move first and
untangle in the new repository as Rachana did. Gate at G3 on four facts: the TCK is green on the moved code, results equal the
reference (Drishti's own jars at the same commit), a Drishti side branch on the pin passes the VaR and ES goldens, and the Python
path passes the same fixtures. Any of the four missing: i8, with nothing lost, because Drishti is untouched until G4.

**XTable: not in the core, not on any write path.** An optional `lake-convert` module after 1.0, run as its own process, behind
a small exposure SPI that 1.0 reserves (section 8).

## 1. Positioning

Drishti Lake is **a loading-and-table layer over the official libraries: rows in as JSONL under a contract, standards-compliant
tables out, the same behaviour in Delta, Iceberg, files and memory, proven by a kit anyone can run.** It is not a format, not a
query engine, and it prices nothing.

| | What it is | What it does not do that Drishti Lake does | Relation |
|---|---|---|---|
| **Apache XTable** (0.4.0-incubating) | translates table metadata between Hudi, Delta, Iceberg, Paimon, Parquet; no data rewritten | no loading, no reading, no contracts, no retention or undo, no memory store; copy-on-write views only | optional exposure back-end (section 8) |
| **DuckDB** | an in-process SQL engine that reads Delta, Iceberg and Parquet | not a governed writer: no load rules, no `.ready` hand-over, no parity between stores, no inbox | a reader of our tables; the TCK may use it as one more outside reader |
| **Polars** | a DataFrame engine; Delta through delta-rs | a library, not a loading contract; no Iceberg parity, no rules for name and date | the Python path's fast writer when installed (owner 11-16) |
| **delta-rs** | the Rust and Python Delta library | Delta only; reads column-mapped tables as nulls and no deletion vectors or v2 checkpoints at 1.6.6 (found 11-08) | the Python path's Delta writer; the interop oracle |
| **delta-kernel, iceberg-core** | the official protocol libraries | they are the protocol, not a product: no loader, no configuration, no cross-format behaviour | **what every byte goes through** |

**What a second customer gets:** one lake file per lake (tables, partitions, promoted columns, retention); a concurrent Java
loader and one Python path with identical results; an inbox with `.ready` hand-over, undo, delete-by-date, relocation,
maintenance; the same on local disk, S3 and `mem://`; a contract check before anything is written; a kit that proves a new
storage back-end or store behaves.

## 2. Scope: what moves, what stays

### 2.1 The table

| Today in Drishti | Lines main / test | Goes to | Notes |
|---|---|---|---|
| `lake/drishti-lakeio`, package `lakeio` (storage, S3, ranges, Parquet footers, codecs, `StorageProvider(s)`) | 2,224 / part of 1,889 | `lake-io` | TLS and proxy settings become the product's own records |
| `lake/drishti-lakeio`, package `lakeconfig`: `Lake`, `LakeTable`, `LakeCheck`, the lake-file half of `Lakes` and `LakeFiles` | about 1,700 of 2,698 | `lake-config` | the model and its checks |
| the same package: `ModuleConnectors`, `PackEntry`, `RemovedKinds`, `ConnectorLake`, the pack and module half of `Lakes`, `LakeFiles` | about 1,000 | **stays** (Drishti `drishti-lake-host`) | behind `LakeCatalog` (3.2) |
| `lake/drishti-deltalake` | 2,797 / 725 | `lake-delta` | on delta-kernel 4.4.0 |
| `lake/drishti-iceberg` | 6,360 / 746 | `lake-iceberg` | on iceberg-core 1.10.1, no Hadoop at run time |
| `lake/drishti-lakeload`, package `lakeload` (the loader, archives, retention, undo, relocate) | 10,189 | `lake-load` | `FileDates`, `RowRetention`, `TempFiles` come with it from `drishti-common` |
| the same module, `dataload` (inbox, `.ready`, ledger, `ReadyRule`) | 5,939 | `lake-load` (`inbox` package) | `ReadyRule` is already an interface the banking module implements |
| the same module, `memlake`, `memlake.store` | 3,252 | `lake-memory` | `ReplenishSource` and `Retention`'s use of `SourceRegistry` go behind `RowSource` (3.2); the two classes' host half stays |
| `plugins/drishti-plugin-delta`: `DeltaTable`, `NativeLake`, `LakeStore`, `NamedTables`, `NewTables`, `TableProblems`, `VectorBatches` | about 1,700 of 3,600 | `lake-delta` | |
| the same: `DeltaSourcePlugin`, `MappedIdMap`, `DeltaLakeFiles`, `EngineKind`, `HadoopLake` | about 1,900 | **stays** | adapter; `HadoopLake` per LK-9 |
| `plugins/drishti-plugin-iceberg`: maintenance, layout, `IcebergLoader`, `IcebergAppender`, `SortedRuns`, `OrphanFiles`, `KindTable`, vectors | about 4,000 of 5,529 | `lake-iceberg` | `IcebergDiskCaches` waits on `DiskCache` (LK-10) |
| the same: `IcebergSourcePlugin`, `MappedIdMap` | about 1,500 | **stays** | adapter |
| `plugins/drishti-plugin-iceberg-external`: `ExternalTable`, `Shape`, `KindConfig` | about 800 of 1,596 | `lake-iceberg` | `IcebergExternalPlugin`, `StarterSchemas` stay |
| the files store (`plugin.file` in `drishti-connectors`, 2,291; its retention and delete already in `lakeload.LakeRetention`; its writers in Python tools) | about 1,200 | `lake-files` | read and write of `<root>/<domain>/<date>/<kind>.jsonl` in one place; the `SourcePlugin` half stays |
| `tools/lake` (Python) | about 6,900 of 9,804 / 4,586 | `python/drishti_lake` | `demo.py`, `stores.py`, `feedroute.py`, `feedfiles.py`, `stage.py`, `publish.py` (2,886) stay: demo, feeds, source systems |
| `tools/contracts.py` (1,306): the contract reader, checker, page generator | about 800 | `lake-contracts` and `drishti_lake.contracts` | section 5 |
| `drishti-testkit`: `s3.*`, `LoaderRows`, `FakeProxy`, `TlsFixture` | small | `lake-tck` | `DatedSourceContract` (36 uses) tests `SourcePlugin`: stays |
| tests of everything that moves | about 18,500 | with their code, or into the TCK | moved or converted, never copied |

**Totals.** Moves: about **40,000 main and 18,500 test lines of Java, 7,700 main and 4,000 test of Python**. Stays as adapters:
about 6,400 Java (three `SourcePlugin`s, the pack resolver, two host halves of the memory lake) and 2,900 Python.

**Stays in Drishti, whole:** `tools/mrloader` (domain); every `*.contract.yaml` (143 files in 20 pack folders) and
`docs/contracts`; `config/lakes/*.yaml` (Drishti's lakes); server wiring (`server.dataload`, `server.memlake`, `server.lakes`,
`server.flight`); `VarReadyRule`, `SourceSystemReadyRule`; `DomainVocabulary`; demo lake builds.

### 2.2 Evidence for the three knots

| Knot | Measured |
|---|---|
| Configuration | `lakeio` module imports `api.resolution.{ConfigResolution, ModuleTemplates}`, `common.ModuleLoaders`, `kernel.api.vocabulary.DomainVocabulary`; `Lakes` walks live module class loaders for lakes inside `.dmod` jars; 20 `"drishti.*"` setting names, 8 `DRISHTI_*` variables and 20 `System.getProperty`/`getenv` calls in lake main code |
| Plugins | outside the lake, the types most imported are Iceberg's (`Catalog` 15, `Table` 14, `Catalogs` 13, `Expr` 10, `FileTask` 9): the plugins reach deep into the engine, so the engine's surface is today its whole package tree |
| Python | `LOADER = "com.ash.drishti.plugin.iceberg.IcebergLoader"` in `stores.py`; the loader is found "in the server's exec jar" (`build.py`); `lakeconfig.py` asks `resolved.py`, which runs Drishti's Java resolver |

History: 451 of Drishti's 5,551 commits touch the moving paths.

## 3. Public API and plug-in SPI

### 3.1 Java: the facade (`com.ash.lake`, module `lake-api` + the implementing modules)

```java
LakeRuntime rt = LakeRuntime.builder()
        .storage(new LocalStorageProvider(), new S3StorageProvider(s3), new MemoryStorageProvider(caps))
        .store(DeltaStore.create(), IcebergStore.create(), FilesStore.create())
        .contracts(ContractChecker.standard())            // optional
        .options(LakeOptions.builder().tempDir(abs).parallelism(24).build())
        .build();                                         // per instance: no static registry, no ServiceLoader, no system property

Lake lake = rt.open(LakeFile.read(Path.of("/abs/config/lakes/risklake.yaml")));   // or LakeFile.builder()
LakeTable t = lake.table("risk.pnl");

try (RowScan rows = t.scan().asOf(date).columns("BOOK", "PNL").where(Expr.eq("DESK", "FX")).open()) { ... }
LoadReport r  = lake.loader().load(LoadRequest.of(input).table("risk.pnl").date(date).mode(REPLACE_DATE));
lake.inbox(inboxDir).rules(readyRules).cycle();           // .ready hand-over
t.maintain(MaintainPlan.defaults());  t.deleteDates(dates);  t.undo(loadId);  t.relocate(newRoot);
```

Rules of the surface: packages `com.ash.lake`, `.spi`, `.config`, `.load`, `.inbox`, `.contract` are public; everything else is
`internal` and closed by the Java module boundary from the first tag. **No Delta Kernel or Iceberg type appears in a public
signature** (rows as `RowView` and Arrow batches, schema as the product's `Columns`), so a library upgrade is never a customer's
break. Errors are `LakeException` with `LK-` codes; the host maps them once at its edge (Drishti: to `DRS-`). Paths are absolute
or anchored to a named root the caller passes (`explicit-paths`); the resolved path of every table is readable (`t.location()`).
The runtime reads configuration and never writes a configuration file (`connectors-read-only`).

### 3.2 The SPI (`com.ash.lake.spi`)

| SPI | Who implements | Shape | From today's |
|---|---|---|---|
| **`StorageProvider`** | a storage back-end (Azure, GCS, a vendor's object store) | `scheme()`, `storage(path)`, `memoryOnly()`; `Storage` = list, status, ranged read, create, `createIfAbsent`, delete | `lakeio.StorageProvider`, `Storage` (unchanged in shape; the static `StorageProviders` becomes per-runtime) |
| **`TableStore`** | a table format | open, create, scan, a `TableTarget` for the loader, maintain, delete dates, undo, relocate, the declared capability list | `lakeload.TableTarget` (today package-private, with `DeltaTarget`, `IcebergTarget`) plus the readers |
| **`InputFormat`** | a loader input | recognise by suffix, yield rows or archive members | `Inputs`, `Archives`, `TarStream`, `GzipMembers` (`.jsonl .gz .zst .zip .tar.gz`) |
| **`ReadyRule`** | the host | may this hand-over load now; `Facts` in, a verdict out | `dataload.ReadyRule` as is |
| **`ContractChecker`** | the host or the product's standard one | rows and a table declaration in, problems out, before anything is written | new seam; the standard checker from `tools/contracts.py` |
| **`LakeCatalog`** | the host | name → `LakeFile` (Drishti: its packs, modules, site overrides) | the pack half of `lakeconfig` |
| **`RowSource`** | the host | rows for a memory table's replenish | `memlake.ReplenishSource` over `SourceRegistry` |
| **`Exposure`** (reserved, 1.x) | `lake-convert` | publish a table's current state in another format | section 8 |

JSONL stays the one row format in (`jsonl-only`); `InputFormat` is about containers and compression, not about a second row
format. There is **no loader-engine SPI**: one Java loader (concurrent, the default) and one Python path (LK-7).

### 3.3 The Python path (`drishti_lake`, a wheel built from `python/`)

`drishti_lake.build`, `.maintain`, `.delete`, `.undo`, `.relocate`, `.scan`, `.contracts`, and the command `lake` (the present
`tools/lake/cli.py` verbs). One mechanism: the plain writer, Polars when it is installed, byte-identical on the 75-case fixture
(owner 11-16). It reaches the Java loader and Delta Kernel reads through the product's own runnable jar (`lake-cli`), named by an
explicit `--jar` or `DRISHTI_LAKE_JAR`, never "the server's jar". Configuration is a lake-file path or a `LakeCatalog` command
the host names; the package does not import the host. Drishti's `tools/drishti.py lake …` stays the operator's command and calls
the package (`python-only-commands`).

## 4. The parity TCK: the conformance gate

**One kit, `lake-tck`, published as a test-jar and inside the wheel; a release is not tagged unless it is green.**

| Part | Content |
|---|---|
| **Cases as data** | `tck/cases/<area>/*.yaml`: a lake file, input files (JSONL and the archives), the operation, the expected rows, columns, partition folders, or the expected refusal by `LK-` code. Harvested from `ChildLoadsTest`, `DeleteDatesParityTest`, `RetentionParityTest`, `IcebergParityTest`, `SourceShapeParityTest`, `test_delta_interop.py`, `test_child_parity.py`, the 75-case loader fixture |
| **The matrix** | every case × store (Delta, Iceberg, files, memory) × storage (local, S3 through the mock, `mem://`) × loader (Java, Python). The answer must be equal across the row |
| **Areas** | list columns, child tables, promoted columns, vectors, flatten, partitions, latest-by, load modes, name and date rules, `.ready` keys, retention (dates and rows), delete dates, undo, relocate, maintenance, special characters in names and values, concurrent writers (two loaders, one table: one wins, none corrupts), start on few cores |
| **Interop, both ways** | Delta: delta-rs and Delta Kernel read ours; we read theirs; the Spark escaping oracle. Iceberg: Apache's own reader and writer against ours, format versions 1 to 3 |
| **Capabilities** | a store that cannot do a thing declares it in `capabilities.yaml` with the reason (files: no time travel). The list is reviewed and **only shrinks**; an undeclared difference is a failure, never a skip |
| **Gates** | coverage (every lake-file key, every `.ready` key, every `LK-` code has a case); additive-only (the previous minor's cases pass unchanged); no case skipped for a missing tool (a missing oracle fails the run) |
| **For a third party** | `lake tck run --storage <provider jar>` or `--store <jar>` writes the same results file; equality with the reference results is the certificate |

**The split's own proof** uses it: at every step the kit's results over the moved code equal, file for file, the results of the
same cases run through a bridge over Drishti's jars built from the freeze commit (Rachana's `compare_results.py` pattern).

## 5. Data contracts' home

| Thing | Home | Why |
|---|---|---|
| The contract **schema** (keys, types, the 16 sections), the reader, the checker (rows against a contract; a contract against a table), the page generator | **Drishti Lake** (`lake-contracts`, `drishti_lake.contracts`) | a second customer needs "the shape of what may be loaded" as much as the loader |
| The contract **files** (143 `*.contract.yaml`), the generated pages, `docs/contracts/*.md` (computed measures) | **Drishti**, with their packs | they describe a domain |
| Host keys in a contract (`mnemonic`, `pack`, `class`, `binding-hint`) | declared by the host as a **contract profile** (extra keys the Core reader accepts and hands back untouched) | no file changes; the product names no host concept |
| Where the check runs | once, in the loader, before the first write, through `ContractChecker`; `mrloader` keeps its domain link checks and calls the same checker for shape | one way per job |

## 6. Repository layout and coordinates

```
drishti-lake/                       private, proprietary; main + integration branches as drishti-rachana
  java/  lake-api  lake-io  lake-config  lake-delta  lake-iceberg  lake-files  lake-memory
         lake-load  lake-contracts  lake-cli  lake-tck        (lake-convert: after 1.0)
  python/drishti_lake/              the wheel; tests beside it
  tck/cases/  tck/capabilities.yaml  tck/results/             cases as data, reference results per release
  tools/split/                      path allow-lists, compare_results.py, known-differences.txt
  tools/release/                    tag, build, install to ~/.m2, wheel, checksums
  docs/  README.md  CHANGELOG.md  LICENSE  java/SPLIT_EXEMPTIONS.md
```

| | Recommended | Note |
|---|---|---|
| groupId | **`com.ash.lake`** | beside `com.ash.rachana` already in `~/.m2/repository/com/ash` |
| artifactIds | `lake-api`, `lake-io`, … (no `drishti-` prefix) | as `rachana-core` |
| Java package | `com.ash.lake` (+ `.spi`, `.config`, `.load`, `.inbox`, `.contract`, `.internal..`) | renamed in one commit |
| Python | distribution `drishti-lake`, import `drishti_lake` | |
| Java level | 21 | Drishti's production level |
| Version | `0.9.0` at the move, `0.10.x` while untangling, **`1.0.0`** when the G3 conditions hold | semantic versioning on the public packages |
| Third-party versions | pinned in the product's parent POM (delta-kernel 4.4.0, iceberg 1.10.1, Parquet 1.18.0 at the move) | Drishti stops managing them and inherits through the BOM `lake-bom` |
| Dependency allow-list | `lake-api`: the JDK only. `lake-io`: Parquet, AWS SDK, codecs. `lake-delta`: delta-kernel. `lake-iceberg`: iceberg-core. No Spring, no host | a guard |

## 7. The move, step by step

Method as Rachana's re-order of 11-19: **move first, untangle in the new repository; Drishti is not modified.** Every step ends
green on its check and can be paused after it. Grades: O Opus, S Sonnet, H Haiku, F Fable.

| # | Step | Check | Grade | bd |
|---|---|---|---|---|
| L0 | Owner decisions LK-1 to LK-16; the path allow-list (`tools/split/lake-paths.txt`) reviewed file by file; the freeze commit named | the list reviewed; a size and private-data scan of the listed paths (no real Endur value, no file over 1 MB) | F | 1 |
| L1 | Repository bootstrap: reactor, headers, licence, the guards of 7.1 in report mode, CI entry point, release script skeleton | the guards run; an empty build | S | 2 |
| L2 | **Bridge and first cases**: a results-file runner over Drishti's own jars at the freeze commit; harvest the parity and interop tests into `tck/cases` (first 60%) | the bridge's results are stable over two runs | O (case format), S (harvest) | 5 |
| L3 | **Move with history**: `git filter-repo` on a fresh clone at the freeze commit, the allow-list only; merged with `--allow-unrelated-histories` (no force push); package rename in one commit; host types the code still names parked in `com.ash.lake.hostcompat`, each listed in `SPLIT_EXEMPTIONS.md` with the step that removes it; tag `v0.9.0` | unit tests moved and green; TCK results equal the bridge's; "names no host" in report mode shows only the exemptions | S, O reviews | 2 |
| L4 | **Own settings and errors**: TLS and proxy records, `LakeException` and `LK-` codes, `LakeOptions` replacing the 20 system properties and 8 variables; `FileDates`, `RowRetention`, `TempFiles` taken in | results equal; `StartOnFewCoresTest` moved and green | S | 3 |
| L5 | **Configuration divided**: lake-file model in `lake-config`; `LakeCatalog` SPI; the pack and module half written down for Drishti's adapter (not built here) | results equal with a test catalog; every lake file under `config/lakes*` reads to the same model as the reference | **O** | 4 |
| L6 | **Per-instance runtime**: `LakeRuntime`; `StorageProviders` and the memory stores off statics; two runtimes in one JVM proven apart | results equal; `TwoRuntimesTest`; the few-core start-up test | **O** | 3 |
| L7 | **Table layer out of the plugins** into `lake-delta` and `lake-iceberg`; `TableStore` SPI over `TableTarget` and the readers; no library type in a public signature | results equal; the moved plugin tests; API surface dump reviewed | **O** | 6 |
| L8 | **Files store** as a `TableStore` in `lake-files`; the memory lake behind `RowSource` | the capability list written; files and memory rows of the matrix green | O | 3 |
| L9 | **TCK complete**: remaining areas, the matrix, capabilities, coverage and additive gates, concurrent-writer cases, the third-party runner | the kit green on all four stores and three storages | O (design), S (cases) | 5 |
| L10 | **Python package**: `drishti_lake` cut from `resolved`, `javacmd`, `packlake`, `samplegen`; `lake-cli` jar; the wheel; the Python rows of the matrix | TCK Python rows equal the Java rows; the 75-case fixture byte-identical | S, O for the cut | 5 |
| L11 | **Contracts**: reader, checker, generator, the profile seam; `ContractChecker` wired into the loader | every one of Drishti's 143 contracts reads identically and its generated page is byte-identical (run from a Drishti checkout, read-only) | S | 3 |
| L12 | Public API review against three consumers (Drishti, the CLI, a 50-line sample project in `examples/`); `module-info`; API dump frozen | the sample project builds from `~/.m2` only | F reviews, S | 2 |
| L13 | Docs (section 10) | link and stale checks | S, H | 3 |
| L14 | Release tooling; `v1.0.0` candidate installed to `~/.m2` (decision R-1, as Rachana's pin) and the wheel built; **G3 evidence**: a Drishti side branch `wip/i7b-lake` on the pin with the goldens run | section 9's checks on the side branch | S | 2.5 |
| | **In the product's repository** | | | **49.5** |

### 7.1 Guards

| In `drishti-lake` | Fails when |
|---|---|
| Names no host | a file contains `com.ash.drishti`, `DRS-`, a `drishti.` setting, or a listed product word (pack, mnemonic, module, source-system names); "Drishti Lake" allowed by exact phrase |
| Dependency allow-list | a module has a dependency outside section 6's list |
| Official libraries only | a class parses or writes a Delta log entry, an Iceberg metadata or manifest file, or a Parquet footer by hand (pattern scan; the cached-footer wrapper is the one listed exception) |
| Public surface | a public type outside the exported packages; a Kernel or Iceberg type in a public signature; the API dump changed without a recorded approval |
| Explicit paths | a relative path reaches `Storage` |
| Writes no configuration | any write under a configuration root |
| Exemptions only shrink | `SPLIT_EXEMPTIONS.md` grew; at `v1.0.0` it is empty |
| TCK gates | coverage, additive-only, undeclared difference, a skipped case |
| File cap, licence header | a file over the shared 2,500-line constant; a missing header |

| In Drishti (from G4) | Fails when |
|---|---|
| Public API only | a source imports `com.ash.lake.internal..` or reflects into it |
| No second copy | a class named like a moved one; `io.delta..`, `org.apache.iceberg..`, `org.apache.parquet..` imported anywhere but the three adapters (extends today's `ArchitectureRulesTest` rules) |
| Pin is one fact | the Maven property `drishti.lake.version`, the wheel's version and the recorded checksums disagree |
| Adapter only adapts | the TCK fails when run inside Drishti with Drishti's `LakeCatalog` and storage wiring |

## 8. Apache XTable: assessment

| Question | Finding | Source |
|---|---|---|
| Maturity | `0.4.0-incubating`, released 2026-08-25; still an Apache incubating project; pre-1.0 API | release page |
| What it does | translates **metadata only** between formats over the same Parquet files; incremental sync with fall-back to full; catalog sync to HMS and Glue | features page |
| Delta without Spark | new in 0.4.0: a Delta Kernel source and target; the older ones need `delta-core` and a Spark session | release page |
| Gaps | **deletion vectors and merge-on-read are not carried** (copy-on-write views only); Delta generated columns not synced; Delta → Iceberg may need field ids through column mapping; Parquet partition columns that exist only in folder names fail | features page |
| Concurrency | **not stated on the pages read.** By construction a sync is a second, later commit to the target's metadata: the target lags the source, the two are never atomic, and two syncs of one table need outside serialisation. To be measured in the spike, not assumed | inference |
| Fit with our rules | **Good:** writes to each format go through that format's own library, which is our rule. **Against:** a table then holds two metadata trees, so our retention, undo, delete-dates, relocation and orphan clean-up must each trigger or follow a sync, and "Iceberg" via XTable is not our Iceberg store: parity (vectors, child tables, promoted columns, row retention) is not given by translation. Its Hadoop and Hudi dependency weight on a Windows machine with no `.exe` is unmeasured |

**Recommendation (LK-12).** Do not use XTable inside the core and never on a write path: each store keeps writing through
delta-kernel or iceberg-core, and storage parity stays the property of our own stores. Offer it as **`lake-convert`, optional,
after 1.0, in its own process** (`lake expose --as iceberg <table>`), for the one job it does well: letting an outside engine
read a Delta table as Iceberg (or the reverse) without a copy. Conditions before it ships: (a) a two-day spike on the Delta
Kernel path, no Spark, on Linux and Windows; (b) TCK "exposure" cases (the other format's official reader returns exactly the
source's rows after load, delete-dates, undo and maintenance); (c) tables with deletion vectors are refused by name; (d) one
exposure writer per table, serialised by the product. 1.0 reserves only the `Exposure` SPI and the case area. The existing copy
conversion (Delta → Iceberg through the Iceberg loader) remains the way to **move** a table; exposure is the way to **show** one.

## 9. Drishti's migration (G4)

One sitting, other builders off the lake modules, on the i7b integration branch; Drishti `main` moves only after the drill.

| # | Step | Check | Grade | bd |
|---|---|---|---|---|
| D1 | **Pin**: `drishti.lake.version` in the parent POM; `lake-bom` imported; the jars from `~/.m2` (R-1); the wheel in the console's environment; Drishti's own delta-kernel, Iceberg and Parquet version properties deleted | `mvn clean` build resolves offline | S | 0.5 |
| D2 | **Delete** `lake/*` (four modules), the moved classes of the three plugins and of `plugin.file`, the moved `tools/lake` files, the moved half of `tools/contracts.py`, the moved testkit classes | the build fails only where an import must change | S | 1 |
| D3 | **Imports**: a scripted, re-runnable rewrite `com.ash.drishti.{lakeio,lakeconfig,lakeload,dataload,memlake,deltalake,iceberg}` → `com.ash.lake..` over the 15 main and about 13 test files, the three adapters, the POMs | compiles | S | 1.5 |
| D4 | **Adapters**: `drishti-lake-host` (`LakeCatalog` over packs, modules and site overrides; `RowSource` over `SourceRegistry`; the `LK-` → `DRS-` map; TLS and proxy settings handed in; the contract profile); the three `SourcePlugin`s on the public API; the server's wiring builds one `LakeRuntime` | module tests; `DatedSourceContract` over all connectors; `StartOnFewCoresTest` | **O** | 4 |
| D5 | **Python**: `tools/drishti.py lake …` and the about 90 callers on `drishti_lake`; `demo.py`, `stores.py`, feeds and `mrloader` on the package | the affected Python tests; `mr_run` sample day | S | 2 |
| D6 | **Guards** of 7.1 on, failing; `ArchitectureRulesTest` rules restated on the new names | the guards | S | 1 |
| D7 | **Docs** (section 10): `DATA_LAKE_GUIDE`, `DATA_ARCHITECTURE` section 3, `CONFIGURATION.md` regenerated, CLI reference, README counts, CHANGELOG | docs checks | S, H | 1.5 |
| D8 | **Proof**: demo lakes rebuilt from test data with the pinned loader; row counts and `compare_lakes` equal to the pre-migration build; **VaR and ES goldens** (`VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `PackagedVarGoldenTest`) exact; the TCK inside Drishti; delta-rs reads the result | all exact; any difference stops the migration | O | 2 |
| D9 | The **one drill** of G4 (shared with the Rachana and Elements migration), then the tag | green drill | lead | 1 |
| | **In Drishti** | | | **14.5** |

Drishti's net effect: about 40,000 main and 18,500 test lines of Java and about 12,000 of Python leave; about 1,200 lines of
adapter are new.

## 10. Docs plan

| Document | Where | Content |
|---|---|---|
| `README.md` | product | what it is, the positioning table, a ten-line start in Java and in Python, the stores and storages, the TCK badge line |
| `docs/GUIDE.md` | product | one guide: the lake file every key, loading (inbox, `lake build`, the Java loader), `.ready` every key, name and date rules, retention, undo, delete, relocate, maintenance, memory store. Moved from `DATA_LAKE_GUIDE.md` sections 1 to 5 and 13 with the host words removed |
| `docs/SPI.md` | product | each SPI with a worked back-end; how to run the TCK on it |
| `docs/TCK.md` | product | the matrix, the case format, capabilities, the gates |
| `docs/CONTRACTS.md` | product | the contract schema and the checker |
| `docs/COMPLIANCE.md` | product | what comes from which official library; the interop evidence per release |
| `CHANGELOG.md` | product | per release; the only place for history |
| `DATA_LAKE_GUIDE.md`, `DATA_ARCHITECTURE.md` | Drishti | keep what is Drishti's (its lakes, packs, Admin pages, connectors); link to the product for the rest; no repeated text (`docs-lean`) |
| `CONFIGURATION.md`, CLI reference, README | Drishti | regenerated |

## 11. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| A loaded table differs after the move | wrong or missing rows; a VaR number moves | results equal the bridge at every step; D8 before the drill; no behaviour change rides along |
| The lane takes slots from Rachana and Elements in a release already at about 1,260 bd | G2 or G3 slips | two builders at most, started after G2's first wave; the G3 escape to i8 costs nothing because Drishti is untouched |
| The public API is cut around today's plugins and leaks library types | every Kernel or Iceberg upgrade breaks the customer | L7 and L12; the surface guard; three consumers before `1.0.0` |
| Per-instance runtime disturbs start-up (two fixes for few-core machines landed this week) | a hang returns | `StartOnFewCoresTest` moves with the code and runs at L4, L6, D4 |
| The Python cut breaks operator commands | loads fail on a QA machine | D5 runs every caller; the command surface of `drishti.py lake` is frozen and compared |
| Files store parity is thinner than believed once it sits in the matrix | L8 and L9 grow | the capability list makes each gap visible and declared; gaps are defects scheduled, not hidden |
| Two repositories slow a one-line loader fix | a release and a pin move per fix | scripted release in minutes; the pin is one property and one wheel |
| Private data in history | a leak into a repository that other projects will read | allow-list reviewed (L0); scan of the filtered clone for product words, real sample values and large files before the first push |
| XTable pulled in early | Hadoop weight, two metadata trees, false parity | LK-12: optional, later, own process |
| The Polars engine (designed; not in `tools/lake` at the freeze commit) is built in Drishti meanwhile | merge pain in `tools/lake` | freeze `tools/lake` and `lake/*` on Drishti from the freeze commit, or re-run the filter and replay (the allow-list is scripted) |

## 12. Estimate

| Lane | Steps | bd | Model |
|---|---|---|---|
| Design and gates | L0, L12 review, the G3 inspection | 3 | Fable |
| Deep: configuration, runtime, table layer, stores, TCK design, adapters, proof | L2 (format), L5 to L9, L10 (the cut), D4, D8 | 31 | Opus |
| Mechanical: bootstrap, move, settings, harvest, Python package, contracts, release, imports, guards | L1, L3, L4, L2 and L9 (cases), L10, L11, L14, D1 to D3, D5, D6 | 25 | Sonnet |
| Repetitive: headers, link fixes, doc moves | parts of L13, D7 | 5 | Haiku |
| **Total** (the split by model is approximate) | | **64** | |
| With 15% contingency | | **about 74** | |
| `lake-convert` (XTable), not in i7b | spike 2 + build 4 | 6 | Opus |

Calendar: a serial chain moves 4 to 5 bd a day (I7B_PRODUCTS_PLAN 4.2); L3 → L4 → L5 → L6 → L7 is serial, the TCK, Python and
contracts lanes run beside it. About **10 to 12 working days** for the product with two builders, then 3 inside G4.

## 13. Decisions (LK-1 to LK-16)

| # | Decision | Recommendation |
|---|---|---|
| LK-1 | In i7b, or straight to i8 | **In i7b as a two-builder lane**, started after G2's first wave; G3 decides on the four facts of section 0 |
| LK-2 | Name and coordinates | **Drishti Lake**; repository `drishti-lake`; groupId `com.ash.lake`; package `com.ash.lake`; Python `drishti_lake` |
| LK-3 | Order of work | **Move first, untangle in the new repository** (`hostcompat` and exemptions that only shrink), as Rachana; Drishti read-only until G4 |
| LK-4 | The connector plugins | **Divide them**: the table layer moves, the three `SourcePlugin` classes stay in Drishti as adapters on the public API |
| LK-5 | The lake configuration | **Divide it**: the lake-file model is the product's; packs, modules and site overrides reach it through `LakeCatalog`, implemented in Drishti |
| LK-6 | Registries | **Per `LakeRuntime` instance**, registered explicitly; no static registry, no `ServiceLoader`, no system property in the core (the CLI's `main` registers the built-ins) |
| LK-7 | Loader engines | **No engine SPI.** One Java loader (default) and one Python path (Polars if installed), both under the TCK; a third way is refused |
| LK-8 | The files store | **Moves and becomes a full `TableStore`**; its gaps go into the capability list and are closed or declared before `1.0.0` |
| LK-9 | The Delta `hadoop` engine (`EngineKind`, `HadoopLake`) | **Not in the product.** Azure and GCS arrive as `StorageProvider`s. Until those exist it stays in Drishti's adapter as a listed exception with an end date; owner to confirm nobody depends on `abfs://`, `gs://` or HDFS today |
| LK-10 | The memory lake and the disk cache | **Memory lake moves** (`lake-memory`), the host supplies rows through `RowSource`. `DiskCache` (used by one Iceberg class) stays in Drishti behind a small cache hook until native maths settles its home |
| LK-11 | Contracts | **Schema, reader, checker, generator move; the 143 files and the measure documents stay** with their packs; host keys through a contract profile; no file edited |
| LK-12 | Apache XTable | **Optional `lake-convert` after 1.0, own process, never a write path**; 1.0 reserves the `Exposure` SPI and the TCK area only |
| LK-13 | Library types in the API | **None.** Rows, columns and expressions are the product's own types; Kernel and Iceberg stay `internal` |
| LK-14 | Conformance | **The TCK is the release gate** and the split's proof; capabilities declared and shrinking; a missing oracle fails |
| LK-15 | The pin | **Jars in `~/.m2` and a wheel, one version property** (R-1 as Rachana); only tagged releases, never a branch or snapshot |
| LK-16 | Freeze during the lane | **Freeze `lake/*`, the three plugins and `tools/lake` on Drishti from the freeze commit** (the Polars engine is built in the product, after the Python cut, not in Drishti) |

## 14. Decisions taken (lead, under the owner's delegation, 2026-11-21)

LK-1 to LK-8 and LK-10 to LK-16 are **accepted as recommended**. LK-9 (the Delta `hadoop` engine stays out of the product) is accepted **provisionally**: the owner is asked to confirm that no site uses `abfs://`, `gs://` or HDFS through it; until then the engine stays in Drishti untouched. R-1 is recorded in drishti-rachana `docs/design/DECISIONS.md` on `g2/integration` (2026-11-21): released jars into `~/.m2`, installed from the tag only.
