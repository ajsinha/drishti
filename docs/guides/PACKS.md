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

This guide is for users and administrators. It explains, with examples:

1. [What a pack is](#what-a-pack-is), and [every pack that ships](#the-packs-that-ship), with its commands.
2. [How to load packs](#turning-packs-on), [switch them off and on for everyone](#switching-packs-off-and-on-admin--packs)
   and [who sees which pack](#who-sees-which-pack).
3. [Loading a pack while the server runs](#loading-a-pack-while-the-server-runs), [pack codes](#pack-codes) and
   [upgrading or removing a pack](#upgrading-and-removing-a-pack).

**Writing a pack** (the manifest key by key, links, roles, Sutras, samples, tests, inheritance, generators, the signed
registry, exporting from the Build workbench) is in the [Pack developer guide](PACK_DEVELOPER_GUIDE.md), which builds a help-desk
pack step by step.

## What a pack is

A pack is configuration and content only. It contains no Java and no Python that the server runs (its Calc snippets,
`python/*.py`, run in the user's browser; see [Calc](PACK_DEVELOPER_GUIDE.md#calc-python-snippets)). Here is the
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
| `trading` | `banking-core`, `market-data` | `TRD` trade (125 products in ten asset classes) | `TRD MX-20000001` |
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
them in (see [Inheritance](PACK_DEVELOPER_GUIDE.md#inheritance)).

## Turning packs on

The server reads one setting, `drishti.packs.enabled`, from the environment variable `DRISHTI_PACKS` (a
comma-separated list; default `finance`). Packs are looked for in `drishti.packs.dir` (`DRISHTI_PACKS_DIR`,
default `./packs`, relative to the directory you start the server in).

1. Stop the server.
2. Start it with the packs you want. From the repository root:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   DRISHTI_PACKS=finance,logistics java -jar drishti-server/target/drishti-server-1.17.1-exec.jar
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
`drishti-console/config/application.yaml` is only a fallback for when the server cannot be reached.) If you moved the
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
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) for the user dialog, roles, and where assignments are stored
(`drishti_user_pack`).

**To choose which packs you see** (any user with more than one pack): click the round box tool in the top bar
(it shows how many packs you have on), tick the packs (**all** and **none** help), and click **Apply**. The choice is saved to your account. (For programs: `GET /api/v1/me/packs` answers
`{"assigned": [...], "active": [...]}` for the signed-in user, and `PUT` with `{"active": [...]}` chooses.)

**Enforcement is on the server.** A kind owned by a pack that is not active for you cannot be opened: the view
answers `DRS-5002` (forbidden). Its mnemonics, suggestions, examples, starter workspaces, guides and alert
suggestions disappear too. Kinds no pack owns are not affected. A user who may see `market-risk` may also open the
kinds of the packs it extends (a VaR result links to its trades and books).

Roles work on top of packs: a role lists the kinds it may open (see [Roles and field masks](PACK_DEVELOPER_GUIDE.md#roles-and-field-masks)).

## Loading a pack while the server runs

Admin → Packs → **Load** brings in a pack that is on disk but not loaded, without anyone touching the machine (a pack from a signed registry is
installed the same way: [the registry](PACK_DEVELOPER_GUIDE.md#a-signed-pack-registry-publishing-and-installing)):

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

## Deploying an archive, history, and the data source (Admin → Packs)

Three more things live on **Admin → Packs**; each is written up step by step, with pictures, in
[OPERATIONALISING.md, section 17](OPERATIONALISING.md#17-deploy-from-admin--packs-change-a-data-source-history-and-roll-back).

- **Deploy an archive.** Upload a `.tar.gz` made by `drishti.py pack bundle` or `pack make` (the pack only, never data). The server checks the
  checksums and manifest, that no path leaves the pack folder, the server version it needs, the Sutras (lint and tests), the packs it extends, and an
  optional signature by a trusted publisher; shows what it **changes** from the running version (breaking: kinds or mnemonics removed or renamed;
  selection; layout; change), and, once you confirm, swaps it into `drishti.packs.installed-dir`, keeps the version it replaces and restarts in place.
  If the server cannot start with the new files, the old ones are put back.
- **History and roll back.** Every deployment, rollback and reverted attempt is listed, with the kept versions (`drishti.packs.deploy.keep-versions`, 5)
  and a **Roll back to this** button; `shipped` goes back to the copy that ships with the server.
- **Data source.** Per loaded pack: each connector's settings with the pack default, your override and any site value, a **Test connection** that lists
  the newest business dates and row counts per kind, and **Save and apply** / **Reset**. An edit is saved to `data/packs/settings/<pack>.yaml`, never into
  the pack, so redeploying the pack keeps it. Precedence, highest first: the site (environment variables, `application.yaml`), the override file, the pack.
  Credentials are only environment references (`${NAME}`).

The same from the terminal: `drishti.py server packs deploy|history|rollback|datasource`
([CLI_GUIDE.md](CLI_GUIDE.md#server-packs-deploy-history-rollback-and-datasource)). A personal API token needs the `packs:admin` scope.

## Telling Drishti new data has landed

A pack can say which kinds its ETL is expected to land each business day, by when, and who is told, in a `loads:` section of its `pack.yaml`
(an administrator can override it from Admin → Packs → *Data loads*). The ETL announces each batch with one call (`POST /api/v1/packs/{pack}/loads`
or `drishti.py data landed`); Drishti refreshes, verifies, evaluates alert rules and notifies, and flags a batch that is late on Admin → Health.
The manifest key, with examples: [DATA_LOADS.md](DATA_LOADS.md#6-configuration-reference).

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

The same answer as JSON: `curl -s localhost:18480/api/v1/packs/MKT/overview`. A pack author sets the code with `code:` in `pack.yaml` (in a generated pack, in its generator:
[how the shipped packs are generated](PACK_DEVELOPER_GUIDE.md#how-the-shipped-packs-are-generated)).

## Upgrading and removing a pack

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

## What stays in the core

- The engine: sources, the view pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity.
- Neutral formats (amounts, signed, percent, compact, dates) and neutral semantic roles (amount, value, count,
  rate, date, label).
- The `viewer`, `author`, `approver` and `admin` roles, and the powers a role can hold (`raw`, `author`, `approve`,
  `admin`, `calc`).
- Calc's runtime and its `drishti` module; packs only switch it on and ship snippets.
- The generic help: using the terminal, panel kinds, the Build workbench, workspaces, and every reference.
