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
2. [How to load packs](#turning-packs-on), [switch them off and on for everyone](#switching-packs-off-and-on-admin--packs)
   and [who sees which pack](#who-sees-which-pack).
3. [Every key of `pack.yaml`](#packyaml-key-by-key), annotated, using a real pack, including
   [`columns:`](#columns-the-key-fields-of-a-pick-list) for pick lists.
4. [How inheritance works](#inheritance), with a worked example.
5. [How to write your own pack](#writing-a-pack-by-hand-step-by-step), step by step.
6. [How the shipped packs are generated](#how-the-shipped-packs-are-generated), and why you never edit their files by hand.

## What a pack is

A pack is configuration and content only. It contains no Java and no Python that the server runs. Here is the
smallest complete pack in the repository, `packs/logistics/`:

```text
packs/logistics/
├── pack.yaml                    the manifest: kinds, commands, links, roles, alerts, console extras
├── sutras/
│   └── shipment.v1.sutra.yaml   one layout (a Sutra, in YAML), for shipments in transit
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
| **Sutra** | a layout for a kind, written in Rachana: one YAML file starting `rachana: 1` | `sutras/shipment.v1.sutra.yaml` |
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

Every pack's overview guide (*Help → Domain packs*, or `packs/<name>/guides/`) describes its domain and has a
**Finding things** section with the commands that work for every kind of the pack:

| Command | Does |
|---|---|
| `CUST <id> <GO>` | Opens that customer. A bare identifier works too: its prefix (`CUST-…`) tells Drishti the kind. |
| `CUST <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `CUST <field>=<value> <GO>` | Lists by field value. Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `CUST <GO>` | Lists every customer. |

(That table is copied from `packs/retail-banking/guides/retail-banking.md`; every pack guide has the same one
for its own kinds. Pick lists are explained in [USER_GUIDE.md](USER_GUIDE.md#pick-lists-when-a-command-names-several-entities).)

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
   DRISHTI_PACKS=finance,logistics java -jar drishti-server/target/drishti-server-1.11.0-exec.jar
   ```

3. Check what loaded. Any of these works:

   ```bash
   curl -s http://localhost:18480/api/v1/packs | python3 -m json.tool | grep '"name"'
   ```

   You should see one line per loaded pack that is switched on, parents included (a pack an admin switched off
   is missing here; *Admin → Packs* lists every pack with its status):

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

## Switching packs off and on (Admin → Packs)

`DRISHTI_PACKS` decides which packs the server **loads** at start-up. Once it runs, an administrator can
switch any loaded pack **off** for everyone, and back **on**, without a restart: *Admin → Packs* in the
console (`/admin/packs`).

| Status on the page | Meaning | What an admin can do |
|---|---|---|
| **on** | Loaded and in use | **Switch off** (unless another switched-on pack needs it) |
| **off** | Loaded, but switched off for everyone | **Switch on** |
| **not loaded** | A folder under `packs/` that `DRISHTI_PACKS` did not name | **Load** (below) |

What switching off does, at every user's next click:

- the pack's kinds cannot be opened (`DRS-5002`), and links to them show disabled;
- its mnemonics, suggestions, examples, guides, starter workspaces and monitors disappear;
- it leaves every user's pack menu, and `GET /api/v1/packs` stops listing it.

Its Sutras, connectors and routes stay loaded, so switching it back on is instant.

**The rules.**

1. **A pack that a switched-on pack builds on cannot be switched off.** `trading` is needed by
   `market-risk`, `counterparty-risk`, `liquidity-risk` and `climate-risk` (directly or through a parent).
   Its button is greyed (*Needed by …: switch those off first*), and the API refuses with
   `DRS-5001 'trading' is needed by [market-risk, counterparty-risk, liquidity-risk, climate-risk]; switch those off first`.
2. **Switching a pack on switches on everything it builds on.** With `trading` and `market-risk` both off,
   switching `market-risk` on switches `trading` on too (and `market-data` and `banking-core`, if they were
   off).
3. **Packs that were not loaded cannot be switched on** (`DRS-5001 '<name>' is not loaded; installed: […]`):
   packs load only when the server starts.

**Where the choice is kept.** In the identity database, table `drishti_pack_state` (one row per pack that was
ever switched: `name`, `enabled`, `updated_at`, `updated_by`). It survives restarts. A pack with no row is on.
Each server keeps it in memory and re-reads it every `drishti.identity.refresh-seconds` (15), so with several
servers sharing one database a switch applies at once on the server that made it and within 15 seconds on the
others (roles defined in Admin → Roles behave the same). Each switch is audited as `pack-enabled` or
`pack-disabled`, with the admin's name, in *Admin → Audit log*.

**For programs** (an admin's token when security is on):

```bash
curl -s http://localhost:18480/api/v1/admin/packs | python3 -c '
import json,sys
for p in json.load(sys.stdin): print(p["name"], "loaded" if p["loaded"] else "NOT LOADED", "on" if p["enabled"] else "off", p.get("requiredBy") or "")'
```

On a server started with `DRISHTI_PACKS=market-risk,counterparty-risk,liquidity-risk,climate-risk,operational-risk,retail-banking,genomics,politics-society,economics`
you should see (abridged):

```text
banking-core loaded on ['market-data', 'trading', 'market-risk', 'counterparty-risk', 'liquidity-risk', 'climate-risk', 'operational-risk', 'retail-banking']
market-data loaded on ['trading', 'market-risk', 'counterparty-risk', 'liquidity-risk', 'climate-risk']
trading loaded on ['market-risk', 'counterparty-risk', 'liquidity-risk', 'climate-risk']
market-risk loaded on
…
economics loaded on
finance NOT LOADED off
logistics NOT LOADED off
```

To switch a pack, `PUT /api/v1/admin/packs/<name>` with `{"enabled": false}` or `{"enabled": true}`. The answer
lists the packs now on: `{"name": "operational-risk", "enabled": false, "enabledPacks": [...]}`. See
[API_GUIDE.md](API_GUIDE.md).

## Who sees which pack

Four lists decide what a user sees. Each one narrows the one before it:

| List | Set by | Meaning |
|---|---|---|
| **Loaded** | `DRISHTI_PACKS`, at start-up | what the server runs (the listed packs and their parents) |
| **Switched on** | an administrator, for everyone (*Admin → Packs*) | which loaded packs are in use; all of them unless switched off ([above](#switching-packs-off-and-on-admin--packs)) |
| **Assigned** | an administrator, per user (*Admin → Users*) | what this user may use. A user with no assignment gets `drishti.packs.default-for-users` (`DRISHTI_DEFAULT_PACKS`; empty means every switched-on pack). |
| **Active** | the user, with the pack menu in the top bar | which of their assigned packs they want to see now |

Example: the server loads twelve packs; an admin switches `operational-risk` off; Priya is assigned
`trading`, `counterparty-risk` and `operational-risk`; she ticks only `counterparty-risk`. Priya sees
counterparty risk, plus the kinds of the packs it extends (`trading`, `market-data`, `banking-core`), and
nothing of operational risk even though it is assigned to her, because it is off for everyone.

**To assign packs to a user** (administrators), step by step:

1. Open *Admin → Users* (`/admin/users`) and click **Edit** on the user (or **New user**).
2. Under **Packs**, tick the packs this person may use, say `banking-core` and `counterparty-risk`.
   Ticking none means *default packs* (`DRISHTI_DEFAULT_PACKS`, or every switched-on pack when that is empty).
3. Click **Save**. The user list now shows `banking-core, counterparty-risk` in the user's row (or
   *default packs*), and the change is in the audit log.
4. At the user's next click, their pack menu lists only those two packs, and kinds of other packs answer
   `DRS-5002`.

The same with the API (an admin's token when security is on), when creating a user:

```bash
curl -s -X POST localhost:18480/api/v1/admin/users -H 'Content-Type: application/json' \
  -d '{"username":"priya","displayName":"Priya Raman","desk":"Credit","roles":["viewer"],
       "packs":["banking-core","counterparty-risk"],"password":"credit-desk-2026"}'
```

`PUT /api/v1/admin/users/priya` with a `packs` list changes it later. See
[USER_MANAGEMENT.md](USER_MANAGEMENT.md) for the user dialog, roles, and where assignments are stored
(`drishti_user_pack`).

**To choose which packs you see** (any user with more than one pack): click the round box tool in the top bar
(it shows how many packs you have on), tick the packs (**all** and **none** help), and click **Apply**. The choice is saved to your account. (For programs: `GET /api/v1/me/packs` answers
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

**Every key at a glance.** Only `pack` is required; a pack with nothing else loads and does nothing.

| Key | Read by | One-line example | What it does | Details |
|---|---|---|---|---|
| `pack` | server | `pack: helpdesk` | The pack's name; must equal the folder name | below |
| `code` | server | `code: HELP` | The pack's short code: typed alone on the command line it opens the pack's overview (`/p/<pack>`). It must not equal a mnemonic (a mnemonic wins) | [Pack codes](#pack-codes) |
| `version` | server | `version: 1.2.0` | Shown in About, Admin → Health and Admin → Packs | [Versioning](#versioning-and-upgrading-a-pack) |
| `title`, `description` | both | `title: Help desk` | The pack's name and paragraph in menus, help and admin pages | below |
| `extends` (or `requires`) | server | `extends: [market-data, trading]` | Inherit everything from these packs | [Inheritance](#inheritance) |
| `kinds` | server | `kinds: [ticket, agent]` | The kinds this pack owns | below |
| `mnemonics` | server | `TKT: { kind: ticket, label: Ticket }` | The commands | below |
| `graph.id-patterns` | server | `- { pattern: "^TKT-", kind: ticket }` | Recognise a bare id's kind | below |
| `graph.fields` | server | `assignee: { kind: agent, label: Assigned agent }` | Turn a field's value into a link | below |
| `graph.badges` | server | `agent: "$.openTickets + ' open'"` | Text shown beside a link, read from the target | below |
| `graph.impact` | server | `follow: [assignee]`, `measures: { ticket: "$.ageHours" }` | What F8 rolls up and sums | below |
| `columns` | server | `ticket: [subject, status, priority]` | Key fields in pick lists and searches | [columns](#columns-the-key-fields-of-a-pick-list) |
| `roles` | server | `support: { kinds: [ticket, agent] }` | Roles this pack adds | [Roles](#roles) |
| `connectors` | server | `helpdesk-store: { plugin: file, … }` | Named data sources | [below](#keys-for-packs-that-inherit-and-read-real-data) |
| `routes` | server | `ticket: helpdesk-store` | Which connector answers a kind | [below](#keys-for-packs-that-inherit-and-read-real-data) |
| `alerts` | server | `- { kind: ticket, name: …, when: "$.ageHours > 24" }` | Suggested alert rules | below |
| `sutras`, `formats`, `semantics`, `samples` | server | `sutras: sutras` | Where the pack's folders and files are | below |
| `console.examples` | console | `- ["TKT TKT-1001", "An open ticket"]` | Example commands on `/t` and the landing page | below |
| `console.workspaces` | console | `workspaces: config/workspaces.yaml` | Starter workspaces | below |
| `console.help` | console | `help: config/help.yaml` | Help-centre cards and F1 targets | [Guides and help](#guides-and-help) |
| `console.monitors` | console | `Queue watch: [ { kind: ticket, id: TKT-1001 } ]` | Starter monitors | below |

Values may use `${VARIABLE:default}`, resolved from the environment when the server starts
(`root: ${HELPDESK_DIR:./data/helpdesk}`).

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
| `sutras` | `sutras` | Sutras (`*.sutra.yaml`), scanned recursively; any other `.yaml`, `.yml` or `.sutra.md` file there is reported (`DRS-2004`) |
| `formats` | `config/formats.yaml` | extra named formats (`formats: { temp1: { type: number, decimals: 1, suffix: " °C" } }`) |
| `semantics` | `config/semantics.yaml` | inference hints: `roles:` (a field-name regex → format, tone, strip weight) and `idFields:` |
| `samples` | `samples` | sample documents for the built-in `demo` source |

### `columns`: the key fields of a pick list

When a command names several entities (`TRD T-100`, `CPTY north`, `TRD productType=Revolver`), the user gets a
**pick list**: a table with one row per entity. `columns:` says which fields of each kind appear beside the id,
in order. From `packs/trading/pack.yaml` and `packs/banking-core/pack.yaml`:

```yaml
columns:                        # KIND: [field, …]  document paths, as in a search ("counterparty.name" works too)
  trade:
  - productType
  - direction
  - currency
  - notional
  - mtm
  - maturityDate
  - book
```

```yaml
columns:
  counterparty: [name, rating, sector, country, netMtm]
  book: [name, deskName, tradeCount, mtm, dv01]
```

With these, `TRD T-100 <GO>` shows:

```text
TRD      Product type   Direction      Currency  Notional      MTM (USD)    Maturity date  Book
T-10001  IRS_FIXFLOAT   Receive fixed  AUD       242,000,000   1,875,863    2032-06-25     BOOK-RATES-3
T-10002  IRS_FIXFLOAT   Pay fixed      EUR       110,000,000   1,761,589    2031-04-15     BOOK-RATES-1
…
```

How the columns of a pick list or search are chosen:

1. the fields the command itself uses, in the order they appear (`TRD currency=usd order by mtm desc` puts
   *Currency* and *MTM (USD)* first);
2. then the kind's `columns:`, skipping any already shown;
3. if the kind has no `columns:`, the first plain (non-list, non-object) fields of the first document,
   leaving out `id` and fields starting with `_`, up to six columns in all.

Column headings come from the label taxonomy (*MTM (USD)* for `mtm`), like every other label. A path may be
written `mtm` or `$.mtm`; both mean the same.

Rules:

- `columns:` is optional. Only `trade`, `counterparty` and `book` have it in the shipped packs; every other
  kind uses the automatic columns (for `LCR`: *Lcr ID*, *Legal entity name*, *Lcr*, *Hqla*, …).
- Choose five to seven fields that tell rows apart at a glance: a type, a direction or status, an amount, a
  date, an owner.
- A child pack may give a kind of its parent other columns; the more specific pack wins, as for every other
  key ([Inheritance](#inheritance)).
- In a **generated** pack, set the columns in the generator, never in `pack.yaml`
  ([below](#how-the-shipped-packs-are-generated)). For the banking packs that is the `COLUMNS` table in
  `tools/packgen/banking/make_packs.py`; for the packs built with `tools/packgen/common/packbuild.py` it is the
  `columns=` argument of the pack's `PackSpec` (for example `columns={"lcr": ["legalEntity", "lcr", "hqla"]}` in
  `tools/packgen/liquidity/make.py`), then rerun its `make.py`.

Check what the server uses for a kind:

```bash
curl -s -G http://localhost:18480/api/v1/search --data-urlencode 'q=BOOK limit 1' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["columns"])'
```

You should see `['$.name', '$.deskName', '$.tradeCount', '$.mtm', '$.dv01']`.

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
| `columns.trade` | `drishti.search.columns.trade[i]` |
| `sutras`, `formats`, `semantics` | added to `drishti.rachana.pack-dirs`, `drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files` |
| `samples` | added to `drishti.sources.plugins.demo.settings.dirs` |

`alerts` and `console` are read directly from the manifest (by the server's alert suggestions and by the console).

## Derived kinds: entities computed from other kinds

A derived kind is a kind no source holds: the server computes it from another kind by grouping and adding up. A
book's P&L from its trades, a currency's exposure, a desk's count of open tickets. A pack declares one as a
connector of the built-in plugin `derived` and routes the kind to it. The finance pack ships `book-pnl`
(`BPNL RATES-NY-3 <GO>`), and the trading pack `desk-pnl`, each desk's MTM, one-day P&L and DV01 from its trades
(`DPNL DESK-RATES <GO>`). The finance one:

```yaml
connectors:
  book-totals:
    plugin: derived
    kinds: [book-pnl]
    settings:
      refresh: 30s              # recomputed at most this often per business date (default 30s)
      max-scan: 50000           # members read at most (default 50000)
      book-pnl:
        from: trade             # the kind it is built from (read through the normal routing)
        group-by: $.book        # Rachana-EL over a member: its group, which is the derived entity's id
        where: $.mtm != null    # optional: which members count
        id-field: book          # a field holding the key besides `id` (default: id only)
        members: tradeIds       # the members' ids, sorted (default: members)
        fields:
          tradeCount: count
          mtm: sum $.mtm
          dv01: sum $.dv01
          largestNotional: max $.notional
          worstMtm: min $.mtm
          currencies: distinct $.currency
        rows:                   # optional: one row per member, for a table (field `rows`, or set rows-field)
          trade: $.tradeId
          mtm: $.mtm
routes:
  book-pnl: book-totals
```

- **Keys.** Each value of `group-by` is one entity (a member whose `group-by` is empty, or whose `where` is false,
  counts in no group). The entity's id is the key; the document has `id`, the `id-field`, `derivedFrom`, each field,
  the members' ids, and `rows` when declared.
- **Fields.** `count`; `sum`, `avg`, `min`, `max` over an expression (values that are missing or not numbers are
  skipped); `distinct` (the sorted distinct values); `first` (the first member's value, members in id order).
- **Links.** Declare the members field under `graph.fields` (`tradeIds: { kind: trade, label: Trade }`) and the
  members show as links; an `id-field` that is already a link field (`book`) links the derived entity to its key's.
- **Business dates.** A picked date is computed from that date's members, so a derived kind has history wherever its
  members do, and *Data for* says which day.
- **Cost.** One computation reads every member (up to `max-scan`) once per `refresh` per business date, and is shared
  by every reader and by all the derived kinds of the connector built on the same kind. Health shows `DOWN` with the
  reason when a computation fails; Admin → Caches → Purge recomputes.
- **Everything else is a kind like any other.** Give it a mnemonic, a Sutra (or let inference lay it out), roles,
  alerts and pick-list columns as usual. It shows in search (`BPNL where mtm < 0`) and the type-ahead.
- **Mistakes fail the start.** A missing `group-by`, an unknown aggregate, a field without its expression, an
  expression that does not parse or a kind built from itself puts the connector under *failed to start* in Admin →
  Health, with the reason.

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

Write the guide in Markdown with the copyright header comment at the top. Describe the domain (what each kind
is, how they link, where the data comes from) rather than listing sample records, and include a **Finding
things** section like the one above, so readers know the commands work on their own data too. Links to other help documents with a relative
path (`[Sutra guide](../console/web/guides/sutra-guide.md)`) open inside the help centre.

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

A pack's roles appear in *Admin → Roles* marked **built-in**, read-only: change them in the pack (or, for a
generated pack, in its generator). An administrator can also define new roles there without touching any pack;
the dialog's *Add every kind of a pack* buttons fill in a pack's kinds in one click
([USER_GUIDE.md](USER_GUIDE.md#admin--roles-what-a-role-may-do)).

## Loading a pack while the server runs

Admin → Packs → **Load** brings in a pack that is on disk but not loaded, without anyone touching the machine:

1. The server runs the same check it runs at start-up on the loaded packs plus the new one (inheritance, clashes, the
   manifest). A pack that would not load is refused with the reason, and nothing changes.
2. It records the pack in the **pack overlay**, `data/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY`), a small
   configuration file the server imports at every start:

   ```yaml
   drishti:
     packs:
       added:
         - logistics
   ```

3. It restarts **inside its own process**: the Spring context closes (connectors, caches and live streams too) and
   is built again from configuration, with the overlay's packs added to `drishti.packs.enabled`. The process id does
   not change. Sessions survive (they are signed tokens), and open views reconnect by themselves.
4. If the server cannot start with the new configuration, it puts the overlay back and starts as it was.

**Unload** does the same in reverse for a pack loaded here; packs named in `DRISHTI_PACKS` stay. Both are audited
(`pack-loaded`, `pack-unloaded`). The API: `POST /api/v1/admin/packs/{name}/load` and `…/unload`; the answer says
whether the server is restarting (`"restarting": true`). Only a server started by its `main` (the jar, the
container) restarts in place; elsewhere (tests) the change waits for the next start.

## Pack codes

Every shipped pack has a short `code:`; typed alone on the command line (with `<GO>`) it opens the pack's overview:
every kind you may open, its mnemonic, how many the sources hold for the business date, an example and the key
fields. Each row's mnemonic opens that kind's pick list. Typing the pack's name (`market-data`) works too.

| Pack | Code | | Pack | Code |
|---|---|---|---|---|
| banking-core | `BNK` | | climate-risk | `CLI` |
| market-data | `MKT` | | economics | `ECO` |
| trading | `TRDS` | | genomics | `GENO` |
| market-risk | `MRSK` | | liquidity-risk | `LIQ` |
| counterparty-risk | `CCR` | | operational-risk | `OPR` |
| finance | `FIN` | | politics-society | `POLS` |
| logistics | `LOGI` | | retail-banking | `RTL` |

The same answer as JSON: `curl -s localhost:18480/api/v1/packs/MKT/overview`. In a generated pack set the code in its
generator (`CODES` in `tools/packgen/banking/make_packs.py`, or `code=` in a `PackSpec`), not in `pack.yaml`.

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

### Worked example: a child of two parents (the rightmost wins)

This example is the one `PackLoaderTest.aChildInheritsItsParentsAndTheRightmostParentWins` builds and checks
on every build, so the results below are guaranteed. Four small packs:

```yaml
# packs/base/pack.yaml
pack: base
kinds: [curve]
mnemonics: { CRV: { kind: curve, label: Curve } }
roles: { viewer: { kinds: [curve] } }
graph: { badges: { curve: "'base'" } }
```

```yaml
# packs/left/pack.yaml
pack: left
extends: [base]
kinds: [trade]
mnemonics: { TRD: { kind: trade, label: Left trade } }
roles: { trader: { kinds: [trade] } }
graph: { badges: { curve: "'left'" } }
```

```yaml
# packs/right/pack.yaml
pack: right
extends: [base]
kinds: [quote]
mnemonics: { TRD: { kind: trade, label: Right trade } }
roles: { trader: { kinds: [trade, quote] } }
```

```yaml
# packs/child/pack.yaml
pack: child
extends: [left, right]
kinds: [var]
roles: { viewer: { kinds: [curve, trade, var] } }
```

Start the server with `DRISHTI_PACKS=child`. All four load, and the order, most specific first, is:

```text
child → right → left → base
```

(`right` comes before `left` because it is the rightmost parent; `base` comes once, after both.) Each thing
defined more than once is settled by that order:

| Defined by | Thing | Result | Why |
|---|---|---|---|
| `left` and `right` | mnemonic `TRD` | label *Right trade* | rightmost parent wins |
| `left` and `right` | role `trader` | kinds `[trade, quote]` | rightmost parent wins |
| `base` and `child` | role `viewer` | kinds `[curve, trade, var]` | the child wins over everything |
| `base` and `left` | badge for `curve` | `'left'` | only `left` redefines it, and `left` is more specific than `base` |
| `base` only | mnemonic `CRV` | `curve` | inherited untouched |

The override report (*Admin → Health*, *Overrides*, and `overrides` in `GET /api/v1/admin/health`) lists each
decision, one line per thing, including:

```text
mnemonic TRD: right overrides left
role viewer: child overrides base
```

Now start the server with `DRISHTI_PACKS=left,right` instead, without `child`. `left` and `right` are now
**unrelated**: neither extends the other, and no loaded pack extends both. Their different `TRD` stops the
server:

```text
mnemonic TRD is defined by both pack 'left' and pack 'right', which do not inherit from each other; declare it identically, or make one pack extend the other
```

That is the point of the rule: two packs may disagree only when some pack has said which of them it prefers, by
listing both in its `extends:`.

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

This tutorial builds a complete `helpdesk` pack from an empty folder: three kinds (tickets, agents, clients),
commands, links with badges, F8 impact, pick-list columns, a role, an alert, a Sutra, a guide, starters, sample
data, and finally a real data folder behind a connector. It needs no code and takes about forty minutes. Each
step says what to type and what you should see.

The finished pack:

```text
packs/helpdesk/
├── pack.yaml                         step 2, grown in steps 6–9 and 13
├── samples/
│   ├── catalog.json                  step 3
│   ├── ticket/TKT-1001.json  TKT-1002.json
│   ├── agent/AGT-07.json
│   └── client/CLI-ACME.json  CLI-GLOBEX.json
├── config/
│   ├── semantics.yaml  formats.yaml  step 10
│   ├── help.yaml  workspaces.yaml     step 12
├── sutras/
│   └── ticket.v1.sutra.yaml          step 11
└── guides/
    └── helpdesk.md                   step 12
```

Before you start: the server and console run from the repository root ([QUICKSTART.md](QUICKSTART.md)), and
you know which packs your server loads (`curl -s http://localhost:18480/api/v1/packs`). The helpdesk pack shares
no kinds with any shipped pack, so it can be added to the `finance` demo or to the banking family alike. The
examples below add it to the banking family.

### Step 1. Create the folders

```bash
mkdir -p packs/helpdesk/{samples/ticket,samples/agent,samples/client,sutras,guides,config}
```

### Step 2. The smallest working manifest

Create `packs/helpdesk/pack.yaml`. Every file in the repository carries the copyright header: copy the comment
block at the top of `packs/logistics/pack.yaml`, or run `python3 tools/license_headers.py --fix` when you are
done (the build's `LicenseHeaderTest` fails otherwise).

```yaml
pack: helpdesk                    # must equal the folder name
version: 0.1.0
title: Help desk
description: Support tickets, the agents who work them and the clients who raise them.

kinds: [ticket, agent, client]    # the kinds this pack owns; no other loaded pack may own them

mnemonics:                        # what users type
  TKT: { kind: ticket, label: Ticket }
  AGT: { kind: agent,  label: Agent }
  CLI: { kind: client, label: Client }

graph:
  id-patterns:                    # a bare id is recognised by its prefix
    - { pattern: "^TKT-", kind: ticket }
    - { pattern: "^AGT-", kind: agent }
    - { pattern: "^CLI-", kind: client }

console:
  examples:                       # shown on /t; also what DomainPacksTest-style tests open
    - ["TKT TKT-1001", "An open ticket · history and agent"]
    - ["AGT AGT-07", "An agent · inferred"]
```

### Step 3. Sample data

The built-in `demo` source serves every loaded pack's `samples/` folder, so the pack works before you have a real
data store. `samples/catalog.json` lists every sample; each entry needs all four fields. The `title` and
`subtitle` are what the suggestions show.

`packs/helpdesk/samples/catalog.json`:

```json
[
  { "kind": "ticket", "id": "TKT-1001", "title": "TKT-1001", "subtitle": "Ticket · Cannot sign in · High" },
  { "kind": "ticket", "id": "TKT-1002", "title": "TKT-1002", "subtitle": "Ticket · Invoice missing · Normal" },
  { "kind": "agent",  "id": "AGT-07",   "title": "AGT-07",   "subtitle": "Agent · Priya N. · Identity" },
  { "kind": "client", "id": "CLI-ACME",   "title": "CLI-ACME",   "subtitle": "Client · Acme Freight Ltd" },
  { "kind": "client", "id": "CLI-GLOBEX", "title": "CLI-GLOBEX", "subtitle": "Client · Globex Retail plc" }
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
  "client": "CLI-ACME",
  "clientName": "Acme Freight Ltd",
  "ageHours": 30,
  "history": [
    { "date": "2026-09-28", "event": "Opened by client" },
    { "date": "2026-09-29", "event": "Assigned to AGT-07" },
    { "date": "2026-09-30", "event": "Client replied" }
  ],
  "_meta": { "source": "helpdesk", "generation": 1, "live": true, "walk": { "ageHours": 1 } }
}
```

`packs/helpdesk/samples/ticket/TKT-1002.json`:

```json
{
  "ticketId": "TKT-1002",
  "subject": "Invoice missing",
  "status": "Waiting on client",
  "priority": "Normal",
  "assignee": "AGT-07",
  "client": "CLI-GLOBEX",
  "clientName": "Globex Retail plc",
  "ageHours": 6,
  "history": [ { "date": "2026-09-30", "event": "Opened by client" } ],
  "_meta": { "source": "helpdesk", "generation": 1, "live": false }
}
```

`packs/helpdesk/samples/agent/AGT-07.json`:

```json
{ "agentId": "AGT-07", "name": "Priya N.", "team": "Identity", "openTickets": 2, "shift": "EMEA early",
  "_meta": { "source": "helpdesk", "generation": 1, "live": false } }
```

`packs/helpdesk/samples/client/CLI-ACME.json` (and `CLI-GLOBEX.json` alike, with `"name": "Globex Retail plc"`
and `"tier": "Silver"`):

```json
{ "clientId": "CLI-ACME", "name": "Acme Freight Ltd", "tier": "Gold", "country": "GB",
  "_meta": { "source": "helpdesk", "generation": 1, "live": false } }
```

| `_meta` key | Meaning |
|---|---|
| `source` | the source system named in *How this view was built* and in `F9` |
| `generation` | a version number shown with the source |
| `live` | `true`: the document ticks while someone watches it |
| `walk` | `{ field: step }`: top-level numbers that random-walk by up to `step` on each tick (here the ticket's age) |

Every catalogue entry must have its file; a missing file stops the demo source from starting.

### Step 4. Load it

Packs load when the server starts. Stop the server (Ctrl+C in its terminal) and start it with the pack added:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
DRISHTI_PACKS=market-risk,counterparty-risk,helpdesk \
  java -jar drishti-server/target/drishti-server-1.11.0-exec.jar
```

Check it loaded:

```bash
curl -s http://localhost:18480/api/v1/packs | python3 -c 'import json,sys; print([p["name"] for p in json.load(sys.stdin)])'
```

You should see `helpdesk` in the list, after the banking packs. If the server stopped instead, the message says
why (a typo in a folder name, a kind another pack owns, a clash with an unrelated pack): see
[TROUBLESHOOTING.md](TROUBLESHOOTING.md#building-and-starting).

The console needs no restart: it asks the server which packs are on, and refreshes its own copy of each pack's
`console:` keys within a minute.

### Step 5. Open it (inference only)

In the terminal (`/t`):

1. Type `TK`. The dropdown offers `TKT` *Ticket*. Type `TKT TKT-1`: it offers `TKT-1001` and `TKT-1002` with
   their subtitles.
2. Type `TKT TKT-1001` and press Enter. There is no Sutra yet, so **inference** lays the ticket out: the strip
   holds the plain fields it scores highest (such as *Subject*, *Status*, *Priority*), `history` becomes a ladder
   (rows led by a date), and *How this view was built* says `inference only`.
3. `AGT-07` and `CLI-ACME` appear as plain text: nothing says yet that they are ids of other kinds.
4. Type `TKT TKT-100` and press Enter: a pick list, *Pick a ticket*, `2 of 2 tickets match`, with the first
   plain fields of a ticket as columns. Type `TKT-1001` on its own and press Enter: the bare id opens the ticket,
   because of the `^TKT-` pattern.

See [INFERENCE.md](INFERENCE.md) for how inference decides.

### Step 6. Links and badges

Add to `graph:` in `pack.yaml`:

```yaml
  fields:                         # a field whose value is another entity's id becomes a link
    assignee: { kind: agent,  label: Assigned agent }
    client:   { kind: client, label: Client }
  badges:                         # Rachana-EL, evaluated on the TARGET of the link
    agent:  "$.openTickets + ' open'"
    client: "$.tier"
```

Restart the server and open `TKT TKT-1001` again. *Linked entities* now lists **Assigned agent** `AGT-07` with
the badge `2 open`, and **Client** `CLI-ACME` with `Gold`. Click `AGT-07`: the agent opens, and the breadcrumbs
read `← TKT-1001 / AGT-07`.

A link field name means **one kind everywhere**: if another loaded pack also declares a `client` field for a
different kind, the server refuses to start. Pick names that fit your domain (`assignee`, not `owner`, if
`owner` is taken).

### Step 7. Impact (F8)

Add under `graph:`:

```yaml
  impact:
    follow: [client]              # roll dependents up through these link fields
    measures: { ticket: "$.ageHours" }   # Rachana-EL per dependent kind, summed per group
    formats:  { ticket: amount0 }
```

Restart, open `AGT AGT-07` and press `F8`. You should see *Impact of AGT-07*:

- **Depends on it directly:** *Ticket · 2*: `TKT-1001` and `TKT-1002`, which name the agent in `assignee`,
  with their ages summed in the group's header.
- **Rolls up into:** *Client · 2*: `CLI-ACME` and `CLI-GLOBEX`, reached through the tickets' `client` field
  (shown in the **Via** column). Clients have no measure, so they are listed without one.

That answers *"which clients are affected if Priya is off sick?"*. Impact finds dependents through the sources'
reverse lookups; the `demo` source has them, as do the lake and database connectors. See the
[Impact guide](../console/web/guides/impact.md).

### Step 8. Pick-list columns

Add at the top level of `pack.yaml`:

```yaml
columns:                          # key fields beside each id in pick lists and searches
  ticket: [subject, status, priority, assignee, ageHours]
  agent:  [name, team, openTickets]
  client: [name, tier, country]
```

Restart and type `TKT TKT-100`, then Enter. The pick list now shows *Subject*, *Status*, *Priority*, *Assignee*
and the age for each ticket. Try `TKT status=open`: one match, so `TKT-1001` opens at once. Try
`TKT priority != high order by ageHours desc`: a list again.

### Step 9. A role and an alert suggestion

```yaml
roles:
  support: { kinds: [ticket, agent, client] }           # opens the pack's kinds, nothing else
  support-lead: { kinds: [ticket, agent, client], raw: true }   # and sees F9 unmasked

alerts:
  - kind: ticket
    name: "Open more than a day"
    when: "$.ageHours > 24 && $.status != 'Closed'"
    severity: warn
    message: "${$.ticketId}: open ${fmt($.ageHours, 'amount0')} h (${$.clientName})"
```

- Roles take effect when sign-in is on. They appear in *Admin → Roles*, marked **built-in**; give them to users
  in *Admin → Users* ([USER_MANAGEMENT.md](USER_MANAGEMENT.md)).
- The alert appears as a suggestion on the Alerts page when the kind is `ticket`. Open `TKT TKT-1001`, click
  **Alert**, and pick *Open more than a day*: the form fills in. Save it: the ticket's age is about 30 (it ticks),
  so the bell shows an alert at once, reading like `warn TKT-1001 TKT-1001: open 31 h (Acme Freight Ltd)`.

### Step 10. Vocabulary: labels and formats for inference

`packs/helpdesk/config/formats.yaml`, a format of your own:

```yaml
formats:
  age0: { type: number, decimals: 0, suffix: " h" }
```

`packs/helpdesk/config/semantics.yaml`, hints that inference and labels use:

```yaml
roles:                            # a field-name pattern → how inference shows such fields
  - { role: age, pattern: "hours$", fmt: age0, weight: 80 }   # ageHours, slaHours, … shown as "30 h"
labels:                           # field → label, everywhere (strips, tables, pick-list headings)
  ageHours: Age (h)
  assignee: Agent
idFields: ["ticketId", "agentId", "clientId"]
```

Restart. In the inferred ticket view the age now reads `30 h`, and the pick list's column is headed *Age (h)*
and *Agent*. Labels are optional: a field without one is humanised (`openTickets` → *Open tickets*).

### Step 11. A Sutra: the layout you want

Inference is a good start; a **Sutra** fixes the layout. Create `packs/helpdesk/sutras/ticket.v1.sutra.yaml`.
It is one YAML document: the copyright header as `#` comments, then `rachana: 1` (the version of the Rachana
language), the name, the version and the layout. Say what the layout is for in `description:` (one paragraph) and
anything a reviewer should know in `notes:`; neither changes the view.

```yaml
rachana: 1
sutra: ticket
version: 1
description: A support ticket with its history, agent and client.
match: { kind: ticket, priority: 10 }
title: { pill: "Ticket", id: $.ticketId, with: $.clientName }
strip:
  - { label: Subject, bind: $.subject }
  - { label: Status, bind: $.status, tone: status }
  - { label: Priority, bind: $.priority }
  - { label: Age, bind: $.ageHours, fmt: age0, emphasis: true }
  - { label: Agent, bind: "link($.assignee, 'agent')" }
  - { label: Client, bind: "link($.client, 'client')" }
panels:
  - id: history
    kind: ladder
    title: History
    code: HIST
    key: F2
    rows: $.history
    highlight: "#index == size($.history) - 1"
    columns:
      - { label: Date, bind: "@.date", fmt: date }
      - { label: Event, bind: "@.event" }
  - { id: built, kind: provenance, title: How this view was built }
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys: { F7: "link($.assignee, 'agent')", F9: raw }
notes: |
  The strip leads with status and age, the two things an agent triages by.
  The history is a ladder with the latest event lit, so a stalled ticket stands out.
```
Pack Sutras are watched: the server picks the file up within about a second, without a restart. Check:

```bash
curl -s http://localhost:18480/api/v1/sutras/problems
```

You should see `{}`. Open `TKT TKT-1001`: the title reads `[Ticket] TKT-1001 with Acme Freight Ltd`, the strip
has your six figures, *History* is on `F2`, `F7` opens the agent, and *How this view was built* says
`Sutra ticket v1 + inference`. A mistake (say `kind: ladderr`) is reported with its line instead, and the view
keeps the last good version: see [runbooks/sutra-broken.md](runbooks/sutra-broken.md).

Writing Sutras is taught in the [Sutra guide](../console/web/guides/sutra-guide.md) and
[RACHANA_GUIDE.md](RACHANA_GUIDE.md); every key is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md). Studio's
**Start from inference** turns the inferred view into a Sutra you can edit, and its editor completes keys,
panel kinds, options, formats and the kinds this server serves from the schema at `GET /api/v1/rachana/schema`.

### Step 12. Guide, help card, starters

`packs/helpdesk/guides/helpdesk.md` (Markdown, header comment first). Describe the domain, not the samples:

```markdown
# The help-desk pack

Tickets raised by clients and worked by agents.

## Kinds

| Kind | Mnemonic | What it is |
|---|---|---|
| ticket | `TKT` | One support request, from opening to closing |
| agent | `AGT` | A person who works tickets |
| client | `CLI` | An organisation that raises tickets |

## Finding things

| Command | Does |
|---|---|
| `TKT <id> <GO>` | Opens that ticket. A bare id (`TKT-…`) works too. |
| `TKT <start of an id> <GO>` | A pick list; one match opens. `*` is a wildcard; case never matters. |
| `TKT <field>=<value> <GO>` | Lists by value: `TKT status=open`, `TKT ageHours > 24 order by ageHours desc`. |
| `TKT <GO>` | Lists every ticket. |
```

`packs/helpdesk/config/help.yaml`, the card in the help centre and the guide `F1` opens on the terminal:

```yaml
guides:
  - { slug: helpdesk-pack, category: start, title: "The help-desk pack", kind: guide, icon: headset,
      file: guides/helpdesk.md, summary: "Tickets, agents and clients." }
contextual:
  terminal: helpdesk-pack
```

`packs/helpdesk/config/workspaces.yaml`, a starter workspace:

```yaml
templates:
  Ticket desk:
    description: A ticket beside its agent.
    layout: "1+2"
    panes:
      - { ref: { kind: ticket, id: TKT-1001 }, title: Ticket }
      - { ref: { kind: agent, id: AGT-07 }, follows: 0, title: Agent }
```

and in `pack.yaml`, under `console:`:

```yaml
  workspaces: config/workspaces.yaml
  help: config/help.yaml
  monitors:
    Queue watch: [ { kind: ticket, id: TKT-1001 }, { kind: ticket, id: TKT-1002 } ]
```

Restart the server, wait a minute, then check: *Help → Help centre* has a card *The help-desk pack*;
*Views → Workspaces* offers *Ticket desk*; *Views → Monitors* offers *Queue watch*, where `TKT-1001`'s age
ticks.

### Step 13. Real data: a connector and a route

Samples are for demos. Real tickets live somewhere else; here, JSON files that a ticketing system exports to a
folder every few minutes. The `file` plugin reads such a folder ([PLUGIN_GUIDE.md](PLUGIN_GUIDE.md#file)).

1. Create the folder and one exported ticket, `data/helpdesk/ticket/TKT-2001.json` (the kind is the folder name,
   the id the file name; no `_meta` needed):

   ```json
   { "ticketId": "TKT-2001", "subject": "VPN drops every hour", "status": "Open", "priority": "High",
     "assignee": "AGT-07", "client": "CLI-ACME", "clientName": "Acme Freight Ltd", "ageHours": 3,
     "history": [ { "date": "2026-09-30", "event": "Opened by client" } ] }
   ```

2. Declare the connector and route tickets to it, in `pack.yaml`:

   ```yaml
   connectors:
     helpdesk-store:
       plugin: file
       enabled: ${HELPDESK_STORE_ENABLED:true}
       kinds: [ticket]
       settings:
         root: ${HELPDESK_DIR:./data/helpdesk}
         rescan-seconds: 30
   routes:
     ticket: helpdesk-store          # tickets are read from this connector first
   ```

3. Restart the server and check the connector:

   ```bash
   curl -s http://localhost:18480/api/v1/sources | python3 -c 'import json,sys; [print(s["name"], s["kinds"], s["health"]) for s in json.load(sys.stdin)["sources"] if s["name"]=="helpdesk-store"]'
   ```

   You should see `helpdesk-store ['ticket'] UP`. A wrong folder gives `DOWN: no directory …`.

4. Type `TKT TKT-2001` and press Enter. The ticket opens with your Sutra, and *How this view was built* names
   `helpdesk-store` as the source. `TKT-1001` still opens from the samples.
5. Drop more files into the folder: they open at once, and appear in suggestions and pick lists after the next
   rescan (30 s).

For a database, a lake, Kafka or S3 instead, only the `plugin` and `settings` change; see
[PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) and, for a pack reading from several stores, its last section. A site can
point the connector elsewhere without touching the pack: `HELPDESK_DIR=/srv/exports/tickets`, or
`drishti.sources.connectors.helpdesk-store.settings.root` in its own configuration.

### Step 14. Check it, as the build does

```bash
python3 tools/license_headers.py --fix        # adds the header to any new file that lacks one
./mvnw -q -pl drishti-rachana -am test -Dtest=PackSutrasTest -Dsurefire.failIfNoSpecifiedTests=false   # every pack's Sutras load
```

`PackSutrasTest` finds every folder under `packs/` that has a `sutras/` folder, so your pack is tested without
registering it anywhere. See [Testing a pack](#testing-a-pack) for the rest.

### What you built, key by key

| Step | Key | What the user gets |
|---|---|---|
| 2 | `pack`, `version`, `title`, `description`, `kinds`, `mnemonics`, `graph.id-patterns`, `console.examples` | `TKT TKT-1001`, `TKT-1001` alone, suggestions, examples on `/t` |
| 3 | `samples/` | data with no database |
| 6 | `graph.fields`, `graph.badges` | links between tickets, agents and clients, with badges |
| 7 | `graph.impact` | F8 on an agent: tickets, then clients |
| 8 | `columns` | useful pick lists |
| 9 | `roles`, `alerts` | access control; one-click alert rules |
| 10 | `config/formats.yaml`, `config/semantics.yaml` | `30 h`, *Age (h)* |
| 11 | `sutras/` | the layout you designed |
| 12 | `guides/`, `console.help`, `console.workspaces`, `console.monitors` | help card, F1, starters |
| 13 | `connectors`, `routes` | real data |

To generate a much larger pack (many kinds, consistent documents, Sutras, a guide and a Delta Lake) from a short
Python description, follow [Tutorial 5 · Build a domain pack](../console/web/guides/build-a-pack.md). That is how
every pack after the banking family was made (`tools/packgen/common/packbuild.py`).

## Versioning and upgrading a pack

**The pack's `version`** (`version: 1.2.0`) is a label: it is shown on About, *Admin → Health* and *Admin →
Packs*, and nothing else depends on it. Raise it whenever the pack changes, so an administrator can tell which
copy a server runs:

```bash
curl -s http://localhost:18480/api/v1/packs | python3 -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin)]'
```

**Sutras carry their own versions** (`version: 2` inside the file, and `ticket.v2.sutra.yaml` as the
file name by convention). The highest version of each Sutra name is the one used. Keep the old file while
views may still need it to compare, or delete it; a name and version defined twice is an error (`DRS-2028`).

**What a change needs**, by part of the pack:

| You changed | It takes effect |
|---|---|
| a Sutra (`sutras/`) | within about a second (hot reload), on every server reading that folder |
| `pack.yaml` (any key), `config/formats.yaml`, `config/semantics.yaml` | at the next server restart |
| `samples/` | at the next server restart (the demo source reads them when it starts) |
| `console:` keys, `guides/`, `config/help.yaml`, `config/workspaces.yaml` | in the console within a minute |
| data behind a connector | at once, or at the connector's next rescan |

**Upgrading a pack on a running installation**, step by step:

1. Read the pack's change notes (its guide, or the generator's commit). Look for renamed kinds, mnemonics or
   link fields: saved monitors, workspaces and alert rules refer to kinds and ids, and a renamed kind leaves
   them pointing at nothing.
2. Replace the folder `packs/<name>/` with the new version (for a repository checkout: `git pull`).
3. If it adds packs it extends, nothing else is needed: parents load with their child.
4. Restart the server. Watch its log for the pack loader's refusals (a kind now owned by two packs, a clash
   with an unrelated pack).
5. Check *Admin → Health*: the pack shows its new version, `sutraProblems` is empty, and its connectors are
   up. Check *Admin → Packs*: a pack switched off before the upgrade stays off (the switch is kept by name).
6. Open one of its example commands from `/t`.

**Removing a pack:** take it out of `DRISHTI_PACKS` and restart. Saved monitors, workspaces and alert rules that
refer to its kinds stay saved, but those entities cannot be opened until the pack returns. To hide a
pack without a restart, switch it off in *Admin → Packs* instead.

## Testing a pack

These checks cover a pack. The Java tests run on every `./mvnw verify`; the drill (`tools/drill.sh`) runs them, plus the
generator checks, before anything reaches `main`.

| Check | Where | What it proves | Picks up a new pack by itself? |
|---|---|---|---|
| `PackLoaderTest` | `drishti-packs` | the loader: manifests, inheritance order, overrides, refusals (clashes, cycles, missing packs) | — (tests the rules, with packs it writes itself) |
| `PackSutrasTest` | `drishti-rachana` | every pack's Sutras parse and validate with zero problems, and every `name@version` is unique | yes: every `packs/*/sutras/` |
| `ImperfectDataTest` | `drishti-server` | every Sutra of every pack survives missing fields, wrong types and flipped shapes: the view still builds, and empty panels say *No data available* | yes |
| `DomainPacksTest` | `drishti-server` | the seven generated domain packs load together, and every example command they advertise opens a full view built by a Sutra, every panel filled, no link missing | no: it names the packs it tests |
| `BankingPacksTest` | `drishti-server` | enabling two risk packs brings the packs they extend, their Sutras and their data-domain connectors | no |
| generator checks | `tools/packgen/*/make*.py --check` | generated files are exactly what the generator writes | yes, for generated packs |
| `LicenseHeaderTest` | `drishti-it` | every file carries the copyright header | yes |

Run the pack-related tests alone while you work:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q -pl drishti-packs -am test -Dtest=PackLoaderTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl drishti-rachana -am test -Dtest=PackSutrasTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl drishti-server -am test -Dtest='ImperfectDataTest,DomainPacksTest' -Dsurefire.failIfNoSpecifiedTests=false
```

To have your pack's examples tested like the shipped ones, add its name to the list in
`DomainPacksTest.everyAdvertisedExampleOpensAFullView` and to the `drishti.packs.enabled` property at the top of
that class. The test then opens each `console.examples` command and fails if a view is laid out by inference
only, if a panel is empty, or if a link points at an entity no source holds.

A manual check after any change takes a minute:

1. `curl -s http://localhost:18480/api/v1/sutras/problems` prints `{}`.
2. *Admin → Health* shows the pack **OK**, with no Sutra problems, no connectors down, and the overrides you
   expect (and no others).
3. Each `console.examples` command opens, and its *How this view was built* names a Sutra.
4. `<MN> <GO>` for each mnemonic gives a pick list with sensible columns.

## How the shipped packs are generated

Most shipped packs are **generated**: a Python program writes their `pack.yaml`, Sutras, samples, guides and
help cards from one description. Each generated file says so in a comment near the top:

```yaml
# Generated by tools/packgen/banking/make_packs.py from the taxonomy. Edit the generator, not this file.
```

| Packs | Generator |
|---|---|
| `banking-core`, `market-data`, `trading`, `market-risk`, `counterparty-risk` | `tools/packgen/banking/`: `make_packs.py` the manifests, `make_sutras.py` the 170 Sutras, `make_docs.py` the guides, `make_data.py` the documents and the lake |
| `liquidity-risk`, `climate-risk`, `operational-risk`, `retail-banking`, `genomics`, `politics-society`, `economics` | `tools/packgen/<area>/make.py` (`liquidity`, `climate`, `oprisk`, `retail`, `genomics`, `politics`, `economics`), all on the common builder `tools/packgen/common/packbuild.py` |
| `finance`, `logistics` | hand-written; their samples come from `packs/<name>/tools/` |

### Never edit a generated file by hand

A hand edit to a generated `pack.yaml`, Sutra, sample or guide is lost the next time the generator runs, and
before that it **fails the drill**. `tools/drill.sh`, which must pass before anything is merged to `main`,
runs every generator in check mode:

```bash
python3 tools/packgen/banking/make_packs.py --check
python3 tools/packgen/banking/make_sutras.py --check
python3 tools/packgen/retail/make.py --check
```

When everything is in step you should see:

```text
5 pack manifests up to date
170 Sutras up to date
retail-banking: 159 files up to date
```

After a hand edit to `packs/trading/pack.yaml`, the first command stops with
`pack manifests out of date; run make_packs.py: ['packs/trading/pack.yaml']`. The packbuild generators also
refuse files they did not write: an extra Sutra dropped into `packs/retail-banking/sutras/` gives
`retail-banking pack out of date; run tools/packgen/retail/make.py. stale=[] extra=[…]`.

### Changing a generated pack, step by step

Example: show the trader's name in trade pick lists.

1. Find the generator named in the file's comment: `tools/packgen/banking/make_packs.py`.
2. Change its input. Here, the `COLUMNS` table near the top:

   ```python
   COLUMNS = {"trading": {"trade": ["productType", "direction", "currency", "notional", "mtm", "maturityDate", "book", "trader"]},
   ```

3. Run the generator without `--check`. It rewrites the files:

   ```bash
   python3 tools/packgen/banking/make_packs.py
   ```

   You should see `wrote 5 pack manifests: banking-core, market-data, trading, market-risk, counterparty-risk`.
4. Check the result: `git diff packs/trading/pack.yaml` shows `- trader` added under `columns: trade:`.
5. Restart the server (packs load at start-up), type `TRD T-100` and press Enter: the pick list has a
   *Trader* column.
6. Commit the generator **and** the files it wrote, together. `--check` now passes.

### Changing a pack you do not own

To change a shipped pack for your site without touching its files at all, use one of these instead:

| You want | Do this |
|---|---|
| A different layout for one kind | Put a Sutra with a higher `version` in the site Sutra folder (`./sutras`, `DRISHTI_SUTRAS`), or edit it in Studio |
| Other mnemonics, labels, columns, connectors or routes | A small pack of your own that `extends` the shipped one ([Inheritance](#inheritance)) |
| A connector pointed elsewhere, or switched off | Site configuration: `drishti.sources.connectors.<name>.…` ([CONFIGURATION.md](CONFIGURATION.md)) |
| A pack hidden from everyone | *Admin → Packs* → **Switch off** ([above](#switching-packs-off-and-on-admin--packs)) |

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
