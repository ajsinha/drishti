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

# Engines in Drishti

Drishti hosts engines. An engine is a JAR built without Drishti, against drishti-engine's `engine-api` (JDK only), and proven by its own
kit ([drishti-engine](https://github.com/drishti-platform/drishti-engine), `spec/1/SPEC.md`). Deploying one is copying its JAR into the
server's engine lib folder and writing its engine file. Nothing is compiled into Drishti for it. The design is
`docs/plans/ENGINE_INTEGRATION_DESIGN.md` (on the i7b products branch until it merges) (sections 0, 4, 5, 9 and 11; decisions EN-1 to EN-55).

## 1. The parts

| Part | Where | Does |
|---|---|---|
| Engine lib folder | `<home>/lib/engines` (`drishti.engine-lib.path`) | one self-contained JAR per product, read once at start, kept on an upgrade |
| `engine-host` (drishti-engine) | in the server jar, shared | a class loader per JAR (platform packages from the server, everything else from the JAR only), discovery by `ServiceLoader`, the version and licence checks with their sentences, the strict engine-file reader, the lifecycle (`EngineInstance`), sessions, admission, budgets, `HealthBoard` |
| `drishti-engine-host` (kernel module, `com.ash.drishti.enginehost`) | plain Java behind ports | binding by contract, inputs over the read API, the output gate, live and stored outputs, triggers, the licence file |
| `server.enginehost` | the server | the ports wired to the router, the access rules, the EL compiler, the packs and the audit log; the REST API; the `engineHost` health entry; the metrics; each engine attached to the engine switches |

The server provides `engine-api`, `engine-host`, drishti-tools, Drishti Lake and Rachana to every engine JAR. `GET /api/v1/engines/platform`
and Admin → Engines state the versions in one line: "provides engine API 0.1, tools 1.1, Lake 0.9, Rachana 1.0".

## 2. At start

1. The licence file is read (section 6).
2. Each `config/engines/<id>.yaml` that is not the switch file of a built-in engine is read strictly. An unknown key, or a value of the wrong
   type, blocks that engine with the key named.
3. The folder is read in name order. Each JAR gets its own loader. Its engines are found and checked against what this server provides and
   against the licence.
4. Every engine id is put in exactly one state, with its words:

| State | When |
|---|---|
| `running` | JAR, engine file, every start check passes, `enabled: true` |
| `off` | the same, `enabled: false` |
| `not configured` | a JAR registers it and there is no engine file (`ENG-5034`). `GET /api/v1/admin/engines/<id>/template` prints one from its descriptor |
| `not installed` | an engine file names it and no JAR registers it (`ENG-5030`) |
| `blocked` | a check fails: the API or a platform library too new (`ENG-1002`, `ENG-1005`), two JARs for one id (`ENG-1006`), a product the licence does not grant (`ENG-1014`), the pin or trust (`ENG-1011`, `ENG-1012`), the engine file (`ENG-1010`, `ENG-1013`), the binding (`ENG-2011`, `ENG-2012`) |

5. The engines that are on are started (`init`, then `start`).
6. When the server is ready, each engine with an engine file is attached to the engine registry. It then shows on Admin → Engines with the
   built-in engines, and has one switch, History, the API gate and cluster routing.

No state of any engine stops the server, a module, a pack or another engine. An engine JAR found in `lib/ext` is not loaded from there.
Health names it and says where it belongs.

## 3. The engine file

```yaml
# config/engines/group-totals.yaml
drishti:
  engines:
    group-totals:
      enabled: true
      changed: { by: "", at: "", reason: "" }            # written by the server
      inputs:
        items: { kinds: [ item ], columns: { GROUP: TT_GROUP }, where: "@.STATUS != 'DEAD'" }
      options: { force-fixed-order: false }
      triggers:
        - { on: request }
        - { on: data-version }
        - { on: schedule, every: 15m, ask: nightly }
      asks:
        nightly: { outputs: [ totals ], as: system }
      outputs:
        totals: { live: true, store: { lake: "${lake.data.dir}/delta/results", table: group-totals, mode: by-snapshot } }
      access:
        opens: [ role:analyst, pack:shop ]
        visibility: row-rules
      limits: { memory-mb: 512, threads: 2, request-seconds: 30, concurrent: { interactive: 8, batch: 1 } }
```

The schema is the engine specification's section 9. The file never names a JAR or a class. Site-wide settings:
`drishti.engine-lib.path`, `drishti.engine-lib.trust` (`folder` or `pinned`), `drishti.engine-lib.licence`, `drishti.engine-lib.poll-seconds`.

## 4. Inputs

**Binding.** For each input, the first rule that applies:

1. The file names `kinds` or a lake `table`.
2. Exactly one kind of the running packs conforms to the input's contract (`pack.yaml` `kinds.<kind>.conforms: [owner/name@major]`).
3. Several kinds conform. The engine is blocked (`ENG-2012`) and the kinds are listed. The host never guesses.
4. None conforms and the input is required. The engine is blocked (`ENG-2011`).
5. None conforms and the input is optional. The input reads as absent, and the outputs that need it answer not found.

**How a bound kind is read.** By default an input holds the snapshot date's rows. An input the engine declares `per: []` (not read
per date: an official-records history, the desk P&L of every day, a parameter file by version) holds the rows of every date its kind
holds, oldest first; the engine selects by date (the market-risk engine's `var-history` and `desk-pnl`). Two binding keys change that, each said in the
binding's words on Admin -> Engines: `as-of: on-or-before` reads each record's latest version on or before the date (a reference kind
loaded once and not every day, such as `csa`: the read the in-tree loaders make of documents as of a date), and `field: <list field>`
makes the items of each record's list-of-records field the input's rows, each with its record's fields beside its own (a credit limit's
`BUCKETS` under its `LIMIT_ID`): `buckets: { kinds: [ credit-limit ], as-of: on-or-before, field: BUCKETS }`.

Admin shows each input's tables and the rule that bound it.

**Reading.** Rows come only through the engine API's `Inputs` (EN-40). The host reads a kind through the router, which for a lake
connector is Drishti Lake's read API. The columns arrive as arrays by reference, read once per session. The host then applies, before the
engine sees a row:

- the column map (the engine sees the contract's names);
- the caller's row rule on each kind;
- the site's `where`;
- the caller's masks (a masked field reads as null).

A grouped scan (`SUM`, `COUNT`, `MIN`, `MAX` by declared columns) is answered at the source, after the same rules. It is offered only when
it is exact for what is asked.

## 5. Access

The host decides and enforces, on inputs and on outputs (EN-47).

- `access.opens` and each output's `access.opens` say who may ask.
- `access.visibility: row-rules` turns the caller into a visibility with the server's one access model: Entitlements' row rules and
  masks.
- **Isolation.** `per-viewer` is the default and always available: a session per visibility signature, every input read as the caller.
  `shared` (the snapshot loaded once) needs a kit certificate beside the JAR that lists the filter-equivalence and interleaving laws.
- **On outputs.** A column that `passes` a masked input column is masked. A row whose passed scope column lies outside the caller's rule
  fails the request (`ENG-4005`).

Every ask, transition and refusal is a line in the audit log.

## 6. Licence

`config/licence.yaml` (`drishti.engine-lib.licence`) lists the products the installation may run:

```yaml
licence:
  customer: Example Bank
  products: [ drishti-risk, drishti-engine-samples ]
  limits: { drishti-risk: { nodes: 3 } }       # shown, enforced with module licensing
```

An engine whose descriptor's `product` is not listed is blocked (`ENG-1014`). The words name the engine, its version, its JAR and the
missing product. Without the file every product is granted (the development licence), as for modules. The file is unsigned for now. Signing
comes with module licensing, and modules and engines then share one licence file.

## 7. Lifecycle, health, stats, metrics

| Route | Does |
|---|---|
| `GET /api/v1/engines`, `/platform`, `/{id}` | the engines, what the server provides, one engine's outputs as tables `engine.<id>.<output>` |
| `POST /api/v1/engines/{id}/output` · `GET /api/v1/engines/{id}/outputs/{output}?<param>=…&snapshot.<dim>=…` | an answer, as the caller |
| `GET /api/v1/engines/{id}/outputs/{output}/live?since=<version>` | a live output's rows, or its changes by key and version |
| `GET /api/v1/engines/{id}/health`, `/stats` | from the health board, never by calling the engine for a page |
| `GET /api/v1/admin/engines/host` | the folder, trust, licence, every JAR (file, SHA-384, engines, certificate), every engine's card |
| `GET`, `POST /api/v1/admin/engines/{id}/lifecycle[/{init,start,suspend,resume,shutdown}]?reason=` | the transitions (administrators) |
| `GET /api/v1/admin/engines/{id}/template` · `POST /api/v1/admin/engines/{id}/asks/{name}` | an engine file from the descriptor · run a named ask |

- **Suspend** refuses new requests at once (`503 ENG-5023`, with the reason) and lets those in flight drain. Sessions, loaded data and
  answers are kept. **Resume** answers the same, bit for bit.
- The switch on Admin → Engines starts a new instance or shuts it down.
- `/actuator/health` carries `engineHost`.
- Prometheus has the `drishti_lifecycle_*` series of `docs/architecture/LIFECYCLE.md` section 7 (the kernel lifecycle lane, `i8/pack-lifecycle`) with `type=engine`, `name=<id>`, plus
  `drishti_lifecycle_host_refusals` (requests the host refused before the engine).
- A pack whose `uses.engines` names an engine that is not serving is degraded, not refused.

## 8. What changes at a restart only

The folder is read at start only. Adding, replacing or removing a JAR takes effect at the next start of that node. On a cluster this is
done node by node, and the engine gate routes requests to a node that runs the engine. Live, without a restart: the switch, the lifecycle
transitions, and a restart of an engine (`shutdown` then `start`).
