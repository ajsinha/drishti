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
| `kernel/drishti-packs/.../packs/PackPaths.java:71-82` | `drishti.packs.dir`, with the `./packs` fallback | direct | A | deleted with the fallback; `ResolutionSettings.siteDir` and `kernelPacksDir` (`config/packs/core` stays where it is) |
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
| `tools/packs_to_modules.py` | the one-off move: deleted, its job is done |
| `modules/*/packs/*/tools/make_*.py` | `HERE / "pack.yaml"`, their own folder (C: no change) |

### 2.4 JavaScript

No page reads a folder. The pages fetch the console's routes (`/admin/connectors/api`, `/admin/connectors/api/stamp`,
`/admin/connectors/`, `/api/v1/packs/`; `drishti_admin-packs.js`, `drishti_admin-connectors.js`, `drishti_admin-index.js`,
`drishti_admin-watch.js`, `drishti_admin-loads.js`, `drishti_conn-designer.js`, `drishti_pivot.js`). Change: Admin → Packs and
Admin → Connectors show the new `origin` and `layer` fields; `drishti_admin-connector-templates.js` is deleted (connres already does).

### 2.5 Scripts, build and configuration

| File:line | What | Risk | Change |
|---|---|---|---|
| `tools/launch/serverstart.py` (`start server`) | warns when `config/packs` is missing | A (a site with no own packs is normal) | warn only when the kernel packs or the modules folder are missing |
| `kernel/drishti-packs/pom.xml:41-43` | copies `config/packs/default-packs.yaml` into the jar | A | removed with the file (step 3 of section 9) |
| `kernel/drishti-server/pom.xml:130` | tests' `drishti.packs.dir` = the repository's `config/packs` | B | the settings of design 18.2, each to the repository's folder |
| `kernel/drishti-server/src/main/resources/application.yaml:144,204,1180,1206` | `module-manifests-dir`, `modules.dir` (switches), `packs.dir`, `packs.enabled` | — | section 6 |
| `drishti-console/config/application.yaml:285,289,292` | the console's twins | — | section 6 |
| `tools/drill.sh:251` | globs `config/packs/*/*/tools/make_*.py modules/*/packs/... modules/banking/dmod/packs/...` | **A** (a hand-kept list of layouts) | asks `tools/drishti.py pack list --folders` (the resolver) |
| `tools/bench/pinning.sh:80` | `DRISHTI_PACKS_DIR="$REPO/packs"` (the layout of three releases ago) | **A** (already dead) | `DRISHTI_PACKS_DIR="$REPO/config/packs"` |
| `packaging/dist/pom.xml:84-112` | packs each module folder into a `.dmod` | C | none |

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
| `tools/launch/clusterctl.py` (`start cluster`), `tools/bench/*`, `tools/drillkit/lake_cache.py:94` | a private connectors folder per node or run | unchanged |

### 3.3 Scripts

| File:line | What | Change |
|---|---|---|
| `tools/launch/serverstart.py` (`start server --iceberg`) | `--iceberg` copies `config/connectors.examples/iceberg/*.yaml` into `config/connectors` | a script the administrator runs may write; it writes the **partial override** (`plugin: iceberg`), not a whole file |
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

Today **eight settings name a modules folder** and four files are found "beside" another folder. After: one setting per concept
(design 18.2), every one in `ResolutionSettings`, no setting derived from another folder's position.

| Concept | Today | After |
|---|---|---|
| The site's packs | `drishti.packs.dir` (`DRISHTI_PACKS_DIR`, `./config/packs`) + the `./packs` fallback; `drishti.packs.extra-dirs`; console `packs.dir` | `drishti.packs.site-dir` (same variable `DRISHTI_PACKS_DIR`, same default; a list is allowed). `drishti.packs.dir` is read as its former name. The `./packs` fallback and `extra-dirs` are deleted |
| The kernel's packs | inside the site's folder: `config/packs/core/` | `drishti.kernel.packs-dir`, default `./config/packs/core` **in this release** (the folder does not move; only its default changes when it does) |
| Packs deployed from Admin or a registry | `drishti.packs.installed-dir` (`<data>/packs/installed`) | unchanged; part of the site layer |
| The modules' folders | `drishti.module-manifests-dir` (`./modules`); system property `drishti.modules.dir` (`PackEntry`); `drishti.live-sets.modules-dir`; console `module_manifests_dir`; "two folders above the packs folder" (`AccessRegistries`, `distribution.py`) | **`drishti.modules.dir`** (`DRISHTI_MODULES_HOME`), default `./modules`. The others are deleted |
| The module store (`.dmod`) | `drishti.modules.path` (`<data>/modules`); `drishti.packs.modules-dir`; `drishti.module-deploy.dir`; console `builder.pack_designer_modules_dir` | `drishti.modules.path` only |
| The modules' switches | **`drishti.modules.dir`** (`DRISHTI_MODULES_DIR`, `./config/modules`) | `drishti.modules.switches-dir` (`DRISHTI_MODULES_SWITCHES_DIR`), same default. **The one renamed meaning**: see the note |
| The site's connector files | `drishti.sources.connectors-dir` (`DRISHTI_CONNECTORS_DIR`, `./config/connectors`) | unchanged |
| The site's bindings | `drishti.bindings.file`, default "the sibling of the connectors folder" | `drishti.bindings.file`, default `./config/bindings.yaml` |
| The site's inbox rules | `drishti.dataload.rules-file` (`./config/dataload.yaml`) | unchanged |
| Placement, UI kit, under-construction | found beside the connectors or packs folder | each its own setting with a plain default under `./config` |
| Which packs load | `drishti.packs.enabled` (`DRISHTI_PACKS`), `config/packs/default-packs.yaml`, `drishti.packs.added` | `drishti.packs.enabled` narrows what the resolver found (`default` = everything of the modules that are on, the kernel's `core`, the site's enabled packs); `default-packs.yaml` deleted (the manifests' `packs.list` is the list) |
| Connector generation | `drishti.sources.connectors-generate`, `drishti.connectors.template-updates`, `template-check-ms`, `settings-file` | deleted (connres) |

**Note on `drishti.modules.dir`.** The owner's name for the modules folder is today the name of the switches folder. Nothing in the
repository sets it (`bin/`, `deploy/`, `packaging/`, the drill and the test forks were searched); the default is unaffected. A site
that did set it would point the modules folder at its switch files, so the start checks: a `drishti.modules.dir` folder that holds
`<id>.yaml` files and no `<id>/module.yaml` stops the start with DRS-1047, naming both settings. If the owner prefers no renamed
meaning at all, the modules folder takes `drishti.modules.home` and `drishti.modules.dir` stays the switches: the design does not
otherwise change.

## 7. The failure analysis

### 7.1 `FORMS` lists only CORE and GLEIF

`drishti-console/core/packs.py:122-130` (`_load`) asks `pack_dir(name, root)` and keeps the answer only when
`str(pdir).startswith(str(root))`. That containment check was true by construction while every pack lived under `config/packs`.
`pack_paths.module_roots` now returns `modules/banking/dmod/packs/trading`, which is not under `config/packs`, so the loop falls
through and the pack is dropped: every module's pack, on every console page that uses `Packs` (FORMS, the switcher, Calc snippets,
the designer's kinds). The server was right (its loader flattens the roots and has no such check), so the two halves disagreed and
nothing compared them.

**Causes, in order of weight:** (1) the rule lives twice (Java, Python) with **no shared fixture**; (2) "where a pack is" is path
arithmetic at each caller instead of an answer from one object; (3) module folders entered through a folder-**name** test
(`config/packs`), which no caller can see or reason about; (4) **no test asks whether the console shows every pack the server
serves**.

### 7.2 The trade connectors vanished

The trade connector templates moved from the `trading` pack into the source modules (`modules/source-<sys>/connectors/delta.yaml`).
The server reads templates only from packs (`Pack.connectorTemplates`); only the lake loader and one tool read a module's folder.
`ConnectorBootstrap` then saw generated, unedited files (`trade-endur.yaml` …) whose template was no longer in any pack and
**retired** them (`ConnectorBootstrap.java:145-168`): no connector, no trades.

**Causes:** (1) the server **writes** site configuration from an inference ("the template is gone, so the file is obsolete"); an
absence caused by a move, a switched-off module or a wrong folder is indistinguishable from a real retirement; (2) two readers of
module connectors (loader, tool) and a third that ignores them (server); (3) no test counts trades per source system after an upgrade.

### 7.3 DRS-9302 on a blotter edit

`EditConfiguration.connectorOf` (125-139) wants exactly one **site file** with `writes:` whose `kinds` holds the kind. It cannot
succeed: (1) `ConnectorFiles` ignores a `kinds:` list (332-345), so `kinds()` is empty for every connector that is not reactive;
(2) the write-capable connectors are module templates (`modules/source-<sys>/connectors/kafka.yaml`), which the server never reads;
(3) with several source systems the match is ambiguous by design. The refusal then surfaces as the catch-all DRS-9302, the same code
as a four-eyes refusal and a read-only field: the message cannot say what to do.

### 7.4 The same class of fault, not yet seen

Every row marked **A** in sections 2 to 5. The ones most likely to be the next bug:

| # | Where | How it breaks |
|---|---|---|
| 1 | `PackEntry.modulesDir` (`PackEntry.java:85-100`) | the loader finds a pack's source-module templates by walking up to a folder that holds both `modules` and `config`. For a pack inside an installed `.dmod` (`<data>/modules/installed/...`) the walk finds no such folder, or finds the product's `./modules` and not the store: **the loader and the server can read different templates for the same connector** |
| 2 | `SourceRegistry.shapeBases` (`SourceRegistry.java:165-176`) | a module template's `map: mappings/endur-trade.yaml` is looked for under the site's connectors folder, `config/connectors`, `config`, `.`: never in the module's own folder. With templates read where they are, **a relative file must resolve against the folder of the layer that names it** (to verify first in step 5: which of today's reads of `map:` are the server's and which the loader's) |
| 3 | `menus.py:82`, `module_parts.py:252`, `distribution.py:131-160`, `house.py:50`, `tools/under_construction.py:109` | five lists of "the modules' folders", three different glob sets, none reading the store: **a module installed only as a `.dmod` has no menu, no console part and no known commands** |
| 4 | `readmes.py:71` | the literal `root / "config" / "packs"`: Help's pack README works only through the name trick, and never for an installed `.dmod` |
| 5 | `ArtifactPaths.packFolders` (88-100) | calls the two-argument `PackLoader.dirs`: Admin → Artifacts never lists a site's installed content module's packs, so their **impact analysis is empty** |
| 6 | `PackReloader.roots` (189-195) | watches the packs folder and the installed-packs folder: an edit inside `modules/installed/<id>/<version>/packs` or a module switch **does not reload** |
| 7 | `BindingsFile.besideConnectors` (64-67), `PlacementConfiguration:67` | a node whose connectors folder is not named `connectors` reads `<name>-bindings.yaml`; `tools/launch/clusterctl.py` gives each node its own `home/connectors`, so **each node of a local cluster reads its own `home/bindings.yaml`** |
| 8 | `UiKitDir`, `RachanaConfiguration:69-78`, `AccessRegistries:74-78`, `distribution.py:233` | "beside the packs folder" and "two folders above it": any site that sets `DRISHTI_PACKS_DIR` to a folder of its own loses the UI kit, the under-construction list and the module manifests |
| 9 | `PackDatasets:85`, banking `Hierarchies:66`, `VarConfiguration:303` | read `pack.manifest().connectors.<name>`: they see neither the site's override (a renamed table) nor a module's template: **VaR hierarchies and BI datasets on the template's table, the data on the site's** |
| 10 | `SourceRouter.picked` versus `BindingResolver` versus `Routes` versus `connectorOf` | four answers to "which connector serves this kind": Admin can show BOUND to one connector while the read uses another and the inbox loads a third |
| 11 | `ModuleDirs.of` (no on/off) | the loader takes rules and templates of a module that is **off**; the server skips them: data is loaded that nothing serves |
| 12 | `config/demo/bindings.yaml` copied into the site (`tools/lake/demo.py:654-659`) | six source systems frozen in a site file: a seventh source module is installed, on, and **silently not read** |
| 13 | `DataLoadConfiguration.moduleRules` (127-160) | an unreadable module rules file gives no rules and no message: its files sit in the inbox as unmatched |
| 14 | `tools/drill.sh:251`, `tools/bench/pinning.sh:80` | hand-kept globs of pack layouts; the second already points at a layout three moves old |
| 15 | `tools/lake/stores.py`, `tools/lake/demo.py`, `start server --iceberg`, `core/lakes.py bind` | write **whole** connector files: with layers, a whole file freezes today's template into the site and hides every later template change |
| 16 | `SourceSystemsWiring:109` | a source system's module guessed from the name `source-<system>`: a site's source module of another name is never switched off with its module |

## 8. The test plan

The aim: a reader that goes its own way, or a release whose layout differs, **cannot merge without a red test that names it**. Tests
1 to 4 and 9 run in the pre-merge gate of every branch (seconds to a few minutes); 5 to 8 in the drill.

### 8.1 The shared fixture (Java and Python give the same answer)

`testing/resolution-fixture/` (the one home; connres's `connector-layers/cases.yaml` moves into it):

- `cases/<case>/tree/` a small file tree (`config/`, `modules/`, `data/modules/installed/`), `cases/<case>/input.yaml` (the settings,
  which modules are on and why the others are off, `drishti.packs.enabled`), `cases/<case>/expect.yaml` (every pack with origin, state
  and folder; every effective connector flattened to dotted keys with each key's layer; every kind's read, live and write; the inbox
  rules in order with origins; the problems with code **and full message text**).
- Runners: `ResolutionFixtureTest` (drishti-api) and `tools/test_resolution_fixture.py`. Both assert they ran exactly the cases of
  `cases/INDEX.txt`, and a third assertion holds `INDEX.txt` equal to the folder listing: a case cannot be skipped by one side.
- Cases, at least: site pack alone; module pack; kernel pack; site pack with `replaces:`; site pack clashing without it; the same
  name twice in the site; leftover `config/packs/<domain>/<pack>` of the last release; module off; module only as `.dmod`; module in
  folder and store; a pack named in `DRISHTI_PACKS` whose module is off; connector: template only, override of one key, override that
  changes the plugin and inherits, `null` removes a key, unedited generated file (superseded), edited generated file, generated file
  whose module is off, site-only connector, override with `template:` whose module is off, two templates of one name; binding: site,
  shipped default from two source modules (union), dangling site binding, write by source system, write ambiguous, write none;
  inbox: site rule before module rule, off module's rule absent, unreadable rules file.

### 8.2 The guard (no reader goes its own way)

`tools/test_resolution_guard.py`, a source scan over Java main code, the console, `tools/`, `bin/`, `deploy/`:

- **Forbidden outside the resolver** (`com.ash.drishti.api.resolution`, `core/resolution.py`): the names `PackFolders`, `pack_paths`,
  `ModuleDirs`, `PackPaths.packsDir`; the retired settings of section 6; the literals `config/packs`, `config/connectors`,
  `bindings.yaml`, `dataload.yaml`, `modules/*`, `"pack.yaml"` or `"module.yaml"` on a line that lists or globs a folder
  (`Files.list`, `Files.walk`, `iterdir`, `glob`, `rglob`); `getParent()`/`.parent` applied to a packs or connectors folder.
- **Allow-list** `testing/resolution-fixture/guard-allow.txt`: one line per exception with its reason (a given folder's own
  `pack.yaml`, authoring tools that write a new pack). The test fails on an entry that matches nothing, and the number of entries is
  pinned: it can go down in a commit, never up without the number changing in the same commit.
- **The server writes nothing**: `StartWritesNothingTest` hashes every file under the site's `config/` and `<data>/connectors`, starts
  the server, reloads packs, switches a module off and on, stops, and compares: identical. The same in the upgrade test.

### 8.3 The upgrade from `v2.9.11.i_2_3`

`tools/connector_upgrade_fixture.py` grows into `tools/upgrade_fixture.py --from v2.9.11.i_2_3`: it builds, from that tag's own files
(`git archive`), what a site of that release holds: `config/packs/{core,banking,economy,capitalmarkets}/...` (one pack edited, the
rest untouched), the generated `config/connectors/*.yaml` with their stamps and markers, one per connector template of the packs that release loads
by default (its `default-packs.yaml`: a pack shipped but off, as us-equities and funds, generated no file) (one edited, one with its marker lost),
`config/bindings.yaml` (the demo copy), `config/modules/*.yaml`, a pack deployed into `<data>/packs/installed`, the old `.history`
folder. `UpgradeFromI23Test` starts this release on it and asserts:

1. no file of the site changed (the hash of 8.2);
2. the loaded packs, FORMS and commands equal a fresh install's, except the listed differences (the edited pack is **not read** and
   listed; the edited connector is an override);
3. **trades per source system > 0**, each of the six;
4. `problems` equals the expected list exactly (every leftover named with both places, nothing else);
5. the count table below matches.

The 2.9.4 fixture stays. Each release adds its own tag's fixture to the list in the release checklist.

### 8.4 The count table

`testing/resolution-fixture/product-counts.yaml`, per distribution (risk, finance, genomics, all): packs loaded, FORMS, mnemonics,
connectors running, kinds bound (by origin), kinds with a write path, inbox rules, Help pack pages, designer kinds. One test computes
them from `GET /api/v1/admin/resolution` and the console and compares. A move that loses a pack changes a number; the number can only
change by editing this file in the same commit, where a reviewer sees it.

### 8.5 Every pack visible everywhere

`drishti-console/tests/test_every_pack_everywhere.py`, driven by the resolution (a new pack is covered without touching the test), per
distribution: for every loaded pack: its FORMS are listed and **each FORM opens**; `DATAMAP` shows its kinds; `WHAT <mnemonic>`
answers for each mnemonic; `EXPORT` offers each kind; Help has its guide and README; the designer lists its kinds and Sutras;
Admin → Packs shows it with its origin. And the reverse: the console lists **no** pack the resolution does not.

### 8.6 Module off, module on

For every module that can be switched (`ModuleSwitchEverywhereTest`, with `test_engine_off_everywhere.py` as the model): off → the
difference between the two resolutions is **exactly** that module's packs, templates, default bindings and inbox rules (and those of
modules that require it); none of them appears in FORMS, DATAMAP, WHAT, EXPORT, Help, the menus, Admin → Connectors, Data bindings or
the inbox; a `DRISHTI_PACKS` entry of it answers DRS-1043. On again → the digest equals the first one. Run twice: with a restart, and
live through `POST /api/v1/admin/modules/{id}`.

### 8.7 Data and commands

- **Every demo kind non-empty**: each kind the resolution binds in the demo returns at least one row (`tools/test_empty_lakes.py` and
  `PacksServedWithoutDemoTest` take their kinds from the resolution, not a list).
- **Every mnemonic opens** and **the command sweep**: `test_command_catalog.py` and the page walk take the commands from the
  resolution; each opens without an error panel; the sweep's total is in the count table.
- **Every editable kind has a write path or says why**: each blotter with editable fields resolves `write(kind, system)` to a
  connector, or answers DRS-9308 with the candidates.
- **`.dmod` only**: `SourceModuleInstallTest` asserts the installed module's packs, templates, rules, menus and console parts all
  appear (rows 1 and 3 of section 7.4), then that they go on uninstall.

### 8.8 One answer on every node

The page walk asserts the console's digest equals the server's (`resolution.digest`); the cluster test asserts every node's digest is
equal and that Health shows a difference when one node's module folder is changed (DRS-1049).

### 8.9 Messages

Every code of design 18.9 has a fixture case whose `expect.yaml` holds the whole message; a message that stops naming both places
fails the fixture on both sides.

## 9. The build order

One implementer, one branch from `wip/i4`, one drill at the end. Each step leaves the tree compiling and the fixture cases written so
far green.

| # | Step | Done when |
|---|---|---|
| 0 | Merge connres (`14b6a9004`, `624b75c24`). **Keep**: `ConnectorLayers`, `ModuleConnectors`, the deletions (bootstrap, stamp writer, template updates, the rewrite at start, the generation calls), Admin's layer display. **Change**: section 3.1 "new" and design 18.5 | compiles |
| 1 | The fixture's cases and expectations for packs and modules (8.1), red | cases reviewed against design 18.4 |
| 2 | `com.ash.drishti.api.resolution` (settings, module folders, packs) and `core/resolution.py`; the fixture green on both sides | 8.1 for packs |
| 3 | Settings (section 6): `ResolutionSettings`, the kernel packs folder as its own setting (**no file moves**), `default-packs.yaml` and the `./packs` fallback deleted | start on the repository as it is |
| 4 | Every pack reader of section 2 through the resolver; `PackFolders`, `pack_paths`, `PackPaths`' folder half, `ModuleDirs` deleted. **First: `core/packs.py:_load` (bug 1)** | 8.2 on for packs; 8.5 green |
| 5 | Connectors: the layer rules of design 18.5 (plugin change inherits, `null` removes, `template:`, the orphan rule, the base folder of a relative file); templates by `name:` with `connectors/examples/`; the loader and the tools on the same layers; `ConnectorOverlayFold` and the legacy move deleted; the five red tests repaired | 8.1 for connectors; trades per system > 0 |
| 6 | Bindings: one resolver, the router served by it, shipped defaults from templates, the write path with DRS-9308/9309, the bindings file's own setting, the demo copy deleted | 8.1 for bindings; 8.7 write path |
| 7 | Inbox rules from the resolution; the loader process builds the same resolution | 8.1 for the inbox; `RoutesTest` |
| 8 | `ResolutionService`: refresh, the watch, the `resolution.changed` event, the consumers (design 18.7); `GET /api/v1/admin/resolution`, `drishti.py resolution show|why` | 8.6 live |
| 9 | Admin: origin on Packs, Connectors, Data bindings, Data inbox; the leftover lists (design 18.8). Columns on existing pages; the "why" panel is shown to the owner as a mock-up first | browser tests |
| 10 | The upgrade fixture and test (8.3), the count table (8.4), 8.6 to 8.9 | all of section 8 |
| 11 | Documents in the same push: DATA_GUIDE section 9 (connector layers; generation removed), ADMIN_GUIDE, OPERATIONS and DEVELOPER links, tutorials 02 and 16 (the deleted screenshot 15 still embedded), CONFIGURATION.md regenerated, DOMAIN_MODULES section 8, KERNEL_AND_MODULES sections 5.4 to 5.6 follow 18.6, Help, API_REFERENCE (the new codes), CHANGELOG | docs tests |
| 12 | The pre-merge gate, then the drill | green |

### 9.1 Four confirmations for the build

**(a) Removing the binding guesses** (*any lake connector whose lake holds the kind*; *demo samples for what nothing serves*):
confirmed for everything shipped. All 117 kinds of the 17 shipped packs are named by a pack's connector template, a route, or a
source module's template (`trade`); none needs a guess. What does rely on them, and must change in the same commit:

- `drishti-console/core/lakes.py:395-431` (`bind`, the Lakes page's "bind a table"): it PUTs a connector with a `kinds:` list the
  server ignores, so the kind is served **only** by the lake guess. It must propose a binding.
- Tests that start a server with no lakes built and expect rows from the demo sample plugins (`DemoPackWalksTest`,
  `PacksServedWithoutDemoTest`, and any API test with no lake: **not countable from the source; run the suite**). They get an
  explicit `demo: true` binding from the testkit, one file.
- `BindingResolverTest`, `SourceRouterTest`, and sections 5.5 and 5.6 of the design document (the `AUTO` state goes).
- **An upgraded site whose kinds are `AUTO` today**: they become UNBOUND, with the binding to approve offered in Health. This is the
  one place where an upgrade needs an administrator's click. Before upgrading the owner's stack: `GET /api/v1/admin/bindings` on the
  running release and look for `AUTO`. The upgrade fixture (8.3) holds one such kind so the message is pinned.

**(b) `ConnectorOverlayFold`: remove.** It writes connector files at start from an inference, the fault being removed; it exists in
`v2.9.11.i_2_3`, so every node of an upgrading site has already folded (its marker is in `<data>/connectors`); a node that never
did keeps its entries in the content store, untouched, and Health lists each (*an edit of an older release in the content store is
not applied: save it in Admin → Connectors*). The same for the `ConnectorFiles` constructor's move of `.history`/`.generated`.

**(c) A module switched at runtime**: design 18.7. The registry's change raises `module.started` / `module.stopped`; the
`ResolutionService` rebuilds the whole snapshot (never a partial patch), swaps it, and publishes one `resolution.changed` with the
difference; the pack registry, the connector manager, the binding service and the inbox each act on their part of the difference; the
console follows by the version. Order inside one change: connectors of removed packs stop **before** the packs unload; added packs
load **before** their connectors start; bindings are handed to the router last. A code module that needs a restart (section 4.10)
changes nothing here: the snapshot is built the same way at start.

**(d) Must not be missed**: the list below.

**Must not be missed** (each is silent if forgotten): rows 1, 2, 7, 9 and 11 of section 7.4; the `drishti.modules.dir` check
(section 6); `SourceRouter`'s own fallbacks deleted in the same commit as the resolver takes over (never both); the loader process
(`LoaderProcess`, a separate JVM) builds its resolution from the same settings the server passes it, including which modules are on.
