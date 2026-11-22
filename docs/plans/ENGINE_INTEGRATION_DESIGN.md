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
# Engine integration: engines by contract, run by configuration

**Status: design for the owner, written 2026-11-21, refreshed 2026-11-22 around the owner's acceptance test (section 0).
Nothing built, nothing run. EN-1 to EN-28 are accepted; EN-29 to EN-44 are open, each with a recommendation (section 16).**
For the refresh also read: `kernel/drishti-common/.../SharedLib`, `kernel/drishti-module-runtime` (`ModuleClassLoader`,
`ModuleVerifier`, `FirstParty`, `KernelLibraries`), `lib/README.md`. Read from git only: Drishti
`main` at `9ce7c3170` (`kernel/drishti-api/.../api/compute`, `kernel/drishti-kernel-api/.../kernel/api/engines`,
`kernel/drishti-server/.../server/engines`, `compute/drishti-compute/.../compute/reactive`, `config/engines/*.yaml`, the banking
and genomics module manifests, `META-INF/drishti/engines.yaml`); [DRISHTI_RISK_DESIGN](DRISHTI_RISK_DESIGN.md);
[DRISHTI_TOOLS_DESIGN](DRISHTI_TOOLS_DESIGN.md); `drishti-lake` `main` (`docs/design/READ_API.md`, the L11 contracts commit);
`drishti-rachana` `develop` (`spec/1/SPEC.md`, `spec/1/data-protocol/PROTOCOL.md`, `docs/design/{LIBRARIES,LIVE_TABLES}.md`,
`conformance/1/`). The normative text an engine author implements is [../specs/ENGINE_SPEC.md](../specs/ENGINE_SPEC.md); this
document is the reasoning, the Drishti side and the plan. Where the two differ, the specification wins.

**The owner's direction (2026-11-21).** Establish interfaces so that people can write engines; a test kit proves an engine
adheres to them; Drishti invokes and runs engines almost entirely through configuration. Be creative and bold: generation from
configuration, dynamic proxies, run-time compiled code. Think of the whole of Drishti, not only risk. Answer: how does a pack
become aware of an engine, and how does an engine help a pack.

**The owner's requirement (2026-11-22).** An engine is developed and unit-tested without the Drishti application. It depends
only on a small JDK-only engine API and, if it wants them, drishti-tools, Drishti Lake and Rachana. Its own test kit runs with
no server. Deploying is copying its JAR into the server's lib folder: the server discovers it at start, checks API
compatibility, and binds it from `config/engines/<name>.yaml`. No engine is compiled into Drishti; adding or upgrading one
needs no Drishti build. Drishti Risk is engine #1 delivered this way. **This is the acceptance test of the design, and
section 0 is built on it.** Text of the first design that it supersedes is marked DELETED where it stood; git keeps it.

## Contents

0. [The acceptance test, and the answer in one page](#0-the-acceptance-test-and-the-answer-in-one-page) · 1. [What exists today](#1-what-exists-today) ·
2. [Drishti-wide view](#2-drishti-wide-view) · 3. [The engine model](#3-the-engine-model) ·
4. [Packs and engines](#4-packs-and-engines) · 5. [Drishti's configuration surface](#5-drishtis-configuration-surface) ·
6. [The engine kit (TCK)](#6-the-engine-kit-tck) · 7. [The generation ladder](#7-the-generation-ladder) ·
8. [In process or a separate process](#8-in-process-or-a-separate-process) · 9. [Drishti Risk as engine #1](#9-drishti-risk-as-engine-1) ·
10. [Two paper tests](#10-two-paper-tests) · 11. [What stays in Drishti](#11-what-stays-in-drishti) ·
12. [Migration of today's engines](#12-migration-of-todays-engines) · 13. [Repository and coordinates](#13-repository-and-coordinates) ·
14. [Estimate and the combined i8 plan](#14-estimate-and-the-combined-i8-plan) · 15. [Risks](#15-risks) ·
16. [Decisions EN-1 to EN-44](#16-decisions-en-1-to-en-44)

## 0. The acceptance test, and the answer in one page

**The owner's acceptance test (2026-11-22, [I8_CARRY_IN](I8_CARRY_IN.md)).** Every line must hold. A design choice that breaks
one of them is wrong, whatever else it buys.

| # | The test | How the design meets it | What proves it |
|---|---|---|---|
| A1 | An engine is developed and unit-tested **without the Drishti application** | it compiles against `engine-api` (JDK only) and, if it wants them, drishti-tools, Drishti Lake and Rachana; no Drishti artifact is on its class path, in any scope (0.2) | the "names no host" guard of every engine repository; its build resolves on a machine that holds no `com.ash.drishti` artifact |
| A2 | Its own test kit runs **with no server** | `engine-tck`: a JUnit harness and a command that need a JDK 21 and nothing else (section 6) | the kit's run in the engine's own build |
| A3 | Deploying is **copying the engine JAR** into the server's lib folder | one self-contained JAR per product, into `<home>/lib/engines` (0.3, 0.4) | the drop-in test (0.11) |
| A4 | The server **discovers it at start** | `ServiceLoader` over each JAR's own class loader (0.4, 0.5) | the start log; Admin → Engines |
| A5 | It checks **API-version compatibility and refuses in words** | the descriptor says what the JAR was built against; one rule; one sentence naming both versions and what to do (0.6) | the kit's delivery group; the drop-in test's too-new JAR |
| A6 | It **binds from `config/engines/<name>.yaml`** | inputs mapped to lake tables and contracts, parameters, outputs to lake and live tables, schedules, access (sections 4.2, 5) | the Admin card: every input's table and the rule that bound it |
| A7 | **Never compiled into Drishti**; adding or upgrading an engine needs no Drishti build | no engine class and no engine dependency in Drishti's reactor; the same server jar, by hash, runs engine version N and N+1 (0.8, 0.11) | two guards in Drishti; the drop-in test |
| A8 | **Drishti Risk** (risk-kernel, market, CCR) is engine #1 delivered this way | one JAR that registers `market-risk` and `ccr` (section 9) | the R2 gate: Drishti's goldens through the dropped JAR |

### 0.1 The shape in three lines

1. **An engine is a JAR made somewhere else.** Its source names no host. It compiles against a small JDK-only API and, if it
   wants them, the three family libraries; its kit runs on a laptop with a JDK.
2. **The server is a host that was built before the engine existed.** At start it opens each JAR of `<home>/lib/engines` in its
   own class loader, finds the engines by `ServiceLoader`, compares versions, and refuses in a sentence what does not fit.
3. **Configuration joins them.** `config/engines/<id>.yaml` maps the engine's inputs to lake tables, sets its options and
   schedules, sends its outputs to lake and live tables and says who may ask. A pack names engine outputs, never a JAR.

### 0.2 What an engine may depend on

| Library | Scope in the engine's build | Used for | In a server it comes from |
|---|---|---|---|
| `com.ash.engine:engine-api` | provided | the contract (section 3) | the server, shared (0.5) |
| drishti-tools (`com.ash.tools:tools-*`) | provided, optional | the xlsx writer for workbooks, settings, codec, time and concurrency helpers | the server, shared |
| Rachana core (`com.ash.rachana`) | provided, optional | evaluating its own explanation formulas; EL values | the server, shared |
| Drishti Lake, API only (`lake-api`, `lake-contracts`) | provided, optional | contract definitions; the read API's types | the server, shared |
| Lake's readers and loader, `engine-inputs-lake`, `engine-inputs-files`, `engine-tck` | test, and the engine's own command line | inputs from files or a lake with no server; a test lake built from JSONL | not used inside a server: the host supplies `Inputs` |
| Anything else (Arrow, a maths library, a native binding behind a Java fallback) | compile, **merged into the engine JAR** | the engine's own business | the engine's JAR only (0.5) |
| Any `com.ash.drishti` artifact, Spring, a servlet or web type | **forbidden** | | invisible: the engine's class loader cannot reach them |

**Data in a host comes only through `Inputs`** (EN-40). The owner allows an engine to use Lake's read API and loader, and it
does: standalone, `engine-inputs-lake` binds its inputs to lake tables with no server, and the loader builds its test lakes.
Inside a server the same rows arrive through `Inputs`, which Drishti implements over the same Lake read API, with the site's
binding, row rules and masks applied. One reading code serves both; an engine that opened a lake by path inside a server would
pass by the access rules, so the specification forbids it (8.4) and the kit runs every case through a recording `Inputs`.

### 0.3 The engine JAR

| Rule | Why |
|---|---|
| One file, `<name>-<version>.jar`, with the engine's classes **and its private libraries merged in** (no relocation is needed: the loader isolates them) | deploying is copying one file (A3) |
| No class of the platform packages (`com.ash.engine`, `com.ash.tools`, `com.ash.lake`, `com.ash.rachana`) inside | they are the server's (0.5); the kit refuses a JAR that bundles one |
| `META-INF/services/com.ash.engine.Engine` lists each engine class; `META-INF/drishti-engine/<id>.engine.yaml` is each engine's descriptor | discovery (0.4); the kit checks the two lists agree |
| A JAR may register several engines; they share its classes | Drishti Risk: one JAR, `market-risk` and `ccr`, one risk kernel |
| Native code only as resources of the JAR, behind a guard that falls back to Java, unpacked under the engine's work folder | no executable is placed on a server (the Windows machine) |
| The kit's certificate (`<jar name>.certificate.json`) travels beside it when the site asks for evidence (0.7) | never a second file the server needs in order to load it |

`engine gen stubs` writes a build file that does all of this (the merge, the services file, the provided scopes).

### 0.4 The folder, and discovery at start

- **The folder is `<home>/lib/engines`** (`drishti.engine-lib.path`, `DRISHTI_ENGINE_LIB_PATH`; absolute or anchored to a named
  root). It sits beside `lib/ext` and, like it, is kept on an upgrade. It is not `lib/ext` itself, because that folder is one
  shared class loader that connectors and modules see, and engines need a loader each (0.5). An engine JAR found in `lib/ext`
  is not loaded from there; Health says where it belongs, as it does today for a server jar found there.
- **At start**, for each JAR in name order: open its loader; `ServiceLoader.load(Engine.class, loader)`; for each provider make
  one instance (the constructor does no work) and read `descriptor()`; run the checks of 0.6; register the engine under its id
  in the one engine registry (today's switch registry); then read `config/engines/<id>.yaml`.

| What is there | State in Admin → Engines | Words |
|---|---|---|
| JAR and engine file, the checks pass, `enabled: true` | `running` | |
| the same, `enabled: false` | `off` | who, when, why (as today) |
| JAR, no engine file | `not configured` | "market-risk 1.0.0 is installed but has no config/engines/market-risk.yaml. `python tools/drishti.py engine template market-risk` prints one from its descriptor." |
| engine file, no JAR that registers the id | `not installed` | "config/engines/market-risk.yaml names an engine that no JAR in /opt/drishti/lib/engines provides." |
| JAR that fails a check | `blocked` | the sentence of 0.6 |

None of these stops the server, a module or another engine (the optional-externals rule). A JAR whose descriptor cannot be read
has no id yet: it is listed by file name with the reason.

### 0.5 Class loading: what is shared, what is isolated

```
JDK
 ├─ kernel loader: the server jar (Drishti, Spring, and the platform libraries: engine-api, engine-host, tools, Lake, Rachana)
 │    ├─ lib/ext loader (the site's drivers and connector jars)            kernel first
 │    └─ one loader per .dmod module                                       kernel first, then lib/ext, then its own
 └─ one loader per engine JAR (its parent is the JDK only)
        com.ash.engine..  com.ash.tools..  com.ash.lake..  com.ash.rachana..   ──▶ the kernel loader, always (parent-first)
        everything else                                                        ──▶ the JAR itself, or not found (child-only)
```

1. **The platform packages are shared and parent-first.** There is one copy of the API's types in the JVM, so the `Answer` an
   engine returns is the host's `Answer`. Only the public packages of each library are handed over, never an `internal` one.
2. **Everything else is the JAR's own, and only the JAR's.** A class that is neither a platform package nor in the JAR is not
   found. The engine cannot see Spring, Jackson, a Drishti class, `lib/ext`, a module or another engine, so it behaves the same
   under every server release, and two engines may carry two versions of one library.
3. This is stricter than the module loader on purpose. A module is built with Drishti and wants the kernel's libraries (kernel
   first for everything). An engine is built without Drishti and must not notice which libraries a kernel happens to ship.
4. A platform class found inside an engine JAR is never used; the kit refuses the JAR and the server notes it in Health.
5. One loader **per JAR**, not per engine: the engines of one product share its classes (Risk's kernel is loaded once).
6. The thread's context class loader is the engine's loader for the length of every call into it (libraries that look
   services up by it find their own).
7. The loader is one small class (`EngineJarLoader`, in `engine-host`) used by the kit, by `engine-cli` and by Drishti. A JAR
   that loads in the kit loads in a server.

It is isolation of names, **not a security boundary** (3.8): a JAR in the folder runs with the server's rights.

| Alternative | Why not |
|---|---|
| All engine JARs in the shared `lib/ext` loader | no isolation: two engines' copies of one library collide, an engine sees Spring and starts to use it, and A1 is lost in practice |
| Share only the API; each engine bundles tools, Lake and Rachana (child-first) | nothing but the API to check, but Lake's storage libraries would sit in every engine JAR, and a storage fix would need every engine rebuilt: against "fix bugs in one place" |
| JDK module layers | the same isolation, but every private library must be a well-formed module; no gain here |

### 0.6 Compatibility, checked at start and refused in words

A descriptor states what its JAR was **built against**: `api: "1.4"` and, for each family library it uses,
`platform: { tools: "1.2", rachana: "1.0", lake: "0.9" }`. One rule for all four: **the same major, and the server's minor is at
least the engine's** (below 1.0 the minor counts as the major). It is enough because each of the four is additive-only within a
major (the engine API by the specification's section 13; tools by TL-12; Lake and Rachana by their frozen API dumps).

| The server provides | The JAR was built for | Result |
|---|---|---|
| engine API 1.4 | 1.0 to 1.4 | loads |
| engine API 1.4 | 1.5 | `blocked`: "market-risk 1.2.0 (drishti-risk-1.2.0.jar) was built for engine API 1.5; this server provides 1.4. Install a Drishti release that provides 1.5 or later, or the engine build for 1.4. Nothing else is affected." |
| engine API 1.4 | 2.0, or 0.x | `blocked`, the same sentence |
| tools 1.2 | `platform.tools: "1.3"` | `blocked`: "…needs drishti-tools 1.3; this server provides 1.2. …" |

| Pair | Rule | When it does not hold |
|---|---|---|
| engine ↔ engine API | above | `blocked` (`ENG-1002`) |
| engine ↔ tools, Lake, Rachana | above, for those it declares | `blocked` (`ENG-1005`) |
| engine ↔ the engine file's pin (0.7) | exact, when the file carries one | `blocked` (`ENG-1011`) |
| two JARs ↔ one engine id | never | both `blocked`, naming both files (`ENG-1006`); an upgrade replaces the file |
| pack ↔ engine | the pack's `uses.engines` range holds and every output it names exists | that pack's uses are off, in words (4.3); the engine runs for others |
| engine ↔ contracts | by major, at binding (4.2) | `blocked`, naming the input |
| engine ↔ stored answers | the stamp holds the engine version | an answer of another version is never served |
| kit ↔ engine API | the same major | the kit refuses to run |

Each Drishti release states one line, also shown in Admin → Engines and at `GET /api/v1/engines/platform`: "provides engine API
1.4, tools 1.2, Lake 0.9, Rachana 1.0". A server provides one API major; whether a server of API 2 also runs 1.x engines is
decided when 2.0 is designed, not now.

### 0.7 Trust

Facts: the JVM has no sandbox; a JAR in the lib folder runs with the server's rights, as every jar in `lib/ext` and the server
jar itself do; `.dmod` modules are verified by a hash list today and are not signed (signing is planned with the security
release).

| Level (`drishti.engine-lib.trust`) | An engine starts when | For |
|---|---|---|
| `folder` (default) | its JAR is in the folder and passes 0.6. Writing to that folder is the act of trust, as it is for `lib/ext` | development, demos, a site that controls its install folder |
| `pinned` | also: its engine file carries `engine.version` and `engine.sha384` equal to the JAR's, and the kit's certificate beside the JAR matches that hash | production (the deployment guide recommends it) |
| `signed` | **not in i8.** When `.dmod` signing is built, the same verifier and key store accept engine JARs signed the JDK's standard way | one signing mechanism for modules and engines, built once |

Under every level Admin and Health list each JAR: file, SHA-384, the engines and versions it registers, the publisher, and the
certificate when one is beside it ("no kit certificate" otherwise). The pin lives in the engine file, so a production upgrade is
two reviewable facts: a new file in the folder and a changed hash in configuration.

### 0.8 Engines and `.dmod` modules: one way each

| | A `.dmod` module | An engine JAR |
|---|---|---|
| Holds | a domain's host side: packs, contracts, screens, vocabulary, access rules, Spring adapters | arithmetic and its explanation |
| Built | with Drishti, against the kernel API, for a kernel version (and, first-party, one kernel build) | without Drishti, against the engine API |
| Installed | a file in the modules folder, verified against its hash list | a file in `lib/engines` |
| Class loader | kernel first | the platform packages only (0.5) |
| Names the other | its packs say `uses.engines: [ { engine: market-risk, version: ">=1.0 <2", … } ]` | names nothing |

**An engine is a plain JAR in the lib folder that a module refers to by name. It is not a `.dmod` kind, and a `.dmod` never
carries one.** Reasons: (1) the `.dmod` format, its verifier and its loader are tied to the kernel's version, which is the tie
the acceptance test forbids; (2) an engine author would need Drishti's module tool to package, which fails A1 and A3;
(3) two carriers would be two install paths, two loaders and two sets of checks for one job. A distribution lists both, and its
installer copies each to its folder. `module.yaml` `compute.engines:` goes at R2: what a module needs of engines is the sum of
its packs' `uses.engines` (section 4).

### 0.9 At start only

- The folder is read once, at start, as `lib/ext` and the modules folder are. Adding, upgrading or removing an engine is: stop
  the node, change the file, start. On a cluster it is one node at a time, and requests route to the nodes that run the engine
  (5.5), so there is no outage.
- **Without a restart:** the switch (on, off); "restart engine" in Admin after an edit of its engine file (sessions and the
  instance are closed and opened anew on the same JAR and loader); rebinding of inputs.
- **Why not hot replacement in i8:** unloading needs every class, thread and native library of the old JAR released, a native
  library cannot be unloaded while its loader lives, and a leak is silent; Windows locks an open JAR, so the copy itself fails
  there; two versions of one engine inside one JVM in the middle of requests complicate the stamp rule; and nothing has asked
  for it. The per-JAR loader keeps the door open: hot **add** of a new JAR is a small later step if a site needs it.

### 0.10 The minimum an engine implements

| Need | Where | Required |
|---|---|---|
| Identity and declaration | the descriptor file; `Engine.descriptor()` | yes |
| Lifecycle | `Engine.open(context)`, `Engine.session(snapshot, inputs, control)`, `Session.close()`, `Engine.close()` | yes |
| Inputs | pulled from the host's `Inputs` as column batches | the host's part |
| Outputs | `Session.ask(request, control)` returns an `Answer` of tables | yes |
| Cancel | `Control.check()` once a batch and once a declared latency | yes |
| Determinism | no interface: an obligation the kit's laws prove; the stamp from `Stamps.of` | yes |
| Explain and workbook | `Explaining` on the session | when an output column is a measure |
| Resource limits | `MemoryBudget.reserve` before allocating; `Session.memoryBytes()`; the host's executor | yes |
| Status words for the Admin card | `Engine.notes()` (default: none) | no |
| Incremental updates, partition | `Incremental`; `Partitioned` and `Assembling` | no |

The smallest engine is a descriptor, one registered class and a session: **two interfaces, eight methods**. Nothing else is
needed to pass the acceptance test; the capabilities are added when an engine has the need.

### 0.11 The proof

| Check | Where it runs | Fails when |
|---|---|---|
| Names no host | every engine repository | a source or a build file names `com.ash.drishti`, Spring, or a Drishti setting |
| Delivery group of the kit (6.1) | the engine's build, no server | the JAR does not load through `EngineJarLoader`, bundles a platform class, has a class reference that resolves nowhere, or its registrations and descriptors disagree |
| No engine in the reactor (from R2) | Drishti | a main class in Drishti implements `com.ash.engine.Engine`, or a pom depends on an engine artifact, or a source imports `com.ash.risk` |
| **The drop-in test** (from H2; in the drill) | Drishti, against the server jar as built | see below |

The drop-in test records the server jar's hash, then with that one jar: starts with an empty `lib/engines` (the sample engine's
uses are off, in words); copies `engine-sample-1.0.0.jar` in and restarts (running; an ask answers); replaces it by `1.1.0`
(the new output is there, the stamp names 1.1.0); adds a JAR built for a newer API (blocked with the sentence, everything else
up). The server jar's hash is the same at the end. At R2 the same test runs with Drishti Risk and the goldens.

### 0.12 The answer in one page

**An engine is a pure, explained function from contract tables to tables.** It declares, in one descriptor file, the contracts
it reads, its parameters, the tables it answers with, which columns are measures, and what it can do (update incrementally,
answer in parts, run in a separate process). It implements two Java interfaces, and one more per capability, from a JDK-only
artifact. It never schedules itself, never knows a user, a pack, a screen or a host. Everything else is the host's, and in
Drishti the host's part is **one generic runtime driven by `config/engines/<id>.yaml`**: which lake tables feed which input,
when it runs, where its outputs go, who may ask.

**Five points where this design departs from the lead's starting notes.**

1. **The lifecycle is not "open, run, cancel": it is engine → session → ask.** The fact that shapes Drishti Risk is a date that
   takes 25 s to open and 3 ms to answer. A run-shaped interface would force every real engine to hide a cache behind it, where
   the host can neither bound its memory nor evict it nor isolate users in it. So the loaded state is a first-class object the
   host owns: a **session** over a **snapshot** of the inputs. A batch engine is a session that answers once. One shape serves
   batch, on demand, reactive and streaming (section 3.3).
2. **The session key is not a business date.** The genomics paper test (section 10.2) has no date at all: its snapshot is a
   cohort at a data version. A snapshot is a set of named dimensions the descriptor declares; `date` is the commonest, not a law.
3. **Drishti does not need a new engine mechanism: it has three, and the work is to fold them into one.** Today there are two
   interfaces both named `ComputeEngine` (the switch lifecycle in `kernel.api.engines`, the reactive compute SPI in
   `api.compute`), a hand-written catalogue of 30 fields per engine (`META-INF/drishti/engines.yaml`), and the reactive
   connector's own declaration of kinds, parameters, reads, triggers and result rules inside `pack.yaml`. The reactive package
   (3,977 lines: triggers, coalescing, result cache, watermarks, parameter checks) **is** the generic host runtime, written
   once already for a narrower contract. This design promotes it; it does not build beside it (section 12).
4. **Generate what people read; bind what the machine runs; compile only against an oracle.** Per-engine endpoint and binder
   *code* should not be generated at all: one generic endpoint family and one binder read the descriptor at run time. What is
   worth generating at build time is what a person or another tool reads: screens as Sutras, documents, the OpenAPI text, the
   workbook skeleton, kit stubs, typed parameter records, AI tool definitions. Run-time compilation is kept for expressions in
   configuration (Rachana-EL), never for engine logic, and never from Java text in a configuration file (section 7).
5. **Explanation is checkable by a kit that knows nothing about the measure.** Each explanation step carries its formula in
   Rachana-EL over named values. The kit evaluates the steps with Rachana's reference evaluator and recalculates the workbook
   with a spreadsheet engine the author did not write. "A metric ships with its playground" (RK-15) moves from the risk
   product's SPI up into the engine specification, and for simple measures the workbook itself is generated from the steps.

**What a pack sees.** A pack stays data, contracts and screens with no compute code. It states which contracts its kinds conform
to and which engine outputs its screens use. The host binds an engine's inputs to the conforming kinds of the modules that are
on, and publishes the engine's outputs back as tables that Sutras, fragments, live tables, the pivot engine, Studio and AI tools
read like any other table. An engine absent or switched off turns those features off in words; nothing fails to start
(section 4).

**Process model.** In process first, as RK-10. The process binding is not new work: it is today's worker pool (the Python
engines of reactive connectors), put behind the same contract. Arrow Flight is the remote transport when a measured trigger asks
for one (section 8).

**Size.** About **65 bd** for the i8 scope (about 75 with 15% contingency), of which about 35 bd is in a new small repository
and needs no Drishti change. The acceptance test adds about 7 bd net to the first estimate (section 14): the JAR loader and the
delivery proof are small; the larger part is that Drishti's banking adapters may no longer compile against Risk's types.
Compiled expressions (about 12 bd) and the Flight transport (about 6 bd) are later, each on a measured trigger.

## 1. What exists today

| Piece | Where | What it is | Fate |
|---|---|---|---|
| Switch lifecycle `ComputeEngine` (`id`, `start`, `stop`, `memoryBytes`, `datesOpen`, `threadPrefixes`, `running`) | `kernel.api.engines`; registry, switches, files, history, cluster, gate filter, Admin controller: 2,501 lines in `server.engines` | on or off per node, needs, drain, state words (`running`, `off`, `blocked`, `not installed`), `changed:` written from Admin | **kept**: it is the host's lifecycle. One generic adapter implements it for every descriptor engine |
| Reactive compute SPI `ComputeEngine` (`manifest`, `compute`, `incremental`, `affected`), `EngineManifest`, `EngineRequest`, `EngineInputs` (lists of `DataNode`), `EngineResult` (maps) | `api.compute` | the code behind a reactive connector: thread-safe, deterministic, seeded, no state between requests | **replaced** by the engine API: same ideas, column batches instead of documents, sessions instead of per-request inputs |
| Reactive host: `ReactiveSpec` (kinds, typed `ParamSpec`, `reads`, engine `type: java | python`, version, triggers `write-back`, `live`, `data-version`, `schedule`, coalescing, cache, limits), `Triggers`, `Coalescer`, `ResultCache`, `Watermark`, `JavaRunner`, `PythonRunner` | `compute.reactive` (3,977) and `compute.worker`, `compute.env`, `compute.approval` | a generic runtime for declared computations, with out-of-process Python workers and an approval of libraries | **promoted**: becomes the engine host runtime |
| Engine catalogue | `META-INF/drishti/engines.yaml` in each module: per engine `opens`, `file`, `switch`, `env`, `aliases`, `needs`, `api`, `console`, `words`, `menus`, `help`, `tiles`, `bi`, `cli`, `playgrounds`, `connectors`, `kinds`, `inputs`, `ai-tools` | what disappears when the engine is off | **generated** from the descriptor plus the pack's use of it; the hand-written list goes |
| `config/engines/*.yaml` (11 files) | site configuration | the switch plus the engine's own settings as `drishti.<prefix>.*` | **kept and extended** (section 5); old keys stay as aliases |
| `<home>/lib/ext` and `SharedLib` | the install folder | the site's jars (drivers, connector plug-ins) in one shared loader under the kernel's, read at start, listed in Health with their hashes | **kept** as it is; `<home>/lib/engines` is its sibling for engine JARs, one loader each (0.4, 0.5) |
| `ModuleClassLoader`, `ModuleVerifier`, `FirstParty` | `drishti-module-runtime` | a module's loader (kernel first, kernel internals hidden), its hash-list checks; no signing yet | **kept** for modules; the engine loader is a smaller, stricter sibling in `engine-host` |
| Implementations of the reactive SPI | `VarResultEngine`, `ExposureResultEngine`, `CorrelationEngine`, `BetaEngine`, `VectorSumEngine` (sample), Python `path-sum` | adapters and small analytics | section 12 |
| `module.yaml` `compute.engines:`, `provides.kinds:` | every `.dmod` | which engines and kinds a module brings | `provides.kinds` **kept**, with the conformance and use declarations of section 4. `compute.engines` **goes** at R2: a module brings no engine (0.8) |

Three of the eleven switch files are not engines in this document's sense (`dataload`, `flight`, `memory-lake`), one is a
library (`openblas`) and one is the kernel's query engine (`pivot`). They keep their switches (section 2.2).

## 2. Drishti-wide view

### 2.1 The test: is it an engine?

A piece of Drishti is an engine, and should adopt the contract, when all four hold:

| # | Test | Why it is the line |
|---|---|---|
| T1 | **Pure**: its answer is a function of declared contract inputs, parameters and options. It writes nothing but its answer | the host can cache, replay, shard, move to another process and prove it |
| T2 | **Deterministic**: the same inputs give the same bits | the kit's laws and the accuracy rule depend on it |
| T3 | **Closed**: no network, no clock, no file outside its work folder, no user | isolation, offline sites, the Windows machine |
| T4 | **It has figures worth explaining**: someone will ask "why this number" | otherwise it is a query, and the pivot engine or Rachana-EL already does it |

This is a different question from the four product tests of the parked-products note (own compute, outside buyers, standard
contracts, worth a product's cost). Those decide whether an engine deserves its own repository. T1 to T4 decide whether a piece
of code is an engine at all. Many engines will live inside a module's jar and never be a product.

### 2.2 The map

| Area of Drishti | Engine? | Relation to the engine model | Consequence |
|---|---|---|---|
| **VaR, ES, parametric VaR, backtesting, stressed windows, sensitivities, MR cube, FRTB-SA** | yes | outputs of the `market-risk` engine (Drishti Risk) | engine #1 (section 9) |
| **Exposure (EE, PFE, EPE), pre-deal, SIMM, SA-CCR** | yes | outputs of the `ccr` engine (Drishti Risk) | engine #1, second descriptor |
| **Liquidity, climate, non-financial risk** (designed in Risk K15) | yes | each a descriptor over the risk kernel; liquidity is paper-tested in 10.1 | K15 designs are written as descriptors first: contracts, parameters, outputs, measures |
| **Regulatory and management reports** | no, an **output** | a report is a table whose cells trace to measures: an output of the engine that owns the measures | no report machinery per engine; the explain rule covers each cell |
| **Official record, sign-off, approvals** | no | a host workflow over stored outputs. The engine offers the output; freezing, four eyes and audit are Drishti's (controls, i10) | stays in Drishti |
| **Python what-if workers, reactive connectors** | **yes, in disguise** | already declared computations with typed parameters, triggers and a worker process | become the process binding of the same contract (section 8, 12) |
| **A pricing supplier (QLRisk) for what-if and stress** | yes, **outside** | an engine that is a *supplier* to another engine through a named port. Drishti still never prices: the supplier is someone's engine, switched on by a site | "not supplied" when absent (optional externals) |
| **Pivot and BI engine, its computed measures** | **no** | the kernel's general query engine: any table, any user-written measure, interactive. It fails T4 by design: its measures are the user's EL | it **reads** engine outputs as tables; engines with the partition capability are offered to it as measure providers through one generic adapter (replacing the bespoke `VarPivotProvider`, `VarPivotLens`, `MrCubeExtras` over time) |
| **Rachana-EL, Sutra compile and bind** | no | the language. Engines use it in three places only: configuration expressions, explanation steps, generated screens | no engine evaluates EL on a hot path of its own |
| **Live tables, blotters, FORM results** (Rachana live) | no | the one live result-set engine. An engine never pushes rows: the host re-asks what changed and feeds the differences by key and version | no second live mechanism |
| **Lake: storage, contracts, inbox loading, in-memory lake** | no | loaders have side effects (they fail T1). The lake is where inputs come from and where stored outputs go, by contract | loaders are not engines; they **emit the data-version event** engines react to |
| **Source-system modules** (Endur, Calypso, Murex, WSS, SwapOne, ICI) | no | mappings into one trade kind: loader mappings | untouched. Their kinds are what an engine's inputs bind to |
| **Feeds** (World Bank, FRED, BoC, StatCan, OECD, GDELT, market data, news) | no | outside I/O, not deterministic (fails T1, T3) | untouched. **Derived indicators** over what they load (rebasing, year-on-year, seasonal adjustment) are an engine candidate for the economy module |
| **Kafka and live streams** | no | a stream reaches the lake or a live table; the host turns it into `Changes` for an engine that declares it can apply them | an engine never names a broker; a broker absent means no incremental updates, calmly |
| **Capital markets, US equities, market data** | candidates | returns, betas, correlations (today `CorrelationEngine`, `BetaEngine`) are a small analytics engine | they move with the risk product as RK-5 says, or become the first module-local engine (EN-24) |
| **Genomics** | candidate | the `association` kind and the `GWAS` word are a stored result today; computed on demand it is an engine (10.2) | the non-risk proof of the abstraction |
| **Packs** | no | data, contracts, screens. They **use** engines (section 4) | packs lose compute code and reactive-connector declarations |
| **`.dmod` modules, the kernel** | user | a module's packs **use** engines by name; the kernel runs them. An engine always arrives as a JAR in `lib/engines` with a config file, never inside a module (0.8) | `module.yaml` `compute.engines` goes at R2 |
| **Distributions, editions, licensing** | n/a | a distribution lists engines like modules; a descriptor names its licence feature | "Drishti Risk" edition = the distribution with those engines on |
| **Drishti\*net cluster** | host | placement per engine, session affinity, shards from the partition capability | section 5.5 |
| **Compute pools, admission, capacity** | host | the limits of a config file are enforced by what exists (`Pool`, admission, the capacity planner) | one accounting: an engine's sessions reserve from the node's budget |
| **RBAC and controls (i10)** | host | the engine receives a visibility, never a principal (RK-9, generalised) | access rules are written once, per output, in config |
| **GenAI and MCP (i9)** | consumer | every output is a tool: its parameters are the tool's input schema, its answer a table, its explanation a second tool. Called as the user, in the `interactive` class | the `ai-tools: []` lists of today's catalogue are generated, not written |
| **Arrow Flight** | transport | serves output tables; later carries the process binding | no engine names Flight |
| **Mnemonics and their switches** | host | a pack gives an output a mnemonic; engine off means the mnemonic is "not active" with the reason | one rule for both switches |
| **Homes, Studio, Help, playgrounds** | consumers | Help shows the generated page of each running engine; Studio's palette lists outputs as tables and the generated fragments; a playground page exists for every measure because the explain rule guarantees its parts | no hand-written playground page per measure |
| **Elements** | none | generated screens are fragments only (no bespoke UI) | a guard, as today |
| **Native maths (OpenBLAS)** | library | an engine's own dependency behind its own guard; the switch and health card stay the host's | not an engine |
| **Windows and offline sites** | constraint | no native launcher: a process engine is started as `java` or `python`; engine JARs come from `lib/engines`; nothing is fetched at run time | in the specification |

### 2.3 Consequences that shape the specification

1. **One model for four ways of running.** On demand, batch, reactive and streaming differ only in *who calls `ask` and when*
   (the trigger), never in the engine's code. The specification has no "batch engine" type.
2. **Inputs and outputs are contracts, in the Lake contract schema.** There is one definition of a contract in the family
   (`lake-contracts`); the engine API does not invent a schema language. Output tables use the Data Protocol's types, so a
   table an engine returns is served to a Sutra without conversion.
3. **The engine owns arithmetic; the host owns everything a deployment differs in.** Access, placement, schedules, storage,
   names, screens, words, licences.
4. **Nothing in the engine API is about risk.** No scope grammar, no hierarchy, no limit, no currency. Those are the risk
   kernel's (RK-6) and reach the host as parameters and outputs like any other engine's.
5. **Where the model must not apply:** anything with a side effect (loaders, feeds, write-back, approvals), anything that is a
   general language over the user's own definitions (pivot, EL), platform services (Flight, memory lake, cluster), and screens.
   Forcing them in would be the over-abstraction the lead's note warns of.
6. **Fragments are functions; engines are functions too, one level down.** A fragment is a typed function from tables to a
   screen. An engine is a typed function from tables to tables. Both declare table parameters as contracts, both are versioned
   by major, both are proven by cases as data. The same words are used on purpose (`with:`, contract, major, cases).

## 3. The engine model

Signatures and rules are in the specification; this section gives the shape and the reasons.

### 3.1 Descriptor

One YAML file in the jar at `META-INF/drishti-engine/<id>.engine.yaml`. It is the single source for the binder, the kit, the
generators, the Admin card, Help and the AI tool list. The engine's class is not named in it: the JAR's
`META-INF/services/com.ash.engine.Engine` registers it (0.4).

| Block | Says | Used by |
|---|---|---|
| identity | `id`, `version`, `title`, `about`, `publisher`, `api` (the engine API version it was built against), `platform` (the tools, Lake and Rachana versions it was built against, for those it uses), error-code prefix, licence feature | the start checks of 0.6, Admin |
| `snapshot` | the dimensions that key a session (`date`; or `cohort`; or none) | session manager, cache keys |
| `inputs` | per named input: contract and major, required or optional, the columns read, the scope columns, `mode` (`session` or `request`), whether changes can be applied | binding, masks, pruning, the kit's coverage gate |
| `options` | deployment settings with types, defaults, bounds | config validation, `CONFIGURATION.md` |
| `params` | request parameters with types, defaults, allowed values | forms, endpoints, tools, the kit |
| `outputs` | per output: `shape` (`table`, `row`, `series`), columns with types, key, order, which are **measures**, their exactness class, parameters accepted | Data Protocol schema, stores, screens |
| `capabilities` | `explain`, `workbook`, `incremental`, `partition`, `process`, `supplier` | binder, cluster, kit |
| `uses` | named supplier ports (another engine's output it may ask for) | wiring, "not supplied" |
| `limits` | declared memory per input row, cancel latency | admission, the kit |

### 3.2 Inputs

- **Rows of a contract as column batches**, pulled by the engine through the host (`Inputs.table(name).scan(..)`). Arrays per
  column (`double[]`, `long[]`, `String[]`), reused per batch, as Lake's read API hands them over: no object per row, no copy.
  Vector columns (a 509-number P&L vector per position) come through a vector sink of the same four arguments as Lake's, so
  Drishti's adapter over Lake adds one interface call per row and no allocation.
- **Own types only, JDK-only API.** Arrow is not in a public signature. The lead's note says "Data Protocol/Arrow" for tables;
  the recommendation is the Data Protocol's *types* in the API and Arrow as an optional adapter artifact (`engine-arrow`) and
  as the wire format of the process binding. Reason: Arrow Java moves its major version often, and an API that outside authors
  compile against should not move with it (the same reasoning as Lake LK-13).
- **Missing input is an answer.** An absent table or column gives `NOT_FOUND` with the names, never an error and never a
  substitute (Risk section 3, made general).
- **Two input modes.** `session`: the engine loads the snapshot once, unmasked, and applies each request's visibility itself
  (Risk). `request`: the host reads the inputs for each request as the viewer, masks applied, and the session lives for that
  request (today's reactive connectors). The descriptor chooses per input.

### 3.3 Lifecycle

```
Engine      NEW ──open(context)──▶ OPEN ──close──▶ CLOSED            (FAILED from any)
Session     OPENING ──▶ READY ──apply(changes)──▶ READY′ … ──close──▶ CLOSED   (STALE when the host sees newer inputs)
Request     ACCEPTED ──▶ RUNNING ──▶ COMPLETE | NOT_FOUND | REFUSED | CANCELLED | TIMED_OUT | FAILED
```

| Way of running | Trigger (config) | What the host does |
|---|---|---|
| On demand | `request` | finds or opens the session for the snapshot, calls `ask`, serves the tables |
| Batch | `schedule` (cron and zone, or `every`) | opens the session, asks the configured requests, stores the tables, closes |
| Reactive | `data-version` (an input's stamp moved) | marks the session stale, reopens or applies changes, re-asks stored and subscribed requests |
| Streaming | `changes` (rows from a live table or stream) | `apply(changes)`, re-asks subscribed requests, sends the differences by key to the live table |
| Chained | `after` (another engine's output stored) | as reactive, on that output's stamp |

The engine starts no thread and keeps no timer. It computes on the host's bounded executor and checks a `Control` for
cancellation and deadline.

### 3.4 Determinism

Same descriptor version, options, input stamps, request and seed give the **same bits**: over two runs, over 1, 4 and all
threads, over any batch size the host delivers rows in, over in-process and process bindings. No wall clock, no unseeded
randomness, no dependence on hash order. Each measure column declares its exactness class: `bits` (the default), or a stated
relative tolerance for native maths (today's OpenBLAS parity rule, 1e-12). Every answer carries a **stamp** (engine, version,
descriptor hash, options hash, input stamps, request hash, seed): it is the cache key, the provenance row and the audit line.

### 3.5 Explain and the workbook

Every column marked `measure: true` needs both, or the kit refuses the engine.

- **Explanation as data.** A list of steps; each has a name, words, a Rachana-EL formula over named values, the values, and
  its result. The last step is the measure. A step the engine cannot express in EL is `opaque` with the reason and a workbook
  cell that holds it; the kit counts opaque steps and a release gate bounds them.
- **Workbook.** `.xlsx` with a values-only `Data` sheet and a `Main` sheet of live formulas; each measure is a defined name.
  The engine writes bytes to a stream (the API does not name a workbook library; the family's is `tools-xlsx`).
- **Generated workbook (bold, section 7.2).** When every step of a measure is in the translatable subset of EL, the workbook
  is generated from the steps and the author writes none of it. Hand-written templates stay possible and stay byte-identical
  (Risk's are kept as they are).

### 3.6 Resource limits

Declared in config, enforced by the host: memory (the session reserves from a budget before it allocates; over it the open is
refused in words, never an out-of-memory error), threads (the executor's size), sessions kept open (least recently used leave
first; pinned snapshots stay), concurrent requests per class (`interactive`, `event`, `batch`), a request's deadline, a work
folder's disk. This is today's `hot-dates`, `open-heap-mb`, `memory-budget-mb`, `load-threads`, `max-disk-gb` and
`refusal-ttl-seconds`, written once for all engines instead of once in `var.yaml` and again in `exposure.yaml`.

### 3.7 Versioning and compatibility

| Thing | Rule |
|---|---|
| Engine API | semantic versioning on the public packages, additive-only within a major; a descriptor states the version it was built against; the host refuses another major or a newer minor than its own, in words (0.6) |
| Descriptor format | `engine: 1`; unknown keys are errors |
| Engine version | semantic. Within a major: outputs and parameters only grow, defaults do not change, no number changes for an existing case (the kit's additive-only gate replays the last release's cases) |
| Contracts | by major (`position@1`); a pack's kind conforms to a major |
| Pin | optional: the engine file may name the exact version and the JAR's SHA-384; required under trust `pinned` (0.7). Never a range, a branch or a snapshot |
| Platform libraries | tools, Lake and Rachana follow the same rule as the API, for the versions the descriptor's `platform` states |
| Stored answers | the stamp holds the engine version: a new version never serves an old answer |

### 3.8 Security and isolation

- **There is no in-process sandbox on the JVM any more** (the security manager is deprecated for removal and switched off in
  later JDKs). In process therefore means *trusted*: an administrator put the JAR in `lib/engines`, and under trust `pinned`
  also wrote its hash into configuration (0.7). A JAR a site will not trust in its server's process runs in a separate
  process under the operating system's limits (section 8).
- The kit's static scan refuses a jar that uses the network, starts a process, exits the JVM, creates threads, holds mutable
  static state, reads the clock or reaches into the host by reflection. It is a lint, not a sandbox, and is described as such.
- Each engine JAR loads in its own class loader; the engine API, tools, Lake's API and Rachana are the shared packages and
  nothing else of the server is visible (0.5).
- **Certificate.** A kit run writes a certificate (engine, version, jar hash, API and kit versions, laws passed, results hash).
  It is evidence: Admin and Health show it, or "no kit certificate", for every JAR. Under trust `pinned` an engine starts
  only when the certificate beside its JAR matches the pinned hash. DELETED: `allow-uncertified` and its four-eyes flow.
- Configuration never holds code. The only expressions are Rachana-EL, which is hermetic.

### 3.9 Many tenants, many users

- The engine receives a **visibility** and an actor name for the audit line; never a principal, a role or a session. A request
  without a visibility is refused, never read as "all".
- In `session` mode the engine may hold only viewer-independent state; any cache it keeps of a viewer's view is keyed by the
  visibility's signature. The kit proves it with two interleaved viewers and with the filter-equivalence law (6.2).
- A viewer's mask on a column the engine computes from is a refusal by the host before the engine is called, unless the
  descriptor marks the column as passed through unchanged (then the host masks the output column).
- No mutable statics: two instances of one engine in one JVM (two tenants, two tests) are proven apart.

## 4. Packs and engines

*The owner's question: how does a pack become aware of an engine, and how does an engine help a pack.*

### 4.1 The three declarations

Nobody names anybody's code. Each side declares against **contracts**, and the host joins them.

| Who | Declares | Where |
|---|---|---|
| The engine | the contracts it **needs** (`position@1`, `pnl-vector@1`) and the outputs it **offers** (`var`, `contributions`) | its descriptor |
| The pack | the contracts its kinds **conform to**, and the engine outputs its screens **use**, with a version range and whether the use is optional | `pack.yaml` (`kinds.<kind>.conforms`, `uses.engines`) and, summed, `module.yaml` |
| The site | which engines are on, any binding the contracts leave open, options, schedules, where outputs go, who may ask (and, under trust `pinned`, the exact version and hash) | `config/engines/<name>.yaml` |

```yaml
# pack.yaml (market-risk), the additions only
kinds:
  position:     { conforms: [ risk/position@1 ] }
  scenario-pv:  { conforms: [ risk/scenario-pv@1 ] }
  var-limit:    { conforms: [ risk/var-limit@1 ] }
uses:
  engines:
    - engine: market-risk
      version: ">=1.0 <2"
      outputs: [ var, contributions, path, backtest ]
      optional: false                 # false: the pack's risk screens are off without it, in words
      as:                             # the names the pack gives the outputs in its own world
        var: { kind: var-result, mnemonic: VRES }
```

`conforms` is the **contract profile** of the Lake and Risk designs: the product owns the contract, the pack's kind adds its
host keys (mnemonic, pack, binding hint). It is checked when the pack is built (`lake contracts check`), so a pack cannot claim
a contract its kind does not meet.

### 4.2 Binding: how an input finds its table

For every input of every engine that is on, in this order; the first that answers wins and the result is shown in Admin with
the reason.

| # | Rule | Example |
|---|---|---|
| 1 | The site's file names it: a lake table, or a kind | `inputs.positions.kind: position` |
| 2 | Exactly one kind of the modules that are **on** conforms to the contract at that major | `risk/position@1` is met only by `market-risk`'s `position` |
| 3 | Several conform: the engine is **blocked** with the candidates listed. The host never guesses | "market-risk needs positions (risk/position@1): `position` (market-risk) and `fund-position` (funds) both conform; name one in config/engines/market-risk.yaml" |
| 4 | None conforms, the input is required: the engine is **blocked**: "no kind conforms to risk/position@1; modules on: …" | |
| 5 | None conforms, the input is optional: the engine runs; the outputs that need it answer `NOT_FOUND` naming it | stressed VaR without a stress set |

- **Several packs and modules feed one engine** without any of them knowing: positions from `trading`, scenario PVs from
  `market-risk`, FX from `market-data`. The engine sees three inputs.
- **Several kinds into one input** (positions from Endur and from Murex, kept as separate Delta sources): the site lists them
  under one input; the host presents their union with a source column. The engine sees one table.
- **Column names that differ** are mapped in the site's file (`columns: { DESK: TT_DESK }`); a row filter is one Rachana-EL
  expression. Nothing else is allowed there.
- Connectors stay the kernel's: an input binds to a kind or a lake table, and the normal routing reads it. An engine never
  sees a connector, a credential or a path.

### 4.3 Absent, off, or the wrong version

| Situation | What the pack's user sees | What starts |
|---|---|---|
| The engine's JAR is in `lib/engines` but there is no engine file | as below, "…is installed but not configured" | everything else |
| The engine's jar is not installed | screens and words that use it are hidden; opened by link: "VaR needs the market-risk engine, which is not installed on this server" | everything else |
| Installed, switched off | the same, "…is off (turned off by a.smith on 2026-11-20: maintenance)" | everything else |
| On another node only | the request is routed there (as engine switches do today) | |
| Version outside the pack's range | the pack's uses of it are off: "needs market-risk >=1.0 <2; 2.1.0 is installed" | the engine itself runs for others |
| An optional use | the panel shows its empty state with the reason; the rest of the screen renders | |

This is the optional-externals rule applied to engines: absence switches a feature off, calmly, and is never a start failure.
The same gate hides the mnemonic ("not active"), the menu entries, the Help pages, the tiles, the command-line words, the pivot
measures and the AI tools, because all of them are now derived from one fact: this pack uses that output.

### 4.4 Outputs back into the pack's world

An output is published once by the host and then is an ordinary table everywhere:

| Consumer | How it sees the output |
|---|---|
| Data Protocol | a table `engine.<id>.<output>` with the schema from the descriptor (types, key, formats); a parameterised answer is addressed by its normalised parameters, as a reactive kind's id is today (`VRES DESK:RATES~ccy=EUR`) |
| A Sutra | a `data:` entry naming the output and its parameters; panels bind to it like any table. In Rachana Core it is a table name; the parameter form is the `drishti` profile's source form |
| Fragments | a fragment's table parameter is a contract; an output whose columns meet it fits. `finance/var-tiles@1`, `finance/var-histogram@1` and `finance/limit-gauge@1` take the market-risk outputs because Risk publishes them under the columns the `finance` library declares (RK-14). The misfit message is Rachana's (`RCH-2116`) |
| Kinds and mnemonics | the pack's `as:` gives an output a kind and a mnemonic in its own vocabulary (`var-result`, `VRES`): a **virtual kind**, computed, never stored. This is what the reactive connector declaration does today, in four lines instead of forty |
| Live tables | an output with a key and a `changes` trigger is declared as a live table; the host feeds it differences by key and version; grids update in place |
| The lake | an output with a `store:` is written as a contract table (the official record, a nightly ladder); then it is a kind like any loaded one, with history |
| Pivot and BI | a stored output is a table; an output with the partition capability is offered as a measure over any grouping |
| Studio | the palette lists the outputs as tables (with the sample from the kit's cases) and the engine's generated fragments |
| AI tools | one tool per output, one for its explanation |
| Access | section 5.4: who may ask is the host's rule per output; what rows they see is the visibility |

### 4.5 What the engine does for the pack

| The pack gets | Without writing |
|---|---|
| Computed measures on demand over its own data, for any scope the engine's parameters allow | compute code, a connector declaration, a controller |
| Recompute when its data changes (a load, an intraday row) and screens that update | triggers, caches, coalescing |
| "Why this number" on every measure, and a formula workbook the user can open | an explain panel, a playground page, an export |
| A parameter form and a default screen per output, as fragments it can use, wrap as a variant or replace | a form |
| Help pages, the configuration reference, the contract pages, AI tools | documents |
| A proof: the kit's certificate for the engine, and the pack's own conformance check for its kinds | a test harness |

**So a pack is data, contracts and screens.** The guard that follows: a pack or module that ships a formula for a figure an
engine offers fails the build (today's "server computes risk" check, made general: "the engine computes it").

### 4.6 Worked example 1: the market-risk pack and Drishti Risk

1. Drishti Risk ships `market-risk.engine.yaml`: inputs `positions` (`risk/position@1`), `vectors` (`risk/pnl-vector@1` or
   `risk/scenario-pv@1`), `scenario-sets`, `fx-scenarios` (optional), `limits` (optional), `actual-pnl` (optional); snapshot
   `date`; outputs `var`, `contributions`, `path`, `history`, `backtest`, `parametric`, `stress-window`, `sensitivities`,
   `cube`; capabilities explain, workbook, incremental (the intraday overlay), partition (shards).
2. The `market-risk` pack's kinds declare `conforms` (4.1). `trading` and `market-data` declare theirs.
3. `drishti-risk-1.0.0.jar` is copied into `lib/engines`; the site's `config/engines/market-risk.yaml` names no artifact (under
   trust `pinned` it carries the version and the hash). Binding rule 2 resolves
   every input; nothing is written by hand. The site adds only what is its own: `hot` snapshots, memory, the nightly official
   capture, who opens it.
4. The pack's Sutras use `finance/var-tiles@1` and `finance/var-histogram@1` over `engine.market-risk.var` and `…path`. `VRES`
   stays the pack's mnemonic for the virtual kind `var-result`.
5. A load of `scenario-pv` for a date moves that table's stamp; the `data-version` trigger reopens the date; subscribed screens
   are re-asked. An intraday PV row from Kafka reaches the live table; the `changes` trigger applies it to the open session.
6. The engine is switched off on a node: `VRES`, the VaR menu, the playground and the AI tool disappear there; requests route
   to a node that runs it; with none, the words of 4.3.
7. What leaves the pack and the banking module: the reactive connector blocks for `var-engine` and `exposure-engine`
   (kinds, params, result rules, triggers), `VarResultEngine`, `ExposureResultEngine`, the catalogue entries.

### 4.7 Worked example 2: the genomics pack and a statistics engine

Hypothetical engine `cohort-stats` (publisher: the genomics module's; see the paper test in 10.2).

1. Descriptor: snapshot `cohort` (text) with no date; inputs `calls` (`genomics/variant-call@1`: SAMPLE, VARIANT, GENOTYPE),
   `samples` (`genomics/study-sample@1`: SAMPLE, STATUS, covariates), `variants` (`genomics/study-variant@1`, optional, for
   gene names); parameters `test` (`allelic`, `burden`), `gene`, `min-maf`; outputs `association` (key VARIANT; measures
   ODDS_RATIO, CHI2, P), `burden` (key GENE); capabilities explain, workbook, partition by chromosome.
2. The `genomics-showcase` pack already provides `variant-call`, `study-sample`, `study-variant`; it adds `conforms` to each
   and `uses.engines: [{ engine: cohort-stats, outputs: [association, burden], as: { association: { kind: association,
   mnemonic: GWAS } } }]`. Its stored `association` kind becomes the virtual one; the Manhattan plot's Sutra does not change,
   because the columns are the same contract.
3. No date dimension, no currency, no hierarchy, no limit: nothing in the engine API had to be switched off or faked.
4. The explanation of one variant's P value is four steps (the 2×2 table of allele counts by status, the expected counts, the
   statistic, the tail probability); the workbook is generated from them (`CHISQ.DIST.RT`).
5. Without the engine the pack still shows its data screens; the association screens say the engine is not installed.

## 5. Drishti's configuration surface

### 5.1 The file

`config/engines/<name>.yaml`, one per engine, imported by `application.yaml` as today; `<name>` is the engine's id. It never
names a JAR or a class. DELETED: `engine.artifact` (Maven coordinates or a jar path) and `engine.allow-uncertified`. The schema is in the specification
(section 9); the shape:

```yaml
drishti:
  engines:
    market-risk:
      enabled: ${DRISHTI_MARKET_RISK_ENABLED:true}
      changed: { by: "", at: "", reason: "" }          # written by the server from Admin, as today
      engine:                                          # optional: the file's name is the engine id, the JAR is found in lib/engines
        version: 1.0.0                                 # a pin: another installed version is refused in words
        sha384: "…"                                    # a pin of the JAR; both required under trust `pinned`
        run: in-process                                # or process
      snapshot: { hot: 10, pinned: { month-end: { back: 13, keep: disk } } }
      inputs:                                          # only what binding by contract leaves open
        positions: { kinds: [ position ], columns: { DESK: TT_DESK }, where: "@.STATUS != 'DEAD'" }
      options: { confidence: 0.99, es-confidence: 0.975, rank-rule: kth-worst }
      triggers:
        - { on: request }
        - { on: data-version }
        - { on: changes, input: vectors, from: live:scenario-pv.intraday }
        - { on: schedule, cron: "0 30 18 * * MON-FRI", zone: America/Toronto, ask: official-capture }
      asks:
        official-capture: { outputs: [ var ], params: { scope: "BANK" }, as: system }
      outputs:
        var:  { serve: [ protocol, flight ], live: true }
        official: { store: { lake: "${lake.root}/risk", table: var-official, mode: by-snapshot } }
      access:
        opens: [ role:market-risk, pack:market-risk ]
        visibility: banking.var-access                 # a named host rule that turns a principal into a visibility
      limits: { memory-mb: 0, threads: 0, request-seconds: 30, concurrent: { interactive: 16, batch: 2 } }
      placement: { roles: [ risk ], shards: { count: 3 } }      # the shard unit is the descriptor's
```

Two site-wide settings sit beside the engine files, in the generated `CONFIGURATION.md` like every other:
`drishti.engine-lib.path` (default `<home>/lib/engines`) and `drishti.engine-lib.trust` (`folder` or `pinned`, 0.7).

Rules: every path absolute or anchored to a named root (`explicit-paths`); the server writes only `enabled` and `changed`
(`connectors-read-only`, the same discipline); every key appears in the generated `CONFIGURATION.md` with the descriptor's
words; an unknown key or an option the descriptor does not declare refuses the engine's start with the key; today's
`drishti.var.*` keys are kept as aliases generated from a table in the Risk descriptor (`EngineKeyAliases` exists).

### 5.2 Triggers

`request`, `schedule`, `data-version`, `changes`, `after`. These are the reactive connector's `write-back`, `live`,
`data-version` and `schedule`, renamed where the new word is plainer, with coalescing (`quiet`, `max-wait`) and the result cache
kept as they are. The engine cannot add a trigger kind; a new one is a host feature for all engines.

### 5.3 Outputs

`serve` (Data Protocol, Flight, JSONL and Arrow files from the command line), `live` (a live-table declaration is generated:
key from the descriptor, version from the answer's stamp sequence), `store` (a lake table under the output's contract; `mode`:
`by-snapshot` replaces that snapshot's rows, `append` keeps history). The engine has no result sink: it returns tables and the
host stores them. (Risk's `ResultSink` port goes; section 9.)

### 5.4 Access

| Question | Answered by |
|---|---|
| Who may open the engine's pages, words and tools | `access.opens` (roles, packs), as the catalogue's `opens` today |
| Who may ask a given output | per output `access` (default: as `opens`); i10 adds control rules here, not in engines |
| Which rows a caller's answer covers | `access.visibility`: a named host rule turns the principal into a `Visibility` (`VarAccess`, `ExposureAccess` today). No rule named: the engine is refused at start, never "all" |
| What is recorded | the host's audit line per request: actor, output, normalised parameters, stamp, outcome |

### 5.5 Switches, Admin and the cluster

- **Switch.** Unchanged for the operator: Admin → Engines, `drishti.py`, the file. The card is drawn from the descriptor:
  title, version, certificate, bound inputs with their tables and stamps, sessions open with their memory, triggers and their
  last firing, outputs and who uses them (which packs). "Off" removes everything derived (4.3).
- **Services.** `dataload`, `flight`, `memory-lake`, `openblas`, `pivot` stay on the same page under the heading Services: one
  registry, two kinds of entry (EN-23). They have no descriptor.
- **Placement.** Per engine, as module parts are placed (no default "all"). A snapshot's session lives on the nodes placed;
  requests carry session affinity; a node without the engine routes.
- **Shards.** An engine with the partition capability names its shard unit; the host splits a snapshot over the shard owners,
  asks each for a `Partial` and calls `assemble`. This is `ShardedMeasure` and Drishti\*net today, for any engine. The kit's
  additivity law is what makes it safe to offer.
- **Capacity.** Each session reserves from the node's budget; the Admin capacity page and the sizing planner read the
  descriptor's declared memory per row.

## 6. The engine kit (TCK)

`engine-tck`: a test jar and a command. `engine tck run --engine <jar> --cases <dir>` writes a results file and a certificate.
It needs a JDK 21 and nothing else: no server, no network, no Drishti artifact (A2). It opens the JAR through the same
`EngineJarLoader` a server uses, so what passes here loads there. The same artifact holds a **JUnit harness** for the author's
own unit tests (`EngineHarness.of(MyEngine.class).inputs(rows…).ask(…)`: in-memory inputs, a budget, a work folder under the
test's temporary folder), so an engine's first test needs no JAR and no case file. The same command runs inside Drishti
against the engine as configured (the host only adapts).

### 6.1 What it proves

| Group | Proof |
|---|---|
| **Delivery** | the JAR as a server will meet it: it loads through `EngineJarLoader`; `META-INF/services` and the descriptors name the same engines; no platform class is bundled; every class reference resolves in the JAR, the platform packages or the JDK (a closed-world link check, so a missing private library is found here and not in production); `api` and `platform` equal the versions the JAR was compiled against; no host type is referenced |
| Descriptor | valid against the schema; equal to what the engine object reports; every option, parameter and column has words; capabilities equal the interfaces implemented |
| Static scan | no forbidden API, no mutable static, no thread creation, dependencies inside the declared list |
| Contracts | the engine reads only declared inputs and columns (a recording input catches others); every declared column is read by some case; outputs match their schema, keys unique, order as declared |
| Cases | the author's cases as data: expected tables with doubles compared by raw bits, expected `NOT_FOUND` with names, expected refusals by code |
| **Laws** | section 6.2: derived from every case, no extra work for the author |
| Lifecycle | the state machine; `close` returns memory and threads; two instances apart; cancel within the declared latency; a small budget gives a refusal in words, not an out-of-memory error |
| Explain | every measure, every method option: steps recompute; the workbook recalculated by an outside spreadsheet engine equals the measure |
| Gates | coverage (every output, every allowed parameter value, every own error code); additive-only against the last release's cases; a missing oracle fails, never skips |

### 6.2 The laws

Each law takes a case the author wrote and checks a property that must hold for **any** engine. An author who writes twenty
cases gets about two hundred checks.

| Law | Statement |
|---|---|
| Repeat | two runs give the same bits |
| Threads | 1, 4 and all threads give the same bits |
| Batching | the same rows delivered in batches of 1, 7 and 4,096 give the same bits |
| Order | the same rows in another order give the same bits (unless the input is declared order-sensitive, with the reason) |
| **Filter equivalence** | the answer under a visibility equals the answer, under "all", over inputs physically reduced to that visibility. This is the isolation proof |
| Interleaving | two viewers asking concurrently each get their solo answer |
| **Incremental equivalence** | `apply(changes)` then `ask` equals a fresh session over the changed inputs (capability `incremental`) |
| **Additivity** | partials over any split, assembled, equal the whole (capability `partition`) |
| Binding | in process and in a separate process give the same bits (capability `process`) |
| Absence | removing an optional input changes only the outputs declared to need it; removing a required one gives `NOT_FOUND` |
| Seed | another seed changes only outputs declared stochastic |
| Explain | for every measure cell of the case, the explanation's last step equals the cell |

### 6.3 How an outside engine passes

Write the descriptor; `engine gen stubs` writes the case skeletons and a build file (provided scopes, the merge of private
libraries, the services file); implement, with unit tests on the harness; write cases; run the command; hand over the JAR, and
the certificate beside it where the site asks for one. A bank's own engine and a vendor's are certified by the same command on their own machine,
with no Drishti present. `risk-tck` (Risk section 7) becomes: this kit, plus Risk's cases and its recorder comparison.

## 7. The generation ladder

**Principle: generate what is read, bind what is run, compile only against an oracle.**

| Rung | What | When made | Now or later |
|---|---|---|---|
| G0 **Interpret** | endpoints, config validation, the Admin card, the catalogue, mnemonic and menu gating, the Data Protocol schema: all read from the descriptor at run time by one generic implementation | never generated | **now** |
| G1 **Build-time generation** (`engine gen`) | Sutra screens and parameter forms from fragments; the Help page; contract pages; the OpenAPI text; `CONFIGURATION.md` entries and a config template; workbook from explain steps; kit stubs; typed parameter and row records; a typed client interface; AI tool definitions | at the engine's build; deterministic, hashed, committed, `--check` in CI | **now**, in two cuts (14) |
| G2 **Run-time binding** | records built and read through method handles; column accessors through `LambdaMetafactory`; the typed client through `java.lang.reflect.Proxy`; process stubs. Zero per-engine glue, cached per descriptor hash | when the engine loads | **now** |
| G3 **Compiled expressions** | the Rachana-EL in config (`where:`, column maps) and in fragments' hot bindings, compiled; Rachana's interpreter is the oracle | when a config loads | **later**, on a measured trigger |
| G4 **GenAI drafts descriptors and config** | a model proposes; the schema and the kit dispose | authoring time | i9 |

### 7.1 Why not generate the glue code

A generated controller, binder and writer per engine is source nobody wrote and everybody must debug, in every engine's
repository, regenerated on every API change. One generic endpoint family (`/api/v1/engines/{engine}/{output}`, `…/explain`,
`…/workbook`, plus the Data Protocol tables) and one binder do the same with nothing to regenerate. The OpenAPI document is
still generated, because people and tools read it.

### 7.2 The bold parts worth doing

1. **Screens with no author.** From a descriptor alone: a page per output (header, parameter form, table, the measure tiles,
   a "why" drawer, the workbook button) as a Sutra that uses only `core` fragments, and one typed fragment per output that a
   pack can `use:`. They are defaults: a pack wraps them (a variant) or ejects. This is the plug-and-play promise made
   visible: copy a JAR into `lib/engines`, add a config file, get a working, explained screen.
2. **Workbooks from explanations.** EL steps in the translatable subset become Excel formulas over the `Data` sheet
   (arithmetic, comparisons, `sum`, `avg`, `min`, `max`, `count`, `if`, sorted rank picks → `SMALL`/`LARGE`, weighted sums →
   `SUMPRODUCT`, the distribution functions Excel has). The kit already recalculates the workbook, so a wrong translation
   cannot ship. Measures outside the subset keep a hand-written template.
3. **Typed surface without hand-written types.** `engine gen` writes `VarParams`, `VarRow` and `interface MarketRisk` from the
   descriptor; at run time `host.engine("market-risk").as(MarketRisk.class)` is a proxy over the generic call. An embedder
   gets compile-time names; the wire stays one shape.
4. **AI tools for free.** Each output's parameter schema is a tool's input schema; descriptions are the descriptor's words.
5. **Compiled expressions with a shadow oracle (G3).** A compiled filter runs beside the interpreter on a sample of batches for
   its first minutes; a single difference turns the compiled path off for that expression, raises a health note, and the
   interpreter's result is the one served. Differential tests in the build, a shadow at run time.

### 7.3 What is rejected

| Idea | Why not |
|---|---|
| Java text in configuration, compiled at run time | accuracy first: unreviewed code deciding a number; needs a full JDK on the server (`javax.tools`), which a bank's run-time image often is not; no sandbox to run it in |
| Engine logic generated from templates | an engine's arithmetic is the one thing that must be written, reviewed and certified by a person |
| Spring beans, controllers or classes generated per engine | G0 does it with none |
| A second expression language for configuration | Rachana-EL exists and is hermetic |

### 7.4 What Java 21 allows

Drishti builds for Java 21 (the newer level may not reach production).

| Mechanism | On 21 | Use |
|---|---|---|
| `java.lang.reflect.Proxy`, method handles, `VarHandle`, `LambdaMetafactory`, records | yes | G2, now |
| Hidden classes (`defineHiddenClass`), class data (`defineHiddenClassWithClassData`, `MethodHandles.classData`) | yes | G3: **template specialisation**. A small set of pre-compiled template classes (a filter loop, a projection loop) is loaded once per expression as a hidden class whose class data is that expression's method-handle tree. The handles are constants to the JIT, so the loop compiles as if written by hand, and no bytecode library is needed |
| Vectorised kernels (plain Java loops over column arrays, one per operator and type) | yes | G3, first step: an expression becomes a plan of kernels over a batch. Often faster than per-row compiled code and needs no class definition at all |
| Class-File API | no (final in 24) | G3 later form, if production moves; nothing waits for it |
| A bytecode library (ASM) at run time | possible | not recommended: one more dependency in the server for what class data already does. The kit's static scan may use one (a tool, not the product) |
| Run-time `javac` | needs a JDK image | rejected (7.3) |
| Foreign Function and Memory API | preview on 21 | native maths stays on the bundled binding, as today |
| Vector API | incubator | not used |
| Security manager | deprecated | no in-process sandbox (3.8) |
| Virtual threads | yes | not for engine work: the host's engine executor is platform threads (the few-core start-up finding) |
| Scoped values, structured concurrency | preview | context is passed explicitly |

**G3 trigger.** Build it when a profile shows expression evaluation above a tenth of a session's open or of a subscribed
re-ask at production size. Until then the interpreter is the only path, which is also the simplest thing to prove.

## 8. In process or a separate process

RK-10 stands and is made general: **in process by default; a separate process on a named trigger; the seam is the API.**

| Trigger | From |
|---|---|
| (a) the host's other requests lose more than 20% at p99 with the engine loaded, or a collector pause exceeds the limit, after tuning | RK-10 |
| (b) a snapshot at target size does not fit one JVM beside the host | RK-10 |
| (c) the engine is not Java (Python, a native library behind its own runtime) | RK-10, and today's Python engines |
| (d) the engine must restart or upgrade without the host | RK-10 |
| (e) the site does not trust the JAR in its server's process (a third party's engine) | new (3.8, 0.7) |
| (f) the engine holds native code that can take the process down (a pricer) | new |

- `engine.run: process` in config; the descriptor must declare the `process` capability; the kit's binding law proves the two
  the same.
- **The process binding in i8 is today's worker pool** (`Pool`, `WorkerProcess`, `PythonRunner`, Arrow files in a scratch
  folder, the approved environments), re-faced to carry the engine contract. It already serves (c). A Java engine in a worker
  uses the same pool with a `java` launcher. Nothing native is started: `java` or `python` only.
- **Arrow Flight** is the remote transport (another machine, or a host that is not a JVM), built when a deployment needs it:
  actions for open, session, apply, close; a ticket per ask; inputs pulled back through the host's Flight service or, when
  co-located, read from the mapped files the lake reports as local. No shared-memory protocol of our own (RK-10's rejection
  of option C stands).
- Requests, answers, partials, changes and explanations are plain values with a stated wire form (tables as Arrow IPC, the
  rest as canonical JSON), so the seam costs nothing to reserve.
- K11's measurement is kept and reads on the generic host, so the figures are valid for every engine.

## 9. Drishti Risk as engine #1

### 9.1 The layering

```
drishti-risk-<version>.jar            ONE file in lib/engines, built in the drishti-risk repository; registers two engines
   risk-engine-market, risk-engine-ccr     thin: a descriptor each (market-risk, ccr), and an Engine over the product's runtime
   risk-market, risk-ccr ──▶ risk-kernel   the product: RiskModule, Measure, Explainer, Playground (its inner SPI)
   private libraries, merged in            Arrow, the OpenBLAS binding (Java fallback), a YAML reader
compiles against, provided                 engine-api · tools-xlsx · lake-contracts · Rachana core (explanation formulas)
```

The engine API is how a host runs any engine. Risk's module SPI (its section 4.2) is how a measure is added to the risk
kernel. They are different questions and both stay. What must not happen is two sets of **ports**. The product's Maven modules
stay as they are for embedders and `risk-cli`; the engine JAR is one more build output of the same repository, and it is the
only thing a Drishti install receives.

### 9.2 What changes in the Risk design

| Risk design | Change |
|---|---|
| 4.1 facade `RiskRuntime`, `RiskDay`, `Answer` | kept for embedders and `risk-cli`, with its explicit module list. `RiskDay` is a `Session` whose snapshot is `{date}`; `Answer`, `Explanation`, tables are the engine API's types, not second ones. A host never calls the facade: it finds the two `Engine` classes by `ServiceLoader` |
| 4.3 `RiskInputs` | **is** the engine API's `Inputs` (column batches, vector sink). `risk-files`, `risk-lake` become `engine-inputs-*` adapters usable by any engine, in the engine repository |
| 4.3 `ResultSink` | **goes**: the engine returns tables; the host stores declared outputs |
| 4.3 `WorkDir`, `AuditSink`, `Clock` | the engine API's `EngineContext` |
| 4.3 `DocumentStore` | **goes**: documents the engine reads (system baskets, report definitions) are inputs under contracts; captures, sign-offs and the official record are stored outputs and the host's workflow over them. Settled file by file at K0 with the E0 review |
| 4.3 `SuppliedVectors` | a named supplier port (`uses:`), answered "not supplied" when unbound (RK-12 unchanged) |
| 4.3 `PartitionTransport` | goes: `Partial` is a value; carrying it is the host's |
| 4.2 `Measure` additivity, determinism; `Explainer`; `Playground` | the kit's laws and the explain rule, generic. RK-15 is satisfied by the specification |
| `Principal` out, visibility in (RK-9) | the engine API's `Visibility` |
| `RSK-` codes (RK-2) | declared in the descriptors; mapped to `DRS-` by the generic host once |
| 7 `risk-tck` | the engine kit plus Risk's cases; the reference recorder and the raw-bit rule are unchanged (RK-18) |
| 5.3 `drishti-risk-host` | **DELETED.** There is no Risk adapter module in Drishti: the generic host and `config/engines/market-risk.yaml`, `ccr.yaml` do its work; the visibility rules stay in the banking module as host rules |
| 5.2 the 14 controllers and 12 properties classes that stay | properties become `options` (old keys as aliases). A route that is a plain ask becomes an alias of the generic endpoint; a controller with a host workflow (sign-off, saved views, basket edits) stays and asks through the generic host (9.3) |
| 8 repository layout, RK-19 the pin | adds `risk-engine-market`, `risk-engine-ccr` and the merged engine JAR; `risk-api` depends on `engine-api`. Drishti has **no Maven dependency** on any Risk artifact: a distribution lists the JAR's version and hash, and packaging copies the file into `lib/engines` |
| 9.1 guard "names no host" | unchanged: `com.ash.engine` is not a host |
| 9.1 Drishti guard "public API only" | replaced by "no source imports `com.ash.risk`" (0.11) |
| RK-7 the OpenBLAS switch | `config/engines/openblas.yaml` becomes the option `linear-algebra: native or java` of the two engines (old key as alias); the Health line comes from `Engine.notes()` |
| K4, K5, K6, K7, K12 | built against `engine-api` 0.x from the start, so ports are cut once |
| RK-10, K11 | section 8 |
| K15 (liquidity, climate) | each design delivered as a descriptor with cases |

**Order matters.** `engine-api` 0.1 must exist before Risk's K4. It is frozen as 1.0 only after three engines pass the kit
(market risk, CCR, and the non-risk one), which is the rule-of-three check against shaping it to VaR.

### 9.3 What Drishti keeps of risk, and what it compiles against

After R2 no Drishti source names a Risk type. What stays in the banking module works through the engine API's values (tables,
requests, visibilities) and output names:

| Stays in the banking module | Works through |
|---|---|
| Access rules (`VarAccess`, `ExposureAccess`) | they produce a `Visibility`: data of the engine API |
| Routes that are a plain ask | the generic endpoints; the old paths are aliases in the module's route table |
| Sign-off, approvals, saved views, basket edits | host workflow over stored outputs and inputs; they ask through the generic host by output name |
| Pivot (`VarPivotProvider`, `VarPivotLens`, `MrCubeExtras`) | `partial` and `ask` by output name; tables only. Still banking's own code in i8; the generic partition-to-pivot adapter is later (section 12) |
| Flight form, the intraday stream | outputs served by the host; the `changes` trigger |
| Explain and why panels | the generic `…/explain` and `…/workbook` |
| The goldens (`VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden`, `PackagedVarGoldenTest`) | **expected values unedited**; the calling code asks through the generic host with the Risk JAR copied into a temporary lib folder |

A wrong output or column name in this code is no longer a compile error. It is caught at start instead: every output and column
a pack or a module uses is checked against the installed descriptor, and a mismatch turns that use off with the names
(`ENG-5033`). The kit's cases stay the proof of the numbers.

## 10. Two paper tests

### 10.1 A second risk engine: a liquidity ladder

Cash-flow ladder and survival horizon over supplied cash flows (from Risk K15's scope; chosen because its shape is unlike VaR).

| Aspect | Fits? | Note |
|---|---|---|
| Inputs: `cash-flow@1` (date, amount, currency, product class), `liquid-asset@1`, `run-off-rule@1` (scenario, class, rate), FX | yes | three packs could supply them |
| Snapshot `{date}`; a nightly batch stored to the lake, and what-if on demand | yes | `schedule` plus `request`: same session, two triggers |
| No vectors, no heavy state; a session is small | yes | `inputs.mode: session` with trivial memory: the degenerate case costs nothing |
| Outputs: `ladder` (key BUCKET, CURRENCY), `survival-horizon` (one row), `buffer` | yes | `ladder` is a `series` output |
| Measures: net outflow per bucket, cumulative gap, horizon | yes | steps are sums and a first-crossing: all in the translatable subset, workbook generated |
| Partition by currency or entity | yes | sums are additive; the horizon is the non-linear last step in `assemble` |
| Scenario as a parameter, with the alternatives beside it | yes | parameters |
| **Found:** time buckets are a parameter set, not a column of an input | changed the model | the specification allows a **list-of-records** parameter type (a small table as a parameter), which the forms generator renders as an edit grid |

### 10.2 A non-risk engine: cohort statistics (genomics)

Allelic association and gene burden over variant calls (4.7).

| Aspect | Fits? | Note |
|---|---|---|
| **No business date** | changed the model | the snapshot is a set of declared dimensions; an engine may declare none (then the session is keyed by the inputs' stamps only) |
| Inputs of 45,000 to 10^8 calls, read once, grouped by variant | yes | column batches with dictionary-like text arrays; `scope-columns: [SAMPLE]` |
| Visibility by sample (consent, study membership), not by book | yes | visibility is a filter over scope columns; nothing in it is a hierarchy |
| Partition by chromosome across nodes | yes | counts are additive; the test statistic is the last step |
| Incremental: new samples arrive | yes | counts update; equivalence law applies |
| Explain: the 2×2 table to the P value | yes | four EL steps; `CHISQ.DIST.RT` in the workbook |
| **Found:** exact tests on tiny counts need arbitrary precision; the "bits" class must allow a decimal column | changed the model | measure columns may be `decimal` (compared as exact text) as well as `number` (raw bits) |
| **Found:** an output of 12,000 rows per ask, read page by page | changed the model | outputs declare `paged: true`; the host keeps the answer for paging under its stamp instead of re-asking |
| Words like scope, desk, currency | absent | confirmed: none is in the engine API |

### 10.3 What the three engines agree on

Session over a snapshot; inputs by contract as column batches; a visibility as a filter over declared scope columns; parameters
typed in the descriptor; tables out with keyed, ordered, typed columns; measures with steps; partials that add. That is the
API. Everything any one of them needed alone (scope grammar, time buckets, genotype encoding) stayed in that engine.

## 11. What stays in Drishti

The generic engine host (promoted from the reactive package) and its Spring wiring; the reading of `lib/engines` at start; the switch registry, files, history, Admin
page; binding by contract and its Admin view; triggers, coalescing, the result cache; access rules and the visibility rules of
each module; storing outputs to lakes; live-table feeding; the generic endpoints, Flight and Data Protocol serving; cluster
placement, shards, routing; capacity and admission; mnemonics, menus, Help, Homes, Studio; the official record and approvals;
the pivot engine; packs, loaders, feeds, source-system modules. No engine arithmetic.

## 12. Migration of today's engines

| Today | Becomes | When |
|---|---|---|
| `api.compute.ComputeEngine`, `EngineManifest`, `EngineRequest`, `EngineInputs`, `EngineResult`, `EngineProgress`, `ComputeEvent`, `Viewer` | deleted; `com.ash.engine` types. No compatibility adapter is needed once the two rows below are done: after them nothing in the reactor implements the old SPI | H2 (types), R2 (last implementer gone) |
| `kernel.api.engines.ComputeEngine` | kept, renamed `EngineLifecycle` to end the two-names confusion; one generic implementation for descriptor engines; services implement it as today | H2 |
| `compute.reactive` (`ReactiveSpec`, `ParamSpec`, `Triggers`, `Coalescer`, `ResultCache`, `Watermark`, runners) | the host runtime in `engine-host` (moved to the engine repository, host types cut at ports) | R1 beside H1, adopted H2 |
| Reactive connector blocks in `pack.yaml` (`plugin: reactive`, kinds, params, result, engine, triggers) | the pack's `uses.engines` and the site's engine file; the connector plugin stays one release as a reader of the old form, then goes | H2, deleted at R2 |
| `META-INF/drishti/engines.yaml` | generated from descriptors and `uses`; services keep a short hand-written entry | H2 |
| `VarResultEngine`, `ExposureResultEngine` | deleted: `market-risk` and `ccr` answer directly from `drishti-risk-<version>.jar` in `lib/engines` | R2 |
| `CorrelationEngine`, `BetaEngine` | outputs of the market-risk engine: they move with `banking-compute/analytics` (RK-5) into the Risk JAR. If K0's file review keeps them out of Risk, they become one small engine JAR of their own (`market-analytics`), built outside Drishti like any other (EN-24) | R2 |
| `VectorSumEngine` (the compute demo) | `engine-sample-<version>.jar`, built in `drishti-engine`: the specification's sample, the kit's own subject and the drop-in test's JAR. A distribution with the demo on copies it into `lib/engines` | R1 (built), H2 (dropped in) |
| Python `path-sum` and the reactive `python` runner | stay one release behind the old reactive form (the row above it), for the compute demo only; a non-Java engine as a JAR is the process binding's later step (section 8, EN-44) | deleted with that step |
| `var.yaml`, `exposure.yaml`, `frtb-sa.yaml`, `simm.yaml`, `sensitivities.yaml`, `mr-cube.yaml`, `openblas.yaml` | `market-risk.yaml`, `ccr.yaml` with `options`; each old file's switch becomes an output switch inside its engine, so "FRTB off" still works; `openblas` becomes the engines' option `linear-algebra`; old keys are aliases | R2 |
| `module.yaml` `compute.engines:` (banking lists six) and `java.jars: drishti-compute-banking.jar` | gone: the module carries no engine and no compute jar; its packs' `uses.engines` say what it needs | R2 |
| `VarPivotProvider`, `VarPivotLens`, `MrCubeExtras` | a generic partition-to-pivot adapter | after i8 (not in scope) |

**The rule after R2, kept by a guard (0.11):** no main class in Drishti's reactor implements `com.ash.engine.Engine`; no pom
depends on an engine artifact; a test that needs an engine copies its JAR into a temporary lib folder. Services (`dataload`,
`flight`, `memory-lake`, `pivot`) are not engines and stay in-tree (EN-23).

The freeze of RK-20 covers the banking paths; the reactive package joins the freeze list from the day `engine-host` is cut
from it.

## 13. Repository and coordinates

| | Recommended | Alternatives considered |
|---|---|---|
| Home | a new small private repository **`drishti-engine`**, pinned from `~/.m2` like Rachana, Lake, tools | in `drishti-risk` (wrong: non-risk engines would depend on risk); in Drishti's `kernel-api` (wrong: an outside author would compile against the host, and Risk's "names no host" guard would fail); in `drishti-tools` (wrong: tools are domain-free utilities with no contract to certify) |
| groupId, package | `com.ash.engine` | |
| Artifacts | `engine-api` (JDK only; the interfaces, values, descriptor model, schemas) · `engine-host` (`EngineJarLoader`, discovery, the compatibility check, descriptor reader, binder, sessions, limits, triggers behind ports; depends on `lake-contracts`, a YAML reader, Rachana core for EL) · `engine-tck` (the kit and the JUnit harness) · `engine-gen` · `engine-inputs-files`, `-lake` · `engine-cli` (a day of files in, tables and workbooks out, for any engine; `risk-cli` becomes a thin name over it) · `engine-sample` (delivered as a JAR, like any engine) · `engine-bom`. Later, each on a need (EN-44): `engine-inputs-jdbc`, `engine-arrow` | |
| Error codes | `ENG-` | engines declare their own prefix |
| Java level | 21 | |
| Versions | `0.1.0` when Risk's K4 needs it; `1.0.0` after three engines pass | |
| Guards | names no host; public surface dump; `engine-api` has no dependency; no statics; cases additive-only; file cap; header | as the sibling repositories |

## 14. Estimate and the combined i8 plan

Grades: F Fable, O Opus, S Sonnet, H Haiku.

| # | Lane | Content | Grade | bd | Milestone |
|---|---|---|---|---|---|
| E0 | Decisions and review | EN-1 to EN-28; API review against three engines' descriptors written first | F | 2 | start of R1 |
| E1 | Bootstrap | repository, reactor, guards, release script | S | 1.5 | R1 |
| E2 | `engine-api` | interfaces, values, descriptor model, JSON Schemas, error codes, wire forms | O 4, S 2 | 6 | R1 (0.1 before Risk K4) |
| E3 | `engine-host` | cut from `compute.reactive`: descriptor reader, binder (G2), sessions and budgets, limits, cancel, triggers behind ports, the worker pool as the process binding | O 6, S 4 | 10 | R1 |
| E4 | `engine-tck` | harness, case format, the twelve laws, static scan, explain and workbook recalculation, certificate, sample engine and its Python twin | O 5, S 4 | 9 | R1 |
| E5 | Input adapters and `engine-cli` | files, JDBC, Lake; the command (replaces Risk K10's adapters, which leave Risk's estimate) | S | 4 | R1 |
| E6 | `engine-gen`, first cut | Help page, contract pages, config template and `CONFIGURATION.md` entries, OpenAPI, kit stubs, AI tool definitions | S | 3.5 | H2 |
| E7 | `engine-gen`, screens | Sutra page and fragment per output from `core` fragments; mock-ups first (2 to 3 for the owner to pick) | F 0.5, S 4 | 4.5 | H2 |
| E8 | Drishti host | `config/engines` schema and loader; binding by contract and its Admin view; generic endpoints; Data Protocol tables; live feed; stores; `EngineLifecycle` adapter; catalogue generated; pack `conforms` and `uses`; gating of words, menus, Help, tools; the reactive plugin reading the old form | O 6, S 6 | 12 | H2 |
| E9 | Risk as engine #1 | `risk-engine-market`, `risk-engine-ccr`, descriptors, option aliases; Drishti's two engine files; delete the result engines | O 3, S 2 | 5 | R1 (adapters), R2 (Drishti) |
| E10 | Workbook from explanations | EL-to-formula subset, used by the sample engine and offered to Risk's new measures only | O 2, S 1.5 | 3.5 | R2 (cut line) |
| E11 | Non-risk engine built | `cohort-stats` in the genomics module, its cases, its generated screen | S | 3 | R2 |
| E12 | Docs | the specification kept current, author's guide, Admin and configuration pages, deck | S 2, H 1 | 3 | R2 |
| E13 | **The lib folder** | `EngineJarLoader`, discovery, the compatibility check and its sentences in `engine-host`; in Drishti: `lib/engines` read at start, the states `not configured` and `blocked`, the JAR list in Admin and Health, `engine template`, the two settings | O 1.5, S 1.5 | 3 | R1 (loader), H2 (Drishti) |
| E14 | **The delivery proof** | the kit's delivery group and JUnit harness; the drop-in test and the two reactor guards in Drishti | O 1, S 1 | 2 | R1 (kit), H2 (test) |
| E15 | **Risk as one JAR, Drishti off Risk's types** | the merged JAR and its registrations; banking's controllers, pivot adapter, sign-off and explain panels on engine API values only; the goldens' calling code through the generic host, expected values unedited | O 4, S 2.5 | 6.5 | R2 |
| | less: run-time artifact resolution and certificate gating (E8, about 1); `engine-inputs-jdbc` and `engine-arrow` moved later (E5, about 1.5); Risk's pin and scripted import rewrite, D1 and D3 (about 2) | | | about −4.5 | |
| | **i8 scope** | | | **about 74** | |
| | less Risk work this replaces (K10's adapters 2, ports designed once in K4 to K7 about 4, D4's adapter about 3) | | | **about −9** | |
| | **Net addition to combined i8** | | | **about 65** (about 75 with 15%) | |

By model: Fable 2.5 · Opus 32.5 · Sonnet 38 · Haiku 1. The figures for E13 to E15 are estimates made on paper on 2026-11-22;
nothing was measured.

| Later, each on its trigger | bd |
|---|---|
| G3 compiled expressions (kernels, template specialisation, shadow oracle) | about 12 (O) |
| Flight transport of the process binding | about 6 (O 4, S 2) |
| Generic partition-to-pivot adapter | about 5 |
| GenAI drafting of descriptors and config (i9) | in i9's plan |

**Folded into the milestones.**

| Milestone | Engine work | Gate |
|---|---|---|
| H1 ∥ R1 | E0 to E5 and the R1 parts of E13, E14 in `drishti-engine`, no Drishti change; Risk builds on `engine-api` 0.x; E9's adapters | the sample engine and both Risk engines pass the kit **from their JARs, with no Drishti artifact on the machine's class path** (A1, A2); three descriptors reviewed |
| H2 | E6, E7, E8 and the Drishti parts of E13, E14 on the integration branch with the sample engine and the old reactive form still read | **the drop-in test** (A3 to A7 on the sample engine): a JAR copied into `lib/engines` and a config file give a working screen on a server jar built before it; the browser files of the compute demo unchanged |
| R2 | E9 (Drishti side), E15, E10, E11, E12; `engine-api` 1.0 | **A8:** Risk from one dropped JAR, through configuration, with goldens bit-identical; Risk 1.0.0 replaced by a 1.0.1 build with the server jar's hash unchanged; no engine class in the reactor; genomics engine certified; reactive connector blocks gone |

Never more than three builders; E2 → E3 → E8 → E15 is the serial chain (about 34 bd). **Cut lines**, in order, if the release is tight:
E10 (workbooks stay hand-written), E11 (the non-risk engine stays a paper test and `engine-api` stays 0.x), E7 (no generated
screens; packs write theirs). The escape hatch of the combined plan applies unchanged: if Risk misses the R1 gate, E0 to E8 still
ship with the sample engine and the analytics engines.

## 15. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| The API is shaped by VaR | the second customer's engine does not fit | three descriptors written before the interfaces are frozen (E0); 1.0 only after three engines pass; the paper tests already moved the model four times |
| A second set of ports grows in Risk beside the engine API | two ways, drift | section 9.2 lands in the Risk design before K4; a guard in `drishti-risk`: no port interface outside `com.ash.engine` |
| The engine API slips and holds Risk's K4 | R1 late | `engine-api` 0.1 is E2's first two days (inputs, context, values); Risk needs only that slice first |
| A number moves when Risk's intake goes through generic inputs | the product's claim | unchanged rule: raw bits against the recorder at the step (RK-18); the vector sink has Lake's exact shape |
| The speed of a generic binder on the 3 ms path | a visible share | method handles bound once; no map per row; the 5% benchmark gate (Risk section 7) applies to the engine path |
| Generated screens look generic | adoption | they are defaults and are mocked up first; real screens use the `finance` and other domain libraries |
| EL-to-Excel translation is wrong for a function | a wrong workbook | the kit recalculates every generated workbook; subset only; outside it, hand-written |
| The static scan is taken for a sandbox | an untrusted jar in process | stated in the specification and on the Admin card; a JAR a site will not trust in its process runs in a separate one |
| Folding the reactive connector breaks the compute demo or VRES | a regression | the old form is read for one release; the equivalence run of old and new answers on the demo before the old form is deleted |
| Binding by contract surprises an operator | the wrong table feeds an engine | never a guess: ambiguity blocks with the candidates; the bound tables and stamps are on the Admin card and in every answer's stamp |
| Scope: about 65 bd on top of a large combined release | the release slides | cut lines of 14; E0 to E5 need no Drishti change and run beside H1 |
| A private library inside an engine JAR misbehaves when a second copy lives in the server (Arrow's allocator, a native library) | a start failure or a crash in one engine | child-only loading keeps the copies apart; native code is unpacked under each engine's own work folder, so two loaders never load one file; the drop-in test runs Risk beside Drishti's own Arrow |
| Merging private libraries drops a `META-INF/services` file or a resource | a library inside the engine fails at its first use | the build file from `engine gen stubs` merges service files; the kit's link check and cases run from the merged JAR, not from the class folders |
| Banking's adapters lose compile-time checking against Risk (names as text) | a wrong output or column name found late | uses are checked against the installed descriptor at start, in words; the banking browser files and the goldens run in the drill through the dropped JAR |
| The shared platform libraries tie an engine to a server after all | an engine needs tools 1.3 and a site's server has 1.2 | additive-only libraries, the built-against rule, the refusal sentence; an engine that wants independence uses only the API and merges what else it needs under its own package names |
| The folder is taken for a security boundary | an untrusted JAR runs with the server's rights | stated in 0.7, in the deployment guide and on the Admin page; `pinned` for production; the process binding for a JAR a site will not trust |
| Token cost | the owner's budget | Fable for 2.5 bd; grade on every lane |

## 16. Decisions EN-1 to EN-44

### EN-1 to EN-28: accepted (owner, 2026-11-21)

| # | Decision | Recommendation |
|---|---|---|
| EN-1 | What an engine is | **A pure, deterministic, explained function from contract tables to tables**, tests T1 to T4; loaders, feeds, pivot, live tables and services are not engines |
| EN-2 | Home of the API | **New repository `drishti-engine`, `com.ash.engine`**, pinned from `~/.m2`; not in Risk, not in the kernel, not in tools |
| EN-3 | API dependencies | **`engine-api` is JDK-only**; Arrow is an adapter and a wire format, not a public type |
| EN-4 | Lifecycle | **engine → session over a snapshot → ask**; no separate batch type |
| EN-5 | Snapshot | **Declared dimensions** (often `date`, possibly none), not a business date in the API |
| EN-6 | Inputs | **Contract rows as column batches pulled through the host**, the vector sink in Lake's shape; two modes, `session` and `request` |
| EN-7 | Contract schema | **Lake's contract schema** for inputs and stored outputs; **Data Protocol types** for output tables |
| EN-8 | Missing input | **An answer (`NOT_FOUND` with names)**, never an error, never a substitute |
| EN-9 | Identity | **Visibility and an actor name; no principal**; no visibility is a refusal |
| EN-10 | Determinism | **Bits by default**; a declared tolerance class per measure column for native maths; decimals as exact text |
| EN-11 | Explain | **Required for every measure**: steps with Rachana-EL formulas, and a formula workbook; checked by the kit without knowing the measure |
| EN-12 | Result storage | **The host stores declared outputs; the engine has no sink** |
| EN-13 | Scheduling | **Only the host triggers** (`request`, `schedule`, `data-version`, `changes`, `after`); an engine starts no thread or timer |
| EN-14 | Live results | **The host re-asks and feeds differences to live tables**; engines do not push |
| EN-15 | Configuration | **`config/engines/<name>.yaml`**: bindings left open by contracts, options, triggers, outputs, access, limits, placement; old keys as aliases. The pinned artifact and hash of the first text are an optional pin (EN-35) |
| EN-16 | Pack to engine | **By contract**: kinds `conforms`, packs `uses.engines` with a range; binding rules 1 to 5; ambiguity blocks, never guesses |
| EN-17 | Engine absent or off | **The feature is off in words**; never a start failure; one gate hides words, menus, Help, tiles, pivot measures and AI tools |
| EN-18 | The kit | **`engine-tck`: cases as data plus twelve laws derived from every case**; a certificate, which is evidence and is required only under trust `pinned` (EN-39) |
| EN-19 | Trust | **In process means trusted**: the site put the JAR in its lib folder (EN-35); otherwise a separate process; the static scan is a lint |
| EN-20 | Process model | **In process first; the process binding is today's worker pool re-faced; Flight when remote is needed**; triggers (a) to (f) |
| EN-21 | Generation | **G0 interpret and G2 bind now; G1 generate documents, screens, stubs, typed records, tools now; G3 compiled expressions later on a measured trigger; G4 with GenAI** |
| EN-22 | What is never generated or compiled | **No Java in configuration, no run-time `javac`, no generated engine logic, no per-engine controllers** |
| EN-23 | Services | **Keep `dataload`, `flight`, `memory-lake`, `openblas`, `pivot` as switched services** in the same registry and page, without descriptors |
| EN-24 | Small analytics (`CorrelationEngine`, `BetaEngine`) | **Follow RK-5 into the market-risk engine** as outputs; a module-local engine only if RK-5's file-by-file review keeps them in Drishti |
| EN-25 | Today's two `ComputeEngine` interfaces and the reactive package | **Fold into one**: the reactive SPI is replaced by `com.ash.engine`; the lifecycle one is renamed `EngineLifecycle`; the reactive runtime becomes `engine-host` |
| EN-26 | Risk design | **Amend as section 9.2 before K4**: ports are the engine API's; `ResultSink` and `PartitionTransport` go; `risk-tck` is the engine kit plus cases |
| EN-27 | Freezing the API | **`0.x` through R1 and H2; `1.0.0` at R2 after market risk, CCR and one non-risk engine pass** |
| EN-28 | Scope in combined i8 | **E0 to E12** with the cut lines E10, E11, E7 in that order; G3 and Flight later. The size is restated by EN-44 |


### EN-29 to EN-44: open, for the owner (2026-11-22, the acceptance test)

| # | Decision | Recommendation | The alternative |
|---|---|---|---|
| EN-29 | The acceptance test as a gate | **A1 to A8 of section 0 are release gates**: the kit's delivery group at R1, the drop-in test from H2 and in the drill, Risk through the dropped JAR at R2 | a statement in the design only |
| EN-30 | The deliverable and its folder | **One self-contained JAR per product** (private libraries merged in, no platform class inside) **in `<home>/lib/engines`** (`drishti.engine-lib.path`), kept on an upgrade | engine JARs in `lib/ext` beside drivers (one shared loader, no isolation) |
| EN-31 | Class-loader isolation | **One loader per engine JAR.** Platform packages parent-first from the kernel; everything else from the JAR only, with no fall-through to the kernel. Engines see neither each other, nor modules, nor Spring | one shared loader; or JDK module layers |
| EN-32 | What is shared | **The engine API, drishti-tools, Lake's API and Rachana core are the server's, shared**; the engine declares the versions it was built against | share only the API and let each engine bundle the other three (bigger JARs, a fix needs every engine rebuilt) |
| EN-33 | Discovery | **`ServiceLoader` of `com.ash.engine.Engine` on each JAR's loader, at start.** The descriptor key `entry` goes: the registration is the one place that names the class | a class name in the descriptor or in configuration |
| EN-34 | Compatibility | **`api:` and `platform:` state what the JAR was built against; same major and server minor at least the engine's; refusal is one sentence with both versions and what to do.** One API major per server; each release states the versions it provides | a version range written by the author (which an author can write too wide) |
| EN-35 | Trust | **`folder` by default; `pinned` (version and SHA-384 in the engine file, certificate beside the JAR) for production; signed JARs not in i8**, arriving with `.dmod` signing as one verifier for both | sign engine JARs now, ahead of modules (a second mechanism, then a third when modules are signed) |
| EN-36 | Engine or `.dmod` | **A plain JAR in the lib folder that a module refers to by name; never a `.dmod` kind, never carried in a `.dmod`.** `module.yaml` `compute.engines:` goes at R2 | an engine kind of `.dmod` (ties an engine to the kernel's version and to Drishti's module tool) |
| EN-37 | Hot add | **At start only in i8** (rolling restart on a cluster). Live without a restart: the switch, "restart engine" after a config edit, rebinding. Hot add of a new JAR later, on a site's need; hot replacement not planned | hot replacement now (unloading, native libraries, Windows file locks) |
| EN-38 | The minimum SPI | **`Engine` and `Session`: two interfaces, eight methods; `Explaining` when a column is a measure; `Engine.notes()` optional; the rest are capabilities** (0.10). Frozen as 1.0 after three engines, as EN-27 | a larger required surface |
| EN-39 | The kit's contents | **Section 6 plus a delivery group** (loads the JAR as a server does: loader, registration, no bundled platform class, closed-world link check, built-against versions) **and a JUnit harness for the author's own tests, in the same artifact.** The certificate is evidence, required only under `pinned` | the certificate as a condition of loading everywhere (a second file to deploy) |
| EN-40 | Data inside a host | **Only through `Inputs`**, bound by configuration with row rules and masks applied. Lake's read API and loader are for the standalone path, and are what Drishti implements `Inputs` over | an engine opens lake tables itself inside the server (passes by access rules and binding) |
| EN-41 | How a pack learns of an engine | **By name and contract, as EN-16**: `pack.yaml` `uses.engines` (id, version range, outputs, `as:` kind and mnemonic); the registry answers at start; only `config/engines/<id>.yaml` names tables. Outputs and columns a pack uses are checked against the installed descriptor at start | a pack or module that carries or names a JAR |
| EN-42 | Migration of the in-tree engines | **Section 12: after R2 no engine class is left in Drishti's reactor.** VaR and exposure arrive in the Risk JAR; `VectorSumEngine` becomes `engine-sample`; correlation and beta follow RK-5 into the Risk JAR; the old reactive form is read for one release | keep small engines in-tree behind the old SPI (two ways) |
| EN-43 | Drishti Risk's delivery | **One JAR, `drishti-risk-<version>.jar`, registering `market-risk` and `ccr`; Drishti has no Maven dependency on Risk**; the banking module keeps only code over engine API values; goldens keep their expected values and ask through the generic host (section 9) | Risk as compile-time jars pinned from `~/.m2` (the first design; fails A7) |
| EN-44 | Scope and cut lines | **About 65 bd net** (section 14). Moved later, each on a need: `engine-inputs-jdbc`, `engine-arrow`, non-Java engines as JARs. Cut lines unchanged: E10, E11, E7 | keep the first estimate's adapters in i8 |

EN-29 to EN-44 change three accepted decisions in the parts shown: EN-15 (the pinned artifact and hash are now an optional pin,
EN-35), EN-18 (the certificate is evidence, EN-39) and EN-19 (in process means the site put the JAR in its folder, EN-35).

## Decisions taken (owner, 2026-11-21)

EN-1 to EN-28 are **accepted as recommended** (owner). The two Drishti interfaces named `ComputeEngine` are folded into one in i7b G4 (owner: fix known duplicates now); the full engine model (drishti-engine repository, engine-api, kit, ladder) is built in the combined i8 as planned.

EN-29 to EN-44 (2026-11-22) are **not yet taken**: they wait for the owner.
