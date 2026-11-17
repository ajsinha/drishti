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
# Impact analysis: release i7a, modules end to end

**Owner decisions, 2026-11-17 (they override the text below where they differ).**
1. **One Spring context is dropped, permanently.** Per-module **child** contexts (the kernel's context as parent) are the one design:
   **F1 and I12 (F1a/F1b/F1c) are dropped**, and the guards G11 and G12 hold the child-context design instead
   (`ModuleControllerPathsGuardTest`, `ModuleBridgeTest`, `ModuleChildContextGuardTest`; TEST_HARNESS.md).
2. **K5 and I15** (banking's kernel-internals 43 to 39) **move to the next release**.
3. **There is no separate Drill A**: the one full drill is the release drill (Drill B).

**Status: analysis, nothing built, nothing run.** Companion of `I7A_BUILD_PLAN.md` (the plan; its step names A1…Z1 are used here).
Read from git only: the base `origin/wip/i6` at `52606dea6` ("the base"), `wip/i7a` at `f8a86ef0a`, `wip/i7a-core` at `010600074`.
No build, test or server was run for this document, so every statement about behaviour is from reading the code; where a point is
an inference and not a line that was read, it says **(inferred)**. Paths are relative to the repository root. Deleted with the plan at release.

## Contents

1. [What the plan gets wrong today](#1-what-the-plan-gets-wrong-today)
2. [Merging the front-loaded branches onto the base](#2-merging-the-front-loaded-branches-onto-the-base)
3. [Impact by step](#3-impact-by-step)
4. [Launcher impact](#4-launcher-impact)
5. [Testing impact](#5-testing-impact)
6. [The safe order](#6-the-safe-order)
7. [Risks and the guards to write first](#7-risks-and-the-guards-to-write-first)
8. [Decisions needed before building](#8-decisions-needed-before-building)

## 1. What the plan gets wrong today

Each row is a place where the plan's text no longer matches the base, or never matched the code. **Sev** = what happens if a builder
follows the plan as written: **stop** (will not compile or cannot be done), **silent** (builds and passes, wrong in production), **cost** (works, more work than estimated).

| # | Plan says | The base says | Sev | Step |
|---|---|---|---|---|
| S1 | `PackResolver.modules`, `ConfigResolution`, `ModuleOverrides`, `ModulePackages`, `ModuleDirs`, `LoaderProcess`, `Requires` are "re-pointed to `ModuleBoot.current()`" | `ModuleBoot` is in `kernel/drishti-module-runtime` (depends on `drishti-kernel-api` only). The readers are **below or beside it**: `drishti-api` (resolver, overrides, `Roots`), `drishti-registry` (→ `drishti-api`), `drishti-lakeio`, `drishti-lakeload`, `drishti-compute`, `drishti-packs`. None depends on `drishti-module-runtime`; `drishti-api` cannot | stop | B1, B2 |
| S2 | "no folder walk": every reader takes modules from the boot result | `ConfigResolution.repository(root)` (`resolve --json --root`, `tools/resolved.py repository()`) is the reading of a **checkout's source folders with every module on**. About 50 Python tools and tests use it (13 `tools/packgen`, 10 console tests, 6 `tools/docs`, 4 `tools/lake`, `tools/testdata`, `contracts.py`, `packlake.py`, `settings_catalogue.py` …) and Java tests through `TestRoots`. The plan never says what `--root` means after i7a | stop | B2 |
| S3 | a packed module is "the same files, another folder" | `content.yaml` **trims** (`packs/*/tests`, `packs/*/tools` left out) and **copies in** (`config/lenses/banking/*.yaml` → `lenses/`, four `docs/guides/*_GUIDE.md` → `guides/`). The unpacked cache folder is not the source folder: a tool that finds a pack's `tools/make_*.py` or `tests/` through the resolver finds nothing in packaged mode | silent | B2 |
| S4 | the module poms call `module pack` with the default kernel build ("the jar in `<home>/lib`") | Inside one Maven run the new exec jar is in `kernel/drishti-server/target`, not yet in `lib/`. Banking (a `kernel-internals` allowance) is stamped for **the old jar's** build, or refused when `lib/` is empty; the new server then refuses banking (D11). The drill's Java lane copies make it certain: the copy's `lib/` holds the gate's jar while the copy compiles its own `drishti-server` (another `build.time`) | silent | A3 |
| S5 | `.dmod` is "byte-for-byte reproducible"; the digest carries `hash16` of the file | No pom sets `project.build.outputTimestamp`: every module jar differs build to build, and banking's manifest carries `kernel-build: <version>+<build time>`. A file hash differs per build and per machine (also per zlib). Two nodes that each ran `build modules` from the same commit show "different module" on Admin → Cluster | silent | A1, B4 |
| S6 | VaR goldens in the gate prove B3, F1, K3, K5 | `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest` run banking on the **flat test class path** (`BankingTestHarness`: `@Import(BankingAutoConfiguration.class)`, one context, no `ModuleClassLoader`, no bean-name prefix, `ServiceLoader` sees banking's `META-INF/services`). They stay green whatever B3 and F1 do to the loader and the context. Only the container smoke's golden goes through a packaged server | silent | B3, F1 |
| S7 | five kept reads move to `ModulePublicController` | The kept reads call the deleted service: `route` → `deploy.routes()`, `consoleParts`/`consolePart` → `deploy.consoleFolder()` and the private `shipped()` (base: folder modules **with code**, the genomics sample), a sixth endpoint `/api/v1/modules/gone` → `deploy.gone()`. `route` answers 308 (moved) and 410 (removed, by whom, when, which deployment) from `RouteTable` and tombstones | stop | B4 |
| S8 | `ModuleNode` keeps the registry, `status`, `byPath…`, `needs`, `fingerprint` | Readers of `ModuleNode` outside `deploy/` that the plan does not list: `PackReloader`, `PublicAboutController` (`manifests` fingerprint), `ConsoleAnswers` (lines 126, 156, 344: the bootstrap answer and its change key), `EditionConfiguration:59-76`, `ModuleConfiguration.Started`. `ModuleNode.State` has `REMOVED`; the store's `registry.json` holds a per-module state that `ModulePackages.read` reads | cost | B4 |
| S9 | one off switch | Two today: the store's `registry.json` state (deploy "off"/"remove") and `config/modules/<id>.yaml` + `drishti.modules.<id>.enabled`. Only the second survives; the first must be read once for the upgrade message | silent | B4 |
| S10 | readers of the modules folder: the 14 files of B2.4 | Also: `api/Roots.java` (`MODULES`), `server/cli/CliLauncher.java:133-135` (`drishti.packs.modules-dir`, `DRISHTI_MODULE_DEPLOY_DIR`), `ModuleVerifier.java:53`, `ModuleRuntime.java:87`, `kernel/drishti-server/pom.xml:131` and `modules/banking/java/banking-service/pom.xml:90` (surefire `drishti.modules.home` = the source tree), `tools/settings_catalogue.py:103-105`, `tools/docs/shots/{deployqa,swapone}.py`, `deploy/docker/ecs/server.Dockerfile:32,43`, `tools/launch/envsetup.py:37` (`DATA_LAYOUT` makes `<data>/modules`), the comments of `config/modules/{banking,capitalmarkets,economy}.yaml` | cost | B2, C1 |
| S11 | A2 deletes `ModuleTool`, `ModuleAssembler` | Users the plan does not list: `ModuleToolTest` (451 lines), `ContentModulesTest`, `testing/drishti-it/SourceModuleInstallTest`, `banking/var/VarInstalledModuleRestartTest` ("made by the same packer"), `tools/packgen/module_from_jsonl.py:310`, `tools/packgen/source_module.py:280-317` (reads `packaging/dist`), `tools/test_pack_new_source.py:42`, `tools/test_pack_authoring.py`, the allow-list rows of `drishti-console/tests/test_no_relative_paths.py:67-69` and `ArchitectureRulesTest:234`, `tools/settings_schemas.yaml:478-480` | stop | A2 |
| S12 | `packaging/dist` has `check-banking` | It has three: `check-banking` (`--with market-data`), `check-market-data`, `check-trading`. The verifier needs `--with` (a required module loaded for C11–C14) | cost | A2, A3 |
| S13 | `packaging/drishti-kernel-app` deleted | Its jar is what three things start: `tools/modulecli.py run` (246-249, 330), `test_genomics_walk_browser.py:38`, `test_connector_designer_browser.py:45-49`. Both tests **skip** when the jar is absent | silent | A3 |
| S14 | the 12 loopback calls | 14: the base added `Caller.figures(…)` (`InlineFigures`, read by `InlinePivotFigures` in `drishti-module-runtime`; used at `TradingLanding:666-667`, `MarketLanding:436`) and `KernelCalls.cache(size, ttl)` (`KernelPersonCache`). K1's `Kernel` has neither | stop | K1, K4 |
| S15 | K1 `InlineRequest` "with the inline body's rules" | The base made `InlinePivot` (`drishti-bicompute`) "the one reading of an inline pivot body". K1 adds a third copy of `MAX_ROWS` and of the field-name rule (`InlineFigures.Field`, `InlinePivot`, `InlineRequest.Field`), with a different type list (`date` added) | cost | K1 |
| S16 | K1 tests: compile only | `kernel-api/src/test/…/ApiSurfaceTest.surfaceIsTheBaseline` fails on every new public type until `kernel-api-surface.txt` is regenerated (`-Dsurface.write=true`). K1 (+19 types), D2 (`DrishtiException` in signatures) and K4 (−`KernelCalls`) each rewrite that sorted file | stop | K1, D2, K4 |
| S17 | K4: "the landings' server tests unchanged and green" | `TradingStandIn` (517 lines) and `MarketStandIn` (435) **implement `KernelCalls`** by path strings; `TradingLandingRecordTest`, `MarketLandingRecordTest` record `drishti-console/tests/answers/landings/*.json` from them. They are rewritten as `Kernel` implementations; the recordings must come out byte-equal | cost | K4 |
| S18 | D2: 549 files | 519 Java files import the two classes; plus `kernel-api-surface.txt` and `DEVELOPER_GUIDE.md` | – | D2 |
| S19 | run configurations: 11 files, `DRISHTI_MODULES_HOME=$PROJECT_DIR$/modules` | 11 in `run-configs/` and 16 in `drishti-console/run-configs/`; one names the modules folder today. The launcher's default already is `<home>/modules` | – | C1 |
| S20 | X1: `today` | `FormCheckController` takes today from `engine.time.BusinessDates` (honours `drishti.business-date.today`). The save paths check with `LocalDate.now(UTC)` (`EditHandler:352`, `DraftsController:175`); `EditService:252` takes a supplied today. A rule on `today` can pass while typing and fail on Save | silent | X1 |
| S21 | X1 token scope "as `/changes/preview`" | That is the read-only POST list `application.yaml:419-420`; the front-loaded controller added no row (new files only) | stop | V0, X1 |
| S22 | E1 guard G2: no kernel test opens `modules/` | `ConsoleAnswersRecordTest` (`drishti-server`) records the four bootstrap answers from every module; 18 `drishti-server` tests and `AllShippedPacksTest`, `DistributionsTest`, `LaunchRootsTest`, `ModuleSettingsTest`, `AccessRegistryTest`, `PackResolverFixtureTest` read `modules/` | cost | E1 |
| S23 | "the anywhere lane: release unpacked" | `test_release_from_other_folder.py:104` unpacks `git archive HEAD` + the gate's jar. Built `.dmod`s are git-ignored: the archive holds module **sources** and no package | stop | C1 |
| S24 | sweep "never deletes another node's cache" | True only for nodes on the same files. Where nodes share one data folder, a rolling upgrade has old and new packages at once; the new node sweeps the old version's folder under the running old nodes. (ECS data is on EFS; `server-entrypoint.sh` holds one task per node's folders with `task.lock`, so it looks per node: confirm before relying on it) | silent | B1 |
| S25 | F1 keeps "`ApplicationReadyEvent` start only calls `DrishtiModule.start`" | Three order hooks hang on the modules' late load today: `SourceRegistry.modulesLoaded()`, `PackReloader.modulesLoaded()`, `Extensions.changing()` (`ModuleDeployConfiguration:200-226`). `NOT_PLACED` (no part of the module on this node, `ModuleRuntime.start`) is decided from `PlacementService`, a bean; the plan's item 7 names switch, licence and distribution only | silent | F1 |

## 2. Merging the front-loaded branches onto the base

| Check | Result |
|---|---|
| `wip/i7a` (3 commits) onto `52606dea6` | `git merge-tree`: clean. Its 5 code files do not exist at the base |
| `wip/i7a-core` (2 more commits, 24 files) onto `52606dea6` | `git merge-tree`: clean. No path exists at the base |
| Never rebase (plan section 6) | Holds: `git merge origin/wip/i6` into `wip/i7a`, then `git merge wip/i7a` into `wip/i7a-core` (or the reverse once); both pushed already |

Textually clean, **semantically not**. What V0 will hit, in the order it fails:

| File (branch) | What breaks at V0 | Fix |
|---|---|---|
| 19 files of `kernel.api.services` (core) | `ApiSurfaceTest.surfaceIsTheBaseline` (S16). `onlyContractsAndNamedValues` passes: all are interfaces, records or an annotation; `Kernel.Refused` is nested | regenerate the baseline in the same commit |
| `Kernel.java` (core) | no `cache(…)`, no figures call (S14); `Refused` duplicates `KernelCalls.Refused` until K4 | add `<V> PersonCache<V> cache(int, long)`; `Pivots.figures(Person, fields, rows, values, by, across, top)` answering `InlineFigures.Figures` (the reading stays `InlinePivotFigures`) |
| `InlineRequest.java` (core) | third copy of the inline rules (S15) | keep the record as fields only; the rules stay in `InlinePivot`; `MAX_ROWS` = `InlineFigures.MAX_ROWS` |
| `ModuleBoot.java` (core) | compiles (same-package types only: `Dmod`, `DmodManifest`, `FirstParty`, `VersionRange`, `KernelFacts`); cannot be called by its readers (S1) | section 8, decision 1 |
| `DmodPythonPackTest.java` (i7a) | package `module.tool` (A2 renames it); `assumeTrue(python != null)`: **skips** with no Python or no PyYAML | move to `module.runtime`; fail, not skip, when `drishti.test.requires-python=true` (set by the drill's Java lanes) |
| `tools/modulepack.py` (i7a) | `test_tools_guide.py` (every tool in the tools table), `test_command_catalog.py`, `CLI_REFERENCE` rows: the file is not registered anywhere yet | A1's registration, docs and catalogue in V0, not later |
| `FormCheckController.java` (i7a) | compiles against the base (`Form.check`, `withDefaults`, `Form.amount`, `Entitlements.formRoles/requireOpen` unchanged since `12443840c`); `TokenScopeGuardTest` and `tools/test_api_docs.py` fail: no scope row, no API-docs row (S21) | add both in V0 |
| both | licence header and `LicenseHeaderTest`, `SourceFileSizeTest` (cap from `tools/code_cap.py`, `CODE_LINE_CAP = 2500`): all files are under 400 lines | none |

Conflict hot spots once steps start (generated or append-only files; **never merge by hand, regenerate**): `kernel-api-surface.txt`,
`docs/admin/CONFIGURATION.md`, `docs/guides/API_REFERENCE.md`, the command catalogue, `pack-resolution-inventory.md`, `CHANGELOG.md`,
`application.yaml`, `tools/settings_schemas.yaml`, `drishti-console/tests/answers/*.json`, `drishti-console/tests/snapshots/**`.

## 3. Impact by step

Per step: **Code** (touched, invalidated, readers), **Launch**, **Tests** (break / new / re-record), **Lanes** the change reaches.
Lanes: gate · java21 · java25 · other (console, no browser) · browser · tools-a/b · anywhere · containers.

### A1 · The Python packager

- **Code.** New `tools/modulepack.py` (front-loaded). Register in `tools/drishti_parser.py:109-128` (today `pack|check|inspect` → `cmd_module`,
  `--tool`, `--jar` single, `--out` default `<folder>/dist`) and `tools/drishti.py:135-160`. Differences from `ModuleAssembler` to settle:
  `--more` (dist pom) becomes repeated `--jar`; default `--out` moves from `<folder>/dist` to `<module>/target/dmod`.
  Add `--kernel-jar FILE` (S4) and `modulepack.digest(folder)` = the content lines of `DMOD.MF` computed from the **source** (for the
  stale check of section 7, R2). `first_party` writes the SHA-256 of `DMOD.MF`: content identity, independent of zip bytes (keep).
- **Launch.** None yet.
- **Tests.** New `tools/test_modulepack.py` (11 tests, front-loaded). `DmodPythonPackTest` (section 2). Breaks when registered:
  `tools/test_drishti_cli.py` (module rows), `test_command_catalog.py`, `test_tools_guide.py`, `test_docs_rot.py` (CLI_REFERENCE names).
  Add to `test_modulepack.py`: a real module (`modules/trading`) packed by Python equals `ModuleAssembler`'s package entry for entry
  (names and SHA-256) **while both packers exist**; that test is deleted with `ModuleAssembler` in A2.
- **Lanes.** gate (static tools checks), tools-a/b, java21 (the Java test).

### A2 · The Java verifier as a command; the module tool deleted

- **Code.** New `server/cli/ModuleCli.java`; `CliLauncher` dispatch. Move `module/tool/{ModuleConformance,ClassFiles,VisualTemplate}`.
  Delete `ModuleTool`, `ModuleAssembler`, `LeakCheck`, the shade execution (`drishti-module-runtime/pom.xml:45-62`), `find_module_tool`.
  `ModuleCli` options the plan omits: `--with FILE…` (S12), `--first-party FILE`, `--kernel-build` (tests). Also re-point (S11):
  `tools/packgen/module_from_jsonl.py:86,310`, `tools/packgen/source_module.py:280-317`, `tools/modulecli.py:246-249`.
  `ModuleConformance` C15 and `cycles` go; C11, C13, C14 still build a `ModuleRuntime` with child contexts until F1 (their rewrite is F1.8).
- **Launch.** `drishti.py module verify` needs the server jar in `<home>/lib` (was: the tool jar from `target/`). A module developer
  without a built kernel has the SDK's jar (D1): until D1, `module verify` in a fresh checkout says *"run build jar"*.
- **Tests.** Delete or rewrite: `ModuleToolTest` (451: split into `ModuleCliTest` in `drishti-server` and packer cases already in
  `test_modulepack.py`), `ContentModulesTest` (packs content modules with `ModuleTool`: becomes a tools test, `tools/test_content_modules.py`,
  packing each pom-less module and running `module verify`), `SourceModuleInstallTest`, `tools/test_pack_new_source.py`,
  `tools/test_pack_authoring.py`, `tools/test_modulecli.py` (the `run` rows), allow-list rows in `test_no_relative_paths.py` and
  `ArchitectureRulesTest`, `settings_schemas.yaml` rows `DRISHTI_MODULE_TOOL`, `DRISHTI_KERNEL_JAR` (→ `test_settings_catalogue.py`,
  CONFIGURATION regenerated). New `ModuleCliTest`.
- **Lanes.** gate, java21, java25, tools-a/b, browser (`test_pack_designer_source_browser.py` runs the source-module flow).

### A3 · The build

- **Code.** `pom.xml` (modules at 46, 82-87; `drishti.modules.home` at 289-291), `packaging/dist` (184 lines) and
  `packaging/drishti-kernel-app` deleted, `packaging/drishti-app/pom.xml`, the four module poms, `drishti-sdk/*`, `tools/launch/envsetup.py`,
  `launchcli.py:227-257`, `.gitignore`, **`.dockerignore`** (today `modules/**/src`, `modules/**/pom.xml`, `**/target`).
  - **Kernel build stamp (S4).** Each module pom passes `--kernel-jar ${repo}/kernel/drishti-server/target/drishti-server-${revision}-exec.jar`
    (reactor order puts `packaging/drishti-app` before the modules; `drishti-app` writes that jar). Never the default.
  - **Reactor.** `modules/genomics` sits at position 46 today (before `drishti-server`); it moves into the `modules` profile after
    `drishti-app`. `testing/drishti-it` depends on `drishti-banking` (line 33 of its pom): stays until E1.
  - **`drishti-app` loses `drishti-modules`.** Every test that starts the exec jar or `DrishtiApplication` and expected shipped modules
    (`drishti.modules.shipped`) now has none until C1/E1 give it a modules folder. This is why A3 cannot merge alone (section 6).
  - **Where `.dmod`s land.** Maven writes `modules/<m>/target/dmod/`; only `build modules` copies to `<home>/modules`. Tests inside
    the reactor must take packages from `target/dmod` (a property), never from `<home>/modules` (which a Java lane copy does not have).
- **Launch.** Section 4.
- **Tests.** New G1 `ServerJarHoldsNoModuleTest`, `tools/test_build_modules.py`. Breaks: `tools/test_launch.py` (build rows),
  `test_genomics_walk_browser.py`, `test_connector_designer_browser.py` (S13: re-point to the one jar with a chosen modules folder; make
  the absence of the jar a failure in the drill), `SourceModuleEndToEndTest`, `test_java_launch_guard.py`, `tools/test_api_docs.py`
  ("reads every module's controllers but the sample publisher's": paths unchanged), `tools/drillkit/affected-map.txt` rows naming
  `packaging/*`, `tools/drillkit/test_affected.py`.
- **Lanes.** every lane (the jar and the build change).

### B1 · Module boot

- **Code.** `ModuleBoot` + `ModuleFiles` (front-loaded, 584 lines). To add before use: (a) the placement of S1's decision;
  (b) the content hash (SHA-256 of `DMOD.MF`) in `Module`, beside the file hash, for the digest (S5); (c) the in-use guard of S24:
  each server writes `<cache>/<id>/<version>-<hash16>.in-use-<node>` (a visible file, refreshed while running, removed on stop) and the
  sweep skips a folder with a fresh one; (d) the upgrade note of S9: `<data>/modules/registry.json` naming a module OFF or REMOVED is
  reported once as *"was off in the old store: set enabled: false in config/modules/<id>.yaml"*; (e) `leftovers` also names
  `<data>/overrides/installed` and `<data>/modules/registry.json`.
  Deleted: `ModuleDiscovery.readActive/pick/HOST_FOLDERS`. Their readers: `ModuleNode:222,224,440,517,558`, `ModuleDeployService:717`,
  `ModuleRuntimeTest:203`. **So the deletion belongs to B4, not B1** (B1 stays "new files only").
  `FirstParty.shipped` and `RESOURCE`: read by `KernelFacts.ofKernel():75`, `ModulePackages.read:93` (its own reading of the resource),
  `YamlAccessStore:84`. `KernelFacts.ofKernel()` then has no list: every caller (`ModuleDeployConfiguration:181`, `ModuleConformance:100`,
  tests) must take the facts **with** the boot's list (`Result.facts`), or first-party origin is silently lost and banking's allowance refused.
- **Tests.** `ModuleBootTest` (G3, G4, G6, G8). Add: G-inuse (a fresh marker keeps a folder), two processes unpacking one file at once
  (the `.part` race), a `.dmod` whose `module.yaml` is not the first entry, a read-only modules folder, a cache root on another volume.
- **Lanes.** java21, java25.

### B2 · Every reader asks the boot result

- **Code.** The readers, complete (base line numbers): plan's B2.4 list **plus S10**. Shapes that change:
  - `ResolutionSettings` record (6 components; `modulesPath` and `Layout.store()` go): constructed in `ConfigResolution.repository`,
    `ResolveCli.siteOnly:153-155`, tests. `ResolutionSettings.of` also reads `drishti.module-manifests-dir` and `drishti.packs.dir` (old names: delete here).
  - `PackResolver.Module.source` is printed by `resolve --json` and read by `tools/resolved.py:183` (`Module.source`, `Layout.store`,
    `Layout.installed`): the JSON document's shape changes → every cached answer under `<data>/tmp/resolved/` and
    `DRISHTI_RESOLVED_CACHE` is stale. `resolved._inputs` (226-233) keys the cache on `modules/` folder times: it must key on the `.dmod`
    files and `first-party.yaml` too, or a rebuilt module serves an old answer **(silent)**.
  - `ModuleOverrides`: `apply`, `SOURCE_HOME`, `sync` of the whole tree deleted; `installed` → `view`; statics `INSTALLED`,
    `INSTALLED_REFUSED`, `activeDirs` stay process-wide. Readers: `LaunchRoots:192-226`, `HealthController:155`, `ResolveCli:140-150`,
    `PackResolver:249`. `<data>/overrides/modules` (whole-tree view) and `<data>/overrides/installed` become leftovers.
  - `Roots.modules()` keeps meaning "the modules folder" (now of `.dmod` files). New `Roots.moduleDir(id)` reads `drishti.module.dir.<id>`.
  - Published properties `drishti.module.dir.<id>`: `LaunchRoots:183-186` publishes three roots as system properties today; the per-module
    ones follow the same way and are passed on by `LoaderProcess:214` (a fixed list of four keys: add every `drishti.module.dir.*`,
    `drishti.modules.dev`). The loader process must **not** unpack: it reads the properties its parent gives.
  - `drishti.live-sets.modules-dir` (`application.yaml:246`, `LiveSetConfiguration:169`) deleted; `drishti.packs.modules-dir`
    (`CliLauncher:133-135`, `CliLauncherScratchTest:54-56`) deleted.
  - FRTB: `config/engines/frtb-sa.yaml:24`, banking's copy, `FrtbProperties:44` → `${drishti.module.dir.banking}/…`. The property exists
    only when banking booted: with banking absent the placeholder must not fail the start of a kernel-only server (default `:`).
  - **Source mode (S2, S3).** `ConfigResolution.repository(root)` = `ModuleBoot.boot(…, devFolders = the checkout's module folders)`:
    the one place that lists source folders; G9 allows exactly it. It is what tools and tests "of the shipped files" mean, and it never
    touches `<home>/modules/*.dmod`.
- **Launch.** `tools/javacmd.py:306-323` (`root_flags` adds `-Ddrishti.modules.home=<repo>/modules`), `tools/settingsfile.py:50-51`,
  `tools/settings_sources.py:222`, `tools/doctor.py`, `tools/settings_catalogue.py:103-105`.
- **Tests.** Re-cut: `kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml`, `PackResolverFixtureTest`, `ModuleOverridesTest`
  (237), `ModuleLayoutTest`, `LaunchRootsTest`, `DistributionsTest`, `ModuleSettingsTest`, `AccessRegistryTest`, `CliLauncherScratchTest`,
  `ResolveCliTest`, `PackReloaderTest` (420), `EngineCatalogueTest`, `LiveSetValidationTest`, `tools/test_resolved.py`,
  `tools/test_config_readers_guard.py`, `tools/test_empty_lakes.py`, `drishti-console/tests/test_paths_guard.py`,
  `test_settings_catalogue.py`, `test_docs_settings.py`. New G9 `NoModuleFolderReaderTest`. Recordings: none yet (answers unchanged).
- **Lanes.** every lane.

### B3 · The plain loader and the library warning

- **Code.** `ModuleClassLoader` (175): parent today is the **platform** loader; everything outside `KernelFacts.SHARED` (12 prefixes) and
  the allowance is invisible. "Parent first, always" makes every kernel library visible to every module: Kafka clients, Caffeine,
  SnakeYAML (banking's three `# library` lines, `kernel-internals.txt:50-54`), Delta, Arrow, Hipparchus, OpenBLAS. `getResource(s)`
  must follow the same order (today: shared paths → kernel, else own then platform): `META-INF/services` and
  `META-INF/spring/*.imports` of the **kernel** become visible through a module's loader — a module-side `ServiceLoader.load(X.class)`
  with its own loader now also returns the kernel's providers **(inferred; check with genomics)**.
  `isClassReloadable`/`publicDefineClass` (`SmartClassLoader`) exist for hot unload: keep `publicDefineClass` (proxies of module classes
  are defined in the module's loader), drop the reload claim in F1.
  `KernelFacts.SHARED` readers: `KernelFacts.ofKernel`, `ModuleRuntime:163`, `ModuleVerifier`, `ModuleConformance`, `ClassFiles` (C1 reads
  what a module reaches against the shared list), `docs/…/KERNEL_AND_MODULES.md` §4.4. The `com.ash.drishti.` filter replaces it in each.
  `META-INF/drishti/kernel-libraries.txt` is generated in `packaging/drishti-app` (A3): B3 depends on A3 for the real list; unit tests use a fixture list.
- **Tests.** `ModuleRuntimeTest` (372: loader visibility cases flip), `ModuleHostServerTest`, C1 in `ModuleConformance`,
  `BankingReachTest` (holds `kernel-internals.txt`; three lines leave). New G10 `KernelLibrariesWinTest`. The VaR check here must be
  **G14** (section 7), not the flat goldens (S6).
- **Lanes.** java21, java25, containers (smoke golden).

### B4 · The deployment machinery deleted

- **Code, server.** Delete `ModuleDeployService` (719), `ModuleAutoDeploy` (150), `NodeCalls` (179), `ModuleRouteFilter` (107), most of
  `ModuleNode` (1,078) and `ModuleDeployController` (386); `ModuleDiscovery.readActive/pick/HOST_FOLDERS` (from B1).
  Kept reads and what each needs instead (S7):

  | Endpoint | Console reader | After |
  |---|---|---|
  | `GET /public/modules/console` | `core/module_parts.py:214` | every booted module: id, version, state (`active`/`off`), title, `console` = the dir has `console/plugin.yaml`, claims. No `removed` |
  | `GET /public/modules/console/{id}/{version}` | `module_parts.py:246` | zip of `module.yaml`, `console/`, `elements/`, `docs/` from the boot's dir (overrides view). 404 off or unknown; **no 410** |
  | `GET /public/modules/manifests` | the console's distributions | `Distributions.known(env)` over the boot result |
  | `POST /api/v1/modules/needs` | `module_admin_routes.py:245` | `ModuleNode.needs` (kept); state `removed` no longer produced |
  | `GET /api/v1/modules/route` | `module_admin_routes.py:228` | 200 / 404 only; `RouteTable` MOVED (308) stays if a plugin declares `moved:`; tombstone fields (`by`, `at`, `deployment`) gone |
  | `GET /api/v1/modules/gone` | **none** (no console, tool or test calls it) | delete |
  | `GET /api/v1/net/modules/status` | cluster digest | kept, plus content hash per module |

  `ModuleNode.State.REMOVED`, tombstones (`ModuleRuntime` 58-81, 318, 366-376), `Leave.REMOVE`, `drain`, `awaitSwap`, `swapping`,
  `enter/leave`: gone. Console files that show "removed": `web/templates/module_removed.html` (49), `needs_data.html`, the 410 branch of
  `module_admin_routes.py:228-240`, `module_parts.py` (state `removed`), `tests/module_parts_fake.py:83-100`. **Decision 4** (section 8).
  Re-point the outside readers (S8): `ConsoleAnswers:126,156,344-354`, `PublicAboutController:79-81`, `EditionConfiguration:59-76`,
  `ModuleConfiguration:78-102`, `PackReloader`, `ModuleWiring`. `DigestService`: carries `{id, version, contentHash}`.
  `ModulePackages.read` (first-party resource, `registry.json`, `active.yaml`) and `ModulePackages.store`, `YamlAccessStore:56,84`,
  `ModuleManifests.IN_JAR`: rewritten over the boot result (they were on B2's list; they only become deletable here).
- **Code, settings.** `application.yaml:204-210` (`path`, `autodeploy`, `autodeploy-seconds`, `shipped`; `dev` kept), `350-356`
  (`module-deploy.*`: `dir`, `drain-seconds`, `node-call-seconds`, `conformance-cycles`, `swap-wait-seconds`), `settings_schemas.yaml`,
  the token-scope rows of the deleted admin and net endpoints, `drishti-console/config/application.yaml:218`
  (`builder.pack_designer_modules_dir`), `config/sitemap.yaml` (the deploy page).
- **Code, console.** Delete `web/templates/admin/module_deploy.html` (120), the deploy routes of `routes/module_admin_routes.py`
  (248 → about 90: `needs`, `route`, the list), `tests/test_admin_modules_deploy.py`. Rewrite `admin/modules.html` (124; reads
  `/api/v1/admin/modules/deployments` as `data` today → `/api/v1/admin/modules`, `ModuleAdminController:84`) and
  `static/js/drishti_admin-modules.js` (79) on Drishti Elements (mock-ups first: 2–3, the owner picks). `core/admin_forms.py`,
  `core/app.py` (route registration), `routes/monitor_routes.py`, `admin/_health_body.html` (245: the modules block gains leftovers,
  refused, extra libraries), `admin/artifacts.html`, `admin/packs.html`.
  **Pack Designer (Q9):** `routes/pack_routes.py:360-405` — actions `install` (copies into `builder.pack_designer_modules_dir`, line 380-387),
  `plan` (392-393: upload to the store + plan), `apply` (397) → one `download`; `web/templates/build/pack_designer.html` (179: the two
  buttons and the line that says how to install), `routes/build_routes.py`. The designer's check (`module check` through the module
  tool) becomes `module verify` through the server jar.
- **Code, tools.** `tools/modulecli.py` deploy commands; `tools/docs/shots/deploy.py` deleted; `shots/packs.py`, `t45.py`,
  `shots/deployqa.py:174`, `shots/swapone.py:17,40` re-cut; `tools/drillkit/explain_hammer.py` (names module-deploy).
- **Tests.** Delete `ModuleDeployClusterTest` (436), `DeployTestModule` (169), `test_admin_modules_deploy.py`, most of
  `tools/test_modulecli.py`. Rewrite `ModuleHostServerTest` (388), `GenomicsDistributionServerTest` (198), `ModulePythonTest`,
  `ModuleRuntimeTest` (active.yaml, tombstone cases), `PackReloaderTest`, `VarInstalledModuleRestartTest` (S11: pack with the Python
  packager into a temp modules folder, start twice), `test_pack_designer_routes.py:172-173`, `test_write_endpoints_admin.py:133`,
  `test_pack_designer_browser.py` (names `builder.pack_designer_modules_dir`), `test_pack_designer_source_browser.py`,
  `test_module_parts.py`, `test_module_parts_browser.py`, `module_parts_fake.py`, `test_genomics_module.py:41-43`, `conftest.py:150-153`,
  `server_answers.py:82-121` (`shipped_part`), `test_top_bar_domains.py`, `test_distributions.py`, `test_home_house_browser.py`,
  `test_modules_off.py`, `test_engine_off_everywhere.py`, `test_admin.py`, `test_studio_auth.py`, `test_thin_console_guard.py`.
  **Re-record:** `ConsoleAnswersRecordTest` → `answers/bootstrap-{all,finance,genomics,risk}.json`, `admin-pages.json` (module rows
  have `"version": null` today for folder modules; every module gets a version; the Deploy page leaves the admin pages).
  **Re-approve snapshots:** `test_snapshots_admin_browser.py` (Admin → Modules, Health, the white-label page's module states),
  `snapshot_pages.py` (drop the deploy page). `test_docs_api_endpoints.py`, `tools/test_api_docs.py` regenerate `API_REFERENCE.md`.
- **Lanes.** every lane; cluster tests (`PlacementClusterTest`, `test_admin_net_stack_browser.py`, `test_cluster_failover.py`).

### C1 · Launchers and deployment files · C2 · The operator's commands

Section 4 holds the detail. Tests: `tools/test_launch.py`, `test_release_from_other_folder.py` (S23), `test_detached_console_browser.py`
and `detached_console.py` (the console copy ships no module: unchanged, proves the thin console still reads none),
`test_console_ships_its_help.py`, `tools/drillkit/container_smoke.{py,json}`, `test_java_launch_guard.py`, `test_script_paths_guard.py`,
`test_python_main_paths_guard.py`, `deploy/aws/**/test_*.py` (image tag only). C2: `tools/test_modulecli.py` rewritten,
`tools/test_drishti_cli.py`, `test_command_catalog.py`. Lanes: tools-a/b, anywhere, containers, browser (the stack fixtures).

### D1 · The SDK folder · D2 · `DrishtiException`, `ErrorCode` into `drishti-api`

- **D1.** `modules/genomics/java/genomics/pom.xml` depends on `drishti-module-maven-plugin` and `drishti-testroots` today: genomics is the
  SDK's proof and must build with the parent's `exec` executions only. Archetype (`drishti-sdk/drishti-module-archetype`, 20 resources)
  and `testkit/ModuleFixtures.generate` (3 users) follow. `SdkBuildsGenomicsTest` needs Maven offline with a file repository: it is a
  heavy job (one at a time) and belongs to the tools lane with a budget row.
- **D2.** 519 Java files; `kernel-api-surface.txt`; `drishti-kernel-api/pom.xml` drops `drishti-common` only if no other `common` type is
  in the API (banking alone imports `common.*` from 75 main files: `TempFiles` is 3 of them; the package stays on the allowance unless
  **all** of `common` that banking uses moves — K5's "common.* (D2)" row is therefore not achievable by D2 alone: count per class first).
  `modules/banking/…/META-INF/drishti/moved-classes.properties` (the rename table for saved names): add the two moves if error class
  names are ever persisted **(check `ErrorCode` in stored audit rows)**. One scripted commit, nothing else merged that day.

### E1 · Tests untied from modules

- **Code.** 34 Java test files outside `modules/` name a module folder (18 `drishti-server`, 4 `drishti-packs`, 3 each `drishti-api`,
  `drishti-module-runtime`, `drishti-it`, 2 `lakeload`, 1 each `rachana`, `engine`, `registry`, `lakeio`). Surefire's
  `drishti.modules.home = <repo>/modules` (S10) is what makes them work; it becomes `none` for kernel projects.
  `ConsoleAnswersRecordTest`, `ConsoleAnswersServerTest`, `GenomicsDistributionServerTest`, `SourceModuleEndToEndTest`,
  `OneTablePerSystemTest`, `AllShippedPacksTest`, `FundsPackTest`, `UsEquitiesPackTest`, `TradingStreamTest`,
  `TradingSutraNextEquivalenceTest`, `RegionEconomicsSampleTest`, `ImperfectDataTest`, `ControlsApiTest`, `NewsInMemorySentinelTest`
  move to `testing/drishti-modules-it` or to their module. `RecordedAnswers` and `TestRoots` (`drishti-testroots`) are unchanged.
- **New project** `testing/drishti-modules-it`: gets its modules from `modules/*/target/dmod` + pom-less modules packed in
  `generate-test-resources`, assembled into `target/modules-home` with a `first-party.yaml`; **source mode is forbidden here**.
  Holds G5, G7, G11, G12, **G14**, `ConsoleAnswersRecordTest`, the genomics distribution test. It starts real servers: one fork.
- **Tests.** `tools/drillkit/affected-map.txt` + `test_affected.py`: a change under `modules/<m>/**` must map to `drishti-modules-it`
  and to "rebuild the modules" (R2). `drill.sh:335` checks named surefire classes exist: add the new guards.
- **Lanes.** java21, java25 (compile and the listed tests), gate.

### F1 · One Spring context

- **Code.** `ModuleRuntime` (462), `ModuleNode.start`, `ApiMounts` (226), `ModuleBridge` (216: line 200 collects extension beans from
  each module's context), `ModuleWiring` (274: `preparing` adds engine defaults to the module's context environment, `started`,
  `stopping`), `KernelModuleContext`, `ModulePython`, `ModuleConfiguration.Started`, `DrishtiApplication`, the four modules' entry
  configurations, `ModuleConformance` C11/C13/C14, `LoadedModule` (35: holds the context), `ModuleHost.preparing(c, ctx)`.
  What the module code uses that changes meaning (counted at the base, `modules/*/java/*/src/main`): `@RestController` 18,
  `@ModuleApi` 2, `@Bean` 53 in 8 files, `@ConfigurationProperties` 12, `@EnableConfigurationProperties` 7, `@EventListener` 2,
  `ApplicationContext` in 2 files, no `@Scheduled`, `@Async`, `@Qualifier`, `@Primary`, `@ConditionalOn*`, `@Order`, filter or advice.
  Points the plan does not cover:
  1. **Mapping.** In one context the kernel's `RequestMappingHandlerMapping` finds every `@RestController` itself at start. `ApiMounts`
     registers `@ModuleApi` beans under `/api/v1/<prefix>` by hand and mounts "absolute" controllers as they are: both must stop, or
     paths register twice (ambiguous-mapping failure at start) or unprefixed (silent). Replace with `PathMatchConfigurer.addPathPrefix`
     keyed on the bean's module attribute, and one check at start: every module handler path is under its own prefix or its declared paths.
  2. **Collected beans double.** A kernel `List<HealthFigures>` (and every other SPI list: 5 banking classes implement such types) now
     includes module beans by injection, and `ModuleBridge` adds them again.
  3. **Bean names.** The `<id>:` prefix is applied by the registrar; banking's flat tests register without it (S6). A name-based lookup
     inside a module (`ctx.getBean("name")`: 2 files use `ApplicationContext`) works in tests and fails in production.
  4. **`@Configuration` proxies and component scan** through a module's loader inside the kernel's bean factory: the highest technical
     risk of the release **(inferred from Spring's design; prove on genomics first, a one-day spike before F1 is estimated)**. Fallback:
     module configurations are `proxyBeanMethods = false` (checked by the verifier).
  5. **Order (S25).** Module beans exist before `ApplicationReadyEvent`. `SourceRegistry.modulesLoaded()` and
     `PackReloader.modulesLoaded()` are then called at the end of refresh, and `Extensions.changing()` is no longer needed for the
     first load. `NOT_PLACED`: read `config/placement.yaml` in the initializer, or register the beans and gate them (decision 6).
  6. **`ServiceLoader`.** Kernel code loads `SourcePlugin`, `OfficialResults`, `DomainVocabulary` with the **kernel's** loader
     (`PluginDiscovery:37`, `OfficialReaders:48`, `Vocabularies:24`); banking ships all three as `META-INF/services` plus `KindProvider`.
     The kernel loader never sees a module's jar: these reach the kernel only where `ModuleWiring` passes them. Unchanged by F1, but
     the flat tests hide any gap. G14 covers it.
  7. **Thread context class loader** is the module's during today's child refresh; in one refresh it is the kernel's. No module code
     reads it (0 uses); a library inside a module that does would change behaviour.
- **Tests.** G11, G12, `ModuleHostServerTest`, `GenomicsDistributionServerTest`, `KernelWithoutModulesTest`, `EngineCatalogueTest`,
  `PlacementClusterTest`, banking's 164 test files (harness change: `BankingTestHarness` registers through the same `ModuleBeans`
  registrar, so names and mappings are production's), trading and market-data landing tests, conformance tests, the whole
  `drishti-modules-it`. Browser: one file per family (section 6). Recordings: none expected; `assertCurrent` proves it.
- **Lanes.** every lane.

### K1–K5 · The typed kernel API

- **K1.** Section 2. `kernelApi` 0.4 → 0.5 (`kernel-api.properties:15`); every module manifest says `kernel: '>=0.2 <1.0'`: unaffected.
- **K2.** Controllers at the base: `api/CatalogController`, `HistoryController`, `BusinessDateController`, `SearchController`,
  `liveset/LiveSetController` exist; **`api/PivotController` and `api/MeController` are not at those paths** (the plan says "grep the
  mapping": do it first). The loopback carries six headers (`LoopbackKernelCalls.CARRIED`: `Authorization`, `X-Drishti-User`, as-of,
  known-at, edition, **`Accept-Language`**): `Person` has no language; a localised message in an answer changes. `TypedCallParityTest`
  needs a language row. The loopback's 60 s timeout and its HTTP error mapping to `Refused(status, code, words)` must be reproduced from
  `DrishtiException` → problem mapping (the one `@ControllerAdvice`): share that mapper, never a second table.
- **K3.** A loopback `/risk/*` call passes `PlacementGuardFilter`, `EngineGateFilter`, `ModuleGateFilter`, `RequestsInFlight`, token
  scopes and `ApiAliasFilter` (old `/api/v1/var` paths) today. The operation path re-implements the first three: a parity row per
  gate. New `/api/v1/net/modules/operation/**`: `NetCallFilter` claims, scope row, API-docs row.
- **K4.** S14, S17. `ModuleConformance.NO_CALLS:706-707` → a refusing `Kernel`. `Answers` loses methods only `Caller.get` answers
  needed. `TradingLanding` (1,252) split; 17 `c.get/post` sites + 2 `figures` + `cache` + `caller` + `lens`. Console side unchanged
  (`landing_answers.py`, `test_landing.py`, `test_landing_back.py`, `test_edition_trading_browser.py`, `test_edition_market_browser.py`).
  Docs that name the loopback: `THIN_CONSOLE_DESIGN.md:401`, `RELEASE_NOTES.md:46`.
- **K5.** Imports at the base: `Entitlements` 15 main + 1 test file, `SourceRouter` 10, `AuditLog` 7 + 2, `BasicEngine` 1,
  `common.*` 75 + 11 (see D2: not removable in i7a). Expect 43 → 39, not 38. `BankingReachTest` is the existing guard (a package no
  longer reached must be removed); `KernelInternalsShrinkTest` duplicates it: extend `BankingReachTest`, add no second guard.

### X1, X2 · The form check

- **X1.** New endpoint beside `FormController` (`/api/v1/forms`) and `FormLookupController` (`/api/v1/forms/lookup`): no mapping clash
  (`/forms/check` vs `/forms/*/*/compile`). Add: the read-only POST row (`application.yaml:419-420`), API docs, the console proxy
  (`routes/drafts_routes.py` beside the lookup proxy at 67-73, or `form_routes.py`), `drill-budgets.txt` row. Fix S20 in the same
  step: `EditHandler:352` and `DraftsController:175` take `today` from `BusinessDates` too (one source, as `EditService:252` is given one), with a test.
- **X2.** `drishti_form-expr.js` (213 lines) has exactly one loader (`terminal/view.html:167`) and one user
  (`drishti_record-forms.js:37`, 864 lines: `X.empty` ×18, `X.typed` ×12, `X.num` ×8, `X.check` ×3, `X.evaluate` ×3). Also
  `tools/dev/asset_prefix_map.yaml:278,515-516`, `test_record_forms.py:55`, `FormExpr.java:29` (its comment names the JS copy),
  `RACHANA_REFERENCE.md:5198`, `drishti_refresh.js:193` (keeps a form being edited: unchanged). Tests: `test_record_forms.py`,
  `test_record_forms_browser.py` (also a detached-console file), `test_form_browser.py`, `test_workbench_form_browser.py`,
  `test_workbench_forms_browser.py`, `test_admin_forms_browser.py`, `form_backend.py` (the stand-in must answer `/forms/check`: record
  the answers with a Java test through `RecordedAnswers`, `answers/forms/check-*.json`, never hand-written), `test_thin_console_guard.py`,
  `test_elements_guards.py`. Behaviour that changes for a person: a first keystroke shows no message for up to 250 ms + a round trip;
  browser tests that type and read at once need the page's "checked" mark (add `data-checked-seq` to wait on; no fixed sleeps).

## 4. Launcher impact

| Thing | Base | After i7a | Who must act |
|---|---|---|---|
| `drishti.py setup` | `envsetup.DATA_LAYOUT` makes `<data>/{run,logs,tmp,dumps,modules,delta}`; next steps print `build jar` | drop `modules` from the layout (else every fresh install shows an empty leftover); print `build all`; list leftovers and old settings via `module list` | C1 |
| `drishti.py build jar` | `mvn -q clean package -DskipTests -pl :drishti-app -am`; one jar in `lib/` | unchanged command; the jar holds no module. **A jar without matching modules is a trap**: banking is tied to the kernel build, so `build jar` alone leaves `<home>/modules/banking-*.dmod` refused at the next start. `build jar` prints that and names `build all` | A3 |
| `drishti.py build modules`, `build all` | – | Maven `-Pmodules` for modules with a pom, `modulepack` for the 8 without; `.part` + replace into `<home>/modules`; `first-party.yaml`. Kernel build from the jar **in `lib/`** here (correct: that is the jar the server will run) | A3 |
| `start server` | `serverstart.py:159` sets `DRISHTI_MODULES_HOME`; prints home, data, modules | prints the count of `.dmod`s; refuses "sources, no `.dmod`, dev off"; **refuses stale** (R2) in a checkout; passes `-Ddrishti.modules.dev` only when asked | C1 |
| `start cluster` | nodes run `<data>/stack/server.jar`, a **copy** pinned by name, size and SHA (`clusterctl.server_jar`) so a rebuild never changes the jar under a running node | modules have no such pin: a rebuild replaces `<home>/modules/*.dmod` while nodes run. Harmless for running nodes (they hold the cache), but a node restarted alone gets the old pinned jar copy + new banking → **refused** (kernel build). Pin both: copy the `.dmod`s and `first-party.yaml` to `<data>/stack/modules/` under the same rule and give every node that folder | C1 |
| `stop`, `status` | pid files | `status` prints the modules folder, each `.dmod` and loaded/refused | C1 |
| `javacmd.py` | `root_flags` adds `-Ddrishti.modules.home=<repo>/modules` for every Java command; `launch_roots` | same folder, now of packages. Every `java_cmd` user that reads packs (`resolve`, `sutra`, lake build) must say which mode: `--root` = source mode | B2 |
| `<home>/lib`, `lib/ext` | one server jar; `lib/ext` = the site's jars (`drishti.lib.path`), kept on upgrade | unchanged. Parent-first order becomes: kernel jar → `lib/ext` → module's own. A site jar in `lib/ext` now **wins over a module's copy** (it lost before): G10 covers a `lib/ext` case | B3 |
| `<home>/modules` | 12 source folders | sources **and** `*.dmod`, `first-party.yaml` (git-ignored) in a checkout; only packages in an install | A3 |
| Cache | – | `<data>/cache/modules/<id>/<version>-<hash16>/` (about 45 MB for the 12 modules, re-unpacked for every rebuilt module); per **node data folder** (`DRISHTI_NODE_DATA` for stack nodes b and c; the repository's `data/` for `drishti-main`) | B1 |
| `deploy/stack/stack.yaml` | no module setting; `drishti-main` is `home: repo` | no change to the file; `clusterctl` pins modules (above) | C1 |
| Images | `server.Dockerfile`: `COPY modules/ modules/` (sources; `.dockerignore` drops `modules/**/src`, poms, `**/target`); ECS: `COPY modules/ shipped/modules/`, `DRISHTI_MODULES_HOME=/opt/drishti/shipped/modules`; `loader.Dockerfile:48` | `COPY modules/*.dmod modules/first-party.yaml modules/`; `.dockerignore` lets those through and drops the source folders; the loader image gets the same files; the cache is written on the data volume (EFS; root file system read-only) → in-use guard where that volume is shared (S24). `container_smoke.py` builds after `build modules` | C1 |
| Console images, detached console | copy `drishti-console/`, `docs/`, `config/{packs,lenses,distributions}`; no `modules/` | unchanged (the console reads console parts from the server). `test_detached_console_browser.py` proves it | – |
| IDE run configurations | 11 + 16; one sets the modules folder | a before-launch `build modules` on the server debug configuration; a "Drishti server (dev switch)" configuration with `DRISHTI_MODULES_DEV=true` | C1 |
| systemd, `config/settings.examples/{dev,prod}.ini` | `drishti.modules.home=/opt/drishti/modules` | same key, same path; comment says "packages". Old keys named at start as *no longer read* | C1 |
| Windows | no `.exe`; Maven calls Python | `exec` with `python`; paths with spaces; locked cache jars: sweep and re-unpack note and skip; `module install` over a file the server holds: the server closes every `.dmod` after boot (ModuleFiles) — add a test that asserts no open handle | A3, B1, C2 |

**The owner's three-node stack, i6 → i7a.**

1. Stop the stack (`drishti-stack/stop.sh`; the stack is down during drills anyway).
2. `git pull`, then `python tools/drishti.py build all` (jar to `lib/`, 12 packages + `first-party.yaml` to `modules/`).
3. `python tools/drishti.py setup` lists what is left over. Start (`drishti-stack/start.sh`).

| Migrates by itself | Does not | Breaks if skipped |
|---|---|---|
| module on/off in `config/modules/<id>.yaml`; `drishti.overrides.dir` (keys are ids); lakes, identity, bindings, connectors; saved Sutras | a module switched **off or removed through Admin → Deploy** (state in `<data>/modules/registry.json`): comes back **on** unless the switch file says off (S9; the start names each) | `build jar` without `build modules`: banking, trading, market-data refused; VaR, EXP, the landings answer 404 DRS-1025 |
| – | a `.dmod` installed through the store (`<data>/modules/installed/<id>/<v>/`, e.g. one made by Pack Designer "Install on this node"): **not loaded**; re-install with `module install <file>` (the file is in `<data>/modules/store/`) | a third-party module in the old store vanishes without a failure: Health names it once as a leftover |
| – | settings `DRISHTI_MODULES_PATH`, `DRISHTI_MODULES_AUTODEPLOY`, `DRISHTI_MODULES_SHIPPED`, `DRISHTI_MODULE_DEPLOY_DIR`, `builder.pack_designer_modules_dir` in `secrets.env`/an ini: named, ignored | – |
| the console (no change to its start) | the console's own module-parts cache (`<data>/console/…`, keyed by id and version): banking 1.0.0 stays 1.0.0, so a changed console part is served **stale** unless the key gains the content hash | menus of a rebuilt module do not change until the console's cache is cleared |

## 5. Testing impact

**By lane** (what a step's merge must run; "all" = the lane's whole content).

| Lane | Reached by | Notes |
|---|---|---|
| gate (`clean package spotless:check -P api-docs`, static console and tools checks, jar into `lib/`) | every step | after A3 the gate also runs `build modules` semantics and copies packages to `<repo>/modules`; `wait_gate_jar` (`drill.sh:246`) must also wait for `first-party.yaml` |
| java21 / java25 (a copy of the committed tree, `clean verify`) | A2, A3, B1–B4, D2, E1, F1, K1–K5, X1 | the copy has no built packages: `drill.sh:448` copies the gate's jar into the copy's `lib/`; module tests take packages from the copy's own `target/dmod` stamped for the copy's own server (S4) |
| other (console, in-process, fake backend) | B4, X2, C1 | stand-ins: `module_parts_fake.py`, `server_answers.py`, `form_backend.py`, `landing_answers.py`, `conftest.py` |
| browser | A3, B2, B4, C1, F1, K4, X2 | servers started by `var_stack.py` and the stack fixtures over `<repo>/modules` packages; `SHARED_TODAY` unchanged |
| tools-a/b | A1, A2, A3, B2, C1, C2, D1 | `drill.sh:269` runs `modules/*/packs/*/tools/make_*.py --check` from the **source** tree: unchanged, and a reason source mode must exist |
| anywhere | A3, B2, C1 | S23 |
| containers | A3, B3, C1, F1 | the only golden through a packaged server until G14 exists |

**Tests that encode the old way and would keep passing.**

| Test | Why it stays green | Make it fail first |
|---|---|---|
| `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyEquivalenceTest`, all of banking's `@SpringBootTest`s | flat class path (S6) | G14; the harness registers banking through `ModuleBeans` (F1) |
| `test_genomics_walk_browser.py`, `test_connector_designer_browser.py` | skip without the thin jar (S13) | in a drill a skip for "jar not built" is a failure (`DRILL=1`) |
| `DmodPythonPackTest` | `assumeTrue(python)` | section 2 |
| any browser or console test after a pack or Sutra edit under `modules/<m>/` | the server reads the cache of the **old** package | R2 guard in `conftest.py` and in `start server` |
| `tools/test_resolved.py`, every user of `resolved.repository()` | the answer cache is keyed on folder times, not on packages (B2) | key on both; a test that rebuilds a fixture module and reads again |
| `test_release_from_other_folder.py:296-306` | asserts a **missing** modules folder stops the start; still true, says nothing about an install with sources and no package | add the "sources, no package" case and the "empty folder starts" case (G7) |
| `tools/drillkit/test_affected.py` | maps `modules/banking/packs/**` to console files only | add "→ build modules, `drishti-modules-it`" |
| `ContentModulesTest`, `ModuleToolTest` | test the Java packer the build no longer uses, if A2's deletion slips after A3 | delete in the same merge as the pom change |
| `KernelInternalsShrinkTest` (planned) | a line count holds when one package is swapped for another | use `BankingReachTest` |

**Recorded answers and stand-ins.**

| Recording / stand-in | Written by | Re-record at |
|---|---|---|
| `answers/bootstrap-{all,finance,genomics,risk}.json`, `admin-pages.json` | `ConsoleAnswersRecordTest` (`-Ddrishti.record=true`) | B4 (module versions, no Deploy page), F1 only if `assertCurrent` says so |
| `answers/landings/{trading,market-data}.json` | `TradingLandingRecordTest`, `MarketLandingRecordTest` over the stand-ins | **never in i7a**: K4 must reproduce them byte for byte; a diff is a bug in K4 |
| new `answers/forms/check-*.json` | a new record test beside `FormCheckControllerTest` | X1 |
| `module_parts_fake.py`, `server_answers.shipped_part`, `conftest.py:150-153` | hand-written to the server's shape | B4, from `ModulePublicController`'s answers |
| visual snapshots (`tests/snapshots/**`) | approved by hand | B4 (Admin → Modules, Health, white label), X2 only if a message's wording changes |
| `tools/drillkit/container_smoke.json` (VaR golden to the cent) | `container_smoke.py record` | **never**: must match unchanged |

## 6. The safe order

The plan's order has four merges that cannot be green alone: **A3** (the jar loses its modules before any server can load a package
from a folder), **B1's deletions** (readers still exist), **B2 before a decision on S1/S2**, and **E1 after B4** (B4 rewrites tests
that E1 then moves). The order below keeps the old path working until the new one is proved, then deletes the old in one increment:
"one way per job" holds at every released state, and inside the release the overlap lasts three increments (I5–I7).

Grades: **O** = Opus (deep), **S** = Sonnet (routine). Every increment: change + its tests + its docs + `CHANGELOG.md`, the pre-merge
gate, and the "done means" list. Browser files are the samples to run before handing over (whole files).

| # | Increment (plan steps) | Grade | Done means | Targeted tests | Browser samples |
|---|---|---|---|---|---|
| I0 | **V0 + guards first.** Merge the base into both branches; compile the front-loaded code; fix section 2; write the guards of section 7 that can exist now (G-surface, G-flat, R2 helper, G1/G2/G9/G13 `@Disabled` with the step that enables each) | S | both branches merged into `wip/i7a`; `ApiSurfaceTest`, `TokenScopeGuardTest`, `test_tools_guide.py` green | `test_modulepack.py`, `ModuleBootTest`, `FormCheckControllerTest`, `InlineRequestTest`, `RefusedTest`, `ApiSurfaceTest` | none |
| I1 | **A1** registered: `module pack` is the Python packer; parity with `ModuleAssembler` proved on all 12 modules (entry names and hashes) | S | `drishti.py module pack modules/trading` = the Java packer's package, entry for entry | `test_modulepack.py`, `test_drishti_cli.py`, `test_command_catalog.py`, `DmodPythonPackTest` | none |
| I2 | **Decision 1 + B1 placed**: the boot result's plain value in `drishti-api`; `ModuleBoot` fills it; source mode = `devFolders`; nothing calls it yet | O | `ModuleBootTest` + in-use, race, content-hash cases | `ModuleBootTest` | none |
| I3 | **A2**: `ModuleCli verify` (`--with`, `--conformance`); the tool jar, `ModuleTool`, `ModuleAssembler`, C15, `LeakCheck` deleted; `packaging/dist` calls the Python packer and `ModuleCli` (still inside the jar) | S | `./mvnw -pl packaging/dist -am package` green with no `ModuleTool`; S11 list re-pointed | `ModuleCliTest`, `test_content_modules.py`, `test_pack_new_source.py`, `test_pack_authoring.py`, `test_modulecli.py` | `test_pack_designer_source_browser.py` |
| I4 | **X1 + X2** (independent of modules; lane 2 from I1 on) | S | no `drishti_form-expr.js`; one `today` (S20) | `FormCheckControllerTest`, `test_record_forms.py`, `test_form_routes.py`, `test_no_form_expr_copy.py` | `test_record_forms_browser.py`, `test_form_browser.py`, `test_workbench_forms_browser.py` |
| I5 | **B2 (dual source)**: `LaunchRoots` runs the boot; readers take the boot result; the result is filled from packages in `<home>/modules` **when there are any, else from the old store and folders through one adapter class** (`LegacyModules`, the only reader of `active.yaml`, marked for deletion in I8). `drishti.module.dir.<id>` published. `resolved.py` cache key | O | G9 green with `LegacyModules` as its one exception; `pack-resolution-inventory.md` regenerated; `resolve --json --root` unchanged for a checkout | `PackResolverFixtureTest`, `ModuleOverridesTest`, `LaunchRootsTest`, `ResolveCliTest`, `DistributionsTest`, `PackReloaderTest`, `test_resolved.py`, `test_config_readers_guard.py`, `test_paths_guard.py`; G14 skeleton (flat goldens still) | `test_var_explorer_browser.py`, `test_packs.py`, `test_admin_packs_browser.py`, `test_page_walk_browser.py` |
| I6 | **A3 build + C1 launch + drill.sh**, together: modules beside the jar; `build modules|all`; launchers, stack pin, images, `.dockerignore`, run configurations, the anywhere test; `drill.sh` (gate build, `STALE_STATE`, copies, smoke) on a non-release run; R2 stale refusal | S, poms reviewed by O | G1, G5, G7 green; `drill.sh --gate`, then `--lanes anywhere,containers`; a server from `build all` answers VaR | `test_launch.py`, `test_build_modules.py`, `test_release_from_other_folder.py`, `test_java_launch_guard.py`, `tools/drillkit/test_affected.py`, `ServerJarHoldsNoModuleTest` | `test_var_explorer_browser.py`, `test_genomics_walk_browser.py`, `test_connector_designer_browser.py`, `test_detached_console_browser.py`, `test_admin_net_stack_browser.py` |
| I7 | **E1**: `testing/drishti-modules-it`; kernel tests on fixtures; G2; **G14** real (VaR golden matrix through a server on packages) | S | G2, G14 green; surefire `drishti.modules.home=none` in kernel projects | the moved tests; `ConsoleAnswersRecordTest` (current, no diff) | none |
| I8 | **B4 + C2**: the deployment machinery, the old store, `LegacyModules`, `ModuleDiscovery.readActive/pick/HOST_FOLDERS`, `FirstParty.shipped` deleted; `ModulePublicController`; Admin → Modules (mock-ups picked before the build); Pack Designer download; `module list|verify|install|remove|rollback` | S (deletion), O (the kept reads, digest) | G3, G9 with no exception; grep for `active.yaml`, `module-deploy`, `modules.path`, `installed/` returns only `ModuleBoot.LEFTOVER_FOLDERS`; recordings re-recorded once | `ModuleHostServerTest`, `GenomicsDistributionServerTest`, `VarInstalledModuleRestartTest`, `test_pack_designer_routes.py`, `test_write_endpoints_admin.py`, `test_module_parts.py`, `test_distributions.py`, `test_modules_off.py`, `test_modulecli.py`, `test_api_docs.py` | `test_snapshots_admin_browser.py`, `test_pack_designer_browser.py`, `test_module_parts_browser.py`, `test_admin_index_browser.py`, `test_home_house_browser.py`, `test_cluster_failover.py` |
| I9 | **B3**: parent-first loader, `KernelLibraries`, the warning | O | G10, G14 green; `module verify` on banking: zero library warnings; `kernel-internals.txt` −3 | `ModuleRuntimeTest`, `KernelLibrariesWinTest`, `BankingReachTest` | `test_var_explorer_browser.py`, `test_genomics_walk_browser.py` |
| I10 | **K1 + K2** (lane 2, any time after I0; merges before F1) | O | `TypedCallParityTest` incl. language and fixed-today rows | `TypedCallParityTest`, `ApiSurfaceTest`, `TokenScopeGuardTest` | none |
| I11 | **D1, D2** (D2 alone on its day) | S | `SdkBuildsGenomicsTest`; full compile; surface regenerated | all Java (`clean verify`), `ArchitectureRulesTest` | none |
| — | **Gate 1**, then **spike** (1 day, O): genomics in the kernel's context on a throw-away branch (F1 point 4) | O | the spike's findings written into F1's brief | – | – |
| I12 | **DROPPED (2026-11-17: child contexts stay; G11/G12 hold them)** ~~**F1a** genomics, **F1b** trading + market-data, **F1c** banking: three merges, alone~~ | O | G11, G12, G14 after each; mapping check; no doubled SPI bean | `OneContextTest`, `ModuleHostServerTest`, `KernelWithoutModulesTest`, banking's suite (F1c), `drishti-modules-it` | F1a: `test_genomics_walk_browser.py`; F1b: `test_edition_trading_browser.py`, `test_edition_market_browser.py`, `test_landing.py`; F1c: `test_var_explorer_browser.py`, `test_var_regulatory_browser.py`, `test_pvar_browser.py`, `test_snapshots_var_browser.py`, `test_command_sweep_browser.py`, `test_page_walk_browser.py` |
| — | **Drill A** (DROPPED, 2026-11-17: the release drill is the one) | – | plan section 8 | – | – |
| I13 | **K3** operations | O | `OperationsTest`, `OperationsClusterTest`, parity rows per gate | + `PlacementClusterTest` | `test_admin_net_stack_browser.py` |
| I14 | **K4** landings typed; loopback, `KernelCalls` deleted | S, reviewed by O | G13; landing recordings **unchanged** | `TradingLandingRecordTest`, `MarketLandingRecordTest`, `ApiSurfaceTest` | `test_edition_trading_browser.py`, `test_edition_market_browser.py`, `test_landing_back.py` |
| I15 | **MOVED to the next release (2026-11-17)** ~~**K5** (beside I13/I14, merges after I13)~~ | S | `BankingReachTest`; G14 | banking's suite | `test_var_explorer_browser.py` |
| I16 | **Z1**, then **Drill B** | S | – | – | – |

**Parallel lanes that never touch the same files** (at most three builders; one heavy job at a time).

| Lane | Increments | Files |
|---|---|---|
| 1 modules | I1 → I2 → I3 → I5 → I6 → I7 → I8 → I9 | `tools/`, `kernel/drishti-{api,module-runtime,packs,registry}`, `server/modules`, poms, `deploy/`, console admin |
| 2 API and forms | I4 → I10 → (after Gate 1) I13 → I14 | `kernel-api/services`, `server/kernelapi`, `server/edit`, `drishti_record-forms.js`, the landings |
| 3 routine | I11 (D1 after I6; D2 on a quiet day), I15, docs | `drishti-sdk/`, imports, banking's imports |

Shared files, one owner at a time: `application.yaml`, `settings_schemas.yaml`, `kernel-api-surface.txt` (lane 2 until I11; D2 takes it
for one day), `CHANGELOG.md` (second merger takes both sides), `tools/drill.sh` (I6 only).

## 7. Risks and the guards to write first

Guards are written in **I0** (disabled with the name of the increment that enables them) unless noted, so a mistake fails loudly at
the step that makes it.

| # | Risk (what breaks silently) | Guard |
|---|---|---|
| R1 | **VaR through the module path is untested** (S6): loader order, a second copy of a maths library, a missing `ServiceLoader` provider | **G14** `PackagedVarGoldenTest` (`drishti-modules-it`): the matrix of `VarGoldenMatrixTest` and the system baskets asked of a server started on packages, compared with the same golden files, exactly. Plus `container_smoke.json` unchanged |
| R2 | **Stale packages**: a source edit under `modules/<m>/` (or a file `content.yaml` copies in: lenses, the four guides) not rebuilt; tests pass on old content | `modulepack.digest(folder)` vs the package's `DMOD.MF`; `start server` in a checkout and `conftest.py` (session start) refuse: *"modules/banking changed since banking-1.0.0.dmod was built: python tools/drishti.py build modules banking"*. `tools/test_modules_fresh.py` |
| R3 | **Kernel build stamp** (S4): banking packed for another jar | `ModuleBoot` refusal already says it; add `BankingMatchesThisKernelTest` in `drishti-modules-it` and a `build modules` self-check (the stamp equals the jar in `lib/`) |
| R4 | **Class-loading order**: a module's own copy silently unused, a `lib/ext` jar now winning, resources of the kernel leaking into a module's `ServiceLoader` | G10 with three fixtures (module copy, `lib/ext` copy, a `META-INF/services` file in both); banking: zero library warnings |
| R5 | (dropped with F1; the child contexts stay) **One context**: doubled SPI beans, twice-mapped or unprefixed controller paths, bean-name clash, a kernel single-bean injection made ambiguous by a module bean | G11 extended: (a) every handler path of a module bean is under its prefix or declared paths; (b) no mapping registered twice; (c) for each kernel SPI type the injected list has no duplicate instance; (d) a fixture module defining an `ObjectMapper` bean does not change the kernel's |
| R6 | **Duplicate-jar warning missing or noisy**: artifact-name comparison misses a shaded or renamed jar | `KernelLibraries` also compares package overlap of each module jar with the kernel list for the top 20 libraries; a test with a renamed Jackson jar |
| R7 | **Resolver and cache paths in outputs**: `<data>/cache/modules/<id>/<v>-<hash16>` changes every build; a path in a recording, snapshot, generated doc or golden churns | `test_no_cache_paths_recorded.py`: no file under `tests/answers`, `tests/snapshots`, `docs/` contains `cache/modules/`; Admin → resolved paths masks the hash in snapshot pages |
| R8 | **Writes under the wrong folder**: the cache or the overrides view under `<home>` or the working folder; a test run unpacking into the repository's `data/` | `ModuleBoot` refuses a cache root not under the data folder; `test_paths_guard.py` row; the test harness sets the data folder (as `DRISHTI_RESOLVED_CACHE` is set today) and a guard asserts `data/cache/modules` is untouched by a `--gate` run |
| R9 | **Shared data folder, rolling upgrade** (S24) | in-use marker + `ModuleBootTest` case; `CLUSTER_GUIDE` says the cache is per node unless shared |
| R10 | **Cluster nodes on different content** shown as equal (same version) or different (same content, other zip bytes) (S5) | digest carries the SHA-256 of `DMOD.MF`; `OperationsClusterTest`/a digest test with two zips of one content |
| R11 | **Wall-clock dates**: form check vs save (S20); `modulepack` zip time is fixed (good); `first-party.yaml` must carry no time; the stale check must use content, never mtimes | `FormCheckTodayTest` (fixed today on both paths); `test_modulepack.py` asserts no timestamp in `first-party.yaml` |
| R12 | **Old store ignored without a word**: a third-party module or an "off" state lost on upgrade | G3 extended: `registry.json` with an OFF module → the note; an `installed/x/1.0.0` → named leftover; `module list` exit code 0 with a warning line |
| R13 | **API surface drift**: three steps rewrite the baseline | `ApiSurfaceTest` is the guard; rule: regenerate, never merge by hand; `versionIsZeroDotSomething` keeps 0.5 |
| R14 | **A second way left behind**: `ModuleAssembler` beside `modulepack`, `LegacyModules` beside `ModuleBoot`, `KernelCalls` beside `Kernel`, `Refused` twice | `test_one_way_i7a.py` (deleted at release): each pair's old name is absent from the tree from the increment that says so (I3, I8, I14) |
| R15 | **Skips counted as passes** (S13, `assumeTrue`) | the drill sets `DRILL=1`; the three tests fail instead of skipping; `drill.sh` prints the skip count of the browser lane against the last drill's |
| R16 | **Console's cached console parts** keyed by id and version only | the key gains the content hash from `/public/modules/console`; `test_module_parts.py` case: same version, new hash → fetched again |
| R17 | **Session isolation** in typed calls | `test_no_shared_user_state.py` pattern in Java: `TypedCallParityTest` runs two people at once on shared threads (as the landings' record tests do) |
| R18 | **Memory and time**: every test fork and every scratch server unpacks 45 MB and hashes the packages | budget rows in `tools/drill-budgets.txt` (boot under 2 s warm, under 8 s cold); the unpack is skipped when the folder verifies |

## 8. Decisions needed before building

| # | Decision | Recommendation |
|---|---|---|
| 1 | Where the boot result lives (S1) | A plain value in `drishti-api` (`api.resolution.ModuleHomes`: id → dir, version, content hash, origin; `current()`), filled by `ModuleBoot` (stays in `drishti-module-runtime`); `drishti-packs` gains the dependency on `drishti-module-runtime` (no cycle) so `LaunchRoots` can run the boot; child processes read `drishti.module.dir.*` |
| 2 | What `--root` / `ConfigResolution.repository` means (S2, S3) | Source mode: the checkout's module folders through `ModuleBoot`'s `devFolders` path, for tools and for tests of the shipped files. Servers under test and the drill run packages |
| 3 | Module identity in the cluster digest (S5) | SHA-256 of `DMOD.MF` (content). The file hash stays the cache key only |
| 4 | "Removed" modules: `module_removed.html`, 410, tombstones (S7) | Gone with the store: a removed module is unknown (404) and saved work that needs it says "module not installed" through `needs`. Delete the page |
| 5 | The overlap of old and new readers during the release (I5–I7) | Accept, in one adapter class with a guard, deleted in I8; never released |
| 6 | A module with no part placed on this node, in one context (S25) | Not registered (placement read from `config/placement.yaml` before the context), as off modules are; its API is forwarded by `PlacementGuardFilter` as today |
| 7 | `common.*` off banking's allowance (K5, D2) | Not in i7a: 75 files; the count becomes 43 → 39 |
| 8 | Estimate | The plan's 46 builder-days do not hold: + about 9 (S1/S2 design and adapter 2, S11 and S13 test rewrites 1.5, launcher pin, images and the anywhere test 1.5, G14 and the stale guard 1.5, the F1 spike 1, console "removed" clean-up and recordings 1.5). Cut lines unchanged; K5 is the first to go |
