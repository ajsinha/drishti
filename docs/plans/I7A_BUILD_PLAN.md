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
# Build plan: release i7a, modules end to end

**Status: plan, nothing built.** Branch `wip/i7a` (worktree `drishti-i7a`), cut from `wip/i6` at `12443840c`. Every choice is made
here. Where the code on the day differs from what a step says, the step says **STOP**: report, do not choose.

**Scope (three items).**

| # | Item | Source |
|---|---|---|
| I | Every module is a `.dmod` file in `<DRISHTI_HOME>/modules`; the server jar holds no module; one Spring context | `MODULE_JARS_I5_DESIGN.md` (branch `origin/wip/i5-module-jars-design`), steps M1–M7, with the owner's later decisions (section 1) |
| II | A typed kernel API for modules; the loopback path deleted | owner, this release |
| III | `drishti_form-expr.js` deleted; the server checks a form draft as people type | owner, this release |

**Model grades.** deep = Opus · standard = Sonnet · routine = Haiku. Set on every Agent call. At most two builders at once.

## Contents

1. [Decisions this plan rests on](#1-decisions-this-plan-rests-on)
2. [What i6 already changed (overlap)](#2-what-i6-already-changed-overlap)
3. [Rules for every step](#3-rules-for-every-step)
4. [The steps](#4-the-steps)
5. [Order, lanes and what runs in parallel](#5-order-lanes-and-what-runs-in-parallel)
6. [Front-loaded now, and what waits for released i6](#6-front-loaded-now-and-what-waits-for-released-i6)
7. [Be mindful](#7-be-mindful)
8. [The two drills](#8-the-two-drills)
9. [Estimate](#9-estimate)
10. [Questions for the owner](#10-questions-for-the-owner)

## 1. Decisions this plan rests on

Owner's decisions (they override the design where they differ) and the choices this plan adds (marked **plan**; each is also a
question in section 10, and the build proceeds on the recommendation).

| # | Decision |
|---|---|
| D1 | `.dmod` files live flat in `<DRISHTI_HOME>/modules` (`DRISHTI_MODULES_HOME`, `drishti.modules.home`). Not `lib/modules`. Discovery lists `*.dmod` there and nothing else |
| D2 | Unpacked copy: `<data>/cache/modules/<id>/<version>-<first 16 hex of the file's SHA-256>/`; addressed, never listed; swept at every start |
| D3 | Two files of one id: neither loads, Health says which. Add, upgrade, remove = file + restart. Modules folder read-only to the server by default |
| D4 | The kernel provides all libraries, parent first, always. A `.dmod` may carry its own in `lib/`. A copy the kernel already has: the module **loads with a warning** (pack and verify output, server log, Admin → Health) |
| D5 | One class loader per module. One Spring context (the kernel's). Spring only from the kernel. A module whose bean fails while starting stops the start, named |
| D6 | One module layout (`modules/<m>/{module.yaml, packs, …, java/<project>, pom.xml only with Java}`, `module-layout.yaml`): done in i5, unchanged |
| D7 | Compile only with Maven. Package only with the Python packager (`drishti.py module pack`), which Maven calls in `package`. No `drishti-module-tool.jar` |
| D8 | Built `.dmod` files land in `<home>/modules`, as `build jar` puts the jar in `<home>/lib` |
| D9 | Operator commands: `drishti.py module list|verify|install|remove|rollback`. Python only, no `.sh`/`.ps1` |
| D10 | `installed/`, `store/`, `shipped/`, `active.yaml` deleted, with the settings `drishti.modules.path`, `drishti.module-deploy.*`, `drishti.modules.shipped`, `drishti.modules.autodeploy*` |
| D11 | Banking stays tied to the exact kernel build. Dev switch `drishti.modules.dev` kept. SDK folder in the install. Upgrade warns, never blocks |
| D12 | File cap 2,500 lines. VaR goldens exactly equal. Windows and Linux. One way per job: the old path is deleted in the same step that replaces it |
| P1 **plan** | The live deployment machinery goes: module store, plans and four eyes, stage/activate across nodes, auto-deploy, hot swap and drain, C15. Admin → Modules shows modules and their switch; it deploys nothing (Q1) |
| P2 **plan** | `first-party.yaml` leaves the server jar and is the file `<home>/modules/first-party.yaml`, written by `build modules`. Else a rebuilt banking is refused by the same server jar (its hash is in the jar today) (Q2) |
| P3 **plan** | One verifier, in Java, in the server jar (`module verify` beside `resolve` and `sutra`); `drishti.py module verify` calls it. Python packs, Java verifies: no second implementation of V1–V9 (Q3) |
| P4 **plan** | `drishti-sdk/drishti-module-maven-plugin` is deleted. The SDK parent pom calls the Python packager with `exec-maven-plugin` (Q4) |
| P5 **plan** | A file inside a module is named in config as `${drishti.module.dir.<id>}/…` (published at launch, shown in Admin → resolved paths). `${drishti.modules.home}/<id>/…` no longer exists as a tree (Q7) |
| P6 **plan** | Overrides: one rule, per module: the view `<data>/overrides/modules/<id>/<version>-<hash16>/` over the cache folder. The whole-tree view and `drishti.modules.source-home` are deleted |
| P7 **plan** | Typed API in two tiers: kernel services as Java interfaces (`Kernel`); module-to-module as **operations** (provider `@Operation` methods, the consumer's own interface, forwarded to the node that holds the part). No shared jars between modules (Q5) |
| P8 **plan** | Banking moves 5 kernel packages off its allowance (43 → 38), those the typed API publishes anyway. The other 38 stay for the later "banking on the public API" release (Q6) |

## 2. What i6 already changed (overlap)

`git diff main..wip/i6` (70 commits; `main` = `v2.9.11.i_5`). None of M1–M7 is built on `wip/i6`.

| Area | What i6 (and i5) left | Consequence for i7a |
|---|---|---|
| Resolver | `PackResolver` reads `<store>/installed/active.yaml` first, then `modules/<dir>/module.yaml` (`PackResolver.java:233-260`). i6 S5 added `ResolveCli` (`resolve --json` in the server jar) and `tools/resolved.py`; the console's own resolver twin is deleted | The design's "Python twin re-pointed" is gone. Only `PackResolver`, `ResolveCli` and `tools/resolved.py` follow (step B2) |
| `ModuleDeployController` (388 lines) | i6 added the thin console's reads: `/public/modules/console`, `/public/modules/manifests`, `/public/modules/console/{id}/{version}` (+62 lines) | These three, with `/api/v1/modules/needs` and `/route`, are **kept** and move to a new `ModulePublicController`. The 16 store, deployment and `/net/modules/*` endpoints go (step B4) |
| `packaging/dist` | i6 added the trading and market-data jars: 5 `pack-*` executions of `ModuleTool` + `first-party` + `check-banking`; `.dmod`s as resources of `drishti-modules.jar` | Replaced by the packager; the module deleted (step A3) |
| `packaging/drishti-app`, `drishti-kernel-app` | version only | `drishti-app` loses `drishti-modules`; `drishti-kernel-app` deleted (A3) |
| `tools/launch/*.py` | Python launchers (i5); `serverstart.py:159` sets `DRISHTI_MODULES_HOME`; `envsetup.build_jar` builds `:drishti-app` and copies to `<home>/lib`. `bin/` holds only `drishti-node.sh` | Step C1 edits Python only. `build modules` is modelled on `build_jar` |
| `tools/modulecli.py` (332), `drishti_parser.py:109-128`, `drishti.py:136-160` | `module pack|check|inspect` run `drishti-module-tool.jar`; `deploy|plan|apply|close|status|list|run` call the deployment API | Rewritten (steps A1, A2, C2) |
| `tools/module_layout.py`, `module-layout.yaml` | the one layout and its allowed names (i5) | The packager reads the same list. Unchanged |
| Overrides | `ModuleOverrides.apply` (whole-tree view `<data>/overrides/modules`, swaps `drishti.modules.home`) and `ModuleOverrides.installed` (per-module view) | Folded into the per-module rule (B2) |
| Version | one version in `.mvn/maven.config` (`-Drevision=2.9.11.i6`), `tools/version.py` | The packager takes a module's version from `module.yaml`, the kernel's from `tools/version.py` |
| Landings (i6 S8a) | `LoopbackKernelCalls` (213), `KernelCalls` (106), `TradingLanding` (1,251), `MarketLanding` (859); the head commit gives the conformance harness a refusing `KernelCalls` | Item II replaces all of it (K1–K4) |
| Elements | one version 2.4.0; the split runbook (`docs/plans/ELEMENTS_SPLIT_RUNBOOK.md`) | Item III changes console files only, no Elements library (section 7) |
| Drill | `tools/drill.sh:580` `STALE_STATE=(modules/installed …)`; builds one Maven run | Edited once, before Drill A, on a non-release run (section 8) |

**Still true from the design's inventory:** three ways a module reaches a server (jar resource → `shipped` → `installed`; source folder;
dropped `.dmod`); `ModuleClassLoader.java:92-109` asks the module's own jars first outside `KernelFacts.SHARED`; a child Spring context
per module (`ModuleRuntime.java:177-201`), started at `ApplicationReadyEvent` (`ModuleDeployConfiguration.java:205`).

## 3. Rules for every step

1. Read only the files named under **Scan**. Do not explore.
2. One step, one branch `wip/i7a-<step>` off `wip/i7a`; commit `git -c user.name=ajsinha -c user.email=ajsinha@gmail.com commit`; no AI
   attribution; `git push -q origin HEAD:wip/i7a-<step>`; never force, never rebase a pushed branch.
3. Pre-merge gate before merging into `wip/i7a`: `./mvnw -q clean verify -pl <changed modules> -am`, the changed Python test files,
   every browser test file that touches changed UI, `tools/drill.sh` gate stage. A fix is proved by its whole test file. No gate log, no merge.
4. After B1, B2, B3, B4, F, K4 and K5: the VaR exact-equality check (the goldens) in the gate, not only in the drill.
5. Tests run in the foreground; `@TempDir`; 10K trades at most; one heavy JVM job at a time; temp files removed.
6. Docs and `CHANGELOG.md` in the same merge as the code (each step lists its docs). Docs say what is, never history.
7. Every new file carries the licence header. No hidden files. Every path absolute or anchored to a named root.
8. A step that deletes code also deletes its tests, settings (`application.yaml`, `settings_schemas.yaml`), CONFIGURATION rows
   (regenerate) and token-scope rows.
9. Report in at most 10 lines: files changed, tests run with counts, what was deleted, anything marked STOP.

## 4. The steps

Days are builder-days. **Par** = may run beside; **After** = must follow.

### Stream A: package and build

**A1 · The Python packager** · standard · 1.5 d · After: none · Par: K1, X1
- Goal: `drishti.py module pack <folder> [--jar J]… [--out DIR] [--kernel-build B]` writes `<id>-<version>.dmod`, byte-for-byte reproducible.
- Scan: `Dmod.java` (format: `module.yaml` first and STORED; `META-INF/DMOD.MF` = `# DMOD.MF format 1…` then `<sha256>  <path>` lines),
  `ModuleAssembler.java:107-330` (stage by layout, `content.yaml` bring-in, `stampAllowance`), `tools/module_layout.py`, `tools/packbundle.py` (style).
- Changes: new `tools/modulepack.py` (`pack`, `first_party`), about 350 lines. Same staging rules as `ModuleAssembler`: only
  `content` names of `module-layout.yaml`; jars copied to `java/` under the names `module.yaml` gives; `kernel-internals.txt` stamped with
  `--kernel-build` (default: `META-INF/build-info.properties` of the jar in `<home>/lib`). Entries sorted, zip time fixed at 1980-01-01,
  deflate level 6. Default `--out`: `<module>/target/dmod`. `first_party(folder)` writes `first-party.yaml` (id, version, sha256 of `DMOD.MF`).
  Register in `drishti_parser.py:109-128` in place of the tool-jar `pack`.
- Tests: new `tools/test_modulepack.py` (same input twice → same bytes; layout names; a name outside the layout is left out and said;
  stamp present). New Java `DmodPythonPackTest` in `kernel/drishti-module-runtime`: `Dmod.verify` accepts a package the test makes by
  running the packager on a fixture folder.
- Docs: `CLI_REFERENCE.md` (module pack), `KERNEL_AND_MODULES.md` §3.1.

**A2 · The Java verifier as a server-jar command; the module tool deleted** · standard · 1.5 d · After: A1
- Goal: `java -jar drishti-server-…-exec.jar module verify <file|folder> [--conformance] [--project P] [--json]`; `drishti.py module verify`
  calls it through `tools/javacmd.py`.
- Scan: `server/cli/ResolveCli.java`, `CliLauncher.java`, `ModuleTool.java`, `ModuleVerifier.java`, `ModuleConformance.java:1-120, 700-760`, `drishti.py:136-160`.
- Changes: new `server/cli/ModuleCli.java` (verify = V1–V9; `--conformance` = C1–C14; warnings of step B3 when `kernel-libraries.txt` is
  there). Move `ModuleConformance`, `ClassFiles`, `VisualTemplate` from `…module.tool` to `…module.conformance` (same artefact). Delete
  `ModuleTool.java`, `ModuleAssembler.java`, the tool-jar shade execution in `kernel/drishti-module-runtime/pom.xml`,
  `find_module_tool`, `--tool`, `DRISHTI_MODULE_TOOL`, the `check` and `inspect` commands (`verify --json` answers both).
  C15 (load and unload cycles), `--cycles` and `LeakCheck.java` are deleted here.
- Tests: `ModuleCliTest` (a good package, a changed file, a wrong kernel range: exit codes and words); `tools/test_drishti_cli.py` module rows.
- Docs: `CLI_REFERENCE.md`, `TEST_HARNESS.md` (C1–C14), `KERNEL_AND_MODULES.md` §7.1, §8.

**A3 · The build: one server jar, modules beside it** · standard, deep review of the poms · 2 d · After: A2 · waits for released i6
- Goal: `./mvnw clean package` gives the server jar with no module in it and one `.dmod` per module with Java;
  `drishti.py build modules` puts every `.dmod` and `first-party.yaml` in `<home>/modules`.
- Scan: root `pom.xml:39-90, 280-290`, `packaging/*/pom.xml`, `modules/{banking,trading,market-data,genomics}/pom.xml`,
  `drishti-sdk/drishti-module-parent/pom.xml`, `tools/launch/envsetup.py:165-214`, `launchcli.py:227-250`, `testing/drishti-it/pom.xml`.
- Changes:
  1. Delete `packaging/dist` and `packaging/drishti-kernel-app`. `packaging/drishti-app`: drop the `drishti-modules` dependency; add
     the generation of `META-INF/drishti/kernel-libraries.txt` (`maven-dependency-plugin:list`, output file as a resource; name, version per line).
  2. Root pom: reactor order kernel … `drishti-server`, `packaging/drishti-app`, then profile `modules` (active by default) with
     `modules/banking`, `modules/trading`, `modules/market-data`, `modules/genomics`, then `testing/*`. Remove `<drishti.modules.home>none`
     only where step E1 says.
  3. Each module's aggregator pom: `exec-maven-plugin` in `package`: `${drishti.python} ${repo}/tools/drishti.py module pack ${module.root}
     --jar <each project jar> --out ${module.root}/target/dmod`. `drishti.python` = `python3` (profile `windows`: `python`).
     `modules/banking/java/banking-service/pom.xml`: the `ModuleCli … --conformance` run in `verify` replaces `check-banking`.
  4. Delete `drishti-sdk/drishti-module-maven-plugin` (5 mojos). `drishti-module-parent` `pluginManagement`: the same `exec` executions
     (`validate` → `module verify --manifest-only`, `package` → `module pack`, `verify` → `module verify --conformance`). Archetype pom updated.
  5. `tools/launch/envsetup.py`: new `build_modules(a)`: Maven `clean package -DskipTests -Pmodules -pl <modules with a pom> -am`; then
     `modulepack.pack` for each module without a pom (capital-markets, economy, source-*); copy each `target/dmod/*.dmod` into
     `<home>/modules` (`.part` then `os.replace`), removing an older file of the same id; write `<home>/modules/first-party.yaml`.
     `launchcli.py`: `build modules [id…]`, `build all` (= jar, then modules). `build jar` unchanged.
  6. `.gitignore`: `modules/*.dmod`, `modules/first-party.yaml`.
- Tests: guard **G1** (new `ServerJarHoldsNoModuleTest` in `testing/drishti-it`: the exec jar has no `.dmod`, no class of a module
  package, no `first-party.yaml`); `tools/test_build_modules.py` (dry run prints the Maven line and the 12 targets).
- Docs: `DEVELOPER_GUIDE.md`, `QUICKSTART_DEVELOPERS.md` (the two builds), `DISTRIBUTIONS.md`, `bin/README.md`, `README.md`.

### Stream B: one place

**B1 · Module boot: find, verify, cache, sweep** · deep · 2 d · After: none (new files) · Par: A1, K1
- Goal: one class answers "which modules, from which file, unpacked where", before any Spring context.
- Scan: `ModuleDiscovery.java`, `ModuleCandidate.java`, `ModuleVerifier.java:40-120`, `Dmod.java:100-251`, `FirstParty.java`, `packs/LaunchRoots.java:60-80, 175-235`.
- Changes: new `kernel/drishti-module-runtime/…/runtime/ModuleBoot.java` (+ `ModuleFiles.java` if over 400 lines):
  `Result boot(Path modulesHome, Path cacheRoot, boolean sweep, KernelFacts facts, List<Path> devFolders)`.
  1. List `modulesHome/*.dmod` (files only). File name must be `<id>-<version>.dmod` and match the manifest, else refused in words.
  2. Two files of one id → both refused: *"banking: two packages in <folder> (1.0.0, 1.1.0); remove one and restart"*.
  3. SHA-256 of the file → `<cacheRoot>/<id>/<version>-<hex16>/`. Missing → unpack into `<…>.part-<pid>` and `Files.move(ATOMIC_MOVE)`;
     lost race → use the winner's folder. Present → check against `DMOD.MF` (V2); mismatch → delete and unpack again.
  4. `sweep=true` (the server only): delete every cache folder no present `.dmod` owns. A folder that cannot be deleted (Windows, a
     running loader process) is noted, never fatal.
  5. First-party list: `modulesHome/first-party.yaml` (`FirstParty.read`); `FirstParty.shipped` and `RESOURCE` deleted.
  6. `devFolders` non-empty (dev switch): candidates are those source folders **only**; `.dmod` files are not read; each marked
     `development (unverified)`.
  7. `Result`: `modules` (id → dir, file, hash, version, manifest, origin), `refused` (id → words), `leftovers`
     (`<data>/modules/{installed,store,shipped,staging,tombstones}` present → *"not read: safe to delete"*), `notes`.
  Delete `ModuleDiscovery.readActive`, `pick`, `HOST_FOLDERS`.
- Tests: `ModuleBootTest`: guards **G3** (planted `installed/banking/0.9.0` and a stale cache folder change nothing; stale folder gone;
  leftover named), **G4**, **G6** (range refusal names range and kernel number), **G8** (a changed cache file → re-unpacked).
- Docs: `KERNEL_AND_MODULES.md` §4.1, §4.2, §18.1.

**B2 · Every reader asks the boot result** · deep (resolver, overrides), standard (the rest) · 3 d · After: B1, A3
- Goal: no code lists a modules folder or the cache; a module's folder is the boot result's `dir`.
- Scan: `docs/architecture/pack-resolution-inventory.md` (the list of readers), `PackResolver.java:215-300`, `ResolutionSettings.java`,
  `ConfigResolution.java:60-100`, `ModuleOverrides.java:100-300`, `LaunchRoots.java`, then each file below at the lines grep gives for
  `modules.home|modules.path|module-deploy|active.yaml|installed`.
- Changes:
  1. `LaunchRoots`: run `ModuleBoot` once (after the roots, before overrides); publish `drishti.module.dir.<id>` for each module, system
     property and environment; keep the `Result` for the process (`ModuleBoot.current()`), as `ModuleOverrides.activate` does today.
  2. `ResolutionSettings`: `modulesPath` removed; `Layout.store()` removed. `PackResolver.modules`: from the boot result only
     (`ModuleOverrides.view(dir, id)`), no folder walk, no `active.yaml`. `ConfigResolution.offline` and `ResolveCli:149-155` follow.
  3. `ModuleOverrides`: `installed` → `view`; view folder `<data>/overrides/modules/<id>/<version>-<hash16>/`; delete `apply`, `sync` of the
     whole tree, `SOURCE_HOME`, the `LaunchRoots.overrides` swap of `drishti.modules.home`. `overrides.yaml` list and the Health report keep their shape.
  4. Re-point, each to `ModuleBoot.current()` or the resolution: `registry/ModulePackages.java`, `registry/AccessRegistries.java:56-61`,
     `YamlAccessStore`, `packs/distribution/Distributions.java:128-130`, `packs/module/ModuleSettingsEnvironmentPostProcessor.java:83`,
     `packs/PackPaths.java:97`, `lakeconfig/ModuleDirs.java`, `dataload/LoaderProcess.java:214` (passes `drishti.modules.home`; the loader
     process calls `ModuleBoot` with `sweep=false`), `server/dataload/DataLoadConfiguration.java:126`, `server/liveset/LiveSetConfiguration.java:169`
     (`drishti.live-sets.modules-dir` deleted: blotters from each module's dir), `server/admin/ArtifactCatalog.java`, `ArtifactPaths.java`,
     `server/engines/EngineCatalogue.java`, `compute/env/Requires.java`.
  5. Config: `config/engines/frtb-sa.yaml:24`, `modules/banking/java/banking-service/src/main/resources/engines/frtb-sa.yaml:24`,
     `FrtbProperties.java:44` → `${drishti.module.dir.banking}/packs/market-risk/capital`. Grep `\$\{drishti\.modules\.home\}/` must return nothing.
  6. Python: `tools/resolved.py:253-283`, `tools/javacmd.py:250-310` (`root_flags` keeps `-Ddrishti.modules.home=<repo>/modules`; that
     folder now holds the built `.dmod`s), `tools/settingsfile.py`, `tools/doctor.py`, `tools/packgen/module_from_jsonl.py:84`
     (`--modules-dir` = the modules home; writes a `.dmod` there through `modulepack`), `tools/settings_sources.py:222`.
  7. Dev switch: `drishti.modules.dev=true` → `devFolders` = the directories of `drishti.modules.home` that hold `module.yaml`; refused in a
     cluster and under `prod`/`production` as today (`ModuleDeployConfiguration.development`, moved to `LaunchRoots`).
- Tests: `kernel/drishti-api/src/test/resources/pack-resolution/cases.yaml` re-cut (store cases → `.dmod` cases); `PackResolverTest`,
  `ModuleOverridesTest`, `ResolveCliTest`, `tools/test_resolved.py`; guard **G9** (new `NoModuleFolderReaderTest`: outside
  `ModuleBoot`, no main class names `cache/modules`, lists `drishti.modules.home` or reads `active.yaml`); VaR exact check.
- Docs: `KERNEL_AND_MODULES.md` §18.1, §18.2, §18.10; `pack-resolution-inventory.md` regenerated; `CONFIGURATION.md` regenerated.

**B3 · The plain loader and the library warning** · deep · 1 d · After: B1 · Par: B2
- Scan: `ModuleClassLoader.java`, `KernelFacts.java:40-60`, `FirstParty.java:150-219`, `host/ModuleBridge.java:195-216`.
- Changes: `ModuleClassLoader.loadClass`: parent first, always (`java.` and everything else → kernel loader, which sees `lib/ext`); on
  `ClassNotFoundException` the module's own jars, then its `lib/`. One filter stays: a class of `com.ash.drishti.` outside the API prefixes
  (`com.ash.drishti.kernel.api.`, `com.ash.drishti.api.`) is refused unless in the module's verified allowance. `KernelFacts.SHARED`
  deleted. New `KernelLibraries` (reads `META-INF/drishti/kernel-libraries.txt`; compares each jar of `java/` and `lib/` by artifact name):
  one sentence per overlap, *"genomics 0.1.0 carries jackson-databind 2.17.1; the kernel's jackson-databind 2.21.4 is used instead.
  Remove the jar from the module and compile against the kernel's (scope provided)."* Used by `ModuleCli verify`, the load log (WARN),
  and the module's Health row (`libraries: [{name, version, sha256, shadowedBy}]`). Remove the three `# library` lines of
  `modules/banking/java/kernel-internals.txt`.
- Tests: guard **G10** (`KernelLibrariesWinTest`: a fixture `.dmod` carrying a Jackson jar loads; the class comes from the kernel's loader;
  the sentence is in verify output, log and Health); VaR exact check.
- Docs: `KERNEL_AND_MODULES.md` §4.4; `ADMIN_GUIDE.md` (Health, modules).

**B4 · The deployment machinery deleted** · standard · 3 d · After: B2 · waits for released i6
- Scan: `server/modules/deploy/*` (method list only: `grep -n "public "`), `ModuleAdminController.java`, `application.yaml:200-215, 345-360,
  575-595`, `drishti-console/routes/module_admin_routes.py`, `pack_routes.py:360-405`, `web/templates/admin/{modules,module_deploy}.html`,
  `web/static/js/drishti_admin-modules.js`, `tools/modulecli.py`.
- Changes:
  1. Delete `ModuleDeployService`, `ModuleAutoDeploy`, `NodeCalls`, `ModuleRouteFilter` (swap wait and drain), the store/plans/apply/
     reject/withdraw endpoints and all `/api/v1/net/modules/*` but `status`. `ModuleNode` keeps: the registry of loaded modules, `status()`,
     `byPath/byPrefix/byApiPath`, `needs`, `fingerprint` (now includes each `.dmod` hash). Target: under 400 lines. STOP if a kept method
     needs a deleted one.
  2. New `ModulePublicController` with the five kept reads (section 2), console parts served from the module's dir.
  3. `ModuleDeployConfiguration` → `ModuleHostConfiguration`: no `shipped()`, no `installShipped`, no auto-deploy watch, no
     `releaseLibraryLoaders`. Keep the dev-mode reload of **packs and Sutras** only.
  4. `application.yaml`: remove the settings of D10 and their token-scope rows; `settings_schemas.yaml` follows.
  5. Cluster digest carries `{id, version, hash16}` per module; Admin → Cluster marks a node whose hash differs (`server/net` digest
     class: grep `fingerprint()` callers).
  6. Console: delete `module_deploy.html`, the deploy routes of `module_admin_routes.py`, `tests/test_admin_modules_deploy.py`.
     `modules.html` + `drishti_admin-modules.js`: list (id, version, hash, origin, state, reason, extra libraries, file), the on/off
     switch, and the line *"Add, upgrade or remove: python tools/drishti.py module install|remove <…>, then restart."* Built from Drishti
     Elements only. `pack_routes.py`: actions `install`, `plan`, `apply` → one action `download` (the `.dmod`); setting
     `builder.pack_designer_modules_dir` deleted.
  7. `tools/modulecli.py`: `deploy|plan|apply|close|status` deleted (C2 writes the new commands). `tools/docs/shots/deploy.py` deleted;
     `shots/packs.py`, `t45.py` re-cut for the changed screens.
- Tests: delete `ModuleDeployClusterTest`, `DeployTestModule`; adapt `ModuleHostServerTest`, `GenomicsDistributionServerTest`,
  `test_pack_designer_routes.py`, `test_write_endpoints_admin.py`, `test_engine_off_everywhere.py`, `tools/test_modulecli.py`; API docs
  check (`tools/api_docs.py`) regenerated; browser tests of Admin → Modules.
- Docs: `ADMIN_GUIDE.md`, `OPERATIONS_GUIDE.md`, `CLUSTER_GUIDE.md`, `KERNEL_AND_MODULES.md` §4.10, §7.1; the affected tutorial and its screenshots.

### Stream C: launch and operate

**C1 · Launchers and deployment files** · routine · 1.5 d · After: A3 · waits for released i6
- Scan and change, names only: `tools/launch/serverstart.py:50-60, 150-165` (prints the modules folder and the count of `.dmod`s; start
  refuses in words when the folder holds source folders and no `.dmod` and the dev switch is off: *"… holds module sources and no .dmod:
  run python tools/drishti.py build modules"*), `tools/launch/clusterctl.py` (every node the same `DRISHTI_MODULES_HOME`; no copy per
  node), `run-configs/*.run.xml` (11 files: `DRISHTI_MODULES_HOME=$PROJECT_DIR$/modules`; a before-launch step `build modules` on the two
  debug configurations), `deploy/systemd/drishti-server.service`, `deploy/**` Dockerfiles and task definitions (COPY `modules/*.dmod` and
  `first-party.yaml`, not the source tree), `config/settings.examples/{dev,prod}.ini`, `tools/launch/envsetup.py` (`setup` reports
  leftovers from `ModuleBoot` via `module list`).
- Tests: `drishti-console/tests/test_release_from_other_folder.py`, `tools/test_launch*.py`, the container smoke definition
  `tools/drillkit/container_smoke.json`; guards **G5** (same jar hash, genomics 0.1.0 → 0.1.1 → removed, across restarts) and **G7** (empty
  modules folder: starts, Health green, `modules: none`) in the new `testing/drishti-modules-it` (step E1).
- Docs: `DEPLOYMENT_AND_QA_GUIDE.md` (+PDF), `bin/README.md`, `OPERATIONS_GUIDE.md`.

**C2 · The operator's commands** · standard · 1 d · After: A2, B1
- Changes: `tools/modulecli.py` rewritten (about 250 lines), file operations, no running server needed:
  `list` (each `.dmod`: id, version, hash16, origin; with `--server` also loaded/refused and why, from `/api/v1/admin/modules`);
  `verify <file>` (A2); `install <file>` (verify; copy `.part` + replace; the same id's older file moved to
  `<data>/backups/modules/<id>-<version>.dmod.previous`; prints *restart the server*); `remove <id>` (moved to the same backup);
  `rollback <id>`; `run` kept (starts the installed server with one project's `.dmod`, temp modules folder, debug port).
  Before `install`: lists every other `.dmod` the kernel in `<home>/lib` would refuse, and continues (D11: warns, never blocks).
- Tests: `tools/test_modulecli.py` rewritten (each command on a temp home; Windows path case; a locked target file said in words).
- Docs: `CLI_REFERENCE.md`, the command catalogue regenerated, `ADMIN_GUIDE.md`.

### Stream D: the SDK

**D1 · The SDK folder** · standard · 1.5 d · After: A3
- Changes: `drishti.py build sdk` → `<home>/sdk/lib/` (`drishti-kernel-api`, `drishti-api` jars with sources and Javadoc),
  `<home>/sdk/repo/` (file repository: the two jars, `drishti-module-parent`, the archetype), `<home>/sdk/kernel-libraries.txt`.
  No tool jar. `drishti-module-parent` gains `dependencyManagement` rows for every line of `kernel-libraries.txt` (generated, scope
  `provided`). The archetype's project names `sdk/repo` by absolute path.
- Tests: `SdkBuildsGenomicsTest` (Python, `tools/`): copies `modules/genomics` to a temp folder with no kernel source, builds against
  `sdk/repo` offline, gets a `.dmod` that `module verify` accepts.
- Docs: `KERNEL_AND_MODULES.md` §8.1, §8.8; `DEVELOPER_GUIDE.md`.

**D2 · `DrishtiException` and `ErrorCode` into `drishti-api`** · routine · 0.5 d · After: released i6 merged (549 files)
- Changes: move both files to `kernel/drishti-api/src/main/java/com/ash/drishti/api/error/`; one scripted import rewrite
  (`com.ash.drishti.common.DrishtiException|ErrorCode` → `com.ash.drishti.api.error.…`, 549 files); `drishti-kernel-api/pom.xml` drops
  `drishti-common`; `com.ash.drishti.common` leaves banking's allowance only if `TempFiles` (3 imports) also goes (it does: use
  `ModuleContext.dataDir()`).
- Tests: full compile; `spotless:apply`; architecture rules.
- Docs: none beyond the CHANGELOG line.

### Stream E: tests

**E1 · Kernel tests untied from modules; the modules' test project; guards** · standard · 3 d · After: B4 · Par: C1
- Scan: `grep -rlE 'modules/(banking|market-data|trading|economy|capital-markets|genomics|source-)' --include=*.java` under `src/test`
  (37 files: 18 `drishti-server`, 4 `drishti-packs`, 3 each `drishti-api`, `drishti-module-runtime`, `drishti-it`, …).
- Changes: each such test either moves to the module it tests (`modules/<m>/java/<project>/src/test`) or reads a fixture pack under its
  own `src/test/resources/fixture-module/` (one small module `fixture`, packed by the test with the packager). `testing/drishti-it/pom.xml:33`
  loses `drishti-banking`. New `testing/drishti-modules-it` (in profile `modules`): starts the built server jar with `<home>/modules` as
  built; holds G5, G7, the genomics distribution test and the VaR exact check's server tests. Guard **G2**
  (`KernelNamesNoModuleTest`: no `pom.xml` outside `modules/` and `testing/drishti-modules-it` names a module artefact; no kernel test
  opens `modules/`). `tools/drillkit/affected-map.txt` rows for the new project.
- Docs: `TEST_HARNESS.md` (the project, G1–G12).

### Stream F: one Spring context

**F1 · Module beans in the kernel's context** · deep · 4 d · After: Gate 1 green (A–E merged) · sequential, alone
- Scan: `ModuleRuntime.java:150-330`, `ModuleNode.java:147-205`, `host/ModuleBridge.java`, `host/ModuleWiring.java:100-150`,
  `deploy/ApiMounts.java`, `DrishtiApplication.java`, `ModuleSettingsEnvironmentPostProcessor.java`,
  `banking/…/BankingAutoConfiguration.java`, `ModuleConformance.java:700-760`.
- Changes, in this order, genomics first, then trading and market-data, then banking:
  1. `DrishtiApplication`: an `ApplicationContextInitializer` (`ModuleBeans`) reads `ModuleBoot.current()`, makes each module's loader,
     and registers each module's `java: spring:` classes and its `DrishtiModule` entry with a `BeanNameGenerator` that prefixes
     `<id>:`; each definition carries the attribute `drishti.module=<id>` and its class loader (`AbstractBeanDefinition.setBeanClass`
     from the module's loader; the context's resource loader stays the kernel's).
  2. A module's package scan (`@ComponentScan` in its configuration) resolves through the module's loader: `ModuleBeans` registers the
     configuration class with a `ClassPathBeanDefinitionScanner` whose resource loader is the module's.
  3. `ApiMounts`, `ModuleBridge`: find a module's controllers and extension beans by the bean definition's `drishti.module`
     attribute; the per-module context lookups deleted.
  4. `ModuleWiring:116-140`: engine defaults joined in `ModuleSettingsEnvironmentPostProcessor`, once.
  5. `ModuleRuntime`: no `AnnotationConfigApplicationContext`, no `close`, no `Leave`; `LoadedModule` loses its context. The
     `ApplicationReadyEvent` start (`ModuleHostConfiguration.startModules`) only calls each `DrishtiModule.start`.
  6. A module's bean that throws while created: `ModuleStartFailure` (a `FailureAnalyzer`) prints *"The server cannot start: module
     genomics 0.1.1 failed while starting (GenomicsData: …). Remove <file> or switch the module off in config/modules/genomics.yaml,
     then start again."*
  7. A module off at start (switch, licence, distribution): not registered at all. Switched off while running: refused at every door
     as today (`ModuleGateFilter`), jobs stopped; classes leave at restart.
  8. Conformance: C13 and C14 across a restart of the harness's context; the harness registers into one context the same way.
- Tests: guards **G11** (`OneContextTest`: `ctx.getParent()==null`, no second context among `LiveBeansView`; a module bean named as a
  kernel bean gets the prefix), **G12** (a fixture module whose bean throws: exit non-zero, the message names id, file, remedy);
  `ModuleHostServerTest`, `GenomicsDistributionServerTest`, `EngineCatalogueTest`, the whole `drishti-modules-it`; VaR exact check.
- Docs: `KERNEL_AND_MODULES.md` §4.5–§4.10; `ARCHITECTURE.md` (start order).

### Stream K: the typed kernel API

**K1 · The interfaces** · deep · 1.5 d · After: none (new files) · Par: A1, B1
- Scan: `kernel-api/…/module/{KernelCalls,ModuleContext,InlineFigures,PersonCache,Answers}.java`, `security/Principal.java`; the answers of
  the 12 calls: `server/api/CatalogController.java:83-95` (`ApiDtos.RawEntity`), `HistoryController.java:207-260` (`Series`),
  `BusinessDateController.java:42-55`, the `/search`, `/me/settings`, `/live/query`, `/pivot/inline` controller methods (grep the mapping).
- Changes: new package `com.ash.drishti.kernel.api.services` in `kernel/drishti-kernel-api`. Records carry exactly the fields the
  REST answer has today; nothing new. `kernelApi` 0.4 → 0.5 (`kernel-api.properties:15`).

  ```java
  public interface Kernel {                       // one bean; also ModuleContext.kernel()
      Person person();                            // of the request on this thread; IllegalStateException off a request
      Records records();  History history();  BusinessDates dates();  PersonSettings settings();
      LiveQueries live(); Pivots pivots();    Lenses lenses();        Access access();  Operations operations();
  }
  public interface Person { String user(); String display(); boolean admin(); List<String> roles(); String lens();
      AsOf asOf(); String key(); Person onDate(LocalDate day); }
  public interface Records { SearchAnswer search(Person p, String query); Optional<RawRecord> raw(Person p, EntityRef ref); }
  public interface History { Series series(Person p, EntityRef ref, String path, int days, LocalDate to); }
  public interface BusinessDates { LocalDate current(); LocalDate selected(Person p); boolean live(Person p); }
  public interface PersonSettings { Map<String, Object> of(Person p); }
  public interface LiveQueries { LivePage query(Person p, String query, int limit); }
  public interface Pivots { CellSet inline(Person p, InlineRequest request); }
  public interface Lenses { Map<String, Object> block(String lens, String block); }
  public interface Access { void requireOpen(Person p, String kind); Map<String, Object> redact(Person p, String kind, Map<String, Object> data); }
  public interface Operations { boolean available(String module); <T> T of(String module, Class<T> consumerInterface); }
  @Retention(RUNTIME) @Target(METHOD) public @interface Operation { String value(); }     // "var.scope"
  public final class Refused extends RuntimeException { int status(); String code(); String say(); }   // moved from KernelCalls
  ```
  `PersonCache` keys on `Person.key()`. `InlineFigures.ask` takes `Pivots`. `KernelCalls` stays until K4 deletes it.
- Tests: compile only (records' canonical constructors validate; one unit test per record with a rule).
- Docs: `KERNEL_AND_MODULES.md` §2.3 (the table of services).

**K2 · The kernel's implementations** · deep · 3 d · After: K1 · Par: stream B
- Scan: the seven controllers above; the filters on `/api/*`: `security/TokenFilter`, `PathGuard`, `RequestSizeFilter`,
  `engines/EngineGateFilter`, `modules/ModuleGateFilter`, `placement/PlacementGuardFilter`, `admission/RequestsInFlight`,
  `api/RequestActivity`, `edition/EditionContext`, `api/AsOfResolver`.
- Changes: new package `server/kernelapi/`: `KernelServices` (`@Component implements Kernel`), `RequestPerson` (built from the request:
  `Principal`, `AsOf`, known-at, lens, the token's scopes; a copy, usable from any thread), one class per service. **Rule:** a
  controller's body moves into a service method `(Person|Principal, AsOf, …)`; the controller and the typed service both call it; a typed
  service never calls a controller. What each filter does for a typed call is fixed here:

  | Filter | Typed call |
  |---|---|
  | `TokenFilter` (identity, token scopes) | identity copied into `Person`; each service method checks the scope its REST path needs, from the same scope table |
  | Entitlements, masks, row rules | inside the service method, as the controller did (`entitlements.requireOpen`, `redact`) |
  | `EditionContext` (lens), `AsOfResolver` (date box, known-at) | values of `Person`, passed explicitly; never a thread local |
  | `EngineGateFilter`, `ModuleGateFilter` | checked by `Operations` before a module's operation runs (K3); kernel services have neither |
  | `PlacementGuardFilter` | `Operations` forwards (K3); kernel services run on every node |
  | `RequestsInFlight` (admission), `RequestActivity` | not counted again: the outer request already holds its slot |
  | `PathGuard`, `RequestSizeFilter` | not applicable (no path, no body) |

- Tests: `TypedCallParityTest` (server test, the fixture module of E1): for each of the 7 kernel calls, the typed answer equals the REST
  answer for the same person, across: administrator, a masked role, a row-ruled role, a lens, an earlier date box, a read-only token, a
  token without the scope (both refuse with the same code).
- Docs: `KERNEL_AND_MODULES.md` §2.3; `ACCESS_MODEL.md` (typed calls pass the same doors: the table above).

**K3 · Operations: one module asks another** · deep · 2.5 d · After: F1, K2
- Scan: `placement/PlacementGuardFilter.java`, `net/RunForwardFilter.java`, `net/NetCaller.java`, `net/NetCallFilter.java` (CLAIMS),
  `host/ModuleBridge.java`, banking's controllers of `/risk/var/scope`, `/scope/contributions`, `/scope/history`, `/official`,
  `/what-if`, `/risk/exposure/predeal` (grep the mappings under `modules/banking/java/banking-service`).
- Changes:
  1. `server/kernelapi/OperationRegistry`: at start, every module bean method annotated `@Operation("x")` with the shape
     `(Person, <one request record>) → <answer record>` is registered as `<module>:x` with the module part that holds it (the part of the
     bean's controller, from the manifest). Two of one name: the start stops, both named.
  2. `Operations.of(module, iface)`: a `java.lang.reflect.Proxy`; each method of the consumer's interface carries `@Operation("x")`.
     A call: module on and licensed (`ModuleGateFilter`'s rule) → engine running (`EngineGateFilter`'s rule) → placed here? invoke,
     converting the consumer's request record to the provider's and the answer back with the kernel's `ObjectMapper.convertValue`;
     placed elsewhere? `POST /api/v1/net/modules/operation/{module}/{name}` to the node `PlacementGuardFilter` would choose, with the
     person's claims as the existing forwards send them. A refusal arrives as `Refused` with the provider's status, code and words.
     Module absent or off: `available()` false; a call refuses with DRS-1020's words.
  3. Banking: six operations (`var.scope`, `var.contributions`, `var.history`, `var.official`, `var.what-if`, `exposure.predeal`) on
     the service beans its controllers already call; request and answer records = the REST answers' fields; the controllers call the
     same methods. No maths moves.
- Tests: `OperationsTest` (fixture provider and consumer modules: local call; module off; engine off; name clash);
  `OperationsClusterTest` (two nodes, the provider's part on the other: same answer, same refusal); parity rows added to
  `TypedCallParityTest` for the six; VaR exact check.
- Docs: `KERNEL_AND_MODULES.md` §2.3, §5 (operations); `CLUSTER_GUIDE.md` (an operation follows placement).

**K4 · Trading and market-data on the typed API; the loopback deleted** · standard, deep review · 3 d · After: K3
- Scan: `TradingLanding.java:100-160, 240-260, 430-445, 795-805, 895-1010, 1180-1230` (every `c.get`/`c.post`: 17 call sites),
  `MarketLanding.java:95-125, 210-230, 295-300`, both controllers and auto-configurations.
- Changes: `Caller c` → `Person p` + injected `Kernel`. Call map (no other change of logic; the panels' figures stay byte-equal):

  | Today | Becomes |
  |---|---|
  | `c.get("/search")` | `kernel.records().search(p, q)` |
  | `c.get("/entities/{k}/{id}/raw")`, `…/~/raw?id=` | `kernel.records().raw(p, EntityRef.of(k, id))` |
  | `c.get("/history/{k}/{id}/series")` | `kernel.history().series(…)` |
  | `c.get("/business-date")` | `kernel.dates()` |
  | `c.get("/me/settings")` | `kernel.settings().of(p)` |
  | `c.get("/live/query")` | `kernel.live().query(p, q, PAGE)` |
  | `kernel.lens(c.lens())` + `map(…, "trading")` | `kernel.lenses().block(p.lens(), "trading")` (`"quality"` for market-data) |
  | the six `/risk/…` calls | `RiskFigures` (a new interface **in the trading module**, six `@Operation` methods, its own records), from `kernel.operations().of("banking", RiskFigures.class)`; `available()` false → the panel says which module is off, as it says a refusal today |

  Delete `server/api/LoopbackKernelCalls.java`, `kernel-api/…/module/KernelCalls.java`, `Answers` methods no caller has left, the
  harness's `NO_CALLS` (a refusing `Kernel` in its place). Trading's `module.yaml`: `uses: banking` (optional). Split `TradingLanding`
  when a panel's code moves anyway: `TradingRisk.java` (the VaR and pre-deal panels), each file under 900 lines.
- Tests: the landings' server tests and the console's landing browser tests unchanged and green; new guard **G13**
  (`NoLoopbackTest`: no main class under `kernel/` or `modules/` builds a URL on `127.0.0.1`/`localhost` with `local.server.port`).
- Docs: `THIN_CONSOLE_DESIGN.md` S8a wording, the trading and market-data module READMEs.

**K5 · Banking: five packages off the allowance** · standard · 2 d · After: K2, D2 · Par: K3
- Changes (imports re-pointed, behaviour equal): `server.security.Entitlements` (15) → `Access` + `Person`; `engine.source.SourceRouter`
  (10) → `ModuleContext.kinds()` (**STOP** on a call `Kinds` cannot express: list it, leave that file on `SourceRouter`, keep the package);
  `identity.AuditLog` (7) → `ModuleContext.audit`; `server.engines.BasicEngine` (1) → `kernel.api.engines`; `common.*` (D2).
  `modules/banking/java/kernel-internals.txt`: remove `com.ash.drishti.common`, `…server.security`, `…engine.source`, `…identity`,
  `…server.engines` (43 → 38; fewer if a STOP). The file may only shrink: guard `KernelInternalsShrinkTest` holds the line count.
- Tests: banking's 164 test files; conformance C1; VaR exact check.
- Docs: `KERNEL_AND_MODULES.md` §3.3 (the allowance's size), `IMPLEMENTATION_PLAN.md` (the remaining 38 named for the later release).

### Stream X: the form check

**X1 · The server checks a draft** · standard · 1.5 d · After: none (new files) · Par: K1
- Scan: `rachana/model/Form.java:589-700` (`withDefaults`, `check`), `FormExpr.java` (public methods only), `Form.Hint`, `Form.Issue`,
  `server/edit/ChangesController.java:67-80`, how `terminal/view.html` gets a panel's `form` (grep `form` in the ViewModel builder).
- Changes: new `server/edit/FormCheckController.java`: `POST /api/v1/forms/check`, read-only, token scope as `/changes/preview`.
  Body `{sutra, panel, values, checked:[field…], carried:{field: {…}}, step}`. Answer
  `{issues:[{field, message, severity}], computed:{field: value}, hints:[{label, step, value, of, percent, over, text}], typed:{field: value}}`:
  `issues` = `Form.check`; `computed` = every `=expr` default and auto field (`Form.withDefaults`); `hints` evaluated and formatted on the
  server; `typed` = each value as the server reads it (number, date). The form is found from the Sutra on the server; the client sends no rule.
- Tests: `FormCheckControllerTest`: the cases of `FormExprTest` through the endpoint; a person without the form's role is refused; 10 fields
  answer under 20 ms on the test machine (budget row in `tools/drill-budgets.txt`).
- Docs: `RACHANA_REFERENCE.md` (the Rules paragraph: the server checks; the browser shows), the API docs regenerated.

**X2 · The console only displays; the JS copy deleted** · standard · 2 d · After: X1 · waits for released i6
- Scan: `drishti_record-forms.js` lines that use `X.` (44 uses: `check` ×3 at 434, 544, 664; `evaluate` ×3 at 720, 744; `typed` ×12;
  `num` ×8; `empty` ×18), `terminal/view.html:168`, the Elements inputs' debounced call (`drishti-inputs`, what-if control).
- Changes: `drishti_record-forms.js`: one function `ask(st, values, checked)` → `POST …/forms/check` through the console's server proxy,
  debounced 250 ms, the newest answer wins (a sequence number), the last answer kept while one is in flight. `issues()`, auto-fill and
  hints read the answer. `X.typed`/`X.num` → the answer's `typed`; for a number shown before the first answer, `Drishti.elements.core`
  number parsing. `X.empty` → a three-line local `empty(v)`. Server away: fields stay editable, the hint line says *"not checked: the
  server did not answer"*, Save still sends (the server checks every save already). Delete `drishti_form-expr.js`, its `<script>` tag,
  `asset_prefix_map.yaml:278, 515-516`, the assertion in `tests/test_record_forms.py:55`.
- Tests: the record-form browser tests (whole files); a new one: type an invalid value, the server's message appears; the page makes
  at most one check per 250 ms; guard: no file under `web/static/js` defines `parse`+`evaluate` for form rules (`test_no_form_expr_copy.py`).
- Docs: `ELEMENTS.md:256, 947` (the two mentions), `RACHANA_REFERENCE.md`, the form tutorial if a screenshot shows a changed message.

### Stream Z: close

**Z1 · Documents sweep and generated files** · standard (writing), routine (generators) · 1.5 d · After: all code
- The design's section 9 list; `CONFIGURATION.md`, command catalogue, API docs, `pack-resolution-inventory.md`, PDFs regenerated;
  `README.md` rewritten (counts from the file system); `HOW_IT_FITS.md`; `RELEASE_NOTES.md`; deck refresh; the upgrade section
  (section 7, owner's stack) in `DEPLOYMENT_AND_QA_GUIDE.md`; `MODULE_JARS_I5_DESIGN.md` is not merged (its facts live in the guides);
  this plan is deleted at release.

## 5. Order, lanes and what runs in parallel

```
Lane 1 (modules)   A1 → A2 → A3 → B1* → B2 → B3 → B4 → E1 ─┐                       ┌→ K3 → K4 ─┐
                                                            ├→ Gate 1 → F1 → DRILL A┤           ├→ Z1 → DRILL B → release
Lane 2 (API, form) K1 → K2 → X1 → X2 → C2 → C1 → D1 → D2 ──┘                       └→ K5 ──────┘
```
`*` B1 is new files only and may be written beside A1–A3.

| Must be sequential | Why |
|---|---|
| A1 → A2 → A3 | the build calls the packager and the verifier |
| B1 → B2 → B4 | readers first, then the deletion of what they read |
| A3 → B2, C1, D1 | they need the `.dmod`s where the build puts them |
| B4 → E1 → Gate 1 → F1 | one context is built on a green base, alone, nothing else merging |
| F1 → K3 | operations find module beans in the one context; written once |
| K3 → K4; K2, D2 → K5 | consumers after the registry; banking after `Access` exists |

| May run in parallel | Note |
|---|---|
| Lane 1 beside Lane 2 up to Gate 1 | disjoint files except `application.yaml` and `CHANGELOG.md`: the second merger takes both sides |
| B3 beside B2 | different files (`ModuleClassLoader` vs the readers) |
| K5 beside K3/K4 | banking's imports vs the registry and the landings; K3's six operations touch banking's services: K3 merges first |

**Gate 1** = the pre-merge gate on `wip/i7a` with A–E, K1, K2, X, C, D merged, plus the VaR exact check and `drishti-modules-it`.

## 6. Front-loaded now, and what waits for released i6

While the i6 drill runs: edits only, no Maven, no tests, no servers, nothing in a file the i6 drill's fixes may touch. Front-loaded
code is **unproved** until step V0.

| Front-load now (new files only) | Step | Grade |
|---|---|---|
| `tools/modulepack.py`, `tools/test_modulepack.py` | A1 | standard |
| `server/cli/ModuleCli.java`, `ModuleCliTest.java` (the deletions of A2 wait) | A2 (part) | standard |
| `ModuleBoot.java`, `ModuleBootTest.java` | B1 | deep |
| `KernelLibraries.java` and its test | B3 (part) | deep |
| package `kernel.api.services` (interfaces and records) | K1 | deep |
| package `server/kernelapi/` (services as new classes; the controllers' bodies are **copied in comments as the target**, not moved yet) | K2 (part) | deep |
| `FormCheckController.java`, `FormCheckControllerTest.java` | X1 | standard |
| new `tools/modulecli.py` content as `tools/modulecli_next.py` (renamed over the old file in C2) | C2 (part) | standard |
| guard tests G1, G2, G9, G13 as new files, `@Disabled("i7a: enabled in <step>")` | E1 (part) | standard |

| Waits for released i6 (merged into `wip/i7a`) | Why |
|---|---|
| **V0** (0.5 d, standard): compile and run the tests of everything front-loaded; fix | nothing was compiled |
| A3, C1 | poms, run configurations, `deploy/`, launch scripts: i6 changed 21 of these files; a drill fix may change them again |
| B2, B4, F1 | edit files across the kernel and the console; need the tests |
| D2 | 549 files: after the last i6 fix, in one commit |
| X2, the console part of B4 | console JS and templates are what the i6 drill tests |
| `tools/drill.sh`, `drill-budgets.txt`, `drillkit/*` | never changed while a release's drill cycle runs |
| `CHANGELOG.md`, `README.md`, `RELEASE_NOTES.md`, generated docs | the i6 release writes them |

**Merging onto released i6.** `wip/i7a` never rebases. After each i6 fix that reaches `origin/wip/i6`: `git merge origin/wip/i6` (routine).
At release: `git merge v2.9.11.i_6` once; then `.mvn/maven.config` → `-Drevision=2.9.11.i7a`. New files do not conflict.

## 7. Be mindful

| Topic | What to hold to | Steps |
|---|---|---|
| **Owner's stack, upgrade from i6** | The stack's `drishti.modules.home` names the source tree today. After i7a: `python tools/drishti.py build all` (jar to `lib/`, `.dmod`s and `first-party.yaml` to `modules/`), restart each node. Old `<data>/modules/{installed,store,shipped}` and `active.yaml`: never read, never deleted, named once on Health as *safe to delete*; `drishti.py setup` lists them. Old settings (`drishti.modules.path`, `…autodeploy`, `module-deploy.*`, `builder.pack_designer_modules_dir`) in an ini: the start names each as *no longer read*, and starts. A site's `drishti.overrides.dir` keeps working unchanged (keys are module ids, as today). `<data>/overrides/installed` is a leftover too | B1, B2, C1, Z1 |
| **Cluster nodes** | Every node needs the same files in its own modules folder (the stack's three nodes share one checkout: one folder). The digest shows a differing hash; nothing pushes a module to a node. A cluster restart is node by node. `ModuleBoot` on two nodes with one shared data folder: unpack is `.part` + atomic move, the sweep deletes only folders no present `.dmod` owns, so nodes on the same files never delete each other's cache | B1, B4, C1 |
| **IntelliJ and the dev switch** | Run configurations: `DRISHTI_MODULES_HOME=$PROJECT_DIR$/modules`. Normal debug: before-launch `build modules`; breakpoints in module sources bind by class name. Dev switch (`drishti.modules.dev=true`): source folders only, `.dmod`s ignored, packs and Sutras reload on change; Java classes come from the module's built jar under `java/<project>/target/` (scan `ModuleRuntime`'s dev path and keep its jar lookup; **STOP** if it needs the jar inside `java/`). Refused in a cluster and under `prod`. Health: *development (unverified)*. After F1 a Java change needs a restart in both modes | B1, B2, C1, F1 |
| **Windows** | No `.exe`. Maven calls `python` (profile `windows`), Linux `python3`. A loaded module's jars in the cache are locked: the sweep and a re-unpack note and skip a folder they cannot delete; they never fail the start. `module install` replaces a `.dmod` the server does not hold open (`ModuleBoot` closes every zip). Atomic move of a folder fails across volumes: the `.part` folder is made inside the cache root. Paths with spaces in the `exec` arguments. Zip entry names always `/` | A1, A3, B1, C2 |
| **Overrides view** | One rule, per module, laid over the cache folder; never over a `.dmod`; code (`java/`, `lib/`, `python/`, `engines/`, code file types) never overridden, as today. The view folder's name carries the hash, so an upgraded module gets a fresh view; old views are swept with the cache. Admin → Health keeps the list of overridden files | B2 |
| **Elements vendored copy** | X2 edits `drishti_record-forms.js` (a console file). It must not edit a `drishti-*` Elements library: that would need the gallery, `ELEMENTS_API.md`, the one Elements version and, after the split runbook, the vendored pin. If the debounce helper is missing in `drishti-inputs`, write it in the console file and **STOP-report** it as an Elements request | X2 |
| **No bespoke UI** | Admin → Modules after B4 is Drishti Elements only | B4 |
| **VaR goldens** | Same banking jars, same libraries (the kernel's), another folder. Parent-first changes which copy of a library banking gets only if banking carried one: it carries none (verify with `module verify`: zero library warnings is an acceptance line of B3) | B3, F1, K3, K5 |
| **Session isolation** | `Person` is a copy; nothing per person in a static or a shared cache; `PersonCache` keys on `Person.key()` | K1–K4 |
| **Token scopes** | Every new endpoint (`/forms/check`, `/net/modules/operation/**`) gets its scope row and its API-docs row in the same step; a typed call checks the scope of the REST path it replaces | K2, K3, X1 |
| **Optional externals** | The server starts with an empty modules folder (G7); trading starts with banking absent | C1, K4 |
| **Loader process and CLIs** | `LoaderProcess`, `ResolveCli`, `ModuleCli` call `ModuleBoot` with `sweep=false` | B2 |
| **Memory during gates** | `drishti-modules-it` starts real servers: one heavy job at a time; stack down | E1, drills |

## 8. The two drills

Rules of `drill-lessons-2911`: pre-merge gate per branch; a pre-drill check before each full drill; code freeze while a drill runs;
`drill.sh` not changed inside a drill cycle.

| When | What |
|---|---|
| Before Gate 1, once, on a non-release run | `tools/drill.sh`: build = `drishti.py build all` semantics in the one Maven run (profile `modules`), servers started with `DRISHTI_MODULES_HOME=<repo>/modules`, `STALE_STATE` loses `modules/installed` and gains `cache/modules`, the container smoke copies `modules/*.dmod`. Validated with `--affected` on `wip/i7a` |
| **Drill A** (after F1) | Proves item I complete (M1–M7) with K1, K2 and item III. Content: full drill + guards G1–G12 + VaR exact + the launch matrix (Linux; Windows checklist run by the owner's QA machine: `build all`, start, `module list`, install/rollback genomics) + cluster lane (three nodes, same modules, one node with a different genomics hash shown on Admin → Cluster). Fixes with `--fix` runs |
| Between | K3, K4, K5, Z1, each through the pre-merge gate; the loopback and `KernelCalls` deleted; nothing else merged |
| **Drill B** (release) | Full drill on the frozen commit: + G13, `TypedCallParityTest`, `OperationsClusterTest`, landing browser tests, page walk and layout checks. Green → tag, develop → main per the drill-it workflow, with the owner |

If Drill A needs more than two fix runs, K3–K5 do not start until it is green.

## 9. Estimate

| Stream | Steps | Days | Grade mix |
|---|---|---|---|
| A build | A1 1.5 · A2 1.5 · A3 2 | 5 | standard |
| B one place | B1 2 · B2 3 · B3 1 · B4 3 | 9 | deep 4.5, standard 4.5 |
| C launch | C1 1.5 · C2 1 | 2.5 | routine, standard |
| D SDK | D1 1.5 · D2 0.5 | 2 | standard, routine |
| E tests | E1 3 | 3 | standard |
| F one context | F1 4 | 4 | deep |
| K typed API | K1 1.5 · K2 3 · K3 2.5 · K4 3 · K5 2 | 12 | deep 7, standard 5 |
| X form check | X1 1.5 · X2 2 | 3.5 | standard |
| Close | V0 0.5 · Z1 1.5 · drill preparation and fixes 3 | 5 | mixed |
| **Total** | | **46 builder-days** | deep 15.5 · standard 26 · routine 4.5 |

Two lanes: about **26 working days** end to end (Lane 1 to Gate 1: 17; F1: 4; Drill A: 1.5; K3–K5 and Z1: 3 in two lanes after K3; Drill
B: 1). Item I alone is 25.5 against the design's 18: + the Python packager and verifier command (3), + the deployment machinery's
deletion with its console screens (3), + the per-module directory setting and the folded overrides (1.5).

**Cut lines if time is short, in this order:** K5 (banking boundary) → item III (X1, X2) → D1 (SDK folder). Item I and K1–K4 are not cut.

## 10. Questions for the owner

The build proceeds on each recommendation unless told otherwise.

| # | Question | Recommendation |
|---|---|---|
| Q1 | The live deployment system (module store, plans with four eyes, roll-out to nodes, auto-deploy, hot swap) and Admin → Modules' deploy screen: delete them, since a module is now a file and a restart? | **Delete.** One way: `drishti.py module install`, restart. Admin shows modules, Health and the switch |
| Q2 | The first-party list is inside the server jar by hash; a rebuilt banking would be refused by an unchanged server jar. Move the list to `<home>/modules/first-party.yaml`? | **Yes**, until signing (release j). Who can write the install folder can already replace the jar |
| Q3 | Verification in one place (Java, in the server jar), with `drishti.py module verify` calling it; Python only packs? | **Yes.** No second copy of V1–V9; a module developer has the install's jar |
| Q4 | Delete `drishti-module-maven-plugin` (the SDK parent calls the Python packager with `exec-maven-plugin`), or keep it as a thin caller? | **Delete** |
| Q5 | Module-to-module typed calls: operations (provider `@Operation` methods, the consumer's own interface, forwarded by placement), or API jars one module compiles against another's? | **Operations.** No class shared between modules; works when the provider's part is on another node |
| Q6 | Banking's boundary in i7a: 5 packages off the allowance (43 → 38, about 170 imports); the other 38 in the later release? | **Yes** |
| Q7 | Config that points inside a module uses `${drishti.module.dir.<id>}` (FRTB parameters today)? | **Yes**; shown in Admin → resolved paths |
| Q8 | In a source checkout `<home>/modules` holds the source folders and the built `.dmod`s side by side (git-ignored). Accept? | **Yes** (it follows D8); discovery reads only `*.dmod`; the dev switch reads only sources |
| Q9 | Pack Designer's "Install on this node" and "Plan for the cluster" become "Download .dmod". Accept? | **Yes**, with the line that says how to install it |
| Q10 | Three large items in one release (rule: two). Keep i7a whole with two drills, with the cut lines of section 9? | **Yes** |
| Q11 | Kernel API 0.4 → 0.5; `KernelCalls` removed with no deprecation period (no module outside the repository uses it)? | **Yes** |
| Q12 | Form check: 250 ms debounce; server away → fields stay editable, the line says *not checked*, Save still checked by the server? | **Yes** |
| Q13 | `module install` keeps one previous file per id in `<data>/backups/modules`. Enough? | **Yes** (one; `rollback` restores it) |
