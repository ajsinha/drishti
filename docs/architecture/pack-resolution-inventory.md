<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# Configuration resolution: the inventory, the failure analysis, the test plan and the build order

**Internal.** The working paper beside the design in
[KERNEL_AND_MODULES.md section 18](KERNEL_AND_MODULES.md#18-configuration-resolution-packs-connectors-bindings-and-inbox-rules): every
reader of packs, connectors, bindings and data-inbox rules in the code as it stands on `wip/i4`, what each must change to, why the
readers disagreed, the tests that hold them together, and the order one implementer builds it in. Line numbers are those of `wip/i4`
at `e09846297`; "connres" means the paused branch `wip/i4-connres` (`14b6a9004`, `624b75c24`).

## Contents

1. [How to read the tables](#1-how-to-read-the-tables)
2. [Readers of packs](#2-readers-of-packs)
3. [Readers of connectors](#3-readers-of-connectors)
4. [Readers of bindings](#4-readers-of-bindings)
5. [Readers of data-inbox rules](#5-readers-of-data-inbox-rules)
6. [Settings: today and after](#6-settings-today-and-after)
7. [The failure analysis](#7-the-failure-analysis)
8. [The test plan](#8-the-test-plan)
9. [The build order](#9-the-build-order)

## 1. How to read the tables

- **Via**: `PF` = `PackFolders` (Java) or `pack_paths` (Python); `PR` = the loaded-pack registry (`PackRegistry`, a `Pack`'s own
  folder: safe, it follows whatever the loader resolved); `direct` = joins or lists folders itself.
- **Risk**: **A** = breaks today or with the next folder move (assumes `config/packs`, a containment check, a walk upward to
  `config/`); **B** = works only through the folder-name trick; **C** = safe once the loader is right (reads a resolved folder).
- **Change**: `→ R` = call the resolver (`ConfigResolution` in Java, `core/resolution.py` in Python; design section 18.3) and nothing
  else; the method named is the one to use.

## 2. Readers of packs

### 2.1 Java (main code)

| File:line | What it reads | Via | Risk | Change |
|---|---|---|---|---|
| `kernel/drishti-api/.../api/PackFolders.java:44-176` | the rule itself: root, domain folders, `moduleRoots` only when the root is named `config/packs` | — | B | deleted; `PackResolver` in the same package replaces it |
| `kernel/drishti-packs/.../packs/PackPaths.java:71-82` | `drishti.packs.dir`, with the `./packs` fallback | direct | A | deleted with the fallback; `ResolutionSettings.siteDir` |
| `kernel/drishti-packs/.../packs/PackPaths.java:88-146` | `extraDirs`, `installedModulePacks` (installed `.dmod` packs, **not** first-party ones) through `drishti.packs.modules-dir` / `drishti.module-deploy.dir` | direct | A | deleted; `.dmod` packs are step 2 of the waterfall for every origin |
| `kernel/drishti-packs/.../packs/PackLoader.java:65,84-105,111-128,142` | `dirs()` (installed, packs, extra), `readOne`, `folders`, `visit`: first folder holding `<name>/pack.yaml` | PF | B | `load(ResolvedPacks, names)`; no folder lists; `dirs`, `folders` deleted |
| `kernel/drishti-packs/.../packs/PackEnvironmentPostProcessor.java:39,76-100` | the start: packs dir, enabled names, module-off filter **after** loading, connector suppression | PF + registry | B | builds one `ConfigResolution` first, loads from it; the off filter moves into the resolver |
| `kernel/drishti-packs/.../packs/PackRegistry.java:41-42` | the second load of the same folders | PF | B | takes the start's `ConfigResolution` |
| `kernel/drishti-packs/.../packs/distribution/Distributions.java:112-113,183` | `drishti.packs.dir` and `drishti.module-manifests-dir` to list the kernel packs' commands | direct | A | `→ R` `packs().ofOrigin(KERNEL)` |
| `kernel/drishti-packs/.../packs/SamplePolicy` (called at `PackEnvironmentPostProcessor:77-80`) | sample packs among the folders | PF dirs | B | `→ R` `packs().all()` |
| `kernel/drishti-registry/.../registry/AccessRegistries.java:49,69-80` | the switches folder (`drishti.modules.dir` = `config/modules`); the manifests folder, else "`modules` two folders above the packs folder" | direct | A | `ResolutionSettings.modulesDir`; the two-folders-up guess deleted |
| `kernel/drishti-server/.../server/PackReloader.java:130-131,189-195` | which folders to watch for a reload: packs root, domain folders, installed | PF | **A** (module folders of an installed `.dmod` are not watched; `moduleRoots` only by the name trick) | watches `resolution.watchRoots()`; reload = `resolution.refresh()` |
| `kernel/drishti-server/.../server/api/PackAdminController.java:89,232-256,274` | Admin → Packs "on disk" list: lists folders itself | PF dirs + direct | B | `→ R` `packs().all()` with `origin`, `folder`, `replaces`, `shadowed` |
| `kernel/drishti-server/.../server/admin/ArtifactPaths.java:88-100,113` | Admin → Artifacts pack rows: `PackLoader.dirs(packs, installed)` (**no extra dirs**), area = "installed" or "packs" | direct | **A** (two-argument `dirs`: installed content modules missing) | `→ R`; area = the origin |
| `kernel/drishti-server/.../server/admin/ArtifactConfiguration.java:57` | hands the two folders to `ArtifactPaths` | direct | A | hands the resolution |
| `kernel/drishti-server/.../server/deploy/PackDeployService.java:113-114,299,373,473-476,557-561` | deploy and roll back: "is there a shipped pack of this name", its version | PF `of(packsDir, name)` | B | `→ R` `packs().find(name)`; a deployed pack is a **site** pack (design 18.4) |
| `kernel/drishti-server/.../server/registry/RegistryConfiguration.java:31`, `PackRegistryClient.java:82-84,282` | the installed-packs folder | direct | C | folder comes from `ResolutionSettings` |
| `kernel/drishti-server/.../server/cli/CliLauncher.java:83,98,175-190` | `DRISHTI_PACKS_DIR` default `./config/packs`; "is this pack outside the packs folder" | PF `roots(packsDir)` | **A** (a module's pack is "outside" unless the name trick fires) | `→ R` `packs().find(name)`; a folder given on the command line is an explicit extra root |
| `kernel/drishti-server/.../server/cli/SutraCli.java:223,682-684` | the Sutras of each pack of a root | PF `roots(dir)` | B | `→ R` when the argument is the site; a pack folder argument stays a folder |
| `kernel/drishti-server/.../server/explain/PackGuides.java:56,75-80` | a pack's guide: upward from the about file to `pack.yaml` | PR | C | none (reads the resolved folder) |
| `kernel/drishti-server/.../server/uikit/UiKit.java:71-72`, `compute/drishti-rachana/.../rachana/UiKitDir.java:34-39`, `RachanaConfiguration.java:52-53` | the UI kit "beside the packs folder" (`<packs>/../ui-kit`) | direct | **A** (anchored on `drishti.packs.dir`'s parent) | its own setting only (`drishti.ui-kit.dir`); the beside-the-packs guess deleted |
| `compute/drishti-rachana/.../rachana/RachanaConfiguration.java:69-78` | `under-construction.yaml` "beside the packs folder" | direct | **A** | its own setting only |
| `compute/drishti-rachana/.../rachana/parse/SutraBlocks.java:202-205` | the `pack.yaml` beside a Sutra's folder | direct (a given file) | C | none |
| `lake/drishti-lakeio/.../lakeconfig/LakeFiles.java:300-309` | pack tables of the lake files: every `pack.yaml` under the roots given | PF `list(root)` | B | the caller passes `packs().manifests()` |
| `lake/drishti-lakeio/.../lakeconfig/PackEntry.java:59-100` | `pack.yaml` + source-module connector templates; modules folder = system property `drishti.modules.dir` (**the same name as the server's setting for the switches folder `config/modules`**: one name, two meanings) else "walk up to a folder holding `modules` and `config`" | direct | **A** (the walk-up fails for a pack under `modules/installed/...` or any pack whose ancestors have no `config`; the property means two things) | reads `ModuleContributions` from the resolution; no walk-up, no property |
| `lake/drishti-lakeio/.../lakeconfig/ModuleDirs.java:38-74` | module folders: `<modules>/<id>` then `installed/<id>/<version>` | — | C (the right list, but ignores on/off and misses `<id>/dmod`) | becomes the module half of the resolver (`ModuleFolders`), in drishti-api |
| `lake/drishti-lakeload/.../dataload/Routes.java:571`, `lakeload/ChildKinds.java:55`, `PackEntry.java:111,135,252` | a given pack folder's `pack.yaml` | PR / given | C | none |
| `kernel/drishti-module-runtime/.../module/tool/ModuleAssembler.java:230,261` | the packs inside a module being packed | given folder | C | none |
| `kernel/drishti-server/.../server/deploy/PackArchive.java:313-318`, `PackDiffer.java:76` | an uploaded archive's `pack.yaml` | given folder | C | none |

Everything else in the server (FORMS, DATAMAP, WHAT, EXPORT, Help's pack pages, the designer's kinds, the contracts API, the glossary,
samples) reads `Pack` objects from `PackRegistry` (`PR`): correct as soon as the loader is.

### 2.2 Python: the console

| File:line | What it reads | Via | Risk | Change |
|---|---|---|---|---|
| `drishti-console/core/pack_paths.py:32-101` | the rule's twin (`REPO_PACKS`, `roots`, `module_roots`, `pack_dirs`, `pack_dir`, `manifests`) | — | B | deleted; `core/resolution.py` replaces it |
| `drishti-console/core/packs.py:122-130` (`_load`) | each active pack's files, **kept only when `str(pdir).startswith(str(root))`** | PF | **A: today's FORMS bug** (a module's pack is never under `config/packs`) | `→ R` `packs.find(name).folder`; the containment check is the resolver's (inside its own origin's folder) |
| `drishti-console/core/packs.py:36-40` | `config/packs/default-packs.yaml` for the fallback list | direct | A | the list comes from the distribution + manifests (design 18.4); the file is deleted |
| `drishti-console/core/edition.py:410-413,432-434,455` | `packs.dir`, `module_manifests_dir`, `default-packs.yaml` | direct | A | `resolution.settings`; no default-packs file |
| `drishti-console/core/distribution.py:88,131-160,196-198,230-238,315-345,397-406` | module manifests (lists `modules/*`, `*/dmod`, parts); kernel packs' commands; `modules_dir or packs_dir.parent.parent / "modules"` | PF + direct | **A** (the parent-parent guess; lists module folders itself) | `→ R` `modules.all()`, `packs.of_origin("kernel")` |
| `drishti-console/core/house.py:37-50,79` | module manifests and titles | direct | B | `→ R` `modules.all()` |
| `drishti-console/core/menus.py:37,81-82` | `modules/*/module.yaml`, `*/dmod/module.yaml`, `*/src/dmod/module.yaml` | direct | **A** (installed `.dmod` modules have no menu) | `→ R` `modules.on()` |
| `drishti-console/core/module_parts.py:252` | `shipped.glob("*/module.yaml")` + `*/src/dmod/module.yaml` (no `*/dmod`) | direct | **A** (a third list of module folders, different from the other two) | `→ R` |
| `drishti-console/core/facts.py:268-271` | every pack's facts file | PF | B | `→ R` `packs.all()` |
| `drishti-console/core/readmes.py:71` | `pack_dir(name, root / "config" / "packs") / "README.md"` | PF with a literal | **A** (Help's pack README: works only by the name trick) | `→ R` `packs.find(name).folder` |
| `drishti-console/routes/terminal_routes.py:127`, `routes/admin_routes.py:966-974` | known commands; the distributions page | PF via `distribution` | B | follow `distribution.py` |
| `drishti-console/routes/data_routes.py:118` | `docs/contracts/packs/<pack>.md` | direct (docs) | C | none (generated docs; the generator is in 2.3) |
| `drishti-console/routes/pack_routes.py:378-379` | the pack designer's target modules folder (`DRISHTI_MODULES_PATH`, `<data>/modules`) | direct | B | `resolution.settings.modules_dir` |
| `drishti-console/core/datamap.py:85,133-134`, `core/build_types.py:127`, `routes/functions_routes.py:47-88`, `routes/render_routes.py:48` | the server's pack list (API answers) | server | C | none |
| `drishti-console/core/schemakit/build.py:379-436`, `routes/ship_routes.py:68`, `core/packdesigner.py` | **write** a new pack into a work folder | own folder | C | none |

### 2.3 Python: tools and generators

All go through `pack_paths` (PF, risk **B**) unless noted; each changes to `from core.resolution import packs` with the repository's
resolution (every module on, the *all* distribution), which is what a generator means.

| File(s) | What it reads |
|---|---|
| `tools/contracts.py` (57 references) | every `pack.yaml`, contracts, the generated `docs/contracts/` pages |
| `tools/packgen/common/packbuild.py`, `readme.py`, `guide_data.py`, `tools/packgen/readme.py`, `childkinds.py` | pack folders for the generated guides, READMEs, child kinds |
| `tools/packgen/banking/make_{data,docs,packs,sutras}.py`, `ccr/`, `sensitivity/`, `var/`, `publicsector/make.py`, `gleif/make.py`, `us_equities/make.py` | each generator's own pack folder |
| `tools/lake/build.py`, `demo.py`, `feedroute.py`, `stores.py`, `tools/build_demo_lakes.py`, `tools/packlake.py` | pack routes and connectors for the demo lakes (also in section 3) |
| `tools/feeds/feedload.py`, `feedreadme.py`, `pullkit.py`, `sources/{funds,gleif,us_equities,world_economy}.py` | a feed's pack folder |
| `tools/testdata/make_testdata.py`, `capture_market.py`, `sample_docs.py`, `tools/samplegen/layout.py` | samples under a pack |
| `tools/command_catalog.py`, `settings_catalogue.py`, `under_construction.py:109` (`modules/*/module.yaml` glob: **A**), `tools/drishti_parser.py` | commands, settings, menus across packs and modules |
| `tools/realdata/packrun.py`, `pack_tests.py`, `tools/bench/net_soak.py`, `bench/trades.py` | packs for runs and benchmarks |
| `tools/drishti.py` (14), `packmake.py`, `packbundle.py`, `packdiff.py`, `packform.py`, `packregen.py`, `packreg/packreg.py`, `authoring.py`, `ingest_jsonl.py`, `packgen/pack_from_jsonl.py`, `module_from_jsonl.py` | the pack CLI: mostly a folder the user names (C); the "find the shipped pack" lookups are B |
| `tools/packs_to_modules.py` | the one-off move | — | deleted (its job is done) |
| `modules/*/packs/*/tools/make_*.py` | `HERE / "pack.yaml"` (their own folder) | C |

### 2.4 JavaScript

No page reads a folder. The pages fetch the console's routes (`/admin/connectors/api`, `/admin/connectors/api/stamp`,
`/admin/connectors/`, `/api/v1/packs/`; `drishti_admin-packs.js`, `drishti_admin-connectors.js`, `drishti_admin-index.js`,
`drishti_admin-watch.js`, `drishti_admin-loads.js`, `drishti_conn-designer.js`, `drishti_pivot.js`). Change: Admin → Packs and
Admin → Connectors show the new `origin` and `layer` fields; `drishti_admin-connector-templates.js` is deleted (connres already does).

### 2.5 Scripts, build and configuration

| File:line | What | Risk | Change |
|---|---|---|---|
| `bin/start-server.sh:166` (+ `.ps1`) | warns when `config/packs` is missing | A (a site with no own packs is normal) | warn only when the kernel packs or the modules folder are missing |
| `kernel/drishti-packs/pom.xml:41-43` | copies `config/packs/default-packs.yaml` into the jar | A | removed with the file |
| `kernel/drishti-server/pom.xml:130` | tests' `drishti.packs.dir` = the repository's `config/packs` | B | `drishti.home` = the repository root (one setting; design 18.2) |
| `kernel/drishti-server/src/main/resources/application.yaml:144,204,1180,1206` | `module-manifests-dir`, `modules.dir` (switches), `packs.dir`, `packs.enabled` | — | section 6 |
| `drishti-console/config/application.yaml:285,289,292` | the console's twins | — | section 6 |
| `tools/drill.sh:251` | globs `config/packs/*/*/tools/make_*.py modules/*/packs/... modules/banking/dmod/packs/...` | **A** (a hand-kept list of layouts) | asks `tools/drishti.py pack list --folders` (the resolver) |
| `tools/bench/pinning.sh:80` | `DRISHTI_PACKS_DIR="$REPO/packs"` (the layout of three releases ago) | **A** (already dead) | `DRISHTI_HOME="$REPO"` |
| `packaging/dist/pom.xml:84-112` | packs each module folder into a `.dmod` | C | adds the kernel packs to the kernel jar (design 18.4) |

### 2.6 Tests

41 Java test files and 111 Python test files name `config/packs`, `modules/<id>/packs` or `"packs"` paths; 16 Java tests call
`PackFolders`. They change with the code they test; the ones that *are* the layout's guard are rewritten, not patched:
`tools/test_pack_layout.py` (the only layout guard today; there is no test of `PackFolders` or `pack_paths` themselves, and none
that a console reader sees a module's pack); `tools/test_no_hidden_names.py` is kept. Section 8 names the new guards.

## 3. Readers of connectors

Where the definitions are today: the site's files `config/connectors/*.yaml` (git-ignored, written at first start), their state in
`<data>/connectors/{history,generated}`, a pack's `connectors:` block, a source module's `connectors/` folder, and (deprecated)
`drishti.sources.connectors.<name>.*` properties. **On `wip/i4` the server never reads a source module's `connectors/` folder**: only
the lake loader (`PackEntry.manifest`) and `tools/packlake.py with_modules` merge it. That is the second bug (section 7.2).
"connres" = the paused branch already changes or deletes it; **new** = this design adds to connres.

### 3.1 Java (main code)

| File:line | What it reads or writes | Writes at start? | Change |
|---|---|---|---|
| `kernel/drishti-packs/.../ConnectorFiles.java:167-290,461` | the file store: `names`, `misnamed`, `read`, `readAll`, etag, render | **yes**: the constructor moves `.history`/`.generated` beside the files into `<data>/connectors` (175-179) | kept as the **site layer's** reader; the constructor's move deleted (**new**: old folders are listed in Health, never moved) |
| `ConnectorFiles.java:484,592,606,501-541,558-587` | `write`, `delete`, `backup`, `.bak` beside, `importRevision`, markers | through the callers below | `write`/`delete`/`backup` stay for **Admin's own saves** only; `keepBeside`, `importRevision`, `writeMarker` deleted (connres deletes `writeMarker`; **new**: the rest) |
| `kernel/drishti-packs/.../ConnectorBootstrap.java:73-433` | generate (122), retire (145-168), upgrade (176-296), migrate pack overrides (342-433) | **yes** | deleted (connres) |
| `kernel/drishti-packs/.../TemplateStamp.java` | the stamp that says "generated, never edited" | no | connres deletes the class; the **read** of an old stamp or marker stays inside `ConnectorLayers` (it tells an unedited generated file) |
| `kernel/drishti-packs/.../PackEnvironmentPostProcessor.java:72-73,96-112,138-150` | builds `ConnectorFiles`; runs the bootstrap; publishes files as `drishti.sources.connectors.*`; suppression by module manifest; the bindings file "beside the folder" | **yes** (101-103) | connres: through `ConnectorLayers`. **new**: the layers come from `ConfigResolution.connectors()`; suppression is the resolver's (a module off = its templates absent) |
| `kernel/drishti-packs/.../PackLoader.java:55,302-330` | skips a pack template when a file of that name exists; emits properties and `defaults.<kind>` | no | connres: `withModuleConnectors`. **new**: templates come from `ModuleContributions`; the loader stops knowing connector files |
| `kernel/drishti-packs/.../Pack.java:72-89` | `connectorTemplates()`, `connectorRefs()` | no | kept |
| `kernel/drishti-packs/.../PackSettings.java:102,154-158` | per-pack `connectors` overrides of an older release (`<data>/packs/settings/<pack>.yaml`) | no | read-only: listed in Health as "not applied"; the fold into files went with the bootstrap |
| `kernel/drishti-engine/.../SourceRegistry.java:165-176,501` | bases for a relative `map:` file: connectors dir, `config/connectors`, `config`, `.`; secrets | no | risk **A**: a source module's `map: mappings/x.yaml` must resolve against **the template's own folder**: `Effective.baseDir` (**new**) |
| `kernel/drishti-server/.../connectors/ConnectorsConfiguration.java:36-77` | the beans; `template-updates` settings; **`new ConnectorOverlayFold(...).run()`** (71) | **yes** (the fold) | connres removes the template-updates settings; **new**: the fold deleted (design 18.5) |
| `connectors/ConnectorOverlayFold.java:62-165` | content-store edits of an older release folded into files, once per node | **yes** | deleted; an unfolded store entry is listed in Health |
| `connectors/ConnectorManager.java:114-300,381-770,1087-1262,1299,1380` | read, watch, list, detail, save, enable, delete, reset, restore, `createFromTemplate`, test, preview | no | connres: through the layers; save writes only changed keys, reset deletes the override. **new**: `createFromTemplate` deleted (no caller); watch = `resolution.watchRoots()` |
| `connectors/TemplateUpdates.java`, `TemplateUpdatesController.java` | the background loop that rewrites files from templates | **yes** (after start) | deleted (connres) |
| `connectors/ConnectorController.java:63-209`, `IndexReloadController.java:47`, `ConnectorFileDrift.java:50-53` | REST; drift digest of the files | no | kept; the digest covers the **effective** connectors, so two nodes with different module versions differ visibly |
| `server/PackReloader.java:356,602-615` | a hot reload re-runs the bootstrap | on reload | connres removes the call; **new**: reload = `resolution.refresh()` |
| `server/deploy/PackDeployService.java:311-339`, `api/PackDeployController.java:131` | "create connectors" for a deployed pack | on request | connres removes generation; the report shows the layers |
| `server/lakes/LakeRelocateOnStart.java:85-95`, `lake/.../LakeFiles.java:553-680` (`adoptDemo`) | rewrites connector files to `lake: demo` | **yes** | connres removes the rewrite; `lake migrate-config` (a command the administrator runs) stays |
| `server/loads/PackDataLoader.java:100-109` | one connector's settings for a browser load | no | `resolution.connectors().find(name)` |
| `server/admin/ArtifactPaths.java:59-80,192`, `ArtifactService.java:305-307`, `ArtifactCatalog.java:132`, `ArtifactImpact.java:167` | Artifacts: the connectors folder, archive of a file | on request | rows carry the layer; only a **site** file can be archived |
| `server/edit/EditConfiguration.java:73-139`, `ConnectorWrites.java:65-210`, `EditAdminController.java:125,155` | the write connector of a kind; `writes:` of the definitions; the write-back switch | no | section 4 (the write path) |
| `server/placement/PlacementConfiguration.java:67`, `server/bindings/BindingsConfiguration.java:48`, `packs/bindings/BindingsFile.java:64-67` | `placement.yaml` and `bindings.yaml` "beside the connectors folder" | no | risk **A** (a file found by another folder's position): each from its own setting |
| `server/cli/CliLauncher.java:142,206-209` | offline CLI: generation off, a private temp connectors folder | no | nothing to switch off; the site folder is read where it is, no temp copy |
| `server/modules/ModuleAdminController.java:225`, `registry/ModuleManifest.java:86`, `EmbeddedAccessRegistry.java:127`, `module-runtime/.../ModuleConformance.java:420-440`, `DmodManifest.java:67,227` | a manifest's `connectors:` (names; folder); the no-secrets check | no | kept; the folder is read by `ModuleContributions` |
| `server/bi/layer/PackDatasets.java:85`, `modules/banking/.../var/Hierarchies.java:66`, `VarConfiguration.java:303` | `pack.manifest().connectors.<name>` directly | no | risk **A**: they see the pack's template only: not the site's override, not a source module's template. `resolution.connectors().find(name)` |
| `lake/.../PackEntry.java:53-120` | merges module templates into a pack manifest; the site file `$DRISHTI_CONNECTORS_DIR/<c>.yaml` else `<root>/config/connectors/<c>.yaml` wins whole | no | connres: `ModuleConnectors`. **new**: the loader calls the same `ConnectorLayers` as the server (today it is a second, simpler merge) |
| `lake/.../PackEntry.java:134-300` and its callers (`LoaderProcess:114,170`, `Routes:530-578`, `ReadyDesk:220-254`, `ReadyChecks:149,323,362`, `LoadConfig:336,396`, `LakeLoad:66`) | the load route of a kind | no | take `ConfigResolution` (built once per loader process) |

### 3.2 Python

| File:line | What | Change |
|---|---|---|
| `drishti-console/routes/connector_routes.py:33-176`, `connector_designer_routes.py:58-76`, `pack_routes.py:343`, `core/lakes.py:390-431`, `routes/lake_routes.py:147-186`, `core/build_types.py:66`, `routes/admin_routes.py:797`, `routes/changes_routes.py:129` | the server's REST only: no file access | show `layer`, `origins`, `templateFrom`, `superseded`; the template-updates route deleted (connres) |
| `drishti-console/core/lakes.py:395-431` (`bind`) | PUTs a **whole** generated Delta connector with a `kinds:` list the server ignores | proposes a binding (`/admin/bindings`) and PUTs only the changed keys |
| `tools/serverconnectors.py:64-156`, `tools/serverpacks.py:214` | REST | none |
| `tools/lake/stores.py:74-76,258,345-359,425-448` | regex `plugin:`; **rewrites whole connector files** to switch store; history copies | writes a **partial override** (`plugin: iceberg` + the changed keys) through `core/resolution.py`; no regex; needs the plugin-change rule (design 18.5) |
| `tools/lake/demo.py:142,610-660` | **writes `trade-<sys>.yaml`** for module-brought connectors; copies `config/demo/bindings.yaml` | writes no file for a connector whose template exists; only the overrides the demo needs; no bindings copy (section 4) |
| `tools/lake/build.py:177-215` (`site_set`, `pack_defaults`), `tools/packlake.py:153-175` (`with_modules`), `tools/lake/feedroute.py:146-232` | the site file else the module template (a third merge); table → pack, kind, connector | `resolution.connectors.find(name)`, `resolution.bindings.read(kind)` |
| `tools/build_demo_lakes.py:281-449`, `tools/lake/lakeconfig.py:224-270` | `--connectors-dir` passthrough; `migrate-config` | unchanged |
| `tools/packgen/module_from_jsonl.py:76-80,251-267`, `source_module.py:112-172` | write a new module's `connectors/`, `dataload.yaml`, `data/needs.yaml`, and a site binding | kept (authoring is a write the user asked for); a source module's template names its `kinds:` and `writes:` (they are read again, design 18.6) |
| `tools/connector_upgrade_fixture.py:134-156` | builds old-release connector files from a git revision | kept; gains `v2.9.11.i_2_3` (section 8.3) |
| `tools/connector_readmes.py:43`, `tools/settings_catalogue.py:115` | `config/connectors.examples/` | kept |
| `bin/lib/cluster.py:315-316,356`, `tools/bench/*`, `tools/drillkit/lake_cache.py:94` | a private connectors folder per node or run | unchanged |

### 3.3 Scripts

| File:line | What | Change |
|---|---|---|
| `bin/start-server.sh:39,189-194` (+ `.ps1:37,178`) | `--iceberg` copies `config/connectors.examples/iceberg/*.yaml` into `config/connectors` | a script the administrator runs may write; it writes the **partial override** (`plugin: iceberg`), not a whole file |
| `deploy/docker/ecs/server.Dockerfile:38`, `deploy/stack/stack.yaml:48-58` | `DRISHTI_CONNECTORS_DIR` | still read (section 6) |
| `kernel/drishti-server/pom.xml:141`, `modules/banking/banking-service/pom.xml:100` | a connectors folder per test fork | unchanged |

### 3.4 Tests

Deleted with their classes (connres): `ConnectorBootstrapTest` (35 cases), `TemplateStampTest`, `TemplateUpdatesTest`,
`test_admin_connector_templates*.py`. Deleted by this design: `ConnectorOverlayFoldTest`. To repair (connres left them red):
`ConnectorManagerTest`, `SutraCliTest:312`, `CliLauncherScratchTest`, `LakeRelocateOnStartTest`, `ConnectorUpgradeTest`. About 75 more
Java tests and 35 Python test files only set a connectors folder: they follow the setting.

## 4. Readers of bindings

How a kind finds its connectors today is decided in **three places that do not agree**: `BindingResolver` (what Admin and Health
report), `SourceRouter.picked` (what a read really uses) and `EditConfiguration.connectorOf` (what a write uses). None of the three
asks the modules.

| File:line | What it reads or decides | Risk | Change |
|---|---|---|---|
| `kernel/drishti-packs/.../bindings/BindingsFile.java:41,56-137` | reads and writes `bindings.yaml`; default = "the sibling of the connectors folder" (`config/connectors` → `config/bindings.yaml`; **any other folder name → `<name>-bindings.yaml`**) | **A** | its own setting, `drishti.bindings.file`, default `./config/bindings.yaml`; the sibling rule deleted. Writes stay: only an approved change in Admin writes it |
| `.../bindings/Bindings.java:46-310` | the model: `read`, `union`, `live`, `write{lake,table}`, `demo`, `note` | C | `write` gains the connector form (design 18.6) |
| `.../bindings/BindingResolver.java:138-232` | the reported resolution: site → pack route → any real source → demo → unbound | **A** (differs from the router) | becomes the **only** resolution; the router is handed its answer |
| `.../bindings/DataNeeds.java:1-168` | modules' `data/needs.yaml`, filtered by module on | C | reads through `ModuleContributions` |
| `kernel/drishti-packs/.../PackEnvironmentPostProcessor.java:124-130,149-154` | validates the bindings file at start (warning only) | C | part of `ConfigResolution.problems()` |
| `kernel/drishti-server/.../bindings/BindingsConfiguration.java:45-62`, `BindingService.java:113-148,197-257,322-371`, `BindingController.java:62-90` | refresh by etag every 30 s; `router.bind(readOrder)`; propose and approve (four eyes) | C | `BindingService` serves `resolution.bindings()`; refresh on the resolution's change event as well as the timer |
| `kernel/drishti-engine/.../source/SourceRouter.java:55-76,95-103,163-171,220-251` | the real read order: bound → `routes[kind]` → `defaults[kind]` → default route → every plugin that serves the kind → lake convention; then drops connectors of a switched-off source system | **A** (a second resolver; `defaults.<kind>` comes from properties `PackLoader` writes) | keeps only `bound`: it serves what `BindingResolver` resolved; steps 3-5 of its own deleted |
| `kernel/drishti-server/.../edit/EditConfiguration.java:73-90,102-121,125-139` | the **write** connector: the form's `save.connector`, else the single site file with `writes:` whose `kinds` holds the kind, else by the record's `SOURCE_SYSTEM` | **A: DRS-9302** (section 7.3) | `resolution.bindings().write(kind, sourceSystem)` |
| `kernel/drishti-server/.../edit/ConnectorWrites.java:158-186` | is the connector's `writes:` command usable | C | reads the effective connector |
| `kernel/drishti-server/.../modules/host/KernelModuleContext.java:106-109` | `writer()` always throws "no write binding on this server" | — | out of scope: the produced-kind writer (`write: {lake, table}`) is displayed, not built; stated in the design |
| `kernel/drishti-packs/.../ConnectorFiles.java:327-345,748` | a connector file's `kinds:` list is **ignored with a warning** (only `plugin: reactive` may map kinds) | **A** (templates carry `kinds: [trade]` and nothing reads it for binding) | a **template's** `kinds:` is read as the shipped default binding; a site file's `kinds:` stays ignored |
| `kernel/drishti-packs/.../Pack.java:74-100`, `PackLoader.java:191,302-318` | `routes:` and the template names per kind, published as `drishti.sources.routes/defaults` properties | B | become `ModuleContributions.defaultBindings()`; the properties deleted |
| `kernel/drishti-server/.../sources/SourceSystemsWiring.java:94-110` | a source system's module by the name pattern `source-<system>` | B | the module is the one whose manifest brings the connector (`Effective.templateFrom`) |
| `kernel/drishti-server/.../connectors/ConnectorManager.java:101-110,603,1331-1336` | `bindings.kindsOf(name)` for the kinds shown | C | from the resolution |
| `config/demo/bindings.yaml`, `tools/lake/demo.py:654-659` | the demo's trade binding, **copied** into the site's `config/bindings.yaml` when absent | **A** (a list of six source systems frozen into a site file: a seventh module never joins it) | deleted: the source modules' templates name `kinds: [trade]` and `union: true`, which is the shipped default (design 18.6) |
| `drishti-console/routes/admin_routes.py:990-1031`, `connector_designer_routes.py:58-64`, `core/admin_forms.py:434-483` | REST only | C | show each binding's origin (site, module, convention, demo) |
| `tools/packgen/module_from_jsonl.py:252-267` | writes `<site>/bindings.yaml` for a new module | C | kept (authoring) |

Tests: `BindingsTest` (21), `BindingResolverTest` (8), `BindingServiceTest` (4), `BankingBindingsTest`, `SourceRoutingTest`,
`WriteBackFlowTest:194`, `SourceRouterTest`, `test_admin_bindings.py`, `tools/test_pack_new_source.py`.

## 5. Readers of data-inbox rules

| File:line | What it reads | Risk | Change |
|---|---|---|---|
| `lake/drishti-lakeload/.../dataload/DataLoadSettings.java:20-88` | `drishti.dataload.*`; `rulesFile` default `./config/dataload.yaml` | C | kept: the site's rules file is this one setting |
| `lake/.../dataload/Routes.java:107-190,340-430,498-602` | the site's rules, then each module's (`Packs.moduleRules()`), then the `.ready` file, then `pack.yaml`; a rule with no connector takes "the first that serves the kind" in `pack.yaml`; **never asks the bindings** | **A** (a fourth answer to "which connector serves this kind") | the rules come from `resolution.inboxRules()`; a rule with no connector takes `resolution.bindings().read(kind)` |
| `lake/.../dataload/Cycle.java:186`, `InboxStatus.java:87`, `ReadyDesk.java:191` | the three callers of `Routes.read`, every cycle | C | pass the resolution's current snapshot |
| `kernel/drishti-server/.../dataload/DataLoadConfiguration.java:127-195` | module rules: **`drishti.live-sets.modules-dir`** (default `modules`) → `ModuleDirs.of` → each `module.yaml`'s `dataload:`; off modules skipped; **an unreadable file silently gives no rules** | **A** (a fourth setting for the modules folder; a silent skip) | `resolution.inboxRules()`; an unreadable rules file is a problem in Health (DRS-1048), never silent |
| `kernel/drishti-server/.../liveset/LiveSetConfiguration.java:161`, `LiveSets.folders` | blotter declarations: the same `drishti.live-sets.modules-dir`, filtered by `DomainFolders.on(env)` | **A** (the same folder under another filter) | `resolution.modules().on()`; the setting deleted |
| `lake/.../lakeconfig/ModuleDirs.java:38-110` | every module folder, **no on/off** | B | becomes `ModuleFolders` inside the resolver; callers get only modules that are on |
| `kernel/drishti-server/.../sources/SourceSystemReadyRule.java:18-47` | checks of a pushed file by source system | C | none |
| `drishti-console/routes/admin_routes.py:385-501`, `tools/dataloadcli.py:131-238` | REST only | C | the inbox page shows each rule's origin (site, module `<id>`) |
| `tools/lake/feedroute.py:43-232`, `tools/feeds/feedload.py:51-60,188-231`, `tools/lake/build.py:254-283` | the `.ready` twin; **does not read** `config/dataload.yaml` or a module's rules | B | `resolution.inbox_rules()` (the Python twin) |
| `tools/settings_catalogue.py:157,454` | `config/dataload.yaml` for the settings document | C | none |

Tests: `RoutesTest` (20), `NewLakeKeysTest` (20), `InboxStatusTest`, `DataInboxLoaderTest`, `LakeRoutingTest`, `CycleTest`,
`ReadyDeskTest`, `SourceModuleInstallTest` (13), `SourceModuleEndToEndTest`, `tools/test_dataloadcli.py`, `tools/lake/test_feedroute.py`,
`drishti-console/tests/test_dataload_inbox*.py`.

## 6. Settings: today and after

<!-- SETTINGS -->

## 7. The failure analysis

<!-- FAILURE -->

## 8. The test plan

<!-- TESTS -->

## 9. The build order

<!-- ORDER -->
