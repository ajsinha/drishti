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
# Domain packs

Drishti's core knows no industry. It knows documents, shapes, the Rachana layout grammar, inference, links and
live updates. Everything that belongs to one industry — the commands you type, the layouts, the sample data, the
help pages — arrives in a **domain pack**: one folder under `packs/`.

This guide explains, with examples:

1. [What a pack is](#what-a-pack-is), and [every pack that ships](#the-packs-that-ship), with its commands.
2. [How to turn packs on](#turning-packs-on) and [who sees which pack](#who-sees-which-pack).
3. [Every key of `pack.yaml`](#packyaml-key-by-key), annotated, using a real pack.
4. [How inheritance works](#inheritance), with a worked example.
5. [How to write your own pack](#writing-a-pack-by-hand-step-by-step), step by step.

## What a pack is

A pack is configuration and content only. It contains no Java and no Python that the server runs. Here is the
smallest complete pack in the repository, `packs/logistics/`:

```text
packs/logistics/
├── pack.yaml                    the manifest: kinds, commands, links, roles, alerts, console extras
├── sutras/
│   └── shipment.v1.sutra.md     one layout (a Sutra), for shipments in transit
├── config/
│   ├── semantics.yaml           field-name hints for inference ("etaDelayHours" is a delay, in hours)
│   ├── formats.yaml             extra number formats (temp1 → "4.2 °C", knots1 → "18.5 kn")
│   ├── workspaces.yaml          a starter workspace ("Shipment tracker")
│   └── help.yaml                the pack's card in the help centre
├── guides/
│   └── logistics.md             the guide that card opens
├── samples/
│   ├── catalog.json             the list of sample entities (kind, id, title, subtitle)
│   ├── shipment/SHP-10042.json  one JSON document per entity, in a folder per kind
│   ├── container/…  vessel/…  port/…
└── tools/
    └── make_samples.py          how the samples were generated (not run by the server)
```

When the server starts with this pack turned on, you can type `SHP SHP-10042 <GO>` and see a shipment. Without
it, `SHP` means nothing.

Words used below:

| Word | Meaning | Example |
|---|---|---|
| **kind** | a type of entity | `shipment`, `trade`, `netting-set` |
| **mnemonic** | the short command for a kind | `SHP` opens a `shipment` |
| **id** | one entity's identifier | `SHP-10042` |
| **Sutra** | a layout for a kind, written in Rachana | `sutras/shipment.v1.sutra.md` |
| **connector** | a named data source a pack reads from | `credit-store` (a Delta Lake folder) |
| **route** | which connector answers a kind | `netting-set: credit-store` |

## The packs that ship

There are fourteen packs in `packs/`. Each row lists the pack, what it inherits from, its mnemonics, and one
command to try. The example ids are real: they exist in the pack's `samples/` folder.

| Pack | Extends | Mnemonics | Try |
|---|---|---|---|
| `banking-core` | — | `CPTY` counterparty · `GRP` counterparty group · `ISS` issuer · `AGR` master agreement · `CCP` central counterparty · `LE` bank legal entity · `BOOK` book · `DESK` desk · `TRDR` trader · `CAL` holiday calendar · `CSA` credit support annex · `CLR` clearing account | `CPTY CP-NORTHBRIDGE` |
| `market-data` | `banking-core` | `CRV` interest rate curve · `REPO` repo curve · `FX` FX spot rate · `FXF` FX forward curve · `FXV` FX volatility surface · `IRV` swaption volatility cube · `CPV` cap/floor volatility surface · `EQ` equity · `EQX` equity index · `DIV` dividend curve · `EQV` equity volatility surface · `CDS` credit curve · `INF` inflation index · `INFC` inflation curve · `CMD` commodity · `CMDC` commodity forward curve · `CMDV` commodity volatility surface · `FIX` rate index fixings · `BND` bond (security master) · `CORR` correlation matrix | `CRV CRV-USD-OIS` |
| `trading` | `banking-core`, `market-data` | `TRD` trade (125 products in ten asset classes) | `TRD T-10001` |
| `market-risk` | `market-data`, `trading` | `VAR` VaR / expected shortfall · `SCN` stress scenario · `STR` stress result · `FRTB` FRTB sensitivities · `PNL` P&L explain | `VAR VAR-RATES` |
| `counterparty-risk` | `market-data`, `trading` | `NSET` netting set · `LIM` credit limit · `EXP` exposure profile · `CVA` CVA / XVA · `SACCR` SA-CCR exposure · `COLL` collateral balance · `MC` margin call · `SIMM` ISDA SIMM initial margin | `NSET NS-SUMMIT-NY` |
| `liquidity-risk` | `trading` | `LCR` liquidity coverage ratio · `NSFR` net stable funding ratio · `MLAD` maturity ladder · `HQLA` HQLA holding · `FUND` funding source · `LST` liquidity stress result · `IDL` intraday liquidity | `LCR LCR-NY` |
| `climate-risk` | `trading` | `CLIM` climate profile · `FE` financed emissions · `NGFS` climate scenario · `CST` climate stress result · `PHY` physical-risk asset · `GAR` green asset ratio | `CLIM CLIM-SOLARIS` |
| `operational-risk` | `banking-core` | `LOSS` operational loss event · `RCSA` risk and control assessment · `KRI` key risk indicator · `ISSUE` issue and action · `OPSCN` operational-risk scenario · `VEND` third party (vendor) · `CYBER` cyber incident · `OPCAP` operational-risk capital | `LOSS LOSS-2025-0001` |
| `retail-banking` | `banking-core` | `CUST` customer · `ACCT` deposit account · `MTG` mortgage · `CARD` card account · `PLN` personal loan · `BRN` branch · `COLC` collections case · `RPF` retail portfolio (IFRS 9) | `CUST CUST-100231` |
| `genomics` | — | `GENE` gene · `VRNT` variant · `PROT` protein · `PWY` pathway · `SMPL` sample · `SEQ` sequencing run · `EXPR` expression study · `TRIAL` clinical trial | `GENE GENE-TP53` |
| `politics-society` | — | `JUR` jurisdiction · `PARTY` party · `CAND` candidate · `ELEC` election · `POLL` opinion poll · `BILL` bill · `REGN` region · `SOCI` social indicator (fictional polities) | `ELEC ELEC-KES-2025` |
| `economics` | — | `ECON` economy · `MACRO` macro indicator · `CBD` central-bank decision · `FCST` economic forecast · `TFLOW` trade flow · `LABR` labour market · `FISC` fiscal position · `CPIB` consumer-price basket | `ECON ECON-US` |
| `finance` | — | `TRD` trade · `NSET` netting set · `CSA` credit support annex · `AGR` master agreement · `CRV` curve · `CPTY` counterparty · `LIM` credit limit · `CLR` clearing account · `SPEC` contract specification · `IDX` rate index · `FXS` FX spot · `BOOK` book · `FIX` rate fixings | `TRD IRS-48213` |
| `logistics` | — | `SHP` shipment · `CTR` container · `VSL` vessel · `PORT` port | `SHP SHP-10042` |

Every pack's overview guide (*Help → Domain packs*, or `packs/<name>/guides/`) starts with a **Try it** list of
commands like these.

**Two families.** The `finance` pack is the small demo behind the four original mockups (`TRD IRS-48213`). The
banking family (`banking-core` … `retail-banking`) is the full model: 125 trade products, 45 data kinds. Both
families define `trade`, `counterparty`, `netting-set` and other kinds, and a kind may belong to only one installed
pack, so **a server runs one family or the other, never both**. `logistics`, `genomics`, `politics-society` and
`economics` share no kinds with anything and can be added to either family.

Typical combinations:

| You want | `DRISHTI_PACKS=` |
|---|---|
| The original demo (the default) | `finance` |
| The demo plus a second industry | `finance,logistics` |
| Every banking pack | `market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking` |
| Every banking pack plus the other domains | `market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics` |
| Only genomics | `genomics` |

You do not list `banking-core`, `market-data` or `trading` in the banking lines: the packs that extend them bring
them in (see [Inheritance](#inheritance)).

## Turning packs on

The server reads one setting, `drishti.packs.enabled`, from the environment variable `DRISHTI_PACKS` (a
comma-separated list; default `finance`). Packs are looked for in `drishti.packs.dir` (`DRISHTI_PACKS_DIR`,
default `./packs`, relative to the directory you start the server in).

1. Stop the server.
2. Start it with the packs you want. From the repository root:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   DRISHTI_PACKS=finance,logistics java -jar drishti-server/target/drishti-server-*-exec.jar
   ```

3. Check what loaded. Any of these works:

   ```bash
   curl -s http://localhost:18480/api/v1/packs | python3 -m json.tool | grep '"name"'
   ```

   You should see one line per loaded pack, parents included:

   ```text
       "name": "finance",
       "name": "logistics",
   ```

   In the console, **About** (`/about`) lists the loaded packs, and *Admin → Health* (`/admin/health`) shows each
   pack with its status, what it extends, its kinds, its Sutras, its connectors and any problems.
4. Open the terminal (`/t`) and type a command from the new pack, for example `SHP SHP-10042 <GO>`.

The console needs no setting: it asks the server which packs are on. (`packs.enabled` in
`console/config/application.yaml` is only a fallback for when the server cannot be reached.) If you moved the
packs folder, set `DRISHTI_PACKS_DIR` for both processes.

What happens at start-up:

- Each listed pack is read from `packs/<name>/pack.yaml`, and so is every pack it extends.
- The packs' contents become **lowest-precedence** configuration. Anything the site sets in
  `drishti-server/src/main/resources/application.yaml`, an external `application.yaml` or an environment variable
  overrides a pack.
- The server **refuses to start**, with a message naming the packs, when:
  - a listed pack folder or its `pack.yaml` is missing (`pack 'x' not found at packs/x/pack.yaml (required by 'y')`);
  - `pack:` inside `pack.yaml` does not match the folder name;
  - packs extend each other in a cycle;
  - two packs own the same kind (`kind trade is defined by both pack 'finance' and pack 'trading'`);
  - two **unrelated** packs define the same mnemonic, link field, badge, role, connector or route differently.

## Who sees which pack

Three lists decide what a user sees:

| List | Set by | Meaning |
|---|---|---|
| **Installed** | `DRISHTI_PACKS` | what the server runs |
| **Assigned** | an administrator, per user | what this user may use. A user with no assignment gets `drishti.packs.default-for-users` (`DRISHTI_DEFAULT_PACKS`; empty means every installed pack). |
| **Active** | the user, with the pack switcher | which of their assigned packs they want to see now |

**To assign packs to a user** (administrators): *Admin → Users*, edit the user, tick the packs under **Packs**,
save. The user list shows each user's packs, or *default packs*. See [USER_MANAGEMENT.md](USER_MANAGEMENT.md) for
the user dialog and roles.

**To choose which packs you see** (any user with more than one pack): click the box icon in the top bar and tick
the packs. The choice is saved to your account. (For programs: `GET /api/v1/me/packs` answers
`{"assigned": [...], "active": [...]}` for the signed-in user, and `PUT` with `{"active": [...]}` chooses.)

**Enforcement is on the server.** A kind owned by a pack that is not active for you cannot be opened: the view
answers `DRS-5002` (forbidden). Its mnemonics, suggestions, examples, starter workspaces, guides and alert
suggestions disappear too. Kinds no pack owns are not affected. A user who may see `market-risk` may also open the
kinds of the packs it extends (a VaR result links to its trades and books).

Roles work on top of packs: a role lists the kinds it may open (see [Roles](#roles) below).

## `pack.yaml`, key by key

Below is the complete `packs/logistics/pack.yaml`, annotated. Then the keys only larger packs use, from
`packs/counterparty-risk/pack.yaml`. Every key here is read either by the server's pack loader
(`drishti-packs/…/PackLoader.java`) or by the console (`console/core/packs.py`); keys not listed are ignored.

```yaml
pack: logistics                 # REQUIRED. Must equal the folder name, or the server refuses to start.
version: 1.0.0                  # shown on About and Admin → Health. Default "0".
title: Logistics · ocean freight          # display name (pack switcher, help, About). Default: the pack name.
description: >-                 # one paragraph for About and the help centre.
  Shipments, reefer containers, vessels and ports, with a shipment Sutra, logistics vocabulary for inference
  (weights, temperatures, delays, knots, TEU) and live sample data.

kinds: [shipment, container, vessel, port]   # the kinds this pack OWNS. A kind belongs to exactly one installed
                                             # pack. Pack access (who may open a kind) is decided by this list.

mnemonics:                      # the commands. MNEMONIC: { kind, label }
  SHP:  { kind: shipment, label: Shipment }  # "SHP SHP-10042 <GO>" opens kind shipment, id SHP-10042
  CTR:  { kind: container, label: Container }# label is shown in suggestions; default: the mnemonic itself
  VSL:  { kind: vessel, label: Vessel }
  PORT: { kind: port, label: Port }

graph:                          # how entities link to each other
  id-patterns:                  # a bare id typed on its own ("SHP-10042") is recognised by a regex
    - { pattern: "^SHP-", kind: shipment }
    - { pattern: "^CTR-", kind: container }
    - { pattern: "^VSL-", kind: vessel }
    - { pattern: "^PORT-", kind: port }
  fields:                       # a document field whose value is an id of another kind becomes a link
    origin:      { kind: port, label: Origin port }       # "origin": "PORT-SGSIN" → a link to that port
    destination: { kind: port, label: Destination port }  # label: how the link is named in Linked entities
    nextPort:    { kind: port, label: Next port }
    vessel:      { kind: vessel, label: Vessel }
    container:   { kind: container, label: Container }
    shipment:    { kind: shipment, label: Shipment }
  impact:                       # F8 impact: what depends on an entity
    follow: [vessel, destination]            # roll dependents up through these link fields
    measures: { shipment: "$.declaredValue" }# Rachana-EL summed per dependent kind ("the amount at stake")
    formats:  { shipment: amount0 }          # how that sum is shown
  badges:                       # the small text beside a link, read from the TARGET entity (Rachana-EL)
    container: "fmt($.currentC, 'temp1')"           # a link to a container shows "4.2 °C"
    vessel: "fmt($.speedKnots, 'knots1')"           # a link to a vessel shows "18.5 kn"
    port: "'wait ' + $.berthWaitHours + ' h'"       # a link to a port shows "wait 6 h"

roles:                          # roles this pack adds. ROLE: { kinds, raw?, author?, approve?, admin? }
  ops: { kinds: [shipment, container, vessel, port] }   # "*" means every kind; raw: true lets F9 show unredacted JSON

alerts:                         # suggested alert rules, offered in the Alert dialog for that kind
  - kind: shipment
    name: "Delayed more than 12 h"           # what the user sees in the list of suggestions
    when: "$.etaDelayHours > 12"             # Rachana-EL condition, checked on every change
    severity: critical                       # info, warn or critical
    message: "${$.shipmentId}: ETA ${fmt($.etaDelayHours, 'hours0')}"   # ${…} is evaluated
  - { kind: container, name: "Reefer out of range", when: "$.currentC > 8 || $.currentC < 2",
      severity: critical, message: "${$.containerId}: ${fmt($.currentC, 'temp1')}" }

console:                        # read by the console only
  examples:                     # [command, description] pairs on the terminal home and landing page
    - ["SHP SHP-10042", "Shipment in transit · route, milestones, reefer temperature"]
    - ["CTR CTR-MSKU1234567", "Reefer container · inferred"]
    - ["VSL VSL-9811000", "Vessel · inferred"]
    - ["SHP SHP-10077", "A delivered shipment · no Sutra match, inferred"]
  workspaces: config/workspaces.yaml        # starter workspaces (a "templates:" map), offered on /w
  help: config/help.yaml                    # help centre cards ("guides:") and F1 targets ("contextual:")
  monitors:                                 # starter monitors: NAME: [ {kind, id}, … ], offered on /m
    Fleet watch: [ { kind: shipment, id: SHP-10042 }, { kind: container, id: CTR-MSKU1234567 },
                   { kind: vessel, id: VSL-9811000 }, { kind: port, id: PORT-NLRTM } ]
```

Four more keys say where the pack's folders are. All are optional, relative to the pack folder, and the default
is used when the key is absent. A folder or file that does not exist is simply skipped.

| Key | Default | What is there |
|---|---|---|
| `sutras` | `sutras` | Sutras (`*.sutra.md`, or plain YAML), scanned recursively |
| `formats` | `config/formats.yaml` | extra named formats (`formats: { temp1: { type: number, decimals: 1, suffix: " °C" } }`) |
| `semantics` | `config/semantics.yaml` | inference hints: `roles:` (a field-name regex → format, tone, strip weight) and `idFields:` |
| `samples` | `samples` | sample documents for the built-in `demo` source |

### Keys for packs that inherit and read real data

From `packs/counterparty-risk/pack.yaml` (abridged; the file is generated by `tools/packgen/banking/make_packs.py`):

```yaml
pack: counterparty-risk
extends:                        # the packs this one inherits from, in order (the older name "requires" works too)
- market-data
- trading
kinds: [netting-set, credit-limit, exposure-profile, cva, sa-ccr, collateral-balance, margin-call, simm]

connectors:                     # named data sources. NAME: { plugin, enabled?, kinds, settings }
  credit-store:
    plugin: delta               # which source plugin: delta, jdbc, aerospike, kafka, s3, rest, file, feeds, …
    enabled: ${DRISHTI_LAKE_ENABLED:true}    # ${VAR:default} is resolved from the environment
    kinds: [netting-set, credit-limit, exposure-profile, cva, sa-ccr]
    settings:                   # passed to the plugin as-is (see PLUGIN_GUIDE.md for each plugin's settings)
      root: ${DRISHTI_DELTA_ROOT:./data/delta}
      domain: credit            # reads data/delta/credit/<kind>/
  collateral-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [collateral-balance, margin-call, simm]
    settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: collateral }

routes:                         # KIND: CONNECTOR — which connector answers each kind
  netting-set: credit-store
  margin-call: collateral-store
  # … one line per kind

roles:
  credit-risk: { kinds: ["*"], raw: true }   # may open every kind and see raw JSON unredacted
```

- **Connectors are per data domain, not per pack.** The lake is laid out as `data/delta/<domain>/<kind>/`. Several
  packs may declare the same connector; if they declare it identically, that is fine. A different declaration
  under the same name is an error, unless one pack extends the other (then the more specific wins).
- **Site configuration overrides a pack.** To point the `credit` domain at PostgreSQL instead of the lake, or to
  switch a connector off, set `drishti.sources.connectors.credit-store.…` in the site configuration. See
  [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) and [CONFIGURATION.md](CONFIGURATION.md).

### What becomes of each key

The server turns the manifest into ordinary settings. Knowing this helps when you read logs or override something:

| `pack.yaml` | Becomes |
|---|---|
| `kinds` | `drishti.packs.kinds.<pack>[i]` |
| `mnemonics.SHP` | `drishti.commands.mnemonics.SHP.kind` / `.label` |
| `graph.id-patterns` | `drishti.graph.id-patterns[i]` |
| `graph.fields.vessel` | `drishti.graph.fields.vessel.kind` / `.label` |
| `graph.badges.vessel` | `drishti.graph.badges.vessel` |
| `graph.impact.*` | `drishti.graph.impact.follow[i]`, `.measures.<kind>`, `.formats.<kind>` |
| `roles.ops` | `drishti.security.roles.ops.kinds[i]`, `.raw`, `.author`, `.admin`, `.approve` |
| `connectors.x` | `drishti.sources.connectors.x.plugin`, `.enabled`, `.kinds[i]`, `.settings.*` |
| `routes.kind` | `drishti.sources.routes.<kind>` |
| `sutras`, `formats`, `semantics` | added to `drishti.rachana.pack-dirs`, `drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files` |
| `samples` | added to `drishti.sources.plugins.demo.settings.dirs` |

`alerts` and `console` are read directly from the manifest (by the server's alert suggestions and by the console).

## Samples

The built-in `demo` source serves every enabled pack's `samples/` folder, so a pack works with no database. The
format:

`samples/catalog.json` lists every sample. Each entry needs all four fields:

```json
[
  { "kind": "shipment", "id": "SHP-10042", "title": "SHP-10042", "subtitle": "Shipment · Singapore → Rotterdam" },
  { "kind": "port",     "id": "PORT-NLRTM", "title": "PORT-NLRTM", "subtitle": "Port · Rotterdam, NL" }
]
```

`samples/<kind>/<id>.json` is the document itself, plus a `_meta` block that becomes its provenance (shown in
*How this view was built* and in `F9`):

```json
{
  "shipmentId": "SHP-10042",
  "status": "In transit",
  "origin": "PORT-SGSIN",
  "destination": "PORT-NLRTM",
  "vessel": "VSL-9811000",
  "etaDelayHours": 6,
  "_meta": { "source": "port-ops", "generation": 512, "live": true, "walk": { "etaDelayHours": 1 } }
}
```

| `_meta` key | Meaning |
|---|---|
| `source` | the name shown as the source system |
| `generation` | a version number shown with the source |
| `live` | `true`: the document ticks while someone watches it |
| `walk` | `{ field: step }`: top-level numeric fields that random-walk by up to `step` on each tick. No code needed. |

Every catalogue entry must have its file; a missing file stops the demo source from starting. The `title` and
`subtitle` are what the suggestions dropdown shows.

Realistic samples matter: they should look like what the real system would hold. `tools/samplegen` is a small,
standard-library-only toolkit for that:

| Module | What it gives |
|---|---|
| `ids` | LEI (ISO 17442 check digits), ISIN (Luhn), CUSIP, UTI, UPI, BIC |
| `dates` | business-day calendars (USNY, GBLO, EUTA, JPTO and joint calendars), tenors, schedules with stubs, day counts (ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT) |
| `curves` | zero curves with log-linear discount factors, forwards and the published pillar table |
| `legs` | fixed and floating legs with every calculation period: fixing dates, year fractions, fixings or projected rates, amounts, DFs, PVs and status |
| `blocks` | execution, lifecycle and audit, confirmation, clearing, regulatory reporting, settlement instructions, valuation and P&L history |

`tools/samplegen/test_samplegen.py` checks the library against known values and the finance samples against
themselves.

## Guides and help

A pack's help appears in the help centre (`/help`) next to the core guides.

`config/help.yaml`:

```yaml
guides:
  - { slug: logistics-pack, category: start, title: "The logistics pack", kind: guide, icon: truck,
      file: guides/logistics.md, summary: "Shipments, reefer containers, vessels and ports." }
contextual:                     # optional: which guide F1 opens on a screen
  terminal: logistics-pack
```

| Key | Meaning |
|---|---|
| `slug` | the guide's address: `/help/logistics-pack`. Must be unique across all packs. |
| `category` | where the card goes: `start`, `layouts`, `data`, `packs`, … (the categories in `console/config/help.yaml`) |
| `title`, `summary`, `icon` | the card. `icon` is a Bootstrap Icons name without the `bi-` prefix. |
| `kind` | `guide` or `tutorial`: the badge shown beside the title |
| `file` | the Markdown file, relative to the pack folder |

Screens for `contextual` include `landing`, `terminal`, `view`, `studio`, `workspace`, `monitor`, `alerts`,
`impact`, `admin`, `account` and `help`.

Write the guide in Markdown with the copyright header comment at the top. Start it with a **Try it** section of
commands that open real samples, as every shipped overview guide does. Links to other help documents with a relative
path (`[Sutra guide](../../../console/web/guides/sutra-guide.md)`) open inside the help centre.

## Roles

A pack can add roles. A role says which kinds its holder may open and what else they may do:

```yaml
roles:
  trader: { kinds: [trade, curve, fx-spot, index, book, contract-spec, clearing-account, counterparty, fixing] }
  risk:   { kinds: ["*"], raw: true }
```

| Field | Meaning |
|---|---|
| `kinds` | the kinds the role may open; `"*"` means all. Links to other kinds show disabled, with the reason. |
| `raw` | may see the raw JSON (`F9`) without redaction |
| `author` | may use Sutra Studio |
| `approve` | may approve Sutra proposals |
| `admin` | may administer users |

The core always has `viewer`, `author`, `approver` and `admin`. Roles are given to users by an administrator; see
[USER_MANAGEMENT.md](USER_MANAGEMENT.md). Security is off by default for local development; roles take effect when
it is on.

## Inheritance

`extends: [a, b]` means: this pack has everything `a` and `b` have — their kinds (to open), mnemonics, id patterns
and link fields, badges, roles, connectors and routes, Sutras, labels and formats, samples, alert suggestions,
workspaces and help — and turning this pack on turns them on too.

When two **related** packs define the same thing differently, **the more specific definition wins**, for every user:

1. a child wins over its parents;
2. among parents, **the rightmost wins**: with `extends: [market-data, trading]`, `trading` beats `market-data`;
3. a pack reached through several parents counts once, below all of them.

This order is C3 linearisation, the rule Python uses to order classes (ADR-015).

### Worked example: the order for `counterparty-risk`

The packs involved:

```text
banking-core:       extends []
market-data:        extends [banking-core]
trading:            extends [banking-core, market-data]
counterparty-risk:  extends [market-data, trading]
```

Work it out from the bottom:

| Pack | Order (most specific first) | Why |
|---|---|---|
| `banking-core` | banking-core | no parents |
| `market-data` | market-data → banking-core | one parent |
| `trading` | trading → market-data → banking-core | rightmost parent (`market-data`) first, then `banking-core`; `banking-core` appears once, after `market-data` (which also extends it) |
| `counterparty-risk` | counterparty-risk → trading → market-data → banking-core | rightmost parent (`trading`) first; `market-data` must come before `banking-core` |

So if `counterparty-risk`, `trading` and `market-data` all defined a mnemonic `FIX`, the `counterparty-risk`
definition would be used. If only `trading` and `market-data` defined it, `trading`'s would win.

### Worked example: overriding a parent

Say your bank wants `NSET` to read "Netting set (CSA)" and wants netting sets read from its own PostgreSQL
connector, without editing the shipped pack. Create `packs/my-bank/pack.yaml`:

```yaml
pack: my-bank
version: 1.0.0
title: My bank
extends: [counterparty-risk]
mnemonics:
  NSET: { kind: netting-set, label: Netting set (CSA) }      # same name as the parent's: replaces it
connectors:
  bank-credit-db:
    plugin: jdbc
    kinds: [netting-set]
    settings:                                                # table mode; see PLUGIN_GUIDE.md for the jdbc settings
      url: ${BANK_CREDIT_JDBC_URL}                           # e.g. jdbc:postgresql://db:5432/credit
      user: ${BANK_CREDIT_DB_USER}
      password: ${BANK_CREDIT_DB_PASSWORD}
      table: credit.entities                                 # (kind, id, business_date, doc jsonb)
routes:
  netting-set: bank-credit-db                                # replaces counterparty-risk's route
```

Start the server with `DRISHTI_PACKS=my-bank`. `counterparty-risk`, `trading`, `market-data` and `banking-core`
come with it. *Admin → Health* lists every override, one per line, in the form `<what> <name>: <winner> overrides
<loser>`:

```text
mnemonic NSET: my-bank overrides counterparty-risk
route netting-set: my-bank overrides counterparty-risk
```

The same list is in `GET /api/v1/admin/health` under `overrides`. It is empty when nothing is overridden.

### What can be redefined

| Thing | A more specific pack may |
|---|---|
| mnemonic, link field, badge, role, connector, route | define the same name again; its definition replaces the parent's |
| Sutra | ship a Sutra with the same `name` and `version` (it replaces the parent's), or another name with a higher `match.priority` |
| labels (`semantics.yaml`) and formats (`formats.yaml`) | define the same label or format; the more specific pack's wins |
| impact measures and formats | define the same kind; the more specific pack's wins |

Two rules never bend:

- **A kind belongs to exactly one pack.** A child cannot redefine a parent's kind; it re-lays it out with a Sutra.
- **Unrelated packs may not disagree.** If neither pack inherits from the other, and no loaded pack inherits from
  both, the same name must be defined identically, or the server stops with:
  `mnemonic X is defined by both pack 'a' and pack 'b', which do not inherit from each other; declare it identically, or make one pack extend the other`.
  For example, `liquidity-risk` and `climate-risk` both extend `trading` but not each other: they may not define
  the same role differently.

## Writing a pack by hand, step by step

This builds a tiny `helpdesk` pack with tickets. It takes about fifteen minutes and needs no code.

**1. Create the folders.**

```bash
mkdir -p packs/helpdesk/samples/ticket packs/helpdesk/sutras packs/helpdesk/guides packs/helpdesk/config
```

**2. Write `packs/helpdesk/pack.yaml`.** (Every file in the repository needs the copyright header; copy it from
`packs/logistics/pack.yaml`, or run `python3 tools/license_headers.py --fix` afterwards.)

```yaml
pack: helpdesk
version: 0.1.0
title: Help desk
description: Support tickets and their agents.
kinds: [ticket, agent]
mnemonics:
  TKT: { kind: ticket, label: Ticket }
  AGT: { kind: agent, label: Agent }
graph:
  id-patterns:
    - { pattern: "^TKT-", kind: ticket }
    - { pattern: "^AGT-", kind: agent }
  fields:
    assignee: { kind: agent, label: Assigned agent }
roles:
  support: { kinds: [ticket, agent] }
console:
  examples:
    - ["TKT TKT-1001", "An open ticket"]
```

**3. Add samples.** `packs/helpdesk/samples/catalog.json`:

```json
[
  { "kind": "ticket", "id": "TKT-1001", "title": "TKT-1001", "subtitle": "Ticket · Cannot sign in" },
  { "kind": "agent",  "id": "AGT-07",   "title": "AGT-07",   "subtitle": "Agent · Priya N." }
]
```

`packs/helpdesk/samples/ticket/TKT-1001.json`:

```json
{
  "ticketId": "TKT-1001",
  "subject": "Cannot sign in",
  "status": "Open",
  "priority": "High",
  "assignee": "AGT-07",
  "ageHours": 5,
  "history": [
    { "date": "2026-09-28", "event": "Opened" },
    { "date": "2026-09-29", "event": "Assigned to AGT-07" },
    { "date": "2026-09-30", "event": "Customer replied" }
  ],
  "_meta": { "source": "helpdesk", "generation": 1, "live": true, "walk": { "ageHours": 1 } }
}
```

and `packs/helpdesk/samples/agent/AGT-07.json`:

```json
{ "agentId": "AGT-07", "name": "Priya N.", "team": "Identity", "openTickets": 4,
  "_meta": { "source": "helpdesk", "generation": 1, "live": false } }
```

**4. Turn it on.** Restart the server with the pack added (it has no kinds in common with `finance`):

```bash
DRISHTI_PACKS=finance,helpdesk java -jar drishti-server/target/drishti-server-*-exec.jar
```

You should see `helpdesk` in `curl -s http://localhost:18480/api/v1/packs`. In the terminal, typing `TK` suggests
`TKT`, and `TKT TKT-1` suggests `TKT-1001`.

**5. Open it.** Type `TKT TKT-1001 <GO>`. There is no Sutra yet, so inference lays it out: a strip with the
scalar fields, a ladder for `history` (rows led by a date), and **Linked entities** with `AGT-07`. The footer says
*inference only*. See [INFERENCE.md](INFERENCE.md) for how it decides.

**6. Make it a Sutra.** Open **Studio** (`/studio`), load the entity `ticket` / `TKT-1001`, press **Start from inference**,
adjust the layout with the live preview, and save it as `packs/helpdesk/sutras/ticket.v1.sutra.md`. (Saving needs
`DRISHTI_STUDIO_SAVE=true` and an author role; otherwise copy the text into the file.) The
[Sutra guide](../console/web/guides/sutra-guide.md) and [Sutra Studio tutorial](../console/web/guides/sutra-studio.md)
explain the grammar and the editor.

**7. Finish it.** Optional, in any order:

- `config/semantics.yaml`, so inference formats your fields (`ageHours` as hours):
  `roles: [ { role: age, pattern: "hours$", fmt: amount0, weight: 70 } ]`;
- `config/formats.yaml` for new formats;
- `graph.badges.agent: "$.openTickets + ' open'"`, so a link to an agent shows its load;
- an `alerts:` suggestion (`when: "$.ageHours > 24"`);
- `guides/helpdesk.md`, starting with **Try it**, and `config/help.yaml` with its card;
- `config/workspaces.yaml` and `console.monitors` for starters.

**8. Real data.** When the data lives somewhere real, add a `connectors:` entry for it and a `routes:` line per
kind (see above and [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md)). The samples can stay for demos.

To generate a larger pack — many kinds, consistent documents, Sutras, a guide and a Delta Lake — from a short
Python description, follow [Tutorial 5 · Build a domain pack](../console/web/guides/build-a-pack.md). That is how
every pack after the banking family was made (`tools/packgen/common/packbuild.py`).

## How the shipped packs are generated

Most shipped packs are generated, and their files say so in a comment (`Generated by …, edit the generator, not
this file`). Regenerating overwrites hand edits.

| Packs | Generator |
|---|---|
| `banking-core`, `market-data`, `trading`, `market-risk`, `counterparty-risk` | `tools/packgen/banking/` (`make_packs.py` manifests, `make_sutras.py` the 170 Sutras, `make_docs.py` guides, `make_data.py` documents and lake) |
| `liquidity-risk`, `climate-risk`, `operational-risk`, `retail-banking`, `genomics`, `politics-society`, `economics` | `tools/packgen/<area>/make.py` on the common builder `tools/packgen/common/packbuild.py` |
| `finance`, `logistics` | hand-written; samples from `packs/<name>/tools/` |

The banking lake is built with:

```bash
uv run --with deltalake --with pyarrow python tools/packgen/banking/make_data.py --lake data/delta
```

## What stays in the core

- The engine: sources, the view pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity.
- Neutral formats (amounts, signed, percent, compact, dates) and neutral semantic roles (amount, value, count,
  rate, date, label).
- The `viewer`, `author`, `approver` and `admin` roles.
- The generic help: using the terminal, panel kinds, Studio, workspaces, and every reference.
